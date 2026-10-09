package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 孤儿进程回收（v1.2.104）。
 *
 * ## 为什么需要（主人反馈：AI 起的进程没及时销毁 → 阻塞）
 * 引擎用 `setsid()` 脱离会话（node-pty 需要真实 tty 语义），父进程死后它和它的
 * 后代不会自动消失。而历史上这些进程**继承了 app 的监听 socket** —— 3080/3083
 * 两个 `java.net.ServerSocket` 都不带 `O_CLOEXEC`（2026-10-09 实测：3083 的
 * listen fd 在引擎进程里可见，fdinfo `flags: 04002` = O_RDWR|O_NONBLOCK，
 * 没有 O_CLOEXEC）。链条是：
 *
 *     AI 起一个后台进程 → 它继承 3083 监听 fd → 它成了孤儿还活着
 *     → 引擎重启时新桥 bind 失败（EADDRINUSE）→ notify/scr/say 全哑
 *     → 而且旧代码只打一行 warning，用户只看到功能失灵、没有任何解释
 *
 * 现在两头治：
 *   · **断新孤儿**：`dsh_pty.c` 在 execve 前关掉全部继承 fd（引擎干净了，
 *     它的整棵后代树自然干净）+ 设 `PR_SET_PDEATHSIG(SIGTERM)`。
 *   · **清老孤儿**：本类在引擎启动前扫一遍设备，把已经躺在那儿的收掉。
 *
 * ## 绝不误杀
 * 三个条件同时满足才动手：`ppid == 1`（真孤儿）、cmdline 非空、cmdline 里
 * **明确出现本 app 的 engine 或 dsh-home 绝对路径**。用户自己在 Termux 里跑的
 * 进程路径不含这两段，不会被牵连；`killProcess` 本身也只能杀同 uid 的进程。
 */
object OrphanReaper {

    private const val TAG = "OrphanReaper"

    /** 回收一次；返回杀掉的进程数（0 表示干净） */
    fun reap(ctx: Context): Int {
        val viaApp = reapAsApp(ctx)
        // app 域**看不到 ppid==1 的孤儿**（见 reapWithPrivilege 说明），root 模式下
        // 再从 su 视角补一遍 —— 用户已授权 root，不会弹新的授权框。
        val viaRoot = if (Privilege.getMode(ctx) == PrivMode.ROOT) reapWithPrivileged(ctx) else 0
        return viaApp + viaRoot
    }

    /**
     * app 视角扫描（能看到什么就收什么）。
     *
     * ⚠️ 能力边界（2026-10-09 实测确认）：Android 的 SELinux 只允许 app 访问
     * **自己进程树内**的进程，而孤儿被 init(1) 收养后就脱离了这棵树 —— 实测
     * `/proc` 只列出 61 条且 3 个真孤儿一个都不在其中。所以这一步实际只能覆盖
     * 「父进程刚刚退出、尚未被收养」的窗口期；真正的兜底靠两件事：
     *   ① dsh_pty.c 已让引擎不再继承监听 fd → **孤儿即使活着也占不住端口**；
     *   ② root 模式走 [reapWithPrivileged]。
     */
    private fun reapAsApp(ctx: Context): Int {
        val marks = marksOf(ctx)
        if (marks.isEmpty()) return 0

        val me = android.os.Process.myPid()
        val dirs = runCatching { File("/proc").listFiles() }.getOrNull()
        if (dirs == null) {
            // 列不出 /proc（SELinux 收紧等）→ 静默跳过会让人以为「扫过了很干净」
            Log.w(TAG, "cannot list /proc — orphan reaping skipped")
            return 0
        }

        var killed = 0
        var orphans = 0
        for (p in dirs) {
            val pid = p.name.toIntOrNull() ?: continue
            if (pid == me || !p.isDirectory) continue
            // 只碰孤儿。读不到 stat/cmdline（别的 uid）→ 直接跳过，不做任何猜测。
            val ppid = ppidOf(p) ?: continue
            if (ppid != 1) continue
            orphans++
            val cmd = cmdlineOf(p) ?: continue
            if (cmd.isBlank()) continue
            if (marks.none { cmd.contains(it) }) continue
            Log.w(TAG, "reaping orphan pid=$pid cmd=${cmd.take(140)}")
            runCatching { android.os.Process.killProcess(pid) }
            killed++
        }
        // 每次扫描都留一条汇总：现场排查时能一眼看出「有没有扫到孤儿」
        Log.i(TAG, "orphan scan (app): ${dirs.size} entries, $orphans orphan(s), $killed reaped")
        return killed
    }

    /**
     * 特权视角扫描（root 模式）。
     *
     * app 域看不到脱离进程树的孤儿，su 视角不受这个限制 —— KernelSU/Magisk 设备上
     * 这才是真正收得掉孤儿的那条路。仍坚持「cmdline 必须含本 app 的 engine/dsh-home
     * 绝对路径」，绝不误杀用户自己的进程。
     */
    private fun reapWithPrivileged(ctx: Context): Int {
        val marks = marksOf(ctx)
        if (marks.isEmpty()) return 0
        val out = Privilege.runSu("ps -A -o PID,PPID,ARGS 2>/dev/null") ?: return 0
        var killed = 0
        for (line in out.lineSequence()) {
            // limit=3：ARGS 里含空格，第三段要保留整串
            val t = line.trim().split(Regex("\\s+"), limit = 3)
            if (t.size < 3) continue
            val pid = t[0].toIntOrNull() ?: continue
            if (t[1] != "1") continue
            val cmd = t[2]
            if (cmd.contains("ps -A")) continue
            if (marks.none { cmd.contains(it) }) continue
            Log.w(TAG, "reaping orphan (root) pid=$pid cmd=${cmd.take(140)}")
            Privilege.runSu("kill -9 $pid")
            killed++
        }
        if (killed > 0) Log.w(TAG, "reaped $killed orphan(s) via root")
        return killed
    }

    private fun marksOf(ctx: Context): List<String> = listOfNotNull(
        runCatching { EngineConfig.engineRoot(ctx).absolutePath }.getOrNull(),
        runCatching { EngineConfig.dshHome(ctx).absolutePath }.getOrNull(),
    )

    /**
     * 读 `/proc/<pid>/stat` 的 ppid。
     *
     * 格式形如 `7770 (node) S 1 7770 …`：comm 可能含空格与括号，故从**最后一个 ')'
     * 之后**切；切出的第一段是状态字母，**第二段才是 ppid**
     * （实测踩坑：取 firstOrNull() 会拿到 "S"，toIntOrNull() 恒为 null，
     *  于是所有孤儿都被静默跳过 —— 装机跑了 76ms 却一个没杀才发现）。
     */
    private fun ppidOf(procDir: File): Int? = runCatching {
        File(procDir, "stat").readText().substringAfterLast(") ", "").trim()
            .split(' ').getOrNull(1)?.toIntOrNull()
    }.getOrNull()

    private fun cmdlineOf(procDir: File): String? = runCatching {
        File(procDir, "cmdline").readBytes().toString(Charsets.UTF_8)
            .replace('\u0000', ' ').trim()
    }.getOrNull()
}

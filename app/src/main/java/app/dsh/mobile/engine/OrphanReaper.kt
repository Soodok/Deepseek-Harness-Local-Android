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
        // app 域**看不到 ppid==1 的孤儿**（见 reapWithPrivileged 说明），root 模式下
        // 再从 su 视角补一遍 —— 用户已授权 root，不会弹新的授权框。
        //
        // ⚠️ 但 su 是一次**进程创建 + 提权握手**，在 KernelSU 上还要过一次 daemon，
        // 实测稳定吃 150~250ms。而这一步在绝大多数冷启里都是空跑：孤儿要么不存在，
        // 要么已被上面的 reapAsApp 收掉。所以加一个前置门：
        //   **只有 3080 真的被占着，才值得花 su 的代价去清孤儿。**
        // 端口空闲时直接跳过 —— 既不影响功能（没有孤儿占端口就没有清理需求），
        // 又省掉冷启路径上最大的一笔固定开销。
        val needRoot = Privilege.getMode(ctx) == PrivMode.ROOT && portLooksBusy()
        val viaRoot = if (needRoot) reapWithPrivileged(ctx) else 0
        return viaApp + viaRoot
    }

    /** 端口在 /proc/net/tcp{,6} 里的十六进制表示（4 位大写，如 3080 -> "0C08"） */
    private val listenState = "0A"

    /**
     * 3080 是否有人**在监听**（读 /proc/net/tcp{,6}，不起 socket、不起进程）。
     *
     * 刻意不复用 `EngineSupervisor.portInUse`：那是 connect 探测，端口空闲时在部分
     * 机型上要等满超时才返回；本函数只需要一个「值不值得花 su 代价」的布尔判断，
     * 读内核表更确定（纯内存读取，微秒级）。
     *
     * 只看 LISTEN 状态（0A）而不是「有没有这条连接」——TIME_WAIT 条目会在端口
     * 空闲后继续存在一分钟以上，用它判断会误认为端口被占（实测踩过：
     * 用 `netstat | grep 3080` 判定就绪，结果三次里有一次匹配到 TIME_WAIT）。
     *
     * 读不到时返回 false —— 保守选择「不花 su 代价」：真占了端口的话，
     * 后面 spawnEngine 的 EADDRINUSE 与退避重试仍会兜住。
     */
    private fun portLooksBusy(): Boolean = runCatching {
        val portHex = String.format("%04X", EngineConfig.DEFAULT_PORT)
        for (f in arrayOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val file = File(f)
            if (!file.canRead()) continue
            var hit = false
            file.useLines { seq ->
                for (line in seq.drop(1)) {
                    val cols = line.trim().split(WS)
                    if (cols.size < 4) continue
                    if (cols[3] != listenState) continue
                    val local = cols[1]                    // HEXADDR:HEXPORT
                    val hexPort = local.substring(local.lastIndexOf(':') + 1)
                    if (hexPort.equals(portHex, ignoreCase = true)) { hit = true; break }
                }
            }
            if (hit) return@runCatching true
        }
        false
    }.getOrDefault(false)

    private val WS = Regex("\\s+")

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
     *
     * ⚠️ v1.3.1：**不再只认 `ppid == 1`**（用户报「Root 模式被杀死后二次启动起不来」）。
     * 旧实现的 ppid 过滤有个致命盲区：Root 引擎链是
     * `App → (PTY fork) → su 外壳 → magiskd/ksud → node`，如果 **su 外壳或
     * magiskd 仍活着**（App 被 kill -9 时 PDEATHSIG 只挂到 su 外壳上，node 完全
     * 收不到信号），node 的 ppid 就**不等于 1**，旧实现在这里直接 continue，
     * 于是它带着 uid 0 一直占着 3080 —— 端口清不掉 → 新引擎 EADDRINUSE → 启动失败。
     *
     * 现在改为按「**是不是我们的引擎、且不是当前正在用的那个**」判定，并额外要求
     * 「父进程不在本 app 进程树内」以排除活着的正常引擎：
     *   · cmdline 必含 engine/dsh-home 绝对路径（同旧逻辑，防误杀）
     *   · 排除 lan-web-proxy（同 cmdline 特征，误杀会让局域网访问静默失效）
     *   · 排除 init(1) 之外的**非孤儿**：父进程若属于本 app（uid 相同且是 app 自身
     *     或它的直接子进程），说明是正常运行的引擎，跳过
     *
     * 这样既不漏 Root 孤儿，也不会误杀正在服务的引擎。
     */
    private fun reapWithPrivileged(ctx: Context): Int {
        val marks = marksOf(ctx)
        if (marks.isEmpty()) return 0
        val out = Privilege.runSu("ps -A -o PID,PPID,ARGS 2>/dev/null") ?: return 0
        // 本 app 当前活着的进程（含正在运行的引擎），这些绝不能杀
        val alive = runCatching {
            File("/proc").listFiles()
                ?.mapNotNull { it.name.toIntOrNull() }
                ?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
        var killed = 0
        for (line in out.lineSequence()) {
            // limit=3：ARGS 里含空格，第三段要保留整串
            val t = line.trim().split(Regex("\\s+"), limit = 3)
            if (t.size < 3) continue
            val pid = t[0].toIntOrNull() ?: continue
            val ppid = t[1].toIntOrNull() ?: continue
            val cmd = t[2]
            if (cmd.contains("ps -A")) continue
            if (cmd.contains("lan-web-proxy")) continue
            if (marks.none { cmd.contains(it) }) continue
            // 父进程还活着且是本 app 的进程（含引擎自身/直接子进程）→ 正在服务，跳过。
            // 真孤儿的父进程要么是 1，要么是 app 域之外的服务进程（su/magiskd）。
            if (ppid != 1 && alive.contains(ppid)) continue
            Log.w(TAG, "reaping orphan (root) pid=$pid ppid=$ppid cmd=${cmd.take(140)}")
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

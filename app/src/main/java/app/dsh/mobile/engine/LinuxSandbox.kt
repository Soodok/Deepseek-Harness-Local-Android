package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Linux 环境的「隔离策略」—— 决定容器内的程序怎么被拽进 rootfs。
 *
 * ## 为什么需要两种策略
 *
 * 我们最初只能走 seccomp 自研容器（[Sandbox.SECCOMP]），因为 App 跑在
 * `untrusted_app` 域，`chroot(2)` 直接返回 `EPERM`。seccomp 方案用 cBPF 把
 * 带路径的 syscall 全部 `SECCOMP_RET_TRAP`，在 SIGSYS handler 里把 guest 路径
 * 翻译成宿主路径 —— 免 root，但有代价：**每一次带路径的 syscall 都要多走一趟
 * 信号往返**。
 *
 * 有 root 时 `chroot(2)` 可用，内核直接改根目录，路径翻译由内核完成，没有信号
 * 开销。同一份 `sysbench` 二进制、同一 rootfs、同 20000 次操作的实测
 * （模拟器 dsh34 / x86_64 / Android 14）：
 *
 * | 操作 | chroot | seccomp | 倍数 |
 * |---|---|---|---|
 * | `stat` | 0.551 µs | 1.997 µs | 3.6× |
 * | `open+read+close` | 1.454 µs | 2.825 µs | 1.9× |
 * | `getpid` | 0.079 µs | 0.147 µs | 1.9× |
 * | `fork+wait` | 229.9 µs | 258.0 µs | 1.1× |
 *
 * `getpid` 本来不需要路径翻译也要慢 1.9×，因为 seccomp 方案对**所有** syscall
 * 都要过一次 filter 判定（filter 装在进程上，无法豁免）。这是全套开销的底噪。
 *
 * ## 选择逻辑
 *
 * | 条件 | 策略 | 理由 |
 * |---|---|---|
 * | 用户没开 chroot 开关 | SECCOMP | 保持既有行为，不动无 root 用户 |
 * | 开了但 su 不可用 | SECCOMP + 日志 | **绝不静默假装用了 root** |
 * | 开了且 su 可用 | CHROOT | 实测快 1.9~3.6× |
 *
 * ## chroot 策略的两个必要前提
 *
 * 1. **必须显式建挂载点**：chroot 不会自动挂 `/proc` `/dev` `/sys`，而 Node 与
 *    包管理器都要读它们。挂载动作需要 root，所以和 chroot 一起在同一个
 *    `su -c` 里做。
 * 2. **PATH 必须显式给**：chroot 后 `/etc/profile` 不会被读（`sh` 不是登录
 *    shell），宿主的 PATH（`/system/bin` 等）会被继承下来，容器里一个都找不到。
 *    实测症状是 `uname: not found` —— 看着像 busybox 缺失，实际全是 PATH 问题。
 */
enum class Sandbox {
    /** 免 root：cBPF + SIGSYS 路径翻译（[dsh_alpine](https://github.com/Soodok/dsh-linux-runtime)） */
    SECCOMP,

    /** 需 root：内核原生 `chroot(2)`，无信号开销 */
    CHROOT,
    ;

    companion object {

        private const val TAG = "LinuxSandbox"

        private const val PREFS = "dsh_ui"

        /**
         * 开关键名。
         *
         * 默认 **false**：chroot 需要 root，且它要挂 `/proc` `/dev` `/sys`、改
         * 挂载命名空间，属于「用户明确知情后才该发生」的操作。默认开启会让
         * 无 root 用户每次启动都多一次 su 探测（有 Magisk 弹窗的设备甚至会被
         * 弹窗打扰）。
         */
        private const val KEY_CHROOT = "engine_linux_chroot"

        fun isChrootPreferred(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CHROOT, false)

        fun setChrootPreferred(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CHROOT, on).apply()
            Log.i(TAG, "linux chroot preferred = $on")
        }

        /**
         * 解析这次该用哪种策略。
         *
         * 只在用户开了 chroot 开关时才探测 su（探测有成本，且会触发 root 授权弹窗）。
         *
         * @param probeSu 注入 su 探测，便于单测与探针不起真实 su
         */
        fun resolve(
            ctx: Context,
            probeSu: () -> Boolean = { suAvailable() },
        ): Sandbox {
            if (!isChrootPreferred(ctx)) return SECCOMP
            val ok = runCatching { probeSu() }.getOrDefault(false)
            if (!ok) {
                // ★绝不静默假装用了 root —— 用户开了开关却跑 seccomp，
                //   必须能在日志里看到，否则「为什么没变快」无从排查。
                Log.w(TAG, "chroot preferred but su unavailable — using seccomp sandbox")
                Log.w(TAG, "  su diag: ${diagnoseSu()}")
                return SECCOMP
            }
            Log.i(TAG, "chroot preferred and su available — using chroot sandbox")
            return CHROOT
        }

        /**
         * su 不可用时给出**可操作**的诊断。
         *
         * 只说「su unavailable」用户无从下手：可能是没 root、可能是 Magisk 没放行、
         * 也可能是这台设备的 su **存在但 App 读不到**（AOSP 模拟器把 `/system/xbin`
         * 限给 `shell` 组，实测 `run-as app.dsh.mobile ls /system/xbin/su` 报
         * `Permission denied`，而 root 视角下同一文件完全正常）。
         *
         * 这三种情况的处置完全不同，所以要分开报：
         * - `no-binary`：候选路径一个都不存在 → 设备没 root
         * - `denied`：文件存在但读不了 → 有 root 但没授权给本 App
         * - `not-root`：能执行但拿不到 uid=0 → su 在但拒绝提权
         */
        fun diagnoseSu(): String {
            val paths = listOf(
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
                "/su/bin/su", "/debug_ramdisk/su", "/data/adb/magisk/su", "/data/adb/ksu/bin/su",
            )
            var anyExists = false
            var anyDenied = false
            for (p in paths) {
                val f = java.io.File(p)
                if (f.exists()) anyExists = true
                // exists() 在 SELinux 拒绝时返回 false 而不抛异常，所以要靠 canRead
                if (!f.canRead() && f.exists()) anyDenied = true
            }
            if (!anyExists) {
                // 再判一次「目录级拒绝」：/system/xbin 是 drwxr-x--- root shell，
                // App 连列目录都不行，此时 File.exists() 一律 false。
                if (!java.io.File("/system/xbin").canRead()) {
                    return "no-binary-and-parent-denied(/system/xbin unreadable — AOSP emulator restricts su to shell group)"
                }
                return "no-binary"
            }
            if (anyDenied) return "denied(exists but unreadable — grant root access to this app)"
            val prefix = Privilege.usableSuPrefix()
            if (prefix == null) return "not-root(su found but `su -c`/`su 0` probes all failed — check Magisk superuser list)"
            return "ok(${prefix.joinToString(" ")})"
        }

        /**
         * su 可用的判据。
         *
         * 复用 [Privilege.usableSuPrefix] —— 它已经把「Magisk 的 su 不在 PATH」
         * 「toybox su 不认 -c」这两个历史坑都吃掉了（见其 KDoc 的 v1.2.109 /
         * v1.2.112 事故记录）。这里不另起一套探测，否则两处判据迟早分叉。
         */
        fun suAvailable(): Boolean = Privilege.usableSuPrefix() != null
    }
}

/**
 * chroot 策略的 argv 组装。
 *
 * 与 [EngineConfig.linuxContainerArgv]（seccomp 策略）**契约完全不同**：
 *
 * | | seccomp | chroot |
 * |---|---|---|
 * | 前缀 | `dsh_alpine ROOTFS INTERP GUEST ...` | `su -c "… chroot ROOTFS GUEST …"` |
 * | 路径语义 | ROOTFS 宿主绝对、INTERP/GUEST 是 rootfs 内路径 | 全部是 rootfs 内路径 |
 * | 提权 | 不需要 | 必须 `su -c` 包裹 |
 * | 挂载 | loader 内 bind 表 | 必须显式 `mount --bind` |
 * | 动态链接器 | 必须显式传 interp | 内核按 ELF 自己的 `PT_INTERP` 装载 |
 */
object ChrootArgv {

    private const val TAG = "ChrootArgv"

    /**
     * rootfs 内必须存在的挂载点。chroot 不会自动建这些目录，缺一个
     * `mount --bind` 就失败，而失败发生在 `su` 子进程里 —— 上层只看到
     * 「容器起不来」，很难定位。
     */
    val REQUIRED_MOUNT_POINTS = listOf("/proc", "/dev", "/sys", "/tmp", "/dev/pts")

    /**
     * chroot 场景下容器内应该用的 PATH。
     *
     * ★必须显式设置。chroot 后宿主的 PATH（`/system/bin:/apex/...`）会被继承，
     * 而 rootfs 里没有那些目录 —— 实测 `uname` `cat` `ls` 全部 `not found`，
     * 看起来像 busybox 缺失，实际是 PATH 一个都不对。
     *
     * `/eng/bin` 放最前（v1.7.8）：门脚本装在 engineRoot/bin，引擎启动时
     * [buildEngine] 会把 /eng bind 进 rootfs，执行器 [build] 也会（见 extraBinds）。
     * 缺它时 `dev linux exec "scr dump"` 报 127，而同一命令由引擎自己跑却正常。
     * 未挂载时该目录不存在，shell 解析 PATH 会静默跳过，不影响其它命令。
     */
    const val CONTAINER_PATH =
        "/eng/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /**
     * POSIX sh 单引号转义。
     *
     * 项目里已有一份 [EngineProcess] 的 `private fun shellQuote`，但它是 private
     * 且语义不同（那边是「无特殊字符也加引号」）。这里需要给 `su -c` 拼一整条
     * shell 命令，路径可能含空格（`/data/data/<pkg>/files` 一般不会，但用户改过
     * 的 `DSH_HOME` 可能），所以独立实现并对外可见。
     */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 生成「挂载点就绪」的 shell 片段。
     *
     * `mount --bind` 要求目标目录已存在，且重复挂载会返回 EBUSY —— 用
     * `mountpoint -q` 先判，避免二次调用报错（本函数每次启动都会跑）。
     */
    fun mountPrepScript(rootfs: File): String {
        val r = shellQuote(rootfs.absolutePath)
        return REQUIRED_MOUNT_POINTS.joinToString("; ") { mp ->
            "mkdir -p \"$r$mp\" 2>/dev/null; " +
                "mountpoint -q \"$r$mp\" 2>/dev/null || mount --bind \"$mp\" \"$r$mp\" 2>/dev/null"
        }
    }

    /** 单条 `mount --bind`（幂等：已经挂上就跳过，避免每次启动都报 EBUSY）。 */
    private fun bindScriptOne(rootfs: File, guest: String, host: File): String {
        val r = shellQuote(rootfs.absolutePath)
        val g = shellQuote(guest)
        val h = shellQuote(host.absolutePath)
        return "mkdir -p \"$r$g\" 2>/dev/null; " +
            "mountpoint -q \"$r$g\" 2>/dev/null || mount --bind $h \"$r$g\" 2>/dev/null"
    }

    /**
     * 组装完整 argv：`<su> -c "<prep>; exec env -i … chroot <rootfs> <cmd…>"`。
     *
     * @param rootfs 宿主上的 rootfs 绝对路径
     * @param command 容器内的命令（guest 路径，如 `/bin/sh`）
     * @return 可直接交给 `ProcessBuilder` 的 argv；su 不可用时返回 null
     */
    fun build(
        rootfs: File,
        command: List<String>,
        /**
         * 额外挂载（guest → host）。
         *
         * 给执行器补 /eng 用：门脚本在 engineRoot/bin，不挂进来容器里就没有
         * scr/dev/loop，`dev linux exec` 一律 127（seccomp 模式的同一问题见
         * [EngineConfig.linuxContainerArgv]）。
         */
        extraBinds: List<Pair<String, File>> = emptyList(),
    ): List<String>? {
        require(command.isNotEmpty()) { "command must not be empty" }
        val r = shellQuote(rootfs.absolutePath)
        val inner = command.joinToString(" ") { shellQuote(it) }
        val script = buildString {
            append(mountPrepScript(rootfs))
            extraBinds.forEach { (guest, host) ->
                append("; ")
                append(bindScriptOne(rootfs, guest, host))
            }
            append("; ")
            append("exec env -i ")
            append("PATH=$CONTAINER_PATH ")
            append("HOME=/root TMPDIR=/tmp TERM=xterm-256color ")
            append("chroot $r $inner")
        }
        // ★审计日志：把拼好的 script 原样打出来（转义成一个可 grep 的单行）。
        //   su 对 App 不可见的环境里（如 AOSP 模拟器），chroot 分支走不到，
        //   「命令拼错了没有」无法靠观察行为回答 —— 但可以拿这条日志里的
        //   原文喂给任意有权限的 shell 复跑，从而把「命令是否正确」与
        //   「本进程是否有权限」两件事分开定性。
        Log.i(TAG, "AUDIT script=" + script.replace("\n", "\\n"))
        return wrapSu(script)
    }

    /**
     * 引擎场景的 argv（复用 [LinuxEngineLauncher] 已经算好的 guest 侧参数）。
     *
     * 与 seccomp 版的差异：
     * - 不需要 runner 二进制、不需要 interp（内核按 ELF 自己的 `PT_INTERP` 装载）
     * - bind 表要显式 `mount --bind`，因为不再有 loader 帮忙翻译
     */
    fun buildEngine(
        rootfs: File,
        binds: List<Pair<String, File>>,
        nodePath: String,
        engineArgs: List<String>,
        env: Map<String, String>,
    ): List<String>? {
        val r = shellQuote(rootfs.absolutePath)
        val bindScript = binds.joinToString("; ") { (guest, host) ->
            bindScriptOne(rootfs, guest, host)
        }
        val inner = (listOf(nodePath) + engineArgs)
            .joinToString(" ") { shellQuote(it) }
        val envPart = env.entries.joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" }
        val script = buildString {
            append(mountPrepScript(rootfs))
            if (bindScript.isNotEmpty()) {
                append("; ")
                append(bindScript)
            }
            append("; ")
            append("cd \"$r/root\" 2>/dev/null; ")
            append("exec env -i ")
            append("PATH=$CONTAINER_PATH HOME=/root TMPDIR=/tmp TERM=xterm-256color ")
            if (envPart.isNotEmpty()) {
                append(envPart)
                append(' ')
            }
            append("chroot $r $inner")
        }
        return wrapSu(script)
    }

    /**
     * 用 [Privilege.usableSuPrefix] 给出的形式包一层。
     *
     * 不自己拼 `su -c`：Magisk 的 su 不在 PATH、toybox su 不认 `-c`，两种设备的
     * 正确形式不同，这层适配必须复用既有实现（见 [Privilege.usableSuPrefix] 的
     * v1.2.109 / v1.2.112 事故记录）。
     *
     * ## ★必须显式插入 `sh -c`（实测踩过）
     *
     * 本次实现的第一版是 `prefix + listOf(script)`，直接抛给 su。在 toybox 设备
     * 上立刻炸：
     *
     * ```
     * $ su 0 'for mp in /proc /dev; do echo MP=$mp; done'
     * su: failed to exec for mp in /proc /dev; ...: No such file or directory
     * ```
     *
     * 原因是 toybox 的 `su` 用法是「su 空格 WHO 空格 COMMAND...」，它把后面的
     * 每个参数当成 **argv 元素直接 exec**，**不经过 shell**（`su --help` 原文：
     * "run the given COMMAND"）。所以整条脚本被当成一个可执行文件名。
     *
     * 加上 `sh -c` 后两种设备都成立：
     * - toybox：`su 0 sh -c '<script>'` → `["su","0","sh","-c",script]`
     * - Magisk：`su -c "sh -c '<script>'"` → `-c` 本身就交给 shell
     *
     * 反例（不能用）：`su root -c` + `sh -c` 会变成 `su root -c sh -c …`，
     * toybox 把 `-c` 当 WHO 之后的 COMMAND 首个元素去 exec → 失败。
     * [Privilege.usableSuPrefix] 的探针已经把这个形式筛掉了。
     */
    private fun wrapSu(script: String): List<String>? {
        val prefix = Privilege.usableSuPrefix() ?: run {
            Log.w(TAG, "no usable su — cannot build chroot argv")
            return null
        }
        // 两种 su 形态需要不同的拼接方式，不能一律 + ["sh","-c",script]：
        //
        // - Magisk 形态 `["su","-c"]`：`-c` 后面**只能跟一个字符串**。若拼成
        //   `su -c sh -c '<script>'`，su 会把 "sh" 当命令、剩下的当额外参数，
        //   实际行为依赖实现（Magisk 会透传，toybox 不会）。为确定起见，
        //   在 `-c` 形态下把整条命令收成一个字符串：`sh -c '<script>'`。
        // - toybox 形态 `["su","0"]`：后面每个元素都是 argv，直接 append 即可。
        return if (prefix.last() == "-c") {
            prefix + listOf("sh -c " + shellQuote(script))
        } else {
            prefix + listOf("sh", "-c", script)
        }
    }

    /**
     * 供探针与日志使用的一行摘要（不含敏感路径内容，只描述形状）。
     */
    fun describe(argv: List<String>?): String {
        if (argv == null) return "chroot:unavailable(no su)"
        val head = argv.takeWhile { !it.contains("chroot") }.joinToString(" ")
        return "chroot via [$head]"
    }

    /**
     * 完整命令的**可复核**形式（诊断用）。
     *
     * 与 [describe] 的区别：describe 只报形状（用于常态日志，不刷屏），
     * 这个报全文（用于「用户开了开关但没生效 / 想手工复现」的排查）。
     *
     * 之所以需要它：模拟器等环境下 su 对 App 不可见，chroot 分支永远走不到，
     * 于是「命令拼错了吗」这个问题**没法靠观察 App 行为回答**。把拼出来的
     * 命令整条打出来 + 退出码 + 输出尾巴，就能用任意有权限的 shell 复跑同一
     * 条命令，把「命令对不对」与「这个进程有没有权限」两件事分开定性。
     *
     * ⚠️ 只在开关打开时调用 —— 常态路径不该打这么长的串。
     */
    fun audit(argv: List<String>?): String {
        if (argv == null) return "AUDIT chroot=unavailable"
        return "AUDIT chroot argv=${argv.joinToString("\\u0001")}"
    }
}

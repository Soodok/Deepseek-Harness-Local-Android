package app.dsh.mobile.engine

import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 引擎进程句柄：封装 master fd 的日志泵与退出监听。
 *
 * 日志策略：双写 —— logcat（调试）+ filesDir/engine.log 环形截断（用户可导出反馈）。
 * 退出监听跑在专用线程上阻塞 nativeWaitChild，完成后 complete future。
 */
class EngineProcess private constructor(
    private val masterFd: Int,
    private val logFile: File,
    private val suUsed: Boolean = false,
) {
    private val closed = AtomicBoolean(false)
    val exitFuture = CompletableFuture<Int>()

    /** PTY 跟踪的子进程 PID（su -c 模式下是 su 外壳；真正 node 在其子进程组） */
    val pid: Int get() = Pty.nativeChildPid()

    private val pumpThread = Thread({ pumpLoop() }, "dsh-log-pump").apply {
        isDaemon = true
        start()
    }

    private val waitThread = Thread({
        val status = Pty.nativeWaitChild()
        // ⚠️ v1.2.110：解析并记录退出原因 —— 用户反馈「进程异常退出」且升级路径必现、
        // 卸载重装正常，说明是旧数据/旧状态触发。引擎是被信号杀的（native crash、
        // OOM kill）还是正常退出，这行一眼可见；配合 boot step 日志能圈出是哪一步。
        // waitpid status：低 7 位 = 终止信号（0 = 正常退出），高 8 位 = 退出码。
        val signal = status and 0x7f
        val exitCode = status shr 8
        Log.w(
            TAG,
            "engine exited: status=$status signal=$signal exit=$exitCode" +
                (if (signal != 0) " (killed by signal, likely crash or OOM)" else ""),
        )
        exitFuture.complete(status)
    }, "dsh-waiter").apply {
        isDaemon = true
        start()
    }

    private fun pumpLoop() {
        val maxLogBytes = 2L shl 20 // 2MB 环形截断
        try {
            FileInputStream(FileDescriptor().apply {
                // 反射注入 fd 是 Android 平台惯例（libcore 未提供公开构造）
                val field = FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.setInt(this, masterFd)
            }).use { input ->
                logFile.appendText(
                    "---- engine start ${System.currentTimeMillis()} ----\n" +
                        "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} "
                        + "SDK=${android.os.Build.VERSION.SDK_INT} "
                        + "Android=${android.os.Build.VERSION.RELEASE} ${android.os.Build.VERSION.INCREMENTAL}\n",
                )
                val buf = ByteArray(4096)
                while (!closed.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        val chunk = String(buf, 0, n, Charsets.UTF_8)
                        Log.d(TAG, chunk.trim())
                        if (logFile.length() < maxLogBytes) {
                            logFile.appendText(chunk)
                        } else {
                            // 超限则保留后半段重新起头，避免无限膨胀
                            val tail = logFile.readText().takeLast((maxLogBytes / 2).toInt())
                            logFile.writeText(tail)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (!closed.get()) Log.w(TAG, "log pump ended: ${e.message}")
        }
    }

    /**
     * 优雅停止：TERM → [graceMs] 宽限 → KILL。
     * 用户手动重启走短宽限（默认 10s 太久——引擎若对 TERM 无响应，用户要干等满 10s
     * 才等到 KILL，实测反馈「重启很久很久」）；进程内退出后立即返回，正常情况 <1s。
     * 会话 JSONL 是逐条追加落盘的，缩短宽限只影响极小的尾部窗口，且原本超时也是 KILL。
     */
    fun stop(graceMs: Long = 10_000) {
        if (!closed.compareAndSet(false, true)) return
        val t0 = System.currentTimeMillis()
        Pty.nativeSignalChild(15)
        val deadline = t0 + graceMs
        while (System.currentTimeMillis() < deadline && !exitFuture.isDone) {
            Thread.sleep(50)
        }
        val graceful = exitFuture.isDone
        if (!graceful) Pty.nativeForceKill()
        Log.i(TAG, "engine stop: ${System.currentTimeMillis() - t0}ms (graceful=$graceful, grace=$graceMs)")
        // 【v1.2.22 事故】root 模式（su -c）下 PTY 只能杀到 su 外壳，exec node 的
        // 孙进程成孤儿继续霸占 3080 → 普通引擎 EADDRINUSE 反复重启（「异常退出」循环
        // 但页面/AI 正常，服务的是孤儿）。进程组 + 子进程双保险击杀。
        if (suUsed && pid > 0) {
            // v1.2.108：改走 Privilege.runSu —— 旧实现硬编码 `su -c`（toybox 风格 su 会报
            // `invalid uid/gid '-c'` 并被 runCatching 吞掉，击杀静默失败），
            // 且自带一份**不完整**的 su 路径表（漏了 /vendor/bin、/data/adb/magisk、
            // /data/adb/ksu），Magisk/KernelSU 设备上会直接跳过击杀 → 孤儿占着 3080/3083。
            // runSu 内部用 findSu() 覆盖全部路径，并按设备实测挑可用的 su 形式。
            Privilege.runSu("kill -9 -- -$pid 2>/dev/null; pkill -9 -P $pid 2>/dev/null; true")
        }
        pumpThread.join(1_000)
    }

    companion object {
        private const val TAG = "EngineProcess"

        /**
         * fork 引擎进程。
         * @throws EngineStartException execve 失败（含 errno）
         */
        /**
         * fork 引擎进程。
         * @param suPath 非空时以 su 整体提权启动（Root 模式）：cmd=su, args=-c "<node> ..."，
         *               引擎 node 将以 uid 0 运行。null 则直接 exec node（普通/Shizuku 模式）。
         * @throws EngineStartException execve 失败（含 errno）
         */
        fun spawn(
            nodeBin: File,
            entryJs: File,
            cwd: File,
            env: Array<String>,
            logFile: File,
            suPath: String? = null,
            patchPath: String? = null,
            extraFlags: List<String> = emptyList(),
        ): EngineProcess {
            var cmd = nodeBin.absolutePath
            // patchPath：Android 适配覆盖层（v1.2.36，沙箱模式等），按官方 CLI 用法
            // `dsh <profile> --patch <file>` 注入；未知/失效条目会被忽略，不阻断启动。
            // extraFlags：留给将来按需注入 V8 参数的接口（当前恒为空 —— 刻意不限制堆：
            // 用户真正吃内存的场景是软件编译（clang/rustc/gradle），限制堆会直接导致
            // 构建 OOM 失败；V8 本身按需分配，空闲时占用并不高）。
            var args = buildList {
                add("--expose-internals")
                addAll(extraFlags)
                add(entryJs.absolutePath); add("web")
                if (patchPath != null) { add("--patch"); add(patchPath) }
                add("--no-open")
            }.toTypedArray()
            if (suPath != null) {
                // Root 整体提权启动。
                //
                // ⚠️ v1.2.100：**不能固定拼 `su -c`** —— 酷安用户（Android 15）反馈
                // 「授予 root 后检测不到 su」，根因就是那台设备的 su 是 toybox 版：
                //     su: invalid uid/gid '-c'
                // 它只认位置参数（`su 0 <cmd>`），不认 `-c`。旧实现写死 -c → 引擎起不来。
                // 现在由 Privilege.usableSuPrefix() 实测挑出可用形式（-c / 0 / root -c）。
                //
                // ⚠️⚠️ 但 **cmd 必须保持绝对路径**（实测回归教训）：native 层
                // `dsh_pty.c` 用 `execve(cmd, ...)`，**execve 不搜索 PATH** —— 传裸
                // "su" 会按相对路径解析，而子进程已 chdir 到 workspaces，那里没有 su，
                // 直接 ENOENT / exit 127，Root 模式全灭。所以：
                //   · cmd   = findSu() 给的绝对路径（调用方已传入 suPath）
                //   · args  = usableSuPrefix() 给出的**参数部分**（首个元素是 "su"，丢弃）
                val inner = StringBuilder()
                inner.append("exec ").append(nodeBin.absolutePath)
                args.forEach { inner.append(' ').append(shellQuote(it)) }
                // ⚠️ v1.2.108：**su 会把 HOME 重置成目标用户的家目录**（root → "/"），
                // 我们在 env 里注入的 `HOME=$DSH_HOME` 根本穿透不过来。
                // 后果不是「少了点便利」而是**包管理工具开箱就坏** —— 内置测试实测：
                // root 模式下 `pnpm -v` 直接
                //     Error: ENOENT: mkdir '/.cache/node/corepack/v1'
                // （往只读的 / 写缓存失败），同一条链上还有 npm、`git config --global`、
                // ssh known_hosts、.npmrc。所以 su 之后必须自己 export 一次，
                // 不能指望调用方传的 env 能穿过去。PATH 同理（su 也可能重置，
                // 而引擎的 PATH 里带着 bin/ 下的全部工具）。
                val homeVar = env.firstOrNull { it.startsWith("HOME=") }?.substringAfter('=')
                val pathVar = env.firstOrNull { it.startsWith("PATH=") }?.substringAfter('=')
                if (homeVar.isNullOrEmpty() || pathVar.isNullOrEmpty()) {
                    // 取不到就等于静默退回「HOME=/ 、pnpm 开箱即坏」的老 bug，
                    // 所以必须留痕：当前唯一调用方（EngineConfig.buildEnv）保证有值，
                    // 但将来新增调用方传了精简 env 时，这条日志就是唯一的线索。
                    android.util.Log.w(
                        TAG,
                        "root spawn: env missing HOME/PATH (home=${!homeVar.isNullOrEmpty()}, path=${!pathVar.isNullOrEmpty()})",
                    )
                }
                inner.insert(
                    0,
                    buildString {
                        append("cd ").append(shellQuote(cwd.absolutePath))
                        if (!homeVar.isNullOrEmpty()) append(" && export HOME=").append(shellQuote(homeVar))
                        if (!pathVar.isNullOrEmpty()) append(" && export PATH=").append(shellQuote(pathVar))
                        append(" && ")
                    },
                )
                val prefix = Privilege.usableSuPrefix()
                    ?: throw EngineStartException("no usable su (tried -c / 0 / root -c)")
                cmd = suPath                                  // 绝对路径，execve 需要
                args = (prefix.drop(1) + inner.toString()).toTypedArray()
            }
            val fd = Pty.nativeForkPty(
                cmd = cmd,
                args = args,
                cwd = cwd.absolutePath,
                env = env,
                rows = 40,
                cols = 120,
            )
            if (fd < 0) throw EngineStartException("fork failed, errno=${-fd}")
            return EngineProcess(fd, logFile, suPath != null)
        }

        /** POSIX sh 单引号转义（防注入/路径含空格） */
        private fun shellQuote(s: String): String {
            if (!s.contains(Regex("[\\s\"'\\\\]"))) return "'$s'"
            return "'" + s.replace("'", "'\\''") + "'"
        }
    }
}

class EngineStartException(message: String) : Exception(message)

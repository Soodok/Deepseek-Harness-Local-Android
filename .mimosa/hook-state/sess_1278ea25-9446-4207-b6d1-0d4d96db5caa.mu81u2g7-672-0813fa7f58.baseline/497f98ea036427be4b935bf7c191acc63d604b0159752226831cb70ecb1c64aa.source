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
                logFile.appendText("---- engine start ${System.currentTimeMillis()} ----\n")
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

    /** 优雅停止：TERM → 10s 宽限 → KILL */
    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        Pty.nativeSignalChild(15)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !exitFuture.isDone) {
            Thread.sleep(100)
        }
        if (!exitFuture.isDone) Pty.nativeForceKill()
        // 【v1.2.22 事故】root 模式（su -c）下 PTY 只能杀到 su 外壳，exec node 的
        // 孙进程成孤儿继续霸占 3080 → 普通引擎 EADDRINUSE 反复重启（"异常退出"循环
        // 但页面/AI 正常，服务的是孤儿）。进程组 + 子进程双保险击杀。
        if (suUsed && pid > 0) {
            runCatching {
                val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")
                    .firstOrNull { java.io.File(it).exists() } ?: return@runCatching
                ProcessBuilder(su, "-c",
                    "kill -9 -- -$pid 2>/dev/null; pkill -9 -P $pid 2>/dev/null; true")
                    .start().waitFor()
            }
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
        ): EngineProcess {
            var cmd = nodeBin.absolutePath
            var args = arrayOf("--expose-internals", entryJs.absolutePath, "web", "--no-open")
            if (suPath != null) {
                // Root 整体提权：以 su -c 'exec node ...' 启动。
                // env 已由调用方按 DSH_ANDROID_PRIV_MODE=ROOT 组装好，su 子进程继承。
                val inner = StringBuilder()
                inner.append("exec ").append(nodeBin.absolutePath)
                args.forEach { inner.append(' ').append(shellQuote(it)) }
                cmd = suPath
                inner.insert(0, "cd " + shellQuote(cwd.absolutePath) + " && ")
                args = arrayOf("-c", inner.toString())
            }
            val fd = Pty.nativeForkPty(
                cmd = cmd,
                args = args,
                cwd = cwd.absolutePath,
                env = env,
                rows = 40,
                cols = 120,
            )
            if (fd < 0) throw EngineStartException("fork 失败 errno=${-fd}")
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

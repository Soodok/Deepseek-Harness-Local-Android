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
) {
    private val closed = AtomicBoolean(false)
    val exitFuture = CompletableFuture<Int>()

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
        pumpThread.join(1_000)
    }

    companion object {
        private const val TAG = "EngineProcess"

        /**
         * fork 引擎进程。
         * @throws EngineStartException execve 失败（含 errno）
         */
        fun spawn(
            nodeBin: File,
            entryJs: File,
            cwd: File,
            env: Array<String>,
            logFile: File,
        ): EngineProcess {
            val fd = Pty.nativeForkPty(
                cmd = nodeBin.absolutePath,
                args = arrayOf("--expose-internals", entryJs.absolutePath, "web"),
                cwd = cwd.absolutePath,
                env = env,
                rows = 40,
                cols = 120,
            )
            if (fd < 0) throw EngineStartException("fork 失败 errno=${-fd}")
            return EngineProcess(fd, logFile)
        }
    }
}

class EngineStartException(message: String) : Exception(message)

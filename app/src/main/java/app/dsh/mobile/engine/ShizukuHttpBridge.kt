package app.dsh.mobile.engine

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Shizuku ADB 级访问桥（m1.30）。
 *
 * 背景：引擎内的 AI 是纯 node 子进程，它的 bash 工具命令走本地 PTY，
 * 完全触碰不到 Android 侧的 Shizuku binder。要让 AI 获得 ADB 级执行能力，
 * 现注入一个 `shz` 命令行包装器：AI 执行 `shz <adb命令>` 时，shz 把这个
 * 命令 POST 到本桥（默认 127.0.0.1:【引擎端口 + 2】），这里用
 * Privilege.shizukuExec(cmd) 以 adb 身份执行并将其 stdout/stderr 回传。
 *
 * 仅当运行权限模式为 SHIZUKU 且已授权时启动；其他模式关闭，AI 调 shz 将无服务可连。
 * 只监听 127.0.0.1，不对局域网暴露。Android 无 com.sun.net.httpserver，故用原生 ServerSocket。
 */
object ShizukuHttpBridge {

    private const val TAG = "ShizukuHttpBridge"
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool()

    /** 桥端口 = 引擎端口 + 2（默认 3080 → 3082），避开引擎 webServer。 */
    fun port(enginePort: Int): Int = enginePort + 2

    /** 启动环回 HTTP 服务；模式非 Shizuku 或不可用时不启动。幂等。 */
    @Synchronized
    fun start(ctx: android.content.Context, enginePort: Int) {
        if (Privilege.getMode(ctx) != PrivMode.SHIZUKU || !Privilege.shizukuUsable()) {
            stop()
            return
        }
        if (serverSocket != null) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", port(enginePort)))
            serverSocket = ss
            acceptThread = Thread({
                while (!ss.isClosed) {
                    try {
                        val sock = ss.accept()
                        pool.execute { handleConnection(sock) }
                    } catch (e: Exception) {
                        if (!ss.isClosed) Log.w(TAG, "accept error: ${e.message}")
                    }
                }
            }, "shz-bridge-accept").apply { isDaemon = true; start() }
            Log.i(TAG, "shz bridge started on 127.0.0.1:${port(enginePort)} (Shizuku mode)")
        } catch (e: Exception) {
            Log.w(TAG, "shz bridge start failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun handleConnection(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                // 只读第一行请求行 + 跳过 header，再取 body
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"
                var contentLength = 0
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val cmd: String = if (method == "POST" && contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    URLDecoder.decode(String(buf, 0, read), "UTF-8")
                } else {
                    // GET 就把命令放 query，兜底
                    path.substringAfter("?cmd=", "").ifEmpty { "" }
                }

                val output = if (cmd.isBlank()) "shz: empty command\n" else Privilege.shizukuExec(cmd)
                respond(s, 200, output)
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle error: ${e.message}")
            runCatching { respond(sock, 500, "shz bridge error: ${e.message}\n") }
        }
    }

    private fun respond(sock: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out: OutputStream = sock.getOutputStream()
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}

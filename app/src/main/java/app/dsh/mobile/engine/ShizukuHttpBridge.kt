package app.dsh.mobile.engine

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Shizuku ADB 级访问桥（m1.30）。
 *
 * 背景：引擎内的 AI 是纯 node 子进程，它的 bash 工具命令走本地 PTY，
 * 完全触碰不到 Android 侧的 Shizuku binder。要让 AI 获得 ADB 级执行能力，
 * 现注入一个 `shz` 命令行包装器：AI 执行 `shz <adb命令>` 时，shz 把这个
 * 命令 POST 到本桥（默认 127.0.0.1:【引擎端口 + 2】），这里用
 * Privilege.shizukuExec(ctx, cmd) 以 adb 身份执行并将其 stdout/stderr 回传。
 *
 * 仅当运行权限模式为 SHIZUKU 且已授权时启动；其他模式关闭，AI 调 shz 将无服务可连。
 * 只监听 127.0.0.1，不对局域网暴露。Android 无 com.sun.net.httpserver，故用原生 ServerSocket。
 */
object ShizukuHttpBridge {

    private const val TAG = "ShizukuHttpBridge"
    /** 启动时捕获的 applicationContext（本地化错误文案用；application 级，不会泄漏 Activity） */
    @Volatile private var appCtx: android.content.Context? = null
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool()

    /** 桥端口 = 引擎端口 + 2（默认 3080 → 3082），避开引擎 webServer。 */
    fun port(enginePort: Int): Int = enginePort + 2

    /** 启动环回 HTTP 服务；模式非 Shizuku 或不可用时不启动。幂等。 */
    @Synchronized
    fun start(ctx: android.content.Context, enginePort: Int) {
        appCtx = ctx.applicationContext
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
                // ⚠️ v1.2.43：与 AgentBridge 同步改为**字节级**解析。旧实现按字符数读
                // Content-Length 字节数 → UTF-8 中文命令（每字 3 字节）永远读不满 → 超时。
                val ins = s.getInputStream()
                val head = java.io.ByteArrayOutputStream()
                var crlf = 0
                while (head.size() < (16 shl 10)) {
                    val b = ins.read()
                    if (b < 0) break
                    head.write(b)
                    crlf = when {
                        crlf == 0 && b == 13 -> 1
                        crlf == 1 && b == 10 -> 2
                        crlf == 2 && b == 13 -> 3
                        crlf == 3 && b == 10 -> 4
                        b == 13 -> 1
                        else -> 0
                    }
                    if (crlf == 4) break
                }
                val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
                val parts = (lines.firstOrNull() ?: return).split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"
                var contentLength = 0
                lines.drop(1).forEach { l ->
                    if (l.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = l.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                // 🔴 桥鉴权（v1.2.100，独立审查发现的缺口）：
                // 本桥监听 127.0.0.1，POST 的命令会以 **adb 身份**执行 —— 权限比
                // AgentBridge 暴露的无障碍能力更高（adb 可 pm grant 自我授权、input 注入等）。
                // 此前**没有任何校验**，同设备任意 App 都能白嫖 adb 权限，
                // 等于把 AgentBridge 刚加的那道门从旁边绕过去了。
                // 现在复用同一 token（每次启动随机生成、只经环境变量交给引擎，不落盘）。
                if (!Privilege.authorisedBridgeRequest(lines)) {
                    Log.w(TAG, "unauthorised request: $method $path")
                    respond(s, 403, "shz: unauthorised\n")
                    return
                }

                val cmd: String = if (method == "POST" && contentLength > 0) {
                    // shz 用 fetch 发的是原始字符串（非 URL 编码），绝不能 URLDecoder——
                    // 会误转 `+`→空格、`%`→转义，破坏命令。
                    val buf = ByteArray(contentLength.coerceIn(0, 4 shl 20))
                    var read = 0
                    while (read < buf.size) {
                        val n = ins.read(buf, read, buf.size - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else {
                    "/"
                }

                val output = if (cmd.isBlank()) "shz: empty command\n" else Privilege.shizukuExec(appCtx ?: return, cmd)
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

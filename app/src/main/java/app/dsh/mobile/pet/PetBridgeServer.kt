package app.dsh.mobile.pet

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 仅回环的极简 HTTP 接收器。
 *
 * 没有引入 NanoHTTPD：本仓库已有 AgentBridge / ShizukuHttpBridge 两处原生
 * ServerSocket 先例，这里复用同样的形态，避免为了一个 POST 增加依赖。
 *
 * 约束：
 *  - 只绑 127.0.0.1:7779，绝不 0.0.0.0；
 *  - 请求行+头 ≤8KB、body ≤8KB、socket 5s 超时；
 *  - 2 个 worker，避免慢连接把 accept 线程卡住；
 *  - 任何解析失败只回 4xx/5xx，不抛到 Service。
 *
 * ## 鉴权（v1.4.21，照抄 AgentBridge v1.2.99 的先例）
 *
 * 桥监听 127.0.0.1:7779，未鉴权时**同设备上任何 App** 都能伪造表情/气泡
 * （弱钓鱼/骚扰面是真实的，评审 PR #48 指出）。因此 `/bubble` 要求请求头
 * [PetProtocol.TOKEN_HEADER] 等于 [currentToken]；token 每进程惰性生成一次
 * （不落盘、不复用），经引擎环境变量 `DSH_PET_BRIDGE_TOKEN` 交给
 * dsh-pet-bridge 插件 —— 只有引擎子进程拿得到，第三方 App 拿不到。
 *
 * **fail-closed**：token 校验失败/缺失一律 403，绝不静默放行。
 * `/ping` 保持免鉴权：响应体只有服务存在性信息，无敏感数据。
 */
class PetBridgeServer(
    private val onEvent: (PetEvent) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val TAG = "DshPetBridge"
    private val SOCKET_TIMEOUT_MS = 5_000

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var workers: ExecutorService? = null
    @Volatile private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running

    companion object {
        @Volatile private var token: String? = null

        /**
         * 当前桥 token（v1.4.21）。进程内**惰性生成一次**：无论引擎环境变量
         * 组装（EngineConfig）和 `start()` 谁先发生，读到的都是同一个值，
         * 不落盘、不复用 —— 第三方 App 拿不到。
         */
        fun currentToken(): String =
            token ?: synchronized(this) {
                token ?: java.util.UUID.randomUUID().toString().replace("-", "").also { token = it }
            }
    }

    fun start() {
        if (running) return
        running = true
        workers = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "dsh-pet-bridge-worker").apply { isDaemon = true }
        }
        acceptThread = Thread({ acceptLoop() }, "dsh-pet-bridge-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { workers?.shutdownNow() }
        workers = null
        acceptThread = null
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), PetProtocol.PORT),
                8,
            )
            serverSocket = ss
            Log.i(TAG, "listening on 127.0.0.1:${PetProtocol.PORT}")
            while (running) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (running) {
                        Log.w(TAG, "accept failed: ${e.message}")
                        onError("accept_failed")
                    }
                    break
                }
                val pool = workers
                if (pool == null || pool.isShutdown) {
                    runCatching { client.close() }
                    break
                }
                runCatching {
                    pool.execute { handle(client) }
                }.onFailure {
                    Log.w(TAG, "dispatch failed: ${it.message}")
                    runCatching { client.close() }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "bind failed on 127.0.0.1:${PetProtocol.PORT}: ${e.message}")
                onError("port_busy")
            }
        } finally {
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val requestLine = readLine(input, PetProtocol.MAX_HEADER_BYTES) ?: run {
                respond(output, 400, "Bad Request")
                return
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(output, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var headerBytes = requestLine.length + 2
            var contentLength = 0
            val headerLines = ArrayList<String>(16)
            while (true) {
                val line = readLine(input, PetProtocol.MAX_HEADER_BYTES - headerBytes) ?: run {
                    respond(output, 431, "Request Header Fields Too Large")
                    return
                }
                if (line.isEmpty()) break
                headerBytes += line.length + 2
                if (headerBytes > PetProtocol.MAX_HEADER_BYTES) {
                    respond(output, 431, "Request Header Fields Too Large")
                    return
                }
                headerLines.add(line)
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: -1
                }
            }

            if (method == "GET" && path == "/ping") {
                respond(
                    output, 200, "OK",
                    """{"ok":true,"service":"pet-overlay","protocol":3}""",
                    "application/json",
                )
                return
            }
            if (path != "/bubble") {
                respond(output, 404, "Not Found")
                return
            }
            if (method != "POST") {
                respond(output, 405, "Method Not Allowed")
                return
            }
            // 鉴权（v1.4.21）：/bubble 必须带 X-DSH-Pet-Token 且等于当前 token。
            // fail-closed：缺失/不匹配一律 403 —— 桌宠只玩表情/气泡，但也不给
            // 同设备第三方 App 免费刷存在感的通道（评审 PR #48）。
            if (!authorised(headerLines)) {
                respond(output, 403, "Forbidden")
                return
            }
            if (contentLength <= 0 || contentLength > PetProtocol.MAX_BODY_BYTES) {
                respond(output, if (contentLength > PetProtocol.MAX_BODY_BYTES) 413 else 400, "Bad Request")
                return
            }

            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            if (read != contentLength) {
                respond(output, 400, "Bad Request")
                return
            }

            val event = PetProtocol.parse(String(body, Charsets.UTF_8))
            if (event == null) {
                respond(output, 400, "Bad Request")
                return
            }
            runCatching { onEvent(event) }
                .onFailure { Log.w(TAG, "event callback failed: ${it.message}") }
            respond(output, 204, "No Content")
        } catch (e: Exception) {
            // 超时/断连/格式错误都静默；下一轮继续服务
            Log.d(TAG, "request ended: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /** 校验请求头：`X-DSH-Pet-Token` 必须等于当前 token（fail-closed，见类注释）。 */
    private fun authorised(headerLines: List<String>): Boolean {
        val expected = currentToken()
        for (line in headerLines) {
            if (line.startsWith(PetProtocol.TOKEN_HEADER, ignoreCase = true) &&
                line.getOrNull(PetProtocol.TOKEN_HEADER.length) == ':'
            ) {
                // 常量时间比较（评审 P2）：避免按前缀长度/提前返回泄露 token 信息。
                // MessageDigest.isEqual 对等长输入做无分支归约，长度不同直接 false。
                return java.security.MessageDigest.isEqual(
                    line.substringAfter(':').trim().toByteArray(Charsets.UTF_8),
                    expected.toByteArray(Charsets.UTF_8),
                )
            }
        }
        return false
    }

    private fun respond(
        output: OutputStream,
        code: Int,
        reason: String,
        body: String = "",
        contentType: String? = null,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Connection: close\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    /** 按字节读一行，限制总长度；返回 null 表示超限/连接断开。 */
    private fun readLine(input: InputStream, max: Int): String? {
        if (max <= 0) return null
        val sb = StringBuilder()
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b == '\r'.code) continue
            count++
            if (count > max) return null
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}

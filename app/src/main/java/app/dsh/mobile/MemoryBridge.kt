package app.dsh.mobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 记忆面板的桥调用（M8，无障碍记忆系统 v2）。
 *
 * ## 为什么不自己写一套网络层
 * 本机桥 [app.dsh.mobile.engine.AgentBridge] 监听 127.0.0.1:3083，且 v1.2.99 起
 * 每个请求都要带 `X-DSH-Token`（token 只写进引擎环境变量，因此只有 App 进程内的
 * [app.dsh.mobile.engine.AgentBridge.currentToken] 拿得到）。这里沿用
 * StatusOverlay 里已验证的那套调法：HttpURLConnection + 该 header，不引入 OkHttp
 * 之类的新依赖（build.gradle.kts 锁死，不能为了一个设置页加库）。
 *
 * ## 为什么封装成 Reply 而不是直接返回字符串
 * 桥的失败有三种：连不上（code=0）、HTTP 4xx/5xx（鉴权失败、无障碍服务没开）、
 * HTTP 200 但 body 里 `ok:false`（例如重放档位不够）。三者对 UI 的处置完全不同，
 * 只返回字符串就把区别丢了 —— 面板会显示「空列表」而不是「引擎没起」。
 */
internal object MemoryBridge {

    /** 桥端口固定 3083（见 EngineConfig 与 StatusOverlay，勿改）。 */
    private const val BASE = "http://127.0.0.1:3083"

    private const val CONNECT_TIMEOUT_MS = 3000

    /** 一次往返的结果。code == 0 表示根本没连上（或拿不到 token）。 */
    internal data class Reply(val code: Int, val body: String) {

        /** 连不上桥。 */
        val unreachable: Boolean get() = code == 0

        /** 桥明确回了成功（HTTP 2xx 且 body.ok 为 true）。 */
        val ok: Boolean get() = code in 200..299 && (json?.optBoolean("ok", false) == true)

        val json: JSONObject? get() = runCatching { JSONObject(body) }.getOrNull()

        /** 桥自己给的错误说明（body.error / body.reason），拿不到就返回空串。 */
        val errorText: String
            get() = json?.let { it.optString("error").ifBlank { it.optString("reason") } } ?: ""
    }

    fun get(path: String, readTimeoutMs: Int = 5000): Reply =
        call("GET", path, null, readTimeoutMs)

    fun post(path: String, body: String, readTimeoutMs: Int = 20000): Reply =
        call("POST", path, body, readTimeoutMs)

    private fun call(
        method: String,
        path: String,
        body: String?,
        readTimeoutMs: Int,
    ): Reply {
        val token = app.dsh.mobile.engine.AgentBridge.currentToken() ?: return Reply(0, "")
        val conn = try {
            (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("X-DSH-Token", token)
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
        } catch (e: Exception) {
            return Reply(0, "")
        }
        return try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // 4xx/5xx 的正文在 errorStream 里；不读它就看不出「服务未开启」这类原因。
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Reply(code, text)
        } catch (e: Exception) {
            Reply(0, "")
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}

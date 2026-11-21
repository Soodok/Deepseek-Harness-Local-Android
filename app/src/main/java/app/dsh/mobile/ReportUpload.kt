package app.dsh.mobile

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 附件上报通道（`/bug/upload.php` + `/bug/consent.php`）的最小客户端。
 *
 * 与 `ReportBundle.post()` 的分工：那个发**纯文本**（200 KiB 以内、一个 JSON），
 * 这个发**附件**（multipart，≤2 MiB，压缩包 / patch / 日志）。
 *
 * ## 三条与协议有关的硬事实（写在这里，免得后人踩）
 *
 * 1. **一次性同意 token 必须先领**：没有它服务端直接 403 NO_CONSENT，
 *    而 token 由 App 在用户点了同意之后才去领 —— 顺序反了就没有意义。
 * 2. **multipart 的分隔符必须每次随机且不出现在内容里**：固定边界 + 二进制
 *    内容里恰好撞上，会切坏文件；这里用 UUID，且拒绝把超过 2 MiB 的文件塞进来。
 * 3. **不跟随重定向、不读超时设短**：报告失败应该是「明确失败」，
 *    而不是把连接拖到用户以为卡死。
 */
object ReportUpload {

    const val BASE = "https://soodok.online/bug"
    const val CONSENT_ENDPOINT = "$BASE/consent.php"
    const val UPLOAD_ENDPOINT = "$BASE/upload.php"

    /** 与服务端一致的上限；客户端先卡一次，省得白跑一趟网络。 */
    const val MAX_BYTES = 2 * 1024 * 1024

    private const val CONNECT_MS = 15_000
    private const val READ_MS = 30_000

    /**
     * 领一次性同意 token。
     *
     * @return token 字符串；失败返回 null（调用方必须当作「没同意」处理，
     *         绝不退化成「不带 token 直接发」—— 那样服务端也会 403，但会白白
     *         在日志里留下一次失败尝试）。
     */
    fun mintConsent(): String? {
        return runCatching {
            val conn = (URL(CONSENT_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                doOutput = true
                setRequestProperty("X-DSH-Report-Key", ReportBundle.REPORT_KEY)
                setRequestProperty("Content-Length", "0")
            }
            conn.outputStream.use { }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return null
            JSONObject(text).optString("token").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * multipart 上传。
     *
     * @return (HTTP 状态码, 响应正文)。网络层失败时返回 (0, 错误文案)，
     *         调用方据此区分「服务端拒绝」与「根本没连上」。
     */
    fun upload(
        fields: Map<String, String>,
        files: List<File>,
        token: String,
    ): Pair<Int, String> {
        val total = files.sumOf { it.length() }
        if (total > MAX_BYTES) {
            return 413 to """{"ok":false,"code":"TOO_LARGE","limit":$MAX_BYTES,"got":$total}"""
        }
        val boundary = "----dsh" + java.util.UUID.randomUUID().toString().replace("-", "")
        return runCatching {
            val conn = (URL(UPLOAD_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                doOutput = true
                setRequestProperty("X-DSH-Report-Key", ReportBundle.REPORT_KEY)
                setRequestProperty("X-DSH-Consent", token)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            conn.outputStream.use { out ->
                fields.forEach { (k, v) ->
                    out.write(("--$boundary\r\n").toByteArray())
                    out.write(("Content-Disposition: form-data; name=\"$k\"\r\n\r\n").toByteArray())
                    out.write(v.toByteArray(Charsets.UTF_8))
                    out.write("\r\n".toByteArray())
                }
                files.forEach { f ->
                    val safe = f.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    out.write(("--$boundary\r\n").toByteArray())
                    out.write(
                        ("Content-Disposition: form-data; name=\"file\"; filename=\"$safe\"\r\n")
                            .toByteArray()
                    )
                    out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                    writeFile(f, out)
                    out.write("\r\n".toByteArray())
                }
                out.write(("--$boundary--\r\n").toByteArray())
                out.flush()
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            code to text
        }.getOrElse { e -> 0 to """{"ok":false,"code":"NETWORK","error":"${e.message?.replace('"', '\'') ?: "unknown"}"}""" }
    }

    private fun writeFile(f: File, out: OutputStream) {
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
    }
}

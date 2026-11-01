package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 运行时/索引下载器（v1.4.20）。
 *
 * 能力：
 *  - URL 列表按顺序回退（MANIFEST 里已带直连 + gh-proxy 前缀版本）；
 *  - 断点续传（`.part` + Range；服务端不支持 206 时自动从头下载）；
 *  - 截断/大小/sha256 校验，失败删除临时文件并尝试下一个 URL。
 *
 * 只用于公开的运行时/索引下载，不携带任何凭据。
 */
class HttpDownloader(private val ctx: Context) {

    data class Result(val sha256: String, val bytes: Long, val url: String)

    /** 调用方主动取消（不是网络错误）：不要按"换下一个源"处理。 */
    class CancelledException : Exception("download cancelled")

    /**
     * @param urls 依次尝试的 URL（直连 / 代理）
     * @param dest 最终文件
     * @param expectedSha256 必填：没有 pin 的包不允许下载
     * @param expectedSize >0 时校验字节数
     * @param onProgress 0f..1f（拿不到 Content-Length 时只在完成时回调 1f）
     * @param shouldCancel 每读一块调用一次；返回 true 立即抛 [CancelledException]
     *        （自动更新的通知栏「取消」按钮用它。取消**不删** `.part`，下次可续传）
     */
    fun download(
        urls: List<String>,
        dest: File,
        expectedSha256: String,
        expectedSize: Long = 0,
        onProgress: (Float) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): Result {
        require(expectedSha256.isNotBlank()) { "refusing download without SHA-256 pin" }
        require(urls.isNotEmpty()) { "no download URL" }
        // 明文 http 一律拒绝（issue #49 P2）。索引已验签 + 上游 URL 全是 https，那两道
        // 都不是下载器自己的保证：镜像前缀被改、索引里的 host 被换，就能把整包换成
        // http 明文。这里在发起请求前就拦掉，环回地址除外（本机自测/离线镜像）。
        val safe = urls.distinct().filter { isSecureDownloadUrl(it) }
        if (safe.isEmpty()) throw IllegalStateException(
            "refusing non-https download URL(s): " + urls.distinct().joinToString(", "),
        )
        urls.distinct().filterNot { isSecureDownloadUrl(it) }.forEach {
            Log.w(TAG, "skip non-https URL: $it")
        }
        val part = File(dest.parentFile, dest.name + ".part")
        var lastError: Exception? = null

        for (url in safe) {
            try {
                val bytes = fetch(url, part, expectedSize, onProgress, shouldCancel)
                val actual = RuntimeInstaller.sha256(part)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    part.delete()
                    throw IllegalStateException(
                        "checksum mismatch: expected=$expectedSha256 actual=$actual ($url)",
                    )
                }
                if (expectedSize > 0 && bytes != expectedSize) {
                    part.delete()
                    throw IllegalStateException("size mismatch: expected=$expectedSize actual=$bytes ($url)")
                }
                if (dest.exists() && !dest.delete()) dest.deleteOnExit()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                Log.i(TAG, "downloaded $url -> ${dest.name} ($bytes bytes)")
                onProgress(1f)
                return Result(actual, bytes, url)
            } catch (e: CancelledException) {
                // 用户取消：保留 .part 供续传，直接向上抛，别再换源重试
                Log.i(TAG, "download cancelled: $url")
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "download failed: $url: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        part.delete()
        throw IllegalStateException(
            "all download URLs failed: " + (lastError?.message ?: "unknown"),
            lastError,
        )
    }

    /** 下载一次；优先续传。返回文件总字节数。 */
    private fun fetch(
        url: String,
        part: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
        shouldCancel: () -> Boolean,
    ): Long {
        var start = if (part.isFile) part.length() else 0L
        if (expectedSize > 0 && start > expectedSize) {
            part.delete()
            start = 0
        }
        if (expectedSize > 0 && start == expectedSize) {
            // 上次其实已经下完（校验失败才会走到这里，保留给上层删除重下）。
            part.delete()
            start = 0
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "dsh-mobile-runtime")
            if (start > 0) setRequestProperty("Range", "bytes=$start-")
        }
        var code = conn.responseCode
        if (start > 0 && code == HttpURLConnection.HTTP_OK) {
            // 服务端忽略了 Range：从头写，避免把整包追加到半包后面。
            part.delete()
            start = 0
        } else if (start > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code")
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code")
        }

        val total = when {
            code == HttpURLConnection.HTTP_PARTIAL -> start + conn.contentLengthLong.coerceAtLeast(0)
            expectedSize > 0 -> expectedSize
            else -> conn.contentLengthLong
        }
        var done = start
        var lastAt = 0L
        try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, start > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (shouldCancel()) throw CancelledException()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastAt >= 250L) {
                            lastAt = now
                            if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (total > 0 && done != total) {
            throw java.io.IOException("truncated download: $done/$total bytes ($url)")
        }
        return done
    }

    companion object {
        private const val TAG = "HttpDownloader"

        /**
         * 只放行 https；`http://127.0.0.1`、`http://localhost`、`http://[::1]` 例外
         * （环回不经网络，明文不构成中间人面，且离线镜像/自测要用）。
         */
        internal fun isSecureDownloadUrl(url: String): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme == "https") return true
            if (scheme != "http") return false
            val host = uri.host?.lowercase() ?: return false
            return host == "127.0.0.1" || host == "localhost" || host == "[::1]" || host == "::1"
        }
    }
}

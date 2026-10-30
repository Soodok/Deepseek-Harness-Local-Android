package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 运行时索引（`runtime-index.json`）客户端。
 *
 * ## 为什么需要它
 * edge / preview 的 noruntime 包指向 `releases/latest/download/runtime-<abi>.zip`
 * 这种滚动 URL，构建时无法钉死 sha。App 必须先取索引拿到 sha，才能安全下载。
 *
 * ## 安全
 * 索引必须带 detached EC P-256 签名（`runtime-index.json.sig`），App 用内置公钥
 * 验签；验签失败直接拒绝，不降级为“无签名也下载”。索引里的 sha256 再约束运行时包。
 */
object RuntimeIndexClient {

    data class Entry(
        val version: String,
        val builtAt: Long,
        val sha256: String,
        val size: Long,
        val unpackedBytes: Long,
        val urls: List<String>,
    )

    private const val TAG = "RuntimeIndex"
    private const val MAX_INDEX_BYTES = 512 * 1024

    /**
     * 解析索引并按 ABI 取条目。
     * @param exactVersion 指定时优先精确匹配（tag/preview 构建时已知版本）；找不到回退到最新。
     * @param proxyBase 可选前缀代理（如 https://gh-proxy.org/），索引请求优先走它，直连作兜底。
     */
    fun resolve(
        ctx: Context,
        indexUrl: String?,
        proxyBase: String?,
        abi: String,
        exactVersion: String?,
        currentVersionCode: Int,
    ): Entry? = runCatching {
        val base = indexUrl?.takeIf { it.isNotBlank() } ?: (RepoConfig.RELEASES_LATEST + "/runtime-index.json")
        // 代理优先：国内直连 GitHub 往往先卡 15–20s 才回退，等于白等一轮。
        val urls = RuntimeDownloadConfig.orderUrls(listOf(base), proxyBase.orEmpty())

        var lastError: Exception? = null
        for (u in urls) {
            try {
                val raw = fetchBytes(u)
                val sig = fetchBytes(u + ".sig")
                if (!app.dsh.mobile.update.SignedIndex.verify(ctx, raw, sig)) {
                    throw SecurityException("runtime-index signature mismatch ($u)")
                }
                val doc = JSONObject(String(raw, Charsets.UTF_8))
                val minCode = doc.optInt("minAppVersionCode", 0)
                if (minCode > 0 && currentVersionCode > 0 && currentVersionCode < minCode) {
                    throw SecurityException(
                        "runtime-index requires app versionCode >= $minCode (current $currentVersionCode)",
                    )
                }
                val obj = doc.optJSONObject("runtime")?.optJSONObject(abi)
                    ?: throw IllegalStateException("runtime-index has no entry for $abi")
                val urlsArr = mutableListOf<String>()
                obj.optJSONArray("urls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (s.isNotBlank()) urlsArr.add(s)
                    }
                }
                val entry = Entry(
                    version = obj.optString("version"),
                    builtAt = obj.optLong("builtAt", 0L),
                    sha256 = obj.optString("sha256"),
                    size = obj.optLong("size", 0L),
                    unpackedBytes = obj.optLong("unpackedBytes", 0L),
                    urls = urlsArr,
                )
                if (entry.sha256.isBlank()) throw IllegalStateException("runtime-index entry missing sha256")
                if (!exactVersion.isNullOrBlank() && exactVersion != "unknown" &&
                    entry.version != exactVersion
                ) {
                    Log.i(TAG, "index version=${entry.version}, manifest=$exactVersion; using index entry")
                }
                Log.i(TAG, "resolved runtime ${entry.version} ($abi) via $u")
                return@runCatching entry
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "index fetch failed: $u: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IllegalStateException(
            "cannot resolve signed runtime-index: " + (lastError?.message ?: "unknown"),
            lastError,
        )
    }.onFailure {
        Log.w(TAG, "runtime-index unavailable: ${it.message}")
    }.getOrNull()

    private fun fetchBytes(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "dsh-mobile-runtime")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code ($url)")
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1 shl 14)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_INDEX_BYTES) {
                        throw IllegalStateException("index too large (> $MAX_INDEX_BYTES bytes)")
                    }
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    // 验签实现已抽到 `update/SignedIndex.kt`：`update-index.json` 与 `runtime-index.json`
    // 共用同一套信任锚与算法选择逻辑（两处各写一遍必然漂移）。
}

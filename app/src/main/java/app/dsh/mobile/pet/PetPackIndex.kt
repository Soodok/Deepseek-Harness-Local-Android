package app.dsh.mobile.pet

import android.content.Context
import android.util.Log
import app.dsh.mobile.engine.RuntimeDownloadConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** index.json 里的一个分卷。 */
data class PackPartSpec(
    val url: String,
    val size: Long,
    val sha256: String,
)

/** index.json 里的一个可选资源包。 */
data class RemotePack(
    val id: String,
    val name: String,
    val resolution: String?,
    val fps: Int,
    val animations: Int,
    val size: Long,
    val sha256: String,
    val font: String?,
    val url: String,
    val parts: List<PackPartSpec>,
)

data class PetPackIndex(
    val version: Int,
    val updatedAt: String?,
    val packs: List<RemotePack>,
)

/**
 * 一个可尝试的下载源 + 它是否走代理。
 *
 * 下载过程中会按候选顺序切换源（代理失败自动回落直连），所以「走没走代理」
 * 不是启动时算一次就完事，必须跟着实际用的地址实时上报给 UI。
 */
data class PackSource(
    val url: String,
    /** true = 经前缀代理；false = 直连。 */
    val viaProxy: Boolean,
) {
    /** 只用于展示的 host。代理地址带很长的原始 URL 后缀，截到第一段就够了。 */
    val host: String
        get() = url.substringAfter("://", url).substringBefore('/')
}

/**
 * 资源包索引客户端。
 *
 * index.json 由资源仓库的 CI 发布（默认在 Release 上），包地址相对 index 基地址解析，
 * 所以换自己的仓库/镜像只需要改 PetPrefs.packIndexUrl。
 */
object PetPackIndexClient {
    private const val TAG = "DshPetIndex"
    private const val TIMEOUT_MS = 20_000

    /** 把相对地址按 index 的目录补齐。 */
    fun resolve(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        val anchor = base.substringBeforeLast('/')
        return "$anchor/${relative.trimStart('/')}"
    }

    /**
     * 拉取索引；按设置里的「运行时下载代理」重排地址。
     *
     * 代理只对 **GitHub 域名**生效（见 [RuntimeDownloadConfig.orderUrls] 里的
     * `isProxyable`）：自建 CDN / 其它镜像不会被二次包装，原样直连。
     * 代理源失败会自动回落到下一个候选（通常是原始直连地址）。
     *
     * ⚠️ 必须传 ctx 才能拿到代理偏好 —— 刻意不留无 ctx 的重载，
     * 免得以后有人顺手调用把代理整条绕过去。
     */
    fun fetch(ctx: Context, indexUrl: String): PetPackIndex {
        val candidates = candidates(ctx, indexUrl)
        var last: Exception? = null
        for (url in candidates) {
            try {
                // ⚠️ base 必须传**原始** indexUrl：包地址是相对它解析的，
                // 用代理后的地址当 base 会把相对路径拼进代理前缀里。
                return fetchOne(indexUrl, url)
            } catch (e: Exception) {
                last = e
                logError("index($url)", e)
            }
        }
        throw IllegalStateException(
            "all index URLs failed: " + (last?.message ?: "unknown"),
            last,
        )
    }

    /**
     * 按当前代理偏好给出该地址的候选下载顺序（代理优先、直连兜底）。
     *
     * 只在地址是 GitHub 时才产生代理变体；其它地址原样返回。
     * `orderUrls` 末尾的 https 强制会把用户自建的明文源剔空 —— 宠物包每卷都有
     * SHA-256 钉死，这里回退到原始地址，不因为协议检查把功能整体关掉。
     */
    fun candidates(ctx: Context?, url: String): List<String> {
        val app = ctx?.applicationContext ?: return listOf(url)
        val proxy = RuntimeDownloadConfig.proxy(app)
        if (proxy.isBlank()) return listOf(url)
        val ordered = RuntimeDownloadConfig.orderUrls(listOf(url), proxy)
        return ordered.ifEmpty { listOf(url) }
    }

    /**
     * 同 [candidates]，但每个候选带上「是否经代理」。
     *
     * UI 要回答的是「这次下载到底走没走代理」，所以必须把判定和生成候选放在一起：
     * 分隔出来单独猜，迟早和 [RuntimeDownloadConfig.orderUrls] 的规则漂移。
     */
    fun sources(ctx: Context?, url: String): List<PackSource> {
        val app = ctx?.applicationContext ?: return listOf(PackSource(url, false))
        val proxy = RuntimeDownloadConfig.proxy(app)
        return candidates(app, url).map { PackSource(it, RuntimeDownloadConfig.isProxied(it, proxy)) }
    }

    private fun fetchOne(base: String, url: String): PetPackIndex {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                r.readText()
            }
            return parse(base, text)
        } finally {
            conn.disconnect()
        }
    }

    fun parse(base: String, text: String): PetPackIndex {
        val root = JSONObject(text)
        val packs = mutableListOf<RemotePack>()
        val arr: JSONArray = root.optJSONArray("packs") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rawUrl = o.optString("url", "")
            if (rawUrl.isBlank()) continue
            val parts = mutableListOf<PackPartSpec>()
            o.optJSONArray("parts")?.let { pa ->
                for (j in 0 until pa.length()) {
                    val po = pa.optJSONObject(j) ?: continue
                    val pu = po.optString("url", "")
                    if (pu.isBlank()) continue
                    parts += PackPartSpec(
                        url = resolve(base, pu),
                        size = po.optLong("size", 0L),
                        sha256 = po.optString("sha256", ""),
                    )
                }
            }
            packs += RemotePack(
                id = o.optString("id", "").takeIf { it.isNotBlank() }
                    ?: rawUrl.substringAfterLast('/').substringBefore('.'),
                name = o.optString("name", "").takeIf { it.isNotBlank() } ?: rawUrl.substringAfterLast('/'),
                resolution = o.optString("resolution", "").takeIf { it.isNotBlank() },
                fps = o.optInt("fps", 0),
                animations = o.optInt("animations", 0),
                size = o.optLong("size", 0L),
                sha256 = o.optString("sha256", ""),
                font = o.optString("font", "").takeIf { it.isNotBlank() },
                url = resolve(base, rawUrl),
                parts = parts,
            )
        }
        return PetPackIndex(
            version = root.optInt("version", 1),
            updatedAt = root.optString("updatedAt", "").takeIf { it.isNotBlank() },
            packs = packs,
        )
    }

    fun open(url: String, rangeStart: Long = 0L): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
        }

    fun logError(op: String, e: Throwable) {
        Log.w(TAG, "$op failed: ${e.message}")
    }
}

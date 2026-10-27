package app.dsh.mobile.pet

import android.util.Log
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

    fun fetch(indexUrl: String): PetPackIndex {
        val conn = (URL(indexUrl).openConnection() as HttpURLConnection).apply {
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
            return parse(indexUrl, text)
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

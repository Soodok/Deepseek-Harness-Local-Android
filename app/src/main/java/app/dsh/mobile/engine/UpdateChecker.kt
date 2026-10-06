package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检测更新（v1.2.77）—— 查 GitHub Releases 是否有比当前更高的版本。
 *
 * ## 为什么用 GitHub Releases API
 * 项目的发行包就发在 Releases 里（tag 形如 `v1.2.56`），官方 API 免费、无需鉴权
 * （有 60 次/小时的匿名限额，手动点"检测更新"完全够用）。
 *
 * ## 判定规则
 *  · 只比**版本号数字**（`1.2.77` → [1,2,77]），逐段比较，避免字符串比较把
 *    `1.2.9` 判成比 `1.2.10` 新这类错误
 *  · 跳过 draft / prerelease（那些不是给用户装的稳定包）
 *  · 取"版本号最高"的那个 release，而不是"最新发布"的（补发的旧版本 tag 不会误报）
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** 仓库（与 README 中的开源地址一致） */
    private const val API = "https://api.github.com/repos/Soodok/Deepseek-Harness-Local-Android/releases"

    const val RELEASES_PAGE = "https://github.com/Soodok/Deepseek-Harness-Local-Android/releases"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 检测结果 */
    data class Result(
        /** 是否有更高版本 */
        val hasUpdate: Boolean,
        /** 远端最新版本号（无则空） */
        val latestTag: String = "",
        /** 该版本对应的 Release 页面链接 */
        val releaseUrl: String = "",
        /** 该版本里 arm64 的 APK 直链（可能为空） */
        val apkUrl: String = "",
        /** 失败原因（成功时为空） */
        val error: String = "",
    )

    /**
     * 查询是否有更新。**阻塞**（网络），调用方必须在后台线程。
     * @param currentVersion 当前 versionName（如 "1.2.77"）
     */
    fun check(ctx: Context, currentVersion: String): Result = runCatching {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "dsh-mobile-update-check")
        }
        val code = conn.responseCode
        if (code != 200) {
            val msg = "HTTP $code"
            Log.w(TAG, "releases api $msg")
            conn.disconnect()
            return@runCatching Result(false, error = msg)
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val arr = JSONArray(body)
        val current = parseVersion(currentVersion)

        var best: org.json.JSONObject? = null
        var bestVer: List<Int> = emptyList()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft", false) || r.optBoolean("prerelease", false)) continue
            val tag = r.optString("tag_name")
            val v = parseVersion(tag)
            if (v.isEmpty()) continue
            if (best == null || compare(v, bestVer) > 0) {
                best = r
                bestVer = v
            }
        }
        val rel = best ?: return@runCatching Result(false, error = "no usable release")
        val tag = rel.optString("tag_name")

        // 优先取 arm64 的 APK 直链（本项目的发布包命名含 arm64-v8a）
        var apk = ""
        rel.optJSONArray("assets")?.let { assets ->
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk") && name.contains("arm64")) {
                    apk = a.optString("browser_download_url")
                    break
                }
            }
        }
        val url = rel.optString("html_url").ifBlank { RELEASES_PAGE }
        Result(
            hasUpdate = compare(bestVer, current) > 0,
            latestTag = tag,
            releaseUrl = url,
            apkUrl = apk,
        )
    }.getOrElse {
        Log.w(TAG, "check failed: ${it.message}")
        Result(false, error = it.message ?: "network error")
    }

    /** "v1.2.77" / "1.2.77" → [1,2,77]；无法解析返回空 */
    private fun parseVersion(s: String): List<Int> {
        val cleaned = s.trim().removePrefix("v").removePrefix("V")
        val core = cleaned.split('-', '+').firstOrNull().orEmpty()   // 丢掉 -rc1 之类后缀
        val parts = core.split('.')
        if (parts.isEmpty()) return emptyList()
        val nums = parts.mapNotNull { it.trim().toIntOrNull() }
        return if (nums.isEmpty()) emptyList() else nums
    }

    /** 逐段比较：a>b 返回正，a<b 返回负，相等 0（缺失段按 0 补齐） */
    private fun compare(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}

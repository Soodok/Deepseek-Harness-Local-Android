package app.dsh.mobile.update

import android.content.Context
import android.util.Log
import app.dsh.mobile.DshApp
import java.io.File

/**
 * 自动更新偏好与运行期状态。
 *
 * 与设置页共用 `dsh_ui`（`DshApp.PREFS_UI`），不新建 SharedPreferences 文件 ——
 * 与 `RuntimeDownloadConfig` 一致，避免"设置项散落在多个文件里"。
 *
 * 键名全部以 `update_` 开头，便于设置页与探针统一扫描。
 */
object AppUpdatePrefs {

    // ── 用户设置 ──────────────────────────────────────────────────────────
    private const val KEY_AUTO_CHECK = "update_auto_check"
    private const val KEY_WIFI_ONLY = "update_wifi_only"
    private const val KEY_PROXY_FALLBACK = "update_index_proxy_fallback"
    private const val KEY_PACKAGE_STRATEGY = "update_package_mode_strategy"

    // ── 运行期状态 ────────────────────────────────────────────────────────
    private const val KEY_IGNORED_CODE = "update_ignored_version_code"
    private const val KEY_AUTO_DOWNLOADED_CODE = "update_auto_downloaded_code"
    private const val KEY_PROMPTED_CODE = "update_prompted_version_code"
    private const val KEY_MODE_CHOICE_CODE = "update_mode_choice_code"
    private const val KEY_CHOSEN_PACKAGE = "update_chosen_package"
    private const val KEY_PENDING_JSON = "update_pending_json"
    private const val KEY_DOWNLOADED_CODE = "update_downloaded_version_code"
    private const val KEY_DOWNLOADED_PATH = "update_downloaded_path"
    private const val KEY_LAST_CHECK_AT = "update_last_check_at"
    private const val KEY_LAST_RESULT = "update_last_result"
    private const val KEY_INSTALL_REQUESTED_AT = "update_install_requested_at"

    const val STRATEGY_AUTO = "auto"
    const val STRATEGY_KEEP = "keep"

    private const val TAG = "AppUpdatePrefs"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)

    // ── 设置项 ────────────────────────────────────────────────────────────

    /** 自动检查更新（关掉后只在关于页/设置页手动检查）。 */
    fun autoCheck(ctx: Context): Boolean = p(ctx).getBoolean(KEY_AUTO_CHECK, true)
    fun setAutoCheck(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean(KEY_AUTO_CHECK, on).apply()

    /**
     * 仅在 WiFi（不计费网络）下自动下载。
     * 关掉后计费网络也会静默下载 —— 设置页需要二次确认，避免用户被流量账单惊到。
     */
    fun wifiOnly(ctx: Context): Boolean = p(ctx).getBoolean(KEY_WIFI_ONLY, true)
    fun setWifiOnly(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean(KEY_WIFI_ONLY, on).apply()

    /**
     * 直连取索引失败时是否允许经代理重取（必须验签）。
     * 默认 true：国内直连 GitHub 大概率超时，关掉等于把自动更新关掉。
     */
    fun proxyFallback(ctx: Context): Boolean = p(ctx).getBoolean(KEY_PROXY_FALLBACK, true)
    fun setProxyFallback(ctx: Context, on: Boolean) =
        p(ctx).edit().putBoolean(KEY_PROXY_FALLBACK, on).apply()

    /**
     * 包形态策略：
     *  - [STRATEGY_AUTO]（默认）运行时无变更优先 noruntime；有变更让用户二选一（F2）
     *  - [STRATEGY_KEEP] 始终与当前形态一致（保守，旧行为）
     */
    fun packageStrategy(ctx: Context): String =
        p(ctx).getString(KEY_PACKAGE_STRATEGY, STRATEGY_AUTO) ?: STRATEGY_AUTO
    fun setPackageStrategy(ctx: Context, v: String) =
        p(ctx).edit().putString(KEY_PACKAGE_STRATEGY, v).apply()

    // ── 忽略语义 ──────────────────────────────────────────────────────────

    /**
     * 「忽略此版本」记录的 versionCode。只拦**这一个**版本：
     * 出现更高版本时自动失效（不需要用户手动清除）。
     */
    fun ignoredVersionCode(ctx: Context): Int = p(ctx).getInt(KEY_IGNORED_CODE, 0)
    fun setIgnoredVersionCode(ctx: Context, code: Int) =
        p(ctx).edit().putInt(KEY_IGNORED_CODE, code).apply()
    fun clearIgnored(ctx: Context) = p(ctx).edit().remove(KEY_IGNORED_CODE).apply()

    fun isIgnored(ctx: Context, versionCode: Int): Boolean {
        val ignored = ignoredVersionCode(ctx)
        return ignored > 0 && versionCode <= ignored
    }

    // ── 自动动作去重 ──────────────────────────────────────────────────────

    fun autoDownloadedCode(ctx: Context): Int = p(ctx).getInt(KEY_AUTO_DOWNLOADED_CODE, 0)
    fun setAutoDownloadedCode(ctx: Context, code: Int) =
        p(ctx).edit().putInt(KEY_AUTO_DOWNLOADED_CODE, code).apply()

    fun promptedCode(ctx: Context): Int = p(ctx).getInt(KEY_PROMPTED_CODE, 0)
    fun setPromptedCode(ctx: Context, code: Int) =
        p(ctx).edit().putInt(KEY_PROMPTED_CODE, code).apply()

    /** 形态二选一是否已经问过这个版本（每版本最多问一次）。 */
    fun modeChoiceCode(ctx: Context): Int = p(ctx).getInt(KEY_MODE_CHOICE_CODE, 0)
    fun setModeChoiceCode(ctx: Context, code: Int) =
        p(ctx).edit().putInt(KEY_MODE_CHOICE_CODE, code).apply()

    fun chosenPackage(ctx: Context): String =
        p(ctx).getString(KEY_CHOSEN_PACKAGE, "").orEmpty()
    fun setChosenPackage(ctx: Context, v: String) =
        p(ctx).edit().putString(KEY_CHOSEN_PACKAGE, v).apply()

    // ── 待下载/已下载 ─────────────────────────────────────────────────────

    fun pending(ctx: Context): UpdateInfo? =
        p(ctx).getString(KEY_PENDING_JSON, null)?.let { UpdateInfo.fromJson(it) }

    fun setPending(ctx: Context, info: UpdateInfo?) {
        val e = p(ctx).edit()
        if (info == null) e.remove(KEY_PENDING_JSON) else e.putString(KEY_PENDING_JSON, info.toJson().toString())
        e.apply()
    }

    fun downloadedCode(ctx: Context): Int = p(ctx).getInt(KEY_DOWNLOADED_CODE, 0)
    fun downloadedPath(ctx: Context): String =
        p(ctx).getString(KEY_DOWNLOADED_PATH, "").orEmpty()

    fun setDownloaded(ctx: Context, code: Int, path: String) =
        p(ctx).edit()
            .putInt(KEY_DOWNLOADED_CODE, code)
            .putString(KEY_DOWNLOADED_PATH, path)
            .apply()

    fun clearDownloaded(ctx: Context) = p(ctx).edit()
        .remove(KEY_DOWNLOADED_CODE)
        .remove(KEY_DOWNLOADED_PATH)
        .apply()

    /** 已下载且文件仍在（文件可能被清理器/用户删掉）。 */
    fun downloadedApk(ctx: Context): File? {
        val path = downloadedPath(ctx)
        if (path.isBlank()) return null
        val f = File(path)
        return if (f.isFile && f.length() > 0) f else null
    }

    // ── 检查节奏 ──────────────────────────────────────────────────────────

    fun lastCheckAt(ctx: Context): Long = p(ctx).getLong(KEY_LAST_CHECK_AT, 0L)
    fun setLastCheckAt(ctx: Context, at: Long) =
        p(ctx).edit().putLong(KEY_LAST_CHECK_AT, at).apply()

    /** 上次检查结果（关于页显示「上次检查 22:30 · 已是最新」）。 */
    fun lastResult(ctx: Context): String = p(ctx).getString(KEY_LAST_RESULT, "").orEmpty()
    fun setLastResult(ctx: Context, v: String) =
        p(ctx).edit().putString(KEY_LAST_RESULT, v).apply()

    fun installRequestedAt(ctx: Context): Long = p(ctx).getLong(KEY_INSTALL_REQUESTED_AT, 0L)
    fun setInstallRequestedAt(ctx: Context, at: Long) =
        p(ctx).edit().putLong(KEY_INSTALL_REQUESTED_AT, at).apply()

    // ── 落盘目录 ──────────────────────────────────────────────────────────

    /** `filesDir/update/` —— 不用 cacheDir：存储紧张会被系统回收，250MB 白下。 */
    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "update").apply { mkdirs() }

    fun versionDir(ctx: Context, versionCode: Int): File =
        File(dir(ctx), versionCode.toString()).apply { mkdirs() }

    /** 安装成功后清掉所有安装包（只保留索引缓存，供关于页展示）。 */
    fun purgeApks(ctx: Context) {
        runCatching {
            dir(ctx).listFiles()?.forEach { f ->
                if (f.isDirectory) f.deleteRecursively()
            }
        }
        clearDownloaded(ctx)
        setPending(ctx, null)
    }

    /** 删除**已下载**的那个安装包（关于页「删除安装包」）；保留 pending，可重新下。 */
    fun deleteDownloaded(ctx: Context) {
        val code = downloadedCode(ctx)
        clearDownloaded(ctx)
        if (code <= 0) return
        runCatching { File(dir(ctx), code.toString()).deleteRecursively() }
    }

    /**
     * 清理"不该继续占地方"的残留（下载前调用）：
     *  - 其它版本号目录（用户连跳两版就会 2×200MB+，以前只有安装成功才清）；
     *  - 当前目录里超过 7 天的 `.part`（续传只剩它一半价值，再留就是白占）。
     *
     * @param keepVersionCode 本次要用的版本目录（<=0 表示只清过期 `.part`）
     */
    fun cleanupStale(ctx: Context, keepVersionCode: Int) {
        val expireBefore = System.currentTimeMillis() - PART_TTL_MS
        runCatching {
            val dirs = dir(ctx).listFiles().orEmpty()
            for (f in dirs) {
                if (!f.isDirectory) continue
                val code = f.name.toIntOrNull()
                if (code == null) {
                    // 非版本目录（理论上不存在）：只清过期文件，不整目录删
                    f.listFiles()?.filter { it.lastModified() in 1 until expireBefore }
                        ?.forEach { it.delete() }
                    continue
                }
                if (keepVersionCode > 0 && code != keepVersionCode) {
                    if (f.deleteRecursively()) {
                        Log.i(TAG, "cleanup: dropped stale version dir $code")
                    }
                    continue
                }
                f.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".part") && file.lastModified() in 1 until expireBefore) {
                        if (file.delete()) Log.i(TAG, "cleanup: dropped expired ${file.name}")
                    }
                }
            }
        }
    }

    private const val PART_TTL_MS = 7L * 24 * 60 * 60 * 1000
}

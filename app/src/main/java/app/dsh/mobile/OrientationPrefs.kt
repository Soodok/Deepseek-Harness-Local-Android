package app.dsh.mobile

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo

/**
 * 屏幕朝向偏好（v1.2.120）：三态 portrait / landscape / auto。
 *
 * 历史：此前只有一个布尔 `landscape`（dsh_ui prefs，默认 false=竖屏）。
 * 本次升级为字符串 `orientation` 三态；旧布尔键保留为迁移源——
 * `get()` 在新键缺失时读一次旧键，之后只写新键（不做破坏性清理）。
 *
 * v1.4.18（平板横屏适配）：**从未设置过**偏好的大屏设备（sw >= 600dp）
 * 默认 AUTO —— 平板开箱即可横屏，用户显式选过竖屏的仍然尊重竖屏。
 * 手机默认仍是 PORTRAIT，行为不变。所有原生设置类页面统一用
 * [applyTo] 应用该偏好（此前它们各自硬编码竖屏）。
 */
enum class OrientationMode(val key: String) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    AUTO("auto");

    companion object {
        fun fromKey(key: String?): OrientationMode = when (key) {
            "landscape" -> LANDSCAPE
            "auto" -> AUTO
            else -> PORTRAIT   // 含历史默认：旧实现 landscape=false 即竖屏
        }
    }
}

object OrientationPrefs {
    private const val KEY = "orientation"
    private const val KEY_LEGACY = "landscape"

    fun get(ctx: Context): OrientationMode {
        val p = prefs(ctx)
        val raw = p.getString(KEY, null)
        if (raw != null) return OrientationMode.fromKey(raw)
        // 迁移：老安装没有 orientation 键，回退到旧布尔（false=竖屏）
        if (p.contains(KEY_LEGACY)) {
            return if (p.getBoolean(KEY_LEGACY, false)) OrientationMode.LANDSCAPE
            else OrientationMode.PORTRAIT
        }
        // v1.4.18：完全没有偏好时，大屏默认跟随旋转（手机仍默认竖屏）
        return defaultFor(ctx)
    }

    /** 未设置任何偏好时的默认值：大屏 AUTO，其它设备 PORTRAIT。 */
    fun defaultFor(ctx: Context): OrientationMode =
        if (isLargeScreen(ctx)) OrientationMode.AUTO else OrientationMode.PORTRAIT

    fun set(ctx: Context, mode: OrientationMode) {
        prefs(ctx).edit().putString(KEY, mode.key).apply()
    }

    /**
     * 把偏好真正落到 Activity 的 requestedOrientation。
     *
     * 所有原生设置类页面统一调用；设置页在用户切换偏好后也立即调用，
     * 这样平板用户选「横屏」能当场看到效果，而不是等返回主界面。
     */
    fun applyTo(activity: Activity) {
        activity.requestedOrientation = toActivityInfo(get(activity))
    }

    /**
     * 强制竖屏（v1.4.64，宝宝反馈「横屏模式只是网页以横屏显示，但设置还是
     * 要为竖屏」）。
     *
     * 主界面的横屏偏好只该作用于 **WebView 里的网页**；设置页是原生表单，
     * 横过来后行宽被拉满、控件间距失衡，反而难用。所有原生设置类页面
     * （Settings / Appearance / ExtensionVoice / Maintenance / Backup /
     * UpdateSettings / About）统一调这个，不再跟随用户的横屏偏好。
     *
     * 与 [applyTo] 的区别：那个跟随偏好（主界面用），这个无条件竖屏。
     */
    fun forcePortrait(activity: Activity) {
        activity.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /** 大屏判定用最小宽度（smallestScreenWidthDp），折叠屏展开后同样命中。 */
    private fun isLargeScreen(ctx: Context): Boolean =
        ctx.resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP

    /**
     * 偏好 → requestedOrientation。
     *
     * ⚠️ 竖屏必须显式 PORTRAIT；UNSPECIFIED 的语义是「交给系统/传感器」，
     * **不是**锁定竖屏（旧实现踩过这个坑，见 MainActivity 历史注释）。
     * auto 用 SENSOR 家族里的 USER：跟随物理传感器，同时尊重系统的
     * 「旋转锁定」总开关（用户锁旋转时保持当前朝向，不强行转）。
     */
    fun toActivityInfo(mode: OrientationMode): Int = when (mode) {
        OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_USER
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)

    /** Material 大屏断点（平板/折叠屏展开后的最小宽度） */
    private const val LARGE_SCREEN_MIN_WIDTH_DP = 600
}

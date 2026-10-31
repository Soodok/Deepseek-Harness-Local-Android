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
 * 手机默认仍是 PORTRAIT，行为不变。
 *
 * v1.5.x（平板回退修复）：v1.4.64 让 13 个设置类页面无条件锁竖屏，
 * 平板从横屏主界面点进设置会被整块扳成竖屏。现在统一走
 * [applyToSettingsPage]：大屏跟随偏好、手机锁竖屏，并新增
 * [setFollowInSettings] 开关让用户要求「任意场景都跟随偏好」。
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

    /**
     * 「设置类页面也跟随朝向偏好」开关（v1.5.x）。
     *
     * 默认关 = 只有大屏（平板/折叠展开）的设置页跟随偏好，手机仍锁竖屏；
     * 打开后 = 任何尺寸的设置页都跟随 [get] 的结果（等同于主界面）。
     */
    private const val KEY_SETTINGS_FOLLOW = "settings_follow_orientation"

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

    /** 「设置类页面跟随朝向偏好」是否开启（默认关，见 [KEY_SETTINGS_FOLLOW]）。 */
    fun followInSettings(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SETTINGS_FOLLOW, false)

    fun setFollowInSettings(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SETTINGS_FOLLOW, on).apply()
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
     * 设置类原生页面的朝向入口（v1.5.x，取代原先无条件的 [forcePortrait]）。
     *
     * 判定顺序：
     *  1. 用户打开「设置页跟随屏幕方向」开关 → 任意设备都跟随偏好（[applyTo]）；
     *  2. 否则大屏（sw >= 600dp，平板/折叠屏展开）→ 跟随偏好。
     *     v1.4.18 给平板的开箱横屏在这里被 v1.4.64 的固定竖屏误伤过，
     *     现在的规则是「大屏尊重偏好，手机才锁竖屏」；
     *  3. 其余（手机）→ 保持 v1.4.64 的竖屏：横屏偏好只作用于网页，
     *     设置页横过来后行宽被拉满、控件间距失衡，反而难用。
     *
     * 注意：偏好的默认值本身对大屏就是 AUTO（见 [defaultFor]），
     * 所以平板从未设置过偏好时，进设置页可以直接跟着转屏。
     */
    fun applyToSettingsPage(activity: Activity) {
        if (followInSettings(activity) || isLargeScreen(activity)) applyTo(activity)
        else forcePortrait(activity)
    }

    /**
     * 无条件锁竖屏（v1.4.64）：仅供 [applyToSettingsPage] 在「手机 + 开关关闭」时使用，
     * **不要**再直接调用 —— v1.4.64 曾让 13 个页面直接调它，结果平板进设置被扳成竖屏
     * （v1.5.x 修复）。
     *
     * 保留的历史背景：设置页是原生表单，横过来后行宽被拉满、控件间距失衡。
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

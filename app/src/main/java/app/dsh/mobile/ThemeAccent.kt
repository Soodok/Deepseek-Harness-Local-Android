package app.dsh.mobile

import android.content.Context
import com.google.android.material.color.DynamicColors

/**
 * 动态取色偏好（v1.6.x）。
 *
 * 按 Google 官方做法，颜色只有两种来源：
 *   · 动态取色（Android 12+ 系统 Monet，跟随壁纸）
 *   · 关闭时使用主题里定义的静态 MD3 色板
 *
 * 参考 https://developer.android.com/develop/ui/views/theming/dynamic-colors
 *
 * 之前这里还有「预设色 / 自定义色」两条分支，它们要用 content-based scheme
 * 覆盖语义色，等于在 MD3 之上再造一套配色，既不符合规范，也容易把
 * surface / onSurface 换成中性色导致卡片和图标失去层次。现已移除。
 */
object ThemeAccent {

    private const val PREFS = "dsh_ui_prefs"
    private const val KEY_DYNAMIC = "theme_dynamic_enabled"

    /** 使用 Material 库的设备可用性判断，而非仅检查系统版本。 */
    val dynamicSupported: Boolean
        get() = DynamicColors.isDynamicColorAvailable()

    /**
     * 用户是否打开了动态取色。
     *
     * 旧版本存过 `theme_accent_mode`（PRESET/CUSTOM/DYNAMIC），这里兼容迁移：
     * 存过 DYNAMIC 视为开启，其余视为关闭。
     */
    fun dynamicEnabled(ctx: Context): Boolean {
        if (!dynamicSupported) return false
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_DYNAMIC)) return prefs.getBoolean(KEY_DYNAMIC, false)
        val legacy = prefs.getString("theme_accent_mode", null)
        val on = legacy == "DYNAMIC"
        prefs.edit().putBoolean(KEY_DYNAMIC, on).apply()
        return on
    }

    fun setDynamicEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DYNAMIC, enabled).apply()
    }
}

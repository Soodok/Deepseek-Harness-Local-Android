package app.dsh.mobile

import android.content.Context
import android.os.Build

/**
 * 主题强调色的三种来源（v1.5.x 主题页）。
 *
 *  · [Mode.PRESET]  —— 用 [Accent] 里的预设色（天蓝/靛蓝/青绿/紫/琥珀）
 *  · [Mode.CUSTOM]  —— 用户自选的一个 ARGB 颜色（色相/饱和度/明度滑杆或十六进制输入）
 *  · [Mode.DYNAMIC] —— 跟随系统 Monet 动态取色（Android 12+，从壁纸取色）
 *
 * 之所以单独放一份偏好、而不是塞进 [Accent] 枚举：枚举的取值固定，装不下
 * 「用户随便挑的颜色」；而 [Accent] 又要保持给引导页等旧代码用，所以这里只做
 * 「当前该显示什么颜色」的解析，不改变枚举本身。
 */
object ThemeAccent {

    enum class Mode { PRESET, CUSTOM, DYNAMIC }

    private const val PREFS = "dsh_ui_prefs"
    private const val KEY_MODE = "theme_accent_mode"
    private const val KEY_CUSTOM = "theme_accent_custom"

    /** 默认自定义色：与天蓝预设一致，避免第一次进页面看到纯黑。 */
    val DEFAULT_CUSTOM: Int = Accent.SKY.argb

    /** 当前系统是否支持 Monet 动态取色（Android 12 / API 31）。 */
    val dynamicSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun mode(ctx: Context): Mode {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.PRESET.name)
        val parsed = runCatching { Mode.valueOf(raw ?: Mode.PRESET.name) }.getOrNull()
            ?: Mode.PRESET
        // 旧系统选了动态色 → 回落预设，否则整页会是死色
        return if (parsed == Mode.DYNAMIC && !dynamicSupported) Mode.PRESET else parsed
    }

    fun setMode(ctx: Context, mode: Mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }

    /** 当前模式对应的 Material 配色种子色：预设/自定义都取解析后的强调色。 */
    fun seedColor(ctx: Context): Int = Accent.current(ctx).argb

    fun customColor(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CUSTOM, DEFAULT_CUSTOM)

    fun setCustomColor(ctx: Context, argb: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CUSTOM, argb or 0xFF000000.toInt()).apply()
    }
}

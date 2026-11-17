package app.dsh.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView

/**
 * 强调色方案（v1.4.23）。
 *
 * ## 为什么需要
 *
 * v1.4.22 把一个社区移植包的 DSHA 靛蓝 `#4F6EF7` 直接写进了 22 个文件里，换掉了
 * 原来的天蓝 `#7DD3FC`。宝宝反馈「跟主题不搭、想保留原色」——正确做法不是再拍一次
 * 板，而是让配色成为用户选项。
 *
 * ## 做法
 *
 * 颜色只在两处定义：本枚举 + [apply] 的转换。界面代码不再写死 `0xFFxxxxxx`，
 * 一律通过 `Accent.current(ctx).on(...)` 取。改配色只需改偏好，无需重装。
 *
 * 注意：XML 布局里的 `android:textColor="#FF..."` 无法运行时切换，因此这些位置
 * 统一改为 [apply] 在代码里覆盖（见各 Activity 的 `applyAccent()`）。
 */
enum class Accent(
    val id: String,
    val labelRes: Int,
    /** ARGB 主色 */
    val color: Long,
    /** 同名次要色（徽标底、描边等），低透明度叠加在深色背景上 */
    val softAlpha: Int = 0x33,
) {
    /** 原项目配色（默认）：天蓝 */
    SKY("sky", R.string.accent_sky, 0xFF7DD3FC),
    /** DSHA 靛蓝 */
    INDIGO("indigo", R.string.accent_indigo, 0xFF4F6EF7),
    /** 青绿 */
    TEAL("teal", R.string.accent_teal, 0xFF2DD4BF),
    /** 紫 */
    VIOLET("violet", R.string.accent_violet, 0xFFA78BFA),
    /** 琥珀（暖色，夜间刺眼程度低一点） */
    AMBER("amber", R.string.accent_amber, 0xFFF59E0B);

    /** 不透明 ARGB int，用于 setTextColor / tint */
    val argb: Int get() = color.toInt()

    /** 半透明版本（徽标背景、选中态底） */
    val softArgb: Int get() = (softAlpha.toLong() shl 24 or (color and 0xFFFFFF)).toInt()

    /** 圆角实心背景（胶囊按钮 / 选中行） */
    fun filledDrawable(radiusDp: Float = 999f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp
        setColor(argb)
    }

    /** 描边胶囊（与既有 bg_btn_outline 同形，但换色） */
    fun outlinedDrawable(radiusDp: Float = 999f, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp
            setColor(0x00000000)
            setStroke(strokeDp.toInt().coerceAtLeast(1), argb)
        }

    /** 把枚举预设转成统一的 [AccentColor]。 */
    fun toColor(): AccentColor = AccentColor(argb, labelRes)

    companion object {
        private const val PREFS = "dsh_ui_prefs"
        private const val KEY = "accent_scheme"

        fun fromId(id: String?): Accent = entries.firstOrNull { it.id == id } ?: SKY

        /** 用户选的**预设**（引导页、预设色块用）；不受自定义/动态模式影响。 */
        fun currentPreset(ctx: Context): Accent {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return fromId(prefs.getString(KEY, null))
        }

        /** 当前**实际生效**的强调色：预设 / 自定义 / 系统动态色三者之一。 */
        fun current(ctx: Context): AccentColor = resolve(ctx)

        fun set(ctx: Context, accent: Accent) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, accent.id).apply()
            // 选预设色 = 明确退出自定义/动态模式
            ThemeAccent.setMode(ctx, ThemeAccent.Mode.PRESET)
        }

        /**
         * 解析当前强调色。
         *
         * 动态模式从当前主题的 `colorPrimary` 取（Material Components 的
         * DynamicColors 已经把 Monet 色写进主题），取不到时回落预设色，
         * 保证任何情况下都有可见颜色而不是透明。
         */
        internal fun resolve(ctx: Context): AccentColor {
            val preset = currentPreset(ctx)
            return when (ThemeAccent.mode(ctx)) {
                ThemeAccent.Mode.CUSTOM -> {
                    val argb = ThemeAccent.customColor(ctx)
                    AccentColor(argb, preset.labelRes, custom = true)
                }
                ThemeAccent.Mode.DYNAMIC -> {
                    // ★ v1.8.1：不再读 MaterialColors.getColor(colorPrimary)。
                    // 那条路要求 DynamicColors 已经把 Monet 装进主题，而我们在
                    // ThemedActivity 里刻意不再注入 DynamicColors（它会改 Resources
                    // → 触发第二次 relaunch → 用户看到闪屏）。现在直接读系统
                    // system_accent1 资源，不碰主题、不碰配置。
                    val fallback = preset.argb
                    val dark = ThemeManager.isDarkNow(ctx)
                    val primary = SystemAccent.primary(ctx, dark, fallback)
                    AccentColor(primary, R.string.theme_color_dynamic, dynamic = true)
                }
                ThemeAccent.Mode.PRESET -> preset.toColor()
            }
        }
    }
}

/**
 * 一次解析结果：既可当 `Int` 颜色用（[argb]），也保留枚举时代的
 * `softArgb` / `filledDrawable` / `outlinedDrawable`，让 41 处调用点不用改。
 */
class AccentColor(
    val argb: Int,
    val labelRes: Int,
    val custom: Boolean = false,
    val dynamic: Boolean = false,
) {
    /** 半透明版本（徽标背景、选中态底） */
    val softArgb: Int get() = (0x33 shl 24) or (argb and 0xFFFFFF)

    fun filledDrawable(radiusDp: Float = 999f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp
        setColor(argb)
    }

    fun outlinedDrawable(radiusDp: Float = 999f, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x00000000)
            setStroke(strokeDp.toInt().coerceAtLeast(1), argb)
        }

    /**
     * 把强调色当**前景色**（文字、小图标）用时的取值。
     *
     * 深色主题原样返回（预设色就是为深底挑的）；浅色主题压暗到对 [surface] 达到
     * [minContrast]。算法在 [ContrastColor]（纯 Int 运算，可跑 JVM 测试）。
     */
    fun onSurface(
        isDark: Boolean,
        surface: Int = if (isDark) 0xFF101418.toInt() else 0xFFF5F7FA.toInt(),
        minContrast: Double = 4.5,

    ): Int = if (isDark) argb else ContrastColor.readableOn(argb, surface, minContrast)
}

/**
 * 把强调色应用到单个控件。布局里的硬编码颜色在运行时无法改变，因此相关位置
 * 统一在代码里调这些扩展函数覆盖。
 */
object AccentApply {
    /** 文字色 = 强调色 */
    fun TextView.accent(): Unit = setTextColor(Accent.current(context).argb)

    /** 文字色 = 强调色，并按需设置粗体（选中标签用） */
    fun TextView.accentBold(bold: Boolean): Unit {
        setTextColor(Accent.current(context).argb)
        setTypeface(null, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /** 描边胶囊背景 */
    fun TextView.accentOutline(): Unit = run {
        val a = Accent.current(context)
        val dp = resources.displayMetrics.density
        background = a.outlinedDrawable(strokeDp = 1f * dp)
        setTextColor(a.argb)
    }

    /** 实心胶囊背景 + 反色文字 */
    fun TextView.accentFilled(): Unit = run {
        val a = Accent.current(context)
        background = a.filledDrawable()
        setTextColor(0xFFFFFFFF.toInt())
    }

    /** CompoundButton / ProgressBar 的 tint */
    fun android.widget.ProgressBar.accentTint(): Unit {
        val tint = ColorStateList.valueOf(Accent.current(context).argb)
        progressTintList = tint
        indeterminateTintList = tint
    }

    fun android.widget.SeekBar.accentTint(): Unit {
        val tint = ColorStateList.valueOf(Accent.current(context).argb)
        progressTintList = tint
        thumbTintList = tint
    }

    fun android.widget.Switch.accentTint(): Unit {
        val tint = ColorStateList.valueOf(Accent.current(context).argb)
        thumbTintList = tint
        trackTintList = ColorStateList.valueOf(Accent.current(context).softArgb)
    }

    /** 给 View 的 backgroundTint 上色（用于既有 drawable 的染色） */
    fun android.view.View.accentBackgroundTint(): Unit {
        backgroundTintList = ColorStateList.valueOf(Accent.current(context).argb)
    }

    /** dp 转 px 的小工具（局部用） */
    fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

    /** 解析主题属性（保留给后续主题化用） */
    fun Context.themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }
}

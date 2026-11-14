package app.dsh.mobile

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup

/** 原生壳主题：深色（默认）/ 浅色 / 跟随系统。WebView 由 dsh 自己管理，不在这里处理。 */
enum class ThemeMode(val key: String, val labelRes: Int) {
    DARK("dark", R.string.theme_dark),
    LIGHT("light", R.string.theme_light),
    SYSTEM("system", R.string.theme_system);

    companion object {
        fun fromKey(key: String?): ThemeMode =
            values().firstOrNull { it.key == key } ?: DARK
    }
}

object ThemePref {
    private const val KEY = "theme_mode"

    fun get(context: Context): ThemeMode =
        ThemeMode.fromKey(
            context.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
                .getString(KEY, ThemeMode.DARK.key),
        )

    fun set(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.key).apply()
    }
}

object ThemeManager {
    /** 让 Activity 的 resources 按偏好选择 values / values-night。 */
    fun wrap(base: Context): Context {
        val mode = ThemePref.get(base)
        if (mode == ThemeMode.SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        val night = if (mode == ThemeMode.DARK) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 应用此刻**实际生效**的明暗（v1.7.11）。
     *
     * 为什么不能直接用 [isDark]：它读的是 resources 的 uiMode，只有经过
     * [wrap] 的 Activity 才反映「外观主题」偏好。MainActivity 只包了 Locale
     * （WebView 自己管配色），它的 uiMode 是**系统**的值 —— 用户把 App 设成
     * 浅色、系统是深色时，灵动岛会错判成深色。所以这里偏好优先，SYSTEM 才看系统。
     */
    fun isDarkNow(context: Context): Boolean = when (ThemePref.get(context)) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isDark(context)
    }

    /**
     * 把我们的主题偏好同步给 androidx.appcompat 的全局夜间模式。
     *
     * 为什么必须做：`md3Dialog()` 造出来的是 `androidx.appcompat.app.AlertDialog`，
     * 它的 AppCompatDelegate 在构造时会跑一次 `applyDayNight()`。本工程的 Activity
     * 是纯 `android.app.Activity`，没有 AppCompat 的本地夜间模式，于是 delegate 认为
     * 「跟随系统」——系统是白天时，它会把 **Activity 共享的那份 Resources** 的
     * uiMode 从 32 就地改成 16（`Resources.updateConfiguration`）。后果：
     *   · 弹窗容器取到 values/ 的 dialog_bg（白）与 text_primary（近黑），
     *     深色页面里就成了「深底浅字看不清」；
     *   · 该 Resources 是全局共享的，翻成白天后本页后续所有资源解析都错。
     * 实测证据：preCreate hostUi=32 → builderCreate hostUi=16（同一 Activity 实例）。
     *
     * 只要让 appcompat 的默认夜间模式与 ThemePref 一致，它 applyDayNight 算出来的
     * 值就与我们相同，updateConfiguration 不产生实际改动，链路自然消失。
     */
    fun syncAppCompatNight(context: Context) {
        val want = when (ThemePref.get(context)) {
            ThemeMode.DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.SYSTEM -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        // 只在真的不一致时才设：setDefaultNightMode 会通知所有活跃 delegate，
        // 每次都调会让正在显示的弹窗跟着走一遍 applyDayNight。
        if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != want) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(want)
        }
    }

    fun applyBars(activity: Activity) {
        val bg = barBackground(activity)
        activity.window.statusBarColor = bg
        activity.window.navigationBarColor = bg
        val lightSurface = androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.5
        val controller = androidx.core.view.WindowInsetsControllerCompat(
            activity.window, activity.window.decorView,
        )
        controller.isAppearanceLightStatusBars = lightSurface
        controller.isAppearanceLightNavigationBars = lightSurface
    }

    /** System and preset palettes both supply their own Material surface role. */
    private fun barBackground(activity: Activity): Int {
        val tv = android.util.TypedValue()
        val ok = activity.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, tv, true,
        )
        if (!ok) return activity.getColor(R.color.bg)
        return if (tv.resourceId != 0) {
            runCatching { activity.getColor(tv.resourceId) }.getOrDefault(activity.getColor(R.color.bg))
        } else {
            tv.data
        }
    }
}

fun IslandPalette.themed(context: Context): IslandPalette.Colors {
    fun role(attr: Int) = com.google.android.material.color.MaterialColors.getColor(context, attr, "IslandPalette")
    val panel = role(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    val onSurface = role(com.google.android.material.R.attr.colorOnSurface)
    val onVariant = role(com.google.android.material.R.attr.colorOnSurfaceVariant)
    val primary = role(androidx.appcompat.R.attr.colorPrimary)
    val tertiary = role(com.google.android.material.R.attr.colorTertiary)
    return IslandPalette.Colors(
        panel = panel,
        handle = panel,
        title = onSurface,
        body = onSurface,
        muted = onVariant,
        accent = primary,
        divider = role(com.google.android.material.R.attr.colorOutlineVariant),
        dot = primary,
        ok = tertiary,
        okSoft = androidx.core.graphics.ColorUtils.setAlphaComponent(tertiary, 31),
        warn = role(androidx.appcompat.R.attr.colorError),
        outline = if (ThemeManager.isDark(context)) 0 else role(com.google.android.material.R.attr.colorOutline),
    )
}

/** 所有原生 Activity 的基类：attachBaseContext 里应用主题，onCreate 里统一系统栏。 */
open class ThemedActivity : Activity() {

    /** 使用官方 Material 对话框主题和动态语义色，不覆盖其窗口背景。 */
    protected fun md3Dialog(): com.google.android.material.dialog.MaterialAlertDialogBuilder =
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeAccent.wrap(ThemeManager.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 保留上游 AppCompat 深浅色同步，避免弹窗把 Activity 的资源配置改回系统模式。
        // 动态色已由 Application 的生命周期回调应用，这里不再覆盖 Activity 主题。
        ThemeManager.syncAppCompatNight(this)
        val paletteOverlay = ThemeAccent.overlayRes(this)
        if (paletteOverlay != 0) theme.applyStyle(paletteOverlay, true)
        super.onCreate(savedInstanceState)
        ThemeManager.applyBars(this)
    }


    // ================= 设置页自定义背景图 =================

    /**
     * 本 Activity 是否使用「设置页背景图」（外观与显示 → 设置页背景图）。
     *
     * 默认 false：主界面、终端、文件编辑器等不属于设置体系的页面保持原样。
     * 设置体系内的页面（设置主页与各二级页）覆盖为 true，从而整条设置路径
     * 都能看到同一张背景图 —— 而不是只有点进设置的那一屏有。
     */
    protected open val usesSettingsBackground: Boolean get() = false

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applySettingsBackground()
        if (!usesSettingsBackground) applyAccentTint()
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applySettingsBackground()
        if (!usesSettingsBackground) applyAccentTint()
    }

    override fun onResume() {
        super.onResume()
        // 从外观页选完图返回时立即生效，不需要用户手动刷新
        if (usesSettingsBackground) applySettingsBackground()
        // 强调色可能在别的页面被改过（主题页重建的只是它自己），
        // 回本页时重新铺一次；未改色时是幂等的空操作。
        if (!usesSettingsBackground) applyAccentTint()
    }

    /**
     * 把用户选的强调色铺到当前页（v1.7.9）。
     *
     * 布局里有 87 处 `@color/accent`、88 处 `?attr/colorPrimary`，全部在资源解析期
     * 定型，不跟随偏好 —— 只有代码里显式取 `Accent.current()` 的位置会变色，
     * 于是同一个页面一半变了、一半没变。这里在视图树挂好之后统一改写一遍。
     *
     * 为什么要 `post`：setContentView 返回时子 View 的 tint / textColor 可能还没
     * 最终确定（Material 控件在 onAttachedToWindow 里补样式），立刻遍历会漏。
     */
    protected fun applyAccentTint() {
        val decor = runCatching { window?.decorView }.getOrNull() ?: return
        decor.post { runCatching { AccentTint.apply(decor) } }
    }



    /**
     * 把用户选的背景图铺到当前页内容根上，并把分组卡片降到半透明 ——
     * 否则卡片不透明会把图挡得只剩卡片缝隙。
     *
     * 幂等：每次都按当前偏好重算。未设置（或用户刚点「移除」）时还原成页面
     * 原本的背景与卡片不透明度，不会残留旧图。
     *
     * 原始值存在 WeakHashMap 而不是 View.setTag：`setTag(Object)` 只有一个槽，
     * 同一 View 上如果再存别的标记会互相覆盖；用 Map 更安全且不影响 View 本身。
     */
    protected fun applySettingsBackground() {
        if (!usesSettingsBackground) return
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? View ?: return

        val originalBg = originalBackgrounds.getOrPut(root) { root.background }

        val file = CustomVisualPref.settingsBgFile(this)
        if (file == null) {
            if (root.background !== originalBg) root.background = originalBg
            restoreCardOpacity(root)
            return
        }
        // 真高斯模糊 + 缓存：同一张图同一强度只算一次，否则每次 onResume
        // 都要跑一遍逐像素卷积，切页面会明显卡。
        val shown = ImageFx.cachedBlur(
            this,
            file,
            // 存的是档位百分比，这里换算成卷积强度（ImageFx 内部仍按强度计算）
            CustomVisualPref.blurStrengthOf(CustomVisualPref.settingsBgBlur(this)),
        ) ?: return
        // 注意：apply { } 里的 this 是 BitmapDrawable，不是 Activity ——
        // 需要 Context 的调用必须提前算好，否则会把 Drawable 当 Context 传进去。
        val bgAlpha = ImageFx.alphaOf(CustomVisualPref.settingsBgAlpha(this))
        root.background = android.graphics.drawable.BitmapDrawable(resources, shown).apply {
            // FILL：等比放大铺满、居中裁切，不拉伸变形
            gravity = android.view.Gravity.FILL
            alpha = bgAlpha
        }
        dimCards(root)
    }

    /** 卡片降透明度，让背景图透上来（86% 下深色正文仍清晰）。 */
    private fun dimCards(root: View) {
        // 系数从写死 0.86 改为用户可调（外观页「控件不透明度」）：
        // 背景图色调各不相同，固定值总有人看不清。
        val pct = CustomVisualPref.settingsBgControlAlpha(this)
        eachView(root) { v ->
            // 必须 mutate() 后再改色：同一份 android:background 资源常被多个 View
            // 共享同一个 GradientDrawable 实例，直接 setColor 会连带压暗别的页面。
            val bg = v.background?.mutate()
            if (bg is android.graphics.drawable.GradientDrawable) {
                val current = runCatching { bg.color?.defaultColor }.getOrNull() ?: return@eachView
                // 必须以**原始色**为基准计算：若拿当前色再乘一次系数，
                // 每调一次滑块 alpha 就再缩一次，越调越淡（真机反馈「不生效」）。
                val original = originalCardColors.getOrPut(v) { current }
                val a = android.graphics.Color.alpha(original) * pct / 100
                bg.setColor(
                    android.graphics.Color.argb(
                        a,
                        android.graphics.Color.red(original),
                        android.graphics.Color.green(original),
                        android.graphics.Color.blue(original),
                    ),
                )
                v.background = bg
            }
        }
    }

    /** 复原卡片原来的不透明度（移除背景图后调用）。 */
    private fun restoreCardOpacity(root: View) {
        eachView(root) { v ->
            val saved = originalCardColors.remove(v) ?: return@eachView
            val bg = v.background?.mutate()
            if (bg is android.graphics.drawable.GradientDrawable) {
                bg.setColor(saved)
                v.background = bg
            }
        }
    }

    private fun eachView(v: View, visit: (View) -> Unit) {
        visit(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) eachView(v.getChildAt(i), visit)
        }
    }

    private companion object {
        /** 背景图启用时卡片的不透明度 */
        const val CARD_ALPHA_ON_BG = 0.86f
    }

    /** 内容根原始背景（首次应用前记下） */
    private val originalBackgrounds =
        java.util.WeakHashMap<View, android.graphics.drawable.Drawable?>()

    /** 卡片原始底色（首次压暗前记下） */
    private val originalCardColors = java.util.WeakHashMap<View, Int>()
}

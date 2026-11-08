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

    fun applyBars(activity: Activity) {
        val dark = isDark(activity)
        // 跟随主题的 colorSurface；动态取色启用时它就是壁纸派生色，
        // 写死 @color/bg 会让状态栏在切换主题瞬间闪回旧色。
        val tv = android.util.TypedValue()
        val bg = if (activity.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurface, tv, true,
            )
        ) {
            if (tv.resourceId != 0) activity.getColor(tv.resourceId) else tv.data
        } else {
            activity.getColor(R.color.bg)
        }
        activity.window.statusBarColor = bg
        activity.window.navigationBarColor = bg
        var flags = activity.window.decorView.systemUiVisibility
        flags = if (dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        activity.window.decorView.systemUiVisibility = flags
    }
}

/** 所有原生 Activity 的基类：attachBaseContext 里应用主题，onCreate 里统一系统栏。 */
open class ThemedActivity : Activity() {

    /** 统一 MD3 对话框入口，所有设置子页的选择/确认窗口共用同一套按钮涟漪和色彩。 */
    protected fun md3Dialog(): com.google.android.material.dialog.MaterialAlertDialogBuilder =
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)

    protected fun com.google.android.material.dialog.MaterialAlertDialogBuilder.showMd3(): androidx.appcompat.app.AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
        }.also { it.show() }
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 设置体系页面用 MD3 主题（Theme.Dsh.MD3），其余原样。
        // 必须在 super.onCreate 之前 setTheme：这里显式设主题会覆盖
        // AndroidManifest 里 activity 的 theme 属性，所以由 usesSettingsBackground
        // 统一决定，而不是靠每个 Activity 在 Manifest 里各写一遍。
        setTheme(if (usesSettingsBackground) R.style.Theme_Dsh_MD3 else R.style.Theme_Dsh)
        // 动态色必须在 setTheme 之后应用：setTheme 会重置主题，早于它的覆盖会被抹掉。
        applyDynamicColor()
        super.onCreate(savedInstanceState)
        ThemeManager.applyBars(this)
    }


    /**
     * 按主题页选择的模式，把 Material 配色注入当前主题。
     *
     *  · DYNAMIC —— 系统壁纸 Monet（Android 12+）
     *  · PRESET / CUSTOM —— 以强调色为种子生成 content-based 色板
     *
     * 一次性应用，不做任何 recreate，避免和页面自身的重建互相触发。
     */
    private fun applyDynamicColor() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        runCatching {
            when (ThemeAccent.mode(this)) {
                ThemeAccent.Mode.DYNAMIC ->
                    com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
                ThemeAccent.Mode.PRESET, ThemeAccent.Mode.CUSTOM ->
                    com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(
                        this,
                        com.google.android.material.color.DynamicColorsOptions.Builder()
                            .setContentBasedSource(ThemeAccent.seedColor(this))
                            .build(),
                    )
            }
        }
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
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applySettingsBackground()
    }

    override fun onResume() {
        super.onResume()
        // 从外观页选完图返回时立即生效，不需要用户手动刷新
        if (usesSettingsBackground) applySettingsBackground()
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

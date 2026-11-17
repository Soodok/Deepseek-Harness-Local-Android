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
        val dark = isDark(activity)
        val bg = barBackground(activity)
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

    /**
     * 系统栏底色 = 当前风格的页面底（`?attr/dshBg`）。
     *
     * 历史（v1.7.x 移植 PR #102）：那时 DYNAMIC 模式会在 onCreate 里注入
     * DynamicColors，它把 Monet 的 `colorSurface` 覆盖进主题，而 `@color/bg`
     * 是资源解析期定型的静态值，于是系统栏与页面底不同色，切页会闪一下旧色。
     * 当时的解法是「DYNAMIC 时读 colorSurface」。
     *
     * ★ v1.8.1：我们**不再注入 DynamicColors**（它改 Resources 配置，会触发第二次
     * relaunch —— 见 [ThemedActivity.onCreate] 的说明），因此主题里的 colorSurface
     * 恒等于我们自己的 md3_surface（卡片色，比页面底更白），拿它当系统栏会
     * 出现一条色带。所以这里无条件读 dshBg —— 它本来就是「当前风格的页面底」，
     * 风格切换时自动跟着变，也不受强调色来源影响。
     */
    private fun barBackground(activity: Activity): Int =
        activity.dshColor(R.attr.dshBg, R.color.bg)
}

/** 所有原生 Activity 的基类：attachBaseContext 里应用主题，onCreate 里统一系统栏。 */
open class ThemedActivity : Activity() {

    /**
     * 统一 MD3 对话框入口，所有设置子页的选择/确认窗口共用同一套按钮涟漪和色彩。
     *
     * 返回的是 [TintedMaterialDialogBuilder]：它在 `show()` 时把当前强调色铺到
     * 对话框自己的窗口上。对话框是**独立窗口**，不在 Activity 的视图树里，
     * ThemedActivity 的 applyAccentTint 够不到（见 [AccentTint] 的类注释）。
     */
    protected fun md3Dialog(): com.google.android.material.dialog.MaterialAlertDialogBuilder =
        TintedMaterialDialogBuilder(this)

    /**
     * MaterialAlertDialogBuilder 的子类，只为在 show 前给弹窗上强调色。
     *
     * 为什么用子类而不是要求每个调用点自己加 `setOnShowListener`：
     * 全仓 `md3Dialog()` 有 11 处调用、散落在 6 个文件，逐处补既容易漏，
     * 又会跟并行改动的会话冲突。这里改一处，所有调用点自动生效，
     * 且返回类型仍是 MaterialAlertDialogBuilder，调用点无需任何改动。
     */
    class TintedMaterialDialogBuilder(private val host: Context) :
        com.google.android.material.dialog.MaterialAlertDialogBuilder(host) {

        override fun show(): androidx.appcompat.app.AlertDialog {
            val dialog = create()
            dialog.setOnShowListener {
                AccentTint.apply(dialog.window?.decorView)
            }
            dialog.show()
            return dialog
        }
    }

    /**
     * 弹窗统一入口（v1.8.1 起**不再**在 show 之后换背景）。
     *
     * 以前这里在 setOnShowListener 里做
     * `window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)`。
     * 那是窗口 attach 之后才改背景：Material 原本的窗口背景是
     * `MaterialDialogs.insetDrawable(MaterialShapeDrawable, backgroundInsets)`（带 inset），
     * 换成不含 inset 的纯 shape 后背景可绘制区从「窗口内缩一圈」变成「整个窗口」，
     * 面板在首帧之后突然变大。真机逐帧实测面板高度 49px → 174px（归一化 190 高），
     * 就是「先在上面闪一下，然后再归位到中间」。
     *
     * 现在容器色/圆角/inset 全部由主题 `ThemeOverlay.Dsh.MaterialAlertDialog`
     * → `DshMaterialAlertDialog` 在 builder 构造期读定，首帧即终态。
     * 这里只保留强调色铺色（那是运行时按用户选色，主题层做不到）。
     */
    protected fun com.google.android.material.dialog.MaterialAlertDialogBuilder.showMd3(): androidx.appcompat.app.AlertDialog =
        create().apply {
            setOnShowListener { AccentTint.apply(window?.decorView) }
        }.also { it.show() }

    /**
     * 把当前强调色铺到对话框上（v1.7.9）。
     *
     * 对话框是**独立窗口**，不在 Activity 的视图树里，[AccentTint.apply] 遍历
     * Activity 内容时够不到它 —— 所以每个弹窗在 show 之后必须自己调一次。
     * 统一走本方法或 [showMd3]，不要各自实现。
     */
    protected fun <T : android.app.Dialog> T.tintAccent(): T = apply {
        setOnShowListener { AccentTint.apply(window?.decorView) }
    }
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 设置体系页面用 MD3 主题（Theme.Dsh.MD3），其余原样。
        // 必须在 super.onCreate 之前 setTheme：这里显式设主题会覆盖
        // AndroidManifest 里 activity 的 theme 属性，所以由 usesSettingsBackground
        // 统一决定，而不是靠每个 Activity 在 Manifest 里各写一遍。
        //
        // ★合并说明（2026-11-09）：feat/linux-workspace 上 3ab3b87d 曾把这里改成
        //   全站统一 Theme.Dsh（当时用户要求「设置页退回旧样式」）。但 main 上
        //   0b396402（11-08，PR #97）已把 MD3 界面整包合回并新增独立主题页，时间更晚，
        //   因此以 main 的 MD3 分派为准。
        //   注意 usesSettingsBackground 同时是「主题分派」与「设置页背景图」两个开关，
        //   后者（applySettingsBackground）与本改动无关，不要因为主题选择变化就删掉。
        // 必须在 setTheme 之前同步：AppCompat 的夜间模式决定了 Material 控件
        // 在构造时会不会把我们这份 Resources 的 uiMode 改掉（见 syncAppCompatNight）。
        ThemeManager.syncAppCompatNight(this)
        // ★ v1.8.0：主题分派改由「界面风格」决定，不再由 usesSettingsBackground 决定。
        //
        // 原来只有设置体系那 18 个页面套 MD3，其余页面永远 Theme.Dsh —— 而这两套
        // 主题的语义色以前都映射到同一张旧色板，画出来一样，所以没人发现。
        // v1.8.0 让 MD3 走真正的暖灰色板后，如果还按旧分派，就会出现
        // 「设置页暖、主界面冷」的花脸。因此改成全站跟随风格，
        // 只有显式声明 followsStyle = false 的页面（自绘深色的编辑器/工作台）
        // 保持经典主题。
        //
        // ⚠️ usesSettingsBackground 仍然保留它原本的第二职责（设置页背景图，
        //    见 applySettingsBackground），不要为了主题把那个开关也删了。
        setTheme(if (followsStyle && StylePref.isMaterial(this)) R.style.Theme_Dsh_MD3 else R.style.Theme_Dsh)
        // 主题偏好会用 createConfigurationContext 重建 resources，动态色必须在之后应用。
        //
        // ⚠️ 2026-11-10：PRESET / CUSTOM 不再走 DynamicColors。
        // 原来三种模式都注入 Monet，PRESET/CUSTOM 还拿强调色当 seed 生成 content-based 色板，
        // 后果是整个 MD3 语义色被运行时覆盖：
        //   · colorSurface 被换成 Monet 中性色，与页面底几乎同色 → 分组卡片看起来"消失"，
        //     行直接浮在背景上，分组边界没了；
        //   · colorPrimary 被换成淡蓝近白 → 分组标题失去强调色；
        //   · colorOnSurface 被换成近白 → 图标统一变白，原有的分类着色全丢。
        // 也就是说 themes.xml 里「MD3 语义色回映射旧色板」对任何 ?attr/color* 控件都不生效。
        // 只有用户真的选「跟随壁纸」时才该让 Monet 接管，其余情况必须让我们的色板说了算。
        // ★ v1.8.1：不再在这里注入 DynamicColors。
        //
        // 原来 DYNAMIC 模式会调 DynamicColors.applyToActivityIfAvailable(this)，
        // 它在 API 31+ 走 ColorResourcesOverride → ResourcesLoader 替换颜色资源，
        // 这会**改变本 Activity 的 Resources 配置**，于是系统紧接着再发一次
        // configuration change → 第二次 relaunch。真机/模拟器实测（Android 14）：
        // 点一下「动态取色」屏幕重绘两次（ActUi 32 vs 应用 16 的配置差被再次应用），
        // 用户看到的就是「点一下就闪屏」。
        //
        // 现在动态取色的取色改由 [SystemAccent] 直接读系统的 system_accent 色，
        // 不碰 Resources、不产生配置变化，只保留我们自己的一次 recreate()。
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

    /**
     * 本 Activity 是否跟随「界面风格」（设置 → 主题 → 界面风格）。
     *
     * 默认 true：v1.8.0 起全站都跟着风格走，布局里的 `?attr/dshXxx` 与
     * Material 语义色才会同步解析，不会出现「设置页暖、主界面冷」的花脸。
     *
     * 覆盖为 false 的只有**自绘深色**的页面 —— 它们的底色是写死的 #FF101418，
     * 套 Material 3 暖灰反而更割裂：
     *   · TerminalActivity（终端，深色底色）
     *   · LogViewerActivity（日志，深色底色，且不继承 ThemedActivity，不影响）
     *   · LinuxActivity（工作台，整屏自绘）
     */
    protected open val followsStyle: Boolean get() = true

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applySettingsBackground()
        applyAccentTint()
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applySettingsBackground()
        applyAccentTint()
    }

    override fun onResume() {
        super.onResume()
        // 从外观页选完图返回时立即生效，不需要用户手动刷新
        if (usesSettingsBackground) applySettingsBackground()
        // 强调色可能在别的页面被改过（主题页重建的只是它自己），
        // 回本页时重新铺一次；未改色时是幂等的空操作。
        applyAccentTint()
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

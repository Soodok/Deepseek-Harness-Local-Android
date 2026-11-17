package app.dsh.mobile

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.RuntimeInstaller
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.ui.LayoutPreviewView

/**
 * 首次启动引导（v2 重做为九页、双形态向导）。
 *
 * ## 九页 = 1 封面 + 8 步
 *  0. 欢迎（封面，不计入步数；说明这是什么、要做什么准备）
 *  1. 语言（6 种）
 *  2. 显示方向（带真机画面预览，切竖/横真实变形）
 *  3. 外观主题（带明暗预览）
 *  4. 配色方案（带实时预览条：选色后按钮/进度条/勾选当场变色）
 *  5. 屏幕与布局（新增：页面缩放 / 设置页布局 / 沉浸式）
 *  6. 运行模式与能力（模式单选 + 能力开关多选）
 *  7. 完成（保持 v1.5.x 原样，用户明确要求不改）
 *
 * ## 双形态
 *  · >=600dp（平板 / 横屏）：左侧 206dp 步骤轨常驻，步骤可点跳；
 *  · < 600dp（手机竖屏）：步骤轨收成顶部「全部步骤」胶囊，点开从底部滑入抽屉。
 *  两形态共用同一份 steps 定义与同一批页面 View，只是容器不同。
 *
 * ## 为什么卡片仍然代码生成
 *  语言 5 种、配色 5 种、缩放 4 档、布局 3 种、沉浸 2 种、能力十余项 ——
 *  写进 XML 是四十多段几乎相同的重复结构，且切语言后文案要跟着变。
 *
 * ## 保留下来的既有行为（不要退化）
 *  · Root 双警告：选 Root 后两次确认才落库；任一取消都回落普通并完成引导；
 *  · 能力检测在后台线程：probeRoot 最坏试 3 种 su 语法 × 2s 超时，主线程会 ANR；
 *  · 返回键 = 以当前选择完成引导，绝不卡死；
 *  · 语言/主题切换需要 recreate，页码与已选项用 savedInstanceState 保住；
 *  · 不设 requestedOrientation（会落在正在销毁的窗口上被丢弃）。
 */
class OnboardingActivity : ThemedActivity() {

    /** 一步的定义：标题。 */
    private data class Step(val titleRes: Int)

    /**
     * 九页 = 1 封面 + 8 步，下标与 pages 一一对应。
     *
     * 封面（index 0）不计步：侧轨显示「·」，进度条与「第 n / 8 步」都不数它。
     * 编号只有这一处来源 —— v1.7 之前 stepRow 跳转、refreshStepper、goTo
     * 各自算了一次 index+1，加页后必然整体偏一页。
     */
    private val steps = listOf(
        Step(R.string.ob_wizard_welcome_cover_label),
        Step(R.string.ob_wizard_language_title),
        Step(R.string.ob_wizard_orientation_title),
        Step(R.string.ob_wizard_theme_title),
        Step(R.string.ob_wizard_accent_title),
        Step(R.string.ob_wizard_screen_title),
        Step(R.string.ob_wizard_mode_title),
        Step(R.string.ob_wizard_caps_title),
        Step(R.string.ob_wizard_done_title),
    )

    /** 方向 / 外观 / 强调色 / 屏幕四页是可选页，主按钮写「保留当前设置并继续」。 */
    private val OPTIONAL_PAGES = setOf(2, 3, 4, 5)

    private var page = 0

    private var selectedLang = "zh"
    private var selectedOrient: OrientationMode = OrientationMode.PORTRAIT
    private var selectedTheme: ThemeMode = ThemeMode.SYSTEM
    private var selectedAccent: Accent = Accent.SKY
    private var selectedPriv: PrivMode = PrivMode.NORMAL
    private var selectedScale = DEFAULT_PAGE_SCALE
    private var selectedLayoutMode: SettingsLayoutMode = SettingsLayoutMode.LIST
    private var selectedImmersive = false

    /** 运行环境四态：欢迎页胶囊与完成页状态卡共用的唯一权威状态。 */
    private enum class RtState { PREPARING, READY, FAILED, LITE }

    @Volatile private var rtState: RtState = RtState.PREPARING
    @Volatile private var rtPct: Int = 0

    /** 完成页的运行环境预取状态（v1.7.10）。 */
    private var doneRuntimeStatus: TextView? = null
    private var doneRuntimeBar: ProgressBar? = null
    private var doneRuntimeSub: TextView? = null
    private var welcomePill: LinearLayout? = null
    private var welcomePillText: TextView? = null
    private var welcomePillSpinner: ProgressBar? = null
    @Volatile private var lastPrefetchPct = -1

    private lateinit var pages: List<View>
    private lateinit var progress: ProgressBar
    private lateinit var stepHint: TextView
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button
    private lateinit var railSteps: LinearLayout
    private lateinit var drawerSteps: LinearLayout
    private lateinit var stepsDrawer: FrameLayout
    private lateinit var sideRail: LinearLayout
    private lateinit var btnSteps: TextView

    /** 步骤轨条目：两种形态各一套，切换时同步高亮。 */
    private val railItems = mutableListOf<LinearLayout>()
    private val drawerItems = mutableListOf<LinearLayout>()

    /** 语言码 → 母语写法的字符串资源（与设置页语言选择保持一致）。 */
    private val langCodes = listOf("zh", "en", "de", "pt", "th", "ru")
    private val langLabelRes = mapOf(
        "zh" to R.string.lang_zh,
        "en" to R.string.lang_en,
        "de" to R.string.lang_de,
        "pt" to R.string.lang_pt,
        "th" to R.string.lang_th,
        "ru" to R.string.lang_ru,
    )

    private val langCards = mutableListOf<LinearLayout>()
    private val orientCards = mutableListOf<LinearLayout>()
    private val themeCards = mutableListOf<LinearLayout>()
    private val accentCards = mutableListOf<LinearLayout>()
    private val scaleCards = mutableListOf<LinearLayout>()
    private val layoutCards = mutableListOf<LinearLayout>()
    private val immersiveCards = mutableListOf<LinearLayout>()
    private val privCards = mutableListOf<LinearLayout>()
    private val capSwitches =
        mutableMapOf<String, com.google.android.material.materialswitch.MaterialSwitch>()

    /** 完成页光晕的呼吸循环；只在停留于完成页且 Activity 可见时运行。 */
    private var glowBreath: ValueAnimator? = null

    /** Shizuku 授权结果监听（requestPermission 异步回调） */
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ) {
                Privilege.markShizukuDenied(
                    this,
                    grantResult != android.content.pm.PackageManager.PERMISSION_GRANTED,
                )
                refreshShizukuStatus()
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        // 恢复状态：语言/主题切换会 recreate，页码与已选项都要保住
        page = savedInstanceState?.getInt(KEY_PAGE) ?: 0
        selectedLang = savedInstanceState?.getString(KEY_LANG) ?: LocaleHelper.get(this)
        selectedOrient = savedInstanceState?.getString(KEY_ORIENTATION)
            ?.let { runCatching { OrientationMode.valueOf(it) }.getOrNull() }
            ?: OrientationPrefs.defaultFor(this)
        selectedTheme = savedInstanceState?.getString(KEY_THEME)
            ?.let { ThemeMode.fromKey(it) } ?: ThemePref.get(this)
        selectedAccent = savedInstanceState?.getString(KEY_ACCENT)?.let { Accent.fromId(it) }
            ?: Accent.currentPreset(this)
        selectedPriv = savedInstanceState?.getString(KEY_PRIV)
            ?.let { runCatching { PrivMode.valueOf(it) }.getOrNull() } ?: PrivMode.NORMAL
        selectedScale = savedInstanceState?.getInt(KEY_SCALE) ?: currentPageScale()
        selectedLayoutMode = savedInstanceState?.getString(KEY_LAYOUT)
            ?.let { SettingsLayoutMode.fromId(it) } ?: SettingsLayoutMode.current(this)
        selectedImmersive = savedInstanceState?.getBoolean(KEY_IMMERSIVE)
            ?: MainActivity.immersiveEnabled(this)

        progress = findViewById(R.id.stepProgress)
        stepHint = findViewById(R.id.txtStepHint)
        btnBack = findViewById(R.id.btnBack)
        btnNext = findViewById(R.id.btnNext)
        railSteps = findViewById(R.id.railSteps)
        drawerSteps = findViewById(R.id.drawerSteps)
        stepsDrawer = findViewById(R.id.stepsDrawer)
        sideRail = findViewById(R.id.sideRail)
        btnSteps = findViewById(R.id.btnSteps)

        pages = listOf(
            findViewById(R.id.pageWelcome),
            findViewById(R.id.pageLanguage),
            findViewById(R.id.pageOrientation),
            findViewById(R.id.pageTheme),
            findViewById(R.id.pageAccent),
            findViewById(R.id.pageScreen),
            findViewById(R.id.pageMode),
            findViewById(R.id.pageCaps),
            findViewById(R.id.pageDone),
        )
        // 进度只算 8 步：封面不计入，否则「第 1 / 9 步」与侧轨编号对不上
        progress.max = steps.size - 1

        buildWelcomeRows()

        buildStepper()
        buildLanguageCards()
        buildOrientationCards()
        buildThemeCards()
        buildAccentCards()
        buildScreenCards()
        buildPrivCards()
        buildCapabilitySwitches()

        btnBack.setOnClickListener {
            // 完成页的次按钮是「先浏览应用」：语义是进入应用，不是回退一页
            if (page == pages.size - 1) finishOnboarding() else goTo(page - 1)
        }
        btnNext.setOnClickListener {
            if (page == pages.size - 1) finishOnboarding() else goTo(page + 1)
        }

        // 手机形态：顶部胶囊打开步骤抽屉；点遮罩关闭
        btnSteps.setOnClickListener { openStepsDrawer(true) }
        // 30dp 高的胶囊达不到 Android 的 48dp 最小触控尺寸（实测 65.5 × 30.2dp）。
        // 视觉不动，用 TouchDelegate 把热区向外扩——和 MainActivity.applyHandleTouchTarget 同一套做法。
        val topBlock = findViewById<ViewGroup>(R.id.topBlock)
        btnSteps.post { expandTouchTarget(btnSteps, topBlock, minDp = 48) }
        findViewById<View>(R.id.drawerScrim).setOnClickListener { openStepsDrawer(false) }

        applyFormFactor()
        // 不要在 onCreate 里同步探测 su：Privilege.rootAvailableMinimal() 最坏要试
        // 3 种 su 语法 × 2s 超时，实测直接把首屏卡到 ANR（FocusEvent 等 5s 超时）。
        // 交给 probeCapabilities() 的后台线程，结果回主线程再应用。
        probeCapabilities()
        refreshShizukuStatus()

        // v1.7.10：用户挑语言/主题的这十几秒足够解压 runtime.zip。
        // 解压提前到这里，点「完成」后引擎只需启动，不再让用户盯着遮罩干等。
        doneRuntimeStatus = findViewById(R.id.txtDoneRuntime)
        doneRuntimeBar = findViewById(R.id.pbDoneRuntime)
        doneRuntimeSub = findViewById(R.id.txtDoneRuntimeSub)
        welcomePill = findViewById(R.id.welcomePill)
        welcomePillText = findViewById(R.id.txtWelcomePill)
        welcomePillSpinner = findViewById(R.id.pbWelcomePill)
        startRuntimePrefetch()

        goTo(page)
    }

    // ================= 形态与步骤轨 =================

    /** 按当前宽度分派形态：>=600dp 用侧栏，否则用顶部胶囊 + 抽屉。 */
    private fun applyFormFactor() {
        val wide = resources.configuration.screenWidthDp >= WIDE_DP
        sideRail.visibility = if (wide) View.VISIBLE else View.GONE
        btnSteps.visibility = if (wide) View.GONE else View.VISIBLE
        // 底部按钮条已移进 MaxWidthLinearLayout 内部，平板上会自然与内容列同宽，
        // 不再需要单独的占位 View。
        if (wide) openStepsDrawer(false)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Activity 声明了 configChanges，转屏不重建；形态要手动重判一次。
        applyFormFactor()
    }

    private fun buildStepper() {
        railItems.clear()
        drawerItems.clear()
        railSteps.removeAllViews()
        drawerSteps.removeAllViews()
        steps.indices.forEach { i ->
            railItems.add(stepRow(railSteps, i))
            drawerItems.add(stepRow(drawerSteps, i))
        }
    }

    /**
     * 一个步骤条目：序号方块 + 标题。
     *
     * 已完成显示勾选、当前步高亮、未到达淡显 —— 三种状态靠同一套重涂逻辑
     * （见 [refreshStepper]），不各写一遍。
     */
    /** 封面页不计步：侧轨与抽屉里显示「·」而不是编号。 */
    private fun countsAsStep(index: Int) = index > 0

    private fun stepRow(parent: LinearLayout, index: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(8), dp(9), dp(8))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(2) }
        }
        row.addView(TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            tag = "badge"
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
        })
        row.addView(TextView(this).apply {
            text = if (countsAsStep(index)) getString(steps[index].titleRes)
            else getString(R.string.ob_wizard_welcome_cover_label)
            textSize = 12.5f
            tag = "label"
        })
        row.setOnClickListener {
            // 任何步骤都可点跳（用户明确要求），已完成的还能回改
            openStepsDrawer(false)
            goTo(index)
        }
        parent.addView(row)
        return row
    }

    private fun refreshStepper() {
        fun paint(row: LinearLayout, index: Int) {
            val badge = row.findViewWithTag<TextView>("badge")
            val label = row.findViewWithTag<TextView>("label")
            val accent = Accent.current(this).argb
            when {
                index == page -> {
                    row.background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Accent.current(this@OnboardingActivity).softArgb)
                    }
                    badge?.text = if (countsAsStep(index)) index.toString() else "\u00b7"
                    badge?.setTextColor(dshColor(R.attr.dshOnAccent, R.color.on_accent))
                    badge?.background = circle(accent)
                    label?.setTextColor(accent)
                    label?.setTypeface(null, android.graphics.Typeface.BOLD)
                }
                index < page -> {
                    row.background = null
                    // 封面是「·」不是勾：它没有步数，做过也不算一步
                    badge?.text = if (countsAsStep(index)) "\u2713" else "\u00b7"
                    badge?.setTextColor(accent)
                    badge?.background = circle(Accent.current(this@OnboardingActivity).softArgb)
                    label?.setTextColor(dshColor(R.attr.dshTextPrimary, R.color.text_primary))
                    label?.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
                else -> {
                    row.background = null
                    badge?.text = if (countsAsStep(index)) index.toString() else "\u00b7"
                    badge?.setTextColor(dshColor(R.attr.dshTextMuted, R.color.text_muted))
                    badge?.background = outlinedCircle(dshColor(R.attr.dshStroke, R.color.stroke))
                    label?.setTextColor(dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
                    label?.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
            }
        }
        railItems.forEachIndexed { i, r -> paint(r, i) }
        drawerItems.forEachIndexed { i, r -> paint(r, i) }
    }

    private fun openStepsDrawer(open: Boolean) {
        if (!open) {
            stepsDrawer.visibility = View.GONE
            return
        }
        stepsDrawer.visibility = View.VISIBLE
        val panel = findViewById<View>(R.id.drawerPanel)
        panel.translationY = dp(320).toFloat()
        panel.animate().translationY(0f).setDuration(220L).start()
    }

    // ================= 分页 =================

    private fun goTo(index: Int) {
        if (index !in pages.indices) return
        val previous = page
        page = index
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        animateStep(previous, index)
        // 步数只算 8 步（封面不计）：进度条、眉标、侧轨编号统一从这里取，
        // 之前三处各算一次 index+1，加页后整体偏一页。
        val stepNo = index
        val stepCount = steps.size - 1
        progress.progress = stepNo
        progress.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        // 封面不再显示页眉提示：那一行「约 30 秒、环境在后台解压」与胶囊
        // 说的是同一件事，用户明确要求去掉（截图里顶部那行灰字）。
        // 其余页照旧显示「第 n / 8 步」，显隐由 syncCoverStatus() 统一管。
        if (index != 0) stepHint.text = getString(R.string.ob_wizard_step_fmt, stepNo, stepCount)

        // 首页不显示「上一步」：用 GONE 而不是 INVISIBLE，否则它仍占着
        // layout_weight 的宽度，右侧按钮会被挤到屏幕中间（v1.5.x 实测偏右 400px）。
        btnBack.visibility = if (index == 0) View.GONE else View.VISIBLE
        btnBack.setText(if (index == pages.size - 1) R.string.ob_done_browse else R.string.ob_wizard_back)
        syncCoverStatus()
        btnNext.setText(
            when {
                index == pages.size - 1 -> R.string.ob_wizard_start   // 覆盖前的占位，随后由 refreshDoneCta 细化
                index == 0 -> R.string.ob_wizard_welcome_start
                index in OPTIONAL_PAGES -> R.string.ob_wizard_next_keep
                else -> R.string.ob_wizard_next
            },
        )
        if (index == pages.size - 1) refreshDoneCta()
        if (index == pages.size - 1) playDoneAnimation() else stopGlowBreath()
        refreshStepper()
        refreshSelection()
    }

    /**
     * 页间转场：旧页淡出 + 轻微位移，新页反向进入。
     *
     * 只有位移没有淡入淡出会像「硬切」；只有淡入淡出又会觉得没动。
     * 两者叠加 140/220ms 是实测最像系统向导的一组值。
     */
    private fun animateStep(previous: Int, next: Int) {
        if (previous == next) return
        val dir = if (next > previous) 1 else -1
        val shift = dp(18).toFloat() * dir

        val outgoing = pages.getOrNull(previous)
        if (outgoing != null) {
            outgoing.animate().cancel()
            outgoing.animate().alpha(0f).translationX(-shift)
                .setDuration(140L).start()
        }

        val incoming = pages.getOrNull(next)
        if (incoming != null) {
            incoming.animate().cancel()
            incoming.alpha = 0f
            incoming.translationX = shift
            incoming.animate().alpha(1f).translationX(0f)
                .setDuration(220L).start()
        }
    }

    /** 完成页特效：光晕放大淡入 + logo 弹入，之后光晕缓慢呼吸。 */
    private fun playDoneAnimation() {
        val glow = findViewById<View>(R.id.doneGlow)
        val logo = findViewById<ImageView>(R.id.doneLogo)
        if (glow == null || logo == null) return

        // 光晕染成当前强调色，换配色也跟着变
        glow.background?.mutate()?.setTint(selectedAccent.argb)

        // 入场与呼吸改的是同一组属性，先停呼吸，否则两者每帧互相覆盖
        stopGlowBreath()
        glow.animate().cancel()
        glow.alpha = 0f
        glow.scaleX = 0.6f
        glow.scaleY = 0.6f
        // withEndAction 在动画被 cancel 时不会触发，所以中途翻走不会漏启一个呼吸
        glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420L)
            .withEndAction { if (page == pages.size - 1) startGlowBreath(glow) }
            .start()

        logo.animate().cancel()
        logo.alpha = 0f
        logo.scaleX = 0.7f
        logo.scaleY = 0.7f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(120L).setDuration(420L).start()
    }

    /**
     * 呼吸循环：让光晕"活着"，暗示可以开始了。
     *
     * 之前是 animate() 放大一次、withEndAction 里缩回一次就结束 —— 名字叫呼吸，
     * 实际只动 2.2 秒。这里用无限往复的 ValueAnimator，并在离开完成页 / Activity
     * 不可见 / 销毁时停掉，避免一个看不见的动画每帧空转。
     */
    private fun startGlowBreath(glow: View) {
        stopGlowBreath()
        // 系统开了「移除动画」时时长会被缩放成 0，无限循环会退化成每帧跳变；
        // 这种情况下保持入场后的静止状态。
        if (!ValueAnimator.areAnimatorsEnabled()) return
        glowBreath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val scale = 1f + (1.12f - 1f) * t
                glow.scaleX = scale
                glow.scaleY = scale
                glow.alpha = 1f - 0.25f * t
            }
            start()
        }
    }

    private fun stopGlowBreath() {
        glowBreath?.cancel()
        glowBreath = null
    }

    // ================= 卡片 =================

    /**
     * 通用可选卡片：图标或色块 + 标题 + 副标题 + 右侧勾选。
     *
     * 选中态用强调色描边 + 柔底（见 [refreshSelection]）而不是只降透明度：
     * 实测 0.62 alpha 在深色底上几乎看不出差别，选没选中要靠猜。
     */
    private fun optionCard(
        container: LinearLayout,
        title: String,
        subtitle: String?,
        iconRes: Int? = null,
        swatchColor: Int? = null,
        /** 用字形（文 / A / D / P / ก / Я）当头像：语言卡六张同款地球分不出是哪门语言。 */
        glyph: String? = null,
        onClick: () -> Unit,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_ob_card_ripple)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(9) }
        }

        if (swatchColor != null) {
            card.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(swatchColor)
                }
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginEnd = dp(13)
                }
            })
        } else if (glyph != null) {
            val box = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Accent.current(this@OnboardingActivity).softArgb)
                }
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(13) }
            }
            box.addView(TextView(this).apply {
                text = glyph
                textSize = 15f
                setTextColor(Accent.current(this@OnboardingActivity).argb)
                gravity = Gravity.CENTER
                tag = "glyph"
                layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
            })
            card.addView(box)
        } else if (iconRes != null) {
            val box = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(dshColor(R.attr.dshSurface, R.color.surface))
                }
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    marginEnd = dp(13)
                }
            }
            box.addView(ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
                tag = "icon"
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            })
            card.addView(box)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15.5f
            setTextColor(dshColor(R.attr.dshTextPrimary, R.color.text_primary))
            tag = "title"
        })
        if (!subtitle.isNullOrBlank()) {
            textCol.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        }
        card.addView(textCol)

        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            visibility = View.INVISIBLE
            tag = "tick"
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })

        card.setOnClickListener { onClick() }
        container.addView(card)
        return card
    }

    /** 统一重算所有卡片的选中态（改任一项都调它）。 */
    private fun refreshSelection() {
        val accent = Accent.current(this).argb
        val soft = Accent.current(this).softArgb
        val normal = dshColor(R.attr.dshTextPrimary, R.color.text_primary)

        fun mark(card: LinearLayout, active: Boolean) {
            card.alpha = 1f
            card.background = if (active) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(soft)
                    setStroke(dp(2).coerceAtLeast(1), accent)
                }
            } else {
                getDrawable(R.drawable.bg_ob_card_ripple)
            }
            // 文本列：第一个带 tag=title 的 LinearLayout（有色块/图标时不是第 0 项）
            val textCol = (0 until card.childCount)
                .map { card.getChildAt(it) }
                .firstOrNull { it is LinearLayout && it.findViewWithTag<TextView>("title") != null }
            (textCol?.findViewWithTag<TextView>("title"))?.setTextColor(if (active) accent else normal)
            card.findViewWithTag<View>("tick")?.visibility =
                if (active) View.VISIBLE else View.INVISIBLE
            card.findViewWithTag<ImageView>("icon")?.setColorFilter(
                if (active) accent else dshColor(R.attr.dshTextSecondary, R.color.text_secondary),
            )
            card.findViewWithTag<TextView>("glyph")?.setTextColor(if (active) accent else dshColor(R.attr.dshTextPrimary, R.color.text_primary))
        }

        langCards.forEachIndexed { i, c -> mark(c, langCodes[i] == selectedLang) }
        orientCards.forEachIndexed { i, c -> mark(c, i == orientIndexOf(selectedOrient)) }
        themeCards.forEachIndexed { i, c -> mark(c, i == themeIndexOf(selectedTheme)) }
        accentCards.forEachIndexed { i, c -> mark(c, Accent.entries[i] == selectedAccent) }
        scaleCards.forEachIndexed { i, c -> mark(c, SCALE_CHOICES[i] == selectedScale) }
        layoutCards.forEachIndexed { i, c -> mark(c, i == layoutIndexOf(selectedLayoutMode)) }
        immersiveCards.forEachIndexed { i, c -> mark(c, (i == 0) == selectedImmersive) }
        privCards.forEachIndexed { i, c -> mark(c, i == privIndexOf(selectedPriv)) }
    }

    private fun orientIndexOf(m: OrientationMode) = when (m) {
        OrientationMode.PORTRAIT -> 0
        OrientationMode.LANDSCAPE -> 1
        OrientationMode.AUTO -> 2
    }

    private fun themeIndexOf(m: ThemeMode) = when (m) {
        ThemeMode.DARK -> 0
        ThemeMode.LIGHT -> 1
        ThemeMode.SYSTEM -> 2
    }

    private fun layoutIndexOf(m: SettingsLayoutMode) = when (m) {
        SettingsLayoutMode.LIST -> 0
        SettingsLayoutMode.GRID -> 1
        SettingsLayoutMode.COLLAPSE -> 2
    }

    private fun privIndexOf(m: PrivMode) = when (m) {
        PrivMode.NORMAL -> 0
        PrivMode.SHIZUKU -> 1
        PrivMode.ROOT -> 2
    }

    private fun buildLanguageCards() {
        val box = findViewById<LinearLayout>(R.id.langContainer)
        langCards.clear()
        langCodes.forEach { code ->
            langCards.add(
                optionCard(
                    box,
                    getString(langLabelRes[code]!!),
                    langNativeName(code),
                    iconRes = null,
                    glyph = langGlyph(code),
                ) {
                    if (selectedLang != code) {
                        // 切语言必须 recreate 才能让本页文案跟着变；页码与选项靠
                        // onSaveInstanceState 保住，用户不会被弹回第一步
                        LocaleHelper.set(this, code)
                        selectedLang = code
                        recreate()
                    }
                },
            )
        }
        refreshSelection()
    }

    /** 语言卡的字形头像：每门语言用自己的一两个字，替代六张同款地球。 */
    private fun langGlyph(code: String): String = when (code) {
        "zh" -> "\u6587"
        "en" -> "A"
        "de" -> "D"
        "pt" -> "P"
        "th" -> "\u0e01"
        "ru" -> "\u042f"
        else -> "?"
    }

    private fun langNativeName(code: String): String = when (code) {
        "zh" -> "\u7b80\u4f53\u4e2d\u6587"
        "en" -> "English"
        "de" -> "Deutsch"
        "pt" -> "Portugu\u00eas"
        "th" -> "\u0e20\u0e32\u0e29\u0e32\u0e44\u0e17\u0e22"
        "ru" -> "\u0420\u0443\u0441\u0441\u043a\u0438\u0439"
        else -> code
    }

    private fun buildOrientationCards() {
        val box = findViewById<LinearLayout>(R.id.orientContainer)
        orientCards.clear()

        // 说明块：三种方向各自的后果，让「为什么选这个」不用猜
        val hintBox = findViewById<LinearLayout>(R.id.orientHint)
        hintBox.removeAllViews()
        hintBox.addView(sectionLabel(R.string.ob_wizard_orient_how))
        listOf(
            R.string.setting_orient_portrait to R.string.ob_wizard_orient_portrait_hint,
            R.string.setting_orient_landscape to R.string.ob_wizard_orient_landscape_hint,
            R.string.setting_orient_auto to R.string.ob_wizard_orient_auto_hint,
        ).forEach { (nameRes, hintRes) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(5))
            }
            row.addView(TextView(this).apply {
                text = getString(nameRes)
                textSize = 12f
                setTextColor(Accent.current(this@OnboardingActivity).argb)
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = getString(hintRes)
                textSize = 12f
                setTextColor(dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            hintBox.addView(row)
        }

        listOf(
            OrientationMode.PORTRAIT to R.string.setting_orient_portrait,
            OrientationMode.LANDSCAPE to R.string.setting_orient_landscape,
            OrientationMode.AUTO to R.string.setting_orient_auto,
        ).forEach { (mode, labelRes) ->
            val icon = when (mode) {
                OrientationMode.PORTRAIT -> R.drawable.ic_display
                OrientationMode.LANDSCAPE -> R.drawable.ic_layout
                OrientationMode.AUTO -> R.drawable.ic_rotate
            }
            orientCards.add(optionCard(box, getString(labelRes), null, icon) {
                selectedOrient = mode
                updateOrientationPreview()
                refreshSelection()
            })
        }
        updateOrientationPreview()
        refreshSelection()
    }

    /** 方向预览与文案随选择变化 —— 这是「选之前就看得到后果」的核心。 */
    private fun updateOrientationPreview() {
        val preview = findViewById<LayoutPreviewView>(R.id.orientPreview)
        val label = findViewById<TextView>(R.id.orientPreviewLabel)
        val box = findViewById<FrameLayout>(R.id.orientPreviewBox)
        val land = selectedOrient == OrientationMode.LANDSCAPE
        preview.landscape = land
        // 竖屏高一些、横屏矮一些，比例差就是用户能感到的差别
        val lp = box.layoutParams
        lp.height = dp(if (land) 132 else 176)
        box.layoutParams = lp
        label.text = getString(
            when (selectedOrient) {
                OrientationMode.PORTRAIT -> R.string.ob_wizard_orient_preview_portrait
                OrientationMode.LANDSCAPE -> R.string.ob_wizard_orient_preview_landscape
                OrientationMode.AUTO -> R.string.ob_wizard_orient_preview_auto
            },
        )
    }

    private fun buildThemeCards() {
        buildThemePreview()
        val box = findViewById<LinearLayout>(R.id.themeContainer)
        themeCards.clear()
        listOf(
            ThemeMode.DARK to R.string.theme_dark,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.SYSTEM to R.string.theme_system,
        ).forEach { (mode, labelRes) ->
            themeCards.add(
                optionCard(box, getString(labelRes), themeHint(mode), R.drawable.ic_theme) {
                    selectedTheme = mode
                    // 立即落库并重建，让用户在引导页当场看到主题效果
                    ThemePref.set(this, mode)
                    recreate()
                },
            )
        }
        refreshSelection()
    }

    /**
     * 明暗对照：并排放「深色」与「浅色」两块同构画面。
     *
     * 为什么不只画当前选中的那一套：用户在这一页要做的判断是
     * 「我更喜欢哪一种」，只有把两种同时摆出来才比得出来。选中态由
     * [refreshSelection] 给卡片加描边表达，预览块只负责展示后果。
     */
    private fun buildThemePreview() {
        val dark = findViewById<LayoutPreviewView>(R.id.themePreview)
        val light = findViewById<LayoutPreviewView>(R.id.themePreview2)
        dark.light = false
        light.light = true
        findViewById<TextView>(R.id.themePreviewLabel).text =
            getString(R.string.ob_wizard_theme_dark_hint)
        findViewById<TextView>(R.id.themePreviewLabel2).text =
            getString(R.string.ob_wizard_theme_light_hint)
    }

    private fun themeHint(mode: ThemeMode): String = when (mode) {
        ThemeMode.DARK -> getString(R.string.ob_wizard_theme_dark_hint)
        ThemeMode.LIGHT -> getString(R.string.ob_wizard_theme_light_hint)
        ThemeMode.SYSTEM -> getString(R.string.ob_wizard_theme_system_hint)
    }

    private fun buildAccentCards() {
        val box = findViewById<LinearLayout>(R.id.accentContainer)
        accentCards.clear()
        Accent.entries.forEach { acc ->
            accentCards.add(
                optionCard(box, getString(acc.labelRes), null, swatchColor = acc.argb) {
                    selectedAccent = acc
                    Accent.set(this, acc)
                    updateAccentPreview()
                    refreshSelection()
                },
            )
        }
        updateAccentPreview()
        refreshSelection()
    }

    /**
     * 配色实时预览：一条按钮 + 一条进度条，全部按当前强调色重涂。
     *
     * 这块同时是那个 bug 的回归证明 —— v1.5.x 选了配色后只有卡片文字变色，
     * 按钮与进度条仍是静态 @color/accent，用户以为没生效。
     */
    private fun updateAccentPreview() {
        val box = findViewById<LinearLayout>(R.id.accentPreview)
        val accent = Accent.current(this).argb
        val onAccent = dshColor(R.attr.dshOnAccent, R.color.on_accent)
        box.removeAllViews()
        box.addView(TextView(this).apply {
            text = getString(R.string.ob_wizard_accent_preview)
            textSize = 11f
            setTextColor(dshColor(R.attr.dshTextMuted, R.color.text_muted))
            letterSpacing = 0.04f
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = getString(R.string.ob_wizard_start)
            textSize = 12.5f
            setTextColor(onAccent)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(accent)
            }
        })
        val track = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(5), 1f).apply { marginStart = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(dshColor(R.attr.dshStroke, R.color.stroke))
            }
        }
        val filled = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(accent)
            }
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        track.addView(filled)
        track.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            (filled.layoutParams as FrameLayout.LayoutParams).width = (v.width * 0.55f).toInt()
            filled.layoutParams = filled.layoutParams
        }
        row.addView(track)
        box.addView(row)

        // 进度条与主按钮一并重涂 —— 这两处正是 v1.5.x 漏掉的地方
        progress.progressTintList = ColorStateList.valueOf(accent)
        btnNext.background?.mutate()?.setTint(accent)
        btnNext.setTextColor(onAccent)
    }

    private fun buildScreenCards() {
        // —— 页面缩放：四档覆盖常用区间 ——
        val scaleBox = findViewById<LinearLayout>(R.id.scaleContainer)
        scaleCards.clear()
        SCALE_CHOICES.forEach { pct ->
            scaleCards.add(
                optionCard(scaleBox, "$pct%", scaleHint(pct), R.drawable.ic_scale) {
                    selectedScale = pct
                    refreshSelection()
                },
            )
        }

        val layoutBox = findViewById<LinearLayout>(R.id.layoutModeContainer)
        layoutCards.clear()
        SettingsLayoutMode.entries.forEach { mode ->
            val icon = when (mode) {
                SettingsLayoutMode.LIST -> R.drawable.ic_layout
                SettingsLayoutMode.GRID -> R.drawable.ic_apps
                SettingsLayoutMode.COLLAPSE -> R.drawable.ic_expand_more
            }
            layoutCards.add(
                optionCard(layoutBox, getString(mode.labelRes), layoutHint(mode), icon) {
                    selectedLayoutMode = mode
                    refreshSelection()
                },
            )
        }

        val immersiveBox = findViewById<LinearLayout>(R.id.immersiveContainer)
        immersiveCards.clear()
        immersiveCards.add(
            optionCard(
                immersiveBox,
                getString(R.string.ob_wizard_immersive_on),
                getString(R.string.ob_wizard_immersive_on_desc),
                R.drawable.ic_status_bar,
            ) {
                selectedImmersive = true
                refreshSelection()
            },
        )
        immersiveCards.add(
            optionCard(
                immersiveBox,
                getString(R.string.ob_wizard_immersive_off),
                getString(R.string.ob_wizard_immersive_off_desc),
                R.drawable.ic_status_bar,
            ) {
                selectedImmersive = false
                refreshSelection()
            },
        )
        refreshSelection()
    }

    /**
     * 四档缩放各自的说明。
     *
     * 90% 与 100% 曾经共用「默认，平衡」一句（原来的 pct <= 100 分支把两者并在一起），
     * 实测两行副标题一字不差，用户根本分不出差别 —— 现在按具体档位分开：
     * 90% 是当前默认值，100% 是「原尺寸」，其余按大小描述。
     */
    private fun scaleHint(pct: Int): String = when {
        pct < DEFAULT_PAGE_SCALE -> getString(R.string.ob_wizard_scale_small)
        pct == DEFAULT_PAGE_SCALE -> getString(R.string.ob_wizard_scale_normal)
        pct == 100 -> getString(R.string.ob_wizard_scale_actual)
        else -> getString(R.string.ob_wizard_scale_large)
    }

    private fun layoutHint(mode: SettingsLayoutMode): String = when (mode) {
        SettingsLayoutMode.LIST -> getString(R.string.ob_wizard_layout_list_hint)
        SettingsLayoutMode.GRID -> getString(R.string.ob_wizard_layout_grid_hint)
        SettingsLayoutMode.COLLAPSE -> getString(R.string.ob_wizard_layout_collapse_hint)
    }

    private fun buildPrivCards() {
        val box = findViewById<LinearLayout>(R.id.privContainer)
        privCards.clear()
        listOf(
            Triple(PrivMode.NORMAL, R.string.ob_priv_normal, R.string.ob_priv_normal_desc),
            Triple(PrivMode.SHIZUKU, R.string.ob_priv_shizuku, R.string.ob_priv_shizuku_desc),
            Triple(PrivMode.ROOT, R.string.ob_priv_root, R.string.ob_priv_root_desc),
        ).forEach { (mode, titleRes, descRes) ->
            val icon = when (mode) {
                PrivMode.NORMAL -> R.drawable.ic_shield
                PrivMode.SHIZUKU -> R.drawable.ic_shizuku
                PrivMode.ROOT -> R.drawable.ic_root_shell
            }
            privCards.add(
                optionCard(box, getString(titleRes), getString(descRes), icon) {
                    if (mode == PrivMode.ROOT && !rootAvailable) {
                        // 无 su 时不接受选择，直接说明原因（比置灰更好懂）
                        Toast.makeText(this, R.string.ob_priv_root_gray_hint, Toast.LENGTH_SHORT).show()
                        return@optionCard
                    }
                    selectedPriv = mode
                    if (mode == PrivMode.SHIZUKU) maybeRequestShizuku()
                    refreshSelection()
                },
            )
        }
        refreshSelection()
    }

    // ================= 欢迎页 =================

    /**
     * 欢迎页五行能力卡。
     *
     * 文案全部走资源（`ob_wizard_welcome_f1..f5` + `_sub`），不写死在代码里：
     * 6 套语言要一起过 check-i18n，写死等于给门禁挖坑。
     */
    private fun buildWelcomeRows() {
        val box = findViewById<LinearLayout>(R.id.welcomeRows)
        box.removeAllViews()
 // 图标按现有 drawable 取（仓库没有 ic_cpu，用最接近的语义图标）
        val items = listOf(
            R.drawable.ic_terminal to (R.string.ob_wizard_welcome_f1 to R.string.ob_wizard_welcome_f1_sub),
            R.drawable.ic_ext_make to (R.string.ob_wizard_welcome_f2 to R.string.ob_wizard_welcome_f2_sub),
            R.drawable.ic_ext_archivers to (R.string.ob_wizard_welcome_f3 to R.string.ob_wizard_welcome_f3_sub),
            R.drawable.ic_touch to (R.string.ob_wizard_welcome_f4 to R.string.ob_wizard_welcome_f4_sub),
            R.drawable.ic_lan to (R.string.ob_wizard_welcome_f5 to R.string.ob_wizard_welcome_f5_sub),
        )
        items.forEach { (icon, texts) ->
            val (titleRes, subRes) = texts
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            val iconBox = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(Accent.current(this@OnboardingActivity).softArgb)
                }
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(11) }
            }
            iconBox.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(Accent.current(this@OnboardingActivity).argb)
                layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            })
            row.addView(iconBox)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = getString(titleRes)
                textSize = 13f
                setTextColor(dshColor(R.attr.dshTextPrimary, R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            col.addView(TextView(this).apply {
                text = getString(subRes)
                textSize = 11f
                setTextColor(dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
                setPadding(0, dp(1), 0, 0)
            })
            row.addView(col)
            box.addView(row)
        }
        // 封面眉标已按需求移除（与胶囊重复的那行小字），这里不再重涂颜色。
    }

    // ================= 完成页 =================

    /**
     * 完成页主按钮文案按运行环境四态切换。
     *
     * 不写死「开始第一次对话」：此时模型还没配，那句话做不到。
     * ready→「进入应用，继续配置模型」，preparing→「先进去，后台继续」，
     * failed→「进入维护里重试」，lite→「进入应用 · 需要时下载」。
     */
    private fun refreshDoneCta() {
        if (page != pages.size - 1) return
        btnNext.setText(
            when (rtState) {
                RtState.READY -> R.string.ob_done_cta_ready
                RtState.PREPARING -> R.string.ob_done_cta_preparing
                RtState.FAILED -> R.string.ob_done_cta_failed
                RtState.LITE -> R.string.ob_done_cta_lite
            },
        )
        val headRes = when (rtState) {
            RtState.READY -> R.string.ob_done_head_ready
            RtState.PREPARING -> R.string.ob_done_head_preparing
            RtState.FAILED -> R.string.ob_done_head_failed
            RtState.LITE -> R.string.ob_done_head_lite
        }
        val subRes = when (rtState) {
            RtState.READY -> R.string.ob_done_sub_ready
            RtState.PREPARING -> R.string.ob_done_sub_preparing
            RtState.FAILED -> R.string.ob_done_sub_failed
            RtState.LITE -> R.string.ob_done_sub_lite
        }
        findViewById<TextView>(R.id.doneTitle)?.text = getString(headRes)
        findViewById<TextView>(R.id.doneSub)?.text = getString(subRes)
        refreshDoneSummary()
    }

    /** 选择回顾：五枚 chip，靠状态函数生成（不写死，改任一项都会重算）。 */
    private fun refreshDoneSummary() {
        val box = findViewById<LinearLayout>(R.id.doneSummary) ?: return
        box.removeAllViews()
        val chips = listOf(
            getString(langLabelRes[selectedLang] ?: R.string.lang_zh),
            getString(
                when (selectedTheme) {
                    ThemeMode.DARK -> R.string.theme_dark
                    ThemeMode.LIGHT -> R.string.theme_light
                    ThemeMode.SYSTEM -> R.string.theme_system
                },
            ),
            getString(selectedAccent.labelRes),
            getString(
                when (selectedPriv) {
                    PrivMode.NORMAL -> R.string.ob_priv_normal
                    PrivMode.SHIZUKU -> R.string.ob_priv_shizuku
                    PrivMode.ROOT -> R.string.ob_priv_root
                },
            ),
            getString(R.string.ob_done_summary_scale, selectedScale),
        )
        chips.forEach { label ->
            box.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(6), dp(11), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(dshColor(R.attr.dshSurface, R.color.surface))
                    setStroke(dp(1).coerceAtLeast(1), dshColor(R.attr.dshStroke, R.color.stroke))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            })
        }
    }

    // ================= 能力开关 =================

    /** 当前选中的能力域（0..5），跨 recreate 由 savedInstanceState 保存。 */
    private var capGroupIndex = 0
    private val capPanelSwitches = mutableMapOf<Int, MutableList<Pair<String, com.google.android.material.materialswitch.MaterialSwitch>>>()

    /**
     * 六个能力域做成横向 chip，只渲染当前域。
     *
     * ## 为什么改成分组
     * 原来 17 项平铺 + 每域一个标题，小屏要滚三屏；分组后单屏最多 4 行，
     * 页面高度不再随能力总数增长。
     *
     * ## 为什么计数不能再靠 tag 找标题
     * 旧实现 refreshCapCounters() 遍历 capabilityContainer 直系子项、用
     * 「组标题 tag = 语言字符串」匹配组头。加分组长后组头不再是直系子项，
     * 那套遍历必然失效 —— 这里改成按组对象直接记账。
     */
    private fun buildCapabilitySwitches() {
        val chips = findViewById<LinearLayout>(R.id.capGroups)
        val panels = findViewById<LinearLayout>(R.id.capPanels)
        chips.removeAllViews()
        panels.removeAllViews()
        capSwitches.clear()
        capPanelSwitches.clear()

        CAP_GROUPS.forEachIndexed { gi, group ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(52),
                ).apply { marginEnd = dp(8) }
                tag = "chip:$gi"
            }
            chip.addView(TextView(this).apply {
                text = getString(capDomainRes(gi))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                tag = "chipTitle"
            })
            chip.addView(TextView(this).apply {
                textSize = 10.5f
                tag = "chipCount"
                setPadding(0, dp(2), 0, 0)
            })
            chip.setOnClickListener { selectCapGroup(gi) }
            chips.addView(chip)

            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = "panel:$gi"
            }
            val list = mutableListOf<Pair<String, com.google.android.material.materialswitch.MaterialSwitch>>()
            group.items.forEach { item ->
                val rowView = capRow(cap = item.cap, titleRes = item.titleRes, subRes = item.subRes)
                panel.addView(rowView)
                capSwitches[item.cap]?.let { list.add(item.cap to it) }
            }
            panels.addView(panel)
            capPanelSwitches[gi] = list
        }

        findViewById<TextView>(R.id.capRecommendHint).text = getString(R.string.ob_caps_recommend_hint)
        findViewById<TextView>(R.id.capNote).text = getString(R.string.ob_caps_note)
        findViewById<TextView>(R.id.btnCapRecommend).setOnClickListener { applyRecommendedCaps() }

        // 右侧提示箭头：滚到末端或没有溢出时隐藏（横向无溢出=所有域都看得见）
        val scroll = findViewById<android.widget.HorizontalScrollView>(R.id.capGroupsScroll)
        val arrow = findViewById<TextView>(R.id.capArrow)
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val overflow = chips.width - scroll.width
            arrow.visibility = if (overflow <= dp(4) || scroll.scrollX >= overflow - dp(4)) View.GONE else View.VISIBLE
        }
        scroll.post {
            val overflow = chips.width - scroll.width
            arrow.visibility = if (overflow <= dp(4)) View.GONE else View.VISIBLE
        }

        selectCapGroup(capGroupIndex)
    }

    private fun capDomainRes(gi: Int): Int = when (gi) {
        0 -> R.string.ob_caps_domain_env
        1 -> R.string.ob_caps_domain_notify
        2 -> R.string.ob_caps_domain_control
        3 -> R.string.ob_caps_domain_personal
        4 -> R.string.ob_caps_domain_input
        5 -> R.string.ob_caps_domain_system
        else -> R.string.ob_caps_domain_env
    }

    /** 选中一个能力域：只显示它的面板、chip 高亮、计数刷新、自动滚进可视区。 */
    private fun selectCapGroup(gi: Int) {
        capGroupIndex = gi.coerceIn(0, CAP_GROUPS.size - 1)
        val chips = findViewById<LinearLayout>(R.id.capGroups)
        val panels = findViewById<LinearLayout>(R.id.capPanels)
        val accent = Accent.current(this)
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i)
            val active = i == capGroupIndex
            chip.background = if (active) {
                GradientDrawable().apply {
                    cornerRadius = dp(11).toFloat()
                    setColor(accent.softArgb)
                    setStroke(dp(1).coerceAtLeast(1), accent.argb)
                }
            } else {
                GradientDrawable().apply {
                    cornerRadius = dp(11).toFloat()
                    setColor(dshColor(R.attr.dshSurface, R.color.surface))
                    setStroke(dp(1).coerceAtLeast(1), dshColor(R.attr.dshStroke, R.color.stroke))
                }
            }
            chip.findViewWithTag<TextView>("chipTitle")?.setTextColor(
                if (active) accent.argb else dshColor(R.attr.dshTextSecondary, R.color.text_secondary),
            )
            chip.findViewWithTag<TextView>("chipCount")?.setTextColor(
                if (active) accent.argb else dshColor(R.attr.dshTextMuted, R.color.text_muted),
            )
        }
        for (i in 0 until panels.childCount) {
            panels.getChildAt(i).visibility = if (i == capGroupIndex) View.VISIBLE else View.GONE
        }
        refreshCapCounters()
        findViewById<TextView>(R.id.capPos).text =
            getString(R.string.ob_caps_pos_fmt, capGroupIndex + 1, CAP_GROUPS.size)
        // 选中项自动滚进可视区（右侧箭头占位扣掉，避免刚露一半就被挡住）
        val scroll = findViewById<android.widget.HorizontalScrollView>(R.id.capGroupsScroll)
        val chip = chips.getChildAt(capGroupIndex) ?: return
        val arrowW = if (findViewById<TextView>(R.id.capArrow).visibility == View.VISIBLE) dp(24) else 0
        val left = chip.left - dp(4)
        val right = chip.right + dp(4) + arrowW
        if (left < scroll.scrollX || right > scroll.scrollX + scroll.width) {
            scroll.smoothScrollTo(left.coerceAtLeast(0), 0)
        }
    }

    /**
     * 计数：每个 chip 显示「已允许 n/m」，右上角显示全站合计。
     *
     * 语义是「已允许」而不是「可用」——CapabilityGate 要求 enabled 且
     * 系统权限可用才算真正可用，这里只反映用户的选择。
     */
    private fun refreshCapCounters() {
        val chips = findViewById<LinearLayout>(R.id.capGroups)
        var sum = 0
        var total = 0
        capPanelSwitches.forEach { (gi, list) ->
            val on = list.count { it.second.isChecked }
            total += list.size
            sum += on
            chips.getChildAt(gi)?.findViewWithTag<TextView>("chipCount")?.text =
                getString(R.string.ob_caps_chip_fmt, on, list.size)
        }
        findViewById<TextView>(R.id.capTotal).text =
            getString(R.string.ob_caps_total_fmt, sum, total)
    }

    private fun applyRecommendedCaps() {
        RECOMMENDED_CAPS.forEach { cap ->
            CapabilityGate.setEnabled(this, cap, true)
            capSwitches[cap]?.isChecked = true
        }
        refreshCapCounters()
        Toast.makeText(this, R.string.ob_caps_recommended_toast, Toast.LENGTH_SHORT).show()
    }

    // ================= 能力开关（单条渲染） =================

    private fun capRow(cap: String, titleRes: Int, subRes: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 与开关留 16dp：否则长副标题会一直排到开关边上（实测英文
            // 「Needs notification access.」与开关轨道间距为 0）。
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(16) }
        }
        textCol.addView(TextView(this).apply {
            text = getString(titleRes)
            textSize = 13f
            setTextColor(dshColor(R.attr.dshTextPrimary, R.color.text_primary))
        })
        textCol.addView(TextView(this).apply {
            text = getString(subRes)
            textSize = 10.5f
            setTextColor(dshColor(R.attr.dshTextMuted, R.color.text_muted))
            setPadding(0, dp(1), 0, 0)
        })
        row.addView(textCol)

        // 用 MaterialSwitch 而不是 android.widget.Switch：后者被 trackTintList 涂成
        // 纯强调色后，开/关两态都是同一个蓝点（实测截图里完全分不出来），观感也与
        // 设置页 PermissionCenterActivity 的同名开关不一致。MaterialSwitch 自带
        // 「开=实心强调色 / 关=灰色描边轨」两态，与设置页共用一套视觉语言。
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = CapabilityGate.enabled(this@OnboardingActivity, cap)
            setOnCheckedChangeListener { _, checked ->
                CapabilityGate.setEnabled(this@OnboardingActivity, cap, checked)
                refreshCapCounters()
            }
        }
        capSwitches[cap] = sw
        row.addView(sw)
        return row
    }

    /** 一个能力域：标题 + 条目。 */
    private data class CapGroup(val titleRes: Int, val items: List<CapItem>)

    /** 一条能力：cap 键 + 标题资源 + 副标题资源（副标题复用设置页文案）。 */
    private data class CapItem(val cap: String, val titleRes: Int, val subRes: Int)

    // ================= 能力检测 =================

    /** su 可用性缓存：探测一次，点击 Root 卡片时不再同步跑 su（主线程不能阻塞）。 */
    @Volatile private var rootAvailable = false

    /** Root 双确认弹窗进行中：避免 Back / 重复点击再触发一次落库。 */
    private var rootConfirmPending = false

    private fun probeCapabilities() {
        val v = findViewById<TextView>(R.id.capRoot)
        v.text = getString(R.string.ob_cap_root_checking)
        v.setTextColor(dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
        Thread({
            val hasRoot = Privilege.probe(this).hasRoot
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                v.text = getString(
                    if (hasRoot) R.string.ob_cap_root_present else R.string.ob_cap_root_absent,
                )
                v.setTextColor(if (hasRoot) dshColor(R.attr.dshSuccess, R.color.success) else dshColor(R.attr.dshWarning, R.color.warning))
                applyRootAvailability(hasRoot)
            }
        }, "probe-root").apply { isDaemon = true }.start()
    }

    private fun applyRootAvailability(rootOk: Boolean) {
        rootAvailable = rootOk
        if (!rootOk && selectedPriv == PrivMode.ROOT) selectedPriv = PrivMode.NORMAL
        refreshSelection()
    }

    private fun refreshShizukuStatus() {
        val v = findViewById<TextView>(R.id.capShizuku)
        when {
            Privilege.shizukuUsable() -> {
                v.text = getString(R.string.ob_cap_shizuku_present)
                v.setTextColor(dshColor(R.attr.dshSuccess, R.color.success))
                v.setOnClickListener(null)
            }
            Privilege.shizukuServerRunning() -> {
                // 不在此自动请求：用户拒绝后回调会回到这里形成死循环（历史教训）。
                // 显式入口是「选中 Shizuku 卡片」→ maybeRequestShizuku()
                v.text = getString(R.string.ob_cap_shizuku_request)
                v.setTextColor(dshColor(R.attr.dshWarning, R.color.warning))
                v.isClickable = true
                v.setOnClickListener {
                    Privilege.requestShizukuPermission(SHIZUKU_REQ, this, explicit = true)
                }
            }
            else -> {
                v.text = getString(R.string.ob_cap_shizuku_absent)
                v.setTextColor(dshColor(R.attr.dshWarning, R.color.warning))
                v.setOnClickListener(null)
            }
        }
    }

    private fun maybeRequestShizuku() {
        if (Privilege.shizukuServerRunning() && !Privilege.shizukuGranted()) {
            Privilege.requestShizukuPermission(SHIZUKU_REQ, this, explicit = true)
        }
    }

    // ================= 收尾 =================

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_PAGE, page)
        outState.putString(KEY_LANG, selectedLang)
        outState.putString(KEY_ORIENTATION, selectedOrient.name)
        outState.putString(KEY_THEME, selectedTheme.key)
        outState.putString(KEY_ACCENT, selectedAccent.id)
        outState.putString(KEY_PRIV, selectedPriv.name)
        outState.putInt(KEY_SCALE, selectedScale)
        outState.putString(KEY_LAYOUT, selectedLayoutMode.id)
        outState.putBoolean(KEY_IMMERSIVE, selectedImmersive)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        glowBreath?.resume()
    }

    override fun onStop() {
        // 切到后台时暂停，回来接着呼吸；不 cancel 是为了保住当前相位
        glowBreath?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        stopGlowBreath()
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    /**
     * 返回键：抽屉开着先关抽屉，否则退一步，首页才完成引导。
     *
     * 注意首屏仍然直接 commit —— 历史 bug 是「按返回键卡在引导页出不去」，
     * 这里不能改成 finish() 或什么都不做。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stepsDrawer.visibility == View.VISIBLE) {
            openStepsDrawer(false)
            return
        }
        if (page > 0) {
            goTo(page - 1)
            return
        }
        // 封面页按返回也走统一出口：Root 模式下同样要过两次确认，
        // 否则「选 Root → 按 Back」就是历史上那条绕过路径。
        finishOnboarding()
    }

    /**
     * 启动页预取运行环境（v1.7.10）。
     *
     * 完整包：引导页一出现就在后台解压，完成页显示「正在解压 42% / 已就绪」。
     * 精简包（noruntime）：**不预取**。APK 里没有 runtime.zip，下载必须由用户
     * 在完成引导后显式确认（MainActivity.showRuntimeSetupDialog）。
     *
     * 解压与 EngineSupervisor 共用 RuntimeInstaller.INSTALL_LOCK：若用户很快点完，
     * 引擎启动会等这把锁，不会重复解压、也不会两份运行时打架。
     */
    private fun startRuntimePrefetch() {
        val installer = RuntimeInstaller(applicationContext)
        val needsDownload = runCatching { installer.needsRuntimeDownload() }.getOrDefault(false)
        if (needsDownload) {
            renderRuntime(RtState.LITE, 0)
            return
        }

        // recreate（切语言/主题）会重建 Activity，但解压任务属于进程级：
        // 上一个实例可能仍在跑，这里先接上它已有的结果，不要从 0% 重来、
        // 更不要并发再起一份解压（INSTALL_LOCK 会串行化，只是白等）。
        val known = PrefetchResult.state
        if (known != null) {
            renderRuntime(known, PrefetchResult.pct)
        } else {
            renderRuntime(RtState.PREPARING, 0)
        }

        PrefetchResult.thread?.takeIf { it.isAlive }?.let { return }

        val t = Thread({
            var failed = false
            runCatching {
                installer.ensureInstalled(
                    onProgress = { p ->
                        val pct = (p * 100f).toInt().coerceIn(0, 100)
                        PrefetchResult.pct = pct
                        if (pct == lastPrefetchPct) return@ensureInstalled
                        lastPrefetchPct = pct
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            renderRuntime(RtState.PREPARING, pct)
                        }
                    },
                )
            }.onFailure { failed = true }
            val final = if (failed) RtState.FAILED else RtState.READY
            PrefetchResult.state = final
            PrefetchResult.pct = if (failed) 0 else 100
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderRuntime(final, PrefetchResult.pct)
            }
        }, "onboarding-runtime-prefetch")
        t.isDaemon = true
        PrefetchResult.thread = t
        t.start()
    }

    /** 预取任务属于进程而非 Activity：recreate 后新实例要能接上同一份进度。 */
    private object PrefetchResult {
        @Volatile var state: RtState? = null
        @Volatile var pct: Int = 0
        @Volatile var thread: Thread? = null
    }

    /**
     * 运行环境四态的唯一渲染入口。
     *
     * 欢迎页胶囊与完成页状态卡都从这里取，避免两处各算一套导致
     * 「胶囊 12% / 完成页 63%」这类假数据。
     */
    private fun renderRuntime(state: RtState, pct: Int) {
        rtState = state
        rtPct = pct
        val titleRes = when (state) {
            RtState.PREPARING -> if (pct >= 100) R.string.ob_rt_ready else R.string.ob_rt_pct
            RtState.READY -> R.string.ob_rt_ready
            RtState.FAILED -> R.string.ob_rt_failed
            RtState.LITE -> R.string.ob_rt_download
        }
        val subRes = when (state) {
            RtState.PREPARING -> R.string.ob_done_rt_sub_preparing
            RtState.READY -> R.string.ob_done_rt_sub_ready
            RtState.FAILED -> R.string.ob_done_rt_sub_failed
            RtState.LITE -> R.string.ob_done_rt_sub_lite
        }

        doneRuntimeStatus?.apply {
            text = if (state == RtState.PREPARING && pct < 100) getString(titleRes, pct)
                   else getString(titleRes)
            visibility = View.VISIBLE
            setTextColor(getColor(if (state == RtState.FAILED) R.color.danger else R.color.text_primary))
        }
        doneRuntimeSub?.apply {
            text = getString(subRes)
            visibility = View.VISIBLE
        }
        doneRuntimeBar?.apply {
            visibility = if (state == RtState.PREPARING) View.VISIBLE else View.GONE
            progress = pct
        }
        // 100% 也算终态：解压回调会先报满再落到 READY，若这里继续按
        // PREPARING 走就能拍出「正在解压… 100%」还带转圈的画面，
        // 看上去像卡在最后一格。与 titleRes 的判定保持一致。
        val settling = state == RtState.PREPARING && pct < 100
        // 进度只在视觉上变是不行的：TalkBack 与触摸探索用户拿不到任何反馈。
        // 朗读源**只留一处** —— 内层 TextView 的 text + POLITE liveRegion。
        // 不要再给容器补 contentDescription：ViewGroup 一旦有 content-description
        // 就会把子视图合并成一个节点，容器描述与子 TextView 文本叠在一起，
        // 实测 dump 里两处都有，TalkBack 会把同一句话读两遍。
        // 转圈本身是装饰，重要度设 no 让它不单独入焦点、也不念「进度条」。
        welcomePillText?.apply {
            text = if (settling) getString(R.string.ob_rt_pct, pct) else getString(titleRes)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        // 进度条不能每 1% 都念一遍：60 秒的解析过程能触发上百次播报，
        // 用户的听觉反馈会被彻底淹没。只在四分之一档与终态播报，
        // 既保证「还活着/到哪了」可感知，又不打断用户正在做的事。
        if (page == 0) announceRuntimeMilestone(state, pct)
        // 只有「还在解压」才转圈：就绪/失败/需下载都是终态，转圈会让人以为还在跑。
        welcomePillSpinner?.visibility = if (settling) View.VISIBLE else View.GONE
        syncCoverStatus()
        refreshDoneCta()
    }

    /**
     * 封面页眉只能有一行状态，否则重复说同一件事。
     *
     * 完整包有可跟踪的进度胶囊（「正在解压 4%」），此时眉标「约 30 秒、
     * 环境在后台解压」就是废话；精简包（LITE）拿不到进度，眉标才有价值。
     * 胶囊挂在 topBlock（所有页共用），所以离开封面页必须一起收起来，
     * 否则后台解压回调会在「选择语言」的页眉上冒出「正在解压 63%」。
     */
    /**
     * 把一个视觉尺寸不足 48dp 的控件，用 [android.view.TouchDelegate] 把**热区**扩到 [minDp]。
     *
     * 只扩热区不动外观：引导页顶栏的「全部步骤」胶囊只有 30dp 高（再多就把
     * pageHost 视口压掉一行），但 30dp 触控目标在实测里确实难按中。
     * 父容器负责把落在扩展区的触摸转发给这个 View —— Android 官方支持的
     * 「小控件大热区」做法；扩展区允许负起点，超出父容器的部分自然无效。
     */
    private fun expandTouchTarget(target: View, host: ViewGroup, minDp: Int) {
        val minPx = dp(minDp)
        val growX = ((minPx - target.width) / 2).coerceAtLeast(0)
        val growY = ((minPx - target.height) / 2).coerceAtLeast(0)
        if (growX == 0 && growY == 0) return
        // TouchDelegate 的 rect 用的是**代理视图自己的坐标系**，但事件是交给
        // host 派发的 —— host 必须先覆盖那块区域，否则点在 host 之外根本到不了
        // 这里。所以直接按 host 的左上角算偏移（先实测见 touch_above 两轮失败）。
        val loc = IntArray(2)
        val hostLoc = IntArray(2)
        target.getLocationInWindow(loc)
        host.getLocationInWindow(hostLoc)
        val left = loc[0] - hostLoc[0]
        val top = loc[1] - hostLoc[1]
        val rect = android.graphics.Rect(
            left - growX,
            top - growY,
            left + target.width + growX,
            top + target.height + growY,
        )
        host.touchDelegate = android.view.TouchDelegate(rect, target)
    }

    /** 上一次播报过的档位（0/25/50/75/100），避免重复念同一句话。 */
    private var announcedMilestone: Int = -1

    private fun announceRuntimeMilestone(state: RtState, pct: Int) {
        val milestone = when {
            state != RtState.PREPARING -> 100
            pct >= 100 -> 100
            pct >= 75 -> 75
            pct >= 50 -> 50
            pct >= 25 -> 25
            else -> 0
        }
        if (milestone == announcedMilestone) return
        announcedMilestone = milestone
        val spoken = if (state == RtState.PREPARING && pct < 100) {
            getString(R.string.ob_rt_pct, pct)
        } else {
            getString(
                when (state) {
                    RtState.READY -> R.string.ob_rt_ready
                    RtState.FAILED -> R.string.ob_rt_failed
                    RtState.LITE -> R.string.ob_rt_download
                    RtState.PREPARING -> R.string.ob_rt_ready
                },
            )
        }
        welcomePill?.announceForAccessibility(spoken)
    }

    private fun syncCoverStatus() {
        val onCover = page == 0
        // 封面页永远只有胶囊这一行状态：眉标「约 30 秒、环境在后台解压」
        // 对精简包（LITE）是假的 —— 那种包根本没有可解压的东西，要用户
        // 自己确认下载，所以精简包的封面必须是「精简包：进入后选择是否下载」。
        stepHint.visibility = if (onCover) View.GONE else View.VISIBLE
        welcomePill?.visibility = if (onCover) View.VISIBLE else View.GONE
    }

    private fun showRuntimeStatus(text: String, progress: Int?) = Unit

    /**
     * 完成引导的**唯一**出口。
     *
     * 历史缺陷：Root 双确认只挂在「下一步」这一条路径上，而系统 Back
     * （`onBackPressed` 在封面页）与步骤轨跳转都会直接 `commit()`，
     * 于是「选 Root → 按 Back」可以完全绕过两次确认就落库。
     * 现在所有出口都走这里，双确认在内部统一执行。
     */
    private fun finishOnboarding() {
        if (rootConfirmPending) return   // 防止 Back 在弹窗期间再触发一次
        if (selectedPriv == PrivMode.ROOT && rootAvailable) {
            rootConfirmPending = true
            warnRoot {
                warnRootAgain {
                    rootConfirmPending = false
                    commitNow()
                }
            }
        } else {
            // 无 su 时 Root 本就选不了；若状态残留则回落普通，不让它静默落库
            if (selectedPriv == PrivMode.ROOT) selectedPriv = PrivMode.NORMAL
            commitNow()
        }
    }

    private fun warnRoot(next: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ob_priv_root_warn_title))
            .setMessage(getString(R.string.ob_priv_root_warn))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ ->
                // 取消 = 以普通模式继续并进主界面；不落 Root，也不卡在引导页
                rootConfirmPending = false
                selectedPriv = PrivMode.NORMAL
                commitNow()
            }
            .show()
    }

    private fun warnRootAgain(next: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ob_priv_root_warn2_title))
            .setMessage(
                getString(R.string.ob_priv_root_warn2) + "\n\n" +
                    getString(R.string.ob_priv_root_paths_warn),
            )
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ ->
                rootConfirmPending = false
                selectedPriv = PrivMode.NORMAL
                commitNow()
            }
            .create()
            .apply {
            }
            .show()
    }

    /** 真正落库并进主界面。**不要**从任何出口直接调用，一律走 [finishOnboarding]。 */
    private fun commitNow() {
        Privilege.setMode(this, selectedPriv)
        Privilege.markOnboarded(this)
        LocaleHelper.set(this, selectedLang)
        ThemePref.set(this, selectedTheme)
        Accent.set(this, selectedAccent)
        // 不能设 requestedOrientation：本 Activity 紧接着 finish()，
        // 方向请求会落在正在销毁的窗口上被丢弃（历史 bug）。
        OrientationPrefs.set(this, selectedOrient)
        // v1.7.0 新增三项：页面缩放 / 设置页布局 / 沉浸式
        getSharedPreferences(DshApp.PREFS_UI, MODE_PRIVATE)
            .edit().putInt(KEY_PAGE_SCALE, selectedScale).apply()
        SettingsLayoutMode.set(this, selectedLayoutMode)
        MainActivity.setImmersiveEnabled(this, selectedImmersive)

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun currentPageScale(): Int = getSharedPreferences(DshApp.PREFS_UI, MODE_PRIVATE)
        .getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

    // ================= 小组件 =================

    private fun sectionLabel(res: Int): TextView = TextView(this).apply {
        text = getString(res)
        textSize = 11f
        setTextColor(dshColor(R.attr.dshTextMuted, R.color.text_muted))
        letterSpacing = 0.05f
        isAllCaps = true
        setPadding(0, 0, 0, dp(4))
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun outlinedCircle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x00000000)
        setStroke(dp(1).coerceAtLeast(1), color)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val SHIZUKU_REQ = 4201
        const val WIDE_DP = 600
        const val KEY_PAGE = "ob_page"
        const val KEY_LANG = "ob_lang"
        const val KEY_ORIENTATION = "ob_orientation"
        const val KEY_THEME = "ob_theme"
        const val KEY_ACCENT = "ob_accent"
        const val KEY_PRIV = "ob_priv"
        const val KEY_SCALE = "ob_scale"
        const val KEY_LAYOUT = "ob_layout"
        const val KEY_IMMERSIVE = "ob_immersive"

        const val KEY_PAGE_SCALE = "page_scale"
        const val DEFAULT_PAGE_SCALE = 90

        /** 四档缩放：小屏能多塞内容，大屏看得清。 */
        val SCALE_CHOICES = listOf(75, 90, 100, 110)

        /**
         * 引导页「推荐配置」只开这些：全是**只读**、不代用户对外发言的能力。
         *
         * 刻意排除 notify_read / notify_action / personal_sms /
         * personal_calls / inputwatch / keywatch / appdata / root —— 那些是
         * 「看你在干什么」或「代你说话」，必须在设置页逐项确认。
         */
        val RECOMMENDED_CAPS = listOf(
            CapabilityGate.STATUS,
            CapabilityGate.SENSOR,
            CapabilityGate.CLIPBOARD,
            CapabilityGate.CONTROL,
            CapabilityGate.SCHED,
            CapabilityGate.NOTIFY,
        )

        /** 六个能力域。副标题直接复用设置页 setting_cap_*_sub，不另写一份。 */
        val CAP_GROUPS = listOf(
            CapGroup(
                R.string.setting_cap_domain_env,
                listOf(
                    CapItem(CapabilityGate.STATUS, R.string.setting_cap_status, R.string.setting_cap_status_sub),
                    CapItem(CapabilityGate.SENSOR, R.string.setting_cap_sensor, R.string.setting_cap_sensor_sub),
                    CapItem(CapabilityGate.CLIPBOARD, R.string.setting_cap_clip, R.string.setting_cap_clip_sub),
                ),
            ),
            CapGroup(
                R.string.setting_cap_domain_notify,
                listOf(
                    CapItem(CapabilityGate.NOTIFY, R.string.setting_cap_notify, R.string.setting_cap_notify_sub),
                    CapItem(
                        CapabilityGate.NOTIFY_READ,
                        R.string.setting_cap_notify_read,
                        R.string.setting_cap_notify_read_sub,
                    ),
                    CapItem(CapabilityGate.MEDIA, R.string.setting_cap_media, R.string.setting_cap_media_sub),
                ),
            ),
            CapGroup(
                R.string.setting_cap_domain_control,
                listOf(
                    CapItem(CapabilityGate.CONTROL, R.string.setting_cap_control, R.string.setting_cap_control_sub),
                    CapItem(CapabilityGate.SCHED, R.string.setting_cap_sched, R.string.setting_cap_sched_sub),
                    CapItem(CapabilityGate.WIFI, R.string.setting_cap_wifi, R.string.setting_cap_wifi_sub),
                    CapItem(CapabilityGate.BT, R.string.setting_cap_bt, R.string.setting_cap_bt_sub),
                ),
            ),
            CapGroup(
                R.string.setting_cap_domain_personal,
                listOf(
                    CapItem(
                        CapabilityGate.PERSONAL_CALENDAR,
                        R.string.setting_cap_personal_calendar,
                        R.string.setting_cap_personal_calendar_sub,
                    ),
                    CapItem(
                        CapabilityGate.PERSONAL_CONTACTS,
                        R.string.setting_cap_personal_contacts,
                        R.string.setting_cap_personal_contacts_sub,
                    ),
                ),
            ),
            CapGroup(
                R.string.setting_cap_domain_input,
                listOf(
                    CapItem(
                        CapabilityGate.SCREEN_CAPTURE,
                        R.string.setting_cap_screen_capture,
                        R.string.setting_cap_screen_capture_sub,
                    ),
                    CapItem(CapabilityGate.LOCATION, R.string.setting_cap_location, R.string.setting_cap_location_sub),
                    CapItem(CapabilityGate.CAMERA, R.string.setting_cap_camera, R.string.setting_cap_camera_sub),
                ),
            ),
            CapGroup(
                R.string.setting_cap_domain_system,
                listOf(
                    CapItem(CapabilityGate.PACKAGES, R.string.setting_cap_packages, R.string.setting_cap_packages_sub),
                    CapItem(CapabilityGate.LINUX, R.string.setting_cap_linux, R.string.setting_cap_linux_sub),
                ),
            ),
        )
    }
}

package app.dsh.mobile

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textview.MaterialTextView

/**
 * 首次启动引导（v1.7.0 重做为七步、双形态向导）。
 *
 * ## 七步
 *  1. 语言（6 种）
 *  2. 显示方向（带画面预览）
 *  3. 外观主题（带明暗预览）
 *  4. 系统动态取色开关
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
 *  语言 6 种、缩放 4 档、布局 3 种、沉浸 2 种、能力十余项 ——
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

    private val steps = listOf(
        Step(R.string.ob_wizard_language_title),
        Step(R.string.ob_wizard_orientation_title),
        Step(R.string.ob_wizard_theme_title),
        Step(R.string.theme_dynamic),
        Step(R.string.ob_wizard_screen_title),
        Step(R.string.ob_wizard_priv_title),
        Step(R.string.ob_wizard_done_title),
    )

    private var page = 0

    private var selectedLang = "zh"
    private var selectedOrient: OrientationMode = OrientationMode.PORTRAIT
    private var selectedTheme: ThemeMode = ThemeMode.SYSTEM
    private var selectedPriv: PrivMode = PrivMode.NORMAL
    private var selectedScale = DEFAULT_PAGE_SCALE
    private var selectedLayoutMode: SettingsLayoutMode = SettingsLayoutMode.LIST
    private var selectedImmersive = false

    /** 完成页的运行环境预取状态（v1.7.10）。 */
    private var doneRuntimeStatus: TextView? = null
    private var doneRuntimeBar: ProgressBar? = null
    @Volatile private var lastPrefetchPct = -1

    private lateinit var pages: List<View>
    private lateinit var progress: ProgressBar
    private lateinit var stepHint: TextView
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button
    private lateinit var railSteps: LinearLayout
    private lateinit var drawerSteps: LinearLayout
    private var stepsDrawer: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private lateinit var sideRail: LinearLayout
    private lateinit var btnSteps: TextView

    /** 步骤轨条目：两种形态各一套，切换时同步高亮。 */
    private val railItems = mutableListOf<MaterialButton>()
    private val drawerItems = mutableListOf<MaterialButton>()

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

    private val langCards = mutableListOf<MaterialCardView>()
    private val orientCards = mutableListOf<MaterialCardView>()
    private val themeCards = mutableListOf<MaterialCardView>()
    private val scaleCards = mutableListOf<MaterialCardView>()
    private val layoutCards = mutableListOf<MaterialCardView>()
    private val immersiveCards = mutableListOf<MaterialCardView>()
    private val privCards = mutableListOf<MaterialCardView>()
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
        drawerSteps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        sideRail = findViewById(R.id.sideRail)
        btnSteps = findViewById(R.id.btnSteps)

        pages = listOf(
            findViewById(R.id.pageLanguage),
            findViewById(R.id.pageOrientation),
            findViewById(R.id.pageTheme),
            findViewById(R.id.pageAccent),
            findViewById(R.id.pageScreen),
            findViewById(R.id.pagePriv),
            findViewById(R.id.pageDone),
        )
        progress.max = pages.size

        buildStepper()
        buildLanguageCards()
        buildOrientationCards()
        buildThemeCards()
        buildDynamicColorOption()
        buildScreenCards()
        buildPrivCards()
        buildCapabilitySwitches()

        btnBack.setOnClickListener { goTo(page - 1) }
        btnNext.setOnClickListener {
            if (page == pages.size - 1) finishOnboarding() else goTo(page + 1)
        }

        // 手机形态：顶部胶囊打开步骤抽屉；点遮罩关闭
        btnSteps.setOnClickListener { openStepsDrawer(true) }

        applyFormFactor()
        probeCapabilities()
        refreshShizukuStatus()
        updateRootAvailability()

        // v1.7.10：用户挑语言/主题的这十几秒足够解压 runtime.zip。
        // 解压提前到这里，点「完成」后引擎只需启动，不再让用户盯着遮罩干等。
        doneRuntimeStatus = findViewById(R.id.txtDoneRuntime)
        doneRuntimeBar = findViewById(R.id.pbDoneRuntime)
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

    /** 步骤按钮保留直接跳转和已完成标记。 */
    private fun stepRow(parent: LinearLayout, index: Int): MaterialButton {
        val row = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            isAllCaps = false
            setPaddingRelative(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(2) }
            setOnClickListener {
                openStepsDrawer(false)
                goTo(index)
            }
        }
        parent.addView(row)
        return row
    }

    private fun refreshStepper() {
        fun paint(row: MaterialButton, index: Int) {
            val marker = if (index < page) "\u2713" else (index + 1).toString()
            row.text = "$marker  ${getString(steps[index].titleRes)}"
            row.isSelected = index == page
            row.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    row,
                    if (index == page) com.google.android.material.R.attr.colorSecondaryContainer
                    else com.google.android.material.R.attr.colorSurface,
                ),
            )
            row.setTextColor(
                MaterialColors.getColor(
                    row,
                    if (index == page) com.google.android.material.R.attr.colorOnSecondaryContainer
                    else com.google.android.material.R.attr.colorOnSurface,
                ),
            )
        }
        railItems.forEachIndexed { i, r -> paint(r, i) }
        drawerItems.forEachIndexed { i, r -> paint(r, i) }
    }

    private fun openStepsDrawer(open: Boolean) {
        if (!open) {
            stepsDrawer?.dismiss()
            return
        }
        if (stepsDrawer?.isShowing == true) return
        (drawerSteps.parent as? ViewGroup)?.removeView(drawerSteps)
        val scroll = android.widget.ScrollView(this).apply {
            addView(drawerSteps)
        }
        stepsDrawer = com.google.android.material.bottomsheet.BottomSheetDialog(this).apply {
            setContentView(scroll)
            setOnDismissListener { stepsDrawer = null }
            show()
        }
    }

    // ================= 分页 =================

    private fun goTo(index: Int) {
        if (index !in pages.indices) return
        val previous = page
        page = index
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        animateStep(previous, index)
        progress.progress = index + 1
        stepHint.text = getString(R.string.ob_wizard_step_fmt, index + 1, pages.size)

        // 首页不显示「上一步」：用 GONE 而不是 INVISIBLE，否则它仍占着
        // layout_weight 的宽度，右侧按钮会被挤到屏幕中间（v1.5.x 实测偏右 400px）。
        btnBack.visibility = if (index == 0) View.GONE else View.VISIBLE
        btnNext.setText(
            if (index == pages.size - 1) R.string.ob_wizard_start else R.string.ob_wizard_next,
        )
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
        glow.background?.mutate()?.setTint(
            MaterialColors.getColor(glow, androidx.appcompat.R.attr.colorPrimary),
        )

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
    private fun materialOptionCard(
        container: LinearLayout,
        title: String,
        subtitle: String?,
        iconRes: Int,
        onClick: () -> Unit,
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            contentDescription = listOfNotNull(title, subtitle?.takeIf { it.isNotBlank() })
                .joinToString(", ")
            setAccessibilityDelegate(object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(8), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) }
        })
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun appearance(attr: Int): Int = TypedValue().let {
            theme.resolveAttribute(attr, it, true)
            it.resourceId
        }
        textCol.addView(MaterialTextView(this).apply {
            text = title
            setTextAppearance(appearance(com.google.android.material.R.attr.textAppearanceBodyLarge))
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        })
        if (!subtitle.isNullOrBlank()) {
            textCol.addView(MaterialTextView(this).apply {
                text = subtitle
                setTextAppearance(appearance(com.google.android.material.R.attr.textAppearanceBodyMedium))
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
        row.addView(textCol)
        row.addView(MaterialRadioButton(this).apply {
            tag = "radio"
            isClickable = false
            isFocusable = false
        })
        card.addView(row)
        card.setOnClickListener { onClick() }
        container.addView(card)
        return card
    }

    /** 统一重算所有卡片的选中态（改任一项都调它）。 */
    private fun refreshSelection() {
        fun markMaterial(card: MaterialCardView, active: Boolean) {
            card.isSelected = active
            card.findViewWithTag<MaterialRadioButton>("radio")?.isChecked = active
        }
        langCards.forEachIndexed { i, c -> markMaterial(c, langCodes[i] == selectedLang) }
        orientCards.forEachIndexed { i, c -> markMaterial(c, i == orientIndexOf(selectedOrient)) }
        themeCards.forEachIndexed { i, c -> markMaterial(c, i == themeIndexOf(selectedTheme)) }
        scaleCards.forEachIndexed { i, c -> markMaterial(c, SCALE_CHOICES[i] == selectedScale) }
        layoutCards.forEachIndexed { i, c -> markMaterial(c, i == layoutIndexOf(selectedLayoutMode)) }
        immersiveCards.forEachIndexed { i, c -> markMaterial(c, (i == 0) == selectedImmersive) }
        privCards.forEachIndexed { i, c -> markMaterial(c, i == privIndexOf(selectedPriv)) }
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
                materialOptionCard(
                    box,
                    getString(langLabelRes[code]!!),
                    langNativeName(code),
                    R.drawable.ic_language,
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
        val hintBox = findViewById<LinearLayout>(R.id.orientHintContent)
        hintBox.removeAllViews()
        hintBox.addView(sectionLabel(R.string.ob_wizard_orient_how))
        listOf(
            R.string.setting_orient_portrait to R.string.ob_wizard_orient_portrait_hint,
            R.string.setting_orient_landscape to R.string.ob_wizard_orient_landscape_hint,
            R.string.setting_orient_auto to R.string.ob_wizard_orient_auto_hint,
        ).forEach { (nameRes, hintRes) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(MaterialTextView(this).apply {
                text = getString(nameRes)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
            row.addView(MaterialTextView(this).apply {
                text = getString(hintRes)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
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
            orientCards.add(materialOptionCard(box, getString(labelRes), null, icon) {
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
                materialOptionCard(box, getString(labelRes), themeHint(mode), R.drawable.ic_theme) {
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

    /** 系统动态色与主题页共用同一偏好；切换后仅重建当前引导页。 */
    private fun buildDynamicColorOption() {
        val container = findViewById<LinearLayout>(R.id.accentContainer)
        container.removeAllViews()
        val supported = ThemeAccent.dynamicSupported
        val toggle = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.theme_dynamic)
            isEnabled = supported
            isChecked = supported && ThemeAccent.dynamicEnabled(this@OnboardingActivity)
            isSaveEnabled = false
        }
        val description = com.google.android.material.textview.MaterialTextView(this).apply {
            setText(if (supported) R.string.theme_dynamic_sub else R.string.theme_dynamic_unsupported)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(toggle)
            addView(description)
        }
        container.addView(MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer))
            addView(content)
        })
        toggle.setOnCheckedChangeListener { _, checked ->
            if (ThemeAccent.dynamicEnabled(this) != checked) {
                ThemeAccent.setDynamicEnabled(this, checked)
                recreate()
            }
        }
    }

    private fun buildScreenCards() {
        // —— 页面缩放：四档覆盖常用区间 ——
        val scaleBox = findViewById<LinearLayout>(R.id.scaleContainer)
        scaleCards.clear()
        SCALE_CHOICES.forEach { pct ->
            scaleCards.add(
                materialOptionCard(scaleBox, "$pct%", scaleHint(pct), R.drawable.ic_scale) {
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
                materialOptionCard(layoutBox, getString(mode.labelRes), layoutHint(mode), icon) {
                    selectedLayoutMode = mode
                    refreshSelection()
                },
            )
        }

        val immersiveBox = findViewById<LinearLayout>(R.id.immersiveContainer)
        immersiveCards.clear()
        immersiveCards.add(
            materialOptionCard(
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
            materialOptionCard(
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
                materialOptionCard(box, getString(titleRes), getString(descRes), icon) {
                    if (mode == PrivMode.ROOT && !Privilege.rootAvailableMinimal()) {
                        // 无 su 时不接受选择，直接说明原因（比置灰更好懂）
                        Toast.makeText(this, R.string.ob_priv_root_gray_hint, Toast.LENGTH_SHORT).show()
                        return@materialOptionCard
                    }
                    selectedPriv = mode
                    if (mode == PrivMode.SHIZUKU) maybeRequestShizuku()
                    refreshSelection()
                },
            )
        }
        refreshSelection()
    }

    // ================= 能力开关 =================

    /**
     * 十余个能力开关，按域分组。
     *
     * ## 为什么引导页要问这个
     * 设置页 PermissionCenterActivity 有 6 个域、19 个开关，但用户在引导页
     * 只能选「普通 / Shizuku / Root」三档 —— 进主界面后 AI 一调能力就 403，
     * 而他不知道刚才漏了哪一项。
     *
     * ## 为什么分域而不是平铺
     * 平铺十九行在小屏上要滚三屏，用户会直接跳过。按域分组带「n/m 已开」计数。
     *
     * ## 默认值
     * 全部沿用设置页现状（CapabilityGate.enabled() 默认 false），
     * 另给「推荐配置」一键开启低风险只读项 —— 见 applyRecommendedCaps。
     */
    private fun buildCapabilitySwitches() {
        val box = findViewById<LinearLayout>(R.id.capabilityContainer)
        box.removeAllViews()
        capSwitches.clear()
        CAP_GROUPS.forEach { group ->
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(6))
                tag = "group:" + getString(group.titleRes)
            }
            header.addView(TextView(this).apply {
                text = getString(group.titleRes)
                textSize = 12f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(TextView(this).apply {
                textSize = 11f
                setTextColor(getColor(R.color.text_muted))
                tag = "count"
            })
            box.addView(header)

            group.items.forEach { item ->
                box.addView(capRow(cap = item.cap, titleRes = item.titleRes, subRes = item.subRes))
            }
        }
        findViewById<TextView>(R.id.btnCapRecommend).setOnClickListener { applyRecommendedCaps() }
        refreshCapCounters()
    }

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
            textSize = 13.5f
            setTextColor(getColor(R.color.text_primary))
        })
        textCol.addView(TextView(this).apply {
            text = getString(subRes)
            textSize = 10.5f
            setTextColor(getColor(R.color.text_muted))
            setPadding(0, dp(1), 0, 0)
        })
        row.addView(textCol)

        // 用 MaterialSwitch 而不是 android.widget.Switch：后者被 trackTintList 涂成
        // 纯强调色后，开/关两态都是同一个蓝点（实测截图里完全分不出来），观感也与
        // 设置页 PermissionCenterActivity 的同名开关不一致。MaterialSwitch 自带
        // 「开=实心强调色 / 关=灰色描边轨」两态，与设置页共用一套视觉语言。
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            contentDescription = getString(titleRes)
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

    /** 每组「n/m 已开」的计数，让用户一眼看到哪一组还是空的。 */
    private fun refreshCapCounters() {
        val box = findViewById<LinearLayout>(R.id.capabilityContainer)
        CAP_GROUPS.forEach { group ->
            val opened = group.items.count { capSwitches[it.cap]?.isChecked == true }
            val key = "group:" + getString(group.titleRes)
            for (i in 0 until box.childCount) {
                val child = box.getChildAt(i)
                if (child.tag == key) {
                    child.findViewWithTag<TextView>("count")?.text =
                        "$opened/" + group.items.size
                }
            }
        }
    }

    /**
     * 「推荐配置」：只开低风险只读能力。
     *
     * 明确不包含：读取通知 / 回复通知 / 短信 / 通话记录 / 输入监听 / 按键监听 /
     * 读取他人应用数据 / 系统级操作。那些是「看你在干什么」或「代你说话」，
     * 必须在设置页单独、明确地打开，不能由引导页一键代劳。
     */
    private fun applyRecommendedCaps() {
        RECOMMENDED_CAPS.forEach { cap ->
            CapabilityGate.setEnabled(this, cap, true)
            capSwitches[cap]?.isChecked = true
        }
        refreshCapCounters()
        Toast.makeText(this, R.string.ob_wizard_cap_recommended, Toast.LENGTH_SHORT).show()
    }

    /** 一个能力域：标题 + 条目。 */
    private data class CapGroup(val titleRes: Int, val items: List<CapItem>)

    /** 一条能力：cap 键 + 标题资源 + 副标题资源（副标题复用设置页文案）。 */
    private data class CapItem(val cap: String, val titleRes: Int, val subRes: Int)

    // ================= 能力检测 =================

    private fun probeCapabilities() {
        val v = findViewById<TextView>(R.id.capRoot)
        v.text = getString(R.string.ob_cap_root_checking)
        v.setTextColor(getColor(R.color.text_secondary))
        Thread({
            val hasRoot = Privilege.probe(this).hasRoot
            runOnUiThread {
                v.text = getString(
                    if (hasRoot) R.string.ob_cap_root_present else R.string.ob_cap_root_absent,
                )
                v.setTextColor(if (hasRoot) getColor(R.color.success) else getColor(R.color.warning))
                updateRootAvailability()
            }
        }, "probe-root").apply { isDaemon = true }.start()
    }

    private fun updateRootAvailability() {
        val rootOk = Privilege.rootAvailableMinimal()
        if (!rootOk && selectedPriv == PrivMode.ROOT) selectedPriv = PrivMode.NORMAL
        refreshSelection()
    }

    private fun refreshShizukuStatus() {
        val v = findViewById<TextView>(R.id.capShizuku)
        when {
            Privilege.shizukuUsable() -> {
                v.text = getString(R.string.ob_cap_shizuku_present)
                v.setTextColor(getColor(R.color.success))
                v.setOnClickListener(null)
            }
            Privilege.shizukuServerRunning() -> {
                // 不在此自动请求：用户拒绝后回调会回到这里形成死循环（历史教训）。
                // 显式入口是「选中 Shizuku 卡片」→ maybeRequestShizuku()
                v.text = getString(R.string.ob_cap_shizuku_request)
                v.setTextColor(getColor(R.color.warning))
                v.isClickable = true
                v.setOnClickListener {
                    Privilege.requestShizukuPermission(SHIZUKU_REQ, this, explicit = true)
                }
            }
            else -> {
                v.text = getString(R.string.ob_cap_shizuku_absent)
                v.setTextColor(getColor(R.color.warning))
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
        stepsDrawer?.dismiss()
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
        if (stepsDrawer?.isShowing == true) {
            openStepsDrawer(false)
            return
        }
        if (page > 0) {
            goTo(page - 1)
            return
        }
        commit()
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
            showRuntimeStatus(getString(R.string.ob_rt_download), null)
            return
        }
        showRuntimeStatus(getString(R.string.ob_rt_preparing), 0)
        Thread({
            var failed = false
            runCatching {
                installer.ensureInstalled(
                    onProgress = { p ->
                        val pct = (p * 100f).toInt().coerceIn(0, 100)
                        if (pct == lastPrefetchPct) return@ensureInstalled
                        lastPrefetchPct = pct
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (pct < 100) showRuntimeStatus(getString(R.string.ob_rt_pct, pct), pct)
                        }
                    },
                )
            }.onFailure { failed = true }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (failed) {
                    showRuntimeStatus(getString(R.string.ob_rt_failed), null)
                } else {
                    lastPrefetchPct = 100
                    showRuntimeStatus(getString(R.string.ob_rt_ready), 100)
                }
            }
        }, "onboarding-runtime-prefetch").apply { isDaemon = true }.start()
    }

    private fun showRuntimeStatus(text: String, progress: Int?) {
        doneRuntimeStatus?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
        doneRuntimeBar?.apply {
            if (progress == null) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                this.progress = progress
            }
        }
    }

    private fun finishOnboarding() {
        if (selectedPriv == PrivMode.ROOT) {
            warnRoot { warnRootAgain { commit() } }
        } else {
            commit()
        }
    }

    private fun warnRoot(next: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ob_priv_root_warn_title))
            .setMessage(getString(R.string.ob_priv_root_warn))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ ->
                // 取消 = 以普通模式继续并进主界面；不落 Root，也不卡在引导页
                selectedPriv = PrivMode.NORMAL
                commit()
            }
            .show()
    }

    private fun warnRootAgain(next: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ob_priv_root_warn2_title))
            .setMessage(
                getString(R.string.ob_priv_root_warn2) + "\n\n" +
                    getString(R.string.ob_priv_root_paths_warn),
            )
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ ->
                selectedPriv = PrivMode.NORMAL
                commit()
            }
            .show()
    }

    private fun commit() {
        Privilege.setMode(this, selectedPriv)
        Privilege.markOnboarded(this)
        LocaleHelper.set(this, selectedLang)
        ThemePref.set(this, selectedTheme)
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
        setTextColor(getColor(R.color.text_muted))
        letterSpacing = 0.05f
        isAllCaps = true
        setPadding(0, 0, 0, dp(4))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val SHIZUKU_REQ = 4201
        const val WIDE_DP = 600
        const val KEY_PAGE = "ob_page"
        const val KEY_LANG = "ob_lang"
        const val KEY_ORIENTATION = "ob_orientation"
        const val KEY_THEME = "ob_theme"
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

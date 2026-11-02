package app.dsh.mobile

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege

/**
 * 首次启动引导（v1.5.x 重写为分步向导）。
 *
 * 六步：语言（默认中文）→ 显示方向 → 外观主题 → 配色 → 运行模式 → 完成页（特效 + 开始使用）。
 *
 * ## 为什么卡片用代码生成，而不是写死在 XML
 * 语言 5 种、配色 5 种，写进 XML 就是 10 段几乎相同的重复结构；而且切换语言后卡片
 * 文案要跟着变，XML 里改不了。代码生成则一处定义，自动跟随主题与配色。
 *
 * ## 保留下来的既有行为（不要退化）
 *  · Root 双警告：选 Root 后两次确认才落库；任一取消都回落普通并完成引导
 *    —— 绝不能因为取消而卡在引导页（历史 bug）；
 *  · 能力检测在后台线程：probeRoot 最坏试 3 种 su 语法 × 2s 超时，主线程会 ANR；
 *  · 返回键 = 以当前选择完成引导，绝不卡死；
 *  · 语言/主题切换需要 recreate，页码与已选项用 savedInstanceState 保住。
 */
class OnboardingActivity : ThemedActivity() {

    private var page = 0

    private var selectedLang = "zh"
    private var selectedOrient: OrientationMode = OrientationMode.PORTRAIT
    private var selectedTheme: ThemeMode = ThemeMode.SYSTEM
    private var selectedAccent: Accent = Accent.SKY
    private var selectedPriv: PrivMode = PrivMode.NORMAL

    private lateinit var pages: List<View>
    private lateinit var progress: ProgressBar
    private lateinit var stepHint: TextView
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button

    /** 语言码 → 母语写法的字符串资源（与设置页语言选择保持一致）。 */
    private val langCodes = listOf("zh", "en", "de", "pt", "th")
    private val langLabelRes = mapOf(
        "zh" to R.string.lang_zh,
        "en" to R.string.lang_en,
        "de" to R.string.lang_de,
        "pt" to R.string.lang_pt,
        "th" to R.string.lang_th,
    )

    private val langCards = mutableListOf<LinearLayout>()
    private val orientCards = mutableListOf<LinearLayout>()
    private val themeCards = mutableListOf<LinearLayout>()
    private val accentCards = mutableListOf<LinearLayout>()
    private val privCards = mutableListOf<LinearLayout>()

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
            ?: Accent.current(this)
        selectedPriv = savedInstanceState?.getString(KEY_PRIV)
            ?.let { runCatching { PrivMode.valueOf(it) }.getOrNull() } ?: PrivMode.NORMAL

        progress = findViewById(R.id.stepProgress)
        stepHint = findViewById(R.id.txtStepHint)
        btnBack = findViewById(R.id.btnBack)
        btnNext = findViewById(R.id.btnNext)
        pages = listOf(
            findViewById(R.id.pageLanguage),
            findViewById(R.id.pageOrientation),
            findViewById(R.id.pageTheme),
            findViewById(R.id.pageAccent),
            findViewById(R.id.pagePriv),
            findViewById(R.id.pageDone),
        )
        progress.max = pages.size

        buildLanguageCards()
        buildOrientationCards()
        buildThemeCards()
        buildAccentCards()
        buildPrivCards()

        btnBack.setOnClickListener { goTo(page - 1) }
        btnNext.setOnClickListener {
            if (page == pages.size - 1) finishOnboarding() else goTo(page + 1)
        }

        probeCapabilities()
        refreshShizukuStatus()
        updateRootAvailability()

        goTo(page)
    }

    // ================= 分页 =================

    private fun goTo(index: Int) {
        if (index !in pages.indices) return
        page = index
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        progress.progress = index + 1
        stepHint.text = getString(R.string.ob_wizard_step_fmt, index + 1, pages.size)

        // 首页不显示「上一步」；末页按钮变「开始使用」
        btnBack.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        btnNext.setText(
            if (index == pages.size - 1) R.string.ob_wizard_start else R.string.ob_wizard_next,
        )
        if (index == pages.size - 1) playDoneAnimation()
    }

    /** 完成页特效：光晕放大淡入 + logo 弹入，之后光晕缓慢呼吸。 */
    private fun playDoneAnimation() {
        val glow = findViewById<View>(R.id.doneGlow)
        val logo = findViewById<ImageView>(R.id.doneLogo)
        if (glow == null || logo == null) return

        // 光晕染成当前强调色，换配色也跟着变
        glow.background?.mutate()?.setTint(selectedAccent.argb)

        glow.animate().cancel()
        glow.alpha = 0f
        glow.scaleX = 0.6f
        glow.scaleY = 0.6f
        glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420L).start()

        logo.animate().cancel()
        logo.alpha = 0f
        logo.scaleX = 0.7f
        logo.scaleY = 0.7f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(120L).setDuration(420L).start()

        // 呼吸循环：让光晕"活着"，暗示可以开始了
        glow.postDelayed({
            if (page == pages.size - 1) {
                glow.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.75f)
                    .setDuration(1100L)
                    .withEndAction {
                        glow.animate().scaleX(0.95f).scaleY(0.95f).alpha(1f)
                            .setDuration(1100L).start()
                    }
                    .start()
            }
        }, 900L)
    }

    // ================= 卡片 =================

    /**
     * 通用可选卡片：色块（可选）+ 标题 + 副标题 + 右侧勾选。
     *
     * 整卡可点、带圆角涟漪（bg_ob_card_ripple）；选中时标题上强调色、卡片不透明，
     * 未选中降透明度 —— 沿用旧引导页的选中态语言。
     */
    private fun optionCard(
        container: LinearLayout,
        title: String,
        subtitle: String?,
        swatchColor: Int? = null,
        onClick: () -> Unit,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_ob_card_ripple)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        if (swatchColor != null) {
            card.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(swatchColor)
                }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                    marginEnd = dp(14)
                }
            })
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            tag = "title"
        })
        if (!subtitle.isNullOrBlank()) {
            textCol.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(3), 0, 0)
            })
        }
        card.addView(textCol)

        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })

        card.setOnClickListener { onClick() }
        container.addView(card)
        return card
    }

    /** 统一重算所有卡片的选中态（改任一项都调它）。 */
    private fun refreshSelection() {
        val accent = selectedAccent.argb
        val normal = getColor(R.color.text_primary)

        fun mark(card: LinearLayout, active: Boolean) {
            card.alpha = if (active) 1f else 0.62f
            // 标题在文本列里（tag=title）；文本列可能是第 1 或第 2 个子项（有/无色块）
            val textCol = (if (card.childCount >= 3) card.getChildAt(1) else card.getChildAt(0))
                as? LinearLayout
            (textCol?.findViewWithTag<TextView>("title"))?.setTextColor(
                if (active) accent else normal,
            )
            (card.getChildAt(card.childCount - 1) as? ImageView)?.visibility =
                if (active) View.VISIBLE else View.INVISIBLE
        }

        langCards.forEachIndexed { i, c -> mark(c, langCodes[i] == selectedLang) }
        orientCards.forEachIndexed { i, c -> mark(c, i == orientIndexOf(selectedOrient)) }
        themeCards.forEachIndexed { i, c -> mark(c, i == themeIndexOf(selectedTheme)) }
        accentCards.forEachIndexed { i, c -> mark(c, Accent.entries[i] == selectedAccent) }
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

    private fun privIndexOf(m: PrivMode) = when (m) {
        PrivMode.NORMAL -> 0
        PrivMode.SHIZUKU -> 1
        PrivMode.ROOT -> 2
    }

    private fun buildLanguageCards() {
        val box = findViewById<LinearLayout>(R.id.langContainer)
        langCards.clear()
        langCodes.forEach { code ->
            langCards.add(optionCard(box, getString(langLabelRes[code]!!), null) {
                if (selectedLang != code) {
                    // 切语言必须 recreate 才能让本页文案跟着变；页码与选项靠
                    // onSaveInstanceState 保住，用户不会被弹回第一步
                    LocaleHelper.set(this, code)
                    selectedLang = code
                    recreate()
                }
            })
        }
        refreshSelection()
    }

    private fun buildOrientationCards() {
        val box = findViewById<LinearLayout>(R.id.orientContainer)
        orientCards.clear()
        listOf(
            OrientationMode.PORTRAIT to R.string.setting_orient_portrait,
            OrientationMode.LANDSCAPE to R.string.setting_orient_landscape,
            OrientationMode.AUTO to R.string.setting_orient_auto,
        ).forEach { (mode, labelRes) ->
            orientCards.add(optionCard(box, getString(labelRes), null) {
                selectedOrient = mode
                refreshSelection()
            })
        }
        refreshSelection()
    }

    private fun buildThemeCards() {
        val box = findViewById<LinearLayout>(R.id.themeContainer)
        themeCards.clear()
        listOf(
            ThemeMode.DARK to R.string.theme_dark,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.SYSTEM to R.string.theme_system,
        ).forEach { (mode, labelRes) ->
            themeCards.add(optionCard(box, getString(labelRes), null) {
                selectedTheme = mode
                // 立即落库并重建，让用户在引导页当场看到主题效果
                ThemePref.set(this, mode)
                recreate()
            })
        }
        refreshSelection()
    }

    private fun buildAccentCards() {
        val box = findViewById<LinearLayout>(R.id.accentContainer)
        accentCards.clear()
        Accent.entries.forEach { acc ->
            accentCards.add(
                optionCard(box, getString(acc.labelRes), null, swatchColor = acc.argb) {
                    selectedAccent = acc
                    Accent.set(this, acc)
                    refreshSelection()
                },
            )
        }
        refreshSelection()
    }

    private fun buildPrivCards() {
        val box = findViewById<LinearLayout>(R.id.privContainer)
        privCards.clear()
        listOf(
            Triple(PrivMode.NORMAL, R.string.ob_priv_normal, R.string.ob_priv_normal_desc),
            Triple(PrivMode.SHIZUKU, R.string.ob_priv_shizuku, R.string.ob_priv_shizuku_desc),
            Triple(PrivMode.ROOT, R.string.ob_priv_root, R.string.ob_priv_root_desc),
        ).forEach { (mode, titleRes, descRes) ->
            privCards.add(
                optionCard(box, getString(titleRes), getString(descRes)) {
                    if (mode == PrivMode.ROOT && !Privilege.rootAvailableMinimal()) {
                        // 无 su 时不接受选择，直接说明原因（比置灰更好懂）
                        Toast.makeText(
                            this, R.string.ob_priv_root_gray_hint, Toast.LENGTH_SHORT,
                        ).show()
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
        findViewById<TextView>(R.id.rootHint).text = getString(
            if (rootOk) R.string.ob_priv_root_desc else R.string.ob_priv_root_gray_hint,
        )
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
        outState.putString(KEY_ACCENT, selectedAccent.id)
        outState.putString(KEY_PRIV, selectedPriv.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    /** 返回键：以当前选择完成引导，绝不卡死（沿用旧行为）。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        commit()
    }

    private fun finishOnboarding() {
        if (selectedPriv == PrivMode.ROOT) {
            warnRoot { warnRootAgain { commit() } }
        } else {
            commit()
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
                selectedPriv = PrivMode.NORMAL
                commit()
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
                selectedPriv = PrivMode.NORMAL
                commit()
            }
            .create()
            .apply {
                setOnShowListener {
                    window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
                }
            }
            .show()
    }

    private fun commit() {
        Privilege.setMode(this, selectedPriv)
        Privilege.markOnboarded(this)
        LocaleHelper.set(this, selectedLang)
        ThemePref.set(this, selectedTheme)
        Accent.set(this, selectedAccent)
        // ⚠️ 不能设 requestedOrientation：本 Activity 紧接着 finish()，
        // 方向请求会落在正在销毁的窗口上被丢弃（历史 bug）。
        OrientationPrefs.set(this, selectedOrient)

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val SHIZUKU_REQ = 4201
        const val KEY_PAGE = "ob_page"
        const val KEY_LANG = "ob_lang"
        const val KEY_ORIENTATION = "ob_orientation"
        const val KEY_THEME = "ob_theme"
        const val KEY_ACCENT = "ob_accent"
        const val KEY_PRIV = "ob_priv"
    }
}

package app.dsh.mobile

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege

/**
 * 首次启动引导（一次性，过后不再弹）。
 *
 * 用户在此初始化三件事：
 *  1. 界面语言（英语/德语/中文，v1.2.120 起三选一，默认英语）
 *  2. 默认显示方向（竖屏/横屏/自动，v1.2.120 起三态）—— 带简易演示图；横屏即绑定桌面布局
 *  3. 运行权限模式（普通/Shizuku/Root）—— 决定 AI 能力边界。
 *
 * Root 模式保护壳：选择时弹两次高危警告，确认后才写入；启动时（EngineSupervisor
 * 进入 Root 分支前）会先对 dsh-home 做 tar 备份可回滚。能力检测仅 stat/probe，
 * 不做任何危险动作。
 */
class OnboardingActivity : ThemedActivity() {

    private var selectedOrientation: OrientationMode = OrientationMode.PORTRAIT
    private var selectedPriv = PrivMode.NORMAL
    private var selectedLang = LocaleHelper.DEFAULT
    private var selectedTheme: ThemeMode = ThemeMode.DARK

    /** Shizuku 授权结果监听（requestPermission 异步回调） */
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ) {
                Privilege.markShizukuDenied(this, grantResult != android.content.pm.PackageManager.PERMISSION_GRANTED)
                refreshShizukuStatus()
            }
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        // v1.4.18：平板默认选中「自动」，手机仍默认竖屏（与 OrientationPrefs 同一默认值）
        selectedOrientation = savedInstanceState?.getString(KEY_ORIENTATION)
            ?.let { runCatching { OrientationMode.valueOf(it) }.getOrNull() }
            ?: OrientationPrefs.defaultFor(this)
        selectedPriv = savedInstanceState?.getString(KEY_PRIV)
            ?.let { runCatching { PrivMode.valueOf(it) }.getOrNull() }
            ?: PrivMode.NORMAL
        // 默认选中：普通 + 语言卡片高亮（默认英语）
        selectedLang = LocaleHelper.get(this)
        // 外观主题：优先取本次重建前保存的选择，其次读已落库偏好（默认深色）
        selectedTheme = savedInstanceState?.getString(KEY_THEME)?.let { ThemeMode.fromKey(it) }
            ?: ThemePref.get(this)
        updateSelectionUi()

        // 语言卡片组（v1.2.120：en/de/zh 三选一）：点击保存偏好并 recreate，立即切换引导页语言
        val langCards = mapOf(
            findViewById<LinearLayout>(R.id.cardLangEn) to "en",
            findViewById<LinearLayout>(R.id.cardLangDe) to "de",
            findViewById<LinearLayout>(R.id.cardLangZh) to "zh",
            findViewById<LinearLayout>(R.id.cardLangPt) to "pt",
        )
        for ((card, code) in langCards) {
            card.setOnClickListener {
                if (selectedLang != code) {
                    LocaleHelper.set(this, code)
                    selectedLang = code
                    recreate()
                }
            }
        }

        // 外观主题三卡（v1.4.24 修复：此前卡片仅存在于布局，点击无任何效果）：
        // 写偏好后 recreate，本页立即按新主题重绘；方向/权限选择由 onSaveInstanceState 保留。
        for ((cardId, mode) in listOf(
            R.id.cardThemeDark to ThemeMode.DARK,
            R.id.cardThemeLight to ThemeMode.LIGHT,
            R.id.cardThemeSystem to ThemeMode.SYSTEM,
        )) {
            findViewById<LinearLayout>(cardId).setOnClickListener {
                if (selectedTheme != mode) {
                    ThemePref.set(this, mode)
                    selectedTheme = mode
                    recreate()
                }
            }
        }

        findViewById<LinearLayout>(R.id.cardPortrait).setOnClickListener {
            selectedOrientation = OrientationMode.PORTRAIT
            updateSelectionUi()
        }
        findViewById<LinearLayout>(R.id.cardLandscape).setOnClickListener {
            selectedOrientation = OrientationMode.LANDSCAPE
            updateSelectionUi()
        }
        findViewById<LinearLayout>(R.id.cardAuto).setOnClickListener {
            selectedOrientation = OrientationMode.AUTO
            updateSelectionUi()
        }

        val privGroup = findViewById<RadioGroup>(R.id.privGroup)
        privGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedPriv = when (checkedId) {
                R.id.privRoot -> PrivMode.ROOT
                R.id.privShizuku -> PrivMode.SHIZUKU
                else -> PrivMode.NORMAL
            }
            if (selectedPriv == PrivMode.SHIZUKU) maybeRequestShizuku()
        }
        privGroup.check(
            when (selectedPriv) {
                PrivMode.ROOT -> R.id.privRoot
                PrivMode.SHIZUKU -> R.id.privShizuku
                else -> R.id.privNormal
            },
        )

        // 能力检测（Root 用 su 实测；Shizuku 用官方 binder ping + 授权态）
        //
        // ⚠️ v1.2.100：**移到后台线程**。probeRoot 最坏要依次试 3 种 su 语法、
        // 每种 2s 超时（首次还会弹 Magisk 授权框），放主线程最坏阻塞 6s+ → ANR 风险。
        // 先显示「检测中」，结果回填 UI。
        findViewById<TextView>(R.id.capRoot).apply {
            text = getString(R.string.ob_cap_root_checking)
            setTextColor(0xFF8A94A3.toInt())
        }
        Thread({
            val hasRoot = Privilege.probe(this).hasRoot
            runOnUiThread {
                findViewById<TextView>(R.id.capRoot).apply {
                    text = getString(
                        if (hasRoot) R.string.ob_cap_root_present else R.string.ob_cap_root_absent
                    )
                    setTextColor(if (hasRoot) 0xFF6EE7B7.toInt() else 0xFFFFB74D.toInt())
                }
            }
        }, "probe-root").apply { isDaemon = true; start() }
        refreshShizukuStatus()

        // m1.30：未检测到 su 时，Root 选项置灰不可选 + 下方提示
        updateRootAvailability()

        findViewById<Button>(R.id.btnStart).setOnClickListener { finishOnboarding() }
    }

    /** Root 无 su 可用时：置灰 RadioButton 禁用 + 显示「未检测到 su」提示 */
    private fun updateRootAvailability() {
        val rootOk = Privilege.rootAvailableMinimal()
        val rb = findViewById<RadioButton>(R.id.privRoot)
        rb.isEnabled = rootOk
        rb.alpha = if (rootOk) 1f else 0.35f
        findViewById<TextView>(R.id.rootHint).text = getString(
            if (rootOk) R.string.ob_priv_root_desc else R.string.ob_priv_root_gray_hint
        )
        // 若当前选中了 Root 但 su 不可用，回落到普通
        if (!rootOk && selectedPriv == PrivMode.ROOT) {
            selectedPriv = PrivMode.NORMAL
            findViewById<RadioGroup>(R.id.privGroup).check(R.id.privNormal)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_ORIENTATION, selectedOrientation.name)
        outState.putString(KEY_PRIV, selectedPriv.name)
        outState.putString(KEY_THEME, selectedTheme.key)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    /** 返回键：以普通模式完成引导进入主界面，绝不卡死 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        commit()
    }

    /**
     * Shizuku 状态刷新 + 必要时主动请求授权。
     * 三态：已授权 → 绿色提示；server 在跑未授权 → 主动 requestPermission 弹框；
     * server 未跑 → 黄色提示用户先启动 Shizuku（弹框无从谈起）。
     */
    private fun refreshShizukuStatus() {
        val v = findViewById<TextView>(R.id.capShizuku)
        when {
            Privilege.shizukuUsable() -> {
                v.text = getString(R.string.ob_cap_shizuku_present)
                v.setTextColor(0xFF6EE7B7.toInt())
            }
            Privilege.shizukuServerRunning() -> {
                // v1.2.158 隐私修复：不再在此自动请求（拒绝后回调会回到这里 → 死循环）。
                // 引导页的显式入口是"选中 Shizuku 单选项"→ maybeRequestShizuku()。
                v.text = getString(R.string.ob_cap_shizuku_request)
                v.setTextColor(0xFFFFB74D.toInt())
                v.isClickable = true
                v.setOnClickListener {
                    Privilege.requestShizukuPermission(SHIZUKU_REQ, this@OnboardingActivity, explicit = true)
                }
            }
            else -> {
                v.text = getString(R.string.ob_cap_shizuku_absent)
                v.setTextColor(0xFFFFB74D.toInt())
            }
        }
    }

    /** 用户切到 Shizuku 单选时再给一次授权机会（server 后来启动的情形） */
    private fun maybeRequestShizuku() {
        if (Privilege.shizukuServerRunning() && !Privilege.shizukuGranted()) {
            Privilege.requestShizukuPermission(SHIZUKU_REQ, this@OnboardingActivity, explicit = true)
        }
    }

    private companion object {
        const val SHIZUKU_REQ = 4201
        const val KEY_ORIENTATION = "ob_orientation"
        const val KEY_PRIV = "ob_priv"
        const val KEY_THEME = "ob_theme"
    }

    /**
     * 卡片选中态（v1.2.120）：
     *  - 方向三卡（竖屏/横屏/自动）：选中卡文字高亮 + 不透明，未选中变暗；
     *  - 语言三卡（en/de/zh）：同样高亮当前语言（此前无语言选中态，
     *    默认英语时用户看不出当前选的是哪个）；
     *  - 主题三卡（v1.4.24）：高亮当前主题，选中即写偏好并重建本页。
     */
    private fun updateSelectionUi() {
        val highlight = app.dsh.mobile.Accent.current(applicationContext).argb
        // 未选中态用主题的次要文字色：深色 #9CA3AF、浅色 #475569，两套都有足够对比度
        val normal = getColor(R.color.text_secondary)

        // —— 方向：竖屏/横屏/自动 ——
        val orientCards = listOf(
            Triple(
                findViewById<LinearLayout>(R.id.cardPortrait),
                findViewById<TextView>(R.id.txtPortrait),
                selectedOrientation == OrientationMode.PORTRAIT,
            ),
            Triple(
                findViewById<LinearLayout>(R.id.cardLandscape),
                findViewById<TextView>(R.id.txtLandscape),
                selectedOrientation == OrientationMode.LANDSCAPE,
            ),
            Triple(
                findViewById<LinearLayout>(R.id.cardAuto),
                findViewById<TextView>(R.id.txtAuto),
                selectedOrientation == OrientationMode.AUTO,
            ),
        )
        for ((card, txt, active) in orientCards) {
            txt.setTextColor(if (active) highlight else normal)
            card.alpha = if (active) 1f else 0.6f
        }

        // —— 语言：en/de/zh ——
        for ((cardId, code) in listOf(
                R.id.cardLangEn to "en",
                R.id.cardLangDe to "de",
                R.id.cardLangZh to "zh",
                R.id.cardLangPt to "pt",
            )) {
            val card = findViewById<LinearLayout>(cardId)
            val txt = card.getChildAt(0) as TextView
            val active = selectedLang == code
            txt.setTextColor(if (active) highlight else normal)
            card.alpha = if (active) 1f else 0.6f
        }

        // —— 主题：深色/浅色/跟随系统 ——
        for ((cardId, txtId, mode) in listOf(
                Triple(R.id.cardThemeDark, R.id.txtThemeDark, ThemeMode.DARK),
                Triple(R.id.cardThemeLight, R.id.txtThemeLight, ThemeMode.LIGHT),
                Triple(R.id.cardThemeSystem, R.id.txtThemeSystem, ThemeMode.SYSTEM),
            )) {
            val active = selectedTheme == mode
            findViewById<TextView>(txtId).setTextColor(if (active) highlight else normal)
            findViewById<LinearLayout>(cardId).alpha = if (active) 1f else 0.6f
        }
    }

    /** 用户点「开始使用」：Root 需双警告，其余直接落库并进主界面。
     *  关键：即使 Root 双警告被取消，也照常 markOnboarded 并进主界面——
     *  避免用户陷入「取消→永远停在引导」的死循环（此前每次进应用都弹的根因）。 */
    private fun finishOnboarding() {
        if (selectedPriv == PrivMode.ROOT) {
            warnRoot {
                warnRootAgain {
                    commit()
                }
            }
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
                // 取消 = 以普通模式继续并进入主界面（不落 Root），同样完成引导不卡死
                selectedPriv = PrivMode.NORMAL
                commit()
            }
            .show()
    }

    private fun warnRootAgain(next: () -> Unit) {
        // 使用完整对话框 + 圆角样式
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ob_priv_root_warn2_title))
            // v1.4.9（用户建议）：第二条确认里把「不要动应用自己的目录」说清楚。
            // Root 引擎以 uid 0 运行，用户/AI 在应用私有目录或工作区里手改文件时，
            // 写出的属主是 root、权限位可能是 600 —— App 自己（普通 uid）从此读不了，
            // 表现为「插件加载失败 / 配置读不到 / 引擎起不来」，只能清数据或重装。
            // 这类损坏我们见过太多次，值得在开启前就说明白。
            .setMessage(getString(R.string.ob_priv_root_warn2) + "\n\n" + getString(R.string.ob_priv_root_paths_warn))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ ->
                // 第二次取消也回落普通并完成引导
                selectedPriv = PrivMode.NORMAL
                commit()
            }
        builder.create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
        }.show()
    }

    private fun commit() {
        // 落库权限模式
        Privilege.setMode(this, selectedPriv)
        Privilege.markOnboarded(this)

        // 落库语言偏好（进主界面后按新语言显示）
        LocaleHelper.set(this, selectedLang)

        // 落库外观主题（点击时已写入；这里兜一次底，保证「没点过」也留下明确偏好）
        ThemePref.set(this, selectedTheme)

        // 落库横竖屏偏好（v1.2.120：三态 portrait/landscape/auto）。朝向由 MainActivity
        // 在 onCreate 里 applyOrientation() 统一应用。
        // ⚠️ 这里【不能】设 requestedOrientation：本 Activity 紧接着 finish()，
        // 方向请求是异步派发给窗口的，会落在一个正在销毁的窗口上而被丢弃；
        // 而且旧实现只处理了横屏分支，选「竖屏」在这里根本不产生任何效果。
        OrientationPrefs.set(this, selectedOrientation)

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

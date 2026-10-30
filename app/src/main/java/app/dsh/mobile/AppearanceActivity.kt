package app.dsh.mobile

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 外观与显示（v1.4.56）：从设置主页收拢的个性化子页。
 *
 * 七项：主题、屏幕朝向、页面缩放、沉浸式、文件目录、强调色、设置页布局。
 * 改动后本页 recreate 立即生效；设置主页在 onResume 用 applied* 快照比对，
 * 检测到主题/强调色/布局变化会自行重建。
 */
class AppearanceActivity : ThemedActivity() {

    private var orientMode: OrientationMode = OrientationMode.PORTRAIT
    private var pageScale = DEFAULT_PAGE_SCALE

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.forcePortrait(this)
        setContentView(R.layout.activity_appearance)

        orientMode = OrientationPrefs.get(this)
        pageScale = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // —— 屏幕朝向（v1.2.120：竖屏/横屏/自动 三选一）——
        findViewById<LinearLayout>(R.id.rowLandscape).setOnClickListener {
            showOrientDialog()
        }
        findViewById<TextView>(R.id.valLandscape).text = getString(orientLabel(orientMode))

        // —— 页面缩放 ——
        findViewById<LinearLayout>(R.id.rowScale).setOnClickListener {
            showScaleDialog()
        }
        findViewById<TextView>(R.id.valScale).text = "$pageScale%"

        // —— 沉浸式状态栏（横竖屏都隐藏，可自行开关）——
        val swImmersive = findViewById<Switch>(R.id.swImmersive)
        swImmersive.isChecked = MainActivity.immersiveEnabled(this)
        swImmersive.setOnCheckedChangeListener { _, checked ->
            MainActivity.setImmersiveEnabled(this, checked)
        }

        // —— 官方推荐手机 UI（v1.4.67）：两套竖屏布局插件二选一 ——
        // 切换需要重写 android-overlay.yml 并重启引擎才生效（插件在启动时加载）。
        val swOfficialUi = findViewById<Switch>(R.id.swOfficialUi)
        swOfficialUi.isChecked = MainActivity.officialMobileUiEnabled(this)
        swOfficialUi.setOnCheckedChangeListener { _, checked ->
            MainActivity.setOfficialMobileUiEnabled(this, checked)
            // 立刻重写 overlay，让「重启引擎」时用的是新方案
            runCatching { app.dsh.mobile.engine.EngineConfig.ensureAndroidOverlay(this) }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.setting_official_ui)
                .setMessage(R.string.setting_official_ui_restart_hint)
                .setPositiveButton(R.string.action_restart_now) { _, _ ->
                    (application as app.dsh.mobile.DshApp).supervisor.restart()
                    Toast.makeText(this, getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // —— 文件保存目录（v1.4.26，DSHA 风格 Download/DSH MOBILE）——
        val filesDir = app.dsh.mobile.engine.EngineConfig.workspaces(this)
        findViewById<TextView>(R.id.valFilesDir)?.text = filesDir.absolutePath
        findViewById<LinearLayout>(R.id.rowFilesDir)?.setOnClickListener {
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("files dir", filesDir.absolutePath),
                )
            }
            Toast.makeText(this, R.string.files_dir_copied, Toast.LENGTH_SHORT).show()
        }

        // —— 外观主题（深色/浅色/跟随系统）—— 与引导页写同一份偏好（ThemePref）
        findViewById<LinearLayout>(R.id.rowTheme)?.setOnClickListener {
            showThemeDialog()
        }
        findViewById<TextView>(R.id.valTheme)?.text = getString(ThemePref.get(this).labelRes)

        // —— 配色方案（v1.4.23）——
        findViewById<LinearLayout>(R.id.rowAccent)?.setOnClickListener {
            showAccentDialog()
        }

        // —— 设置页布局（v1.4.23）——
        findViewById<LinearLayout>(R.id.rowLayoutMode)?.setOnClickListener {
            showLayoutModeDialog()
        }

        findViewById<TextView>(R.id.valLayoutMode)?.text =
            getString(SettingsLayoutMode.current(this).labelRes)
        findViewById<TextView>(R.id.valAccent)?.text = getString(Accent.current(this).labelRes)
        findViewById<TextView>(R.id.valLayoutMode)?.deprecatedAccent()
        findViewById<TextView>(R.id.valAccent)?.deprecatedAccent()
    }

    private fun orientLabel(mode: OrientationMode): Int = when (mode) {
        OrientationMode.PORTRAIT -> R.string.setting_orient_portrait
        OrientationMode.LANDSCAPE -> R.string.setting_orient_landscape
        OrientationMode.AUTO -> R.string.setting_orient_auto
    }

    /**
     * 屏幕朝向选择：竖屏 / 横屏 / 自动 单选列表。
     * 落库后由 MainActivity.onResume 检测并统一应用。
     */
    private fun showOrientDialog() {
        val modes = listOf(
            OrientationMode.PORTRAIT,
            OrientationMode.LANDSCAPE,
            OrientationMode.AUTO,
        )
        val labels = modes.map { getString(orientLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_landscape))
            .setSingleChoiceItems(labels, modes.indexOf(orientMode)) { dlg, which ->
                val picked = modes[which]
                dlg.dismiss()
                if (picked == orientMode) return@setSingleChoiceItems
                orientMode = picked
                OrientationPrefs.set(this, picked)
                // v1.4.64：设置页本身**固定竖屏**（不再跟随横屏偏好），
                // 所以这里不能调 applyTo —— 那会让整个设置页横过来。
                // 偏好在返回主界面时生效（MainActivity 按偏好应用）。
                OrientationPrefs.forcePortrait(this)
                findViewById<TextView>(R.id.valLandscape).text = getString(orientLabel(picked))
                Toast.makeText(
                    this,
                    getString(orientLabel(picked)) + " · " + getString(R.string.setting_applied_now),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .showStyled()
    }

    /**
     * 页面缩放对话框：SeekBar 滑条连续拖动（范围 50–150，步长 5），实时显示百分比。
     * 落库后 MainActivity onResume 读取并 reload 生效。
     */
    private fun showScaleDialog() {
        val value = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            text = "$pageScale%"
            setPadding(0, dp(8), 0, dp(4))
        }
        val bar = SeekBar(this).apply {
            max = (MAX_PAGE_SCALE - MIN_PAGE_SCALE) / SCALE_STEP   // 索引 0..20 → 50..150 步长5
            progress = (pageScale - MIN_PAGE_SCALE) / SCALE_STEP
            progressTintList = android.content.res.ColorStateList.valueOf(Accent.current(this@AppearanceActivity).argb)
            thumbTintList = android.content.res.ColorStateList.valueOf(Accent.current(this@AppearanceActivity).argb)
            setPadding(dp(24), 0, dp(24), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "${MIN_PAGE_SCALE + progress * SCALE_STEP}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(value)
            addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.scale_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pageScale = MIN_PAGE_SCALE + bar.progress * SCALE_STEP
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit().putInt(KEY_PAGE_SCALE, pageScale).apply()
                findViewById<TextView>(R.id.valScale).text = "$pageScale%"
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showStyled()
    }

    /** 外观主题：深色 / 浅色 / 跟随系统。选完 recreate 才会重新取 values(-night) 资源。 */
    private fun showThemeDialog() {
        val modes = ThemeMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(ThemePref.get(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_theme)
            .setSingleChoiceItems(labels, checked) { d, which ->
                ThemePref.set(this, modes[which])
                d.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 配色方案选择：5 种强调色，默认项目原本的天蓝 */
    private fun showAccentDialog() {
        val modes = Accent.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(Accent.current(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_accent)
            .setSingleChoiceItems(labels, checked) { d, which ->
                Accent.set(this, modes[which])
                d.dismiss()
                // 配色影响整个原生壳（含本页所有强调色），重建本页最省事且无状态丢失风险
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 设置页布局选择：LIST（单列）/ CARD（分区加大间距）/ GRID（两列图标网格）。
     * 落库后设置主页在 onResume 检测到变化会重建并按新模式重排。
     */
    private fun showLayoutModeDialog() {
        val modes = SettingsLayoutMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(SettingsLayoutMode.current(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_layout_mode)
            .setSingleChoiceItems(labels, checked) { d, which ->
                SettingsLayoutMode.set(this, modes[which])
                d.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** dp → px 小工具（对话框内边距用） */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 给设置项的当前值文字上强调色（v1.4.23，配色可切换后动态取色） */
    private fun TextView.deprecatedAccent() {
        setTextColor(Accent.current(this.context).argb)
    }

    /** 统一弹窗样式：覆盖自绘圆角背景 */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
        }

    private companion object {
        const val PREFS_UI = DshApp.PREFS_UI
        const val KEY_PAGE_SCALE = "page_scale"
        const val DEFAULT_PAGE_SCALE = 90
        const val MIN_PAGE_SCALE = 50
        const val MAX_PAGE_SCALE = 150
        const val SCALE_STEP = 5
    }
}

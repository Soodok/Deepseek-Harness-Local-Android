package app.dsh.mobile

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import com.google.android.material.materialswitch.MaterialSwitch
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
    /** 属于设置体系：启用「设置页背景图」（见 ThemedActivity.usesSettingsBackground）。 */
    override val usesSettingsBackground: Boolean get() = true


    private var orientMode: OrientationMode = OrientationMode.PORTRAIT
    private var pageScale = DEFAULT_PAGE_SCALE

    /**
     * 本页上次「已生效」的主题 / 强调色 / 布局快照（v1.7.9）。
     *
     * 之前本页**完全没有快照比对**：从「主题」子页改完强调色返回，
     * 既不会重建也不刷新文字 —— 第 2 行「主题」的当前值仍显示「天蓝（默认）」，
     * 而子页里明明是靛蓝。这是用户看到「切换没效果」最直接的一处。
     */
    private var appliedTheme: ThemeMode = ThemeMode.DARK
    private var appliedAccent: Int = 0
    private var appliedLayout: SettingsLayoutMode = SettingsLayoutMode.LIST

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_appearance)

        orientMode = OrientationPrefs.get(this)
        pageScale = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        appliedTheme = ThemePref.get(this)
        appliedAccent = Accent.current(this).argb
        appliedLayout = SettingsLayoutMode.current(this)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        // 桌面宠物（v1.5.8 从「语音与插件」挪到外观与显示）：独立设置页管权限/包/开关。
        runCatching {
            findViewById<android.view.View>(R.id.rowPet).setOnClickListener {
                startActivity(android.content.Intent(this, app.dsh.mobile.pet.PetSettingsActivity::class.java))
            }
        }


        // —— 屏幕朝向（v1.2.120：竖屏/横屏/自动 三选一）——
        findViewById<LinearLayout>(R.id.rowLandscape).setOnClickListener {
            showOrientDialog()
        }
        findViewById<TextView>(R.id.valLandscape).text = getString(orientLabel(orientMode))

        // —— 设置页跟随屏幕方向（v1.5.x）——
        // 默认关：手机上的设置页固定竖屏（v1.4.64 的诉求），平板跟随上面的朝向选择。
        // 打开后：任何尺寸的设置页都跟随朝向偏好（等同主界面）。
        val swSettingsFollow = findViewById<MaterialSwitch>(R.id.swSettingsFollow)
        swSettingsFollow.isChecked = OrientationPrefs.followInSettings(this)
        swSettingsFollow.setOnCheckedChangeListener { _, checked ->
            OrientationPrefs.setFollowInSettings(this, checked)
            // 立即生效：本页当场跟着转（关掉时手机立刻转回竖屏），
            // 其余设置页在各自 onCreate / onResume 读同一份偏好。
            OrientationPrefs.applyToSettingsPage(this)
        }

        // —— 页面缩放 ——
        findViewById<LinearLayout>(R.id.rowScale).setOnClickListener {
            showScaleDialog()
        }
        findViewById<TextView>(R.id.valScale).text = "$pageScale%"

        // —— 沉浸式状态栏（横竖屏都隐藏，可自行开关）——
        val swImmersive = findViewById<MaterialSwitch>(R.id.swImmersive)
        swImmersive.isChecked = MainActivity.immersiveEnabled(this)
        swImmersive.setOnCheckedChangeListener { _, checked ->
            MainActivity.setImmersiveEnabled(this, checked)
        }

        // —— 官方推荐手机 UI（v1.4.67）：两套竖屏布局插件二选一 ——
        // 切换需要重写 android-overlay.yml 并重启引擎才生效（插件在启动时加载）。
        val swOfficialUi = findViewById<MaterialSwitch>(R.id.swOfficialUi)
        swOfficialUi.isChecked = MainActivity.officialMobileUiEnabled(this)
        swOfficialUi.setOnCheckedChangeListener { _, checked ->
            MainActivity.setOfficialMobileUiEnabled(this, checked)
            // v1.5.0：mobile-layout 已改为用户态插件（profile bundle），overlay 里
            // 不再有它的 insert 行 —— 这个开关必须同时把插件挂上/摘下 bundles，
            // 否则拨开也只会得到「三件套关掉、什么布局都没上」的半残状态。
            // 引擎在跑，package.json 不能当场改，只记意图，下次启动落地。
            app.dsh.mobile.engine.ProfilePluginProvisioner.requestEnabled(
                this,
                app.dsh.mobile.engine.ProfilePluginProvisioner.MOBILE_LAYOUT_PKG,
                checked,
            )
            // 立刻重写 overlay，让「重启引擎」时用的是新方案
            runCatching { app.dsh.mobile.engine.EngineConfig.ensureAndroidOverlay(this) }
            md3Dialog()
                .setTitle(R.string.setting_official_ui)
                .setMessage(R.string.setting_official_ui_restart_hint)
                .setPositiveButton(R.string.action_restart_now) { _, _ ->
                    (application as app.dsh.mobile.DshApp).supervisor.restart()
                    Toast.makeText(this, getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // —— 外观主题（深色/浅色/跟随系统）—— 与引导页写同一份偏好（ThemePref）
        findViewById<LinearLayout>(R.id.rowTheme)?.setOnClickListener {
            showThemeDialog()
        }

        // —— 主题（v1.5.x）：预设 / 自定义 / 动态取色都在独立页面里 ——
        findViewById<LinearLayout>(R.id.rowAccent)?.setOnClickListener {
            startActivity(Intent(this, ThemeActivity::class.java))
        }

        // —— 设置页布局（v1.4.23）——
        findViewById<LinearLayout>(R.id.rowLayoutMode)?.setOnClickListener {
            showLayoutModeDialog()
        }

        // —— 自定义素材（v1.5.x）：设置页背景图 / 收起态圆点图 ——
        findViewById<LinearLayout>(R.id.rowSettingsBg)?.setOnClickListener {
            // 选图 / 不透明度 / 模糊都在子页里
            startActivity(Intent(this, SettingsBgActivity::class.java))
        }

        refreshValueRows()
    }

    /** 回到本页时重新对齐朝向：外观页可能刚改过开关/偏好。 */
    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
        // 从「主题」子页返回：主题/强调色/布局任一变了就重建，让 ?attr/colorPrimary
        // 与 @color/accent 重新解析（v1.7.9，原先这里没有任何比对）。
        if (appliedTheme != ThemePref.get(this) ||
            appliedAccent != Accent.current(this).argb ||
            appliedLayout != SettingsLayoutMode.current(this)
        ) {
            recreate()
            return
        }
        // 值没变也刷一次：语言切换、或别的入口改了同一份偏好时文案要对得上。
        refreshValueRows()
        // 选图是异步回填的（系统相册在另一个 Activity），回来时刷新"已设置/未设置"
        refreshImageRows()
    }

    /**
     * 刷新本页四处「当前值」文字（v1.7.9）。
     *
     * 原先这些文字只在 onCreate 赋一次值，从子页返回时不再执行 —— 于是偏好改了、
     * 界面还写着旧值（用户反馈「切换没效果」的直观来源）。抽成一个方法，
     * 让 onCreate 与 onResume 共用同一份取值来源，不会再走岔。
     */
    private fun refreshValueRows() {
        findViewById<TextView>(R.id.valTheme)?.text = getString(ThemePref.get(this).labelRes)
        findViewById<TextView>(R.id.valAccent)?.text = getString(Accent.current(this).labelRes)
        findViewById<TextView>(R.id.valLayoutMode)?.text =
            getString(SettingsLayoutMode.current(this).labelRes)
        findViewById<TextView>(R.id.valLayoutMode)?.deprecatedAccent()
        findViewById<TextView>(R.id.valAccent)?.deprecatedAccent()
    }

    /**
     * 刷新素材行的状态文字与缩略图预览。
     *
     * 预览直接读已落盘的私有文件（导入时就已缩到上限尺寸），不需要再解码原图。
     */
    private fun refreshImageRows() {
        val none = getString(R.string.setting_image_none)
        val yes = getString(R.string.setting_image_set)

        val bgFile = CustomVisualPref.settingsBgFile(this)
        findViewById<TextView>(R.id.valSettingsBg)?.text = if (bgFile != null) yes else none
        val bgPreview = findViewById<ImageView>(R.id.imgSettingsBgPreview)
        if (bgFile != null && bgPreview != null) {
            val bmp = runCatching {
                android.graphics.BitmapFactory.decodeFile(bgFile.absolutePath)
            }.getOrNull()
            if (bmp != null) {
                bgPreview.setImageBitmap(bmp)
                bgPreview.visibility = View.VISIBLE
            } else {
                bgPreview.visibility = View.GONE
            }
        } else {
            bgPreview?.visibility = View.GONE
        }
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
        md3Dialog()
            .setTitle(getString(R.string.setting_landscape))
            .setSingleChoiceItems(labels, modes.indexOf(orientMode)) { dlg, which ->
                val picked = modes[which]
                dlg.dismiss()
                if (picked == orientMode) return@setSingleChoiceItems
                orientMode = picked
                OrientationPrefs.set(this, picked)
                // v1.5.x：大屏（或用户打开了「设置页跟随屏幕方向」）时本页也跟着转，
                // 手机则保持 v1.4.64 的竖屏。判定与所有设置页共用同一个入口。
                OrientationPrefs.applyToSettingsPage(this)
                findViewById<TextView>(R.id.valLandscape).text = getString(orientLabel(picked))
                Toast.makeText(
                    this,
                    getString(orientLabel(picked)) + " · " + getString(R.string.setting_applied_now),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .showMd3()
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
        md3Dialog()
            .setTitle(getString(R.string.scale_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pageScale = MIN_PAGE_SCALE + bar.progress * SCALE_STEP
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit().putInt(KEY_PAGE_SCALE, pageScale).apply()
                findViewById<TextView>(R.id.valScale).text = "$pageScale%"
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showMd3()
    }

    /** 外观主题：深色 / 浅色 / 跟随系统。选完 recreate 才会重新取 values(-night) 资源。 */
    private fun showThemeDialog() {
        val modes = ThemeMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(ThemePref.get(this))
        md3Dialog()
            .setTitle(R.string.setting_theme)
            .setSingleChoiceItems(labels, checked) { d, which ->
                ThemePref.set(this, modes[which])
                d.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }


    /**
     * 设置页布局选择：LIST（单列）/ GRID（两列图标网格）。
     * 落库后设置主页在 onResume 检测到变化会重建并按新模式重排。
     *
     * 旧的 CARD 档已在 v1.5.0 去掉（与 LIST 只差分区间距 4dp→18dp，几乎看不出
     * 区别）；存过 `"card"` 的偏好由 [SettingsLayoutMode.fromId] 回落到 LIST。
     */
    private fun showLayoutModeDialog() {
        val modes = SettingsLayoutMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(SettingsLayoutMode.current(this))
        md3Dialog()
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

    /**
     * 统一弹窗样式。
     *
     * v1.8.1 起**不再**在这里换背景：窗口 attach 之后再
     * `setBackgroundDrawableResource` 会让面板首帧后突然从「内缩一圈」撑成整窗，
     * 真机逐帧实测就是「先在上方闪一下再归位」。容器色/圆角/inset 现由
     * 主题 `materialAlertDialogTheme`（ThemeOverlay.Dsh.MaterialAlertDialog）
     * 在 builder 构造期定好，首帧即终态。
     */
    private fun AlertDialog.Builder.showMd3(): AlertDialog =
        create().apply { show() }

    private companion object {
        const val PREFS_UI = DshApp.PREFS_UI
        const val KEY_PAGE_SCALE = "page_scale"
        const val DEFAULT_PAGE_SCALE = 90
        const val MIN_PAGE_SCALE = 50
        const val MAX_PAGE_SCALE = 150
        const val SCALE_STEP = 5
    }
}

package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.ExtensionManager
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege

/**
 * 独立设置页（MIUI 分组卡片风格，替代原先的悬浮菜单）。
 *
 * 三个分组：
 *  - 显示：横屏模式、页面缩放
 *  - 权限中心（统一管理运行权限与无障碍）：运行权限模式、Root 能力状态、Shizuku 状态、屏幕点击（无障碍）
 *  - 其他：重启引擎、关于
 *
 * 权限切换与缩放均复用 MainActivity 的既有决策（Root 双警告、缩放 −/＋ 步进、
 * 无障碍跳系统设置），落库到同一组 SharedPreferences，MainActivity 在 onResume 重新读取生效。
 */
class SettingsActivity : Activity() {

    private var landscape = false
    private var pageScale = DEFAULT_PAGE_SCALE

    /** 当前打开的权限选择对话框（选完/切换中转时关闭） */
    private var privPickDialog: AlertDialog? = null

    /** Shizuku 授权结果监听（requestPermission 异步回调后刷新状态行） */
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ ->
            refreshShizuku()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置页固定竖屏：即使主界面开了横屏模式，设置页也不跟随旋转
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        landscape = prefs.getBoolean(KEY_LANDSCAPE, false)
        pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        // —— 顶部返回 ——
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // —— 显示：横屏模式 ——
        findViewById<LinearLayout>(R.id.rowLandscape).setOnClickListener {
            toggleLandscape()
        }

        // —— 显示：页面缩放 ——
        findViewById<LinearLayout>(R.id.rowScale).setOnClickListener {
            showScaleDialog()
        }

        // —— 权限中心：运行权限模式 ——
        findViewById<LinearLayout>(R.id.rowPriv).setOnClickListener {
            showPrivDialog()
        }

        // —— 权限中心：屏幕点击（无障碍） ——
        findViewById<LinearLayout>(R.id.rowAccess).setOnClickListener {
            handleAccessibility()
        }

        // —— 扩展中心 ——
        findViewById<LinearLayout>(R.id.rowExt).setOnClickListener {
            startActivity(Intent(this, ExtensionStoreActivity::class.java))
        }

        // —— 其他：重启引擎 ——
        findViewById<LinearLayout>(R.id.rowRestart).setOnClickListener {
            // 完整 stop→start；MainActivity 的 handleBar/状态栏会随 state 流转刷新
            (application as DshApp).supervisor.restart()
            Toast.makeText(this, getString(android.R.string.ok), Toast.LENGTH_SHORT).show()
        }

        // —— 其他：关于 ——
        findViewById<TextView>(R.id.subAbout).text =
            getString(R.string.setting_about_sub, versionName())
        findViewById<LinearLayout>(R.id.rowAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    /** 从包管理器读取 versionName（AGP 8+ 默认关闭 BuildConfig，避免依赖它） */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"

    override fun onResume() {
        super.onResume()
        // 从系统无障碍设置返回后刷新状态；Shizuku 授权结果回调也会刷新
        refreshAll()
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    /** 全量刷新：横屏值 + 缩放值 + 权限状态行 + 无障碍 */
    private fun refreshAll() {
        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        landscape = prefs.getBoolean(KEY_LANDSCAPE, false)
        pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

        findViewById<TextView>(R.id.valLandscape).text = getString(
            if (landscape) R.string.setting_orient_landscape else R.string.setting_orient_portrait
        )
        findViewById<TextView>(R.id.valScale).text = "$pageScale%"
        findViewById<TextView>(R.id.valPriv).text = privLabel(Privilege.getMode(this))
        findViewById<TextView>(R.id.valRootStatus).let {
            val rootOk = Privilege.rootAvailableMinimal()
            it.text = getString(
                if (rootOk) R.string.setting_root_status_yes else R.string.setting_root_status_no
            )
            it.setTextColor(if (rootOk) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
        }
        refreshShizuku()
        refreshAccess()
        refreshExt()
        // 缩放副标题文案无需变；图标着色按打开时状态由静态 XML 决定
    }

    /** 扩展中心入口：显示已激活扩展数（>0 变绿提示已并入引擎） */
    private fun refreshExt() {
        val count = ExtensionManager(this).activeCount()
        val v = findViewById<TextView>(R.id.valExt)
        v.text = getString(R.string.setting_ext_active_count, count)
        v.setTextColor(if (count > 0) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
    }

    /** Shizuku 三态刷新（已授权绿 / 等待授权黄 / 未运行灰） */
    private fun refreshShizuku() {
        val v = findViewById<TextView>(R.id.valShizuku)
        when {
            Privilege.shizukuUsable() -> {
                v.text = getString(R.string.setting_shizuku_granted)
                v.setTextColor(0xFF6EE7B7.toInt())
            }
            Privilege.shizukuServerRunning() -> {
                v.text = getString(R.string.setting_shizuku_request)
                v.setTextColor(0xFFFFB74D.toInt())
                Privilege.requestShizukuPermission(SHIZUKU_REQ)
            }
            else -> {
                v.text = getString(R.string.setting_shizuku_absent)
                v.setTextColor(0xFF8A94A3.toInt())
            }
        }
    }

    /** 无障碍状态刷新 */
    private fun refreshAccess() {
        val on = DshAccessibilityService.isEnabled()
        findViewById<TextView>(R.id.subAccess).text = getString(
            if (on) R.string.setting_access_sub_on else R.string.setting_access_sub_off
        )
        findViewById<TextView>(R.id.valAccess).text = if (on) "已开启" else "未开启"
        findViewById<TextView>(R.id.valAccess).setTextColor(
            if (on) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt()
        )
    }

    private fun privLabel(mode: PrivMode): String = when (mode) {
        PrivMode.ROOT -> getString(R.string.priv_root)
        PrivMode.SHIZUKU -> getString(R.string.priv_shizuku)
        PrivMode.NORMAL -> getString(R.string.priv_normal)
    }

    // ================= 显示：横屏 =================

    private fun toggleLandscape() {
        landscape = !landscape
        getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .edit().putBoolean(KEY_LANDSCAPE, landscape).apply()
        // 注意：这里【不能】设置 requestedOrientation——它作用于设置页自身，
        // 会把本应锁竖屏的设置页也转横。朝向切换由 MainActivity.onResume
        // 检测偏好变化后统一应用（返回主界面才生效）。
        findViewById<TextView>(R.id.valLandscape).text = getString(
            if (landscape) R.string.setting_orient_landscape else R.string.setting_orient_portrait
        )
    }

    // ================= 显示：页面缩放 =================

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
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF7DD3FC.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFF7DD3FC.toInt())
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

    /** dp → px 小工具（对话框内边距用） */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ================= 权限中心：运行权限模式 =================

    /**
     * 运行权限模式选择：自定义单选列表（MIUI 行式）。
     * 能力未就绪的选项直接置灰（alpha 0.35）且不可点击：
     *  - Shizuku：server 未运行 → 置灰（无从授权）
     *  - Root：未检测到 su → 置灰
     * Root 仍保留双警告；切换后自动重启引擎。
     */
    private fun showPrivDialog() {
        val current = Privilege.getMode(this)
        val shizukuOk = Privilege.shizukuServerRunning()
        val rootOk = Privilege.rootAvailableMinimal()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        fun makeRow(
            label: String, sub: String, mode: PrivMode,
            enabled: Boolean, checked: Boolean,
        ): LinearLayout {
            val radio = android.widget.RadioButton(this).apply {
                isChecked = checked
                isEnabled = enabled
                isClickable = false   // 由整行接管点击
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                })
                if (sub.isNotEmpty()) addView(TextView(this@SettingsActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(0xFF8A94A3.toInt())
                })
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                if (enabled) {
                    // 解析主题的 ripple 背景为真实 resId 再取 drawable（attr 不能直接 getDrawable）
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    background = getDrawable(tv.resourceId)
                }
                addView(radio)
                addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                })
                alpha = if (enabled) 1f else 0.35f
                isEnabled = enabled
                if (enabled) {
                    setOnClickListener {
                        // 互斥：重画全部 radio
                        for (i in 0 until box.childCount) {
                            val row = box.getChildAt(i) as LinearLayout
                            (row.getChildAt(0) as android.widget.RadioButton).isChecked = row === this
                        }
                        when (mode) {
                            PrivMode.ROOT -> warnRootSwitch {
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.ROOT)
                            }
                            PrivMode.SHIZUKU -> {
                                // server 在跑但未授权 → 先请求授权（弹 Shizuku 框）
                                if (!Privilege.shizukuGranted()) {
                                    Privilege.requestShizukuPermission(SHIZUKU_REQ)
                                }
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.SHIZUKU)
                            }
                            PrivMode.NORMAL -> {
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.NORMAL)
                            }
                        }
                    }
                }
            }
        }

        box.addView(makeRow(
            getString(R.string.priv_normal), "", PrivMode.NORMAL, true, current == PrivMode.NORMAL))
        box.addView(makeRow(
            getString(R.string.priv_shizuku),
            if (shizukuOk) "" else getString(R.string.setting_shizuku_absent),
            PrivMode.SHIZUKU, shizukuOk, current == PrivMode.SHIZUKU))
        box.addView(makeRow(
            getString(R.string.priv_root),
            if (rootOk) "" else getString(R.string.setting_root_status_no),
            PrivMode.ROOT, rootOk, current == PrivMode.ROOT))

        privPickDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.priv_pick_title))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        privPickDialog?.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        privPickDialog?.show()
    }

    /** 关闭权限选择对话框（选完/警告链中转用；null 安全） */
    private fun dismissPrivPick() {
        privPickDialog?.dismiss()
        privPickDialog = null
    }

    /** 切换运行权限模式并自动重启引擎（模式未变则不重启） */
    private fun applyModeAndRestart(target: PrivMode) {
        if (Privilege.getMode(this) == target) return
        Privilege.setMode(this, target)
        findViewById<TextView>(R.id.valPriv).text = privLabel(target)
        (application as DshApp).supervisor.restart()
        Toast.makeText(this, getString(R.string.setting_priv_mode), Toast.LENGTH_SHORT).show()
    }

    private fun warnRootSwitch(next: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ob_priv_root_warn_title))
            .setMessage(getString(R.string.ob_priv_root_warn))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ob_priv_root_warn2_title))
                    .setMessage(getString(R.string.ob_priv_root_warn2))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
                    .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ -> }
                    .showStyled()
            }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ -> }
            .showStyled()
    }

    // ================= 权限中心：无障碍 =================

    /** 无障碍：未开启则跳系统设置引导开启；已开启则提示已可用 */
    private fun handleAccessibility() {
        if (DshAccessibilityService.isEnabled()) {
            Toast.makeText(
                this, getString(R.string.menu_accessibility) + "：已开启",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show() }
    }

    /** 统一弹窗样式：覆盖自绘圆角背景，贴近原生安卓对话框的圆润观感 */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
        }

    private companion object {
        const val SHIZUKU_REQ = 4202
        const val PREFS_UI = "dsh_ui"
        const val KEY_PAGE_SCALE = "page_scale"
        const val KEY_LANDSCAPE = "landscape"
        const val DEFAULT_PAGE_SCALE = 90
        const val MIN_PAGE_SCALE = 50
        const val MAX_PAGE_SCALE = 150
        const val SCALE_STEP = 5
    }
}

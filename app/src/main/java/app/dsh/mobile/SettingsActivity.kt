package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    /** Shizuku 授权结果监听（requestPermission 异步回调后刷新状态行） */
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ ->
            refreshShizuku()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            Toast.makeText(
                this,
                getString(R.string.setting_about_sub, versionName()),
                Toast.LENGTH_SHORT
            ).show()
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
        // 缩放副标题文案无需变；图标着色按打开时状态由静态 XML 决定
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
        requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        findViewById<TextView>(R.id.valLandscape).text = getString(
            if (landscape) R.string.setting_orient_landscape else R.string.setting_orient_portrait
        )
    }

    // ================= 显示：页面缩放 =================

    /** 页面缩放对话框：−/＋ 步进（步长 5），确定后落库；MainActivity onResume 重新读取生效 */
    private fun showScaleDialog() {
        var current = pageScale
        val value = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            text = "$current%"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(Button(this@SettingsActivity).apply {
                text = "−"
                textSize = 22f
                setOnClickListener {
                    current = (current - SCALE_STEP).coerceIn(MIN_PAGE_SCALE, MAX_PAGE_SCALE)
                    value.text = "$current%"
                }
            })
            val centerParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            value.layoutParams = centerParams
            addView(value)
            addView(Button(this@SettingsActivity).apply {
                text = "＋"
                textSize = 22f
                setOnClickListener {
                    current = (current + SCALE_STEP).coerceIn(MIN_PAGE_SCALE, MAX_PAGE_SCALE)
                    value.text = "$current%"
                }
            })
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.scale_title))
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pageScale = current
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit().putInt(KEY_PAGE_SCALE, pageScale).apply()
                findViewById<TextView>(R.id.valScale).text = "$pageScale%"
                // 提示用户回主界面查看效果（缩放需 WebView reload 后按 meta 重写生效）
                Toast.makeText(this, getString(R.string.setting_scale), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showStyled()
    }

    // ================= 权限中心：运行权限模式 =================

    /** 运行权限模式选择：普通/Shizuku/Root，Root 需双警告，切换后自动重启引擎 */
    private fun showPrivDialog() {
        val current = Privilege.getMode(this)
        val rootOk = Privilege.rootAvailableMinimal()
        val rootLabel = getString(R.string.priv_root) + if (rootOk) "" else "（未检测到 su）"
        val opts = arrayOf(
            getString(R.string.priv_normal),
            getString(R.string.priv_shizuku),
            rootLabel,
        )
        val checked = when (current) {
            PrivMode.ROOT -> 2
            PrivMode.SHIZUKU -> 1
            PrivMode.NORMAL -> 0
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_priv_title))
            .setSingleChoiceItems(opts, checked) { diag, which ->
                diag.dismiss()
                val target = when (which) {
                    2 -> PrivMode.ROOT
                    1 -> PrivMode.SHIZUKU
                    else -> PrivMode.NORMAL
                }
                if (target == PrivMode.ROOT && !rootOk) {
                    Toast.makeText(
                        this, getString(R.string.ob_priv_root_gray_hint),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setSingleChoiceItems
                }
                if (target == PrivMode.ROOT) {
                    warnRootSwitch { applyModeAndRestart(PrivMode.ROOT) }
                } else {
                    applyModeAndRestart(target)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        }
        dialog.show()
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

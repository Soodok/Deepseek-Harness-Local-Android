package app.dsh.mobile

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 无障碍（v1.7.9）—— 设置 → 权限与能力 → 无障碍。
 *
 * 为什么单独一页：无障碍相关的东西原先散在三处（系统无障碍开关只能在系统设置里点、
 * 任务记忆在设置→记忆、悬浮窗只能从界面上摸）。用户要求「一个地方管完」。
 *
 * 本页只做**入口与状态**：真正的管理仍在各自页面（记忆面板、系统设置），
 * 这里负责把状态说清楚 + 一键跳过去。
 *
 * 行序按「从底座到上层」：服务开关（没有它全都用不了）→ 任务记忆 → 悬浮窗 →
 * 语音输入 → 安全边界（只读说明）。
 */
class A11ySettingsActivity : ThemedActivity() {

    /** 属于设置体系：跟随「设置页背景图」。 */
    override val usesSettingsBackground: Boolean get() = true

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_settings_a11y)

        findViewById<MaterialToolbar>(R.id.btnBack).setNavigationOnClickListener { finish() }

        findViewById<android.view.View>(R.id.rowA11yService).setOnClickListener { toggleService() }
        findViewById<android.view.View>(R.id.rowA11yMemory).setOnClickListener {
            startActivity(Intent(this, MemoryPanelActivity::class.java))
        }
        findViewById<android.view.View>(R.id.rowA11yOverlay).setOnClickListener { toggleOverlay() }

        // AI 触摸点（v1.7.9 从「个性化」搬来）：只控制画不画点，手势注入完全不受影响。
        val swMarks = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swA11yMarks)
        swMarks.isChecked = DshApp.touchMarkersEnabled(this)
        swMarks.setOnCheckedChangeListener { _, on -> DshApp.setTouchMarkersEnabled(this, on) }
        findViewById<android.view.View>(R.id.rowA11yVoice).setOnClickListener {
            startActivity(Intent(this, VoiceServiceActivity::class.java))
        }
        findViewById<android.view.View>(R.id.rowA11ySafety).setOnClickListener { showSafety() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 设置页统一契约：回来时重贴朝向偏好（见 _test_responsive_native_ui 的检查）。
        OrientationPrefs.applyToSettingsPage(this)
        // 用户可能刚从系统设置里回来 —— 状态必须重读，不能缓存。
        refresh()
    }

    /** 三处状态一次性刷新（服务 / 悬浮窗 / 语音通道）。 */
    private fun refresh() {
        val svcOn = DshAccessibilityService.instance != null
        findViewById<TextView>(R.id.txtA11yService).text = when {
            svcOn -> getString(R.string.a11y_service_on)
            A11ySwitch.granted(this) -> getString(R.string.accessibility_enable)
            else -> getString(R.string.a11y_service_no_perm)
        }
        findViewById<TextView>(R.id.txtA11yOverlay).text = when {
            !svcOn -> getString(R.string.a11y_overlay_need_service)
            StatusOverlay.isShown() -> getString(R.string.a11y_overlay_on)
            else -> getString(R.string.a11y_overlay_off)
        }
        findViewById<TextView>(R.id.txtA11yVoice).text = getString(R.string.settings_section_voice_sub)
    }

    /**
     * 服务开关：拿到 WRITE_SECURE_SETTINGS 就直接写系统列表（A11ySwitch），
     * 没拿到就跳系统设置 —— 后者是唯一合法的路，**不能**假装打开。
     */
    private fun toggleService() {
        val svcOn = DshAccessibilityService.instance != null
        if (svcOn) {
            if (A11ySwitch.granted(this) && A11ySwitch.remove(this)) {
                toast(getString(R.string.a11y_service_off_done))
            } else {
                // 没有写权限：只能把用户送到系统设置里自己关。
                openSystemA11y()
            }
            refresh()
            return
        }
        if (A11ySwitch.granted(this) && A11ySwitch.add(this)) {
            toast(getString(R.string.a11y_service_on_done))
        } else {
            openSystemA11y()
        }
        refresh()
    }

    private fun openSystemA11y() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            toast(getString(R.string.a11y_no_settings_page))
        }
    }

    /** 悬浮窗：点一下立刻显示 / 收起（省得等 AI 动起来才看得到）。 */
    private fun toggleOverlay() {
        val svc = DshAccessibilityService.instance
        if (svc == null) {
            toast(getString(R.string.a11y_overlay_need_service))
            return
        }
        if (StatusOverlay.isShown()) {
            StatusOverlay.hide(svc)
        } else {
            StatusOverlay.showForWork(svc)
        }
        refresh()
    }

    /** 安全边界：只读说明（这些规则是代码里的硬约束，不是可配项）。 */
    private fun showSafety() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.a11y_row_safety)
            .setMessage(R.string.a11y_safety_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

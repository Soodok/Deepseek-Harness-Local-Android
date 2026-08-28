package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
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
 * 用户在此初始化两件事：
 *  1. 默认显示方向（竖屏/横屏）—— 带简易演示图；横屏即绑定桌面布局。
 *  2. 运行权限模式（普通/Shizuku/Root）—— 决定 AI 能力边界。
 *
 * Root 模式保护壳：选择时弹两次高危警告，确认后才写入；启动时（EngineSupervisor
 * 进入 Root 分支前）会先对 dsh-home 做 tar 备份可回滚。能力检测仅 stat/probe，
 * 不做任何危险动作。
 */
class OnboardingActivity : Activity() {

    private var selectedLandscape = false
    private var selectedPriv = PrivMode.NORMAL

    /** Shizuku 授权结果监听（requestPermission 异步回调） */
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ) refreshShizukuStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        // 默认选中：竖屏（手机）+ 普通
        updateSelectionUi()

        findViewById<LinearLayout>(R.id.cardPortrait).setOnClickListener {
            selectedLandscape = false
            selectedOrientationDone = true
            updateSelectionUi()
        }
        findViewById<LinearLayout>(R.id.cardLandscape).setOnClickListener {
            selectedLandscape = true
            selectedOrientationDone = true
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
        privGroup.check(R.id.privNormal)

        // 能力检测（Root 用 su -c id 实测；Shizuku 用官方 binder ping + 授权态）
        val cap = Privilege.probe(this)
        findViewById<TextView>(R.id.capRoot).apply {
            text = if (cap.hasRoot) getString(R.string.ob_cap_root_present)
            else getString(R.string.ob_cap_root_absent)
            setTextColor(if (cap.hasRoot) 0xFF6EE7B7.toInt() else 0xFFFFB74D.toInt())
        }
        refreshShizukuStatus()

        // m1.30：未检测到 su 时，Root 选项置灰不可选 + 下方提示
        updateRootAvailability()

        findViewById<Button>(R.id.btnStart).setOnClickListener { finishOnboarding() }
    }

    /** Root 无 su 可用时：置灰 RadioButton 禁用 + 显示"未检测到 su"提示 */
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
                v.text = getString(R.string.ob_cap_shizuku_request)
                v.setTextColor(0xFFFFB74D.toInt())
                Privilege.requestShizukuPermission(SHIZUKU_REQ)
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
            Privilege.requestShizukuPermission(SHIZUKU_REQ)
        }
    }

    private companion object {
        const val SHIZUKU_REQ = 4201
    }

    /** 方向是否已由用户显式选择（因默认即竖屏，需区分"看过/未选"） */
    private var selectedOrientationDone = false

    private fun afterPrivChange() {
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        // 方向卡片高亮（选中卡加描边边框色）
        val p = findViewById<LinearLayout>(R.id.cardPortrait)
        val l = findViewById<LinearLayout>(R.id.cardLandscape)
        val txtP = findViewById<TextView>(R.id.txtPortrait)
        val txtL = findViewById<TextView>(R.id.txtLandscape)
        val highlight = 0xFF7DD3FC.toInt()
        val normal = 0xFF9CA3AF.toInt()
        txtP.setTextColor(if (selectedLandscape) normal else highlight)
        txtL.setTextColor(if (selectedLandscape) highlight else normal)
        p.alpha = if (selectedLandscape) 0.6f else 1f
        l.alpha = if (selectedLandscape) 1f else 0.6f
    }

    /** 用户点「开始使用」：Root 需双警告，其余直接落库并进主界面。
     *  关键：即使 Root 双警告被取消，也照常 markOnboarded 并进主界面——
     *  避免用户陷入"取消→永远停在引导"的死循环（此前每次进应用都弹的根因）。 */
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
            .setMessage(getString(R.string.ob_priv_root_warn2))
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

        // 落库横竖屏偏好，并应用默认方向
        getSharedPreferences("dsh_ui", Context.MODE_PRIVATE)
            .edit().putBoolean("landscape", selectedLandscape).apply()
        if (selectedLandscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

package app.dsh.mobile

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege
import rikka.shizuku.Shizuku

/**
 * 权限中心（v1.4.58）：设置主页「权限与能力」收纳为两个分类入口后的层级 2 子页。
 *
 * 一个 Activity 承载两张卡，按 Intent extra 只显示请求的分组：
 *  - [GROUP_ENGINE]「运行身份」：运行权限模式 / Root 能力 / Shizuku 状态
 *    —— 三项彼此联动（模式切换依赖前两者的可用性），描述的都是"引擎以什么身份跑"；
 *  - [GROUP_CAPS]「系统能力」：屏幕点击（无障碍）/ 所有文件访问 / 通知 / 麦克风
 *    —— 功能所需的系统授权，各自独立。
 *
 * 绑定代码整体自 SettingsActivity 迁入（v1.4.45~v1.4.57 的逻辑原样保留）：
 * Shizuku 授权监听、麦克风运行时权限回调、运行权限模式对话框（双警告 + 引擎重启）。
 */
class PermissionCenterActivity : ThemedActivity() {

    /** 请求的分组：engine / caps（缺省 = 全部显示，便于调试） */
    private val group: String by lazy {
        intent?.getStringExtra(EXTRA_GROUP) ?: GROUP_ENGINE
    }

    /** 当前打开的权限选择对话框（选完/切换中转时关闭） */
    private var privPickDialog: AlertDialog? = null

    /** Shizuku 授权结果监听（requestPermission 异步回调后刷新状态行） */
    private val shizukuPermListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            // v1.2.158：记住用户的选择——拒绝后不再自动弹窗（隐私）
            if (requestCode == SHIZUKU_REQ) {
                Privilege.markShizukuDenied(
                    this,
                    grantResult != android.content.pm.PackageManager.PERMISSION_GRANTED,
                )
            }
            refreshShizuku()
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.forcePortrait(this)
        setContentView(R.layout.activity_permission_center)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.txtTitle).text = getString(
            if (group == GROUP_CAPS) R.string.settings_perm_group_caps
            else R.string.settings_perm_group_engine,
        )

        // 只显示请求的分组（另一组 GONE：视图仍在层级里，findViewById 照常工作，
        // 刷新函数无需判空）。分组子标题一并隐藏：子页顶栏标题已是同文案，再显示
        // 一行小标题纯属重复（v1.4.58 二次修正）。
        val engineCard = findViewById<View>(R.id.groupEngine)
        val capsCard = findViewById<View>(R.id.groupCaps)
        if (group == GROUP_CAPS) {
            engineCard.visibility = View.GONE
        } else {
            findViewById<View>(R.id.txtGroupEngineSub).visibility = View.GONE
        }
        if (group == GROUP_ENGINE) {
            capsCard.visibility = View.GONE
        } else {
            findViewById<View>(R.id.txtGroupCapsSub).visibility = View.GONE
        }

        // —— 运行身份：运行权限模式 ——
        findViewById<LinearLayout>(R.id.rowPriv).setOnClickListener {
            showPrivDialog()
        }

        // —— 系统能力：屏幕点击（无障碍） ——
        findViewById<LinearLayout>(R.id.rowAccess).setOnClickListener {
            handleAccessibility()
        }

        // —— 系统能力：所有文件访问（AI 在共享存储建/读写文件） ——
        findViewById<LinearLayout>(R.id.rowStorage).setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= 30)
                Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                )
            else Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName"),
            )
            runCatching { startActivity(intent) }
                .onFailure {
                    Toast.makeText(this, getString(R.string.setting_access_open_failed), Toast.LENGTH_SHORT).show()
                }
        }

        // —— 系统能力：通知权限（Android 13+ 运行时授权，Agent 推送需要） ——
        findViewById<LinearLayout>(R.id.rowNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
        }

        // —— 系统能力：麦克风（语音输入需要 RECORD_AUDIO） ——
        // 已授权时点击提示无需重复授权；未授权时弹系统授权框（本 Activity 有权请求）。
        findViewById<LinearLayout>(R.id.rowMic).setOnClickListener {
            if (hasMicPermission()) {
                Toast.makeText(this, getString(R.string.mic_granted), Toast.LENGTH_SHORT).show()
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
        }

        // —— AI 设备能力开关（v1.4.78 · docs/DEVICE_CAPABILITIES.md） ——
        // 用户显式授权的唯一入口：默认全关，关闭时 AgentBridge 对相应接口 403。
        // 开关只改「是否允许 AI 用」，不改系统权限；系统权限不满足时开关点不动，
        // 并提示去哪一张卡里补授权（否则用户会以为开关坏了）。
        bindCapSwitch(R.id.swCapStatus, R.id.rowCapStatus, CapabilityGate.STATUS, 0)
        bindCapSwitch(R.id.swCapSensor, R.id.rowCapSensor, CapabilityGate.SENSOR, 0)
        bindCapSwitch(R.id.swCapNotify, R.id.rowCapNotify, CapabilityGate.NOTIFY, R.string.setting_cap_notify_need)
        bindCapSwitch(R.id.swCapClip, R.id.rowCapClip, CapabilityGate.CLIPBOARD, 0)
        bindCapSwitch(R.id.swCapControl, R.id.rowCapControl, CapabilityGate.CONTROL, 0)
        bindCapSwitch(R.id.swCapSched, R.id.rowCapSched, CapabilityGate.SCHED, 0)
        bindCapSwitch(R.id.swCapWifi, R.id.rowCapWifi, CapabilityGate.WIFI, 0)
        bindCapSwitch(R.id.swCapBt, R.id.rowCapBt, CapabilityGate.BT, 0)
        // 读取他人通知只能由用户在系统里手动授予「通知使用权」，运行时权限拿不到，
        // 所以权限不足时直接跳该设置页。
        bindCapSwitch(
            R.id.swCapNotifyRead,
            R.id.rowCapNotifyRead,
            CapabilityGate.NOTIFY_READ,
            R.string.setting_cap_notify_read_need,
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        )
        // 代用户回复通知：与前两者分开授权 —— 回复是「以用户身份对外发言」，后果不可撤回。
        bindCapSwitch(
            R.id.swCapNotifyAction,
            R.id.rowCapNotifyAction,
            CapabilityGate.NOTIFY_ACTION,
            R.string.setting_cap_notify_read_need,
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        )
        // 媒体控制同样依赖通知使用权（MediaSession 的 MEDIA_CONTENT_CONTROL 拿不到）。
        bindCapSwitch(
            R.id.swCapMedia,
            R.id.rowCapMedia,
            CapabilityGate.MEDIA,
            R.string.setting_cap_media_need,
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        )
        // 日历/联系人/短信：运行时危险权限，缺时提示去系统设置授予。
        bindCapSwitch(
            R.id.swCapPersonal,
            R.id.rowCapPersonal,
            CapabilityGate.PERSONAL,
            R.string.setting_cap_personal_need,
            null,
        )
        // NFC 与地理围栏：NFC 无法由应用静默开启，缺时直接跳无线设置页。
        bindCapSwitch(
            R.id.swCapRf,
            R.id.rowCapRf,
            CapabilityGate.RF,
            R.string.setting_cap_rf_need,
            Settings.ACTION_NFC_SETTINGS,
        )
        // 屏幕录制：受系统授权弹窗约束（每次开始都要用户点头），无前置系统权限。
        bindCapSwitch(
            R.id.swCapScreenCapture,
            R.id.rowCapScreenCapture,
            CapabilityGate.SCREEN_CAPTURE,
            0,
        )

        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页（无障碍/存储/通知）返回后刷新状态；Shizuku 回调也会刷新
        refreshAll()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == REQ_MIC) {
            refreshMicRow()
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                getString(if (ok) R.string.mic_granted else R.string.mic_denied),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ------------------------------------------------------------ 状态刷新

    private fun refreshAll() {
        findViewById<TextView>(R.id.valPriv).text = privLabel(Privilege.getMode(this))
        findViewById<TextView>(R.id.valRootStatus).let {
            val rootOk = Privilege.rootAvailableMinimal()
            it.text = getString(
                if (rootOk) R.string.setting_root_status_yes else R.string.setting_root_status_no,
            )
            it.asStatusChip(
                if (rootOk) R.color.success else R.color.text_secondary,
                if (rootOk) R.color.success_soft else R.color.surface_alt,
            )
        }
        refreshShizuku()
        refreshAccess()
        refreshStorageRow()
        refreshNotifRow()
        refreshMicRow()
        refreshCapSwitches()

    }

    /** 所有文件访问状态（MANAGE_EXTERNAL_STORAGE，Android 11+ 系统设置页授予；
     *  引擎进程与 App 同 uid，授权后引擎即可读写共享存储） */
    private fun refreshStorageRow() {
        val granted = if (Build.VERSION.SDK_INT >= 30)
            android.os.Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val v = findViewById<TextView>(R.id.valStorage)
        v.text = getString(if (granted) R.string.storage_granted else R.string.storage_denied)
        v.asStatusChip(
            if (granted) R.color.success else R.color.warning,
            if (granted) R.color.success_soft else R.color.warning_soft,
        )
    }

    /** 通知权限（Android 13+ 运行时授权；低版本默认持有） */
    private fun refreshNotifRow() {
        val granted = if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
        val v = findViewById<TextView>(R.id.valNotif)
        v.text = getString(if (granted) R.string.notif_granted else R.string.notif_denied)
        v.asStatusChip(
            if (granted) R.color.success else R.color.text_secondary,
            if (granted) R.color.success_soft else R.color.surface_alt,
        )
    }

    /** 麦克风权限是否已授予（语音输入的前提） */
    private fun hasMicPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun refreshMicRow() {
        val granted = hasMicPermission()
        val v = findViewById<TextView>(R.id.valMic)
        v.text = getString(if (granted) R.string.mic_granted else R.string.mic_denied)
        v.asStatusChip(
            if (granted) R.color.success else R.color.text_secondary,
            if (granted) R.color.success_soft else R.color.surface_alt,
        )
    }

    /** Shizuku 三态刷新（已授权绿 / 等待授权黄 / 未运行灰） */
    private fun refreshShizuku() {
        val v = findViewById<TextView>(R.id.valShizuku)
        when {
            Privilege.shizukuUsable() -> {
                v.text = getString(R.string.setting_shizuku_granted)
                v.asStatusChip(R.color.success, R.color.success_soft)
            }
            Privilege.shizukuServerRunning() -> {
                // v1.2.158 隐私修复：这里**不再**自动请求（拒绝后回调又会回到此处 →
                // 死循环弹窗）。改为展示状态 + 允许点击该行**显式**重新申请。
                v.text = getString(R.string.setting_shizuku_request)
                v.asStatusChip(R.color.warning, R.color.warning_soft)
                v.isClickable = true
                v.setOnClickListener {
                    Privilege.requestShizukuPermission(SHIZUKU_REQ, this@PermissionCenterActivity, explicit = true)
                }
            }
            else -> {
                v.text = getString(R.string.setting_shizuku_absent)
                v.asStatusChip(R.color.text_secondary, R.color.surface_alt)
            }
        }
    }

    /** 无障碍状态刷新 */
    private fun refreshAccess() {
        val on = DshAccessibilityService.isEnabled()
        findViewById<TextView>(R.id.subAccess).text = getString(
            if (on) R.string.setting_access_sub_on else R.string.setting_access_sub_off,
        )
        val v = findViewById<TextView>(R.id.valAccess)
        v.text = getString(if (on) R.string.state_on else R.string.state_off)
        v.asStatusChip(
            if (on) R.color.success else R.color.text_secondary,
            if (on) R.color.success_soft else R.color.surface_alt,
        )
    }

    private fun privLabel(mode: PrivMode): String = when (mode) {
        PrivMode.ROOT -> getString(R.string.priv_root)
        PrivMode.SHIZUKU -> getString(R.string.priv_shizuku)
        PrivMode.NORMAL -> getString(R.string.priv_normal)
    }

    // ------------------------------------------------------------ 运行权限模式

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
                addView(TextView(this@PermissionCenterActivity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(getColor(R.color.text_primary))
                })
                if (sub.isNotEmpty()) addView(TextView(this@PermissionCenterActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
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
                                    // 用户主动选了 Shizuku 模式 = 显式请求（会重置"已拒绝"标记）
                                    Privilege.requestShizukuPermission(SHIZUKU_REQ, this@PermissionCenterActivity, explicit = true)
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

    // ------------------------------------------------------------ 无障碍

    /** 无障碍：未开启则跳系统设置引导开启；已开启则提示已可用 */
    private fun handleAccessibility() {
        if (DshAccessibilityService.isEnabled()) {
            Toast.makeText(
                this, getString(R.string.access_enabled_toast),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, getString(R.string.setting_access_open_failed), Toast.LENGTH_SHORT).show() }
    }

    /** 统一弹窗样式：覆盖自绘圆角背景，贴近原生安卓对话框的圆润观感 */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
        }

    /**
     * 绑定一个 AI 能力开关。
     *
     * 设计要点：**点击行为永远一致**（切换开关），是否可用只在渲染时体现。
     * 早期实现按「系统权限不满足」分支把 row 的点击改弹提示 —— 结果用户从上方
     * 卡片补授权限返回后，开关虽已置为可用，点击却仍只弹提示（行为没跟着恢复）。
     * 改为：点击一律 toggle，权限不满足时由 toggle 自己弹提示。
     *
     * @param needHintRes 系统权限不满足时要提示的文案；0 表示该能力无额外系统门槛。
     * @param settingsAction 非空时，权限不满足会提示并跳该系统设置页（如通知使用权
     *        只能由用户在系统里手动授予，运行时弹窗拿不到）。
     */
    private fun bindCapSwitch(
        switchId: Int,
        rowId: Int,
        cap: String,
        needHintRes: Int,
        settingsAction: String? = null,
    ) {
        val sw = findViewById<android.widget.Switch>(switchId)
        val row = findViewById<View>(rowId)

        val hint: () -> Unit = {
            if (needHintRes != 0) {
                Toast.makeText(this, getString(needHintRes), Toast.LENGTH_LONG).show()
            }
            if (settingsAction != null) {
                runCatching { startActivity(Intent(settingsAction)) }
            }
        }

        val toggle: () -> Unit = {
            if (needHintRes != 0 && !CapabilityGate.permissionAvailable(this, cap)) {
                hint()
            } else {
                val next = !CapabilityGate.enabled(this, cap)
                CapabilityGate.setEnabled(this, cap, next)
                sw.isChecked = next
            }
        }

        sw.setOnCheckedChangeListener { _, checked ->
            // 用户手点才会走到这里；与存储值不一致才写盘（refresh 的赋值不会写）。
            if (checked != CapabilityGate.enabled(this, cap)) {
                if (needHintRes != 0 && !CapabilityGate.permissionAvailable(this, cap)) {
                    sw.isChecked = CapabilityGate.enabled(this, cap)
                    hint()
                } else {
                    CapabilityGate.setEnabled(this, cap, checked)
                }
            }
        }
        row.setOnClickListener { toggle() }
        renderCapSwitch(sw, cap)
    }

    /** 单个开关的渲染：值来自存储，可用性来自系统权限。 */
    private fun renderCapSwitch(sw: android.widget.Switch, cap: String) {
        val ok = CapabilityGate.permissionAvailable(this, cap)
        sw.isChecked = CapabilityGate.enabled(this, cap)
        sw.isEnabled = ok
        sw.alpha = if (ok) 1f else 0.4f
    }

    /** 从系统设置页返回后同步开关的真实状态（权限可能刚被授予或撤销）。 */
    private fun refreshCapSwitches() {
        val pairs = listOf(
            R.id.swCapStatus to CapabilityGate.STATUS,
            R.id.swCapSensor to CapabilityGate.SENSOR,
            R.id.swCapNotify to CapabilityGate.NOTIFY,
            R.id.swCapClip to CapabilityGate.CLIPBOARD,
            R.id.swCapControl to CapabilityGate.CONTROL,
            R.id.swCapSched to CapabilityGate.SCHED,
            R.id.swCapWifi to CapabilityGate.WIFI,
            R.id.swCapBt to CapabilityGate.BT,
            R.id.swCapNotifyRead to CapabilityGate.NOTIFY_READ,
            R.id.swCapNotifyAction to CapabilityGate.NOTIFY_ACTION,
            R.id.swCapMedia to CapabilityGate.MEDIA,
            R.id.swCapPersonal to CapabilityGate.PERSONAL,
            R.id.swCapRf to CapabilityGate.RF,
            R.id.swCapScreenCapture to CapabilityGate.SCREEN_CAPTURE,
        )
        for ((id, cap) in pairs) {
            val sw = runCatching { findViewById<android.widget.Switch>(id) }.getOrNull() ?: continue
            renderCapSwitch(sw, cap)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 状态值徽章化（v1.4.45）：软底 chip + 状态色文字。
     * 软底/文字色由调用方按状态给（success/success_soft、warning/warning_soft、
     * text_secondary/surface_alt），明暗主题都由 values(-night) 保证可读。
     */
    private fun TextView.asStatusChip(textColorRes: Int, bgTintRes: Int) {
        setBackgroundResource(R.drawable.bg_status_chip)
        backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(bgTintRes))
        setTextColor(getColor(textColorRes))
        setPadding(dp(12), dp(5), dp(12), dp(5))
    }

    companion object {
        /** Intent extra：请求显示的分组（engine / caps） */
        const val EXTRA_GROUP = "group"
        const val GROUP_ENGINE = "engine"
        const val GROUP_CAPS = "caps"

        private const val SHIZUKU_REQ = 4202

        /** 麦克风权限请求码 */
        private const val REQ_MIC = 4203
    }
}

package app.dsh.mobile

import androidx.appcompat.app.AlertDialog
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
    /** 属于设置体系：启用「设置页背景图」（见 ThemedActivity.usesSettingsBackground）。 */
    override val usesSettingsBackground: Boolean get() = true


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
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_permission_center)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.txtTitle).text = getString(
            if (group == GROUP_CAPS) R.string.settings_perm_group_caps
            else R.string.settings_perm_group_engine,
        )

        // 只显示请求的分组（另一组 GONE：视图仍在层级里，findViewById 照常工作，
        // 刷新函数无需判空）。分组子标题一并隐藏：子页顶栏标题已是同文案，再显示
        // 一行小标题纯属重复（v1.4.58 二次修正）。
        //
        // ★groupDeviceCaps 必须单独处理（2026-11-09 修复）。它是 groupCaps 的
        //   **兄弟节点**，不是子节点 —— 布局里三个卡片都是根 ScrollView 的直接孩子。
        //   只藏 groupCaps 时会剩下 28 个 AI 能力磁贴 + 总览卡 + 批量授权/权限清单
        //   全都留在屏幕上，于是「运行身份」页里也堆满了权限管理内容，和
        //   「系统能力」页看起来几乎一样（实测：两页都有 0/28 总览与 6 个能力域）。
        val engineCard = findViewById<View>(R.id.groupEngine)
        val capsCard = findViewById<View>(R.id.groupCaps)
        val deviceCapsCard = findViewById<View>(R.id.groupDeviceCaps)
        findViewById<View>(R.id.txtGroupEngineSub).visibility = View.GONE
        findViewById<View>(R.id.txtGroupCapsSub).visibility = View.GONE
        if (group == GROUP_CAPS) {
            engineCard.visibility = View.GONE
        } else {
            capsCard.visibility = View.GONE
            deviceCapsCard.visibility = View.GONE
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

        // —— AI 能力磁贴（v1.6.0：总览磁贴 + 二级详情） ——
        // 用户显式授权的唯一入口：默认全关，关闭时 AgentBridge 对相应接口 403。
        // 交互分工：磁贴上的开关直接切换（快路径）；点磁贴其余区域进二级详情，
        // 说明文字只在详情里出现 —— 这样 20 项才能在 1.4 屏内排完（原列表形态
        // 真机实测 4.9 屏，一屏只能看到 4 项）。
        for (spec in capSpecs()) bindCapSwitch(spec)

        // 一键授予全部权限（v1.5.0-beta.9）：无差别尝试 Manifest 里声明的每一个权限。
        // 有 root/Shizuku 时才有意义；两者都没有时按钮会如实提示无法授予。
        findViewById<android.view.View>(R.id.rowGrantAll)?.setOnClickListener { grantAllPerms() }
        findViewById<android.view.View>(R.id.btnGrantAll)?.setOnClickListener { grantAllPerms() }
        refreshGrantAllSub()

        // 查看清单（v1.5.0-beta.10）：1358 条权限不能只给个数字，要能搜能筛能撤销。
        findViewById<android.view.View>(R.id.rowPermList)?.setOnClickListener {
            startActivity(android.content.Intent(this, PermissionListActivity::class.java))
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        refreshAll()
    }

    /**
     * 一键授予全部权限（v1.5.0-beta.9）。
     *
     * 无差别尝试：把 Manifest 声明的**每一个**权限都 `pm grant` 一遍，能拿就拿。
     * 拿不到的那 1000 多个 signature 级权限本来就不属于第三方 App，但以后系统
     * 版本放宽时我们自动就有了，不用改代码。
     *
     * 必须回读 —— `pm grant` 对未声明的权限也返回成功，只有 checkSelfPermission
     * 才反映真实状态。
     */
    private fun grantAllPerms() {
        val sub = findViewById<android.widget.TextView>(R.id.txtGrantAllSub)
        val btn = findViewById<android.widget.TextView>(R.id.btnGrantAll)
        btn?.isEnabled = false
        sub?.text = getString(R.string.grant_perms_progress)
        // 授权跑在**脱离 App 的 root 进程**里（见 PermGrant 顶部注释：
        // pm grant 带 gid 的权限会杀掉 App 进程，进程内线程必然跑不完）。
        app.dsh.mobile.engine.PermGrant.startAsync(this, force = true)
        Thread {
            var last = -1
            var waited = 0
            while (waited < 300_000) {
                val pr = app.dsh.mobile.engine.PermGrant.progress(this)
                if (pr.done != last) {
                    last = pr.done
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        sub?.text = "" + pr.done + " / " + pr.total + "  +" + pr.newlyGranted
                    }
                }
                if (!pr.running) break
                Thread.sleep(500)
                waited += 500
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btn?.isEnabled = true
                val pr = app.dsh.mobile.engine.PermGrant.progress(this)
                val msg = when {
                    pr.lastGranted < 0 -> getString(R.string.grant_perms_fail)
                    pr.lastGranted > 0 -> getString(R.string.grant_perms_ok, pr.lastGranted)
                    else -> getString(
                        R.string.grant_perms_none,
                        app.dsh.mobile.engine.PermGrant.grantedCount(this),
                        pr.total,
                    )
                }
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                refreshGrantAllSub()
            }
        }.apply { isDaemon = true }.start()
    }

    /** 刷新一键授权行的副标题：当前已拿到 / 总共声明。 */
    private fun refreshGrantAllSub() {
        val sub = findViewById<android.widget.TextView>(R.id.txtGrantAllSub) ?: return
        val total = app.dsh.mobile.engine.PermGrant.declaredPermissions(this).size
        val got = app.dsh.mobile.engine.PermGrant.grantedCount(this)
        sub.text = if (total > 0) "$got / $total" else getString(R.string.setting_grant_perms_sub)
    }

    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
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
        if (requestCode == REQ_CAP_RUNTIME) {
            // 能力磁贴的运行时权限回来了：重绘全部磁贴，让刚拿到权限的那一格从「待授权」
            // 变成可点。不在这里顺手把开关打开 —— 授权 ≠ 同意把能力交给 AI，两件事分开。
            refreshCapSwitches()
            val any = grantResults.any {
                it == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            Toast.makeText(
                this,
                getString(if (any) R.string.cap_perm_granted else R.string.cap_perm_denied),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
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
        refreshTtsRow()
        refreshCapSwitches()

    }

    /**
     * TTS 语音合成测试（v1.7.9）：chip 显示设备上的引擎数，点行念一句示例。
     *
     * 朗读放到后台线程：TextToSpeech 的 init 回调绑构造线程的 looper，主线程直接调
     * speak() 会互等（TtsManager 里有防御性重入，但那会在主线程 join 最多 25s）。
     * 这里自己开线程，结果用 Toast 回显，不阻塞 UI。
     */
    private fun refreshTtsRow() {
        val n = app.dsh.mobile.engine.TtsManager.engineCount(this)
        val v = findViewById<TextView>(R.id.valTtsTest)
        v.text = if (n > 0) getString(R.string.tts_engines, n) else getString(R.string.tts_missing)
        v.asStatusChip(
            if (n > 0) R.color.success else R.color.warning,
            if (n > 0) R.color.success_soft else R.color.warning_soft,
        )
        findViewById<LinearLayout>(R.id.rowTtsTest).setOnClickListener {
            Toast.makeText(this, R.string.tts_test_speaking, Toast.LENGTH_SHORT).show()
            val ctx = applicationContext
            val sample = getString(R.string.tts_test_sample)
            Thread({
                val r = app.dsh.mobile.engine.TtsManager.speak(ctx, sample, true)
                val ok = r.startsWith("speaking") || r.startsWith("edge tts speaking")
                runOnUiThread {
                    if (!ok) Toast.makeText(this, getString(R.string.tts_test_failed, r), Toast.LENGTH_LONG).show()
                }
            }, "dsh-tts-test").apply { isDaemon = true }.start()
        }
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
            val radio = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
                isChecked = checked
                isEnabled = enabled
                isClickable = false   // 由整行接管点击
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    getColor(R.color.md3_primary),
                )
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@PermissionCenterActivity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(dshColor(R.attr.dshTextPrimary, R.color.text_primary))
                })
                if (sub.isNotEmpty()) addView(TextView(this@PermissionCenterActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
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

        privPickDialog = md3Dialog()
            .setTitle(getString(R.string.priv_pick_title))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
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
        md3Dialog()
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
            show()
        }

    // --------------------------------------------------------- AI 能力磁贴

    /** 一项 AI 能力的视图与系统门槛描述（v1.6.0）。 */
    private data class CapSpec(
        /** CapabilityGate 里的能力名（持久化键与 403 判定都用它）。 */
        val cap: String,
        /** 磁贴右上角的开关。 */
        val sw: Int,
        /** 磁贴容器：点它进二级详情。 */
        val row: Int,
        /** 磁贴底部状态文字（已开启 / 未开启 / 待授权）。 */
        val state: Int,
        val title: Int,
        /** 说明文字，只在二级详情里显示。 */
        val sub: Int,
        /** 系统门槛不满足时的提示；0 = 该能力无额外系统门槛。 */
        val need: Int = 0,
        /** 非空时，门槛不满足会跳该系统设置页（如通知使用权只能手动授予）。 */
        val action: String? = null,
    )

    /** 一个域：标题、右上角「已开 n/m」计数、该域下的能力。 */
    private data class CapDomain(val label: Int, val count: Int, val items: List<CapSpec>)

    private fun spec(
        cap: String,
        sw: Int,
        row: Int,
        state: Int,
        title: Int,
        sub: Int,
        need: Int = 0,
        action: String? = null,
    ) = CapSpec(cap, sw, row, state, title, sub, need, action)

    /**
     * 六个域的能力清单（顺序即界面顺序）。
     *
     * 为什么分域而不是一列平铺：20 项排成一列时，「短信在哪」要靠滚动找；按
     * 设备/通知/控制/个人数据/屏幕/网络系统分组后，每个域右上角的「已开 n/m」
     * 能直接回答「这一类我开放了多少」，不用逐格数。
     *
     * 每一项都必须是**独立**开关：旧设计把日历/联系人/短信/通话捆成一个
     * PERSONAL 总开关，等于逼用户为了看日程把短信也交出去。
     */
    private fun capDomains(): List<CapDomain> = listOf(
        CapDomain(
            R.string.setting_cap_domain_env, R.id.txtDomEnv,
            listOf(
                spec(
                    CapabilityGate.STATUS, R.id.swCapStatus, R.id.rowCapStatus,
                    R.id.stCapStatus, R.string.setting_cap_status, R.string.setting_cap_status_sub,
                ),
                spec(
                    CapabilityGate.SENSOR, R.id.swCapSensor, R.id.rowCapSensor,
                    R.id.stCapSensor, R.string.setting_cap_sensor, R.string.setting_cap_sensor_sub,
                ),
            ),
        ),
        CapDomain(
            R.string.setting_cap_domain_notify, R.id.txtDomNotify,
            listOf(
                spec(
                    CapabilityGate.NOTIFY, R.id.swCapNotify, R.id.rowCapNotify,
                    R.id.stCapNotify, R.string.setting_cap_notify, R.string.setting_cap_notify_sub,
                    R.string.setting_cap_notify_need,
                ),
                // 读取他人通知只能由用户在系统里手动授予「通知使用权」，运行时权限拿不到。
                spec(
                    CapabilityGate.NOTIFY_READ, R.id.swCapNotifyRead, R.id.rowCapNotifyRead,
                    R.id.stCapNotifyRead, R.string.setting_cap_notify_read,
                    R.string.setting_cap_notify_read_sub,
                    R.string.setting_cap_notify_read_need, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                ),
                // 代用户回复：与前两者分开授权 —— 这是「以用户身份对外发言」，不可撤回。
                spec(
                    CapabilityGate.NOTIFY_ACTION, R.id.swCapNotifyAction, R.id.rowCapNotifyAction,
                    R.id.stCapNotifyAction, R.string.setting_cap_notify_action,
                    R.string.setting_cap_notify_action_sub,
                    R.string.setting_cap_notify_read_need, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                ),
                // 媒体控制同样依赖通知使用权（MEDIA_CONTENT_CONTROL 拿不到）。
                spec(
                    CapabilityGate.MEDIA, R.id.swCapMedia, R.id.rowCapMedia,
                    R.id.stCapMedia, R.string.setting_cap_media, R.string.setting_cap_media_sub,
                    R.string.setting_cap_media_need, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                ),
            ),
        ),
        CapDomain(
            R.string.setting_cap_domain_control, R.id.txtDomControl,
            listOf(
                spec(
                    CapabilityGate.CONTROL, R.id.swCapControl, R.id.rowCapControl,
                    R.id.stCapControl, R.string.setting_cap_control, R.string.setting_cap_control_sub,
                ),
                spec(
                    CapabilityGate.SCHED, R.id.swCapSched, R.id.rowCapSched,
                    R.id.stCapSched, R.string.setting_cap_sched, R.string.setting_cap_sched_sub,
                ),
                spec(
                    CapabilityGate.WIFI, R.id.swCapWifi, R.id.rowCapWifi,
                    R.id.stCapWifi, R.string.setting_cap_wifi, R.string.setting_cap_wifi_sub,
                ),
                spec(
                    CapabilityGate.BT, R.id.swCapBt, R.id.rowCapBt,
                    R.id.stCapBt, R.string.setting_cap_bt, R.string.setting_cap_bt_sub,
                ),
            ),
        ),
        CapDomain(
            R.string.setting_cap_domain_personal, R.id.txtDomPersonal,
            listOf(
                spec(
                    CapabilityGate.PERSONAL_CALENDAR, R.id.swCapCalendar, R.id.rowCapCalendar,
                    R.id.stCapCalendar, R.string.setting_cap_personal_calendar,
                    R.string.setting_cap_personal_calendar_sub, R.string.setting_cap_personal_need,
                ),
                spec(
                    CapabilityGate.PERSONAL_CONTACTS, R.id.swCapContacts, R.id.rowCapContacts,
                    R.id.stCapContacts, R.string.setting_cap_personal_contacts,
                    R.string.setting_cap_personal_contacts_sub, R.string.setting_cap_personal_need,
                ),
                spec(
                    CapabilityGate.PERSONAL_SMS, R.id.swCapSms, R.id.rowCapSms,
                    R.id.stCapSms, R.string.setting_cap_personal_sms,
                    R.string.setting_cap_personal_sms_sub, R.string.setting_cap_personal_need,
                ),
                spec(
                    CapabilityGate.PERSONAL_CALLS, R.id.swCapCalls, R.id.rowCapCalls,
                    R.id.stCapCalls, R.string.setting_cap_personal_calls,
                    R.string.setting_cap_personal_calls_sub, R.string.setting_cap_personal_need,
                ),
            ),
        ),
        CapDomain(
            R.string.setting_cap_domain_input, R.id.txtDomInput,
            listOf(
                // 屏幕录制受系统授权弹窗约束（每次开始都要用户点头），无前置系统权限。
                spec(
                    CapabilityGate.SCREEN_CAPTURE, R.id.swCapScreenCapture, R.id.rowCapScreenCapture,
                    R.id.stCapScreenCapture, R.string.setting_cap_screen_capture,
                    R.string.setting_cap_screen_capture_sub,
                ),
                // 虚拟 HID：在系统里注册一个真实外接键盘，需要 root。
                spec(
                    CapabilityGate.HID, R.id.swCapHid, R.id.rowCapHid,
                    R.id.stCapHid, R.string.setting_cap_hid, R.string.setting_cap_hid_sub,
                    R.string.setting_cap_hid_need,
                ),
                spec(
                    CapabilityGate.CLIPBOARD, R.id.swCapClip, R.id.rowCapClip,
                    R.id.stCapClip, R.string.setting_cap_clip, R.string.setting_cap_clip_sub,
                ),
            ),
        ),
        CapDomain(
            R.string.setting_cap_domain_system, R.id.txtDomSystem,
            listOf(
                // NFC 无法由应用静默开启，缺时直接跳无线设置页。
                spec(
                    CapabilityGate.RF, R.id.swCapRf, R.id.rowCapRf,
                    R.id.stCapRf, R.string.setting_cap_rf, R.string.setting_cap_rf_sub,
                    R.string.setting_cap_rf_need, Settings.ACTION_NFC_SETTINGS,
                ),
                // 按 UID 断网：唯一能整体切断别的 App 网络的能力，封锁需 root。
                spec(
                    CapabilityGate.NET, R.id.swCapNet, R.id.rowCapNet,
                    R.id.stCapNet, R.string.setting_cap_net, R.string.setting_cap_net_sub,
                    R.string.setting_cap_net_need,
                ),
                // 系统级操作 / root shell：本项目权限最大的一档，仅 ROOT 模式可用。
                spec(
                    CapabilityGate.ROOT, R.id.swCapRoot, R.id.rowCapRoot,
                    R.id.stCapRoot, R.string.setting_cap_root, R.string.setting_cap_root_sub,
                    R.string.setting_cap_root_need,
                ),
                // 读取其他 App 私有数据。与 root shell **分开授权**：
                // root 是「能操作系统」，这个是「能看别人存了什么」——
                // 用户可能愿意让 AI 帮他还原设置，但不愿让它翻聊天数据库。
                spec(
                    CapabilityGate.APPDATA, R.id.swCapAppdata, R.id.rowCapAppdata,
                    R.id.stCapAppdata, R.string.setting_cap_appdata,
                    R.string.setting_cap_appdata_sub,
                    R.string.setting_cap_appdata_need,
                ),
                // Shizuku / ADB shell 通道。与 root 互斥但不等价：root 靠「引擎本身
                // uid 0」走门脚本直连；Shizuku 靠 App 进程的 binder，必须经桥中转。
                spec(
                    CapabilityGate.SHIZUKU, R.id.swCapShizuku, R.id.rowCapShizuku,
                    R.id.stCapShizuku, R.string.setting_cap_shizuku,
                    R.string.setting_cap_shizuku_sub,
                    R.string.setting_cap_shizuku_need,
                ),
                // 虚拟屏：给 AI 的第二块屏。比录屏强一个量级 —— 录屏只是「看」，
                // 虚拟屏是「另开一台手机」，用户完全看不见，所以单独授权、默认关。
                spec(
                    CapabilityGate.VSCREEN, R.id.swCapVscreen, R.id.rowCapVscreen,
                    R.id.stCapVscreen, R.string.setting_cap_vscreen,
                    R.string.setting_cap_vscreen_sub,
                    R.string.setting_cap_vscreen_need,
                ),
                // 事件流两项（v1.6.x）。默认关，且**只有它们会在每次被读取时弹告警**。
                // 与 CAP.CONTROL 分开：控制是「代我操作」，这两项是「看我做了什么」。
                spec(
                    CapabilityGate.INPUTWATCH, R.id.swCapInputwatch, R.id.rowCapInputwatch,
                    R.id.stCapInputwatch, R.string.setting_cap_inputwatch,
                    R.string.setting_cap_inputwatch_sub,
                    R.string.setting_cap_inputwatch_need,
                ),
                spec(
                    CapabilityGate.KEYWATCH, R.id.swCapKeywatch, R.id.rowCapKeywatch,
                    R.id.stCapKeywatch, R.string.setting_cap_keywatch,
                    R.string.setting_cap_keywatch_sub,
                    R.string.setting_cap_keywatch_need,
                ),
            ),
        ),
        /*
         * ★Linux 容器域（v1.6.x）。
         *
         * 这一域收的是「AI 能对这台手机做的、与运行环境有关」的能力：
         * 在容器里跑命令、装/卸应用、看位置、拍照。
         *
         * ⚠️ 前三项（packages/location/camera）的能力代码与文案早就写好了，
         * 但**权限中心一直没引用它们** —— 用户根本看不到开关，只能靠有人手改
         * prefs 才能用。这里一并补上，否则那三个端点等于不存在。
         *
         * 顺序按「动静」排：Linux 是最大的（能跑任意命令），然后是装应用
         * （会改变这台机器上有什么），最后两个是读位置/拍照（只看不写）。
         */
        CapDomain(
            R.string.setting_cap_domain_linux, R.id.txtDomLinux,
            listOf(
                // 容器内执行命令：能力上等价于给一个 shell，所以放这一域第一格。
                spec(
                    CapabilityGate.LINUX, R.id.swCapLinux, R.id.rowCapLinux,
                    R.id.stCapLinux, R.string.setting_cap_linux,
                    R.string.setting_cap_linux_sub,
                ),
                // 装/卸应用：install 仍强制走系统确认页（不提供静默安装）。
                // 列出应用不需要任何系统权限，所以它的门槛提示说的是「装才要授权」——
                // 点开关时直接跳「安装未知应用」页，而不是弹一句无从下手的提示。
                spec(
                    CapabilityGate.PACKAGES, R.id.swCapPackages, R.id.rowCapPackages,
                    R.id.stCapPackages, R.string.setting_cap_packages,
                    R.string.setting_cap_packages_sub,
                    R.string.setting_cap_packages_need,
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                ),
                // 位置与相机是**运行时权限**：缺权时 permissionAvailable 返 false，
                // 开关会置灰；点它先当场弹系统授权框（requestCapPerm），被永久拒绝
                // 时再退回文案 + 应用详情页。
                spec(
                    CapabilityGate.LOCATION, R.id.swCapLocation, R.id.rowCapLocation,
                    R.id.stCapLocation, R.string.setting_cap_location,
                    R.string.setting_cap_location_sub,
                    R.string.setting_cap_location_need,
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                ),
                spec(
                    CapabilityGate.CAMERA, R.id.swCapCamera, R.id.rowCapCamera,
                    R.id.stCapCamera, R.string.setting_cap_camera,
                    R.string.setting_cap_camera_sub,
                    R.string.setting_cap_camera_need,
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                ),
            ),
        ),
    )

    private fun capSpecs(): List<CapSpec> = capDomains().flatMap { it.items }

    /**
     * 绑定一个能力磁贴（v1.6.0）。
     *
     * 交互分工（这是与旧版最大的不同）：
     *  - **开关本体**直接切换，不弹任何东西 —— 高频操作要一步到位；
     *  - **磁贴其余区域**进二级详情，说明文字、系统门槛、状态都放那里。
     *
     * 旧版把「切换」绑在整行上，说明文字又必须常驻，于是每行都得占 130dp 以上，
     * 20 项就是 4.9 屏。拆开之后每格只有标题 + 状态，一屏能放进 8~9 格。
     */
    private fun bindCapSwitch(spec: CapSpec) {
        val sw = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(spec.sw)
        val row = findViewById<View>(spec.row)

        val hint: () -> Unit = {
            // 相机/定位是运行时权限：先当场向系统要一次（弹系统授权框），
            // 被永久拒绝时系统不再弹窗、请求会立刻返回，这时才落到文案 + 跳转设置页。
            if (!requestCapPerm(spec.cap) && spec.need != 0) {
                Toast.makeText(this, getString(spec.need), Toast.LENGTH_LONG).show()
            }
            if (spec.action != null) openCapSettings(spec.action)
        }

        // 开关是快路径：点它只切换，不弹详情。
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked != CapabilityGate.enabled(this, spec.cap)) {
                if (spec.need != 0 && !CapabilityGate.permissionAvailable(this, spec.cap)) {
                    sw.isChecked = CapabilityGate.enabled(this, spec.cap)
                    hint()
                } else {
                    CapabilityGate.setEnabled(this, spec.cap, checked)
                    renderCapTile(spec)
                    refreshCapOverview()
                }
            }
        }

        // 点磁贴其余区域进详情：说明文字只在这里出现。
        row.setOnClickListener { showCapDetail(spec) }
        renderCapTile(spec)
    }

    /**
     * 一个磁贴的渲染：开关值 + 底部状态文字 + 门槛不满足时的视觉弱化。
     *
     * 状态用**文案与色彩双重编码**（不只靠开关位置）：色盲用户、以及「开着但系统
     * 没给权限」这种中间态，光看开关是分不出来的。
     */
    private fun renderCapTile(spec: CapSpec) {
        val sw = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(spec.sw) ?: return
        val on = CapabilityGate.enabled(this, spec.cap)
        val ok = CapabilityGate.permissionAvailable(this, spec.cap)
        sw.isChecked = on
        sw.isEnabled = ok
        sw.alpha = if (ok) 1f else 0.4f

        val st = findViewById<TextView>(spec.state) ?: return
        // Linux 多一档「已授权但还没下载」（v1.7.8）：这不是缺权限，是缺文件。
        // 两件事此前共用「待授权」文案，用户照提示去系统设置里找那一项 —— 而
        // 那里根本没有，因为 rootfs 是 App 自己下载的。现在分开显示，并保留
        // 开关可点（点开只是允许 AI 管理容器，不等于已经装好）。
        val notReady = spec.cap == CapabilityGate.LINUX && !CapabilityGate.linuxReady(this)
        when {
            !ok -> {
                st.text = getString(R.string.setting_cap_state_blocked)
                st.setTextColor(dshColor(R.attr.dshWarning, R.color.warning))
            }
            on && notReady -> {
                st.text = getString(R.string.linux_state_not_deployed)
                st.setTextColor(dshColor(R.attr.dshWarning, R.color.warning))
            }
            on -> {
                st.text = getString(R.string.state_on)
                st.setTextColor(dshColor(R.attr.dshSuccess, R.color.success))
            }
            else -> {
                st.text = getString(R.string.state_off)
                st.setTextColor(dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
            }
        }
    }

    /**
     * 这一格的「系统门槛提示」现在该不该显示。
     *
     * 与 [CapabilityGate.permissionAvailable] 故意不同：那个决定**开关能不能点**，
     * 这个决定**要不要提示还缺什么**。应用管理是最典型的分离场景 —— 列出已装应用
     * 不需要任何系统权限（开关必须可点），但安装需要「安装未知应用」授权（提示必须
     * 出现）。若两者共用一个判据，要么开关被误置灰，要么提示永远不出现。
     */
    private fun capNeedActive(spec: CapSpec): Boolean {
        if (spec.need == 0) return false
        if (spec.cap == CapabilityGate.PACKAGES) {
            return Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()
        }
        return !CapabilityGate.permissionAvailable(this, spec.cap)
    }

    /**
     * 运行时权限的当场申请（v1.6.x）。
     *
     * 为什么需要：相机与定位是**运行时权限**，而权限中心此前只会弹一句 Toast
     * 「去系统设置里授予」—— 用户被引导离开 App，多跳一层，还容易以为开关坏了。
     * 这里在开关点亮的瞬间直接调 requestPermissions，系统授权框当场弹出。
     *
     * 返回 false 表示「本次没能发起申请」：该能力不需要运行时权限（如 Linux / 应用列表），
     * 或者已被永久拒绝（系统不再弹窗，只能去设置页）—— 调用方据此决定是否再弹文案。
     */
    private fun requestCapPerm(cap: String): Boolean {
        val perms = when (cap) {
            CapabilityGate.CAMERA -> arrayOf(android.Manifest.permission.CAMERA)
            CapabilityGate.LOCATION -> arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            // 蓝牙（v1.7.10）：真机实测的缺口 —— 开关是亮的、一调就三条 500
            // （Need BLUETOOTH_CONNECT / BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE），
            // 而这里只处理 CAMERA 与 LOCATION，于是用户在界面上**根本没有地方**
            // 能授这三个权限，只能自己去系统设置里翻。它们都是 API 31+ 的运行时
            // 权限，本来就能当场弹框，没有理由不弹。
            CapabilityGate.BT -> arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
            )
            // 其余能力没有可在此申请的运行时权限：有的靠系统设置页（通知使用权），
            // 有的靠 root/Shizuku，有的根本不需要系统权限。返回 false 让调用方走文案。
            else -> return false
        }
        val missing = perms.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return false
        // shouldShowRequestPermissionRationale=false 且已被拒绝过一次时，系统不会再弹窗，
        // 直接返回空结果 —— 那种情况让调用方提示「去设置页」，避免用户点了没反应。
        requestPermissions(missing.toTypedArray(), REQ_CAP_RUNTIME)
        return true
    }

    /**
     * 跳到系统设置页补权限。
     *
     * 有一类 action **必须**带 `package:` data，否则会打开一张与目标无关的列表：
     *   · `ACTION_APPLICATION_DETAILS_SETTINGS` 不带 data → 「所有应用」列表；
     *   · `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 不带 data → 直接抛
     *     ActivityNotFoundException（系统要求按包授权，没有全局页）。
     * 通知使用权 / NFC 这类全局设置页反而不接受 data，所以只能按 action 分开处理。
     */
    private fun openCapSettings(action: String) {
        val perPackage = action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS ||
            action == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
        val intent = if (perPackage) {
            Intent(action, android.net.Uri.parse("package:$packageName"))
        } else {
            Intent(action)
        }
        runCatching { startActivity(intent) }
    }

    /**
     * 二级详情：说明 + 系统门槛 + 允许开关。
     *
     * 这里复用 showPrivDialog 的圆角背景与自绘布局，保持两种对话框观感一致。
     */
    private fun showCapDetail(spec: CapSpec) {
        val on = CapabilityGate.enabled(this, spec.cap)
        val ok = CapabilityGate.permissionAvailable(this, spec.cap)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        box.addView(TextView(this).apply {
            text = getString(spec.title)
            textSize = 17f
            setTextColor(dshColor(R.attr.dshTextPrimary, R.color.text_primary))
        })
        box.addView(TextView(this).apply {
            text = getString(spec.sub)
            textSize = 13f
            setTextColor(dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
        })
        // 「门槛提示」与「开关能否点」是两件事：应用管理的开关始终可点（列出应用本来
        // 就不需要权限），但「安装未知应用」没授权时安装会 403 —— 那条提示属于安装，
        // 不属于开关本身，所以单独用 capNeedActive 判定，不跟着 ok 走。
        if (capNeedActive(spec)) {
            box.addView(TextView(this).apply {
                text = getString(spec.need)
                textSize = 13f
                setTextColor(dshColor(R.attr.dshWarning, R.color.warning))
                setPadding(0, dp(10), 0, 0)
            })
            // 磁贴上的开关在缺权时是置灰的（点不动），所以「去补权限」必须在这里给一个
            // 明确的入口：否则用户看到的是「有个说明文字，但什么也点不了」。
            box.addView(TextView(this).apply {
                text = getString(R.string.setting_cap_grant_now)
                textSize = 14f
                setTextColor(dshColor(R.attr.dshAccent, R.color.accent))
                setPadding(0, dp(12), 0, 0)
                setOnClickListener {
                    if (!requestCapPerm(spec.cap)) {
                        Toast.makeText(
                            this@PermissionCenterActivity,
                            getString(spec.need),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    if (spec.action != null) openCapSettings(spec.action)
                }
            })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(4))
        }
        val label = TextView(this).apply {
            text = getString(R.string.setting_cap_detail_allow)
            textSize = 15f
            setTextColor(getColor(if (ok) R.color.text_primary else R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = on
            isEnabled = ok
            alpha = if (ok) 1f else 0.4f
        }
        row.addView(label)
        row.addView(sw)
        box.addView(row)

        val dialog = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton(getString(R.string.setting_cap_detail_close), null)
            .create()
        dialog.show()

        sw.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                CapabilityGate.setEnabled(this, spec.cap, false)
                renderCapTile(spec)
                refreshCapOverview()
                return@setOnCheckedChangeListener
            }
            if (spec.need != 0 && !CapabilityGate.permissionAvailable(this, spec.cap)) {
                // 系统权限不满足：开关弹回，并当场提示去哪补 —— 否则用户会以为开关坏了。
                // 能运行时申请的（相机/定位）直接弹系统授权框，不必让用户自己翻设置页。
                sw.isChecked = false
                if (!requestCapPerm(spec.cap)) {
                    Toast.makeText(this, getString(spec.need), Toast.LENGTH_LONG).show()
                }
                if (spec.action != null) openCapSettings(spec.action)
            } else {
                CapabilityGate.setEnabled(this, spec.cap, true)
                renderCapTile(spec)
                refreshCapOverview()
            }
        }
    }

    /**
     * 总览卡的三个数字：已开启 / 总数、百分比、系统授权进度。
     *
     * 「已开启」只算真正可用的（开关开 + 系统权限在），否则用户会看到一个
     * 开了但用不了的数字，比不显示更误导。
     */
    private fun refreshCapOverview() {
        val all = capSpecs()
        val enabled = all.count { CapabilityGate.can(this, it.cap) }
        val total = all.size
        findViewById<TextView>(R.id.txtCapTotal)?.text = "$enabled / $total"
        val pct = if (total == 0) 0 else enabled * 100 / total
        findViewById<TextView>(R.id.txtCapPct)?.text = "$pct%"
        findViewById<android.widget.ProgressBar>(R.id.barCapProgress)?.progress = pct

        // 系统授权四项（无障碍 / 文件 / 通知 / 麦克风）单独计数：它们不由这些开关控制，
        // 混进同一进度条会把「AI 能力开了多少」讲成「系统给了多少」，两件事分开报。
        val grants = listOf(
            DshAccessibilityService.isEnabled(),
            if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
            else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
            if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED else true,
            hasMicPermission(),
        ).count { it }

        findViewById<TextView>(R.id.txtCapOverviewSub)?.text = getString(
            R.string.setting_cap_overview_sub, enabled, total, grants, 4,
        )

        // 每个域的分母来自 XML 构建出的磁贴数，分子只算真正可用的。
        for (domain in capDomains()) {
            val n = domain.items.count { CapabilityGate.can(this, it.cap) }
            findViewById<TextView>(domain.count)?.text = "$n/${domain.items.size}"
        }
    }

    /** 从系统设置页返回后同步全部磁贴与总览（权限可能刚被授予或撤销）。 */
    private fun refreshCapSwitches() {
        for (spec in capSpecs()) renderCapTile(spec)
        refreshCapOverview()
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

        /** 能力磁贴的运行时权限请求码（相机 / 定位） */
        private const val REQ_CAP_RUNTIME = 4204
    }
}

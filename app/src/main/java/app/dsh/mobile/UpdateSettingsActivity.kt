package app.dsh.mobile

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.update.AppUpdatePrefs
import app.dsh.mobile.update.AppUpdateScheduler
import app.dsh.mobile.update.RuntimeStatusRow
import app.dsh.mobile.update.UpdateStatusCard

/**
 * 升级（v1.4.58：由「应用更新」更名并扩容）——设置→系统下的更新统一子页。
 *
 * 三个分区：
 *  1. 应用更新状态卡（[UpdateStatusCard]）：完整状态机（检查中/最新/可下载/
 *     下载中%/已下载/安装中/失败/重试）+ 主/次按钮，与自动流程同一条代码路径；
 *  2. 运行时更新（[RuntimeStatusRow]，v1.4.58 自关于页迁入）：拉起后台服务，
 *     进度看通知栏，行副标题常显当前已装运行时版本；
 *  3. 偏好（v1.4.46 六项）：自动检查、仅 WiFi、索引代理降级、安装包形态、
 *     已忽略版本、立即检查。
 *
 * 下载线路（运行时代理降级）仍在 设置→网络 —— 与运行时通用代理相邻更直观，
 * 未迁入本页（v1.4.58 决策）。
 */
class UpdateSettingsActivity : ThemedActivity() {

    /** 应用更新状态卡：onDestroy 时 stop() 取消订阅。 */
    private lateinit var statusCard: UpdateStatusCard

    /** 运行时更新行：同上。 */
    private lateinit var runtimeRow: RuntimeStatusRow

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.forcePortrait(this)
        setContentView(R.layout.activity_update_settings)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // 状态卡 + 运行时行（长按不跳 Release：旧入口习惯只保留在历史里，
        // 这里是收拢后的唯一入口，所有动作都摆在明面上）
        statusCard = UpdateStatusCard(this)
        statusCard.start()
        runtimeRow = RuntimeStatusRow(this)
        runtimeRow.start()

        bindUpdateSection()
    }

    override fun onDestroy() {
        statusCard.stop()
        runtimeRow.stop()
        super.onDestroy()
    }

    private fun bindUpdateSection() {
        val swAuto = findViewById<Switch>(R.id.swUpdateAutoCheck)
        swAuto.isChecked = AppUpdatePrefs.autoCheck(this)
        swAuto.setOnCheckedChangeListener { _, checked -> AppUpdatePrefs.setAutoCheck(this, checked) }

        val swWifi = findViewById<Switch>(R.id.swUpdateWifiOnly)
        swWifi.isChecked = AppUpdatePrefs.wifiOnly(this)
        swWifi.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                AppUpdatePrefs.setWifiOnly(this, true)
                return@setOnCheckedChangeListener
            }
            // 关掉"仅 WiFi"= 允许移动网络静默下 200MB+，第一次必须二次确认（§11）。
            // 取消则把开关拨回去（会再触发一次监听，那时 checked=true，走上面的分支）。
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_update_wifi_only)
                .setMessage(R.string.settings_update_wifi_warn)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    AppUpdatePrefs.setWifiOnly(this, false)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> swWifi.isChecked = true }
                .setOnCancelListener { swWifi.isChecked = true }
                .show()
        }

        val swProxy = findViewById<Switch>(R.id.swUpdateIndexProxy)
        swProxy.isChecked = AppUpdatePrefs.proxyFallback(this)
        swProxy.setOnCheckedChangeListener { _, checked ->
            AppUpdatePrefs.setProxyFallback(this, checked)
        }

        refreshUpdateStrategyRow()
        findViewById<LinearLayout>(R.id.rowUpdateStrategy)?.setOnClickListener {
            showUpdateStrategyDialog()
        }

        refreshIgnoredRow()
        findViewById<LinearLayout>(R.id.rowUpdateIgnored)?.setOnClickListener {
            if (AppUpdatePrefs.ignoredVersionCode(this) > 0) clearIgnoredVersion()
        }
        findViewById<TextView>(R.id.btnUpdateIgnoredClear)?.setOnClickListener {
            clearIgnoredVersion()
        }

        findViewById<LinearLayout>(R.id.rowUpdateCheckNow)?.setOnClickListener {
            AppUpdateScheduler.checkNow(this)
            Toast.makeText(this, R.string.settings_update_check_started, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshUpdateStrategyRow() {
        val sub = findViewById<TextView>(R.id.subUpdateStrategy) ?: return
        val auto = AppUpdatePrefs.packageStrategy(this) == AppUpdatePrefs.STRATEGY_AUTO
        sub.text = getString(
            if (auto) R.string.settings_update_strategy_auto else R.string.settings_update_strategy_keep,
        ) + " · " + getString(R.string.settings_update_strategy_desc)
    }

    private fun showUpdateStrategyDialog() {
        val auto = AppUpdatePrefs.packageStrategy(this) == AppUpdatePrefs.STRATEGY_AUTO
        val labels = arrayOf(
            getString(R.string.settings_update_strategy_auto),
            getString(R.string.settings_update_strategy_keep),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_update_strategy)
            .setSingleChoiceItems(labels, if (auto) 0 else 1) { d, which ->
                AppUpdatePrefs.setPackageStrategy(
                    this,
                    if (which == 0) AppUpdatePrefs.STRATEGY_AUTO else AppUpdatePrefs.STRATEGY_KEEP,
                )
                refreshUpdateStrategyRow()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 已忽略的版本行：显示版本名（拿不到就退回 versionCode）+「清除」按钮。 */
    private fun refreshIgnoredRow() {
        val sub = findViewById<TextView>(R.id.subUpdateIgnored) ?: return
        val clear = findViewById<TextView>(R.id.btnUpdateIgnoredClear) ?: return
        val code = AppUpdatePrefs.ignoredVersionCode(this)
        if (code <= 0) {
            sub.text = getString(R.string.settings_update_ignored_none)
            clear.visibility = android.view.View.GONE
            return
        }
        val name = AppUpdatePrefs.pending(this)?.takeIf { it.versionCode == code }?.versionName
        sub.text = name ?: getString(R.string.settings_update_ignored_code, code)
        clear.visibility = android.view.View.VISIBLE
    }

    private fun clearIgnoredVersion() {
        AppUpdatePrefs.clearIgnored(this)
        refreshIgnoredRow()
        Toast.makeText(this, R.string.settings_update_cleared, Toast.LENGTH_SHORT).show()
    }
}

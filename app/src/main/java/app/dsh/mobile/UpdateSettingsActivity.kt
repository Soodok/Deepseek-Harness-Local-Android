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

/**
 * 应用更新（v1.4.46）：从设置主页收拢的更新配置子页。
 *
 * 六个条目：打开时自动检查、仅 WiFi 下载、索引代理降级、安装包形态（智能/保持）、
 * 已忽略版本、立即检查。都是低频配置，放主页会和日常设置抢注意力。
 *
 * 四个开关的读写都在 [AppUpdatePrefs] 里，这里只做绑定 —— 默认值全部是"安全"侧
 * （自动检查开、仅 WiFi 开、代理降级开、形态智能），所以**不绑定也不会崩**，
 * 但用户就没有任何手段关掉它们，等于把选择权锁死在默认值上。
 */
class UpdateSettingsActivity : ThemedActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyTo(this)
        setContentView(R.layout.activity_update_settings)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        bindUpdateSection()
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

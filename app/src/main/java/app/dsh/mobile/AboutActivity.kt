package app.dsh.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.RepoConfig
import app.dsh.mobile.update.AppUpdateBus
import app.dsh.mobile.update.AppUpdatePrefs
import app.dsh.mobile.update.AppUpdateScheduler
import app.dsh.mobile.update.NetworkState
import app.dsh.mobile.update.Phase
import app.dsh.mobile.update.RuntimePhase
import app.dsh.mobile.update.RuntimeUpdateBus
import app.dsh.mobile.update.RuntimeUpdateService
import app.dsh.mobile.update.RuntimeUpdateState
import app.dsh.mobile.update.UpdateDialogs
import app.dsh.mobile.update.UpdateState
import app.dsh.mobile.update.UpdateTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关于页（MIUI 风格）：应用标识卡 + 介绍/特性 + 开源地址（点击复制）。
 *
 * v1.5.0 起两行状态机化：
 *  - `rowUpdate`：检查 / 后台下载 / 立即安装 / 前往 Release（长按）——
 *    **旧入口（跳 Release 页）保留**，被忽略的版本仍可在这里手动更新；
 *  - `rowRuntime`：不再在页内等下载，改为拉起 `RuntimeUpdateService`，
 *    进度看通知栏，退出本页不影响。
 */
class AboutActivity : ThemedActivity() {

    /** 页面级协程作用域：onDestroy 取消，避免订阅泄漏。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyTo(this)
        setContentView(R.layout.activity_about)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.txtVersion).text =
            getString(R.string.about_version, versionName())

        // 开源地址：点击复制到剪贴板（WebView 只放行回环，外链交系统浏览器亦可，复制更轻）
        findViewById<android.view.View>(R.id.rowRepo).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dsh-android", getString(R.string.about_repo)))
            Toast.makeText(this, getString(R.string.about_copied), Toast.LENGTH_SHORT).show()
        }

        // QQ 交流群：点击复制群号 —— 与开源地址同样的轻交互。
        // 不尝试 deep link 拉起 QQ：加群链接格式各版本不一，未安装时会失败。
        findViewById<android.view.View>(R.id.rowQqGroup).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText("qq-group", getString(R.string.about_qq_group_id)),
            )
            Toast.makeText(this, getString(R.string.about_qq_group_copied), Toast.LENGTH_SHORT).show()
        }

        // 应用更新：点击 = 当前状态对应的主动作；长按 = 前往 Release（旧入口，R10）
        findViewById<android.view.View>(R.id.rowUpdate).apply {
            setOnClickListener { onUpdateRowClick() }
            setOnLongClickListener {
                UpdateDialogs.openReleasePage(this@AboutActivity, AppUpdateBus.state.value.info)
                true
            }
        }

        // 运行时更新：拉起后台服务（页内不再等待），进度在通知栏
        findViewById<android.view.View>(R.id.rowRuntime).setOnClickListener {
            if (UpdateTasks.isBusy()) {
                Toast.makeText(this, R.string.app_update_busy, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (RuntimeUpdateService.start(this)) {
                findViewById<TextView>(R.id.txtRuntime).text =
                    getString(R.string.about_runtime_bg_started)
            } else {
                Toast.makeText(this, R.string.app_update_busy, Toast.LENGTH_SHORT).show()
            }
        }

        // 订阅状态（服务在跑、页面可能后进 → 用 StateFlow 拿当前值）
        scope.launch { AppUpdateBus.state.collect { renderUpdate(it) } }
        scope.launch { RuntimeUpdateBus.state.collect { renderRuntime(it) } }

        renderUpdate(AppUpdateBus.state.value)
        renderRuntime(RuntimeUpdateBus.state.value)
    }

    override fun onResume() {
        super.onResume()
        // 回到前台：距上次检查超过 24h 则补一次（§6）
        AppUpdateScheduler.onForeground(this)
        UpdateDialogs.maybeShow(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 更新行

    /**
     * 主按钮的状态机（§12）。所有分支都走与自动流程同一条代码路径，
     * 避免"手动能装、自动装不上"的分叉。
     */
    private fun onUpdateRowClick() {
        val state = AppUpdateBus.state.value
        val info = state.info

        // 1) 已经下好了 → 直接进安装
        if (AppUpdatePrefs.downloadedApk(this) != null) {
            UpdateDialogs.startInstall(this)
            return
        }
        // 2) 等用户选形态（运行时变更）
        if (state.phase == Phase.NEEDS_CHOICE && state.choices.size == 2) {
            UpdateDialogs.showChoice(this, state.choices[0], state.choices[1])
            return
        }
        // 3) 有可用更新（含"被忽略的版本"手动更新）→ 后台下载
        if (info != null && (state.phase == Phase.AVAILABLE || state.phase == Phase.CANCELLED ||
                state.phase == Phase.FAILED)
        ) {
            UpdateDialogs.ensureDownload(this, info)
            return
        }
        // 4) 下载中 → 提示看通知栏（不打断任务）
        if (state.phase == Phase.DOWNLOADING || state.phase == Phase.CHECKING) {
            Toast.makeText(this, R.string.about_update_tap_hint, Toast.LENGTH_SHORT).show()
            return
        }
        // 5) 其它（已最新 / 拿不到信息 / 空闲）→ 再查一次
        findViewById<TextView>(R.id.txtUpdate).text = getString(R.string.about_update_checking)
        AppUpdateScheduler.checkNow(this)
    }

    private fun renderUpdate(state: UpdateState) {
        val tv = findViewById<TextView>(R.id.txtUpdate) ?: return
        val info = state.info
        val ignored = info != null && AppUpdatePrefs.isIgnored(this, info.versionCode)
        when (state.phase) {
            Phase.CHECKING -> {
                tv.text = getString(R.string.about_update_checking)
                tv.setTextColor(getColor(R.color.text_secondary))
            }

            Phase.UP_TO_DATE -> {
                tv.text = getString(
                    R.string.about_update_up_to_date,
                    state.message ?: versionName(),
                    lastCheckText(),
                )
                tv.setTextColor(getColor(R.color.success))
            }

            Phase.AVAILABLE, Phase.CANCELLED -> {
                if (info == null) {
                    tv.text = getString(R.string.about_update_idle)
                    return
                }
                if (ignored) {
                    tv.text = getString(R.string.about_update_ignored, info.versionName)
                } else if (AppUpdatePrefs.wifiOnly(this) && !NetworkState.isUnmetered(this)) {
                    tv.text = getString(
                        R.string.about_update_available_metered,
                        info.versionName,
                        info.sizeMb,
                    )
                } else {
                    tv.text = getString(R.string.about_update_available, info.versionName, info.sizeMb)
                }
                tv.setTextColor(Accent.current(applicationContext).argb)
            }

            Phase.NEEDS_CHOICE -> {
                val target = state.choices.getOrNull(1) ?: info
                if (target == null) {
                    tv.text = getString(R.string.about_update_idle)
                    return
                }
                tv.text = getString(
                    R.string.about_update_available,
                    target.versionName,
                    target.sizeMb,
                )
                tv.setTextColor(Accent.current(applicationContext).argb)
            }

            Phase.DOWNLOADING -> {
                tv.text = getString(R.string.about_update_downloading, state.progress)
                tv.setTextColor(Accent.current(applicationContext).argb)
            }

            Phase.VERIFYING -> {
                tv.text = getString(R.string.about_update_downloading, state.progress)
                tv.setTextColor(Accent.current(applicationContext).argb)
            }

            Phase.DOWNLOADED -> {
                tv.text = getString(
                    R.string.about_update_ready,
                    info?.versionName ?: versionName(),
                )
                tv.setTextColor(getColor(R.color.success))
            }

            Phase.INSTALLING -> {
                tv.text = getString(R.string.about_update_installing)
                tv.setTextColor(Accent.current(applicationContext).argb)
            }

            Phase.FAILED -> {
                tv.text = getString(
                    R.string.about_update_install_failed,
                    state.message ?: "—",
                )
                tv.setTextColor(getColor(R.color.danger))
            }

            Phase.UNAVAILABLE -> {
                tv.text = getString(
                    R.string.about_update_unavailable,
                    state.message ?: "—",
                )
                tv.setTextColor(getColor(R.color.text_secondary))
            }

            Phase.IDLE -> {
                tv.text = getString(
                    R.string.about_update_up_to_date,
                    versionName(),
                    lastCheckText(),
                )
                tv.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    // ------------------------------------------------------------ 运行时行

    private fun renderRuntime(state: RuntimeUpdateState) {
        val tv = findViewById<TextView>(R.id.txtRuntime) ?: return
        when (state.phase) {
            RuntimePhase.IDLE -> {
                tv.text = getString(R.string.about_runtime_idle)
                tv.setTextColor(getColor(R.color.text_secondary))
            }

            RuntimePhase.PREPARING -> {
                tv.text = getString(R.string.about_runtime_checking_bg)
                tv.setTextColor(getColor(R.color.text_secondary))
            }

            RuntimePhase.DOWNLOADING -> {
                tv.text = getString(R.string.app_update_runtime_downloading, state.progress)
                tv.setTextColor(Accent.current(applicationContext).argb)
            }

            RuntimePhase.EXTRACTING -> {
                tv.text = getString(R.string.app_update_runtime_extracting, state.progress)
                tv.setTextColor(Accent.current(applicationContext).argb)
            }

            RuntimePhase.DONE -> {
                tv.text = getString(R.string.about_runtime_latest, state.version)
                tv.setTextColor(getColor(R.color.success))
            }

            RuntimePhase.CANCELLED -> {
                tv.text = getString(R.string.app_update_cancelled)
                tv.setTextColor(getColor(R.color.text_secondary))
            }

            RuntimePhase.FAILED -> {
                tv.text = getString(R.string.about_runtime_failed, state.message ?: "—")
                tv.setTextColor(getColor(R.color.danger))
            }
        }
    }

    // ------------------------------------------------------------ 工具

    private fun lastCheckText(): String {
        val at = AppUpdatePrefs.lastCheckAt(this)
        if (at <= 0) return "—"
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"

    private companion object {
        const val TAG = "About"
    }
}

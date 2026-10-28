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
import app.dsh.mobile.update.failureText
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
 * v1.4.37 起两行状态机化：
 *  - `rowUpdate`：主按钮（检查 / 后台下载 / 查看进度 / 立即安装 / 重试）+ 次按钮
 *    （前往 Release / 删除安装包），长按也跳 Release —— **旧入口（跳 Release 页）保留**，
 *    被忽略的版本仍可在这里手动更新；
 *  - `rowRuntime`：不再在页内等下载，改为拉起 `RuntimeUpdateService`，
 *    进度看通知栏，退出本页不影响。
 */
class AboutActivity : ThemedActivity() {

    /** 页面级协程作用域：onDestroy 取消，避免订阅泄漏。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 次按钮当前动作（随状态变化）。
     *
     * 默认「前往 Release」（old entry, R10）；DOWNLOADED 时换成「删除安装包」——
     * 用户下完之后改主意了，得能把这 200MB 清掉，否则只能等 7 天清理。
     */
    private var secondaryAction: (() -> Unit)? = null

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

        // fork 构建要显式声明身份：仓库地址按构建期 RepoConfig 显示，
        // 避免把 XiaoPb 的包看成上游正式包（两者签名与发版节奏都不同）。
        findViewById<TextView>(R.id.txtRepo).text = "github.com/" + RepoConfig.REPO
        findViewById<TextView>(R.id.txtForkBadge).visibility =
            if (RepoConfig.IS_FORK) android.view.View.VISIBLE else android.view.View.GONE

        // 开源地址：点击复制到剪贴板（WebView 只放行回环，外链交系统浏览器亦可，复制更轻）
        findViewById<android.view.View>(R.id.rowRepo).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText("dsh-android", "https://github.com/" + RepoConfig.REPO),
            )
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

        // Telegram 交流群（v1.4.38 上游）：与 QQ 不同，Telegram 有统一的 t.me 邀请链接，
        // 可以直接 ACTION_VIEW 打开；没装 Telegram 时系统会用浏览器兜底。
        // 链接里带邀请 token，所以放在 strings.xml 而不是写死在代码里。
        findViewById<android.view.View>(R.id.rowTelegram).setOnClickListener {
            val url = getString(R.string.about_telegram_url)
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url),
            )
            runCatching { startActivity(intent) }.onFailure {
                // 没有浏览器也没有 Telegram：复制链接，至少让用户能自己粘出去
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("telegram", url))
                Toast.makeText(this, getString(R.string.about_telegram_copied), Toast.LENGTH_SHORT).show()
            }
        }

        // 应用更新：主按钮 = 当前状态对应的主动作；次按钮 = 前往 Release / 删除安装包；
        // 长按仍然跳 Release（老用户习惯），三者最终都走 UpdateDialogs 的同一套路径（§12）
        findViewById<android.view.View>(R.id.rowUpdate).apply {
            setOnClickListener { onUpdateRowClick() }
            setOnLongClickListener {
                openReleasePage()
                true
            }
        }
        findViewById<TextView>(R.id.btnUpdatePrimary).setOnClickListener { onUpdateRowClick() }
        findViewById<TextView>(R.id.btnUpdateSecondary).setOnClickListener { secondaryAction?.invoke() }

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
        // 进程重启后 bus 回到 IDLE，但安装包还躺在 filesDir/update 里 —— 这时按
        // "已下载待安装"渲染，否则会出现"显示已是最新、点一下却开始装"的自相矛盾。
        val phase = if (state.phase == Phase.IDLE && AppUpdatePrefs.downloadedApk(this) != null) {
            Phase.DOWNLOADED
        } else {
            state.phase
        }
        when (phase) {
            Phase.CHECKING -> {
                tv.text = getString(R.string.about_update_checking)
                tv.setTextColor(getColor(R.color.text_secondary))
                bindActions(R.string.about_update_checking, primaryEnabled = false)
            }

            Phase.UP_TO_DATE -> {
                tv.text = getString(
                    R.string.about_update_up_to_date,
                    state.message ?: versionName(),
                    lastCheckText(),
                )
                tv.setTextColor(getColor(R.color.success))
                bindActions(R.string.about_check_update)
            }

            Phase.AVAILABLE, Phase.CANCELLED -> {
                if (info == null) {
                    tv.text = getString(R.string.about_update_idle)
                    bindActions(R.string.about_check_update)
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
                bindActions(
                    R.string.about_update_action_download,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.NEEDS_CHOICE -> {
                val target = state.choices.getOrNull(1) ?: info
                if (target == null) {
                    tv.text = getString(R.string.about_update_idle)
                    bindActions(R.string.about_check_update)
                    return
                }
                tv.text = getString(
                    R.string.about_update_available,
                    target.versionName,
                    target.sizeMb,
                )
                tv.setTextColor(Accent.current(applicationContext).argb)
                bindActions(
                    R.string.about_update_action_choose,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.DOWNLOADING -> {
                tv.text = getString(R.string.about_update_downloading, state.progress)
                tv.setTextColor(Accent.current(applicationContext).argb)
                bindActions(
                    R.string.about_update_action_progress,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.VERIFYING -> {
                tv.text = getString(R.string.about_update_downloading, state.progress)
                tv.setTextColor(Accent.current(applicationContext).argb)
                bindActions(
                    R.string.about_update_action_progress,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.DOWNLOADED -> {
                tv.text = getString(
                    R.string.about_update_ready,
                    info?.versionName ?: versionName(),
                )
                tv.setTextColor(getColor(R.color.success))
                bindActions(
                    R.string.app_update_dlg_install,
                    R.string.about_update_delete_apk,
                    ::deleteDownloadedPackage,
                )
            }

            Phase.INSTALLING -> {
                tv.text = getString(R.string.about_update_installing)
                tv.setTextColor(Accent.current(applicationContext).argb)
                bindActions(R.string.about_update_installing, primaryEnabled = false)
            }

            Phase.FAILED -> {
                tv.text = getString(
                    R.string.about_update_install_failed,
                    state.message ?: "—",
                )
                tv.setTextColor(getColor(R.color.danger))
                bindActions(
                    R.string.about_update_action_retry,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.UNAVAILABLE -> {
                // 只显示分类后的人话；异常原文（URL / HTTP 码）留在 logcat
                tv.text = state.failureText(this)
                tv.setTextColor(getColor(R.color.text_secondary))
                bindActions(
                    R.string.about_update_action_retry,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.IDLE -> {
                tv.text = getString(
                    R.string.about_update_up_to_date,
                    versionName(),
                    lastCheckText(),
                )
                tv.setTextColor(getColor(R.color.text_secondary))
                bindActions(R.string.about_check_update)
            }
        }
    }

    /**
     * 绑定主/次按钮（§12 表）。
     *
     * @param secondaryRes 为空则隐藏次按钮（"已是最新"这类只有一个动作的状态）
     */
    private fun bindActions(
        primaryRes: Int,
        secondaryRes: Int? = null,
        secondary: (() -> Unit)? = null,
        primaryEnabled: Boolean = true,
    ) {
        val primary = findViewById<TextView>(R.id.btnUpdatePrimary) ?: return
        primary.text = getString(primaryRes)
        primary.isEnabled = primaryEnabled
        primary.alpha = if (primaryEnabled) 1f else 0.6f

        val sBtn = findViewById<TextView>(R.id.btnUpdateSecondary) ?: return
        if (secondaryRes == null || secondary == null) {
            sBtn.visibility = android.view.View.GONE
            secondaryAction = null
        } else {
            sBtn.text = getString(secondaryRes)
            sBtn.visibility = android.view.View.VISIBLE
            secondaryAction = secondary
        }
    }

    private fun openReleasePage() {
        UpdateDialogs.openReleasePage(this, AppUpdateBus.state.value.info)
    }

    /** 「删除安装包」：删掉当前已下载的版本目录，状态回到"有更新可再下"。 */
    private fun deleteDownloadedPackage() {
        val code = AppUpdatePrefs.downloadedCode(this)
        val info = AppUpdateBus.state.value.info ?: AppUpdatePrefs.pending(this)
        AppUpdatePrefs.deleteDownloaded(this)
        if (info != null && code > 0 && info.versionCode == code) {
            AppUpdateBus.set(
                UpdateState(
                    phase = Phase.AVAILABLE,
                    info = info,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        } else {
            AppUpdateBus.phase(Phase.IDLE)
        }
        Toast.makeText(this, R.string.about_update_apk_deleted, Toast.LENGTH_SHORT).show()
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

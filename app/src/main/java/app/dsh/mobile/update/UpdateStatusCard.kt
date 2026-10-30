package app.dsh.mobile.update

import android.app.Activity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.Accent
import app.dsh.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用更新状态卡（v1.4.58）：AboutActivity 与 UpdateSettingsActivity 共用的
 * 同一套状态机渲染 + 动作分发（§12 表）。
 *
 * 抽成公共组件的原因：两页各自维护一份 renderUpdate/onUpdateRowClick 曾出现
 * 行为漂移（关于页改了设置页没跟）。现在两页都 `UpdateStatusCard(activity).start()`
 * —— 布局里的 id 约定：`rowUpdate` / `txtUpdate` / `btnUpdatePrimary` /
 * `btnUpdateSecondary`，缺哪个就跳过哪个（比如只渲染不带按钮的紧凑形态）。
 *
 * 生命周期：`start()` 订阅 [AppUpdateBus]，页面 `onDestroy` 时调 [stop]。
 */
class UpdateStatusCard(
    private val activity: Activity,
    private val rowId: Int = R.id.rowUpdate,
    private val txtId: Int = R.id.txtUpdate,
    private val primaryId: Int = R.id.btnUpdatePrimary,
    private val secondaryId: Int = R.id.btnUpdateSecondary,
) {

    /** 页面级协程作用域：stop() 取消，避免订阅泄漏。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 次按钮当前动作（随状态变化）。
     *
     * 默认「前往 Release」（old entry, R10）；DOWNLOADED 时换成「删除安装包」——
     * 用户下完之后改主意了，得能把这 200MB 清掉，否则只能等 7 天清理。
     */
    private var secondaryAction: (() -> Unit)? = null

    /**
     * 绑定点击 + 订阅状态流。长按行为由宿主决定（关于页=跳 Release 的旧习惯，
     * 设置子页可传 null 不启用）。
     */
    fun start(onRowLongClick: (() -> Unit)? = null) {
        activity.findViewById<View>(rowId)?.apply {
            setOnClickListener { onPrimaryClick() }
            if (onRowLongClick != null) {
                setOnLongClickListener {
                    onRowLongClick.invoke()
                    true
                }
            }
        }
        activity.findViewById<TextView>(primaryId)?.setOnClickListener { onPrimaryClick() }
        activity.findViewById<TextView>(secondaryId)?.setOnClickListener { secondaryAction?.invoke() }

        // 订阅状态（服务在跑、页面可能后进 → 用 StateFlow 拿当前值）
        scope.launch { AppUpdateBus.state.collect { render(it) } }
        render(AppUpdateBus.state.value)
    }

    /** 页面 onDestroy 时调用，取消订阅。 */
    fun stop() {
        scope.cancel()
    }

    // ------------------------------------------------------------ 动作分发

    /**
     * 主按钮的状态机（§12）。所有分支都走与自动流程同一条代码路径，
     * 避免"手动能装、自动装不上"的分叉。
     */
    private fun onPrimaryClick() {
        // 点击时再归一化一次（幂等、开销为读 prefs）：防陈旧"已下载"状态触发无意义的重装
        AppUpdatePrefs.clearStaleInstallState(activity)
        val state = AppUpdateBus.state.value
        val info = state.info

        // 1) 已经下好了 → 直接进安装
        if (AppUpdatePrefs.downloadedApk(activity) != null) {
            UpdateDialogs.startInstall(activity)
            return
        }
        // 2) 等用户选形态（运行时变更）
        if (state.phase == Phase.NEEDS_CHOICE && state.choices.size == 2) {
            UpdateDialogs.showChoice(activity, state.choices[0], state.choices[1])
            return
        }
        // 3) 有可用更新（含"被忽略的版本"手动更新）→ 后台下载
        if (info != null && (state.phase == Phase.AVAILABLE || state.phase == Phase.CANCELLED ||
                state.phase == Phase.FAILED)
        ) {
            UpdateDialogs.ensureDownload(activity, info)
            return
        }
        // 4) 下载中 → 提示看通知栏（不打断任务）
        if (state.phase == Phase.DOWNLOADING || state.phase == Phase.CHECKING) {
            Toast.makeText(activity, R.string.about_update_tap_hint, Toast.LENGTH_SHORT).show()
            return
        }
        // 5) 其它（已最新 / 拿不到信息 / 空闲）→ 再查一次
        activity.findViewById<TextView>(txtId)?.text =
            activity.getString(R.string.about_update_checking)
        AppUpdateScheduler.checkNow(activity)
    }

    private fun openReleasePage() {
        UpdateDialogs.openReleasePage(activity, AppUpdateBus.state.value.info)
    }

    /** 「删除安装包」：删掉当前已下载的版本目录，状态回到"有更新可再下"。 */
    private fun deleteDownloadedPackage() {
        val code = AppUpdatePrefs.downloadedCode(activity)
        val info = AppUpdateBus.state.value.info ?: AppUpdatePrefs.pending(activity)
        AppUpdatePrefs.deleteDownloaded(activity)
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
        Toast.makeText(activity, R.string.about_update_apk_deleted, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 渲染

    private fun render(state: UpdateState) {
        val tv = activity.findViewById<TextView>(txtId) ?: return
        val info = state.info
        val ignored = info != null && AppUpdatePrefs.isIgnored(activity, info.versionCode)
        // 进程重启后 bus 回到 IDLE，但安装包还躺在 filesDir/update 里 —— 这时按
        // "已下载待安装"渲染，否则会出现"显示已是最新、点一下却开始装"的自相矛盾。
        // ⚠️ 只有安装包版本**比当前已安装的新**才成立；≤ 已安装版本是覆盖安装
        // 杀进程后没清理的尸体状态，拿来渲染就成了"已是最新版还提示可更新"（v1.4.56 修）。
        val phase = if (state.phase == Phase.IDLE &&
            AppUpdatePrefs.downloadedApk(activity) != null &&
            AppUpdatePrefs.downloadedCode(activity) > installedVersionCode()
        ) {
            Phase.DOWNLOADED
        } else {
            state.phase
        }
        when (phase) {
            Phase.CHECKING -> {
                tv.text = activity.getString(R.string.about_update_checking)
                tv.setTextColor(activity.getColor(R.color.text_secondary))
                bindActions(R.string.about_update_checking, primaryEnabled = false)
            }

            Phase.UP_TO_DATE -> {
                tv.text = activity.getString(
                    R.string.about_update_up_to_date,
                    state.message ?: versionName(),
                    lastCheckText(),
                )
                tv.setTextColor(activity.getColor(R.color.success))
                bindActions(R.string.about_check_update)
            }

            Phase.AVAILABLE, Phase.CANCELLED -> {
                if (info == null) {
                    tv.text = activity.getString(R.string.about_update_idle)
                    bindActions(R.string.about_check_update)
                    return
                }
                if (ignored) {
                    tv.text = activity.getString(R.string.about_update_ignored, info.versionName)
                } else if (AppUpdatePrefs.wifiOnly(activity) && !NetworkState.isUnmetered(activity)) {
                    tv.text = activity.getString(
                        R.string.about_update_available_metered,
                        info.versionName,
                        info.sizeMb,
                    )
                } else {
                    tv.text = activity.getString(R.string.about_update_available, info.versionName, info.sizeMb)
                }
                tv.setTextColor(Accent.current(activity.applicationContext).argb)
                bindActions(
                    R.string.about_update_action_download,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.NEEDS_CHOICE -> {
                val target = state.choices.getOrNull(1) ?: info
                if (target == null) {
                    tv.text = activity.getString(R.string.about_update_idle)
                    bindActions(R.string.about_check_update)
                    return
                }
                tv.text = activity.getString(
                    R.string.about_update_available,
                    target.versionName,
                    target.sizeMb,
                )
                tv.setTextColor(Accent.current(activity.applicationContext).argb)
                bindActions(
                    R.string.about_update_action_choose,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.DOWNLOADING, Phase.VERIFYING -> {
                tv.text = activity.getString(R.string.about_update_downloading, state.progress)
                tv.setTextColor(Accent.current(activity.applicationContext).argb)
                bindActions(
                    R.string.about_update_action_progress,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.DOWNLOADED -> {
                tv.text = activity.getString(
                    R.string.about_update_ready,
                    info?.versionName ?: versionName(),
                )
                tv.setTextColor(activity.getColor(R.color.success))
                bindActions(
                    R.string.app_update_dlg_install,
                    R.string.about_update_delete_apk,
                    ::deleteDownloadedPackage,
                )
            }

            Phase.INSTALLING -> {
                tv.text = activity.getString(R.string.about_update_installing)
                tv.setTextColor(Accent.current(activity.applicationContext).argb)
                bindActions(R.string.about_update_installing, primaryEnabled = false)
            }

            Phase.FAILED -> {
                tv.text = activity.getString(
                    R.string.about_update_install_failed,
                    state.message ?: "—",
                )
                tv.setTextColor(activity.getColor(R.color.danger))
                bindActions(
                    R.string.about_update_action_retry,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.UNAVAILABLE -> {
                // 只显示分类后的人话；异常原文（URL / HTTP 码）留在 logcat
                tv.text = state.failureText(activity)
                tv.setTextColor(activity.getColor(R.color.text_secondary))
                bindActions(
                    R.string.about_update_action_retry,
                    R.string.about_update_release,
                    ::openReleasePage,
                )
            }

            Phase.IDLE -> {
                tv.text = activity.getString(
                    R.string.about_update_up_to_date,
                    versionName(),
                    lastCheckText(),
                )
                tv.setTextColor(activity.getColor(R.color.text_secondary))
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
        val primary = activity.findViewById<TextView>(primaryId) ?: return
        primary.text = activity.getString(primaryRes)
        primary.isEnabled = primaryEnabled
        primary.alpha = if (primaryEnabled) 1f else 0.6f

        val sBtn = activity.findViewById<TextView>(secondaryId) ?: return
        if (secondaryRes == null || secondary == null) {
            sBtn.visibility = View.GONE
            secondaryAction = null
        } else {
            sBtn.text = activity.getString(secondaryRes)
            sBtn.visibility = View.VISIBLE
            secondaryAction = secondary
        }
    }

    // ------------------------------------------------------------ 工具

    private fun lastCheckText(): String {
        val at = AppUpdatePrefs.lastCheckAt(activity)
        if (at <= 0) return "—"
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }

    private fun versionName(): String =
        runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }
            .getOrNull() ?: "?"

    /** 当前已安装的 versionCode（渲染兜底的比较基准）。 */
    private fun installedVersionCode(): Int = runCatching {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pi.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") pi.versionCode
        }
    }.getOrDefault(0)
}

/**
 * 运行时更新行（v1.4.58）：从关于页迁出的渲染 + 动作，供升级子页复用。
 *
 * 交互与 v1.4.20 一致：点击拉起 [RuntimeUpdateService]，页内不等待，
 * 进度看通知栏；这里同时订阅 [RuntimeUpdateBus] 把进度映到行副标题。
 */
class RuntimeStatusRow(
    private val activity: Activity,
    private val rowId: Int = R.id.rowRuntime,
    private val txtId: Int = R.id.txtRuntime,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        activity.findViewById<View>(rowId)?.setOnClickListener {
            val tv = activity.findViewById<TextView>(txtId)
            if (UpdateTasks.isBusy()) {
                Toast.makeText(activity, R.string.app_update_busy, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (RuntimeUpdateService.start(activity)) {
                tv?.text = activity.getString(R.string.about_runtime_bg_started)
            } else {
                Toast.makeText(activity, R.string.app_update_busy, Toast.LENGTH_SHORT).show()
            }
        }
        // IDLE 时显示当前已装运行时版本（About 页时代没有的能力，顺手补上）
        val tv = activity.findViewById<TextView>(txtId)
        if (tv != null && RuntimeUpdateBus.state.value.phase == RuntimePhase.IDLE) {
            val ver = AppUpdateChecker.installedRuntime(activity)?.version
            if (!ver.isNullOrBlank()) {
                tv.text = activity.getString(R.string.settings_runtime_current, ver)
            }
        }
        scope.launch { RuntimeUpdateBus.state.collect { render(it) } }
    }

    fun stop() {
        scope.cancel()
    }

    private fun render(state: RuntimeUpdateState) {
        val tv = activity.findViewById<TextView>(txtId) ?: return
        when (state.phase) {
            RuntimePhase.IDLE -> {
                val ver = AppUpdateChecker.installedRuntime(activity)?.version
                tv.text = if (ver.isNullOrBlank()) {
                    activity.getString(R.string.about_runtime_idle)
                } else {
                    activity.getString(R.string.settings_runtime_current, ver)
                }
                tv.setTextColor(activity.getColor(R.color.text_secondary))
            }

            RuntimePhase.PREPARING -> {
                tv.text = activity.getString(R.string.about_runtime_checking_bg)
                tv.setTextColor(activity.getColor(R.color.text_secondary))
            }

            RuntimePhase.DOWNLOADING -> {
                tv.text = activity.getString(R.string.app_update_runtime_downloading, state.progress)
                tv.setTextColor(Accent.current(activity.applicationContext).argb)
            }

            RuntimePhase.EXTRACTING -> {
                tv.text = activity.getString(R.string.app_update_runtime_extracting, state.progress)
                tv.setTextColor(Accent.current(activity.applicationContext).argb)
            }

            RuntimePhase.DONE -> {
                tv.text = activity.getString(R.string.about_runtime_latest, state.version)
                tv.setTextColor(activity.getColor(R.color.success))
            }

            RuntimePhase.CANCELLED -> {
                tv.text = activity.getString(R.string.app_update_cancelled)
                tv.setTextColor(activity.getColor(R.color.text_secondary))
            }

            RuntimePhase.FAILED -> {
                tv.text = activity.getString(R.string.about_runtime_failed, state.message ?: "—")
                tv.setTextColor(activity.getColor(R.color.danger))
            }
        }
    }
}

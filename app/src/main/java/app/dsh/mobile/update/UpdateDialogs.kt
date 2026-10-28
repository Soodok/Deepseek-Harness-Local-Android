package app.dsh.mobile.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import app.dsh.mobile.DshApp
import app.dsh.mobile.R

/**
 * 三种可忽略弹窗 + 安装入口。
 *
 * ## "可忽略"的三层语义（§10）
 * - **稍后**：本次不处理，任务保留；下次时机（下次打开 / 24h / 连 WiFi / 手动）可再提示；
 * - **忽略此版本**：记 versionCode，该版本不再自动提示，但**关于页仍可手动更新**；
 * - **点外部/返回键**：等价"稍后"，不写忽略记录。
 *
 * 所有动作都走与自动流程**同一条代码路径**（[AppUpdateDownloadService] / [ApkInstaller]），
 * 避免"手动能装、自动装不上"的分叉 bug（§12）。
 */
object UpdateDialogs {

    private const val TAG = "UpdateDialogs"

    /** 同一时刻只允许一个弹窗（bus 变化会多次触发）。 */
    @Volatile
    private var showing = false

    /** 由 Activity 在 onResume / 状态变化时调用；没有该弹的东西就什么都不做。 */
    fun maybeShow(activity: Activity) {
        if (activity.isFinishing || showing) return
        val state = AppUpdateBus.state.value
        when (state.phase) {
            Phase.NEEDS_CHOICE -> {
                val pair = state.choices
                val info = state.info
                if (pair.size == 2 && info != null &&
                    AppUpdatePrefs.modeChoiceCode(activity) != info.versionCode
                ) {
                    showChoice(activity, pair[0], pair[1])
                }
            }

            Phase.AVAILABLE -> {
                val info = state.info ?: return
                if (AppUpdatePrefs.isIgnored(activity, info.versionCode)) return
                if (AppUpdatePrefs.promptedCode(activity) == info.versionCode) return
                // 不计费网络时已经在后台下了，不用问；计费网络才需要征得同意
                if (AppUpdatePrefs.wifiOnly(activity) && !NetworkState.isUnmetered(activity)) {
                    showMobileConfirm(activity, info)
                }
            }

            Phase.DOWNLOADED -> {
                val info = state.info ?: return
                if (AppUpdatePrefs.promptedCode(activity) == info.versionCode) return
                showReady(activity, info)
            }

            else -> Unit
        }
    }

    // ------------------------------------------------------------ 运行时变更二选一（F2）

    /**
     * 运行时有变更时让用户自己权衡：只更应用（省流量、秒起）还是连运行时一起换
     * （多 250MB、首启 1–3 分钟）。默认按钮给"仅更新应用"—— 大多数人不想为了
     * 一个 patch 版本等 3 分钟。
     */
    fun showChoice(activity: Activity, appOnly: UpdateInfo, withRuntime: UpdateInfo) {
        showing = true
        val installed = AppUpdateChecker.installedRuntime(activity)?.first ?: "—"
        val remote = withRuntime.asset.runtimeVersion ?: "—"
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.app_update_choice_title, withRuntime.versionName))
            .setMessage(
                activity.getString(
                    R.string.app_update_choice_text,
                    appOnly.sizeMb,
                    installed,
                    remote,
                    withRuntime.sizeMb,
                ),
            )
            .setPositiveButton(R.string.app_update_choice_app_only) { _, _ ->
                choose(activity, appOnly)
            }
            .setNeutralButton(R.string.app_update_choice_with_runtime) { _, _ ->
                choose(activity, withRuntime)
            }
            .setNegativeButton(R.string.app_update_dlg_later, null)
            .setOnDismissListener {
                showing = false
                AppUpdatePrefs.setModeChoiceCode(activity, withRuntime.versionCode)
            }
            .show()
    }

    private fun choose(activity: Activity, info: UpdateInfo) {
        AppUpdatePrefs.setModeChoiceCode(activity, info.versionCode)
        AppUpdatePrefs.setChosenPackage(activity, info.asset.packageMode)
        AppUpdatePrefs.setPending(activity, info)
        AppUpdateBus.set(
            UpdateState(
                phase = Phase.AVAILABLE,
                info = info,
                viaProxy = AppUpdateBus.state.value.viaProxy,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        ensureDownload(activity, info)
    }

    // ------------------------------------------------------------ 移动网络询问

    fun showMobileConfirm(activity: Activity, info: UpdateInfo) {
        showing = true
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.app_update_dlg_title, info.versionName))
            .setMessage(activity.getString(R.string.app_update_dlg_mobile, info.sizeMb))
            .setPositiveButton(R.string.app_update_dlg_download) { _, _ ->
                AppUpdatePrefs.setPromptedCode(activity, info.versionCode)
                if (!AppUpdateDownloadService.start(activity, info)) {
                    toast(activity, R.string.app_update_busy)
                }
            }
            .setNeutralButton(R.string.app_update_dlg_ignore) { _, _ ->
                // 忽略此版本：只拦这一个 versionCode，更高版本自动恢复提示
                AppUpdatePrefs.setIgnoredVersionCode(activity, info.versionCode)
                AppUpdateBus.phase(Phase.IDLE)
            }
            .setNegativeButton(R.string.app_update_dlg_later, null)
            .setOnDismissListener {
                showing = false
                AppUpdatePrefs.setPromptedCode(activity, info.versionCode)
            }
            .show()
    }

    // ------------------------------------------------------------ 下载完成

    fun showReady(activity: Activity, info: UpdateInfo) {
        showing = true
        val message = if (info.runtimePrepare) {
            activity.getString(R.string.app_update_dlg_ready_text_runtime)
        } else {
            activity.getString(R.string.app_update_dlg_ready_text)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.app_update_dlg_ready_title, info.versionName))
            .setMessage(message)
            .setPositiveButton(R.string.app_update_dlg_install) { _, _ -> startInstall(activity) }
            .setNegativeButton(R.string.app_update_dlg_later, null)
            .setOnDismissListener {
                showing = false
                AppUpdatePrefs.setPromptedCode(activity, info.versionCode)
            }
            .show()
    }

    // ------------------------------------------------------------ 空间不足

    fun showNoSpace(activity: Activity, needMb: Long, freeMb: Long) {
        if (showing) return
        showing = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.app_update_dlg_nospace_title)
            .setMessage(activity.getString(R.string.app_update_dlg_nospace_text, needMb, freeMb))
            .setPositiveButton(R.string.app_update_dlg_ok, null)
            .setOnDismissListener { showing = false }
            .show()
    }

    // ------------------------------------------------------------ 下载 / 安装

    /** 只有不计费网络（或用户关了"仅 WiFi"）才直接下；否则先问流量。 */
    fun ensureDownload(activity: Activity, info: UpdateInfo) {
        val unmetered = NetworkState.isUnmetered(activity)
        if (AppUpdatePrefs.wifiOnly(activity) && !unmetered) {
            showMobileConfirm(activity, info)
            return
        }
        if (!AppUpdateDownloadService.start(activity, info)) {
            toast(activity, R.string.app_update_busy)
        }
    }

    /**
     * 提交安装。
     *
     * 顺序很重要：先检查「安装未知应用」权限 → 抢闸门 → **停引擎** → 提交会话。
     * 覆盖安装会杀掉本进程，引擎里的 node/llama/局域网页子进程会变孤儿（§14）。
     */
    fun startInstall(activity: Activity) {
        val ctx = activity.applicationContext
        val apk = AppUpdatePrefs.downloadedApk(activity)
        if (apk == null) {
            toast(activity, R.string.app_update_err_missing_file)
            return
        }
        if (!ApkInstaller.canRequestInstall(activity)) {
            ApkInstaller.openInstallPermission(activity)
            return
        }
        if (!UpdateTasks.acquire(UpdateTasks.Task.APK_INSTALL)) {
            toast(activity, R.string.app_update_busy)
            return
        }
        val versionCode = AppUpdatePrefs.downloadedCode(activity)
        AppUpdateBus.phase(Phase.INSTALLING)
        Thread({
            val app = ctx as? DshApp
            val stopped = runCatching { app?.supervisor?.stop(graceMs = 3_500) ?: true }
                .getOrDefault(false)
            if (!stopped) {
                Log.w(TAG, "install aborted: engine did not stop in time")
                UpdateTasks.release(UpdateTasks.Task.APK_INSTALL)
                AppUpdateBus.phase(Phase.DOWNLOADED)
                activity.runOnUiThread { toast(activity, R.string.app_update_install_err_engine) }
                return@Thread
            }
            val ok = ApkInstaller.install(ctx, apk, versionCode)
            if (!ok) {
                UpdateTasks.release(UpdateTasks.Task.APK_INSTALL)
                AppUpdateBus.phase(Phase.DOWNLOADED)
                activity.runOnUiThread { toast(activity, R.string.app_update_install_err_unknown) }
            }
            // 成功时由 InstallResultReceiver 收尾（清目录 / 更新状态）
        }, "dsh-app-update-install").apply { isDaemon = true; start() }
    }

    /** 跳浏览器看 Release（旧入口保留的落点，R10）。 */
    fun openReleasePage(activity: Activity, info: UpdateInfo?) {
        val url = info?.notesUrl?.takeIf { it.isNotBlank() }
            ?: app.dsh.mobile.engine.RepoConfig.RELEASES_PAGE
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { toast(activity, R.string.app_update_err_no_browser) }
    }

    private fun toast(activity: Activity, res: Int) {
        runCatching { Toast.makeText(activity, res, Toast.LENGTH_SHORT).show() }
    }
}

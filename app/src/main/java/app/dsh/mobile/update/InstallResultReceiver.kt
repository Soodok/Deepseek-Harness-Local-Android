package app.dsh.mobile.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.dsh.mobile.MainActivity
import app.dsh.mobile.R
import app.dsh.mobile.download.DownloadNotifier
import java.io.File

/**
 * 系统安装器的回执接收器（`exported=false`，只能由系统/本应用触发）。
 *
 * 三个分支都必须处理，缺一个就会出现"看起来没有任何反应"：
 *  - `STATUS_PENDING_USER_ACTION`：系统把**确认框**的 Intent 交回来，必须
 *    `startActivity` 才能弹出安装界面。**漏这一步 = 用户点安装没反应**（D6）。
 *  - `STATUS_SUCCESS`：清安装包与相关偏好，状态置 DOWNLOADED→完成。
 *  - 其它（`FAILURE_*`）：保留 APK，给出可读原因与重试入口。
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_APP
        Log.i(TAG, "install result: status=$status kind=$kind versionCode=$versionCode message=$message")

        // 扩展 APK 与应用自更新共用同一套回执，但**不能**动更新状态：
        // 扩展装完由包名扫描判定 green（ExtensionManager 的轮询），与 filesDir/update 无关。
        if (kind == KIND_EXTENSION) {
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> launchConfirm(context, intent)
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "extension apk installed")
                else -> Log.w(TAG, "extension apk install failed: status=$status $message")
            }
            return
        }

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> launchConfirm(context, intent)

            PackageInstaller.STATUS_SUCCESS -> {
                UpdateTasks.release(UpdateTasks.Task.APK_INSTALL)
                // 装完清理：下次打开是新版，残留目录只会占空间
                AppUpdatePrefs.purgeApks(context)
                AppUpdatePrefs.setAutoDownloadedCode(context, 0)
                AppUpdatePrefs.setPromptedCode(context, 0)
                AppUpdateBus.phase(Phase.IDLE)
                DownloadNotifier.cancel(context, DownloadNotifier.ID_APK)
                Log.i(TAG, "install succeeded: versionCode=$versionCode")
            }

            else -> {
                UpdateTasks.release(UpdateTasks.Task.APK_INSTALL)
                val reason = describe(context, status, message)
                AppUpdateBus.set(
                    UpdateState(
                        phase = Phase.FAILED,
                        message = reason,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                DownloadNotifier.ensureChannel(context)
                DownloadNotifier.post(
                    context,
                    DownloadNotifier.ID_APK,
                    DownloadNotifier.result(
                        context,
                        DownloadNotifier.ID_APK,
                        context.getString(R.string.app_update_install_failed_title),
                        reason,
                        openApp(context),
                    ),
                )
            }
        }
    }

    /**
     * 系统把安装确认框交回来 —— 必须 `startActivity`，否则用户点"安装"什么都不发生。
     * 应用自更新与扩展 APK 都走这里。
     */
    private fun launchConfirm(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        if (confirm == null) {
            Log.w(TAG, "PENDING_USER_ACTION without EXTRA_INTENT")
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(confirm) }
            .onFailure { Log.w(TAG, "cannot start install confirm: ${it.message}") }
    }

    /**
     * 把安装器的失败码翻成人话。
     *
     * `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 是最常见的一个：换过签名（不同渠道/别人
     * fork 的包）就必然出现。这时**不做任何绕过**，只能卸载重装 —— 文案直接说清楚。
     */
    private fun describe(context: Context, status: Int, raw: String): String {
        val known = when {
            raw.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ->
                R.string.app_update_install_err_signature
            raw.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) ->
                R.string.app_update_install_err_downgrade
            raw.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true) ->
                R.string.app_update_install_err_space
            else -> when (status) {
                PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.app_update_install_err_aborted
                PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.app_update_install_err_blocked
                PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.app_update_install_err_conflict
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.app_update_install_err_incompatible
                PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.app_update_install_err_space
                else -> R.string.app_update_install_err_unknown
            }
        }
        val base = context.getString(known)
        return if (raw.isBlank()) base else "$base ($raw)"
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "InstallResult"
        const val ACTION_INSTALL_RESULT = "app.dsh.mobile.update.INSTALL_RESULT"
        const val EXTRA_VERSION_CODE = "version_code"

        /** 回执种类：`app` = 应用自更新（要清更新状态），`extension` = 扩展 APK（只记日志）。 */
        const val EXTRA_KIND = "install_kind"
        const val KIND_APP = "app"
        const val KIND_EXTENSION = "extension"

        /** 安装包目录（供 UI 判断"还有没有可装的包"）。 */
        fun apkFile(ctx: Context): File? = AppUpdatePrefs.downloadedApk(ctx)
    }
}

package app.dsh.mobile.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.dsh.mobile.MainActivity
import app.dsh.mobile.R
import app.dsh.mobile.download.DownloadNotifier
import app.dsh.mobile.download.DownloadSession
import app.dsh.mobile.download.DownloadUi
import app.dsh.mobile.download.TaskResult
import app.dsh.mobile.engine.HttpDownloader
import app.dsh.mobile.engine.RepoConfig
import app.dsh.mobile.engine.RuntimeDownloadConfig
import java.io.File

/**
 * APK 后台下载（R1/R2/R6）。
 *
 * 外壳（前台服务 / 通知 / 节流 / 取消 / 收尾）全部来自 [DownloadSession]，
 * 本类只负责"怎么把 APK 安全地拿到本地"：
 *
 * ```
 * 候选 URL = RuntimeDownloadConfig.orderUrls(assetUrl, proxy)   // 代理优先、直连兜底
 * HttpDownloader.download(urls, dest, expectedSha256 = index.sha256)
 * ```
 * sha 来自**验签过的** `update-index.json`（§5），所以这里能真用上强校验：
 * 代理投毒的包 sha 对不上 → 自动删 `.part` → 换下一个候选源重下。
 *
 * 下载完成**不自动安装**（系统要求用户确认）：发一条可点击的通知，
 * 状态推到 [Phase.DOWNLOADED]，前台可见时由 Activity 弹「立即安装 / 稍后」。
 */
class AppUpdateDownloadService : DownloadSession() {

    private var info: UpdateInfo? = null
    private var downloaded: File? = null

    override fun gate(): UpdateTasks.Task = UpdateTasks.Task.APK_DOWNLOAD

    override fun ui(): DownloadUi {
        val pending = info ?: AppUpdatePrefs.pending(this)
        return DownloadUi(
            notifId = DownloadNotifier.ID_APK,
            title = getString(R.string.app_update_title),
            preparingText = getString(
                R.string.app_update_preparing,
                pending?.versionName.orEmpty(),
            ),
            click = openApp(),
        )
    }

    override fun runTask(): TaskResult {
        val info = AppUpdatePrefs.pending(this)
            ?: return TaskResult.Failure(
                getString(R.string.app_update_failed_title),
                getString(R.string.app_update_err_missing_file),
                openApp(),
            )
        this.info = info
        AppUpdateBus.set(
            UpdateState(
                phase = Phase.DOWNLOADING,
                info = info,
                progress = 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        val dir = AppUpdatePrefs.versionDir(this, info.versionCode)
        val dest = File(dir, info.asset.name)

        // 清残留：旧版本目录（用户跳版不装会越堆越多）+ 超 7 天的 .part（§8 清理）
        AppUpdatePrefs.cleanupStale(this, info.versionCode)

        // 幂等：已下好且版本对得上，直接算完成（用户重试 / 服务被重启）
        if (dest.isFile && dest.length() == info.asset.size && info.asset.size > 0 &&
            ApkInstaller.archiveVersionCode(this, dest) == info.versionCode
        ) {
            downloaded = dest
            return successResult(info)
        }

        val need = (info.asset.size * 1.3).toLong()
        val free = runCatching { dir.usableSpace }.getOrDefault(0L)
        if (info.asset.size > 0 && free > 0 && free < need) {
            val needMb = (need + (1L shl 20) - 1) / (1L shl 20)
            val freeMb = free / (1L shl 20)
            return TaskResult.Failure(
                getString(R.string.app_update_failed_title),
                getString(R.string.app_update_need_space, needMb, freeMb),
                openApp(),
            )
        }

        val proxy = RuntimeDownloadConfig.proxy(this)
        val urls = RuntimeDownloadConfig.orderUrls(listOf(assetUrl(info)), proxy)

        val wake = acquireWakeLock("app-update-download")
        val downloader = HttpDownloader(this)
        var speed = 0f
        var lastBytes = 0L
        var lastAt = SystemClock.elapsedRealtime()
        val result = try {
            downloader.download(
                urls = urls,
                dest = dest,
                expectedSha256 = info.asset.sha256,
                expectedSize = info.asset.size,
                onProgress = { frac ->
                    val pct = (frac * 100).toInt().coerceIn(0, 100)
                    val bytes = (info.asset.size * frac).toLong()
                    val now = SystemClock.elapsedRealtime()
                    val dt = (now - lastAt) / 1000f
                    if (dt > 0.5f) {
                        speed = (bytes - lastBytes) / dt
                        lastBytes = bytes
                        lastAt = now
                    }
                    AppUpdateBus.progress(pct, null)
                    report(pct, progressText(info, bytes, speed))
                },
                shouldCancel = { isCancelRequested() },
            )
        } catch (e: HttpDownloader.CancelledException) {
            return TaskResult.Cancelled
        } catch (e: Exception) {
            return TaskResult.Failure(
                getString(R.string.app_update_failed_title),
                getString(R.string.app_update_failed, e.message ?: e.javaClass.simpleName),
                openApp(),
            )
        } finally {
            releaseWakeLock(wake)
        }

        // 线路标注（通知标题上的「经代理 / 直连」）
        reportSource(RuntimeDownloadConfig.isProxied(result.url, proxy))

        // 二次校验：sha 已由 HttpDownloader 保证，这里确认"装上去的确实是这一版"
        val archiveCode = ApkInstaller.archiveVersionCode(this, dest)
        if (archiveCode != 0 && archiveCode != info.versionCode) {
            dest.delete()
            return TaskResult.Failure(
                getString(R.string.app_update_failed_title),
                getString(R.string.app_update_err_version, archiveCode, info.versionCode),
                openApp(),
            )
        }
        if (!ApkInstaller.sameSignature(this, dest)) {
            // 签名不一致：换过渠道 key、别人 fork 的包，或代理投毒。三种都不该装。
            dest.delete()
            return TaskResult.Failure(
                getString(R.string.app_update_failed_title),
                getString(R.string.app_update_err_signature),
                openApp(),
            )
        }

        runCatching { File(dir, "meta.json").writeText(info.toJson().toString()) }
        downloaded = dest
        report(100, progressText(info, info.asset.size, 0f), force = true)
        return successResult(info)
    }

    override fun onFinished(result: TaskResult) {
        val info = this.info ?: return
        when (result) {
            is TaskResult.Success -> {
                downloaded?.let {
                    AppUpdatePrefs.setDownloaded(this, info.versionCode, it.absolutePath)
                }
                AppUpdatePrefs.setAutoDownloadedCode(this, info.versionCode)
                AppUpdateBus.set(
                    UpdateState(
                        phase = Phase.DOWNLOADED,
                        info = info,
                        progress = 100,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }

            is TaskResult.Failure -> {
                AppUpdateBus.set(
                    UpdateState(
                        phase = Phase.FAILED,
                        info = info,
                        message = result.text,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }

            TaskResult.Cancelled -> {
                AppUpdateBus.set(
                    UpdateState(
                        phase = Phase.CANCELLED,
                        info = info,
                        message = getString(R.string.app_update_cancelled),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------ 工具

    private fun successResult(info: UpdateInfo) = TaskResult.Success(
        title = getString(R.string.app_update_done_title, info.versionName),
        text = getString(R.string.app_update_done_text),
        click = installIntent(info.versionCode),
    )

    private fun progressText(info: UpdateInfo, bytes: Long, speed: Float): String {
        val totalMb = (info.asset.size / (1L shl 20)).toInt()
        val doneMb = (bytes / (1L shl 20)).toInt()
        val speedText = if (speed > 0) {
            String.format(java.util.Locale.US, "%.1f MB/s", speed / (1 shl 20))
        } else {
            ""
        }
        return getString(R.string.app_update_bigtext, info.versionName, doneMb, totalMb, speedText)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun installIntent(versionCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        versionCode,
        Intent(this, MainActivity::class.java)
            .setAction(ACTION_OPEN_INSTALL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** 点击「已下载」通知时带着它进入 App，由 UI 直接弹安装确认。 */
        const val ACTION_OPEN_INSTALL = "app.dsh.mobile.update.OPEN_INSTALL"

        /** 拼安装包下载地址（tag 来自索引，edge/preview 是 rolling tag）。 */
        fun assetUrl(info: UpdateInfo): String {
            val tag = info.tag.takeIf { it.isNotBlank() }
            return if (tag != null) {
                RepoConfig.releaseDownload(tag) + "/" + info.asset.name
            } else {
                RepoConfig.RELEASES_LATEST + "/" + info.asset.name
            }
        }

        /**
         * 起服务下载。返回 false 表示没起来（另一任务在跑 / 系统禁止后台启动前台服务）。
         *
         * Android 12+ 限制了"从后台启动前台服务"：24h 定时器或网络恢复回调触发时，
         * 应用可能已在后台，此时 `startForegroundService` 会抛
         * `ForegroundServiceStartNotAllowedException`。这里必须吞掉并返回 false ——
         * 崩溃换不来下载，下次打开 App 再试就是。
         */
        fun start(ctx: Context, info: UpdateInfo): Boolean {
            if (UpdateTasks.isBusy()) return false
            AppUpdatePrefs.setPending(ctx, info)
            val i = Intent(ctx, AppUpdateDownloadService::class.java)
            return runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
                true
            }.getOrElse {
                android.util.Log.w("AppUpdateDl", "cannot start download service: ${it.message}")
                false
            }
        }
    }
}

package app.dsh.mobile.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.dsh.mobile.AboutActivity
import app.dsh.mobile.DshApp
import app.dsh.mobile.R
import app.dsh.mobile.download.DownloadNotifier
import app.dsh.mobile.download.DownloadSession
import app.dsh.mobile.download.DownloadUi
import app.dsh.mobile.download.TaskResult
import app.dsh.mobile.engine.HttpDownloader
import app.dsh.mobile.engine.RepoConfig
import app.dsh.mobile.engine.RuntimeDownloadConfig
import app.dsh.mobile.engine.RuntimeIndexClient
import app.dsh.mobile.engine.RuntimeInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 运行时更新的页面状态（关于页订阅）。 */
enum class RuntimePhase { IDLE, PREPARING, DOWNLOADING, EXTRACTING, DONE, FAILED, CANCELLED }

data class RuntimeUpdateState(
    val phase: RuntimePhase = RuntimePhase.IDLE,
    val progress: Int = 0,
    val version: String = "",
    val message: String? = null,
    val updatedAt: Long = 0L,
)

object RuntimeUpdateBus {
    private val _state = MutableStateFlow(RuntimeUpdateState())
    val state: StateFlow<RuntimeUpdateState> = _state.asStateFlow()

    fun set(s: RuntimeUpdateState) {
        _state.value = s
    }

    fun update(block: (RuntimeUpdateState) -> RuntimeUpdateState) {
        _state.value = block(_state.value)
    }
}

/**
 * 运行时换装的前台服务（R11 / D5）。
 *
 * ## 为什么把这段从 AboutActivity 搬出来
 * 旧实现把「停止引擎 → 下载 → 换装 → 重启引擎」跑在关于页的 Thread 里：
 * 用户一退出关于页就失去进度感知，而引擎此刻是**停着的** —— 他去点终端/会话
 * 会失败，却没有任何提示说"正在换运行时，请稍等"。350MB 下载 + 3 分钟解压，
 * 这段时间必须能从通知栏看到。
 *
 * ## 复用基座的方式
 * 外壳（前台服务、通知、节流、取消、收尾）来自 [DownloadSession]；
 * 这里只做"外部驱动进度"的那一半 —— `RuntimeInstaller` 自带 下载/解压 进度回调，
 * 直接转给 `report()` 即可，不需要基座碰下载细节。
 *
 * `supervisor.stop` 的返回值仍是硬闸门：引擎停不干净就中止（旧实现踩过：
 * 没停住照样换名，进程读着被删的 inode 直接崩）。
 */
class RuntimeUpdateService : DownloadSession() {

    private var targetVersion = ""
    private var installing = false

    override fun gate(): UpdateTasks.Task = UpdateTasks.Task.RUNTIME

    override fun ui(): DownloadUi = DownloadUi(
        notifId = DownloadNotifier.ID_RUNTIME,
        title = getString(R.string.app_update_runtime_title),
        preparingText = getString(R.string.app_update_runtime_preparing),
        click = openAbout(),
    )

    override fun runTask(): TaskResult {
        val app = applicationContext as? DshApp
        var engineStopped = false
        try {
            RuntimeUpdateBus.set(
                RuntimeUpdateState(
                    phase = RuntimePhase.PREPARING,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            val env = AppUpdateChecker.runtimeEnv(this)
            val proxy = RuntimeDownloadConfig.proxy(this)
            val indexUrl = RepoConfig.RELEASES_LATEST + "/runtime-index.json"
            val entry = RuntimeIndexClient.resolve(
                ctx = this,
                indexUrl = indexUrl,
                proxyBase = proxy,
                abi = env.abi,
                exactVersion = null,
                currentVersionCode = env.versionCode,
            ) ?: return TaskResult.Failure(
                getString(R.string.app_update_runtime_title),
                getString(R.string.about_runtime_failed, getString(R.string.about_runtime_no_pin)),
                openAbout(),
            )
            targetVersion = entry.version

            // 已经不比本机新 → 不重装（否则用户点一次就白等 3 分钟）
            val installed = AppUpdateChecker.installedRuntime(this)
            if (installed != null && !isNewer(entry, installed)) {
                RuntimeUpdateBus.set(
                    RuntimeUpdateState(
                        phase = RuntimePhase.DONE,
                        progress = 100,
                        version = installed.version,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                return TaskResult.Success(
                    getString(R.string.app_update_runtime_title),
                    getString(R.string.about_runtime_latest, installed.version),
                    openAbout(),
                )
            }

            checkCancelled()

            // 运行中的引擎会持续读 engine/lib：先停引擎再替换运行时，成功后自动重启
            val stopped = runCatching { app?.supervisor?.stop(graceMs = 3_500) ?: true }
                .getOrDefault(false)
            if (!stopped) {
                return TaskResult.Failure(
                    getString(R.string.app_update_runtime_title),
                    getString(R.string.about_runtime_failed, getString(R.string.about_runtime_engine_busy)),
                    openAbout(),
                )
            }
            engineStopped = true

            installing = true
            RuntimeInstaller(this).installFromIndex(
                entry = entry,
                indexUrl = indexUrl,
                proxies = listOfNotNull(proxy.takeIf { it.isNotBlank() }),
                onProgress = { frac -> onProgress(entry.version, frac) },
                shouldCancel = { isCancelRequested() },
            )
            installing = false
            RuntimeUpdateBus.set(
                RuntimeUpdateState(
                    phase = RuntimePhase.DONE,
                    progress = 100,
                    version = entry.version,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            return TaskResult.Success(
                getString(R.string.app_update_runtime_done_title),
                getString(R.string.app_update_runtime_done_text, entry.version),
                openAbout(),
            )
        } catch (e: HttpDownloader.CancelledException) {
            return TaskResult.Cancelled
        } finally {
            installing = false
            if (engineStopped) {
                // 无论成功失败都要把引擎拉回来，否则用户面对"App 打开但引擎不动"的黑洞
                runCatching { app?.supervisor?.restart() }
            }
        }
    }

    override fun onFinished(result: TaskResult) {
        when (result) {
            is TaskResult.Failure -> RuntimeUpdateBus.set(
                RuntimeUpdateState(
                    phase = RuntimePhase.FAILED,
                    message = result.text,
                    updatedAt = System.currentTimeMillis(),
                ),
            )

            TaskResult.Cancelled -> RuntimeUpdateBus.set(
                RuntimeUpdateState(
                    phase = RuntimePhase.CANCELLED,
                    updatedAt = System.currentTimeMillis(),
                ),
            )

            is TaskResult.Success -> Unit
        }
    }

    /**
     * 索引条目是否比本机更新。
     *
     * 只用版本号相等会漏掉"同版本号但 builtAt 更早"（preview 回退 stable），
     * 因此优先比 builtAt —— 与 `RuntimeInstaller.ensureInstalled()` 的防降级判据同源。
     */
    private fun isNewer(
        entry: RuntimeIndexClient.Entry,
        installed: AppUpdateChecker.InstalledRuntime,
    ): Boolean {
        val localVersion = installed.version
        val localBuiltAt = installed.builtAt
        if (entry.version != localVersion) {
            return entry.builtAt <= 0 || localBuiltAt <= 0 || entry.builtAt > localBuiltAt ||
                (entry.builtAt <= 0 && localBuiltAt <= 0)
        }
        if (entry.builtAt > 0 && localBuiltAt > 0) return entry.builtAt > localBuiltAt
        return false
    }

    private fun onProgress(version: String, frac: Float) {
        val pct = (frac * 100).toInt().coerceIn(0, 100)
        // RuntimeInstaller 内部：下载占前 45%、解压占后 55%
        val extracting = frac >= 0.45f
        RuntimeUpdateBus.update {
            it.copy(
                phase = if (extracting) RuntimePhase.EXTRACTING else RuntimePhase.DOWNLOADING,
                progress = pct,
                version = version,
                updatedAt = System.currentTimeMillis(),
            )
        }
        // 解压阶段不给取消：半解压的 engine 目录比多等 3 分钟更糟
        setCancelable(!extracting)
        report(
            pct = pct,
            text = if (extracting) {
                getString(R.string.app_update_runtime_extracting, pct)
            } else {
                getString(R.string.app_update_runtime_downloading, pct)
            },
            bigText = getString(R.string.app_update_runtime_paused),
            indeterminate = false,
        )
    }

    private fun openAbout(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, AboutActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "RuntimeUpdateSvc"

        /** 起服务。返回 false 表示没起来（另一任务在跑 / 系统禁止后台启动前台服务）。 */
        fun start(ctx: Context): Boolean {
            if (UpdateTasks.isBusy()) return false
            val i = Intent(ctx, RuntimeUpdateService::class.java)
            return runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
                true
            }.getOrElse {
                android.util.Log.w("RuntimeUpdateSvc", "cannot start service: ${it.message}")
                false
            }
        }
    }
}

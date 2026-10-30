package app.dsh.mobile.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 检查时机编排（R9）。
 *
 * ## 规则（这是产品要求，不是技术选择）
 * - **每次打开应用检查一次**：冷启动后延迟 20s（等引擎 READY，避免和 580MB 解压抢
 *   IO/网络），不受 24h 节流限制；
 * - **应用常驻时 24 小时一次**：进程内协程定时器，进程被杀自然结束；
 * - **不打开就不检查**：不引入 WorkManager / JobScheduler —— 用户要的就是
 *   "不开 App 别联网"，周期任务反而违背它，还多一个依赖。
 *
 * 唯一的节流是 **10 分钟最小间隔**：防"反复杀进程重开"把 GitHub API 的
 * 60 次/小时限额打爆（命中时复用上次结果，不显示「检查中」）。
 */
object AppUpdateScheduler {

    private const val TAG = "UpdateScheduler"

    private const val COLD_START_DELAY_MS = 20_000L
    private const val RESIDENT_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val MIN_GAP_MS = 10L * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    @Volatile
    private var timerJob: Job? = null

    @Volatile
    private var lastStartedAt = 0L

    @Volatile
    private var networkWatch: Any? = null

    @Volatile
    private var coldStartScheduled = false

    /** 冷启动：引擎 READY 后调用（延迟 20s 再查，必查一次）。 */
    fun onEngineReady(ctx: Context) {
        val app = ctx.applicationContext
        startIfNeeded(app)
        // 引擎状态会多次重发（Healthy 重连、恢复等）—— 冷启动检查只排一次队
        if (coldStartScheduled) return
        coldStartScheduled = true
        // 每次打开都要查：不受 24h 限制，只受 10 分钟保护
        scope.launch {
            delay(COLD_START_DELAY_MS)
            runCheck(app, "cold-start", force = false)
        }
    }

    /** 应用从后台回到前台（onResume）：距上次检查超过 24h 才补一次。 */
    fun onForeground(ctx: Context) {
        val app = ctx.applicationContext
        val since = System.currentTimeMillis() - AppUpdatePrefs.lastCheckAt(app)
        if (since < RESIDENT_INTERVAL_MS) return
        scope.launch { runCheck(app, "foreground", force = false) }
    }

    /** 手动检查（关于页 / 设置页）：无视所有节流。 */
    fun checkNow(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCheck(app, "manual", force = true) }
    }

    private fun startIfNeeded(ctx: Context) {
        if (started) return
        started = true
        // 常驻 24h 定时器
        timerJob = scope.launch {
            while (true) {
                delay(RESIDENT_INTERVAL_MS)
                runCheck(ctx, "resident-timer", force = false)
            }
        }
        // 网络转为不计费（回 WiFi）→ 有 pending 就续传，而不是重新查一遍
        networkWatch = NetworkState.watchUnmetered(ctx) { unmetered ->
            if (unmetered) onUnmeteredAvailable(ctx)
        }
    }

    /**
     * 回到 WiFi 时：自动下载只在这里"续上"。
     * 移动网络下用户选了「稍后」，接上 WiFi 就应该自动开始，不用再点一次。
     */
    private fun onUnmeteredAvailable(ctx: Context) {
        if (!AppUpdatePrefs.autoCheck(ctx)) return
        if (UpdateTasks.isBusy()) return
        val state = AppUpdateBus.state.value
        val info = state.info ?: AppUpdatePrefs.pending(ctx) ?: return
        if (state.phase != Phase.AVAILABLE && state.phase != Phase.CANCELLED && state.phase != Phase.FAILED) {
            return
        }
        if (AppUpdatePrefs.downloadedApk(ctx) != null) return
        if (AppUpdatePrefs.isIgnored(ctx, info.versionCode)) return
        if (AppUpdatePrefs.wifiOnly(ctx) && !NetworkState.isUnmetered(ctx)) return
        Log.i(TAG, "unmetered network available, resuming download of ${info.versionName}")
        if (AppUpdateDownloadService.start(ctx, info)) {
            AppUpdatePrefs.setPromptedCode(ctx, info.versionCode)
        }
    }

    private fun runCheck(ctx: Context, reason: String, force: Boolean) {
        if (!force && !AppUpdatePrefs.autoCheck(ctx)) {
            Log.i(TAG, "skip check ($reason): auto-check disabled by user")
            return
        }
        if (UpdateTasks.isBusy()) {
            Log.i(TAG, "skip check ($reason): another update task running")
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastStartedAt < MIN_GAP_MS) {
            Log.i(TAG, "skip check ($reason): within ${MIN_GAP_MS / 60000}min guard")
            return
        }
        lastStartedAt = now

        // 先清掉"已下载/待装版本 ≤ 当前已安装"的过时状态（覆盖安装杀进程会跳过
        // InstallResultReceiver 的成功清理，详见 AppUpdatePrefs.clearStaleInstallState），
        // 否则关于页会拿尸体状态显示"可更新"，即使当前版本已与云端一致。
        AppUpdatePrefs.clearStaleInstallState(ctx)

        AppUpdateBus.phase(Phase.CHECKING)
        val result = runCatching { AppUpdateChecker.check(ctx, force) }
            .getOrElse { AppUpdateChecker.Result.Failed(it.message ?: it.javaClass.simpleName) }
        Log.i(TAG, "check ($reason) → ${result::class.simpleName}")

        when (result) {
            is AppUpdateChecker.Result.UpToDate -> {
                AppUpdatePrefs.setPending(ctx, null)
                AppUpdateBus.set(
                    UpdateState(
                        phase = Phase.UP_TO_DATE,
                        message = result.latestVersionName,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }

            is AppUpdateChecker.Result.Available -> {
                val info = result.info
                AppUpdatePrefs.setPending(ctx, info)
                AppUpdateBus.set(
                    UpdateState(
                        phase = Phase.AVAILABLE,
                        info = info,
                        viaProxy = result.viaProxy,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                maybeAutoDownload(ctx, info)
            }

            is AppUpdateChecker.Result.Choice -> {
                // 形态二选一：按钮由 UI 弹（后台无法直接弹 Activity）
                AppUpdatePrefs.setPending(ctx, result.appOnly)
                AppUpdateBus.set(
                    UpdateState(
                        phase = Phase.NEEDS_CHOICE,
                        info = result.withRuntime,
                        choices = listOf(result.appOnly, result.withRuntime),
                        message = "${
                            result.installedRuntime
                        } → ${result.remoteRuntime}",
                        viaProxy = result.viaProxy,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }

            is AppUpdateChecker.Result.Failed -> {
                AppUpdateBus.set(
                    UpdateState(
                        phase = Phase.UNAVAILABLE,
                        message = result.reason,
                        failure = result.failure,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /**
     * 静默下载策略（行为矩阵 A/B/C）：
     * - 不计费网络 + 未忽略 + 该版本没自动下过 → 直接后台下载；
     * - 计费网络且开着「仅 WiFi」→ 只更新状态，等 UI 弹询问框；
     * - 用户关掉「仅 WiFi」→ 计费网络也静默下载（设置页已二次确认过）。
     */
    private fun maybeAutoDownload(ctx: Context, info: UpdateInfo) {
        if (AppUpdatePrefs.isIgnored(ctx, info.versionCode)) {
            Log.i(TAG, "version ${info.versionCode} is ignored, no auto download")
            return
        }
        if (AppUpdatePrefs.autoDownloadedCode(ctx) == info.versionCode) {
            // 已为该版本下载过：把状态恢复成 DOWNLOADED，让前台 UI 弹一次
            // 「立即安装 / 稍后」。以前直接 return，启动自检发现更新却全程静默，
            // 用户只能去关于页手点 —— 表现为"启动不自检"（v1.4.56 修）。
            val apk = AppUpdatePrefs.downloadedApk(ctx)
            if (apk != null && AppUpdatePrefs.downloadedCode(ctx) == info.versionCode) {
                Log.i(TAG, "version ${info.versionCode} already downloaded, restore DOWNLOADED state")
                AppUpdateBus.set(
                    UpdateState(
                        phase = Phase.DOWNLOADED,
                        info = info,
                        progress = 100,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                Log.i(TAG, "version ${info.versionCode} already downloaded once, skip")
            }
            return
        }
        val unmetered = NetworkState.isUnmetered(ctx)
        val wifiOnly = AppUpdatePrefs.wifiOnly(ctx)
        if (wifiOnly && !unmetered) {
            Log.i(TAG, "metered network + wifiOnly → wait for user consent")
            return
        }
        if (AppUpdateDownloadService.start(ctx, info)) {
            AppUpdatePrefs.setPromptedCode(ctx, info.versionCode)
        }
    }
}

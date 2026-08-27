package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 引擎监督器：冷启动 → 健康检查 → 运行中 → 崩溃退避重启 的完整状态机。
 *
 * 状态流转：
 *   Idle → Installing → Starting → Healthy(port)
 *        ↘ Backoff(delay, attempt) ↘ Failed(reason) → Stopped
 * 连续健康一次即重置退避计数；达到 MAX_RESTART 后进入 Failed 终态。
 *
 * 自愈层（ProfileGuardian）： Healthy 时快照配置；同签名连续失败触发
 * last-good 回滚；仍失败进入安全模式（归档坏配置空跑）。
 * SafeMode 会作为独立状态暴露给 UI 展示"引擎运行于安全模式"。
 */
class EngineSupervisor(private val ctx: Context) {

    sealed interface State {
        data object Idle : State
        data object Installing : State
        data object Starting : State

        /** 正常健康 */
        data class Healthy(val port: Int) : State

        /** 安全模式：配置被隔离后以空配置拉起，功能受限但可用 */
        data class SafeMode(val port: Int) : State
        data class Backoff(val delayMs: Long, val attempt: Int) : State
        data class Failed(val reason: String) : State
        data object Stopped : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 当前健康端口，UI 层据此加载 WebView */
    val healthyPort: Int get() = when (val s = _state.value) {
        is State.Healthy -> s.port
        is State.SafeMode -> s.port
        else -> EngineConfig.DEFAULT_PORT
    }

    private var process: EngineProcess? = null
    private var loopJob: Job? = null
    private var userStop = false
    private val guardian by lazy { ProfileGuardian(ctx) }

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        userStop = false
        loopJob = scope.launch(Dispatchers.Default) { supervisionLoop() }
    }

    fun stop() {
        userStop = true
        loopJob?.cancel()
        process?.stop()
        process = null
        _state.value = State.Stopped
    }

    /** 手动导出引擎日志（用户反馈通道） */
    fun logFile(): File = File(EngineConfig.engineRoot(ctx), "engine.log")

    /**
     * 失败签名：用「引擎日志尾部 + 退出码」的哈希近似。
     * 确定性崩溃（坏配置）每次堆栈一致 → 同签名；
     * 偶发崩溃（OOM/被杀）尾部随机 → 不同签名不累计。
     */
    private fun failureSignature(status: Int?): String {
        val tail = runCatching {
            logFile().readText().takeLast(4096)
                .lineSequence()
                .filter { it.contains("Error") || it.contains("at ") }
                .toList()
                .takeLast(12)
                .joinToString("\n")
        }.getOrDefault("")
        return "$status:${tail.hashCode()}"
    }

    private suspend fun supervisionLoop() {
        var backoffIndex = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive && !userStop) {
            try {
                // 启动前先把被误隔离的引擎内置 patch 恢复（自愈；防 ENOENT fail-loud）
                val healed = withContext(Dispatchers.IO) { guardian.restoreQuarantinedBuiltinOverlays() }
                if (healed > 0) Log.w(TAG, "guardian: restored $healed quarantined builtin overlay(s)")
                // 启动前把明显畸形的 patch 隔离掉（廉价预检，减少无效重启）
                withContext(Dispatchers.IO) { guardian.quarantineMalformedPatches() }

                _state.value = State.Installing
                withContext(Dispatchers.IO) { RuntimeInstaller(ctx).ensureInstalled() }

                _state.value = State.Starting
                val proc = withContext(Dispatchers.IO) { spawnEngine() }
                process = proc

                val healthy = pollHealth(EngineConfig.DEFAULT_PORT, proc)
                if (healthy) {
                    withContext(Dispatchers.IO) {
                        guardian.resetCrashStreak()
                        guardian.snapshotLastGood()
                    }
                    backoffIndex = 0
                    val safe = guardian.inSafeMode()
                    Log.i(TAG, if (safe) "engine healthy in SAFE MODE on :${EngineConfig.DEFAULT_PORT}" else "engine healthy on :${EngineConfig.DEFAULT_PORT}")
                    _state.value =
                        if (safe) State.SafeMode(EngineConfig.DEFAULT_PORT)
                        else State.Healthy(EngineConfig.DEFAULT_PORT)
                    // 阻塞等待进程退出（被杀/崩溃）
                    val status = proc.exitFuture.get()
                    if (userStop) break
                    Log.w(TAG, "engine exited, raw status=0x${status.toString(16)}")
                } else {
                    proc.stop()
                    throw EngineStartException("健康检查超时（${EngineConfig.HEALTH_TIMEOUT_MS}ms）")
                }
            } catch (e: Exception) {
                if (userStop) break
                Log.e(TAG, "supervision failure", e)
            }

            // ---- 自愈判定：确定性失败序列达到阈值则回滚/进安全模式 ----
            when (
                runCatching { guardian.onFailure(failureSignature(lastExitStatus)) }
                    .getOrElse { ProfileGuardian.Action.NONE }
            ) {
                ProfileGuardian.Action.ROLLED_BACK -> {
                    Log.w(TAG, "guardian: deterministic crash detected, rolled back profiles to last-good")
                    backoffIndex = 0 // 给恢复后的启动全新的退避额度
                }
                ProfileGuardian.Action.SAFE_MODE -> {
                    Log.e(TAG, "guardian: rollback insufficient, entering SAFE MODE")
                    backoffIndex = 0
                }
                ProfileGuardian.Action.NONE -> {}
            }

            // 统一走退避重启
            backoffIndex++
            if (backoffIndex > EngineConfig.MAX_RESTART) {
                _state.value = State.Failed("连续 ${EngineConfig.MAX_RESTART} 次启动失败，已停止自动重启")
                return
            }
            val delayMs = EngineConfig.BACKOFF_STEPS[
                (backoffIndex - 1).coerceAtMost(EngineConfig.BACKOFF_STEPS.size - 1)
            ]
            _state.value = State.Backoff(delayMs, backoffIndex)
            delay(delayMs)
        }
    }

    /** 最近一次子进程退出码；仅在进程已退出后可读，避免阻塞 */
    private val lastExitStatus: Int?
        get() = process?.exitFuture?.takeIf { it.isDone }?.get()

    private fun spawnEngine(): EngineProcess =
        EngineProcess.spawn(
            nodeBin = EngineConfig.nodeBin(ctx),
            entryJs = EngineConfig.dshEntry(ctx),
            cwd = EngineConfig.workspaces(ctx),
            env = EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT),
            logFile = logFile(),
        )

    /** 轮询 http://127.0.0.1:port 直到响应/超时/子进程提前死亡 */
    private suspend fun pollHealth(port: Int, proc: EngineProcess): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + EngineConfig.HEALTH_TIMEOUT_MS
        val url = "http://127.0.0.1:$port/"
        while (System.currentTimeMillis() < deadline &&
            kotlinx.coroutines.currentCoroutineContext().isActive
        ) {
            // 子进程已死就别干等超时——立即返回走快速退避重试
            if (proc.exitFuture.isDone) return@withContext false
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 2_000
                conn.readTimeout = 2_000
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) return@withContext true // Web UI 起来即算就绪
            } catch (_: Exception) {
                // 引擎尚未监听，继续等
            }
            delay(EngineConfig.HEALTH_INTERVAL_MS)
        }
        false
    }

    companion object {
        private const val TAG = "EngineSupervisor"
    }
}

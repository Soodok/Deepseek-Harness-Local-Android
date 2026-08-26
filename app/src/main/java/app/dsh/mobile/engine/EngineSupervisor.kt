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
 */
class EngineSupervisor(private val ctx: Context) {

    sealed interface State {
        data object Idle : State
        data object Installing : State
        data object Starting : State
        data class Healthy(val port: Int) : State
        data class Backoff(val delayMs: Long, val attempt: Int) : State
        data class Failed(val reason: String) : State
        data object Stopped : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 当前健康端口，UI 层据此加载 WebView */
    val healthyPort: Int get() = (_state.value as? State.Healthy)?.port ?: EngineConfig.DEFAULT_PORT

    private var process: EngineProcess? = null
    private var loopJob: Job? = null
    private var userStop = false

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

    private suspend fun supervisionLoop() {
        var backoffIndex = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive && !userStop) {
            try {
                _state.value = State.Installing
                withContext(Dispatchers.IO) { RuntimeInstaller(ctx).ensureInstalled() }

                _state.value = State.Starting
                val proc = withContext(Dispatchers.IO) { spawnEngine() }
                process = proc

                val healthy = pollHealth(EngineConfig.DEFAULT_PORT)
                if (healthy) {
                    backoffIndex = 0
                    Log.i(TAG, "engine healthy on :${EngineConfig.DEFAULT_PORT}")
                    _state.value = State.Healthy(EngineConfig.DEFAULT_PORT)
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

    private fun spawnEngine(): EngineProcess =
        EngineProcess.spawn(
            nodeBin = EngineConfig.nodeBin(ctx),
            entryJs = EngineConfig.dshEntry(ctx),
            cwd = EngineConfig.workspaces(ctx),
            env = EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT),
            logFile = logFile(),
        )

    /** 轮询 http://127.0.0.1:port 直到响应或超时 */
    private suspend fun pollHealth(port: Int): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + EngineConfig.HEALTH_TIMEOUT_MS
        val url = "http://127.0.0.1:$port/"
        while (System.currentTimeMillis() < deadline &&
            kotlinx.coroutines.currentCoroutineContext().isActive
        ) {
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

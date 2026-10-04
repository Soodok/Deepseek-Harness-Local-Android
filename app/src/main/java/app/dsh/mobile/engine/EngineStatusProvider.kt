package app.dsh.mobile.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Detailed engine status snapshot (Phase 1 dashboard).
 * Collected periodically by a lightweight coroutine that reads engine.log
 * and probes the HTTP endpoint — no engine restart required.
 *
 * Published via [status] StateFlow consumed by MainActivity's dashboard panel.
 */
data class EngineStatus(
    val state: EngineSupervisor.State,
    val port: Int,
    val uptimeSec: Long = 0L,
    val runtimeVersion: String = "—",
    val extensionCount: Int = 0,
    val memoryMb: Long = 0L,
    val cpuPercent: Float = 0f,
    val privMode: PrivMode = PrivMode.NORMAL,
    val lastError: String? = null,
) {
    val isHealthy: Boolean get() = state is EngineSupervisor.State.Healthy ||
        state is EngineSupervisor.State.SafeMode
}

object EngineStatusProvider {

    private const val TAG = "EngineStatusProvider"

    @Volatile private var snapshot: EngineStatus = EngineStatus(
        state = EngineSupervisor.State.Idle,
        port = EngineConfig.DEFAULT_PORT,
    )

    private val _status = MutableStateFlow(snapshot)
    val status: StateFlow<EngineStatus> = _status

    private var pollJob: kotlinx.coroutines.Job? = null

    fun start(scope: CoroutineScope) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    collectSnapshot()
                } catch (e: Exception) {
                    Log.w(TAG, "status poll failed: ${e.message}")
                }
                delay(2_000L)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun collectSnapshot() {
        val app = DshAppProvider.get()
        val supervisor = app.supervisor
        val currentState = supervisor.state.value
        val port = supervisor.healthyPort
        val privMode = Privilege.getMode(app)

        var uptimeSec = 0L
        if (currentState is EngineSupervisor.State.Healthy || currentState is EngineSupervisor.State.SafeMode) {
            uptimeSec = System.currentTimeMillis() / 1000 - (app.startupTimestamp / 1000)
        }

        val runtimeVer = runCatching {
            File(EngineConfig.engineRoot(app), ".runtime-version").readText().trim()
        }.getOrDefault("—")

        val extCount = runCatching {
            ExtensionManager(app).activeCount()
        }.getOrDefault(0)

        val memMb = readMemInfoMb()
        val cpuPct = readCpuPercent()

        val lastErr = runCatching {
            val log = supervisor.logFile()
            if (log.isFile) {
                val tail = log.readText().takeLast(2048)
                tail.lineSequence()
                    .filter { it.contains("Error") || it.contains("error") }
                    .lastOrNull()
                    ?.trim()
            } else null
        }.getOrDefault(null) as? String

        snapshot = EngineStatus(
            state = currentState,
            port = port,
            uptimeSec = uptimeSec,
            runtimeVersion = runtimeVer,
            extensionCount = extCount,
            memoryMb = memMb,
            cpuPercent = cpuPct,
            privMode = privMode,
            lastError = lastErr,
        )
        _status.value = snapshot
    }

    /** Reads Pss total from /proc/meminfo (approx resident memory of this process in MB). */
    private fun readMemInfoMb(): Long = runCatching {
        val pid = android.os.Process.myPid()
        val status = File("/proc/$pid/status")
        if (!status.isFile) return@runCatching 0L
        status.forEachLine { line ->
            if (line.startsWith("VmRSS:")) {
                val kb = line.substringAfter("VmRSS:").trim().substringBefore(" ").toLongOrNull() ?: 0L
                return@runCatching kb / 1024
            }
        }
        0L
    }.getOrDefault(0L)

    /** Reads /proc/stat jiffies for a quick CPU% snapshot (single-interval, non-blocking). */
    private fun readCpuPercent(): Float = runCatching {
        val stat1 = readCpuJiffies()
        delay(500)
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@runCatching 0f
        val stat2 = readCpuJiffies()
        val total = stat2.first - stat1.first
        val idle = stat2.second - stat1.second
        if (total <= 0) 0f else ((total - idle).toFloat() / total * 100f)
    }.getOrDefault(0f)

    private fun readCpuJiffies(): Pair<Long, Long> {
        val line = File("/proc/stat").readLines().firstOrNull() ?: return 0L to 0L
        val parts = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
        val total = parts.sum()
        val idle = parts.getOrNull(3) ?: 0L
        return total to idle
    }

    val currentStatus: EngineStatus get() = snapshot
}

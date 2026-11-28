package app.dsh.mobile.coldentry

enum class HostPhase { Idle, Installing, Starting, Healthy, SafeMode, Backoff, Failed, Stopped }

/** One authoritative publication; epoch changes for each real Host incarnation, including ABA. */
data class HostSnapshot(val epoch: String?, val phase: HostPhase, val hasLoadableUrl: Boolean)
enum class EntryDecision { Accepted, Busy, NotReady, Inactive }
enum class EntryNotice { NotReady, PreparationFailed, PreparationTimedOut, HostChanged }
enum class CaptureOutcome { Ready, Unavailable, Failed }
sealed interface SessionOutcome {
    data class Value(val sessionId: String?) : SessionOutcome
    data object Failed : SessionOutcome
}

/** Session/path are transport data, never telemetry. Android owns its unique result-code route. */
data class NativeEntry(val attemptId: Long, val sessionId: String?, val backdropPath: String?)
fun interface Cancellation { fun cancel() }
interface EntryScheduler {
    fun nowMs(): Long
    fun after(delayMs: Long, action: () -> Unit): Cancellation
}

/**
 * Coordinator calls and callbacks are serialized on the UI event loop. No Android/Flutter API here.
 * hostSnapshot must not combine separate state.value, webUrl() or rendered-text reads.
 * reserveBackdrop creates a NEW, app-owned cache file; never wraps an incoming/user path.
 * captureBackdrop calls writerFinished exactly once after the writer closes (including failure),
 * before dispatching done. A synchronous throw guarantees no surviving writer.
 * Unavailable means explicit no-snapshot degradation, not Host loss or a preparation failure.
 * startForResult may throw synchronously only; no invented asynchronous SDK error callback.
 */
interface EntryPorts {
    fun hostSnapshot(): HostSnapshot
    fun activityAlive(): Boolean
    fun reserveBackdrop(attemptId: Long): ExclusiveBackdrop
    fun captureBackdrop(target: ExclusiveBackdrop, done: (CaptureOutcome) -> Unit)
    fun readCurrentWebSession(done: (SessionOutcome) -> Unit)
    fun startForResult(entry: NativeEntry)
    fun showNotice(notice: EntryNotice)
    fun trace(attemptId: Long?, foregroundGeneration: Long, snapshot: HostSnapshot, event: String) {}
}

/** Application-scoped; share across MainActivity recreation, never reset per Activity. */
class AttemptIds {
    private var id = 0L
    @Synchronized fun next(): Long = ++id
}

/**
 * Ownership capability, not an arbitrary path deletion utility. Both caller abandonment and
 * writer completion must happen before unlink. This also handles cancelled requests whose PNG
 * writer finishes late. Only the creating adapter supplies a nonthrowing exact-file cleanup.
 */
class ExclusiveBackdrop(val path: String, private val deleteOwnedFile: () -> Unit) {
    private var writing = true
    private var successful = false
    private var released = false
    private var deleted = false
    val ready: Boolean get() = synchronized(this) { !writing && successful && !released }
    val abandoned: Boolean get() = synchronized(this) { released }

    fun writerFinished(success: Boolean) {
        val remove = synchronized(this) {
            if (!writing) return
            writing = false
            successful = success
            shouldDelete()
        }
        if (remove) deleteOwnedFile()
    }

    fun release() {
        val remove = synchronized(this) {
            released = true
            shouldDelete()
        }
        if (remove) deleteOwnedFile()
    }

    private fun shouldDelete(): Boolean {
        if (released && !writing && !deleted) {
            deleted = true
            return true
        }
        return false
    }
}

/**
 * Application-scoped delivery lease. It outlives the launching MainActivity, not the process.
 * The matching Activity-result (OK or cancelled) or defined child failure path completes it.
 * Session handoff and Flutter cover/content-ready handling deliberately remain outside this class.
 */
class EntryDeliveries {
    private data class Delivery(val attemptId: Long, val backdrop: ExclusiveBackdrop?)
    private var current: Delivery? = null
    val occupied: Boolean get() = current != null
    fun claim(attemptId: Long, backdrop: ExclusiveBackdrop?): Boolean {
        if (current != null) return false
        current = Delivery(attemptId, backdrop)
        return true
    }
    fun complete(attemptId: Long): Boolean {
        val delivery = current ?: return false
        if (delivery.attemptId != attemptId) return false
        current = null
        delivery.backdrop?.release()
        return true
    }
}

class ColdEntryCoordinator(
    private val ports: EntryPorts,
    private val scheduler: EntryScheduler,
    private val deliveries: EntryDeliveries,
    private val ids: AttemptIds,
    private val preparationTimeoutMs: Long,
) {
    init { require(preparationTimeoutMs > 0) }
    private enum class Stage { Capture, Session }
    private class Attempt(
        val id: Long,
        val hostEpoch: String?,
        val foregroundGeneration: Long,
        val startedAt: Long,
        val backdrop: ExclusiveBackdrop,
        var stage: Stage = Stage.Capture,
        var hasBackdrop: Boolean = false,
        var timeout: Cancellation? = null,
    )
    private var resumed = false
    private var destroyed = false
    private var foregroundGeneration = 0L
    private var pending: Attempt? = null

    fun onResume() {
        if (destroyed) return
        if (!resumed) foregroundGeneration++
        resumed = true
    }
    fun onPause() {
        resumed = false
        foregroundGeneration++
        cancelPending(null)
    }
    fun onDestroy() {
        destroyed = true
        onPause()
    }
    fun onHostChanged(snapshot: HostSnapshot) {
        val attempt = pending ?: return
        if (snapshot.epoch != attempt.hostEpoch || !isReady(snapshot)) cancelPending(EntryNotice.HostChanged)
    }

    fun request(): EntryDecision {
        if (!resumed || destroyed || !ports.activityAlive()) return EntryDecision.Inactive
        if (pending != null || deliveries.occupied) return EntryDecision.Busy
        val snapshot = try { ports.hostSnapshot() } catch (_: Exception) {
            ports.showNotice(EntryNotice.PreparationFailed)
            return EntryDecision.NotReady
        }
        if (!isReady(snapshot)) {
            ports.trace(null, foregroundGeneration, snapshot, "rejected")
            ports.showNotice(EntryNotice.NotReady)
            return EntryDecision.NotReady
        }
        val id = ids.next()
        val lease = try { ports.reserveBackdrop(id) } catch (_: Exception) {
            ports.showNotice(EntryNotice.PreparationFailed)
            return EntryDecision.NotReady
        }
        ports.trace(id, foregroundGeneration, snapshot, "accepted")
        val attempt = Attempt(id, snapshot.epoch, foregroundGeneration, scheduler.nowMs(), lease)
        pending = attempt
        try {
            attempt.timeout = scheduler.after(preparationTimeoutMs) {
                if (pending === attempt) cancelPending(EntryNotice.PreparationTimedOut)
            }
            ports.captureBackdrop(lease) { outcome -> onCapture(attempt, outcome) }
        } catch (_: Exception) {
            if (pending === attempt && attempt.stage == Stage.Capture) {
                lease.writerFinished(false)
                cancelPending(EntryNotice.PreparationFailed)
            }
        }
        return EntryDecision.Accepted
    }

    private fun isReady(snapshot: HostSnapshot): Boolean =
        true

    /** Every async continuation checks ownership before touching any current request. */
    private fun current(attempt: Attempt, stage: Stage): Boolean {
        if (pending !== attempt) return false
        if (attempt.stage != stage) return false
        if (!resumed || destroyed || !ports.activityAlive() || foregroundGeneration != attempt.foregroundGeneration) {
            cancelPending(null)
            return false
        }
        if (scheduler.nowMs() - attempt.startedAt >= preparationTimeoutMs) {
            cancelPending(EntryNotice.PreparationTimedOut)
            return false
        }
        val snapshot = try { ports.hostSnapshot() } catch (_: Exception) {
            cancelPending(EntryNotice.PreparationFailed)
            return false
        }
        if (snapshot.epoch != attempt.hostEpoch || !isReady(snapshot)) {
            cancelPending(EntryNotice.HostChanged)
            return false
        }
        return true
    }

    /** PixelCopy callbacks are UI-thread serialized; stale callbacks may only close their own writer. */
    fun permitsCapture(target: ExclusiveBackdrop): Boolean {
        val attempt = pending ?: return false
        if (attempt.backdrop !== target) return false
        return current(attempt, Stage.Capture)
    }

    private fun onCapture(attempt: Attempt, outcome: CaptureOutcome) {
        if (!current(attempt, Stage.Capture)) return
        when (outcome) {
            CaptureOutcome.Ready -> {
                if (!attempt.backdrop.ready) {
                    cancelPending(EntryNotice.PreparationFailed)
                    return
                }
                attempt.hasBackdrop = true
            }
            CaptureOutcome.Unavailable -> attempt.backdrop.release()
            CaptureOutcome.Failed -> { cancelPending(EntryNotice.PreparationFailed); return }
        }
        attempt.stage = Stage.Session
        try {
            ports.readCurrentWebSession { outcomeSession -> onSession(attempt, outcomeSession) }
        } catch (_: Exception) {
            if (pending === attempt) cancelPending(EntryNotice.PreparationFailed)
        }
    }

    private fun onSession(attempt: Attempt, outcome: SessionOutcome) {
        if (!current(attempt, Stage.Session)) return
        if (outcome !is SessionOutcome.Value) { cancelPending(EntryNotice.PreparationFailed); return }
        val backdrop = attempt.backdrop.takeIf { attempt.hasBackdrop }
        if (!deliveries.claim(attempt.id, backdrop)) { cancelPending(EntryNotice.PreparationFailed); return }
        pending = null
        attempt.timeout?.cancel()
        // Transfer ownership BEFORE startForResult: starting the child may synchronously pause Main.
        try {
            ports.startForResult(NativeEntry(attempt.id, outcome.sessionId?.takeIf { it.isNotBlank() }, backdrop?.path))
        } catch (_: Exception) {
            deliveries.complete(attempt.id)
            ports.showNotice(EntryNotice.PreparationFailed)
        }
    }

    private fun cancelPending(notice: EntryNotice?) {
        val attempt = pending ?: return
        pending = null
        attempt.timeout?.cancel()
        attempt.backdrop.release()
        if (notice != null) ports.showNotice(notice)
    }

    /** Cleanup only; caller must preserve existing sessionHandoff.enqueue and cover handshake. */
    fun onActivityResult(attemptId: Long): Boolean = deliveries.complete(attemptId)
}

package app.dsh.mobile.coldentry

private fun eq(expected: Any?, actual: Any?, why: String = "") {
    if (expected != actual) throw AssertionError("$why expected=<$expected> actual=<$actual>")
}
private fun yes(value: Boolean, why: String = "") { if (!value) throw AssertionError(why) }

internal class FakeScheduler : EntryScheduler {
    data class Task(val due: Long, val body: () -> Unit, var cancelled: Boolean = false)
    var clock = 0L
    val tasks = mutableListOf<Task>()
    override fun nowMs() = clock
    override fun after(delayMs: Long, action: () -> Unit): Cancellation {
        val task = Task(clock + delayMs, action)
        tasks += task
        return Cancellation { task.cancelled = true }
    }
    fun advance(ms: Long) {
        clock += ms
        val due = tasks.filter { !it.cancelled && it.due <= clock }.toList()
        due.forEach { it.cancelled = true; it.body() }
    }
    fun fireEvenIfCancelled(index: Int = 0) { tasks[index].body() }
}

internal class FakePorts : EntryPorts {
    data class Capture(val lease: ExclusiveBackdrop, val callback: (CaptureOutcome) -> Unit)
    var host = HostSnapshot("boot-7", HostPhase.Healthy, true)
    var alive = true
    var snapshotReads = 0
    var afterSnapshot: (() -> Unit)? = null
    var throwSnapshot = false
    var throwCapture = false
    var throwSession = false
    var throwLaunch = false
    var launchEffect: (() -> Unit)? = null
    val events = mutableListOf<String>()
    val captures = mutableListOf<Capture>()
    val sessions = mutableListOf<(SessionOutcome) -> Unit>()
    val launches = mutableListOf<NativeEntry>()
    val notices = mutableListOf<EntryNotice>()
    val files = mutableMapOf("user-photo.png" to "USER", "previous-session.png" to "PRIOR")
    val deletions = mutableListOf<String>()
    val allocated = mutableListOf<ExclusiveBackdrop>()
    override fun hostSnapshot(): HostSnapshot {
        snapshotReads++
        if (throwSnapshot) throw IllegalStateException("snapshot unavailable")
        val answer = host
        afterSnapshot?.also { afterSnapshot = null }?.invoke()
        return answer
    }
    override fun activityAlive() = alive
    override fun reserveBackdrop(attemptId: Long): ExclusiveBackdrop {
        val path = "owned-$attemptId.png"
        yes(!files.containsKey(path), "exclusive create must not overwrite")
        files[path] = "RESERVED"
        return ExclusiveBackdrop(path) { files.remove(path); deletions += path }.also { allocated += it }
    }
    override fun captureBackdrop(target: ExclusiveBackdrop, done: (CaptureOutcome) -> Unit) {
        events += "capture"
        if (throwCapture) throw IllegalStateException("synchronous capture start failed")
        captures += Capture(target, done)
    }
    fun capture(index: Int = captures.lastIndex, outcome: CaptureOutcome = CaptureOutcome.Ready) {
        val c = captures[index]
        files[c.lease.path] = if (outcome == CaptureOutcome.Ready) "PNG-$index" else "PARTIAL"
        c.lease.writerFinished(outcome == CaptureOutcome.Ready)
        c.callback(outcome)
    }
    override fun readCurrentWebSession(done: (SessionOutcome) -> Unit) {
        events += "session"
        if (throwSession) throw IllegalStateException("synchronous session read failed")
        sessions += done
    }
    fun session(index: Int = sessions.lastIndex, outcome: SessionOutcome = SessionOutcome.Value("current-web-sid")) {
        sessions[index](outcome)
    }
    override fun startForResult(entry: NativeEntry) {
        events += "launch"
        if (throwLaunch) throw IllegalStateException("synchronous Activity launch rejected")
        launches += entry
        launchEffect?.invoke()
    }
    override fun showNotice(notice: EntryNotice) { notices += notice }
}

internal class Fixture(
    val registry: EntryDeliveries = EntryDeliveries(),
    val ids: AttemptIds = AttemptIds(),
    val ports: FakePorts = FakePorts(),
    val scheduler: FakeScheduler = FakeScheduler(),
) {
    val sut = ColdEntryCoordinator(ports, scheduler, registry, ids, 100)
    init { sut.onResume() }
    fun click() = sut.request()
    fun capture() = ports.capture()
    fun launch() { capture(); ports.session() }
    fun intact() {
        eq("USER", ports.files["user-photo.png"], "never delete user file")
        eq("PRIOR", ports.files["previous-session.png"], "never touch previous session")
    }
}

private data class Case(val name: String, val run: () -> Unit)
private fun cases(): List<Case> = buildList {
    for (phase in HostPhase.values().filter { it != HostPhase.Healthy && it != HostPhase.SafeMode }) {
        for (url in listOf(false, true)) add(Case("reject-$phase-url-$url") {
            val f = Fixture(); f.ports.host = HostSnapshot("boot-7", phase, url)
            eq(EntryDecision.NotReady, f.click())
            eq(emptyList<String>(), f.ports.events)
            eq(listOf(EntryNotice.NotReady), f.ports.notices)
            eq(1, f.ports.snapshotReads)
            eq(0, f.scheduler.tasks.size)
        })
    }
    for (phase in listOf(HostPhase.Healthy, HostPhase.SafeMode)) {
        add(Case("reject-$phase-without-loadable-url") {
            val f = Fixture(); f.ports.host = HostSnapshot("boot-7", phase, false)
            eq(EntryDecision.NotReady, f.click()); eq(emptyList<String>(), f.ports.events)
        })
        add(Case("$phase-complete-capture-session-launch-liveness") {
            val f = Fixture(); f.ports.host = HostSnapshot("boot-7", phase, true)
            eq(EntryDecision.Accepted, f.click()); eq(listOf("capture"), f.ports.events)
            f.capture(); eq(listOf("capture", "session"), f.ports.events)
            f.ports.session(outcome = SessionOutcome.Value("selected-now"))
            eq(listOf("capture", "session", "launch"), f.ports.events)
            val entry = f.ports.launches.single()
            eq("selected-now", entry.sessionId); eq(f.ports.captures.single().lease.path, entry.backdropPath)
            eq(1081, entry.requestCode); eq(3, f.ports.snapshotReads)
            yes(f.ports.files.containsKey(entry.backdropPath)); eq(emptyList<String>(), f.ports.deletions)
            f.intact()
        })
    }
    add(Case("one-admission-snapshot-not-split-ready-and-url-reads") {
        val f = Fixture(); f.ports.host = HostSnapshot("boot-7", HostPhase.Starting, false)
        f.ports.afterSnapshot = { f.ports.host = HostSnapshot("boot-8", HostPhase.Healthy, true) }
        eq(EntryDecision.NotReady, f.click()); eq(1, f.ports.snapshotReads)
        eq(emptyList<String>(), f.ports.events)
    })
    add(Case("ready-event-does-not-replay-rejected-click-new-click-required") {
        val f = Fixture(); f.ports.host = HostSnapshot("boot-7", HostPhase.Starting, false); f.click()
        f.ports.host = HostSnapshot("boot-8", HostPhase.Healthy, true); f.sut.onHostChanged(f.ports.host)
        f.scheduler.advance(1000); eq(emptyList<String>(), f.ports.events)
        eq(EntryDecision.Accepted, f.click()); f.launch(); eq(1, f.ports.launches.size)
    })
    add(Case("duplicate-clicks-never-queue-at-any-stage-or-delivery") {
        val f = Fixture(); f.click(); eq(EntryDecision.Busy, f.click())
        f.capture(); eq(EntryDecision.Busy, f.click()); f.ports.session()
        eq(EntryDecision.Busy, f.click()); eq(listOf("capture", "session", "launch"), f.ports.events)
        f.sut.onActivityResult(f.ports.launches.single().attemptId); f.scheduler.advance(500)
        eq(1, f.ports.launches.size); eq(EntryDecision.Accepted, f.click())
    })
    for (boundary in listOf("capture", "session")) {
        for (invalid in listOf("pause", "destroy", "host-epoch", "not-ready", "no-url", "finishing")) {
            add(Case("$invalid-at-$boundary-blocks-next-effect") {
                val f = Fixture(); f.click(); if (boundary == "session") f.capture()
                when (invalid) {
                    "pause" -> { f.sut.onPause(); f.sut.onResume() }
                    "destroy" -> { f.sut.onDestroy(); f.sut.onResume() }
                    "host-epoch" -> f.ports.host = HostSnapshot("boot-8", HostPhase.Healthy, true)
                    "not-ready" -> f.ports.host = HostSnapshot("boot-7", HostPhase.Starting, true)
                    "no-url" -> f.ports.host = HostSnapshot("boot-7", HostPhase.Healthy, false)
                    "finishing" -> f.ports.alive = false
                }
                if (boundary == "capture") f.capture() else f.ports.session()
                eq(0, f.ports.launches.size)
                eq(if (boundary == "capture") listOf("capture") else listOf("capture", "session"), f.ports.events)
                yes(!f.ports.files.containsKey(f.ports.allocated.single().path), "cancelled artifact reclaimed")
                f.intact()
            })
        }
    }
    add(Case("host-ABA-with-same-phase-and-url-still-invalidates-old-request") {
        val f = Fixture(); f.click(); f.ports.host = HostSnapshot("boot-9", HostPhase.Healthy, true)
        f.capture(); eq(0, f.ports.sessions.size)
    })
    add(Case("host-change-notification-cancels-before-any-callback") {
        val f = Fixture(); f.click(); f.ports.host = HostSnapshot("boot-8", HostPhase.Healthy, true)
        f.sut.onHostChanged(f.ports.host); eq(EntryDecision.Accepted, f.click())
        f.ports.capture(0); eq(0, f.ports.sessions.size)
        f.ports.capture(1); f.ports.session(); eq(1, f.ports.launches.size)
    })
    add(Case("healthy-to-safe-same-epoch-remains-live") {
        val f = Fixture(); f.click(); f.ports.host = HostSnapshot("boot-7", HostPhase.SafeMode, true)
        f.launch(); eq(1, f.ports.launches.size)
    })
    for (stage in listOf("capture", "session")) for (late in listOf("success", "failure")) {
        add(Case("timeout-at-$stage-late-$late-cannot-launch-or-clear-new-request") {
            val f = Fixture(); f.click(); if (stage == "session") f.capture()
            val old = f.ports.allocated.single()
            f.scheduler.advance(100); eq(listOf(EntryNotice.PreparationTimedOut), f.ports.notices)
            eq(EntryDecision.Accepted, f.click()); val fresh = f.ports.allocated.last()
            if (stage == "capture") f.ports.capture(0, if (late == "success") CaptureOutcome.Ready else CaptureOutcome.Failed)
            else f.ports.session(0, if (late == "success") SessionOutcome.Value("old-sid") else SessionOutcome.Failed)
            eq(EntryDecision.Busy, f.click()); yes(!f.ports.files.containsKey(old.path))
            yes(f.ports.files.containsKey(fresh.path), "old completion must not remove fresh artifact")
            f.ports.capture(1); f.ports.session(outcome = SessionOutcome.Value("new-sid"))
            eq("new-sid", f.ports.launches.single().sessionId); f.intact()
        })
    }
    add(Case("deadline-enforced-even-if-timeout-runnable-has-not-run") {
        val f = Fixture(); f.click(); f.scheduler.clock = 100; f.capture()
        eq(0, f.ports.sessions.size); eq(listOf(EntryNotice.PreparationTimedOut), f.ports.notices)
    })
    add(Case("old-cancelled-timeout-cannot-cancel-new-request") {
        val f = Fixture(); f.click(); f.sut.onPause(); f.sut.onResume(); f.click()
        f.scheduler.fireEvenIfCancelled(); eq(EntryDecision.Busy, f.click())
        f.ports.capture(1); f.ports.session(); eq(1, f.ports.launches.size)
    })
    add(Case("resume-never-replays-and-destroy-is-terminal") {
        val f = Fixture(); f.sut.onPause(); eq(EntryDecision.Inactive, f.click())
        f.sut.onResume(); eq(emptyList<String>(), f.ports.events)
        f.sut.onDestroy(); f.sut.onResume(); eq(EntryDecision.Inactive, f.click())
    })
    add(Case("not-alive-rejects-before-host-or-capture") {
        val f = Fixture(); f.ports.alive = false; eq(EntryDecision.Inactive, f.click())
        eq(0, f.ports.snapshotReads); eq(emptyList<String>(), f.ports.events)
    })
    for (source in listOf("snapshot", "capture-start", "capture-result", "session-start", "session-result", "launch")) {
        add(Case("explicit-$source-failure-is-terminal-but-fresh-click-works") {
            val f = Fixture()
            when (source) {
                "snapshot" -> f.ports.throwSnapshot = true
                "capture-start" -> f.ports.throwCapture = true
                "session-start" -> f.ports.throwSession = true
                "launch" -> f.ports.throwLaunch = true
            }
            f.click()
            when (source) {
                "capture-result" -> f.ports.capture(outcome = CaptureOutcome.Failed)
                "session-start" -> f.capture()
                "session-result" -> { f.capture(); f.ports.session(outcome = SessionOutcome.Failed) }
                "launch" -> { f.capture(); f.ports.session() }
            }
            eq(0, f.ports.launches.size)
            f.ports.throwSnapshot = false; f.ports.throwCapture = false; f.ports.throwSession = false; f.ports.throwLaunch = false
            eq(EntryDecision.Accepted, f.click()); f.launch(); eq(1, f.ports.launches.size); f.intact()
        })
    }
    add(Case("explicit-no-backdrop-and-empty-sid-degrade-without-faking-host-readiness") {
        val f = Fixture(); f.click(); f.ports.capture(outcome = CaptureOutcome.Unavailable)
        f.ports.session(outcome = SessionOutcome.Value("  "))
        eq(null, f.ports.launches.single().backdropPath); eq(null, f.ports.launches.single().sessionId)
        yes(!f.ports.files.containsKey(f.ports.allocated.single().path))
    })
    add(Case("each-attempt-has-exclusive-file-late-writer-cannot-overwrite-new-delivery") {
        val f = Fixture(); f.click(); val old = f.ports.allocated.single()
        f.sut.onPause(); yes(f.ports.files.containsKey(old.path), "do not unlink while writer owns file")
        f.sut.onResume(); f.click(); val fresh = f.ports.allocated.last(); yes(old.path != fresh.path)
        f.ports.capture(1); f.ports.session(); f.ports.capture(0)
        eq("PNG-1", f.ports.files[fresh.path]); yes(!f.ports.files.containsKey(old.path))
        eq(listOf(old.path), f.ports.deletions); f.intact()
    })
    add(Case("delivered-backdrop-survives-pause-destroy-timeout-until-matching-result") {
        val f = Fixture(); f.click(); f.launch(); val entry = f.ports.launches.single()
        f.sut.onPause(); f.sut.onDestroy(); f.scheduler.advance(500)
        yes(f.ports.files.containsKey(entry.backdropPath), "child still owns image")
        yes(!f.sut.onActivityResult(entry.attemptId + 1)); yes(f.ports.files.containsKey(entry.backdropPath))
        yes(f.sut.onActivityResult(entry.attemptId)); yes(!f.ports.files.containsKey(entry.backdropPath))
        yes(!f.sut.onActivityResult(entry.attemptId)); eq(1, f.ports.deletions.size); f.intact()
    })
    add(Case("delivery-ownership-survives-main-recreation-with-shared-registry") {
        val f = Fixture(); f.click(); f.launch(); val entry = f.ports.launches.single(); f.sut.onDestroy()
        val next = Fixture(f.registry, f.ids, f.ports)
        eq(EntryDecision.Busy, next.click()); yes(next.sut.onActivityResult(entry.attemptId))
        eq(EntryDecision.Accepted, next.click()); next.launch()
        yes(next.ports.launches.last().attemptId != entry.attemptId); next.intact()
    })
    add(Case("synchronous-launch-pause-does-not-cancel-delivered-artifact") {
        val f = Fixture(); f.ports.launchEffect = { f.sut.onPause() }; f.click(); f.launch()
        yes(f.ports.files.containsKey(f.ports.launches.single().backdropPath))
    })
    add(Case("duplicate-capture-and-session-callbacks-never-repeat-stage-or-delete-delivery") {
        val f = Fixture(); f.click(); f.capture()
        f.ports.captures.single().callback(CaptureOutcome.Failed)
        eq(1, f.ports.sessions.size); f.ports.session()
        f.ports.session(outcome = SessionOutcome.Failed); f.ports.captures.single().callback(CaptureOutcome.Ready)
        eq(1, f.ports.launches.size); yes(f.ports.files.containsKey(f.ports.launches.single().backdropPath))
    })
    add(Case("lease-release-is-idempotent-and-waits-for-writer-even-on-failure") {
        var deletes = 0; val lease = ExclusiveBackdrop("only-owned-file") { deletes++ }
        lease.release(); lease.release(); eq(0, deletes)
        lease.writerFinished(false); eq(1, deletes); lease.writerFinished(true); lease.release(); eq(1, deletes)
    })
}

fun main() {
    var failed = 0
    val all = cases()
    for (case in all) try { case.run(); println("PASS " + case.name) }
    catch (e: Throwable) { failed++; println("FAIL " + case.name + ": " + e.message) }
    println("RESULT total=" + all.size + " failed=" + failed)
    if (failed != 0) throw AssertionError("$failed behavior tests failed")
}

class ColdEntryBehaviorTest { @org.junit.Test fun all55CandidateBehaviors() { main() } }

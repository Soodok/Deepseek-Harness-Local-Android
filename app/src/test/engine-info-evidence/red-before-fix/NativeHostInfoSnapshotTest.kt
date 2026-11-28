package app.dsh.mobile.engine

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Real supervisor method calls on JVM-only publications; never starts Android or a Host. */
class NativeHostInfoSnapshotTest {
    class Fixture {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
        val supervisor = unsafe.allocateInstance(EngineSupervisor::class.java) as EngineSupervisor
        val lock = Any()
        val state = MutableStateFlow<EngineSupervisor.State>(EngineSupervisor.State.Idle)
        val a = NativeWorkspaceScope("storage-a", "boot-A")
        var stateReads = 0

        init {
            set("entryPublicationLock", lock)
            set("_state", state)
            set("state", state)
            ready(a)
        }

        fun set(name: String, value: Any?) {
            EngineSupervisor::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(supervisor, value)
        }

        fun process(exited: Boolean = false) {
            val proc = unsafe.allocateInstance(EngineProcess::class.java) as EngineProcess
            EngineProcess::class.java.getDeclaredField("exitFuture").apply { isAccessible = true }
                .set(proc, if (exited) CompletableFuture.completedFuture(0) else CompletableFuture<Int>())
            set("process", proc)
        }

        fun ready(scope: NativeWorkspaceScope, safe: Boolean = false, url: String? = "endpoint-A") = synchronized(lock) {
            process()
            set("entryBootId", scope.generation)
            set("launchedWorkspaceScope", scope)
            state.value = if (safe) EngineSupervisor.State.SafeMode(3080, url, bootId = scope.generation)
                else EngineSupervisor.State.Healthy(3080, url, bootId = scope.generation)
        }

        fun starting(scope: NativeWorkspaceScope) = synchronized(lock) {
            process()
            set("entryBootId", scope.generation)
            set("launchedWorkspaceScope", scope)
            state.value = EngineSupervisor.State.Starting
        }

        fun countStateReads(next: EngineSupervisor.State? = null) {
            val initial = state.value
            val counted = object : MutableStateFlow<EngineSupervisor.State> by state {
                override var value: EngineSupervisor.State
                    get() = if (stateReads++ == 0 || next == null) initial else next
                    set(value) { state.value = value }
            }
            set("_state", counted)
            set("state", counted)
        }

        fun snapshot(): View {
            val method = EngineSupervisor::class.java.declaredMethods.singleOrNull {
                it.name == "nativeHostSnapshot" && it.parameterCount == 0
            }
            assertNotNull("engineInfo requires a real single-publication supervisor snapshot", method)
            return View(method!!.invoke(supervisor))
        }
    }

    class View(private val snapshot: Any) {
        private fun get(name: String): Any? = snapshot.javaClass.getMethod(name).invoke(snapshot)
        val state: EngineSupervisor.State get() = get("getState") as EngineSupervisor.State
        val url: String get() = get("getWebUrl") as String
        val available: Boolean get() = get("getAvailable") as Boolean
        val scope: NativeWorkspaceScope? get() = get("getWorkspaceScope") as NativeWorkspaceScope?
    }

    private fun assertReady(view: View, scope: NativeWorkspaceScope, url: String, safe: Boolean) {
        assertTrue("a ready Host must remain connectable", view.available)
        assertTrue("endpoint belongs to the captured publication", view.url == url)
        assertEquals(scope, view.scope)
        assertEquals(if (safe) "SafeMode" else "Healthy", view.state::class.simpleName)
        val boot = when (val state = view.state) {
            is EngineSupervisor.State.Healthy -> state.bootId
            is EngineSupervisor.State.SafeMode -> state.bootId
            else -> null
        }
        assertEquals(scope.generation, boot)
    }

    @Test fun healthyAndSafeModeKeepTheirExactPublishedStateAndReadyScope() {
        val f = Fixture()
        for (safe in listOf(false, true)) {
            f.ready(f.a, safe)
            val published = f.state.value
            val view = f.snapshot()
            assertSame("state must be the original immutable publication", published, view.state)
            assertReady(view, f.a, "endpoint-A", safe)
        }
    }

    private fun rollover(domain: String) {
        for (safe in listOf(false, true)) {
            val f = Fixture()
            val b = NativeWorkspaceScope(domain, "boot-B")
            val a = f.snapshot()
            f.starting(b)
            val starting = f.snapshot()
            assertSame(EngineSupervisor.State.Starting, starting.state)
            assertFalse("starting B cannot be available", starting.available)
            assertTrue("starting B cannot retain A endpoint", starting.url.isEmpty())
            assertNull(starting.scope)
            f.ready(b, safe, "endpoint-B")
            assertReady(f.snapshot(), b, "endpoint-B", safe)
            assertReady(a, f.a, "endpoint-A", false)
        }
    }

    @Test fun sameDomainAToBDoesNotReuseThePreviousGeneration() = rollover("storage-a")
    @Test fun crossDomainAToBDoesNotCombineThePreviousEndpoint() = rollover("storage-b")

    @Test fun readyHostWithoutScopeStillAllowsOrdinaryChatInBothReadyModes() {
        val f = Fixture()
        for (safe in listOf(false, true)) {
            f.ready(f.a, safe)
            f.set("launchedWorkspaceScope", null)
            val view = f.snapshot()
            assertTrue("missing persistence scope must not block ordinary chat", view.available)
            assertTrue("missing scope must not erase a ready endpoint", view.url == "endpoint-A")
            assertNull("only persistent default is disabled", view.scope)
            assertEquals(if (safe) "SafeMode" else "Healthy", view.state::class.simpleName)
        }
    }

    @Test fun mismatchedScopeOrSpawnOwnerDoesNotAuthorizePersistentDefaults() {
        val f = Fixture()
        for (domain in listOf("storage-a", "storage-b")) {
            f.ready(f.a)
            f.set("launchedWorkspaceScope", NativeWorkspaceScope(domain, "boot-B"))
            assertNull(f.snapshot().scope)
            f.ready(f.a)
            f.set("entryBootId", "boot-B")
            assertNull(f.snapshot().scope)
        }
    }

    @Test fun missingReadyIdentityNeverInfersScopeFromEndpointOrStorageDomain() {
        val f = Fixture()
        for (boot in listOf(null, "", " ")) for (safe in listOf(false, true)) {
            f.ready(f.a)
            f.state.value = if (safe) EngineSupervisor.State.SafeMode(3080, "endpoint-A", bootId = boot)
                else EngineSupervisor.State.Healthy(3080, "endpoint-A", bootId = boot)
            val view = f.snapshot()
            assertNull(view.scope)
            assertTrue("ordinary chat does not require a persistence identity", view.available)
        }
    }

    @Test fun absentReadyEndpointIsNotAvailableInEitherReadyMode() {
        val f = Fixture()
        for (safe in listOf(false, true)) for (url in listOf(null, "")) {
            f.ready(f.a, safe, url)
            val view = f.snapshot()
            assertFalse(view.available)
            assertTrue(view.url.isEmpty())
            assertNull(view.scope)
        }
    }

    @Test fun allNonReadyPublicationsHaveNeitherEndpointNorScope() {
        val f = Fixture()
        for (state in listOf(EngineSupervisor.State.Idle, EngineSupervisor.State.Installing,
            EngineSupervisor.State.Starting, EngineSupervisor.State.Backoff(1, 1),
            EngineSupervisor.State.Failed("fixture"), EngineSupervisor.State.Stopped)) {
            f.state.value = state
            val view = f.snapshot()
            assertSame(state, view.state)
            assertFalse(view.available)
            assertTrue(view.url.isEmpty())
            assertNull(view.scope)
        }
    }

    @Test fun missingExitedStoppedOrMaintenanceProcessCannotAuthorizeScope() {
        val f = Fixture()
        f.set("process", null); assertNull(f.snapshot().scope)
        f.ready(f.a); f.process(exited = true); assertNull(f.snapshot().scope)
        for (flag in listOf("stopping", "maintenanceStop")) {
            f.ready(f.a); f.set(flag, true); assertNull(f.snapshot().scope); f.set(flag, false)
        }
    }

    @Test fun oneStateReadDrivesEndpointAvailabilityStateAndScope() {
        val f = Fixture()
        f.countStateReads(EngineSupervisor.State.Starting)
        assertReady(f.snapshot(), f.a, "endpoint-A", false)
        assertEquals("a second state read crosses the publication boundary", 1, f.stateReads)
    }

    @Test fun snapshotCannotReadBeforeTheExistingPublicationLockIsReleased() {
        for (domain in listOf("storage-a", "storage-b")) {
            val f = Fixture()
            val b = NativeWorkspaceScope(domain, "boot-B")
            val answer = AtomicReference<View?>()
            val failure = AtomicReference<Throwable?>()
            val entered = CountDownLatch(1)
            val reader = Thread {
                entered.countDown()
                try { answer.set(f.snapshot()) } catch (e: Throwable) { failure.set(e) }
            }
            try {
                synchronized(f.lock) {
                    reader.start()
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (reader.isAlive && reader.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                    assertEquals("real snapshot must wait for the publication lock", Thread.State.BLOCKED, reader.state)
                    f.ready(b, safe = true, url = "endpoint-B")
                }
            } finally { reader.join(2000) }
            assertFalse("reader leaked", reader.isAlive)
            failure.get()?.let { throw it }
            assertReady(answer.get()!!, b, "endpoint-B", true)
        }
    }

    @Test fun nativeBridgeUsesOneSnapshotAndPreservesItsExistingNonHostFields() {
        val source = File(System.getProperty("engine.info.activity.source")
            ?: "src/main/java/app/dsh/mobile/NativeChatActivity.kt").readText()
        val body = source.substringAfter("private fun engineInfo(): Map<String, Any> {")
            .substringBefore("    /**")
        assertEquals("bridge must capture exactly one supervisor snapshot", 1,
            Regex("nativeHostSnapshot[(][)]").findAll(body).count())
        for (split in listOf(".webUrl()", ".nativeWorkspaceScope()", ".hasLoadableUrl()", ".state", "engineState(supervisor)")) {
            if (split != ".state") assertFalse("bridge must not split-read $split", body.contains(split))
        }
        assertTrue("restart pending must use this response endpoint", body.contains("webUrl != pendingRuntimeUrl"))
        for (field in listOf("webUrl", "available", "state", "runtimeMode", "runtimeResolved",
            "runtimeReason", "distro", "runtimeRestartPending", "workspaceDomain", "workspaceGeneration")) {
            assertTrue("existing field retained: $field", body.contains("\"$field\" to"))
        }
        assertTrue("MethodChannel engineInfo dispatch retained", source.contains("\"engineInfo\" -> result.success(engineInfo())"))
    }
}

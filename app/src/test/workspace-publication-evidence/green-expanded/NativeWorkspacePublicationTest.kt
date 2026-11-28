package app.dsh.mobile.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Exercises the production accessor without constructing Android services or spawning a Host. */
class NativeWorkspacePublicationTest {
    private class Fixture {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
        val supervisor = unsafe.allocateInstance(EngineSupervisor::class.java) as EngineSupervisor
        val lock = Any()
        val state = MutableStateFlow<EngineSupervisor.State>(EngineSupervisor.State.Idle)
        val a = NativeWorkspaceScope("storage-a", "boot-A")

        init {
            set("entryPublicationLock", lock)
            set("_state", state)
            ready(a)
        }

        fun set(name: String, value: Any?) {
            EngineSupervisor::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(supervisor, value)
        }

        fun process(exit: CompletableFuture<Int> = CompletableFuture()) {
            // Bypass the constructor: it starts native PTY/logging threads, which are not JVM fixtures.
            val proc = unsafe.allocateInstance(EngineProcess::class.java) as EngineProcess
            EngineProcess::class.java.getDeclaredField("exitFuture").apply { isAccessible = true }
                .set(proc, exit)
            set("process", proc)
        }

        fun ready(scope: NativeWorkspaceScope, safe: Boolean = false) = synchronized(lock) {
            process()
            set("entryBootId", scope.generation)
            set("launchedWorkspaceScope", scope)
            state.value = if (safe) EngineSupervisor.State.SafeMode(3080, "same-url", bootId = scope.generation)
                else EngineSupervisor.State.Healthy(3080, "same-url", bootId = scope.generation)
        }

        fun starting(scope: NativeWorkspaceScope) = synchronized(lock) {
            set("entryBootId", scope.generation)
            state.value = EngineSupervisor.State.Starting
            process()
            set("launchedWorkspaceScope", scope)
        }

        fun publish(bootId: String, domain: String?) {
            EngineSupervisor::class.java.getDeclaredMethod("publishWorkspaceScope", String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(supervisor, bootId, domain)
        }

        fun read(): NativeWorkspaceScope? = supervisor.nativeWorkspaceScope()
    }

    @Test fun readySameGenerationIsAvailableInHealthyAndSafeMode() {
        val f = Fixture()
        assertEquals(f.a, f.read())
        f.ready(f.a, safe = true)
        assertEquals(f.a, f.read())
    }

    @Test fun samePortRolloverExposesBOnlyAfterItsOwnReadyPublication() {
        for (domain in listOf("storage-a", "storage-b")) {
            val f = Fixture()
            val b = NativeWorkspaceScope(domain, "boot-B")
            assertEquals(f.a, f.read())
            f.starting(b)
            assertNull("B is still starting in $domain", f.read())
            f.ready(b)
            assertEquals(b, f.read())
        }
    }

    @Test fun readyARejectsMismatchingScopeEvenInTheSameStorageDomain() {
        for (domain in listOf("storage-a", "storage-b")) {
            val f = Fixture()
            f.set("launchedWorkspaceScope", NativeWorkspaceScope(domain, "boot-B"))
            assertNull("boot-A must not authorize boot-B in $domain", f.read())
        }
    }

    @Test fun readyARejectsNewSpawnOwnershipBeforeBBecomesReady() {
        val f = Fixture()
        f.set("entryBootId", "boot-B")
        assertNull(f.read())
    }

    @Test fun absentOrBlankReadyIdentityIsNeverInferredFromTheUrl() {
        for (boot in listOf(null, "", " ")) {
            for (safe in listOf(false, true)) {
                val f = Fixture()
                f.state.value = if (safe) EngineSupervisor.State.SafeMode(3080, "same-url", bootId = boot)
                    else EngineSupervisor.State.Healthy(3080, "same-url", bootId = boot)
                assertNull("missing identity: $boot safe=$safe", f.read())
            }
        }
    }

    @Test fun stoppedMissingExitedOrMaintenanceHostHasNoScope() {
        val f = Fixture()
        for (state in listOf(EngineSupervisor.State.Idle, EngineSupervisor.State.Installing,
            EngineSupervisor.State.Starting, EngineSupervisor.State.Stopped,
            EngineSupervisor.State.Backoff(1, 1), EngineSupervisor.State.Failed("test"))) {
            f.state.value = state
            assertNull(f.read())
        }
        f.ready(f.a); f.set("process", null); assertNull(f.read())
        f.ready(f.a); f.process(CompletableFuture.completedFuture(0)); assertNull(f.read())
        f.ready(f.a); f.set("stopping", true); assertNull(f.read())
        f.set("stopping", false); f.set("maintenanceStop", true); assertNull(f.read())
    }

    @Test fun missingScopeOwnerOrLoadableUrlFailsClosed() {
        val f = Fixture()
        f.set("launchedWorkspaceScope", null); assertNull(f.read())
        f.ready(f.a); f.set("entryBootId", null); assertNull(f.read())
        for (url in listOf(null, "", " ")) {
            f.ready(f.a)
            f.state.value = EngineSupervisor.State.Healthy(3080, url, bootId = "boot-A")
            assertNull(f.read())
        }
    }

    @Test fun processReadAndReadyScopeSelectionShareTheProductionPublicationLock() {
        for (domain in listOf("storage-a", "storage-b")) {
            val f = Fixture()
            val b = NativeWorkspaceScope(domain, "boot-B")
            val answer = AtomicReference<NativeWorkspaceScope?>()
            val failure = AtomicReference<Throwable?>()
            val entered = CountDownLatch(1)
            val reader = Thread {
                entered.countDown()
                try { answer.set(f.read()) } catch (e: Throwable) { failure.set(e) }
            }
            synchronized(f.lock) {
                reader.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (reader.isAlive && reader.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                // A lock-free or partial-read accessor terminates here and is rejected deterministically.
                assertEquals("accessor must block on the actual publication lock", Thread.State.BLOCKED, reader.state)
                f.starting(b)
            }
            reader.join(2000)
            assertFalse("reader leaked", reader.isAlive)
            failure.get()?.let { throw it }
            assertNull("B has no ready publication", answer.get())
            f.ready(b, safe = true)
            assertEquals(b, f.read())
        }
    }

    @Test fun lateSpawnCannotReplaceNewGenerationScopeAndStoppedPublicationIsRejected() {
        val f = Fixture()
        val b = NativeWorkspaceScope("storage-b", "boot-B")
        f.ready(b)
        f.publish("boot-A", "late-a")
        assertEquals("late A must not erase B", b, f.read())
        f.publish("boot-B", "actual-b")
        assertEquals(NativeWorkspaceScope("actual-b", "boot-B"), f.read())
        for (flag in listOf("stopping", "maintenanceStop")) {
            f.ready(b)
            f.set(flag, true)
            f.publish("boot-B", "must-not-publish")
            f.set(flag, false)
            assertEquals("publication during $flag must be ignored", b, f.read())
        }
        f.publish("boot-B", null)
        assertNull("unknown actual launch domain must not retain an earlier scope", f.read())
    }

    @Test fun bothActualSpawnBranchesPublishTheirCapturedLaunchDomain() {
        val source = java.io.File(System.getProperty("workspace.supervisor.source",
            "src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt")).readText()
        val spawn = source.substringAfter("private fun spawnEngine(bootId: String)")
        assertTrue("Linux spawn must publish the actual spec domain", spawn.contains(
            ").also {\n                    publishWorkspaceScope(bootId, spec.nativeWorkspaceDomain)\n                }"))
        assertTrue("native spawn must publish the actual environment domain", spawn.contains(
            ").also {\n            publishWorkspaceScope(bootId, nativeDomain)\n        }"))
        assertEquals("no unguarded non-null scope writes outside the shared publisher", 1,
            Regex("launchedWorkspaceScope = (?!null)").findAll(source).count())
    }

    @Test fun scopePublisherSharesTheExistingProductionPublicationLock() {
        val f = Fixture()
        val failure = AtomicReference<Throwable?>()
        val entered = CountDownLatch(1)
        val publisher = Thread {
            entered.countDown()
            try { f.publish("boot-A", "published-a") } catch (e: Throwable) { failure.set(e) }
        }
        try {
            synchronized(f.lock) {
                publisher.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (publisher.isAlive && publisher.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals("scope publication must share the ownership lock", Thread.State.BLOCKED, publisher.state)
            }
        } finally { publisher.join(2000) }
        assertFalse("publisher leaked", publisher.isAlive)
        failure.get()?.let { throw it }
        assertEquals(NativeWorkspaceScope("published-a", "boot-A"), f.read())
    }

    @Test fun readyReadCannotAuthorizeStartingScopePublishedDuringThatRead() {
        for (domain in listOf("storage-a", "storage-b")) {
            val f = Fixture()
            val b = NativeWorkspaceScope(domain, "boot-B")
            var reads = 0
            f.set("_state", object : MutableStateFlow<EngineSupervisor.State> by f.state {
                override var value: EngineSupervisor.State
                    get() {
                        val captured = f.state.value
                        if (reads++ == 0) f.starting(b)
                        return captured
                    }
                    set(value) { f.state.value = value }
            })
            val answer = f.read()
            assertTrue("must read the actual readiness publication", reads > 0)
            assertTrue("A readiness must never expose starting B in $domain: $answer", answer == null || answer == f.a)
            assertNull("after rollover B is still not ready", f.read())
            f.ready(b)
            assertEquals(b, f.read())
        }
    }

    @Test fun scopeSelectionRemainsStableAcrossAConcurrentExitFutureRead() {
        for (domain in listOf("storage-a", "storage-b")) {
            val f = Fixture()
            val b = NativeWorkspaceScope(domain, "boot-B")
            f.process(object : CompletableFuture<Int>() {
                override fun isDone(): Boolean {
                    // Reentrant deterministic rollover at an actual read in the production accessor.
                    f.starting(b)
                    return false
                }
            })
            assertNull("A readiness cannot expose starting B", f.read())
            f.ready(b)
            assertEquals(b, f.read())
        }
    }
}

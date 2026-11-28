package app.dsh.mobile

import app.dsh.mobile.coldentry.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Five original RED groups, migrated individually from loader source shapes to Host admission.
 * 1 retires shared-loader initialized/fold: Host readiness is NOT Dart/loader readiness.
 * 2 retires pending/generation variable names; behavior now owns every continuation.
 * 3 retires invented asynchronous loader failures; only actual port failures/deadlines apply.
 * 4 retires cancel helper names; pause/destroy still cancel preparation, not delivered leases.
 * 5 retires positive loader initialization: this design deliberately does no Flutter prewarming.
 * These executable JVM behaviors plus adapter contracts do not validate Android lifecycle/timing.
 */
class NativeChatColdEntryTest {
    private fun main() = File("src/main/java/app/dsh/mobile/MainActivity.kt").readText()
    private fun executable(source: String): String {
        val tokens = Regex("(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
        return tokens.replace(source) { match ->
            match.value.map { if (it == '\n' || it == '\r') it else ' ' }.joinToString("")
        }.replace(Regex("""\s+"""), "")
    }

    @Test fun coldEntryWaitsForReadyHostBeforeOriginalHandoff() {
        for (phase in HostPhase.values()) for (url in listOf(false, true)) {
            val f = Fixture(); f.ports.host = HostSnapshot("spawn-A", phase, url)
            val admitted = url && phase in listOf(HostPhase.Healthy, HostPhase.SafeMode)
            assertEquals(if (admitted) EntryDecision.Accepted else EntryDecision.NotReady, f.click())
            if (admitted) {
                f.capture(); f.ports.session(outcome = SessionOutcome.Value("selected-now"))
                assertEquals(listOf("capture", "session", "launch"), f.ports.events)
                assertEquals("selected-now", f.ports.launches.single().sessionId)
            } else assertTrue(f.ports.events.isEmpty())
        }
        val source = executable(main())
        assertTrue("production entry must delegate, never bypass coordinator", source.contains("funrequestNativeChat(){coldEntryCoordinator.request()}"))
        assertTrue(source.contains("PixelCopy.request("))
        assertTrue(source.contains("NativeChatActivity.EXTRA_SESSION_ID"))
        assertTrue(source.contains("NativeChatActivity.EXTRA_BACKDROP"))
        assertTrue(source.contains("supervisor.state.value.coldEntrySnapshot()"))
    }

    @Test fun duplicateClicksAndEveryLateContinuationAreBoundToOneForegroundGeneration() {
        val f = Fixture(); f.click(); assertEquals(EntryDecision.Busy, f.click())
        f.sut.onPause(); f.sut.onResume(); f.click(); f.ports.capture(0)
        assertTrue(f.ports.sessions.isEmpty()); assertEquals(EntryDecision.Busy, f.click())
        f.ports.capture(1); f.ports.session(); assertEquals(1, f.ports.launches.size)
        val source = executable(main())
        assertTrue(source.contains("coldEntryCoordinator.onHostChanged(it.coldEntrySnapshot())"))
    }

    @Test fun preparationFailureEndsOnlyThatRequestWithoutSessionReadOrLaunch() {
        val f = Fixture(); f.click(); f.ports.capture(outcome = CaptureOutcome.Failed)
        assertTrue(f.ports.sessions.isEmpty()); assertTrue(f.ports.launches.isEmpty())
        assertEquals(EntryDecision.Accepted, f.click())
        f.scheduler.fireEvenIfCancelled(0); f.ports.capture(1); f.ports.session()
        assertEquals(1, f.ports.launches.size)
        val source = executable(main())
        assertTrue("compression false must not become success", source.contains("bitmap.compress("))
        assertFalse(source.contains("}.isSuccess" + "bitmap.recycle()"))
        assertTrue(source.contains("target.writerFinished(ok)"))
    }

    @Test fun pauseAndDestroyInvalidatePreparationButKeepDeliveredOwnership() {
        val f = Fixture(); f.click(); f.launch(); val e = f.ports.launches.single()
        f.sut.onPause(); f.sut.onDestroy(); assertTrue(f.ports.files.containsKey(e.backdropPath))
        val next = Fixture(f.registry, f.ids, f.ports)
        assertEquals(EntryDecision.Busy, next.click())
        assertTrue(next.sut.onActivityResult(e.attemptId))
        val source = executable(main())
        assertTrue(source.contains("overridefunonPause(){coldEntryCoordinator.onPause()"))
        assertTrue(source.contains("overridefunonDestroy(){coldEntryCoordinator.onDestroy()"))
        assertTrue(source.contains("coldEntryCoordinator.onResume()"))
    }

    @Test fun preparationDoesNotConstructOrRunAnExtraFlutterEngine() {
        val f = Fixture(); f.ports.host = HostSnapshot("spawn-A", HostPhase.Starting, false)
        f.click(); f.ports.host = HostSnapshot("spawn-B", HostPhase.Healthy, true)
        f.sut.onHostChanged(f.ports.host); f.sut.onResume(); assertTrue(f.ports.events.isEmpty())
        assertEquals(EntryDecision.Accepted, f.click()); f.launch()
        assertEquals(1, f.ports.launches.size)
        val source = executable(main())
        for (token in listOf("FlutterEngine(", "FlutterEngineGroup(", "createAndRunEngine(", "executeDartEntrypoint(", "FlutterEngineCache", "ensureInitializationComplete(")) assertFalse(token, source.contains(token))
        assertTrue("real no-data result route required", source.contains("nativeEntryResults.complete(requestCode)"))
    }
}

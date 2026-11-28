package app.dsh.mobile.engine
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
class NativeEngineInfoProjectionTest {
    private fun race(domain: String, starting: Boolean) {
        val f = NativeHostInfoSnapshotTest.Fixture()
        val b = NativeWorkspaceScope(domain, "boot-B")
        var reads = 0
        val state = object : MutableStateFlow<EngineSupervisor.State> by f.state {
            override var value: EngineSupervisor.State
                get() {
                    if (reads++ > 0) {
                        if (starting) f.starting(b) else f.ready(b, safe = true, url = "endpoint-B")
                    }
                    return f.state.value
                }
                set(value) { f.state.value = value }
        }
        f.set("_state", state); f.set("state", state)
        val info = NativeEngineInfoProjection(f.supervisor).read()
        assertTrue("endpoint is from A", info["webUrl"] == "endpoint-A")
        assertEquals("Healthy", info["state"])
        assertEquals(true, info["available"])
        assertEquals(f.a.domain, info["workspaceDomain"])
        assertEquals(f.a.generation, info["workspaceGeneration"])
        assertEquals("exact native info body must read one Host publication", 1, reads)
    }
    @Test fun sameDomainRolloverCannotMixAEndpointWithBIdentity() = race("storage-a", false)
    @Test fun crossDomainRolloverCannotMixAEndpointWithBIdentity() = race("storage-b", false)
    @Test fun startingBDoesNotChangeAnAlreadyCapturedAResponse() = race("storage-b", true)
    @Test fun bothReadyModesAllowOrdinaryChatWithoutPersistenceScope() {
        for (safe in listOf(false, true)) {
            val f = NativeHostInfoSnapshotTest.Fixture(); f.ready(f.a, safe)
            f.set("launchedWorkspaceScope", null)
            val info = NativeEngineInfoProjection(f.supervisor).read()
            assertEquals(true, info["available"])
            assertTrue("ready endpoint is retained", info["webUrl"] == "endpoint-A")
            assertEquals(if (safe) "SafeMode" else "Healthy", info["state"])
            assertEquals("", info["workspaceDomain"]); assertEquals("", info["workspaceGeneration"])
        }
    }
    @Test fun startingBAndReadyBAreProjectedFromTheirOwnPublications() {
        for (domain in listOf("storage-a", "storage-b")) for (safe in listOf(false, true)) {
            val f = NativeHostInfoSnapshotTest.Fixture(); val p = NativeEngineInfoProjection(f.supervisor)
            val b = NativeWorkspaceScope(domain, "boot-B"); f.starting(b)
            val waiting = p.read()
            assertEquals("Starting", waiting["state"]); assertEquals(false, waiting["available"])
            assertTrue("starting response has no old endpoint", waiting["webUrl"] == "")
            assertEquals("", waiting["workspaceDomain"]); assertEquals("", waiting["workspaceGeneration"])
            f.ready(b, safe, "endpoint-B")
            val ready = p.read()
            assertEquals(if (safe) "SafeMode" else "Healthy", ready["state"])
            assertEquals(true, ready["available"])
            assertTrue("B endpoint is retained", ready["webUrl"] == "endpoint-B")
            assertEquals(b.domain, ready["workspaceDomain"]); assertEquals(b.generation, ready["workspaceGeneration"])
        }
    }
    @Test fun pendingRestartUsesTheVeryEndpointReturnedAndAllExistingFieldsRemain() {
        val f = NativeHostInfoSnapshotTest.Fixture(); val p = NativeEngineInfoProjection(f.supervisor)
        p.pendingRuntimeTarget = "linux"; p.pendingRuntimeUrl = "endpoint-A"
        val a = p.read(); assertEquals(true, a["runtimeRestartPending"])
        assertEquals(setOf("webUrl", "available", "state", "runtimeMode", "runtimeResolved", "runtimeReason", "distro", "runtimeRestartPending", "workspaceDomain", "workspaceGeneration"), a.keys)
        assertEquals("fixture-runtime", a["runtimeMode"])
        assertEquals(EngineModeSelector.resolved, a["runtimeResolved"])
        assertEquals(EngineModeSelector.lastReason, a["runtimeReason"])
        assertEquals(EngineModeSelector.lastDistro, a["distro"])
        f.ready(NativeWorkspaceScope("storage-b", "boot-B"), url = "endpoint-B")
        val b = p.read(); assertEquals(false, b["runtimeRestartPending"])
        assertNull(p.pendingRuntimeTarget); assertTrue("pending endpoint cleared", p.pendingRuntimeUrl.isEmpty())
    }
    @Test fun absentSupervisorPreservesUninitializedWireState() {
        val info = NativeEngineInfoProjection(null).read()
        assertEquals("uninitialized", info["state"]); assertEquals(false, info["available"])
        assertTrue("uninitialized has no endpoint", info["webUrl"] == "")
        assertEquals("", info["workspaceDomain"]); assertEquals("", info["workspaceGeneration"])
    }
}

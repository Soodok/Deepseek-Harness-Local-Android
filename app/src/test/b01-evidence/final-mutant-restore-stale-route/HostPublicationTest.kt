package app.dsh.mobile.coldentry

import app.dsh.mobile.engine.EngineSupervisor.State
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HostPublicationTest {
    @Test fun actualReadyStatesCarryOpaqueSpawnIdentityAndOwnUrlCapability() {
        val a=State.Healthy(3080,"same-url",bootId="opaque-spawn-A")
        val b=State.Healthy(3080,"same-url",bootId="opaque-spawn-B")
        assertNotEquals(a.coldEntrySnapshot().epoch,b.coldEntrySnapshot().epoch)
        assertEquals("opaque-spawn-A",a.copy(recoveredPlugin="plugin").coldEntrySnapshot().epoch)
        assertTrue(a.coldEntrySnapshot().hasLoadableUrl)
        assertFalse(a.copy(tokenUrl=" ").coldEntrySnapshot().hasLoadableUrl)
        val safe=State.SafeMode(3080,"same-url",bootId="opaque-safe-spawn")
        assertEquals(HostPhase.SafeMode,safe.copy(degraded=null).coldEntrySnapshot().phase)
        assertEquals("opaque-safe-spawn",safe.copy().coldEntrySnapshot().epoch)
        assertFalse(safe.copy(tokenUrl=null).coldEntrySnapshot().hasLoadableUrl)
    }
    @Test fun missingIdentityOrNonReadyStateNeverBecomesAdmission() {
        val noId=State.Healthy(3080,"url").coldEntrySnapshot()
        assertNull(noId.epoch)
        for(state in listOf(State.Idle,State.Installing,State.Starting,State.Backoff(1,1),State.Failed("x"),State.Stopped)) {
            assertNull(state.coldEntrySnapshot().epoch); assertFalse(state.coldEntrySnapshot().hasLoadableUrl)
        }
        val f=Fixture(); f.ports.host=noId
        assertEquals(EntryDecision.NotReady,f.click()); assertTrue(f.ports.events.isEmpty())
    }
    @Test fun stopDoesNotOverwriteANewerSpawnAfterBlockingProcessCleanup() {
        val src=File("src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt").readText()
        for(anchor in listOf("fun stop(graceMs:","fun stopForMaintenance(")) {
            val body=src.substringAfter(anchor).substringBefore("fun ")
            assertEquals("Publish invalidation once, before blocking cleanup; never erase a newer Host",1,body.split("_state.value = State.Stopped").size-1)
        }
    }
    @Test fun supervisorBindsPublicationToActualSpawnAndInvalidatesBeforeStop() {
        val src=File("src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt").readText()
        val spawn=src.substringAfter("val proc = withContext(Dispatchers.IO) { spawnEngine(bootId) }")
        assertTrue(spawn.contains("entryBootId == bootId"))
        assertTrue(spawn.contains("State.SafeMode(EngineConfig.DEFAULT_PORT, tokenUrl, notice, degradedNow, bootId)"))
        assertTrue(spawn.contains("State.Healthy(EngineConfig.DEFAULT_PORT, tokenUrl, notice, degradedNow, bootId)"))
        for(anchor in listOf("fun stop(graceMs:","fun stopForMaintenance(")) {
            val stop=src.substringAfter(anchor).substringBefore("fun ")
            assertTrue(stop.indexOf("entryBootId = null") in 0 until stop.indexOf("proc?.stop(").takeIf { it>=0 }.let { it?:stop.indexOf("proc.stop(") })
        }
    }
}

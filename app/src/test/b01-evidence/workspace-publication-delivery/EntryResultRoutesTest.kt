package app.dsh.mobile.coldentry

import org.junit.Assert.*
import org.junit.Test

/** Android transports resultCode/requestCode even with a null Intent; never invent an echoed ID. */
class EntryResultRoutesTest {
    private fun delivered(registry: EntryDeliveries, id: Long, removed: MutableList<Long>): NativeEntry {
        val lease = ExclusiveBackdrop("owned-$"+id) { removed += id }
        lease.writerFinished(true); assertTrue(registry.claim(id, lease))
        return NativeEntry(id, "selected-now", lease.path)
    }
    @Test fun cancelledWithoutIntentCompletesOnlyLaunchedChild() {
        val removed = mutableListOf<Long>(); val deliveries = EntryDeliveries(); val routes = EntryResultRoutes(deliveries)
        val code = routes.launched(delivered(deliveries, 1, removed))
        assertFalse(routes.complete(1081)); assertTrue(removed.isEmpty())
        assertTrue(routes.complete(code)); assertEquals(listOf(1L), removed)
        assertFalse(routes.complete(code)); assertEquals(listOf(1L), removed)
    }
    @Test fun duplicateLateResultCannotDeleteFreshDeliveryOrPreparation() {
        val removed = mutableListOf<Long>(); val d = EntryDeliveries(); val r = EntryResultRoutes(d)
        val old = r.launched(delivered(d, 1, removed)); r.complete(old)
        val current = r.launched(delivered(d, 2, removed)); assertNotEquals(old, current)
        assertFalse(r.complete(old)); assertEquals(listOf(1L), removed); assertTrue(d.occupied)
        assertTrue(r.complete(current)); assertEquals(listOf(1L, 2L), removed)
        val preparing = ExclusiveBackdrop("preparing") { removed += 3L }
        preparing.writerFinished(true)
        assertFalse(r.complete(current)); assertTrue(preparing.ready)
    }
    @Test fun mainRecreationDoesNotResetInFlightOwnershipOrRequestCode() {
        val removed=mutableListOf<Long>(); val d=EntryDeliveries(); val r=EntryResultRoutes(d)
        r.restore(0, null)
        val code=r.launched(delivered(d, 7, removed)); val savedNext=r.nextCode; val savedChild=r.launchedCode
        // Process owner is retained; a stale Activity Bundle may not replace its live route.
        r.restore(savedNext, savedChild); assertTrue(removed.isEmpty())
        assertTrue(r.complete(code)); assertEquals(listOf(7L), removed)
        r.restore(savedNext, savedChild); assertFalse(r.complete(code))
    }
    @Test fun processDeathRestoresRoutingButNeverReconstructsDeletionFromAPathOrAttempt() {
        val old=EntryResultRoutes(EntryDeliveries()); old.restore(0,null)
        val removed=mutableListOf<Long>(); val d=EntryDeliveries(); val r=EntryResultRoutes(d)
        val code=old.launched(delivered(EntryDeliveries(), 9, removed))
        r.restore(old.nextCode,code); assertTrue(r.complete(code)); assertTrue(removed.isEmpty())
        val fresh=r.launched(delivered(d,1,removed)); assertTrue(fresh>code)
        assertFalse(r.complete(code)); assertTrue(removed.isEmpty()); r.complete(fresh)
        assertEquals(listOf(1L),removed)
    }
    @Test fun synchronousStartFailureRetiresCodeWithoutTouchingUnrelatedLease() {
        val removed=mutableListOf<Long>(); val d=EntryDeliveries(); val r=EntryResultRoutes(d)
        val entry=delivered(d,1,removed); val code=r.launched(entry)
        r.abandon(code); d.complete(entry.attemptId)
        val next=r.launched(delivered(d,2,removed)); assertTrue(next>code)
        assertFalse(r.complete(code)); assertEquals(listOf(1L),removed)
    }
    @Test fun exhaustedRequestCodeSpaceFailsClosedAndNeverWraps() {
        val d=EntryDeliveries(); val r=EntryResultRoutes(d); r.restore(65536,null)
        try { r.launched(NativeEntry(1,null,null)); fail("must not reuse an Android route") }
        catch (_: IllegalStateException) { }
    }
}

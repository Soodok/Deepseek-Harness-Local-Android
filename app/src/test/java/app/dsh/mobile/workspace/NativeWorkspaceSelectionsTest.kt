package app.dsh.mobile.workspace

import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceSelectionsTest {
    private fun choice() = NativeWorkspaceSelections.Choice(
        "domain", "g1", "/storage/emulated/0/Documents/project", "shared", "content://tree/project", true)

    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected obsolete or mismatched selection to be rejected") }
        catch (_: IllegalStateException) { }
    }

    @Test fun selectionAloneDoesNotWriteAndExplicitConfirmationWritesOnce() {
        val gate = NativeWorkspaceSelections()
        val selected = gate.select(gate.begin(), choice())
        var grants = 0
        var records = 0
        assertSame(selected, gate.matching("domain", "g1", selected.path, "shared", selected.uri))
        assertEquals(0, grants)
        gate.commit(selected, { true }, { guard -> guard(); grants++; guard(); records++ }, { fail("no rollback") })
        assertEquals(1, grants)
        assertEquals(1, records)
    }

    @Test fun pathUriKindAndScopeMustMatchTheActualSelection() {
        val gate = NativeWorkspaceSelections()
        val selected = gate.select(gate.begin(), choice())
        assertNull(gate.matching("domain", "g2", selected.path, "shared", selected.uri))
        assertNull(gate.matching("other", "g1", selected.path, "shared", selected.uri))
        assertNull(gate.matching("domain", "g1", selected.path + "/other", "shared", selected.uri))
        assertNull(gate.matching("domain", "g1", selected.path, "private", selected.uri))
        assertNull(gate.matching("domain", "g1", selected.path, "shared", "content://different"))
    }

    @Test fun reselectOrCancelInvalidatesEvenAnIdenticalOldChoice() {
        val gate = NativeWorkspaceSelections()
        val old = gate.select(gate.begin(), choice())
        val next = gate.begin()
        assertNull(gate.matching(old.domain, old.generation, old.path, old.kind, old.uri))
        rejected { gate.select(next - 1, choice()) }
        gate.select(next, choice())
        rejected { gate.commit(old, { true }, { fail("old choice wrote") }, { fail("nothing written") }) }
    }

    @Test fun generationChangeBeforeConfirmationNeverCallsTheWriter() {
        val gate = NativeWorkspaceSelections()
        val selected = gate.select(gate.begin(), choice())
        rejected { gate.commit(selected, { false }, { fail("stale generation wrote") }, { fail("nothing written") }) }
    }

    @Test fun closeRejectsOutstandingIoAndConfirmationWithoutWriting() {
        val gate = NativeWorkspaceSelections()
        val ticket = gate.begin()
        val selected = gate.select(ticket, choice())
        gate.close()
        rejected { gate.select(ticket, choice()) }
        rejected { gate.begin() }
        rejected { gate.commit(selected, { true }, { fail("destroyed activity wrote") }, { fail("nothing written") }) }
    }

    @Test fun generationChangeDuringGrantRollsBackBeforeWritingRecord() {
        val gate = NativeWorkspaceSelections()
        val selected = gate.select(gate.begin(), choice())
        var current = true
        var grants = 0
        var records = 0
        rejected {
            gate.commit(selected, { current }, { guard ->
                guard(); grants++; current = false; guard(); records++
            }, { grants = 0; records = 0 })
        }
        assertEquals(0, grants)
        assertEquals(0, records)
    }

    @Test fun generationChangeDuringRecordRollsBackBothEffects() {
        val gate = NativeWorkspaceSelections()
        val selected = gate.select(gate.begin(), choice())
        var current = true
        var grants = 0
        var records = 0
        rejected {
            gate.commit(selected, { current }, { guard ->
                guard(); grants++; guard(); records++; current = false
            }, { grants = 0; records = 0 })
        }
        assertEquals(0, grants)
        assertEquals(0, records)
    }

    @Test fun persistenceFailureNeverLeavesARecord() {
        val gate = NativeWorkspaceSelections()
        val selected = gate.select(gate.begin(), choice())
        var rolledBack = false
        rejected {
            gate.commit(selected, { true }, { guard -> guard(); error("grant rejected") }, { rolledBack = true })
        }
        assertTrue(rolledBack)
    }
}

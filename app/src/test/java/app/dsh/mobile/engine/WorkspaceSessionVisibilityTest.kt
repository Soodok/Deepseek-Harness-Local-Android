package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceSessionVisibilityTest {
    private val opening = "function sessionVisible(session,current,archived,archivedFilter){" +
        "if(session.origin===`subagent`)return false;"
    private val blankGuard = "if(session.blank&&session.id!==current)return false;"
    private val archiveSwitch = "switch(archivedFilter){case`default`:return!archived.has(session.id);" +
        "case`show`:return true;case`only`:return archived.has(session.id);}}"

    @Test fun exposesBlankSessionsAndKeepsTheOtherFilters() {
        val original = "prefix;" + opening + blankGuard + archiveSwitch + "suffix;"
        val expected = "prefix;" + opening + archiveSwitch + "suffix;"
        val patched = WorkspaceSessionVisibility.patch(original)
        assertEquals(expected, patched)
        assertEquals(patched, WorkspaceSessionVisibility.patch(patched))
    }

    @Test fun leavesUnknownAndAmbiguousBundlesIntact() {
        val expectedGuard = opening + blankGuard + archiveSwitch
        val variants = listOf(
            "unrelated(){$blankGuard}",
            expectedGuard.replace("switch(archivedFilter)", "switch(storageFilter)"),
            expectedGuard + expectedGuard,
        )
        variants.forEach { assertEquals(it, WorkspaceSessionVisibility.patch(it)) }
    }
}

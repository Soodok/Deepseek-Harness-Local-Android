package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class WorkspaceManagerTest {

    @Test
    fun acceptsNamesThatCanBeSafelyStoredAsWorkspaceDirectories() {
        assertEquals("Android project 1", WorkspaceManager.validateName("  Android project 1  "))
    }

    @Test
    fun rejectsPathTraversalAndAmbiguousWorkspaceNames() {
        listOf("../outside", ".", "..", "name/", "workspace\\name", "trailing.", "").forEach { name ->
            assertThrows(IOException::class.java) { WorkspaceManager.validateName(name) }
        }
    }
}

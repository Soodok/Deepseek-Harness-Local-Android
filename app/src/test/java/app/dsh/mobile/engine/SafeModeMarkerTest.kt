package app.dsh.mobile.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeModeMarkerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun safeModePersistsUntilExplicitlyExited() {
        val profiles = temporaryFolder.newFolder("profiles")
        val marker = SafeModeMarker(profiles)

        marker.mark("repeatable startup failure")

        assertTrue(marker.isActive())
        assertTrue(SafeModeMarker(profiles).isActive())
        marker.clear()
        assertFalse(marker.isActive())
    }
}

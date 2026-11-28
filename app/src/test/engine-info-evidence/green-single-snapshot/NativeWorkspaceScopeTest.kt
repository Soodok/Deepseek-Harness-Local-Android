package app.dsh.mobile.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeWorkspaceScopeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sameLaunchInputsKeepDomainAcrossDistinctGenerations() {
        val home = temp.newFolder("home")
        val first = NativeWorkspaceScope.domain("android-posix", home)!!
        val reboot = NativeWorkspaceScope.domain("android-posix", home)!!
        assertEquals(first, reboot)
        val before = NativeWorkspaceScope(first, "boot-one")
        val after = NativeWorkspaceScope(reboot, "boot-two")
        assertEquals(before.domain, after.domain)
        assertFalse(after.matches(first, "boot-one"))
        assertTrue(after.matches(first, "boot-two"))
        assertFalse(after.matches(null, "boot-two"))
    }

    @Test fun changedStorageDomainOrNamespaceCannotReuseThePreferenceKey() {
        val homeA = temp.newFolder("home-a")
        val homeB = temp.newFolder("home-b")
        val native = NativeWorkspaceScope.domain("android-posix", homeA)!!
        assertNotEquals(native, NativeWorkspaceScope.domain("android-posix", homeB))
        assertNotEquals(native, NativeWorkspaceScope.domain("linux-posix:rootfs-a", homeA))
        assertNotEquals(
            NativeWorkspaceScope.domain("linux-posix:rootfs-a", homeA),
            NativeWorkspaceScope.domain("linux-posix:rootfs-b", homeA),
        )
    }

    @Test fun actualGuestBindingsAreOrderIndependentButPathSensitive() {
        val home = temp.newFolder("home")
        val alpha = temp.newFolder("alpha")
        val bravo = temp.newFolder("bravo")
        val binds = listOf("/dsh" to home, "/ws" to alpha)
        val first = NativeWorkspaceScope.domain("linux-posix:rootfs", home, binds)
        assertEquals(first, NativeWorkspaceScope.domain("linux-posix:rootfs", home, binds.reversed()))
        assertNotEquals(first, NativeWorkspaceScope.domain("linux-posix:rootfs", home, listOf("/dsh" to home, "/ws" to bravo)))
    }

    @Test fun unknownLaunchIdentityIsRejectedRatherThanInvented() {
        val home = temp.newFolder("home")
        assertNull(NativeWorkspaceScope.domain("", home))
        assertNull(NativeWorkspaceScope.domain("android-posix", File("relative-home")))
        assertNull(NativeWorkspaceScope.domain("linux-posix:rootfs", home, listOf("relative-guest" to home)))
    }
}

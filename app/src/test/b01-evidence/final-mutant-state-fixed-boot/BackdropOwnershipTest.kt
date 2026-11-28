package app.dsh.mobile.coldentry

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BackdropOwnershipTest {
    @Test fun pixelCopyContinuationRevalidatesForegroundHostAndAttemptBeforeWriter() {
        for (invalid in listOf("pause", "destroy", "boot", "phase", "url", "deadline")) {
            val f=Fixture(); f.click(); val lease=f.ports.allocated.single()
            assertTrue(f.sut.permitsCapture(lease))
            when(invalid) {
                "pause" -> { f.sut.onPause(); f.sut.onResume() }
                "destroy" -> f.sut.onDestroy()
                "boot" -> f.ports.host=HostSnapshot("changed",HostPhase.Healthy,true)
                "phase" -> f.ports.host=HostSnapshot("boot-7",HostPhase.Starting,true)
                "url" -> f.ports.host=HostSnapshot("boot-7",HostPhase.Healthy,false)
                "deadline" -> f.scheduler.clock=100
            }
            assertFalse(invalid,f.sut.permitsCapture(lease)); assertTrue(lease.abandoned)
        }
    }
    @Test fun latePixelCopyCannotCancelNewCaptureAndStageCannotBeRepeated() {
        val f=Fixture(); f.click(); val old=f.ports.allocated.single()
        f.sut.onPause(); f.sut.onResume(); f.click(); val fresh=f.ports.allocated.last()
        assertFalse(f.sut.permitsCapture(old)); assertTrue(f.sut.permitsCapture(fresh))
        f.ports.capture(1); assertFalse(f.sut.permitsCapture(fresh))
        f.ports.session(); assertFalse(f.sut.permitsCapture(fresh))
        assertTrue(f.ports.files.containsKey(fresh.path))
    }
    @Test fun realExclusiveFilesAreNotUnlinkedUntilWriterHasClosed() {
        val root=File("src/test/b01-evidence/fs-"+java.util.UUID.randomUUID()).apply { mkdirs() }
        val a=BackdropFiles.reserve(root) { fail("unexpected deletion error") }
        val b=BackdropFiles.reserve(root) { fail("unexpected deletion error") }
        assertNotEquals(a.path,b.path)
        java.io.FileOutputStream(a.path).use { writer ->
            a.release(); assertTrue(File(a.path).exists()); writer.write(byteArrayOf(1,2,3))
        }
        a.writerFinished(true); assertFalse(File(a.path).exists()); assertTrue(File(b.path).exists())
        b.writerFinished(false); b.release(); assertFalse(File(b.path).exists())
        assertTrue(root.delete())
    }
    @Test fun failedExactUnlinkIsObservableNonthrowingAndRetriedWithoutDirectoryScan() {
        val root=File("src/test/b01-evidence/fs-retry-"+java.util.UUID.randomUUID()).apply { mkdirs() }
        val untouched=File(root,"unrelated").apply { writeText("keep") }
        var failures=0
        val lease=BackdropFiles.reserve(root) { failures++; throw IllegalStateException("observer must not escape cleanup") }
        lease.writerFinished(false)
        val file=File(lease.path)
        // An occupied directory at this minted path deterministically makes File.delete() return false,
        // without changing permissions/system settings or using any path supplied by the app user.
        assertTrue(file.delete()); assertTrue(file.mkdir())
        val blocker=File(file,"blocker").apply { writeText("test") }
        lease.release(); assertEquals(1,failures); assertTrue(file.exists())
        BackdropFiles.retryDeletes { failures++ }; assertEquals(2,failures)
        assertTrue(blocker.delete()); BackdropFiles.retryDeletes { fail("retry should unlink now") }
        assertFalse(file.exists()); assertEquals("keep",untouched.readText())
        assertTrue(untouched.delete()); assertTrue(root.delete())
    }
}

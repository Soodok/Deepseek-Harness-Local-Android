package app.dsh.mobile.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DeliveredFileCacheTest {
    @Test fun expiresOnlyOldOwnedCopiesAndKeepsRecentReaders() {
        val root = Files.createTempDirectory("dsh-file-cache-test").toFile()
        try {
            val stale = DeliveredFileCache.stage(root, "stale.txt", byteArrayOf(1))
            val recent = DeliveredFileCache.stage(root, "recent.txt", byteArrayOf(2))
            val unrelated = File(root, "not-a-delivery").apply { mkdirs() }
            File(unrelated, "keep.txt").writeText("keep")
            val old = System.currentTimeMillis() - 25L * 60 * 60 * 1000
            assertTrue(stale.parentFile!!.setLastModified(old))
            assertTrue(unrelated.setLastModified(old))
            DeliveredFileCache.stage(root, "current.txt", byteArrayOf(3))
            assertFalse(stale.exists())
            assertTrue(recent.exists())
            assertEquals("keep", File(unrelated, "keep.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun preservesBinaryBytesAndUnicodeFilename() {
        val root = Files.createTempDirectory("dsh-file-cache-test").toFile()
        try {
            val bytes = byteArrayOf(0, 127, -128, -1)
            val first = DeliveredFileCache.stage(root, "报告 (1).zip", bytes)
            assertEquals("报告 (1).zip", first.name)
            assertArrayEquals(bytes, first.readBytes())
            assertTrue(first.canonicalPath.startsWith(root.canonicalPath + File.separator))
            val second = DeliveredFileCache.stage(root, "报告 (1).zip", byteArrayOf(1))
            assertNotEquals(first.parent, second.parent)
            assertArrayEquals(bytes, first.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsTraversalAndControlNamesBeforeWriting() {
        val root = Files.createTempDirectory("dsh-file-cache-test").toFile()
        try {
            for (name in listOf("../secret.txt", "a/b.txt", "a\\b.txt", "..", ".", "bad\u0000.txt", "")) {
                assertThrows(IllegalArgumentException::class.java) {
                    DeliveredFileCache.stage(root, name, byteArrayOf(1))
                }
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}

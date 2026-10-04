package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PresetManagerTest {

    @Test
    fun previewsDesktopPresetPackageWithoutLosingMetadata() {
        val packageFile = createPackage("my-agent")
        try {
            val preset = PresetManager.preview(packageFile)
            assertEquals("my-agent", preset.id)
            assertEquals("My agent", preset.name)
            assertEquals("A test preset", preset.description)
            assertTrue(preset.composition.startsWith("- id: example"))
        } finally {
            packageFile.delete()
        }
    }

    @Test
    fun rejectsArchiveTraversalAndUnsupportedFiles() {
        val traversal = createPackage("my-agent", "../escape")
        val unexpected = createPackage("my-agent", "preset/extra.txt")
        try {
            assertThrows(IOException::class.java) { PresetManager.preview(traversal) }
            assertThrows(IOException::class.java) { PresetManager.preview(unexpected) }
        } finally {
            traversal.delete()
            unexpected.delete()
        }
    }

    @Test
    fun insertsAndRemovesOnlyManagedPresetWithoutChangingOtherProfileContent() {
        val original = "base: true\n"
        val preset = testPreset("first")
        val firstBlock = PresetManager.profileBlock(preset)
        val installed = PresetManager.updateManagedSection(original, preset.id, firstBlock, remove = false)
        assertTrue(installed.startsWith(original.trimEnd()))
        assertTrue(installed.contains("id: preset-first"))
        assertThrows(IOException::class.java) {
            PresetManager.updateManagedSection(installed, preset.id, firstBlock, remove = false)
        }

        val remaining = PresetManager.updateManagedSection(installed, preset.id, firstBlock, remove = true)
        assertEquals(original, remaining)
    }

    @Test
    fun removesOnePresetBlockWhilePreservingOtherManagedPresets() {
        val presetA = testPreset("first")
        val presetB = testPreset("second")
        val first = PresetManager.updateManagedSection("", presetA.id, PresetManager.profileBlock(presetA), remove = false)
        val both = PresetManager.updateManagedSection(first, presetB.id, PresetManager.profileBlock(presetB), remove = false)
        val onlySecond = PresetManager.updateManagedSection(both, presetA.id, PresetManager.profileBlock(presetA), remove = true)
        assertTrue(onlySecond.contains("id: preset-second"))
        assertTrue(!onlySecond.contains("id: preset-first"))
    }

    private fun testPreset(id: String) = PresetManager.Preset(
        id = id,
        name = "Preset $id",
        description = "",
        sourceDshVersion = "test",
        composition = "- id: example\n  name: example\n",
        metadata = "name: example\n",
    )

    private fun createPackage(id: String, extraEntry: String? = null): File {
        val file = File.createTempFile("test-preset", ".dshpreset")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """{"format":"dsh-preset","version":1,"id":"$id","name":"My agent","description":"A test preset","sourceDshVersion":"0.1.0"}"""
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("preset/agent.cordis.yml"))
            zip.write("- id: example\n  name: example\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("preset/preset.yml"))
            zip.write("name: My agent\n".toByteArray())
            zip.closeEntry()
            extraEntry?.let {
                zip.putNextEntry(ZipEntry(it))
                zip.write("unexpected".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}

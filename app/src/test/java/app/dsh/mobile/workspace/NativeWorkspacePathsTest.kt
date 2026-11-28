package app.dsh.mobile.workspace

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeWorkspacePathsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun rejected(action: () -> Any?) {
        try { action(); fail("Expected unavailable directory to be rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun selectsOnlyRealExistingPrivateDirectories() {
        val root = temp.newFolder("private")
        val child = File(root, "project").apply { mkdir() }
        File(root, "readme.txt").writeText("keep")
        val paths = NativeWorkspacePaths(root)
        assertEquals(child.canonicalFile, paths.selectPrivate(child.path))
        assertEquals(listOf(child.canonicalFile), paths.listPrivate(root.path))
        assertEquals("keep", File(root, "readme.txt").readText())
        assertEquals(listOf("project", "readme.txt"), root.list()!!.sorted())
        rejected { paths.selectPrivate(File(root, "missing").path) }
        rejected { paths.selectPrivate(File(root, "readme.txt").path) }
    }

    @Test fun traversalPrefixSiblingAndOutsideSymlinkCannotEscapePrivateRoot() {
        val root = temp.newFolder("private")
        val outside = temp.newFolder("private-other")
        val paths = NativeWorkspacePaths(root)
        rejected { paths.selectPrivate(outside.path) }
        rejected { paths.selectPrivate(File(root, "../private-other").path) }
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        rejected { paths.selectPrivate(File(root, "escape").path) }
        assertTrue(paths.listPrivate(root.path).isEmpty())
        assertTrue(outside.list()!!.isEmpty())
    }

    @Test fun createsOneNamedChildWithoutOverwritingAnExistingDirectory() {
        val root = temp.newFolder("private")
        val paths = NativeWorkspacePaths(root)
        val created = paths.createPrivate(root.path, "项目 alpha")
        assertEquals(File(root, "项目 alpha").canonicalFile, created)
        File(created, "keep.txt").writeText("original")
        rejected { paths.createPrivate(root.path, "项目 alpha") }
        assertEquals("original", File(created, "keep.txt").readText())
    }

    @Test fun invalidChildNamesNeverCreateAnything() {
        val root = temp.newFolder("private")
        val paths = NativeWorkspacePaths(root)
        for (name in listOf("", " ", ".", "..", "../escape", "a/b", "a\\b", "a\u0000b", "a\nb", "a".repeat(256))) {
            rejected { paths.createPrivate(root.path, name) }
        }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun contentUriAndRelativeTextAreNeverAcceptedAsFilesystemPaths() {
        val paths = NativeWorkspacePaths(temp.newFolder("private"))
        for (path in listOf("content://provider/tree/primary%3Aproject", "relative/project", "")) {
            rejected { paths.selectPrivate(path) }
        }
    }

    @Test fun mapsOnlyExplicitPrimaryExternalStorageDocuments() {
        val storage = temp.newFolder("phone")
        val project = File(storage, "Documents/project").apply { mkdirs() }
        assertEquals(project.canonicalFile, NativeWorkspacePaths.sharedDirectory(
            "com.android.externalstorage.documents", "primary:Documents/project", storage,
        ))
        for ((authority, id) in listOf(
            "cloud.example.documents" to "primary:Documents/project",
            "com.android.externalstorage.documents" to "ABCD-1234:project",
            "com.android.externalstorage.documents" to "primary:../other",
            "com.android.externalstorage.documents" to "primary:Android/data/other.app",
            "com.android.externalstorage.documents" to "primary:Android/obb/other.app",
            "com.android.externalstorage.documents" to "primary:/Documents/project",
            "com.android.externalstorage.documents" to "primary:Documents/missing",
        )) rejected { NativeWorkspacePaths.sharedDirectory(authority, id, storage) }
    }

    @Test fun sharedSymlinkCannotResolveOutsideAuthorizedStorage() {
        val storage = temp.newFolder("phone")
        val outside = temp.newFolder("outside")
        Files.createSymbolicLink(File(storage, "escape").toPath(), outside.toPath())
        rejected { NativeWorkspacePaths.sharedDirectory(
            "com.android.externalstorage.documents", "primary:escape", storage,
        ) }
        assertTrue(outside.list()!!.isEmpty())
    }
    @Test fun registeredPrivateWorkspacesAreReprobedEvenWithoutAPreferenceRecord() {
        val root = temp.newFolder("private")
        val child = File(root, "project").apply { mkdir() }
        val paths = NativeWorkspacePaths(root)
        assertTrue(paths.validateRegistered(child.path, false, null) { _, _ -> fail("private is not shared") })
        assertTrue(child.delete())
        rejected { paths.validateRegistered(child.path, false, null) { _, _ -> fail("private is not shared") } }
        rejected { paths.validateRegistered(root.path, true, "") { _, _ -> fail("invalid record must fail closed") } }
    }

    @Test fun rememberedSharedPathsRequireTheRecordedUriAndNeverUseLegacyFallback() {
        val paths = NativeWorkspacePaths(temp.newFolder("private"))
        val shared = temp.newFolder("shared").path
        var validated = false
        assertTrue(paths.validateRegistered(shared, true, "content://tree/selected") { path, uri ->
            assertEquals(shared, path)
            assertEquals("content://tree/selected", uri)
            validated = true
        })
        assertTrue(validated)
        rejected { paths.validateRegistered(shared, true, null) { _, _ -> fail("missing record") } }
        rejected { paths.validateRegistered(shared, true, "content://revoked") { _, _ -> error("grant revoked") } }
        assertTrue(paths.validateRegistered(shared, false, null) { _, _ -> fail("legacy Host path must be unaffected") })
    }

    @Test fun registeredPrivateSymlinkDoesNotFallThroughAsALegacyPath() {
        val root = temp.newFolder("private")
        val outside = temp.newFolder("outside")
        val link = File(root, "escape")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val paths = NativeWorkspacePaths(root)
        rejected { paths.validateRegistered(link.path, false, null) { _, _ -> fail("not shared") } }
    }

    @Test fun registeredMissingPrivateRootDoesNotFallThroughAsALegacyPath() {
        val root = temp.newFolder("private")
        val paths = NativeWorkspacePaths(root)
        assertTrue(root.delete())
        rejected { paths.validateRegistered(File(root, "gone").path, false, null) { _, _ -> fail("not shared") } }
    }
}


package app.dsh.mobile.engine

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object WorkspaceManager {

    const val DEFAULT_ID = "."
    private const val ACTIVE_MARKER = ".dsh-active-workspace"
    private val namePattern = Regex("[A-Za-z0-9][A-Za-z0-9 _.-]{0,59}")

    data class Workspace(
        val id: String,
        val name: String,
        val directory: File,
        val active: Boolean,
    )

    fun active(context: Context): File {
        val root = EngineConfig.workspaces(context)
        val id = runCatching { File(root, ACTIVE_MARKER).readText().trim() }.getOrNull()
        if (id.isNullOrEmpty() || id == DEFAULT_ID) return root
        val selected = childWorkspace(root, id)
        if (selected != null && selected.isDirectory) return selected
        File(root, ACTIVE_MARKER).delete()
        return root
    }

    fun list(context: Context): List<Workspace> {
        val root = EngineConfig.workspaces(context)
        val active = active(context).canonicalFile
        return buildList {
            add(Workspace(DEFAULT_ID, "Default", root, active == root.canonicalFile))
            root.listFiles()
                ?.filter { it.isDirectory && it.name != ACTIVE_MARKER && !Files.isSymbolicLink(it.toPath()) }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { directory ->
                    add(Workspace(directory.name, directory.name, directory, active == directory.canonicalFile))
                }
        }
    }

    fun create(context: Context, rawName: String): Workspace {
        val name = validateName(rawName)
        val root = EngineConfig.workspaces(context)
        if (root.listFiles().orEmpty().any { it.name.equals(name, ignoreCase = true) }) {
            throw IOException("A workspace named '$name' already exists")
        }
        val directory = File(root, name)
        if (!directory.mkdir()) throw IOException("Unable to create workspace '$name'")
        return Workspace(name, name, directory, false)
    }

    fun rename(context: Context, id: String, rawName: String): Workspace {
        if (id == DEFAULT_ID) throw IOException("The default workspace cannot be renamed")
        val name = validateName(rawName)
        val root = EngineConfig.workspaces(context)
        val source = childWorkspace(root, id) ?: throw IOException("Workspace no longer exists")
        if (root.listFiles().orEmpty().any { it.name != id && it.name.equals(name, ignoreCase = true) }) {
            throw IOException("A workspace named '$name' already exists")
        }
        val wasActive = active(context).canonicalFile == source.canonicalFile
        val target = File(root, name)
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
        if (wasActive) writeActive(root, name)
        return Workspace(name, name, target, wasActive)
    }

    fun delete(context: Context, id: String) {
        if (id == DEFAULT_ID) throw IOException("The default workspace cannot be deleted")
        val root = EngineConfig.workspaces(context)
        val directory = childWorkspace(root, id) ?: throw IOException("Workspace no longer exists")
        val wasActive = active(context).canonicalFile == directory.canonicalFile
        if (!directory.deleteRecursively()) throw IOException("Unable to delete workspace '$id'")
        if (wasActive) writeActive(root, DEFAULT_ID)
    }

    fun select(context: Context, id: String) {
        val root = EngineConfig.workspaces(context)
        if (id == DEFAULT_ID) {
            File(root, ACTIVE_MARKER).delete()
            return
        }
        val directory = childWorkspace(root, id)
            ?: throw IOException("Workspace no longer exists")
        if (!directory.isDirectory) throw IOException("Workspace is not a directory")
        writeActive(root, id)
    }

    internal fun validateName(rawName: String): String {
        val name = rawName.trim()
        if (!namePattern.matches(name) || name == "." || name == ".." ||
            name.endsWith('.') || name.endsWith(' ')
        ) {
            throw IOException("Use 1–60 letters, numbers, spaces, dots, dashes or underscores; do not end with a dot")
        }
        return name
    }

    private fun childWorkspace(root: File, id: String): File? {
        if (!namePattern.matches(id) || id == "." || id == "..") return null
        val child = File(root, id)
        if (Files.isSymbolicLink(child.toPath())) return null
        if (child.canonicalFile.parentFile != root.canonicalFile) return null
        return child
    }

    private fun writeActive(root: File, id: String) {
        val marker = File(root, ACTIVE_MARKER)
        if (id == DEFAULT_ID) {
            if (marker.exists() && !marker.delete()) throw IOException("Unable to reset active workspace")
        } else {
            val temporary = File(root, "$ACTIVE_MARKER.tmp")
            try {
                temporary.writeText(id)
                try {
                    Files.move(
                        temporary.toPath(),
                        marker.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
        }
    }
}

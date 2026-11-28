package app.dsh.mobile.workspace

import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Directory selection never grants additional OS or engine permissions. */
class NativeWorkspacePaths(private val privateRoot: File) {
    fun listPrivate(path: String = privateRoot.path): List<File> {
        val dir = privateDirectory(path, probe = false)
        val files = dir.listFiles() ?: error("应用私有目录无法列出")
        return files.filter { candidate ->
            runCatching { privateDirectory(candidate.path, probe = false) }.isSuccess
        }.sortedBy { it.name.lowercase() }
    }

    fun selectPrivate(path: String): File = privateDirectory(path, probe = true)

    /** Only app-owned selections are revalidated here; legacy Host paths keep their existing contract. */
    fun validateRegistered(path: String, hasSharedRecord: Boolean, sharedUri: String?,
        validateShared: (String, String) -> Unit): Boolean {
        if (hasSharedRecord) {
            require(!sharedUri.isNullOrBlank()) { "系统目录授权记录已失效，请重新选择" }
            validateShared(path, sharedUri)
        } else if (isPrivateWorkspacePath(path)) {
            selectPrivate(path)
        }
        return true
    }

    private fun isPrivateWorkspacePath(path: String): Boolean {
        if (!File(path).isAbsolute) return false
        val raw = File(path).absoluteFile.toPath().normalize().toFile()
        val base = privateRoot.absoluteFile.toPath().normalize().toFile()
        return inside(base, raw) || inside(privateRoot.canonicalFile, raw.canonicalFile)
    }

    fun createPrivate(parentPath: String, name: String): File {
        require(name.isNotBlank() && name.trim() == name && name.length <= 128 &&
            name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) {
            "请输入有效的文件夹名称，不能包含路径分隔符"
        }
        val parent = privateDirectory(parentPath, probe = true)
        val target = File(parent, name)
        require(!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "同名文件夹已存在" }
        check(target.mkdir()) { "无法新建应用私有文件夹" }
        return privateDirectory(target.path, probe = true)
    }

    private fun privateDirectory(path: String, probe: Boolean): File {
        require(path.isNotBlank() && File(path).isAbsolute && path.none { it.code < 32 || it.code == 127 }) {
            "目录必须是可访问的绝对文件路径"
        }
        check(!Files.isSymbolicLink(privateRoot.toPath())) { "应用私有工作区根目录不能是符号链接" }
        val base = privateRoot.canonicalFile
        val raw = File(path).absoluteFile.toPath().normalize().toFile()
        require(inside(base, raw) && !symlinkBelow(base, raw)) { "目录不在应用私有工作区内" }
        val dir = raw.canonicalFile
        require(inside(base, dir) && dir.isDirectory) { "所选应用私有目录不存在或不可达" }
        check(dir.list() != null && (!probe || isDirUsable(dir))) { "所选应用私有目录无法读写" }
        return dir
    }

    companion object {
        /** DocumentsContract tree IDs are decoded by Android before this method. */
        fun sharedDirectory(authority: String, documentId: String, storageRoot: File): File {
            require(authority == "com.android.externalstorage.documents") {
                "此文件提供方只有 URI，当前引擎不能直接访问；请选择手机内部共享存储"
            }
            require(documentId.startsWith("primary:")) { "当前引擎不能确认此存储卷的真实文件路径" }
            val relative = documentId.removePrefix("primary:")
            val parts = relative.split('/')
            require(relative.isNotEmpty() && parts.none { it.isEmpty() || it == "." || it == ".." ||
                it.any { c -> c == '\\' || c.code < 32 || c.code == 127 } }) { "共享目录标识无效" }
            require(parts.first().lowercase() != "android") { "不能使用其他应用的 Android 沙箱目录" }
            val base = storageRoot.canonicalFile
            val raw = File(base, relative)
            require(inside(base, raw) && !symlinkBelow(base, raw)) { "共享目录越过存储边界" }
            val dir = raw.canonicalFile
            require(inside(base, dir) && dir.isDirectory) { "所选共享目录不存在或不可达" }
            check(isDirUsable(dir)) { "系统已授权此 URI，但引擎无法直接读写该目录" }
            return dir
        }

        fun isDirUsable(dir: File): Boolean = runCatching {
            check(dir.isDirectory && dir.list() != null)
            val probe = File(dir, ".dsh-workspace-probe-" + UUID.randomUUID())
            try {
                java.io.FileOutputStream(probe).use { out -> out.write(byteArrayOf(68, 83, 72)); out.fd.sync() }
                check(probe.readBytes().contentEquals(byteArrayOf(68, 83, 72)))
                check(dir.list()?.contains(probe.name) == true)
            } finally {
                check(!probe.exists() || probe.delete()) { "工作区探针清理失败" }
            }
            true
        }.getOrDefault(false)

        private fun inside(base: File, child: File): Boolean =
            child.path == base.path || child.path.startsWith(base.path + File.separator)

        private fun symlinkBelow(base: File, child: File): Boolean {
            if (Files.isSymbolicLink(base.toPath())) return true
            var cursor = child
            while (cursor.path != base.path) {
                if (Files.isSymbolicLink(cursor.toPath())) return true
                cursor = cursor.parentFile ?: return true
            }
            return false
        }
    }
}

/** Activity-local selection identity; no grant or preference is written by choosing alone. */
internal class NativeWorkspaceSelections {
    data class Choice(val domain: String, val generation: String, val path: String,
        val kind: String, val uri: String? = null, val persistable: Boolean = false)

    private var ticket = 0L
    private var selected: Choice? = null
    private var closed = false

    @Synchronized fun begin(): Long {
        check(!closed) { "目录选择已关闭" }
        selected = null
        return ++ticket
    }

    @Synchronized fun select(request: Long, choice: Choice): Choice {
        check(!closed && request == ticket) { "目录选择已变化，请重新选择" }
        selected = choice
        return choice
    }

    @Synchronized fun matching(domain: String, generation: String, path: String,
        kind: String, uri: String?): Choice? = selected?.takeIf {
        !closed && it.domain == domain && it.generation == generation &&
            it.path == path && it.kind == kind && it.uri == uri
    }

    @Synchronized fun commit(choice: Choice, isCurrent: () -> Boolean,
        write: (() -> Unit) -> Unit, rollback: () -> Unit) {
        fun guard() {
            check(!closed && selected === choice && isCurrent()) { "本机引擎或目录选择已变化，请重新选择" }
        }
        guard()
        try {
            write(::guard)
            guard()
        } catch (failure: Throwable) {
            runCatching { rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    @Synchronized fun close() {
        closed = true
        selected = null
        ticket++
    }
}


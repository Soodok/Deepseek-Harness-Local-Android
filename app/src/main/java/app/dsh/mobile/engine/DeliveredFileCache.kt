package app.dsh.mobile.engine

import java.io.File
import java.util.UUID

/** One read-only share copy per action. Android may reclaim its cache later. */
internal object DeliveredFileCache {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val RETAIN_MS = 24L * 60 * 60 * 1000
    private val ownedName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Keep live receiver copies for a full day; ignore unknown directories. */
    private fun expireOldCopies(root: File) {
        val cutoff = System.currentTimeMillis() - RETAIN_MS
        val canonicalRoot = root.canonicalFile
        for (directory in root.listFiles().orEmpty()) {
            if (!ownedName.matches(directory.name) || !directory.isDirectory ||
                directory.lastModified() >= cutoff || directory.canonicalFile.parentFile != canonicalRoot) continue
            val entries = directory.listFiles() ?: continue
            // The cache owns flat copies only. Never descend into an unknown tree.
            if (entries.any { !it.isFile || it.canonicalFile.parentFile != directory.canonicalFile }) continue
            entries.forEach { it.delete() }
            directory.delete()
        }
    }

    fun stage(root: File, name: String, bytes: ByteArray): File {
        require(name.isNotBlank() && name != "." && name != "..")
        require(name.length <= 240 && name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 })
        require(bytes.size <= MAX_BYTES)
        expireOldCopies(root)
        val directory = File(root, UUID.randomUUID().toString())
        check(directory.mkdirs()) { "Cannot create file share cache" }
        val output = File(directory, name)
        try {
            output.outputStream().use { stream -> stream.write(bytes) }
            return output
        } catch (error: Exception) {
            output.delete()
            directory.delete()
            throw error
        }
    }
}

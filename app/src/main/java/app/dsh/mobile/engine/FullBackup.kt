package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import app.dsh.mobile.DshApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A plaintext, best-effort archive, not a consistent snapshot or an Android device image.
 * User files may contain secrets even when credentials are excluded. Keystore keys and
 * Android-managed state are not portable. SharedPreferences are never restored live.
 * Call on a background thread; the caller owns the output stream and discards it on failure.
 */
object FullBackup {
    data class Options(
        val includePlugins: Boolean = false,
        val includeCredentials: Boolean = false,
        val includeExtensions: Boolean = false,
        val includeLinux: Boolean = false,
        val replaceExisting: Boolean = false,
    )

    data class Result(val files: Int, val bytes: Long, val skipped: Int)

    private const val TAG = "FullBackup"
    private const val FORMAT = "dsh-full-backup"
    private const val VERSION = 2
    private const val MANIFEST = "manifest.json"
    private const val REGISTRY = "files/dsh-home/storages/workspace.json"
    private const val MAX_ENTRIES = 200_000
    private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    private const val MAX_DEPTH = 128

    private fun restoreSafetyReady(): Boolean = false


    private data class Source(val name: String, val file: File, val root: File, val directory: Boolean)
    private data class Staged(val name: String, val file: File, val size: Long)
    private data class Digest(val size: Long, val crc: Long, val sha256: String)

    /** Progress reports processed files and total files, not bytes. */
    fun export(
        ctx: Context,
        out: OutputStream,
        options: Options,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val base = ctx.filesDir.parentFile.canonicalFile
        val entries = mutableListOf<Source>()
        val names = mutableSetOf<String>()
        var skipped = 0
        var visited = 0

        fun scan(file: File, name: String, root: File, depth: Int) {
            if (++visited > MAX_ENTRIES || depth > MAX_DEPTH) throw IOException("backup tree limit exceeded: $name")
            if (!allowed(name, options)) { skipped++; return }
            val attrs = attributes(file)
            if (attrs.isSymbolicLink) { skipped++; return }
            ensureInside(root, file)
            if (!names.add(name)) throw IOException("duplicate backup path: $name")
            when {
                attrs.isDirectory -> {
                    entries += Source(name, file, root, true)
                    val children = file.listFiles() ?: throw IOException("cannot list $name")
                    for (child in children.sortedBy { it.name }) scan(child, "$name/${child.name}", root, depth + 1)
                }
                attrs.isRegularFile -> entries += Source(name, file, root, false)
                else -> skipped++
            }
        }

        val privateChildren = base.listFiles() ?: throw IOException("cannot list private data directory")
        for (child in privateChildren.sortedBy { it.name }) scan(child, child.name, base, 0)

        // Only the known application-managed external roots may be followed.
        val home = EngineConfig.dshHome(ctx)
        for (kind in listOf("sessions", "workspaces")) {
            val link = File(home, kind)
            if (Files.isSymbolicLink(link.toPath())) {
                val target = trustedExternalRoot(ctx, link, kind)
                    ?: throw IOException("untrusted or unavailable $kind directory")
                scan(target, "external/$kind", target, 0)
            }
        }
        val activeWorkspace = EngineConfig.publicFilesRoot(ctx)
        if (activeWorkspace != null && activeWorkspace.isDirectory &&
            activeWorkspace.list()?.isNotEmpty() == true) {
            scan(activeWorkspace.canonicalFile, "external/public-workspaces", activeWorkspace.canonicalFile, 0)
        }

        val preferences = BackupPreferences.snapshot(ctx, options.includeCredentials)
        val total = entries.count { !it.directory } + 1
        var done = 0
        var totalBytes = 0L
        val hashes = JSONArray()
        val zip = ZipOutputStream(out.buffered())
        zip.setLevel(Deflater.BEST_SPEED)
        onProgress(0, total)
        for (entry in entries) {
            val current = attributes(entry.file)
            if (current.isSymbolicLink || current.isDirectory != entry.directory ||
                (!entry.directory && !current.isRegularFile)) throw IOException("source changed: ${entry.name}")
            ensureNoLinks(entry.root, entry.file)
            zip.putNextEntry(ZipEntry(entry.name + if (entry.directory) "/" else ""))
            if (!entry.directory) {
                val digest = Files.newInputStream(entry.file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
                    copyBounded(it, zip, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - totalBytes)
                }
                totalBytes += digest.size
                hashes.put(JSONObject().put("path", entry.name).put("size", digest.size).put("sha256", digest.sha256))
                onProgress(++done, total)
            }
            zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("preferences.json"))
        val preferenceDigest = copyBounded(preferences.inputStream(), zip, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - totalBytes)
        zip.closeEntry()
        totalBytes += preferenceDigest.size
        hashes.put(JSONObject().put("path", "preferences.json").put("size", preferenceDigest.size).put("sha256", preferenceDigest.sha256))
        onProgress(++done, total)
        val exclusions = JSONArray(listOf(
            "nested symbolic links and unrecognized external roots", "non-regular files",
            "Android Keystore and system-managed state", "cache, code_cache, no_backup and lib", "raw shared_prefs",
        ))
        if (!options.includeCredentials) exclusions.put("profiles, databases, .ssh, credentials and mixed configuration")
        exclusions.put("engine runtime (except selected extensions)")
        if (!options.includeLinux) exclusions.put("Linux container")
        if (!options.includePlugins) exclusions.put("plugin and node_modules directories")
        if (!options.includeExtensions) exclusions.put("engine/extensions")
        val manifest = JSONObject().put("format", FORMAT).put("schemaVersion", VERSION)
            .put("plaintext", true).put("consistentSnapshot", false)
            .put("warning", "User content may contain secrets; Keystore and device-specific state are not portable")
            .put("excluded", exclusions).put("skipped", skipped).put("files", hashes)
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        if (manifestBytes.size > MAX_MANIFEST_BYTES) throw IOException("manifest too large")
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(manifestBytes)
        zip.closeEntry()
        zip.finish()
        zip.flush()
        return Result(done, totalBytes, skipped)
    }

    /**
     * Validates and stages the entire archive before publishing missing files. Existing registries
     * are explicitly skipped, not rewritten. Publication is per-file, not an atomic transaction.
     */
    fun import(
        ctx: Context,
        archive: File,
        options: Options,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val app = ctx.applicationContext as? DshApp ?: throw IOException("supervisor unavailable")
        if (!restoreSafetyReady()) throw IOException("import unavailable: safe restoration is still in development")
        val base = ctx.filesDir.parentFile.canonicalFile
        val stage = Files.createTempDirectory(ctx.cacheDir.toPath(), "full-backup-").toFile()
        val rollback = Files.createTempDirectory(ctx.cacheDir.toPath(), "full-backup-rollback-").toFile()
        var skipped = 0
        var totalBytes = 0L
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList()
                if (entries.size > MAX_ENTRIES) throw IOException("archive entry limit exceeded")
                val seen = mutableSetOf<String>()
                val files = mutableListOf<Pair<ZipEntry, String>>()
                var manifestEntry: ZipEntry? = null
                for (entry in entries) {
                    val name = validName(entry.name, entry.isDirectory)
                    if (!seen.add(name)) throw IOException("duplicate archive path: $name")
                    if (name == MANIFEST) {
                        if (entry.isDirectory) throw IOException("manifest is a directory")
                        manifestEntry = entry
                    } else files += entry to name
                }
                val fileNames = entries.filterNot { it.isDirectory }.map { validName(it.name, false) }.toSet()
                for (name in seen) {
                    var parent = name.substringBeforeLast('/', "")
                    while (parent.isNotEmpty()) {
                        if (parent in fileNames) throw IOException("file/directory collision: $name")
                        parent = parent.substringBeforeLast('/', "")
                    }
                }
                val manifestZip = manifestEntry ?: throw IOException("missing manifest")
                val manifestBuffer = ByteArrayOutputStream()
                val manifestDigest = zip.getInputStream(manifestZip).use {
                    copyBounded(it, manifestBuffer, MAX_MANIFEST_BYTES, MAX_MANIFEST_BYTES)
                }
                verifyZip(manifestZip, manifestDigest)
                val manifest = try { JSONObject(manifestBuffer.toString("UTF-8")) }
                    catch (e: Exception) { throw IOException("invalid manifest", e) }
                val version = manifest.optInt("schemaVersion", -1)
                val legacy = version == 1 && !manifest.has("format")
                if (!legacy && (version != VERSION || manifest.optString("format") != FORMAT)) {
                    throw IOException("unsupported backup format")
                }
                val expected = mutableMapOf<String, Pair<Long, String>>()
                if (!legacy) {
                    val array = manifest.optJSONArray("files") ?: throw IOException("missing checksums")
                    if (array.length() > MAX_ENTRIES) throw IOException("too many checksums")
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: throw IOException("invalid checksum record")
                        val name = validName(item.getString("path"), false)
                        val size = item.getLong("size")
                        val hash = item.getString("sha256")
                        if (size < 0 || size > MAX_ENTRY_BYTES || !hash.matches(Regex("[0-9a-f]{64}")) ||
                            expected.put(name, size to hash) != null) throw IOException("invalid checksum: $name")
                    }
                }
                val staged = mutableListOf<Staged>()
                var index = 0
                val total = files.count { !it.first.isDirectory }
                var done = 0
                onProgress(0, total)
                for ((entry, original) in files) {
                    val name = if (legacy) legacyName(original) else original
                    val isAllowed = name != null && allowedImport(name, options)
                    if (entry.isDirectory) {
                        if (!isAllowed) skipped++
                        continue
                    }
                    val tmp = File(stage, (++index).toString())
                    val digest = zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(tmp.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                            copyBounded(input, output, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - totalBytes)
                        }
                    }
                    totalBytes += digest.size
                    verifyZip(entry, digest)
                    if (!legacy) {
                        val want = expected.remove(original) ?: throw IOException("missing checksum: $original")
                        if (want.first != digest.size || want.second != digest.sha256) throw IOException("checksum mismatch: $original")
                    }
                    if (isAllowed) staged += Staged(name!!, tmp, digest.size) else skipped++
                    onProgress(++done, total)
                }
                if (expected.isNotEmpty()) throw IOException("manifest references missing files")

                var published = 0
                var publishedBytes = 0L
                val moved = mutableListOf<Pair<File, File>>()
                try {
                    for (item in staged) {
                        val target = File(base, item.name)
                        if (!prepareParents(base, target)) { skipped++; continue }
                        val existing = target.exists()
                        if (existing && !options.replaceExisting) { skipped++; continue }
                        if (existing && options.replaceExisting) {
                            val backup = File(rollback, item.name)
                            backup.parentFile?.mkdirs()
                            Files.move(target.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                            moved += target to backup
                        }
                        try {
                            Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS).use { output -> item.file.inputStream().use { it.copyTo(output) } }
                        } catch (e: Exception) {
                            if (target.exists()) Files.deleteIfExists(target.toPath())
                            throw e
                        }
                        published++
                        publishedBytes += item.size
                    }
                    return Result(published, publishedBytes, skipped)
                } catch (e: Exception) {
                    moved.asReversed().forEach { (target, backup) ->
                        runCatching { Files.deleteIfExists(target.toPath()) }
                        runCatching { Files.move(backup.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
                    }
                    throw e
                }
            }
        } finally {
            // This directory was freshly created by this operation and contains numeric files only.
            stage.listFiles()?.forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
            runCatching { Files.deleteIfExists(stage.toPath()) }
            rollback.walkBottomUp().forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
            app.supervisor.allowStartAfterMaintenance()
            app.supervisor.restart()
        }
    }

    private fun trustedExternalRoot(ctx: Context, link: File, kind: String): File? {
        val storage = android.os.Environment.getExternalStorageDirectory()
        val candidates = listOfNotNull(
            File(storage, "Documents/dshdata/$kind"),
            ctx.getExternalFilesDir(null)?.let { File(it, "dshdata/$kind") },
        )
        val target = runCatching { link.canonicalFile }.getOrNull() ?: return null
        return target.takeIf { it.isDirectory && candidates.any { candidate ->
            runCatching { candidate.canonicalFile == target }.getOrDefault(false)
        } }
    }

    private fun legacyName(name: String): String? = when {
        name == "sessions" || name.startsWith("sessions/") -> "files/dsh-home/$name"
        name == "workspaces" || name.startsWith("workspaces/") -> "files/dsh-home/$name"
        name == "storages/workspace.json" -> REGISTRY
        else -> null
    }

    private fun allowedImport(name: String, options: Options): Boolean {
        if (!allowed(name, options)) return false
        if (name == "shared_prefs" || name.startsWith("shared_prefs/") ||
            name == "databases" || name.startsWith("databases/")) return false
        return name == "files" || name.startsWith("files/")
    }

    private fun allowed(name: String, options: Options): Boolean {
        val parts = name.lowercase(Locale.ROOT).split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        if (parts.first() in setOf("cache", "code_cache", "no_backup", "lib", "shared_prefs")) return false
        if (parts.size >= 2 && parts[0] == "files" && parts[1] == "engine" &&
            (parts.size > 2 && parts[2] != "extensions" || !options.includeExtensions)) return false
        if (!options.includeLinux && parts.size >= 2 && parts[0] == "files" && parts[1] == "linux") return false
        if (!options.includeCredentials) {
            if (parts.first() in setOf("shared_prefs", "databases")) return false
            if (parts.any { it == "profiles" || it.startsWith("profiles.") || it == ".ssh" || it == "ssh" ||
                    it.contains("credential") || it.contains("secret") || it.contains("password") ||
                    it.contains("token") || it.contains("vault") || it == ".npmrc" || it == ".netrc" ||
                    it == ".env" || it.startsWith(".env.") || it.endsWith(".pem") || it.endsWith(".key") }) return false
            if (parts.size >= 3 && parts[0] == "files" && parts[1] == "dsh-home" &&
                (parts[2] == ".dsh" || (parts.size == 3 &&
                    (parts[2].endsWith(".json") || parts[2].endsWith(".yaml") || parts[2].endsWith(".yml"))))) return false
        }
        if (!options.includePlugins && parts.any { it == "plugins" || it == "node_modules" || it == ".plugin-manager" }) return false
        if (!options.includeExtensions && parts.size >= 3 && parts[0] == "files" &&
            parts[1] == "engine" && parts[2] == "extensions") return false
        return true
    }

    private fun validName(raw: String, directory: Boolean): String {
        val name = if (directory && raw.endsWith('/')) raw.dropLast(1) else raw
        if (name.isEmpty() || name.startsWith('/') || name.contains('\\') || name.contains('\u0000') ||
            name.length > 4096 || name.split('/').size > MAX_DEPTH ||
            name.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            Regex("^[A-Za-z]:").containsMatchIn(name)) throw IOException("unsafe archive path")
        return name
    }

    private fun attributes(file: File): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun inside(root: File, file: File): Boolean =
        file.canonicalPath == root.canonicalPath || file.canonicalPath.startsWith(root.canonicalPath + File.separator)

    private fun ensureInside(root: File, file: File) {
        if (!inside(root, file)) throw IOException("path escapes backup root")
    }

    private fun ensureNoLinks(root: File, file: File) {
        ensureInside(root, file)
        var current = file
        while (current != root) {
            if (attributes(current).isSymbolicLink) throw IOException("symbolic link in path")
            current = current.parentFile ?: throw IOException("invalid path")
        }
        if (attributes(root).isSymbolicLink) throw IOException("symbolic link root")
    }

    private fun prepareParents(root: File, target: File): Boolean {
        if (!inside(root, target)) return false
        val relative = root.toPath().relativize(target.parentFile.toPath())
        var dir = root
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return false
        for (part in relative) {
            dir = File(dir, part.toString())
            try { Files.createDirectory(dir.toPath()) } catch (_: java.nio.file.FileAlreadyExistsException) { }
            val attrs = attributes(dir)
            if (!attrs.isDirectory || attrs.isSymbolicLink) return false
        }
        return inside(root, target)
    }

    private fun copyBounded(input: InputStream, output: OutputStream, entryLimit: Long, remaining: Long): Digest {
        val hash = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            size += n
            if (size > entryLimit || size > remaining) throw IOException("archive size limit exceeded")
            hash.update(buffer, 0, n)
            crc.update(buffer, 0, n)
            output.write(buffer, 0, n)
        }
        return Digest(size, crc.value, hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    private fun verifyZip(entry: ZipEntry, digest: Digest) {
        if (entry.size < 0 || entry.crc < 0 || entry.size != digest.size || entry.crc != digest.crc) {
            throw IOException("ZIP size or CRC mismatch: ${entry.name}")
        }
    }
}

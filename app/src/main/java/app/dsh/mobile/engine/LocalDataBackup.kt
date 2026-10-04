package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object LocalDataBackup {

    private const val TAG = "LocalDataBackup"
    private const val MAGIC = "DSHBACK1"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 310_000
    private const val MAX_ENTRIES = 100_000
    private const val MAX_UNCOMPRESSED_BYTES = 2L shl 30
    private const val MAX_BACKUP_BYTES = 2L shl 30
    private val secureRandom = SecureRandom()

    fun export(context: Context, destination: File, passphrase: CharArray) {
        val staging = File(context.cacheDir, "dsh-backup-${System.nanoTime()}.tmp")
        try {
            require(passphrase.size >= 12) { "Backup passphrase must contain at least 12 characters." }
            val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
            val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
            val cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, iv)
            FileOutputStream(staging).use { fileOut ->
                fileOut.write(MAGIC.toByteArray(Charsets.US_ASCII) + salt + iv)
                val encrypted = CipherOutputStream(BufferedOutputStream(fileOut), cipher)
                ZipOutputStream(encrypted).use { zip ->
                    addTree(zip, "dsh-home", EngineConfig.dshHome(context))
                    addTree(zip, "workspaces", EngineConfig.workspaces(context))
                }
            }
            destination.parentFile?.let {
                check(it.isDirectory || it.mkdirs()) { "Cannot create backup destination" }
            }
            staging.copyTo(destination, overwrite = true)
            Log.i(TAG, "encrypted local backup exported")
        } finally {
            passphrase.fill('\u0000')
            staging.delete()
        }
    }

    fun restore(context: Context, source: File, passphrase: CharArray) {
        val stage = File(context.cacheDir, "dsh-restore-${System.nanoTime()}").apply {
            check(mkdirs()) { "Cannot create restore staging directory" }
        }
        val oldHome = EngineConfig.dshHome(context)
        val oldWorkspaces = EngineConfig.workspaces(context)
        val backupRoot = File(context.cacheDir, "dsh-restore-previous-${System.nanoTime()}")
        val stagedHome = File(stage, "dsh-home")
        val stagedWorkspaces = File(stage, "workspaces")
        try {
            require(source.isFile && source.length() <= MAX_BACKUP_BYTES) { "Backup file does not exist or exceeds the size limit." }
            require(passphrase.size >= 12) { "Backup passphrase must contain at least 12 characters." }
            authenticate(source, passphrase)
            ZipInputStream(BufferedInputStream(openDecrypted(source, passphrase))).use { zip ->
                var entries = 0
                var totalBytes = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) throw IOException("Backup contains too many entries")
                    val relative = validateEntry(entry.name)
                    val rootName = relative.substringBefore('/')
                    val base = if (rootName == "dsh-home") stagedHome else stagedWorkspaces
                    val childPath = relative.substringAfter('/', "")
                    val output = if (childPath.isEmpty()) base.canonicalFile else File(base, childPath).canonicalFile
                    if (output != base.canonicalFile && !output.toPath().startsWith(base.canonicalFile.toPath())) {
                        throw IOException("Backup entry escaped restore directory")
                    }
                    if (entry.isDirectory || childPath.isEmpty()) {
                        if (!output.isDirectory && !output.mkdirs()) throw IOException("Cannot create restore directory")
                    } else {
                        output.parentFile?.let {
                            if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create restore parent")
                        }
                        FileOutputStream(output).use { target ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                    throw IOException("Backup expands beyond the restore size limit")
                                }
                                target.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (!stagedHome.isDirectory || !stagedWorkspaces.isDirectory) {
                throw IOException("Backup is missing required user data")
            }
            check(backupRoot.mkdirs()) { "Cannot create rollback directory" }
            val previousHome = File(backupRoot, "dsh-home")
            val previousWorkspaces = File(backupRoot, "workspaces")
            var movedHome = false
            var movedWorkspaces = false
            var installedHome = false
            var installedWorkspaces = false
            try {
                if (oldHome.exists()) {
                    move(oldHome, previousHome)
                    movedHome = true
                }
                if (oldWorkspaces.exists()) {
                    move(oldWorkspaces, previousWorkspaces)
                    movedWorkspaces = true
                }
                move(stagedHome, oldHome)
                installedHome = true
                move(stagedWorkspaces, oldWorkspaces)
                installedWorkspaces = true
            } catch (e: Exception) {
                if (installedHome) oldHome.deleteRecursively()
                if (installedWorkspaces) oldWorkspaces.deleteRecursively()
                if (movedHome && previousHome.exists()) move(previousHome, oldHome)
                if (movedWorkspaces && previousWorkspaces.exists()) move(previousWorkspaces, oldWorkspaces)
                throw e
            }
            backupRoot.deleteRecursively()
            Log.i(TAG, "encrypted local backup restored")
        } catch (e: Exception) {
            throw if (e is IOException) e else IOException("Unable to restore encrypted backup: ${e.message}", e)
        } finally {
            passphrase.fill('\u0000')
            stage.deleteRecursively()
            if (backupRoot.exists() && oldHome.isDirectory && oldWorkspaces.isDirectory) {
                backupRoot.deleteRecursively()
            }
        }
    }

    private fun openDecrypted(source: File, passphrase: CharArray): CipherInputStream {
        val input = FileInputStream(source)
        try {
            val header = ByteArray(MAGIC.length + SALT_BYTES + IV_BYTES)
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) throw IOException("Truncated backup header")
                read += count
            }
            if (!header.copyOfRange(0, MAGIC.length).contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) {
                throw IOException("Unsupported or invalid backup file")
            }
            val salt = header.copyOfRange(MAGIC.length, MAGIC.length + SALT_BYTES)
            val iv = header.copyOfRange(MAGIC.length + SALT_BYTES, header.size)
            return CipherInputStream(input, cipher(Cipher.DECRYPT_MODE, passphrase, salt, iv))
        } catch (e: Exception) {
            input.close()
            throw e
        }
    }

    private fun authenticate(source: File, passphrase: CharArray) {
        try {
            openDecrypted(source, passphrase).use { input ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_UNCOMPRESSED_BYTES) {
                        throw IOException("Encrypted backup payload exceeds the size limit")
                    }
                }
            }
        } catch (e: Exception) {
            throw IOException("Incorrect passphrase or damaged backup", e)
        }
    }

    internal fun cipher(mode: Int, passphrase: CharArray, salt: ByteArray, iv: ByteArray): Cipher {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(MAGIC.toByteArray(Charsets.US_ASCII))
            key.fill(0)
        }
    }

    private fun addTree(zip: ZipOutputStream, rootName: String, source: File) {
        check(source.isDirectory || source.mkdirs()) { "Cannot create backup source directory: $rootName" }
        zip.putNextEntry(ZipEntry("$rootName/"))
        zip.closeEntry()
        val root = source.canonicalFile
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val children = directory.listFiles() ?: throw IOException("Cannot read backup directory")
            for (child in children) {
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                val relative = child.relativeTo(root).invariantSeparatorsPath
                val name = "$rootName/$relative"
                if (child.isDirectory) {
                    zip.putNextEntry(ZipEntry("$name/"))
                    zip.closeEntry()
                    pending.add(child)
                } else if (child.isFile) {
                    zip.putNextEntry(ZipEntry(name))
                    child.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    internal fun validateEntry(rawName: String): String {
        if (rawName.startsWith('/') || rawName.contains('\\') || rawName.contains('\u0000')) {
            throw IOException("Invalid backup entry path")
        }
        val name = rawName.trimEnd('/')
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) throw IOException("Invalid backup entry path")
        if (parts.firstOrNull() !in setOf("dsh-home", "workspaces")) throw IOException("Unexpected backup data")
        return name
    }

    private fun move(source: File, destination: File) {
        try {
            java.nio.file.Files.move(
                source.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(source.toPath(), destination.toPath())
        }
    }
}

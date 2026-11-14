package app.dsh.mobile.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Best-effort backup of credential-protected SharedPreferences. This is not encryption:
 * even the ordinary section can contain secrets in arbitrary keys or values. Keystore-bound
 * material is not portable. Call on a background thread, with other preference writers stopped.
 * Snapshot discovers only existing XML names, so in-memory-only preferences are not discovered.
 */
object BackupPreferences {
    private const val FORMAT = "dsh-preferences"
    private const val VERSION = 1
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_FILES = 1024
    private const val MAX_ENTRIES = 100_000
    private val fileName = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,199}")
    private val keyName = Regex("[^\\u0000-\\u001f]{1,512}")

    // New credential-like keys are skipped until reviewed. Authority is always skipped.
    private val credentialKeys = setOf(
        "dsh_ui/lan_web_password", "dsh_ui/pet_pack_index_url",
        "cloud_asr/api_key", "peersync/pw_plain",
    )
    private val deniedFiles = setOf(
        "capabilities", "dsh_priv", "dsh_priv_state", "perm_grant", "sensitive_audit",
        "remote_access", "linux_ssh",
    )
    private val authorityWords = listOf(
        "permission", "capability", "capabilities", "privilege", "priv_mode", "shizuku",
        "root", "superuser", "session", "access_token", "refresh_token", "bearer",
        "pair_code", "pairing", "authorized", "authorised", "authorization", "authorisation",
        "lan_web_access", "lan_web_pw_pending", "engine_linux_chroot", "ob_priv", "ob_cap_group",
    )
    private val sensitiveWords = listOf(
        "password", "passwd", "passphrase", "pwd", "pw_", "_pw", "secret", "credential",
        "api_key", "apikey", "api-key", "token", "private_key", "privatekey", "auth", "cookie",
    )

    private enum class Category { ORDINARY, SENSITIVE, DENIED }
    private data class Entry(val file: String, val key: String, val value: Any)
    data class ApplyReport(val count: Int, val rollback: RollbackSnapshot)
    data class RollbackReport(val restored: Int, val conflicts: Int, val failedFiles: List<String>)
    class RollbackSnapshot internal constructor(internal val changes: List<ChangeRecord>)
    data class ChangeRecord internal constructor(
        internal val file: String, internal val key: String, internal val before: Any?, internal val after: Any,
    )
    class ApplyException(message: String, cause: Throwable?, val rollback: RollbackReport) : IOException(message, cause)

    private fun category(file: String, key: String): Category {
        if (!fileName.matches(file) || !keyName.matches(key)) return Category.DENIED
        val f = file.lowercase(Locale.ROOT)
        val k = key.lowercase(Locale.ROOT)
        if (f in deniedFiles || authorityWords.any { it in f || it in k } ||
            k.startsWith("cap.") || k.startsWith("cap_") ||
            f.contains("remote_access") || f.contains("linux_ssh") || f.contains("permission")) return Category.DENIED
        val sensitive = sensitiveWords.any { it in f || it in k } || k == "pet_pack_index_url"
        if (sensitive) return if ("$f/$k" in credentialKeys) Category.SENSITIVE else Category.DENIED
        return Category.ORDINARY
    }

    /** Emits a versioned UTF-8 JSON document; includeCredentials opts into reviewed sensitive keys. */
    @Synchronized
    fun snapshot(ctx: Context, includeCredentials: Boolean = false): ByteArray {
        val app = ctx.applicationContext.createCredentialProtectedStorageContext()
        val dir = File(File(app.applicationInfo.dataDir).canonicalFile, "shared_prefs")
        if (dir.exists() && (dir.canonicalFile != dir.absoluteFile || !dir.isDirectory)) {
            throw IOException("invalid shared_prefs directory")
        }
        val files = if (dir.exists()) dir.listFiles() ?: throw IOException("cannot list shared_prefs") else emptyArray()
        if (files.size > MAX_FILES) throw IOException("too many preference files")
        val ordinary = JSONArray()
        val sensitive = JSONArray()
        var count = 0
        for (file in files.sortedBy { it.name }) {
            if (!file.name.endsWith(".xml") || !file.isFile || file.canonicalFile != file.absoluteFile) continue
            val name = file.name.removeSuffix(".xml")
            if (!fileName.matches(name)) continue
            val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            for ((key, value) in prefs.all.toSortedMap()) {
                val c = category(name, key)
                if (c == Category.DENIED || (c == Category.SENSITIVE && !includeCredentials)) continue
                if (++count > MAX_ENTRIES) throw IOException("too many preference entries")
                val record = encode(name, key, value ?: throw IOException("null preference value"))
                (if (c == Category.SENSITIVE) sensitive else ordinary).put(record)
            }
        }
        val doc = JSONObject().put("format", FORMAT).put("version", VERSION).put("ordinary", ordinary)
        if (includeCredentials) doc.put("sensitive", sensitive)
        val result = doc.toString().toByteArray(Charsets.UTF_8)
        if (result.size > MAX_BYTES) throw IOException("preference backup too large")
        return result
    }

    /** Applies missing values by default. replace=true replaces matching keys, never clears files. */
    @Synchronized
    fun apply(ctx: Context, bytes: ByteArray, replace: Boolean = false): Int =
        applyWithRollback(ctx, bytes, replace).count

    /** The returned in-memory journal can restore only values unchanged since this apply. */
    @Synchronized
    fun applyWithRollback(ctx: Context, bytes: ByteArray, replace: Boolean = false): ApplyReport {
        val entries = decode(bytes)
        val app = ctx.applicationContext.createCredentialProtectedStorageContext()
        val changes = mutableListOf<ChangeRecord>()
        try {
            for ((file, group) in entries.groupBy { it.file }) {
                val prefs = app.getSharedPreferences(file, Context.MODE_PRIVATE)
                val current = prefs.all
                val selected = group.filter { replace || !current.containsKey(it.key) }
                if (selected.isEmpty()) continue
                val editor = prefs.edit()
                val pending = mutableListOf<ChangeRecord>()
                for (entry in selected) {
                    val before = current[entry.key]?.let { copyValue(it) }
                    if (before != null) validateValue(before)
                    pending += ChangeRecord(file, entry.key, before, copyValue(entry.value))
                    put(editor, entry.key, entry.value)
                }
                // commit() can return false after updating the process cache, so journal first.
                changes.addAll(pending)
                if (!editor.commit()) throw IOException("preference commit failed: $file")
            }
        } catch (e: Exception) {
            val report = rollbackInternal(app, RollbackSnapshot(changes.toList()))
            throw ApplyException("preference restore failed; rollback conflicts=${report.conflicts}, failedFiles=${report.failedFiles}", e, report)
        }
        return ApplyReport(changes.size, RollbackSnapshot(changes.toList()))
    }

    @Synchronized
    fun rollback(ctx: Context, snapshot: RollbackSnapshot): RollbackReport =
        rollbackInternal(ctx.applicationContext.createCredentialProtectedStorageContext(), snapshot)

    private fun rollbackInternal(ctx: Context, snapshot: RollbackSnapshot): RollbackReport {
        var restored = 0
        var conflicts = 0
        val failed = mutableListOf<String>()
        for ((file, group) in snapshot.changes.asReversed().groupBy { it.file }) {
            try {
                val prefs = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
                val current = prefs.all
                val editor = prefs.edit()
                var pending = 0
                for (change in group) {
                    if (!current.containsKey(change.key) || !sameValue(current[change.key], change.after)) {
                        conflicts++
                        continue
                    }
                    if (change.before == null) editor.remove(change.key) else put(editor, change.key, change.before)
                    pending++
                }
                if (pending > 0) {
                    if (editor.commit()) restored += pending else failed += file
                }
            } catch (_: Exception) { failed += file }
        }
        return RollbackReport(restored, conflicts, failed)
    }

    private fun encode(file: String, key: String, value: Any): JSONObject {
        val (type, encoded) = when (value) {
            is Boolean -> "boolean" to value
            is Int -> "int" to value.toString()
            is Long -> "long" to value.toString()
            is Float -> "float" to java.lang.Float.floatToRawIntBits(value).toString()
            is String -> "string" to value
            is Set<*> -> {
                if (value.any { it !is String }) throw IOException("invalid string set")
                "string_set" to JSONArray(value.map { it as String }.sorted())
            }
            else -> throw IOException("unsupported preference type")
        }
        return JSONObject().put("file", file).put("key", key).put("type", type).put("value", encoded)
    }

    private fun decode(bytes: ByteArray): List<Entry> {
        if (bytes.size > MAX_BYTES) throw IOException("preference backup too large")
        try {
            val decoder = Charsets.UTF_8.newDecoder()
            val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val tokener = JSONTokener(text)
            val root = tokener.nextValue() as? JSONObject ?: throw IOException("invalid preference document")
            if (tokener.nextClean() != '\u0000' || root.optString("format") != FORMAT || root.optInt("version", -1) != VERSION) {
                throw IOException("unsupported preference document")
            }
            val result = mutableListOf<Entry>()
            val seen = mutableSetOf<Pair<String, String>>()
            val files = mutableSetOf<String>()
            var total = 0
            for ((section, expected) in listOf("ordinary" to Category.ORDINARY, "sensitive" to Category.SENSITIVE)) {
                if (section == "sensitive" && !root.has(section)) continue
                val array = root.get(section) as? JSONArray ?: throw IOException("invalid preference section")
                total += array.length()
                if (total > MAX_ENTRIES) throw IOException("too many preference entries")
                for (i in 0 until array.length()) {
                    val item = array.get(i) as? JSONObject ?: throw IOException("invalid preference entry")
                    val file = item.get("file") as? String ?: throw IOException("invalid preference file")
                    val key = item.get("key") as? String ?: throw IOException("invalid preference key")
                    if (files.add(file) && files.size > MAX_FILES) throw IOException("too many preference files")
                    if (!seen.add(file to key)) throw IOException("duplicate preference entry")
                    val type = item.get("type") as? String ?: throw IOException("invalid preference type")
                    val raw = item.get("value")
                    val value: Any = when (type) {
                        "boolean" -> raw as? Boolean ?: throw IOException("invalid boolean")
                        "int" -> (raw as? String)?.toIntOrNull() ?: throw IOException("invalid int")
                        "long" -> (raw as? String)?.toLongOrNull() ?: throw IOException("invalid long")
                        "float" -> java.lang.Float.intBitsToFloat((raw as? String)?.toIntOrNull() ?: throw IOException("invalid float"))
                        "string" -> raw as? String ?: throw IOException("invalid string")
                        "string_set" -> {
                            val arrayValue = raw as? JSONArray ?: throw IOException("invalid string set")
                            val set = linkedSetOf<String>()
                            for (j in 0 until arrayValue.length()) {
                                if (!set.add(arrayValue.get(j) as? String ?: throw IOException("invalid string set"))) throw IOException("duplicate set item")
                            }
                            set
                        }
                        else -> throw IOException("unsupported preference type")
                    }
                    if (category(file, key) == expected) result += Entry(file, key, value)
                }
            }
            return result
        } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("invalid preference document", e) }
    }

    private fun copyValue(value: Any): Any = if (value is Set<*>) value.toSet() else value

    private fun sameValue(left: Any?, right: Any): Boolean =
        if (left is Float && right is Float) {
            java.lang.Float.floatToRawIntBits(left) == java.lang.Float.floatToRawIntBits(right)
        } else left == right

    private fun validateValue(value: Any) {
        if (value !is Boolean && value !is Int && value !is Long && value !is Float &&
            value !is String && (value !is Set<*> || value.any { it !is String })) {
            throw IOException("unsupported existing preference type")
        }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> {
                if (value.any { it !is String }) throw IOException("invalid string set")
                editor.putStringSet(key, value.map { it as String }.toSet())
            }
            else -> throw IOException("unsupported preference type")
        }
    }
}

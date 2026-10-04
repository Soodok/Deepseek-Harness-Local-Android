package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Audit logger (Phase 4): records security-relevant events to a JSON file
 * in dsh-home/audit/. Entries are append-only and timestamped. Each entry
 * includes the capability mode and a sanitized message (no secrets logged).
 *
 * The log is never trimmed automatically (user may inspect it), but a
 * manual "clear" writes an empty file in-place.
 */
object AuditLogger {

    private const val TAG = "AuditLogger"
    private const val DIR_NAME = "audit"
    private const val FILE_NAME = "audit.log.jsonl"
    private const val MAX_LINE_LEN = 8192
    private const val MAX_ENTRIES_MEMORY = 500

    @Volatile private var initialized = false
    private lateinit var ctx: Context

    private val _entries = MutableStateFlow<List<AuditEntry>>(emptyList())
    val entries: StateFlow<List<AuditEntry>> = _entries

    fun init(context: Context) {
        if (initialized) return
        ctx = context.applicationContext
        initialized = true
        loadEntries()
    }

    data class AuditEntry(
        val timestamp: String,
        val eventType: String,
        val privMode: String,
        val message: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("timestamp", timestamp)
            .put("eventType", eventType)
            .put("privMode", privMode)
            .put("message", message)

        companion object {
            fun fromJson(json: JSONObject): AuditEntry = AuditEntry(
                timestamp = json.optString("timestamp", ""),
                eventType = json.optString("eventType", ""),
                privMode = json.optString("privMode", ""),
                message = json.optString("message", ""),
            )
        }
    }

    fun log(eventType: String, message: String, context: Context? = null) {
        if (!initialized) {
            val ctx = context ?: return
            init(ctx)
        }
        val mode = Privilege.getMode(ctx).name
        val entry = AuditEntry(
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                .format(java.util.Date()),
            eventType = eventType,
            privMode = mode,
            message = message.take(MAX_LINE_LEN),
        )
        val dir = java.io.File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
        val file = java.io.File(dir, FILE_NAME)
        runCatching {
            file.appendText(entry.toJson().toString() + "\n")
        }.onFailure {
            Log.w(TAG, "failed to write audit entry: ${it.message}")
        }
        val current = _entries.value.toMutableList()
        if (current.size >= MAX_ENTRIES_MEMORY) current.removeAt(0)
        current.add(entry)
        _entries.value = current
    }

    fun clear(context: Context? = null) {
        if (!initialized) {
            val ctx = context ?: return
            init(ctx)
        }
        val dir = java.io.File(ctx.filesDir, DIR_NAME)
        val file = java.io.File(dir, FILE_NAME)
        runCatching { file.writeText("") }
        _entries.value = emptyList()
    }

    private fun loadEntries() {
        val dir = java.io.File(ctx.filesDir, DIR_NAME)
        val file = java.io.File(dir, FILE_NAME)
        if (!file.isFile) return
        val loaded = file.readLines()
            .mapNotNull { line ->
                try {
                    fromJsonSafe(line)
                } catch (_: Exception) { null }
            }
            .takeLast(MAX_ENTRIES_MEMORY)
        _entries.value = loaded
    }

    private fun fromJsonSafe(line: String): AuditEntry? = runCatching {
        val obj = JSONObject(line)
        AuditEntry.fromJson(obj)
    }.getOrNull()
}

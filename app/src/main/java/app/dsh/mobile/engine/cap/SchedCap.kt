package app.dsh.mobile.engine.cap

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

/** Persistent exact alarms for AI reminders. */
object SchedCap {
    private const val PREFS = "reminders"
    private const val KEY_ITEMS = "items"
    const val ACTION_TRIGGER = "app.dsh.mobile.engine.cap.REMINDER_TRIGGER"
    const val ACTION_ACK = "app.dsh.mobile.engine.cap.REMINDER_ACK"
    const val ACTION_SNOOZE = "app.dsh.mobile.engine.cap.REMINDER_SNOOZE"

    fun create(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.SCHED)) return denied()
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to error("invalid json")
        val title = obj.optString("title").trim()
        val text = obj.optString("body").trim()
        if (title.isBlank() || text.isBlank()) return 400 to error("title and body are required")
        val at = when {
            obj.has("at") -> parseAt(obj.optString("at"))
            obj.has("in_seconds") -> System.currentTimeMillis() + obj.optLong("in_seconds").coerceAtLeast(1L) * 1000L
            else -> null
        } ?: return 400 to error("at or in_seconds is required")
        if (at <= System.currentTimeMillis()) return 400 to error("reminder time must be in the future")
        val repeat = obj.optJSONObject("repeat") ?: JSONObject().put("kind", "once")
        val id = "rm_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val item = JSONObject(obj.toString()).apply {
            put("id", id)
            put("next_at", at)
            put("repeat", repeat)
            put("priority", optString("priority", "default"))
            put("sound", optBoolean("sound", true))
            put("vibrate", optBoolean("vibrate", true))
        }
        save(ctx, load(ctx).put(item))
        register(ctx, item, at)
        CapabilityGate.audit(ctx, "dev.remind.create:$id")
        val response = JSONObject().put("ok", true).put("id", id).put("next_at", at)
        if (!isWhitelisted(ctx)) response.put("warn", "not_whitelisted")
        return 200 to response.toString()
    }

    fun list(ctx: Context): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.SCHED)) return denied()
        CapabilityGate.audit(ctx, "dev.remind-list")
        return 200 to JSONObject().put("ok", true).put("items", load(ctx)).toString()
    }

    fun cancel(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.SCHED)) return denied()
        val id = runCatching { JSONObject(body).optString("id") }.getOrDefault("").trim()
        if (id.isBlank()) return 400 to error("id required")
        val all = load(ctx)
        val kept = JSONArray()
        var removed = false
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optString("id") == id) {
                removed = true
                cancelAlarm(ctx, id)
            } else kept.put(item)
        }
        save(ctx, kept)
        CapabilityGate.audit(ctx, "dev.remind-cancel:$id")
        return 200 to JSONObject().put("ok", true).put("cancelled", removed).put("id", id).toString()
    }

    fun restore(ctx: Context) {
        if (!CapabilityGate.can(ctx, CapabilityGate.SCHED)) return
        load(ctx).let { all ->
            for (i in 0 until all.length()) {
                val item = all.optJSONObject(i) ?: continue
                val at = item.optLong("next_at", 0L)
                if (at > 0L) register(ctx, item, at)
            }
        }
    }

    internal fun onTrigger(ctx: Context, id: String) {
        val all = load(ctx)
        val item = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.firstOrNull { it.optString("id") == id } ?: return
        NotifyCap.showReminder(ctx, item)
        val kind = item.optJSONObject("repeat")?.optString("kind", "once") ?: "once"
        val next = nextAt(item, kind)
        if (next != null && next > System.currentTimeMillis()) {
            item.put("next_at", next)
            save(ctx, all)
            register(ctx, item, next)
        } else {
            val kept = JSONArray()
            for (i in 0 until all.length()) if (all.optJSONObject(i)?.optString("id") != id) kept.put(all.optJSONObject(i))
            save(ctx, kept)
        }
    }

    internal fun snooze(ctx: Context, id: String, minutes: Long) {
        val all = load(ctx)
        val item = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.firstOrNull { it.optString("id") == id } ?: return
        val next = System.currentTimeMillis() + minutes.coerceIn(1L, 1440L) * 60_000L
        item.put("next_at", next)
        save(ctx, all)
        register(ctx, item, next)
    }

    private fun nextAt(item: JSONObject, kind: String): Long? = when (kind) {
        "daily" -> item.optLong("next_at") + 86_400_000L
        "weekday" -> {
            var next = item.optLong("next_at") + 86_400_000L
            while (java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(next), java.time.ZoneId.systemDefault()).dayOfWeek.value >= 6) next += 86_400_000L
            next
        }
        else -> null
    }

    private fun parseAt(value: String): Long? = runCatching {
        ZonedDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrNull() ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun load(ctx: Context): JSONArray = runCatching { JSONArray(prefs(ctx).getString(KEY_ITEMS, "[]")) }.getOrDefault(JSONArray())
    private fun save(ctx: Context, value: JSONArray) { prefs(ctx).edit().putString(KEY_ITEMS, value.toString()).apply() }
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun register(ctx: Context, item: JSONObject, at: Long) {
        val alarm = ctx.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_TRIGGER).putExtra("id", item.optString("id"))
        val pi = PendingIntent.getBroadcast(ctx, requestCode(item.optString("id")), intent, pendingFlags())
        if (Build.VERSION.SDK_INT >= 23) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else alarm.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        if (Build.VERSION.SDK_INT >= 21) {
            val show = PendingIntent.getActivity(ctx, requestCode(item.optString("id")) xor 0x2a, Intent(ctx, app.dsh.mobile.MainActivity::class.java), pendingFlags())
            alarm.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        }
    }

    private fun cancelAlarm(ctx: Context, id: String) {
        val intent = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_TRIGGER).putExtra("id", id)
        ctx.getSystemService(AlarmManager::class.java)?.cancel(PendingIntent.getBroadcast(ctx, requestCode(id), intent, pendingFlags()))
    }

    private fun isWhitelisted(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 23) true
        else ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }.getOrDefault(false)

    private fun requestCode(id: String) = id.hashCode() and 0x7fffffff
    private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    private fun denied() = 403 to error("capability disabled")
    private fun error(message: String) = JSONObject().put("ok", false).put("error", message).toString()
}

class ReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            SchedCap.ACTION_TRIGGER -> SchedCap.onTrigger(ctx, intent.getStringExtra("id").orEmpty())
            SchedCap.ACTION_ACK -> Unit
            SchedCap.ACTION_SNOOZE -> SchedCap.snooze(ctx, intent.getStringExtra("id").orEmpty(), intent.getLongExtra("minutes", 10L))
        }
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            SchedCap.restore(context.applicationContext)
        }
    }
}

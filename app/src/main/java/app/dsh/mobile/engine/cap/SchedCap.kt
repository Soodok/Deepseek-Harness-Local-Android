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
        // ⚠️ 顺序与容错（v1.7.10）：register 会走 AlarmManager，缺精确闹钟授权时
        // setExactAndAllowWhileIdle 抛 SecurityException。旧写法把 register 放在
        // save 之后且不捕获 —— 结果是**条目已经落盘、接口却回 500**：调用方以为没建成
        // 于是重试，用户看到两条一样的提醒。现在 register 内部自己降级并把实际
        // 精度如实回传（见 registerPrecision），这里不会因为闹钟失败而丢条目。
        val precision = register(ctx, item, at)
        CapabilityGate.audit(ctx, "dev.remind.create:$id")
        val response = JSONObject().put("ok", true).put("id", id).put("next_at", at)
            .put("exact", precision == "exact")
            .put("precision", precision)
        // 两个 warn 是两件不同的事，混成一个会误导：
        //   · not_whitelisted → 电池优化白名单没开，**提醒本身仍会到**，只是可能延后
        //     （Doze 里 wakelock 受限）；
        //   · inexact_alarm → 拿不到精确闹钟，系统会攒批唤醒，可能晚几十分钟。
        // 实测（OnePlus PLZ110 / Android 16 / targetSdk 28）只出现前者，但后者在
        // 抬高 targetSdk 后必然出现，所以两条都显式给。
        val warn = JSONArray()
        if (!isWhitelisted(ctx)) warn.put("not_whitelisted")
        if (precision != "exact") warn.put("inexact_alarm")
        if (warn.length() > 0) {
            response.put("warn", warn)
            response.put(
                "warn_hint",
                "not_whitelisted: battery optimisation may delay the reminder; " +
                    "inexact_alarm: the system batches wakes, so it can fire late",
            )
        }
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

    /**
     * 注册系统闹钟，返回**实际达成的精度**（`exact` / `inexact`）。
     *
     * 为什么要降级而不是让异常冒出去（v1.7.10 真机审计）：提醒条目在此之前已经
     * 落盘，若这里抛 SecurityException，接口会回 500 而条目仍在 —— 调用方以为
     * 失败去重试，用户就得到两条一模一样的提醒。宁可晚几十秒，也不要「报错但已生效」。
     *
     * 三级降级，从强到弱：
     *  ① setAlarmClock —— 最优先，能穿过 Doze、并在系统里显示为「闹钟」（用户可见可改）。
     *  ② setExactAndAllowWhileIdle —— 精确但不显示在闹钟栏。
     *  ③ set / setAndAllowWhileIdle —— 不精确，可能被系统攒批延后。
     * 返回的字符串就是实际走到的那一级，供调用方决定要不要提示用户。
     */
    private fun register(ctx: Context, item: JSONObject, at: Long): String {
        val alarm = ctx.getSystemService(AlarmManager::class.java) ?: return "none"
        val id = item.optString("id")
        val intent = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_TRIGGER).putExtra("id", id)
        val pi = PendingIntent.getBroadcast(ctx, requestCode(id), intent, pendingFlags())

        // ① 闹钟样式：Android 12+ 同样要求精确闹钟授权，所以也要 try。
        if (Build.VERSION.SDK_INT >= 21) {
            val show = PendingIntent.getActivity(
                ctx, requestCode(id) xor 0x2a,
                Intent(ctx, app.dsh.mobile.MainActivity::class.java), pendingFlags(),
            )
            val ok = runCatching {
                alarm.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                true
            }.getOrDefault(false)
            if (ok) return "exact"
        }
        // ② 精确（穿 Doze）。
        val exactOk = runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                @Suppress("DEPRECATION")
                alarm.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            }
            true
        }.getOrDefault(false)
        if (exactOk) return "exact"
        // ③ 不精确兜底：晚到总比不到好。
        val fallbackOk = runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                @Suppress("DEPRECATION")
                alarm.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
            true
        }.getOrDefault(false)
        return if (fallbackOk) "inexact" else "none"
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

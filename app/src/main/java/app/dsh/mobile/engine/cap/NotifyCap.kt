package app.dsh.mobile.engine.cap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.dsh.mobile.R
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/** Notification sending, channel setup and notification-listener reads. */
object NotifyCap {
    const val CHANNEL_REMINDER = "agent_reminder"
    const val CHANNEL_NOTIFY = "agent_notify"
    const val CHANNEL_SILENT = "agent_silent"

    internal val cache = ConcurrentLinkedDeque<JSONObject>()

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDER, ctx.getString(R.string.notif_channel_reminder), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_NOTIFY, ctx.getString(R.string.notif_channel_agent), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SILENT, ctx.getString(R.string.notif_channel_silent), NotificationManager.IMPORTANCE_LOW))
    }

    fun read(ctx: Context, query: String): Pair<Int, String> {
        // 读他人通知是独立能力（NOTIFY_READ），与「AI 自己发通知」（NOTIFY）分开授权：
        // 用户可能愿意让 AI 提醒自己，却不愿让它读微信消息。
        if (!CapabilityGate.can(ctx, CapabilityGate.NOTIFY_READ)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled")
                .put("capability", CapabilityGate.NOTIFY_READ)
                .toString()
        }
        CapabilityGate.audit(ctx, "dev.notifications")
        val limit = query.split('&').firstOrNull { it.startsWith("limit=") }
            ?.substringAfter('=')?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val listener = DeviceNotificationListener.instance
        val arr = JSONArray()
        if (listener != null) {
            listener.activeNotifications.orEmpty().take(limit).forEach { arr.put(toJson(it)) }
            return 200 to JSONObject().put("ok", true).put("items", arr).toString()
        }
        synchronized(cache) {
            cache.take(limit).forEach { arr.put(it) }
        }
        return 200 to JSONObject().put("ok", true).put("degraded", "accessibility_cache").put("items", arr).toString()
    }

    fun send(ctx: Context, title: String, body: String, priority: String = "default", sound: Boolean = true, vibrate: Boolean = true): Boolean {
        if (!CapabilityGate.can(ctx, CapabilityGate.NOTIFY)) return false
        ensureChannels(ctx)
        val channel = when (priority.lowercase()) {
            "high" -> CHANNEL_REMINDER
            "low" -> CHANNEL_SILENT
            else -> CHANNEL_NOTIFY
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        builder.setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            val defaults = (if (sound) Notification.DEFAULT_SOUND else 0) or (if (vibrate) Notification.DEFAULT_VIBRATE else 0)
            builder.setDefaults(defaults)
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(System.currentTimeMillis().toInt() and 0x7fffffff, builder.build())
        CapabilityGate.audit(ctx, "dev.notify")
        return true
    }

    internal fun showReminder(ctx: Context, item: JSONObject) {
        if (!CapabilityGate.can(ctx, CapabilityGate.NOTIFY)) return
        ensureChannels(ctx)
        val id = item.optString("id")
        val priority = item.optString("priority", "default")
        val channel = when (priority.lowercase()) {
            "high" -> CHANNEL_REMINDER
            "low" -> CHANNEL_SILENT
            else -> CHANNEL_NOTIFY
        }
        val builder = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(item.optString("title"))
            .setContentText(item.optString("body"))
            .setStyle(Notification.BigTextStyle().bigText(item.optString("body")))
            .setAutoCancel(true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val ack = PendingIntent.getBroadcast(ctx, id.hashCode(), android.content.Intent(ctx, ReminderReceiver::class.java).setAction(SchedCap.ACTION_ACK).putExtra("id", id), flags)
        val snooze = PendingIntent.getBroadcast(ctx, id.hashCode() xor 0x51, android.content.Intent(ctx, ReminderReceiver::class.java).setAction(SchedCap.ACTION_SNOOZE).putExtra("id", id).putExtra("minutes", 10L), flags)
        builder.addAction(Notification.Action.Builder(null, ctx.getString(R.string.reminder_action_ack), ack).build())
        builder.addAction(Notification.Action.Builder(null, ctx.getString(R.string.reminder_action_snooze), snooze).build())
        ctx.getSystemService(NotificationManager::class.java).notify(id.hashCode(), builder.build())
        CapabilityGate.audit(ctx, "dev.remind.trigger:$id")
    }

    internal fun toJson(sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val extras = n.extras
        val actions = JSONArray()
        n.actions.orEmpty().forEach { action ->
            actions.put(JSONObject().put("title", action.title?.toString().orEmpty()).put("remote_input", action.remoteInputs?.isNotEmpty() == true))
        }
        return JSONObject()
            .put("key", sbn.key)
            .put("package", sbn.packageName)
            .put("app_label", sbn.packageName)
            .put("title", extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
            .put("text", extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            .put("big_text", extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
            .put("posted_at", sbn.postTime)
            .put("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
            .put("clearable", sbn.isClearable)
            .put("actions", actions)
            .put("group", n.group.orEmpty())
    }
}

/** Optional system-authorized notification listener. */
class DeviceNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        runCatching { activeNotifications.orEmpty().take(100).forEach { NotifyCap.cache.addFirst(NotifyCap.toJson(it)) } }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotifyCap.cache.addFirst(NotifyCap.toJson(sbn))
        while (NotifyCap.cache.size > 100) NotifyCap.cache.pollLast()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit

    companion object {
        @Volatile var instance: DeviceNotificationListener? = null
    }
}

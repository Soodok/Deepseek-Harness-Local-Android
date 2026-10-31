package app.dsh.mobile.engine.cap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.dsh.mobile.R
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 通知能力：自己发通知、读他人通知、**代用户回复他人通知**。
 *
 * ## 为什么「回复」是独立能力（NOTIFY_ACTION）
 * 读通知（NOTIFY_READ）只是看，回复是**以用户的身份对外发消息** —— 微信回了什么、
 * 短信承诺了什么，都是不可撤回的社交后果。喜欢让 AI 提醒自己（NOTIFY）的用户，
 * 未必愿意让它替自己回微信。因此这是第三个独立开关，默认关。
 *
 * ## RemoteInput 的实现约束
 * Android 的直接回复靠 `Notification.Action` 里的 `RemoteInput`：调用方要把文本塞进
 * 一个 `Bundle`，键名必须用该 action 自己的 `resultKey`，再交给 `actionIntent.send()`。
 * 少一步（漏填 resultKey、发错 action）目标 App 会收到空内容或直接崩，故这里逐层校验。
 */
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

    /**
     * POST /dev/notify-reply —— 代用户回复一条可回复的通知（微信/短信/QQ 等）。
     *
     * body: `{"key":"<通知 key>","text":"<要回复的内容>","action_index":0}`
     *
     * `key` 来自 GET /dev/notifications 返回的 `items[].key`；`action_index` 是
     * 该通知 `actions[]` 里带 `remote_input: true` 的序号（缺省取第一个可回复的）。
     *
     * 一步步的校验顺序是故意这么排的：先验能力开关（最便宜且被拒时不该泄露通知内容）、
     * 再找通知（可能已被用户划掉）、再找 action（可能这版 App 取消了回复入口）、
     * 最后才真正 send。这样失败时能明确告诉调用方是哪一层断了。
     */
    fun reply(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.NOTIFY_ACTION)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled")
                .put("capability", CapabilityGate.NOTIFY_ACTION)
                .toString()
        }
        val req = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val key = req.optString("key")
        val text = req.optString("text")
        if (key.isBlank()) return 400 to JSONObject().put("ok", false).put("error", "missing key").toString()
        if (text.isBlank()) return 400 to JSONObject().put("ok", false).put("error", "missing text").toString()
        val wantIndex = if (req.has("action_index")) req.optInt("action_index", -1) else -1

        val listener = DeviceNotificationListener.instance
            ?: return 409 to JSONObject().put("ok", false)
                .put("error", "listener_unavailable")
                .put("hint", "notification access not granted or service not connected")
                .toString()

        val sbn = runCatching { listener.activeNotifications.orEmpty().firstOrNull { it.key == key } }.getOrNull()
            ?: return 404 to JSONObject().put("ok", false)
                .put("error", "notification_not_found")
                .put("hint", "it may have been dismissed; re-list before replying")
                .toString()

        val actions = sbn.notification.actions.orEmpty()
        // 只认「真能带文本」的 action：remoteInputs 非空且 resultKey 有值。
        // 有些 App 声明的 remoteInputs 里 slot 是纯图片/选项，resultKey 为空，发过去也是空。
        val replyable = actions.withIndex().filter { (_, a) ->
            a.remoteInputs?.any { it.resultKey?.isNotBlank() == true } == true
        }
        if (replyable.isEmpty()) {
            return 422 to JSONObject().put("ok", false)
                .put("error", "not_replyable")
                .put("package", sbn.packageName)
                .put("action_count", actions.size)
                .toString()
        }
        val picked = if (wantIndex >= 0) {
            replyable.firstOrNull { it.index == wantIndex }
                ?: return 400 to JSONObject().put("ok", false)
                    .put("error", "action_not_replyable")
                    .put("replyable_indexes", JSONArray(replyable.map { it.index }))
                    .toString()
        } else {
            replyable.first()
        }

        val action = picked.value
        val resultKey = action.remoteInputs!!.first { it.resultKey?.isNotBlank() == true }.resultKey
        val intent = action.actionIntent
            ?: return 409 to JSONObject().put("ok", false).put("error", "action_intent_missing").toString()

        val result = runCatching {
            val bundle = Bundle().apply { putCharSequence(resultKey, text) }
            // 直接回复在部分 App 上还需要把每个 RemoteInput 的 slot 都回填，
            // 否则它们会读不到输入框内容 —— 全部 slot 都填同一段文本，代价可忽略。
            action.remoteInputs!!.forEach { ri ->
                val k = ri.resultKey
                if (!k.isNullOrBlank() && !bundle.containsKey(k)) bundle.putCharSequence(k, text)
            }
            val fill = Intent().apply { clipData = android.content.ClipData.newPlainText("reply", text) }
            // 标准写法：结果必须通过 RemoteInput.addResultsToIntent 挂到 Intent 上，
            // 再把这个 Intent 交给 actionIntent.send()；直接 send 空 Intent 目标 App 收到的是空串。
            RemoteInput.addResultsToIntent(action.remoteInputs, fill, bundle)
            intent.send(ctx, 0, fill)
        }

        return result.fold(
            onSuccess = {
                CapabilityGate.audit(ctx, "dev.notify.reply:${sbn.packageName}")
                // 回复后目标 App 一般会自己更新通知，这里同步刷新缓存免得列表显示旧内容。
                runCatching { NotifyCap.cache.addFirst(toJson(sbn)) }
                200 to JSONObject().put("ok", true)
                    .put("package", sbn.packageName)
                    .put("action_index", picked.index)
                    .put("chars", text.length)
                    .toString()
            },
            onFailure = { e ->
                500 to JSONObject().put("ok", false)
                    .put("error", "send_failed")
                    .put("detail", e.message.orEmpty().take(200))
                    .toString()
            },
        )
    }

    fun send(ctx: Context, title: String, body: String, priority: String = "default", sound: Boolean = true, vibrate: Boolean = true): Boolean {        if (!CapabilityGate.can(ctx, CapabilityGate.NOTIFY)) return false
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

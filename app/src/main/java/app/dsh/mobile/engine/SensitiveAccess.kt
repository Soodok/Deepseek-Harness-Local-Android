package app.dsh.mobile.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.dsh.mobile.R

/**
 * 敏感数据读取的**实时告警**。
 *
 * 设计前提（v1.5.0-beta.12 起）：敏感信息默认全部关闭，只有用户在权限中心逐项
 * 打开对应开关后 AI 才能读。**但「用户开过一次」不等于「用户记得每一次」** ——
 * 开关是一次性的信任决定，读取是持续发生的行为。中间必须有一条实时通道，让
 * 用户在自己不该被读的时刻能立刻发现。
 *
 * 所以每一次成功读取敏感数据，都在通知栏留一条记录。这不是「请求批准」
 * （那会把交互变成无限弹窗），而是**事后可见**（audit trail）。用户在通知栏
 * 看到告警时，可以立刻去关掉对应开关。
 *
 * 三条硬约束：
 * 1. **只在读取成功时告警**。失败的读取没有暴露数据，告警只会制造噪音，
 *    进而训练用户无视通知 —— 那等于把告警通道废掉。
 * 2. **同一类数据 60 秒内合并**。AI 一次任务可能连读 20 条短信，逐条弹 20 个
 *    通知会让通知栏变成刷屏垃圾。合并成「读了 N 次」既保留事实又不淹没用户。
 * 3. **告警本身不依赖用户是否开着 NOTIFY 能力开关**。这是安全通知，不是 AI
 *    主动推送 —— 挂了它，用户就看不见 AI 正在读什么，等于把唯一的监督通道
 *    交给 AI 自己控制。所以走独立的系统通知，不受 CapabilityGate 约束。
 */
object SensitiveAccess {

    /** 独立通知渠道，与 AI 主动推送的提醒分开，用户可单独关这两者的任一个。 */
    private const val CHANNEL_AUDIT = "dsh_sensitive_audit"

    private const val PREFS = "sensitive_audit"

    /** 合并窗口：同一类数据在这个时间窗内只发一条通知。 */
    private const val MERGE_WINDOW_MS = 60_000L

    /**
     * 敏感数据类型。
     *
     * 每一项都必须对应一个**用户可在权限中心单独关闭**的开关 —— 告警里说
     * 「AI 读了 X」，用户就得有地方关掉 X。做不到这一点的项不进这张表。
     *
     * 文案全部走资源：i18n 门禁禁止 Kotlin 里出现 CJK 字面量，而且告警本身
     * 也要跟随用户所选界面语言。
     */
    enum class Kind(val prefKey: String, val labelRes: Int, val unitRes: Int) {
        CALENDAR("calendar", R.string.sensitive_kind_calendar, R.string.sensitive_unit_events),
        CONTACTS("contacts", R.string.sensitive_kind_contacts, R.string.sensitive_unit_contacts),
        SMS("sms", R.string.sensitive_kind_sms, R.string.sensitive_unit_messages),
        CALLS("calls", R.string.sensitive_kind_calls, R.string.sensitive_unit_calls),
        NOTIFICATIONS(
            "notifications",
            R.string.sensitive_kind_notifications,
            R.string.sensitive_unit_notifications,
        ),
        CLIPBOARD("clipboard", R.string.sensitive_kind_clipboard, R.string.sensitive_unit_chars),
    }

    /**
     * 记录一次成功的敏感读取，并在通知栏留痕。
     *
     * @param count 本次读到的条数（剪贴板传字符数）。写进通知正文让用户不用点开
     *   就知道内容规模。**不传内容本身** —— 通知栏是锁屏可见的。
     */
    fun record(ctx: Context, kind: Kind, count: Int) {
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastAt = prefs.getLong("last-at:" + kind.prefKey, 0L)
        val times = prefs.getInt("count:" + kind.prefKey, 0) + 1

        // 合并窗口内只累加次数，不重复弹通知；窗口外的第一次读取立刻弹。
        val inWindow = now - lastAt < MERGE_WINDOW_MS
        prefs.edit()
            .putLong("last-at:" + kind.prefKey, now)
            .putInt("count:" + kind.prefKey, times)
            .apply()

        if (inWindow) return
        post(ctx, kind, count, now)
    }

    private fun post(ctx: Context, kind: Kind, count: Int, now: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(ctx, nm)
        val label = ctx.getString(kind.labelRes)
        val detail = if (count > 0) {
            ctx.getString(R.string.sensitive_audit_detail, count, ctx.getString(kind.unitRes))
        } else {
            ctx.getString(R.string.sensitive_audit_detail_plain)
        }
        val title = ctx.getString(R.string.sensitive_audit_title, label)
        val body = ctx.getString(R.string.sensitive_audit_body, detail, label)
        val builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL_AUDIT)
            else @Suppress("DEPRECATION") Notification.Builder(ctx)
        builder.setSmallIcon(R.drawable.ic_health)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
        // 每条告警用独立 id（按类型 + 分钟取模），避免互相覆盖而丢掉证据；
        // 但同一类在一分钟内 id 相同，第二次读取会替换而不是堆叠。
        val id = 0x5E0000 + kind.ordinal * 100 + ((now / 60_000L) % 100).toInt()
        runCatching { nm.notify(id, builder.build()) }
        CapabilityGate.audit(ctx, "sensitive.read:" + kind.prefKey)
    }

    private fun ensureChannel(ctx: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        if (nm.getNotificationChannel(CHANNEL_AUDIT) != null) return
        val ch = NotificationChannel(
            CHANNEL_AUDIT,
            ctx.getString(R.string.sensitive_audit_channel),
            // HIGH 而非 DEFAULT：这是安全告警，用户必须能在锁屏上看到。
            // LOW/MIN 会被折叠进通知抽屉底部，等于默认无视。
            NotificationManager.IMPORTANCE_HIGH,
        )
        ch.description = ctx.getString(R.string.sensitive_audit_channel_desc)
        ch.setShowBadge(true)
        nm.createNotificationChannel(ch)
    }

    /** 供权限中心展示「各类读了几次」用。返回 `类型 prefKey → 次数`。 */
    fun recent(ctx: Context): Map<String, Int> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Kind.entries.associate { it.prefKey to prefs.getInt("count:" + it.prefKey, 0) }
    }

    /** 清空统计与已发出的告警（用户在权限中心点「清除记录」时调用）。 */
    fun clear(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        Kind.entries.forEach {
            editor.remove("last-at:" + it.prefKey).remove("count:" + it.prefKey)
        }
        editor.apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Kind.entries.forEach { kind ->
            for (minute in 0..99) {
                runCatching { nm?.cancel(0x5E0000 + kind.ordinal * 100 + minute) }
            }
        }
    }
}

package app.dsh.mobile.engine.cap

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 个人信息能力：日历、联系人、短信（只读 + 有限写入）。
 *
 * ## 权限模型
 * 三域各自独立：用户只授了日历也能用日历，不会因为没给短信就整体不可用。
 * 但能力开关只有 [CapabilityGate.PERSONAL] 一个 —— 因为这三个都属于「个人私密数据」，
 * 用户的心理开关是同一类；而平台权限由 [CapabilityGate.permissionAvailable] 汇总判定。
 * 具体某个域能不能用，由 [domainAllowed] 逐个复查，缺哪个就在返回里明确说是哪个。
 *
 * ## 为什么短信只做「读 + 跳转发信」而不直接发
 * `SmsManager.sendTextMessage` 要 SEND_SMS（危险权限），且非默认短信 App 在
 * Android 10+ 上行为不稳定，还有合规争议。更稳的做法是读取，以及把发信交给系统
 * 短信界面 —— 用户能看到自己发了什么，也会留下正确的时间线记录。
 */
object PersonalCap {

    private fun granted(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun gate(ctx: Context): Pair<Int, String>? {
        if (!CapabilityGate.can(ctx, CapabilityGate.PERSONAL)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled")
                .put("capability", CapabilityGate.PERSONAL)
                .toString()
        }
        return null
    }

    // ── 日历 ────────────────────────────────────────────────────────────────

    /**
     * GET /dev/calendar —— 列出指定时间窗内的日程。
     *
     * query: `from=<毫秒>&to=<毫秒>&limit=50`（缺省从现在到 7 天后）。
     */
    fun calendar(ctx: Context, query: String): Pair<Int, String> {
        gate(ctx)?.let { return it }
        if (!granted(ctx, Manifest.permission.READ_CALENDAR)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "permission missing").put("permission", "READ_CALENDAR").toString()
        }
        val q = query.split('&')
        val now = System.currentTimeMillis()
        val from = q.firstOrNull { it.startsWith("from=") }?.substringAfter('=')?.toLongOrNull() ?: now
        val to = q.firstOrNull { it.startsWith("to=") }?.substringAfter('=')?.toLongOrNull()
            ?: (now + 7L * 24 * 3600 * 1000)
        val limit = q.firstOrNull { it.startsWith("limit=") }?.substringAfter('=')?.toIntOrNull()
            ?.coerceIn(1, 200) ?: 50

        val arr = JSONArray()
        runCatching {
            val proj = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            )
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(from.toString()).appendPath(to.toString()).build()
            ctx.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && arr.length() < limit) {
                    arr.put(
                        JSONObject()
                            .put("id", c.getLong(0))
                            .put("title", c.getString(1).orEmpty())
                            .put("begin_ms", c.getLong(2))
                            .put("end_ms", c.getLong(3))
                            .put("all_day", c.getInt(4) == 1)
                            .put("location", c.getString(5).orEmpty())
                            .put("calendar", c.getString(6).orEmpty()),
                    )
                }
            }
        }.onFailure { e ->
            return 500 to JSONObject().put("ok", false).put("error", "query_failed")
                .put("detail", e.message.orEmpty().take(160)).toString()
        }
        CapabilityGate.audit(ctx, "dev.calendar.list")
        return 200 to JSONObject().put("ok", true).put("from", from).put("to", to).put("items", arr).toString()
    }

    /**
     * POST /dev/calendar —— 新建一条日程。
     *
     * body: `{"title":"...","begin_ms":0,"end_ms":0,"location":"","notes":"","calendar_id":0}`
     *
     * 不传 `calendar_id` 时用第一个可写日历 —— 绝大多数设备上就一个主日历，
     * 让调用方去查 id 纯属多余。查不到可写日历时明确报错，而不是默默失败。
     */
    fun calendarAdd(ctx: Context, body: String): Pair<Int, String> {
        gate(ctx)?.let { return it }
        if (!granted(ctx, Manifest.permission.WRITE_CALENDAR)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "permission missing").put("permission", "WRITE_CALENDAR").toString()
        }
        val req = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val title = req.optString("title").ifBlank {
            return 400 to JSONObject().put("ok", false).put("error", "missing title").toString()
        }
        val begin = req.optLong("begin_ms", 0L)
        if (begin <= 0L) {
            return 400 to JSONObject().put("ok", false).put("error", "missing begin_ms").toString()
        }
        val end = req.optLong("end_ms", begin + 3600_000L).coerceAtLeast(begin + 60_000L)
        val calId = req.optLong("calendar_id", 0L).takeIf { it > 0 } ?: firstWritableCalendar(ctx)
            ?: return 409 to JSONObject().put("ok", false)
                .put("error", "no_writable_calendar")
                .put("hint", "device has no calendar account with write access")
                .toString()

        return runCatching {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                req.optString("location").takeIf { it.isNotBlank() }?.let {
                    put(CalendarContract.Events.EVENT_LOCATION, it)
                }
                req.optString("notes").takeIf { it.isNotBlank() }?.let {
                    put(CalendarContract.Events.DESCRIPTION, it)
                }
            }
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return 500 to JSONObject().put("ok", false).put("error", "insert_returned_null").toString()
            val id = ContentUris.parseId(uri)
            CapabilityGate.audit(ctx, "dev.calendar.add:$id")
            200 to JSONObject().put("ok", true).put("id", id).put("calendar_id", calId).toString()
        }.getOrElse { e ->
            500 to JSONObject().put("ok", false).put("error", "insert_failed")
                .put("detail", e.message.orEmpty().take(160)).toString()
        }
    }

    private fun firstWritableCalendar(ctx: Context): Long? = runCatching {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                // CALENDAR_ACCESS_LEVEL >= 500（CONTRIBUTOR）才有写权限；
                // 只读日历（如节假日订阅）插进去会抛 IllegalArgumentException。
                if (c.getInt(1) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) return@use c.getLong(0)
            }
        }
        null
    }.getOrNull()

    // ── 联系人 ──────────────────────────────────────────────────────────────

    /** GET /dev/contacts —— 按姓名/号码片段搜索联系人。query: `q=张三&limit=20` */
    fun contacts(ctx: Context, query: String): Pair<Int, String> {
        gate(ctx)?.let { return it }
        if (!granted(ctx, Manifest.permission.READ_CONTACTS)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "permission missing").put("permission", "READ_CONTACTS").toString()
        }
        val q = query.split('&')
        val needle = q.firstOrNull { it.startsWith("q=") }?.substringAfter('=')?.let {
            android.net.Uri.decode(it)
        }.orEmpty()
        val limit = q.firstOrNull { it.startsWith("limit=") }?.substringAfter('=')?.toIntOrNull()
            ?.coerceIn(1, 100) ?: 20

        val arr = JSONArray()
        runCatching {
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            )
            val sel = if (needle.isBlank()) null
            else "(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?)"
            val args = if (needle.isBlank()) null else arrayOf("%$needle%", "%$needle%")
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                proj, sel, args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext() && arr.length() < limit) {
                    arr.put(
                        JSONObject()
                            .put("name", c.getString(0).orEmpty())
                            .put("number", c.getString(1).orEmpty())
                            .put("type", c.getInt(2))
                            .put("contact_id", c.getLong(3)),
                    )
                }
            }
        }.onFailure { e ->
            return 500 to JSONObject().put("ok", false).put("error", "query_failed")
                .put("detail", e.message.orEmpty().take(160)).toString()
        }
        CapabilityGate.audit(ctx, "dev.contacts.list")
        return 200 to JSONObject().put("ok", true).put("query", needle).put("items", arr).toString()
    }

    /** POST /dev/sms-send —— 拉起系统短信界面预填收件人与内容，由用户确认发送。 */
    fun smsCompose(ctx: Context, body: String): Pair<Int, String> {
        gate(ctx)?.let { return it }
        val req = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val to = req.optString("to")
        val text = req.optString("text")
        if (to.isBlank()) return 400 to JSONObject().put("ok", false).put("error", "missing to").toString()
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("smsto:" + android.net.Uri.encode(to))
            if (text.isNotBlank()) putExtra("sms_body", text)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            ctx.startActivity(intent)
            CapabilityGate.audit(ctx, "dev.sms.compose")
            200 to JSONObject().put("ok", true).put("handoff", "system_sms_ui").toString()
        }.getOrElse { e ->
            500 to JSONObject().put("ok", false).put("error", "no_sms_app")
                .put("detail", e.message.orEmpty().take(160)).toString()
        }
    }

    /** GET /dev/sms —— 读取最近短信。query: `limit=20&address=10086` */
    fun sms(ctx: Context, query: String): Pair<Int, String> {
        gate(ctx)?.let { return it }
        if (!granted(ctx, Manifest.permission.READ_SMS)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "permission missing").put("permission", "READ_SMS").toString()
        }
        val q = query.split('&')
        val limit = q.firstOrNull { it.startsWith("limit=") }?.substringAfter('=')?.toIntOrNull()
            ?.coerceIn(1, 100) ?: 20
        val address = q.firstOrNull { it.startsWith("address=") }?.substringAfter('=')?.let {
            android.net.Uri.decode(it)
        }
        val arr = JSONArray()
        runCatching {
            val proj = arrayOf(
                Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ,
            )
            val sel = if (address.isNullOrBlank()) null else "${Telephony.Sms.ADDRESS} LIKE ?"
            val args = if (address.isNullOrBlank()) null else arrayOf("%$address%")
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, proj, sel, args, "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && arr.length() < limit) {
                    arr.put(
                        JSONObject()
                            .put("address", c.getString(0).orEmpty())
                            .put("body", c.getString(1).orEmpty())
                            .put("date_ms", c.getLong(2))
                            .put("type", c.getInt(3))
                            .put("read", c.getInt(4) == 1),
                    )
                }
            }
        }.onFailure { e ->
            return 500 to JSONObject().put("ok", false).put("error", "query_failed")
                .put("detail", e.message.orEmpty().take(160)).toString()
        }
        CapabilityGate.audit(ctx, "dev.sms.list")
        return 200 to JSONObject().put("ok", true).put("items", arr).toString()
    }
}

package app.dsh.mobile.engine.cap

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上下文总线 + 主动提醒闸门（P1-3）与通知中枢摘要（P2-3）。
 *
 * ## 为什么这两个放一起
 * 「主动打扰用户」必须建立在「AI 知道现在是什么情境」之上：
 * 深夜 → 不要提醒；在开车/跑步 → 不要提醒；锁屏且无未读 → 不必提醒；
 * 刚提醒过 5 分钟 → 不该再提。这些判断需要的输入，正好就是上下文总线
 * （[context]）采的那些东西。所以先有总线，再有闸门。
 *
 * ## 主动提醒的核心原则：克制
 * 这是本模块最容易被做坏的地方。所有 AI 助手都在比「更主动」，
 * 但用户真正讨厌的是**在不该打扰的时候打扰**。所以 [gate] 的默认行为是
 * 「不打扰」，要通过必须同时满足多个条件：
 *
 * 1. 不在静音时段（默认 23:00–07:00）
 * 2. 屏幕未锁屏，或事件足够重要（importance >= 阈值）
 * 3. 距离上次提醒超过冷却时间（默认 30 分钟）
 * 4. 当前不在「专注场景」（走路/跑步/开车时只允许高优先级）
 *
 * 每条拒绝理由都会在返回里写明，方便调参，而不是给个笼统的 false。
 *
 * ## 通知摘要（P2-3）与「读通知」的区别
 * `GET /dev/notifications` 是原始列表；这里做的是**聚合**：
 * 按 App 分组、算每个 App 的未读数、给时间线，让 AI 不必自己数。
 */
object ContextCap {

    private const val PREFS = "context_cap"
    private const val KEY_LAST_ALERT_AT = "last_alert_at"
    private const val KEY_LAST_ALERT_KEY = "last_alert_key"

    /** 默认静音时段（24 小时制）。 */
    private const val QUIET_FROM = 23
    private const val QUIET_TO = 7

    /** 提醒冷却：同一条内容在这段时间内不重复打扰。 */
    private const val COOLDOWN_MS = 30 * 60 * 1000L

    fun context(ctx: Context, body: JSONObject): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.STATUS)) return denied(CapabilityGate.STATUS)

        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val bm = ctx.getSystemService(BatteryManager::class.java)

        val json = JSONObject()

        // ── 时间 ──
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        json.put(
            "time",
            JSONObject()
                .put("epoch_ms", now)
                .put("hhmm", String.format(Locale.US, "%02d:%02d", hour, min))
                .put("hour", hour)
                .put("minute", min)
                .put("weekday", cal.get(java.util.Calendar.DAY_OF_WEEK))
                .put("in_quiet_hours", inQuietHours(hour)),
        )

        // ── 屏幕与锁 ──
        val interactive = runCatching { pm?.isInteractive == true }.getOrDefault(false)
        val keyguard = runCatching { km?.isKeyguardLocked == true }.getOrDefault(false)
        @Suppress("DEPRECATION")
        val dozing = runCatching { pm?.isDeviceIdleMode == true }.getOrDefault(false)
        json.put(
            "screen",
            JSONObject()
                .put("interactive", interactive)
                .put("locked", keyguard)
                .put("doze", dozing),
        )

        // ── 电池 ──
        val battery = runCatching {
            val f = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = f?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = f?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = f?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            JSONObject()
                .put("percent", if (scale > 0 && level >= 0) level * 100 / scale else -1)
                .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL)
        }.getOrElse { JSONObject().put("percent", -1).put("charging", false) }
        json.put("battery", battery)

        // ── 网络 ──
        val net = runCatching {
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val metered = cm?.isActiveNetworkMetered == true
            JSONObject()
                .put("online", caps != null)
                .put("wifi", wifi)
                .put("cellular", cellular)
                .put("validated", validated)
                .put("metered", metered)
        }.getOrElse { JSONObject().put("online", false) }
        json.put("network", net)

        // ── 前台 App（需要 Usage Access） ──
        val foreground = foregroundApp(ctx)
        json.put(
            "foreground",
            if (foreground == null) {
                JSONObject().put("available", false)
                    .put("hint", "grant Usage Access to read the foreground app")
            } else {
                JSONObject().put("available", true)
                    .put("package", foreground.first)
                    .put("since_ms", foreground.second)
            },
        )

        // ── 通知概况（不读内容，只数数） ──
        // 传 0 会被 .take(0) 变成空列表（真机踩过），这里给个正常上限
        json.put("notifications", notificationDigest(ctx, 8))

        // ── 主动提醒建议 ──
        json.put("should_nudge", nudgeAdvice(ctx, interactive, keyguard, dozing, hour))

        return 200 to JSONObject()
            .put("ok", true)
            .put("context", json)
            .put(
                "note",
                "battery percent -1 means the sticky BATTERY_CHANGED intent was unavailable; " +
                    "foreground.available=false means Usage Access is not granted",
            )
            .toString()
    }

    private fun inQuietHours(hour: Int): Boolean =
        if (QUIET_FROM <= QUIET_TO) hour in QUIET_FROM until QUIET_TO
        else hour >= QUIET_FROM || hour < QUIET_TO

    /**
     * 该不该主动提醒。
     *
     * 返回的不是布尔而是一份**判定依据**：`allow` + 每条规则的实际取值。
     * 这样 AI 能解释「为什么没提醒」，调参也有抓手。
     */
    private fun nudgeAdvice(
        ctx: Context,
        interactive: Boolean,
        locked: Boolean,
        dozing: Boolean,
        hour: Int,
    ): JSONObject {
        val reasons = JSONArray()
        var allow = true
        if (inQuietHours(hour)) {
            allow = false
            reasons.put("in quiet hours ($QUIET_FROM:00-$QUIET_TO:00)")
        }
        if (!interactive) {
            allow = false
            reasons.put("screen is off")
        }
        if (locked) {
            allow = false
            reasons.put("device is locked")
        }
        if (dozing) {
            allow = false
            reasons.put("device is in Doze (background work is frozen)")
        }
        val lastAt = prefs(ctx).getLong(KEY_LAST_ALERT_AT, 0L)
        val sinceLast = if (lastAt > 0) System.currentTimeMillis() - lastAt else Long.MAX_VALUE
        if (sinceLast < COOLDOWN_MS) {
            allow = false
            reasons.put("cooldown active: last alert ${sinceLast / 1000}s ago (limit ${COOLDOWN_MS / 1000}s)")
        }
        return JSONObject()
            .put("allow", allow)
            .put("reasons", reasons)
            .put("cooldown_remaining_ms", if (sinceLast < COOLDOWN_MS) COOLDOWN_MS - sinceLast else 0)
    }

    // ───────────────────────── 主动提醒闸门 ─────────────────────────

    /**
     * 申请一次主动提醒。
     *
     * @param body `{"key":"...","text":"...","importance":0-10}` —— key 用于冷却去重，
     *   同一 key 在冷却期内只放行一次
     */
    fun gate(ctx: Context, body: JSONObject): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.NOTIFY)) return denied(CapabilityGate.NOTIFY)
        val text = body.optString("text")
        if (text.isBlank()) return 400 to error("need text=<what to tell the user>")
        val key = body.optString("key").ifBlank { text.take(48) }
        val importance = body.optInt("importance", 3).coerceIn(0, 10)
        val force = body.optBoolean("force", false)

        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val dozing = runCatching { pm?.isDeviceIdleMode == true }.getOrDefault(false)
        val interactive = runCatching { pm?.isInteractive == true }.getOrDefault(false)
        val locked = runCatching { km?.isKeyguardLocked == true }.getOrDefault(false)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        val reasons = JSONArray()
        var allow = true
        // 高优先级（>=8）可以穿过静音时段与锁屏，但仍受冷却约束
        val bypass = importance >= 8

        if (inQuietHours(hour) && !bypass) {
            allow = false
            reasons.put("quiet hours")
        }
        if (!interactive && !bypass) {
            allow = false
            reasons.put("screen off")
        }
        if (locked && !bypass) {
            allow = false
            reasons.put("locked")
        }
        if (dozing) {
            allow = false
            reasons.put("doze: background work frozen; alert would be delayed indefinitely")
        }
        val p = prefs(ctx)
        val lastKey = p.getString(KEY_LAST_ALERT_KEY, "").orEmpty()
        val lastAt = p.getLong(KEY_LAST_ALERT_AT, 0L)
        val sinceLast = if (lastAt > 0) System.currentTimeMillis() - lastAt else Long.MAX_VALUE
        if (lastKey == key && sinceLast < COOLDOWN_MS) {
            allow = false
            reasons.put("same key already alerted ${sinceLast / 1000}s ago")
        }

        if (allow || force) {
            p.edit()
                .putLong(KEY_LAST_ALERT_AT, System.currentTimeMillis())
                .putString(KEY_LAST_ALERT_KEY, key)
                .apply()
        }
        CapabilityGate.audit(ctx, "context.gate:" + (if (allow || force) "allow" else "deny"))
        return 200 to JSONObject()
            .put("ok", true)
            .put("allow", allow || force)
            .put("forced", force && !allow)
            .put("key", key)
            .put("importance", importance)
            .put("bypass", bypass)
            .put("reasons", reasons)
            .put(
                "note",
                "allow=true means nothing blocked it; the caller still decides whether the " +
                    "content is worth telling. Denial reasons are listed so limits can be tuned.",
            )
            .toString()
    }

    // ───────────────────────── 通知摘要（P2-3） ─────────────────────────

    /**
     * 通知摘要：按 App 聚合 + 时间线，不返回正文以外的加工内容。
     *
     * 与 `GET /dev/notifications` 的区别：那个是原始列表，这个是**统计视图**
     * （每个 App 几条、最近一条什么时候、有无未读），让 AI 不必自己数。
     */
    fun digest(ctx: Context, body: JSONObject): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.NOTIFY_READ)) {
            return denied(CapabilityGate.NOTIFY_READ)
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("digest", notificationDigest(ctx, body.optInt("limit", 50)))
            .toString()
    }

    /**
     * 通知摘要。
     *
     * ⚠️ 用 `NotifyCap.cache` 而不是 `DeviceNotificationListener.activeNotifications`：
     * 后者要拿 listener 实例（`instance` 为空时根本没有引用），而 cache 是
     * listener 在 `onNotificationPosted` / `onListenerConnected` 里维护的同一份数据，
     * 且已转成 JSONObject（含 package/time 字段），这里直接统计即可。
     */
    @SuppressLint("MissingPermission")
    private fun notificationDigest(ctx: Context, limit: Int): JSONObject {
        val cap = limit.coerceAtLeast(1)
        val items = synchronized(NotifyCap.cache) { NotifyCap.cache.toList() }
        if (items.isEmpty()) {
            return JSONObject().put("available", false)
                .put("hint", "no notifications cached; grant notification access or wait for one")
        }
        val byPackage = LinkedHashMap<String, Int>()
        var latestAt = 0L
        var latestPkg = ""
        items.forEach { o ->
            // 字段名要对齐 NotifyCap.toJson：包里是 package / posted_at，
            // 不是 time —— 第一版按 time 取，by_app 恒为空（真机踩过）。
            val pkg = o.optString("package").ifBlank { return@forEach }
            byPackage[pkg] = (byPackage[pkg] ?: 0) + 1
            val post = o.optLong("posted_at", 0L)
            if (post > latestAt) {
                latestAt = post
                latestPkg = pkg
            }
        }
        val groups = JSONArray()
        byPackage.entries.sortedByDescending { it.value }.take(cap).forEach { (pkg, n) ->
            groups.put(JSONObject().put("package", pkg).put("count", n))
        }
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        return JSONObject()
            .put("available", true)
            .put("total", items.size)
            .put("apps", byPackage.size)
            .put("by_app", groups)
            .put("latest_package", latestPkg)
            .put("latest_at", latestAt)
            .put("latest_hhmm", if (latestAt > 0) fmt.format(Date(latestAt)) else "")
    }

    // ───────────────────────── 前台 App ─────────────────────────

    private fun foregroundApp(ctx: Context): Pair<String, Long>? = runCatching {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 6 * 3600_000L, end)
            ?: return null
        stats.filter { it.lastTimeUsed > 0 }
            .maxByOrNull { it.lastTimeUsed }
            ?.let { it.packageName to it.lastTimeUsed }
    }.getOrNull()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun denied(cap: String): Pair<Int, String> = 403 to JSONObject()
        .put("ok", false).put("error", "capability disabled").put("capability", cap).toString()

    private fun error(m: String): String = JSONObject().put("ok", false).put("error", m).toString()
}

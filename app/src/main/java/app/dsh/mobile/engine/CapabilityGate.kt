package app.dsh.mobile.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/**
 * Single source of truth for device capabilities.
 * A capability is usable only when its explicit user switch is on and the
 * platform permission/special access it needs is currently available.
 */
object CapabilityGate {
    private const val PREFS = "capabilities"
    private const val KEY_PREFIX = "cap."

    const val STATUS = "status"
    const val SENSOR = "sensor"
    const val NOTIFY = "notify"
    const val NOTIFY_READ = "notify_read"

    /**
     * 代用户回复他人通知。
     *
     * 与 [NOTIFY_READ] 分开是刻意的：读通知只是「看」，回复是**以用户身份对外发言**，
     * 后果不可撤回。愿意让 AI 读消息的用户未必愿意让它替自己回微信，所以这是第三个开关。
     */
    const val NOTIFY_ACTION = "notify_action"
    const val CLIPBOARD = "clipboard"
    const val CONTROL = "control"
    const val WIFI = "wifi"
    const val BT = "bt"
    const val SCHED = "sched"

    /** 媒体播放控制（MediaSession：播放/暂停/上一首/下一首/进度）。 */
    const val MEDIA = "media"

    /** 个人信息：日历、联系人、短信。三者同属「读取用户私密数据」一并授权。 */
    const val PERSONAL = "personal"

    /**
     * NFC 与地理围栏。
     *
     * 与 [BT] 分开：蓝牙是「和附近设备通信」，NFC 是「贴一下」的身份/门禁域，
     * 地理围栏则是位置相关的持续后台动作 —— 用户对这三者的心理预期完全不同。
     */
    const val RF = "rf"

    /** 屏幕录制（MediaProjection，每次启动必须用户点头）。 */
    const val SCREEN_CAPTURE = "screen_capture"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Explicit user opt-in. Every switch defaults to false. */
    fun enabled(ctx: Context, name: String): Boolean =
        prefs(ctx).getBoolean(KEY_PREFIX + name, false)

    fun setEnabled(ctx: Context, name: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PREFIX + name, enabled).apply()
        audit(ctx, "cap.$name=${if (enabled) "enabled" else "disabled"}")
    }

    /** Record an invocation using the existing LastAction persistence pattern. */
    fun audit(ctx: Context, action: String) = LastAction.record(ctx, "capability:$action")

    /** True only when the switch and platform requirement both pass. */
    fun can(ctx: Context, name: String): Boolean =
        enabled(ctx, name) && permissionAvailable(ctx, name)

    /**
     * 能力开关总览。
     *
     * ⚠️ 必须返回 JSONObject 而不是 Map：Android 的 org.json 在序列化未知类型时
     * 抛 `Illegal object in JSONStringer`，直接 put(Map) 会让 /dev/ping 变成 500，
     * 三层链路「看起来通、实际全废」。
     */
    fun snapshot(ctx: Context): JSONObject = JSONObject()
        .put(STATUS, can(ctx, STATUS))
        .put(SENSOR, can(ctx, SENSOR))
        .put(NOTIFY, can(ctx, NOTIFY))
        .put(NOTIFY_READ, can(ctx, NOTIFY_READ))
        .put(NOTIFY_ACTION, can(ctx, NOTIFY_ACTION))
        .put(CLIPBOARD, can(ctx, CLIPBOARD))
        .put(CONTROL, can(ctx, CONTROL))
        .put(WIFI, can(ctx, WIFI))
        .put(BT, can(ctx, BT))
        .put(SCHED, can(ctx, SCHED))
        .put(MEDIA, can(ctx, MEDIA))
        .put(PERSONAL, can(ctx, PERSONAL))
        .put(RF, can(ctx, RF))
        .put(SCREEN_CAPTURE, can(ctx, SCREEN_CAPTURE))

    /** Platform truth, independent from the user switch. */
    fun permissionAvailable(ctx: Context, name: String): Boolean = when (name) {
        NOTIFY -> Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        WIFI -> ctx.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
        BT -> Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        NOTIFY_READ, NOTIFY_ACTION -> notificationListenerEnabled(ctx)
        // MediaSession.getActiveSessions 要 MEDIA_CONTENT_CONTROL（系统权限，拿不到），
        // 实际路径是「通知监听权限 → 从通知里取 MediaController」。所以判定同上。
        MEDIA -> notificationListenerEnabled(ctx)
        // 日历/联系人/短信三者任一可用即算「平台允许」，具体域在调用处再逐个校验，
        // 这样用户只授了日历也能用日历，不会因为没给短信就整体置灰。
        PERSONAL -> hasAny(ctx, Manifest.permission.READ_CALENDAR, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS)
        // NFC 硬件存在 + 系统里 NFC 已打开，两者都成立才算可用：
        // 应用无法在 Android 10+ 静默打开 NFC，所以硬件和开关要分别判，缺哪个就报哪个。
        RF -> nfcUsable(ctx) || locationUsable(ctx)
        SCREEN_CAPTURE -> Build.VERSION.SDK_INT >= 21
        CONTROL, STATUS, SENSOR, CLIPBOARD, SCHED -> true
        else -> false
    }

    private fun nfcUsable(ctx: Context): Boolean = runCatching {
        android.nfc.NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true
    }.getOrDefault(false)

    private fun locationUsable(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasAny(ctx: Context, vararg perms: String): Boolean =
        perms.any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun notificationListenerEnabled(ctx: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        // 每一项是完整组件名（`pkg/pkg.engine.cap.DeviceNotificationListener`），
        // 只比 `/` 之前的包名：直接跟 packageName 做 equals 永远不成立，
        // 会让「读取通知」开关永久置灰、用户永远点不动。
        enabled.split(':').any {
            it.substringBefore('/').trim().equals(ctx.packageName, ignoreCase = true)
        }
    }.getOrDefault(false)
}

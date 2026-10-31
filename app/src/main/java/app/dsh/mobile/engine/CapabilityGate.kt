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
    const val CLIPBOARD = "clipboard"
    const val CONTROL = "control"
    const val WIFI = "wifi"
    const val BT = "bt"
    const val SCHED = "sched"

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
        .put(CLIPBOARD, can(ctx, CLIPBOARD))
        .put(CONTROL, can(ctx, CONTROL))
        .put(WIFI, can(ctx, WIFI))
        .put(BT, can(ctx, BT))
        .put(SCHED, can(ctx, SCHED))

    /** Platform truth, independent from the user switch. */
    fun permissionAvailable(ctx: Context, name: String): Boolean = when (name) {
        NOTIFY -> Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        WIFI -> ctx.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
        BT -> Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        NOTIFY_READ -> notificationListenerEnabled(ctx)
        CONTROL, STATUS, SENSOR, CLIPBOARD, SCHED -> true
        else -> false
    }

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

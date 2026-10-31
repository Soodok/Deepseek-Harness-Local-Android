package app.dsh.mobile.engine.cap

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject

/** Read-only device status snapshot. */
object StatusCap {
    fun ping(ctx: Context): Pair<Int, String> {
        CapabilityGate.audit(ctx, "dev.ping")
        return 200 to JSONObject()
            .put("ok", true)
            .put("service", "device-capabilities")
            .put("capabilities", CapabilityGate.snapshot(ctx))
            .toString()
    }

    fun status(ctx: Context): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.STATUS)) {
            return 403 to JSONObject().put("ok", false).put("error", "capability disabled").put("capability", CapabilityGate.STATUS).toString()
        }
        CapabilityGate.audit(ctx, "dev.status")
        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0) ?: 0.0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val audio = ctx.getSystemService(AudioManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val wifiInfo = runCatching { wifi?.connectionInfo }.getOrNull()
        val stat = StatFs(Environment.getDataDirectory().path)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val fgPkg = app.dsh.mobile.DshAccessibilityService.instance?.foregroundPackage().orEmpty()
        val foreground = JSONObject().put("package", fgPkg)
        if (fgPkg.isNotBlank()) {
            runCatching { foreground.put("label", ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(fgPkg, 0))) }
        }
        val netType = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "none"
        }
        val net = JSONObject()
            .put("type", netType)
            .put("connected", caps != null)
            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        if (netType == "wifi") {
            net.put("wifi", JSONObject()
                .put("ssid", wifiInfo?.ssid.orEmpty().trim('"'))
                .put("rssi", wifiInfo?.rssi ?: 0)
                .put("link_speed_mbps", wifiInfo?.linkSpeed ?: 0)
                .put("ip", wifiInfo?.ipAddress?.let { ip ->
                    listOf(ip and 255, ip shr 8 and 255, ip shr 16 and 255, ip shr 24 and 255).joinToString(".")
                }.orEmpty()))
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("battery", JSONObject()
                .put("level", if (scale > 0) level * 100 / scale else level)
                .put("charging", charging)
                .put("temperature", temp)
                .put("health", batteryHealth(batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0)))
            .put("screen", JSONObject()
                .put("on", ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true)
                .put("brightness", runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(0))
                .put("timeout_ms", Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 0)))
            .put("audio", JSONObject()
                .put("music_volume", audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0)
                .put("music_max", audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0)
                .put("ringer_mode", when (audio?.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                })
                .put("dnd", audio?.ringerMode == AudioManager.RINGER_MODE_SILENT))
            .put("network", net)
            .put("bluetooth", JSONObject().put("enabled", android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true).put("connected", JSONArray()))
            .put("storage", JSONObject()
                .put("internal_free_mb", stat.availableBytes / 1_000_000)
                .put("internal_total_mb", stat.totalBytes / 1_000_000))
            .put("memory", JSONObject()
                .put("avail_mb", mem.availMem / 1_000_000)
                .put("total_mb", mem.totalMem / 1_000_000))
            .put("foreground", foreground)
            .put("capabilities", CapabilityGate.snapshot(ctx))
            .toString()
    }

    private fun batteryHealth(value: Int): String = when (value) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }
}

package app.dsh.mobile.engine.cap

import android.content.ClipData
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.TextUtils
import android.net.wifi.WifiManager
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONObject

/** Controlled hardware operations and clipboard access. */
object ControlCap {
    fun clipboard(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.CLIPBOARD)) return denied(CapabilityGate.CLIPBOARD)
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to error("invalid json")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: return 503 to error("clipboard unavailable")
        return when (obj.optString("op", "read").lowercase()) {
            "read" -> {
                CapabilityGate.audit(ctx, "dev.clipboard.read")
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                200 to JSONObject().put("ok", true).put("text", text).toString()
            }
            "write" -> {
                if (!obj.has("text")) return 400 to error("field 'text' required")
                cm.setPrimaryClip(ClipData.newPlainText("DSH", obj.optString("text")))
                CapabilityGate.audit(ctx, "dev.clipboard.write")
                200 to JSONObject().put("ok", true).toString()
            }
            else -> 400 to error("op must be read|write")
        }
    }

    fun control(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to error("invalid json")
        val op = obj.optString("op").lowercase()
        // wifi / bt 各有独立开关：用户点名要的这两项能力必须单独可关。
        // 其余广播类操作（音量/亮度/手电/振动）统一受 control 开关约束。
        val gate = if (op == "wifi") CapabilityGate.WIFI else if (op == "bt") CapabilityGate.BT else CapabilityGate.CONTROL
        if (!CapabilityGate.can(ctx, gate)) return denied(gate)
        val result = when (op) {
            "wifi" -> wifi(ctx, obj)
            "bt" -> bluetooth(obj)
            "volume" -> volume(ctx, obj)
            "brightness" -> brightness(ctx, obj)
            "torch" -> torch(ctx, obj)
            "vibrate" -> vibrate(ctx, obj)
            else -> return 400 to error("op must be wifi|bt|volume|brightness|torch|vibrate")
        }
        CapabilityGate.audit(ctx, "dev.control:$op")
        return result
    }

    private fun wifi(ctx: Context, obj: JSONObject): Pair<Int, String> {
        if (obj.has("connect")) return 409 to JSONObject().put("ok", false).put("reason", "user_confirmation_required").put("hint", "Android 10+ requires WifiNetworkSpecifier confirmation").toString()
        if (!obj.has("enabled")) return 400 to error("wifi requires enabled or connect")
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            ?: return 503 to error("wifi unavailable")
        @Suppress("DEPRECATION") wm.isWifiEnabled = obj.optBoolean("enabled")
        return 200 to JSONObject().put("ok", true).put("enabled", wm.isWifiEnabled).toString()
    }

    private fun bluetooth(obj: JSONObject): Pair<Int, String> {
        if (!obj.has("enabled")) return 400 to error("bt requires enabled")
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return 404 to error("bluetooth unavailable")
        @Suppress("DEPRECATION")
        val ok = if (obj.optBoolean("enabled")) adapter.enable() else adapter.disable()
        return 200 to JSONObject().put("ok", ok).put("enabled", obj.optBoolean("enabled")).toString()
    }

    private fun volume(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val stream = when (obj.optString("stream", "music").lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return 503 to error("audio unavailable")
        val value = obj.optInt("value", -1)
        if (value < 0) return 400 to error("volume value required")
        audio.setStreamVolume(stream, value.coerceIn(0, audio.getStreamMaxVolume(stream)), 0)
        return 200 to JSONObject().put("ok", true).put("value", audio.getStreamVolume(stream)).toString()
    }

    private fun brightness(ctx: Context, obj: JSONObject): Pair<Int, String> {
        if (!Settings.System.canWrite(ctx)) return 409 to JSONObject().put("ok", false).put("reason", "write_settings_required").put("settings_action", Settings.ACTION_MANAGE_WRITE_SETTINGS).toString()
        if (!obj.has("value")) return 400 to error("brightness value required")
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, obj.optInt("value").coerceIn(1, 255))
        return 200 to JSONObject().put("ok", true).toString()
    }

    private fun torch(ctx: Context, obj: JSONObject): Pair<Int, String> {
        if (Build.VERSION.SDK_INT < 23) return 400 to error("torch unsupported")
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return 503 to error("camera unavailable")
        val id = cm.cameraIdList.firstOrNull { runCatching { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false) }
            ?: return 404 to error("torch unavailable")
        if (!obj.has("enabled")) return 400 to error("torch requires enabled")
        cm.setTorchMode(id, obj.optBoolean("enabled"))
        return 200 to JSONObject().put("ok", true).put("enabled", obj.optBoolean("enabled")).toString()
    }

    private fun vibrate(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val ms = obj.optLong("ms", 200L).coerceIn(1L, 10_000L)
        val v = ctx.getSystemService(Vibrator::class.java) ?: return 503 to error("vibrator unavailable")
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") v.vibrate(ms)
        return 200 to JSONObject().put("ok", true).put("ms", ms).toString()
    }

    private fun denied(capability: String): Pair<Int, String> = 403 to JSONObject().put("ok", false).put("error", "capability disabled").put("capability", capability).toString()
    private fun error(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}

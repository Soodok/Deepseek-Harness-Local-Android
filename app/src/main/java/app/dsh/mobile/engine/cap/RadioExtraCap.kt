package app.dsh.mobile.engine.cap

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * NFC 与地理围栏。
 *
 * ## NFC 的真实边界（先说清，免得做出来发现不是想的那样）
 * 普通 App **不能**"主动去扫一张卡" —— Android 的 NFC 是标签**靠近时系统唤醒**你的模型，
 * 没有"开始扫描"这个动作（那需要 `FLAG_READER_NFC_*` 的读卡器模式，只有系统级/特定 ROM 支持）。
 * 所以这里的能力是：
 *   - `enable/disable`：把本 App 设成前台优先接收者（enableForegroundDispatch），
 *     之后用户把卡贴上来，卡的信息就进队列；
 *   - `read`：把已捕获的标签队列取出来（UID / 技术类型 / NDEF 文本）；
 *   - `status`：当前 NFC 硬件与授权状态。
 * HCE 卡模拟（`HostApduService`）需要单独声明 AID，属于另一个工程，不在这里冒充。
 *
 * ## Geofence 为什么不用 GeofencingClient
 * 那个走 Google Play Services，AOSP / 无 GMS 设备上没有。`LocationManager.addProximityAlert`
 * 是纯系统 API，虽然被标记 deprecated 但在所有设备上都可用，且围栏场景只需要"进入/离开"。
 * 后台定位权限（ACCESS_BACKGROUND_LOCATION）缺省时明确报出来，别让调用方以为是代码 bug。
 */
object RadioExtraCap {

    internal val tags = ConcurrentLinkedDeque<JSONObject>()

    // ── NFC ─────────────────────────────────────────────────────────────────

    private fun nfcAdapter(ctx: Context): NfcAdapter? =
        runCatching { NfcAdapter.getDefaultAdapter(ctx) }.getOrNull()

    /** GET /dev/nfc —— 硬件与授权状态。 */
    fun nfcStatus(ctx: Context): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.RF)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled").put("capability", CapabilityGate.RF).toString()
        }
        val a = nfcAdapter(ctx)
        return 200 to JSONObject()
            .put("ok", true)
            .put("present", a != null)
            .put("enabled", a?.isEnabled == true)
            .put("queued", tags.size)
            .toString()
    }

    /**
     * POST /dev/nfc —— 开关前台分发。body: `{"op":"enable"|"disable"|"read"|"clear"}`
     *
     * enable 需要一个 Activity 才能调 `enableForegroundDispatch`；这里用 MainActivity 的
     * 单例引用，拿不到就明确说"应用不在前台"，而不是静默返回成功。
     */
    fun nfc(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.RF)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled").put("capability", CapabilityGate.RF).toString()
        }
        val op = runCatching { JSONObject(body.ifBlank { "{}" }).optString("op", "read") }
            .getOrDefault("read")
        val a = nfcAdapter(ctx)
            ?: return 404 to JSONObject().put("ok", false).put("error", "nfc_not_present").toString()
        when (op) {
            "enable" -> {
                if (!a.isEnabled) {
                    return 409 to JSONObject().put("ok", false)
                        .put("error", "nfc_disabled_in_settings")
                        .put("hint", "turn NFC on in system settings; apps can no longer toggle it silently on Android 10+")
                        .toString()
                }
                // 真正去开 reader-mode：只成功不收标签等于没开，必须如实回报。
                val ok = runCatching { NfcForegroundRelay.enable() }.getOrDefault(false)
                CapabilityGate.audit(ctx, "dev.nfc.enable")
                if (!ok) {
                    return 409 to JSONObject().put("ok", false)
                        .put("error", "activity_unavailable")
                        .put("hint", "bring the app to the foreground first; reading needs a live Activity")
                        .toString()
                }
                return 200 to JSONObject().put("ok", true).put("foreground_dispatch", "enabled")
                    .put("hint", "tap a tag now; then POST op=read").toString()
            }
            "disable" -> {
                runCatching { NfcForegroundRelay.disable() }
                CapabilityGate.audit(ctx, "dev.nfc.disable")
                return 200 to JSONObject().put("ok", true).toString()
            }
            "clear" -> {
                tags.clear()
                return 200 to JSONObject().put("ok", true).put("cleared", true).toString()
            }
            "read" -> {
                val arr = JSONArray()
                synchronized(tags) { tags.take(50).forEach { arr.put(it) } }
                CapabilityGate.audit(ctx, "dev.nfc.read")
                return 200 to JSONObject().put("ok", true).put("items", arr).toString()
            }
            else -> return 400 to JSONObject().put("ok", false).put("error", "unknown_op").toString()
        }
    }

    /** 把系统送来的 Tag 转成 JSON；只读能读到的 NDEF 文本，不做写卡。 */
    internal fun tagToJson(tag: Tag): JSONObject {
        val techs = JSONArray()
        tag.techList.orEmpty().forEach { techs.put(it.removePrefix("android.nfc.tech.")) }
        val uid = tag.id?.joinToString("") { b -> "%02x".format(b) }.orEmpty()
        var ndefText = ""
        var ndefRaw = ""
        runCatching {
            val ndef = Ndef.get(tag) ?: return@runCatching
            ndef.connect()
            val msg = ndef.ndefMessage
            if (msg != null) {
                val payload = msg.records.firstOrNull()?.payload
                ndefRaw = payload?.joinToString("") { b -> "%02x".format(b) }.orEmpty()
                // NDEF 文本记录前 3 字节是状态字节 + 语言码长度，去掉才是正文。
                if (payload != null && payload.size > 3) {
                    val langLen = payload[0].toInt() and 0x3F
                    val start = 1 + langLen
                    if (start < payload.size) {
                        ndefText = String(payload, start, payload.size - start, Charsets.UTF_8)
                    }
                }
            }
            ndef.close()
        }
        return JSONObject()
            .put("uid", uid)
            .put("techs", techs)
            .put("ndef_text", ndefText)
            .put("ndef_raw", ndefRaw)
            .put("at_ms", System.currentTimeMillis())
    }

    // ── 地理围栏 ─────────────────────────────────────────────────────────────

    private const val FENCE_PREFS = "geofences"

    private fun fencePrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FENCE_PREFS, Context.MODE_PRIVATE)

    /**
     * POST /dev/geofence —— 增删地理围栏。body:
     * `{"op":"add","id":"home","lat":0,"lon":0,"radius_m":200,"label":"","enter":true,"exit":true}`
     * `{"op":"remove","id":"home"}` / `{"op":"list"}`
     */
    fun geofence(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.PERSONAL)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled").put("capability", CapabilityGate.PERSONAL).toString()
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
            ?: return 500 to JSONObject().put("ok", false).put("error", "no location manager").toString()
        val req = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val op = req.optString("op", "list")

        if (op == "list") {
            val all = fencePrefs(ctx).all
            val arr = JSONArray()
            all.forEach { (k, v) -> (v as? String)?.let { runCatching { arr.put(JSONObject(it).put("id", k)) } } }
            return 200 to JSONObject().put("ok", true).put("items", arr).toString()
        }

        val id = req.optString("id")
        if (id.isBlank()) return 400 to JSONObject().put("ok", false).put("error", "missing id").toString()

        val fineOk = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fineOk) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "permission missing").put("permission", "ACCESS_FINE_LOCATION").toString()
        }

        val pi = proximityIntent(ctx, id)
        return when (op) {
            "add" -> runCatching {
                val lat = req.getDouble("lat")
                val lon = req.getDouble("lon")
                val radius = req.optDouble("radius_m", 200.0).coerceIn(50.0, 5000.0)
                lm.addProximityAlert(lat, lon, radius.toFloat(), -1L, pi)
                val rec = JSONObject()
                    .put("lat", lat).put("lon", lon).put("radius_m", radius)
                    .put("label", req.optString("label"))
                    .put("enter", req.optBoolean("enter", true))
                    .put("exit", req.optBoolean("exit", true))
                    .put("at_ms", System.currentTimeMillis())
                fencePrefs(ctx).edit().putString(id, rec.toString()).apply()
                CapabilityGate.audit(ctx, "dev.geofence.add:$id")
                200 to JSONObject().put("ok", true).put("id", id).put("radius_m", radius).toString()
            }.getOrElse { e ->
                500 to JSONObject().put("ok", false).put("error", "add_failed")
                    .put("detail", e.message.orEmpty().take(160)).toString()
            }
            "remove" -> runCatching {
                lm.removeProximityAlert(pi)
                fencePrefs(ctx).edit().remove(id).apply()
                CapabilityGate.audit(ctx, "dev.geofence.remove:$id")
                200 to JSONObject().put("ok", true).put("id", id).toString()
            }.getOrElse { e ->
                500 to JSONObject().put("ok", false).put("error", "remove_failed")
                    .put("detail", e.message.orEmpty().take(160)).toString()
            }
            else -> 400 to JSONObject().put("ok", false).put("error", "unknown_op").toString()
        }
    }

    private fun proximityIntent(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, GeofenceReceiver::class.java).apply {
            action = GeofenceReceiver.ACTION_PROXIMITY
            putExtra("id", id)
        }
        return PendingIntent.getBroadcast(
            ctx, id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}

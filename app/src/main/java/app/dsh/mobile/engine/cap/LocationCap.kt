package app.dsh.mobile.engine.cap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONObject

/**
 * 当前地理位置查询（`/dev/location`）。
 *
 * 为什么需要它：项目里原本只有 `dev/geofence`（地理围栏），那是**被动触发**——
 * 它只能在「进入/离开某个区域」时通知，AI 若要主动回答「我现在在哪」，
 * 没有任何端点可用。围栏和「此时此刻的位置」是两件不同的能力。
 *
 * 授权模型（刻意拆成独立开关 `CapabilityGate.LOCATION`）：
 *   位置上能推断出住址、公司、就医记录，所以它与 NFC/围栏的 `RF` 开关分开。
 *   用户愿意让 AI「到公司时提醒我」不代表愿意让它随时知道自己在哪。
 *
 * 精度控制：默认返回 `lat/lon` 精确到小数点后 5 位（约 1 米）。
 *   请求体给 `coarse=true` 时降为 3 位（约 100 米）—— 需要「大致在哪个城市」
 *   这类场景不该拿到精确坐标。
 *
 * ⚠️ 平台限制（实测，不是推测）：
 *   1. `getLastKnownLocation` 只是「最近一次缓存」，可能为 null 也可能过时几小时，
 *      所以返回值里必须带 `age_ms`，让调用方自己判断可信度 —— 静默返回旧坐标
 *      比返回 null 更危险。
 *   2. targetSdk=28 < 31，不需要 ACCESS_BACKGROUND_LOCATION，但**仍需**
 *      ACCESS_FINE_LOCATION 已授予，否则所有 provider 都返回 null（不报错）。
 *   3. 模拟器上通常没有真实 provider（只有 `passive`），所以要如实报
 *      `providers` 列表，缺什么说清楚。
 */
object LocationCap {

    /**
     * 一次定位结果。放在文件级而不是函数内：Kotlin 的**局部类不能是 data class**，
     * 写在 `read()` 里会在编译期报 "Modifier 'data' is not applicable to a local class"。
     * 这个坑很隐蔽 —— IDE 不提示，只有真跑编译才暴露。
     */
    private data class Fix(
        val provider: String,
        val lat: Double,
        val lon: Double,
        val acc: Float,
        val age: Long,
        val hasAlt: Boolean,
        val alt: Double,
    )

    /** 定位查询的三种失败语义，各自对应不同处置，不能混成一个 500。 */
    private const val ERR_DISABLED = "capability disabled"
    private const val ERR_NO_PERMISSION = "location permission not granted"
    private const val ERR_NO_PROVIDER = "no location provider available"
    private const val ERR_NO_FIX = "no cached fix yet (GPS may need a few seconds outdoors)"

    fun read(ctx: Context, query: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LOCATION)) {
            return 403 to err(ERR_DISABLED, "location")
        }
        val coarse = query.split('&').any { it == "coarse=true" || it == "coarse=1" }
        val mgr = ctx.getSystemService(LocationManager::class.java)
            ?: return 503 to err("location service unavailable")

        // 权限先判：没有它时 provider 全部返回 null，会造成「设备在跑但没位置」
        // 的静默失败，排查会往 provider 不存在走。
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarsePerm = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarsePerm) return 403 to err(ERR_NO_PERMISSION)

        // 列出真实存在的 provider。passive 一定在（系统合成），单独列出来没有意义，
        // 但 gps/network 缺失说明这台设备根本定不了位。
        val providers = runCatching { mgr.allProviders }.getOrDefault(emptyList())
        if (providers.isEmpty()) {
            return 503 to JSONObject()
                .put("ok", false).put("error", ERR_NO_PROVIDER)
                .put("providers", emptyList<String>())
                .toString()
        }

        // 系统位置总开关关闭时所有 provider 都不可用（不是权限问题，是用户关了开关）。
        val enabled = runCatching { mgr.isLocationEnabled }.getOrDefault(false)

        // 按精度优先级依次尝试：gps 最准但要室外，network 快但偏差大，passive 是别的
        // App 触发后留下的缓存。顺序不是随便排的 —— 优先级错会让 AI 拿到一个
        // 好几分钟前的 network 定位而以为它是精确位置。
        val order = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: Fix? = null
        for (p in order) {
            if (p !in providers) continue
            val loc = runCatching { mgr.getLastKnownLocation(p) }.getOrNull() ?: continue
            val cand = Fix(
                provider = p,
                lat = loc.latitude,
                lon = loc.longitude,
                acc = if (loc.hasAccuracy()) loc.accuracy else -1f,
                age = System.currentTimeMillis() - loc.time,
                hasAlt = loc.hasAltitude(),
                alt = if (loc.hasAltitude()) loc.altitude else 0.0,
            )
            // 先拿到的（更高优先级）就用它；但若它精度差到 10 倍以上，
            // 说明这个 provider 刚被唤醒还没收敛，换下一个更可信。
            val cur = best
            if (cur == null) {
                best = cand
            } else if (cand.acc > 0 && cur.acc > 0 && cur.acc > cand.acc * 10) {
                best = cand
            }
        }

        val fix = best
            ?: return 200 to JSONObject()
                .put("ok", true)
                .put("fix", false)
                .put("reason", ERR_NO_FIX)
                .put("providers", providers)
                .put("location_enabled", enabled)
                .put("permission", if (fine) "fine" else "coarse")
                .toString()

        CapabilityGate.audit(ctx, "dev.location:${fix.provider}")

        // 精度降级只截小数位，不做模糊网格 —— 后者会让「离我多远」这类计算失效。
        val digits = if (coarse) 3 else 5
        val scale = Math.pow(10.0, digits.toDouble())
        val lat = Math.round(fix.lat * scale) / scale
        val lon = Math.round(fix.lon * scale) / scale

        val out = JSONObject()
            .put("ok", true)
            .put("fix", true)
            .put("lat", lat)
            .put("lon", lon)
            .put("provider", fix.provider)
            .put("precision", if (coarse) "coarse" else "fine")
            .put("age_ms", fix.age)
            .put("providers", providers)
            .put("location_enabled", enabled)
            .put("permission", if (fine) "fine" else "coarse")
            .put("at", System.currentTimeMillis())
        if (fix.acc > 0) out.put("accuracy_m", fix.acc.toDouble())
        if (fix.hasAlt) out.put("altitude_m", fix.alt)
        // 采信提示：超过 5 分钟的缓存坐标不该被当成「当前位置」直接用于决策。
        out.put("stale", fix.age > 5 * 60 * 1000)
        return 200 to out.toString()
    }

    private fun err(message: String, capability: String? = null): String {
        val o = JSONObject().put("ok", false).put("error", message)
        if (capability != null) o.put("capability", capability)
        return o.toString()
    }
}

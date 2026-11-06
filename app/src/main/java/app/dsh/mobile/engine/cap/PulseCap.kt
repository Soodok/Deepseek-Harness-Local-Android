package app.dsh.mobile.engine.cap

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「不说话也能反馈」与「环境自省」两组小能力：
 *
 * - **振动摩斯电码（P2-1）**：用长短振动"说"结果。AI 在口袋里干活时，
 *   不需要点亮屏幕就能知道「成了 / 败了 / 在等」。
 * - **传感器场景推断（P2-4）**：气压计判楼层变化、光感判口袋、步频判走路。
 * - **Doze 白名单自助（P2-6）**：`dumpsys deviceidle whitelist` 让自己不被
 *   电池优化掐掉 —— 这是 HID / GATT / 录音这些长驻能力能不能活过息屏的前提。
 *
 * ## 为什么摩斯电码比提示音好
 * 声音在图书馆/课堂上不合适；振动是私密的，且**不需要看屏幕**。
 * 长短组合能编码足够多信息（一个字母 1-4 个符号），而 `VibrationEffect.createWaveform`
 * 是公开 API，A 档，无需任何权限（只需要设备有马达）。
 */
object PulseCap {

    // ───────────────────────── 振动摩斯电码 ─────────────────────────

    /** 摩斯码表（只收 A-Z 0-9 与几个常用功能码）。 */
    private val MORSE: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
        'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
        'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
        'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--",
        '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.",
    )

    /** 常用语义短码：比单个字母更好记。 */
    private val SHORTCUTS: Map<String, String> = mapOf(
        "ok" to "...",      // S = 成功
        "fail" to "..-.",   // F = 失败
        "wait" to ".--",    // W = 等待
        "done" to "-..",    // D = 完成
        "ask" to ".-",      // A = 需要你
        "sos" to "...---...",
    )

    private fun vibrator(ctx: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    /**
     * 按摩斯码振动。
     *
     * @param text 要"说"的内容；命中 [SHORTCUTS] 的用短码，否则按字母转摩斯
     * @param unitMs 基本时间单位（点=1，划=3，符号间=1，字母间=3，词间=7）
     */
    fun morse(ctx: Context, body: JSONObject): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.CONTROL)) return denied(CapabilityGate.CONTROL)
        val v = vibrator(ctx) ?: return 503 to error("no vibrator on this device")
        val raw = body.optString("text").uppercase()
        if (raw.isBlank()) return 400 to error("need text=<letters or shortcut: ok|fail|wait|done|ask|sos>")
        val unit = body.optInt("unitMs", 60).coerceIn(20, 400)

        val code = SHORTCUTS[raw.lowercase()] ?: buildString {
            for (ch in raw) {
                if (ch == ' ') {
                    append(' ')
                } else {
                    MORSE[ch]?.let { append(it).append(' ') }
                }
            }
        }.trim()
        if (code.isBlank()) {
            return 400 to JSONObject().put("ok", false)
                .put("error", "nothing encodable")
                .put("hint", "A-Z and 0-9 only, or a shortcut: ${SHORTCUTS.keys.joinToString("|")}")
                .toString()
        }

        val pattern = buildPattern(code, unit)
        val total = pattern.sum()
        return runCatching {
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            CapabilityGate.audit(ctx, "pulse.morse:$raw")
            200 to JSONObject().put("ok", true)
                .put("text", raw)
                .put("code", code)
                .put("segments", pattern.size)
                .put("duration_ms", total)
                .toString()
        }.getOrElse { e ->
            500 to error("vibrate_failed: ${e.message.orEmpty().take(140)}")
        }
    }

    /** 摩斯字符串 → 振动时长数组（on, off, on, off, ... 末位 0）。 */
    private fun buildPattern(code: String, unit: Int): LongArray {
        val segs = ArrayList<Long>()
        val tokens = code.toList()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when (t) {
                '.' -> { segs.add(unit.toLong()); segs.add(unit.toLong()) }
                '-' -> { segs.add((unit * 3).toLong()); segs.add(unit.toLong()) }
                ' ' -> {
                    // 词间隔：把上一个符号间隔扩到 7 单位
                    if (segs.isNotEmpty()) {
                        val last = segs.removeAt(segs.size - 1)
                        segs.add(last + (unit * 4).toLong())
                    }
                }
            }
            i++
        }
        // 末尾的 off 改成 0，否则会白等
        if (segs.isNotEmpty()) segs[segs.size - 1] = 0L
        return segs.toLongArray()
    }

    // ───────────────────────── 传感器场景推断 ─────────────────────────

    /**
     * 读一次传感器并推断当前场景。
     *
     * ## 为什么是"推断"不是"识别"
     * 这里只做**规则判断**，不训练模型：
     * - 光感 < 5 lux 且距离接近 0 → 大概率在口袋/包里
     * - 气压变化率 > 0.3 hPa/秒 → 在电梯里（楼层变化）
     * - 步频 1.4-2.6 Hz → 走路；> 3.5 Hz → 跑
     * - 加速度方差极小且屏幕朝下 → 平放在桌上
     *
     * 规则简单的好处是**可解释**：AI 能把这几个数字直接讲给用户听，
     * 而不是给一个没有依据的场景标签。
     */
    fun scene(ctx: Context, body: JSONObject): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.SENSOR)) return denied(CapabilityGate.SENSOR)
        val sm = ctx.getSystemService(SensorManager::class.java)
            ?: return 503 to error("sensor manager unavailable")
        val windowMs = body.optLong("windowMs", 1_500L).coerceIn(200L, 10_000L)

        val readings = JSONObject()
        val facts = JSONArray()
        val scene = LinkedHashSet<String>()

        // 光感
        readOnce(sm, Sensor.TYPE_LIGHT, windowMs)?.let { v ->
            readings.put("light_lux", (v * 10).toInt() / 10.0)
            when {
                v < 5f -> {
                    scene.add("dark")
                    facts.put("light ${v}lux -> very dark; sensor may be covered (pocket or bag)")
                }
                v > 2000f -> {
                    scene.add("outdoor")
                    facts.put("light ${v}lux -> bright; likely outdoors")
                }
                else -> Unit
            }
        }
        // 距离（口袋判断的关键：贴脸/贴布时接近 0）
        readOnce(sm, Sensor.TYPE_PROXIMITY, windowMs)?.let { v ->
            readings.put("proximity_cm", (v * 10).toInt() / 10.0)
            if (v < 1f) {
                scene.add("covered")
                facts.put("proximity ${v}cm -> sensor blocked (pocket/bag/hand over it)")
            }
            Unit
        }
        // 气压
        readOnce(sm, Sensor.TYPE_PRESSURE, windowMs)?.let { v ->
            readings.put("pressure_hpa", (v * 100).toInt() / 100.0)
            val prev = prefs(ctx).getFloat("last_pressure", -1f)
            if (prev > 0f) {
                val d = v - prev
                readings.put("pressure_delta", (d * 1000).toInt() / 1000.0)
                if (kotlin.math.abs(d) > 0.15f) {
                    scene.add("altitude_change")
                    facts.put("pressure delta ${(d * 1000).toInt() / 1000.0}hPa -> changing altitude (lift or stairs)")
                }
            }
            prefs(ctx).edit().putFloat("last_pressure", v).apply()
            Unit
        }
        // 加速度方差（静止 vs 运动）
        val accs = readSeries(sm, Sensor.TYPE_ACCELEROMETER, windowMs)
        if (accs.isNotEmpty()) {
            val mean = accs.average()
            var varSum = 0.0
            var maxAbs = 0.0
            for (a in accs) {
                val d = a - mean
                varSum += d * d
                if (kotlin.math.abs(d) > maxAbs) maxAbs = kotlin.math.abs(d)
            }
            val sd = kotlin.math.sqrt(varSum / accs.size)
            readings.put("accel_std", (sd * 1000).toInt() / 1000.0)
            readings.put("accel_samples", accs.size)
            if (sd < 0.12 && maxAbs < 0.5) {
                scene.add("still")
                facts.put("accel std ${(sd * 1000).toInt() / 1000.0} -> stationary")
            } else if (sd > 0.8) {
                scene.add("moving")
                facts.put("accel std ${(sd * 1000).toInt() / 1000.0} -> in motion")
            }
        }
        // 计步器（步频）
        val steps = readSteps(sm, windowMs)
        if (steps > 0) {
            val hz = steps * 1000.0 / windowMs
            readings.put("steps_in_window", steps)
            readings.put("step_hz", (hz * 100).toInt() / 100.0)
            when {
                hz > 3.5 -> { scene.add("running"); facts.put("step rate ${(hz * 100).toInt() / 100.0}Hz -> running") }
                hz > 1.2 -> { scene.add("walking"); facts.put("step rate ${(hz * 100).toInt() / 100.0}Hz -> walking") }
            }
        }

        if (scene.isEmpty()) {
            scene.add("unknown")
            facts.put("no discriminative sensor signal (sensors missing, or the window was too short)")
        }
        CapabilityGate.audit(ctx, "pulse.scene")
        return 200 to JSONObject()
            .put("ok", true)
            .put("scene", JSONArray(scene.toList()))
            .put("readings", readings)
            .put("facts", facts)
            .put("window_ms", windowMs)
            .put("note", "rule-based inference, not a trained classifier — every label comes with the number behind it")
            .toString()
    }

    @SuppressLint("MissingPermission")
    private fun readOnce(sm: SensorManager, type: Int, timeoutMs: Long): Float? {
        val s = sm.getDefaultSensor(type) ?: return null
        var value: Float? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                value = e.values.firstOrNull()
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        runCatching { sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI) }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        runCatching { sm.unregisterListener(l) }
        return value
    }

    private fun readSeries(sm: SensorManager, type: Int, windowMs: Long): List<Float> {
        val s = sm.getDefaultSensor(type) ?: return emptyList()
        val out = ArrayList<Float>()
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // 用三轴模长，消除方向变化带来的假运动
                val x = e.values.getOrNull(0) ?: 0f
                val y = e.values.getOrNull(1) ?: 0f
                val z = e.values.getOrNull(2) ?: 0f
                out.add(kotlin.math.sqrt(x * x + y * y + z * z))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        runCatching { sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME) }
        runCatching { Thread.sleep(windowMs) }
        runCatching { sm.unregisterListener(l) }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun readSteps(sm: SensorManager, windowMs: Long): Int {
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return 0
        var first = -1f
        var last = -1f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values.firstOrNull() ?: return
                if (first < 0f) first = v
                last = v
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        runCatching { sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI) }
        runCatching { Thread.sleep(windowMs) }
        runCatching { sm.unregisterListener(l) }
        if (first < 0f || last < 0f) return 0
        return (last - first).toInt().coerceAtLeast(0)
    }

    // ───────────────────────── Doze 白名单自助 ─────────────────────────

    /**
     * 把自己加进电池优化白名单（`dumpsys deviceidle whitelist +<pkg>`）。
     *
     * ## 为什么这条很关键
     * HID / GATT Server / 录音 / 桥常驻，全都怕息屏被 Cgroup Freezer 冻住。
     * 白名单能让系统不把本 App 打进 Doze，是这些长驻能力**活过息屏的前提**。
     *
     * 档位：多数机型上这条命令需要 root 或 shell；没有提权时会明确报失败。
     */
    fun doze(ctx: Context, body: JSONObject): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.CONTROL)) return denied(CapabilityGate.CONTROL)
        val op = body.optString("op").ifBlank { "status" }
        val pkg = ctx.packageName
        return when (op) {
            "status" -> {
                val pm = ctx.getSystemService(PowerManager::class.java)
                val ignoring = runCatching { pm?.isIgnoringBatteryOptimizations(pkg) }.getOrDefault(false)
                200 to JSONObject().put("ok", true)
                    .put("package", pkg)
                    .put("ignoring_battery_optimizations", ignoring)
                    .put("note", "true means we survive Doze; false means background work will be frozen")
                    .toString()
            }
            "add" -> {
                val out = app.dsh.mobile.engine.Privilege.runPrivileged(
                    ctx, "dumpsys deviceidle whitelist +$pkg 2>&1",
                ) ?: return 403 to error("dumpsys deviceidle needs root")
                val ok = !out.contains("Exception", ignoreCase = true) && !out.contains("Permission denial")
                val pm = ctx.getSystemService(PowerManager::class.java)
                val now = runCatching { pm?.isIgnoringBatteryOptimizations(pkg) }.getOrDefault(false)
                CapabilityGate.audit(ctx, "pulse.doze.add")
                200 to JSONObject().put("ok", true)
                    .put("command_ok", ok)
                    .put("now_ignoring", now)
                    .put("raw", out.trim().take(300))
                    .toString()
            }
            "remove" -> {
                val out = app.dsh.mobile.engine.Privilege.runPrivileged(
                    ctx, "dumpsys deviceidle whitelist -$pkg 2>&1",
                ) ?: return 403 to error("dumpsys deviceidle needs root")
                val pm = ctx.getSystemService(PowerManager::class.java)
                val now = runCatching { pm?.isIgnoringBatteryOptimizations(pkg) }.getOrDefault(false)
                CapabilityGate.audit(ctx, "pulse.doze.remove")
                200 to JSONObject().put("ok", true)
                    .put("now_ignoring", now)
                    .put("raw", out.trim().take(300))
                    .toString()
            }
            else -> 400 to error("unknown op '$op' (status|add|remove)")
        }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("pulse_cap", Context.MODE_PRIVATE)

    private fun denied(cap: String): Pair<Int, String> = 403 to JSONObject()
        .put("ok", false).put("error", "capability disabled").put("capability", cap).toString()

    private fun error(m: String): String = JSONObject().put("ok", false).put("error", m).toString()
}

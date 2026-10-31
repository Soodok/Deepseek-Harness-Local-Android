package app.dsh.mobile.engine.cap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One-shot sensor reads; listeners are always unregistered. */
object SensorCap {
    fun read(ctx: Context, query: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.SENSOR)) {
            return 403 to JSONObject().put("ok", false).put("error", "capability disabled").toString()
        }
        val typeName = query.split('&').firstOrNull { it.startsWith("type=") }
            ?.substringAfter('=')?.lowercase().orEmpty()
        val type = when (typeName) {
            "light" -> Sensor.TYPE_LIGHT
            "accel", "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyro", "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "proximity" -> Sensor.TYPE_PROXIMITY
            "step", "step_counter" -> Sensor.TYPE_STEP_COUNTER
            else -> return 400 to JSONObject().put("ok", false).put("error", "type must be light|accel|gyro|proximity|step").toString()
        }
        val manager = ctx.getSystemService(SensorManager::class.java)
            ?: return 503 to JSONObject().put("ok", false).put("error", "sensor manager unavailable").toString()
        val sensor = manager.getDefaultSensor(type)
            ?: return 404 to JSONObject().put("ok", false).put("error", "sensor unavailable").toString()
        val latch = CountDownLatch(1)
        var values: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                values = event.values.clone()
                latch.countDown()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        return try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            if (!latch.await(2, TimeUnit.SECONDS)) {
                504 to JSONObject().put("ok", false).put("error", "sensor timeout").toString()
            } else {
                CapabilityGate.audit(ctx, "dev.sensors:$typeName")
                val v = values ?: floatArrayOf()
                val out = JSONObject().put("ok", true).put("type", typeName).put("at", System.currentTimeMillis())
                if (v.size >= 3) {
                    out.put("value", JSONObject().put("x", v[0]).put("y", v[1]).put("z", v[2]))
                } else {
                    out.put("value", v.firstOrNull() ?: 0f)
                }
                out.put("unit", when (type) {
                    Sensor.TYPE_LIGHT -> "lux"
                    Sensor.TYPE_PROXIMITY -> "cm"
                    Sensor.TYPE_STEP_COUNTER -> "steps"
                    else -> "m/s²"
                })
                200 to out.toString()
            }
        } finally {
            manager.unregisterListener(listener)
        }
    }
}

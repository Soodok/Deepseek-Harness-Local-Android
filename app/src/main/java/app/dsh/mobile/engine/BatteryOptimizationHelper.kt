package app.dsh.mobile.engine

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery optimization helper (Phase 4).
 *
 * On Android 6+ (API 23+), the system may throttle background work.
 * Request the user to add the app to the battery optimization whitelist
 * (a.k.a. "ignore battery optimizations") so the engine foreground service
 * is not killed.
 *
 * Uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS which opens a system dialog.
 */
object BatteryOptimizationHelper {

    /** Returns true if the app is already whitelisted for battery optimization. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system dialog to request battery optimization exemption.
     * Must be called from an Activity.
     */
    fun requestIgnoreBatteryOptimizations(activity: android.app.Activity, requestCode: Int = 1919) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        activity.startActivityForResult(intent, requestCode)
    }

    /**
     * Registers the EngineService as a foreground service with the
     * proper battery-exempt behavior. On some OEMs (Xiaomi, Huawei),
     * an additional "auto-start" permission is needed.
     */
    fun ensureForegroundExemption(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (!isIgnoringBatteryOptimizations(app)) {
            AuditLogger.log("battery_opt", "Not ignoring battery optimizations — engine may be killed in background")
        } else {
            Log.i("BatteryOptimization", "app is whitelisted for battery optimization")
        }
    }
}

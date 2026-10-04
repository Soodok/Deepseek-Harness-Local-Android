package app.dsh.mobile.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Granular capability manager (Phase 4).
 *
 * Controls individual AI capabilities independently of the privilege mode.
 * Each capability can be toggled on/off, and the engine is gated
 * accordingly (e.g., even in Root mode, screen capture can be disabled).
 *
 * Capabilities are persisted in SharedPreferences and audited on change.
 */
enum class Capability(
    val key: String,
    val defaultEnabled: Boolean = true,
) {
    NOTIFY("cap_notify", true),
    TTS("cap_tts", true),
    SCREENCAP("cap_screencap", true),
    GESTURE("cap_gesture", true),
    SU("cap_su", false),
    SHIZUKU("cap_shizuku", false),
    STORAGE("cap_storage", true),
}

object CapabilityManager {

    private const val TAG = "CapabilityManager"
    private const val PREFS = "dsh_caps"

    private lateinit var ctx: Context

    fun init(context: Context) {
        if (!::ctx.isInitialized) {
            ctx = context.applicationContext
        }
    }

    private fun prefs(): SharedPreferences =
        if (::ctx.isInitialized) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        else throw IllegalStateException("CapabilityManager not initialized")

    fun isEnabled(cap: Capability): Boolean =
        prefs().getBoolean(cap.key, cap.defaultEnabled)

    fun setEnabled(cap: Capability, enabled: Boolean) {
        prefs().edit().putBoolean(cap.key, enabled).apply()
        AuditLogger.log("capability_changed", "${cap.name} = $enabled")
        Log.i(TAG, "capability ${cap.name} -> $enabled")
    }

    /** Returns all capabilities with their current state. */
    fun allCapabilities(): List<Pair<Capability, Boolean>> =
        Capability.values().map { it to isEnabled(it) }

    /** Check multiple capabilities at once. */
    fun checkAll(vararg caps: Capability): Boolean = caps.all { isEnabled(it) }

    /**
     * Gate a function call — only executes if the capability is enabled.
     * @return true if the action was allowed and executed.
     */
    fun gate(cap: Capability, action: () -> Unit): Boolean {
        if (!isEnabled(cap)) {
            Log.w(TAG, "blocked: capability ${cap.name} is disabled")
            return false
        }
        action()
        return true
    }
}

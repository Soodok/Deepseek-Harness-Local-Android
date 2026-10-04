package app.dsh.mobile.engine

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Connect-Phone pairing manager (Phase 1).
 *
 * Generates a short-lived numeric pairing code and a verification URL
 * that an external client can use to establish a trusted session with
 * the phone's engine.
 *
 * The pairing code is valid for 5 minutes and expires automatically.
 */
object ConnectPhoneManager {

    private const val TAG = "ConnectPhoneManager"
    private const val CODE_LENGTH = 6
    private const val CODE_TTL_MS = 5 * 60 * 1000L  // 5 minutes

    private val rng = SecureRandom()

    data class PairingInfo(
        val code: String,
        val url: String,
        val expiresAt: Long,
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt
        val remainingSec: Long get() = (expiresAt - System.currentTimeMillis()) / 1000
    }

    @Volatile private var currentPairing: PairingInfo? = null

    fun generatePairingCode(port: Int = EngineConfig.DEFAULT_PORT): PairingInfo {
        val code = (1..CODE_LENGTH).joinToString("") { (rng.nextInt(10) + '0'.code).toChar().toString() }
        val expiresAt = System.currentTimeMillis() + CODE_TTL_MS
        val url = "http://127.0.0.1:$port/pair?code=$code"
        currentPairing = PairingInfo(code, url, expiresAt)
        AuditLogger.log("connection_pair", "Pairing code generated (port=$port)")
        Log.i(TAG, "pairing code generated: $code (expires in ${CODE_TTL_MS / 1000}s)")
        return currentPairing!!
    }

    fun currentPairingCode(): PairingInfo? {
        val info = currentPairing
        return if (info != null && !info.isExpired) info else null
    }

    fun refreshPairingCode(port: Int = EngineConfig.DEFAULT_PORT): PairingInfo {
        currentPairing = null
        return generatePairingCode(port)
    }

    /** Returns the local IP address for display (e.g. 192.168.1.100). */
    fun getLocalIpAddress(): String? {
        return runCatching {
            val wifi = (DshAppProvider.get(null).applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager)
            val ipInt = wifi.connectionInfo.ipAddress
            if (ipInt == 0) null else {
                val b = IntArray(4) { 0 }
                for (i in 0..3) b[i] = (ipInt shr (i * 8)) and 0xFF
                "${b[3]}.${b[2]}.${b[1]}.${b[0]}"
            }
        }.getOrNull()
    }

    /**
     * Verifies a pairing code submitted by an external client.
     * @return true if the code matches and is not expired.
     */
    fun verifyPairingCode(code: String): Boolean {
        val info = currentPairing ?: return false
        val valid = !info.isExpired && info.code == code
        if (valid) {
            AuditLogger.log("connection_pair", "Pairing code verified successfully")
        }
        return valid
    }
}

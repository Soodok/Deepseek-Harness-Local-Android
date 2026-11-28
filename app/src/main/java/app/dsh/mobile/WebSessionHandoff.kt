package app.dsh.mobile

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONObject

/** A one-shot return to the native session in the existing, trusted engine WebView. */
internal class WebSessionHandoff(
    private val currentView: () -> WebView?,
    private val engineUrl: () -> String,
    private val onUnavailable: () -> Unit,
) {
    private class Pending(val id: String, val deadline: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val step = Runnable(::applySessionHandoff)
    private var resumed = false
    private var pending: Pending? = null
    private var inFlight: Any? = null

    fun enqueue(id: String?) {
        if (id == null || id.isBlank() || id.length > 256 || id.any { it.isISOControl() }) return
        inFlight = null
        pending = Pending(id, SystemClock.elapsedRealtime() + TIMEOUT_MS)
        schedule()
    }

    fun resume() {
        resumed = true
        schedule()
    }

    fun pause() {
        resumed = false
        inFlight = null
        handler.removeCallbacks(step)
    }

    fun documentChanged() {
        inFlight = null
        schedule()
    }

    fun save(state: Bundle) {
        pending?.let {
            state.putString(STATE_ID, it.id)
            state.putLong(STATE_DEADLINE, it.deadline)
        }
    }

    fun restore(state: Bundle?) {
        val id = state?.getString(STATE_ID) ?: return
        pending = Pending(id, state.getLong(STATE_DEADLINE))
    }

    private fun schedule(delay: Long = 0L) {
        handler.removeCallbacks(step)
        if (resumed && pending != null) handler.postDelayed(step, delay)
    }

    private fun finish(confirmed: Boolean) {
        pending = null
        inFlight = null
        handler.removeCallbacks(step)
        if (!confirmed) onUnavailable()
    }

    private fun applySessionHandoff() {
        val request = pending ?: return
        if (!resumed) return
        val remaining = request.deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) return finish(false)
        if (inFlight != null) return schedule(remaining)
        val view = currentView()
        val expected = origin(engineUrl())
        if (view == null || expected == null || origin(view.url) != expected) {
            return schedule(minOf(remaining, RETRY_MS))
        }
        val target = JSONObject.quote(request.id)
        val trustedOrigin = JSONObject.quote(expected)
        val expires = System.currentTimeMillis() + remaining
        val js = """
            (function () {
              if (location.origin !== $trustedOrigin || Date.now() >= $expires) return 'retry';
              var open = window.__dshOpenNativeSession;
              return typeof open === 'function' ? open($target) : 'retry';
            })();
        """.trimIndent()
        val attempt = Any()
        inFlight = attempt
        // If Chromium never responds, the deadline still releases the request.
        schedule(remaining)
        runCatching {
            view.evaluateJavascript(js) { raw ->
                if (inFlight !== attempt || pending !== request || currentView() !== view) return@evaluateJavascript
                inFlight = null
                if (SystemClock.elapsedRealtime() >= request.deadline) return@evaluateJavascript schedule()
                when (raw) {
                    JSONObject.quote("selected:${request.id}") -> finish(true)
                    JSONObject.quote("unavailable") -> finish(false)
                    else -> schedule(RETRY_MS)
                }
            }
        }.onFailure {
            if (inFlight === attempt) {
                inFlight = null
                schedule(RETRY_MS)
            }
        }
    }

    private fun origin(url: String?): String? = url?.let {
        val uri = Uri.parse(it)
        val host = uri.host
        if (uri.scheme == "http" && (host == "127.0.0.1" || host == "localhost") && uri.port > 0) {
            "http://$host:${uri.port}"
        } else null
    }

    private companion object {
        const val TIMEOUT_MS = 12_000L
        const val RETRY_MS = 200L
        const val STATE_ID = "native_session_handoff_id"
        const val STATE_DEADLINE = "native_session_handoff_deadline"
    }
}

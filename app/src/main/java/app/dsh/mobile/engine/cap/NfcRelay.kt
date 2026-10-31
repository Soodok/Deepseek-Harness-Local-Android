package app.dsh.mobile.engine.cap

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.os.Bundle

/**
 * NFC 前台分发的 Activity 侧胶水。
 *
 * `enableForegroundDispatch()` 只接受 Activity 实例 —— 它本质上是「告诉系统：
 * 在这个界面期间，标签事件优先给我，不要弹 App 选择器」。桥（Receiver/Service）
 * 拿不到 Activity，所以由 [app.dsh.mobile.MainActivity] 在 `onCreate`/`onResume`
 * 里注册自己，这里只保存一个弱引用，避免 Activity 泄漏。
 *
 * 设计上宁可「拿不到就明确报错」也不做静默兜底：静默会让调用方以为在等标签，
 * 实际永远收不到事件，排查成本极高。
 */
object NfcForegroundRelay {

    @Volatile
    private var host: Activity? = null

    fun attach(a: Activity) {
        host = a
    }

    fun detach(a: Activity) {
        if (host === a) host = null
    }

    /** 请求前台分发；返回是否真的发出去了（Activity 不在前台时 false）。 */
    fun enable(): Boolean {
        val a = host ?: return false
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(a) }.getOrNull() ?: return false
        if (!adapter.isEnabled) return false
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
        return runCatching {
            adapter.enableReaderMode(a, { tag ->
                runCatching { RadioExtraCap.tags.addFirst(RadioExtraCap.tagToJson(tag)) }
                while (RadioExtraCap.tags.size > 50) RadioExtraCap.tags.pollLast()
            }, flags, Bundle())
            true
        }.getOrDefault(false)
    }

    fun disable() {
        val a = host ?: return
        runCatching { NfcAdapter.getDefaultAdapter(a)?.disableReaderMode(a) }
    }
}

/**
 * 地理围栏进出的广播落点。
 *
 * `KEY_PROXIMITY_ENTERING` 是 Boolean：true = 进入，false = 离开。
 * 围栏本身没有失效时间（`addProximityAlert` 传了 -1），所以会一直有效到被 remove。
 */
class GeofenceReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: android.content.Intent) {
        if (intent.action != ACTION_PROXIMITY) return
        val id = intent.getStringExtra("id").orEmpty()
        val entering = intent.getBooleanExtra(android.location.LocationManager.KEY_PROXIMITY_ENTERING, false)
        runCatching {
            app.dsh.mobile.engine.LastAction.record(
                ctx,
                "geofence:${if (entering) "enter" else "exit"}:$id",
            )
        }
    }

    companion object {
        const val ACTION_PROXIMITY = "app.dsh.mobile.action.GEOFENCE"
    }
}

/** NFC 标签到达时的广播落点（仅在未使用 reader-mode 的兜底路径上触发）。 */
class NfcTagReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: android.content.Intent) {
        val tag = intent.getParcelableExtra<android.nfc.Tag>(NfcAdapter.EXTRA_TAG) ?: return
        runCatching {
            RadioExtraCap.tags.addFirst(RadioExtraCap.tagToJson(tag))
            while (RadioExtraCap.tags.size > 50) RadioExtraCap.tags.pollLast()
        }
    }
}

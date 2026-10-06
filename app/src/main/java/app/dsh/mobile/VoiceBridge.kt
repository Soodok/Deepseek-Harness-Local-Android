package app.dsh.mobile

import android.content.Context
import android.util.Log

/**
 * 语音输入的两个跨组件能力（v1.2.65）。
 *
 * 为什么要独立成一个 object：语音入口现在由**无障碍服务**持有（不依赖 MainActivity
 * 存活），但「把文字送进 WebUI 输入框」必须由持有 WebView 的 MainActivity 执行。
 * 两者通过这里解耦：服务侧只管识别，发送侧由 MainActivity 注册回调。
 */
object VoiceBridge {

    private const val TAG = "VoiceBridge"

    /** WebView 注入回调（由 MainActivity 注册；null = 主界面不在） */
    @Volatile private var sender: ((String) -> Unit)? = null

    /** MainActivity 注册发送能力（onCreate 时调用；onDestroy 传 null 注销） */
    fun registerSender(block: ((String) -> Unit)?) {
        sender = block
    }

    /**
     * 把识别文本发给 AI。
     *
     * 主界面不在（用户没打开 App / 被回收）时不能静默失败 —— 那正是旧版
     * 「点了没反应」的观感来源。这里给明确的悬浮窗提示。
     */
    fun send(ctx: Context, text: String) {
        val s = sender
        if (s == null) {
            Log.w(TAG, "send skipped: MainActivity not alive")
            StatusOverlay.flashNotice(ctx.getString(R.string.voice_need_app), 4_000L)
            return
        }
        runCatching { s(text) }
            .onFailure { Log.w(TAG, "send failed: ${it.message}") }
    }

    /** 是否可发送（主界面存活） */
    fun canSend(): Boolean = sender != null

}

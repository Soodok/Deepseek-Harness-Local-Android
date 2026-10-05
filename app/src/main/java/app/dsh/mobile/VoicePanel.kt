package app.dsh.mobile

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView

/**
 * 语音输入面板（v1.2.58）。
 *
 * 形态（按用户要求）：点麦克风 → 屏幕下方滑出一个悬浮框 → 实时显示识别文字
 * → 可随时编辑 → 点「发送」才提交给 AI。类似系统语音助手的交互。
 *
 * 为什么独立成面板，而不是直接填进 WebUI 输入框：
 * 语音识别必然有错字，若直接发送，AI 会立刻按错误指令开始执行（可能改文件、
 * 跑命令）。面板让用户在发送前确认与修改，这是可控性的关键。
 *
 * 窗口类型用 TYPE_ACCESSIBILITY_OVERLAY（与 StatusOverlay 同源），
 * 不需要 SYSTEM_ALERT_WINDOW 授权。
 *
 * ⚠️ 本面板**不加** FLAG_NOT_FOCUSABLE —— 因为需要接收软键盘输入。
 * （StatusOverlay 的状态条加了，两者独立。）
 */
class VoicePanel(private val ctx: Context) {

    companion object {
        private const val TAG = "VoicePanel"
    }

    private var view: View? = null
    private var input: EditText? = null
    private var hint: TextView? = null
    private val wm: WindowManager =
        ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val isVisible: Boolean get() = view != null

    /**
     * 显示面板。
     * @param onSend 点「发送」时回调（已 trim，非空）
     * @param onRetry 点「重新说」时回调（调用方应重启识别）
     * @param onClose 点「关闭」时回调
     */
    fun show(
        onSend: (String) -> Unit,
        onRetry: () -> Unit,
        onClose: () -> Unit,
    ) {
        if (view != null) {
            // 已显示 → 视为再次唤起：清空并重新聚焦
            input?.setText("")
            setHint(ctx.getString(R.string.overlay_speak_now))
            return
        }
        runCatching {
            val v = LayoutInflater.from(ctx).inflate(R.layout.voice_input_panel, null)
            val et = v.findViewById<EditText>(R.id.voiceInput)
            val hintTv = v.findViewById<TextView>(R.id.voiceHint)
            val dot = v.findViewById<View>(R.id.voiceDot)
            val send = v.findViewById<TextView>(R.id.voiceSend)
            val retry = v.findViewById<TextView>(R.id.voiceRetry)
            val close = v.findViewById<TextView>(R.id.voiceClose)

            dot.background.setTint(0xFFF28B82.toInt())   // 录音红
            hintTv.text = ctx.getString(R.string.overlay_speak_now)

            send.setOnClickListener {
                val text = et.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) onSend(text)
                hide()
            }
            retry.setOnClickListener {
                et.setText("")
                hintTv.text = ctx.getString(R.string.overlay_speak_now)
                onRetry()
            }
            close.setOnClickListener {
                hide()
                onClose()
            }

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,   // 不加 NOT_FOCUSABLE
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                y = (12 * ctx.resources.displayMetrics.density).toInt()  // 避开导航栏
            }

            wm.addView(v, p)
            view = v; input = et; hint = hintTv
            Log.i(TAG, "voice panel shown")
        }.onFailure { Log.w(TAG, "show failed: ${it.message}") }
    }

    /** 写入识别结果（中间结果实时刷新，final=true 时更新提示） */
    fun setText(text: String, final: Boolean) {
        val et = input ?: return
        et.setText(text)
        et.setSelection(et.text?.length ?: 0)
        if (final) setHint(ctx.getString(R.string.voice_recognized))
    }

    /** 更新顶部提示（如「没听清，请再说一次」） */
    fun setHint(msg: String) {
        hint?.text = msg
    }

    fun hide() {
        val v = view ?: return
        runCatching { wm.removeView(v) }
        view = null; input = null; hint = null
        Log.i(TAG, "voice panel hidden")
    }
}

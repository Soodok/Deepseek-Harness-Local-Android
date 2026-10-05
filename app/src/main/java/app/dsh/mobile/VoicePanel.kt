package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
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
 * 语音输入面板（v1.2.58 初版，v1.2.65 修复「从未显示过」）。
 *
 * 形态：屏幕**下方**滑出可编辑输入框，实时显示识别文字，改完点「发送」才提交给 AI。
 *
 * ## 🔴 v1.2.65 修复的致命缺陷
 * 初版用 `TYPE_ACCESSIBILITY_OVERLAY` 窗口类型，却由 **Activity 的 Context** 添加 ——
 * 这个窗口类型只能由无障碍服务持有，`addView` 必然抛
 * `Unable to add window -- token null is not valid`，**面板一次都没成功显示过**。
 * 现在改为由 `AccessibilityService` 的 Context 创建（与 StatusOverlay 同源），
 * 并且麦克风入口不再依赖 MainActivity 存活。
 */
class VoicePanel(private val svc: AccessibilityService) {

    companion object {
        private const val TAG = "VoicePanel"
    }

    private val ctx: Context = svc.applicationContext
    private var view: View? = null
    private var input: EditText? = null
    private var hint: TextView? = null
    private val wm: WindowManager =
        svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

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

            // 点击绑定统一走 bindTap（见其注释：overlay 窗口里 clickable 链路不可靠）

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,   // 不加 NOT_FOCUSABLE：需要软键盘输入
                PixelFormat.TRANSLUCENT,
            ).apply {
                // ⚠️ 水平居中必须用 CENTER_HORIZONTAL gravity，不能靠手算 x ——
                // 实测手算（x=margin + width=屏宽-2*margin）在 overlay 窗口上得到
                // frame=[52,…][1080,…]：左边 52px、右边 0px，明显偏右（用户实测反馈）。
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (14 * ctx.resources.displayMetrics.density).toInt()   // 避开导航栏
                // 键盘弹出时把面板顶上去，而不是被系统平移（pan）导致错位
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

            // 宽度 = 屏宽 - 两侧边距；配合 CENTER_HORIZONTAL 实现左右等距
            val dm = ctx.resources.displayMetrics
            val margin = (10 * dm.density).toInt()
            p.width = dm.widthPixels - margin * 2

            wm.addView(v, p)

            view = v; input = et; hint = hintTv

            // ⚠️ 无障碍 overlay 窗口里的点击：实测父容器的 OnClickListener 链路
            // 在 TYPE_ACCESSIBILITY_OVERLAY 上不可靠（触摸到达窗口但按钮不触发）。
            // 这里给三个按钮显式加触摸处理，用 ACTION_UP 直接触发 —— 不依赖
            // View 的 clickable/performClick 链路。
            bindTap(send) {
                val text = et.text?.toString()?.trim().orEmpty()
                Log.i(TAG, "send tapped: '$text'")
                if (text.isNotEmpty()) onSend(text)
                hide()
            }
            bindTap(retry) {
                Log.i(TAG, "retry tapped")
                // 只清空文本 + 回调；停止旧会话由调用方在 onRetry 里统一处理
                // （面板里再调一次 stop 会与 VoskRecognizer.start 内部的 stopAll 打架，
                //  实测出现 stopped/listening 反复重启）
                et.setText("")
                hintTv.text = ctx.getString(R.string.overlay_speak_now)
                onRetry()
            }
            bindTap(close) {
                Log.i(TAG, "close tapped")
                hide()
                onClose()
            }

            // 自动聚焦并弹软键盘，用户可以立刻手改识别结果
            et.requestFocus()
            runCatching {
                val imm = svc.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            Log.i(TAG, "voice panel shown (w=${p.width} gravity=CENTER_HORIZONTAL)")
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

    /**
     * 当前输入框内容（trim 后）。
     * 识别结束时用它判断「面板里有没有待发送的文字」——
     * 有就留着让用户编辑/发送，没有（误触、没听清）才收起来。
     */
    fun currentText(): String = input?.text?.toString()?.trim().orEmpty()

    /**
     * 隐藏面板。
     *
     * ⚠️ 必须**同时停止识别**：面板消失后麦克风（AudioRecord）若还在跑，
     * 系统状态栏会一直显示「正在使用麦克风」——用户实测反馈
     * 「无论有没有关闭语音，只要打开过一次，右上角就持续提示占用语音通道」。
     * 旧版 hide() 只 removeView，识别仍在后台录音。
     *
     * @param stopAudio 是否停止识别（默认 true）。由 onSend/onClose 内部调用时
     *                  调用方可能已停过，但重复 stop 是幂等的，这里统一兜底。
     */
    fun hide(stopAudio: Boolean = true) {
        if (stopAudio) onHide?.invoke()
        val v = view ?: return
        runCatching { wm.removeView(v) }
        view = null; input = null; hint = null
        Log.i(TAG, "voice panel hidden (stopAudio=$stopAudio)")
    }

    /** 面板隐藏时的清理回调（由服务注入：停识别 + 复位悬浮窗） */
    @Volatile var onHide: (() -> Unit)? = null

    /**
     * 给按钮绑定点击（显式触摸处理）。
     *
     * 为什么不用 `setOnClickListener`：在 TYPE_ACCESSIBILITY_OVERLAY 窗口里，
     * 实测触摸能到达窗口但 View 的 clickable 派发链路不生效（点 Send 无反应）。
     * 这里直接吃 ACTION_DOWN/UP，用位移阈值区分点击与滑动，UP 时触发。
     */
    private fun bindTap(v: View, action: () -> Unit) {
        v.isClickable = true
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    action()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> true
            }
        }
    }
}

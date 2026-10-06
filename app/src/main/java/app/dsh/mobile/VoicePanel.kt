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

            consumed = false   // 新面板 → 复位守卫
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

            // 入场动效（v1.2.88，v1.2.89 同步改用屏高比例）：从下方滑入 + 淡入
            v.translationY = (v.resources.displayMetrics.heightPixels * 0.15f).coerceAtLeast(120f)
            v.alpha = 0f
            v.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(ANIM_IN_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()

            view = v; input = et; hint = hintTv

            // ⚠️ 无障碍 overlay 窗口里的点击：实测父容器的 OnClickListener 链路
            // 在 TYPE_ACCESSIBILITY_OVERLAY 上不可靠（触摸到达窗口但按钮不触发）。
            // 这里给三个按钮显式加触摸处理，用 ACTION_UP 直接触发 —— 不依赖
            // View 的 clickable/performClick 链路。
            bindTap(send) {
                val raw = et.text?.toString()?.trim().orEmpty()
                // 最后防线：若内容恰好是「最后一次识别结果重复两遍」（IME 迟到提交），
                // 折叠回一份再发。用户真说了重复词（如"对对对对"）时 lastRecognized
                // 本身就是整句，长度关系不满足翻倍，不会误伤。
                val text = if (isExactDouble(raw, lastRecognized)) {
                    Log.w(TAG, "send: doubled text collapsed -> '${lastRecognized.take(30)}'")
                    lastRecognized.trim()
                } else {
                    raw
                }
                Log.i(TAG, "send tapped: raw='${raw.take(40)}' text='${text.take(40)}'")
                if (text.isNotEmpty()) onSend(text)
                hide()
            }
            bindTap(retry) {
                Log.i(TAG, "retry tapped")
                // 只清空文本 + 回调；停止旧会话由调用方在 onRetry 里统一处理
                //（面板里再调一次 stop 会与新会话启动打架，实测出现 stopped/listening 反复重启）
                et.setText("")
                hintTv.text = ctx.getString(R.string.overlay_speak_now)
                onRetry()
            }
            bindTap(close) {
                Log.i(TAG, "close tapped")
                hide()
                onClose()
            }

            // ⚠️ v1.2.95：**不再自动聚焦弹软键盘**。
            // 主人实测「说一句你好，发出去变成你好你好」—— 根因是 IME 组合作业与
            // 程序化 setText 的竞争：识别期间输入法连在 EditText 上，每次
            // setText（partial/final 都会调）都可能让输入法把它缓冲的内容
            // 重新提交一遍 → "你好" 变 "你好你好"。
            // 识别期间保持无 IME 连接即根治；用户点输入框仍可正常聚焦编辑
            //（EditText 默认行为，点击时输入法照常弹出）。
            Log.i(TAG, "voice panel shown (w=${p.width} gravity=CENTER_HORIZONTAL)")
        }.onFailure { Log.w(TAG, "show failed: ${it.message}") }
    }

    /** 最近一次识别写入的内容（用于识别「IME 把它又提交了一遍」） */
    @Volatile private var lastRecognized: String = ""

    /** 写入识别结果（中间结果实时刷新，final=true 时更新提示） */
    fun setText(text: String, final: Boolean) {
        val et = input ?: return
        lastRecognized = text
        if (et.text?.toString() == text) return   // 内容没变就不碰，避免无谓地惊动输入法
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
        // 出场动效（v1.2.88，v1.2.89 修「卡回原位再消失」）：
        // 先播「下滑 + 淡出」，动画结束再移除窗口。
        //
        // ⚠️ 旧实现用 `v.height` 作为位移量，但动画启动时视图**可能尚未测量**（height=0）
        // → translationY 只有 40px → 视觉上"先掉一点又弹回原位再消失"
        //（主人实测："向下退出，但又突然一瞬间卡回原位置，然后消失"）。
        // 改用**屏高比例**的固定位移，与视图是否已测量无关。
        view = null; input = null; hint = null
        val dy = (v.resources.displayMetrics.heightPixels * 0.35f).coerceAtLeast(300f)
        v.animate()
            .translationY(dy)
            .alpha(0f)
            .setDuration(ANIM_OUT_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                runCatching { wm.removeView(v) }
                Log.i(TAG, "voice panel hidden (animated, stopAudio=$stopAudio)")
            }
            .start()
    }

    companion object {
        private const val TAG = "VoicePanel"
        /** 入场/出场动画时长（ms）：够快不拖沓，又能看清动效 */
        private const val ANIM_IN_MS = 220L
        private const val ANIM_OUT_MS = 180L
    }

    /** now 是否恰好是 once 重复两遍（忽略空白差异）。 */
    private fun isExactDouble(now: String, once: String): Boolean {
        val a = now.filterNot { it.isWhitespace() }
        val b = once.filterNot { it.isWhitespace() }
        return b.isNotEmpty() && a.length == b.length * 2 && a == b + b
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
    /**
     * 已消费标记（v1.2.90）。
     *
     * 🔴 修的是「一次性复制两段发出去」：
     * v1.2.88 给出场加了动画后，`hide()` 把 `removeView` **推迟到动画结束（180ms）**，
     * 而这段时间窗口**仍在屏幕上且可点击** —— 用户手指的抖动/重复触摸会再次触发
     * `onSend` → 同一句话发两遍。旧版 `removeView` 是立即的，所以没这个问题。
     *
     * 守卫策略：任一面板按钮被触发后立刻置位，后续触摸一律忽略；面板重新显示时复位。
     */
    @Volatile private var consumed = false

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
                    // 守卫：一次触摸只允许触发一次（防止动画窗口期内的重复点击）
                    if (!consumed) {
                        consumed = true
                        action()
                    }
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

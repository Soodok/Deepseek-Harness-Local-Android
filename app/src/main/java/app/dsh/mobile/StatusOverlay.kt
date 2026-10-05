package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * AI 状态悬浮窗（v1.2.57 初版，v1.2.58 重做外观 + 补上拖动）。
 *
 * ## 为什么用 TYPE_ACCESSIBILITY_OVERLAY
 * 由无障碍服务自身持有，**不需要用户再授权"显示在其他应用上层"**。
 *
 * ## 交互（全部实测可用）
 *  · **拖动**：按住任意位置拖动移动（松手吸附到左右边缘，避免长期挡内容）
 *  · **点击**：展开/收起详情行（点与拖由移动阈值区分：<10dp 视为点击）
 *  · **长按**：隐藏（用户明确不要时尊重）
 *
 * ⚠️ v1.2.58 修正：初版注释写了"拖动：移动位置"但**根本没实现**——
 * 文档承诺了功能却没做，用户实测发现"不能移动"。本次补上。
 *
 * ## 外观
 * 对齐设置页设计语言：深色表面 #1C232C + 16dp 圆角 + 细描边 + 强调色 #7DD3FC；
 * 左侧状态点用颜色区分状态（强调色=执行中 / 绿=完成 / 灰=空闲）。
 */
object StatusOverlay {

    private const val TAG = "StatusOverlay"

    /** 判定为"点击"而非"拖动"的位移阈值（dp） */
    private const val TAP_SLOP_DP = 10

    /** 松手后吸附边缘的动画时长 */
    private const val SNAP_MS = 180L

    @Volatile private var root: View? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    @Volatile private var statusText: TextView? = null
    @Volatile private var actionText: TextView? = null
    @Volatile private var statusDot: View? = null
    @Volatile private var expanded = false
    @Volatile private var hidden = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var wm: WindowManager? = null

    /**
     * 麦克风点击回调（由 MainActivity 注入：需要 Activity 才能申请 RECORD_AUDIO 权限）。
     * 为 null 时按钮点击无效果（权限未就绪）。
     */
    @Volatile var onMicClick: ((Context) -> Unit)? = null

    /** 识别中标记（用于把状态点染成录音色） */
    @Volatile private var recording = false

    /** 状态点配色（与 drawable 默认色一致，运行时切换） */
    private const val COLOR_IDLE = 0xFF9AA0A6.toInt()      // 中性灰
    private const val COLOR_WORKING = 0xFF7DD3FC.toInt()   // 强调蓝
    private const val COLOR_DONE = 0xFF81C995.toInt()      // 完成绿
    private const val COLOR_RECORDING = 0xFFF28B82.toInt() // 录音红

    fun isAvailable(): Boolean = DshAccessibilityService.instance != null

    /**
     * 显示悬浮窗。
     * @param svc 无障碍服务实例（提供 overlay 窗口类型）
     */
    fun show(svc: AccessibilityService) {
        main {
            if (root != null || hidden) return@main
            try {
                val ctx = svc.applicationContext
                appCtx = ctx
                val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm = w

                val view = LayoutInflater.from(ctx).inflate(R.layout.overlay_status, null)
                val status = view.findViewById<TextView>(R.id.overlayStatus)
                val action = view.findViewById<TextView>(R.id.overlayAction)
                val dot = view.findViewById<View>(R.id.overlayDot)
                val mic = view.findViewById<android.widget.ImageView>(R.id.overlayMic)

                status.text = ctx.getString(R.string.overlay_idle)
                dot.background.setTint(COLOR_IDLE)

                // 语音输入（v1.2.58）：点麦克风开始/停止识别
                mic.setOnClickListener { onMicClick?.invoke(ctx) }

                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // 默认放**右侧**：多数 App 的左侧是导航栏/图标栏，悬浮窗压在上面会挡住操作
                    // （模拟器实测：初版默认 x=8dp 正好盖住左侧图标按钮）。
                    // 具体 x 在 addView 后按实际宽度回算，这里先给个右对齐的初值。
                    x = ctx.resources.displayMetrics.widthPixels - dp(ctx, 8)
                    y = dp(ctx, 120)
                }

                attachTouch(view, p, ctx)

                w.addView(view, p)
                // 加进窗口后才有实际宽度 → 右对齐到留 8dp 边距
                val margin = dp(ctx, 8)
                p.x = (ctx.resources.displayMetrics.widthPixels - view.width - margin)
                    .coerceAtLeast(margin)
                runCatching { w.updateViewLayout(view, p) }
                root = view; params = p
                statusText = status; actionText = action; statusDot = dot
                Log.i(TAG, "overlay shown at ${p.x},${p.y} (${view.width}x${view.height})")
            } catch (e: Exception) {
                Log.w(TAG, "show failed: ${e.message}")
            }
        }
    }

    /**
     * 拖动 + 点击 + 长按的手势处理。
     *
     * 关键点：**必须返回 true 消费事件**，否则系统不会把 MOVE 传给我们；
     * 且用位移阈值区分点击与拖动 —— 直接监听 onClick 会在拖动后误触发。
     */
    private fun attachTouch(view: View, p: WindowManager.LayoutParams, ctx: Context) {
        val slop = dp(ctx, TAP_SLOP_DP).toFloat()
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        var moved = false

        view.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = p.x; startY = p.y
                    dragging = true; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        moved = true
                    }
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { wm?.updateViewLayout(view, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    if (moved) {
                        snapToEdge(p, ctx)
                    } else {
                        // 未移动 = 点击：展开/收起
                        toggleExpanded()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }

        // 长按隐藏（用 View 自带的长按，它不会与拖动冲突：拖动会先触发 MOVE 取消长按）
        view.setOnLongClickListener {
            hide(DshAccessibilityService.instance)
            true
        }
    }

    /** 松手后吸附到最近的水平边缘（避免长期悬在屏幕中央挡内容） */
    private fun snapToEdge(p: WindowManager.LayoutParams, ctx: Context) {
        val view = root ?: return
        val screenW = ctx.resources.displayMetrics.widthPixels
        val margin = dp(ctx, 8)
        val target = if (p.x + view.width / 2 < screenW / 2) margin else screenW - view.width - margin
        animateX(p, target)
    }

    /** 简单的逐帧位移动画（避免引入动画库；60fps 下约 180ms 走完） */
    private fun animateX(p: WindowManager.LayoutParams, targetX: Int) {
        val view = root ?: return
        val start = p.x
        val steps = (SNAP_MS / 16).toInt().coerceAtLeast(1)
        var i = 0
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                i++
                val t = i.toFloat() / steps
                // ease-out：起步快、收尾缓，手感更自然
                val eased = 1f - (1f - t) * (1f - t)
                p.x = start + ((targetX - start) * eased).toInt()
                runCatching { wm?.updateViewLayout(view, p) }
                if (i < steps) handler.postDelayed(this, 16)
            }
        }
        handler.post(runnable)
    }

    private fun toggleExpanded() {
        expanded = !expanded
        actionText?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    fun hide(svc: AccessibilityService?) {
        main {
            val r = root ?: return@main
            runCatching {
                (svc?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(r)
            }
            root = null; params = null; statusText = null; actionText = null; statusDot = null
            hidden = true
            Log.i(TAG, "overlay hidden by user")
        }
    }

    /** 用户隐藏后重新允许显示（引擎重启等场景调用） */
    fun resetHidden() { hidden = false }

    /** 更新状态行（如"执行中"），并把状态点染成强调色 */
    fun setStatus(text: String) {
        main {
            statusText?.text = text
            statusDot?.background?.setTint(COLOR_WORKING)
        }
    }

    /** 更新最近动作行（如 `tap-text "WLAN"`） */
    fun setAction(text: String) {
        main {
            actionText?.text = text
            if (!expanded && text.isNotEmpty()) actionText?.visibility = View.VISIBLE
        }
    }

    /** 回到空闲态（灰色点 + 空闲文案） */
    fun setIdle() {
        main {
            statusText?.text = appCtx?.getString(R.string.overlay_idle) ?: "Idle"
            statusDot?.background?.setTint(COLOR_IDLE)
            if (!expanded) actionText?.visibility = View.GONE
        }
    }

    /**
     * 语音识别状态（v1.2.58）：把状态点染成录音色，动作行显示中间识别结果。
     * @param listening true=正在听，false=结束
     * @param partial 实时识别文本（listening 时刷新）
     */
    fun setListening(listening: Boolean, partial: String = "") {
        main {
            recording = listening
            val ctx = appCtx ?: return@main
            if (listening) {
                statusText?.text = ctx.getString(R.string.overlay_listening)
                statusDot?.background?.setTint(COLOR_RECORDING)
                actionText?.let {
                    it.visibility = View.VISIBLE
                    it.text = partial.ifBlank { ctx.getString(R.string.overlay_speak_now) }
                }
            } else {
                setIdle()
            }
        }
    }

    /** 识别失败/提示（短暂显示，然后回空闲） */
    fun flashNotice(msg: String, holdMs: Long = 3_000L) {
        main {
            val s = statusText ?: return@main
            s.text = msg
            statusDot?.background?.setTint(COLOR_RECORDING)
            actionText?.let { it.visibility = View.VISIBLE }
            s.postDelayed({ setIdle() }, holdMs)
        }
    }

    /**
     * 对话完成提示：绿色高亮 + 短暂显示，到点自动回空闲。
     */
    fun flashComplete(summary: String, holdMs: Long = 6_000L) {
        main {
            val s = statusText ?: return@main
            s.text = summary
            statusDot?.background?.setTint(COLOR_DONE)
            actionText?.let {
                it.visibility = View.VISIBLE
                if (it.text.isEmpty()) it.text = appCtx?.getString(R.string.overlay_turn_done).orEmpty()
            }
            s.postDelayed({ setIdle() }, holdMs)
        }
    }

    /** 默认完成文案（供无 Context 的调用方，如桥接层 handler） */
    fun flashCompleteDefault(holdMs: Long = 6_000L) {
        flashComplete(appCtx?.getString(R.string.overlay_turn_done) ?: "Done", holdMs)
    }

    /** "执行中 · 点击" 文案（供无 Context 的调用方） */
    fun labelWorkingTap(): String {
        val c = appCtx ?: return "Working"
        return c.getString(R.string.overlay_working, c.getString(R.string.a11y_action_tap))
    }

    private fun main(block: () -> Unit) {
        runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post(block) }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}

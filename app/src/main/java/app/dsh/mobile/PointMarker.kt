package app.dsh.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max

/**
 * 把 AI 的触屏动作画在屏幕上（v1.6.27 起点，v1.6.29 扩到三种动作）。
 *
 * ## 解决什么
 *
 * AI 在用户手机上操作，用户看不见任何东西 —— 只看到界面莫名变了。出了问题
 * 也没法追溯「它到底点了哪」。这个标记把动作画出来：
 *
 * | 动作 | 画什么 |
 * |---|---|
 * | 点击 | 一个环 + 中心点，1 秒后淡出 |
 * | 长按 | 环从外圈向里收，按多久收多久 |
 * | 滑动 | 一条轨迹线 + 沿路径移动的实心点 |
 *
 * 三种画法不同是刻意的：用户要能一眼分出「点了一下」和「从这滑到那」，
 * 而不是看到同一坨东西在动。
 *
 * ## 三条硬约束
 *
 * 1. **不能挡住触摸**。窗口必须 NOT_TOUCHABLE + NOT_FOCUSABLE，否则标记盖在
 *    按钮上会把 AI 自己的下一次点击吃掉。
 * 2. **不能拖慢动作**。画标记是旁路：调用方不等它，失败也不影响动作。
 * 3. **同一时刻只留一个**。连点时复用同一个窗口重绘，不堆一堆窗口。
 *
 * 窗口类型用 TYPE_ACCESSIBILITY_OVERLAY（无障碍服务已有权限，不必再申请悬浮窗）。
 */
object PointMarker {

    /** 环的半径（dp）。 */
    private const val RADIUS_DP = 26f

    /** 环的粗细（dp）。 */
    private const val STROKE_DP = 2.5f

    /** 长按收到底时的内圈半径（dp）。 */
    private const val HOLD_INNER_DP = 9f

    /** 滑动轨迹的线宽（dp）。 */
    private const val TRAIL_DP = 3f

    private const val COLOR = 0xFF0284C7.toInt()

    private val main = Handler(Looper.getMainLooper())

    private var view: MarkView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var hideTask: Runnable? = null

    /** 点击：在 (x,y) 闪一个环。 */
    fun show(svc: android.accessibilityservice.AccessibilityService, x: Float, y: Float, label: String = "") {
        main.post { runCatching { draw(svc, Mode.TAP, x, y, x, y, 0L) } }
    }

    /** 长按：在 (x,y) 画一个向内收的环，持续 durationMs。 */
    fun showHold(svc: android.accessibilityservice.AccessibilityService, x: Float, y: Float, durationMs: Long) {
        main.post { runCatching { draw(svc, Mode.HOLD, x, y, x, y, durationMs) } }
    }

    /**
     * 双指缩放：以 (cx,cy) 为心，两指从 ±startGap 移到 ±endGap。
     *
     * 单独一支而不是画两次 showSwipe：它们共用同一个窗口，后一次会把前一次
     * 的范围盖掉，屏幕上只剩一根指头 —— 那就不像缩放了。
     */
    fun showPinch(
        svc: android.accessibilityservice.AccessibilityService,
        cx: Float, cy: Float, startGap: Float, endGap: Float, durationMs: Long,
    ) {
        main.post { runCatching { drawPinch(svc, cx, cy, startGap, endGap, durationMs) } }
    }

    /** 滑动：从 (x1,y1) 到 (x2,y2) 画轨迹 + 移动点，持续 durationMs。 */
    fun showSwipe(
        svc: android.accessibilityservice.AccessibilityService,
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long,
    ) {
        main.post { runCatching { draw(svc, Mode.SWIPE, x1, y1, x2, y2, durationMs) } }
    }

    private fun draw(
        svc: android.accessibilityservice.AccessibilityService,
        mode: Mode,
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long,
    ) {
        // ⚠️ 必须用无障碍服务**自身**的 Context，不能用 applicationContext。
        // TYPE_ACCESSIBILITY_OVERLAY 需要服务绑定的窗口 token；
        // 用 app context 会报「Unable to add window -- token null is not valid」（实测碰到）。
        // ⚠️ 不能缓存 WindowManager！服务重启后旧 manager 上的 token 失效，
        // 表现是「刚开始能画，后来怎么都画不出来」。每次现取。
        val ctx: Context = svc
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager

        val pad = dp(ctx, RADIUS_DP)
        // 窗口要覆盖整个动作范围：滑动是一条长条，点击只是一个小方框。
        val left = (minOf(x1, x2) - pad).toInt()
        val top = (minOf(y1, y2) - pad).toInt()
        val w = (abs(x2 - x1) + pad * 2).toInt().coerceAtLeast(1)
        val h = (abs(y2 - y1) + pad * 2).toInt().coerceAtLeast(1)

        val p = params ?: WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }.also { params = it }

        p.x = left
        p.y = top
        p.width = w
        p.height = h

        val v = view ?: MarkView(ctx).also {
            view = it
            runCatching { manager.addView(it, p) }
        }
        // 视口坐标 = 屏幕坐标 - 窗口左上角
        v.configure(mode, x1 - left, y1 - top, x2 - left, y2 - top, durationMs)
        runCatching { manager.updateViewLayout(v, p) }
        v.alpha = 1f
        v.invalidate()

        hideTask?.let { main.removeCallbacks(it) }
        val task = Runnable { fadeOut() }
        hideTask = task
        main.postDelayed(task, MarkGeometry.lifeMs(durationMs))
    }

    /** 双指缩放的窗口与绘制：横向覆盖两指整个行程。 */
    private fun drawPinch(
        svc: android.accessibilityservice.AccessibilityService,
        cx: Float, cy: Float, startGap: Float, endGap: Float, durationMs: Long,
    ) {
        val ctx: Context = svc
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager

        val pad = dp(ctx, RADIUS_DP)
        val halfSpan = maxOf(startGap, endGap) + pad
        val left = (cx - halfSpan).toInt()
        val top = (cy - pad).toInt()
        val w = (halfSpan * 2).toInt().coerceAtLeast(1)
        val h = (pad * 2).toInt().coerceAtLeast(1)

        val p = params ?: WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }.also { params = it }
        p.x = left; p.y = top; p.width = w; p.height = h

        val v = view ?: MarkView(ctx).also {
            view = it
            runCatching { manager.addView(it, p) }
        }
        v.configurePinch(
            cx - left, cy - top,
            startGap, endGap, durationMs,
        )
        runCatching { manager.updateViewLayout(v, p) }
        v.alpha = 1f
        v.invalidate()

        hideTask?.let { main.removeCallbacks(it) }
        val task = Runnable { fadeOut() }
        hideTask = task
        main.postDelayed(task, MarkGeometry.lifeMs(durationMs))
    }

    private fun fadeOut() {
        val v = view ?: return
        v.animate().alpha(0f).setDuration(200L).withEndAction {
            runCatching { wm?.removeView(v) }
            view = null
            params = null
        }.start()
    }

    /** 服务断开时清干净，避免窗口泄漏。 */
    fun release() {
        main.post {
            hideTask?.let { main.removeCallbacks(it) }
            hideTask = null
            view?.let { v -> runCatching { wm?.removeView(v) } }
            view = null
            params = null
        }
    }

    private fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** 画法。 */
    enum class Mode { TAP, HOLD, SWIPE, PINCH }

    /**
     * 自绘标记。
     *
     * 用 canvas 而不是图片：不同 dpi 下不需要多套切图，颜色也能跟着主题走；
     * 更要紧的是**动画参数可调** —— 滑动的点要真的沿路径走，用静态图做不到。
     */
    private class MarkView(ctx: Context) : View(ctx) {

        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(ctx, STROKE_DP)
            color = COLOR
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR
        }
        private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(ctx, TRAIL_DP)
            strokeCap = Paint.Cap.ROUND
            color = (COLOR and 0x00FFFFFF) or 0x66000000
        }
        private val outer = dpf(ctx, RADIUS_DP)
        private val inner = dpf(ctx, HOLD_INNER_DP)
        private val coreR = dpf(ctx, 3.5f)

        private var mode = Mode.TAP
        private var x1 = 0f
        private var y1 = 0f
        private var x2 = 0f
        private var y2 = 0f
        private var durMs = 0L
        private var startAt = 0L

        /** 双指缩放：记中心与两指起始/结束间距。 */
        fun configurePinch(cx: Float, cy: Float, sg: Float, eg: Float, durationMs: Long) {
            mode = Mode.PINCH
            x1 = cx; y1 = cy
            x2 = sg; y2 = eg
            durMs = durationMs.coerceIn(0L, MarkGeometry.MAX_ACTION_MS)
            startAt = SystemClock.uptimeMillis()
        }

        fun configure(m: Mode, ax: Float, ay: Float, bx: Float, by: Float, durationMs: Long) {
            mode = m
            x1 = ax; y1 = ay; x2 = bx; y2 = by
            durMs = durationMs.coerceIn(0L, MarkGeometry.MAX_ACTION_MS)
            startAt = SystemClock.uptimeMillis()
        }

        private fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            val t = MarkGeometry.progress(SystemClock.uptimeMillis() - startAt, durMs)
            when (mode) {
                Mode.TAP -> {
                    canvas.drawCircle(x1, y1, outer - ring.strokeWidth, ring)
                    canvas.drawCircle(x1, y1, coreR, fill)
                }
                Mode.HOLD -> {
                    val r = MarkGeometry.holdRadius(outer, inner, t)
                    canvas.drawCircle(x1, y1, r, ring)
                    canvas.drawCircle(x1, y1, coreR, fill)
                }
                Mode.PINCH -> {
                    // x1,y1 = 中心；x2,y2 = 起始间距/结束间距
                    val cx = x1; val cy = y1
                    val sg = x2; val eg = y2
                    val gap = MarkGeometry.lerp(sg, eg, t)
                    canvas.drawLine(cx - sg, cy, cx - eg, cy, trail)
                    canvas.drawLine(cx + sg, cy, cx + eg, cy, trail)
                    canvas.drawCircle(cx - gap, cy, coreR * 1.4f, fill)
                    canvas.drawCircle(cx + gap, cy, coreR * 1.4f, fill)
                }
                Mode.SWIPE -> {
                    // 轨迹先画淡线：让用户看到「整条路线」，不只看到一个点在飞
                    canvas.drawLine(x1, y1, x2, y2, trail)
                    val cx = MarkGeometry.lerp(x1, x2, t)
                    val cy = MarkGeometry.lerp(y1, y2, t)
                    canvas.drawCircle(cx, cy, coreR * 1.6f, fill)
                    canvas.drawCircle(cx, cy, outer * 0.6f, ring)
                }
            }
            // 还在动就继续要帧；走完停在终点，等外层到点淡出
            if (mode != Mode.TAP && t < 1f) postInvalidateOnAnimation()
        }
    }
}
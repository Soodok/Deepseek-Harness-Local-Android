package app.dsh.mobile

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 点击位置的可见标记（v1.6.27）。
 *
 * ## 解决什么
 *
 * AI 在用户手机上点了一处，用户看不见任何东西 —— 只看到界面莫名变了。
 * 出了问题也没法追溯「它到底点了哪」。这个标记把每一次点击画在屏幕上：
 * 一圈扩散的环 + 一个中心点，1 秒后自己消失。
 *
 * ## 三条约束
 *
 * 1. **不能挡住点击**。窗口必须 NOT_TOUCHABLE + NOT_FOCUSABLE，否则标记
 *    盖在按钮上会把 AI 自己的下一次点击吃掉。
 * 2. **不能拖慢点击**。画标记是旁路：调用方不等它，失败也不影响动作。
 * 3. **同一时刻只留一个**。连点时后一个复用同一个窗口重绘，不堆一堆窗口。
 *
 * 窗口类型用 TYPE_ACCESSIBILITY_OVERLAY（无障碍服务已有权限，不必再申请悬浮窗）。
 */
object PointMarker {

    /** 标记在屏上停留的时长。太短看不清，太长会糊住界面。 */
    private const val LIFE_MS = 1_000L

    /** 环的半径（dp）。 */
    private const val RADIUS_DP = 26f

    /** 环的粗细（dp）。 */
    private const val STROKE_DP = 2.5f

    private val main = Handler(Looper.getMainLooper());

    private var view: MarkView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var hideTask: Runnable? = null

    /**
     * 在屏幕坐标处闪一个标记。
     *
     * @param label 可选说明（当前不绘制，保留给以后做「点了什么」的浮字）
     */
    fun show(svc: android.accessibilityservice.AccessibilityService, x: Float, y: Float, label: String = "") {
        main.post {
            runCatching { showOnMain(svc, x, y) }
        }
    }

    private fun showOnMain(svc: android.accessibilityservice.AccessibilityService, x: Float, y: Float) {
        // ⚠️ 必须用无障碍服务**自身**的 Context，不能用 applicationContext。
        // TYPE_ACCESSIBILITY_OVERLAY 需要服务绑定的窗口 token；
        // 用 app context 会报「Unable to add window -- token null is not valid」（实测碰到）。
        // ⚠️ 不能缓存 WindowManager！无障碍服务重启（开关拨动、进程重建、系统重新绑定）后，
        // 旧 manager 上挂的窗口 token 就失效了。实测报「token ... is not valid」，
        // 表现就是「刚开始能画，后来怎么都画不出来」。每次现取。
        val ctx: Context = svc
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager

        val size = dp(ctx, RADIUS_DP * 2);
        val half = size / 2;

        val p = params ?: WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }.also { params = it }

        // 窗口左上角 = 点击点 - 半径
        p.x = (x - half).toInt()
        p.y = (y - half).toInt()

        val v = view ?: MarkView(ctx).also {
            view = it
            runCatching { manager.addView(it, p) }
        }
        runCatching { manager.updateViewLayout(v, p) }
        v.alpha = 1f
        v.invalidate()

        hideTask?.let { main.removeCallbacks(it) }
        val task = Runnable { fadeOut() }
        hideTask = task
        main.postDelayed(task, LIFE_MS)
    }

    private fun fadeOut() {
        val v = view ?: return
        v.animate().alpha(0f).setDuration(220L).withEndAction {
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

    /**
     * 一个环。
     *
     * 用 canvas 画而不是图片：不同 dpi 下不需要多套切图，颜色也能跟着主题走。
     */
    private class MarkView(ctx: Context) : View(ctx) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(ctx, STROKE_DP)
            color = 0xFF0284C7.toInt()
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xCC0284C7.toInt()
        }
        private val r = dpf(ctx, RADIUS_DP)
        private val coreR = dpf(ctx, 3.5f)

        private fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, r - ring.strokeWidth, ring)
            canvas.drawCircle(cx, cy, coreR, core)
        }
    }

}

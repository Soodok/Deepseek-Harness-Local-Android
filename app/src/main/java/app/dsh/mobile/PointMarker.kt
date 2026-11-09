package app.dsh.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max

/**
 * 把 AI 的触屏动作画在屏幕上（v1.6.27 起点，v1.6.30 改样式）。
 *
 * ## 观感
 *
 * 照着**安卓录屏 / 开发者选项「显示点按操作反馈」**那种手感做：一颗半透明白色
 * 圆点，边缘用径向渐变糊开（就是「稍微模糊」的效果），外面一圈极淡的深色描边
 * 让它在白底界面上也看得见。不用蓝色圆环 —— 那像瞄准镜，不像手指。
 *
 * 四种动作四种画法，靠**形状与运动**区分，不靠颜色：
 *
 * | 动作 | 画什么 |
 * |---|---|
 * | 点击 | 一颗点，约 1 秒淡出 |
 * | 长按 | 点从外圈向里收，按多久收多久（收缩=一直按着） |
 * | 滑动 | 淡白轨迹 + 沿路径移动的点 |
 * | 双指缩放 | 以屏幕中心为轴，两点对称外扩/内收 |
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

    /** 点的半径（dp）。安卓自己的点按反馈约这个大小，再大就挡内容了。 */
    private const val DOT_RADIUS_DP = 17f

    /** 长按收到底时的半径（dp）。 */
    private const val HOLD_INNER_DP = 7f

    /** 滑动轨迹的线宽（dp）。 */
    private const val TRAIL_DP = 4f

    /** 主体盘（半透明白）。 */
    private const val COLOR_DISC = 0x99FFFFFF.toInt()


    /** 极淡的深色描边：白底界面上没有它，白点等于隐形。 */
    private const val COLOR_RING_LIGHT = 0x59FFFFFF.toInt()

    /** 外暗环：亮色界面上保轮廓。 */
    private const val COLOR_EDGE = 0x40000000


    /** 滑动轨迹（淡白）。 */
    private const val COLOR_TRAIL = 0x66FFFFFF

    private val main = Handler(Looper.getMainLooper())

    private var view: MarkView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var hideTask: Runnable? = null

    /** 点击：在 (x,y) 闪一个点。 */
    fun show(svc: android.accessibilityservice.AccessibilityService, x: Float, y: Float, label: String = "") {
        main.post { runCatching { draw(svc, Mode.TAP, x, y, x, y, 0L) } }
    }

    /** 长按：在 (x,y) 画一个向内收的点，持续 durationMs。 */
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

        val pad = dp(ctx, DOT_RADIUS_DP) + dp(ctx, 8f)
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
                // FLAG_LAYOUT_IN_SCREEN 不能少：没有它，窗口坐标是相对状态栏以下的
                // 内容区，p.y=800 的标记会画在 931 处（实测偏 131px，正好一个状态栏）。
                // 这种偏移只有对着截图量才发现，肉眼只会觉得「差不多在附近」。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
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

        val pad = dp(ctx, DOT_RADIUS_DP) + dp(ctx, 8f)
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
                // FLAG_LAYOUT_IN_SCREEN 不能少：没有它，窗口坐标是相对状态栏以下的
                // 内容区，p.y=800 的标记会画在 931 处（实测偏 131px，正好一个状态栏）。
                // 这种偏移只有对着截图量才发现，肉眼只会觉得「差不多在附近」。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
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
     * 用 canvas 而不是图片：不同 dpi 下不需要多套切图；更要紧的是**动画参数可调**
     * —— 滑动的点要真的沿路径走、长按的点要真的在收，静态图做不到。
     */
    private class MarkView(ctx: Context) : View(ctx) {

        // 内亮环：暗色界面上勾出圆点的边，像玻璃珠的高光边
        private val ringLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(ctx, 1.6f)
            color = COLOR_RING_LIGHT
        }
        // 外暗环：亮色界面上白点会整个糊掉，靠这一圈极淡的深色边保住轮廓
        private val ringDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(ctx, 1.0f)
            color = COLOR_EDGE
        }
        // 填充靠 shader（渐变），这里的 color 只是兜底，实际由 haloShader 决定。
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_DISC
        }
        private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(ctx, TRAIL_DP)
            strokeCap = Paint.Cap.ROUND
            color = COLOR_TRAIL
        }
        private val outer = dpf(ctx, DOT_RADIUS_DP)
        private val inner = dpf(ctx, HOLD_INNER_DP)

        /**
         * 柔光渐变的缓存。
         *
         * 键必须同时含**圆心与半径**：双指缩放一帧要画两个点，只按半径缓存的话
         * 第二个点会复用第一个点的渐变，光晕跑到隔壁去。
         */
        private var haloR = -1f
        private var haloCx = -1f
        private var haloCy = -1f
        private var haloShader: Shader? = null

        private var mode = Mode.TAP
        private var x1 = 0f
        private var y1 = 0f
        private var x2 = 0f
        private var y2 = 0f
        private var durMs = 0L
        private var startAt = 0L

        fun configure(m: Mode, ax: Float, ay: Float, bx: Float, by: Float, durationMs: Long) {
            mode = m
            x1 = ax; y1 = ay; x2 = bx; y2 = by
            durMs = durationMs.coerceIn(0L, MarkGeometry.MAX_ACTION_MS)
            startAt = SystemClock.uptimeMillis()
        }

        /** 双指缩放：记中心与两指起始/结束间距。 */
        fun configurePinch(cx: Float, cy: Float, sg: Float, eg: Float, durationMs: Long) {
            mode = Mode.PINCH
            x1 = cx; y1 = cy
            x2 = sg; y2 = eg
            durMs = durationMs.coerceIn(0L, MarkGeometry.MAX_ACTION_MS)
            startAt = SystemClock.uptimeMillis()
        }

        private fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

        /**
         * 画一颗「手指点」。
         *
         * 四层叠出来：柔光外圈（渐变糊开）→ 半透明主体 → 极淡深色描边 → 高光核心。
         * 顺序不能反：描边要在主体之上，否则被主体盖掉；核心在最上面才有玻璃感。
         */
        private fun touchDot(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            if (haloShader == null ||
                abs(r - haloR) > 0.5f ||
                abs(cx - haloCx) > 0.5f ||
                abs(cy - haloCy) > 0.5f
            ) {
                // 边缘羽化就是「稍微模糊」：82% 处才开始掉 alpha，看上去是一圈软边
                // 而不是硬描边。不做外扩光晕 —— 光晕叠在暗底上会变成一只灰盘子，
                // 那是「按钮」的样子，不是「手指点」的样子。
                haloShader = RadialGradient(
                    cx, cy, r * 1.15f,
                    intArrayOf(COLOR_DISC, COLOR_DISC, 0x00FFFFFF),
                    floatArrayOf(0f, 0.82f, 1f),
                    Shader.TileMode.CLAMP,
                )
                haloR = r
                haloCx = cx
                haloCy = cy
            }
            halo.shader = haloShader
            canvas.drawCircle(cx, cy, r * 1.15f, halo)
            // 双层描边：亮底靠外暗环保轮廓，暗底靠内亮环提层次。
            // 只用一层的话必然有一边难看 —— 深色环在暗底上像抠了个洞，
            // 亮色环在亮底上等于没画。
            canvas.drawCircle(cx, cy, r * 0.92f, ringLight)
            canvas.drawCircle(cx, cy, r * 0.99f, ringDark)
        }

        override fun onDraw(canvas: Canvas) {
            val t = MarkGeometry.progress(SystemClock.uptimeMillis() - startAt, durMs)
            when (mode) {
                Mode.TAP -> touchDot(canvas, x1, y1, outer)
                Mode.HOLD -> touchDot(canvas, x1, y1, MarkGeometry.holdRadius(outer, inner, t))
                Mode.SWIPE -> {
                    // 轨迹先画淡线：让用户看到「整条路线」，不只看到一个点在飞
                    canvas.drawLine(x1, y1, x2, y2, trail)
                    val cx = MarkGeometry.lerp(x1, x2, t)
                    val cy = MarkGeometry.lerp(y1, y2, t)
                    touchDot(canvas, cx, cy, outer * 0.92f)
                }
                Mode.PINCH -> {
                    // x1,y1 = 中心；x2,y2 = 起始间距/结束间距
                    val cx = x1; val cy = y1
                    val sg = x2; val eg = y2
                    val gap = MarkGeometry.lerp(sg, eg, t)
                    canvas.drawLine(cx - sg, cy, cx - eg, cy, trail)
                    canvas.drawLine(cx + sg, cy, cx + eg, cy, trail)
                    touchDot(canvas, cx - gap, cy, outer * 0.85f)
                    touchDot(canvas, cx + gap, cy, outer * 0.85f)
                }
            }
            // 还在动就继续要帧；走完停在终点，等外层到点淡出
            if (mode != Mode.TAP && t < 1f) postInvalidateOnAnimation()
        }
    }
}
package app.dsh.mobile.engine.cap

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import app.dsh.mobile.DshAccessibilityService
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「AI 的手」可视化（P1-4）：在屏幕上画出 AI 准备点哪里、正在滑哪里。
 *
 * ## 为什么这件小事值得做
 * 现在 AI 点屏幕是**完全不可见**的：用户只看到界面自己跳了一下，不知道是谁动的、
 * 点了哪儿、下一步要去哪。三个真实收益：
 * 1. **可解释**：用户能当场看出「AI 点错了」而不是等结果不对才怀疑；
 * 2. **可调试**：手势时序出问题时，能直接看到坐标落点是否偏了；
 * 3. **信任**：看得见的操作才敢交给它做。
 *
 * ## 实现要点
 * - 一个**全屏但不可触摸、不拦截**的 overlay（`FLAG_NOT_TOUCHABLE` +
 *   `FLAG_NOT_FOCUSABLE`），只用来画，永不吃事件。
 * - 圆点 + 脉冲环用 Canvas 手绘（不引图片资源，随尺寸自适应）。
 * - 坐标用**屏幕绝对像素**，与无障碍 `getBoundsInScreen` 同一坐标系，
 *   所以调用方把节点 bounds 中心直接丢进来就对。
 * - 一段时间无操作自动淡出（默认 2.5 秒），避免残留干扰。
 *
 * ## 与 StatusOverlay 的关系
 * 那个是「AI 在做什么」的状态球，可交互；这里是**纯视觉提示层**，不可交互。
 * 两者各用各的 WindowManager 窗口，互不影响。
 */
object HandOverlay {

    private const val TAG = "HandOverlay"

    /** 圆点半径（px）。 */
    private const val DOT_R = 18f

    /** 脉冲环最终半径（px）。 */
    private const val PULSE_R = 52f

    /** 停留多久自动消失。 */
    private const val AUTO_HIDE_MS = 2_500L

    @Volatile private var wm: WindowManager? = null
    @Volatile private var root: View? = null
    @Volatile private var canvasView: DotView? = null
    @Volatile private var shown: Boolean = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hideTask: Runnable? = null

    /** 当前画的东西：一串点（含半径与颜色）与可选路径。 */
    private data class Mark(val x: Float, val y: Float, val color: Int, val kind: String)

    private val marks = ArrayList<Mark>()

    fun control(ctx: Context, body: JSONObject): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.CONTROL)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled")
                .put("capability", CapabilityGate.CONTROL).toString()
        }
        return when (val op = body.optString("op").ifBlank { "status" }) {
            "status" -> status()
            "tap" -> tap(ctx, body)
            "path" -> path(ctx, body)
            "clear" -> clear()
            "hide" -> hide()
            "debugbg" -> debugBg(ctx, body)
            else -> 400 to JSONObject().put("ok", false)
                .put("error", "unknown op '$op' (status|tap|path|clear|hide)").toString()
        }
    }

    private fun status(): Pair<Int, String> {
        val arr = JSONArray()
        synchronized(marks) {
            marks.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("x", m.x.toInt())
                        .put("y", m.y.toInt())
                        .put("kind", m.kind),
                )
            }
        }
        val dm = root?.context?.resources?.displayMetrics
        val loc = IntArray(2)
        mainHandler.post { runCatching { root?.getLocationOnScreen(loc) } }
        Thread.sleep(60)
        return 200 to JSONObject()
            .put("ok", true)
            .put("shown", shown)
            .put("marks", arr)
            .put("auto_hide_ms", AUTO_HIDE_MS)
            // 排查坐标错位：overlay 实际尺寸 vs 屏幕物理尺寸
            .put("view_size", JSONArray().put(viewW).put(viewH))
            .put("view_origin", JSONArray().put(viewLocX).put(viewLocY))
            .put("draw_count", drawCount)
            .put("debug_bg", debugBg)
            .put("screen_size", JSONArray().put(dm?.widthPixels ?: 0).put(dm?.heightPixels ?: 0))
            .toString()
    }

    /**
     * 画一个点击点。
     *
     * @param body `{"x":123,"y":456,"kind":"tap"|"long"|"swipe","text":"可选说明"}`
     */
    private fun tap(ctx: Context, body: JSONObject): Pair<Int, String> {
        val x = body.optDouble("x", -1.0)
        val y = body.optDouble("y", -1.0)
        if (x < 0 || y < 0) {
            return 400 to JSONObject().put("ok", false)
                .put("error", "need x=<px> and y=<px> in screen coordinates")
                .put("hint", "use the center of an accessibility node's boundsInScreen").toString()
        }
        val kind = body.optString("kind").ifBlank { "tap" }
        val color = when (kind) {
            "long" -> 0xFFFF9800.toInt()   // 橙 = 长按
            "swipe" -> 0xFF2196F3.toInt()  // 蓝 = 滑动
            else -> 0xFF4CAF50.toInt()     // 绿 = 点击
        }
        synchronized(marks) {
            marks.add(Mark(x.toFloat(), y.toFloat(), color, kind))
            if (marks.size > 24) marks.removeAt(0)
        }
        return render(ctx).let { err ->
            if (err != null) 500 to JSONObject().put("ok", false).put("error", err).toString()
            else 200 to JSONObject().put("ok", true)
                .put("x", x.toInt()).put("y", y.toInt()).put("kind", kind)
                .put("auto_hide_ms", AUTO_HIDE_MS).toString()
        }
    }

    /**
     * 画一条滑动路径（起点 → 终点），并在终点画点。
     *
     * @param body `{"x1":..,"y1":..,"x2":..,"y2":..}`
     */
    private fun path(ctx: Context, body: JSONObject): Pair<Int, String> {
        val x1 = body.optDouble("x1", -1.0)
        val y1 = body.optDouble("y1", -1.0)
        val x2 = body.optDouble("x2", -1.0)
        val y2 = body.optDouble("y2", -1.0)
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            return 400 to JSONObject().put("ok", false)
                .put("error", "need x1,y1,x2,y2 in screen coordinates").toString()
        }
        synchronized(marks) {
            marks.add(Mark(x1.toFloat(), y1.toFloat(), 0xFF2196F3.toInt(), "swipe-start"))
            marks.add(Mark(x2.toFloat(), y2.toFloat(), 0xFF2196F3.toInt(), "swipe-end"))
            if (marks.size > 24) marks.removeAt(0)
        }
        return render(ctx).let { err ->
            if (err != null) 500 to JSONObject().put("ok", false).put("error", err).toString()
            else 200 to JSONObject().put("ok", true)
                .put("from", JSONArray().put(x1.toInt()).put(y1.toInt()))
                .put("to", JSONArray().put(x2.toInt()).put(y2.toInt()))
                .put("kind", "swipe").toString()
        }
    }

    /** 自检：给窗口铺半透明背景，确认窗口是否真的显示。 */
    private fun debugBg(ctx: Context, body: JSONObject): Pair<Int, String> {
        debugBg = body.optBoolean("on", false)
        if (!ensureOverlay(ctx)) {
            return 500 to JSONObject().put("ok", false).put("error", lastError.orEmpty()).toString()
        }
        mainHandler.post { canvasView?.invalidate() }
        Thread.sleep(120)
        return 200 to JSONObject().put("ok", true).put("debug_bg", debugBg).toString()
    }

    private fun clear(): Pair<Int, String> {
        synchronized(marks) { marks.clear() }
        canvasView?.invalidate()
        return 200 to JSONObject().put("ok", true).put("cleared", true).toString()
    }

    private fun hide(): Pair<Int, String> {
        mainHandler.post {
            hideTask?.let { mainHandler.removeCallbacks(it) }
            root?.let { v ->
                runCatching { wm?.removeView(v) }
            }
            root = null
            canvasView = null
            shown = false
            synchronized(marks) { marks.clear() }
        }
        return 200 to JSONObject().put("ok", true).put("hidden", true).toString()
    }

    /**
     * 确保 overlay 存在，然后重绘并重置自动消失计时。
     *
     * @return 出错时返回错误说明，成功返回 null
     */
    private fun render(ctx: Context): String? {
        if (!ensureOverlay(ctx)) return lastError
        mainHandler.post {
            runCatching {
                val lo = IntArray(2)
                root?.getLocationOnScreen(lo)
                viewLocX = lo[0]
                viewLocY = lo[1]
            }
            canvasView?.invalidate()
            hideTask?.let { mainHandler.removeCallbacks(it) }
            val t = Runnable {
                // 自动淡出：只删标记，保留窗口（下次复用，省一次 addView）
                synchronized(marks) { marks.clear() }
                canvasView?.invalidate()
            }
            hideTask = t
            mainHandler.postDelayed(t, AUTO_HIDE_MS)
        }
        return null
    }

    @Volatile private var lastError: String? = null

    /**
     * 建 overlay（幂等）。
     *
     * ⚠️ **必须在主线程做**：`View` 的构造函数内部会 `new Handler()`，
     * 在没有 `Looper` 的线程（桥的请求线程叫 `AgentBridge-req`）里会直接抛
     * `Can't create handler inside thread ... that has not called Looper.prepare()`。
     * 真机第一版就撞在这个上，报错信息还被误当成「无障碍服务没跑」。
     * 所以这里把整段丢到 mainHandler，并用 latch 等结果。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun ensureOverlay(ctx: Context): Boolean {
        if (root != null) return true
        val app = ctx.applicationContext
        var ok = false
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            ok = try {
                buildOverlay(app)
            } catch (t: Throwable) {
                lastError = "addView failed: ${t.message.orEmpty().take(200)}"
                shown = false
                false
            } finally {
                latch.countDown()
            }
        }
        runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
        return ok
    }

    /**
     * 真正建窗口（只在主线程调用）。
     *
     * ## 为什么必须借无障碍服务的 WindowManager
     * `TYPE_ACCESSIBILITY_OVERLAY` 的窗口必须挂在**无障碍服务**的 window token 上。
     * 拿 `applicationContext` 的 WindowManager 去 addView 会报
     * `Unable to add window -- token null is not valid; is your activity running?`
     * ——真机第一版就是这个错，而错误信息会误导人以为「服务没跑」。
     * 所以这里优先取 `DshAccessibilityService.instance` 的 context 与 WindowManager；
     * 服务没连上时直接明确报「无障碍服务未运行」，不再尝试劣化路径。
     */
    private fun buildOverlay(appCtx: Context): Boolean {
        val svc = DshAccessibilityService.instance
            ?: run {
                lastError = "accessibility service is not running; " +
                    "TYPE_ACCESSIBILITY_OVERLAY needs it (enable the service first)"
                return false
            }
        val svcCtx: Context = svc
        val w = svc.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: run { lastError = "window manager unavailable"; return false }
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // 关键：永不吃事件、永不抢焦点 —— 这是纯视觉层。
            // ⚠️ 不要加 FLAG_LAYOUT_NO_LIMITS / FLAG_LAYOUT_IN_SCREEN：实测加上后
            // 窗口布局原点发生偏移（请求 (600,1320) 画到了 (1061,303)），
            // 因为这两个 flag 允许窗口超出屏幕并被重新居中。保持默认原点 (0,0)。
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 0 表示不压暗后面的界面
            dimAmount = 0f
        }
        val v = DotView(svcCtx)
        return try {
            w.addView(v, lp)
            wm = w
            canvasView = v
            root = v
            shown = true
            true
        } catch (e: Throwable) {
            // 没有无障碍服务时 TYPE_ACCESSIBILITY_OVERLAY 会失败，如实说明
            lastError = "addView failed: ${e.message.orEmpty().take(160)} " +
                "(TYPE_ACCESSIBILITY_OVERLAY requires the accessibility service to be running)"
            shown = false
            false
        }
    }

    /** View 实际拿到的尺寸（排查坐标错位用：MATCH_PARENT 不一定等于屏幕）。 */
    @Volatile private var viewW: Int = 0
    @Volatile private var viewH: Int = 0
    /** View 在屏幕上的实际原点（排查坐标错位的关键：它不一定是 (0,0)）。 */
    @Volatile private var viewLocX: Int = 0
    @Volatile private var viewLocY: Int = 0
    /** onDraw 被调用了几次（排查"画了但看不见"到底是没画还是没显示）。 */
    @Volatile private var drawCount: Int = 0
    /** 是否给窗口铺了可见背景（自检用）。 */
    @Volatile private var debugBg: Boolean = false

    /** 只负责画的自定义 View。 */
    private class DotView(ctx: Context) : View(ctx) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            viewW = w
            viewH = h
        }

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawCount++
            if (debugBg) {
                // 自检：整块铺半透明红，用来确认窗口到底有没有显示在屏幕上
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
                    color = 0x40FF0000
                })
            }
            val snapshot = synchronized(marks) { marks.toList() }
            if (snapshot.isEmpty()) return

            // ⚠️ 坐标系换算（真机实测得来，别删）
            // 调用方给的是**屏幕绝对坐标**（与无障碍 boundsInScreen 同系），
            // 但 Canvas 的 (0,0) 是本 View 的左上角，而本窗口原点不一定在屏幕 (0,0) ——
            // 实测 `view_origin=[0,135]`（状态栏+挖孔高度），所以必须减掉它，
            // 否则每个点都会整体下移 135px。同时 view 高度也小于物理屏高
            // （1216x2505 vs 1216x2640），即窗口被限制在「可用区域」里。
            val ox = viewLocX.toFloat()
            val oy = viewLocY.toFloat()

            // 换算到 canvas 坐标
            val pts = snapshot.map { Mark(it.x - ox, it.y - oy, it.color, it.kind) }

            // 先画滑动路径（在点下面）
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                if (a.kind == "swipe-start" && b.kind == "swipe-end") {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0x802196F3.toInt()
                        style = Paint.Style.STROKE
                        strokeWidth = 6f
                    }
                    canvas.drawLine(a.x, a.y, b.x, b.y, p)
                    // 终点箭头（两根短线）
                    val ang = kotlin.math.atan2(b.y - a.y, b.x - a.x)
                    val len = 26f
                    for (d in intArrayOf(30, -30)) {
                        val rad = ang + Math.toRadians(d.toDouble()).toFloat()
                        canvas.drawLine(
                            b.x, b.y,
                            b.x - len * kotlin.math.cos(rad),
                            b.y - len * kotlin.math.sin(rad),
                            p,
                        )
                    }
                }
            }

            // 再画点
            pts.forEach { m ->
                // 白色外描边：深色界面与浅色界面上都能看清（第一版只有纯绿，
                // 叠在深色 UI 上实测几乎看不见）
                dotPaint.color = Color.WHITE
                dotPaint.alpha = 235
                canvas.drawCircle(m.x, m.y, DOT_R + 4f, dotPaint)

                dotPaint.color = m.color
                dotPaint.alpha = 255
                canvas.drawCircle(m.x, m.y, DOT_R, dotPaint)

                ringPaint.color = Color.WHITE
                ringPaint.alpha = 200
                canvas.drawCircle(m.x, m.y, PULSE_R, ringPaint)

                ringPaint.color = m.color
                ringPaint.alpha = 220
                canvas.drawCircle(m.x, m.y, PULSE_R - 3f, ringPaint)

                // 十字准星：让落点精确可读，而不是只有个圆
                crossPaint.color = Color.WHITE
                crossPaint.alpha = 190
                canvas.drawLine(m.x - 8f, m.y, m.x + 8f, m.y, crossPaint)
                canvas.drawLine(m.x, m.y - 8f, m.x, m.y + 8f, crossPaint)
            }
        }
    }
}

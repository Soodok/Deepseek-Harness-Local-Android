package app.dsh.mobile.pet

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 一只桌宠的绘制与手势层。
 *
 * 渲染优先级：
 *  1) 动画包里的 WebP：API 28+ AnimatedImageDrawable；API 26/27 Movie；
 *  2) 解码失败/无包：内置 Canvas 占位鲸鱼。
 *
 * 窗口移动/甩抛不在这里做：Service 持有 WindowManager.LayoutParams，
 * 本 View 只上报拖拽与松手速度。
 */
@Suppress("DEPRECATION")
class PetView(context: Context) : View(context) {

    enum class Face { IDLE, THINKING, EXPLORING, WORKING, ACK, CELEBRATE, ERROR }

    var pack: PetPackConfig? = null
        set(value) {
            field = value
            invalidate()
        }

    var face: Face = Face.IDLE
        set(value) {
            field = value
            invalidate()
        }

    var onPetClick: (() -> Unit)? = null
    var onPetLongPress: (() -> Unit)? = null
    var onDragStart: ((downRawX: Float, downRawY: Float) -> Unit)? = null
    var onDragMove: ((rawX: Float, rawY: Float) -> Unit)? = null
    var onDragEnd: ((vx: Float, vy: Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    private var longFired = false
    private var velocity: VelocityTracker? = null

    private val longPressRunnable = Runnable {
        if (!dragging) {
            longFired = true
            onPetLongPress?.invoke()
        }
    }

    // ---- 动画状态 ----
    private var frameDrawable: Drawable? = null
    private var frameMovie: Movie? = null
    private var frameSlug: String? = null
    private var frameLoop = true
    private var loadToken = 0
    private var movieStartMs = 0L
    private var paused = false
    private var squashAt = 0L
    private var usingPlaceholder = true
    private var mirrored = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!paused) {
                invalidate()
                handler.postDelayed(this, if (frameMovie != null) 33L else 66L)
            }
        }
    }

    init {
        isClickable = true
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    val isPlaceholder: Boolean get() = usingPlaceholder

    fun play(slug: String?, loop: Boolean) {
        if (slug.isNullOrBlank()) {
            clearFrame()
            usingPlaceholder = true
            return
        }
        val cfg = pack
        if (cfg == null || !cfg.isAvailable(slug)) {
            clearFrame()
            usingPlaceholder = true
            return
        }
        val file = File(PetPrefs.packWebpDir(context), "$slug.webp")
        if (!file.isFile) {
            clearFrame()
            usingPlaceholder = true
            return
        }
        loadToken++
        val token = loadToken
        val durationMs = cfg.durationOf(slug)

        if (Build.VERSION.SDK_INT >= 28) {
            decodeExecutor.execute {
                val d = runCatching { Api28.decode(file, decodeWidth(), decodeHeight()) }.getOrNull()
                post {
                    if (token != loadToken) {
                        runCatching { Api28.stop(d) }
                        return@post
                    }
                    val drawable = d
                    if (drawable == null) {
                        clearFrame()
                        usingPlaceholder = true
                        invalidate()
                        return@post
                    }
                    clearFrame()
                    frameDrawable = drawable
                    frameMovie = null
                    frameSlug = slug
                    frameLoop = loop
                    usingPlaceholder = false
                    drawable.callback = this@PetView
                    drawable.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
                    Api28.start(drawable, loop)
                    invalidate()
                    if (!loop) scheduleOneShot(token, durationMs)
                }
            }
        } else {
            decodeExecutor.execute {
                val movie = runCatching {
                    FileInputStream(file).use { Movie.decodeStream(it) }
                }.getOrNull()
                post {
                    if (token != loadToken) return@post
                    if (movie == null) {
                        clearFrame()
                        usingPlaceholder = true
                        invalidate()
                        return@post
                    }
                    clearFrame()
                    frameMovie = movie
                    frameDrawable = null
                    frameSlug = slug
                    frameLoop = loop
                    movieStartMs = SystemClock.uptimeMillis()
                    usingPlaceholder = false
                    if (!loop) scheduleOneShot(token, durationMs)
                    invalidate()
                }
            }
        }
    }

    fun playAction(slug: String) = play(slug, loop = false)

    /** 长按菜单的「翻转朝向」：只翻转绘制，不影响窗口坐标与触摸。 */
    fun flip() {
        mirrored = !mirrored
        invalidate()
    }

    /** 漫游用：直接设定朝向（true = 面朝左）。 */
    fun setMirror(m: Boolean) {
        if (mirrored != m) {
            mirrored = m
            invalidate()
        }
    }

    /** 正在播一次性动画（点击回应/菜单动作/工作状态单段）时为 true。 */
    val isBusy: Boolean
        get() = (frameDrawable != null || frameMovie != null) && !frameLoop

    fun pauseDrawing(paused: Boolean) {
        this.paused = paused
        if (paused) {
            handler.removeCallbacks(ticker)
            if (Build.VERSION.SDK_INT >= 28) Api28.stopAny(frameDrawable)
        } else {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            if (Build.VERSION.SDK_INT >= 28) Api28.startAny(frameDrawable)
            invalidate()
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        clearFrame()
        onPetClick = null
        onPetLongPress = null
        onDragStart = null
        onDragMove = null
        onDragEnd = null
    }

    private fun scheduleOneShot(token: Int, durationMs: Long) {
        handler.postDelayed({
            if (token != loadToken) return@postDelayed
            clearFrame()
            usingPlaceholder = true
            invalidate()
        }, durationMs.coerceIn(500L, 30_000L))
    }

    private fun clearFrame() {
        handler.removeCallbacks(oneShot)
        val d = frameDrawable
        frameDrawable = null
        if (Build.VERSION.SDK_INT >= 28) Api28.stopAny(d)
        d?.callback = null
        frameMovie?.setTime(0)
        frameMovie = null
        frameSlug = null
        invalidate()
    }

    private val oneShot = Runnable { clearFrame() }

    private fun decodeWidth(): Int {
        val w = width.takeIf { it > 0 } ?: (PetPrefs.DEFAULT_SIZE_DP * resources.displayMetrics.density).toInt()
        return w.coerceIn(96, 480)
    }

    private fun decodeHeight(): Int = max(54, decodeWidth() * 9 / 16)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacksAndMessages(null)
        clearFrame()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                longFired = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (!dragging) {
                    val dx = abs(event.rawX - downRawX)
                    val dy = abs(event.rawY - downRawY)
                    if (dx > slop || dy > slop) {
                        if (longFired) return true
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                        onDragStart?.invoke(downRawX, downRawY)
                    }
                }
                if (dragging) onDragMove?.invoke(event.rawX, event.rawY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                velocity?.addMovement(event)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    val vy = velocity?.yVelocity ?: 0f
                    onDragEnd?.invoke(vx, vy)
                } else if (!longFired) {
                    squash()
                    performClick()
                }
                velocity?.recycle()
                velocity = null
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging) onDragEnd?.invoke(0f, 0f)
                velocity?.recycle()
                velocity = null
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onPetClick?.invoke()
        return true
    }

    private fun squash() {
        squashAt = SystemClock.uptimeMillis()
        invalidate()
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val now = SystemClock.uptimeMillis()
        val t = ((now - squashAt).toFloat() / PetPhysics.SQUASH_MS.toFloat()).coerceIn(0f, 1f)
        val squash = if (squashAt > 0 && t < 1f) {
            1f - PetPhysics.SQUASH_AMOUNT.toFloat() * sin(t * PI.toFloat())
        } else {
            1f
        }
        canvas.save()
        canvas.scale(1f + (1f - squash) * 0.4f, squash, w / 2f, h.toFloat())
        if (mirrored) canvas.scale(-1f, 1f, w / 2f, h / 2f)

        val d = frameDrawable
        val m = frameMovie
        if (d != null) {
            d.setBounds(0, 0, w, h)
            runCatching { d.draw(canvas) }
        } else if (m != null) {
            drawMovie(canvas, m, w, h, now)
        } else {
            drawPlaceholder(canvas, w, h, now)
        }
        canvas.restore()
    }

    private fun drawMovie(canvas: Canvas, movie: Movie, w: Int, h: Int, now: Long) {
        val duration = movie.duration().coerceAtLeast(1)
        val elapsed = if (frameLoop) {
            ((now - movieStartMs) % duration).toInt()
        } else {
            ((now - movieStartMs).coerceAtMost(duration.toLong())).toInt()
        }
        movie.setTime(elapsed)
        val mw = movie.width().coerceAtLeast(1)
        val mh = movie.height().coerceAtLeast(1)
        canvas.save()
        canvas.scale(w.toFloat() / mw, h.toFloat() / mh)
        movie.draw(canvas, 0f, 0f)
        canvas.restore()
    }

    private fun drawPlaceholder(canvas: Canvas, w: Int, h: Int, now: Long) {
        val t = now / 1000.0
        val bob = sin(t * 2.0).toFloat() * h * 0.02f
        val cx = w / 2f
        val bodyW = w * 0.62f
        val bodyH = h * 0.52f
        val bodyTop = h * 0.34f + bob
        val body = RectF(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyTop + bodyH)

        // 影子
        paint.style = Paint.Style.FILL
        paint.color = 0x22000000
        rect.set(cx - bodyW * 0.32f, h * 0.88f, cx + bodyW * 0.32f, h * 0.94f)
        canvas.drawOval(rect, paint)

        // 尾巴
        paint.color = bodyColor()
        path.reset()
        path.moveTo(body.left + bodyW * 0.08f, body.centerY())
        path.lineTo(body.left - bodyW * 0.22f, body.centerY() - bodyH * 0.26f)
        path.lineTo(body.left - bodyW * 0.18f, body.centerY() + bodyH * 0.26f)
        path.close()
        canvas.drawPath(path, paint)

        // 身体
        canvas.drawOval(body, paint)

        // 肚皮
        paint.color = 0x66FFFFFF
        rect.set(cx - bodyW * 0.22f, bodyTop + bodyH * 0.38f, cx + bodyW * 0.22f, bodyTop + bodyH * 0.92f)
        canvas.drawOval(rect, paint)

        // 眼睛
        val eyeY = bodyTop + bodyH * 0.38f
        val eyeDx = bodyW * 0.16f
        paint.color = Color.WHITE
        canvas.drawCircle(cx - eyeDx, eyeY, bodyH * 0.11f, paint)
        canvas.drawCircle(cx + eyeDx, eyeY, bodyH * 0.11f, paint)
        paint.color = 0xFF16223B.toInt()
        val look = when (face) {
            Face.THINKING -> -bodyH * 0.03f
            Face.ERROR -> bodyH * 0.04f
            else -> 0f
        }
        canvas.drawCircle(cx - eyeDx, eyeY + look, bodyH * 0.055f, paint)
        canvas.drawCircle(cx + eyeDx, eyeY + look, bodyH * 0.055f, paint)

        // 嘴
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1.5f, h * 0.01f)
        paint.color = 0xFF16223B.toInt()
        path.reset()
        when (face) {
            Face.CELEBRATE, Face.ACK -> {
                path.addArc(
                    RectF(cx - bodyW * 0.09f, eyeY + bodyH * 0.16f, cx + bodyW * 0.09f, eyeY + bodyH * 0.34f),
                    10f, 160f,
                )
            }
            Face.ERROR -> {
                path.moveTo(cx - bodyW * 0.07f, eyeY + bodyH * 0.2f)
                path.quadTo(cx, eyeY + bodyH * 0.14f, cx + bodyW * 0.07f, eyeY + bodyH * 0.2f)
            }
            else -> {
                path.moveTo(cx - bodyW * 0.05f, eyeY + bodyH * 0.2f)
                path.quadTo(cx, eyeY + bodyH * 0.25f, cx + bodyW * 0.05f, eyeY + bodyH * 0.2f)
            }
        }
        canvas.drawPath(path, paint)

        // 状态装饰
        paint.style = Paint.Style.FILL
        when (face) {
            Face.THINKING -> {
                paint.color = 0xFF7DD3FC.toInt()
                for (i in 0 until 3) {
                    val alpha = 90 + ((sin(t * 3.0 + i) + 1) * 60).toInt()
                    paint.alpha = alpha.coerceIn(0, 255)
                    canvas.drawCircle(cx + bodyW * 0.42f + i * bodyW * 0.08f, bodyTop - h * 0.03f, h * 0.018f, paint)
                }
                paint.alpha = 255
            }
            Face.WORKING, Face.EXPLORING -> {
                paint.color = 0x887DD3FC.toInt()
                paint.strokeWidth = max(1.5f, h * 0.01f)
                paint.style = Paint.Style.STROKE
                for (i in 0 until 3) {
                    val y = bodyTop + bodyH * (0.3f + i * 0.2f)
                    canvas.drawLine(body.right + bodyW * 0.06f, y, body.right + bodyW * 0.18f, y, paint)
                }
                paint.style = Paint.Style.FILL
            }
            Face.CELEBRATE -> {
                paint.color = 0xFFFFD166.toInt()
                for (i in 0 until 5) {
                    val a = t * 2.0 + i * 1.2
                    val x = cx + sin(a).toFloat() * bodyW * 0.55f
                    val y = bodyTop - h * 0.08f + sin(a * 1.7).toFloat() * h * 0.06f
                    canvas.drawCircle(x, y, h * 0.014f, paint)
                }
            }
            else -> Unit
        }

        // 名称角标（很小，不抢画面）
        paint.color = 0x99FFFFFF.toInt()
        paint.textSize = h * 0.055f
        paint.textAlign = Paint.Align.CENTER
        val label = pack?.spec?.name ?: "DSH"
        canvas.drawText(label.take(10), cx, h * 0.98f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun bodyColor(): Int = when (face) {
        Face.IDLE -> 0xFF5B8DEF.toInt()
        Face.THINKING -> 0xFF6C7BF5.toInt()
        Face.EXPLORING -> 0xFF4FB6E8.toInt()
        Face.WORKING -> 0xFF4C8DFF.toInt()
        Face.ACK -> 0xFF63C7A8.toInt()
        Face.CELEBRATE -> 0xFFF0B44A.toInt()
        Face.ERROR -> 0xFFB06A6A.toInt()
    }

    companion object {
        private const val LONG_PRESS_MS = 450L
        private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "dsh-pet-decode").apply { isDaemon = true }
        }
    }

    /**
     * API 28+ 的 WebP 动画解码单独放一个对象里，避免 minSdk 26 的类验证在
     * 低版本设备上碰到 AnimatedImageDrawable/ImageDecoder。
     */
    @TargetApi(28)
    private object Api28 {
        fun decode(file: File, targetW: Int, targetH: Int): AnimatedImageDrawable? {
            val source = ImageDecoder.createSource(file)
            val decoded = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                decoder.setTargetSize(targetW, targetH)
            }
            return decoded as? AnimatedImageDrawable
        }

        fun start(drawable: AnimatedImageDrawable, loop: Boolean) {
            drawable.repeatCount = if (loop) AnimatedImageDrawable.REPEAT_INFINITE else 1
            drawable.start()
        }

        fun stop(drawable: AnimatedImageDrawable?) {
            runCatching { drawable?.stop() }
        }

        fun stopAny(drawable: Drawable?) {
            runCatching { (drawable as? AnimatedImageDrawable)?.stop() }
        }

        fun startAny(drawable: Drawable?) {
            runCatching { (drawable as? AnimatedImageDrawable)?.start() }
        }
    }
}

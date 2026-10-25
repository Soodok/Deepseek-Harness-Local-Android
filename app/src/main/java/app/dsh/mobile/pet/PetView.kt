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
 *  2) 无资源包时 Service 不会挂载本 View——不再画任何占位图形。
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

    /** 当前资源包的 webp 目录，由 Service 按 pack id 传入；无包时应不挂载本 View。 */
    var webpDir: File? = null

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

    /**
     * 一次性动画播到结尾时回调（循环动画不触发）。
     *
     * 画面会**停在最后一帧**而不是被清空 —— 交给动画链去接上下一段，
     * 中间即便有解码延迟也不会闪出黑屏。
     */
    var onAnimationEnd: (() -> Unit)? = null

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
    /** 帧资源上屏时刻：elapsedMs / 位移插值的时间基准。 */
    private var frameStartMs = 0L
    /** 一次性动画是否已走到结尾（避免重复回调）。 */
    private var oneShotFired = false
    private var paused = false
    private var squashAt = 0L
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

    fun play(slug: String?, loop: Boolean) {
        clearFrame()
        val cfg = pack
        val dir = webpDir
        if (slug.isNullOrBlank() || cfg == null || dir == null) return
        if (!cfg.isAvailable(slug)) return
        val file = File(dir, "$slug.webp")
        if (!file.isFile) return
        loadToken++
        val token = loadToken
        oneShotFired = false
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
                        invalidate()
                        return@post
                    }
                    clearFrame()
                    frameDrawable = drawable
                    frameMovie = null
                    frameSlug = slug
                    frameLoop = loop
                    frameStartMs = SystemClock.uptimeMillis()
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
                        invalidate()
                        return@post
                    }
                    clearFrame()
                    frameMovie = movie
                    frameDrawable = null
                    frameSlug = slug
                    frameLoop = loop
                    movieStartMs = SystemClock.uptimeMillis()
                    frameStartMs = movieStartMs
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

    /**
     * 当前动画已播时长（ms），给位移的时间轴插值用。
     *
     * 桌面版用 video 的 currentTime 做插值基准（而非墙钟），保证动画与位移不脱节；
     * 这里同理：以帧资源真正上屏的时刻为起点。
     */
    fun elapsedMs(): Long {
        if (frameDrawable == null && frameMovie == null) return 0L
        return (SystemClock.uptimeMillis() - frameStartMs).coerceAtLeast(0L)
    }

    private fun scheduleOneShot(token: Int, durationMs: Long) {
        handler.postDelayed({
            if (token != loadToken) return@postDelayed
            oneShotFired = true
            onAnimationEnd?.invoke()
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

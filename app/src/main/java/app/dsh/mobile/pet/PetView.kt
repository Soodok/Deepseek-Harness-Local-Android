package app.dsh.mobile.pet

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
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
 * 播放采用**双缓冲 + 交叉淡入**（对应桌面版两个 <video> 叠放 + CSS opacity 过渡）：
 *  解码好的新动画进 front 槽，透明度 0→1 淡入盖住旧画面；旧画面退到 back 槽，
 *  在整个切换期间保持不动（不再 clearFrame），因此不会出现"先清空、再等解码"的黑帧。
 *  解码产物进 [LruCache]，同一段再次被选中时可立即上屏。
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

    // ---- 双缓冲播放状态 ----

    /** 一个播放槽：前台是当前正显示的，后台是正在淡入的新的一段。 */
    private class Slot {
        var drawable: Drawable? = null
        var movie: Movie? = null
        var slug: String? = null
        var loop: Boolean = true
        /** 本段资源上屏的时刻，位移插值与 start 的时间基准。 */
        var startMs: Long = 0L
        /** 淡入进度 0~1（160~180ms，与桌面版 CSS .18s ease 同量级）。 */
        var alpha: Float = 1f

        val isEmpty: Boolean get() = drawable == null && movie == null
    }

    private var front = Slot()
    private var back = Slot()
    private var frameLoop = true
    private var fadeStartMs = 0L
    /** 待触发的「一次性动画播完」回调（换段/清画面时要能取消）。 */
    private var pendingOneShot: Runnable? = null
    private var loadToken = 0
    private var paused = false
    private var squashAt = 0L
    private var mirrored = false

    /**
     * 解码产物池。取用时即把条目从池中移除（同一个 drawable 不能被两个槽同时持有），
     * 被 LRU 淘汰或换出时负责停掉播放。
     */
    private val drawablePool = object : LruCache<String, Drawable>(CACHE_SLOTS) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Drawable,
            newValue: Drawable?,
        ) {
            Api28.stopAny(oldValue)
            oldValue.callback = null
        }
    }

    private val moviePool = object : LruCache<String, Movie>(CACHE_SLOTS) {}

    private val ticker = object : Runnable {
        override fun run() {
            if (paused) return
            stepFade()
            invalidate()
            // AnimatedImageDrawable 自己会通过 callback 驱动重绘，只有淡入过渡和
            // Movie（API 26/27）才需要外部步进。常驻 60fps 空转对悬浮窗是纯耗电。
            if (needsTicker()) handler.postDelayed(this, if (front.movie != null) 33L else 16L)
        }
    }

    private fun needsTicker(): Boolean = front.alpha < 1f || front.movie != null

    init {
        isClickable = true
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun play(slug: String?, loop: Boolean) {
        val cfg = pack
        val dir = webpDir
        if (slug.isNullOrBlank() || cfg == null || dir == null) {
            retireAll()
            return
        }
        if (!cfg.isAvailable(slug)) return
        val file = File(dir, "$slug.webp")
        if (!file.isFile) return

        loadToken++
        val token = loadToken
        val durationMs = cfg.durationOf(slug)
        frameLoop = loop

        val pooled28 = Build.VERSION.SDK_INT >= 28 && drawablePool.get(slug) != null
        val pooledMovie = Build.VERSION.SDK_INT < 28 && moviePool.get(slug) != null

        fun land(target: Slot) {
            target.slug = slug
            target.loop = loop
            target.startMs = SystemClock.uptimeMillis()
            beginCrossFade()
            if (!loop && target === front) scheduleOneShot(token, durationMs)
        }

        if (Build.VERSION.SDK_INT >= 28) {
            if (pooled28) {
                val d = drawablePool.remove(slug)
                if (d != null) {
                    prepareDrawable(d, loop)
                    recycleSlot(back)
                    // 前台先让位到后台：旧画面留在下面，新画面从上面淡入
                    back.drawable = front.drawable
                    back.movie = front.movie
                    back.slug = front.slug
                    back.loop = front.loop
                    back.startMs = front.startMs
                    back.alpha = 1f
                    front.drawable = d
                    front.movie = null
                    land(front)
                    return
                }
            }
            decodeExecutor.execute {
                val d = runCatching {
                    Api28.decode(file, decodeMaxWidth())
                }.getOrNull()
                post {
                    if (token != loadToken) {
                        Api28.stop(d)
                        return@post
                    }
                    if (d == null) {
                        retireAll()
                        return@post
                    }
                    prepareDrawable(d, loop)
                    swapToFront(d, null, slug, loop)
                    land(front)
                }
            }
        } else {
            if (pooledMovie) {
                val m = moviePool.remove(slug)
                if (m != null) {
                    swapToFront(null, m, slug, loop)
                    land(front)
                    return
                }
            }
            decodeExecutor.execute {
                val movie = runCatching {
                    FileInputStream(file).use { Movie.decodeStream(it) }
                }.getOrNull()
                post {
                    if (token != loadToken) return@post
                    if (movie == null) {
                        retireAll()
                        return@post
                    }
                    swapToFront(null, movie, slug, loop)
                    land(front)
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

    /** 漫游/动画链用：直接设定朝向（true = 水平镜像）。 */
    fun setMirror(m: Boolean) {
        if (mirrored != m) {
            mirrored = m
            invalidate()
        }
    }

    /** 正在播一次性动画（点击回应/菜单动作/工作状态单段）时为 true。 */
    val isBusy: Boolean
        get() = !front.isEmpty && !frameLoop

    /** 换包/换资源：清空池和两个槽，避免残留旧包的 drawable。 */
    fun resetPlayback() {
        loadToken++
        retireAll()
        drawablePool.evictAll()
        moviePool.evictAll()
    }

    fun pauseDrawing(paused: Boolean) {
        this.paused = paused
        if (paused) {
            handler.removeCallbacks(ticker)
            Api28.stopAny(front.drawable)
            Api28.stopAny(back.drawable)
        } else {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            // 恢复时把当前动画重新 drag 起来；Movie 靠 startMs 自然续播
            Api28.restart(front.drawable, front.loop)
            invalidate()
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        retireAll()
        drawablePool.evictAll()
        moviePool.evictAll()
        onPetClick = null
        onPetLongPress = null
        onDragStart = null
        onDragMove = null
        onDragEnd = null
        onAnimationEnd = null
    }

    // ---------------------------------------------------------------- 播放内部

    private fun prepareDrawable(d: Drawable, loop: Boolean) {
        d.callback = this@PetView
        d.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        Api28.restart(d, loop)
    }

    /**
     * 新资源上位：旧前台退到后台继续显示（alpha=1），新资源进前台从 0 淡入。
     * 淡入结束后 [stepFade] 会把后台资源归还池。
     */
    private fun swapToFront(drawable: Drawable?, movie: Movie?, slug: String, loop: Boolean) {
        // 后台若还有未淡完的帧，先回收它，避免堆叠
        recycleSlot(back)
        back.drawable = front.drawable
        back.movie = front.movie
        back.slug = front.slug
        back.loop = front.loop
        back.startMs = front.startMs
        back.alpha = 1f
        front.drawable = drawable
        front.movie = movie
        front.slug = slug
        front.loop = loop
    }

    private fun beginCrossFade() {
        front.alpha = 0f
        fadeStartMs = SystemClock.uptimeMillis()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** 推进淡入；完成后把后台残留帧归还解码池并停掉它的播放。 */
    private fun stepFade() {
        if (front.alpha >= 1f) return
        val p = (SystemClock.uptimeMillis() - fadeStartMs).toFloat() / CROSSFADE_MS
        front.alpha = if (p >= 1f) 1f else p
        if (front.alpha >= 1f) recycleSlot(back)
    }

    /** 把槽里的资源停掉并放回池（LRU 超限时会被真正淘汰）。 */
    private fun recycleSlot(slot: Slot) {
        val d = slot.drawable
        val m = slot.movie
        val slug = slot.slug
        if (d != null) {
            Api28.stopAny(d)
            d.callback = null
            if (Build.VERSION.SDK_INT >= 28) {
                runCatching { d.setBounds(0, 0, 0, 0) }
                if (slug != null) drawablePool.put(slug, d)
            }
        }
        if (m != null && slug != null) moviePool.put(slug, m)
        slot.drawable = null
        slot.movie = null
        slot.slug = null
    }

    private fun retireAll() {
        cancelOneShot()
        Api28.stopAny(front.drawable)
        Api28.stopAny(back.drawable)
        front = Slot()
        back = Slot()
        invalidate()
    }

    private fun scheduleOneShot(token: Int, durationMs: Long) {
        cancelOneShot()
        val r = Runnable {
            pendingOneShot = null
            if (token != loadToken) return@Runnable
            onAnimationEnd?.invoke()
        }
        pendingOneShot = r
        handler.postDelayed(r, durationMs.coerceIn(500L, 30_000L))
    }

    /** 取消尚未触发的结尾回调：清画面/换包时不能让旧计时器把动画链往前推。 */
    private fun cancelOneShot() {
        pendingOneShot?.let { handler.removeCallbacks(it) }
        pendingOneShot = null
    }

    /**
     * 解码宽度的上限：**按控件实际像素**，不再固定压到 480。
     *
     * 素材本身最大 640px 宽，解码时只做「超过显示尺寸才降采样」，
     * 否则 1350px 的控件会把 480px 的图放大 2.8 倍，糊得明显。
     * 上限 1280 是兜底：极端大尺寸（1024dp 档）下也不至于爆内存。
     */
    private fun decodeMaxWidth(): Int {
        val w = width.takeIf { it > 0 } ?: (PetPrefs.DEFAULT_SIZE_DP * resources.displayMetrics.density).toInt()
        return w.coerceIn(96, 1280)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacksAndMessages(null)
        retireAll()
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

        // 旧画面在下、新画面在上淡入：切换窗口期两边都有内容，不会露黑
        if (!back.isEmpty) drawSlot(canvas, back, w, h, now)
        if (!front.isEmpty) drawSlot(canvas, front, w, h, now)
        canvas.restore()
    }

    private fun drawSlot(canvas: Canvas, slot: Slot, w: Int, h: Int, now: Long) {
        val alpha = slot.alpha.coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val needsLayer = alpha < 1f
        val save = if (needsLayer) {
            canvas.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (alpha * 255).toInt())
        } else {
            canvas.save()
        }
        val d = slot.drawable
        val m = slot.movie
        if (d != null) {
            d.setBounds(0, 0, w, h)
            runCatching { d.draw(canvas) }
        } else if (m != null) {
            drawMovie(canvas, m, w, h, now, slot)
        }
        canvas.restoreToCount(save)
    }

    private fun drawMovie(canvas: Canvas, movie: Movie, w: Int, h: Int, now: Long, slot: Slot) {
        val duration = movie.duration().coerceAtLeast(1)
        val elapsed = if (slot.loop) {
            ((now - slot.startMs) % duration).toInt()
        } else {
            ((now - slot.startMs).coerceAtMost(duration.toLong())).toInt()
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
        /** 交叉淡入时长：桌面版 CSS opacity 过渡是 .18s，这里同量级取整。 */
        private const val CROSSFADE_MS = 180f
        /**
         * 常驻解码槽位。同一个 drawable 任一时刻只被一个槽持有（取用时即出池），
         * 所以这里限制的是"最近播过的动画"占用的内存总量。一段 300x168 的
         * 动画 WebP 约几百 KB，8 槽在可接受范围内；OOM 时 LRU 会自动淘汰。
         */
        private const val CACHE_SLOTS = 8
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
        /**
         * 解码动画 WebP。**只在素材宽于显示宽时降采样**，不主动放大——
         * 放大只会白占内存，画质也不会变好（canvas 缩放即可）。
         */
        fun decode(file: File, maxW: Int): AnimatedImageDrawable? {
            val source = ImageDecoder.createSource(file)
            val decoded = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val iw = info.size.width
                if (maxW > 0 && iw > maxW) {
                    val scale = maxW.toFloat() / iw
                    val h = (info.size.height * scale).toInt().coerceAtLeast(1)
                    decoder.setTargetSize(maxW, h)
                }
            }
            return decoded as? AnimatedImageDrawable
        }

        fun restart(drawable: Drawable?, loop: Boolean) {
            val d = drawable as? AnimatedImageDrawable ?: return
            runCatching {
                d.stop()
                d.repeatCount = if (loop) AnimatedImageDrawable.REPEAT_INFINITE else 1
                d.start()
            }
        }

        fun stop(drawable: AnimatedImageDrawable?) {
            runCatching { drawable?.stop() }
        }

        fun stopAny(drawable: Drawable?) {
            runCatching { (drawable as? AnimatedImageDrawable)?.stop() }
        }
    }
}

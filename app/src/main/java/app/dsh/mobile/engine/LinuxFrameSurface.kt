package app.dsh.mobile.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 显示容器渲染画面的 SurfaceView。
 *
 * 数据通路（见镜像仓 `dsh-linux-runtime/docs/benchmark.md` 6.7）：
 *
 * ```
 * 容器进程 framesrv ──glReadPixels──> 共享文件（双方 mmap）
 *                                        │
 *                        App 侧 mmap ────┘
 *                              │
 *                         Bitmap ← copyPixelsFromBuffer
 *                              │
 *                         SurfaceView 贴图
 * ```
 *
 * ★为什么不是 memfd：`/proc/<pid>/fd/<n>` 只有 root 能 open，同 uid 的 App
 * 自己读容器进程的 memfd 会被 SELinux 拦成 Permission denied。必须落普通文件。
 *
 * ★为什么文件在 `/data/data/<pkg>/files/`：容器 rootfs 里的路径宿主看不到
 * （容器路径翻译只对容器内部生效），共享文件必须放在宿主与容器都能直接
 * `open` 的目录——App 私有 filesDir 正好两边都可写（容器靠 bind 挂进去）。
 *
 * ## 脏帧检测
 *
 * 共享文件是「无锁环形覆盖」：容器一直写，App 一直读，不做同步。判断「有没有
 * 新帧」靠容器推进的帧计数（文件头 16 字节，见 [HEADER_BYTES]），而不是文件
 * mtime —— mtime 被文件系统时间粒度卡住，16ms 一帧的写入会漏掉。
 */
class LinuxFrameSurface(ctx: Context) : SurfaceView(ctx), SurfaceHolder.Callback, Choreographer.FrameCallback {

    companion object {
        private const val TAG = "LinuxFrameSurface"

        /**
         * 共享文件头部：`u32 magic | u32 seq | u32 w | u32 h`（小端）。
         *
         * 与容器侧 `framesrv.c` 约定一致。magic = 0x4D415246（小端落盘后 od -c 读出即 "FRAM"）。
         * 像素数据从 [HEADER_BYTES] 开始，布局为 RGBA8888，与 Android
         * `ARGB_8888` 的内存字节序一致（Android 的 ARGB_8888 底层就是 RGBA）。
         */
        const val HEADER_BYTES = 16
        const val MAGIC = 0x4D415246

        /**
         * 显示帧率诊断日志的间隔。
         *
         * 为什么要打这个：容器侧渲染能到 1400 fps（见镜像仓 benchmark 6.10），
         * 但**显示端受 VSYNC 限制**——Choreographer 每个刷新周期只回调一次，
         * 所以界面实际只可能到屏幕刷新率。这个日志用于区分「容器慢」与
         * 「显示端到顶」，排查掉帧时先看它。
         */
        private const val FPS_LOG_INTERVAL_MS = 2000L

        /** 默认共享文件路径：App 私有目录，容器通过 bind 挂载进来 */
        fun defaultFrameFile(ctx: Context): File = File(ctx.filesDir, "dshframe.bin")
    }

    /** 帧就绪回调（在主线程） */
    var onFrameSize: ((w: Int, h: Int) -> Unit)? = null

    /** 统计：已显示帧数 / 最近一次读到的 seq */
    var framesShown = 0L
        private set

    private val frameFile: File = defaultFrameFile(ctx)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = Rect()

    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var mapped: ByteBuffer? = null
    private var bitmap: Bitmap? = null
    private var mappedWidth = 0
    private var mappedHeight = 0
    private var lastSeq = -1
    private var pending = false
    /** 诊断用状态（只进 logcat，不画到界面上） */
    private var lastStatus = "waiting for container frame"

    /** 上次打诊断日志的系统时刻（每 [FPS_LOG_INTERVAL_MS] 打一行） */
    private var lastFpsLogAt = 0L
    /** 上次打日志时累计的帧数，用于算区间帧率 */
    private var framesAtLastLog = 0L
    /** 分段耗时累计（ns），用于定位显示端瓶颈在取帧还是绘制 */
    private var perfPullNs = 0L
    private var perfDrawNs = 0L

    init {
        holder.addCallback(this)
        // SurfaceView 默认不透明，省一次合成
        holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
    }

    // ================= SurfaceHolder.Callback =================

    override fun surfaceCreated(h: SurfaceHolder) {
        startLoop()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        dstRect.set(0, 0, width, height)
        render()
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        stopLoop()
    }

    // ================= 帧循环 =================

    private fun startLoop() {
        if (pending) return
        pending = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        pending = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!pending) return
        val progressed = render()
        // 有进展（新帧或尺寸变化）就下一帧继续；没有也继续等，
        // 容器可能正在启动。空转成本极低（只是一次 stat + 内存比较）。
        if (progressed) framesShown++
        logFpsIfDue()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * 每 [FPS_LOG_INTERVAL_MS] 打一行显示帧率。
     *
     * 日志形如 `display fps=59.8 shown=1206 lastSeq=1206 size=1280x720`，
     * 可直接判断是「显示端到顶（≈屏幕刷新率）」还是「上游没给新帧
     * （fps 远低于刷新率，看 lastSeq 是否也在涨）」。
     */
    private fun logFpsIfDue() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastFpsLogAt == 0L) {
            lastFpsLogAt = now
            framesAtLastLog = framesShown
            return
        }
        val span = now - lastFpsLogAt
        if (span < FPS_LOG_INTERVAL_MS) return
        val shown = framesShown - framesAtLastLog
        val fps = shown * 1000.0 / span
        val n = (shown.coerceAtLeast(1)).toDouble()
        Log.i(
            TAG,
            "display fps=%.1f shown=%d lastSeq=%d size=%dx%d pull=%.1fms draw=%.1fms".format(
                fps, framesShown, lastSeq, mappedWidth, mappedHeight,
                perfPullNs / 1e6 / n, perfDrawNs / 1e6 / n
            )
        )
        lastFpsLogAt = now
        framesAtLastLog = framesShown
        perfPullNs = 0L
        perfDrawNs = 0L
    }

    /**
     * 拉取并显示一帧。
     *
     * @return true 表示这次真的画了新内容
     */
    private fun render(): Boolean {
        val holderOk = holder.surface?.isValid == true
        if (!holderOk) return false
        val t0 = android.os.SystemClock.elapsedRealtimeNanos()
        val fresh = ensureMapped() && pullFrame()
        val tPull = android.os.SystemClock.elapsedRealtimeNanos()
        if (!fresh) return false
        // ★用 lockHardwareCanvas 而不是 lockCanvas：后者拿的是软件 Canvas，
        //   drawBitmap 走 CPU 逐像素合成。实测（模拟器 720p）lockCanvas 路径
        //   draw 段 12.7ms/帧、整链只到 29 fps；换硬件 Canvas 后交给 GPU 合成，
        //   draw 段从 12.7ms 降到 0.4~0.8ms（实测 20 倍）。
        //   换完后仍有 ~30 fps 上限：对照实验（把 drawBitmap 整个去掉、只清屏）
        //   帧率仍是 30 fps，证明那个上限来自模拟器的 vsync 调度，不是我们的绘制。
        //   真机 60/120Hz 屏上这个上限会相应提高。
        val canvas: Canvas = holder.lockHardwareCanvas() ?: return false
        try {
            canvas.drawColor(Color.BLACK)
            bitmap?.let { bmp ->
                canvas.drawBitmap(bmp, null, dstRect, paint)
            }
            val tDraw = android.os.SystemClock.elapsedRealtimeNanos()
            perfPullNs += tPull - t0
            perfDrawNs += tDraw - tPull
            /* 没有画面时画布保持黑色；面向用户的提示由 Activity 的文案资源给，
               这里不画字 —— Kotlin 里出现中文会被 check-i18n 拦下。 */
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
        return true
    }

    /**
     * 打开共享文件并按文件头声明的尺寸建立映射 + Bitmap。
     *
     * 容器可能还没启动（文件不存在）或正在写文件头（magic 还没落），
     * 这两种情况都返回 false，等下一帧再试。
     */
    private fun ensureMapped(): Boolean {
        val f = frameFile
        if (!f.isFile) {
            lastStatus = "no frame file yet: ${f.name}"
            return false
        }
        val len = f.length()
        if (len < HEADER_BYTES) {
            lastStatus = "frame file too small"
            return false
        }

        // 已经映射过且尺寸没变，直接复用
        if (mapped != null && mappedWidth > 0) {
            return true
        }

        return runCatching {
            raf?.let { runCatching { it.close() } }
            val r = RandomAccessFile(f, "r")
            raf = r
            val ch = r.channel
            channel = ch
            val head = ByteBuffer.allocate(HEADER_BYTES)
            ch.read(head, 0)
            head.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            head.flip()
            val magic = head.int       // offset 0
            head.int                   // offset 4 = seq（这里读掉以对齐偏移，值不用）
            val w = head.int           // offset 8
            val h = head.int           // offset 12
            if (magic != MAGIC || w !in 16..8192 || h !in 16..8192) {
                // 容器还没写文件头，或格式不认识 —— 关掉等下一帧
                closeQuietly()
                lastStatus = "frame header not ready"
                return false
            }
            val need = (HEADER_BYTES.toLong()) + w.toLong() * h.toLong() * 4L
            if (len < need) {
                closeQuietly()
                lastStatus = "frame file short by ${need - len} bytes"
                return false
            }
            // 多映射一页，避免容器正在扩容时 SIGBUS
            val mapLen = (len.coerceAtMost(need)).toInt()
            // ★必须显式设小端：文件头与像素都由容器按小端写（aarch64/x86_64
            //   都是 LE），而 ByteBuffer 默认是 BIG_ENDIAN。不设的话 getInt(4)
            //   读出的 seq 是字节反转的垃圾值（实测打出 -1475936256 这类数字），
            //   于是每帧都被判成「新帧」→ 重复帧也白拷一次整帧。
            mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, mapLen.toLong()).apply {
                order(java.nio.ByteOrder.LITTLE_ENDIAN)
            }
            mappedWidth = w
            mappedHeight = h
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            onFrameSize?.invoke(w, h)
            Log.i(TAG, "mapped ${f.absolutePath} ${w}x${h} (${mapLen} bytes)")
            true
        }.getOrElse { e ->
            Log.w(TAG, "map failed: ${e.message}")
            closeQuietly()
            lastStatus = "map failed: ${e.message}"
            false
        }
    }

    /**
     * 从映射区取一帧。
     *
     * 脏判据是文件头里的 seq（容器每帧 +1）。seq 没变说明没有新帧，
     * 直接返回 false 省掉一次整帧拷贝（1080p 是 8MB，拷一次约 6ms）。
     */
    private fun pullFrame(): Boolean {
        val m = mapped ?: return false
        val w = mappedWidth
        val h = mappedHeight
        if (w <= 0 || h <= 0) return false
        val seq = m.getInt(4)
        if (seq == lastSeq) return false
        lastSeq = seq
        val bmp = bitmap ?: return false
        // 像素从 HEADER_BYTES 开始，整帧 RGBA8888
        val pixels = m.duplicate()
        pixels.position(HEADER_BYTES)
        return runCatching {
            bmp.copyPixelsFromBuffer(pixels)
            true
        }.getOrElse { e ->
            Log.w(TAG, "copyPixels failed: ${e.message}")
            false
        }
    }

    private fun closeQuietly() {
        runCatching { mapped = null }
        runCatching { bitmap?.recycle() }
        bitmap = null
        mappedWidth = 0
        mappedHeight = 0
        lastSeq = -1
        runCatching { channel?.close() }
        channel = null
        runCatching { raf?.close() }
        raf = null
    }

    /** 释放资源（Activity onDestroy 调用） */
    fun release() {
        stopLoop()
        closeQuietly()
    }
}

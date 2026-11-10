package app.dsh.mobile.engine

import android.view.MotionEvent
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 触摸事件回传通道：把 App 侧的手指动作写进共享文件，容器内的 `inputd` 读走
 * 并通过 XTEST 注入到 X 服务器。
 *
 * ## 为什么是共享文件而不是 socket / stdin
 *
 * 三条路都试过或评估过：
 * - **容器内 stdin**：`runner.shell()` 每次调用起一个进程，写一次 stdin 就退出，
 *   实测单次约 200ms，拖动会卡成幻灯片。
 * - **抽象 Unix socket**：需要容器内先起监听、App 侧连过去；但容器进程的生命周期
 *   由用户按钮控制，socket 断了要重连、重连期间事件丢失，状态机复杂。
 * - **共享文件**：与已有的帧服务（`dshframe.bin`）同一套机制，App 只管覆盖写、
 *   容器只管轮询读，双方永不阻塞。**实测端到端 p50 = 1.3ms**（见镜像仓
 *   benchmark §18），比起进程快 150 倍。
 *
 * ## 协议（与 `dsh-linux-runtime/bench/inputd.c` 严格一致）
 *
 * ```
 * offset 0   u32 magic    = 0x54504E49 ("INPT")
 * offset 4   u32 head     生产者写的下一个位置（单调递增）
 * offset 8   u32 tail     消费者已处理到的位置（单调递增）
 * offset 12  u32 capacity 固定 256
 * offset 16  u32 flags    bit0 = 生产者已挂载
 * offset 20  u32 resv[3]
 * offset 32  起每条 16 字节：u32 type | u32 x | u32 y | u32 buttons
 * ```
 *
 * `head`/`tail` 单调递增而不取模，取模只用于定位条目 —— 这样「满/空」只看
 * 减法，永远不会因为 32 位回绕判错。队列满时覆盖最旧的（生产者从不阻塞）。
 *
 * ## 坐标必须在这里换算
 *
 * 画面是按比例缩放显示的（`LinuxFrameSurface.onFrameSize` 把高度设成
 * `widthPx * h / w`），所以手指在控件上的位置与容器帧内的像素**不是一比一**。
 * 换算放在这里，因为只有这里同时知道 `dstRect` 和帧的真实尺寸；放到容器侧
 * 就得把缩放参数也传过去，两处各算一次迟早分叉。
 *
 * 线程安全：所有写入都在主线程（`onTouchEvent`），无需加锁。
 */
class LinuxInputChannel(ctx: android.content.Context) {

    companion object {
        const val MAGIC = 0x54504E49
        const val CAPACITY = 256
        const val HEADER_BYTES = 32
        const val ENTRY_BYTES = 16
        const val TOTAL_BYTES = HEADER_BYTES + CAPACITY * ENTRY_BYTES

        const val TYPE_NONE = 0
        const val TYPE_DOWN = 1
        const val TYPE_MOVE = 2
        const val TYPE_UP = 3

        /** 共享文件默认路径：App 私有目录，容器通过 bind 挂载进来 */
        fun defaultInputFile(ctx: android.content.Context): File =
            File(ctx.filesDir, "dshinput.bin")

        /** 容器内看到的路径（`/data/data/<pkg>/files/...`，与帧服务同一套约定） */
        fun containerPath(pkg: String): String = "/data/data/$pkg/files/dshinput.bin"
    }

    private val file: File = defaultInputFile(ctx)
    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var mapped: ByteBuffer? = null

    /** 生产者写入位置（单调递增，不回绕） */
    private var head = 0

    /** 打开失败的诊断信息（null = 正常）。只进日志，不抛给 UI。 */
    var lastError: String? = null
        private set

    /** 已累计写入的事件条数 */
    var eventsWritten = 0L
        private set

    /**
     * 打开并映射共享文件。
     *
     * 用 `RandomAccessFile("rw")` + `setLength` 而不是 `FileOutputStream`：
     * 需要可变长度映射，而 `FileOutputStream` 拿不到 Channel 的映射能力。
     */
    fun open(): Boolean {
        if (mapped != null) return true
        return runCatching {
            if (!file.parentFile!!.isDirectory) file.parentFile!!.mkdirs()
            raf = RandomAccessFile(file, "rw").apply { setLength(TOTAL_BYTES.toLong()) }
            channel = raf!!.channel
            val buf = channel!!.map(FileChannel.MapMode.READ_WRITE, 0, TOTAL_BYTES.toLong())
            // ★必须显式小端：ByteBuffer 默认 BIG_ENDIAN，容器按小端写 head/tail，
            //   不设的话读出来是字节反转的垃圾值，表现为「每帧都是新事件」。
            //   显示端（LinuxFrameSurface）踩过同样的坑。
            //
            //   order() 返回的是同一个 ByteBuffer（链式），但写成 apply { order(...); putInt(...) }
            //   时接收者仍是原对象、编译器却会因 order 的返回值类型推断混乱而报
            //   Unresolved reference: putInt —— 所以这里分开写，别图省事链起来。
            buf.order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0, MAGIC)
            buf.putInt(4, 0)          // head
            buf.putInt(8, 0)          // tail
            buf.putInt(12, CAPACITY)
            buf.putInt(16, 1)         // flags: 生产者已挂载
            mapped = buf
            head = 0
            true
        }.getOrElse { e ->
            lastError = "open failed: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    /** 解除映射并关文件。可重复调用。 */
    fun close() {
        runCatching { channel?.close() }
        runCatching { raf?.close() }
        channel = null
        raf = null
        mapped = null
    }

    /**
     * 推入一条事件。
     *
     * 队列满时**覆盖最旧的**而不是丢弃新的：触摸场景里「最新位置」永远比
     * 「历史轨迹的一条」更有价值，而且这样生产者永不阻塞（阻塞会让 UI 卡顿）。
     */
    fun push(type: Int, x: Int, y: Int, buttons: Int): Boolean {
        val b = mapped ?: return false
        val idx = head % CAPACITY
        val off = HEADER_BYTES + idx * ENTRY_BYTES
        b.putInt(off + 0, type)
        b.putInt(off + 4, x)
        b.putInt(off + 8, y)
        b.putInt(off + 12, buttons)
        head += 1
        b.putInt(4, head)
        eventsWritten += 1
        return true
    }

    /**
     * 把一次 `MotionEvent` 翻译成事件并推入。
     *
     * 坐标换算：`dstRect` 是画面在控件里的实际绘制区域（`LinuxFrameSurface`
     * 里 `dstRect.set(0, 0, width, height)`），`frameW/frameH` 是容器输出的
     * 真实分辨率。手指在控件任意位置 → 先按 dstRect 归一到 [0,1] → 再乘帧尺寸。
     *
     * 越界钳到边界而不是丢弃：手指滑出画面边缘时，程序通常期望收到边缘坐标
     * （拖动选择、滑块拉到尽头都依赖这个行为）。
     */
    fun handleTouch(ev: MotionEvent, dstRect: android.graphics.Rect, frameW: Int, frameH: Int): Boolean {
        if (frameW <= 0 || frameH <= 0) return false
        if (dstRect.width() <= 0 || dstRect.height() <= 0) return false

        val nx = (ev.x - dstRect.left) / dstRect.width().toFloat()
        val ny = (ev.y - dstRect.top) / dstRect.height().toFloat()
        val cx = (nx * frameW).toInt().coerceIn(0, frameW - 1)
        val cy = (ny * frameH).toInt().coerceIn(0, frameH - 1)

        return when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                push(TYPE_DOWN, cx, cy, 1)
            MotionEvent.ACTION_MOVE ->
                push(TYPE_MOVE, cx, cy, 1)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                push(TYPE_UP, cx, cy, 0)
            else -> false
        }
    }

    /** 诊断一行（只进 logcat） */
    fun describe(): String =
        "file=${file.name} head=$head events=$eventsWritten err=${lastError ?: "none"}"
}

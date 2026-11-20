package app.dsh.mobile.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import app.dsh.mobile.DshAccessibilityService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 逐步截图：把「每一步当时那个界面长什么样」变成记忆的一部分。
 *
 * ## 为什么需要
 *
 * 记忆原来只有文本（指纹 + 目标文本 + 校验用的文本集合）。文本能回答「该点哪个」，
 * 但回答不了「这个页面长什么样」—— 而人一眼就能看出「这是微信的聊天页还是通讯录页」。
 * 模型缺了这层，在文本稀疏的界面（图标页、自绘界面、图片列表）里就只剩盲猜。
 *
 * 更实际的一条：记忆失效时（App 改版、页面换布局），文本层面只表现为
 * `verify-failed`，模型得重新 dump 一遍才知道为什么；有图就能直接看出「按钮挪位置了」。
 *
 * ## 三个设计约束（都是被真机数字逼出来的）
 *
 * 1. **绝不阻塞动作路径**。录制当前已经 +40ms/动作（脚本里记着这是待优化头号负担），
 *    而一次 `takeScreenshot` 是 100–400ms，还可能撞系统节流。同步取帧等于把任务
 *    变慢一倍。所以取帧全部丢给单线程 worker，动作线程只做一次 `enqueue`。
 *    队列满就**丢新的那张**：宁可少一张图，也不能让界面等。
 *
 * 2. **落盘就缩到 [THUMB_W] 宽**。1080×2280 的 PNG 是 300KB–1MB，一任务 8 步就 8MB，
 *    500 条记忆能把手机写满。缩到 320 宽 + WebP q55 后单张 ~20KB，
 *    8 步 ≈ 160KB，全套预算 [MAX_TOTAL_BYTES] 兜底。
 *
 * 3. **图片侧车存，不动轨迹主文件**。见 [StepShotLayout] 顶部注释：轨迹行要立刻落
 *    盘（崩溃不丢），图片晚到，两者写盘时机不同；回头改主文件的行会留下半行。
 *
 * ## 时序语义（写清楚，别让后来人猜）
 *
 * 采集点在「这一步刚做完」之后（外加任务起手一屏）。所以拼图读起来是一条时间线：
 *
 * ```
 * [S] 开始前 → [0] 第1步做完 → [1] 第2步做完 → ...
 * ```
 *
 * 第 k 张（k≥1）基本就是第 k+1 步的「操作前界面」，重放时拿它跟当前屏比对最直观。
 * 中间若界面自己动了（转圈、视频、系统弹窗）会有细微差别，这是「事后取帧」的
 * 固有代价，接受 —— 换成「事前取帧」要么多一次取帧，要么得在动作前阻塞。
 */
object StepShots {

    private const val TAG = "DshStepShots"

    /** 缩略图宽度。320 在手机上看得出布局，单张 ~20KB。 */
    const val THUMB_W = 320

    /** 拼图单格宽度。200 能认出页面骨架，四列约 820px 宽。 */
    const val STRIP_CELL_W = 200

    /** 单条轨迹最多留几张。超过就没有拼接价值（格子细到认不出），也吃存储。 */
    const val MAX_PER_TRACE = 24

    /** 全部记忆截图的预算。超了按目录最后修改时间淘汰最旧的。 */
    const val MAX_TOTAL_BYTES = 48L * 1024 * 1024

    /** 缩略图 WebP 质量。55 在这个尺寸下肉眼与 80 几乎无差，体积差一倍。 */
    private const val THUMB_QUALITY = 55

    /** 拼图质量。要看清角标数字，给高一点。 */
    private const val STRIP_QUALITY = 78

    /** 总开关（设置页可关；关掉后连队列都不进）。 */
    @Volatile var enabled: Boolean = true

    /** 取帧超时。撞系统节流时 screenshotArgb 自己会退避重试，这里只兜总时长。 */
    private const val SHOT_TIMEOUT_MS = 1_200L

    /**
     * 起手帧的宽限窗口。
     *
     * 起手帧入队后，任务的**第一个动作**紧接着开始，于是 `latestAsked` 立刻被改成 0 ——
     * 按「不是最新就丢」的规则，起手帧会有相当高的概率被判 stale 丢掉
     * （首动作 tap 通常 200–800ms，一次取帧 100–400ms，两者重叠区很大）。
     * 起手屏是「这条记忆从哪个界面开始」的唯一证据，且不参与重放判定（只是拼图第一格），
     * 所以给它一个有限宽限：晚几百毫秒的起点图仍有用。超过这个窗口才丢 ——
     * 再晚就该怀疑拍到的已经是后面的界面了。
     */
    private const val START_GRACE_MS = 3_000L

    /**
     * 单线程 + 有界队列。
     *
     * 用自定义拒绝处理器而不是 `CallerRunsPolicy` / `DiscardPolicy`：
     * - `CallerRunsPolicy` 会把取帧压回动作线程 —— 正好是要避免的那件事；
     * - `DiscardPolicy` **静默丢弃**：既不抛异常也不执行任务。而 `pending++` 与
     *   perTrace 名额都已经在入队前加过了，没人补偿 → `pending` 永不归零，
     *   `awaitIdle` 每次都吃满 2.5s 超时；名额也会被丢弃次数烧光，24 次之后
     *   这条轨迹彻底停止截图。所以必须**显式记账并归还**。
     */
    private val worker: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue(3),
            { r -> Thread(r, "dsh-step-shots").apply { isDaemon = true } },
            java.util.concurrent.RejectedExecutionHandler { r, _ ->
                // 队列满：这一帧不要了，但账要还回去。
                val t = r as? ShotTask
                val id = t?.traceId.orEmpty()
                if (id.isNotBlank()) {
                    pending[id]?.let { if (it.decrementAndGet() <= 0) pending.remove(id, it) }
                    perTrace[id]?.let { if (it.decrementAndGet() <= 0) perTrace.remove(id, it) }
                }
                drop(id, "queue-full")
            },
        ).apply { allowCoreThreadTimeOut(true) }
    }

    /** 一次取帧任务。带 traceId 是为了让拒绝处理器能把 pending / 名额还回去。 */
    private class ShotTask(val app: Context, val traceId: String, val index: Int) : Runnable {
        override fun run() {
            StepShots.captureNow(app, traceId, index)
        }
    }

    /** 每条轨迹已截几张（用于 [MAX_PER_TRACE] 上限，避免读侧车判断）。 */
    private val perTrace = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 每条轨迹「最新一次请求的步号」。
     *
     * 用来判「这一帧是不是已经过期」：worker 取帧有 100–400ms 延迟，若这期间又
     * 有更新的步入队，说明界面已经往下走了，此刻取到的屏属于后面的步，绝不能
     * 记到早一步头上。宁可丢一帧，也不能错位（错位比没图糟得多）。
     */
    private val latestAsked = ConcurrentHashMap<String, Int>()

    /**
     * 每条轨迹里「已接纳但还没落盘」的取帧数。
     *
     * 入库（`bundle`）前要等它归零：否则最后一帧还在路上，读侧车读不到，
     * 正常做完的任务会稳定缺尾图（短任务甚至整条无图）。
     */
    private val pending = ConcurrentHashMap<String, AtomicInteger>()

    /** 已作废的轨迹（clear / TTL 剪枝）。worker 取帧前查这里，晚到的帧直接丢。 */
    private val tombstones = ConcurrentHashMap<String, Long>()

    /** 起手帧的入队时刻（毫秒）。只给起手帧的宽限窗口用，见 captureInner。 */
    private val startAskedAt = ConcurrentHashMap<String, Long>()

    /** 被丢弃的取帧次数（诊断用：录制里图少了，先看这个数）。 */
    private val dropped = AtomicInteger(0)

    /** 丢弃原因分布，诊断「为什么没图」用（过期/保护页/超限/队列满/取帧失败）。 */
    private val dropReasons = ConcurrentHashMap<String, AtomicInteger>()

    // ==================== 写 ====================

    /** 任务起手：清计数并截「开始前」那一屏。 */
    fun onTaskBegin(ctx: Context, traceId: String, protected: Boolean = false) {
        if (!enabled || traceId.isBlank()) return
        perTrace[traceId] = AtomicInteger(0)
        latestAsked[traceId] = StepShotLayout.START_INDEX
        startAskedAt[traceId] = System.currentTimeMillis()
        tombstones.remove(traceId)
        enqueue(ctx, traceId, StepShotLayout.START_INDEX, protected)
    }

    /**
     * 一步做完：截这一屏。
     *
     * @param stepIndex 轨迹里的步序号（`TaskRecorder.step` 的返回值）；<0 表示这步没落盘
     * @param protected 保护页上不截 —— 密码框、支付页的像素不该进记忆
     */
    fun onStep(ctx: Context, traceId: String, stepIndex: Int, protected: Boolean) {
        if (!enabled || traceId.isBlank() || stepIndex < 0) return
        enqueue(ctx, traceId, stepIndex, protected)
    }

    /**
     * 入队一次取帧。
     *
     * 三步：
     * 1. **记录最新请求的步号**（供 worker 判过期）；
     * 2. **CAS 抢名额**（[MAX_PER_TRACE] 的原子实现）；
     * 3. **pending++** 后入队，worker 出队时 pending--。
     *
     * 关于「过期」：worker 取帧有 100–400ms 延迟。若这期间又有更新的步入队，
     * 此刻屏幕已经往下走了，这时取到的像素属于后面的步。给它记到早一步头上，
     * 就是**稳定错图**（而且看起来一切正常）。所以 worker 取出后先比一次
     * [latestAsked]，不是最新就丢 —— 宁可少一张图。
     */
    private fun enqueue(ctx: Context, traceId: String, index: Int, protected: Boolean) {
        if (protected) {
            drop(traceId, "protected")
            return
        }
        latestAsked[traceId] = index
        val n = perTrace.getOrPut(traceId) { AtomicInteger(0) }
        // CAS 抢名额：get-then-increment 会让两个并发生产者都通过 23 号检查，
        // 实际占成 25 —— 软上限被越过后「为什么多两张」没人说得清。
        var cur: Int
        do {
            cur = n.get()
            if (cur >= MAX_PER_TRACE) {
                drop(traceId, "over-limit")
                return
            }
        } while (!n.compareAndSet(cur, cur + 1))

        pending.getOrPut(traceId) { AtomicInteger(0) }.incrementAndGet()
        val app = ctx.applicationContext
        try {
            worker.execute(ShotTask(app, traceId, index))
        } catch (e: Exception) {
            // execute 抛（线程池已关）时名额与 pending 都得还回去，否则这条轨迹
            // 会永久占着配额，`awaitIdle` 也会永远等不到零。
            pending[traceId]?.decrementAndGet()
            drop(traceId, "enqueue: " + e.message)
        }
    }

    /** 记一次丢弃（带原因），诊断「这次录制为什么没图」。 */
    private fun drop(traceId: String, reason: String) {
        dropped.incrementAndGet()
        dropReasons.getOrPut(reason) { AtomicInteger(0) }.incrementAndGet()
        if (traceId.isNotBlank()) {
            Log.d(TAG, "drop shot [" + reason + "] trace=" + traceId)
        }
    }

    /** 真正取帧 + 缩图 + 落盘 + 记侧车。只在 worker 线程上跑。 */
    private fun captureNow(ctx: Context, traceId: String, index: Int) {
        try {
            captureInner(ctx, traceId, index)
        } finally {
            // pending 必须成对递减：`bundle` 的等待靠它归零。
            pending[traceId]?.let { if (it.decrementAndGet() <= 0) pending.remove(traceId, it) }
        }
    }

    private fun captureInner(ctx: Context, traceId: String, index: Int) {
        val svc = DshAccessibilityService.instance ?: run {
            drop(traceId, "no-service"); return
        }
        // 1) 轨迹已被清空/剪枝 → 这一帧不要了（否则清空后磁盘上又长出来）
        if (tombstones.containsKey(traceId)) {
            drop(traceId, "tombstone"); return
        }
        // 2) 过期：这期间已经有更新的步入队，此刻屏幕属于后面的步
        val asked = latestAsked[traceId]
        if (asked != null && asked != index) {
            if (index == StepShotLayout.START_INDEX) {
                // 起手帧例外：它入队后首动作立刻开始、latestAsked 马上变，
                // 按上一条规则会系统性丢掉「任务从哪个界面开始」这唯一证据。
                // 给一个有限宽限（超过就丢，那时候多半真的拍到后面的界面了）。
                val askedAt = startAskedAt[traceId] ?: 0L
                if (System.currentTimeMillis() - askedAt > START_GRACE_MS) {
                    drop(traceId, "stale-start-window"); return
                }
            } else {
                drop(traceId, "stale(" + index + "!=" + asked + ")"); return
            }
        }
        // 3) 取帧前**再验一次**保护页。
        //
        // 调用方的判断发生在入队那一刻，而队列是有延迟的（撞节流时能积压好几步）。
        // 中间界面可能已经从普通页跳到了安装器/权限控制器/密码页 —— 那几张图会
        // 既不进任何记忆，又已经落盘。这里只做**零成本**的整包判据（读一次前台包名），
        // 覆盖的正是风险面最大的那几类；设置类 + 硬标题词由调用方按动作后屏文本判。
        if (svc.foregroundPackage()?.let { it in app.dsh.mobile.ProtectedPages.PACKAGES } == true) {
            drop(traceId, "protected-package"); return
        }
        val t0 = System.currentTimeMillis()
        val frame = svc.screenshotArgb(SHOT_TIMEOUT_MS) ?: run {
            drop(traceId, "screenshot-failed"); return
        }
        val (px, w, h) = frame
        if (w <= 0 || h <= 0 || px.size < w * h) {
            drop(traceId, "bad-frame"); return
        }
        // 4) 取完再比一次：取帧本身也要 100–400ms，这期间界面可能又走了。
        //    这一条挡的是「拿到的是第 N+1 步的屏，却要记成第 N 步」。
        if (latestAsked[traceId]?.let { it != index } == true) {
            drop(traceId, "stale-after-capture(" + index + ")"); return
        }

        val tw = minOf(THUMB_W, w)
        val th = ((h.toLong() * tw) / w).toInt().coerceIn(1, h * 4)
        val small = boxDownscale(px, w, h, tw, th)

        val dir = shotDirOf(ctx, traceId)
        if (!dir.exists() && !dir.mkdirs()) {
            drop(traceId, "mkdir-failed"); return
        }
        val name = if (index == StepShotLayout.START_INDEX) "start.webp" else index.toString() + ".webp"
        val f = File(dir, name)

        var bmp: Bitmap? = null
        var written = false
        try {
            bmp = Bitmap.createBitmap(small, tw, th, Bitmap.Config.ARGB_8888)
            val bos = ByteArrayOutputStream(24 * 1024)
            if (!bmp.compress(Bitmap.CompressFormat.WEBP, THUMB_QUALITY, bos)) {
                drop(traceId, "webp-encode-failed"); return
            }
            // 先写临时文件再改名：直接写最终名时若中途失败（磁盘满、被 kill），
            // 会留下一个**半张图**。`bundle` 只看文件在不在，就会把半张图搬进记忆，
            // 而且这张半图会一直显示成「上半截有界面、下半截黑」。改名是原子的。
            val tmp = File(dir, name + ".part")
            tmp.writeBytes(bos.toByteArray())
            if (!tmp.renameTo(f)) {
                tmp.delete()
                drop(traceId, "rename-failed"); return
            }
            written = true
        } catch (e: Exception) {
            drop(traceId, "io:" + e.message)
        } finally {
            bmp?.recycle()
        }
        if (!written) return

        // 侧车写失败 → 这张图没有任何记录认领它，留着就是磁盘残留。
        // 所以侧车追加必须成对：写完才留文件，写失败就连图一起删。
        if (!appendSidecar(ctx, traceId, StepShotLayout.Thumb(index, tw, th, name, f.length()))) {
            f.delete()
            drop(traceId, "sidecar-failed")
            return
        }
        val cost = System.currentTimeMillis() - t0
        if (cost > 400) Log.i(TAG, "shot slow: step=" + index + " " + cost + "ms")
    }

    /**
     * 面积平均降采样（不是最近邻）。
     *
     * 最近邻在 3.4 倍缩小下会把细笔画打碎成噪点 —— 中文界面上半屏全是笔画，
     * 结果就是「一片糊」，拼图里认不出是哪个页面。这里对每个目标像素取源区块均值，
     * 代价是 320×676×约 11 次取样 ≈ 2.4M 次加法，实测量级 10–20ms，值得。
     */
    private fun boxDownscale(px: IntArray, w: Int, h: Int, tw: Int, th: Int): IntArray {
        val dst = IntArray(tw * th)
        for (ty in 0 until th) {
            val y0 = (ty.toLong() * h / th).toInt().coerceIn(0, h - 1)
            val y1 = (((ty + 1).toLong() * h / th).toInt()).coerceIn(y0 + 1, h)
            for (tx in 0 until tw) {
                val x0 = (tx.toLong() * w / tw).toInt().coerceIn(0, w - 1)
                val x1 = (((tx + 1).toLong() * w / tw).toInt()).coerceIn(x0 + 1, w)
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                var y = y0
                while (y < y1) {
                    val row = y * w
                    var x = x0
                    while (x < x1) {
                        val c = px[row + x]
                        r += (c ushr 16) and 0xFF
                        g += (c ushr 8) and 0xFF
                        b += c and 0xFF
                        n++
                        x++
                    }
                    y++
                }
                if (n == 0) n = 1
                dst[ty * tw + tx] = (0xFF shl 24) or
                    ((r / n).toInt() shl 16) or
                    ((g / n).toInt() shl 8) or
                    (b / n).toInt()
            }
        }
        return dst
    }

    /**
     * 追加一行侧车。每行 flush：进程被杀时已截到的图仍能被认领（与轨迹同一条原则）。
     *
     * @return 写成功与否。调用方据此决定「图留不留」—— 侧车没有这一行，这张图永远
     *         不会被 `bundle` 认领，留在磁盘上就是纯垃圾。
     */
    private fun appendSidecar(ctx: Context, traceId: String, t: StepShotLayout.Thumb): Boolean {
        val f = sidecarOf(ctx, traceId)
        return runCatching {
            f.parentFile?.mkdirs()
            f.appendText(StepShotLayout.encodeLine(t) + "\n")
            true
        }.getOrElse {
            Log.w(TAG, "sidecar append failed: " + it.message)
            false
        }
    }

    /**
     * 等某条轨迹「已接纳的取帧」全部落盘，最多 [timeoutMs]。
     *
     * 入库前必须调它：截图在后台，`bundle` 只读当时的侧车。不等的话，正常做完的
     * 任务会**稳定缺尾图**（最后一两帧还在路上），短任务甚至整条没有图 ——
     * 真机上第一轮验收就是这个症状（learn 返回了 id，但 `memory/v2/shots` 目录不存在）。
     *
     * 上限是必须的：撞系统截图节流时一帧能拖几百毫秒，这里不能无限等。
     * 超时就按现有侧车内容打包 —— 少一张尾图远好于整个 learn 失败。
     */
    fun awaitIdle(traceId: String, timeoutMs: Long = 2_500L): Boolean {
        if (traceId.isBlank()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val left = pending[traceId]?.get() ?: 0
            if (left <= 0) return true
            try {
                Thread.sleep(40L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Log.w(TAG, "awaitIdle timeout, pending=" + (pending[traceId]?.get() ?: 0) + " trace=" + traceId)
        return false
    }

    // ==================== 入库 ====================

    /**
     * 入库结果。
     *
     * [shotRels] 以 `stepIndex`（含 `-1`）为键，值是**相对 `filesDir` 的路径**
     * （不存绝对路径：App 数据目录在恢复备份/更换用户后可能变，相对路径永远可解析）。
     */
    class Bundle(
        val startShot: String,
        val strip: String,
        val shotRels: Map<Int, String>,
        val count: Int,
        val bytes: Long,
    )

    /**
     * 把某条轨迹的图搬进某条记忆名下，并拼一张总览图。
     *
     * 为什么入库时要「搬」而不是引用轨迹的图：轨迹 7 天 TTL 清掉，记忆是长期资产。
     * 引用等于让记忆里的图随轨迹一起消失。
     *
     * @param keptIndices 剪枝后各步的 `sourceIndex`（原始顺序）
     */
    fun bundle(ctx: Context, traceId: String, memoryId: String, keptIndices: List<Int>): Bundle? {
        if (traceId.isBlank() || memoryId.isBlank()) return null
        // 不安全 id 一律不落盘：越界写比不写危险得多（详见 isSafeId 的注释）。
        val dstDir = memoryDirOf(ctx, memoryId) ?: return null
        // 等已接纳的取帧落盘（幂等：调用方通常已经等过，这里再等一次几乎零成本）。
        // 入库路径有两条（learnQuiet / memLearn），把等待放在 bundle 里比放在两个调用点
        // 更可靠 —— 将来再加第三条路径也不会漏。
        awaitIdle(traceId)
        val shots = readSidecar(ctx, traceId)
        if (shots.isEmpty()) return null
        val picked = StepShotLayout.pickForKept(shots, keptIndices)
        if (picked.isEmpty()) return null

        val srcDir = shotDirOf(ctx, traceId)
        if (!dstDir.exists() && !dstDir.mkdirs()) return null

        val relMap = HashMap<Int, String>(picked.size * 2)
        var bytes = 0L
        val copied = ArrayList<File>(picked.size)
        for (t in picked) {
            val src = File(srcDir, t.fileName)
            if (!src.isFile) continue
            val dst = File(dstDir, t.fileName)
            // 文件名也过一次安全校验：它来自侧车（我们自己写的，但侧车在磁盘上可能被
            // 手工改过/被旧版本写过），拼进目标目录前必须确认是单段文件名。
            if (!isSafeId(t.fileName.removeSuffix(".webp"))) {
                Log.w(TAG, "skip unsafe shot file name: " + t.fileName)
                continue
            }
            runCatching {
                src.copyTo(dst, overwrite = true)
                bytes += dst.length()
                relMap[t.stepIndex] = relOf(ctx, dst)
                copied.add(dst)
            }.onFailure { Log.w(TAG, "copy shot failed: " + t.fileName + " " + it.message) }
        }
        if (copied.isEmpty()) return null

        val stripRel = buildStrip(ctx, memoryId, picked, srcDir)
        // 收尾：目录里不属于这一版的文件清掉。
        //
        // 为什么放在这里而不是调用方 resetMemory：先删再打包时，若打包失败
        // （节流/保护页/没图），旧图已经被删光，调用方还在指望着「保留旧图」——
        // 结果是旧图必丢 + 元数据指向不存在的文件。现在的顺序是
        // 「先打包成功，再清残留」，失败就什么都没动。
        pruneUnlisted(ctx, dstDir, relMap.values.mapNotNull { File(ctx.filesDir, it).name },
            keepStripName = stripRel.isNotBlank())
        val startRel = relMap[StepShotLayout.START_INDEX].orEmpty()
        return Bundle(startRel, stripRel, relMap, copied.size, bytes)
    }

    /** 删掉目录里「这一版不该有」的文件（重学后上一步的图、半截的 .part 残片等）。 */
    private fun pruneUnlisted(ctx: Context, dir: File, keepNames: List<String>, keepStripName: Boolean) {
        runCatching {
            val keep = HashSet<String>(keepNames.size + 2)
            for (n in keepNames) keep.add(n)
            if (keepStripName) keep.add(STRIP_NAME)
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                if (f.name !in keep) {
                    Log.d(TAG, "prune stale shot file: " + f.name)
                    f.delete()
                }
            }
        }
    }

    /**
     * 拼图：把每步的缩略图按 [StepShotLayout.plan] 摆到一张画布上，每格带角标。
     *
     * 角标是这张图能被读懂的关键 —— 没有编号，模型只能看到「一串相似界面」，
     * 无法把第 3 格对应到第 3 步（而重放是按步走的）。
     */
    private fun buildStrip(
        ctx: Context,
        memoryId: String,
        picked: List<StepShotLayout.Thumb>,
        srcDir: File,
    ): String {
        val aspect = picked.firstOrNull { it.aspect() > 0f }?.aspect() ?: 2.0f
        val plan = StepShotLayout.plan(picked.size, STRIP_CELL_W, aspect)
        if (plan.canvasW <= 0 || plan.canvasH <= 0) return ""

        var canvas: Bitmap? = null
        var strip: Bitmap? = null
        try {
            strip = Bitmap.createBitmap(plan.canvasW, plan.canvasH, Bitmap.Config.ARGB_8888)
            canvas = strip
            val c = Canvas(strip)
            c.drawColor(Color.parseColor("#101014"))

            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E8E8EE")
                textSize = plan.labelH * 0.74f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val border = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = Color.parseColor("#3A3A44")
            }
            val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

            for (i in picked.indices) {
                val t = picked[i]
                val cell = plan.cells[i]
                // 角标：起点写 S，其余写步号
                c.drawText(t.label(), cell.x.toFloat() + 2f, cell.labelY.toFloat(), labelPaint)
                val f = File(srcDir, t.fileName)
                if (!f.isFile) continue
                val src = runCatching { decodeSampled(f, plan.cellW) }.getOrNull() ?: continue
                try {
                    // fitInto 返回 (w, h, dx, dy)：等比缩放后居中留边，绝不拉伸
                    val fit = StepShotLayout.fitInto(src.width, src.height, cell.w, cell.h)
                    val l = cell.x + fit[2]
                    val t = cell.y + fit[3]
                    val dst = android.graphics.Rect(l, t, l + fit[0], t + fit[1])
                    c.drawBitmap(src, null, dst, imgPaint)
                    c.drawRect(l.toFloat(), t.toFloat(),
                        (l + fit[0]).toFloat(), (t + fit[1]).toFloat(), border)
                } finally {
                    src.recycle()
                }
            }

            val out = File(memoryDirOf(ctx, memoryId), STRIP_NAME)
            val bos = ByteArrayOutputStream(96 * 1024)
            if (!strip.compress(Bitmap.CompressFormat.WEBP, STRIP_QUALITY, bos)) return ""
            out.writeBytes(bos.toByteArray())
            return relOf(ctx, out)
        } catch (e: Exception) {
            Log.w(TAG, "buildStrip failed: " + e.message)
            return ""
        } finally {
            // canvas 与 strip 是同一个对象，只回收一次
            if (canvas != null) canvas.recycle() else strip?.recycle()
        }
    }

    /** 按目标宽度做采样解码：直接用原图解码会为 200px 的格子占掉 10MB。 */
    private fun decodeSampled(f: File, targetW: Int): Bitmap? {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
        var sample = 1
        var w = opts.outWidth.coerceAtLeast(1)
        while (w / (sample * 2) >= targetW) sample *= 2
        val dec = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeFile(f.absolutePath, dec)
    }

    // ==================== 读 ====================

    fun readSidecar(ctx: Context, traceId: String): List<StepShotLayout.Thumb> = runCatching {
        val f = sidecarOf(ctx, traceId)
        if (!f.isFile) return emptyList()
        StepShotLayout.parseAll(f.readText())
    }.getOrDefault(emptyList())

    /**
     * 相对路径 → 绝对文件。记忆里永远存相对路径，读的时候现算。
     *
     * 这里**必须**做越界校验，不能 `File(filesDir, rel)` 一把梭：
     * `rel` 虽然正常情况下由 `relOf` 生成（形如 `memory/v2/shots/<id>/0.webp`），
     * 但它最终来自磁盘上的记忆 JSON，那个文件是**可被手工编辑**的
     * （本项目就处理过手工改 JSON 带 BOM 的场景）。注入 `../../databases/x`
     * 就能让 `/memory/v2/shot` 返回 filesDir 之外的任意文件路径。
     *
     * 两道判据：① 任何一段是 `..` 直接拒；② 解析后（canonical，能吃掉 `a/../b`
     * 这种还没进 ① 的变体）必须仍在 filesDir 之下。
     */
    fun resolve(ctx: Context, rel: String): File? {
        if (rel.isBlank()) return null
        val normalized = rel.replace('\\', '/')
        for (seg in normalized.split('/')) {
            if (seg == "..") {
                Log.w(TAG, "refuse escaping rel: " + rel.take(60))
                return null
            }
        }
        val base = ctx.filesDir
        val f = File(base, normalized)
        val baseCanon = runCatching { base.canonicalPath }.getOrDefault(base.absolutePath)
        val fileCanon = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        if (fileCanon != baseCanon && !fileCanon.startsWith(baseCanon + File.separator)) {
            Log.w(TAG, "refuse out-of-root rel: " + rel.take(60))
            return null
        }
        return f
    }

    fun stripName(): String = STRIP_NAME

    // ==================== 生命周期 ====================

    /** 记忆重建（fork / 重新学习）时先清旧图，避免残留指不到任何一步的图。 */
    fun resetMemory(ctx: Context, memoryId: String) = deleteMemory(ctx, memoryId)

    /**
     * 记忆 id 是否**可以安全地当作一个目录名**。
     *
     * ## 为什么必须有这个检查
     *
     * `memoryId` 来自 HTTP 输入（`/memory/v2/upsert`、`/memory/v2/delete` 的 `id`），
     * 而 `deleteMemory` 会 `deleteRecursively()`。若 id 写成 `../com.android.settings.json`，
     * `File(memory/v2/shots, id)` 会解析到 `memory/v2/com.android.settings.json` ——
     * 那是**另一个包的全部记忆**。删一张图变成删一包数据，这是越界删除，不是误删图片。
     *
     * 判据刻意窄：只允许 `[A-Za-z0-9_.-]`，且不许是 `.` / `..` / 空 / 含分隔符。
     * 自带的 id 形如 `m_1795_8809`，天然通过；任何需要穿越的写法一律拒绝。
     */
    fun isSafeId(id: String): Boolean {
        if (id.isBlank() || id.length > 120) return false
        if (id == "." || id == "..") return false
        for (c in id) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.'
            if (!ok) return false
        }
        // 形如 `...` 或 `a..b` 的也拒：虽然拼接后不一定逃逸，但没有正当用途。
        return !id.contains("..")
    }

    /**
     * 记忆 id → 目录。**不安全就返回 null**，调用方必须当成「没有图」处理。
     *
     * 另外做一层 canonical 校验：即使 isSafeId 被绕过（未来有人放宽字符集），
     * 解析后的父目录必须仍是 shots 根，否则同样拒绝。两道锁，任何一道成立即可挡住。
     */
    private fun memoryDirOf(ctx: Context, memoryId: String): File? {
        if (!isSafeId(memoryId)) {
            Log.w(TAG, "refuse unsafe memoryId: " + memoryId.take(40))
            return null
        }
        val root = File(File(ctx.filesDir, MEM_PARENT), MEM_DIR)
        val dir = File(root, memoryId)
        return if (dir.parentFile?.absolutePath == root.absolutePath) dir else {
            Log.w(TAG, "refuse escaping memoryId: " + memoryId.take(40))
            null
        }
    }

    /**
     * 作废一批轨迹：清掉它们的侧车、图目录，并给 worker 留 tombstone。
     *
     * 只删文件不够 —— 队列里可能还有已接纳的取帧，它们会在删除之后才执行并
     * 重新创建目录、写回侧车。真机症状是「`/trace/clear` 返回 success，
     * 但磁盘上又长出一批图」。tombstone 让 `captureInner` 自己丢帧。
     */
    fun invalidate(ctx: Context, traceIds: List<String>) {
        if (traceIds.isEmpty()) return
        val now = System.currentTimeMillis()
        for (id in traceIds) {
            if (id.isBlank()) continue
            tombstones[id] = now
            perTrace.remove(id)
            latestAsked.remove(id)
            startAskedAt.remove(id)
        }
        runCatching {
            for (id in traceIds) {
                sidecarOf(ctx, id).delete()
                shotDirOf(ctx, id).deleteRecursively()
            }
            // tombstone 不能永久留着（那会变成内存里的垃圾清单）：30 秒后清一遍。
            // 30 秒足够覆盖任何还在队列里的取帧（一帧上限 SHOT_TIMEOUT_MS=1200ms，
            // 队列深度 3，极端情况 4 帧 ≈ 5 秒）。
            val expire = now - 30_000L
            tombstones.entries.removeIf { it.value < expire }
        }.onFailure { Log.w(TAG, "invalidate failed: " + it.message) }
    }

    /**
     * 清掉「目录名不匹配任何存活记忆 id」的图目录。
     *
     * 记忆被容量淘汰（PER_PKG=50 / GLOBAL=500）或 upsert 写盘失败时，图目录没人删：
     * 它既不属于任何记忆，也不在轨迹侧，`pruneOrphanTraces` 扫不到。
     * `enforceBudget` 只能按总量兜底，删谁与记忆存活无关（可能删掉活记忆的图、
     * 留下死记忆的图）。所以在每次入库收尾时按「存活 id 白名单」扫一遍。
     */
    fun sweepOrphanMemoryDirs(ctx: Context, liveIds: Collection<String>) {
        runCatching {
            val root = File(File(ctx.filesDir, MEM_PARENT), MEM_DIR)
            val dirs = root.listFiles { f -> f.isDirectory } ?: return
            for (d in dirs) {
                if (d.name !in liveIds) {
                    Log.i(TAG, "sweep orphan memory shot dir: " + d.name)
                    d.deleteRecursively()
                }
            }
        }.onFailure { Log.w(TAG, "sweepOrphanMemoryDirs failed: " + it.message) }
    }

    /**
     * 列出磁盘上**还留着图目录**的轨迹 id。
     *
     * 给 `/trace/clear` 用：它的 `listIds` 只看现存的 `.jsonl`，于是「轨迹主文件
     * 已被 TTL 剪掉、但图目录还赖在 `traces/shots/` 下」的孤儿清不掉 ——
     * 真机实测 clear 返回 `traces:1`，清完后 `traces/shots/` 里仍有两条旧目录。
     * 清空就该真清空，不该留两坨没人认领的图。
     */
    fun listTraceIdsWithShots(ctx: Context): List<String> {
        return shotsRoot(ctx).listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
    }

    /** 删一条记忆的全部图（记忆被删/被改造时调用）。不安全 id 直接忽略。 */
    fun deleteMemory(ctx: Context, memoryId: String) {
        val dir = memoryDirOf(ctx, memoryId) ?: return
        runCatching { dir.deleteRecursively() }
    }

    /** 清空全部记忆图（`/memory/clear` 用）。 */
    fun clearAll(ctx: Context) {
        runCatching { File(File(ctx.filesDir, MEM_PARENT), MEM_DIR).deleteRecursively() }
        perTrace.clear()
        // 只清 perTrace 会留下别的轨迹级状态：tombstones 里过期条目、
        // latestAsked 的旧步号。清空之后若同 id 轨迹再录，这些残留会让新帧被判 stale。
        latestAsked.clear()
        tombstones.clear()
        pending.clear()
        startAskedAt.clear()
    }

    /**
     * 轨迹清理的配套：轨迹被 TTL 删掉后，它的图与侧车也要走。
     *
     * 判据是「主文件还在不在」—— 不以侧车自己的时间为准，因为侧车没有独立的生命周期。
     */
    fun pruneOrphanTraces(ctx: Context) {
        runCatching {
            val tracesDir = File(ctx.filesDir, TraceContract.DIR)
            val alive = tracesDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
                ?.map { it.name.removeSuffix(".jsonl") }?.toHashSet() ?: hashSetOf()
            tracesDir.listFiles { f -> f.isFile && f.name.endsWith(StepShotLayout.SIDECAR_EXT) }
                ?.forEach { f ->
                    val id = f.name.removeSuffix(StepShotLayout.SIDECAR_EXT)
                    if (id !in alive) {
                        f.delete()
                        shotDirOf(ctx, id).deleteRecursively()
                        perTrace.remove(id)
                    }
                }
            // 图目录可能比侧车先被删（手工清理 / 旧版本），单独再扫一遍
            File(tracesDir, SHOT_SUBDIR).listFiles()?.forEach { d ->
                if (d.isDirectory && d.name !in alive) d.deleteRecursively()
            }
        }
    }

    /** 预算淘汰：按目录最后修改时间从旧到新删，直到降到 [MAX_TOTAL_BYTES] 以下。 */
    fun enforceBudget(ctx: Context, keepMemoryId: String = "") {
        runCatching {
            val root = File(File(ctx.filesDir, MEM_PARENT), MEM_DIR)
            val dirs = root.listFiles { f -> f.isDirectory } ?: return
            var total = 0L
            for (d in dirs) total += sizeOf(d)
            if (total <= MAX_TOTAL_BYTES) return
            // 从最旧（目录 mtime 最小）开始删，但**至少留一份**：
            // 一条图都没有的记忆比「图旧了」更让人困惑。
            val victims = dirs.filter { it.name != keepMemoryId }.sortedBy { it.lastModified() }
            var remain = dirs.size
            for (d in victims) {
                if (total <= MAX_TOTAL_BYTES || remain <= 1) break
                val sz = sizeOf(d)
                if (d.deleteRecursively()) {
                    total -= sz
                    remain--
                }
            }
            Log.i(TAG, "shot budget trimmed to " + total + " bytes, dirs=" + remain)
        }.onFailure { Log.w(TAG, "enforceBudget failed: " + it.message) }
    }

    private fun sizeOf(d: File): Long =
        d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 诊断：调用方可查「这次录制为什么没图」。 */
    fun stats(): String =
        "enabled=" + enabled + " dropped=" + dropped.get() + " traces=" + perTrace.size +
            " reasons=" + dropReasons.entries.joinToString(",") { it.key + ":" + it.value.get() }

    // ==================== 路径 ====================

    private const val SHOT_SUBDIR = "shots"

    /** 记忆图的根：`memory/v2/shots`。 */
    private const val MEM_PARENT = "memory/v2"

    private const val MEM_DIR = "shots"

    private const val STRIP_NAME = "strip.webp"

    private fun shotsRoot(ctx: Context): File =
        File(File(ctx.filesDir, TraceContract.DIR), SHOT_SUBDIR)

    private fun shotDirOf(ctx: Context, traceId: String): File =
        File(shotsRoot(ctx), traceId)

    private fun sidecarOf(ctx: Context, traceId: String): File =
        File(File(ctx.filesDir, TraceContract.DIR), traceId + StepShotLayout.SIDECAR_EXT)

    private fun relOf(ctx: Context, f: File): String =
        runCatching { f.absolutePath.removePrefix(ctx.filesDir.absolutePath + File.separator) }
            .getOrDefault("")
}

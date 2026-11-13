package app.dsh.mobile.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * 对话完成检测（v1.2.57）。
 *
 * ## 为什么需要
 * 用户反馈：「AI 完成任务之后，其实通知栏没有任何信息，能不能直接监测，
 * 只要有对话完成就弹出消息」。此前 `notify` 是**由 AI 主动调用**的，
 * AI 忘了调、或者没意识到任务已结束，用户就收不到任何提示。
 *
 * ## 信号来源（权威、非猜测）
 * dsh 的会话持久化把每轮对话写成 JSONL 事件流，其中 **`turn/end` 是官方定义的
 * 「本轮对话结束」事件**（见 `dsh-session-format-v3-to-v4` 的 RELATIONSHIP_TYPES：
 * `turn/start` / `turn/end` / `step/start` / `step/end` …）。
 * 位置：`$DSH_HOME/sessions/<projectKey>/<sessionId>/` 下的 jsonl 文件
 * （`dsh-base` 里 `session-persistence-jsonl` 的 `root: dshHomePath('sessions')`）。
 *
 * 比「AI 主动调 notify」可靠得多：不依赖模型是否记得，只要对话真的结束就会触发。
 *
 * ## 实现
 * 轻量轮询（默认 1.5s）：扫描 sessions 目录下最新的几个 .jsonl，
 * 记录每个文件的已读偏移，只解析新增的行，发现 `turn/end` 就回调。
 * 轮询而非 FileObserver：Android 上 FileObserver 对多层子目录不可靠，
 * 且引擎写入频繁；1.5s 粒度对「任务完成提示」完全够用，开销可忽略
 * （只读增量字节，不重复解析）。
 */
object TurnWatcher {

    private const val TAG = "TurnWatcher"

    /** 轮询间隔：够快（用户几乎无感）又不浪费（每次只读增量） */
    private const val POLL_MS = 1_500L

    /**
     * 同一个 zstd 会话两次解压之间的最小间隔。
     *
     * ## 为什么必须有（v1.6.0 性能回归）
     *
     * 引擎写会话是**持续的**：一个 turn 进行中每几百毫秒就可能追加一次。
     * 原来的去重键是 `lastModified xor (length shl 21)` —— 文件一动键就变，
     * 于是每 1.5 秒轮询到的每一次变化都会触发**整份会话重新解压**。
     *
     * 实测（真机 OnePlus PLZ110 / Android 16，29.5 MB 会话，同一份文件）：
     * ```
     * 1.4.76 路径：24 ms / 解压 0 MB    （帧扫描器漏跳过 XXH64 校验和 → 只认出 1 帧且解不开）
     * 1.6.0 路径：1317 ms / 解压 233 MB （帧扫描器修好后开始真解压）
     * ```
     * 也就是说这条开销在 1.4.76 里**从未真正发生**过，是 b87d7f62 修好帧扫描
     * 之后才存在的；4c576dfe 把明文挪出 Java 堆解决了 OOM，但每次仍是全解。
     *
     * 10 秒是折中：turn 结束后最多晚 10 秒弹通知（用户几乎无感），
     * 而一个持续几十秒的 turn 只会解压 2~3 次而不是 20 多次。
     */
    private const val MIN_DECOMPRESS_INTERVAL_MS = 10_000L

    /** 单次增量读取上限：防异常情况下一次读入巨大文件 */
    private const val MAX_CHUNK = 256 * 1024

    /**
     * 每隔多少轮重新走一次 sessions 目录。
     *
     * `walkTopDown(maxDepth = 4)` 在 508 个文件 / 524 个目录上是 24~44 ms
     * （真机实测），每 1.5 秒一次 ≈ 2% 单核常驻 —— 而且**全程零变化时也在跑**
     * （90 秒实测：文件变化 0 次，线程仍烧掉 1.76 秒 CPU）。
     * 改成每 5 轮（7.5 秒）重扫一次，其余轮次只 stat 上次拿到的几个文件：
     * 新会话最多晚 7.5 秒被发现（完成通知本身就有 1.5 秒轮询粒度），
     * 而常态目录遍历开销降到 1/5。
     */
    private const val DIR_SCAN_EVERY_POLLS = 5

    /** 每个文件记住已读到的字节偏移 */
    private val offsets = HashMap<String, Long>()

    /**
     * 每个 zstd 会话上次真正解压的时刻（[SystemClock.elapsedRealtime]）。
     *
     * 与 [zstdMtime] 的分工：那边判「有没有变」，这边判「变了但还不到时候」。
     * 用 elapsedRealtime 而不是 currentTimeMillis —— 后者会被用户改时钟/时区
     * 影响，一改就可能让节流窗意外变成几小时或负数。
     */
    private val lastDecompressAt = HashMap<String, Long>()

    /** 上一轮目录扫描拿到的会话文件（两次目录扫描之间只 stat 这几个） */
    private var cachedLogs: List<File> = emptyList()

    /** scan() 被调用的轮次，用于 [DIR_SCAN_EVERY_POLLS] 节流 */
    private var pollCount = 0L

    /** 是否已完成首次基线扫描（首次只记录偏移，不把历史对话当成新完成） */
    @Volatile private var primed = false

    @Volatile private var thread: Thread? = null

    @Volatile private var running = false

    /**
     * 启动监听。
     * @param ctx 用于定位 dsh-home
     * @param onTurnEnd 每检测到一次 turn/end 回调（在监听线程上，勿做重活）
     */
    fun start(ctx: Context, onTurnEnd: (sessionFile: String) -> Unit) {
        if (running) return
        running = true
        val appCtx = ctx.applicationContext
        thread = Thread({
            Log.i(TAG, "turn watcher started (poll ${POLL_MS}ms)")
            while (running) {
                try {
                    scan(appCtx, onTurnEnd)
                } catch (e: Exception) {
                    Log.w(TAG, "scan failed: ${e.message}")
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            Log.i(TAG, "turn watcher stopped")
        }, "dsh-turn-watcher").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** 重置基线（引擎重启后调用：把现有文件偏移当基线，不误报历史对话） */
    fun resetBaseline() {
        synchronized(offsets) { offsets.clear() }
        synchronized(zstdMtime) { zstdMtime.clear() }
        synchronized(zstdSeen) { zstdSeen.clear() }
        synchronized(lastDecompressAt) { lastDecompressAt.clear() }
        cachedLogs = emptyList()
        pollCount = 0L
        primed = false
    }

    private fun scan(ctx: Context, onTurnEnd: (String) -> Unit) {
        val root = File(EngineConfig.dshHome(ctx), "sessions")
        if (!root.isDirectory) return

        // 收集所有 .jsonl，按最后修改时间取最新的若干个（活跃会话必在其中）。
        //
        // ⚡ 目录遍历按 [DIR_SCAN_EVERY_POLLS] 节流：这是 508 个文件 / 524 个目录
        // 的全量 walk（真机 24~44 ms），每 1.5 秒一次就是 2% 单核常驻。两次扫描
        // 之间沿用上一轮的文件清单、只 stat 这几个 —— 新会话最多晚 7.5 秒被发现，
        // 而完成通知本身就有 1.5 秒轮询粒度，用户感知不到差别。
        pollCount++
        val needRescan = cachedLogs.isEmpty() || pollCount % DIR_SCAN_EVERY_POLLS == 1L
        if (needRescan) {
            cachedLogs = root.walkTopDown()
                .maxDepth(4)
                .filter {
                    // v1.2.105：放宽到同时收 .json.zstd（引擎两种落盘形态都要认）
                    it.isFile && (it.name.endsWith(".jsonl") || it.name.endsWith(".jsonl.zstd") ||
                        it.name.endsWith(".json.zstd"))
                }
                .sortedByDescending { it.lastModified() }
                .take(6)
                .toList()
        }
        val logs = cachedLogs

        // 首次扫描只建基线：避免把历史会话的 turn/end 当成「刚刚完成」
        if (!primed) {
            synchronized(offsets) {
                logs.forEach { offsets[it.absolutePath] = it.length() }
            }
            primed = true
            Log.i(TAG, "baseline primed: ${logs.size} session log(s)")
            return
        }

        logs.forEach { f ->
            val key = f.absolutePath
            val known = synchronized(offsets) { offsets[key] }
            val len = f.length()
            when {
                // 新文件：从 0 开始读（新会话的第一轮完成也要报）
                known == null -> readFrom(ctx, f, 0L, key, onTurnEnd)
                // 追加了内容
                len > known -> readFrom(ctx, f, known, key, onTurnEnd)
                // 文件被截断/重建（会话迁移）→ 重新建基线
                len < known -> synchronized(offsets) { offsets[key] = len }
            }
        }
    }

    /**
     * 从 [from] 读到文件尾，逐行找 `turn/end`，并更新偏移。
     *
     * 只认「事件类型字段精确等于 turn/end」的行：JSONL 每行是一个事件对象，
     * 形如 `{"type":"turn/end",...}`。用字符串包含判断会有误报风险
     * （比如 assistant 回复里提到这个词），故解析出 type 字段再比对。
     */
    private fun readFrom(ctx: Context, f: File, from: Long, key: String, onTurnEnd: (String) -> Unit) {
        var hits = 0
        var newOffset = from
        try {
            // ⚠️ v1.2.61 修复：引擎默认用 **zstd 压缩**写会话
            // （dsh-session-persistence-jsonl 的 DEFAULT_COMPRESSION = "zstd"，
            //  文件名是 `.jsonl.zstd`）。初版只找 `.jsonl` → 永远匹配不到文件 →
            // 对话完成通知从不触发（用户实测「还是没有接到系统通知」）。
            // zstd 是**帧压缩**：不能按字节偏移读增量。但仍然可以按**帧序号**做增量
            // —— 会话文件是追加写的，前缀帧不变，所以只解新增帧（见 [readZstd]）。
            //
            // ⚠️ 这里原来写着「只对最近 2 分钟内修改过的文件做解压」，但**没有
            // 任何代码实现这个判断** —— 那个 2 分钟窗口是注释里的想象，实际是
            // 「mtime 一变就全解」。本条注释已按实现改写，别再按旧说法推断开销。
            if (f.name.endsWith(".zstd")) {
                readZstd(ctx, f, key, onTurnEnd)
                return
            }
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(from)
                val avail = (raf.length() - from).coerceAtLeast(0L)
                val toRead = minOf(avail, MAX_CHUNK.toLong()).toInt()
                if (toRead <= 0) return
                val buf = ByteArray(toRead)
                raf.readFully(buf)
                val text = String(buf, Charsets.UTF_8)
                // 只处理完整行：末尾可能是半个 JSON（引擎正在写）
                val lastNewline = text.lastIndexOf('\n')
                if (lastNewline < 0) return          // 没有完整行，等下次
                val complete = text.substring(0, lastNewline)
                // 偏移推进到最后一个完整行之后（按**字节**算，中文会差字符数）
                newOffset = from + complete.toByteArray(Charsets.UTF_8).size + 1

                complete.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    if (isTurnEnd(line)) hits++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "read ${f.name} failed: ${e.message}")
            return
        }
        synchronized(offsets) { offsets[key] = newOffset }
        repeat(hits) { onTurnEnd(f.name) }
    }


    /**
     * 已处理过的 zstd 会话快照（路径 → [ZstdTail.Stats]）。
     *
     * 原来是 `HashMap<String, Int>`（解压后**字符数**），改成 `Bytes + turnEnds`
     * 两个数是因为 issue #104：明文不能进 Java 堆，只能拿 Node 侧算好的统计。
     * v1.6.0 性能修复又加了 **frames** —— 它才是现在的增量基准：下一轮只解
     * `[frames, 现在的帧数)` 这一段。bytes/turnEnds 只剩诊断价值。
     */
    private val zstdSeen = HashMap<String, ZstdTail.Stats>()

    /** zstd 文件的 mtime+大小 快照（没变化就不解压 —— 解一次 29.5 MB 会话要 1.8 秒 / 799 MB 峰值） */
    private val zstdMtime = HashMap<String, Long>()

    /**
     * zstd 会话文件的完成检测。
     *
     * zstd 是帧压缩，无法按**字节**偏移增量读，但可以按**帧序号**增量读：
     * 会话文件是追加写的，已经处理过的前缀帧内容不变，所以只解新增的那几帧。
     *
     * 三道闸（从便宜到贵，前一道挡住就不进下一道）：
     * 1. `mtime xor (length shl 21)` 没变 → 直接返回；
     * 2. 变了但距上次解压不足 [MIN_DECOMPRESS_INTERVAL_MS] → 返回（下轮再解，不丢）；
     * 3. 过了时间闸 → 只解 `[上次帧数, 现在帧数)` 这一段。
     *
     * ## 为什么不拿明文了（issue #104）
     *
     * 原来这里 `ZstdTail.read(ctx, f, keepFrames = 0)` 把解压后的**完整明文**
     * 读进 Java 堆，再 `text.length` 去重、`delta.lineSequence()` 数 turn/end。
     * 而 `readBytes()` 没有上限，且 `ByteArrayOutputStream` 按 2 倍扩容：
     *
     * ```
     * 压缩后   38.38 MB（21,788 帧）
     * 解压后  128.3 MB
     * 扩容申请 256 MB，402 MB 堆里只余 163 MB
     * FATAL EXCEPTION: dsh-turn-watcher
     * java.lang.OutOfMemoryError: Failed to allocate a 268435472 byte allocation
     *     at ZstdTail.read(ZstdTail.kt:146)
     * ```
     *
     * 但这里真正需要的只有两个数：**解压后有多长**、**新增部分里几个 turn/end**。
     * 两者都交给 Node 子进程算，回传几十字节 JSON —— Java 侧内存占用与会话
     * 大小彻底解耦（38 MB 与 380 MB 的会话都只占几十字节）。
     *
     * v1.6.0 起 `turnEnds` 不再是累计值：脚本按帧序号只解新增帧，回传的就是
     * 「这几帧里有几个 turn/end」。这比原来「拿明文切片数行」更稳：切片会跨行
     * 截断（prevLen 落在某行中间时半行会被当成新行的一部分，`isTurnEnd` 失真），
     * 而按帧切只在帧边界上切，配合脚本内的 carrier 预置还不会漏跨帧 token。
     */
    private fun readZstd(ctx: Context, f: File, key: String, onTurnEnd: (String) -> Unit) {
        // ⚡ 第一道闸：文件没更新就不解压（活跃会话少则几十秒写一次）。
        //
        // ⚠️ 这里**只读不写** `zstdMtime`：写成「先写再比」会让第二道闸提前
        // return 时把这次变化永久吞掉 —— 下一轮 prev 已经等于 stamp，第一道闸
        // 就把它挡掉了，那个 turn/end 通知再也不会来。所以 stamp 在真正解压
        // 之后才落库（见函数末尾）。
        val stamp = f.lastModified() xor (f.length() shl 21)
        val prev = synchronized(zstdMtime) { zstdMtime[key] }
        if (prev != null && prev == stamp) return

        // ⚡ 第二道闸（v1.6.0 性能回归的修复）：变了也不一定现在就解。
        //
        // 引擎在 turn 进行中每几百毫秒就追加一次，「变了就解」等于把整份会话
        // 反复全解（29.5 MB 会话一次 1317 ms / 233 MB 明文，见常量注释）。
        // 这里给一个最小间隔；窗口内直接返回，**不更新 stamp**，所以下一轮
        // poll 看到的变化仍在，会再进来真正解压 —— 最多晚一个窗口，不丢事件。
        val now = SystemClock.elapsedRealtime()
        val lastAt = synchronized(lastDecompressAt) { lastDecompressAt[key] }

        // 首次见到这个文件：只数帧、不解压（fromFrames = -1）。
        // 那一步只需要知道「现在有几帧」当基线，把几百 MB 明文解一遍纯浪费。
        val prevStats = synchronized(zstdSeen) { zstdSeen[key] }
        val fromFrames = prevStats?.frames ?: -1

        // 基线（本次不解压）不受时间闸限制：它只扫帧头，实测 29.5 MB 会话 0.23 秒，
        // 却能让「建基线 → 真正解第一段」从「再等 10 秒」变成「下一轮就解」——
        // 引擎刚重启时用户最可能立刻问一句话，那时不该多等一个窗口。
        // 只在**第一次**见到该文件时豁免（prevStats 与 lastAt 都为空）；一旦
        // 落过账，后面的失败重试仍受闸门约束，避免坏文件每 1.5 秒被重扫一遍。
        val baseline = prevStats == null && lastAt == null
        if (!baseline && lastAt != null && now - lastAt < MIN_DECOMPRESS_INTERVAL_MS) return
        val (stats, why) = ZstdTail.stats(ctx, f, fromFrames = fromFrames)
        // 无论成败都在这里落两个记账：stamp 表示「这份内容已处理过」，
        // lastDecompressAt 表示「刚花过钱」。失败也落，否则一个坏文件会每
        // 1.5 秒被重试一次 —— 解压成本与失败重试叠加更糟。
        synchronized(zstdMtime) { zstdMtime[key] = stamp }
        // 基线不解压，也就不该占掉一个节流窗口（见上面的 baseline 说明）。
        if (!baseline) synchronized(lastDecompressAt) { lastDecompressAt[key] = now }
        if (stats == null) {
            // 解不开不是致命错误：内容再变（stamp 变了）时会再试。但必须留痕，
            // 否则又变成「什么都不发生」的静默失败。
            if (why != null) Log.w(TAG, "zstd stats failed for ${f.name}: $why")
            return
        }
        if (prevStats == null) {
            // 基线只记帧数，bytes/turnEnds 都是 0（本次没解压）。
            synchronized(zstdSeen) { zstdSeen[key] = stats }
            return
        }
        // 帧数回落 = 文件被重建（会话迁移/压缩比异常）→ 重建基线，不上报。
        if (stats.frames < prevStats.frames) {
            synchronized(zstdSeen) { zstdSeen[key] = stats }
            return
        }
        synchronized(zstdSeen) { zstdSeen[key] = stats }

        // 增量模式下 turnEnds 已经是「本次新增帧里的」个数，直接用它。
        if (stats.turnEnds <= 0) return
        repeat(stats.turnEnds) { onTurnEnd(f.name) }
    }


    /**
     * 该行是否为 `turn/end` 事件。
     * 用极简解析（不引 JSON 库，避免异常）：找 `"type"` 后紧跟的值。
     * 兼容 `"type":"turn/end"` 与带空格的写法。
     */
    private fun isTurnEnd(line: String): Boolean {
        val i = line.indexOf("\"type\"")
        if (i < 0) return false
        val rest = line.substring(i + 6)
        val colon = rest.indexOf(':')
        if (colon < 0) return false
        val after = rest.substring(colon + 1).trimStart()
        if (!after.startsWith("\"")) return false
        val end = after.indexOf('"', 1)
        if (end < 0) return false
        return after.substring(1, end) == "turn/end"
    }
}

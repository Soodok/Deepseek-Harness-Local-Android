package app.dsh.mobile.engine

import android.content.Context
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
 * "本轮对话结束"事件**（见 `dsh-session-format-v3-to-v4` 的 RELATIONSHIP_TYPES：
 * `turn/start` / `turn/end` / `step/start` / `step/end` …）。
 * 位置：`$DSH_HOME/sessions/<projectKey>/<sessionId>/` 下的 jsonl 文件
 * （`dsh-base` 里 `session-persistence-jsonl` 的 `root: dshHomePath('sessions')`）。
 *
 * 比"AI 主动调 notify"可靠得多：不依赖模型是否记得，只要对话真的结束就会触发。
 *
 * ## 实现
 * 轻量轮询（默认 1.5s）：扫描 sessions 目录下最新的几个 .jsonl，
 * 记录每个文件的已读偏移，只解析新增的行，发现 `turn/end` 就回调。
 * 轮询而非 FileObserver：Android 上 FileObserver 对多层子目录不可靠，
 * 且引擎写入频繁；1.5s 粒度对"任务完成提示"完全够用，开销可忽略
 * （只读增量字节，不重复解析）。
 */
object TurnWatcher {

    private const val TAG = "TurnWatcher"

    /** 轮询间隔：够快（用户几乎无感）又不浪费（每次只读增量） */
    private const val POLL_MS = 1_500L

    /** 单次增量读取上限：防异常情况下一次读入巨大文件 */
    private const val MAX_CHUNK = 256 * 1024

    /** 每个文件记住已读到的字节偏移 */
    private val offsets = HashMap<String, Long>()

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
        primed = false
    }

    private fun scan(ctx: Context, onTurnEnd: (String) -> Unit) {
        val root = File(EngineConfig.dshHome(ctx), "sessions")
        if (!root.isDirectory) return

        // 收集所有 .jsonl，按最后修改时间取最新的若干个（活跃会话必在其中）
        val logs = root.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .sortedByDescending { it.lastModified() }
            .take(6)
            .toList()

        // 首次扫描只建基线：避免把历史会话的 turn/end 当成"刚刚完成"
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
                known == null -> readFrom(f, 0L, key, onTurnEnd)
                // 追加了内容
                len > known -> readFrom(f, known, key, onTurnEnd)
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
    private fun readFrom(f: File, from: Long, key: String, onTurnEnd: (String) -> Unit) {
        var hits = 0
        var newOffset = from
        try {
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

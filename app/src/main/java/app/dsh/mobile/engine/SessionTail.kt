package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 会话事件尾部读取（v1.2.99）—— 悬浮条的数据源。
 *
 * ## 为什么需要
 * 悬浮条要显示「AI 正在说什么 / 正在调什么工具」，需要读取活跃会话的最新事件。
 * 上游 dsh 把每个事件 append 成 JSONL 的一行（见 dsh-agent-loop 源码）：
 *
 * ```js
 * session.append("tool/call",   { turn, step, callId, name, arguments })
 * session.append("tool/result", { turn, step, message, error?, meta? })
 * session.append("turn/end",    { … })
 * ```
 *
 * 事件类型全集（从 `@deepseek-ai/dsh-agent-loop/lib/index.js` 提取）：
 * `agent/assistant-stream` `agent/status` `agent/error` `assistant/message`
 * `tool/call` `tool/result` `turn/start` `turn/end` `step/start` `step/end` …
 *
 * ## ⚠️ 为什么不能增量读（关键约束）
 * 引擎默认 **zstd 压缩**写会话（`dsh-session-persistence-jsonl` 的
 * DEFAULT_COMPRESSION = "zstd"，文件名 `.jsonl.zstd`）。zstd 是**帧压缩**：
 * 追加会让整个文件重新压缩，**无法按字节偏移读增量** —— 只能整体解压后取尾部。
 * 因此本类做了两层节流：
 *   ① 只有 mtime 在 [RECENT_MS] 内的文件才解析（活跃会话）
 *   ② 同一 mtime 不重复解析（缓存）
 *
 * 解压用引擎自带的 node（`zstdDecompressSync`），与 SessionReader/TurnWatcher 同方案。
 */
object SessionTail {

    private const val TAG = "SessionTail"

    /**
     * 活跃会话的时间窗（v1.2.101：10 分钟 → 2 小时）。
     *
     * 旧值太严：用户思考、查资料、等模型长时间推理时，会话文件可能十几分钟没有新事件，
     * 于是被判「没有活跃会话」→ 悬浮条内容被清空（主人实测「输出不了内容」的一种成因）。
     * 放宽到 2 小时，覆盖绝大多数交互间隔；真正的旧会话不会干扰（取的是 mtime 最新者）。
     */
    private const val RECENT_MS = 2 * 60 * 60_000L

    /**
     * 会话文件的最大搜索深度（v1.2.101）。
     * 路径形如 `sessions/<projectKey>/<sessionId>/session.v4.jsonl.zstd` = 3 层；
     * 留一层余量给 projectKey 含子路径的情况。深度不足会**静默漏掉**会话
     * （旧实现用 maxDepth(4) 尚可，但异常被吞，问题不可见）。
     */
    private const val MAX_DEPTH = 5

    /** 解压后只保留尾部这么多字节（事件行按行取，避免整文件解析） */
    private const val TAIL_BYTES = 256 * 1024

    /** 悬浮条最多展示的工具调用条数 */
    private const val MAX_TOOLS = 3

    /** 最近一次解析结果（供悬浮条读取） */
    data class Snapshot(
        /** AI 当前/最近一段输出（已裁剪） */
        val assistantText: String = "",
        /** 最近的工具调用（人话描述，倒序：最新在前） */
        val toolLines: List<String> = emptyList(),
        /** 是否正在跑（有 turn/start 但还没 turn/end） */
        val running: Boolean = false,
        /** 解析时间戳 */
        val at: Long = 0L,
    ) {
        val isEmpty: Boolean get() = assistantText.isBlank() && toolLines.isEmpty()
    }

    @Volatile
    private var cached: Snapshot = Snapshot()

    /** 上次解析的文件 mtime（同一 mtime 不重复解压） */
    @Volatile
    private var lastMtime: Long = -1L

    /** 最近快照（不触发解析） */
    fun latest(): Snapshot = cached

    /**
     * 刷新快照（调用方在后台线程按需调用，建议间隔 ≥1.5s）。
     * @return 最新快照
     */
    fun refresh(ctx: Context): Snapshot {
        // ⚠️ v1.2.101：旧实现连续调了两次 activeSessionFile()（调试残留），
        // 每次都 walkTopDown 整棵树 —— 轮询 2 秒一次，白耗 IO。
        val file = activeSessionFile(ctx)
        if (file == null) {
            // 找不到活跃会话：**清空快照**而不是保留旧内容。
            // 旧实现直接 return cached，用户看到的可能是几分钟前的陈旧输出。
            if (!cached.isEmpty) cached = Snapshot()
            return cached
        }
        val mtime = file.lastModified()
        if (mtime == lastMtime) return cached          // 没变化，省掉解压
        val text = readTail(ctx, file) ?: return cached
        lastMtime = mtime
        val snap = parse(text)
        cached = snap
        return snap
    }

    /**
     * 找最近修改的活跃会话文件。
     *
     * ## ⚠️ v1.2.101 修复「悬浮条永远没内容」的真根因
     * 旧实现把整段遍历包在 `runCatching{...}.getOrNull()` 里 —— **异常被静默吞掉**，
     * 无论什么原因失败（目录不可读、软链悬空、权限拒绝）都返回 null，
     * 而且**连一行日志都没有**，现场完全无法诊断（主人实测：悬浮条一直空白）。
     *
     * 现在分三段做，各自记录失败原因：
     *  ① 根目录存在性与可读性
     *  ② 遍历**逐个目录容错**（单目录不可读不应毁掉整次扫描；walkTopDown 一处失败会中断整条流）
     *  ③ 无匹配时打印根目录内容，便于区分「没会话」还是「读不到」
     */
    private fun activeSessionFile(ctx: Context): File? {
        val root = File(EngineConfig.dshHome(ctx), "sessions")
        if (!root.isDirectory) {
            Log.i(TAG, "sessions root missing: ${root.absolutePath}")
            return null
        }
        // 诊断（v1.2.101）：把「路径 / 是否软链 / 能否列目录」一次打全 ——
        // 主人实测「悬浮条输出不了内容」时，就是靠这三项区分出
        // 「软链建了但 App 穿过去读不到」的（shell 视角正常、App 视角 Permission denied）。
        runCatching {
            val isLink = java.nio.file.Files.isSymbolicLink(root.toPath())
            val real = runCatching { root.canonicalPath }.getOrNull()
            val entries = runCatching { root.list()?.size }.getOrNull()
            Log.i(TAG, "sessions root: link=$isLink real=$real entries=${entries ?: "DENIED"}")
        }
        if (!root.canRead()) {
            Log.w(TAG, "sessions root not readable: ${root.absolutePath}")
            return null
        }
        val now = System.currentTimeMillis()
        val found = ArrayList<File>()
        collectSessionFiles(root, 0, now, found)
        if (found.isEmpty()) {
            val sample = runCatching { root.list()?.take(5)?.joinToString() }.getOrNull()
            Log.i(TAG, "no session file in window; root entries=[${sample ?: "unreadable"}]")
            return null
        }
        return found.maxByOrNull { it.lastModified() }
    }

    /** 递归收集（最多 [MAX_DEPTH] 层）；**单目录失败不中断**，只记录 */
    private fun collectSessionFiles(dir: File, depth: Int, now: Long, out: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull()
        if (children == null) {
            Log.i(TAG, "skip unreadable dir: ${dir.absolutePath}")
            return
        }
        for (f in children) {
            if (f.isFile && (f.name.endsWith(".jsonl") || f.name.endsWith(".jsonl.zstd"))) {
                if (now - f.lastModified() < RECENT_MS) out.add(f)
                continue
            }
            if (f.isDirectory) collectSessionFiles(f, depth + 1, now, out)
        }
    }

    /** 解压并取尾部（zstd 无法增量读，只能整体解压后截尾） */
    private fun readTail(ctx: Context, f: File): String? {
        val raw = if (f.name.endsWith(".zstd")) decompressZstd(ctx, f) else runCatching {
            f.readText()
        }.getOrNull()
        if (raw.isNullOrEmpty()) return null
        // 只保留尾部：事件是按时间追加的，最新内容在末尾
        return if (raw.length > TAIL_BYTES) raw.substring(raw.length - TAIL_BYTES) else raw
    }

    /**
     * 用引擎自带的 node 解压 zstd。
     *
     * ⚠️ **必须显式设置 LD_LIBRARY_PATH**（实测踩坑）：引擎 node 是动态链接的
     * bionic 二进制，需要 `lib/libz.so.1` 等库。本方法在**无障碍服务进程**里执行，
     * 该进程环境**没有**引擎的库路径（SessionReader 能用是因为它由引擎进程派生、
     * 继承了环境）。缺了 LD_LIBRARY_PATH 时 node 直接链接失败退出，
     * 表现为「解压永远失败、悬浮条空白」。
     */
    private fun decompressZstd(ctx: Context, f: File): String? {
        val node = findNode(ctx) ?: return null
        return try {
            val script = "const z=require('node:zlib'),fs=require('node:fs');" +
                "const b=z.zstdDecompressSync(fs.readFileSync(process.argv[1]));" +
                "process.stdout.write(b);"
            val root = EngineConfig.engineRoot(ctx)
            val pb = ProcessBuilder(node, "-e", script, f.absolutePath)
            pb.environment()["LD_LIBRARY_PATH"] =
                "${root.absolutePath}/lib:${root.absolutePath}/usr/lib"
            pb.environment()["HOME"] = EngineConfig.dshHome(ctx).absolutePath
            val p = pb.start()
            val out = p.inputStream.readBytes()
            val err = p.errorStream.readBytes()
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroy(); return null
            }
            if (p.exitValue() != 0) {
                Log.w(TAG, "node decompress exit=${p.exitValue()}: ${String(err).take(200)}")
                return null
            }
            String(out, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decompress failed: ${e.message}"); null
        }
    }

    private fun findNode(ctx: Context): String? {
        val bin = EngineConfig.nodeBin(ctx)
        return if (bin.canExecute()) bin.absolutePath else null
    }

    /**
     * 解析事件尾部 → 快照。
     *
     * 逐行 JSON；只认我们关心的类型，其余跳过（未知类型不能报错，上游会加新事件）。
     */
    private fun parse(text: String): Snapshot {
        var assistant = ""
        var running = false
        val tools = ArrayDeque<String>()   // 最新在前

        text.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.isEmpty() || !l.startsWith("{")) return@forEach
            runCatching {
                val o = JSONObject(l)
                when (o.optString("type")) {
                    // AI 输出。⚠️ v1.2.100 修正（独立审查发现）：
                    // `agent/assistant-stream` 只是 agent-loop 的**进程内 dispatch 事件**，
                    // **不写会话文件**；真正落盘的是 `assistant/message`（每轮结束一条）。
                    // 所以悬浮条实际是「逐轮刷新最新全文」，不是逐字流式 —— 保留前者兼容
                    // （万一上游改了持久化策略），但主路径靠 assistant/message。
                    "agent/assistant-stream", "assistant/message" -> {
                        val t = extractText(o)
                        if (t.isNotBlank()) assistant = t
                    }
                    // 工具调用：翻译成人话（tool/call 与 tool/result 成对）
                    "tool/call" -> {
                        val name = o.optString("name")
                        if (name.isNotBlank()) {
                            // ⚠️ v1.2.100：`arguments` 是 **JSON 字符串**（上游
                            // dsh-session-format 用 stringValue() 校验），不是嵌套对象。
                            // 旧代码用 optJSONObject 取值 → 永远 null → 工具行退化成
                            // 纯工具名、参数详情显示不出来（审查发现）。
                            tools.addFirst(humaniseTool(name, parseArgs(o.opt("arguments"))))
                            while (tools.size > MAX_TOOLS) tools.removeLast()
                        }
                    }
                    "turn/start" -> running = true
                    "turn/end" -> running = false
                    // `agent/error` 同样不落盘（dispatch 事件）——保留分支以兼容，
                    // 实际错误信息会体现在 assistant/message 或会话日志里。
                    "agent/error" -> {
                        val m = o.optString("message").ifBlank { "agent error" }
                        tools.addFirst(m.take(60))
                        while (tools.size > MAX_TOOLS) tools.removeLast()
                    }
                }
            }
        }
        return Snapshot(
            assistantText = assistant.trim().take(300),
            toolLines = tools.toList(),
            running = running,
            at = System.currentTimeMillis(),
        )
    }

    /**
     * 解析 `tool/call` 的 arguments（v1.2.100）。
     *
     * 上游把它存成 **JSON 字符串**（`stringValue(data["arguments"])` 校验），
     * 但也可能是对象（不同版本/写入路径）。两种都接住，解析失败返回 null。
     */
    private fun parseArgs(raw: Any?): JSONObject? = when (raw) {
        null -> null
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrNull()
        else -> null
    }

    /** 从事件对象里挖文本（不同事件把内容放在不同字段） */
    private fun extractText(o: JSONObject): String {
        // 顶层直接带 text（如 agent/assistant-stream）
        o.optString("text").takeIf { it.isNotBlank() }?.let { return it }

        // message.content：**可能是字符串，也可能是数组**。
        // ⚠️ v1.2.101 修（实测踩坑）：旧代码先 `msg.optString("content")` ——
        // 当 content 是数组时 optString 会返回**整段 JSON 文本**
        // （`[{"type":"text","text":"…"}]`），于是提前 return，永远走不到数组解析，
        // 悬浮条上显示的就是这坨原始 JSON（主人实测「输出不了内容」的真凶之一）。
        // 现在先判类型，数组走逐元素提取。
        val msg = o.optJSONObject("message")
        if (msg != null) {
            // 数组优先（0.2.0 的 assistant/message 就是数组形态）
            msg.optJSONArray("content")?.let { arr ->
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    when (val part = arr.opt(i)) {
                        is JSONObject -> part.optString("text").takeIf { it.isNotBlank() }?.let { sb.append(it) }
                        is String -> if (part.isNotBlank()) sb.append(part)
                    }
                }
                if (sb.isNotBlank()) return sb.toString()
            }
            // 字符串形态
            msg.opt("content").let { c ->
                if (c is String && c.isNotBlank()) return c
            }
        }

        // 顶层 content 同理（兜底）
        o.optJSONArray("content")?.let { arr ->
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                (arr.opt(i) as? JSONObject)?.optString("text")?.takeIf { it.isNotBlank() }?.let { sb.append(it) }
            }
            if (sb.isNotBlank()) return sb.toString()
        }
        o.opt("content").let { c ->
            if (c is String && c.isNotBlank()) return c
        }
        return ""
    }

    /**
     * 工具调用的可读描述（悬浮条展示）。
     *
     * 直接沿用上游工具名（bash / read / edit …）—— 与 WebUI 里 AI 自称的工具名一致，
     * 用户对照得上；只把关键参数摘出来，避免整段 JSON 糊在悬浮条上。
     */
    private fun humaniseTool(name: String, args: JSONObject?): String {
        fun arg(vararg keys: String): String {
            if (args == null) return ""
            for (k in keys) {
                val v = args.optString(k)
                if (v.isNotBlank()) return v.replace('\n', ' ').take(48)
            }
            return ""
        }
        val detail = when (name) {
            "bash", "shell" -> arg("command", "cmd")
            "read", "read_file", "write", "write_file", "edit", "edit_file" ->
                arg("file_path", "path")
            "glob", "list", "ls" -> arg("pattern", "path")
            "grep", "search" -> arg("pattern", "query")
            "webfetch", "fetch", "web_fetch" -> arg("url")
            "websearch", "web_search" -> arg("query")
            else -> arg("path", "query", "command", "url")
        }
        return if (detail.isBlank()) name else "$name · $detail"
    }
}

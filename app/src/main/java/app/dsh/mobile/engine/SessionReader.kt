package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 会话读取（v1.2.65）。
 *
 * ## 为什么直接读文件而不是引擎 API
 * 实测引擎 HTTP API 需要 token 且会话列表路径未公开（`/api/sessions` → 401），
 * 而会话持久化格式是稳定的（`dsh-session-format-v3-to-v4`）：
 *   `$DSH_HOME/sessions/<projectKey>/<sessionId>/session.v4.jsonl.zstd`
 * `TurnWatcher` 已在用同一条路径 + node `zstdDecompressSync` 解压，这里复用该方案。
 *
 * ## 用途
 *  · 语音发送：定位「最后活跃会话」，把识别文本发给它
 *  · 悬浮窗第三段：列出活跃会话（名称 / 最后活跃时间 / AI 在做什么）
 */
object SessionReader {

    private const val TAG = "SessionReader"

    /** 一个会话的摘要信息 */
    data class SessionInfo(
        /** 会话 id（目录名，形如 session-<uuid>） */
        val id: String,
        /** 会话文件 */
        val file: File,
        /** 最后修改时间（毫秒） */
        val lastActiveAt: Long,
        /** 最后一条用户消息（截断），可能为空 */
        val lastUserText: String = "",
        /** 最后一条助手消息（截断），可能为空 */
        val lastAssistantText: String = "",
        /**
         * 对话名 —— 取**第一条用户消息**（像聊天软件的会话标题）。
         * 为空时调用方应退回显示 id 尾号。
         * 主人实测反馈：「对话名字还都是 16 进制数，我根本不知道哪个对话是哪个」——
         * 光有 `2a1059c6` 这种 id 尾号是无法辨认的。
         */
        val title: String = "",
        /** 会话是否还是空白（引擎 sessionListMetadata.blank）——空白会话不入列表 */
        val blank: Boolean = false,
    )

    /**
     * 标题缓存：file.path -> (lastModified, title)。
     * 取标题要解压 + 逐行解析（会起一个 node 进程，约 100-300ms），
     * 悬浮窗每次展开都重算会明显卡顿，故按 mtime 缓存。
     */
    private val titleCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** 只取标题（带缓存），供悬浮窗列表这类高频调用 */
    fun titleOf(ctx: Context, info: SessionInfo): String {
        // 投影缓存里已带标题 → 直接用（旧实现每次都要解压 zstd，慢且经常解不出）
        if (info.title.isNotBlank()) return info.title
        val key = info.file.path
        titleCache[key]?.let { (mtime, t) -> if (mtime == info.lastActiveAt) return t }
        val title = loadDetail(ctx, info).title
        titleCache[key] = info.lastActiveAt to title
        return title
    }

    /** 会话根目录（旧路径：zstd 事件流，**只在建会话时写一次**） */
    private fun sessionsRoot(ctx: Context): File =
        File(EngineConfig.dshHome(ctx), "sessions")

    /**
     * 引擎的会话**投影缓存**目录 —— 这才是真正随活动更新的数据源。
     *
     * 🔴 主人实测反馈「我明明现在有对话在活跃，上面仍显示三个小时前」的根因：
     * 旧实现读 `sessions/<proj>/<id>/session.v4.jsonl.zstd` 的 mtime，实测该文件
     * **只写一次**（模拟器上 434 字节、mtime 停在建会话的 11:46），而引擎把真正的
     * 会话状态写在 `storages/session_projcache/sessions/<id>.json`（明文 JSON，
     * 随活动更新到 16:35）。里面还直接带 `title` / `titleInput.first` /
     * `sessionListMetadata.lastPromptAt` —— 名字与活跃时间都不用再解压 zstd 猜。
     */
    private fun projCacheDir(ctx: Context): File =
        File(EngineConfig.dshHome(ctx), "storages/session_projcache/sessions")

    /** 从投影缓存里取出来的会话元信息 */
    private data class ProjMeta(
        val title: String = "",
        val firstPrompt: String = "",
        val lastPromptAt: Long = 0L,
        val blank: Boolean = true,
        val turns: Int = 0,
    )

    /** 解析会话投影缓存（4KB 级明文 JSON，比解压 zstd 便宜得多） */
    private fun readProjMeta(f: File): ProjMeta = runCatching {
        val o = JSONObject(f.readText())
        val rows = o.optJSONObject("record")?.optJSONObject("rows") ?: return@runCatching ProjMeta()
        fun rowVal(key: String): JSONObject? = rows.optJSONObject(key)?.optJSONObject("val")
        val titleVal = rows.optJSONObject("title")?.opt("val")?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
        val firstPrompt = rowVal("titleInput")?.optString("first").orEmpty()
        val listMeta = rowVal("sessionListMetadata")
        val stats = rowVal("sessionStats")
        ProjMeta(
            title = titleVal ?: firstPrompt,
            firstPrompt = firstPrompt,
            lastPromptAt = listMeta?.optLong("lastPromptAt", 0L) ?: 0L,
            blank = listMeta?.optBoolean("blank", true) ?: true,
            turns = stats?.optInt("turns", 0) ?: 0,
        )
    }.getOrDefault(ProjMeta())

    /**
     * 找到某会话的**磁盘目录**（`sessions/<projectKey>/<sessionId>/`）。
     * 租约文件与事件流都在这里，用于取真实活跃时间。
     */
    private fun sessionDirFor(ctx: Context, id: String): File? {
        val root = sessionsRoot(ctx)
        if (!root.isDirectory) return null
        root.listFiles()?.forEach { proj ->
            if (!proj.isDirectory) return@forEach
            val d = File(proj, id)
            if (d.isDirectory) return d
        }
        return null
    }

    /**
     * 会话的**真实最后活跃时间**：多源取最大。
     *
     * 🔴 主人实测「刚完成对话，悬浮窗还显示 14 个小时前」的根因：
     * 之前只取 `session.v4.jsonl.zstd` 的 mtime 或投影缓存的 `lastPromptAt`，
     * 两者都**不随对话更新**（实测该会话：zstd 停在 11:46、lastPromptAt 为 null；
     * 而 14 小时 = 创建时间 11:46 → 当时 01:5x 的差值，等于退回了创建时间）。
     *
     * 依据（引擎源码 dsh-session-persistence-jsonl/lib/index.js）：
     * `session.lock` 是**跨进程写所有权租约**，在 write-open 时获取、会话被写入期间持有 ——
     * 它的 mtime 精确反映"该会话最后一次被写入"的时刻，是磁盘上最可靠的活跃信号。
     */
    private fun lastActiveOf(ctx: Context, id: String, projFile: File, meta: ProjMeta): Long {
        var t = 0L
        sessionDirFor(ctx, id)?.let { dir ->
            dir.listFiles()?.forEach { f ->
                // ① 权威：租约文件（会话每次被写入都会刷新）
                // ② 事件流文件（内容增长即活跃）
                if (f.name == "session.lock" || f.name.startsWith("session.")) {
                    if (f.lastModified() > t) t = f.lastModified()
                }
            }
        }
        // ③ 引擎记录的提问时间（若有）
        if (meta.lastPromptAt > t) t = meta.lastPromptAt
        // ④ 投影缓存文件本身
        if (projFile.lastModified() > t) t = projFile.lastModified()
        return t
    }

    /**
     * 列出所有会话，按最后活跃时间倒序。
     *
     * 🔴 枚举源改为 **sessions 目录**（而不是投影缓存目录）：
     * 投影缓存是 `throttled write-behind`（引擎源码 dsh-session-projection-cache 原话），
     * **磁盘版本天然滞后**；而 sessions 下的租约/事件流文件是随写随更新的。
     * 以 sessions 为准枚举，就不会因为"缓存还没落盘"而漏掉刚活跃的会话。
     *
     * 标题仍从投影缓存取（那里有引擎算好的 title），取不到再退回首条用户消息。
     */
    fun list(ctx: Context): List<SessionInfo> {
        val root = sessionsRoot(ctx)
        if (!root.isDirectory) return emptyList()
        val cache = projCacheDir(ctx)
        val out = ArrayList<SessionInfo>()
        root.walkTopDown().maxDepth(2).forEach { sess ->
            if (!sess.isDirectory) return@forEach
            val files = sess.listFiles() ?: return@forEach
            // 事件流文件（可能 .jsonl 或 .jsonl.zstd）
            val eventFile = files.firstOrNull {
                it.name.startsWith("session.") &&
                    (it.name.endsWith(".jsonl") || it.name.endsWith(".jsonl.zstd"))
            } ?: return@forEach
            val id = sess.name
            // 活跃时间：租约 / 事件流 / 目录，三者取最大（都随写更新）
            var t = eventFile.lastModified()
            files.forEach { f ->
                if (f.name == "session.lock" && f.lastModified() > t) t = f.lastModified()
            }
            if (sess.lastModified() > t) t = sess.lastModified()
            // 标题 + 空白标记：从投影缓存拿（有就更好，没有也不影响时间）
            val projFile = File(cache, "$id.json")
            val meta = if (projFile.isFile) readProjMeta(projFile) else ProjMeta()
            if (meta.lastPromptAt > t) t = meta.lastPromptAt
            out += SessionInfo(
                id = id,
                file = eventFile,
                lastActiveAt = t,
                title = meta.title.replace('\n', ' ').trim().take(24),
                blank = meta.blank && meta.turns == 0,
            )
        }
        return out.sortedByDescending { it.lastActiveAt }
    }

    /**
     * 旧实现（保留作参考）：以投影缓存为准枚举。
     * 问题是缓存节流延迟写入，且没有缓存的会话会被漏掉。
     */
    @Suppress("unused")
    private fun listFromProjCache(ctx: Context): List<SessionInfo> {
        val cache = projCacheDir(ctx)
        val out = ArrayList<SessionInfo>()
        cache.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
            val meta = readProjMeta(f)
            val id = f.name.removeSuffix(".json")
            out += SessionInfo(
                id = id,
                file = f,
                lastActiveAt = lastActiveOf(ctx, id, f, meta),
                title = meta.title.replace('\n', ' ').trim().take(24),
                blank = meta.blank && meta.turns == 0,
            )
        }
        return out.sortedByDescending { it.lastActiveAt }
    }

    /** 最后活跃的会话（无则 null） */
    fun lastActive(ctx: Context): SessionInfo? = list(ctx).firstOrNull()

    /**
     * 解析会话内容，补上「最后一条用户消息 / 助手消息」。
     * 需要解压 + 逐行解析，较重，调用方应在后台线程做。
     */
    fun loadDetail(ctx: Context, info: SessionInfo): SessionInfo {
        val text = decompress(info.file) ?: return info
        var user = ""
        var assistant = ""
        var title = ""
        // jsonl：逐行 JSON。用户/助手消息取**最后**一条；标题取**第一条**用户消息
        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            runCatching {
                val o = JSONObject(line)
                val type = o.optString("type")
                if (type == "message" || type.contains("message")) {
                    val role = o.optString("role")
                    val content = o.optString("content").ifBlank {
                        o.optJSONObject("message")?.optString("content").orEmpty()
                    }
                    if (content.isNotBlank()) {
                        if (role == "user") {
                            if (title.isEmpty()) title = content   // 第一条 = 对话名
                            user = content
                        } else if (role == "assistant") assistant = content
                    }
                }
            }
        }
        return info.copy(
            lastUserText = user.take(80),
            lastAssistantText = assistant.take(80),
            title = title.replace('\n', ' ').trim().take(24),
        )
    }

    /** 用引擎自带的 node 解压 zstd（与 TurnWatcher 同一方案） */
    private fun decompress(f: File): String? {
        val node = findNode() ?: run {
            Log.w(TAG, "node not found, cannot decompress")
            return null
        }
        return try {
            val script = "const z=require('node:zlib'),fs=require('node:fs');" +
                "const b=z.zstdDecompressSync(fs.readFileSync(process.argv[1]));" +
                "process.stdout.write(b);"
            val p = ProcessBuilder(node, "-e", script, f.absolutePath).start()
            val out = p.inputStream.readBytes()
            if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroy(); return null }
            if (p.exitValue() != 0) return null
            String(out, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decompress failed: ${e.message}"); null
        }
    }

    private fun findNode(): String? {
        // 引擎自带的 node（TurnWatcher 同款探测顺序）
        val candidates = listOf(
            File("/data/data/app.dsh.mobile/files/engine/bin/node"),
        )
        return candidates.firstOrNull { it.canExecute() }?.absolutePath
    }
}

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
    )

    /**
     * 标题缓存：file.path -> (lastModified, title)。
     * 取标题要解压 + 逐行解析（会起一个 node 进程，约 100-300ms），
     * 悬浮窗每次展开都重算会明显卡顿，故按 mtime 缓存。
     */
    private val titleCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** 只取标题（带缓存），供悬浮窗列表这类高频调用 */
    fun titleOf(ctx: Context, info: SessionInfo): String {
        val key = info.file.path
        titleCache[key]?.let { (mtime, t) -> if (mtime == info.lastActiveAt) return t }
        val title = loadDetail(ctx, info).title
        titleCache[key] = info.lastActiveAt to title
        return title
    }

    /** 会话根目录 */
    private fun sessionsRoot(ctx: Context): File =
        File(EngineConfig.dshHome(ctx), "sessions")

    /**
     * 列出所有会话，按最后活跃时间倒序。
     * 不解析内容（快）；需要内容时再调 [loadDetail]。
     */
    fun list(ctx: Context): List<SessionInfo> {
        val root = sessionsRoot(ctx)
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<SessionInfo>()
        root.listFiles()?.forEach { proj ->
            if (!proj.isDirectory) return@forEach
            proj.listFiles()?.forEach { sess ->
                if (!sess.isDirectory) return@forEach
                val f = sess.listFiles()
                    ?.firstOrNull { it.name.startsWith("session.") && it.name.endsWith(".zstd") }
                    ?: return@forEach
                out += SessionInfo(id = sess.name, file = f, lastActiveAt = f.lastModified())
            }
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

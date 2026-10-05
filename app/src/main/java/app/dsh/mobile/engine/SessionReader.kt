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
    )

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
        // jsonl：逐行 JSON。只关心文本消息，取最后出现的
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
                        if (role == "user") user = content
                        else if (role == "assistant") assistant = content
                    }
                }
            }
        }
        return info.copy(
            lastUserText = user.take(80),
            lastAssistantText = assistant.take(80),
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

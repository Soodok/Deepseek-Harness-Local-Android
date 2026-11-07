package app.dsh.mobile

import android.content.Context
import android.util.Log
import app.dsh.mobile.engine.EngineConfig
import org.json.JSONArray
import java.io.File

/**
 * 外部文件的落地队列。
 *
 * 语义是**至少投递一次**而不是恰好一次：读走之后就把队列清空，但如果
 * WebUI 那侧注入失败（页面还没准备好、JS 报错），文件仍在 `files/inbox/`
 * 里，用户可以让 AI 直接读那个目录。宁可多留一个文件，不能丢用户的东西。
 */
object Inbox {

    private const val TAG = "Inbox"
    private const val FILE = "pending.json"

    fun pending(ctx: Context): List<String> {
        val f = File(EngineConfig.inboxDir(ctx), FILE)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrElse {
            Log.w(TAG, "pending.json unreadable, treating as empty: ${it.message}")
            emptyList()
        }
    }

    /**
     * 原子写入：先写临时文件再 rename。
     *
     * 直接 `writeText` 到目标文件的话，进程在这期间被杀会留下半个 JSON，
     * 下次读出来解析失败、队列**静默丢失**。rename 在同一文件系统上是原子的。
     */
    fun writePending(ctx: Context, paths: List<String>) {
        val dir = EngineConfig.inboxDir(ctx)
        val target = File(dir, FILE)
        if (paths.isEmpty()) {
            runCatching { target.delete() }
            return
        }
        val arr = JSONArray()
        paths.distinct().forEach { arr.put(it) }
        val tmp = File(dir, "$FILE.tmp")
        runCatching {
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(target)) {
                // rename 失败（极少数文件系统）→ 退回直接写，至少不丢内容
                target.writeText(arr.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "writePending failed: ${it.message}") }
    }

    /** 取走并清空。调用方拿到的是「本次新到的文件」。 */
    fun drain(ctx: Context): List<String> {
        val got = pending(ctx)
        if (got.isNotEmpty()) writePending(ctx, emptyList())
        return got
    }
}

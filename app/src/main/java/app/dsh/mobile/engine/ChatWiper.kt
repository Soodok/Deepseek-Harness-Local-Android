package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话删除（v1.6.17，用户需求：「在维护与高级里加一个删除对话内容的新功能，
 * 允许在里面直接让用户删对话，或者一键清空所有对话记录」）。
 *
 * ## 硬约束：不许影响启动速度
 *
 * 用户明确要求「不要启动时读取，一定不要让任何事情影响启动速度」。
 * 因此本类是**纯懒加载**：
 *
 * · 没有任何 `init` 块、没有 `object` 初始化时的工作、没有后台线程自启；
 * · [list] 只在用户点「选择对话」时调一次，且必须在后台线程；
 * · 启动路径（`EngineSupervisor` / `MainActivity` / `DshApp`）**完全不引用本类**。
 *
 * 这一点由探针 `_test_chat_wiper.py` 反向锁定：把本类加进任何启动路径即变红。
 *
 * ## 为什么必须「先停引擎，再删」
 *
 * 引擎把 `storages/workspace.json` 当**独占文件**：启动时读一次进内存，之后每次
 * 变更整份写回，既不监视文件也不比对时间戳。所以运行期删掉会话文件后，引擎
 * 内存里仍持有那些 sessionId，下一次任何变更都会把整份注册表写回去 —— 于是
 * 列表里出现一堆打不开的幽灵条目。`ChatRecovery` 踩过同一个坑（见其注释里
 * 「13:04 写进 7 个工作区，13:07 被回落成 1 个」）。
 *
 * 所以流程固定为：**停引擎 → 删文件 + 清注册表 → 重启引擎**。调用方负责
 * 调用 `EngineSupervisor.stopForMaintenance()` 与 `allowStartAfterMaintenance()`。
 *
 * ## 安全边界
 *
 * 1. **删除前必备份**：`workspace.json` 拷一份带时间戳的副本到 `storages/`，
 *    并把被删的会话文件**移动**（不是 unlink）到 `sessions/.trash-<时间戳>/`
 *    下，保留 [TRASH_KEEP_DAYS] 天。删错了还能捞回来。
 * 2. **只删会话本体，不碰工作区**：工作区条目（用户手建的目录）保留，只把
 *    `sessionIds` 里对应项摘掉。清空全部时同理 —— 工作区壳还在，只是没有对话。
 * 3. **归档表也要清**：`global.archivedSessionIds` 里残留的 id 一并摘掉，
 *    否则「已归档」列表里会留下点进去空白的条目。
 * 4. **原子写回**：临时文件 + rename，避免半截 JSON 让引擎彻底读不了注册表。
 */
object ChatWiper {

    private const val TAG = "ChatWiper"
    private const val REGISTRY = "storages/workspace.json"
    private const val SESSIONS = "sessions"
    private const val SESSION_FILE = "session.v4.jsonl.zstd"

    /** 回收站保留天数；超过则由 [purgeTrash] 清掉。 */
    const val TRASH_KEEP_DAYS = 7

    /** 回收站目录名前缀。**列表遍历必须排除它**，否则删掉的对话会重新出现。 */
    private const val TRASH_PREFIX = ".trash-"

    /**
     * 列表里的一个会话。
     *
     * 只带 UI 需要的字段 —— 标题来自投影缓存（明文 JSON），**不解压会话本体**，
     * 否则几百个会话要起几百个 node 进程，点一下按钮等几十秒。
     */
    data class Item(
        val sessionId: String,
        /** 会话文件或目录（删除时用；可能为 null 表示只有注册表条目、文件已丢） */
        val file: File?,
        val title: String,
        val cwd: String,
        val updatedAt: Long,
        val bytes: Long,
        val archived: Boolean,
    )

    /** 扫描结果。 */
    data class Snapshot(
        val items: List<Item>,
        val totalBytes: Long,
        /** 注册表里登记了、但磁盘上找不到文件的「幽灵」会话数 */
        val ghosts: Int,
    )

    /**
     * 枚举全部会话（含已归档）。
     *
     * **必须在后台线程调用**（会遍历目录 + 读投影缓存）。
     */
    fun list(ctx: Context): Snapshot {
        val home = EngineConfig.dshHome(ctx)
        val root = registryOf(home)
        val registered = registeredIds(root)
        val archived = archivedIds(root)
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        val sessionsRoot = File(home, SESSIONS)

        sessionsRoot.listFiles()?.forEach { top ->
            if (!top.isDirectory) return@forEach
            // ⚠️ 必须跳过回收站目录本身。
            //
            // 回收站就建在 `sessions/` 下（`.trash-<stamp>-<mode>/`），里面是
            // `<session-id>/session.v4.jsonl.zstd` —— 与正常布局长得一模一样。
            // 不排除的话，用户刚删掉的对话会**重新出现在列表里**（离线复刻脚本
            // 抓到的真 bug：清空全部之后 list 仍返回 1 条）。
            if (top.name.startsWith(TRASH_PREFIX)) return@forEach
            // 布局一（当前）：sessions/<cwd-slug>/<session-id>/
            // 布局二（极早期）：sessions/<cwd-slug>/<session-id>.jsonl
            val nested = top.listFiles() ?: return@forEach
            for (child in nested) {
                val (id, file) = when {
                    child.isDirectory -> {
                        val f = File(child, SESSION_FILE)
                        if (!f.isFile) continue
                        child.name to f
                    }
                    child.isFile && child.name.endsWith(".jsonl") ->
                        child.name.removeSuffix(".jsonl") to child
                    else -> continue
                }
                if (!seen.add(id)) continue
                val meta = metaOf(home, id)
                out += Item(
                    sessionId = id,
                    file = file,
                    title = meta.first,
                    cwd = meta.second,
                    updatedAt = file.lastModified(),
                    bytes = sizeOf(file),
                    archived = id in archived,
                )
            }
        }

        // 注册表里有、磁盘上没有的幽灵条目：也列出来，让用户能清掉
        val ghosts = registered.filter { it !in seen }
        for (id in ghosts) {
            val meta = metaOf(home, id)
            out += Item(
                sessionId = id,
                file = null,
                title = meta.first,
                cwd = meta.second,
                updatedAt = 0L,
                bytes = 0L,
                archived = id in archived,
            )
        }

        val sorted = out.sortedByDescending { it.updatedAt }
        Log.i(TAG, "list: items=${sorted.size} ghosts=${ghosts.size}")
        return Snapshot(sorted, sorted.sumOf { it.bytes }, ghosts.size)
    }

    /**
     * 删除指定会话。
     *
     * @param ids 要删的 sessionId
     * @return 实际删除条数
     */
    fun delete(ctx: Context, ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        return run(ctx, ids.toSet(), "delete")
    }

    /**
     * 清空全部会话（保留工作区壳与本类不认识的其它文件）。
     *
     * @return 实际清掉的会话数
     */
    fun clearAll(ctx: Context): Int {
        val all = list(ctx).items.map { it.sessionId }.toSet()
        return clearAllIds(ctx, all)
    }

    /**
     * 清空指定 id 集合（调用方已扫过列表，避免二次 IO）。
     *
     * 与 [delete] 的区别只在回收站目录名与日志标记（`-clear-all`），
     * 便于事后从磁盘上区分「用户一个个勾着删」和「一键全清」。
     */
    fun clearAllIds(ctx: Context, ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        return run(ctx, ids.toSet(), "clear-all")
    }

    /**
     * 清掉超过 [TRASH_KEEP_DAYS] 天的回收站目录。
     *
     * @return 清掉的目录数
     */
    fun purgeTrash(ctx: Context): Int {
        val sessionsRoot = File(EngineConfig.dshHome(ctx), SESSIONS)
        val cutoff = System.currentTimeMillis() - TRASH_KEEP_DAYS * 24L * 3600_000L
        var n = 0
        sessionsRoot.listFiles()?.forEach { f ->
            if (!f.isDirectory) return@forEach
            if (!f.name.startsWith(TRASH_PREFIX)) return@forEach
            if (f.lastModified() > cutoff) return@forEach
            if (f.deleteRecursively()) n++
        }
        if (n > 0) Log.i(TAG, "purged $n expired trash dir(s)")
        return n
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 真正的删除流程。调用方**必须先停引擎**。
     *
     * @param mode 仅用于归档目录命名与日志，便于事后追查是单删还是清空
     */
    private fun run(ctx: Context, ids: Set<String>, mode: String): Int {
        val home = EngineConfig.dshHome(ctx)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val trash = File(File(home, SESSIONS), "$TRASH_PREFIX$stamp-$mode")
        var moved = 0
        var registryRemoved = 0

        // ① 移动会话本体到回收站（移动而非 unlink：用户可能误删）
        val sessionsRoot = File(home, SESSIONS)
        sessionsRoot.listFiles()?.forEach { top ->
            if (!top.isDirectory || top.name.startsWith(TRASH_PREFIX)) return@forEach
            val nested = top.listFiles() ?: return@forEach
            for (child in nested) {
                val id = when {
                    child.isDirectory -> child.name
                    child.isFile && child.name.endsWith(".jsonl") -> child.name.removeSuffix(".jsonl")
                    else -> continue
                }
                if (id !in ids) continue
                val dest = File(trash, id)
                val ok = runCatching {
                    trash.mkdirs()
                    if (!child.renameTo(dest)) {
                        // 跨文件系统 rename 会失败：退化成 copy + delete
                        child.copyRecursively(dest, overwrite = true)
                        child.deleteRecursively()
                    }
                    true
                }.getOrElse {
                    Log.w(TAG, "move $id to trash failed: ${it.message}")
                    false
                }
                if (ok) moved++
            }
        }

        // ② 清注册表引用（会话 id 从每个工作区的 sessionIds 与归档表里摘掉）
        val registryFile = File(home, REGISTRY)
        if (registryFile.isFile) {
            val root = runCatching { JSONObject(registryFile.readText()) }.getOrNull()
            if (root == null) {
                Log.e(TAG, "registry unreadable, skipped id cleanup")
            } else {
                registryRemoved = pruneRegistry(root, ids)
                if (registryRemoved > 0) {
                    backupRegistry(registryFile)
                    writeAtomically(registryFile, root)
                }
            }
        }

        Log.i(
            TAG,
            "$mode: requested=${ids.size} filesMoved=$moved registryEntries=$registryRemoved trash=${trash.name}",
        )
        return maxOf(moved, registryRemoved)
    }

    /** 从注册表里摘掉给定会话 id，返回摘掉的条目数。 */
    private fun pruneRegistry(root: JSONObject, ids: Set<String>): Int {
        var removed = 0
        val tables = root.optJSONObject("tables")?.optJSONObject("workspaces")
        if (tables != null) {
            for (wid in tables.keys().asSequence().toList()) {
                val ws = tables.optJSONObject(wid) ?: continue
                val arr = ws.optJSONArray("sessionIds") ?: continue
                val next = JSONArray()
                var changed = false
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id in ids) {
                        removed++
                        changed = true
                    } else {
                        next.put(id)
                    }
                }
                if (changed) {
                    ws.put("sessionIds", next)
                    ws.put("updatedAt", isoNow())
                }
            }
        }
        // 归档表与固定表也要清，否则「已归档」里留下点进去空白的条目
        for (key in listOf("archivedSessionIds", "pinnedSessionIds")) {
            val g = root.optJSONObject("global") ?: continue
            val arr = g.optJSONArray(key) ?: continue
            val next = JSONArray()
            var changed = false
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id in ids) {
                    removed++
                    changed = true
                } else {
                    next.put(id)
                }
            }
            if (changed) g.put(key, next)
        }
        return removed
    }

    private fun backupRegistry(file: File) {
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            file.copyTo(File(file.parentFile, "workspace.json.before-wipe-$stamp"), overwrite = true)
        }.onFailure { Log.w(TAG, "registry backup failed: ${it.message}") }
    }

    private fun writeAtomically(file: File, root: JSONObject) {
        val tmp = File(file.parentFile, ".workspace.json.wipe-tmp")
        runCatching {
            tmp.writeText(root.toString(2) + "\n", StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            runCatching { tmp.delete() }
            Log.e(TAG, "registry write failed: ${it.message}", it)
        }
    }

    private fun registryOf(home: File): JSONObject? {
        val f = File(home, REGISTRY)
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun registeredIds(root: JSONObject?): Set<String> {
        if (root == null) return emptySet()
        val out = HashSet<String>()
        root.optJSONObject("tables")?.optJSONObject("workspaces")?.let { tables ->
            for (wid in tables.keys().asSequence().toList()) {
                val arr = tables.optJSONObject(wid)?.optJSONArray("sessionIds") ?: continue
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf(String::isNotBlank)?.let(out::add)
                }
            }
        }
        return out
    }

    private fun archivedIds(root: JSONObject?): Set<String> {
        val arr = root?.optJSONObject("global")?.optJSONArray("archivedSessionIds")
            ?: return emptySet()
        val out = HashSet<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf(String::isNotBlank)?.let(out::add)
        }
        return out
    }

    /**
     * 从投影缓存读标题与 cwd；拿不到就用 id 兜底。
     *
     * 引擎在 `storages/session_projcache/sessions/<id>.json` 缓存会话元数据
     * （明文 JSON）。读它比解压 `.zstd` 快三个数量级 —— 这是本功能敢做「列全部」
     * 的前提（`ChatRecovery` 里同样的取舍）。
     */
    private fun metaOf(home: File, sessionId: String): Pair<String, String> {
        val f = File(home, "storages/session_projcache/sessions/$sessionId.json")
        if (!f.isFile) return sessionId to ""
        return runCatching {
            val o = JSONObject(f.readText())
            val title = sequenceOf("title", "name", "summary")
                .map { o.optString(it) }
                .firstOrNull { it.isNotBlank() }
                ?: sessionId
            val cwd = o.optString("cwd")
            title to cwd
        }.getOrDefault(sessionId to "")
    }

    private fun sizeOf(f: File): Long = when {
        f.isFile -> f.length()
        f.isDirectory -> f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        else -> 0L
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date())
}

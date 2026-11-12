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
 * 对话恢复（v1.4.4，用户需求：「设置里直接添加对话恢复功能，自动检索并恢复之前的对话」）。
 *
 * ## 为什么需要
 * 引擎把「哪些会话归哪个工作区」记在 `$DSH_HOME/storages/workspace.json` 里
 * （每个工作区一个 `sessionIds` 数组）。而会话本体是磁盘上的
 * `sessions/<cwd-slug>/<session-id>/session.v4.jsonl.zstd`。
 *
 * 两处数据是**分开维护**的，一旦出现下面的情况，会话文件还在、但列表里看不到：
 *
 *   · 从旧版本升级 / 换机恢复 / 手动拷回 `dshdata` 目录；
 *   · 数据迁移搬到新位置后，注册表没跟着更新；
 *   · 某次异常退出导致注册表写了一半。
 *
 * 用户看到的就是「对话没了」。实际上文件都在，只是**没登记**。
 *
 * ## 本类做什么
 * 1. [scan] —— 遍历 `sessions/` 下所有 `session.v4.jsonl.zstd`，读出每个会话的
 *    header（id / cwd / createdAt），与 `workspace.json` 的登记情况比对，
 *    找出「磁盘上有、但任何工作区都没登记」的会话。
 * 2. [recover] —— 把这些会话登记进**其 cwd 对应的工作区**（没有对应工作区时，
 *    按 cwd 新建一个）。只增不删，绝不移动或改写会话文件本身。
 *
 * ## 安全边界
 * · 只读取会话文件的**第一行**（header），不解析整份日志 —— 快且不会因大文件卡住；
 * · 已有登记的一律跳过，不会重复添加、不会改动顺序；
 * · 找不到 cwd 对应工作区时按路径建一个新的，标题用目录名；
 * · 任何一步失败都只记录、不抛给 UI 之外，最坏情况是「一个都没恢复」，
 *   绝不会让已有数据变得更糟。
 */
object ChatRecovery {

    private const val TAG = "ChatRecovery"
    private const val REGISTRY = "storages/workspace.json"
    private const val SESSION_FILE = "session.v4.jsonl.zstd"

    /** 磁盘上一个尚未登记的会话。 */
    data class Orphan(
        val sessionId: String,
        val cwd: String,
        val createdAt: Long,
        val file: File,
    )

    /** 扫描结果。 */
    data class ScanResult(
        val orphans: List<Orphan>,
        /** 已在注册表里的会话数（用于 UI 展示「共找到 N 个，其中 M 个未登记」） */
        val registeredCount: Int,
        val totalOnDisk: Int,
    )

    /**
     * 扫描「磁盘上有、注册表里没有」的会话。
     *
     * 全部在调用线程同步执行，调用方负责放到后台线程（UI 侧用 Thread）。
     */
    fun scan(ctx: Context): ScanResult {
        val home = EngineConfig.dshHome(ctx)
        val registered = registeredSessionIds(home)
        val sessionsRoot = File(home, "sessions")
        val orphans = mutableListOf<Orphan>()
        var total = 0

        // 布局一：sessions/<slug>/<session-id>/session.v4.jsonl.zstd（当前版本）
        // 布局二：sessions/<session-id>.jsonl（极早期版本，也一并认）
        sessionsRoot.listFiles()?.forEach { top ->
            if (!top.isDirectory) {
                if (top.isFile && top.name.endsWith(".jsonl")) {
                    total++
                    val id = top.name.removeSuffix(".jsonl")
                    if (id !in registered) headerOf(top)?.let { (cwd, at) ->
                        orphans += Orphan(id, cwd, at, top)
                    }
                }
                return@forEach
            }
            val nested = top.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (nested.isEmpty()) return@forEach
            for (dir in nested) {
                val f = File(dir, SESSION_FILE)
                if (!f.isFile) continue
                total++
                val id = dir.name
                if (id in registered) continue
                headerOf(f)?.let { (cwd, at) -> orphans += Orphan(id, cwd, at, f) }
            }
        }

        Log.i(
            TAG,
            "scan: onDisk=$total registered=${registered.size} orphans=${orphans.size}",
        )
        return ScanResult(orphans.sortedByDescending { it.createdAt }, registered.size, total)
    }

    /**
     * 恢复：把扫描到的会话登记回工作区注册表。
     *
     * @returns 实际新增的会话数
     */
    fun recover(ctx: Context, orphans: List<Orphan>): Int {
        if (orphans.isEmpty()) return 0
        val home = EngineConfig.dshHome(ctx)
        val registryFile = File(home, REGISTRY)
        val root: JSONObject
        if (registryFile.isFile) {
            root = runCatching { JSONObject(registryFile.readText()) }.getOrElse {
                Log.e(TAG, "registry unreadable: ${it.message}", it)
                return 0
            }
        } else {
            // 注册表不存在：按引擎的最小结构新建
            root = JSONObject().apply {
                put("unit", JSONObject().apply { put("name", "workspace"); put("version", 2) })
                put("global", JSONObject().apply {
                    put("initialized", true); put("workspaceIds", JSONArray())
                    put("archivedSessionIds", JSONArray()); put("pinnedSessionIds", JSONArray())
                })
                put("tables", JSONObject().apply { put("workspaces", JSONObject()) })
            }
            File(home, "storages").mkdirs()
        }

        val tables = root.optJSONObject("tables")?.optJSONObject("workspaces")
            ?: JSONObject().also { t ->
                root.optJSONObject("tables")?.put("workspaces", t)
            }
        val global = root.optJSONObject("global") ?: JSONObject().also { root.put("global", it) }
        val ids = global.optJSONArray("workspaceIds") ?: JSONArray().also { global.put("workspaceIds", it) }
        // ⚠️ v1.4.6（审查 P0）：引擎的 WorkspaceRegistry.validateStoredState 会校验
        // 「tables 里的每个工作区都必须出现在 workspaceIds 顺序表里」，缺失直接
        // 抛 `workspace 'X' is absent from registry order` → **引擎起不来**。
        // 旧实现新建空数组就往下写，一旦 tables 有记录而 order 为空，就把引擎弄挂。
        // 这里先把现有 tables 的 id 全部补进 order（保留已有顺序，新 id 追加）。
        runCatching {
            val known = (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) }.toMutableSet()
            for (wid in tables.keys().asSequence().toList()) {
                if (known.add(wid)) ids.put(wid)
            }
        }

        // cwd（规整后）→ 工作区 id
        val byCwd = HashMap<String, String>()
        for (wid in tables.keys().asSequence().toList()) {
            val ws = tables.optJSONObject(wid) ?: continue
            val p = ws.optString("path").takeIf { it.isNotBlank() } ?: continue
            byCwd[norm(p)] = wid
        }

        val nowIso = isoNow()
        var added = 0
        val touched = HashSet<String>()

        for (o in orphans) {
            // cwd 必然非空（headerOf 里拿不到就返回 null，不会进 orphans）
            //
            // ★v1.7.6：会话头里的 cwd 可能是**容器视角**拼写（Linux 模式下引擎
            //   DSH_HOME=/dsh，会话 cwd 记成 /dsh/workspaces）。原样登记回注册表
            //   等于把 healWorkspaceRegistry 刚修好的条目又写坏 —— 下次启动依然
            //   ensure project directory 失败。所以登记前统一翻成宿主拼写。
            val cwd = EngineConfig.guestToHost(ctx, o.cwd) ?: o.cwd
            val key = norm(cwd)
            var wid = byCwd[key]
            if (wid == null) {
                // 没有匹配的工作区：按 cwd 新建一个（只登记，不创建/移动目录）
                if (cwd.isBlank()) continue   // 无 cwd 且无任何工作区可归：跳过，不造垃圾
                wid = java.util.UUID.randomUUID().toString()
                val title = File(cwd).name.ifBlank { cwd }
                tables.put(wid, JSONObject().apply {
                    put("path", cwd)
                    put("title", title)
                    put("sessionIds", JSONArray())
                    put("createdAt", nowIso)
                    put("updatedAt", nowIso)
                })
                ids.put(wid)
                byCwd[key] = wid
                Log.i(TAG, "created workspace '$title' for $cwd")
            }
            val ws = tables.optJSONObject(wid) ?: continue
            val list = ws.optJSONArray("sessionIds") ?: JSONArray().also { ws.put("sessionIds", it) }
            val existing = (0 until list.length()).mapNotNull { list.optString(it).takeIf(String::isNotBlank) }
            if (o.sessionId in existing) continue
            // 新会话放最前（引擎列表按数组顺序展示，最近的更可能被先看）
            val next = JSONArray()
            next.put(o.sessionId)
            existing.forEach(next::put)
            ws.put("sessionIds", next)
            ws.put("updatedAt", nowIso)
            touched += wid.toString()
            added++
        }

        if (added == 0) return 0

        // ⚠️ v1.4.7（用户实测反馈，关键）：**引擎把 workspace.json 当独占文件**。
        // 它启动时读一次进内存，之后只在「发生变更」时整份写回 —— 既不监视文件，
        // 写回前也不比对时间戳。所以我们在外部改完这个文件后，只要引擎再次发生
        // 任何会话/工作区变更，就会用**旧内存状态**把我们的修改整份抹掉：
        //
        //   外部写入 7 个工作区 → 引擎某次变更写回 → 只剩内存里的 1 个
        //   → engine.log 一个字都没有 → 用户以为历史丢了 → 卸载重装 → 真没了
        //
        // 用户实测：13:04:35 和 13:07:39 两次都被抹掉，条目从 7 回落到 1。
        //
        // 应对（三层）：
        //   ① 写前把原文件备份一份（用户现在完全没有回退手段）；
        //   ② 保存写入前的 mtime + 内容摘要，供调用方检测是否被引擎覆盖；
        //   ③ **调用方必须重启引擎**（见 SettingsActivity），让引擎重新从磁盘读。
        //      这是唯一正确的顺序：先停引擎 → 改文件 → 起引擎。
        runCatching {
            val backup = File(registryFile.parentFile, "workspace.json.before-recover")
            registryFile.copyTo(backup, overwrite = true)
            Log.i(TAG, "registry backed up to ${backup.absolutePath}")
        }.onFailure { Log.w(TAG, "registry backup failed: ${it.message}") }

        // 原子写回（临时文件 + rename，避免半截 JSON 让引擎彻底读不了注册表）
        val tmp = File(registryFile.parentFile, ".workspace.json.recover-tmp")
        try {
            tmp.writeText(root.toString(2) + "\n", StandardCharsets.UTF_8)
            if (!tmp.renameTo(registryFile)) {
                tmp.copyTo(registryFile, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "recovered $added session(s) into ${touched.size} workspace(s)")
            // 记下写入后的状态，供 verifyNotOverwritten() 在重启前确认引擎没抢写
            runCatching {
                File(registryFile.parentFile, ".workspace.json.recover-stamp")
                    .writeText(registryFile.length().toString() + "|" + registryFile.lastModified().toString())
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            Log.e(TAG, "registry write failed: ${e.message}", e)
            return 0
        }
        return added
    }

    /**
     * 恢复是否仍然存在（未被运行中的引擎覆盖）。
     *
     * ## 为什么需要
     * 引擎把 `workspace.json` 当独占文件：启动时读一次进内存，之后每次变更整份
     * 写回。所以**在引擎运行期间改这个文件，改动很快会被抹掉** —— 用户实测
     * 13:04 写进 7 个工作区，13:07 就被回落到 1 个，日志里没有任何记录。
     *
     * 因此正确流程是：**先停引擎 → 恢复 → 再启动引擎**。调用方在恢复后如果
     * 发现引擎仍在运行，应当提示用户重启；本方法用于恢复完成后再确认一次。
     *
     * @returns true 表示恢复的内容还在；false 表示已被覆盖（需要重做并重启引擎）
     */
    fun verifyStillPresent(ctx: Context, expectedSessions: Int): Boolean = runCatching {
        val home = EngineConfig.dshHome(ctx)
        val registered = registeredSessionIds(home)
        registered.size >= expectedSessions
    }.getOrDefault(false)

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 推导会话的 cwd 与 createdAt。
     *
     * ## 为什么不去解压会话文件读 header
     * `.zstd` 会话要起 Node 子进程解压（`ZstdTail.read`），每个会话一次进程 ——
     * 磁盘上几百个会话时这会是几十秒的扫描，用户点一下「对话恢复」要等到超时。
     *
     * 而**恢复其实不需要精确 cwd**：只要把会话 id 登记进某一个工作区的
     * `sessionIds`，它就会出现在该工作区的列表里。会话文件里的 cwd 只影响
     * 「归到哪个工作区」这一件事，而用户真正要的是「能重新打开这个对话」。
     *
     * 所以策略是：**归到唯一/首个工作区**，并保留文件修改时间用于排序。
     * 若后续要做精确归位，再在后台解压 header（不阻塞恢复本身）。
     */
    private fun headerOf(file: File): Pair<String, Long>? {
        // ⚠️ v1.4.6（审查 P0）：这里必须拿到**真实 cwd**，不能留空。
        //
        // 原因：引擎的 dsh-workspace 在读取 sessionIds 时会用 candidateMatches
        // 按「会话 header 的 cwd」过滤 —— 登记到 A 工作区、但 cwd 属于 B 的会话
        // 会被**静默过滤掉**。旧实现返回空 cwd，于是所有会话被塞进第一个工作区，
        // 用户看到「已恢复 N 个」，重启后一个都没有；更糟的是这些 id 已写进
        // registry，下次扫描认为「已登记」，永久无法自愈。
        //
        // 取 cwd 的成本：会话文件是 zstd，要解压第一行。为避免每个会话起一个
        // node 进程（几百个会话会等到超时），这里读引擎自己维护的投影缓存
        // `storages/session_projcache/sessions/<id>.json`（明文 JSON，含 cwd）。
        val created = file.lastModified()
        val id = file.parentFile?.name ?: return null
        val cwd = cwdFromProjectionCache(file, id) ?: return null  // 拿不到就不恢复（宁可少恢复，不可错归）
        return cwd to created
    }

    /**
     * 从引擎的投影缓存里读会话 cwd。
     *
     * 引擎在 `storages/session_projcache/sessions/<session-id>.json` 里缓存每个
     * 会话的元数据（明文 JSON）。这里只读 `cwd` 字段 —— 解析失败返回 null，
     * 由调用方跳过该会话（不做猜测）。
     *
     * @param sessionFile 会话文件本身（用于定位同级的 dsh-home）
     * @param sessionId 会话 id（目录名）
     * @returns 绝对 cwd，或 null
     */
    private fun cwdFromProjectionCache(sessionFile: File, sessionId: String): String? {
        // sessionFile = .../dsh-home/sessions/<slug>/<id>/session.v4.jsonl.zstd
        // 向上找到 dsh-home（含 storages 子目录的那一层）
        var dir: File? = sessionFile.parentFile
        repeat(4) {
            val cur = dir ?: return null
            val cache = File(cur, "storages/session_projcache/sessions/$sessionId.json")
            if (cache.isFile) {
                val cwd = runCatching {
                    JSONObject(cache.readText()).optString("cwd").takeIf(String::isNotBlank)
                }.getOrNull()
                if (cwd != null) return cwd
            }
            dir = cur.parentFile
        }
        return null
    }

    /** 注册表里已登记的所有 session id（含归档的）。 */
    private fun registeredSessionIds(home: File): Set<String> {
        val f = File(home, REGISTRY)
        if (!f.isFile) return emptySet()
        val root = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptySet()
        val out = HashSet<String>()
        val tables = root.optJSONObject("tables")?.optJSONObject("workspaces") ?: return out
        for (wid in tables.keys().asSequence().toList()) {
            val ws = tables.optJSONObject(wid) ?: continue
            val arr = ws.optJSONArray("sessionIds") ?: continue
            for (i in 0 until arr.length()) arr.optString(i).takeIf(String::isNotBlank)?.let(out::add)
        }
        root.optJSONObject("global")?.optJSONArray("archivedSessionIds")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf(String::isNotBlank)?.let(out::add)
        }
        return out
    }

    /** 路径规整：统一分隔符、去尾斜杠、忽略大小写差异（POSIX 上大小写敏感，但这里只做等值判断）。 */
    private fun norm(p: String): String {
        // 引擎的 workspace path 是 fs.realpath 规范化后的值；会话 header 的 cwd
        // 是 process.cwd()（软链下二者字符串不同）。必须 realpath 后才能比较，
        // 否则同一个工作区会被判成两个，进而新建重复工作区（审查第 9 条）。
        val resolved = runCatching { File(p).canonicalPath }.getOrNull()
        return (resolved ?: p).trim().replace(File.separatorChar, '/').trimEnd('/')
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date())
}

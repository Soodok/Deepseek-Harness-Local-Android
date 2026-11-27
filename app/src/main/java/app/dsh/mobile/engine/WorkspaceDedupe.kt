package app.dsh.mobile.engine

/**
 * 工作区注册表「同一个 path 被多个 workspace 认领」的合并计划。
 *
 * ## 为什么需要（真机事故，2026-11-27）
 *
 * 上游 `dsh-workspace` 的 `WorkspaceRegistry.validateStoredState` 有一条硬约束：
 * **同一个 path 只能被一个 workspace 认领**。违反时它 `throw`：
 *
 * ```
 * workspace domain is inconsistent: path '/storage/emulated/0/-云更新网盘'
 *   is claimed by both workspace '5be6e911-…' and workspace '78455992-…'
 * ```
 *
 * 抛在 `[cordis.init]` 里，后果不是「某个功能坏了」，而是**整条依赖链塌掉**：
 *
 * ```
 * workspace                 → failed（就是上面那条）
 * storage-domain            → failed
 * session-persistence-jsonl → failed
 * workspaceRegistry         → 缺失
 * session-controller        → pending (waiting for service: workspaceRegistry)
 * workspace-controller      → pending
 * directoryPickerController → 缺失
 * ui-deliverables           → pending (waiting for service: sessionController)
 * ```
 *
 * 用户看到的是「会话全都不见了」「选工作区提示 unavailable」「插件页一片黄」，
 * 而磁盘上 128 个会话文件**一个都没少** —— 只是登记表坏了。
 *
 * ## 污点从哪来
 *
 * 两个来源，都指向「合并」这一步没有做去重：
 *
 *  1. `DataExport.mergeRegistry`（备份恢复）：它按 `workspaceIds` 取**并集**、
 *     `tables` 里「缺失的才加」。于是「当前设备的 workspace A」与「备份里的
 *     workspace B」即使 `path` 完全相同，也会**两条都留下** —— 上游只在
 *     启动校验时才炸，所以恢复当时看起来是成功的。
 *  2. `ChatRecovery.recover`（会话恢复）：按 `cwd` 找 workspace，但 key 用
 *     `File(p).canonicalPath`。软链/大小写/尾斜杠任一不一致，就会为**同一个
 *     目录**再新建一个 workspace。
 *
 * ## 合并规则（本文件是唯一权威）
 *
 *  · 赢家 = `workspaceIds` 顺序表里**靠前**的那个（顺序表就是用户看到的显示
 *    顺序，保住它等于保住用户熟悉的排列）；
 *  · 被并的 workspace 的 `sessionIds` **追加**到赢家末尾（并集、去重，
 *    赢家原有的会话排在前面，保持引擎「最近的在最前」语义不乱）；
 *  · `createdAt` 取更早的（这确实是同一个目录的历史起点）；
 *  · `updatedAt` 取更晚的；
 *  · `title` 保留赢家的；赢家为空才借用被并的；
 *  · 被并的 id 从顺序表移除，`tables` 里那条也删掉。
 *
 * **绝不丢弃任何 sessionId** —— 宁可把两条登记并成一条，也不让会话失去归属。
 *
 * ## 为什么做成纯函数
 *
 * 这段逻辑错了**不会崩**，只会「静默少登记一个会话」或「把用户的工作区顺序
 * 打乱」—— 必须能拿真实数据在宿主 JVM 上跑一遍。所以这里不碰 `org.json`、
 * 不碰 `android.*`：调用方负责 JSON 读写，本文件只算计划。
 * 断言见 `scripts/jvm/BehaviourTest.kt` 的 `assertWorkspaceDedupe()`。
 */
object WorkspaceDedupe {

    /** 注册表里的一条 workspace 记录（字段与上游 `workspaceRecord` schema 对齐）。 */
    data class Record(
        val id: String,
        val path: String,
        val title: String,
        val sessionIds: List<String>,
        val createdAt: String,
        val updatedAt: String,
    )

    /** 一次「把 [loser] 并进 [winner]」的合并，用于日志与用户可见的摘要。 */
    data class Merge(val winner: String, val loser: String, val path: String)

    /**
     * 合并后的结果。
     *
     * @property keep    应该留在 `tables` 里的记录（已合并、已排序）
     * @property merges  发生了哪些合并（空 = 注册表本来就干净，调用方**不要写文件**）
     * @property order   更新后的 `workspaceIds` 显示顺序
     */
    data class Plan(
        val keep: List<Record>,
        val merges: List<Merge>,
        val order: List<String>,
    ) {
        val changed: Boolean get() = merges.isNotEmpty()
    }

    /**
     * path 的比较形态。
     *
     * 上游把这个字段存成 `fs.realpath` 的结果：已经是绝对的、无尾斜杠、无 `..`。
     * 所以这里只做**字符串级**规整（统一分隔符、去尾斜杠、压缩重复斜杠），
     * **不做** `File.canonicalPath` —— 宿主上这条路径此刻可能根本不存在
     * （比如用户删了目录但注册表还没跟上），canonicalPath 会抛或返回相对量。
     */
    internal fun normPath(p: String): String {
        var s = p.trim().replace('\\', '/')
        while (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
        while (s.contains("//")) s = s.replace("//", "/")
        return s
    }

    /**
     * 算出合并计划。**幂等**：对已经干净的表再调用一次，`changed` 为 false。
     *
     * @param order   `global.workspaceIds` 的当前顺序（可能缺项，也可能含 `tables`
     *                里不存在的 id —— 后者原样保留在返回值里，由调用方决定怎么处理；
     *                本函数不越权删「顺序表里有但表里没有」的条目，那是上游另一条校验）
     * @param records `tables.workspaces` 的全部记录
     */
    fun plan(order: List<String>, records: List<Record>): Plan {
        val byId = LinkedHashMap<String, Record>()
        for (r in records) byId[r.id] = r

        // 遍历顺序 = 顺序表优先，表里有但顺序表没提到的补在后面。
        // 这样「赢家」永远是顺序表里靠前的那个，不依赖二者的 UUID 大小。
        val visit = ArrayList<String>(order.size + byId.size)
        for (id in order) if (id in byId && id !in visit) visit += id
        for (id in byId.keys) if (id !in visit) visit += id

        val winnerByPath = HashMap<String, String>()      // normPath -> 赢家 id
        val merged = HashMap<String, Record>()            // 赢家 id -> 合并后的记录
        val merges = ArrayList<Merge>()
        val dropped = LinkedHashSet<String>()             // 被并掉的 id

        for (id in visit) {
            val src = byId[id] ?: continue
            val key = normPath(src.path)
            val winner = winnerByPath[key]
            if (winner == null) {
                winnerByPath[key] = id
                merged[id] = src.copy(sessionIds = src.sessionIds.filter { it.isNotBlank() })
                continue
            }
            val cur = merged[winner] ?: src
            // 会话并集：赢家在前、被并的在后，按 id 去重（同一会话不可能归两个工作区）
            val existing = cur.sessionIds.toMutableSet()
            val extra = src.sessionIds.filter { it.isNotBlank() && existing.add(it) }
            merged[winner] = cur.copy(
                sessionIds = cur.sessionIds + extra,
                title = cur.title.ifBlank { src.title },
                createdAt = earlier(cur.createdAt, src.createdAt),
                updatedAt = later(cur.updatedAt, src.updatedAt),
            )
            merges += Merge(winner = winner, loser = id, path = cur.path)
            dropped += id
        }

        // keep 的顺序沿用原顺序表，表里多出来的按原表顺序追加在后面
        val keep = ArrayList<Record>(merged.size)
        for (id in order) {
            val r = merged[id] ?: continue
            if (id in dropped) continue
            keep += r
            merged.remove(id)
        }
        for ((id, r) in merged) keep += r

        val newOrder = order.filter { it !in dropped }
        return Plan(keep = keep, merges = merges, order = newOrder)
    }

    /**
     * ISO-8601 字符串取更早的那个。
     *
     * 不做真正的日期解析：上游写的是 `toISOString()` 形态（同长度、同时区、同格式），
     * 字典序即时间序。任一侧为空/形态异常时**保留左侧**（宁可不动，也不乱猜）。
     */
    internal fun earlier(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        b < a -> b
        else -> a
    }

    /** 与 [earlier] 对称：取更晚的那个。 */
    internal fun later(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        b > a -> b
        else -> a
    }
}

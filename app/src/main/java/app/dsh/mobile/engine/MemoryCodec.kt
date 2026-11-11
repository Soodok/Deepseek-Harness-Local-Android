package app.dsh.mobile.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 记忆 v2 的 JSON 编解码（docs/A11Y_MEMORY_DESIGN.md §2）。
 *
 * ## 为什么单独一层
 * 本文件依赖 org.json，而 org.json 在宿主机 JVM 上是 android.jar 的空壳（调用即 "Stub!"）。
 * 所以真正的逻辑分层是：
 *
 * - [MemoryEntry] / [MemoryStep] / [MemoryRetention] / [MemoryStore]：存储与策略
 * - 本文件：**只管格式**，把读写盘时的字段名、缺省值、量化、容错全关在一处
 *
 * 好处是字段改名只需要改这里一处，而存储层不必知道 vector 落盘是 int8 还是 float。
 *
 * ## 三条硬约定
 * 1. **解码永不抛**：缺字段用默认值、类型不对退化成默认值、整条坏掉就返回空 id 由
 *    [decodeArray] 丢弃。一条坏条目不该让整个包的全部记忆一起读不出来。
 * 2. **向量 int8 量化**（设计文档 F3.3 / §8 存储预算）：512 维 float 写成 JSON 约 4KB，
 *    量化到 [-127,127] 的整数后约 1/4。量化误差上界 1/127 ≈ 0.0079，对余弦排序无影响。
 *    解码同时接受旧格式（未量化的 float 数组），字段名分别是 vectorQ / vector。
 * 3. **写入是有序的**：字段顺序固定、args 键排序后写。同一份记忆两次落盘字节可比对，
 *    排障时 diff 才有意义（同 TraceCodec 的取舍）。
 */
object MemoryCodec {

    /** int8 量化的满量程。成员向量是 L2 归一化后的值域 [-1,1]，乘 127 正好用满一个字节。 */
    private const val Q = 127f

    private const val SUFFIX = ".json"

    // ==================== 编码 ====================

    fun encodeEntry(e: MemoryEntry): JSONObject = encodeEntry(e, withHistory = true)

    /**
     * 编码本体。
     *
     * [withHistory] 只有顶层传 true：history 里每个快照若再带自己的 history，
     * 体积就是 10 × 9 × 8 … 指数膨胀（一条记忆几十 KB 变成几 MB，还是每条都这样）。
     * 快照「就是当时那条记忆」——当时它自己的 history 另有 10 条上限管着，不需要副本。
     */
    private fun encodeEntry(e: MemoryEntry, withHistory: Boolean): JSONObject = JSONObject().apply {
        // schema 每条都写：单条记忆被单独导出/贴进报告时，读方仍知道它是哪一版格式
        put("schema", MemoryContract.SCHEMA)
        put("id", e.id)
        put("pkg", e.pkg)
        put("appVersion", e.appVersion)
        put("intent", e.intent)
        put("keywords", JSONArray().apply { for (k in e.keywords) put(k) })
        put("title", e.title)
        put("steps", JSONArray().apply { for (s in e.steps) put(encodeStep(s)) })
        put("state", e.state)
        put("hits", e.hits)
        put("misses", e.misses)
        put("createdAt", e.createdAt)
        put("lastUsed", e.lastUsed)
        put("originTraceId", e.originTraceId)
        put("rev", e.rev)
        put("pending", e.pending)
        put("pendingHits", e.pendingHits)
        if (e.vector.isNotEmpty()) {
            put("vectorQ", JSONArray().apply {
                for (f in e.vector) put(quantize(f))
            })
        }
        // 空指纹不写：写一个空数组只会让文件看起来「有指纹但比对恒为 0」
        if (e.sceneFp.isNotEmpty()) {
            put("sceneFp", JSONArray().apply { for (t in e.sceneFp) put(t) })
        }
        // 稳定任务键：非空才写（旧文件没有这个字段，解码给空串）
        if (e.taskKey.isNotBlank()) put("taskKey", e.taskKey)
        if (withHistory && e.history.isNotEmpty()) {
            put("history", JSONArray().apply { for (r in e.history) put(encodeRevision(r)) })
        }
    }

    /** 一条历史版本。snapshot 用 withHistory=false 编码 —— 防递归膨胀的唯一执行点。 */
    private fun encodeRevision(r: MemoryRevision): JSONObject = JSONObject().apply {
        put("rev", r.rev)
        put("at", r.at)
        put("kind", r.kind)
        put("note", r.note)
        put("snapshot", encodeEntry(r.snapshot, withHistory = false))
    }

    private fun encodeStep(s: MemoryStep): JSONObject = JSONObject().apply {
        put("sourceIndex", s.sourceIndex)
        put("action", s.action)
        put("targetText", s.targetText)
        put("targetRole", s.targetRole)
        put("targetViewId", s.targetViewId)
        if (s.targetBounds.isNotEmpty()) {
            put("targetBounds", JSONArray().apply { for (f in s.targetBounds) put(f.toDouble()) })
        }
        if (s.args.isNotEmpty()) {
            put("args", JSONObject().apply {
                // 键排序后再写：同一份记忆两次落盘字节可比对
                for (k in s.args.keys.sorted()) put(k, s.args.getValue(k))
            })
        }
        if (s.expectAfter.isNotEmpty()) {
            put("expectAfter", JSONArray().apply { for (t in s.expectAfter) put(t) })
        }
        if (s.preFp.isNotEmpty()) {
            put("preFp", JSONArray().apply { for (t in s.preFp) put(t) })
        }
    }

    /** 一整个包 = 一个 JSON 数组（[MemoryStore] 的文件格式）。 */
    fun encodeArray(list: List<MemoryEntry>): String {
        val arr = JSONArray()
        for (e in list) arr.put(encodeEntry(e))
        return arr.toString()
    }

    // ==================== 解码 ====================

    /**
     * 解一条。**永不抛**：任何异常都退化成一条空 id 的条目，由调用方丢弃。
     *
     * 空 id 是刻意的「无效标记」而不是 null：一来签名简单（decodeArray 里不用判空），
     * 二来 [MemoryStore.upsert] 也会拒绝空 id，两条路都挡住脏数据。
     */
    fun decodeEntry(o: JSONObject): MemoryEntry = decodeEntry(o, withHistory = true)

    /**
     * 解码本体。[withHistory] 与编码侧对称：快照层不读 history。
     *
     * 外部存档（用户手工改、旧版本产物）里可能真的套了多层 history，
     * 读的时候不展开 = 既不会栈溢出，也不会把几十 MB 的嵌套结构拉进内存。
     */
    private fun decodeEntry(o: JSONObject, withHistory: Boolean): MemoryEntry = runCatching {
        MemoryEntry(
            id = str(o, "id"),
            pkg = str(o, "pkg").ifBlank { MemoryContract.UNKNOWN_PKG },
            appVersion = str(o, "appVersion"),
            intent = str(o, "intent"),
            keywords = stringList(o.optJSONArray("keywords")),
            title = str(o, "title"),
            steps = stepsOf(o.optJSONArray("steps")),
            state = normalizeState(str(o, "state")),
            hits = o.optInt("hits", 0).coerceAtLeast(0),
            misses = o.optInt("misses", 0).coerceAtLeast(0),
            createdAt = o.optLong("createdAt", 0L),
            lastUsed = o.optLong("lastUsed", 0L),
            originTraceId = str(o, "originTraceId"),
            rev = o.optInt("rev", 1).coerceAtLeast(1),
            pending = o.optBoolean("pending", true),
            pendingHits = o.optInt("pendingHits", 0).coerceAtLeast(0),
            vector = vectorOf(o),
            sceneFp = stringList(o.optJSONArray("sceneFp")),
            history = if (withHistory) revisionsOf(o.optJSONArray("history")) else emptyList(),
            taskKey = o.optString("taskKey"),
        )
    }.getOrElse {
        MemoryEntry(id = "", pkg = MemoryContract.UNKNOWN_PKG, intent = "", createdAt = 0L)
    }

    /**
     * 解一个包。字符串坏了返回空列表；单条坏了只丢那一条。
     *
     * 没有 id 的条目直接丢：get/delete/setState 全靠 id，留着它只会占容量额度还永远删不掉。
     */
    fun decodeArray(s: String): List<MemoryEntry> {
        val raw = strip(s)
        if (raw.isEmpty()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<MemoryEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val e = decodeEntry(o)
            if (e.id.isBlank()) continue
            out.add(e)
        }
        return out
    }

    /**
     * v1 → v2 的转换（[MemoryStore.migrateV1] 用）。只做**尽力转换**，不编造：
     *
     * - goal → intent 与 title
     * - pkg 取指纹里的 pkg: 元素，找不到归 unknown（不同应用混桶会误命中）
     * - plan.steps：target.text 取第一个（v2 的 targetText 是单字符串，多锚点退化信息
     *   在这里会丢；要保住它得给模型加 targetTexts 字段，属 M4/M6 的事）
     * - verify.anyText → expectAfter；action 的其余键 → args（一律字符串）
     * - hits/misses/pending/pendingHits/created/updated 原样保留（G3 的统计口径要求）
     * - **id 是确定性的**（created + goal 哈希），重跑迁移只覆盖不翻倍
     * - 指纹（fp）：原样塞进 MemoryEntry.sceneFp，同时填到**首步**的 preFp（M4 的 Jaccard
     *   接口按「首步 pre.fp」取）。v1 只有整条指纹、没有逐步指纹，所以第二步往后一律为空
     *   —— 这是**已知的信息损失**：v2 的逐步 preFp 只能等 M7 录制器真采一遍才有。
     */
    fun decodeV1(s: String): List<MemoryEntry> {
        val raw = strip(s)
        if (raw.isEmpty()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse {
            // 也容忍被包成 {"entries":[...]} 的存档（有人手工导出过就是这形状）
            runCatching { JSONObject(raw).optJSONArray("entries") }.getOrNull() ?: return emptyList()
        }
        val out = ArrayList<MemoryEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val goal = str(o, "goal")
            val created = o.optLong("created", 0L)
            val fp = stringList(o.optJSONArray("fp"))
            val pkg = pkgOfFp(fp)
            // v1 只有「整条记忆一个指纹」。把它同时写到 sceneFp 与首步 preFp：
            // M4 的结构比对读的是首步 pre.fp，而检索粗筛读 sceneFp，两处都得有值，
            // 否则迁移来的记忆永远只能靠向量层命中（话术一改就丢）。
            val steps = stepsOfV1(o.optJSONObject("plan"))
            val stepsWithPre =
                if (steps.isEmpty() || fp.isEmpty()) steps
                else listOf(steps[0].copy(preFp = fp)) + steps.drop(1)
            out.add(
                MemoryEntry(
                    id = v1Id(created, goal),
                    pkg = pkg,
                    appVersion = "",
                    intent = goal,
                    keywords = emptyList(),
                    title = goal,
                    steps = stepsWithPre,
                    state = MemoryContract.STATE_ACTIVE,
                    hits = o.optInt("hits", 0).coerceAtLeast(0),
                    misses = o.optInt("misses", 0).coerceAtLeast(0),
                    createdAt = created,
                    // v1 的 updated 就是「最后一次动过」，映射到 lastUsed 才能沿用 LRU 顺序
                    lastUsed = o.optLong("updated", created),
                    originTraceId = "",
                    rev = 1,
                    pending = o.optBoolean("pending", false),
                    pendingHits = o.optInt("pendingHits", 0).coerceAtLeast(0),
                    vector = emptyList(),
                    sceneFp = fp,
                )
            )
        }
        return out
    }

    // ==================== 内部 ====================

    /**
     * history 数组 → [MemoryRevision] 列表。单条解不出来就丢那一条（同 steps 的取舍）。
     *
     * 两处防守：① 快照解不出 id 视为坏历史，丢弃；② 超过 [MemoryContract.HISTORY_MAX]
     * 截断到最近的若干条 —— 文件是外部可写的，不能让一条脏数据把内存撑爆。
     */
    private fun revisionsOf(a: JSONArray?): List<MemoryRevision> {
        if (a == null || a.length() == 0) return emptyList()
        val out = ArrayList<MemoryRevision>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val snapObj = o.optJSONObject("snapshot") ?: continue
            val snap = decodeEntry(snapObj, withHistory = false)
            if (snap.id.isBlank()) continue
            out.add(
                MemoryRevision(
                    rev = o.optInt("rev", 0),
                    at = o.optLong("at", 0L),
                    kind = str(o, "kind"),
                    note = str(o, "note"),
                    snapshot = snap.copy(history = emptyList()),
                )
            )
        }
        if (out.size <= MemoryContract.HISTORY_MAX) return out
        return ArrayList(out.subList(out.size - MemoryContract.HISTORY_MAX, out.size))
    }

    private fun stepsOf(a: JSONArray?): List<MemoryStep> {
        if (a == null || a.length() == 0) return emptyList()
        val out = ArrayList<MemoryStep>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(
                MemoryStep(
                    sourceIndex = o.optInt("sourceIndex", a.optInt(i, i)),
                    action = str(o, "action"),
                    targetText = str(o, "targetText"),
                    targetRole = str(o, "targetRole"),
                    targetViewId = str(o, "targetViewId"),
                    targetBounds = boundsOf(o.optJSONArray("targetBounds")),
                    args = argsOf(o.optJSONObject("args")),
                    expectAfter = stringList(o.optJSONArray("expectAfter")),
                    preFp = stringList(o.optJSONArray("preFp")),
                )
            )
        }
        return out
    }

    /**
     * v1 的 plan.steps → [MemoryStep]。
     *
     * v1 的步骤形如 {id, target:{text:[...],role,bounds}, action:{type,...}, verify:{anyText:[...]}}。
     * 三块都缺的步骤（v1 里出现过只有 id 的空壳）直接跳过：留着它只会让重放在第一步就卡死。
     */
    private fun stepsOfV1(plan: JSONObject?): List<MemoryStep> {
        val arr = plan?.optJSONArray("steps") ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        val out = ArrayList<MemoryStep>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val target = s.optJSONObject("target")
            val action = s.optJSONObject("action")
            val verify = s.optJSONObject("verify")
            if (target == null && action == null && verify == null) continue
            val texts = stringList(target?.optJSONArray("text"))
            out.add(
                MemoryStep(
                    sourceIndex = i,
                    action = str(action, "type"),
                    targetText = texts.firstOrNull { it.isNotBlank() }.orEmpty(),
                    targetRole = str(target, "role"),
                    targetViewId = str(target, "viewId"),
                    targetBounds = boundsOf(target?.optJSONArray("bounds")),
                    args = actionArgsOf(action),
                    expectAfter = stringList(verify?.optJSONArray("anyText")),
                )
            )
        }
        return out
    }

    /** action 里除 type 之外的键当参数（v1 的 input 步骤靠它带 text）。 */
    private fun actionArgsOf(o: JSONObject?): Map<String, String> {
        if (o == null || o.length() == 0) return emptyMap()
        val out = LinkedHashMap<String, String>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "type" || o.isNull(k)) continue
            val v = o.opt(k)
            out[k] = if (v is String) v else v?.toString() ?: continue
        }
        return out
    }

    /** v1 指纹里的 pkg: 元素 → 包名。 */
    private fun pkgOfFp(fp: List<String>): String {
        for (t in fp) {
            if (!t.startsWith(PKG_PREFIX)) continue
            val name = t.substring(PKG_PREFIX.length).trim()
            if (name.isNotEmpty()) return name
        }
        return MemoryContract.UNKNOWN_PKG
    }

    private const val PKG_PREFIX = "pkg:"

    /**
     * v1 条目的确定性 id：created + goal 哈希。
     *
     * 为什么要确定性：v1 存档被手工还原后重跑迁移时，随机后缀会产出两条一模一样的记忆，
     * 一条 hits=3 一条 hits=0 —— 命中率立刻对半砍。用哈希当后缀，重跑只会覆盖同一条。
     */
    private fun v1Id(created: Long, goal: String): String {
        val seed = if (goal.isBlank()) created.toString() else goal
        val h = seed.hashCode().toLong() and 0x7FFFFFFFL
        val base36 = h.toString(36)
        val suffix = (base36 + "0000").substring(0, 4)
        return "m_" + created + "_" + suffix
    }

    private fun quantize(f: Float): Int {
        if (f.isNaN()) return 0
        // Math.round(Float) 返回 Int，正好一个字节的量级
        return Math.round(f * Q).coerceIn(-127, 127)
    }

    /**
     * 向量解码：优先 vectorQ（int8 量化），退而读 vector（未量化 float）。
     *
     * 两个字段名而不是同一个：同一个字段既可能是 0.0123 也可能是 16，
     * 靠猜「这个数是不是被乘过 127」迟早猜错——字段名把语义写死。
     */
    private fun vectorOf(o: JSONObject): List<Float> {
        val q = o.optJSONArray("vectorQ")
        if (q != null && q.length() > 0) {
            val out = ArrayList<Float>(q.length())
            for (i in 0 until q.length()) out.add(q.optInt(i, 0) / Q)
            return out
        }
        val f = o.optJSONArray("vector")
        if (f != null && f.length() > 0) {
            val out = ArrayList<Float>(f.length())
            for (i in 0 until f.length()) {
                val d = f.optDouble(i, Double.NaN)
                if (!d.isNaN() && !d.isInfinite()) out.add(d.toFloat())
            }
            return out
        }
        return emptyList()
    }

    /**
     * 坐标 [l, t, r, b]：长度不是 4 或含非数字就整体作废。
     *
     * 不做「跳过坏值」：少一个元素会让 MemoryStep.hasAnchor() 静默变 false，
     * 还可能算出错误的中心点。宁可让上层看到「这条没有坐标」（同 TraceCodec 的取舍）。
     */
    private fun boundsOf(a: JSONArray?): List<Float> {
        if (a == null || a.length() != 4) return emptyList()
        val out = ArrayList<Float>(4)
        for (i in 0 until 4) {
            val d = a.optDouble(i, Double.NaN)
            if (d.isNaN() || d.isInfinite()) return emptyList()
            out.add(d.toFloat())
        }
        return out
    }

    private fun argsOf(o: JSONObject?): Map<String, String> {
        if (o == null || o.length() == 0) return emptyMap()
        val out = LinkedHashMap<String, String>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (o.isNull(k)) continue
            val v = o.opt(k)
            out[k] = if (v is String) v else v?.toString() ?: continue
        }
        return out
    }

    private fun stringList(a: JSONArray?): List<String> {
        if (a == null || a.length() == 0) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            if (a.isNull(i)) continue
            val v = a.opt(i) ?: continue
            val s = if (v is String) v else v.toString()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** 取字符串字段；显式 null 与缺失都退化成空串（org.json 的 optString 会给 "null"）。 */
    private fun str(o: JSONObject?, key: String): String {
        if (o == null || o.isNull(key)) return ""
        return o.optString(key, "")
    }

    /** 状态词归一：未知/缺失按 active 处理（坏状态不该让条目既不能命中又删不掉）。 */
    private fun normalizeState(s: String): String {
        val v = s.trim().lowercase()
        return if (v in MemoryContract.STATES) v else MemoryContract.STATE_ACTIVE
    }

    /** Windows 上手工编辑过的存档可能带 BOM，JSONArray/JSONObject 都不认它。 */
    private fun strip(s: String): String {
        val t = s.trim()
        return if (t.startsWith("\uFEFF")) t.substring(1).trim() else t
    }
}

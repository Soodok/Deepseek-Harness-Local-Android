package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 记忆 v2 的常量契约（schema 2）。
 *
 * ## 为什么集中成对象
 * 目录名、状态词、上限都是**跨文件约定**（存储、淘汰、迁移，以及后面的 MemoryIndex/MemoryReplay 都要读），
 * 散在各处的字符串字面量一定会漂：状态写错一个字母，条目就既不参与命中也不被淘汰，
 * 变成看不见的僵尸。集中一处后，写错就是编译不过或断言失败。
 */
object MemoryContract {
    /** 落盘格式版本。加字段不涨版本（读方容忍未知字段），改语义才涨。 */
    const val SCHEMA = 2

    /** files/memory/v2 —— 按应用分文件。v1 是单个 scene_memory.json，全局膨胀后没法按包清理。 */
    const val DIR = "memory/v2"

    /** v1 存档（SceneMemory 写的那个）。迁移后改名保留，用于回滚。 */
    const val V1_FILE = "scene_memory.json"
    const val V1_MIGRATED = "scene_memory.json.migrated"

    /** v1 的 fp（指纹）里找不到 pkg: 前缀时的归属：宁可单列一桶，也不要把别的应用的记忆混进来。 */
    const val UNKNOWN_PKG = "unknown"

    /** active 参与命中；suspect 只作候选（连续两次复用失败自动转）；disabled 是负样本，永不命中。 */
    const val STATE_ACTIVE = "active"
    const val STATE_SUSPECT = "suspect"
    const val STATE_DISABLED = "disabled"

    /** 连续失败几次转 suspect（设计文档 §3D 第 3 条）。 */
    const val MAX_MISSES = 2

    val STATES: Set<String> = setOf(STATE_ACTIVE, STATE_SUSPECT, STATE_DISABLED)
}

/**
 * 一条记忆 = **意图 + 可重放步骤序列 + 统计**（设计文档 §2）。
 *
 * ## 为什么和 v1 的 SceneMemory.Entry 不是一个类
 * v1 是「场景指纹 + 一坨 plan JSON」：plan 是云端原样吐出的对象，本地只能整块存取，
 * 既不能逐步剪枝也不能逐步校验。v2 把步骤**拆成结构化的 MemoryStep**，
 * 这样剪枝器删得掉单步、重放器校验得了单步、AI 改得动单步。
 *
 * ## 为什么字段全是 val
 * 每处修改都走 copy()（见 [afterHit] / [afterMiss]），调用方拿到的对象永远不会被偷偷改掉。
 * 并发读写的正确性交给 [MemoryStore] 的锁，而不是让数据类自己半可变。
 */
data class MemoryEntry(
    /** 格式 m_<epochMillis>_<4位随机>；全局唯一，是 get/delete/setState 的唯一句柄。 */
    val id: String,
    val pkg: String,
    /** 记录当时的 App 版本。App 改版只降置信、不删记忆（设计文档 §10）。 */
    val appVersion: String = "",
    /** 归一化意图（AI 写入），也是 v1 的 goal 迁移过来的落脚点。 */
    val intent: String,
    val keywords: List<String> = emptyList(),
    val title: String = "",
    val steps: List<MemoryStep> = emptyList(),
    /** active|suspect|disabled —— 取值见 [MemoryContract]，别写字面量。 */
    val state: String = "active",
    val hits: Int = 0,
    val misses: Int = 0,
    val createdAt: Long,
    val lastUsed: Long = 0,
    /** 这条记忆是哪次录制剪出来的（轨迹契约 docs/A11Y_TRACE_CONTRACT.md 的 traceId）。空 = 非录制来源（v1 迁移 / AI 手写）。 */
    val originTraceId: String = "",
    /** 修正版本号：patch/extend/fork 一次 +1，旧版进 history（M6 用）。 */
    val rev: Int = 1,
    /** 待确认：单次成功先存 pending，同一轨迹第二次成功才转正（防单次偶然污染）。 */
    val pending: Boolean = true,
    val pendingHits: Int = 0,
    /** bge 512 维意图向量。落盘时按 int8 量化（见 MemoryCodec），内存里是原始 float。 */
    val vector: List<Float> = emptyList(),
    /**
     * 这条记忆「起点屏」的指纹（v1 的 fp 原样迁过来；v2 由录制器采集）。
     *
     * M4 检索的第一步是**结构粗筛**：不同应用/不同页面的意图话术可以很像，
     * 但屏幕文本集合（Jaccard）差得远。先粗筛再算向量，既快也防误命中。
     */
    val sceneFp: List<String> = emptyList(),
) {
    fun isActive(): Boolean = state == MemoryContract.STATE_ACTIVE

    /**
     * 结构粗筛用的指纹：优先整条 [sceneFp]，退回首步的 [MemoryStep.preFp]。
     *
     * 为什么两级：v2 录制时两处都有（整条是首步的超集，取整条信息更多）；
     * v1 迁移只写了 sceneFp；手工/半成品条目可能只填了首步 preFp。
     * 调用方不该关心这个差别，所以收口在这一个函数里。
     */
    fun fingerprint(): List<String> =
        if (sceneFp.isNotEmpty()) sceneFp else steps.firstOrNull()?.preFp.orEmpty()

    /**
     * 命中一次：hits+1、清零 miss 连击、刷新 lastUsed。
     *
     * 注意**不动** pending/pendingHits —— 转正由入库流程判定（M7 F7.2），
     * 存储层不替上层决定「这条算不算验证过」。
     */
    fun afterHit(now: Long): MemoryEntry =
        copy(hits = hits + 1, misses = 0, lastUsed = now)

    /**
     * 失败一次：misses+1，达到 [MemoryContract.MAX_MISSES] 自动转 suspect。
     *
     * **不刷新 lastUsed**：lastUsed 表示「最后一次成功复用」，失败的记忆不该看起来新鲜，
     * 否则一条坏记忆会因为反复失败而一直躲过 LRU 淘汰（设计文档 §3D 第 3 条要的是
     * 「连续失败就降级」，不是「越失败越新」）。
     */
    fun afterMiss(): MemoryEntry = copy(
        misses = misses + 1,
        state = if (misses + 1 >= MemoryContract.MAX_MISSES) MemoryContract.STATE_SUSPECT else state,
    )
}

/**
 * 重放用的单步（设计文档 §2 的 act / target / verify 压平版）。
 *
 * - target* 是**动作目标元素**，重放前必须用它做指纹预校验（对不上就停，绝不盲点）。
 * - expectAfter 是**执行后的自证条件**（屏幕应出现的文本），对应轨迹契约里的 verify.anyText。
 * - sourceIndex 保留原始步骤号：剪枝删掉中间几步后，还能回答「这是原来第几步」。
 */
data class MemoryStep(
    val sourceIndex: Int,
    /** tap|tap_id|input|key|scroll|launch|...（与轨迹契约 §2.2 的 endpoint 同一套词）。 */
    val action: String,
    val targetText: String = "",
    val targetRole: String = "",
    val targetViewId: String = "",
    /** 归一化 0..1 的 [l, t, r, b]；跨分辨率可用。长度不为 4 视为没有坐标。 */
    val targetBounds: List<Float> = emptyList(),
    val args: Map<String, String> = emptyMap(),
    val expectAfter: List<String> = emptyList(),
    /**
     * 执行这一步**之前**的屏幕指纹。
     *
     * 首步的 preFp 是「这条记忆从哪来」的结构证据，M4 的 Jaccard 就靠它。
     * v1 迁移时只有整条指纹，所以**首步 preFp 与 MemoryEntry.sceneFp 相同，后续步骤为空**
     * —— 这是已知的信息损失，别把它当成真采集的逐步指纹（M7 自动入库时才有真值）。
     */
    val preFp: List<String> = emptyList(),
) {
    /** 有没有可用来定位的锚点（文本 / viewId / 完整矩形三者之一）。 */
    fun hasAnchor(): Boolean =
        targetText.isNotBlank() || targetViewId.isNotBlank() || targetBounds.size == 4

    /** 点击中心（归一化坐标）。没有完整矩形返回 null —— 调用方自己决定兜底，不许猜。 */
    fun center(): Pair<Float, Float>? =
        if (targetBounds.size == 4) {
            (targetBounds[0] + targetBounds[2]) / 2f to (targetBounds[1] + targetBounds[3]) / 2f
        } else {
            null
        }
}

/**
 * 容量策略：每应用 50 条 / 全局 500 条（设计文档 §5、§10）。
 *
 * ## 为什么是纯函数、单独一个对象
 * [MemoryStore] 依赖 Context/File，宿主机 JVM 编不过；而「该淘汰谁」恰恰是最容易写错、
 * 也最值得钉死边界的部分（淘汰错一条，用户刚学会的路径就白学了）。
 * 抽成不碰 IO 的纯函数后，规则能在 scripts/jvm/BehaviourTest.kt 里逐条断言，
 * 落盘那层只剩「拿到名单 → 删掉 → 写回」，没有分支就没有 bug。
 * 与 [TraceRetention] 同一套取舍。
 */
object MemoryRetention {
    const val PER_PKG = 50
    const val GLOBAL = 500

    /**
     * 排序键：**越靠前越该被淘汰**。
     *
     * 1. state：disabled(0) < suspect(1) < active(2)。
     *    disabled 是负样本，留着只为「别再这么干」，最先扔；suspect 已经不自动命中，
     *    扔它比扔一条还能用的 active 划算。
     * 2. hits 升序：没人用过的先走。
     * 3. lastUsed 升序：同样没人用，搁得久的先走（LRU）。
     * 4. id 升序：**纯为确定性**。同一份输入必须永远给出同一份名单，
     *    否则断言会随 Map 迭代顺序飘，门禁就成了掷骰子。
     */
    private val EVICT_ORDER: Comparator<MemoryEntry> = compareBy<MemoryEntry>(
        { stateRank(it.state) },
        { it.hits },
        { it.lastUsed },
        { it.id },
    )

    private fun stateRank(state: String): Int = when (state) {
        MemoryContract.STATE_DISABLED -> 0
        MemoryContract.STATE_SUSPECT -> 1
        else -> 2
    }

    /**
     * 给出该淘汰的 id（空 = 不用淘汰）。
     *
     * 顺序：**先按每应用上限削，再按全局上限削**。
     * 只削全局会让某个超大包继续占着份额，只削每包则全局 500 条上限失效。
     *
     * @param entries 全部记忆（各包混在一起）。同一 id 出现多次只算一个。
     * @param perPkg 每应用上限；传 0 表示清空该包。
     * @param global 全局上限；传 0 表示全清。
     * @return 应淘汰的 id，顺序为「先各包溢出（包名升序），再全局溢出」。
     */
    fun toEvict(
        entries: List<MemoryEntry>,
        perPkg: Int = PER_PKG,
        global: Int = GLOBAL,
    ): List<String> {
        if (entries.isEmpty()) return emptyList()
        val cap = perPkg.coerceAtLeast(0)
        val globalCap = global.coerceAtLeast(0)

        // 按包分桶；包名排序保证结果与入参顺序无关
        val byPkg = LinkedHashMap<String, MutableList<MemoryEntry>>()
        for (e in entries) byPkg.getOrPut(e.pkg) { mutableListOf() }.add(e)

        val victims = LinkedHashSet<String>()
        val survivors = ArrayList<MemoryEntry>(entries.size)
        for (pkg in byPkg.keys.sorted()) {
            val list = byPkg.getValue(pkg)
            val overflow = list.size - cap
            if (overflow <= 0) {
                survivors.addAll(list)
                continue
            }
            val worst = list.sortedWith(EVICT_ORDER).take(overflow)
            val worstIds = HashSet<String>(worst.size)
            for (w in worst) {
                victims.add(w.id)
                worstIds.add(w.id)
            }
            for (e in list) if (e.id !in worstIds) survivors.add(e)
        }

        // 全局上限只对「已经过了每包关」的那批生效：已判死的条目不该再占名额，
        // 否则一次裁剪会多删一批本该留下的（与 TraceRetention 同一取舍）。
        val overGlobal = survivors.size - globalCap
        if (overGlobal > 0) {
            for (e in survivors.sortedWith(EVICT_ORDER).take(overGlobal)) victims.add(e.id)
        }
        return victims.toList()
    }
}

/**
 * 记忆统计的**纯格式化器**（[MemoryStore.stats] 的纯逻辑部分）。
 */
object MemorySummary {

    /**
     * 把一批记忆压成一行「人读写给模型看 + JSON 计数给程序看」。
     *
     * ## 为什么单独抽出来
     * [MemoryStore.stats] 要 Context（读盘），没法在宿主机 JVM 上跑；而**格式本身**
     * 是最容易出问题的地方：少一个字段、数字对不上、混进一个非 ASCII 字符（i18n 门禁
     * 会红）。抽成纯函数后，这三件事都能被 BehaviourTest 逐字断言。
     *
     * ## 为什么文案全是英文
     * 这个字符串会被 /memory/stats 直接吐给调用方；本仓库 i18n 门禁禁止 Kotlin 字面量里
     * 出现 CJK，中文只能写在注释里。新代码没有 SceneMemory 那条豁免。
     *
     * ## 为什么手拼 JSON 而不是用 org.json
     * 值全是计数（数字），没有任何需要转义的内容；而 org.json 在宿主机 JVM 上是 Stub，
     * 用了它这个函数就没法被单测。字段顺序固定也让两次输出可以逐字比对（diff 有意义）。
     */
    fun of(entries: List<MemoryEntry>): String {
        val total = entries.size
        val pkgs = entries.map { it.pkg }.toSortedSet().size
        val active = entries.count { it.state == MemoryContract.STATE_ACTIVE }
        val suspect = entries.count { it.state == MemoryContract.STATE_SUSPECT }
        val disabled = entries.count { it.state == MemoryContract.STATE_DISABLED }
        val pending = entries.count { it.pending }
        val hits = entries.sumOf { it.hits }
        val misses = entries.sumOf { it.misses }
        val json = "{\"schema\":" + MemoryContract.SCHEMA +
            ",\"total\":" + total +
            ",\"pkgs\":" + pkgs +
            ",\"active\":" + active +
            ",\"suspect\":" + suspect +
            ",\"disabled\":" + disabled +
            ",\"pending\":" + pending +
            ",\"hits\":" + hits +
            ",\"misses\":" + misses + "}"
        return "memories=" + total + " pkgs=" + pkgs + " active=" + active +
            " suspect=" + suspect + " disabled=" + disabled + " pending=" + pending +
            " hits=" + hits + " misses=" + misses + " :: " + json
    }
}

/**
 * 记忆 v2 的存储层：files/memory/v2/<pkg>.json，一个文件 = 该应用的全部记忆数组。
 *
 * ## 为什么按应用分文件
 * v1 是单个 scene_memory.json（上限 200 条全局）：任一应用膨胀都会拖慢所有人的读写，
 * 也没法「只清某个应用的记忆」。按 pkg 分文件后：按包读只碰一个文件、按包清只是删文件、
 * 坏一个应用的数据不会带走其它的。
 *
 * ## 线程模型
 * AgentBridge 的 HTTP 线程、本地循环线程、设置页会同时进来。本对象所有入口都在
 * 同一把锁上串行化（[LOCK]），一次读写 = 一次完整事务；调用方不需要自己加锁。
 * 锁内只做小文件读写（每条 2-6KB、每应用 ≤50 条），不做网络、不睡眠。
 *
 * ## 为什么不加内存缓存
 * 缓存要处理「文件被外部改了 / 被清空 / 进程被杀」三种失效路径，省下的是每次几毫秒的
 * JSON 解析。记忆读写的调用点（一次任务一次）远没到需要缓存的量级，
 * 少一个不变量就少一类「明明删了却还能命中」的鬼故事。等实测有瓶颈再加。
 *
 * ## 容错
 * 所有 IO 都是 runCatching + Log.w：写不进去只返回 false，绝不把异常抛给 HTTP 线程
 * —— 一条记忆存不下不该让整个 /loop 请求 500。读坏的文件退化成空列表
 * （原文件保留不覆盖，除非用户真的再写这个包）。
 */
object MemoryStore {

    private const val TAG = "MemoryStore"

    /** 所有落盘操作的串行化点（见类注释的线程模型）。 */
    private val LOCK = Any()

    /** 列表顺序：按包名，再按创建时间倒序（列表页「最近学的在最上面」），最后按 id 保证稳定。 */
    private val LIST_ORDER: Comparator<MemoryEntry> =
        compareBy({ it.pkg }, { -it.createdAt }, { it.id })

    private const val SUFFIX_LEN = 5   // ".json"

    // ==================== 查询 ====================

    /** 该应用的全部记忆；pkg 为空则返回所有应用（按 [LIST_ORDER] 排好）。 */
    fun list(ctx: Context, pkg: String? = null): List<MemoryEntry> = synchronized(LOCK) {
        val all = ArrayList<MemoryEntry>()
        for ((_, group) in loadAllLocked(ctx)) all.addAll(group)
        val wanted = pkg?.trim()
        val out = if (wanted.isNullOrEmpty()) all else all.filter { it.pkg == wanted }
        out.sortedWith(LIST_ORDER)
    }

    /** 按 id 取一条。id 全局唯一，所以要扫所有包文件（包数是个位数，代价可忽略）。 */
    fun get(ctx: Context, id: String): MemoryEntry? = synchronized(LOCK) {
        if (id.isBlank()) return@synchronized null
        for ((_, group) in loadAllLocked(ctx)) {
            for (e in group) if (e.id == id) return@synchronized e
        }
        null
    }

    // ==================== 写入 ====================

    /**
     * 新增或覆盖同 id 的条目，落盘成功返回 true。
     *
     * 写入前先跑一次容量淘汰：先落盘再淘汰会多一次写，还会在两次写之间留下超限状态
     * （进程正好在这里被杀 = 永远超限）。
     *
     * @return false = ① 写盘失败；② 写进去了但**立刻被淘汰**（该包已满且这条比在场的都差）。
     *         第二种也返回 false 是刻意的：调用方需要知道「这条没留下」，否则会以为学会了。
     */
    fun upsert(ctx: Context, entry: MemoryEntry): Boolean = synchronized(LOCK) {
        upsertLocked(ctx, entry)
    }

    /** 硬删（任务卡 §API 指定硬删；想保留证据请用 [setState] 改成 disabled）。 */
    fun delete(ctx: Context, id: String): Boolean = synchronized(LOCK) {
        if (id.isBlank()) return@synchronized false
        val groups = loadAllLocked(ctx)
        for ((pkg, group) in groups) {
            val at = group.indexOfFirst { it.id == id }
            if (at < 0) continue
            group.removeAt(at)
            val ok = writePkgLocked(ctx, pkg, group)
            Log.i(TAG, "delete " + pkg + "/" + id + " ok=" + ok)
            return@synchronized ok
        }
        false
    }

    /** 改状态（active|suspect|disabled）。非法状态直接拒绝，不落盘。 */
    fun setState(ctx: Context, id: String, state: String): Boolean = synchronized(LOCK) {
        // lowercase() 是 region-independent 的（Kotlin 1.5+），不吃系统语言设置在字符串上的差异。
        val wanted = state.trim().lowercase()
        if (wanted !in MemoryContract.STATES) {
            Log.w(TAG, "setState rejected: unknown state " + state)
            return@synchronized false
        }
        val groups = loadAllLocked(ctx)
        for ((pkg, group) in groups) {
            val at = group.indexOfFirst { it.id == id }
            if (at < 0) continue
            if (group[at].state == wanted) return@synchronized true
            group[at] = group[at].copy(state = wanted)
            return@synchronized writePkgLocked(ctx, pkg, group)
        }
        false
    }

    /** 命中一次（复用成功）。 */
    fun markHit(ctx: Context, id: String, now: Long = System.currentTimeMillis()): Unit =
        synchronized(LOCK) {
            val groups = loadAllLocked(ctx)
            for ((pkg, group) in groups) {
                val at = group.indexOfFirst { it.id == id }
                if (at < 0) continue
                group[at] = group[at].afterHit(now)
                writePkgLocked(ctx, pkg, group)
                return@synchronized
            }
        }

    /** 失败一次；misses 达到 [MemoryContract.MAX_MISSES] 自动转 suspect（不再自动命中）。 */
    fun markMiss(ctx: Context, id: String, now: Long = System.currentTimeMillis()): Unit =
        synchronized(LOCK) {
            val groups = loadAllLocked(ctx)
            for ((pkg, group) in groups) {
                val at = group.indexOfFirst { it.id == id }
                if (at < 0) continue
                val before = group[at]
                val after = before.afterMiss()
                group[at] = after
                writePkgLocked(ctx, pkg, group)
                // now 不写进条目（见 MemoryEntry.afterMiss 的解释），但留在日志里便于与轨迹对时间
                Log.w(
                    TAG,
                    "miss " + pkg + "/" + id + " now=" + now + " misses=" + after.misses +
                        " state=" + after.state,
                )
                return@synchronized
            }
        }

    // ==================== 诊断 ====================

    /**
     * 人读摘要 + JSON 计数（设计文档 P5 的观测项要数字，不是句子）。
     *
     * 文案一律英文：这个字符串会被 /memory/stats 直接吐给调用方，而本仓库的 i18n 门禁
     * 禁止 Kotlin 字面量里出现 CJK（中文只能进注释）——新代码没有 SceneMemory 那条豁免。
     */
    fun stats(ctx: Context): String = synchronized(LOCK) {
        val all = ArrayList<MemoryEntry>()
        for ((_, group) in loadAllLocked(ctx)) all.addAll(group)
        // 取数是 IO，拼字是纯函数 —— 后者能在宿主机 JVM 上被逐字断言（见 MemorySummary）
        MemorySummary.of(all)
    }

    // ==================== v1 迁移 ====================

    /**
     * 把 v1 的 scene_memory.json 迁进 v2，返回迁移条数。
     *
     * ## 规则（任务卡 F3.2）
     * 1. goal → intent 与 title；plan.steps 尽力转成 [MemoryStep]（转不了的字段留默认值，不编造）。
     * 2. pkg 从指纹里的 pkg: 元素取；找不到就归到 unknown 包（不同应用混桶会造成误命中）。
     * 3. hits/misses/pending/pendingHits/created/updated 原样保留 —— G3 要求
     *    「迁移后旧 v1 命中行为不变」，统计口径就不能丢。
     * 4. 全部写成功才把原文件改名 .migrated（**不删**，可回滚）。任一条写失败就保留原文件
     *    并返回 0，下次启动重试。
     * 5. 幂等：原文件已被改名过就不会再进来；即使被手工还原，迁移 id 是
     *    「created + goal 哈希」算出来的**确定性 id**，重跑只会覆盖同一条，不会翻倍。
     *
     * ## 指纹的归宿（M4 依赖）
     * v1 的 fp 原样写进 [MemoryEntry.sceneFp]，同时填到**首步**的 [MemoryStep.preFp]
     * （M4 的结构比对按「首步 pre.fp」取）。v1 没有逐步指纹，所以第二步往后 preFp 为空
     * —— 已知的信息损失，等 M7 录制器真采一遍才会补齐。
     *
     * ## 已知取舍（需要上层知晓）
     * 多锚点（target.text 有多个候选）只保留第一个；v2 的 targetText 是单字符串。
     */
    fun migrateV1(ctx: Context): Int = synchronized(LOCK) {
        migrateV1Locked(ctx)
    }

    // ==================== 内部实现（都在 LOCK 里跑） ====================

    private fun upsertLocked(ctx: Context, raw: MemoryEntry): Boolean {
        val entry = if (raw.pkg.isBlank()) raw.copy(pkg = MemoryContract.UNKNOWN_PKG) else raw
        if (entry.id.isBlank()) {
            Log.w(TAG, "upsert rejected: entry has no id")
            return false
        }
        val groups = loadAllLocked(ctx)
        val group = groups.getOrPut(entry.pkg) { mutableListOf() }
        val at = group.indexOfFirst { it.id == entry.id }
        if (at >= 0) group[at] = entry else group.add(entry)

        val evicted = MemoryRetention.toEvict(
            groups.values.flatten(),
            MemoryRetention.PER_PKG,
            MemoryRetention.GLOBAL,
        )
        if (evicted.isNotEmpty()) {
            val dead = HashSet<String>(evicted)
            for ((_, list) in groups) list.removeAll { it.id in dead }
            Log.i(TAG, "retention evicted " + evicted.size + " entr(ies)")
        }

        var ok = true
        for ((pkg, list) in groups) if (!writePkgLocked(ctx, pkg, list)) ok = false

        if (entry.id in evicted) {
            Log.w(TAG, "upsert evicted immediately: " + entry.pkg + "/" + entry.id)
            return false
        }
        return ok
    }

    private fun migrateV1Locked(ctx: Context): Int {
        val v1 = File(ctx.filesDir, MemoryContract.V1_FILE)
        if (!v1.isFile) return 0

        val marker = File(ctx.filesDir, MemoryContract.V1_MIGRATED)
        val groups = loadAllLocked(ctx)
        if (marker.isFile && groups.values.any { it.isNotEmpty() }) {
            // 已经迁过（且 v2 里有数据）：不动，免得把用户后来的修正冲掉
            return 0
        }

        val text = runCatching { v1.readText() }.getOrElse {
            Log.w(TAG, "migrate: cannot read v1: " + it.message)
            return 0
        }
        val entries = MemoryCodec.decodeV1(text)
        if (entries.isEmpty() && text.trim().length > 4) {
            // 有内容但一条都没转出来：仍然归档（内容还在 .migrated 里，可回滚），
            // 但必须留下痕迹，否则「迁移静默丢了全部记忆」永远查不出来。
            Log.w(TAG, "migrate: v1 has " + text.length + " chars but 0 entry survived conversion")
        }

        for (e in entries) {
            val group = groups.getOrPut(e.pkg) { mutableListOf() }
            val at = group.indexOfFirst { it.id == e.id }
            if (at >= 0) group[at] = e else group.add(e)
        }

        // 迁移也要守容量：v1 允许 200 条全局，v2 是 50/包，不削会立刻超限
        val evicted = MemoryRetention.toEvict(
            groups.values.flatten(),
            MemoryRetention.PER_PKG,
            MemoryRetention.GLOBAL,
        )
        if (evicted.isNotEmpty()) {
            val dead = HashSet<String>(evicted)
            for ((_, list) in groups) list.removeAll { it.id in dead }
        }

        var ok = true
        for ((pkg, list) in groups) if (!writePkgLocked(ctx, pkg, list)) ok = false
        if (!ok) {
            Log.w(TAG, "migrate: v2 write failed, v1 kept for retry")
            return 0
        }

        archiveV1Locked(ctx, v1)
        Log.i(TAG, "migrate: " + entries.size + " entr(ies) from v1")
        return entries.size
    }

    /**
     * 归档 v1 文件（改名，不删）。
     *
     * 已经有旧的 .migrated 时（回滚后又跑了一次）加时间戳后缀，绝不覆盖上一份存档 ——
     * 覆盖等于把用户的回滚点抹掉。
     */
    private fun archiveV1Locked(ctx: Context, src: File): Boolean {
        val plain = File(ctx.filesDir, MemoryContract.V1_MIGRATED)
        val target =
            if (plain.exists()) File(ctx.filesDir, MemoryContract.V1_MIGRATED + "." + System.currentTimeMillis())
            else plain
        val renamed = runCatching { src.renameTo(target) }.getOrDefault(false)
        if (renamed) {
            Log.i(TAG, "migrate: v1 archived to " + target.name)
            return true
        }
        // 改名失败（跨挂载点/权限）时退化成「复制 + 删源」：内容一样完整地留在 .migrated，
        // 语义与改名等价，只是多一次 IO。
        val copied = runCatching { target.writeText(src.readText()) }.isSuccess
        val removed = copied && runCatching { src.delete() }.getOrDefault(false)
        if (!removed) Log.w(TAG, "migrate: archive v1 failed (copied=" + copied + ")")
        return removed
    }

    private fun rootDirLocked(ctx: Context): File = File(ctx.filesDir, MemoryContract.DIR)

    /**
     * 包名 → 文件名。
     *
     * 包名里的点号原样保留（一眼能看出是包名，Android 各分区也都接受）；
     * 只把路径分隔符换成下划线 —— 否则 a/b 这类异常包名会把文件写到目录外面去。
     */
    private fun fileNameOf(pkg: String): String {
        val safe = pkg.trim().ifEmpty { MemoryContract.UNKNOWN_PKG }
            .replace('/', '_')
            .replace('\\', '_')
        return safe + ".json"
    }

    /** 读所有包文件。键 = 文件名去掉 .json（即包名），值 = 该包条目。 */
    private fun loadAllLocked(ctx: Context): LinkedHashMap<String, MutableList<MemoryEntry>> {
        val out = LinkedHashMap<String, MutableList<MemoryEntry>>()
        val dir = rootDirLocked(ctx)
        if (!dir.isDirectory) return out
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return out
        // 按文件名排序：同样的目录内容永远按同样顺序读出，诊断输出才可比对
        for (f in files.sortedBy { it.name }) {
            val pkg = f.name.dropLast(SUFFIX_LEN)
            out[pkg] = readPkgLocked(f).toMutableList()
        }
        return out
    }

    private fun readPkgLocked(f: File): List<MemoryEntry> = runCatching {
        MemoryCodec.decodeArray(f.readText())
    }.getOrElse {
        // 坏文件退化成空列表但**不删**：留着它，下一次写这个包时才覆盖，
        // 期间用户还能把它捞出来人工看看出了什么事。
        Log.w(TAG, "read " + f.name + " failed: " + it.message)
        emptyList()
    }

    /**
     * 写回一个包。空包直接删文件（目录里不留空文件，列表页才不会出现幽灵应用）。
     *
     * 先写 .tmp 再改名：进程在写的中途被杀时，宁可丢掉这一次更新，
     * 也不要留下半个 JSON —— 半个 JSON 会让这个包的所有记忆一起失效。
     */
    private fun writePkgLocked(ctx: Context, pkg: String, list: List<MemoryEntry>): Boolean {
        val dir = rootDirLocked(ctx)
        val target = File(dir, fileNameOf(pkg))
        if (list.isEmpty()) {
            return runCatching { if (target.exists()) target.delete() else true }.getOrElse {
                Log.w(TAG, "delete empty " + target.name + " failed: " + it.message)
                false
            }
        }
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "cannot create memory dir: " + dir.absolutePath)
                return@runCatching false
            }
            val text = MemoryCodec.encodeArray(list)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(text)
            if (tmp.renameTo(target)) return@runCatching true
            // 改名失败（目标存在且平台不支持覆盖）时退回直写
            target.writeText(text)
            tmp.delete()
            true
        }.getOrElse {
            Log.w(TAG, "write " + pkg + " failed: " + it.message)
            false
        }
    }
}

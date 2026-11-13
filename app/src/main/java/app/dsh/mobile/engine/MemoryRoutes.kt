package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import app.dsh.mobile.NumFmt
import org.json.JSONArray
import org.json.JSONObject

/**
 * 无障碍记忆系统 v2 的**路由与录制挂钩**（Lead 独占，避免多人同时改 AgentBridge 的 108 条 when 块）。
 *
 * ## 职责
 * 1. 把 trace 与 memory-v2 的处理集中在这里，AgentBridge 只加一行注册。
 * 2. 提供三个「动作生命周期」钩子：`onActionBegin` / `onActionDone` / `onBoundaryEnd`，
 *    AgentBridge 在 taskGate / taskDone / endBoundary 各调一行即可开始录制。
 *
 * ## 为什么录制挂在桥的动作边界上，而不是挂在 loop 执行器里
 * 挂 loop 只能录「走本地闭环」的任务；模型逐个调 `/tap` `/gesture` 的任务一样该被记住。
 * 桥的动作边界是**唯一所有动作都会经过**的地方。
 *
 * ## 指纹成本
 * 每个动作只取**一次** dump（动作之后），前指纹复用上一次的后指纹 ——
 * 这样额外开销是 1 次读屏（~30-60ms），而不是 2 次。第一次动作没有历史指纹，
 * 用动作前的 dump 补一次。
 */
object MemoryRoutes {

    private const val TAG = "MemoryRoutes"

    /** 录制开关（设置页会接这个）。关掉时一行也不写。 */
    @Volatile var recordEnabled: Boolean = true

    /** 上一次动作后的屏幕指纹，作为下一次动作的 beforeFp。 */
    @Volatile private var lastFp: Set<String> = emptySet()
    /** 上一屏的候选节点（含 text/role/中心坐标）。用来把「坐标点击」翻译成「元素锚点」。 */
    @Volatile private var lastCands: List<FastPicker.Candidate> = emptyList()
    @Volatile private var lastPkg: String = ""
    @Volatile private var lastTs: Long = 0
    @Volatile private var lastStatus: String = TraceStatus.OK
    @Volatile private var lastMs: Long = 0
    /** 本次轨迹里成功的动作数（决定「这次任务值不值得记」）。 */
    @Volatile private var okSteps: Int = 0

    // ==================== 动作生命周期钩子 ====================

    /** 动作被放行（taskGate 之后调用）：没有在录就开一条新轨迹。 */
    fun onActionBegin(ctx: Context) {
        if (!recordEnabled) return
        runCatching {
            val svc = app.dsh.mobile.DshAccessibilityService.instance ?: return
            val pkg = svc.foregroundPackage().orEmpty()
            if (!TaskRecorder.isRecording()) {
                TaskRecorder.begin(ctx, "", pkg)
                val snap = snapshotOf(svc, pkg)
                lastFp = snap.first
                lastCands = snap.second
                lastPkg = pkg
                okSteps = 0
            }
            lastTs = System.currentTimeMillis()
        }.onFailure { Log.w(TAG, "onActionBegin failed: ${it.message}") }
    }

    /**
     * 本次 /loop/run 是否已由执行器逐步骤录制（有则桥不再记那条不可重放的 loop_step）。
     * 执行器每次进入 loop 都会先 onLoopBegin() 清标记。
     */
    @Volatile private var loopRecorded: Boolean = false

    /** /loop/run 开始（LocalLoopExecutor 入口调用）：清掉上一轮的「已逐步骤录制」标记。 */
    fun onLoopBegin() {
        loopRecorded = false
    }

    /**
     * loop 内部一步（M1 补，由 LocalLoopExecutor 在**该步成功**后调用）。
     *
     * dump 优先用验证阶段已经读到的那一屏：省掉一次读屏，录制开销从 +40ms 降到 ~0。
     * 传 null 时自己拍一次（乐观通过、没有 verify 的步走这条）。
     */
    fun onLoopStep(
        svc: app.dsh.mobile.DshAccessibilityService,
        endpoint: String,
        args: Map<String, String>,
        anchorText: String,
        dump: String?,
        ms: Long,
    ) {
        if (!recordEnabled || !TaskRecorder.isRecording()) return
        runCatching {
            var pkg = svc.foregroundPackage().orEmpty()
            val after: Set<String>
            val cands: List<FastPicker.Candidate>
            if (!dump.isNullOrBlank()) {
                after = SceneMemory.fingerprint(dump, pkg)
                cands = FastPicker.parseCompact(dump)
            } else {
                // 没有 verify 的步（典型是 key back）会换页：立刻拍 dump 拍到的是**换页前**的屏，
                // 于是 afterFp 记成旧页面，重放时这一步必然 verify-failed（实测就是这么错的）。
                // 等界面稳定再拍，代价只落在这些没 verify 的步上。
                runCatching { svc.awaitSettle(120L, 1_500L, 60L) }
                // 换页后前台包也可能变（设置 → 桌面），指纹里的 pkg: 元素必须用新的。
                pkg = svc.foregroundPackage().orEmpty().ifBlank { pkg }
                val snap = snapshotOf(svc, pkg)
                after = snap.first
                cands = snap.second
            }
            TaskRecorder.step(
                endpoint = endpoint,
                args = args,
                beforeFp = lastFp,
                afterFp = after,
                anchor = anchorText.ifBlank { null }?.let { TraceAnchor(text = it) },
                status = TraceStatus.OK,
                via = if (after != lastFp) TraceVia.FRAME else TraceVia.NONE,
                ms = ms,
                caller = "loop",
                protected = false,
            )
            lastFp = after
            lastCands = cands
            lastPkg = pkg
            lastTs = System.currentTimeMillis()
            okSteps++
            loopRecorded = true
        }.onFailure { Log.w(TAG, "onLoopStep failed: ${it.message}") }
    }

    /** 动作结束（taskDone 里调用）：取一次后指纹，落一行事件。 */
    fun onActionDone(ctx: Context, path: String, decision: String, body: String = "") {
        if (!recordEnabled || !TaskRecorder.isRecording()) return
        // loop 内部步骤已经逐步进了轨迹 → 这条外壳（无 args、重放必失败）直接丢弃。
        if (loopRecorded && endpointOf(path) == TraceEndpoint.LOOP_STEP) {
            loopRecorded = false
            return
        }
        runCatching {
            val svc = app.dsh.mobile.DshAccessibilityService.instance ?: return
            var pkg = svc.foregroundPackage().orEmpty()
            val now = System.currentTimeMillis()
            // launch 是把前台换成另一个 App：立刻拍 dump 只能拍到换包前的屏（实测就是这么错的），
            // 等它稳定一下再拍。只对 launch 付这份代价，其余动作保持原开销。
            if (endpointOf(path) == TraceEndpoint.LAUNCH) {
                runCatching { svc.awaitSettle(120L, 1_500L, 60L) }
                // 换包后必须重读前台包：指纹里的 pkg: 元素和下面 lastPkg 都靠它，
                // 沿用旧包会让这条轨迹的「在哪个 App 里」从头就错。
                pkg = svc.foregroundPackage().orEmpty().ifBlank { pkg }
            }
            val tDump = System.currentTimeMillis()
            val snap = snapshotOf(svc, pkg)
            val after = snap.first
            val dumpMs = System.currentTimeMillis() - tDump
            // 锚点必须用「动作发生前那一屏」的候选来解析 —— 动作之后屏幕已经变了。
            val anchor = anchorFor(path, body, lastCands)
            // 成败看两处：HTTP 状态 + 响应体里的 ok 字段。
            // 只看状态会漏掉一大类「200 但 ok:false」的端点（如 /scroll-find 没找到目标），
            // 那些动作在轨迹里被记成 ok，剪枝时就删不掉了 —— 记忆里会留下一条假成功。
            val bodySaysFail = body.contains("\"ok\":false")
            val status = when {
                decision == "refused" -> TraceStatus.SKIPPED
                decision == "ok" && !bodySaysFail -> TraceStatus.OK
                else -> TraceStatus.FAILED
            }
            TaskRecorder.step(
                endpoint = endpointOf(path),
                args = argsOf(path, body),
                beforeFp = lastFp,
                afterFp = after,
                anchor = anchor,
                status = status,
                via = if (after != lastFp) TraceVia.FRAME else TraceVia.NONE,
                ms = if (lastTs > 0) now - lastTs else 0,
                caller = "bridge",
                protected = false,
            )
            lastFp = after
            lastCands = snap.second
            lastPkg = pkg
            lastStatus = status
            if (status == TraceStatus.OK) okSteps++
            lastMs = now - lastTs
            // 录制开销要能查：dump 与写盘分开计时，超阈值才打日志（不刷屏）。
            // 实测口径见 docs/reports/G1.md；超过 20ms 说明写盘路径退化了。
            val total = System.currentTimeMillis() - now
            if (total - dumpMs > 20) {
                Log.i(TAG, "record slow: dump=${dumpMs}ms write=${total - dumpMs}ms total=${total}ms")
            }
        }.onFailure { Log.w(TAG, "onActionDone failed: ${it.message}") }
    }

    /** 行动边界收工（endBoundary 里调用）：封存轨迹并清理过期文件。 */
    fun onBoundaryEnd(ctx: Context) {
        runCatching {
            val traceId = TaskRecorder.currentTraceId()
            // 判「这次任务值不值得记」用**成功步数**，不是最后一步的成败：
            // 中间某一步失败（点了屏幕上不存在的文本）很常见，剪枝会把那一步删掉；
            // 用 lastStatus 一刀切会让整条轨迹作废 —— 实测就是这么丢掉第一条记忆的。
            val okSoFar = okSteps >= 2
            if (TaskRecorder.isRecording()) {
                TaskRecorder.end(okSoFar, "boundary")
            }
            TaskRecorder.pruneOld(ctx)
            lastFp = emptySet()
            lastCands = emptyList()
            lastTs = 0
            okSteps = 0
            // F7.1：任务结束自动学习。成功才学（失败任务只作负样本，由 pruner 拒掉）。
            // 这里不需要模型参与 —— 模型可能压根没意识到「该存记忆」，这类每任务都该发生的事
            // 不能交给模型的记性（v1.7.0 的 FastMode 就栽在这上面）。
            if (autoLearn && okSoFar && traceId != null) {
                learnQuiet(ctx, traceId)
            }
        }.onFailure { Log.w(TAG, "onBoundaryEnd failed: ${it.message}") }
    }

    /** 自动学习开关（设置页可关）。 */
    @Volatile var autoLearn: Boolean = true

    /**
     * 静默学习：把刚结束的轨迹剪枝入库。
     *
     * intent 先给一个「可检索的派生签名」（包名 + 首步与末步的目标文本），
     * 因为桥上拿不到模型心里的那句目标。模型随后可以用 /memory/v2/label 把它改成
     * 一句真正的人话意图 —— 没改也能靠签名做词面召回，不至于白录。
     */
    private fun learnQuiet(ctx: Context, traceId: String) {
        runCatching {
            val doc = TaskRecorder.get(ctx, traceId) ?: return
            if (doc.steps.size < 2) return          // 单步任务不值得记
            val pruned = TracePruner.prune(doc)
            if (pruned.rejected || pruned.steps.isEmpty()) return
            if (TracePruner.unreplayable(pruned.steps).isNotEmpty()) {
                Log.i(TAG, "auto-learn skipped: trace " + traceId + " has loop_step (not replayable)")
                return
            }
            val sig = signatureOf(doc, pruned)
            val home = homePkgOf(doc, pruned)
            val fp = doc.steps.firstOrNull()?.beforeFp ?: emptySet()
            // F7.2：同 (pkg,intent) 且指纹高度重合的旧条目 → 累加 pendingHits，第二次成功即转正
            // 匹配口径：优先用稳定 taskKey；老条目没有 taskKey 时回落到 intent。
            // 用 intent 单独判会出问题：用户一旦「标注」，intent 变成人话，同一任务下次就匹配不上，
            // 于是重复堆新条目（实测踩过）。taskKey 只在自动学习时写、标注不动它。
            val existing = MemoryStore.list(ctx, home).firstOrNull { e ->
                val sameTask = if (e.taskKey.isNotBlank()) e.taskKey == sig else e.intent == sig
                sameTask && sameScene(fp, e.fingerprint().toSet())
            }
            val now = System.currentTimeMillis()
            val steps = pruned.steps.mapIndexed { i, p ->
                MemoryStep(
                    sourceIndex = p.sourceIndex, action = p.action,
                    targetText = p.target?.text.orEmpty(), targetRole = p.target?.role.orEmpty(),
                    targetViewId = p.target?.viewId.orEmpty(),
                    targetBounds = p.target?.bounds ?: emptyList(),
                    args = p.args,
                    // launch 步不设文本后置条件（见 TraceExpect：录制时的 afterFp 常是换包前的屏）
                    expectAfter = TraceExpect.after(p.action, p.expectAfter.toList()),
                    // 每一步都记自己的前置指纹（v1.7.x）。此前只有首步有，理由是
                    // 「v1 只存了整条指纹」—— 但 v2 是逐步采集的，白丢这个信息会让
                    // 后续步骤无法判断「现在是不是已经能做这一步」。
                    preFp = p.preFp.toList().ifEmpty {
                        if (i == 0) fp.toList() else emptyList()
                    },
                )
            }
            if (existing == null) {
                MemoryStore.upsert(ctx, MemoryEntry(
                    id = "m_" + now + "_" + (1000..9999).random(), pkg = home,
                    intent = sig, title = sig, steps = steps, createdAt = now,
                    originTraceId = traceId, pending = true, sceneFp = fp.toList(),
                    taskKey = sig, vector = MemoryIndex.embed(sig) ?: emptyList(),
                ))
                Log.i(TAG, "auto-learn: new pending memory for " + home + " steps=" + steps.size)
            } else {
                val hits = existing.pendingHits + 1
                val promoted = hits >= 2
                MemoryStore.upsert(ctx, existing.copy(
                    steps = steps, pending = !promoted, pendingHits = hits,
                    rev = existing.rev + 1, originTraceId = traceId,
                ))
                Log.i(TAG, "auto-learn: confirmed " + existing.id + " pendingHits=" + hits + " promoted=" + promoted)
            }
        }.onFailure { Log.w(TAG, "learnQuiet failed: ${it.message}") }
    }

    /**
     * 两次执行是不是「同一个屏」。
     *
     * 只看 Jaccard 会误判：设置首页只有 6-7 个文本节点，多出/少掉一个（页面标题、时间）
     * 就能把相似度从 1.0 拉到 0.86 —— 实测就是这样，同一个任务第二次没认出第一次。
     *
     * 所以加一条「包含度」通道：小屏上的增删一两个节点不算换屏，
     * 但必须同时满足「绝对差 ≤2」，否则「20 项里含 3 项」这种也会被当成同一屏。
     */
    private fun sameScene(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        if (union > 0 && inter.toDouble() / union >= 0.9) return true
        val min = minOf(a.size, b.size)
        return inter.toDouble() / min >= 0.9 && Math.abs(a.size - b.size) <= 2
    }

    /**
     * 这条记忆该记在哪个 App 名下。
     *
     * 首步是 `launch` 时，任务真正的「家」是**被启动的那个包**，不是起手时所在的包。
     * 反例（实测）：从桌面起手「打开设置 → 显示 → 深色主题」，轨迹的 doc.pkg 是桌面
     * `com.miui.home`；记忆若挂在桌面名下，用户人在「设置」里按 pkg 过滤根本查不到它，
     * 而挂在设置名下时，人从桌面起手又会被 precheck 的包名比对拒掉 ——
     * 所以除了改这里，[MemoryReplay.precheck] 还补了「首步是启动目标 App 就允许跨包起跑」。
     */
    private fun homePkgOf(doc: TraceDoc, pruned: PruneResult): String {
        val first = pruned.steps.firstOrNull() ?: return doc.pkg
        if (!first.action.equals(TraceEndpoint.LAUNCH, ignoreCase = true)) return doc.pkg
        val to = first.args["package"].orEmpty()
        return to.ifBlank { doc.pkg }
    }

    /** 派生签名：包名 + 首步目标 + 末步目标（拿不到模型那句目标时的可检索替身）。 */
    private fun signatureOf(doc: TraceDoc, pruned: PruneResult): String {
        val first = pruned.steps.firstOrNull()?.target?.text.orEmpty()
        val last = pruned.steps.lastOrNull()?.target?.text.orEmpty()
        val tail = listOf(first, last).filter { it.isNotBlank() }.joinToString(" -> ")
        val home = homePkgOf(doc, pruned)
        return home + (if (tail.isBlank()) "" else ": " + tail)
    }

    /**
     * 给记忆补一句真正的人话意图：POST /memory/v2/label {"id"|"traceId", "intent", "title"}
     * 自动学习只能给派生签名；模型任务完成后调这个，记忆才可被语义检索。
     */
    fun memLabel(ctx: Context, body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        val intent = o.optString("intent")
        if (intent.isBlank()) return 400 to err("missing intent")
        var e = o.optString("id").ifBlank { null }?.let { MemoryStore.get(ctx, it) }
        if (e == null) {
            val tid = o.optString("traceId")
            if (tid.isNotBlank()) {
                e = MemoryStore.list(ctx).firstOrNull { it.originTraceId == tid }
            }
        }
        if (e == null) {
            // 没指定就取最近一条还没被标注的（派生签名里含 ": " 且等于 title）
            e = MemoryStore.list(ctx).maxByOrNull { it.createdAt }
        }
        if (e == null) return 404 to err("no memory to label")
        val updated = e.copy(
            intent = intent,
            title = o.optString("title").ifBlank { intent },
            rev = e.rev + 1,
            // 标注换的是「人话意图」，向量必须跟着重算 —— 否则检索还在用派生签名的向量，
            // 标注等于只改了显示文字。
            vector = MemoryIndex.embed(intent) ?: e.vector,
        )
        val ok = MemoryStore.upsert(ctx, updated)
        200 to JSONObject().put("ok", ok).put("id", updated.id).put("intent", intent).toString()
    }.getOrElse { ex -> Log.w(TAG, "memLabel failed: ${ex.message}"); 400 to err("bad body") }

    // ==================== /trace 路由 ====================

    fun traceList(ctx: Context, query: String): Pair<Int, String> = runCatching {
        val limit = param(query, "limit")?.toIntOrNull() ?: 50
        val arr = JSONArray()
        for (d in TaskRecorder.list(ctx, limit)) {
            arr.put(JSONObject().apply {
                put("id", d.traceId)
                put("startedAt", d.startedAt)
                put("pkg", d.pkg)
                put("goal", d.goal)
                put("steps", if (d.steps.isNotEmpty()) d.steps.size else TaskRecorder.stepCount(ctx, d.traceId))
                d.endOk?.let { put("endOk", it) }
                put("endReason", d.endReason)
            })
        }
        val doc = JSONObject().put("ok", true).put("count", arr.length()).put("traces", arr)
        // 录制状态一起回，调用方一眼能看出「现在是不是在录」
        doc.put("recording", TaskRecorder.isRecording())
        doc.put("current", TaskRecorder.currentTraceId() ?: "")
        doc.put("recordEnabled", recordEnabled)
        200 to doc.toString()
    }.getOrElse { e ->
        Log.w(TAG, "traceList failed: ${e.message}")
        500 to JSONObject().put("ok", false).put("error", "trace list failed").toString()
    }

    fun traceGet(ctx: Context, query: String): Pair<Int, String> = runCatching {
        val id = param(query, "id").orEmpty()
        if (id.isBlank()) return 400 to JSONObject().put("ok", false)
            .put("error", "missing id").toString()
        val d = TaskRecorder.get(ctx, id)
            ?: return 404 to JSONObject().put("ok", false).put("error", "no such trace").toString()
        val steps = JSONArray()
        for (s in d.steps) {
            steps.put(JSONObject().apply {
                put("i", s.index)
                put("ts", s.ts)
                put("endpoint", s.endpoint)
                put("args", JSONObject(s.args as Map<*, *>))
                put("beforeFp", JSONArray(s.beforeFp.toList()))
                put("afterFp", JSONArray(s.afterFp.toList()))
                s.anchor?.let { a ->
                    put("anchor", JSONObject().apply {
                        put("text", a.text); put("role", a.role); put("viewId", a.viewId)
                        put("bounds", JSONArray(a.bounds))
                    })
                }
                put("verify", JSONObject().apply {
                    put("status", s.verify.status); put("via", s.verify.via)
                    put("ms", s.verify.ms); put("retries", s.verify.retries)
                })
                put("caller", s.caller)
                put("protected", s.protected)
            })
        }
        val doc = JSONObject().apply {
            put("ok", true)
            put("id", d.traceId)
            put("startedAt", d.startedAt)
            put("pkg", d.pkg)
            put("goal", d.goal)
            put("schema", d.schema)
            d.endOk?.let { put("endOk", it) }
            put("endReason", d.endReason)
            put("steps", steps)
        }
        200 to doc.toString()
    }.getOrElse { e ->
        Log.w(TAG, "traceGet failed: ${e.message}")
        500 to JSONObject().put("ok", false).put("error", "trace get failed").toString()
    }

    fun traceClear(ctx: Context): Pair<Int, String> = runCatching {
        TaskRecorder.clear(ctx)
        200 to JSONObject().put("ok", true).put("cleared", true).toString()
    }.getOrElse { e ->
        Log.w(TAG, "traceClear failed: ${e.message}")
        500 to JSONObject().put("ok", false).put("error", "trace clear failed").toString()
    }

    /**
     * 录制开关（POST /trace/record {"enabled":true|false}）。
     *
     * 存在的意义有两个：设置页的总开关接这里；门禁要测「开/关两种状态下的动作耗时差」，
     * 没有开关就只能靠重装 APK 换版本，那测不出同机同屏的对照。
     */
    fun traceRecord(body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        val enabled = o.optBoolean("enabled", !recordEnabled)
        recordEnabled = enabled
        200 to JSONObject().put("ok", true).put("recordEnabled", enabled).toString()
    }.getOrElse { e ->
        Log.w(TAG, "traceRecord failed: ${e.message}")
        400 to JSONObject().put("ok", false).put("error", "bad body").toString()
    }

    // ==================== memory v2 路由 ====================

    fun memList(ctx: Context, query: String): Pair<Int, String> = runCatching {
        val pkg = param(query, "pkg")
        val arr = JSONArray()
        for (e in MemoryStore.list(ctx, pkg)) {
            arr.put(JSONObject().apply {
                put("id", e.id); put("pkg", e.pkg); put("intent", e.intent); put("title", e.title)
                put("state", e.state); put("pending", e.pending); put("hits", e.hits)
                put("misses", e.misses); put("steps", e.steps.size)
                put("lastUsed", e.lastUsed); put("rev", e.rev)
                put("fpSize", e.fingerprint().size)
            })
        }
        200 to JSONObject().put("ok", true).put("count", arr.length()).put("entries", arr).toString()
    }.getOrElse { e -> Log.w(TAG, "memList failed: ${e.message}"); 500 to err("memory list failed") }

    fun memGet(ctx: Context, query: String): Pair<Int, String> = runCatching {
        val id = param(query, "id").orEmpty()
        if (id.isBlank()) return 400 to err("missing id")
        val e = MemoryStore.get(ctx, id) ?: return 404 to err("no such memory")
        val steps = JSONArray()
        for (s in e.steps) {
            steps.put(JSONObject().apply {
                put("i", s.sourceIndex); put("action", s.action)
                put("targetText", s.targetText); put("targetRole", s.targetRole)
                put("targetViewId", s.targetViewId)
                put("bounds", JSONArray(s.targetBounds))
                put("expectAfter", JSONArray(s.expectAfter))
                put("preFp", JSONArray(s.preFp))
            })
        }
        200 to JSONObject().apply {
            put("ok", true); put("id", e.id); put("pkg", e.pkg); put("intent", e.intent)
            put("title", e.title); put("state", e.state); put("pending", e.pending)
            put("hits", e.hits); put("misses", e.misses); put("rev", e.rev)
            put("originTraceId", e.originTraceId)
            put("sceneFp", JSONArray(e.fingerprint()))
            put("steps", steps)
        }.toString()
    }.getOrElse { e -> Log.w(TAG, "memGet failed: ${e.message}"); 500 to err("memory get failed") }

    fun memDelete(ctx: Context, query: String): Pair<Int, String> = runCatching {
        // 两种形态都认：?id=xxx（query）与 {"id":"xxx"}（body）。
        // 桥的 Route 把 q.ifBlank { b } 一起传进来，模型/脚本习惯发 JSON body，
        // 只认 query 会让 delete 静默失败成 "missing id"（v1.7.9 实测踩到）。
        val id = param(query, "id").orEmpty().ifBlank {
            runCatching { JSONObject(query).optString("id") }.getOrDefault("")
        }
        if (id.isBlank()) return 400 to err("missing id")
        val ok = MemoryStore.delete(ctx, id)
        200 to JSONObject().put("ok", ok).put("deleted", ok).toString()
    }.getOrElse { e -> Log.w(TAG, "memDelete failed: ${e.message}"); 500 to err("memory delete failed") }

    fun memState(ctx: Context, body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        val id = o.optString("id")
        val state = o.optString("state")
        if (id.isBlank() || state.isBlank()) return 400 to err("missing id or state")
        val ok = MemoryStore.setState(ctx, id, state)
        200 to JSONObject().put("ok", ok).put("state", state).toString()
    }.getOrElse { e -> Log.w(TAG, "memState failed: ${e.message}"); 400 to err("bad body") }

    /**
     * 检索：POST /memory/v2/lookup {"intent":"...","fp":["..",".."],"limit":3}
     *
     * 向量来源三级：bge（本地 llama-server）→ 字符哈希 → 关键词。
     * 为什么把 query 向量显式算出来再传：bge 是 512 维、哈希是 256 维，
     * 尺寸不等时 cosine 恒 0，不传 query 向量等于让 bge 永远白算。
     */
    fun memLookup(ctx: Context, body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        val intent = o.optString("intent")
        if (intent.isBlank()) return 400 to err("missing intent")
        val fp = HashSet<String>()
        o.optJSONArray("fp")?.let { for (i in 0 until it.length()) fp.add(it.optString(i)) }
        val limit = o.optInt("limit", 3)
        val entries = MemoryStore.list(ctx)
        val qv = MemoryIndex.embed(intent)
        val ranked = MemoryIndex.rank(entries, intent, fp, fp, limit, qv)
        val arr = JSONArray()
        for (s in ranked) {
            arr.put(JSONObject().apply {
                put("id", s.entry.id); put("intent", s.entry.intent); put("pkg", s.entry.pkg)
                put("state", s.entry.state)
                put("cos", NumFmt.round2(s.cos)); put("fp", NumFmt.round2(s.fp))
                put("anchor", s.anchor); put("score", NumFmt.round2(s.score))
                put("tier", s.tier); put("steps", s.entry.steps.size)
                put("engine", MemoryIndex.engineOf(s.entry, intent))
            })
        }
        200 to JSONObject().apply {
            put("ok", true); put("count", arr.length())
            put("engine", if (qv != null) "bge" else "hash")
            put("ranked", arr)
        }.toString()
    }.getOrElse { e -> Log.w(TAG, "memLookup failed: ${e.message}"); 500 to err("memory lookup failed") }

    /**
     * 记忆统计：GET /memory/v2/stats
     *
     * 除了总量，还给出**按应用**的命中率、省下的云端往返次数、待标注与 suspect 清单 ——
     * 这三项是判断「这套东西到底有没有用」的唯一依据，没有它们就只能靠感觉。
     */
    fun memStats(ctx: Context): Pair<Int, String> = runCatching {
        val all = MemoryStore.list(ctx)
        val byPkg = JSONObject()
        for ((pkg, list) in all.groupBy { it.pkg }) {
            val hits = list.sumOf { it.hits }
            val misses = list.sumOf { it.misses }
            val total = hits + misses
            byPkg.put(pkg, JSONObject().apply {
                put("count", list.size)
                put("hits", hits); put("misses", misses)
                put("hitRate", NumFmt.round2(if (total == 0) 0.0 else hits.toDouble() / total))
                put("steps", list.sumOf { it.steps.size })
            })
        }
        val suspect = JSONArray()
        all.filter { it.state == MemoryContract.STATE_SUSPECT }.forEach {
            suspect.put(JSONObject().put("id", it.id).put("intent", it.intent)
                .put("misses", it.misses).put("pkg", it.pkg))
        }
        val unlabeled = all.count { it.pending && it.title == it.intent && it.intent.contains(": ") }
        // 「省下的往返」按命中次数 × 1 次云端规划估（真实值要靠对照实验，这里只做量级指示）
        val saved = all.sumOf { it.hits }
        val doc = JSONObject().apply {
            put("ok", true)
            put("summary", MemoryStore.stats(ctx))
            put("byPkg", byPkg)
            put("suspect", suspect)
            put("unlabeled", unlabeled)
            put("pending", all.count { it.pending })
            put("savedPlanningCalls", saved)
            put("traces", TaskRecorder.list(ctx, 500).size)
            put("recordEnabled", recordEnabled)
            put("autoLearn", autoLearn)
            // ★ 语义层就绪状态必须自己报出来（v1.7.x）。
            //
            // 用户实测踩到的坑：`/local/status` 显示 modelReady=false，而
            // `/memory/v2/stats` 只字不提 —— 于是他看到 cos 分数却不知道那是
            // 「bge 语义向量」还是「字符哈希兜底」。两者含义完全不同：哈希相似度
            // 对同义改写（"打开侧栏" vs "展开右边"）几乎无感，看到高分容易误判
            // 「语义匹配在工作」。
            //
            // 这里把三件事一并说清：模型备好没、这条链路当前实际走哪一级、
            // 以及降级时的后果。AI 自排查第一步就该看这段，而不是去猜分数怎么来的。
            val modelReady = runCatching { LocalModel.isModelReady(ctx) }.getOrDefault(false)
            val serverUp = runCatching { LocalModel.isServerRunning() }.getOrDefault(false)
            val semanticReady = modelReady && serverUp
            put("semantic", JSONObject().apply {
                put("ready", semanticReady)
                put("modelReady", modelReady)
                put("serverRunning", serverUp)
                put("engine", if (semanticReady) "bge" else "hash-fallback")
                if (!semanticReady) {
                    put(
                        "degraded",
                        "semantic layer not ready; scoring is character-hash only, so " +
                            "paraphrases (\"open sidebar\" vs \"expand the right panel\") will rarely match. " +
                            "POST /local/ensure starts the model download + llama-server.",
                    )
                }
            })
        }
        200 to doc.toString()
    }.getOrElse { e -> Log.w(TAG, "memStats failed: ${e.message}"); 500 to err("memory stats failed") }

    fun memMigrate(ctx: Context): Pair<Int, String> = runCatching {
        val n = MemoryStore.migrateV1(ctx)
        200 to JSONObject().put("ok", true).put("migrated", n).toString()
    }.getOrElse { e -> Log.w(TAG, "memMigrate failed: ${e.message}"); 500 to err("migrate failed") }

    /**
     * 写入/更新一条记忆：POST /memory/v2/upsert
     * {"id?","pkg","intent","title?","keywords?":[],"sceneFp?":[],"state?","pending?","steps?":[{action,targetText,expectAfter,preFp}]}
     *
     * 两个用途：① 模型在任务完成后主动存一条（设计文档要求的「AI 完成后可直接存储」）；
     * ② 门禁造数据（G3 要 60 条淘汰、G4 要 10 条不同意图，没有这个端点就只能 push 文件）。
     */
    fun memUpsert(ctx: Context, body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        val pkg = o.optString("pkg")
        val intent = o.optString("intent")
        if (pkg.isBlank() || intent.isBlank()) return 400 to err("missing pkg or intent")
        val now = System.currentTimeMillis()
        val steps = ArrayList<MemoryStep>()
        o.optJSONArray("steps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                steps.add(MemoryStep(
                    sourceIndex = s.optInt("i", i),
                    action = s.optString("action"),
                    targetText = s.optString("targetText"),
                    targetRole = s.optString("targetRole"),
                    targetViewId = s.optString("targetViewId"),
                    targetBounds = floats(s.optJSONArray("bounds")),
                    // args 必须能传：launch 的 package、input 的 text、key 的 code 都在里面。
                    // 以前这里写死 emptyMap()，导致手工建的 launch 步骤没有目标包，
                    // 「首步 launch 换包」这条路径在 upsert 入口上根本表达不出来。
                    args = strMap(s.optJSONObject("args")),
                    expectAfter = strings(s.optJSONArray("expectAfter")),
                    preFp = strings(s.optJSONArray("preFp")),
                ))
            }
        }
        val id = o.optString("id").ifBlank { "m_" + now + "_" + (1000..9999).random() }
        val existing = MemoryStore.get(ctx, id)
        val entry = MemoryEntry(
            id = id,
            pkg = pkg,
            appVersion = o.optString("appVersion"),
            intent = intent,
            keywords = strings(o.optJSONArray("keywords")),
            title = o.optString("title", intent),
            steps = steps,
            state = o.optString("state", MemoryContract.STATE_ACTIVE),
            hits = existing?.hits ?: 0,
            misses = existing?.misses ?: 0,
            createdAt = existing?.createdAt ?: now,
            lastUsed = existing?.lastUsed ?: 0,
            originTraceId = o.optString("originTraceId"),
            rev = (existing?.rev ?: 0) + 1,
            pending = o.optBoolean("pending", false),
            pendingHits = existing?.pendingHits ?: 0,
            // 入库就算向量：没有向量的条目在检索时只能退回字符哈希，
            // 「向量搜索」就名存实亡（实测：模型起来了但分数与纯哈希一模一样，就是因为这里存的是空）。
            vector = MemoryIndex.embed(intent) ?: emptyList(),
            sceneFp = strings(o.optJSONArray("sceneFp")),
        )
        val ok = MemoryStore.upsert(ctx, entry)
        200 to JSONObject().put("ok", ok).put("id", id).put("pkg", pkg)
            .put("vectorDim", entry.vector.size).toString()
    }.getOrElse { e -> Log.w(TAG, "memUpsert failed: ${e.message}"); 400 to err("bad body") }

    /** JSON 对象 → Map（步骤参数：launch 的 package / input 的 text / key 的 code …）。 */
    private fun strMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) out.add(a.optString(i))
        return out
    }

    private fun floats(a: JSONArray?): List<Float> {
        if (a == null) return emptyList()
        val out = ArrayList<Float>(a.length())
        for (i in 0 until a.length()) out.add(a.optDouble(i).toFloat())
        return out
    }

    /**
     * 重放：POST /memory/run
     * {"id":"m_..."} 或 {"intent":"...","fp":["..",".."],"auto":true}
     *
     * 不给 id 时走检索：只有 **tier=high** 才自动执行；mid 一律拒绝（要模型先确认），
     * low 不召回。这是设计文档 §3C 的分档落地 —— 宁可让模型多问一句，也不能自己猜着点。
     */
    fun memRun(ctx: Context, body: String): Pair<Int, String> = runCatching {
        val svc = app.dsh.mobile.DshAccessibilityService.instance
            ?: return 503 to err("accessibility service not enabled")
        val o = JSONObject(body.ifBlank { "{}" })
        val id = o.optString("id")
        val intent = o.optString("intent")
        val fp = HashSet<String>()
        o.optJSONArray("fp")?.let { for (i in 0 until it.length()) fp.add(it.optString(i)) }
        val auto = o.optBoolean("auto", true)

        val entry: MemoryEntry?
        var pickedBy = "id"
        var scoreJson: JSONObject? = null
        if (id.isNotBlank()) {
            entry = MemoryStore.get(ctx, id)
        } else {
            pickedBy = "rank"
            val qv = MemoryIndex.embed(intent)
            val top = MemoryIndex.rank(MemoryStore.list(ctx), intent, fp, fp, 1, qv).firstOrNull()
            scoreJson = top?.let {
                JSONObject().put("id", it.entry.id).put("score", NumFmt.round2(it.score))
                    .put("tier", it.tier).put("cos", NumFmt.round2(it.cos)).put("fp", NumFmt.round2(it.fp))
            }
            if (top != null && top.tier != "high" && auto) {
                return 200 to JSONObject().put("ok", false)
                    .put("fallback", true)
                    .put("reason", "tier=" + top.tier + " (needs confirmation)")
                    .put("ranked", scoreJson).toString()
            }
            entry = top?.entry
        }
        if (entry == null) return 404 to err("no such memory")

        // 与 /loop/run 共用同一把屏上动作锁：两个长任务同时点会互相破坏对方以为的界面
        // （每一步都返回 ok，只有整体结果不对，事后极难归因）。拿不到就立刻 409。
        val r = OnScreenTask.runOrNull("memory/run") { MemoryReplay.run(ctx, svc, entry) }
            ?: return 409 to err("another on-screen task is running; wait for it to finish")
        // 成败都要记账：ok → hits++；回落 → misses++（misses>=2 自动 suspect，不再自动命中）
        // 记账口径：成功 → hits++；真失效（锚点不符/换包/保护页/崩溃）→ misses++；
        // budget（没跑完）与 interrupted（调用方自己取消）不算失败 —— 记忆没坏，别让它被降级。
        when {
            r.ok -> MemoryStore.markHit(ctx, entry.id)
            r.fallback && r.reason != MemoryReplay.Reason.BUDGET &&
                r.reason != MemoryReplay.Reason.INTERRUPTED -> MemoryStore.markMiss(ctx, entry.id)
            else -> Unit
        }

        val steps = JSONArray()
        for (s in r.steps) {
            steps.put(JSONObject().apply {
                put("i", s.sourceIndex); put("action", s.action); put("target", s.targetText)
                put("status", s.status); put("detail", s.detail); put("ms", s.ms)
            })
        }
        200 to JSONObject().apply {
            put("ok", r.ok); put("memoryId", r.memoryId); put("pickedBy", pickedBy)
            put("fallback", r.fallback); put("failedAt", r.failedAt); put("reason", r.reason)
            put("steps", steps)
            scoreJson?.let { put("ranked", it) }
        }.toString()
    }.getOrElse { e ->
        Log.w(TAG, "memRun failed: ${e.message}")
        500 to err("memory run failed")
    }

    /**
     * 从一条轨迹学习成记忆：POST /memory/v2/learn {"traceId":"...","intent":"...","title":"...","autoIntent":true}
     *
     * 这是 M7「任务结束自动入库」的手动入口，也是 G5 端到端链路缺的那一环：
     * 轨迹 → 剪枝 → 记忆（pending）→ 检索 → 重放。
     *
     * 为什么先存成 pending：单次成功可能有偶然性（v1 就踩过「只成功一次就固化」的坑），
     * 同一场景第二次成功才转正。force=true 可跳过（调试用）。
     */
    fun memLearn(ctx: Context, body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        val traceId = o.optString("traceId")
        if (traceId.isBlank()) return 400 to err("missing traceId")
        val doc = TaskRecorder.get(ctx, traceId) ?: return 404 to err("no such trace")
        val pruned = TracePruner.prune(doc)
        if (pruned.rejected) {
            return 409 to JSONObject().put("ok", false)
                .put("rejected", true).put("reason", pruned.rejectReason).toString()
        }
        if (pruned.steps.isEmpty()) return 409 to err("nothing to learn after pruning")
        // 不可重放的轨迹不存（见 TracePruner.unreplayable）：存下来只会变成「命中即失败」的垃圾记忆。
        val bad = TracePruner.unreplayable(pruned.steps)
        if (bad.isNotEmpty()) {
            return 409 to JSONObject().put("ok", false).put("rejected", true)
                .put("reason", bad).toString()
        }

        val now = System.currentTimeMillis()
        val intent = o.optString("intent").ifBlank { doc.goal }
        val title = o.optString("title").ifBlank { intent }
        val sceneFp = doc.steps.firstOrNull()?.beforeFp?.toList() ?: emptyList()
        val steps = ArrayList<MemoryStep>(pruned.steps.size)
        for ((i, p) in pruned.steps.withIndex()) {
            steps.add(MemoryStep(
                sourceIndex = p.sourceIndex,
                action = p.action,
                targetText = p.target?.text.orEmpty(),
                targetRole = p.target?.role.orEmpty(),
                targetViewId = p.target?.viewId.orEmpty(),
                targetBounds = p.target?.bounds ?: emptyList(),
                args = p.args,
                // launch 步不设文本后置条件（见 TraceExpect）
                expectAfter = TraceExpect.after(p.action, p.expectAfter.toList()),
                // 逐步指纹（v1.7.x）：v2 轨迹本来就逐步采了 beforeFp，没理由丢。
                preFp = p.preFp.toList().ifEmpty { if (i == 0) sceneFp else emptyList() },
            ))
        }
        val entry = MemoryEntry(
            id = "m_" + now + "_" + (1000..9999).random(),
            pkg = homePkgOf(doc, pruned),
            intent = intent,
            title = title,
            steps = steps,
            state = MemoryContract.STATE_ACTIVE,
            createdAt = now,
            originTraceId = traceId,
            pending = !o.optBoolean("force", false),
            sceneFp = sceneFp,
            taskKey = signatureOf(doc, pruned),
            vector = MemoryIndex.embed(intent) ?: emptyList(),
        )
        val ok = MemoryStore.upsert(ctx, entry)
        val drops = JSONArray()
        for (d in pruned.drops) {
            drops.put(JSONObject().put("rule", d.rule).put("i", d.sourceIndex).put("why", d.why))
        }
        200 to JSONObject().apply {
            put("ok", ok); put("id", entry.id); put("pkg", entry.pkg)
            put("intent", intent); put("pending", entry.pending)
            put("steps", steps.size); put("pruned", pruned.drops.size)
            put("drops", drops)
        }.toString()
    }.getOrElse { e ->
        Log.w(TAG, "memLearn failed: ${e.message}")
        500 to err("memory learn failed")
    }

    // ==================== 修正与扩展（M6） ====================

    /** POST /memory/v2/patch {"id","step","targetText"?,"action"?} */
    fun memPatch(ctx: Context, body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        val id = o.optString("id")
        if (id.isBlank()) return 400 to err("missing id")
        val e = MemoryStore.patch(
            ctx, id,
            o.optInt("step", -1),
            if (o.has("targetText")) o.optString("targetText") else null,
            if (o.has("action")) o.optString("action") else null,
        ) ?: return 404 to err("patch failed (bad id or step)")
        200 to entryJson(e).toString()
    }.getOrElse { e -> Log.w(TAG, "memPatch failed: ${e.message}"); 400 to err("bad body") }

    /** POST /memory/v2/extend {"id","steps":[{action,targetText,expectAfter}]} */
    fun memExtend(ctx: Context, body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        val id = o.optString("id")
        if (id.isBlank()) return 400 to err("missing id")
        val steps = ArrayList<MemoryStep>()
        o.optJSONArray("steps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                steps.add(MemoryStep(
                    sourceIndex = s.optInt("i", i), action = s.optString("action"),
                    targetText = s.optString("targetText"), targetRole = s.optString("targetRole"),
                    targetViewId = s.optString("targetViewId"),
                    targetBounds = floats(s.optJSONArray("bounds")),
                    expectAfter = strings(s.optJSONArray("expectAfter")),
                    preFp = strings(s.optJSONArray("preFp")),
                ))
            }
        }
        if (steps.isEmpty()) return 400 to err("no steps to extend")
        val e = MemoryStore.extend(ctx, id, steps, o.optString("note"))
            ?: return 404 to err("extend failed (bad id)")
        200 to entryJson(e).toString()
    }.getOrElse { e -> Log.w(TAG, "memExtend failed: ${e.message}"); 400 to err("bad body") }

    /** POST /memory/v2/fork {"id","fromStep","intent","title"?} */
    fun memFork(ctx: Context, body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        val id = o.optString("id")
        val intent = o.optString("intent")
        if (id.isBlank() || intent.isBlank()) return 400 to err("missing id or intent")
        val child = MemoryStore.fork(ctx, id, o.optInt("fromStep", -1), intent, o.optString("title"))
            ?: return 404 to err("fork failed (bad id or fromStep)")
        200 to entryJson(child).toString()
    }.getOrElse { e -> Log.w(TAG, "memFork failed: ${e.message}"); 400 to err("bad body") }

    /** POST /memory/v2/rollback {"id","rev"} */
    fun memRollback(ctx: Context, body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        val id = o.optString("id")
        if (id.isBlank()) return 400 to err("missing id")
        val e = MemoryStore.rollback(ctx, id, o.optInt("rev", -1))
            ?: return 404 to err("rollback failed (unknown id or rev)")
        200 to entryJson(e).toString()
    }.getOrElse { e -> Log.w(TAG, "memRollback failed: ${e.message}"); 400 to err("bad body") }

    /** 统一的记忆回执（改完/建完都给调用方看一份完整现状，免得它再 GET 一次）。 */
    private fun entryJson(e: MemoryEntry): JSONObject = JSONObject().apply {
        put("ok", true); put("id", e.id); put("pkg", e.pkg); put("intent", e.intent)
        put("title", e.title); put("state", e.state); put("pending", e.pending)
        put("hits", e.hits); put("misses", e.misses); put("rev", e.rev)
        put("steps", e.steps.size); put("history", e.history.size)
    }

    /**
     * 录制/自动学习开关：POST /memory/v2/config {"autoLearn":bool,"recordEnabled":bool}
     *
     * 两个开关都必须是**可写的**：只有只读回读时，设置页那个 Switch 只能弹回去，
     * 用户会以为坏了。UI 侧的 Switch 直接打这个端点。
     */
    fun memConfig(body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        if (o.has("autoLearn")) autoLearn = o.optBoolean("autoLearn")
        if (o.has("recordEnabled")) recordEnabled = o.optBoolean("recordEnabled")
        200 to JSONObject().put("ok", true)
            .put("autoLearn", autoLearn).put("recordEnabled", recordEnabled).toString()
    }.getOrElse { e -> Log.w(TAG, "memConfig failed: ${e.message}"); 400 to err("bad body") }

    private fun err(msg: String): String = JSONObject().put("ok", false).put("error", msg).toString()

    // ==================== 小工具 ====================

    /** 一次 dump 同时拿「指纹」和「候选节点」—— 两次读屏会白白多花 30-60ms。 */
    private fun snapshotOf(
        svc: app.dsh.mobile.DshAccessibilityService,
        pkg: String,
    ): Pair<Set<String>, List<FastPicker.Candidate>> = runCatching {
        val dump = svc.dumpScreenCompact("all")
        SceneMemory.fingerprint(dump, pkg) to FastPicker.parseCompact(dump)
    }.getOrDefault(emptySet<String>() to emptyList())

    /**
     * 把一次动作翻译成「元素锚点」—— 这是「自动录制 → 重放」整条链的关键一环。
     *
     * 为什么必须做：重放时的安全规则是「锚点对不上就绝不点」。录制时如果只留下坐标，
     * 重放就只能拒绝（坐标盲点正是要消灭的东西），整条记忆等于废掉。
     *
     * 规则：
     * - 请求里自带 text（/scroll-find、/tap?text=）→ 直接用；
     * - 只有坐标 → 在**动作前那一屏**的候选里找离该点最近的（≤160px），取它的文本；
     * - /input → 永远不记锚点（输入内容是隐私，绝不能进轨迹）；
     * - 找不到 → null（宁可没有锚点，也不要编一个假的）。
     */
    private fun anchorFor(
        path: String,
        body: String,
        cands: List<FastPicker.Candidate>,
    ): TraceAnchor? = runCatching {
        val o = JSONObject(body.ifBlank { "{}" })
        when (endpointOf(path)) {
            TraceEndpoint.SCROLL -> o.optString("text").ifBlank { null }?.let { TraceAnchor(text = it) }
            TraceEndpoint.INPUT -> null
            // /dev/node 的目标文本就是它自己的 text 字段 —— 不认这一支，节点动作
            // 重放时拿不到目标，只能报 "node action needs target text"。
            TraceEndpoint.NODE -> {
                val t = o.optString("text").ifBlank { o.optString("label") }
                if (t.isNotBlank()) TraceAnchor(text = t) else null
            }
            TraceEndpoint.TAP, TraceEndpoint.TAP_ID, TraceEndpoint.GESTURE -> {
                val t = o.optString("text").ifBlank { o.optString("label") }
                if (t.isNotBlank()) TraceAnchor(text = t)
                else {
                    val x = o.optDouble("x", -1.0)
                    val y = o.optDouble("y", -1.0)
                    if (x < 0 || y < 0) null else nearestCandidate(cands, x, y)
                }
            }
            else -> null
        }
    }.getOrNull()

    /** 离点击点最近的、有文本的候选（半径 160px）。可点/可编辑的优先。 */
    private fun nearestCandidate(
        cands: List<FastPicker.Candidate>,
        x: Double,
        y: Double,
    ): TraceAnchor? {
        var best: FastPicker.Candidate? = null
        var bestD = Double.MAX_VALUE
        for (c in cands) {
            if (c.text.isBlank() || c.text == "-") continue
            val dx = c.x - x
            val dy = c.y - y
            val d = Math.sqrt(dx * dx + dy * dy)
            if (d > 160.0) continue
            // 排序三要素（实测教训）：同一行里往往有「标题」和「副标题」两个文本节点，
            // 副标题更长、常常离点击点更近，但它不是这一行的身份。所以：
            //   距离为主，可点/可编辑减分，**文本越长越减分**（标题总是短的）。
            val penalty = if (c.clickable || c.editable) 0.0 else 60.0
            val lengthPenalty = c.text.length * 1.5
            val score = d + penalty + lengthPenalty
            if (score < bestD) {
                bestD = score
                best = c
            }
        }
        return best?.let { TraceAnchor(text = it.text, role = it.role) }
    }

    /** HTTP 路径 → 契约里的 endpoint 名。 */
    private fun endpointOf(path: String): String = when (path) {
        "/tap", "/tap-ref", "/vision/tap" -> TraceEndpoint.TAP
        "/tap-id" -> TraceEndpoint.TAP_ID
        "/gesture", "/vision/drag" -> TraceEndpoint.GESTURE
        "/input", "/type" -> TraceEndpoint.INPUT
        "/key" -> TraceEndpoint.KEY
        "/scroll-find", "/scroll-center", "/scroll-edge" -> TraceEndpoint.SCROLL
        "/launch", "/launch-intent", "/open" -> TraceEndpoint.LAUNCH
        // /dev/node 是最常用的语义动作入口，必须归到 NODE 而不是让它落到
        // 兜底分支变成 `dev_node`（那个名字重放侧不认识，整条轨迹会被丢弃）。
        "/dev/node" -> TraceEndpoint.NODE
        "/loop/run" -> TraceEndpoint.LOOP_STEP
        else -> path.removePrefix("/").replace('/', '_')
    }

    /**
     * 只留**不含内容**的参数（目标包、方向、距离、按键名、输入长度），绝不记 input 的文本。
     *
     * 字段翻译在 [TraceArgs.of]（纯函数，JVM 有断言）：桥的 `/key` 用 "action"、
     * 重放读 "name"；`/launch` 允许 "pkg" 别名 —— 以前这里直接 `emptyMap()`，
     * 轨迹里所有步骤都没有参数，重放时 key 按 back、launch 报缺包，等于这三类动作永远不可重放。
     */
    private fun argsOf(path: String, body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return emptyMap()
        val flat = LinkedHashMap<String, String>()
        for (k in o.keys()) flat[k] = o.optString(k)
        return TraceArgs.of(endpointOf(path), flat)
    }

    private fun param(query: String, name: String): String? =
        query.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}

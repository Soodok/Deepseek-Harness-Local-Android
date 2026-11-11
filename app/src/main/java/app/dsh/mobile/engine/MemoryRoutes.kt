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
            }
            lastTs = System.currentTimeMillis()
        }.onFailure { Log.w(TAG, "onActionBegin failed: ${it.message}") }
    }

    /** 动作结束（taskDone 里调用）：取一次后指纹，落一行事件。 */
    fun onActionDone(ctx: Context, path: String, decision: String, body: String = "") {
        if (!recordEnabled || !TaskRecorder.isRecording()) return
        runCatching {
            val svc = app.dsh.mobile.DshAccessibilityService.instance ?: return
            val pkg = svc.foregroundPackage().orEmpty()
            val now = System.currentTimeMillis()
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
                args = argsOf(path),
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
            if (TaskRecorder.isRecording()) {
                TaskRecorder.end(lastStatus == TraceStatus.OK, "boundary")
            }
            TaskRecorder.pruneOld(ctx)
            lastFp = emptySet()
            lastTs = 0
        }.onFailure { Log.w(TAG, "onBoundaryEnd failed: ${it.message}") }
    }

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
        val id = param(query, "id").orEmpty()
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

    fun memStats(ctx: Context): Pair<Int, String> = runCatching {
        200 to JSONObject().put("ok", true).put("summary", MemoryStore.stats(ctx)).toString()
    }.getOrElse { e -> 500 to err("memory stats failed") }

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
                    args = emptyMap(),
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
            vector = emptyList(),
            sceneFp = strings(o.optJSONArray("sceneFp")),
        )
        val ok = MemoryStore.upsert(ctx, entry)
        200 to JSONObject().put("ok", ok).put("id", id).put("pkg", pkg).toString()
    }.getOrElse { e -> Log.w(TAG, "memUpsert failed: ${e.message}"); 400 to err("bad body") }

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

        val r = MemoryReplay.run(ctx, svc, entry)
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
                expectAfter = p.expectAfter.toList(),
                // 只有首步有逐步指纹（v1/契约的已知信息损失），后续步留空
                preFp = if (i == 0) sceneFp else emptyList(),
            ))
        }
        val entry = MemoryEntry(
            id = "m_" + now + "_" + (1000..9999).random(),
            pkg = doc.pkg,
            intent = intent,
            title = title,
            steps = steps,
            state = MemoryContract.STATE_ACTIVE,
            createdAt = now,
            originTraceId = traceId,
            pending = !o.optBoolean("force", false),
            sceneFp = sceneFp,
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
        "/loop/run" -> TraceEndpoint.LOOP_STEP
        else -> path.removePrefix("/").replace('/', '_')
    }

    /** 只留**不含内容**的参数（坐标、方向、长度），绝不记 input 的文本。 */
    private fun argsOf(path: String): Map<String, String> = emptyMap()

    private fun param(query: String, name: String): String? =
        query.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}

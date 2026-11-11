package app.dsh.mobile.engine

import android.content.Context
import android.content.Intent
import app.dsh.mobile.HumanPace
import app.dsh.mobile.NumFmt
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 本地闭环执行器（v1.2.123）—— 「决策程序」的端侧执行引擎。
 *
 * ## 这是什么（主人设计的架构）
 * 云端大模型把任务**编译**成一份结构化决策程序（JSON DSL），本地拿着它
 * **连续执行**，不再每步往返云端：
 *
 * ```
 *   PICK（FastPicker 三层：精确 1-2ms → 语义 ~93ms → 放弃）→ ACT（动作原语）
 *     → VERIFY（文本 / 帧差 ~55ms）→ 成功走下一步
 *                                   → 失败 RETRY（判别式降级：换锚点→换原生匹配）
 *                                   → 耗尽 ESCALATE（带证据回云端重新编译）
 * ```
 *
 * ## 为什么这样能快
 * 云端路径每步 3-4 秒（截图→上传→推理→下发）；本路径每步 0.1-0.5 秒，
 * 且闭环内**零网络往返**。努比亚豆包那种"每步零点几秒"的流畅感，本质就是
 * 把理解前置成可判别的规则，本地只做判别 + 执行 + 验证。
 *
 * ## 判别式重试（不是盲目重试）
 * Jev 的心法：判"是什么"而不是"生成怎么做"。失败时按**可判别的证据**分流：
 * - attempt 0：正常 FastPicker（text[0] 锚点）
 * - attempt 1：换下一个锚点文本 / 放开 clickableOnly 过滤
 * - attempt 2：绕开 FastPicker，走无障碍原生 tapText（文本精确命中）
 * - 仍失败：escalate（记录 via/score/候选池/帧差，供云端重新规划与日后蒸馏）
 *
 * ## 合规
 * 通用 UI 自动化的正常能力（设置、浏览器、办公），不含游戏脚本/反作弊绕过。
 */
object LocalLoopExecutor {

    private const val TAG = "LocalLoop"

    /** 无障碍节点树 IPC 专用线程（与 AgentBridge.screenExec 同款原因：
     *  节点树 IPC 偶发挂起，必须带超时隔离，不能阻塞循环线程） */
    private val ipc = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LocalLoop-ipc").apply { isDaemon = true }
    }

    private fun <T> withTimeout(name: String, ms: Long, block: () -> T): T? {
        if (ms <= 0L) return null
        val future = ipc.submit(Callable { block() })
        return try {
            future.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            future.cancel(true)
            // 上层请求已经被取消时不能吞掉中断，继续派发后续动作。
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            // get 超时不等于任务取消：否则旧 tap 仍在队列里，稍后可能点到下一页。
            future.cancel(true)
            Log.w(TAG, "ipc timeout/fail: $name (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }

    /** 耗时/预算用单调时钟，系统校时不能把等待期限延长或倒退。 */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    // ==================== 入口 ====================

    /**
     * 执行决策程序。
     * @param planJson 决策程序 JSON，格式：
     * ```
     * {
     *   "goal": "开启飞行模式",
     *   "resetFrame": true,          // 开始时重置帧差基线（默认 true）
     *   "steps": [
     *     {
     *       "id": "s1",
     *       "target": {"text": ["设置","Settings"]},   // 多锚点，按序退化
     *       "action": {"type":"tap"},                  // tap|tap_text|swipe|input|key|launch|sleep|scroll_find|collect|noop
     *       "verify": {"anyText":["网络和互联网"], "frameChanged":true, "foregroundPkg":"com.android.settings", "timeoutMs":4000},
     *       "onFail": {"retry": 1, "then": "escalate"}  // escalate|continue|abort
     *     }
     *   ]
     * }
     * ```
     * @return 执行报告 JSON（含每步 via/score/耗时/尝试记录）
     */
    /**
     * @param deadlineAt 单调时钟截止点（`nowMs()` 口径）；到点后**在步与步之间**收手。
     *   默认 [Long.MAX_VALUE] = 不限。为什么要它：`/loop/run` 的超时原来只切断 HTTP
     *   连接，循环线程照样在后台继续点 —— 调用方看到 504，屏幕却还在动，而且下一次
     *   调用会排在这个线程后面。现在到点就停在两步之间，返回带 budget 标记的报告。
     */
    fun run(
        ctx: Context,
        svc: app.dsh.mobile.DshAccessibilityService,
        planJson: String,
        deadlineAt: Long = Long.MAX_VALUE,
    ): String {
        val t0 = nowMs()
        val plan = runCatching { JSONObject(planJson) }.getOrNull()
            ?: return """{"ok":false,"error":"invalid plan json"}"""
        val goal = plan.optString("goal")
        val useMemory = plan.optBoolean("useMemory", false) && goal.isNotBlank()

        // ---- 场景记忆查询（v1.2.125）：命中则用记忆的决策，跳过云端 ----
        var memoryInfo: JSONObject? = null
        var memoryEntry: SceneMemory.Entry? = null
        var memoryFp: Set<String>? = null
        var execPlan = plan
        if (useMemory) {
            val dump = withTimeout("memory.dump", 4000) { svc.dumpScreenCompact("all") }
            if (dump != null) {
                val fp = SceneMemory.fingerprint(dump, svc.foregroundPackage() ?: "")
                memoryFp = fp
                val (entry, sim) = SceneMemory.lookup(ctx, goal, fp)
                if (entry != null) {
                    // 适用性检查（v1.2.126，极复杂场景实测发现）：记忆的"成功"是**步骤级**
                    // 验证，且场景指纹粒度有限（同一 App 不同页面可能相似）→ 命中的计划
                    // 可能**不适用于当前页面**（实测：带记忆时"开深色主题"只跑 3 步就失败，
                    // 清记忆后 4/4 通过）。不适用则放弃记忆、走云端规划。
                    if (memoryApplicable(svc, entry.plan, dump)) {
                        memoryEntry = entry
                        execPlan = entry.plan
                        memoryInfo = JSONObject().put("hit", true)
                            .put("similarity", NumFmt.round2(sim))
                            .put("reused", entry.hits)
                        Log.i(TAG, "memory hit: goal=$goal sim=$sim")
                    } else {
                        memoryInfo = JSONObject().put("hit", false)
                            .put("bestSimilarity", NumFmt.round2(sim))
                            .put("rejected", "plan not applicable to current page")
                        Log.i(TAG, "memory hit but rejected (not applicable): goal=$goal sim=$sim")
                    }
                } else {
                    memoryInfo = JSONObject().put("hit", false)
                        .put("bestSimilarity", NumFmt.round2(sim))
                }
            }
        }

        val steps = execPlan.optJSONArray("steps")
            ?: return """{"ok":false,"error":"plan has no steps${if (useMemory) " (and memory miss)" else ""}"}"""
        if (steps.length() == 0) return """{"ok":false,"error":"plan has empty steps"}"""

        if (execPlan.optBoolean("resetFrame", true)) app.dsh.mobile.Framer.reset()
        val planDebug = execPlan.optBoolean("debug", false)
        // 调度策略来自请求而非场景记忆；并行线程可独占消费式事件摘要。
        val useScreenEvents = plan.optBoolean("useScreenEvents", true)
        val waitSliceMs = plan.optLong("waitSliceMs", 0L).let {
            if (it > 0L) it.coerceIn(200L, 20_000L) else 0L
        }

        // v1.2.151 乐观执行（用户设计：本地只需按预测轨迹执行 + 保持纠错能力，
        // 单步就能压进 1 秒）：plan 级 "optimistic":true 时，**中间步骤只做快速
        // 检查**（≤200ms，命中即过；未命中标注 optimisticPass 继续）——因为计划
        // 本就是云端**预测的轨迹**（默认假设前步成功）。**最后一步始终完整验证**
        // （终态必须确认）；明确失败证据出现时仍然停止/升级，纠错能力不丢。
        val optimistic = plan.optBoolean("optimistic", false)
        // v1.6.33：真人节奏（每步 60–220ms 思考停顿 + 5% 概率额外犹豫 0.7–1.8s，v1.6.34 下调）。
        // 计划里传 "humanPace": false 可关闭。
        val humanPace = plan.optBoolean("humanPace", true)
        val stepReports = JSONArray()
        var escalated = false
        var yielded = false
        var anyFailed = false
        var failedAt: String? = null
        val collected = JSONArray()

        for (i in 0 until steps.length()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("loop interrupted")
            val step = steps.getJSONObject(i)
            val id = step.optString("id").ifBlank { "s${i + 1}" }
            if (optimistic && i < steps.length() - 1) step.put("_fastVerify", true)
            // ---- v1.2.125 分支步骤（when 条件）----
            // 云端在**一次推理**里生成多条分支路径（如「出现弹窗就点允许」「没弹窗
            // 就继续正常路径」），本地按当前屏幕状态选择执行——预测发生在前，
            // 执行时无需再往返云端（真机反馈「对话流里只能确认后继续」的纯本地补强）。
            val whenCond = step.optJSONObject("when")
            if (whenCond != null && !whenMatches(svc, whenCond)) {
                stepReports.put(JSONObject().apply {
                    put("id", id)
                    put("status", "skipped")
                    put("reason", "when condition not met")
                })
                continue
            }
            // v1.6.34：步与步之间自查用户是否叫停 —— 桥的入口闸门只挡新请求，
            // 挡不住已经在跑的循环（实测：叫停后 40 步照样跑完）。
            // v1.7.2：预算到点也在两步之间收手（与叫停同一位置）。放在动作之前 ——
            // 半截手势比「少做一步」危险得多。
            if (nowMs() >= deadlineAt) {
                stepReports.put(JSONObject().apply {
                    put("id", id)
                    put("status", "skipped")
                    put("reason", "loop budget exhausted")
                })
                return JSONObject()
                    .put("ok", false)
                    .put("budgetExceeded", true)
                    .put("error", "loop budget exhausted before step " + id)
                    .put("failedAt", id)
                    .put("steps", stepReports)
                    .toString()
            }
            val brake = app.dsh.mobile.PhoneTask.shared().interruptReason()
            if (brake != null) {
                stepReports.put(JSONObject().apply {
                    put("id", id)
                    put("status", "stopped")
                    put("reason", brake)
                })
                return JSONObject()
                    .put("ok", false)
                    .put("stopped", true)
                    .put("error", brake)
                    .put("failedAt", id)
                    .put("steps", stepReports)
                    .toString()
            }
            // v1.6.33：每步之前来一段不均匀的思考停顿。本地闭环的间隔原本整齐得像
            // 节拍器（每步 1.7s 上下），这是比轨迹更便宜的一类特征。
            // 用户明确说过「不用考虑每步变慢，AI 思考和给出动作本身就慢」，故默认开。
            if (humanPace) HumanPace.pause(HumanPace.stepPauseMs())
            val r = try {
                runStep(ctx, svc, id, step, collected, planDebug, useScreenEvents, waitSliceMs)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                // 单步崩溃不应炸掉整条闭环：记证据、按失败处理
                Log.w(TAG, "step $id crashed: ${e.message}")
                StepResult("failed", JSONObject().apply {
                    put("id", id).put("status", "failed")
                    put("error", "executor crash: ${e.message}")
                })
            }
            stepReports.put(r.report)
            if (r.status == "yielded") {
                // 等待尚未完成，后续步骤的前提不成立；即使 onFail=continue 也不能继续。
                yielded = true
                failedAt = id
                break
            }
            if (r.status != "ok") {
                anyFailed = true
                // then 决定：escalate（默认）→ 中止后续；continue → 继续下一步；abort → 中止
                val then = step.optJSONObject("onFail")?.optString("then") ?: "escalate"
                if (r.status == "escalated" || then != "continue") {
                    escalated = r.status == "escalated"
                    failedAt = id
                    break
                }
            }
        }

        val totalMs = nowMs() - t0
        // continue 只是允许后续执行，不是该失败已解决；失败/等待让出的计划不能存为成功记忆。
        val success = !anyFailed && !yielded && failedAt == null

        // ---- 记忆写入/标记（v1.2.125）：成功存决策（云端给的），复用失败计数淘汰 ----
        if (useMemory && memoryFp != null) {
            runCatching {
                if (success) {
                    if (memoryEntry != null) {
                        SceneMemory.markHit(ctx, memoryEntry!!)
                        memoryInfo?.put("result", "hit-confirmed")
                    } else {
                        // 云端给的决策成功了 → 存为记忆（下次同场景可跳过云端）
                        // ⚠️ v1.2.152 审查修复：store 前剥离运行时标记 `_fastVerify`——
                        // 它被写进 plan 的 step 后随记忆持久化，下次**非乐观**请求命中
                        // 记忆时会读到残留标记 → 中间步骤被静默乐观化（审查 P0-1）。
                        val cleanPlan = JSONObject(plan.toString())
                        cleanPlan.optJSONArray("steps")?.let { arr ->
                            for (k in 0 until arr.length()) {
                                arr.optJSONObject(k)?.remove("_fastVerify")
                            }
                        }
                        SceneMemory.store(ctx, goal, memoryFp!!, cleanPlan, pending = true)
                        memoryInfo?.put("result", "stored-pending")
                    }
                } else if (yielded) {
                    memoryInfo?.put("result", "wait-yielded")
                } else if (memoryEntry != null) {
                    val evicted = SceneMemory.markMiss(ctx, memoryEntry!!)
                    val resultTag = if (evicted) "evicted" else "miss-counted"
                    memoryInfo?.put("result", resultTag)
                }
                Unit   // lambda 末尾显式无返回值（if 链不穷尽时 Kotlin 要求）
            }.onFailure { Log.w(TAG, "memory update failed: ${it.message}") }
        }

        return JSONObject().apply {
            put("ok", success)
            put("goal", goal)
            put("escalated", escalated)
            if (yielded) put("yielded", true)
            if (failedAt != null) put("failedAt", failedAt)
            put("totalMs", totalMs)
            if (memoryInfo != null) put("memory", memoryInfo)
            put("steps", stepReports)
            if (collected.length() > 0) put("collected", collected)
        }.toString()
    }

    // ==================== 单步执行 ====================

    private class StepResult(val status: String, val report: JSONObject)

    private fun runStep(
        ctx: Context,
        svc: app.dsh.mobile.DshAccessibilityService,
        id: String,
        step: JSONObject,
        collected: JSONArray,
        planDebug: Boolean,
        useScreenEvents: Boolean,
        waitSliceMs: Long,
    ): StepResult {
        val desc = step.optString("desc")
        val target = step.optJSONObject("target")
        val action = step.optJSONObject("action") ?: JSONObject().put("type", "tap")
        val type = action.optString("type").ifBlank { "tap" }
        val verify = step.optJSONObject("verify")
        val retry = (step.optJSONObject("onFail")?.optInt("retry", 0) ?: 0).coerceIn(0, 2)
        // noop 没有可替换的锚点/动作，重新等一遍同一超时只会把 10s 放大成 20s。
        val effectiveRetry = if (type == "noop" || type == "sleep" || type == "collect") 0 else retry
        // v1.2.133：默认 3000→4000 再到 5000（AI 实测反馈）——慢页面（设置→关于页）
        // 首次验证实际花 3007ms 踩线失败，触发一次无谓重试（整步 4146ms 里 3s 空等）。
        // 5000ms 覆盖这类慢页面，同时 verify 成功仍立即返回（不增加快页面的耗时）。
        val timeout = verify?.optLong("timeoutMs", 5000L)?.coerceIn(200L, 20_000L) ?: 5000L
        val waitBudget = if (type == "noop" && waitSliceMs > 0L) minOf(timeout, waitSliceMs) else timeout
        var verifyRemaining = waitBudget
        val anchors = anchorTexts(target)

        // v1.2.159：把「当前在做什么」推到悬浮窗（否则 daemon/闭环执行时界面一片空白，
        // 用户完全看不出 AI 在干什么）。文案取自计划本身（desc / 目标文本），无硬编码文本。
        runCatching {
            val label = step.optString("desc").ifBlank {
                step.optJSONObject("target")?.optJSONArray("text")?.optString(0).orEmpty()
            }
            app.dsh.mobile.StatusOverlay.setDaemonAction(
                if (label.isBlank()) id else "$id: $label"
            )
        }
        val attempts = JSONArray()
        val t0 = nowMs()
        var lastVia = "none"
        var lastScore = 0.0
        var lastPickText = ""
        var candidatePool: JSONArray? = null
        // v1.2.132：首次选中节点的角色——重试守卫的基准（见 performAction 内校验）
        var firstRole: String? = null

        for (attempt in 0..effectiveRetry) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("step interrupted")
            val a = JSONObject().put("attempt", attempt)

            // ---- ACT（含 PICK）----
            // 文本定位/手势仍保留稳定守卫，避免在过渡动画中丢点击。
            // noop/sleep 本来就在等、launch 不依赖当前页，不能先等旧页面稳定。
            val settleT0 = nowMs()
            if (type !in setOf("noop", "sleep", "launch")) settle(svc)
            a.put("settleMs", nowMs() - settleT0)
            // debug 模式：记录动作前的屏幕文本快照（排障用：直接看到"点击时屏幕上有什么"；
            // 阶段 B 训练池也复用这个结构：目标 + 决策时屏幕候选 + 正确决策）
            if (planDebug) {
                val snap = withTimeout("snap", 3000) { svc.dumpScreenCompact("all") }
                if (snap != null) {
                    val texts = FastPicker.parseCompact(snap)
                        .filter { it.text.isNotBlank() && it.text != "-" }
                        .take(8)
                    a.put("screen", texts.joinToString(" | ") { it.text })
                }
            }
            // 帧差基线：需要在**动作前**采一帧——动作后轮询时才能判"画面比动作前变了没"。
            // 每次 attempt 都重建，避免上次尝试的残留画面成为基线。
            if (verify != null && verify.optBoolean("frameChanged", false)) {
                val primeT0 = nowMs()
                withTimeout("frame.prime", 3000) { app.dsh.mobile.Framer.captureAndDiff(svc) }
                a.put("framePrimeMs", nowMs() - primeT0)
            }
            val actT0 = nowMs()
            val act = performAction(ctx, svc, action, anchors, attempt, firstRole)
            if (act.role.isNotEmpty() && firstRole == null) firstRole = act.role
            val actMs = nowMs() - actT0
            a.put("act", act.ok)
            a.put("actMs", actMs)
            if (act.pickText.isNotBlank()) { lastPickText = act.pickText }
            if (act.via != "none") lastVia = act.via
            if (act.score > 0) lastScore = act.score
            if (act.via != "none") a.put("via", act.via).put("score", act.score)
            if (act.tapX >= 0) a.put("tap", "${act.tapX},${act.tapY}")
            if (act.note.isNotBlank()) a.put("note", act.note)
            if (act.pool != null) candidatePool = act.pool

            if (!act.ok) {
                a.put("error", act.error)
                attempts.put(a)
                continue
            }

            // ---- VERIFY ----
            // timeout 是该步所有 VERIFY 的总预算，不是每次重试重新获得一整份。
            // 留 <=1s 给最后一次降级后的验证；首轮仍拿到绝大部分页面加载预算。
            val reserve = if (attempt < effectiveRetry) minOf(1000L, verifyRemaining / 4) else 0L
            // 乐观执行：中间步骤的验证预算压到 200ms（快速检查）；最后一步不受限
            val fastVerify = step.optBoolean("_fastVerify", false)
            val budget = (verifyRemaining - reserve).coerceAtLeast(0L)
                .let { if (fastVerify) minOf(it, 200L) else it }
            val vT0 = nowMs()
            val v = verifyStep(svc, verify, budget, useScreenEvents)
            val verifyMs = nowMs() - vT0
            verifyRemaining = (verifyRemaining - verifyMs).coerceAtLeast(0L)
            a.put("verify", v.ok).put("verifyMs", verifyMs).put("verifyBudgetMs", budget)
            if (v.slowSemanticMs > 0) a.put("verifySemanticSlowMs", v.slowSemanticMs)
            a.put("verifyPolls", v.polls).put("verifyDumpReads", v.dumpReads).put("verifyFrameReads", v.frameReads)
            if (v.how.isNotBlank()) a.put("verifyHow", v.how)
            // 失败帧（v1.2.132）：AI 反馈「escalate 时没有失败屏幕文本，被迫再发
            // 一次 dump」——现在直接随报告返回（紧凑行，前 20 个有文本节点）。
            if (!v.ok && v.failScreen.isNotEmpty()) a.put("failScreen", v.failScreen)
            attempts.put(a)

            // 乐观通过（v1.2.151）：中间步骤快检查未命中 → 按预测继续（标注
            // optimisticPass，云端由此知道"这步未经确认"）；不重试、不升级——
            // 真正的把关在最后一步的完整验证与后续步骤的失败证据。
            if (!v.ok && fastVerify) {
                a.put("optimisticPass", true)
                if (act.collectedText != null) collected.put(act.collectedText)
                return StepResult("ok", JSONObject().apply {
                    put("id", id)
                    if (desc.isNotBlank()) put("desc", desc)
                    put("status", "ok")
                    put("optimisticPass", true)
                    put("attempts", attempt + 1)
                    put("via", lastVia).put("score", lastScore)
                    if (lastPickText.isNotBlank()) put("picked", lastPickText)
                    put("totalMs", nowMs() - t0)
                    put("detail", attempts)
                })
            }
            if (v.ok) {
                // 成功：若有 collect 产物，收集进报告
                if (act.collectedText != null) collected.put(act.collectedText)
                return StepResult("ok", JSONObject().apply {
                    put("id", id)
                    if (desc.isNotBlank()) put("desc", desc)
                    put("status", "ok")
                    put("attempts", attempt + 1)
                    put("via", lastVia).put("score", lastScore)
                    if (lastPickText.isNotBlank()) put("picked", lastPickText)
                    put("totalMs", nowMs() - t0)
                    put("detail", attempts)
                })
            }
            if (verifyRemaining <= 0L) break
        }

        // 等待时间片耗尽是调度让出，不是完成或失败：云端检查新状态后决定续等/改路径。
        val waitYielded = type == "noop" && waitBudget < timeout
        val then = step.optJSONObject("onFail")?.optString("then") ?: "escalate"
        val status = if (waitYielded) "yielded" else if (then == "escalate") "escalated" else "failed"
        return StepResult(status, JSONObject().apply {
            put("id", id)
            if (desc.isNotBlank()) put("desc", desc)
            put("status", status)
            put("attempts", attempts.length())
            put("via", lastVia).put("score", lastScore)
            if (waitYielded) put("reason", "wait slice exhausted; verify not confirmed")
            // 升级证据：候选池（本地看到过哪些文本 —— 云端据此知道"屏幕上有什么"）
            candidatePool?.let { put("candidates", it) }
            put("foreground", withTimeout("report.fg", 500) { svc.foregroundPackage() } ?: "")
            put("totalMs", nowMs() - t0)
            put("detail", attempts)
        })
    }

    // ==================== 动作层 ====================

    private class ActResult(
        val ok: Boolean,
        val error: String = "",
        val via: String = "none",
        val score: Double = 0.0,
        val pickText: String = "",
        /** 实际点击坐标（v1.2.123 排障用：区分"选对了"与"点对了"） */
        val tapX: Int = -1,
        val tapY: Int = -1,
        /** 原生匹配失败时附带的最接近候选（给 AI/云端做证据） */
        val note: String = "",
        /** /pick 失败时返回的候选池（升级证据） */
        val pool: JSONArray? = null,
        /** collect 动作的产物 */
        val collectedText: JSONObject? = null,
        /** 选中节点的角色（v1.2.132 重试守卫用；空 = 未知/不适用） */
        val role: String = "",
    )

    /**
     * 执行动作。attempt 用于**判别式降级**：
     * 0=FastPicker(text[0]) → 1=换锚点/放开过滤 → 2=原生 tapText
     */
    private fun performAction(
        ctx: Context,
        svc: app.dsh.mobile.DshAccessibilityService,
        action: JSONObject,
        anchors: List<String>,
        attempt: Int,
        /** 首次选中节点的角色（v1.2.132 重试守卫；null = 首次还未产生） */
        firstRole: String? = null,
    ): ActResult {
        val type = action.optString("type").ifBlank { "tap" }
        return when (type) {
            "tap" -> {
                if (anchors.isEmpty()) return ActResult(false, "tap needs target.text")
                val anchor = anchors[minOf(attempt, anchors.size - 1)]
                if (attempt >= 2) {
                    // 降级层 2：绕开 FastPicker，走无障碍原生精确匹配
                    val ok = withTimeout("tapText", 4000) { svc.tapText(anchor) } ?: false
                    if (ok) ActResult(true, via = "native", score = 1.0, pickText = anchor)
                    else {
                        val near = withTimeout("nearest", 3000) { svc.nearestCandidates(anchor, 5) }
                            ?.joinToString(", ") ?: ""
                        ActResult(false, "native tapText failed", note = "nearest: $near")
                    }
                } else {
                    val dump = withTimeout("dump", 4000) { svc.dumpScreenCompact("all") }
                        ?: return ActResult(false, "screen read timed out")
                    // attempt 1：放开 clickableOnly（更宽松的候选池）
                    val clickableOnly = attempt == 0
                    val r = FastPicker.pick(dump, anchor, clickableOnly)
                    val c = r.candidate
                    if (c == null) {
                        // 附带候选池：升级证据（云端知道屏幕上有什么）
                        val pool = JSONArray()
                        FastPicker.parseCompact(dump)
                            .filter { it.text.isNotBlank() && it.text != "-" }
                            .take(24)
                            .forEach { pool.put(JSONObject().put("t", it.text).put("c", it.clickable)) }
                        ActResult(false, "pick found nothing", via = "none", score = r.score, pool = pool)
                    } else {
                        // v1.2.132 重试角色守卫（AI 体验反馈）：实测重试曾把目标从
                        // 开关重选到**页面标题**（score 0.95）——"比不重试更危险"。
                        // 重试时角色与首次不一致（且双方都已知角色）→ 拒绝该次，
                        // 宁可升级给云端也不误点。
                        if (attempt > 0 && !firstRole.isNullOrEmpty() &&
                            c.role.isNotEmpty() && c.role != firstRole
                        ) {
                            return ActResult(
                                false,
                                "repick rejected: role mismatch (${c.role} != $firstRole)",
                                via = r.via, score = r.score, pickText = c.text, role = c.role,
                            )
                        }
                        val ok = withTimeout("tapRect", 3000) {
                            svc.dispatchTapRect(android.graphics.Rect(c.x, c.y, c.x, c.y))
                        } ?: false
                        if (ok) ActResult(true, via = r.via, score = r.score, pickText = c.text,
                            tapX = c.x, tapY = c.y, role = c.role)
                        else ActResult(false, "tap dispatch failed", role = c.role)
                    }
                }
            }

            "tap_text" -> {
                val t = action.optString("text").ifBlank { anchors.firstOrNull() ?: "" }
                if (t.isBlank()) return ActResult(false, "tap_text needs text")
                val ok = withTimeout("tapText", 4000) { svc.tapText(t) } ?: false
                if (ok) ActResult(true, via = "native", score = 1.0, pickText = t)
                else ActResult(false, "tapText failed", note = run {
                    val near = withTimeout("nearest", 3000) { svc.nearestCandidates(t, 5) }
                    "nearest: ${near?.joinToString(", ") ?: ""}"
                })
            }

            "swipe" -> {
                val (w, h) = withTimeout("screenSize", 2000) { svc.screenSize() } ?: (1080 to 1920)
                val dist = action.optDouble("dist", 0.5).coerceIn(0.1, 0.9)
                val dir = action.optString("dir", "up")
                val cx = w / 2f
                val cy = h / 2f
                val dx = (w * dist / 2).toFloat()
                val dy = (h * dist / 2).toFloat()
                val (x1, y1, x2, y2) = when (dir) {
                    "up" -> listOf(cx, cy + dy, cx, cy - dy)
                    "down" -> listOf(cx, cy - dy, cx, cy + dy)
                    "left" -> listOf(cx + dx, cy, cx - dx, cy)
                    else -> listOf(cx - dx, cy, cx + dx, cy)
                }
                // v1.2.149：默认**拟人化**滑动（弧线+变速+起止抖动，用户需求）；
                // human=false 走精确直线（像素级拖拽场景）。
                val human = action.optBoolean("human", true)
                val dur = action.optLong("durationMs", 0L)   // v1.6.32：0 = 按距离自动
                val ok = withTimeout("swipe", 3000) {
                    if (human) svc.dispatchSwipeHuman(x1, y1, x2, y2, dur)
                    else svc.dispatchSwipe(x1, y1, x2, y2, dur)
                } ?: false
                ActResult(ok, if (ok) "" else "swipe dispatch failed", via = if (human) "swipe-human" else "swipe")
            }

            "scroll" -> {
                // 语义化滚动（v1.2.133，AI 体验反馈）：AI 实测 loop 无滚动动作
                // （`scroll`/`scroll_down` 报 unknown action type），长列表任务被迫
                // 跳出闭环用 scr —— 与「必须用 loop」的规则冲突。direction 按
                // **内容方向**命名（直观）：down = 看下面的内容（手指上滑）。
                val dir = action.optString("direction", "down").lowercase()
                val (w, h) = withTimeout("screenSize", 2000) { svc.screenSize() } ?: (1080 to 1920)
                // v1.7.2：几何交给 ScrollGeometry（纯函数、可 JVM 断言）。默认行程
                // 0.6h → 0.72h，起点从 0.65h 移到 0.86h —— 原来把 distance 调大时
                // 终点会算到屏幕外，反而滚得更少。
                val geo = app.dsh.mobile.ScrollGeometry.step(w, h, dir, action.optInt("distance", 0))
                val ok = withTimeout("scroll", 4000) {
                    svc.dispatchSwipeHuman(geo.x, geo.y1, geo.x, geo.y2, action.optLong("durationMs", 0L))
                } ?: false
                // v1.2.133：滚动后等界面稳定再返回——AI 实测反馈「scroll 未产生
                // 滚动」的真相是：它动作后立刻 dump，滚动动画未完成，看到的还是
                // 旧内容。等稳定再交还控制权，后续读取/判断才基于新画面。
                if (ok) settle(svc)
                ActResult(ok, if (ok) "" else "scroll dispatch failed")
            }

            "scroll_find" -> {
                // 滚动查找（v1.2.133，AI 体验反馈）：loop 内的「滚动直到找到」。
                // 相对 scr find 的三点改进（均来自 AI 实测痛点）：① **命中即停**
                // （scr find 滚到底才判定，8 次滚动全废）；② 失败回传已滚次数 +
                // 最后屏幕文本（可诊断，不用再发 dump）；③ 可选 `tap` 一步到位。
                // 用法：{"type":"scroll_find","text":["关于手机","关于模拟设备"],"maxSwipes":8,"tap":true}
                val wants = ArrayList<String>()
                action.optJSONArray("text")?.let { arr ->
                    for (k in 0 until arr.length()) wants.add(arr.optString(k))
                }
                if (wants.isEmpty()) anchors.forEach { wants.add(it) }
                if (wants.isEmpty()) return ActResult(false, "scroll_find needs text")
                val maxSwipes = action.optInt("maxSwipes", 8).coerceIn(1, 20)
                val doTap = action.optBoolean("tap", false)
                val (w, h) = withTimeout("screenSize", 2000) { svc.screenSize() } ?: (1080 to 1920)
                var swipes = 0
                var lastSnap = ""
                var stuck = 0
                while (true) {
                    val dump = withTimeout("dump", 4000) { svc.dumpScreenCompact("all") }
                    // 到底检测（v1.2.133）：连续 2 次滚动后内容完全不变 = 已滚到底
                    // （AI 实测：不可达目标会白滚满 maxSwipes，每次还叠加 settle 等待）。
                    // v1.2.156 说明：本轮试过"首次不变即停"和"廉价确认即停"，**两者都会
                    // 在手势异步未落地时误判到底**（实测滑 1-3 次就退出，而目标需要 8 次），
                    // 已全部回滚。用户实测反馈："平均一秒不到滚一次，整体感觉很流畅"——
                    // 保持可用优先，尾巴那 2 次白滑留待有更稳的到底判据（如滚动位置读数）再动。
                    // v1.2.159：改用**位置指纹**判"到底"——比整份 dump 稳得多：
                    // 轮播/倒计时/toast 只改文字不改坐标，旧比较会被它们骗得一直划。
                    val fp = if (dump != null) positionFingerprint(dump) else null
                    if (fp != null && fp == lastSnap) {
                        stuck++
                        if (stuck >= 2) break
                    } else {
                        stuck = 0
                    }
                    if (fp != null) lastSnap = fp
                    val cands = if (dump != null) FastPicker.parseCompact(dump) else emptyList()
                    val hit = cands.firstOrNull { c ->
                        c.text.isNotBlank() && wants.any { a -> c.text.contains(a, true) }
                    }
                    if (hit != null) {
                        if (doTap) {
                            val ok = withTimeout("tapRect", 3000) {
                                svc.dispatchTapRect(android.graphics.Rect(hit.x, hit.y, hit.x, hit.y))
                            } ?: false
                            return ActResult(ok, if (ok) "" else "scroll_find: tap dispatch failed",
                                via = "scroll_find", score = 1.0, pickText = hit.text,
                                tapX = hit.x, tapY = hit.y,
                                note = "found after $swipes swipes")
                        }
                        return ActResult(true, via = "scroll_find", score = 1.0, pickText = hit.text,
                            tapX = hit.x, tapY = hit.y, note = "found after $swipes swipes")
                    }
                    if (swipes >= maxSwipes) break
                    val cx = w / 2f
                    // 每次滚 35%（v1.2.133）：55% 的大跨度会在两次 dump 之间跳过
                    // 中间内容（AI 实测漏检「Android 版本」）。
                    withTimeout("scroll", 4000) {
                        svc.dispatchSwipeHuman(cx, (h * 0.7).toFloat(), cx, (h * 0.35).toFloat(), 0L)
                    }
                    swipes++
                    settle(svc)
                }
                val lastDump = withTimeout("dump", 4000) { svc.dumpScreenCompact("all") } ?: ""
                val tail = lastDump.lines().filter { it.contains('@') }.takeLast(8)
                    .joinToString(" | ") { it.substringAfter(' ').take(28) }
                ActResult(
                    false, "scroll_find: '${wants.first()}' not found after $swipes swipes",
                    note = "last screen: $tail",
                )
            }

            "input" -> {
                val text = action.optString("text")
                if (text.isEmpty()) return ActResult(false, "input needs text")
                // target 可选：指定输入框的定位文本（如"搜索或输入网址"）——
                // inputText 会先找到该节点并聚焦再写入，不依赖前置点击的焦点
                // （实测：Chrome 主页渲染慢时，点击地址栏的焦点会丢，带 target 才稳）
                // v1.2.153 实测修复（靶场发现）：此前**只认 action.target**，
                // 而 AI 很自然会写成步骤级 `"target":{"text":["搜索框"]}`（那是
                // PICK 的通用写法）→ 这里读到空 → 退化成"找当前焦点" → 静默失败，
                // 只留一句容易被忽略的 error。现在回退到步骤级锚点。
                val tgt = action.optString("target").takeIf { it.isNotBlank() }
                    ?: anchors.firstOrNull { it.isNotBlank() }
                val ok = withTimeout("inputText", 4000) {
                    svc.inputText(text, action.optBoolean("append", false), tgt)
                } ?: false
                ActResult(ok, if (ok) "" else "no editable field found (tap the input box first, or pass target)")
            }

            "key" -> {
                val name = action.optString("name", "back")
                // enter 走 ACTION_IME_ENTER（输入框提交），其余走全局动作
                val ok = if (name == "enter") {
                    withTimeout("enter", 3000) { svc.pressEnter() } ?: false
                } else {
                    withTimeout("key", 3000) { svc.performGlobalActionByName(name) } ?: false
                }
                ActResult(ok, if (ok) "" else "key failed: $name")
            }

            "launch" -> {
                val pkg = action.optString("package")
                val data = action.optString("data")
                if (pkg.isBlank() && data.isBlank()) return ActResult(false, "launch needs package or data")
                val ok = runCatching {
                    val intent = if (data.isNotBlank()) {
                        // data = URL：直接打开网页（Intent.ACTION_VIEW）
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(data))
                    } else {
                        ctx.packageManager.getLaunchIntentForPackage(pkg)
                    }
                    if (intent == null) false
                    else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                        true
                    }
                }.getOrDefault(false)
                ActResult(ok, if (ok) "" else "launch failed: $pkg$data")
            }

            "intent" -> {
                // 按 Intent action 直达（v1.2.137，AI 体验反馈「多挑几条快捷命令」）：
                // 打开系统设置的具体页面（显示/声音/WLAN/蓝牙/应用/关于…）无需
                // 一步步点列表——规划 AI 用它可省 3-5 步/页面，且零推理成本。
                // 用法：{"type":"intent","action":"android.settings.DISPLAY_SETTINGS"}
                // （可选 data 字段附带 Uri；未知 action 会由系统返回失败，安全）
                val act = action.optString("action")
                if (act.isBlank()) return ActResult(false, "intent needs action")
                val ok = runCatching {
                    val intent = Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    action.optString("data").takeIf { it.isNotBlank() }?.let {
                        intent.data = android.net.Uri.parse(it)
                    }
                    ctx.startActivity(intent)
                    true
                }.getOrDefault(false)
                ActResult(ok, if (ok) "" else "intent failed: $act")
            }

            "sleep" -> {
                Thread.sleep(action.optLong("ms", 500L).coerceIn(0L, 15_000L))
                ActResult(true)
            }

            "collect" -> {
                // 收集当前屏全部文本（去重）——本地"记录"能力，不含生成/总结
                val raw = withTimeout("collect.dump", 4000) { svc.dumpScreenJson() }
                    ?: return ActResult(false, "screen read timed out")
                val lines = runCatching {
                    val nodes = JSONObject(raw).getJSONArray("nodes")
                    val seen = LinkedHashSet<String>()
                    for (i in 0 until nodes.length()) {
                        val n = nodes.getJSONObject(i)
                        val t = n.optString("text").trim()
                        val d = n.optString("desc").trim()
                        if (t.isNotBlank() && t != "-") seen.add(t)
                        if (d.isNotBlank() && d != "-" && d != t) seen.add(d)
                    }
                    seen.toList()
                }.getOrDefault(emptyList())
                val text = lines.joinToString("\n")
                val name = action.optString("saveAs")
                    .ifBlank { "note-${System.currentTimeMillis()}.txt" }
                val path = runCatching {
                    val dir = File(ctx.filesDir, "notes").apply { mkdirs() }
                    val f = File(dir, name)
                    f.writeText(text)
                    f.absolutePath
                }.getOrNull() ?: ""
                ActResult(
                    ok = true,
                    collectedText = JSONObject().apply {
                        put("chars", text.length)
                        put("lines", lines.size)
                        put("path", path)
                        // 报告内截断预览（完整文本在文件里）
                        put("preview", text.take(600))
                    },
                )
            }

            "noop" -> ActResult(true)

            "scroll_collect" -> {
                // 滚动并自动收集文字（v1.2.148，用户点名）：滚动 N 次，每次读屏、
                // 去重合并文本——长列表/长文章的内容提取。返回合并后的全文。
                // 用法：{"type":"scroll_collect","maxSwipes":5,"direction":"down"}
                val maxSwipes = action.optInt("maxSwipes", 5).coerceIn(1, 20)
                val dir = action.optString("direction", "down")
                val seen = LinkedHashSet<String>()
                var rounds = 0
                while (rounds <= maxSwipes) {
                    val dump = withTimeout("dump", 4000) { svc.dumpScreenCompact("all") }
                    if (dump != null) {
                        FastPicker.parseCompact(dump).forEach { c ->
                            if (c.text.isNotBlank() && c.text != "-" && c.text.length > 1) seen.add(c.text)
                        }
                    }
                    if (rounds >= maxSwipes) break
                    val (w, h) = withTimeout("screenSize", 2000) { svc.screenSize() } ?: (1080 to 1920)
                    val y1 = if (dir == "up") h * 0.35f else h * 0.65f
                    val y2 = if (dir == "up") h * 0.8f else h * 0.2f
                    withTimeout("scroll", 4000) { svc.dispatchSwipeHuman(w / 2f, y1, w / 2f, y2, 0L) }
                    rounds++
                    settle(svc)
                }
                val text = seen.joinToString("\n")
                ActResult(
                    true, via = "scroll_collect", score = 1.0,
                    note = "collected ${seen.size} unique lines in $rounds swipes",
                    collectedText = JSONObject()
                        .put("lines", seen.size)
                        .put("chars", text.length)
                        .put("preview", text.take(4000)),
                )
            }

            "repeat" -> {
                // 重复执行一个动作 N 次（v1.2.148，用户点名「定时重复」）：
                // 刷新页面、重复点击等场景。
                // 用法：{"type":"repeat","action":{...},"times":3,"intervalMs":800}
                val sub = action.optJSONObject("action")
                    ?: return ActResult(false, "repeat needs an inner action")
                val times = action.optInt("times", 3).coerceIn(1, 20)
                val interval = action.optLong("intervalMs", 600L).coerceIn(0L, 10_000L)
                var okCount = 0
                repeat(times) { i ->
                    val r = performAction(ctx, svc, sub, anchors, 0, null)
                    if (r.ok) okCount++
                    if (i < times - 1 && interval > 0) Thread.sleep(interval)
                }
                ActResult(
                    okCount > 0, if (okCount > 0) "" else "all repeats failed",
                    via = "repeat", score = 1.0,
                    note = "repeated ${times}x ($okCount ok, interval ${interval}ms)",
                )
            }

            "repeat_until" -> {
                // 重复执行直到条件满足（v1.2.148）：刷新等待、重试点击——
                // 比固定 repeat 更聪明（条件满足即停，不浪费）。
                // 用法：{"type":"repeat_until","action":{...},"verify":{...},"maxTimes":8,"intervalMs":800}
                val sub = action.optJSONObject("action")
                    ?: return ActResult(false, "repeat_until needs an inner action")
                val cond = action.optJSONObject("verify")
                val maxTimes = action.optInt("maxTimes", 8).coerceIn(1, 20)
                val interval = action.optLong("intervalMs", 800L).coerceIn(0L, 10_000L)
                var n = 0
                while (n < maxTimes) {
                    performAction(ctx, svc, sub, anchors, 0, null)
                    n++
                    if (interval > 0) Thread.sleep(interval)
                    if (cond != null) {
                        val v = withTimeout("repeat.verify", 2500L) { verifyStep(svc, cond, 1500L, false) }
                        if (v?.ok == true) {
                            return ActResult(true, via = "repeat_until", score = 1.0,
                                note = "condition met after ${n} repeats")
                        }
                    }
                }
                ActResult(false, "condition not met after $maxTimes repeats", via = "repeat_until")
            }

            "double_tap" -> {
                // 双击（v1.2.148）：地图缩放/图片放大等。两次快速点击（间隔
                // <150ms——再慢会被识别成两次单击）。用法：target 锚点。
                val anchor = anchors.firstOrNull() ?: action.optString("text")
                if (anchor.isBlank()) return ActResult(false, "double_tap needs target")
                val dump = withTimeout("dump", 4000) { svc.dumpScreenCompact("all") }
                    ?: return ActResult(false, "screen read timed out")
                val c = FastPicker.pick(dump, anchor, true).candidate
                    ?: return ActResult(false, "double_tap: '$anchor' not found")
                svc.dispatchTapRect(android.graphics.Rect(c.x, c.y, c.x, c.y))
                Thread.sleep(110)
                svc.dispatchTapRect(android.graphics.Rect(c.x, c.y, c.x, c.y))
                ActResult(true, via = "double_tap", score = 1.0, pickText = c.text, tapX = c.x, tapY = c.y)
            }

            "long_press" -> {
                // 长按（v1.2.148）：loop 此前缺此原语（scr 有）。同点长按时长。
                // 用法：{"type":"long_press","durationMs":800} + target 锚点
                val anchor = anchors.firstOrNull() ?: action.optString("text")
                if (anchor.isBlank()) return ActResult(false, "long_press needs target")
                val dur = action.optLong("durationMs", 800L).coerceIn(300L, 5000L)
                val dump = withTimeout("dump", 4000) { svc.dumpScreenCompact("all") }
                    ?: return ActResult(false, "screen read timed out")
                val c = FastPicker.pick(dump, anchor, true).candidate
                    ?: return ActResult(false, "long_press: '$anchor' not found")
                val ok = withTimeout("longPress", 4000) {
                    svc.dispatchSwipe(c.x.toFloat(), c.y.toFloat(), c.x.toFloat(), c.y.toFloat(), dur)
                } ?: false
                ActResult(ok, if (ok) "" else "long_press failed",
                    via = "long_press", score = 1.0, pickText = c.text, tapX = c.x, tapY = c.y)
            }

            "pinch" -> {
                // 双指捏合（v1.2.148）：地图/图片缩放——无障碍 dispatchGesture
                // 支持多笔划（对称向外=放大，向内=缩小）。
                // 用法：{"type":"pinch","scale":1.5}（>1 放大 <1 缩小）
                val scale = action.optDouble("scale", 1.5).coerceIn(0.3, 3.0)
                val ok = withTimeout("pinch", 4000) { svc.dispatchPinch(scale.toFloat()) } ?: false
                ActResult(ok, if (ok) "" else "pinch dispatch failed",
                    via = "pinch", note = "scale=$scale")
            }

            "drag" -> {
                // 拖拽（v1.2.148）：长距离拖（滑块/排序）。起点用 target 锚点。
                // 用法：{"type":"drag","toX":0.5,"toY":0.3}（归一化目标）或 toPx
                val anchor = anchors.firstOrNull() ?: action.optString("text")
                val dump = withTimeout("dump", 4000) { svc.dumpScreenCompact("all") }
                    ?: return ActResult(false, "screen read timed out")
                val c = FastPicker.pick(dump, anchor, true).candidate
                    ?: return ActResult(false, "drag: '$anchor' not found")
                val (w, h) = withTimeout("screenSize", 2000) { svc.screenSize() } ?: (1080 to 1920)
                val tx = (action.optDouble("toX", -1.0).takeIf { it >= 0 }?.let { it * w }?.toFloat())
                    ?: action.optInt("toPx", w / 2).toFloat()
                val ty = (action.optDouble("toY", -1.0).takeIf { it >= 0 }?.let { it * h }?.toFloat())
                    ?: action.optInt("toPy", (h * 0.3).toInt()).toFloat()
                val ok = withTimeout("drag", 5000) {
                    svc.dispatchSwipe(c.x.toFloat(), c.y.toFloat(), tx, ty,
                        action.optLong("durationMs", 700L))
                } ?: false
                ActResult(ok, if (ok) "" else "drag dispatch failed",
                    via = "drag", pickText = c.text, tapX = c.x, tapY = c.y,
                    note = "→ ${tx.toInt()},${ty.toInt()}")
            }

            "back_to" -> {
                // 连续返回直到前台是目标包（v1.2.148）：跨应用回退——比连点
                // back 更省心（自动停在目标应用，不过头）。用法：
                // {"type":"back_to","package":"com.android.settings"}
                val pkg = action.optString("package")
                if (pkg.isBlank()) return ActResult(false, "back_to needs package")
                var n = 0
                while (n < 10) {
                    val cur = svc.foregroundPackage()
                    if (cur != null && cur.startsWith(pkg)) break
                    withTimeout("back", 2000) { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
                    n++
                    Thread.sleep(500)
                }
                val nowPkg = svc.foregroundPackage() ?: "?"
                ActResult(nowPkg.startsWith(pkg), if (nowPkg.startsWith(pkg)) "" else "back_to: still at $nowPkg",
                    via = "back_to", note = "backed ${n}x → $nowPkg")
            }

            "set_switch" -> {
                // 幂等开关（v1.2.131，AI 体验反馈驱动）：按标签找同行开关 →
                // 读当前态 → 只在需要时点击。解决三个实测痛点：
                //  ① 开关节点文本为空（"-"），标签在旁边的行上 → 同行关联（±80px，
                //     与 verify 的 checkedText 同判据）；
                //  ② toggle 类动作重试会二次翻转（开了又关）→ 幂等语义；
                //  ③ 状态位不可断言 → 动作回传切换前后状态（note），供诊断。
                // 用法：{"type":"set_switch","value":"on|off"}，target 给标签锚点。
                val want = action.optString("value", "on").lowercase()
                val wantOn = want !in setOf("off", "false", "0")
                val dump = withTimeout("dump", 4000) { svc.dumpScreenCompact("all") }
                    ?: return ActResult(false, "set_switch: screen read timed out")
                val cands = FastPicker.parseCompact(dump)
                val switches = cands.filter { it.checked != null }
                if (switches.isEmpty()) return ActResult(false, "set_switch: no switch on screen")
                val label = cands.firstOrNull { c ->
                    c.text.isNotBlank() && anchors.any { a -> c.text.contains(a, true) }
                }
                val sw = if (label != null) {
                    switches.minByOrNull { c -> kotlin.math.abs(c.y - label.y) }
                } else switches.first()
                if (sw == null) return ActResult(false, "set_switch: no switch near label")
                val cur = sw.checked == true
                if (cur == wantOn) {
                    // 幂等：已是目标态 → 不点击直接成功（避免二次翻转）
                    return ActResult(
                        true, via = "idempotent", score = 1.0, pickText = sw.text,
                        tapX = sw.x, tapY = sw.y,
                        note = "already ${if (wantOn) "on" else "off"}",
                        role = "switch",
                    )
                }
                val ok = withTimeout("tapRect", 3000) {
                    svc.dispatchTapRect(android.graphics.Rect(sw.x, sw.y, sw.x, sw.y))
                } ?: false
                if (!ok) return ActResult(false, "set_switch: tap dispatch failed")
                ActResult(
                    true, via = "switch", score = 1.0, pickText = sw.text,
                    tapX = sw.x, tapY = sw.y,
                    note = "toggled ${if (cur) "on" else "off"}→${if (wantOn) "on" else "off"}",
                    role = "switch",
                )
            }

            else -> ActResult(false, "unknown action type: $type")
        }
    }

    // ==================== 验证层 ====================

    private class VerifyResult(
        val ok: Boolean, val how: String,
        val polls: Int = 0, val dumpReads: Int = 0, val frameReads: Int = 0,
        /** 失败帧快照（v1.2.132，AI 体验反馈）：失败时最后一次读到的屏幕
         *  压成紧凑行——省去 AI「失败后必发一次 dump」的往返。 */
        val failScreen: String = "",
        /** 一次明显偏慢的语义调用耗时（v1.2.152）：>0 表示本轮因此放弃了语义兜底 */
        val slowSemanticMs: Long = 0L,
    )

    /**
     * 保留「任一条件满足即通过」语义。事件只触发更早读屏，不能凭旧摘要直接通过。
     * 无变化时整树读取逐渐退避到 750ms；事件缺失/被其他观察者消费仍有周期读取兜底。
     * timeout 是总 VERIFY 预算，所有 IPC/sleep 都受剩余期限约束，防止最后一读超时外溢。
     */
    private fun verifyStep(
        svc: app.dsh.mobile.DshAccessibilityService,
        verify: JSONObject?,
        budgetMs: Long,
        useScreenEvents: Boolean,
    ): VerifyResult {
        if (verify == null || verify.length() == 0) return VerifyResult(true, "none")
        // ⚠️ v1.2.152 审查修复（P1）：有内容但**全是未知 key**（如 anyTxt/checkedtext
        // 拼错）→ 明确失败，而不是静默 ok/none——否则 AI 以为验证过、实际判据从未求值。
        val KNOWN_KEYS = setOf("anyText", "checkedText", "toggle", "frameChanged", "foregroundPkg", "timeoutMs")
        val keys = verify.keys()
        var knownCount = 0
        while (keys.hasNext()) if (keys.next() in KNOWN_KEYS) knownCount++
        if (knownCount == 0) {
            val bad = StringBuilder()
            val it2 = verify.keys()
            while (it2.hasNext()) { if (bad.isNotEmpty()) bad.append(","); bad.append(it2.next()) }
            return VerifyResult(false, "unknown verify keys: $bad")
        }
        val anyText = verify.optJSONArray("anyText")
        val checkedText = verify.optJSONArray("checkedText")
        val frameChanged = verify.optBoolean("frameChanged", false)
        val fgPkg = verify.optString("foregroundPkg")
        val toggle = verify.optJSONObject("toggle")
        if (anyText == null && !frameChanged && fgPkg.isBlank() &&
            checkedText == null && toggle == null
        ) {
            return VerifyResult(true, "none")
        }
        val deadline = nowMs() + budgetMs
        var polls = 0
        var dumpReads = 0
        var frameReads = 0
        var nextDumpAt = 0L
        // takeScreenshot 过密会报 INTERVAL_TIME_SHORT，不能紧跟动作前的 prime 再抓一帧。
        var nextFrameAt = nowMs() + 300L
        var dumpInterval = 80L
        var lastDump: String? = null
        var nextSemanticAt = 0L
        var semanticGivenUp = false
        var slowSemanticMs = 0L
        fun result(ok: Boolean, how: String) = VerifyResult(
            ok, how, polls, dumpReads, frameReads, slowSemanticMs = slowSemanticMs)
        while (nowMs() < deadline) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("verify interrupted")
            polls++
            // v1.2.131：toggle 也走 dump 通道——漏掉它时「只有 toggle 的验证」
            // 永远不读屏、必然超时（实测：AI 用 toggle 验证切开关，verifyHow=deadline）。
            val needsDump = anyText != null || checkedText != null || toggle != null
            if (needsDump && useScreenEvents) {
                val events = svc.changedSummary()
                if (events.isNotBlank()) {
                    dumpInterval = 80L
                    nextDumpAt = minOf(nextDumpAt, nowMs())
                }
            }
            if (needsDump && nowMs() >= nextDumpAt) {
                // 剩余预算装不下一次读屏 → 立即收尾，别开一个必然超时的读
                // （v1.2.152：单次迭代能把 deadline 甩开数秒，AI 请求的 timeoutMs
                //  实测被超支 2 倍以上）。
                if (deadline - nowMs() < 400L) break
                dumpReads++
                // ---- v1.2.152 性能/一致性修复：只有 anyText 的验证走 **compact**
                // （体积约为 JSON 的 1/7，读一次 ~30ms vs ~90ms），而且 compact
                // 里带着只有本地才补出来的三类信息：OCR 行（`o` 前缀）、图标识别名
                // （`=name`）、空间推断标签（`~标签`）——**它们是 AI 在 dump 里看到的
                // 内容**。此前 verify 一律用 JSON dump，于是出现「AI 看得见、
                // 验证却说没出现」的认知撕裂。
                // checkedText / toggle 需要 checkable/checked 结构化字段，继续走 JSON。
                val needStructured = checkedText != null || toggle != null
                val raw = if (needStructured) {
                    withTimeout("verify.dump", minOf(3000L, deadline - nowMs())) { svc.dumpScreenJson() }
                } else {
                    withTimeout("verify.compact", minOf(3000L, deadline - nowMs())) { svc.dumpScreenCompact("all") }
                }
                if (!needStructured && raw != null) {
                    val tokens = compactTokens(raw)
                    if (anyText != null && tokens.any { tk -> (0 until anyText.length()).any { k ->
                            val w = anyText.optString(k)
                            w.isNotBlank() && tk.contains(w, true)
                        }
                    }) return result(true, "anyText(compact)")
                    // 语义兜底的时间守卫（v1.2.152 实测）：语义调用**不受 deadline
                    // 约束**（每次都是一次独立的嵌入请求），实测请求 timeoutMs=9000
                    // 时 verify 跑了 20.2s——超预算 2 倍以上，AI 被白白拖住。
                    // 两条纪律：① 剩余预算不足就彻底不试（省一次必然白花的等待）；
                    // ② 最快每 900ms 只试一次（否则每个轮询都发一次嵌入请求）。
                    if (anyText != null && !semanticGivenUp &&
                        nowMs() + 2_500L < deadline && nowMs() >= nextSemanticAt
                    ) {
                        nextSemanticAt = nowMs() + 900L
                        val semT0 = nowMs()
                        val semHit = semanticMatchTexts(tokens, anyText, deadline)
                        // 一次语义调用明显偏慢 → 本轮不再用语义（记录在报告里可诊断）。
                        // 慢的通常是首次冷加载（模型要进内存），让它在后台自然完成，
                        // 别让后续每个轮询再吃一次这种延迟。
                        if (nowMs() - semT0 > 1_200L) {
                            semanticGivenUp = true
                            slowSemanticMs = nowMs() - semT0
                        }
                        if (semHit) return result(true, "anyText(compact-semantic)")
                        // 语义调用可能长于剩余预算（冷加载）→ 复查后立即收尾
                        if (nowMs() >= deadline) break
                    }
                }
                // 先存证：任何 break 路径都要能拿到本次读到的屏幕（失败帧靠它）
                if (raw != null) lastDump = raw
                val nodes = if (needStructured)
                    raw?.let { runCatching { JSONObject(it).getJSONArray("nodes") }.getOrNull() } else null
                if (nodes != null) {
                    // anyText/checkedText 同一轮只做一次 dump，避免条件越多读取越慢。
                    if (anyText != null && (0 until nodes.length()).any { i ->
                        matchesText(nodes.getJSONObject(i), anyText)
                    }) return result(true, "anyText")
                    // v1.2.124 语义兜底（让本地模型真正参与）：精确子串 miss 后，
                    // 用本地嵌入做语义匹配。真实收益场景（真机反馈驱动）：
                    //  · OCR 识别噪声（实测「凶未连接到互联网」vs want「未连接到互联网」）；
                    //  · 近义表述（「下载完成」vs 屏幕「已下载完毕」）；
                    //  · 云端用了概括词而屏幕是具体标签。
                    // 毫秒级、离线；服务未就绪时 isReady() 有 30s 缓存，几乎零成本。
                    if (anyText != null && semanticMatchTexts(nodeTexts(nodes), anyText, deadline)) {
                        return result(true, "anyText(semantic)")
                    }
                    if (checkedText != null) {
                        // 标签与无文本 Switch 的同行关联沿用 v1.2.126 的判据。
                        val checkedYs = ArrayList<Int>()
                        val textYs = ArrayList<Int>()
                        for (i in 0 until nodes.length()) {
                            val n = nodes.getJSONObject(i)
                            if (n.optBoolean("checkable", false) && n.optBoolean("checked", false)) {
                                checkedYs.add(n.optInt("y"))
                            }
                            if (matchesText(n, checkedText)) textYs.add(n.optInt("y"))
                        }
                        if (checkedYs.any { cy -> textYs.any { ty -> kotlin.math.abs(cy - ty) < 80 } }) {
                            return result(true, "checkedText")
                        }
                    }
                    // 状态位断言（v1.2.131，AI 体验反馈驱动）：
                    //   {"toggle":{"label":"启用深色主题","to":"on"}} 或 {"toggle":{"text":[...],"to":"off"}}
                    // 与 checkedText 的关键区别：**能断言 off**——checkedText 只验
                    // 「某标签旁存在已勾选节点」，目标状态是 off 时永远无法通过
                    // （AI 实测踩过：切换前用 checkedText 验证必然失败，规则靠试探）。
                    if (toggle != null) {
                        // v1.2.152 审查修复：label 与 text 双空 → 立即失败（否则 matchesText
                        // 对空串永不命中，白烧完整个验证预算）
                        if (toggle.optJSONArray("text") == null && toggle.optString("label").isBlank()) {
                            return result(false, "toggle needs label or text")
                        }
                        val to = toggle.optString("to", "on").lowercase()
                        val wantOn = to !in setOf("off", "false", "0")
                        val labels = toggle.optJSONArray("text")
                            ?: org.json.JSONArray().put(toggle.optString("label"))
                        val labelYs = ArrayList<Int>()
                        for (i in 0 until nodes.length()) {
                            val n = nodes.getJSONObject(i)
                            if (matchesText(n, labels)) labelYs.add(n.optInt("y"))
                        }
                        for (i in 0 until nodes.length()) {
                            val n = nodes.getJSONObject(i)
                            if (n.optBoolean("checkable", false)) {
                                val ny = n.optInt("y")
                                if (labelYs.any { ty -> kotlin.math.abs(ny - ty) < 80 } &&
                                    n.optBoolean("checked", false) == wantOn
                                ) return result(true, "toggle")
                            }
                        }
                    }
                }
                dumpInterval = if (raw != null && raw != lastDump) 80L else minOf(750L, dumpInterval * 2)
                lastDump = raw
                nextDumpAt = nowMs() + dumpInterval
            }
            if (fgPkg.isNotBlank()) {
                val cur = withTimeout("verify.fg", minOf(2000L, deadline - nowMs())) { svc.foregroundPackage() }
                if (cur != null && cur.startsWith(fgPkg)) return result(true, "foregroundPkg")
            }
            if (frameChanged && nowMs() >= nextFrameAt) {
                frameReads++
                val r = withTimeout("verify.frame", minOf(3000L, deadline - nowMs())) {
                    app.dsh.mobile.Framer.captureAndDiff(svc)
                }
                if (r != null && r.changed) {
                    return result(true, "frameChanged(${ "%.4f".format(r.changedRatio) })")
                }
                nextFrameAt = nowMs() + if (r == null || r.unavailable) 750L else 300L
            }
            val remaining = deadline - nowMs()
            if (remaining <= 0L) break
            // 廉价事件检查 60ms；未使用事件时只在实际需读树/帧/前台时唤醒。
            var sleepMs = if (needsDump && useScreenEvents || fgPkg.isNotBlank()) 60L else 750L
            if (needsDump) sleepMs = minOf(sleepMs, (nextDumpAt - nowMs()).coerceAtLeast(1L))
            if (frameChanged) sleepMs = minOf(sleepMs, (nextFrameAt - nowMs()).coerceAtLeast(1L))
            Thread.sleep(minOf(sleepMs, remaining))
        }
        // 失败帧：优先用循环里最后一次读到的屏幕；一次都没读到（读屏本身超时）
        // 时补一次**有界**收尾读（1.5s）——失败诊断是最需要屏幕内容的时刻，
        // 但也不能让一次诊断把已经超支的预算再拉长。
        var snapSrc = lastDump
        if (snapSrc == null) {
            snapSrc = withTimeout("failSnap.dump", 900L) { svc.dumpScreenCompact("all") }
        }
        return VerifyResult(
            false, "deadline", polls, dumpReads, frameReads,
            failScreen = failSnap(snapSrc), slowSemanticMs = slowSemanticMs,
        )
    }

    /**
     * 失败帧快照（v1.2.132，AI 体验反馈）：把最后一次 JSON dump 压成紧凑行
     * （前 20 个有文本节点，含勾选位）。AI 原话：「失败诊断本应自带失败帧」
     * ——此前它必须再发一次 `scr dump` 才能知道失败时屏幕上是什么。
     */
    private fun failSnap(lastDump: String?): String {
        val raw = lastDump ?: return ""
        if (!raw.trimStart().startsWith("{")) {
            // ⚠️ v1.2.152 实测修复：verify 改走 compact 快路径后，这里的
            // JSONObject(raw) 必然抛异常 → runCatching 吞掉 → failScreen **恒为空**，
            // v1.2.132 的「失败帧」功能静默失效（字段还在、调用方还在读、就是永远空）。
            // compact 本身就是紧凑行格式，直接截取即可（零成本）。
            return raw.lineSequence().filter { it.isNotBlank() }.take(22).joinToString("\n")
        }
        return runCatching {
            val nodes = JSONObject(raw).getJSONArray("nodes")
            val sb = StringBuilder()
            var n = 0
            for (i in 0 until nodes.length()) {
                if (n >= 20) break
                val o = nodes.getJSONObject(i)
                val t = o.optString("text").ifBlank { o.optString("desc") }
                if (t.isBlank()) continue
                sb.append(o.optInt("index")).append(' ').append(t.take(36))
                    .append(" @").append(o.optInt("x")).append(',').append(o.optInt("y"))
                if (o.optBoolean("checkable", false)) {
                    sb.append(if (o.optBoolean("checked", false)) " v" else " o")
                }
                sb.append('\n')
                n++
            }
            sb.toString().trimEnd()
        }.getOrDefault("")
    }

    /**
     * 把 compact dump 解析成「可匹配的文本令牌」（v1.2.152）。
     *
     * 与 `whenMatches` 直接对整份 dump 做 substring 不同，这里逐行解析：既避免
     * 表头（`1080x2209 13nodes ocr=unavailable`）和坐标段造成误命中，又能把
     * `d:`（contentDescription）、`=图标名`、`~推断标签` 一并纳入——这三类正是
     * AI 从 dump 里看到并据此决策的内容。
     */
    private fun compactTokens(compact: String): List<String> {
        val out = ArrayList<String>()
        for (line in compact.lineSequence()) {
            val m = COMPACT_LINE.find(line.trim()) ?: continue
            // v1.2.153：`p:` = 空输入的 placeholder（见 DshAccessibilityService 处说明），
            // 与 `d:` 一样按内容参与匹配
            val text = m.groupValues[2].trim().removePrefix("d:").removePrefix("p:").trim()
            if (text.isNotEmpty() && text != "-") out.add(text)
            val rest = m.groupValues[5]
            if (rest.isNotEmpty()) {
                Regex("=(\\S+)").findAll(rest).forEach { out.add(it.groupValues[1]) }
                Regex("~(.+)$").find(rest)?.groupValues?.get(1)?.trim()
                    ?.let { if (it.isNotEmpty()) out.add(it) }
            }
        }
        return out
    }

    /** compact 行格式：可选 o 前缀 + 序号 + 文本 + 坐标段，坐标段之后依次是
     *  标志/角色、等号开头的图标识别名、波浪号开头的空间推断标签。 */
    private val COMPACT_LINE = Regex("^o?(\\d+)\\s+(.*?)\\s+@(\\d+),(\\d+)(?:\\s+(.*))?$")

    /**
     * 位置指纹（v1.2.159，用户反馈"到底后还会划四五下才停"）：
     * 只取每个节点的坐标（忽略文字），用于判断"页面有没有真的滚动"。
     *
     * 旧实现直接比**整份 dump**，而页面上的轮播广告/倒计时/toast 会不停改文字内容
     * （坐标不变）→ 每次比较都"不一样" → 到底计数被反复清零 → 一直划到 maxSwipes。
     * 用坐标序列做指纹后，动态文字不再干扰；真正的滚动必然会改坐标。
     */
    private fun positionFingerprint(dump: String): String {
        val sb = StringBuilder(dump.length / 4)
        for (line in dump.lineSequence()) {
            val at = line.indexOf('@')
            if (at < 0) continue
            sb.append(line, at + 1, minOf(line.length, at + 14)).append(';')
        }
        return sb.toString()
    }

    private fun matchesText(node: JSONObject, wants: JSONArray): Boolean {
        val text = node.optString("text")
        val desc = node.optString("desc")
        val hint = node.optString("hint")     // v1.2.153：空输入的 placeholder 也算内容
        return (0 until wants.length()).any { k ->
            val want = wants.optString(k)
            want.isNotBlank() && (text.contains(want, true) || desc.contains(want, true) ||
                hint.contains(want, true))
        }
    }

    /**
     * 分支条件判断（v1.2.125）：当前屏幕是否满足 `when`。
     * 支持 `anyText`（任一文本出现）与 `noneText`（任一文本不出现），
     * 两个字段同时存在时都要满足。用 compact dump 做包含判断
     * （含 OCR 兜底行 → 无节点树界面同样可判分支）；只在步骤带 when 时读屏。
     */
    private fun whenMatches(svc: app.dsh.mobile.DshAccessibilityService, cond: JSONObject): Boolean {
        val dump = withTimeout("when.dump", 3000) { svc.dumpScreenCompact("all") } ?: return false
        val anyText = cond.optJSONArray("anyText")
        if (anyText != null && (0 until anyText.length()).none { k ->
                val w = anyText.optString(k)
                w.isNotBlank() && dump.contains(w, true)
            }
        ) return false
        val noneText = cond.optJSONArray("noneText")
        if (noneText != null && (0 until noneText.length()).any { k ->
                val w = noneText.optString(k)
                w.isNotBlank() && dump.contains(w, true)
            }
        ) return false
        return true
    }

    /**
     * 语义验证兜底（v1.2.124）：任一 want 与屏幕文本的余弦相似度 ≥ 阈值即命中。
     * 阈值比 pick 的 0.62 高——验证通过会结束流程，误判代价大于点击误点
     * （点击错了还有后续步骤的验证兜底；验证错了直接错误完成）。
     */
    /** JSON 节点 → 可匹配文本列表（text + desc） */
    private fun nodeTexts(nodes: JSONArray): List<String> {
        val texts = ArrayList<String>(nodes.length() * 2)
        for (i in 0 until nodes.length()) {
            val n = nodes.getJSONObject(i)
            val t = n.optString("text")
            val d = n.optString("desc")
            val h = n.optString("hint")
            if (t.isNotBlank()) texts.add(t)
            if (d.isNotBlank()) texts.add(d)
            if (h.isNotBlank()) texts.add(h)
        }
        return texts
    }

    /** 语义匹配公共实现（JSON 节点文本 / compact 令牌两种来源共用）
     *  @param deadlineMs 绝对截止时刻；**每个 want 一次嵌入请求**，不设上限时
     *         多词目标会把预算甩开数秒（实测 5 个词 = 13.5s，单次 verify 总耗时
     *         从 6s 变 13.5s）。到点即停止尝试剩余词。 */
    private fun semanticMatchTexts(
        texts: List<String>, wants: JSONArray, deadlineMs: Long = Long.MAX_VALUE,
    ): Boolean {
        if (texts.isEmpty()) return false
        for (k in 0 until wants.length()) {
            if (nowMs() >= deadlineMs) return false
            val want = wants.optString(k)
            if (want.isBlank() || want.length < 2) continue
            val m = LocalEmbedder.bestMatch(texts, want) ?: continue
            // ⚠️ v1.2.152 实测修复：**纯阈值判定会假阳性**。取证：Gmail 冷启动停在
            // 「Gmail 换新颜 / 功能强大一如既往…」引导页（屏幕上没有"收件箱"三个字），
            // want「收件箱」仍被 bge-small-zh 判到 ≥0.78 → verify 报"已进入收件箱"。
            // 短中文 UI 串上这个 15MB 模型的相似度分布被整体压高，阈值挡不住无关文本。
            // 语义命中必须**有词面锚点**（共享汉字，或共享一个长度 ≥2 的 ASCII 词）：
            // 这样"识别噪声/近义表述"仍能通过（实测收益场景本来就是词面接近的），
            // 而完全无关的文本会被挡掉。假阳性比慢更糟——它让"已验证"变成谎话。
            if (m.score >= SEMANTIC_VERIFY_MIN && lexicalOverlap(texts[m.index], want)) {
                FastPicker.stats.verifySemantic.incrementAndGet()
                return true
            }
        }
        return false
    }

    /** 语义验证阈值（比 pick 的 0.62 严：验证错误 = 错误完成，代价更大） */
    private const val SEMANTIC_VERIFY_MIN = 0.78

    /**
     * 语义命中的**词面锚点**校验（v1.2.152 实测新增）。
     *
     * 规则：命中文本与目标必须共享汉字，或共享一个长度 ≥2 的 ASCII 词/数字串。
     * 实测收益场景都满足它（OCR 噪声「凶未连接到互联网」vs「未连接到互联网」共享
     * 汉字；近义表述「已下载完毕」vs「下载完成」共享「下载」）；而实测的假阳性
     * （「Gmail 换新颜」vs「收件箱」）会被挡住。
     */
    private fun lexicalOverlap(matched: String, want: String): Boolean {
        val m = matched.lowercase()
        val w = want.lowercase()
        if (m.isEmpty() || w.isEmpty()) return false
        if (m.contains(w) || w.contains(m)) return true
        for (ch in w) if (ch.code > 0x2E80 && m.contains(ch)) return true
        for (tok in w.split(Regex("[^a-z0-9]+"))) {
            if (tok.length >= 2 && m.contains(tok)) return true
        }
        return false
    }

    // ==================== 工具 ====================

    /**
     * 等界面稳定后再动作（v1.2.123 实测修正）。
     * ① 文本场景：节点树签名连续 2 次一致（快，通常 120-300ms）；
     * ② 动态视觉场景（节点树空/不稳）：帧差连续 2 帧无变化兜底。
     * ③ 真机反馈「不流畅」后收紧：waitForIdle 对空节点树快速返回
     *   （不再白等 900ms），总预算 1800→1200ms、帧差间隔 300→200ms；
     *   慢动画到点放行，正确性由动作后 verify 兜底。
     */
    private fun settle(svc: app.dsh.mobile.DshAccessibilityService) {
        // v1.2.144：改用**页面状态检测器**（用户实测提出的两个难题）：
        //  ① 持续动态内容（Banner 轮播）让"像素稳定"永远不成立 → 旧逻辑白等满预算；
        //  ② 慢加载（进页面卡 4-5 秒）会在加载页上就执行动作 → 必然失败。
        // 新判据 = **结构签名**（节点数+角色+格位，不含文本）就绪即返回（通常
        // 300-600ms，比旧的固定 1.2s 更快）；"加载中"（ProgressBar/加载文本）则
        // 继续等（最长 8s 覆盖慢加载）。超时才走帧差兜底（动态视觉场景）。
        // ⚠️ v1.2.147 实测修正（用户观察「导航 1-2 秒但某些页面停留明显更久」）：
        // 上一版最坏路径 = 检测器 4s（结构签名在列表加载/动画/WebView 抖动页面上
        // 不收敛）+ waitForIdle 0.6s + 帧差 1.2s ≈ 5.8s 白等。
        // 关键认识：**不收敛 ≠ 不能操作**——settle 只是成功率优化，正确性由
        // 动作后的 verify 保障（那才是判据）。故预算砍到 1.5s，超时直接放行。
        val pageState = withTimeout("settle.page", 2000L) {
            // maxLoadingMs：只在看到**进度条/加载文案**时才延长到这里（用户实测
            // 慢应用会卡四五秒加载页）；空树不算强信号，不会拖长常规 settle。
            app.dsh.mobile.PageStateDetector.waitReady(svc, budgetMs = 1500L, maxLoadingMs = 4000L)
        }
        if (pageState?.phase == app.dsh.mobile.PageStateDetector.Phase.READY) return
        // 仅在"明确见过加载中"时给一次短帧差兜底（最多 800ms）；其余直接放行
        if (pageState?.loadingSeen != true) return
        val deadline = nowMs() + 800L
        val ok = withTimeout("settle.idle", 700L) {
            svc.waitForIdle(timeoutMs = 600L, quietMs = 60L, stableReads = 2)
        } ?: false
        if (ok) return
        var stable = 0
        while (nowMs() < deadline && stable < 2) {
            val r = withTimeout("settle.frame", deadline - nowMs()) {
                app.dsh.mobile.Framer.captureAndDiff(svc)
            }
            // 截图不可用时反复抓只会重复 IPC；保留上限放行，交给动作后验证判定。
            if (r == null || r.unavailable) return
            if (!r.isFirst && !r.changed) stable++ else stable = 0
            if (stable < 2) {
                val remaining = deadline - nowMs()
                if (remaining > 0L) Thread.sleep(minOf(200L, remaining))
            }
        }
    }

    /**
     * 记忆适用性检查（v1.2.126）—— 命中的计划"起点假设"是否与当前屏幕一致。
     *
     * 实测教训（极复杂场景跨 App 测试）：场景指纹是"屏幕文本集合"，同一 App 的
     * 不同页面可能相似度很高 → 命中到**不适用于当前页面**的计划（计划假设从桌面
     * 开始，实际已在 App 内某页）→ 第一步就找不到锚点、任务失败，还要靠"复用失败
     * 淘汰"白跑一次才修正。
     *
     * 规则保守（无法判断时视为适用，不阻断正常复用）：
     *  - 首步 launch：被 launch 的包**已是前台** → 起点比计划预期更靠后 → 不适用
     *  - 首步 tap（有 target.text）：**至少一个锚点**能在当前屏幕匹配到 → 适用
     *  - 其他动作（input/key/swipe/noop/collect）：无法判断 → 适用
     */
    private fun memoryApplicable(
        svc: app.dsh.mobile.DshAccessibilityService,
        plan: JSONObject,
        dump: String,
    ): Boolean {
        val steps = plan.optJSONArray("steps") ?: return false
        val first = if (steps.length() > 0) steps.optJSONObject(0) else null
        val action = first?.optJSONObject("action") ?: return true
        return when (action.optString("type")) {
            "launch" -> {
                val pkg = action.optString("package")
                val fg = svc.foregroundPackage()
                !(pkg.isNotBlank() && fg != null && fg.startsWith(pkg))
            }
            "tap" -> {
                val arr = first.optJSONObject("target")?.optJSONArray("text") ?: return true
                val anchors = (0 until arr.length()).map { arr.optString(it) }
                    .filter { it.isNotBlank() }
                anchors.isEmpty() || anchors.any { a -> FastPicker.pick(dump, a).candidate != null }
            }
            else -> true
        }
    }

    private fun anchorTexts(target: JSONObject?): List<String> {
        if (target == null) return emptyList()
        val arr = target.optJSONArray("text") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }
}

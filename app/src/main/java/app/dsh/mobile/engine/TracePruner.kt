package app.dsh.mobile.engine

/**
 * 轨迹剪枝器（M2 / F2.1–F2.3）。
 *
 * 契约唯一权威：docs/A11Y_TRACE_CONTRACT.md；设计见 docs/A11Y_MEMORY_DESIGN.md §3-B。
 *
 * ## 它解决什么问题
 * 轨迹录的是「人怎么摸索出来的」，不是「正确答案」：里面必然有误点、重复滚动、
 * 走错页面又退回来。原样重放等于把当时的犹豫再执行一遍——既慢又容易再错。
 * 这里按契约 §5 允许的**证据**把噪音删掉，只留真正推动流程的动作。
 *
 * ## 为什么是纯 Kotlin（不 import android.* / org.json）
 * org.json 来自 android.jar，在宿主 JVM 上跑起来是 "Stub!"，所以「JSON 与模型互转」的活
 * 由调用方（TraceCodec / fixture 读取器）做；本文件只吃 TraceDoc、吐 PruneResult，
 * 于是能被 scripts/run-a11y-jvm-test.sh 直接断言真值，而不是靠 grep 源码。
 *
 * ## 为什么 why / rejectReason 是英文
 * 仓库门禁 scripts/check-i18n.py 禁止 Kotlin 字符串字面量里出现 CJK（中文只允许写在注释里）：
 * 用户可见文案走 res/values 与各语言目录下的 strings.xml。剪枝理由是给模型/日志看的诊断数据，不是界面文案，
 * 但它仍然是字面量，所以统一用英文；中文说明全部留在注释里。
 *
 * ## 规则顺序是有讲究的（不是随便排的）
 * 1. reject：先判「整条能不能入库」，但仍然继续剪枝——负样本也要能看清当时做了哪些动作；
 * 2. observation：先清掉观测步，它们不改世界，留着只会干扰后面按动作链做的判断；
 * 3. detour：再删大块的绕路（A 到 B 再回 A），避免绕路里的 tap 把 retry / noop-verify 带偏；
 * 4. retry：同目标的多次尝试只留最后一次成功的那条；
 * 5. scroll-fold：连续同向 scroll 折成一条 scroll_to。必须排在 noop-verify 前面，
 *    否则「滚到头了」的那一次 unchanged scroll 会被当成无效动作单独删掉，剩下的两次
 *    就再也折不成一条了（这是实现时真踩到的坑）；
 * 6. noop-verify：最后删空转点击。判据是「后面还有动作改变了屏幕」，只有在前面几轮删完
 *    之后看剩下的动作链才准——早删会把唯一有效的后续动作一起误判。
 *
 * ## 线程安全
 * 无任何可变状态（纯函数 + 局部集合），多线程并发调用互不影响，不需要同步。
 *
 * ## 异常
 * prune 对外**不抛**：内部 runCatching 兜底，失败时返回 rejected=true。
 * 剪枝出错时宁可「不入库」也不能把半成品存成记忆——错误记忆比没有记忆危险得多。
 * 这里不用 Log.w（android.util.Log 是 Android 依赖，本文件必须能在宿主 JVM 跑），
 * 记日志由调用方根据 PruneResult.rejectReason / PruneDrop.why 做。
 */

/** 剪枝后的一步：能在**另一个屏幕状态**下重放的最小信息。 */
data class PrunedStep(
    val sourceIndex: Int,          // 原始 trace 里的 index（回溯用）
    val action: String,            // tap|tap_id|gesture|input|key|scroll|scroll_to|launch
    val target: TraceAnchor?,      // 目标元素（scroll_to 时是目标文本）
    val args: Map<String, String>,
    val expectAfter: Set<String>,  // 期望的后置指纹（空 = 不校验）
) {
    /** 目标文本；没有锚点、或锚点没有文本时是空串（重放时退化成按 viewId / 坐标定位）。 */
    fun targetText(): String = target?.text?.trim().orEmpty()
}

/** 一条删除留痕：删了什么、按哪条规则删的、为什么。 */
data class PruneDrop(val rule: String, val sourceIndex: Int, val why: String)

/** 剪枝结果。rejected 为 true 时 steps 只能当负样本看，**不得**拿去重放。 */
data class PruneResult(
    val steps: List<PrunedStep>,
    val drops: List<PruneDrop>,
    val rejected: Boolean,         // 整条记忆不可入库（保护页 / 失败任务）
    val rejectReason: String,
) {
    /** 这条记忆现在能不能进重放池：没被拒、且至少还剩一步。 */
    val replayable: Boolean get() = !rejected && steps.isNotEmpty()

    /** 按规则统计删了几条（调参、写报告时用）。 */
    fun dropCount(rule: String): Int = drops.count { it.rule == rule }

    /** 有没有触发过某条规则。 */
    fun hasRule(rule: String): Boolean = drops.any { it.rule == rule }
}

object TracePruner {

    // ---- 六条规则的 rule 取值（契约 §5，fixture README 的断言按字面值比对） ----
    const val RULE_OBSERVATION = "observation"
    const val RULE_RETRY = "retry"
    const val RULE_NOOP_VERIFY = "noop-verify"
    const val RULE_SCROLL_FOLD = "scroll-fold"
    const val RULE_DETOUR = "detour"
    const val RULE_REJECT = "reject"

    // ---- 整条记忆被拒的两种理由（契约 §5 最后两行） ----
    const val REJECT_PROTECTED = "protected page"
    const val REJECT_FAILED = "task failed"

    /** 折叠出来的动作类型（fixture README 的断言按这个字面值比对）。 */
    const val ACTION_SCROLL_TO = "scroll_to"

    /**
     * 绕路判定的最大跨度：回退动作最远隔几步。
     *
     * 为什么要上限：判据是「指纹回到了进入前的状态」，但真实任务里 A 屏→做三件事→回到 A 屏
     * 是很常见的**正当**流程（比如回到列表再点下一项）。跨度太大时删掉的不是绕路而是真实路径，
     * 剪错一次比不剪危险得多。实测绕路都是「点错了就立刻返回」，1 到 2 步内就回退。
     */
    const val MAX_DETOUR_GAP = 3

    /**
     * 把一条轨迹剪成可重放的步骤序列。
     *
     * 不抛异常：内部错误 → rejected = true、rejectReason = "prune failed"，调用方据此不入库。
     */
    fun prune(doc: TraceDoc): PruneResult = runCatching { pruneOrThrow(doc) }.getOrElse { e ->
        PruneResult(
            steps = emptyList(),
            drops = listOf(
                PruneDrop(
                    RULE_REJECT, -1,
                    "prune threw (" + e.javaClass.simpleName + ": " + e.message +
                        "): treating the whole trace as non-storable",
                ),
            ),
            rejected = true,
            rejectReason = "prune failed",
        )
    }

    // ------------------------------------------------------------------ 主流程

    private fun pruneOrThrow(doc: TraceDoc): PruneResult {
        val drops = ArrayList<PruneDrop>()
        // 按 index 稳定排序：契约允许轨迹带 replaces 行，index 才是原始顺序的唯一依据。
        val alive = doc.steps.sortedBy { it.index }.toMutableList()

        val (rejected, rejectReason) = checkRejectable(doc, alive, drops)

        dropObservations(alive, drops)
        foldDetours(alive, drops)
        foldRetries(alive, drops)
        foldScrolls(alive, drops)
        dropNoopVerifies(alive, drops)

        // 只删/折叠、绝不重排：alive 始终是原始顺序的子序列，这里不再排序（排序会掩盖重排 bug）。
        val steps = alive.map { s ->
            PrunedStep(
                sourceIndex = s.index,
                action = s.endpoint,
                target = s.anchor,
                args = s.args,
                expectAfter = s.afterFp,
            )
        }
        return PruneResult(steps, drops, rejected, rejectReason)
    }

    /**
     * 这条记忆**能不能重放**（纯函数，JVM 有断言）。空串 = 能。
     *
     * 目前只有一类不能：`loop_step`。桥把整次 `/loop/run` 记成**一步**，循环内部的
     * tap/scroll 没进轨迹（args 也是空的），所以重放时 dispatch 必然落到
     * `unsupported action: loop_step`。
     *
     * 与其存一条「命中后必然失败」的记忆 —— 每次命中都白花一次 lookup、把 misses 加一、
     * 最后被降级成 suspect —— 不如不存，并在响应里说清楚为什么。
     * 真正的修法是让 LocalLoopExecutor 把内部每一步也写进轨迹（见 A11Y_MEMORY_PLAN 的待办）。
     */
    fun unreplayable(steps: List<PrunedStep>): String {
        val bad = steps.firstOrNull { it.action == TraceEndpoint.LOOP_STEP }
        if (bad != null) {
            return "loop_step: /loop/run internal actions never reached the trace (step " +
                (bad.sourceIndex + 1) + ")"
        }
        // 重放器不认识的动作（set_switch / repeat / pinch / swipe …）：整条轨迹入库也白搭，
        // 命中一次失败一次。宁可现在拒绝，也不要留下「能召回、一跑就废」的记忆。
        val unknown = steps.firstOrNull { it.action !in REPLAYABLE }
        if (unknown != null) {
            return unknown.action + ": replay does not support this action (step " +
                (unknown.sourceIndex + 1) + ")"
        }
        // input 有重放分支，但**永远拿不到文本**（隐私红线只记 len）→ 每次都会在这一步失败并回落。
        // 与其存一条「必回落到云端」的记忆（还会让 misses++ 把它降级），不如现在拒绝。
        val input = steps.firstOrNull { it.action == TraceEndpoint.INPUT }
        if (input != null) {
            return "input: the text is never recorded (privacy), so this step cannot be replayed (step " +
                (input.sourceIndex + 1) + ")"
        }
        return ""
    }

    /**
     * 重放器**真正能执行**的动作集合，与 MemoryReplay.dispatch 的分支保持同源：
     * TraceEndpoint.ACTIONS（tap/tap_id/gesture/input/key/scroll/launch）+ noop +
     * 剪枝折叠出来的 scroll_to + loop 的 scroll_find。
     */
    val REPLAYABLE: Set<String> = TraceEndpoint.ACTIONS + TraceEndpoint.NOOP +
        ACTION_SCROLL_TO + "scroll_find"

    // ------------------------------------------- 规则 6：reject（保护页 / 失败任务）

    /**
     * 保护页优先于失败任务：保护页是**安全**边界（记忆不得绕过四层安全），
     * 失败只影响这条记忆好不好用。两者都命中时报保护页。
     *
     * 注意这里**不提前 return**：被拒的轨迹仍要剪完，负样本（state=disabled）要能看清
     * 「当时试了哪些动作、倒在哪一步」，否则下次排障还得重新录。
     */
    private fun checkRejectable(
        doc: TraceDoc,
        alive: List<TraceStep>,
        drops: MutableList<PruneDrop>,
    ): Pair<Boolean, String> {
        val protectedStep = alive.firstOrNull { it.protected }
        if (protectedStep != null) {
            drops.add(
                PruneDrop(
                    RULE_REJECT, protectedStep.index,
                    "step " + protectedStep.index + " hit a protected page (protected=true): no action on a " +
                        "protected page may enter memory, so the whole trace is rejected",
                ),
            )
            return true to REJECT_PROTECTED
        }
        if (doc.endOk == false) {
            val lastIndex = alive.lastOrNull()?.index ?: -1
            drops.add(
                PruneDrop(
                    RULE_REJECT, lastIndex,
                    "end line ok=false (reason=\"" + doc.endReason + "\"): a failed task is kept only as a " +
                        "negative sample, never as replayable steps",
                ),
            )
            return true to REJECT_FAILED
        }
        return false to ""
    }

    // ------------------------------------------------------- 规则 1：observation

    private fun dropObservations(alive: MutableList<TraceStep>, drops: MutableList<PruneDrop>) {
        val iter = alive.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            if (!s.isObservation()) continue
            iter.remove()
            drops.add(
                PruneDrop(
                    RULE_OBSERVATION, s.index,
                    "observation endpoint (" + s.endpoint + "): reads state without changing the world; " +
                        "replay re-reads the screen every step anyway, so keeping it only costs a round trip",
                ),
            )
        }
    }

    // ------------------------------------------------------------ 规则 5：detour

    /**
     * A 屏 → B 屏 → A 屏：第 i 步把屏幕从 S 带到 T，之后某一步又把屏幕送回 S
     * （afterFp 等于第 i 步的 beforeFp）。那么第 i 步与那个回退动作是纯绕路，成对删掉。
     *
     * 只用指纹做判据（契约 §5），不看动作类型：回退可能是 key=back，也可能是点左上角返回、
     * 甚至点了一个 tab 回到原页——判据统一是「屏幕状态回到了进入前」。
     */
    private fun foldDetours(alive: MutableList<TraceStep>, drops: MutableList<PruneDrop>) {
        var i = 0
        while (i < alive.size) {
            val enter = alive[i]
            val from = enter.beforeFp
            val to = enter.afterFp
            var removed = false
            if (from.isNotEmpty() && to.isNotEmpty() && from != to) {
                val limit = minOf(alive.size - 1, i + MAX_DETOUR_GAP)
                var j = i + 1
                while (j <= limit) {
                    val back = alive[j]
                    if (back.afterFp == from) {
                        drops.add(
                            PruneDrop(
                                RULE_DETOUR, enter.index,
                                "detour start: step " + enter.index + " (" + label(enter) +
                                    ") left the current page and step " + back.index +
                                    " returned to the same screen (its afterFp equals the fingerprint from " +
                                    "before the detour)",
                            ),
                        )
                        drops.add(
                            PruneDrop(
                                RULE_DETOUR, back.index,
                                "detour return: step " + back.index + " (" + label(back) +
                                    ") put the screen back to its state before step " + enter.index +
                                    "; dropped as a pair with the step above",
                            ),
                        )
                        repeat(j - i + 1) { alive.removeAt(i) }
                        removed = true
                        break
                    }
                    j++
                }
            }
            if (!removed) i++
        }
    }

    // ------------------------------------------------------------- 规则 2：retry

    /**
     * 同一 anchor.text 的多次尝试，只留最后一次**成功**的那次。
     *
     * 只剪「有证据证明失败」的前几次（verify.status 不是 ok）：同一目标点两次且两次都 ok，
     * 可能是有意的重复操作（开关拨两次），没有证据就不能当失败尝试删掉。
     * 另外，如果连最后一次都没成功，就不剪——那不是「重试后成功」，是整段没走通，要留证。
     */
    private fun foldRetries(alive: MutableList<TraceStep>, drops: MutableList<PruneDrop>) {
        val groups = LinkedHashMap<String, MutableList<Int>>()
        for ((pos, s) in alive.withIndex()) {
            val text = s.anchorText()
            if (text.isBlank()) continue    // 没有目标文本就无从判断「同一目标」
            groups.getOrPut(text) { ArrayList() }.add(pos)
        }
        val doomed = ArrayList<Int>()
        val why = HashMap<Int, String>()
        for ((text, positions) in groups) {
            if (positions.size < 2) continue
            val lastPos = positions.last()
            if (alive[lastPos].verify.status != TraceStatus.OK) continue
            for ((k, pos) in positions.withIndex()) {
                if (pos == lastPos) continue
                val st = alive[pos]
                if (st.verify.status == TraceStatus.OK) continue
                doomed.add(pos)
                why[pos] = "attempt " + (k + 1) + " failed (status=" + st.verify.status + "): target \"" +
                    text + "\" was tried " + positions.size + " times, keeping the last successful attempt"
            }
        }
        removeAt(alive, doomed, RULE_RETRY, why, drops)
    }

    // -------------------------------------------------------- 规则 4：scroll-fold

    /**
     * 连续同向 scroll 折叠成一条 scroll_to。
     *
     * 目标取「折叠区间之后第一个 tap 的 anchor.text」——这正是当初滚动的**意图**：
     * 滚是为了点到那个元素。没有后续 tap 时退回区间最后一步的锚点文本；再没有就退化成
     * 一条普通 scroll（保留动作，不臆造目标）。
     */
    private fun foldScrolls(alive: MutableList<TraceStep>, drops: MutableList<PruneDrop>) {
        var i = 0
        while (i < alive.size) {
            if (alive[i].endpoint != TraceEndpoint.SCROLL) {
                i++
                continue
            }
            val dir = alive[i].args["direction"].orEmpty().lowercase()
            var end = i + 1
            while (end < alive.size &&
                alive[end].endpoint == TraceEndpoint.SCROLL &&
                alive[end].args["direction"].orEmpty().lowercase() == dir
            ) {
                end++
            }
            val runLen = end - i
            if (runLen < 2) {
                i = end
                continue
            }

            val first = alive[i]
            val last = alive[end - 1]
            val targetText = firstTapTextAfter(alive, end)
                ?: last.anchorText().takeIf { it.isNotBlank() }
            val action = if (targetText != null) ACTION_SCROLL_TO else TraceEndpoint.SCROLL
            val folded = first.copy(
                endpoint = action,
                anchor = if (targetText != null) TraceAnchor(text = targetText) else first.anchor,
                // 折叠后的一步代表整段滚动：后置指纹取区间最后一步，重放时才有整段完成后的验收依据。
                afterFp = last.afterFp,
            )
            val foldTo = if (targetText != null) ACTION_SCROLL_TO + " -> \"" + targetText + "\"" else "scroll"

            for (pos in i + 1 until end) {
                val s = alive[pos]
                drops.add(
                    PruneDrop(
                        RULE_SCROLL_FOLD, s.index,
                        "same scroll direction (" + dir + ") as step " + first.index +
                            ": folded into a single " + foldTo + " (this step is folded away)",
                    ),
                )
            }
            repeat(runLen - 1) { alive.removeAt(i + 1) }
            alive[i] = folded
            i++
        }
    }

    /** 折叠区间之后第一个「能点到具体元素」的动作的锚点文本（tap / tap_id / input 都算）。 */
    private fun firstTapTextAfter(alive: List<TraceStep>, from: Int): String? {
        for (pos in from until alive.size) {
            val s = alive[pos]
            if (s.endpoint != TraceEndpoint.TAP &&
                s.endpoint != TraceEndpoint.TAP_ID &&
                s.endpoint != TraceEndpoint.INPUT
            ) {
                continue
            }
            val text = s.anchorText()
            if (text.isNotBlank()) return text
        }
        return null
    }

    // ------------------------------------------------------ 规则 3：noop-verify

    /**
     * verify.status = unchanged：动作发出去了，屏幕一点没变。如果后面还有动作把流程推下去了，
     * 那这一步就是空转（误点 / 点了没反应的控件），删掉。
     *
     * 两个刻意收紧的条件：
     * - **必须有 anchor.text**：没有目标文本就证明不了它和后续动作是「同一目标」，宁可不剪；
     * - **不碰 scroll / scroll_to**：滚动由 scroll-fold 统一处理，滚到头那次 unchanged 是
     *   「这一屏滚完了」的证据，单独删掉会把折叠关系打断（实现时真踩过：折叠出的 scroll_to
     *   继承了最后那次的 unchanged 状态，差点被这里再删一次）。
     */
    private fun dropNoopVerifies(alive: MutableList<TraceStep>, drops: MutableList<PruneDrop>) {
        val doomed = ArrayList<Int>()
        val why = HashMap<Int, String>()
        for ((pos, s) in alive.withIndex()) {
            if (s.verify.status != TraceStatus.UNCHANGED) continue
            if (s.endpoint == TraceEndpoint.SCROLL || s.endpoint == ACTION_SCROLL_TO) continue
            val text = s.anchorText()
            if (text.isBlank()) continue
            val nextChanged = alive.drop(pos + 1).firstOrNull { it.changedScreen() } ?: continue
            doomed.add(pos)
            why[pos] = "verify=unchanged: tapping \"" + text + "\" changed nothing on screen; step " +
                nextChanged.index + " is what actually moved the flow forward, so this tap is a no-op"
        }
        removeAt(alive, doomed, RULE_NOOP_VERIFY, why, drops)
    }

    // ------------------------------------------------------------------ 小工具

    /** 按**降序**删下标，保证前面的下标不因删除而错位。 */
    private fun removeAt(
        alive: MutableList<TraceStep>,
        positions: List<Int>,
        rule: String,
        why: Map<Int, String>,
        drops: MutableList<PruneDrop>,
    ) {
        for (pos in positions.sortedDescending()) {
            val s = alive.removeAt(pos)
            drops.add(PruneDrop(rule, s.index, why[pos] ?: rule))
        }
    }

    /** 留痕里给人看的步骤名称：优先锚点文本，没有就退化成端点名。 */
    private fun label(step: TraceStep): String {
        val text = step.anchorText()
        return if (text.isNotBlank()) text else step.endpoint
    }
}

package app.dsh.mobile.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import app.dsh.mobile.DshAccessibilityService
import app.dsh.mobile.PhoneTask
import app.dsh.mobile.ProtectedPages
import app.dsh.mobile.ScrollGeometry
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 记忆重放执行器（M5 · 设计文档 §3D）。
 *
 * ## 一句话
 * 拿一条 [MemoryEntry]（意图 + 结构化步骤 + 每步后置校验），在**当前屏**上逐步重放；
 * 每步动手之前先做一次锚点预校验，对不上就立刻停并把证据交回上层重规划，**绝不盲点**。
 *
 * ## 与 LocalLoopExecutor 的关系（为什么不直接调它）
 * [LocalLoopExecutor] 的入口是 run(planJson)：吃云端吐的决策程序 JSON，内部
 * performAction / runStep / verifyStep 全是 **private**，本文件拿不到。所以这里
 * **复用它的原语**而不是复制它的实现：
 * - 定位：FastPicker.pick(dump, anchor, preferClickable)（纯函数，与 loop 同一套精确/语义分层）
 * - 动作：svc.tapText / svc.tapById / svc.dispatchTapRect / svc.scrollToFind /
 *   svc.dispatchSwipeHuman / svc.inputText / svc.pressEnter / svc.performGlobalActionByName
 * - 几何：ScrollGeometry.step(w, h, direction, distance)（与 loop 的滚动行程同源）
 * - 稳定：svc.awaitSettle(...)（看无障碍事件是否安静，而不是写死 sleep）
 * 这样「loop 里怎么点，重放就怎么点」，但重放多了两样 loop 没有的东西：
 * 每步锚点预校验 + 事务级中止（叫停 / 保护页 / 换包）。
 *
 * ## 三层安全（顺序即优先级）
 * 1. **预检**（[precheck]，纯函数）：包不符 / disabled / 空步骤 / 首步锚点不在当前屏 → 一步不动。
 * 2. **逐步锚点校验**（[checkAnchor]，纯函数）：目标元素的文本在这一屏**看不到** → 停。
 * 3. **事务守卫**（[run] 里每步之前）：用户叫停 / 进入保护页 / 前台包变了 → 停。
 *
 * ## 指纹预校验为什么只看文本、不看 role / bounds
 * 设计文档 §3D 写的是「text / role / bounds 对不上 → 停」，但三者可信度差一个量级：
 * - **文本**：用户的可见事实，跨 ROM 稳定，也能被人读懂（拒绝原因可直接展示）。
 * - **role**：switch / row / button 是各 ROM、各 Compose 版本自述的，同一控件换个系统
 *   版本就变；拿它当**拦路的判据**会把好记忆大面积误杀（假阴性），而误杀在用户那里
 *   表现为「记忆又不好使了」。
 * - **bounds**：记录的是一次性屏幕坐标。页面只要滚动过、字号变过、分屏过，矩形就不再
 *   指向同一个元素 —— 用坐标放行正是不该做的那件事：**盲点**。
 * 所以 role / viewId / bounds 只用来**定位**（tap 的降级链、tapById），不当拦路判据。
 *
 * ## 时钟口径
 * deadlineAt 与 [LocalLoopExecutor.run] 的 deadlineAt **同一口径**：单调时钟
 * （System.nanoTime() / 1_000_000），不是 System.currentTimeMillis()。
 * 路由可以把同一个值透传进来；系统校时不会把等待期限拉长或缩短。
 *
 * ## 线程与超时
 * 节点树 IPC 偶发挂起（/screen 被顶成 504 的老路），所以读屏与动作都走一条专用单线程
 * 加 withTimeout 隔离 —— 与 [LocalLoopExecutor] 同一取舍：超时只丢这一次调用，
 * 不让整条重放线程永久卡死。注意 future.cancel(true) 不保证已发出的手势被撤回
 * （与 loop 的注释同义），因此**动作超时按失败处理、绝不重试**（重试才会真的点两下）。
 */
object MemoryReplay {

    private const val TAG = "MemoryReplay"

    // ------------------------------------------------------------------ 词表

    /** 单步结局（[StepOutcome.status] 的取值全集）。 */
    object Status {
        /** 执行成功（后置软校验也通过）。 */
        const val OK = "ok"
        /** 动手之前锚点不在屏幕上 —— 这一步**没有执行**。 */
        const val MISMATCH = "anchor-mismatch"
        /** 动作发出去了，但后置软校验没看到期望文本（不中止，只记账）。 */
        const val VERIFY_FAILED = "verify-failed"
        /** 观测类动作（noop / dump / screenshot / sleep），重放时无事可做。 */
        const val SKIPPED = "skipped"
        /** 想执行但执行不了（无锚点 / 不支持的动件 / 派发失败 / 守卫拦住）。 */
        const val BLOCKED = "blocked"
    }

    /** [ReplayResult.reason] 的取值（路由与断言共用，避免同一串字面量写两遍）。 */
    object Reason {
        /** 记忆的包与当前前台包不同。 */
        const val PKG_MISMATCH = "pkg-mismatch"
        /** 条目是负样本（state=disabled），永不重放。 */
        const val DISABLED = "disabled"
        /** 没有步骤可重放。 */
        const val EMPTY = "empty"
        /** 锚点不在当前屏（预检与逐步校验都报这个）。 */
        const val ANCHOR_MISMATCH = "anchor-mismatch"
        /** 当前页是受保护页面（安装器 / 权限 / 无障碍 / 开发者选项…）。 */
        const val PROTECTED = "protected-page"
        /** 前台包在执行中途变了（切走应用 / 弹了别的包）。 */
        const val PKG_CHANGED = "pkg-changed"
        /** 用户叫停（[PhoneTask.interruptReason]）。 */
        const val INTERRUPTED = "interrupted"
        /** maxSteps 或 deadlineAt 用完了。 */
        const val BUDGET = "budget"
        /** 有后置条件但屏上一条都没出现（最后一次软校验失败）。 */
        const val VERIFY_FAILED = "verify-failed"
        /** 这一步没有任何可用锚点（例如只有坐标）。 */
        const val NO_ANCHOR = "no-anchor"
        /** 动作类型不认识 / 参数不全。 */
        const val UNSUPPORTED = "unsupported"
        /** 动作发出去了但没成功（点击派发失败等）。 */
        const val ACTION_FAILED = "action-failed"
        /** 重放自身抛异常（兜底）。 */
        const val CRASH = "crash"
    }

    // ------------------------------------------------------------------ 常量

    /**
     * 子串命中的最短锚点长度。
     *
     * 口径与 [MemoryIndex] 的 anchorHit **完全一致**：先比相等（忽略大小写与首尾空白），
     * 再比「锚点是某个屏幕文本的子串，且锚点至少 2 个字符」。
     * 为什么必须一致：M4 检索时说「首步锚点还在」（加 0.1 分），M5 重放却说「锚点不在」，
     * 用户看到的就是「命中了但一跑就停」，而这种矛盾最难排查。
     * 为什么门槛是 2：单字锚点（开 / 设）在中文界面里几乎必然被子串命中，那等于没有校验
     * —— 宁可让它只能精确命中。
     */
    private const val MIN_SUBSTRING_ANCHOR = 2

    /**
     * 「把开关拨到开」的文案词（v1.7.x 幂等判定用）。
     *
     * ## v1.7.12：删掉单字「开」与 open/show/close/hide/collapse
     * 单字是**子串级**误命中源头：中文里「开」出现在「开发者选项」「开始」「打开方式」
     * 「展开全部」等海量词里，判定层只做 contains，于是「开发者选项」会被判成「用户想打开它」，
     * 回放时直接**静默跳过**那一步 —— 这正是本文件注释里声明要避免的「假成功」，
     * 而且跳过后报告还写决胜。同理 open/show 会误命中「打开方式」（picker 不是开关）。
     * 保留的都是双字以上的**动作意图词**，且不在任何常见界面名词里独立出现。
     * 代价：像「启动」「切换」这类确实表示打开的词没被收录 —— 认不出就退化成正常点击，
     * 那是安全的失败方向（多点一次），比静默跳过好。
     *
     * 刻意同时收中英文：这类词是**动作意图**而不是界面文案，属于平台事实，
     * 不该走 i18n 资源（那会让判定随语言漂移）。
     */
    private val TOGGLE_ON_WORDS = listOf(
        "打开", "开启", "启用", "turn on", "enable",
    )

    /** 「把开关拨到关」的文案词。 */
    private val TOGGLE_OFF_WORDS = listOf(
        "关闭", "停用", "禁用", "turn off", "disable",
    )

    /** 读一次屏的上限（与 loop 的 dump 同口径）。 */
    private const val DUMP_TIMEOUT_MS = 4000L

    /** 单个动作派发的上限。 */
    private const val ACT_TIMEOUT_MS = 4000L

    /**
     * 动作后等界面稳定的上限：awaitSettle 看无障碍事件是否安静，已稳定时会在 quietMs
     * 内直接返回，慢页面最多等这么久。
     *
     * 为什么必须有：不等就 dump，读到的是**旧页面** —— 既会让本步软校验误报，更会让
     * **下一步**的锚点校验拿旧屏去比 → 假 mismatch → 整条记忆被误判失效。
     * 代价（不超过 700ms）已经算在 §8 的性能预算里，比误判便宜。
     */
    private const val SETTLE_TIMEOUT_MS = 700L
    private const val SETTLE_QUIET_MS = 180L
    private const val SETTLE_IPC_MS = 1500L

    /** scroll_to 一次最多花多久（真机滚 8 次约 4 到 8 秒，别让单步吃掉整条预算）。 */
    private const val SCROLL_FIND_BUDGET_MS = 8000L

    /** 剩余预算低于这个值就别再启动手势了（半截手势比少做一步危险）。 */
    private const val MIN_ACT_BUDGET_MS = 800L

    private const val PKG_PREFIX = "pkg:"

    /** 指纹里勾选态的前缀（见 [SceneMemory.fingerprint]）。 */
    private const val CHECK_ON = "v:"
    private const val CHECK_OFF = "o:"

    // ------------------------------------------------------------------ 数据

    /**
     * 单步结局。
     *
     * @param sourceIndex 原始轨迹里的步号（剪枝后仍保留，便于对着 trace 排查）
     * @param action 这一步的动作类型（原样回传，便于路由展示）
     * @param targetText 这一步的锚点文本（空 = 无文本锚点）
     * @param status 见 [Status]；**只有 [Status.OK] 代表成功**
     * @param detail 人读证据（拒绝原因 / 派发失败原因 / 未满足的后置条件）；无内容时为空串
     * @param ms 这一步的墙钟耗时
     */
    data class StepOutcome(
        val sourceIndex: Int,
        val action: String,
        val targetText: String,
        val status: String,
        val detail: String,
        val ms: Long,
    )

    /**
     * 一次重放的结果。
     *
     * @param ok 全部步骤都 ok（无 mismatch / 无 blocked / 末步软校验通过）
     * @param memoryId 记忆 id（原样回传，路由据此记账）
     * @param steps 已处理的每一步（**含未执行的那一步**：它就是停下来的原因）
     * @param failedAt 出问题的步在 entry.steps 里的**下标（0 基）**；-1 = 没出问题。
     *   预检不过时也是 -1：一步都没开始，没有「哪一步失败了」这回事 —— 用 [fallback] 与
     *   [reason] 区分，别把 -1 当「成功」用（[ok] 才是成功判据）
     * @param fallback true = 需要回落云端重规划（锚点对不上 / 被守卫拦住 / 崩溃）
     * @param reason 见 [Reason]；预检不过时直接回传它的原始结论
     */
    data class ReplayResult(
        val ok: Boolean,
        val memoryId: String,
        val steps: List<StepOutcome>,
        val failedAt: Int,
        val fallback: Boolean,
        val reason: String,
    )

    // ------------------------------------------------------------------ IPC 隔离

    private val ipc: java.util.concurrent.ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "MemoryReplay-ipc").apply { isDaemon = true }
        }
    }

    private fun <T> withTimeout(name: String, ms: Long, block: () -> T): T? {
        if (ms <= 0L) return null
        val future = ipc.submit(Callable { block() })
        return try {
            future.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            future.cancel(true)
            // 上层请求已被取消：把中断还给调用方，不要吞掉。
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            future.cancel(true)
            Log.w(TAG, "ipc timeout/fail: " + name + " (" + e.javaClass.simpleName + ": " + e.message + ")")
            null
        }
    }

    /** 单调时钟（与 [LocalLoopExecutor.run] 的 deadlineAt 同口径）。 */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    // ================================================================== 纯函数层

    /**
     * 判断「记忆里的这一步能不能在**当前屏**上安全执行」。纯函数，可在宿主 JVM 上断言。
     *
     * 规则（只有三条，越简单越不容易被改坏）：
     * 1. step.targetText 为空 → 返回空串（没有文本锚点可校验，放行；能不能执行由 [run]
     *    按动作类型决定 —— 例如只有 viewId 的 tap 可以走 tapById，而只有坐标的 tap 必须拒绝）。
     * 2. 有锚点且当前屏看得到 → 空串（通过）。
     * 3. 有锚点但当前屏看不到 → "anchor-mismatch: <text>"。
     *
     * **绝不**因为「找不到」就退化成坐标盲点：返回 mismatch，让上层停下并回落云端。
     * 一次点歪的代价远大于一次多问模型。
     *
     * 匹配口径见 [MIN_SUBSTRING_ANCHOR]（相等优先，其次 2 字以上子串）。
     * visibleTexts 为空（读屏失败 / 空页）**不等于放行**：找不到就是找不到。
     *
     * @param currentFp 当前屏指纹（含 pkg: 元素）。刻意**不参与**判定：指纹回答的是
     *   「这一屏大概是什么」，回答不了「这个元素此刻可点」。把它当放行条件，旧记忆
     *   就能在改版后的页面上继续盲点 —— 那正是本函数要防的事。参数保留是为了三件事：
     *   与 [precheck] 的输入形状一致；[run] 用它把「当时的屏」写进证据；断言能钉住
     *   「指纹相同但锚点不在 → 照样 mismatch」这条不许改坏的语义。
     */
    fun checkAnchor(
        step: MemoryStep,
        currentFp: Set<String>,
        visibleTexts: Set<String>,
    ): String {
        val want = step.targetText.trim()
        if (want.isEmpty()) return ""
        return if (visibleContains(want, visibleTexts)) "" else Status.MISMATCH + ": " + want
    }

    /**
     * 这一步是不是「把某个开关拨到目标状态」，而当前屏**已经**是那个状态 → 该跳过（v1.7.x）。
     *
     * ## 为什么必须有
     *
     * 真机反馈（决定性）：一条记忆记的是「点『打开侧边栏』→ 点『打开侧边栏』」，
     * 而它的 expectAfter 就是录制那一刻的界面（侧栏**开着**）。于是同一条命令在
     * 不同状态下行为相反：
     *
     * · 侧栏关着 → 点「打开侧边栏」= 打开   ✅
     * · 侧栏已开 → 点「打开侧边栏」= 关闭   ❌（没有任何幂等检查）
     *
     * 这不是匹配算法的问题，是「记忆记了瞬时像素快照，却没记状态意图」。
     * 只改指纹没用 —— 必须在这一步真正执行**之前**问一句「是不是已经到位了」。
     *
     * ## 判据刻意窄
     *
     * 只在同时满足时跳过：
     * 1. 这一步有可识别的 toggle 意图（动作文本里出现开/关类词，或 targetRole=switch）；
     * 2. 该意图是**单义**的（文本只说「打开」或只说「关闭」，两者都提就别猜）；
     * 3. 当前屏上这个目标**确实可勾选**（能读到勾选态），且已经是想要的状态。
     *
     * 任一不满足就返回 false（照常执行）。宁可多点一次，也不要因为读不到状态就静默跳过 ——
     * 跳过会让「以为执行了其实没动」变成新的假成功。
     *
     * @return true = 已经处于目标状态，本步可安全跳过
     */
    fun alreadyInTargetState(step: MemoryStep, targets: List<FastPicker.Candidate>): Boolean {
        val want = desiredToggleState(step) ?: return false
        val text = step.targetText.trim()
        if (text.isEmpty()) return false
        // 找到屏上这个目标节点（相等优先，其次 2 字以上子串，与 checkAnchor 同口径）
        val hit = targets.firstOrNull { c ->
            val t = c.text.trim()
            t.isNotEmpty() && (t.equals(text, ignoreCase = true) ||
                (text.length >= MIN_SUBSTRING_ANCHOR && t.contains(text, ignoreCase = true)))
        } ?: return false
        // 读不到勾选态就不判 —— 那是「不知道」，不是「已到位」
        val nowChecked = hit.checked ?: return false
        return nowChecked == want
    }

    /**
     * 从这一步的文本里读出「它想把开关拨到哪个状态」。
     *
     * @return true=想打开、false=想关闭、null=不是 toggle 意图或意图不明确。
     *
     * 单义性检查（见 [alreadyInTargetState] 第 2 条）在这里：同时出现开与关的词，
     * 说明这句文案本身有歧义（例如「关闭后重新打开」），一律返回 null 不猜。
     */
    fun desiredToggleState(step: MemoryStep): Boolean? {
        val text = step.targetText + " " + step.targetRole
        val on = TOGGLE_ON_WORDS.any { text.contains(it, ignoreCase = true) }
        val off = TOGGLE_OFF_WORDS.any { text.contains(it, ignoreCase = true) }
        return when {
            on && !off -> true
            off && !on -> false
            else -> null
        }
    }

    /**
     * 动作之后的**软校验**（设计文档 §3D 第 2 条的自证条件）。纯函数，可断言。
     *
     * @return 空串 = 通过（或这一步没有后置条件）；否则是一句人读的「缺什么」。
     *
     * 语义细节（都来自轨迹的真实形态，不是想当然）：
     * - expectAfter 来自 [TraceStep.afterFp]，是**屏幕文本集合**，所以判定是「交集非空」
     *   而不是「全部命中」—— 页面上有 6 到 14 条文本，要求全中等于要求页面一像素不差，
     *   那不叫软校验。
     * - pkg: 元素不是文本，按前台包名比对（前台包对了就算这条满足）。
     * - v: / o: 前缀（勾选态）剥掉前缀只比文本：开关的勾选态**本来就会变**（这正是
     *   set_switch 的语义），拿它当失败依据会把「成功地把开关打开」记成失败。
     */
    fun checkAfter(step: MemoryStep, visibleTexts: Set<String>, pkg: String = ""): String {
        val wants = step.expectAfter.map { it.trim() }.filter { it.isNotEmpty() }
        if (wants.isEmpty()) return ""
        for (w in wants) if (expectSatisfied(w, visibleTexts, pkg)) return ""
        return "none of [" + wants.joinToString(", ") + "]"
    }

    /**
     * 整条记忆的可行性预检（纯函数）。**一步都没执行之前**做的判断，代价只有一次读屏。
     *
     * 顺序即优先级（断言钉住了先后，别换）：
     * 1. entry.pkg != pkg → "pkg-mismatch"（不同 App 的同名页面不是同一件事；
     *    前台包读不到时 pkg 为空串，同样判不符 —— 宁可不动，也不要在盲环境里点）。
     *    **例外**：首步就是「启动 entry.pkg 这个 App」时放行 —— 那一步的全部作用就是换包，
     *    从桌面/别的 App 起手是这类记忆的正常入口（见 [launchesIntoTarget]）。
     * 2. entry.state == disabled → "disabled"（负样本，只作「别再这么干」的素材）
     * 3. entry.steps.isEmpty() → "empty"
     * 4. 首步有锚点但当前屏看不到 → "anchor-mismatch"
     * 5. 否则空串（通过）
     *
     * 注意这里**不判保护页**：保护页要看当前页标题，属于执行期守卫（[run] 里每步之前都判，
     * 第一步之前也判）。放在这里会让一次读屏变成一次读屏加一次标题解析，而且 precheck 的
     * 输入里没有标题候选，硬判只能拿整屏文本做子串匹配，误拦率更高。
     */
    fun precheck(
        entry: MemoryEntry,
        currentFp: Set<String>,
        visibleTexts: Set<String>,
        pkg: String,
    ): String {
        if (entry.pkg != pkg && !launchesIntoTarget(entry)) return Reason.PKG_MISMATCH
        if (entry.state == MemoryContract.STATE_DISABLED) return Reason.DISABLED
        if (entry.steps.isEmpty()) return Reason.EMPTY
        if (checkAnchor(entry.steps.first(), currentFp, visibleTexts).isNotEmpty()) {
            return Reason.ANCHOR_MISMATCH
        }
        return ""
    }

    /**
     * 首步是不是「启动 entry.pkg 这个 App」。
     *
     * 为什么需要：任务从桌面起手（`launch com.android.settings` → 点显示 → 点深色主题）时，
     * 重放往往发生在**另一个前台 App**里。如果这里硬按「包名必须相等」拒掉，
     * 这类「先开 App 再操作」的记忆就永远只能在目标 App 里启动 —— 等于丢掉了它最有价值的那一半。
     *
     * 放行的前提很硬：首步 action 必须是 launch，且它要启动的包**正好是** entry.pkg。
     * 只是「有 launch 步」不够，否则一条记忆能在任意 App 里起跑、盲点第一屏。
     */
    private fun launchesIntoTarget(entry: MemoryEntry): Boolean {
        val s = entry.steps.firstOrNull() ?: return false
        if (!s.action.equals(TraceEndpoint.LAUNCH, ignoreCase = true)) return false
        val to = s.args["package"].orEmpty()
        return to.isNotEmpty() && to == entry.pkg
    }

    // ================================================================== 执行层

    /**
     * 在真机上重放一条记忆。
     *
     * 流程（与任务卡 F5.1 到 F5.4 一一对应）：
     * 1. 读一次屏 → [precheck]；不过就**一步都不动**，fallback=true、reason = 预检结论。
     * 2. 逐步：守卫（预算 → 叫停 → 保护页 → 换包）→ [checkAnchor] → 派发 → 等稳定 → 读屏 →
     *    [checkAfter] 软校验。锚点 mismatch **不执行这一步**，failedAt = 该步下标。
     * 3. 软校验失败只记账（verify-failed），不立刻中止：下一步的锚点校验会兜住；但如果
     *    **最后一步**软校验失败，就没有下一步来兜了 → 按失败收口（fallback=true）。
     * 4. 全程不抛：任何异常 → ok=false、fallback=true、reason 以 "crash: " 开头。
     *
     * 三件事任一件发生就立刻停（停在**两步之间**，绝不在手势中间收手）：
     * 用户叫停 / 进入保护页 / 前台包变了。三者都回落云端（任务卡 F5.4）；唯一例外是
     * 线程被上层取消（InterruptedException）：那是调用方自己不要了，fallback=false，
     * 否则会给一个已经放弃的请求再买一次云端规划。
     *
     * @param maxSteps 最多处理多少步（含 skipped）；小于等于 0 立刻按预算退出
     * @param deadlineAt 单调时钟截止点；Long.MAX_VALUE = 不限
     */
    fun run(
        ctx: Context,
        svc: DshAccessibilityService,
        entry: MemoryEntry,
        maxSteps: Int = 40,
        deadlineAt: Long = Long.MAX_VALUE,
    ): ReplayResult {
        val outcomes = ArrayList<StepOutcome>()
        var failedAt = -1

        fun done(ok: Boolean, fallback: Boolean, reason: String): ReplayResult {
            if (!ok) {
                Log.i(
                    TAG,
                    "replay " + entry.id + " stopped: " + reason + " failedAt=" + failedAt,
                )
            }
            return ReplayResult(ok, entry.id, outcomes.toList(), failedAt, fallback, reason)
        }

        try {
            if (maxSteps <= 0) return done(false, false, Reason.BUDGET)

            val startPkg = foregroundPkg(svc)
            var screen = readScreen(svc, startPkg)
            val pkg = startPkg.ifBlank { pkgOfFp(screen.fp) }

            val pre = precheck(entry, screen.fp, screen.texts, pkg)
            if (pre.isNotEmpty()) return done(false, true, pre)

            var executed = 0
            var lastVerifyNote = ""
            var lastVerifyAt = -1
            // 期望的前台包名。首步 launch 会把前台换成目标 App，之后各步的守卫必须跟到新包，
            // 否则「从桌面起手」的记忆在第一步之后就必然 PKG_CHANGED 中止（实测踩过）。
            var expectedPkg = entry.pkg

            for (i in entry.steps.indices) {
                val step = entry.steps[i]

                // ---- 预算：停在两步之间（半截手势比少做一步危险）----
                if (executed >= maxSteps || nowMs() >= deadlineAt) {
                    failedAt = i
                    return done(false, false, Reason.BUDGET)
                }

                // ---- 守卫一：用户叫停 ----
                val brake = PhoneTask.shared().interruptReason()
                if (brake != null) {
                    failedAt = i
                    outcomes.add(blocked(step, brake, 0L))
                    return done(false, true, Reason.INTERRUPTED)
                }

                // ---- 守卫二：保护页（安装器 / 权限 / 无障碍 / 开发者选项…）----
                val cur = foregroundPkg(svc)
                // 判据与桥的保护页闸门**同源**：只看标题区（titleBlob），不是整屏文本。
                // 用整屏文本会误伤：设置首页里「Security」「Privacy」都是普通列表行，
                // 整屏匹配就把正常页面当成保护页 —— 实测「打开设置并滚动」的记忆在
                // key back 那一步被判 protected-page，整条记忆一步都跑不完。
                // 整包保护（安装器/权限控制器…）不受影响：那条规则只看包名。
                val titles = runCatching { svc.titleBlob() }.getOrDefault("")
                if (ProtectedPages.isProtectedFast(cur.ifBlank { pkg }, titles)) {
                    failedAt = i
                    outcomes.add(blocked(step, "protected page: " + cur, 0L))
                    return done(false, true, Reason.PROTECTED)
                }

                // ---- 守卫三：前台包变了（launch 这一步例外：它就是去换包的）----
                if (
                    !step.action.equals(TraceEndpoint.LAUNCH, ignoreCase = true) &&
                    cur.isNotEmpty() && cur != expectedPkg
                ) {
                    failedAt = i
                    outcomes.add(blocked(step, "foreground changed: " + expectedPkg + " -> " + cur, 0L))
                    return done(false, true, Reason.PKG_CHANGED)
                }

                // ---- 锚点预校验：对不上就停，且**不执行这一步** ----
                val anchorWhy = checkAnchor(step, screen.fp, screen.texts)
                if (anchorWhy.isNotEmpty()) {
                    failedAt = i
                    outcomes.add(
                        StepOutcome(
                            step.sourceIndex,
                            step.action,
                            step.targetText,
                            Status.MISMATCH,
                            anchorWhy,
                            0L,
                        ),
                    )
                    return done(false, true, Reason.ANCHOR_MISMATCH)
                }

                // ---- 幂等守卫：已经是目标状态的 toggle 不重复拨（v1.7.x）----
                //
                // 真机事故：记忆记「点打开侧边栏」两步，回放时侧栏已开 → 第一个 tap
                // 反而把它**关掉**，行为与意图相反。判据窄到只认「单义的开关意图 +
                // 能读到勾选态 + 确实已是目标态」，任一不满足就照常执行（宁可多点一次，
                // 也不要因为读不到状态就静默跳过 —— 那会制造新的假成功）。
                if (step.action.equals(TraceEndpoint.TAP, ignoreCase = true) &&
                    alreadyInTargetState(step, screen.candidates)
                ) {
                    outcomes.add(
                        StepOutcome(
                            step.sourceIndex,
                            step.action,
                            step.targetText,
                            Status.SKIPPED,
                            "already in target state",
                            0L,
                        ),
                    )
                    continue
                }

                // ---- 执行 ----
                val t0 = nowMs()
                val act = execute(ctx, svc, step, screen.dump, deadlineAt)
                val ms = nowMs() - t0
                executed++

                if (act.skipped) {
                    outcomes.add(
                        StepOutcome(
                            step.sourceIndex,
                            step.action,
                            step.targetText,
                            Status.SKIPPED,
                            act.detail,
                            ms,
                        ),
                    )
                    continue
                }
                if (!act.ok) {
                    failedAt = i
                    outcomes.add(blocked(step, act.detail, ms))
                    return done(false, true, act.reason)
                }

                // ---- 等稳定 + 重读一次屏：既给本步软校验，也作为下一步的「当前屏」----
                withTimeout("settle", SETTLE_IPC_MS) {
                    svc.awaitSettle(SETTLE_QUIET_MS, SETTLE_TIMEOUT_MS, 60L)
                }
                val curPkg = foregroundPkg(svc)
                screen = readScreen(svc, curPkg)
                // launch 成功换包后，期望包名跟着走（空包名的 launch 只开链接/取默认入口，不换期望）
                if (step.action.equals(TraceEndpoint.LAUNCH, ignoreCase = true)) {
                    val to = step.args["package"].orEmpty()
                    if (to.isNotBlank()) expectedPkg = to
                }

                val note = checkAfter(step, screen.texts, curPkg)
                if (note.isEmpty()) {
                    outcomes.add(
                        StepOutcome(
                            step.sourceIndex,
                            step.action,
                            step.targetText,
                            Status.OK,
                            act.detail,
                            ms,
                        ),
                    )
                } else {
                    // 软校验失败只记账：下一步的锚点校验会兜住（它比「期望文本」更硬）
                    lastVerifyNote = note
                    lastVerifyAt = i
                    outcomes.add(
                        StepOutcome(
                            step.sourceIndex,
                            step.action,
                            step.targetText,
                            Status.VERIFY_FAILED,
                            note,
                            ms,
                        ),
                    )
                }
            }

            // ---- 收口 ----
            // 只有**最后一步真实动作**的软校验失败会让这笔账不可信（它后面没有硬判据兜底了）。
            // 末尾的观测步（skipped）不产生新证据，不能拿它把一次失败"洗掉"。
            val lastReal = outcomes.lastOrNull { it.status != Status.SKIPPED }
            if (lastVerifyAt >= 0 && lastReal != null && lastReal.status == Status.VERIFY_FAILED) {
                failedAt = lastVerifyAt
                return done(false, true, Reason.VERIFY_FAILED + ": " + lastVerifyNote)
            }
            return done(true, false, "")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (failedAt < 0) failedAt = outcomes.size
            return done(false, false, Reason.INTERRUPTED + ": thread")
        } catch (e: Exception) {
            if (failedAt < 0) failedAt = outcomes.size
            Log.w(TAG, "replay crashed: " + e.javaClass.simpleName + ": " + e.message)
            return done(false, true, Reason.CRASH + ": " + e.message)
        }
    }

    // ------------------------------------------------------------------ 单步派发

    /** 一次派发的结果。skipped = 不需要真动作（观测类）。 */
    private class Act(
        val ok: Boolean,
        val reason: String = "",
        val detail: String = "",
        val skipped: Boolean = false,
    )

    private fun blocked(step: MemoryStep, detail: String, ms: Long): StepOutcome =
        StepOutcome(step.sourceIndex, step.action, step.targetText, Status.BLOCKED, detail, ms)

    /**
     * 按 [MemoryStep.action] 派发。动作词表与轨迹契约 §2.2 同一套
     * （tap / tap_id / gesture / input / key / scroll / scroll_to / launch），
     * scroll_to 是剪枝器把连续同向滚动折叠出来的那一条（[TracePruner.ACTION_SCROLL_TO]）。
     *
     * 不认识的动件**不猜**：直接 blocked 加回落，让云端重规划 —— 猜错代价是点歪。
     */
    private fun execute(
        ctx: Context,
        svc: DshAccessibilityService,
        step: MemoryStep,
        dump: String,
        deadlineAt: Long,
    ): Act {
        val action = step.action.trim().lowercase()
        return when (action) {
            TraceEndpoint.TAP -> tap(svc, dump, step)
            TraceEndpoint.TAP_ID -> tapById(svc, dump, step)
            // v1 迁移过来的 tap_text：只走锚点文本原生匹配，没有 PICK 的语义层
            "tap_text" -> tapTextOnly(svc, step)
            TraceEndpoint.INPUT -> input(svc, step)
            TraceEndpoint.KEY -> key(svc, step)
            TraceEndpoint.SCROLL -> scroll(svc, step)
            // v1 迁移过来的 swipe（dist / dir 语义与 loop 一致）
            "swipe" -> swipe(svc, step)
            TracePruner.ACTION_SCROLL_TO, "scroll_find" -> scrollTo(svc, step, deadlineAt)
            TraceEndpoint.LAUNCH -> launch(ctx, step)
            TraceEndpoint.GESTURE -> gesture(svc, step)
            TraceEndpoint.NOOP, "sleep", "screen", "dump", "screenshot" ->
                Act(true, skipped = true, detail = "observation")
            else -> Act(false, Reason.UNSUPPORTED, "unsupported action: " + action)
        }
    }

    /**
     * 点击的降级链（与 loop 的判别式重试同源，但每一步都有证据）：
     * 1. viewId → svc.tapById（节点身份最硬，改版后最不容易错）
     * 2. 锚点文本 → svc.tapText（无障碍原生精确匹配）
     * 3. FastPicker.pick 定位 + dispatchTapRect（本地语义兜底）
     * 全失败就 blocked。
     *
     * **只有坐标的锚点直接拒绝**：那正是「盲点」。锚点校验只能证明文本在屏上，
     * 证明不了记录的矩形此刻还指着它 —— 页面滚一屏就够了。
     */
    private fun tap(svc: DshAccessibilityService, dump: String, step: MemoryStep): Act {
        val text = step.targetText.trim()
        val rid = step.targetViewId.trim()
        if (rid.isNotEmpty()) {
            val hit = withTimeout("tapById", ACT_TIMEOUT_MS) { svc.tapById(rid) }
            if (hit != null) return Act(true, detail = "via viewId " + rid)
        }
        if (text.isEmpty()) {
            if (rid.isEmpty()) {
                return Act(false, Reason.NO_ANCHOR, "coords-only anchor refused (never tap blind)")
            }
            return Act(false, Reason.ACTION_FAILED, "tapById failed: " + rid)
        }
        val native = withTimeout("tapText", ACT_TIMEOUT_MS) { svc.tapText(text) } ?: false
        if (native) return Act(true, detail = "via tapText")

        val pick = FastPicker.pick(dump, text, false)
        val c = pick.candidate
        if (c != null) {
            val tapped = withTimeout("tapRect", ACT_TIMEOUT_MS) {
                svc.dispatchTapRect(android.graphics.Rect(c.x, c.y, c.x, c.y))
            } ?: false
            if (tapped) return Act(true, detail = "via picker " + pick.via + " '" + c.text + "'")
        }
        return Act(false, Reason.ACTION_FAILED, "tap failed: '" + text + "' not tappable")
    }

    private fun tapById(svc: DshAccessibilityService, dump: String, step: MemoryStep): Act {
        val rid = step.targetViewId.trim()
        if (rid.isNotEmpty()) {
            val hit = withTimeout("tapById", ACT_TIMEOUT_MS) { svc.tapById(rid) }
            if (hit != null) return Act(true, detail = "via viewId " + rid)
        }
        // viewId 对不上（改版后最常见的失效方式）时退回文本锚点，仍按 tap 的证据链走
        if (step.targetText.isNotBlank()) return tap(svc, dump, step)
        return Act(false, Reason.NO_ANCHOR, "tap_id needs a viewId")
    }

    /** 文字输入。**不记录输入的文本**（可能是密码 / 地址 / 昵称），只记成败。 */
    private fun input(svc: DshAccessibilityService, step: MemoryStep): Act {
        val text = step.args["text"] ?: step.args["value"] ?: ""
        if (text.isEmpty()) {
            // 轨迹按隐私红线**不记输入内容**（只记 len）。所以走到这里不是缺陷，是设计：
            // 明确告诉上层「这一步没有可重放的内容」，让它回落云端重新规划，
            // 而不是盲打一段空文本（那会把输入框里已有的内容搞乱）。
            val len = step.args["len"].orEmpty()
            val why = if (len.isNotEmpty()) "input text not recorded (privacy, len=" + len + ")"
            else "input without text"
            return Act(false, Reason.ACTION_FAILED, why)
        }
        val append = step.args["append"].equals("true", ignoreCase = true)
        val target = step.targetText.trim().ifBlank { null }
        val ok = withTimeout("input", ACT_TIMEOUT_MS) { svc.inputText(text, append, target) } ?: false
        return if (ok) {
            Act(true, detail = "input ok")
        } else {
            Act(false, Reason.ACTION_FAILED, "input failed (no editable field)")
        }
    }

    private fun key(svc: DshAccessibilityService, step: MemoryStep): Act {
        val name = step.args["name"].orEmpty().ifBlank { "back" }
        val ok = if (name.equals("enter", ignoreCase = true)) {
            withTimeout("enter", ACT_TIMEOUT_MS) { svc.pressEnter() } ?: false
        } else {
            withTimeout("key", ACT_TIMEOUT_MS) { svc.performGlobalActionByName(name) } ?: false
        }
        return if (ok) {
            Act(true, detail = "key " + name)
        } else {
            Act(false, Reason.ACTION_FAILED, "key failed: " + name)
        }
    }

    /** v1 迁移过来的 tap_text：只做锚点文本的原生精确点击。 */
    private fun tapTextOnly(svc: DshAccessibilityService, step: MemoryStep): Act {
        val text = step.args["text"].orEmpty().trim().ifBlank { step.targetText.trim() }
        if (text.isEmpty()) return Act(false, Reason.NO_ANCHOR, "tap_text needs text")
        val ok = withTimeout("tapText", ACT_TIMEOUT_MS) { svc.tapText(text) } ?: false
        return if (ok) {
            Act(true, detail = "via tapText")
        } else {
            Act(false, Reason.ACTION_FAILED, "tapText failed: '" + text + "'")
        }
    }

    /**
     * v1 迁移过来的 swipe：dist 是屏高/宽的比例（0.1 到 0.9），dir 是手势方向。
     * 几何与 [LocalLoopExecutor] 的同名分支逐行一致 —— 同一份记忆在两条路上画出的手势
     * 必须一样，否则"录的时候这样滑、重放的时候那样滑"会变成查不出的时序差异。
     */
    private fun swipe(svc: DshAccessibilityService, step: MemoryStep): Act {
        val dist = (step.args["dist"]?.toDoubleOrNull() ?: 0.5).coerceIn(0.1, 0.9)
        val dir = step.args["dir"].orEmpty().ifBlank { "up" }
        val human = !step.args["human"].equals("false", ignoreCase = true)
        val dur = step.args["durationMs"]?.toLongOrNull() ?: 0L
        val (w, h) = withTimeout("size", 2000L) { svc.screenSize() } ?: (1080 to 1920)
        val cx = w / 2f
        val cy = h / 2f
        val dx = (w * dist / 2).toFloat()
        val dy = (h * dist / 2).toFloat()
        var x1 = cx - dx
        var y1 = cy
        var x2 = cx + dx
        var y2 = cy
        when (dir) {
            "up" -> {
                x1 = cx; y1 = cy + dy; x2 = cx; y2 = cy - dy
            }
            "down" -> {
                x1 = cx; y1 = cy - dy; x2 = cx; y2 = cy + dy
            }
            "left" -> {
                x1 = cx + dx; y1 = cy; x2 = cx - dx; y2 = cy
            }
        }
        val ok = withTimeout("swipe", ACT_TIMEOUT_MS) {
            if (human) {
                svc.dispatchSwipeHuman(x1, y1, x2, y2, dur)
            } else {
                svc.dispatchSwipe(x1, y1, x2, y2, dur)
            }
        } ?: false
        return if (ok) {
            Act(true, detail = "swipe " + dir)
        } else {
            Act(false, Reason.ACTION_FAILED, "swipe dispatch failed")
        }
    }

    /** 与原 loop 同源：几何交给 [ScrollGeometry]，手势走拟人化 swipe。 */
    private fun scroll(svc: DshAccessibilityService, step: MemoryStep): Act {
        val dir = step.args["direction"].orEmpty().ifBlank { "down" }
        val px = step.args["distance"]?.toIntOrNull() ?: 0
        val (w, h) = withTimeout("size", 2000L) { svc.screenSize() } ?: (1080 to 1920)
        val geo = ScrollGeometry.step(w, h, dir, px)
        val ok = withTimeout("scroll", ACT_TIMEOUT_MS) {
            svc.dispatchSwipeHuman(geo.x, geo.y1, geo.x, geo.y2, 0L)
        } ?: false
        return if (ok) {
            Act(true, detail = "scroll " + dir)
        } else {
            Act(false, Reason.ACTION_FAILED, "scroll dispatch failed")
        }
    }

    /**
     * 滚动直到目标出现（剪枝器折叠出来的 scroll_to）。
     *
     * 注意它**不点**：折叠规则取的是「折叠区间之后第一个 tap 的锚点文本」，真正的点击是
     * 紧跟其后的那一步。这里多点一下等于把用户的动作做两遍。
     *
     * 正反方向各试一次（内容也可能在上面）：这就是失败策略里「多锚点退化」在滚动上的形态。
     */
    private fun scrollTo(svc: DshAccessibilityService, step: MemoryStep, deadlineAt: Long): Act {
        val want = step.targetText.trim()
        if (want.isEmpty()) return Act(false, Reason.NO_ANCHOR, "scroll_to needs target text")
        val forward = !step.args["direction"].orEmpty().equals("up", ignoreCase = true)
        val left = if (deadlineAt == Long.MAX_VALUE) SCROLL_FIND_BUDGET_MS else deadlineAt - nowMs()
        if (left < MIN_ACT_BUDGET_MS) return Act(false, Reason.BUDGET, "scroll_to skipped: budget")
        val budget = minOf(left, SCROLL_FIND_BUDGET_MS)

        var rect = withTimeout("scrollToFind", budget) { svc.scrollToFind(want, 8, forward) }
        if (rect == null) {
            val left2 = if (deadlineAt == Long.MAX_VALUE) budget else deadlineAt - nowMs()
            if (left2 >= MIN_ACT_BUDGET_MS) {
                rect = withTimeout("scrollToFind", minOf(left2, budget)) {
                    svc.scrollToFind(want, 4, !forward)
                }
            }
        }
        return if (rect != null) {
            Act(true, detail = "scroll_to '" + want + "' found")
        } else {
            Act(false, Reason.ACTION_FAILED, "scroll_to failed: '" + want + "' not found")
        }
    }

    /** 启动应用 / 打开链接（与原 loop 的 launch 同实现：Intent 加 NEW_TASK）。 */
    private fun launch(ctx: Context, step: MemoryStep): Act {
        val pkg = step.args["package"].orEmpty()
        val data = step.args["data"].orEmpty()
        if (pkg.isBlank() && data.isBlank()) {
            return Act(false, Reason.ACTION_FAILED, "launch needs package or data")
        }
        val ok = runCatching {
            val intent = if (data.isNotBlank()) {
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(data))
            } else {
                ctx.packageManager.getLaunchIntentForPackage(pkg)
            }
            if (intent == null) {
                false
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                true
            }
        }.getOrDefault(false)
        return if (ok) {
            Act(true, detail = "launched " + pkg.ifBlank { data })
        } else {
            Act(false, Reason.ACTION_FAILED, "launch failed: " + pkg + data)
        }
    }

    /**
     * 自由手势。轨迹里的 gesture 目前**不带坐标**（args 还是空的，见 G1 报告第 6 节），
     * 所以真机上一定会落到 blocked —— 这是如实反映，不是缺陷：没有坐标就画不出手势，
     * 而自己编一个坐标正是盲点。等录制器把 args.x1 / y1 / x2 / y2 补上就自动可用。
     */
    private fun gesture(svc: DshAccessibilityService, step: MemoryStep): Act {
        val a = step.args
        val x1 = a["x1"]?.toFloatOrNull()
        val y1 = a["y1"]?.toFloatOrNull()
        val x2 = a["x2"]?.toFloatOrNull()
        val y2 = a["y2"]?.toFloatOrNull()
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return Act(false, Reason.UNSUPPORTED, "gesture without coordinates (args empty)")
        }
        val dur = a["durationMs"]?.toLongOrNull() ?: 0L
        val ok = withTimeout("gesture", ACT_TIMEOUT_MS) {
            svc.dispatchSwipeHuman(x1, y1, x2, y2, dur)
        } ?: false
        return if (ok) {
            Act(true, detail = "gesture")
        } else {
            Act(false, Reason.ACTION_FAILED, "gesture dispatch failed")
        }
    }

    // ------------------------------------------------------------------ 读屏

    /**
     * 前台包名。超时/读不到一律当空串：它只用来**拦**（包不符就不动），
     * 所以"不知道"必须走安全那一侧，不能默认放行。
     */
    private fun foregroundPkg(svc: DshAccessibilityService): String =
        withTimeout("foreground", 1500L) { svc.foregroundPackage() }?.orEmpty() ?: ""

    /** 一屏的快照：dump 原文（定位用）、可见文本集合（锚点校验用）、指纹（证据用）。 */
    private class Screen(
        val dump: String,
        val texts: Set<String>,
        val fp: Set<String>,
        /** 原始候选节点：锚点幂等判定要读 checked 态，texts 里没有这个信息。 */
        val candidates: List<FastPicker.Candidate> = emptyList(),
    )

    private fun readScreen(svc: DshAccessibilityService, pkg: String): Screen {
        val dump = withTimeout("dump", DUMP_TIMEOUT_MS) { svc.dumpScreenCompact("all") } ?: ""
        val cands = runCatching { FastPicker.parseCompact(dump) }.getOrDefault(emptyList())
        return Screen(dump, textsOf(dump), SceneMemory.fingerprint(dump, pkg), cands)
    }

    /**
     * 可见文本集合 = [FastPicker.parseCompact] 解析出的文本（去掉 d: / p: 前缀）。
     *
     * 为什么与 [SceneMemory.fingerprint] 用同一个解析器：锚点校验和 M4 检索的「首步锚点
     * 还在不在」必须对同一份文本做判断，否则会出现「检索说在、重放说不在」。
     * 前缀剥离的理由：desc-only 节点（图标按钮）在紧凑 dump 里是 d:通讯录，而记忆里存的
     * 锚点就是「通讯录」—— 不剥前缀会把这类锚点全判成 mismatch。
     */
    private fun textsOf(dump: String): Set<String> {
        if (dump.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for (c in FastPicker.parseCompact(dump)) {
            val t = c.text.trim().removePrefix("d:").removePrefix("p:").trim()
            if (t.isNotEmpty() && t != "-") out.add(t)
        }
        return out
    }

    /** 指纹里的前台包名（[SceneMemory.fingerprint] 会加 pkg: 前缀）；没有返回空串。 */
    private fun pkgOfFp(fp: Set<String>): String {
        for (t in fp) {
            if (!t.startsWith(PKG_PREFIX)) continue
            val name = t.substring(PKG_PREFIX.length).trim()
            if (name.isNotEmpty()) return name
        }
        return ""
    }

    /**
     * 屏幕上有没有这段文字（锚点与后置校验共用一套口径，见 [MIN_SUBSTRING_ANCHOR]）。
     * 大小写与首尾空白的差异不算不同 —— 系统语言、输入法、ROM 都会在这上面飘。
     */
    private fun visibleContains(want: String, visible: Set<String>): Boolean {
        val w = want.trim()
        if (w.isEmpty()) return false
        for (v in visible) {
            val t = v.trim()
            if (t.isEmpty()) continue
            if (t.equals(w, ignoreCase = true)) return true
            if (w.length >= MIN_SUBSTRING_ANCHOR && t.contains(w, ignoreCase = true)) return true
        }
        return false
    }

    /** 一条后置条件是否满足（pkg: / v: / o: 三种前缀各有各的比法）。 */
    private fun expectSatisfied(raw: String, visible: Set<String>, pkg: String): Boolean {
        val w = raw.trim()
        if (w.isEmpty()) return false
        if (w.startsWith(PKG_PREFIX)) {
            val want = w.substring(PKG_PREFIX.length).trim()
            return want.isNotEmpty() && want == pkg
        }
        val text = when {
            w.startsWith(CHECK_ON) -> w.substring(CHECK_ON.length).trim()
            w.startsWith(CHECK_OFF) -> w.substring(CHECK_OFF.length).trim()
            else -> w
        }
        return visibleContains(text, visible)
    }
}

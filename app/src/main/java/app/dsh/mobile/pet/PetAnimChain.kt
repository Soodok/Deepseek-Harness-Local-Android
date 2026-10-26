package app.dsh.mobile.pet

import android.util.Log
import kotlin.random.Random

/**
 * 动画链驱动：桌面版 dsh-pet `client/pet.ts` 里 `pickNext` + `handleEnded` 的 Kotlin 移植。
 *
 * 之前 App 侧没有这条链 —— 空闲时恒播第一段 idle，一次性动画播完只是清空画面，
 * categories 里的随机动作从未被点到，桌宠看起来是"死的"。这里把灵感电流补上：
 *
 * ```
 *   一段播完 (PetView.onAnimationEnd)
 *        │
 *        ├─ 工作状态在身且非终态 → 轮换/续播工作动画（不被随机链抢走）
 *        ├─ 终态刚播完          → 收回随机链
 *        ├─ 交互插播刚播完      → 先尝试恢复工作动画，否则回随机链
 *        └─ 随机链按权重掷骰    → idle / turn / move / 分类随机动作
 * ```
 *
 * 关键的几处上游语义（改坏就会"卡在工作动画回不去"或"不停重复同一段"）：
 *  - 终态槽位（4成功/5出错）播一遍就走；非终态多候选循环轮换、单候选常驻循环；
 *  - 选动画一律避开正在播的那一段；
 *  - turn 动画播完立刻翻转朝向，且**立即同步**给后续选取（noMirror 过滤要用）；
 *  - noMirror 分类在已镜像时不参与随机链。
 */
class PetAnimChain(
    private val packProvider: () -> PetPackConfig?,
    private val onPlay: (slug: String?, loop: Boolean) -> Unit,
    private val onMirror: (mirrored: Boolean) -> Unit,
    /** 请求位移：返回 false 表示这一步走不了（会被降级成随机动作）。 */
    private val onMoveRequest: (action: PetMoveAction, durationMs: Long) -> Boolean,
) {
    /** 工作状态六个档位；终态播一遍即可，非终态要常驻。 */
    enum class WorkSlot(val terminal: Boolean) {
        THINKING(false),
        WORKING(false),
        RESULT(false),
        WAITING(false),
        SUCCESS(true),
        ERROR(true),
        ;

        companion object {
            fun byIndex(i: Int): WorkSlot? = entries.getOrNull(i)
        }
    }

    /** 当前「主要」动画的类型，决定播完往哪走。 */
    private enum class Mode { RANDOM, WORK, ONCE }

    private var mode: Mode = Mode.RANDOM
    private var currentSlug: String? = null
    private var currentLoop = true
    private var mirrored = false
    private var workSlot: WorkSlot? = null
    private var started = false

    /** 是否正在播一次性动画（留给漫游/贴边让路用）。 */
    val isBusy: Boolean
        get() = started && !currentLoop

    val currentAnim: String? get() = currentSlug

    val isMirrored: Boolean get() = mirrored

    fun start() {
        if (started) return
        started = true
        advance()
    }

    fun stop() {
        started = false
        currentSlug = null
        workSlot = null
        mode = Mode.RANDOM
    }

    /** 包换了：重新接线。 */
    fun retarget() {
        currentSlug = null
        workSlot = null
        mode = Mode.RANDOM
        if (started) advance()
    }

    // ---------------------------------------------------------------- 外部输入

    /**
     * 设定工作状态档位：null 表示回到空闲（让当前工作动画播完 naturally 走回随机链）。
     */
    fun setWorkStatus(slot: WorkSlot?) {
        if (workSlot == slot) return
        workSlot = slot
        if (!started) return
        if (slot != null) {
            val cfg = packProvider() ?: return
            val name = cfg.workSlotPrimary(slot.ordinal)
            // 终态：播一遍就走；非终态多候选：播一遍后轮换；单候选：常驻循环。
            val rotating = !slot.terminal && cfg.workSlotCandidates(slot.ordinal).size > 1
            mode = Mode.WORK
            playInternal(name, once = slot.terminal || rotating)
        } else {
            // 回空闲：不硬切，只把正在循环播的工作动画改成"播完即停"，
            // 它结束后自然走 notifyEnded → 回随机链。
            val cfg = packProvider()
            if (cfg != null && currentSlug != null && currentLoop &&
                PetAnimPicker.inWorkStatus(cfg.workStatus, currentSlug!!)
            ) {
                currentLoop = false
                onPlay(currentSlug, false)
            }
        }
    }

    /** 用户点击：播一段 clicks 回应，播完自动回到原来的状态。 */
    fun playClick(): Boolean {
        val cfg = packProvider() ?: return false
        val slug = PetAnimPicker.pick(cfg.clicks.filter { cfg.isAvailable(it) }, currentSlug)
            ?: return false
        mode = Mode.ONCE
        playInternal(slug, once = true)
        return true
    }

    /** 菜单点播 / 外部点播的单段。noMirror 动作在已镜像时先翻回来。 */
    fun playOnce(slug: String): Boolean {
        val cfg = packProvider() ?: return false
        if (!cfg.isAvailable(slug)) return false
        if (cfg.isNoMirror(slug) && mirrored) setMirror(false)
        mode = Mode.ONCE
        playInternal(slug, once = true)
        return true
    }

    /** 拖拽开始：播 drag 动画（循环），不参与随机链。 */
    fun playDrag(): Boolean {
        val cfg = packProvider() ?: return false
        val slug = PetAnimPicker.pick(cfg.drag.filter { cfg.isAvailable(it) }) ?: return false
        mode = Mode.WORK // 借 WORK 语义：一直播，松手后由 endDrag 收尾
        playInternal(slug, once = false)
        return true
    }

    /** 松手：停止拖拽动画，回到原来的状态。 */
    fun endDrag() {
        if (!started) return
        restoreFromOnce()
    }

    fun flip() {
        setMirror(!mirrored)
    }

    fun setMirror(m: Boolean) {
        if (mirrored == m) return
        mirrored = m
        onMirror(m)
    }

    // ---------------------------------------------------------------- 播完回调

    /** PetView 在一次性动画播到结尾时调用。 */
    fun notifyEnded() {
        if (!started) return
        val cfg = packProvider()
        val cur = currentSlug

        when (mode) {
            Mode.WORK -> {
                val slot = workSlot
                if (slot != null && cfg != null && cur != null) {
                    if (slot.terminal) {
                        // 终态播完 → 收回随机链
                        workSlot = null
                        advance()
                        return
                    }
                    val nextSlug = PetAnimPicker.nextWorkStatusAnim(cfg.workStatus, cur)
                    if (nextSlug != null) {
                        playInternal(nextSlug, once = true)
                        return
                    }
                    // 单候选档位：续播同一段（这里兜住 loop 被掐断的情形）
                    playInternal(cur, once = false)
                    return
                }
                restoreFromOnce()
            }

            Mode.ONCE -> restoreFromOnce()

            Mode.RANDOM -> advance()
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 交互插播结束：优先回到工作状态，否则回随机链。 */
    private fun restoreFromOnce() {
        val cfg = packProvider()
        val slot = workSlot
        if (cfg != null && slot != null && !slot.terminal) {
            val name = PetAnimPicker.resumeWorkStatus(cfg.workStatus, slot.ordinal, currentSlug)
            if (name != null) {
                val rotating = cfg.workSlotCandidates(slot.ordinal).size > 1
                mode = Mode.WORK
                playInternal(name, once = rotating)
                return
            }
        }
        if (slot != null) workSlot = null
        advance()
    }

    /** 随机链：按权重掷骰选下一段。 */
    private fun advance() {
        val cfg = packProvider()
        if (cfg == null) {
            mode = Mode.RANDOM
            playInternal(null, once = false)
            return
        }
        mode = Mode.RANDOM
        when (PetAnimPicker.rollKind(Random.nextDouble(), cfg.animationWeights)) {
            // 注意：桌面版 pickNext 末尾统一 setOnce(true) —— **idle 也是播一遍就换**，
            // 不存在"无限循环 idle"。若这里设成 loop，抽到 idle 后 ended 永不触发，
            // 整条链会当场停摆（桌宠从此再也不换动作）。
            PetAnimPicker.Kind.IDLE -> {
                val slug = PetAnimPicker.pick(cfg.idle.filter { cfg.isAvailable(it) }, currentSlug)
                playInternal(slug, once = true)
            }

            PetAnimPicker.Kind.TURN -> {
                val pool = cfg.turn.filter { cfg.isAvailable(it) }
                val slug = PetAnimPicker.pick(pool, currentSlug)
                if (slug == null) {
                    fallbackIdle(cfg)
                } else {
                    turnPending = true
                    playInternal(slug, once = true)
                }
            }

            PetAnimPicker.Kind.MOVE -> {
                if (!tryMove(cfg)) fallbackRandomAction(cfg)
            }

            PetAnimPicker.Kind.ACTION -> fallbackRandomAction(cfg)
        }
    }

    private var turnPending = false

    /** move 分支：选中移动动画并请求位移；走不了返回 false 让调用方降级。 */
    private fun tryMove(cfg: PetPackConfig): Boolean {
        val pool = cfg.moves.filter { cfg.isAvailable(it.slug) }
        if (pool.isEmpty()) return false
        val action = PetAnimPicker.pick(pool, null) ?: return false
        val durationMs = cfg.durationOf(action.slug)
        if (!onMoveRequest(action, durationMs)) return false
        playInternal(action.slug, once = true)
        return true
    }

    private fun fallbackRandomAction(cfg: PetPackConfig) {
        val act = PetAnimPicker.pickCategoryAction(
            categories = cfg.categories,
            idlePool = cfg.idle.filter { cfg.isAvailable(it) },
            mirrored = mirrored,
            current = currentSlug,
        )
        if (act == null) fallbackIdle(cfg)
        else playInternal(act.slug, once = true)
    }

    /** idle 兜底同样播一遍即换，理由见 Kind.IDLE 分支。 */
    private fun fallbackIdle(cfg: PetPackConfig) {
        val slug = PetAnimPicker.pick(cfg.idle.filter { cfg.isAvailable(it) }, currentSlug)
        playInternal(slug, once = true)
    }

    private fun playInternal(slug: String?, once: Boolean) {
        if (slug == null) {
            // 池子是空的（包缺段/素材缺失）—— 以前这里静默变成"桌宠不动也不换"，
            // 现场只剩一句"它死了"，查不出是哪一步是空的。
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "nothing to play (mode=$mode current=$currentSlug)")
            }
            currentSlug = null
            currentLoop = true
            onPlay(null, true)
            return
        }
        // turn 播完要翻面：这里先记下，notifyEnded 时统一处理
        val cfg = packProvider()
        val isTurn = cfg != null && slug in cfg.turn
        currentSlug = slug
        currentLoop = !once
        turnPending = isTurn && once
        if (cfg != null && cfg.isNoMirror(slug) && mirrored) {
            // 随机链里理论上选不到 noMirror（已在 pickWeightedCategory 过滤），
            // 这里兜住来自 turn/idle 等池的情况
            setMirror(false)
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "play ${cfg?.nameOf(slug) ?: slug} once=$once mode=$mode")
        }
        onPlay(slug, !once)
    }

    /** turn 动画播完立刻翻面，并同步给后续选取（onMirror 已同步 mirrored 字段）。 */
    private fun handleTurnFinished() {
        turnPending = false
        setMirror(!mirrored)
    }

    /** 一次性动画真正到点时由 View 回调这里，先结清 turn 翻面再交给状态机。 */
    fun onAnimationEnd() {
        if (turnPending) handleTurnFinished()
        notifyEnded()
    }

    private companion object {
        const val TAG = "DshPetChain"
    }
}

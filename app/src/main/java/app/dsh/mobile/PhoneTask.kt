package app.dsh.mobile

/**
 * 手机任务状态机（v1.6.22）。
 *
 * ## 它解决什么
 *
 * AI 在操作手机时，用户手上**没有任何按钮能让它停下来**。
 * 只有 Thread.interrupt，没有对外入口 —— 这是安全缺口，不是体验问题。
 *
 * 本类把「一次任务」变成一个显式状态：
 *
 *   IDLE --begin()--> RUNNING --stop()--> STOPPED --(30s 无调用)--> IDLE
 *                       |
 *                       +--pause()--> PAUSED --resume()--> RUNNING
 *
 * ## 三条设计约束（都是踩过的坑）
 *
 * 1. **停止要硬**：`stop()` 之后一段窗口内所有调用都被拒绝，而不是「下次检查时退出」。
 *    无障碍动作是异步派发的，攒在队列里的手势不会因为一个 volatile 标记而消失；
 *    必须在**入口**就拒掉。窗口期内不断重新计时 —— 模型重试一次就续一次，
 *    否则它会在 1 秒后重来，用户看到的就是「停止按钮没用」。
 *
 * 2. **暂停 ≠ 空闲**：暂停期间不推进空闲计时。否则用户暂停去接个电话，
 *    回来发现任务已经被判超时结束，无法恢复。
 *
 * 3. **报告要诚实**：任务结束时给的是「做了什么、成没成、卡在哪」，
 *    不是一句「已完成」。做不到的事写进报告，比假装成功有用得多。
 *
 * 纯 Kotlin，时钟注入 —— 状态机边界只能在 JVM 上真跑才靠得住。
 */
class PhoneTask(
    private val clock: () -> Long = System::currentTimeMillis,
    /** 多久没有新的调用算任务结束。 */
    val idleMs: Long = 3L * 60L * 1000L,
    /** 停止后的硬保持窗口。 */
    val stopHoldMs: Long = 30L * 1000L,
) {

    enum class State { IDLE, RUNNING, PAUSED, STOPPED }
    companion object {
        /** 悬浮窗「停止 AI」按钮写进任务记录的停止原因。 */
        const val USER_STOP_REASON = "用户点了停止"
        const val OVERLAY_STOP_REASON = "用户在悬浮窗点了停止"
    }

    /** 一次任务的快照，给 UI 与报告用。 */
    data class Snapshot(
        val id: Long,
        val state: State,
        /** AI 最后操作的应用名。 */
        val app: String?,
        /** 停止原因（用户点了停止 / 用户接管触摸 / 调用方主动结束）。 */
        val stopReason: String?,
        /** 本任务累计的动作数。 */
        val steps: Int,
        /** 最近一步的描述，给悬浮窗显示。 */
        val lastStep: String?,
        /** 已完成的步骤记录，给任务报告用。 */
        val trail: List<String>,
    ) {
        val active: Boolean get() = state == State.RUNNING || state == State.PAUSED
    }

    private val lock = Any()
    private var nextId = 1L
    private var id = 0L
    private var state = State.IDLE
    private var lastCallAt = 0L
    private var stoppedUntil = 0L
    private var stopReason: String? = null
    private var app: String? = null
    private var steps = 0
    private var lastStep: String? = null
    private val trail = ArrayList<String>()

    /** 状态变化订阅（悬浮窗/通知用）。 */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun addListener(l: (Snapshot) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Snapshot) -> Unit) { listeners.remove(l) }

    private fun fire() {
        val s = snapshot()
        listeners.forEach { runCatching { it(s) } }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(id, state, app, stopReason, steps, lastStep, trail.toList())
    }

    /** 停止原因文案（给模型与用户看）。 */
    fun stoppedMessage(): String {
        val r = synchronized(lock) { stopReason }
        return "手机操作已被用户停止" + (r?.let { "（$it）" } ?: "") + "。不要重试；先问用户要不要继续。"
    }

    /**
     * 每次手机操作调用前必须过这里。
     *
     * 返回 null 表示放行；返回非 null 表示**拒绝**，调用方应把这句话回给模型。
     */
    fun begin(appLabel: String? = null): String? {
        var changed = false
        val refusal: String?
        synchronized(lock) {
            val now = clock()
            // 停止窗口：拒掉，并续期 —— 模型重试一次就续一次。
            if (now < stoppedUntil) {
                stoppedUntil = now + stopHoldMs
                return stoppedMessage()
            }
            // 窗口刚过：清状态。
            if (state == State.STOPPED) {
                state = State.IDLE
                id = 0L
                stopReason = null
                changed = true
            }
            // 空闲超时：暂停期间不算。
            if (state == State.RUNNING && now - lastCallAt > idleMs) {
                state = State.IDLE
                id = 0L
                changed = true
            }
            if (state == State.IDLE) {
                state = State.RUNNING
                id = nextId++
                steps = 0
                trail.clear()
                lastStep = null
                stopReason = null
                changed = true
            }
            lastCallAt = now
            if (!appLabel.isNullOrBlank()) app = appLabel
            refusal = null
        }
        if (changed) fire()
        return refusal
    }

    /** 记录一步（给悬浮窗与报告用）。 */
    fun noteStep(text: String) {
        synchronized(lock) {
            steps++
            lastStep = text
            if (trail.size < 20) trail.add(text)
        }
        fire()
    }

    /**
     * 用户点了停止。
     *
     * 会立刻进入 STOPPED 并开一个 stopHoldMs 的硬窗口；窗口内每次调用都被拒并续期。
     */
    fun stop(reason: String = USER_STOP_REASON) {
        synchronized(lock) {
            val now = clock()
            if (state == State.RUNNING || state == State.PAUSED) {
                state = State.STOPPED
                stopReason = reason
                stoppedUntil = now + stopHoldMs
            } else if (state == State.STOPPED) {
                // 重复点停止：续期，不覆盖原因。
                stoppedUntil = now + stopHoldMs
            }
        }
        fire()
    }

    /** 用户暂停。暂停期间不推进空闲计时。 */
    fun pause() {
        synchronized(lock) {
            if (state == State.RUNNING) state = State.PAUSED
        }
        fire()
    }

    /** 用户恢复。 */
    fun resume() {
        synchronized(lock) {
            if (state == State.PAUSED) {
                state = State.RUNNING
                lastCallAt = clock()
            }
        }
        fire()
    }

    /** 调用方主动结束（任务完成）。 */
    fun finish() {
        synchronized(lock) {
            state = State.IDLE
            id = 0L
            stopReason = null
            app = null
            steps = 0
            lastStep = null
            trail.clear()
        }
        fire()
    }

    /** 是否处于暂停态（动作执行前应拒绝）。 */
    fun isPaused(): Boolean = synchronized(lock) { state == State.PAUSED }

    /** 是否处于停止窗口。 */
    fun isStopped(): Boolean = synchronized(lock) { clock() < stoppedUntil }

    /** 当前是否有一个活着的任务。 */
    fun isActive(): Boolean = synchronized(lock) { state == State.RUNNING || state == State.PAUSED }
}
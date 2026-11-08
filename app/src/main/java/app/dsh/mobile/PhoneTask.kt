package app.dsh.mobile

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 手机任务状态机（v1.6.23）。
 *
 * ## 它解决什么
 *
 * AI 在操作手机时，用户手上必须有一个按钮能让它停下来。
 * 本类把一次任务变成显式状态，并维护：
 *
 *   IDLE --begin()--> RUNNING --stop()--> STOPPED --(30s 无调用)--> IDLE
 *                       |
 *                       +--pause()--> PAUSED --resume()--> RUNNING
 *
 * ## 六条设计约束（每条都对应一个真实缺陷）
 *
 * 1. 停止要硬：stop() 之后一段窗口内所有调用都在入口被拒，而不是下次检查时退出。
 *    无障碍动作是异步派发的，攒在队列里的手势不会因为一个标记而消失。
 *    窗口期内每拒一次就续一次 —— 模型重试就续期，否则它 1 秒后重来，用户看到的就是停止没用。
 *
 * 2. 暂停不等于空闲：暂停期间不推进空闲计时。否则用户接个电话回来，任务已被判超时。
 *
 * 3. 在飞的调用不算空闲：handoff 可以等 10 分钟，若按 3 分钟空闲判死，
 *    用户刚点完成，任务已经在 7 分钟前被收掉了。inFlight 计数优先于时钟。
 *
 * 4. 快照必须惰性判空闲：不靠定时器也要让 GET /task 立刻显示任务已结束，
 *    否则 AI 停手后悬浮窗的停止按钮会永远亮着。
 *
 * 5. 报告要诚实：记的是做了什么、什么结果（ok/error/timeout/stopped），不是一句已完成。
 *
 * 6. 旧任务的晚到结果不能影响新任务：lifecycle 计数器让过期 Owner 直接失效。
 *
 * 纯 Kotlin，时钟注入 —— 这些边界只有真跑断言才靠得住。
 */
class PhoneTask(
    private val clock: () -> Long = System::currentTimeMillis,
    /** 多久没有新的调用算任务结束。 */
    val idleMs: Long = 3L * 60L * 1000L,
    /** 停止后的硬保持窗口。 */
    val stopHoldMs: Long = 30L * 1000L,
) {

    enum class State { IDLE, RUNNING, PAUSED, STOPPED }

    /** 一次任务的快照，给 UI 与报告用。 */
    data class Snapshot(
        val id: Long,
        val state: State,
        /** AI 最后操作的应用标识。 */
        val app: String?,
        /** 停止原因。 */
        val stopReason: String?,
        /** 本任务累计的动作数。 */
        val steps: Int,
        /** 最近一步的描述。 */
        val lastStep: String?,
        /** 是否处于停止窗口内。 */
        val stopped: Boolean,
        /** 生命周期代号，用于让旧 Owner 失效。 */
        internal val lifecycle: Long,
    ) {
        val active: Boolean get() = state == State.RUNNING || state == State.PAUSED
    }

    /**
     * 不透明的任务归属凭证。
     *
     * 一个动作发起时拿到 Owner，结果回来时用它核对「还是不是当初那个任务」——
     * 否则一个慢动作的返回可能把新任务的应用名改掉。
     */
    class Owner internal constructor(
        val taskId: Long,
        internal val lifecycle: Long,
    )

    private val lock = Object()
    private var nextId = 1L
    private var id = 0L
    private var lifecycle = 0L
    private var state = State.IDLE
    private var lastCallAt = 0L
    private var stoppedUntil = 0L
    private var stopReason: String? = null
    private var app: String? = null
    private var inFlight = 0
    private var pausedFlag = false
    private val interjections = ArrayList<String>()

    /** 动作日志（给人看的，不含屏幕内容）。 */
    val log = ActionLog(clock = clock)

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun addListener(l: (Snapshot) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Snapshot) -> Unit) { listeners.remove(l) }

    /** 停止原因文案（给模型看）。 */
    fun stoppedMessage(): String {
        val r = synchronized(lock) { stopReason }
        return STOPPED_MESSAGE + (r?.let { "（" + it + "）" } ?: "")
    }
    /**
     * 每个手机动作调用前必须过这里。
     *
     * 返回 null 表示放行；非 null 表示拒绝，调用方应把它回给模型。
     */
    fun begin(appLabel: String? = null): String? {
        var started = false
        synchronized(lock) {
            val now = clock()
            if (now < stoppedUntil) {
                stoppedUntil = now + stopHoldMs
                return stoppedMessage()
            }
            if (state == State.STOPPED) resetIdleLocked()
            // 在飞的调用不算空闲：handoff 可以等很久。
            if (state == State.RUNNING && inFlight == 0 && now - lastCallAt > idleMs) resetIdleLocked()
            if (state == State.IDLE) {
                state = State.RUNNING
                id = nextId++
                lifecycle++
                log.clear()
                started = true
            }
            lastCallAt = now
            if (!appLabel.isNullOrBlank()) app = appLabel
        }
        if (started) fire()
        return null
    }

    /** 当前 Owner（动作发起时取，结果回来时核对）。 */
    fun owner(): Owner = synchronized(lock) { Owner(id, lifecycle) }

    /** 这个 Owner 是否仍属于当前任务。 */
    fun isCurrent(o: Owner?): Boolean = synchronized(lock) {
        o != null && o.taskId == id && o.lifecycle == lifecycle &&
            state != State.IDLE && clock() >= stoppedUntil
    }

    /** 一个动作开始执行（在飞的调用数加一）。 */
    fun callStarted() {
        synchronized(lock) { inFlight++ }
    }

    /** 一个动作结束（在飞数减一，并把活动时间推到现在 —— 长任务不能算空闲）。 */
    fun callEnded(o: Owner? = null) {
        synchronized(lock) {
            if (isCurrent(o)) lastCallAt = clock()
            if (inFlight > 0) inFlight--
        }
    }

    /** 在飞的调用数。 */
    val callsInFlight: Int get() = synchronized(lock) { inFlight }

    /** 记一步（给悬浮窗与报告用）。 */
    fun noteStep(tool: String, summary: String, decision: String, appLabel: String? = null) {
        log.add(tool, appLabel ?: synchronized(lock) { app }, summary, decision)
        fire()
    }

    /** 用户点了停止。 */
    fun stop(reason: String = USER_STOP_REASON) {
        synchronized(lock) {
            if (state == State.RUNNING || state == State.PAUSED) {
                state = State.STOPPED
                stopReason = reason
                stoppedUntil = clock() + stopHoldMs
                pausedFlag = false
            } else if (state == State.STOPPED) {
                stoppedUntil = clock() + stopHoldMs
            }
            lock.notifyAll()
        }
        fire()
    }

    /** 暂停。 */
    fun pause() {
        synchronized(lock) {
            if (state == State.RUNNING) {
                state = State.PAUSED
                pausedFlag = true
            }
            lock.notifyAll()
        }
        fire()
    }

    /** 恢复。 */
    fun resume() {
        synchronized(lock) {
            if (state == State.PAUSED) {
                state = State.RUNNING
                pausedFlag = false
                lastCallAt = clock()
            }
            lock.notifyAll()
        }
        fire()
    }
    /**
     * 暂停期间阻塞等待，最多 timeoutMs。
     *
     * 返回 true 才继续执行；返回 false 表示期间被停止、任务结束或超时 ——
     * 光改一个标记不阻塞，暂停等于没暂停。
     */
    fun awaitResume(timeoutMs: Long): Boolean {
        val deadline = clock() + timeoutMs
        synchronized(lock) {
            val expect = if (state != State.IDLE) id else 0L
            while (pausedFlag) {
                val left = deadline - clock()
                if (left <= 0) return false
                try {
                    lock.wait(minOf(left, 1000L))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            // 暂停期间任务被结束了：那不是恢复。
            if (expect != 0L && (state == State.IDLE || id != expect)) return false
            return clock() >= stoppedUntil
        }
    }

    /** 用户中途插话（最多留 5 条）。 */
    fun interject(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        synchronized(lock) {
            interjections.add(t)
            while (interjections.size > 5) interjections.removeAt(0)
        }
    }

    /** 只读不清的插话（GET 用，GET 不应有副作用）。 */
    fun peekInterjections(): List<String> = synchronized(lock) { interjections.toList() }

    /** 取走并清空插话。 */
    fun drainInterjections(): List<String> = synchronized(lock) {
        val out = interjections.toList()
        interjections.clear()
        out
    }

    /** 定期调用：让监听方及时收到任务因空闲而结束。返回是否真的结束了。 */
    fun expireIdle(): Boolean {
        var ended = false
        synchronized(lock) {
            if (idleLocked(clock())) {
                resetIdleLocked()
                ended = true
            }
        }
        if (ended) fire()
        return ended
    }

    /** 调用方主动结束（任务完成）。 */
    fun finish() {
        synchronized(lock) {
            lifecycle++
            resetIdleLocked()
            lock.notifyAll()
        }
        fire()
    }

    /** 清掉停止窗口（用户把手机控制关了又开时用）。 */
    fun clearStop() {
        synchronized(lock) {
            lifecycle++
            stoppedUntil = 0L
            stopReason = null
        }
        fire()
    }

    fun isPaused(): Boolean = synchronized(lock) { state == State.PAUSED }
    fun isStopped(): Boolean = synchronized(lock) { clock() < stoppedUntil }
    fun isActive(): Boolean = synchronized(lock) { state != State.IDLE }

    /**
     * 快照。
     *
     * 惰性判空闲：AI 停手后 GET /task 立刻显示任务已结束，
     * 不依赖任何定时器 —— 否则悬浮窗的停止按钮会一直亮着。
     */
    fun snapshot(): Snapshot = synchronized(lock) {
        val idle = idleLocked(clock())
        val effective = if (idle) State.IDLE else state
        Snapshot(
            id = if (effective == State.IDLE) 0L else id,
            state = effective,
            app = if (effective == State.IDLE) null else app,
            stopReason = stopReason,
            steps = log.size(),
            lastStep = log.last()?.summary,
            stopped = clock() < stoppedUntil,
            lifecycle = lifecycle,
        )
    }

    private fun idleLocked(now: Long): Boolean =
        state == State.RUNNING && inFlight == 0 && now - lastCallAt > idleMs

    private fun resetIdleLocked() {
        lifecycle++
        state = State.IDLE
        id = 0L
        app = null
        stopReason = null
        pausedFlag = false
        interjections.clear()
        log.clear()
    }

    private fun fire() {
        val s = snapshot()
        listeners.forEach { runCatching { it(s) } }
    }

    companion object {
        /** 用户点了停止（桥的默认原因）。 */
        const val USER_STOP_REASON = "用户点了停止"

        /** 悬浮窗按钮写进日志的停止原因。 */
        const val OVERLAY_STOP_REASON = "用户在悬浮窗点了停止"

        /** 回给模型的停止文案 —— 明确要求它别再调用、直接汇报。 */
        const val STOPPED_MESSAGE = "用户已停止本次手机操作。不要再调用手机工具，直接告诉用户已经停下"
    }
}
/**
 * 动作日志：按时间记录每一步做了什么、什么结果。
 *
 * 为什么不复用 trail: List<String>：只有工具名等于没记（会得到 tap/tap/tap）。
 * 这里每条都带 tool、app、summary、decision，容量 300，取用时最新在前。
 * 刻意不存屏幕内容 —— 这份日志是给人复查的，不该夹带敏感数据。
 */
class ActionLog(
    private val capacity: Int = 300,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(
        val time: Long,
        val tool: String,
        val app: String?,
        val summary: String,
        /** ok / error / timeout / stopped / refused */
        val decision: String,
    )

    private val entries = ArrayDeque<Entry>()

    fun add(tool: String, app: String?, summary: String, decision: String) {
        synchronized(entries) {
            entries.addLast(Entry(clock(), tool, app, summary, decision))
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun last(): Entry? = synchronized(entries) { entries.lastOrNull() }

    /** 最近 N 条，最新在前。 */
    fun recent(limit: Int): List<Entry> = synchronized(entries) {
        entries.reversed().take(limit.coerceIn(0, capacity))
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
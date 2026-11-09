package app.dsh.mobile

/**
 * 向用户提问（v1.6.24）。
 *
 * ## 它解决什么
 *
 * AI 卡在需要用户决定的地方时，只有两条路：瞎猜，或者整条任务放弃。
 * 前者会做错事，后者让用户重新来一遍。真实场景里这类岔路很多 ——
 * 「有两个同名的咖啡店，选哪个」「要不要把这个标签页关掉」
 * 「这一步要提交订单，确认吗」。
 *
 * ## 三条约束
 *
 * 1. **答案只能被取一次**。模型重试或两个调用方同时读到同一个答案，
 *    会让它误以为用户答了两遍，进而执行两次动作。
 *
 * 2. **选项是建议不是限制**。用户可能想回答选项之外的内容，
 *    所以 `answer` 接受任意文本；选项只是让常见情况点一下就好。
 *
 * 3. **超时要说清楚**。用户没答和用户拒绝是两件事 ——
 *    前者可以再问一次，后者不该追问。
 *
 * 纯 Kotlin，时钟注入。
 */
class PhoneAsk(
    private val clock: () -> Long = System::currentTimeMillis,
    /** 默认等待上限。 */
    val defaultTimeoutMs: Long = 2L * 60L * 1000L,
) {

    enum class State {
        /** 没有待回答的问题。 */
        IDLE,
        /** 已提出，等用户回答。 */
        ASKING,
        /** 用户答了。 */
        ANSWERED,
        /** 等超时了，用户没答。 */
        TIMEOUT,
    }

    /** 一次提问。 */
    data class Question(
        val id: Long,
        /** 要问用户的话。 */
        val text: String,
        /** 预设选项（可为空 = 只等自由输入）。 */
        val options: List<String>,
        /** 允许自由输入。 */
        val allowFreeText: Boolean,
        val askedAt: Long,
        val deadlineAt: Long,
    )

    /** 提问结果。 */
    data class Result(
        val state: State,
        /** 用户的答案（ANSWERED 时非 null）。 */
        val answer: String?,
        /** 回答来自选项还是自由输入。 */
        val fromOption: Boolean,
    )

    private val lock = Object()
    private var nextId = 1L
    private var current: Question? = null
    private var state = State.IDLE
    private var answer: String? = null
    private var fromOption = false

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Question?) -> Unit>()

    fun addListener(l: (Question?) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Question?) -> Unit) { listeners.remove(l) }

    private fun fire() {
        val q = synchronized(lock) { if (state == State.ASKING) current else null }
        listeners.forEach { runCatching { it(q) } }
    }

    /** 当前待回答的问题（没在等就 null）。 */
    fun pending(now: Long = clock()): Question? = synchronized(lock) {
        val q = current ?: return null
        if (state != State.ASKING) return null
        if (now >= q.deadlineAt) return null
        q
    }

    /**
     * 提出一个问题。
     *
     * 已有待回答的问题时不覆盖 —— 覆盖会让先问的那个永远等不到答案。
     */
    fun ask(
        text: String,
        options: List<String> = emptyList(),
        allowFreeText: Boolean = true,
        timeoutMs: Long = defaultTimeoutMs,
    ): Question? {
        var fired = false
        val q: Question?
        synchronized(lock) {
            val now = clock()
            val existing = current
            if (state == State.ASKING && existing != null && now < existing.deadlineAt) {
                q = null   // 已有待答问题，不覆盖
            } else {
                val nq = Question(
                    id = nextId++,
                    text = text,
                    options = options.take(4),
                    allowFreeText = allowFreeText,
                    askedAt = now,
                    deadlineAt = now + timeoutMs.coerceIn(5_000L, 30L * 60L * 1000L),
                )
                current = nq
                state = State.ASKING
                answer = null
                fromOption = false
                q = nq
                fired = true
            }
        }
        if (fired) fire()
        return q
    }

    /**
     * 用户回答。
     *
     * 返回 false 表示没有待答问题、已超时、或已经答过了。
     */
    fun answer(text: String, fromOption: Boolean = false, now: Long = clock()): Boolean {
        var fired = false
        val ok: Boolean
        synchronized(lock) {
            val q = current
            if (q == null || state != State.ASKING) {
                ok = false
            } else if (now >= q.deadlineAt) {
                state = State.TIMEOUT
                ok = false
                fired = true
            } else {
                answer = text
                this.fromOption = fromOption
                state = State.ANSWERED
                ok = true
                fired = true
            }
        }
        if (fired) fire()
        return ok
    }

    /**
     * 取走答案。
     *
     * **只能成功一次** —— 模型重试或两个调用方同时读，若都能拿到答案，
     * 它会以为用户答了两遍，进而执行两次动作。
     * 取走后回到 IDLE。
     */
    fun take(now: Long = clock()): Result {
        var fired = false
        val r: Result
        synchronized(lock) {
            val q = current
            if (q == null) {
                r = Result(State.IDLE, null, false)
            } else if (state == State.ASKING && now >= q.deadlineAt) {
                // 超时就地消费：答案永远不可能来了，
                // 没有理由把它留在队列里占着，也不该让模型连续读两次才能开始新的提问。
                current = null
                state = State.IDLE
                r = Result(State.TIMEOUT, null, false)
                fired = true
            } else if (state == State.ANSWERED) {
                r = Result(State.ANSWERED, answer, fromOption)
                // 取走即清空：答案只能被消费一次。
                current = null
                state = State.IDLE
                answer = null
                fromOption = false
                fired = true
            } else if (state == State.TIMEOUT) {
                r = Result(State.TIMEOUT, null, false)
                current = null
                state = State.IDLE
                fired = true
            } else {
                // 还在等
                r = Result(State.ASKING, null, false)
            }
        }
        if (fired) fire()
        return r
    }

    /** 用户取消（关掉问题不答）。 */
    fun cancel() {
        var fired = false
        synchronized(lock) {
            if (state == State.ASKING || state == State.ANSWERED) {
                current = null
                state = State.IDLE
                answer = null
                fromOption = false
                fired = true
            }
        }
        if (fired) fire()
    }

    /** 等用户回答，最多 timeoutMs。 */
    fun await(timeoutMs: Long, now: () -> Long = clock): State {
        val deadline = now() + timeoutMs
        synchronized(lock) {
            while (state == State.ASKING) {
                val left = deadline - now()
                if (left <= 0) return State.ASKING
                try {
                    lock.wait(minOf(left, 500L))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return State.ASKING
                }
            }
            return state
        }
    }


    companion object {
        /** 默认等待上限。 */
        const val DEFAULT_TIMEOUT_MS = 2L * 60L * 1000L

        /** 回给模型的提问开场白。 */
        const val ASK_BEGIN_MESSAGE = "已向用户提问。等待回答：用 POST /ask/result 取结果（答案只能取一次）。"

        /** 悬浮窗上的提问标题行。 */
        const val ASK_UI_TITLE = "需要你选一下"
    }
}

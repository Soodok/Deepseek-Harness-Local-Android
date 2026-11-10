package app.dsh.mobile

/**
 * 交接给用户（v1.6.21）。
 *
 * ## 场景
 *
 * 登录、短信验证码、支付确认这类页面，agent 不该猜也不该点。
 * 保护页闸门能拦住它，但只回一句「403 请用户接管」等于把球扔回给模型 ——
 * 模型只能反复尝试、或者干脆放弃整条任务。
 *
 * 这里补上缺的那半截：**真的等**。模型发起交接，用户在手机上操作完，
 * 点一下「已完成」；模型拿到「用户已完成，当前是新屏幕」继续往下走。
 * 超时也明确告诉它超时了，而不是让它以为页面没变。
 *
 * ## 为什么状态放这里而不放 AgentBridge
 *
 * 等待要跨请求（模型发起 → 用户操作 → 模型再查），状态必须活在两次请求之间。
 * 抽成独立对象也便于 JVM 直接断言状态机，不必拉起 Android。
 */
object Handoff {

    /** 一次交接的状态。 */
    enum class State {
        /** 没有进行中的交接。 */
        IDLE,
        /** 已请用户接管，正在等。 */
        WAITING,
        /** 用户点了「已完成」。 */
        DONE,
        /** 等超时了。 */
        TIMEOUT,
    }

    /** 单次交接。 */
    class Session(
        val reason: String,
        val packageName: String,
        val startedAt: Long,
        val deadlineAt: Long,
    ) {
        @Volatile var state: State = State.WAITING
        @Volatile var finishedAt: Long = 0L
    }

    /** 默认等待上限。与 dsh-Go 的 10 分钟一致：足够慢手用户，又不至于挂死一天。 */
    const val DEFAULT_TIMEOUT_MS = 10L * 60L * 1000L

    /** 轮询间隔。 */
    private const val POLL_MS = 200L

    @Volatile private var current: Session? = null

    /** 当前会话（没有则为 null）。 */
    fun current(): Session? = current

    /**
     * 发起交接。已有进行中的会话时直接返回它 —— 不覆盖。
     *
     * 为什么不允许覆盖：模型可能重试，或者两个任务同时抢；
     * 覆盖会让先发起的那个永远等不到结果。
     */
    fun begin(reason: String, packageName: String, timeoutMs: Long): Session {
        val now = System.currentTimeMillis()
        val live = current
        if (live != null && live.state == State.WAITING && now < live.deadlineAt) return live
        val t = if (timeoutMs in 5_000L..DEFAULT_TIMEOUT_MS) timeoutMs else DEFAULT_TIMEOUT_MS
        val s = Session(reason, packageName, now, now + t)
        current = s
        return s
    }

    /** 用户点了「已完成」。没有进行中的会话时返回 false。 */
    fun complete(): Boolean {
        val s = current ?: return false
        if (s.state != State.WAITING) return false
        s.state = State.DONE
        s.finishedAt = System.currentTimeMillis()
        return true
    }

    /** 用户取消 / 放弃本次任务。 */
    fun cancel() {
        val s = current
        if (s != null && s.state == State.WAITING) {
            // 取消与超时在对外语义上不同：取消是用户主动，超时是没人管。
            s.state = State.TIMEOUT
            s.finishedAt = System.currentTimeMillis()
        }
        current = null
    }

    /** 清空（任务结束、或开始新任务时）。 */
    fun reset() {
        current = null
    }

    /** 结算：过了 deadline 还在等，就转成 TIMEOUT。返回结算后的状态。 */
    fun settle(now: Long = System.currentTimeMillis()): State {
        val s = current ?: return State.IDLE
        if (s.state == State.WAITING && now >= s.deadlineAt) {
            s.state = State.TIMEOUT
            s.finishedAt = now
        }
        return s.state
    }

    /**
     * 阻塞等待用户完成（或超时）。
     *
     * 桥为每个连接开独立线程，所以这里阻塞不会卡住其他请求。
     * 返回结算后的状态 —— 调用方据 DONE / TIMEOUT 给不同响应。
     */
    fun await(timeoutMs: Long, now: () -> Long = { System.currentTimeMillis() }): State {
        val s = current ?: return State.IDLE
        val until = now() + timeoutMs
        while (now() < until) {
            val st = settle(now())
            if (st != State.WAITING) return st
            try {
                Thread.sleep(POLL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return settle(now())
            }
        }
        return settle(now())
    }

    /** 发起交接时给模型的话。模型应把它转达给用户。 */
    const val BEGIN_MESSAGE =
        "已请用户接管这台手机。请把下面的提示转达给用户，然后用 GET /handoff 查询是否完成。"
    /** 状态的中文说明，直接给模型看。 */
    fun describe(state: State): String = when (state) {
        State.IDLE -> "当前没有进行中的交接。"
        State.WAITING -> "正在等用户在手机上完成操作。"
        State.DONE -> "用户已完成操作，可以继续。"
        State.TIMEOUT -> "等待超时，用户还没有完成。不会继续自动操作；请询问用户，或放弃这一步。"
    }
}
package app.dsh.mobile

/**
 * 任务完成报告（v1.6.25）。
 *
 * ## 为什么需要它
 *
 * AI 在手机上干完一件事，用户很可能根本不在看屏幕 —— 手机在口袋里、
 * 或者用户去拿水了。任务做完了但没人知道，等于没做。
 *
 * ## 一个关键区分
 *
 * **「启动成功」不等于「用户回来了」**。这是 dsh-Go 在 PhoneTaskReturn
 * 里专门写下的教训：OS 接受了一次 Activity 启动请求，只说明请求被收下，
 * 不说明界面真的回到前台、用户真的看见。所以这里的做法是：
 *
 * 1. 先试着把用户带回会话（reopen）；
 * 2. 在限定时间内反复确认前台是否真的是我们的界面；
 * 3. 确认不到就发通知 —— 用户至少知道「有结果了」。
 *
 * 报告本身只写**做了什么**，不写「成功了」（成功与否由动作判定，
 * 这里给的是给人看的一句话）。
 *
 * 纯 Kotlin，时钟与副作用全部注入，可在 JVM 上直接测。
 */
class TaskReport(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 给用户看的一句话报告。 */
    data class Body(
        /** 干了什么（人话，不带工具名）。 */
        val summary: String,
        /** 做了几步。 */
        val steps: Int,
        /** 结束时刻。 */
        val at: Long,
        /** 被用户叫停 / 正常完成。 */
        val stopped: Boolean,
    )

    enum class Shown {
        /** 用户已经看到了会话界面。 */
        VISIBLE,
        /** 没看到，已发通知。 */
        NOTIFIED,
        /** 没看到，通知也没发出去。 */
        LOST,
    }

    data class Outcome(
        val shown: Shown,
        /** 用户是否被带回会话。 */
        val opened: Boolean,
        /** 启动请求是否被接受（注意：接受 ≠ 用户看见）。 */
        val launched: Boolean,
        val notified: Boolean,
    )

    /** 确认窗口。太短会误判（界面还没起来），太长会白等。 */
    val confirmMs: Long = 3_000L
    private val pollMs: Long = 100L

    private var answered = false

    /**
     * 完成一次报告。
     *
     * 所有副作用都是注入的 lambda，且 [current] 会在每个阶段重新判断 ——
     * 任务可能在等待期间就被新任务取代，那时旧的报告不该再打扰用户。
     *
     * @param current 这次报告是否仍然有效（任务没被取代 / 没被停）
     * @param visible 我们的会话界面现在是否在前台
     * @param launch 请求把用户带回会话，返回是否被接受
     * @param notify 发通知，返回是否发出去
     * @param sleep 等待（注入以便 JVM 上不真睡）
     */
    fun deliver(
        body: Body,
        current: () -> Boolean,
        visible: () -> Boolean,
        launch: () -> Boolean,
        notify: () -> Boolean,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Outcome {
        answered = false
        var launched = false

        fun finish(opened: Boolean, notified: Boolean): Outcome {
            if (answered) return lastOutcome
            answered = true
            val valid = runCatching(current).getOrDefault(false)
            val shown = when {
                !valid -> Shown.LOST
                opened -> Shown.VISIBLE
                notified -> Shown.NOTIFIED
                else -> Shown.LOST
            }
            lastOutcome = Outcome(shown, opened, launched, notified)
            return lastOutcome
        }

        // 已经在会话里：不用带走，也不用打扰。
        if (!runCatching(current).getOrDefault(false) || runCatching(visible).getOrDefault(false)) {
            return finish(runCatching(visible).getOrDefault(false), false)
        }

        launched = runCatching(launch).getOrDefault(false)
        if (!launched) {
            // 启动都没被接受，别等，直接发通知。
            val n = runCatching(notify).getOrDefault(false)
            return finish(false, n)
        }

        // 启动被接受 ≠ 用户看见了。给一段确认窗口，期间反复看前台。
        val deadline = clock() + confirmMs
        while (true) {
            if (!runCatching(current).getOrDefault(false)) return finish(false, false)
            if (runCatching(visible).getOrDefault(false)) return finish(true, false)
            if (clock() >= deadline) break
            runCatching { sleep(pollMs) }
        }
        val n = runCatching(notify).getOrDefault(false)
        return finish(false, n)
    }

    private var lastOutcome = Outcome(Shown.LOST, false, false, false)

    /** 报告正文：一句话，不带工具名与坐标。 */
    fun body(summary: String, steps: Int, stopped: Boolean, now: Long = clock()): Body =
        Body(
            summary = summary.trim().ifEmpty { if (stopped) STOPPED_SUMMARY else DONE_SUMMARY },
            steps = steps.coerceAtLeast(0),
            at = now,
            stopped = stopped,
        )

    companion object {
        const val DONE_SUMMARY = "已做完";
        const val STOPPED_SUMMARY = "已停下";

        /** 通知标题。 */
        const val NOTIFY_TITLE = "手机上那件事有结果了";

        /** 报告文末的固定说明。 */
        const val NOTIFY_HINT = "点一下回到会话看详情";
    }
}
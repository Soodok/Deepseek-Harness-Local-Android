package app.dsh.mobile

import kotlin.random.Random

/**
 * 动作之间的「思考停顿」（v1.6.33）。
 *
 * ## 为什么需要它
 *
 * 模型每轮往返本来就要几秒，但那几秒**不在同一段执行里**。真正露馅的是本地闭环：
 * 一次 /loop 里连续七八个动作，每个间隔都是 1.7 秒上下、整齐得像节拍器 ——
 * 真人做同一件事的间隔是抖动的，而且偶尔会「停一下再看看」。
 *
 * 所以这里只做两件事：
 *
 * 1. **不均匀**：每步之前 220–900ms，分布偏向短（多数时候很快，偶尔慢一点）；
 * 2. **偶发犹豫**：约 7% 的步骤会额外停 1.2–2.6 秒，模拟「这一步结果不太对，多看两眼」。
 *
 * 用户明确说过「不用考虑每步变慢，AI 思考和给出动作本身就慢」，所以默认开启，
 * 计划里传 `"humanPace": false` 可以关掉。
 *
 * 纯 Kotlin、只返回毫秒数，睡眠交给调用方 —— 这样 JVM 上能断言分布，不用真睡。
 */
object HumanPace {

    /** 常规思考停顿的下界 */
    const val MIN_THINK_MS = 220L

    /** 常规思考停顿的上界 */
    const val MAX_THINK_MS = 900L

    /** 偶发犹豫的概率 */
    const val HESITATE_CHANCE = 0.07f

    /** 犹豫时长下界 */
    const val HESITATE_MIN_MS = 1200L

    /** 犹豫时长上界 */
    const val HESITATE_MAX_MS = 2600L

    /** 偏斜指数：>1 让取值集中在短端（真人多数动作是快的，慢的是少数） */
    private const val SKEW = 1.8f

    /**
     * 一次常规思考停顿。
     *
     * `u^1.8` 把均匀分布压向短端：中位数约 320ms，而不是线性分布的中位 560ms。
     */
    fun thinkMs(rnd: Random = Random.Default): Long {
        val u = rnd.nextFloat()
        val k = Math.pow(u.toDouble(), SKEW.toDouble()).toFloat()
        return MIN_THINK_MS + ((MAX_THINK_MS - MIN_THINK_MS) * k).toLong()
    }

    /** 偶发犹豫：按 [HESITATE_CHANCE] 命中，否则 0。 */
    fun hesitateMs(rnd: Random = Random.Default): Long {
        if (rnd.nextFloat() >= HESITATE_CHANCE) return 0L
        val span = HESITATE_MAX_MS - HESITATE_MIN_MS + 1
        return HESITATE_MIN_MS + rnd.nextLong(span)
    }

    /** 某一步之前应当停顿的总时长。 */
    fun stepPauseMs(rnd: Random = Random.Default): Long = thinkMs(rnd) + hesitateMs(rnd)

    /** 暂停，可被 `Thread.interrupt()` 打断（用户点停止时靠这条立刻退出）。 */
    fun pause(ms: Long) {
        if (ms > 0L) Thread.sleep(ms)
    }
}
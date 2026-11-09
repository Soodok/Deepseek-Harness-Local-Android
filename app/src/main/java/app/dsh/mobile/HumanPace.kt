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
 * 1. **不均匀**：每步之前 60–220ms（均值约 117ms），分布偏向短；
 * 2. **偶发犹豫**：约 5% 的步骤额外停 0.7–1.8 秒，模拟「这一步结果不太对，多看两眼」。
 *
 * 量级是用户定的：v1.6.33 先按 220–900ms 做，用户反馈「延迟不需要 0.5 秒这么高，
 * 0.1 秒就够了，正常人类操纵也很快」——真人连续操作确实很快，整齐才是破绽，慢不是。
 * 默认开启，计划里传 `"humanPace": false` 可以关掉。
 *
 * 纯 Kotlin、只返回毫秒数，睡眠交给调用方 —— 这样 JVM 上能断言分布，不用真睡。
 */
object HumanPace {

    /** 常规思考停顿的下界（v1.6.34：0.5s 太重，用户要的是「够快但别整齐」） */
    const val MIN_THINK_MS = 60L

    /** 常规思考停顿的上界 */
    const val MAX_THINK_MS = 220L

    /** 偶发犹豫的概率 */
    const val HESITATE_CHANCE = 0.05f

    /** 犹豫时长下界 */
    const val HESITATE_MIN_MS = 700L

    /** 犹豫时长上界 */
    const val HESITATE_MAX_MS = 1800L

    /** 偏斜指数：>1 让取值集中在短端（真人多数动作是快的，慢的是少数） */
    private const val SKEW = 1.8f

    /**
     * 一次常规思考停顿。
     *
     * `u^1.8` 把均匀分布压向短端：中位数约 86ms、均值约 117ms，
     * 而不是线性分布的中位 140ms —— 真人连续操作时多数动作是快的。
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
package app.dsh.mobile

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 生成「像手指划出来的」滑动轨迹（v1.6.31 起点，v1.6.32 加过冲/停顿/速度抖动）。
 *
 * ## 为什么不是直线
 *
 * 直线匀速滑动是最典型的机器特征：真人的手指受手腕结构、握持角度、屏幕摩擦影响，
 * 永远走不出标准直线，也从来不会匀速。检测方只要看「路径的垂直偏移是否恒为 0」
 * 就能一眼分出来。
 *
 * 这里叠的层：
 *
 * | 层 | 作用 |
 * |---|---|
 * | 主弧 | 垂直方向一次单峰偏移（`sin(πt)`），幅度 = 长度 × curvature |
 * | 反向小弧 | 再叠一个完整 S 波（`sin(2πt)`），让轨迹是轻微 S 而不是单一圆弧 |
 * | 低频微抖 | 正弦 + 随机相位，模拟手部不可控的小幅摆动 |
 * | 逐点噪声 | ±1px 级别的采样抖动 |
 * | 速度抖动 | 沿切线方向扰动点位 → 改变点间距 = 改变速度 |
 * | 中段停顿 | 插入几个重合点，手指在途中犹豫一下（真实拖拽常见） |
 * | 收手过冲 | 冲过目标再回弹到精确落点（拖拽类动作的真实手感） |
 *
 * 所有偏移在两端都严格归零（乘以 `sin(πt)`），所以**起止点永远精确** —— 拖滑块、
 * 拉进度条这类需要落点准确的动作，也能安全地带上弧线。
 *
 * ## 时长不是常数
 *
 * 固定 300ms 本身就是指纹：真人滑动的耗时跟距离强相关（Fitts 定律的直觉版），
 * 滑得越远越慢。见 [durationFor]。
 *
 * 抽成纯 Kotlin（无 Android 依赖）是为了能在 JVM 上断言：端点是否精确、
 * 是否真的偏离直线、沿轴进度是否单调、过冲后是否回到精确落点。
 */
object SwipePath {

    /** 采样点数（含两端）。24 段足够让 1000px 的滑动看不出折线。 */
    const val SAMPLES = 24

    /** 一个轨迹点。 */
    data class P(val x: Float, val y: Float)

    /** smoothstep：慢-快-慢的速度曲线。 */
    fun ease(t: Float): Float {
        val k = t.coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    /**
     * 按距离估滑动时长。
     *
     * 经验式：`120ms + 0.18 × 距离`，再乘 0.8~1.2 的随机系数。
     * 300px 约 174ms，1000px 约 300ms，2000px 约 480ms —— 与真人手感接近。
     * 夹在 120~900ms：太短系统会当成点按，太长用户会觉得卡。
     */
    fun durationFor(distancePx: Float, rnd: Random = Random.Default): Long {
        val d = distancePx.coerceAtLeast(0f)
        val base = 120f + 0.18f * d
        val k = 0.8f + rnd.nextFloat() * 0.4f
        return (base * k).toLong().coerceIn(120L, 900L)
    }

    /**
     * 生成轨迹点。
     *
     * @param curvature 主弧幅度占长度的比例（0.02 = 轻微，0.08 = 明显）
     * @param sCurve 反向小弧相对主弧的比例（0 = 纯单弧，0.35 = 轻微 S）
     * @param wobblePx 低频微抖的峰值像素
     * @param overshootPx 收手过冲的像素（0 = 不过冲）。过冲后**仍回到精确终点**
     * @param pauseChance 途中停顿的概率（0 = 不停）
     * @param speedJitterPx 沿切线扰动点位的像素（0 = 严格按 ease 分布）
     * @param samples 采样段数
     */
    fun build(
        x1: Float, y1: Float, x2: Float, y2: Float,
        curvature: Float = 0.06f,
        sCurve: Float = 0.35f,
        wobblePx: Float = 1.2f,
        overshootPx: Float = 0f,
        pauseChance: Float = 0f,
        speedJitterPx: Float = 0.8f,
        samples: Int = SAMPLES,
        rnd: Random = Random.Default,
    ): List<P> {
        val n = samples.coerceIn(2, 200)
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        // 退化情况（起止同点）：没有方向可言，直接给一条原地不动的轨迹
        if (len < 1f) return List(n + 1) { P(x1, y1) }

        // 垂直 / 切向单位向量：横向偏移走 perp，速度抖动走 dir
        val perpX = -dy / len
        val perpY = dx / len
        val dirX = dx / len
        val dirY = dy / len
        val sign = if (rnd.nextBoolean()) 1f else -1f
        val arcAmp = len * curvature.coerceIn(0f, 0.25f) * sign
        val sAmp = arcAmp * sCurve.coerceIn(0f, 1f) * -1f
        // 微抖频率取 1.5~2.5 个周期，相位随机 —— 固定频率本身也是特征
        val freq = 1.5f + rnd.nextFloat()
        val phase = rnd.nextFloat() * 2f * PI.toFloat()

        val out = ArrayList<P>(n + 10)
        for (i in 0..n) {
            val t = i.toFloat() / n
            val te = ease(t)
            // 端点权重：sin(πte) 在两端为 0，保证起止点不被偏移污染
            val w = sin(PI.toFloat() * te)
            val arc = arcAmp * w
            val s = sAmp * sin(2f * PI.toFloat() * te)
            val wob = sin(2f * PI.toFloat() * freq * te + phase) * wobblePx * w
            val noise = (rnd.nextFloat() - 0.5f) * 2f * 0.8f * w
            val off = arc + s + wob + noise
            val bx = x1 + dx * te
            val by = y1 + dy * te
            // 速度抖动：沿切线挪一点，点间距变了 = 速度变了。两端不动。
            // 幅度必须**小于上一点的间距**，否则轨迹会往回走 —— 起点附近 ease 的步长
            // 极小（smoothstep 起步导数接近 0），不夹就会出现负向抖动，
            // 系统会把回退识别成二次触摸/手抖。
            var along = if (i == 0 || i == n) 0f else (rnd.nextFloat() - 0.5f) * 2f * speedJitterPx * w
            if (i > 0 && along != 0f) {
                val px = out[i - 1].x
                val py = out[i - 1].y
                val step = hypot((bx - px).toDouble(), (by - py).toDouble()).toFloat()
                val cap = step * 0.3f
                if (along > cap) along = cap
                if (along < -cap) along = -cap
            }
            out.add(P(bx + perpX * off + dirX * along, by + perpY * off + dirY * along))
        }

        // 收手过冲：冲过去再回到精确落点。最后一点必须是 (x2,y2)。
        if (overshootPx > 1f) {
            for (f in floatArrayOf(1.0f, 0.62f, 0.28f, 0.08f, 0.0f)) {
                out.add(P(x2 + dirX * overshootPx * f, y2 + dirY * overshootPx * f))
            }
        }

        // 中段停顿：插入几个重合点 = 手指停了一下。放在中段，不碰两端。
        if (pauseChance > 0f && rnd.nextFloat() < pauseChance) {
            val lo = (out.size * 0.3f).toInt().coerceAtLeast(1)
            val hi = (out.size * 0.7f).toInt().coerceAtMost(out.size - 2)
            if (hi > lo) {
                val at = lo + rnd.nextInt(hi - lo)
                val hold = out[at]
                repeat(3) { out.add(at, hold) }
            }
        }
        return out
    }

    /** 摊平成 [x0,y0,x1,y1,...]，给绘制层用（省一次对象数组分配）。 */
    fun flatten(pts: List<P>): FloatArray {
        val out = FloatArray(pts.size * 2)
        pts.forEachIndexed { i, p ->
            out[i * 2] = p.x
            out[i * 2 + 1] = p.y
        }
        return out
    }

    /** 轨迹相对直线段的最大垂直偏移（像素）。用来断言「真的不是直线」。 */
    fun maxDeviation(pts: List<P>, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        if (pts.isEmpty()) return 0f
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1f) return 0f
        val nx = -dy / len
        val ny = dx / len
        return pts.maxOf { p -> kotlin.math.abs((p.x - x1) * nx + (p.y - y1) * ny) }
    }

    /** 轨迹沿行进方向的最远点（相对起点）。用来断言过冲真的发生了。 */
    fun maxReach(pts: List<P>, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1f) return 0f
        val ux = dx / len
        val uy = dy / len
        return pts.maxOf { p -> (p.x - x1) * ux + (p.y - y1) * uy }
    }
}
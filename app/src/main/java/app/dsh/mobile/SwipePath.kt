package app.dsh.mobile

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 生成「像手指划出来的」滑动轨迹（v1.6.31）。
 *
 * ## 为什么不是直线
 *
 * 直线匀速滑动是最典型的机器特征：真人的手指受手腕结构、握持角度、屏幕摩擦影响，
 * 永远走不出标准直线，也从来不会匀速。检测方只要看「路径的垂直偏移是否恒为 0」
 * 就能一眼分出来。
 *
 * 这里叠四层：
 *
 * | 层 | 作用 |
 * |---|---|
 * | 主弧 | 垂直方向一次单峰偏移（`sin(πt)`），幅度 = 长度 × curvature |
 * | 反向小弧 | 再叠一个完整 S 波（`sin(2πt)`），让轨迹是轻微 S 而不是单一圆弧 |
 * | 低频微抖 | 正弦 + 随机相位，模拟手部不可控的小幅摆动 |
 * | 逐点噪声 | ±1px 级别的采样抖动 |
 *
 * 所有偏移在两端都严格归零（乘以 `sin(πt)`），所以**起止点永远精确** —— 拖滑块、
 * 拉进度条这类需要落点准确的动作，也能安全地带上弧线。
 *
 * ## 速度
 *
 * `GestureDescription` 沿路径**匀速**移动，所以速度只能靠点密度表达：这里用
 * smoothstep 把采样参数挤向两端（端点多、中间少）= 起手慢、中间快、收手慢。
 *
 * 抽成纯 Kotlin（无 Android 依赖）是为了能在 JVM 上断言：端点是否精确、
 * 是否真的偏离直线、沿轴进度是否单调。
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
     * 生成轨迹点。
     *
     * @param curvature 主弧幅度占长度的比例（0.02 = 轻微，0.08 = 明显）
     * @param sCurve 反向小弧相对主弧的比例（0 = 纯单弧，0.35 = 轻微 S）
     * @param wobblePx 低频微抖的峰值像素
     * @param samples 采样段数
     */
    fun build(
        x1: Float, y1: Float, x2: Float, y2: Float,
        curvature: Float = 0.06f,
        sCurve: Float = 0.35f,
        wobblePx: Float = 1.2f,
        samples: Int = SAMPLES,
        rnd: Random = Random.Default,
    ): List<P> {
        val n = samples.coerceIn(2, 200)
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        // 退化情况（起止同点）：没有方向可言，直接给一条原地不动的轨迹
        if (len < 1f) return List(n + 1) { P(x1, y1) }

        // 垂直单位向量：所有偏移都加在这个方向上
        val perpX = -dy / len
        val perpY = dx / len
        val sign = if (rnd.nextBoolean()) 1f else -1f
        val arcAmp = len * curvature.coerceIn(0f, 0.25f) * sign
        val sAmp = arcAmp * sCurve.coerceIn(0f, 1f) * -1f
        // 微抖频率取 1.5~2.5 个周期，相位随机 —— 固定频率本身也是特征
        val freq = 1.5f + rnd.nextFloat()
        val phase = rnd.nextFloat() * 2f * PI.toFloat()

        return List(n + 1) { i ->
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
            P(bx + perpX * off, by + perpY * off)
        }
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
}
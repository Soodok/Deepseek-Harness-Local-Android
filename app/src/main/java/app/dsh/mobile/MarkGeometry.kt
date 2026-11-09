package app.dsh.mobile

/**
 * 标记的几何与时长（v1.6.29）。
 *
 * 抽出来单独一个文件，只因为它**不含任何 Android 依赖** —— 这样插值、
 * 时长这类容易算错的地方能在 JVM 上直接断言，不必开模拟器看图。
 *
 * 绘制本身（Canvas / 窗口）仍在 [PointMarker] 里。
 */
object MarkGeometry {

    /** 动作结束后标记还要停留多久，让眼睛来得及跟过去。 */
    const val TAIL_MS = 520L

    /** 动作时长的上限：再长的滑动/长按也只按这个算，防止标记挂太久。 */
    const val MAX_ACTION_MS = 20_000L

    /** 线性插值，t 超出 [0,1] 会被夹住（手势时长与实际回放对不齐时兜底）。 */
    fun lerp(from: Float, to: Float, t: Float): Float {
        val k = t.coerceIn(0f, 1f)
        return from + (to - from) * k
    }

    /** 已过时间 → 进度 [0,1]。时长非正直接算走完，避免除零。 */
    fun progress(elapsedMs: Long, durMs: Long): Float =
        if (durMs <= 0L) 1f else (elapsedMs.toFloat() / durMs).coerceIn(0f, 1f)

    /**
     * 长按的环半径：从外圈收向内圈。
     *
     * 为什么是「收」而不是「扩散」：扩散读起来像「刚点了一下」（点击就是扩散环），
     * 收缩才对应「一直按着、越按越实」，两种动作一眼能分开。
     */
    fun holdRadius(outer: Float, inner: Float, t: Float): Float = lerp(outer, inner, t)

    /** 标记总存活时长 = 动作时长（夹到上限）+ 收尾停留。 */
    fun lifeMs(actionMs: Long, tailMs: Long = TAIL_MS): Long =
        actionMs.coerceIn(0L, MAX_ACTION_MS) + tailMs
}
package app.dsh.mobile

/**
 * 一次滚动手势的几何（纯 Kotlin，可在 JVM 上断言）。
 *
 * ## 为什么单独抽出来
 * 原来这段算术散在 LocalLoopExecutor 的 scroll 分支里：起点写死 0.65h、
 * 终点 0.65h - dist。两个问题——
 *   1. 默认只走 0.6h，长列表一屏一屏挪得慢（用户反馈「滑动距离可以再多一点」）；
 *   2. 一旦有人把 distance 调大，终点会算出**负数**（0.65h - 0.8h < 0），
 *      手势落到屏幕外被裁掉，实际滚动量反而变小，而且看不出来为什么。
 * 抽成纯函数之后，这两件事都能被测试钉住。
 *
 * ## 边距怎么定
 * 手指不许落在屏幕最上/最下 EDGE_FRACTION 以内：那两圈在多数机器上是状态栏、
 * 手势导航条、或者「下拉通知」的热区。落在那里轻则被系统吃掉，重则触发别的动作。
 * 于是可用行程 = h * (1 - 2 * EDGE_FRACTION)，正好等于 DEFAULT_FRACTION。
 */
object ScrollGeometry {

    /** 默认一次滚动覆盖屏幕高度的比例。0.72 = 边距吃掉 0.14 + 0.14 之后的全部行程。 */
    const val DEFAULT_FRACTION = 0.72f

    /** 手指起止点距屏幕上下边缘的安全比例。 */
    const val EDGE_FRACTION = 0.14f

    /** 再小的行程没意义（手势抖动会把它吃掉）。 */
    const val MIN_TRAVEL_PX = 100

    /** 一次滚动手势：手指从 y1 划到 y2，x 取屏幕中线。 */
    data class Step(val x: Float, val y1: Float, val y2: Float)

    /** 屏幕上允许的最大行程（px）。 */
    fun maxTravel(height: Int): Int =
        (height * (1f - 2f * EDGE_FRACTION)).toInt().coerceAtLeast(MIN_TRAVEL_PX)

    /**
     * 算出一次滚动手势。
     *
     * @param direction 按**内容方向**命名（与 loop 的约定一致）：down = 看下面的内容
     *   （手指从下往上划）；up = 往回看。其它值按 down 处理。
     * @param distancePx 想走的像素；<= 0 表示用 DEFAULT_FRACTION，超过 maxTravel 会被夹住。
     */
    fun step(width: Int, height: Int, direction: String, distancePx: Int = 0): Step {
        val travel = (if (distancePx > 0) distancePx else (height * DEFAULT_FRACTION).toInt())
            .coerceIn(MIN_TRAVEL_PX, maxTravel(height))
        val near = (height * EDGE_FRACTION).toInt().toFloat()
        val far = (height - near)
        val x = width / 2f
        return if (direction.equals("up", ignoreCase = true)) {
            Step(x, near, near + travel)
        } else {
            Step(x, far, far - travel)
        }
    }
}

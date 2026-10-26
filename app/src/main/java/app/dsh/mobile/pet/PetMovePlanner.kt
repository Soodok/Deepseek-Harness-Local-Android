package app.dsh.mobile.pet

import kotlin.math.abs
import kotlin.random.Random

/**
 * 移动（moves）的几何规划：桌面版 dsh-pet `src/shared/motion.ts` 的 Kotlin 移植。
 *
 * 要点：
 *  - 单次行进距离随宠物大小缩放：config 里的 minDist/maxDist 是基准宽度
 *    （[PET_REF_WIDTH]=462px 画布）下的像素，运行时乘 `size / 462`；
 *  - **坐标口径是窗口左上角**，且窗口就是角色矩形（Service 按素材量出的不透明
 *    包围盒裁过窗），所以"窗口贴到屏幕边"就等于"身体贴到屏幕边"，不需要再算
 *    素材的透明边 allowance；
 *  - 只有水平位移（Y 不动），与上游一致。
 */
object PetMovePlanner {

    /** 宠物宽度的换算基准（放大的等比缩放参照）。 */
    const val PET_REF_WIDTH = 462.0

    data class Plan(
        /** 目标位置（窗口左上角 x）。 */
        val targetX: Int,
        /** 本段行进距离（px，已带方向符号外的正值）。 */
        val distance: Double,
        /** 窗口 x 的安全区间，供调用方夹取。 */
        val minX: Int,
        val maxX: Int,
    )

    /**
     * 规划一次移动。返回 null = 这一步走不了（目标出界），调用方应降级为随机动作。
     *
     * @param cx        当前窗口左上角 x（= 身体左边，窗口未裁边时同样是外框左边）
     * @param viewW     窗口宽度（= 身体宽度）
     * @param screenW   屏幕可用宽度
     * @param dir       -1 向左 / +1 向右
     * @param action    选中的移动动作（提供 minDist/maxDist/margin）
     * @param distScale 行进距离缩放比 = 画布宽度 / PET_REF_WIDTH
     */
    fun plan(
        cx: Int,
        viewW: Int,
        screenW: Int,
        dir: Int,
        action: PetMoveAction,
        distScale: Double,
    ): Plan? {
        if (viewW <= 0 || screenW <= 0) return null
        val min = action.minDist * distScale
        val max = action.maxDist * distScale
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        val distance = if (hi - lo < 0.5) lo else lo + Random.nextDouble() * (hi - lo)
        if (distance <= 0.0) return null

        // margin 语义 =「身体到屏幕边缘的安全距」；窗口即身体，直接夹窗口左上角
        val leftBound = action.margin
        val rightBound = screenW - action.margin - viewW
        if (rightBound <= leftBound) return null

        val target = cx + dir * distance
        if (target < leftBound || target > rightBound) return null

        return Plan(
            targetX = target.toInt().coerceIn(leftBound.toInt(), rightBound.toInt()),
            distance = abs(target - cx),
            minX = leftBound.toInt(),
            maxX = rightBound.toInt(),
        )
    }

    /**
     * 时间轴线性插值：给定动画已播秒数与动作参数，返回当前应在的 x。
     *
     * 与上游 `startMoveDrive` 完全同口径 —— **线性、无 ease**，以动画播放时间为基准：
     *  - t ≤ leadSec          → 停在起点（起步动作站稳）
     *  - leadSec < t < duration-tailSec → 线性推进
     *  - t ≥ duration-tailSec → 停在终点（收尾动作）
     */
    fun interpolateX(
        startX: Int,
        plan: Plan,
        action: PetMoveAction,
        elapsedSec: Double,
        durationSec: Double,
    ): Int {
        val travel = action.travelWindow(durationSec)
        val x = when {
            elapsedSec <= action.leadSec -> startX.toDouble()
            elapsedSec >= durationSec - action.tailSec -> plan.targetX.toDouble()
            else -> {
                val p = ((elapsedSec - action.leadSec) / travel).coerceIn(0.0, 1.0)
                startX + (plan.targetX - startX) * p
            }
        }
        return x.toInt()
    }
}

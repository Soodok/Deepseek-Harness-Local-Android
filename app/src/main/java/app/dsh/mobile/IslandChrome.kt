package app.dsh.mobile

import kotlin.math.roundToInt

/** 灵动岛停靠在屏幕哪条边。 */
enum class IslandDock { TOP, LEFT, RIGHT }

/**
 * 灵动岛靠边展开的几何计算（v1.5.6）。
 *
 * 这里只做纯算术：给定停靠边、屏幕尺寸、密度、内容高度，算出窗口该摆在哪、
 * 多大。不碰任何 Android API，因此可以在 JVM 里直接单测（scripts/jvm）。
 *
 * 设计口径（对应用户选定的 A 方案）：
 *  · 收起态贴长边 —— 26×44dp 竖条，贴边侧圆角 0，外凸侧 10dp；
 *  · 展开态贴长边 —— 252dp 宽，高度由内容决定但不超过屏高 70%，超出内部滚动；
 *  · 侧边停靠时纵向位置夹在 [48dp, H-48dp-高度] 之间：
 *    屏幕四角有圆角，贴边窗口压到角上会被裁掉一截；
 *  · 顶部停靠维持原有实现（顶栏横向胶囊），不在这里计算。
 */
object IslandChrome {

    const val COLLAPSED_W_DP = 26
    const val COLLAPSED_H_DP = 44
    const val PANEL_W_DP = 252
    const val CORNER_OUTER_DP = 10
    const val CORNER_PANEL_DP = 22

    /** 屏幕四角圆角的安全距离（dp）：贴边窗口不能压进圆角区。 */
    const val EDGE_SAFE_DP = 48

    /** 展开面板高度上限占屏高比例，超出部分内部滚动。 */
    const val MAX_HEIGHT_RATIO = 0.70f

    data class Metrics(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun px(dp: Int, density: Float): Int = (dp * density).roundToInt()

    /**
     * 收起态（贴左/右边）的窗口位置与尺寸。
     *
     * @param yCenter 期望的垂直中心（px）。会夹到圆角安全区内。
     */
    fun collapsed(
        dock: IslandDock,
        screenW: Int,
        screenH: Int,
        density: Float,
        yCenter: Int,
    ): Metrics {
        val w = px(COLLAPSED_W_DP, density)
        val h = px(COLLAPSED_H_DP, density)
        val safe = px(EDGE_SAFE_DP, density)
        val y = (yCenter - h / 2).coerceIn(safe, (screenH - h - safe).coerceAtLeast(safe))
        val x = when (dock) {
            IslandDock.LEFT -> 0
            IslandDock.RIGHT -> (screenW - w).coerceAtLeast(0)
            IslandDock.TOP -> ((screenW - w) / 2).coerceAtLeast(0)
        }
        return Metrics(x, y, w, h)
    }

    /**
     * 展开态（贴左/右边）的窗口位置与尺寸。
     *
     * @param contentH 面板内容高度（px）。超过屏高 70% 时截断到上限，由内部 ScrollView 滚动。
     */
    fun expanded(
        dock: IslandDock,
        screenW: Int,
        screenH: Int,
        density: Float,
        contentH: Int,
        yCenter: Int,
    ): Metrics {
        val w = px(PANEL_W_DP, density)
        val safe = px(EDGE_SAFE_DP, density)
        val maxH = (screenH * MAX_HEIGHT_RATIO).roundToInt()
        val h = contentH.coerceIn(px(120, density), maxH)
        val y = (yCenter - h / 2).coerceIn(safe, (screenH - h - safe).coerceAtLeast(safe))
        val x = when (dock) {
            IslandDock.RIGHT -> (screenW - w).coerceAtLeast(0)
            else -> 0
        }
        return Metrics(x, y, w, h)
    }

    /** 展开面板是否被高度上限截断（需要内部滚动）。 */
    fun needsScroll(screenH: Int, density: Float, contentH: Int): Boolean {
        val maxH = (screenH * MAX_HEIGHT_RATIO).roundToInt()
        return contentH > maxH
    }

    /** 外凸侧圆角（px）：收起态用 10dp，展开态用 22dp。 */
    fun outerRadiusPx(expanded: Boolean, density: Float): Int =
        px(if (expanded) CORNER_PANEL_DP else CORNER_OUTER_DP, density)
}

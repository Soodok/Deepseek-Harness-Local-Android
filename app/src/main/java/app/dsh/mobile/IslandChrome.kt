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

/**
 * 灵动岛配色（v1.7.11）：暗色是原来的固定值，浅色是新补的一套。
 *
 * 为什么放在这里而不是 IslandSidePanel：这些只是 ARGB 常量，不碰 Android API，
 * 于是能在 JVM 里直接断言「浅色底够亮、字够深」这类不变量（见 scripts/jvm）。
 *
 * 取值口径：
 *  · 暗色沿用旧值，一个都不改（用户已经看惯）；
 *  · 浅色面板用 #F7F9FC（不是纯白：页面就是纯白，纯白面板没有边界），
 *    把手用 #F1F5F9 —— 比面板再深一点点，压在白色网页上也看得见；
 *  · 强调色在浅底上要换深一档（#7DD3FC 在白底上对比度只有 1.6:1，几乎看不见），
 *    取 sky-700 #0369A1；
 *  · 分隔线暗色用 13% 白，浅色用 8% 黑，观感对齐。
 */
object IslandPalette {

    data class Colors(
        /** 展开面板底 */
        val panel: Int,
        /** 收起态把手 / 拖离边缘时的浮标底 */
        val handle: Int,
        val title: Int,
        val body: Int,
        val muted: Int,
        val accent: Int,
        val divider: Int,
        /** 状态圆点 */
        val dot: Int,
    )

    val DARK = Colors(
        panel = 0xFF000000.toInt(),
        handle = 0xFF000000.toInt(),
        title = 0xFFF2F5F9.toInt(),
        body = 0xFFE6EAF0.toInt(),
        muted = 0xFF6B7684.toInt(),
        accent = 0xFF7DD3FC.toInt(),
        divider = 0x22FFFFFF,
        dot = 0xFF7DD3FC.toInt(),
    )

    val LIGHT = Colors(
        // 面板不用纯白：App 页面本身就是纯白，纯白面板在浅色主题下没有边界，
        // 像「内容浮在页面上」。取 surface-container 那一档灰白，既有边界又不脏。
        panel = 0xFFF7F9FC.toInt(),
        handle = 0xFFF1F5F9.toInt(),
        title = 0xFF0E1116.toInt(),
        body = 0xFF1B232E.toInt(),
        muted = 0xFF5A6472.toInt(),
        accent = 0xFF0369A1.toInt(),
        divider = 0x14000000,
        dot = 0xFF0369A1.toInt(),
    )

    fun of(dark: Boolean): Colors = if (dark) DARK else LIGHT

    /** 相对亮度（0..1，sRGB 近似）。只给断言与对比度检查用。 */
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = ch((argb shr 16) and 0xFF)
        val g = ch((argb shr 8) and 0xFF)
        val b = ch(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG 对比度（1..21）。 */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}

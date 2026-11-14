package app.dsh.mobile

import kotlin.math.roundToInt

/** 灵动岛停靠在屏幕哪条边。 */
enum class IslandDock { TOP, LEFT, RIGHT, BOTTOM }

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
        val horizontalBottom = dock == IslandDock.BOTTOM
        val w = px(if (horizontalBottom) COLLAPSED_H_DP else COLLAPSED_W_DP, density)
        val h = px(if (horizontalBottom) COLLAPSED_W_DP else COLLAPSED_H_DP, density)
        val safe = px(EDGE_SAFE_DP, density)
        val y = if (horizontalBottom) (screenH - h).coerceAtLeast(0) else
            (yCenter - h / 2).coerceIn(safe, (screenH - h - safe).coerceAtLeast(safe))
        val x = when (dock) {
            IslandDock.LEFT -> 0
            IslandDock.RIGHT -> (screenW - w).coerceAtLeast(0)
            IslandDock.TOP, IslandDock.BOTTOM -> ((screenW - w) / 2).coerceAtLeast(0)
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
        val w = px(PANEL_W_DP, density).coerceAtMost(screenW.coerceAtLeast(0))
        val safe = px(EDGE_SAFE_DP, density)
        val maxH = (screenH * MAX_HEIGHT_RATIO).roundToInt().coerceAtLeast(0)
        val h = contentH.coerceIn(minOf(px(120, density), maxH), maxH)
        val y = (yCenter - h / 2).coerceIn(safe, (screenH - h - safe).coerceAtLeast(safe))
        val x = when (dock) {
            IslandDock.RIGHT -> (screenW - w).coerceAtLeast(0)
            else -> 0
        }
        return Metrics(x, y, w, h)
    }

    /**
     * 收起态把手的**唯一几何来源**（v1.7.13）。
     *
     * 之前这段算式写在 MainActivity.placeCollapsedHandle 里，而 applyIslandInsets
     * 又会按「挖孔对齐」把同一个 View 的 layoutParams 改回 26×26 正方形 + 水平居中
     * —— 一次 insets 回调就能把贴边把手从屏幕边上挪到顶部中央、尺寸也从 26×44
     * 压成正方形。用户看到的就是「缩小之后直接不见了」（他贴的是右边）。
     *
     * 把算术收敛到这里有两个好处：Activity 只负责套用，insets 不再有机会改写形状；
     * 而且它是纯函数，能在宿主 JVM 上断言（真机重现需要特定 insets 时序）。
     *
     * @param safeTopPx 顶部安全边（状态栏 / 挖孔下沿）。贴顶时 y 取它和 10dp 的较大值。
     * @param safeBottomPx Bottom inset in pixels; negative values are treated as zero.
     * @param ratio     沿边位置（0..1）——只影响沿边那一维，另一维贴边为 0。
     */
    fun collapsedRect(
        dock: IslandDock,
        parentW: Int,
        parentH: Int,
        density: Float,
        ratio: Float,
        safeTopPx: Int = 0,
        safeBottomPx: Int = 0,
    ): Metrics {
        val horizontal = dock == IslandDock.TOP || dock == IslandDock.BOTTOM
        val wDp = if (horizontal) COLLAPSED_H_DP else COLLAPSED_W_DP
        val hDp = if (horizontal) COLLAPSED_W_DP else COLLAPSED_H_DP
        val w = px(wDp, density)
        val h = px(hDp, density)
        val safe = px(EDGE_SAFE_DP, density)
        val topBase = maxOf(px(10, density), safeTopPx)
        val r = ratio.coerceIn(0f, 1f)
        return when (dock) {
            IslandDock.TOP -> Metrics(
                x = (r * (parentW - w)).toInt().coerceIn(0, (parentW - w).coerceAtLeast(0)),
                y = topBase,
                w = w,
                h = h,
            )
            IslandDock.BOTTOM -> Metrics(
                x = (r * (parentW - w)).toInt().coerceIn(0, (parentW - w).coerceAtLeast(0)),
                y = (parentH - h - safeBottomPx.coerceAtLeast(0)).coerceAtLeast(0),
                w = w,
                h = h,
            )
            IslandDock.LEFT -> Metrics(
                x = 0,
                y = (safe + r * ((parentH - h - safe) - safe).coerceAtLeast(0)).toInt(),
                w = w,
                h = h,
            )
            IslandDock.RIGHT -> Metrics(
                x = (parentW - w).coerceAtLeast(0),
                y = (safe + r * ((parentH - h - safe) - safe).coerceAtLeast(0)).toInt(),
                w = w,
                h = h,
            )
        }
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
 *  · 浅色面板用 #F7F9FC（不是纯白：页面就是纯白，纯白面板没有边界）；
 *  · **把手（handle）两种主题都用深色**（v1.7.13）：把手不等于面板 —— 面板只
 *    贴在自己的页面上，而把手是压在**任意网页**上的控制物，网页底色不可控。
 *    v1.7.11 曾把浅色把手配成 #F1F5F9（照"浅色面板"的思路），结果压在
 *    #F5F7FA 页面上只有 1.02:1、压在纯白上 1.10:1 —— 用户报「点隐藏后
 *    缩小态按钮不显示」，其实按钮在，只是完全看不见（只剩内部那个 8dp 核心点
 *    隐约可见）。深色主题没暴露是因为黑把手在白页有 21:1；
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
        /** 「已选中 / 成功」绿（勾选标记、选中行描边） */
        val ok: Int,
        /** 选中行的填充（同色低透明度） */
        val okSoft: Int,
        /** 警示文字（容器一个都没下时的提示） */
        val warn: Int,
        /**
         * 收起态把手的描边色（v1.7.13）。
         *
         * 为什么要描边而不是把底色改深：把手压在**任意网页**上，浅色主题下
         * 页面底色可能和把手几乎同色（实测 #F1F5F9 对 #F5F7FA 只有 1.02:1，
         * 对纯白 1.10:1），只靠填充色无论怎么选都会在某种网页上撞色。
         * 而用户明确不要改填充色（"浅色模式下不要改灵动岛收起态颜色，很难看"），
         * 所以边界改由一条细描边承担 —— 这也是 XML 原始
         * drawable/bg_dynamic_island_dot.xml 的做法（0.5dp stroke + 外环），
         * 代码生成的背景把那一层丢了。
         *
         * 取 0 表示不描边（深色主题沿用原来的纯黑，不做任何改动）。
         */
        val outline: Int = 0,
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
        ok = 0xFF22A06B.toInt(),
        okSoft = 0x3322A06B,
        warn = 0xFFB45309.toInt(),
        outline = 0,   // 深色沿用旧观感，一个像素都不改
    )

    val LIGHT = Colors(
        // 面板不用纯白：App 页面本身就是纯白，纯白面板在浅色主题下没有边界，
        // 像「内容浮在页面上」。取 surface-container 那一档灰白，既有边界又不脏。
        panel = 0xFFF7F9FC.toInt(),
        // ⚠️ v1.7.13：把手**不能是浅灰**。它压在任意网页上，#F1F5F9 对
        // #F5F7FA 页面 1.02:1、对纯白 1.10:1 —— 视觉上就是"消失"（用户报的
        // 「点隐藏后缩小态按钮不显示」）。取 slate-700，白底 10.35:1、
        // 浅灰底同量级，任何背景下都有边界；与深色主题观感也一致（都是深色把手）。
        // ⚠️ v1.7.13：恢复原来的浅色（用户明确"浅色模式下不要改灵动岛收起态颜色，
        // 很难看"）。它和浅色网页几乎同色，边界改由下面的 outline 承担。
        handle = 0xFFF1F5F9.toInt(),
        title = 0xFF0E1116.toInt(),
        body = 0xFF1B232E.toInt(),
        muted = 0xFF5A6472.toInt(),
        accent = 0xFF0369A1.toInt(),
        divider = 0x14000000,
        dot = 0xFF0369A1.toInt(),
        // 浅底上的绿要压深一档：#22A06B 在 #F7F9FC 上只有 2.4:1，勾选标记会看不清
        ok = 0xFF15803D.toInt(),
        okSoft = 0x1F16A34A,
        // 警示色用 amber-800：浅底上 amber-700 偏亮，正文级文字要 4.5:1
        warn = 0xFF92400E.toInt(),
        // 对 handle 3.29:1、对纯白 3.61:1 —— 满足 UI 元素边界 3:1，又不至于成黑框
        outline = 0xFF7B8899.toInt(),
    )

    fun of(dark: Boolean): Colors = if (dark) DARK else LIGHT

    /** 相对亮度（0..1）。转发到 [ContrastColor] —— 同一算法不要有三份实现。 */
    fun luminance(argb: Int): Double = ContrastColor.luminance(argb)

    /** WCAG 对比度（1..21）。 */
    fun contrast(a: Int, b: Int): Double = ContrastColor.contrast(a, b)
}
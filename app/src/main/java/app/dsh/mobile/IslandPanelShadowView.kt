package app.dsh.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 侧边灵动岛面板的边缘投影（v1.7.14，用户要求「加一层边缘阴影，不描边」）。
 *
 * ## 为什么要单独一个 View
 * 面板的圆角与底色画在它自己的 background 上，而 Drawable 只能画在 View 边界内 ——
 * 想投到面板外面就必然被裁。所以阴影另起一块正好「面板 + 预留边」大小的视图，
 * 垫在面板下面，各司其职。
 *
 * ## 为什么用 setShadowLayer 而不是 elevation
 * 1. elevation 的投影颜色由系统定（浅色主题偏灰、深色主题几乎看不见），
 *    而面板压在**任意网页**上，底色不可控，必须自己拿色；
 * 2. elevation 的投影形状来自 outline，贴屏幕那一侧是直边，会沿着屏幕边缘
 *    投出一条多余的暗边；这里手工画路径，可以只给外凸侧圆角。
 *
 * ## 代价
 * setShadowLayer 的模糊在硬件画布上会被忽略（同 pet/PetBubbleDrawable.kt 的原因），
 * 因此这块视图强制走软件层。它只在面板展开期间存在，尺寸上限 252dp×(70% 屏高)，
 * 收起时随面板一起移除，不做常驻。
 *
 * 只投外凸侧与上下两侧：贴边侧（贴屏幕那一侧）不画 —— 那里本来就出屏，
 * 画了既看不见，又会在某些机型上沿边缘露出半像素暗线。
 */
internal class IslandPanelShadowView(
    context: Context,
    /** true = 面板贴屏幕左边（阴影只往右投）；false = 贴右边（阴影只往左投）。 */
    private val dockLeft: Boolean,
    /** 面板相对本视图的内缩（左、上、右、下），阴影就围绕这个矩形外侧铺开。 */
    private val insetLeft: Int,
    private val insetTop: Int,
    private val insetRight: Int,
    private val insetBottom: Int,
    /** 外凸侧圆角（与面板一致，22dp）。贴边侧恒为直角。 */
    private val radiusPx: Float,
    /** 模糊半径。 */
    blurPx: Float,
    /** 纵向偏移：正值向下，模拟光源在上方。 */
    dyPx: Float,
    /** 面板填充色（阴影的形状由它实心绘制，最终被真面板完全覆盖）。 */
    fillColor: Int,
    shadowColor: Int,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        setShadowLayer(blurPx, 0f, dyPx, shadowColor)
    }

    private val path = Path()
    private val rect = RectF()

    init {
        // 硬件画布会忽略 setShadowLayer 的模糊（只剩一个硬边矩形），必须软件层。
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        // 阴影本身要能画到绘制区域之外是不可能的 —— 它被限制在本 View 边界内。
        // 调用方必须保证预留边 ≥ blurPx，否则外沿会被切出一条平口。
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(
            insetLeft.toFloat(),
            insetTop.toFloat(),
            (width - insetRight).toFloat(),
            (height - insetBottom).toFloat(),
        )
        if (rect.width() <= 0f || rect.height() <= 0f) return
        // 贴边侧直角、外凸侧圆角 —— 与 IslandSidePanel.panelBackground() 的 cornerRadii 同序
        val radii = if (dockLeft) {
            floatArrayOf(0f, 0f, radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f)
        } else {
            floatArrayOf(radiusPx, radiusPx, 0f, 0f, 0f, 0f, radiusPx, radiusPx)
        }
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }
}

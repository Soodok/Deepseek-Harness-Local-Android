package app.dsh.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.dsh.mobile.Accent

/**
 * 引导页的「真机画面预览」控件（v1.7.0）。
 *
 * ## 为什么自绘而不是塞一堆子 View
 *
 * 预览要表达的是**布局形态**（竖屏单列 / 横屏双栏）与**明暗对比**，
 * 不是真的界面。用子 View 拼一个有 8 个元素的假界面，每次切换方向都要
 * 重新 requestLayout 一整棵子树，而且五种强调色、明暗两套底色都要跟着重涂。
 * 自绘一个 View 只有三条线和一个标题条，切形态只需改一个布尔值再 invalidate。
 *
 * ## 三个刻意保留的细节
 *
 * 1. **横屏不是把竖屏转 90°**：真机横屏是「内容区变成双栏」，
 *    所以这里画的是左右两列，而不是把单列图旋转。用户看的是后果，不是动画。
 * 2. **圆角与宿主卡片一致（10dp）**：预览框本身要看起来像「一块屏幕」，
 *    但又不能像真的手机外壳 —— 太重会喧宾夺主。
 * 3. **强调色实时读取**：[Accent.current] 每次 onDraw 重新取，
 *    用户在第 4 页换配色时，第 2 页的预览条也跟着变色，不必重建。
 */
class LayoutPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** true = 横屏双栏形态；false = 竖屏单列形态。 */
    var landscape: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /**
     * true = 用浅色底画（白底 + 浅灰线），false = 用深色底。
     *
     * 主题页要表达的正是「同一个界面在明/暗两套配色下长什么样」，
     * 所以这里不能直接读当前 Activity 的 R.color.bg —— 用户还没选完，
     * 界面已经是深色/浅色了，预览必须能画出**另一种**对照。
     */
    var light: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density

    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    /** 预览的比例：竖屏 9:16，横屏 16:9。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val ratio = if (landscape) 16f / 9f else 9f / 16f
        // 高度给多少就用多少，宽度由比例反推 —— 这样在横向拉长的容器里
        // 预览会自己缩成一个小屏，而不是被拉变形。
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        val h = if (availH > 0) availH else (110 * d).toInt()
        val w = (h * ratio).toInt().coerceAtMost(availW)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = 10f * d
        val pad = 5f * d

        // 一套固定的明/暗对照色：不读 R.color，因为预览要画的可能正是
        // 「当前没在用的那一套」（主题页选浅色时，界面已经是浅色了）。
        val paper = if (light) COLOR_PAPER_LIGHT else COLOR_PAPER_DARK
        cardTone = if (light) COLOR_CARD_LIGHT else COLOR_CARD_DARK
        lineTone = if (light) COLOR_LINE_LIGHT else COLOR_LINE_DARK

        // 屏幕底色：比卡片稍亮一档，读作「一块屏幕」而不是「又一张卡片」
        frame.color = paper
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, frame)

        // 顶部标题条：强调色柔底
        val accent = Accent.current(context).argb
        accentPaint.color = (accent and 0x00FFFFFF) or 0x33000000
        val topH = h * 0.13f
        rect.set(pad, pad, w - pad, pad + topH)
        canvas.drawRoundRect(rect, 4f * d, 4f * d, accentPaint)

        // 内容占位线
        line.color = lineTone
        val bodyTop = pad + topH + 8f * d
        val bodyBottom = h - pad - 6f * d
        if (landscape) {
            // 双栏：左右各一组线，右列多一块「内容卡」
            val gutter = 6f * d
            val colW = (w - pad * 2 - gutter) / 2f
            drawColumn(canvas, pad, bodyTop, colW, bodyBottom, withCard = true)
            drawColumn(canvas, pad + colW + gutter, bodyTop, colW, bodyBottom, withCard = false)
        } else {
            drawColumn(canvas, pad + 4f * d, bodyTop, w - (pad + 4f * d) * 2, bodyBottom, withCard = true)
        }
    }

    /** 一列内容：三条宽度递减的线 + 一块内容卡。 */
    private fun drawColumn(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        bottom: Float,
        withCard: Boolean,
    ) {
        val lineH = 3f * d
        val gap = 5f * d
        var y = top
        listOf(0.85f, 0.62f, 0.45f).forEach { frac ->
            if (y + lineH > bottom) return@forEach
            rect.set(left, y, left + width * frac, y + lineH)
            canvas.drawRoundRect(rect, lineH / 2f, lineH / 2f, line)
            y += lineH + gap
        }
        if (withCard && y + gap < bottom) {
            line.color = cardTone
            rect.set(left, y + gap, left + width, bottom)
            canvas.drawRoundRect(rect, 5f * d, 5f * d, line)
            line.color = lineTone
        }
    }

    /** 内容卡底色，由 [onDraw] 按明/暗选定后传入 [drawColumn]。 */
    private var cardTone: Int = COLOR_CARD_DARK

    /** 占位线颜色，同上。 */
    private var lineTone: Int = COLOR_LINE_DARK

    // 不用 const val：0xFFF7F9FC.toInt() 不是编译期常量表达式
    private companion object {
        val COLOR_PAPER_LIGHT = 0xFFF7F9FC.toInt()
        val COLOR_PAPER_DARK = 0xFF161D29.toInt()
        val COLOR_CARD_LIGHT = 0xFFEDF1F6.toInt()
        val COLOR_CARD_DARK = 0xFF1B2330.toInt()
        val COLOR_LINE_LIGHT = 0xFFCFD8E3.toInt()
        val COLOR_LINE_DARK = 0xFF39465A.toInt()
    }
}

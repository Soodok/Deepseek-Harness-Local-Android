package app.dsh.mobile.pet

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 气泡底板：一颗「圆角矩形 + 小尾巴」的对话泡，样式对齐 dsh-pet 网页端
 * （`.dsh-pet-bubble`）：
 *  - 平涂 rgba(255,255,255,.92)，无渐变、无描边；
 *  - 圆角固定 0.77em（web `border-radius: calc(var(--f)*0.7692)`）；
 *  - 投影 `0 0.2em 0.77em rgba(0,0,0,.14)`（大而柔，浮在桌面上的一层纸片）；
 *  - 尾巴是**直角三角形**（web `::after` CSS border 三角）：宽 0.75em、高 0.37em，
 *    尖端横向位置可按角色中线设置。
 *
 * 这里把**整条轮廓画成一条闭合 Path**（上边 → 右上圆角 → 右边 → 右下圆角 →
 * 带尾巴的下边 → 左下圆角 → 左边 → 左上圆角），一次填充：
 * 轮廓连续，没有拼接缝；路径带抗锯齿，缩放后边缘是干净的灰度过渡。
 *
 * 尺寸全部由 [configure] 从字号派生（em 口径），所以字号一变，圆角、尾巴、
 * 投影同步缩放，不会出现「字长大了、圆角还是老的」。
 *
 * **需要在软件层渲染**（`View.setLayerType(LAYER_TYPE_SOFTWARE)`）：
 * `Paint.setShadowLayer` 的模糊投影在硬件画布上会被忽略。气泡窗口是一块静止的
 * 小窗，软件渲染的开销可以忽略。
 */
class PetBubbleDrawable : Drawable() {

    private val body = RectF()
    private val outline = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private var flip = Matrix()

    private var radius = 0f
    private var tailW = 0f
    private var tailH = 0f
    /**
     * 投影向外扩散的留白 px。气泡实体要在窗口里**内缩**这么多，
     * 否则模糊投影会被窗口边界切掉一圈（看着像描边断了）。
     */
    private var inset = 0f
    /** 尾巴尖端在底边上的横向位置（0~1，相对气泡宽度）。由 Service 按角色中线设置。 */
    private var tailFrac = 0.5f
    /** true = 尾巴朝上（气泡在宠物下方时）。 */
    private var tailUp = false

    /**
     * @param em 正文字号 px —— 所有尺寸的基准
     * @param bodyH 气泡**正文区**高度 px（不含尾巴），仅用于约束圆角上限
     */
    fun configure(em: Float, bodyH: Float) {
        fillPaint.color = FILL
        // web: border-radius: calc(var(--f)*0.7692)
        radius = (em * RADIUS_EM).coerceAtMost(bodyH * 0.5f).coerceAtLeast(em * 0.3f)
        // web ::after: border: calc(var(--f)*0.3736) —— 三角宽 2×0.3736、高 0.3736
        tailW = em * 0.7472f
        tailH = (em * 0.3736f).coerceAtLeast(1f)
        // 投影 blur 0.77em + 位移 0.2em → 留白要罩住整个模糊范围
        inset = (em * SHADOW_BLUR_EM + em * SHADOW_DY_EM).coerceAtLeast(em * 0.3f)
        // web box-shadow: 0 0.1978em 0.7692em rgba(0,0,0,.14)
        fillPaint.setShadowLayer(em * SHADOW_BLUR_EM, 0f, em * SHADOW_DY_EM, SHADOW)
        invalidateSelf()
    }

    /** 尾巴尖的横向位置：0 = 左边，1 = 右边。超出 [0.14,0.86] 会被夹住（避免戳穿圆角）。 */
    fun setTailFraction(f: Float) {
        val c = f.coerceIn(TAIL_MIN_FRAC, TAIL_MAX_FRAC)
        if (c != tailFrac) {
            tailFrac = c
            invalidateSelf()
        }
    }

    fun setTailUp(up: Boolean) {
        if (up != tailUp) {
            tailUp = up
            invalidateSelf()
        }
    }

    /** 尾巴占用的高度 px —— 调用方要用它把正文区抬起来、把窗口拉高。 */
    val tailHeight: Float get() = tailH

    /** 投影留白 px —— 调用方要把它加进内边距，并据此换算气泡实体在窗口里的位置。 */
    val shadowInset: Float get() = inset

    override fun onBoundsChange(bounds: Rect) {
        // 平涂色无 shader，无需随 bounds 重建
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || radius <= 0f) return
        // 正文区：尾巴朝下时占上半部分，朝上时让出顶上那一条；四边都要内缩出投影的余量
        if (tailUp) {
            body.set(
                b.left + inset,
                b.top + inset + tailH,
                b.right - inset,
                b.bottom - inset,
            )
        } else {
            body.set(
                b.left + inset,
                b.top + inset,
                b.right - inset,
                b.bottom - inset - tailH,
            )
        }
        buildOutline(outline)
        if (tailUp) {
            // 尾部朝上 = 朝下那条路径上下镜像；阴影方向不受影响（阴影是绘制属性）
            flip.setScale(1f, -1f, b.centerX().toFloat(), b.centerY().toFloat())
            outline.transform(flip)
        }
        canvas.drawPath(outline, fillPaint)
    }

    /**
     * 一条闭合轮廓：圆角矩形在底边处「挖」出一个直角三角尾巴。
     *
     * 对齐 dsh-pet web 的 `::after` CSS border 三角：宽 `tailW`（0.75em）、
     * 高 `tailH`（0.37em），两腰是直线，尖端是硬尖角 —— 和 web 完全同款。
     */
    private fun buildOutline(out: Path) {
        val r = minOf(radius, body.width() * 0.5f, body.height() * 0.5f)
        val d = r * CIRCLE_K
        val tx = (body.left + body.width() * tailFrac)
            .coerceIn(body.left + r + tailW * 0.5f, body.right - r - tailW * 0.5f)
        out.reset()
        out.moveTo(body.left + r, body.top)
        out.lineTo(body.right - r, body.top)
        out.cubicTo(
            body.right - r + d, body.top, body.right, body.top + r - d, body.right, body.top + r,
        )
        out.lineTo(body.right, body.bottom - r)
        out.cubicTo(
            body.right, body.bottom - r + d, body.right - r + d, body.bottom, body.right - r, body.bottom,
        )
        // 底边从右往左走，走到尾巴右根 → 直线捅到尖端 → 直线回到左根 → 继续往左
        out.lineTo(tx + tailW * 0.5f, body.bottom)
        out.lineTo(tx, body.bottom + tailH)
        out.lineTo(tx - tailW * 0.5f, body.bottom)
        out.lineTo(body.left + r, body.bottom)
        out.cubicTo(
            body.left + r - d, body.bottom, body.left, body.bottom - r + d, body.left, body.bottom - r,
        )
        out.lineTo(body.left, body.top + r)
        out.cubicTo(
            body.left, body.top + r - d, body.left + r - d, body.top, body.left + r, body.top,
        )
        out.close()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** 圆角用三次贝塞尔近似 1/4 圆：控制点偏移 = r × (1 - 0.5523)。 */
        const val CIRCLE_K = 0.4477f

        /** 平涂底色 = web `background: rgba(255,255,255,.92)`。 */
        const val FILL = 0xEBFFFFFF.toInt()

        /** 投影 = web `box-shadow: 0 0.1978em 0.7692em rgba(0,0,0,.14)`。 */
        const val SHADOW = 0x24000000.toInt()
        const val SHADOW_DY_EM = 0.1978f
        const val SHADOW_BLUR_EM = 0.7692f

        /** 圆角 = web `border-radius: calc(var(--f)*0.7692)`。 */
        const val RADIUS_EM = 0.7692f

        const val TAIL_MIN_FRAC = 0.14f
        const val TAIL_MAX_FRAC = 0.86f
    }
}

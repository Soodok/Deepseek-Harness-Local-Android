package app.dsh.mobile.pet

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 气泡底板：一颗「胶囊 + 小尾巴」的对话泡。
 *
 * 为什么不用 `GradientDrawable`（原来的做法）：
 *  - 它只能画圆角矩形，画不出尾巴 —— 而尾巴是气泡「在跟宠物说话」的关键暗示；
 *  - 圆角矩形和描边是分开两次光栅化的，半透明描边叠在圆角上会糊出一圈毛边，
 *    在 4xx dpi 的屏上看着就是「边缘发虚/发毛」；
 *  - 它没有投影。
 *
 * 这里把**整条轮廓画成一条闭合 Path**（上边 → 右上圆角 → 右边 → 右下圆角 →
 * 带尾巴的下边 → 左下圆角 → 左边 → 左上圆角），一次填充、一次描边：
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
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val flip = Matrix()

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
    private var shader: LinearGradient? = null

    /**
     * @param em 正文字号 px —— 所有尺寸的基准
     * @param bodyH 气泡**正文区**高度 px（不含尾巴），用来把圆角收敛成胶囊
     */
    fun configure(em: Float, bodyH: Float) {
        radius = minOf(bodyH * 0.5f, em * 1.05f).coerceAtLeast(em * 0.45f)
        tailW = em * 1.05f
        tailH = (em * 0.54f).coerceAtLeast(1f)
        inset = em * 0.30f
        strokePaint.strokeWidth = (em * 0.055f).coerceIn(1f, em * 0.08f)
        // 描边只给轮廓一点「存在感」，不抢戏：12% 的冷灰蓝，比纯黑细线柔和得多
        strokePaint.color = STROKE
        // 投影：向下轻推一点点 + 大模糊半径，读起来是「浮在桌面上一层的纸片」
        fillPaint.setShadowLayer(inset, 0f, em * 0.14f, SHADOW)
        rebuildShader()
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
        rebuildShader()
    }

    private fun rebuildShader() {
        val b = bounds
        if (b.isEmpty) {
            shader = null
            return
        }
        val top: Float = if (tailUp) b.top + inset + tailH else b.top + inset
        val bottom: Float = if (tailUp) b.bottom - inset else b.bottom - inset - tailH
        // 竖直方向的极淡渐变：纯白 → 一点点冷调，避免大面积纯白看起来"死板"
        shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(FILL_TOP, FILL_BOTTOM),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        fillPaint.shader = shader
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
        canvas.drawPath(outline, strokePaint)
    }

    /**
     * 一条闭合轮廓：圆角矩形在底边处「挖」出一个带圆头的小尾巴。
     *
     * 尾巴宽度用 `tailW`（约 0.9em）、高度 `tailH`（约 0.46em），两腰是二次贝塞尔，
     * 尖端收得比较圆 —— 尖角三角形会显得硬，圆头才"软"。
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
        // 底边从右往左走，走到尾巴根 → 绕下去再绕回来 → 继续往左
        out.lineTo(tx + tailW * 0.5f, body.bottom)
        out.cubicTo(
            tx + tailW * 0.34f, body.bottom + tailH * 0.42f,
            tx + tailW * 0.20f, body.bottom + tailH * 0.74f,
            tx, body.bottom + tailH,
        )
        out.cubicTo(
            tx - tailW * 0.20f, body.bottom + tailH * 0.74f,
            tx - tailW * 0.34f, body.bottom + tailH * 0.42f,
            tx - tailW * 0.5f, body.bottom,
        )
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
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** 圆角用三次贝塞尔近似 1/4 圆：控制点偏移 = r × (1 - 0.5523)。 */
        const val CIRCLE_K = 0.4477f
        const val FILL_TOP = 0xFCFFFFFF.toInt()
        const val FILL_BOTTOM = 0xF7F3F7FF.toInt()
        const val STROKE = 0x1F33456B
        const val SHADOW = 0x3322304F
        const val TAIL_MIN_FRAC = 0.14f
        const val TAIL_MAX_FRAC = 0.86f
    }
}

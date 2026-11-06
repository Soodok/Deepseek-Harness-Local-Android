package app.dsh.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import app.dsh.mobile.R

/**
 * 启动页的细进度环（v1.6.19）。
 *
 * ## 为什么做这个控件而不是用 ProgressBar
 *
 * 原生 `ProgressBar` 水平条在深色背景上只有「一条灰底 + 一条强调色」，
 * 位置固定、粗细不能自适应、也没法在环心放东西。改用自绘环之后：
 *
 * · 环本身成为视觉主体，logo 直接压在环心，不需要额外卡片承托；
 * · 确定态时环长**严格等于真实比例**（不做「假缓慢增长」，那会骗用户）；
 * · 不确定态时只跑一段短弧（约 1/4 圈），2.6 秒一周 —— 不是反复填满整圈，
 *   避免「看起来一直在完成」的错觉。
 *
 * ## 三个必须自己处理的边界
 *
 * 1. **减少动画**：系统关掉动画（`ValueAnimator.areAnimatorsEnabled()` 为 false，
 *    无障碍/开发者选项里能关）时不能启动 Animator，否则会一直占着 Choreographer。
 *    此时仍画一段静态短弧表示「在处理」。
 * 2. **不可见时停**：`onWindowVisibilityChanged` / `onVisibilityAggregated` /
 *    `onDetachedFromWindow` 都要停 —— 启动页会淡出隐藏，不停就会在后台空转。
 * 3. **失败态**：进度环变红色、不做转动动画，与文字颜色一起表达「出错了」。
 *
 * 自绘不用第三方库，符合项目「零外部 UI 依赖」的约定。
 */
class SplashRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    /** 环线宽度：1.5dp 是「细但看得见」的下限，再细在低 dpi 屏上会断成点。 */
    private val strokeWidth = 1.5f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@SplashRingView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.splash_track)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@SplashRingView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.splash_accent)
    }

    private val rect = RectF()

    /** 0f..1f；null = 不确定态（跑短弧动画） */
    private var fraction: Float? = null

    private var failed = false

    /** 不确定态的动画相位 0f..1f（一圈归一化） */
    private var phase = 0f

    private var animator: ValueAnimator? = null

    /** 环是否允许跑动画（可见性 + 系统动画开关的组合结果） */
    private var motionEnabled = true

    init {
        // 环是纯装饰，进度语义由旁边文字承担（读屏读百分比更准确）
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * 设置进度。
     *
     * @param value 0f..1f 的真实比例；null 表示拿不到总量（转不确定态）
     * @param isFailed 失败态：变红、停动画
     */
    fun setState(value: Float?, isFailed: Boolean = false) {
        val norm = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        val failedChanged = failed != isFailed
        if (fraction == norm && !failedChanged) return
        fraction = norm
        failed = isFailed
        progressPaint.color = context.getColor(
            if (failed) R.color.splash_failure else R.color.splash_accent,
        )
        syncMotion()
        invalidate()
    }

    /** 外部（SplashController）在遮罩淡出时显式停掉动画。 */
    fun stopMotion() {
        motionEnabled = false
        cancelAnimator()
    }

    private fun syncMotion() {
        val shouldRun = motionEnabled &&
            fraction == null &&
            !failed &&
            isAttachedToWindow &&
            isShown &&
            windowVisibility == VISIBLE &&
            ValueAnimator.areAnimatorsEnabled()

        if (!shouldRun) {
            cancelAnimator()
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2_600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun cancelAnimator() {
        animator?.let {
            it.removeAllUpdateListeners()
            it.cancel()
        }
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 环线要完整落在 View 内：半径再让出半个线宽 + 1dp 余量
        val inset = strokeWidth / 2f + density
        rect.set(inset, inset, w - inset, h - inset)

        // 轨道始终画整圈：不确定态下它就是「底环」，有它才知道这一段弧在走
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        val frac = fraction
        if (frac != null) {
            // 确定态：从 12 点方向顺时针，弧长严格等于比例
            if (frac > 0f) {
                canvas.drawArc(rect, START_ANGLE, 360f * frac, false, progressPaint)
            }
            return
        }

        // 不确定态：一段约 1/4 圈的短弧绕圈走
        val sweep = SWEEP_DEG
        val start = START_ANGLE + phase * 360f
        canvas.drawArc(rect, start, sweep, false, progressPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motionEnabled = true
        syncMotion()
    }

    override fun onDetachedFromWindow() {
        cancelAnimator()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncMotion()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncMotion()
    }

    private companion object {
        /** 12 点方向（Canvas 的 0° 是 3 点方向，-90° 转到顶部） */
        const val START_ANGLE = -90f

        /** 不确定态的弧长：90° = 1/4 圈。再长会让人误以为快完成了。 */
        const val SWEEP_DEG = 90f
    }
}

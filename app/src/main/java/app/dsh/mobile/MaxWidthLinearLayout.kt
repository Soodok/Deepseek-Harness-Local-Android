package app.dsh.mobile

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * 限宽 LinearLayout（v1.4.18，平板横屏适配）。
 *
 * 作用：让设置类页面的单列内容在平板横屏（1280dp 宽）下不要被拉成
 * 一条超宽行；宽度超过 [maxWidthDp] 时收窄，配合 XML 里的
 * `android:layout_gravity="center_horizontal"` 居中。
 *
 * 为什么用自定义 View，而不是 `layout-land` + 资源限定符：
 * 这些 Activity 都声明了 `configChanges="orientation|screenSize"`，
 * 转屏**不会重建、不会重新 inflate** —— `layout-land` 只有在以横屏
 * 启动时才生效。自定义 onMeasure 每次布局都按当前密度/宽度重新计算，
 * 因此转屏、分屏、折叠屏展开都能立即生效，也不会丢滚动位置或打断
 * 正在进行的导出/安装任务。
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 最大内容宽度（dp）；<= 0 表示不限制。 */
    var maxWidthDp: Int = DEFAULT_MAX_WIDTH_DP
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxWidthDp <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val capPx = (maxWidthDp * resources.displayMetrics.density).roundToInt()
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val width = if (mode == MeasureSpec.UNSPECIFIED) {
            capPx
        } else {
            minOf(MeasureSpec.getSize(widthMeasureSpec), capPx)
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, mode), heightMeasureSpec)
    }

    companion object {
        /** 默认内容宽度：普通列表页（关于 / ASR / 语音服务 / 引导页）。 */
        const val DEFAULT_MAX_WIDTH_DP = 760
    }
}

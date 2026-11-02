package app.dsh.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup

/**
 * 收起态灵动岛（状态栏收起后那个小圆点）的统一渲染器。
 *
 * ## 为什么要抽出来
 * 主界面真实的圆点、以及设置子页里的预览，必须长得**完全一样** —— 否则用户
 * 看到"预览是这样、收起来却是那样"。之前两边各写一套，结果预览漏掉了默认外观
 * 中间那个小亮核，与实际不符。现在两边都调 [apply]，天然一致。
 *
 * ## 默认外观的结构
 * 布局里是「容器（bg_dynamic_island_dot：外圈淡光环 + 内圈深色圆）+
 * 第一个子 View（bg_island_core_dot：中心 8dp 小亮核）」。设置自定义图后，
 * 容器背景换成图片、小亮核隐藏（整个圆点就是图片本身）。
 */
object IslandDotRenderer {

    /**
     * 按当前偏好渲染圆点。
     *
     * @param container 圆点容器；其第一个子 View 被当作"中心小亮核"处理（可为空）
     * @param sizeDpOverride 预览时可传固定尺寸；null 表示用用户设置的尺寸
     */
    fun apply(
        context: Context,
        container: View,
        sizeDpOverride: Int? = null,
        centerInParent: Boolean = false,
        applySize: Boolean = true,
    ) {
        if (applySize) {
            val sizeDp = sizeDpOverride ?: CustomVisualPref.islandDotSizeDp(context)
            val px = (sizeDp * context.resources.displayMetrics.density).toInt()
            container.layoutParams = container.layoutParams.apply {
                width = px
                height = px
                // 预览框里必须居中；真机的 handleBar 用 layout_gravity="top|center_horizontal"
                // 自己控制位置，这里不动它，否则会被拉到垂直居中、盖到状态栏上。
                if (centerInParent && this is android.widget.FrameLayout.LayoutParams) {
                    gravity = android.view.Gravity.CENTER
                }
            }
            container.requestLayout()
        }

        val core = (container as? ViewGroup)?.getChildAt(0)

        val file = CustomVisualPref.islandDotFile(context)
        if (file == null) {
            // 默认外观：外圈小光环 + 中心小亮核
            container.background = context.getDrawable(R.drawable.bg_dynamic_island_dot)
            core?.visibility = View.VISIBLE
            return
        }
        val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
        if (bmp == null) {
            container.background = context.getDrawable(R.drawable.bg_dynamic_island_dot)
            core?.visibility = View.VISIBLE
            return
        }
        // 两种形状都先居中裁成正方形：直接铺原图会被容器拉伸变形（非方形素材尤其明显），
        // 圆形模式再额外描圆。这样"预览"与"实机"看到的构图一致。
        val square = centerSquare(bmp)
        val shown = when (CustomVisualPref.islandDotShape(context)) {
            CustomVisualPref.DotShape.CIRCLE -> circular(square) ?: square
            CustomVisualPref.DotShape.SQUARE -> square
        }
        container.background = BitmapDrawable(context.resources, shown)
        // 整个圆点就是图片，中心小亮核不再需要
        core?.visibility = View.GONE
    }

    /** 居中裁成正方形（不变形）；已是正方形时原样返回。 */
    fun centerSquare(src: Bitmap): Bitmap =
        if (src.width == src.height) src
        else {
            val size = minOf(src.width, src.height)
            Bitmap.createBitmap(
                src, (src.width - size) / 2, (src.height - size) / 2, size, size,
            )
        }

    /** 在正方形位图上描一个内切圆；入参已是正方形，不再二次裁切。 */
    fun circular(square: Bitmap): Bitmap? = runCatching {
        val size = square.width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = size / 2f
        canvas.drawCircle(r, r, r, paint)
        out
    }.getOrNull()
}

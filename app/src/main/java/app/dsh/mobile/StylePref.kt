package app.dsh.mobile

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

/**
 * 主题属性取色（v1.8.0，2026-11 收敛为单一 Material 3 主题）。
 *
 * 历史：v1.8.0 曾在 [Theme.Dsh]（经典冷蓝灰）与 `Theme.Dsh.MD3`（暖灰青墨）之间
 * 分派，用户可在主题页切换。现在只保留 Material 3 一套主题，风格枚举与分派
 * 已删除；本对象只剩「按主题属性解析颜色」这一个职责。
 *
 * 保留的原因：布局里有 403 处 `?attr/dshXxx`，代码侧也有大量 `getColor(R.color.x)`
 * —— 后者拿的是**静态色**，与布局的 `?attr/...` 不是同一条路径。凡是要跟随主题
 * （含配色方案覆盖层）的颜色，代码里都必须走 [color] / [dshColor]。
 */
object StylePref {

    /**
     * 解析一个主题属性，用于代码侧上色。
     *
     * [fallback] 可以是颜色资源 ID，也可以是裸 ARGB 值。解析失败时绝不抛异常：
     * 这类调用多在 onDraw / 列表绑定里，抛了就是整屏白。
     */
    fun color(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        val fallbackColor = fallbackColor(ctx, fallback)
        return if (ctx.theme.resolveAttribute(attr, tv, true)) {
            when {
                tv.resourceId != 0 -> runCatching {
                    val colors: ColorStateList = ctx.getColorStateList(tv.resourceId)
                    colors.defaultColor
                }.getOrDefault(fallbackColor)
                tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> tv.data
                else -> fallbackColor
            }
        } else {
            fallbackColor
        }
    }

    /**
     * 解析主题尺寸属性。返回像素值，供 MaterialCardView 等代码控件使用。
     */
    fun dimension(ctx: Context, attr: Int, fallbackPx: Float): Float {
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(attr, tv, true)) return fallbackPx
        return when {
            tv.type == TypedValue.TYPE_DIMENSION ->
                TypedValue.complexToDimension(tv.data, ctx.resources.displayMetrics)
            tv.resourceId != 0 -> runCatching {
                ctx.resources.getDimension(tv.resourceId)
            }.getOrDefault(fallbackPx)
            else -> fallbackPx
        }
    }

    private fun fallbackColor(ctx: Context, fallback: Int): Int =
        runCatching { ctx.getColorStateList(fallback).defaultColor }.getOrDefault(fallback)
}

/**
 * 按主题取色（Context 扩展，便于原地替换 `getColor(R.color.xxx)`）。
 *
 * 用法：把 `getColor(R.color.text_secondary)` 换成
 * `dshColor(R.attr.dshTextSecondary, R.color.text_secondary)`。
 * 第二个参数是主题解析失败时的回退值，保证任何情况下都不会拿到透明色
 * （那会直接让文字消失）。
 */
fun Context.dshColor(attr: Int, fallback: Int): Int = StylePref.color(this, attr, fallback)

/**
 * 将设置体系的 MaterialCardView 统一到同一组主题令牌。
 */
fun MaterialCardView.applyDshCardStyle(
    context: Context,
    radiusAttr: Int = R.attr.dshCardRadius,
) {
    radius = StylePref.dimension(
        context,
        radiusAttr,
        resources.getDimension(R.dimen.md3_card_radius),
    )
    strokeWidth = StylePref.dimension(
        context,
        R.attr.dshCardStroke,
        resources.displayMetrics.density,
    ).roundToInt().coerceAtLeast(0)
    setStrokeColor(context.dshColor(R.attr.dshStroke, R.color.stroke))
    setCardBackgroundColor(context.dshColor(R.attr.dshSurface, R.color.surface))
    cardElevation = 0f
}

/**
 * 遍历页面中的卡片，覆盖 XML 中可能来自旧版 MD3 的形态参数。
 */
fun View.applyDshCardStyles(context: Context) {
    if (this is MaterialCardView) applyDshCardStyle(context)
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).applyDshCardStyles(context)
        }
    }
}

fun applyDshCardStyles(activity: Activity) {
    activity.window.decorView.applyDshCardStyles(activity)
}

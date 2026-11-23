package app.dsh.mobile

import android.content.Context
import android.util.TypedValue

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
     * 解析失败时回退 [fallback]，绝不抛异常：这类调用多在 onDraw / 列表绑定里，
     * 抛了就是整屏白。
     */
    fun color(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) {
            when {
                tv.resourceId != 0 -> runCatching { ctx.getColor(tv.resourceId) }.getOrDefault(fallback)
                else -> tv.data
            }
        } else {
            fallback
        }
    }
}

/**
 * 按主题取色（Context 扩展，便于原地替换 `getColor(R.color.xxx)`）。
 *
 * 用法：把 `getColor(R.color.text_secondary)` 换成
 * `dshColor(R.attr.dshTextSecondary, R.color.text_secondary)`。
 * 第二个参数是主题解析失败时的回退值，保证任何情况下都不会拿到透明色
 * （那会直接让文字消失）。
 */
fun Context.dshColor(attr: Int, fallback: Int): Int = StylePref.color(this, attr, fallback);

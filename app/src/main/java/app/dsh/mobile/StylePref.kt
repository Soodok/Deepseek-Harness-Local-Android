package app.dsh.mobile

import android.content.Context
import android.util.TypedValue

/**
 * 界面风格（v1.8.0）。
 *
 * 两套风格共用一个主题文件的两个 style：
 *   · [MATERIAL] → `Theme.Dsh.MD3`（Material 3 暖灰 + 青墨）
 *   · [CLASSIC]  → `Theme.Dsh`（v1.7.x 及以前的冷蓝灰）
 *
 * 切换只做一件事：改一个偏好值 + recreate。布局里 403 处 `?attr/dshXxx`
 * 与 652 处 Material 语义属性会各自解析到对应主题的值，不需要逐页改代码。
 *
 * 为什么不复用配色选择：它管的是**强调色来源**（预设/自定义/
 * 跟随壁纸），与「界面是经典还是材料 3」正交 —— 材料 3 风格下同样可以挑强调色。
 * 混在一起会让两套语义互相牵制（PR #102 就是因此把 Mode 删成了 Boolean）。
 */
object StylePref {

    enum class Style {
        /** v1.7.x 及以前的冷蓝灰（bg #F5F7FA / text #0F172A / accent #0284C7） */
        CLASSIC,

        /** Material 3 原生暖灰（bg #F7F6F5 / text #1A1C1D / primary #1B6C8C） */
        MATERIAL,
    }

    private const val PREFS = "dsh_ui_prefs"
    private const val KEY_STYLE = "ui_style"

    /** 默认材质 3：v1.8.0 起这就是主视觉，经典风格作为可选项保留。 */
    val DEFAULT = Style.MATERIAL

    fun style(ctx: Context): Style {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STYLE, DEFAULT.name)
        return runCatching { Style.valueOf(raw ?: DEFAULT.name) }.getOrDefault(DEFAULT)
    }

    fun setStyle(ctx: Context, style: Style) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STYLE, style.name).apply()
    }

    fun isMaterial(ctx: Context): Boolean = style(ctx) == Style.MATERIAL

    /**
     * 按当前风格解析一个主题属性，用于代码侧上色。
     *
     * 代码里的 `getColor(R.color.text_secondary)` 拿的是**静态色**，
     * 与布局里的 `?attr/dshTextSecondary` 不是同一条路径 —— 换风格时前者不变、
     * 后者跟随主题，同一屏就会出现两种灰。凡是「跟随风格」的颜色都必须走这里。
     *
     * 解析失败时回退 [fallback]，绝不抛异常：这类调用多在 onBindViewHolder /
     * onDraw 里，抛了就是整屏白。
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
 * 按「界面风格」取色（Context 扩展，便于原地替换 `getColor(R.color.xxx)`）。
 *
 * 用法：把 `getColor(R.color.text_secondary)` 换成
 * `dshColor(R.attr.dshTextSecondary, R.color.text_secondary)`。
 * 第二个参数是**经典风格下的静态值** —— 主题解析失败、或页面不跟随风格时回退到它，
 * 保证任何情况下都不会拿到透明色（那会直接让文字消失）。
 */
fun Context.dshColor(attr: Int, fallback: Int): Int = StylePref.color(this, attr, fallback);

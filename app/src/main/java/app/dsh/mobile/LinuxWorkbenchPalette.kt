package app.dsh.mobile

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Linux 工作台专用色板。
 *
 * 为什么不吃全局 Accent：这一页的绿/黄是**语义色**（容器引擎 vs Bionic 原生），
 * 不是用户主题色。AccentTint 会把全局 accent 重新染色，若这里引用 @color/accent，
 * 换主题时会把「容器绿」改成用户选的颜色，语义就没了。
 *
 * 深浅两套通过 values-night 覆盖同名资源实现，不在代码里判断主题。
 *
 * 层次模型（2026-11-12 视觉重做）：页面底 → 卡面 → 卡内小件，三层各差一档明度；
 * 每一层都是「上亮下暗」的一对颜色（*Hi / 无后缀），由视图层连成渐变。
 * 只给一个平涂色的话，深色下卡片与底色只差十几级亮度，真机上是糊在一起的。
 */
data class WorkbenchPalette(
    /** 页面底基准色 / 视口顶部的受光色 */
    val bg: Int,
    val bgTop: Int,
    /** 卡面下沿 / 上沿 */
    val surface: Int,
    val surfaceHi: Int,
    /** 描边下沿 / 上沿（上亮下暗 = 光从上方来） */
    val stroke: Int,
    val strokeHi: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    /** 第三级文字：版本号这类「可以不读」的信息，再退一档 */
    val textTertiary: Int,
    val accent: Int,
    val accentSoft: Int,
    /** 实心 accent 上的文字色（按亮度自动取深/浅） */
    val onAccent: Int,
    /** 主卡：左上 → 右下的填充，以及上 → 下的描边 */
    val heroTop: Int,
    val heroBottom: Int,
    val heroStrokeTop: Int,
    val heroStrokeBottom: Int,
    val bannerBg: Int,
    val bannerStroke: Int,
    val bannerFg: Int,
    val chipBg: Int,
    val chipStroke: Int,
    /** 卡内分隔线 */
    val divider: Int,
    /** 按压水波：主文字色的低透明度版本，深浅两套自动反相 */
    val ripple: Int,
    val fault: Int,
)

fun workbenchPalette(context: Context, tone: HeroTone, dark: Boolean): WorkbenchPalette {
    val ok = ContextCompat.getColor(context, R.color.wb_accent_linux)
    val native = ContextCompat.getColor(context, R.color.wb_accent_native)
    val fault = ContextCompat.getColor(context, R.color.wb_fault)
    val accent = when (tone) {
        HeroTone.NATIVE -> native
        HeroTone.MISSING -> native
        HeroTone.FAULT -> fault
        HeroTone.ACTIVE -> ok
        HeroTone.IDLE -> ContextCompat.getColor(context, R.color.wb_idle)
    }
    val surface = ContextCompat.getColor(context, R.color.wb_surface)
    val surfaceHi = ContextCompat.getColor(context, R.color.wb_surface_hi)
    val stroke = ContextCompat.getColor(context, R.color.wb_stroke)
    val strokeHi = ContextCompat.getColor(context, R.color.wb_stroke_hi)
    val textPrimary = ContextCompat.getColor(context, R.color.wb_text)
    val textSecondary = ContextCompat.getColor(context, R.color.wb_text_secondary)

    return WorkbenchPalette(
        bg = ContextCompat.getColor(context, R.color.wb_bg),
        bgTop = ContextCompat.getColor(context, R.color.wb_bg_top),
        surface = surface,
        surfaceHi = surfaceHi,
        stroke = stroke,
        strokeHi = strokeHi,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textTertiary = blend(textSecondary, surface, 0.28f),
        accent = accent,
        // ★blend(base, overlay, ratio) 是「把 overlay 以 ratio 混进 base」。
        //   要的是「surface 底色里掺一点 accent」，所以 base=surface、overlay=accent。
        //   （写反会得到接近纯 accent 的亮色块 —— 真机第一版就是这个问题。）
        accentSoft = blend(surface, accent, 0.16f),
        // 旧值几乎恒为白：深色下 accent 是 #30D158 / #FFD60A，白字对比度只有 2.0 / 1.4，
        // 「开始」按钮与当前步骤序号在缺依赖态（黄）根本读不出来。按底色亮度取深墨或白。
        onAccent = readableOn(accent, ContextCompat.getColor(context, R.color.wb_ink)),
        // 主卡不再整块平涂。旧的 strong = surface 掺 10% accent，黄色在近黑上就是军绿土色
        // （真机 #40391A）。改成只在受光的左上角给 10% 色调、右下角退到 3%：
        // 语义色还在，但大面积是中性卡面，不再是一块「色块」。
        // ★真机复看：浅色下 10% 黄在纯白上是一块象牙白，深色下是灰橄榄 —— 都显脏。
        //   再降一档：主卡几乎回到中性卡面（深 6%/2%，浅 4%/1.5%），
        //   语义色只由左上状态点与顶部描边承担，不靠面积说话。
        heroTop = blend(surfaceHi, accent, if (dark) 0.06f else 0.04f),
        heroBottom = blend(surface, accent, if (dark) 0.02f else 0.015f),
        // 主卡的识别度交给描边：一条带 accent 的亮边比一整块底色更克制，也更像「被点亮」。
        heroStrokeTop = blend(strokeHi, accent, if (dark) 0.42f else 0.40f),
        heroStrokeBottom = blend(stroke, accent, if (dark) 0.16f else 0.22f),
        // 横幅同理：底色从 18%~22% 降到 10%，描边带 38% accent。
        // 横幅同理，浅色下 10% 黄是一整条奶油色 pill，压到 7%；识别度交给描边与状态点。
        bannerBg = blend(surface, accent, 0.07f),
        bannerStroke = blend(stroke, accent, 0.38f),
        // 横幅底色是 accent 的低比例混色，正文仍用主文字色即可
        bannerFg = textPrimary,
        // ★方向：以 surface 为底掺一点文字色，得到「比卡面略亮/略深」的 chip 底色；
        //   写反（textPrimary 为底）会得到接近前景色的深块。
        //   旧视图层没用这个字段、直接拿 surface 画 chip —— chip 与卡面同色，真机上只剩字悬空。
        chipBg = blend(surface, textPrimary, if (dark) 0.07f else 0.05f),
        chipStroke = blend(surface, textPrimary, 0.12f),
        divider = blend(surface, textPrimary, 0.08f),
        ripple = ColorUtils.setAlphaComponent(textPrimary, 0x1F),
        fault = fault,
    )
}

/** 把 [overlay] 以 [ratio] 混到 [base] 上（ratio=0 返回 base） */
internal fun blend(base: Int, overlay: Int, ratio: Float): Int {
    val r = ratio.coerceIn(0f, 1f)
    return Color.rgb(
        (Color.red(base) * (1 - r) + Color.red(overlay) * r).toInt(),
        (Color.green(base) * (1 - r) + Color.green(overlay) * r).toInt(),
        (Color.blue(base) * (1 - r) + Color.blue(overlay) * r).toInt(),
    )
}

/**
 * 实心色块上该用深字还是白字。
 *
 * 阈值 0.40（相对亮度）：本页四个实心色里 #30D158(0.47) / #FFD60A(0.70) 取深墨，
 * #00A84D(0.29) / #C58A00(0.31) / 两个故障红取白 —— 每一组对比度都在 3:1 以上。
 */
internal fun readableOn(solid: Int, ink: Int): Int =
    if (ColorUtils.calculateLuminance(solid) > 0.40) ink else Color.WHITE

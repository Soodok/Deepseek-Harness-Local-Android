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
 * 资源改用全局静态 success / warning / danger，让状态色同源且保持三种语义可区分。
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
        // 语义色改用全局 success / warning / danger 后，软底减量，避免另造一套高饱和色块。
        accentSoft = blend(surface, accent, if (dark) 0.12f else 0.08f),
        // 全局语义色浅色较深、深色较亮：保留按亮度取白字 / 深墨，覆盖按钮与步骤序号。
        onAccent = readableOn(accent, ContextCompat.getColor(context, R.color.wb_ink)),
        // 主卡以全局石板灰为主，只在受光端留少量语义色；保留两端明度差，避免回到平涂。
        // 深色 3% / 1%、浅色 2% / 0.5%，让容器绿与原生黄主要由状态点和描边传达。
        heroTop = blend(surfaceHi, accent, if (dark) 0.03f else 0.02f),
        heroBottom = blend(surface, accent, if (dark) 0.01f else 0.005f),
        // 中性描边已对齐全局线色，降低语义色占比，避免主卡比其他卡片多一圈鲜亮轮廓。
        // 浅色语义色较深，上沿只混 18%，防止受光边反而比下沿暗。
        heroStrokeTop = blend(strokeHi, accent, if (dark) 0.28f else 0.18f),
        heroStrokeBottom = blend(stroke, accent, if (dark) 0.10f else 0.14f),
        // 横幅同样回到中性卡面；浅色 warning 较深，混入量更低以免整条横幅泛棕。
        bannerBg = blend(surface, accent, if (dark) 0.04f else 0.025f),
        bannerStroke = blend(stroke, accent, 0.24f),
        // 横幅底色是 accent 的低比例混色，正文仍用主文字色即可
        bannerFg = textPrimary,
        // 以 surface 为底掺一点文字色，保持 chip 与卡面分层；深色减到 4%，接近全局小件的石板灰。
        chipBg = blend(surface, textPrimary, if (dark) 0.04f else 0.05f),
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
 * 阈值 0.40（相对亮度）沿用：全局浅色 success / warning / danger 取白，
 * 深色三者取深墨；按资源实色计算，三种状态的文字对比度均超过 4.5:1。
 */
internal fun readableOn(solid: Int, ink: Int): Int =
    if (ColorUtils.calculateLuminance(solid) > 0.40) ink else Color.WHITE

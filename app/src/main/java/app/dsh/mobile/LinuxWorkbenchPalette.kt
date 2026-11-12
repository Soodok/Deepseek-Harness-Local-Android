package app.dsh.mobile

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat

/**
 * Linux 工作台专用色板。
 *
 * 为什么不吃全局 Accent：这一页的绿/黄是**语义色**（容器引擎 vs Bionic 原生），
 * 不是用户主题色。AccentTint 会把全局 accent 重新染色，若这里引用 @color/accent，
 * 换主题时会把「容器绿」改成用户选的颜色，语义就没了。
 *
 * 深浅两套通过 values-night 覆盖同名资源实现，不在代码里判断主题。
 */
data class WorkbenchPalette(
    val bg: Int,
    val surface: Int,
    val stroke: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentSoft: Int,
    val onAccent: Int,
    val strong: Int,
    val bannerBg: Int,
    val bannerFg: Int,
    val chipBg: Int,
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
    val textPrimary = ContextCompat.getColor(context, R.color.wb_text)
    val textSecondary = ContextCompat.getColor(context, R.color.wb_text_secondary)

    return WorkbenchPalette(
        bg = ContextCompat.getColor(context, R.color.wb_bg),
        surface = surface,
        stroke = ContextCompat.getColor(context, R.color.wb_stroke),
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        accent = accent,
        // ★blend(base, overlay, ratio) 是「把 overlay 以 ratio 混进 base」。
        // 主卡要的是「surface 底色里掺 24% accent」，所以 base=surface、overlay=accent。
        // （写反会得到接近纯 accent 的亮色块 —— 真机第一版就是这个问题。）
        accentSoft = blend(surface, accent, 0.14f),
        onAccent = if (tone == HeroTone.IDLE) textPrimary else blend(textPrimary, accent, 0.02f),
        // 0.16 在深色下是军绿色（accent #FFD60A + 近黑 ≈ #49411A），真机看着发土；
        // 0.10 只留一层若有若无的暖调，层级靠卡片对比度而不是大色块。
        strong = blend(surface, accent, if (dark) 0.10f else 0.16f),
        bannerBg = blend(surface, accent, if (dark) 0.18f else 0.22f),
        // 横幅底色是 accent 的低比例混色，正文仍用主文字色即可（原本两个分支同值）
        bannerFg = textPrimary,
        // ★方向：以 surface 为底掺一点文字色，得到「比卡面略深/略浅」的 chip 底色；
        //   写反（textPrimary 为底）会得到接近前景色的深块。
        chipBg = blend(surface, textPrimary, 0.06f),
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

package app.dsh.mobile

/**
 * 颜色对比度工具（WCAG 2.1）。
 *
 * ## 为什么单独一个文件
 *
 * 强调色是按**深色底**挑的：天蓝 SKY 的 `#7DD3FC` 落在浅色页面 `#F5F7FA` 上只有
 * 约 1.6:1，分类标题、「下载」这类前景色在浅色主题下几乎看不见。修复办法是把前景色
 * 压到对实际底色达标，但这个算法要被三处共用（Accent 的前景色、扩展中心的 tonal
 * 按钮、灵动岛的配色断言），而且**必须能在纯 JVM 下跑**——不能依赖 Android 类，
 * 否则 `scripts/run-a11y-jvm-test.sh` 编译不进来，改动就只能靠真机眼睛验。
 *
 * 所以这里只放纯 Int 运算。调用方：`AccentColor.onSurface()`、
 * `ExtensionStoreActivity.mixArgb()`、`IslandPalette`。
 */
object ContrastColor {

    /** WCAG 2.1 相对亮度（0..1）。 */
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = ch((argb shr 16) and 0xFF)
        val g = ch((argb shr 8) and 0xFF)
        val b = ch(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG 2.1 对比度（1..21）。 */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = if (la > lb) la else lb
        val lo = if (la > lb) lb else la
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * 把一个颜色当**前景**用时的可读版本。
     *
     * 按 RGB **等比压暗**直到对 [surface] 达到 [minContrast]，保住色相 ——
     * 压出来仍是同一支颜色，不会变灰。已经达标或底色本身就深时原样返回。
     *
     * 只用于前景（文字、小图标）。大面积色块底不要用它，压暗会让整页发沉。
     */
    fun readableOn(color: Int, surface: Int, minContrast: Double = 4.5): Int {
        if (contrast(color, surface) >= minContrast) return color
        var c = color
        var i = 0
        while (contrast(c, surface) < minContrast && i < 32) {
            c = scaleRgb(c, 0.92f)
            i++
        }
        return c
    }

    /** RGB 等比缩放（alpha 置为不透明）。 */
    fun scaleRgb(color: Int, f: Float): Int {
        val r = (((color shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * ARGB 直通道混合：把 [top] 以 [alpha] 叠到 [bottom] 上。
     *
     * 用来预估「半透明强调色底」的实际颜色 —— tonal 按钮是 22% 的 accent 叠在页面
     * 底色上，只按页面底色算对比度会偏乐观（实测 4.36:1 差一点点达标）。
     */
    fun mix(bottom: Int, top: Int, alpha: Float): Int {
        fun ch(shift: Int): Int {
            val b = (bottom shr shift) and 0xFF
            val t = (top shr shift) and 0xFF
            return (t * alpha + b * (1f - alpha)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

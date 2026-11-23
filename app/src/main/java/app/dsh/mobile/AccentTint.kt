package app.dsh.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView

/**
 * 静态配色的运行时重着色（v1.7.9）。
 *
 * ## 为什么需要
 *
 * 强调色可以在「主题」页切换（预设 / 自定义 / 动态取色），但布局与 drawable 里
 * 有 87 处写的是静态资源 `@color/accent`、88 处写的是 `?attr/colorPrimary`
 * （后者最终也解析到同一个 `@color/accent`）。这两类引用在**资源解析期**就定型，
 * 与用户偏好无关：用户选靛蓝 `#4F6EF7` 后图标仍是 `#0284C7`，看到的就是
 * 「切了没反应」。只有代码里显式调 `Accent.current()` 的位置会变色，于是同一个
 * 页面里一半变了、一半没变。
 *
 * ## 为什么不用 Material 的 ColorResourcesOverride
 *
 * 那才是资源级的正统做法，但它的 `getInstance()` 在 API ≤30 直接返回 null
 * （javap 反查 material-1.12.0 确认：仅 SDK 31..33 与 ≥34 返回实现），
 * 而本仓库 minSdk 是 26；且整个类标了 `@RestrictTo(LIBRARY_GROUP)`。
 * 只覆盖一半机型的方案不能当主路径。这里改为一次视图树遍历，原地改写。
 *
 * ## 为什么采样位图而不是读 GradientDrawable 的颜色
 *
 * `GradientDrawable.getColor()` / `getStrokeColor()` / `getStrokeWidth()` 都是
 * @hide（`android-36/android.jar` 里只有 setStroke 系列，反查确认），编译期就取不到；
 * 反射读非 SDK 接口在 targetSdk 28+ 会被灰名单拦。所以这里把 drawable 画进一张
 * 小位图再取像素 —— 纯公开 API，任何版本都稳。
 *
 * ## 明暗适配（重要）
 *
 * `@color/accent` 本来就有明暗两份：浅色 `#0284C7`、深色 `#7DD3FC`。
 * 而 `Accent` 枚举只存一份中间调（SKY `#7DD3FC`）。把枚举值直接铺到浅色页面上，
 * 天蓝落在近白底上只有约 1.5:1，比现状更糊。所以这里先做明暗适配：
 *  · 用户没改过强调色（PRESET + 天蓝）→ 直接返回色板静态值，整棵树都不动，
 *    默认观感与改造前完全一致；
 *  · 其余情况 → 按当前底色把强调色的相对亮度提到 WCAG 3:1（图形元素阈值）以上。
 *
 * ## 边界
 *
 * · RecyclerView 的 item 是绑定期才创建的，遍历覆盖不到；本仓库 `item_*.xml`
 *   都没有引用 `@color/accent`，当前无遗漏。新增时请在 bind 里走 Accent.current()。
 * · 多状态 ColorStateList（按钮禁用态等）的色值数组是 @hide 取不到，这类列表
 *   原样保留 —— 它们是 selector，本来就不直接引用 @color/accent。
 * · 幂等：每个 View 的原始值记在 [textOriginals] / [bgAccent] 里，每次都从原始值
 *   重算，反复调用不会叠加变换，也不会在换色后残留上一次的颜色。
 */
object AccentTint {

    /** `values/colors.xml` 的 @color/accent */
    private const val LIGHT_ACCENT = 0xFF0284C7.toInt()

    /** `values-night/colors.xml` 的 @color/accent */
    private const val DARK_ACCENT = 0xFF7DD3FC.toInt()

    /** `values/colors.xml` 的 @color/accent_soft */
    private const val LIGHT_SOFT = 0xFFE0F2FE.toInt()

    /** `values-night/colors.xml` 的 @color/accent_soft（12% 天蓝） */
    private const val DARK_SOFT = 0x1F7DD3FC

    /** `values/colors.xml` 的 @color/bg */
    private const val LIGHT_BG = 0xFFF5F7FA.toInt()

    /** `values-night/colors.xml` 的 @color/bg */
    private const val DARK_BG = 0xFF101418.toInt()

    /** 软色透明度，与 colors.xml 的 accent_soft 一致 */
    private const val SOFT_ALPHA = 0x1F

    /** 图形元素的对比度下限（WCAG 1.4.11 对非文本元素要求 3:1） */
    private const val MIN_CONTRAST = 3.0

    /** 采样位图边长：够小不费内存，够大能取到描边像素 */
    private const val SAMPLE = 24

    /** 各 View 的原始文字色（首次改写前记下，之后每次都从它重算） */
    private val textOriginals = java.util.WeakHashMap<TextView, ColorStateList>()

    /** 已知「背景本身就是强调色」的 View；标记后每次直接重染，不必再采样 */
    private val bgAccent = java.util.WeakHashMap<View, Boolean>()

    /**
     * 当前实际该用的强调色（已按明暗底色适配）。
     *
     * 没改过强调色时返回色板里的静态值 —— 此时 [apply] 是纯粹的空操作。
     */
    fun effectiveAccent(context: Context): Int {
        val dark = ThemeManager.isDark(context)
        val static = if (dark) DARK_ACCENT else LIGHT_ACCENT
        val raw = runCatching { Accent.current(context).argb }.getOrDefault(static)
        return ensureContrast(raw, if (dark) DARK_BG else LIGHT_BG)
    }

    /** 把 [root] 子树里的旧强调色改写成当前强调色。幂等，可反复调用。 */
    fun apply(root: View?) {
        val node = root ?: return
        val ctx = node.context ?: return
        val accent = runCatching { effectiveAccent(ctx) }.getOrNull() ?: return
        val dark = runCatching { ThemeManager.isDark(ctx) }.getOrDefault(true)
        val static = if (dark) DARK_ACCENT else LIGHT_ACCENT
        val staticSoft = if (dark) DARK_SOFT else LIGHT_SOFT
        if (accent == static) {
            // 未自定义：静态资源本来就是对的，整棵树都不用动
            return
        }
        val soft = if (dark) {
            (SOFT_ALPHA shl 24) or (accent and 0xFFFFFF)
        } else {
            blendOnWhite(accent, SOFT_ALPHA / 255f)
        }
        walk(node) { view -> tint(view, accent, soft, static, staticSoft) }
    }

    // ---------------------------------------------------------------- 明暗适配

    /** 与 accent_soft 同构的浅色软底：把强调色按 12% 叠到白底上。 */
    private fun blendOnWhite(color: Int, ratio: Float): Int = argb(
        255,
        mixc(red(color), 255, ratio),
        mixc(green(color), 255, ratio),
        mixc(blue(color), 255, ratio),
    )

    /** 逐级向黑（浅底）或向白（深底）靠，直到对 [bg] 的对比度达到 [MIN_CONTRAST]。 */
    private fun ensureContrast(color: Int, bg: Int): Int {
        if (contrastRatio(color, bg) >= MIN_CONTRAST) return color
        val towardWhite = luminance(bg) < 0.5
        var best = color
        for (step in 1..40) {
            val r = step / 40f
            val target = if (towardWhite) 255 else 0
            best = argb(
                255,
                mixc(red(color), target, r),
                mixc(green(color), target, r),
                mixc(blue(color), target, r),
            )
            if (contrastRatio(best, bg) >= MIN_CONTRAST) return best
        }
        return best
    }

    private fun mixc(from: Int, to: Int, ratio: Float): Int =
        (from + (to - from) * ratio).toInt().coerceIn(0, 255)

    private fun red(c: Int): Int = (c shr 16) and 0xFF
    private fun green(c: Int): Int = (c shr 8) and 0xFF
    private fun blue(c: Int): Int = c and 0xFF

    private fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(red(color)) + 0.7152 * channel(green(color)) + 0.0722 * channel(blue(color))
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = if (la > lb) la else lb
        val lo = if (la > lb) lb else la
        return (hi + 0.05) / (lo + 0.05)
    }

    // ---------------------------------------------------------------- 遍历与改写

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
        }
    }

    private fun tint(view: View, accent: Int, soft: Int, static: Int, staticSoft: Int) {
        // 1) 文字色。原始值存起来，每次从原始值重算 —— 换色不会残留上一次的结果。
        if (view is TextView) {
            val original = textOriginals.getOrPut(view) { view.textColors }
            if (original != null && !original.isStateful) {
                val mapped = mapColor(original.defaultColor, accent, static)
                if (mapped != original.defaultColor) {
                    view.setTextColor(mapped)
                } else if (view.textColors !== original && view.textColors.defaultColor != original.defaultColor) {
                    view.setTextColor(original)
                }
            } else if (original != null) {
                // 多状态列表（Material 的按钮文字色 m3_text_button_foreground_color_selector
                // 就是一张 selector，对话框的 Cancel / OK 全走它）。列表里的色值数组是
                // @hide 取不到，但 getCurrentTextColor() 是公开 API，返回的是**当前状态
                // 实际解析出来的颜色** —— 用它判断这个按钮是否正在用强调色。
                // 命中就换成自造的等价格式 selector：启用=新强调色，禁用=强调色 38%。
                val current = view.currentTextColor
                if (current == static || current == LIGHT_ACCENT || current == DARK_ACCENT) {
                    view.setTextColor(accentSelector(accent))
                }
            }
        }

        // 2) tint 类（ImageView / CompoundButton / Switch / ProgressBar / backgroundTint）。
        //    这些列表由静态资源解析而来，非多状态时只有 defaultColor 一个值。
        if (view is ImageView) {
            view.imageTintList = remapTint(view.imageTintList, accent, static)
        }
        if (view is CompoundButton) {
            view.buttonTintList = remapTint(view.buttonTintList, accent, static)
        }
        if (view is Switch) {
            view.thumbTintList = remapTint(view.thumbTintList, accent, static)
            view.trackTintList = remapTint(view.trackTintList, accent, static)
        }
        if (view is ProgressBar) {
            view.progressTintList = remapTint(view.progressTintList, accent, static)
            view.indeterminateTintList = remapTint(view.indeterminateTintList, accent, static)
        }

        // 3) 背景：采样判断它是不是「强调色实心/描边」，是就换成当前强调色。
        tintBackground(view, accent, static, soft, staticSoft)
    }

    /**
     * 背景里的强调色。
     *
     * 采样而不是读属性：GradientDrawable 的 getColor/getStrokeColor 是 @hide。
     * 判定命中后改用 backgroundTintList 上色 —— 它对实心与描边同样生效，
     * 且不会破坏圆角等形状属性（重新 setColor 就需要 mutate，容易改到共享实例）。
     */
    private fun tintBackground(view: View, accent: Int, static: Int, soft: Int, staticSoft: Int) {
        val bg: Drawable = view.background ?: return
        val known = bgAccent[view] == true
        if (!known) {
            val sampled = sampleColors(bg)
            if (sampled.isEmpty()) return
            val hit = sampled.any { it == static || it == staticSoft }
            if (!hit) return
            bgAccent[view] = true
        }
        val wanted = view.backgroundTintList
        if (wanted != null && !wanted.isStateful && wanted.defaultColor == accent) return
        view.backgroundTintList = ColorStateList.valueOf(accent)
    }

    /**
     * 把 drawable 画进一张 [SAMPLE]×[SAMPLE] 的位图，返回出现过的非透明像素色。
     *
     * 采样点覆盖中心与四边中点：实心形状看中心，纯描边形状（outline 胶囊）看边缘。
     * 失败（drawable 为空、尺寸为 0）时返回空，调用方据此跳过。
     */
    private fun sampleColors(drawable: Drawable): Set<Int> {
        return runCatching {
            // ★必须采样**副本**，绝不能碰 View 正在用的那个实例：
            //   setBounds 会改它的 bounds，而 View 只在 mBackgroundSizeChanged 为 true
            //   时才重设背景 bounds —— 我们改完再"还原"也救不回来，因为此刻
            //   （对话框 onShow 阶段）它的 bounds 本来还是空的，一改就再也画不出来。
            //   实测后果：卡片左上角多出 24×24 的白色小圆点；对话框背景整块消失、
            //   只剩 DIM_BEHIND 的变暗，看起来像"弹窗变透明了"。
            val copy = drawable.constantState?.newDrawable()?.mutate() ?: return emptySet()
            val bmp = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            copy.setBounds(0, 0, SAMPLE, SAMPLE)
            copy.draw(canvas)
            val points = listOf(
                SAMPLE / 2 to SAMPLE / 2,
                0 to SAMPLE / 2,
                SAMPLE - 1 to SAMPLE / 2,
                SAMPLE / 2 to 0,
                SAMPLE / 2 to SAMPLE - 1,
            )
            val out = HashSet<Int>()
            for ((x, y) in points) {
                val c = bmp.getPixel(x, y)
                if (android.graphics.Color.alpha(c) > 0x20) out.add(c)
            }
            bmp.recycle()
            out
        }.getOrDefault(emptySet())
    }

    /** 单个颜色：命中旧强调色/旧软色就换成新的，否则原样返回。 */
    private fun mapColor(color: Int, accent: Int, static: Int): Int = when (color) {
        LIGHT_ACCENT, DARK_ACCENT, static -> accent
        LIGHT_SOFT, DARK_SOFT -> (SOFT_ALPHA shl 24) or (accent and 0xFFFFFF)
        else -> color
    }

    /** tint 列表：非多状态且命中旧强调色时换成新色；多状态（selector）原样保留。 */
    private fun remapTint(list: ColorStateList?, accent: Int, static: Int): ColorStateList? {
        if (list == null || list.isStateful) return list
        val mapped = mapColor(list.defaultColor, accent, static)
        return if (mapped == list.defaultColor) list else ColorStateList.valueOf(mapped)
    }

    /**
     * 与 Material 文本按钮选择器同构：禁用态是主色 38%，其余状态用主色本身。
     *
     * 为什么自己造：Material 的 selector 色值藏在 @color 资源数组里（@hide 取不到），
     * 无法"只改颜色、保留状态语义"，所以按它公开的行为（禁用=38%）重建一份。
     */
    private fun accentSelector(accent: Int): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_enabled),
            intArrayOf(),
        ),
        intArrayOf(
            accent,
            (0x26 shl 24) or (accent and 0xFFFFFF),
        ),
    )

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}

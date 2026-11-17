package app.dsh.mobile

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.provider.Settings

/**
 * 系统撞色（Monet）原生取色（v1.8.1）。
 *
 * ## 为什么不再用 DynamicColors
 *
 * 原先 DYNAMIC 模式在 [ThemedActivity.onCreate] 里调
 * `DynamicColors.applyToActivityIfAvailable(this)`。它在 API 31+ 的实现是
 * `ColorResourcesOverride.applyIfPossible(...)` → 注册 ResourcesLoader 替换
 * colorPrimary / colorSurface 等一批资源 id。**这会改掉本 Activity 的 Resources
 * 配置**，系统随后再派发一次 configuration change，导致连续两次 relaunch ——
 * 真机/模拟器实测（Android 14 / API 34）点一下「动态取色」出现两次
 * `Schedule relaunch activity`，用户看到的就是「点一下就闪屏」。
 *
 * 而且它替换的是 Material 语义色（colorPrimary 等），我们自己的 [dsh*] 令牌
 * 是 @color 直引，**根本不跟着它变** —— 于是出现「页面底色被 Monet 换了、
 * 卡片和文字还是旧的」的半截态，视觉上更像闪。
 *
 * ## 现在怎么取色
 *
 * 直接读系统的 Monet 四个 accent 资源（Android 12+ 由 SystemUI 提供），
 * 与我们自己的强调色体系（[AccentColor]）对接：不注册 ResourcesLoader、
 * 不改配置、不触发额外 relaunch。取不到（旧系统/厂商阉割）时回落预设色。
 *
 * 为什么不走 `MaterialColors.getColor(ctx, colorPrimary)`：那读的是**本主题**
 * 的 colorPrimary，而 DYNAMIC 下我们从没把 Monet 装进主题（见上），读出来是
 * 我们自己的 md3_primary，等于没生效。
 */
object SystemAccent {

    /** 系统是否支持 Monet 取色（Android 12 / API 31）。 */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 系统强调色（用于文字/图标等**前景**），取不到返回 [fallback]。
     *
     * 读 `android.R.color.system_accent1_600`：600 档是 Monet 给浅色主题选的
     * 「可读前景」色阶，对白底的对比度已经过 Material 团队校准。
     */
    fun primary(ctx: Context, dark: Boolean, fallback: Int): Int {
        if (!supported) return fallback
        val id = if (dark) {
            android.R.color.system_accent1_200
        } else {
            android.R.color.system_accent1_600
        }
        return read(ctx, id, fallback)
    }

    /** 系统强调色的实心填充版（用于色块、选中底），取不到返回 [fallback]。 */
    fun container(ctx: Context, dark: Boolean, fallback: Int): Int {
        if (!supported) return fallback
        val id = if (dark) android.R.color.system_accent1_100 else android.R.color.system_accent1_100
        return read(ctx, id, fallback)
    }

    /**
     * 读一个系统色资源。
     *
     * 必须用 `resources.getColor(id, theme)`（API 23+）而不是
     * `Settings.Secure` 里的字符串：后者在部分 ROM（ColorOS/HyperOS）是空串，
     * 前置判断反而更脆。getColor 对不存在的资源会抛 Resources.NotFoundException，
     * 统一 runCatching 兜底。
     */
    private fun read(ctx: Context, id: Int, fallback: Int): Int =
        runCatching { ctx.resources.getColor(id, ctx.theme) }
            .getOrElse { fallback }
            .let { if (Color.alpha(it) == 0) fallback else it }

    /** 调试用：当前系统 accent1 主色（十六进制），仅日志/面板展示。 */
    fun describe(ctx: Context): String {
        if (!supported) return "unsupported"
        val dark = ThemeManager.isDark(ctx)
        val c = primary(ctx, dark, 0)
        return if (c == 0) "unavailable" else String.format("#%06X", c and 0xFFFFFF)
    }
}

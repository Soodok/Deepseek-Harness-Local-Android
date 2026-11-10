package app.dsh.mobile

import android.app.Activity
import android.os.Build

/**
 * 主题引擎（v1.6.x）：把「用户选的颜色来源」翻译成 Material 3 语义色。
 *
 * ## 为什么需要它
 *
 * 之前动态取色散在 `ThemedActivity` 里，且只有「跟随壁纸」这一条路真正生效：
 * 预设色和自定义色只改了 `Accent` 的旧式 tint，`?attr/colorPrimary` 这类
 * 原生 MD3 组件根本不动 —— 于是「主题页」看起来能选，实际只有动态色有用。
 *
 * ## 三条来源，统一出口
 *
 *  · [ThemeAccent.Mode.DYNAMIC] —— 系统壁纸的 Monet 取色（Android 12+）
 *  · [ThemeAccent.Mode.PRESET]  —— 以预设强调色为种子，生成完整 MD3 色板
 *  · [ThemeAccent.Mode.CUSTOM]  —— 以自定义颜色为种子，生成完整 MD3 色板
 *
 * 后两者走 Material 的 content-based scheme：从种子色推导
 * primary / surface / container 全套色阶，语义关系仍然符合 MD3，
 * 而不是像 Monet 那样被替换成壁纸的中性灰。
 *
 * ## 为什么不放在 attachBaseContext
 *
 * 它需要 `setTheme()` 之后的 Activity 主题对象；早于 setTheme 应用会被重置。
 */
object ThemeEngine {

    /** Android 12（API 31）起才有系统动态色能力。 */
    val dynamicSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 把当前颜色来源注入 Activity 主题。必须在 `setTheme()` 之后调用。
     *
     * 全程 runCatching：取色是外观功能，任何厂商 ROM 的异常都不该让页面起不来。
     */
    fun apply(activity: Activity) {
        if (!dynamicSupported) return
        val mode = ThemeAccent.mode(activity)
        runCatching {
            when (mode) {
                ThemeAccent.Mode.DYNAMIC ->
                    com.google.android.material.color.DynamicColors
                        .applyToActivityIfAvailable(activity)

                ThemeAccent.Mode.PRESET, ThemeAccent.Mode.CUSTOM ->
                    com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(
                        activity,
                        com.google.android.material.color.DynamicColorsOptions.Builder()
                            .setContentBasedSource(seedColor(activity))
                            .build(),
                    )
            }
        }
    }

    /** 当前来源对应的种子色：预设取枚举色，自定义取用户挑的颜色。 */
    fun seedColor(activity: Activity): Int =
        when (ThemeAccent.mode(activity)) {
            ThemeAccent.Mode.CUSTOM -> ThemeAccent.customColor(activity)
            else -> Accent.currentPreset(activity).argb
        }

    /** 主题页展示用的当前来源名称。 */
    fun currentLabelRes(activity: Activity): Int =
        when (ThemeAccent.mode(activity)) {
            ThemeAccent.Mode.DYNAMIC -> R.string.theme_color_dynamic
            ThemeAccent.Mode.CUSTOM -> R.string.theme_custom
            ThemeAccent.Mode.PRESET -> Accent.currentPreset(activity).labelRes
        }
}

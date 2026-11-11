package app.dsh.mobile

/**
 * 数值格式化（v1.7.5）—— 端侧算出来的数字，回给调用方前统一在这里收敛。
 *
 * ## 为什么需要它
 * `"%.2f".format(v)` 用的是**默认 Locale**，跟随系统语言。pt-BR / de / ru / fr
 * 这些用逗号当小数点的地区会产出 "0,00"，紧接着 `.toDouble()` 就抛
 * `NumberFormatException: For input string: "0,00"`。
 *
 * 实测（emulator-5556，系统语言 pt-BR）：带 `useMemory:true` 的 `POST /loop/run`
 * **必崩**，原样错误文本 `loop execution failed: For input string: '0,00'`；
 * 而 zh-CN 设备上格式化成 "0.00" 一切正常 —— 所以这个 bug 藏了很久，
 * 只在逗号小数点地区才暴露。
 *
 * ## 结论
 * 数值就走算术，不要经过字符串。任何"为了保留两位小数"而 format→parse 的写法
 * 都是同一个坑，改成 `NumFmt.round2(v)`。
 */
object NumFmt {

    /**
     * 取两位小数，四舍五入（0.5 一律进位）。
     *
     * 与 `"%.2f"` 的差别：不受 Locale 影响，且永远返回 Double。
     *
     * 为什么用 `Math.round` 而不是 `kotlin.math.round`：后者的契约是
     * 「ties rounded towards even integer」（等价 `Math.rint`），62.5 会变成 62 ——
     * 做相似度显示时这种「有时进位有时不进位」很难解释。`Math.round` 是 half-up。
     */
    fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}

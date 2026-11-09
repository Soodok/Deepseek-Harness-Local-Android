package app.dsh.mobile

/**
 * 屏幕文档的预算装配：把已经渲染好的四个区段文本按字符预算组合成最终输出。
 *
 * 之所以单独成文件而不留在 ScreenDoc 里 —— 它必须是纯字符串逻辑，不碰
 * AccessibilityNodeInfo，才能在 JVM 上真跑断言。「顶部+底部吃满预算时中部会不会被
 * 静默丢弃」这类缺陷，源码 grep 永远看不出来，只有真跑才露馅。
 */
object ScreenBudget {

    /** 单页字符预算。超出后保顶部与底部（导航区），截断中部。 */
    const val CHAR_BUDGET = 3000

    /**
     * 预算装配：把已经渲染好的四个区段文本按 CHAR_BUDGET 组合。
     * 抽成纯字符串函数（不碰 AccessibilityNodeInfo）是为了能在 JVM 上直接断言 ——
     * 「顶部+底部吃满预算时中部会不会被静默丢弃」这类缺陷只有真跑才能发现。
     */
    fun assemble(
        headText: String,
        topText: String,
        middleText: String,
        bottomText: String,
        middleCount: Int,
        truncatedNote: String = "",
    ): String {
        val fullBody = StringBuilder()
        fullBody.append(topText).append(middleText).append(bottomText).append(truncatedNote)
        if (headText.length + fullBody.length <= CHAR_BUDGET) return headText + fullBody

        // 超预算：保顶部与底部（导航区），中部截断。
        val avail = CHAR_BUDGET - headText.length - topText.length - bottomText.length - 60
        val out = StringBuilder()
        out.append(topText)
        if (middleText.isNotEmpty()) {
            if (avail > 0 && middleText.length <= avail) {
                out.append(middleText)
            } else if (avail <= 0) {
                // 顶部 + 底部已经吃满预算（长列表页常见：顶部工具栏密、底部导航密）。
                // 此前这里两个分支都不进，中部整段被**静默**丢弃 —— 模型看到的是
                // 「这页只有顶部和底部」，既不知道中间有内容，也不知道被截了，
                // 于是它会判断「页面上没有目标」并放弃。现在明说。
                out.append("中部\n  …（中部 ").append(middleCount).append(" 项因预算未列出）\n")
            } else {
                val kept = StringBuilder()
                var used = 0
                var stopped = false
                for (line in middleText.lines()) {
                    if (line == "中部") continue
                    if (used + line.length + 1 > avail) {
                        stopped = true
                        break
                    }
                    kept.append(line).append('\n')
                    used += line.length + 1
                }
                out.append("中部\n").append(kept)
                if (stopped) out.append("…（中部还有内容未列出）\n")
            }
        }
        out.append(bottomText)
        return headText + out
    }
}
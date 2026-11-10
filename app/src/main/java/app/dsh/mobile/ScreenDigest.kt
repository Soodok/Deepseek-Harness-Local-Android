package app.dsh.mobile

/**
 * 屏幕去重（v1.6.21）。
 *
 * ## 为什么需要
 *
 * 一次点击后，整页往往只变了一两行。把整份 3000 字符的文档重发一遍，
 * 既烧 token 又稀释注意力 —— 模型要在几十行里找出「哪里不一样」。
 *
 * ## 怎么减
 *
 * 若编号顺序没变、只有少数行变了，就只列变化行，并明说其余与上一屏相同。
 * 若整页都没变，只回一句「与 s7 相同，编号不变」。
 * 若变化超过三分之一，**照旧输出全量** —— 这时候省下的 token 不值得
 * 让模型去猜哪些编号还在。
 *
 * ## 为什么是纯字符串
 *
 * 不碰 AccessibilityNodeInfo，才能在 JVM 上真跑断言。「变化超过三分之一就
 * 退回全量」这种边界，源码看一眼是看不出来的。
 */
object ScreenDigest {

    /** 行首编号：`  [12] ...` 里的 12。 */
    private val ROW = Regex("^\\s*\\[(\\d+)\\]")

    /** 整页没变时的提示。 */
    fun sameNote(previousScreenId: String): String =
        "（与 " + previousScreenId + " 相同，编号不变）"

    /** 只列变化时的提示。 */
    fun partialNote(keptCount: Int, previousScreenId: String): String =
        "（只列变化：其余 " + keptCount + " 项与 " + previousScreenId + " 相同，编号不变）"

    /** 从渲染行里取编号；不是元素行则返回 null。 */
    fun rowId(line: String): Int? {
        val m = ROW.find(line) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /**
     * 生成精简版文档。
     *
     * @return 精简后的文本；返回 null 表示**应当输出全量**
     */
    fun reduce(
        currentText: String,
        previousScreenId: String,
        previous: List<String>,
        now: List<String>,
    ): String? {
        if (previousScreenId.isEmpty()) return null
        if (previous.size != now.size) return null

        val changed = ArrayList<Int>()
        for (i in now.indices) {
            if (previous[i] != now[i]) {
                val id = now[i].substringBefore(':').toIntOrNull() ?: return null
                changed.add(id)
            }
        }

        val all = currentText.lines()
        val header = all.firstOrNull { it.isNotEmpty() } ?: ""

        if (changed.isEmpty()) return header + "\n" + sameNote(previousScreenId)

        // 变化超过三分之一就退回全量：省下的 token 不值得让模型去猜编号。
        if (changed.size * 3 > now.size) return null

        val keep = HashSet(changed)
        val out = StringBuilder()
        out.append(header).append('\n')
        var kept = 0
        var pendingZone: String? = null
        for (line in all.drop(1)) {
            val id = rowId(line)
            if (id == null) {
                // 区段标题先挂起：该区段一行都没保留，就不要输出空标题。
                if (line.isNotBlank()) pendingZone = line
                continue
            }
            if (keep.contains(id)) {
                if (pendingZone != null) {
                    out.append(pendingZone).append('\n')
                    pendingZone = null
                }
                out.append(line).append('\n')
                kept++
            }
        }
        out.append(partialNote(now.size - kept, previousScreenId)).append('\n')
        return out.toString()
    }
}
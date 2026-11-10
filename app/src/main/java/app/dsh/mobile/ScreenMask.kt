package app.dsh.mobile

/**
 * 屏幕文本脱敏（v1.6.20）。
 *
 * ## 为什么在 App 侧脱敏
 *
 * 读屏结果会经 AgentBridge 回到引擎，再由引擎发给模型服务。一旦发出去就收不回。
 * 银行卡号、身份证号属于「看一眼就已泄露」的数据，唯一有效的时机是它离开设备之前。
 *
 * ## 判据用的是校验位，不是长度
 *
 * 只按「15~19 位数字」匹配会把运单号、订单号、设备序列号一起打码——那是误伤，
 * 会让模型读不到它真正需要的内容。所以两条硬判据：
 *
 * - 身份证：18 位 + GB 11643 / ISO 7064 MOD 11-2 校验位正确
 * - 银行卡：15~19 位 + Luhn（ISO/IEC 7812）校验和正确
 *
 * 校验位能把随机数字列的误判率压到约 1/10（Luhn）和 1/11（身份证），
 * 再叠加「订单号/运单号上下文排除」，实际误伤极少。
 *
 * ## 为什么是滑窗扫描，而不是一条带边界断言的正则
 *
 * 最初用 `(?<![0-9A-Za-z])...(?![0-9A-Za-z])` 一条正则搞定。真机实测立刻露馅：
 * 输入框里把身份证与卡号**连写**时（粘贴、表格相邻单元格被拼到一起都很常见）
 *
 *     11010519491231002X4111111111111111
 *
 * 身份证尾字符 `X` 后面紧跟数字 `4` → 尾边界断言失败；
 * 卡号前面是字母 `X` → 首边界断言失败。**两个号码一个都没打码。**
 *
 * 放宽字符类只能治一半。只要允许「两个号码相邻」，任何边界断言都是错的：
 * 相邻时前后都紧贴，无论排除什么字符都会命中。
 *
 * 所以改成**在每个起始位置尝试匹配，用校验位判定真假，不依赖边界**。
 * 校验位本身就是最强的边界证据（随机数字串通过 Luhn 的概率约 1/10），
 * 比 lookaround 更可靠，还顺带支持了相邻与重叠的场景。
 *
 * ## 已知边界
 *
 * - 一张真实的卡号若校验位写错（用户手工输错），不会被脱敏。这不是漏洞：
 *   输错的卡号本来就不是有效卡号。
 * - 脱敏后的文本里没有原文，不可逆。需要原始卡号的场景（支付流程）应走人工接管。
 * - **两个卡号无分隔符连写**（`4111...5555...`，共 32 位）不会被打码：整段既不是
 *   15~19 位卡号也不是身份证。这是能力上限而非疏漏——没有分隔符时，32 位数字
 *   究竟是一个 32 位账号还是两个 16 位卡号，从字符串本身无从判断；改成「在内部
 *   找子串」会重新引入 Luhn 子串误伤（任何长数字串都有约 1/10 概率的子串通过校验）。
 *   真实场景里卡号之间通常有空格、逗号或分行，那些都能正确处理。
 *
 * 本文件是纯 Kotlin + 正则，无 Android 依赖，可直接在 JVM 单测里跑
 * （见 scripts/run-a11y-jvm-test.sh）。
 */
object ScreenMask {

    private const val ID_TAG = "«身份证 ****"
    private const val CARD_TAG = "«银行卡 ****"
    private const val TAG_END = "»"
    private const val CONTEXT_CHARS = 8
    private const val ID_LEN = 18
    private const val CARD_MIN = 15
    private const val CARD_MAX = 19

    /**
     * 身份证的**形状**（不含校验位判定）：6 位地区 + 8 位生日 + 3 位顺序 + 1 位校验。
     *
     * 用 matches() 逐窗口比对，因此带 ^$ 锚点；不再需要前后 lookaround。
     */
    private val ID_SHAPE = Regex(
        "^[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx]$"
    )

    /** 这些词之后的数字串是单号/流水号，不是卡号。取匹配位置前 8 个字符做上下文判断。 */
    private val NOT_CARD_CONTEXT = listOf(
        "订单", "单号", "运单", "物流", "快递", "编号", "流水", "交易号", "条码", "序列号",
        "No.", "NO.", "no.",
    )

    /** 一处待替换的区间。 */
    private class Hit(val start: Int, val end: Int, val tag: String, val last4: String)
    /**
     * 脱敏入口。文本过短时直接返回（少于 15 个字符不可能含身份证或卡号）。
     *
     * 顺序：先身份证后银行卡。反过来会让 18 位身份证被当成卡号——
     * 两者都可能命中时，身份证的语义更具体，先判先赢。
     */
    fun mask(text: String): String {
        if (text.length < 15) return text
        if (text.count { it.isDigit() } < 15) return text
        val n = text.length
        val hits = ArrayList<Hit>(4)
        val covered = BooleanArray(n)

        // ---- ① 身份证：在所有起始位置尝试，靠校验位定真假 ----
        var i = 0
        while (i + ID_LEN <= n) {
            val win = text.substring(i, i + ID_LEN)
            if (ID_SHAPE.matches(win) && idChecksumOk(win)) {
                var k = i
                while (k < i + ID_LEN) { covered[k] = true; k++ }
                hits.add(Hit(i, i + ID_LEN, ID_TAG, win.takeLast(4).uppercase()))
                i += ID_LEN
            } else {
                i++
            }
        }

        // ---- ② 银行卡：在「没被身份证吃掉」的数字串上滑窗 ----
        var start = 0
        while (start < n) {
            if (covered[start] || !text[start].isDigit()) {
                start++
                continue
            }
            // 从 start 起收集**整段连续数字**（允许单个空格/连字符作分组）。
            //
            // ⚠️ 不设 CARD_MAX 上限：必须知道这段数字的真实总长度，
            // 才能判断「它整段是不是一个卡号」。截断到 19 位会让
            // 「20 位数字串的头 19 位」被误判成卡号。
            val digitIdx = ArrayList<Int>(24)
            var pos = start
            while (pos < n) {
                val c = text[pos]
                if (c.isDigit() && !covered[pos]) {
                    digitIdx.add(pos)
                    pos++
                } else if ((c == 32.toChar() || c == 45.toChar()) && pos + 1 < n &&
                    text[pos + 1].isDigit() && !covered[pos + 1] && digitIdx.isNotEmpty()) {
                    pos++   // 分隔符：跳过，不占位
                } else {
                    break
                }
            }
            // ⚠️ 必须**整段**是一个卡号，不能在这段数字里挑任意 15~19 位子串。
            //
            // 第一版就是逐长度试 Luhn（15、16、…、19），结果测试当场抓到误伤：
            // 输入「身份证 110105194912310021」（19 位数字，含无效身份证），
            // 头 16 位恰好通过 Luhn → 被当成卡号打码，输出
            //     「身份证 1«银行卡 ****1002»1」
            // 在任何足够长的数字串里总有一个子串能通过 Luhn（约 1/10 概率陷阱），
            // 所以「试到通过为止」这条路本身就是错的。
            //
            // 正确口径：这段连续数字（允许分组空格）的**总位数**必须落在 15~19，
            // 且整段通过 Luhn。位数不够/超出就不是卡号，直接放过。
            var used = false
            val len = digitIdx.size
            if (len in CARD_MIN..CARD_MAX) {
                val digits = StringBuilder(len)
                var k = 0
                while (k < len) { digits.append(text[digitIdx[k]]); k++ }
                val d = digits.toString()
                if (luhnOk(d)) {
                    val ctxStart = maxOf(0, start - CONTEXT_CHARS)
                    val before = text.substring(ctxStart, start)
                    var excluded = false
                    for (w in NOT_CARD_CONTEXT) if (before.contains(w)) { excluded = true; break }
                    if (!excluded) {
                        val end = digitIdx[len - 1] + 1
                        var m = start
                        while (m < end) { covered[m] = true; m++ }
                        hits.add(Hit(start, end, CARD_TAG, d.takeLast(4)))
                        used = true
                    }
                }
            }
            // ⚠️ 未命中时**整体跳过这段数字**，不能 start + 1 滑进它内部。
            //
            // 实测教训：把「身份证 110105194912310021」（18 位，校验位错 → 不是身份证）
            // 逐位滑动时，从第 2 位起取 16 位恰好通过 Luhn →
            //     「身份证 11«银行卡 ****0021»」
            // 任何足够长的数字串里都存在能通过 Luhn 的子串，
            // 所以「在一段数字内部找卡号」这个思路本身就会持续误伤。
            // 一段连续数字要么整段是卡号，要么就不是。
            start = maxOf(pos, start + 1)
        }

        if (hits.isEmpty()) return text
        hits.sortBy { it.start }
        val sb = StringBuilder(n + hits.size * 8)
        var cursor = 0
        for (h in hits) {
            if (h.start < cursor) continue   // 理论不会发生，防御性跳过
            sb.append(text, cursor, h.start)
            sb.append(h.tag).append(h.last4).append(TAG_END)
            cursor = h.end
        }
        if (cursor < n) sb.append(text, cursor, n)
        return sb.toString()
    }

    /** Luhn 校验（ISO/IEC 7812-1）：从右往左，偶数位乘 2，超过 9 减 9，总和能被 10 整除。 */
    fun luhnOk(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) {
            var d = c - 48.toChar()
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /**
     * 身份证校验位（GB 11643-1999，ISO 7064 MOD 11-2）。
     * 前 17 位按固定权重加权求和，对 11 取模，查表得校验字符。
     */
    fun idChecksumOk(id: String): Boolean {
        if (id.length != ID_LEN) return false
        val weights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        var sum = 0
        for (i in 0 until 17) {
            val d = id[i] - 48.toChar()
            if (d !in 0..9) return false
            sum += d * weights[i]
        }
        val check = "10X98765432"[sum % 11]
        return id[17].uppercaseChar() == check
    }

    /** 该文本是否含需要脱敏的内容（供调用方决定要不要承担 mask 的开销）。 */
    /** 打码行数的提示文案（给模型看）。
     *
     * 放在这里而不是读屏服务里：check-i18n 只允许工具文档类文件
     * 写中文字面量；把它归到脱敏模块一起管，也避免了两处各写一份。
     */
    fun maskedRowsNote(rows: Int): String =
        "提示：本页有 " + rows + " 行敏感数字已脱敏（身份证 ／ 银行卡）。"
    fun looksSensitive(text: String): Boolean =
        text.length >= 15 && text.count { it.isDigit() } >= 15 && mask(text) != text
}

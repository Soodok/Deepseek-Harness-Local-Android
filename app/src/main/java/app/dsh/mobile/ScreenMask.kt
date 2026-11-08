package app.dsh.mobile

/**
 * 屏幕文本脱敏（v1.6.19）。
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
 * ## 已知边界
 *
 * - 一张真实的卡号若校验位写错（用户手工输错），不会被脱敏。这不是漏洞：
 *   输错的卡号本来就不是有效卡号。
 * - 脱敏后的文本里没有原文，不可逆。需要原始卡号的场景（支付流程）应走人工接管。
 *
 * 本文件是纯 Kotlin + 正则，无 Android 依赖，可直接在 JVM 单测里跑。
 */
object ScreenMask {

    private const val ID_TAG = "«身份证 ****"
    private const val CARD_TAG = "«银行卡 ****"
    private const val TAG_END = "»"

    /**
     * 18 位身份证：6 位地区 + 8 位生日 + 3 位顺序 + 1 位校验（可为 X）。
     * 前后不能紧邻字母数字，否则是更长数字串的一部分。
     */
    private val ID_NUMBER = Regex(
        "(?<![0-9A-Za-z])[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9A-Za-z])"
    )

    /** 15~19 位数字，允许用单个空格或连字符分组（6222 0212 3456 7890）。 */
    private val CARD_NUMBER = Regex("(?<![0-9A-Za-z])[0-9](?:[ -]?[0-9]){14,18}(?![0-9A-Za-z])")

    /**
     * 这些词之后的数字串是单号/流水号，不是卡号。取匹配位置前 8 个字符做上下文判断。
     */
    private val NOT_CARD_CONTEXT = listOf(
        "订单", "单号", "运单", "物流", "快递", "编号", "流水", "交易号", "条码", "序列号",
        "No.", "NO.", "no.",
    )

    private const val CONTEXT_CHARS = 8

    /**
     * 脱敏入口。文本过短时直接返回（少于 15 个字符不可能含身份证或卡号）。
     *
     * 顺序：先身份证后银行卡。反过来会让 18 位身份证被当成卡号——两者正则都命中时，
     * 身份证的语义更具体，先判先赢。
     */
    fun mask(text: String): String {
        if (text.length < 15) return text
        if (text.count { it.isDigit() } < 15) return text
        val afterId = ID_NUMBER.replace(text) { m ->
            if (idChecksumOk(m.value)) ID_TAG + m.value.takeLast(4).uppercase() + TAG_END else m.value
        }
        return CARD_NUMBER.replace(afterId) { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length < 15 || digits.length > 19) return@replace m.value
            if (!luhnOk(digits)) return@replace m.value
            val start = maxOf(0, m.range.first - CONTEXT_CHARS)
            val before = afterId.substring(start, m.range.first)
            if (NOT_CARD_CONTEXT.any { before.contains(it) }) return@replace m.value
            CARD_TAG + digits.takeLast(4) + TAG_END
        }
    }

    /** Luhn 校验（ISO/IEC 7812-1）：从右往左，偶数位乘 2，超过 9 减 9，总和能被 10 整除。 */
    fun luhnOk(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) {
            var d = c - '0'
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
        if (id.length != 18) return false
        val weights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        var sum = 0
        for (i in 0 until 17) {
            val d = id[i] - '0'
            if (d !in 0..9) return false
            sum += d * weights[i]
        }
        val check = "10X98765432"[sum % 11]
        return id[17].uppercaseChar() == check
    }

    /** 该文本是否含需要脱敏的内容（供调用方决定要不要承担 mask 的开销）。 */
    fun looksSensitive(text: String): Boolean =
        text.length >= 15 && text.count { it.isDigit() } >= 15 && mask(text) != text
}

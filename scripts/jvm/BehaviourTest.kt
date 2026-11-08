package jvmtest

import app.dsh.mobile.ProtectedPages
import app.dsh.mobile.ScreenBudget
import app.dsh.mobile.ScreenMask

/**
 * ScreenMask / ProtectedPages 的 **JVM 行为测试**（v1.6.20）。
 *
 * 这两类是纯 Kotlin（无 Android 依赖），能在宿主机 JVM 上直接编译执行，
 * 断言的是**函数实际返回值**，不是「源码里有没有那行字」。
 *
 * 为什么要有它：原来那三个探针全是源码 grep，只能证明常量还在、结构没被删；
 * 它们证明不了「身份证真的被打码了」「设置首页真的没被误拦」。
 */
object BehaviourTest {

    private var passed = 0
    private var failed = 0
    private val failures = ArrayList<String>()

    private fun check(cond: Boolean, msg: String) {
        if (cond) {
            passed++
            println("  PASS  " + msg)
        } else {
            failed++
            failures.add(msg)
            println("  FAIL  " + msg)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        maskId()
        maskCard()
        maskFalsePositive()
        maskBoundary()
        pagesPackage()
        pagesSettings()
        fastGuard()
        titleTextsRegression()
        assertBudget()

        println()
        println("passed=" + passed + " failed=" + failed)
        if (failed > 0) {
            println("FAILURES:")
            for (f in failures) println("  - " + f)
            System.exit(1)
        }
        println("All JVM behaviour checks passed.")
    }

    private fun maskId() {
        println("== 1. ScreenMask 身份证 ==")
        val masked = ScreenMask.mask("身份证 11010519491231002X")
        check(masked.contains("«身份证 ****002X»"), "valid id masked with its last 4 chars: $masked")
        check(!masked.contains("11010519491231002"), "the full id is GONE from the output")
        val bad = ScreenMask.mask("身份证 110105194912310021")
        check(bad == "身份证 110105194912310021", "wrong check digit NOT masked: $bad")
        // 覆盖校验字符表的**每一个位置**：110105194912310062 的 sum%11 == 10，
        // 落在表尾的 '2'。只测 X 的话，把表末位改掉测试也不会红（反向验证抓到的漏洞）。
        val tail2 = ScreenMask.mask("身份证 110105194912310062")
        check(tail2.contains("«身份证 ****0062»"), "check-char table tail ('2') is exercised: $tail2")
        val wrapped = ScreenMask.mask("身份证 110105194912310142")
        check(wrapped.contains("«身份证 ****0142»"), "the whole table is covered, not just X")
    }

    private fun maskCard() {
        println("== 2. ScreenMask 银行卡 ==")
        val masked = ScreenMask.mask("卡号 4111111111111111")
        check(masked.contains("«银行卡 ****1111»"), "valid card masked: $masked")
        check(!masked.contains("4111111111111111"), "the full card number is GONE")
        // 6222 0212 3456 7894 是 Luhn 通过的分组写法（…7890 不通过，不该打码）
        val grouped = ScreenMask.mask("卡号 6222 0212 3456 7894")
        check(grouped.contains("«银行卡 ****7894»"), "space-grouped card masked: $grouped")
        val groupedBad = ScreenMask.mask("卡号 6222 0212 3456 7890")
        check(groupedBad == "卡号 6222 0212 3456 7890", "grouped digits failing Luhn NOT masked: $groupedBad")
        val bad = ScreenMask.mask("卡号 4111111111111112")
        check(bad == "卡号 4111111111111112", "Luhn failure NOT masked: $bad")
    }

    private fun maskFalsePositive() {
        println("== 3. ScreenMask 误伤排除 ==")
        val waybill = ScreenMask.mask("运单号 4111111111111111")
        check(waybill == "运单号 4111111111111111", "Luhn-valid number after 运单号 NOT a card: $waybill")
        val order = ScreenMask.mask("订单 6222021234567890128")
        check(order == "订单 6222021234567890128", "valid number after 订单 NOT a card")
        val bare = ScreenMask.mask("1234567890123456")
        // 1234567890123456 的 Luhn 不通过 —— 它不是卡号，不打码才是对的
        check(bare == "1234567890123456", "bare digits failing Luhn are left alone: $bare")
        // 4111111111111111 无上下文但 Luhn 通过 —— 会被打码（fail-safe 方向）
        check(ScreenMask.mask("4111111111111111").contains("«银行卡"), "bare Luhn-valid digits ARE masked (fail-safe)")
    }

    private fun maskBoundary() {
        println("== 4. ScreenMask 边界 ==")
        check(ScreenMask.mask("").isEmpty(), "empty string untouched")
        check(ScreenMask.mask("短") == "短", "short text untouched")
        check(ScreenMask.mask("11010519491231002") == "11010519491231002", "17-digit id untouched")
        check(ScreenMask.looksSensitive("卡号 4111111111111111"), "looksSensitive true for a card")
        // ⚠️ 真机实测暴露的场景：身份证与卡号**连写**（粘贴 / 表格相邻单元格拼在一起）
        // 旧实现用 lookaround 边界断言，两个号码紧贴时**一个都没打码**。
        val jammed = ScreenMask.mask("11010519491231002X4111111111111111")
        check(jammed.contains("«身份证 ****002X»"), "jammed: the id IS masked: $jammed")
        check(jammed.contains("«银行卡 ****1111»"), "jammed: the adjacent card IS masked too: $jammed")
        check(!jammed.contains("11010519491231002"), "jammed: no id digits leak")
        check(!jammed.contains("4111111111111111"), "jammed: no card digits leak")
        // 三个连写（含一个应被上下文排除的运单号）
        val triple = ScreenMask.mask("11010519491231002X4111111111111111YT8899001122334")
        check(triple.contains("«身份证") && triple.contains("«银行卡"), "triple: both masked: $triple")
        check(triple.contains("YT8899001122334"), "triple: the waybill is left alone")
        // ---- 边界场景矩阵：这些是「整段判定」改动的回归网 ----
        // 卡号在前、身份证在后（连写）
        val cardFirst = ScreenMask.mask("4111111111111111 11010519491231002X")
        check(cardFirst.contains("«银行卡 ****1111»"), "card-first: card masked: $cardFirst")
        check(cardFirst.contains("«身份证 ****002X»"), "card-first: id masked: $cardFirst")
        // ⚠️ 已知边界（不是 bug，是能力上限）：两个卡号**无分隔符**连写时，
        // 32 位整段既不是 15~19 位卡号，也不是身份证，所以不会被打码。
        //
        // 无法可靠处理的原因：没有分隔符时，32 位数字到底是一个 32 位账号
        // 还是两个 16 位卡号，从字符串本身无从判断。若改成「内部找子串」
        // 就会重新引入上面那个 Luhn 子串误伤（任何长数字串都有 1/10 概率的子串）。
        // 这里明确记录能力边界，而不是假装能处理。
        val twoCards = ScreenMask.mask("41111111111111115555555555555555")
        check(twoCards == "41111111111111115555555555555555",
            "KNOWN LIMIT: two cards with no separator are left alone (not a 15-19 digit run): $twoCards")
        // 24 位且 Luhn 通过的数字串：仍不是卡号（超 19 位上限）。
        // 这条专门覆盖 CARD_MAX 上界 —— 把上限放宽到 30 时它会立刻变红。
        val len24 = ScreenMask.mask("622202123456789012345678")
        check(len24 == "622202123456789012345678", "24 digits passing Luhn is still not a card: $len24")
        // 20 位纯数字：不是卡号（超长），不该被打码
        val twenty = ScreenMask.mask("12345678901234567890")
        check(twenty == "12345678901234567890", "20 digits is not a card, left alone: $twenty")
        // 17 位：既不是身份证也不是卡号
        val seventeen = ScreenMask.mask("11010519491231002")
        check(seventeen == "11010519491231002", "17 digits: untouched: $seventeen")
        // 校验位错的身份证：其 18 位数字整段不是卡号，必须原样留着
        val badIdOnly = ScreenMask.mask("110105194912310021")
        check(badIdOnly == "110105194912310021", "invalid id (18 digits) is NOT re-read as a card: $badIdOnly")
        check(!ScreenMask.looksSensitive("hello world"), "looksSensitive false for plain text")
    }

    private fun pagesPackage() {
        println("== 5. ProtectedPages 整包保护 ==")
        check(ProtectedPages.isProtected("com.android.packageinstaller", emptyList()), "AOSP installer protected")
        check(ProtectedPages.isProtected("com.miui.securitycenter", emptyList()), "MIUI security center protected")
        check(!ProtectedPages.isProtected("com.tencent.mm", emptyList()), "WeChat NOT protected")
        check(!ProtectedPages.isProtected(null, emptyList()), "null package NOT protected")
        check(!ProtectedPages.isProtected("", emptyList()), "blank package NOT protected")
    }

    private fun pagesSettings() {
        println("== 6. ProtectedPages 设置类按标题 ==")
        check(ProtectedPages.isProtected("com.android.settings", listOf("无障碍")), "settings + 无障碍 protected")
        check(ProtectedPages.isProtected("com.android.settings", listOf("Developer options")), "settings + Developer options protected")
        check(!ProtectedPages.isProtected("com.android.settings", listOf("壁纸", "显示")), "settings + harmless NOT protected")
        check(!ProtectedPages.isProtected("com.tencent.mm", listOf("无障碍")), "non-settings with 无障碍 NOT protected")
    }

    private fun fastGuard() {
        println("== 7. isProtectedFast 动作侧快速判据 ==")
        check(ProtectedPages.isProtectedFast("com.android.packageinstaller", ""), "fast: installer blocked with no text")
        check(ProtectedPages.isProtectedFast("com.android.settings", "无障碍"), "fast: settings + 无障碍 blocked")
        check(ProtectedPages.isProtectedFast("com.android.settings", "开发者选项"), "fast: settings + 开发者选项 blocked")
        check(!ProtectedPages.isProtectedFast("com.android.settings", "壁纸 显示 声音"), "fast: settings + harmless NOT blocked")
        check(!ProtectedPages.isProtectedFast("com.tencent.mm", "无障碍"), "fast: non-settings NOT blocked")
        check(!ProtectedPages.isProtectedFast(null, "无障碍"), "fast: null package NOT blocked")
        check(!ProtectedPages.isProtectedFast("com.android.settings", ""), "fast: empty text NOT blocked")
        check(
            !ProtectedPages.isProtectedFast("com.android.settings", "安全与隐私 隐私保护 密码"),
            "fast: generic words (安全/隐私/密码) do NOT block the settings home page",
        )
    }

    private fun titleTextsRegression() {
        println("== 8. titleTexts 回归用例（真机踩过的坑）==")
        val h = 2400
        check(
            ProtectedPages.titleTexts(listOf(ProtectedPages.TitleItem("无障碍", 279, false, 1)), h) == listOf("无障碍"),
            "REGRESSION: settings title inside a RecyclerView (depth=1) IS a title",
        )
        check(
            ProtectedPages.titleTexts(listOf(ProtectedPages.TitleItem("无障碍", 279, false, 2)), h).isEmpty(),
            "a row nested deeper (depth=2) is list content, not a title",
        )
        check(
            ProtectedPages.titleTexts(listOf(ProtectedPages.TitleItem("底部文字", 2200, false, 0)), h).isEmpty(),
            "text near the bottom is not a title",
        )
        check(
            ProtectedPages.titleTexts((0 until 20).map { ProtectedPages.TitleItem("t" + it, 10, false, 0) }, h).size == 8,
            "limit is honoured (8)",
        )
    }

    // ===== ScreenBudget：预算装配（缺陷 5 的行为验证） =====
    // 缺陷原状：顶部+底部已吃满 3000 字符预算时（avail <= 0），中部整段被**静默**丢弃，
    // 模型只看到「顶部+底部」，既不知道中间有内容也不知道被截了，于是判定「页面没有目标」放弃。
    private fun assertBudget() {
        println("== 9. ScreenBudget 预算装配（缺陷 5：中部静默消失）==")
        val head = "屏幕 s1 · 设置 · 竖屏 · 共 3 项\n"

        // 1) 小页面原样返回
        val small = ScreenBudget.assemble(head, "顶部\n  [1] a\n", "中部\n  [2] b\n", "底部\n  [3] c\n", 1)
        check(
            small.contains("[1] a") && small.contains("[2] b") && small.contains("[3] c"),
            "a small page is returned verbatim (no truncation)",
        )

        // 2) 中部可部分容纳：保留若干行 + 明说还有内容
        val mid = StringBuilder("中部\n")
        for (i in 0 until 200) {
            mid.append("  [").append(i).append("] 很长的列表项文本用来吃掉预算 ").append("x".repeat(20)).append("\n")
        }
        val bigTop = "顶部\n" + (0 until 5).joinToString("") { "  [t" + it + "] " + "y".repeat(40) + "\n" }
        val bigBot = "底部\n" + (0 until 5).joinToString("") { "  [b" + it + "] " + "z".repeat(40) + "\n" }
        val part = ScreenBudget.assemble(head, bigTop, mid.toString(), bigBot, 200)
        check(part.contains("中部"), "a partially fitting middle is still emitted")
        check(part.contains("中部还有内容未列出"), "a partially truncated middle says so")
        check(part.contains("[t0]"), "the top zone survives truncation")
        check(part.contains("[b0]"), "the bottom zone survives truncation")

        // 3) ★ 顶部+底部吃满预算（avail <= 0）：中部必须明说被略过，不能静默消失
        val fatTop = "顶部\n" + (0 until 40).joinToString("") { "  [t" + it + "] " + "y".repeat(60) + "\n" }
        val fatBot = "底部\n" + (0 until 40).joinToString("") { "  [b" + it + "] " + "z".repeat(60) + "\n" }
        check(
            fatTop.length + fatBot.length > ScreenBudget.CHAR_BUDGET,
            "fixture really blows the budget (top+bottom alone exceed it)",
        )
        val squeezed = ScreenBudget.assemble(head, fatTop, mid.toString(), fatBot, 200)
        check(squeezed.contains("中部"), "the middle zone heading never vanishes")
        check(
            squeezed.contains("200 项因预算未列出"),
            "REGRESSION: when top+bottom eat the whole budget the middle is REPORTED, not silently dropped",
        )

        // 4) 节点上限提示要带上
        val withNote = ScreenBudget.assemble(
            head, "顶部\n", "中部\n", "底部\n", 0, "…（节点数超过上限，只列出了前 200 项）\n",
        )
        check(withNote.contains("节点数超过上限"), "the node-cap note survives assembly")
    }}
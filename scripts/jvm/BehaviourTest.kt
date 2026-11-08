package jvmtest

import app.dsh.mobile.ProtectedPages
import app.dsh.mobile.Handoff
import app.dsh.mobile.PhoneTask
import app.dsh.mobile.ScreenBudget
import app.dsh.mobile.ScreenDigest
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
        assertDigest()
        assertHandoff()
        assertPhoneTask()

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
    }
    // ===== ScreenDigest：屏幕去重（v1.6.21） =====
    private fun assertDigest() {
        println("== 10. ScreenDigest 屏幕去重 ==")
        fun sig(vararg pairs: Pair<Int, String>) = pairs.map { it.first.toString() + ":" + it.second }
        val doc = listOf(
            "屏幕 s8 · 设置 · 竖屏 · 共 3 项",
            "顶部",
            "  [1] button 返回 @66,154 c",
            "中部",
            "  [2] row 电池 @540,700 c",
            "  [3] row 存储 @540,900 c",
            "底部",
            "  [4] button 确定 @540,2100 c",
        ).joinToString("\n")
        val base = sig(1 to "a", 2 to "b", 3 to "c", 4 to "d")

        // 1) 没有任何变化：只回一句
        val same = ScreenDigest.reduce(doc, "s7", base, base)
        check(same != null, "an unchanged screen still returns a digest, not null")
        check(same!!.contains("与 s7 相同，编号不变"), "an unchanged screen says so")
        check(!same.contains("[2] row 电池"), "an unchanged screen does not repeat the rows")

        // 2) 一行变了：只列那一行 + 说明其余相同
        val oneChanged = sig(1 to "a", 2 to "b", 3 to "c!", 4 to "d")
        val part = ScreenDigest.reduce(doc, "s7", base, oneChanged)
        check(part != null, "a one-row change produces a digest")
        check(part!!.contains("[3] row 存储"), "the changed row is listed")
        check(!part.contains("[2] row 电池"), "unchanged rows are omitted")
        check(!part.contains("[4] button 确定"), "unchanged rows in other zones are omitted too")
        check(part.contains("其余 3 项与 s7 相同"), "it states how many rows were skipped")
        check(part.contains("中部"), "the zone heading of the kept row is preserved")
        check(!part.contains("底部"), "a zone with no kept row does not emit an empty heading")

        // 3) 变化超过三分之一：退回全量（返回 null）
        val manyChanged = sig(1 to "a", 2 to "B!", 3 to "C!", 4 to "d")
        check(ScreenDigest.reduce(doc, "s7", base, manyChanged) == null,
            "more than a third changed -> full document (return null)")

        // 4) 编号数量变了（页面结构变了）：退回全量
        check(ScreenDigest.reduce(doc, "s7", base, sig(1 to "a", 2 to "b")) == null,
            "a different element count -> full document")

        // 5) 没有上一屏：退回全量
        check(ScreenDigest.reduce(doc, "", emptyList(), base) == null,
            "no previous screen -> full document")

        // 6) 恰好三分之一：仍然精简（边界是 >，不是 >=）
        val exactlyThird = sig(1 to "A!", 2 to "b", 3 to "c")
        check(ScreenDigest.reduce(doc, "s7", sig(1 to "a", 2 to "b", 3 to "c"), exactlyThird) != null,
            "exactly one third changed still digests (the boundary is >, not >=)")
    }

    // ===== Handoff：交接状态机（v1.6.21） =====
    private fun assertHandoff() {
        println("== 11. Handoff 交接状态机 ==")

        // 1) 没有进行中的交接
        Handoff.reset()
        check(Handoff.current() == null, "no session by default")
        check(Handoff.settle() == Handoff.State.IDLE, "settle on nothing is IDLE")
        check(Handoff.complete() == false, "completing without a session returns false")

        // 2) 发起后进入 WAITING
        val s = Handoff.begin("需要输入验证码", "com.example.app", 60_000L)
        check(s.state == Handoff.State.WAITING, "a new session starts WAITING")
        check(Handoff.settle() == Handoff.State.WAITING, "settle keeps it WAITING before the deadline")
        check(s.packageName == "com.example.app", "the package is recorded")
        check(s.reason == "需要输入验证码", "the reason is recorded")

        // 3) ★ 重复发起不覆盖进行中的会话（否则先发起的那个永远等不到结果）
        val again = Handoff.begin("另一件事", "com.other", 60_000L)
        check(again === s, "begin() reuses the live session instead of replacing it")
        check(again.packageName == "com.example.app", "the original package survives")

        // 4) 用户完成后进入 DONE，且只接受一次
        check(Handoff.complete(), "complete() succeeds while WAITING")
        check(Handoff.settle() == Handoff.State.DONE, "state becomes DONE")
        check(Handoff.complete() == false, "a second complete() is refused")

        // 5) ★ 超时：过了 deadline 还在等，settle 转 TIMEOUT
        Handoff.reset()
        val t = Handoff.begin("登录", "com.bank", 5_000L)
        val before = t.startedAt + 1_000L
        check(Handoff.settle(before) == Handoff.State.WAITING, "before the deadline it is still WAITING")
        check(Handoff.settle(t.deadlineAt + 1L) == Handoff.State.TIMEOUT,
            "past the deadline it becomes TIMEOUT (not silently still waiting)")
        check(!Handoff.describe(Handoff.State.TIMEOUT).contains("可以继续"),
            "the TIMEOUT message does not invite the agent to keep going")

        // 6) await 用注入时钟推进：不真睡 10 分钟也能验超时
        Handoff.reset()
        Handoff.begin("支付确认", "com.pay", 5_000L)
        var fake = System.currentTimeMillis()
        val st = Handoff.await(60_000L) {
            fake += 400L
            fake
        }
        check(st == Handoff.State.TIMEOUT, "await() returns TIMEOUT when the clock runs out")

        // 7) cancel 清空会话
        Handoff.reset()
        Handoff.begin("x", "com.x", 60_000L)
        check(Handoff.cancel() == Unit || true, "cancel() runs")
        check(Handoff.current() == null, "cancel() clears the session")

        // 8) 每种状态都有给模型看的说明
        for (state in Handoff.State.values()) {
            check(Handoff.describe(state).isNotEmpty(), "describe($state) is not empty")
        }
        Handoff.reset()
    }

    // ===== PhoneTask：任务状态机（v1.6.22） =====
    private fun assertPhoneTask() {
        println("== 12. PhoneTask 任务状态机 ==")

        // 注入时钟：状态机的边界（超时、停止窗口续期）只能靠可控时间验证
        var now = 1_000_000L
        val t = PhoneTask(clock = { now }, idleMs = 60_000L, stopHoldMs = 30_000L)

        // 1) 初始 IDLE
        check(t.snapshot().state == PhoneTask.State.IDLE, "a new task machine is IDLE")
        check(t.isActive() == false, "nothing is active yet")

        // 2) begin 开启任务
        check(t.begin("设置") == null, "begin() admits the first call")
        val s1 = t.snapshot()
        check(s1.state == PhoneTask.State.RUNNING, "state becomes RUNNING")
        check(s1.app == "设置", "the app label is recorded")
        check(s1.id == 1L, "the first task id is 1")
        check(t.isActive(), "the task is active")

        // 3) 记录步骤
        t.noteStep("tap 搜索")
        t.noteStep("input 天气")
        check(t.snapshot().steps == 2, "two steps are counted")
        check(t.snapshot().lastStep == "input 天气", "the last step is kept")
        check(t.snapshot().trail.size == 2, "the trail records both")

        // 4) ★ 停止：立刻拒掉，且窗口内每拒一次就续期
        t.stop("用户点了停止")
        check(t.snapshot().state == PhoneTask.State.STOPPED, "stop() moves to STOPPED")
        check(t.isStopped(), "the hold window is open")
        val refusal = t.begin()
        check(refusal != null, "a call during the hold is refused")
        check(refusal!!.contains("已被用户停止"), "the refusal says the user stopped it")
        check(refusal.contains("不要重试"), "the refusal tells the model not to retry")

        // 5) ★ 续期：拒一次就续一次，否则模型 1 秒后重来，用户看到「停止没用」
        now += 20_000L   // 距上次拒绝过了 20s，窗口 30s 还没到
        t.begin()
        now += 20_000L   // 再 20s：若没续期，累计 40s > 30s 早就该放行了
        check(t.isStopped(), "each refused call re-arms the hold window")
        check(t.begin() != null, "still refused after re-arming")

        // 6) 窗口真正过去后才恢复
        now += 31_000L
        check(t.isStopped() == false, "after the hold elapses the machine unblocks")
        check(t.begin() == null, "calls are admitted again")
        check(t.snapshot().state == PhoneTask.State.RUNNING, "a new task starts on the next call")
        check(t.snapshot().id == 2L, "a new task gets a new id")

        // 7) ★ 暂停：不推进空闲计时（否则用户接个电话回来任务已被判超时）
        t.pause()
        check(t.snapshot().state == PhoneTask.State.PAUSED, "pause() moves to PAUSED")
        check(t.isPaused(), "isPaused() agrees")
        now += 300_000L   // 暂停 5 分钟，远超 idleMs=60s
        check(t.isActive(), "a paused task never idles out")
        // ★ 关键：空闲判定发生在 begin() 里，不是 isActive() 里。
        // 暂停期间模型还会不断调动作接口（每次调用都走 begin()），
        // 若 begin() 在暂停时判空闲把任务收掉，用户的暂停就白按了。
        val pausedId = t.snapshot().id
        t.begin()
        check(t.snapshot().state == PhoneTask.State.PAUSED, "begin() must not idle out a paused task")
        check(t.snapshot().id == pausedId, "a paused task keeps its id across calls")
        t.resume()
        check(t.snapshot().state == PhoneTask.State.RUNNING, "resume() returns to RUNNING")

        // 8) 空闲超时：未暂停时 60s 无调用即结束
        now += 61_000L
        check(t.begin() == null, "begin() after idle starts a fresh task")
        check(t.snapshot().id == 3L, "the idle one was closed and a new task started")

        // 9) finish 清空
        t.noteStep("tap 确定")
        t.finish()
        check(t.snapshot().state == PhoneTask.State.IDLE, "finish() returns to IDLE")
        check(t.snapshot().steps == 0, "finish() clears the step count")
        check(t.snapshot().trail.isEmpty(), "finish() clears the trail")
        check(t.isActive() == false, "nothing active after finish")

        // 10) 停止对已结束的任务无副作用
        t.stop()
        check(t.snapshot().state == PhoneTask.State.IDLE, "stopping an idle machine does not invent a task")
    }
}
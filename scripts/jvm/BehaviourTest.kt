package jvmtest

import app.dsh.mobile.ProtectedPages
import app.dsh.mobile.Handoff
import app.dsh.mobile.HumanPace
import app.dsh.mobile.MarkGeometry
import app.dsh.mobile.SwipePath
import kotlin.random.Random
import app.dsh.mobile.ActionLog
import app.dsh.mobile.OverlayState
import app.dsh.mobile.PhoneAsk
import app.dsh.mobile.PhoneTask
import app.dsh.mobile.TaskReport
import app.dsh.mobile.ScreenBudget
import app.dsh.mobile.ScreenDigest
import app.dsh.mobile.ScreenMask
import app.dsh.mobile.ScrollGeometry
import app.dsh.mobile.FastMode
import app.dsh.mobile.A11yEntry
import java.io.File

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
        assertOverlayState()
        assertMarkGeometry()
        assertSwipePath()
        assertHumanPace()
        assertTaskReport()
        assertPhoneAsk()
        assertPhoneTask()
        assertFastMode()
        assertA11yEntry()
        assertAfterActionSuccess()
        assertScrollGeometry()

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
    // ===== PhoneTask：任务状态机（v1.6.23） =====
    private fun assertPhoneTask() {
        println("== 12. PhoneTask 任务状态机 ==")

        // 注入时钟：空闲超时、停止续期这些边界只能靠可控时间验证。
        var now = 1_000_000L
        val t = PhoneTask(clock = { now }, idleMs = 60_000L, stopHoldMs = 30_000L)

        // 1) 初始 IDLE
        check(t.snapshot().state == PhoneTask.State.IDLE, "a new task machine is IDLE")
        check(!t.isActive(), "nothing is active yet")

        // 2) begin 开启任务
        check(t.begin("com.android.settings") == null, "begin() admits the first call")
        val s1 = t.snapshot()
        check(s1.state == PhoneTask.State.RUNNING, "state becomes RUNNING")
        check(s1.app == "com.android.settings", "the app label is recorded")
        check(s1.id == 1L, "the first task id is 1")

        // 3) 动作日志：带 tool/summary/decision，不是一串同名 tap
        t.callStarted()
        t.noteStep("tap", "tap 搜索框", "ok")
        t.callEnded(t.owner())
        t.callStarted()
        t.noteStep("input", "input 天气", "ok")
        t.callEnded(t.owner())
        check(t.snapshot().steps == 2, "two steps are counted")
        check(t.snapshot().lastStep == "input 天气", "the last step is its summary")
        val recent = t.log.recent(10)
        check(recent.size == 2, "the log holds both entries")
        check(recent[0].summary == "input 天气", "the log is newest first")
        check(recent[0].decision == "ok", "each entry carries its decision")
        check(recent[1].tool == "tap", "each entry carries its tool")

        // 4) ★ 停止：立刻拒掉，窗口内每拒一次就续期
        t.stop("用户点了停止")
        check(t.snapshot().state == PhoneTask.State.STOPPED, "stop() moves to STOPPED")
        check(t.isStopped(), "the hold window is open")
        val refusal = t.begin()
        check(refusal != null, "a call during the hold is refused")
        check(refusal!!.contains("已停止"), "the refusal says the user stopped it")
        check(refusal.contains("不要再调用"), "the refusal tells the model to stop calling")

        // 5) ★ 续期：拒一次就续一次，否则模型 1 秒后重来，用户看到「停止没用」
        now += 20_000L
        t.begin()
        now += 20_000L   // 若没续期，累计 40s > 30s 早该放行
        check(t.isStopped(), "each refused call re-arms the hold window")
        check(t.begin() != null, "still refused after re-arming")

        // 6) 窗口真正过去后恢复
        now += 31_000L
        check(!t.isStopped(), "after the hold elapses the machine unblocks")
        check(t.begin() == null, "calls are admitted again")
        check(t.snapshot().state == PhoneTask.State.RUNNING, "a new task starts on the next call")
        check(t.snapshot().id == 2L, "a new task gets a new id")
        check(t.log.recent(10).isEmpty(), "a new task starts with an empty log")
        // 6.5) ★ 用户叫停后，悬浮窗绿灯（setAllowed(true)）必须能立刻解除 ——
        // 这是唯一能解刹车的入口；模型侧的 resume() 不能自己解。
        val t2 = PhoneTask(clock = { now }, idleMs = 60_000L, stopHoldMs = 30_000L)
        t2.begin("com.android.settings")
        t2.stop("用户点了停止")
        check(t2.isStopped(), "stop() opens the hold window")
        check(t2.begin() != null, "calls are refused while stopped")
        t2.setAllowed(true)      // 已经 allowed=true，用户仍点了「继续」
        check(!t2.isStopped(), "setAllowed(true) clears the stop window even when already allowed")
        check(t2.begin() == null, "calls are admitted right after the user pressed continue")

        // 6.6) 但「关掉允许」不能顺手清掉叫停窗口（那是中止，不是解刹车）
        val t3 = PhoneTask(clock = { now }, idleMs = 60_000L, stopHoldMs = 30_000L)
        t3.begin("x")
        t3.stop("用户点了停止")
        t3.setAllowed(false)
        check(t3.isStopped(), "setAllowed(false) does NOT clear the stop window")
        check(t3.begin() != null, "the stop still refuses calls after allow=false")

        // 6.65) ★ 空闲（IDLE）时按停止也必须踩住刹车：任务没在跑不代表下一个动作不该被拒
        val t5 = PhoneTask(clock = { now }, idleMs = 60_000L, stopHoldMs = 30_000L)
        check(t5.snapshot().state == PhoneTask.State.IDLE, "a fresh machine is IDLE")
        t5.stop("用户点了停止")
        check(t5.isStopped(), "stop() arms the brake even from IDLE")
        check(t5.begin() != null, "the next call is refused right after an idle stop")
        check(t5.snapshot().state == PhoneTask.State.STOPPED, "state is STOPPED after an idle stop")

        // 6.66) ★ AI 主动收尾：任务回 IDLE，但**不会**替用户把允许开关打开
        val t7 = PhoneTask(clock = { now }, idleMs = 60_000L, stopHoldMs = 30_000L)
        t7.begin("w")
        t7.setAllowed(false)
        t7.finish()
        check(t7.snapshot().state == PhoneTask.State.IDLE, "finish() returns to IDLE")
        check(!t7.isAllowed(), "finish() must NOT re-enable the user's allow switch")
        check(t7.begin() != null, "actions are still refused after finishing with allow off")

        // 6.68) ★ 长任务步间自查：叫停 / 关掉允许 都要能立刻中断在跑的循环
        val t6 = PhoneTask(clock = { now }, idleMs = 60_000L, stopHoldMs = 30_000L)
        check(t6.interruptReason() == null, "a fresh machine reports no brake")
        t6.begin("z")
        t6.stop("用户点了停止")
        val brake = t6.interruptReason()
        check(brake != null, "interruptReason() reports the stop")
        check(brake!!.contains("已停止"), "the brake message is the stop message")
        t6.setAllowed(true)
        check(t6.interruptReason() == null, "pressing continue releases the brake")
        t6.setAllowed(false)
        val offBrake = t6.interruptReason()
        check(offBrake != null, "allow=false also brakes a running loop")
        check(offBrake!!.contains("允许") || offBrake.contains("AI"), "the brake explains allow is off")

        // 6.7) 暂停后 resume 仍然正常（不能因为加了叫停判断把暂停路径弄坏）
        val t4 = PhoneTask(clock = { now }, idleMs = 60_000L, stopHoldMs = 30_000L)
        t4.begin("y")
        t4.pause()
        check(t4.isPaused(), "paused before resume")
        t4.resume()
        check(t4.snapshot().state == PhoneTask.State.RUNNING, "resume() still works from PAUSED")
        check(!t4.isStopped(), "resume() does not fake a stop")

        // 7) ★ 暂停：不推进空闲计时（否则用户接个电话回来任务已被判超时）
        t.pause()
        check(t.snapshot().state == PhoneTask.State.PAUSED, "pause() moves to PAUSED")
        check(t.isPaused(), "isPaused() agrees")
        now += 300_000L   // 暂停 5 分钟，远超 idleMs=60s
        check(t.isActive(), "a paused task never idles out")
        // 空闲判定在 begin() 里，不是 isActive() 里：暂停期间模型仍会调动作接口。
        val pausedId = t.snapshot().id
        t.begin()
        check(t.snapshot().state == PhoneTask.State.PAUSED, "begin() must not idle out a paused task")
        check(t.snapshot().id == pausedId, "a paused task keeps its id across calls")
        t.resume()
        check(t.snapshot().state == PhoneTask.State.RUNNING, "resume() returns to RUNNING")

        // 8) ★ 在飞的调用不算空闲
        // handoff 可以等 10 分钟；若按 3 分钟空闲判死，用户刚点完成，
        // 任务已经在 7 分钟前被收掉了。
        now += 61_000L
        check(t.expireIdle(), "an idle task expires")
        check(t.snapshot().state == PhoneTask.State.IDLE, "idle expiry returns to IDLE")
        check(t.begin() == null, "begin() after idle starts a fresh task")
        val longTask = t.snapshot().id
        t.callStarted()          // 一个 10 分钟的 handoff 正在跑
        now += 600_000L          // 10 分钟过去
        check(!t.expireIdle(), "a task with a call in flight never idles out")
        check(t.snapshot().state == PhoneTask.State.RUNNING, "it is still running while in flight")
        check(t.snapshot().id == longTask, "it keeps the same task id")
        // begin() 自己也要尊重在飞计数（与 expireIdle 是两条不同路径）。
        // 否则模型在一个长 handoff 进行中发出下一步，就把自己的任务归零了。
        check(t.begin() == null, "begin() during a long call is admitted")
        check(t.snapshot().id == longTask, "an in-flight call keeps the same task across begin()")
        check(t.snapshot().state == PhoneTask.State.RUNNING, "still running, not silently restarted")
        t.callEnded(t.owner())
        now += 61_000L
        check(t.expireIdle(), "once the call lands and the idle window passes, it ends")

        // 9) ★ 快照惰性判空闲：AI 停手后 GET /task 立刻显示已结束，
        //    不依赖定时器 —— 否则悬浮窗的停止按钮会一直亮着。
        t.begin()
        check(t.snapshot().state == PhoneTask.State.RUNNING, "a fresh task is running")
        now += 61_000L   // 不发 expireIdle，直接看快照
        check(t.snapshot().state == PhoneTask.State.IDLE, "snapshot() reports idle without a timer")
        check(!t.snapshot().active, "an idled snapshot is not active")
        check(t.snapshot().id == 0L, "an idled snapshot has no task id")

        // 10) ★ awaitResume：暂停要真的阻塞，不是只改一个标记
        now += 1_000L
        t.begin()
        t.pause()
        // 另一个线程在 resume 前必须被挡住 —— 用超时 0 模拟「等不到」
        check(!t.awaitResume(0L), "awaitResume times out while still paused")
        t.resume()
        check(t.awaitResume(0L), "awaitResume returns true once resumed")

        // 11) 暂停期间被停止：那不是恢复
        t.pause()
        t.stop("中途叫停")
        check(!t.awaitResume(0L), "a stop during pause is not a resume")

        // 12) ★ 插话：用户可以在任务进行中说话
        now += 31_000L
        t.begin()
        t.interject("  太快了  ")
        t.interject("")
        t.interject("先别点提交")
        val said = t.drainInterjections()
        check(said.size == 2, "blank interjections are dropped")
        check(said[0] == "太快了", "interjections are trimmed")
        check(t.drainInterjections().isEmpty(), "draining clears them")

        // 13) ★ Owner：旧任务的晚到结果不能影响新任务
        val oldOwner = t.owner()
        check(t.isCurrent(oldOwner), "the owner is current while its task lives")
        t.finish()
        check(!t.isCurrent(oldOwner), "finishing invalidates the old owner")
        t.begin()
        check(!t.isCurrent(oldOwner), "a new task does not revive the old owner")
        check(t.isCurrent(t.owner()), "the new owner is current")

        // 13b) lifecycle 的真正作用点：clearStop 不改 taskId，只推 lifecycle。
        // 若 isCurrent 只比 taskId，这个旧 Owner 会被当成当前任务。
        val beforeClear = t.owner()
        val idBeforeClear = t.snapshot().id
        check(t.isCurrent(beforeClear), "the owner is current before clearStop")
        t.clearStop()
        check(t.snapshot().id == idBeforeClear, "clearStop keeps the same task id")
        check(!t.isCurrent(beforeClear), "clearStop retires the old owner via lifecycle")

        // 14) finish 清空
        t.callStarted()
        t.noteStep("tap", "tap 确定", "ok")
        t.callEnded(t.owner())
        t.finish()
        check(t.snapshot().state == PhoneTask.State.IDLE, "finish() returns to IDLE")
        check(t.snapshot().steps == 0, "finish() clears the log")

        // 15) 日志容量上限
        val small = ActionLog(capacity = 3)
        repeat(5) { i -> small.add("tap", null, "step " + i, "ok") }
        check(small.size() == 3, "the log is capped at its capacity")
        check(small.last()!!.summary == "step 4", "the newest entry survives")
        check(small.recent(10).size == 3, "recent() never exceeds the cap")
    }
    // ===== PhoneAsk：向用户提问（v1.6.24） =====
    // ===== TaskReport：任务完成报告（v1.6.25） =====
    // ===== OverlayState：五态状态（v1.6.27） =====
    private fun assertOverlayState() {
        println("== 15. OverlayState 五态状态 ==")

        // 1) 优先级：提问 > 错误 > 行动中 > 完成 > 空闲
        check(OverlayState.resolve(asking = true, lastFailed = true, working = true, justFinished = true)
            == OverlayState.Kind.ASK, "a pending question outranks everything")
        check(OverlayState.resolve(asking = false, lastFailed = true, working = true, justFinished = true)
            == OverlayState.Kind.ERROR, "a failure outranks working and done")
        check(OverlayState.resolve(asking = false, lastFailed = false, working = true, justFinished = true)
            == OverlayState.Kind.WORKING, "working outranks done")
        check(OverlayState.resolve(asking = false, lastFailed = false, working = false, justFinished = true)
            == OverlayState.Kind.DONE, "done outranks idle")
        check(OverlayState.resolve(asking = false, lastFailed = false, working = false, justFinished = false)
            == OverlayState.Kind.IDLE, "nothing at all is idle")

        // 2) 颜色是语义
        check(OverlayState.colorOf(OverlayState.Kind.IDLE) == OverlayState.COLOR_IDLE, "idle is the neutral grey")
        check(OverlayState.colorOf(OverlayState.Kind.ERROR) == OverlayState.COLOR_ERROR, "error is the danger red")
        check(OverlayState.colorOf(OverlayState.Kind.DONE) == OverlayState.COLOR_DONE, "done is the success green")
        check(OverlayState.colorOf(OverlayState.Kind.ASK) == OverlayState.COLOR_ASK, "ask is the warning yellow")

        // 3) ★ 只有 WORKING 跟随用户配色
        val custom = 0xFF00FF00.toInt()
        check(OverlayState.colorOf(OverlayState.Kind.WORKING, custom) == custom,
            "working follows the user accent")
        check(OverlayState.colorOf(OverlayState.Kind.ERROR, custom) == OverlayState.COLOR_ERROR,
            "error ignores the accent - changing it would silence the alarm")
        check(OverlayState.colorOf(OverlayState.Kind.DONE, custom) == OverlayState.COLOR_DONE,
            "done ignores the accent")
        check(OverlayState.colorOf(OverlayState.Kind.ASK, custom) == OverlayState.COLOR_ASK,
            "ask ignores the accent")
        check(OverlayState.colorOf(OverlayState.Kind.IDLE, custom) == OverlayState.COLOR_IDLE,
            "idle ignores the accent")

        // 4) 五档颜色互不相同（否则用户扫一眼分不出来）
        val colors = listOf(
            OverlayState.COLOR_IDLE, OverlayState.COLOR_WORKING_FALLBACK,
            OverlayState.COLOR_ERROR, OverlayState.COLOR_DONE, OverlayState.COLOR_ASK,
        )
        check(colors.toSet().size == 5, "all five states are visually distinct")

        // 5) matters()
        check(!OverlayState.matters(OverlayState.Kind.IDLE), "idle does not demand attention")
        check(OverlayState.matters(OverlayState.Kind.WORKING), "working does")
        check(OverlayState.matters(OverlayState.Kind.ERROR), "error does")
        check(OverlayState.matters(OverlayState.Kind.DONE), "done does")
        check(OverlayState.matters(OverlayState.Kind.ASK), "ask does")

        // 6) needsAttention()：只有「要你动手」的两档才允许自动弹面板。
        //    执行中/完成只是告知，弹出来会挡着 AI 正在点的界面。
        check(!OverlayState.needsAttention(OverlayState.Kind.IDLE), "idle never pops the panel")
        check(!OverlayState.needsAttention(OverlayState.Kind.WORKING), "working does not pop the panel")
        check(!OverlayState.needsAttention(OverlayState.Kind.DONE), "done does not pop the panel")
        check(OverlayState.needsAttention(OverlayState.Kind.ERROR), "error pops the panel")
        check(OverlayState.needsAttention(OverlayState.Kind.ASK), "ask pops the panel")
    }
    private fun assertMarkGeometry() {
        println("== 15. MarkGeometry 描点几何 ==")

        // 1) 插值端点与中点：滑动的点必须真的从起点走到终点
        check(MarkGeometry.lerp(100f, 500f, 0f) == 100f, "lerp starts at from")
        check(MarkGeometry.lerp(100f, 500f, 1f) == 500f, "lerp ends at to")
        check(MarkGeometry.lerp(100f, 500f, 0.5f) == 300f, "lerp midpoint")
        check(MarkGeometry.lerp(500f, 100f, 0.25f) == 400f, "lerp works when to < from")

        // 2) t 越界要夹住：手势时长与实际回放对不齐时不能让点飞出屏幕
        check(MarkGeometry.lerp(0f, 10f, -3f) == 0f, "lerp clamps below 0")
        check(MarkGeometry.lerp(0f, 10f, 9f) == 10f, "lerp clamps above 1")

        // 3) 进度：时长非正算走完（防除零），正常按比例
        check(MarkGeometry.progress(0L, 0L) == 1f, "zero duration counts as done")
        check(MarkGeometry.progress(0L, 1000L) == 0f, "no elapsed = 0 progress")
        check(MarkGeometry.progress(500L, 1000L) == 0.5f, "half elapsed = half progress")
        check(MarkGeometry.progress(5000L, 1000L) == 1f, "over elapsed clamps to 1")

        // 4) 长按的环是「向内收」：起点在外圈、终点在内圈，中间在两者之间
        check(MarkGeometry.holdRadius(100f, 30f, 0f) == 100f, "hold starts at outer")
        check(MarkGeometry.holdRadius(100f, 30f, 1f) == 30f, "hold ends at inner")
        val mid = MarkGeometry.holdRadius(100f, 30f, 0.5f)
        check(mid < 100f && mid > 30f, "hold shrinks monotonically in between")

        // 5) 存活时长 = 动作时长 + 收尾停留；超长动作夹到上限
        check(MarkGeometry.lifeMs(0L) == MarkGeometry.TAIL_MS, "tap life is just the tail")
        check(MarkGeometry.lifeMs(800L) == 800L + MarkGeometry.TAIL_MS, "hold life adds the action")
        check(
            MarkGeometry.lifeMs(999_999L) == MarkGeometry.MAX_ACTION_MS + MarkGeometry.TAIL_MS,
            "very long actions are capped",
        )
        check(MarkGeometry.lifeMs(-50L) == MarkGeometry.TAIL_MS, "negative duration cannot shorten life")
    }

    private fun assertSwipePath() {
        println("== 16. SwipePath 拟人滑动轨迹 ==")

        // 1) 端点必须精确：拖滑块/拉进度条全靠这个
        val p = SwipePath.build(100f, 200f, 900f, 1400f, rnd = Random(7))
        check(p.size == SwipePath.SAMPLES + 1, "sample count is SAMPLES+1")
        check(kotlin.math.abs(p.first().x - 100f) < 0.01f && kotlin.math.abs(p.first().y - 200f) < 0.01f,
            "start point is exact")
        check(kotlin.math.abs(p.last().x - 900f) < 0.01f && kotlin.math.abs(p.last().y - 1400f) < 0.01f,
            "end point is exact")

        // 2) 真的不是直线 —— 这是这一版的全部意义
        val dev = SwipePath.maxDeviation(p, 100f, 200f, 900f, 1400f)
        check(dev > 8f, "path deviates from the straight line (was " + dev + ")")

        // 3) 沿轴进度单调：不允许往回走（回退会被识别成手抖/二次触摸）
        var prev = -1f
        for (q in p) {
            val prog = (q.x - 100f) * 800f + (q.y - 200f) * 1200f
            check(prog >= prev - 0.01f, "progress along the axis never goes backwards")
            prev = prog
        }

        // 4) 每次都不一样：固定轨迹本身就是指纹
        val a = SwipePath.build(0f, 0f, 0f, 1000f, rnd = Random(1))
        val b = SwipePath.build(0f, 0f, 0f, 1000f, rnd = Random(2))
        val differ = a.indices.any { kotlin.math.abs(a[it].x - b[it].x) > 0.5f }
        check(differ, "two runs with different seeds produce different paths")

        // 5) 曲率为 0 时退化成正直线（保留精确模式）
        val straight = SwipePath.build(0f, 0f, 0f, 1000f, curvature = 0f, sCurve = 0f,
            wobblePx = 0f, rnd = Random(3))
        val dev0 = SwipePath.maxDeviation(straight, 0f, 0f, 0f, 1000f)
        check(dev0 < 1.5f, "curvature=0 keeps the path essentially straight (was " + dev0 + ")")

        // 6) 起止同点不能炸（长按走的就是这条）
        val same = SwipePath.build(500f, 500f, 500f, 500f, rnd = Random(4))
        check(same.size == SwipePath.SAMPLES + 1, "degenerate path still returns all samples")
        check(same.all { kotlin.math.abs(it.x - 500f) < 0.01f }, "degenerate path stays at the point")

        // 7) 摊平后长度对得上
        val flat = SwipePath.flatten(p)
        check(flat.size == p.size * 2, "flatten doubles the length")
        check(kotlin.math.abs(flat[0] - p.first().x) < 0.01f, "flatten keeps order")

        // 8) 时长按距离推，不是常数：固定 300ms 本身就是指纹
        val short = (1..40).map { SwipePath.durationFor(200f, Random(it)) }.average()
        val long = (1..40).map { SwipePath.durationFor(1800f, Random(it)) }.average()
        check(long > short * 1.6, "a 1800px swipe takes clearly longer than a 200px one (" + short + " vs " + long + ")")
        check(SwipePath.durationFor(0f, Random(1)) >= 120L, "duration never drops below the 120ms floor")
        check(SwipePath.durationFor(50_000f, Random(1)) <= 900L, "duration is capped at 900ms")
        val d1 = SwipePath.durationFor(1000f, Random(1))
        val d2 = SwipePath.durationFor(1000f, Random(2))
        check(d1 != d2, "same distance still varies between runs (" + d1 + " vs " + d2 + ")")

        // 9) 过冲：冲过终点再回到精确落点
        val ov = SwipePath.build(0f, 0f, 0f, 1000f, overshootPx = 40f, rnd = Random(5))
        val reach = SwipePath.maxReach(ov, 0f, 0f, 0f, 1000f)
        check(reach > 1030f, "overshoot really passes the end point (reach " + reach + ")")
        check(kotlin.math.abs(ov.last().y - 1000f) < 0.01f, "after overshooting it still lands exactly on the end")
        val noOv = SwipePath.build(0f, 0f, 0f, 1000f, rnd = Random(5))
        check(SwipePath.maxReach(noOv, 0f, 0f, 0f, 1000f) < 1010f, "overshoot=0 never passes the end")

        // 10) 中段停顿：出现重合点（= 手指停了一下）
        var sawPause = false
        for (seed in 1..30) {
            val q = SwipePath.build(0f, 0f, 0f, 1000f, pauseChance = 1f, rnd = Random(seed))
            if (q.size > SwipePath.SAMPLES + 1) { sawPause = true; break }
        }
        check(sawPause, "pauseChance=1 actually inserts hold points")
        val noPause = SwipePath.build(0f, 0f, 0f, 1000f, pauseChance = 0f, rnd = Random(9))
        check(noPause.size == SwipePath.SAMPLES + 1, "pauseChance=0 keeps the plain sample count")

        // 11) 速度抖动不能把轨迹拉回去：任何一步的沿轴进度都必须非负。
        //     第二组用夸张的 40px 抖动，专门守「夹取」这道闸 —— 实际用的 3px
        //     在端点附近本来就很小（乘了 w），夹不夹都看不出来。
        for (jitter in floatArrayOf(3f, 300f)) {
            for (seed in 1..10) {
                val q = SwipePath.build(100f, 100f, 900f, 1500f, speedJitterPx = jitter, rnd = Random(seed))
                var prevProg = -1f
                for (k in q.indices) {
                    val prog = (q[k].x - 100f) * 800f + (q[k].y - 100f) * 1400f
                    check(prog >= prevProg - 0.01f,
                        "speed jitter " + jitter + "px (seed=" + seed + ") never steps backwards")
                    prevProg = prog
                }
            }
        }
    }

    private fun assertHumanPace() {
        println("== 17. HumanPace 动作间隔节拍 ==")

        // 1) 常规停顿落在区间内，且真的每次都不同
        val rnd = Random(11)
        var minSeen = Long.MAX_VALUE
        var maxSeen = Long.MIN_VALUE
        val samples = ArrayList<Long>()
        repeat(2000) {
            val v = HumanPace.thinkMs(rnd)
            samples.add(v)
            if (v < minSeen) minSeen = v
            if (v > maxSeen) maxSeen = v
        }
        check(minSeen >= HumanPace.MIN_THINK_MS, "thinkMs never goes below the floor (" + minSeen + ")")
        check(maxSeen <= HumanPace.MAX_THINK_MS, "thinkMs never exceeds the ceiling (" + maxSeen + ")")
        check(samples.distinct().size > 50, "thinkMs is not a constant (" + samples.distinct().size + " distinct values)")

        // 2) 分布必须偏短：真人多数动作是快的，慢的是少数。
        //    线性分布的中位数会正好落在区间中点，偏斜分布应明显低于它。
        val mean = samples.average()
        val mid = (HumanPace.MIN_THINK_MS + HumanPace.MAX_THINK_MS) / 2.0
        check(mean < mid * 0.92, "thinkMs is skewed towards the short end (mean " + mean + " vs midpoint " + mid + ")")

        // 3) 偶发犹豫：概率大致对得上，时长落在区间内
        var hits = 0
        var hesMin = Long.MAX_VALUE
        var hesMax = 0L
        repeat(20000) {
            val h = HumanPace.hesitateMs(rnd)
            if (h > 0L) {
                hits++
                if (h < hesMin) hesMin = h
                if (h > hesMax) hesMax = h
            }
        }
        val rate = hits / 20000.0
        check(rate > 0.02 && rate < 0.10, "hesitation fires around 5% of the time (was " + rate + ")")
        check(hesMin >= HumanPace.HESITATE_MIN_MS, "hesitation never shorter than its floor")
        check(hesMax <= HumanPace.HESITATE_MAX_MS, "hesitation never longer than its ceiling")

        // 4) 总停顿 = 思考 + 犹豫，且永不为负
        val seeded = Random(3)
        repeat(500) {
            val total = HumanPace.stepPauseMs(seeded)
            check(total >= HumanPace.MIN_THINK_MS, "a step pause is never shorter than the think floor")
            check(total <= HumanPace.MAX_THINK_MS + HumanPace.HESITATE_MAX_MS, "a step pause is bounded")
        }

        // 5) 两个种子给出不同序列（不是写死的表）
        val ra = Random(1)
        val rb = Random(2)
        val s1 = (1..20).map { HumanPace.thinkMs(ra) }
        val s2 = (1..20).map { HumanPace.thinkMs(rb) }
        check(s1 != s2, "different seeds produce different rhythms")
        check(s1.distinct().size > 10, "a single run's rhythm is not constant (" + s1.distinct().size + ")")

        // 6) pause(0) 不能睡（否则每步白等）—— 用耗时粗测
        val t0 = System.nanoTime()
        repeat(200) { HumanPace.pause(0L) }
        val spentMs = (System.nanoTime() - t0) / 1_000_000
        check(spentMs < 50L, "pause(0) returns immediately (took " + spentMs + "ms)")
    }

    private fun assertTaskReport() {
        println("== 14. TaskReport 任务完成报告 ==")

        var now = 5_000_000L
        val r = TaskReport(clock = { now })
        var visible = false
        var launchedOk = true
        var notified = 0
        var valid = true
        var opened = 0

        // 假时钟 + 假睡眠：睡眠推进时间，让确认窗口能在循环里自然到期
        fun run(stopped: Boolean = false): TaskReport.Outcome {
            visible = false; launchedOk = true; notified = 0; valid = true; opened = 0
            return r.deliver(
                body = r.body("打开了设置", steps = 3, stopped = stopped),
                current = { valid },
                visible = { visible },
                launch = { opened++; launchedOk },
                notify = { notified++; true },
                sleep = { ms -> now += ms },
            )
        }

        // 1) 已经在会话里：不带走、不打扰
        visible = true
        var o = r.deliver(
            body = r.body("x", steps = 1, stopped = false),
            current = { true }, visible = { visible },
            launch = { opened++; true }, notify = { notified++; true },
            sleep = { ms -> now += ms },
        )
        check(o.shown == TaskReport.Shown.VISIBLE, "already visible => VISIBLE")
        check(opened == 0, "already visible => no launch is attempted")
        check(notified == 0, "already visible => no notification")

        // 2) 不在会话里，启动成功且真的回来了 => VISIBLE，不发通知
        now = 5_000_000L
        visible = false; launchedOk = true; notified = 0; valid = true; opened = 0
        o = r.deliver(
            body = r.body("y", steps = 2, stopped = false),
            current = { true },
            visible = { now >= 5_000_200L },   // 第三次轮询起才算看见
            launch = { opened++; true },
            notify = { notified++; true },
            sleep = { ms -> now += ms },
        )
        check(o.shown == TaskReport.Shown.VISIBLE, "it came back => VISIBLE")
        check(o.opened, "outcome records that it opened")
        check(o.launched, "outcome records that launch was accepted")
        check(notified == 0, "came back in time => no notification")

        // 3) ★ 启动被接受但用户始终没回来 => 发通知（启动成功 ≠ 用户看见）
        now = 6_000_000L
        visible = false; launchedOk = true; notified = 0; valid = true; opened = 0
        val before = now
        o = r.deliver(
            body = r.body("z", steps = 1, stopped = false),
            current = { true }, visible = { false },
            launch = { opened++; true }, notify = { notified++; true },
            sleep = { ms -> now += ms },
        )
        check(o.launched, "launch was accepted")
        check(o.shown == TaskReport.Shown.NOTIFIED, "accepted but never visible => NOTIFIED")
        check(notified == 1, "exactly one notification")
        check(now - before >= r.confirmMs, "it waited the full confirm window before giving up")

        // 4) 启动被拒 => 不等，立刻通知
        now = 7_000_000L
        visible = false; launchedOk = false; notified = 0; valid = true; opened = 0
        val before2 = now
        o = r.deliver(
            body = r.body("w", steps = 1, stopped = false),
            current = { true }, visible = { false },
            launch = { opened++; false }, notify = { notified++; true },
            sleep = { ms -> now += ms },
        )
        check(!o.launched, "launch was refused")
        check(o.shown == TaskReport.Shown.NOTIFIED, "refused launch => NOTIFIED")
        check(now == before2, "a refused launch does not wait")

        // 5) 等待期间任务被取代 => 不再打扰用户
        now = 8_000_000L
        visible = false; launchedOk = true; notified = 0; valid = true; opened = 0
        var ticks = 0
        o = r.deliver(
            body = r.body("v", steps = 1, stopped = false),
            current = { ticks < 2 }, visible = { false },
            launch = { opened++; true }, notify = { notified++; true },
            sleep = { ms -> now += ms; ticks++ },
        )
        check(o.shown == TaskReport.Shown.LOST, "a superseded task reports LOST")
        check(notified == 0, "a superseded task does not notify")

        // 6) 通知也失败 => LOST（诚实：用户确实没收到）
        now = 9_000_000L
        visible = false; launchedOk = false; valid = true; opened = 0
        o = r.deliver(
            body = r.body("u", steps = 1, stopped = false),
            current = { true }, visible = { false },
            launch = { opened++; false }, notify = { false },
            sleep = { ms -> now += ms },
        )
        check(o.shown == TaskReport.Shown.LOST, "no notification => LOST, not NOTIFIED")

        // 7) 报告正文
        val b1 = r.body("打开了设置", steps = 3, stopped = false, now = 123L)
        check(b1.summary == "打开了设置", "the summary is kept")
        check(b1.steps == 3, "the step count is kept")
        check(b1.at == 123L, "the timestamp is kept")
        check(!b1.stopped, "not stopped")
        val b2 = r.body("   ", steps = 0, stopped = true)
        check(b2.summary == TaskReport.STOPPED_SUMMARY, "a blank summary falls back to the stopped wording")
        check(b2.stopped, "stopped is kept")
        val b3 = r.body("", steps = -5, stopped = false)
        check(b3.summary == TaskReport.DONE_SUMMARY, "a blank summary falls back to the done wording")
        check(b3.steps == 0, "a negative step count is clamped to zero")
    }
    private fun assertPhoneAsk() {
        println("== 13. PhoneAsk 向用户提问 ==")

        var now = 1_000_000L
        val a = PhoneAsk(clock = { now }, defaultTimeoutMs = 60_000L)

        // 1) 初始无待答问题
        check(a.pending() == null, "nothing is pending at first")
        check(a.take().state == PhoneAsk.State.IDLE, "take() on an idle machine reports IDLE")

        // 2) 提问
        val q = a.ask("选哪个？", options = listOf("瑞幸", "星巴克"))
        check(q != null, "ask() creates a question")
        check(q!!.id == 1L, "the first question id is 1")
        check(q.options.size == 2, "the options are kept")
        check(a.pending() != null, "the question is pending")

        // 3) ★ 已有待答问题时不覆盖：覆盖会让先问的那个永远等不到答案
        check(a.ask("另一个问题？") == null, "ask() refuses to overwrite a pending question")
        check(a.pending()!!.text == "选哪个？", "the original question survives")

        // 4) 回答
        check(a.answer("瑞幸", fromOption = true), "answer() accepts")
        check(a.pending() == null, "nothing is pending after answering")

        // 5) ★ 答案只能取一次
        val first = a.take()
        check(first.state == PhoneAsk.State.ANSWERED, "take() reports ANSWERED")
        check(first.answer == "瑞幸", "take() returns the answer")
        check(first.fromOption, "take() says it came from an option")
        val second = a.take()
        check(second.state == PhoneAsk.State.IDLE, "a second take() finds nothing")
        check(second.answer == null, "the answer cannot be taken twice")

        // 6) 重复回答被拒
        check(!a.answer("再来一次"), "answering with no pending question is refused")

        // 7) ★ 自由输入：选项是建议不是限制
        a.ask("要提交吗？", options = listOf("提交", "取消"))
        check(a.answer("先改一下收货地址"), "free text outside the options is accepted")
        val free = a.take()
        check(free.answer == "先改一下收货地址", "the free text comes back verbatim")
        check(!free.fromOption, "it is not marked as an option pick")

        // 8) ★ 超时：用户没答和用户拒绝是两件事
        a.ask("还在吗？", timeoutMs = 10_000L)
        now += 11_000L
        check(a.pending() == null, "a timed-out question is no longer pending")
        val timedOut = a.take()
        check(timedOut.state == PhoneAsk.State.TIMEOUT, "take() reports TIMEOUT, not a refusal")
        check(timedOut.answer == null, "a timeout carries no answer")
        check(a.take().state == PhoneAsk.State.IDLE, "the timeout is consumed too")

        // 9) 超时后回答不被接受
        a.ask("最后一个问题", timeoutMs = 5_000L)
        now += 6_000L
        check(!a.answer("迟到"), "answering after the deadline is refused")
        a.take()   // 消费掉 TIMEOUT

        // 10) 取消
        a.ask("这个不问了")
        a.cancel()
        check(a.pending() == null, "cancel() clears the question")
        check(a.take().state == PhoneAsk.State.IDLE, "a cancelled question yields IDLE")

        // 11) 选项数量上限（悬浮窗放不下太多）
        val many = a.ask("选一个", options = listOf("1", "2", "3", "4", "5", "6"))
        check(many!!.options.size == 4, "at most four options are kept")
        a.cancel()

        // 12) 超时会被夹在合理区间
        val clamped = a.ask("边界", timeoutMs = 1L)
        check(clamped!!.deadlineAt - clamped.askedAt >= 5_000L, "a tiny timeout is clamped up to 5s")
        a.cancel()
    }
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

    // ===== FastMode：行动边界驱动的思考开关（v1.7.0） =====
    private fun assertFastMode() {
        println("== FastMode 标记读写 ==")
        val home = File(System.getProperty("java.io.tmpdir"), "dsh-fastmode-test-" + System.nanoTime())
        home.mkdirs()

        // 1) 没有家目录就没有标记 —— 调用方据此判断「无处可写」
        check(FastMode.marker(null) == null, "marker(null) is null")
        check(FastMode.marker(File("")) == null, "marker(empty path) is null")
        check(FastMode.marker(home)?.name == "fast-mode", "marker name matches the plugin")

        // 2) 初始是关的
        check(!FastMode.isOn(home), "isOn() is false before any write")
        check(!FastMode.off(home) || true, "off() on a missing marker does not throw")

        // 3) 打开 → 文件真的落在 home 下
        check(FastMode.on(home), "on() writes the marker")
        check(File(home, "fast-mode").isFile, "the marker file exists on disk")
        check(FastMode.isOn(home), "isOn() is true right after on()")

        // 4) 幂等：已经开着不重写（每步都碰磁盘没意义，且重写会刷新 TTL）
        val before = File(home, "fast-mode").lastModified()
        Thread.sleep(20)
        check(FastMode.on(home), "on() is idempotent")
        check(File(home, "fast-mode").lastModified() == before, "a second on() does NOT rewrite the marker")

        // 5) TTL 与插件一致：超过 30 分钟视为关闭（时钟注入，不真等）
        // 基准取文件 mtime，不取「现在」：中间隔了一次 sleep(20)，用 now 会差 20ms，
        // 边界断言就不是边界了（第一次写这条断言时正是这么假红的）。
        val stamp = File(home, "fast-mode").lastModified()
        check(FastMode.isOn(home, stamp + FastMode.TTL_MS), "still on at exactly the TTL")
        check(!FastMode.isOn(home, stamp + FastMode.TTL_MS + 1), "off one millisecond past the TTL")
        check(FastMode.TTL_MS == 30L * 60L * 1000L, "TTL matches fast-mode.mjs (30 min)")

        // 6) 过期后 on() 会重新写（否则「过期了但文件还在」会让它永远打不开）
        check(FastMode.on(home, stamp + FastMode.TTL_MS + 5_000L), "on() rewrites an expired marker")
        check(FastMode.isOn(home), "and it is on again by the real clock")

        // 7) 关闭
        check(FastMode.off(home), "off() deletes the marker")
        check(!File(home, "fast-mode").exists(), "the marker file is gone")
        check(!FastMode.isOn(home), "isOn() is false after off()")
        check(FastMode.off(home), "off() is idempotent")

        home.deleteRecursively()
    }

    // ===== A11yEntry：只改自己那一条（v1.7.0） =====
    private fun assertA11yEntry() {
        println("== A11yEntry 列表增删 ==")
        val pkg = "app.dsh.mobile"
        val cls = "app.dsh.mobile.DshAccessibilityService"
        val me = "app.dsh.mobile/app.dsh.mobile.DshAccessibilityService"
        val talkback = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

        // 1) 两种合法写法都认（系统可能写成 .Short）
        check(A11yEntry.isOurs(me, pkg, cls), "full class name is ours")
        check(A11yEntry.isOurs("app.dsh.mobile/.DshAccessibilityService", pkg, cls), "dot-short form is ours")
        check(!A11yEntry.isOurs(talkback, pkg, cls), "TalkBack is NOT ours")
        check(!A11yEntry.isOurs("app.dsh.mobile", pkg, cls), "a bare package (no class) is not ours")
        check(!A11yEntry.isOurs("app.dsh.mobileother/.DshAccessibilityService", pkg, cls),
            "a package that merely starts with ours is NOT ours")

        // 2) listed
        check(!A11yEntry.listed(null, pkg, cls), "null list has nothing")
        check(!A11yEntry.listed("", pkg, cls), "empty list has nothing")
        check(A11yEntry.listed("$talkback:$me", pkg, cls), "listed when present among others")

        // 3) 加：不重复、不丢别人的
        val added = A11yEntry.withMe(talkback, pkg, cls)
        check(added == "$talkback:$me", "added at the end, others untouched: $added")
        check(A11yEntry.withMe(added, pkg, cls) == added, "adding twice does not duplicate")
        check(A11yEntry.withMe(null, pkg, cls) == me, "adding to an empty list yields just us")

        // 4) 删：只删我们，别人的顺序不变
        val three = "$talkback:$me:com.example.other/.Svc"
        val removed = A11yEntry.withoutMe(three, pkg, cls)
        check(removed == "$talkback:com.example.other/.Svc", "only ours is removed: $removed")
        check(A11yEntry.withoutMe(me, pkg, cls) == "", "removing the only entry leaves an empty list")
        check(A11yEntry.withoutMe(talkback, pkg, cls) == talkback, "removing when absent changes nothing")
        // 缩略写法也要删得掉 —— 系统写回去的就是这种
        check(A11yEntry.withoutMe("app.dsh.mobile/.DshAccessibilityService", pkg, cls) == "",
            "the dot-short form is removed too")
    }

    // ===== 动作成功后的状态（v1.7.0） =====
    private fun assertAfterActionSuccess() {
        println("== 动作成功后该显示哪一档 ==")
        // 任务还活着：保持执行中。这条就是用户报的「AI 还在动，上面一直绿着说完成」。
        check(OverlayState.afterActionSuccess(true) == OverlayState.Kind.WORKING,
            "a success inside a live task stays WORKING, not DONE")
        check(OverlayState.colorOf(OverlayState.afterActionSuccess(true)) == OverlayState.COLOR_WORKING_FALLBACK,
            "and its colour is the working accent, not the done green")
        // 任务结束了：才给完成绿。
        check(OverlayState.afterActionSuccess(false) == OverlayState.Kind.DONE,
            "a success after the task ended shows DONE")
        check(OverlayState.colorOf(OverlayState.afterActionSuccess(false)) == OverlayState.COLOR_DONE,
            "and its colour is the done green")
        check(OverlayState.afterActionSuccess(false) != OverlayState.afterActionSuccess(true),
            "the two branches really differ (a constant would fail here)")
    }

    // ===== ScrollGeometry：一次滚动手势的几何（v1.7.2） =====
    private fun assertScrollGeometry() {
        println("== 滚动手势几何 ==")
        val w = 1080
        val h = 2280

        // 1) 默认行程 = 0.72h，比原来写死的 0.6h 更长（用户要求「再多一点」）
        val down = ScrollGeometry.step(w, h, "down")
        val travel = down.y1 - down.y2
        // 实现里先 toInt() 再算，所以是下取整而不是四舍五入。
        check(travel == kotlin.math.floor(h * ScrollGeometry.DEFAULT_FRACTION),
            "default travel is 0.72h, got " + travel)
        check(travel > h * 0.6f, "default travel is longer than the old 0.6h")

        // 2) down = 看下面的内容 = 手指从下往上划
        check(down.y1 > down.y2, "down scrolls with the finger moving up")
        check(down.x == w / 2f, "the gesture runs down the middle of the screen")

        // 3) up 是镜像：手指从上往下
        val up = ScrollGeometry.step(w, h, "up")
        check(up.y1 < up.y2, "up scrolls with the finger moving down")
        check(up.y1 - down.y2 < 1f, "up starts where down ends (same travel)")

        // 4) 两端都在屏幕内，且留出边距（不许落到状态栏/手势导航条上）
        // 边距是像素整数（实现里 toInt()），所以留 1px 容差 —— 断言要盯的是
        // 「别落进状态栏/手势条」，不是「精确到亚像素」。
        val lo = h * ScrollGeometry.EDGE_FRACTION - 1f
        val hi = h * (1f - ScrollGeometry.EDGE_FRACTION) + 1f
        for (s in listOf(down, up)) {
            check(s.y1 in lo..hi, "start point stays inside the safe area")
            check(s.y2 in lo..hi, "end point stays inside the safe area")
        }

        // 5) 关键回归：把 distance 调大不再算出屏幕外的终点。
        //    旧写法是 0.65h - dist，dist=0.8h 时终点 = -0.15h（负坐标）。
        val huge = ScrollGeometry.step(w, h, "down", (h * 0.95f).toInt())
        check(huge.y2 >= 0f, "an oversized distance never produces a negative y")
        check(huge.y2 >= h * ScrollGeometry.EDGE_FRACTION, "it is clamped to the safe area instead")
        check(huge.y1 - huge.y2 == ScrollGeometry.maxTravel(h).toFloat(),
            "the clamped travel equals maxTravel()")

        // 6) 显式 distance 被尊重（在合法范围内原样使用）
        val exact = ScrollGeometry.step(w, h, "down", 500)
        check(exact.y1 - exact.y2 == 500f, "an explicit distance is used as given")

        // 7) 太小的距离被抬到下限（手势抖动会吃掉几 px 的行程）
        val tiny = ScrollGeometry.step(w, h, "down", 10)
        check(tiny.y1 - tiny.y2 == ScrollGeometry.MIN_TRAVEL_PX.toFloat(),
            "a tiny distance is raised to MIN_TRAVEL_PX")

        // 8) 方向大小写与未知值都按 down 处理（调用方传进来的是任意字符串）
        val upper = ScrollGeometry.step(w, h, "UP")
        check(upper.y1 < upper.y2, "direction is case-insensitive")
        val junk = ScrollGeometry.step(w, h, "sideways")
        check(junk.y1 == down.y1 && junk.y2 == down.y2, "an unknown direction behaves as down")

        // 9) 极矮的屏幕也不返回零行程（min 夹在 MIN_TRAVEL_PX 上）
        val short = ScrollGeometry.step(320, 400, "down")
        check(short.y1 - short.y2 >= 1f, "a short screen still produces a real gesture")
    }

    // ===== PhoneTask：任务状态机（v1.6.22） =====
}
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
import app.dsh.mobile.NumFmt
import app.dsh.mobile.PhoneTask
import app.dsh.mobile.TaskReport
import app.dsh.mobile.ScreenBudget
import app.dsh.mobile.ScreenDigest
import app.dsh.mobile.ScreenMask
import app.dsh.mobile.ScrollGeometry
import app.dsh.mobile.FastMode
import app.dsh.mobile.A11yEntry
import java.io.File
import app.dsh.mobile.engine.TraceDoc
import app.dsh.mobile.engine.TraceStep
import app.dsh.mobile.engine.TraceAnchor
import app.dsh.mobile.engine.TraceVerify
import app.dsh.mobile.engine.TraceStatus
import app.dsh.mobile.engine.TraceVia
import app.dsh.mobile.engine.TracePruner
import app.dsh.mobile.engine.TraceArgs
import app.dsh.mobile.engine.TraceExpect
import app.dsh.mobile.engine.TraceEndpoint
import app.dsh.mobile.engine.PruneResult
import app.dsh.mobile.engine.PrunedStep
import app.dsh.mobile.engine.TraceContract
import app.dsh.mobile.engine.TraceRetention
import app.dsh.mobile.engine.MemoryContract
import app.dsh.mobile.engine.MemoryEntry
import app.dsh.mobile.engine.MemoryRetention
import app.dsh.mobile.engine.MemorySummary
import app.dsh.mobile.engine.MemoryStep
import app.dsh.mobile.engine.MemoryEdits
import app.dsh.mobile.engine.MemoryRevision
import app.dsh.mobile.engine.MemoryIndex
import app.dsh.mobile.engine.MemoryReplay

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
        assertNumFmt()
        assertTracePruner()
        assertTraceRetention()
        assertMemoryStore()
        assertMemoryEdits()
        assertMemoryIndex()
        assertMemoryReplay()
        assertTraceArgs()
        assertUnreplayable()
        assertStubMatchesService()

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

    // ===== TraceRetention：轨迹保留策略（M1 / F1.3） =====
    //
    // 为什么单独钉这一层：删轨迹是**不可逆**的。差一天就删掉用户还在排查的那条轨迹、
    // 差一个文件就淘汰掉最新的一条，事后没有任何办法恢复 —— 原始轨迹是剪枝的唯一证据
    // （设计文档 §10：剪枝剪错了靠它 7 天内重剪）。所以「该删哪些」被抽成纯函数
    // TraceRetention.toDelete，在 JVM 上逐条钉边界，而不是只靠真机上「看起来没删」。
    private fun assertTraceRetention() {
        println("== 轨迹保留：7 天 TTL + 最多 200 个 ==")
        val now = 1_800_000_000_000L
        val ttl = TraceContract.TTL_MS
        val max = TraceContract.MAX_FILES

        // 1) 常量本身是契约 §1 的一部分：有人改常量等于悄悄改契约，门禁要拦住
        check(ttl == 7L * 24 * 60 * 60 * 1000, "TTL 常量是 7 天整（改它就是改契约）")
        check(max == 200, "容量上限常量是 200 个文件")

        // 2) TTL 边界：恰好活满 7 天不删，多 1 毫秒才删。
        //    边界必须站在「不误删」那侧 —— 多留一天没代价，误删一次就少一条原始证据。
        check(!TraceRetention.isExpired(now - ttl, now), "恰好 7 天整不算过期")
        check(TraceRetention.isExpired(now - ttl - 1, now), "7 天零 1 毫秒算过期")
        check(TraceRetention.toDelete(listOf("t_edge.jsonl" to now - ttl), now).isEmpty(),
            "边界文件（刚好 7 天）不被删")

        // 3) 没过期也没超量：一个都不删
        val fresh = listOf("t_a.jsonl" to now - 1_000L, "t_b.jsonl" to now - ttl + 1)
        check(TraceRetention.toDelete(fresh, now).isEmpty(), "未过期且不超量时一个都不删")

        // 4) 该删的删对名字，不该删的一个不动
        val mixed = listOf("t_old.jsonl" to now - ttl - 60_000L, "t_new.jsonl" to now - 1_000L)
        val doomed = TraceRetention.toDelete(mixed, now)
        check(doomed == listOf("t_old.jsonl"), "只删过期那条，活的完全不动：" + doomed)

        // 5) 容量边界：刚好 200 个不删（差一个都不行）
        val exactlyMax = (0 until max).map { "t_" + it + ".jsonl" to now - it * 1000L }
        check(TraceRetention.toDelete(exactlyMax, now).isEmpty(),
            "刚好 " + max + " 个文件时一个都不删")

        // 6) 201 个：只淘汰最旧的那一个，且必须是最旧的
        val overByOne = (0 until max + 1).map { "t_" + it + ".jsonl" to now - it * 1000L }
        val cutOne = TraceRetention.toDelete(overByOne, now)
        check(cutOne == listOf("t_" + max + ".jsonl"), "201 个只淘汰最旧的 1 个，实得 " + cutOne)

        // 7) 250 个：淘汰 50 个，保留的正好是最新的 200 个
        val many = (0 until 250).map { "t_" + it + ".jsonl" to now - it * 1000L }
        val cut50 = TraceRetention.toDelete(many, now)
        val deleted = cut50.toSet()
        check(cut50.size == 50, "250 个淘汰 50 个，实得 " + cut50.size)
        check(cut50.first() == "t_249.jsonl", "最先淘汰的是最旧的那条")
        check(!deleted.contains("t_0.jsonl"), "最新的一条绝不淘汰")
        check(!deleted.contains("t_199.jsonl"), "第 200 新的一条仍在保留线内")
        check(deleted.contains("t_200.jsonl"), "第 201 新的一条被淘汰")

        // 8) TTL 与容量叠加：300 个里有 30 个过期 -> 删 30 + (270-200) = 100 个。
        //    这条钉住「过期文件不吃容量配额」：若先按总量算超量，会多删 30 条活着的轨迹。
        val mix = (0 until 300).map { n ->
            val ts = if (n >= 270) now - ttl - 10_000L else now - n * 1000L
            "t_" + n + ".jsonl" to ts
        }
        val cutMix = TraceRetention.toDelete(mix, now)
        check(cutMix.size == 100, "过期 30 + 超量 70 = 共删 100 个，实得 " + cutMix.size)
        check(cutMix.contains("t_299.jsonl"), "过期的即便 startedAt 更「新」也照样删")
        check(!cutMix.contains("t_199.jsonl"), "没过期、又在最新 200 之内的不动")
        check(cutMix.contains("t_200.jsonl"), "活着的超量部分按最旧淘汰")

        // 9) 同名多条取最保守（最新）的 startedAt：宁可判它活着，也不误删
        val dup = listOf("t_dup.jsonl" to now - ttl - 5_000L, "t_dup.jsonl" to now - 1_000L)
        check(TraceRetention.toDelete(dup, now).isEmpty(), "同名多条按最保守的 startedAt 判活")

        // 10) 退化输入：空列表、以及时钟回拨（startedAt 在未来）
        check(TraceRetention.toDelete(emptyList(), now).isEmpty(), "空输入返回空，不抛异常")
        val future = listOf("t_future.jsonl" to now + 86_400_000L, "t_real.jsonl" to now - 1_000L)
        check(TraceRetention.toDelete(future, now).isEmpty(), "startedAt 在未来（时钟回拨）也不删")

        // 11) 删除名单自身要干净：不重复、且只能是输入里出现过的名字
        val noisy = (0 until 210).map { "t_" + it + ".jsonl" to now - it * 1000L } +
            listOf("t_5.jsonl" to now - 900_000L)
        val cutNoisy = TraceRetention.toDelete(noisy, now)
        check(cutNoisy.size == cutNoisy.toSet().size, "删除名单里没有重复名字")
        check(cutNoisy.all { name -> noisy.any { it.first == name } },
            "删除名单只包含输入里出现过的名字（不会凭空删别的文件）")

        // 12) 确定性：输入顺序变了，删除名单不能变
        //     否则文件系统枚举顺序一换，同一份数据这次删 A 下次删 B，门禁就成了掷骰子。
        check(
            TraceRetention.toDelete(many, now) ==
                TraceRetention.toDelete(many.shuffled(kotlin.random.Random(7)), now),
            "输入顺序变化不改变删除名单（确定性）"
        )
    }

    // ===== NumFmt：小数点的地区差异（v1.7.5） =====
    //
    // 这条测试的由来：`"%.2f".format(sim).toDouble()` 在 zh-CN 上一切正常，
    // 在 pt-BR 上产出 "0,00"，toDouble() 直接抛异常把整个 /loop/run 打死。
    // 断言两件事：① 这个坑真的存在（Locale 一换就变）；② NumFmt 不吃这一套。
    private fun assertNumFmt() {
        println("[NumFmt] 两位小数不受系统语言影响")

        // 1) 正常取两位
        check(NumFmt.round2(0.6666) == 0.67, "round2(0.6666) == 0.67")
        check(NumFmt.round2(0.0) == 0.0, "round2(0.0) == 0.0")
        check(NumFmt.round2(1.0) == 1.0, "round2(1.0) == 1.0")
        // 0.625 在二进制里是精确值（5/8），*100 = 62.5 恰好半个刻度 -> half-up 进位成 0.63。
        // 这条同时钉住「不是 banker's rounding」：kotlin.math.round(62.5) 会给 62。
        check(NumFmt.round2(0.625) == 0.63, "round2(0.625) rounds half up to 0.63, not ties-to-even")

        // 2) 阈值边界不能被舍入带偏：0.6999 -> 0.7，仍是「够像」的那一侧
        check(NumFmt.round2(0.6999) == 0.7, "round2(0.6999) == 0.7")
        check(NumFmt.round2(0.6949) == 0.69, "round2(0.6949) == 0.69")

        // 3) 回归本体：逗号小数点地区的 format 结果确实不能 toDouble
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("pt", "BR"))
            val formatted = "%.2f".format(0.0)
            check(formatted == "0,00", "pt-BR 下 \"%.2f\".format(0.0) 变成 \"0,00\"（坑就在这）")
            check(formatted.toDoubleOrNull() == null, "\"0,00\".toDouble() 会抛异常，所以旧写法必崩")
            // 同一地区下 NumFmt 仍然返回数值
            check(NumFmt.round2(0.6666) == 0.67, "pt-BR 下 round2 不受影响")
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    // ===== TracePruner：轨迹剪枝（M2 / F2.3） =====
    //
    // 为什么坚持「读 fixture 文件」而不是把样本内联进测试：scripts/jvm/fixtures/README.md 说这
    // 三份 jsonl 就是验收基准。内联一份等于真值有了两份，哪天 fixture 改了、测试还在拿旧数据
    // 自说自话，断言就退化成自我安慰。宿主 JVM 上没有 org.json（android.jar 里的是 "Stub!"），
    // 所以下面自带一个最小 JSON 解析器 —— fixture 的字段结构是固定的，手写比引依赖更可控。
    //
    // 覆盖：三份 fixture 的真值表（步数 / 规则 / 顺序 / 折叠目标）+ 两条拒绝分支 + retry 规则。
    // retry 在三份样本里都没触发，用内联最小样本钉住它，否则那条规则等于没测。
    private fun assertTracePruner() {
        println("[TracePruner] 轨迹剪枝六条规则（fixture 真跑）")

        val dir = traceFixtureDir()
        if (!File(dir, "trace_ok.jsonl").isFile) {
            check(false, "找不到 fixture 目录（可用 -DtraceFixtures= 指定）：" + dir.absolutePath)
            return
        }

        // ---------- 样本 1：干净轨迹（只有 1 个 noop 观测步） ----------
        val okDoc = readTraceFile(File(dir, "trace_ok.jsonl"))
        check(okDoc.steps.size == 5, "trace_ok.jsonl 解析出 5 步（含 1 个 noop）：实际 " + okDoc.steps.size)
        val okRes = TracePruner.prune(okDoc)
        check(okRes.steps.size == 4, "trace_ok 5 步剪成 4 步：实际 " + okRes.steps.size)
        check(
            okRes.drops.size == 1 && okRes.drops[0].rule == TracePruner.RULE_OBSERVATION,
            "trace_ok 只删 1 条且规则是 observation：" + okRes.drops.map { it.rule },
        )
        check(
            okRes.steps.map { it.action } == listOf("launch", "tap", "tap", "tap"),
            "trace_ok 留下的动作序列（launch + 3 次 tap）没被改：" + okRes.steps.map { it.action },
        )
        check(
            okRes.steps.map { it.sourceIndex } == listOf(0, 1, 2, 3),
            "trace_ok 只删不重排（sourceIndex 递增）：" + okRes.steps.map { it.sourceIndex },
        )
        check(!okRes.rejected && okRes.replayable, "trace_ok 没被拒，可进重放池")
        check(
            okRes.steps.lastOrNull()?.expectAfter == okDoc.steps.getOrNull(3)?.afterFp,
            "剪枝不篡改后置指纹（重放靠它验收）",
        )

        // ---------- 样本 2：含 1 次误点（verify=unchanged） ----------
        val misDoc = readTraceFile(File(dir, "trace_misclick.jsonl"))
        check(misDoc.steps.size == 5, "trace_misclick.jsonl 解析出 5 步：实际 " + misDoc.steps.size)
        val misRes = TracePruner.prune(misDoc)
        check(misRes.steps.size == 4, "trace_misclick 5 步剪成 4 步：实际 " + misRes.steps.size)
        check(
            misRes.drops.size == 1 && misRes.drops[0].rule == TracePruner.RULE_NOOP_VERIFY,
            "误点按 noop-verify 删除：" + misRes.drops.map { it.rule + "@" + it.sourceIndex },
        )
        check(misRes.drops.firstOrNull()?.sourceIndex == 2, "被删的正是第 2 步（亮度级别），不是别的 tap")
        check(
            misRes.steps.map { it.sourceIndex } == listOf(0, 1, 3, 4),
            "保留的 4 步顺序与原序一致：" + misRes.steps.map { it.sourceIndex },
        )
        check(
            misRes.steps.map { it.targetText() } == listOf("设置", "显示", "深色主题", "使用深色主题"),
            "误点之后的关键步骤一个不少：" + misRes.steps.map { it.targetText() },
        )

        // ---------- 样本 3：1 段绕路 + 3 次重复滚动 + 1 个 noop，9 步 ----------
        val detDoc = readTraceFile(File(dir, "trace_loop_detour.jsonl"))
        check(detDoc.steps.size == 9, "trace_loop_detour.jsonl 解析出 9 步：实际 " + detDoc.steps.size)
        val detRes = TracePruner.prune(detDoc)
        check(detRes.steps.size == 4, "trace_loop_detour 9 步剪成 4 步：实际 " + detRes.steps.size)
        check(
            detRes.dropCount(TracePruner.RULE_DETOUR) == 2,
            "绕路删 2 条（进 B + 回退动作）：" + detRes.dropCount(TracePruner.RULE_DETOUR),
        )
        check(
            detRes.dropCount(TracePruner.RULE_SCROLL_FOLD) == 2,
            "3 次同向 scroll 折成 1 条（记 2 条 drop）：" + detRes.dropCount(TracePruner.RULE_SCROLL_FOLD),
        )
        check(
            detRes.dropCount(TracePruner.RULE_OBSERVATION) == 1,
            "noop 观测步删 1 条：" + detRes.dropCount(TracePruner.RULE_OBSERVATION),
        )
        check(
            detRes.dropCount(TracePruner.RULE_NOOP_VERIFY) == 0,
            "折叠出来的 scroll_to 继承的 unchanged 状态不会被 noop-verify 再删一次",
        )
        check(detRes.drops.size == 5, "共删 5 条，9 - 5 = 4 自洽：实际 " + detRes.drops.size)
        val folded = detRes.steps.firstOrNull { it.action == "scroll_to" }
        check(folded != null, "折叠出来的动作类型是 scroll_to：" + detRes.steps.map { it.action })
        check(
            folded?.targetText() == "屏幕超时",
            "scroll_to 的目标取折叠区间之后第一个 tap 的 anchor.text：" + folded?.targetText(),
        )
        check(folded?.sourceIndex == 4, "折叠步沿用区间第一步的 index（回溯用）：" + folded?.sourceIndex)
        check(
            folded?.expectAfter?.contains("电池") == true,
            "折叠步的 expectAfter 是三次滚动之后的最终屏幕：" + folded?.expectAfter?.size,
        )
        check(
            folded?.args?.get("direction") == "down",
            "折叠步保留原滚动的方向参数：" + folded?.args,
        )
        check(
            detRes.steps.map { it.action } == listOf("launch", "tap", "scroll_to", "tap"),
            "绕路/折叠之后剩下的动作链：" + detRes.steps.map { it.action },
        )
        check(
            detRes.steps.map { it.sourceIndex } == listOf(0, 1, 4, 8),
            "留下的四步来自原轨迹的 0/1/4/8：" + detRes.steps.map { it.sourceIndex },
        )

        // ---------- 拒绝分支：保护页 / 失败任务 ----------
        val protectedDoc = okDoc.copy(
            steps = okDoc.steps.mapIndexed { i, s -> if (i == 1) s.copy(protected = true) else s },
        )
        val protectedRes = TracePruner.prune(protectedDoc)
        check(protectedRes.rejected, "任一步 protected=true 就 rejected")
        check(
            protectedRes.rejectReason == "protected page",
            "拒绝理由是 protected page：" + protectedRes.rejectReason,
        )
        check(protectedRes.steps.isNotEmpty(), "被拒仍返回剪好的步骤（负样本要能看清当时做了什么）")
        check(!protectedRes.replayable, "rejected 的记忆 replayable=false，调用方不会拿它去重放")

        val failedDoc = okDoc.copy(endOk = false, endReason = "timeout")
        val failedRes = TracePruner.prune(failedDoc)
        check(failedRes.rejected, "endOk=false 就 rejected")
        check(
            failedRes.rejectReason == "task failed",
            "拒绝理由是 task failed：" + failedRes.rejectReason,
        )
        check(
            failedRes.dropCount(TracePruner.RULE_REJECT) == 1,
            "失败任务记一条 reject 留痕：" + failedRes.drops.map { it.rule },
        )

        // ---------- retry 规则：三份 fixture 都没覆盖，内联最小样本 ----------
        // 同目标「设置」试了两次，第一次 timeout、第二次成功 —— 只留第二次。
        val retryDoc = TraceDoc(
            traceId = "t_retry_inline",
            startedAt = 0L,
            pkg = "com.android.settings",
            goal = "重试样本",
            steps = listOf(
                TraceStep(index = 0, ts = 1L, endpoint = "launch", beforeFp = setOf("a"), afterFp = setOf("a")),
                TraceStep(
                    index = 1, ts = 2L, endpoint = "tap",
                    anchor = TraceAnchor(text = "设置"),
                    beforeFp = setOf("a"), afterFp = setOf("a"),
                    verify = TraceVerify(status = TraceStatus.TIMEOUT),
                ),
                TraceStep(
                    index = 2, ts = 3L, endpoint = "tap",
                    anchor = TraceAnchor(text = "设置"),
                    beforeFp = setOf("a"), afterFp = setOf("b"),
                    verify = TraceVerify(status = TraceStatus.OK),
                ),
            ),
            endOk = true,
        )
        val retryRes = TracePruner.prune(retryDoc)
        check(
            retryRes.drops.size == 1 && retryRes.drops[0].rule == TracePruner.RULE_RETRY,
            "同目标两次尝试只留最后一次：drop rule=" + retryRes.drops.map { it.rule },
        )
        check(
            retryRes.drops.firstOrNull()?.why?.contains("attempt 1 failed") == true,
            "删除理由写清第几次尝试失败：" + (retryRes.drops.firstOrNull()?.why ?: "-"),
        )
        check(
            retryRes.steps.map { it.sourceIndex } == listOf(0, 2),
            "留下的是第 2 次（成功那次）：" + retryRes.steps.map { it.sourceIndex },
        )
        check(!retryRes.rejected, "重试剪枝不影响可入库状态")
    }

    // ---------------------------------------------------------- fixture 读取器
    //
    // 宿主 JVM 没有 org.json，这里自带一个够用的递归下降解析器：只处理 fixture 里真正出现的
    // 类型（对象 / 数组 / 字符串 / 数字 / 布尔 / null）。写死了字段结构，所以不做流式、不做 schema。

    private class MiniJson(private val src: String) {
        private var i = 0

        fun parse(): Any? {
            val v = readValue()
            skipWs()
            require(i >= src.length) { "trailing garbage at " + i }
            return v
        }

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        private fun readValue(): Any? {
            skipWs()
            require(i < src.length) { "unexpected end of json" }
            return when (src[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++    // '{'
            skipWs()
            if (i < src.length && src[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                require(i < src.length && src[i] == ':') { "expected ':' at " + i }
                i++
                out[key] = readValue()
                skipWs()
                require(i < src.length) { "unterminated object" }
                when (src[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected ',' or '}' at " + i)
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            i++    // '['
            skipWs()
            if (i < src.length && src[i] == ']') { i++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                require(i < src.length) { "unterminated array" }
                when (src[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected ',' or ']' at " + i)
                }
            }
        }

        private fun readString(): String {
            require(i < src.length && src[i] == '"') { "expected '\"' at " + i }
            i++
            val sb = StringBuilder()
            while (i < src.length) {
                val c = src[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = src[i++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = src.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("bad escape " + e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun readNumber(): Double {
            val start = i
            while (i < src.length && (src[i].isDigit() || src[i] == '-' || src[i] == '+' ||
                    src[i] == '.' || src[i] == 'e' || src[i] == 'E')
            ) {
                i++
            }
            require(i > start) { "expected a value at " + start }
            return src.substring(start, i).toDouble()
        }

        private fun expect(word: String) {
            require(src.startsWith(word, i)) { "expected " + word + " at " + i }
            i += word.length
        }
    }

    private object JsonView {
        @Suppress("UNCHECKED_CAST")
        fun map(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()
        fun mapOrNull(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
        fun list(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()
        fun str(v: Any?): String = v as? String ?: ""
        fun num(v: Any?): Double = (v as? Number)?.toDouble() ?: 0.0

        /** JSON 数字一律解析成 Double，但 args 要的是 "540" 不是 "540.0"。 */
        fun arg(v: Any?): String = when (v) {
            null -> ""
            is String -> v
            is Boolean -> v.toString()
            is Double -> if (!v.isInfinite() && v == Math.floor(v) && Math.abs(v) < 1e15) {
                v.toLong().toString()
            } else {
                v.toString()
            }
            else -> v.toString()
        }
    }

    private fun readTraceFile(file: File): TraceDoc {
        var head: Map<String, Any?> = emptyMap()
        var endOk: Boolean? = null
        var endReason = ""
        val steps = ArrayList<TraceStep>()
        for (line in file.readLines()) {
            val text = line.trim()
            if (text.isEmpty() || text[0] != '{') continue
            val obj = JsonView.map(MiniJson(text).parse())
            when (JsonView.str(obj["type"])) {
                "trace" -> head = obj
                "step" -> steps.add(stepOf(obj, steps.size))
                "end" -> {
                    endOk = obj["ok"] as? Boolean
                    endReason = JsonView.str(obj["reason"])
                }
            }
        }
        val schema = JsonView.num(head["schema"]).toInt()
        return TraceDoc(
            traceId = JsonView.str(head["traceId"]),
            startedAt = JsonView.num(head["startedAt"]).toLong(),
            pkg = JsonView.str(head["pkg"]),
            goal = JsonView.str(head["goal"]),
            steps = steps,
            endOk = endOk,
            endReason = endReason,
            schema = if (schema <= 0) 1 else schema,
        )
    }

    private fun stepOf(obj: Map<String, Any?>, index: Int): TraceStep {
        val anchor = JsonView.mapOrNull(obj["anchor"])?.let { a ->
            TraceAnchor(
                text = JsonView.str(a["text"]),
                role = JsonView.str(a["role"]),
                viewId = JsonView.str(a["viewId"]),
                bounds = JsonView.list(a["bounds"]).map { JsonView.num(it).toFloat() },
            )
        }
        val verify = JsonView.map(obj["verify"])
        val args = LinkedHashMap<String, String>()
        for ((k, v) in JsonView.map(obj["args"])) args[k] = JsonView.arg(v)
        return TraceStep(
            index = index,
            ts = JsonView.num(obj["ts"]).toLong(),
            endpoint = JsonView.str(obj["endpoint"]),
            args = args,
            beforeFp = fpOf(obj["beforeFp"]),
            afterFp = fpOf(obj["afterFp"]),
            anchor = anchor,
            verify = TraceVerify(
                status = JsonView.str(verify["status"]).ifBlank { TraceStatus.OK },
                via = JsonView.str(verify["via"]).ifBlank { TraceVia.NONE },
                ms = JsonView.num(verify["ms"]).toLong(),
                retries = JsonView.num(verify["retries"]).toInt(),
            ),
            caller = JsonView.str(obj["caller"]).ifBlank { "loop" },
            protected = (obj["protected"] as? Boolean) ?: false,
        )
    }

    private fun fpOf(v: Any?): Set<String> {
        val set = LinkedHashSet<String>()
        for (item in JsonView.list(v)) set.add(JsonView.str(item))
        return set
    }

    /**
     * 找 scripts/jvm/fixtures：先看 -DtraceFixtures=，再沿 cwd 与 class 目录往上找。
     *
     * 为什么不用写死的相对路径：run-a11y-jvm-test.sh 并不 cd 到仓库根，谁从哪个目录调用都有可能。
     * 但类文件一定落在 <repo>/build/a11y-jvm-test/out 下，从 code source 往上爬必定命中仓库根。
     */
    private fun traceFixtureDir(): File {
        val candidates = ArrayList<File>()
        System.getProperty("traceFixtures")?.let { candidates.add(File(it)) }
        var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (d != null) {
            candidates.add(File(d, "scripts/jvm/fixtures"))
            d = d.parentFile
        }
        runCatching {
            val url = BehaviourTest::class.java.protectionDomain?.codeSource?.location
                ?: return@runCatching
            var f: File? = File(url.toURI()).absoluteFile
            while (f != null) {
                candidates.add(File(f, "scripts/jvm/fixtures"))
                f = f.parentFile
            }
        }

        return candidates.firstOrNull { File(it, "trace_ok.jsonl").isFile } ?: candidates.first()
    }

    // ===== MemoryStore v2：容量淘汰与数据模型（M3 / F3.3） =====
    //
    // 只测纯逻辑：MemoryStore 的落盘那层要 Context（宿主机 JVM 里 android.jar 只是 Stub），
    // 那部分靠模拟器实测；而「该淘汰谁」「新条目长什么样」是纯函数，也是最容易写错的地方
    // —— 淘汰错一条，用户刚学会的路径就白学了。这里全部是真跑函数，不是 grep 源码。
    private fun memTag(prefix: String, i: Int, width: Int = 2): String =
        prefix + i.toString().padStart(width, '0')

    private fun mem(
        id: String,
        pkg: String = "com.example.app",
        hits: Int = 0,
        lastUsed: Long = 0L,
        state: String = MemoryContract.STATE_ACTIVE,
        createdAt: Long = 0L,
    ): MemoryEntry = MemoryEntry(
        id = id,
        pkg = pkg,
        intent = "intent " + id,
        createdAt = createdAt,
        hits = hits,
        lastUsed = lastUsed,
        state = state,
    )

    private fun assertMemoryStore() {
        println("== MemoryStore v2 retention / model ==")

        // 1) 上限是设计文档 §5 定死的数字（UI 与文档都引用它，改这里必须同步改那两处）
        check(MemoryRetention.PER_PKG == 50, "PER_PKG is 50")
        check(MemoryRetention.GLOBAL == 500, "GLOBAL is 500")

        // 2) 同包 60 条 -> 淘汰 10 条（G3 的第一条验收）
        //    hits 与 id **故意反序**（a_00 命中最高、a_59 最低）：如果实现忽略了 hits、
        //    靠最后的 id 兜底排序，名单会碰巧一样 —— 反序才抓得住这种坏实现。
        val sixty = (0 until 60).map { mem(memTag("a_", it), hits = 59 - it) }
        val ev60 = MemoryRetention.toEvict(sixty)
        check(ev60.size == 10, "60 entries in one package evict exactly 10, got " + ev60.size)

        // 3) 淘汰的正是 hits 最低的 10 条（a_50..a_59），不是随便 10 条
        check(
            ev60.toSet() == (50 until 60).map { memTag("a_", it) }.toSet(),
            "the 10 evicted are the lowest-hits entries (a_50..a_59)",
        )

        // 4) 高命中的一条都没被碰（反向检查：全淘汰或全不淘汰都会让上面两条失真）
        check("a_00" !in ev60 && "a_49" !in ev60, "high-hit entries survive")

        // 5) 边界：正好 50 条不淘汰（规则是「超过才削」，不是「等于就削」）
        val fifty = (0 until 50).map { mem(memTag("b_", it), hits = it) }
        check(MemoryRetention.toEvict(fifty).isEmpty(), "exactly 50 entries evict nothing")

        // 6) 全局上限：600 条、每包不限 -> 淘汰 100 条，且正是命中最低的那 100 条
        val wide = (0 until 600).map { mem(memTag("g_", it, 3), pkg = "p" + (it % 10), hits = it / 10) }
        val evGlobal = MemoryRetention.toEvict(wide, perPkg = 1000, global = 500)
        check(
            evGlobal.size == 100,
            "600 entries with a free per-pkg cap evict 100 by the global cap, got " + evGlobal.size,
        )
        check(evGlobal.toSet() == wide.filter { it.hits < 10 }.map { it.id }.toSet(),
            "the global trim takes the 100 lowest-hit entries")

        // 7) 每包关先削：10 包 x 60 条（默认 50/包）-> 每包削 10，共 100
        val both = (0 until 600).map { mem(memTag("h_", it, 3), pkg = "q" + (it % 10), hits = it / 10) }
        val evBoth = MemoryRetention.toEvict(both)
        check(
            evBoth.size == 100,
            "600 entries in 10 packages evict 100 by the per-pkg rule alone, got " + evBoth.size,
        )
        check(evBoth.none { id -> both.first { it.id == id }.hits >= 10 },
            "per-pkg trimming also keeps the high-hit ones")
        check(evBoth.size == evBoth.toSet().size, "the eviction list has no duplicate ids")

        // 8) disabled 优先于 hits：5 条 disabled 即使是最高命中，也先被淘汰
        val withDisabled = (0 until 50).map { mem(memTag("c_", it), hits = 0, lastUsed = 1000L + it) } +
            (0 until 5).map { mem("d_" + it, hits = 99, lastUsed = 9000L, state = MemoryContract.STATE_DISABLED) }
        val evDisabled = MemoryRetention.toEvict(withDisabled)
        check(evDisabled.size == 5, "55 entries evict 5, got " + evDisabled.size)
        check(
            evDisabled.toSet() == (0 until 5).map { "d_" + it }.toSet(),
            "disabled entries go first even with the highest hits",
        )

        // 9) hits 相同看 lastUsed（LRU）：52 条同 hits -> 淘汰最旧的两条。
        //    lastUsed 与 id 同样**故意反序**（e_51 最老），忽略 lastUsed 的实现会误淘汰 e_00/e_01。
        val sameHits = (0 until 52).map { mem(memTag("e_", it), hits = 0, lastUsed = 10_000L - it) }
        check(MemoryRetention.toEvict(sameHits).toSet() == setOf("e_51", "e_50"),
            "same hits -> the two oldest lastUsed go first (e_51, e_50)")

        // 10) suspect 排在 active 之前：1 条 suspect 命中再高也先于 active 被淘汰
        val withSuspect = (0 until 51).map { mem(memTag("f_", it), hits = 0, lastUsed = 5000L + it) } +
            mem("sus", hits = 999, lastUsed = 9000L, state = MemoryContract.STATE_SUSPECT)
        val evSuspect = MemoryRetention.toEvict(withSuspect)
        check(
            evSuspect.size == 2 && "sus" in evSuspect,
            "a suspect entry goes before active ones even with more hits",
        )

        // 11) 确定性：入参顺序反过来，名单完全一致（门禁不能掷骰子）
        check(
            MemoryRetention.toEvict(sixty) == MemoryRetention.toEvict(sixty.reversed()),
            "eviction is deterministic and independent of input order",
        )

        // 12) 空输入不炸
        check(MemoryRetention.toEvict(emptyList()).isEmpty(), "empty input evicts nothing")

        // 13) MemoryEntry 默认值（新建一条记忆时的出厂状态）
        val fresh = MemoryEntry(
            id = "m_1700000000000_abcd",
            pkg = "com.example.app",
            intent = "open settings",
            createdAt = 42L,
        )
        check(fresh.state == "active" && fresh.isActive(), "a new entry is active")
        check(fresh.hits == 0 && fresh.misses == 0 && fresh.pendingHits == 0, "counters start at zero")
        check(fresh.pending && fresh.rev == 1, "a new entry is pending at rev 1")
        check(
            fresh.appVersion.isEmpty() && fresh.title.isEmpty() && fresh.keywords.isEmpty() &&
                fresh.steps.isEmpty() && fresh.vector.isEmpty(),
            "optional fields default to empty",
        )
        check(fresh.lastUsed == 0L && fresh.originTraceId.isEmpty(), "no lastUsed / trace yet")
        check(fresh.sceneFp.isEmpty(), "no scene fingerprint yet")

        // 14) 命中与失败的状态迁移（misses >= 2 自动转 suspect）
        val hit1 = fresh.afterHit(1700000001000L)
        check(hit1.hits == 1 && hit1.lastUsed == 1700000001000L && hit1.misses == 0,
            "afterHit bumps hits and lastUsed")
        val miss1 = hit1.afterMiss()
        check(miss1.misses == 1 && miss1.state == MemoryContract.STATE_ACTIVE, "one miss keeps it active")
        check(miss1.lastUsed == hit1.lastUsed,
            "a miss does NOT refresh lastUsed (a failing entry must not look fresh)")
        val miss2 = miss1.afterMiss()
        check(miss2.misses == 2 && miss2.state == MemoryContract.STATE_SUSPECT,
            "the 2nd consecutive miss flips it to suspect")
        val recovered = miss1.afterHit(1700000002000L)
        check(recovered.misses == 0 && recovered.hits == 2, "a hit resets the miss streak")

        // 15) MemoryStep 默认值 / 锚点 / 中心点
        val bare = MemoryStep(sourceIndex = 3, action = "tap")
        check(bare.targetText.isEmpty() && bare.targetRole.isEmpty() && bare.targetViewId.isEmpty(),
            "unknown anchor fields default to empty")
        check(
            bare.targetBounds.isEmpty() && bare.args.isEmpty() && bare.expectAfter.isEmpty() &&
                bare.preFp.isEmpty(),
            "collections default to empty (including preFp)",
        )
        check(!bare.hasAnchor() && bare.center() == null, "a bare step has no anchor and no center")
        // 用二进制精确值（0.25 / 0.5 / 0.75 / 1.0），浮点相等才是可靠的断言
        val boxed = bare.copy(targetText = "Wi-Fi", targetBounds = listOf(0.25f, 0.5f, 0.75f, 1f))
        check(boxed.hasAnchor(), "a step with text plus bounds has an anchor")
        check(boxed.center() == (0.5f to 0.75f), "center is the middle of the normalized box")
        val realBox = bare.copy(targetBounds = listOf(0.12f, 0.31f, 0.88f, 0.35f))
        val realCenter = realBox.center()
        check(realCenter != null && kotlin.math.abs(realCenter.first - 0.5f) < 1e-4f,
            "a realistic dump box centers on x=0.5")
        check(bare.copy(targetBounds = listOf(0.1f, 0.2f, 0.3f)).center() == null,
            "a 3-element box is not a box")

        // 15b) 指纹两级取用：整条 sceneFp 优先，退回首步 preFp（M4 的 Jaccard 靠它）
        val fpOnEntry = fresh.copy(sceneFp = listOf("设置", "pkg:com.example.app"))
        check(fpOnEntry.fingerprint() == listOf("设置", "pkg:com.example.app"),
            "fingerprint() prefers sceneFp")
        val fpOnStep = fresh.copy(
            steps = listOf(MemoryStep(0, "tap", preFp = listOf("显示", "pkg:com.example.app"))),
        )
        check(fpOnStep.fingerprint() == listOf("显示", "pkg:com.example.app"),
            "fingerprint() falls back to the first step preFp")
        check(fresh.fingerprint().isEmpty(), "a memory with neither fingerprint reports empty")

        // 16) 三个状态缺一不可（少一个，条目就会既不命中也不能被淘汰）
        check(
            MemoryContract.STATES.size == 3 && MemoryContract.STATE_SUSPECT in MemoryContract.STATES,
            "three states are declared",
        )

        // 16) stats 摘要：逐字断言（字段顺序、计数口径、英文文案一次钉死）。
        //     /memory/stats 是 P5 观测项的唯一出口，数字对不上就等于没有可观测性。
        val summaryEntries = listOf(
            mem("s1", pkg = "com.a", hits = 2),
            mem("s2", pkg = "com.a", hits = 1, state = MemoryContract.STATE_SUSPECT)
                .copy(misses = 2, pending = false),
            mem("s3", pkg = "com.b", state = MemoryContract.STATE_DISABLED).copy(pending = false),
        )
        val summary = MemorySummary.of(summaryEntries)
        val expected = """memories=3 pkgs=2 active=1 suspect=1 disabled=1 pending=1 hits=3 misses=2 :: {"schema":2,"total":3,"pkgs":2,"active":1,"suspect":1,"disabled":1,"pending":1,"hits":3,"misses":2}"""
        check(summary == expected, "stats summary has the exact expected shape, got: " + summary)
        // i18n 门禁禁止 Kotlin 字面量出现 CJK：这条在 JVM 侧先拦一道（不用等 python 门禁）
        check(summary.all { it.code < 128 }, "stats summary is pure ASCII (no CJK literals)")
        check(MemorySummary.of(emptyList()).startsWith("memories=0 pkgs=0 "),
            "an empty store still reports zeros")
    }

    // ===== M6：修正与扩展（patch / extend / fork / rollback） =====
    //
    // 这一段全在 MemoryEdits 上跑（纯函数，不碰 Context/File）。
    // 存储层的四个同名方法只是「定位 + 调这里 + 落盘」，语义若错，错在这里，
    // 所以断言必须打在这层而不是打路由。
    private fun mstep(i: Int, text: String, act: String = "tap"): MemoryStep =
        MemoryStep(sourceIndex = i, action = act, targetText = text)

    private fun editable(): MemoryEntry = MemoryEntry(
        id = "m_1000_0001",
        pkg = "com.example.app",
        intent = "turn on wifi",
        steps = listOf(mstep(0, "Settings"), mstep(1, "Network", "tap_id"), mstep(2, "WLAN")),
        createdAt = 1000L,
        hits = 3,
        misses = 1,
        pending = true,
        pendingHits = 1,
        sceneFp = listOf("pkg:com.example.app", "Settings"),
    )

    private fun assertMemoryEdits() {
        println("== MemoryEdits v2: patch / extend / fork / rollback ==")

        // ---------- 1) patch：只改文本 ----------
        val base0 = editable()
        val p1 = MemoryEdits.patch(base0, 1, "Network and internet", null, 2000L)
        check(p1 != null, "patch returns a new entry")
        check(p1!!.steps[1].targetText == "Network and internet", "patch rewrites the target text")
        check(p1.steps[1].action == "tap_id", "patch leaves the action alone when it is null")
        check(p1.steps.size == 3, "patch does not change the step count")
        check(p1.rev == 2, "patch bumps rev from 1 to 2")
        check(p1.history.size == 1 && p1.history[0].rev == 1, "patch keeps the previous rev in history")
        check(p1.history[0].kind == MemoryContract.KIND_PATCH, "history kind is patch")
        check(p1.history[0].note == "step=1", "history note names the step, got: " + p1.history[0].note)
        check(p1.history[0].snapshot.steps[1].targetText == "Network", "history snapshot holds the BEFORE text")
        check(base0.steps[1].targetText == "Network", "patch never mutates the entry it was given")

        // ---------- 2) patch：只改动作 ----------
        val p2 = MemoryEdits.patch(base0, 1, null, "tap")!!
        check(p2.steps[1].action == "tap", "patch rewrites the action")
        check(p2.steps[1].targetText == "Network", "patch leaves the text alone when it is null")

        // ---------- 3) patch：两样都改 ----------
        val p3 = MemoryEdits.patch(base0, 1, "Wi-Fi", "tap_text")!!
        check(p3.steps[1].targetText == "Wi-Fi" && p3.steps[1].action == "tap_text",
            "patch rewrites text and action together")

        // ---------- 4) 清空文本是合法操作，清空动作不是 ----------
        val cleared = MemoryEdits.patch(base0, 1, "", null, 2000L)!!
        check(!cleared.steps[1].hasAnchor(), "an empty target text clears the step anchor")
        val blankAct = MemoryEdits.patch(base0, 1, null, "  ", 2000L)
        check(blankAct === base0, "a blank action is treated as no change (same instance back)")

        // ---------- 5) 什么都不改 = 不算一次修改 ----------
        val noop = MemoryEdits.patch(base0, 1, null, null, 2000L)
        check(noop === base0, "patch with both fields null returns the entry unchanged")
        check(noop!!.rev == 1 && noop.history.isEmpty(), "a no-op patch does not consume a history slot")

        // ---------- 6) 越界与非法 ----------
        check(MemoryEdits.patch(base0, -1, "x", null, 2000L) == null, "patch rejects a negative step index")
        check(MemoryEdits.patch(base0, 3, "x", null, 2000L) == null, "patch rejects step index == size")
        check(MemoryEdits.patch(null, 0, "x", null, 2000L) == null, "patch returns null when the id is unknown")

        // ---------- 7) 连续两次 patch：rev 累加、快照是「上一次改完的样子」 ----------
        val twice = MemoryEdits.patch(p1, 1, "Wi-Fi", null, 3000L)!!
        check(twice.rev == 3, "a second patch bumps rev to 3")
        check(twice.history.map { it.rev } == listOf(1, 2), "history holds revs 1 and 2")
        check(twice.history[1].snapshot.steps[1].targetText == "Network and internet",
            "the second snapshot is the state right before the second patch")
        check(twice.history.all { it.snapshot.history.isEmpty() },
            "history snapshots never carry their own history (no exponential growth)")

        // ---------- 8) extend：末尾追加，顺序不变 ----------
        val e1 = MemoryEdits.extend(base0, listOf(mstep(3, "More"), mstep(4, "Advanced")), "learned more", 4000L)!!
        check(e1.steps.size == 5, "extend appends the new steps, got " + e1.steps.size)
        check(e1.steps.map { it.targetText } == listOf("Settings", "Network", "WLAN", "More", "Advanced"),
            "extend keeps the original order and appends after it")
        check(e1.rev == 2, "extend bumps rev")
        check(e1.history[0].kind == MemoryContract.KIND_EXTEND, "history kind is extend")
        check(e1.history[0].note == "learned more", "extend keeps the caller note")
        check(MemoryEdits.extend(base0, emptyList(), "", 4000L) === base0,
            "extending with no steps is a no-op (same instance back)")
        check(MemoryEdits.extend(null, listOf(mstep(9, "x")), "", 4000L) == null,
            "extend returns null when the id is unknown")

        // ---------- 9) fork：前缀 + 归零 ----------
        val child = MemoryEdits.fork(base0, 2, "m_5000_4242", "switch wifi off", "", 5000L)!!
        check(child.id == "m_5000_4242", "fork uses the id it was handed")
        check(child.id != base0.id, "fork produces a different id")
        check(child.steps.size == 2, "fork takes exactly the prefix steps, got " + child.steps.size)
        check(child.steps.map { it.targetText } == listOf("Settings", "Network"),
            "fork keeps the first fromStep steps in order")
        check(child.intent == "switch wifi off", "fork uses the new intent")
        check(child.title == "switch wifi off", "fork defaults the title to the new intent")
        check(child.hits == 0 && child.misses == 0, "fork zeroes hits and misses")
        check(!child.pending, "a hand-forked memory is not pending (no double-confirm needed)")
        check(child.rev == 1 && child.history.isEmpty(), "fork starts a fresh revision history")
        check(child.state == MemoryContract.STATE_ACTIVE, "fork starts active")
        check(child.sceneFp == base0.sceneFp, "fork inherits the start-screen fingerprint")
        check(base0.steps.size == 3 && base0.rev == 1 && base0.hits == 3,
            "fork leaves the parent untouched")

        // ---------- 10) fork 的边界 ----------
        check(MemoryEdits.fork(base0, 0, "m_5000_0000", "x", "", 5000L)?.steps?.isEmpty() == true,
            "forking at step 0 yields a memory with no steps")
        check(MemoryEdits.fork(base0, 3, "m_5000_0001", "x", "", 5000L)?.steps?.size == 3,
            "forking at size keeps every step")
        check(MemoryEdits.fork(base0, 4, "m_5000_0002", "x", "", 5000L) == null,
            "fork rejects fromStep > size")
        check(MemoryEdits.fork(base0, -1, "m_5000_0003", "x", "", 5000L) == null,
            "fork rejects a negative fromStep")
        check(MemoryEdits.fork(base0, 1, "m_5000_0004", "   ", "", 5000L) == null,
            "fork rejects a blank intent")
        check(MemoryEdits.fork(null, 1, "m_5000_0005", "x", "", 5000L) == null,
            "fork returns null when the id is unknown")

        // ---------- 11) 父记忆留痕：不升版，但记住孩子是谁 ----------
        val marked = MemoryEdits.markForked(base0, child.id, 6000L)
        check(marked.rev == 1, "markForked does not bump the parent rev")
        check(marked.history.size == 1 && marked.history[0].kind == MemoryContract.KIND_FORK,
            "markForked records a fork revision")
        check(marked.history[0].note == child.id, "the fork note carries the child id")
        check(marked.steps == base0.steps, "markForked keeps every step")

        // ---------- 12) rollback：回到旧版，且回滚本身可回滚 ----------
        val r1 = MemoryEdits.rollback(p1, 1, 7000L)!!
        check(r1.steps[1].targetText == "Network", "rollback restores the old target text")
        check(r1.steps.size == 3, "rollback restores the old step count")
        check(r1.rev == 3, "rollback becomes a new revision (max + 1), got " + r1.rev)
        check(r1.history.map { it.kind } == listOf(MemoryContract.KIND_PATCH, MemoryContract.KIND_ROLLBACK),
            "rollback appends its own revision")
        check(r1.history.last().note == "to=1", "the rollback note names the target rev")
        val r2 = MemoryEdits.rollback(r1, 2, 8000L)!!
        check(r2.steps[1].targetText == "Network and internet",
            "the rollback itself can be rolled back (restores the patched text)")
        check(r2.rev == 4, "the second rollback also takes max + 1, got " + r2.rev)

        // ---------- 13) rollback 恢复的是整条快照（含统计） ----------
        val risen = base0.copy(hits = 9, misses = 0)
        val patched = MemoryEdits.patch(risen, 0, "System", null, 9000L)!!
        val back = MemoryEdits.rollback(patched, 1, 9100L)!!
        // 快照恢复是逐字段还原：patch 既没动 hits 也没动 misses，回滚后应与 patch 前完全一致。
        // 原先这里写的是 misses==1，与断言自己的名字（restores the snapshot's stats）矛盾，
        // 也与「回滚是修复动作、不是一次失败」的口径冲突 —— 回滚不该把记忆推向 suspect。
        check(patched.hits == 9 && patched.misses == 0, "patch keeps the stats untouched")
        check(back.hits == 9 && back.misses == 0, "rollback restores the snapshot's stats too")

        // ---------- 14) rollback 的拒绝路径 ----------
        check(MemoryEdits.rollback(p1, 99, 7000L) == null, "rollback rejects an unknown rev")
        check(MemoryEdits.rollback(base0, 1, 7000L) == null, "rollback rejects an entry with no history")
        check(MemoryEdits.rollback(null, 1, 7000L) == null, "rollback returns null when the id is unknown")

        // ---------- 15) 历史上限：12 次修正只留最近 10 版 ----------
        var chained = editable()
        for (i in 1..12) chained = MemoryEdits.patch(chained, 0, "text" + i, null, 10000L + i)!!
        check(chained.rev == 13, "twelve patches land on rev 13, got " + chained.rev)
        check(chained.history.size == MemoryContract.HISTORY_MAX, "history is capped at HISTORY_MAX (10)")
        check(chained.history.first().rev == 3 && chained.history.last().rev == 12,
            "the cap drops the oldest revisions and keeps 3..12")
        check(chained.history.all { it.snapshot.history.isEmpty() },
            "every retained snapshot still carries no nested history")
    }

    // ===== MemoryIndex v2：三级降级 + 结构打分 + 分档（M4 / F4.1-F4.3） =====
    //
    // 这一段全部是纯函数真跑：embed() 要网络、要 llama-server，不在这里调（真机才验）；
    // 但「哈希向量怎么算」「怎么打分」「阈值落在哪」「跨 App 会不会误命中」都能在宿主机上钉死。
    private fun vec(vararg v: Float): List<Float> = v.toList()

    private fun assertMemoryIndex() {
        println("== MemoryIndex v2 semantic scoring ==")

        // 1) 余弦：同向 / 正交 / 反向 / 零向量 / 尺寸不等
        check(kotlin.math.abs(MemoryIndex.cosine(vec(1f, 0f), vec(1f, 0f)) - 1.0) < 1e-9,
            "cosine of identical directions is 1")
        check(kotlin.math.abs(MemoryIndex.cosine(vec(1f, 0f), vec(0f, 1f))) < 1e-9,
            "cosine of orthogonal vectors is 0")
        check(kotlin.math.abs(MemoryIndex.cosine(vec(1f, 0f), vec(-1f, 0f)) + 1.0) < 1e-9,
            "cosine of opposite vectors is -1")
        check(MemoryIndex.cosine(vec(0f, 0f), vec(1f, 1f)) == 0.0,
            "a zero vector scores 0 instead of NaN")
        check(MemoryIndex.cosine(vec(1f, 0f), vec(1f)) == 0.0,
            "vectors of different length score 0 (512 vs 256 must not be compared)")

        // 2) 加权打分：0.6/0.3/0.1
        check(kotlin.math.abs(MemoryIndex.score(1.0, 1.0, true) - 1.0) < 1e-9,
            "score saturates at 1.0 for a perfect candidate")
        check(kotlin.math.abs(MemoryIndex.score(0.5, 0.5, false) - 0.45) < 1e-9,
            "score is 0.6*cos + 0.3*fp")
        check(kotlin.math.abs(MemoryIndex.score(0.0, 0.0, false)) < 1e-9,
            "an all-zero candidate scores 0")
        check(kotlin.math.abs(MemoryIndex.score(0.5, 0.5, true) - 0.55) < 1e-9,
            "the anchor bit is worth exactly 0.1")

        // 3) 分档阈值 0.85 / 0.65：两侧都要测，否则「>= 写成 >」这种错会溜过去
        check(MemoryIndex.tier(0.85) == "high", "0.85 is high")
        check(MemoryIndex.tier(0.8499) == "mid", "0.8499 is only mid")
        check(MemoryIndex.tier(0.65) == "mid", "0.65 is mid")
        check(MemoryIndex.tier(0.6499) == "low", "0.6499 is low")
        check(MemoryIndex.tier(0.0) == "low", "0 is low")
        check(MemoryIndex.tier(Double.NaN) == "low", "NaN degrades to low, never high")

        // 4) 哈希兜底向量：维度固定、确定性、L2 归一化、形近文本更近
        val darkIntent = "打开深色主题"
        val hv = MemoryIndex.embedFallback(darkIntent)
        check(hv.size == MemoryIndex.FALLBACK_DIM, "the fallback vector has 256 dims, got " + hv.size)
        check(hv == MemoryIndex.embedFallback(darkIntent), "the fallback vector is deterministic")
        var norm = 0.0
        for (x in hv) norm += x.toDouble() * x
        check(kotlin.math.abs(norm - 1.0) < 1e-6, "the fallback vector is L2-normalized")
        check(MemoryIndex.embedFallback("").all { it == 0f }, "empty text gives a zero vector, not NaN")
        val closeCos = MemoryIndex.cosine(hv, MemoryIndex.embedFallback("打开深色主题设置"))
        val farCos = MemoryIndex.cosine(hv, MemoryIndex.embedFallback("删除所有聊天记录"))
        check(closeCos > farCos + 0.1,
            "bigram hash keeps look-alike intents closer (close=" + closeCos + " far=" + farCos + ")")

        // 5) rank：同包最像的排第一、跨 App 一条都不返回、锚点加分、满分进 high
        val pkgA = "com.android.settings"
        val fpNow = setOf("设置", "显示", "深色主题", "pkg:" + pkgA)
        val dark = mem("i_dark", pkg = pkgA, hits = 3).copy(
            intent = darkIntent,
            sceneFp = listOf("设置", "显示", "深色主题", "pkg:" + pkgA),
            steps = listOf(MemoryStep(0, "tap", targetText = "深色主题", preFp = listOf("设置"))),
        )
        val wipe = mem("i_wipe", pkg = pkgA).copy(
            intent = "删除所有聊天记录",
            sceneFp = listOf("聊天", "列表", "pkg:" + pkgA),
        )
        val otherApp = mem("i_other", pkg = "com.tencent.mm").copy(
            intent = darkIntent,
            sceneFp = listOf("设置", "显示", "深色主题", "pkg:com.tencent.mm"),
        )
        val ranked = MemoryIndex.rank(
            listOf(wipe, otherApp, dark),
            darkIntent,
            fpNow,
            setOf("深色主题", "显示"),
        )
        check(ranked.isNotEmpty() && ranked[0].entry.id == "i_dark", "the matching memory ranks first")
        check(ranked.none { it.entry.id == "i_other" },
            "a memory from another package is never recalled (cross-app guard)")
        check(ranked[0].anchor, "the first-step anchor present on screen scores the anchor bit")
        check(ranked[0].cos > 0.99,
            "an entry without a stored vector still scores by the hash fallback")
        check(kotlin.math.abs(ranked[0].fp - 1.0) < 1e-9, "an identical fingerprint gives fp = 1.0")
        check(ranked[0].tier == "high", "a perfect match lands in high tier")

        // 6) fp 就是两组文本的 Jaccard（不是包含率，也不是长度比）
        val partial = mem("i_part", pkg = pkgA).copy(
            intent = darkIntent,
            sceneFp = listOf("设置", "pkg:" + pkgA),
        )
        val partialRank = MemoryIndex.rank(listOf(partial), darkIntent, fpNow, emptySet())
        check(kotlin.math.abs(partialRank[0].fp - 0.5) < 1e-9,
            "fp is the Jaccard of the two text sets (2 shared of 4), got " + partialRank[0].fp)

        // 7) 锚点缺失只扣 0.1，不能变成 0 分（记忆的价值主要在步骤，不在“这一屏”长什么样）
        val noAnchor = MemoryIndex.rank(listOf(dark), darkIntent, fpNow, emptySet())
        check(!noAnchor[0].anchor, "no visible text -> the anchor bit is off")
        check(kotlin.math.abs(noAnchor[0].score - (ranked[0].score - 0.1)) < 1e-9,
            "losing the anchor costs exactly 0.1")

        // 8) limit 截断与边界
        check(MemoryIndex.rank(listOf(dark, wipe, partial), darkIntent, fpNow, emptySet(), limit = 2).size == 2,
            "limit truncates the result list")
        check(MemoryIndex.rank(listOf(dark), darkIntent, fpNow, emptySet(), limit = 0).isEmpty(),
            "limit 0 returns nothing")
        check(MemoryIndex.rank(emptyList(), darkIntent, fpNow, emptySet()).isEmpty(),
            "no candidates -> empty, not an exception")

        // 9) 同分稳定性：分数相同按 hits、再按 id，且与入参顺序无关
        val tieA = mem("z_a", pkg = pkgA).copy(intent = darkIntent)
        val tieB = mem("z_b", pkg = pkgA).copy(intent = darkIntent)
        val tie1 = MemoryIndex.rank(listOf(tieB, tieA), darkIntent, fpNow, emptySet())
        val tie2 = MemoryIndex.rank(listOf(tieA, tieB), darkIntent, fpNow, emptySet())
        check(tie1.size == 2 && tie1[0].entry.id == "z_a", "equal scores fall back to id order")
        check(tie1.map { it.entry.id } == tie2.map { it.entry.id },
            "input order does not change the ranking (deterministic)")

        // 10) state 安全：disabled 永不召回；suspect 最高只到 mid（不许“直接重放、不惊动模型”）
        check(MemoryIndex.rank(listOf(dark.copy(state = MemoryContract.STATE_DISABLED)), darkIntent, fpNow,
            setOf("深色主题")).isEmpty(), "disabled memories are never recalled")
        val suspectRank = MemoryIndex.rank(listOf(dark.copy(state = MemoryContract.STATE_SUSPECT)), darkIntent,
            fpNow, setOf("深色主题"))
        check(suspectRank[0].score > 0.99 && suspectRank[0].tier == "mid",
            "a suspect entry with a perfect score is capped at mid")

        // 11) fp 里没有 pkg: 时无法判断归属：不过滤（调用方必须保证 dump 带前台包名）
        check(MemoryIndex.rank(listOf(dark, otherApp), darkIntent, setOf("设置"), emptySet()).size == 2,
            "without a pkg: marker both entries are returned (documented caller contract)")

        // 12) 降级链可观测：engineOf 报出这条走的是哪一级
        check(MemoryIndex.engineOf(dark.copy(vector = vec(1f, 0f)), "任意") == "bge",
            "a stored vector reports the bge level")
        check(MemoryIndex.engineOf(dark, darkIntent) == "hash",
            "no vector reports the hash level")
        check(MemoryIndex.engineOf(dark.copy(intent = "", keywords = listOf("深色主题")), darkIntent) == "keyword",
            "a keywords-only match reports the keyword level")
    }

    // ===== MemoryReplay v2：锚点预校验 / 预检 / 后置软校验（M5 / F5.2） =====
    //
    // 这一段只跑**纯函数**。run() 要 Context + 无障碍服务，宿主机上跑不了（真机验收在 G5），
    // 但 M5 的安全核心恰好全在纯函数里：「锚点不在就不许动」「预检的先后顺序」
    // 「后置条件到底算不算满足」。这三样一旦飘了，真机上就是点歪或者好记忆被误杀。
    private fun mstep(
        action: String = "tap",
        target: String = "",
        viewId: String = "",
        bounds: List<Float> = emptyList(),
        expect: List<String> = emptyList(),
        args: Map<String, String> = emptyMap(),
    ): MemoryStep = MemoryStep(
        sourceIndex = 0,
        action = action,
        targetText = target,
        targetViewId = viewId,
        targetBounds = bounds,
        args = args,
        expectAfter = expect,
    )

    private fun entryOf(
        pkg: String = "com.android.settings",
        state: String = MemoryContract.STATE_ACTIVE,
        steps: List<MemoryStep> = listOf(mstep(target = "深色主题")),
    ): MemoryEntry = mem("m_replay", pkg = pkg, state = state).copy(steps = steps)

    /**
     * TraceArgs：桥字段 → 重放参数名的翻译。
     *
     * 这一层的价值就是「录下来的参数重放时认得」：漏翻译时轨迹 args 恒为空，
     * key 会退化成 back、launch 报缺包（真机上就是这么失败的）。
     */
    /**
     * TracePruner.unreplayable：不可重放的轨迹必须在**入库前**被挡掉。
     *
     * 背景：桥把整次 /loop/run 记成一步（loop_step），内部动作没进轨迹，
     * 重放必然 unsupported。存下来只会变成「命中即失败」的垃圾记忆。
     */
    private fun assertUnreplayable() {
        println("== TracePruner.unreplayable: loop_step 不入库 ==")

        fun st(action: String, i: Int = 0) = PrunedStep(
            sourceIndex = i, action = action, target = null,
            args = emptyMap(), expectAfter = emptySet(),
        )

        check(TracePruner.unreplayable(listOf(st("tap"), st("scroll", 1))).isEmpty(),
            "a trace of plain actions is replayable")
        check(TracePruner.unreplayable(emptyList()).isEmpty(), "an empty step list is not 'unreplayable'")

        val bad = TracePruner.unreplayable(listOf(st("tap"), st(TraceEndpoint.LOOP_STEP, 3), st("tap", 4)))
        check(bad.isNotEmpty(), "a loop_step makes the trace unreplayable")
        check(bad.contains("loop_step"), "the reason names the offending endpoint, got: " + bad)
        check(bad.contains("4"), "the reason carries the 1-based source index, got: " + bad)
    }

    private fun assertTraceArgs() {
        println("== TraceArgs: bridge fields -> replay args ==")

        // 1) /launch 的 package 原样带过来
        check(TraceArgs.of(TraceEndpoint.LAUNCH, mapOf("package" to "com.android.settings")) ==
            mapOf("package" to "com.android.settings"), "launch keeps its target package")

        // 2) 桥允许 "pkg" 别名（gates 的 scr macro 一直传 pkg），翻译后统一成 package
        check(TraceArgs.of(TraceEndpoint.LAUNCH, mapOf("pkg" to "com.tencent.mm")) ==
            mapOf("package" to "com.tencent.mm"), "the pkg alias is normalised to package")

        // 3) 只开链接不记内容：留 kind=uri，绝不把 URL 写进轨迹
        val uriArgs = TraceArgs.of(TraceEndpoint.LAUNCH, mapOf("data" to "https://example.com/a?q=1"))
        check(uriArgs == mapOf("kind" to "uri"), "a uri launch records only the fact, not the link")
        check(uriArgs.values.none { it.contains("example.com") }, "the uri content never lands in the trace")

        // 4) /key 的 "action" → 重放读的 "name"（否则 key 永远按 back 执行）
        check(TraceArgs.of(TraceEndpoint.KEY, mapOf("action" to "home")) == mapOf("name" to "home"),
            "key action is translated to name")

        // 5) /input 只留长度，绝不留文本
        val inArgs = TraceArgs.of(TraceEndpoint.INPUT, mapOf("text" to "hunter2"))
        check(inArgs == mapOf("len" to "7"), "input records the length only, got " + inArgs)
        check(inArgs.values.none { it.contains("hunter2") }, "input text never lands in the trace")

        // 6) /scroll 的方向与距离
        check(TraceArgs.of(TraceEndpoint.SCROLL, mapOf("direction" to "up", "distance" to "600")) ==
            mapOf("direction" to "up", "distance" to "600"), "scroll keeps direction and distance")

        // 7) tap 这类没有可翻译参数的端点 → 空表（别硬塞坐标，坐标在 targetBounds 里）
        check(TraceArgs.of(TraceEndpoint.TAP, mapOf("text" to "设置", "x" to "10", "y" to "20")).isEmpty(),
            "tap carries no args (its target lives in targetText/targetBounds)")

        // 8) 空 body 不炸
        check(TraceArgs.of(TraceEndpoint.LAUNCH, emptyMap()).isEmpty(), "an empty body yields no args")

        // 9) 空白值不算有值（桥的空串字段很常见）
        check(TraceArgs.of(TraceEndpoint.KEY, mapOf("action" to "   ")).isEmpty(),
            "a whitespace-only field is treated as absent")

        // ---- TraceExpect：launch 不设文本后置条件 ----
        // 实测：录制时 launch 的 afterFp 是换包前的桌面，直接当期望会让重放判 verify-failed。
        check(TraceExpect.after(TraceEndpoint.LAUNCH, listOf("Messages", "Chrome")).isEmpty(),
            "a launch step gets no text expectation (its afterFp is the pre-switch screen)")
        check(TraceExpect.after("LAUNCH", listOf("x")).isEmpty(), "the launch rule is case-insensitive")
        check(TraceExpect.after(TraceEndpoint.TAP, listOf("深色主题")) == listOf("深色主题"),
            "other actions keep their expectation")
        check(TraceExpect.after(TraceEndpoint.SCROLL, emptyList()).isEmpty(),
            "an empty expectation stays empty")
    }

    private fun assertMemoryReplay() {
        println("== MemoryReplay v2 anchor precheck ==")
        val pkg = "com.android.settings"
        val fp = setOf("设置", "显示", "深色主题", "pkg:" + pkg)
        val texts = setOf("设置", "显示", "深色主题", "关于手机")

        // 1) 无锚点（空串）→ 放行。放行不等于"可以点"：能不能执行由 run() 按动作类型决定
        check(MemoryReplay.checkAnchor(mstep(target = ""), fp, texts).isEmpty(),
            "a step without an anchor passes checkAnchor (the action type decides later)")

        // 2) 精确命中 → 放行
        check(MemoryReplay.checkAnchor(mstep(target = "深色主题"), fp, texts).isEmpty(),
            "an anchor that is exactly on screen passes")

        // 3) 大小写与首尾空白不算不同（系统语言/滚动截图都会在这上面飘）
        check(MemoryReplay.checkAnchor(mstep(target = "  DARK THEME  "), fp, setOf("dark theme")).isEmpty(),
            "case and surrounding whitespace differences still count as a hit")

        // 4) 找不到 → anchor-mismatch，且**消息里带原文**（路由要能把"少了什么"给模型看）
        check(MemoryReplay.checkAnchor(mstep(target = "蓝牙"), fp, texts) == "anchor-mismatch: 蓝牙",
            "a missing anchor returns anchor-mismatch with the text, got " +
                MemoryReplay.checkAnchor(mstep(target = "蓝牙"), fp, texts))

        // 5) **子串算命中**（口径与 MemoryIndex.anchorHit 一致：锚点是屏幕文本的子串且 >= 2 字）。
        //    理由：界面常把"深色主题"写成"深色主题（跟随系统）"，锚点只是它的前缀。
        //    这里刻意与检索侧同一口径 —— 否则会出现"检索说锚点还在、重放说不在"。
        check(MemoryReplay.checkAnchor(mstep(target = "深色"), fp, setOf("深色主题（跟随系统）")).isEmpty(),
            "a 2+ char anchor contained in a longer screen text counts as a hit")

        // 6) 但**单字锚点不做子串**：中文单字几乎必然命中，那等于没有校验
        check(MemoryReplay.checkAnchor(mstep(target = "开"), fp, setOf("开发者选项")) == "anchor-mismatch: 开",
            "a single-character anchor never matches by substring (that would be no check at all)")
        check(MemoryReplay.checkAnchor(mstep(target = "开"), fp, setOf("开")).isEmpty(),
            "a single-character anchor still matches exactly")

        // 7) 空屏（读屏失败/空页）不等于放行
        check(MemoryReplay.checkAnchor(mstep(target = "深色主题"), fp, emptySet()).startsWith("anchor-mismatch"),
            "an empty screen never passes a step that has an anchor")

        // 8) 只有空白的锚点 == 没有锚点
        check(MemoryReplay.checkAnchor(mstep(target = "   "), fp, texts).isEmpty(),
            "a whitespace-only anchor is treated as no anchor")

        // 9) 指纹相同但锚点不在 → 照样 mismatch（currentFp 不参与放行，这条钉死它）
        check(MemoryReplay.checkAnchor(mstep(target = "蓝牙"), fp, texts).startsWith("anchor-mismatch"),
            "an identical fingerprint does NOT make a missing anchor pass")

        // 10) 子串判定同样忽略大小写
        check(MemoryReplay.checkAnchor(mstep(target = "THEME"), fp, setOf("Dark Theme Settings")).isEmpty(),
            "the substring rule is case-insensitive too")

        // ---- precheck ----
        // 11) 包不符（不同 App 的同名页面不是同一件事）
        check(MemoryReplay.precheck(entryOf(pkg = "com.tencent.mm"), fp, texts, pkg) == "pkg-mismatch",
            "a memory from another package is refused at precheck")

        // 12) 负样本永不重放
        check(MemoryReplay.precheck(entryOf(state = MemoryContract.STATE_DISABLED), fp, texts, pkg) == "disabled",
            "a disabled memory is refused at precheck")

        // 13) 空步骤
        check(MemoryReplay.precheck(entryOf(steps = emptyList()), fp, texts, pkg) == "empty",
            "a memory without steps is refused at precheck")

        // 14) 首步锚点不在当前屏 —— 这一步正是"锚点改坏必须拒绝"的那条门禁
        check(MemoryReplay.precheck(entryOf(steps = listOf(mstep(target = "蓝牙"))), fp, texts, pkg) ==
            "anchor-mismatch", "a broken first-step anchor is refused at precheck")

        // 15) 全通过
        check(MemoryReplay.precheck(entryOf(), fp, texts, pkg).isEmpty(), "a healthy memory passes precheck")

        // 16) 顺序即优先级：包不符优先于 disabled（路由会把 reason 直接展示，别让两条规则互相盖住）
        check(MemoryReplay.precheck(entryOf(pkg = "com.tencent.mm", state = MemoryContract.STATE_DISABLED),
            fp, texts, pkg) == "pkg-mismatch", "pkg-mismatch wins over disabled (the documented order)")

        // 17) 首步没有锚点（launch / key 这类）→ 预检放行，不能因为"没有可校验的东西"就拒绝
        check(MemoryReplay.precheck(entryOf(steps = listOf(mstep(action = "launch"))), fp, texts, pkg).isEmpty(),
            "a first step without an anchor passes precheck")

        // 18) 前台包读不到（空串）→ 判不符：宁可不跑，也不要在不知道自己在哪个 App 时点东西
        check(MemoryReplay.precheck(entryOf(), fp, texts, "") == "pkg-mismatch",
            "an unknown foreground package is treated as a mismatch")

        // ---- 首步 launch 的跨包起跑（实测踩过的坑，见 MemoryRoutes.homePkgOf 的注释）----
        val launchSelf = listOf(mstep(action = "launch", args = mapOf("package" to pkg)))
        // 19) 首步启动的就是 entry.pkg → 从桌面起手也放行（这一步的全部作用就是换包）
        check(MemoryReplay.precheck(entryOf(pkg = pkg, steps = launchSelf), fp, texts, "com.miui.home")
            .isEmpty(), "a launch-first memory may start from another app (the step itself switches)")
        // 20) 但「有 launch 步」不等于放行：它启动的是**别的**包时，仍按包不符拒绝
        check(MemoryReplay.precheck(
            entryOf(pkg = pkg, steps = listOf(mstep(action = "launch", args = mapOf("package" to "com.tencent.mm")))),
            fp, texts, "com.miui.home") == "pkg-mismatch",
            "a launch step into a DIFFERENT package does not unlock a cross-package start")
        // 21) launch 没写 package（只开链接/取默认入口）→ 不算「启动 entry.pkg」，不放行
        check(MemoryReplay.precheck(entryOf(pkg = pkg, steps = listOf(mstep(action = "launch"))),
            fp, texts, "com.miui.home") == "pkg-mismatch",
            "a launch without an explicit package does not unlock a cross-package start")
        // 22) 已经在目标 App 里时照样放行（不能因为"首步是 launch"就变成只能从外面起跑）
        check(MemoryReplay.precheck(entryOf(pkg = pkg, steps = launchSelf), fp, texts, pkg).isEmpty(),
            "a launch-first memory also passes when already inside the target app")

        // ---- checkAfter（后置软校验）----
        // 19) 没有后置条件 → 视为通过（不能因为"没写校验"就记失败）
        check(MemoryReplay.checkAfter(mstep(expect = emptyList()), texts).isEmpty(),
            "a step without expectAfter always passes the soft check")

        // 20) 交集非空 → 通过（判据是"有交集"，不是"全部命中"）
        check(MemoryReplay.checkAfter(mstep(expect = listOf("不存在", "关于手机")), texts).isEmpty(),
            "one matching expectation is enough (intersection, not all-of)")

        // 21) 一条都没有 → 给出人读的缺失清单
        val miss = MemoryReplay.checkAfter(mstep(expect = listOf("蓝牙", "WLAN")), texts)
        check(miss.isNotEmpty() && miss.contains("蓝牙"), "a miss names what was expected, got: " + miss)

        // 22) pkg: 元素按前台包名比，不当成屏幕文本
        check(MemoryReplay.checkAfter(mstep(expect = listOf("pkg:" + pkg)), texts, pkg).isEmpty(),
            "a pkg: expectation is satisfied by the foreground package")
        check(MemoryReplay.checkAfter(mstep(expect = listOf("pkg:com.tencent.mm")), texts, pkg).isNotEmpty(),
            "a pkg: expectation for another package fails")
        check(MemoryReplay.checkAfter(mstep(expect = listOf("pkg:" + pkg)), texts, "").isNotEmpty(),
            "an unknown foreground package cannot satisfy a pkg: expectation")

        // 23) v:/o:（勾选态）剥掉前缀只比文本：开关的勾选态本来就会变
        check(MemoryReplay.checkAfter(mstep(expect = listOf("v:深色主题")), texts).isEmpty(),
            "a checked-state fingerprint compares by text only")
        check(MemoryReplay.checkAfter(mstep(expect = listOf("o:深色主题")), texts).isEmpty(),
            "the unchecked prefix is stripped the same way")
    }

    // ===== stub 与真实无障碍服务的一致性（防"编译过、真机点不动"） =====
    //
    // MemoryReplay.run() 碰的每个 svc 方法在 JVM 这条路上都来自
    // scripts/jvm/DshAccessibilityServiceStub.kt（真实类编不动宿主机 JVM）。
    // stub 一旦和真实服务脱节，编译仍然会过 —— 那是最难查的一类假绿。
    // 所以这里读真实源码，逐个确认方法名还在。
    private fun assertStubMatchesService() {
        println("== MemoryReplay stub vs real accessibility service ==")
        val src = findRepoFile("app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt")
        check(src != null, "the real DshAccessibilityService.kt is locatable from " + System.getProperty("user.dir"))
        val text = src?.readText().orEmpty()
        val used = listOf(
            "dumpScreenCompact", "foregroundPackage", "screenSize", "awaitSettle", "tapText",
            "tapById", "dispatchTapRect", "dispatchSwipeHuman", "dispatchSwipe", "scrollToFind",
            "performGlobalActionByName", "pressEnter", "inputText",
        )
        for (m in used) {
            check(text.contains("fun " + m + "("), "the svc method " + m + " used by MemoryReplay exists in the real service")
        }
    }

    /** 从 user.dir 与类的 code source 两处向上找仓库内文件（与 fixtures 定位同一思路）。 */
    private fun findRepoFile(rel: String): File? {
        val candidates = ArrayList<File>()
        var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (d != null) {
            candidates.add(File(d, rel))
            d = d.parentFile
        }
        runCatching {
            val url = BehaviourTest::class.java.protectionDomain.codeSource.location
            var f: File? = File(url.toURI()).absoluteFile
            while (f != null) {
                candidates.add(File(f, rel))
                f = f.parentFile
            }
        }
        return candidates.firstOrNull { it.isFile }
    }
}
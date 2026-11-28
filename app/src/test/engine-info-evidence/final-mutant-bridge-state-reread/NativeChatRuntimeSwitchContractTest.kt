package app.dsh.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 面板内「运行模式切换」这块宿主代码的静态契约。
 *
 * ## 为什么是静态的
 *
 * 真正的行为（先验 → 落偏好 → 权威回读 → 不一致回滚）已经由
 * [app.dsh.mobile.engine.RuntimeModeAuthorityTest] 用假端口钉住；这里只钉
 * **接线**：通道名对不对、有没有真的走那条权威路径、有没有偷偷重启引擎、
 * 回执是不是在回读之后才发出去。
 *
 * 这几件事恰恰是「改用别的写法」时最先松掉的地方 —— 比如有人为了让面板
 * 立刻看到效果，在 switchRuntimeMode 里顺手调一次 supervisor.restart()，
 * 行为测试仍然是绿的（Authority 没变），而用户的一次点击就变成了静默重启。
 *
 * 与 [NativeChatLaunchHandshakeTest] 同一套做法：读源文件做断言，不需要
 * 设备、不需要 rootfs。
 */
class NativeChatRuntimeSwitchContractTest {

    private val source = File("src/main/java/app/dsh/mobile/NativeChatActivity.kt").readText()

    /** 取一段源码，用来把断言限制在目标函数里，避免注释与相邻代码混进来。 */
    private fun section(start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing section " + start, from >= 0)
        val until = source.indexOf(end, from + start.length)
        assertTrue("missing section end " + end, until > from)
        return source.substring(from, until)
    }

    private val switchBody: String
        get() = section("private fun switchRuntimeMode(", "private fun runtimePort()")

    @Test
    fun theChannelAnswersTheNameFlutterCalls() {
        // Dart 侧 HostBridge.selectRuntimeMode 调的就是这个名字；改一边就是「没有通道」。
        assertTrue(source.contains("\"selectRuntimeMode\" ->"))
        assertTrue(source.contains("switchRuntimeMode(target, result)"))
    }

    @Test
    fun theSwitchGoesThroughTheAuthorityInsteadOfWritingPreferencesItself() {
        assertTrue(
            "必须走 RuntimeModeAuthority.select（回读与回滚都在里面）",
            switchBody.contains("RuntimeModeAuthority.select("),
        )
        // 直接写偏好就意味着「写进去 = 成功」，正是这次要消灭的语义。
        assertFalse(
            "切换实现不得自己落偏好",
            switchBody.contains("setLinuxEnabled("),
        )
    }

    @Test
    fun oneClickNeverRestartsOrStopsTheEngine() {
        // 重启是用户的显式动作（LinuxActivity 里那个带确认框的按钮），
        // 不是点一下模式项的副作用。
        assertFalse("不得在切换里重启引擎", switchBody.contains(".restart("))
        assertFalse("不得在切换里停引擎", switchBody.contains(".stop("))
        assertFalse("不得在切换里提权", switchBody.contains("runPrivileged"))
    }

    @Test
    fun theReceiptIsSentOnlyAfterTheReadback() {
        val receipt = switchBody.indexOf("RuntimeModeAuthority.select(")
        val reply = switchBody.indexOf("result.success(receipt.toWire())")
        assertTrue("回执必须真的发出去", reply > 0)
        assertTrue("回执必须在权威回读之后才发", receipt in 0 until reply)

        // 只有两条路径可以先回：① 并发 busy（什么都不做，如实说在切换中）；
        // ② 回读之后。除此之外任何位置的 result.success 都是「先答应再干活」，
        // 等于把没做的事报成做完了。
        val busyStart = switchBody.indexOf("if (runtimeSwitching)")
        val busyEnd = switchBody.indexOf("return", busyStart)
        assertTrue("必须有并发 busy 分支", busyStart >= 0 && busyEnd > busyStart)
        assertTrue("必须真的入队执行", switchBody.contains("runtimeIo.execute {"))
        var at = switchBody.indexOf("result.success(")
        var replies = 0
        while (at >= 0) {
            replies++
            assertTrue(
                "应答只能出现在 busy 分支或回读之后（位置 " + at + "）",
                at in busyStart until busyEnd || at >= reply,
            )
            at = switchBody.indexOf("result.success(", at + 1)
        }
        // 写死条数：多一处就是新加的「先答应再干活」，少一处就是某条路径没回执。
        assertTrue("切换实现里应恰好有三处应答（busy / 回执 / 未入队），实际 " + replies, replies == 3)
    }

    @Test
    fun aConcurrentClickGetsBusyInsteadOfQueueingASecondSwitch() {
        assertTrue(switchBody.contains("if (runtimeSwitching)"))
        assertTrue("并发第二次必须如实说在切换中", switchBody.contains("\"busy\""))
        // 锁必须在回执送达那一刻解开，否则会留下「永远在切换中」的面板。
        // 注意不能用 section()：它从整个文件里找起点，会命中 draftIo 里那个
        // handler.post。这里只在 switchRuntimeMode 内部切片。
        val posted = switchBody.substring(
            switchBody.indexOf("handler.post {"),
            switchBody.indexOf("result.success(receipt.toWire())"),
        )
        assertTrue(posted.contains("runtimeSwitching = false"))
    }

    @Test
    fun unknownTargetsAreRejectedBeforeAnyStateChanges() {
        val head = section("private fun switchRuntimeMode(", "val previous = currentRuntimeWire()")
        assertTrue(head.contains("Target.fromWire("))
        assertTrue(head.contains("result.error("))
        assertFalse("非法目标不得走到落偏好那一步", head.contains("runtimeIo.execute"))
    }

    @Test
    fun readinessFailureReasonsReachThePanelVerbatim() {
        val port = section("private fun runtimePort()", "private fun engineState(")
        assertTrue(port.contains("Readiness.Ready -> null"))
        assertTrue("未部署要给可操作的短码", port.contains("\"needs-deploy\""))
        assertTrue("缺包/不可用要透出真实原因", port.contains("r.reason"))
    }

    @Test
    fun theRestartPendingFlagIsDerivedAndClearedByAHostReadback() {
        assertTrue(source.contains("\"runtimeRestartPending\" to (pendingRuntimeTarget != null)"))
        // 引擎重新拉起必然换 token：webUrl 变了就说明新偏好已被采用，标记要收掉。
        val info = section("private fun engineInfo(", "private fun switchRuntimeMode(")
        assertTrue(info.contains("webUrl != pendingRuntimeUrl"))
        assertTrue(info.indexOf("pendingRuntimeTarget = null") in 0 until info.indexOf("\"runtimeRestartPending\""))
    }
}

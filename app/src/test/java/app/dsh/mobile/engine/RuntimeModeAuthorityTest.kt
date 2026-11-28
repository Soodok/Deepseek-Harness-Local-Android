package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板内切换运行模式的三条契约。
 *
 * 这三条都不是「跑通一次真好用」能证明的 —— 真机上要造出 rootfs 缺失、
 * 探测期间环境变坏、偏好与判定不一致这些状态，成本是装 250MB rootfs 再撞运气。
 * 而它们恰恰是最容易写错的：把「写进去了」当成功、失败后留下半截偏好、
 * 目标已达成却还去重启引擎。所以用假端口把每条分支钉住。
 *
 * 判据刻意用 **行为**（偏好最终值、回执里的 applied）而不是实现细节：
 * 将来内部改成别的探测方式，只要这三条仍然成立就该是绿的。
 */
class RuntimeModeAuthorityTest {

    /**
     * 可控的假端口。
     *
     * [resolvedLinux] 决定 resolve() 返回什么：切 Linux 时模拟「偏好为真但环境
     * 不就绪」→ 判定回 native_fallback（真机上这确实会发生）。
     */
    private class FakePort(
        var enabled: Boolean = false,
        var resolvedLinux: Boolean = enabled,
        var unavailable: String? = null,
        var reason: String = "",
    ) : RuntimeModeAuthority.Port {
        var writes = 0
        var resolves = 0

        override fun isLinuxEnabled(): Boolean = enabled

        override fun setLinuxEnabled(on: Boolean) {
            enabled = on
            writes++
        }

        override fun resolve(): String {
            resolves++
            return if (enabled && resolvedLinux) "linux"
            else if (enabled) "native_fallback"
            else "native"
        }

        override fun lastReason(): String = reason

        override fun linuxUnavailableReason(): String? = unavailable
    }

    // ------------------------------------------------- 1. 环境不就绪：一个字节都不该写

    @Test
    fun requestingLinuxWithoutReadyEnvironmentLeavesPreferenceUntouched() {
        val port = FakePort(enabled = false, resolvedLinux = false, unavailable = "needs-deploy")
        val receipt = RuntimeModeAuthority.select(
            RuntimeModeAuthority.Target.LINUX,
            port,
            previous = "native",
        )
        assertFalse("环境不就绪时必须如实报失败", receipt.ok)
        assertEquals(0, port.writes)
        assertFalse("失败后偏好必须还是原值", port.enabled)
        assertEquals("native", receipt.applied)
        assertEquals("needs-deploy", receipt.reason)
        assertFalse(receipt.restartRequired)
    }

    // ------------------------------------------------- 2. 判定与目标不一致：回滚偏好

    @Test
    fun resolutionMismatchRollsBackAndReportsFailure() {
        // 环境探测说就绪，但落偏好后 resolve() 给了 native_fallback ——
        // 这正是「探测与探测之间环境变坏」那种罕见但真实的情况。
        // 注意 lastReason() 在回滚后会变成另一句话，与真实实现一致
        // （EngineModeSelector 回滚那次 resolve 会写「linux mode disabled」）——
        // 就是为了让「原因是否在回滚前取」这件事有判别力：
        // 若实现先回滚再读原因，断言拿到的是后者，测试会红。
        val flaky = object : RuntimeModeAuthority.Port {
            var enabled = false
            var resolveCalls = 0
            override fun isLinuxEnabled() = enabled
            override fun setLinuxEnabled(on: Boolean) { enabled = on }
            override fun resolve(): String {
                resolveCalls++
                // 偏好为真时判回退；回滚后再判定就回到 native。
                // 两次判定都记录下来，确保回滚确实被执行了。
                return if (enabled) "native_fallback" else "native"
            }

            override fun lastReason(): String =
                if (enabled) "linux rootfs not deployed" else "linux mode disabled"

            override fun linuxUnavailableReason(): String? = null
        }
        val receipt = RuntimeModeAuthority.select(
            RuntimeModeAuthority.Target.LINUX,
            flaky,
            previous = "native",
        )
        assertFalse("回读不等于目标就不能报成功", receipt.ok)
        assertFalse("必须把偏好回滚成原值", flaky.enabled)
        assertEquals("native", receipt.applied)
        assertEquals("linux rootfs not deployed", receipt.reason)
        assertEquals("两次判定：一次回读 + 一次回滚后确认", 2, flaky.resolveCalls)
    }

    // ------------------------------------------------- 3. 幂等：已经是该模式就不写、不重启

    @Test
    fun selectingTheCurrentModeIsANoOp() {
        val port = FakePort(enabled = false, resolvedLinux = false)
        val receipt = RuntimeModeAuthority.select(
            RuntimeModeAuthority.Target.NATIVE,
            port,
            previous = "native",
        )
        assertTrue(receipt.ok)
        assertEquals(0, port.writes)
        assertEquals(0, port.resolves)
        assertFalse("什么都没改，不该提示重启", receipt.restartRequired)
    }

    @Test
    fun switchingToLinuxWritesAndReadsBack() {
        val port = FakePort(enabled = false, resolvedLinux = true)
        val receipt = RuntimeModeAuthority.select(
            RuntimeModeAuthority.Target.LINUX,
            port,
            previous = "native",
        )
        assertTrue(receipt.ok)
        assertEquals(1, port.writes)
        assertTrue(port.enabled)
        assertEquals("linux", receipt.applied)
        assertEquals("native", receipt.previous)
        assertTrue("引擎在跑，切完必须提示重启才生效", receipt.restartRequired)
    }

    @Test
    fun reSelectingTheSamePreferenceRecomputesWithoutRewriting() {
        // 用户偏好本来就是「原生」，但上次判定留在 native_fallback（启动前
        // 环境坏过一瞬）。点「Bionic Node」应当只重算、不写偏好、不提示重启。
        val port = FakePort(enabled = false, resolvedLinux = false)
        val receipt = RuntimeModeAuthority.select(
            RuntimeModeAuthority.Target.NATIVE,
            port,
            previous = "native_fallback",
        )
        assertTrue("重算后判定与目标一致就该报成功", receipt.ok)
        assertEquals(0, port.writes)
        assertEquals(1, port.resolves)
        assertEquals("native", receipt.applied)
        assertFalse("偏好没改过，没有需要重启才生效的东西", receipt.restartRequired)
    }

    // ------------------------------------------------- 4. 值域：未知目标不能被当成合法输入

    @Test
    fun unknownWireValuesAreRejected() {
        assertEquals(RuntimeModeAuthority.Target.NATIVE, RuntimeModeAuthority.Target.fromWire("native"))
        assertEquals(
            "native_fallback 是「当前判定」，面板点原生时应归一到 native",
            RuntimeModeAuthority.Target.NATIVE,
            RuntimeModeAuthority.Target.fromWire("native_fallback"),
        )
        assertEquals(RuntimeModeAuthority.Target.LINUX, RuntimeModeAuthority.Target.fromWire("linux"))
        assertEquals(null, RuntimeModeAuthority.Target.fromWire("ssh_windows"))
        assertEquals(null, RuntimeModeAuthority.Target.fromWire(""))
        assertEquals(null, RuntimeModeAuthority.Target.fromWire(null))
    }
}

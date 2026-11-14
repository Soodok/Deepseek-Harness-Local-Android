package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按钮摘要的真实输出测试（v1.7.31）。
 *
 * ## 为什么必须存在
 *
 * 2026-11-14 用户发来的截图里，官方插件页那个失败按钮上**只有一串路径**：
 *     失败：file:///data/user/0/app.dsh.mobile/files/engine/lib/node_mod...
 * 这行字对用户和我们都没有信息量 —— 真正的原因在下一行，被摘要逻辑吃掉了。
 * py 探针只能检查「源码里有没有 file:// 过滤」，证明不了「同一段原文进来，
 * 出来的是错误码而不是路径」。所以这里直接喂真实的 Node / pnpm 原文。
 *
 * 这些断言全部跑真实 Kotlin 实现，不依赖 Android API。
 */
class InstallErrorSummaryTest {

    // ---------------------------------------------------- 本次截图对应的形态

    @Test
    fun nodeEsmStackHeadIsNotUsedAsSummary() {
        // Node 对未被捕获的 ESM 异常：先打模块 URL，再打错误
        val raw = listOf(
            "file:///data/user/0/app.dsh.mobile/files/engine/lib/node_modules/corepack/dist/pnpm.js:4127",
            "      throw new Error(`Cannot find package 'x'`);",
            "      ^",
            "",
            "Error: Cannot find package 'x'",
            "    at Module._resolveFilename (node:internal/modules/esm/resolve:1:1)",
        ).joinToString("\n")
        val shown = InstallErrorSummary.summarize(raw)
        assertFalse("按钮上不能只剩路径：" + shown, shown.startsWith("file://"))
        assertTrue("要给出可读原因：" + shown, shown.contains("Cannot find package"))
    }

    @Test
    fun pnpmLifecycleFailureUsesItsOwnErrorLine() {
        val raw = listOf(
            "file:///data/user/0/app.dsh.mobile/files/dsh-home/profiles/web/node_modules/x/postinstall.js:1",
            "throw new Error(\"-d\");",
            "^",
            "",
            "Error: -d",
            "    at file:///data/user/0/app.dsh.mobile/files/dsh-home/profiles/web/node_modules/x/postinstall.js:1:7",
        ).joinToString("\n")
        val shown = InstallErrorSummary.summarize(raw)
        assertFalse("不能是路径：" + shown, shown.startsWith("file://"))
        assertTrue("要带上真正的原因：" + shown, shown.contains("-d"))
    }

    // ---------------------------------------------------- 既有形态：不能被这次改动挤掉

    @Test
    fun pnpmErrorCodeStillWins() {
        val raw = listOf(
            "Error: ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY",
            "  × adding a new package",
            "  ╰─▶ Broken lockfile: no entry for 'dshmarket@1.73.19' in pnpm-lock.yaml",
            "help: To fix the lockfile, run 'pnpm install --fix-lockfile'.",
        ).joinToString("\n")
        assertEquals("ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY", InstallErrorSummary.summarize(raw))
    }

    @Test
    fun upstreamDshDiagnosticLineWinsOverCauseChain() {
        val raw = listOf(
            "Error: something went wrong",
            "  ╰─▶ lower level cause",
            "dsh: plugin 'x' requires dsh >=0.3.0",
        ).joinToString("\n")
        assertEquals("dsh: plugin 'x' requires dsh >=0.3.0", InstallErrorSummary.summarize(raw))
    }

    @Test
    fun causeChainTakesLastNode() {
        val raw = listOf(
            "Error: install failed",
            "  ╰─▶ outer",
            "  ╰─▶ the real cause",
        ).joinToString("\n")
        assertEquals("the real cause", InstallErrorSummary.summarize(raw))
    }

    @Test
    fun emptyOutputGivesUnknown() {
        assertEquals("unknown", InstallErrorSummary.summarize(""))
        assertEquals("unknown", InstallErrorSummary.summarize("   \n\n  "))
    }

    // ---------------------------------------------------- 过滤必须精准，不能把线索删掉

    @Test
    fun realErrorLineMentioningFileUrlSurvives() {
        // 关键反例：真正的错误行里同样会出现 file://，删掉就丢了线索
        val raw = "Cannot find module 'file:///data/user/0/app.dsh.mobile/files/x/index.js'"
        assertEquals(raw, InstallErrorSummary.stripLocationLines(raw))
    }

    @Test
    fun pureLocationLineDroppedButPlainLineKept() {
        val raw = listOf("file:///a/b/c.js:12", "keep me").joinToString("\n")
        assertEquals("keep me", InstallErrorSummary.stripLocationLines(raw))
    }

    @Test
    fun errorLineWithFileUrlNotDroppedEvenWhenOtherLinesRemain() {
        // ⚠️ 这条是为了防「假绿」而写的：单行输入下，若过滤判据放宽到
        // s.contains("file://")，错误行会被剔掉 → 结果为空 → 触发 ifBlank
        // 回退原文 → 上一条测试照样通过。加一行普通文本后，回退不再发生，
        // 判据放宽就必然可见。
        val raw = listOf(
            "Cannot find module 'file:///data/user/0/app.dsh.mobile/files/x/index.js'",
            "note: run with --trace to see more",
        ).joinToString("\n")
        assertEquals(raw, InstallErrorSummary.stripLocationLines(raw))
    }

    @Test
    fun allLocationLinesFallBackToOriginal() {
        val raw = "file:///a/b/c.js:12"
        assertEquals(raw, InstallErrorSummary.stripLocationLines(raw))
    }
}

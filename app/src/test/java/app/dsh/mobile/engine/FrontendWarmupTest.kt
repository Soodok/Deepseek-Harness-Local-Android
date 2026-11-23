package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前端语法高亮预热的空闲化改写（v1.6.0-beta.18）。
 *
 * ## 为什么断言必须落在「改写后的字符串」而不是源码
 *
 * 预热调用是打包产物里的压缩代码，函数名每次构建都变（实测到 `Ji` / `Mi` / `Xi`），
 * 所以补丁只能按结构匹配。一个「正则能匹配」的断言会假绿：匹配到但改写把括号写坏了
 * 照样通过。所以这里断言三件事——原文案被替换、`requestIdleCallback` 出现、且
 * 括号配平与语法仍成立。
 *
 * ## 实测依据（emulator-5554 / x86_64 / Android 14 / 8 GB AVD，2026-11-19）
 *
 * `nB()` 内部拆段计时：createHighlighter 3~21 ms，typescript 预热 tokenize
 * 494~662 ms，shellscript 121~153 ms，json 3~6 ms。预热总计 641~843 ms 落在
 * load 窗口内。改为空闲执行后，应用自身 webview-first-paint 与引擎就绪的差值三轮
 * 中位 4629 ms → 3311 ms（省 28.5%，三轮全胜）。
 */
class FrontendWarmupTest {

    /** 与打包器实际输出同形：把 unref 惯用法连临时变量一起写出。 */
    private val unrefSource =
        "function foo(){}" +
            "(sk=(ak=setTimeout(()=>{Xi()},0)).unref)==null||sk.call(ak);" +
            "function bar(){}"

    /** 另一个构建只留箭头形 + 可选 unref 调用。 */
    private val plainSource =
        "function foo(){}setTimeout(()=>{Ji()},0).unref?.();function bar(){}"

    @Test
    fun unrefFormIsRewrittenToIdleCallback() {
        val out = FrontendWarmup.patchBundle(unrefSource)
        assertNotEquals("必须发生改写", unrefSource, out)
        assertTrue("必须走 requestIdleCallback", out.contains("requestIdleCallback"))
        assertFalse("原始预热调用不得残留", out.contains("setTimeout(()=>{Xi()},0)"))
        assertTrue("预热函数仍须被调用", out.contains("Xi()"))
        assertTrue("unref 惯用法的临时变量必须一并去掉", !out.contains("sk.call(ak)"))
    }

    @Test
    fun plainFormIsRewrittenToIdleCallback() {
        val out = FrontendWarmup.patchBundle(plainSource)
        assertNotEquals("必须发生改写", plainSource, out)
        assertTrue("必须走 requestIdleCallback", out.contains("requestIdleCallback"))
        assertTrue("预热函数仍须被调用", out.contains("Ji()"))
    }

    /**
     * 关键回归：unref 形包含裸 `setTimeout(()=>{FN()},0)` 子串。
     * 若先跑 plain 规则，会把内层调用改掉、外层 `(...).unref)==null||sk.call(ak)`
     * 失去被调函数而变成语法错误。这里断言改写结果括号配平。
     */
    @Test
    fun unrefFormStaysBalancedAfterPatch() {
        val out = FrontendWarmup.patchBundle(unrefSource)
        var depth = 0
        var minDepth = 0
        for (ch in out) {
            when (ch) {
                '{', '(' -> depth += 1
                '}', ')' -> {
                    depth -= 1
                    if (depth < minDepth) minDepth = depth
                }
            }
        }
        assertEquals("括号必须配平", 0, depth)
        assertEquals("不得出现提前闭合", 0, minDepth)
    }

    @Test
    fun alreadyPatchedSourceIsUntouched() {
        val once = FrontendWarmup.patchBundle(unrefSource)
        val twice = FrontendWarmup.patchBundle(once)
        assertEquals("重复施加必须幂等", once, twice)
    }

    @Test
    fun sourceWithoutWarmupIsReturnedVerbatim() {
        val unrelated = "const a=1;function render(){return a}"
        assertEquals("没有预热调用时不得插入补丁", unrelated, FrontendWarmup.patchBundle(unrelated))
    }

    /**
     * 函数名单字符/多字符都要能取到——压缩器会按出现频次分配名字长度。
     * 取错组号会把半截变量名当函数名，调用点就指向不存在的符号。
     */
    @Test
    fun minifiedFunctionNameIsExtractedFromAnyLength() {
        val short = "(a=(b=setTimeout(()=>{Q()},0)).unref)==null||a.call(b);"
        val out = FrontendWarmup.patchBundle(short)
        assertTrue("短名必须被正确取出", out.contains("Q()"))
        assertFalse("不得把临时变量当函数名", out.contains("{b()}"))
        assertFalse("不得把临时变量当函数名", out.contains("{a()}"))
    }
}

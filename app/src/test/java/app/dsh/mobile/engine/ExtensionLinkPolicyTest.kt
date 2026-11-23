package app.dsh.mobile.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ExtensionLinkPolicy.isInExtensionRoot] 的行为断言 —— 直接跑真实实现。
 *
 * ## 这些用例为什么必须存在
 * 这段判定守的是「软链不许指到扩展根之外」（防包/索引被篡改后任意落地链接）。
 * 它出过一次方向性错误：相对 target 被当成「相对于扩展根」解析，而 POSIX 语义是
 * **相对于链接所在目录**。后果不是理论风险 —— deb 里最常见的入口软链全是上级
 * 相对形态，于是：
 *
 *   zig   bin/zig        -> ../lib/zig/zig       （负载 171MB 完好）
 *   golang bin/go        -> ../lib/go/bin/go
 *   dex2jar bin/d2j-dex2jar -> ../opt/dex2jar/d2j-dex2jar.sh
 *
 * 全被判越界、静默跳过，随后主程序存在性校验报
 * 「none of the executables declared by Zig (zig) were installed」。
 *
 * ## 反假绿设计
 * 只测「恶意 target 被拒」会放过一个恒返回 false 实现（那样正确链接也建不出，
 * 正是这次的真实故障）；只测「合法 target 放行」会放过一个恒返回 true 实现
 * （守卫形同虚设）。所以三组都必须在场：
 *   1. 合法上级相对链 → 放行（旧实现的失败点）
 *   2. 真越界（../ 叠加、绝对路径根外）→ 拒绝（恒 true 实现的失败点）
 *   3. 目标尚不存在（悬空链）→ 仍判为「在根内」，由调用方决定是否落地
 */
class ExtensionLinkPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File = tmp.newFolder("zig-ext")

    // ------------------------------------------------ 1. 合法上级相对链必须放行

    @Test
    fun `parent-relative target resolves from link directory, not extension root`() {
        // 这是 zig/golang/dex2jar 的真实形态：bin/x -> ../lib/x/x
        val r = root()
        assertTrue(
            "bin/zig -> ../lib/zig/zig 是扩展根内的合法链接",
            ExtensionLinkPolicy.isInExtensionRoot(r, "bin/zig", "../lib/zig/zig"),
        )
    }

    @Test
    fun `two-level parent-relative target stays inside root`() {
        // fzf / sox 等包的 share 下版权链：share/doc/x/copyright -> ../../LICENSES/GPL-2.0.txt
        val r = root()
        assertTrue(
            ExtensionLinkPolicy.isInExtensionRoot(
                r, "share/doc/sox/copyright", "../../LICENSES/GPL-2.0.txt",
            ),
        )
    }

    @Test
    fun `sibling target without any parent segment is allowed`() {
        // bzip2 包：bin/bunzip2 -> bzip2
        val r = root()
        assertTrue(ExtensionLinkPolicy.isInExtensionRoot(r, "bin/bunzip2", "bzip2"))
    }

    @Test
    fun `absolute target already inside root is allowed`() {
        val r = root()
        assertTrue(
            ExtensionLinkPolicy.isInExtensionRoot(
                r, "bin/sh", r.absolutePath + "/bin/bash",
            ),
        )
    }

    // ------------------------------------------------ 2. 真越界必须拒绝

    @Test
    fun `target climbing out of root is rejected`() {
        // bin/x -> ../../../../etc/passwd：链接父目录是 <ext>/bin，退四级已在根外
        val r = root()
        assertFalse(
            ExtensionLinkPolicy.isInExtensionRoot(r, "bin/x", "../../../../../../etc/passwd"),
        )
    }

    @Test
    fun `single parent segment escaping root is rejected`() {
        // 根级链接再用 .. 就直接出去了（旧实现的判据恰好在这里是对的，
        // 它错在把相对基准一律当成根，而不是「链接父目录」）
        val r = root()
        assertFalse(ExtensionLinkPolicy.isInExtensionRoot(r, "x", "../outside"))
    }

    @Test
    fun `absolute target outside root is rejected`() {
        val r = root()
        assertFalse(
            ExtensionLinkPolicy.isInExtensionRoot(r, "bin/env", "/system/bin/env"),
        )
    }

    @Test
    fun `prefix-similar sibling directory is rejected`() {
        // 前缀相似但不同目录：<ext> 与 <ext>-evil 是两棵不同的树，
        // 单纯 startsWith 字符串比较会把它们混为一谈（这里顺带钉住该边界）
        val r = root()
        val evil = File(r.parentFile, r.name + "-evil/bin/bash")
        assertFalse(
            ExtensionLinkPolicy.isInExtensionRoot(r, "bin/bad", evil.absolutePath),
        )
    }

    // ------------------------------------------------ 3. 悬空但未越界

    @Test
    fun `dangling target inside root is still considered inside`() {
        // 目标文件此刻不存在（解包顺序 / 可选依赖），但落点仍在根内。
        // 是否落地交给调用方，判定本身只看路径归属。
        val r = root()
        assertTrue(
            ExtensionLinkPolicy.isInExtensionRoot(r, "bin/missing", "../lib/not-yet-unpacked"),
        )
    }

    @Test
    fun `same target is judged by link depth, proving parent-relative semantics`() {
        // 同一条 target 字符串在两个不同深度的链接下结果必须不同：
        //   bin/zig（父目录 <ext>/bin）+ ../../lib → <ext>/../lib → 根外
        //   share/doc/x/foo（父目录 <ext>/share/doc/x）+ ../../lib → <ext>/share/lib → 根内
        // 只有当基准是「链接父目录」时才会出现这种差异；以扩展根为基准会两个都拒。
        val r = root()
        assertFalse(
            ExtensionLinkPolicy.isInExtensionRoot(r, "bin/zig", "../../lib/zig/zig"),
        )
        assertTrue(
            ExtensionLinkPolicy.isInExtensionRoot(
                r, "share/doc/x/foo", "../../lib/zig/zig",
            ),
        )
    }
}

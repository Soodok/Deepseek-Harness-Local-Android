package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * [ExtensionLinkRepair] / [ExtensionBinAliases] 的行为断言 —— 跑真实实现。
 *
 * ## 覆盖的三件事
 * 1. **清单编解码**：安装期写入的 manifest 是存量修复的唯一凭据，
 *    编码/解码必须能往返（含制表符与相对路径里的空格）。
 * 2. **按已知布局补链**：zig/golang/dex2jar 三种上游布局各一条；
 *    目标不存在时不建（不产出悬空链），越界候选直接拒。
 * 3. **binutils 别名**：目录声明 ar/nm/objdump/strip/ld，上游叫
 *    gar/gnm/gobjdump/gstrip/ld.bfd。恒不建 → issue #116 修不好；
 *    无条件建 → 会把已有真文件覆盖/把不存在目标建成悬空链。
 */
class ExtensionLinkRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ext(id: String = "zig"): File = tmp.newFolder(id).apply { File(this, "bin").mkdirs() }

    private fun touch(dir: File, rel: String, exec: Boolean = true): File =
        File(dir, rel).apply {
            parentFile?.mkdirs()
            writeText("x")
            if (exec) setExecutable(true, false)
        }

    private fun exists(extDir: File, name: String) = File(extDir, "bin/$name").exists()

    // ------------------------------------------------------- 1. 清单编解码

    @Test
    fun `manifest line round-trips`() {
        val line = ExtensionLinkRepair.encodeLink("bin/zig", "../lib/zig/zig", true)
        val decoded = ExtensionLinkRepair.decodeLink(line)
        assertNotNull(decoded)
        assertEquals("bin/zig", decoded!!.first)
        assertEquals("../lib/zig/zig", decoded.second)
        assertTrue(decoded.third)
    }

    @Test
    fun `hard link kind is preserved`() {
        val decoded = ExtensionLinkRepair.decodeLink(
            ExtensionLinkRepair.encodeLink("bin/x", "lib/x", false),
        )
        assertNotNull(decoded)
        assertFalse(decoded!!.third)
    }

    @Test
    fun `malformed manifest lines are rejected rather than half-applied`() {
        assertNull(ExtensionLinkRepair.decodeLink("bin/zig"))
        assertNull(ExtensionLinkRepair.decodeLink("bin/zig\t../lib/zig/zig"))
        assertNull(ExtensionLinkRepair.decodeLink("\t../x\ts"))
        assertNull(ExtensionLinkRepair.decodeLink("bin/x\t\ts"))
    }

    // ------------------------------------------------------- 2. 上游已知布局

    @Test
    fun `zig layout lib-name-name is healed`() {
        val d = ext("zig")
        touch(d, "lib/zig/zig")
        val healed = ExtensionLinkRepair.pendingRepairs(d, listOf("zig")) { e, n -> exists(e, n) }
        assertEquals(listOf("zig" to "lib/zig/zig"), healed)
    }

    @Test
    fun `golang layout lib-name-bin-name is healed`() {
        val d = ext("golang")
        touch(d, "lib/go/bin/go")
        val healed = ExtensionLinkRepair.pendingRepairs(d, listOf("go")) { e, n -> exists(e, n) }
        assertEquals(listOf("go" to "lib/go/bin/go"), healed)
    }

    @Test
    fun `dex2jar layout opt-name-script is healed for d2j prefixed name`() {
        val d = ext("apk-reverse")
        touch(d, "opt/dex2jar/d2j-dex2jar.sh")
        val healed = ExtensionLinkRepair.pendingRepairs(d, listOf("d2j-dex2jar")) { e, n -> exists(e, n) }
        assertEquals(listOf("d2j-dex2jar" to "opt/dex2jar/d2j-dex2jar.sh"), healed)
    }

    @Test
    fun `no candidate on disk means no dangling link is created`() {
        val d = ext("zig")
        val healed = ExtensionLinkRepair.pendingRepairs(d, listOf("zig")) { e, n -> exists(e, n) }
        assertTrue(healed.isEmpty())
        assertFalse("不许建成悬空链", Files.isSymbolicLink(File(d, "bin/zig").toPath()))
    }

    @Test
    fun `already present bin is left alone`() {
        val d = ext("zig")
        touch(d, "lib/zig/zig")
        touch(d, "bin/zig")   // 真的已在（例如手装过）
        val healed = ExtensionLinkRepair.pendingRepairs(d, listOf("zig")) { e, n -> exists(e, n) }
        assertTrue(healed.isEmpty())
    }

    // ------------------------------------------------------- 3. binutils 别名

    @Test
    fun `binutils aliases map declared names onto g-prefixed upstream names`() {
        val d = ext("binutils")
        listOf("ld.bfd", "gar", "gnm", "gobjdump", "gstrip").forEach { touch(d, "bin/$it") }
        val pending = ExtensionBinAliases.pendingAliases("binutils", d).toMap()
        // 目录声明的是这五个名字（issue #116 里被报成 "Main executables missing (5)"）
        assertEquals("ld.bfd", pending["ld"])
        assertEquals("gar", pending["ar"])
        assertEquals("gnm", pending["nm"])
        assertEquals("gobjdump", pending["objdump"])
        assertEquals("gstrip", pending["strip"])
    }

    @Test
    fun `binutils alias skips missing upstream targets`() {
        val d = ext("binutils")
        touch(d, "bin/gar")             // 只有 ar 的上游
        val pending = ExtensionBinAliases.pendingAliases("binutils", d).toMap()
        assertEquals("gar", pending["ar"])
        assertFalse("gnm 不在盘上，不许把它排进待建列表", "nm" in pending)
    }

    @Test
    fun `existing alias is not replaced`() {
        val d = ext("binutils")
        touch(d, "bin/gar")
        touch(d, "bin/ar")              // 别名已存在（真文件或旧链）
        val pending = ExtensionBinAliases.pendingAliases("binutils", d).toMap()
        assertFalse("已存在的入口不许覆盖", "ar" in pending)
    }

    @Test
    fun `unknown extension gets no aliases`() {
        val d = ext("nasm")
        assertTrue(ExtensionBinAliases.pendingAliases("nasm", d).isEmpty())
        assertTrue(ExtensionBinAliases.aliasesOf("nasm").isEmpty())
    }
}
package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3080 清场脚本的渲染断言（v1.6.0-beta.18）。
 *
 * ## 为什么必须断言**渲染后的文本**
 *
 * 旧实现的脚本内联在 EngineSupervisor 的 raw string 里，awk 字段引用写成了
 * 「反斜杠 + 4」。raw string 里反斜杠是字面字符，渲染结果于是带着反斜杠、awk
 * 直接 syntax error，脚本却仍然 exit 0（末尾 true）—— 一个进程都没杀。
 *
 * 这类缺陷用「源码里出现过目标片段」的断言抓不到：源码里确实有，只是前面多了一个
 * 反斜杠。所以断言必须落在 script() 的**返回值**上。
 */
class PortReclaimTest {

    private val script = PortReclaim.script(3080)

    /** 渲染结果里不得出现任何「反斜杠 + 美元符」：那会让 awk / sh 看到转义字符。 */
    @Test
    fun renderedScriptHasNoEscapedDollars() {
        assertFalse(
            "rendered script must not contain a backslash-dollared field reference",
            script.contains("\\" + "$"),
        )
    }

    /** awk 的字段引用必须是裸的列引用（4 / 2 / 10）。 */
    @Test
    fun awkFieldReferencesAreBare() {
        assertTrue("awk row filter must read the state column", script.contains("$4 == \"0A\""));
        assertTrue("awk must split the local address column", script.contains("split(" + "$" + "2,a,\":\")"));
        assertTrue("awk must print the inode column", script.contains("print " + "$" + "10"));
    }

    /** 端口要真的参与换算（printf %04X + 端口字面量），而不是写死一个十六进制常量。 */
    @Test
    fun portIsInterpolated() {
        assertTrue("script must convert the port to hex", script.contains("printf '%04X' 3080"));
        assertTrue("hex value must be derived at runtime", script.contains("PORT_HEX"));
    }

    /** 只按 LISTEN 状态找持有者：读 /proc/net/tcp{,6} + 反查 socket inode，不按 cmdline 猜。 */
    @Test
    fun matchesListenersViaProcAndInode() {
        assertTrue(script.contains("/proc/net/tcp"));
        assertTrue(script.contains("/proc/net/tcp6"));
        assertTrue("must resolve socket inodes from /proc/<pid>/fd", script.contains("socket:"));
        assertTrue("must kill the holder", script.contains("kill -9 "));
        assertFalse("must not match by cmdline", script.contains("engine/bin/node"));
    }

    /** 脚本退出码恒为 0（末尾 true）——所以「没报错」不能当作「杀掉了」的证据。 */
    @Test
    fun scriptEndsWithTrueSoItNeverSurfacesFailures() {
        val lines = script.lines()
        assertEquals("last line must be a bare true", "true", lines.last().trim())
        assertFalse("must not leave a trailing newline", script.endsWith("\n"))
    }

    /** 渲染结果不能夹带 CJK（那是 shell 文本，中文只允许出现在 Kotlin 注释里）。 */
    @Test
    fun renderedScriptHasNoCjk() {
        assertFalse("shell text must stay ASCII", script.contains(Regex("[\\u4e00-\\u9fff]")));
    }

    /**
     * 兜底脚本里的 NUL 转义必须**恰好一个反斜杠**。
     *
     * tr 只认单反斜杠的 `\0`；Kotlin raw string 里写成两个反斜杠，交给 sh 的是 `\\0`，
     * 被当成字面「反斜杠 0」替换，cmdline 里的 NUL 不会被换成分隔符，`case` 于是永远匹配
     * 不到引擎路径 —— 又一个「跑完 exit 0 但什么都没做」。
     */
    @Test
    fun cmdlineSweepUsesSingleBackslashNul() {
        val sweep = PortReclaim.cmdlineSweepScript("/data/user/0/x/files/engine", "/data/user/0/x/files/dsh-home")
        assertTrue(
            "tr must receive exactly one backslash-NUL",
            sweep.contains("tr '" + "\\" + "0' "),
        )
        assertFalse("two backslashes " + "are" + " a literal, not NUL", sweep.contains("tr '" + "\\\\" + "0' "))
    }

    /**
     * readlink 给出的是 `socket:[967201]`，/proc/net/tcp 的 inode 列是裸的 `967201`。
     * 只 `''${ino%]}''` 会留下前导 `[`，`case " $INODES " in *" $ino "*)` 永远匹配不上 ——
     * 第三个「跑完 exit 0 却什么都没杀」。两端都必须剥。
     */
    @Test
    fun socketInodeIsStrippedOnBothSides() {
        val d = "$"
        assertTrue("must drop the leading bracket", script.contains("ino=" + d + "{ino#[}"))
        assertTrue("must drop the trailing bracket", script.contains("ino=" + d + "{ino%]}"))
    }
}

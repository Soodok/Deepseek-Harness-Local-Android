package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 「包装了但文件不可用」的判据测试（2026-11-14 真机）。
 *
 * ## 为什么要单测而不是只靠真机
 *
 * 真机复现一次要装 250MB 的 build-base、还要等 `apk add` 跑几十秒，
 * 而且**修好之后就不再复现**。判据本身（「这个文件该不该有 x 位」）
 * 是纯字符串/字节判断，完全不依赖 Android API —— 可以用真实字节在
 * 毫秒级验完，每次回归都跑。
 *
 * 这里的断言全部喂**真实文件头**（真 ELF 魔数、真 `#!`），不是构造的
 * 近似值：判据宽度只要比真实数据宽一点，就会把数据文件改成可执行。
 */
class RootfsModesTest {

    // ---------------------------------------------------- 形态 1：真机那三个文件

    @Test
    fun elfWithoutExecBitNeedsIt() {
        // /usr/bin/gcc 在真机上是 `-rw-r--r--` 的 ELF
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        assertTrue(RootfsModes.isElf(elf))
        assertTrue(RootfsModes.needsExecBit(elf, hasExecBit = false))
    }

    @Test
    fun elfWithExecBitIsLeftAlone() {
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        assertFalse(
            "已经有 x 位就不用再碰 —— 修复必须幂等，否则每次装包都改一遍 mtime",
            RootfsModes.needsExecBit(elf, hasExecBit = true),
        )
    }

    @Test
    fun shebangScriptWithoutExecBitNeedsIt() {
        val sh = "#! /bin/sh\n".toByteArray()
        assertTrue(RootfsModes.isShebang(sh))
        assertTrue(RootfsModes.needsExecBit(sh, hasExecBit = false))
    }

    // ---------------------------------------------------- 形态 2：绝不能被误伤

    @Test
    fun plainTextIsNeverMadeExecutable() {
        // /usr/share 下的文档、/usr/bin 下偶尔的纯数据文件都在这一档。
        // 把它改成可执行是**新的损坏**，所以判据必须窄。
        val text = "This is a license file.\n".toByteArray()
        assertFalse(RootfsModes.isElf(text))
        assertFalse(RootfsModes.isShebang(text))
        assertFalse(RootfsModes.needsExecBit(text, hasExecBit = false))
    }

    @Test
    fun emptyOrShortHeadIsNotExecutable() {
        // 读不到内容（EPERM）时 readHead 返回空数组 —— 此时不能猜
        for (head in listOf(ByteArray(0), byteArrayOf(0x7F), byteArrayOf(0x7F, 'E'.code.toByte()))) {
            assertFalse(RootfsModes.needsExecBit(head, hasExecBit = false))
        }
    }

    @Test
    fun elfMagicMustBeExactPrefix() {
        // ELF 魔数只有前 4 字节是确定的，后面是 class/endianness/version。
        // 判据只看前 4 字节，所以补什么尾巴都该认。
        val real = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0)
        assertTrue(RootfsModes.isElf(real))
        // 而第 4 字节不是 'F' 就不是 ELF（例如 Mach-O 或任意二进制）
        val notElf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'G'.code.toByte())
        assertFalse(RootfsModes.isElf(notElf))
    }

    // ---------------------------------------------------- 真目录：扫描行为

    @Test
    fun findMissingExecBitWalksRealDirectory() {
        val root = Files.createTempDirectory("rootfs-modes").toFile()
        try {
            val bin = File(root, "usr/bin").apply { mkdirs() }
            // ① 缺 x 位的 ELF —— 该被修
            val gcc = File(bin, "gcc")
            gcc.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 0))
            // ② 缺 x 位的脚本 —— 该被修
            val script = File(bin, "script.sh")
            script.writeText("#! /bin/sh\necho hi\n")
            // ③ 纯数据文件 —— 绝不能碰
            val data = File(bin, "NOTICE")
            data.writeText("license text\n")
            // ④ 已有 x 位 —— 不用管
            val already = File(bin, "node")
            already.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            already.setExecutable(true, false)

            // ★hasExec 必须注入，不能依赖真实 canExecute()：
            //   Windows 上 `File.canExecute()` **恒为 true**（NTFS 没有 POSIX x 位，
            //   JDK 一律报可执行）。不注入的话这条测的就不是扫描逻辑，而是宿主的
            //   文件系统模型 —— 在 Windows 开发机上永远假红/假绿。
            //   这里用「名单」当模型：只有 node 被认为已有 x 位。
            val found = RootfsModes.findMissingExecBit(root, hasExec = { it.name == "node" })
                .map { it.name }.toSet()

            assertTrue("gcc 缺 x 位，必须被找出来", "gcc" in found)
            assertTrue("脚本缺 x 位也要找出来", "script.sh" in found)
            assertFalse("纯数据文件绝不能被改成可执行", "NOTICE" in found)
            assertFalse("已有 x 位的不该重复修", "node" in found)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun findMissingExecBitSkipsSymlinks() {
        val root = Files.createTempDirectory("rootfs-modes-link").toFile()
        try {
            val bin = File(root, "usr/bin").apply { mkdirs() }
            val real = File(bin, "real-elf")
            real.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            // 软链自己 0644 是正常的（内核跟着链接走），不该被当成损坏
            runCatching {
                Files.createSymbolicLink(File(bin, "cc").toPath(), real.toPath())
            }

            val found = RootfsModes.findMissingExecBit(root, hasExec = { false })
                .map { it.name }.toSet()

            assertTrue("真实文件缺 x 位要修", "real-elf" in found)
            assertFalse("软链的权限位没有意义，不该算进待修清单", "cc" in found)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun findMissingExecBitHandlesMissingDirs() {
        val root = Files.createTempDirectory("rootfs-modes-empty").toFile()
        try {
            // 一个 bin 目录都没有的 rootfs（比如刚解压、还没装任何包）
            assertTrue(RootfsModes.findMissingExecBit(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun findMissingExecBitRespectsLimit() {
        val root = Files.createTempDirectory("rootfs-modes-limit").toFile()
        try {
            val bin = File(root, "usr/bin").apply { mkdirs() }
            repeat(10) { i ->
                File(bin, "tool$i").writeBytes(
                    byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
                )
            }
            assertEquals(
                "limit 必须真的生效，否则修一次能卡几十秒",
                3,
                RootfsModes.findMissingExecBit(root, limit = 3, hasExec = { false }).size,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------------------------------------------------- 装包名 → 要不要顺手修头文件

    @Test
    fun wantsHeadersForCompilerPackages() {
        assertTrue(RootfsModes.wantsHeaders(listOf("build-base")))
        assertTrue(RootfsModes.wantsHeaders(listOf("gcc")))
        assertTrue(RootfsModes.wantsHeaders(listOf("musl-dev")))
        assertTrue(RootfsModes.wantsHeaders(listOf("nodejs", "g++")))
        assertTrue("gcc-14 这种带版本后缀的也要认", RootfsModes.wantsHeaders(listOf("gcc-14")))
    }

    @Test
    fun doesNotWantHeadersForPlainPackages() {
        // 装 node/python 不该为它多走一次特权通道 —— 那是有代价的操作
        assertFalse(RootfsModes.wantsHeaders(listOf("nodejs")))
        assertFalse(RootfsModes.wantsHeaders(listOf("python3", "curl", "git")))
        assertFalse(RootfsModes.wantsHeaders(emptyList()))
    }

    // ---------------------------------------------------- 修复动作本身

    @Test
    fun repairSelectsOnlyQualifyingFiles() {
        val root = Files.createTempDirectory("rootfs-modes-repair").toFile()
        try {
            val bin = File(root, "usr/bin").apply { mkdirs() }
            File(bin, "gcc").writeBytes(
                byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
            )
            File(bin, "run.sh").writeText("#! /bin/sh\necho hi\n")
            File(bin, "NOTICE").writeText("text\n")

            // 起点：谁也不被认为有 x 位
            val before = RootfsModes.findMissingExecBit(root, hasExec = { false })
                .map { it.name }.toSet()
            assertEquals("起点应当把 gcc 与脚本都算作待修", setOf("gcc", "run.sh"), before)

            // 修：对选中的文件补位。★这里真跑 setExecutable，但**不用它作判据** ——
            //   Windows 上它是空操作（NTFS 没有 POSIX x 位），Linux 上才生效。
            //   可移植的判据只有「选谁去修」，那是纯逻辑。
            for (f in RootfsModes.findMissingExecBit(root, hasExec = { false })) {
                f.setExecutable(true, false)
            }

            // 幂等：假定补位成功（Linux 上的真实结果），再扫必须为空
            val after = RootfsModes.findMissingExecBit(root, hasExec = { it.name in before })
                .map { it.name }.toSet()
            assertTrue("补过位的文件不该再次出现在待修清单里", after.isEmpty())

            // 而数据文件从未被选中 —— 这是「不误伤」的核心断言
            assertFalse("纯数据文件绝不能进待修清单", "NOTICE" in before)
        } finally {
            root.deleteRecursively()
        }
    }
}

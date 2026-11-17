package app.dsh.mobile.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 「目录声明的入口名」与「上游实际产物名」不一致时补别名链 —— 纯 JVM 逻辑。
 *
 * ## 为什么需要
 * Termux 的 binutils 包**故意**给工具加了 `g` 前缀（`gar`/`gnm`/`gobjdump`/
 * `gstrip`/`gobjcopy`/`greadelf`…）并只提供 `ld.bfd`，因为 Android 基线里已经
 * 有 llvm 的同名工具，不加前缀会互相覆盖。这是上游的正确选择，不是打包缺陷。
 *
 * 但我们的 catalog 按 GCC 语义声明了 `ar`/`nm`/`objdump`/`strip`/`ld` ——
 * 于是：健康检查报「Main executables missing (5)」（issue #116），"一键修复"
 * 重装后**仍然是同一个结论**（重装改变不了上游叫 gar 这件事）；更实际的是
 * gcc/clang 驱动按裸名调用 `as`/`ld`，缺 `ld` 会让链接阶段直接找不到链接器。
 *
 * 修法不是改 catalog 声明（那等于假装 GCC 不需要这些名字），而是按上游真实产物
 * 补一层别名链：`ld -> ld.bfd`、`ar -> gar` …，让声明名与可用名对齐。
 *
 * ## 不做的事
 *  - 不覆盖已存在的东西（真文件/已有的链都不动），避免和包的后续改动打架。
 *  - 目标不存在就不建（不产出悬空链）。Termux 换版本改名时，这里会安静地少建
 *    几条，而不是把健康检查变成永远修不好的噪声。
 */
internal object ExtensionBinAliases {

    /** extId -> [(要建的入口名, 上游真实产物名)] */
    private val TABLE: Map<String, List<Pair<String, String>>> = mapOf(
        "binutils" to listOf(
            "ld" to "ld.bfd",
            "ar" to "gar",
            "nm" to "gnm",
            "objdump" to "gobjdump",
            "strip" to "gstrip",
            "objcopy" to "gobjcopy",
            "readelf" to "greadelf",
            "ranlib" to "granlib",
            "size" to "gsize",
            "strings" to "gstrings",
            "addr2line" to "gaddr2line",
            "c++filt" to "gc++filt",
        ),
    )

    /** 该扩展需要补的别名对（空 = 无需处理） */
    fun aliasesOf(extId: String): List<Pair<String, String>> = TABLE[extId].orEmpty()

    /**
     * 需要补的别名（只做**决策**，不落盘）：目标在盘上、别名尚未存在。
     *
     * 决策与落盘分开是为了让 JVM 单测能验证判据 —— Windows 上普通用户
     * 建软链需要开发者模式/管理员权限（实测 FileSystemException: 客户端没有
     * 所需的特权），若把两者写死在一起，所有用例都只能 skip，等于没测。
     */
    fun pendingAliases(extId: String, extDir: File): List<Pair<String, String>> {
        val pairs = aliasesOf(extId)
        if (pairs.isEmpty()) return emptyList()
        val bin = File(extDir, "bin")
        if (!bin.isDirectory) return emptyList()
        return pairs.filter { (alias, real) ->
            val link = File(bin, alias)
            val target = File(bin, real)
            // 目标必须真实存在；已有的别名（文件或链）一律不碰
            target.isFile && !Files.isSymbolicLink(link.toPath()) && !link.exists()
        }
    }

    /**
     * 实际补链，返回新建条数。
     * 逐条独立 runCatching：个别 ROM 拒建软链时不影响其余条目。
     */
    fun apply(extId: String, extDir: File): Int {
        val bin = File(extDir, "bin")
        var created = 0
        pendingAliases(extId, extDir).forEach { (alias, real) ->
            val link = File(bin, alias)
            runCatching {
                Files.createSymbolicLink(link.toPath(), Paths.get(real))
                link.setExecutable(true, false)
                created++
            }
        }
        return created
    }
}

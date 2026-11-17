package app.dsh.mobile.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 已装扩展的**启动期补链**：把「声明了但 bin/ 里没有」的入口名补回来。
 *
 * ## 为什么需要（issue #115 / #116 的存量受害者）
 * v1.7.12 起的落点守卫以「扩展根」为基准解析相对 target，把 deb 里最常见的
 * 上级相对软链（`bin/zig -> ../lib/zig/zig`、`bin/go -> ../lib/go/bin/go`、
 * `bin/d2j-dex2jar -> ../opt/dex2jar/d2j-dex2jar.sh`）判成越界并静默跳过。
 * 修好判定只能救**将来**的安装 —— 已经装坏的那些扩展目录里根本没有这条链，
 * 而用户手里的 deb 早被 cacheDir 清了，无法从原始 payload 重放。
 *
 * 所以这里做两件事：
 *  1. 安装期把待落地链接写进 `LINKS` 清单（见 ExtensionManager.createLinks），
 *     启动期按清单重放 —— 覆盖所有「当时被守卫拦掉」的链接，通用且无猜测。
 *  2. 没有清单的历史安装：按上游**已知布局**探测真实产物（`lib/<名>/<名>`、
 *     `lib/<名>/bin/<名>`、`opt/<名>/<名>.sh` 等），命中就补一条相对链。
 *     只认这几种布局，不做全树模糊搜索 —— 猜错会把链指到无关文件上。
 *
 * ## 安全
 * 补链同样过 [ExtensionLinkPolicy]：落点必须在扩展根内。清单来自本应用自己
 * 写的文件（应用私有目录），但仍按不可信输入处理。
 */
internal object ExtensionLinkRepair {

    /** 清单文件里的单行：`linkRel<TAB>target<TAB>s|h` */
    private const val SEP = "\t"

    fun encodeLink(linkRel: String, target: String, isSymlink: Boolean): String =
        linkRel + SEP + target + SEP + (if (isSymlink) "s" else "h")

    fun decodeLink(line: String): Triple<String, String, Boolean>? {
        val parts = line.split(SEP)
        if (parts.size != 3) return null
        val (rel, target, kind) = parts
        if (rel.isBlank() || target.isBlank()) return null
        return Triple(rel, target, kind == "s")
    }

    /**
     * 上游已知布局下，入口名 [name] 的真实产物候选（相对扩展根）。
     * 顺序即优先级：越具体的布局越靠前。
     */
    fun candidateTargets(name: String): List<String> {
        val stripped = name.removePrefix("d2j-")
        return listOf(
            "lib/$name/$name",                 // zig:  lib/zig/zig
            "lib/$name/bin/$name",             // golang: lib/go/bin/go
            "opt/$name/$name.sh",              // 通用脚本式包
            "opt/$stripped/$name.sh",          // dex2jar: opt/dex2jar/d2j-dex2jar.sh
            "lib/$stripped/bin/$name",         // 前缀差异但仍在 lib/<pkg>/bin
        ).distinct()
    }

    /**
     * 为缺失的入口名补链。返回实际新建的 (入口名 -> 目标) 列表。
     *
     * @param declaredBins 目录声明的入口名
     * @param binExists    调用方的存在性探针（ExtensionManager.binExists，含 jvm 布局）
     */
    fun repairMissingBins(
        extDir: File,
        declaredBins: List<String>,
        binExists: (File, String) -> Boolean,
    ): List<Pair<String, String>> {
        val bin = File(extDir, "bin")
        var made = 0
        return pendingRepairs(extDir, declaredBins, binExists).mapNotNull { (name, target) ->
            val link = File(bin, name)
            runCatching {
                Files.createSymbolicLink(link.toPath(), Paths.get("../$target"))
                link.setExecutable(true, false)
                made++
                name to target
            }.getOrNull()
        }
    }

    /**
     * 只做**决策**：哪些入口名能按已知布局补出来、补成什么。
     *
     * 与落盘分开的理由同 [ExtensionBinAliases.pendingAliases] —— Windows 普通用户
     * 建软链需要开发者模式（实测被拒），若把判据和 createSymbolicLink 绑死，
     * 单测在开发机上只能全 skip，等于永远测不到「判据宽了」这种真问题。
     */
    fun pendingRepairs(
        extDir: File,
        declaredBins: List<String>,
        binExists: (File, String) -> Boolean,
    ): List<Pair<String, String>> {
        if (declaredBins.isEmpty()) return emptyList()
        val bin = File(extDir, "bin")
        if (!bin.isDirectory) return emptyList()
        return declaredBins.mapNotNull { name ->
            if (binExists(extDir, name)) return@mapNotNull null
            val link = File(bin, name)
            if (link.exists() || Files.isSymbolicLink(link.toPath())) return@mapNotNull null
            val target = candidateTargets(name).firstOrNull { rel ->
                // 候选必须先存在，且补链后能真的解析出来（不是又一条悬空链）
                val real = File(extDir, rel)
                real.isFile && ExtensionLinkPolicy.isInExtensionRoot(extDir, "bin/$name", "../$rel")
            } ?: return@mapNotNull null
            name to target
        }
    }

    /** 按安装期清单重放（只补缺失的，已存在的一律不碰）。返回新建条数。 */
    fun replayManifest(extDir: File, lines: List<String>): Int {
        var made = 0
        lines.forEach { line ->
            val (rel, target, isSymlink) = decodeLink(line) ?: return@forEach
            val linkRel = rel.removePrefix("usr/")
            val link = File(extDir, linkRel)
            if (link.exists() || Files.isSymbolicLink(link.toPath())) return@forEach
            val t = if (isSymlink) target else target
            if (!ExtensionLinkPolicy.isInExtensionRoot(extDir, linkRel, t)) return@forEach
            val src = if (Paths.get(t).isAbsolute) File(t) else File(File(extDir, linkRel).parent, t)
            if (!src.exists()) return@forEach   // 目标不在盘上：不建悬空链
            runCatching {
                link.parentFile?.mkdirs()
                if (isSymlink) {
                    Files.createSymbolicLink(link.toPath(), Paths.get(t))
                } else {
                    Files.createLink(link.toPath(), src.toPath())
                }
                link.setExecutable(true, false)
                made++
            }
        }
        return made
    }
}

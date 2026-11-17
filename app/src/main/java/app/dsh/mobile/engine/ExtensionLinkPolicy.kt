package app.dsh.mobile.engine

import java.io.File
import java.nio.file.Paths

/**
 * 扩展包内软链的目标落点判定 —— 纯 JVM 逻辑，便于单测直接跑真实实现。
 *
 * ## 为什么需要单独一个文件
 * 这段判定的唯一职责就是「链接目标是否还在扩展目录内」。它由 v1.7.12 的
 * 安全加固引入（防止索引/包被篡改后把链接指到扩展根之外），但从引入那天起
 * 就带了一个**方向性错误**：相对 target 被当成「相对于扩展根」解析，而
 * POSIX 语义是**相对于链接所在目录**。
 *
 * 这个错误不是理论风险。deb 里绝大多数入口软链都是上级相对形态：
 *
 *   usr/bin/zig      -> ../lib/zig/zig          （zig 0.17.1）
 *   usr/bin/go       -> ../lib/go/bin/go        （golang 3:1.27.3）
 *   usr/bin/gofmt    -> ../lib/go/bin/gofmt
 *   usr/bin/d2j-dex2jar -> ../opt/dex2jar/d2j-dex2jar.sh  （dex2jar 2.4-1）
 *
 * 以扩展根解析时，`../lib/zig/zig` 直接退到扩展根的上一级 → 判越界 → 链接被
 * 静默跳过 → `bin/zig` 根本不存在 → 主程序存在性校验报「none of the executables
 * declared by Zig (zig) were installed」，而实际负载 171MB 完好齐全。
 *
 * ## 判定口径
 *  - 相对 target：先按**链接父目录**解析，再判是否落在扩展根内（canonical 化）
 *  - 绝对 target：直接比较是否落在扩展根内（termux 前缀的绝对 target 已由
 *    remapTermuxTarget 映射进根内，无需在这里重复处理）
 *
 * canonical 化能吃掉 `..`、重复分隔符与真实存在的中间软链；目标不存在时
 * canonicalPath 仍可用（File.canonicalFile 不要求目标存在），因此悬空链接也会
 * 被正确判为「未越界」，交由调用方的其他检查决定是否落地。
 */
internal object ExtensionLinkPolicy {

    /**
     * @param rootDir 扩展根目录（安装后为已发布目录，安装期为临时目录）
     * @param linkRel 链接相对扩展根的路径（示例：`bin/zig`；带 `usr/` 前缀的调用方需先剥掉）
     * @param target  deb 里记录的原始 target（相对或绝对）
     */
    fun isInExtensionRoot(rootDir: File, linkRel: String, target: String): Boolean = runCatching {
        val root = rootDir.canonicalFile
        val t = Paths.get(target)
        val resolved = if (t.isAbsolute) {
            t
        } else {
            // 相对 target 的基准是**链接所在目录**，不是扩展根 —— 这是本条判定的全部要害。
            val linkAbs = File(root, linkRel).absoluteFile
            val parent = linkAbs.parentFile ?: return@runCatching false
            parent.toPath().resolve(t)
        }
        val rootPath = root.toPath().normalize()
        val targetPath = resolved.normalize()
        // 用 canonical 再兜一层：中间某级目录若是指向根外的软链，normalize 看不出来。
        val canonical = runCatching { targetPath.toFile().canonicalFile.toPath() }.getOrElse { targetPath }
        canonical.startsWith(rootPath) || targetPath.startsWith(rootPath)
    }.getOrDefault(false)
}

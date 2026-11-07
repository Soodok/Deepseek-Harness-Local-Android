package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * SELinux MCS 分类的检测与修复。
 *
 * ## 为什么需要单独一个类
 *
 * App 进程跑在自己的 MCS 分类里（如 `u:r:untrusted_app_27:s0:c207,c256,c512,c768`），
 * 自己创建的文件恒带完整分类。但**任何跨身份写入**都会产生被剥掉分类的 inode
 * （裸 `u:object_r:app_data_file:s0`）：
 *
 * - Root 模式（或 adb `su`）下跑的 pnpm / 容器装包
 * - 从外部解包覆盖（`tar` / `cp -r` 保留源上下文）
 * - 我们自己用 `su` 往 rootfs 里装包（`apk add`）
 *
 * 被剥分类的文件对 App 是**读不了**的，表现可能极其误导：
 *
 * ```
 * Error loading shared library libicuuc.so.76: Permission denied (needed by /usr/bin/node)
 * ```
 * 看着像缺库或权限位问题，实际是 SELinux 拒绝。引擎随后 exit 127 / SIGSEGV，
 * 而 `ls -l` 一切正常、属主全对。
 *
 * ## 两个实测硬事实（决定实现形态）
 *
 * ### 1. 非 root 域改不了标签，必须走特权通道
 *
 * | 域 | `chcon` 结果 |
 * | --- | --- |
 * | App 进程 `u:r:untrusted_app_27` | `Operation not permitted`, exit 1 |
 * | `run-as` `u:r:runas_app` | `Operation not permitted`, exit 1 |
 * | `su 0` | exit 0，成功 |
 *
 * 所以修复**必须**经 [Privilege.runPrivileged]（Shizuku → su 兜底）。
 * 早期实现直接在 App 内 `ProcessBuilder("/system/bin/chcon", ...)`，在普通模式下
 * 从未成功过一次 —— 它把失败写进日志就算完，是一段静默死代码。
 *
 * ### 2. 检测必须用 `ls -Zd`，不能用 `stat -c %C`
 *
 * 同一个漂移的符号链接：
 *
 * ```
 * ls -Zd    → u:object_r:app_data_file:s0                ← 读链接自身，能发现
 * stat -c%C → u:object_r:app_data_file:s0:c207,c256,...  ← 跟随链接读目标，漏掉
 * ```
 *
 * `chcon -R` 也要配 `-h`：默认改的是链接指向的目标，链接本体仍是 `s0`，
 * loader 解析链接时照样被拒。这几个坑串起来正好是「改完还是不生效」。
 *
 * ## 成本实测（7143 文件 / 643 symlink 的 Alpine rootfs）
 *
 * | 方式 | 耗时 |
 * | --- | --- |
 * | `find -xdev -context <s0> -print` 全量扫描 | 361 ms |
 * | 哨兵 `ls -Zd` 8 条路径（一次 fork） | **14 ms** |
 * | `chcon -R -h` 全量 | **59 ms** |
 *
 * 因此策略是**先查哨兵、只在真漂移时才全量修**：干净路径 14ms，漂移时 73ms。
 * 不做「每次无条件全量 chcon」—— 那会每轮启动改动 7000+ 个 inode 的 ctime。
 */
object SelinuxContext {

    private const val TAG = "SelinuxContext"

    /** 裸 s0：被剥掉 MCS 分类的标记。带分类的是 `s0:c207,c256,...`。 */
    private const val BARE_S0 = "u:object_r:app_data_file:s0"

    /**
     * 判定一个 context 字符串是否「漂移」（缺 MCS 分类）。
     *
     * `u:object_r:app_data_file:s0` → true
     * `u:object_r:app_data_file:s0:c207,c256,c512,c768` → false
     *
     * 后缀判断用 `startsWith("$BARE_S0:")` 而不是相等：带分类时 bare 串是它的前缀，
     * 所以相等判断会漏掉「带分类」这个正常情况（不过滤掉正常值会每轮都判漂移，
     * 变成事实上的无条件全量修）。
     */
    fun isDrifted(context: String?): Boolean {
        val c = context?.trim().orEmpty()
        if (c.isEmpty()) return false
        return c == BARE_S0 || (c.startsWith(BARE_S0) && !c.startsWith("$BARE_S0:"))
    }

    /**
     * 读若干路径的 SELinux 上下文，返回第一条漂移的路径（没有则 null）。
     *
     * 用 `ls -Zd` 而不是 `stat`：后者读的是链接目标，漏掉链接自身的漂移
     * （见类注释的事实 2）。
     *
     * @param paths 要检查的路径；建议传「执行相关」的哨兵（见 [sentinelsFor]）
     * @return 第一条漂移路径；读不到或全正常返回 null
     */
    fun firstDrifted(ctx: Context, paths: List<String>): String? {
        if (paths.isEmpty()) return null
        // 一次 fork 查多条：逐条 fork 在 8 条路径上就是 8 倍成本。
        // shellQuote 全部路径；`ls -Zd` 对不存在的路径会报错到 stderr，
        // 已被重定向丢弃，不影响其余行。
        val quoted = paths.joinToString(" ") { Privilege.shellQuoteForContext(it) }
        val out = Privilege.runPrivileged(ctx, "/system/bin/ls -Zd $quoted 2>/dev/null")
            ?: return null
        // 输出形如：`u:object_r:app_data_file:s0:c207,c256 /path/to/thing`
        // 第一列是 context，最后一段是路径。逐行判断。
        out.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            val ctxPart = t.substringBefore(' ')
            if (!ctxPart.startsWith("u:object_r:")) return@forEach
            if (isDrifted(ctxPart)) {
                return t.substringAfter(' ', "").ifEmpty { null } ?: ctxPart
            }
        }
        return null
    }

    /**
     * 修复一棵树的 SELinux 上下文。
     *
     * 基准（baseline）从 `dshHome` 读**现状**，绝不硬编码 —— MCS 分类串因安装而异
     * （这个 App 在这台设备上是 `c207,c256,c512,c768`，换个安装就变了）。
     *
     * 用 `chcon -R -h`：`-h` 让符号链接本体也一起改。少了它，链接仍是 `s0`，
     * loader 解析时照样 `Permission denied` —— 症状是「修完了还是不生效」。
     *
     * @return 是否真的执行了修复（无特权通道 / 无基准时为 false）
     */
    fun healTree(ctx: Context, root: File): Boolean {
        if (!root.isDirectory) return false
        val baseline = Privilege.fileContextOfPublic(
            EngineConfig.dshHome(ctx).takeIf { it.isDirectory } ?: return false,
        ) ?: return false
        // 基准必须带 MCS 分类；读到裸 s0 说明样本本身就是坏的，不能拿它当标准，
        // 否则会把整棵树「修」成没分类（比不修更糟）。
        if (isDrifted(baseline) || !baseline.startsWith("u:object_r:")) {
            Log.w(TAG, "healTree: refusing to use drifted baseline '$baseline'")
            return false
        }
        val cmd = "/system/bin/chcon -R -h " + Privilege.shellQuoteForContext(baseline) +
            " " + Privilege.shellQuoteForContext(root.absolutePath) + " 2>&1"
        val out = Privilege.runPrivileged(ctx, cmd) ?: run {
            Log.i(TAG, "healTree: no privileged channel (shizuku/su unavailable)")
            return false
        }
        // chcon 对个别条目失败也会返回 0 并继续；有 "Operation not permitted"
        // 就说明这条通道其实没成 —— 如实记录，不假装成功。
        if (out.contains("Operation not permitted") || out.contains("Permission denied")) {
            Log.w(TAG, "healTree: partial failure: " + out.trim().take(200))
            return false
        }
        Log.w(TAG, "healTree: relabelled ${root.absolutePath} -> $baseline")
        return true
    }

    /**
     * Linux rootfs 里「会影响程序加载」的哨兵路径。
     *
     * 为什么是这几条而不是全树扫描：实测全树 `find -context` 要 361ms、
     * 而 8 条哨兵一次 `ls -Zd` 只要 14ms。被剥分类真正致命的是**动态库与可执行
     * 文件**——它们的 `Permission denied` 会让引擎直接起不来；文档/手册页之类
     * 就算漂移也不影响启动，留给 [healTree] 在处理时顺带修。
     *
     * 选点覆盖各层：rootfs 根（目录本身的 dac_override）、`bin`/`usr/bin`（程序）、
     * `usr/lib`（动态库，本类注释里的 libicuuc 事故就在这）、以及 node / bash /
     * curl 三个硬依赖的实体文件。
     */
    fun sentinelsFor(rootfs: File): List<String> = listOf(
        rootfs.absolutePath,
        File(rootfs, "bin").absolutePath,
        File(rootfs, "bin/bash").absolutePath,
        File(rootfs, "usr/bin").absolutePath,
        File(rootfs, "usr/bin/node").absolutePath,
        File(rootfs, "usr/bin/curl").absolutePath,
        File(rootfs, "usr/lib").absolutePath,
        File(rootfs, "usr/lib/libstdc++.so.6").absolutePath,
    )
}

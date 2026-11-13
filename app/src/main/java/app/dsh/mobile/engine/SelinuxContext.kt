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
     * 跑一条只读命令并取回输出。
     *
     * 优先用 [Privilege.runPrivileged]（Shizuku / su）—— 那条路径顺带能读
     * 别人拥有的文件；不可用时退回**当前进程身份**直接 fork。
     *
     * 为什么必须留退路：[firstDrifted] 只读 `ls -Zd`，对 App 自己拥有的
     * inode 完全有权限，没必要依赖特权通道。走特权通道的唯一后果是
     * 「没有 root 就完全看不出有没有漂移」，而那些设备恰恰最需要这条诊断。
     *
     * @return 命令输出；两条通道都失败时 null（调用方据此区分「探测不可用」）
     */
    private fun readContexts(ctx: Context, command: String): String? {
        Privilege.runPrivileged(ctx, command)?.let { return it }
        return runCatching {
            val p = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return@runCatching null
            }
            // 非 0 退出不一定没结果（`ls` 对部分路径报错仍会打印其余行），
            // 但完全没有输出就当作没读到。
            out.ifBlank { null }
        }.getOrNull()
    }

    /**
     * 读若干路径的 SELinux 上下文，返回第一条漂移的路径（没有则 null）。
     *
     * 用 `ls -Zd` 而不是 `stat`：后者读的是链接目标，漏掉链接自身的漂移
     * （见类注释的事实 2）。
     *
     * ## ★检测**不**走特权通道（2026-11-10 修）
     *
     * 原来这里调 [Privilege.runPrivileged]，于是没有 Shizuku / su 的设备上
     * 直接返回 null —— 而 null 的语义是「没漂移」。后果是「检测不到」
     * 与「一切正常」在调用方完全无法区分：
     *
     * - 真漂移 + 无特权通道 → 判「没漂移」→ 放行 → 引擎读不到库（Permission
     *   denied）→ 用户看到的却是「缺基础包，请点修复」，点修复也修不好；
     * - 真漂移 + 有特权通道 → 正常工作。
     *
     * 而**读标签根本不需要 root**：`ls -Zd` 对 App 自己拥有的 inode 完全可读
     * （实测：`su 0 chcon` 打成裸 s0 后，shell 域 `head -c4` 直接读到 ELF 头）。
     * 只有 `chcon`（写）才需要特权 —— 那条限制留在 [healTree] 里。
     *
     * @param paths 要检查的路径；建议传「执行相关」的哨兵（见 [sentinelsFor]）
     * @return 第一条漂移路径；**读不到时也是 null**，调用方要自己区分
     *         「探测不可用」与「确实没漂移」（见 [probe]）
     */
    fun firstDrifted(ctx: Context, paths: List<String>): String? {
        if (paths.isEmpty()) return null
        // 一次 fork 查多条：逐条 fork 在 8 条路径上就是 8 倍成本。
        // shellQuote 全部路径；`ls -Zd` 对不存在的路径会报错到 stderr，
        // 已被重定向丢弃，不影响其余行。
        val quoted = paths.joinToString(" ") { Privilege.shellQuoteForContext(it) }
        val out = readContexts(ctx, "/system/bin/ls -Zd $quoted 2>/dev/null")
            ?: return null
        // 输出形如：`u:object_r:app_data_file:s0:c207,c256 /path/to/thing`
        // 第一列是 context，最后一段是路径。逐行判断。
        out.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            val ctxPart = t.substringBefore(' ')
            if (!ctxPart.startsWith("u:object_r:")) return@forEach
            if (isDrifted(ctxPart)) {
                // ★必须 trim：`ls -Zd` 为了列对齐会在 context 后面补一堆空格，
                // 直接 substringAfter(' ') 拿到的是「              /path/to/x」，
                // 而它会被原样塞进用户可见的报错文案里（实测：
                // 「标签已漂移（                    /data/user/0/.../bin/bash）」）。
                val path = t.substringAfter(' ', "").trim()
                return path.ifEmpty { null } ?: ctxPart
            }
        }
        return null
    }

    /**
     * 扫描整棵树的**符号链接**，返回第一条「标签掉分类」的路径（没有则 null）。
     *
     * ## 为什么哨兵不够（2026-11-13 真机事故）
     *
     * [sentinelsFor] 列的全是**目录与普通文件**。只要这些样本干净，
     * `firstDrifted` 就判「没漂移」，于是 [healTree] 永远不触发 —— 哪怕树里躺着
     * 一堆裸 s0 的符号链接。
     *
     * 而 dpkg 解包**必须先 dereference 既有符号链接**才能覆盖它们：
     *
     * ```
     * dpkg: error processing archive .../curl_8.5.0-2ubuntu10.16_arm64.deb (--install):
     *  failed to stat (dereference) existing symlink
     *  '/usr/share/doc/curl/changelog.Debian.gz': Permission denied
     * ```
     *
     * 后果是 `curl` 永远装不上 → bootstrap 标记停在 `attempted:` → 页面永远
     * 「缺依赖」。真机实测（PLZ110 / Android 16）：26 条裸 s0 链接全部是
     * deb 里的 changelog、`lib*.so`、`usr/lib/ssl` 下的软链，属主权限全对、
     * `ls -l` 一切正常。
     *
     * ## 为什么必须用 `find -type l -exec ls -Zd`
     *
     * - `ls -Zd <路径列表>` 会**跟随**链接读目标，链接本体坏掉时反而看不出来；
     *   只有对链接自身取属性才暴露（与类注释事实 2 同一个坑）。
     * - **App 域连读都读不到**：实测 `run-as app.dsh.mobile ls -Zd <裸s0链接>`
     *   直接 `Permission denied`，`find -type l` 也会静默漏掉它。所以这条检测
     *   只能走特权通道；无通道时返回 null（宁可不报，也不误报）。
     *
     * 成本实测（9682 条 / 227 个链接，真机）：93ms。只在装包与冷启各跑一次。
     */
    fun firstDriftedLink(ctx: Context, root: File): String? {
        if (!root.isDirectory) return null
        val cmd = "/system/bin/find " + Privilege.shellQuoteForContext(root.absolutePath) +
            " -type l -exec /system/bin/ls -Zd {} + 2>/dev/null"
        val out = readContexts(ctx, cmd) ?: return null
        out.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            val ctxPart = t.substringBefore(' ')
            if (!ctxPart.startsWith("u:object_r:")) return@forEach
            if (isDrifted(ctxPart)) {
                val path = t.substringAfter(' ', "").trim()
                return path.ifEmpty { null } ?: ctxPart
            }
        }
        return null
    }

    /**
     * 装包前的兜底：树里有掉分类的**符号链接**就全量重定标签。
     *
     * 放在 dpkg/apt 入口，而不是只放在冷启：裸 s0 链接是**解包时**产生的
     * （root 域写、tar 保留源上下文），冷启那次自愈发生在它之前，修不到。
     *
     * @return 是否执行了修复（无特权通道 / 未漂移时为 false）
     */
    fun healLinksIfDrifted(ctx: Context, root: File): Boolean {
        val drifted = runCatching { firstDriftedLink(ctx, root) }.getOrNull() ?: return false
        Log.w(TAG, "symlink context drifted at $drifted — relabelling tree")
        return healTree(ctx, root)
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
     *
     * ★2026-11-08 补 apk 数据库（`lib/apk/db` 下的 installed / scripts.tar /
     *   triggers 三件）：漏了它们上线过一次真实故障。
     *
     *   症状：扩展中心任何安装都失败，`engine.log` / logcat 里只有
     *     `apk update: ok=false rc=99 err=ERROR: Unable to read database state: Permission denied`
     *   而 `ls -la` 看属主与权限**全对**（u0_a80:u0_a80 0644），全树 `find -user root`
     *   也是 0 —— 因为问题不在 DAC，在 MAC：`lib/apk/db/installed` 的上下文是裸
     *   `u:object_r:app_data_file:s0`，比同目录（`...:s0:c512,c768`）少了 MCS 分类，
     *   而 App 域带着分类去读它 → 拒绝。
     *
     *   成因：只要有任何 root 域（`u:r:su:s0`）的进程往 rootfs 里写过文件，新建的
     *   文件就没有 MCS 分类。本类自己的 [healTree] 与 `Privilege.runPrivileged`
     *   都走 su，所以漂移会自己产生自己 —— 哨兵必须覆盖这些「运行期会被 root 写」
     *   的路径，否则"修好了下次又坏"。
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
        // 包管理器数据库：apk（Alpine）与 dpkg/apt（Ubuntu）。
        // root 域写过就会掉分类，症状是 rc=99 Permission denied，
        // 而属主/权限看上去完全正常。
        File(rootfs, "lib/apk/db").absolutePath,
        File(rootfs, "lib/apk/db/installed").absolutePath,
        File(rootfs, "lib/apk/db/scripts.tar").absolutePath,
        File(rootfs, "lib/apk/db/triggers").absolutePath,
        File(rootfs, "var/lib/dpkg").absolutePath,
        File(rootfs, "var/lib/dpkg/status").absolutePath,
        // ★dpkg 的**备份文件**也要覆盖（v1.7.13 实测补上）。
        //
        // dpkg 每次写 status 前会先建 `status-old`（rename 旧文件）。一旦某个环节
        // 以 root 域写过 /var/lib/dpkg，这些文件就是裸 s0；之后 App uid 再跑
        // `dpkg --configure -a` 会死在
        //     dpkg: error: error creating new backup file '/var/lib/dpkg/status-old':
        //       Permission denied
        // —— 它把「读不到父目录里的旧文件」报成「创建备份失败」，方向完全误导。
        // 实测：status / status-old / diversions-old 三个文件的标签是 `?`（跨 MCS
        // 读取被静默拒绝），而 `ls -l` 属主权限全对、手工 touch 也能写。
        // 只检测 status 一个文件会漏掉这类「同伴文件漂移」，所以把这一族平铺列出。
        File(rootfs, "var/lib/dpkg/status-old").absolutePath,
        File(rootfs, "var/lib/dpkg/diversions").absolutePath,
        File(rootfs, "var/lib/dpkg/diversions-old").absolutePath,
        File(rootfs, "var/lib/dpkg/available").absolutePath,
        File(rootfs, "var/cache/apk").absolutePath,
    )
}

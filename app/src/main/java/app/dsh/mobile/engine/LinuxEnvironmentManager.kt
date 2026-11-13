package app.dsh.mobile.engine

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Linux 环境（Workspace）管理器 —— 多发行版 rootfs 的部署、修复与自检。
 *
 * ## 这是什么
 *
 * 把 rootfs 落地到 `filesDir/linux/distros/<id>/`，并用 `dsh-alpine`
 * （seccomp 用户态运行器）在其中执行 Linux 程序 —— **全程免 root**。
 *
 * 支持两个发行版（见 [LinuxRootfsCatalog]）：
 * - **Alpine**（musl，随 APK 资产，8.1MB 解压 / 离线可用）
 * - **Ubuntu 24.04**（glibc，联网下载 29.9MB / 解压 96MB）
 *
 * 两者**并存**，各自独立目录、独立标记、独立已装软件。切换只改"当前指向"，
 * 不动任何文件 —— 用户在 Alpine 里装的包不会因为试 Ubuntu 就消失。
 *
 * ## glibc 发行版的两条专属处理（都是实测踩出来的）
 *
 * 1. **装包必须两步**：`apt-get install` 直接跑会在 dpkg 解包后报
 *    `cannot stat '/tmp/apt-dpkg-install-XXXX/00-pkg.deb'`（实测 Ubuntu 24.04）。
 *    改成 `apt-get -d install`（只下载）+ 对 cache 里的 deb 跑 `dpkg -i`
 *    即成功。见 [installPackagesApt]。
 * 2. **ldconfig 必须 shim**：它是静态 PIE，跑起来段错误（exit 139），
 *    导致所有 libc 触发器的 configure 失败。见
 *    [LinuxRootfsCatalog.glibcQuirksScript]。
 *
 * ## 为什么需要这个类（而不是直接 tar 解压）
 *
 * 直接把 tarball 解开是**不能用的**，有三个必须处理的坑，每个都会导致「命令找不到」
 * 或「Permission denied」，而且报错极具误导性（看起来像 rootfs 缺文件）：
 *
 * 1. **绝对符号链接**：官方 minirootfs 的 335 个 applet 链接全部指向 `/bin/busybox`
 *    这类**绝对路径**。我们的 seccomp 方案只重定向「应用发起的路径」；内核解析
 *    symlink 目标是按**宿主真实根**来的 → `/bin/busybox` 在宿主上不存在 → ENOENT，
 *    表现为 `sh: cat: not found`。必须在部署期一次性重写成相对链接
 *    （实测 335 个链接、306 个需重写；TAWC 选的是每次 syscall 都解析的 live 方案，
 *    安装期一次性重写成本低得多）。
 * 2. **执行位依赖 tar 头**：Android 的 `tar` 解包在部分路径下会丢 mode；本项目统一
 *    用自己实现的 [untar] 按 tar 头 offset 100 的 mode 字段重建权限位（与
 *    `ExtensionManager.untar` 同源做法），否则 busybox 本体不可执行。
 * 3. **命令行工具不全**：minirootfs 只带 busybox + apk，没有 node / git / gcc。
 *    这些交给 `apk add` 按需安装（见 [apkRepositories]），不预置进 APK。
 *
 * ## 目录布局
 *
 * ```
 * filesDir/
 *   linux/
 *     runner/dsh_alpine           <- 从 APK 的 libdsh_alpine.so 提取（见 EngineConfig.deployLinuxRunner）
 *     distros/
 *       alpine/                   <- musl 用户态（随包资产解出）
 *         .dsh-deployed           <- 部署完成标记（含版本号，幂等与升级判定）
 *       ubuntu/                   <- glibc 用户态（联网下载后解出）
 *         .dsh-deployed
 *     state/                      <- 会话状态、日志
 * ```
 *
 * 老版本用 `linux/rootfs/` 单目录，首次访问会被原子重命名迁移到
 * `distros/alpine/`（见 [migrateLegacyLayout]），不复制、不丢数据。
 *
 * ## 幂等与自愈
 *
 * - 首次运行：解包/下载 → 修链接 → 恢复执行位 → 写标记。
 * - 后续运行：比对标记里的版本号，一致则跳过（不动用户已装的所有软件）。
 * - 检测到关键文件缺失（关键二进制不可执行 / 链接断了）时判 BROKEN，
 *   由用户点「修复」强制重装。
 */
class LinuxEnvironmentManager(private val ctx: Context) {

    /** Linux 工作区根：`filesDir/linux` */
    fun linuxRoot(): File = File(ctx.filesDir, "linux").apply { mkdirs() }

    /**
     * 当前选中的发行版（每次实时解析，切发行版后立刻生效）。
     *
     * 顺带做一次老布局迁移 —— 这是**所有** rootfs 访问的必经入口（status /
     * ensureDeployed / runner / launcher 都从这里拿选项），放这里能保证迁移
     * 一定先于任何读路径发生。迁移本身是 O(1) 的目录存在性判断，已迁移后
     * 每次只剩两次 stat，不会拖慢冷启。
     */
    fun activeOption(): LinuxRootfsCatalog.Option {
        migrateLegacyLayout()
        return LinuxRootfsCatalog.active(ctx)
    }

    /** 当前 rootfs 落点：`linux/distros/<activeId>/` */
    fun rootfsDir(): File = rootfsDir(activeOption())

    /** 指定发行版的 rootfs 落点 */
    fun rootfsDir(option: LinuxRootfsCatalog.Option): File =
        LinuxRootfsCatalog.rootfsDir(ctx, option)

    /**
     * 把老版本的单目录布局迁移到多发行版布局。
     *
     * 老版本只有 `linux/rootfs/`（Alpine）。新布局是 `linux/distros/alpine/`。
     * 用**重命名**而不是复制：rootfs 里有用户装的几百个包，复制既慢又费磁盘，
     * 而且中途失败会留下两份半成品。重命名是原子的，失败就当没迁移过。
     *
     * 触发时机：任何路径访问 rootfs 之前（[rootfsDir] 调用方）。幂等。
     */
    @Synchronized
    fun migrateLegacyLayout() {
        val legacy = File(linuxRoot(), "rootfs")
        val target = LinuxRootfsCatalog.rootfsDir(ctx, LinuxRootfsCatalog.byFamily(LinuxRootfsCatalog.Family.ALPINE))
        if (!legacy.isDirectory || target.exists()) return
        target.parentFile?.mkdirs()
        val ok = runCatching { legacy.renameTo(target) }.getOrDefault(false)
        if (ok) {
            Log.i(TAG, "migrated legacy rootfs -> ${target.absolutePath}")
        } else {
            // 跨文件系统重命名会失败（同一 filesDir 下理论上不会）。绝不静默：
            // 迁移失败时保留 legacy 目录，让老用户仍能跑，只是没有多发行版。
            Log.w(TAG, "legacy rootfs migration failed (keeping ${legacy.absolutePath})")
        }
    }

    /** 该发行版是否已落地（只看标记文件，O(1)） */
    fun isDeployed(option: LinuxRootfsCatalog.Option): Boolean =
        File(rootfsDir(option), MARKER).isFile

    /**
     * X11 抓帧路线在本机 ABI 上是否可用（= `x11frsrv` 资产是否随包）。
     *
     * 为什么单独暴露：`X11FRSRV_ASSET` 是 private，而 UI 需要据此选画面路线。
     * 若 UI 自己再判一次 `Build.SUPPORTED_ABIS`，就等于把「哪些 ABI 有产物」
     * 这个事实抄了两份，迟早一处改了一处没改；判据只留这里一个来源。
     *
     * 2026-11-11 起 x86_64 与 arm64 都有产物，所以恒为 true。保留这个函数而
     * 不是让 UI 直接走 X11，是为了将来某个 ABI 又缺产物时改一处就够。
     */
    fun x11FrameAvailable(): Boolean = X11FRSRV_ASSET.isNotEmpty()

    /**
     * 该**形态**的额外包是否已装好。
     *
     * 与 [isDeployed] 是两件事：
     * - `isDeployed` 按 family 算（flavor 共享同一 rootfs 目录）
     * - 这个是按 flavor 的 bootstrap 标记文件算（见 [ensureBootstrapPackages]
     *   里标记按 flavor 分开的理由）
     *
     * 为什么要单独暴露给 UI：只报 `isDeployed` 会让「切到 GUI」显示成
     * 「已安装」，而 Xvfb 其实还没装 —— 用户点「开始画面」得到 not found，
     * 界面上却写着一切正常。
     *
     * BASE 形态只看主标记；其它形态看 `.dsh-bootstrap.<flavor>`。
     */
    fun flavorPackagesReady(option: LinuxRootfsCatalog.Option): Boolean {
        if (!isDeployed(option)) return false
        val root = rootfsDir(option)
        val name = BOOTSTRAP_MARKER + if (option.flavor == LinuxRootfsCatalog.Flavor.BASE)
            "" else "." + option.flavor.id
        val mark = File(root, name)
        if (!mark.isFile) return false
        /*
         * 标记存在还不够 —— 它可能带 `attempted:` 前缀（装失败也落标记，
         * 见 ensureBootstrapPackages 的失败分支）。那种情况不算 ready，
         * 否则 UI 又会报「已就绪」而实际缺包。
         */
        val head = runCatching { mark.readText().take(16) }.getOrDefault("")
        return !head.startsWith("attempted:")
    }

    /** 运行器与状态目录 */
    fun runnerDir(): File = File(linuxRoot(), "runner").apply { mkdirs() }

    fun stateDir(): File = File(linuxRoot(), "state").apply { mkdirs() }

    /** 用户数据目录（home、装好的软件包都在 rootfs 内，这里只放会话级状态） */
    fun markerFile(): File = File(rootfsDir(), MARKER)

    // ==================== 状态查询 ====================

    enum class State {
        /** 未部署（首次使用，或标记缺失） */
        NOT_DEPLOYED,

        /** 正在部署 */
        DEPLOYING,

        /** 已就绪，可直接进容器 */
        READY,

        /** 已部署但自检失败（修复中或需重装） */
        BROKEN,
    }

    data class Status(
        val state: State,
        val rootfsVersion: String? = null,
        val rootfsBytes: Long = 0L,
        val fileCount: Int = 0,
        val detail: String = "",
    )

    /**
     * 轻量自检：只看关键路径，不做全量扫描（避免启动路径上遍历上万个文件）。
     *
     * 判据按发行版不同（每一条都是实测踩过的具体故障点）：
     * - 动态链接器存在 → 否则容器连第一个程序都起不来
     * - 包管理器可执行 → 否则装不了任何软件包
     * - 一个**确实存在**的符号链接样本能解析 → 验证链接修复没漏
     *
     * ⚠️ 判据样本必须选**真实存在于该 rootfs 布局里**的条目。
     * 踩过的坑：最初拿 `usr/bin/cat` 当 Alpine 的判据，但 minirootfs 的 cat
     * 只在 `bin/cat`，`usr/bin/` 下根本没有 cat —— 健康环境永远被判
     * 「applet links not repaired」，UI 显示需要修复，并且因为 status() 不是
     * READY，部署流程提前 return、连运行器都不部署，整条链路假死。
     */
    fun status(): Status = status(activeOption())

    fun status(option: LinuxRootfsCatalog.Option): Status {
        val root = rootfsDir(option)
        if (!root.isDirectory) return Status(State.NOT_DEPLOYED, detail = "rootfs directory does not exist")

        val marker = File(root, MARKER)
        val ver = if (marker.isFile) marker.readText().trim().lines().firstOrNull() else null

        // ① 动态链接器：容器 argv 里必须传它，缺了整个 rootfs 都用不了
        val interp = File(root, option.interpPath.trimStart('/'))
        if (!interp.isFile) {
            return Status(State.BROKEN, ver, detail = "dynamic loader missing: ${option.interpPath}")
        }

        // ② 包管理器（Alpine 走 sbin/apk；Ubuntu 走 usr/bin/apt-get）
        val pm = when (option.pkgManager) {
            LinuxRootfsCatalog.PackageManager.APK -> File(root, "sbin/apk")
            LinuxRootfsCatalog.PackageManager.APT -> File(root, "usr/bin/apt-get")
        }
        if (!pm.isFile || !pm.canExecute()) {
            return Status(State.BROKEN, ver, detail = "${pm.name} missing or not executable")
        }

        // ③ 链接样本：验证链接修复真的生效（同时也是断链检测）
        val sample = when (option.family) {
            // 3.23 与 3.22 同为 Alpine 布局，健康判据同一个文件
            LinuxRootfsCatalog.Family.ALPINE,
            LinuxRootfsCatalog.Family.ALPINE_EDGE -> File(root, "usr/bin/env")
            // Ubuntu 的 /bin/sh -> dash 是必然存在的绝对->相对重写样本
            LinuxRootfsCatalog.Family.UBUNTU -> File(root, "bin/sh")
        }
        if (!sample.exists()) {
            return Status(State.BROKEN, ver, detail = "${sample.name} missing or dangling (links not repaired)")
        }

        // ⚠️ 这里**不能**算 dirBytes/countFiles。
        //
        // 它们是两次完整的 rootfs 目录遍历（Alpine 628 文件，Ubuntu 3413 文件，
        // 实测 300~500ms 起），而这只是给 LinuxActivity 状态卡显示两个数字。
        // 更糟的是 status() 在**每次冷启**都会被 EngineModeSelector.resolve()
        // 调用一次 —— 用户为两个展示数字付出近半秒的启动延迟。
        //
        // READY 分支只做 O(1) 判据；数字留给 UI 需要时通过
        // [diskUsage]/[fileCount] 单独取。
        return Status(State.READY, ver)
    }

    // ==================== 部署主流程 ====================

    /**
     * 确保 rootfs 就绪。幂等：已就绪时直接返回。
     *
     * **必须跑在后台线程**（解包 110 个文件 + 重写 335 个链接，实测 1~3 秒）。
     *
     * @param force 为 true 时忽略标记强制重新解包（用于「修复」按钮与升级）
     * @return 部署后的状态
     */
    fun ensureDeployed(force: Boolean = false): Status = ensureDeployed(activeOption(), force)

    /**
     * 部署指定发行版（幂等）。**必须跑在后台线程**（解包 + 修链接实测 1~3 秒；
     * glibc 发行版还要先下载，30MB 在网络好的时候约 5~10 秒）。
     *
     * @param force 为 true 时忽略标记强制重装（「修复」按钮）
     */
    fun ensureDeployed(option: LinuxRootfsCatalog.Option, force: Boolean = false): Status {
        migrateLegacyLayout()
        val root = rootfsDir(option)
        val marker = File(root, MARKER)
        val assetVersion = versionTag(option)

        if (!force) {
            val cur = status(option)
            if (cur.state == State.READY && cur.rootfsVersion == assetVersion) {
                Log.i(TAG, "rootfs already ready (${option.family.id} v${cur.rootfsVersion})")
                // 预置编译缓存也在部署阶段补（幂等；已有就只做一次 listFiles）。
                // 为什么放这里而不是冷启路径：解 1281 个文件要 1.5~4 秒，
                // 而收益只有 840ms —— 挂在冷启上是净亏（实测）。
                runCatching { seedCompileCache() }
                    .onFailure { Log.w(TAG, "seed cache failed: ${it.message}") }
                // ★兼容层必须在这里也跑一次（v1.6.14 修复）。
                //
                // 原先它只挂在「解包完成」路径的末尾，于是 rootfs 一旦 READY
                // 就**永远跳过** —— 「代码明明加了 shim，设备上却什么都没有」。
                // 实测症状：容器内 grep/glob 报 ripgrep launch failed
                // （$root/bin/rg 不存在）、门脚本 shebang 找不到 /system/bin/sh。
                //
                // 幂等（靠标记文件 + 目标存在判断），重复调用无代价。
                installLinuxCompatShims(root)
                return cur
            }
            if (cur.state == State.READY && cur.rootfsVersion != assetVersion) {
                // 版本变了但结构完好：不重装，只更新标记，避免抹掉用户装好的软件。
                Log.i(TAG, "rootfs version drift ${cur.rootfsVersion} -> $assetVersion (keeping installed packages)")
                marker.writeText("$assetVersion\n${System.currentTimeMillis()}\n")
                installLinuxCompatShims(root)
                return cur.copy(rootfsVersion = assetVersion)
            }
        }

        Log.i(TAG, "deploying rootfs ${option.family.id} (force=$force) -> ${root.absolutePath}")
        val t0 = System.currentTimeMillis()

        // 强制重装时先清空；普通部署只补缺（保留用户已装软件）
        if (force) purge(root)
        root.mkdirs()

        val pending = mutableListOf<LinkJob>()
        val entries = when (val src = option.source) {
            is LinuxRootfsCatalog.RootfsSource.Bundled ->
                openAssetRootfs(src.assetPath).use { ins -> untar(ins, root, pending) }

            is LinuxRootfsCatalog.RootfsSource.Remote -> {
                // 下载到 cacheDir（不是 rootfs 内）：解包前它不属于用户环境，
                // 放在外面也让「修复」时的 purge 不会误删正在用的 tar。
                val cached = downloadRootfs(option, src)
                openArchive(cached, src.format).use { ins -> untar(ins, root, pending) }
            }
        }
        materialiseLinks(root, pending)
        restoreExecBits(root)

        // 发行版专属配置：写在解包之后、标记之前 —— 配置没写成功就不该算部署完成。
        // runQuirks = true：部署时把 ldconfig shim 一次做完，启动路径就不用碰容器。
        writeDistroConfig(option, root, runQuirks = true)
        // 引擎侧兼容层：这两件事不做，容器里「能跑 shell」但「AI 的工具基本不可用」。
        installLinuxCompatShims(root)
        // 预置编译缓存：首次部署时就解出来，让第一轮引擎启动直接命中。
        // 放在这里而不是冷启路径（见 ensureDeployed 早退分支的说明：解包
        // 1.5~4 秒 vs 收益 840ms，挂在冷启上是净亏）。失败不影响部署结果。
        runCatching { seedCompileCache() }
            .onFailure { Log.w(TAG, "seed cache failed (deploy): ${it.message}") }

        // POSIX 共享内存目录。/dev 是整棵透传给宿主的，而 Android 宿主**没有**
        // /dev/shm；dsh_alpine.c 的 is_shm_path() 已把 /dev/shm 排除出透传，
        // 落到 rootfs 内这个目录。不建它的后果：
        //   - musl 的 shm_open()（实现就是 open("/dev/shm/<name>")）全部 ENOENT
        //   - wlroots 的 headless 后端报 Failed to allocate buffer，sway/weston
        //     的 output 建得出来但永远分配不到帧缓冲，截图全是空
        //   - dbus / PulseAudio / Chromium 同样依赖它
        ensureShmDir(root)

        val ms = System.currentTimeMillis() - t0
        marker.writeText("$assetVersion\n${System.currentTimeMillis()}\n")
        Log.i(TAG, "rootfs deployed (${option.family.id}): $entries entries, ${pending.size} links, ${ms}ms")

        // 部署完补基础包（bash / curl / ca-certificates），**同步**装完。
        //
        // 缺 bash 时 AI 的 bash 工具（dsh-bash-local 固定执行 `bash -c`）每条
        // 命令都会 spawn 失败；缺 ca-certificates 则 https 报 SSL 错。
        //
        // ★这里同步等它，是为了让「启动路径不再需要联网」这个前提成立：
        //   部署是用户显式动作（点「安装/修复」），多等几秒完全可接受；
        //   而启动是每次进 Linux 工作台都要走的路，慢一点都刺眼。
        //   早先这里丢后台线程、启动路径上又各调一次，结果每次冷启都要
        //   重新判断/重跑一遍，Ubuntu 侧因 apt 慢变成 63 秒。
        //   装完写标记，之后启动时 ensureBootstrapPackages 是 O(1) 文件判断。
        runCatching { ensureBootstrapPackages(option) }
            .onFailure { Log.w(TAG, "bootstrap failed: " + it.message) }

        return status(option)
    }

    /**
     * 容器内的两处引擎兼容层。都是幂等的，每次部署/修复都会重跑。
     *
     * ## 1. `/system/bin/sh` → `/bin/sh`
     *
     * `assets/gates` 里的门脚本首行是 `#!/system/bin/sh`（它们为**原生**模式写的：
     * Android 的 toybox sh 一定在那）。Alpine rootfs 里没有 `/system/bin`，
     * 于是 `dev` / `scr` / `loop` / `notify` 等一执行就
     * `No such file or directory` —— 注意报错指向脚本本身，极易误判成脚本丢失。
     *
     * 门脚本内部第一件事就是 `exec` 到 bash（`/dev/tcp` 需要 bash），
     * 所以这里只要把 shebang 指向的解释器补上即可，脚本内容不用改。
     *
     * ## 2. 静态 musl 版 ripgrep → `$root/bin/rg`
     *
     * 引擎的 `grep`/`glob` 工具用 `@vscode/ripgrep`，它按
     * `process.platform-arch` 拼包名后 `require.resolve`。容器里
     * `process.platform` 是 **linux**（不是 android），所以去要
     * `@vscode/ripgrep-linux-arm64`；而 runtime 里只注入了
     * `@vscode/ripgrep-android-<arch>`（bionic 版）→ 必然解析失败，
     * 表现为「grep/glob 全挂」。
     *
     * 注入的这份是 ripgrep 官方 release 的**静态链接** aarch64 二进制
     * （无 PT_INTERP、无 DT_NEEDED），musl 容器里可直接 exec。
     *
     * 注：不改 `@vscode/ripgrep` 的 resolver —— 上游改版即碎（构建脚本里
     * 已经退过一次文本补丁，见 collect-termux-runtime.sh 的注释）。
     * 这里走「把二进制放到 PATH 上」这条对上游完全免疫的路。
     */
    /**
     * 清理 Node 编译缓存里的**陈旧条目**（v1.6.15）。
     *
     * ## 为什么必须清
     *
     * Node 在 `NODE_COMPILE_CACHE` 指定目录下按 `<node版本>-<arch>-<hash>-0`
     * 建子目录。同一个根里可以堆任意多个 —— 换一次 node 就多一个，且**旧的不会
     * 自动删**（Node 只保证自己的目录能复用，不做跨版本 GC）。
     *
     * 实测（真机 2026-11-05）：根下堆了 6 个目录 / 46MB / 5577 文件，
     * 而当前 node 认得的只有 1 个（13MB / 1532 文件），其余 33MB 是死重。
     * 每次冷启 Node 要 readdir + 逐个 stat 全部条目，白白多花几百 ms。
     *
     * ## 判据：只留当前 node 的那个
     *
     * 目录名前缀是 Node 自己写的（`v22.23.4-arm64-2b4477fa-0`），其中
     * `v<version>-<arch>` 段与 `process.version` / `process.arch` 一致。
     * 这里用宿主机上 node 二进制的版本号推断，拿不到就**一个都不删**
     * （宁可不清，也不能把正在用的缓存删掉）。
     *
     * @return 删掉的目录数
     */
    fun pruneCompileCache(): Int {
        // ★版本来源：预置包里的目录名，**不是 node 二进制**。
        //
        // 原来读 node 二进制前 64KB 找版本串 —— 那条路**从来没成功过**：
        // 版本串并不在 ELF 头部。实测 engine 的 node（v24.18.0 / 44.8 MB）
        // 首个版本串在偏移 10 283 052，rootfs 里的 node（v22.23.4 / 55.7 MB）
        // 在偏移 36 802 442。两者都远在 64KB 之外，于是 version 恒为 null、
        // 函数在下面一行直接 return 0 —— prune 一直是死代码，
        // 缓存目录只增不减（这也是「根下堆了 6 个版本目录 / 46MB」的成因）。
        //
        // 现在的来源天然正确：预置包（[COMPILE_CACHE_ASSET]）里就带着
        // `v<版本>-<arch>-<hash>-0` 这一级目录名，读 zip 中央目录即可，
        // 零 fork、零大文件读取。没有预置包就一个都不删（保持原有安全兜底）。
        val keep = seededCachePrefix() ?: return 0
        val root = File(EngineConfig.dshHome(ctx), ".node-compile-cache")
        val dirs = root.listFiles { f -> f.isDirectory } ?: return 0
        var removed = 0
        for (d in dirs) {
            if (d.name.startsWith(keep)) continue
            // 只删形如 vX.Y.Z-arch-hash-0 的目录，别的（用户自己放的）一律不动
            if (!d.name.startsWith("v") || d.name.count { it == '-' } < 3) continue
            if (runCatching { d.deleteRecursively() }.getOrDefault(false)) removed++
        }
        if (removed > 0) Log.i(TAG, "pruned $removed stale compile-cache dir(s), kept $keep*")
        return removed
    }

    /**
     * 预置缓存包里那一级版本目录名，形如 `v24.18.0-x64-cf738c9d-0`；拿不到返回 null。
     *
     * ## 为什么不用 node 二进制当来源（这是修掉的一个真实 bug）
     *
     * 原来 [pruneCompileCache] 与 [seedCompileCache] 都这么读版本：打开 node
     * 二进制，只看**前 64 KB**，正则找一个 `X.Y.Z`。实测这个做法**一次都没成功过**：
     *
     * | 二进制 | 大小 | 首个版本串偏移 |
     * |---|---|---|
     * | `engine/bin/node`（v24.18.0） | 44.8 MB | **10 283 052** |
     * | `distros/alpine/usr/bin/node`（v22.23.4） | 55.7 MB | **36 802 442** |
     *
     * 两者都远在 64 KB 之外（ELF 头部只有 "GNU"、interp 路径这类字符串），
     * 于是 `version` 恒为 null、两个函数都在 `?: return` 处静默退出。
     * 后果：prune 是死代码（缓存目录只增不减，这就是「根下堆了 6 个版本
     * 目录 / 46MB」的真正成因）；seed 也从不执行。
     *
     * ## 为什么包内目录名是可靠来源
     *
     * 那一级目录名是 **Node 自己**写出来的（`process.version` + `process.arch`），
     * 打预置包时原样保留。读它只需解析 zip 第一个 entry 的名字（本地文件头，
     * 几十字节），既不 fork 也不扫大文件 —— 冷启路径的两条硬约束都满足。
     *
     * 读到的结果缓存在 [cachedPrefix]：同一进程内 APK 不会变，没必要每次问一遍。
     */
    private fun seededCachePrefix(): String? {
        cachedPrefix?.let { return it }
        val hit = runCatching {
            ctx.assets.open("linux/$COMPILE_CACHE_ASSET").use { ins ->
                java.util.zip.ZipInputStream(ins.buffered()).use { zin ->
                    val first = zin.nextEntry?.name ?: return@runCatching null
                    val top = first.substringBefore('/')
                    if (top.startsWith("v") && top.count { it == '-' } >= 3) top else null
                }
            }
        }.getOrNull()
        cachedPrefix = hit
        if (hit == null) {
            Log.w(TAG, "seed cache: cannot read version prefix from $COMPILE_CACHE_ASSET")
            return null
        }

        // ★必须与**实际要跑的那个 node** 交叉校验，否则缓存永远不命中。
        //
        // 实测踩到的情形：包从 `engine/bin/node`（bionic v24.18.0）生成，
        // 而 Linux 模式真正执行的是容器内的 `/usr/bin/node`（musl v22.23.4，
        // 见 LinuxEngineLauncher.NODE_IN_CONTAINER）。两者版本不同、构建
        // 指纹（目录名最后那段 hash）也不同：
        //   engine/bin/node  -> v24.18.0-x64-cf738c9d-0
        //   /usr/bin/node    -> v22.23.4-x64-9ac5647c-0
        // 包版本不对时 seed 照样解 958~1281 个文件，但 Node 一个都不认，
        // 白占 6.8MB 磁盘、A/B 测出来是「零收益」（实测 4328 vs 4275ms）。
        //
        // 校验方式：从**目标 node 里**取版本——不能读二进制头部（见
        // pitfalls #49），改为问一次 `node --version`。
        // 这次 fork 是可接受的：它只发生在部署阶段（本函数只在 ensureDeployed
        // 里被调用），不在冷启路径上，而且整段 seed 本来就是 1.5~4 秒的量级。
        val live = nodeVersionOf(runCatching { activeOption() }.getOrNull()) ?: run {
            Log.i(TAG, "seed cache: cannot determine live node version, skipping (bundle $hit)")
            return null
        }
        val bundleVer = hit.split('-').getOrNull(0)?.removePrefix("v")
        if (bundleVer != live) {
            Log.i(TAG, "seed cache: bundle $hit is for node v$bundleVer, live node is v$live -- skipping")
            return null
        }
        return hit
    }

    /**
     * 问一次目标 node 的版本（`--version`），拿不到返回 null。
     *
     * 只在部署阶段调用（[ensureDeployed]），**不在冷启路径上** ——
     * 冷启不能多一次 fork，这一点在 [LinuxEngineLauncher.readiness] 的注释里
     * 有详细说明。而部署阶段用户本来就在等 rootfs 解包 1~3 秒，多这几十毫秒
     * 完全感知不到。
     */
    private fun nodeVersionOf(option: LinuxRootfsCatalog.Option?): String? = runCatching {
        val r = LinuxContainerRunner.get(ctx).shell(
            "/usr/bin/node --version 2>/dev/null || " +
                LinuxRootfsCatalog.GLIBC_NODE_PATH + " --version 2>/dev/null",
            15_000L,
        )
        val raw = r.stdout.trim().removePrefix("v")
        if (raw.matches(Regex("""\d{1,2}\.\d{1,2}\.\d{1,3}"""))) raw else null
    }.getOrNull()

    /**
     * 首次冷启前把**预置好的** Node 编译缓存解到 `$DSH_HOME/.node-compile-cache`。
     *
     * ## 为什么这条能省时间（模拟器 x86_64 实测，交替 A/B 各 3 轮）
     *
     * 有预置缓存中位 **3357.4 ms**（3184.0 / 3357.4 / 3714.8），
     * 无缓存中位 **4197.9 ms**（3664.3 / 4197.9 / 4746.5）
     * → **省 840.5 ms（25.0%）**。
     *
     * 这是目前唯一一条实测有效的冷启优化：realpath 缓存（+7.1%）、
     * v8-pool-size（−2.1%，噪声）、Node 24（慢 30~70%）全部已否证。
     *
     * ## 为什么用 zip 而不是 tar.gz、为什么在 Kotlin 里解
     *
     * 最初版本是「tar.gz + 让容器内的 tar 解」，**这条路根本不成立**：
     * [LinuxContainerRunner.shell] 走的是 [EngineConfig.linuxContainerArgv]，
     * 那个函数只返回 `runner + rootfs + interp`，**一条 `-m` 挂载都不加**。
     * 所以容器里既没有 `/eng` 也没有 `/dsh`，`tar xzf /eng/...` 必然 ENOENT
     * （实测确认：容器内 `ls /eng` 报 No such file or directory）。
     *
     * 改成 zip 后纯 Kotlin 解压，**完全不依赖容器**：更快、无需 rootfs 就绪、
     * 也不占 [LinuxContainerRunner] 的并发许可（那个 Semaphore 只有 3 个名额，
     * 冷启路径上抢名额可能让真正要紧的调用白等）。
     * 代价是 zip 比 tar.gz 大一些（3.16 MB vs 2.55 MB），可接受。
     *
     * ## 三个必须照做的约束（都实测踩过）
     *
     * 1. **目录名里的 arch 是硬约定**。缓存目录叫 `v<node版本>-<process.arch>-<hash>-0`，
     *    改了名 Node 就不再认它 —— 实测把 `x64` 改成 `arm64` 后耗时 5715.8 ms
     *    （等于无缓存），且 Node 自己又新建了一个 `x64` 目录。
     *    所以按 [NODE_ARCH] 拼目录名，不能写死。
     *
     * 2. **必须在 node 找不到缓存时才解**。已有内容时不覆盖，否则每次冷启都
     *    白解 1281 个文件，把收益吃掉。
     *
     * 3. **版本不匹配就整个跳过**。包里的目录是打进来时那个 node 版本的；
     *    引擎升级 node 后旧缓存不仅无用，还会被 [pruneCompileCache] 删掉。
     *    这里先比对版本前缀，不匹配就一个文件都不解。
     *
     * 失败只记日志、不影响启动：预置缓存是纯优化，缺了就该退回「第一次慢一点」。
     */
    fun seedCompileCache() {
        // 入口留痕：这条日志是「到底有没有被调用」的唯一判据。
        // 曾经因为没有它，卡了两轮才定位到 endsWith 那个恒假条件。
        Log.i(TAG, "seed cache: enter")
        val root = File(EngineConfig.dshHome(ctx), ".node-compile-cache")
        // 版本前缀直接来自包内目录名（见 [seededCachePrefix] 的说明：
        // 读 node 二进制头部找版本串是死路，两个 node 的版本串都在 10MB 之外）
        val want = seededCachePrefix() ?: return
        // ★arch 在第 2 段，**不是**结尾段。完整形态是 `v<版本>-<arch>-<hash>-0`：
        //   `v24.18.0-x64-cf738c9d-0` 的结尾是 `-0`（计数器），不是 `-x64`。
        //   写成 endsWith("-x64") 会恒为假 → seed 每次都在这行静默退出，
        //   表现为「代码看着对、日志里什么都没有、缓存就是不出现」（实测踩到）。
        val bundleArch = want.split('-').getOrNull(1)
        if (bundleArch != NODE_ARCH) {
            // 包是给另一种 ABI 打的（例如把 arm64 的包塞进 x86_64 设备）。
            // 解出来 Node 也不认，直接跳过并留一行日志便于排查。
            Log.i(TAG, "seed cache skipped: bundle arch $bundleArch != device $NODE_ARCH")
            return
        }
        // 已有当前版本的缓存目录就不再解（约束 2）
        if (root.listFiles { f -> f.isDirectory && f.name.startsWith(want) }?.isNotEmpty() == true) {
            Log.i(TAG, "seed cache: already present ($want)")
            return
        }

        val t0 = System.currentTimeMillis()
        var files = 0
        runCatching {
            ctx.assets.open("linux/$COMPILE_CACHE_ASSET").use { ins ->
                java.util.zip.ZipInputStream(ins.buffered()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (e.isDirectory) continue
                        // 包内路径形如 `v24.18.0-x64-cf738c9d-0/<hash>`。
                        // ★不能无条件 mkdirs：entry 名来自 APK 资产，虽然可信，
                        //   但仍然拒绝任何含 .. 的路径，避免把文件写到 DSH_HOME 之外。
                        if (e.name.contains("..")) continue
                        val out = File(root, e.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o -> zin.copyTo(o) }
                        files++
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "seed cache: unzip failed after $files file(s): ${it.message}")
            // 半成品会让下次冷启误判「已有缓存」而不重试，这里清掉不完整目录
            runCatching { File(root, want).takeIf { d -> !d.isDirectory || d.list() == null }?.delete() }
            return
        }

        val ok = File(root, want).let { d -> d.isDirectory && (d.list()?.isNotEmpty() == true) }
        if (ok) {
            Log.i(TAG, "seeded compile cache: $files file(s) under $want in ${System.currentTimeMillis() - t0}ms")
        } else {
            // 版本不匹配（包内目录名与 want 不同）：清掉解出来的东西，保持 env 干净
            runCatching { root.listFiles { f -> f.isDirectory }?.forEach { it.deleteRecursively() } }
            Log.i(TAG, "seeded cache discarded (want=$want, got ${root.list()?.joinToString(",")}); engine will rebuild")
        }
    }

    /**
     * 供冷启路径调用（[LinuxEngineLauncher.readiness]）。
     *
     * 为什么需要单独的公开入口：冷启**不经过 [ensureDeployed]**，只在
     * [status] 上判断 READY。把兼容层只挂在部署路径上等于永远不执行。
     */
    fun installCompatShimsPublic() {
        val root = runCatching { rootfsDir(activeOption()) }.getOrElse {
            Log.w(TAG, "compat shims: activeOption failed: ${it.message}")
            return
        }
        Log.i(TAG, "compat shims: root=${root.absolutePath} exists=${root.isDirectory}")
        // 落盘留痕：logcat 在部分 ROM（OPPO ColorOS）上被限流/丢弃，
        // 排查「这段代码到底有没有执行」时日志不可靠。写文件最直白。
        runCatching {
            File(ctx.filesDir, "engine/compat-shims.trace")
                .writeText("${System.currentTimeMillis()} root=${root.absolutePath} exists=${root.isDirectory}" + System.lineSeparator())
        }
        installLinuxCompatShims(root)
    }

    /**
     * 建 POSIX 共享内存目录 `<rootfs>/dev/shm`（权限 1777，同 tmpfs 惯例）。
     *
     * Android 宿主没有 /dev/shm，而 /dev 是整棵透传的，所以必须由容器自己提供。
     * 幂等：已存在就只校正权限。
     */
    private fun ensureShmDir(root: File) {
        runCatching {
            val shm = File(root, "dev/shm")
            if (!shm.isDirectory) {
                shm.mkdirs()
                Log.i(TAG, "dev/shm created: ${shm.absolutePath}")
            }
            // 1777 = 粘滞位 + 全权限，与标准 tmpfs 的 /dev/shm 一致
            shm.setReadable(true, false)
            shm.setWritable(true, false)
            shm.setExecutable(true, false)
        }.onFailure { Log.w(TAG, "dev/shm setup failed: " + it.message) }
    }

    private fun installLinuxCompatShims(root: File) {
        runCatching {
            val shDir = File(root, "system/bin").apply { mkdirs() }
            listOf("sh", "bash").forEach { name ->
                val link = File(shDir, name)
                if (!link.exists()) Files.createSymbolicLink(link.toPath(), File("/bin/sh").toPath())
            }
        }.onFailure { Log.w(TAG, "system/bin shim failed: ${it.message}") }

        runCatching {
            val dst = File(root, "bin/rg")
            val stamp = File(root, ".dsh-rg-version")
            // 幂等判据用**标记文件**而不是比长度：APK 里的 asset 是压缩存储，
            // AssetManager.open().available() 返回值不可靠（可能只报当前缓冲长度），
            // 拿它跟落地文件长度比会每次都不等 → 每次启动都重写 4.7MB。
            val want = LINUX_RG_ASSET
            // x86_64 没有随包的静态 rg：静默跳过，别让兼容层抛错，也别装出
            // 一个空文件来「假装成功」（那会让 require.resolve 命中却 exec 失败）。
            val have = if (want == null) null else runCatching { stamp.readText().trim() }.getOrNull()
            val stale = want != null && (!dst.isFile || have != want || !dst.canExecute())
            if (want != null && stale) {
                dst.delete()
                ctx.assets.open(want).use { ins ->
                    dst.outputStream().use { out -> ins.copyTo(out) }
                }
                dst.setExecutable(true, false)
                stamp.writeText(want)
                Log.i(TAG, "installed static rg -> ${dst.absolutePath} (${dst.length()} bytes)")
            }
        }.onFailure { Log.w(TAG, "static rg install failed: ${it.message}") }

        // 帧服务：容器里用宿主 GPU 渲染、把画面写进共享文件给 App 贴图。
        // 见 LinuxFrameSurface 与镜像仓 docs/benchmark.md 6.7。
        // 装成 `$root/framesrv`（rootfs 内绝对路径 /framesrv），因为容器里
        // 拿不到 APK 的 assets，只能提前抽出来。
        runCatching {
            val want = FRAMESRV_ASSET ?: return@runCatching
            val dst = File(root, "framesrv")
            val stamp = File(root, ".dsh-framesrv-version")
            // 戳里要记「asset 路径 + 字节数」，不能只记路径：二进制内容更新但
            // 路径不变时旧逻辑不会重装，用户会一直跑旧 framesrv（实测踩过：
            // 换成 AHB 版本后设备上仍是 11728 字节的旧文件）。
            val wantStamp = want + ":" + assetSizeOf(ctx, want)
            val have = runCatching { stamp.readText().trim() }.getOrNull()
            val stale = !dst.isFile || have != wantStamp || !dst.canExecute()
            if (stale) {
                dst.delete()
                ctx.assets.open(want).use { ins ->
                    dst.outputStream().use { out -> ins.copyTo(out) }
                }
                dst.setExecutable(true, false)
                stamp.writeText(wantStamp)
                Log.i(TAG, "installed framesrv -> ${dst.absolutePath} (${dst.length()} bytes)")
            }
        }.onFailure { Log.w(TAG, "framesrv install failed: ${it.message}") }

        // X11 画面服务与触摸注入服务。与 framesrv 同一套幂等戳模式，
        // 但**戳里带字节数**（`assetSizeOf`）—— 只写资产名的话，换了同名
        // 但内容不同的产物（同一次开发里改了代码重编）会被判成「已是最新」，
        // 设备上留的还是旧二进制。这个坑在 framesrv 上实测踩过。
        installLinuxTool(ctx, root, X11FRSRV_ASSET, "x11frsrv")
        installLinuxTool(ctx, root, INPUTD_ASSET, "inputd")
    }

    /**
     * 把一个随包工具装进 rootfs。
     *
     * 幂等戳 = `资产路径:字节数`。为什么不用 mtime：APK 里 asset 的 mtime 不可信
     * （构建时被规整），换了产物也看不出变化。为什么不用纯路径：同一次开发里
     * 改代码重编会在同一路径产出不同内容，纯路径判「已就绪」就永远不更新。
     *
     * 失败只 warn 不抛：这些是**可选增强**（没有 x11frsrv 最多是没画面，
     * 没有 inputd 最多是不能触摸），不该让整个 rootfs 部署失败。
     */
    private fun installLinuxTool(ctx: android.content.Context, root: File, asset: String?, name: String) {
        if (asset == null) return
        runCatching {
            val dst = File(root, name)
            val stamp = File(root, ".dsh-$name-version")
            val wantSize = assetSizeOf(ctx, asset)
            val want = "$asset:$wantSize"
            val have = runCatching { stamp.readText().trim() }.getOrNull()
            val stale = !dst.isFile || have != want || !dst.canExecute()
            if (stale) {
                dst.delete()
                ctx.assets.open(asset).use { ins ->
                    dst.outputStream().use { out -> ins.copyTo(out) }
                }
                dst.setExecutable(true, false)
                stamp.writeText(want)
                Log.i(TAG, "installed $name -> ${dst.absolutePath} (${dst.length()} bytes)")
            }
        }.onFailure { Log.w(TAG, "$name install failed: ${it.message}") }
    }

    /**
     * 取 asset 的字节数，用于幂等戳。
     *
     * 为什么要这么绕：`AssetManager.openFd()` 对**压缩**进 APK 的 asset 会抛
     * `FileNotFoundException: This file can not be opened as a file descriptor;
     * it is compressed`。framesrv 是几 KB 的可执行文件，AGP 会按默认规则压缩，
     * 所以 openFd 这条路不可靠。优先试 openFd（未压缩时最省），失败则退到
     * 流式读长度 —— 只在部署判定时跑一次，几 KB 的量没有性能顾虑。
     */
    private fun assetSizeOf(ctx: Context, name: String): Long = runCatching {
        ctx.assets.openFd(name).use { it.length }
    }.getOrElse {
        runCatching {
            ctx.assets.open(name).use { ins ->
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                }
                total
            }
        }.getOrDefault(0L)
    }

    /**
     * 版本标签：写进 `.dsh-deployed` 第一行，用于幂等判定。
     * Alpine 沿用老的常量（老用户标记里就是这个值，改了会让所有人都重装一次）；
     * glibc 发行版用 `家族-镜像标识`。
     */
    private fun versionTag(option: LinuxRootfsCatalog.Option): String = when (option.family) {
        // 3.22 沿用老常量（老用户标记里就是这个值，改了会让所有人重装一次）；
        // 3.23 是新家族，用可读的版本串
        LinuxRootfsCatalog.Family.ALPINE -> ASSET_VERSION
        LinuxRootfsCatalog.Family.ALPINE_EDGE -> "alpine-3.23.8"
        LinuxRootfsCatalog.Family.UBUNTU -> "ubuntu-24.04.5-base"
    }

    /**
     * 下载 glibc rootfs 到 cacheDir。已存在且哈希正确时直接复用。
     *
     * 校验纪律：**必须有 sha256 pin**，下载器本身也拒绝无 pin 的下载
     * （见 [HttpDownloader.download]）。镜像链任一可达即可，哈希把所有源
     * 钉在同一份内容上。
     */
    private fun downloadRootfs(
        option: LinuxRootfsCatalog.Option,
        src: LinuxRootfsCatalog.RootfsSource.Remote,
    ): File {
        val cacheDir = File(ctx.cacheDir, "linux-rootfs").apply { mkdirs() }
        val dest = File(cacheDir, "${option.family.id}-rootfs." + when (src.format) {
            LinuxRootfsCatalog.ArchiveFormat.TAR_GZ -> "tar.gz"
            LinuxRootfsCatalog.ArchiveFormat.TAR_XZ -> "tar.xz"
        })
        // 已下载且校验通过：跳过重复下载（30MB 不算小）
        if (dest.isFile && dest.length() == src.compressedBytes) {
            val actual = RuntimeInstaller.sha256(dest)
            if (actual.equals(src.sha256, ignoreCase = true)) {
                Log.i(TAG, "rootfs archive already cached (${dest.name})")
                return dest
            }
            dest.delete()
        }
        val r = HttpDownloader(ctx).download(
            urls = src.urls,
            dest = dest,
            expectedSha256 = src.sha256,
            expectedSize = src.compressedBytes,
        )
        Log.i(TAG, "rootfs downloaded from ${r.url} (${r.bytes} bytes)")
        return dest
    }

    /** 按格式打开压缩流（gz / xz） */
    private fun openArchive(file: File, format: LinuxRootfsCatalog.ArchiveFormat): java.io.InputStream {
        val raw = java.io.BufferedInputStream(file.inputStream(), 64 * 1024)
        return when (format) {
            LinuxRootfsCatalog.ArchiveFormat.TAR_GZ ->
                java.util.zip.GZIPInputStream(raw, 64 * 1024)

            LinuxRootfsCatalog.ArchiveFormat.TAR_XZ ->
                org.tukaani.xz.XZInputStream(raw)
        }
    }

    /**
     * 写发行版专属配置（hosts / resolv.conf / 镜像源 / glibc 补丁）。
     *
     * 都是**幂等**的：内容没变就不写盘，避免抹掉用户手改的镜像配置。
     */
    /**
     * 供 [LinuxContainerRunner] 在每次 ensureReady 时重申配置（幂等）。
     *
     * ★这里**绝不起容器**：只做「读-比较-可能写」几个小文件，O(1)。
     * 起容器那步（glibc quirks）只在部署时做一次 —— 原因见 [runQuirks]。
     */
    fun writeDistroConfigPublic(option: LinuxRootfsCatalog.Option) {
        writeDistroConfig(option, rootfsDir(option), runQuirks = false)
    }

    /**
     * @param runQuirks 是否执行 `dsh-glibc-quirks.sh`。
     *
     * **只有部署（ensureDeployed）可以传 true。** 原因是这条命令要 fork 一个
     * 容器进程，而 Android 的 Cgroup Freezer 会在息屏/后台把整个 App 进程组
     * 冻在 `do_freezer_trap`（实测 74 个线程全部停在那里）。容器子进程跟着
     * 冻住 → `waitFor(60_000L)` 一直不返回 → **Ubuntu 模式冷启凭空多等 60 秒**，
     * 而 Alpine 因为不走 glibc 分支只要 150ms，症状就像「Ubuntu 特别慢」。
     *
     * 部署是用户显式动作（有前台 UI、不会在几百毫秒内被冻），跑一次没问题；
     * 启动路径必须一次容器 fork 都不做。
     */
    private fun writeDistroConfig(
        option: LinuxRootfsCatalog.Option,
        root: File,
        runQuirks: Boolean = false,
    ) {
        val nl = "\n"
        fun writeIfChanged(rel: String, content: List<String>) {
            if (content.isEmpty()) return
            val want = content.joinToString(nl) + nl
            val f = File(root, rel)
            // resolv.conf 在部分镜像里是指向 systemd-resolved 的符号链接，
            // 直接写会写穿到不存在的目标 —— 先删链接再写实体文件。
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) f.delete()
            if (!f.isFile || f.readText() != want) {
                f.parentFile?.mkdirs()
                f.writeText(want)
                Log.i(TAG, "wrote /$rel (${option.family.id})")
            }
        }

        writeIfChanged("etc/hosts", HOSTS)
        // ★resolv.conf 必须按 libc 分叉：
        //   glibc 加 `options use-vc` 强制走 TCP 查询 —— 这是唯一能穿透 Android
        //   netd/SELinux 对 UDP 53 封锁的办法（实测：加之前 getent 报 EAI_AGAIN、
        //   curl 报 http=000、apt 装不上包；加之后 curl http=200、apt 装 jq 成功）。
        //   musl **不支持** use-vc（源码里没有这个选项，实测 nslookup 直接
        //   `write to '223.5.5.5': Operation not permitted`），改用它认得
        //   `nameserver <ip>:<port>` 的能力，指向 App 内的本地 DNS 代理
        //   （见 [LinuxDnsProxy]）。代理起不来时退回公共 DNS 并降级为「只能靠
        //   hosts + 预置索引」，至少不让配置写坏。
        if (option.needsGlibcQuirks) {
            writeIfChanged("etc/resolv.conf", RESOLV_CONF_GLIBC)
        } else if (Sandbox.isChrootPreferred(ctx)) {
            // ★chroot 模式必须写**真实公共 DNS**，不能写 127.0.0.1。
            //
            // 免 root 的 seccomp 路线里，`nameserver 127.0.0.1` 是对的：容器内
            // musl 把 DNS 端口硬编码成 53，而 App uid 绑不上 53，所以由 loader
            // 双向改写端口指向 App 内的 [LinuxDnsProxy]。
            //
            // 但 chroot 路线**没有 loader**（内核直接改根目录，不经 SIGSYS），
            // 端口改写整段失效 → 包发往 127.0.0.1:53 无人监听，实测症状是
            //     nslookup: write to '127.0.0.1': Connection refused
            //     ;; connection timed out; no servers could be reached
            // 而 root 身份**本来就可以直接连真实 53**（不需要特权端口技巧）。
            // 实测写 `nameserver 223.5.5.5` 后容器内 `apk update` 直接成功：
            //     OK: 26336 distinct packages available
            //
            // 所以这里按「会不会有 loader」分流，而不是按「代理在不在跑」。
            writeIfChanged("etc/resolv.conf", RESOLV_CONF)
            Log.i(TAG, "chroot sandbox: resolv.conf -> public DNS (no loader to rewrite 53)")
        } else {
            val proxyPort = LinuxDnsProxy.start(ctx)
            if (proxyPort > 0) {
                // ★固定写「不带端口」—— 端口由 loader 改写，见下方说明。
                //
                // musl 的 resolver 把 DNS 端口硬编码成 53，`nameserver 127.0.0.1:15353`
                // 里的 `:15353` 会被**整段忽略**（实测决定性实验：root 在 53 与 35931
                // 各起记录器，写 35931 时 53 收到 33/60 字节、35931 收到 0）。
                // 所以 resolv.conf 里写什么端口都没用，只能写纯 IP。
                //
                // 真正的桥接在 dsh_alpine 里：
                //   出程  sendto/sendmsg  把 127.0.0.1:53 改写成 127.0.0.1:<proxyPort>
                //   回程  recvfrom/recvmsg 把来源 <proxyPort> 改回 53
                // 回程这一步**必须有**：musl 的 __res_msend_rc 会校验应答来源端口等于
                // 它发的目标端口，只改出程会让它把应答整包丢弃，表现为 sendto 成功、
                // 代理日志也有 replied，但 getaddrinfo 仍返回 EAI_AGAIN。
                writeIfChanged("etc/resolv.conf", listOf("nameserver 127.0.0.1"))
                Log.i(TAG, "DNS proxy on $proxyPort; loader rewrites 53<->$proxyPort")
            } else {
                Log.w(TAG, "DNS proxy unavailable; musl net access will be degraded")
                writeIfChanged("etc/resolv.conf", RESOLV_CONF)
            }
        }

        when (option.pkgManager) {
            LinuxRootfsCatalog.PackageManager.APK ->
                // 按发行版版本取仓库（3.23 不能写 3.22 的索引，见 apkRepositories）
                writeIfChanged("etc/apk/repositories", apkRepositories(option))

            LinuxRootfsCatalog.PackageManager.APT -> {
                // noble 只读 deb822 的 sources.list.d/ubuntu.sources；
                // 写 etc/apt/sources.list 是死码（实测 apt 仍走官方源）。
                writeIfChanged(
                    LinuxRootfsCatalog.APT_SOURCES_REL,
                    LinuxRootfsCatalog.aptSources(option),
                )
                // 旧布局留下的一行式源要清掉，否则两个源同时生效会重复拉索引
                val legacy = File(root, "etc/apt/sources.list")
                if (legacy.isFile && legacy.readText().contains("mirrors.")) {
                    legacy.delete()
                    Log.i(TAG, "removed legacy etc/apt/sources.list (${option.family.id})")
                }
                writeIfChanged("etc/apt/apt.conf.d/99dsh", LinuxRootfsCatalog.APT_CONF)
            }
        }

        if (option.needsGlibcQuirks) {
            // apt/gpgv 的签名校验子进程会以 `_apt` 用户往 /tmp 写临时文件。
            // 镜像里 /tmp 是 0755（不是 1777），`_apt` 写不进去，实测报
            // `Couldn't create temporary file /tmp/apt.conf.XXXX for passing
            // config to apt-key`。容器里我们不区分用户，放宽到 1777 即可。
            File(root, "tmp").let { t ->
                if (t.isDirectory) {
                    runCatching { t.setReadable(true, false); t.setWritable(true, false); t.setExecutable(true, false) }
                }
            }

            val script = File(root, "etc/dsh-glibc-quirks.sh")
            script.parentFile?.mkdirs()
            script.writeText(LinuxRootfsCatalog.glibcQuirksScript())
            script.setExecutable(true, false)

            // ★执行**只做一次**，靠标记文件判据。
            //
            // 这段代码所在的 writeDistroConfig 被 readiness() 每次冷启都调一次
            // （写 hosts / resolv.conf / 源 / quirks 都是幂等的文件比较，本身很便宜），
            // 但这里起容器是**昂贵**的：一次完整 fork dsh_alpine + 载入 interp +
            // 跑脚本。更致命的是它要抢 LinuxContainerRunner.SEM（只有 3 个许可），
            // 而 ensureReady() 也要抢 —— 一旦许可被占满，tryAcquire 会等满
            // timeoutMs=60_000L，表现为 **Ubuntu 模式 spawn 固定卡 60 秒**
            // （Alpine 不触发这段，所以只有 150ms）。
            //
            // 脚本内容是纯幂等的（ldconfig shim / 清 apt partial），跑一次就够；
            // 部署/修复时 rootfs 被重解包会把标记一起清掉，自然会再跑一次。
            val quirksDone = File(root, "etc/dsh-glibc-quirks.done")
            if (runQuirks && !quirksDone.isFile) {
                val r = LinuxContainerRunner.get(ctx).run(
                    guestPath = "/bin/sh",
                    args = listOf("/etc/dsh-glibc-quirks.sh"),
                    timeoutMs = 60_000L,
                    skipReady = true,
                )
                // ★标记**无论成败都写**。
                //
                // 只在 r.ok 时写会造成死循环：容器跑不起来（或抢不到 SEM 许可而超时）
                // → 标记不写 → 下次冷启再试一遍 → 再等一遍 timeoutMs。
                // 本意是「部署后跑一次让 ldconfig shim 立刻生效」，代价不该是
                // 每次启动都可能白等 60 秒。真失败时用户可以在终端里手动跑
                // /etc/dsh-glibc-quirks.sh，或点「修复」重装（会清掉标记）。
                quirksDone.writeText("${r.exitCode}@${System.currentTimeMillis()}")
                if (r.ok) {
                    Log.i(TAG, "glibc quirks applied")
                } else {
                    Log.w(TAG, "glibc quirks failed rc=${r.exitCode}: " + r.combined().take(300))
                }
            }
        }
    }

    /**
     * 装基础包（bash / curl / ca-certificates）。**幂等**：装过就跳过。
     *
     * 为什么需要：极简 Alpine rootfs 只有 busybox 的 `sh`(ash)，没有 `/bin/bash`。
     * DSH 的 bash 工具（`@deepseek-ai/dsh-bash-local` 的 `execute()`）固定调用
     * `bash -c <cmd>`，不会退回 sh —— 于是**每一条 AI 命令都 spawn 失败**，
     * 用户看到的现象是「AI 调用不了任何命令」。这是「容器里一堆工具不可用」
     * 最直接的一条根因。
     *
     * 必须在部署后跑一次、而不是查 rootfs 里有没有 bash：apk 装包需要网络，
     * 而部署本身是离线解包。所以用标记文件区分「已尝试」，避免每次启动都 apk。
     */
    /**
     * 装基础包（bash / curl / ca-certificates）。**幂等**：装过就跳过。
     *
     * 为什么需要：极简 Alpine rootfs 只有 busybox 的 `sh`(ash)，没有 `/bin/bash`。
     * DSH 的 bash 工具（`@deepseek-ai/dsh-bash-local` 的 `execute()`）固定调用
     * `bash -c <cmd>`，不会退回 sh —— 于是**每一条 AI 命令都 spawn 失败**，
     * 用户看到的现象是「AI 调用不了任何命令」。这是「容器里一堆工具不可用」
     * 最直接的一条根因。
     *
     * 必须在部署后跑一次、而不是查 rootfs 里有没有 bash：装包需要网络，
     * 而部署 Alpine 本身是离线解包。所以用标记文件区分「已尝试」，避免每次启动
     * 都装一遍。
     *
     * glibc 发行版走**两步法**（见 [installPackagesApt]）：一步装实测会失败。
     */
    // ==================== glibc 发行版的 Node 安装 ====================

    /** 下载到本地后 node tarball 在 cacheDir 里的文件名 */
    private fun nodeTarballFile(tb: LinuxRootfsCatalog.NodeTarball): File =
        File(File(ctx.cacheDir, "linux-node").apply { mkdirs() }, "node-${tb.version}-linux-arm64.tar.xz")

    /**
     * 把官方预编译 node 装进 glibc 发行版的 rootfs（`/opt/node`）。
     *
     * ## 为什么必须有这个方法（v1.7.13）
     *
     * [LinuxRootfsCatalog.NodeTarball] 从 13a46b5b 起就只定义了元数据（版本、URL、
     * sha256、体积、安装目录），**全仓没有任何代码消费它** —— 于是 Ubuntu 这类
     * `NodeStrategy.OFFICIAL_TARBALL` 的发行版永远停在「缺 node」：
     * `readiness()` 检查 `/opt/node/bin/node` 不存在就返回 `NeedsPackages`，
     * 而 UI 的「补齐依赖」走 `pkg.install(listOf("nodejs"))`（apt 源里没有
     * Node 24），装完再看还是缺 —— 用户看到的就是「删了重装、补齐依赖还是错」。
     *
     * ## 形态
     *
     * 用 [HttpDownloader]（带 sha256 pin、多源回退、断点续传），解 `tar.xz` 到
     * rootfs 根的临时目录，再把 `node-v<ver>-linux-arm64/` 整体移成 `/opt/node`。
     * 官方包内部是相对链接，不需要重写绝对链接。
     *
     * @return 是否装好（`/opt/node/bin/node` 存在且可执行）
     */
    fun installNodeTarball(option: LinuxRootfsCatalog.Option): Boolean {
        if (option.nodeStrategy != LinuxRootfsCatalog.NodeStrategy.OFFICIAL_TARBALL) return true
        val root = rootfsDir(option)
        val tb = LinuxRootfsCatalog.nodeTarball
        val target = File(root, tb.installDir.trimStart('/'))
        val bin = File(target, "bin/node")
        if (bin.isFile) return true

        return try {
            val archive = nodeTarballFile(tb)
            if (!archive.isFile || archive.length() != tb.bytes ||
                !RuntimeInstaller.sha256(archive).equals(tb.sha256, ignoreCase = true)
            ) {
                HttpDownloader(ctx).download(
                    urls = tb.urls,
                    dest = archive,
                    expectedSha256 = tb.sha256,
                    expectedSize = tb.bytes,
                )
            }
            // 解到 rootfs 内的临时目录：官方包里顶层是 node-v<ver>-linux-arm64/，
            // 先解到 staging 再整体 rename，避免中途失败留下半个 /opt/node。
            val staging = File(root, ".dsh-node-staging")
            staging.deleteRecursively()
            staging.mkdirs()
            val pending = mutableListOf<LinkJob>()
            openArchive(archive, LinuxRootfsCatalog.ArchiveFormat.TAR_XZ).use { ins ->
                untar(ins, staging, pending)
            }
            materialiseLinks(staging, pending)
            val inner = staging.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("node-") }
            if (inner == null) {
                Log.w(TAG, "node tarball has no node-* directory at top level")
                staging.deleteRecursively()
                return false
            }
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!inner.renameTo(target)) {
                inner.copyRecursively(target, overwrite = true)
                inner.deleteRecursively()
            }
            staging.deleteRecursively()
            Log.i(TAG, "node installed at ${target.absolutePath} (version ${tb.version})")
            bin.isFile
        } catch (e: Throwable) {
            Log.w(TAG, "node tarball install failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
    fun ensureBootstrapPackages(option: LinuxRootfsCatalog.Option) {
        val root = rootfsDir(option)
        /*
         * ★标记文件按 **flavor 分开**，不共用。
         *
         * 共用会出现这个问题：用户先用 BASE 形态（标记 = "bash,curl,ca-certificates"），
         * 之后切到 GUI 形态 —— 标记已存在 → 直接 return → **额外包永远不装**，
         * 用户点了「开始画面」得到 `Xvfb: not found`，而 UI 一片正常。
         *
         * 分开之后每个 flavor 各自幂等：切到没装过的形态会装一次，装过就不再重装。
         * 代价是同一批基础包可能被不同 flavor 各 `apk add` 一次 —— apk 对已装包
         * 是 O(1) 跳过，实测几毫秒，比「静默没装上」便宜得多。
         */
        // 同上：解包/首装前先把掉分类的符号链接修好，否则 apt 系的 dpkg 一定死在
        // 「dereference existing symlink: Permission denied」。
        if (option.pkgManager == LinuxRootfsCatalog.PackageManager.APT) {
            runCatching { SelinuxContext.healLinksIfDrifted(ctx, root) }
                .onFailure { Log.w(TAG, "bootstrap symlink heal failed: " + it.message) }
        }
        val mark = File(root, BOOTSTRAP_MARKER + if (option.flavor == LinuxRootfsCatalog.Flavor.BASE)
            "" else "." + option.flavor.id)
        if (mark.isFile) {
            /*
             * ★标记有两种语义，不能一律当成「已就绪」（v1.6.17）。
             *
             * 成功写 `bash,curl,ca-certificates`；
             * 失败写 `attempted:bash,curl,ca-certificates`（失败也落标记，
             * 否则每次冷启都重跑一遍联网装包，Ubuntu 上实测白等 35~37 秒）。
             *
             * 但「attempted」只该抑制**同一次会话内**的重复尝试，不该永久
             * 等于就绪 —— 一旦装包失败（当时没网 / DNS 代理没起来），
             * 标记会永远停在 attempted，而 bash 始终不存在。后果是容器里
             * 32 个门脚本全部 `exec /bin/bash: not found`，冷启 readiness()
             * 却因为「只查 node」判定 Ready，UI 一片正常、功能全废。
             *
             * 现在的判据：attempted 标记 + 目标文件确实缺 → 允许再试一次。
             * 成功标记则无条件早退（文件被人手动删掉属用户操作，不代他恢复）。
             *
             * ★合并（2026-11-09）：`attempted:` 语义与「按 flavor 分标记」正交，
             *   两条线各自修的是不同的坑，故合并后**同时生效**：先按 flavor 取标记，
             *   再按前缀区分「已成功」与「只尝试过」。
             */
            val marker = runCatching { mark.readText().trim() }.getOrNull().orEmpty()
            val attemptedOnly = marker.startsWith("attempted:")
            if (!attemptedOnly) return
            val stillMissing = BOOTSTRAP_PROBE_FILES.any { !File(root, it).isFile }
            // ★flavor 的额外包也必须参与「要不要重试」的判断（v1.7.16）。
            //   只查那三个基础文件会有个死角：DEV/GUI 形态额外装 build-base、
            //   python3 等，它们失败时基础三个文件仍可能在（上一个形态装过），
            //   于是 stillMissing=false → 直接 return → 标记永远停在 attempted，
            //   而开发工具一个都没装上，用户点「补齐依赖」也补不上。
            //   判据用「apk/dpkg 自己的包状态」而不是猜文件路径：
            //   python3 的主程序叫 python3.12，猜路径必漏。
            val flavorMissing = if (stillMissing) {
                emptyList()
            } else {
                runCatching {
                    when (option.pkgManager) {
                        LinuxRootfsCatalog.PackageManager.APK ->
                            verifyApkInstalled(option.flavor.extraPackages)

                        LinuxRootfsCatalog.PackageManager.APT ->
                            verifyAptInstalled(option.flavor.extraPackages)
                    }
                }.getOrElse {
                    // 容器起不来时别把重试也堵死：当作「有缺失」，让它再试一次。
                    Log.w(TAG, "flavor package probe failed: " + it.message)
                    option.flavor.extraPackages
                }
            }
            if (!stillMissing && flavorMissing.isEmpty()) return
            Log.w(
                TAG,
                "bootstrap marker says attempted but packages still missing " +
                    "(base=" + BOOTSTRAP_PROBE_FILES.filter { !File(root, it).isFile }.joinToString(",") +
                    " flavor=" + flavorMissing.joinToString(",") + "); retrying",
            )
        }
        // glibc 发行版的 node 不在 apt 源里（需要 >= 24.9，镜像里只有 18.x），
        // 单独走官方 tarball。它必须和基础包一起装完，否则 readiness() 立刻
        // 判 NeedsPackages，用户点「补齐依赖」也补不上（apt 装 nodejs 是 18.x）。
        if (option.nodeStrategy == LinuxRootfsCatalog.NodeStrategy.OFFICIAL_TARBALL) {
            runCatching { installNodeTarball(option) }
                .onFailure { Log.w(TAG, "node tarball install failed: " + it.message) }
        }

        val pkgs = BOOTSTRAP_PACKAGES + option.flavor.extraPackages
        val ok = when (option.pkgManager) {
            LinuxRootfsCatalog.PackageManager.APK -> {
                // ★不要在这里额外跑 `apk update`。
                //
                // 我一度以为「全新 minirootfs 没有包索引 → apk add 必失败」，
                // 于是加了一次 apk update。**那是假根因，已撤回**。实测：
                //
                //   $ apk add --no-progress bash          # var/cache/apk 为空
                //   fetch .../APKINDEX.tar.gz             # ← apk add 自己会抓
                //   (1/4) Installing ncurses-terminfo-base ...
                //   rc=0
                //
                // `apk add` 在索引缺失时会自动 fetch，不需要前置 update。
                // 当时看到失败，是因为**没带 DSH_DNS_PORT**：musl 把 DNS 发到 53
                // 无人监听 → fetch 报 temporary error → 回落成 "no such package"。
                // 带上端口后同样空索引一次成功。
                //
                // 所以这里只做一件事：add。多跑一次 update 是白白多一轮联网，
                // 在慢网/离线时还会多等一个超时。
                val r = LinuxContainerRunner.get(ctx).run(
                    guestPath = "/sbin/apk",
                    // --no-chown：同 LinuxPackageManager.install 里的说明 ——
                    // 非 root 容器下 apk 改属主必失败。
                    // ★合并（2026-11-09）：参数用 pkgs（= BOOTSTRAP_PACKAGES +
                    //   option.flavor.extraPackages），不能退成 BOOTSTRAP_PACKAGES，
                    //   否则切到 DEV / GUI 形态时额外包永远不装。
                    args = listOf("add", "--no-progress", "--no-chown") + pkgs,
                    timeoutMs = 600_000L,
                    // 必须跳过 ensureReady：它会回调本方法，形成
                    // run -> ensureReady -> ensureBootstrapPackages -> run 的死循环
                    // （实测表现：启动变慢、.dsh-bootstrap 永远不出现）。
                    skipReady = true,
                )
                if (!r.ok) {
                    // ★别只打 combined()：它会把空行 trim 成空串，看起来像"没报错"。
                    // 这里把 rc 与两路长度都打出来，否则「bootstrap failed: 」后面
                    // 一片空白，完全无法判断是没网、索引空、还是被杀。
                    Log.w(
                        TAG,
                        "bootstrap failed (will retry next boot): rc=${r.exitCode} " +
                            "timedOut=${r.timedOut} " +
                            "stdout=${r.stdout.length}B stderr=${r.stderr.length}B " +
                            r.combined().take(400),
                    )
                }
                // ★退出码 0 不等于装上了（v1.7.16，真机 2026-11-13）。
                //   apk 在部分包失败时**退出码仍是 0 之外的预期码**，但更常见的
                //   陷阱是「索引抓不到 → 逐包 temporary error → 只装了缓存里那几个」。
                //   实测 Alpine dev 形态：build-base/git/python3/py3-pip 全缺，
                //   apk 报了 12 个 ERROR，而 BOOTSTRAP_PROBE_FILES 只查 bash/curl/
                //   ca-certificates 三个基础文件 —— 三个都在，于是写下「成功」标记。
                //   核验必须按**实际请求的包名**逐个问 apk，而不是查固定三个文件。
                // ★判据是「包真的在不在」，退出码只用来记日志。
                //   apk 有两类假信号，方向相反，都必须靠逐个核对包消解：
                //   ① rc=0 但索引抓不到、逐包 temporary error → 大部分包没装上
                //      （实测 dev 形态：build-base/git/python3/py3-pip 全缺）。
                //   ② rc!=0 但包已装上 —— apk 的目录权限/触发器阶段报错会拉高
                //      退出码（实测 `apk add xvfb` → rc=28 "2 errors updating
                //      directory permissions"，而 /usr/bin/Xvfb 确实已落地）。
                //   按 rc 判会在 ② 上误判失败、在 ① 上误判成功，两种都会写错标记。
                val missing = verifyApkInstalled(pkgs)
                if (missing.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "apk install incomplete: rc=${r.exitCode} missing=" +
                            missing.joinToString(","),
                    )
                }
                missing.isEmpty()
            }

            LinuxRootfsCatalog.PackageManager.APT ->
                installPackagesApt(option, pkgs).also { res ->
                    if (!res.ok) {
                        Log.w(TAG, "bootstrap failed (will retry next boot): " + res.raw.take(400))
                    }
                }.ok
        }
        // ★成功标记必须以**文件真的在**为前提（v1.7.14）。
        //
        // 环境层的 ok 来自包管理器退出码/装包流程，而界面层的判据是
        // BOOTSTRAP_PROBE_FILES 那三个文件。两者一旦分叉（dpkg 报成功但 curl
        // 没解出来 —— 真机确有此事），写下的就是一份「骗人的成功标记」：
        // flavorPackagesReady() 从此恒真，页面显示已就绪，而容器里没有 curl，
        // 且因为标记不带 attempted 前缀，**永远不会再重试**。
        val filesOk = BOOTSTRAP_PROBE_FILES.all { File(root, it).isFile }
        if (ok && !filesOk) {
            Log.w(
                TAG,
                "bootstrap install reported success but probe files missing: " +
                    BOOTSTRAP_PROBE_FILES.filter { !File(root, it).isFile }.joinToString(","),
            )
        }
        if (ok && filesOk) {
            mark.writeText(pkgs.joinToString(","))
            Log.i(TAG, "bootstrap packages installed (${option.family.id}/${option.flavor.id}): " + pkgs)
        } else {
            // ★失败也必须落标记，否则**每次启动都重跑一遍联网装包**。
            //
            // 实测代价（Ubuntu 24.04 base）：apt-get update 单次 35~37 秒，
            // 因为镜像里没有 gnupg，apt-key 一调就失败，四个源全部报
            // `GPG error: ... Unknown error executing apt-key`。
            // ensureBootstrapPackages 每次都不成功 → 标记永远不出现 → 每次冷启
            // 都白等一遍 apt 流程，用户看到的就是「Ubuntu 要 63 秒，Alpine 0.1 秒」。
            //
            // 落标记 = 「已尝试过」，不是「已成功」。真的缺包时用户可以在
            // Linux 终端里手动装，或点「修复」强制重来。
            mark.writeText("attempted:" + pkgs.joinToString(","))
            Log.w(TAG, "bootstrap failed; recorded attempt marker so next boot skips retry")
        }
    }

    /**
     * apt 侧装包 —— **必须两步**。
     *
     * ## 为什么不能一步
     *
     * 实测（Ubuntu 24.04 base，真机）`apt-get install -y foo` 会走到
     * ```
     * dpkg: error: cannot stat pathname '/tmp/apt-dpkg-install-XXXX/00-foo.deb':
     *       No such file or directory
     * E: Sub-process /usr/bin/dpkg returned an error code (2)
     * ```
     * apt 把 deb 从 cache 硬链/复制进 `/tmp/apt-dpkg-install-*` 之后，dpkg 却
     * 看不见它。这一步在容器的路径重写层上不可靠（同一份 deb 留在
     * `/var/cache/apt/archives/` 里 dpkg 就能正常读）。
     *
     * 两步法绕开了这一步：
     * 1. `apt-get -d install`：**只下载**到 `/var/cache/apt/archives/`
     * 2. 对 `/var/cache/apt/archives/` 下的 deb 跑 `dpkg -i`：直接从 cache 装
     * 3. `dpkg --configure -a`：收尾（ldconfig shim 已让 configure 阶段不再 139）
     *
     * 这条路径实测装好了 curl 8.5.0 / bash / ca-certificates / less。
     */
    fun installPackagesApt(
        option: LinuxRootfsCatalog.Option,
        packages: List<String>,
        timeoutMs: Long = 600_000L,
        onLine: ((String) -> Unit)? = null,
    ): AptResult {
        if (packages.isEmpty()) return AptResult(true, "nothing to install")
        // ★整个 apt 流程（update / -d install / dpkg -i / configure）必须持
        //   包数据库锁：它与 Alpine 的 apk 路径争的是**同一个** rootfs 包数据库，
        //   并发时后到的那条会拿 "Unable to lock database" 静默失败。
        //   锁是跨类共享的独立对象，见 [LinuxPkgLock]。
        return LinuxPkgLock.withLock { installPackagesAptLocked(option, packages, timeoutMs, onLine) }
    }

    private fun installPackagesAptLocked(
        option: LinuxRootfsCatalog.Option,
        packages: List<String>,
        timeoutMs: Long,
        onLine: ((String) -> Unit)?,
    ): AptResult {
        val runner = LinuxContainerRunner.get(ctx)
        val sb = StringBuilder()

        // ★apt 之前必须先修掉「掉 MCS 分类的符号链接」（2026-11-13 真机）。
        //
        // dpkg 解包要先 dereference 既有链接才能覆盖，而裸 s0 的链接连 App 域
        // 都读不到，于是报
        //   failed to stat (dereference) existing symlink
        //   '/usr/share/doc/curl/changelog.Debian.gz': Permission denied
        // 直接让 curl 装不上 → bootstrap 永远停 attempted → 页面永远缺依赖。
        //
        // 这条不能只靠冷启那次自愈：裸 s0 链接是**上一次解包时**由 root 域产生的，
        // 冷启自愈早于它。所以每次 apt 装包前都查一遍（实测 93ms）。
        runCatching { SelinuxContext.healLinksIfDrifted(ctx, rootfsDir(option)) }
            .onFailure { Log.w(TAG, "pre-apt symlink heal failed: " + it.message) }

        fun step(label: String, guestPath: String, args: List<String>): Boolean {
            val r = runner.run(
                guestPath = guestPath,
                args = args,
                timeoutMs = timeoutMs,
                skipReady = true,
                onLine = onLine,
            )
            sb.append("$ ").append(label).append('\n')
            val out = r.combined()
            if (out.isNotEmpty()) sb.append(out.takeLast(2000)).append('\n')
            sb.append("[exit ").append(r.exitCode).append("]\n")
            if (!r.ok) Log.w(TAG, "$label failed rc=${r.exitCode}: " + out.take(400))
            return r.ok
        }

        // ⓪ 先修 partial 目录属主/权限问题（v1.7.13）。
        // 只要有**任何一次** apt 以 root 身份跑过（用户手动在终端里 sudo、或旧版本
        // 的部署流程），/var/lib/apt/lists/partial 就会变成 _apt:root 0700，而容器
        // 全程按 App uid 运行 —— 于是 E: List directory ... is missing /
        // Acquire (13: Permission denied)，且从 UI 上看只是「装包失败」。
        // 这里无条件把这个目录删掉重建：它是纯缓存，删了没有副作用。
        step(
            "reset apt lists/partial",
            "/bin/sh",
            listOf("-c", "rm -rf /var/lib/apt/lists/partial 2>/dev/null || true; mkdir -p /var/lib/apt/lists/partial 2>/dev/null || true; true"),
        )

        // ① 先刷新索引（失败不致命：可能是半成品缓存，先清再试一次）
        if (!step(
                "apt-get update",
                APT_GET,
                listOf("-o", APT_SANDBOX_OFF, "update"),
            )
        ) {
            // v1.7.13：连 partial 目录本身一起删掉再重建。
            // 旧代码只清 partial/*，而「目录」本身的属主可能已被一次 root 身份的
            // apt 改成 _apt:root 且 0700 —— App uid 连穿越都做不到，之后的每一步
            // 都报 Acquire (13: Permission denied)，重试多少次都一样。
            // rm -rf 目录 + mkdir 由当前 uid 重建，属主自然正确。
            runner.run(
                "/bin/sh",
                listOf("-c", "rm -rf /var/lib/apt/lists/partial 2>/dev/null || true; mkdir -p /var/lib/apt/lists/partial 2>/dev/null || true"),
                60_000,
                skipReady = true,
            )
            step("apt-get update (retry)", APT_GET, listOf("-o", APT_SANDBOX_OFF, "update"))
        }

        // ② 只下载
        if (!step(
                "apt-get -d install",
                APT_GET,
                listOf("-o", APT_SANDBOX_OFF, "-y", "--no-install-recommends", "-d", "install") + packages,
            )
        ) {
            return AptResult(false, sb.toString())
        }

        // ③ 从 cache 直接 dpkg -i（glob 交给 sh 展开：容器内没有 Kotlin 侧的目录枚举）
        //
        // ★--force-not-root 必须加（v1.7.13）：容器按 App uid 运行（seccomp 路线
        //   无 root，chroot 路线也要保证无 root 时可用），而 dpkg 不加这个开关
        //   硬性要求 uid=0，直接报
        //       dpkg: error: requested operation requires superuser privilege
        //   rc=2 —— 用户看到的「Ubuntu 装依赖报错」就是它。真机实证（uid 10499）：
        //   --force-not-root 后 18 个 deb（curl/openssl/ca-certificates/jq）全部
        //   装成功，jq --version 可用。dpkg 官方就有这个开关，专为非 root 安装设计。
        //
        // ★cache 里没有 deb 时跳过（v1.7.13 追加）：第二次 bootstrap（包已装）时
        //   ② 会是 0 newly installed、archives 为空，裸 dpkg -i 无参报
        //   need an action option rc=2，把整个流程判成失败 → bootstrap 标记永远
        //   attempted → 用户每次冷启都重跑一遍 apt。有 deb 才装，没有就当场跳过。
        //
        // ★这一段的 rc **不作为整体成败判据**：全新 rootfs 上 libc-bin 的 postinst
        //   会因 ldconfig 段错误让 dpkg -i 非 0（此刻 ldconfig.real 是 deb 刚解包
        //   出来的真 PIE，④ 的 quirks 还没来得及 divert 它），但 deb 已解包完毕，
        //   ④/⑤ 能把半配置的包收尾成功。真机时序实证（2026-11-13）：fresh rootfs
        //   首装报 libc-bin 139，quirks + configure 之后 Setting up 成功、无残留报错。
        step(
            "dpkg -i",
            "/bin/sh",
            listOf(
                "-c",
                "set -- /var/cache/apt/archives/*.deb; [ -e \"\$1\" ] && dpkg --force-not-root -i \"\$@\" || true",
            ),
        )

        // ④/⑤ 收尾：quirks（ldconfig shim 就位）→ configure（幂等）。
        // 整体成败以这一步为准：救得回来才算成功，救不回来（非 0）如实报失败，
        // 输出里已带真实原因。
        val configureOk = LinuxRootfsCatalog.postInstallArgv(option)
            .fold(true) { acc, argv ->
                step(argv.joinToString(" "), argv.first(), argv.drop(1)) && acc
            }
        // ⑥ 最后核对：请求的包**真的**进了 dpkg 数据库没有（v1.7.14）。
        //
        // 少了这一步就是假绿：③ 的 dpkg -i 明文「rc 不作判据」，而 configure 对
        // 未解包的包不报错、返回 0 —— 两者叠加，解包全失败也会被判成装成功。
        // 真机实证（2026-11-13）：cache 里 28 个 deb 齐、status 有 92 个包，
        // 但 `grep ^Package: curl` 是 0，界面却写了成功标记，之后永不重试。
        val missing = verifyAptInstalled(packages)
        if (missing.isNotEmpty()) {
            sb.append("still missing after install: ").append(missing.joinToString(",")).append('\n')
            Log.w(TAG, "apt install reported success but these are not installed: " + missing.joinToString(","))
        }
        // 把整段流水账落到磁盘（v1.7.14）。理由：这条链路失败时用户只看到
        // 「下载完了还是缺依赖」，而 logcat 环形缓冲几小时就被冲掉，事后无法归因。
        // 落盘后无论用户什么时候复述问题，都能读到最后一次装包的真实输出。
        runCatching {
            val f = File(stateDir(), "apt-last.log")
            val head = "# " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()) +
                " pkg=" + option.family.id + "/" + option.flavor.id +
                " want=" + packages.joinToString(",") +
                " missing=" + missing.joinToString(",") + "\n"
            val body = head + sb.toString()
            f.writeText(if (body.length > 64_000) body.takeLast(64_000) else body)
        }.onFailure { Log.w(TAG, "apt transcript write failed: " + it.message) }
        return AptResult(configureOk && missing.isEmpty(), sb.toString())
    }

    /**
     * 核对一批 apk 包是否真的装上了。返回仍然缺的那些。
     *
     * 判据用 `apk info -e <pkg>`（exists）而不是退出码：
     * apk 在「索引抓不到 / 镜像临时错误」时会逐包打 ERROR 并继续，
     * 最后只装上了缓存里已有的那几个，退出码却仍可能是 0（实测 python3 那次是 12）。
     * 两者都会让「装完了」的判断与「文件在不在」分叉。
     *
     * `apk info -e` 对每个包返回 0/1，所以这里必须**一次问一个**，
     * 不能用 `apk info <a> <b>`（那条会把参数当成要匹配的模式，语义不同）。
     * 但为省进程数，合并成一条 shell：把所有包丢进去循环，只回传缺的。
     */
    private fun verifyApkInstalled(packages: List<String>): List<String> {
        if (packages.isEmpty()) return emptyList()
        // 包名来自 RootfsCatalog 常量，不含引号/空格；仍做一次白名单式的清洗，
        // 避免将来有人从用户输入拼进来。
        val safe = packages.filter { it.matches(Regex("[A-Za-z0-9._+@-]+")) }
        if (safe.isEmpty()) return packages
        val script = safe.joinToString(" ") { p ->
            "apk info -e '$p' >/dev/null 2>&1 || echo '$p'"
        }
        val r = LinuxContainerRunner.get(ctx).run(
            "/bin/sh",
            listOf("-c", script),
            60_000L,
            skipReady = true,
        )
        val missing = r.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return packages.filter { it in missing }
    }

    /**
     * 核对一批包是否真的 `install ok installed`。返回仍然缺的那些。
     *
     * 判据取自 dpkg 自己的状态字段而不是退出码：dpkg 的退出码语义是「有没有出错」，
     * 而我们关心的是「包在不在」，这两件事在依赖不满足、postinst 失败、解包中止时
     * 会分叉。
     *
     * `dpkg-query -W` 只要有一个参数包不存在就整体 rc=1，但存在的包照常打印，
     * 所以这里解析 stdout，不用 rc。
     */
    private fun verifyAptInstalled(packages: List<String>): List<String> {
        if (packages.isEmpty()) return emptyList()
        val r = LinuxContainerRunner.get(ctx).run(
            "/usr/bin/dpkg-query",
            listOf("-W", "-f=\${Package} \${Status}\n") + packages,
            60_000L,
            skipReady = true,
        )
        val installed = HashSet<String>()
        r.stdout.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            val sp = t.indexOf(' ')
            if (sp <= 0) return@forEach
            // Status 是三段：<want> <flag> <state>，只有 install ok installed 算装上。
            if (t.endsWith(" installed")) installed.add(t.substring(0, sp))
        }
        return packages.filter { !installed.contains(it) }
    }

    /** apt 装包结果（带原始输出，纪律：跑不通必须贴原始报错） */
    data class AptResult(val ok: Boolean, val raw: String)

    /** 打开内置的 rootfs 资产 */
    private fun openAssetRootfs(assetPath: String): InputStream {
        val raw = ctx.assets.open(assetPath)
        return GZIPInputStream(BufferedInputStream(raw, 64 * 1024), 64 * 1024)
    }

    // ==================== tar 解包 ====================

    /** 延后落地的链接（tar 里 symlink 的目标可能指向尚未解出的文件） */
    private class LinkJob(val linkRel: String, val target: String, val isSymlink: Boolean)

    /**
     * Alpine rootfs tar 解包。
     *
     * 与 `ExtensionManager.untar` 的关键差异：这里的 rootfs 是**系统镜像**而不是
     * 某个扩展，路径不做 Termux 前缀剥离，也不做 usr/ 拍平（Alpine 的 `usr/bin`
     * 与 `bin` 是真实并列布局，标准 posix 语义）。
     *
     * 完整性判据沿用同一套纪律：只有读到全零结束块才算正常收尾；半截头或流尾
     * 一律抛错 —— 历史实现把 EOF 当「正常结束」会留下半套负载却仍标成功。
     */
    private fun untar(input: InputStream, target: File, pending: MutableList<LinkJob>): Int {
        var entries = 0
        DataInputStream(BufferedInputStream(input, 64 * 1024)).use { din ->
            val bh = ByteArray(512)
            var gnuLongName: String? = null
            var paxPath: String? = null
            while (true) {
                var off = 0
                while (off < 512) {
                    val r = din.read(bh, off, 512 - off)
                    if (r < 0) break
                    off += r
                }
                if (off == 0) {
                    throw EOFException("rootfs tar ended without a closing block ($entries entries)")
                }
                if (off < 512) throw EOFException("rootfs tar header truncated ($off/512)")
                if (bh.allZero()) break
                entries++

                val size = octal(bh, 124, 12)
                val type = bh[156].toInt().toChar()
                when (type) {
                    'L' -> { gnuLongName = readBodyString(din, size); continue }
                    'x' -> { paxPath = parsePaxPath(readBodyString(din, size)) ?: paxPath; continue }
                    'g' -> { skipBody(din, size); continue }
                }

                var name = gnuLongName ?: paxPath ?: str(bh, 0, 100)
                gnuLongName = null; paxPath = null
                val prefix = str(bh, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                val linkname = str(bh, 157, 100)
                val rel = name.removePrefix("./").trimStart('/')
                if (rel.isBlank()) { skipBody(din, size); continue }

                val out = File(target, rel).canonicalFile
                check(out.path.startsWith(target.canonicalPath)) { "rootfs path escape: $name" }

                when (type) {
                    '5' -> { out.mkdirs(); skipBody(din, size) }
                    '0', '\u0000' -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            var left = size
                            val buf = ByteArray(64 shl 10)
                            while (left > 0) {
                                val r = din.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (r < 0) throw EOFException("rootfs tar truncated: $name")
                                o.write(buf, 0, r)
                                left -= r
                            }
                        }
                        // 按 tar 头 mode 重建权限 —— 不保留则 busybox 不可执行（实测
                        // Windows 解包重打包路径下 mode 全丢，表现为 Permission denied）
                        val mode = octal(bh, 100, 8)
                        if (mode > 0) {
                            out.setExecutable((mode and 73L) != 0L, false)
                            out.setReadable((mode and 292L) != 0L, false)
                            out.setWritable((mode and 146L) != 0L, false)
                        }
                        skipPadAfter(din, size)
                    }
                    '2' -> { pending.add(LinkJob(rel, linkname, true)); skipBody(din, size) }
                    '1' -> { pending.add(LinkJob(rel, linkname, false)); skipBody(din, size) }
                    else -> skipBody(din, size)
                }
            }
        }
        return entries
    }

    // ==================== 链接修复（本类的核心价值）====================

    /**
     * 落地 tar 里的 symlink / 硬链接，并把**绝对 symlink 重写为相对**。
     *
     * 这是整个部署流程里最关键的一步，理由见类注释第 1 条。重写算法：
     * 把链接所在目录与目标路径都按 `/` 拆段，去掉公共前缀后，向上补 `../`、
     * 再拼目标剩余段。例：
     * - `/usr/bin/cat`（链接在 `usr/bin/`）指向 `/bin/busybox`
     *   → 公共前缀为空 → 目标 = `../../bin/busybox`
     * - `/bin/sh`（链接在 `bin/`）指向 `/bin/busybox`
     *   → 公共前缀 `bin` → 目标 = `busybox`
     */
    private fun materialiseLinks(root: File, pending: List<LinkJob>) {
        var fixed = 0
        var failed = 0
        pending.forEach { job ->
            val link = File(root, job.linkRel)
            link.parentFile?.mkdirs()
            val target = if (job.isSymlink) relativeTarget(job.linkRel, job.target) else job.target
            runCatching {
                Files.deleteIfExists(link.toPath())
                if (job.isSymlink) {
                    Files.createSymbolicLink(link.toPath(), Paths.get(target))
                } else {
                    val src = File(root, job.target.removePrefix("./").trimStart('/'))
                    Files.createLink(link.toPath(), src.toPath())
                }
                fixed++
            }.onFailure { e ->
                failed++
                Log.w(TAG, "link failed ${job.linkRel} -> ${job.target}: ${e.message}")
            }
        }
        Log.i(TAG, "links materialised: fixed=$fixed failed=$failed")
    }

    /**
     * 把绝对目标改成相对目标的链接内容。
     * 相对目标与非绝对目标原样返回（相对链接在 rootfs 内语义正确，不用动）。
     */
    private fun relativeTarget(linkRel: String, target: String): String {
        if (!target.startsWith("/")) return target
        // 链接所在目录（rootfs 内绝对路径，不含文件名）
        val linkDir = linkRel.substringBeforeLast('/', "")
        val from = if (linkDir.isEmpty()) emptyList() else linkDir.split('/')
        val to = target.trimStart('/').split('/')

        var i = 0
        while (i < from.size && i < to.size && from[i] == to[i]) i++

        val up = from.size - i
        if (up == 0 && i >= to.size) return "."
        val sb = StringBuilder()
        repeat(up) { sb.append("../") }
        for (k in i until to.size) {
            sb.append(to[k])
            if (k + 1 < to.size) sb.append('/')
        }
        return if (sb.isEmpty()) "." else sb.toString()
    }

    /**
     * 兜底：把 rootfs 内**残留的**绝对符号链接批量转相对。
     *
     * [materialiseLinks] 只处理「本次 tar 里声明的」链接；用户后续用 `apk add`
     * 装的包如果带绝对链接，需要这个扫描器（apk 的 post-install 在本方案里
     * 不完整执行，链接可能以原始绝对形态留下）。
     *
     * @return 修复数量
     */
    fun fixAbsoluteSymlinks(): Int {
        val root = rootfsDir()
        if (!root.isDirectory) return 0
        var n = 0
        root.walkTopDown()
            .onEnter { it.name != "proc" && it.name != "sys" && it.name != "dev" }
            .forEach { f ->
                if (!Files.isSymbolicLink(f.toPath())) return@forEach
                val tgt = runCatching { Files.readSymbolicLink(f.toPath()).toString() }.getOrNull() ?: return@forEach
                if (!tgt.startsWith("/")) return@forEach
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                val rt = relativeTarget(rel, tgt)
                runCatching {
                    Files.deleteIfExists(f.toPath())
                    Files.createSymbolicLink(f.toPath(), Paths.get(rt))
                    n++
                }
            }
        if (n > 0) Log.i(TAG, "absolute symlinks rewritten: $n")
        return n
    }

    /** 恢复可执行位（解包丢 mode 时的兜底；bin/sbin/usr/bin/usr/sbin/lib） */
    private fun restoreExecBits(root: File) {
        listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/lib", "lib").forEach { d ->
            val dir = File(root, d)
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { f ->
                if (f.isFile && !Files.isSymbolicLink(f.toPath())) {
                    // 只给「原本就是可执行的」补位：用 ELF/shebang 判断，避免把
                    // /etc 下的配置文件误标可执行。minirootfs 里可执行文件很少。
                    if (looksExecutable(f)) f.setExecutable(true, false)
                }
            }
        }
        // busybox 本体必须可执行（断言，不做静默容错）
        File(root, "bin/busybox").takeIf { it.isFile }?.setExecutable(true, false)
        File(root, "sbin/apk").takeIf { it.isFile }?.setExecutable(true, false)
    }

    private fun looksExecutable(f: File): Boolean {
        if (f.length() < 4) return false
        return runCatching {
            f.inputStream().use { ins ->
                val b = ByteArray(4)
                val n = ins.read(b)
                if (n < 4) return@use false
                b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte()   // ELF
            }
        }.getOrDefault(false)
    }

    // ==================== 其他工具方法 ====================

    fun dirBytes(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile && !Files.isSymbolicLink(f.toPath())) total += f.length()
        }
        return total
    }

    fun countFiles(dir: File): Int = dir.walkTopDown().count { it.isFile }

    /** 强清目录（部署重装用；不静默跳过删不掉的节点） */
    private fun purge(dir: File) {
        if (!dir.exists()) return
        dir.walkBottomUp().forEach {
            if (it == dir) return@forEach
            runCatching { it.delete() }
        }
        dir.mkdirs()
    }

    /** 磁盘占用（供 UI 展示）。**不要**放进 status()：完整遍历，几百毫秒。 */
    fun diskUsage(): Long = dirBytes(rootfsDir())

    fun diskUsage(option: LinuxRootfsCatalog.Option): Long = dirBytes(rootfsDir(option))

    /** rootfs 文件数（供 UI 展示）。同样不进 status()，理由见上。 */
    fun fileCount(): Int = countFiles(rootfsDir())

    fun fileCount(option: LinuxRootfsCatalog.Option): Int = countFiles(rootfsDir(option))

    // ==================== 二进制解析辅助（与 ExtensionManager 同源做法）====================

    /** tar 块尾填充的丢弃缓冲（最多 511 字节，见 [skipPadAfter]）。 */
    private val padBuf = ByteArray(512)

    private fun ByteArray.allZero(): Boolean = all { it.toInt() == 0 }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = minOf(off + len, b.size)
        while (end < max && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8).trim()
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = String(b, off, len, Charsets.US_ASCII).trim().trimEnd('\u0000')
        if (s.isEmpty()) return 0L
        return s.toLongOrNull(8) ?: 0L
    }

    private fun readBodyString(din: DataInputStream, size: Long): String {
        if (size <= 0 || size > (1 shl 20)) { skipBody(din, size); return "" }
        val buf = ByteArray(size.toInt())
        din.readFully(buf)
        skipPadAfter(din, size)
        return String(buf, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun skipBody(din: DataInputStream, size: Long) {
        var left = size
        val buf = ByteArray(8 shl 10)
        while (left > 0) {
            val r = din.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw EOFException("rootfs tar body truncated")
            left -= r
        }
        skipPadAfter(din, size)
    }

    /**
     * 跳过 tar 块尾的对齐填充（tar 每个条目按 512 字节对齐）。
     *
     * ★★必须自己读着跳过，不能用 [InputStream.skipNBytes]（issue #98）。
     *
     *   `skipNBytes(long)` 是 **Java 12** 新增的 API，Android 直到 **API 33
     *   （Android 13）** 才有实现。本项目 `minSdk = 26`，所以在 Android 12
     *   及以下设备上调用会抛 `NoSuchMethodError: No virtual method
     *   skipNBytes(J)V in class Ljava/io/DataInputStream`，整个 rootfs 解包
     *   当场中断 —— 用户看到的现象就是「点部署后立刻报错，目录里只有空骨架、
     *   没有 /lib，dynamic loader missing」。
     *
     *   也不能用 `skip()`：它在 DataInputStream 上的契约是「可能跳过少于
     *   n 字节」，返回实际跳过数，调用方必须自己循环；tar 流里少跳一格就会
     *   让后续所有 header 错位（解析出乱码路径或 size）。这里直接 readFully
     *   到丢弃缓冲里 —— 填充最多 511 字节，代价可忽略，且绝对正确。
     *
     *   同类前例见 `engine/AgentBridge.kt:717`（`readNBytes` 同属 API 33+）。
     */
    private fun skipPadAfter(din: DataInputStream, size: Long) {
        val pad = (512 - (size % 512)) % 512
        if (pad <= 0) return
        din.readFully(padBuf, 0, pad.toInt())
    }

    /** PAX 扩展头的 path record（供超长路径使用） */
    private fun parsePaxPath(body: String): String? {
        var rest = body
        while (rest.isNotEmpty()) {
            val sp = rest.indexOf(' ')
            if (sp <= 0) break
            val lenStr = rest.substring(0, sp)
            val len = lenStr.toIntOrNull() ?: break
            if (len <= 0 || len > rest.length) break
            val record = rest.substring(0, len).trimEnd('\u0000')
            rest = rest.substring(len)
            if (record.startsWith("path=") || record.contains(" path=")) {
                return record.substringAfter("path=").trimEnd('\u0000')
            }
        }
        return null
    }

    companion object {
    /** apt-get 的容器内路径（避免每处重复字面量） */
    private const val APT_GET = "/usr/bin/apt-get"

    /**
     * 关掉 apt 把子进程降权到 _apt 的行为（v1.7.13）。
     *
     * apt 正常会 fork 出 _apt 用户来跑 gpgv / 下载（sandbox）。容器里 uid 映射
     * 与真实系统不同：_apt 是 uid 42，而 rootfs 里所有文件都属于 App uid（10499）。
     * 于是 gpgv 以 _apt 身份读不到刚下载的 InRelease（父目录 0700、属主 10499），
     * 签名校验失败，apt 报的却是
     *   W: GPG error: ... InRelease: At least one invalid signature was encountered.
     * —— 看起来像镜像站签名坏了，实际是读不到文件。真机复现这条链的关键证据：
     * apt 会打印 Download is performed unsandboxed as root ... could not be accessed
     * by user _apt - pkgAcquire::Run (13: Permission denied)。
     *
     * 容器里没有真实的多用户隔离需求，直接把 sandbox 用户置空（= 不降权）即可。
     * 值必须是空串：-o APT::Sandbox::User= 形式。
     */
    private const val APT_SANDBOX_OFF = "APT::Sandbox::User="

        private const val TAG = "LinuxEnv"

        /** 随包的 rootfs 资产（Alpine）。glibc 发行版见 [LinuxRootfsCatalog] */
        private const val ASSET_ROOTFS = "linux/rootfs-bootstrap.tgz"

        /**
         * 资产版本号。与 tar 内容一起变更时递增 —— 但注意：**版本变化不会重装**
         * （见 [ensureDeployed]），避免抹掉用户已装的软件；只有 [ensureDeployed]
         * 传 force=true 才真重装。
         */
        const val ASSET_VERSION = "alpine-3.22.0-bootstrap"

        /** 部署完成标记 */
        private const val MARKER = ".dsh-deployed"

        /**
         * 静态链接的 aarch64 ripgrep（官方 release，无 PT_INTERP / 无 DT_NEEDED）。
         * 容器需要它是因为 @vscode/ripgrep 在 linux 平台解析 ripgrep-linux-arm64，
         * 而 runtime 里只注入了 bionic 的 ripgrep-android-arm64 —— 详见
         * [installLinuxCompatShims] 的说明。
         */
        /** 设备主 ABI 是否为 arm64（容器 rootfs 与各兼容层资产的架构开关） */
        private val RUNNER_ABI_ARM64: Boolean =
            when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "x86_64", "x86", "i686" -> false
                else -> true
            }

        private val LINUX_RG_ASSET: String? =
            if (RUNNER_ABI_ARM64) "linux/rg-static-arm64" else null

        /**
         * 预置的 Node 编译缓存包（`app/src/main/assets/linux/node-compile-cache.tgz`）。
         *
         * 3.2 MB 压缩 / 7.9 MB 解压 / 1281 个文件，由真机跑一轮引擎后
         * 打包出来。首次冷启时解到 `$DSH_HOME/.node-compile-cache`，
         * 省 840.5 ms（25.0%，交替 A/B 各 3 轮中位）。
         *
         * ⚠️ 包内目录名含 `v<node版本>-<process.arch>-<hash>-0`，**两段都不能改**：
         * 版对不上会被 [pruneCompileCache] 当陈旧删掉，arch 对不上 Node 直接不认
         * （实测改成 arm64 后耗时退回无缓存水平）。所以引擎升级 node 后必须重新
         * 生成这个包，否则 seed 会被版本检查跳过（行为正确，只是没收益）。
         */
        private const val COMPILE_CACHE_ASSET = "node-compile-cache.zip"

        /** [seededCachePrefix] 的进程内缓存（APK 在进程生命周期内不变） */
        @Volatile
        private var cachedPrefix: String? = null

        /**
         * 容器侧帧服务（`app/src/main/assets/linux/framesrv-*`）。
         *
         * 一个 bionic 小程序：用宿主 GPU 渲染，把每帧 glReadPixels 直接写进
         * 双方 mmap 的共享文件（App 私有目录），供 [LinuxFrameSurface] 贴图。
         * 两个架构都随包，按设备 ABI 选。
         */
        private val FRAMESRV_ASSET: String? =
            if (RUNNER_ABI_ARM64) "linux/framesrv-arm64" else "linux/framesrv-x86_64"

        /**
         * X11 画面服务（`app/src/main/assets/linux/x11frsrv-*`）。
         *
         * 与 [FRAMESRV_ASSET] 的区别：framesrv **自己用 EGL 画**内容（证明能用
         * 宿主 GPU），而这个是抓 X 服务器上**真实程序**（xterm / Geany / NetSurf）
         * 的合成结果。只有后者才让「点画面里的按钮」有意义。
         *
         * 两架构都有产物（2026-11-11 补齐 arm64）。绝不装空文件：那会让
         * 「文件存在」判据通过而 exec 失败，比缺失更难查。
         */
        private val X11FRSRV_ASSET: String =
            if (RUNNER_ABI_ARM64) "linux/x11frsrv-arm64" else "linux/x11frsrv-x86_64"

        /**
         * 触摸注入服务（`app/src/main/assets/linux/inputd-*`）。
         *
         * 常驻 XTEST 长连接：App 把手指动作写进共享文件，它轮询读走后注入。
         * 实测端到端 p50 = 1.3 ms（镜像仓 benchmark §18），比每次起 `xdotool`
         * （约 200ms）快约 150 倍 —— 拖动能否跟手全看这一点。
         *
         * 两架构都有产物（2026-11-11 补齐 arm64）。
         */
        private val INPUTD_ASSET: String =
            if (RUNNER_ABI_ARM64) "linux/inputd-arm64" else "linux/inputd-x86_64"

        /**
         * Node 的 `process.arch` 命名（`arm64` / `x64`）。
         *
         * ⚠️ 与 Android ABI 命名（`arm64-v8a` / `x86_64`）**不同**，而
         * `@vscode/ripgrep` 是拿 `process.arch` 拼平台包名的。混用会得到
         * `ripgrep-linux-x86_64` 这种永远解析不到的包名，且报错与「包不存在」
         * 完全一样，很难看出是命名问题。
         */
        private val NODE_ARCH: String =
            when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "x86_64", "x86", "i686" -> "x64"
                else -> "arm64"
            }

        /**
         * apk 镜像源。国内直连优先阿里云（实测 26178 个包可达）。
         *
         * ⚠️ 容器内**没有可用 DNS**（musl 直连 UDP 53 被 Android netd/SELinux 拦，
         * 实测 `nslookup` 报 `write to '223.5.5.5': Operation not permitted`），
         * 必须靠静态 `/etc/hosts` 解析。`mirrors.aliyun.com` 的 IP 见 [HOSTS]。
         */
        /**
         * 首次部署后额外装的**基础包**（离线装不了，所以在 status()==READY 后的
         * 第一次启动时装一次，装完写标记，不重复）。
         *
         * 为什么必须有 bash：
         *   DSH 的 bash 工具（`dsh-bash-local`）固定执行 `bash -c <cmd>`。极简
         *   Alpine rootfs 只有 busybox 的 `sh`（ash），没有 `/bin/bash`，于是
         *   **AI 的每条 bash 命令都 spawn 失败**，表现为「工具调用不了、命令全失败」。
         *   bash 包压缩 500KB / 解压 3.2MB，很便宜。
         *
         * 为什么还要 curl / ca-certificates：
         *   引擎与插件的自检、以及 npm/pip 走 https 时需要证书与 curl；
         *   缺 ca-certificates 会报 `SSL certificate problem`，很容易被误读成网络问题。
         */
        val BOOTSTRAP_PACKAGES = listOf("bash", "curl", "ca-certificates")

        /**
         * 基础包装好后应当出现的文件（相对 rootfs）。
         *
         * 用途只有一个：`attempted:` 标记存在时判断「到底装上没有」。
         * 不能用 `command -v` —— 那要起一个容器进程（实测 200~280ms），
         * 而这里问的只是「文件在不在」。
         *
         * ca-certificates 落的是单文件而非目录（`usr/share/ca-certificates`
         * 是散装源清单，实际 TLS 用的是合并后的那个 crt），取后者更贴近
         * 「证书真的可用」。
         */
        private val BOOTSTRAP_PROBE_FILES = listOf(
            "bin/bash",
            "usr/bin/curl",
            "etc/ssl/certs/ca-certificates.crt",
        )

        /** 基础包安装完成标记（存在即视为已尝试，不重复装） */
        private const val BOOTSTRAP_MARKER = ".dsh-bootstrap"

        /**
         * apk 仓库地址 —— **必须按发行版版本分流**。
         *
         * ★原实现只有一份写死 v3.22 的常量，加了 Alpine 3.23 之后成了真 bug：
         *   3.23 的 rootfs 会拿 3.22 的索引装包，等于把整个包系统往回降级。
         *   3.23 里 musl 1.2.6 / gcc 15 的包依赖 3.23 的新 soname，装 3.22 的
         *   旧包会缺依赖、甚至 `apk` 自己都跑不起来（它自己也来自该仓库）。
         *
         * 用 `mirrors` 段里的版本号注册表而不是写两遍字面量：以后加 3.24
         * 只需要在 [APK_MIRROR_VERSION] 里加一行。
         */
        private val APK_MIRROR_VERSION: Map<LinuxRootfsCatalog.Family, String> = mapOf(
            LinuxRootfsCatalog.Family.ALPINE to "v3.22",
            LinuxRootfsCatalog.Family.ALPINE_EDGE to "v3.23",
            // ★故意不登记 UBUNTU：它走 apt，apkRepositories 不该被调用。
            //   登记成 v3.22 会让「误调」得到一个看似正常实则可笑的阿里云 Alpine
            //   仓库地址 —— 比直接抛错更难查。缺登记 → error() 会立刻说出来。
        )

        /**
         * 取该发行版的 apk 仓库地址。
         *
         * ★缺映射时 **fail-fast，不静默回落**。
         *
         * 原实现是 `?: "v3.22"`。那正是 8e91833f 修掉的那个严重 bug 的形态：
         * 3.23 的 rootfs 拿 3.22 的索引装包 → 新 soname 依赖全找不到 → 连 `apk`
         * 自己都可能起不来。把它写成"有个值"的缺省，等于让**下一次忘记登记**
         * 的行为与那次事故完全一样，而且没有任何信号。
         *
         * 这里改成显式报错：加了新 Alpine 家族却忘了登记，构建期探针 G2/G3 会红；
         * 真跑到运行时也会立刻炸出清晰原因，而不是悄悄降级。
         *
         * @throws IllegalStateException 该发行版没有 apk 仓库登记
         */
        fun apkRepositories(option: LinuxRootfsCatalog.Option): List<String> {
            val v = APK_MIRROR_VERSION[option.family]
                ?: error(
                    "no apk mirror version registered for family '${option.family.id}'. " +
                        "Add it to APK_MIRROR_VERSION — falling back silently would install " +
                        "packages from the wrong Alpine release (see 8e91833f)."
                )
            return listOf(
                "http://mirrors.aliyun.com/alpine/$v/main",
                "http://mirrors.aliyun.com/alpine/$v/community",
            )
        }

        /**
         * 静态 hosts。
         *
         * 只保留 localhost —— 镜像域名**不要**写死 IP。
         *
         * ⚠️ 踩过的坑：这里曾经硬编码 `111.1.164.136 mirrors.aliyun.com`，
         * 因为当时容器里没有 `/etc/resolv.conf`、DNS 完全不可用，只能靠 hosts
         * 解析出一个固定 IP 才能装包。但那是个治标方案，代价是：
         *   · 用户和 AI 访问任何**其他**域名都解析失败 —— 账号登录
         *     （`/auth-api/v0/users/current` 报 no-response）、模型列表
         *     （`api.deepseek.com` 超时）、pip/npm/git 全线不通，
         *     而这些都不在 hosts 白名单里。
         * 正确做法是让 DNS 真的能用（见 [RESOLV_CONF]），hosts 只留 localhost。
         * 实测：补上 resolv.conf 后 `nslookup mirrors.aliyun.com` 与
         * `apk update`（26178 个包）都正常，不再需要写死 IP。
         */
        val HOSTS = """
            127.0.0.1 localhost
        """.trimIndent().lines()

        /**
         * `/etc/resolv.conf` —— 容器内 DNS 配置（**musl 版**）。
         *
         * 这是**必须写**的：musl 的解析器只认 `/etc/resolv.conf`，没有它就
         * 只能用 127.0.0.1:53（Android 上没人监听，报 Connection refused）。
         *
         * ## ⚠️ 更正一处旧结论（2026-11-05 真机实测）
         *
         * 这里原先写着「Android 允许 musl 直连外部 UDP 53」，**那是错的**。
         * 实测（App uid 10488 与 shell uid 2000 都试过）：
         * ```
         * nslookup mirrors.aliyun.com
         *   → nslookup: write to '223.5.5.5': Operation not permitted
         * wget http://mirrors.aliyun.com/...
         *   → wget: bad address 'mirrors.aliyun.com'
         * ```
         * 但**同一个容器**里用固定 IP 就能通：
         * ```
         * wget --header="Host: mirrors.aliyun.com" http://112.15.7.206/alpine/
         *   → rc=0
         * ```
         * 宿主侧 `nc -u 223.5.5.5 53` 也超时（rc=124），而 `nc 223.5.5.5 53`
         * （TCP）**成功（rc=0）**。
         *
         * 结论：**UDP 53 被 Android 拦，TCP 53 通**。这是平台限制，不是容器 bug。
         *
         * ## 所以 musl 侧只能这样
         *
         * musl **不支持** glibc 的 `options use-vc`（源码里没这个选项），无法
         * 强制走 TCP。可行的替代只有：
         *   · `/etc/hosts` 写死关键域名（这里是 [MUSL_HOSTS]）—— 治标但可用
         *   · `apk add` 依赖的索引能用预置缓存，但装**新**包仍需网络
         *
         * glibc 侧不需要这些妥协，见 [RESOLV_CONF_GLIBC]。
         */
        val RESOLV_CONF = """
            nameserver 223.5.5.5
            nameserver 119.29.29.29
        """.trimIndent().lines()

        /**
         * `/etc/resolv.conf` —— 容器内 DNS 配置（**glibc 版**）。
         *
         * `options use-vc` 让 glibc 的解析器**强制用 TCP 发 DNS 查询**
         * （glibc 的 `res_init` 认这个选项，`__res_context_query` 会设
         * `RES_USEVC` 后走 `send` 而非 `sendto`）。
         *
         * ## 真机实测对比（同容器、同 App、只差这一行）
         *
         * | 项 | 无 use-vc | 有 use-vc |
         * |---|---|---|
         * | `getent hosts mirrors.aliyun.com` | `EAI_AGAIN` | 返回 3 个 IP |
         * | `curl http://mirrors.tuna.../ubuntu/` | `http=000` | **`http=200 size=14972`** |
         * | `apt-get install jq` | 装不上 | **成功装 1.7.1** |
         *
         * 这就是「Ubuntu 环境看着能用、实际联网全废」的根因 —— 之前
         * `apt update` 之所以显示成功，是因为它在读**预置的索引缓存**，
         * 并没有真的联网。
         */
        val RESOLV_CONF_GLIBC = """
            options use-vc
            nameserver 223.5.5.5
            nameserver 119.29.29.29
        """.trimIndent().lines()

        /** 单例（进程级，避免重复解包） */
        @Volatile
        private var instance: LinuxEnvironmentManager? = null

        fun get(ctx: Context): LinuxEnvironmentManager =
            instance ?: synchronized(this) {
                instance ?: LinuxEnvironmentManager(ctx.applicationContext).also { instance = it }
            }
    }
}

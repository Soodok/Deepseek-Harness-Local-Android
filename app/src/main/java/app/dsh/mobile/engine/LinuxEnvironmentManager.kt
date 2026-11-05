package app.dsh.mobile.engine

import android.content.Context
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
 *    这些交给 `apk add` 按需安装（见 [APK_REPOSITORIES]），不预置进 APK。
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
            LinuxRootfsCatalog.Family.ALPINE -> File(root, "usr/bin/env")
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
                return cur
            }
            if (cur.state == State.READY && cur.rootfsVersion != assetVersion) {
                // 版本变了但结构完好：不重装，只更新标记，避免抹掉用户装好的软件。
                Log.i(TAG, "rootfs version drift ${cur.rootfsVersion} -> $assetVersion (keeping installed packages)")
                marker.writeText("$assetVersion\n${System.currentTimeMillis()}\n")
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

        // 发行版专属配置：写在解包之后、标记之前 —— 配置没写成功就不该算部署完成
        writeDistroConfig(option, root)

        val ms = System.currentTimeMillis() - t0
        marker.writeText("$assetVersion\n${System.currentTimeMillis()}\n")
        Log.i(TAG, "rootfs deployed (${option.family.id}): $entries entries, ${pending.size} links, ${ms}ms")

        // 部署完补基础包（bash / curl / ca-certificates），**异步**。
        // 缺 bash 时 AI 的 bash 工具（dsh-bash-local 固定执行 `bash -c`）每条
        // 命令都会 spawn 失败；缺 ca-certificates 则 https 报 SSL 错。
        // 但它是附加能力、不是部署成功的前提 —— 同步等它会把首次部署拖慢
        // 好几秒（联网装这三个包实测 4~8 秒），所以丢后台线程，装完写标记。
        Thread({
            runCatching { ensureBootstrapPackages(option) }
                .onFailure { Log.w(TAG, "bootstrap failed: " + it.message) }
        }, "dsh-linux-bootstrap").apply { isDaemon = true }.start()

        return status(option)
    }

    /**
     * 版本标签：写进 `.dsh-deployed` 第一行，用于幂等判定。
     *
     * Alpine 沿用老的常量（老用户标记里就是这个值，改了会让所有人都重装一次）；
     * glibc 发行版用 `家族-镜像标识`。
     */
    private fun versionTag(option: LinuxRootfsCatalog.Option): String = when (option.family) {
        LinuxRootfsCatalog.Family.ALPINE -> ASSET_VERSION
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
    /** 供 [LinuxContainerRunner] 在每次 ensureReady 时重申配置（幂等） */
    fun writeDistroConfigPublic(option: LinuxRootfsCatalog.Option) {
        writeDistroConfig(option, rootfsDir(option))
    }

    private fun writeDistroConfig(option: LinuxRootfsCatalog.Option, root: File) {
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
        } else {
            val proxyPort = LinuxDnsProxy.start(ctx)
            if (proxyPort > 0) {
                // musl 只认 IP、端口写死 53，所以**不要**写 `:port` 后缀
                // （写了会被整个忽略，实测仍发往 53）。代理已尽力绑在 53 上。
                val line = if (proxyPort == 53) "nameserver 127.0.0.1"
                           else "nameserver 127.0.0.1:$proxyPort"
                writeIfChanged("etc/resolv.conf", listOf(line))
                if (proxyPort != 53) {
                    Log.w(TAG, "DNS proxy bound to $proxyPort, not 53: musl getaddrinfo " +
                        "will bypass it (wget/apk DNS unusable without root)")
                }
            } else {
                Log.w(TAG, "DNS proxy unavailable; musl net access will be degraded")
                writeIfChanged("etc/resolv.conf", RESOLV_CONF)
            }
        }

        when (option.pkgManager) {
            LinuxRootfsCatalog.PackageManager.APK ->
                writeIfChanged("etc/apk/repositories", APK_REPOSITORIES)

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
            // 立即执行一次（部署后立刻生效，不等到第一次装包才发现 ldconfig 崩）。
            // 失败不阻断部署：补丁失败只让后续 apt 操作报错，此时用户还能进终端手工修。
            val r = LinuxContainerRunner.get(ctx).run(
                guestPath = "/bin/sh",
                args = listOf("/etc/dsh-glibc-quirks.sh"),
                timeoutMs = 60_000L,
                skipReady = true,
            )
            if (r.ok) {
                Log.i(TAG, "glibc quirks applied")
            } else {
                Log.w(TAG, "glibc quirks failed rc=${r.exitCode}: " + r.combined().take(300))
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
    fun ensureBootstrapPackages() = ensureBootstrapPackages(activeOption())

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
    fun ensureBootstrapPackages(option: LinuxRootfsCatalog.Option) {
        val root = rootfsDir(option)
        val mark = File(root, BOOTSTRAP_MARKER)
        if (mark.isFile) return
        val ok = when (option.pkgManager) {
            LinuxRootfsCatalog.PackageManager.APK -> {
                val r = LinuxContainerRunner.get(ctx).run(
                    guestPath = "/sbin/apk",
                    args = listOf("add", "--no-progress") + BOOTSTRAP_PACKAGES,
                    timeoutMs = 600_000L,
                    // 必须跳过 ensureReady：它会回调本方法，形成
                    // run -> ensureReady -> ensureBootstrapPackages -> run 的死循环
                    // （实测表现：启动变慢、.dsh-bootstrap 永远不出现）。
                    skipReady = true,
                )
                if (!r.ok) {
                    Log.w(TAG, "bootstrap failed (will retry next boot): " + r.combined().take(400))
                }
                r.ok
            }

            LinuxRootfsCatalog.PackageManager.APT ->
                installPackagesApt(option, BOOTSTRAP_PACKAGES).also { res ->
                    if (!res.ok) {
                        Log.w(TAG, "bootstrap failed (will retry next boot): " + res.raw.take(400))
                    }
                }.ok
        }
        if (ok) {
            mark.writeText(BOOTSTRAP_PACKAGES.joinToString(","))
            Log.i(TAG, "bootstrap packages installed (${option.family.id}): " + BOOTSTRAP_PACKAGES)
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
    ): AptResult {
        if (packages.isEmpty()) return AptResult(true, "nothing to install")
        val runner = LinuxContainerRunner.get(ctx)
        val sb = StringBuilder()

        fun step(label: String, guestPath: String, args: List<String>): Boolean {
            val r = runner.run(
                guestPath = guestPath,
                args = args,
                timeoutMs = timeoutMs,
                skipReady = true,
            )
            sb.append("$ ").append(label).append('\n')
            val out = r.combined()
            if (out.isNotEmpty()) sb.append(out.takeLast(2000)).append('\n')
            sb.append("[exit ").append(r.exitCode).append("]\n")
            if (!r.ok) Log.w(TAG, "$label failed rc=${r.exitCode}: " + out.take(400))
            return r.ok
        }

        // ① 先刷新索引（失败不致命：可能是半成品缓存，先清再试一次）
        if (!step(
                "apt-get update",
                "/usr/bin/apt-get",
                listOf("update"),
            )
        ) {
            runner.run("/bin/sh", listOf("-c", "rm -rf /var/lib/apt/lists/partial/* 2>/dev/null || true"), 60_000, skipReady = true)
            step("apt-get update (retry)", "/usr/bin/apt-get", listOf("update"))
        }

        // ② 只下载
        if (!step(
                "apt-get -d install",
                "/usr/bin/apt-get",
                listOf("-y", "--no-install-recommends", "-d", "install") + packages,
            )
        ) {
            return AptResult(false, sb.toString())
        }

        // ③ 从 cache 直接 dpkg -i（glob 交给 sh 展开：容器内没有 Kotlin 侧的目录枚举）
        if (!step(
                "dpkg -i",
                "/bin/sh",
                listOf("-c", "dpkg -i /var/cache/apt/archives/*.deb"),
            )
        ) {
            return AptResult(false, sb.toString())
        }

        // ④ 收尾 configure（幂等）
        LinuxRootfsCatalog.postInstallArgv(option).forEach { argv ->
            step(argv.joinToString(" "), argv.first(), argv.drop(1))
        }
        return AptResult(true, sb.toString())
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

    private fun skipPadAfter(din: DataInputStream, size: Long) {
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) din.skipNBytes(pad)
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

        /** 基础包安装完成标记（存在即视为已尝试，不重复装） */
        private const val BOOTSTRAP_MARKER = ".dsh-bootstrap"

        val APK_REPOSITORIES = """
            http://mirrors.aliyun.com/alpine/v3.22/main
            http://mirrors.aliyun.com/alpine/v3.22/community
        """.trimIndent().lines()

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

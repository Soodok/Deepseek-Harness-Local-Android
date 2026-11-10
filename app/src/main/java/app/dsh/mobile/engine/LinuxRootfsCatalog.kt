package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import app.dsh.mobile.R
import java.io.File

/**
 * Linux rootfs catalog — data source and selection logic for the optional distros.
 *
 * ## Why this layer exists
 *
 * The app used to ship a single hardcoded Alpine minirootfs. Alpine uses musl,
 * which cannot run the large body of glibc-only prebuilt binaries (Oracle JDK,
 * some commercial tools, third-party CLIs that only publish glibc builds).
 * Supporting those means offering a glibc distro as an alternative.
 *
 * This class collects "where the rootfs comes from, how to unpack it, which
 * package manager it uses, where its dynamic loader lives" into one table so
 * the upper layers ([LinuxEnvironmentManager], [LinuxContainerRunner],
 * [LinuxEngineLauncher]) no longer hardcode Alpine.
 *
 * ## Measured facts (2026-11-05, on-device at 192.168.101.21:9000)
 *
 * | Item | Result |
 * |---|---|
 * | Ubuntu base 24.04 arm64 unpack | 3413 entries / 196 links / 96.1MB extracted |
 * | `dsh_alpine` running a glibc binary | **works** (`/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1`) |
 * | glibc node running real JS | **works** (v22.14.0, `os.cpus()` reported 8 / 15.7GB) |
 * | `apt-get update` | **works** (TUNA ubuntu-ports, **http only**, see below) |
 * | `apt-get install` in one shot | **fails**: `dpkg: cannot stat '/tmp/apt-dpkg-install-XXXX/00-pkg.deb'` |
 * | `apt-get -d install` + `dpkg -i` | **works** (curl 8.5.0 installed and runs) |
 * | glibc `ldconfig.real` | **segfault, exit 139** (static PIE, no PT_INTERP) |
 *
 * Two hard constraints follow, implemented in [LinuxEnvironmentManager]:
 * 1. glibc package installs **must be two-step** (download-only + `dpkg -i`).
 * 2. glibc `ldconfig` **must be shimmed out**, otherwise every libc trigger's
 *    configure phase exits 139.
 *
 * ## Why distros coexist instead of replacing each other
 *
 * Each distro lives in its own directory `filesDir/linux/distros/<id>/`; only
 * the one [active] points at is in effect. Rationale: packages a user installed
 * in one environment must not vanish because they tried the other; a broken
 * install can be switched away from; the only cost is disk (shown per item in
 * the UI).
 *
 * Older builds had a single `linux/rootfs/` directory. It is migrated on first
 * access with an atomic rename into `distros/alpine/` — no copy, no data loss
 * (see [LinuxEnvironmentManager.migrateLegacyLayout]).
 */
object LinuxRootfsCatalog {

    private const val TAG = "LinuxRootfsCatalog"

    /** Distro family. The id is also the directory name — changing it loses user data. */
    enum class Family(val id: String) {
        ALPINE("alpine"),
        UBUNTU("ubuntu"),

        /**
         * Alpine **3.23**（更新的稳定版）。
         *
         * 为什么另开一个 Family 而不是给 ALPINE 加 flavor：这两者是**不同的
         * rootfs 内容**（内核版本、musl 版本、包版本全线不同），共享目录会
         * 导致原地升级式的互相污染。flavor 只能共享「同一份文件系统上的
         * 额外包」，跨版本必须各自一份。
         *
         * 与 3.22 的取舍：3.23 的 gcc/musl/python 全线新一档（用户要新编译器
         * 时不必自己换仓库），代价是社区验证少一档、体积大 45 KB。
         *
         * 实测（2026-11-09，Alpine 官方 .sha256 逐字核对）：
         *   x86_64  3702300 B  sha256 56e28102...3676
         *   aarch64 4027855 B  sha256 3f1bd681...1473
         */
        ALPINE_EDGE("alpine323"),
    }

    /** Package manager kind. The difference is more than a command name (see class doc). */
    enum class PackageManager { APK, APT }

    /** Archive format of the rootfs payload */
    enum class ArchiveFormat { TAR_GZ, TAR_XZ }

    /** Where the rootfs comes from: bundled asset, or downloaded */
    sealed class RootfsSource {
        /**
         * Asset packaged into the APK (Alpine: small and works offline).
         *
         * The sizes are carried here as well so the UI can show "download vs disk"
         * for every option uniformly; for a bundled asset the download part is
         * simply the APK payload.
         */
        data class Bundled(
            val assetPath: String,
            val compressedBytes: Long,
            val extractedBytes: Long,
        ) : RootfsSource()

        /**
         * Downloaded at deploy time.
         *
         * [urls] is a mirror fallback chain, tried in order. Every entry is a copy
         * of the same upstream tarball (cross-checked against the official
         * SHA256SUMS), so a single sha256 covers all of them.
         */
        data class Remote(
            val urls: List<String>,
            val sha256: String,
            /** Compressed size, measured from each mirror's Content-Length */
            val compressedBytes: Long,
            /** Extracted size, measured by unpacking locally and summing file sizes */
            val extractedBytes: Long,
            val format: ArchiveFormat,
        ) : RootfsSource()
    }

    /**
     * How the engine's node runtime is obtained for a distro.
     */
    enum class NodeStrategy {
        /** `apk add nodejs` (Alpine upstream ships a musl build) */
        APK_PACKAGE,

        /**
         * Download Node's official linux-arm64 build and unpack to `/opt/node`.
         *
         * Why not apt: Ubuntu 24.04 ships nodejs 18.19, below the 22 the engine
         * needs. The official tarball is a glibc build, which matches this rootfs.
         */
        OFFICIAL_TARBALL,
    }

    /**
     * Full description of one selectable distro.
     *
     * Size numbers **must be measured**, never estimated: the UI compares them
     * against free disk space, and an underestimate sends the user into ENOSPC.
     */
    data class Option(
        val family: Family,
        /** Display name (a proper noun, not translated) */
        val displayName: String,
        val versionLabel: String,
        val libc: String,
        val pkgManager: PackageManager,
        val source: RootfsSource,
        /** Absolute path of the dynamic loader inside the guest */
        val interpPath: String,
        /** Install command, used for UI hints and package-manager dispatch */
        val installCommand: String,
        val nodeStrategy: NodeStrategy,
        /** Whether deploy must apply glibc-specific patches (ldconfig shim, apt limits) */
        val needsGlibcQuirks: Boolean,
        val isDefault: Boolean = false,
        /**
         * 「形态」：同一个 rootfs 的预装包组合。
         *
         * ## 为什么用 flavor 而不是多加几个 Option 各带一份 rootfs
         *
         * 一份 Alpine minirootfs 解压后 8.5 MB、装完常用包能到 600+ MB。
         * 如果「开发版」「图形版」各带一份 rootfs，用户装三个就吃掉 1.8 GB ——
         * 而这三种形态**装的东西高度重叠**（bash/curl/ca-certificates 都要）。
         *
         * 所以 flavor 只决定**首启额外 `apk add` 什么**，`rootfsDir` 仍然按
         * [Family.id] 算，所有 flavor 共享同一个目录。切换 flavor 不复制任何
         * 文件，只是下次启动时多装/少装几个包（已装过的包 apk 会跳过）。
         *
         * ⚠️ 因此 flavor **不是隔离环境** —— 用户从 Minimal 切到 Full 再切回来，
         * 装过的包仍在。这是刻意的（省磁盘、可逆），UI 上要如实说明。
         */
        val flavor: Flavor = Flavor.BASE,
    )

    /**
     * 同一发行版的预装包形态。
     *
     * `extraPackages` 是**首启额外装**的包（在 `BOOTSTRAP_PACKAGES` 之后）。
     * 体积数字按 Alpine 包索引的 `I:` 字段量级给，不能随手估 ——
     * UI 拿它跟磁盘余量比，低估会把用户带进 ENOSPC。
     */
    enum class Flavor(
        val id: String,
        /** 在 UI 上显示的后缀（空字符串 = 就是这个发行版本身） */
        val label: String,
        /** 额外下载体积（仅 UI 提示；实际以 apk 报的为准） */
        val extraDownloadBytes: Long,
        /** 额外解压后的磁盘占用（仅 UI 提示；同样以 apk 报的为准） */
        val extraExtractBytes: Long,
        /**
         * 「装完能干什么」的文案资源。
         *
         * 为什么必须显示：三个形态共享同一份 rootfs，所以体积行几乎一样
         * （都是 3.5 MB / 8.1 MB），用户看界面**分不出差异**，只能自己猜
         * Dev 和 GUI 有什么区别。用一句大白话讲清用途，比堆技术名词有效。
         */
        val useCaseRes: Int,
        /** 这个 flavor 首启额外装的包 */
        val extraPackages: List<String>,
    ) {
        /** 只有 bootstrap 那三个包（bash/curl/ca-certificates） */
        BASE(
            "base", "", 0L, 0L,
            R.string.linux_flavor_base_use,
            emptyList(),
        ),

        /**
         * 完整开发工具链。
         *
         * `build-base` = gcc + musl-dev + make + binutils（Alpine 的元包）。
         * 实测这套解压后约 250 MB —— 数字写在这里是为了让用户在点之前知道代价。
         */
        DEV(
            "dev",
            "Dev",
            48_000_000L,
            250_000_000L,
            R.string.linux_flavor_dev_use,
            listOf("build-base", "git", "python3", "py3-pip"),
        ),

        /**
         * 图形栈（X11 路线）。
         *
         * 这是「触摸容器画面」功能的前提：没有 Xvfb 与 xterm，用户点了
         * 「开始画面」只会看到 `Xvfb: not found`。实测约 160 MB。
         */
        GUI(
            "gui",
            "GUI",
            62_000_000L,
            160_000_000L,
            R.string.linux_flavor_gui_use,
            listOf("xvfb", "xterm", "xdotool", "libx11", "libxtst", "font-misc-misc"),
        ),
    }

    /**
     * 该形态在**当前 ABI** 上是否真的可用。
     *
     * ★为什么需要这个判据：GUI 形态除了 `apk add xvfb xterm` 这些**能从仓库装**的包，
     * 还依赖两个**必须随 APK 分发**的二进制：`x11frsrv`（抓帧）与 `inputd`
     * （触摸注入）。缺了它们，包虽然装得上，点「开始画面」会报
     * `x11frsrv: not found`，而界面显示一切正常 —— 属于「承诺了但做不到」。
     *
     * ## 2026-11-11：arm64 产物已补齐
     *
     * 此前只有 x86_64 版，arm64 上一律返回 false（手机装不了 GUI）。现已用
     * `clang --target=aarch64-linux-musl --sysroot=<aarch64 rootfs>` 交叉编译出
     * `inputd-arm64`(13488 B) 与 `x11frsrv-arm64`(16208 B)，与 x86_64 版同为
     * musl 动态链接、依赖同名库（libX11/libXext/libXtst）。
     *
     * 因此本判据不再按架构区分 —— 两种 ABI 都有产物。保留函数是**为了将来**
     * 再出现「某形态只在部分架构可用」时有个集中的落点，而不是散落在 UI 里。
     */
    fun availableOnThisAbi(@Suppress("UNUSED_PARAMETER") flavor: Flavor): Boolean = when (flavor) {
        Flavor.GUI, Flavor.BASE, Flavor.DEV -> true
    }

    /** Node 官方预编译 tarball 的元数据（glibc, arm64） */
    data class NodeTarball(
        val version: String,
        val urls: List<String>,
        val sha256: String,
        val bytes: Long,
        val installDir: String,
    )

    /**
     * Node 官方预编译 tarball（glibc, arm64）。大小与 sha256 实测取自官方 SHASUMS256.txt。
     *
     * ⚠️ 版本必须 ≥ 24.9.0，这是引擎自己的硬要求（`@deepseek-ai/dsh/package.json`
     * 的 `engines.node`）。真机实测：装 22.14.0 时 `node bin.js --help` **静默退出**
     * （exit=0、无任何输出），App 侧只看到 "engine exited: status=0 signal=0 exit=0"，
     * 极难定位；换 24.9.0 立刻正常打印 usage。Alpine 侧的 musl node 是 22.23.4 却
     * 能跑 —— 说明引擎并非在所有版本上都硬校验，但 22.14.0 确实不行，别再降级。
     */
    val nodeTarball = NodeTarball(
        version = "24.9.0",
        urls = listOf(
            "https://cdn.npmmirror.com/binaries/node/v24.9.0/node-v24.9.0-linux-arm64.tar.xz",
            "https://registry.npmmirror.com/-/binary/node/v24.9.0/node-v24.9.0-linux-arm64.tar.xz",
            "https://nodejs.org/dist/v24.9.0/node-v24.9.0-linux-arm64.tar.xz",
        ),
        sha256 = "5a5b1dc4906e891a655d2f0689db664879724f2d9e63309486fd588172a052bc",
        bytes = 31_085_180L,
        installDir = "/opt/node",
    )

    /** In-container path of the glibc node binary (derived from [NodeTarball.installDir]) */
    const val GLIBC_NODE_PATH = "/opt/node/bin/node"

    /**
     * 设备主 ABI 是否是 arm64。
     *
     * 为什么在**目录层**判架构：rootfs 资产与动态链接器都是架构专属的，
     * 写死 arm64 时 x86_64 设备/模拟器上容器连第一个程序都起不来
     * （interp 路径不存在 → `LOADER: preload FAIL`）。原来只有
     * `EngineConfig.linuxMuslInterp` 做了兜底，但真正喂给 runner 的是
     * `Option.interpPath`，兜底值永远走不到。
     *
     * 类初始化时算一次：`Build.SUPPORTED_ABIS` 在进程生命周期内不变，
     * 而 [options] 是静态 val，这样 5 个调用点都不用改签名。
     */
    private val IS_ARM64: Boolean = run {
        val abi = runCatching { android.os.Build.SUPPORTED_ABIS.firstOrNull() }.getOrNull()
        abi == null || abi == "arm64-v8a" || abi == "aarch64"
    }

    /** Alpine 的 musl 动态链接器（架构随设备） */
    private val ALPINE_INTERP: String =
        if (IS_ARM64) "/lib/ld-musl-aarch64.so.1" else "/lib/ld-musl-x86_64.so.1"

    // ==================== Catalog ====================

    /** Two candidates: the existing bundled musl one, plus a downloaded glibc one. */
    val options: List<Option> = listOf(
        Option(
            family = Family.ALPINE,
            displayName = "Alpine",
            versionLabel = "3.22",
            libc = "musl",
            pkgManager = PackageManager.APK,
            source = RootfsSource.Bundled(
                assetPath = if (IS_ARM64) "linux/rootfs-bootstrap.tgz"
                            else "linux/rootfs-bootstrap-x86_64.tgz",
                // 实测：arm64 版压缩 3,979,016 / 解压后 8.5MB；
                //       x86_64 版压缩 3,657,952（Alpine v3.22.2 minirootfs，
                //       sha256 412454ed98025ad9cd910d13f3d448b184e81502baa83180e89f98d0f13674be，
                //       与官方 .sha256 逐字一致）
                compressedBytes = if (IS_ARM64) 3_979_016L else 3_657_952L,
                extractedBytes = 8_510_313L,
            ),
            interpPath = ALPINE_INTERP,
            installCommand = "apk add",
            nodeStrategy = NodeStrategy.APK_PACKAGE,
            needsGlibcQuirks = false,
            isDefault = true,
        ),
        Option(
            family = Family.UBUNTU,
            displayName = "Ubuntu",
            versionLabel = "24.04 base",
            libc = "glibc 2.39",
            pkgManager = PackageManager.APT,
            source = RootfsSource.Remote(
                // Three CN mirrors plus the official origin. sha256 cross-checked
                // against ubuntu-cdimage/.../24.04/release/SHA256SUMS.
                urls = listOf(
                    "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                    "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                    "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                ),
                sha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
                compressedBytes = 29_936_675L,
                extractedBytes = 100_784_109L,
                format = ArchiveFormat.TAR_GZ,
            ),
            interpPath = "/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            installCommand = "apt-get install",
            nodeStrategy = NodeStrategy.OFFICIAL_TARBALL,
            needsGlibcQuirks = true,
        ),
        Option(
            family = Family.ALPINE_EDGE,
            displayName = "Alpine",
            versionLabel = "3.23",
            libc = "musl",
            pkgManager = PackageManager.APK,
            source = RootfsSource.Remote(
                // 实测 2026-11-09，与官方 .sha256 逐字核对（见 Family.ALPINE_EDGE 的注释）
                urls = listOf(
                    if (IS_ARM64)
                        "https://mirrors.aliyun.com/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.8-aarch64.tar.gz"
                    else
                        "https://mirrors.aliyun.com/alpine/v3.23/releases/x86_64/alpine-minirootfs-3.23.8-x86_64.tar.gz",
                    if (IS_ARM64)
                        "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.8-aarch64.tar.gz"
                    else
                        "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/releases/x86_64/alpine-minirootfs-3.23.8-x86_64.tar.gz",
                ),
                sha256 = if (IS_ARM64)
                    "3f1bd6811370264cc363bb991ff15ac67621b239ea6e25ff2eb22af3d0641473"
                else
                    "56e2810205c17a5dff78cdd7b2c2af4ccdef16f18d765d5bb0f527438b136676",
                compressedBytes = if (IS_ARM64) 4_027_855L else 3_702_300L,
                // 与 3.22 同源同构建方式，解压体积按同一比例给（8.5MB 量级）
                extractedBytes = 8_600_000L,
                format = ArchiveFormat.TAR_GZ,
            ),
            interpPath = ALPINE_INTERP,
            installCommand = "apk add",
            nodeStrategy = NodeStrategy.APK_PACKAGE,
            needsGlibcQuirks = false,
        ),
    )

    /**
     * 带 flavor 的组合视图 —— UI 上「Alpine 3.22 / Alpine 3.22 Dev / Alpine 3.22 GUI」
     * 三行都从这里出。
     *
     * 为什么要展开而不是在 [options] 里写死三份：`rootfsDir` 按 `family.id` 算，
     * 三份共享同一目录；写成三条 Option 会让 `find()`/`byFamily()` 的语义变模糊
     * （到底哪个才是「Alpine 3.22 本体」）。展开只在 UI 与 API 层需要。
     *
     * **只给 APK 系展开**：apt 的 ExtraPackages 要另做两步法适配（见
     * `installPackagesApt`），本轮先把 Alpine 这条最常用的做扎实。
     */
    val catalog: List<Option> = options.flatMap { base ->
        if (base.pkgManager != PackageManager.APK) {
            listOf(base)
        } else {
            Flavor.entries.map { f -> base.copy(flavor = f) }
        }
    }

    /**
     * 当前 ABI 上**能真正用起来**的形态（UI 与 API 的候选集）。
     *
     * 与 [catalog] 的区别：catalog 是「全部定义」，这个是「这台机器上真能跑的」。
     * arm64 缺少随包的 x11frsrv/inputd，GUI 形态会被排除 —— 而不是让用户
     * 选完再撞 `x11frsrv: not found`。
     *
     * ⚠️ 不直接把 catalog 过滤掉：`findExact` 与 `/dev/linux/distros` 仍要能
     * 看到被排除的项（带 `available=false`），否则用户切到一半的形态会从列表里
     * 凭空消失，看起来像数据丢了。
     */
    val usableCatalog: List<Option> = catalog.filter { availableOnThisAbi(it.flavor) }

    /** 按 `familyId` + `flavorId` 精确定位（API 用） */
    fun findExact(family: String?, flavor: String?): Option? {
        val fam = family ?: return null
        return catalog.firstOrNull {
            it.family.id == fam && it.flavor.id == (flavor ?: Flavor.BASE.id)
        }
    }

    /** Look up by id; unknown ids return null */
    fun find(id: String?): Option? = id?.let { i -> options.firstOrNull { it.family.id == i } }

    fun byFamily(family: Family): Option =
        options.firstOrNull { it.family == family } ?: error("no option for ${family.id}")

    val defaultOption: Option = options.firstOrNull { it.isDefault } ?: options.first()

    /**
     * Currently selected distro.
     *
     * The selection lives in SharedPreferences rather than inside the rootfs:
     * the rootfs directory itself may be being created or deleted while the
     * user switches, and storing the choice somewhere that can vanish leaves
     * the app not knowing which environment it is in.
     *
     * When nothing was ever selected: prefer an **already deployed** distro
     * (so an existing Alpine install is not reported as an undeployed Ubuntu),
     * otherwise fall back to [defaultOption].
     */
    fun active(ctx: Context): Option {
        val hit = find(prefs(ctx).getString(KEY_ACTIVE, null))
        if (hit != null) {
            /*
             * ★必须把 flavor 组合回来，否则「切到 gui 之后读回 BASE」——
             * 用户点了 GUI、界面显示已切换，但 `activeOption()` 拿到的还是
             * BASE，于是 GUI 的额外包永远不会被 ensureBootstrapPackages 装上，
             * 表现就是「点了开始画面报 Xvfb: not found」，而 UI 一切正常。
             *
             * 用 copy 而不是 findExact：flavor 可能对这个发行版不适用
             * （比如 Ubuntu 不支持 flavor），那种情况下优雅退回 BASE 更像
             * 「忽略了这个形态」而不是「unknown distro」报错。
             */
            val f = activeFlavor(ctx)
            if (hit.pkgManager != PackageManager.APK) return hit
            /*
             * ★组合后必须再校验一次 ABI 可用性。
             *
             * 半写入/陈旧 key/手工改 prefs 都可能让这里拿到一个本机跑不了的形态
             * （典型：arm64 上残留 flavor=gui）。不校验的后果是整个 App 认为
             * 「当前是 GUI 形态」，而实际 x11frsrv/inputd 不存在 —— 用户点开始画面
             * 得到 not found，界面上还显示一切正常。
             *
             * 退回 BASE 而不是报错：容器本身仍然可用（只是没有 GUI 那套预装包），
             * 静静退到最小形态比让整条 Linux 功能不可用更合理。
             */
            if (!availableOnThisAbi(f)) {
                Log.w(TAG, "active flavor ${f.id} unsupported on this ABI; falling back to BASE")
                return hit.copy(flavor = Flavor.BASE)
            }
            return hit.copy(flavor = f)
        }
        return options.firstOrNull { LinuxEnvironmentManager.get(ctx).isDeployed(it) } ?: defaultOption
    }

    /** Switch the active distro. This **only changes the selection**; no files move. */
    fun setActive(ctx: Context, family: Family) {
        prefs(ctx).edit().putString(KEY_ACTIVE, family.id).apply()
        Log.i(TAG, "active rootfs -> ${family.id}")
    }

    /**
     * **原子**切换 family + flavor。
     *
     * ★为什么需要：`setActive()` 与 `setActiveFlavor()` 各自 `apply()` 是两次独立
     * 提交。进程在两次之间被杀（用户切换时正好收到来电、系统回收、崩溃）就会留下
     * 「新 family + 旧 flavor」的不一致状态 —— 而 flavor 是**全局 key**，切到另一个
     * APK family 时会把上一次的 flavor 套上去，可能得到「激活的形态其实是 GUI，
     * 但这台机器是 arm64 用不了」。同一 `edit()` 提交两个 key 就消除了这个窗口。
     *
     * 调用方应优先用这个而非两次单独调用（UI 与 `/dev/linux/switch` 都已改）。
     */
    fun setActiveExact(ctx: Context, family: Family, flavor: Flavor) {
        prefs(ctx).edit()
            .putString(KEY_ACTIVE, family.id)
            .putString(KEY_ACTIVE_FLAVOR, flavor.id)
            .apply()
        Log.i(TAG, "active -> ${family.id}/${flavor.id}")
    }

    /**
     * Switch the active **flavor** of the current family.
     *
     * 与 family 分开存两个 key，而不是拼成一个串（`alpine:gui`）：
     * family 是长期身份（同时是目录名，改了等于换环境），flavor 是可随时
     * 来回切的形态。分开存的好处是只有 family 的旧版本仍能正确读到自己那份，
     * 不会因为多了一个 `:` 就解析失败退回默认。
     *
     * 读的时候由 [active] 组合：family 定位选项、flavor 再挑形态；flavor 缺失
     * 或对不上（比如切走的发行版不支持 flavor）就退回 [Flavor.BASE]。
     */
    fun setActiveFlavor(ctx: Context, flavor: Flavor) {
        prefs(ctx).edit().putString(KEY_ACTIVE_FLAVOR, flavor.id).apply()
        Log.i(TAG, "active flavor -> ${flavor.id}")
    }

    /** 当前激活的 flavor（对不上任何已知值就退回 BASE，绝不抛） */
    fun activeFlavor(ctx: Context): Flavor {
        val id = prefs(ctx).getString(KEY_ACTIVE_FLAVOR, null) ?: return Flavor.BASE
        return Flavor.entries.firstOrNull { it.id == id } ?: Flavor.BASE
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Host path of a distro's rootfs */
    fun rootfsDir(ctx: Context, option: Option): File = File(distrosDir(ctx), option.family.id)

    /** Parent directory holding every distro rootfs */
    fun distrosDir(ctx: Context): File = File(File(ctx.filesDir, "linux"), "distros")

    // ==================== Per-distro configuration ====================

    /**
     * apt sources, in **deb822** format.
     *
     * ## Three measured facts baked into this config
     *
     * 1. **Must be deb822 at `sources.list.d/ubuntu.sources`.** Ubuntu 24.04
     *    ships `/etc/apt/sources.list.d/ubuntu.sources` and **does not read**
     *    the legacy `/etc/apt/sources.list` at all. Verified on device: with
     *    classic `deb http://...` lines written to `sources.list`, `apt-get
     *    update` still fetched from `ports.ubuntu.com`（官方源，国内极慢）；
     *    after replacing the deb822 file with tuna, it hit tuna immediately.
     *
     * 2. **`Trusted: yes` is required.** The base 24.04 image ships `gpgv` but
     *    **no `gnupg`**, so `apt-key` cannot run:
     *    `E: gnupg, gnupg2 and gnupg1 do not seem to be installed, but one of
     *    them is required for this operation`. apt then rejects the repo
     *    ("is not signed") and nothing can be installed. Installing gnupg first
     *    is a chicken-and-egg dead end — it comes from the very repo apt
     *    refuses. So we pin our own CN mirrors over plain http and trust them.
     *
     * 3. **All plain http.** The measured base image has an incomplete
     *    `/etc/ssl/certs`, so https fails with `The certificate issuer is
     *    unknown`.
     */
    fun aptSources(option: Option): List<String> = when (option.family) {
        // ★只保留清华一个镜像：实测阿里云 ubuntu-ports 单源要 30 秒
        // （同一容器、同一网络、同一时刻，清华只要 4 秒），两家叠加时
        // apt-get update 总耗时 32~37 秒，占满 Ubuntu 冷启 63 秒的大半。
        // 双源本来是「一家挂了还有另一家」的冗余，代价是每次冷启多等 30 秒，
        // 不值得 —— 一家不可用时用户会直接看到报错，比默默慢 30 秒好。
        Family.UBUNTU -> listOf(
            "Types: deb",
            "URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/",
            "Suites: noble noble-updates",
            "Components: main universe",
            "Trusted: yes",
        )

        // 3.23 与 3.22 同为 musl + apk，不走 apt，没有 sources 概念
        Family.ALPINE, Family.ALPINE_EDGE -> emptyList()
    }

    /**
     * apt 源文件在 rootfs 内的相对路径。
     *
     * **noble 只认 deb822 这一份**；写到 `etc/apt/sources.list` 是死码
     * （实测 apt-get update 仍走官方源）。详见 [aptSources] 第 1 条。
     */
    val APT_SOURCES_REL: String = "etc/apt/sources.list.d/ubuntu.sources"

    /**
     * apt global options: force IPv4.
     *
     * Measured necessity: IPv6 is unreachable on the test network, so apt tries
     * AAAA records first and stalls until timeout, showing up as `apt-get update`
     * producing no output for a long time. Pinning ForceIPv4 restores full speed.
     */
    val APT_CONF: List<String> = listOf("Acquire::ForceIPv4 \"true\";")

    /**
     * glibc-specific patch script, run once after deploy. Idempotent.
     *
     * ## The one hard failure it fixes: ldconfig segfaults
     *
     * glibc's `/usr/sbin/ldconfig.real` is a **static PIE** (readelf reports
     * `Type=DYN` with no `PT_INTERP`), and `dsh_alpine` only supports the
     * "dynamic interpreter + ordinary executable" combination, so running it
     * dies with SIGSEGV (measured exit 139). The consequence is not just a stale
     * cache: `dpkg --configure libc-bin` fails on that child process and the
     * whole transaction returns non-zero.
     *
     * Fix: `dpkg-divert` the real binary aside and drop an `exit 0` shell script
     * at the original path. When the cache is missing, `ld.so` falls back to its
     * built-in search path (`/lib`, `/usr/lib` and their multiarch subdirs), so
     * the cache is a startup optimisation rather than a functional requirement —
     * measured: apt install / curl / less all work with the shim in place.
     *
     * The critical detail is the divert path: it **must be
     * `/usr/sbin/ldconfig.real`** (the post-merged-usr real path). `/sbin` is a
     * symlink to `usr/sbin`, so diverting the old path silently succeeds but
     * does nothing, and configuring libc-bin still exits 139.
     *
     * The script is written for `/bin/sh`: a glibc base image has **no bash**
     * (it must be installed via apt), but `/bin/sh` (dash) is always present.
     */
    fun glibcQuirksScript(): String = buildString {
        appendLine("set -e")
        appendLine("# ldconfig is a static PIE and segfaults under the container runner;")
        appendLine("# replace it with a no-op shim (ld.so has built-in search paths as fallback).")
        appendLine("if [ -x /usr/sbin/ldconfig.real ] && [ ! -e /usr/sbin/ldconfig.dshreal ]; then")
        appendLine("  dpkg-divert --add --rename --divert /usr/sbin/ldconfig.dshreal /usr/sbin/ldconfig.real || true")
        appendLine("fi")
        appendLine("if [ -e /usr/sbin/ldconfig.dshreal ]; then")
        appendLine("  printf '#!/bin/sh\\nexit 0\\n' > /usr/sbin/ldconfig.real")
        appendLine("  chmod 755 /usr/sbin/ldconfig.real")
        appendLine("fi")
        appendLine("# A killed apt update leaves partial index files behind, which later")
        appendLine("# surfaces as Hash Sum mismatch. Drop them and let apt rebuild.")
        appendLine("rm -rf /var/lib/apt/lists/partial/* 2>/dev/null || true")
        appendLine("exit 0")
    }

    /**
     * Post-install argv (without the container prefix).
     *
     * apt side: the two-step path (download-only, then `dpkg -i`) works around
     * the `cannot stat /tmp/apt-dpkg-install-XXXX/` defect, but `dpkg -i` can
     * leave packages half-configured, so finish with `--configure -a`.
     */
    fun postInstallArgv(option: Option): List<List<String>> = when (option.pkgManager) {
        PackageManager.APK -> emptyList()
        PackageManager.APT -> listOf(listOf("/usr/bin/dpkg", "--configure", "-a"))
    }

    private const val PREFS = "dsh_linux_rootfs"
    private const val KEY_ACTIVE = "active_family"

    /**
     * 激活形态的 key。
     *
     * 与 [KEY_ACTIVE] 分开存的理由见 [setActiveFlavor]：family 是长期身份
     * （同时是目录名），flavor 是可来回切的形态。分成两个 key 之后，
     * 只有 family 的旧版本仍能正确读到自己那份，不会因为多了个分隔符解析失败。
     */
    private const val KEY_ACTIVE_FLAVOR = "active_flavor"
}

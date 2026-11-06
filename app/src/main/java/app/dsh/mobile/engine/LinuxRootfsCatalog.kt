package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
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
    )

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
    )

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
        if (hit != null) return hit
        return options.firstOrNull { LinuxEnvironmentManager.get(ctx).isDeployed(it) } ?: defaultOption
    }

    /** Switch the active distro. This **only changes the selection**; no files move. */
    fun setActive(ctx: Context, family: Family) {
        prefs(ctx).edit().putString(KEY_ACTIVE, family.id).apply()
        Log.i(TAG, "active rootfs -> ${family.id}")
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

        Family.ALPINE -> emptyList()
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
}

package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 扩展中心的 **Linux 模式适配层**（双发行版）。
 *
 * ## 为什么需要这一层
 *
 * 扩展目录（`assets/extensions/catalog.json`）原本描述的是「**Termux 仓库的包**」：
 * 条目 `packages` 里写 `python,python-pip`、`clang,make,ndk-sysroot`，由
 * [ExtensionManager] 拉 Termux 的 `Packages.gz` → 解析依赖闭包 → 逐包下 `.deb`
 * → ar 解包 → `data.tar.xz` → 解 tar 到扩展目录 → 拍平 `usr/` 前缀。
 *
 * 走 Linux 容器路线后，这套机制**整体失效**，原因是三个硬差异：
 *
 * | 维度 | Termux（旧） | Linux 容器（新） |
 * |---|---|---|
 * | 包格式 | `.deb`（ar + tar.xz） | Alpine：`.apk`；Ubuntu：`.deb` |
 * | 包名 | `python` / `python-pip` | Alpine：`python3` / `py3-pip`；Ubuntu：`python3` / `python3-pip` |
 * | 装到哪 | 各扩展独立目录，靠 PATH 拼 | rootfs 的 `/` 全局共享 |
 *
 * 所以 Linux 模式下不能复用 `ExtensionManager.installTask()` 的下载/解包链路，
 * 必须改走 [LinuxPackageManager]（容器内 `apk add` / `apt-get`）。但**目录条目
 * 本身不该分叉**——两份 catalog 会让「同一个扩展在两种模式下名字/分类/描述不
 * 一致」，而且 `check-i18n.py` 要求双语 catalog 的 id 与顺序逐项一致，再加第三
 * 份只会让维护成本翻倍。
 *
 * 因此做法是：**保留一份 catalog**，本文件负责
 * 1. 把 Termux 包名映射成**当前发行版**的包名（[ALPINE_NAMES] / [UBUNTU_NAMES]）；
 * 2. 标记出该发行版仓库里**根本没有**的条目（[ALPINE_UNAVAILABLE] /
 *    [UBUNTU_UNAVAILABLE]），UI 据此置灰；
 * 3. 提供 Linux 模式的统一安装/卸载/状态查询入口。
 *
 * ## 为什么必须有第二张表（两发行版的差异是真实的，不是改名游戏）
 *
 * 同一个 Termux 包名在两个发行版里可能对到**完全不同**的包，甚至一边有另一边
 * 没有。三处最典型的分歧（都是对着真实索引核过的，不靠记忆）：
 *
 * | Termux | Alpine 3.22 | Ubuntu 24.04 (noble) |
 * |---|---|---|
 * | `aapt` / `apksigner` / `gradle` | **没有**（Alpine 不打包 Android SDK 工具） | **有**（universe 仓库） |
 * | `aapt2` / `d8` | 没有 | **没有**（d8 要自己从 Android SDK 拿；`dalvik-exchange` 只提供 dx） |
 * | `clang` | `clang17`（主仓库无裸 clang） | `clang` 本身就是元包（→ clang-18） |
 * | `sqlite` | `sqlite` | `sqlite3` |
 * | `openssh` | `openssh`（客户端+服务端同一个包） | 拆包，本表只装 `openssh-client` |
 *
 * 表里任何一项写错，用户在 App 里点「安装」只会拿到
 *
 *     ERROR: unable to select packages:  /  E: Unable to locate package
 *
 * 这种报错最容易出现在跨发行版改名上，靠人肉记忆迟早写错。所以两表都**只列
 * 确实需要改名的**，其余同名透传；映射不到的一律进 `unavailable`，**绝不猜**。
 *
 * ## 包名映射的来源
 *
 * - Alpine：`https://mirrors.aliyun.com/alpine/v3.22/{main,community}/aarch64/APKINDEX.tar.gz`
 *   （main 5666 + community 20500 = 26160 个不同包名）
 * - Ubuntu 24.04：`https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/dists/noble/{main,universe}/binary-arm64/Packages.gz`
 *   （合计 69641 个不同包名，快照见 `scripts/probes/data/aptindex-names.txt.gz`）
 */
object LinuxExtensionBridge {

    private const val TAG = "LinuxExt"

    /** 一套「Termux 包名 → 该发行版包名」的完整映射 */
    private class DistroMap(
        val renames: Map<String, List<String>>,
        val unavailable: Map<String, String>,
        val itemUnavailable: Map<String, String>,
    )

    /**
     * Termux 包名 → **Alpine** 包名。
     *
     * 只列**确实需要改名**的；同名的直接透传（见 [packagesOf]）。
     * 每一项都对着 APKINDEX 核过，不靠记忆。
     */
    private val ALPINE_NAMES: Map<String, List<String>> = mapOf(
        "python" to listOf("python3"),
        "python-pip" to listOf("py3-pip"),
        // Termux 的 lua 是 `lua54`，Alpine 是 `lua5.4`
        "lua54" to listOf("lua5.4"),
        "lua53" to listOf("lua5.3"),
        "lua51" to listOf("lua5.1"),
        // Termux 打包 xz-utils，Alpine 叫 xz
        "xz-utils" to listOf("xz"),
        // Termux 的 golang，Alpine 叫 go
        "golang" to listOf("go"),
        // Termux 的 ninja → Alpine 社区仓库的 ninja-build。
        // ⚠️ ninja-build 把二进制装在 `/usr/lib/ninja-build/bin/ninja`，
        //    并不会创建 `/usr/bin/ninja`；必须再装 `ninja-is-really-ninja`
        //    才会生成那个 symlink（实测：只装前者时 `ninja --version` → not found）。
        "ninja" to listOf("ninja-build", "ninja-is-really-ninja"),
        // Termux 的 php 是不带版本号的主包；Alpine 只有 php83 这种带版本的
        "php" to listOf("php83"),
        // Termux 的 openjdk-17 → Alpine 的 openjdk17（无连字符）
        "openjdk-17" to listOf("openjdk17"),
        "openjdk-21" to listOf("openjdk21"),
        // clang：Termux 是 `clang`，Alpine 主仓库没有裸 clang，只有 clang17/clang18 等
        "clang" to listOf("clang17"),
        // Termux 的 ndk-sysroot（bionic 头文件）在 musl 下对应 musl-dev
        "ndk-sysroot" to listOf("musl-dev"),
        // Termux 有 pkg-config（一个独立的 C 程序包），Alpine 的主实现叫 pkgconf，
        // 二进制同时提供 /usr/bin/pkgconf 与 /usr/bin/pkg-config 两个名字。
        "pkg-config" to listOf("pkgconf"),
        // ---- v1.6.20：头文件类条目的拆分差异 ----
        // Termux 把「运行库 + 头文件」打在同一个包里（openssl 一个 .deb 就有
        // 142 个 .h），Alpine 拆成 openssl（库+CLI）与 openssl-dev（头文件）。
        // headers-c 要的是头文件，所以必须把 -dev 一起带上，否则 Linux 模式下
        // 装完 `#include <openssl/ssl.h>` 照样找不到。
        "openssl" to listOf("openssl", "openssl-dev"),
        "zlib" to listOf("zlib", "zlib-dev"),
        // Termux 的 CLI 在 openssl-tool 里（Termux 的 openssl 包只带库与头），
        // Alpine 的 CLI 直接在 openssl 包里（实测 apk 内容：唯一文件 usr/bin/openssl）。
        "openssl-tool" to listOf("openssl"),
    )

    /**
     * Termux 包名 → **Ubuntu 24.04 (noble)** 包名。
     *
     * 与 Alpine 表的关键分歧（见类注释的对照表）：
     * - `clang` **同名透传**：Ubuntu 的 `clang` 是元包（当前指向 clang-18），
     *   并不像 Alpine 那样必须指名 `clang17`。
     * - `ndk-sysroot` 在 glibc 下对应 `libc6-dev`（头文件 + crt 对象）。
     * - `openssh` 只装 `openssh-client`：Ubuntu 把 sshd 拆成 `openssh-server`，
     *   而后者在无 init 的容器里 postinst 会吵，需要服务端的人可以在终端里
     *   自己 `apt-get install openssh-server`。
     * - `rust` 拆成 `rustc` + `cargo`：Termux 的 rust 一个包自带两者。
     * - `sqlite` → `sqlite3`（Ubuntu 没有叫 `sqlite` 的包）。
     * - `android-tools` → `adb` + `fastboot`：Termux 合包，Ubuntu 拆包。
     */
    private val UBUNTU_NAMES: Map<String, List<String>> = mapOf(
        "python" to listOf("python3"),
        "python-pip" to listOf("python3-pip"),
        "lua54" to listOf("lua5.4"),
        "lua53" to listOf("lua5.3"),
        "lua51" to listOf("lua5.1"),
        // Termux 的 rust 自带 rustc + cargo;Ubuntu 拆成两个包
        "rust" to listOf("rustc", "cargo"),
        // Termux 的 openjdk-17 是 JDK;Ubuntu 的主包是 openjdk-17-jdk
        "openjdk-17" to listOf("openjdk-17-jdk"),
        "openjdk-21" to listOf("openjdk-21-jdk"),
        // Termux 的 ndk-sysroot（bionic）在 glibc 下对应 libc6-dev
        "ndk-sysroot" to listOf("libc6-dev"),
        // Termux 的 readline 含头文件与静态库,对应 Debian 系的 -dev 包
        "readline" to listOf("libreadline-dev"),
        "sqlite" to listOf("sqlite3"),
        "openssh" to listOf("openssh-client"),
        "android-tools" to listOf("adb", "fastboot"),
        // Ubuntu 的 ninja-build 直接生成 /usr/bin/ninja,不需要 Alpine 那个补丁包
        "ninja" to listOf("ninja-build"),
        // ★合并（2026-11-09）：以下三条是 main 的扩展目录新增条目带来的包名，
        //   Alpine 侧同名透传即可，Ubuntu 侧三处都得改名（对着 noble 索引核过）：
        "dtc" to listOf("device-tree-compiler"),
        "zlib-dev" to listOf("zlib1g-dev"),
        "openssl-dev" to listOf("libssl-dev"),
        // 这三条是 main 36362dfe 的逆向分类 + headers-c 带来的：Ubuntu 侧都得改名
        // （zlib / openssl-tool 是 Alpine 的拆包名，Ubuntu 用 zlib1g 与 openssl；
        //   upx 在 noble 叫 upx-ucl）。
        "zlib" to listOf("zlib1g", "zlib1g-dev"),
        "openssl-tool" to listOf("openssl"),
        "upx" to listOf("upx-ucl"),
    )

    /**
     * **Alpine** 仓库里没有对应物的 Termux 包，以及替代方案说明。
     *
     * 这些不是「懒得映射」，是实测 26160 个包里搜不到：
     * - `aapt`/`aapt2`/`apksigner`/`d8`：Android SDK 工具，Alpine 不打包（用宿主侧的）
     * - `llama-cpp`：Alpine 无此包
     * - `gradle`：**有**（community 仓库），但 Alpine 的 gradle 依赖 openjdk，闭包极大
     * - `android-tools`：**有**（community，提供 adb/fastboot），故不列入此表
     */
    private val ALPINE_UNAVAILABLE: Map<String, String> = mapOf(
        "aapt" to "Android build-tools are not packaged by Alpine; use the host-side copy",
        "aapt2" to "Android build-tools are not packaged by Alpine; use the host-side copy",
        "apksigner" to "Android build-tools are not packaged by Alpine; use the host-side copy",
        "d8" to "Android build-tools are not packaged by Alpine; use the host-side copy",
        "llama-cpp" to "not available in the Alpine repositories",
        // v1.6.20：逆向类条目的包在 Alpine 的缺口（对着 v3.22 的
        // main 5666 + community 20495 = 26160 个包名逐个搜过）
        //
        // ★合并（2026-11-09）：rizin 那条已删 —— main 36362dfe 重做逆向分类时
        //   把 rizin 条目整个去掉了，留着会让「UNAVAILABLE 的 key 必须在 catalog
        //   里真实用到」这条守护断言变红（闲置映射说明表在腐烂）。
        "binwalk" to "not packaged by Alpine; install it from Termux in the non-Linux extension store",
        "apktool" to "not packaged by Alpine",
        "jadx" to "not packaged by Alpine",
        "dex2jar" to "not packaged by Alpine",
        // gcovr / lcov 在 Termux 里没有，但 **Alpine community 里都有**
        // （实测 gcovr 8.3-r0 / lcov 2.0-r3），所以它们绝不能进 UNAVAILABLE ——
        // 那等于把 Linux 模式下的现成能力凭空标死。它们在 catalog 里由
        // linuxOnly 标记，只在 Termux 模式下置灰。
    )

    /**
     * **Ubuntu 24.04** 里没有对应物的 Termux 包。
     *
     * ⚠️ 这里只有三个 —— 与 Alpine 的五个形成对比：Ubuntu 的 universe 仓库
     * **确实打包了** `aapt`、`apksigner`、`gradle`（实测 noble arm64 索引里有），
     * 所以 `android-buildtools` 在 Ubuntu 上是「部分可装」而不是「不可用」。
     * 把这三个也照 Alpine 那样一股脑置灰，会让 Ubuntu 用户白丢三个能用的工具。
     */
    private val UBUNTU_UNAVAILABLE: Map<String, String> = mapOf(
        "aapt2" to "aapt2 is not packaged by Ubuntu; use the Android SDK copy or the bundled aapt",
        "d8" to "d8 is not packaged by Ubuntu; dalvik-exchange only ships the older dx",
        "llama-cpp" to "not available in the Ubuntu repositories",
        // ★合并（2026-11-09）：下面三条在 Alpine 有、Ubuntu 24.04 **确实没有**
        //   （对着 noble 的 69648 个包名核过，不是猜的），必须显式列不可用，
        //   否则会被当成「同名透传」→ 安装时 E: Unable to locate package。
        "gcc-aarch64-none-elf" to
            "Ubuntu has gcc-aarch64-linux-gnu (Linux target) and gcc-arm-none-eabi (32-bit), " +
            "but no bare-metal aarch64 cross compiler",
        "zig" to "zig is not packaged by Ubuntu 24.04",
        // ★合并（2026-11-09）：逆向分类用的两个 JVM 反编译工具在 noble 里没有
        //   （对着 69648 个包名核过；apktool 有、jadx/dex2jar 没有）。
        "jadx" to "jadx is not packaged by Ubuntu 24.04; use apktool or the Termux build",
        "dex2jar" to "dex2jar is not packaged by Ubuntu 24.04; use apktool or the Termux build",
    )

    /**
     * **Alpine** 下整个条目不可用（不是个别包缺失，而是核心包全缺）。
     * 值为给用户看的原因。
     */
    private val ALPINE_ITEM_UNAVAILABLE: Map<String, String> = mapOf(
        "android-buildtools" to
            "aapt/aapt2/apksigner/d8 are Android SDK tools, not packaged by Alpine",
        "llama-cpp" to "llama-cpp is not available in the Alpine repositories",
        // v1.6.20 逆向类：这几个在 Alpine 的 26160 个包里确实没有对应物，
        // 与其让用户点了才报 "ERROR: unable to select packages"，
        // 不如在 Linux 模式下直接置灰并说明原因。
        "binwalk" to "binwalk is not packaged by Alpine (Termux mode only)",
        "apk-reverse" to
            "apktool / jadx / dex2jar are not packaged by Alpine (Termux mode only)",
    )

    /**
     * **Ubuntu** 下整个条目不可用。
     *
     * 只有 llama-cpp：`android-buildtools` 在 Ubuntu 上 aapt / apksigner / gradle
     * 都能装（只有 aapt2 与 d8 缺），属于「部分可装」，不该整条置灰——缺的那两个
     * 由 [partialNote] 在安装对话框里明说。
     */
    private val UBUNTU_ITEM_UNAVAILABLE: Map<String, String> = mapOf(
        "llama-cpp" to "llama-cpp is not available in the Ubuntu repositories",
        // zig 条目只装一个包，而 zig 本身不在 Ubuntu 仓库 —— 整条置灰比
        // 「装了个空列表」清楚。
        "zig" to "zig is not packaged by Ubuntu 24.04",
    )

    private val ALPINE_MAP = DistroMap(ALPINE_NAMES, ALPINE_UNAVAILABLE, ALPINE_ITEM_UNAVAILABLE)
    private val UBUNTU_MAP = DistroMap(UBUNTU_NAMES, UBUNTU_UNAVAILABLE, UBUNTU_ITEM_UNAVAILABLE)

    /** 取指定发行版的映射表 */
    private fun distroMap(family: LinuxRootfsCatalog.Family): DistroMap =
        if (family == LinuxRootfsCatalog.Family.UBUNTU) UBUNTU_MAP else ALPINE_MAP

    /**
     * 当前部署中的发行版。
     *
     * 每次现取而不缓存：用户切发行版后应当立刻生效（切换会重启引擎，但本页可能
     * 在重启前就被重新创建）。
     */
    fun activeFamily(ctx: Context): LinuxRootfsCatalog.Family =
        runCatching { LinuxEnvironmentManager.get(ctx).activeOption().family }
            .getOrDefault(LinuxRootfsCatalog.Family.ALPINE)

    /** 把一条 catalog 条目的 Termux 包名列表翻成指定发行版的包名列表（去重、保序） */
    fun packagesOf(packages: List<String>, family: LinuxRootfsCatalog.Family): List<String> {
        val map = distroMap(family)
        val out = LinkedHashSet<String>()
        for (p in packages) {
            if (map.unavailable.containsKey(p)) continue
            // 一条 Termux 包名可能对应多个包（ninja → ninja-build + ninja-is-really-ninja，
            // rust → rustc + cargo），故用 List 而非单值。
            map.renames[p]?.let { out += it } ?: run { out += p }
        }
        return out.toList()
    }

    /**
     * 该条目在指定发行版下是否**整体**可装；null = 可装，非 null = 不可装的原因。
     *
     * 注意「可装」不等于「包的都齐了」：Ubuntu 下 `android-buildtools` 是可装的
     * （aapt/apksigner/gradle 齐），只是缺 aapt2/d8 —— 那部分由 [partialNote] 交代。
     */
    fun unavailableReason(
        ext: ExtensionManager.Extension,
        family: LinuxRootfsCatalog.Family,
    ): String? {
        val map = distroMap(family)
        map.itemUnavailable[ext.id]?.let { return it }
        if (ext.apkUrl.isNotBlank()) {
            // APK 型扩展与 Linux 无关：它本来就是装一个 Android 应用
            return null
        }
        if (packagesOf(ext.packages, family).isEmpty()) {
            return "none of its packages exist in the ${label(family)} repositories"
        }
        return null
    }

    /**
     * 条目可装、但其中有包在该发行版里缺失时的提示（null = 一个都不缺）。
     *
     * 存在的意义：`android-buildtools` 在 Ubuntu 上会静默少装 `aapt2` 与 `d8`。
     * 不提示的话，用户装完发现 `aapt2` 命令不存在，只会以为装失败了。
     */
    fun partialNote(
        ext: ExtensionManager.Extension,
        family: LinuxRootfsCatalog.Family,
    ): String? {
        if (ext.apkUrl.isNotBlank()) return null
        val map = distroMap(family)
        val missing = ext.packages.filter { map.unavailable.containsKey(it) }
        if (missing.isEmpty()) return null
        return missing.joinToString(", ")
    }

    /**
     * 发行版的人类可读名（用于拼错误提示；不本地化，因为它是专有名词的一部分）。
     *
     * 公开给 UI 用：装包进度窗要显示「在哪个发行版里装几个包」，
     * 各页面自己拼一份容易和这里的口径漂移。
     */
    fun label(family: LinuxRootfsCatalog.Family): String =
        if (family == LinuxRootfsCatalog.Family.UBUNTU) "Ubuntu" else "Alpine"
    /**
     * 把「选中的版本」解析成实际要装的包名列表（并按发行版映射）。
     *
     * [versionId] 为 null 或空 → 用扩展自带的默认 [ExtensionManager.Extension.packages]
     * （与旧行为一致）。给了但找不到 → 返回 null，调用方必须当成错误报出去，
     * **不能静默回落到默认版本** —— 否则用户选了 openjdk21 却装了 17，
     * 而界面上还显示「已装」，是最难查的那类错。
     */
    fun resolvePackages(
        ext: ExtensionManager.Extension,
        versionId: String?,
        family: LinuxRootfsCatalog.Family,
    ): List<String>? {
        if (versionId.isNullOrBlank()) return packagesOf(ext.packages, family)
        val v = ext.versions.firstOrNull { it.id == versionId } ?: return null
        return packagesOf(v.packages, family)
    }

    /**
     * Linux 模式下的安装（发行版分派 + 版本选择）。
     *
     * 与 [ExtensionManager] 的关键差异：**装到 rootfs 的全局位置**，不是各扩展
     * 独立目录。因此状态判断也必须换 —— 不能看扩展目录里的 MARKER 文件，
     * 要问容器的包数据库（`apk info` / `dpkg-query`）该包在不在。
     *
     * ★合并说明（2026-11-09）：本函数同时承载两条独立演进 ——
     *   ① main 的多版本选择（versionId，扩展中心多版本）
     *   ② feat/linux-workspace 的发行版分派与实时输出（family + onLine）
     *   两者参数互不重叠，故签名取并集；漏任一个都会让对应功能静默失效
     *   （漏 versionId → 选了 21 装 17；漏 onLine → 装包窗口永远空屏）。
     */
    fun install(
        ctx: Context,
        ext: ExtensionManager.Extension,
        versionId: String? = null,
        onLine: ((String) -> Unit)? = null,
    ): LinuxPackageManager.OpResult {
        val family = activeFamily(ctx)
        unavailableReason(ext, family)?.let {
            return LinuxPackageManager.OpResult(false, emptyList(), "unavailable: $it")
        }
        if (!versionId.isNullOrBlank() && ext.versions.none { it.id == versionId }) {
            return LinuxPackageManager.OpResult(
                false, emptyList(), "unknown version: $versionId",
            )
        }
        val pkgs = resolvePackages(ext, versionId, family)
            ?: return LinuxPackageManager.OpResult(false, emptyList(), "version has no packages")
        if (pkgs.isEmpty()) {
            return LinuxPackageManager.OpResult(false, emptyList(), "no packages to install")
        }
        Log.i(
            TAG,
            "installing ${ext.id}${versionId?.let { ":$it" } ?: ""} on ${label(family)} " +
                "as ${pkgs.joinToString(" ")}",
        )
        return LinuxPackageManager.get(ctx).install(pkgs, onLine = onLine)
    }

    fun remove(
        ctx: Context,
        ext: ExtensionManager.Extension,
        versionId: String? = null,
        onLine: ((String) -> Unit)? = null,
    ): LinuxPackageManager.OpResult {
        val family = activeFamily(ctx)
        val pkgs = resolvePackages(ext, versionId, family)
            ?: return LinuxPackageManager.OpResult(false, emptyList(), "unknown version")
        if (pkgs.isEmpty()) {
            return LinuxPackageManager.OpResult(false, emptyList(), "no packages to remove")
        }
        return LinuxPackageManager.get(ctx).remove(pkgs, onLine = onLine)
    }

    /**
     * Linux 模式下的状态判定。
     *
     * 语义对齐 [ExtensionManager.ExtState] 的三态：
     * - 全部包都在 → [ExtensionManager.ExtState.ACTIVATED]（Linux 里装好即可用，没有额外「激活」步骤）
     * - 部分在 → [ExtensionManager.ExtState.DOWNLOADED]
     * - 都不在 → [ExtensionManager.ExtState.NOT_DOWNLOADED]
     */
    fun state(
        ctx: Context,
        ext: ExtensionManager.Extension,
        installed: Map<String, String>? = null,
        versionId: String? = null,
        family: LinuxRootfsCatalog.Family = activeFamily(ctx),
    ): ExtensionManager.ExtState {
        if (ext.apkUrl.isNotBlank()) {
            // APK 型扩展与运行模式无关：它本身就是装一个 Android 应用。
            // 这里不转调 ExtensionManager（它是 per-Context 实例，由 DshApp 持有），
            // 就地按包名是否已装判断，语义与 ExtensionManager.state() 完全一致。
            val pkg = ext.apkPackage
            val installedApk = runCatching {
                ctx.packageManager.getInstalledPackages(0).any { it.packageName == pkg }
            }.getOrDefault(false)
            return if (installedApk) {
                ExtensionManager.ExtState.ACTIVATED
            } else {
                ExtensionManager.ExtState.NOT_DOWNLOADED
            }
        }
        if (unavailableReason(ext, family) != null) return ExtensionManager.ExtState.NOT_DOWNLOADED
        // 版本未知时按「未装」处理而不是回落到默认版本 —— 回落会让 UI 把
        // 「选了 A 但没装」显示成「已装 B」，是静默错误。
        val pkgs = resolvePackages(ext, versionId, family)
            ?: return ExtensionManager.ExtState.NOT_DOWNLOADED
        if (pkgs.isEmpty()) return ExtensionManager.ExtState.NOT_DOWNLOADED
        val have = installed ?: LinuxPackageManager.get(ctx).installed()
        val present = pkgs.count { have.containsKey(it) }
        return when (present) {
            0 -> ExtensionManager.ExtState.NOT_DOWNLOADED
            pkgs.size -> ExtensionManager.ExtState.ACTIVATED
            else -> ExtensionManager.ExtState.DOWNLOADED
        }
    }

    /** 该条目已装包 / 总包数，供 UI 显示「2/3 装好」这种细节 */
    fun installProgress(
        ext: ExtensionManager.Extension,
        installed: Map<String, String>,
        versionId: String? = null,
        family: LinuxRootfsCatalog.Family,
    ): Pair<Int, Int> {
        val pkgs = resolvePackages(ext, versionId, family) ?: return 0 to 0
        return pkgs.count { installed.containsKey(it) } to pkgs.size
    }

    /** catalog 里所有条目在指定发行版下需要装的包总数（摸底用） */
    fun allPackages(
        catalog: List<ExtensionManager.Extension>,
        family: LinuxRootfsCatalog.Family,
    ): List<String> {
        val out = LinkedHashSet<String>()
        for (e in catalog) {
            if (e.apkUrl.isNotBlank()) continue
            if (unavailableReason(e, family) != null) continue
            // 默认版本 + 所有可选版本都算进去：批量「一键装齐」时若只装默认版本，
            // 用户切到别的版本又要联网再下一遍。多版本体积叠加由 apk 自己算。
            out += packagesOf(e.packages, family)
            for (v in e.versions) out += packagesOf(v.packages, family)
        }
        return out.toList()
    }

    /** 给日志/诊断用的一份摘要（两个发行版各需要多少包） */
    fun describeForLog(catalog: List<ExtensionManager.Extension>): JSONObject = JSONObject().apply {
        for (f in LinuxRootfsCatalog.Family.entries) {
            put(
                f.id,
                JSONObject().apply {
                    put("packages", allPackages(catalog, f).size)
                    put(
                        "unavailableItems",
                        catalog.count { unavailableReason(it, f) != null },
                    )
                },
            )
        }
    }
}

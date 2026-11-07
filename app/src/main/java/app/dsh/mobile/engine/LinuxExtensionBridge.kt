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
    )

    /**
     * **Alpine** 下整个条目不可用（不是个别包缺失，而是核心包全缺）。
     * 值为给用户看的原因。
     */
    private val ALPINE_ITEM_UNAVAILABLE: Map<String, String> = mapOf(
        "android-buildtools" to
            "aapt/aapt2/apksigner/d8 are Android SDK tools, not packaged by Alpine",
        "llama-cpp" to "llama-cpp is not available in the Alpine repositories",
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
     * Linux 模式下的安装。
     *
     * 与 [ExtensionManager] 的关键差异：**装到 rootfs 的全局位置**，不是各扩展
     * 独立目录。因此状态判断也必须换 —— 不能看扩展目录里的 MARKER 文件，
     * 要问容器的包数据库（`apk info` / `dpkg-query`）该包在不在。
     */
    fun install(
        ctx: Context,
        ext: ExtensionManager.Extension,
        onLine: ((String) -> Unit)? = null,
    ): LinuxPackageManager.OpResult {
        val family = activeFamily(ctx)
        unavailableReason(ext, family)?.let {
            return LinuxPackageManager.OpResult(false, emptyList(), "unavailable: $it")
        }
        val pkgs = packagesOf(ext.packages, family)
        if (pkgs.isEmpty()) {
            return LinuxPackageManager.OpResult(false, emptyList(), "no packages to install")
        }
        Log.i(TAG, "installing ${ext.id} on ${label(family)} as ${pkgs.joinToString(" ")}")
        return LinuxPackageManager.get(ctx).install(pkgs, onLine = onLine)
    }

    fun remove(
        ctx: Context,
        ext: ExtensionManager.Extension,
        onLine: ((String) -> Unit)? = null,
    ): LinuxPackageManager.OpResult {
        val pkgs = packagesOf(ext.packages, activeFamily(ctx))
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
        val pkgs = packagesOf(ext.packages, family)
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
        ctx: Context,
        ext: ExtensionManager.Extension,
        installed: Map<String, String>,
        family: LinuxRootfsCatalog.Family = activeFamily(ctx),
    ): Pair<Int, Int> {
        val pkgs = packagesOf(ext.packages, family)
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
            out += packagesOf(e.packages, family)
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

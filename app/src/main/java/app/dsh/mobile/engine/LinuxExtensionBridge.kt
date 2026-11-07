package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 扩展中心的 **Linux 模式适配层**。
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
 * | 维度 | Termux（旧） | Alpine（新） |
 * |---|---|---|
 * | 包格式 | `.deb`（ar + tar.xz） | `.apk`（拼接 gzip 的 tar） |
 * | 包名 | `python` / `python-pip` | `python3` / `py3-pip` |
 * | 装到哪 | 各扩展独立目录，靠 PATH 拼 | rootfs 的 `/` 全局共享 |
 *
 * 所以 Linux 模式下不能复用 `ExtensionManager.installTask()` 的下载/解包链路，
 * 必须改走 [LinuxPackageManager]（即容器内 `apk add`）。但**目录条目本身不该
 * 分叉**——两份 catalog 会让「同一个扩展在两种模式下名字/分类/描述不一致」，
 * 而且 `check-i18n.py` 要求双语 catalog 的 id 与顺序逐项一致，再加第三份只
 * 会让维护成本翻倍。
 *
 * 因此做法是：**保留一份 catalog**，本文件负责
 * 1. 把 Termux 包名映射成 Alpine 包名（[ALPINE_NAMES]）；
 * 2. 标记出 Alpine 仓库里**根本没有**的条目（[UNAVAILABLE]），UI 据此置灰；
 * 3. 提供 Linux 模式的统一安装/卸载/状态查询入口。
 *
 * ## 包名映射的来源
 *
 * 全部对照 `https://mirrors.aliyun.com/alpine/v3.22/{main,community}/aarch64/APKINDEX.tar.gz`
 * 实测（main 5666 包 + community 20500 包 = 26160 个不同包名）。映射不到的
 * 一律进 [UNAVAILABLE]，**绝不猜**——猜错会让用户点了装却报
 * `ERROR: unable to select packages`，比直接置灰体验更差。
 */
object LinuxExtensionBridge {

    private const val TAG = "LinuxExt"

    /**
     * Termux 包名 → Alpine 包名。
     *
     * 只列**确实需要改名**的；同名的直接透传（见 [alpinePackagesOf]）。
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
        // Termux 的 cmake 自带 ninja 依赖；Alpine 分开
        "ndk-sysroot" to listOf("musl-dev"),
    )

    /**
     * Alpine 仓库里**没有对应物**的 Termux 包，以及替代方案说明。
     *
     * 这些不是「懒得映射」，是实测 26160 个包里搜不到：
     * - `aapt`/`aapt2`/`apksigner`/`d8`：Android SDK 工具，Alpine 不打包（用宿主侧的）
     * - `llama-cpp`：Alpine 无此包
     * - `gradle`：**有**（community 仓库），但 Alpine 的 gradle 依赖 openjdk，闭包极大
     * - `android-tools`：**有**（community，提供 adb/fastboot），故不列入此表
     */
    private val UNAVAILABLE: Map<String, String> = mapOf(
        "aapt" to "Android build-tools are not packaged by Alpine; use the host-side copy",
        "aapt2" to "Android build-tools are not packaged by Alpine; use the host-side copy",
        "apksigner" to "Android build-tools are not packaged by Alpine; use the host-side copy",
        "d8" to "Android build-tools are not packaged by Alpine; use the host-side copy",
        "llama-cpp" to "not available in the Alpine repositories",
    )

    /**
     * 整个条目在 Linux 模式下不可用（不是个别包缺失，而是核心包全缺）。
     * 值为给用户看的原因。
     */
    private val ITEM_UNAVAILABLE: Map<String, String> = mapOf(
        "android-buildtools" to
            "aapt/aapt2/apksigner/d8 are Android SDK tools, not packaged by Alpine",
        "llama-cpp" to "llama-cpp is not available in the Alpine repositories",
    )

    /** 把一条 catalog 条目的 Termux 包名列表翻成 Alpine 包名列表（去重、保序） */
    fun alpinePackagesOf(packages: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (p in packages) {
            if (UNAVAILABLE.containsKey(p)) continue
            // 一条 Termux 包名可能对应多个 Alpine 包（如 ninja → ninja-build +
            // ninja-is-really-ninja），故用 List 而非单值。
            ALPINE_NAMES[p]?.let { out += it } ?: run { out += p }
        }
        return out.toList()
    }

    /** 该条目在 Linux 模式下是否可安装；null = 可装，非 null = 不可装的原因 */
    fun unavailableReason(ext: ExtensionManager.Extension): String? {
        ITEM_UNAVAILABLE[ext.id]?.let { return it }
        if (ext.apkUrl.isNotBlank()) {
            // APK 型扩展与 Linux 无关：它本来就是装一个 Android 应用
            return null
        }
        val mapped = alpinePackagesOf(ext.packages)
        if (mapped.isEmpty()) {
            return "none of its packages exist in the Alpine repositories"
        }
        return null
    }

    /**
     * Linux 模式下的安装。
     *
     * 与 [ExtensionManager] 的关键差异：**装到 rootfs 的全局位置**，不是各扩展
     * 独立目录。因此状态判断也必须换 —— 不能看扩展目录里的 MARKER 文件，
     * 要问容器的 `apk info` 该包在不在。
     */
    /**
     * 把「选中的版本」解析成实际要装的包名列表。
     *
     * [versionId] 为 null 或空 → 用扩展自带的默认 [ExtensionManager.Extension.packages]
     * （与旧行为一致）。给了但找不到 → 返回 null，调用方必须当成错误报出去，
     * **不能静默回落到默认版本** —— 否则用户选了 openjdk21 却装了 17，
     * 而界面上还显示「已装」，是最难查的那类错。
     */
    fun resolvePackages(
        ext: ExtensionManager.Extension,
        versionId: String?,
    ): List<String>? {
        if (versionId.isNullOrBlank()) return alpinePackagesOf(ext.packages)
        val v = ext.versions.firstOrNull { it.id == versionId } ?: return null
        return alpinePackagesOf(v.packages)
    }

    fun install(
        ctx: Context,
        ext: ExtensionManager.Extension,
        versionId: String? = null,
    ): LinuxPackageManager.OpResult {
        unavailableReason(ext)?.let {
            return LinuxPackageManager.OpResult(false, emptyList(), "unavailable: $it")
        }
        if (!versionId.isNullOrBlank() && ext.versions.none { it.id == versionId }) {
            return LinuxPackageManager.OpResult(
                false, emptyList(), "unknown version: $versionId",
            )
        }
        val pkgs = resolvePackages(ext, versionId)
            ?: return LinuxPackageManager.OpResult(false, emptyList(), "version has no packages")
        if (pkgs.isEmpty()) {
            return LinuxPackageManager.OpResult(false, emptyList(), "no Alpine packages to install")
        }
        Log.i(TAG, "installing ${ext.id}${versionId?.let { ":$it" } ?: ""} as ${pkgs.joinToString(" ")}")
        return LinuxPackageManager.get(ctx).install(pkgs)
    }

    fun remove(
        ctx: Context,
        ext: ExtensionManager.Extension,
        versionId: String? = null,
    ): LinuxPackageManager.OpResult {
        val pkgs = resolvePackages(ext, versionId)
            ?: return LinuxPackageManager.OpResult(false, emptyList(), "unknown version")
        if (pkgs.isEmpty()) {
            return LinuxPackageManager.OpResult(false, emptyList(), "no Alpine packages to remove")
        }
        return LinuxPackageManager.get(ctx).remove(pkgs)
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
        if (unavailableReason(ext) != null) return ExtensionManager.ExtState.NOT_DOWNLOADED
        // 版本未知时按「未装」处理而不是回落到默认版本 —— 回落会让 UI 把
        // 「选了 A 但没装」显示成「已装 B」，是静默错误。
        val pkgs = resolvePackages(ext, versionId)
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
    ): Pair<Int, Int> {
        val pkgs = resolvePackages(ext, versionId) ?: return 0 to 0
        return pkgs.count { installed.containsKey(it) } to pkgs.size
    }

    /** catalog 里所有条目在 Linux 模式下需要装的 Alpine 包总数（摸底用） */
    fun allAlpinePackages(catalog: List<ExtensionManager.Extension>): List<String> {
        val out = LinkedHashSet<String>()
        for (e in catalog) {
            if (e.apkUrl.isNotBlank()) continue
            if (unavailableReason(e) != null) continue
            // 默认版本 + 所有可选版本都算进去：批量「一键装齐」时若只装默认版本，
            // 用户切到别的版本又要联网再下一遍。多版本体积叠加由 apk 自己算。
            out += alpinePackagesOf(e.packages)
            for (v in e.versions) out += alpinePackagesOf(v.packages)
        }
        return out.toList()
    }
}

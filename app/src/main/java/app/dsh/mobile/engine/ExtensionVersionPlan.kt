package app.dsh.mobile.engine

/**
 * 扩展中心**多版本条目**的「哪一版真的装着」纯逻辑（v1.7.12）。
 *
 * ## 为什么单独抽一个文件
 *
 * [LinuxExtensionBridge] 依赖 `Context` / `Log`，进不了宿主 JVM 的断言集；
 * 而这里要守的两条恰恰是「算错了界面上看不出来」的静默错误，
 * 必须能用真实数据在 JVM 上跑一遍（见 `scripts/jvm/BehaviourTest.kt` 的
 * `assertExtVersionPlan()`）。本文件零 Android 依赖，只做集合运算。
 *
 * ## 它修的是什么
 *
 * 旧实现里条目的三态完全由「**当前选中的那一版**」的包决定
 * （`state(ext, installed, versionId)`）。多版本条目一旦切换胶囊，
 * 同一个条目会在「已安装 / 未安装」之间来回翻，原因有两类，都是数据形态上的：
 *
 * 1. **默认版的包集与版本项不一致**。catalog 里 `lua` 默认是
 *    `['lua54','readline']`（2 个包），而版本项 `lua5.4` 只有 `['lua5.4']`（1 个）。
 *    用户装了 `lua5.4` 之后切回「默认」（versionId = null），按 2 个包判 →
 *    readline 不在 → 显示「部分安装」，于是又出现「安装」按钮。
 *    `clang` 同理（默认 `clang+make+ndk-sysroot`，版本项各只有一个包）。
 * 2. **装的是哪一版，界面无从得知**。旧 UI 只能拿「选中版」当代理，
 *    而选中版是用户的意图、不是既成事实，两者在切换的瞬间必然不一致。
 *
 * 所以这里把判定改成**条目级事实**：把默认版与每个可选版各清点一遍
 * （[inventory]），任一版全装即 COMPLETE，与「当前选中哪一版」彻底解耦。
 * 选中版只影响「点安装时装哪一版」，不再影响「装没装」。
 */
data class VersionPresence(
    /**
     * 版本 id；**null 表示 catalog 条目的默认 `packages`**（用户没选版本时的旧行为）。
     * 用 null 而不是造一个 "default" 假 id，是为了让这个维度继续与
     * `selectedVersion()` 的 null 语义一一对应。
     */
    val versionId: String?,
    /** 该版按当前发行版映射后的包名（已去重、保序）。 */
    val packages: List<String>,
    /** 其中已装的包数。 */
    val present: Int,
) {
    val total: Int get() = packages.size

    /** 这一版整整齐齐全在。 */
    val complete: Boolean get() = total > 0 && present == total

    /** 至少装了一个包（用于「部分安装」判定）。 */
    val touched: Boolean get() = present > 0
}

/** 条目级三态。与 [ExtensionManager.ExtState] 同形，但本文件不依赖 Android。 */
enum class EntryState { NONE, PARTIAL, COMPLETE }

object ExtensionVersionPlan {

    /**
     * 逐版清点。默认版固定排在首位（index 0），其后按 catalog 里 `versions` 的顺序 ——
     * 顺序有意义：「装的是哪一版」在默认版与某个可选版包集相同时（php 默认就是 php83）
     * 要优先报默认版，否则卸载会只删掉子集，把 make / readline 这类附带的包留成孤儿。
     */
    fun inventory(
        defaultPackages: List<String>,
        versions: List<Pair<String, List<String>>>,
        installed: Map<String, String>,
    ): List<VersionPresence> {
        val out = ArrayList<VersionPresence>(versions.size + 1)
        fun add(id: String?, pkgs: List<String>) {
            if (pkgs.isEmpty()) return
            out += VersionPresence(id, pkgs, pkgs.count { installed.containsKey(it) })
        }
        add(null, defaultPackages)
        versions.forEach { (id, pkgs) -> add(id, pkgs) }
        return out
    }

    /** 条目级三态：任一版全装即 [EntryState.COMPLETE]，与「选中哪一版」无关。 */
    fun state(inventory: List<VersionPresence>): EntryState = when {
        inventory.any { it.complete } -> EntryState.COMPLETE
        inventory.any { it.touched } -> EntryState.PARTIAL
        else -> EntryState.NONE
    }

    /**
     * 现在装着的到底是哪一版（null = 一版都没装满）。
     *
     * 取值顺序是有讲究的：
     * 1. [prefer] 指定的那一版（用户选中的版本）**且它真的装齐了** —— 用户意图与现实
     *    一致时最不容易误判；
     * 2. **默认版装齐了就报默认版**。默认版的包集是超集（clang 默认 =
     *    clang17+make+ndk-sysroot），若这时报某个子集版本，卸载就只会删掉一个包、
     *    把其余包留成孤儿；
     * 3. 否则按 catalog 顺序取第一个装齐的可选版（lua 装的是 lua5.4 就报它）。
     *
     * @return 装齐的那一版；一版都没装齐返回 null（此时条目级状态必是 NONE 或 PARTIAL）
     */
    fun installed(
        inventory: List<VersionPresence>,
        prefer: String? = null,
    ): VersionPresence? {
        val complete = inventory.filter { it.complete }
        if (complete.isEmpty()) return null
        if (prefer != null) complete.firstOrNull { it.versionId == prefer }?.let { return it }
        complete.firstOrNull { it.versionId == null }?.let { return it }
        return complete.first()
    }

    /**
     * 哪一颗胶囊该显示成「选中」。
     *
     * 优先反映**既成事实**（装的是哪版），而不是用户的点击意图 ——
     * 用户抱怨的正是「切回原来装的那一版还是显示没装」，那就必须让界面
     * 永远能指出「现在装的是这一颗」。
     *
     * 装的是**默认版**且默认包集不等于任何一个版本项时（clang），退一步高亮
     * 「包集被默认版完全包含、且包数最多」的那一版（→ clang17）：
     * 默认 clang 下真正装上的就是 clang17，这个高亮不是猜的。
     *
     * @return 该高亮的版本 id；null = 一颗都不该高亮
     */
    fun highlight(
        inventory: List<VersionPresence>,
        explicit: String? = null,
    ): String? {
        val ins = installed(inventory, explicit)
        if (ins != null) {
            if (ins.versionId != null) return ins.versionId
            // 装的是默认版 → 找包集是它子集、且最大的那一版
            val target = ins.packages.toSet()
            return inventory
                .filter { it.versionId != null && it.packages.isNotEmpty() && target.containsAll(it.packages) }
                .maxByOrNull { it.packages.size }
                ?.versionId
        }
        if (explicit != null && inventory.any { it.versionId == explicit }) return explicit
        // 一版都没装、也没选过：高亮与默认包集完全相同的那一版（openjdk 默认 = 17）
        val def = inventory.firstOrNull { it.versionId == null } ?: return null
        val defPkgs = def.packages.toSet()
        return inventory.firstOrNull { it.versionId != null && it.packages.toSet() == defPkgs }?.versionId
    }

    /**
     * 该版本项是否**已经装齐**（用于「点胶囊时要不要先卸载」的判定）。
     *
     * 与 [VersionPresence.complete] 的区别：这里按「包是否都在」独立判一次，
     * 因为某版本可能不在 [inventory] 里（catalog 升级删了版本，而包还留着）。
     */
    fun isReady(packages: List<String>, installed: Map<String, String>): Boolean =
        packages.isNotEmpty() && packages.all { installed.containsKey(it) }

    /**
     * 「容器里同时只认一版」这条硬约束的判定：这次安装该不该被拦下。
     *
     * ## 为什么判据落在**包集**而不是版本 id
     *
     * 版本 id 是包集的**标签**，两者不是一一对应：
     * - 默认包集（`versionId = null`）在 catalog 里常常是超集：
     *   lua 默认 `[lua54, readline]`、clang 默认 `[clang, make, ndk-sysroot]`；
     * - 而版本项通常只有单包（`lua5.4` → 只有 `lua5.4` 一个包）。
     *
     * 于是任何「比较 versionId」的写法都会在某一侧出错（真机 2026-11-13 实测）：
     * - 用 `installedVersionId() != version`：默认版装齐时 `installedVersionId()`
     *   返回 null（见 [installed] 的约定），`null != version` 恒真 →
     *   连「原样重装默认版」都被拦成 409；
     * - 只比较「是否装着某个可选版」：装的是默认版时该判断为 false →
     *   `lua` 装了默认版再请求 `lua5.3` 会畅通无阻，两版并存。
     *
     * 包集是既成事实，标签只是它的一种叫法，所以判据落在事实上。
     *
     * @param inventory 条目级清点（[inventory] 的结果）
     * @param requestedPackages 这次要装的包集（已按发行版映射、去重）
     * @return true = 已装着**另一套**包，必须先卸载；false = 放行
     */
    fun switchBlocked(
        inventory: List<VersionPresence>,
        requestedPackages: List<String>,
    ): Boolean {
        val cur = installed(inventory) ?: return false
        // 请求的就是当前这套包（原样重装）→ 放行：重复点「安装」不该被拦，
        // 那条路径本来就走「重装/修复」，拦了用户只会以为坏了。
        return cur.packages.toSet() != requestedPackages.toSet()
    }
}

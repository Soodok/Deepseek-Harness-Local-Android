package app.dsh.mobile.pet

import android.content.Context
import android.provider.Settings
import app.dsh.mobile.engine.EngineConfig
import org.json.JSONObject
import java.io.File

/**
 * 只读探测 web profile 里有没有**兼容版本**的桥插件。
 *
 * 不修改任何 profile 文件、不执行 pnpm、不依赖 dsh 进程存活。
 *
 * 判定口径（v1.4.21 起收紧）：
 *   - bridgeInstalled：dependencies 里有官方 scoped 包 `@xiaopb/dsh-pet-bridge`，
 *     或 node_modules 里能看见；上游遗留名 `dsh-pet-bridge` 也算「已安装」，
 *     但会因版本 < 0.3.1 被判为 outdated（见 bridgeLegacy）；
 *   - bridgeEnabled：dsh.profile.bundles 里真的有它（装了但没进 bundles 引擎不加载）；
 *   - bridgeVersionOk：版本 >= [MIN_BRIDGE_VERSION]，读不到版本按不达标处理（fail-closed）；
 *   - bridgeReady = installed && enabled && versionOk；
 *   - webPetInstalled：dsh-pet（网页桌宠）是否装了，用来给用户区分提示。
 *
 * 为什么卡版本：协议 v2 的 `/bubble` 强制 `X-DSH-Pet-Token`（fail-closed 403），
 * token 与显式 `Content-Length`（上游 0.1.0 走 chunked，APK 侧必回 400）都从
 * 0.3.1 起才有。旧版装上去表现为「已连接但永远没有气泡」，与其让用户对着
 * 静默失败排查，不如在设置页直接说清版本要求。
 */
object PetPluginDetector {

    /** 官方 scoped 包（npm 已发布 0.3.1+，public）：token 版桥插件。 */
    const val BRIDGE_PKG = "@xiaopb/dsh-pet-bridge"

    /** 上游遗留包名（npm 只有 0.1.0，无 token）：只用于识别并提示升级。 */
    const val LEGACY_BRIDGE_PKG = "dsh-pet-bridge"

    const val WEB_PET_PKG = "dsh-pet"

    /** 插件中心搜索词：npm 搜索对 scoped 全名不友好，用短词。 */
    const val BRIDGE_SEARCH_QUERY = "dsh-pet-bridge"

    /** 兼容的最低桥插件版本（协议 v2 / token / Content-Length 的起点）。 */
    const val MIN_BRIDGE_VERSION = "0.3.1"

    /** GitHub 源（用户不想走 npm 时的手动安装口）。 */
    const val BRIDGE_REPO = "https://github.com/XiaoPb/dsh-pet-bridge"

    data class Snapshot(
        val profileExists: Boolean = false,
        val bridgeInstalled: Boolean = false,
        val bridgeEnabled: Boolean = false,
        val bridgeVersion: String? = null,
        /** 命中的是上游遗留包名（0.1.0），需要提示换官方 scoped 包。 */
        val bridgeLegacy: Boolean = false,
        val webPetInstalled: Boolean = false,
    ) {
        /** 版本可解析且 >= [MIN_BRIDGE_VERSION]；读不到版本按不达标处理。 */
        val bridgeVersionOk: Boolean get() = compareVersion(bridgeVersion, MIN_BRIDGE_VERSION) >= 0

        val bridgeReady: Boolean get() = bridgeInstalled && bridgeEnabled && bridgeVersionOk
    }

    /**
     * 门禁判定：true = 允许桌宠画出角色。
     *
     * Service 与设置页共用这一份，避免两处判断逐渐走偏（以前只有设置页判，
     * `MainActivity.onResume → restoreIfEnabled()` 和 START_STICKY 重建全绕过去了）。
     *
     * **硬门禁，无任何旁路**（2026-10-27 起收紧，用户明确要求）：
     *  - 就绪（装了 + 启用 + ≥ [MIN_BRIDGE_VERSION]）→ 放行；
     *  - 其余一律拦下。曾经的「仍然开启」软旁路偏好已删除 ——
     *    没桥插件的桌宠只会待机、永远不会响应工作状态，开了只会让人以为"桌宠坏了"；
     *  - 装了但版本偏低 → 硬拦：协议 v2 下旧插件不带 token，连上也是永远 403。
     */
    fun bridgeGateOpen(ctx: Context): Boolean = detect(ctx).bridgeReady

    /**
     * 资源包门禁：true = 已安装可用的动画包（packId 存在且目录完整）。
     * 没包的桌宠没有任何可画的内容，开启入口直接拦下，不进服务。
     */
    fun packReady(ctx: Context): Boolean = PetPrefs.packId(ctx) != null

    /**
     * 开启桌宠的完整硬门禁（设置页开关与 [PetOverlayService.restoreIfEnabled] 共用）：
     * 悬浮窗权限 + 桥插件就绪 + 资源包就绪，三者缺一不可，不能跳过。
     */
    fun canEnable(ctx: Context): Boolean =
        Settings.canDrawOverlays(ctx) && bridgeGateOpen(ctx) && packReady(ctx)

    fun detect(ctx: Context): Snapshot = runCatching {
        val profileDir = File(EngineConfig.dshHome(ctx), "profiles/web")
        val manifest = File(profileDir, "package.json")
        if (!manifest.isFile) return Snapshot(profileExists = false)

        val root = JSONObject(manifest.readText())
        val deps = root.optJSONObject("dependencies")
            ?.keys()?.asSequence()?.toSet() ?: emptySet()
        val bundles = root.optJSONObject("dsh")
            ?.optJSONObject("profile")
            ?.optJSONArray("bundles")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } } }
            ?.toSet()
            ?: emptySet()

        // 命中顺序：官方 scoped 包优先，其次上游遗留名（两者同时存在时以官方为准）。
        val bridgeName = deps.firstOrNull { it == BRIDGE_PKG }
            ?: deps.firstOrNull { it == LEGACY_BRIDGE_PKG }
            ?: listOf(BRIDGE_PKG, LEGACY_BRIDGE_PKG).firstOrNull {
                File(File(profileDir, "node_modules"), it).isDirectory
            }

        fun installed(pkg: String): Boolean =
            deps.any { it == pkg } || File(File(profileDir, "node_modules"), pkg).isDirectory

        fun enabled(name: String?): Boolean = name != null && bundles.any { it == name }

        val version = bridgeName?.let { name ->
            runCatching {
                val pj = File(File(profileDir, "node_modules"), "$name/package.json")
                if (!pj.isFile) return@runCatching null
                JSONObject(pj.readText()).optString("version").takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        Snapshot(
            profileExists = true,
            bridgeInstalled = bridgeName != null,
            bridgeEnabled = enabled(bridgeName),
            bridgeVersion = version,
            bridgeLegacy = bridgeName == LEGACY_BRIDGE_PKG,
            webPetInstalled = installed(WEB_PET_PKG),
        )
    }.getOrElse { Snapshot(profileExists = false) }

    /**
     * 语义化版本三段比较，忽略 `-pre` / `+build` 后缀。
     * 解析失败返回 -1（调用方据此 fail-closed）。
     */
    internal fun compareVersion(actual: String?, required: String): Int {
        fun parse(v: String?): List<Int>? {
            val core = v?.trim()?.takeWhile { it != '-' && it != '+' } ?: return null
            if (core.isEmpty()) return null
            val parts = core.split('.')
            return parts.map { it.toIntOrNull() ?: return null }
        }
        val a = parse(actual) ?: return -1
        val b = parse(required) ?: return -1
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}

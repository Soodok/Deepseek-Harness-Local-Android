package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 内置「用户态插件」预装器（v1.5.0）。
 *
 * ## 为什么需要它
 * 有些内置插件应当是**用户可管理的**：出现在插件页，能开关、能升级、能卸载。
 * 上游 dsh 的「用户态」语义只有一条路径 —— profile bundle：
 * `profiles/web/package.json` 的 `dsh.profile.bundles` 决定插件页的列表与挂载，
 * 每个 bundle 靠自己的 `dsh.bundle.patch`（cordis.patch.yml）挂载。
 * App 的 `android-overlay.yml` 是 `--patch` 层、最后应用、每次启动重写，
 * 挂在那里的插件永远开启、用户关不掉（v1.4.47 ~ v1.4.5x 的 mobile-layout 即如此）。
 *
 * ## 与内置能力（ssh / narrow-*）的分工
 * 本类只负责「**feature 插件**」：APK 带 tgz，装进 profile，之后归用户管。
 * 引擎补丁类（narrow-sidebar / chat-perf / priv-mode 等）是宿主兼容层，必须恒开，
 * 继续走 overlay。
 *
 * ## 生命周期：只在首次安装时默认开启
 * 用户的明确约定（2026-10-31）：**首次安装 App 时默认开启一次，之后不再干预**。
 * 因此这里用「每个 App 安装一份」的偏好标记（[Context.getSharedPreferences]，
 * 卸载 App 即清），而不是 dsh-home 里的文件（那里跨卸载存活）：
 *
 *  · 标记不存在 → 首次安装：没装就装（默认开启）；已装则只记录，不改状态；
 *  · 标记存在   → 之后每次启动什么都不做：不重装、不重新启用、不改版本。
 *
 * 换句话说：用户在插件页关掉/卸载它，后续启动（含 APK 升级）都不会被偷偷装回来。
 * 想恢复由用户自己在插件页打开。多个内置用户态插件共用这套规则（见 [REGISTRY]）。
 *
 * ## 安装方式与时机
 * 安装 = `dsh plugin --profile web add <本地 tgz>`（官方 CLI，pnpm 记账完整，
 * 离线可用）。必须**在引擎停止时**执行：引擎退出前会把内存态 profile 写回
 * package.json，运行中改会被吞掉。调用点固定在 boot 序列 spawnEngine 之前。
 *
 * ## 失败即降级
 * 任何异常只记日志：本次启动没有该插件，下次启动重试。绝不让引擎起不来。
 */
object ProfilePluginProvisioner {

    private const val TAG = "ProfilePluginProvisioner"

    /** 偏好文件名：随 App 安装存在，卸载即清 —— 「首次安装」的天然边界。 */
    private const val PREFS = "dsh_profile_plugins"

    /**
     * 一个随 APK 分发、以 profile bundle 形式安装的插件。
     *
     * @param pkg       npm 包名（profile 的 dependencies / bundles 里的键）
     * @param assetRoot `assets/<assetRoot>/`：内含 `<pkg>-<version>.tgz` 与 `bundle.json`
     */
    private data class BundledProfilePlugin(
        val pkg: String,
        val assetRoot: String,
    )

    /**
     * 内置用户态插件注册表。新增一个插件 = 这里加一行 + 发一个带
     * `cordis.patch.yml` 的 tgz + CI 打包任务加一步；App 侧无需再写安装器。
     */
    private val REGISTRY = listOf(
        BundledProfilePlugin(
            pkg = "@xiaopb/dsh-mobile-layout",
            assetRoot = "mobile-layout",
        ),
    )

    /** 资产元数据（构建期由 scripts/pack-mobile-layout.sh 生成）。 */
    private data class AssetBundle(val version: String, val tgz: String, val sha256: String?)

    /** 供 EngineSupervisor 在 spawnEngine 之前调用；不抛异常。 */
    fun ensureAll(ctx: Context) {
        if (PluginKillSwitch.isRestoring(ctx)) {
            Log.i(TAG, "plugin restore in progress; skip provisioning")
            return
        }
        for (plugin in REGISTRY) {
            runCatching { ensure(ctx, plugin) }
                .onFailure { Log.w(TAG, "${plugin.pkg}: provision failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun ensure(ctx: Context, plugin: BundledProfilePlugin) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 非首次安装：一个字节都不动（用户可能关掉/卸载/自己升级过它）。
        if (prefs.getBoolean(key(plugin), false)) {
            Log.i(TAG, "${plugin.pkg}: already provisioned on this install; leaving state untouched")
            return
        }

        val asset = readAsset(ctx, plugin) ?: run {
            Log.i(TAG, "${plugin.pkg}: no bundled tgz (older APK); skip")
            return
        }

        // 已由别的途径装好（旧版本内置、用户手动安装、或从云端恢复的 profile）：
        // 只补记标记，不改它当前的开关状态。
        if (isManaged(ctx, plugin.pkg)) {
            markProvisioned(prefs, plugin, installedVersion(ctx, plugin.pkg))
            Log.i(TAG, "${plugin.pkg}: already in profile; recorded without touching state")
            return
        }

        install(ctx, plugin, asset, prefs)
    }

    private fun install(
        ctx: Context,
        plugin: BundledProfilePlugin,
        asset: AssetBundle,
        prefs: android.content.SharedPreferences,
    ) {
        val staged = File(ctx.filesDir, "plugin-staging").apply { mkdirs() }
            .resolve(File(asset.tgz).name.removeSuffix(".tgz") + "-staged.tgz")
        staged.delete()
        ctx.assets.open("${plugin.assetRoot}/${asset.tgz}").use { input ->
            staged.outputStream().use { out -> input.copyTo(out) }
        }
        try {
            asset.sha256?.let { want ->
                val got = sha256(staged)
                check(got.equals(want, ignoreCase = true)) {
                    "tgz sha256 mismatch (got $got, want $want)"
                }
            }
            val (ok, msg) = PluginCenter.addLocalTarball(ctx, staged)
            if (!ok) {
                // 不写标记 → 下次启动重试。
                Log.w(TAG, "${plugin.pkg}: install failed: $msg")
                return
            }
            // CLI 会把本地 tarball 记成 `"<pkg>": "file:<staging 路径>"`。暂存文件
            // 随后就会删掉，留下悬空路径 —— 用户之后装/更新任何插件时 pnpm 解析
            // 依赖会直接失败。装完立刻改回普通版本号：node_modules 里已是实体文件，
            // 引擎不读 dependencies，插件页的「更新」也能正常走 registry。
            val effective = installedVersion(ctx, plugin.pkg) ?: asset.version
            normalizeDependencySpec(ctx, plugin.pkg, effective)
            // `dsh plugin add <tgz>` 对「dependencies 里已存在同一版本」的包是 no-op：
            // pnpm 认为已满足，bundles 不会被补上。而「用户关掉插件」恰恰就是这个
            // 形态（deps 在、bundles 被摘）。首次预装必须确保挂载项存在，否则默认
            // 开启落空。这里只在一次性预装路径执行，之后不再干预用户状态。
            ensureBundleEntry(ctx, plugin.pkg)
            markProvisioned(prefs, plugin, effective)
            Log.i(TAG, "${plugin.pkg}: installed $effective on first run (effective on this boot)")
        } finally {
            staged.delete()
        }
    }

    // ── profile 状态（直接读文件，不缓存：引擎可能随时改写）────────────────

    private fun profileDir(ctx: Context): File =
        File(EngineConfig.dshHome(ctx), "profiles/web")

    /**
     * profile 是否管理该插件。
     *
     * 只看 `dsh.profile.bundles`——那是插件页渲染与挂载的唯一依据。
     * 用户关掉插件 = 从 bundles 摘掉（dependencies / node_modules 保留）。
     */
    private fun isManaged(ctx: Context, pkg: String): Boolean = runCatching {
        val manifest = File(profileDir(ctx), "package.json")
        if (!manifest.isFile) return@runCatching false
        val bundles = JSONObject(manifest.readText())
            .optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles")
            ?: return@runCatching false
        (0 until bundles.length()).any { bundles.optString(it) == pkg }
    }.getOrDefault(false)

    /** 落盘包版本（node_modules 里的 package.json）；不存在返回 null。 */
    private fun installedVersion(ctx: Context, pkg: String): String? = runCatching {
        val pj = File(File(profileDir(ctx), "node_modules"), "$pkg/package.json")
        if (!pj.isFile) return@runCatching null
        JSONObject(pj.readText()).optString("version").takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 确保插件在 `dsh.profile.bundles` 里（挂载列表）。
     *
     * 仅用于**一次性首装**：CLI 对已满足的依赖是 no-op，不会补挂载项。
     * 引擎此刻没在跑，写入走 tmp + rename。之后用户关掉插件时 bundles 会被摘掉，
     * 但因为预装标记已写，这条路径不会再执行 —— 不会与用户的关闭意图打架。
     */
    private fun ensureBundleEntry(ctx: Context, pkg: String) {
        runCatching {
            if (isManaged(ctx, pkg)) return@runCatching
            val manifest = File(profileDir(ctx), "package.json")
            if (!manifest.isFile) return@runCatching
            val root = JSONObject(manifest.readText())
            val dsh = root.optJSONObject("dsh") ?: JSONObject().also { root.put("dsh", it) }
            val profile = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
            val bundles = profile.optJSONArray("bundles") ?: org.json.JSONArray().also { profile.put("bundles", it) }
            if ((0 until bundles.length()).any { bundles.optString(it) == pkg }) return@runCatching
            bundles.put(pkg)
            val tmp = File(manifest.parentFile, "package.json.tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(manifest)) {
                manifest.delete()
                check(tmp.renameTo(manifest)) { "cannot replace ${manifest.absolutePath}" }
            }
            Log.i(TAG, "$pkg: added to profile bundles")
        }.onFailure { Log.w(TAG, "$pkg: cannot add bundle entry: ${it.message}") }
    }

    /**
     * 把 `file:<本地 tgz>` 形态的依赖声明改回版本号。
     *
     * 只在当前值确实是 `file:` 时改写 —— 用户自己用 registry 装过的记录
     * （普通 semver / range）原样保留。写入走 tmp + rename，避免半个 JSON。
     */
    private fun normalizeDependencySpec(ctx: Context, pkg: String, version: String) {
        runCatching {
            val manifest = File(profileDir(ctx), "package.json")
            if (!manifest.isFile) return@runCatching
            val root = JSONObject(manifest.readText())
            val deps = root.optJSONObject("dependencies") ?: return@runCatching
            if (!deps.optString(pkg).startsWith("file:")) return@runCatching
            deps.put(pkg, version)
            val tmp = File(manifest.parentFile, "package.json.tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(manifest)) {
                manifest.delete()
                check(tmp.renameTo(manifest)) { "cannot replace ${manifest.absolutePath}" }
            }
            Log.i(TAG, "$pkg: dependency spec -> $version (was a local tarball path)")
        }.onFailure { Log.w(TAG, "$pkg: cannot normalize dependency spec: ${it.message}") }
    }

    // ── 资产与标记 ─────────────────────────────────────────────────────────

    private fun readAsset(ctx: Context, plugin: BundledProfilePlugin): AssetBundle? = runCatching {
        val raw = ctx.assets.open("${plugin.assetRoot}/bundle.json")
            .bufferedReader().use { it.readText() }
        val obj = JSONObject(raw)
        val version = obj.optString("version")
        val tgz = obj.optString("tgz")
        if (version.isBlank() || tgz.isBlank()) return@runCatching null
        AssetBundle(version, tgz, obj.optString("sha256").takeIf { it.isNotBlank() })
    }.getOrElse {
        if (it is java.io.FileNotFoundException) null
        else {
            Log.w(TAG, "${plugin.pkg}: cannot read bundled metadata: ${it.message}")
            null
        }
    }

    private fun key(plugin: BundledProfilePlugin): String = "provisioned:" + plugin.pkg

    private fun markProvisioned(
        prefs: android.content.SharedPreferences,
        plugin: BundledProfilePlugin,
        version: String?,
    ) {
        runCatching {
            prefs.edit()
                .putBoolean(key(plugin), true)
                .putString(key(plugin) + ":version", version ?: "")
                .putLong(key(plugin) + ":at", System.currentTimeMillis())
                .apply()
        }.onFailure { Log.w(TAG, "${plugin.pkg}: cannot record provisioning: ${it.message}") }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

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
 *  · 标记不存在 → 首次安装：没装就装，装完按该插件的 `enabledOnFirstRun` 决定
 *    要不要挂到 bundles（mobile-layout 跟随设置里的「官方推荐手机 UI」）；
 *    已装则只记录，不改状态；
 *  · 标记存在   → 之后每次启动什么都不做：不重装、不重新启用、不改版本。
 *
 * 唯一的例外是用户**自己**在设置里拨了那个总开关 —— 那属于显式意图，走
 * [requestEnabled] 记一条待办，下次启动落地（插件页里的手动开关不受影响）。
 *
 * 换句话说：用户在插件页关掉/卸载它，后续启动（含 APK 升级）都不会被偷偷装回来。
 * 想恢复由用户自己在插件页打开。多个内置用户态插件共用这套规则（见 [REGISTRY]）。
 *
 * ## 安装方式与时机
 * 安装 = `dsh plugin --profile web add <本地 tgz>`（官方 CLI，pnpm 记账完整，
 * 离线可用）。必须**在引擎停止时**执行：引擎退出前会把内存态 profile 写回
 * package.json，运行中改会被吞掉。调用点固定在 boot 序列 spawnEngine 之前。
 *
 * ## 失败即降级（含退避）
 * 任何异常只记日志：本次启动没有该插件，下次启动重试。绝不让引擎起不来。
 *
 * 「下次重试」有**退避**：单次 `dsh plugin add` 硬超时 120 秒（`PluginCenter` 的
 * `p.waitFor(120, SECONDS)`），失败若每次都重试，慢网用户每次冷启动都要白等两分钟。
 * 所以失败后按 10 分钟 → 1 小时 → 6 小时 → 24 小时递增退避（记在 [PREFS] 里，
 * 跨重启生效），成功则清零；断网时直接跳过，不在必然失败的操作上耗时间。
 */
object ProfilePluginProvisioner {

    private const val TAG = "ProfilePluginProvisioner"

    /** 偏好文件名：随 App 安装存在，卸载即清 —— 「首次安装」的天然边界。 */
    private const val PREFS = "dsh_profile_plugins"

    /** 手机竖屏布局插件包名（设置页「官方推荐手机 UI」也按它同步开关）。 */
    const val MOBILE_LAYOUT_PKG = "@xiaopb/dsh-mobile-layout"

    /**
     * 一个随 APK 分发、以 profile bundle 形式安装的插件。
     *
     * @param pkg       npm 包名（profile 的 dependencies / bundles 里的键）
     * @param assetRoot `assets/<assetRoot>/`：内含 `<pkg>-<version>.tgz` 与 `bundle.json`
     * @param enabledOnFirstRun 首装时是否挂到 bundles；默认开。只在首装求值一次。
     */
    private data class BundledProfilePlugin(
        val pkg: String,
        val assetRoot: String,
        val enabledOnFirstRun: (Context) -> Boolean = { true },
    )

    /**
     * 内置用户态插件注册表。新增一个插件 = 这里加一行 + 发一个带
     * `cordis.patch.yml` 的 tgz + CI 打包任务加一步；App 侧无需再写安装器。
     */
    private val REGISTRY = listOf(
        BundledProfilePlugin(
            pkg = MOBILE_LAYOUT_PKG,
            assetRoot = "mobile-layout",
            // v1.5.0：首装是否默认开启，跟随设置里的「官方推荐手机 UI」。
            //
            // 上游 v1.4.67 把竖屏方案做成二选一（默认 narrow-* 三件套），
            // mobile-layout 从 overlay 挪到 profile 之后，它的开与关就得由这里定：
            //  · 开关为开 → 首装挂到 bundles（等同旧行为：装上即生效）；
            //  · 开关为关（默认）→ 只装进 profile、不挂载 —— 插件页能看到它、
            //    随时可开，但默认不改变用户的竖屏方案。
            // 无论哪个分支，都只在**首装**执行一次，之后不再干预用户状态。
            enabledOnFirstRun = { app.dsh.mobile.MainActivity.officialMobileUiEnabled(it) },
        ),
    )

    /** 资产元数据（构建期由 scripts/pack-mobile-layout.sh 生成）。 */
    private data class AssetBundle(val version: String, val tgz: String, val sha256: String?)

    /**
     * 请求在**下次启动时**把某个插件挂上/摘下 bundles。
     *
     * 用途：设置页的「官方推荐手机 UI」开关。它原本靠 overlay 互斥实现，插件挪到
     * 用户态之后，开关必须落到 bundles 上才有意义。不能当场写 package.json ——
     * 引擎在跑，退出时会用内存态覆写；所以只记意图，由 [ensureAll] 在
     * spawnEngine 之前（引擎已停）落地。
     *
     * 这是**用户的显式动作**，不违反「首次安装后不再干预」：只有用户自己拨开关
     * 才会写这条意图，插件页的手动开关不会被动过。
     */
    fun requestEnabled(ctx: Context, pkg: String, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(pendingKey(pkg), on).apply()
            Log.i(TAG, "$pkg: pending ${if (on) "enable" else "disable"} recorded for next boot")
        }.onFailure { Log.w(TAG, "$pkg: cannot record pending state: ${it.message}") }
    }

    /** 供 EngineSupervisor 在 spawnEngine 之前调用；不抛异常。 */
    fun ensureAll(ctx: Context) {
        if (PluginKillSwitch.isRestoring(ctx)) {
            Log.i(TAG, "plugin restore in progress; skip provisioning")
            return
        }
        for (plugin in REGISTRY) {
            runCatching { applyPending(ctx, plugin) }
                .onFailure { Log.w(TAG, "${plugin.pkg}: pending apply failed: ${it.message}") }
            runCatching { ensure(ctx, plugin) }
                .onFailure { Log.w(TAG, "${plugin.pkg}: provision failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** 落地 [requestEnabled] 记下的意图（引擎已停，可以安全改写 package.json）。 */
    private fun applyPending(ctx: Context, plugin: BundledProfilePlugin) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = pendingKey(plugin.pkg)
        if (!prefs.contains(k)) return
        val want = prefs.getBoolean(k, false)
        if (want) ensureBundleEntry(ctx, plugin.pkg) else removeBundleEntry(ctx, plugin.pkg)
        prefs.edit().remove(k).apply()
        Log.i(TAG, "${plugin.pkg}: applied pending ${if (want) "enable" else "disable"}")
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

        // 失败退避：装失败过就隔一段时间再试，不是每次开机都重新卡一遍。
        //
        // 为什么必须有：`PluginCenter` 的 `dsh plugin add` 硬超时 120 秒
        //（`PluginCenter.kt:576` `p.waitFor(120, SECONDS)`），失败路径还要再等
        // 180 秒 future。慢网/断网环境下每次冷启动都要白等两分钟，用户看到
        // 的就是「启动卡住」。
        val waitMs = backoffRemainingMs(prefs, plugin.pkg)
        if (waitMs > 0) {
            Log.i(TAG, "${plugin.pkg}: backing off ${waitMs}ms after previous failure; skip")
            return
        }

        // 没网就别试 —— 必然失败，还要白等两分钟。等下次启动（恢复网络后）再说。
        if (!app.dsh.mobile.update.NetworkState.isOnline(ctx)) {
            Log.i(TAG, "${plugin.pkg}: offline; defer provisioning to next boot")
            return
        }

        install(ctx, plugin, asset, prefs)
    }

    /**
     * 上次失败后还要等多久（毫秒）；0 = 可以试。
     *
     * 退避梯度按**失败次数**递增：10 分钟 → 1 小时 → 6 小时 → 24 小时封顶。
     * 用「下次允许时刻」而不是「距上次多久」，因为它能跨重启、跨时区变化稳定生效，
     * 也不受设备时钟小抖动影响（`System.currentTimeMillis` 单调性由 Android 保证
     * 在合理范围内）。
     */
    private fun backoffRemainingMs(
        prefs: android.content.SharedPreferences,
        pkg: String,
    ): Long {
        val retryAt = prefs.getLong(retryAtKey(pkg), 0L)
        if (retryAt <= 0L) return 0L
        return (retryAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 记录一次失败并算出下次允许重试的时刻。 */
    private fun recordFailure(
        prefs: android.content.SharedPreferences,
        pkg: String,
    ) {
        val fails = prefs.getInt(failKey(pkg), 0) + 1
        // 10 分钟起步，逐次 ×6，24 小时封顶。
        val delaysMs = longArrayOf(10 * 60_000L, 60 * 60_000L, 6 * 3_600_000L, 24 * 3_600_000L)
        val delay = delaysMs[(fails - 1).coerceIn(0, delaysMs.size - 1)]
        val retryAt = System.currentTimeMillis() + delay
        prefs.edit()
            .putInt(failKey(pkg), fails)
            .putLong(retryAtKey(pkg), retryAt)
            .apply()
        Log.w(TAG, "$pkg: failure #$fails; next attempt in ${delay / 1000}s")
    }

    /** 成功后清掉退避状态（否则下次真出问题会直接跳到长退避）。 */
    private fun clearFailure(
        prefs: android.content.SharedPreferences,
        pkg: String,
    ) {
        if (!prefs.contains(failKey(pkg)) && !prefs.contains(retryAtKey(pkg))) return
        prefs.edit().remove(failKey(pkg)).remove(retryAtKey(pkg)).apply()
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
                // 不写 provisioned 标记 → 下次启动还会重试；但要记一次失败，
                // 用退避把「每次都白等 120s」变成「隔几小时试一次」。
                Log.w(TAG, "${plugin.pkg}: install failed: $msg")
                recordFailure(prefs, plugin.pkg)
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
            // 形态（deps 在、bundles 被摘）。所以要挂载就得自己补，或自己摘掉 ——
            // 两者都只在一次性首装路径执行，之后不再干预用户状态。
            if (plugin.enabledOnFirstRun(ctx)) {
                ensureBundleEntry(ctx, plugin.pkg)
            } else {
                removeBundleEntry(ctx, plugin.pkg)
                Log.i(TAG, "${plugin.pkg}: installed but left off (app setting says narrow-* scheme)")
            }
            markProvisioned(prefs, plugin, effective)
            clearFailure(prefs, plugin.pkg)
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
     * 把插件从 `dsh.profile.bundles` 摘掉（= 插件页「关闭」）。
     *
     * dependencies / node_modules 一律保留：插件仍算「已安装」，插件页里还能再
     * 打开，也不需要在下次开机时重新下载。
     */
    private fun removeBundleEntry(ctx: Context, pkg: String) {
        runCatching {
            if (!isManaged(ctx, pkg)) return@runCatching
            val manifest = File(profileDir(ctx), "package.json")
            if (!manifest.isFile) return@runCatching
            val root = JSONObject(manifest.readText())
            val bundles = root.optJSONObject("dsh")?.optJSONObject("profile")
                ?.optJSONArray("bundles") ?: return@runCatching
            val kept = org.json.JSONArray()
            for (i in 0 until bundles.length()) {
                val name = bundles.optString(i)
                if (name != pkg) kept.put(name)
            }
            root.getJSONObject("dsh").getJSONObject("profile").put("bundles", kept)
            val tmp = File(manifest.parentFile, "package.json.tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(manifest)) {
                manifest.delete()
                check(tmp.renameTo(manifest)) { "cannot replace ${manifest.absolutePath}" }
            }
            Log.i(TAG, "$pkg: removed from profile bundles (package kept)")
        }.onFailure { Log.w(TAG, "$pkg: cannot remove bundle entry: ${it.message}") }
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

    private fun pendingKey(pkg: String): String = "pending-enabled:" + pkg

    /** 累计失败次数（退避梯度用）。 */
    private fun failKey(pkg: String): String = "fail-count:" + pkg

    /** 下次允许重试的时刻（毫秒时间戳）；0/不存在 = 立即可试。 */
    private fun retryAtKey(pkg: String): String = "retry-at:" + pkg

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

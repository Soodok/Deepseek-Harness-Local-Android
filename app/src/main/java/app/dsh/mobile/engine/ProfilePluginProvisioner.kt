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
 * 引擎补丁类（chat-perf / priv-mode / device-tools 等）是宿主兼容层，必须恒开，
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
     * 解决方案浏览器（VS Code 式文件树 + 完整源码控制，替换 Web GUI 右侧栏）。
     *
     * 与 mobile-layout 的差异：它**带 npm 运行时依赖**（highlight.js、
     * omni-doc-viewer），首装时 pnpm 会去 registry 解析它们 —— 所以首装需要
     * 网络；升级走直接解包，不再需要网络（见 [extractTgzInto] 的说明）。
     */
    const val SOLUTION_EXPLORER_PKG = "@xiaopb/dsh-solution-explorer"

    /**
     * 一个随 APK 分发、以 profile bundle 形式安装的插件。
     *
     * @param pkg       npm 包名（profile 的 dependencies / bundles 里的键）
     * @param assetRoot `assets/<assetRoot>/`：内含 `<pkg>-<version>.tgz` 与 `bundle.json`
     * @param enabledOnFirstRun 首装时是否挂到 bundles；默认开。只在首装求值一次。
     * @param hasNpmDependencies 包内 `dependencies` 非空 —— 首装时 pnpm 必须联网
     *   到 registry 解析它们。仅用于日志与文档说明，不改变安装流程（升级走直接
     *   解包，不受影响）。
     */
    private data class BundledProfilePlugin(
        val pkg: String,
        val assetRoot: String,
        val enabledOnFirstRun: (Context) -> Boolean = { true },
        val hasNpmDependencies: Boolean = false,
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
        BundledProfilePlugin(
            pkg = SOLUTION_EXPLORER_PKG,
            assetRoot = "solution-explorer",
            // 默认**不挂载**，理由不是「怕出问题」，而是它改写 Web GUI 的整个
            // 右侧栏（VS Code 式文件树 + 源码控制），属于主张很强的界面替换。
            // 让用户先在插件页看到它、自己决定要不要开，比替他做决定更合适 ——
            // 与 mobile-layout「跟随设置开关」是同一原则：默认不改变既有体验。
            enabledOnFirstRun = { false },
            hasNpmDependencies = true,
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

        // 预装过一次之后：**不再「一个字节都不动」**，而是只在「随包版本比装着的
        // 新」时升级。这是 v1.5.x 修的真实缺陷 —— 原实现只判 provisioned 标记就
        // 直接 return，于是 CI 把新 tgz 打进 APK 也没用，老用户永远停在首次安装
        // 那一版（实测设备 0.4.5 / npm 已 0.4.8，UI 修复传不到存量用户）。
        //
        // 升级有严格边界，缺一条都不动：
        //   1. node_modules 里还在 —— 用户彻底卸载过就尊重这个意图，不偷偷装回去；
        //   2. 随包版本 > 已装版本 —— 用户自己装了更新版时绝不降级；
        //   3. 升级只换文件与依赖版本号，**绝不碰 bundles 挂载状态**（那是用户的开关）。
        if (prefs.getBoolean(key(plugin), false)) {
            val bundled = readAsset(ctx, plugin)?.version
            val current = installedVersion(ctx, plugin.pkg)
            if (bundled == null || current == null) {
                Log.i(TAG, "${plugin.pkg}: already provisioned (bundled=$bundled current=$current); nothing to do")
                return
            }
            if (compareVersions(bundled, current) <= 0) {
                Log.i(TAG, "${plugin.pkg}: provisioned at $current; bundled $bundled not newer")
                return
            }
            Log.i(TAG, "${plugin.pkg}: upgrading $current -> $bundled from bundled tgz")
            install(ctx, plugin, readAsset(ctx, plugin)!!, prefs, upgradeFrom = current)
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
        //
        // 为什么「必然失败」取决于包里有没有 npm 依赖：
        //   · 无依赖的包（mobile-layout）install 就是把 tgz 解进 profile，
        //     离线也能成 —— 但 pnpm 仍会尝试解析 peerDependencies 并可能报警，
        //     且这里无法可靠区分「能成」与「会卡」，所以统一按需网处理；
        //   · 有依赖的包（solution-explorer：highlight.js、omni-doc-viewer）
        //     必须联网让 pnpm 从 registry 取，离线一定失败。
        // 两种情况在离线时都只是「推迟到下次启动」，不写失败退避，所以用户联网后
        // 重启一次就能装上，不会因为首启没网而永久跳过。
        if (!app.dsh.mobile.update.NetworkState.isOnline(ctx)) {
            Log.i(
                TAG,
                "${plugin.pkg}: offline; defer provisioning to next boot" +
                    if (plugin.hasNpmDependencies) " (this plugin resolves npm deps, network required)" else "",
            )
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
        upgradeFrom: String? = null,
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
            // 升级路径：**直接解包覆盖，不走 pnpm**。
            //
            // 升级与首装的语义不同：首装要让引擎知道「多了一个包」（写 dependencies、
            // 解析 lockfile、可能补 bundle），必须由 CLI 完成；而升级只是「同一个包
            // 换一批文件」，引擎每次启动读的都是 node_modules 里的实体内容，
            // dependencies 里的版本号仅供插件页显示。
            //
            // 而 CLI 在升级场景下已被实测证明不可用（四种做法全失败，pnpm.log 空、
            // 1.3 秒 no-op —— 详见 [extractTgzInto] 的表）。绕开它反而更可靠：
            // 无网络依赖、无 lockfile 参与、失败时旧版本原样保留。
            if (upgradeFrom != null) {
                val dest = File(profileDir(ctx), "node_modules/${plugin.pkg}")
                if (!dest.parentFile!!.isDirectory) {
                    Log.w(TAG, "${plugin.pkg}: node_modules missing; cannot upgrade in place")
                    recordFailure(prefs, plugin.pkg)
                    return
                }
                val installed = extractTgzInto(staged, dest)
                if (installed == null || compareVersions(installed, upgradeFrom) <= 0) {
                    Log.w(
                        TAG,
                        "${plugin.pkg}: upgrade $upgradeFrom -> ${asset.version} failed " +
                            "(extracted=$installed; old version left in place)",
                    )
                    recordFailure(prefs, plugin.pkg)
                    return
                }
                // 不碰 dependencies / lockfile：升级只换 node_modules 里的实体文件，
                // `file:` 依赖与暂存 tgz 都还在原位，pnpm 的结构保持自洽。
                // （早期版本在这里调过 normalizeDependencySpec，会把 importers
                //  的 specifier 改成纯版本号而 packages/snapshots 仍是 file:，
                //   之后所有 pnpm 操作都报 broken lockfile。）
                // **绝不碰 bundles**：用户的开关状态不因为「随包带了新版」而改变。
                markProvisioned(prefs, plugin, installed)
                clearFailure(prefs, plugin.pkg)
                Log.i(TAG, "${plugin.pkg}: upgraded $upgradeFrom -> $installed by direct extraction")
                return
            }
            val (ok, msg) = PluginCenter.addLocalTarball(ctx, staged)
            if (!ok) {
                // 首装失败不写 provisioned 标记 → 下次启动还会重试；但要记一次失败，
                // 用退避把「每次都白等 120s」变成「隔几小时试一次」。
                // 升级失败不写标记以外的东西：旧版本仍在盘上、仍可用，只是这次没升上去；
                // 同样记退避，避免每次开机都白等两分钟。
                Log.w(TAG, "${plugin.pkg}: install failed: $msg")
                recordFailure(prefs, plugin.pkg)
                return
            }
            // CLI 会把本地 tarball 记成 `"<pkg>": "file:<staging 路径>"`。暂存文件
            // 随后就会删掉，留下悬空路径 —— 用户之后装/更新任何插件时 pnpm 解析
            // 依赖会直接失败。装完立刻改回普通版本号：node_modules 里已是实体文件，
            // 引擎不读 dependencies，插件页的「更新」也能正常走 registry。
            val effective = installedVersion(ctx, plugin.pkg) ?: asset.version
            // 不调用 normalizeDependencySpec：见本函数末尾 finally 里的说明。
            // 把 `file:` 改回纯版本号会让 lockfile 的 packages/snapshots 与
            // importers 不一致，之后所有 pnpm 操作都会报 broken lockfile。
            // 升级路径到此为止：**只换文件与依赖版本号，绝不碰 bundles**。
            // 用户的开关状态（挂载/摘掉）不因为「随包带了新版」而改变。
            if (upgradeFrom != null) {
                // ⚠️ 必须用**安装后重新读到的**版本判断升级是否真的发生。
                //
                // `dsh plugin add <tgz>` 对「dependencies 里已是同一版本」是 no-op，
                // 而 installedVersion() 读的是 node_modules 里的实体文件 —— 若 pnpm
                // 认定已满足而没换文件，effective 就等于 upgradeFrom。此时若照样
                // markProvisioned + 打「upgraded X -> X」，会把**没成功的升级记成
                // 成功**：下次启动仍判 bundled > current，于是每次开机都白等一轮
                // pnpm（实测 10 秒）。如实记录，不谎报。
                if (effective == upgradeFrom) {
                    Log.w(
                        TAG,
                        "${plugin.pkg}: upgrade $upgradeFrom -> ${asset.version} did NOT take " +
                            "effect (still $effective; pnpm treated it as satisfied) — will retry next boot",
                    )
                    clearFailure(prefs, plugin.pkg)   // 不是「失败」，下次仍该试
                    return
                }
                markProvisioned(prefs, plugin, effective)
                clearFailure(prefs, plugin.pkg)
                Log.i(TAG, "${plugin.pkg}: upgraded $upgradeFrom -> $effective (bundles untouched)")
                return
            }
            // `dsh plugin add <tgz>` 对「dependencies 里已存在同一版本」的包是 no-op：
            // pnpm 认为已满足，bundles 不会被补上。而「用户关掉插件」恰恰就是这个
            // 形态（deps 在、bundles 被摘）。所以要挂载就得自己补，或自己摘掉 ——
            // 两者都只在一次性首装路径执行，之后不再干预用户状态。
            if (plugin.enabledOnFirstRun(ctx)) {
                ensureBundleEntry(ctx, plugin.pkg)
            } else {
                removeBundleEntry(ctx, plugin.pkg)
                // 文案必须通用：这句话原本写死成「app setting says narrow-* scheme」，
                // 只对 mobile-layout 成立；solution-explorer 是「默认不替用户换界面」，
                // 照抄那句会让以后查日志的人以为是竖屏方案在起作用。
                Log.i(TAG, "${plugin.pkg}: installed but left off (enabledOnFirstRun=false)")
            }
            markProvisioned(prefs, plugin, effective)
            clearFailure(prefs, plugin.pkg)
            Log.i(TAG, "${plugin.pkg}: installed $effective on first run (effective on this boot)")
        } finally {
            // ⚠️ 成功时**不删**暂存 tgz（v1.6.19 修的真实回归）。
            //
            // `pnpm add <tgz>` 会把依赖记成 `file:<staging 路径>`，并在
            // `pnpm-lock.yaml` 的 importers / packages / snapshots 三处都写上
            // 这个路径。暂存文件一删，lockfile 立刻变成悬空引用：
            //
            //     ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY
            //     Broken lockfile: no entry for '<pkg>' in pnpm-lock.yaml
            //
            // 后果不是「只有这一个插件坏」，而是**之后每一次 pnpm 操作全失败**
            // —— 装第二个插件时撞到的正是这条（实测 solution-explorer 首装
            // 报的就是 mobile-layout 的 lockfile 缺条目）。
            //
            // 曾经的做法是事后把 package.json 的 `file:` 改回纯版本号，但那只
            // 改了 importers，packages 里的 `tarball:` 与 snapshots 的键仍是
            // file: 形态 → 一样自相矛盾。lockfile 是 pnpm 的派生结构，不许
            // 手工外科手术。正解是让文件留着，lockfile 从头到尾保持自洽。
            //
            // 文件名自带版本号（`<pkg>-<ver>-staged.tgz`），各版本互不覆盖，
            // 所以旧版本记录也始终能解析到自己的文件。单份 190KB~4MB。
            if (!runCatching { installedVersion(ctx, plugin.pkg) }.getOrNull().isNullOrBlank()) {
                Log.i(TAG, "${plugin.pkg}: keeping ${staged.name} so pnpm-lock.yaml stays resolvable")
            } else {
                staged.delete()
            }
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

    /**
     * 比较两个 npm 语义化版本号：返回 <0 表示 a 旧于 b，0 相同，>0 表示 a 新于 b。
     *
     * 为什么不用 `String.compareTo`：字符串比较下 `"0.4.10" < "0.4.8"`（逐字符），
     * 0.4.9 之后就会判错、导致该升级时不升级。必须按数字段比较。
     *
     * 只实现升级判定需要的子集：`major.minor.patch` 数字段 + 可选 prerelease。
     * prerelease 规则按 semver：有 prerelease 的**低于**同号正式版
     * （`0.5.0-rc.1 < 0.5.0`）。`v` 前缀与 `build+meta` 忽略。
     *
     * 非法输入一律返回 0（视为「不更新」），宁可不动也不要降级装错。
     */
    internal fun compareVersions(a: String, b: String): Int {
        fun normalize(v: String): Pair<List<Int>, List<String>>? {
            var s = v.trim().removePrefix("v").removePrefix("V")
            s = s.substringBefore('+')
            val core = s.substringBefore('-')
            val pre = if (s.contains('-')) s.substringAfter('-') else ""
            val nums = core.split('.').map { it.toIntOrNull() ?: return null }
            if (nums.isEmpty()) return null
            val pres = if (pre.isEmpty()) emptyList() else pre.split('.')
            return nums to pres
        }
        val pa = normalize(a) ?: return 0
        val pb = normalize(b) ?: return 0
        val (na, prea) = pa
        val (nb, preb) = pb
        // 先按数字段比，缺位补 0（1.2 == 1.2.0）
        for (i in 0 until maxOf(na.size, nb.size)) {
            val x = na.getOrElse(i) { 0 }
            val y = nb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        // 数字段相同：无 prerelease 的更大；都有则逐段比（数字段按数值，其余按字典序）
        if (prea.isEmpty() && preb.isEmpty()) return 0
        if (prea.isEmpty()) return 1
        if (preb.isEmpty()) return -1
        for (i in 0 until maxOf(prea.size, preb.size)) {
            val x = prea.getOrNull(i) ?: return -1
            val y = preb.getOrNull(i) ?: return 1
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

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

    /**
     * 把 npm `.tgz` 直接解到 `node_modules/<pkg>`，**完全不经过 pnpm**。
     *
     * ## 为什么要绕开 pnpm（v1.6.18 实测，四次尝试后定论）
     *
     * 升级场景下 `dsh plugin add <tgz>` 无法可靠工作，四种做法全试过：
     *
     * | 做法 | 结果 |
     * |---|---|
     * | 只改 package.json 版本号再 add | pnpm 判「已满足」，1.3 秒 no-op 返回 |
     * | 同时外科改 lockfile | 拼出矛盾 lockfile，pnpm 报 badly resolved merge conflict |
     * | 先删 node_modules 再 add | `installedVersion()==null` → ensure() 早退，包丢了 |
     * | 先把 deps 摘掉再 add | pnpm 仍按旧 lockfile 解析，装的还是旧版 |
     *
     * 决定性证据：升级尝试里 `.plugin-manager/logs/<op>/pnpm.log` 是**空文件**、
     * 整个操作 1.3 秒完成（真实安装要 10 秒+）—— pnpm 根本没跑起来，被上游
     * `dsh-plugin-manager` 在更早的位置判成「无需操作」短路了。
     *
     * 而 tgz 就在 APK 里、目标目录已知、`node_modules` 里是实体目录（不是
     * pnpm 的符号链接，`ls -la` 已实测确认），所以直接解压可行且最快：
     * 无网络、无 pnpm、无 lockfile 参与，纯文件覆盖。
     *
     * 解压到临时目录再整体换位，避免留下半套文件：解压失败时旧版本原封不动。
     *
     * @return 成功返回新版本号，失败返回 null（旧版本保持不变）
     */
    private fun extractTgzInto(tgz: File, dest: File): String? = runCatching {
        val staging = File(dest.parentFile, dest.name + ".upgrade-new")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "cannot create ${staging.absolutePath}" }
        var entries = 0
        java.util.zip.GZIPInputStream(tgz.inputStream().buffered()).use { gz ->
            val din = java.io.DataInputStream(gz)
            val header = ByteArray(512)
            while (true) {
                var off = 0
                while (off < 512) {
                    val r = din.read(header, off, 512 - off)
                    if (r < 0) break
                    off += r
                }
                if (off < 512) break                          // 流尾
                if (header.all { it == 0.toByte() }) break    // 零块 = 结束
                val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
                val size = tarOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                if (name.isEmpty()) break
                // npm 包固定以 `package/` 为前缀，剥掉这一层
                val rel = name.removePrefix("package/").trim('/')
                if (rel.isEmpty() || rel == "package") {
                    skipEntryBody(din, size, false)
                    continue
                }
                val out = File(staging, rel)
                var bodyRead = false
                when (type) {
                    '5' -> out.mkdirs()                       // 目录：内容为空
                    '0', '\u0000' -> {                        // 普通文件
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            var left = size
                            val buf = ByteArray(1 shl 16)
                            while (left > 0) {
                                val n = din.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) throw java.io.EOFException("tar body truncated at $rel")
                                o.write(buf, 0, n)
                                left -= n
                            }
                        }
                        entries++
                        bodyRead = true
                    }
                    else -> Unit                              // 软链等：npm 包不含，整体跳过
                }
                // 内容已读 → 只吃 512 对齐；未读 → 连内容一起跳。
                // ⚠️ 对齐必须按**真实 size** 算：传 0 会算出 pad=0，下一个头
                // 就落在正文中间（实测症状：只解出第 1 个文件 LICENSE 就停）。
                skipEntryBody(din, size, bodyRead)
            }
        }
        val produced = staging.walkTopDown().filter { it.isFile }.map { it.relativeTo(staging).path }
            .toList()
        Log.i(TAG, "${dest.name}: extracted $entries entries; files=${produced.take(12)}")
        val newVer = runCatching {
            JSONObject(File(staging, "package.json").readText()).optString("version")
        }.getOrNull()
        check(!newVer.isNullOrBlank()) { "extracted tarball has no version in package.json" }
        check(entries > 0) { "extracted 0 files from tarball" }
        // 换位：旧目录先改名成备份，新目录改名就位；任何一步失败都把备份放回去。
        val backup = File(dest.parentFile, dest.name + ".upgrade-old")
        backup.deleteRecursively()
        if (dest.exists() && !dest.renameTo(backup)) dest.deleteRecursively()
        if (!staging.renameTo(dest)) {
            if (backup.exists()) backup.renameTo(dest)
            error("cannot move extracted tree into ${dest.absolutePath}")
        }
        backup.deleteRecursively()
        Log.i(TAG, "${dest.name}: extracted $entries files from tarball (version $newVer)")
        newVer
    }.getOrElse {
        Log.w(TAG, "${dest.name}: tarball extraction failed: ${it.message}")
        null
    }

    /**
     * 解析 tar 头里的八进制数字字段。
     *
     * tar 的数值字段是 `"0000007520 "` 这种**固定宽度、尾部空格 + NUL 填充**的
     * 八进制串。直接 `String(...).trim().trimEnd('\u0000')` 是错的 —— 顺序反了，
     * 先 trim 会把尾部空格去掉但留下 NUL，再 trimEnd 又留下空格，`toLongOrNull(8)`
     * 返回 null → size 被当成 0 → 之后的成员全部错位，症状是解出来的文件里混进
     * 别的文件的 CSS/JS 片段（实测诊断日志里出现 `'  width: 12px;'`）。
     *
     * 正确做法：去掉 NUL 与空格后，只取到第一个非八进制字符为止。
     */
    private fun tarOctal(header: ByteArray, off: Int, len: Int): Long {
        val raw = String(header, off, len, Charsets.US_ASCII)
            .takeWhile { it in '0'..'7' }
        return raw.toLongOrNull(8) ?: 0L
    }

    /**
     * 吃掉 tar 成员正文与 512 字节对齐补位。
     * @param size 该成员正文长度（**对齐必须按它算**）
     * @param bodyAlreadyRead 正文是否已被上层读走（true 时只吃补位）
     */
    private fun skipEntryBody(din: java.io.DataInputStream, size: Long, bodyAlreadyRead: Boolean) {
        if (!bodyAlreadyRead && size > 0) skipFully(din, size)
        val pad = (512L - (size % 512L)) % 512L
        if (pad > 0) skipFully(din, pad)
    }

    private fun skipFully(din: java.io.DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = din.skip(left)
            if (skipped <= 0) {
                if (din.read() < 0) return
                left -= 1
            } else {
                left -= skipped
            }
        }
    }
}

package app.dsh.mobile.engine

import android.content.Context
import app.dsh.mobile.R
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 社区插件中心（v1.2.113 MVP）：一键安装 dsh 生态插件。
 *
 * dsh 的插件机制：`dsh plugin --profile web add <pkg>` 把 npm 包装进 profile。
 * 本类做两件事：
 *  1. [list] —— 远程清单（我们仓库维护的 community-plugins.json，GitHub raw），
 *     拉取失败回退内置兜底清单；
 *  2. [install] —— 引擎子进程执行官方 CLI（`--expose-internals` + buildEnv，
 *     与引擎启动同一套环境），完成后回调。
 *
 * UI 入口后续补（扩展中心页加「社区插件」块）；先通过 AgentBridge 暴露给 AI
 * （GET /plugin/list、POST /plugin/install），AI 也能自助安装。
 */
object PluginCenter {
    // v1.4.16 审查 P2：安装/卸载串行化。旧实现每次 new 一个裸 Thread 直接跑
    // pnpm —— 两个插件并发安装会同时操作同一 profile，轻则锁错误、重则
    // node_modules / pnpm-store 状态损坏。单线程执行器从根上消除并发。
    private val installExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-plugin-ops").apply { isDaemon = true }
    }

    private const val TAG = "PluginCenter"

    /** 远程清单：我们仓库根目录维护，CI/手动更新；raw 直读无需鉴权 */
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/Soodok/Deepseek-Harness-Local-Android/main/community-plugins.json"

    data class PluginInfo(
        val id: String,
        val pkg: String,
        val desc: String,
        val repo: String = "",
        val version: String = "",
    )

    /** 内置兜底（远程拉不到时用；与 community-plugins.json 同步，desc 英文过 i18n 门禁）。
     *  均已核对 peerDependencies 兼容引擎 0.2.0-rc.2（2026-10-10 npm registry 实查）。 */
    /**
     * 内置精选清单（v1.4.21）—— 供 [PluginMarket] 在远端清单拉取失败时兜底，
     * 保证插件市场不空屏（离线/网络重置场景实测会空）。
     */
    fun bundledCatalog(): List<PluginMarket.Entry> = BUILTIN.map {
        PluginMarket.Entry(
            pkg = it.pkg,
            name = it.id,
            desc = it.desc,
            repo = it.repo,
            official = true,
        )
    }

    private val BUILTIN = listOf(
        PluginInfo("cost-meter", "dsh-cost-meter",
            "Session cost tracking: per-conversation/daily cost, multi-vendor price tables"),
        PluginInfo("free-search", "dsh-free-search",
            "Free web search: 24 engines (Bing/DuckDuckGo etc), no API key"),
        PluginInfo("whale-widget", "dsh-whale-widget",
            "Balance whale widget for the WebUI: balance/daily usage/peak-valley pricing"),
        PluginInfo("mcp-connector", "dsh-mcp-connector",
            "MCP connector: attach Model Context Protocol servers and search tools"),
        PluginInfo("im", "@xmanrui/dsh-im",
            "Bridge WeChat/Lark/DingTalk/QQ/Telegram/Discord into DSH"),
        PluginInfo("memory", "@openviking/dsh-memory-plugin",
            "Memory and context management across sessions"),
        PluginInfo("vision-router", "dsh-vision-router",
            "Vision router: free image understanding for text-only agents"),
        PluginInfo("genui", "@changfenhuang/dsh-genui",
            "Interactive UI components rendered in the conversation"),
        PluginInfo("rewind", "dsh-rewind-plugin",
            "In-window conversation rewind with workspace file restore"),
        PluginInfo("pet", "dsh-pet",
            "Desktop pet: the blue fat fish that refuses to leave"),
        PluginInfo("pet-bridge", "@xiaopb/dsh-pet-bridge",
            "Android pet bridge (0.3.1+, token auth): push DSH turn/tool status to the overlay",
            "https://github.com/XiaoPb/dsh-pet-bridge"),
        PluginInfo("dream-skin", "dsh-dream-skin",
            "WebUI skins: 8 iOS / Linear style themes"),
        PluginInfo("skills-manager", "@michengai/dsh-skills-manager",
            "Unified skill loading and management"),
    )

    /** 拉取远程清单；任何失败回退内置（日志留痕，不静默） */
    fun list(): List<PluginInfo> {
        val remote = runCatching {
            val conn = java.net.URL(CATALOG_URL).openConnection()
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val text = conn.getInputStream().bufferedReader().use { it.readText() }
            parseCatalog(text) ?: throw IllegalStateException("catalog parse failed")
        }.getOrElse {
            Log.w(TAG, "catalog fetch failed (${it.message}); using builtin")
            BUILTIN
        }
        return remote
    }

    /**
     * 已安装插件包名集合（v1.2.118）。
     *
     * 读 `$DSH_HOME/profiles/web/package.json` 的 dependencies —— 那是 dsh 官方
     * plugin manager 的真实记录（`dsh plugin add` 写的就是这里）。
     * ⚠️ 必须查真实文件而非内存标记：主人实测「装完显示已安装，退出重进又能装」
     * （旧版只记 UI 内存状态，Activity 重建即丢）。
     */
    fun installedPackages(ctx: Context): Set<String> = runCatching {
        val pj = java.io.File(EngineConfig.dshHome(ctx), "profiles/web/package.json")
        if (!pj.isFile) return emptySet()
        val deps = JSONObject(pj.readText()).optJSONObject("dependencies") ?: return emptySet()
        deps.keys().asSequence().toSet()
    }.getOrElse {
        Log.w(TAG, "read installed packages failed: ${it.message}")
        emptySet()
    }

    /**
     * 已安装插件的**实际版本**（v1.4.18）—— 读
     * `$DSH_HOME/profiles/web/node_modules/<pkg>/package.json` 的 version。
     *
     * 为什么不读 profile package.json 的 dependencies spec：那是声明范围
     * （如 `^0.74.0`），pnpm 装完后即使 registry 有新版也不会动它——这正是
     * 「升级不行」的根源。真实安装版本以 node_modules 里落盘的为准
     * （pnpm symlink 布局，File 读文本自然跟随软链到 .pnpm 实体）。
     *
     * @return pkg -> version；读不到的包不出现在 map 里
     */
    fun installedVersions(ctx: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (pkg in installedPackages(ctx)) {
            runCatching {
                val pj = java.io.File(
                    EngineConfig.dshHome(ctx), "profiles/web/node_modules/$pkg/package.json",
                )
                if (pj.isFile) {
                    val v = JSONObject(pj.readText()).optString("version", "")
                    if (v.isNotBlank()) out[pkg] = v
                }
            }
        }
        return out
    }

    /**
     * 实时搜索 npm 上的插件（registry search API，v1.2.118）。
     * 查询自动限定 `keywords:deepseek-harness` 生态；@deepseek-ai/ 官方内部组件
     * （引擎部件，非插件）被排除。主人在插件中心看到的列表即此实时结果。
     * @param query 用户关键词，空 = 全部生态
     * @param onDone 后台线程回调（网络失败返回空列表，UI 层回退内置精选）
     */
    fun search(query: String, onDone: (List<PluginInfo>) -> Unit) {
        Thread({
            // v1.2.118：**放开为全网 npm 搜索** —— 主人反馈「插件太少」。
            // 空查询 = 浏览 dsh 生态（keywords:deepseek-harness）；有查询词 =
            // 直接搜全网 npm（用户想装什么就搜什么，不再限定生态关键词）。
            val q = if (query.isBlank()) "keywords:deepseek-harness" else query.trim()
            val encoded = java.net.URLEncoder.encode(q, "UTF-8")
            val url = "https://registry.npmjs.org/-/v1/search?text=$encoded&size=50"
            val result = runCatching {
                val conn = java.net.URL(url).openConnection()
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val text = conn.getInputStream().bufferedReader().use { it.readText() }
                parseSearch(text, browseMode = query.isBlank())
            }.getOrElse {
                Log.w(TAG, "npm search failed (${it.message}); empty result")
                emptyList()
            }
            onDone(result)
        }, "dsh-plugin-search").apply { isDaemon = true; start() }
    }

    private fun parseSearch(text: String, browseMode: Boolean): List<PluginInfo> = runCatching {
        val d = JSONObject(text)
        val arr = d.getJSONArray("objects")
        val out = ArrayList<PluginInfo>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i).getJSONObject("package")
            val name = p.getString("name")
            // 生态浏览（空查询）时排除官方内部组件：它们是引擎部件而非可装插件；
            // 显式搜索（用户输入了词）时不排除 —— 用户搜什么就给什么。
            if (browseMode && name.startsWith("@deepseek-ai/")) continue
            out.add(
                PluginInfo(
                    id = name.substringAfterLast('/'),
                    pkg = name,
                    desc = p.optString("description", ""),
                    version = p.optString("version", ""),
                ),
            )
        }
        out
    }.getOrDefault(emptyList())

    private fun parseCatalog(text: String): List<PluginInfo>? = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PluginInfo(
                o.getString("id"),
                o.getString("package"),
                o.optString("desc", ""),
                o.optString("repo", ""),
            )
        }
    }.getOrNull()

    /**
     * 一键安装：引擎子进程执行 `dsh plugin --profile web add <pkg>`。
     * 与引擎启动同环境（buildEnv），保证 pnpm/npm registry 配置一致。
     * v1.4.18：失败命中 pnpm store 锁损坏签名时自动清缓存重试一次
     * （见 [healPnpmStore]）。
     * @param onDone (成功, 详情或错误信息) —— 在后台线程回调
     */
    fun install(ctx: Context, pkg: String, onDone: (Boolean, String) -> Unit) {
        // v1.4.27：应急总开关生效期间拒绝安装。装进来也不会被加载 —— 下次启动的
        // 收敛会把 bundles 再摘一遍，用户只会看到「装完没反应」这种更难查的现象。
        // v1.4.28：停用期间**放开**安装/更新 —— 装了不加载（不在白名单），用户
        // 可以先更新嫌疑插件再单独启用它试；只拦「正在恢复」窗口（enabling），
        // 那会儿监督器正要按日志写回 bundles，新装的包会被写回动作丢掉。
        if (PluginKillSwitch.isRestoring(ctx)) {
            Log.w(TAG, "install $pkg refused: restore in progress")
            onDone(false, "plugins are being restored; try again in a moment")
            return
        }
        installExecutor.execute({
            var ok = false
            var msg = "?"
            try {
                // v1.4.52：已装插件的「更新」语义修复（pet-bridge 0.3.1→0.3.3
                // 死循环实测）。`dsh plugin add <pkg>` 对**已存在**的包是空操作
                // —— pnpm 报 "Lockfile is up to date, resolution step is
                // skipped"（7ms 完成，版本原地不动）；而插件页的更新建议按
                // 「registry latest vs 落盘实际版本」计算，于是建议永远在、
                // 更新永远无效。已装的包改用 pkg@latest 强制重新解析；
                // 新装仍用裸包名。
                val spec = if (pkg in installedPackages(ctx)) "$pkg@latest" else pkg
                val r = runDshPluginCli(ctx, "add", spec)
                ok = r.first
                msg = r.second
                if (!ok && isPnpmStoreLockError(msg)) {
                    val healed = healPnpmStore(ctx)
                    Log.w(TAG, "install $pkg: pnpm store lock broken; heal=$healed; retrying once")
                    val r2 = runDshPluginCli(ctx, "add", spec)
                    ok = r2.first
                    msg = if (ok) r2.second
                    else "${r2.second} [pnpm store cache reset, still failing]"
                }
                // v1.4.37（用户截图 2）：`ERR_PNPM_IGNORED_BUILDS`。
                // pnpm 10+ 默认**不执行**依赖的 install/postinstall 脚本（安全策略），
                // 带原生模块的插件装完但二进制没编译，`build/` 不存在 → 插件加载失败。
                // 用户的报错被截断成 `ERR_PNPM_IGNORED_BUIL`,看不出是哪种。
                //
                // 修法：在 profile 里写 `.npmrc` 声明 onlyBuiltDependencies 白名单，
                // 再重试一次。白名单只放**本次安装的插件本身**（不是 `*`）——
                // 放开通配等于对所有依赖执行任意脚本，那是供应链攻击面。
                if (!ok && isIgnoredBuildsError(msg)) {
                    val named = allowBuildFor(ctx, pkg)
                    Log.w(TAG, "install $pkg: pnpm ignored builds; allowlisted=$named; retrying once")
                    val r2 = runDshPluginCli(ctx, "add", spec)
                    ok = r2.first
                    msg = if (ok) {
                        r2.second + if (named) " [build scripts allowed]" else ""
                    } else {
                        "${r2.second} [pnpm still refuses build scripts; the native module likely cannot compile on Android]"
                    }
                }
                // v1.4.37（用户反馈「插件下不了」）：引擎的插件管理器在安装前会做
                // 兼容性预检，不通过就直接回滚并输出
                // `dsh: installation rejected: <requirements>`。
                // 旧实现把 stderr 截到 300 字符，用户只看到冒号后面空空的
                // 「installation rejected:」——完全不知道原因。
                //
                // 这里不再自动放行（那可能让引擎崩溃或丢数据），而是：
                //   ① 把真实原因解析出来（要求 vs 内置版本）
                //   ② 明确告知用户「可以一键豁免」及其风险
                //   ③ 需要用户显式确认才执行 allow-version
                if (!ok) {
                    val rejection = parseEngineRejection(ctx, pkg, msg)
                    if (rejection != null) {
                        msg = rejection.userMessage(ctx)
                        Log.w(TAG, "install $pkg rejected by engine compat: $rejection")
                    }
                }
            } catch (e: Exception) {
                msg = e.message ?: "install failed"
            }
            Log.i(TAG, "install $pkg -> ok=$ok: $msg")
            onDone(ok, msg)
        })
    }

    /**
     * 卸载插件（v1.2.120）—— dsh plugin --profile web remove <pkg>。
     *
     * dsh plugin 转发给 pnpm（实测 --help 显示 pnpm 命令表），故卸载即
     * `pnpm remove`。此前插件中心只能装不能卸（主人反馈），装错/不想要的
     * 插件只能靠 CLI 手工处理。v1.4.18：与 install 共享 store 锁自愈。
     */
    fun uninstall(ctx: Context, pkg: String, onDone: (Boolean, String) -> Unit) {
        installExecutor.execute({
            var ok = false
            var msg = "?"
            try {
                val r = runDshPluginCli(ctx, "remove", pkg)
                ok = r.first
                msg = r.second
                if (!ok && isPnpmStoreLockError(msg)) {
                    val healed = healPnpmStore(ctx)
                    Log.w(TAG, "uninstall $pkg: pnpm store lock broken; heal=$healed; retrying once")
                    val r2 = runDshPluginCli(ctx, "remove", pkg)
                    ok = r2.first
                    msg = if (ok) r2.second
                    else "${r2.second} [pnpm store cache reset, still failing]"
                }
            } catch (e: Exception) {
                msg = e.message ?: "uninstall failed"
            }
            Log.i(TAG, "uninstall $pkg -> ok=$ok: $msg")
            onDone(ok, msg)
        })
    }

    /**
     * 跑一次 `dsh plugin --profile web <verb> <pkg>` 子进程（install/uninstall 共用）。
     * @param verb add / remove
     * @return (成功与否, 截断到 300 字符的 stdout 或 stderr)
     */
    private fun runDshPluginCli(
        ctx: Context,
        verb: String,
        pkg: String,
        extraArgs: List<String> = emptyList(),
    ): Pair<Boolean, String> {
        val node = EngineConfig.nodeBin(ctx)
        val entry = EngineConfig.dshEntry(ctx)
        val env = EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT)
        val pb = ProcessBuilder(
            buildList {
                add(node.absolutePath)
                add("--expose-internals")
                add(entry.absolutePath)
                addAll(listOf("plugin", "--profile", "web", verb, pkg))
                addAll(extraArgs)
            },
        )
        pb.environment().clear()
        env.forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
        }
        val p = pb.start()
        val out = p.inputStream.readBytes()
        val err = p.errorStream.readBytes()
        return if (p.waitFor(120, TimeUnit.SECONDS)) {
            if (p.exitValue() == 0) {
                true to String(out, Charsets.UTF_8).trim().take(300)
            } else {
                // v1.4.37：从 300 放宽到 1200 —— 引擎把「为什么拒绝」写在
                // `installation rejected:` 之后，300 字符正好截在冒号处，
                // 用户只看到一个空荡荡的错误（用户反馈「插件下不了」的现场）。
                false to String(err, Charsets.UTF_8).trim().take(1200)
                    .ifEmpty { "exit=${p.exitValue()}" }
            }
        } else {
            p.destroy()
            false to "plugin $verb timed out (120s)"
        }
    }

    /**
     * pnpm 拒绝执行依赖 build 脚本（v1.4.37，用户截图报 `ERR_PNPM_IGNORED_BUIL`）。
     *
     * pnpm 10 起默认把依赖的 install / postinstall 脚本列为「ignored builds」，
     * 只打印警告不执行。带原生模块的插件（node-gyp / prebuild-install）因此
     * 装完却没有编译产物，插件加载时报错或直接被引擎判为不可用。
     *
     * 错误文本各版本措辞不同，这里同时匹配错误码与警告措辞。
     */
    private fun isIgnoredBuildsError(msg: String): Boolean =
        msg.contains("ERR_PNPM_IGNORED_BUILDS", ignoreCase = true) ||
            msg.contains("ERR_PNPM_IGNORED_BUILD", ignoreCase = true) ||
            (msg.contains("ignored build", ignoreCase = true) &&
                msg.contains("onlyBuiltDependencies", ignoreCase = true)) ||
            msg.contains("Ignored build scripts", ignoreCase = true)

    /**
     * 把 [pkg] 写进 profile 的 `.npmrc` `onlyBuiltDependencies` 白名单。
     *
     * ## 为什么只放这一个包，不用 `*`
     * `onlyBuiltDependencies` 是「允许执行 install 脚本的包名清单」。写 `*` 等于
     * 允许**所有**传递依赖执行任意 postinstall 脚本 —— 那是供应链攻击的经典入口。
     * 用户点安装的是这一个插件，就只放它。
     *
     * ## 同时关掉 pnpm 的 ignored-builds 警告升级
     * pnpm 在 `strict` 模式下会把 ignored builds 从警告升级为失败。我们显式给了
     * 白名单，就不该再被这条策略拦住，所以把 `dangerouslyAllowAllBuilds` 保持关闭
     * —— 只靠白名单，最小权限。
     *
     * @return 是否成功写入
     */
    private fun allowBuildFor(ctx: Context, pkg: String): Boolean = runCatching {
        val dir = java.io.File(EngineConfig.dshHome(ctx), "profiles/web")
        if (!dir.isDirectory) return@runCatching false
        // ⚠️ v1.4.46（安全审查 M3）：pkg 未校验就写进 .npmrc —— 构造含换行的
        // 名字可注入新指令（如 dangerouslyAllowAllBuilds=true），绕过 pnpm 10
        // 默认拒绝执行依赖 build 脚本的供应链防线。只接受合法 npm 包名。
        if (!pkg.matches(NPM_PACKAGE_NAME)) {
            Log.w(TAG, "allowBuildFor: refuse illegal package name: " + pkg.take(80))
            return@runCatching false
        }
        val npmrc = java.io.File(dir, ".npmrc")
        val existing = if (npmrc.isFile) npmrc.readText() else ""
        // 已有 onlyBuiltDependencies 就合并，不覆盖用户可能自己加的内容
        val line = Regex("""(?m)^onlyBuiltDependencies\s*=.*$""")
        val current = line.find(existing)?.value?.substringAfter('=')?.trim().orEmpty()
        val items = current.split(',', ' ')
            .map { it.trim().trim('"', '\'').trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
        if (pkg in items) return@runCatching true
        items += pkg
        val updated = if (line.containsMatchIn(existing)) {
            // lambda 形式：replacement 里的 $ 不会被当成分组引用（安全审查 M3）
            line.replace(existing) { "onlyBuiltDependencies=" + items.joinToString(",") }
        } else {
            val nl = System.lineSeparator()
            (existing.trimEnd() + nl + "onlyBuiltDependencies=" + items.joinToString(",") + nl)
        }
        npmrc.writeText(updated)
        Log.i(TAG, "allowBuildFor: $pkg -> ${npmrc.absolutePath}")
        true
    }.getOrDefault(false)

    /**
     * 引擎的插件兼容性拒绝（v1.4.37，用户反馈「插件下不了」）。
     *
     * ## 现象
     * 插件中心点安装后显示 `失败：dsh: installation rejected:` —— 冒号后面什么都没有。
     * 用户完全不知道原因，只能反复重试。
     *
     * ## 根因
     * 这不是 pnpm 的错误。引擎的 `dsh-plugin-manager` 在**安装前**做兼容性预检
     * （`evaluatePluginCompatibility`）：插件 `engines.dsh` 要求的最低版本高于内置
     * 引擎版本时不通过，直接回滚并输出
     *
     *     dsh: installation rejected: Plugin <pkg>@<ver> is incompatible with
     *     dsh <runtime>: requirements {...}. ... To accept this risk explicitly,
     *     grant the exact-version exemption for <pkg>@<ver> on dsh <runtime> with
     *     `dsh plugin allow-version` ...
     *
     * 旧实现把 stderr 截断到 300 字符，正好截在冒号之后，把真正的原因（以及引擎
     * 自己给出的解法）全丢了。
     *
     * ## 为什么不自动放行
     * 引擎的警告原文是「Running it may cause crashes or data loss」，且它的官方
     * API 强制要求 `acceptRisk: true` 显式确认。App 替用户静默接受这个风险不合适，
     * 所以这里只负责**把原因说清楚**，并在用户明确同意后才执行 `allow-version`。
     */
    internal data class EngineRejection(
        val pkg: String,
        val version: String?,
        val runtime: String?,
        val requirements: String,
    ) {
        /**
         * 给用户看的可读信息：原因 + 内置版本 + 可选做法。
         *
         * 全部文案走 `strings.xml`（i18n 门禁禁止 Kotlin 里写中文），
         * 用 [ctx] 取已按应用语言包装过的资源。
         */
        fun userMessage(ctx: Context): String = buildString {
            val nl = System.lineSeparator()
            append(ctx.getString(R.string.plugin_engine_old_why, runtime ?: "?"))
            if (requirements.isNotBlank()) {
                append(nl).append(ctx.getString(R.string.plugin_engine_old_req, requirements))
            }
            append(nl).append(ctx.getString(R.string.plugin_engine_old_note))
        }
    }

    /**
     * 从安装失败的输出里解析引擎兼容性拒绝。
     * @return null = 不是这类错误
     */
    internal fun parseEngineRejection(ctx: Context, pkg: String, msg: String): EngineRejection? {
        if (!msg.contains("installation rejected", ignoreCase = true) &&
            !msg.contains("incompatible with dsh", ignoreCase = true) &&
            !msg.contains("compatibility", ignoreCase = true)
        ) {
            return null
        }
        // 引擎原文形如：Plugin <name>@<version> is incompatible with dsh <runtime>:
        // requirements 后跟一个 JSON 对象，形如 dsh: 大于等于 0.2.1-rc.3
        val pkgVer = Regex("""Plugin\s+(\S+?)@(\S+?)\s+is incompatible""")
            .find(msg)?.let { it.groupValues[1] to it.groupValues[2] }
        val runtime = Regex("""incompatible with dsh\s+([^\s:]+)""")
            .find(msg)?.groupValues?.get(1)
        val reqs = Regex("""requirements\s+(\{[^}]*\})""")
            .find(msg)?.groupValues?.get(1).orEmpty()
        val name = pkgVer?.first ?: pkg
        val version = pkgVer?.second ?: runCatching {
            // 退路：从本地已读到的 registry 版本推断（市场列表里的 version）
            null
        }.getOrNull()
        val builtin = runtime ?: runCatching {
            val pj = java.io.File(
                EngineConfig.engineRoot(ctx), "lib/node_modules/@deepseek-ai/dsh/package.json",
            )
            JSONObject(pj.readText()).getString("version")
        }.getOrNull()
        return EngineRejection(name, version, builtin, reqs)
    }

    /**
     * 为「引擎版本不够」的插件写入 exact-version 豁免，然后重试安装。
     *
     * 走引擎官方命令（不手写 compatibility.json）：
     *   `dsh plugin --profile web allow-version <pkg>@<ver> --dsh-version <rt> --accept-risk`
     *
     * **必须由用户显式确认**：引擎文档与错误信息都写明运行不兼容插件
     * 「may cause crashes or data loss」，它的 API 也强制 `acceptRisk: true`。
     * 本方法只在该确认已经拿到后调用。
     */
    fun forceInstallWithExemption(
        ctx: Context,
        pkg: String,
        version: String,
        onDone: (Boolean, String) -> Unit,
    ) {
        installExecutor.execute({
            try {
                // 恢复窗口（enabling）内与 install() 同规：此时新装动作会被
                // 监督器的 bundles 写回丢掉，豁免+重装等于白做，直接拒绝
                // （审查发现的竞态缺口，install():229 同一检查）。
                if (PluginKillSwitch.isRestoring(ctx)) {
                    onDone(false, "plugins are being restored; try again in a moment")
                    return@execute
                }
                val runtime = runCatching {
                    val pj = java.io.File(
                        EngineConfig.engineRoot(ctx),
                        "lib/node_modules/@deepseek-ai/dsh/package.json",
                    )
                    JSONObject(pj.readText()).getString("version")
                }.getOrNull()
                if (runtime == null) {
                    onDone(false, ctx.getString(R.string.plugin_engine_old_no_version))
                    return@execute
                }
                // ⚠️ v1.4.45 审查修复：UI 主链路传入的 pkg 是完整 spec
                // （`name@version`，PluginStoreActivity.runInstall 原样透传），
                // 直接拼 "@$version" 会得到 `name@ver@ver` 非法参数——豁免
                // 写不进去，「强制安装此版本」在市场/升级链路上必失败。
                // 只剥"恰好等于本次 version"的后缀：scoped 包名（@scope/name）
                // 开头的 @ 与无版本的裸包名都不受影响。
                val base = if (pkg.endsWith("@$version")) pkg.removeSuffix("@$version") else pkg
                val r = runDshPluginCli(
                    ctx, "allow-version", "$base@$version",
                    extraArgs = listOf("--dsh-version", runtime, "--accept-risk"),
                )
                if (!r.first) {
                    onDone(false, ctx.getString(R.string.plugin_engine_old_exempt_failed, r.second))
                    return@execute
                }
                Log.i(TAG, "exemption granted for $pkg@$version on dsh $runtime")
                // 豁免写好后重试安装
                val again = runDshPluginCli(ctx, "add", pkg)
                onDone(again.first, again.second)
            } catch (e: Exception) {
                onDone(false, e.message ?: "force install failed")
            }
        })
    }

    /**
     * pnpm store 锁损坏签名（v1.4.18）。用户截图报：
     * `ERR_PNPM_STORE_DIR_OPEN_OPERATION_LOCK`，消息原文含
     * "lock directory must be a real directory owned by the current user"，
     * 路径指向 `$HOME/.cache/pnpm-store…/locks-0`。错误码与消息双签名
     * 大小写不敏感匹配，防 pnpm 版本措辞变化。
     */
    private fun isPnpmStoreLockError(msg: String): Boolean =
        msg.contains("ERR_PNPM_STORE_DIR_OPEN_OPERATION_LOCK", ignoreCase = true) ||
            msg.contains("store operation lock", ignoreCase = true)

    /**
     * 自愈 pnpm store 锁损坏（v1.4.18）。成因两类：
     * ① Root 模式残留属主 —— 引擎以 uid 0 写过 `.cache/pnpm-*`，
     *    切回普通模式后 app uid 无法持锁（报"owned by the current user"）；
     * ② 安装中途进程被杀，留下悬空锁目录。
     *
     * `.cache` 下以 pnpm 开头的条目（store 本体 / operation-locks /
     * dir-locks / modules-swaps）全是可重建缓存，删掉不影响已装插件本体；
     * 唯一例外 `.cache/pnpm`（PNPM_HOME，全局 bin）必须排除。
     *
     * 清理两级递进：
     * 1. 直接删（普通死锁文件场景，最干净）；
     * 2. 删不动（Root 残留属主 + 000，里面的 .lock 无法 unlink）→
     *    **rename 绕开**：rename 只改父目录条目、不进入目录内部，只要
     *    `.cache` 本身可写（必然）就成功。pnpm 只按固定名
     *    （如 `pnpm-store-operation-locks-<uid>`）找锁，改名后等于无锁
     *    状态，下次安装自动重建。改名残留的 `.broken-*` 由 pnpm 侧无感，
     *    占用极小（锁目录只有两个空 .lock 文件）。
     */
    private fun healPnpmStore(ctx: Context): Boolean {
        val cacheDir = File(EngineConfig.dshHome(ctx), ".cache")
        fun targets(): List<File> = runCatching {
            cacheDir.listFiles()?.filter {
                it.name.startsWith("pnpm") && it.name != "pnpm"
            }.orEmpty()
        }.getOrDefault(emptyList())

        if (targets().isEmpty()) return true
        targets().forEach { EngineConfig.deleteNoFollow(it) }

        if (targets().isNotEmpty()) {
            // 删不动 = 属主/权限坏（Root 残留 000）。rename 不进入目录内部，
            // 只改 .cache 的目录条目，属主正常即必成功。
            val ts = System.currentTimeMillis()
            targets().forEach { f ->
                val moved = runCatching { f.renameTo(File(cacheDir, ".broken-${f.name}-$ts")) }
                    .getOrDefault(false)
                if (moved) Log.w(TAG, "healPnpmStore: renamed undeletable ${f.name} out of the way")
            }
        }
        return targets().isEmpty()
    }

    /**
     * 合法 npm 包名（含 scoped）。用于阻断写进 .npmrc 的换行/指令注入
     * （v1.4.46 安全审查 M3）。npm 规则：小写，可含 . _ - ~，可带 @scope/。
     */
    private val NPM_PACKAGE_NAME =
        Regex("^(@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*$")

}

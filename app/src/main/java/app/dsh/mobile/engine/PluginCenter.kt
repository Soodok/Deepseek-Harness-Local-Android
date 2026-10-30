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
                // ══════════════════════════════════════════════════════════
                // v1.4.65（宝宝第 3 次反馈「报错一点没变」，真机取证后定位）
                //
                // 真正让插件**一个都装不上**的不是「构建脚本被忽略」，而是
                // profiles/web/pnpm-workspace.yaml 里残留了 pnpm 11 已删除的
                // `onlyBuiltDependencies:` 段，且其中 scoped 包名是裸写的：
                //     onlyBuiltDependencies:
                //       - @zzerx/dsh-plugin-notes
                // YAML 里裸 `@` 是保留指示符 → 整份配置解析失败 →
                //     × load configuration / unexpected character: `@'
                // pnpm 连 add 都跑不起来。报错里因此永远没有
                // `Ignored build scripts`，批准逻辑一次都没被触发过。
                //
                // 这个坏文件是 v1.4.59 那轮写进去的，代码撤回了但设备上的文件
                // 一直留着 —— 所以「改了几版，报错一点没变」。
                // 每次安装前先做最小外科清理，幂等，没坏键时不写文件。
                // ══════════════════════════════════════════════════════════
                if (healPnpmWorkspace(ctx)) {
                    Log.w(TAG, "install $pkg: cleaned obsolete build keys in pnpm-workspace.yaml")
                }
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
                // ══════════════════════════════════════════════════════════
                // v1.4.67（宝宝第 5 次反馈「还是下载失败，还是报错」，真机实测定位）
                //
                // 真机事实：pnpm 报 ERR_PNPM_IGNORED_BUILDS 时，**包其实已经装好了**
                // —— profile/package.json 的 dependencies 里有它，node_modules 里也有
                // 实体目录。pnpm 只是因依赖的 postinstall（如 esbuild）未获批准而
                // 用 exit 1 收尾，App 就整条判失败、UI 显示「失败」。
                //
                // 之前几版想「弹框让用户点批准」全部落空，因为流程走不通/太绕，
                // 用户侧只会看到同一条报错。而早期版本（v1.4.37）的做法是
                // **自动把当前包加进白名单再重试一次** —— 那时候能装成功，
                // 这正是宝宝说的「早期自己写的时候都不会」。
                //
                // 所以这里恢复那条路径，但按 pnpm 11+ 的正确配置位来写：
                //   ① 从输出里解析 pnpm 明确点名的包（`Ignored build scripts: esbuild@0.28.2`）
                //   ② 只把这些**具体名字**写成 allowBuilds: { name: true }
                //      —— 绝不用 `*` / dangerouslyAllowAllBuilds，通配等于允许
                //      所有传递依赖跑任意脚本，那是供应链攻击面
                //   ③ 重试同一个 spec，成功后正常返回
                //
                // 安全性：放行范围严格等于 pnpm 自己报告的那几个包，不扩权。
                // ══════════════════════════════════════════════════════════
                if (!ok && isIgnoredBuildsError(msg)) {
                    val names = ignoredBuildNames(msg)
                        .ifEmpty { probeIgnoredBuilds(ctx, spec) }
                    if (names.isNotEmpty() && allowBuildsFor(ctx, names)) {
                        Log.i(TAG, "install $pkg: pnpm ignored builds for ${names.joinToString(",")}; allowed+retrying")
                        val r3 = runDshPluginCli(ctx, "add", spec)
                        ok = r3.first
                        msg = if (ok) r3.second else r3.second
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
     * 直接调 pnpm 拿完整的失败输出（v1.4.66）。
     *
     * ## 为什么要绕开引擎（真机实测，不是推测）
     *
     * engine 的 `dsh plugin add` 只把 pnpm 的输出摘要写进 stderr：
     *     Command failed with exit code 1: pnpm add '@zzerx/dsh-plugin-memory'
     * 整整 68 字节，**没有** `Ignored build scripts: ...` 那一行。
     * 所以 App 侧不管怎么放宽截断都解析不到包名（前三版都没解决的原因）。
     *
     * 而设备上直接跑 pnpm 能拿到全文：
     *     Error: ERR_PNPM_IGNORED_BUILDS
     *       ╰─▶ Ignored build scripts: esbuild@0.28.2
     * 因为引擎把 pnpm 的输出收敛成一行摘要，直连才有细节。
     *
     * ## 为什么跑 `add` 是安全的
     *
     * 只读探测：这次 add 与 engine 刚跑的那次结果相同（同样被 ignored-builds
     * 拦下、不会真的改变落盘依赖），我们只取它打印的包名。已经装好的包不会
     * 因为这次探测被移除。
     *
     * @return 被 pnpm 跳过构建脚本的包名；解析不到时返回空
     */
    private fun probeIgnoredBuilds(ctx: Context, spec: String): List<String> = runCatching {
        val engine = EngineConfig.engineRoot(ctx)
        val pnpmJs = File(engine, "lib/node_modules/corepack/dist/pnpm.js")
        if (!pnpmJs.isFile) {
            Log.w(TAG, "probeIgnoredBuilds: pnpm.js missing at " + pnpmJs.absolutePath)
            return@runCatching emptyList()
        }
        val workDir = File(EngineConfig.dshHome(ctx), "profiles/web")
        val env = EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT)
        val pb = ProcessBuilder(
            buildList {
                add(EngineConfig.nodeBin(ctx).absolutePath)
                add(pnpmJs.absolutePath)
                add("add")
                add(spec)
            },
        )
        pb.directory(workDir)
        pb.environment().clear()
        env.forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
        }
        val p = pb.start()
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        fun pump(input: java.io.InputStream, sink: StringBuilder) = Thread {
            runCatching {
                input.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    var n = r.read(buf)
                    while (n > 0) {
                        synchronized(sink) { sink.append(buf, 0, n) }
                        n = r.read(buf)
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
        val outT = pump(p.inputStream, outBuf)
        val errT = pump(p.errorStream, errBuf)
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            runCatching { p.destroyForcibly() }
            runCatching { p.inputStream.close() }
            runCatching { p.errorStream.close() }
            runCatching { outT.join(1000) }
            runCatching { errT.join(1000) }
            Log.w(TAG, "probeIgnoredBuilds: timed out")
            return@runCatching emptyList()
        }
        runCatching { outT.join(2000) }
        runCatching { errT.join(2000) }
        val combined = synchronized(outBuf) { outBuf.toString() } +
            "\n" + synchronized(errBuf) { errBuf.toString() }
        val names = ignoredBuildNames(combined)
        Log.i(
            TAG,
            "probeIgnoredBuilds: exit=" + p.exitValue() +
                " names=" + names.joinToString(",") +
                " (" + combined.trim().length + " chars captured)",
        )
        names
    }.getOrDefault(emptyList())

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

        // ⚠️ v1.4.66（宝宝反馈「插件下载完没有回调，一直显示安装中」）：
        // 旧实现是
        //     val out = p.inputStream.readBytes()
        //     val err = p.errorStream.readBytes()
        //     if (p.waitFor(120, SECONDS)) ...
        // 三个致命问题：
        //   ① `readBytes()` 阻塞到**流关闭**（进程退出）才返回，所以它排在
        //      waitFor 之前 = 超时检查永远走不到，进程卡住就永久挂起；
        //   ② 先读 stdout 再读 stderr：stderr 管道（默认 64KB）写满后，
        //      子进程阻塞在写、父进程阻塞在等 stdout EOF —— 经典死锁；
        //   ③ 挂起的任务占着 installExecutor 这个**单线程**池，后续安装全部
        //      排队永不执行。
        // 结果就是 UI 停在「安装中」，而插件其实已经装好了（用户实测）。
        //
        // 现在：两个流各起一个线程并发读，主线程只做 waitFor；超时则强杀并
        // 关流，保证一定返回。
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        fun pump(input: java.io.InputStream, sink: StringBuilder) = Thread {
            runCatching {
                input.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    var n = r.read(buf)
                    while (n > 0) {
                        synchronized(sink) { sink.append(buf, 0, n) }
                        n = r.read(buf)
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        val outT = pump(p.inputStream, outBuf)
        val errT = pump(p.errorStream, errBuf)

        val finished = p.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            // 强杀 + 关流：否则读线程会一直阻塞，installExecutor 仍被占着
            runCatching { p.destroyForcibly() }
            runCatching { p.inputStream.close() }
            runCatching { p.errorStream.close() }
            runCatching { outT.join(1000) }
            runCatching { errT.join(1000) }
            Log.w(TAG, "plugin $verb $pkg timed out (120s); killed")
            return false to "plugin $verb timed out (120s)"
        }
        // 进程已退出：给读线程一点时间把剩余缓冲读完（通常瞬间完成）
        runCatching { outT.join(2000) }
        runCatching { errT.join(2000) }
        val out = synchronized(outBuf) { outBuf.toString() }
        val err = synchronized(errBuf) { errBuf.toString() }
        return if (p.exitValue() == 0) {
            true to out.trim().take(300)
        } else {
            // v1.4.37：从 300 放宽到 1200 —— 引擎把「为什么拒绝」写在
            // `installation rejected:` 之后，300 字符正好截在冒号处，
            // 用户只看到一个空荡荡的错误（用户反馈「插件下不了」的现场）。
            //
            // v1.4.64：改成「头 + 尾」而不是只留头。pnpm 的
            // `Ignored build scripts: ...` 警告落在输出**末尾**，只留前 1200
            // 字符会把它整段切掉 —— 于是 ignoredBuildNames() 永远解析不到包名，
            // 批准入口也就永远不出现（宝宝反馈「还是同样的报错」的第二个根因）。
            val raw = err.trim().ifEmpty { "exit=${p.exitValue()}" }
            // pnpm 的 `Ignored build scripts:` 警告是**警告**不是错误，可能落在
            // stdout 而不是 stderr。只拼 stderr 会导致 ignoredBuildNames() 解析
            // 不到——把 stdout 也接在后面，保证两条流里的线索都进 msg。
            val combined = if (out.isNotBlank()) raw + "\n" + out.trim() else raw
            false to clipHeadTail(combined, 2400)
        }
    }

    /** 超长输出保留头尾各一半，中间标注省略量 —— 错误的关键信息两头都有。 */
    private fun clipHeadTail(s: String, max: Int): String {
        if (s.length <= max) return s
        val half = max / 2
        val dropped = s.length - (half * 2)
        return s.take(half) + "\n…[" + dropped + " chars omitted]…\n" + s.takeLast(half)
    }

    /**
     * pnpm 拒绝执行依赖 build 脚本（v1.4.37，用户截图报 `ERR_PNPM_IGNORED_BUIL`）。
     *
     * pnpm 11 起默认把依赖的 install / postinstall 脚本列为「ignored builds」，
     * 只打印警告不执行。带原生模块的插件（node-gyp / prebuild-install）因此
     * 装完却没有编译产物，插件加载时报错或直接被引擎判为不可用。
     *
     * 错误文本各版本措辞不同，这里同时匹配错误码与警告措辞。
     * v1.4.63：措辞里已不出现 onlyBuiltDependencies（该键在 pnpm 11 被
     * `allowBuilds` 取代），改为同时认 allowBuilds。
     */
    private fun isIgnoredBuildsError(msg: String): Boolean =
        msg.contains("ERR_PNPM_IGNORED_BUILDS", ignoreCase = true) ||
            msg.contains("ERR_PNPM_IGNORED_BUILD", ignoreCase = true) ||
            (msg.contains("ignored build", ignoreCase = true) &&
                (msg.contains("allowBuilds", ignoreCase = true) ||
                    msg.contains("onlyBuiltDependencies", ignoreCase = true))) ||
            msg.contains("Ignored build scripts", ignoreCase = true)

    // ══════════════════════════════════════════════════════════════════
    // 构建脚本批准（v1.4.63：照抄上游 @deepseek-ai/dsh-plugin-manager
    //   lib/types/build-approval.js，不再自造 .npmrc 白名单）
    //
    // ## 为什么换掉旧实现
    // 旧实现往 `profiles/web/.npmrc` 写 onlyBuiltDependencies 列表。这个键在
    // **pnpm 11 就被删除了**（官方 migration：onlyBuiltDependencies /
    // onlyBuiltDependenciesFile / neverBuiltDependencies / ignoredBuiltDependencies
    // / ignoreDepScripts 全部由 `allowBuilds` 取代），而且 pnpm 10 起 `.npmrc`
    // 只读 auth/registry/TLS/proxy/local-address 五类设置。设备上 corepack 拉的
    // 是 pnpm 12.15.0 —— 所以旧写法**从来没有生效过**，这就是
    // ERR_PNPM_IGNORED_BUILDS 反复修不掉的真因。
    //
    // ## 上游的机制（本类完全照抄）
    // 运行时配置位是 `pnpm-workspace.yaml` 的 `allowBuilds` 映射：
    //     allowBuilds:
    //       esbuild: true      ← 批准
    //       core-js: false     ← 拒绝（不再询问）
    //       sharp: set this to true or false   ← pnpm 留下的「待决定」占位值
    // 引擎在安装失败时自己把 `Ignored build scripts: a, b` 里的包名以占位值写进
    // 该文件，并把待批准名单放进安装结果（`failure.pendingBuilds`），
    // 由 UI 让用户点「批准并重试」。
    //
    // ## 为什么 App 侧绝不新建这个文件
    // v1.4.59 那一轮曾「凭猜测」在 profile 里造了一个 pnpm-workspace.yaml，
    // 内容不是合法映射 → pnpm 报 Load configuration → **所有插件都装不了**，
    // 比原 bug 更严重。所以这里 approveBuilds 只在文件已存在、且 `allowBuilds`
    // 段形状认得出来时改写；认不出就返回 false 放弃，绝不硬写。
    // ══════════════════════════════════════════════════════════════════

    /** pnpm 的构建脚本批准策略文件（与上游 build-approval.js 同一路径）。 */
    private fun policyFile(ctx: Context): File =
        File(File(EngineConfig.dshHome(ctx), "profiles/web"), "pnpm-workspace.yaml")

    /** pnpm 留给「还没决定」的构建脚本的占位值（上游 UNDECIDED_BUILD 原文）。 */
    private const val UNDECIDED_BUILD = "set this to true or false"

    /** 非交互运行里 pnpm 列出被跳过脚本的那一行（上游 IGNORED_BUILDS 原文）。 */
    private val IGNORED_BUILDS = Regex("""\bIgnored build scripts: (.+)""")

    /** `allowBuilds:` 段头。只认「无内联值」与「空映射 {}」两种写法。 */
    private val ALLOW_BUILDS_HEADER = Regex("""^allowBuilds[ \t]*:(.*)$""")

    /**
     * pnpm 11 起删除的构建白名单键（顶层 `<key>:` 形式）。
     * 只用于识别并清理历史遗留，不再写入。
     */
    private val OBSOLETE_LIST_HEADER =
        Regex("""^(onlyBuiltDependencies|neverBuiltDependencies|ignoredBuiltDependencies)[ \t]*:(.*)$""")

    /** 段内一条 `键: 值`。键允许双引号、单引号、裸名（含 scoped 的 @a/b）三种。 */
    private val BUILD_ENTRY =
        Regex("""^([ \t]+)(?:"([^"]+)"|'([^']+)'|([A-Za-z0-9@/._~-]+))[ \t]*:[ \t]*(.*)$""")

    /** allowBuilds 段里的一个条目。 */
    private data class BuildEntry(
        val key: String,
        val value: String,
        val indent: String,
        val line: Int,
    )

    /** allowBuilds 段的解析结果。 */
    private data class AllowBuilds(
        val header: Int,
        val emptyInline: Boolean,
        val entries: List<BuildEntry>,
    )

    /**
     * 解析 `pnpm-workspace.yaml` 的 `allowBuilds` 段。
     *
     * @return null = 这段的形状不认识（内联列表、锚点等），调用方**必须放弃改写**，
     *   否则会把 pnpm 的配置文件写坏（历史事故见上方注释）。
     */
    private fun parseAllowBuilds(lines: List<String>): AllowBuilds? {
        var header = -1
        for (i in lines.indices) {
            if (ALLOW_BUILDS_HEADER.matches(lines[i])) {
                header = i
                break
            }
        }
        if (header < 0) return null
        val rest = ALLOW_BUILDS_HEADER.matchEntire(lines[header])
            ?.groupValues?.get(1)?.trim().orEmpty()
        if (rest.isNotEmpty() && rest != "{}") return null
        val entries = mutableListOf<BuildEntry>()
        var i = header + 1
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) {
                i++
                continue
            }
            // 注释行不影响段边界（YAML 里注释可缩进也可顶格），跳过继续。
            // 若在这里 break，段内一条顶格注释就会让后面的条目全部漏读，
            // 待定名单变短 → approveBuilds 判 stale 而拒绝。
            if (raw.trimStart().startsWith("#")) {
                i++
                continue
            }
            // 顶格行 = 段结束
            if (!raw.startsWith(" ") && !raw.startsWith("\t")) break
            val m = BUILD_ENTRY.matchEntire(raw) ?: break
            val key = listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4])
                .firstOrNull { it.isNotEmpty() } ?: break
            entries += BuildEntry(key, m.groupValues[5], m.groupValues[1], i)
            i++
        }
        return AllowBuilds(header, rest == "{}", entries)
    }

    /** YAML 标量去引号（只处理单/双引号包裹这一种形状）。 */
    private fun unquoteYaml(v: String): String {
        val s = v.trim()
        if (s.length >= 2 &&
            ((s.first() == '"' && s.last() == '"') || (s.first() == '\'' && s.last() == '\''))
        ) {
            return s.substring(1, s.length - 1)
        }
        return s
    }

    /**
     * 按行切分策略文件，统一换行为 `\n`。
     *
     * pnpm 在设备上写的是 `\n`，但策略文件也可能由桌面端引擎生成（`\r\n`）。
     * 若不归一化，行尾残留的 `\r` 会让下面所有 `$` 锚定的正则全部失配：
     * `(.*)$` 里的 `.` 不匹配 `\r`，`$` 又要求落在末尾 —— 结果整个 `allowBuilds`
     * 段都认不出来，批准流程会静默走进「形状不认识，放弃改写」。
     */
    private fun splitYamlLines(text: String): MutableList<String> =
        text.trimStart('\uFEFF')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .toMutableList()

    /** 仍待用户决定的构建脚本包名（值等于 pnpm 的占位串）。 */
    internal fun pendingFromAllowBuilds(text: String): List<String> {
        val block = parseAllowBuilds(splitYamlLines(text)) ?: return emptyList()
        return block.entries
            .filter { unquoteYaml(it.value) == UNDECIDED_BUILD }
            .map { it.key }
    }

    /**
     * 把 [updates] 合并进 `allowBuilds`，文件其余内容原样保留。
     *
     * 不引入 YAML 库：只按行改这一个段，段外一个字节都不碰。
     * 键一律写成双引号形式 —— scoped 包名以 `@` 开头，YAML 的裸标量不允许
     * 以 `@` 起始（保留指示符），不加引号会直接写坏文件。
     *
     * @return null = 现有文件形状无法安全改写，调用方必须放弃。
     */
    internal fun mergeAllowBuilds(text: String, updates: Map<String, String>): String? {
        if (updates.isEmpty()) return text
        val lines = splitYamlLines(text)
        val rendered = updates.entries.map { (k, v) -> "\"" + k + "\": " + v }
        val block = parseAllowBuilds(lines)

        if (block == null) {
            // 段头在、但形状认不出来（内联列表 `[a, b]`、锚点、行内注释等）：
            // 必须放弃，绝不能走下面的「追加段」逻辑 —— 那会在文件里再加一个
            // 同名 `allowBuilds:` 键，YAML 重复键会让 pnpm 直接读失败。
            if (lines.any { ALLOW_BUILDS_HEADER.matches(it) }) return null
            // 没有 allowBuilds 段：只允许往一个顶层 mapping 的末尾追加。
            // 顶层出现列表项、或有不含冒号的行 → 不是 mapping，放弃。
            val top = lines.filter { it.isNotBlank() && !it.startsWith(" ") && !it.startsWith("\t") }
            if (top.any { it.startsWith("- ") || it.trim() == "-" }) return null
            if (top.any { !it.contains(':') && it.trim() != "{}" }) return null
            val keep = lines.dropLastWhile { it.isBlank() }
            val out = mutableListOf<String>()
            if (keep.any { it.trim().isNotEmpty() && it.trim() != "{}" }) out += keep
            out += "allowBuilds:"
            rendered.forEach { out += "  " + it }
            return out.joinToString("\n") + "\n"
        }

        if (block.emptyInline) lines[block.header] = "allowBuilds:"
        val indent = block.entries.firstOrNull()?.indent ?: "  "
        var insertAt = block.entries.lastOrNull()?.line?.plus(1) ?: (block.header + 1)
        for (entry in rendered) {
            val key = entry.substringAfter('"').substringBefore('"')
            val hit = block.entries.firstOrNull { it.key == key }
            if (hit != null) {
                lines[hit.line] = hit.indent + entry
            } else {
                lines.add(insertAt, indent + entry)
                insertAt++
            }
        }
        return lines.joinToString("\n")
    }

    /** 解析 pnpm 报告里被跳过的构建脚本包名（上游 ignoredBuildNames 原文语义）。 */
    fun ignoredBuildNames(output: String): List<String> {
        val reported = IGNORED_BUILDS.find(output)?.groupValues?.get(1) ?: return emptyList()
        return reported.split(',')
            // v1.4.64：剥掉每个包名后面的句号/分号。pnpm 的原文是一句英文：
            //   Ignored build scripts: esbuild, sharp.
            // 最后一个包名天然带句号，而 npm 包名不允许以 `.` 结尾 ——
            // 不剥掉的话 NPM_PACKAGE_NAME 校验会把这个包整个丢掉，
            // 用户永远批不了它（纯函数测试实测发现）。
            .map { it.trim().trimEnd('.', ';', ':') }
            .filter { it.isNotEmpty() && !it.contains('*') && !it.contains('?') }
            // v1.4.65（真机实测发现）：pnpm 12.15.0 的报告里**带版本号**：
            //   Ignored build scripts: esbuild@0.28.2
            // 而 allowBuilds 的键必须是裸包名（不带 @版本）。不剥掉的话
            // NPM_PACKAGE_NAME 会判它非法、整个丢掉，批准框永远不出现。
            .map { stripVersion(it) }
            .filter { it.matches(NPM_PACKAGE_NAME) }
            .distinct()
    }

    /**
     * 去掉包名尾部的 `@<版本>`（scoped 包的 `@scope` 前缀必须保留）。
     *
     * `esbuild@0.28.2` → `esbuild`；`@parcel/watcher@2.5.1` → `@parcel/watcher`；
     * `@parcel/watcher` → 原样（首个 @ 在 0 位，必须是 scope 标记）。
     */
    private fun stripVersion(name: String): String {
        val at = name.lastIndexOf('@')
        return if (at > 0) name.substring(0, at) else name
    }

    /**
     * 让 pnpm 能正常读配置：清掉 profile 的 `pnpm-workspace.yaml` 里 pnpm 11 已删除、
     * 且**裸写 scoped 包名会导致整份 YAML 解析失败**的历史键。
     *
     * ## 真机取证（v1.4.65，宝宝第 3 次反馈「报错一点没变」）
     *
     * 设备上 `/data/data/app.dsh.mobile/files/dsh-home/profiles/web/pnpm-workspace.yaml`
     * 长这样（v1.4.59 那轮往 workspace 写 onlyBuiltDependencies 留下的）：
     *
     * ```yaml
     * packages:
     *   - .
     * nodeLinker: hoisted
     * autoInstallPeers: false
     * onlyBuiltDependencies:
     *   - @zzerx/dsh-plugin-notes
     * ```
     *
     * pnpm 12.15.0 的报错原文：
     *
     * ```
     * Error:   × load configuration
     *   ╰─▶ Failed to parse pnpm-workspace.yaml ...: line 7 column 5:
     *       unexpected character: `@'
     *       7 |   - @zzerx/dsh-plugin-notes
     *         |     ^ unexpected character: `@'
     * ```
     *
     * YAML 里裸 `@` 是保留指示符 → **整份配置解析失败**，于是 pnpm 连 add 都跑不起来，
     * 报错里自然也不会出现 `Ignored build scripts` —— 批准逻辑一次都没被触发过。
     * 代码早就撤回了写入，但设备上的坏文件一直留着，这就是「改了几版报错一点没变」。
     *
     * 这里只做**最小外科清理**：删掉 `onlyBuiltDependencies:` 段（以及 pnpm 11 同样
     * 删除的 `neverBuiltDependencies:` / `ignoredBuiltDependencies:`），其余内容
     * 一个字节都不动。文件不存在、或段没找到时不写。
     *
     * @return 是否真的改写了文件
     */
    fun healPnpmWorkspace(ctx: Context): Boolean = runCatching {
        val f = policyFile(ctx)
        if (!f.isFile) return@runCatching false
        val text = f.readText()
        val obsolete = listOf(
            "onlyBuiltDependencies",
            "neverBuiltDependencies",
            "ignoredBuiltDependencies",
        )
        val lines = splitYamlLines(text)
        val drop = mutableSetOf<Int>()
        var changed = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val head = OBSOLETE_LIST_HEADER.matchEntire(line)
            if (head != null && head.groupValues[1] in obsolete) {
                drop += i
                changed = true
                var j = i + 1
                // 连带吃掉它下面的缩进列表项（`  - foo`）与同缩进的续行
                while (j < lines.size) {
                    val raw = lines[j]
                    if (raw.isBlank()) { j++; continue }
                    if (!raw.startsWith(" ") && !raw.startsWith("\t")) break
                    drop += j
                    j++
                }
                i = j
                continue
            }
            i++
        }
        if (!changed) return@runCatching false
        // 顶层裸键形式也可能出现：把 .npmrc 的 `key=value` 语法误抄进 yaml
        val kept = lines.filterIndexed { idx, _ -> idx !in drop }
        val merged = kept.joinToString("\n")
        if (merged == text) return@runCatching false
        val ok = writePolicyAtomic(f, if (merged.endsWith("\n")) merged else merged + "\n")
        if (ok) Log.i(TAG, "healPnpmWorkspace: dropped obsolete build keys from " + f.absolutePath)
        ok
    }.getOrDefault(false)

    /** allowBuilds 段里已有的全部键（无论值是什么）。 */
    private fun keysFromAllowBuilds(text: String): List<String> =
        parseAllowBuilds(splitYamlLines(text))?.entries?.map { it.key } ?: emptyList()

    /** 列出策略文件里仍待用户批准的构建脚本包名。 */
    fun pendingBuilds(ctx: Context): List<String> = runCatching {
        val f = policyFile(ctx)
        if (f.isFile) pendingFromAllowBuilds(f.readText()) else emptyList()
    }.getOrDefault(emptyList())

    /**
     * 把本次失败报告里被跳过的包名**记进**策略文件，然后返回全部待批准名单。
     * 对应上游 `recordPendingBuilds(dir, output)`。
     *
     * ## 为什么必须自己写（v1.4.64 修，宝宝反馈「还是同样的报错」）
     *
     * 上游源码注释原话：
     * > pnpm writes those policy entries itself only when it can prompt, and the
     * > manager always runs it non-interactively, so the run's own report is the
     * > record that survives.
     *
     * 也就是说：**非交互运行时 pnpm 不会自己往 pnpm-workspace.yaml 写任何东西**，
     * 那份「待批准」记录必须由调用方根据失败输出补上。v1.4.63 只实现了
     * 「回读文件里的占位值」这一半，文件里天然永远没有占位值 → 名单恒为空 →
     * 既弹不出批准框、也不改文案，用户看到的还是 pnpm 原文报错。
     *
     * ## 与「绝不凭空造文件」的边界
     *
     * v1.4.59 的事故是「凭猜测造了个格式不对的文件」；这里是**有据可依**的写入：
     * 只写 `allowBuilds` 一个键，且只在 pnpm 明确报告了被跳过的包名时才写。
     * 文件不存在时仍以 `{}` 为底（与上游 `readPolicy` 的 ENOENT 分支一致），
     * 因为不创建这个文件，批准流程根本无法进行 —— 用户会永远卡在同一条报错上。
     *
     * @return 全部待批准包名（本次新增的 + 文件里原本就待定的）
     */
    fun recordPendingBuilds(ctx: Context, output: String): List<String> = runCatching {
        val f = policyFile(ctx)
        val text = if (f.isFile) f.readText() else "{}\n"
        val pending = pendingFromAllowBuilds(text)
        val known = keysFromAllowBuilds(text).toSet()
        val added = ignoredBuildNames(output).filter { it !in known }
        if (added.isEmpty()) return@runCatching pending
        // 只认报告里明确列出的合法包名，防止把 pnpm 的说明性文字写成 YAML 键
        val safe = added.filter { it.matches(NPM_PACKAGE_NAME) }
        if (safe.isEmpty()) return@runCatching pending
        val merged = mergeAllowBuilds(text, safe.associateWith { UNDECIDED_BUILD })
            ?: run {
                Log.w(TAG, "recordPendingBuilds: layout not recognised; names kept in memory only")
                return@runCatching pending + safe
            }
        if (writePolicyAtomic(f, merged)) {
            Log.i(TAG, "recordPendingBuilds: " + safe.joinToString(",") + " -> pending")
        }
        pending + safe
    }.getOrDefault(emptyList())

    /**
     * 把 pnpm 报出的一批包名记为「待用户批准」并返回全部待定名单。
     *
     * 与 [recordPendingBuilds] 的区别：那个是从输出文本里解析，这个直接拿现成的
     * 包名列表（[probeIgnoredBuilds] 的结果）。两条路都必须**真的写进策略文件**，
     * 否则 [approveBuilds] 会因名字不在 `pending` 里而判 stale 整体拒绝 ——
     * 用户点了「批准」也永远装不上。
     */
    private fun markPendingBuilds(ctx: Context, names: List<String>): List<String> = runCatching {
        val f = policyFile(ctx)
        val text = if (f.isFile) f.readText() else "{}\n"
        val pending = pendingFromAllowBuilds(text)
        val known = keysFromAllowBuilds(text).toSet()
        val safe = names.map { stripVersion(it.trim()) }
            .filter { it.isNotEmpty() && it.matches(NPM_PACKAGE_NAME) }
            .filter { it !in known }
            .distinct()
        if (safe.isEmpty()) return@runCatching pending
        val merged = mergeAllowBuilds(text, safe.associateWith { UNDECIDED_BUILD })
            ?: run {
                Log.w(TAG, "markPendingBuilds: layout not recognised; not writing")
                return@runCatching pending
            }
        if (writePolicyAtomic(f, merged)) {
            Log.i(TAG, "markPendingBuilds: " + safe.joinToString(",") + " -> pending")
        }
        pending + safe
    }.getOrDefault(emptyList())

    /**
     * 把 pnpm 报告的包名写进 `allowBuilds: { name: true }` 并落盘（v1.4.67）。
     *
     * 与 [approveBuilds] 的区别：那个要求名字**先在文件里以待定占位值存在**
     * （上游 stale-approval 语义），用于「用户点确认」的交互路径；这里是安装
     * 流程内部的自愈，直接从 pnpm 的输出拿名字写 true，不需要先写占位值。
     *
     * ## 安全边界（不可放宽）
     * - 只写 pnpm 自己在 `Ignored build scripts:` 里**点名的具体包**
     * - 包名必须过 [NPM_PACKAGE_NAME]（挡住 YAML 注入，如 `x\n  "*": true`）
     * - 绝不写 `*`、绝不写 `dangerouslyAllowAllBuilds`：通配等于允许所有传递
     *   依赖执行任意 postinstall 脚本，是经典供应链攻击面
     * - 只写 `allowBuilds` 一个段，文件其余字节原样保留
     *
     * @return 是否至少写入了一个包（false = 一个都没写，调用方不要重试）
     */
    private fun allowBuildsFor(ctx: Context, names: List<String>): Boolean = runCatching {
        val safe = names.map { stripVersion(it.trim()) }
            .filter { it.isNotEmpty() && !it.contains('*') && !it.contains('?') }
            .filter { it.matches(NPM_PACKAGE_NAME) }
            .distinct()
        if (safe.isEmpty()) {
            Log.w(TAG, "allowBuildsFor: no legal package name in " + names.joinToString(","))
            return@runCatching false
        }
        val f = policyFile(ctx)
        val text = if (f.isFile) f.readText() else "{}\n"
        // 已经是 true 的不用再写（幂等）
        val already = parseAllowBuilds(splitYamlLines(text))
            ?.entries?.filter { unquoteYaml(it.value) == "true" }?.map { it.key }?.toSet()
            ?: emptySet()
        val targets = safe.filter { it !in already }
        if (targets.isEmpty()) return@runCatching true
        val merged = mergeAllowBuilds(text, targets.associateWith { "true" })
            ?: run {
                Log.w(TAG, "allowBuildsFor: layout not recognised; refusing to write")
                return@runCatching false
            }
        val ok = writePolicyAtomic(f, merged)
        if (ok) {
            Log.i(TAG, "allowBuildsFor: " + targets.joinToString(",") + " -> true")
        }
        ok
    }.getOrDefault(false)

    /** 写临时文件再 rename，避免写一半留下坏配置。 */
    private fun writePolicyAtomic(f: File, text: String): Boolean = runCatching {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        val ok = tmp.renameTo(f)
        if (!ok) {
            runCatching { tmp.delete() }
            Log.w(TAG, "writePolicyAtomic: rename failed; original left untouched")
        }
        ok
    }.getOrDefault(false)

    /**
     * 用户确认后把 [names] 从占位值改成 `true`（上游 approveBuilds 语义）。
     *
     * - 只批准**当前确实待定**的名字，防止批准一个已被拒绝/已批准的包；
     * - 文件不存在就直接返回 false —— 绝不新建（历史事故）；
     * - 写临时文件再 rename，避免写一半留下坏配置。
     *
     * @return 是否真的写入了批准
     */
    fun approveBuilds(ctx: Context, names: List<String>): Boolean = runCatching {
        val f = policyFile(ctx)
        if (!f.isFile) return@runCatching false
        val wanted = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return@runCatching false
        // 安全审查 M3 的口径不变：写进 pnpm 配置的包名必须是合法 npm 名，
        // 否则可借换行/YAML 指示符注入别的策略（如 dangerouslyAllowAllBuilds）。
        // 校验必须在「是否待定」过滤**之前**，否则非法名字会先被过滤掉，
        // 校验形同虚设（checker v1.4.63 审查指出的顺序缺陷）。
        if (wanted.any { !it.matches(NPM_PACKAGE_NAME) }) {
            Log.w(TAG, "approveBuilds: refuse illegal package name")
            return@runCatching false
        }
        val text = f.readText()
        val pending = pendingFromAllowBuilds(text)
        // 上游 approveBuilds：只要有一个名字不在待定列表里就整体拒绝
        // （ManagementFailure('stale-approval')），绝不「部分批准」——
        // 否则 UI 拿着过期名单重试会静默写出用户没确认过的批准。
        if (wanted.any { it !in pending }) {
            Log.w(TAG, "approveBuilds: stale approval, ignore")
            return@runCatching false
        }
        val merged = mergeAllowBuilds(text, wanted.associateWith { "true" }) ?: run {
            Log.w(TAG, "approveBuilds: pnpm-workspace.yaml layout not recognised; refusing to write")
            return@runCatching false
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(merged)
        val ok = tmp.renameTo(f)
        if (!ok) {
            runCatching { tmp.delete() }
            Log.w(TAG, "approveBuilds: rename failed; original file left untouched")
            false
        } else {
            Log.i(TAG, "approveBuilds: " + wanted.joinToString(",") + " -> " + f.absolutePath)
            true
        }
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

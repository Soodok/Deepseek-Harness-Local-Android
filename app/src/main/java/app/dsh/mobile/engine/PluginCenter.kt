package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
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
     * 实时搜索 npm 上的 dsh 社区插件（registry search API，v1.2.114）。
     * 查询自动限定 `keywords:deepseek-harness` 生态；@deepseek-ai/ 官方内部组件
     * （引擎部件，非插件）被排除。主人在插件中心看到的列表即此实时结果。
     * @param query 用户关键词，空 = 全部生态
     * @param onDone 后台线程回调（网络失败返回空列表，UI 层回退内置精选）
     */
    fun search(query: String, onDone: (List<PluginInfo>) -> Unit) {
        Thread({
            val q = buildString {
                append("keywords:deepseek-harness")
                if (query.isNotBlank()) append(" ").append(query.trim())
            }
            val encoded = java.net.URLEncoder.encode(q, "UTF-8")
            val url = "https://registry.npmjs.org/-/v1/search?text=$encoded&size=50"
            val result = runCatching {
                val conn = java.net.URL(url).openConnection()
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val text = conn.getInputStream().bufferedReader().use { it.readText() }
                parseSearch(text)
            }.getOrElse {
                Log.w(TAG, "npm search failed (${it.message}); empty result")
                emptyList()
            }
            onDone(result)
        }, "dsh-plugin-search").apply { isDaemon = true; start() }
    }

    private fun parseSearch(text: String): List<PluginInfo> = runCatching {
        val d = JSONObject(text)
        val arr = d.getJSONArray("objects")
        val out = ArrayList<PluginInfo>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i).getJSONObject("package")
            val name = p.getString("name")
            // 排除官方内部组件：它们是引擎的部件而非可装插件
            if (name.startsWith("@deepseek-ai/")) continue
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
     * @param onDone (成功, 详情或错误信息) —— 在后台线程回调
     */
    fun install(ctx: Context, pkg: String, onDone: (Boolean, String) -> Unit) {
        Thread({
            var ok = false
            var msg = "?"
            try {
                val node = EngineConfig.nodeBin(ctx)
                val entry = EngineConfig.dshEntry(ctx)
                val env = EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT)
                val pb = ProcessBuilder(
                    node.absolutePath, "--expose-internals", entry.absolutePath,
                    "plugin", "--profile", "web", "add", pkg,
                )
                pb.environment().clear()
                env.forEach { kv ->
                    val i = kv.indexOf('=')
                    if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
                }
                val p = pb.start()
                val out = p.inputStream.readBytes()
                val err = p.errorStream.readBytes()
                if (p.waitFor(120, TimeUnit.SECONDS)) {
                    if (p.exitValue() == 0) {
                        ok = true
                        msg = String(out, Charsets.UTF_8).trim().take(300)
                    } else {
                        msg = String(err, Charsets.UTF_8).trim().take(300)
                            .ifEmpty { "exit=${p.exitValue()}" }
                    }
                } else {
                    p.destroy()
                    msg = "plugin install timed out (120s)"
                }
            } catch (e: Exception) {
                msg = e.message ?: "install failed"
            }
            Log.i(TAG, "install $pkg -> ok=$ok: $msg")
            onDone(ok, msg)
        }, "dsh-plugin-install").apply { isDaemon = true; start() }
    }
}

package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 插件市场（v1.4.21）—— 取代 v1.2.118 的极简 [PluginCenter.search]。
 *
 * ## 为什么重写
 *
 * 参考 DSHA 的插件中心（开源，DSH-APP/DSHA 的 PluginFragment/PluginRepository），
 * 它把「插件管理」做成了完整的应用商店形态：市场/已安装双标签、富卡片、排序、
 * 下载源切换、链接安装（npm / GitHub 仓库 / Release / 压缩包四种来源实时识别）、
 * 检查更新 / 更新 / 回退、能力声明、可取消进度、批量导出。
 *
 * 我们的加强点（DSHA 没有、Android 移植特有）：
 *  1. **兼容性分级内联标注**——复用 [NativePluginCheck] 的 7 信号分析，卡片上直接
 *     标「纯 JS / 需原生 / 引擎 fork」三态，不用点进详情才知道装不了；
 *  2. **精确版本安装/升级**——pnpm 12 的升级语义坑（详见 [install]）；
 *  3. **体积展示**——registry 的 dist.unpackedSize；
 *  4. **pnpm store 锁自愈**——沿用 [PluginCenter.healPnpmStore]。
 *
 * ## 数据来源分层（网络失败不空屏）
 *  1. npm registry 搜索（`keywords:deepseek-harness` 浏览 / 任意词搜索）；
 *  2. 我们的 community-plugins.json 精选清单（离线兜底 + 编辑推荐标记）。
 */
object PluginMarket {

    private const val TAG = "PluginMarket"

    /** 远程精选清单（我们维护；raw 直读无需鉴权） */
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/Soodok/Deepseek-Harness-Local-Android/main/community-plugins.json"

    private const val CATALOG_ASSET = "market/catalog.json"

    /** 浏览时最多为多少条做联网兼容性探测（每次一个 registry 请求） */
    private const val COMPAT_PROBE_LIMIT = 8

    // ─────────────────────────────────────────────────────────────────────
    // 数据模型
    // ─────────────────────────────────────────────────────────────────────

    /** 兼容性三态（来自 NativePluginCheck 的 7 信号分析，或本地目录未验证） */
    enum class Compat {
        /** 纯 JS，Android 可直接运行 */
        PURE_JS,

        /** 需要原生编译 / 平台不匹配（装了多半不能用） */
        NEEDS_NATIVE,

        /** 整栈引擎 fork（破甲类插件）——官方引擎上设计性不兼容 */
        ENGINE_FORK,

        /** 查不到（离线 / git 源 / 未请求） */
        UNKNOWN,

        /**
         * 插件要求的 dsh 引擎版本比内置的新（issue #66）。
         *
         * 与上面三态的区别：**这不是插件的问题，也不是用户环境的问题**，
         * 而是我们内置的引擎版本落后于 npm `next` 线。npm 上 `@deepseek-ai/dsh`
         * 的 `latest` 停在 0.2.1-rc.2，而 `next` 已到 rc.4；插件作者照着 next 线
         * 写 `engines.dsh`，于是「按 latest 内置引擎」的我们与「按 next 开发」的
         * 插件生态错位。
         *
         * 单独列一态是为了给用户正确的预期：装不上不是插件坏了，升级 App
         * 内置引擎后才能用。
         */
        ENGINE_TOO_OLD,
    }

    /**
     * 市场条目（同时用于「市场」与「已安装」两个标签）。
     *
     * @param pkg npm 包名（`@scope/name`）
     * @param version registry 上的最新版本（市场标签）或已安装版本（已安装标签）
     * @param installedVer 实际落盘版本（已安装标签；null = 未安装）
     * @param unpackedSize 解压后字节数（0 = 未知）
     * @param official 我们清单里的编辑推荐
     * @param compat 兼容性判定；[compatReasons] 是对用户可见的判断依据
     */
    data class Entry(
        val pkg: String,
        val name: String,
        val desc: String = "",
        /** 英文描述（目录里中英双语，按界面语言挑选） */
        val descEn: String = "",
        /** 目录分类 id（ui/tools/session/...，23 类） */
        val category: String = "",
        val version: String = "",
        val installedVer: String? = null,
        val author: String = "",
        val repo: String = "",
        val homepage: String = "",
        val unpackedSize: Long = 0L,
        val updatedAt: Long = 0L,          // 秒或毫秒按来源，统一为毫秒
        val downloadCount: Int = 0,
        val official: Boolean = false,
        val compat: Compat = Compat.UNKNOWN,
        val compatReasons: List<String> = emptyList(),
        val builtin: Boolean = false,      // 引擎自带（不可卸载）
    ) {
        /** 按界面语言挑描述：中文界面优先中文，英文界面优先英文，缺则回退 */
        fun descFor(chineseUi: Boolean): String =
            if (chineseUi) desc.ifBlank { descEn } else descEn.ifBlank { desc }

        val isInstalled: Boolean get() = installedVer != null

        /**
         * npm 上有比已装版本更新的版本。
         * v1.4.52: 用 SemVer 比较替代原来的 `installedVer != version` 字符串
         * 不等判断 —— 市场标签的 version 是构建期内置目录快照，可能**旧于**
         * 已装版本，字符串不等会把这种情况也标成"有更新"，出现
         * 「升级 → 0.3.1（已装 0.3.3）」的降级建议（pet-bridge 实测）。
         * 只有候选版本真正**大于**已装版本才算升级。
         */
        val hasUpdate: Boolean
            get() = isInstalled && version.isNotBlank() &&
                EngineConfig.semverGreater(version, installedVer ?: "")

        /** 卡片副标题：版本 + 体积 + 作者，空字段自动省略 */
        fun metaLine(): String = buildList {
            if (installedVer != null) {
                add(if (hasUpdate) "v$installedVer → v$version" else "v$installedVer")
            } else if (version.isNotBlank()) {
                add("v$version")
            }
            if (unpackedSize > 0) add(humanSize(unpackedSize))
            if (author.isNotBlank()) add(author)
        }.joinToString(" · ")
    }

    /**
     * 排序模式（对齐 DSHA 的四种 + 我们加体积）。
     * 标签不放这里——项目 i18n 门禁禁止 Kotlin 中文字面量，UI 层按 [labelRes] 取资源。
     */
    enum class Sort(val labelRes: Int) {
        NAME_ASC(app.dsh.mobile.R.string.plugin_sort_name_asc),
        NAME_DESC(app.dsh.mobile.R.string.plugin_sort_name_desc),
        RECENT(app.dsh.mobile.R.string.plugin_sort_recent),
        DOWNLOADS(app.dsh.mobile.R.string.plugin_sort_downloads),
        SIZE(app.dsh.mobile.R.string.plugin_sort_size),
    }

    fun sort(entries: List<Entry>, mode: Sort): List<Entry> = when (mode) {
        Sort.NAME_ASC -> entries.sortedBy { it.name.lowercase() }
        Sort.NAME_DESC -> entries.sortedByDescending { it.name.lowercase() }
        Sort.RECENT -> entries.sortedByDescending { it.updatedAt }
        Sort.DOWNLOADS -> entries.sortedByDescending { it.downloadCount }
        Sort.SIZE -> entries.sortedBy { if (it.unpackedSize == 0L) Long.MAX_VALUE else it.unpackedSize }
    }

    /** 过滤：关键词（名称/描述/包名）+ 仅看已安装 + 隐藏内置 */
    fun filter(
        entries: List<Entry>,
        query: String,
        onlyInstalled: Boolean = false,
        hideBuiltin: Boolean = false,
    ): List<Entry> {
        val q = query.trim().lowercase()
        return entries.filter { e ->
            if (onlyInstalled && !e.isInstalled) return@filter false
            if (hideBuiltin && e.builtin) return@filter false
            q.isEmpty() ||
                e.pkg.lowercase().contains(q) ||
                e.name.lowercase().contains(q) ||
                e.desc.lowercase().contains(q)
        }
    }

    fun humanSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%.2f GB", bytes / 1073741824.0)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 市场搜索
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 搜索/浏览市场。
     *
     * 空查询 = 浏览 dsh 生态（`keywords:deepseek-harness`）并标注精选；
     * 有查询词 = 搜全网 npm（用户想装什么就搜什么）。
     *
     * @param onDone 后台线程回调；(结果, 失败原因)；失败原因非空时 UI 回退精选清单
     */
    /**
     * 浏览/搜索内置目录（v1.4.24 重做，参考 dsh-market）。
     *
     * 与旧版的区别：不再依赖 npm 搜索 API（返回信息太贫瘠且国内常超时），
     * 直接读内置的 3540 条目录——离线可用、带分类与双语描述。搜索在本地做
     * 子串匹配（包名/标题/中英描述），零网络延迟。
     *
     * @param category 分类 id；空 = 全部
     * @param checkCompat true 时才联网补兼容性判定（逐包查 registry，较慢）
     */
    fun browse(
        ctx: Context?,
        query: String,
        category: String,
        installed: Map<String, String>,
        checkCompat: Boolean,
        onDone: (List<Entry>, String?) -> Unit,
    ) {
        Thread({
            val all = fetchCatalog(ctx)
            if (all.isEmpty()) {
                onDone(emptyList(), "catalog unavailable")
                return@Thread
            }
            val q = query.trim().lowercase()
            val filtered = all.asSequence()
                .filter { category.isBlank() || it.category == category }
                .filter { e ->
                    q.isEmpty() ||
                        e.pkg.lowercase().contains(q) ||
                        e.name.lowercase().contains(q) ||
                        e.desc.lowercase().contains(q) ||
                        e.descEn.lowercase().contains(q)
                }
                .map { it.copy(installedVer = installed[it.pkg]) }
                .toList()
            // v1.4.52: 内置目录的 version 是 APK 构建期的收录快照，随时间过期。
            // 已装条目数量少（通常个位数），逐个查镜像 latest 覆盖快照版本，
            // 让市场页的升级建议与「引擎实际能装到什么」同源同新 —— 否则
            // 快照 0.3.1 / registry 0.3.3 时，新装装到旧版、升级建议也错位
            // （pet-bridge 实测）。查不到/网络失败保留快照（hasUpdate 已改
            // SemVer 比较，不会因此出现降级建议）。
            val refreshed = filtered.map { e ->
                val iv = e.installedVer
                if (iv.isNullOrBlank()) return@map e
                val latest = runCatching {
                    httpGet("${EngineConfig.NPM_REGISTRY_MIRROR}/${e.pkg.replace("/", "%2F")}/latest")
                }.getOrNull()?.let { text ->
                    runCatching { org.json.JSONObject(text).optString("version") }.getOrNull()
                }?.takeIf { v -> v.isNotBlank() && !EngineConfig.semverGreater(iv, v) }
                if (latest.isNullOrBlank()) e else e.copy(version = latest)
            }
            // 兼容性判定是网络操作：只对前 N 条做，避免一次浏览发几百个请求。
            // 其余保持 UNKNOWN，用户点进详情时会按需补。
            val withCompat = if (checkCompat) {
                refreshed.take(COMPAT_PROBE_LIMIT).map { attachCompat(it) } + refreshed.drop(COMPAT_PROBE_LIMIT)
            } else refreshed
            onDone(withCompat, null)
        }, "dsh-market-browse").apply { isDaemon = true; start() }
    }

    /** 附加兼容性判定（只对未安装/未知的条目做网络查询；已装的不必再查） */
    private fun attachCompat(e: Entry): Entry {
        if (e.compat != Compat.UNKNOWN) return e
        return when (val r = runCatching { NativePluginCheck.check(e.pkg) }.getOrNull()) {
            is NativePluginCheck.Result.PureJs -> e.copy(compat = Compat.PURE_JS)
            is NativePluginCheck.Result.Risk -> {
                val (compat, _) = classifyRisk(r.reasons)
                e.copy(compat = compat, compatReasons = r.reasons)
            }
            is NativePluginCheck.Result.Unknown -> e.copy(
                compat = Compat.UNKNOWN,
                compatReasons = listOf(r.reason),
            )
            is NativePluginCheck.Result.EngineTooOld -> e.copy(
                compat = Compat.ENGINE_TOO_OLD,
                compatReasons = listOf("requires dsh >=${r.required} (bundled ${r.bundled})"),
            )
            null -> e
        }
    }

    /**
     * Risk 细分：NativePluginCheck 的 reasons 里同时含原生模块信号与引擎 fork 信号。
     * fork 类插件（@xxx/dsh-system-prompt 等）在官方引擎上**设计性**不可用，
     * 与「原生模块编译不出来」是两种不同的坏法，分开标注才能给出正确建议。
     */
    internal fun classifyRisk(reasons: List<String>): Pair<Compat, List<String>> {
        // NativePluginCheck 的 reasons 是英文（"fork of the engine" / "engine component"）。
        // 不用中文字面量：i18n 门禁禁止，且用户语言变化不影响判定。
        val isFork = reasons.any {
            it.contains("fork", ignoreCase = true) ||
                it.contains("engine component", ignoreCase = true) ||
                it.contains("engine core", ignoreCase = true)
        }
        return (if (isFork) Compat.ENGINE_FORK else Compat.NEEDS_NATIVE) to reasons
    }

    private fun parseSearch(
        text: String,
        browse: Boolean,
        officialPkgs: Set<String>,
        installed: Map<String, String>,
    ): List<Entry> = runCatching {
        val arr = JSONObject(text).getJSONArray("objects")
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = o.getJSONObject("package")
            val pkg = p.getString("name")
            // 浏览态排除官方内部组件（引擎部件，不是可装插件）；显式搜索不排除
            if (browse && pkg.startsWith("@deepseek-ai/")) continue
            val links = p.optJSONObject("links")
            val publisher = p.optJSONObject("publisher")
            out.add(
                Entry(
                    pkg = pkg,
                    name = pkg.substringAfterLast('/'),
                    desc = p.optString("description", ""),
                    version = p.optString("version", ""),
                    installedVer = installed[pkg],
                    author = publisher?.optString("username", "") ?: "",
                    repo = links?.optString("repository", "") ?: "",
                    updatedAt = parseIso(p.optString("date", "")),
                    official = pkg in officialPkgs,
                    compat = Compat.UNKNOWN,
                ),
            )
        }
        out
    }.getOrDefault(emptyList())

    /** 把精选清单里没被搜索命中的条目补进来（离线可用 + 编辑推荐） */
    private fun mergeCatalog(
        remote: List<Entry>,
        catalog: List<Entry>,
        installed: Map<String, String>,
    ): List<Entry> {
        val seen = remote.map { it.pkg }.toMutableSet()
        val out = remote.toMutableList()
        for (c in catalog) {
            if (seen.add(c.pkg)) out.add(c.copy(installedVer = installed[c.pkg]))
        }
        return out
    }

    /**
     * 读取内置插件目录（dsh-market 的 dsh-plugin-catalog，format 1）。
     *
     * ## 为什么用内置而不是实时搜 npm
     *
     * npm 搜索只返回包名 + 一句话，没有分类、没有中文描述、没有版本历史。而
     * dsh-market（8.8k star 的官方级项目）维护的目录有 **3540 个插件、23 个分类、
     * 中英双语描述**，且每条都带仓库与主页。整份目录压缩后约 1.7MB，内置进 APK
     * 换来「离线可浏览 + 分类筛选 + 双语」，比实时搜索值得。
     *
     * 目录随版本更新（构建时从 npm 的 dsh-plugin-catalog 取最新版）。
     */
    /**
     * 内置目录的 Entry 列表缓存（v1.4.28，issue #58）。
     * 3540 条 Entry 的构建（含 JSON 解析）约 100~200ms；频繁切换分类时反复构建
     * 会叠加成主线程卡顿。目录在进程生命周期内不变，缓存即可。
     */
    @Volatile
    private var catalogCache: List<Entry>? = null

    private fun fetchCatalog(ctx: Context?): List<Entry> {
        catalogCache?.let { return it }
        return buildCatalog(ctx).also { if (it.isNotEmpty()) catalogCache = it }
    }

    private fun buildCatalog(ctx: Context?): List<Entry> = runCatching {
        val text = ctx?.assets?.open(CATALOG_ASSET)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return emptyList()
        val root = JSONObject(text)
        val arr = root.getJSONArray("plugins")
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val descObj = o.optJSONObject("desc")
            out.add(
                Entry(
                    pkg = o.getString("name"),
                    name = o.optString("title", o.getString("name").substringAfterLast('/')),
                    desc = descObj?.optString("zh", "") ?: "",
                    descEn = descObj?.optString("en", "") ?: "",
                    category = o.optString("cat", ""),
                    version = o.optString("ver", ""),
                    repo = o.optString("repo", ""),
                    homepage = o.optString("home", ""),
                    updatedAt = o.optLong("ts", 0L) * 1000L,
                ),
            )
        }
        out
    }.getOrElse {
        Log.w(TAG, "bundled catalog read failed: ${it.message}")
        emptyList()
    }

    /**
     * 目录分类缓存（v1.4.28，issue #58）。
     *
     * 目录 1.7MB，解析一次约几十毫秒。旧实现每次调用都重新读+解析，而
     * PluginStoreActivity.refreshChipStyles() 在**每个 chip 的循环里**调用它
     * ——24 个分类 = 24 次全量解析，主线程卡死数秒，触发引擎健康看门狗重启。
     * 分类列表在进程生命周期内不变，缓存即可。
     */
    @Volatile
    private var categoriesCache: Map<String, List<Pair<String, String>>>? = null

    /** 目录里的分类（id → 当前语言的标题），供筛选器使用；按语言缓存 */
    fun categories(ctx: Context): List<Pair<String, String>> = runCatching {
        val lang = java.util.Locale.getDefault().language
        categoriesCache?.get(lang)?.let { return it }
        val text = ctx.assets.open(CATALOG_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val cats = JSONObject(text).getJSONObject("metadata").getJSONObject("categories")
        val chinese = java.util.Locale.getDefault().language == "zh"
        cats.keys().asSequence().map { id ->
            val t = cats.getJSONObject(id).getJSONObject("title")
            id to (if (chinese) t.optString("zh", id) else t.optString("en", id))
        }.toList().also { list ->
            categoriesCache = (categoriesCache ?: emptyMap()) + (lang to list)
        }
    }.getOrElse { emptyList() }

    // ─────────────────────────────────────────────────────────────────────
    // 单包详情（点进详情时按需拉，避免列表阶段 100 个请求）
    // ─────────────────────────────────────────────────────────────────────

    /** registry 单包完整 manifest（含 unpackedSize / 全部版本 / maintainers） */
    fun detail(pkg: String, onDone: (Entry?, String?) -> Unit) {
        Thread({
            val r = runCatching {
                val text = httpGet("https://registry.npmjs.org/${pkg.replace("/", "%2F")}")
                val o = JSONObject(text)
                val latest = o.optJSONObject("dist-tags")?.optString("latest", "") ?: ""
                val versions = o.optJSONObject("versions")
                val vobj = versions?.optJSONObject(latest)
                val dist = vobj?.optJSONObject("dist")
                val times = o.optJSONObject("time")
                val maintainers = o.optJSONArray("maintainers")
                Entry(
                    pkg = pkg,
                    name = pkg.substringAfterLast('/'),
                    desc = o.optString("description", ""),
                    version = latest,
                    author = maintainers?.optJSONObject(0)?.optString("name", "") ?: "",
                    repo = o.optJSONObject("repository")?.optString("url", "")
                        ?: vobj?.optJSONObject("repository")?.optString("url", "") ?: "",
                    unpackedSize = runCatching {
                        o.optJSONObject("_attachments")?.let { 0L }
                        dist?.optLong("unpackedSize", 0L) ?: 0L
                    }.getOrDefault(0L),
                    updatedAt = parseIso(times?.optString(latest, "") ?: ""),
                )
            }
            if (r.isSuccess) {
                val compat = attachCompat(r.getOrThrow())
                onDone(compat, null)
            } else {
                onDone(null, r.exceptionOrNull()?.message)
            }
        }, "dsh-market-detail").apply { isDaemon = true; start() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 已安装清单（读 profile，含实际版本）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 已安装插件清单：包名 → 实际落盘版本。同时补上 registry 最新版本以判断可升级。
     *
     * @param checkUpdates true 时逐个查 registry 的最新版本（网络；用于「检查更新」）
     */
    fun installed(
        ctx: Context,
        checkUpdates: Boolean,
        onDone: (List<Entry>, String?) -> Unit,
    ) {
        Thread({
            val versions = PluginCenter.installedVersions(ctx)
            val errs = mutableListOf<String>()
            val out = versions.map { (pkg, ver) ->
                var latest = ""
                var desc = ""
                var size = 0L
                if (checkUpdates) {
                    runCatching {
                        // v1.4.52: 查镜像而非官方 registry —— 引擎的 pnpm 从镜像装
                        // （buildEnv 注入 npm_config_registry），建议必须与「实际能
                        // 装到什么」同源，否则镜像滞后时会显示永远装不上的更新建议。
                        val text = httpGet(
                            "${EngineConfig.NPM_REGISTRY_MIRROR}/${pkg.replace("/", "%2F")}/latest",
                        )
                        val o = JSONObject(text)
                        latest = o.optString("version", "")
                        desc = o.optString("description", "")
                        size = o.optJSONObject("dist")?.optLong("unpackedSize", 0L) ?: 0L
                    }.onFailure { errs += "${pkg}: ${it.message}" }
                }
                Entry(
                    pkg = pkg,
                    name = pkg.substringAfterLast('/'),
                    desc = desc,
                    version = latest.ifBlank { ver },
                    installedVer = ver,
                    unpackedSize = size,
                )
            }.sortedBy { it.name.lowercase() }
            onDone(out, errs.takeIf { it.isNotEmpty() }?.joinToString("; "))
        }, "dsh-market-installed").apply { isDaemon = true; start() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 网络
    // ─────────────────────────────────────────────────────────────────────

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "dsh-android-plugin-market")
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseIso(s: String): Long = runCatching {
        if (s.isBlank()) 0L
        else java.time.Instant.parse(s).toEpochMilli()
    }.getOrDefault(0L)
}

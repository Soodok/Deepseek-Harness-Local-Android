package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.dsh.mobile.engine.PluginCenter
import app.dsh.mobile.engine.PluginKillSwitch
import app.dsh.mobile.engine.PluginMarket
import app.dsh.mobile.engine.PluginMarket.Compat
import app.dsh.mobile.engine.PluginMarket.Entry

/**
 * 插件中心（v1.4.21 重写）。
 *
 * ## 为什么要重写
 *
 * 旧版（v1.2.117–v1.4.20）只有一个 npm 搜索列表：没有「已安装」视图，看不到
 * 版本/体积/作者，不能排序、不能检查更新、点进去也没有详情——社区反馈「太简单」。
 * 参考开源项目 DSHA（DSH-APP/DSHA）的插件中心形态后重做：
 *
 *  · **双标签**：市场（浏览/搜索/安装）· 已安装（管理/更新/卸载）；
 *  · **富卡片**：「推荐」角标、名称、版本（可升级时显示 `v1 → v2`）、体积、
 *    作者、兼容性分级徽标（纯 JS / 需原生 / 引擎 fork / 未验证）、描述、
 *    以及升级时的高亮操作按钮；
 *  · **排序**：名称 / 最近更新 / 下载量 / 体积；
 *  · **检查更新**：一次性查全部已装插件的最新版本，汇总提示；
 *  · **详情弹窗**：完整描述、仓库、体积、兼容性判断依据（为什么装不了）；
 *  · 输入即搜（300ms 防抖）、下拉刷新按钮、空态与错误态提示。
 *
 * ## 保留的已验证行为（不要回退）
 *  - 装/卸串行化（[PluginCenter] 单线程执行器）；
 *  - **精确版本**安装与升级（pnpm 12 下 `add pkg`/`@latest`/`update` 全部无效，
 *    只有 `add pkg@<精确版本>` 会真正换版本——v1.4.18 实测）；
 *  - 装前原生模块预检 + 风险确认（[NativePluginCheck]）；
 *  - 按钮状态防抖与视图存活检查（预检期间可能已离开页面）。
 */
class PluginStoreActivity : ThemedActivity() {

    /** 与其余页面一致，按应用内语言偏好包装 Context */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var container: LinearLayout
    private lateinit var listScroll: android.widget.ScrollView
    private lateinit var tvSubtitle: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageView
    private lateinit var btnTabMarket: TextView
    private lateinit var btnTabInstalled: TextView
    private lateinit var btnSort: TextView
    private lateinit var btnCheckUpdates: TextView
    private val dp by lazy { resources.displayMetrics.density }

    /** 当前标签：true = 市场，false = 已安装 */
    private var onMarket = true

    /** 市场结果 / 已安装清单（两份数据分开缓存，切标签不重拉） */
    private var marketEntries: List<Entry> = emptyList()
    private var installedEntries: List<Entry> = emptyList()

    /** 已安装实际版本（升级判断的唯一事实来源） */
    private var installedVer: Map<String, String> = emptyMap()

    private var sortMode = PluginMarket.Sort.NAME_ASC

    /** 当前分类筛选（空 = 全部）；目录来自 dsh-market 的 23 个分类 */
    private var selectedCategory: String = ""

    private lateinit var categoryChips: LinearLayout
    private var loading = false
    private var lastError: String? = null

    /** 增量渲染状态（v1.4.30，issue #62）：已渲染行数 / 待追加的剩余结果 / 滚动监听是否已挂 */
    private var shownRows = 0
    private var pendingRows: List<Entry> = emptyList()
    private var loadMoreHooked = false

    /** 当前显示的排序/筛选状态是否已应用到列表（避免每次重渲染都重排） */
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val searchRun = Runnable { reload(verbose = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyTo(this)
        setContentView(R.layout.activity_plugin_store)
        findViewById<MaxWidthLinearLayout>(R.id.pluginStoreBody).maxWidthDp =
            STORE_CONTENT_MAX_WIDTH_DP

        container = findViewById(R.id.listContainer)
        listScroll = findViewById(R.id.listScroll)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        etSearch = findViewById(R.id.etSearch)
        btnTabMarket = findViewById(R.id.btnTabMarket)
        btnTabInstalled = findViewById(R.id.btnTabInstalled)
        btnSort = findViewById(R.id.btnSort)
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        btnSort.text = getString(sortMode.labelRes)
        categoryChips = findViewById(R.id.categoryChips)
        btnTabMarket.setOnClickListener { switchTab(true) }
        btnTabInstalled.setOnClickListener { switchTab(false) }
        btnSort.setOnClickListener { showSortDialog() }
        btnCheckUpdates.setOnClickListener { checkUpdates() }

        // v1.4.27：应急总开关生效时的常驻提示（点一下直接恢复）
        val killBanner = findViewById<TextView>(R.id.tvKillBanner)
        killBanner.setOnClickListener {
            val outcome = PluginKillSwitch.requestEnable(this)
            if (!outcome.ok) {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.plugin_kill_failed, outcome.message),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }
            android.widget.Toast.makeText(
                this, getString(R.string.plugin_kill_restarting), android.widget.Toast.LENGTH_SHORT,
            ).show()
            (application as DshApp).supervisor.restart()
            refreshKillBanner()
        }
        refreshKillBanner()

        btnClear = findViewById(R.id.btnClear)
        btnClear.setOnClickListener {
            etSearch.setText("")
            btnClear.visibility = View.GONE
            hideKeyboard()
            reload(verbose = false)
        }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                reload(verbose = false)
            }
            true
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                btnClear.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                searchHandler.removeCallbacks(searchRun)
                searchHandler.postDelayed(searchRun, 300)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // 从桌宠设置页跳过来时预填搜索词（v1.4.19，如 dsh-pet-bridge）
        val initialQuery = intent.getStringExtra(EXTRA_QUERY).orEmpty().trim()
        if (initialQuery.isNotBlank()) {
            etSearch.setText(initialQuery)
            etSearch.setSelection(initialQuery.length)
        }
        buildCategoryChips()
        reload(verbose = true)
    }

    /** 构建分类筛选条：「全部」+ 目录里的 23 个分类 */
    private fun buildCategoryChips() {
        categoryChips.removeAllViews()
        val cats = PluginMarket.categories(this)
        val items = listOf("" to getString(R.string.plugin_category_all)) +
            cats.map { it.first to it.second }
        chipIds = items.map { it.first }
        val d: (Int) -> Int = { dp(it) }
        items.forEach { (id, label) ->
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(d(12), d(6), d(12), d(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = d(8) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (selectedCategory == id) return@setOnClickListener
                    selectedCategory = id
                    refreshChipStyles()
                    // v1.4.28（issue #58）：分类切换只在**本地**重过滤，不再走
                    // reload(verbose=true) 重新读目录——连点会堆积后台线程 + 反复
                    // 解析 1.7MB 目录，主线程被拖住后健康看门狗误判引擎失联并重启。
                    rebuildChipsForCategory()
                }
            }
            categoryChips.addView(tv)
        }
        refreshChipStyles()
    }

    /** 分类 id 列表（与 chip 顺序一致：第 0 个是「全部」= 空串） */
    private var chipIds: List<String> = emptyList()

    /**
     * 分类切换后的本地重过滤（v1.4.28，issue #58）。
     *
     * 目录与分类都已缓存，这里全部在内存里完成：过滤 → 排序 → 渲染。
     * 不碰网络、不读 assets，连点也不会堆积。
     */
    private fun rebuildChipsForCategory() {
        if (marketEntries.isEmpty()) { reload(verbose = true); return }
        render()
    }

    /** 选中的分类用实心强调色，其余描边 */
    private fun refreshChipStyles() {
        val accent = Accent.current(this)
        for (i in 0 until categoryChips.childCount) {
            val tv = categoryChips.getChildAt(i) as? TextView ?: continue
            val id = chipIds.getOrElse(i) { "" }
            if (id == selectedCategory) {
                tv.background = accent.filledDrawable()
                tv.setTextColor(0xFFFFFFFF.toInt())
            } else {
                tv.background = accent.outlinedDrawable()
                tv.setTextColor(accent.argb)
            }
        }
    }

    private fun dp(v: Int): Int = (v * dp).toInt()

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    // ───────────────────────────────────────────────────────────────────
    // 数据加载
    // ───────────────────────────────────────────────────────────────────

    /**
     * 重载当前标签。
     * @param verbose true 时总是重新拉网络（下拉/切标签/首次进入）；
     *                false 时若结果已在内存则只重渲染（输入防抖不能每次都发请求）
     */
    private fun reload(verbose: Boolean) {
        installedVer = runCatching { PluginCenter.installedVersions(this) }.getOrDefault(emptyMap())
        if (onMarket) loadMarket(verbose) else loadInstalled(verbose)
    }

    private fun loadMarket(force: Boolean) {
        val query = etSearch.text.toString().trim()
        if (!force && query.isBlank() && marketEntries.isNotEmpty()) {
            render()
            return
        }
        loading = true
        lastError = null
        showLoadingState()
        PluginMarket.browse(this, query, "", installedVer, checkCompat = false) { entries, err ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                lastError = err
                marketEntries = entries
                render()
            }
        }
    }

    private fun loadInstalled(force: Boolean) {
        if (!force && installedEntries.isNotEmpty()) {
            render()
            return
        }
        loading = true
        lastError = null
        showLoadingState()
        PluginMarket.installed(this, checkUpdates = true) { entries, err ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                lastError = err
                installedEntries = entries
                render()
                reportUpdates(entries)
            }
        }
    }

    private fun showLoadingState() {
        container.removeAllViews()
        tvSubtitle.text = getString(R.string.plugin_searching)
        container.addView(hint(getString(R.string.plugin_searching)))
    }

    private fun switchTab(market: Boolean) {
        if (onMarket == market) return
        onMarket = market
        hideKeyboard()
        etSearch.clearFocus()
        // 标签样式：选中蓝色加粗，未选中灰色
        btnTabMarket.setTextColor(if (market) Accent.current(this@PluginStoreActivity).argb else getColor(R.color.text_muted))
        btnTabMarket.setTypeface(null, if (market) Typeface.BOLD else Typeface.NORMAL)
        btnTabInstalled.setTextColor(if (!market) Accent.current(this@PluginStoreActivity).argb else getColor(R.color.text_muted))
        btnTabInstalled.setTypeface(null, if (!market) Typeface.BOLD else Typeface.NORMAL)
        etSearch.visibility = if (market) View.VISIBLE else View.GONE
        btnCheckUpdates.visibility = if (market) View.GONE else View.VISIBLE
        reload(verbose = true)
        findViewById<View>(R.id.listContainer).post { /* 列表从顶部开始 */ }
    }

    // ───────────────────────────────────────────────────────────────────
    // 渲染
    // ───────────────────────────────────────────────────────────────────

    private fun render() {
        container.removeAllViews()
        shownRows = 0
        pendingRows = emptyList()
        val source = if (onMarket) marketEntries else installedEntries
        // v1.4.28：分类筛选在本地做（目录已缓存），避免每次切分类都重新拉目录
        val byCategory = if (onMarket && selectedCategory.isNotBlank()) {
            source.filter { it.category == selectedCategory }
        } else source
        val filtered = PluginMarket.filter(byCategory, etSearch.text.toString(), onlyInstalled = !onMarket)
        val sorted = PluginMarket.sort(filtered, sortMode)

        tvSubtitle.text = when {
            loading -> getString(R.string.plugin_searching)
            lastError != null && source.isEmpty() -> getString(R.string.plugin_search_fail)
            else -> buildString {
                append(getString(if (onMarket) R.string.plugin_center_sub else R.string.plugin_tab_installed))
                append(" · ")
                append(sorted.size)
            }
        }
        // 失败提示只保留一条：subtitle 已说明搜索失败，列表内不再重复插 hint（实测两条重叠）
        val failed = lastError != null && source.isEmpty()
        if (sorted.isEmpty() && !loading) {
            container.addView(
                hint(
                    when {
                        failed -> getString(R.string.plugin_search_fail)
                        !onMarket -> getString(R.string.plugin_none_installed)
                        etSearch.text.isNotBlank() -> getString(R.string.plugin_search_none)
                        else -> getString(R.string.plugin_empty)
                    }
                )
            )
            return
        }
        // 市场里已安装的排后面（用户更关心可装的），已安装标签按名称排
        val ordered = if (onMarket) sorted.sortedBy { it.isInstalled } else sorted
        // v1.4.30（issue #62）：**绝不能一次性渲染完**。
        // 目录有 3540 个插件，每行约 6 个嵌套 View → 两万多个 View 塞进一个
        // ScrollView（没有视图回收）→ 主线程 OOM/ANR → 进程被杀 → 引擎看门狗
        // 误判「引擎异常退出」并重启。用户感知就是「一搜索就卡崩重启」。
        // 改为首屏渲染 RENDER_PAGE 条，滚到底再追加下一批。
        pendingRows = ordered
        appendNextPage()
        setupLoadMore()
    }

    /**
     * 追加下一批结果。
     *
     * 每批只建 [RENDER_PAGE] 个行视图，把单次主线程工作量压到毫秒级；
     * 剩余条目留在 [pendingRows] 里等滚动触发。
     */
    private fun appendNextPage() {
        if (pendingRows.isEmpty()) return
        val end = minOf(shownRows + RENDER_PAGE, pendingRows.size)
        for (i in shownRows until end) {
            container.addView(pluginRow(pendingRows[i]))
        }
        shownRows = end
        if (shownRows >= pendingRows.size) pendingRows = emptyList()
    }

    /** 滚到接近底部时补充下一批（幂等；重复安装监听器由标志位挡住） */
    private fun setupLoadMore() {
        if (loadMoreHooked) return
        loadMoreHooked = true
        listScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (pendingRows.isEmpty()) return@setOnScrollChangeListener
            val child = listScroll.getChildAt(0) ?: return@setOnScrollChangeListener
            // 距底部不足一屏时追加，避免用户看到明显停顿
            if (child.height - (scrollY + listScroll.height) < listScroll.height) {
                appendNextPage()
            }
        }
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, dp(30), 0, dp(10))
    }

    /** 插件卡片：名称 + 角标 / 元信息行 / 描述 / 兼容性徽标 / 操作按钮 */
    private fun pluginRow(e: Entry): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            isClickable = true
        }

        // ── 第一行：名称 + 「推荐」角标 + 右侧操作按钮 ──
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameCol.addView(TextView(this).apply {
            text = e.name
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (e.official) {
            nameCol.addView(TextView(this).apply {
                text = getString(R.string.plugin_official)
                setTextColor(Accent.current(this@PluginStoreActivity).argb)
                textSize = 10f
                setPadding(dp(6), dp(1), dp(6), dp(1))
                setBackgroundResource(R.drawable.bg_icon_chip_soft)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) }
            })
        }
        headRow.addView(nameCol)

        val btn = actionButton(e)
        headRow.addView(btn)
        // v1.4.28：停用期间给第三方插件一个「单独启用/再停用」白名单入口
        killSwitchChip(e)?.let { headRow.addView(it) }
        card.addView(headRow)

        // ── 第二行：版本 / 体积 / 作者 ──
        e.metaLine().takeIf { it.isNotBlank() }?.let { meta ->
            card.addView(TextView(this).apply {
                text = meta
                setTextColor(if (e.hasUpdate) getColor(R.color.warning) else getColor(R.color.text_secondary))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })
        }

        // ── 第三行：描述 ──
        if (e.desc.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = e.desc
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            })
        }

        // ── 第四行：兼容性徽标（只在非纯 JS 时显示，避免每行都加噪音）──
        compatBadge(e)?.let { card.addView(it) }

        // 点卡片 = 详情
        card.setOnClickListener { showDetail(e) }
        return card
    }

    private fun compatBadge(e: Entry): TextView? {
        val (label, color) = when (e.compat) {
            Compat.PURE_JS -> return null   // 纯 JS 是常态，不加徽标
            Compat.NEEDS_NATIVE -> getString(R.string.plugin_compat_native) to getColor(R.color.danger)
            Compat.ENGINE_FORK -> getString(R.string.plugin_compat_fork) to getColor(R.color.danger)
            Compat.UNKNOWN -> getString(R.string.plugin_compat_unknown) to getColor(R.color.text_secondary)
            // issue #66：不是插件坏，是我们内置引擎落后于 npm next 线
            Compat.ENGINE_TOO_OLD -> getString(R.string.plugin_compat_engine_old) to getColor(R.color.warning)
        }
        return TextView(this).apply {
            text = "⚠ $label"
            setTextColor(color)
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        }
    }

    /**
     * 停用期间的「单独启用/再停用」白名单徽标（v1.4.28）。
     * 只在「已安装」页、总开关生效、且该插件确实被停用时显示：
     * 未启用的第三方包显示「启用」，已在白名单的显示「再停用」。
     */
    private fun killSwitchChip(e: Entry): TextView? {
        val st = runCatching { PluginKillSwitch.status(this) }.getOrNull() ?: return null
        if (!st.active || !st.disabled.contains(e.pkg)) return null
        if (!app.dsh.mobile.engine.PluginRecovery.isUserBundle(e.pkg)) return null
        val enabled = st.allow.contains(e.pkg)
        return TextView(this).apply {
            text = getString(
                if (enabled) R.string.plugin_kill_disable_one else R.string.plugin_kill_enable_one,
            )
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = dp(52)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundResource(R.drawable.bg_btn_outline)
            setTextColor(if (enabled) getColor(R.color.success) else Accent.current(this@PluginStoreActivity).argb)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
            setOnClickListener {
                val outcome =
                    if (enabled) PluginKillSwitch.requestDisableOne(this@PluginStoreActivity, e.pkg)
                    else PluginKillSwitch.requestEnableOne(this@PluginStoreActivity, e.pkg)
                if (!outcome.ok) {
                    android.widget.Toast.makeText(
                        this@PluginStoreActivity,
                        getString(R.string.plugin_kill_failed, outcome.message),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    return@setOnClickListener
                }
                android.widget.Toast.makeText(
                    this@PluginStoreActivity,
                    getString(
                        if (enabled) R.string.plugin_kill_disable_one_toast
                        else R.string.plugin_kill_enable_one_toast,
                        e.name,
                    ),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                (application as DshApp).supervisor.restart()
                render()
            }
        }
    }

    /** 右侧按钮：安装 / 升级 / 已安装（可点卸载） */
    private fun actionButton(e: Entry): TextView {
        val btn = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = dp(64)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundResource(R.drawable.bg_btn_outline)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) }
        }
        when {
            !e.isInstalled -> {
                btn.text = getString(R.string.plugin_install)
                btn.setTextColor(Accent.current(this@PluginStoreActivity).argb)
                btn.setOnClickListener {
                    // v1.4.52: 裸包名安装。市场条目的 version 是构建期内置目录
                    // 快照，装 `pkg@快照` 会把新用户钉死在旧版（pet-bridge 快照
                    // 0.3.1 / registry 0.3.3 实测）。未安装时 `add <pkg>` 由
                    // registry 解析 latest（lockfile skip 只发生在包已装时）；
                    // 精确版本语义只保留给升级按钮（e.version 已改为镜像 latest）。
                    runInstall(e.pkg, btn, e.pkg)
                }
            }
            e.hasUpdate -> {
                // 精确版本升级：pnpm 12 下 add pkg / @latest / update 全部无效（v1.4.18 实测）
                btn.text = getString(R.string.plugin_upgrade)
                btn.setTextColor(getColor(R.color.warning))
                btn.setText(getString(R.string.plugin_upgrade) + " → ${e.version}")
                btn.setOnClickListener {
                    runInstall("${e.pkg}@${e.version}", btn, e.pkg)
                }
            }
            else -> {
                btn.text = getString(R.string.plugin_installed)
                btn.setTextColor(getColor(R.color.success))
                btn.setOnClickListener { confirmUninstall(e.pkg, btn) }
            }
        }
        return btn
    }

    // ───────────────────────────────────────────────────────────────────
    // 详情
    // ───────────────────────────────────────────────────────────────────

    private fun showDetail(e: Entry) {
        hideKeyboard()
        val body = StringBuilder()
        if (e.desc.isNotBlank()) body.append(e.desc).append("\n\n")
        body.append(getString(R.string.plugin_detail_title)).append(": ").append(e.pkg).append('\n')
        if (e.installedVer != null) {
            body.append("v").append(e.installedVer)
            if (e.hasUpdate) body.append(" → v").append(e.version)
            body.append('\n')
        } else if (e.version.isNotBlank()) {
            body.append("v").append(e.version).append('\n')
        }
        if (e.author.isNotBlank()) body.append(e.author).append('\n')
        if (e.repo.isNotBlank()) body.append(getString(R.string.plugin_detail_repo))
            .append(": ").append(e.repo).append('\n')

        val dialog = AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage(body.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.plugin_detail_compat, null)
            .create()

        dialog.setOnShowListener {
            // 中性按钮：拉完整 manifest（体积/仓库/全部版本）+ 兼容性判断依据
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.setMessage(getString(R.string.plugin_loading_detail))
                PluginMarket.detail(e.pkg) { full, err ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val merged = full ?: e
                        val sb = StringBuilder()
                        sb.append(merged.desc.ifBlank { e.desc }).append("\n\n")
                        sb.append(getString(R.string.plugin_detail_size)).append(": ")
                        sb.append(PluginMarket.humanSize(merged.unpackedSize).ifBlank { "?" }).append('\n')
                        if (merged.repo.isNotBlank()) {
                            sb.append(getString(R.string.plugin_detail_repo)).append(": ")
                                .append(merged.repo).append('\n')
                        }
                        sb.append(getString(R.string.plugin_detail_compat)).append(": ")
                        sb.append(
                            when (merged.compat) {
                                Compat.PURE_JS -> getString(R.string.plugin_compat_pure)
                                Compat.NEEDS_NATIVE -> getString(R.string.plugin_compat_native)
                                Compat.ENGINE_FORK -> getString(R.string.plugin_compat_fork)
                                Compat.UNKNOWN -> getString(R.string.plugin_compat_unknown)
                                Compat.ENGINE_TOO_OLD -> getString(R.string.plugin_compat_engine_old)
                            }
                        )
                        if (merged.compatReasons.isNotEmpty()) {
                            sb.append('\n').append(getString(R.string.plugin_detail_reasons)).append(":\n")
                            merged.compatReasons.forEach { sb.append("• ").append(it).append('\n') }
                        }
                        if (err != null) sb.append("\n(").append(err).append(')')
                        dialog.setMessage(sb.toString().trim())
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
                    }
                }
            }
        }
        dialog.show()
    }

    // ───────────────────────────────────────────────────────────────────
    // 排序 / 检查更新
    // ───────────────────────────────────────────────────────────────────

    private fun showSortDialog() {
        val modes = PluginMarket.Sort.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.plugin_sort_title)
            .setSingleChoiceItems(labels, modes.indexOf(sortMode)) { d, which ->
                sortMode = modes[which]
                btnSort.text = getString(sortMode.labelRes)
                d.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkUpdates() {
        if (loading) return
        loading = true
        loadInstalled(force = true)
        // loadInstalled 里已带 checkUpdates=true，完成后按结果汇总
        tvSubtitle.text = getString(R.string.plugin_searching)
    }

    /** 已安装标签渲染完后调用：汇总可更新数量 */
    private fun reportUpdates(entries: List<Entry>) {
        val n = entries.count { it.hasUpdate }
        android.widget.Toast.makeText(
            this,
            if (n > 0) getString(R.string.plugin_updates_found, n)
            else getString(R.string.plugin_updates_none),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    // ───────────────────────────────────────────────────────────────────
    // 安装 / 卸载（保留 v1.4.15–v1.4.18 已验证的全部行为）
    // ───────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        refreshKillBanner()
    }

    /** 应急总开关生效时把提示条露出来；恢复后立即收起（v1.4.27） */
    private fun refreshKillBanner() {
        val banner = findViewById<TextView>(R.id.tvKillBanner) ?: return
        val active = runCatching { PluginKillSwitch.isActive(this) }.getOrDefault(false)
        banner.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun runInstall(pkg: String, btn: TextView, basePkg: String) {
        btn.text = getString(R.string.plugin_checking)
        btn.isEnabled = false
        Thread({
            val r = runCatching { app.dsh.mobile.engine.NativePluginCheck.check(pkg) }
                .getOrElse { app.dsh.mobile.engine.NativePluginCheck.Result.Unknown(it.message ?: "check failed") }
            runOnUiThread {
                btn.isEnabled = true
                when (r) {
                    is app.dsh.mobile.engine.NativePluginCheck.Result.PureJs -> doInstall(pkg, btn, basePkg)
                    // issue #66：引擎版本不够，装上也跑不起来。这里直接说清楚
                    // 「不是插件坏了，是内置引擎旧了」，而不是让用户去猜。
                    is app.dsh.mobile.engine.NativePluginCheck.Result.EngineTooOld -> {
                        btn.text = getString(R.string.plugin_install)
                        confirmInstall(
                            pkg, btn, basePkg,
                            title = getString(R.string.plugin_compat_engine_old),
                            message = getString(
                                R.string.plugin_engine_too_old_body, r.required, r.bundled,
                            ),
                        )
                    }
                    is app.dsh.mobile.engine.NativePluginCheck.Result.Unknown -> {
                        btn.text = getString(R.string.plugin_install)
                        confirmInstall(
                            pkg, btn, basePkg,
                            title = getString(R.string.plugin_check_unknown_title),
                            message = getString(R.string.plugin_check_unknown_body, r.reason),
                        )
                    }
                    is app.dsh.mobile.engine.NativePluginCheck.Result.Risk -> {
                        btn.text = getString(R.string.plugin_install)
                        confirmInstall(
                            pkg, btn, basePkg,
                            title = getString(R.string.plugin_native_title),
                            message = getString(
                                R.string.plugin_native_body,
                                r.reasons.joinToString("\n• ", prefix = "• "),
                            ),
                        )
                    }
                }
            }
        }, "plugin-precheck").apply { isDaemon = true; start() }
    }

    private fun confirmInstall(
        pkg: String, btn: TextView, basePkg: String, title: String, message: String,
    ) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.plugin_install_anyway) { _, _ -> doInstall(pkg, btn, basePkg) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doInstall(pkg: String, btn: TextView, basePkg: String) {
        if (isFinishing || isDestroyed) return
        // v1.4.28：停用期间允许安装/更新（PluginCenter 只拦「正在恢复」窗口）——
        // 排查工作流需要先更新嫌疑插件。装完不自动加载（不在白名单），下面提示。
        if (!btn.isAttachedToWindow) {
            android.util.Log.w("PluginStore", "install of $pkg dropped: row re-rendered")
            return
        }
        btn.text = getString(R.string.plugin_installing)
        btn.isEnabled = false
        btn.setTextColor(getColor(R.color.text_secondary))
        PluginCenter.install(this, pkg) { ok, msg ->
            runOnUiThread {
                if (ok) {
                    btn.text = getString(R.string.plugin_installed)
                    btn.setTextColor(getColor(R.color.success))
                    btn.isClickable = true
                    btn.isEnabled = true
                    btn.setOnClickListener { confirmUninstall(basePkg, btn) }
                    // v1.4.28：停用期间装上的插件不会被加载，提示去「已安装」单独启用
                    if (runCatching { PluginKillSwitch.isActive(this@PluginStoreActivity) }.getOrDefault(false)) {
                        android.widget.Toast.makeText(
                            this@PluginStoreActivity,
                            getString(R.string.plugin_kill_installed_hint),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    // 重读真实版本后整页刷新（升级场景下版本号/按钮要跟着变）
                    reload(verbose = true)
                } else {
                    btn.text = getString(R.string.plugin_failed, msg.take(28))
                    btn.setTextColor(getColor(R.color.danger))
                    btn.isEnabled = true
                    btn.isClickable = true
                    // v1.4.37（用户反馈「插件下不了」）：引擎因版本不兼容拒绝安装时，
                    // 把真正原因完整弹出来，并提供「强制安装此版本」入口。
                    // 旧实现只把错误截成 28 字符塞进按钮，用户根本看不到原因。
                    offerEngineExemptionIfNeeded(pkg, btn, basePkg, msg)
                }
            }
        }
    }

    /**
     * 引擎兼容性拒绝 → 弹窗说明原因，并给「强制安装此版本」（写入 exact-version 豁免）。
     *
     * 只在 [PluginCenter.parseEngineRejection] 认出这是引擎拒绝时触发；
     * 其它错误（网络、store 锁、原生模块编译失败）不弹这个窗，避免误导。
     *
     * **必须用户点确认**才执行 —— 引擎原文警告「may cause crashes or data loss」，
     * App 不能替用户静默承担这个风险。
     */
    private fun offerEngineExemptionIfNeeded(
        pkg: String,
        btn: TextView,
        basePkg: String,
        msg: String,
    ) {
        val rejection = runCatching {
            PluginCenter.parseEngineRejection(this, pkg, msg)
        }.getOrNull() ?: return
        val version = rejection.version ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.plugin_engine_old_title))
            .setMessage(rejection.userMessage(this))
            .setPositiveButton(getString(R.string.plugin_engine_old_force)) { _, _ ->
                forceInstall(pkg, btn, basePkg, version)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 用户已确认风险 → 写 exact-version 豁免并重试安装。 */
    private fun forceInstall(pkg: String, btn: TextView, basePkg: String, version: String) {
        if (isFinishing || isDestroyed || !btn.isAttachedToWindow) return
        btn.text = getString(R.string.plugin_installing)
        btn.isEnabled = false
        PluginCenter.forceInstallWithExemption(this, pkg, version) { ok, msg ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    btn.text = getString(R.string.plugin_installed)
                    btn.setTextColor(getColor(R.color.success))
                    btn.isEnabled = true
                    btn.isClickable = true
                    btn.setOnClickListener { confirmUninstall(basePkg, btn) }
                    reload(verbose = true)
                } else {
                    btn.text = getString(R.string.plugin_failed, msg.take(28))
                    btn.setTextColor(getColor(R.color.danger))
                    btn.isEnabled = true
                    btn.isClickable = true
                }
            }
        }
    }

    private fun confirmUninstall(pkg: String, btn: TextView) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.plugin_uninstall))
            .setMessage(pkg)
            .setPositiveButton(R.string.plugin_uninstall) { _, _ -> runUninstall(pkg, btn) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runUninstall(pkg: String, btn: TextView) {
        btn.text = getString(R.string.plugin_uninstalling)
        btn.isEnabled = false
        btn.setTextColor(getColor(R.color.text_secondary))
        PluginCenter.uninstall(this, pkg) { ok, msg ->
            runOnUiThread {
                if (ok) {
                    btn.text = getString(R.string.plugin_install)
                    btn.setTextColor(Accent.current(this@PluginStoreActivity).argb)
                    btn.isEnabled = true
                    btn.isClickable = true
                    btn.setOnClickListener {
                        runInstall(pkg, btn, pkg)
                    }
                    reload(verbose = true)
                } else {
                    btn.text = getString(R.string.plugin_failed, msg.take(28))
                    btn.setTextColor(getColor(R.color.danger))
                    btn.isEnabled = true
                    btn.isClickable = true
                }
            }
        }
    }

    companion object {
        private const val STORE_CONTENT_MAX_WIDTH_DP = 720

        /**
         * 每批渲染的插件卡片数（v1.4.30，issue #62）。
         *
         * 目录有 3540 条，每行约 6 个嵌套 View。全量渲染会把两万多个 View 塞进
         * 没有视图回收的 ScrollView，主线程直接 OOM/ANR → 进程被杀 → 引擎看门狗
         * 误判重启（用户看到「一搜索就卡崩重启」）。30 条约几百个 View，
         * 单批主线程耗时在毫秒级，滚动追加时也察觉不到。
         */
        private const val RENDER_PAGE = 30

        /** 从桌宠设置页跳过来时预填搜索词，例如 dsh-pet-bridge。 */
        const val EXTRA_QUERY = "app.dsh.mobile.extra.PLUGIN_QUERY"
    }
}

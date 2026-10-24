package app.dsh.mobile

import android.app.Activity
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
import app.dsh.mobile.engine.NativePluginCheck
import app.dsh.mobile.engine.PluginCenter
import app.dsh.mobile.engine.PluginCenter.PluginInfo

/**
 * 社区插件中心（v1.2.117）：**实时搜索 npm** 上的 dsh 生态插件 + 一键安装。
 *
 * ## 与上一版的区别（主人实测反馈）
 *  - **可滚动**：结果区放进 ScrollView（旧版用普通 LinearLayout 当根容器，50 条结果
 *    被屏幕截断且无法滚动 —— 主人实测「上下滑动都不行」）。
 *  - **风格统一**：改用 XML 布局（顶栏 + 返回箭头 + ScrollView），与扩展中心一致；
 *    按钮改小尺寸描边胶囊（旧版全宽实心大按钮，主人反馈「太 AI 了」）。
 *  - 进入页面自动加载全部生态，搜索框回车/点按钮过滤。
 */
class PluginStoreActivity : Activity() {

    /** v1.2.120：与其余页面一致，按应用内语言偏好包装 Context（此前遗漏） */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var container: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageView
    private val dp by lazy { resources.displayMetrics.density }

    /** 已安装包名（读 profile/package.json，v1.2.118）—— 每次刷新重读，保证真实 */
    private var installed: Set<String> = emptySet()

    /** 已安装插件的实际版本 pkg -> version（v1.4.18，升级判断用） */
    private var installedVer: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.4.18：跟随应用朝向偏好；平板横屏下列表限宽，避免卡片拉满整屏
        OrientationPrefs.applyTo(this)
        setContentView(R.layout.activity_plugin_store)
        findViewById<MaxWidthLinearLayout>(R.id.pluginStoreBody).maxWidthDp =
            STORE_CONTENT_MAX_WIDTH_DP

        container = findViewById(R.id.listContainer)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        etSearch = findViewById(R.id.etSearch)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        btnClear = findViewById(R.id.btnClear)
        btnClear.setOnClickListener {
            etSearch.setText("")
            btnClear.visibility = View.GONE
            hideKeyboard()
            doSearch("")
        }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                doSearch(etSearch.text.toString().trim())
            }
            true
        }
        // 输入即搜（300ms 防抖）：极简风格下没有独立按钮，边打边出结果
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            private val handler = android.os.Handler(android.os.Looper.getMainLooper())
            private val run = Runnable { doSearch(etSearch.text.toString().trim()) }
            override fun afterTextChanged(s: android.text.Editable?) {
                btnClear.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                handler.removeCallbacks(run)
                handler.postDelayed(run, 300)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        doSearch("")
    }

    private fun dp(v: Int): Int = (v * dp).toInt()

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    /** 搜索并渲染；query 空 = 全部生态 */
    private fun doSearch(query: String) {
        container.removeAllViews()
        tvSubtitle.text = getString(R.string.plugin_searching)
        // 每次搜索都重读已装清单：安装后立刻反映，退出重进也准确（v1.2.118）
        installed = runCatching { PluginCenter.installedPackages(this) }.getOrDefault(emptySet())
        installedVer = runCatching { PluginCenter.installedVersions(this) }.getOrDefault(emptyMap())
        PluginCenter.search(query) { results ->
            runOnUiThread { render(query, results) }
        }
    }

    private fun render(query: String, results: List<PluginInfo>) {
        container.removeAllViews()
        if (results.isEmpty()) {
            // npm 不可达或确实无结果 → 回退内置精选（离线也有东西可装）
            val fallback = runCatching { PluginCenter.list() }.getOrDefault(emptyList())
            tvSubtitle.text = getString(R.string.plugin_search_fail)
            if (fallback.isEmpty()) {
                container.addView(hint(getString(R.string.plugin_search_none)))
                return
            }
            container.addView(sectionHeader(getString(R.string.plugin_catalog_title)))
            fallback.forEach { container.addView(pluginRow(it)) }
            return
        }
        tvSubtitle.text = if (query.isBlank()) {
            "npm · ${results.size} " + getString(R.string.plugin_catalog_title)
        } else {
            "npm · ${results.size}"
        }
        results.forEach { container.addView(pluginRow(it)) }
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFF8A94A3.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, dp(30), 0, 0)
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(0xFF7DD3FC.toInt())
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(18), dp(8), dp(8))
    }

    /**
     * 插件行（与扩展中心同构）：一行内 名称+版本 / 描述 / 右侧小按钮。
     * 行整体是卡片，不占满宽的大按钮 —— 主人反馈旧版按钮「太 AI」。
     */
    private fun pluginRow(p: PluginInfo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 名称 + 版本（同行）
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = p.pkg
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (p.version.isNotBlank()) {
            titleRow.addView(TextView(this).apply {
                text = "v${p.version}"
                setTextColor(0xFF7DD3FC.toInt())
                textSize = 11f
                maxLines = 1          // 版本号不换行（实测 v0.1.4 被拆成两行）
                setPadding(dp(8), 0, 0, 0)
            })
        }
        textCol.addView(titleRow)
        if (p.desc.isNotBlank()) {
            textCol.addView(TextView(this).apply {
                text = p.desc
                setTextColor(0xFF8A94A3.toInt())
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
        }
        card.addView(textCol)

        // 右侧：小尺寸描边胶囊按钮（与扩展中心操作按钮同规格）
        val isInstalled = installed.contains(p.pkg)
        val btn = TextView(this).apply {
            text = if (isInstalled) getString(R.string.plugin_installed)
            else getString(R.string.plugin_install)
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = dp(64)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setTextColor(0xFF7DD3FC.toInt())
            setBackgroundResource(R.drawable.bg_btn_outline)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) }
        }
        if (isInstalled) {
            // v1.4.18：npm 上有新版（搜索结果 version 与实际安装版本不同）→ 显示「升级」。
            // 升级必须 `add pkg@<精确版本>`：pnpm 12 实测（PC + 模拟器双验证）——
            // `add pkg` 保留旧 spec 不解析；`add pkg@latest` 与 `update pkg` 被
            // "Lockfile is up to date / Already up to date" 跳过（supply-chain
            // verified 缓存）；只有精确版本会真正重新解析（-0.74.0 +0.75.1 实证）。
            val canUpgrade = p.version.isNotBlank() && installedVer[p.pkg] != p.version
            if (canUpgrade) {
                btn.text = getString(R.string.plugin_upgrade)
                btn.setTextColor(0xFFFBBF24.toInt())
                btn.isClickable = true
                btn.isEnabled = true
                btn.setOnClickListener { runInstall("${p.pkg}@${p.version}", btn, basePkg = p.pkg) }
            } else {
                // 已装且无新版：绿色只读 → 点击卸载（v1.2.120，主人反馈「能装不能卸」）
                btn.setTextColor(0xFF4ADE80.toInt())
                btn.isClickable = true
                btn.isEnabled = true
                btn.setOnClickListener { runUninstall(p.pkg, btn) }
            }
        } else {
            // v1.4.18：安装也用精确版本 —— `add pkg`（无 spec）在 remove 残留
            // lockfile/spec 时会复用旧版；精确版本与升级同路径、行为可预期。
            btn.setOnClickListener {
                val spec = if (p.version.isNotBlank()) "${p.pkg}@${p.version}" else p.pkg
                runInstall(spec, btn, basePkg = p.pkg)
            }
        }
        card.addView(btn)
        return card
    }

    /**
     * 安装入口（v1.4.15）：先做原生模块预检，再决定是否直接装。
     *
     * 为什么要预检 —— 用户反馈 `ERR_PNPM_IGNORED_BUILDS`：pnpm 12 默认拒绝执行依赖的
     * build 脚本，带原生模块的插件会「装成功但二进制没编译」，`build/` 目录都不存在。
     * 在 Android 上这类插件多数根本编译不出来（缺 clang 头文件搜索路径、node_prefix
     * 写死成 Termux 路径），所以与其让用户拿到一个用不了的插件，不如装前说清楚。
     */
    private fun runInstall(pkg: String, btn: TextView, basePkg: String = pkg.substringBefore('@')) {
        btn.text = getString(R.string.plugin_checking)
        btn.isEnabled = false
        Thread({
            val r = runCatching { NativePluginCheck.check(pkg) }
                .getOrElse { NativePluginCheck.Result.Unknown(it.message ?: "check failed") }
            runOnUiThread {
                btn.isEnabled = true
                when (r) {
                    is NativePluginCheck.Result.PureJs -> doInstall(pkg, btn, basePkg)
                    is NativePluginCheck.Result.Unknown -> {
                        // 查不到（离线 / git 源）：不拦，但要告诉用户「没验证过」
                        btn.text = getString(R.string.plugin_install)
                        confirmInstall(
                            pkg, btn, basePkg,
                            title = getString(R.string.plugin_check_unknown_title),
                            message = getString(R.string.plugin_check_unknown_body, r.reason),
                        )
                    }
                    is NativePluginCheck.Result.Risk -> {
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

    /** 预检有风险时的二次确认；点「仍然安装」才真的装。 */
    private fun confirmInstall(
        pkg: String, btn: TextView, basePkg: String, title: String, message: String,
    ) {
        if (isFinishing || isDestroyed) return
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.plugin_install_anyway) { _, _ -> doInstall(pkg, btn, basePkg) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 真正执行安装并刷新按钮状态 */
    private fun doInstall(pkg: String, btn: TextView, basePkg: String = pkg.substringBefore('@')) {
        // v1.4.16 审查 P2：PureJs 分支不弹确认框，预检期间（最长 20s）用户可能
        // 已离开页面或搜索重渲染让 btn 脱离视图树 —— 此时不再启动安装。
        if (isFinishing || isDestroyed) {
            android.util.Log.w("PluginStore", "install of $pkg dropped: activity gone")
            return
        }
        if (!btn.isAttachedToWindow) {
            android.util.Log.w("PluginStore", "install of $pkg dropped: row re-rendered")
            return
        }
        btn.text = getString(R.string.plugin_installing)
        btn.isEnabled = false
        btn.setTextColor(0xFF8A94A3.toInt())
        PluginCenter.install(this, pkg) { ok, msg ->
            runOnUiThread {
                if (ok) {
                    // 装成功（含升级）：标记已装并保持绿色只读（不再 2.5s 后复原 —— 那会让用户
                    // 以为没装上、反复点；且与「退出重进」的显示一致）
                    btn.text = getString(R.string.plugin_installed)
                    btn.setTextColor(0xFF4ADE80.toInt())
                    btn.isClickable = false
                    btn.isEnabled = false
                    installed = installed + basePkg
                    // 升级成功：重读实际版本（spec 里的 @latest 不是真版本号）
                    installedVer = runCatching {
                        PluginCenter.installedVersions(this@PluginStoreActivity)
                    }.getOrDefault(installedVer)
                } else {
                    // 失败：按钮就地显示原因（限长），完整原因已在通知与日志里
                    btn.text = getString(R.string.plugin_failed, msg.take(28))
                    btn.setTextColor(0xFFF87171.toInt())
                    btn.isEnabled = true
                    btn.isClickable = true
                }
            }
        }
    }

    /** 执行卸载并刷新按钮状态（v1.2.120） */
    private fun runUninstall(pkg: String, btn: TextView) {
        btn.text = getString(R.string.plugin_uninstalling)
        btn.isEnabled = false
        btn.setTextColor(0xFF8A94A3.toInt())
        PluginCenter.uninstall(this, pkg) { ok, msg ->
            runOnUiThread {
                if (ok) {
                    // 卸成功：回到"安装"可点击态
                    btn.text = getString(R.string.plugin_install)
                    btn.setTextColor(0xFF7DD3FC.toInt())
                    btn.isEnabled = true
                    btn.isClickable = true
                    btn.setOnClickListener { runInstall(pkg, btn) }
                    installed = installed - pkg
                } else {
                    btn.text = getString(R.string.plugin_failed, msg.take(28))
                    btn.setTextColor(0xFFF87171.toInt())
                    btn.isEnabled = true
                    btn.isClickable = true
                }
            }
        }
    }

    /** 平板/横屏下列表内容最大宽度（dp） */
    private companion object {
        const val STORE_CONTENT_MAX_WIDTH_DP = 900
    }
}

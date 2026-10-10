package app.dsh.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
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

    private lateinit var container: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageView
    private val dp by lazy { resources.displayMetrics.density }

    /** 已安装包名（读 profile/package.json，v1.2.118）—— 每次刷新重读，保证真实 */
    private var installed: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_plugin_store)

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
            // 已装：绿色只读标记（主人实测「装完退出重进又能装」——现在查真实安装记录）
            btn.setTextColor(0xFF4ADE80.toInt())
            btn.isClickable = false
            btn.isEnabled = false
        } else {
            btn.setOnClickListener { runInstall(p.pkg, btn) }
        }
        card.addView(btn)
        return card
    }

    /** 执行安装并刷新按钮状态 */
    private fun runInstall(pkg: String, btn: TextView) {
        val original = btn.text.toString()
        btn.text = getString(R.string.plugin_installing)
        btn.isEnabled = false
        btn.setTextColor(0xFF8A94A3.toInt())
        PluginCenter.install(this, pkg) { ok, msg ->
            runOnUiThread {
                if (ok) {
                    // 装成功：标记已装并保持绿色只读（不再 2.5s 后复原 —— 那会让用户
                    // 以为没装上、反复点；且与「退出重进」的显示一致）
                    btn.text = getString(R.string.plugin_installed)
                    btn.setTextColor(0xFF4ADE80.toInt())
                    btn.isClickable = false
                    btn.isEnabled = false
                    installed = installed + pkg
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
}

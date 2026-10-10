package app.dsh.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.dsh.mobile.engine.PluginCenter
import app.dsh.mobile.engine.PluginCenter.PluginInfo

/**
 * 社区插件中心（v1.2.114）：**实时搜索 npm** 上的 dsh 生态插件 + 一键安装。
 *
 * 进入页面自动搜索全部生态（keywords:deepseek-harness），搜索框可过滤；
 * 结果实时来自 npm registry，每项带安装按钮（引擎官方 CLI 安装）。
 * npm 不可达时回退内置精选清单。
 */
class PluginStoreActivity : Activity() {

    private lateinit var resultBox: LinearLayout
    private lateinit var searchInput: EditText
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            setBackgroundColor(Color.rgb(15, 17, 22))
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = getString(R.string.plugin_center_title)
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.plugin_center_sub)
            setTextColor(Color.rgb(138, 148, 163))
            textSize = 13f
            setPadding(0, (6 * dp).toInt(), 0, (14 * dp).toInt())
        })

        // —— 搜索框 + 按钮 ——
        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchInput = EditText(this).apply {
            hint = getString(R.string.plugin_search_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(90, 98, 110))
            textSize = 14f
            setSingleLine(true)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { rightMargin = (10 * dp).toInt() }
        }
        searchBar.addView(searchInput)
        searchBar.addView(Button(this).apply {
            text = getString(R.string.plugin_search_btn)
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setBackgroundResource(R.drawable.bg_btn_accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (44 * dp).toInt(),
            )
            setOnClickListener {
                hideKeyboard()
                doSearch(searchInput.text.toString().trim())
            }
        })
        // 回车也搜索
        searchInput.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            doSearch(searchInput.text.toString().trim())
            true
        }
        root.addView(searchBar)

        // —— 结果区（动态填充）——
        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }
        root.addView(resultBox)

        // 进入即加载全部
        doSearch("")
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    /** 搜索并渲染结果；query 空 = 全部生态 */
    private fun doSearch(query: String) {
        resultBox.removeAllViews()
        resultBox.addView(TextView(this).apply {
            text = getString(R.string.plugin_searching)
            setTextColor(Color.rgb(138, 148, 163))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, 0)
        })
        PluginCenter.search(query) { results ->
            runOnUiThread { render(query, results) }
        }
    }

    private fun render(query: String, results: List<PluginInfo>) {
        resultBox.removeAllViews()
        if (results.isEmpty()) {
            resultBox.addView(TextView(this).apply {
                text = getString(R.string.plugin_search_none) + "\n" +
                    getString(R.string.plugin_search_fail)
                setTextColor(Color.rgb(138, 148, 163))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, (24 * dp).toInt(), 0, 0)
            })
            // 回退：内置精选（npm 不可达时至少有东西可装）
            runCatching { PluginCenter.list() }.getOrDefault(emptyList()).forEach { p ->
                resultBox.addView(pluginCard(p))
            }
            return
        }
        if (query.isBlank()) {
            resultBox.addView(TextView(this).apply {
                text = "npm · ${results.size} plugins"
                setTextColor(Color.rgb(125, 211, 252))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, (10 * dp).toInt())
            })
        }
        results.forEach { p -> resultBox.addView(pluginCard(p)) }
    }

    /** 插件卡片：名称+版本 / 描述 / 安装按钮 */
    private fun pluginCard(p: PluginInfo): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (18 * dp).toInt())
        setBackgroundResource(R.drawable.bg_card)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.bottomMargin = (14 * dp).toInt()
        layoutParams = lp

        addView(LinearLayout(this@PluginStoreActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@PluginStoreActivity).apply {
                text = p.pkg
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            })
            if (p.version.isNotBlank()) {
                addView(TextView(this@PluginStoreActivity).apply {
                    text = "v${p.version}"
                    setTextColor(Color.rgb(125, 211, 252))
                    textSize = 12f
                })
            }
        })
        if (p.desc.isNotBlank()) {
            addView(TextView(this@PluginStoreActivity).apply {
                text = p.desc
                setTextColor(Color.rgb(138, 148, 163))
                textSize = 13f
                setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt())
            })
        }
        val btn = Button(this@PluginStoreActivity).apply {
            text = getString(R.string.plugin_install)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            isAllCaps = false
            setBackgroundResource(R.drawable.bg_btn_accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (44 * dp).toInt(),
            )
        }
        btn.setOnClickListener { runInstall(p.pkg, btn) }
        addView(btn)
    }

    /** 执行安装并刷新按钮状态 */
    private fun runInstall(pkg: String, btn: Button) {
        val original = btn.text.toString()
        btn.text = getString(R.string.plugin_installing)
        btn.isEnabled = false
        PluginCenter.install(this, pkg) { ok, msg ->
            runOnUiThread {
                btn.text = if (ok) getString(R.string.plugin_installed)
                else getString(R.string.plugin_failed, msg.take(50))
                btn.isEnabled = true
                if (ok) btn.postDelayed({ btn.text = original }, 2500)
            }
        }
    }
}

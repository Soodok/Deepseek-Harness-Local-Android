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

/**
 * 社区插件中心（v1.2.114）：一键安装 dsh 生态插件。
 *
 * 列表程序化构建（清单量小，无需 RecyclerView）。两部分：
 *  1. 清单插件（[PluginCenter.list]，远程拉取失败回退内置）—— 逐个卡片带安装按钮；
 *  2. 手动安装 —— 输入任意 npm 包名安装（清单没有的也能装，dsh 自身会做兼容校验）。
 */
class PluginStoreActivity : Activity() {

    private lateinit var root: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
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
            setPadding(0, (6 * dp).toInt(), 0, (16 * dp).toInt())
        })

        // —— 手动安装：任意 npm 包名（清单没有的也能装，dsh 自身做兼容校验）——
        val manualCard = card()
        manualCard.addView(TextView(this).apply {
            text = getString(R.string.plugin_manual_title)
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        val input = EditText(this).apply {
            hint = "@scope/package"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(90, 98, 110))
            textSize = 14f
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            setBackgroundResource(R.drawable.bg_card)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (10 * dp).toInt()
            layoutParams = lp
        }
        manualCard.addView(input)
        val installBtn = accentButton(getString(R.string.plugin_install))
        installBtn.setOnClickListener {
            val pkg = input.text.toString().trim()
            if (pkg.isEmpty()) return@setOnClickListener
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
            runInstall(pkg, installBtn)
        }
        manualCard.addView(installBtn)
        root.addView(manualCard)

        // —— 清单插件 ——
        root.addView(TextView(this).apply {
            text = getString(R.string.plugin_catalog_title)
            setTextColor(Color.rgb(125, 211, 252))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (18 * dp).toInt(), 0, (10 * dp).toInt())
        })

        val plugins = runCatching { PluginCenter.list() }.getOrDefault(emptyList())
        if (plugins.isEmpty()) {
            root.addView(TextView(this).apply {
                text = getString(R.string.plugin_empty)
                setTextColor(Color.rgb(138, 148, 163))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
            })
        } else {
            plugins.forEach { p ->
                val card = card()
                card.addView(TextView(this).apply {
                    text = p.pkg
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                })
                if (p.desc.isNotBlank()) {
                    card.addView(TextView(this).apply {
                        text = p.desc
                        setTextColor(Color.rgb(138, 148, 163))
                        textSize = 13f
                        setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt())
                    })
                }
                val btn = accentButton(getString(R.string.plugin_install))
                btn.setOnClickListener { runInstall(p.pkg, btn) }
                card.addView(btn)
                root.addView(card)
            }
        }
    }

    /** 卡片容器（统一圆角底 + 间距） */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (18 * dp).toInt())
        setBackgroundResource(R.drawable.bg_card)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.bottomMargin = (14 * dp).toInt()
        layoutParams = lp
    }

    /** 圆角强调按钮（bg_btn_accent，白字，全宽） */
    private fun accentButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isAllCaps = false
        setBackgroundResource(R.drawable.bg_btn_accent)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (46 * dp).toInt(),
        )
        lp.topMargin = (4 * dp).toInt()
        layoutParams = lp
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
                if (ok) {
                    btn.postDelayed({ btn.text = original }, 2500)
                }
            }
        }
    }
}

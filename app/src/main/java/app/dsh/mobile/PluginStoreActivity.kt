package app.dsh.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.dsh.mobile.engine.PluginCenter

/**
 * 社区插件中心（v1.2.113）：一键安装 dsh 生态插件。
 *
 * 列表程序化构建（清单量小，无需 RecyclerView），与扩展中心同风格。
 * 数据源 [PluginCenter.list]（远程清单，失败回退内置）；安装走引擎官方 CLI
 * （`dsh plugin --profile web add <pkg>`），完成后按钮显示结果。
 */
class PluginStoreActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            setBackgroundColor(Color.rgb(17, 19, 24))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.plugin_center_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.plugin_center_sub)
            setTextColor(Color.rgb(138, 148, 163))
            textSize = 13f
            setPadding(0, (6 * dp).toInt(), 0, (14 * dp).toInt())
        })

        val plugins = runCatching { PluginCenter.list() }.getOrDefault(emptyList())
        if (plugins.isEmpty()) {
            root.addView(TextView(this).apply {
                text = getString(R.string.plugin_empty)
                setTextColor(Color.rgb(138, 148, 163))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, (30 * dp).toInt(), 0, 0)
            })
        } else {
            plugins.forEach { p ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                    setBackgroundResource(R.drawable.bg_card)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.bottomMargin = (10 * dp).toInt()
                    layoutParams = lp
                }
                card.addView(TextView(this).apply {
                    text = p.pkg
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                })
                if (p.desc.isNotBlank()) {
                    card.addView(TextView(this).apply {
                        text = p.desc
                        setTextColor(Color.rgb(138, 148, 163))
                        textSize = 13f
                        setPadding(0, (4 * dp).toInt(), 0, (10 * dp).toInt())
                    })
                }
                card.addView(Button(this).apply {
                    text = getString(R.string.plugin_install)
                    setOnClickListener {
                        text = getString(R.string.plugin_installing)
                        isEnabled = false
                        PluginCenter.install(this@PluginStoreActivity, p.pkg) { ok, msg ->
                            runOnUiThread {
                                text = if (ok) getString(R.string.plugin_installed)
                                else getString(R.string.plugin_failed, msg.take(60))
                                isEnabled = true
                            }
                        }
                    }
                })
                root.addView(card)
            }
        }

        setContentView(root)
    }
}

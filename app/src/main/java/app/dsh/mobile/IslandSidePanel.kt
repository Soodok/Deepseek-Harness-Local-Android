package app.dsh.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.PluginCenter

/**
 * 灵动岛「贴左/右边展开」的竖排面板（v1.5.6，A 方案）。
 *
 * 为什么用代码建视图而不是新写一份 XML：面板要按停靠边动态改圆角（贴边侧 0、
 * 外凸侧 22dp）、高度受屏高 70% 限制后内部滚动，XML 里写死这些反而更难维护；
 * 而且主界面布局 activity_main.xml 已经很大，再加一段会让 diff 难以复核。
 *
 * 内容（自上而下）：
 *  ① 状态头：状态点 + 状态文字 + 引擎副标题
 *  ② 动作：重启引擎 / 终端 / 收起
 *  ③ 插件：显示已装数量 + 「管理插件」入口（开关不在这里 —— 引擎侧目前没有
 *     启停 API，装了就是启用，真要开关得先加 PluginCenter 的 enable/disable）
 *  ④ 扩展中心（快捷入口）
 *  ⑤ 设置
 */
class IslandSidePanel(private val act: Activity, private val dock: IslandDock) {

    private val density = act.resources.displayMetrics.density

    /** 插件列表最多显示的行数；超出则内部滚动（用户要求）。 */
    private val MAX_PLUGIN_ROWS = 3
    private val PLUGIN_ROW_DP = 36
    private fun dp(v: Int) = (v * density).toInt()

    lateinit var root: LinearLayout
        private set
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var dot: View
    private lateinit var statusHead: View
    var onStatus: (() -> Unit)? = null

    /** 当前主题下的配色（v1.7.11）：暗色沿用旧值，浅色是新补的一套。 */
    private val pal: IslandPalette.Colors by lazy { IslandPalette.themed(act) }

    var onCollapse: (() -> Unit)? = null
    /** 点「运行模式」：由宿主弹 RunModeDialog（需要 Activity 级 Dialog）。 */
    var onRunMode: (() -> Unit)? = null
    var onRestart: (() -> Unit)? = null

    fun build(): View {
        root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            clipToOutline = false
            // 点空白区域收起：与灵动岛一致（用户明确要求「不用必须点某个选项」）。
            // 子行自己带点击事件，不会冒泡到这里。
            isClickable = true
            setOnClickListener { onCollapse?.invoke() }
        }

        // ① 状态头
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(9))
        }
        statusHead = head
        head.setOnClickListener { onStatus?.invoke() }
        dot = View(act).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pal.accent)
            }
        }
        head.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })

        val titles = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        statusText = TextView(act).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(pal.title)
        }
        subText = TextView(act).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTextColor(pal.muted)
        }
        titles.addView(statusText)
        titles.addView(subText)
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(divider())

        // ② 动作
        root.addView(actionRow(ic = R.drawable.ic_restart, label = act.getString(R.string.btn_restart)) {
            onRestart?.invoke()
        })
        root.addView(actionRow(ic = R.drawable.ic_terminal, label = act.getString(R.string.btn_terminal)) {
            act.startActivity(Intent(act, TerminalActivity::class.java))
        })
        root.addView(actionRow(ic = R.drawable.ic_chevron, label = act.getString(R.string.btn_hide)) {
            onCollapse?.invoke()
        })
        root.addView(divider())

        // ③ 插件
        root.addView(sectionHeader(act.getString(R.string.setting_plugin), showMore = true) {
            act.startActivity(Intent(act, PluginStoreActivity::class.java))
        })
        val installed = runCatching { PluginCenter.installedVersions(act).keys }.getOrDefault(emptySet())
        if (installed.isEmpty()) {
            root.addView(hintRow(act.getString(R.string.island_side_no_plugins)))
        } else {
            // 插件多的时候不能把面板撑长（用户要求）：超过 3 个就只保留 3 行的
            // 窗口高度，列表在内部上下滑动选择。
            val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            installed.forEach { pkg -> list.addView(pluginRow(pkg)) }
            if (installed.size > MAX_PLUGIN_ROWS) {
                root.addView(
                    ScrollView(act).apply {
                        isFillViewport = false
                        addView(list, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ))
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(PLUGIN_ROW_DP * MAX_PLUGIN_ROWS),
                    ),
                )
            } else {
                root.addView(list)
            }
        }
        root.addView(pluginCountRow(installed.size))
        root.addView(divider())

        // ④ 扩展中心（工具链）：图标用下载箭头，不用安卓标
        root.addView(actionRow(ic = R.drawable.ic_package, label = act.getString(R.string.setting_ext)) {
            act.startActivity(Intent(act, ExtensionStoreActivity::class.java))
        })
        // ⑤ 运行模式：替代原来的「语音与插件」行（用户要求）。
        //    点开是一个悬浮窗，原生 / 容器 两组二选一，选完问是否立刻重启。
        root.addView(actionRow(ic = R.drawable.ic_puzzle, label = act.getString(R.string.island_run_mode)) {
            onRunMode?.invoke()
        })
        // ⑥ 设置
        root.addView(actionRow(ic = R.drawable.ic_settings, label = act.getString(R.string.settings_title)) {
            act.startActivity(Intent(act, SettingsActivity::class.java))
        })

        val scroll = ScrollView(act).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return scroll
    }

    /** 只有贴左边时圆角在右侧；贴右边镜像。 */
    private fun panelBackground(): GradientDrawable {
        val r = IslandChrome.outerRadiusPx(expanded = true, density = density).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(pal.panel)
            // 不描边：贴屏幕那一侧若画描边，会沿边露出一条亮线
            cornerRadii = when (dock) {
                IslandDock.RIGHT -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                else -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
        }
    }

    private fun divider() = View(act).apply {
        setBackgroundColor(pal.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(12); marginEnd = dp(12)
        }
    }

    private fun actionRow(ic: Int, label: String, onClick: () -> Unit): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val icon = ImageView(act).apply {
            setImageResource(ic)
            setColorFilter(pal.accent)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        val tv = TextView(act).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(pal.title)
        }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.minimumHeight = dp(40)
        return row
    }

    private fun sectionHeader(title: String, showMore: Boolean, onMore: () -> Unit): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(12), dp(4))
        }
        row.addView(TextView(act).apply {
            text = title.uppercase()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(pal.muted)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (showMore) {
            row.addView(TextView(act).apply {
                text = act.getString(R.string.island_side_more)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(pal.accent)
                isClickable = true
                setOnClickListener { onMore() }
            })
        }
        return row
    }

    private fun pluginRow(pkg: String): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(12), dp(6))
            isClickable = true
            setOnClickListener { act.startActivity(Intent(act, PluginStoreActivity::class.java)) }
        }
        row.addView(View(act).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pal.accent)
            }
        }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(8) })
        row.addView(TextView(act).apply {
            text = pkg.substringAfterLast('/')
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(pal.body)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.minimumHeight = dp(36)
        return row
    }

    private fun pluginCountRow(n: Int): View = TextView(act).apply {
        text = act.getString(R.string.island_side_plugin_count, n)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        setTextColor(pal.muted)
        setPadding(dp(14), dp(4), dp(12), dp(8))
    }

    private fun hintRow(text: String): View = TextView(act).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(pal.muted)
        setPadding(dp(14), dp(6), dp(12), dp(6))
    }

    /** 由主界面在状态变化时调用。 */
    fun update(state: String, detail: String, dotColor: Int, statusClickable: Boolean = false) {
        if (!::statusText.isInitialized) return
        statusHead.isClickable = statusClickable
        statusHead.isFocusable = statusClickable
        statusText.text = state
        subText.text = detail
        subText.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        (dot.background as? GradientDrawable)?.setColor(dotColor)
    }

    /** 面板淡入；收起动画由调用方负责。 */
    fun fadeIn() {
        if (!::root.isInitialized) return
        root.alpha = 0f
        (root.parent as? View)?.alpha = 1f
        root.animate().alpha(1f).setDuration(120L).start()
    }
}

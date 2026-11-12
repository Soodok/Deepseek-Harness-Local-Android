package app.dsh.mobile

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.dsh.mobile.engine.LinuxRootfsCatalog

/** 工作台上的一个动作，视图层只发意图，业务在 Activity 里做 */
sealed class WorkbenchAction {
    data class SelectImage(val optionKey: String) : WorkbenchAction()
    object Deploy : WorkbenchAction()
    object Repair : WorkbenchAction()
    object InstallNode : WorkbenchAction()
    object InstallDevtools : WorkbenchAction()
    object OpenTerminal : WorkbenchAction()
    object Diagnose : WorkbenchAction()
    object ToggleMode : WorkbenchAction()
    object StartSetup : WorkbenchAction()
    object GotoConsole : WorkbenchAction()
    object GotoImages : WorkbenchAction()
}

/** 一张镜像卡要显示的事实 */
data class WorkbenchImageItem(
    val key: String,
    val name: String,
    val sub: String,
    val badge: String?,
    val inUse: Boolean,
    val installed: Boolean,
    val usable: Boolean,
)

/**
 * 方案 E 的视图构建器。
 *
 * 只负责「长什么样」与「状态对应的显隐/颜色」，不碰引擎、不碰 IO。
 * 业务动作通过 [onAction] 回给 [LinuxActivity]，画面与命令两个区块由 Activity
 * 注入到 [screenHost] / [consoleHost]（它们要拿 SurfaceView 与 EditText）。
 */
class LinuxWorkbenchView(
    private val context: Context,
    private val onAction: (WorkbenchAction) -> Unit,
) {
    lateinit var screenHost: LinearLayout
        private set
    lateinit var consoleHost: LinearLayout
        private set
    /** 操作区之后的宿主：忙碌进度条等临时控件挂这里 */
    lateinit var actionsHost: LinearLayout
        private set

    private lateinit var root: LinearLayout
    private lateinit var heroCard: FrameLayout
    private lateinit var heroIcon: ImageView
    private lateinit var heroState: TextView
    private lateinit var heroRuntime: TextView
    private lateinit var heroVersion: TextView
    private lateinit var toolsValue: TextView
    private lateinit var diskValue: TextView
    private lateinit var banner: TextView
    private lateinit var bannerBox: FrameLayout
    private lateinit var infoCard: LinearLayout
    private lateinit var infoMoreBtn: TextView
    private lateinit var infoExtra: LinearLayout
    private lateinit var infoRuntimeValue: TextView
    private lateinit var infoVersionValue: TextView
    private lateinit var infoDeviceValue: TextView
    private lateinit var infoFreeValue: TextView
    private lateinit var modeValue: TextView
    private lateinit var modeRestartHint: TextView
    private lateinit var imagesTitle: TextView
    private lateinit var imagesCount: TextView
    private lateinit var imagesBox: LinearLayout
    private lateinit var setupCard: LinearLayout
    private val setupSteps = mutableListOf<StepRow>()
    /** 每张镜像卡的安装进度条（key = family:flavor），Activity 通过 [imageBars] 接管 */
    private val cardBars = mutableMapOf<String, ProgressBar>()

    /** 展开全部工具时底部的「收起」行：只建一次，反复展开不会越堆越多 */
    private var toolsLessRow: TextView? = null

    /** 给 Activity 的 rowProgress 用：单卡安装时只转那一张 */
    val imageBars: Map<String, ProgressBar> get() = cardBars
    private lateinit var setupStart: TextView
    private lateinit var toolsChips: FlowLayout
    private lateinit var toolsCount: TextView
    private lateinit var toolsList: LinearLayout
    private lateinit var actionsBox: LinearLayout
    private lateinit var placeholder: TextView
    private lateinit var faultText: TextView

    private var infoExpanded = false
    private var toolsExpanded = false
    private var dark = false

    private val dp get() = context.resources.displayMetrics.density

    private fun px(v: Int): Int = (v * dp).toInt()

    fun build(): View {
        dark = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(6), px(16), px(24))
        }

        // 标题
        root.addView(TextView(context).apply {
            text = context.getString(R.string.linux_workbench_title)
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text))
            setPadding(px(2), px(12), px(2), px(12))
        })

        buildHero()
        buildBanner()
        buildInfo()
        buildImages()
        buildSetup()
        buildTools()
        buildActions()

        screenHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        consoleHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(screenHost)
        root.addView(consoleHost)

        placeholder = TextView(context).apply {
            text = context.getString(R.string.linux_workbench_placeholder)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
            setPadding(px(18), px(26), px(18), px(26))
            background = rounded(
                ContextCompat.getColor(context, R.color.wb_surface), 22,
                ContextCompat.getColor(context, R.color.wb_stroke),
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = px(14)
            layoutParams = lp
        }
        root.addView(placeholder)

        faultText = TextView(context).apply {
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.wb_fault))
            setPadding(px(4), px(10), px(4), 0)
            visibility = View.GONE
        }
        root.addView(faultText)

        return root
    }

    // ---------- 主网格 ----------
    private fun buildHero() {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        // 左：状态大卡（172dp 高，跨两行）
        heroCard = FrameLayout(context).apply {
            clipToOutline = true
            background = rounded(ContextCompat.getColor(context, R.color.wb_surface), 22)
            layoutParams = LinearLayout.LayoutParams(0, px(172), 1.04f)
        }
        val heroCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(14), px(14), px(14))
        }
        heroState = TextView(context).apply {
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text))
        }
        heroRuntime = TextView(context).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
            setPadding(0, px(3), 0, 0)
        }
        heroVersion = TextView(context).apply {
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
            setPadding(0, px(3), 0, 0)
            maxWidth = px(190)
        }
        heroCol.addView(heroState)
        heroCol.addView(heroRuntime)
        heroCol.addView(heroVersion)
        heroCard.addView(heroCol, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        heroIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_linux_status_mark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(px(118), px(118), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = -px(20)
                bottomMargin = -px(22)
            }
        }
        heroCard.addView(heroIcon)
        grid.addView(heroCard)

        // 右：两张数字卡（各 60dp，整列垂直居中）
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, px(172), 1f).apply { marginStart = px(12) }
        }
        right.addView(metricCard(R.string.linux_workbench_metric_tools) { toolsValue = it })
        right.addView(metricCard(R.string.linux_workbench_metric_disk) { diskValue = it }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, px(60),
        ).apply { topMargin = px(12) })
        grid.addView(right)
        root.addView(grid)
    }

    private fun metricCard(labelRes: Int, keep: (TextView) -> Unit): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), px(8), px(14), px(8))
            background = rounded(ContextCompat.getColor(context, R.color.wb_surface), 22)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(60))
        }
        card.addView(TextView(context).apply {
            text = context.getString(labelRes)
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
        })
        val value = TextView(context).apply {
            text = context.getString(R.string.linux_workbench_value_unknown)
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text))
        }
        card.addView(value)
        keep(value)
        return card
    }

    // ---------- 横幅 ----------
    private fun buildBanner() {
        bannerBox = FrameLayout(context).apply {
            background = rounded(ContextCompat.getColor(context, R.color.wb_surface), 21)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(42))
            lp.topMargin = px(12)
            layoutParams = lp
            isClickable = true
            setOnClickListener { onAction(WorkbenchAction.GotoConsole) }
        }
        banner = TextView(context).apply {
            textSize = 13.5f
            gravity = Gravity.CENTER
            setPadding(px(16), 0, px(16), 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        bannerBox.addView(banner)
        root.addView(bannerBox)
    }

    // ---------- 引擎信息（折叠） ----------
    private fun buildInfo() {
        infoCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ContextCompat.getColor(context, R.color.wb_surface), 22)
            setPadding(px(18), px(16), px(18), px(14))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = px(12)
            layoutParams = lp
        }

        // 引擎模式行（整行可点切换）
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onAction(WorkbenchAction.ToggleMode) }
        }
        modeRow.addView(TextView(context).apply {
            text = context.getString(R.string.linux_workbench_info_mode)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text))
        })
        modeValue = TextView(context).apply {
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
            setPadding(0, px(4), 0, 0)
        }
        modeRow.addView(modeValue)
        modeRestartHint = TextView(context).apply {
            text = context.getString(R.string.linux_workbench_mode_restart)
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(context, R.color.wb_accent_native))
            setPadding(0, px(3), 0, 0)
            visibility = View.GONE
        }
        modeRow.addView(modeRestartHint)
        infoCard.addView(modeRow)

        infoExtra = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        infoRuntimeValue = infoItem(infoExtra, R.string.linux_workbench_info_runtime)
        infoVersionValue = infoItem(infoExtra, R.string.linux_workbench_info_rootfs)
        infoDeviceValue = infoItem(infoExtra, R.string.linux_workbench_info_device)
        infoFreeValue = infoItem(infoExtra, R.string.linux_workbench_info_free)
        // 默认只露运行时一项（其余收起）
        infoCard.addView(infoExtra)
        infoExtra.getChildAt(0).visibility = View.VISIBLE

        infoMoreBtn = TextView(context).apply {
            text = context.getString(R.string.linux_workbench_info_more)
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
            setPadding(0, px(12), 0, px(2))
            isClickable = true
            isFocusable = true
            minimumHeight = px(48)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { setInfoExpanded(!infoExpanded) }
        }
        infoCard.addView(infoMoreBtn)
        root.addView(infoCard)
        setInfoExpanded(false)
    }

    private fun infoItem(parent: LinearLayout, labelRes: Int): TextView {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(context).apply {
            text = context.getString(labelRes)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text))
        })
        val value = TextView(context).apply {
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
            setPadding(0, px(4), 0, 0)
        }
        box.addView(value)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = px(16)
        parent.addView(box, lp)
        return value
    }

    private fun setInfoExpanded(expanded: Boolean) {
        infoExpanded = expanded
        infoMoreBtn.text = context.getString(
            if (expanded) R.string.linux_workbench_info_less else R.string.linux_workbench_info_more,
        )
        for (i in 1 until infoExtra.childCount) {
            infoExtra.getChildAt(i).visibility = if (expanded) View.VISIBLE else View.GONE
        }
    }

    // ---------- 镜像卡牌（两列） ----------
    private fun buildImages() {
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(px(2), px(24), px(2), px(10))
        }
        imagesTitle = TextView(context).apply {
            text = context.getString(R.string.linux_workbench_images_title)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text))
        }
        imagesCount = TextView(context).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
            setPadding(px(8), 0, 0, px(1))
        }
        head.addView(imagesTitle)
        head.addView(imagesCount)
        root.addView(head)

        imagesBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(imagesBox)
    }

    /** 重建镜像卡牌：两列，最多展示 [visible] 张，其余给一个「全部」入口 */
    fun renderImages(items: List<WorkbenchImageItem>, total: Int) {
        imagesBox.removeAllViews()
        cardBars.clear()
        imagesCount.text = context.getString(R.string.linux_workbench_images_count, total)
        items.chunked(2).forEach { rowItems ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { i, item ->
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (i > 0) lp.marginStart = px(10)
                row.addView(imageCard(item), lp)
            }
            if (rowItems.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = px(10) })
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (imagesBox.childCount > 0) lp.topMargin = px(10)
            imagesBox.addView(row, lp)
        }
    }

    private fun imageCard(item: WorkbenchImageItem): View {
        val card = FrameLayout(context).apply {
            minimumHeight = px(84)
            isClickable = true
            isFocusable = true
            contentDescription = item.name + ", " + item.sub
            setOnClickListener { onAction(WorkbenchAction.SelectImage(item.key)) }
        }
        card.background = rounded(
            ContextCompat.getColor(context, R.color.wb_surface), 20,
        )
        // 使用中的卡加一圈 accent 描边（原来 if/else 两个分支同色，等于没区分）
        if (item.inUse && item.usable) {
            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(20).toFloat()
                setColor(ContextCompat.getColor(context, R.color.wb_surface))
                setStroke(px(2), ContextCompat.getColor(context, R.color.wb_accent_linux))
            }
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(13), px(12), px(13), px(12))
        }
        col.addView(TextView(context).apply {
            text = item.name
            // 13.5sp + 46dp 让位：14sp 时 "Ubuntu 24.04" 恰好卡在 ellipsize 边界上
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // 右侧给角标让位（「已安装」「使用中」最宽时约 39dp + 9dp 边距）
            setPadding(0, 0, px(46), 0)
        })
        col.addView(TextView(context).apply {
            text = item.sub
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
            setPadding(0, px(3), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        if (item.badge != null) {
            card.addView(TextView(context).apply {
                text = item.badge
                textSize = 9f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(android.graphics.Color.WHITE)
                setPadding(px(6), px(2), px(6), px(2))
                background = rounded(ContextCompat.getColor(context, R.color.wb_accent_linux), 99)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = px(10); rightMargin = px(9) })
        }
        // 单卡安装进度：只在「这张正在装」时可见
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.wb_accent_linux)
            )
        }
        card.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, px(3), Gravity.BOTTOM,
        ))
        cardBars[item.key] = bar
        if (!item.usable) {
            col.alpha = 0.45f
        }
        return card
    }

    // ---------- 首次配置引导 ----------
    private fun buildSetup() {
        setupCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ContextCompat.getColor(context, R.color.wb_surface), 22)
            setPadding(px(16), px(14), px(16), px(10))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = px(12)
            layoutParams = lp
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(context).apply {
            text = context.getString(R.string.linux_workbench_setup_title)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(context).apply {
            text = context.getString(R.string.linux_workbench_setup_sub)
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
        })
        setupCard.addView(head)

        setupSteps.clear()
        setupSteps.add(stepRow(R.string.linux_workbench_step_choose, R.string.linux_workbench_step_choose_sub, true))
        setupSteps.add(stepRow(R.string.linux_workbench_step_unpack, R.string.linux_workbench_step_unpack_sub, false))
        setupSteps.add(stepRow(R.string.linux_workbench_step_deps, R.string.linux_workbench_step_deps_sub, true))

        setupStart = TextView(context).apply {
            text = context.getString(R.string.linux_workbench_setup_start)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(px(14), px(7), px(14), px(7))
            background = rounded(ContextCompat.getColor(context, R.color.wb_accent_linux), 99)
            isClickable = true
            isFocusable = true
            minimumHeight = px(44)
            minimumWidth = px(64)
            gravity = Gravity.CENTER
            setOnClickListener { onAction(WorkbenchAction.StartSetup) }
        }
        // ★之前只 new 了没挂进任何父容器 —— 按钮永远不可见（真机也点不到）。
        //   挂在引导卡底部，作为「现在就开始」的快捷入口。
        setupCard.addView(setupStart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = px(10) })
        root.addView(setupCard)
    }

    private fun stepRow(titleRes: Int, subRes: Int, withButton: Boolean): StepRow {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(11), 0, px(11))
        }
        val index = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = rounded(ContextCompat.getColor(context, R.color.wb_surface), 99)
        }
        box.addView(index, LinearLayout.LayoutParams(px(22), px(22)))
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(context).apply {
            text = context.getString(titleRes)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
        }
        val desc = TextView(context).apply {
            text = context.getString(subRes)
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
            setPadding(0, px(2), 0, 0)
        }
        col.addView(title)
        col.addView(desc)
        box.addView(col)
        val btn = TextView(context).apply {
            text = context.getString(R.string.linux_workbench_setup_start)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(px(14), px(7), px(14), px(7))
            background = rounded(ContextCompat.getColor(context, R.color.wb_accent_linux), 99)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        if (withButton) {
            box.addView(btn)
        }
        setupCard.addView(box)
        return StepRow(box, index, title, desc, btn, withButton)
    }

    data class StepRow(
        val box: LinearLayout,
        val index: TextView,
        val title: TextView,
        val desc: TextView,
        val btn: TextView,
        val hasButton: Boolean,
    )

    // ---------- 已装工具 chips ----------
    private fun buildTools() {
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(px(2), px(24), px(2), px(10))
        }
        head.addView(TextView(context).apply {
            text = context.getString(R.string.linux_workbench_tools_title)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text))
        })
        toolsCount = TextView(context).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
            setPadding(px(8), 0, 0, px(1))
        }
        head.addView(toolsCount)
        root.addView(head)

        toolsChips = FlowLayout(context).apply {
            background = rounded(ContextCompat.getColor(context, R.color.wb_surface), 22)
            setPadding(px(14), px(14), px(14), px(14))
        }
        root.addView(toolsChips, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        toolsList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ContextCompat.getColor(context, R.color.wb_surface), 22)
            setPadding(px(16), px(6), px(16), px(6))
            visibility = View.GONE
        }
        root.addView(toolsList)
    }

    /** 渲染 chips：最多 [maxChips] 个，其余折成 +N 点开完整列表 */
    fun renderTools(names: List<String>, total: Int, maxChips: Int = 8) {
        toolsChips.removeAllViews()
        toolsCount.text = context.getString(R.string.linux_workbench_tools_count, total)
        val shown = names.take(maxChips)
        shown.forEach { name -> toolsChips.addView(chip(name, clickable = false)) }
        if (names.size > maxChips) {
            val more = TextView(context).apply {
                text = context.getString(R.string.linux_workbench_tools_more, names.size - maxChips)
            }
            val extra = chip("", clickable = true)
            extra.text = more.text
            extra.setTextColor(ContextCompat.getColor(context, R.color.wb_accent_linux))
            extra.setOnClickListener { setToolsExpanded(true) }
            toolsChips.addView(extra)
        }
    }

    private fun chip(text: String, clickable: Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = 11.5f
        typeface = Typeface.MONOSPACE
        setTextColor(ContextCompat.getColor(context, R.color.wb_text))
        setPadding(px(11), px(7), px(11), px(7))
        background = rounded(ContextCompat.getColor(context, R.color.wb_surface), 99)
        isClickable = clickable
        if (clickable) isFocusable = true
        val lp = FlowLayout.LayoutParams()
        lp.setMargins(0, 0, px(8), px(8))
        layoutParams = lp
    }

    fun renderToolList(names: List<String>) {
        toolsList.removeAllViews()
        // removeAllViews 会把收起行一并摘掉，引用必须同步失效，否则下次展开时
        // 往一个已 detach 的 View 上设 visible 永远不会显示。
        toolsLessRow = null
        names.forEach { name ->
            toolsList.addView(TextView(context).apply {
                text = name
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(context, R.color.wb_text))
                setPadding(0, px(9), 0, px(9))
            })
        }
    }

    private fun setToolsExpanded(expanded: Boolean) {
        toolsExpanded = expanded
        toolsChips.visibility = if (expanded) View.GONE else View.VISIBLE
        toolsList.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) {
            // ★只建一次：旧实现每次展开都 addView 一条新的收起行，
            //   展开→收起→展开 会留下两条、三条……整块列表越拉越长。
            val row = toolsLessRow ?: TextView(context).apply {
                text = context.getString(R.string.linux_workbench_tools_less)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
                isClickable = true
                isFocusable = true
                setPadding(0, px(8), 0, px(8))
                setOnClickListener { setToolsExpanded(false) }
            }.also {
                toolsLessRow = it
                toolsList.addView(it)
            }
            row.visibility = View.VISIBLE
        } else {
            toolsLessRow?.visibility = View.GONE
        }
    }

    // ---------- 操作（两行三列） ----------
    private fun buildActions() {
        val head = LinearLayout(context).apply { setPadding(px(2), px(24), px(2), px(10)) }
        head.addView(TextView(context).apply {
            text = context.getString(R.string.linux_actions)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.wb_text))
        })
        root.addView(head)

        actionsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val specs = listOf(
            Triple(R.string.linux_workbench_act_deploy, WorkbenchAction.Deploy, true),
            Triple(R.string.linux_workbench_act_repair, WorkbenchAction.Repair, false),
            Triple(R.string.linux_workbench_act_node, WorkbenchAction.InstallNode, true),
            Triple(R.string.linux_workbench_act_devtools, WorkbenchAction.InstallDevtools, true),
            Triple(R.string.linux_workbench_act_terminal, WorkbenchAction.OpenTerminal, true),
            Triple(R.string.linux_workbench_act_diagnose, WorkbenchAction.Diagnose, true),
        )
        specs.chunked(3).forEachIndexed { rowIndex, rowSpecs ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowSpecs.forEachIndexed { i, spec ->
                val cell = actionCell(spec.first, spec.second)
                val lp = LinearLayout.LayoutParams(0, px(68), 1f)
                if (i > 0) lp.marginStart = px(10)
                row.addView(cell, lp)
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (rowIndex > 0) lp.topMargin = px(10)
            actionsBox.addView(row, lp)
        }
        root.addView(actionsBox)
        actionsHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(actionsHost)
    }

    private fun actionCell(labelRes: Int, action: WorkbenchAction): View = TextView(context).apply {
        text = context.getString(labelRes)
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ContextCompat.getColor(context, R.color.wb_text))
        gravity = Gravity.CENTER
        background = rounded(ContextCompat.getColor(context, R.color.wb_surface), 20)
        isClickable = true
        isFocusable = true
        setOnClickListener { onAction(action) }
    }

    // ---------- 渲染 ----------
    fun render(state: WorkbenchUiState, facts: WorkbenchFacts) {
        val pal = workbenchPalette(context, state.tone, dark)
        val accent = pal.accent

        heroCard.background = rounded(pal.strong, 22)
        // 对勾是 118dp 的大面积图形：纯 accent 白给会像荧光笔，
        // 用低 alpha 让它在深底上退成「印章」而不是「图标」
        heroIcon.setColorFilter(accent)
        heroIcon.alpha = if (dark) 0.30f else 0.22f
        heroState.text = context.getString(
            when {
                state.fault != null -> R.string.linux_workbench_state_fault
                state.phase == Phase.READY && facts.engineRunning -> R.string.linux_workbench_state_running
                state.phase == Phase.READY -> R.string.linux_workbench_state_ready
                state.phase == Phase.PACKAGES -> R.string.linux_workbench_state_missing
                else -> R.string.linux_workbench_state_setup
            }
        )
        heroState.setTextColor(pal.textPrimary)
        heroRuntime.text = state.heroRuntime
        heroRuntime.setTextColor(pal.textSecondary)
        heroVersion.text = state.heroVersion ?: ""
        heroVersion.visibility = if (state.heroVersion == null) View.GONE else View.VISIBLE
        heroVersion.setTextColor(pal.textSecondary)

        toolsValue.text = state.toolCount?.toString() ?: context.getString(R.string.linux_workbench_value_unknown)
        diskValue.text = state.diskBytes?.let { formatBytes(it) } ?: context.getString(R.string.linux_workbench_value_unknown)

        // 横幅
        bannerBox.background = rounded(pal.bannerBg, 21)
        banner.setTextColor(pal.bannerFg)
        banner.text = when (state.banner) {
            BannerKind.CHOOSE_IMAGE -> context.getString(R.string.linux_workbench_banner_setup)
            BannerKind.FIX_DEPENDENCIES -> context.getString(
                R.string.linux_workbench_banner_packages,
                state.bannerArg ?: context.getString(R.string.linux_workbench_value_unknown),
            )
            BannerKind.READY -> context.getString(R.string.linux_workbench_banner_ready)
            BannerKind.FAULT -> context.getString(
                R.string.linux_workbench_banner_fault,
                state.fault ?: "",
            )
            BannerKind.DEPLOYING -> context.getString(R.string.linux_workbench_banner_deploying)
        }
        bannerBox.setOnClickListener {
            if (state.phase == Phase.SETUP) onAction(WorkbenchAction.GotoImages)
            else onAction(WorkbenchAction.GotoConsole)
        }

        // 信息卡
        modeValue.text = context.getString(
            if (state.actualMode == EngineMode.LINUX) R.string.linux_workbench_mode_container
            else R.string.linux_workbench_mode_native,
        )
        modeValue.setTextColor(accent)
        modeRestartHint.visibility = if (state.modeRestartPending) View.VISIBLE else View.GONE
        infoRuntimeValue.text = state.infoRuntime
        infoVersionValue.text = facts.versionLabel ?: context.getString(R.string.linux_workbench_value_unknown)
        infoDeviceValue.text = deviceLine()
        infoFreeValue.text = facts.freeBytes?.let { formatBytes(it) }
            ?: context.getString(R.string.linux_workbench_value_unknown)

        // 引导
        setupCard.visibility = if (state.phase == Phase.READY) View.GONE else View.VISIBLE
        // 「现在就开始」只在还没部署时给；已装但缺依赖时，第 3 步自带的按钮更准
        setupStart.visibility = if (state.phase == Phase.SETUP) View.VISIBLE else View.GONE
        setupSteps.forEachIndexed { index, row ->
            val mark = state.stepState(index)
            row.index.text = if (mark == StepMark.DONE) "\u2713" else (index + 1).toString()
            val current = mark == StepMark.CURRENT
            row.index.background = rounded(
                when {
                    current -> accent
                    mark == StepMark.DONE -> pal.accentSoft
                    else -> blend(pal.textSecondary, pal.surface, 0.10f)
                }, 99,
            )
            row.index.setTextColor(
                when {
                    current -> android.graphics.Color.WHITE
                    mark == StepMark.DONE -> accent
                    else -> pal.textSecondary
                }
            )
            row.title.setTextColor(if (current) pal.textPrimary else pal.textSecondary)
            row.btn.visibility = if (current && row.hasButton) View.VISIBLE else View.GONE
            row.btn.background = rounded(accent, 99)
            row.box.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // 工具区与操作
        toolsChips.background = rounded(pal.surface, 22)
        actionsBox.alpha = if (state.actionsAllowed) 1f else 0.5f
        for (i in 0 until actionsBox.childCount) {
            val row = actionsBox.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                row.getChildAt(j).isEnabled = state.actionsAllowed
            }
        }

        // 画面与命令门控
        screenHost.visibility = if (state.screenVisible) View.VISIBLE else View.GONE
        consoleHost.visibility = if (state.consoleVisible) View.VISIBLE else View.GONE
        placeholder.visibility = if (state.screenVisible) View.GONE else View.VISIBLE

        // ★fault 文案可能为空（BROKEN 但没带 reason）：此时仍要显示兜底文案并置红，
        //   否则页面会装作一切正常、操作也照常放行。
        val faultValue = state.fault ?: when (state.faultKind) {
            FaultKind.BROKEN -> context.getString(R.string.linux_workbench_fault_broken)
            FaultKind.UNAVAILABLE -> context.getString(R.string.linux_workbench_fault_unavailable)
            null -> null
        }
        faultText.visibility = if (faultValue != null) View.VISIBLE else View.GONE
        faultText.text = faultValue ?: ""
    }

    /** 未部署时工具区给一行说明，而不是假的 0 个工具列表 */
    fun renderToolsEmpty() {
        toolsChips.removeAllViews()
        toolsCount.text = context.getString(R.string.linux_workbench_tools_pending)
        toolsChips.addView(TextView(context).apply {
            text = context.getString(R.string.linux_workbench_tools_empty)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_secondary))
        })
    }

    private fun deviceLine(): String {
        val model = android.os.Build.MODEL ?: ""
        val release = android.os.Build.VERSION.RELEASE ?: ""
        return if (model.isBlank()) release else model + " \u00b7 Android " + release
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = px(radiusDp).toFloat()
            setColor(color)
            if (stroke != null) setStroke(px(1), stroke)
        }
}

/**
 * 简易换行布局（chips 用）。
 *
 * 为什么不用 FlexboxLayout：本页最多十来个 chip，为一个换行引入新依赖
 * 不划算；也避免了给 ScrollView 里塞横向滚动的体验问题。
 */
private class FlowLayout(context: Context) : ViewGroup(context) {

    class LayoutParams : ViewGroup.MarginLayoutParams {
        constructor() : super(WRAP_CONTENT, WRAP_CONTENT)
        constructor(source: ViewGroup.LayoutParams) : super(source)
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams()

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): LayoutParams = LayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams): Boolean = p is LayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val avail = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val lp = child.layoutParams as LayoutParams
            val childW = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childH = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + childW > avail) {
                x = 0
                y += lineHeight
                lineHeight = 0
            }
            x += childW
            lineHeight = maxOf(lineHeight, childH)
        }
        setMeasuredDimension(width, y + lineHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val avail = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            val childW = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childH = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + childW > avail) {
                x = 0
                y += lineHeight
                lineHeight = 0
            }
            child.layout(
                paddingLeft + x + lp.leftMargin,
                paddingTop + y + lp.topMargin,
                paddingLeft + x + lp.leftMargin + child.measuredWidth,
                paddingTop + y + lp.topMargin + child.measuredHeight,
            )
            x += childW
            lineHeight = maxOf(lineHeight, childH)
        }
    }
}

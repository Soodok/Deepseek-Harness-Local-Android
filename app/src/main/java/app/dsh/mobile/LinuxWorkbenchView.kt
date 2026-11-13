package app.dsh.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
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
    /** 点「引擎模式」行：弹出运行模式选择，而不是直接翻转开关 */
    object ChooseMode : WorkbenchAction()
    object RestartEngine : WorkbenchAction()
    object StartSetup : WorkbenchAction()
    /** 引导第 3 步：当前镜像缺什么补什么（基础包 / 形态额外包 / node） */
    object FixDependencies : WorkbenchAction()
    /** 长按已安装的镜像卡 */
    data class DeleteImage(val optionKey: String) : WorkbenchAction()
    object GotoConsole : WorkbenchAction()
    object GotoImages : WorkbenchAction()
    object GotoSetup : WorkbenchAction()
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
    /** 系统文件已在盘上，但这个形态要用的软件没装齐（装到一半失败过，或基础包缺失） */
    val partial: Boolean = false,
)

/**
 * 字阶：全页只有这 8 档。
 *
 * 为什么要收敛：旧实现散落着 30 / 26 / 19 / 15 / 14.5 / 14 / 13.5 / 13 / 12.5 / 12 / 11.5 / 11 / 9
 * 共 13 种字号，相邻两张卡的同级文字差 0.5~1sp —— 肉眼说不出哪里不对，但整页「不齐」。
 * 现在每个角色只对应一档，行高跟着字号走（多行文本约 1.3~1.4 倍）。
 *
 * [sp] 字号，[lineSp] 行高（都按 sp 计，随系统字体缩放），[medium] 是否用中等字重。
 */
private enum class TypeScale(val sp: Float, val lineSp: Float, val medium: Boolean) {
    /** 页面大标题 */
    DISPLAY(30f, 36f, true),
    /** 数字卡的大数字 */
    METRIC(24f, 30f, true),
    /** 主卡状态词 */
    HEADLINE(20f, 26f, true),
    /** 分区标题 / 卡片标题 */
    SECTION(16f, 22f, true),
    /** 卡内条目标题（镜像名、信息项、步骤名） */
    TITLE(14f, 20f, true),
    /** 正文与取值 */
    BODY(13f, 18f, false),
    /** 说明、计数、版本号 */
    CAPTION(12f, 16f, false),
    /** 徽章 */
    BADGE(10f, 14f, true),
}

/**
 * 方案 E 的视图构建器。
 *
 * 只负责「长什么样」与「状态对应的显隐/颜色」，不碰引擎、不碰 IO。
 * 业务动作通过 [onAction] 回给 [LinuxActivity]，画面与命令两个区块由 Activity
 * 注入到 [screenHost] / [consoleHost]（它们要拿 SurfaceView 与 EditText）。
 *
 * 视觉规则（2026-11-12 重做，数值集中在 companion 里）：
 *   · 层次靠「双层板」而不是平涂 —— 每张卡是一块上亮下暗的描边板 + 一块上亮下暗的卡面，
 *     深色下阴影不可见，「浮起来」只能靠这条受光的边；
 *   · 圆角只有一档（22dp），胶囊只用于徽章 / chip / 按钮；
 *   · 间距取自一组固定值（见 companion）：页边 16、卡间 12、卡内 16、小卡内 14、
 *     分区上 28 下 12、标题与取值之间 4 —— 同一种关系在全页只有一个数；
 *   · 语义色只出现在需要读的地方（状态点、描边、徽章、当前步骤），不再铺大色块。
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
    private lateinit var heroDot: View
    private lateinit var heroState: TextView
    private lateinit var heroRuntime: TextView
    private lateinit var heroVersion: TextView
    private lateinit var toolsValue: TextView
    private lateinit var diskValue: TextView
    private lateinit var banner: TextView
    private lateinit var bannerDot: View
    private lateinit var bannerBox: FrameLayout
    private lateinit var bannerDetail: TextView
    private lateinit var bannerChevron: ImageView
    private lateinit var bannerFix: TextView
    private lateinit var bannerBar: ProgressBar
    private lateinit var bannerMeter: LinearLayout
    private lateinit var bannerPercent: TextView
    private lateinit var bannerEta: TextView
    private lateinit var failureCard: LinearLayout
    private lateinit var failureTitle: TextView
    private lateinit var failureBody: TextView
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
    private lateinit var imagesHead: View
    private lateinit var imagesHint: TextView
    private lateinit var setupCard: LinearLayout
    private val setupSteps = mutableListOf<StepRow>()
    /** 每张镜像卡的安装进度条（key = family:flavor），Activity 通过 [imageBars] 接管 */
    private val cardBars = mutableMapOf<String, ProgressBar>()

    /** 展开全部工具时卡片底部的分隔线与「收起」行：建一次，之后只切显隐 */
    private lateinit var toolsLessDivider: View
    private lateinit var toolsLessRow: TextView
    /** 最近一次渲染的工具名：展开 / 收起只是换一种排法，不必回 Activity 重新取 */
    private var toolNames: List<String> = emptyList()
    private var toolsMaxChips = 8
    /** 操作区每个入口对应的控件，render 里按动作各自决定能不能点 */
    private val actionCells = mutableMapOf<WorkbenchAction, View>()
    /** 操作格下面那行说明，以及它对应的标题（拼无障碍朗读文字用） */
    private val actionHints = mutableMapOf<WorkbenchAction, TextView>()
    private val actionTitles = mutableMapOf<WorkbenchAction, String>()

    /** 给 Activity 的 rowProgress 用：单卡安装时只转那一张 */
    val imageBars: Map<String, ProgressBar> get() = cardBars

    /** 滚动定位用的锚点：Activity 只需要知道「滚到哪一块」，不需要知道块里是什么 */
    val imagesAnchor: View get() = imagesHead
    val setupAnchor: View get() = setupCard
    private lateinit var toolsChips: FlowLayout
    private lateinit var toolsCount: TextView
    private lateinit var toolsCard: LinearLayout
    private lateinit var actionsBox: LinearLayout
    private lateinit var placeholder: TextView
    private lateinit var faultText: TextView

    private var infoExpanded = false
    private var toolsExpanded = false
    private var dark = false

    /**
     * 与状态无关的中性色（底、卡面、描边、文字）。
     * 用 IDLE 取一次即可：这些字段不随 tone 变，只有 accent 系才需要在 render 里重算。
     */
    private lateinit var base: WorkbenchPalette

    private val dp get() = context.resources.displayMetrics.density

    private fun px(v: Int): Int = (v * dp).toInt()

    /**
     * 发丝线宽：约 0.66dp，且不小于 1 个物理像素。
     * 写死 1dp 在 3x 屏上是 3px，卡片会像被马克笔勾过；写死 1px 在 3x 屏上又细到看不见。
     */
    private val hairline: Int get() = maxOf(1, Math.round(dp * 0.66f))

    /** 实心语义色上的深色字（亮绿 / 亮黄底上白字读不出来） */
    private val ink: Int get() = ContextCompat.getColor(context, R.color.wb_ink)

    private val green: Int get() = ContextCompat.getColor(context, R.color.wb_accent_linux)

    fun build(): View {
        dark = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        base = workbenchPalette(context, HeroTone.IDLE, dark)
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(PAGE_MARGIN), px(8), px(PAGE_MARGIN), px(32))
        }
        attachBackdrop()

        // 标题
        root.addView(label(TypeScale.DISPLAY, base.textPrimary).apply {
            text = context.getString(R.string.linux_workbench_title)
            // 大字号下默认字距偏松，收 1% 更像一个「字标」而不是一行正文
            letterSpacing = -0.01f
            setPadding(px(2), px(16), px(2), px(16))
        })

        buildHero()
        buildBanner()
        buildFailure()
        buildInfo()
        buildImages()
        buildSetup()
        buildTools()
        buildActions()

        screenHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        consoleHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        adoptHosted(screenHost)
        adoptHosted(consoleHost)
        root.addView(screenHost)
        root.addView(consoleHost)

        placeholder = label(TypeScale.BODY, base.textSecondary).apply {
            text = context.getString(R.string.linux_workbench_placeholder)
            gravity = Gravity.CENTER
            setPadding(px(24), px(24), px(24), px(24))
            // 虚线框 = 「这里将来会有东西」。实心卡会被读成一张内容为空的卡片，
            // 用户分不清是没加载出来还是本来就没有。
            background = GradientDrawable().apply {
                cornerRadius = px(CARD_RADIUS).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(hairline, blend(base.bg, base.textSecondary, 0.38f), px(6).toFloat(), px(4).toFloat())
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = px(16)
            layoutParams = lp
        }
        root.addView(placeholder)

        faultText = label(TypeScale.BODY, base.fault).apply {
            setPadding(px(4), px(12), px(4), 0)
            visibility = View.GONE
        }
        root.addView(faultText)

        return root
    }

    /**
     * 页面底色与系统栏。
     *
     * 渐变挂在滚动宿主上而不是内容根上：内容根有两三屏高，渐变铺在它上面会被拉到
     * 几乎看不出，而且会跟着内容滚走。挂在宿主上它就固定在屏幕上 —— 顶部永远略亮，
     * 卡片滚过时始终是「浮在一块有明暗的底板上」。
     *
     * 状态栏跟着取顶部色：基类按全局 R.color.bg 刷系统栏（深色是偏蓝的 #101418），
     * 而这一页的底是中性石墨色，真机截图里状态栏那一条明显是另一种颜色 ——
     * 「背景对不上」最直接的来源就是这条色差。
     *
     * 这两个 setter 在新 SDK 里标了废弃（edge-to-edge 之后不再生效），但本应用 targetSdk=28，
     * 基类 ThemeManager.applyBars 走的也是同一条路；这里与它保持一致。
     */
    @Suppress("DEPRECATION")
    private fun attachBackdrop() {
        (context as? android.app.Activity)?.window?.let { window ->
            window.statusBarColor = base.bgTop
            window.navigationBarColor = base.bg
        }
        // 三个色标 [顶, 底, 底]：上半屏完成过渡，下半屏是纯底色，
        // 避免整屏线性渐变在底部导航条附近留下一截没走完的色差。
        val backdrop = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(base.bgTop, base.bg, base.bg),
        )
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val host = v.parent as? View
                if (host != null) host.background = backdrop else v.background = backdrop
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    /**
     * Activity 注入的「画面 / 命令」两块沿用全局样式（蓝灰卡面 + 全局强调色的小标题），
     * 与本页的中性石墨色放在一起像两个 App 拼起来的。这里在它们挂进来时统一换成本页的
     * 分区标题与卡面 —— 只换外观，不动它们内部的控件与事件。
     */
    private fun adoptHosted(host: LinearLayout) {
        host.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                when (child) {
                    is TextView -> {
                        child.setTextSize(TypedValue.COMPLEX_UNIT_SP, TypeScale.SECTION.sp)
                        child.typeface = MEDIUM
                        child.setTextColor(base.textPrimary)
                        child.setPadding(px(2), px(SECTION_TOP), px(2), px(SECTION_BOTTOM))
                    }
                    is LinearLayout -> child.background = cardPlate()
                    else -> Unit
                }
            }

            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
    }

    // ---------- 主网格 ----------
    private fun buildHero() {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        // 左：状态大卡。高度 = 右侧两张数字卡 + 一个卡间距（68 + 12 + 68），
        // 三张卡上下沿对齐；旧版 172 对 60+12+60，右列比左卡矮 40dp、悬在中间。
        heroCard = FrameLayout(context).apply {
            clipToOutline = true
            background = cardPlate()
            layoutParams = LinearLayout.LayoutParams(0, px(HERO_HEIGHT), 1.04f)
        }
        val heroCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(CARD_PADDING), px(CARD_PADDING), px(CARD_PADDING), px(CARD_PADDING))
        }
        // 状态点 + 状态词：主卡不再整块上色，语义色收进这个 8dp 的点里
        val stateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroDot = View(context)
        stateRow.addView(heroDot, LinearLayout.LayoutParams(px(8), px(8)).apply { marginEnd = px(8) })
        heroState = label(TypeScale.HEADLINE, base.textPrimary).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        stateRow.addView(heroState)
        heroRuntime = label(TypeScale.BODY, base.textSecondary).apply {
            setPadding(0, px(STACK), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // 版本号是三行里最不需要读的一行：降到第三级文字色，并限制单行，
        // 长版本串不会折行压到水印上。
        heroVersion = label(TypeScale.CAPTION, base.textTertiary).apply {
            setPadding(0, px(2), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        heroCol.addView(stateRow)
        heroCol.addView(heroRuntime)
        heroCol.addView(heroVersion)
        heroCard.addView(heroCol, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        // 水印对勾：96dp（原 118dp），出血再多 4dp。旧尺寸下它占了卡片近三分之一的面积，
        // 比状态词更先被看到 —— 它是「印章」，不是主体。
        heroIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_linux_status_mark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(px(96), px(96), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = -px(22)
                bottomMargin = -px(26)
            }
        }
        heroCard.addView(heroIcon)
        grid.addView(heroCard)

        // 右：两张数字卡，等分左卡高度
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, px(HERO_HEIGHT), 1f).apply { marginStart = px(GAP) }
        }
        right.addView(
            metricCard(R.string.linux_workbench_metric_tools) { toolsValue = it },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        right.addView(
            metricCard(R.string.linux_workbench_metric_disk) { diskValue = it },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(GAP) },
        )
        grid.addView(right)
        root.addView(grid)
    }

    private fun metricCard(labelRes: Int, keep: (TextView) -> Unit): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardPlate()
            setPadding(px(CARD_PADDING), 0, px(CARD_PADDING), 0)
        }
        card.addView(label(TypeScale.CAPTION, base.textSecondary).apply {
            text = context.getString(labelRes)
        })
        val value = label(TypeScale.METRIC, base.textPrimary).apply {
            text = context.getString(R.string.linux_workbench_value_unknown)
            // 等宽数字：数值刷新时（39 → 41、86.8 → 101.2）字宽不跳，卡片不抖
            fontFeatureSettings = "tnum"
            maxLines = 1
        }
        card.addView(value)
        keep(value)
        return card
    }

    // ---------- 横幅 ----------
    private fun buildBanner() {
        bannerBox = FrameLayout(context).apply {
            // 最小高度 44 = 2 × 圆角 22：单行时是胶囊，文案折成两行时自然长成圆角卡，
            // 不会像旧版固定 42dp 那样把第二行挤出去。
            minimumHeight = px(44)
            // 底边那条进度条要跟着圆角一起裁，否则两端的直角会戳出圆角外
            clipToOutline = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = px(GAP)
            layoutParams = lp
            isFocusable = true
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(CARD_PADDING), px(10), px(12), px(10))
        }
        bannerDot = View(context)
        row.addView(bannerDot, LinearLayout.LayoutParams(px(6), px(6)))
        // 左对齐 + 行尾箭头：旧版整条居中的色块读起来像一条装饰带，看不出它能点。
        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        banner = label(TypeScale.BODY, base.textPrimary, medium = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(banner)
        // 第二行只在有操作在跑时出现：下载百分比 / 包管理器的最新一行 / 已用时。
        // 等宽数字，秒数每跳一下整行不会跟着左右抖。
        bannerDetail = label(TypeScale.CAPTION, base.textSecondary).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            fontFeatureSettings = "tnum"
            setPadding(0, px(2), 0, 0)
            visibility = View.GONE
        }
        textCol.addView(bannerDetail)
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = px(10)
            marginEnd = px(8)
        })
        // 忙碌时行尾换成读数：上面百分比，下面剩余时间。
        // 等宽数字 + 固定最小宽度：从 9% 走到 10% 时左边的文字不会被挤着动。
        bannerMeter = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
        }
        bannerPercent = label(TypeScale.SECTION, base.textPrimary).apply {
            maxLines = 1
            gravity = Gravity.END
            fontFeatureSettings = "tnum"
            minimumWidth = px(44)
        }
        bannerMeter.addView(bannerPercent)
        bannerEta = label(TypeScale.CAPTION, base.textSecondary).apply {
            maxLines = 1
            gravity = Gravity.END
            fontFeatureSettings = "tnum"
        }
        bannerMeter.addView(bannerEta)
        row.addView(bannerMeter, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        bannerChevron = icon(R.drawable.ic_chevron, base.textSecondary)
        row.addView(bannerChevron, LinearLayout.LayoutParams(px(18), px(18)))
        // 故障时行尾的箭头换成这个实心按钮：「修复」原来只在页面靠下的六宫格里，
        // 而且故障时整组操作都被置灰 —— 环境坏了，唯一该点的按钮恰恰点不了。
        bannerFix = label(TypeScale.CAPTION, base.textPrimary, medium = true).apply {
            text = context.getString(R.string.linux_workbench_banner_fix)
            gravity = Gravity.CENTER
            maxLines = 1
            minimumHeight = px(36)
            minimumWidth = px(56)
            setPadding(px(14), 0, px(14), 0)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { onAction(WorkbenchAction.Repair) }
        }
        row.addView(bannerFix)
        bannerBox.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL,
        ))
        // 忙碌进度条贴着横幅底边：部署 / 装包 / 删除时这一条在动，就知道不是卡死
        bannerBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = PROGRESS_MAX
            visibility = View.GONE
        }
        bannerBox.addView(bannerBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, px(3), Gravity.BOTTOM,
        ))
        root.addView(bannerBox)
    }

    /**
     * 忙碌时横幅上的实时读数。
     *
     * [detail] 是第二行的阶段文字；[fraction] 是 0..1 的完成度，null 表示这一刻量不出来
     * （进度条退回来回滚动、不显示百分比，而不是编一个数）；[eta] 是排好版的剩余时间，null 就不显示。
     * [cardKey] 是正在被操作的那张镜像卡，它底边的进度条跟着走同一个数。
     */
    fun setBusyProgress(detail: String?, fraction: Float?, eta: String?, cardKey: String?) {
        bannerDetail.text = detail ?: ""
        bannerDetail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        applyProgress(bannerBar, fraction)
        cardKey?.let { cardBars[it] }?.let { applyProgress(it, fraction) }
        bannerPercent.text = if (fraction == null) {
            ""
        } else {
            String.format(java.util.Locale.US, "%d%%", (fraction * 100).toInt())
        }
        bannerPercent.visibility = if (fraction == null) View.GONE else View.VISIBLE
        bannerEta.text = eta ?: ""
        bannerEta.visibility = if (eta.isNullOrBlank()) View.GONE else View.VISIBLE
        bannerMeter.visibility = if (fraction == null && eta.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** 操作结束：收起读数，进度条回到来回滚动的样式，下一次操作从头开始而不是接着上次的位置 */
    fun clearBusyProgress() {
        bannerDetail.visibility = View.GONE
        bannerMeter.visibility = View.GONE
        applyProgress(bannerBar, null)
        bannerBar.progress = 0
        cardBars.values.forEach {
            applyProgress(it, null)
            it.progress = 0
        }
    }

    private fun applyProgress(bar: ProgressBar, fraction: Float?) {
        if (fraction == null) {
            if (!bar.isIndeterminate) bar.isIndeterminate = true
            return
        }
        if (bar.isIndeterminate) bar.isIndeterminate = false
        // 带动画走到新位置：读数半秒才来一个，直接跳的话进度条是一格一格蹦的
        bar.setProgress((fraction.coerceIn(0f, 1f) * PROGRESS_MAX).toInt(), true)
    }

    // ---------- 上一次操作的报错 ----------
    /**
     * 失败卡：紧贴横幅下面，原样显示上一次操作的报错。
     *
     * 为什么要有：操作结果原来只写进「命令」区的输出框，而那个区块在环境未就绪时整块隐藏。
     * 部署、装包的失败恰恰都发生在未就绪时 —— 报错写进了一个看不见的控件，
     * 页面上的表现就是「点了没反应」。
     */
    private fun buildFailure() {
        failureCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 同一套双层板，只是描边与卡面带一点故障色：它是一张卡，不是一条警示色带
            background = plate(
                blend(base.surfaceHi, base.fault, 0.08f), blend(base.surface, base.fault, 0.04f),
                blend(base.strokeHi, base.fault, 0.46f), blend(base.stroke, base.fault, 0.30f),
            )
            setPadding(px(CARD_PADDING), px(TILE_PADDING), px(4), px(TILE_PADDING))
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = px(GAP)
            layoutParams = lp
        }
        // 圆点与首行文字的中线对齐：(行高 18 - 圆点 6) / 2
        failureCard.addView(
            View(context).apply { background = pill(base.fault) },
            LinearLayout.LayoutParams(px(6), px(6)).apply { topMargin = px(6) },
        )
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        failureTitle = label(TypeScale.BODY, base.textPrimary, medium = true).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        failureBody = label(TypeScale.CAPTION, base.textSecondary).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, px(STACK), 0, 0)
            maxLines = FAILURE_LINES
            ellipsize = TextUtils.TruncateAt.END
            // 包管理器的报错动辄几十行：默认收成 6 行不把首屏顶走，点一下展开看全
            setOnClickListener {
                maxLines = if (maxLines == FAILURE_LINES) Int.MAX_VALUE else FAILURE_LINES
            }
        }
        col.addView(failureTitle)
        col.addView(failureBody)
        failureCard.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = px(10)
        })
        // 关闭按钮：图标 18dp，触摸区 40dp；向上提 10dp 让图标与首行文字同高
        failureCard.addView(
            icon(R.drawable.ic_close, base.textSecondary).apply {
                contentDescription = context.getString(R.string.close)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                background = pressable(null, 20)
                setPadding(px(11), px(11), px(11), px(11))
                isClickable = true
                isFocusable = true
                setOnClickListener { clearFailure() }
            },
            LinearLayout.LayoutParams(px(40), px(40)).apply { topMargin = -px(10) },
        )
        root.addView(failureCard)
    }

    /** 显示一次失败：首行作标题，其余作正文（等宽，保留包管理器输出的原样排版） */
    fun showFailure(text: String) {
        val clean = text.trim()
        val rest = clean.substringAfter('\n', "").trim()
        failureTitle.text = clean.substringBefore('\n').trim()
        failureBody.text = rest
        failureBody.maxLines = FAILURE_LINES
        failureBody.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        failureCard.visibility = View.VISIBLE
    }

    fun clearFailure() {
        failureCard.visibility = View.GONE
    }

    // ---------- 引擎信息（折叠） ----------
    private fun buildInfo() {
        infoCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardPlate()
            setPadding(px(CARD_PADDING), px(CARD_PADDING), px(CARD_PADDING), 0)
            // 模式行用负外边距把按压水波伸进了内边距区；带内边距的容器默认会把子项
            // 裁到内容区，不关掉的话水波左、上两侧的圆角会被切成直角。
            clipToPadding = false
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = px(GAP)
            layoutParams = lp
        }

        // 引擎模式行（整行可点，弹出运行模式选择）。内边距 + 等量负外边距：文字仍与下面几项左对齐，
        // 但按压水波比文字大一圈，不会贴着字边出现。
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(null, 14)
            setPadding(px(10), px(8), px(6), px(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onAction(WorkbenchAction.ChooseMode) }
        }
        val modeCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        modeCol.addView(label(TypeScale.TITLE, base.textPrimary).apply {
            text = context.getString(R.string.linux_workbench_info_mode)
        })
        modeValue = label(TypeScale.BODY, base.textSecondary).apply {
            setPadding(0, px(STACK), 0, 0)
        }
        modeCol.addView(modeValue)
        modeRestartHint = label(
            TypeScale.CAPTION, ContextCompat.getColor(context, R.color.wb_accent_native),
        ).apply {
            text = context.getString(R.string.linux_workbench_mode_restart)
            setPadding(0, px(STACK), 0, 0)
            visibility = View.GONE
        }
        modeCol.addView(modeRestartHint)
        modeRow.addView(modeCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // 行尾箭头：这一行能点，但旧版与下面只读的信息项长得一模一样
        modeRow.addView(icon(R.drawable.ic_chevron, base.textSecondary), LinearLayout.LayoutParams(px(18), px(18)))
        infoCard.addView(modeRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(-px(10), -px(8), -px(6), -px(8)) })

        infoExtra = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        infoRuntimeValue = infoItem(infoExtra, R.string.linux_workbench_info_runtime)
        infoVersionValue = infoItem(infoExtra, R.string.linux_workbench_info_rootfs)
        infoDeviceValue = infoItem(infoExtra, R.string.linux_workbench_info_device)
        infoFreeValue = infoItem(infoExtra, R.string.linux_workbench_info_free)
        // 默认只露运行时一项（其余收起）
        infoCard.addView(infoExtra)
        infoExtra.getChildAt(0).visibility = View.VISIBLE

        // 分隔线只用在这一处：把「内容」与「展开开关」分成两块。条目之间靠间距区分，不画线。
        infoCard.addView(divider(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, hairline,
        ).apply { topMargin = px(CARD_PADDING) })

        infoMoreBtn = label(TypeScale.BODY, base.textSecondary).apply {
            text = context.getString(R.string.linux_workbench_info_more)
            isClickable = true
            isFocusable = true
            minimumHeight = px(48)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { setInfoExpanded(!infoExpanded) }
        }
        infoCard.addView(infoMoreBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(infoCard)
        setInfoExpanded(false)
    }

    private fun infoItem(parent: LinearLayout, labelRes: Int): TextView {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(TypeScale.TITLE, base.textPrimary).apply {
            text = context.getString(labelRes)
        })
        val value = label(TypeScale.BODY, base.textSecondary).apply {
            setPadding(0, px(STACK), 0, 0)
        }
        box.addView(value)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = px(CARD_PADDING)
        parent.addView(box, lp)
        return value
    }

    private fun setInfoExpanded(expanded: Boolean) {
        infoExpanded = expanded
        infoMoreBtn.text = context.getString(
            if (expanded) R.string.linux_workbench_info_less else R.string.linux_workbench_info_more,
        )
        // 行尾的上下箭头跟着状态翻：只换文字的话，要读完字才知道现在是展开还是收起
        val arrow = trailingArrow(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        infoMoreBtn.setCompoundDrawablesRelative(null, null, arrow, null)
        for (i in 1 until infoExtra.childCount) {
            infoExtra.getChildAt(i).visibility = if (expanded) View.VISIBLE else View.GONE
        }
    }

    // ---------- 镜像卡牌（两列） ----------
    private fun buildImages() {
        imagesTitle = label(TypeScale.SECTION, base.textPrimary).apply {
            text = context.getString(R.string.linux_workbench_images_title)
        }
        imagesCount = label(TypeScale.CAPTION, base.textSecondary)
        imagesHead = sectionHead(imagesTitle, imagesCount)
        root.addView(imagesHead)

        imagesBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(imagesBox)

        // 长按删除没有任何可见的把手，不写这一行就没人知道。只在确实有已装镜像时出现。
        imagesHint = label(TypeScale.CAPTION, base.textTertiary).apply {
            text = context.getString(R.string.linux_workbench_delete_hint)
            setPadding(px(2), px(8), px(2), 0)
            visibility = View.GONE
        }
        root.addView(imagesHint)
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
                if (i > 0) lp.marginStart = px(GAP)
                row.addView(imageCard(item), lp)
            }
            if (rowItems.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = px(GAP) })
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (imagesBox.childCount > 0) lp.topMargin = px(GAP)
            imagesBox.addView(row, lp)
        }
        imagesHint.visibility = if (items.any { it.installed || it.partial }) View.VISIBLE else View.GONE
    }

    private fun imageCard(item: WorkbenchImageItem): View {
        val active = item.inUse && item.usable
        val card = FrameLayout(context).apply {
            // 72dp（原 84dp）：两行文字 + 上下各 14dp 正好撑满，旧高度下半张卡是空的
            minimumHeight = px(72)
            isClickable = true
            isFocusable = true
            // 进度条贴着卡片底边，要跟着圆角一起裁，否则两端的直角会戳出圆角外
            clipToOutline = true
            contentDescription = item.name + ", " + item.sub
            setOnClickListener { onAction(WorkbenchAction.SelectImage(item.key)) }
            // 长按删除只挂在已装的卡上：没装的卡长按没有事可做，挂了监听只会白震一下，
            // 让人以为有菜单但没弹出来。
            // 「未装完」的卡同样占着磁盘（系统文件已经解包），也要能删
            if (item.installed || item.partial) {
                setOnLongClickListener {
                    onAction(WorkbenchAction.DeleteImage(item.key))
                    true
                }
            }
        }
        // 使用中的卡：同一套双层板，只把描边与卡面换成带绿的版本。
        // 旧版是 2dp 纯绿粗框套在同色卡面上，像一个选中框；现在是一张「被点亮」的卡。
        card.background = pressable(
            if (active) {
                plate(
                    blend(base.surfaceHi, green, 0.12f), blend(base.surface, green, 0.05f),
                    blend(base.strokeHi, green, 0.72f), blend(base.stroke, green, 0.46f),
                    strokePx = maxOf(hairline, px(1)),
                )
            } else {
                cardPlate()
            },
            CARD_RADIUS,
        )
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(TILE_PADDING), px(TILE_PADDING), px(TILE_PADDING), px(TILE_PADDING))
        }
        // 标题行是一个横向 LinearLayout：它默认按文字基线对齐子项，
        // 所以徽章里的小字与镜像名落在同一条基线上。旧版把徽章用绝对位置钉在右上角，
        // 再给标题留 46dp 右边距让位 —— 徽章换个语言变宽就会压字，没有徽章时又白白空着。
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(label(TypeScale.TITLE, base.textPrimary).apply {
            text = item.name
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (item.badge != null) {
            // 两种徽章两个音量：「使用中」是实心胶囊，「已安装 / 推荐」是浅底描边胶囊。
            // 旧版三种状态全是同一个实心绿块，一屏四五个，分不出哪张才是当前的。
            val solid = item.inUse && item.installed
            // 「未装完」用中性灰：它不是一种可用状态，不能和「已安装」一样是绿的。
            // 同一发行版的三种形态共用一份系统文件，只装了基础版时另外两张卡不该跟着变绿。
            val muted = item.partial && !item.installed
            val badgeColor = when {
                solid -> readableOn(green, ink)
                muted -> base.textSecondary
                else -> green
            }
            head.addView(label(TypeScale.BADGE, badgeColor).apply {
                text = item.badge
                maxLines = 1
                setPadding(px(8), px(2), px(8), px(2))
                background = when {
                    solid -> pill(green)
                    muted -> pill(base.chipBg, base.chipStroke)
                    else -> pill(blend(base.surface, green, 0.14f), blend(base.surface, green, 0.42f))
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = px(8) })
        }
        col.addView(head)
        col.addView(label(TypeScale.CAPTION, base.textSecondary).apply {
            text = item.sub
            setPadding(0, px(STACK), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL,
        ))
        // 单卡安装进度：只在「这张正在装」时可见
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = PROGRESS_MAX
            visibility = View.GONE
            // 不定进度条走的是 indeterminateTint；原来只设了 progressTint，
            // 转起来时用的还是系统默认色（与本页的绿不是一个绿）。
            indeterminateTintList = ColorStateList.valueOf(green)
            progressTintList = ColorStateList.valueOf(green)
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
            background = cardPlate()
            setPadding(px(CARD_PADDING), px(CARD_PADDING), px(CARD_PADDING), px(CARD_PADDING))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = px(GAP)
            layoutParams = lp
        }
        // 标题与副标题按基线对齐（横向 LinearLayout 的默认行为），不再按盒子居中 ——
        // 16sp 与 12sp 按盒子居中时小字会比大字「浮」起来一点。
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, px(4))
        }
        head.addView(label(TypeScale.SECTION, base.textPrimary).apply {
            text = context.getString(R.string.linux_workbench_setup_title)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(label(TypeScale.CAPTION, base.textSecondary).apply {
            text = context.getString(R.string.linux_workbench_setup_sub)
        })
        setupCard.addView(head)

        setupSteps.clear()
        // 「开始」跟着当前步骤走，一步一个：选镜像 → 部署当前选中的镜像；解压没有按钮（它是部署的一部分）；
        // 补依赖 → 装包。旧版行内的按钮没挂点击，卡片底部又另放了一个一模一样的「开始」——
        // 两个长得一样的按钮，一个点了没反应。现在只留行内这一个。
        setupSteps.add(stepRow(
            R.string.linux_workbench_step_choose, R.string.linux_workbench_step_choose_sub, WorkbenchAction.StartSetup,
        ))
        setupSteps.add(stepRow(R.string.linux_workbench_step_unpack, R.string.linux_workbench_step_unpack_sub, null))
        setupSteps.add(stepRow(
            R.string.linux_workbench_step_deps, R.string.linux_workbench_step_deps_sub, WorkbenchAction.FixDependencies,
        ))

        root.addView(setupCard)
    }

    private fun stepRow(titleRes: Int, subRes: Int, action: WorkbenchAction?): StepRow {
        // 步骤之间用发丝线分开（第一步上面不画）：三行都是「序号 + 两行字」，
        // 只靠间距时相邻两步的副行与标题会读成一组。
        if (setupSteps.isNotEmpty()) {
            setupCard.addView(divider(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, hairline,
            ).apply { marginStart = px(36) })
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12), 0, px(12))
        }
        val index = label(TypeScale.CAPTION, base.textSecondary, medium = true).apply {
            gravity = Gravity.CENTER
            background = pill(base.chipBg, base.chipStroke)
        }
        box.addView(index, LinearLayout.LayoutParams(px(24), px(24)))
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, px(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = label(TypeScale.TITLE, base.textSecondary).apply {
            text = context.getString(titleRes)
        }
        val desc = label(TypeScale.CAPTION, base.textSecondary).apply {
            text = context.getString(subRes)
            setPadding(0, px(2), 0, 0)
        }
        col.addView(title)
        col.addView(desc)
        box.addView(col)
        val btn = label(TypeScale.CAPTION, readableOn(green, ink), medium = true).apply {
            text = context.getString(R.string.linux_workbench_setup_start)
            gravity = Gravity.CENTER
            minimumHeight = px(36)
            setPadding(px(14), 0, px(14), 0)
            background = pill(green)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        if (action != null) {
            // ★这个按钮之前只建了、没挂点击：第 3 步的「开始」点下去什么都不会发生
            btn.setOnClickListener { onAction(action) }
            box.addView(btn)
        }
        setupCard.addView(box)
        return StepRow(box, index, title, desc, btn, action != null)
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
        toolsCount = label(TypeScale.CAPTION, base.textSecondary)
        root.addView(sectionHead(
            label(TypeScale.SECTION, base.textPrimary).apply {
                text = context.getString(R.string.linux_workbench_tools_title)
            },
            toolsCount,
        ))

        // 卡面挂在外层竖排容器上：收起时里面只有 chips；展开后下面多一条分隔线和「收起」行，
        // 与上面引擎信息卡的「全部信息 / 收起」是同一个形态。
        toolsCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardPlate()
        }
        toolsChips = FlowLayout(context).apply {
            // 右、下各少 8dp：每个 chip 自带 8dp 的右/下外边距（用来和邻居拉开），
            // 四边都给 16 的话，视觉上右边与下边会是 24 —— 旧版卡片底部那块多出来的空白就是这么来的。
            setPadding(px(CARD_PADDING), px(CARD_PADDING), px(CARD_PADDING - 8), px(CARD_PADDING - 8))
        }
        toolsCard.addView(toolsChips, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        toolsLessDivider = divider()
        toolsCard.addView(toolsLessDivider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, hairline,
        ).apply {
            marginStart = px(CARD_PADDING)
            marginEnd = px(CARD_PADDING)
        })
        toolsLessRow = label(TypeScale.BODY, base.textSecondary).apply {
            text = context.getString(R.string.linux_workbench_tools_less)
            isClickable = true
            isFocusable = true
            minimumHeight = px(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(CARD_PADDING), 0, px(CARD_PADDING), 0)
            setCompoundDrawablesRelative(null, null, trailingArrow(R.drawable.ic_expand_less), null)
            setOnClickListener { setToolsExpanded(false) }
        }
        toolsCard.addView(toolsLessRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(toolsCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        layoutTools()
    }

    /**
     * 渲染 chips。收起时最多 [maxChips] 个，其余折成「+N」；展开后仍是同一组 chips，只是不再截断。
     *
     * 旧版展开会换成一张一行一个包的长列表：四十个包就是四十行，比整页其它内容加起来还长。
     */
    fun renderTools(names: List<String>, total: Int, maxChips: Int = 8) {
        toolNames = names
        toolsMaxChips = maxChips
        toolsCount.text = context.getString(R.string.linux_workbench_tools_count, total)
        // 少到不需要折叠时退回收起态，否则会留下一条什么也收不了的「收起」
        if (names.size <= maxChips) toolsExpanded = false
        layoutTools()
    }

    private fun layoutTools() {
        toolsChips.removeAllViews()
        val hidden = toolNames.size - toolsMaxChips
        val shown = if (toolsExpanded) toolNames else toolNames.take(toolsMaxChips)
        shown.forEach { name -> toolsChips.addView(chip(name, clickable = false)) }
        if (!toolsExpanded && hidden > 0) {
            val extra = chip(context.getString(R.string.linux_workbench_tools_more, hidden), clickable = true)
            // 「+N」是这一组里唯一能点的：换成带绿的浅底胶囊，和只读的工具名区分开
            extra.setTextColor(green)
            extra.typeface = MEDIUM
            extra.background = pressable(
                pill(blend(base.surface, green, 0.14f), blend(base.surface, green, 0.42f)), 999,
            )
            extra.setOnClickListener { setToolsExpanded(true) }
            toolsChips.addView(extra)
        }
        val less = if (toolsExpanded) View.VISIBLE else View.GONE
        toolsLessDivider.visibility = less
        toolsLessRow.visibility = less
    }

    private fun chip(name: String, clickable: Boolean): TextView =
        label(TypeScale.CAPTION, base.textPrimary).apply {
            text = name
            typeface = Typeface.MONOSPACE
            maxLines = 1
            setPadding(px(12), px(6), px(12), px(6))
            // chip 底色比卡面亮一档并带发丝描边。旧版直接拿卡面色画 chip ——
            // chip 与卡片同色，真机上只剩一堆悬空的字，看不出是 chip。
            background = pill(base.chipBg, base.chipStroke)
            isClickable = clickable
            if (clickable) isFocusable = true
            val lp = FlowLayout.LayoutParams()
            lp.setMargins(0, 0, px(8), px(8))
            layoutParams = lp
        }

    private fun setToolsExpanded(expanded: Boolean) {
        toolsExpanded = expanded
        layoutTools()
    }

    /** 文字行尾的小箭头（展开 / 收起） */
    private fun trailingArrow(res: Int): Drawable? =
        ContextCompat.getDrawable(context, res)?.mutate()?.apply {
            setBounds(0, 0, px(18), px(18))
            setTint(base.textSecondary)
        }

    // ---------- 操作（两行三列） ----------
    private fun buildActions() {
        root.addView(sectionHead(
            label(TypeScale.SECTION, base.textPrimary).apply { text = context.getString(R.string.linux_actions) },
            null,
        ))

        actionsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        actionCells.clear()
        actionHints.clear()
        actionTitles.clear()
        // 每格补一个图标：六个只有两三个字的纯文字方块排在一起，要逐个读字才找得到目标；
        // 图标让「终端」「诊断」这类高频入口靠形状就能定位。
        // 每格再带一行说明：只有「Node」「工具链」两个字时，第一次进来的人不知道点下去是要联网下载几十 MB。
        // Node 与工具链的说明由 Activity 按实际情况改写（下载多大 / 已安装），见 setActionHint。
        val specs = listOf(
            ActionSpec(
                R.string.linux_workbench_act_deploy, R.string.linux_workbench_act_hint_deploy,
                WorkbenchAction.Deploy, R.drawable.ic_download,
            ),
            // 重装换成「回退」图标：环形箭头留给下面的「重启引擎」，两个入口不能长得一样
            ActionSpec(
                R.string.linux_workbench_act_repair, R.string.linux_workbench_act_hint_repair,
                WorkbenchAction.Repair, R.drawable.ic_restore,
            ),
            ActionSpec(
                R.string.linux_workbench_act_node, R.string.linux_workbench_act_hint_needs,
                WorkbenchAction.InstallNode, R.drawable.ic_puzzle,
            ),
            ActionSpec(
                R.string.linux_workbench_act_devtools, R.string.linux_workbench_act_hint_needs,
                WorkbenchAction.InstallDevtools, R.drawable.ic_ext_make,
            ),
            ActionSpec(
                R.string.linux_workbench_act_terminal, R.string.linux_workbench_act_hint_terminal,
                WorkbenchAction.OpenTerminal, R.drawable.ic_terminal,
            ),
            ActionSpec(
                R.string.linux_workbench_act_diagnose, R.string.linux_workbench_act_hint_diagnose,
                WorkbenchAction.Diagnose, R.drawable.ic_health,
            ),
        )
        specs.chunked(3).forEachIndexed { rowIndex, rowSpecs ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowSpecs.forEachIndexed { i, spec ->
                val cell = actionCell(spec)
                actionCells[spec.action] = cell
                val lp = LinearLayout.LayoutParams(0, px(CELL_HEIGHT), 1f)
                if (i > 0) lp.marginStart = px(GAP)
                row.addView(cell, lp)
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (rowIndex > 0) lp.topMargin = px(GAP)
            actionsBox.addView(row, lp)
        }
        root.addView(actionsBox)

        // 重启引擎：单独一条通栏行，不挤进上面的六宫格。六格都是「对容器环境做什么」，
        // 重启是「对引擎做什么」，而且它得带一行说明（模式切换与新装的软件靠它生效），方格里放不下。
        val restart = actionRow(
            R.string.linux_workbench_restart_title, R.string.linux_workbench_restart_sub,
            WorkbenchAction.RestartEngine, R.drawable.ic_restart,
        )
        actionCells[WorkbenchAction.RestartEngine] = restart
        root.addView(restart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = px(GAP) })

        actionsHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(actionsHost)
    }

    private class ActionSpec(val label: Int, val hint: Int, val action: WorkbenchAction, val icon: Int)

    private fun actionCell(spec: ActionSpec): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pressable(cardPlate(), CARD_RADIUS)
            // 左右留 6dp：说明行在德语、俄语下会顶到卡片的圆角
            setPadding(px(6), 0, px(6), 0)
            isClickable = true
            isFocusable = true
            val title = context.getString(spec.label)
            val hintText = context.getString(spec.hint)
            contentDescription = title + ", " + hintText
            setOnClickListener { onAction(spec.action) }
            // 图标用中性色而不是强调色：这一页的颜色只留给「状态」，
            // 六个彩色图标会和主卡、徽章抢同一种注意力。
            addView(
                icon(spec.icon, base.textPrimary).apply { alpha = 0.82f },
                LinearLayout.LayoutParams(px(22), px(22)),
            )
            addView(label(TypeScale.CAPTION, base.textPrimary, medium = true).apply {
                text = title
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = px(6) })
            // 说明行比标题低两档（更小、更淡）：它是给第一次来的人看的，熟手扫一眼标题就够
            val hint = label(TypeScale.BADGE, base.textSecondary, medium = false).apply {
                text = hintText
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(hint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = px(2) })
            actionHints[spec.action] = hint
            actionTitles[spec.action] = title
        }

    /** 改某个操作格的说明行（例如 Node 装好后从「下载 24 MB」换成「已安装」） */
    fun setActionHint(action: WorkbenchAction, text: String) {
        val hint = actionHints[action] ?: return
        if (hint.text?.toString() == text) return
        hint.text = text
        actionCells[action]?.contentDescription = (actionTitles[action] ?: "") + ", " + text
    }

    /** 通栏操作行：图标 + 标题 + 一行说明，给需要解释后果的入口用 */
    private fun actionRow(titleRes: Int, subRes: Int, action: WorkbenchAction, iconRes: Int): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(cardPlate(), CARD_RADIUS)
            setPadding(px(CARD_PADDING), px(TILE_PADDING), px(CARD_PADDING), px(TILE_PADDING))
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(titleRes) + ", " + context.getString(subRes)
            setOnClickListener { onAction(action) }
            addView(
                icon(iconRes, base.textPrimary).apply { alpha = 0.82f },
                LinearLayout.LayoutParams(px(22), px(22)),
            )
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(TypeScale.TITLE, base.textPrimary).apply {
                text = context.getString(titleRes)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            col.addView(label(TypeScale.CAPTION, base.textSecondary).apply {
                text = context.getString(subRes)
                setPadding(0, px(2), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(12)
            })
        }

    // ---------- 渲染 ----------
    fun render(state: WorkbenchUiState, facts: WorkbenchFacts) {
        val pal = workbenchPalette(context, state.tone, dark)
        val accent = pal.accent
        // 故障文案可能为空（损坏但没带原因）：用种类取兜底文案，下面主卡、横幅、页尾都按它判故障，
        // 否则页面会装作一切正常。
        val faultValue = state.fault ?: when (state.faultKind) {
            FaultKind.BROKEN -> context.getString(R.string.linux_workbench_fault_broken)
            FaultKind.UNAVAILABLE -> context.getString(R.string.linux_workbench_fault_unavailable)
            null -> null
        }
        val inFault = faultValue != null

        // 主卡：左上受光角带一点语义色、右下退回中性卡面，描边带 accent。
        // 旧版整块平涂 surface+10% accent，黄色在近黑上是一块军绿土色。
        heroCard.background = plate(
            pal.heroTop, pal.heroBottom, pal.heroStrokeTop, pal.heroStrokeBottom,
            orientation = GradientDrawable.Orientation.TL_BR,
        )
        heroDot.background = pill(accent)
        // 水印透明度再压一档（深 0.30 → 0.16，浅 0.22 → 0.12）：
        // 它只需要在余光里「有一个对勾」，不能比状态词先被看到。
        heroIcon.setColorFilter(accent)
        heroIcon.alpha = if (dark) 0.16f else 0.12f
        heroState.text = context.getString(
            when {
                inFault -> R.string.linux_workbench_state_fault
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
        heroVersion.setTextColor(pal.textTertiary)

        toolsValue.text = state.toolCount?.toString() ?: context.getString(R.string.linux_workbench_value_unknown)
        diskValue.text = state.diskBytes?.let { withSmallUnit(formatBytes(it), pal.textSecondary) }
            ?: context.getString(R.string.linux_workbench_value_unknown)

        // 横幅：浅底 + 带 accent 的描边 + 状态点，代替旧版的实心色条
        bannerBox.background = pressable(pill(pal.bannerBg, pal.bannerStroke, CARD_RADIUS), CARD_RADIUS)
        bannerDot.background = pill(accent)
        banner.setTextColor(pal.bannerFg)
        val unknown = context.getString(R.string.linux_workbench_value_unknown)
        banner.text = when (state.banner) {
            BannerKind.CHOOSE_IMAGE -> context.getString(R.string.linux_workbench_banner_setup)
            // ★空串也要兜底（v1.7.13）：参数为 null 时会取 unknown，但为 "" 时
            // 原样拼进模板 —— 真机实测渲染成「补齐一后即可使用」（中间空一格），
            // 用户完全不知道要补什么。凡是空白一律当作「未知」。
            BannerKind.FIX_DEPENDENCIES -> context.getString(
                R.string.linux_workbench_banner_packages,
                state.bannerArg?.takeIf { it.isNotBlank() } ?: unknown,
            )
            BannerKind.READY -> context.getString(R.string.linux_workbench_banner_ready)
            BannerKind.FAULT -> context.getString(R.string.linux_workbench_banner_fault, faultValue ?: "")
            BannerKind.DEPLOYING -> context.getString(R.string.linux_workbench_banner_deploying)
            BannerKind.INSTALLING -> context.getString(
                R.string.linux_workbench_banner_installing, state.bannerArg ?: unknown,
            )
            BannerKind.REMOVING -> context.getString(
                R.string.linux_workbench_banner_removing, state.bannerArg ?: unknown,
            )
        }
        // 有操作在跑时，横幅就是进度条：底边一条进度 + 第二行阶段文字 + 行尾百分比与剩余时间（见 setBusyProgress）
        bannerBar.visibility = if (state.busy) View.VISIBLE else View.GONE
        bannerBar.indeterminateTintList = ColorStateList.valueOf(accent)
        bannerBar.progressTintList = ColorStateList.valueOf(accent)
        // 轨道用很淡的同色：系统默认的灰轨道在带色的横幅上像一条脏边
        bannerBar.progressBackgroundTintList = ColorStateList.valueOf(blend(pal.bannerBg, accent, 0.22f))
        bannerPercent.setTextColor(pal.bannerFg)
        if (!state.busy) {
            bannerDetail.visibility = View.GONE
            bannerMeter.visibility = View.GONE
        }
        val showFix = inFault && !state.busy && state.repairAllowed
        bannerFix.visibility = if (showFix) View.VISIBLE else View.GONE
        bannerFix.background = pressable(pill(accent), 999)
        bannerFix.setTextColor(pal.onAccent)
        bannerChevron.visibility = if (state.busy || showFix) View.GONE else View.VISIBLE
        // 点横幅去「下一步该去的地方」：故障看诊断，没装去选镜像，缺依赖去引导卡，就绪去命令行。
        // 旧版缺依赖也发 GotoConsole —— 而命令区在缺依赖时是隐藏的，等于滚向一个不存在的目标。
        bannerBox.setOnClickListener {
            onAction(
                when {
                    inFault -> WorkbenchAction.Diagnose
                    state.phase == Phase.SETUP -> WorkbenchAction.GotoImages
                    state.phase == Phase.PACKAGES -> WorkbenchAction.GotoSetup
                    else -> WorkbenchAction.GotoConsole
                }
            )
        }
        // 忙碌时不响应点击：此刻把人带去别处，回头就找不到进度了。
        // 必须写在 setOnClickListener 之后 —— 它会顺手把 isClickable 置回 true。
        bannerBox.isClickable = !state.busy

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
        setupSteps.forEachIndexed { index, row ->
            val mark = state.stepState(index)
            row.index.text = if (mark == StepMark.DONE) "\u2713" else (index + 1).toString()
            val current = mark == StepMark.CURRENT
            row.index.background = when {
                current -> pill(accent)
                mark == StepMark.DONE -> pill(pal.accentSoft)
                // 未到的步骤用 chip 底。旧写法 blend(textSecondary, surface, 0.10) 把方向写反了，
                // 得到的是接近文字色的灰块，上面再压同色数字 —— 序号几乎看不见。
                else -> pill(pal.chipBg, pal.chipStroke)
            }
            row.index.setTextColor(
                when {
                    // 实心 accent 上的字按亮度取深/浅：缺依赖态是亮黄底，白字读不出来
                    current -> pal.onAccent
                    mark == StepMark.DONE -> accent
                    else -> pal.textSecondary
                }
            )
            row.title.setTextColor(if (current) pal.textPrimary else pal.textSecondary)
            row.btn.visibility = if (current && row.hasButton) View.VISIBLE else View.GONE
            row.btn.background = pressable(pill(accent), 999)
            row.btn.isEnabled = !state.busy
            row.btn.alpha = if (state.busy) DISABLED_ALPHA else 1f
            row.btn.setTextColor(pal.onAccent)
            row.box.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // 操作：按入口各自判，不再整组一起置灰。
        // 旧版只看 actionsAllowed —— 故障时连「重装」「诊断」也点不了，而这两个恰恰是故障时该点的。
        actionCells.forEach { (action, cell) ->
            val enabled = when (action) {
                WorkbenchAction.Diagnose -> true
                WorkbenchAction.Repair -> state.repairAllowed
                WorkbenchAction.OpenTerminal, WorkbenchAction.RestartEngine -> !state.busy
                else -> state.actionsAllowed
            }
            cell.isEnabled = enabled
            // 终端在容器没就绪时变暗但仍可点：点下去会说明还差什么。
            // 直接禁用的话只剩一个按不动的灰块，看不出要先做完上面哪一步。
            val dim = !enabled || (action == WorkbenchAction.OpenTerminal && !state.terminalReady)
            cell.alpha = if (dim) DISABLED_ALPHA else 1f
        }

        // 画面与命令门控
        screenHost.visibility = if (state.screenVisible) View.VISIBLE else View.GONE
        consoleHost.visibility = if (state.consoleVisible) View.VISIBLE else View.GONE
        placeholder.visibility = if (state.screenVisible) View.GONE else View.VISIBLE

        faultText.visibility = if (faultValue != null) View.VISIBLE else View.GONE
        faultText.text = faultValue ?: ""
    }

    /** 未部署时工具区给一行说明，而不是假的 0 个工具列表 */
    fun renderToolsEmpty() {
        toolNames = emptyList()
        toolsExpanded = false
        layoutTools()
        toolsCount.text = context.getString(R.string.linux_workbench_tools_pending)
        toolsChips.addView(label(TypeScale.BODY, base.textSecondary).apply {
            text = context.getString(R.string.linux_workbench_tools_empty)
            // 与 chip 同样的右/下外边距：容器为 chip 少留了 8dp，说明文字要补回来才四边等距
            layoutParams = FlowLayout.LayoutParams().apply { setMargins(0, 0, px(8), px(8)) }
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

    /**
     * 「86.8 MB」里的单位缩到 0.54 倍（约 13sp）并降成次级色。
     * 数字和单位同为 24sp 时，两个大写字母和数字一样重，扫一眼读到的是「MB」而不是「86.8」。
     */
    private fun withSmallUnit(value: String, unitColor: Int): CharSequence {
        val cut = value.lastIndexOf(' ')
        if (cut <= 0) return value
        return SpannableString(value).apply {
            setSpan(RelativeSizeSpan(0.54f), cut, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(unitColor), cut, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // ---------- 视觉原件 ----------

    /** 按字阶建一个 TextView：字号、字重、行高一次配齐，调用处不再各写各的 textSize */
    private fun label(type: TypeScale, color: Int, medium: Boolean = type.medium): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, type.sp)
            typeface = if (medium) MEDIUM else Typeface.DEFAULT
            setTextColor(color)
            // 行高必须在字号、字重之后设：它是按当前画笔的字体度量换算成行间距的
            TextViewCompat.setLineHeight(
                this,
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, type.lineSp, context.resources.displayMetrics,
                ).toInt(),
            )
        }

    /** 分区标题行：标题与计数按基线对齐（旧版按底边对齐再手动垫 1dp，换字体就歪） */
    private fun sectionHead(title: TextView, trailing: TextView?): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(2), px(SECTION_TOP), px(2), px(SECTION_BOTTOM))
            addView(title)
            if (trailing != null) {
                addView(trailing, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = px(8) })
            }
        }

    private fun icon(res: Int, tint: Int): ImageView = ImageView(context).apply {
        setImageResource(res)
        // 项目图标各自写死了填充色（icon_color / text_secondary / 白），这里统一盖成本页的颜色
        setColorFilter(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun divider(): View = View(context).apply { setBackgroundColor(base.divider) }

    /**
     * 双层板：外层是上亮下暗的「描边板」，内层是缩进一个线宽、上亮下暗的「卡面」。
     *
     * 为什么不用 GradientDrawable.setStroke：它只能给单色描边，而深色界面里卡片的立体感
     * 恰恰来自「上沿比下沿亮」。为什么不用 elevation：行容器会裁掉阴影，深色下阴影也不可见。
     */
    private fun plate(
        top: Int,
        bottom: Int,
        strokeTop: Int,
        strokeBottom: Int,
        radiusDp: Int = CARD_RADIUS,
        strokePx: Int = hairline,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TOP_BOTTOM,
    ): Drawable {
        val radius = px(radiusDp).toFloat()
        val rim = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(strokeTop, strokeBottom),
        ).apply { cornerRadius = radius }
        val face = GradientDrawable(orientation, intArrayOf(top, bottom)).apply {
            // 内层圆角 = 外层圆角 - 线宽，两条弧才同心；用同一个半径的话四角的描边会变粗
            cornerRadius = (radius - strokePx).coerceAtLeast(0f)
        }
        return LayerDrawable(arrayOf(rim, face)).apply {
            setLayerInset(1, strokePx, strokePx, strokePx, strokePx)
        }
    }

    /** 中性卡面：全页所有不带语义的卡片都是这一块 */
    private fun cardPlate(): Drawable =
        plate(base.surfaceHi, base.surface, base.strokeHi, base.stroke)

    /** 单色圆角块；默认是胶囊（徽章 / chip / 按钮 / 圆点），可选发丝描边 */
    private fun pill(color: Int, stroke: Int? = null, radiusDp: Int = 999): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = px(radiusDp).toFloat()
            setColor(color)
            if (stroke != null) setStroke(hairline, stroke)
        }

    /**
     * 给可点的块加按压水波。
     * 旧版所有卡片、按钮点下去没有任何变化，分不清「没点到」和「点到了在等」。
     * 遮罩用与内容同圆角的形状，水波不会溢出圆角。
     */
    private fun pressable(content: Drawable?, radiusDp: Int): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(base.ripple),
            content,
            GradientDrawable().apply {
                cornerRadius = px(radiusDp).toFloat()
                setColor(Color.WHITE)
            },
        )

    private companion object {
        /** 全页唯一的卡片圆角。旧版 22 / 21 / 20 三种混用，相邻两张卡的角不一样圆 */
        const val CARD_RADIUS = 22
        /** 页边距 */
        const val PAGE_MARGIN = 16
        /** 卡与卡之间（纵向、横向同一个值；旧版主网格 12、镜像与操作网格 10） */
        const val GAP = 12
        /** 大卡内边距 */
        const val CARD_PADDING = 16
        /** 两列小卡内边距 */
        const val TILE_PADDING = 14
        /** 分区标题上 / 下留白：上面远、下面近，标题才归属于它下面那组卡 */
        const val SECTION_TOP = 28
        const val SECTION_BOTTOM = 12
        /** 标题与它下面那行取值之间 */
        const val STACK = 4
        /** 主卡高度 = 2 × 68（数字卡）+ GAP */
        const val HERO_HEIGHT = 148
        /** 失败卡正文默认露出的行数 */
        const val FAILURE_LINES = 6
        /** 不可用入口的透明度：全页一个值 */
        const val DISABLED_ALPHA = 0.45f
        /** 进度条满格值：按千分位走，100 格在下载大镜像时十几秒才动一格 */
        const val PROGRESS_MAX = 1000
        /** 操作格高度：图标 + 标题 + 一行说明（只有标题时是 72） */
        const val CELL_HEIGHT = 88
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
            val lp = child.layoutParams as LayoutParams
            // 可用宽度要先扣掉子项自己的外边距：否则一条占满整行的长文本
            // 会连同右边距一起顶出内边距（空状态说明文字右侧会贴边）。
            child.measure(
                MeasureSpec.makeMeasureSpec(
                    (avail - lp.leftMargin - lp.rightMargin).coerceAtLeast(0), MeasureSpec.AT_MOST,
                ),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
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

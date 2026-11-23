package app.dsh.mobile

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 设置页「行内展开」小组件（issue #111）。
 *
 * ## 为什么不弹窗
 * 语言与运行时代理这两个设置原先各弹一个对话框。问题不在对话框本身难看，而在
 * **它把「当前值」藏起来了**：语言弹窗是一串单选，用户点开之前看不到自己现在是
 * 哪个；代理弹窗更明显，要等对话框盖上来才见到输入框里的值。设置项就在那一行，
 * 展开在行下面比盖住半屏更符合「改一个值」的心智模型。
 *
 * 反过来，**同意类**的（远程访问、局域网 WebUI）仍保留弹窗 —— 那是安全确认，
 * 不该做成静默的内联开关。
 *
 * ## 三条纪律
 * 1. **面板按行 id 缓存**：同一行反复点不会越插越多（这是「就地展开」最容易写崩
 *    的地方，面板插一次就会永久留在树里）。
 * 2. **插在行之后**：面板紧跟目标行，不能追加到组尾，否则展开的内容离它所属的
 *    行隔着好几行，看起来像另一组。
 * 3. **默认隐藏 + 分隔线**：展开前不占高度；展开后靠 1dp 线跟上面的行分开。
 */
object InlinePanels {

    /**
     * 在 [row] 下方插一个默认隐藏的面板（带 1dp 上分隔线），返回面板容器。
     *
     * 幂等：已经插过就直接返回旧的那个，不再新建。
     *
     * @param host  row 的直接父容器（必须是 LinearLayout 垂直排列）
     * @param row   触发行
     * @param fill  往面板里填内容（只在**第一次**创建时调用）
     */
    fun attach(host: LinearLayout, row: View, fill: (LinearLayout) -> Unit): LinearLayout {
        val tag = panelTag(row.id)
        (host.findViewWithTag<View>(tag))?.let { return it as LinearLayout }
        val ctx = host.context
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            visibility = View.GONE
            setTag(tag)
        }
        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1))
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.stroke))
        }
        panel.addView(divider)
        fill(panel)
        // 插到 row 的紧邻后面（不是组尾）。row 用 indexOfChild 而不是靠 id 找：
        // 有些行在运行时被 setVisibility(GONE) 挪过位置，index 才是当下的真位置。
        val index = host.indexOfChild(row)
        host.addView(panel, if (index >= 0) index + 1 else host.childCount)
        return panel
    }

    /** 点一次展开、再点一次收起（返回展开后是否可见）。 */
    fun toggle(panel: LinearLayout): Boolean {
        val show = panel.visibility != View.VISIBLE
        panel.visibility = if (show) View.VISIBLE else View.GONE
        return show
    }

    /**
     * 一行单选：左标题、右勾选。
     *
     * 走的是普通 LinearLayout + TextView，不是 RadioButton —— 我们要的是「点整行
     * 就选中」和「右侧只有一个勾」，RadioButton 自带的圆点位置与行高对不上，还
     * 会被 Material 主题重新着色。
     *
     * @param accent 选中项的标题加重 + 勾可见；未选中项两者都不给
     */
    fun radioRow(
        ctx: Context,
        title: String,
        checked: Boolean,
        accent: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ctx.dp(48),
            )
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            isClickable = true
            isFocusable = true
            setBackgroundResource(ctx.selectableItemBackground())
        }
        val label = TextView(ctx).apply {
            text = title
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(
                if (accent) ctx.dshColor(R.attr.dshAccent, R.color.accent)
                else ctx.dshColor(R.attr.dshTextPrimary, R.color.text_primary)
            )
            if (checked) {
                // 当前项加粗：既要能看出「现在用的是哪个」，也不能只靠颜色（
                // 灰度屏/色弱用户的判别手段只剩字重与勾号两样）
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
        row.addView(label)
        val mark = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_check)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20))
            visibility = if (checked) View.VISIBLE else View.GONE
            contentDescription = null
        }
        row.addView(mark)
        row.setOnClickListener { onClick() }
        return row
    }

    /** 面板右下角的一个文字按钮（保存 / 重置这类）。 */
    fun actionButton(ctx: Context, title: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = title
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(ctx.dp(16), ctx.dp(9), ctx.dp(16), ctx.dp(9))
            isClickable = true
            isFocusable = true
            setTextColor(
                if (primary) ctx.dshColor(R.attr.dshAccent, R.color.accent)
                else ctx.dshColor(R.attr.dshTextSecondary, R.color.text_secondary)
            )
            setBackgroundResource(ctx.selectableItemBackground())
            setOnClickListener { onClick() }
        }

    private fun panelTag(id: Int): String = "inline-panel-%d".format(id)
}

/** dp → px（面板内部间距用；Activity 里那份是私有的，这里单独放一份给组件用）。 */
internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** `?attr/selectableItemBackground` → 资源 id（Ripple 由主题提供）。 */
internal fun Context.selectableItemBackground(): Int {
    val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
    val id = a.getResourceId(0, 0)
    a.recycle()
    return id
}

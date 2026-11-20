package app.dsh.mobile

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * 记忆列表的适配器（M8）。
 *
 * 一行就是个性状：标题（没有标题就退回意图原文）、副标题（包名 · 步数 · 命中/失败）、
 * 状态徽标。徽标用 bg_status_chip 打底 + 运行时 tint —— 与权限清单/权限中心的
 * 状态胶囊是同一套画法，不新增 drawable。徽标规则放在 companion 里，详情页复用同一份，
 * 避免列表说「待确认」而详情说「生效中」这种自相矛盾。
 */
internal class MemoryEntryAdapter(private val ctx: Context) : BaseAdapter() {

    /** GET /memory/v2/list 的一行，字段与桥返回一一对应，不做额外推导。 */
    internal class Row(
        val id: String,
        val pkg: String,
        val title: String,
        val intent: String,
        val state: String,
        val pending: Boolean,
        val hits: Int,
        val misses: Int,
        val steps: Int,
        val lastUsed: Long,
    ) {
        /** 列表展示名：title 为空时退化成 intent（派生签名也比空白强）。 */
        val display: String get() = title.ifBlank { intent }
    }

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Row = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, recycle: View?, parent: ViewGroup?): View {
        val v = recycle
            ?: LayoutInflater.from(ctx).inflate(R.layout.item_memory, parent, false)
        val row = rows[position]

        v.findViewById<TextView>(R.id.txtMemRowTitle).text = row.display
        v.findViewById<TextView>(R.id.txtMemRowSub).text =
            ctx.getString(R.string.mem_item_sub, row.pkg, row.steps, row.hits, row.misses)
        applyBadge(ctx, v.findViewById(R.id.txtMemRowBadge), row.state, row.pending)
        return v
    }

    companion object {
        /** 与 engine/MemoryStore.kt 的 MemoryContract 同值：桥传的是裸字符串。 */
        const val STATE_ACTIVE = "active"
        const val STATE_SUSPECT = "suspect"
        const val STATE_DISABLED = "disabled"

        /** 徽标的文案资源：pending 优先于 state 显示（待确认的条目还要人去标注）。 */
        fun badgeLabel(state: String, pending: Boolean): Int = when {
            pending -> R.string.mem_badge_pending
            state == STATE_SUSPECT -> R.string.mem_badge_suspect
            state == STATE_DISABLED -> R.string.mem_badge_disabled
            else -> R.string.mem_badge_active
        }

        /** 状态徽标使用 MD3 语义色角色，不依赖自制状态色板。 */
        fun applyBadge(ctx: Context, view: TextView, state: String, pending: Boolean) {
            val fgAttr: Int
            val bgAttr: Int
            when {
                pending || state == STATE_SUSPECT -> {
                    fgAttr = com.google.android.material.R.attr.colorOnErrorContainer
                    bgAttr = com.google.android.material.R.attr.colorErrorContainer
                }
                state == STATE_DISABLED -> {
                    fgAttr = com.google.android.material.R.attr.colorOnSurfaceVariant
                    bgAttr = com.google.android.material.R.attr.colorSurfaceVariant
                }
                else -> {
                    fgAttr = com.google.android.material.R.attr.colorOnSecondaryContainer
                    bgAttr = com.google.android.material.R.attr.colorSecondaryContainer
                }
            }
            fun resolve(attr: Int): Int {
                val value = android.util.TypedValue()
                return if (ctx.theme.resolveAttribute(attr, value, true)) {
                    if (value.resourceId != 0) ctx.getColor(value.resourceId) else value.data
                } else {
                    ctx.getColor(R.color.md3_on_surface)
                }
            }
            view.text = ctx.getString(badgeLabel(state, pending))
            view.setTextColor(resolve(fgAttr))
            view.backgroundTintList = android.content.res.ColorStateList.valueOf(resolve(bgAttr))
        }
    }
}

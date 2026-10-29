package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 结构化报告弹窗（v1.4.45）：状态徽章 + 分项列表，替代 `setMessage` 纯文本堆砌。
 *
 * 使用方：维护页「全面体检」与「上次启动详情」。要点：
 *  - 摘要徽章行：按状态聚合计数，一眼看出有没有异常（全 0 异常时只显示通过数）
 *  - 分项行：徽章圈（软底色 + 状态色符号）+ 标题 + 明细，异常项加软底高亮
 *  - 颜色全部走主题 token（success/warning/danger + 对应 _soft），明暗主题都能读
 */
object ReportDialog {

    enum class Status { OK, FIXED, WARN, FAIL }

    data class Item(val status: Status, val title: String, val detail: String = "")

    fun show(
        activity: Activity,
        title: CharSequence,
        items: List<Item>,
        copyLabel: CharSequence,
        copyText: String,
    ) {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        fun softOf(status: Status) = when (status) {
            Status.OK, Status.FIXED -> R.color.success_soft
            Status.WARN -> R.color.warning_soft
            Status.FAIL -> R.color.danger_soft
        }

        fun colorOf(status: Status) = when (status) {
            Status.OK, Status.FIXED -> R.color.success
            Status.WARN -> R.color.warning
            Status.FAIL -> R.color.danger
        }

        fun mark(status: Status) = when (status) {
            Status.OK -> "✓"
            Status.FIXED -> "✓+"
            Status.WARN -> "!"
            Status.FAIL -> "✗"
        }

        fun chip(text: String, tint: Int, textColor: Int): TextView =
            TextView(activity).apply {
                this.text = text
                textSize = 12f
                setTextColor(activity.getColor(textColor))
                background = activity.getDrawable(R.drawable.bg_status_chip)
                backgroundTintList = ColorStateList.valueOf(activity.getColor(tint))
                setPadding(dp(12), dp(5), dp(12), dp(5))
            }

        fun badge(status: Status): TextView =
            TextView(activity).apply {
                text = mark(status)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(activity.getColor(colorOf(status)))
                background = activity.getDrawable(R.drawable.bg_report_badge)
                backgroundTintList = ColorStateList.valueOf(activity.getColor(softOf(status)))
                setPadding(0, 0, 0, 0)
            }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(4))
        }

        // —— 摘要徽章行 ——
        val counts = Status.entries.associateWith { s -> items.count { it.status == s } }
        val badgeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            Status.OK to R.string.report_passed,
            Status.FIXED to R.string.report_fixed,
            Status.WARN to R.string.report_warned,
            Status.FAIL to R.string.report_failed,
        ).forEach { (status, label) ->
            val n = counts[status] ?: 0
            if (n > 0) {
                if (badgeRow.childCount > 0) {
                    badgeRow.addView(TextView(activity).apply { text = " " })
                }
                badgeRow.addView(chip(activity.getString(label, n), softOf(status), colorOf(status)))
            }
        }
        box.addView(badgeRow)

        // —— 分项列表（可滚动）——
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, item ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                if (item.status == Status.WARN || item.status == Status.FAIL) {
                    background = activity.getDrawable(R.drawable.bg_status_chip)
                    backgroundTintList = ColorStateList.valueOf(activity.getColor(softOf(item.status)))
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                }
            }
            val b = badge(item.status)
            row.addView(b, LinearLayout.LayoutParams(dp(22), dp(22)))
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            textCol.addView(TextView(activity).apply {
                text = item.title
                textSize = 14f
                setTextColor(activity.getColor(R.color.text_primary))
            })
            if (item.detail.isNotBlank()) {
                textCol.addView(TextView(activity).apply {
                    text = item.detail
                    textSize = 12f
                    setTextColor(activity.getColor(R.color.text_secondary))
                })
            }
            row.addView(
                textCol,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                },
            )
            list.addView(row)
            if (index < items.lastIndex &&
                item.status != Status.WARN && item.status != Status.FAIL &&
                items[index + 1].status != Status.WARN && items[index + 1].status != Status.FAIL
            ) {
                list.addView(TextView(activity).apply {
                    setBackgroundResource(R.drawable.bg_status_chip)
                    backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.divider))
                    setPadding(0, 0, 0, 0)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
        }
        box.addView(
            ScrollView(activity).apply { addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(copyLabel) { _, _ ->
                val cm = activity.getSystemService(Activity.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh-report", copyText))
                android.widget.Toast.makeText(activity, R.string.file_editor_path_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
            .create()
            .apply {
                setOnShowListener { window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded) }
                show()
            }
    }
}

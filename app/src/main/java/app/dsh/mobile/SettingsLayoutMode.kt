package app.dsh.mobile

import android.content.Context

/**
 * 设置页布局样式（v1.4.23 引入，v1.5.0 起精简为两档）。
 *
 * 曾经有 LIST / CARD / GRID 三种。CARD 与 LIST 的差别只是「分区卡片之间的
 * 下间距 4dp → 18dp」，视觉上几乎分不出来（宝宝 2026-11-06 要求去掉），
 * 所以现在只保留两种真正不同的排布：
 *
 *  · [LIST]   —— 分区标题 + 单列分组卡片，每项两行（标题 + 说明）
 *  · [GRID]   —— 每个分区一个 2 列图标网格，只显示标题与图标；点格子进二级页/
 *                直接执行开关类操作。一屏能看完全部条目，适合熟手
 *
 *  · [COLLAPSE] —— 分区默认收起，只留分区标题；点标题展开该区的全部条目。
 *                条目多时一屏就能看到所有分区，找东西不用长滚。
 *
 * 三种都复用同一份条目定义（见 SettingsActivity 的 row builders），不复制逻辑。
 *
 * ⚠️ 旧偏好迁移：存过 `"card"` 的用户由 [fromId] 落到 LIST，无需额外清理逻辑。
 */
enum class SettingsLayoutMode(val id: String, val labelRes: Int, val cols: Int) {
    LIST("list", R.string.settings_layout_list, 1),
    GRID("grid", R.string.settings_layout_grid, 2),
    COLLAPSE("collapse", R.string.settings_layout_collapse, 1);

    companion object {
        private const val PREFS = "dsh_ui_prefs"
        private const val KEY = "settings_layout"
        private const val KEY_EXPANDED = "settings_layout_expanded"

        fun fromId(id: String?): SettingsLayoutMode = entries.firstOrNull { it.id == id } ?: LIST

        fun current(ctx: Context): SettingsLayoutMode = fromId(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

        fun set(ctx: Context, mode: SettingsLayoutMode) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.id).apply()
        }

        /**
         * 折叠模式下**已展开**的分区下标集合。
         *
         * 用下标而不是分区标题文案：标题跟着切语言变，用它当键会让切换语言后
         * 展开状态全丢。分区顺序由 XML 决定、运行期固定，下标是稳的。
         */
        fun expandedSections(ctx: Context): Set<Int> =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_EXPANDED, emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()

        fun toggleSection(ctx: Context, index: Int) {
            val now = expandedSections(ctx).toMutableSet()
            if (!now.add(index)) now.remove(index)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_EXPANDED, now.map { it.toString() }.toSet()).apply()
        }
    }
}

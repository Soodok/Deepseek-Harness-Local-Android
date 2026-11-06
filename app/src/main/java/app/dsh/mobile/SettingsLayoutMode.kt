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
 * 两种都复用同一份条目定义（见 SettingsActivity 的 row builders），不复制逻辑。
 *
 * ⚠️ 旧偏好迁移：存过 `"card"` 的用户由 [fromId] 落到 LIST，无需额外清理逻辑。
 */
enum class SettingsLayoutMode(val id: String, val labelRes: Int, val cols: Int) {
    LIST("list", R.string.settings_layout_list, 1),
    GRID("grid", R.string.settings_layout_grid, 2);

    companion object {
        private const val PREFS = "dsh_ui_prefs"
        private const val KEY = "settings_layout"

        fun fromId(id: String?): SettingsLayoutMode = entries.firstOrNull { it.id == id } ?: LIST

        fun current(ctx: Context): SettingsLayoutMode = fromId(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

        fun set(ctx: Context, mode: SettingsLayoutMode) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.id).apply()
        }
    }
}

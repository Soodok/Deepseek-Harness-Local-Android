package app.dsh.mobile.engine

import android.content.Context

/**
 * 「AI 上一次做了什么」的持久化记录（v1.2.88）。
 *
 * ## 为什么需要
 * 主人要求悬浮窗展开后「直接显示 AI 上一次做了什么以及上一次 AI 是在什么时候做的」。
 * 这些信息本来只在内存里（`StatusOverlay.setAction`），**进程重启就没了**；
 * 而用户往往是在"AI 做完事之后"才去看悬浮窗，所以必须落盘。
 *
 * ## 存什么
 *  · `action` —— 动作描述（如 `tap-text "搜索"`）
 *  · `at`     —— 该动作发生的**时间戳**（毫秒）
 *
 * 用 SharedPreferences 而非文件：数据极小、读写频繁（每次动作都写）、不需要复杂结构。
 * `apply()` 是异步落盘，不阻塞动作派发路径。
 */
object LastAction {

    private const val PREFS = "last_action"
    private const val KEY_ACTION = "action"
    private const val KEY_AT = "at"

    data class Entry(val action: String, val at: Long)

    /** 记一次动作（每次 AI 派发动作时调用） */
    fun record(ctx: Context, action: String) {
        val a = action.trim()
        if (a.isEmpty()) return
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_ACTION, a)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        }
    }

    /** 读上次动作；没有则 null */
    fun read(ctx: Context): Entry? = runCatching {
        val p = prefs(ctx)
        val a = p.getString(KEY_ACTION, "").orEmpty()
        val t = p.getLong(KEY_AT, 0L)
        if (a.isBlank() || t <= 0L) null else Entry(a, t)
    }.getOrNull()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

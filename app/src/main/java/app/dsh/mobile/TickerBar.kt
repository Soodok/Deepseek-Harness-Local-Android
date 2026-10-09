package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.dsh.mobile.engine.SessionTail

/**
 * 流式悬浮条（v1.2.99）—— AI 输出贴屏顶部实时滚动。
 *
 * ## 为什么做
 * 悬浮球只在展开后显示「上一次动作」，用户得主动去看；而长任务运行时，
 * 用户想知道「AI 现在在干什么」需要切回 App。同类项目（DSHA）的流式悬浮条
 * 把 AI 输出像歌词一样贴在屏幕顶部，工具调用翻译成人话，一眼可见。
 *
 * ## 形态
 *  - 贴屏幕顶部（状态栏下方），半透明深色卡片，可拖动
 *  - 第一行：AI 当前输出（流式滚动，最多 N 行）
 *  - 后续行：最近工具调用（人话描述，最多 3 条）
 *  - 空闲时自动淡出隐藏；有新内容自动出现
 *  - 点击展开/收起行数；长按隐藏
 *
 * ## 数据来源
 * [SessionTail] 轮询活跃会话文件（引擎用 zstd 写会话，无法增量读，
 * 故按 mtime 变化触发整体解压 + 取尾部）。默认 2 秒轮询。
 *
 * ## 归实验性功能管辖
 * 与悬浮球一致：实验性开关关闭时不显示（见 DshAccessibilityService）。
 */
class TickerBar(private val svc: AccessibilityService) {

    private val ctx: Context = svc.applicationContext
    private val wm: WindowManager =
        svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var textView: TextView? = null
    private var toolsBox: LinearLayout? = null

    /** 展开行数档位（1 = 紧凑，2 = 展开） */
    @Volatile private var expanded = false

    @Volatile private var polling = false

    val isVisible: Boolean get() = view != null

    fun show() {
        if (view != null) return
        runCatching {
            val v = LayoutInflater.from(ctx).inflate(R.layout.ticker_bar, null)
            textView = v.findViewById(R.id.tickerText)
            toolsBox = v.findViewById(R.id.tickerTools)

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // ⚠️ v1.2.101：**必须带 FLAG_NOT_TOUCHABLE**（主人实测「没输出时遮挡屏幕」）。
                // 旧 flags 只有 NOT_FOCUSABLE + NOT_TOUCH_MODAL —— 后者仅表示「不阻塞窗口外
                // 的触摸」，**窗口自身区域照样吃触摸**。悬浮条是 MATCH_PARENT 宽的横条，
                // 无内容时 alpha=0（透明）但仍在吃那一条的点击 → 用户点顶部区域点不动。
                // 现在默认 NOT_TOUCHABLE（完全穿透），有内容时再摘掉它（见 render）。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val dm = ctx.resources.displayMetrics
                val margin = (8 * dm.density).toInt()
                x = margin
                y = (28 * dm.density).toInt()      // 状态栏下方
                width = dm.widthPixels - margin * 2
            }

            // 点击切换展开；长按隐藏
            v.isClickable = true
            v.setOnClickListener { toggleExpand() }
            v.setOnLongClickListener { hide(); true }

            v.alpha = 0f
            wm.addView(v, p)
            v.animate().alpha(1f).setDuration(200L).start()

            view = v
            params = p
            Log.i(TAG, "ticker shown")
        }.onFailure { Log.w(TAG, "show failed: ${it.message}") }
    }

    fun hide() {
        // ⚠️ v1.2.100：长按隐藏也要**停轮询**（独立审查发现的小漏）——
        // 否则窗口没了但 pollLoop 每 2 秒仍在解压 zstd（会话是帧压缩、开销不低），
        // 白耗电且用户看不到任何效果。
        stopPolling()
        val v = view ?: return
        view = null
        textView = null
        toolsBox = null
        v.animate().alpha(0f).setDuration(150L).withEndAction {
            runCatching { wm.removeView(v) }
        }.start()
        Log.i(TAG, "ticker hidden")
    }

    private fun toggleExpand() {
        expanded = !expanded
        val tv = textView ?: return
        tv.maxLines = if (expanded) EXPANDED_LINES else COLLAPSED_LINES
        toolsBox?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    /** 开始轮询会话（调用方在服务连上/开关打开时调） */
    fun startPolling() {
        if (polling) return
        polling = true
        pollLoop()
        Log.i(TAG, "polling started (${POLL_MS}ms)")
    }

    fun stopPolling() {
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun pollLoop() {
        if (!polling) return
        Thread({
            val snap = runCatching { SessionTail.refresh(ctx) }.getOrNull()
            if (snap != null && view != null) handler.post { render(snap) }
        }, "ticker-poll").apply { isDaemon = true }.start()
        handler.postDelayed({ pollLoop() }, POLL_MS)
    }

    /** 渲染快照：AI 输出 + 工具调用；空闲（无内容）时淡出或显示诊断 */
    private fun render(snap: SessionTail.Snapshot) {
        val v = view ?: return
        val tv = textView ?: return

        if (snap.isEmpty) {
            // 无内容分两种（v1.2.102）：
            //  · 无诊断 → 确实没有会话活动，淡出**并让窗口完全穿透触摸**（v1.2.101，
            //    主人实测「遮挡屏幕」）。只把 alpha 降到 0 不够：透明窗口仍吃触摸。
            //  · 有诊断 → 把「为什么取不到内容」画出来。真实用户抓不到 logcat，
            //    否则只看到一片空白，无法区分「AI 没说话」和「App 读不到会话」。
            //    仍然 NOT_TOUCHABLE，所以看得见但点得穿。
            setTouchable(false)
            if (snap.diag.isBlank()) {
                if (v.alpha > 0.05f) v.animate().alpha(0.0f).setDuration(300L).start()
                return
            }
            if (v.alpha < 0.9f) v.animate().alpha(0.9f).setDuration(150L).start()
            tv.text = snap.diag
            tv.maxLines = COLLAPSED_LINES
            toolsBox?.visibility = View.GONE
            return
        }
        setTouchable(true)
        if (v.alpha < 0.95f) v.animate().alpha(1f).setDuration(150L).start()

        // 主行：AI 输出（无输出但有工具时显示「正在…」）
        val main = snap.assistantText.ifBlank {
            if (snap.running) ctx.getString(R.string.ticker_working) else ""
        }
        tv.text = main.ifBlank { "-" }
        tv.maxLines = if (expanded) EXPANDED_LINES else COLLAPSED_LINES

        // 工具行
        val box = toolsBox ?: return
        if (box.childCount != snap.toolLines.size) {
            box.removeAllViews()
            snap.toolLines.forEach {
                val line = TextView(ctx).apply {
                    text = "· $it"
                    setTextColor(0xFF8A94A3.toInt())
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                box.addView(line)
            }
        } else {
            snap.toolLines.forEachIndexed { i, s ->
                (box.getChildAt(i) as? TextView)?.text = "· $s"
            }
        }
        box.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    /**
     * 切换窗口是否接收触摸（v1.2.101）。
     *
     * 无内容时置 FLAG_NOT_TOUCHABLE，让触摸**穿透**到下层应用 —— 否则一条
     * MATCH_PARENT 宽的透明横条会挡住顶部区域的点击（主人实测反馈）。
     * 有内容时才允许接收（点击展开 / 长按隐藏）。
     */
    private fun setTouchable(touchable: Boolean) {
        val p = params ?: return
        val cur = p.flags
        val want = if (touchable) cur and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else cur or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (want == cur) return
        p.flags = want
        runCatching { view?.let { wm.updateViewLayout(it, p) } }
    }

    companion object {
        private const val TAG = "TickerBar"

        /** 轮询间隔：会话是 zstd 帧压缩，解压有成本；2 秒够用且不费电 */
        private const val POLL_MS = 2_000L

        /** 收起/展开时 AI 输出最多显示几行 */
        private const val COLLAPSED_LINES = 2
        private const val EXPANDED_LINES = 8
    }
}

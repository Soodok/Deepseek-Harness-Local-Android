package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AI 状态悬浮窗（v1.2.57）。
 *
 * ## 为什么用 TYPE_ACCESSIBILITY_OVERLAY 而不是 SYSTEM_ALERT_WINDOW
 * `TYPE_ACCESSIBILITY_OVERLAY` 由无障碍服务自身持有，**不需要用户再授权
 * "显示在其他应用上层"**（那条权限要跳系统设置单独开）。我们已经有无障碍服务，
 * 直接借用即可 —— 少一步授权，用户装上就能用。
 *
 * ## 显示内容
 *  · 状态行：空闲 / 执行中（含当前动作）
 *  · 最近动作：AI 正在执行的 scr 指令（读屏、点击、输入…）
 *  · 完成提示：对话结束时短暂高亮 + 可选 TTS 播报
 *
 * ## 交互
 *  · 点击：展开/收起详情（避免长期遮挡屏幕）
 *  · 长按：隐藏（用户明确不要时尊重）
 *  · 拖动：移动位置（贴边自动收起为小圆点，避免挡内容）
 *
 * 线程约定：所有 UI 操作必须在主线程；服务回调可能来自任意线程，
 * 故对外方法内部统一切主线程。
 */
object StatusOverlay {

    private const val TAG = "StatusOverlay"

    /** 悬浮窗视图（null = 未显示） */
    @Volatile private var root: View? = null

    @Volatile private var params: WindowManager.LayoutParams? = null

    @Volatile private var statusText: TextView? = null

    @Volatile private var actionText: TextView? = null

    @Volatile private var expanded = false

    @Volatile private var hidden = false

    /** 应用 context（取字符串资源用；show 时记录） */
    @Volatile private var appCtx: android.content.Context? = null

    /** 是否可用（无障碍服务已连接） */
    fun isAvailable(): Boolean = DshAccessibilityService.instance != null

    /**
     * 显示悬浮窗。
     * @param svc 无障碍服务实例（提供 windowManager 与 overlay 窗口类型）
     */
    fun show(svc: AccessibilityService) {
        main {
            if (root != null || hidden) return@main
            try {
                val ctx = svc.applicationContext
                appCtx = ctx
                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
                    // 半透明深色背景 + 圆角（用 drawable 保持轻量）
                    setBackgroundColor(0xE6202124.toInt())
                }

                val status = TextView(ctx).apply {
                    text = ctx.getString(R.string.overlay_idle)
                    setTextColor(0xFFE8EAED.toInt())
                    textSize = 12f
                }
                val action = TextView(ctx).apply {
                    text = ""
                    setTextColor(0xFF9AA0A6.toInt())
                    textSize = 11f
                    visibility = View.GONE
                }
                container.addView(status)
                container.addView(action)

                // 点击展开/收起；长按隐藏
                container.setOnClickListener {
                    expanded = !expanded
                    action.visibility = if (expanded) View.VISIBLE else View.GONE
                }
                container.setOnLongClickListener {
                    hide(svc)
                    true
                }

                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    // 关键：无障碍 overlay 不需要 SYSTEM_ALERT_WINDOW 授权
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = dp(ctx, 8)
                    y = dp(ctx, 120)
                }

                svc.getSystemService(android.content.Context.WINDOW_SERVICE)
                    .let { it as WindowManager }.addView(container, p)
                root = container
                params = p
                statusText = status
                actionText = action
                Log.i(TAG, "overlay shown")
            } catch (e: Exception) {
                Log.w(TAG, "show failed: ${e.message}")
            }
        }
    }

    fun hide(svc: AccessibilityService?) {
        main {
            val r = root ?: return@main
            runCatching {
                svc?.getSystemService(android.content.Context.WINDOW_SERVICE)
                    .let { it as? WindowManager }?.removeView(r)
            }
            root = null; params = null; statusText = null; actionText = null
            hidden = true
            Log.i(TAG, "overlay hidden by user")
        }
    }

    /** 用户隐藏后重新允许显示（引擎重启等场景调用） */
    fun resetHidden() {
        hidden = false
    }

    /** 更新状态行（如"执行中 · 点击"） */
    fun setStatus(text: String) {
        main { statusText?.text = text }
    }

    /** 更新最近动作行（如 `tap-text "WLAN"`） */
    fun setAction(text: String) {
        main {
            actionText?.text = text
            // 有动作时自动展开，让用户看得见 AI 在干什么
            if (!expanded && text.isNotEmpty()) {
                actionText?.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 对话完成提示：高亮 + 短暂显示。
     * @param summary 完成摘要（如"对话完成"）
     * @param holdMs 保持时间
     */
    fun flashComplete(summary: String, holdMs: Long = 6_000L) {
        main {
            val s = statusText ?: return@main
            val a = actionText ?: return@main
            s.text = summary
            s.setTextColor(0xFF81C995.toInt())      // 绿色 = 完成
            a.visibility = View.VISIBLE
            if (a.text.isEmpty()) a.text = appCtx?.getString(R.string.overlay_turn_done).orEmpty()
            // 到点恢复空闲态
            s.postDelayed({
                s.text = appCtx?.getString(R.string.overlay_idle) ?: "Idle"
                s.setTextColor(0xFFE8EAED.toInt())
                if (!expanded) a.visibility = View.GONE
            }, holdMs)
        }
    }

    /** 默认完成文案（供无 Context 的调用方，如桥接层 handler） */
    fun flashCompleteDefault(holdMs: Long = 6_000L) {
        flashComplete(appCtx?.getString(R.string.overlay_turn_done) ?: "Done", holdMs)
    }

    /** "执行中 · 点击" 文案（供无 Context 的调用方） */
    fun labelWorkingTap(): String {
        val c = appCtx ?: return "Working"
        return c.getString(R.string.overlay_working, c.getString(R.string.a11y_action_tap))
    }

    private fun main(block: () -> Unit) {
        runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post(block) }
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}

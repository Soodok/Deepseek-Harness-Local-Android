package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「AI 要往开发者的服务器发东西」时的**强制同意弹窗**（30 秒倒计时）。
 *
 * ## 为什么是浮层而不是 Activity
 *
 * Android 10 起后台服务 `startActivity` 会被拦（除非有悬浮窗权限），而这个弹窗
 * 恰恰是「AI 在后台干活时冒出来的」。用 `TYPE_ACCESSIBILITY_OVERLAY` 挂到无障碍
 * 服务上就不受这个限制 —— 和 `StatusOverlay` / `PointMarker` 同一套做法，
 * 也不需要额外申请悬浮窗权限。
 *
 * ## 三条取向
 *
 * 1. **失败关闭**：无障碍没连上、视图建不出来、被系统拦下 —— 一律当「没同意」。
 *    宁可发不出去，也不能悄悄发出去。
 * 2. **看得见要发什么**：标题、附件名、总字节都摆出来。只写「是否允许发送」
 *    等于什么都没告知，那种同意书签了也不算数。
 * 3. **超时不延、不自动同意**：30 秒一到就按拒绝处理并收起窗口。
 *
 * ## 它不是什么
 *
 * 不是安全边界。App 里嵌的 report key 谁都能扒出来；它真正的意义是
 * **让人有机会看见并拦住**，不是防住一个有决心的攻击者。
 */
object ReportConsentGate {

    const val PREF_DISABLED = "report_disabled"

    /** 用户在「关于」页里永久关掉之后，这里直接拒绝，连窗口都不弹。 */
    fun permanentlyDisabled(ctx: Context): Boolean = runCatching {
        prefs(ctx).getBoolean(PREF_DISABLED, false)
    }.getOrDefault(true)
    // ★读不到偏好时按「已关闭」处理：保守方向，不是偷懒。

    fun setPermanentlyDisabled(ctx: Context, disabled: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_DISABLED, disabled).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("dsh_ui", Context.MODE_PRIVATE)

    /**
     * 阻塞式询问，**在后台线程调用**。
     *
     * @return true = 用户点了同意；false = 拒绝 / 超时 / 起不来 / 已永久关闭。
     */
    fun request(
        svc: AccessibilityService?,
        title: String,
        bodyPreview: String,
        fileNames: List<String>,
        totalBytes: Long,
        timeoutMs: Long = 30_000L,
    ): Boolean {
        if (svc == null) return false
        if (permanentlyDisabled(svc)) return false

        val latch = CountDownLatch(1)
        val approved = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val holder = arrayOfNulls<Any>(2)   // [0]=view [1]=WindowManager

        fun decide(ok: Boolean) {
            if (ok) approved.set(true)
            latch.countDown()
        }

        main.post {
            runCatching {
                val manager = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val built = build(svc, title, bodyPreview, fileNames, totalBytes) { ok -> decide(ok) }
                val view = built.first
                val update = built.second
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                )
                manager.addView(view, lp)
                holder[0] = view
                holder[1] = manager

                val deadline = System.currentTimeMillis() + timeoutMs
                val tick = object : Runnable {
                    override fun run() {
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) {
                            update(0)
                            decide(false)
                            return
                        }
                        update(((left + 999) / 1000).toInt())
                        main.postDelayed(this, 250)
                    }
                }
                main.post(tick)
            }.onFailure { decide(false) }
        }

        runCatching { latch.await(timeoutMs + 2_000L, TimeUnit.MILLISECONDS) }

        main.post {
            val v = holder[0] as? View
            val m = holder[1] as? WindowManager
            if (v != null && m != null) runCatching { m.removeView(v) }
        }
        return approved.get()
    }

    /**
     * 建视图：返回 (根视图, 更新倒计时)。
     *
     * 全用代码搭、不新增 XML —— 这个窗口只在极少数时刻出现，
     * 为它单独维护一份布局资源（还要跟主题对齐）不划算。
     */
    private fun build(
        ctx: Context,
        title: String,
        bodyPreview: String,
        fileNames: List<String>,
        totalBytes: Long,
        onDecide: (Boolean) -> Unit,
    ): Pair<View, (Int) -> Unit> {
        val dp = ctx.resources.displayMetrics.density

        fun px(v: Int) = (v * dp).toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(18), px(20), px(14))
            background = GradientDrawable().apply {
                cornerRadius = 20 * dp
                setColor(0xFF1F2328.toInt())
            }
        }

        card.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.report_consent_title)
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })

        card.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.report_consent_target)
            setTextColor(0xFFB7BDC6.toInt())
            textSize = 12f
            setPadding(0, px(8), 0, 0)
        })

        if (title.isNotBlank()) {
            card.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.report_consent_title_line, title)
                setTextColor(0xFFE6E9EE.toInt())
                textSize = 13f
                setPadding(0, px(12), 0, 0)
            })
        }
        if (bodyPreview.isNotBlank()) {
            card.addView(TextView(ctx).apply {
                text = bodyPreview.take(160)
                setTextColor(0xFF9AA3AF.toInt())
                textSize = 12f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, px(6), 0, 0)
            })
        }

        card.addView(TextView(ctx).apply {
            text = if (fileNames.isEmpty()) {
                ctx.getString(R.string.report_consent_files_none)
            } else {
                ctx.getString(
                    R.string.report_consent_files,
                    fileNames.joinToString(", ") { it.take(40) },
                    String.format("%.1f", totalBytes / 1024.0),
                )
            }
            setTextColor(0xFFE6E9EE.toInt())
            textSize = 13f
            setPadding(0, px(10), 0, 0)
        })

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, px(14), 0, 0)
        }
        buttons.addView(Button(ctx).apply {
            text = ctx.getString(R.string.report_consent_deny)
            setOnClickListener { onDecide(false) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = px(8) })
        buttons.addView(Button(ctx).apply {
            text = ctx.getString(R.string.report_consent_approve)
            setOnClickListener { onDecide(true) }
        })
        card.addView(buttons)

        val countdown = TextView(ctx).apply {
            text = ctx.getString(R.string.report_consent_countdown, 30)
            setTextColor(0xFFFFB4A2.toInt())
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(12), 0, 0)
        }
        card.addView(countdown)

        val scroller = ScrollView(ctx).apply { addView(card) }
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setPadding(px(24), 0, px(24), 0)
            addView(scroller)
        }

        val update: (Int) -> Unit = { left ->
            countdown.text = if (left <= 0) {
                ctx.getString(R.string.report_consent_expired)
            } else {
                ctx.getString(R.string.report_consent_countdown, left)
            }
        }
        return outer to update
    }
}

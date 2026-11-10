package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.dsh.mobile.engine.AgentBridge
import app.dsh.mobile.engine.LanAddress
import app.dsh.mobile.engine.RemoteAccess
import app.dsh.mobile.engine.RemoteAccessServer

/**
 * 「远程访问手机能力」详情面板（v1.7.1）。
 *
 * ## 为什么用对话框而不是跳新页面
 *
 * 用户来这里只做一件事：把手机上的配对码读到电脑上。这中间隔着「看屏幕 →
 * 敲键盘」的动作，任何多一步的跳转都是干扰。所以做成一个面板，一次显示齐：
 * 地址、配对码（带倒计时）、已配对设备、重置入口。
 *
 * ## 配对码为什么要倒计时
 *
 * 码只在窗口内有效（[RemoteAccess.PAIR_WINDOW_MS] = 5 分钟），用户看不到剩余
 * 时间就会输一个已经过期的码，然后得到「配对失败」—— 那是最让人困惑的错误。
 * 所以这里每秒刷新一次，到 0 就自动清掉显示并换成「已过期」。
 *
 * ## 已配对设备为什么显示名字而不是密钥
 *
 * 密钥是凭据，显示出来只会被截屏泄露。名字（配对时由客户端自报）足以让用户
 * 判断「这台是不是我的」。真要撤销时按行点即可 —— 撤销不需要看到密钥。
 */
object RemoteAccessSheet {

    private const val COUNTDOWN_TICK_MS = 1000L

    /**
     * 显示面板。
     *
     * @param onChanged 面板内的操作（开窗/撤销/重置）改动了状态后回调，
     *   让设置页刷新它那一行副标题
     */
    fun show(activity: Activity, onChanged: () -> Unit) {
        val ctx: Context = activity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }

        // —— 地址区 ——
        val addrView = TextView(ctx).apply {
            textSize = 14f
            val all = LanAddress.describe()
            val best = LanAddress.best()
            text = buildString {
                if (best == null) {
                    append(ctx.getString(R.string.remote_access_no_address))
                } else {
                    append("http://")
                    append(best)
                    append(":")
                    append(RemoteAccessServer.PORT)
                    if (all.size > 1) {
                        // 多网卡必须全部列出：开热点时手机同时有移动网络与热点 AP，
                        // 用户得知道该用哪个（这正是本轮修的那个 bug）
                        append("\n")
                        append(ctx.getString(R.string.remote_access_all_addresses, all.joinToString("\n")))
                    }
                }
            }
        }
        root.addView(addrView)

        // —— 配对码区 ——
        val codeView = TextView(ctx).apply {
            textSize = 22f
            setPadding(0, (16 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
        }
        root.addView(codeView)

        val hintView = TextView(ctx).apply {
            textSize = 12f
            setPadding(0, (6 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
        }
        root.addView(hintView)

        // —— 已配对设备区 ——
        val pairsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (16 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
        }
        root.addView(pairsBox)

        // 每秒刷新的倒计时（对话框关闭时必须停 —— 否则 Activity 泄漏）
        val handler = Handler(Looper.getMainLooper())
        var alive = true
        val tick = object : Runnable {
            override fun run() {
                if (!alive) return
                renderCode(ctx, codeView, hintView)
                handler.postDelayed(this, COUNTDOWN_TICK_MS)
            }
        }

        fun renderPairs() {
            pairsBox.removeAllViews()
            val list = RemoteAccess.pairs(ctx)
            pairsBox.addView(TextView(ctx).apply {
                textSize = 14f
                text = ctx.getString(R.string.remote_access_paired_title, list.size, RemoteAccess.MAX_PAIRS)
            })
            if (list.isEmpty()) {
                pairsBox.addView(TextView(ctx).apply {
                    textSize = 12f
                    text = ctx.getString(R.string.remote_access_paired_none)
                })
                return
            }
            val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            list.forEach { p ->
                pairsBox.addView(TextView(ctx).apply {
                    textSize = 14f
                    val pad = (8 * ctx.resources.displayMetrics.density).toInt()
                    setPadding(0, pad, 0, pad)
                    text = "${p.name}\n${df.format(java.util.Date(p.at))}"
                    setTextColor(0xFFE57373.toInt())
                    setOnClickListener {
                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.remote_access_revoke)
                            .setMessage(ctx.getString(R.string.remote_access_revoke_msg, p.name))
                            .setNegativeButton(R.string.action_cancel, null)
                            .setPositiveButton(R.string.remote_access_revoke) { _, _ ->
                                RemoteAccess.revokePair(ctx, p.key)
                                renderPairs()
                                onChanged()
                            }
                            .show()
                    }
                })
            }
        }

        renderPairs()
        val wasOpen = RemoteAccess.pairWindowOpen(ctx)
        if (!wasOpen) RemoteAccess.openPairWindow(ctx)
        renderCode(ctx, codeView, hintView)
        handler.postDelayed(tick, COUNTDOWN_TICK_MS)

        fun copy(code: String) {
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("DSH pair code", code))
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.setting_remote_access)
            .setView(root)
            .setPositiveButton(R.string.remote_access_regenerate) { _, _ ->
                RemoteAccess.openPairWindow(ctx)
                renderCode(ctx, codeView, hintView)
                onChanged()
            }
            .setNeutralButton(R.string.remote_access_reset_key) { _, _ ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.remote_access_reset_key)
                    .setMessage(R.string.remote_access_reset_key_msg)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.remote_access_reset_key) { _, _ ->
                        RemoteAccess.resetSecret(ctx)
                        renderPairs()
                        onChanged()
                    }
                    .show()
            }
            .setNegativeButton(R.string.close) { _, _ -> }
            .create()

        dialog.setOnDismissListener {
            // 关面板就关窗：码不该在用户看不到的时候还活着
            alive = false
            handler.removeCallbacks(tick)
            RemoteAccess.closePairWindow(ctx)
            onChanged()
        }
        dialog.show()
        // 点码即复制（用户下一步就是粘到电脑上）
        codeView.setOnClickListener {
            val code = RemoteAccess.currentPairCode(ctx) ?: return@setOnClickListener
            copy(code)
        }
    }

    /**
     * 渲染配对码与提示。
     *
     * 没开窗时显示「已关闭」+ 提示点「重新生成」；开了显示码 + 剩余秒数。
     */
    private fun renderCode(ctx: Context, codeView: TextView, hintView: TextView) {
        val code = RemoteAccess.currentPairCode(ctx)
        if (code == null) {
            codeView.text = ctx.getString(R.string.remote_access_window_closed)
            hintView.text = ctx.getString(R.string.remote_access_window_closed_hint)
        } else {
            codeView.text = ctx.getString(R.string.remote_access_code, code)
            hintView.text = ctx.getString(
                R.string.remote_access_code_hint,
                RemoteAccess.pairWindowRemainingSeconds(ctx),
            )
        }
    }
}

package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 局域网访问「验证码 + 访问地址」提示框（v1.2.162，用户要求）。
 *
 * ## 为什么用普通 AlertDialog，而不是系统浮层
 * 用户实测结论：浮层方案问题面太大 —— 无障碍通道依赖服务在位（重装 APK 后系统会停用，
 * 浮层直接不出现），应用浮层要额外权限、还可能被系统拦；**普通提示框就够用且可靠**。
 *
 * 用户明确要求（逐条对照实现）：
 *   · 验证码**显著标明**（大字 34sp、等宽、醒目蓝）→ 见下方 codeView
 *   · 验证码**可复制**（点击即复制并 toast 反馈）
 *   · 访问地址**紧贴验证码下方**、**字号稍小**（13sp）、**也可复制**
 *   · 设置页那一行**不再保留验证码**（避免长期驻留；见 SettingsActivity.lanSubText）
 */
object LanAccessDialog {

    fun show(activity: Activity, code: String, host: String?) {
        val ctx: Context = activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 6), dp(ctx, 20), dp(ctx, 2))
        }

        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.lan_dialog_code_label)
            setTextColor(0xFF8A94A3.toInt())
            textSize = 13f
        })

        // 验证码：大字 + 等宽 + 醒目色，点击即复制
        box.addView(TextView(ctx).apply {
            text = code
            setTextColor(app.dsh.mobile.Accent.current(ctx).argb)
            textSize = 34f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.22f
            setPadding(0, dp(ctx, 2), 0, 0)
            isClickable = true
            setOnClickListener {
                copyToClipboard(ctx, code)
                Toast.makeText(ctx, ctx.getString(R.string.lan_dialog_copied_code, code), Toast.LENGTH_SHORT).show()
            }
        })

        // 访问地址：紧贴验证码下方，字号稍小，点击即复制
        if (!host.isNullOrBlank()) {
            box.addView(TextView(ctx).apply {
                text = host
                setTextColor(0xFF9AA0A6.toInt())
                textSize = 13f
                setPadding(0, dp(ctx, 4), 0, 0)
                isClickable = true
                setOnClickListener {
                    copyToClipboard(ctx, host)
                    Toast.makeText(ctx, ctx.getString(R.string.lan_dialog_copied_host), Toast.LENGTH_SHORT).show()
                }
            })
        }

        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.lan_dialog_hint)
            setTextColor(0xFF6B7075.toInt())
            textSize = 12f
            setPadding(0, dp(ctx, 16), 0, 0)
        })

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lan_dialog_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DSH LAN", text))
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}

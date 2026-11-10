package app.dsh.mobile

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.dsh.mobile.engine.LinuxSshServer

/**
 * 设置 SSH 登录密码（v1.7.2）。
 *
 * ## 为什么是「设密码」而不是「生成密钥」
 *
 * 密钥更安全，但用户要在 PC 上跑 `ssh-keygen`、再把公钥弄到手机上（要么输入、
 * 要么用别的通道传）—— 对一个「想在手机上 ssh 进去写代码」的场景，这条路有
 * 三四步摩擦，很多人会卡在中间。密码只要在手机上敲两遍，VS Code 那边填一次就行。
 *
 * 想用密钥的用户仍然可以：把公钥放进
 * `/data/data/app.dsh.mobile/files/ssh/authorized_keys`（每行一个 OpenSSH 公钥），
 * `LinuxSshServer.authorizedKeysMatch` 会逐行比对。这条路没做 UI —— 做的人不需要
 * UI，需要 UI 的人不会做。
 *
 * ## 为什么要求输入两遍
 *
 * 密码设错了没法找回（只有哈希），而用户下一次要输它可能是一周后。两遍输入
 * 是唯一低成本的防呆。
 */
object SshPasswordDialog {

    fun show(activity: Activity, onSet: () -> Unit) {
        val ctx: Context = activity
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        box.addView(TextView(ctx).apply {
            textSize = 13f
            text = ctx.getString(R.string.linux_ssh_password_hint)
        })

        val first = EditText(ctx).apply {
            hint = ctx.getString(R.string.linux_ssh_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        val second = EditText(ctx).apply {
            hint = ctx.getString(R.string.linux_ssh_password_repeat)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        box.addView(first)
        box.addView(second)

        val error = TextView(ctx).apply {
            textSize = 12f
            setTextColor(0xFFE57373.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        box.addView(error)

        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.linux_ssh_set_password)
            .setView(box)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.linux_ssh_save, null)
            .create()

        dialog.show()
        // 自己接管 positive 按钮：校验不过就不关对话框（默认实现会直接关掉）
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = first.text.toString()
            val b = second.text.toString()
            when {
                a.length < 6 -> error.text = ctx.getString(R.string.linux_ssh_password_too_short)
                a != b -> error.text = ctx.getString(R.string.linux_ssh_password_mismatch)
                else -> {
                    LinuxSshServer.setPassword(ctx, a)
                    dialog.dismiss()
                    onSet()
                }
            }
        }
    }
}

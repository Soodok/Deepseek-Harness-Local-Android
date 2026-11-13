package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.LanAddress
import app.dsh.mobile.engine.LanWebProxy
import app.dsh.mobile.engine.RemoteAccess
import app.dsh.mobile.engine.RemoteAccessServer
import app.dsh.mobile.engine.SessionSync
import com.google.android.material.color.MaterialColors

/**
 * 双端互联：手机连接电脑 DSH / 同步会话。
 *
 * 电脑端在 DSH-MOBILE「远程连接」面板里开启：
 *   · 局域网模式 -> http://<PC-IP>:3091 + 6 位密码
 *   · 公网模式   -> https://xxx.trycloudflare.com + 32 位密码
 *
 * 本页只保存电脑地址与密码，不读取电脑凭证；同步只传 sessions 目录下的 .jsonl。
 */
class PeerLinkActivity : ThemedActivity() {

    private lateinit var urlInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var status: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface, Color.BLACK))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "\u2039"
            textSize = 30f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(12), dp(4))
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "\u53cc\u7aef\u4e92\u8054"
            textSize = 24f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
        }
        top.addView(back)
        top.addView(title)
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        root.addView(note("\u624b\u673a\u8fde\u63a5\u7535\u8111 DSH\uff0c\u6216\u628a\u624b\u673a\u4f1a\u8bdd\u540c\u6b65\u5230\u7535\u8111\u3002\u7535\u8111\u7aef\u5148\u5728 DSH-MOBILE \u7684\u300c\u8fdc\u7a0b\u8fde\u63a5\u300d\u9762\u677f\u91cc\u70b9\u5f00\u542f\u8fde\u63a5\u3002"))

        // ---------------------------------------------------------- 电脑地址
        val connect = section("\u8fde\u63a5\u7535\u8111") {
            val saved = PeerLinkPrefs.load(this@PeerLinkActivity)
            urlInput = EditText(this@PeerLinkActivity).apply {
                hint = "http://192.168.1.10:3091 \u6216 https://xxx.trycloudflare.com"
                setText(saved.first)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
                textSize = 14f
            }
            codeInput = EditText(this@PeerLinkActivity).apply {
                hint = "\u7535\u8111\u7aef 6 \u4f4d / 32 \u4f4d\u8fde\u63a5\u5bc6\u7801"
                setText(saved.second)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                textSize = 14f
            }
            addView(urlInput, matchWrap().apply { topMargin = dp(10) })
            addView(codeInput, matchWrap().apply { topMargin = dp(6) })

            addView(primaryButton("\u5728\u6d4f\u89c8\u5668\u6253\u5f00\u7535\u8111 DSH") {
                val url = normalizedUrl()
                if (url == null) return@primaryButton
                save()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }, matchWrap().apply { topMargin = dp(10) })

            addView(note("\u6253\u5f00\u540e\u662f\u7535\u8111\u4e0a\u7684 DSH \u754c\u9762\uff0c\u624b\u673a\u6d4f\u89c8\u5668\u4f1a\u76f4\u63a5\u8fdb\u5165\uff1b\u7b2c\u4e00\u6b21\u9700\u8981\u8f93\u5165\u4e0a\u9762\u7684\u5bc6\u7801\u3002"))
        }
        root.addView(connect)

        // ---------------------------------------------------------- 同步
        val sync = section("\u540c\u6b65\u4f1a\u8bdd") {
            status = TextView(this@PeerLinkActivity).apply {
                text = "\u672a\u540c\u6b65"
                textSize = 12f
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                setPadding(0, dp(8), 0, dp(4))
            }
            addView(status, matchWrap())
            val row = LinearLayout(this@PeerLinkActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(secondaryButton("\u53cc\u5411\u540c\u6b65") { runSync("both") }, LinearLayout.LayoutParams(0, dp(44), 1f))
            row.addView(space(8))
            row.addView(secondaryButton("\u53ea\u62c9\u53d6") { runSync("pull") }, LinearLayout.LayoutParams(0, dp(44), 1f))
            row.addView(space(8))
            row.addView(secondaryButton("\u53ea\u63a8\u9001") { runSync("push") }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(row, matchWrap().apply { topMargin = dp(6) })
            addView(note("\u53ea\u540c\u6b65 sessions \u76ee\u5f55\u4e0b\u7684 .jsonl\uff1b\u4e0d\u4f20\u56fe\u7247\u9644\u4ef6\u3001\u5de5\u4f5c\u533a\u6587\u4ef6\u3001\u51ed\u8bc1\u548c\u63d2\u4ef6\u3002\u5206\u53c9\u7684\u4f1a\u8bdd\u4f1a\u5907\u4efd\u5230\u624b\u673a dsh-home/sync-conflicts\u3002"))
        }
        root.addView(sync)

        // ---------------------------------------------------------- 本机服务
        val local = section("\u672c\u673a\u670d\u52a1") {
            val addresses = LanAddress.all()
            val ip = addresses.firstOrNull() ?: LanWebProxy.lanAddress() ?: "?"
            val lanOn = DshApp.lanWebEnabled(this@PeerLinkActivity)
            val code = if (lanOn) DshApp.lanWebPassword(this@PeerLinkActivity) else null
            val lines = buildString {
                append("\u672c\u673a\u5730\u5740\uff1a")
                append(if (addresses.isEmpty()) "?" else addresses.joinToString(" / "))
                append('\n')
                append("\u5c40\u57df\u7f51 WebUI\uff1ahttp://")
                append(ip)
                append(':')
                append(LanWebProxy.PORT)
                append('\n')
                append("\u8fde\u63a5\u5bc6\u7801\uff1a")
                append(code ?: "\u672a\u5f00\u542f\uff08\u8bbe\u7f6e \u2192 \u5c40\u57df\u7f51\u8bbf\u95ee WebUI\uff09")
                append('\n')
                append("\u8fdc\u7a0b\u8bbf\u95ee\u914d\u5bf9\uff1ahttp://")
                append(ip)
                append(':')
                append(RemoteAccessServer.PORT)
                append("\uff08\u5df2\u914d\u5bf9 ")
                append(RemoteAccess.pairs(this@PeerLinkActivity).size)
                append('/')
                append(RemoteAccess.MAX_PAIRS)
                append("\uff09")
            }
            addView(note(lines))
            addView(primaryButton("\u590d\u5236\u672c\u673a WebUI \u5730\u5740") {
                copy("http://$ip:${LanWebProxy.PORT}")
            }, matchWrap().apply { topMargin = dp(10) })
            if (code != null) {
                addView(secondaryButton("\u590d\u5236\u672c\u673a\u8fde\u63a5\u5bc6\u7801") { copy(code) }, matchWrap().apply { topMargin = dp(6) })
            }
        }
        root.addView(local)

        root.addView(note("\u5b89\u5168\u63d0\u793a\uff1a\u7535\u8111\u516c\u7f51\u5730\u5740\u662f\u4e34\u65f6\u516c\u5f00\u5730\u5740\uff0c\u5bc6\u7801\u7b49\u540c\u4e8e\u7535\u8111 DSH \u7684\u5b8c\u6574\u64cd\u4f5c\u6743\u9650\u3002\u53ea\u5728\u53ef\u4fe1\u7f51\u7edc/\u53ef\u4fe1\u8bbe\u5907\u4e0a\u4f7f\u7528\uff0c\u7528\u5b8c\u5728\u7535\u8111\u7aef\u5173\u95ed\u516c\u7f51\u8bbf\u95ee\u3002"))

        return ScrollView(this).apply { addView(root) }
    }

    // ---------------------------------------------------------------- actions

    private fun runSync(direction: String) {
        val url = normalizedUrl() ?: return
        val code = codeInput.text.toString().trim()
        if (code.isBlank()) {
            toast("\u5148\u586b\u7535\u8111\u7aef\u8fde\u63a5\u5bc6\u7801")
            return
        }
        save()
        status.text = "\u540c\u6b65\u4e2d\u2026"
        Thread({
            val result = runCatching { SessionSync.sync(this@PeerLinkActivity, url, code, direction) }
            runOnUiThread {
                result.onSuccess {
                    status.text = "\u5b8c\u6210\uff1a\u4e0b\u8f7d ${it.downloaded}\uff0c\u4e0a\u4f20 ${it.uploaded}\uff0c\u51b2\u7a81 ${it.conflicts}\uff0c\u8df3\u8fc7 ${it.skipped}\uff08\u8fdc\u7aef ${it.remoteFiles} / \u672c\u5730 ${it.localFiles}\uff09"
                    toast("\u4f1a\u8bdd\u540c\u6b65\u5b8c\u6210")
                }.onFailure {
                    status.text = "\u5931\u8d25\uff1a${it.message}"
                    toast("\u540c\u6b65\u5931\u8d25\uff1a${it.message}")
                }
            }
        }, "peer-sync").start()
    }

    private fun normalizedUrl(): String? {
        var raw = urlInput.text.toString().trim()
        if (raw.isBlank()) {
            toast("\u5148\u586b\u7535\u8111\u5730\u5740")
            return null
        }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "http://$raw"
        return raw.trimEnd('/')
    }

    private fun save() {
        PeerLinkPrefs.save(this, urlInput.text.toString().trim(), codeInput.text.toString().trim())
    }

    private fun copy(value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH-MOBILE", value))
        toast("\u5df2\u590d\u5236")
    }

    // ---------------------------------------------------------------- tiny UI kit

    private fun section(title: String, content: LinearLayout.() -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            setBackgroundResource(R.drawable.bg_md3_group)
            layoutParams = matchWrap().apply { bottomMargin = dp(14) }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            setPadding(0, 0, 0, dp(4))
        })
        card.content()
        return card
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setLineSpacing(dp(2).toFloat(), 1f)
        setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun primaryButton(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        setOnClickListener { click() }
    }

    private fun secondaryButton(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        setOnClickListener { click() }
    }

    private fun space(widthDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun color(attr: Int, fallback: Int): Int =
        runCatching { MaterialColors.getColor(this, attr, fallback) }.getOrDefault(fallback)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

/** 电脑地址/密码的本地保存（仅本机 SharedPreferences）。 */
object PeerLinkPrefs {
    private const val PREFS = "peer_link"
    private const val KEY_URL = "url"
    private const val KEY_CODE = "code"

    fun load(ctx: Context): Pair<String, String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (p.getString(KEY_URL, "") ?: "") to (p.getString(KEY_CODE, "") ?: "")
    }

    fun save(ctx: Context, url: String, code: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url)
            .putString(KEY_CODE, code)
            .apply()
    }
}

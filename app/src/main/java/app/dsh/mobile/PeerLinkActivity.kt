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
 * 本页只保存电脑地址与密码，不读取电脑凭证；同步只传 sessions/*.jsonl。
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
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(12), dp(4))
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "双端互联"
            textSize = 24f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
        }
        top.addView(back)
        top.addView(title)
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        root.addView(note("手机连接电脑 DSH，或把手机会话同步到电脑。电脑端先在 DSH-MOBILE 的「远程连接」面板里点开启连接。"))

        // ---------------------------------------------------------- 电脑地址
        val connect = section("连接电脑") {
            val saved = PeerLinkPrefs.load(this@PeerLinkActivity)
            urlInput = EditText(this@PeerLinkActivity).apply {
                hint = "http://192.168.1.10:3091 或 https://xxx.trycloudflare.com"
                setText(saved.first)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
                textSize = 14f
            }
            codeInput = EditText(this@PeerLinkActivity).apply {
                hint = "电脑端 6 位 / 32 位连接密码"
                setText(saved.second)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                textSize = 14f
            }
            addView(urlInput, matchWrap().apply { topMargin = dp(10) })
            addView(codeInput, matchWrap().apply { topMargin = dp(6) })

            addView(primaryButton("在浏览器打开电脑 DSH") {
                val url = normalizedUrl()
                if (url == null) return@primaryButton
                save()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }, matchWrap().apply { topMargin = dp(10) })

            addView(note("打开后是电脑上的 DSH 界面，手机浏览器会直接进入；第一次需要输入上面的密码。"))
        }
        root.addView(connect)

        // ---------------------------------------------------------- 同步
        val sync = section("同步会话") {
            status = TextView(this@PeerLinkActivity).apply {
                text = "未同步"
                textSize = 12f
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                setPadding(0, dp(8), 0, dp(4))
            }
            addView(status, matchWrap())
            val row = LinearLayout(this@PeerLinkActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(secondaryButton("双向同步") { runSync("both") }, LinearLayout.LayoutParams(0, dp(44), 1f))
            row.addView(space(8))
            row.addView(secondaryButton("只拉取") { runSync("pull") }, LinearLayout.LayoutParams(0, dp(44), 1f))
            row.addView(space(8))
            row.addView(secondaryButton("只推送") { runSync("push") }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(row, matchWrap().apply { topMargin = dp(6) })
            addView(note("只同步 sessions/**/*.jsonl；不传图片附件、工作区文件、凭证和插件。分叉的会话会备份到手机 dsh-home/sync-conflicts。"))
        }
        root.addView(sync)

        // ---------------------------------------------------------- 本机服务
        val local = section("本机服务") {
            val addresses = LanAddress.all()
            val ip = addresses.firstOrNull() ?: LanWebProxy.lanAddress() ?: "?"
            val lanOn = DshApp.lanWebEnabled(this@PeerLinkActivity)
            val code = if (lanOn) DshApp.lanWebPassword(this@PeerLinkActivity) else null
            val lines = buildString {
                append("本机地址：")
                append(if (addresses.isEmpty()) "?" else addresses.joinToString(" / "))
                append('\n')
                append("局域网 WebUI：http://")
                append(ip)
                append(':')
                append(LanWebProxy.PORT)
                append('\n')
                append("连接密码：")
                append(code ?: "未开启（设置 → 局域网访问 WebUI）")
                append('\n')
                append("远程访问配对：http://")
                append(ip)
                append(':')
                append(RemoteAccessServer.PORT)
                append("（已配对 ")
                append(RemoteAccess.pairs(this@PeerLinkActivity).size)
                append('/')
                append(RemoteAccess.MAX_PAIRS)
                append("）")
            }
            addView(note(lines))
            addView(primaryButton("复制本机 WebUI 地址") {
                copy("http://$ip:${LanWebProxy.PORT}")
            }, matchWrap().apply { topMargin = dp(10) })
            if (code != null) {
                addView(secondaryButton("复制本机连接密码") { copy(code) }, matchWrap().apply { topMargin = dp(6) })
            }
        }
        root.addView(local)

        root.addView(note("安全提示：电脑公网地址是临时公开地址，密码等同于电脑 DSH 的完整操作权限。只在可信网络/可信设备上使用，用完在电脑端关闭公网访问。"))

        return ScrollView(this).apply { addView(root) }
    }

    // ---------------------------------------------------------------- actions

    private fun runSync(direction: String) {
        val url = normalizedUrl() ?: return
        val code = codeInput.text.toString().trim()
        if (code.isBlank()) {
            toast("先填电脑端连接密码")
            return
        }
        save()
        status.text = "同步中…"
        Thread({
            val result = runCatching { SessionSync.sync(this@PeerLinkActivity, url, code, direction) }
            runOnUiThread {
                result.onSuccess {
                    status.text = "完成：下载 ${it.downloaded}，上传 ${it.uploaded}，冲突 ${it.conflicts}，跳过 ${it.skipped}（远端 ${it.remoteFiles} / 本地 ${it.localFiles}）"
                    toast("会话同步完成")
                }.onFailure {
                    status.text = "失败：${it.message}"
                    toast("同步失败：${it.message}")
                }
            }
        }, "peer-sync").start()
    }

    private fun normalizedUrl(): String? {
        var raw = urlInput.text.toString().trim()
        if (raw.isBlank()) {
            toast("先填电脑地址")
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
        toast("已复制")
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

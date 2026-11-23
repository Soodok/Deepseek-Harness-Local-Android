package app.dsh.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * 「反馈问题」页：**先让人看清要发什么，再决定怎么发**。
 *
 * ## 为什么不是「点一下就静默上传」
 *
 * 用户懒的是「注册 + 组织语言」，不是「看一眼」。所以这里把力气都花在两件事：
 * 1. 自动把现场（版本 / 运行模式 / engine.log / logcat）整理成一段可读文本；
 * 2. 提供四个出口 —— 直接发送 / 复制 / 邮件 / GitHub Issue，
 *    不想给服务器的用户可以只在群里贴一段。
 *
 * 四个出口用**同一份** [ReportBundle.markdown] 产物，所以「预览看到什么」
 * 与「实际发出去什么」不会分叉。
 */
class ReportActivity : ThemedActivity() {

    override val usesSettingsBackground: Boolean get() = true

    private lateinit var titleInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var contactInput: EditText
    private lateinit var attachLogs: CheckBox
    private lateinit var attachDevice: CheckBox
    private lateinit var sendButton: Button
    private lateinit var previewButton: Button

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_report)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        titleInput = findViewById(R.id.reportTitle)
        bodyInput = findViewById(R.id.reportBody)
        contactInput = findViewById(R.id.reportContact)
        attachLogs = findViewById(R.id.reportAttachLogs)
        attachDevice = findViewById(R.id.reportAttachDevice)
        sendButton = findViewById(R.id.reportSend)
        previewButton = findViewById(R.id.reportPreview)

        previewButton.setOnClickListener { showPreview() }
        sendButton.setOnClickListener { send() }
        // v1.7.32：布局里这三个是 MaterialButton（style 用的是 M3 Outlined/Text），
        // 类型写 Button 时 findViewById 按 id 查到的实例是 MaterialButton，
        // 泛型只是编译期断言 —— 编译能过、运行期也可能抛 ClassCastException。
        // 统一按布局真实类型取，别再靠 Button 冒充。
        findViewById<MaterialButton>(R.id.reportCopy).setOnClickListener { copyToClipboard() }
        findViewById<MaterialButton>(R.id.reportMail).setOnClickListener { openMail() }
        findViewById<MaterialButton>(R.id.reportGithub).setOnClickListener { openGithub() }

        // 上一轮的标题/描述留在输入框里，避免手一滑返回就全丢。
        savedInstanceState?.let {
            titleInput.setText(it.getString(KEY_TITLE, ""))
            bodyInput.setText(it.getString(KEY_BODY, ""))
            contactInput.setText(it.getString(KEY_CONTACT, ""))
        }
    }

    /**
     * 每次回到前台都重新对齐方向（v1.7.32）。
     *
     * 本页走 [OrientationPrefs.applyToSettingsPage]：大屏跟随偏好、手机锁竖屏，
     * 而且这个开关能被外观页改。只在 onCreate 应用是不够的 —— 从外观页返回时
     * 本页实例还在，requestedOrientation 仍是旧值，会把刚改好的屏幕扳回去。
     * 与其它设置类页面保持同一约定（探针 every settings page re-applies
     * orientation on resume 守的就是这条）。
     */
    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TITLE, titleInput.text.toString())
        outState.putString(KEY_BODY, bodyInput.text.toString())
        outState.putString(KEY_CONTACT, contactInput.text.toString())
    }

    /** 读当前控件状态生成草稿。四个出口都走这里，保证内容一致。 */
    private fun draft(): ReportBundle.Draft = ReportBundle.collect(
        ctx = this,
        title = titleInput.text.toString(),
        body = bodyInput.text.toString(),
        contact = contactInput.text.toString(),
        attachLogs = attachLogs.isChecked,
        attachDevice = attachDevice.isChecked,
    )

    private fun showPreview() {
        val text = draft().preview()
        AlertDialog.Builder(this)
            .setTitle(R.string.report_preview_title)
            .setMessage(text)
            .setPositiveButton(R.string.report_preview_close, null)
            .show()
    }

    private fun send() {
        if (titleInput.text.isBlank() && bodyInput.text.isBlank()) {
            toast(getString(R.string.report_need_content))
            return
        }
        val d = draft()
        sendButton.isEnabled = false
        sendButton.text = getString(R.string.report_sending)
        thread(name = "report-send") {
            val result = runCatching { ReportBundle.post(d) }
            runOnUiThread {
                sendButton.isEnabled = true
                sendButton.setText(R.string.report_send)
                result.onSuccess { id ->
                    AlertDialog.Builder(this)
                        .setTitle(R.string.report_sent_title)
                        .setMessage(getString(R.string.report_sent_body, id.ifEmpty { "-" }))
                        .setPositiveButton(R.string.report_preview_close, null)
                        .show()
                }.onFailure { err ->
                    // 失败不装作成功：给出可以直接贴到群里的替代路径。
                    AlertDialog.Builder(this)
                        .setTitle(R.string.report_failed_title)
                        .setMessage(getString(R.string.report_failed_body, err.message ?: "?"))
                        .setPositiveButton(R.string.report_copy) { _, _ -> copyToClipboard() }
                        .setNegativeButton(R.string.report_preview_close, null)
                        .show()
                }
            }
        }
    }

    private fun copyToClipboard() {
        val text = draft().preview()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dsh report", text))
        toast(getString(R.string.report_copied))
    }

    /**
     * 邮件出口。用 `ACTION_SENDTO` + `mailto:` 而不是 `ACTION_SEND`：
     * 后者会让用户在一堆「分享到 XX」里挑，真正的邮件客户端反而沉底。
     */
    private fun openMail() {
        val text = draft().preview()
        val uri = Uri.parse(
            "mailto:${MAIL_TO}" +
                "?subject=" + enc(getString(R.string.report_mail_subject)) +
                "&body=" + enc(text)
        )
        runCatching { startActivity(Intent(Intent.ACTION_SENDTO, uri)) }
            .onFailure { copyToClipboard() }
    }

    /**
     * GitHub Issue 出口。正文走 URL 参数，**超过 6000 字符就改走剪贴板** ——
     * 浏览器对超长 URL 的处理各平台不一致，宁可让用户粘贴。
     */
    private fun openGithub() {
        val text = draft().preview()
        if (text.length > GITHUB_URL_BUDGET) {
            copyToClipboard()
            toast(getString(R.string.report_github_too_long))
            return
        }
        val url = "$GITHUB_NEW_ISSUE?title=" + enc(titleInput.text.toString().ifBlank {
            getString(R.string.report_mail_subject)
        }) + "&body=" + enc(text)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { copyToClipboard() }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_TITLE = "t"
        private const val KEY_BODY = "b"
        private const val KEY_CONTACT = "c"

        /** GitHub 仓库的问题页（公开，用户自己的账号里点提交）。 */
        private const val GITHUB_NEW_ISSUE =
            "https://github.com/Soodok/Deepseek-Harness-Local-Android/issues/new"
        private const val GITHUB_URL_BUDGET = 6000

        /** 邮件出口的收件人；换成你自己的邮箱即可。 */
        private const val MAIL_TO = "soodok@soodok.online"
    }
}

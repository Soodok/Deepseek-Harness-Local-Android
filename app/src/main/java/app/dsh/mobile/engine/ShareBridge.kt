package app.dsh.mobile.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 页面侧「系统分享」的 JS 桥。
 *
 * 页面契约：
 *   `window.DshAndroidShare.shareText(text, title, fileName) -> String`
 *     成功返回 "ok"；失败返回可读原因（页面据此提示，不做静默）。
 *
 * 为什么需要它：Android WebView 没有实现 W3C Web Share（navigator.share 为
 * undefined），插件里「系统分享」按钮在应用内永远只能提示「这个浏览器没有系统
 * 分享」。系统分享是普通用户的常规出口，不能只剩复制和保存到文件。
 *
 * 约定：
 *  · 文本先落到 cacheDir/shared/（FileProvider 的 cache-path 子树，见
 *    res/xml/file_paths.xml），再以 ACTION_SEND + EXTRA_STREAM 交给系统选择器；
 *    同时带 EXTRA_TEXT，接收方不支持流也能拿到正文。
 *  · 只读授权：FLAG_GRANT_READ_URI_PERMISSION，外部应用改不到原内容。
 *  · 同步返回字符串：JS 侧在 JavaBridge 线程上拿到结果，页面再展示提示。
 *  · 文件名做白名单清洗，避免 ../ 之类把文件写出 shared/ 之外。
 */
class ShareBridge(private val ctx: Context) {

    @android.webkit.JavascriptInterface
    fun shareText(text: String?, title: String?, fileName: String?): String {
        if (text.isNullOrEmpty()) return "没有可分享的内容"
        return try {
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, safeName(fileName))
            file.writeText(text, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, title ?: "")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, title ?: "分享")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
            "ok"
        } catch (error: Exception) {
            error.message ?: "share failed"
        }
    }

    /** 只留安全字符，并保证有时间戳与 .txt 后缀，绝不允许路径分隔符。 */
    private fun safeName(fileName: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = (fileName ?: "").trim().substringBeforeLast('.').ifBlank { "dsh-conversation" }
        val cleaned = base.replace(Regex("[^A-Za-z0-9_\u4e00-\u9fa5-]"), "-").take(48)
        val trimmed = cleaned.trim('-').ifBlank { "dsh-conversation" }
        return trimmed + "-" + stamp + ".txt"
    }
}

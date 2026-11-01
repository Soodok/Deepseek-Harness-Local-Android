package app.dsh.mobile.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * dsh-solution-explorer「用系统程序打开」的 JS 桥（v1.5.1，平板适配计划 C/D2 路）。
 *
 * 页面侧契约（插件 `src/client/explorer/native-open.ts`）：
 *   `window.DshAndroidOpen.open(absPath, mime) → boolean`  ACTION_VIEW，交给系统默认程序
 *   `window.DshAndroidOpen.share(absPath, mime) → boolean` ACTION_SEND，弹出分享选择器
 *
 * 约定：
 *  · 同步返回 boolean：true = Intent 已成功派发；false = 文件不存在/无默认程序/任何异常。
 *    插件侧拿不到 true 就自动走下一级兜底（分享 → 下载），不依赖错误文本。
 *  · 只读：FLAG_GRANT_READ_URI_PERMISSION，外部编辑不回写原文件。
 *  · FileProvider 只认 file_paths.xml 里声明的 `dsh-home/workspaces/` 子树——
 *    工作区之外的路径 getUriForFile 会抛异常，同样折返回 false（防路径外泄）。
 *  · 注入时机与 DshAndroidClipboard / DshAndroidDownloader 相同：页面加载前。
 */
class OpenBridge(private val ctx: Context) {

    @android.webkit.JavascriptInterface
    fun open(absolutePath: String?, mime: String?): Boolean =
        dispatch(Intent.ACTION_VIEW, absolutePath, mime)

    @android.webkit.JavascriptInterface
    fun share(absolutePath: String?, mime: String?): Boolean =
        dispatch(Intent.ACTION_SEND, absolutePath, mime)

    private fun dispatch(action: String, absolutePath: String?, mime: String?): Boolean {
        if (absolutePath.isNullOrBlank()) return false
        return try {
            val file = File(absolutePath)
            if (!file.exists() || !file.isFile) return false
            val type = if (mime.isNullOrBlank()) "application/octet-stream" else mime
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(action)
                .setDataAndType(uri, type)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            if (action == Intent.ACTION_SEND) intent.putExtra(Intent.EXTRA_STREAM, uri)
            // 无默认程序时 VIEW 抛 ActivityNotFoundException → false（插件侧回落分享），
            // SEND 用系统选择器，不会抛。
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}

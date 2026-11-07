package app.dsh.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import app.dsh.mobile.engine.EngineConfig
import java.io.File

/**
 * 接住「用 DSH Mobile 打开 / 分享到 DSH Mobile」进来的文件。
 *
 * ## 解决什么
 * 此前 App 只有 LAUNCHER 入口，`SEND` / `VIEW` / `SEND_MULTIPLE` /
 * `PROCESS_TEXT` 四个 intent-filter **一个都没有**。用户在微信里点一个
 * PDF「用其他应用打开」，或者长按文件「分享」，列表里根本不会出现我们 ——
 * 文件没法流进来，只能靠用户自己先存到手机再让 AI 去找。
 *
 * ## 做法
 * 这是一个**无界面**的转发 Activity：
 * 1. 把 `content://` 流复制进 App 私有目录 `files/inbox/`（外部 URI 的
 *    授权是临时的，复制一份才不会被回收；同时避免 WebView 侧再去拿权限）。
 * 2. 把落地路径写进一个待消费队列 `files/inbox/pending.json`。
 * 3. 拉起 [MainActivity]，由它把路径注入 WebUI 输入框。
 *
 * ## 为什么不用 `android:launchMode` 的 singleTop 直接复用 MainActivity
 * MainActivity 承载 WebView、启动引擎、管理遮罩，承担「接收文件」会把
 * 生命周期搅乱；而且用户可能在我们**没启动**的时候点文件进来，那时
 * MainActivity 还在冷启动、WebView 还没加载完，直接给它塞文件必然丢。
 * 所以先把文件落到磁盘，MainActivity 在 `onPageFinished` 之后自己来取。
 *
 * ## 不去重、不覆盖
 * 同名文件加时间戳后缀。用户连着分享三个 `report.pdf` 不能只剩一个。
 */
class ShareInboxActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = collectUris(intent)
        if (uris.isEmpty()) {
            Log.w(TAG, "no uri in intent action=${intent?.action}")
            finish()
            return
        }
        // 复制是 IO，放到后台线程；主线程只负责收尾拉起界面
        Thread {
            val saved = ArrayList<String>(uris.size)
            for (uri in uris) {
                val f = runCatching { copyIn(uri) }.getOrElse {
                    Log.w(TAG, "copy failed for $uri: ${it.message}")
                    null
                }
                if (f != null) saved.add(f.absolutePath)
            }
            runCatching { appendPending(saved) }
            Log.i(TAG, "received ${saved.size}/${uris.size} file(s)")
            runOnUiThread {
                // 任何情况下都要拉起界面 —— 用户点文件是明确意图，
                // 就算复制失败也该让他看到 App 并知道发生了什么。
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                finish()
            }
        }.start()
    }

    /** 从 intent 里收集所有可能的文件 URI（SEND / VIEW / SEND_MULTIPLE 形态不同）。 */
    private fun collectUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val out = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
                // 有些 App（尤其国产 IM）不给 EXTRA_STREAM，而是把 uri 放在 data 里
                intent.data?.let { if (it !in out) out.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
            }
            Intent.ACTION_VIEW -> intent.data?.let { out.add(it) }
            else -> intent.data?.let { out.add(it) }
        }
        return out.filter { it.scheme == "content" || it.scheme == "file" }
    }

    /**
     * 把外部 URI 复制进 `files/inbox/`。
     *
     * 文件名优先用 content resolver 的 `OpenableColumns.DISPLAY_NAME`（微信/
     * 文件管理器都会填），拿不到再从路径末段猜，都没有就叫 `shared-<时间戳>`。
     * 一律清洗掉路径分隔符与 `..`，避免外部输入构造出目录穿越。
     */
    private fun copyIn(uri: Uri): File? {
        val dir = EngineConfig.inboxDir(this)
        val name = resolveName(uri)
        val target = uniqueFile(dir, name)
        val input = contentResolver.openInputStream(uri) ?: return null
        return try {
            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
            target.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
    }

    private fun resolveName(uri: Uri): String {
        val fromResolver = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val raw = fromResolver?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "shared-${System.currentTimeMillis()}"
        return sanitize(raw)
    }

    /** 去掉路径分隔符、控制字符与前导点，防止 `../../` 之类穿越到别处。 */
    private fun sanitize(name: String): String {
        val cleaned = name
            .replace('/', '_')
            .replace('\\', '_')
            .filter { it.code >= 0x20 }
            .trim()
            .trimStart('.')
        return cleaned.ifBlank { "shared-${System.currentTimeMillis()}" }.take(120)
    }

    /** 同名不覆盖：`a.pdf` → `a-1720000000000.pdf`。 */
    private fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return File(dir, "$stem-${System.currentTimeMillis()}$ext")
    }

    /**
     * 追加到待消费队列。
     *
     * 用 JSON 数组而不是「一个文件一行」：文件名可能含换行（少见但合法），
     * 行格式会坏掉。队列由 [Inbox] 在读的时候原子替换（写临时文件 + rename）。
     */
    private fun appendPending(paths: List<String>) {
        if (paths.isEmpty()) return
        val prev = Inbox.pending(this)
        Inbox.writePending(this, prev + paths)
    }

    companion object {
        private const val TAG = "ShareInbox"
    }
}

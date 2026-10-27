package app.dsh.mobile.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.app.DownloadManager
import java.io.File

/**
 * WebView 下载处理（v1.4.17，issue #37）。
 *
 * ## 背景
 *
 * 用户报告：文件分享插件的「下载」按钮点了没反应——不下载、不报错、无提示。
 *
 * 根因两层（issue #37 的四组证据链，全部实锤）：
 *   ① 宿主 WebView 从未 [android.webkit.WebView.setDownloadListener]——Android WebView
 *      收到下载请求（`Content-Disposition: attachment` 等）时若无监听器**直接丢弃**，
 *      静默失败；
 *   ② App 声明的是 `MANAGE_EXTERNAL_STORAGE`（API 30 权限）——在 Android 10 设备上
 *      系统不认识这个 op，设置里既没有「所有文件访问」也没有普通存储项可授权，
 *      用户无从自救。
 *
 * ## 两条下载路径
 *
 *   · **http(s) 直链** → [DownloadManager] 系统代写，**任何 API 级别都不需要存储权限**。
 *     关键点：WebUI 有认证，下载请求必须带上 WebView 会话的 Cookie，否则拿到的是
 *     登录页 HTML 而不是文件。
 *   · **blob: URL**（插件「打包下载」用 fetch→blob→a[download] 实现）→ 这类请求
 *     **到不了** DownloadListener（blob 只存在于页面 JS 内存里）。由注入的 JS 桥
 *     （[BRIDGE_NAME]）把 blob 转成 base64 交给 [saveBase64] 落盘。
 *
 * ## 落盘位置与权限
 *
 *   · API 29+：MediaStore.Downloads（scoped storage，**无需任何权限**）
 *   · API 26–28：直接写公共 Download/ 目录，需要运行时存储权限
 *     （清单已按 maxSdkVersion=28 声明；调用方负责先申请）。
 */
object WebViewDownloader {

    private const val TAG = "WebViewDownloader"

    /** 注入页面的 JS 桥名（与 [bridgeJs] 里的 addJavascriptInterface 对应） */
    const val BRIDGE_NAME = "DshAndroidDownloader"

    /** 下载落盘子目录（DSHA 风格：Download/DSH MOBILE） */
    const val SUBDIR = "DSH MOBILE"

    /** 下载完成/失败的通知 id 起点（避免与引擎通知冲突） */
    const val REQ_STORAGE_PERMISSION = 4207

    data class Result(val ok: Boolean, val message: String, val uri: Uri? = null)

    /**
     * 解析下载文件名：contentDisposition 的 `filename*=UTF-8''…` 优先，
     * 其次 `filename="…"`，最后取 URL 末段（去掉 query 与尾部斜杠）。
     */
    fun resolveFileName(contentDisposition: String?, mimeType: String?, url: String): String {
        val cd = contentDisposition ?: ""
        // RFC 5987：filename*=UTF-8''<percent-encoded>
        Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(cd)?.let { m ->
            runCatching {
                java.net.URLDecoder.decode(m.groupValues[1].trim().trim('"'), "UTF-8")
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return sanitize(it) }
        }
        Regex("""filename\s*=\s*"([^"]+)"""").find(cd)?.let { m ->
            m.groupValues[1].trim().takeIf { it.isNotBlank() }?.let { return sanitize(it) }
        }
        Regex("""filename\s*=\s*([^;]+)""").find(cd)?.let { m ->
            m.groupValues[1].trim().trim('"').takeIf { it.isNotBlank() }?.let { return sanitize(it) }
        }
        // URL 末段
        val last = url.substringBefore("?").trimEnd('/').substringAfterLast('/')
        val name = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        if (name.isNotBlank() && name.contains('.')) return sanitize(name)
        // 兜底：按 mime 推断扩展名
        val ext = mimeType?.substringAfter('/')?.takeIf { it.length in 1..5 && it != "*" }
        return sanitize("download${ext?.let { ".$it" } ?: ""}")
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifBlank { "download" }

    /**
     * 处理 [android.webkit.DownloadListener] 收到的 http(s) 下载。
     *
     * v1.4.20（issue #44）：**回环地址不走 DownloadManager** —— 开 VPN 时系统
     * 下载管理器（独立进程）的连接被绑到 VPN 默认网络，访问 127.0.0.1 拿不到
     * 结果，任务 100% 失败；而页面自身 fetch（打包下载）不受影响。回环目标
     * 改为 App 内 HttpURLConnection 流式下载 → MediaStore/公共目录落盘，
     * 与 VPN 状态无关，也不进内存。
     *
     * @return null = 已交给 DownloadManager / 已由 App 内下载完成；非 null = 失败说明
     */
    fun enqueueHttpDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): String? = runCatching {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "unsupported download URL scheme"
        }
        if (isLoopback(url)) {
            // DownloadListener 回调在主线程，同步网络 IO 会 ANR —— 起后台线程，
            // 失败时自补一个失败 toast（成功 toast 由调用方在返回 null 后立即给）。
            val appCtx = context.applicationContext
            Thread({
                val err = downloadInApp(appCtx, url, userAgent, contentDisposition, mimeType)
                if (err != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            appCtx, "Download failed: $err", android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }, "dsh-loopback-dl").apply { isDaemon = true }.start()
            return null
        }
        val fileName = resolveFileName(contentDisposition, mimeType, url)
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            userAgent?.let { addRequestHeader("User-Agent", it) }
            // WebUI 走 token/cookie 认证：DownloadManager 不共享 WebView 会话，必须手动带上
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            addRequestHeader("Referer", url)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setTitle(fileName)
        }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Log.i(TAG, "download enqueued: $fileName")
        null
    }.getOrElse {
        Log.w(TAG, "enqueue failed: ${it.message}")
        it.message ?: "download failed"
    }

    /** 下载目标是否为本机回环（WebUI 文件分享的直链就是 127.0.0.1）。 */
    fun isLoopback(url: String): Boolean = runCatching {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        host == "127.0.0.1" || host == "localhost" || host == "::1"
    }.getOrDefault(false)

    /**
     * App 内流式下载回环资源（issue #44）：不经过 DownloadManager（VPN 绑定），
     * 不整文件进内存。带 WebView 会话 Cookie（引擎有 token 认证）。
     *
     * @return null = 成功（已落盘 Download/）；非 null = 失败说明
     */
    private fun downloadInApp(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): String? = runCatching {
        val fileName = resolveFileName(contentDisposition, mimeType, url)
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 30_000
            userAgent?.let { setRequestProperty("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            setRequestProperty("Referer", url)
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            return "HTTP $code"
        }
        val stream = conn.inputStream
        val saved = writeToPublicDownloadsStream(
            context, fileName, conn.contentType?.substringBefore(';') ?: mimeType ?: "",
            stream,
        )
        stream.close()
        conn.disconnect()
        Log.i(TAG, "in-app loopback download saved: $fileName -> $saved")
        null
    }.getOrElse {
        Log.w(TAG, "in-app download failed: ${it.message}")
        it.message ?: "download failed"
    }

    /**
     * 流式写公共 Download/（v1.4.20，配 [downloadInApp]）：29+ MediaStore
     * openOutputStream；26–28 直写路径。重名去重与 [writeToPublicDownloads] 一致。
     */
    fun writeToPublicDownloadsStream(
        context: Context,
        fileName: String,
        mimeType: String,
        input: java.io.InputStream,
    ): Uri {
        val safeMime = mimeType.ifBlank { "application/octet-stream" }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, safeMime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$SUBDIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
                ?: throw IllegalStateException("cannot open output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        // API 26–28：legacy 路径（清单已声明 WRITE_EXTERNAL_STORAGE，v1.4.20 起 maxSdk=29）
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBDIR,
        )
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("cannot create Download dir")
        var f = File(dir, fileName)
        if (f.exists()) {
            val dot = fileName.lastIndexOf('.')
            val base = if (dot > 0) fileName.substring(0, dot) else fileName
            val ext = if (dot > 0) fileName.substring(dot) else ""
            var i = 1
            while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        }
        f.outputStream().use { out -> input.copyTo(out) }
        return Uri.fromFile(f)
    }

    /**
     * JS 桥入口：把页面里的 blob 下载转成 base64 存进公共 Download/。
     * 由注入的 [bridgeJs] 调用；必须在主线程调用本方法（UI 弹 toast），落盘自己切线程。
     *
     * @return 给 JS 的回执（"ok" 或错误信息；JS 侧据此 toast）
     */
    fun saveBase64(context: Context, mimeType: String, base64: String, fileName: String): String {
        val name = sanitize(fileName.ifBlank { resolveFileName(null, mimeType, "") })
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrElse {
            return "decode failed"
        }
        return try {
            val uri = writeToPublicDownloads(context, name, mimeType, bytes)
            Log.i(TAG, "blob download saved: $name (${bytes.size} bytes) -> $uri")
            "ok"
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
            e.message ?: "save failed"
        }
    }

    /**
     * 写公共 Download/：
     *   API 29+ 走 MediaStore（无需权限）；API 26–28 直接写路径
     *   （需运行时存储权限，调用方先用 [needsRuntimePermission] 申请）。
     */
    fun writeToPublicDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Uri {
        val safeMime = mimeType.ifBlank { "application/octet-stream" }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, safeMime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$SUBDIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("cannot open output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        // API 26–28：legacy 路径（清单已声明 WRITE_EXTERNAL_STORAGE maxSdk=28；
        // requestLegacyExternalStorage 在 Android 10 生效，8/9 本就无 scoped storage）
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBDIR,
        )
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("cannot create Download dir")
        var f = File(dir, fileName)
        if (f.exists()) {
            val dot = fileName.lastIndexOf('.')
            val base = if (dot > 0) fileName.substring(0, dot) else fileName
            val ext = if (dot > 0) fileName.substring(dot) else ""
            var i = 1
            while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        }
        f.writeBytes(bytes)
        return Uri.fromFile(f)
    }

    /** API 26–28 的 blob 落盘需要运行时存储权限；29+ 走 MediaStore 不需要。 */
    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT < 29

    /**
     * 注入每个页面的 blob 下载桥：拦截 `a[download]` 且 href 以 blob: 开头的点击，
     * fetch 转 base64 后交给原生落盘。捕获阶段监听（bundle 外部插件也拦得到）。
     */
    fun bridgeJs(): String = """
        (function(){
          if (window.__dshDownloaderInstalled) return;
          window.__dshDownloaderInstalled = true;
          document.addEventListener('click', function(e){
            var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
            if (!a) return;
            var href = a.getAttribute('href') || a.href || '';
            if (href.indexOf('blob:') !== 0) return;
            e.preventDefault();
            e.stopPropagation();
            fetch(href).then(function(r){ return r.blob(); }).then(function(b){
              var fr = new FileReader();
              fr.onload = function(){
                var dataUrl = String(fr.result || '');
                var comma = dataUrl.indexOf(',');
                var b64 = comma >= 0 ? dataUrl.substring(comma + 1) : '';
                var resp = window.$BRIDGE_NAME && window.$BRIDGE_NAME.saveBase64
                  ? window.$BRIDGE_NAME.saveBase64(b.type || 'application/octet-stream', b64, a.download || '')
                  : 'bridge missing';
                try { console.log('[dsh-android] blob download: ' + resp); } catch(_) {}
              };
              fr.readAsDataURL(b);
            }).catch(function(err){
              try { console.log('[dsh-android] blob fetch failed: ' + err); } catch(_) {}
            });
          }, true);
        })();
    """.trimIndent().replace("\$BRIDGE_NAME", BRIDGE_NAME)
}

package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/**
 * ASR 模型下载/提取/管理（v1.2.59）。
 *
 * 模型从 Vosk 官方源下载（alphacephei.com），解压到 filesDir/asr-models/<dirName>/。
 * 下载完成后 Vosk Recognizer 用解压目录作为 Model 构造参数。
 */
object AsrModelManager {

    private const val TAG = "AsrModelManager"

    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "asr-models").apply { mkdirs() }

    /**
     * 模型是否**完整**可用。
     *
     * ⚠️ 不只看目录非空 —— 旧实现用 `listFiles().isNotEmpty()`，解压到一半
     * （断电/被杀/磁盘满）留下的残目录会被判为「已下载」，Vosk 加载时直接抛异常，
     * 用户看到的是「下载完成但用不了」。现在校验关键文件齐全（对齐扩展中心的
     * 安装完整性思路）。
     */
    fun isDownloaded(ctx: Context, model: AsrModel): Boolean {
        val dir = File(modelsDir(ctx), model.dirName)
        if (!dir.isDirectory) return false
        return model.requiredFiles.all { File(dir, it).isFile }
    }

    /** 已下载的模型列表 */
    fun downloadedModels(ctx: Context): List<String> {
        val dir = modelsDir(ctx)
        return dir.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
    }

    /**
     * 下载并解压模型。
     *
     * ## 完整性保障（对齐扩展中心的强校验思路）
     * 1. **字节数校验**：比对 `Content-Length` 与实际落盘大小（截断立即失败）
     * 2. **解压路径安全**：拒绝 zip 路径逃逸
     * 3. **解压后关键文件校验**：`requiredFiles` 逐个检查，缺一即失败
     * 4. **失败清理**：任何一步失败都删掉残目录/残 zip，避免下次误判为已完成
     *
     * @param onProgress 0.0~1.0
     * @param onDone 下载解压**并校验**完成
     * @param onError 失败原因（用户可读）
     */
    fun download(
        ctx: Context, model: AsrModel,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread({
            val dir = modelsDir(ctx)
            val zipFile = File(dir, "${model.id}.zip")
            val dest = File(dir, model.dirName)
            try {
                // 已完整就绪 → 直接成功（残目录会被下面的校验判否，走重下）
                if (isDownloaded(ctx, model)) { onDone(); return@Thread }
                dest.deleteRecursively()

                // ⚠️ 源顺序：**国内镜像优先**，官方源兜底。
                // 实测（2026-10-05，本机直连）：官方 alphacephei.com 仅 ~55 KB/s
                // （40MB 要 ~12 分钟），hf-mirror 达 ~16.8 MB/s（~2.4 秒）——差 300 倍。
                // 旧版把官方放前面，国内用户每次都得先等官方源慢慢拖。
                val sources = listOf(model.mirrorUrl, model.url).filter { it.isNotBlank() }
                var lastErr: Exception? = null
                var ok = false
                for (src in sources) {
                    if (ok) break
                    var sourceFailed = false
                    Log.i(TAG, "downloading from $src (~${model.sizeMb}MB)")
                    runCatching {
                        val conn = java.net.URL(src).openConnection() as java.net.HttpURLConnection
                        conn.instanceFollowRedirects = true
                        conn.connectTimeout = 10_000
                        // 读超时是「两次读之间」的上限：慢源会卡在这里 → 自动切下一源。
                        conn.readTimeout = 30_000
                        val total = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            zipFile.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var read = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n); read += n
                                    if (total > 0) onProgress(read.toFloat() / total)
                                }
                            }
                        }
                        // ① 完整性：实际字节数必须与声明一致（截断 = 残包）
                        val got = zipFile.length()
                        if (total > 0 && got != total) {
                            throw IllegalStateException("truncated: got $got of $total bytes")
                        }
                        // ② 与 catalog 记录的官方大小比对（防镜像返回错误页/别的文件）
                        if (model.expectedBytes > 0 && got != model.expectedBytes) {
                            throw IllegalStateException("size mismatch: got $got, expected ${model.expectedBytes}")
                        }
                        // ③ 兜底：至少得有 1MB（错误页/302 页面）
                        if (got < 1_000_000) {
                            throw IllegalStateException("file too small (${got}B) — error page?")
                        }
                    }.onFailure {
                        sourceFailed = true
                        lastErr = it as? Exception ?: Exception(it.message, it)
                        Log.w(TAG, "source failed ($src): ${it.message}, trying next…")
                        zipFile.delete()
                    }
                    if (!sourceFailed) ok = true
                }
                if (!ok) throw (lastErr ?: IllegalStateException("all sources failed"))

                // 解压（zip 根下通常有一个 <dirName>/ 目录）
                Log.i(TAG, "extracting…")
                onProgress(1.0f)
                ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(dir, entry.name)
                        // 解压路径安全：拒绝 zip 逃逸
                        if (!outFile.canonicalPath.startsWith(dir.canonicalPath)) {
                            throw SecurityException("zip path escape: ${entry.name}")
                        }
                        if (entry.isDirectory) { outFile.mkdirs() }
                        else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zis.copyTo(it) }
                        }
                        entry = zis.nextEntry
                    }
                }
                zipFile.delete()

                if (!dest.isDirectory) {
                    // zip 没有根目录包装 → 把解压内容移到预期目录
                    val extracted = dir.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }
                    extracted?.renameTo(dest)
                }

                // ④ 解压后校验：关键文件必须齐全，否则视为失败并清理
                if (!isDownloaded(ctx, model)) {
                    dest.deleteRecursively()
                    throw IllegalStateException(
                        "incomplete model: missing ${model.requiredFiles.joinToString()}"
                    )
                }

                Log.i(TAG, "model ready: ${dest.absolutePath}")
                onDone()
            } catch (e: Exception) {
                // 失败清理：残目录/残 zip 都删掉，避免下次被误判为已完成
                runCatching { zipFile.delete() }
                if (!isDownloaded(ctx, model)) runCatching { dest.deleteRecursively() }
                Log.w(TAG, "download failed: ${e.message}")
                onError(e.message ?: "download failed")
            }
        }, "asr-download").apply { isDaemon = true; start() }
    }

    /** 已下载模型的 Vosk Model 路径（供 VoskRecognizer 用） */
    fun modelPath(ctx: Context, model: AsrModel): String? {
        val dir = File(modelsDir(ctx), model.dirName)
        return if (isDownloaded(ctx, model)) dir.absolutePath else null
    }
}

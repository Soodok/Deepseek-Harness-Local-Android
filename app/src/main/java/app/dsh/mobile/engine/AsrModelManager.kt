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

    /** 模型是否已下载解压完成 */
    fun isDownloaded(ctx: Context, model: AsrModel): Boolean {
        val dir = File(modelsDir(ctx), model.dirName)
        return dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    /** 已下载的模型列表 */
    fun downloadedModels(ctx: Context): List<String> {
        val dir = modelsDir(ctx)
        return dir.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
    }

    /**
     * 下载并解压模型。
     * @param onProgress 0.0~1.0
     * @param onDone 下载解压完成
     * @param onError 失败原因
     */
    fun download(
        ctx: Context, model: AsrModel,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread({
            try {
                val dir = modelsDir(ctx)
                val zipFile = File(dir, "${model.id}.zip")
                val dest = File(dir, model.dirName)

                if (dest.isDirectory) { onDone(); return@Thread }

                // ⚠️ 双源：主源（Vosk 官方）在国内通常不可达 → 失败自动换 hf-mirror 镜像
                // （镜像 URL 已实测可达：hf-mirror.com/rhasspy/vosk-models）
                val sources = listOf(model.url, model.mirrorUrl).filter { it.isNotBlank() }
                var lastErr: Exception? = null
                var ok = false
                for (src in sources) {
                    if (ok) break
                    var sourceFailed = false
                    Log.i(TAG, "downloading from $src (~${model.sizeMb}MB)")
                    runCatching {
                        val conn = java.net.URL(src).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 10_000
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
                        // 校验：小于 1MB 说明下载被截断（镜像 302 页或报错页）
                        if (zipFile.length() < 1_000_000) throw IllegalStateException("file too small (${zipFile.length()}B) — source returned an error page?")
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
                        // zip 路径安全检查
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

                Log.i(TAG, "model ready: ${dest.absolutePath}")
                onDone()
            } catch (e: Exception) {
                Log.w(TAG, "download failed: ${e.message}")
                onError(e.message ?: "download failed")
            }
        }, "asr-download").apply { isDaemon = true; start() }
    }

    /** 已下载模型的 Vosk Model 路径（供 VoskRecognizer 用） */
    fun modelPath(ctx: Context, model: AsrModel): String? {
        val dir = File(modelsDir(ctx), model.dirName)
        return if (dir.isDirectory) dir.absolutePath else null
    }
}

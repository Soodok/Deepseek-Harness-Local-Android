package app.dsh.mobile.pet

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadResult(
    val ok: Boolean,
    val file: File? = null,
    val message: String? = null,
)

/**
 * 资源包下载器：单一包 or 分卷，带断点续传与 sha256 校验。
 *
 * - 单文件：直接 Range 续传到 cacheDir/pet-pack/<id>.zip；
 * - 分卷：逐卷续传到 <id>.zip.partNN，全部到齐且逐卷 sha256 通过后才拼装成完整 zip，
 *   再校验整包 sha256；任何一步不匹配立即报错，不让半成品进安装器。
 */
object PetPackDownloader {
    private val busy = AtomicBoolean(false)

    fun busy(): Boolean = busy.get()

    fun download(
        ctx: android.content.Context,
        pack: RemotePack,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        onDone: (DownloadResult) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) {
            onDone(DownloadResult(false, null, "another download is running"))
            return
        }
        val app = ctx.applicationContext
        Thread({
            val result = runCatching {
                doDownload(app, pack, onProgress)
            }.getOrElse { e ->
                PetPackIndexClient.logError("download", e)
                DownloadResult(false, null, e.message ?: e.javaClass.simpleName)
            }
            busy.set(false)
            onDone(result)
        }, "dsh-pet-pack-download").apply { isDaemon = true }.start()
    }

    /** 已下载的分卷/半成品大小，用于展示进度。 */
    fun localProgress(ctx: android.content.Context, pack: RemotePack): Long =
        targets(ctx, pack).sumOf { if (it.exists()) it.length() else 0L }

    private fun targets(ctx: android.content.Context, pack: RemotePack): List<File> {
        val dir = File(ctx.cacheDir, "pet-pack").apply { mkdirs() }
        if (pack.parts.isNotEmpty()) {
            return pack.parts.mapIndexed { i, _ ->
                File(dir, "${pack.id}.zip.part${i + 1}")
            }
        }
        return listOf(File(dir, "${pack.id}.zip"))
    }

    private fun doDownload(
        ctx: android.content.Context,
        pack: RemotePack,
        onProgress: (Long, Long) -> Unit,
    ): DownloadResult {
        val dir = File(ctx.cacheDir, "pet-pack").apply { mkdirs() }
        val total = if (pack.size > 0) pack.size else localProgress(ctx, pack)

        if (pack.parts.isNotEmpty()) {
            val files = pack.parts.mapIndexed { i, part ->
                val out = File(dir, "${pack.id}.zip.part${i + 1}")
                downloadTo(part.url, out, part.size) { delta ->
                    onProgress(localProgress(ctx, pack) + delta, total)
                }
                if (part.sha256.isNotBlank() && sha256(out) != part.sha256) {
                    return DownloadResult(false, null, "checksum mismatch: ${out.name}")
                }
                out
            }
            val zip = File(dir, "${pack.id}.zip")
            if (zip.exists()) zip.delete()
            FileOutputStream(zip).use { fos ->
                for (f in files) {
                    f.inputStream().use { it.copyTo(fos, 256 * 1024) }
                }
            }
            for (f in files) f.delete()
            if (pack.sha256.isNotBlank() && sha256(zip) != pack.sha256) {
                zip.delete()
                return DownloadResult(false, null, "checksum mismatch: ${zip.name}")
            }
            onProgress(total, total)
            return DownloadResult(true, zip)
        }

        val out = File(dir, "${pack.id}.zip")
        downloadTo(pack.url, out, pack.size) { delta ->
            onProgress(localProgress(ctx, pack) + delta, total)
        }
        if (pack.sha256.isNotBlank() && sha256(out) != pack.sha256) {
            out.delete()
            return DownloadResult(false, null, "checksum mismatch: ${out.name}")
        }
        onProgress(total, total)
        return DownloadResult(true, out)
    }

    /** Range 续传写一个文件；服务端不支持 Range 就从头写。 */
    private fun downloadTo(
        url: String,
        out: File,
        expectedSize: Long,
        onDelta: (Long) -> Unit,
    ) {
        var start = if (out.exists()) out.length() else 0L
        var conn = PetPackIndexClient.open(url, start)
        val code = conn.responseCode
        if (code == 416) { // Range 不满足：可能服务端文件变了，重头再来
            conn.disconnect()
            out.delete()
            start = 0L
            conn = PetPackIndexClient.open(url, 0L)
        }
        val resumed = code == 206
        try {
            val finalCode = conn.responseCode
            if (finalCode !in 200..299) throw IllegalStateException("HTTP $finalCode")
            if (!resumed && out.exists()) out.delete()
            val stream = conn.inputStream
            RandomAccessFile(out, "rw").use { raf ->
                if (!resumed) raf.setLength(0)
                raf.seek(if (resumed) start else 0L)
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    onDelta(n.toLong())
                }
            }
        } finally {
            conn.disconnect()
        }
        if (expectedSize > 0 && out.length() != expectedSize) {
            if (out.length() > expectedSize) {
                out.delete()
                throw IllegalStateException("size mismatch: ${out.name}")
            }
            throw IllegalStateException("incomplete download: ${out.name}")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

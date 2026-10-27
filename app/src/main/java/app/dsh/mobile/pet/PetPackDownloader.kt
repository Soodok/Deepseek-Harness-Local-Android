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
    /** 用户取消：不算失败，残件保留，下次点下载会续传。 */
    val cancelled: Boolean = false,
)

/** 用户取消下载：只在 [PetPackDownloadBus] 置位后由下载线程抛出。 */
private class CancelledException : Exception("cancelled")

/**
 * 资源包下载器：单一包 or 分卷，带断点续传与 sha256 校验。
 *
 * - 单文件：直接 Range 续传到 cacheDir/pet-pack/<id>.zip；
 * - 分卷：逐卷续传到 <id>.zip.partNN，全部到齐且逐卷 sha256 通过后才拼装成完整 zip，
 *   再校验整包 sha256；任何一步不匹配立即报错，不让半成品进安装器。
 */
object PetPackDownloader {
    private val busy = AtomicBoolean(false)

    /** 残件保留时限：超过就没必要续传了，直接回收空间。 */
    private const val STALE_MS = 7L * 24 * 60 * 60 * 1000

    fun busy(): Boolean = busy.get()

    fun download(
        ctx: android.content.Context,
        pack: RemotePack,
        /** 每次切换候选源时回调：UI 靠它显示「当前走代理还是直连」。 */
        onSource: (PackSource) -> Unit = {},
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
                doDownload(app, pack, onSource, onProgress)
            }.getOrElse { e ->
                if (e is CancelledException) {
                    DownloadResult(false, null, "cancelled", cancelled = true)
                } else {
                    PetPackIndexClient.logError("download", e)
                    DownloadResult(false, null, e.message ?: e.javaClass.simpleName)
                }
            }
            busy.set(false)
            onDone(result)
        }, "dsh-pet-pack-download").apply { isDaemon = true }.start()
    }

    /** 已下载的分卷/半成品大小，用于展示进度。 */
    fun localProgress(ctx: android.content.Context, pack: RemotePack): Long =
        targets(ctx, pack).sumOf { if (it.exists()) it.length() else 0L }

    /**
     * 下载暂存目录。
     *
     * 放 `filesDir` 而不是 `cacheDir`：后台下载可能跑几十分钟、用户已经不盯着屏幕了，
     * 而 `cacheDir` 在存储吃紧时会被系统直接回收——回收就等于白下几百 MB。
     * 首次访问顺手把旧版本留在 `cacheDir/pet-pack` 的残件搬过来，续传进度不丢。
     */
    private fun dir(ctx: android.content.Context): File {
        val target = File(ctx.filesDir, "pet-pack")
        val legacy = File(ctx.cacheDir, "pet-pack")
        if (legacy.isDirectory) {
            runCatching {
                target.mkdirs()
                legacy.listFiles()?.forEach { old ->
                    val to = File(target, old.name)
                    if (!to.exists()) old.renameTo(to)
                }
                if (legacy.listFiles()?.isEmpty() != false) legacy.delete()
            }
        }
        target.mkdirs()
        return target
    }

    private fun targets(ctx: android.content.Context, pack: RemotePack): List<File> {
        val dir = dir(ctx)
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
        onSource: (PackSource) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): DownloadResult {
        val dir = dir(ctx)
        if (PetPackDownloadBus.isCancelled()) throw CancelledException()
        val total = if (pack.size > 0) pack.size else localProgress(ctx, pack)
        // 别的包（或已从索引下架的包）留下的半成品会一直躺在 pet-pack 里，
        // 每次下载顺手清掉 7 天没动过的，避免"下了一半就放弃"的几百 MB 越积越多。
        sweepStale(dir, keepPrefix = "${pack.id}.")

        if (pack.parts.isNotEmpty()) {
            val files = pack.parts.mapIndexed { i, part ->
                // 每卷之间也给一次退出机会：分卷包可能有十几卷
                if (PetPackDownloadBus.isCancelled()) throw CancelledException()
                val out = File(dir, "${pack.id}.zip.part${i + 1}")
                downloadTo(ctx, part.url, out, part.size, onSource) { delta ->
                    onProgress(localProgress(ctx, pack) + delta, total)
                }
                if (part.sha256.isNotBlank() && sha256(out) != part.sha256) {
                    // 残件必须删掉：留着会让下次续传接在错误内容后面，永远校验不过。
                    out.delete()
                    return DownloadResult(false, null, "checksum mismatch: ${out.name}")
                }
                out
            }
            val zip = File(dir, "${pack.id}.zip")
            if (zip.exists()) zip.delete()
            FileOutputStream(zip).use { fos ->
                val buf = ByteArray(256 * 1024)
                for (f in files) {
                    f.inputStream().use { input ->
                        while (true) {
                            // 几百 MB 的拼装也要能中断：分卷还在，下次可以从拼装重来
                            if (PetPackDownloadBus.isCancelled()) {
                                runCatching { zip.delete() }
                                throw CancelledException()
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                        }
                    }
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
        downloadTo(ctx, pack.url, out, pack.size, onSource) { delta ->
            onProgress(localProgress(ctx, pack) + delta, total)
        }
        if (pack.sha256.isNotBlank() && sha256(out) != pack.sha256) {
            out.delete()
            return DownloadResult(false, null, "checksum mismatch: ${out.name}")
        }
        onProgress(total, total)
        return DownloadResult(true, out)
    }

    /** 清理 pet-pack 里与本次下载无关、且超过 [STALE_MS] 未改动的残件。 */
    private fun sweepStale(dir: File, keepPrefix: String) {
        val now = System.currentTimeMillis()
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(keepPrefix)) return@forEach
                if (now - f.lastModified() > STALE_MS && f.delete()) {
                    android.util.Log.i("DshPetPack", "dropped stale download ${f.name}")
                }
            }
        }
    }

    /**
     * 按设置里的代理偏好逐个候选源下载（GitHub 才套前缀代理，其余直连）。
     *
     * 候选之间共用同一个 `.part`：代理源下到一半断了，切直连会从已有长度续传；
     * 服务端不支持 Range 时 [downloadOne] 会自动删档重下，不会把整包追加到半包后面。
     *
     * 每次尝试前先报一次 [onSource]：代理挂掉回落直连时，通知栏的「经代理」
     * 要跟着变成「直连」，否则用户看到的是一个已经不在用的线路。
     */
    private fun downloadTo(
        ctx: android.content.Context,
        url: String,
        out: File,
        expectedSize: Long,
        onSource: (PackSource) -> Unit,
        onDelta: (Long) -> Unit,
    ) {
        var last: Throwable? = null
        for (source in PetPackIndexClient.sources(ctx, url)) {
            onSource(source)
            try {
                downloadOne(source.url, out, expectedSize, onDelta)
                return
            } catch (e: CancelledException) {
                // 取消不是"这个源不行"，别拿它去喂下一个候选
                throw e
            } catch (e: Exception) {
                last = e
                PetPackIndexClient.logError("download(${source.url})", e)
            }
        }
        throw IllegalStateException(
            "all sources failed: " + (last?.message ?: "unknown"),
            last,
        )
    }

    /** Range 续传写一个文件；服务端不支持 Range 就从头写。 */
    private fun downloadOne(
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
                    // 取消检查放在块边界：开销就是一次 AtomicBoolean.get，
                    // 但用户点了取消最多 256KB 后就能停，残件完整可续传。
                    if (PetPackDownloadBus.isCancelled()) throw CancelledException()
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

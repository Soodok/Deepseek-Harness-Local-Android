package app.dsh.mobile.pet

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

data class PetPackImportResult(
    val ok: Boolean,
    val message: String?,
    /**
     * 失败原因码（见 [PetPackErrors]）。界面据此显示本地化文案，
     * 不再把 "pet-config.json not found" 这类英文技术串直接甩给用户。
     */
    val code: String = PetPackErrors.OK,
    val animationCount: Int = 0,
    val bytes: Long = 0L,
    /** 安装成功后落在哪个包目录下（pack id）；失败为 null。 */
    val packId: String? = null,
)

/** 导入失败原因码；与三语文案 `pet_pack_err_*` 一一对应。 */
object PetPackErrors {
    const val OK = "ok"
    const val BUSY = "busy"
    const val APK = "apk"
    const val OPEN_FAILED = "open_failed"
    const val NOT_ZIP = "not_zip"
    const val UNSAFE_PATH = "unsafe_path"
    const val NO_CONFIG = "no_config"
    const val BAD_CONFIG = "bad_config"
    const val NO_WEBP = "no_webp"
    const val BAD_WEBP = "bad_webp"
    const val MISSING_ANIM = "missing_anim"
    const val TOO_MANY_ENTRIES = "too_many_entries"
    const val ENTRY_TOO_LARGE = "entry_too_large"
    const val TOO_LARGE = "too_large"
    const val NO_SPACE = "no_space"
    /** 加密 zip：JDK 的 ZipInputStream 读不了 ZipCrypto/AES 条目，只能明确告知。 */
    const val ENCRYPTED = "encrypted"
    const val IO = "io"
}

/**
 * 动画包导入器：只接受 dsh-pet-android 的 WebP 包（zip）。
 *
 * 安全边界：
 *  - 只写 filesDir/pet，不碰 dsh-home / profiles；
 *  - **格式硬校验**：先看文件头是不是 zip（PK\x03\x04），每个 webp 再验 RIFF/WEBP 头，
 *    改名成 .webp 的图片/文本一律拦下 —— 只按扩展名信，装进去就是空白桌宠；
 *  - 拒绝绝对路径、`..`、非 webp/config 的可执行内容；
 *  - entry 数、单 entry、总解压量、可用空间全部有上限；
 *  - 先解到 pack.tmp-<uuid>，校验通过后再原子替换，失败保留旧包并删临时目录。
 */
object PetPackInstaller {
    private const val TAG = "DshPetPack"

    private const val MAX_ENTRIES = 2_000
    private const val MAX_CONFIG_BYTES = 1024 * 1024L
    private const val MAX_ENTRY_BYTES = 50L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 600L * 1024 * 1024
    private const val MIN_FREE_BYTES = 20L * 1024 * 1024

    /** 半成品目录超过这个时间还没被碰过，就认定是上次异常退出的残留。 */
    private const val STALE_MS = 60L * 60 * 1000

    private val busy = AtomicBoolean(false)

    /**
     * 在后台线程执行；onProgress/onDone 都在该后台线程回调。
     *
     * preferredId 为空时按包内 `pets[0].id` 取名（"main" 这种通用名回落到 "imported"），
     * 重名自动追加 -2/-3，**不会覆盖已安装的其它包**。
     *
     * replaceExisting = true 时反过来：同 id 视为**版本更新**，原地原子替换
     * （先解到临时目录，成功后旧目录挪开再删）。只有「从远端索引下载」这类
     * id 由发布方决定的场景才该传 true——否则重复下载会在设备上堆出
     * `xxx-2`、`xxx-3` 这样的 100MB+ 副本。
     */
    fun install(
        ctx: Context,
        uri: Uri,
        preferredId: String? = null,
        replaceExisting: Boolean = false,
        onProgress: (Int) -> Unit = {},
        onDone: (PetPackImportResult) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) {
            onDone(PetPackImportResult(false, "import already running", PetPackErrors.BUSY))
            return
        }
        val app = ctx.applicationContext
        Thread({
            val result = runCatching {
                doInstall(app, uri, preferredId, replaceExisting, onProgress)
            }.getOrElse { e ->
                Log.w(TAG, "import failed: ${e.message}", e)
                PetPackImportResult(false, e.message ?: e.javaClass.simpleName, codeFor(e.message))
            }
            busy.set(false)
            onDone(result)
        }, "dsh-pet-pack-import").apply { isDaemon = true }.start()
    }

    private fun doInstall(
        ctx: Context,
        uri: Uri,
        preferredId: String?,
        replaceExisting: Boolean,
        onProgress: (Int) -> Unit,
    ): PetPackImportResult {
        val resolver = ctx.contentResolver

        // ---- 前置：拒绝 APK ----
        // APK 本身也是 zip，光看文件头分不出来。资源包只发 .zip，安装包进来必然
        // 找不到 pet-config.json；这里直接按 MIME / 显示名拦下，给明确提示。
        if (looksLikeApk(ctx, uri)) {
            return PetPackImportResult(false, "apk is not a pet pack", PetPackErrors.APK)
        }

        // ---- 前置：文件头必须是 zip ----
        // 用户完全可能选到 .zip 后缀的下载残留 / 改名的 APK / 随便一个文件。
        // ZipInputStream 对非 zip 只是"读完没条目"，会误报成"缺少 pet-config.json"，
        // 这里先判签名，给一句能看懂的话。
        if (!looksLikeZip(resolver.openInputStream(uri))) {
            return PetPackImportResult(false, "not a zip archive", PetPackErrors.NOT_ZIP)
        }

        // ---- 第一遍：只读配置与条目清单，不落盘 ----
        var configText: String? = null
        val available = linkedSetOf<String>()
        val badWebp = mutableListOf<String>()
        var entryCount = 0
        var declaredTotal = 0L
        var sawUnsafe = false

        resolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        return PetPackImportResult(false, "too many entries", PetPackErrors.TOO_MANY_ENTRIES)
                    }
                    val name = safeName(entry.name)
                    if (name == null) {
                        sawUnsafe = true
                        continue
                    }
                    if (entry.isDirectory) continue
                    // macOS 打包残留（__MACOSX/、._xxx）不是素材，别当成坏包。
                    if (isNoise(name)) continue
                    val relevant = name.endsWith("pet-config.json") || isWebpEntry(name)
                    if (!relevant) continue
                    if (entry.size > MAX_ENTRY_BYTES) {
                        return PetPackImportResult(false, "entry too large: $name", PetPackErrors.ENTRY_TOO_LARGE)
                    }
                    if (entry.size > 0) declaredTotal += entry.size
                    if (declaredTotal > MAX_TOTAL_BYTES) {
                        return PetPackImportResult(false, "pack too large", PetPackErrors.TOO_LARGE)
                    }

                    if (name.endsWith("pet-config.json")) {
                        if (configText == null) {
                            configText = readLimited(zip, MAX_CONFIG_BYTES)
                        }
                    } else if (isWebpEntry(name)) {
                        val slug = webpSlug(name)
                        if (slug == null) {
                            badWebp += name
                        } else if (isWebpStream(zip)) {
                            available += slug
                        } else {
                            // 扩展名是 .webp，内容不是 —— 装进去只会看到空白桌宠。
                            badWebp += name
                        }
                    }
                }
            }
        } ?: return PetPackImportResult(false, "cannot open the selected file", PetPackErrors.OPEN_FAILED)

        if (sawUnsafe) {
            return PetPackImportResult(false, "unsafe path in archive", PetPackErrors.UNSAFE_PATH)
        }
        if (badWebp.isNotEmpty()) {
            val shown = badWebp.take(3).joinToString(", ")
            return PetPackImportResult(
                false,
                if (badWebp.size > 3) "$shown (+${badWebp.size - 3})" else shown,
                PetPackErrors.BAD_WEBP,
            )
        }
        val text = configText ?: return PetPackImportResult(false, "pet-config.json not found", PetPackErrors.NO_CONFIG)
        val parsed = PetPack.parseConfig(text)
            ?: return PetPackImportResult(false, "invalid pet-config.json", PetPackErrors.BAD_CONFIG)
        val missing = PetPack.missingRequired(parsed, available)
        if (missing.isNotEmpty()) {
            return PetPackImportResult(
                false,
                missing.take(4).joinToString(","),
                PetPackErrors.MISSING_ANIM,
            )
        }
        if (available.isEmpty()) {
            return PetPackImportResult(false, "no webp animations found", PetPackErrors.NO_WEBP)
        }

        val free = runCatching { StatFs(ctx.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        val need = if (declaredTotal > 0) declaredTotal + declaredTotal / 5 else MIN_FREE_BYTES
        if (free < need + MIN_FREE_BYTES) {
            return PetPackImportResult(false, "not enough free space", PetPackErrors.NO_SPACE)
        }

        val preferred = preferredId?.takeIf { it.isNotBlank() }
            ?: parsed.spec.id.takeIf { it.isNotBlank() && it != "main" }
            ?: "imported"
        // 显式指定 id 且允许替换时，同 id 即「更新」：不追加 -2，直接复用目录名，
        // 由下面的「旧包先改名、成功后再删」流程完成原子替换。
        val packId = if (replaceExisting) PetPrefs.safeId(preferred) else PetPack.uniqueId(ctx, preferred)
        val packDir = PetPrefs.packDir(ctx, packId)
        val parent = PetPrefs.packsRoot(ctx)
        parent.mkdirs()
        // 清理上次进程被杀留下的半成品；只动本功能自己的前缀。
        // 这里用 0 阈值（无条件清）是安全的：import 有 busy 单飞锁，不会有第二个在写。
        sweepStale(ctx, 0L)
        val tmp = File(parent, ".tmp-${UUID.randomUUID()}")
        if (!tmp.mkdirs()) return PetPackImportResult(false, "cannot create temp directory", PetPackErrors.IO)

        var written = 0L
        var lastPercent = -1
        try {
            resolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = safeName(entry.name) ?: continue
                        if (entry.isDirectory) continue
                        if (isNoise(name)) continue
                        if (name.endsWith("pet-config.json")) {
                            val configBytes = readLimited(zip, MAX_CONFIG_BYTES).toByteArray(Charsets.UTF_8)
                            if (configBytes.size > MAX_CONFIG_BYTES) throw IllegalStateException("config too large")
                            File(tmp, "pet-config.json").writeBytes(configBytes)
                            written += configBytes.size
                        } else if (isWebpEntry(name)) {
                            val slug = webpSlug(name) ?: continue
                            if (!available.contains(slug)) continue
                            val outFile = File(File(tmp, "webp"), "$slug.webp")
                            outFile.parentFile?.mkdirs()
                            var entryWritten = 0L
                            FileOutputStream(outFile).use { fos ->
                                val out = BufferedOutputStream(fos, 64 * 1024)
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n < 0) break
                                    entryWritten += n
                                    written += n
                                    if (entryWritten > MAX_ENTRY_BYTES) throw IllegalStateException("entry too large")
                                    if (written > MAX_TOTAL_BYTES) throw IllegalStateException("pack too large")
                                    out.write(buf, 0, n)
                                }
                                out.flush()
                            }
                        } else if (isFontEntry(name)) {
                            val outFile = File(File(tmp, "font"), name.substringAfterLast('/'))
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                val out = BufferedOutputStream(fos, 64 * 1024)
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n < 0) break
                                    written += n
                                    if (written > MAX_TOTAL_BYTES) throw IllegalStateException("pack too large")
                                    out.write(buf, 0, n)
                                }
                                out.flush()
                            }
                        }
                        if (declaredTotal > 0) {
                            val percent = ((written * 100) / declaredTotal).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("cannot reopen the selected file")

            if (!File(tmp, "pet-config.json").isFile) {
                throw IllegalStateException("pet-config.json was not extracted")
            }
            if (!File(tmp, "webp").isDirectory) {
                throw IllegalStateException("webp directory was not extracted")
            }

            // ---- 原子替换：旧包先改名，失败可恢复 ----
            val old = File(parent, ".old-${UUID.randomUUID()}")
            val hadOld = packDir.exists()
            if (hadOld && !packDir.renameTo(old)) {
                throw IllegalStateException("cannot move the old pack aside")
            }
            if (!tmp.renameTo(packDir)) {
                if (hadOld) old.renameTo(packDir)
                throw IllegalStateException("cannot install the new pack")
            }
            if (hadOld) old.deleteRecursively()
            PetPrefs.setPackId(ctx, packId)
            onProgress(100)
            return PetPackImportResult(
                ok = true,
                message = null,
                code = PetPackErrors.OK,
                animationCount = available.size,
                bytes = written,
                packId = packId,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "import aborted: ${t.message}")
            tmp.deleteRecursively()
            val msg = t.message ?: t.javaClass.simpleName
            return PetPackImportResult(false, msg, codeFor(msg))
        }
    }

    /** 底层异常 → 原因码。加密 zip 的文案由 JDK 固定为 "encrypted ZIP entry not supported"。 */
    private fun codeFor(msg: String?): String {
        val m = msg.orEmpty().lowercase()
        return when {
            m.contains("encrypted") -> PetPackErrors.ENCRYPTED
            m.contains("entry too large") -> PetPackErrors.ENTRY_TOO_LARGE
            m.contains("too large") -> PetPackErrors.TOO_LARGE
            m.contains("not extracted") -> PetPackErrors.BAD_CONFIG
            m.contains("space") || m.contains("enospc") -> PetPackErrors.NO_SPACE
            else -> PetPackErrors.IO
        }
    }

    /**
     * 清理 `.tmp-` / `.old-` 半成品。
     *
     * 导入中进程被杀、或 rename 失败回滚不完整时，这几百 MB 的临时目录会一直躺在
     * filesDir/pet/packs 里 —— 只有下次导入才会被扫到。这里给 [PetPack.ensureHeavy]
     * 也留一个入口，[olderThanMs] 默认 1 小时，避免误删正在写入的那一个。
     */
    fun sweepStale(ctx: Context, olderThanMs: Long = STALE_MS) {
        val root = PetPrefs.packsRoot(ctx)
        if (!root.isDirectory) return
        val now = System.currentTimeMillis()
        runCatching {
            root.listFiles()?.forEach { f ->
                val n = f.name
                if (!n.startsWith(".tmp-") && !n.startsWith(".old-")) return@forEach
                val age = now - f.lastModified()
                if (olderThanMs <= 0L || age > olderThanMs) {
                    if (f.deleteRecursively()) Log.i(TAG, "swept stale ${n} (${age / 1000}s old)")
                }
            }
        }
    }

    /** APK 也是 zip：按 MIME 或显示名判，别让安装包混进资源包导入。 */
    private fun looksLikeApk(ctx: Context, uri: Uri): Boolean {
        if (ctx.contentResolver.getType(uri) == "application/vnd.android.package-archive") return true
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        return name?.endsWith(".apk", ignoreCase = true) == true
    }

    /** zip 本地文件头 / 空包目录 / 分卷签名；读不到或不匹配都算"不是 zip"。 */
    private fun looksLikeZip(input: InputStream?): Boolean {
        if (input == null) return false
        return runCatching {
            input.use {
                val head = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = it.read(head, read, 4 - read)
                    if (n < 0) break
                    read += n
                }
                read == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                    ((head[2] == 3.toByte() && head[3] == 4.toByte()) ||
                        (head[2] == 5.toByte() && head[3] == 6.toByte()) ||
                        (head[2] == 7.toByte() && head[3] == 8.toByte()))
            }
        }.getOrDefault(false)
    }

    /** 读 12 字节头：RIFF....WEBP 才算 WebP；读不满（截断）也算不合格。 */
    private fun isWebpStream(input: InputStream): Boolean {
        val h = ByteArray(12)
        var read = 0
        while (read < 12) {
            val n = input.read(h, read, 12 - read)
            if (n < 0) break
            read += n
        }
        if (read < 12) return false
        return h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() &&
            h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte() &&
            h[8] == 'W'.code.toByte() && h[9] == 'E'.code.toByte() &&
            h[10] == 'B'.code.toByte() && h[11] == 'P'.code.toByte()
    }

    /** macOS 打包残留：不算素材，也不算"坏包"。 */
    private fun isNoise(name: String): Boolean {
        if (name.startsWith("__MACOSX/")) return true
        val base = name.substringAfterLast('/')
        return base.startsWith("._") || base == ".DS_Store"
    }

    private fun safeName(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("/") || raw.contains(':')) return null
        val name = raw.replace('\\', '/').trimStart('/')
        if (name.isEmpty()) return null
        if (name.split('/').any { it == ".." || it == "." }) return null
        return name
    }

    private fun isWebpEntry(name: String): Boolean {
        val lower = name.lowercase()
        if (!lower.endsWith(".webp")) return false
        // 只接受 webp/<slug>.webp 或 assets/webp/<slug>.webp
        val parts = name.split('/')
        val base = parts.lastOrNull() ?: return false
        if (base.isEmpty()) return false
        val parentName = parts.getOrNull(parts.size - 2) ?: return false
        return parentName == "webp"
    }

    /** fonts/<文件>.ttf|otf：包自带的气泡字体，原样解出（兼容旧命名 font/）。 */
    private fun isFontEntry(name: String): Boolean {
        val lower = name.lowercase()
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) return false
        val parts = name.split('/')
        val base = parts.lastOrNull() ?: return false
        if (base.isEmpty()) return false
        return parts.getOrNull(parts.size - 2) == "fonts" ||
            parts.getOrNull(parts.size - 2) == "font"
    }

    private fun webpSlug(name: String): String? {
        val base = name.substringAfterLast('/')
        if (!base.endsWith(".webp", ignoreCase = true)) return null
        val slug = base.substring(0, base.length - 5)
        if (slug.isBlank() || slug.contains('/') || slug.contains('\\')) return null
        return slug
    }

    private fun readLimited(input: InputStream, max: Long): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IllegalStateException("file too large")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}

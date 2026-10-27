package app.dsh.mobile.pet

import android.content.Context
import android.net.Uri
import android.os.StatFs
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
    val animationCount: Int = 0,
    val bytes: Long = 0L,
    /** 安装成功后落在哪个包目录下（pack id）；失败为 null。 */
    val packId: String? = null,
)

/**
 * 动画包导入器：只接受 dsh-pet-android 的 WebP 包（zip 或 APK）。
 *
 * 安全边界：
 *  - 只写 filesDir/pet，不碰 dsh-home / profiles；
 *  - 拒绝绝对路径、`..`、非 webp/config 的可执行内容；
 *  - entry 数、单 entry、总解压量、可用空间全部有上限；
 *  - 先解到 pack.tmp-<uuid>，校验通过后再原子替换，失败保留旧包。
 */
object PetPackInstaller {
    private const val TAG = "DshPetPack"

    private const val MAX_ENTRIES = 2_000
    private const val MAX_CONFIG_BYTES = 1024 * 1024L
    private const val MAX_ENTRY_BYTES = 50L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 600L * 1024 * 1024
    private const val MIN_FREE_BYTES = 20L * 1024 * 1024

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
            onDone(PetPackImportResult(false, "import already running"))
            return
        }
        val app = ctx.applicationContext
        Thread({
            val result = runCatching {
                doInstall(app, uri, preferredId, replaceExisting, onProgress)
            }.getOrElse { e ->
                Log.w(TAG, "import failed: ${e.message}", e)
                PetPackImportResult(false, e.message ?: e.javaClass.simpleName)
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

        // ---- 第一遍：只读配置与条目清单，不落盘 ----
        var configText: String? = null
        val available = linkedSetOf<String>()
        var entryCount = 0
        var declaredTotal = 0L
        var sawUnsafe = false

        resolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        return PetPackImportResult(false, "too many entries")
                    }
                    val name = safeName(entry.name)
                    if (name == null) {
                        sawUnsafe = true
                        continue
                    }
                    if (entry.isDirectory) continue
                    val relevant = name.endsWith("pet-config.json") || isWebpEntry(name)
                    if (!relevant) continue
                    if (entry.size > MAX_ENTRY_BYTES) {
                        return PetPackImportResult(false, "entry too large: $name")
                    }
                    if (entry.size > 0) declaredTotal += entry.size
                    if (declaredTotal > MAX_TOTAL_BYTES) {
                        return PetPackImportResult(false, "pack too large")
                    }

                    if (name.endsWith("pet-config.json")) {
                        if (configText == null) {
                            configText = readLimited(zip, MAX_CONFIG_BYTES)
                        }
                    } else if (isWebpEntry(name)) {
                        val slug = webpSlug(name)
                        if (slug != null) available += slug
                    }
                }
            }
        } ?: return PetPackImportResult(false, "cannot open the selected file")

        if (sawUnsafe) {
            return PetPackImportResult(false, "unsafe path in archive")
        }
        val text = configText ?: return PetPackImportResult(false, "pet-config.json not found")
        val parsed = PetPack.parseConfig(text)
            ?: return PetPackImportResult(false, "invalid pet-config.json")
        val missing = PetPack.missingRequired(parsed, available)
        if (missing.isNotEmpty()) {
            return PetPackImportResult(false, "missing required animations: ${missing.take(4).joinToString(",")}")
        }
        if (available.isEmpty()) {
            return PetPackImportResult(false, "no webp animations found")
        }

        val free = runCatching { StatFs(ctx.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        val need = if (declaredTotal > 0) declaredTotal + declaredTotal / 5 else MIN_FREE_BYTES
        if (free < need + MIN_FREE_BYTES) {
            return PetPackImportResult(false, "not enough free space")
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
        runCatching {
            parent.listFiles()
                ?.filter { it.name.startsWith(".tmp-") || it.name.startsWith(".old-") }
                ?.forEach { it.deleteRecursively() }
        }
        val tmp = File(parent, ".tmp-${UUID.randomUUID()}")
        if (!tmp.mkdirs()) return PetPackImportResult(false, "cannot create temp directory")

        var written = 0L
        var lastPercent = -1
        try {
            resolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = safeName(entry.name) ?: continue
                        if (entry.isDirectory) continue
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
                animationCount = available.size,
                bytes = written,
                packId = packId,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "import aborted: ${t.message}")
            tmp.deleteRecursively()
            return PetPackImportResult(false, t.message ?: t.javaClass.simpleName)
        }
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

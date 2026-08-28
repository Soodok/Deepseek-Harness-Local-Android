package app.dsh.mobile.engine

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 环境扩展管理器（v1.2.0）。
 *
 * 职责：扩展目录 filesDir/engine/extensions/<id>/ 的下载、SHA-256 校验、解包、
 * 三态跟踪（未下载/已下载未激活/已激活），以及为引擎环境注入已激活扩展的
 * bin 与 lib 路径（EngineConfig.buildEnv 经 [activeRoots] 取用）。
 *
 * 三态判定：
 *  - 红  NOT_DOWNLOADED：extensions/<id>/ 无 .ext-version 标记
 *  - 黄  DOWNLOADED    ：已安装但 prefs 未标记激活（可用【激活】并入引擎 PATH）
 *  - 绿  ACTIVATED     ：已安装且已激活（重启引擎后二进制进入 PATH/LD_LIBRARY_PATH）
 *
 * 扩展包布局约定（Termux 构建产物重打包）：zip 根平铺 bin/ lib/ share/ etc/；
 * 若包内仍是 usr/ 前缀布局则自动拍平，保证 <id>/bin、<id>/lib 稳定存在。
 */
class ExtensionManager(private val ctx: Context) {

    // ================= 数据模型 =================

    data class Extension(
        val id: String,
        val name: String,
        val version: String,
        val category: String,
        val desc: String,
        val sizeMB: Long,
        val bins: List<String>,
        val urlByAbi: Map<String, String>,
        val sha256ByAbi: Map<String, String>,
    ) {
        fun urlFor(abiKey: String): String? = urlByAbi[abiKey]
        fun sha256For(abiKey: String): String? =
            sha256ByAbi[abiKey]?.takeIf { it.isNotBlank() }
    }

    enum class ExtState { NOT_DOWNLOADED, DOWNLOADED, ACTIVATED }

    // ================= 存储 =================

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val extRoot get() = File(EngineConfig.engineRoot(ctx), "extensions")

    private fun dirOf(id: String) = File(extRoot, id)
    private fun markerOf(id: String) = File(dirOf(id), MARKER)

    // ================= 目录（清单） =================

    /** 解析内置扩展清单，仅保留当前设备 ABI 有下载源的条目 */
    fun loadCatalog(): List<Extension> {
        val raw = ctx.assets.open("extensions/catalog.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val mirror = root.optString("mirror", "")
        val items = root.getJSONArray("items")
        val abi = deviceAbiKey()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val urlObj = o.getJSONObject("url")
            if (!urlObj.has(abi)) return@mapNotNull null
            val shaObj = o.optJSONObject("sha256") ?: JSONObject()
            val rawUrl = urlObj.getString(abi)
            val url = if (rawUrl.startsWith("http")) rawUrl else "$mirror/$rawUrl"
            Extension(
                id = o.getString("id"),
                name = o.getString("name"),
                version = o.getString("version"),
                category = o.optString("category", "扩展"),
                desc = o.optString("desc", ""),
                sizeMB = o.optLong("sizeMB", 0L),
                bins = o.optJSONArray("bins")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                urlByAbi = mapOf(abi to url),
                sha256ByAbi = mapOf(abi to shaObj.optString(abi, "")),
            )
        }
    }

    /** 设备 ABI → 清单键（app 仅出 arm64/x86_64 双架构包） */
    fun deviceAbiKey(): String =
        when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        }

    // ================= 三态 =================

    fun state(id: String): ExtState {
        val installed = markerOf(id).isFile
        val activated = prefs.getBoolean(keyActivated(id), false)
        if (!installed && activated) {
            // 目录被手动清掉：惰性修正标记，避免幽灵激活
            prefs.edit().putBoolean(keyActivated(id), false).apply()
        }
        return when {
            !installed -> ExtState.NOT_DOWNLOADED
            !prefs.getBoolean(keyActivated(id), false) -> ExtState.DOWNLOADED
            else -> ExtState.ACTIVATED
        }
    }

    /** 已安装扩展的版本号（来自安装标记），未安装返回 null */
    fun installedVersion(id: String): String? =
        markerOf(id).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    // ================= 下载 / 解包（阻塞调用，须在 IO 线程） =================

    /**
     * 下载并安装扩展：下载 → SHA-256 校验（清单非空时强制）→ 解包 → 拍平 usr 布局
     * → 恢复可执行位 → 写版本标记。安装目录以临时名落盘后 rename 原子发布
     * （SELinux 禁 link()，rename 是项目既定原子操作约定）。
     *
     * @param onProgress 0f..1f（下载字节数驱动；服务器未给 Content-Length 时不回调）
     */
    fun download(ext: Extension, onProgress: (Float) -> Unit = {}) {
        val abi = deviceAbiKey()
        val url = ext.urlFor(abi) ?: throw IllegalStateException("清单中无 $abi 架构下载源")
        val expected = ext.sha256For(abi)
        val cacheZip = File(ctx.cacheDir, "ext-${ext.id}.zip")
        val finalDir = dirOf(ext.id)
        val tmpDir = File(extRoot, "${ext.id}.tmp-install")

        try {
            extRoot.mkdirs()
            // 半截安装（目录在但无标记）先清场重装
            if (finalDir.exists() && !markerOf(ext.id).isFile) finalDir.deleteRecursively()
            if (tmpDir.exists()) tmpDir.deleteRecursively()

            downloadTo(url, cacheZip, onProgress)
            expected?.let { exp ->
                val actual = RuntimeInstaller.sha256(cacheZip)
                check(actual.equals(exp, ignoreCase = true)) {
                    "SHA-256 校验失败: expected=$exp actual=$actual"
                }
            }

            unzip(cacheZip, tmpDir)
            flattenUsrLayout(tmpDir)
            restoreExecBits(tmpDir)
            File(tmpDir, MARKER).writeText(ext.version)

            check(tmpDir.renameTo(finalDir)) { "扩展目录发布失败（rename）: ${tmpDir.path}" }
            Log.i(TAG, "extension ${ext.id} installed at ${finalDir.path}")
        } finally {
            cacheZip.delete()
        }
    }

    private fun downloadTo(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        check(code in 200..299) { "下载失败 HTTP $code: $url" }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                }
            }
        }
    }

    private fun unzip(zip: File, target: File) {
        var total = 0L
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) total += entries.nextElement().size
        }
        var done = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(target, entry.name).canonicalFile
                check(out.path.startsWith(target.canonicalPath)) { "zip-slip: ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { done += zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    /** Termux 原始包是 usr/ 前缀布局：usr 存在且根下无 bin 时，把 usr 内条目提升到根 */
    private fun flattenUsrLayout(root: File) {
        val usr = File(root, "usr")
        if (!usr.isDirectory || File(root, "bin").isDirectory) return
        usr.listFiles()?.forEach { child ->
            val dest = File(root, child.name)
            if (!child.renameTo(dest)) {
                // rename 失败（跨设备等）退化为复制
                child.copyRecursively(dest, overwrite = true)
                child.deleteRecursively()
            }
        }
        usr.delete()
    }

    private fun restoreExecBits(root: File) {
        listOf("bin", "usr/bin").forEach { rel ->
            File(root, rel).takeIf { it.isDirectory }?.listFiles()?.forEach {
                it.setExecutable(true, false)
                it.setReadable(true, false)
            }
        }
    }

    // ================= 激活 / 停用 / 卸载 =================

    /** 激活：并入引擎 PATH/LD_LIBRARY_PATH（由调用方重启引擎生效） */
    fun activate(id: String) {
        check(markerOf(id).isFile) { "扩展未安装，无法激活" }
        prefs.edit().putBoolean(keyActivated(id), true).apply()
    }

    /** 停用：从引擎环境中摘除（保留文件，可随时再激活） */
    fun deactivate(id: String) = prefs.edit().putBoolean(keyActivated(id), false).apply()

    /** 已激活且目录健在的扩展数（UI 副标题计数用） */
    fun activeCount(): Int = activeRoots(ctx).size

    /** 卸载：删除文件与全部状态标记 */
    fun remove(id: String) {
        dirOf(id).deleteRecursively()
        prefs.edit().remove(keyActivated(id)).apply()
    }

    // ================= 引擎环境注入（供 EngineConfig 调用） =================

    companion object {
        private const val TAG = "ExtensionManager"
        private const val PREFS = "dsh_extensions"
        private const val MARKER = ".ext-version"
        private const val KEY_PREFIX = "activated_"

        private fun keyActivated(id: String) = "$KEY_PREFIX$id"

        /** 已激活且目录健在的扩展根目录列表（EngineConfig.buildEnv 拼 PATH 用） */
        fun activeRoots(ctx: Context): List<File> {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val extRoot = File(EngineConfig.engineRoot(ctx), "extensions")
            return prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
                .filter { prefs.getBoolean(it, false) }
                .mapNotNull { key ->
                    val dir = File(extRoot, key.removePrefix(KEY_PREFIX))
                    dir.takeIf { File(it, MARKER).isFile }
                }
                .sortedBy { it.name }
        }
    }
}

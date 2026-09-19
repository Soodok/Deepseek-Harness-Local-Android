package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Node 运行时安装器。
 *
 * 两条安装路径（优先级从高到低）：
 *  1. assets/runtime.zip —— CI 构建时注入的离线包（推荐，无网络依赖）
 *  2. MANIFEST.json 声明的远程 URL —— 开发期热更新，强制 SHA-256 校验
 *
 * 安装 = 解压到 filesDir/engine + 恢复可执行位。
 * zip 不保存 Unix 权限，因此解压后对 bin/ 下所有文件 chmod 755；
 * targetSdk 28 下 SELinux 允许对 filesDir 内文件 execve，无需 jniLibs 伪装。
 */
class RuntimeInstaller(private val ctx: Context) {

    data class Manifest(val version: String, val url: String?, val sha256: String?)

    private val root get() = EngineConfig.engineRoot(ctx)
    private val stampFile get() = File(root, ".runtime-version")

    fun readManifest(): Manifest {
        val raw = ctx.assets.open("runtime/MANIFEST.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(raw)
        return Manifest(
            version = obj.getString("version"),
            url = obj.optString("url").takeIf { it.isNotEmpty() },
            sha256 = obj.optString("sha256").takeIf { it.isNotEmpty() },
        )
    }

    /** 确保运行时就绪；已安装且版本匹配且关键资产完整则跳过。
     *  @param onProgress 解压进度 0f..1f（仅解压阶段有值；频繁调用方自行节流）
     *
     *  m1.13 完整性校验：m1.12 事故实锤——覆盖升级后 @deepseek-ai 目录只剩空壳
     *  （JS 文件未落地）但 .runtime-version 已写成功，"engine 存在+版本匹配"即跳装
     *  → MODULE_NOT_FOUND 反复重启。故改为【版本匹配 + 关键资产存在】双重条件，
     *  任一关键资产缺失即视为安装不完整，删除重装。 */
    fun ensureInstalled(onProgress: (Float) -> Unit = {}) {
        val manifest = readManifest()
        if (EngineConfig.nodeBin(ctx).exists() &&
            stampFile.exists() && stampFile.readText().trim() == manifest.version &&
            isRootComplete()
        ) {
            Log.i(TAG, "runtime ${manifest.version} already installed (assets verified)")
            return
        }
        if (isRootComplete()) {
            // 版本变了才重装，属正常升级
        } else {
            Log.w(TAG, "runtime install incomplete (missing assets); forcing reinstall")
        }
        install(manifest, onProgress)
    }

    /**
     * 完整性探针：校验最容易被部分解压吞掉的【引擎入口 JS】与【关键动态库】存在。
     * 只查"必须存在"的锚点文件，避免与运行时裁剪的解耦（不校验具体数量）。
     * @return true 表示本次安装是完整的
     */
    private fun isRootComplete(): Boolean {
        // 1) 引擎入口 bin.js（MODULE_NOT_FOUND 的直接案发现场）
        if (!EngineConfig.dshEntry(ctx).isFile) return false
        // 2) bash 依赖闭环：readline SONAME 别名（m1.5 事故）
        val lib = File(root, "lib")
        if (!File(lib, "libreadline.so.8").isFile) return false
        // 3) node 本体可执行位（安装器 restoreExecBits 之后应为 true）
        if (!EngineConfig.nodeBin(ctx).canExecute()) return false
        // 4) @deepseek-ai 作用域下至少要有一批真实文件（空壳=部分解压）
        val dshAi = File(root, "lib/node_modules/@deepseek-ai")
        val fileCount = dshAi.walkTopDown().filter { it.isFile }.count()
        if (fileCount < MIN_DSH_AI_FILES) return false
        return true
    }

    private fun install(manifest: Manifest, onProgress: (Float) -> Unit) {
        Log.i(TAG, "installing runtime ${manifest.version}")
        installing = true
        try {
            val assetZip = File(ctx.cacheDir, "runtime.zip")
            try {
                // 路径 1：assets 内置包
                ctx.assets.open("runtime.zip").use { input ->
                    assetZip.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                // 路径 2：远程下载（必须带 SHA-256）
                val url = manifest.url ?: throw IllegalStateException(
                    "既无 assets/runtime.zip 也未配置下载 URL", e,
                )
                downloadTo(url, assetZip)
                manifest.sha256?.let { expected ->
                    val actual = sha256(assetZip)
                    check(actual.equals(expected, ignoreCase = true)) {
                        "runtime 校验失败: expected=$expected actual=$actual"
                    }
                }
            }

            // v1.2.21 事故修复：原 root.deleteRecursively() 会把 engine/ 整个删光，
            // extensions/（用户下载的全家扩展）一起陪葬；且删除 70MB 目录耗时较长，
            // 与用户点扩展下载并发 → 刚发布的扩展目录被删到只剩 lib → rename 报
            // "扩展目录发布失败"。现在按 zip 实际顶层目录（bin/etc/lib/share/usr）
            // 精确替换，extensions/ 等用户资产永不触碰。
            root.mkdirs()
            val topDirs = java.util.zip.ZipFile(assetZip).use { zf ->
                zf.entries().asSequence()
                    .map { it.name.substringBefore('/') }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
            topDirs.forEach { top ->
                File(root, top).deleteRecursively()
            }

            unzip(assetZip, root, onProgress)
            restoreExecBits(root)
            stampFile.writeText(manifest.version)
            assetZip.delete()
            Log.i(TAG, "runtime installed at $root (kept top dirs: ${topDirs.joinToString()})")
        } finally {
            installing = false
        }
    }

    private fun downloadTo(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        // getInputStream 隐式触发连接；非 2xx 视为失败
        val code = conn.responseCode
        check(code in 200..299) { "下载失败 HTTP $code: $url" }
        conn.inputStream.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    private fun unzip(zip: File, target: File, onProgress: (Float) -> Unit) {
        // 预扫 central directory 拿总未压缩字节（ZipInputStream 流式读时 size 可能为 -1，
        // ZipFile 走 central directory 是精确的）→ 真实确定性进度而非假转圈
        var total = 0L
        java.util.zip.ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) total += entries.nextElement().size
        }
        var done = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(target, entry.name).canonicalFile
                // 防 zip-slip：解压目标必须落在 engineRoot 内
                check(out.path.startsWith(target.canonicalPath)) { "zip-slip: ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { done += zis.copyTo(it) }
                    if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                }
                zis.closeEntry()
            }
        }
    }

    /** bin 与 usr/bin 下全部文件恢复可执行位（zip 无权限语义） */
    private fun restoreExecBits(root: File) {
        listOf("bin", "usr/bin").forEach { dir ->
            File(root, dir).takeIf { it.isDirectory }?.listFiles()?.forEach {
                it.setExecutable(true, false)
                it.setReadable(true, false)
            }
        }
    }

    companion object {
        /** runtime 装配进行中：ExtensionManager 拒绝在此窗口安装扩展（防互删） */
        @Volatile
        var installing: Boolean = false
            private set

        private const val TAG = "RuntimeInstaller"

        /** @deepseek-ai 作用域下"完整安装"至少应存在的文件数（m1.12 空壳事故阈值） */
        private const val MIN_DSH_AI_FILES = 100

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

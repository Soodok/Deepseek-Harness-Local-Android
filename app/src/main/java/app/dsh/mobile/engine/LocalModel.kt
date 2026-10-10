package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 本地嵌入模型管理（v1.2.121）—— FastPicker 语义层的数据与进程后端。
 *
 * ## 角色
 * [FastPicker] 的第 ② 层需要「目标描述 vs 屏幕文本」的语义相似度。
 * 本类负责把它跑起来：
 *  1. **模型下载**：bge-small-zh-v1.5（**q4_k_m 仅 15MB**）—— 中文语义匹配，
 *     24M 参数，手机上算一次嵌入是毫秒级；
 *  2. **服务启动**：用扩展提供的 `llama-server` 在 127.0.0.1:3085 起
 *     OpenAI 兼容 HTTP 服务（`/v1/embeddings`），FastPicker 直接调它；
 *  3. **生命周期**：按需启动、失败静默（FastPicker 自动回退云端，不阻塞用户）。
 *
 * ## 为什么用嵌入模型而不是生成模型
 * 生成模型逐 token 解码（几百 ms 到秒级）；嵌入是一次前向传播，15MB 模型
 * 在手机上**几毫秒**。而「从屏幕文本里挑最相关的那个」本质是排序问题，
 * 嵌入天然合适 —— 这是整个快速决策层能到毫秒级的关键。
 *
 * ## 依赖
 * - 扩展 `llama-cpp`（提供 llama-server 二进制）
 * - 模型文件（首次使用时下载，15MB）
 */
object LocalModel {

    private const val TAG = "LocalModel"

    /** 模型下载地址（HuggingFace；国内可换镜像，见 [MIRRORS]） */
    private const val MODEL_FILE = "bge-small-zh-v1.5-q4_k_m.gguf"
    private val MIRRORS = listOf(
        "https://hf-mirror.com/CompendiumLabs/bge-small-zh-v1.5-gguf/resolve/main/$MODEL_FILE",
        "https://huggingface.co/CompendiumLabs/bge-small-zh-v1.5-gguf/resolve/main/$MODEL_FILE",
    )

    /** 模型存放目录（引擎私有区，随扩展一起管理） */
    fun modelsDir(ctx: Context): File =
        File(EngineConfig.engineRoot(ctx), "models").apply { mkdirs() }

    fun modelFile(ctx: Context): File = File(modelsDir(ctx), MODEL_FILE)

    fun isModelReady(ctx: Context): Boolean =
        modelFile(ctx).let { it.isFile && it.length() > 5_000_000 }   // 15MB 模型，>5MB 视为完整

    fun isServerRunning(): Boolean = LocalEmbedder.isReady()

    /** llama-server 二进制（扩展激活后进 PATH） */
    private fun llamaServer(ctx: Context): File? {
        val roots = ExtensionManager.activeRoots(ctx)
        val candidates = roots.map { File(it, "bin/llama-server") } +
            listOf(File(EngineConfig.engineRoot(ctx), "bin/llama-server"))
        return candidates.firstOrNull { it.canExecute() }
    }

    /**
     * 确保模型 + 服务就绪（后台线程调用；幂等）。
     * @param onProgress (0f..1f 下载进度, 说明文字)
     * @return 成功与否
     */
    fun ensureReady(ctx: Context, onProgress: (Float, String) -> Unit = { _, _ -> }): Boolean {
        if (isServerRunning()) return true

        val server = llamaServer(ctx) ?: run {
            onProgress(0f, "llama-server not installed (install llama.cpp extension first)")
            return false
        }
        val model = modelFile(ctx)
        if (!isModelReady(ctx)) {
            onProgress(0f, "downloading embedding model (15MB)…")
            if (!downloadModel(model, onProgress)) {
                onProgress(0f, "model download failed")
                return false
            }
        }
        onProgress(1f, "starting local inference server…")
        return startServer(ctx, server, model)
    }

    /** 下载模型（多镜像 failover；写到 .part 再改名，避免半截文件被当成完整模型） */
    private fun downloadModel(target: File, onProgress: (Float, String) -> Unit): Boolean {
        val part = File(target.absolutePath + ".part")
        for (url in MIRRORS) {
            val ok = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { ins ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct / 100f, "downloading embedding model $pct%")
                            }
                        }
                    }
                }
                conn.disconnect()
                part.length() > 5_000_000
            }.getOrElse {
                Log.w(TAG, "model download failed from $url: ${it.message}")
                false
            }
            if (ok) {
                if (part.renameTo(target)) {
                    Log.i(TAG, "model ready: ${target.absolutePath} (${target.length()} bytes)")
                    return true
                }
            }
            part.delete()
        }
        return false
    }

    /**
     * 启动 llama-server（嵌入模式）。
     * `--embeddings` 开启 /v1/embeddings；`-c 512` 上下文够放「目标 + 若干屏幕文本」；
     * `--threads` 留 2 核给 UI，避免抢引擎资源。
     */
    private fun startServer(ctx: Context, server: File, model: File): Boolean {
        return runCatching {
            val pb = ProcessBuilder(
                server.absolutePath,
                "-m", model.absolutePath,
                "--embeddings",
                "--host", "127.0.0.1",
                "--port", LocalEmbedder.PORT.toString(),
                "-c", "512",
                "-t", "2",
                "--no-webui",
            )
            pb.redirectErrorStream(true)
            // 库路径：扩展自带的 .so 在扩展 lib/ 下
            val extRoot = server.parentFile?.parentFile
            if (extRoot != null) {
                pb.environment()["LD_LIBRARY_PATH"] =
                    "${extRoot.absolutePath}/lib:" +
                    "${EngineConfig.engineRoot(ctx).absolutePath}/lib"
            }
            val p = pb.start()
            // 排空输出（不读会阻塞子进程）
            Thread({ runCatching { p.inputStream.use { it.readBytes() } } }, "llama-server-log")
                .apply { isDaemon = true }.start()
            // 等服务就绪（最多 30 秒；首次加载模型要几秒）
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                LocalEmbedder.resetProbe()
                if (LocalEmbedder.isReady()) {
                    Log.i(TAG, "llama-server ready on :${LocalEmbedder.PORT}")
                    return true
                }
                if (!p.isAlive) {
                    Log.w(TAG, "llama-server exited early (code=${p.exitValue()})")
                    return false
                }
            }
            Log.w(TAG, "llama-server did not become ready in 30s")
            false
        }.getOrElse {
            Log.w(TAG, "llama-server start failed: ${it.message}")
            false
        }
    }

    /** 停止服务（用户关闭开关时调用） */
    @Volatile private var serverProcess: Process? = null

    fun stop() {
        runCatching {
            Privilege.runSu("pkill -f 'llama-server.*${LocalEmbedder.PORT}'")
        }
        runCatching { serverProcess?.destroy() }
        serverProcess = null
        LocalEmbedder.resetProbe()
    }
}

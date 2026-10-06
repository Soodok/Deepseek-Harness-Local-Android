package app.dsh.mobile.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 云端语音合成（v1.2.77）—— 自建 TTS 服务的客户端。
 *
 * ## 为什么自建
 * 系统 TTS 的中文音色机械感强（尤其国产 ROM 自带引擎）。服务端跑 **edge-tts**
 * （微软 Edge 神经网络语音），质量高一个档次且**完全免费、无需 API Key**；
 * 部署在用户自己的服务器上（`/opt/dsh-tts`），不依赖第三方额度政策。
 *
 * ## 与系统 TTS 的关系
 * **系统 TTS 仍是默认**（离线、零配置）；用户在设置页填了服务地址后才走云端。
 * 云端失败自动回落系统 TTS —— 不会因为服务器不可达就彻底没声音。
 *
 * ## 播放方式
 * 服务端返回 MP3 字节流 → 落盘到 `files/tts-cache/<hash>.mp3`（**同一句话只下一次**）
 * → MediaPlayer 播放。比"每次都要下载再播"省流量，也让重复播报接近瞬时。
 */
object CloudTts {

    private const val TAG = "CloudTts"

    private const val PREFS = "cloud_tts"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 30_000

    /** 单次合成最长等待（超过就回落到系统 TTS，避免用户干等） */
    private const val SYNTH_TIMEOUT_MS = 12_000L

    /** 缓存目录最多保留多少个 mp3 */
    private const val CACHE_MAX_FILES = 60

    // ==================== 配置 ====================

    /** 服务地址，形如 `http://your-host/tts`（空 = 未配置，走系统 TTS） */
    fun endpoint(ctx: Context): String = prefs(ctx).getString("endpoint", "").orEmpty()

    /** 访问 token（与服务端 DSH_TTS_TOKEN 一致） */
    fun token(ctx: Context): String = prefs(ctx).getString("token", "").orEmpty()

    /** 音色名（edge-tts 的 ShortName，如 zh-CN-XiaoxiaoNeural） */
    fun voice(ctx: Context): String =
        prefs(ctx).getString("voice", DEFAULT_VOICE)?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE

    /** 语速（edge-tts 格式，如 +0% / +20% / -10%） */
    fun rate(ctx: Context): String =
        prefs(ctx).getString("rate", "+0%")?.takeIf { it.isNotBlank() } ?: "+0%"

    fun isConfigured(ctx: Context): Boolean = endpoint(ctx).isNotBlank()

    fun save(ctx: Context, endpoint: String, token: String, voice: String, rate: String) {
        prefs(ctx).edit()
            .putString("endpoint", endpoint.trim())
            .putString("token", token.trim())
            .putString("voice", voice.trim().ifBlank { DEFAULT_VOICE })
            .putString("rate", rate.trim().ifBlank { "+0%" })
            .apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

    /**
     * 推荐音色（edge-tts 的免费神经网络音色）。
     * 名字 → 字符串资源 id（描述文案必须走资源，i18n 门禁禁止 Kotlin 里出现 CJK 字面量）。
     */
    val SUGGESTED_VOICES = listOf(
        "zh-CN-XiaoxiaoNeural" to app.dsh.mobile.R.string.tts_voice_xiaoxiao,
        "zh-CN-YunxiNeural" to app.dsh.mobile.R.string.tts_voice_yunxi,
        "zh-CN-XiaoyiNeural" to app.dsh.mobile.R.string.tts_voice_xiaoyi,
        "zh-CN-YunjianNeural" to app.dsh.mobile.R.string.tts_voice_yunjian,
    )

    // ==================== 合成 + 播放 ====================

    @Volatile private var player: MediaPlayer? = null

    /**
     * 合成并朗读。**阻塞**（含网络），调用方必须在后台线程。
     * @return 人类可读结果；失败时由调用方回落到系统 TTS
     */
    fun speak(ctx: Context, text: String, flush: Boolean): String {
        val clean = text.trim()
        if (clean.isEmpty()) return "empty text"
        val url = endpoint(ctx)
        if (url.isBlank()) return "cloud tts not configured"

        val cacheDir = File(ctx.filesDir, "tts-cache").apply { mkdirs() }
        val file = File(cacheDir, hashOf(clean, voice(ctx), rate(ctx)) + ".mp3")

        // ① 缓存命中 → 直接播（无需网络）
        if (!file.isFile || file.length() == 0L) {
            val ok = download(ctx, url, clean, file)
            if (!ok) return "cloud tts download failed"
        }
        // ② 播放
        return play(ctx, file, flush)
    }

    private fun download(ctx: Context, url: String, text: String, dest: File): Boolean =
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                token(ctx).takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("X-Token", it)
                }
            }
            val body = org.json.JSONObject().apply {
                put("text", text.take(2000))
                put("voice", voice(ctx))
                put("rate", rate(ctx))
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "tts http $code: ${conn.errorStream?.bufferedReader()?.use { r -> r.readText() }?.take(160)}")
                conn.disconnect()
                return@runCatching false
            }
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            pruneCache(dest.parentFile)
            Log.i(TAG, "tts cached: ${dest.name} (${dest.length()} bytes)")
            true
        }.getOrElse {
            Log.w(TAG, "tts download error: ${it.message}")
            false
        }

    private fun play(ctx: Context, file: File, flush: Boolean): String = runCatching {
        if (flush) stop()
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener { runCatching { it.release() } }
            setOnErrorListener { p, _, _ -> runCatching { p.release() }; true }
            prepare()
            start()
        }
        player = mp
        "cloud tts speaking (${file.length()} bytes, ${voice(ctx)})"
    }.getOrElse {
        Log.w(TAG, "tts play error: ${it.message}")
        "cloud tts play failed: ${it.message}"
    }

    /** 停止云端播放 */
    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    /** 缓存淘汰：按最后修改时间保留最近的 N 个 */
    private fun pruneCache(dir: File?) {
        runCatching {
            val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(".mp3") } ?: return
            if (files.size <= CACHE_MAX_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - CACHE_MAX_FILES)
                .forEach { it.delete() }
        }
    }

    /** 清空本地缓存（设置页"清除缓存"用） */
    fun clearCache(ctx: Context): Int = runCatching {
        val dir = File(ctx.filesDir, "tts-cache")
        val n = dir.listFiles()?.size ?: 0
        dir.listFiles()?.forEach { it.delete() }
        n
    }.getOrDefault(0)

    private fun hashOf(vararg parts: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        parts.forEach { md.update(it.toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(20)
    }
}

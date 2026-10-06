package app.dsh.mobile.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 微软 Edge 神经网络语音合成 —— **客户端直连**（v1.2.78）。
 *
 * ## 为什么不走服务器中转（主人决策）
 * 主人指出「我服务器中转不保险」（服务器到期/被刷/被限流都受影响）。
 * 实测确认：edge-tts 的协议**可以在客户端手写复刻**（在服务器上用纯 WebSocket
 * 复刻协议成功拿到 28KB 有效 MP3），于是改为 App 直连微软，**不再依赖任何中转服务器**。
 *
 * ## 协议（逆向自 edge-tts 7.2.8，实测可用）
 *  · WSS：`wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`
 *    查询参数 `TrustedClientToken`（Edge 内置的公开常量）+ `Sec-MS-GEC` 签名
 *  · **Sec-MS-GEC 签名算法**（关键，没有它会被拒）：
 *      ① 取当前 Unix 秒 → 加 Windows 文件时间纪元偏移 11644473600
 *      ② 向下取整到 5 分钟（300 秒）的整数倍
 *      ③ ×10^7 转成 100 纳秒间隔
 *      ④ 拼上 TrustedClientToken → SHA-256 → **大写十六进制**
 *  · 握手后先发 `speech.config`（指定输出格式 audio-24khz-48kbitrate-mono-mp3）
 *  · 再发 `Path:ssml` 的 SSML 请求
 *  · 服务端回**二进制帧**：前 2 字节 = 头长度（大端），头里 `Path:audio` 的帧才是音频
 *  · 文本消息里出现 `Path:turn.end` 表示合成结束
 *
 * ## 为什么要手写这么多
 * Android 没有内置 WebSocket 客户端，这里用 `org.java-websocket:Java-WebSocket`。
 * 协议头格式（`X-RequestId:` / `Content-Type:` / `Path:` + `\r\n\r\n` + body）是
 * 微软这个接口的私有约定，必须逐字节照抄（参考 edge-tts 的 ssml_headers_plus_data）。
 */
object EdgeTts {

    private const val TAG = "EdgeTts"

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val BASE = "speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val CHROMIUM_MAJOR = "143"
    private const val WIN_EPOCH = 11644473600L

    /** 合成超时（含连接） */
    private const val TIMEOUT_MS = 25_000L

    private const val PREFS = "edge_tts"

    /** 默认音色（中文女声，神经网络） */
    const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

    /** 推荐音色：名称 → 资源 id（文案走资源，i18n 门禁要求） */
    val VOICES = listOf(
        "zh-CN-XiaoxiaoNeural" to app.dsh.mobile.R.string.tts_voice_xiaoxiao,
        "zh-CN-YunxiNeural" to app.dsh.mobile.R.string.tts_voice_yunxi,
        "zh-CN-XiaoyiNeural" to app.dsh.mobile.R.string.tts_voice_xiaoyi,
        "zh-CN-YunjianNeural" to app.dsh.mobile.R.string.tts_voice_yunjian,
        "zh-CN-liaoning-XiaobeiNeural" to app.dsh.mobile.R.string.tts_voice_xiaobei,
        "zh-CN-shaanxi-XiaoniNeural" to app.dsh.mobile.R.string.tts_voice_xiaoni,
    )

    // ==================== 配置 ====================

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun voice(ctx: Context): String =
        prefs(ctx).getString("voice", DEFAULT_VOICE)?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE

    fun rate(ctx: Context): String =
        prefs(ctx).getString("rate", "+0%")?.takeIf { it.isNotBlank() } ?: "+0%"

    fun save(ctx: Context, enabled: Boolean, voice: String, rate: String) {
        prefs(ctx).edit()
            .putBoolean("enabled", enabled)
            .putString("voice", voice.trim().ifBlank { DEFAULT_VOICE })
            .putString("rate", rate.trim().ifBlank { "+0%" })
            .apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ==================== 签名 ====================

    /** Sec-MS-GEC：见类注释的算法说明（必须逐位照抄，否则服务端拒绝） */
    private fun secMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000L
        ticks += WIN_EPOCH
        ticks -= ticks % 300            // 向下取整到 5 分钟
        ticks *= 10_000_000L            // 100ns 间隔
        val raw = "$ticks$TRUSTED_CLIENT_TOKEN"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02X".format(it) }
    }

    /** SSML 时间戳格式（微软这个接口用 JS 风格日期，且末尾会多一个 Z —— 照抄） */
    private fun jsDate(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    /** SSML：文本必须转义，否则 `&`/`<` 会破坏 XML */
    private fun ssml(voice: String, text: String, rate: String): String {
        val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'><prosody pitch='+0Hz' rate='$rate' volume='+0%'>$esc</prosody></voice></speak>"
    }

    // ==================== 合成 ====================

    @Volatile private var player: MediaPlayer? = null

    /**
     * 合成并朗读。**阻塞**（含网络），调用方必须在后台线程。
     * @return 人类可读结果；失败时由调用方回落到系统 TTS
     */
    fun speak(ctx: Context, text: String, flush: Boolean): String {
        val clean = text.trim()
        if (clean.isEmpty()) return "empty text"

        val cacheDir = File(ctx.filesDir, "edge-tts-cache").apply { mkdirs() }
        val cache = File(cacheDir, hash(clean, voice(ctx), rate(ctx)) + ".mp3")

        if (!cache.isFile || cache.length() == 0L) {
            val audio = synthesize(clean, voice(ctx), rate(ctx))
                ?: return "edge tts synth failed"
            runCatching { cache.writeBytes(audio) }
            prune(cacheDir)
        }
        return play(ctx, cache, flush)
    }

    /** 走 WebSocket 拿音频字节；失败返回 null */
    private fun synthesize(text: String, voice: String, rate: String): ByteArray? {
        val url = "wss://$BASE?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=1-$CHROMIUM_FULL_VERSION"
        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var error: String? = null
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"

        val client = object : WebSocketClient(URI(url), mapOf(
            "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
            "User-Agent" to ua,
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache",
        )) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                runCatching {
                    // ① 配置输出格式
                    val cfg = """{"context":{"synthesis":{"audio":{"metadataoptions":""" +
                        """{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
                        """"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                    send(
                        "X-Timestamp:${jsDate()}\r\n" +
                            "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" + cfg,
                    )
                    // ② SSML 请求
                    send(
                        "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${jsDate()}Z\r\n" +
                            "Path:ssml\r\n\r\n" + ssml(voice, text, rate),
                    )
                }.onFailure { error = "send failed: ${it.message}"; done.countDown() }
            }

            override fun onMessage(message: String?) {
                // 文本消息：turn.end = 合成结束
                if (message?.contains("Path:turn.end") == true) done.countDown()
            }

            override fun onMessage(bytes: java.nio.ByteBuffer) {
                val b = ByteArray(bytes.remaining())
                bytes.get(b)
                if (b.size < 2) return
                // 二进制帧：前 2 字节（大端）= 头长度
                val hlen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (hlen <= 0 || 2 + hlen > b.size) return
                val header = String(b, 2, hlen, Charsets.UTF_8)
                if (header.contains("Path:audio")) {
                    audio.write(b, 2 + hlen, b.size - 2 - hlen)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                done.countDown()
            }

            override fun onError(ex: Exception?) {
                error = ex?.message ?: "websocket error"
                done.countDown()
            }
        }

        return try {
            client.connectBlocking(8, TimeUnit.SECONDS)
            if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) error = "timeout"
            runCatching { client.closeBlocking() }
            if (error != null) {
                Log.w(TAG, "synth failed: $error")
                null
            } else {
                val out = audio.toByteArray()
                Log.i(TAG, "synth ok: ${out.size} bytes, voice=$voice")
                out.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "synth exception: ${e.message}")
            runCatching { client.close() }
            null
        }
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
        "edge tts speaking (${file.length()} bytes)"
    }.getOrElse {
        Log.w(TAG, "play failed: ${it.message}")
        "edge tts play failed: ${it.message}"
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    /** 缓存淘汰：保留最近 60 个 */
    private fun prune(dir: File) {
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp3") } ?: return
            if (files.size <= 60) return
            files.sortedBy { it.lastModified() }.take(files.size - 60).forEach { it.delete() }
        }
    }

    fun clearCache(ctx: Context): Int = runCatching {
        val dir = File(ctx.filesDir, "edge-tts-cache")
        val n = dir.listFiles()?.size ?: 0
        dir.listFiles()?.forEach { it.delete() }
        n
    }.getOrDefault(0)

    private fun hash(vararg parts: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        parts.forEach { md.update(it.toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(20)
    }
}

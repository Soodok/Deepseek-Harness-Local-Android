package app.dsh.mobile.engine

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 云端语音识别（v1.2.74）—— 系统识别不可用时的兜底通道。
 *
 * ## 为什么需要
 * 设备没有系统 SpeechRecognizer（国内 ROM 常见）时，原来的兜底是离线 Vosk small：
 * 那是 2019 年 Kaldi 时代的小模型，中文准确率很差（主人原话「太垃圾了」）。
 * 这里换成云端 API（默认硅基流动，免费），识别质量是代差级提升。
 *
 * ## 为什么"准流式"而不是等录完
 * 主人要求「可不可以流式输出？录音完再输出可能效果不好」。
 * 实测（curl 探测）硅基流动**只有非流式接口** `/v1/audio/transcriptions`
 * （`/v1/realtime`、`/v1/audio/asr` 等全部 404），没有 WebSocket 流式通道。
 *
 * 折中做法 = **分段准流式**：
 *  · 录音期间按**能量静音检测**切段（说完一小句、停顿约 [SEGMENT_SILENCE_MS] 就切）
 *  · 每段立刻作为独立 wav POST 上传，拿到文本后**追加**到面板
 *  · 用户还在说下一句时，上一句的结果已经显示出来了 —— 体感接近流式
 *
 * 代价：切段处若断在词中间，可能丢字/重复（下一段会重新识别该处语音）。
 * 为降低影响，切段阈值取得比较保守（要有明显静音才切）。
 *
 * ## 音频格式
 * 16kHz / 单声道 / 16bit PCM（与 Vosk 一致，也是各家 ASR 的通用输入），
 * 每段包成标准 WAV（44 字节头 + PCM），Content-Type: audio/wav。
 */
object CloudAsr {

    private const val TAG = "CloudAsr"

    /** 采样率：16k 单声道是语音识别的通用输入 */
    private const val SAMPLE_RATE = 16_000

    /** 每段最长时长（防止用户一口气说很久都不上传） */
    private const val SEGMENT_MAX_MS = 8_000L

    /** 段内静音多久算"这一句说完了"（保守取值，避免断在词中间） */
    private const val SEGMENT_SILENCE_MS = 900L

    /** 判定为"静音"的 RMS 阈值（16bit PCM；经验值，偏保守） */
    private const val SILENCE_RMS = 620.0

    /** 全程没有任何语音 → 多久自动结束（避免误触后一直录） */
    private const val NO_SPEECH_TIMEOUT_MS = 6_000L

    /** 上传超时 */
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    // ==================== 配置 ====================

    private const val PREFS = "cloud_asr"

    /** 端点（默认硅基流动；OpenAI 兼容格式，换服务商只改这个 URL） */
    fun endpoint(ctx: Context): String =
        prefs(ctx).getString("endpoint", DEFAULT_ENDPOINT)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT

    /** 模型名（硅基流动免费的 SenseVoiceSmall） */
    fun model(ctx: Context): String =
        prefs(ctx).getString("model", DEFAULT_MODEL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /** API Key（空 = 未配置，云端通道不可用） */
    fun apiKey(ctx: Context): String = prefs(ctx).getString("api_key", "").orEmpty()

    /** 是否已配置好（有 key 就能用） */
    fun isConfigured(ctx: Context): Boolean = apiKey(ctx).isNotBlank()

    fun save(ctx: Context, endpoint: String, model: String, key: String) {
        prefs(ctx).edit()
            .putString("endpoint", endpoint.trim())
            .putString("model", model.trim())
            .putString("api_key", key.trim())
            .apply()
    }



    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 默认端点：**留空**（主人要求让用户自己选服务商，不自作主张） */
    const val DEFAULT_ENDPOINT = ""
    const val DEFAULT_MODEL = ""


    // ==================== 识别 ====================

    @Volatile private var running = false
    @Volatile private var recordThread: Thread? = null
    @Volatile private var stopRequested = false

    fun isRunning(): Boolean = running

    /**
     * 开始"准流式"识别。
     *
     * @param onSegment 每段识别出文本时回调（**追加**语义：调用方应把文本接到已有内容后）
     * @param onStatus  状态提示（上传中 / 网络错误等，用于面板提示行）
     * @param onEnd     本轮结束（用户停手 / 全程无语音 / 出错）
     */
    fun start(
        ctx: Context,
        onSegment: (String) -> Unit,
        onStatus: (String) -> Unit = {},
        onEnd: () -> Unit = {},
    ) {
        if (running) {
            Log.i(TAG, "already running, ignoring start")
            return
        }
        val app = ctx.applicationContext
        val key = apiKey(app)
        if (key.isBlank()) {
            onStatus("cloud ASR not configured")
            onEnd()
            return
        }
        stopRequested = false
        running = true
        recordThread = Thread({ recordLoop(app, key, onSegment, onStatus, onEnd) }, "cloud-asr")
            .apply { isDaemon = true; start() }
    }

    /** 停止（用户点取消/发送）。当前段的音频会被丢弃。 */
    fun stop() {
        stopRequested = true
        recordThread?.let { runCatching { it.join(1_500) } }
        recordThread = null
        running = false
    }

    /**
     * 录音主循环：读 PCM → 按能量切段 → 每段上传。
     *
     * ⚠️ 全程在后台线程（AudioRecord.read 会阻塞，上传是网络 IO）。
     */
    private fun recordLoop(
        ctx: Context,
        key: String,
        onSegment: (String) -> Unit,
        onStatus: (String) -> Unit,
        onEnd: () -> Unit,
    ) {
        var record: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(SAMPLE_RATE / 5)   // 至少 200ms，避免过小导致溢出

            @Suppress("MissingPermission")
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord init failed")
                onStatus("mic init failed")
                return
            }
            record.startRecording()
            Log.i(TAG, "cloud asr recording (segment: ${SEGMENT_SILENCE_MS}ms silence / ${SEGMENT_MAX_MS}ms max)")

            val buf = ShortArray(SAMPLE_RATE / 10)   // 100ms 一块
            val segment = ByteArrayOutputStream()
            var segmentMs = 0L
            var silenceMs = 0L
            var totalMs = 0L
            var heard = false
            val startedAt = System.currentTimeMillis()

            while (!stopRequested) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                totalMs += 100L

                // 能量（RMS）判断是否静音
                var sum = 0.0
                for (i in 0 until n) sum += (buf[i].toDouble() * buf[i])
                val rms = kotlin.math.sqrt(sum / n)
                val silent = rms < SILENCE_RMS
                if (!silent) heard = true

                // 写入本段（静音也写，保留自然的停顿，识别更准）
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                segment.write(bytes)
                segmentMs += 100L
                silenceMs = if (silent) silenceMs + 100L else 0L

                // ① 说完一小句（静音够久）且本段有实际语音 → 切段上传
                val shouldFlush = (silenceMs >= SEGMENT_SILENCE_MS && heard && segmentMs > 300L) ||
                    segmentMs >= SEGMENT_MAX_MS
                if (shouldFlush) {
                    val pcm = segment.toByteArray()
                    val hadSpeech = heard
                    segment.reset()
                    segmentMs = 0L
                    silenceMs = 0L
                    heard = false
                    if (hadSpeech) {
                        uploadSegment(ctx, key, pcm, onSegment, onStatus)
                    }
                }

                // ② 全程没说话 → 超时结束（避免误触后一直录）
                if (!heard && totalMs >= NO_SPEECH_TIMEOUT_MS &&
                    System.currentTimeMillis() - startedAt >= NO_SPEECH_TIMEOUT_MS
                ) {
                    Log.i(TAG, "no speech for ${totalMs}ms → ending")
                    break
                }
            }

            // 收尾：把还没上传的尾段发出去（用户说完立刻停手时，最后一句在这里）
            if (segment.size() > 0) {
                val pcm = segment.toByteArray()
                if (pcm.size > SAMPLE_RATE / 2) {   // 至少 250ms 才值得上传
                    uploadSegment(ctx, key, pcm, onSegment, onStatus)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "record loop failed: ${e.message}")
            onStatus(e.message ?: "recording failed")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            running = false
            stopRequested = false
            onEnd()
            Log.i(TAG, "cloud asr stopped")
        }
    }

    /** 把一段 PCM 包成 WAV 上传，识别出的文本回调给调用方 */
    private fun uploadSegment(
        ctx: Context,
        key: String,
        pcm: ByteArray,
        onSegment: (String) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        runCatching {
            val wav = pcmToWav(pcm)
            val boundary = "----dsh${UUID.randomUUID().toString().replace("-", "")}"
            val url = URL(endpoint(ctx))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write(value.toByteArray())
                    out.write("\r\n".toByteArray())
                }
                field("model", model(ctx))
                field("response_format", "json")
                // 文件字段
                out.write("--$boundary\r\n".toByteArray())
                out.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"seg.wav\"\r\n"
                        .toByteArray()
                )
                out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
                out.write(wav)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "asr http $code: ${body.take(200)}")
                onStatus("ASR HTTP $code")
                return@runCatching
            }
            val text = JSONObject(body).optString("text").trim()
            Log.i(TAG, "segment text: $text")
            if (text.isNotEmpty()) onSegment(text)
        }.onFailure {
            Log.w(TAG, "upload failed: ${it.message}")
            onStatus(it.message ?: "upload failed")
        }
    }

    /** 16bit 单声道 PCM → 标准 WAV（44 字节头） */
    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val total = pcm.size + 44
        val out = ByteArrayOutputStream(total)
        fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        out.write("RIFF".toByteArray())
        out.write(le32(total - 8))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))                       // fmt 块大小
        out.write(le16(1))                        // PCM
        out.write(le16(1))                        // 单声道
        out.write(le32(SAMPLE_RATE))
        out.write(le32(SAMPLE_RATE * 2))          // 字节率 = 采样率 * 声道 * 位深/8
        out.write(le16(2))                        // 块对齐
        out.write(le16(16))                       // 位深
        out.write("data".toByteArray())
        out.write(le32(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    /** 诊断：把最近一次上传的 wav 落盘（便于排查识别效果），返回路径 */
    fun dumpLastSegment(ctx: Context, pcm: ByteArray): String? = runCatching {
        val f = File(ctx.filesDir, "asr-last-segment.wav")
        f.writeBytes(pcmToWav(pcm))
        f.absolutePath
    }.getOrNull()
}

package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Vosk 离线语音识别（v1.2.59）。
 *
 * 当设备没有系统 SpeechRecognizer 时的 fallback。
 * 使用用户从模型目录下载的 Vosk 模型做**全离线**识别。
 *
 * 用法：
 * ```kotlin
 * VoskRecognizer.init(ctx, modelPath)
 * VoskRecognizer.start(
 *     onPartial = { text -> … },
 *     onFinal = { text -> … },
 * )
 * VoskRecognizer.stop()
 * ```
 */
object VoskRecognizer {

    private const val TAG = "VoskRecognizer"
    private const val SAMPLE_RATE = 16000

    @Volatile private var model: Model? = null
    @Volatile private var speechService: SpeechService? = null
    @Volatile private var modelPath: String? = null

    /** 是否已加载模型 */
    fun isModelLoaded(): Boolean = model != null

    /** 加载模型（耗时操作：需读入 ~40MB 文件，应在后台线程调用） */
    fun init(ctx: Context, path: String): Boolean = runCatching {
        Log.i(TAG, "loading model from $path")
        model = Model(path)
        modelPath = path
        Log.i(TAG, "model loaded")
        true
    }.getOrElse { Log.w(TAG, "model load failed: ${it.message}"); false }

    /**
     * 开始监听麦克风并识别。
     * @param onPartial 中间结果（实时刷新）
     * @param onFinal 最终结果（每次静默后回调一次）
     */
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit) {
        val m = model ?: return
        if (speechService != null) return
        runCatching {
            // 签名实测自 AAR 0.3.75（javap 语义反解）：
            //   Recognizer(Model, Float)  /  SpeechService(Recognizer, Float)
            // SpeechService 内部自建 AudioRecord，无需我们开录音
            val rec = Recognizer(m, SAMPLE_RATE.toFloat())
            val svc = SpeechService(rec, SAMPLE_RATE.toFloat())
            // ⚠️ 实测 0.3.75 API（Kotlin 编译器指认）：
            //  · SpeechService 构造只有 (Recognizer, Float)，listener 用 setListener 装配
            //  · RecognitionListener 必须实现 onResult(String)（最终结果）
            svc.startListening(object : RecognitionListener {
                override fun onPartialResult(partial: String?) {
                    val text = JSONObject(partial ?: "{}").optString("text").orEmpty()
                    if (text.isNotBlank()) onPartial(text)
                }
                override fun onFinalResult(final: String?) {
                    val text = JSONObject(final ?: "{}").optString("text").orEmpty()
                    if (text.isNotBlank()) onFinal(text)
                }
                override fun onResult(result: String?) {
                    // 无 partial 场景的最终兜底（与 onFinalResult 同义）
                    val text = JSONObject(result ?: "{}").optString("text").orEmpty()
                    if (text.isNotBlank()) onFinal(text)
                }
                override fun onError(e: Exception?) {
                    Log.w(TAG, "vosk error: ${e?.message}")
                }
                override fun onTimeout() {}
            })
            speechService = svc
            Log.i(TAG, "vosk listening…")
        }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
    }

    fun stopAll() {
        runCatching { speechService?.stop() }
        speechService = null
        Log.i(TAG, "vosk stopped")
    }
}


package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 语音识别（ASR，v1.2.58）。
 *
 * ## 为什么用系统 SpeechRecognizer
 *  · **零依赖**：Android 内置，不需要打包模型或引第三方 SDK
 *  · **离线可用**：多数设备带离线识别包（Google / 厂商引擎），与项目"本地优先"一致
 *  · **不需要网络权限**（INTERNET 已有，但离线时不走网）
 * 唯一代价：需要用户授予 `RECORD_AUDIO`（麦克风），这是系统强制的。
 *
 * ## 用法
 * ```kotlin
 * AsrManager.start(ctx,
 *     onPartial = { text -> /* 实时中间结果，可用于悬浮窗预览 */ },
 *     onFinal   = { text -> /* 最终结果 */ },
 *     onError   = { msg -> },
 * )
 * ```
 * 再次调用 start 会先停止上一次（避免叠加）。
 *
 * ⚠️ SpeechRecognizer **必须在主线程创建与调用**（官方要求），故所有方法内部切主线程。
 */
object AsrManager {

    private const val TAG = "AsrManager"

    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var listening = false

    /** 是否可用（设备有识别服务） */
    fun isAvailable(ctx: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)

    fun isListening(): Boolean = listening

    /**
     * 开始一次识别。
     * @param onPartial 中间结果（实时刷新，可为空实现）
     * @param onFinal 最终结果（成功后回调一次）
     * @param onError 失败原因（用户可读）
     * @param onEnd 无论成败都会回调（用于复位 UI 状态）
     */
    fun start(
        ctx: Context,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit = {},
        onEnd: () -> Unit = {},
    ) {
        main {
            val app = ctx.applicationContext
            if (!isAvailable(app)) {
                onError("device has no speech recognition service")
                onEnd()
                return@main
            }
            // 已有会话先释放，避免 "recognizer is busy"
            stop()

            runCatching {
                val r = SpeechRecognizer.createSpeechRecognizer(app)
                recognizer = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listening = true
                        Log.i(TAG, "listening…")
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.i(TAG, "end of speech")
                    }

                    override fun onError(error: Int) {
                        listening = false
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "not recognized, try again"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission required"
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "recognition network error"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy, try again"
                            SpeechRecognizer.ERROR_AUDIO -> "audio error"
                            else -> "recognition failed (code $error)"
                        }
                        Log.w(TAG, "onError $error")
                        onError(msg)
                        onEnd()
                    }

                    override fun onResults(results: Bundle?) {
                        listening = false
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        Log.i(TAG, "result: $text")
                        if (text.isNotBlank()) onFinal(text) else onError("not recognized, try again")
                        onEnd()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        if (text.isNotBlank()) onPartial(text)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    // 优先离线（本地优先哲学）；设备无离线包时系统会自动回退在线
                    if (Build.VERSION.SDK_INT >= 23) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // 跟随界面语言
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                }
                r.startListening(intent)
            }.onFailure {
                listening = false
                Log.w(TAG, "start failed: ${it.message}")
                onError("start failed: ${it.message}")
                onEnd()
            }
        }
    }

    /** 停止并释放（再次 start 前会调用） */
    fun stop() {
        main {
            listening = false
            runCatching { recognizer?.stopListening() }
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    private fun main(block: () -> Unit) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else runCatching { h.post(block) }
    }
}

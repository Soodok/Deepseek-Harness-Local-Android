package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 语音识别（ASR，v1.2.58）。
 *
 * ## 为什么用系统 SpeechRecognizer
 *  · **零依赖**：Android 内置，不需要打包模型或引第三方 SDK
 *  · **离线可用**：多数设备带离线识别包（Google / 厂商引擎），与项目「本地优先」一致
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

    /**
     * 指定的识别服务组件（v1.2.87）。
     *
     * ## 为什么需要"直接指定"而不是用系统默认
     * 主人真机实测：**系统「语音输入」设置页被 ROM 重定向到数字助理页**，
     * 用户根本没地方选识别引擎（模拟器同样如此，是很多国内 ROM 的普遍行为）。
     * 但系统真正生效的是 `Settings.Secure.voice_recognition_service` 这个键 ——
     * 只要它指向某个服务，`SpeechRecognizer` 就会用它。
     *
     * 所以：**DSH 自己直接指定组件**（`createSpeechRecognizer(ctx, component)`），
     * 完全绕过那个打不开的设置页。
     */
    private const val PREFS = "asr_service"

    /** DSH 离线识别插件的默认组件（与我们同源的项目 dsh-asr-service） */
    const val PLUGIN_PKG = "app.dsh.asr"
    const val PLUGIN_SERVICE = "app.dsh.asr.AsrRecognitionService"

    /** 用户指定的识别服务组件（空 = 用系统默认） */
    fun preferredComponent(ctx: Context): String? =
        prefs(ctx).getString("component", "").orEmpty().takeIf { it.isNotBlank() }

    fun setPreferredComponent(ctx: Context, component: String?) {
        prefs(ctx).edit().putString("component", component.orEmpty()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 解析成 ComponentName（无效返回 null） */
    private fun componentOf(ctx: Context, spec: String?): android.content.ComponentName? {
        val s = spec?.trim().orEmpty()
        if (s.isBlank()) return null
        val parts = s.split('/')
        if (parts.size != 2) return null
        val pkg = parts[0].trim()
        val cls = parts[1].trim().let { if (it.startsWith(".")) pkg + it else it }
        return runCatching { android.content.ComponentName(pkg, cls) }.getOrNull()
    }

    /** 插件是否已安装 */
    fun isPluginInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(PLUGIN_PKG, 0); true
    }.getOrDefault(false)

    /**
     * 是否可用：优先看**指定组件**是否已安装且可用；否则看系统默认。
     * （系统默认那个在设置页被重定向的设备上可能指向不可用服务）
     */
    fun isAvailable(ctx: Context): Boolean {
        val comp = componentOf(ctx, preferredComponent(ctx))
        if (comp != null) {
            // 指定了组件 → 检查它是否真的可绑定
            val ok = runCatching {
                val intent = android.content.Intent(RecognitionService.SERVICE_INTERFACE)
                    .setComponent(comp)
                ctx.packageManager.queryIntentServices(intent, 0).isNotEmpty()
            }.getOrDefault(false)
            if (ok) return true
        }
        return runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)
    }

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
                // 指定了组件就用它（绕过被 ROM 重定向的系统设置页），否则用系统默认
                val comp = componentOf(app, preferredComponent(app))
                // 诊断（关键）：明确打印绑定的组件 —— 用于证明"直接指定服务"是否生效
                // （设备本身有 Google 语音时，光看"哪个进程活着"无法区分）
                Log.i(TAG, "createSpeechRecognizer: preferred=${preferredComponent(app)} resolved=$comp")
                val r = if (comp != null) {
                    SpeechRecognizer.createSpeechRecognizer(app, comp)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(app)
                }
                Log.i(TAG, "recognizer created: $r")
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

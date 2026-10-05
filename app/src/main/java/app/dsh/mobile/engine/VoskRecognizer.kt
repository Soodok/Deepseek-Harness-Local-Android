package app.dsh.mobile.engine

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

    /**
     * 静音自动结束时长（毫秒）—— **真正的静音检测**，不是总时长上限。
     *
     * ⚠️ 为什么不用 `SpeechService.startListening(listener, timeout)`：
     * 反编译 AAR 0.3.75 确认，那个 timeout 是**总录音预算** ——
     * `remainingSamples` 每读一块就扣减，扣完即停（`RecognizerThread.run` 字节码），
     * 与「有没有在说话」无关。用它会导致：① 长句子说到一半被硬切；
     * ② 用户停手后还要干等到预算耗尽才释放麦克风（实测体感「等几秒才自己关」）。
     *
     * ## 阈值为什么这么长（v1.2.65 二次修正）
     * 首版 1.2 秒 → 用户实测「**话没说完就自己停了**」。
     * 原因：Vosk 只在**识别出词**时才回调 `onPartialResult`，说话中的换气、
     * 想词、长词之间的停顿都不产生回调，1.2 秒很容易被误判为「说完了」。
     * 而 Vosk 的 `RecognitionListener` **不提供 RMS/音量回调**，
     * `SpeechService.recorder` 是 private 且无 getter（javap 确认），拿不到实时音量。
     *
     * 折中：把阈值放宽到能覆盖正常换气停顿的长度 ——
     *  · 说过话后 [IDLE_AFTER_SPEECH_MS]（3 秒）→ 正常语速下不会切断句子
     *  · 全程没说话 [IDLE_NO_SPEECH_MS]（5 秒）→ 误触后不至于一直录
     * 用户若仍觉得早/晚，直接调这两个常量即可。
     */
    private const val IDLE_AFTER_SPEECH_MS = 3_000L
    private const val IDLE_NO_SPEECH_MS = 5_000L

    /** 静音检测轮询间隔 */
    private const val IDLE_POLL_MS = 200L

    /** 最近一次识别活动时间 / 本轮是否听到过语音 */
    @Volatile private var lastActivityAt = 0L
    @Volatile private var heardSpeech = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var idleCheck: Runnable? = null

    /** 内部停止用的单线程（stop() 会 join 识别线程，不能占主线程） */
    private val stopExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "vosk-idle-stop").apply { isDaemon = true }
    }

    @Volatile private var model: Model? = null
    @Volatile private var speechService: SpeechService? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var modelPath: String? = null

    /** 是否已加载模型 */
    fun isModelLoaded(): Boolean = model != null

    /** 已加载模型的路径（null = 未加载）；供调用方避免重复加载 40MB 模型 */
    fun loadedPath(): String? = if (model != null) modelPath else null

    /**
     * 加载模型（耗时操作：需读入 ~40MB 文件，应在后台线程调用）。
     * 同一路径重复调用会直接复用已加载的模型 —— 否则每次点麦克风都会重读 40MB。
     *
     * @param path 模型目录绝对路径（Vosk Model 构造参数）
     */
    fun init(path: String): Boolean {
        if (model != null && modelPath == path) return true
        return runCatching {
            Log.i(TAG, "loading model from $path")
            // 换模型前释放旧的 native 句柄，避免内存翻倍
            releaseModel()
            model = Model(path)
            modelPath = path
            Log.i(TAG, "model loaded")
            true
        }.getOrElse { Log.w(TAG, "model load failed: ${it.message}"); false }
    }

    /**
     * 开始监听麦克风并识别。
     * @param onPartial 中间结果（实时刷新）
     * @param onFinal 最终结果（每次静默后回调一次）
     * @param onEnd 本轮结束（超时/出错，无最终结果时）—— 调用方据此复位 UI。
     *              旧版没有这个回调，静默超时后状态永远挂在「聆听中」，
     *              用户实测「取消/发送后上面还显示 listen」。
     */
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onEnd: () -> Unit = {},
    ) {
        val m = model ?: run {
            Log.w(TAG, "start ignored: no model loaded")
            onEnd()
            return
        }
        // 上一轮若还在跑，先彻底释放（stop 只中断线程，不释放 AudioRecord）
        stopAll()
        runCatching {
            // 签名实测自 AAR 0.3.75（javap 反解）：
            //   Recognizer(Model, Float)  /  SpeechService(Recognizer, Float)
            //   SpeechService.startListening(RecognitionListener)
            // SpeechService 内部自建 AudioRecord，无需我们开录音
            val rec = Recognizer(m, SAMPLE_RATE.toFloat())
            val svc = SpeechService(rec, SAMPLE_RATE.toFloat())
            recognizer = rec
            lastActivityAt = System.currentTimeMillis()
            heardSpeech = false
            val listener = object : RecognitionListener {
                override fun onPartialResult(partial: String?) {
                    val text = JSONObject(partial ?: "{}").optString("text").orEmpty()
                    if (text.isNotBlank()) {
                        lastActivityAt = System.currentTimeMillis()
                        heardSpeech = true
                        onPartial(text)
                    }
                }
                override fun onFinalResult(final: String?) {
                    val text = JSONObject(final ?: "{}").optString("text").orEmpty()
                    if (text.isNotBlank()) onFinal(text) else onEnd()
                }
                override fun onResult(result: String?) {
                    // 无 partial 场景的最终兜底（与 onFinalResult 同义）
                    val text = JSONObject(result ?: "{}").optString("text").orEmpty()
                    if (text.isNotBlank()) onFinal(text) else onEnd()
                }
                override fun onError(e: Exception?) {
                    Log.w(TAG, "vosk error: ${e?.message}")
                    onEnd()
                }
                override fun onTimeout() {
                    // Vosk 自身的 timeout 我们没启用（传 -1），这里只作兜底
                    Log.i(TAG, "vosk timeout")
                    onEnd()
                }
            }
            // ⚠️ 传 -1 = 不设总时长预算，改用我们自己的静音检测（见 startIdleWatch）。
            // 旧版传固定毫秒数是**总录音预算**（与是否说话无关）：
            // 长句子会被硬切，且用户停手后要干等到预算耗尽才释放麦克风。
            svc.startListening(listener, -1)
            speechService = svc
            startIdleWatch(onEnd)
            Log.i(TAG, "vosk listening… (idle detect: ${IDLE_AFTER_SPEECH_MS}ms after speech / ${IDLE_NO_SPEECH_MS}ms silence)")
        }.onFailure {
            Log.w(TAG, "start failed: ${it.message}")
            onEnd()
        }
    }

    /**
     * 静音检测：说完了就自动结束本轮并释放麦克风。
     *
     * 判据（自己算，不用 Vosk 的总预算）：
     *  · 听到过语音 → 静默 [IDLE_AFTER_SPEECH_MS] 即停
     *  · 一直没听到 → 静默 [IDLE_NO_SPEECH_MS] 即停（避免用户误触后一直录）
     */
    private fun startIdleWatch(onEnd: () -> Unit) {
        stopIdleWatch()
        val r = object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastActivityAt
                val limit = if (heardSpeech) IDLE_AFTER_SPEECH_MS else IDLE_NO_SPEECH_MS
                if (idle >= limit) {
                    Log.i(TAG, "idle ${idle}ms (heard=$heardSpeech) → finishing")
                    stopIdleWatch()
                    // 取最终结果（若有），然后释放
                    val svc = speechService
                    stopExec.execute {
                        runCatching { svc?.stop() }
                        runCatching { svc?.shutdown() }
                        speechService = null
                        runCatching { recognizer?.close() }
                        recognizer = null
                        Log.i(TAG, "vosk stopped (idle)")
                    }
                    onEnd()
                    return
                }
                mainHandler.postDelayed(this, IDLE_POLL_MS)
            }
        }
        idleCheck = r
        mainHandler.postDelayed(r, IDLE_POLL_MS)
    }

    private fun stopIdleWatch() {
        idleCheck?.let { mainHandler.removeCallbacks(it) }
        idleCheck = null
    }

    /**
     * 停止并**彻底释放**。
     *
     * ⚠️ 三个调用缺一不可（javap 反解 AAR 0.3.75 确认）：
     *  · `stop()`     只 interrupt 识别线程（AudioRecord 仍持有）
     *  · `shutdown()` 才 release AudioRecord —— 漏掉它每轮泄漏一个录音句柄，
     *                 几轮后 AudioRecord 创建失败，语音输入直接失效
     *  · `Recognizer.close()` 释放 native 句柄
     *
     * ⚠️ 并发安全：**先摘引用再停**。否则「取消」与「新建会话」竞争时，
     * 迟到的 stopAll 会把刚建好的新会话停掉（用户实测「取消了还在听」）。
     */
    /**
     * 停止并**彻底释放**。
     *
     * ⚠️ 调用顺序有讲究（javap 反解 AAR 0.3.75 确认）：
     *  · `cancel()`  先 `setPause(true)` 再停线程 —— 让识别线程**跳过结果处理**直接退出，
     *                比裸 `stop()` 更快（stop 要等当前缓冲区读完并走完 acceptWaveForm）
     *  · `stop()`    兜底（cancel 已停时它是 no-op）
     *  · `shutdown()` 才 release AudioRecord —— 漏掉它每轮泄漏一个录音句柄
     *  · `Recognizer.close()` 释放 native 句柄
     *
     * ⚠️ 并发安全：**先摘引用再停**。否则「取消」与「新建会话」竞争时，
     * 迟到的 stopAll 会把刚建好的新会话停掉（用户实测「取消了还在听」）。
     */
    fun stopAll() {
        stopIdleWatch()   // 旧会话的静音计时器必须停，否则会误停新会话
        val svc = speechService
        val rec = recognizer
        speechService = null
        recognizer = null
        runCatching { svc?.cancel() }   // 快速路径：暂停并停线程
        runCatching { svc?.stop() }     // 兜底（已停则 no-op）
        runCatching { svc?.shutdown() } // 释放 AudioRecord
        runCatching { rec?.close() }
        Log.i(TAG, "vosk stopped")
    }

    /** 释放模型本身（换模型 / 退出时用） */
    private fun releaseModel() {
        stopAll()
        runCatching { model?.close() }
        model = null
        modelPath = null
    }
}


package app.dsh.mobile.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 系统语音合成（TTS）管理器：将 Agent 文本朗读为语音。
 *
 * 使用系统 TextToSpeech 引擎（离线、免费、无云端依赖，与项目本地哲学一致）。
 * 初始化是异步回调，speak() 内部用 latch 等待就绪（上限 10s）；
 * 超长文本按 getMaxSpeechInputLength 截断（系统上限 4000 字符）。
 * flush=true 打断当前朗读立即播报，默认排队追加。
 */
object TtsManager {

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    /** 最近一次初始化失败原因（诊断用：权限中心 TTS 行显示） */
    @Volatile var lastError: String? = null
        private set

    /** 国产 ROM 的 TTS 引擎冷启动（bind 系统服务）经常超过 10s —— 20s 并保留后台完成 */
    private const val WAIT_INIT_MS = 20_000L

    /** 初始化（幂等）：返回是否就绪。language 设为中文，引擎缺失时回退默认。 */
    @Synchronized
    private fun ensure(ctx: Context): Boolean {
        if (ready && tts != null) return true
        val latch = CountDownLatch(1)
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            lastError = if (ready) null else "init status=$status"
            if (ready) {
                val r = tts?.setLanguage(Locale.CHINA)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()   // 中文数据缺失 → 引擎默认语言
                }
            }
            latch.countDown()
        }
        val ok = latch.await(WAIT_INIT_MS, TimeUnit.MILLISECONDS)
        if (!ok && !ready) lastError = "init timeout (${WAIT_INIT_MS / 1000}s, engine cold start?)"
        return ready
    }

    /** 设备上可用的 TTS 引擎数（不触发绑定，用于权限中心诊断行） */
    fun engineCount(ctx: Context): Int = runCatching {
        ctx.packageManager.queryIntentServices(
            android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0,
        ).size
    }.getOrDefault(0)

    /**
     * 朗读文本。
     * @param flush true=打断当前朗读立即播报（QUEUE_FLUSH）；false=排队追加（QUEUE_ADD）
     * @return 人类可读的结果描述（"speaking"/错误原因）
     */
    fun speak(ctx: Context, text: String, flush: Boolean): String {
        // ⚠️ TextToSpeech 的 init 回调绑定【构造线程】的 looper（默认主线程）：
        // 主线程调用 speak → ensure 的 latch.await 与回调互等 → 必然死锁 20s → ANR
        // （用户真机实测：设置页点 TTS 行应用无响应）。此处防御性重入后台线程。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            var result = "TTS: main-thread reentry"
            Thread({ result = speak(ctx, text, flush) }, "dsh-tts-main-reentry")
                .apply { isDaemon = true; start() }
                .join(25_000L)
            return result
        }
        val clean = text.trim().take(TextToSpeech.getMaxSpeechInputLength())
        if (clean.isEmpty()) return "empty text"
        if (!ensure(ctx)) {
            stop()
            return "TTS engine init failed or timeout"
        }
        val engine = tts ?: return "TTS not available"
        val res = engine.speak(
            clean,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "dsh-${System.currentTimeMillis()}",
        )
        return if (res == TextToSpeech.SUCCESS) "speaking (${clean.length} chars, ${if (flush) "flush" else "queued"})"
        else "speak() error code=$res"
    }

    /** 停止当前朗读并清空队列 */
    fun stop() {
        runCatching { tts?.stop() }
    }

    /** 释放引擎（App 退出时） */
    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}

package app.dsh.mobile

import android.content.Context
import android.util.Log
import app.dsh.mobile.engine.AsrModelManager
import app.dsh.mobile.engine.VoskRecognizer
import java.io.File

/**
 * 语音输入的两个跨组件能力（v1.2.65）。
 *
 * 为什么要独立成一个 object：语音入口现在由**无障碍服务**持有（不依赖 MainActivity
 * 存活），但「把文字送进 WebUI 输入框」必须由持有 WebView 的 MainActivity 执行。
 * 两者通过这里解耦：服务侧只管识别，发送侧由 MainActivity 注册回调。
 */
object VoiceBridge {

    private const val TAG = "VoiceBridge"

    /** WebView 注入回调（由 MainActivity 注册；null = 主界面不在） */
    @Volatile private var sender: ((String) -> Unit)? = null

    /** MainActivity 注册发送能力（onCreate 时调用；onDestroy 传 null 注销） */
    fun registerSender(block: ((String) -> Unit)?) {
        sender = block
    }

    /**
     * 把识别文本发给 AI。
     *
     * 主界面不在（用户没打开 App / 被回收）时不能静默失败 —— 那正是旧版
     * 「点了没反应」的观感来源。这里给明确的悬浮窗提示。
     */
    fun send(ctx: Context, text: String) {
        val s = sender
        if (s == null) {
            Log.w(TAG, "send skipped: MainActivity not alive")
            StatusOverlay.flashNotice(ctx.getString(R.string.voice_need_app), 4_000L)
            return
        }
        runCatching { s(text) }
            .onFailure { Log.w(TAG, "send failed: ${it.message}") }
    }

    /** 是否可发送（主界面存活） */
    fun canSend(): Boolean = sender != null

    /** 是否已下载任一可用离线模型（不加载，只查文件） */
    fun hasDownloadedModel(ctx: Context): Boolean {
        val dir = AsrModelManager.modelsDir(ctx)
        return AsrModelManager.downloadedModels(ctx)
            .any { f -> File(dir, f).listFiles()?.isNotEmpty() == true }
    }

    /**
     * 确保离线模型已加载（不阻塞：未加载则后台加载，本次返回 false）。
     * 服务侧与 MainActivity 侧共用同一份加载状态（VoskRecognizer 内部单例）。
     */
    @Volatile private var loading = false

    fun ensureVoskLoaded(ctx: Context): Boolean {
        if (VoskRecognizer.isModelLoaded()) return true
        if (loading) return false

        val dir = AsrModelManager.modelsDir(ctx)
        val path = AsrModelManager.downloadedModels(ctx)
            .firstOrNull { f -> File(dir, f).listFiles()?.isNotEmpty() == true }
            ?: return false
        val full = dir.resolve(path).absolutePath
        if (VoskRecognizer.loadedPath() == full) return true

        loading = true
        Thread({
            val ok = VoskRecognizer.init(full)
            loading = false
            Log.i(TAG, "vosk background load: $ok")
        }, "vosk-load").apply { isDaemon = true; start() }
        return false
    }

    /**
     * 后台加载离线模型，**完成后回调**（在主线程）。
     *
     * 与 `ensureVoskLoaded` 的区别：那个是「尽力而为、本次返回结果」，
     * 这个是「一定会加载完并告诉你」—— 语音入口需要它来避免
     * 「第一次点没反应、第二次才行」的观感（见 DshAccessibilityService.listenIntoPanel）。
     */
    fun loadVoskAsync(ctx: Context, onDone: (Boolean) -> Unit) {
        if (VoskRecognizer.isModelLoaded()) {
            onDone(true)
            return
        }
        val dir = AsrModelManager.modelsDir(ctx)
        val path = AsrModelManager.downloadedModels(ctx)
            .firstOrNull { f -> File(dir, f).listFiles()?.isNotEmpty() == true }
        if (path == null) {
            onDone(false)
            return
        }
        val full = dir.resolve(path).absolutePath
        if (VoskRecognizer.loadedPath() == full) {
            onDone(true)
            return
        }
        loading = true
        Thread({
            val ok = VoskRecognizer.init(full)
            loading = false
            Log.i(TAG, "vosk load (awaited): $ok")
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(ok) }
        }, "vosk-load-await").apply { isDaemon = true; start() }
    }
}

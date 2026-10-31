package app.dsh.mobile.engine.cap

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONObject
import java.io.File

/**
 * 屏幕录制（MediaProjection）。
 *
 * ## 一个绕不开的硬限制必须先说清
 * Android 规定 `MediaProjection` **每次开始都必须由用户在系统弹窗里点"开始录制"**，
 * 没有任何 API 能让 App 静默拿到授权，`MediaProjectionManager.createScreenCaptureIntent()`
 * 返回的 PendingIntent 也不能复用旧授权（Android 14 起 `createScreenCaptureIntent()`
 * 的一次授权只能启动一次投影）。所以本能力提供的是：
 *   - `request`：发起授权请求（需要 Activity，会把系统弹窗拉起来）；
 *   - `start` / `stop`：用已拿到的授权启动/停止录制；
 *   - `status`：当前是否在录、输出文件、时长。
 * 「定时自动录屏」在 Android 上做不到，别承诺。
 *
 * ## 为什么用 MediaRecorder 而不是 MediaCodec 自己封装
 * MediaRecorder 直接吐 MP4，代码量小、兼容性好；MediaCodec + MediaMuxer 只有在需要
 * 逐帧处理（如实时送视觉模型）时才值得。当前目标是「录下来给人看」，选简单路线。
 */
object RecordCap {

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var startedAt: Long = 0L

    /** 保存最近一次授权结果，供 start 使用。 */
    @Volatile private var pendingResultCode: Int = 0
    @Volatile private var pendingData: Intent? = null

    internal fun acceptPermission(resultCode: Int, data: Intent?) {
        pendingResultCode = resultCode
        pendingData = data
    }

    fun status(ctx: Context): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.SCREEN_CAPTURE)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled")
                .put("capability", CapabilityGate.SCREEN_CAPTURE).toString()
        }
        val f = outputFile
        return 200 to JSONObject()
            .put("ok", true)
            .put("recording", recorder != null && projection != null)
            .put("file", f?.absolutePath.orEmpty())
            .put("exists", f?.exists() == true)
            .put("bytes", f?.length() ?: 0L)
            .put("elapsed_ms", if (startedAt > 0 && recorder != null) System.currentTimeMillis() - startedAt else 0L)
            .put("permission_pending", pendingData != null)
            .toString()
    }

    /**
     * POST /dev/record —— `{"op":"request"|"start"|"stop"}`
     *
     * request 需要一个前台 Activity 才能把系统授权弹窗拉起来；
     * 拿不到就明确返回 `activity_unavailable`，不做无意义的静默重试。
     */
    fun control(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.SCREEN_CAPTURE)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled")
                .put("capability", CapabilityGate.SCREEN_CAPTURE).toString()
        }
        val op = runCatching { JSONObject(body.ifBlank { "{}" }).optString("op", "status") }
            .getOrDefault("status")
        return when (op) {
            "request" -> request(ctx)
            "start" -> start(ctx)
            "stop" -> stop(ctx)
            "status" -> status(ctx)
            else -> 400 to JSONObject().put("ok", false).put("error", "unknown_op").put("op", op).toString()
        }
    }

    private fun request(ctx: Context): Pair<Int, String> {
        val host = ScreenRecordRelay.host()
            ?: return 409 to JSONObject().put("ok", false)
                .put("error", "activity_unavailable")
                .put("hint", "bring the app to the foreground, then request again").toString()
        val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
            ?: return 500 to JSONObject().put("ok", false).put("error", "no media projection service").toString()
        return runCatching {
            host.startActivityForResult(mgr.createScreenCaptureIntent(), ScreenRecordRelay.REQ_CAPTURE)
            CapabilityGate.audit(ctx, "dev.record.request")
            200 to JSONObject().put("ok", true)
                .put("awaiting", "system_permission_dialog")
                .put("hint", "user must tap Start now; then POST op=start").toString()
        }.getOrElse { e ->
            500 to JSONObject().put("ok", false).put("error", "request_failed")
                .put("detail", e.message.orEmpty().take(160)).toString()
        }
    }

    private fun start(ctx: Context): Pair<Int, String> {
        if (recorder != null) {
            return 409 to JSONObject().put("ok", false).put("error", "already_recording").toString()
        }
        val rc = pendingResultCode
        val data = pendingData
        if (data == null || rc == 0) {
            return 409 to JSONObject().put("ok", false)
                .put("error", "no_permission")
                .put("hint", "POST op=request first and let the user approve").toString()
        }
        val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
            ?: return 500 to JSONObject().put("ok", false).put("error", "no media projection service").toString()

        return runCatching {
            val proj = mgr.getMediaProjection(rc, data)
                ?: return 500 to JSONObject().put("ok", false).put("error", "projection_null").toString()
            val metrics = ctx.resources.displayMetrics
            val w = (metrics.widthPixels / 2) * 2   // MediaRecorder 要求偶数宽高
            val h = (metrics.heightPixels / 2) * 2
            val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
            val out = File(dir, "rec-${System.currentTimeMillis()}.mp4")

            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            rec.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(w, h)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(4_000_000)
                setOutputFile(out.absolutePath)
                prepare()
            }
            val surface = rec.surface
            proj.createVirtualDisplay(
                "dsh-record", w, h, metrics.densityDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null,
            )
            rec.start()

            projection = proj
            recorder = rec
            outputFile = out
            startedAt = System.currentTimeMillis()
            // 一次授权只够启动一次投影（Android 14+），用过就作废，避免下次误以为还有效。
            pendingData = null
            pendingResultCode = 0

            CapabilityGate.audit(ctx, "dev.record.start")
            200 to JSONObject().put("ok", true)
                .put("file", out.absolutePath)
                .put("width", w).put("height", h).toString()
        }.getOrElse { e ->
            runCatching { recorder?.release() }
            recorder = null
            projection = null
            500 to JSONObject().put("ok", false).put("error", "start_failed")
                .put("detail", e.message.orEmpty().take(200)).toString()
        }
    }

    private fun stop(ctx: Context): Pair<Int, String> {
        val rec = recorder
        if (rec == null) {
            return 409 to JSONObject().put("ok", false).put("error", "not_recording").toString()
        }
        val f = outputFile
        val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        return runCatching {
            // stop() 会把 moov box 写完，必须成功，否则文件不可播；release 放 finally。
            runCatching { rec.stop() }
            rec.release()
            runCatching { projection?.stop() }
            recorder = null
            projection = null
            startedAt = 0L
            CapabilityGate.audit(ctx, "dev.record.stop")
            200 to JSONObject().put("ok", true)
                .put("file", f?.absolutePath.orEmpty())
                .put("bytes", f?.length() ?: 0L)
                .put("elapsed_ms", elapsed).toString()
        }.getOrElse { e ->
            runCatching { rec.release() }
            recorder = null
            projection = null
            500 to JSONObject().put("ok", false).put("error", "stop_failed")
                .put("detail", e.message.orEmpty().take(160)).toString()
        }
    }
}

/**
 * 屏幕录制需要的 Activity 胶水：`createScreenCaptureIntent` 的授权结果只能由
 * Activity 的 `onActivityResult` 收到，桥（Receiver/Service）拿不到。
 *
 * 这里同样只保存弱引用式单例，并把授权结果转交给 [RecordCap.acceptPermission]。
 */
object ScreenRecordRelay {
    const val REQ_CAPTURE = 0x5C01

    @Volatile private var activity: android.app.Activity? = null

    fun attach(a: android.app.Activity) { activity = a }
    fun detach(a: android.app.Activity) { if (activity === a) activity = null }
    fun host(): android.app.Activity? = activity?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** Activity.onActivityResult 转发到这里。 */
    fun onResult(resultCode: Int, data: Intent?) {
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            RecordCap.acceptPermission(resultCode, data)
        }
    }
}

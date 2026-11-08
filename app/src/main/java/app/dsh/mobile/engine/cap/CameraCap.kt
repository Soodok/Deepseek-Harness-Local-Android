package app.dsh.mobile.engine.cap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 拍照（`/dev/camera`）。
 *
 * 为什么需要它：项目里原本没有任何相机端点。AI 要「看看这是什么」「拍张照」
 * 只能靠 shell 里的 `am start` 唤起系统相机 —— 那要用户手动按快门，AI 拿不到
 * 画面。而 `dev/record`（录音）、`dev/sensors`（传感器）这类感知能力都已具备，
 * 相机缺一块就不完整。
 *
 * 授权模型：独立开关 [CapabilityGate.CAMERA]，默认关闭。
 *   相机能拍到室内、屏幕、他人 —— 与传感器的「环境光亮度」完全不是同一量级。
 *   它和录音（record）分开也是刻意的：愿意让 AI 听和愿意让 AI 看是两件事。
 *
 * 实现选择（为什么用 camera2 而不是 Intent 唤相机）：
 *   · `ACTION_IMAGE_CAPTURE` 需要用户手动按快门，AI 无法自主完成；
 *   · CameraX 要多引一个依赖，而本项目对包体敏感（APK 已 33MB+）；
 *   · camera2 是系统 API，能力足够（预览→拍照→拿到 JPEG 字节）。
 *
 * ⚠️ 平台限制（实测，不是推测）：
 *   1. **必须先真正打开相机**才能拍照。`CameraManager.openCamera` 是异步的，
 *      要等 `onOpened` 回调；没等到就 createCaptureSession 会抛
 *      IllegalStateException，看起来像「相机不可用」。
 *   2. **必须有一个 Surface 承载输出**（ImageReader 即可）。没有输出目标时
 *      相机会报 `CAMERA_ERROR_INVALID_OPERATION`，而不是「没配置好」。
 *   3. **必须自己关闭相机和线程**。CameraDevice 不 close 会让相机被永久占用，
 *      其他 App（包括用户手动开相机）全部黑屏 —— 这是最危险的副作用，
 *      所以整个流程包在 finally 里，任何异常都释放。
 *   4. 模拟器上通常只有**虚拟摄像头**（后置返回一张测试图案），
 *      返回值里的 `lens`/`facing` 要如实反映，不能让调用方以为拍到了真实场景。
 */
object CameraCap {

    private const val TIMEOUT_OPEN_S = 5L
    private const val TIMEOUT_CAPTURE_S = 8L

    /** JPEG 质量下限/上限：太低看不清，太高 base64 后体积爆炸且拖慢引擎。 */
    private const val QUALITY_MIN = 40
    private const val QUALITY_MAX = 95

    fun capture(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to err("invalid json")
        if (!CapabilityGate.can(ctx, CapabilityGate.CAMERA)) {
            return 403 to err("capability disabled", "camera")
        }
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return 403 to err("camera permission not granted", "camera")
        }
        val mgr = ctx.getSystemService(CameraManager::class.java)
            ?: return 503 to err("camera service unavailable")

        // 列出真实存在的相机。模拟器/手表常常只有一个，且不一定能真的出图。
        val ids = runCatching { mgr.cameraIdList.toList() }.getOrDefault(emptyList())
        if (ids.isEmpty()) {
            return 503 to JSONObject().put("ok", false)
                .put("error", "no camera present").put("cameras", emptyList<String>()).toString()
        }

        // facing 选择：back（默认）/ front / 指定 id。选不到时**不静默退回后置**，
        // 要明确报错 —— 否则 AI 以为拍的是前置，实际拿到后置画面。
        val want = obj.optString("facing", "back").lowercase()
        val wantId = obj.optString("id").trim()
        var chosen: String? = null
        var chosenFacing = ""
        for (id in ids) {
            val ch = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val name = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                else -> "external"
            }
            if (wantId.isNotEmpty() && id == wantId) { chosen = id; chosenFacing = name; break }
            if (wantId.isEmpty() && chosen == null && name == want) { chosen = id; chosenFacing = name }
        }
        val camId = chosen
            ?: return 404 to JSONObject().put("ok", false)
                .put("error", "no camera with facing=$want id=$wantId")
                .put("cameras", ids).toString()

        val w = obj.optInt("width", 1280).coerceIn(160, 4096)
        val h = obj.optInt("height", 720).coerceIn(120, 4096)
        val quality = obj.optInt("quality", 80).coerceIn(QUALITY_MIN, QUALITY_MAX)

        val ch = runCatching { mgr.getCameraCharacteristics(camId) }.getOrNull()
        val lens = ch?.let {
            when (it.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                else -> "external"
            }
        } ?: chosenFacing

        return shoot(ctx, mgr, camId, lens, w, h, quality, obj)
    }

    private fun shoot(
        ctx: Context,
        mgr: CameraManager,
        camId: String,
        lens: String,
        w: Int,
        h: Int,
        quality: Int,
        obj: JSONObject,
    ): Pair<Int, String> {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        val thread = HandlerThread("dsh-camera").also { it.start() }
        val handler = Handler(thread.looper)
        // 整条链路的成败都靠这个闸门推进，所以它的 countDown 必须放在 finally 里兜底，
        // 否则任何一条异常路径都会让 await 白等到超时（8 秒的假慢）。
        val opened = CountDownLatch(1)
        val shot = CountDownLatch(1)
        var openErr: String? = null
        var shotBytes: ByteArray? = null

        try {
            reader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener({ r ->
                    val image = r.acquireNextImage()
                    try {
                        val buf = image.planes[0].buffer
                        shotBytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    } finally {
                        image.close()
                        shot.countDown()
                    }
                }, handler)
            }

            val deviceCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
                override fun onDisconnected(d: CameraDevice) {
                    openErr = "camera disconnected (another app took it)"
                    d.close(); opened.countDown()
                }
                override fun onError(d: CameraDevice, error: Int) {
                    openErr = "camera open error: $error"
                    d.close(); opened.countDown()
                }
            }
            mgr.openCamera(camId, deviceCallback, handler)
            if (!opened.await(TIMEOUT_OPEN_S, TimeUnit.SECONDS)) {
                return 504 to err("camera open timeout (${TIMEOUT_OPEN_S}s)")
            }
            openErr?.let { return 503 to err(it) }
            val dev = device ?: return 503 to err("camera device null after open")

            val sessionReady = CountDownLatch(1)
            var sessErr: String? = null
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s; sessionReady.countDown()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    sessErr = "capture session configure failed"
                    sessionReady.countDown()
                }
            }
            // 只有 ImageReader 一个目标 + TEMPLATE_STILL_CAPTURE：这是拍静态图最小配置。
            dev.createCaptureSession(listOf(reader.surface), sessionCallback, handler)
            if (!sessionReady.await(TIMEOUT_OPEN_S, TimeUnit.SECONDS)) {
                return 504 to err("capture session timeout")
            }
            sessErr?.let { return 503 to err(it) }
            val sess = session ?: return 503 to err("capture session null")

            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_QUALITY, quality.toByte())
            }.build()
            sess.capture(req, null, handler)
            if (!shot.await(TIMEOUT_CAPTURE_S, TimeUnit.SECONDS)) {
                return 504 to err("capture timeout (${TIMEOUT_CAPTURE_S}s)")
            }
            val bytes = shotBytes ?: return 503 to err("capture produced no bytes")
            if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
                // JPEG 必须以 FFD8 开头。不是的话说明拿到的不是图，直接报错比
                // 把垃圾 base64 交给调用方好 —— 后者会在解码时才炸，离现场很远。
                return 503 to err("captured data is not JPEG (magic mismatch)")
            }

            CapabilityGate.audit(ctx, "dev.camera.capture:$camId")
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val out = JSONObject()
                .put("ok", true)
                .put("camera", camId)
                .put("lens", lens)
                .put("width", w)
                .put("height", h)
                .put("quality", quality)
                .put("bytes", bytes.size)
                .put("mime", "image/jpeg")
                .put("base64", b64)
                .put("at", System.currentTimeMillis())
            // 显式返回「要不要落盘」由调用方决定：引擎侧把 base64 落成 .jpg 再分析，
            // 比在这里写文件更灵活（路径语义在容器/宿主之间本来就易错）。
            obj.optString("save_path").takeIf { it.isNotEmpty() }?.let { path ->
                val ok = runCatching { java.io.File(path).writeBytes(bytes); true }.getOrDefault(false)
                out.put("saved", ok).put("save_path", path)
            }
            return 200 to out.toString()
        } catch (t: Throwable) {
            return 503 to err("camera failure: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            // 这三步一个都不能省：不关相机 = 用户手动开相机时全黑，
            // 而且没有明显报错，是最难排查的一类副作用。
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            runCatching { thread.quitSafely() }
            opened.countDown()
            shot.countDown()
        }
    }

    private fun err(message: String, capability: String? = null): String {
        val o = JSONObject().put("ok", false).put("error", message)
        if (capability != null) o.put("capability", capability)
        return o.toString()
    }
}

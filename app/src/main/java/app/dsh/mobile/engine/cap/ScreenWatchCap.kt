package app.dsh.mobile.engine.cap

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 屏幕变化检测（P1-5）：只回答「屏幕有没有变、哪块区域在变」，**不存视频、不存图**。
 *
 * ## 为什么需要它
 * 现在 AI 看屏幕要么截图（几十毫秒 + 几十 MB + 一次读图成本），要么靠无障碍节点树
 * （拿不到像素级信息，动画/视频/自绘控件全盲）。中间缺一层：
 * **廉价的「变没变」**。有了它就能做到「等页面加载完再截图」「只在有变化时唤醒」。
 *
 * ## 做法
 * MediaProjection → VirtualDisplay → ImageReader（小分辨率，默认 1/8 边长），
 * 每抓一帧转成灰度数组与上一帧做差，统计：
 * - `changed_ratio`：差值超过阈值的像素占比
 * - `mean_delta`：平均差值（0-255）
 * - `blocks`：粗网格（默认 8×8）里变化最剧烈的几块，给出归一化坐标 ——
 *   AI 据此知道「变化发生在屏幕下半部中间」，不必看像素。
 *
 * ## 为什么分辨率要小
 * 1080p 一帧 8MB，转灰度再差分会吃掉几十毫秒；缩到 1/8（约 135×240）后每帧
 * 只有 3 万像素，差分是亚毫秒级。判断「变没变」不需要全分辨率。
 *
 * ## 授权
 * 与录屏同一个 MediaProjection 授权（用户必须在系统弹窗点同意）。Android 14+
 * 一次授权只能起一个投影，所以 [start] 会消费掉授权，重复 start 要重新 request。
 */
object ScreenWatchCap {

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var running: Boolean = false
    @Volatile private var downscale: Int = 8
    @Volatile private var threshold: Int = 12
    @Volatile private var grid: Int = 8

    /** 上一帧的灰度（用于差分）。 */
    @Volatile private var prevGray: ByteArray? = null
    @Volatile private var prevW: Int = 0
    @Volatile private var prevH: Int = 0
    @Volatile private var frames: Long = 0
    @Volatile private var startedAt: Long = 0L

    /** 最近一次差分结果。 */
    @Volatile private var last: Diff? = null

    private data class Diff(
        val width: Int,
        val height: Int,
        val changedRatio: Float,
        val meanDelta: Float,
        val maxDelta: Int,
        val blocks: List<Triple<Int, Int, Float>>,
        val at: Long,
    )

    fun status(ctx: Context): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.SCREEN_CAPTURE)) {
            return denied(CapabilityGate.SCREEN_CAPTURE)
        }
        val d = last
        return 200 to JSONObject()
            .put("ok", true)
            .put("running", running)
            .put("frames_captured", frames)
            .put("elapsed_ms", if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L)
            .put("downscale", downscale)
            .put("threshold", threshold)
            .put("grid", grid)
            .also { o ->
                if (d != null) {
                    o.put("last", diffJson(d))
                }
            }
            .toString()
    }

    fun control(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.SCREEN_CAPTURE)) {
            return denied(CapabilityGate.SCREEN_CAPTURE)
        }
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to error("invalid json")
        return when (val op = obj.optString("op").ifBlank { "status" }) {
            "status" -> status(ctx)
            "start" -> start(ctx, obj)
            "stop" -> stop()
            "diff" -> diffNow(obj)
            "request" -> request(ctx)
            else -> 400 to error("unknown op '$op' (status|start|stop|diff|request)")
        }
    }

    private fun request(ctx: Context): Pair<Int, String> {
        val host = ScreenRecordRelay.host()
            ?: return 409 to error("activity_unavailable")
        val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
            ?: return 500 to error("no media projection service")
        return runCatching {
            host.startActivityForResult(mgr.createScreenCaptureIntent(), ScreenRecordRelay.REQ_CAPTURE)
            200 to JSONObject().put("ok", true)
                .put("hint", "user must approve the system dialog, then call op=start")
                .toString()
        }.getOrElse { 500 to error("request_failed: ${it.message}") }
    }

    @SuppressLint("WrongConstant")
    private fun start(ctx: Context, body: JSONObject): Pair<Int, String> {
        if (running) return 409 to error("already running; op=stop first")
        val rc = RecordCap.pendingResultCodePublic()
        val data = RecordCap.pendingDataPublic()
        if (data == null || rc == 0) {
            return 409 to error("no_permission")
        }
        downscale = body.optInt("downscale", 8).coerceIn(2, 32)
        threshold = body.optInt("threshold", 12).coerceIn(1, 128)
        grid = body.optInt("grid", 8).coerceIn(2, 16)

        return runCatching {
            val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
                ?: return 500 to error("no media projection service")
            val proj = mgr.getMediaProjection(rc, data)
                ?: return 500 to error("projection_null")

            val metrics = ctx.resources.displayMetrics
            val w = (metrics.widthPixels / downscale).coerceAtLeast(32)
            val h = (metrics.heightPixels / downscale).coerceAtLeast(32)
            val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val vd = proj.createVirtualDisplay(
                "dsh-screenwatch", w, h, 1,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rd.surface, null, null,
            )
            projection = proj
            display = vd
            reader = rd
            running = true
            frames = 0
            startedAt = System.currentTimeMillis()
            prevGray = null
            RecordCap.clearPendingPublic()
            // 一次性消费掉授权（Android 14+ 同一授权不能复用）
            CapabilityGate.audit(ctx, "dev.screenwatch.start")
            200 to JSONObject().put("ok", true)
                .put("width", w).put("height", h)
                .put("downscale", downscale)
                .put("hint", "call op=diff to sample; it returns changed_ratio and hot blocks")
                .toString()
        }.getOrElse { e ->
            runCatching { reader?.close() }
            runCatching { display?.release() }
            runCatching { projection?.stop() }
            reader = null; display = null; projection = null; running = false
            500 to error("start_failed: ${e.message.orEmpty().take(200)}")
        }
    }

    private fun stop(): Pair<Int, String> {
        if (!running) return 200 to JSONObject().put("ok", true).put("was_running", false).toString()
        runCatching { reader?.close() }
        runCatching { display?.release() }
        runCatching { projection?.stop() }
        reader = null; display = null; projection = null
        running = false
        prevGray = null
        last = null
        startedAt = 0L
        return 200 to JSONObject().put("ok", true).put("was_running", true)
            .put("frames_captured", frames).toString()
    }

    /**
     * 抓一帧并与上一帧比。
     *
     * @param wait 是否阻塞等新帧（默认 true，最多等 waitMs）；false 时用最近一帧
     */
    private fun diffNow(body: JSONObject): Pair<Int, String> {
        val rd = reader ?: return 409 to error("not running; op=start first")
        val waitMs = body.optLong("waitMs", 800L).coerceIn(0L, 5_000L)
        val deadline = System.currentTimeMillis() + waitMs
        var img: android.media.Image? = null
        while (System.currentTimeMillis() <= deadline) {
            img = runCatching { rd.acquireLatestImage() }.getOrNull()
            if (img != null) break
            if (waitMs == 0L) break
            runCatching { Thread.sleep(40) }
        }
        if (img == null) {
            return 200 to JSONObject().put("ok", true).put("frame", false)
                .put("note", "no frame available yet; screen may be static (VirtualDisplay still emits) or frozen")
                .also { o -> last?.let { o.put("last", diffJson(it)) } }
                .toString()
        }
        return runCatching {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = img.width
            val h = img.height
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)

            // RGBA → 灰度（用近似亮度，省一次乘加）
            val gray = ByteArray(w * h)
            var i = 0
            for (y in 0 until h) {
                val row = y * rowStride
                for (x in 0 until w) {
                    val p = row + x * pixelStride
                    if (p + 2 < bytes.size) {
                        val r = bytes[p].toInt() and 0xFF
                        val g = bytes[p + 1].toInt() and 0xFF
                        val b = bytes[p + 2].toInt() and 0xFF
                        gray[i] = (((r * 77 + g * 150 + b * 29) shr 8) and 0xFF).toByte()
                    }
                    i++
                }
            }

            frames++
            val prev = prevGray
            val pgW = prevW
            val pgH = prevH
            prevGray = gray
            prevW = w
            prevH = h

            if (prev == null || pgW != w || pgH != h) {
                val d = Diff(w, h, 0f, 0f, 0, emptyList(), System.currentTimeMillis())
                last = d
                return@runCatching 200 to JSONObject().put("ok", true).put("frame", true)
                    .put("first_frame", true)
                    .put("width", w).put("height", h)
                    .put("note", "baseline captured; call op=diff again to get a delta")
                    .toString()
            }

            var changed = 0L
            var sum = 0L
            var maxD = 0
            for (k in gray.indices) {
                val d = kotlin.math.abs((gray[k].toInt() and 0xFF) - (prev[k].toInt() and 0xFF))
                if (d >= threshold) changed++
                sum += d
                if (d > maxD) maxD = d
            }
            val total = gray.size.coerceAtLeast(1)
            val ratio = changed.toFloat() / total
            val mean = sum.toFloat() / total

            // 粗网格热点：每块独立统计变化像素占比
            val gx = grid
            val gy = grid
            val bw = (w + gx - 1) / gx
            val bh = (h + gy - 1) / gy
            val hot = ArrayList<Triple<Int, Int, Float>>()
            for (by in 0 until gy) {
                for (bx in 0 until gx) {
                    var c = 0
                    var n = 0
                    for (y in by * bh until minOf((by + 1) * bh, h)) {
                        val rowOff = y * w
                        for (x in bx * bw until minOf((bx + 1) * bw, w)) {
                            val k = rowOff + x
                            val d = kotlin.math.abs(
                                (gray[k].toInt() and 0xFF) - (prev[k].toInt() and 0xFF),
                            )
                            if (d >= threshold) c++
                            n++
                        }
                    }
                    if (n > 0 && c > 0) hot.add(Triple(bx, by, c.toFloat() / n))
                }
            }
            hot.sortByDescending { it.third }
            val d = Diff(w, h, ratio, mean, maxD, hot.take(6), System.currentTimeMillis())
            last = d
            200 to JSONObject().put("ok", true).put("frame", true)
                .put("first_frame", false)
                .also { it.put("diff", diffJson(d)) }
                .toString()
        }.getOrElse { e ->
            500 to error("diff_failed: ${e.message.orEmpty().take(160)}")
        }.also {
            runCatching { img.close() }
        }
    }

    private fun diffJson(d: Diff): JSONObject {
        val arr = JSONArray()
        d.blocks.forEach { (bx, by, r) ->
            arr.put(
                JSONObject()
                    .put("col", bx)
                    .put("row", by)
                    .put("ratio", (r * 1000).toInt() / 1000.0),
            )
        }
        return JSONObject()
            .put("width", d.width)
            .put("height", d.height)
            .put("changed_ratio", (d.changedRatio * 1000).toInt() / 1000.0)
            .put("mean_delta", (d.meanDelta * 10).toInt() / 10.0)
            .put("max_delta", d.maxDelta)
            .put("hot_blocks", arr)
            .put("at", d.at)
    }

    private fun denied(cap: String): Pair<Int, String> = 403 to JSONObject()
        .put("ok", false).put("error", "capability disabled").put("capability", cap).toString()

    private fun error(m: String): String = JSONObject().put("ok", false).put("error", m).toString()
}

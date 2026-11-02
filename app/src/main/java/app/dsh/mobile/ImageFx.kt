package app.dsh.mobile

import android.graphics.Bitmap
import java.io.File
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 背景图特效（v1.5.x）：不透明度 + 高斯模糊。
 */
object ImageFx {

    /** 不透明度换算：0~100 → 0~255。 */
    fun alphaOf(percent: Int): Int = percent.coerceIn(0, 100) * 255 / 100

    /** 滑块 0~24 映射到高斯标准差：sigma 到 8 已是很重的模糊，再大只增加耗时。 */
    private fun sigmaOf(strength: Int): Float = strength.coerceIn(0, 24) / 3f

    /**
     * **真高斯模糊**（可分离卷积，不是降采样近似）。
     *
     * ## 为什么不用降采样
     * 之前用「缩小→放大」冒充模糊，放大出来是双线性插值的结果，边缘有块状/棱角感。
     * 真高斯对每个像素做加权平均，过渡是均匀柔和的。
     *
     * ## 为什么可分离
     * 二维高斯核 G(x,y)=G(x)·G(y) 可拆成水平、垂直两次一维卷积，复杂度从
     * O(w·h·r²) 降到 O(w·h·r)，半径 24 时差 24 倍 —— 这是能不能用的差别。
     *
     * ## 边缘 clamp
     * 核越界时取最近的边缘像素，而不是补 0：补 0 会让四周出现一圈暗边。
     */
    fun gaussianBlur(src: Bitmap, strength: Int): Bitmap {
        if (strength <= 0) return src
        val sigma = sigmaOf(strength)
        // 半径取 3σ：高斯在 3σ 外的权重 < 0.3%，再远没有可见贡献
        val radius = (sigma * 3).roundToInt().coerceAtLeast(1)
        val kernel = buildKernel(radius, sigma)

        val w = src.width
        val h = src.height
        val srcPx = IntArray(w * h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)

        val tmpPx = IntArray(w * h)
        blurHorizontal(srcPx, tmpPx, w, h, kernel, radius)
        val outPx = IntArray(w * h)
        blurVertical(tmpPx, outPx, w, h, kernel, radius)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    /** 归一化的一维高斯核（长度 2r+1，和为 1）。 */
    private fun buildKernel(radius: Int, sigma: Float): FloatArray {
        val size = radius * 2 + 1
        val k = FloatArray(size)
        var sum = 0f
        val twoSigmaSq = 2f * sigma * sigma
        for (i in -radius..radius) {
            val v = exp(-(i * i) / twoSigmaSq)
            k[i + radius] = v
            sum += v
        }
        for (i in k.indices) k[i] /= sum
        return k
    }

    private fun blurHorizontal(
        src: IntArray, dst: IntArray, w: Int, h: Int, kernel: FloatArray, radius: Int,
    ) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    val c = src[row + sx]
                    val weight = kernel[k + radius]
                    a += ((c ushr 24) and 0xFF) * weight
                    r += ((c ushr 16) and 0xFF) * weight
                    g += ((c ushr 8) and 0xFF) * weight
                    b += (c and 0xFF) * weight
                }
                dst[row + x] = pack(a, r, g, b)
            }
        }
    }

    private fun blurVertical(
        src: IntArray, dst: IntArray, w: Int, h: Int, kernel: FloatArray, radius: Int,
    ) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    val c = src[sy * w + x]
                    val weight = kernel[k + radius]
                    a += ((c ushr 24) and 0xFF) * weight
                    r += ((c ushr 16) and 0xFF) * weight
                    g += ((c ushr 8) and 0xFF) * weight
                    b += (c and 0xFF) * weight
                }
                dst[y * w + x] = pack(a, r, g, b)
            }
        }
    }

    private fun pack(a: Float, r: Float, g: Float, b: Float): Int =
        (clamp255(a) shl 24) or (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)

    private fun clamp255(v: Float): Int = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> v.roundToInt()
    }

    /**
     * 带缓存的高斯模糊：同一张图 + 同一强度只算一次。
     *
     * 高斯是逐像素运算，整屏尺寸算一遍要几百毫秒；设置页每次 onResume 都重贴背景，
     * 不缓存会明显卡顿。缓存键 = 源文件修改时间 + 强度，换图或改滑块自然失效。
     *
     * 输入最长边先限制到 [MAX_EDGE] —— 这只是限制**输入分辨率**，模糊本身仍是真高斯，
     * 不会有降采样放大那种块状感。
     */
    fun cachedBlur(
        context: android.content.Context,
        srcFile: File,
        strength: Int,
        maxEdge: Int = MAX_EDGE,
    ): Bitmap? {
        val raw = runCatching {
            android.graphics.BitmapFactory.decodeFile(srcFile.absolutePath)
        }.getOrNull() ?: return null
        if (strength <= 0) return raw

        val cacheDir = File(context.filesDir, "appearance/cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cached = File(cacheDir, "bg_${srcFile.lastModified()}_${strength}_$maxEdge.png")
        if (cached.isFile) {
            val hit = runCatching {
                android.graphics.BitmapFactory.decodeFile(cached.absolutePath)
            }.getOrNull()
            if (hit != null) {
                if (raw !== hit) raw.recycle()
                return hit
            }
        }
        val limited = limitEdge(raw, maxEdge)
        val blurred = gaussianBlur(limited, strength)
        if (limited !== raw) raw.recycle()
        runCatching {
            cached.outputStream().use { out ->
                blurred.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        // 同一张图换个模糊值时，清掉旧缓存，避免缓存目录越堆越大
        runCatching {
            val prefix = "bg_${srcFile.lastModified()}_"
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith(prefix) && f.name != cached.name) f.delete()
            }
        }
        return blurred
    }

    /** 把最长边限制到 maxEdge 以内（已经够小则原样返回）。 */
    private fun limitEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).roundToInt().coerceAtLeast(1),
            (src.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** 高斯卷积的输入分辨率上限（见 [cachedBlur]）。 */
    private const val MAX_EDGE = 1080

    /**
     * 预览用的输入上限：预览区只有 160dp 高，用 480px 输入完全够看，
     * 而拖滑块时每一档都要重算 —— 1080 宽在这个尺寸下会明显掉帧。
     */
    const val PREVIEW_EDGE = 480
}

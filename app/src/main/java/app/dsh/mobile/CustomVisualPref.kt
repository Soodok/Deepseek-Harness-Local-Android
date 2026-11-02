package app.dsh.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 外观自定义素材（v1.5.x）：设置页背景图、收起态灵动岛圆点图。
 *
 * ## 为什么要拷贝到私有目录
 * 从系统相册选回来的 `content://` URI 只在本进程、本次授权内有效。用户重启 App、
 * 或系统回收授权后，原 URI 就再也读不到图 —— 下次启动背景变空、圆点变回默认。
 * 所以选图后立刻把字节拷贝到 `filesDir/appearance/`，持久化只存文件名。
 *
 * ## 为什么不存 URI 的原图尺寸
 * 两张图的使用尺寸都很小（背景铺满屏幕、圆点直径仅 26dp），没必要保留原图。
 * 拷贝时就按各自上限缩放到合适尺寸，既省空间也避免大图解码 OOM。
 */
object CustomVisualPref {
    /** 设置页背景图（持久化的文件名，空 = 不启用） */
    private const val KEY_SETTINGS_BG = "settings_bg_file"

    /** 收起态圆点图（持久化的文件名，空 = 用默认小亮核） */
    private const val KEY_ISLAND_DOT = "island_dot_file"

    /** 设置页背景图的不透明度（0~100）与模糊强度（0~24）。 */
    const val BG_ALPHA_DEFAULT = 100
    const val BG_BLUR_DEFAULT = 0
    const val BG_BLUR_MAX = 24

    private const val KEY_BG_ALPHA = "settings_bg_alpha"
    private const val KEY_BG_BLUR = "settings_bg_blur"

    fun settingsBgAlpha(context: Context): Int =
        prefs(context).getInt(KEY_BG_ALPHA, BG_ALPHA_DEFAULT).coerceIn(0, 100)

    fun setSettingsBgAlpha(context: Context, alpha: Int) {
        prefs(context).edit().putInt(KEY_BG_ALPHA, alpha.coerceIn(0, 100)).apply()
    }

    fun settingsBgBlur(context: Context): Int =
        prefs(context).getInt(KEY_BG_BLUR, BG_BLUR_DEFAULT).coerceIn(0, BG_BLUR_MAX)

    fun setSettingsBgBlur(context: Context, blur: Int) {
        prefs(context).edit().putInt(KEY_BG_BLUR, blur.coerceIn(0, BG_BLUR_MAX)).apply()
    }

    /** 展开态状态栏背景图的文件名（空 = 用默认的近黑胶囊） */
    private const val KEY_EXPANDED_BG = "expanded_bg_file"

    fun expandedBgFile(context: Context): File? = file(context, KEY_EXPANDED_BG)

    fun hasExpandedBg(context: Context): Boolean = expandedBgFile(context) != null

    fun importExpandedBg(context: Context, uri: Uri): Boolean =
        import(context, uri, KEY_EXPANDED_BG, "expanded_bg", BG_MAX_WIDTH, 0)

    fun clearExpandedBg(context: Context) = clear(context, KEY_EXPANDED_BG)

    /** 收起态圆点的显示形状：圆形裁切，或保留原图的方形。 */
    enum class DotShape { CIRCLE, SQUARE }

    /** 收起态圆点边长（dp）：可调，默认 26（与布局里 handleBar 原始尺寸一致）。 */
    const val DOT_SIZE_MIN_DP = 26
    const val DOT_SIZE_MAX_DP = 72
    const val DOT_SIZE_DEFAULT_DP = 26

    private const val KEY_DOT_SHAPE = "island_dot_shape"
    private const val KEY_DOT_SIZE = "island_dot_size_dp"

    fun islandDotShape(context: Context): DotShape {
        val raw = prefs(context).getString(KEY_DOT_SHAPE, null)
        return DotShape.entries.firstOrNull { it.name == raw } ?: DotShape.CIRCLE
    }

    fun setIslandDotShape(context: Context, shape: DotShape) {
        prefs(context).edit().putString(KEY_DOT_SHAPE, shape.name).apply()
    }

    fun islandDotSizeDp(context: Context): Int =
        prefs(context).getInt(KEY_DOT_SIZE, DOT_SIZE_DEFAULT_DP)
            .coerceIn(DOT_SIZE_MIN_DP, DOT_SIZE_MAX_DP)

    fun setIslandDotSizeDp(context: Context, dp: Int) {
        prefs(context).edit()
            .putInt(KEY_DOT_SIZE, dp.coerceIn(DOT_SIZE_MIN_DP, DOT_SIZE_MAX_DP)).apply()
    }

    /** 素材目录：随 App 私有数据一起被备份，卸载即清 */
    private fun dir(context: Context): File =
        File(context.filesDir, "appearance").apply { if (!exists()) mkdirs() }

    private fun file(context: Context, key: String): File? =
        prefs(context).getString(key, null)?.takeIf { it.isNotBlank() }?.let { name ->
            File(dir(context), name).takeIf { it.isFile }
        }

    /** 背景图画布上限宽度：超出按比例缩小，避免整屏 4K 图解码占内存 */
    private const val BG_MAX_WIDTH = 1440

    /** 圆点图边长上限：显示尺寸只有 26dp，给 3 倍屏留足余量 */
    private const val DOT_MAX_PX = 216

    fun settingsBgFile(context: Context): File? = file(context, KEY_SETTINGS_BG)
    fun islandDotFile(context: Context): File? = file(context, KEY_ISLAND_DOT)

    fun hasSettingsBg(context: Context): Boolean = settingsBgFile(context) != null
    fun hasIslandDot(context: Context): Boolean = islandDotFile(context) != null

    /** 导入设置页背景图。@return true = 已保存 */
    fun importSettingsBg(context: Context, uri: Uri): Boolean =
        import(context, uri, KEY_SETTINGS_BG, "settings_bg", BG_MAX_WIDTH, 0)

    /** 导入收起态圆点图。@return true = 已保存 */
    fun importIslandDot(context: Context, uri: Uri): Boolean =
        import(context, uri, KEY_ISLAND_DOT, "island_dot", DOT_MAX_PX, DOT_MAX_PX)

    fun clearSettingsBg(context: Context) = clear(context, KEY_SETTINGS_BG)

    fun clearIslandDot(context: Context) = clear(context, KEY_ISLAND_DOT)

    /**
     * 读入 → 按上限缩放 → 写私有目录 → 记文件名。
     *
     * 缩放用 `inSampleSize` 先粗降采样，再精确缩放到目标边长 —— 对大图
     * （手机相册常见 4000×3000）只靠 `createScaledBitmap` 需要先整张解码，
     * 峰值内存可能到几十 MB；两步走能把峰值压到原来的 1/N。
     *
     * @param maxW 目标上限宽；@param maxH 目标上限高（0 = 按宽等比，不限高）
     */
    private fun import(
        context: Context, uri: Uri, key: String, baseName: String, maxW: Int, maxH: Int,
    ): Boolean = runCatching {
        val resolver = context.contentResolver
        // 第一遍：只读尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false

        // 粗降采样
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxW, maxH)
        }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching false

        // 精确缩放
        val scaled = resize(raw, maxW, maxH)
        if (scaled !== raw) raw.recycle()

        val target = File(dir(context), "$baseName.png")
        target.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
        if (!scaled.isRecycled) scaled.recycle()

        prefs(context).edit().putString(key, target.name).apply()
        true
    }.getOrDefault(false)

    /** 依次尝试缩放：宽高都不超上限时返回原图（同一对象）。 */
    private fun resize(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val w = src.width
        val h = src.height
        val scaleW = if (maxW > 0 && w > maxW) maxW.toFloat() / w else 1f
        val scaleH = if (maxH > 0 && h > maxH) maxH.toFloat() / h else 1f
        val scale = minOf(scaleW, scaleH)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    /** 降低采样倍率：保证降采样后的边长仍 >= 目标边长（否则会缩得比目标还小）。 */
    private fun sampleSizeFor(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        var sample = 1
        while (true) {
            val nw = w / (sample * 2)
            val nh = h / (sample * 2)
            val tooWide = maxW > 0 && nw < maxW
            val tooTall = maxH > 0 && nh < maxH && maxW <= 0
            if (tooWide || tooTall) break
            sample *= 2
        }
        return sample
    }

    private fun clear(context: Context, key: String) {
        file(context, key)?.delete()
        prefs(context).edit().remove(key).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
}

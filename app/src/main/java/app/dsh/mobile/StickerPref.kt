package app.dsh.mobile

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 展开态状态栏的贴纸（v1.5.x）。
 *
 * 用户可以往展开态的那条胶囊上贴若干图片，每张各自的相对位置与大小。
 *
 * ## 相对坐标
 * 位置以**比例**存（0~1 相对状态栏宽高），而不是绝对像素 —— 状态栏在不同机型
 * / 横竖屏下宽高都不同，绝对坐标换台设备就跑到屏幕外了。
 *
 * ## 文件落盘
 * 与背景图同理：`content://` 授权随进程/会话失效，所以选图后立刻把字节拷到
 * `filesDir/appearance/`，只持久化文件名。
 */
object StickerPref {

    /** 一张贴纸：文件名 + 中心点相对位置 + 边长（dp）。 */
    data class Sticker(
        val file: String,
        val xRatio: Float,
        val yRatio: Float,
        val sizeDp: Int,
    )

    private const val KEY = "status_bar_stickers"

    /** 贴纸边长范围（dp） */
    const val SIZE_MIN_DP = 16
    const val SIZE_MAX_DP = 64
    const val SIZE_DEFAULT_DP = 32

    private fun dir(context: Context): File =
        File(context.filesDir, "appearance").apply { if (!exists()) mkdirs() }

    fun fileOf(context: Context, sticker: Sticker): File? =
        File(dir(context), sticker.file).takeIf { it.isFile }

    fun list(context: Context): List<Sticker> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("f").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // 文件被外部清掉时跳过，避免界面上留下空白占位
                if (!File(dir(context), name).isFile) return@mapNotNull null
                Sticker(
                    file = name,
                    xRatio = o.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                    yRatio = o.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                    sizeDp = o.optInt("s", SIZE_DEFAULT_DP).coerceIn(SIZE_MIN_DP, SIZE_MAX_DP),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<Sticker>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("f", it.file)
                    .put("x", it.xRatio.toDouble())
                    .put("y", it.yRatio.toDouble())
                    .put("s", it.sizeDp),
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * 导入一张贴纸（居中放在状态栏里）。
     *
     * @return 新增的贴纸；读图失败返回 null
     */
    fun add(context: Context, uri: Uri): Sticker? {
        val name = "sticker_${System.currentTimeMillis()}.png"
        val target = File(dir(context), name)
        val ok = runCatching {
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            } ?: return null
            // 贴纸显示尺寸很小，先缩到 256px 以内再落盘，省空间也省解码
            val scaled = scaleDown(bmp, 256)
            target.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (scaled !== bmp) bmp.recycle()
            true
        }.getOrDefault(false)
        if (!ok) return null

        val sticker = Sticker(
            file = name,
            xRatio = 0.5f,
            yRatio = 0.5f,
            sizeDp = SIZE_DEFAULT_DP,
        )
        save(context, list(context) + sticker)
        return sticker
    }

    /** 按索引删除贴纸，同时删掉它的图片文件。 */
    fun removeAt(context: Context, index: Int) {
        val items = list(context).toMutableList()
        if (index !in items.indices) return
        val removed = items.removeAt(index)
        File(dir(context), removed.file).delete()
        save(context, items)
    }

    /** 拖动后更新位置（相对比例）。 */
    fun moveTo(context: Context, index: Int, xRatio: Float, yRatio: Float) {
        val items = list(context).toMutableList()
        if (index !in items.indices) return
        items[index] = items[index].copy(
            xRatio = xRatio.coerceIn(0f, 1f),
            yRatio = yRatio.coerceIn(0f, 1f),
        )
        save(context, items)
    }

    /** 调整某张贴纸的边长。 */
    fun resize(context: Context, index: Int, sizeDp: Int) {
        val items = list(context).toMutableList()
        if (index !in items.indices) return
        items[index] = items[index].copy(
            sizeDp = sizeDp.coerceIn(SIZE_MIN_DP, SIZE_MAX_DP),
        )
        save(context, items)
    }

    fun clearAll(context: Context) {
        list(context).forEach { File(dir(context), it.file).delete() }
        prefs(context).edit().remove(KEY).apply()
    }

    private fun scaleDown(src: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxPx) return src
        val scale = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
}

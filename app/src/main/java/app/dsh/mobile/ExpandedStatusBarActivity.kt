package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * 展开态状态栏设置（v1.5.x）：背景图 + 贴纸。
 *
 * ## 预览为什么按比例
 * 真机状态栏是「撑满宽度、44dp 高」的胶囊。预览区里按接近的比例缩一条出来，
 * 贴纸位置用**相对比例**（0~1）记录，这样预览和真机、横竖屏、不同机型之间都
 * 对得上 —— 拖到哪儿就贴在哪儿。
 *
 * ## 贴纸交互
 *  · 添加：选图后默认落在状态栏中央；
 *  · 拖动：长按拖动改变位置（松手写回偏好）；
 *  · 删除：双击贴纸即可移除（状态栏本身很小，长按留给拖动更顺手）。
 */
class ExpandedStatusBarActivity : ThemedActivity() {

    /** 属于设置体系：启用「设置页背景图」。 */
    override val usesSettingsBackground: Boolean get() = true

    private lateinit var preview: FrameLayout
    private lateinit var txtHint: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_status_expanded)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        preview = findViewById(R.id.previewStatusBar)
        txtHint = findViewById(R.id.txtPreviewHint)

        findViewById<View>(R.id.btnPickBg).setOnClickListener {
            pickImage(REQ_BG)
        }
        findViewById<View>(R.id.btnRemoveBg).setOnClickListener {
            CustomVisualPref.clearExpandedBg(this)
            render()
        }
        findViewById<View>(R.id.btnAddSticker).setOnClickListener {
            pickImage(REQ_STICKER)
        }
        findViewById<View>(R.id.btnClearStickers).setOnClickListener {
            StickerPref.clearAll(this)
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
        render()
    }

    /** 重画预览：背景 + 全部贴纸。 */
    private fun render() {
        // 背景：有自定义图就铺图，否则用默认胶囊
        val bgFile = CustomVisualPref.expandedBgFile(this)
        if (bgFile != null) {
            val bmp = runCatching {
                android.graphics.BitmapFactory.decodeFile(bgFile.absolutePath)
            }.getOrNull()
            if (bmp != null) {
                preview.background = BitmapDrawable(resources, bmp).apply {
                    gravity = android.view.Gravity.FILL
                }
            } else {
                preview.setBackgroundResource(R.drawable.bg_dynamic_island)
            }
        } else {
            preview.setBackgroundResource(R.drawable.bg_dynamic_island)
        }

        // 贴纸：清掉旧 View 再按当前列表重建
        preview.removeAllViews()
        val items = StickerPref.list(this)
        txtHint.text = if (items.isEmpty()) {
            getString(R.string.setting_sticker_hint)
        } else {
            getString(R.string.setting_sticker_count, items.size)
        }

        items.forEachIndexed { index, sticker ->
            val iv = ImageView(this)
            val bmp = StickerPref.fileOf(this, sticker)?.let {
                runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
            }
            if (bmp == null) return@forEachIndexed
            iv.setImageBitmap(bmp)
            val sizePx = (sticker.sizeDp * resources.displayMetrics.density).toInt()
            iv.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            // 位置要在布局完成后再算（那时才知道容器宽高）
            iv.post { placeSticker(iv, sticker, sizePx) }
            attachDrag(iv, index, sizePx)
            preview.addView(iv)
        }
    }

    /** 按相对比例把贴纸摆到预览容器里（以贴纸中心为锚点，并夹在容器内）。 */
    private fun placeSticker(v: View, sticker: StickerPref.Sticker, sizePx: Int) {
        val w = preview.width
        val h = preview.height
        if (w <= 0 || h <= 0) return
        // 上限夹到 >= 0：贴纸可以比预览容器还高，负的上界会让 coerceIn 抛异常
        v.x = (sticker.xRatio * w - sizePx / 2f)
            .coerceIn(0f, maxOf(0f, (w - sizePx).toFloat()))
        v.y = (sticker.yRatio * h - sizePx / 2f)
            .coerceIn(0f, maxOf(0f, (h - sizePx).toFloat()))
    }

    /** 拖动贴纸：跟随手指，松手后把新位置写回偏好。 */
    private fun attachDrag(v: View, index: Int, sizePx: Int) {
        var startX = 0f
        var startY = 0f
        var originX = 0f
        var originY = 0f
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    originX = view.x
                    originY = view.y
                    view.bringToFront()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = originX + (ev.rawX - startX)
                    val ny = originY + (ev.rawY - startY)
                    view.x = nx.coerceIn(0f, (preview.width - sizePx).coerceAtLeast(0).toFloat())
                    view.y = ny.coerceIn(0f, (preview.height - sizePx).coerceAtLeast(0).toFloat())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 中心点 → 相对比例（与 placeSticker 的换算互逆）
                    val cx = (view.x + sizePx / 2f) / preview.width.coerceAtLeast(1)
                    val cy = (view.y + sizePx / 2f) / preview.height.coerceAtLeast(1)
                    StickerPref.moveTo(this, index, cx, cy)
                    true
                }
                else -> false
            }
        }
        // 长按删除：双击容易误触，这里用长按 800ms 更稳
        v.setOnLongClickListener {
            StickerPref.removeAt(this, index)
            render()
            Toast.makeText(this, R.string.action_remove_sticker, Toast.LENGTH_SHORT).show()
            true
        }
        // 长按与拖动共存：OnTouchListener 已接管，长按需手动触发 —— 用 OnLongClickListener
        // 配合返回 true，Android 会在未移动时正常派发长按。
    }

    private fun pickImage(req: Int) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { startActivityForResult(intent, req) }.onFailure {
            Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_BG -> {
                if (!CustomVisualPref.importExpandedBg(this, uri)) {
                    Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
                    return
                }
                render()
            }
            REQ_STICKER -> {
                if (StickerPref.add(this, uri) == null) {
                    Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
                    return
                }
                render()
            }
        }
    }

    private companion object {
        const val REQ_BG = 4313
        const val REQ_STICKER = 4314
    }
}

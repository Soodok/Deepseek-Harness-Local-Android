package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * 设置页背景图（v1.5.x）：选图、不透明度、模糊强度。
 *
 * 三项都实时反映在上方预览里；改动写偏好后，[ThemedActivity.applySettingsBackground]
 * 会在每个设置页的 onResume 套用，因此返回任意设置页立即看到效果。
 */
class SettingsBgActivity : ThemedActivity() {

    /** 属于设置体系：本页自身也套用背景图。 */
    override val usesSettingsBackground: Boolean get() = true

    private lateinit var imgPreview: ImageView
    private lateinit var txtHint: TextView
    private lateinit var seekAlpha: SeekBar
    private lateinit var seekBlur: SeekBar
    private lateinit var valAlpha: TextView
    private lateinit var valBlur: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_settings_bg)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        imgPreview = findViewById(R.id.imgBgPreview)
        txtHint = findViewById(R.id.txtPreviewHint)
        seekAlpha = findViewById(R.id.seekBgAlpha)
        seekBlur = findViewById(R.id.seekBgBlur)
        valAlpha = findViewById(R.id.valBgAlpha)
        valBlur = findViewById(R.id.valBgBlur)

        seekAlpha.progress = CustomVisualPref.settingsBgAlpha(this)
        seekBlur.progress = CustomVisualPref.settingsBgBlur(this)

        seekAlpha.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun changed(v: Int) {
                CustomVisualPref.setSettingsBgAlpha(this@SettingsBgActivity, v)
                refreshPreview()
                // 让本页背景也立刻跟着变（不同步就要退出再进才看到）
                applySettingsBackground()
            }
        })
        seekBlur.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun changed(v: Int) {
                CustomVisualPref.setSettingsBgBlur(this@SettingsBgActivity, v)
                refreshPreview()
                applySettingsBackground()
            }
        })

        findViewById<View>(R.id.btnPickBg).setOnClickListener { pickImage() }
        findViewById<View>(R.id.btnRemoveBg).setOnClickListener {
            CustomVisualPref.clearSettingsBg(this)
            applySettingsBackground()
            refreshPreview()
        }
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
        refreshPreview()
    }

    /**
     * 预览：与实机相同的一套处理（同一张盘上文件 + 同样的模糊与不透明度），
     * 做到「预览什么样，设置页就什么样」。
     */
    private fun refreshPreview() {
        val alpha = CustomVisualPref.settingsBgAlpha(this)
        val blur = CustomVisualPref.settingsBgBlur(this)
        valAlpha.text = getString(R.string.setting_bg_alpha_value, alpha)
        valBlur.text = getString(R.string.setting_bg_blur_value, blur)
        seekAlpha.progress = alpha
        seekBlur.progress = blur

        val file = CustomVisualPref.settingsBgFile(this)
        // 预览用更小的输入尺寸：拖滑块时每档都要重算，1080 宽会掉帧
        val bmp: Bitmap? = file?.let { ImageFx.cachedBlur(this, it, blur, ImageFx.PREVIEW_EDGE) }
        if (bmp == null) {
            imgPreview.setImageDrawable(null)
            imgPreview.visibility = View.GONE
            txtHint.setText(R.string.setting_image_none)
            return
        }
        imgPreview.setImageDrawable(
            BitmapDrawable(resources, bmp).apply {
                this.alpha = ImageFx.alphaOf(alpha)
            },
        )
        imgPreview.visibility = View.VISIBLE
        txtHint.setText(R.string.setting_image_set)
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { startActivityForResult(intent, REQ_PICK) }.onFailure {
            Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uri = data?.data ?: return
        if (!CustomVisualPref.importSettingsBg(this, uri)) {
            Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
            return
        }
        applySettingsBackground()
        refreshPreview()
    }

    /** 只关心进度的 SeekBar 回调（start/stop 用不上）。 */
    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        abstract fun changed(value: Int)

        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) changed(progress)
        }

        override fun onStartTrackingTouch(sb: SeekBar?) = Unit
        override fun onStopTrackingTouch(sb: SeekBar?) = Unit
    }

    private companion object {
        const val REQ_PICK = 4312
    }
}

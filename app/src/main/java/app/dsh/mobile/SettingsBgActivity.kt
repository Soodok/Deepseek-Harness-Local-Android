package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.slider.Slider
import android.widget.TextView
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
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
    private lateinit var seekAlpha: Slider
    private lateinit var seekBlur: Slider
    private lateinit var seekControlAlpha: Slider
    private val blurButtons = mutableListOf<Pair<android.widget.TextView, Int>>()
    private lateinit var valAlpha: TextView
    private lateinit var valControlAlpha: TextView
    private lateinit var editBlur: EditText

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_settings_bg)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        imgPreview = findViewById(R.id.imgBgPreview)
        txtHint = findViewById(R.id.txtBgPickHint)
        seekAlpha = findViewById(R.id.seekBgAlpha)
        seekBlur = findViewById(R.id.seekBgBlur)
        seekControlAlpha = findViewById(R.id.seekControlAlpha)
        listOf(seekAlpha, seekBlur, seekControlAlpha).forEach {
            it.valueFrom = 0f
            it.valueTo = 75f
            it.stepSize = 1f
        }
        valAlpha = findViewById(R.id.valBgAlpha)
        valControlAlpha = findViewById(R.id.valControlAlpha)
        editBlur = findViewById(R.id.editBgBlur)
        editBlur.setText(CustomVisualPref.settingsBgBlur(this).toString())
        editBlur.setOnEditorActionListener { _, _, _ ->
            applyBlurInput(); true
        }
        editBlur.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyBlurInput() }

        seekAlpha.value = CustomVisualPref.settingsBgAlpha(this).toFloat()
        seekBlur.value = CustomVisualPref.settingsBgBlur(this).toFloat()
        seekControlAlpha.value = CustomVisualPref.settingsBgControlAlpha(this).toFloat()

        // 精细滑块：与预设按钮联动
        seekBlur.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val v = value.toInt()
                CustomVisualPref.setSettingsBgBlur(this@SettingsBgActivity, v)
                editBlur.setText(v.toString())
                refreshPreview()
                applySettingsBackground()
            }
        }

        // 四档模糊预设：文字在代码里填，点击即写偏好并重绘
        blurButtons.clear()
        listOf(
            R.id.btnBlur0 to 0,
            R.id.btnBlur25 to 25,
            R.id.btnBlur50 to 50,
            R.id.btnBlur75 to 75,
        ).forEach { (id, pct) ->
            val btn = findViewById<com.google.android.material.button.MaterialButton>(id)
            btn.text = getString(R.string.setting_bg_alpha_value, pct)
            btn.setOnClickListener {
                CustomVisualPref.setSettingsBgBlur(this, pct)
                seekBlur.value = pct.toFloat()
                editBlur.setText(pct.toString())
                refreshPreview()
                applySettingsBackground()
            }
            blurButtons.add(btn to pct)
        }

        seekAlpha.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val v = value.toInt()
                CustomVisualPref.setSettingsBgAlpha(this@SettingsBgActivity, v)
                refreshPreview()
                // 让本页背景也立刻跟着变（不同步就要退出再进才看到）
                applySettingsBackground()
            }
        }
        seekControlAlpha.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val v = value.toInt()
                CustomVisualPref.setSettingsBgControlAlpha(this@SettingsBgActivity, v)
                valControlAlpha.text = getString(R.string.setting_bg_alpha_value, v)
                applySettingsBackground()
            }
        }

        // 四个折叠卡片的点击展开/收起
        SECTION_IDS.forEachIndexed { idx, secId ->
            findViewById<View>(secId)?.setOnClickListener {
                val nowOpen = idx !in CustomVisualPref.settingsBgSections(this)
                CustomVisualPref.setSettingsBgSection(this, idx, nowOpen)
                val bodyId = BODY_IDS[idx]
                val body = findViewById<LinearLayout>(bodyId) ?: return@setOnClickListener
                if (nowOpen) {
                    body.visibility = View.VISIBLE
                    body.measure(
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    )
                    val lp = body.layoutParams as? LinearLayout.LayoutParams
                        ?: LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    lp.height = 0
                    body.layoutParams = lp
                    animateHeight(body, lp, 0, body.measuredHeight)
                } else {
                    // 用安全转换：卡片容器可能是 MaterialCardView，其子 View 的
                    // layoutParams 不保证是 LinearLayout.LayoutParams，强转会崩。
                    val lp = body.layoutParams as? LinearLayout.LayoutParams
                        ?: LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    animateHeight(body, lp, body.height, 0)
                }
                val arrowId = ARROW_IDS[idx]
                findViewById<ImageView>(arrowId)?.setImageResource(
                    if (nowOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                )
            }
        }

        // 设置页布局：点击弹出选择
        findViewById<View>(R.id.secLayoutMode)?.setOnClickListener { showLayoutModeDialog() }

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

        // 套用四个折叠卡片的展开状态（从偏好恢复）
        val openSet = CustomVisualPref.settingsBgSections(this)
        SECTION_IDS.forEachIndexed { idx, secId ->
            val bodyId = BODY_IDS[idx]
            val body = findViewById<LinearLayout>(bodyId) ?: return@forEachIndexed
            val open = idx in openSet
            body.visibility = if (open) View.VISIBLE else View.GONE
            val arrowId = ARROW_IDS[idx]
            findViewById<ImageView>(arrowId)?.setImageResource(
                if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
            )
        }
        // 设置页布局的当前值
        findViewById<TextView>(R.id.valLayoutMode)?.text =
            getString(SettingsLayoutMode.current(this).labelRes)
    }

    private fun applyBlurInput() {
        val value = editBlur.text.toString().toIntOrNull()?.coerceIn(0, 75) ?: 0
        CustomVisualPref.setSettingsBgBlur(this, value)
        seekBlur.value = value.toFloat()
        editBlur.setText(value.toString())
        refreshPreview()
        applySettingsBackground()
    }

    /** 高度动画：0 → 目标，收起则目标 → 0。 */
    private fun animateHeight(
        view: LinearLayout, lp: LinearLayout.LayoutParams, from: Int, to: Int,
    ) {
        val anim = android.animation.ValueAnimator.ofInt(from, to)
        anim.duration = 220
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener { a ->
            lp.height = a.animatedValue as Int
            view.layoutParams = lp
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                view.layoutParams = lp
                if (to == 0) view.visibility = View.GONE
            }
        })
        anim.start()
    }

    private fun showLayoutModeDialog() {
        val modes = SettingsLayoutMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(SettingsLayoutMode.current(this))
        md3Dialog()
            .setTitle(R.string.setting_layout_mode)
            .setSingleChoiceItems(labels, checked) { d, which ->
                SettingsLayoutMode.set(this, modes[which])
                d.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 预览：与实机相同的一套处理（同一张盘上文件 + 同样的模糊与不透明度），
     * 做到「预览什么样，设置页就什么样」。
     */
    private fun refreshPreview() {
        val alpha = CustomVisualPref.settingsBgAlpha(this)
        val blur = CustomVisualPref.settingsBgBlur(this)
        valAlpha.text = getString(R.string.setting_bg_alpha_value, alpha)
        valControlAlpha.text =
            getString(R.string.setting_bg_alpha_value, CustomVisualPref.settingsBgControlAlpha(this))
        // 当前档位实心高亮，其余描边
        // 滑块可以停在任意值，此时高亮「最近的一档」，而不是一个都不亮
        val nearest = CustomVisualPref.BG_BLUR_PRESETS.minByOrNull { kotlin.math.abs(it - blur) }
        blurButtons.forEach { (btn, pct) ->
            val active = nearest == pct
            // MaterialButton 的背景由它自己管（MaterialButtonBackground）。
            // 调 setBackgroundResource 会让它内部的 drawable 类型不符而崩溃，
            // 改用 MaterialButton 的官方 API 表达「选中 / 未选中」。
            if (btn is com.google.android.material.button.MaterialButton) {
                btn.setBackgroundColor(
                    getColor(if (active) R.color.accent else android.R.color.transparent),
                )
                btn.setTextColor(
                    getColor(if (active) R.color.on_accent else R.color.text_primary),
                )
            } else {
                btn.setBackgroundResource(
                    if (active) R.drawable.bg_btn_accent_ripple else R.drawable.bg_btn_outline_ripple,
                )
                btn.setTextColor(getColor(if (active) R.color.on_accent else R.color.text_primary))
            }
        }
        seekAlpha.value = alpha.toFloat()
        seekBlur.value = blur.toFloat()
        seekControlAlpha.value = CustomVisualPref.settingsBgControlAlpha(this).toFloat()

        val file = CustomVisualPref.settingsBgFile(this)
        // 预览用更小的输入尺寸：拖滑块时每档都要重算，1080 宽会掉帧
        val bmp: Bitmap? = file?.let {
            ImageFx.cachedBlur(
                this, it,
                CustomVisualPref.blurStrengthOf(blur),
                ImageFx.PREVIEW_EDGE,
            )
        }
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

    /** MD3 Slider 监听在各控件绑定处直接处理值变化。 */


    private companion object {
        /** 四个折叠卡片的容器 id（顺序 = 展开状态的下标） */
        val SECTION_IDS = intArrayOf(
            R.id.secBgPick, R.id.secBgAlpha, R.id.secBgBlur, R.id.secControlAlpha,
        )
        /** 对应内容区 id */
        val BODY_IDS = intArrayOf(
            R.id.bgPickBody, R.id.bgAlphaBody, R.id.bgBlurBody, R.id.controlAlphaBody,
        )
        /** 对应箭头 id */
        val ARROW_IDS = intArrayOf(
            R.id.imgBgPickArrow, R.id.imgBgAlphaArrow, R.id.imgBgBlurArrow, R.id.imgControlAlphaArrow,
        )

        const val REQ_PICK = 4312
    }
}

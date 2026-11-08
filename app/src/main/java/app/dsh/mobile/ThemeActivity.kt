package app.dsh.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText

/**
 * 主题与配色（v1.5.x）：独立管理页，替代原来外观页里的「配色方案」弹窗。
 *
 * 三种取色来源，互斥：
 *  · 预设   —— Chip 单选里的五套固定配色
 *  · 自定义 —— 色相/饱和度/明度三个 MD3 Slider + 十六进制输入，随拖随预览
 *  · 动态   —— Android 12+ 跟随系统壁纸取色（MaterialSwitch 打开）
 *
 * 改动统一走「写偏好 → recreate()」，让 ThemeManager.wrap 重新选 values(-night)，
 * 避免局部刷新残留旧颜色。
 */
class ThemeActivity : ThemedActivity() {

    override val usesSettingsBackground: Boolean get() = true

    private lateinit var swDynamic: MaterialSwitch
    private lateinit var subDynamic: TextView
    private lateinit var chipGroup: ChipGroup
    private lateinit var txtCurrent: TextView
    private lateinit var layoutHex: com.google.android.material.textfield.TextInputLayout
    private lateinit var editHex: TextInputEditText
    private lateinit var seekHue: Slider
    private lateinit var seekSat: Slider
    private lateinit var seekVal: Slider
    private lateinit var btnApply: MaterialButton

    /** 滑块回调里会写回 hex，加个锁避免互相触发。 */
    private var syncing = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_theme)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        swDynamic = findViewById(R.id.swDynamic)
        subDynamic = findViewById(R.id.subDynamic)
        chipGroup = findViewById(R.id.chipPresets)
        txtCurrent = findViewById(R.id.txtCurrentAccent)
        layoutHex = findViewById(R.id.layoutHex)
        editHex = findViewById(R.id.editHex)
        seekHue = findViewById(R.id.seekHue)
        seekSat = findViewById(R.id.seekSat)
        seekVal = findViewById(R.id.seekVal)
        btnApply = findViewById(R.id.btnApplyCustom)

        setupDynamic()
        setupChips()
        setupCustom()
        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
        refreshSummary()
    }

    // ---------------------------------------------------------------- 动态取色

    private fun setupDynamic() {
        if (!ThemeAccent.dynamicSupported) {
            swDynamic.isEnabled = false
            swDynamic.isChecked = false
            subDynamic.setText(R.string.theme_dynamic_unsupported)
            subDynamic.alpha = 0.6f
            return
        }
        val mode = ThemeAccent.mode(this)
        swDynamic.isChecked = mode == ThemeAccent.Mode.DYNAMIC
        swDynamic.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            // 三种来源互斥：打开动态色 = 退出预设/自定义
            val target = if (checked) ThemeAccent.Mode.DYNAMIC else ThemeAccent.Mode.PRESET
            // 空转保护：模式没变就不重建。没有这道闸，开关状态与重建会互相触发，
            // 表现为点一下动态取色就疯狂闪烁。
            if (ThemeAccent.mode(this) == target) return@setOnCheckedChangeListener
            ThemeAccent.setMode(this, target)
            recreate()
        }
    }

    // ---------------------------------------------------------------- 预设配色

    private fun setupChips() {
        val ids = listOf(
            R.id.chipSky to Accent.SKY,
            R.id.chipIndigo to Accent.INDIGO,
            R.id.chipTeal to Accent.TEAL,
            R.id.chipViolet to Accent.VIOLET,
            R.id.chipAmber to Accent.AMBER,
        )
        val preset = Accent.currentPreset(this)
        val mode = ThemeAccent.mode(this)
        // 动态取色启用时禁用预设选择，三个来源只允许一个生效。
        chipGroup.isEnabled = mode != ThemeAccent.Mode.DYNAMIC
        for (i in 0 until chipGroup.childCount) {
            chipGroup.getChildAt(i).isEnabled = mode != ThemeAccent.Mode.DYNAMIC
        }
        syncing = true
        ids.forEach { (id, accent) ->
            val chip = findViewById<Chip>(id)
            // 用 chip 底色直接表达预设色；文字按亮度取黑/白，避免浅底白字看不清。
            val color = accent.argb
            chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(color)
            chip.setTextColor(contrastOn(color))
            // 互斥：动态色或自定义色生效时，预设 chip 一律不选中
            chip.isChecked = mode == ThemeAccent.Mode.PRESET && accent == preset
        }
        syncing = false

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (syncing || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val picked = ids.firstOrNull { it.first == checkedIds.first() }?.second ?: return@setOnCheckedStateChangeListener
            if (Accent.currentPreset(this) == picked &&
                ThemeAccent.mode(this) == ThemeAccent.Mode.PRESET
            ) {
                return@setOnCheckedStateChangeListener
            }
            Accent.set(this, picked) // 同时关闭动态取色并切换为 PRESET
            recreate()
        }
    }

    // ---------------------------------------------------------------- 自定义颜色

    private fun setupCustom() {
        val current = ThemeAccent.customColor(this)
        val hsv = FloatArray(3)
        Color.colorToHSV(current, hsv)

        seekHue.valueFrom = 0f
        seekHue.valueTo = 360f
        seekHue.stepSize = 1f
        seekHue.value = snap(hsv[0], 0f, 360f)

        seekSat.valueFrom = 0f
        seekSat.valueTo = 100f
        seekSat.stepSize = 1f
        seekSat.value = snap(hsv[1] * 100f, 0f, 100f)

        seekVal.valueFrom = 0f
        seekVal.valueTo = 100f
        seekVal.stepSize = 1f
        seekVal.value = snap(hsv[2] * 100f, 0f, 100f)

        editHex.setText(String.format("#%06X", current and 0xFFFFFF))

        val onSlider = { _: Slider, _: Float, fromUser: Boolean ->
            if (fromUser) syncFromSliders()
        }
        seekHue.addOnChangeListener(onSlider)
        seekSat.addOnChangeListener(onSlider)
        seekVal.addOnChangeListener(onSlider)

        editHex.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyHexInput(refreshOnly = true)
        }

        btnApply.setOnClickListener {
            applyHexInput(refreshOnly = false)
        }

        // 互斥提示：当前不是自定义模式时，说明点了会切换过去
        if (ThemeAccent.mode(this) != ThemeAccent.Mode.CUSTOM) {
            layoutHex.helperText = getString(R.string.theme_custom_inactive)
        } else {
            layoutHex.helperText = null
        }
    }

    private fun currentSliderColor(): Int = Color.HSVToColor(
        floatArrayOf(
            seekHue.value,
            (seekSat.value / 100f).coerceIn(0f, 1f),
            (seekVal.value / 100f).coerceIn(0f, 1f),
        ),
    )

    private fun syncFromSliders() {
        if (syncing) return
        syncing = true
        val color = currentSliderColor()
        editHex.setText(String.format("#%06X", color and 0xFFFFFF))
        paintHexBox(color)
        syncing = false
    }

    /** 十六进制输入：合法就采纳，非法就回落当前滑块颜色。 */
    private fun applyHexInput(refreshOnly: Boolean) {
        if (syncing) return
        val raw = editHex.text?.toString()?.trim().orEmpty()
        val parsed = raw.removePrefix("#").toIntOrNull(16)?.takeIf { it in 0..0xFFFFFF }
        if (parsed != null) {
            val color = parsed or 0xFF000000.toInt()
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            syncing = true
            seekHue.value = snap(hsv[0], 0f, 360f)
            seekSat.value = snap(hsv[1] * 100f, 0f, 100f)
            seekVal.value = snap(hsv[2] * 100f, 0f, 100f)
            syncing = false
            paintHexBox(color)
            if (!refreshOnly) commitCustom(color)
        } else if (refreshOnly) {
            syncing = true
            editHex.setText(String.format("#%06X", currentSliderColor() and 0xFFFFFF))
            syncing = false
        } else {
            Toast.makeText(this, getString(R.string.theme_custom_invalid), Toast.LENGTH_SHORT).show()
        }
    }

    private fun commitCustom(color: Int) {
        val same = ThemeAccent.mode(this) == ThemeAccent.Mode.CUSTOM &&
            ThemeAccent.customColor(this) == color
        ThemeAccent.setCustomColor(this, color)
        ThemeAccent.setMode(this, ThemeAccent.Mode.CUSTOM)
        // 同色重复点「使用这个颜色」不重建，避免无谓闪一下
        if (!same) recreate()
    }

    /** 用输入框当前色给 OutlinedBox 描边，给一点即时反馈。 */
    private fun paintHexBox(color: Int) {
        runCatching {
            layoutHex.boxStrokeColor = color
            layoutHex.setBoxStrokeColorStateList(
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(color, color),
                ),
            )
        }
    }

    /**
     * Material Slider 要求赋值必须落在 `valueFrom + n × stepSize` 的格点上，
     * 否则直接抛 IllegalStateException。HSV 换算是浮点结果（例如 204.53），
     * 必须先吸附到整数格点再赋值，否则一进页面就崩。
     */
    private fun snap(value: Float, from: Float, to: Float): Float =
        Math.round(value).toFloat().coerceIn(from, to)

    /** 背景色上该用黑字还是白字：按相对亮度取对比更强的一方。 */
    private fun contrastOn(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luminance > 0.6) Color.BLACK else Color.WHITE
    }

    // ---------------------------------------------------------------- 状态摘要

    private fun refreshSummary() {
        val accent = Accent.current(this)
        txtCurrent.text = getString(
            R.string.theme_current_fmt,
            getString(accent.labelRes),
        )
        if (ThemeAccent.mode(this) == ThemeAccent.Mode.DYNAMIC) {
            txtCurrent.text = getString(R.string.theme_current_dynamic)
        }
    }
}

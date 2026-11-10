package app.dsh.mobile

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 主题设置（v1.6.x）：原生 Material 3 单选列表。
 *
 * 颜色来源是**互斥单选**，用 MaterialRadioButton 表达当前生效项：
 *   跟随壁纸（Monet） / 五个预设 / 自定义
 * 自定义选中后才展开输入框与三条滑杆，避免「同时出现多个看似生效的控件」。
 *
 * 具体如何把颜色注入主题由 [ThemeEngine] 负责；本页只负责读写偏好。
 */
class ThemeActivity : ThemedActivity() {

    override val usesSettingsBackground: Boolean get() = true

    private lateinit var cardCustom: View
    private lateinit var layoutHex: TextInputLayout
    private lateinit var editHex: TextInputEditText
    private lateinit var seekHue: Slider
    private lateinit var seekSat: Slider
    private lateinit var seekVal: Slider
    private lateinit var btnApply: MaterialButton
    private lateinit var subDynamic: TextView

    /** 单选组：来源 id → 单选按钮 id */
    private val radioOf = linkedMapOf<String, Int>()

    /** 滑块与 hex 互写时防止互相触发 */
    private var syncing = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_theme)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        subDynamic = findViewById(R.id.subDynamic)
        cardCustom = findViewById(R.id.cardCustom)
        layoutHex = findViewById(R.id.layoutHex)
        editHex = findViewById(R.id.editHex)
        seekHue = findViewById(R.id.seekHue)
        seekSat = findViewById(R.id.seekSat)
        seekVal = findViewById(R.id.seekVal)
        btnApply = findViewById(R.id.btnApplyCustom)

        bindSourceRows()
        bindCustom()
        refreshSelection()
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
        refreshSelection()
    }

    // ------------------------------------------------------------ 颜色来源

    /** 每行整行可点，点击后写入模式并重建页面让主题重新注入。 */
    private fun bindSourceRows() {
        val presets = listOf(
            "sky" to (R.id.rowSky to R.id.rbSky),
            "indigo" to (R.id.rowIndigo to R.id.rbIndigo),
            "teal" to (R.id.rowTeal to R.id.rbTeal),
            "violet" to (R.id.rowViolet to R.id.rbViolet),
            "amber" to (R.id.rowAmber to R.id.rbAmber),
        )

        // 跟随壁纸
        radioOf["dynamic"] = R.id.rbDynamic
        findViewById<View>(R.id.rowDynamic).setOnClickListener {
            if (!ThemeEngine.dynamicSupported) {
                Toast.makeText(this, R.string.theme_dynamic_unsupported, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pick(ThemeAccent.Mode.DYNAMIC)
        }

        for ((id, ids) in presets) {
            val (rowId, rbId) = ids
            radioOf[id] = rbId
            findViewById<View>(rowId).setOnClickListener {
                val accent = Accent.entries.first { it.id == id }
                if (Accent.currentPreset(this) == accent &&
                    ThemeAccent.mode(this) == ThemeAccent.Mode.PRESET
                ) {
                    return@setOnClickListener
                }
                Accent.set(this, accent)   // 内部同时把模式置回 PRESET
                recreate()
            }
        }

        // 自定义
        radioOf["custom"] = R.id.rbCustom
        findViewById<View>(R.id.rowCustom).setOnClickListener {
            if (ThemeAccent.mode(this) == ThemeAccent.Mode.CUSTOM) {
                cardCustom.visibility = View.VISIBLE
                return@setOnClickListener
            }
            ThemeAccent.setMode(this, ThemeAccent.Mode.CUSTOM)
            recreate()
        }
    }

    private fun pick(mode: ThemeAccent.Mode) {
        if (ThemeAccent.mode(this) == mode) return
        ThemeAccent.setMode(this, mode)
        recreate()
    }

    /** 按当前偏好刷新单选状态与自定义区可见性。 */
    private fun refreshSelection() {
        val mode = ThemeAccent.mode(this)
        val checked = when (mode) {
            ThemeAccent.Mode.DYNAMIC -> "dynamic"
            ThemeAccent.Mode.CUSTOM -> "custom"
            ThemeAccent.Mode.PRESET -> Accent.currentPreset(this).id
        }
        syncing = true
        radioOf.forEach { (key, rbId) ->
            findViewById<MaterialRadioButton>(rbId).isChecked = key == checked
        }
        syncing = false

        cardCustom.visibility = if (mode == ThemeAccent.Mode.CUSTOM) View.VISIBLE else View.GONE

        if (!ThemeEngine.dynamicSupported) {
            subDynamic.setText(R.string.theme_dynamic_unsupported)
            subDynamic.alpha = 0.6f
            findViewById<View>(R.id.rowDynamic).alpha = 0.6f
        }
    }

    // ------------------------------------------------------------ 自定义颜色

    private fun bindCustom() {
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

        val onChange = { _: Slider, _: Float, fromUser: Boolean ->
            if (fromUser) syncFromSliders()
        }
        seekHue.addOnChangeListener(onChange)
        seekSat.addOnChangeListener(onChange)
        seekVal.addOnChangeListener(onChange)

        editHex.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyHexInput(refreshOnly = true)
        }
        btnApply.setOnClickListener { applyHexInput(refreshOnly = false) }
    }

    /**
     * Material Slider 要求赋值落在 `valueFrom + n × stepSize` 格点上，
     * HSV 换算是浮点结果（如 204.53），必须先吸附到整数格点。
     */
    private fun snap(value: Float, from: Float, to: Float): Float =
        Math.round(value).toFloat().coerceIn(from, to)

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
        editHex.setText(String.format("#%06X", currentSliderColor() and 0xFFFFFF))
        syncing = false
    }

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
            if (!refreshOnly) commitCustom(color)
        } else if (refreshOnly) {
            syncing = true
            editHex.setText(String.format("#%06X", currentSliderColor() and 0xFFFFFF))
            syncing = false
        } else {
            Toast.makeText(this, R.string.theme_custom_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    private fun commitCustom(color: Int) {
        val same = ThemeAccent.mode(this) == ThemeAccent.Mode.CUSTOM &&
            ThemeAccent.customColor(this) == color
        ThemeAccent.setCustomColor(this, color)
        ThemeAccent.setMode(this, ThemeAccent.Mode.CUSTOM)
        if (!same) recreate()
    }
}

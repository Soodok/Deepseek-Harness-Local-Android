package app.dsh.mobile

import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TextAppearanceSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.R as MaterialR

/**
 * 主题设置（v1.6.x）。
 *
 * 严格按 Google 官方 Material 3 用法，见 docs/md3/README.md：
 *   1. 外观模式：MaterialRadioButton 单选，写 [ThemePref]
 *   2. 配色方案：系统动态取色或预设色板，写 [ThemeAccent]
 */
class ThemeActivity : ThemedActivity() {

    override val usesSettingsBackground: Boolean get() = true

    private lateinit var paletteSelector: ThemePaletteSelector

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_theme)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        paletteSelector = ThemePaletteSelector(
            this, ThemeAccent.selected(this), ThemeAccent.dynamicSupported,
        ) { id ->
            if (id != ThemeAccent.selected(this)) {
                ThemeAccent.setSelected(this, id)
                recreate()
            }
        }
        findViewById<LinearLayout>(R.id.paletteContainer).addView(paletteSelector)
        bindModeRows()
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
        refresh()
    }

    // ---------------------------------------------------------------- 外观模式

    private val ThemeMode.radioId: Int
        get() = when (this) {
            ThemeMode.LIGHT -> R.id.rbModeLight
            ThemeMode.DARK -> R.id.rbModeDark
            ThemeMode.SYSTEM -> R.id.rbModeSystem
        }

    private fun bindModeRows() {
        val options = listOf(
            ThemeMode.LIGHT to R.string.theme_mode_light_sub,
            ThemeMode.DARK to R.string.theme_mode_dark_sub,
            ThemeMode.SYSTEM to R.string.theme_mode_system_sub,
        )
        val inset = resources.getDimensionPixelSize(R.dimen.md3_item_padding_h)
        val rowHeight = resources.getDimensionPixelSize(R.dimen.md3_item_two_line)
        for ((mode, subtitleRes) in options) {
            val radio = findViewById<MaterialRadioButton>(mode.radioId)
            // One native label gives the radio both lines without a second accessibility stop.
            val label = SpannableStringBuilder(getText(mode.labelRes)).append('\n')
            val subtitleStart = label.length
            label.append(getText(subtitleRes))
            label.setSpan(
                TextAppearanceSpan(radio.context, MaterialR.style.TextAppearance_Material3_BodyMedium),
                subtitleStart, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            label.setSpan(
                ForegroundColorSpan(MaterialColors.getColor(radio, MaterialR.attr.colorOnSurfaceVariant)),
                subtitleStart, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            radio.text = label

            // CompoundButton draws its indicator at the view edge, outside text padding.
            // Inset the original drawable without shrinking the row's click/focus bounds.
            radio.buttonDrawable?.let { drawable ->
                val rtl = radio.layoutDirection == View.LAYOUT_DIRECTION_RTL
                // Detach first so CompoundButton does not clear the wrapper's child callback.
                radio.buttonDrawable = null
                radio.buttonDrawable = InsetDrawable(
                    drawable, if (rtl) 0 else inset, 0, if (rtl) inset else 0, 0,
                )
                radio.minHeight = rowHeight
            }
            radio.setOnClickListener {
                if (ThemePref.get(this) == mode) return@setOnClickListener
                ThemePref.set(this, mode)
                recreate()
            }
        }
        refreshModeSelection()
    }

    private fun refreshModeSelection() {
        findViewById<RadioGroup>(R.id.appearanceModeGroup).check(ThemePref.get(this).radioId)
    }

    private fun refresh() {
        refreshModeSelection()
        paletteSelector.setSelectedId(ThemeAccent.selected(this))
    }
}

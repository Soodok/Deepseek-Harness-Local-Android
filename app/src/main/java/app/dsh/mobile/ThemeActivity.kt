package app.dsh.mobile

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.radiobutton.MaterialRadioButton

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

        setupStyle()
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

    private fun setupStyle() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.groupStyle)
        val sub = findViewById<TextView>(R.id.subStyle)
        fun describe(style: StylePref.Style) = getString(
            if (style == StylePref.Style.MATERIAL) R.string.style_sub_material else R.string.style_sub_classic,
        )
        val current = StylePref.style(this)
        group.check(if (current == StylePref.Style.MATERIAL) R.id.btnStyleMaterial else R.id.btnStyleClassic)
        sub.text = describe(current)
        group.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                val wanted = if (checkedId == R.id.btnStyleMaterial) StylePref.Style.MATERIAL else StylePref.Style.CLASSIC
                if (wanted != StylePref.style(this)) {
                    StylePref.setStyle(this, wanted)
                    recreate()
                }
            }
        }
    }

    // ---------------------------------------------------------------- 外观模式

    private fun bindModeRows() {
        val rows = listOf(
            Triple(R.id.rowModeLight, R.id.rbModeLight, ThemeMode.LIGHT),
            Triple(R.id.rowModeDark, R.id.rbModeDark, ThemeMode.DARK),
            Triple(R.id.rowModeSystem, R.id.rbModeSystem, ThemeMode.SYSTEM),
        )
        for ((rowId, rbId, mode) in rows) {
            findViewById<View>(rowId).setOnClickListener {
                if (ThemePref.get(this) == mode) return@setOnClickListener
                ThemePref.set(this, mode)
                recreate()
            }
            findViewById<MaterialRadioButton>(rbId).isChecked = ThemePref.get(this) == mode
        }
    }

    private fun refresh() {
        findViewById<MaterialRadioButton>(R.id.rbModeLight).isChecked = ThemePref.get(this) == ThemeMode.LIGHT
        findViewById<MaterialRadioButton>(R.id.rbModeDark).isChecked = ThemePref.get(this) == ThemeMode.DARK
        findViewById<MaterialRadioButton>(R.id.rbModeSystem).isChecked = ThemePref.get(this) == ThemeMode.SYSTEM
        paletteSelector.setSelectedId(ThemeAccent.selected(this))
    }
}

package app.dsh.mobile

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * 主题设置（v1.6.x）。
 *
 * 严格按 Google 官方 Material 3 用法，见 docs/md3/README.md：
 *   1. 外观模式：MaterialRadioButton 单选，写 [ThemePref]
 *   2. 动态取色：MaterialSwitch，写 [ThemeAccent]，由 Application 里的
 *      DynamicColors 官方 API 在 onActivityPreCreated 生效
 *
 * 本页不生成任何色板、不注入主题属性 —— 那是系统动态取色的职责。
 */
class ThemeActivity : ThemedActivity() {

    override val usesSettingsBackground: Boolean get() = true

    private lateinit var swDynamic: MaterialSwitch
    private lateinit var subDynamic: TextView

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

        bindModeRows()
        bindDynamic()
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
        refresh()
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

    // ---------------------------------------------------------------- 动态取色

    private fun bindDynamic() {
        if (!ThemeAccent.dynamicSupported) {
            swDynamic.isEnabled = false
            swDynamic.isChecked = false
            subDynamic.setText(R.string.theme_dynamic_unsupported)
            return
        }
        swDynamic.setOnCheckedChangeListener { _, checked ->
            if (ThemeAccent.dynamicEnabled(this) == checked) return@setOnCheckedChangeListener
            ThemeAccent.setDynamicEnabled(this, checked)
            recreate()
        }
    }

    private fun refresh() {
        // 单选状态跟随偏好；开关同理，且只在支持时才可操作
        findViewById<MaterialRadioButton>(R.id.rbModeLight).isChecked = ThemePref.get(this) == ThemeMode.LIGHT
        findViewById<MaterialRadioButton>(R.id.rbModeDark).isChecked = ThemePref.get(this) == ThemeMode.DARK
        findViewById<MaterialRadioButton>(R.id.rbModeSystem).isChecked = ThemePref.get(this) == ThemeMode.SYSTEM

        if (ThemeAccent.dynamicSupported) {
            val on = ThemeAccent.dynamicEnabled(this)
            swDynamic.setOnCheckedChangeListener(null)
            swDynamic.isChecked = on
            bindDynamic()
        }
    }
}

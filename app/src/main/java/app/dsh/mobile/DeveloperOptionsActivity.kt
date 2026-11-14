package app.dsh.mobile

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * This page only controls menu visibility. It does not grant capabilities or skip
 * CapabilityGate authorization; each privileged operation must validate access itself.
 */
class DeveloperOptionsActivity : ThemedActivity() {
    override val usesSettingsBackground: Boolean get() = true
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(LocaleHelper.wrap(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guard stale or explicit in-app launches after developer mode has been disabled.
        if (!DeveloperModePrefs.isEnabled(this)) {
            finish()
            return
        }
        OrientationPrefs.applyToSettingsPage(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.developer_options_intro); textSize = 14f
            setTextColor(0xff888888.toInt()); setPadding(dp(4), dp(8), dp(4), dp(20))
        })
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(18).toFloat(); cardElevation = 0f
            setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface, 0xff202124.toInt()))
            strokeWidth = dp(1); strokeColor = resolveColor(com.google.android.material.R.attr.colorOutline, 0xff555555.toInt())
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        labels.addView(TextView(this).apply { text = getString(R.string.developer_options_title); textSize = 16f; setTextColor(resolveColor(android.R.attr.textColorPrimary, -1)) })
        labels.addView(TextView(this).apply { text = getString(R.string.developer_options_enabled_sub); textSize = 12f; setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xffaaaaaa.toInt())); setPadding(0, dp(4), 0, 0) })
        val master = MaterialSwitch(this).apply { isChecked = true; contentDescription = getString(R.string.developer_options_title) }
        row.addView(labels); row.addView(master); card.addView(row); root.addView(card)
        root.addView(TextView(this).apply {
            text = getString(R.string.developer_options_security_note); textSize = 12f
            setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xffaaaaaa.toInt())); setPadding(dp(4), dp(16), dp(4), 0)
        })
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(20), dp(10)) }
        toolbar.addView(TextView(this).apply { text = "‹"; textSize = 32f; setTextColor(resolveColor(android.R.attr.textColorPrimary, -1)); setPadding(0, 0, dp(16), 0); setOnClickListener { finish() } })
        toolbar.addView(TextView(this).apply { text = getString(R.string.developer_options_title); textSize = 20f; setTextColor(resolveColor(android.R.attr.textColorPrimary, -1)) })
        page.addView(toolbar); page.addView(android.widget.ScrollView(this).apply { addView(root) }, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(page)
        master.setOnCheckedChangeListener { _, checked -> if (!checked) {
            DeveloperModePrefs.setEnabled(this, false)
            Toast.makeText(this, R.string.developer_options_disabled_toast, Toast.LENGTH_SHORT).show()
            finish()
        } }
    }
    override fun onResume() { super.onResume(); OrientationPrefs.applyToSettingsPage(this) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun resolveColor(attr: Int, fallback: Int): Int {
        val typed = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, typed, true)) {
            if (typed.resourceId != 0) runCatching { androidx.core.content.ContextCompat.getColor(this, typed.resourceId) }.getOrDefault(typed.data) else typed.data
        } else fallback
    }
}

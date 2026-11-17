package app.dsh.mobile

import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * This page only controls menu visibility. It does not grant capabilities or skip
 * CapabilityGate authorization; each privileged operation must validate access itself.
 */
class DeveloperOptionsActivity : ThemedActivity() {
    override val usesSettingsBackground: Boolean get() = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guard stale or explicit in-app launches after developer mode has been disabled.
        if (!DeveloperModePrefs.isEnabled(this)) {
            finish()
            return
        }
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_developer_options)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        val master = findViewById<MaterialSwitch>(R.id.swDeveloperMaster)
        master.isChecked = true
        master.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                DeveloperModePrefs.setEnabled(this, false)
                Toast.makeText(this, R.string.developer_options_disabled_toast, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
    }
}

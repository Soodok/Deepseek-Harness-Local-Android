package app.dsh.mobile

import android.content.Context

/** UI discovery flag only; privileged operations must enforce their own authorization. */
object DeveloperModePrefs {
    private const val PREFS = "developer_options"
    private const val KEY_ENABLED = "enabled"
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import java.util.Locale

/**
 * 应用内语言设置：首次使用系统语言，未支持的语言和旧值回退至英语。
 */
object LocaleHelper {
    private const val PREFS = "app_locale"
    private const val KEY = "locale"

    const val DEFAULT = "en"
    // v1.4.29（issue #46）：新增葡语（巴西）；v1.5.0-beta：新增泰语
    // v1.7.4：新增俄语（ru）
    val SUPPORTED = listOf("en", "de", "zh", "pt", "th", "ru")

    fun get(ctx: Context): String {
        val preferences = prefs(ctx)
        val v = if (preferences.contains(KEY)) {
            preferences.getString(KEY, DEFAULT) ?: DEFAULT
        } else {
            Resources.getSystem().configuration.locales[0]?.language ?: DEFAULT
        }
        return if (v in SUPPORTED) v else DEFAULT
    }

    fun set(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY, value).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 包装 Context：按设置覆盖 locale（三种之一，恒生效） */
    fun wrap(ctx: Context): Context {
        val locale = Locale(get(ctx))
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        return ctx.createConfigurationContext(config)
    }
}

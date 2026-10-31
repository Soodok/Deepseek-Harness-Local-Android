package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * 应用内语言设置（v1.2.120）：en / de / zh / pt-BR，默认 en。
 *
 * 历史：此前是 system（跟随系统）/ zh / en，默认 system。现按需求收敛为
 * 三种明确语言、英语为默认；旧值 system 或未知值一律归一化为 en。
 */
object LocaleHelper {
    private const val PREFS = "app_locale"
    private const val KEY = "locale"

    const val DEFAULT = "en"
    // v1.4.29（issue #46）：新增葡语（巴西）；v1.5.0-beta：新增泰语
    val SUPPORTED = listOf("en", "de", "zh", "pt", "th")

    fun get(ctx: Context): String {
        // getString() 返回平台类型 String!；先兜底再比较，避免可空推断
        val v = prefs(ctx).getString(KEY, DEFAULT) ?: DEFAULT
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

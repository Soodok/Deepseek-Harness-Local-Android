package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/** 应用内语言设置：system（跟随系统）/ zh / en */
object LocaleHelper {
    private const val PREFS = "app_locale"
    private const val KEY = "locale"

    fun get(ctx: Context): String =
        prefs(ctx).getString(KEY, "system") ?: "system"

    fun set(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY, value).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 包装 Context：按设置覆盖 locale（system 时原样返回，跟随系统） */
    fun wrap(ctx: Context): Context {
        val lang = get(ctx)
        if (lang == "system") return ctx
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        return ctx.createConfigurationContext(config)
    }
}

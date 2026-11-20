package app.dsh.mobile

import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.material.color.DynamicColors

/** Colour-source preference shared by onboarding and the theme settings page. */
object ThemeAccent {
    private const val PREFS = "dsh_ui_prefs"
    private const val KEY_PALETTE = "theme_palette"
    private const val KEY_DYNAMIC = "theme_dynamic_enabled"

    const val SYSTEM = "system"
    const val BLUE = "blue"
    const val TEAL = "teal"
    const val VIOLET = "violet"
    const val AMBER = "amber"
    val paletteIds = listOf(SYSTEM, BLUE, TEAL, VIOLET, AMBER)

    val dynamicSupported: Boolean
        get() = DynamicColors.isDynamicColorAvailable()

    /** Preserve the user's selected ID even on devices where dynamic colour is unavailable. */
    fun selected(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_PALETTE, null)
        if (stored in paletteIds) return stored!!
        val legacyDynamic = if (prefs.contains(KEY_DYNAMIC)) {
            prefs.getBoolean(KEY_DYNAMIC, false)
        } else {
            prefs.getString("theme_accent_mode", null) == "DYNAMIC"
        }
        val legacyPreset = when (prefs.getString("accent_scheme", null)) {
            "teal" -> TEAL
            "violet" -> VIOLET
            "amber" -> AMBER
            else -> BLUE
        }
        return (if (legacyDynamic) SYSTEM else legacyPreset).also {
            prefs.edit().putString(KEY_PALETTE, it).apply()
        }
    }

    fun setSelected(ctx: Context, id: String) {
        require(id in paletteIds)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PALETTE, id)
            .putBoolean(KEY_DYNAMIC, id == SYSTEM)
            .apply()
    }

    fun dynamicEnabled(ctx: Context): Boolean = selected(ctx) == SYSTEM && dynamicSupported

    fun labelRes(ctx: Context): Int = when (selected(ctx)) {
        SYSTEM -> R.string.theme_palette_dynamic
        TEAL -> R.string.theme_palette_teal
        VIOLET -> R.string.theme_palette_violet
        AMBER -> R.string.theme_palette_amber
        else -> R.string.theme_palette_blue
    }

    /** Official DynamicColors handles SYSTEM; other sources use generated Material overlays. */
    fun overlayRes(ctx: Context): Int = when (selected(ctx)) {
            TEAL -> R.style.ThemeOverlay_Dsh_Palette_Teal
            VIOLET -> R.style.ThemeOverlay_Dsh_Palette_Violet
            AMBER -> R.style.ThemeOverlay_Dsh_Palette_Amber
            BLUE -> R.style.ThemeOverlay_Dsh_Palette_Blue
            // Dynamic unavailable uses the blue fallback without changing the stored preference.
            else -> if (dynamicSupported) 0 else R.style.ThemeOverlay_Dsh_Palette_Blue
    }

    fun wrap(ctx: Context): Context {
        val overlay = overlayRes(ctx)
        return if (overlay == 0) ctx else ContextThemeWrapper(ctx, overlay)
    }
}

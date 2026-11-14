package app.dsh.mobile

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import com.google.android.material.radiobutton.MaterialRadioButton

/** A selection UI only. Callers own the selected ID, palette application and persistence. */
class ThemePaletteSelector(
    context: Context,
    selectedId: String,
    private val dynamicAvailable: Boolean,
    private val onSelected: (String) -> Unit,
) : LinearLayout(context) {

    companion object {
        const val DYNAMIC = ThemeAccent.SYSTEM
        const val BLUE = "blue"
        const val TEAL = "teal"
        const val VIOLET = "violet"
        const val AMBER = "amber"

        /** Seed colors for Material Tonal Spot schemes; dynamic color has no fixed seed. */
        @ColorInt
        fun seedColor(id: String): Int? = when (id) {
            BLUE -> 0xFF3268AE.toInt()
            TEAL -> 0xFF006B5A.toInt()
            VIOLET -> 0xFF7750A2.toInt()
            AMBER -> 0xFF8E5900.toInt()
            else -> null
        }
    }

    private val buttons = linkedMapOf<String, MaterialRadioButton>()
    private var currentId = normalizedId(selectedId)

    init {
        orientation = VERTICAL
        addOption(DYNAMIC, R.string.theme_palette_dynamic)
        addOption(BLUE, R.string.theme_palette_blue)
        addOption(TEAL, R.string.theme_palette_teal)
        addOption(VIOLET, R.string.theme_palette_violet)
        addOption(AMBER, R.string.theme_palette_amber)
        updateChecked()
    }

    /** Updates the displayed selection without notifying the caller. */
    fun setSelectedId(selectedId: String) {
        currentId = normalizedId(selectedId)
        updateChecked()
    }

    private fun normalizedId(id: String): String = when (id) {
        DYNAMIC -> DYNAMIC
        BLUE, TEAL, VIOLET, AMBER -> id
        else -> BLUE
    }

    private fun addOption(id: String, labelRes: Int) {
        val unavailable = id == DYNAMIC && !dynamicAvailable
        val label = context.getString(labelRes)
        val accessibleLabel = if (unavailable) {
            context.getString(R.string.theme_palette_dynamic_unavailable, label)
        } else {
            label
        }
        val swatchColor = seedColor(id) ?: if (dynamicAvailable && Build.VERSION.SDK_INT >= 31) {
            context.getColor(android.R.color.system_accent1_500)
        } else {
            null
        }
        val swatch = swatchColor?.let { color ->
            ColorDrawable(color).apply { setBounds(0, 0, dp(24), dp(24)) }
        }
        val button = MaterialRadioButton(context).apply {
            text = accessibleLabel
            contentDescription = accessibleLabel
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(12)
            setPaddingRelative(dp(12), 0, dp(16), 0)
            setCompoundDrawablesRelative(null, null, swatch, null)
            isEnabled = !unavailable
            setOnClickListener {
                if (id != currentId) {
                    currentId = id
                    updateChecked()
                    onSelected(id)
                }
            }
        }
        buttons[id] = button
        addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun updateChecked() {
        buttons.forEach { (id, button) -> button.isChecked = id == currentId }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()
}

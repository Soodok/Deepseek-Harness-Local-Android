package app.dsh.mobile

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup

class IslandDockActivity : ThemedActivity() {
    override val usesSettingsBackground: Boolean get() = true

    private var updating = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_island_dock)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButtonToggleGroup>(R.id.dockModeGroup)
            .addOnButtonCheckedListener { _, id, checked ->
                if (!checked || updating) return@addOnButtonCheckedListener
                val current = CustomVisualPref.islandDock(this)
                when (id) {
                    R.id.dockModeIsland -> {
                        if (current == IslandDock.BOTTOM) {
                            CustomVisualPref.setIslandDock(this, rememberedDock())
                        }
                    }
                    R.id.dockModeToolbar -> {
                        if (current != IslandDock.BOTTOM) {
                            rememberDock(current)
                            CustomVisualPref.setIslandDock(this, IslandDock.BOTTOM)
                        }
                    }
                }
                refresh()
            }
        findViewById<RadioGroup>(R.id.dockPositionGroup)
            .setOnCheckedChangeListener { _, id ->
                if (updating) return@setOnCheckedChangeListener
                val dock = when (id) {
                    R.id.dockTop -> IslandDock.TOP
                    R.id.dockLeft -> IslandDock.LEFT
                    R.id.dockRight -> IslandDock.RIGHT
                    else -> return@setOnCheckedChangeListener
                }
                rememberDock(dock)
                CustomVisualPref.setIslandDock(this, dock)
            }
    }

    override fun onResume() {
        super.onResume()
        if (styleNeedsRecreate()) return
        OrientationPrefs.applyToSettingsPage(this)
        refresh()
    }

    private fun refresh() {
        val dock = CustomVisualPref.islandDock(this)
        if (dock != IslandDock.BOTTOM) rememberDock(dock)
        updating = true
        findViewById<MaterialButtonToggleGroup>(R.id.dockModeGroup).check(
            if (dock == IslandDock.BOTTOM) R.id.dockModeToolbar else R.id.dockModeIsland,
        )
        findViewById<View>(R.id.dockPositionSection).visibility =
            if (dock == IslandDock.BOTTOM) View.GONE else View.VISIBLE
        findViewById<View>(R.id.dockToolbarDescription).visibility =
            if (dock == IslandDock.BOTTOM) View.VISIBLE else View.GONE
        val position = if (dock == IslandDock.BOTTOM) rememberedDock() else dock
        findViewById<RadioGroup>(R.id.dockPositionGroup).check(when (position) {
            IslandDock.LEFT -> R.id.dockLeft
            IslandDock.RIGHT -> R.id.dockRight
            else -> R.id.dockTop
        })
        updating = false
    }

    private fun rememberedDock(): IslandDock = when (
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_ISLAND_DOCK, null)
    ) {
        IslandDock.LEFT.name -> IslandDock.LEFT
        IslandDock.RIGHT.name -> IslandDock.RIGHT
        else -> IslandDock.TOP
    }

    private fun rememberDock(dock: IslandDock) {
        if (dock != IslandDock.BOTTOM) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST_ISLAND_DOCK, dock.name).apply()
        }
    }

    private companion object {
        const val PREFS = "island_dock_settings"
        const val KEY_LAST_ISLAND_DOCK = "last_island_dock"
    }
}

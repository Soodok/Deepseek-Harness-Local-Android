package app.dsh.mobile

import java.io.File
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiContractTest {

    @Test
    fun lightThemesDeclareTheCompleteDshTokenSet() {
        val source = readProjectFile("app/src/main/res/values/themes.xml")
        val expected = listOf(
            "dshBg",
            "dshSurface",
            "dshSurfaceAlt",
            "dshSurfaceHigh",
            "dshDialogBg",
            "dshTextPrimary",
            "dshTextSecondary",
            "dshTextMuted",
            "dshAccent",
            "dshAccentSoft",
            "dshOnAccent",
            "dshDivider",
            "dshStroke",
            "dshDanger",
            "dshSuccess",
            "dshWarning",
            "dshIconColor",
            "dshBtnRipple",
            "dshOverlay",
            "dshIslandText",
            "dshIslandAccent",
            "dshCardRadius",
            "dshCardStroke",
            "dshIconChipRadius",
            "dshIconChipStroke",
            "dshButtonRadius",
            "dshControlRadius",
            "dshGridCellRadius",
        )

        assertTrue("Theme.Dsh must expose all dsh tokens", hasAllItems(styleBlock(source, "Theme.Dsh"), expected))
        assertTrue(
            "Theme.Dsh.MD3 must expose all dsh tokens",
            hasAllItems(styleBlock(source, "Theme.Dsh.MD3"), expected),
        )
    }

    @Test
    fun stylePrefDoesNotTreatEveryResourceIdAsAPlainColor() {
        val source = readProjectFile("app/src/main/java/app/dsh/mobile/StylePref.kt")

        assertTrue(source.contains("getColorStateList"))
        assertTrue(source.contains("ColorStateList"))
        assertTrue(source.contains("TYPE_FIRST_COLOR_INT"))
        assertTrue(source.contains("TYPE_LAST_COLOR_INT"))
        assertFalse(source.contains("ctx.getColor(tv.resourceId)"))
    }

    @Test
    fun settingsCardsAndGridUseThemeShapeAndSurfaceTokens() {
        val source = readProjectFile("app/src/main/java/app/dsh/mobile/SettingsActivity.kt")

        assertTrue(source.contains("applyDshCardStyles(this)"))
        assertTrue(source.contains("R.attr.dshGridCellRadius"))
        assertTrue(source.contains("R.attr.dshCardStroke"))
        assertTrue(source.contains("R.attr.dshStroke"))
        assertTrue(source.contains("R.attr.dshSurface"))
        assertFalse(source.contains("MaterialColors.getColor("))
        assertFalse(source.contains("R.dimen.md3_card_radius"))
    }

    @Test
    fun settingsSubpagesApplyTheSameCardContract() {
        val permission = readProjectFile("app/src/main/java/app/dsh/mobile/PermissionCenterActivity.kt")
        val maintenance = readProjectFile("app/src/main/java/app/dsh/mobile/MaintenanceActivity.kt")
        val themed = readProjectFile("app/src/main/java/app/dsh/mobile/ThemedActivity.kt")

        assertTrue(permission.contains("applyDshCardStyles(this)"))
        assertTrue(maintenance.contains("applyDshCardStyles(this)"))
        assertTrue(
            "Every settings subpage must inherit the shared card contract",
            themed.contains("if (usesSettingsBackground) applyDshCardStyles(this)"),
        )
    }

    @Test
    fun settingsMemoryPreviewUsesTheSharedMaterialDialog() {
        val source = readProjectFile("app/src/main/java/app/dsh/mobile/MemoryDetailActivity.kt")

        assertTrue(source.contains("MaterialAlertDialogBuilder(this)"))
        assertFalse(source.contains("android.app.AlertDialog.Builder(this)"))
    }

    @Test
    fun lightThemesUseTheSharedMaterialDialogOverlay() {
        val source = readProjectFile("app/src/main/res/values/themes.xml")
        val expected =
            "<item name=\"materialAlertDialogTheme\">@style/ThemeOverlay.Dsh.MaterialAlertDialog</item>"

        assertTrue(
            "Theme.Dsh must style MaterialAlertDialogBuilder",
            styleBlock(source, "Theme.Dsh").contains(expected),
        )
        assertTrue(
            "Theme.Dsh.MD3 must style MaterialAlertDialogBuilder",
            styleBlock(source, "Theme.Dsh.MD3").contains(expected),
        )
    }

    @Test
    fun pluginSelectedCategoryUsesThemeOnAccentColor() {
        val source = readProjectFile("app/src/main/java/app/dsh/mobile/PluginStoreActivity.kt")
        val refresh = source.substringAfter("private fun refreshChipStyles()")
            .substringBefore("private fun dp(")

        assertTrue(refresh.contains("R.attr.dshAccent"))
        assertTrue(refresh.contains("R.attr.dshOnAccent"))
        assertFalse(refresh.contains("Accent.current(this)"))
        assertFalse(refresh.contains("0xFFFFFFFF.toInt()"))
    }

    private fun styleBlock(source: String, name: String): String =
        source.substringAfter("<style name=\"$name\"")
            .substringBefore("</style>")

    private fun hasAllItems(source: String, names: List<String>): Boolean =
        names.all { source.contains("<item name=\"$it\">") }

    private fun readProjectFile(relativePath: String): String {
        val candidates = listOf(
            Path.of(relativePath),
            Path.of("..", relativePath),
            Path.of("..", "..", relativePath),
        )
        val file = candidates
            .map(Path::toFile)
            .firstOrNull(File::isFile)
            ?: error("Cannot find project file $relativePath from ${Path.of("").toAbsolutePath()}")
        return file.readText()
    }
}

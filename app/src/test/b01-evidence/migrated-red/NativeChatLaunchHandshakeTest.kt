package app.dsh.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static contract tests for the native side of the launch handoff. */
class NativeChatLaunchHandshakeTest {
    private val source = File("src/main/java/app/dsh/mobile/NativeChatActivity.kt").readText()

    private fun section(start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing section " + start, from >= 0)
        val until = source.indexOf(end, from + start.length)
        assertTrue("missing section end " + end, until > from)
        return source.substring(from, until)
    }

    @Test
    fun extrasAreAvailableBeforeFlutterEngineStarts() {
        val create = section("override fun onCreate(", "override fun onResume()")
        assertTrue(create.contains("sessionId ="))
        assertTrue(create.contains("backdropPath ="))
        assertTrue(create.contains("super.onCreate("))
        assertTrue(create.indexOf("sessionId =") < create.indexOf("super.onCreate("))
        assertTrue(create.indexOf("backdropPath =") < create.indexOf("super.onCreate("))
    }

    @Test
    fun queuedAttachmentCannotRestoreAReleasedOrFailedCover() {
        val attach = section("private fun attachBackdrop(", "private fun releaseBackdrop()")
        assertTrue(attach.contains("isFinishing || isDestroyed || backdropReleased || launchFailed"))
        assertTrue(attach.indexOf("backdropReleased || launchFailed") < attach.indexOf("manager.addView("))
    }

    @Test
    fun fullWindowCaptureIsNotInsetByApplicationPanel() {
        val attach = section("private fun attachBackdrop(", "private fun releaseBackdrop()")
        val clearInsets = attach.indexOf("lp.setFitInsetsTypes(0)")
        assertTrue("panel must not fit bars before attaching a full-window bitmap", clearInsets >= 0)
        assertTrue(clearInsets < attach.indexOf("manager.addView("))
    }

    @Test
    fun removalFailureDoesNotAcknowledgeOrLoseTheCoverReference() {
        val release = section("private fun releaseBackdrop()", "private fun removeBackdrop()")
        assertTrue(release.contains("if (!removeBackdrop()) return false"))
        assertTrue(release.indexOf("if (!removeBackdrop()) return false") < release.indexOf("backdropReleased = true"))
        val remove = section("private fun removeBackdrop()", "private fun failLaunch(")
        assertTrue(remove.contains("manager.removeViewImmediate(image)"))
        assertTrue(remove.contains("if (!removed) return false"))
        assertTrue(remove.indexOf("if (!removed) return false") < remove.indexOf("backdropView = null"))
    }

    @Test
    fun themeStartsWithTheSameTransparentBarsAsTheNativeAndFlutterViews() {
        val themes = File("src/main/res/values/themes.xml").readText()
        assertTrue(themes.contains("<style name=\"Theme.Dsh.NativeChat\""))
        val nativeTheme = themes.substringAfter("<style name=\"Theme.Dsh.NativeChat\"")
            .substringBefore("</style>")
        assertTrue(nativeTheme.contains("<item name=\"android:statusBarColor\">@android:color/transparent</item>"))
        assertTrue(nativeTheme.contains("<item name=\"android:navigationBarColor\">@android:color/transparent</item>"))
        assertTrue(nativeTheme.contains("<item name=\"android:enforceStatusBarContrast\">false</item>"))
        assertTrue(nativeTheme.contains("<item name=\"android:enforceNavigationBarContrast\">false</item>"))
    }

    @Test
    fun nativeChatUsesFixedTransparentEdgeToEdgeSystemBars() {
        assertTrue(source.contains("setDecorFitsSystemWindows(false)"))
        assertTrue(source.contains("statusBarColor = android.graphics.Color.TRANSPARENT"))
        assertTrue(source.contains("navigationBarColor = android.graphics.Color.TRANSPARENT"))
        assertTrue(source.contains("isStatusBarContrastEnforced = false"))
        assertTrue(source.contains("isNavigationBarContrastEnforced = false"))
    }

    @Test
    fun backdropIsReleasedOnlyByExplicitFlutterAcknowledgementPath() {
        assertTrue(source.contains("\"releaseBackdrop\""))
        assertTrue(source.contains("result.success(releaseBackdrop())"))
        assertFalse(
            "brightness watchdog must not withdraw the cover before content-ready",
            source.contains("startBackdropWatch()"),
        )
        assertFalse(
            "old watchdog timeout must not remain as an early-release path",
            source.contains("BACKDROP_WATCH_TIMEOUT_MS"),
        )
    }

    @Test
    fun launchFailureHasBoundedHostFallback() {
        assertTrue(source.contains("\"launchFadeFailed\""))
        assertTrue(source.contains("handler.postDelayed(readyTimeout, BACKDROP_READY_TIMEOUT_MS)"))
        val failure = section("private fun failLaunch(", "private fun backdropPng()")
        assertTrue(failure.contains("launchFailed = true"))
        assertTrue(failure.contains("openWebFallback()"))
        assertTrue(failure.contains("removeBackdrop()"))
        assertTrue(failure.contains("finish()"))
    }
}

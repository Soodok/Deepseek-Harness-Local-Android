package app.dsh.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contracts for the cold Web -> native entry, not a timing or SDK failure-delivery test.
 *
 * The request remains in MainActivity until preparation reports success AND the shared loader
 * is initialized. Only then may the existing PixelCopy -> Web SID -> Activity-result route run.
 * Every asynchronous boundary belongs to one foreground generation; pause/destroy cancels it.
 *
 * The named preparation callback uses Result<Unit>. This does NOT claim FlutterLoader's
 * ensureInitializationCompleteAsync supplies errors: in SDK 3.50.0 it has a success Runnable
 * only, and throws failures on a worker or a separately posted main-thread runnable. A wrapper
 * around the initiating call is insufficient. Its adapter needs separate proof before release.
 *
 * These tests intentionally introduce no Android/Flutter execution or new test dependencies.
 * Existing launch/readiness tests and a fresh cold-entry trace remain separate acceptance gates.
 */
class NativeChatColdEntryTest {
    private val main = executable(File("src/main/java/app/dsh/mobile/MainActivity.kt").readText())

    // Mask comments AND literals so documentation or a log message cannot satisfy a contract.
    private fun executable(source: String): String {
        val tokens = Regex("(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
        return tokens.replace(source) { match ->
            match.value.map { if (it == '\n' || it == '\r') it else ' ' }.joinToString("")
        }
    }

    private fun block(source: String, anchor: String): String {
        val from = source.indexOf(anchor)
        assertTrue("missing executable launch contract: " + anchor, from >= 0)
        val opening = source.indexOf('{', from + anchor.length)
        assertTrue("missing block for " + anchor, opening >= 0)
        var depth = 0
        for (index in opening until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(opening + 1, index)
                }
            }
        }
        throw AssertionError("unclosed block for " + anchor)
    }

    private fun compact(source: String): String = source.replace(Regex("\\s+"), "")

    private fun assertBefore(source: String, first: String, second: String) {
        val a = source.indexOf(first)
        val b = source.indexOf(second)
        assertTrue("missing " + first, a >= 0)
        assertTrue("missing " + second, b >= 0)
        assertTrue(first + " must precede " + second, a < b)
    }

    private fun assertSingle(source: String, token: String) {
        val invocation = Regex("\\b" + Regex.escape(token) + "\\s*(?:\\(|\\{)")
        assertEquals("one executable invocation of " + token, 1, invocation.findAll(source).count())
    }

    @Test
    fun coldEntryWaitsForSuccessfulInitializedLoaderBeforeOriginalHandoff() {
        val request = block(main, "fun requestNativeChat()")
        val prepared = block(request, "prepareNativeChatRuntime")
        val success = block(prepared, "onSuccess =")
        val capture = block(success, "captureWindowBackdrop")
        val session = block(capture, "readCurrentWebSession")

        assertTrue("preparation must be result-aware, not a success-only Runnable", prepared.contains(".fold("))
        val check = compact(success)
        assertTrue(
            "success cannot launch until the singleton loader is actually initialized",
            check.contains("if(!FlutterInjector.instance().flutterLoader().initialized())"),
        )
        val notReady = block(success, "if (!FlutterInjector.instance().flutterLoader().initialized())")
        assertTrue("an uninitialized completion must release the pending request", compact(notReady).contains("nativeChatLaunchPending=false"))
        assertTrue("an uninitialized completion must stop the success continuation", notReady.contains("return@"))
        assertFalse(notReady.contains("captureWindowBackdrop"))
        assertBefore(success, "FlutterInjector.instance().flutterLoader().initialized()", "captureWindowBackdrop")
        assertTrue(session.contains("startActivityForResult("))
        assertTrue(session.contains("NativeChatActivity::class.java"))
        assertTrue(session.contains("NativeChatActivity.EXTRA_SESSION_ID"))
        assertTrue(session.contains("NativeChatActivity.EXTRA_BACKDROP"))
        assertTrue(session.contains("REQ_NATIVE_CHAT"))
        for (token in listOf("prepareNativeChatRuntime", "captureWindowBackdrop", "readCurrentWebSession", "startActivityForResult")) {
            assertSingle(request, token)
        }
        assertTrue("keep the hardware-composited snapshot route", main.contains("PixelCopy.request("))
        assertFalse("do not synchronously wait for the loader in the click handler", request.contains(".ensureInitializationComplete("))
    }

    @Test
    fun duplicateClicksAndEveryLateContinuationAreBoundToOneForegroundGeneration() {
        val request = block(main, "fun requestNativeChat()")
        val dense = compact(request)
        assertTrue("a second click must not enqueue another preparation", dense.contains("if(nativeChatLaunchPending"))
        assertBefore(request, "nativeChatLaunchPending = true", "prepareNativeChatRuntime")
        assertBefore(request, "val generation = ++nativeChatLaunchGeneration", "prepareNativeChatRuntime")

        for ((anchor, label) in listOf(
            "prepareNativeChatRuntime" to "prepareNativeChatRuntime",
            "captureWindowBackdrop" to "captureWindowBackdrop",
            "readCurrentWebSession" to "readCurrentWebSession",
        )) {
            val continuation = compact(block(request, anchor))
                .replaceFirst(Regex("^[A-Za-z_]\\w*->"), "")
            assertTrue(
                "reject stale completion immediately at " + label,
                continuation.startsWith("if(!isNativeChatLaunchCurrent(generation))return@" + label),
            )
        }
        val session = block(block(request, "captureWindowBackdrop"), "readCurrentWebSession")
        assertBefore(session, "nativeChatLaunchPending = false", "startActivityForResult(")

        val current = compact(block(main, "fun isNativeChatLaunchCurrent("))
        for (condition in listOf("nativeChatLaunchPending", "nativeChatResumed", "generation==nativeChatLaunchGeneration", "!isFinishing", "!isDestroyed")) {
            assertTrue("launch validity needs " + condition, current.contains(condition))
        }
        assertFalse("all validity predicates must hold together", current.contains("||"))
    }

    @Test
    fun preparationFailureEndsOnlyThatRequestWithoutSnapshotSessionReadOrLaunch() {
        val request = block(main, "fun requestNativeChat()")
        val failure = block(block(request, "prepareNativeChatRuntime"), "onFailure =")
        assertTrue("failure must allow a fresh click rather than stick pending", compact(failure).contains("nativeChatLaunchPending=false"))
        assertFalse(failure.contains("captureWindowBackdrop"))
        assertFalse(failure.contains("readCurrentWebSession"))
        assertFalse(failure.contains("startActivityForResult("))
        assertFalse("failure must not synthesize a successful continuation", failure.contains("onSuccess"))
    }

    @Test
    fun pauseAndDestroyInvalidateTheGenerationRatherThanLaunchingOnNextResume() {
        val cancel = compact(block(main, "fun cancelNativeChatLaunch()"))
        assertTrue("invalidate old requests even if the Activity resumes before their completion", cancel.contains("nativeChatLaunchGeneration++"))
        assertTrue(cancel.contains("nativeChatLaunchPending=false"))
        assertTrue(cancel.contains("nativeChatResumed=false"))
        for (lifecycle in listOf("onPause", "onDestroy")) {
            val callback = block(main, "override fun " + lifecycle + "()")
            assertBefore(callback, "cancelNativeChatLaunch()", "super." + lifecycle + "()")
        }
        val resume = block(main, "override fun onResume()")
        assertTrue(compact(resume).contains("nativeChatResumed=true"))
        assertFalse("resume alone must never replay an old click", resume.contains("requestNativeChat("))
        assertFalse("resume alone must not enqueue a delayed launch", resume.contains("prepareNativeChatRuntime"))
        val request = compact(block(main, "fun requestNativeChat()"))
        assertBefore(request, "!nativeChatResumed", "nativeChatLaunchPending=true")
    }

    @Test
    fun preparationUsesSharedLoaderWithoutConstructingOrRunningAnEngine() {
        val preparation = block(main, "fun prepareNativeChatRuntime(")
        assertTrue("use the loader consumed by FlutterActivity, not a detached loader", preparation.contains("FlutterInjector.instance().flutterLoader()"))
        assertTrue("initialize with application context, never retain MainActivity as context", preparation.contains(".startInitialization(applicationContext)"))
        assertFalse("do not create a second loader", preparation.contains("FlutterLoader("))
        for (forbidden in listOf("FlutterEngine(", "FlutterEngineGroup(", "createAndRunEngine(", "executeDartEntrypoint(", "FlutterEngineCache")) {
            assertFalse("MainActivity may prepare only the loader: " + forbidden, main.contains(forbidden))
        }
    }
}

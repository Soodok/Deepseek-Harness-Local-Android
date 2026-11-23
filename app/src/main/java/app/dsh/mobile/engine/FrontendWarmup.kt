package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Move the bundled frontend's eager syntax-highlight warm-up to idle time.
 *
 * The shipped `dist/assets/index-*.js` builds a Shiki highlighter and immediately
 * tokenizes three sample snippets on a `setTimeout(...,0)` right after module
 * evaluation. Measured on emulator-5554 (x86_64, Android 14, 8 GB AVD), that
 * warm-up costs 641-843 ms of main-thread time, of which the TypeScript grammar
 * alone is 494-662 ms; building the highlighter itself is only 3-21 ms. Because
 * the callback is scheduled at timeout 0 it lands inside the load window and
 * delays first paint.
 *
 * Delaying it to `requestIdleCallback` keeps the warm-up benefit (the first real
 * code block still gets a pre-compiled grammar) while moving the cost out of the
 * paint window. A/B over the app's own `webview-first-paint` marker, three
 * alternating rounds: engine-ready -> first-paint 4629 ms median unchanged vs
 * 3311 ms deferred (28.5% faster, deferred won 3/3 rounds).
 *
 * The warm-up function name is minified per build (`Ji`, `Mi`, `Xi` observed), so
 * the patch is matched structurally, never by name. Applied once per installed
 * asset file and idempotent via [MARKER].
 */
object FrontendWarmup {
    private const val TAG = "FrontendWarmup"

    /** Bump when the replacement body changes so already-patched installs re-apply. */
    private const val MARKER = "/* dsh-android: idle warm-up v1 */"

    private const val FRONTEND_ROOT =
        "lib/node_modules/@deepseek-ai/dsh-web-frontend/dist"
    private const val ASSETS_DIR = "$FRONTEND_ROOT/assets"

    /**
     * `(v=(w=setTimeout(()=>{FN()},0)).unref)==null||v.call(w);`
     *
     * The bundler emits this idiom for the preload so it can `unref` the handle in
     * Node. Groups: 1 = outer temp, 2 = handle temp, 3 = warm-up function name.
     */
    private val unrefForm = Regex(
        """\((\w+)=\((\w+)=setTimeout\(\(\)=>\{(\w+)\(\)\},0\)\)\.unref\)==null\|\|\1\.call\(\2\);?""",
    )

    /** Plain `setTimeout(()=>{FN()},0).unref?.();` form used by the other build. */
    private val plainForm = Regex(
        """setTimeout\(\(\)=>\{(\w+)\(\)\},0\)(\.unref\?\.\(\);?)?""",
    )

    private fun idleBody(fn: String): String =
        "setTimeout(()=>{const __f=()=>{window.__dshWarmAt=window.__dshWarmAt||" +
            "Math.round(performance.now());$fn()};" +
            "if(typeof requestIdleCallback===\"function\")" +
            "requestIdleCallback(__f,{timeout:2500});else setTimeout(__f,1200)},0)"

    /** Pure text transform, exposed for tests. Returns the input when nothing matches. */
    internal fun patchBundle(source: String): String {
        if (source.contains(MARKER)) return source
        var replaced = false
        // Order matters: the unref form contains a plain `setTimeout(()=>{FN()},0)`
        // substring, so consume it first or the plain pass would rewrite the inner
        // call and leave the wrapper broken.
        var out = unrefForm.replace(source) { m ->
            replaced = true
            idleBody(m.groupValues[3]) + ";"
        }
        out = plainForm.replace(out) { m ->
            replaced = true
            idleBody(m.groupValues[1]) + ";"
        }
        if (!replaced) return source
        return "$MARKER\n$out"
    }

    /** True when [file] already carries this patch. */
    internal fun isPatched(file: File): Boolean =
        runCatching { file.isFile && file.readText(Charsets.UTF_8).contains(MARKER) }
            .getOrDefault(false)

    /**
     * Patch every `assets/index-*.js` entry under the installed frontend.
     * Returns the number of files whose content changed.
     */
    fun healInstalled(ctx: Context): Int = runCatching {
        val assets = File(EngineConfig.engineRoot(ctx), ASSETS_DIR)
        if (!assets.isDirectory) {
            Log.w(TAG, "frontend assets dir missing: " + assets.absolutePath)
            return@runCatching 0
        }
        val targets = assets.listFiles()?.filter { file ->
            file.isFile && file.name.startsWith("index-") && file.name.endsWith(".js")
        }.orEmpty()
        if (targets.isEmpty()) {
            Log.w(TAG, "no index-*.js under " + assets.absolutePath)
            return@runCatching 0
        }
        var changed = 0
        for (file in targets) {
            val before = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val after = patchBundle(before)
            if (after == before) continue
            if (writeAtomically(file, after)) {
                changed += 1
                Log.i(TAG, "idle warm-up applied: " + file.name)
            }
        }
        changed
    }.getOrElse { error ->
        Log.e(TAG, "idle warm-up patch failed: " + error.message, error)
        0
    }

    private fun writeAtomically(file: File, body: String): Boolean = runCatching {
        val temp = File(file.parentFile, file.name + ".dsh-warm.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
        true
    }.getOrElse { error ->
        Log.e(TAG, "atomic write failed for " + file.name + ": " + error.message, error)
        false
    }
}

package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Shared WebView polyfill loader and HTML patcher.
 *
 * The bytes are generated at build time from scripts/patch-webview-polyfill.py's
 * POLYFILL constant into assets/js/legacy-webview-polyfill.html. This class owns
 * only placement and runtime healing; it must not contain a second polyfill body.
 */
object WebViewPolyfill {
    private const val TAG = "WebViewPolyfill"
    private const val ASSET_PATH = "js/legacy-webview-polyfill.html"
    const val FRONTEND_RELATIVE =
        "lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html"

    private val moduleScript = Regex(
        """<script\b[^>]*\btype\s*=\s*[\"']module[\"'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val oldPolyfill = Regex(
        """(?s)(?:<!--\s*\[dsh-android\]\s*legacy-webview polyfill v\d+[^>]*-->\s*)+<script>.*?</script>\s*""",
    )

    private fun asset(ctx: Context): String =
        ctx.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Compare the whole generated block: comments/API names alone are not proof. */
    internal fun hasRequiredPolyfill(html: String, polyfill: String): Boolean {
        val firstModule = moduleScript.find(html)?.range?.first ?: return false
        val blocks = oldPolyfill.findAll(html).toList()
        return blocks.size == 1 && blocks[0].range.last < firstModule &&
            blocks[0].value.trimEnd() == polyfill.trimEnd()
    }

    /** Inject/replace the generated polyfill; returns the original if no anchor exists. */
    fun patchHtml(ctx: Context, html: String): String = patchHtml(html, asset(ctx))

    internal fun patchHtml(html: String, polyfill: String): String {
        if (hasRequiredPolyfill(html, polyfill)) return html
        val stripped = oldPolyfill.replace(html, "")
        val anchor = moduleScript.find(stripped) ?: return html
        return stripped.substring(0, anchor.range.first) + polyfill + "\n" +
            stripped.substring(anchor.range.first)
    }

    /**
     * Heal an already installed runtime before the engine serves WebUI.
     * Missing, incomplete, or misplaced polyfill is repaired in place once.
     */
    fun healInstalled(ctx: Context): Boolean = runCatching {
        val htmlFile = File(EngineConfig.engineRoot(ctx), FRONTEND_RELATIVE)
        if (!htmlFile.isFile) {
            Log.w(TAG, "runtime frontend index.html missing: " + htmlFile.absolutePath)
            return@runCatching false
        }
        val before = htmlFile.readText(Charsets.UTF_8)
        val polyfill = asset(ctx)
        if (hasRequiredPolyfill(before, polyfill)) {
            Log.i(TAG, "runtime frontend polyfill present: " + htmlFile.absolutePath)
            return@runCatching false
        }
        val after = patchHtml(before, polyfill)
        if (after == before) {
            Log.w(TAG, "runtime frontend polyfill not injected: module anchor missing")
            return@runCatching false
        }
        val temp = File(htmlFile.parentFile, htmlFile.name + ".dsh-polyfill.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(after.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                htmlFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
        Log.w(TAG, "runtime frontend polyfill missing/incompatible; injected: " + htmlFile.absolutePath)
        true
    }.getOrElse { error ->
        Log.e(TAG, "runtime frontend polyfill self-heal failed: " + error.message, error)
        false
    }
}

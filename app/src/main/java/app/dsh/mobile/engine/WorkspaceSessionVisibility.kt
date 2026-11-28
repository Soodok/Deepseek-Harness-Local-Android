package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Compatibility patch for the bundled workspace client used by WebView. */
internal object WorkspaceSessionVisibility {
    private const val TAG = "WorkspaceSessionVisibility"
    private const val CLIENT = "lib/node_modules/@deepseek-ai/dsh-client-ui-workspace/lib/client.js"
    private const val PREFIX = "function sessionVisible(session,current,archived,archivedFilter){" +
        "if(session.origin===`subagent`)return false;"
    private const val BLANK_GUARD = "if(session.blank&&session.id!==current)return false;"
    private const val ARCHIVE_FILTER = "switch(archivedFilter){"
    private const val ANCHOR = PREFIX + BLANK_GUARD + ARCHIVE_FILTER

    /** Expose empty sessions while keeping the workspace client's archive and origin checks. */
    internal fun patch(source: String): String {
        val offset = source.indexOf(ANCHOR)
        if (offset < 0 || source.indexOf(ANCHOR, offset + 1) >= 0) return source
        val guardStart = offset + PREFIX.length
        return source.removeRange(guardStart, guardStart + BLANK_GUARD.length)
    }

    fun healInstalled(ctx: Context) {
        val installed = File(EngineConfig.engineRoot(ctx), CLIENT)
        if (!installed.isFile) return
        runCatching {
            val source = installed.readText(Charsets.UTF_8)
            val patched = patch(source)
            if (patched == source) return
            val temp = File.createTempFile("workspace-client-", ".tmp", installed.parentFile)
            try {
                FileOutputStream(temp).use { out ->
                    out.write(patched.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                Files.move(temp.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } finally {
                if (temp.exists()) temp.delete()
            }
            Log.i(TAG, "Enabled visibility for empty workspace sessions")
        }.onFailure { Log.w(TAG, "Could not update workspace client", it) }
    }
}

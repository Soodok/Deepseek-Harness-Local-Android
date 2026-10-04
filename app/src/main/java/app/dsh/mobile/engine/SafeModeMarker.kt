package app.dsh.mobile.engine

import java.io.File

internal class SafeModeMarker(private val profilesDirectory: File) {

    private val markerFile: File
        get() = File(profilesDirectory, ".safe-mode")

    fun isActive(): Boolean = markerFile.isFile

    fun mark(reason: String?) {
        check(profilesDirectory.isDirectory || profilesDirectory.mkdirs()) {
            "Unable to create profile directory for safe-mode marker"
        }
        markerFile.writeText(
            "engine entered safe mode ${System.currentTimeMillis()}\n" +
                (reason?.let { "failure signature: $it\n" } ?: "") +
                "your plugin configs and top-level config files were archived to\n" +
                "the newest profiles.crash-archive-* (__home__/ holds top-level files)\n" +
                "recover: copy needed files back after fixing them\n",
        )
    }

    fun clear() {
        if (markerFile.exists()) check(markerFile.delete()) { "Unable to remove safe-mode marker" }
    }
}

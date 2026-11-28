package app.dsh.mobile.coldentry

import java.io.File

/** Exact files minted here only; never reconstruct ownership from Intent/Bundle or scan a directory. */
object BackdropFiles {
    private val retry = mutableSetOf<File>()

    @Synchronized fun reserve(directory: File, deletionFailed: () -> Unit): ExclusiveBackdrop {
        retryDeletes(deletionFailed)
        check(directory.isDirectory || directory.mkdirs()) { "Backdrop cache unavailable" }
        val file = File.createTempFile("entry-", ".png", directory)
        return ExclusiveBackdrop(file.absolutePath) {
            synchronized(this) {
                if (!remove(file)) {
                    retry += file
                    notifyFailure(deletionFailed)
                }
            }
        }
    }

    private fun remove(file: File): Boolean = try { !file.exists() || file.delete() } catch (_: Exception) { false }
    private fun notifyFailure(notify: () -> Unit) { try { notify() } catch (_: Exception) { /* Cleanup remains nonthrowing. */ } }

    @Synchronized fun retryDeletes(deletionFailed: () -> Unit) {
        val iterator = retry.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if (remove(file)) iterator.remove() else notifyFailure(deletionFailed)
        }
    }
}

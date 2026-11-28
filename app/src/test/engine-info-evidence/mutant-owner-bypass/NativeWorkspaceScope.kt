package app.dsh.mobile.engine

import java.io.File
import java.security.MessageDigest

/** Native UI preference scope; intentionally separate from Host registry defaults. */
data class NativeWorkspaceScope(val domain: String, val generation: String) {
    fun matches(domain: String?, generation: String?): Boolean =
        this.domain == domain && this.generation == generation

    companion object {
        /** Capture only from actual launch inputs, not pending runtime preferences. */
        fun domain(namespace: String, home: File, bindings: List<Pair<String, File>> = emptyList()): String? =
            runCatching {
                require(namespace.isNotBlank() && home.isAbsolute)
                val fields = listOf(
                    "native-workspace-v1", namespace, home.canonicalPath,
                    File(home, "storages").canonicalPath,
                ) + bindings.sortedBy { it.first }.map { (guest, host) ->
                    require(guest.startsWith("/") && host.isAbsolute)
                    guest + "=" + host.canonicalPath
                }
                MessageDigest.getInstance("SHA-256")
                    .digest(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }.getOrNull()
    }
}

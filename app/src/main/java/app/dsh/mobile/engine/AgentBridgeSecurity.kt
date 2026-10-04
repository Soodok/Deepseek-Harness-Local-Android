package app.dsh.mobile.engine

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object AgentBridgeSecurity {

    private val secureRandom = SecureRandom()

    fun newToken(): String = ByteArray(32)
        .also(secureRandom::nextBytes)
        .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    fun isAuthorized(token: String, authorization: String?): Boolean {
        val supplied = authorization
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?: return false
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8),
        )
    }

    fun isAuthorized(token: String, authorizationHeaders: List<String>): Boolean {
        if (authorizationHeaders.size != 1) return false
        val header = authorizationHeaders.single()
        if (!header.startsWith("Authorization:", ignoreCase = true)) return false
        return isAuthorized(token, header.substringAfter(':').trim())
    }
}

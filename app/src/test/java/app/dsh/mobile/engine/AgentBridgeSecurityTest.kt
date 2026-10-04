package app.dsh.mobile.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBridgeSecurityTest {

    @Test
    fun tokensAreUniqueAndUrlSafe() {
        val first = AgentBridgeSecurity.newToken()
        val second = AgentBridgeSecurity.newToken()

        assertNotEquals(first, second)
        assertTrue(first.length >= 40)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun onlyExactBearerTokenIsAuthorized() {
        val token = AgentBridgeSecurity.newToken()

        assertTrue(AgentBridgeSecurity.isAuthorized(token, "Bearer $token"))
        assertFalse(AgentBridgeSecurity.isAuthorized(token, null))
        assertFalse(AgentBridgeSecurity.isAuthorized(token, token))
        assertFalse(AgentBridgeSecurity.isAuthorized(token, "Bearer wrong"))
        assertFalse(AgentBridgeSecurity.isAuthorized(token, "bearer $token"))
        assertFalse(AgentBridgeSecurity.isAuthorized(token, "Bearer $token-extra"))
    }

    @Test
    fun rejectsMissingMalformedAndDuplicateAuthorizationHeaders() {
        val token = AgentBridgeSecurity.newToken()

        assertTrue(AgentBridgeSecurity.isAuthorized(token, listOf("Authorization: Bearer $token")))
        assertFalse(AgentBridgeSecurity.isAuthorized(token, emptyList()))
        assertFalse(AgentBridgeSecurity.isAuthorized(token, listOf("Authorization Bearer $token")))
        assertFalse(
            AgentBridgeSecurity.isAuthorized(
                token,
                listOf("Authorization: Bearer $token", "Authorization: Bearer $token"),
            ),
        )
    }
}

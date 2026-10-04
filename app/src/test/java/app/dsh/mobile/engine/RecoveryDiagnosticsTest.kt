package app.dsh.mobile.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryDiagnosticsTest {

    @Test
    fun reportRedactsWebTokensAndApiKeys() {
        val report = RecoveryDiagnostics.report(
            engineState = "Healthy(tokenUrl=http://127.0.0.1:3080/?token=private-token)",
            runtimeVersion = "runtime-1",
            archives = emptyList(),
            logTail = "authorization: Bearer very-secret-token sk-12345678901234567890",
        )

        assertTrue(report.contains("token=[REDACTED]"))
        assertTrue(report.contains("authorization: [REDACTED]"))
        assertTrue(report.contains("[REDACTED_API_KEY]"))
        assertFalse(report.contains("private-token"))
        assertFalse(report.contains("very-secret-token"))
        assertFalse(report.contains("sk-12345678901234567890"))
    }
}

package app.dsh.mobile.engine

internal object RecoveryDiagnostics {

    private val authorization = Regex("(?im)(authorization\\s*:\\s*)[^\\r\\n]+")
    private val keyValueSecret = Regex(
        "(?i)(token|api[_-]?key|authorization|password|secret)(\\s*[=:]\\s*|\\s+)[^\\s&;,\"']+",
    )
    private val urlToken = Regex("(?i)([?&]token=)[^&#\\s]+")
    private val apiKey = Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b")

    fun redact(value: String): String =
        value
            .replace(authorization) { "${it.groupValues[1]}[REDACTED]" }
            .replace(keyValueSecret) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]" }
            .replace(urlToken, "${'$'}1[REDACTED]")
            .replace(apiKey, "[REDACTED_API_KEY]")

    fun report(engineState: String, runtimeVersion: String, archives: List<String>, logTail: String): String =
        buildString {
            appendLine("DSH Mobile recovery report")
            appendLine("Engine state: ${redact(engineState)}")
            appendLine("Runtime version: ${redact(runtimeVersion)}")
            appendLine("Safe-mode archives: ${archives.joinToString().ifEmpty { "none" }}")
            appendLine()
            appendLine("Recent engine log (automatically redacted):")
            appendLine(redact(logTail))
        }
}

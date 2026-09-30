package ai.passioncode.fabricvr.assistant

/**
 * OpenRouter's stream is ordinary SSE with one habit worth naming: it sends comment lines
 * (`: OPENROUTER PROCESSING`) to keep the connection warm. A parser that feeds those to a JSON
 * reader fails on the first slow answer, so comments are classified rather than parsed.
 */
object SseParser {

    sealed interface Parsed {
        data class Data(val json: String) : Parsed
        data object DoneMarker : Parsed
        data object Comment : Parsed
        data object Ignore : Parsed
    }

    fun event(line: String): Parsed {
        val trimmed = line.trimEnd('\r')
        return when {
            trimmed.isBlank() -> Parsed.Ignore
            trimmed.startsWith(":") -> Parsed.Comment
            trimmed.startsWith("data:") -> {
                val payload = trimmed.removePrefix("data:").trim()
                if (payload == "[DONE]") Parsed.DoneMarker else Parsed.Data(payload)
            }
            else -> Parsed.Ignore
        }
    }
}

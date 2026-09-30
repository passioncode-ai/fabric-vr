package ai.passioncode.fabricvr.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test fun `an OpenRouter keep-alive is a comment, not data`() {
        assertTrue(SseParser.event(": OPENROUTER PROCESSING") is SseParser.Parsed.Comment)
        assertTrue(SseParser.event(":") is SseParser.Parsed.Comment)
    }

    @Test fun `the done marker ends the stream`() {
        assertEquals(SseParser.Parsed.DoneMarker, SseParser.event("data: [DONE]"))
    }

    @Test fun `a data line yields its json`() {
        val parsed = SseParser.event("""data: {"choices":[{"delta":{"content":"hi"}}]}""")
        assertEquals("""{"choices":[{"delta":{"content":"hi"}}]}""", (parsed as SseParser.Parsed.Data).json)
    }

    @Test fun `blank lines and unknown fields are ignored`() {
        assertTrue(SseParser.event("") is SseParser.Parsed.Ignore)
        assertTrue(SseParser.event("event: message") is SseParser.Parsed.Ignore)
    }

    @Test fun `a trailing carriage return does not break the marker`() {
        assertEquals(SseParser.Parsed.DoneMarker, SseParser.event("data: [DONE]\r"))
    }
}

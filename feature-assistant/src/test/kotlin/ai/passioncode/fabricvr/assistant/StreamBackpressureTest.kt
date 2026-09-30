package ai.passioncode.fabricvr.assistant

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A stream that outruns its reader must queue, never drop. With the default rendezvous-ish buffer
 * a `trySend` from the OkHttp callback silently loses tokens, and a lost token in the middle of an
 * answer is invisible — the text simply reads wrong.
 */
class StreamBackpressureTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    @Test fun `every token survives a slow collector`() = runTest {
        val count = 400
        val body = buildString {
            repeat(count) { i -> appendLine("""data: {"choices":[{"delta":{"content":"$i "}}]}""") }
            appendLine("data: [DONE]")
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))

        val client = OpenRouterClient(
            apiKey = { "k" },
            baseUrl = server.url("/v1").toString().trimEnd('/'),
        )
        val tokens = mutableListOf<String>()
        client.stream(Models.DEFAULT, listOf(ChatMessage.user("go"))).toList()
            .filterIsInstance<StreamEvent.Token>()
            .forEach { tokens.add(it.text) }

        assertEquals(count, tokens.size)
        assertEquals("0 ", tokens.first())
        assertEquals("${count - 1} ", tokens.last())
    }
}

package ai.passioncode.fabricvr.assistant

import ai.passioncode.fabricvr.common.AppError
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The three ways a 200 can still be a failure. Each of them used to end as a silently truncated or
 * empty answer, which is the worst shape an assistant can have: wrong, and confident.
 */
class StreamFailureTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun client() =
        OpenRouterClient(apiKey = { "k" }, baseUrl = server.url("/v1").toString().trimEnd('/'))

    private fun sse(vararg lines: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(lines.joinToString("\n", postfix = "\n"))

    @Test fun `an error frame inside a 200 stream surfaces as an OpenRouter failure`() = runTest {
        server.enqueue(
            sse(
                """data: {"choices":[{"delta":{"content":"partial"}}]}""",
                """data: {"error":{"code":502,"message":"provider went away"}}""",
            ),
        )
        var error: AppError? = null
        val events = client().stream(Models.DEFAULT, listOf(ChatMessage.user("hi")))
            .catch { error = (it as AssistantException).error }
            .toList()

        assertEquals("partial", events.filterIsInstance<StreamEvent.Token>().single().text)
        val failure = error as AppError.OpenRouter
        assertEquals(502, failure.status)
        assertEquals("provider went away", failure.message)
    }

    @Test fun `a stream that ends without a finish is a network failure, not a complete answer`() = runTest {
        server.enqueue(sse("""data: {"choices":[{"delta":{"content":"half an ans"}}]}"""))

        var error: AppError? = null
        val events = client().stream(Models.DEFAULT, listOf(ChatMessage.user("hi")))
            .catch { error = (it as AssistantException).error }
            .toList()

        assertTrue("a truncated answer must not report Done", events.none { it is StreamEvent.Done })
        assertTrue(error is AppError.Network)
    }

    @Test fun `a finish_reason without DONE still completes`() = runTest {
        server.enqueue(
            sse(
                """data: {"choices":[{"delta":{"content":"all of it"},"finish_reason":"stop"}]}""",
            ),
        )
        val events = client().stream(Models.DEFAULT, listOf(ChatMessage.user("hi"))).toList()

        assertTrue(events.last() is StreamEvent.Done)
    }

    @Test fun `a rate limit is retried once, honouring Retry-After`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
                .setBody("""{"error":{"code":429,"message":"slow down"}}"""),
        )
        server.enqueue(sse("""data: {"choices":[{"delta":{"content":"second try"}}]}""", "data: [DONE]"))

        val events = client().stream(Models.DEFAULT, listOf(ChatMessage.user("hi"))).toList()

        assertEquals("second try", events.filterIsInstance<StreamEvent.Token>().single().text)
        assertEquals("the retry must actually reach the provider", 2, server.requestCount)
    }
}

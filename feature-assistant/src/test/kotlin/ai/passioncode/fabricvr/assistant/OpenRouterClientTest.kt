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

class OpenRouterClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun client(key: String? = "test-key") =
        OpenRouterClient(apiKey = { key }, baseUrl = server.url("/v1").toString().trimEnd('/'))

    private fun sse(vararg lines: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(lines.joinToString("\n", postfix = "\n"))

    @Test fun `tokens arrive in order and keep-alives are skipped`() = runTest {
        server.enqueue(
            sse(
                ": OPENROUTER PROCESSING",
                """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
                ": OPENROUTER PROCESSING",
                """data: {"choices":[{"delta":{"content":"lo"}}]}""",
                """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":3}}""",
                "data: [DONE]",
            ),
        )

        val events = client().stream(Models.DEFAULT, listOf(ChatMessage.user("hi"))).toList()

        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<StreamEvent.Token>().map { it.text })
        assertEquals(StreamEvent.Usage(12, 3), events.filterIsInstance<StreamEvent.Usage>().single())
        assertTrue(events.last() is StreamEvent.Done)
    }

    @Test fun `no key fails before any request is made`() = runTest {
        var error: AppError? = null
        client(key = null).stream(Models.DEFAULT, listOf(ChatMessage.user("hi")))
            .catch { error = (it as AssistantException).error }
            .toList()

        assertEquals(AppError.NoApiKey, error)
        assertEquals("no request must reach the provider", 0, server.requestCount)
    }

    @Test fun `a rejected key is reported as 401`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":401,"message":"No auth credentials found"}}"""),
        )
        var error: AppError? = null
        client().stream(Models.DEFAULT, listOf(ChatMessage.user("hi")))
            .catch { error = (it as AssistantException).error }
            .toList()

        val openRouter = error as AppError.OpenRouter
        assertEquals(401, openRouter.status)
        assertEquals("No auth credentials found", openRouter.message)
    }

    @Test fun `exhausted credits are final and are not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"code":402,"message":"credits"}}"""))

        var error: AppError? = null
        client().stream(Models.DEFAULT, listOf(ChatMessage.user("a")))
            .catch { error = (it as AssistantException).error }.toList()

        assertEquals(402, (error as AppError.OpenRouter).status)
        assertEquals("credits do not come back by asking twice", 1, server.requestCount)
    }

    @Test fun `a rate limit that survives its one retry keeps its status`() = runTest {
        // Transient statuses get exactly one more chance; a second refusal is the answer.
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
                    .setBody("""{"error":{"code":429,"message":"slow down"}}"""),
            )
        }

        var error: AppError? = null
        client().stream(Models.DEFAULT, listOf(ChatMessage.user("a")))
            .catch { error = (it as AssistantException).error }.toList()

        assertEquals(429, (error as AppError.OpenRouter).status)
        assertEquals(2, server.requestCount)
    }

    @Test fun `the request carries the key and the attribution headers`() = runTest {
        server.enqueue(sse("data: [DONE]"))
        client().stream(Models.FAST, listOf(ChatMessage.user("hi"))).toList()

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertEquals("Fabric VR", recorded.getHeader("X-Title"))
        assertTrue(recorded.body.readUtf8().contains(Models.FAST))
    }
}

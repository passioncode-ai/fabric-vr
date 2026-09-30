package ai.passioncode.fabricvr.assistant

import java.net.InetAddress
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `B-197`. **The assistant was the third `NetworkPolicy` caller and the one with no hop check.**
 *
 * `DEC-0077` put a network interceptor on every client in `:feature-stt`, so a `307` could no
 * longer move a recording or a transcription key to a public host in the clear. [OpenRouterClient]
 * built its own `OkHttpClient` in a different module and followed redirects unchecked — and what
 * travels on this one is the **context**: `NotesContextBuilder` packs up to 12 KB of the person's
 * own notes into every request, under an `Authorization` header carrying their OpenRouter key.
 *
 * `DEC-0020` unlinked `:feature-assistant` from `:app`, so nothing installable reaches this today.
 * That is why it was a row rather than a blocker, and it is also why the test had to be written
 * now: the day the module is linked again is the day nobody remembers the row.
 *
 * **"Somewhere else" is a NAME here, not a network** — the same device under a different name,
 * resolved through [loopback], exactly as `RedirectPolicyTest` does it in `:feature-stt`. Before
 * the fix `elsewhere` received the prompt; after it, its request count is zero.
 */
class AssistantRedirectPolicyTest {

    /** The provider address the client is configured with. */
    private lateinit var origin: MockWebServer

    /** Wherever the redirect points — the same machine, under a different name. */
    private lateinit var elsewhere: MockWebServer

    @Before fun setUp() {
        origin = MockWebServer().also { it.start() }
        elsewhere = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        origin.shutdown()
        elsewhere.shutdown()
    }

    private val loopback = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName("127.0.0.1"))
    }

    private fun client() = OpenRouterClient.SHARED.newBuilder().dns(loopback).build()

    /**
     * A `307` preserves method and body, so the whole context is re-POSTed to whatever the
     * provider names.
     */
    @Test fun `a redirect off the private network never receives the prompt`() = runTest {
        origin.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", "http://assistant.example.com:${elsewhere.port}/v1/chat/completions"),
        )
        elsewhere.enqueue(MockResponse().setBody("data: [DONE]\n\n"))

        val thrown = mutableListOf<Throwable>()
        OpenRouterClient(
            apiKey = { "sk-or-test" },
            client = client(),
            baseUrl = origin.url("/").toString().trimEnd('/'),
        )
            .stream("openai/gpt-4o-mini", listOf(ChatMessage("user", "что я записал вчера")))
            .catch { thrown += it }
            .toList()

        assertEquals(
            "the person's notes and their key were re-POSTed in the clear to a host nothing had checked",
            0,
            elsewhere.requestCount,
        )
        assertTrue("a refused hop reported success: $thrown", thrown.isNotEmpty())
    }

    /**
     * The other half of `DEC-0005`, and the reason this is a policy rather than a ban: a hop that
     * stays inside the person's own network is followed exactly as before, so a local gateway in
     * front of the provider keeps working.
     */
    @Test fun `a redirect that stays on the private network is followed`() = runTest {
        origin.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", "http://gateway.local:${elsewhere.port}/v1/chat/completions"),
        )
        elsewhere.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"готово\"}}]}\n\ndata: [DONE]\n\n",
            ),
        )

        val events = OpenRouterClient(
            apiKey = { "sk-or-test" },
            client = client(),
            baseUrl = origin.url("/").toString().trimEnd('/'),
        )
            .stream("openai/gpt-4o-mini", listOf(ChatMessage("user", "привет")))
            .toList()

        assertEquals("the LAN redirect was refused", 1, elsewhere.requestCount)
        assertTrue(
            "the answer did not arrive through the followed redirect: $events",
            events.any { it is StreamEvent.Token && it.text == "готово" },
        )
    }
}

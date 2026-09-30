package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.notes.SttSource
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Transcription through an OpenAI-compatible endpoint: Groq, OpenAI, or a self-hosted
 * faster-whisper. One client covers all three because they agree on the route and the form.
 */
class CloudTranscriptionClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun client(key: String? = "k-123", model: String = "whisper-large-v3") =
        CloudTranscriptionClient(
            baseUrl = server.url("/").toString().removeSuffix("/"),
            apiKey = { key },
            model = model,
        )

    private val pcm = ShortArray(16_000) { (it % 100).toShort() }

    @Test fun `it posts the audio to the OpenAI transcription route and returns the text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"привет из гарнитуры","language":"russian"}"""))

        val transcript = client().transcribe(pcm, 16_000, langHint = "ru").getOrThrow()

        val request = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer k-123", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue("the model must be named", body.contains("whisper-large-v3"))
        assertTrue("the audio must be sent as a file part", body.contains("filename=\"audio.wav\""))
        assertTrue("a pinned language must reach the provider", body.contains("name=\"language\""))
        assertEquals("привет из гарнитуры", transcript.text)
        assertEquals(SttSource.REMOTE, transcript.source)
    }

    @Test fun `auto detection sends no language at all`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"hello"}"""))

        client().transcribe(pcm, 16_000, langHint = "auto").getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(
            "sending language=auto would be read as a language called 'auto'",
            !body.contains("name=\"language\""),
        )
    }

    @Test fun `the provider's own language answer is carried back, normalised`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"hi","language":"english"}"""))

        val transcript = client().transcribe(pcm, 16_000, langHint = "auto").getOrThrow()

        assertEquals("en", transcript.language)
    }

    @Test fun `a rejected key is reported as 401 rather than as silence`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val failure = client().transcribe(pcm, 16_000, langHint = "auto").exceptionOrNull()

        val error = (failure as SttException).error
        assertTrue(error is AppError.RemoteStt && error.status == 401)
    }

    @Test fun `no key means no request is made`() = runTest {
        val failure = client(key = null).transcribe(pcm, 16_000, langHint = "auto").exceptionOrNull()

        assertEquals("a request was sent without a key", 0, server.requestCount)
        assertTrue((failure as SttException).error is AppError.NoApiKey)
    }

    @Test fun `a cleartext endpoint on the open internet is refused before any audio leaves`() {
        val failure = runCatching {
            CloudTranscriptionClient(
                baseUrl = "http://speech.example.com",
                apiKey = { "k" },
                model = "whisper-large-v3",
            )
        }.exceptionOrNull()

        assertTrue("recorded speech may not travel in the clear", failure != null)
    }
}

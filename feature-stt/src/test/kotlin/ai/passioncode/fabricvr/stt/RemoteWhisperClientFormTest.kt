package ai.passioncode.fabricvr.stt

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `H7`: **the SERVER provider decoded everything as English.**
 *
 * whisper-server's `whisper_params.language` is initialised to `"en"`
 * (`third_party/whisper.cpp/examples/server/server.cpp:113`) and is only overwritten when the
 * request carries a `language` form field (`:558-560`). This client sent `file`, `temperature`
 * and `response_format` and nothing else, so a Russian dictation through a whisper-server came
 * back as English phonetics — while the transcript was labelled with the *hint*, so the interface
 * confidently said `ru` over text that had been decoded as `en`.
 *
 * The two cases are one rule with two values: a pinned language is sent as itself, and no pinned
 * language is sent as `"auto"` — the value `whisper.h` documents for detection, and the value the
 * JNI bridge already passes on the local path (`feature-stt.md`, "Owns"). Unlike
 * [CloudTranscriptionClient], which must *omit* the field because an OpenAI-compatible provider
 * would read `auto` as a language named auto, whisper-server understands the word.
 */
class RemoteWhisperClientFormTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun client() = RemoteWhisperClient(baseUrl = server.url("/").toString().removeSuffix("/"))

    private val pcm = ShortArray(1_600) { (it % 100).toShort() }

    /**
     * The value of the `language` part, read out of the multipart body the server received.
     *
     * The part's headers are skipped by matching to the blank line rather than assuming there is
     * exactly one: OkHttp writes `Content-Length` after `Content-Disposition` whenever the part's
     * length is known, which a string part's always is.
     */
    private fun sentLanguage(body: String): String? =
        Regex("""name="language"(?:\r?\n[^\r\n]+)*\r?\n\r?\n(.*?)\r?\n--""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1)

    @Test fun `a pinned language reaches whisper-server as the language field`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"привет из гарнитуры"}"""))

        val transcript = client().transcribe(pcm, 16_000, langHint = "ru").getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        assertTrue("no language part at all: the server keeps its own default of \"en\"", body.contains("name=\"language\""))
        assertEquals("the hint did not reach the server, so Russian was decoded as English", "ru", sentLanguage(body))
        assertEquals("привет из гарнитуры", transcript.text)
    }

    /**
     * No hint is **not** "let the server decide" unless we say so: the server's own default is
     * `"en"`, so silence here is a decision to decode English. `"auto"` is the word whisper.cpp
     * documents for detection.
     */
    @Test fun `no hint is sent as auto rather than left to the server's English default`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"hello"}"""))

        client().transcribe(pcm, 16_000, langHint = null).getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        assertEquals("silence is decoded as English by whisper-server", "auto", sentLanguage(body))
    }

    /** A blank hint is the same absence of a choice as a null one, and must not be sent as `""`. */
    @Test fun `a blank hint is auto too, not an empty language`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"hello"}"""))

        client().transcribe(pcm, 16_000, langHint = "  ").getOrThrow()

        assertEquals("auto", sentLanguage(server.takeRequest().body.readUtf8()))
    }
}

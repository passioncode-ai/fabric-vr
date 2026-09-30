package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **A 200 that is not a transcript is not what the person said** (`B-244`, `DEC-0091`).
 *
 * Both remote clients read `text` from the JSON and fell back to the raw body with `?: body`, so a
 * captive portal's HTML, a proxy's error page or an unrelated JSON object was saved as the note.
 * And the successful body was read under the 64 KiB ceiling meant for error bodies, so a long
 * transcript arrived truncated, failed to parse, and the same fallback wrote half a JSON document
 * into the note.
 *
 * The two clients differ in route and form (`RemoteWhisperClientFormTest`,
 * `CloudTranscriptionClientTest`); the rule under test is the same for both, so each case runs
 * against each.
 */
class RemoteTranscriptShapeTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private val pcm = ShortArray(1_600) { (it % 100).toShort() }

    private fun base() = server.url("/").toString().removeSuffix("/")

    private val clients: List<Pair<String, () -> SttEngine>> = listOf(
        "whisper-server" to { RemoteWhisperClient(baseUrl = base()) },
        "cloud" to { CloudTranscriptionClient(baseUrl = base(), apiKey = { "k" }, model = "m") },
    )

    private suspend fun each(body: String, check: (String, Result<ai.passioncode.fabricvr.notes.Transcript>) -> Unit) {
        clients.forEach { (name, make) ->
            server.enqueue(MockResponse().setBody(body))
            check(name, make().transcribe(pcm, 16_000, langHint = "ru"))
        }
    }

    private fun unreadable(name: String, result: Result<*>) {
        val error = (result.exceptionOrNull() as? SttException)?.error
        assertTrue(
            "$name saved something that is not a transcript as the note: ${result.getOrNull()}",
            error is AppError.RemoteSttUnreadable,
        )
        assertEquals(200, (error as AppError.RemoteSttUnreadable).status)
    }

    @Test fun `a captive portal's page is refused, not transcribed`() = runTest {
        each("<html><body>Sign in to the hotel Wi-Fi</body></html>") { name, result -> unreadable(name, result) }
    }

    @Test fun `a JSON answer with no text is refused`() = runTest {
        each("""{"error":null,"status":"ok"}""") { name, result -> unreadable(name, result) }
    }

    @Test fun `a text field that is not a string is refused`() = runTest {
        each("""{"text":{"segments":[]}}""") { name, result -> unreadable(name, result) }
    }

    @Test fun `a long transcript arrives whole`() = runTest {
        // 100 kB of text, well past the 64 KiB error-body ceiling the success path used to read under.
        val long = "слово ".repeat(10_000).trim()
        each("""{"text":"$long"}""") { name, result ->
            assertEquals("$name truncated a long transcript", long, result.getOrThrow().text)
        }
    }

    @Test fun `an empty transcript is still a transcript`() = runTest {
        // Silence transcribes to "", and "Nothing was heard" is decided downstream — an empty
        // string is an answer, not a malformed one.
        each("""{"text":""}""") { name, result ->
            assertEquals("$name refused an empty but well-formed transcript", "", result.getOrThrow().text)
        }
    }
}

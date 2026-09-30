package ai.passioncode.fabricvr.stt

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **The call's deadline grows with the recording** (`B-243`, `DEC-0091`).
 *
 * `RemoteWhisperClient` gave every call 60 s and `CloudTranscriptionClient` 120 s, while a dictation
 * may run ten minutes (`VoiceViewModel.MAX_SECONDS`). Uploading ~19 MB and decoding ten minutes on
 * a home whisper-server does not fit in a minute, so every long dictation timed out and fell back to
 * the headset — the person had chosen their own server, and it was never used for exactly the
 * dictations it exists for.
 *
 * Read through an interceptor rather than by waiting: `Call.timeout()` is what OkHttp enforces, so
 * the value it carries is the claim, and a real-time test would take the minutes it is about.
 */
class RemoteCallTimeoutTest {

    private lateinit var server: MockWebServer
    private var seenNanos = -1L

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private val probe = Interceptor { chain ->
        seenNanos = chain.call().timeout().timeoutNanos()
        chain.proceed(chain.request())
    }

    private fun base() = server.url("/").toString().removeSuffix("/")

    private fun seconds(pcm: ShortArray) = TimeUnit.NANOSECONDS.toSeconds(seenNanos) to pcm.size / 16_000

    @Test fun `a ten-minute dictation gets a deadline longer than ten minutes, on both clients`() = runTest {
        val tenMinutes = ShortArray(16_000 * 600)
        listOf(
            RemoteWhisperClient(base(), RemoteWhisperClient.SHARED.newBuilder().addInterceptor(probe).build()),
            CloudTranscriptionClient(base(), apiKey = { "k" }, model = "m",
                client = CloudTranscriptionClient.SHARED.newBuilder().addInterceptor(probe).build()),
        ).forEach { client ->
            seenNanos = -1
            server.enqueue(MockResponse().setBody("""{"text":"ok"}"""))
            client.transcribe(tenMinutes, 16_000, langHint = "ru").getOrThrow()
            val (deadline, audio) = seconds(tenMinutes)
            assertTrue("${client.name}: a $audio s dictation was given $deadline s", deadline > audio)
        }
    }

    @Test fun `a short dictation keeps the floor`() = runTest {
        val twoSeconds = ShortArray(16_000 * 2)
        server.enqueue(MockResponse().setBody("""{"text":"ok"}"""))
        RemoteWhisperClient(base(), RemoteWhisperClient.SHARED.newBuilder().addInterceptor(probe).build())
            .transcribe(twoSeconds, 16_000, langHint = "ru").getOrThrow()
        assertEquals(RemoteDeadline.FLOOR_SECONDS, TimeUnit.NANOSECONDS.toSeconds(seenNanos))
    }

    /**
     * **The read timeout must not undercut the deadline** (found by the seam-tier verification of
     * `B-243`). Both clients set only `callTimeout`; `FabricHttp.builder()` sets no read timeout,
     * so OkHttp's default 10 s applied — and whisper-server sends nothing until it has finished
     * decoding. Every decode longer than ten seconds timed out and fell back, before and after the
     * deadline change, while the test above read only `call.timeout()` and passed.
     */
    @Test fun `neither client's read timeout cuts a silent decode short`() = runTest {
        var readMillis = -1
        val readProbe = Interceptor { chain -> readMillis = chain.readTimeoutMillis(); chain.proceed(chain.request()) }
        listOf(
            RemoteWhisperClient(base(), RemoteWhisperClient.SHARED.newBuilder().addInterceptor(readProbe).build()),
            CloudTranscriptionClient(base(), apiKey = { "k" }, model = "m",
                client = CloudTranscriptionClient.SHARED.newBuilder().addInterceptor(readProbe).build()),
        ).forEach { client ->
            readMillis = -1
            server.enqueue(MockResponse().setBody("""{"text":"ok"}"""))
            client.transcribe(ShortArray(16_000), 16_000, langHint = "ru").getOrThrow()
            assertEquals("${client.name}: a read timeout of $readMillis ms fires inside a decode", 0, readMillis)
        }
    }

    /**
     * And one case against the world rather than the rule (`SI-09`): a server that says nothing for
     * eleven seconds — past OkHttp's default — before answering. Real time, once, on one client.
     */
    @Test fun `a server that thinks for eleven seconds is still heard`() = runTest {
        server.enqueue(MockResponse().setHeadersDelay(11, TimeUnit.SECONDS).setBody("""{"text":"дождались"}"""))
        val transcript = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            RemoteWhisperClient(base()).transcribe(ShortArray(16_000), 16_000, langHint = "ru")
        }
        assertEquals("дождались", transcript.getOrThrow().text)
    }
}

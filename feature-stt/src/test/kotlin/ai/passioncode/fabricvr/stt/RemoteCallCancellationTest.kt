package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.notes.Transcript
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `H9`: **dismissing the sheet did not stop the upload.**
 *
 * Both remote clients registered `call.cancel()` through
 * `currentCoroutineContext().job.invokeOnCompletion { … }`, and a job blocked inside OkHttp's
 * *synchronous* `execute()` does not complete when it is cancelled — it completes when the call
 * returns. So the handler fired at the end of the very call it was meant to abort: the recording
 * kept uploading for up to the call timeout (60 s for whisper-server, 120 s for the cloud), and
 * the whole time the run held `LocalWhisperOwner`'s mutex, so the person's *next* dictation queued
 * behind a transcription nobody wanted.
 *
 * **`isCanceled()` alone cannot tell the two apart** — the old handler did cancel the call, two
 * minutes late. The clock is the assertion; the flag only says the cancellation reached OkHttp
 * at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCallCancellationTest {

    private lateinit var server: MockWebServer

    @Volatile private var started: Call? = null

    @Before fun setUp() { server = MockWebServer(); server.start(); started = null }
    @After fun tearDown() = server.shutdown()

    private val pcm = ShortArray(1_600) { (it % 100).toShort() }

    private fun base() = server.url("/").toString().removeSuffix("/")

    /** An OkHttp client that hands the test the `Call` the client made, so it can be inspected. */
    private fun recording(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) { started = call }
        })
        .build()

    /**
     * A server that accepts the upload and then sits on the answer — the shape of a stalled
     * endpoint, and the shape that parks a *synchronous* `execute()`.
     *
     * Three seconds, not the sixty a real call timeout allows: `MockWebServer.shutdown()` gives
     * its dispatcher five seconds to drain and throws *"Gave up waiting for queue to shut down"*
     * otherwise, so a longer stall fails every test in `tearDown` regardless of what it proved.
     * Three is far outside the one-second window asserted below, which is all the stall is for.
     */
    private fun stalled() = MockResponse()
        .setHeadersDelay(3, TimeUnit.SECONDS)
        .setBody("""{"text":"nobody is waiting for this"}""")

    /**
     * Real time, not `runTest`'s virtual clock — the property under test is *how long* a
     * cancellation takes, and a virtual clock would report any duration as zero.
     * `AudioRecorderErrorTest` and `ModelDownloadsTest` carry the same wrapper for the same reason.
     */
    private suspend fun assertCancelsPromptly(transcribe: suspend () -> Result<Transcript>) =
        withContext(Dispatchers.Default.limitedParallelism(2)) {
            withTimeout(30_000) {
                val job = launch(Dispatchers.IO) { transcribe() }
                // Cancel only once the request is genuinely in flight: a cancel that lands before
                // the call starts proves nothing while looking exactly as if it did.
                withTimeout(10_000) { while (started == null) delay(10) }
                delay(100)

                val startedAt = System.nanoTime()
                job.cancelAndJoin()
                val ms = (System.nanoTime() - startedAt) / 1_000_000

                assertTrue(
                    "cancelling took $ms ms: the coroutine is parked in a blocking execute() and " +
                        "only the call timeout ends it",
                    ms < 1_000,
                )
                assertTrue("the cancellation never reached OkHttp", started!!.isCanceled())
            }
        }

    @Test fun `cancelling a whisper-server transcription stops the call at once`() = runTest {
        server.enqueue(stalled())
        val engine = RemoteWhisperClient(baseUrl = base(), client = recording())

        assertCancelsPromptly { engine.transcribe(pcm, 16_000, langHint = "ru") }
    }

    @Test fun `cancelling a cloud transcription stops the call at once`() = runTest {
        server.enqueue(stalled())
        val engine = CloudTranscriptionClient(baseUrl = base(), apiKey = { "k-123" }, client = recording())

        assertCancelsPromptly { engine.transcribe(pcm, 16_000, langHint = "ru") }
    }

    /** The ordinary path must still work: an answered call returns its transcript, not a failure. */
    @Test fun `an uncancelled call still returns its transcript`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"это доехало"}"""))
        val engine = RemoteWhisperClient(baseUrl = base(), client = recording())

        val transcript = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(20_000) { engine.transcribe(pcm, 16_000, langHint = "ru").getOrThrow() }
        }

        assertTrue("the enqueue path lost the body: '${transcript.text}'", transcript.text == "это доехало")
    }
}

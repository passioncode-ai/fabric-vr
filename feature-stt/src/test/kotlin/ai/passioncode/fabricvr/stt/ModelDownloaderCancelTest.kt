package ai.passioncode.fabricvr.stt

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **Cancel stops a stalled download now, not after the read timeout** (`B-250`, `B-127`'s residue).
 *
 * The transfer reads the body with blocking calls on an IO thread, and a coroutine cancelled while
 * its thread is parked in `read()` cannot observe the cancellation until the read returns — which,
 * on a stalled connection, is `SHARED`'s 90 s read timeout. `ModelDownloads.cancel` joins the
 * transfer (`M15`), so *Cancel* in Settings sat there for a minute and a half. The call is now
 * cancelled from a child coroutine the moment the transfer's job is, which closes the socket and
 * unparks the read.
 *
 * Real time and a real server, because the defect IS the blocking read (`SI-09`).
 */
class ModelDownloaderCancelTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    @Test fun `cancelling a stalled transfer returns in seconds, not at the read timeout`() = runBlocking {
        val root = temp.newFolder("models")
        val store = object : ModelStore {
            override val modelName = "stalled.bin"
            override val expectedBytes = 1_000_000L
            override val expectedSha256 = ""
            override val downloadUrl = server.url("/model").toString()
            override fun modelFile() = File(root, modelName)
            override fun isPresent() = false
        }
        // One byte every four seconds (short enough for the server to shut down after the test): headers arrive, the body stalls — a dying Wi-Fi link.
        server.enqueue(
            MockResponse().setBody(okio.Buffer().write(ByteArray(1_000_000)))
                .throttleBody(1, 4, TimeUnit.SECONDS),
        )
        val downloader = ModelDownloader(store, freeBytes = { Long.MAX_VALUE }, maxAttempts = 1, retryDelay = {})
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch { downloader.download().collect { } }
        assertNotNull("the transfer never reached the server", server.takeRequest(10, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertTrue("the transfer was not running when Cancel was pressed, so this proves nothing", job.isActive)

        val stopped = withTimeoutOrNull(1_500) { job.cancelAndJoin() }

        assertNotNull("Cancel waited for the blocking read instead of stopping it", stopped)
    }

    /**
     * **A cancel that lands before the headers is a cancel, not a retry** (seam verification of
     * `B-250`). The watcher cancels the call, `execute()` then throws `IOException("Canceled")`,
     * and `runCatchingCancellable` turned that into `Attempt.Retry` — a retry log line, a wait, and
     * a `.part` that `ModelDownloads`' own KDoc says a cancel deletes.
     */
    @Test fun `cancelling before the headers arrive deletes the partial and does not retry`() = runBlocking {
        val root = temp.newFolder("models-headers")
        val store = object : ModelStore {
            override val modelName = "slow-headers.bin"
            override val expectedBytes = 1_000L
            override val expectedSha256 = ""
            override val downloadUrl = server.url("/model").toString()
            override fun modelFile() = File(root, modelName)
            override fun isPresent() = false
        }
        // A partial from an earlier attempt, so "the cancel deleted it" is observable.
        val partial = File(root, "slow-headers.bin.part").apply { writeBytes(ByteArray(10)) }
        server.enqueue(MockResponse().setHeadersDelay(4, TimeUnit.SECONDS).setBody("x"))
        var attempts = 0
        val downloader = ModelDownloader(
            store, freeBytes = { Long.MAX_VALUE }, maxAttempts = 3, retryDelay = { attempts++ },
        )
        val job = CoroutineScope(Dispatchers.IO).launch { downloader.download().collect { } }
        assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
        Thread.sleep(300)

        val stopped = withTimeoutOrNull(1_500) { job.cancelAndJoin() }

        assertNotNull("Cancel waited for the headers", stopped)
        assertEquals("a cancelled transfer was retried", 0, attempts)
        assertTrue("the cancel left the partial behind", !partial.exists())
    }
}

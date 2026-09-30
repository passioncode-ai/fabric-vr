package ai.passioncode.fabricvr.stt

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `I-05`, and its user-visible shape is not "a race sometimes corrupts a download": it is **the
 * download can never succeed** while both screens are on the back stack, and the app blames the
 * file for being corrupt every time. A person reaches that state by doing what they are told —
 * the Today banner says *Download*, and so does Settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadsTest {

    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var root: File

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        // A real scope on real IO: these tests are about a transfer outliving its collector, and
        // a virtual clock cannot observe that.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        root = temp.newFolder("models")
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun storeFor(model: WhisperModel, bytes: Long) = object : ModelStore {
        override val modelName = model.fileName
        override val expectedBytes = bytes
        override val expectedSha256 = ""
        override val downloadUrl = server.url("/" + model.key).toString()
        override fun modelFile() = File(root, modelName)
        override fun isPresent() = modelFile().isFile
    }

    private fun downloads(bytes: Long = 1_000L) =
        ModelDownloads(scope) { model ->
            ModelDownloader(storeFor(model, bytes), freeBytes = { Long.MAX_VALUE })
        }

    private fun body(size: Int, delayMs: Long = 0) = MockResponse()
        .setBody(Buffer().write(ByteArray(size) { 7 }))
        .apply { if (delayMs > 0) setBodyDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS) }

    /**
     * Real time, not `runTest`'s virtual clock.
     *
     * The download runs on a real `Dispatchers.IO` scope by design — that is the property under
     * test — so a bare `withTimeout` inside `runTest` fires instantly at virtual 20 s and every
     * test here failed that way first. `AudioRecorderErrorTest` learned the same thing an hour
     * earlier; it is worth writing down twice because both times the failure looked like the
     * production code being broken.
     */
    private suspend fun <T> realTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(HANG_GUARD_MS) { block() } }

    /**
     * **A guard against a hang, and it asserts nothing** (`B-233`).
     *
     * It was 20 s, and *a start during a cancel waits for the writer instead of opening a second
     * one* went red twice on 2026-09-22 — both times while the machine was running fourteen Gradle
     * workers and two other agents, both times passing in isolation and in a quiet full run of the
     * whole suite. Every verdict in these cases comes from a **positive** signal (the request
     * arrived; the writer took the latch; the flow reached `Failed`), so the only thing this bound
     * can decide is whether the test hangs for ever — and on a loaded machine 20 s of real time is
     * not long for three scheduling handoffs through a real `Dispatchers.IO`.
     *
     * A minute costs nothing when the case passes in well under a second, and it removes a red
     * that says *the machine was busy* while looking like *the download is broken*. What it does
     * **not** fix is the one negative assertion in this class — `SETTLE_SECONDS`, waiting to see
     * that nothing happened — which cannot be made deterministic without a seam inside
     * `ModelDownloads`. `B-233` carries that.
     */


    /**
     * How long this class is willing to wait for a request that a correct implementation cannot
     * send (`B-186`). It bounds the wait, never the verdict — see the case that uses it.
     */
    private companion object {
        const val SETTLE_SECONDS = 5L

        /**
         * A short look for a request that a **correct** implementation has already told us it will
         * not send. It is a belt beside the braces: the wait has been announced, so anything on
         * the wire now is a second transfer regardless of how long we look, and 200 ms is enough
         * for one that is already in flight to arrive. It is no longer the thing the verdict
         * rests on, which is why it shrank from five seconds (`B-233`).
         */
        const val SETTLE_MILLIS = 200L
        const val HANG_GUARD_MS = 60_000L
    }

    private suspend fun awaitTerminal(flow: kotlinx.coroutines.flow.StateFlow<DownloadProgress>) =
        realTime { flow.first { it !is DownloadProgress.Running } }

    /** **The headline.** Both screens press Download; one transfer happens. */
    @Test fun `two starts for the same model produce one HTTP request`() = runTest {
        val downloads = downloads()
        server.enqueue(body(1_000, delayMs = 300))

        val fromToday = downloads.start(WhisperModel.SMALL)
        val fromSettings = downloads.start(WhisperModel.SMALL)

        val first = awaitTerminal(fromToday)
        val second = awaitTerminal(fromSettings)
        assertEquals("380 MB of headset Wi-Fi: the second Download started a second transfer", 1, server.requestCount)
        assertTrue("the shared download did not finish: " + first, first is DownloadProgress.Done)
        assertTrue("the second collector never saw the result: " + second, second is DownloadProgress.Done)
    }

    @Test fun `two starts for different models run independently`() = runTest {
        val downloads = downloads()
        server.enqueue(body(1_000))
        server.enqueue(body(1_000))

        val small = downloads.start(WhisperModel.SMALL)
        val tiny = downloads.start(WhisperModel.TINY)

        assertTrue(awaitTerminal(small) is DownloadProgress.Done)
        assertTrue(awaitTerminal(tiny) is DownloadProgress.Done)
        assertEquals(2, server.requestCount)
        assertTrue(File(root, WhisperModel.SMALL.fileName).isFile)
        assertTrue(File(root, WhisperModel.TINY.fileName).isFile)
    }

    /**
     * `I-22`/`A-21`: choosing a different model in Settings used to cancel the running download,
     * and cancellation deletes the partial — a 539 MB transfer gone because the person looked at
     * something else.
     */
    @Test fun `cancelling one model does not touch another's download`() = runTest {
        val downloads = downloads()
        server.enqueue(body(1_000, delayMs = 500))
        server.enqueue(body(1_000))
        val slow = downloads.start(WhisperModel.SMALL)
        downloads.start(WhisperModel.TINY)

        downloads.cancel(WhisperModel.TINY)

        val outcome = awaitTerminal(slow)
        assertTrue("the other model's download was taken down with it: " + outcome, outcome is DownloadProgress.Done)
        assertTrue(File(root, WhisperModel.SMALL.fileName).isFile)
        assertNull("the cancelled model is still listed as running", downloads.progress(WhisperModel.TINY))
    }

    /**
     * `A-22`, and on headset Wi-Fi for 190–574 MB this is the change a person actually notices.
     * The collection used to live in `viewModelScope`; leaving the screen cancelled it and the
     * cancellation deletes the `.part`, so `ModelDownloader`'s resume path — a `Range` header, a
     * 206-vs-200 check, a digest replay over the existing bytes — could only ever engage after a
     * process death.
     */
    @Test fun `a download survives the collector going away`() = runTest {
        val downloads = downloads(bytes = 4_000L)
        server.enqueue(body(4_000, delayMs = 400))

        val progress = downloads.start(WhisperModel.SMALL)
        // Collect once and stop, which is what navigating away does.
        realTime { progress.first() }

        assertTrue("the transfer stopped when nobody was watching", awaitTerminal(progress) is DownloadProgress.Done)
        assertTrue(File(root, WhisperModel.SMALL.fileName).isFile)
    }

    /** The `invokeOnCompletion` removal must not leave a dead entry that blocks a retry. */
    @Test fun `a model can be started again after being cancelled`() = runTest {
        val downloads = downloads()
        server.enqueue(body(1_000, delayMs = 2_000))
        server.enqueue(body(1_000))

        downloads.start(WhisperModel.SMALL)
        // Wait for the transfer to be genuinely in flight before cancelling it. Without this the
        // cancel can land before the request is even made, and then the test asserts nothing
        // about cancellation while looking exactly as if it did.
        val firstRequest = realTime {
            withContext(Dispatchers.IO) { server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS) }
        }
        assertNotNull("the first download never reached the server", firstRequest)
        downloads.cancel(WhisperModel.SMALL)

        val second = downloads.start(WhisperModel.SMALL)

        val outcome = awaitTerminal(second)
        assertTrue("the retry did not finish: " + outcome, outcome is DownloadProgress.Done)
        assertNotNull(
            "the retry never reached the server — a completed entry is still in the map",
            realTime { withContext(Dispatchers.IO) { server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS) } },
        )
    }

    /**
     * `M15`: **the join happened outside the lock, so two writers could hold the same `.part`.**
     *
     * `cancel` removed the entry under `lock` (`:107`) and only then ran `cancelAndJoin` (`:125`),
     * and a cancelled transfer does not stop when it is told — it stops when its blocking
     * `input.read()`/`output.write()` returns and the loop next checks `ensureActive()`. In that
     * window `start` for the same model found an empty map, built a second `ModelDownloader` and
     * opened a second writer on the same file. The dying one then ran `partial.delete()`
     * underneath it: the new download's bytes went to an unlinked inode, its `renameTo` failed,
     * and the person was told **"There isn't room on this headset"** on a headset with plenty.
     *
     * The stall here is a blocking `write`, because that is exactly what cancellation cannot
     * interrupt — a fake that stopped politely would leave the window untested.
     *
     * **`B-186`: there is no wall clock in this case any more, and that is the whole of the
     * change.** It used to assert the sink count after `realTime { delay(500) }` — a green that
     * said *nothing else happened in half a second*, which is a claim about the machine rather
     * than about the code, and it lost the race once in a full multi-module run and never
     * reproduced in nine more.
     *
     * What replaced it is a **handshake against a latch this test holds**, and the argument for
     * why its remaining bound cannot make a red into a green is worth stating, because "there is
     * still a timeout in it" is the obvious objection:
     *
     * - The dying writer is parked inside a blocking `write` on [gate]. **Only this test can
     *   release it.**
     * - A correct `start` therefore *cannot* build a second downloader, *cannot* make a second
     *   request and *cannot* open a second sink, however slow or fast the machine is — it is
     *   blocked on a happens-before edge, not on a duration.
     * - So [SETTLE_SECONDS] is a bound on how long we are willing to wait for a request that a
     *   correct implementation can never send. A loaded machine makes the wait *longer* and the
     *   verdict identical; it can only ever weaken the detection of the defect, never invent a
     *   failure. The old sleep had it the other way round.
     *
     * **`maxAttempts = 1`, and that is not tidying.** The downloader's own retry opens a second
     * sink on the same `.part` by design (`H8`), so a first attempt that stalled under load could
     * bump the count this case reads and produce exactly the red that was seen. One attempt makes
     * *a second sink* mean one thing only: a second transfer.
     *
     * The last two assertions are the control. Without them this case passes just as happily when
     * nothing happens at all — and "nothing happened" is precisely what a flaky sleep looks like.
     */
    @Test fun `a start during a cancel waits for the writer instead of opening a second one`() = runTest {
        val gate = java.util.concurrent.CountDownLatch(1)
        val sinks = java.util.concurrent.atomic.AtomicInteger(0)
        // **The positive signal that replaced a five-second look at nothing** (`B-233`). The
        // property is that a second transfer was NOT opened, and the only way to observe an
        // absence is to wait with a number on it — a number that went red twice on a loaded
        // machine while the code was correct. `start` announces that it reached the wait instead.
        val waiting = java.util.concurrent.CountDownLatch(1)
        val parked = java.util.concurrent.CountDownLatch(1)
        val downloads = ModelDownloads(
            scope,
            downloaderFor = { model ->
                ModelDownloader(
                    storeFor(model, 1_000L),
                    freeBytes = { Long.MAX_VALUE },
                    openSink = { file, append -> gatedSink(file, append, gate, sinks, parked) },
                    maxAttempts = 1,
                    retryDelay = {},
                )
            },
            onWaitingForDyingTransfer = { waiting.countDown() },
        )
        server.enqueue(body(1_000))
        server.enqueue(body(1_000))

        val first = downloads.start(WhisperModel.SMALL)
        // Positive signals only, in order: the transfer reached the server, then its writer took
        // the latch. Both must become true, so a regression fails on `realTime`'s own bound with
        // this test's own message rather than passing because a sleep was long enough.
        assertNotNull(
            "the first transfer never reached the server",
            realTime { withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) } },
        )
        // The writer is ON the latch, not merely opened (`B-233`, third red) — see `gatedSink`.
        realTime { withContext(Dispatchers.IO) { parked.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS) } }
        val canceller = scope.launch { downloads.cancel(WhisperModel.SMALL) }
        // `cancel` publishes the ending before it joins, so seeing it means the join is under way
        // and the writer is still alive on the latch — the exact window `M15` is about.
        realTime { first.first { it is DownloadProgress.Failed } }

        val second = scope.async { downloads.start(WhisperModel.SMALL) }

        // **The handshake, and it is positive now.** A correct `start` reaches the wait; a broken
        // one opens a second transfer instead and never announces anything. Bounded by the hang
        // guard rather than by a settle time, because this waits for something that must happen.
        assertTrue(
            "a start during a cancel never reached the wait — it opened a second transfer on the " +
                "same .part while the dying writer still held it (M15)",
            realTime { withContext(Dispatchers.IO) { waiting.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS) } },
        )
        assertNull(
            "the waiting start still put a request on the wire",
            withContext(Dispatchers.IO) { server.takeRequest(SETTLE_MILLIS, TimeUnit.MILLISECONDS) },
        )
        assertEquals(
            "a second writer was opened on the same .part while the first was still alive",
            1,
            sinks.get(),
        )

        gate.countDown()
        val outcome = awaitTerminal(realTime { second.await() })
        assertTrue("the retry never finished once the first writer was gone: $outcome", outcome is DownloadProgress.Done)
        assertTrue(File(root, WhisperModel.SMALL.fileName).isFile)
        assertEquals(
            "the second transfer never opened a writer at all, so the assertions above passed " +
                "because nothing happened rather than because it waited",
            2,
            sinks.get(),
        )
        canceller.join()
    }

    /** A sink that parks its first write until the test releases it, and counts how many opened. */
    private fun gatedSink(
        file: File,
        append: Boolean,
        gate: java.util.concurrent.CountDownLatch,
        sinks: java.util.concurrent.atomic.AtomicInteger,
        /**
         * Counted down the moment a write is **parked on [gate]** — not when the sink opens. The
         * third red of `B-233` (2026-09-23) was the gap between the two: the test cancelled once
         * the sink existed, the cancel landed before the first write, `ensureActive()` ended the
         * transfer correctly, and the start that was waiting for it then opened a second one — a
         * legitimate request the test read as a defect.
         */
        parked: java.util.concurrent.CountDownLatch? = null,
    ): java.io.OutputStream {
        sinks.incrementAndGet()
        return object : java.io.OutputStream() {
            private val real = java.io.FileOutputStream(file, append)
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                parked?.countDown()
                gate.await()
                real.write(b, off, len)
            }
            override fun close() = real.close()
        }
    }

    @Test fun `active lists what is transferring`() = runTest {
        val downloads = downloads()
        server.enqueue(body(1_000, delayMs = 500))

        downloads.start(WhisperModel.SMALL)

        val active = downloads.active()
        assertNotNull("Settings has no way to say another model is downloading", active[WhisperModel.SMALL])
        assertNull(active[WhisperModel.MEDIUM])
    }
}

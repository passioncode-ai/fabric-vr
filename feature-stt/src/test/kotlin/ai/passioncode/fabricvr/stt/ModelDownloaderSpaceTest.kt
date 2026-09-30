package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `G-06`/`G-07`: a headset that is simply full was told **"The download stopped. Check the
 * network."**
 *
 * Every throwable from the write loop was mapped to `Reason.NETWORK`, `ENOSPC` included. So the
 * person checked the network, retried, spent another 190 MB of headset Wi-Fi, failed again, and
 * never learned the cause — while the correct string, `error_model_disk`, sat three lines away
 * reachable only from a failed `renameTo`, which is the *least* likely way to run out of space
 * because source and target share a directory.
 *
 * Five models total 1.395 GB and every dictation keeps its WAV for ever, so a full headset is not
 * a corner case here; it is the expected end state.
 */
class ModelDownloaderSpaceTest {

    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun store(root: File, bytes: Long, sha: String = "") = object : ModelStore {
        override val modelName = "test-model.bin"
        override val expectedBytes = bytes
        override val expectedSha256 = sha
        override val downloadUrl = server.url("/model").toString()
        override fun modelFile() = File(root, modelName)
        override fun isPresent() = modelFile().isFile
    }

    private fun body(size: Int) = MockResponse().setBody(Buffer().write(ByteArray(size) { 7 }))

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The whole complaint is the wasted transfer, so the assertion that matters is
     * `requestCount == 0`: the refusal has to happen **before the first byte is requested**, not
     * after 190 MB have crossed the Wi-Fi.
     */
    @Test fun `a download refuses before the first byte when there is not enough room`() = runTest {
        val root = temp.newFolder("models")
        val downloader = ModelDownloader(store(root, bytes = 1_000L), freeBytes = { 1_000L })
        server.enqueue(body(1_000))

        val events = downloader.download().toList()

        val failed = events.last() as DownloadProgress.Failed
        assertEquals(
            "a full headset was told to check its network",
            AppError.ModelDownload.Reason.DISK,
            (failed.error as AppError.ModelDownload).reason,
        )
        assertEquals("bytes were spent before the refusal — that is the whole defect", 0, server.requestCount)
    }

    /**
     * The margin is ten per cent, not a hundred: a download needing exactly the free space is
     * refused (the filesystem needs metadata, and a headset within ten per cent of full will fail
     * the next thing it does anyway), but one with room to spare must proceed.
     */
    @Test fun `a download with room to spare proceeds`() = runTest {
        val root = temp.newFolder("models")
        val downloader = ModelDownloader(store(root, bytes = 1_000L), freeBytes = { 10_000L })
        server.enqueue(body(1_000))

        val events = downloader.download().toList()

        assertTrue("a download with ten times the room was refused: ${events.last()}", events.last() is DownloadProgress.Done)
        assertEquals(1, server.requestCount)
    }

    /**
     * Resuming needs only what is still missing. Requiring the whole model again would refuse a
     * download that is 95% finished on a headset with 6% free — and `A-22` is that this resume
     * path had never run at all.
     *
     * **The digest is pinned here and the outcome is `Done`.** This test used to run against a
     * blank `expectedSha256` and assert only `!is Failed`, so the one thing the resume path has
     * to get right — that the replayed bytes and the new ones hash to the published digest
     * together — was the one thing it did not check, and a `Running` as the last event would have
     * satisfied it. `ModelDownloaderResumeTest` proves the same property across a *failed*
     * attempt; this one proves it across the space precondition.
     */
    @Test fun `a resumed download only needs the bytes it still lacks`() = runTest {
        val root = temp.newFolder("models")
        val target = File(root, "test-model.bin")
        val whole = ByteArray(1_000) { 7 }
        File(target.parentFile, target.name + ".part").writeBytes(whole.copyOfRange(0, 900))
        // 1000 total, 900 already on disk: 100 missing, 110 needed with the margin.
        val downloader = ModelDownloader(
            store(root, bytes = 1_000L, sha = sha256(whole)),
            freeBytes = { 200L },
        )
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(whole.copyOfRange(900, 1_000))))

        val events = downloader.download().toList()

        val done = events.last() as? DownloadProgress.Done
        assertTrue(
            "a nearly-finished download was refused for want of the space it no longer needs: ${events.last()}",
            done != null,
        )
        assertEquals("the resumed file does not hash to the pinned digest", sha256(whole), done!!.sha256)
        assertEquals("bytes=900-", server.takeRequest().getHeader("Range"))
    }

    /** `G-06`: the form Android actually produces — the errno is in the message. */
    @Test fun `ENOSPC during the write is reported as DISK, not NETWORK`() = runTest {
        val root = temp.newFolder("models")
        val downloader = ModelDownloader(
            store(root, bytes = 1_000L),
            freeBytes = { Long.MAX_VALUE },
            openSink = { _, _ -> throw IOException("write failed: ENOSPC (No space left on device)") },
        )
        server.enqueue(body(1_000))

        val events = downloader.download().toList()

        assertEquals(
            AppError.ModelDownload.Reason.DISK,
            ((events.last() as DownloadProgress.Failed).error as AppError.ModelDownload).reason,
        )
    }

    /**
     * And the form that survives a locale change: `FileOutputStream.write` surfaces `ENOSPC` as an
     * `IOException` whose **cause** is the `ErrnoException`, so a classifier that reads only
     * `t.message` misses it. `G-06`'s own proposal did exactly that.
     */
    @Test fun `an ENOSPC nested as a cause is still DISK`() = runTest {
        val root = temp.newFolder("models")
        val nested = IOException("write failed", IOException("ENOSPC"))
        val downloader = ModelDownloader(
            store(root, bytes = 1_000L),
            freeBytes = { Long.MAX_VALUE },
            openSink = { _, _ -> throw IOException("could not write", nested) },
        )
        server.enqueue(body(1_000))

        val events = downloader.download().toList()

        assertEquals(
            "the classifier reads only the top-level message, so a wrapped ENOSPC is called a network failure",
            AppError.ModelDownload.Reason.DISK,
            ((events.last() as DownloadProgress.Failed).error as AppError.ModelDownload).reason,
        )
    }

    /**
     * The guard against the classifier over-reaching. A classifier that calls everything DISK
     * tells somebody with a dropped connection to free up space, which is the same defect in the
     * other direction.
     *
     * `maxAttempts = 1` in this test and the two below: a `NETWORK` failure is now retried three
     * times (`H8`), so without it the assertion about the *classification* would silently also
     * depend on the server having three answers queued. The retry has its own test.
     */
    @Test fun `a socket failure is still NETWORK`() = runTest {
        val root = temp.newFolder("models")
        val downloader = ModelDownloader(
            store(root, bytes = 1_000L),
            freeBytes = { Long.MAX_VALUE },
            openSink = { _, _ -> throw SocketTimeoutException("timeout") },
            maxAttempts = 1,
        )
        server.enqueue(body(1_000))

        val events = downloader.download().toList()

        assertEquals(
            AppError.ModelDownload.Reason.NETWORK,
            ((events.last() as DownloadProgress.Failed).error as AppError.ModelDownload).reason,
        )
    }

    /** `FileNotFoundException` on an existing directory is usually permissions, never space. */
    @Test fun `a file-not-found is NETWORK, not DISK`() = runTest {
        val root = temp.newFolder("models")
        val downloader = ModelDownloader(
            store(root, bytes = 1_000L),
            freeBytes = { Long.MAX_VALUE },
            openSink = { _, _ -> throw java.io.FileNotFoundException("/models/test-model.bin.part (Permission denied)") },
            maxAttempts = 1,
        )
        server.enqueue(body(1_000))

        val events = downloader.download().toList()

        assertEquals(
            "a permissions failure told the person to free up room — `G-06`'s proposal did this",
            AppError.ModelDownload.Reason.NETWORK,
            ((events.last() as DownloadProgress.Failed).error as AppError.ModelDownload).reason,
        )
    }

    /**
     * Found by the group-verification pass: when the server refuses the range and answers 200,
     * the whole model is coming — but the precondition had only asked for the bytes that were
     * missing. On a nearly-full headset with a nearly-complete partial that is a mid-transfer
     * failure where an up-front refusal was owed.
     */
    @Test fun `a server that refuses the range is checked against the whole model`() = runTest {
        val root = temp.newFolder("models")
        val target = File(root, "test-model.bin")
        File(target.parentFile, target.name + ".part").writeBytes(ByteArray(900) { 7 })
        // 100 missing, so the first check passes with 200 free — but a 200 response re-fetches
        // all 1000, needing 1100, and 200 + the 900 reclaimed is 1100... minus the margin.
        val downloader = ModelDownloader(store(root, bytes = 1_000L), freeBytes = { 150L })
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(1_000) { 7 })))

        val events = downloader.download().toList()

        assertEquals(
            "a full re-fetch was started on a headset that cannot hold it",
            AppError.ModelDownload.Reason.DISK,
            ((events.last() as DownloadProgress.Failed).error as AppError.ModelDownload).reason,
        )
    }

    /**
     * A cause chain that cycles must not spin inside the catch block.
     *
     * Built by overriding `getCause` rather than by reflection: `Throwable.cause` is not open to
     * this module, and the class that overrides it is a fairer model of a library that computes
     * its own cause anyway.
     */
    @Test fun `a cyclic cause chain is classified rather than walked for ever`() = runTest {
        class Looping : IOException("looping") {
            override val cause: Throwable get() = this.other!!
            var other: Throwable? = null
        }
        val a = Looping()
        val b = Looping()
        a.other = b
        b.other = a
        val root = temp.newFolder("models")
        val downloader = ModelDownloader(
            store(root, bytes = 1_000L),
            freeBytes = { Long.MAX_VALUE },
            openSink = { _, _ -> throw b },
            maxAttempts = 1,
        )
        server.enqueue(body(1_000))

        val events = downloader.download().toList()

        assertEquals(
            AppError.ModelDownload.Reason.NETWORK,
            ((events.last() as DownloadProgress.Failed).error as AppError.ModelDownload).reason,
        )
    }
}

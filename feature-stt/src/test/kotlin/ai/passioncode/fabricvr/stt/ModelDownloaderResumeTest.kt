package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `H8`: **a Wi-Fi hiccup threw away the whole model.**
 *
 * `ModelDownloader` deleted the `.part` on *any* `Throwable` from the write loop, so a dropped
 * connection at 560 of 574 MB cost all 574 again. The resume machinery it already carried — the
 * `Range` header, the 206-versus-200 check, the digest replayed over the bytes on disk — could
 * only ever engage after a process death, which is `A-22`: the best-engineered part of that class
 * had never run. `DEC-0033` and the class's own KDoc both said otherwise.
 *
 * Three things are proved here and they are one behaviour: what survives a failure, what the next
 * attempt asks for, and that the file which finally lands still hashes to the pinned digest. The
 * last one is the point — a resume that concatenates the wrong bytes is worse than no resume,
 * because the digest is the only thing standing between the person and a corrupt model.
 */
class ModelDownloaderResumeTest {

    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    /** A thousand bytes that are not all the same value, so a wrong offset changes the digest. */
    private val payload = ByteArray(1_000) { (it * 31 % 251).toByte() }
    private val payloadSha = sha256(payload)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun store(root: File, sha: String = payloadSha, bytes: Long = 1_000L) = object : ModelStore {
        override val modelName = "test-model.bin"
        override val expectedBytes = bytes
        override val expectedSha256 = sha
        override val downloadUrl = server.url("/model").toString()
        override fun modelFile() = File(root, modelName)
        override fun isPresent() = modelFile().isFile
    }

    /**
     * A sink that writes [cap] bytes and then fails the way a dropped connection does.
     *
     * The failure has to happen at a byte count the test chose, not at whatever boundary the
     * socket happened to break on: the assertion is *"exactly what was written is still there"*,
     * and MockWebServer's disconnect policies cannot say how much got through.
     */
    private class CappedSink(file: File, append: Boolean, private val cap: Long) : OutputStream() {
        private val real = FileOutputStream(file, append)
        private var written = 0L

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val room = (cap - written).coerceAtLeast(0L).toInt()
            if (len <= room) {
                real.write(b, off, len)
                written += len
                return
            }
            real.write(b, off, room)
            written += room
            real.flush()
            throw IOException("unexpected end of stream")
        }

        override fun close() = real.close()
    }

    /** Caps the first [failures] attempts and lets any later one write freely. */
    private fun cappedFor(failures: Int, cap: Long): Pair<(File, Boolean) -> OutputStream, AtomicInteger> {
        val opens = AtomicInteger(0)
        val factory = { file: File, append: Boolean ->
            if (opens.getAndIncrement() < failures) CappedSink(file, append, cap)
            else FileOutputStream(file, append) as OutputStream
        }
        return factory to opens
    }

    private fun part(root: File) = File(root, "test-model.bin.part")

    /**
     * (a) The bytes stay. This is the whole of `H8`: `ModelDownloader.kt:197-198` used to run
     * `partial.delete()` for every non-cancellation `Throwable`, and the person was told to check
     * their network and start the 190 MB again.
     */
    @Test fun `a dropped connection keeps the partial file and says it can be resumed`() = runTest {
        val root = temp.newFolder("models")
        val (sink, _) = cappedFor(failures = 1, cap = 400L)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))

        val events = ModelDownloader(
            store(root),
            freeBytes = { Long.MAX_VALUE },
            openSink = sink,
            maxAttempts = 1,
            retryDelay = {},
        ).download().toList()

        val failed = events.last() as DownloadProgress.Failed
        assertEquals(
            "a dropped connection told the person to free up disk space",
            AppError.ModelDownload.Reason.NETWORK,
            (failed.error as AppError.ModelDownload).reason,
        )
        assertTrue("the partial was deleted, so the next press costs the whole model again", part(root).exists())
        assertEquals("the bytes that did arrive were not all kept", 400L, part(root).length())
        assertTrue("the failure does not say the transfer can be resumed", failed.resumable)
    }

    /**
     * (b) And the next attempt uses them. The `Range` header, the 206, and — the part that makes
     * the other two safe — the digest over the **whole** file, replayed across the two attempts.
     */
    @Test fun `the next attempt resumes with a Range header and the whole file still verifies`() = runTest {
        val root = temp.newFolder("models")
        val (sink, _) = cappedFor(failures = 1, cap = 400L)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        server.enqueue(
            MockResponse().setResponseCode(206).setBody(Buffer().write(payload.copyOfRange(400, 1_000))),
        )

        val events = ModelDownloader(
            store(root),
            freeBytes = { Long.MAX_VALUE },
            openSink = sink,
            retryDelay = {},
        ).download().toList()

        val done = events.last() as? DownloadProgress.Done
        assertTrue("the transfer did not survive one dropped connection: ${events.last()}", done != null)
        assertEquals("the resumed file does not hash to the pinned digest", payloadSha, done!!.sha256)
        assertEquals(1_000L, File(root, "test-model.bin").length())
        assertFalse("the partial outlived the download", part(root).exists())

        assertNull("the first attempt asked for a range it had no bytes for", server.takeRequest().getHeader("Range"))
        assertEquals(
            "the retry re-fetched from zero, which is the 190 MB this change exists to stop spending",
            "bytes=400-",
            server.takeRequest().getHeader("Range"),
        )
    }

    /**
     * (c) `416 Range Not Satisfiable` is the server saying the bytes on disk are not a prefix of
     * what it is serving — the model was re-published, or the `.part` is from another file.
     * Resuming onto them would produce a file that fails the digest after a full download, so the
     * only honest move is to drop them and start again, in the same transfer.
     */
    @Test fun `a 416 deletes the partial and starts again from zero`() = runTest {
        val root = temp.newFolder("models")
        part(root).writeBytes(ByteArray(400) { 9 })
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))

        val events = ModelDownloader(
            store(root),
            freeBytes = { Long.MAX_VALUE },
            retryDelay = {},
        ).download().toList()

        val done = events.last() as? DownloadProgress.Done
        assertTrue("a 416 ended the transfer instead of restarting it: ${events.last()}", done != null)
        assertEquals("the stale bytes were kept and corrupted the result", payloadSha, done!!.sha256)
        assertFalse(part(root).exists())

        assertEquals("bytes=400-", server.takeRequest().getHeader("Range"))
        assertNull("the restart asked for a range again", server.takeRequest().getHeader("Range"))
    }

    /**
     * (d) The retry is bounded and it waits. Three attempts is two waits — one second then two —
     * and a retry loop with no wait is a way to fail three times as fast on a network that needs
     * a moment.
     */
    @Test fun `a transport failure is retried three times with a doubling backoff`() = runTest {
        val root = temp.newFolder("models")
        val (sink, opens) = cappedFor(failures = Int.MAX_VALUE, cap = 0L)
        repeat(3) { server.enqueue(MockResponse().setBody(Buffer().write(payload))) }
        val waits = mutableListOf<Long>()

        val events = ModelDownloader(
            store(root),
            freeBytes = { Long.MAX_VALUE },
            openSink = sink,
            retryDelay = { waits += it },
        ).download().toList()

        assertTrue("a retried transfer reported a failure before it had finished trying", events.last() is DownloadProgress.Failed)
        assertEquals("the transfer did not use its whole budget", 3, opens.get())
        assertEquals("the retry did not reach the server three times", 3, server.requestCount)
        assertEquals("three attempts are two waits, doubling", listOf(1_000L, 2_000L), waits)
    }

    /** One failure, then success: the person sees no failure at all, only a finished download. */
    @Test fun `a transfer that recovers never reports a failure`() = runTest {
        val root = temp.newFolder("models")
        val (sink, _) = cappedFor(failures = 1, cap = 400L)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        server.enqueue(
            MockResponse().setResponseCode(206).setBody(Buffer().write(payload.copyOfRange(400, 1_000))),
        )

        val events = ModelDownloader(
            store(root),
            freeBytes = { Long.MAX_VALUE },
            openSink = sink,
            retryDelay = {},
        ).download().toList()

        assertTrue(
            "a recovered transfer flashed a failure at the person: $events",
            events.none { it is DownloadProgress.Failed },
        )
    }

    /**
     * The guard on the other side. A digest that does not match is **not** resumable: those bytes
     * are wrong and keeping them would make every future attempt fail the same way for ever.
     */
    @Test fun `a wrong digest still deletes the partial and is not resumable`() = runTest {
        val root = temp.newFolder("models")
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000) { 3 })))

        val events = ModelDownloader(
            store(root),
            freeBytes = { Long.MAX_VALUE },
            retryDelay = {},
        ).download().toList()

        val failed = events.last() as DownloadProgress.Failed
        assertEquals(
            AppError.ModelDownload.Reason.CHECKSUM,
            (failed.error as AppError.ModelDownload).reason,
        )
        assertFalse("bytes that failed the digest were kept to be resumed on to", part(root).exists())
        assertFalse("a corrupt transfer was offered as resumable", failed.resumable)
    }
}

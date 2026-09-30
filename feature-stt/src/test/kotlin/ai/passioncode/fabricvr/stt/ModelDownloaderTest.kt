package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloaderTest {

    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun store(
        root: File,
        sha: String = "",
        bytes: Long = 64L,
        redirectTo: Set<String> = emptySet(),
    ) = object : ModelStore {
        override val modelName = "test-model.bin"
        override val expectedBytes = bytes
        override val expectedSha256 = sha
        override val downloadUrl = server.url("/model").toString()
        override val allowedRedirectHosts = redirectTo
        override fun modelFile() = File(root, modelName)
        override fun isPresent() = modelFile().exists()
    }

    @Test fun `a good download lands the file and reports progress`() = runTest {
        val payload = ByteArray(64) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val root = temp.newFolder("models")
        val events = ModelDownloader(store(root), url = server.url("/model").toString()).download().toList()

        assertTrue(events.any { it is DownloadProgress.Running })
        val done = events.last() as DownloadProgress.Done
        assertEquals(payload.size.toLong(), done.file.length())
        assertTrue(done.file.exists())
        assertFalse(File(root, "test-model.bin.part").exists())
    }

    @Test fun `a wrong digest removes the file and names the checksum`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64))))
        val root = temp.newFolder("models")
        val wrongSha = "0".repeat(64)
        val events = ModelDownloader(store(root, sha = wrongSha), url = server.url("/model").toString())
            .download().toList()

        val failed = events.last() as DownloadProgress.Failed
        assertEquals(
            AppError.ModelDownload.Reason.CHECKSUM,
            (failed.error as AppError.ModelDownload).reason,
        )
        assertFalse(File(root, "test-model.bin").exists())
        assertFalse(File(root, "test-model.bin.part").exists())
    }

    @Test fun `a body that is not the published size is refused rather than kept`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2))))
        val root = temp.newFolder("models")
        val events = ModelDownloader(store(root, bytes = 1_000L), url = server.url("/model").toString())
            .download().toList()

        assertTrue(events.last() is DownloadProgress.Failed)
        assertFalse(File(root, "test-model.bin").exists())
    }

    @Test fun `cancelling mid-download keeps no half-written model`() = runTest {
        // Throttled so the collector is still reading when the job is cancelled.
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(1_000_000)))
                .throttleBody(4_096, 20, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        val root = temp.newFolder("models")
        val downloader = ModelDownloader(store(root, bytes = 1_000_000L), url = server.url("/model").toString())

        val seen = mutableListOf<DownloadProgress>()
        val job = launch(Dispatchers.IO) { downloader.download().collect { seen.add(it) } }
        withContext(Dispatchers.IO) { Thread.sleep(150) }
        job.cancelAndJoin()

        assertFalse("the model must not exist", File(root, "test-model.bin").exists())
        assertFalse("the partial must be cleaned up", File(root, "test-model.bin.part").exists())
        assertTrue(
            "a cancel must not be reported as a network failure",
            seen.none { it is DownloadProgress.Failed },
        )
    }

    @Test fun `a redirect to another host is refused`() = runTest {
        // Both servers are local, so the two hosts are spelled differently on purpose: `127.0.0.1`
        // and `localhost` have different registrable suffixes, which is exactly the check.
        val elsewhere = MockWebServer().apply { start() }
        elsewhere.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64))))
        val target = elsewhere.url("/model").toString().replace("127.0.0.1", "localhost")
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
        val root = temp.newFolder("models")
        val from = server.url("/model").toString().replace("localhost", "127.0.0.1")

        val events = ModelDownloader(store(root), url = from).download().toList()

        assertTrue("a payload may not be swapped by a redirect", events.last() is DownloadProgress.Failed)
        assertFalse(File(root, "test-model.bin").exists())
        elsewhere.shutdown()
    }

    @Test fun `a server error is reported as a network failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val root = temp.newFolder("models")
        val events = ModelDownloader(store(root), url = server.url("/model").toString()).download().toList()

        val failed = events.last() as DownloadProgress.Failed
        assertEquals(
            AppError.ModelDownload.Reason.NETWORK,
            (failed.error as AppError.ModelDownload).reason,
        )
    }

    @Test fun `a redirect the store names as the vendor's own is followed`() = runTest {
        // Hugging Face answers `resolve/main` with a 302 to `us.aws.cdn.hf.co` — a different
        // registrable domain from `huggingface.co`. Deriving the allowed host from the configured
        // URL therefore refused the vendor's own CDN, and the model could not be downloaded at all.
        val payload = ByteArray(64) { it.toByte() }
        val cdn = MockWebServer().apply { start() }
        cdn.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val target = cdn.url("/blob").toString().replace("127.0.0.1", "localhost")
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
        val root = temp.newFolder("models")
        val from = server.url("/model").toString().replace("localhost", "127.0.0.1")

        val events = ModelDownloader(
            // The store names the redirect target, the way FileModelStore names hf.co.
            store(root, redirectTo = setOf("localhost")),
            url = from,
        ).download().toList()

        assertTrue("the vendor's own CDN was refused: ${events.last()}", events.last() is DownloadProgress.Done)
        assertEquals(64L, File(root, "test-model.bin").length())
        cdn.shutdown()
    }

    @Test fun `a redirect somewhere the store does not name is still refused`() = runTest {
        // Same trick as above: `localhost` and `127.0.0.1` have different registrable suffixes,
        // and naming an unrelated CDN in the store must not widen the rule to cover this one.
        val elsewhere = MockWebServer().apply { start() }
        elsewhere.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64))))
        val target = elsewhere.url("/swap").toString().replace("127.0.0.1", "localhost")
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
        val root = temp.newFolder("models")
        val from = server.url("/model").toString().replace("localhost", "127.0.0.1")

        val events = ModelDownloader(
            store(root, redirectTo = setOf("cdn.example.test")),
            url = from,
        ).download().toList()

        assertTrue("a payload may still be swapped by a redirect", events.last() is DownloadProgress.Failed)
        assertFalse(File(root, "test-model.bin").exists())
        elsewhere.shutdown()
    }
}

package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.stt.DownloadProgress
import ai.passioncode.fabricvr.stt.FileModelStore
import ai.passioncode.fabricvr.stt.ModelDownloader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the real model download actually start from this headset?
 *
 * A mocked redirect proves the rule the code implements. It cannot prove the rule still matches the
 * vendor, and that is precisely what broke: Hugging Face serves `resolve/main` with a 302 to
 * `us.aws.cdn.hf.co`, which shares no registrable domain with `huggingface.co`, so the guard refused
 * the vendor's own CDN and the model could not be downloaded at all. Nothing in the JVM suite could
 * see it — every test there redirects to a server the test itself started.
 *
 * It stops at the first bytes, so it costs about a megabyte, not 190.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadReachabilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "download-reachability")

    @After fun tearDown() { root.deleteRecursively() }

    @Test
    fun theRealDownloadStartsFromThisDevice() = runBlocking {
        root.mkdirs()
        val store = FileModelStore(root)

        val progress = withTimeoutOrNull(60_000) {
            ModelDownloader(store).download().first { it !is DownloadProgress.Running || it.bytes > 0 }
        }
        Log2.i("model.reachability", "progress" to progress.toString())

        assertTrue(
            "the download never produced bytes — if this is a refused redirect, the vendor's CDN " +
                "has moved and FileModelStore.allowedRedirectHosts needs the new domain. Got: $progress",
            progress is DownloadProgress.Running && progress.bytes > 0,
        )
    }
}

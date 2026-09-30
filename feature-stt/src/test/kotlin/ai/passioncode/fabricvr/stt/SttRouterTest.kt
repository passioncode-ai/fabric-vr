package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SttRouterTest {

    private lateinit var server: MockWebServer
    private val pcm = ShortArray(16_000) { (it % 100).toShort() }

    private class FakeEngine(
        override val name: String,
        private val result: Result<Transcript>,
        var calls: Int = 0,
    ) : SttEngine {
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, langHint: String?): Result<Transcript> {
            calls++
            return result
        }
    }

    private fun localOk() = FakeEngine(
        "local",
        Result.success(Transcript("on device", "ru", SttSource.LOCAL, "whisper", 100)),
    )

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `with no server the local engine answers and is marked local`() = runTest {
        val local = localOk()
        val transcript = SttRouter(local = local, remote = null).transcribe(pcm).getOrThrow()

        assertEquals(SttSource.LOCAL, transcript.source)
        assertEquals(1, local.calls)
    }

    @Test fun `a healthy server answers and is marked remote`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"from the server"}"""))
        val local = localOk()
        val remote = RemoteWhisperClient(server.url("/").toString())

        val transcript = SttRouter(local = local, remote = remote).transcribe(pcm).getOrThrow()

        assertEquals("from the server", transcript.text)
        assertEquals(SttSource.REMOTE, transcript.source)
        assertEquals("the local engine must not be touched", 0, local.calls)
    }

    @Test fun `a failing server falls back to the device and says so`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val local = localOk()
        val remote = RemoteWhisperClient(server.url("/").toString())

        val transcript = SttRouter(local = local, remote = remote).transcribe(pcm).getOrThrow()

        assertEquals("on device", transcript.text)
        assertEquals(SttSource.LOCAL_FALLBACK, transcript.source)
        assertEquals(1, local.calls)
    }

    @Test fun `a hanging server falls back too`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val local = localOk()
        val remote = RemoteWhisperClient(
            server.url("/").toString(),
            OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build(),
        )

        val transcript = SttRouter(local = local, remote = remote).transcribe(pcm).getOrThrow()
        assertEquals(SttSource.LOCAL_FALLBACK, transcript.source)
    }

    @Test fun `with a failing server and no local engine the failure is reported`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val remote = RemoteWhisperClient(server.url("/").toString())

        val result = SttRouter(local = null, remote = remote).transcribe(pcm)

        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as SttException).error
        assertEquals(503, (error as AppError.RemoteStt).status)
    }

    @Test fun `with nothing configured the missing model is named`() = runTest {
        val result = SttRouter(local = null, remote = null).transcribe(pcm)
        assertTrue((result.exceptionOrNull() as SttException).error is AppError.ModelMissing)
    }
    /**
     * The input `SttRouter` was designed for and never given: a provider the person chose but did
     * not finish configuring. Until `T-008` that case was a `null` remote, which takes the bottom
     * branch and records plain `LOCAL` — indistinguishable from somebody who chose the headset on
     * purpose, which is the whole of what the person could not see.
     */
    /**
     * **`M13`, the half `REQ-058` left open.** That row closed the `local == null` branch — a
     * remote failure with no fallback reports the remote's reason — and said nothing about the
     * branch where a local engine EXISTS and also fails. `.map` does not touch a failure, so the
     * remote's reason was dropped on the floor and the local's took its place.
     *
     * The cost is not abstract: somebody whose cloud key has expired gets *"The speech model
     * isn't on this headset yet"* with a **Download** button under it, and pressing it commits
     * them to 190–574 MB that will not fix anything. The 401 is the one sentence they could have
     * acted on, and it is the one that was thrown away.
     *
     * The remote is what they chose, so the remote is what is reported; the fallback's own
     * failure is logged rather than shown, because it is an answer to a question they did not ask.
     */
    @Test fun `when the remote fails and the fallback fails too the remote's reason survives`() = runTest {
        val remote = FailingEngine(AppError.RemoteStt(401, "invalid api key"))
        val local = FakeEngine(
            "local",
            Result.failure(SttException(AppError.ModelMissing("ggml-small-q5_1.bin"))),
        )

        val result = SttRouter(local = local, remote = remote).transcribe(pcm, 16_000, null)

        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as SttException).error
        assertTrue(
            "a person with an expired key was told to download a 190 MB model: $error",
            error is AppError.RemoteStt,
        )
        assertEquals(401, (error as AppError.RemoteStt).status)
        assertEquals("the fallback was not attempted", 1, local.calls)
    }

    /**
     * The control for the case above, and it is not optional: reporting the remote's reason
     * whatever happened would be the same defect facing the other way. When the remote had no
     * reason to give — nothing classified it — the fallback's own failure is the only thing there
     * is to say, and it is said.
     */
    @Test fun `with nothing to report from the remote the fallback's own reason is kept`() = runTest {
        val remote = FakeEngine("remote", Result.failure(IllegalStateException("not an SttException")))
        val local = FakeEngine(
            "local",
            Result.failure(SttException(AppError.ModelMissing("ggml-small-q5_1.bin"))),
        )

        val result = SttRouter(local = local, remote = remote).transcribe(pcm, 16_000, null)

        val error = (result.exceptionOrNull() as SttException).error
        assertTrue("the only reason anybody had was discarded: $error", error is AppError.ModelMissing)
    }

    @Test fun `a refusing remote degrades visibly`() = runTest {
        val refusal = AppError.SttNotConfigured("cloud", AppError.SttNotConfigured.Missing.KEY)
        val local = localOk()

        val result = SttRouter(local = local, remote = FailingEngine(refusal)).transcribe(pcm, 16_000, null)

        val transcript = result.getOrThrow()
        assertEquals(SttSource.LOCAL_FALLBACK, transcript.source)
        assertEquals("the reason did not travel with the transcript", refusal, transcript.fallbackReason)
        assertEquals(1, local.calls)
    }

}

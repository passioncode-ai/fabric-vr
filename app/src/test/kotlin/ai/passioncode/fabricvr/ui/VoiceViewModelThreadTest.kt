package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.AudioRecorder
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.stt.Recorder
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * `I-11`: `VoiceViewModel` contained no `withContext` and no `Dispatchers` import at all, so every
 * Keystore decrypt, `File.exists()` and two-megabyte WAV write it did ran on
 * `Dispatchers.Main.immediate` — at the exact moments the person is touching the control the whole
 * product is. In the 2D panel that reads as jank; in the Space the panel's texture is redrawn from
 * the same thread that composes it, so it is a frozen rectangle hanging in the room while the
 * passthrough world behind it keeps moving.
 *
 * **These assert the dispatcher, not a thread name.** Under `StandardTestDispatcher` every
 * coroutine runs on the same JVM thread by design, so `Thread.currentThread().name` would be
 * identical either side of a `withContext` and the obvious test would pass against the defect.
 * Two distinct dispatchers over one scheduler, and the assertion is which one intercepted the
 * continuation — deterministic, and it is the actual question.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VoiceViewModelThreadTest {

    private val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
    private val main = StandardTestDispatcher(scheduler, name = "main")
    private val io = StandardTestDispatcher(scheduler, name = "io")

    /** The named dispatcher is the point of this class, so the rule takes it rather than its own. */
    @get:Rule val mainRule = MainDispatcherRule(main)

    private lateinit var temp: File

    /** Where each injected provider was called from, recorded as the dispatcher that ran it. */
    private val calledOn = mutableMapOf<String, ContinuationInterceptor?>()
    private val order = mutableListOf<String>()

    @Before fun setUp() {
        temp = File.createTempFile("voice-thread", "").apply { delete(); mkdirs() }
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    }

    @After fun tearDown() = temp.deleteRecursively().let { }

    private suspend fun record(name: String) {
        calledOn[name] = coroutineContext[ContinuationInterceptor]
        synchronized(order) { order += name }
    }

    private fun store(present: Boolean) = object : ModelStore {
        override val modelName = "ggml-test.bin"
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = ""
        override fun modelFile() = File(temp, modelName)
        override fun isPresent() = present
    }

    private fun viewModel(modelPresent: Boolean = true) = VoiceViewModel(
        recorder = TestRecorder(),
        modelStore = { record("modelStore"); store(modelPresent) },
        transcribeWith = { _, _, _ ->
            synchronized(order) { order += "transcribe" }
            Result.success(
                Transcript("t", "en", SttSource.LOCAL, "fake", 0),
            )
        },
        language = { record("language"); "auto" },
        remoteReady = { record("remoteReady"); false },
        downloads = FakeDownloads(emptyList()),
        chosenModel = { WhisperModel.DEFAULT },
        // Both default to `Graph`, and `refresh()` in `init` reads them — see the note in
        // `VoiceViewModelTest`. Without them the suite fails a different test every run.
        currentProvider = { SttProvider.LOCAL },
        audioDir = { synchronized(order) { order += "audioDir" }; temp },
        // `REQ-046`: both reach `Graph` by default — the outbox resolves `appContext`, and
        // `Graph.scope` is a real pool this test's scheduler cannot advance.
        outbox = ai.passioncode.fabricvr.DictationOutbox(),
        appScope = kotlinx.coroutines.CoroutineScope(io),
        io = io,
    )

    /**
     * The two reads behind the Record button. `modelStore()` is a Keystore decrypt plus two
     * `stat` calls; `remoteReady()` builds a transcription client and reads the Keystore again
     * (`B-111`). Both happened on Main, on every tap.
     */
    @Test fun `the checks behind Record run on the injected dispatcher, not the caller's`() =
        runTest(main) {
            val vm = viewModel(modelPresent = true)

            vm.start()
            advanceUntilIdle()

            assertNotNull("modelStore was never consulted — the fixture proves nothing", calledOn["modelStore"])
            assertSame(
                "the model check ran on the caller's dispatcher: this is the Keystore on the drawing thread",
                io,
                calledOn["modelStore"],
            )
        }

    @Test fun `the language read runs on the injected dispatcher`() = runTest(main) {
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        vm.stopAndTranscribe(completed = true)
        advanceUntilIdle()

        assertNotNull("the fixture never reached a transcription", calledOn["language"])
        assertSame("a Keystore decrypt ran on the drawing thread", io, calledOn["language"])
    }

    /**
     * The `withContext` must not reorder the recording's own steps: the audio has to be on disk
     * before anything is told there is a transcript to attach to it.
     */
    @Test fun `the WAV is written before the transcription starts`() = runTest(main) {
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        vm.stopAndTranscribe(completed = true)
        advanceUntilIdle()

        val wav = order.indexOf("audioDir")
        val stt = order.indexOf("transcribe")
        assertTrue("the recording never reached the writer: $order", wav >= 0)
        assertTrue("the transcription never ran: $order", stt >= 0)
        assertTrue("the transcript was asked for before the audio was written: $order", wav < stt)
    }

    /** The refusal still arrives — asynchronously now, which is the behaviour change to pin. */
    @Test fun `start reports a missing model without blocking`() = runTest(main) {
        val vm = viewModel(modelPresent = false)

        vm.start()
        assertEquals(
            "start() did its disk and Keystore reads before returning, which is the defect",
            VoiceState.Idle,
            vm.state.value,
        )

        advanceUntilIdle()
        assertTrue("the refusal never arrived: ${vm.state.value}", vm.state.value is VoiceState.Failed)
        assertSame("the model check ran on the caller's dispatcher", io, calledOn["remoteReady"])
    }
}

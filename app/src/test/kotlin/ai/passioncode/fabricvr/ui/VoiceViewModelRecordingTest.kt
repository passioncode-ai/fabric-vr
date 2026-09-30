package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A recording had no end.
 *
 * Nothing in the app bounded its length (`E-03`), so a person who started dictating and was
 * distracted — took the headset off, walked away, was spoken to — recorded until the process died
 * of memory, and **everything they had said died with it**, because a recording only becomes a
 * file when it stops. And nothing stopped it when the screen went away: the Today header's actions
 * were live during a recording, tapping one left the view model on the back stack, and the
 * microphone kept reading with nothing anywhere saying so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VoiceViewModelRecordingTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    private lateinit var temp: File
    private val transcriptions = AtomicInteger(0)
    private var lastTranscribed: ShortArray? = null

    @Before fun setUp() {
        temp = File.createTempFile("recording", "").apply { delete(); mkdirs() }
    }

    @After fun tearDown() = temp.deleteRecursively().let { }

    private fun store() = object : ModelStore {
        override val modelName = "ggml-test.bin"
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = ""
        override fun modelFile() = File(temp, modelName)
        override fun isPresent() = true
    }

    /**
     * A cap of two chunks of a second each, so the test does not have to record ten real minutes
     * of virtual time. The constant under test is the *mechanism*; `MAX_SECONDS` is a product
     * number and is asserted separately below.
     */
    private fun viewModel(
        chunks: Int,
        maxSamples: Int,
        // `Recorder`, not `TestRecorder`: `REQ-052` needs one whose flow stays open so a mute
        // can arrive mid-recording, and the cases about the cap need one that completes.
        recorder: ai.passioncode.fabricvr.stt.Recorder = TestRecorder(samples = 16_000, chunks = chunks),
    ) = VoiceViewModel(
        recorder = recorder,
        modelStore = { store() },
        transcribeWith = { pcm, _, _ ->
            transcriptions.incrementAndGet()
            lastTranscribed = pcm
            Result.success(Transcript("spoken", "en", SttSource.LOCAL, "fake", 0))
        },
        language = { "auto" },
        remoteReady = { false },
        downloads = FakeDownloads(emptyList()),
        chosenModel = { WhisperModel.DEFAULT },
        // Both default to `Graph`, and `refresh()` in `init` reads them — see the note in
        // `VoiceViewModelTest`. Without them the suite fails a different test every run.
        currentProvider = { SttProvider.LOCAL },
        audioDir = { temp },
        outbox = ai.passioncode.fabricvr.DictationOutbox(),
        appScope = kotlinx.coroutines.CoroutineScope(dispatcher),
        io = dispatcher,
        maxSamples = maxSamples,
    )

    @Test fun `reaching the maximum stops the recording and transcribes it`() = runTest(dispatcher) {
        val vm = viewModel(chunks = 5, maxSamples = 2 * 16_000)

        vm.start()
        advanceUntilIdle()

        assertTrue("the recording never ended: ${vm.state.value}", vm.state.value is VoiceState.Ready)
        assertEquals("the recording was discarded at the limit instead of transcribed", 1, transcriptions.get())
        assertEquals(
            "the transcript is not of the audio up to the cap",
            2 * 16_000,
            lastTranscribed?.size,
        )
    }

    /**
     * `PcmBuffer.append` keeps returning false once full, so the cap path fires on every
     * subsequent chunk. Idempotence comes from `stopAndTranscribe`'s state guard — and relying on
     * a guard in another method is exactly the kind of thing that quietly stops being true.
     */
    @Test fun `reaching the maximum transcribes exactly once`() = runTest(dispatcher) {
        val vm = viewModel(chunks = 20, maxSamples = 16_000)

        vm.start()
        advanceUntilIdle()

        assertEquals("the cap fired more than once", 1, transcriptions.get())
    }

    @Test fun `stopping at the limit does not delete the recording`() = runTest(dispatcher) {
        val vm = viewModel(chunks = 5, maxSamples = 2 * 16_000)

        vm.start()
        advanceUntilIdle()

        val ready = vm.state.value as VoiceState.Ready
        val path = assertNotNull("the limit left no audio file at all", ready.audioPath).let { ready.audioPath!! }
        assertTrue("the WAV the person was promised is not on disk: $path", File(path).isFile)
    }

    /**
     * What the screen's `DisposableEffect` and `ON_STOP` handler call. **Transcribe, not discard**:
     * the view model outlives the composition, so the transcript is waiting when the person comes
     * back. Discarding would throw away speech they produced deliberately.
     */
    @Test fun `a recording stopped by the screen going away is transcribed, not discarded`() =
        runTest(dispatcher) {
            val vm = viewModel(chunks = 1, maxSamples = 10 * 16_000)
            vm.start()
            advanceUntilIdle()
            // One chunk emitted and the flow completed, so the state is still Recording.
            assertTrue("the fixture is not recording: ${vm.state.value}", vm.state.value is VoiceState.Recording)

            vm.stopForNavigation()
            advanceUntilIdle()

            assertTrue("the recording was dropped on the way out: ${vm.state.value}", vm.state.value is VoiceState.Ready)
            assertEquals(1, transcriptions.get())
        }

    @Test fun `stopping for navigation when nothing is recording does nothing`() = runTest(dispatcher) {
        val vm = viewModel(chunks = 1, maxSamples = 10 * 16_000)

        vm.stopForNavigation()
        advanceUntilIdle()

        assertEquals(VoiceState.Idle, vm.state.value)
        assertEquals(0, transcriptions.get())
    }

    /**
     * The product number, asserted where a change to it is visible. Ten minutes is about thirteen
     * minutes of transcription at the 1.31× real time measured on this headset — the bound is the
     * wait, not the memory. `DEC-0032`.
     */
    @Test fun `the maximum is ten minutes of audio`() {
        assertEquals(600, VoiceViewModel.MAX_SECONDS)
        assertEquals(600 * 16_000, VoiceViewModel.MAX_SAMPLES)
    }

    /**
     * **A regression `T-019` introduced and the group-verification pass caught.**
     *
     * Moving the two reads behind Record off the drawing thread made `start()` asynchronous, so
     * the state is still `Idle` while a recording is on its way. The button is a toggle that
     * reads `Recording` to choose between start and stop — so in that window it started again:
     * two recorder flows feeding one buffer, with only the second one's job in `recordJob`, and
     * the first holding the microphone for the life of the view model.
     */
    @Test fun `tapping Record twice before the first resolves starts one recorder`() =
        runTest(dispatcher) {
            val recorder = TestRecorder(chunks = 1)
            val vm = viewModel(chunks = 1, maxSamples = 10 * 16_000, recorder = recorder)

            vm.start()
            vm.start()
            advanceUntilIdle()

            assertEquals("two recorders were opened on one buffer", 1, recorder.startCount)
        }

    /**
     * The other half: a stop arriving inside the same window was silently dropped, because every
     * stop path is guarded on the state being `Recording`. The microphone then opened with
     * nothing able to close it.
     */
    @Test fun `a stop inside the resolve window cancels the pending start`() = runTest(dispatcher) {
        val recorder = TestRecorder(chunks = 1)
        val vm = viewModel(chunks = 1, maxSamples = 10 * 16_000, recorder = recorder)

        vm.start()
        vm.stopAndTranscribe(completed = true)
        advanceUntilIdle()

        assertEquals("the microphone opened after the person had already stopped", 0, recorder.startCount)
        assertEquals(VoiceState.Idle, vm.state.value)
        assertEquals("nothing was recorded, so nothing should have been transcribed", 0, transcriptions.get())
    }

    /** And a screen going away in the window, which is the same hole through `stopForNavigation`. */
    @Test fun `navigating away inside the resolve window cancels the pending start`() =
        runTest(dispatcher) {
            val recorder = TestRecorder(chunks = 1)
            val vm = viewModel(chunks = 1, maxSamples = 10 * 16_000, recorder = recorder)

            vm.start()
            vm.stopForNavigation()
            advanceUntilIdle()

            assertEquals("the microphone was left opening behind a screen that is gone", 0, recorder.startCount)
        }

    // ---- REQ-052 / H11: the OS can mute an open microphone ---------------------------------

    /**
     * **A silenced microphone is its own state, and it must not be read as silence.**
     *
     * *Meta Horizon OS Audio*: when the system or another app takes the microphone "the
     * microphone stream isn't closed and is provided empty audio data". Nothing in a read loop
     * can tell that from a quiet room, so the app recorded up to ten minutes of nothing, sent it
     * to whisper without an error, and answered *Nothing heard* — blaming the person for the
     * headset's decision. `Recorder.silenced` publishes it; this is the half that turns it into
     * something on the screen.
     */
    @Test fun `a muted microphone becomes its own state and comes back`() = runTest(dispatcher) {
        val recorder = SilenceableRecorder()
        val vm = viewModel(chunks = 1, maxSamples = 10 * 16_000, recorder = recorder)

        vm.start()
        advanceUntilIdle()
        assertTrue("the fixture never started recording: ${vm.state.value}", vm.state.value is VoiceState.Recording)

        recorder.mute(true)
        advanceUntilIdle()
        val muted = vm.state.value
        assertTrue("a muted microphone was indistinguishable from a quiet room: $muted", muted is VoiceState.Silenced)
        assertTrue("the recording was treated as over", muted.isRecording)
        assertEquals("the clock restarted when the microphone was muted", 16_000, (muted as VoiceState.Silenced).samples)

        recorder.mute(false)
        advanceUntilIdle()
        assertTrue("unmuting left the screen saying the microphone was muted", vm.state.value is VoiceState.Recording)
    }

    /** And the recording is still a recording: stopping it transcribes, as any other stop does. */
    @Test fun `a recording stopped while muted is still transcribed`() = runTest(dispatcher) {
        val recorder = SilenceableRecorder()
        val vm = viewModel(chunks = 1, maxSamples = 10 * 16_000, recorder = recorder)

        vm.start()
        advanceUntilIdle()
        recorder.mute(true)
        advanceUntilIdle()

        vm.stopAndTranscribe(completed = true)
        advanceUntilIdle()

        assertEquals("a stop from Silenced reached no engine", 1, transcriptions.get())
        assertTrue("the words were discarded: ${vm.state.value}", vm.state.value is VoiceState.Ready)
    }

    /**
     * A recorder whose flow stays open so the mute can arrive **during** a recording, which is
     * when it happens. [TestRecorder] hands over one chunk and completes, which is right for
     * every other case here and cannot express this one.
     */
    private class SilenceableRecorder : ai.passioncode.fabricvr.stt.Recorder {
        private val _silenced = kotlinx.coroutines.flow.MutableStateFlow(false)
        override val silenced: kotlinx.coroutines.flow.StateFlow<Boolean> = _silenced
        override fun hasPermission(): Boolean = true
        fun mute(value: Boolean) { _silenced.value = value }
        override fun record(
            onSamples: (ShortArray, Int) -> Unit,
        ): kotlinx.coroutines.flow.Flow<ai.passioncode.fabricvr.stt.AudioRecorder.Level> =
            kotlinx.coroutines.flow.flow {
                val chunk = ShortArray(16_000) { 1 }
                onSamples(chunk, chunk.size)
                emit(ai.passioncode.fabricvr.stt.AudioRecorder.Level(rms = 0.5f, samples = chunk.size))
                // Never completes: the recorder runs until it is cancelled, which is what makes
                // a mute arriving mid-recording reachable at all.
                kotlinx.coroutines.awaitCancellation()
            }
    }
}

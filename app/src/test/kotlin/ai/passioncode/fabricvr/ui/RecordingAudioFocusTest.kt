package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.AudioFocus
import ai.passioncode.fabricvr.CueChannel
import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.PlatformFeedbackCues
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.AudioRecorder
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.Recorder
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lifecycle contract LC-02, audit F3 (2026-10-03): **audio focus is released on every path a
 * recording can end by, not only the happy one.**
 *
 * The `RECORD_START` cue requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`; only the `RECORD_STOP` and
 * `AUTO_STOP` cues gave it back. A recorder that failed — the shell's voice command holding the
 * microphone, `ERROR_DEAD_OBJECT`, an `AudioRecord` that would not initialise — ended in
 * `VoiceState.Failed` with no cue, and a discarded recording ended with none by design. Either way
 * the person's streamed desktop or call stayed **ducked** until the next dictation stopped or the
 * process died.
 *
 * Every test drives the real [PlatformFeedbackCues] — the seam that owns the request — with a
 * counting [AudioFocus], so "released" means the platform was actually told.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RecordingAudioFocusTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    private lateinit var temp: File

    @Before fun setUp() {
        temp = File.createTempFile("focus", "").apply { delete(); mkdirs() }
    }

    @After fun tearDown() = temp.deleteRecursively().let { }

    /** Whether focus is held right now, and how often it was asked for. */
    private class Focus : AudioFocus {
        var held = false
        var requests = 0
        override fun requestTransientDuck(onLoss: () -> Unit): Boolean { requests++; held = true; return true }
        override fun abandon() { held = false }
    }

    /** A recorder that fails the way `AudioRecorder` does, after [chunksFirst] good chunks. */
    private class FailingRecorder(private val chunksFirst: Int) : Recorder {
        var starts = 0
        override fun hasPermission() = true
        override fun record(onSamples: (ShortArray, Int) -> Unit): Flow<AudioRecorder.Level> = flow {
            starts++
            val chunk = ShortArray(16_000) { 1 }
            repeat(chunksFirst) { i ->
                onSamples(chunk, chunk.size)
                emit(AudioRecorder.Level(0.5f, (i + 1) * chunk.size))
            }
            throw IllegalStateException("ERROR_DEAD_OBJECT")
        }
    }

    /** A recorder whose microphone stays open until somebody stops it. */
    private class OpenRecorder : Recorder {
        override fun hasPermission() = true
        override fun record(onSamples: (ShortArray, Int) -> Unit): Flow<AudioRecorder.Level> = flow {
            val chunk = ShortArray(16_000) { 1 }
            onSamples(chunk, chunk.size)
            emit(AudioRecorder.Level(0.5f, chunk.size))
            awaitCancellation()
        }
    }

    private fun store() = object : ModelStore {
        override val modelName = "ggml-test.bin"
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = ""
        override fun modelFile() = File(temp, modelName)
        override fun isPresent() = true
    }

    private fun cues(focus: Focus) =
        PlatformFeedbackCues(enabled = { false }, audio = CueChannel { }).apply { attachAudioFocus(focus) }

    private fun viewModel(recorder: Recorder, focus: Focus) = VoiceViewModel(
        recorder = recorder,
        modelStore = { store() },
        transcribeWith = { _, _, _ -> Result.success(Transcript("spoken", "en", SttSource.LOCAL, "fake", 0)) },
        language = { "auto" },
        remoteReady = { false },
        downloads = FakeDownloads(emptyList()),
        chosenModel = { WhisperModel.DEFAULT },
        currentProvider = { SttProvider.LOCAL },
        audioDir = { temp },
        outbox = DictationOutbox(),
        appScope = TestScope(dispatcher),
        io = dispatcher,
        cues = cues(focus),
    )

    @Test fun `a recorder that fails at start gives the focus back`() = runTest(dispatcher) {
        val focus = Focus()
        val vm = viewModel(FailingRecorder(chunksFirst = 0), focus)

        vm.start()
        advanceUntilIdle()

        assertTrue("the failure was expected to surface: ${vm.state.value}", vm.state.value is VoiceState.Failed)
        assertEquals("the recording never asked for focus — the test proves nothing", 1, focus.requests)
        assertFalse("other apps stay ducked after a recorder that failed at start", focus.held)
    }

    @Test fun `a recorder that fails mid-recording gives the focus back`() = runTest(dispatcher) {
        val focus = Focus()
        val vm = viewModel(FailingRecorder(chunksFirst = 2), focus)

        vm.start()
        advanceUntilIdle()

        assertTrue(vm.state.value is VoiceState.Failed)
        assertFalse("other apps stay ducked after a recorder that died mid-recording", focus.held)
    }

    @Test fun `discarding a recording gives the focus back`() = runTest(dispatcher) {
        val focus = Focus()
        val vm = viewModel(OpenRecorder(), focus)

        vm.start()
        advanceUntilIdle()
        assertTrue("the microphone was expected to be open", focus.held)

        vm.cancel()
        advanceUntilIdle()

        assertFalse("a discarded recording left other apps ducked", focus.held)
    }

    @Test fun `a view model cleared mid-recording gives the focus back`() = runTest(dispatcher) {
        val focus = Focus()
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel(OpenRecorder(), focus) as T
            },
        )[VoiceViewModel::class.java]

        vm.start()
        advanceUntilIdle()
        assertTrue(focus.held)

        store.clear()
        advanceUntilIdle()

        assertFalse("a host that died mid-recording left other apps ducked", focus.held)
    }

    @Test fun `the ordinary stop still gives the focus back exactly as before`() = runTest(dispatcher) {
        val focus = Focus()
        val vm = viewModel(OpenRecorder(), focus)

        vm.start()
        advanceUntilIdle()
        vm.stopAndTranscribe()
        advanceUntilIdle()

        assertFalse(focus.held)
        assertTrue(vm.state.value is VoiceState.Ready)
    }

    /**
     * Found beside F3: the recorder's `silenced` collector is a child of the recording job and a
     * `StateFlow` never completes, so after a recorder failure the job stayed active for ever —
     * and `beginRecording` refuses while it is. The next press of *Record* did nothing at all.
     */
    @Test fun `after a recorder failure the next press records again`() = runTest(dispatcher) {
        val focus = Focus()
        val recorder = FailingRecorder(chunksFirst = 0)
        val vm = viewModel(recorder, focus)

        vm.start()
        advanceUntilIdle()
        assertTrue(vm.state.value is VoiceState.Failed)

        vm.start()
        advanceUntilIdle()

        assertEquals("the second press never reached the recorder", 2, recorder.starts)
    }
}

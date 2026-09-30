package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the capture flow owes a person when part of it does not work.
 *
 * Three board rows meet in [VoiceViewModel] and none of them is about the happy path:
 *
 * - `B-092` — the WAV write was wrapped in `runCatching { … }.getOrNull()`. A full disk, or a
 *   directory that is not one, produced a note with `audioPath = null` and **not one word on
 *   screen**; the person learns months later that *Transcribe again* is missing from a row.
 * - `B-091` — `SCN-004` promises that when the engine fails "the audio is kept and offered as a
 *   note without a transcript". `T-005` stopped the file being deleted at the moment of failure,
 *   and then `onCleared` deleted it anyway on the way out, because nothing owned it.
 * - `B-178`/`B-179` — a ten-minute dictation is thirteen minutes of *Transcribing…* with no shape
 *   and no exit. `WhisperEngine.progressPercent()` has been readable since `DEC-0060` and
 *   cancellation has reached the blocking native call since the same change.
 *
 * The harness is [VoiceViewModelTest]'s, narrowed: these tests need a transcription they can hold
 * open, which that file's `CountingEngine` returns from immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VoiceDegradationTest {

    // `B-112`: the rule rather than a hand-installed dispatcher. This class was written in a
    // parallel worktree while the rule was being applied to nine others, and
    // `MainDispatcherPolicyTest` — the gate that came out of that work — named it on the first
    // run after the merge. That is the gate doing its job, not a collision.
    @get:Rule val main = MainDispatcherRule()

    private val dispatcher get() = main.dispatcher
    private lateinit var temp: File

    private class PresentModelStore(private val file: File) : ModelStore {
        override val modelName = "ggml-test.bin"
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = "https://example.invalid/$modelName"
        override fun modelFile() = file
        override fun isPresent() = true
    }

    @Before fun setUp() {
        temp = File(System.getProperty("java.io.tmpdir"), "voice-degrade-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        temp.deleteRecursively()
    }

    private val outbox = DictationOutbox()
    private val recorder = TestRecorder()

    private fun viewModel(
        audioDir: File = temp,
        /** How long the decode takes, so a test can stand inside it. */
        decodeMillis: Long = 0,
        decode: (suspend () -> Result<Transcript>)? = null,
        progress: () -> Int = { 0 },
        last: VoiceViewModel.LastRecording = VoiceViewModel.LastRecording(),
    ) = VoiceViewModel(
        recorder = recorder,
        modelStore = { PresentModelStore(File(temp, "model.bin").apply { writeBytes(ByteArray(8)) }) },
        chosenModel = { WhisperModel.DEFAULT },
        currentProvider = { SttProvider.LOCAL },
        transcribeWith = { _, _, _ ->
            if (decodeMillis > 0) delay(decodeMillis)
            decode?.invoke() ?: Result.success(Transcript("hello", "en", SttSource.LOCAL, "whisper-test", 1))
        },
        progressOf = progress,
        language = { "auto" },
        remoteReady = { false },
        audioDir = { audioDir },
        outbox = outbox,
        appScope = CoroutineScope(dispatcher),
        last = last,
        io = dispatcher,
    )

    /** Record one chunk and release the button — the shortest route to a decode. */
    private fun TestScope.dictate(vm: VoiceViewModel) {
        vm.start()
        advanceUntilIdle()
        vm.stopAndTranscribe(completed = true)
    }

    // ---------------------------------------------------------------- B-092

    /**
     * `B-092`. The recording is the artefact *Transcribe again* needs, and losing it silently is
     * the failure this row names: the note was written, the row carried no audio, and the app had
     * said nothing at all.
     */
    @Test
    fun `a recording that could not be written is reported`() = runTest(dispatcher) {
        // A regular file where the audio directory should be: every write under it fails, which is
        // what a full disk or a revoked directory looks like from here.
        val blocked = File(temp, "audio-is-a-file").apply { writeText("not a directory") }
        val vm = viewModel(audioDir = blocked)

        dictate(vm)
        advanceUntilIdle()

        assertNotNull(
            "the WAV write failed and the person was told nothing (`getOrNull()`)",
            vm.recordingLost.value,
        )
        assertEquals(
            "the notice did not name the lost recording",
            R.string.state_recording_not_kept,
            vm.recordingLost.value?.textRes,
        )
        assertTrue(
            "the words were lost with the recording — they are the part that must survive",
            vm.state.value is VoiceState.Ready,
        )
    }

    /** A written recording says nothing: a notice that fires on success is a notice nobody reads. */
    @Test
    fun `a recording that was written raises no notice`() = runTest(dispatcher) {
        val vm = viewModel()

        dictate(vm)
        advanceUntilIdle()

        assertNull("a successful write raised a failure notice", vm.recordingLost.value)
    }

    // ---------------------------------------------------------------- B-091

    /**
     * `B-091` / `SCN-004`. A decode that fails leaves a recording nothing owns, and
     * [VoiceViewModel.onCleared] deletes anything nothing owns — so leaving the screen after an
     * engine failure destroyed the audio the failure message had just promised was kept.
     */
    @Test
    fun `a failed decode can hand its recording to a note`() = runTest(dispatcher) {
        // Through a `ViewModelStore` for [VoiceViewModelTest]'s reason: `clear()` is what the
        // platform does, and a test that calls `onCleared()` by hand stays green with the
        // production hook missing entirely.
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    viewModel(decode = { Result.failure(IllegalStateException("engine died")) }) as T
            },
        )[VoiceViewModel::class.java]

        dictate(vm)
        advanceUntilIdle()
        assertTrue("the decode was expected to fail", vm.state.value is VoiceState.Failed)

        val path = vm.releaseRecordingForNote()

        assertNotNull("the failure kept no recording to offer", path)
        assertTrue("the recording named by the hand-off is not on disk", File(path!!).isFile)
        assertEquals("the screen did not return to a usable state", VoiceState.Idle, vm.state.value)

        // The point of the hand-off: the sweep must no longer claim it.
        store.clear()
        advanceUntilIdle()
        assertTrue("`onCleared` deleted a recording that had been handed to a note", File(path).isFile)
    }

    /** Nothing to hand over is answered with null rather than with an empty note. */
    @Test
    fun `there is nothing to hand over when no recording was made`() = runTest(dispatcher) {
        val vm = viewModel()
        assertNull("a recording was invented", vm.releaseRecordingForNote())
    }

    // ---------------------------------------------------------------- B-178

    /**
     * `B-178`. The value is a poll, not a flow (`DEC-0060`): whisper's `progress_callback` fires
     * on threads that are not attached to the JVM, so the native side writes an atomic and this
     * reads it. The cadence is therefore this class's decision, and it must stop with the state.
     */
    @Test
    fun `a running transcription publishes its progress`() = runTest(dispatcher) {
        var percent = 0
        val vm = viewModel(decodeMillis = 60_000, progress = { percent })
        // **A subscriber, because nothing ticks without one.** The flow is shared
        // `WhileSubscribed`, which is what keeps an idle panel free and — see the production
        // KDoc — what stops `advanceUntilIdle()` spinning on an unbounded delay loop.
        backgroundScope.launch { vm.transcriptionProgress.collect { } }

        dictate(vm)
        advanceTimeBy(100)
        assertTrue("the decode was expected to still be running", vm.state.value is VoiceState.Transcribing)

        percent = 41
        advanceTimeBy(VoiceViewModel.PROGRESS_POLL_MS + 1)
        assertEquals("the progress the engine reports never reached the screen", 41, vm.transcriptionProgress.value)

        advanceUntilIdle()
        assertEquals("the poll outlived the transcription it was polling", 0, vm.transcriptionProgress.value)
    }

    /**
     * And it costs nothing when nobody is looking. An idle screen, or a screen that is not drawing
     * the bar, must not be paying for a volatile load twice a second for thirteen minutes.
     */
    @Test
    fun `nothing is polled while no transcription is running`() = runTest(dispatcher) {
        var reads = 0
        val vm = viewModel(progress = { reads++; 50 })
        backgroundScope.launch { vm.transcriptionProgress.collect { } }

        advanceTimeBy(VoiceViewModel.PROGRESS_POLL_MS * 20)

        assertEquals("the engine was polled with nothing to poll", 0, reads)
    }

    // ---------------------------------------------------------------- B-179

    /**
     * `B-179`. `DEC-0060` chose `CancellationException` so no new error shape was needed, and that
     * is right for a model switch abandoning a run nobody asked about. It is **not** enough for a
     * person who pressed something: the decision recorded here is *told* rather than *silent* —
     * the recording is kept and the banner says so, with *Try again* and *Discard* beside it,
     * because a stop that looked like a crash is the defect this row was opened for.
     */
    @Test
    fun `a transcription can be stopped and the recording is kept`() = runTest(dispatcher) {
        val vm = viewModel(decodeMillis = 60_000, progress = { 44 })
        backgroundScope.launch { vm.transcriptionProgress.collect { } }

        dictate(vm)
        advanceTimeBy(VoiceViewModel.PROGRESS_POLL_MS + 1)
        assertTrue("the decode was expected to still be running", vm.state.value is VoiceState.Transcribing)
        assertEquals("the bar was never drawn, so its disappearance proves nothing", 44, vm.transcriptionProgress.value)

        vm.stopTranscription()
        advanceUntilIdle()

        val state = vm.state.value

        assertTrue("stopping left no message at all — a silent stop reads as a crash", state is VoiceState.Failed)
        assertEquals(
            "the stop did not say that the recording survived it",
            R.string.state_transcription_stopped,
            (state as VoiceState.Failed).message.textRes,
        )
        assertTrue("the recording was discarded by a stop that promised to keep it", vm.hasRecording())
        assertEquals("the progress bar was left standing after the stop", 0, vm.transcriptionProgress.value)
    }

    /** Nothing running is nothing to stop: a dead press must not invent a message. */
    @Test
    fun `stopping when nothing is transcribing changes nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        val before = vm.state.value

        vm.stopTranscription()
        advanceUntilIdle()

        assertEquals("a stop with nothing to stop moved the state", before, vm.state.value)
        assertFalse("a stop with nothing to stop invented a recording", vm.hasRecording())
    }
}

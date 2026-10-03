package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.AudioRecorder
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.Recorder
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * **A run the engine abandons is not a wait with no end** (`B-251`, `DEC-0092`).
 *
 * `DEC-0060` reports a cancelled native run as a `CancellationException`, and `WhisperEngine`
 * answers a run queued behind `close()` the same way — which is what a model switch does. The
 * transcription coroutine in `VoiceViewModel` was **not** itself cancelled, so the exception ended
 * it the way a cancellation ends a coroutine: silently. Nothing set a state, and the screen stayed
 * on *Transcribing…* for ever, with the recording kept and no control that reached it.
 *
 * View-model tier with a test dispatcher (`SI-05`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionAbandonedTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    private val appScope = CoroutineScope(dispatcher)

    private val root: File =
        File(System.getProperty("java.io.tmpdir"), "abandoned-${System.nanoTime()}").apply { mkdirs() }

    @After fun tearDown() {
        appScope.cancel()
        root.deleteRecursively()
    }

    private class PresentStore(private val file: File) : ModelStore {
        override val modelName = "ggml-test.bin"
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = "https://example.invalid/$modelName"
        override fun modelFile() = file
        override fun isPresent() = true
    }

    /** One chunk and stop — the real loop fills the heap under the JVM tier. */
    private class OneChunkRecorder : Recorder {
        override fun hasPermission(): Boolean = true
        override fun record(onSamples: (ShortArray, Int) -> Unit): Flow<AudioRecorder.Level> = flow {
            val chunk = ShortArray(16_000) { 1 }
            onSamples(chunk, chunk.size)
            emit(AudioRecorder.Level(rms = 0.5f, samples = chunk.size))
        }
    }

    private fun voice(transcribe: suspend () -> Result<Transcript>) = VoiceViewModel(
        recorder = OneChunkRecorder(),
        modelStore = { PresentStore(File(root, "model.bin").apply { writeBytes(ByteArray(8)) }) },
        transcribeWith = { _, _, _ -> transcribe() },
        language = { "auto" },
        remoteReady = { false },
        downloads = FakeDownloads(emptyList()),
        chosenModel = { WhisperModel.DEFAULT },
        currentProvider = { SttProvider.LOCAL },
        audioDir = { File(root, "audio").apply { mkdirs() } },
        outbox = DictationOutbox(),
        journal = ai.passioncode.fabricvr.TranscriptionJournal(null),
        appScope = appScope,
        io = dispatcher,
    )

    @Test fun `a run the engine abandons leaves a state with an exit, and keeps the recording`() =
        runTest(dispatcher) {
            val vm = voice { throw CancellationException("the engine closed while this run was queued") }
            vm.start()
            advanceUntilIdle()
            vm.stopAndTranscribe(completed = true)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue("an abandoned run left the screen waiting for ever: $state", state is VoiceState.Failed)
            val message = (state as VoiceState.Failed).message
            assertEquals(R.string.state_transcription_model_changed, message.textRes)
            assertEquals("the recording had no way back to a transcript", UiAction.RETRY_LOAD, message.action)
            assertTrue("the recording was dropped with the run", vm.hasRecording())
        }
}

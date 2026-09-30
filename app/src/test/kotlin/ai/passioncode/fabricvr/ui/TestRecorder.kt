package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.stt.AudioRecorder
import ai.passioncode.fabricvr.stt.Recorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * A microphone that hands over one chunk and stops.
 *
 * The real [AudioRecorder] loops `while (isActive)` on `Dispatchers.IO`, and Robolectric's
 * `AudioRecord` shadow answers every `read` with a full buffer — so a test that lets it start
 * accumulates samples on a real thread as fast as the JVM will run, and the suite dies with
 * `OutOfMemoryError` in whichever test happens to be running when the heap goes. It was watched
 * happening twice while `T-019` was written, the second time only when the whole suite ran
 * together, which is the worst way for it to appear.
 *
 * None of these tests is about the recorder. They are about what happens after a recording, and
 * one chunk is a recording.
 */
internal class TestRecorder(
    private val permitted: Boolean = true,
    private val samples: Int = 16_000,
    /** How many chunks of [samples] to emit. More than one is how a cap is reached. */
    private val chunks: Int = 1,
) : Recorder {
    /** Whether anything actually started, for the assertions that are about a refusal. */
    var started: Boolean = false
        private set

    /** How MANY times, which is what catches two recorders sharing one buffer. */
    var startCount: Int = 0
        private set

    override fun hasPermission(): Boolean = permitted

    override fun record(onSamples: (ShortArray, Int) -> Unit): Flow<AudioRecorder.Level> =
        if (!permitted) emptyFlow() else flow {
            started = true
            startCount++
            // One array, reused — the contract `Recorder` states and the reason `T-020` exists.
            val chunk = ShortArray(samples) { 1 }
            var total = 0
            repeat(chunks) {
                onSamples(chunk, chunk.size)
                total += chunk.size
                emit(AudioRecorder.Level(rms = 0.5f, samples = total))
            }
        }
}

package ai.passioncode.fabricvr.stt

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * `H11`: **the OS can mute the microphone without closing it.**
 *
 * *Meta Horizon OS Audio* (fetched 2026-09-21) states the contract exactly: when another app or
 * the system takes the microphone, "the microphone stream isn't closed and is provided empty
 * audio data", and the two ways to notice are
 * `AudioRecordingConfiguration.isClientSilenced()` and `AudioManager.isMicrophoneMute()`.
 *
 * Nothing here looked. So a person could hold the button for ten minutes while Meta Virtual
 * Display or a call held the microphone, watch a level meter sitting at zero, and be told
 * **"Nothing heard"** at the end — the app's word for *you did not speak*. Ten minutes of
 * zeroes then went to whisper, which obligingly returned nothing, and every part of the
 * interface agreed it was the person's fault.
 *
 * Silencing is a **state, not an error**: the recording must keep running, because the microphone
 * can come back, and the flow must not fail — failing would be the same lie in a different
 * costume. So the signal is its own `StateFlow`, beside the levels rather than instead of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AudioRecorderSilenceTest {

    private lateinit var recorder: AudioRecorder

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        recorder = AudioRecorder(app)
    }

    /** See `AudioRecorderErrorTest`: the loop runs on real IO while `runTest`'s clock is virtual. */
    private suspend fun <T> realTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(2)) { withTimeout(10_000) { block() } }

    /**
     * A microphone that keeps answering — with zeroes when the OS has silenced it, which is
     * precisely what Meta documents. A source that returned an error instead would be testing
     * `C-05` again, not this.
     */
    private class SilenceableSource : PcmSource {
        @Volatile var muted = false
        val reads = AtomicInteger(0)

        override val isInitialized = true
        override val isSilenced get() = muted
        override fun start() = Unit
        override fun read(into: ShortArray, offset: Int, count: Int): Int {
            reads.incrementAndGet()
            val n = minOf(160, count)
            for (i in 0 until n) into[i] = if (muted) 0 else 100
            return n
        }
        override fun stop() = Unit
        override fun release() = Unit
    }

    @Test fun `nothing is silenced before a recording starts`() {
        assertFalse("the interface would open claiming the microphone is muted", recorder.silenced.value)
    }

    @Test fun `a silenced microphone raises its own signal rather than delivering quiet audio`() = runTest {
        val source = SilenceableSource().apply { muted = true }
        val duringRecording = CopyOnWriteArrayList<Boolean>()

        realTime {
            recorder.record({ _, _ -> }, { source })
                .take(2)
                .collect { duringRecording += recorder.silenced.value }
        }

        assertTrue(
            "ten minutes of zeroes went to whisper and the person was told nothing was heard: $duringRecording",
            duringRecording.isNotEmpty() && duringRecording.all { it },
        )
    }

    /** And an ordinary recording must not claim to be silenced — the signal has to discriminate. */
    @Test fun `an ordinary recording never raises the signal`() = runTest {
        val source = SilenceableSource()
        val duringRecording = CopyOnWriteArrayList<Boolean>()

        realTime {
            recorder.record({ _, _ -> }, { source })
                .take(3)
                .collect { duringRecording += recorder.silenced.value }
        }

        assertTrue("a working microphone was reported as muted: $duringRecording", duringRecording.none { it })
    }

    /**
     * The microphone can come back — a call ends, the other app releases it. The signal has to
     * fall again, or the interface is stuck telling the person about a problem that is over.
     *
     * The assertion is that this terminates inside the timeout: both `first { }` calls are waits.
     */
    @Test fun `the signal falls again when the microphone comes back`() = runTest {
        val source = SilenceableSource().apply { muted = true }

        realTime {
            val job = launch(Dispatchers.IO) { recorder.record({ _, _ -> }, { source }).catch { }.collect { } }
            recorder.silenced.first { it }
            source.muted = false
            recorder.silenced.first { !it }
            job.cancelAndJoin()
        }
    }

    /**
     * Silencing does not end the recording. If it did, the person would be told their dictation
     * failed at the moment the OS borrowed the microphone for a notification — and the buffer
     * they had already filled would go with it.
     */
    @Test fun `a silenced recording keeps running and never fails the flow`() = runTest {
        val source = SilenceableSource().apply { muted = true }
        var failure: Throwable? = null

        val levels = realTime {
            recorder.record({ _, _ -> }, { source })
                .catch { failure = it }
                .take(3)
                .toList()
        }

        assertNull("silencing ended the recording instead of reporting a state", failure)
        assertTrue("the level meter stopped while the microphone was muted", levels.size == 3)
    }

    /** A second recording must not open already claiming to be muted. */
    @Test fun `the signal is cleared when a recording ends`() = runTest {
        val source = SilenceableSource().apply { muted = true }

        realTime { recorder.record({ _, _ -> }, { source }).take(2).toList() }

        assertFalse(
            "the last recording's silencing outlived it, so the next screen opens muted",
            recorder.silenced.value,
        )
    }
}

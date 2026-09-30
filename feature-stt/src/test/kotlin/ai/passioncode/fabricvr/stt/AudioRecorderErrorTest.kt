package ai.passioncode.fabricvr.stt

import android.media.AudioRecord
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * `C-05`: `recorder.read()` returns a negative code when the session dies or the microphone is
 * taken — by the shell's voice command, by another app, by Meta Virtual Display. The loop tested
 * `if (read > 0)`, so a negative fell straight through to `while (isActive)` and called `read()`
 * again immediately: **one core at 100%**, the meter frozen at its last value, the interface still
 * saying *Recording*, and a buffer too short to transcribe when the person finally stopped — so
 * they were told "nothing was heard".
 *
 * Every assertion here is under a timeout, because the defect's signature is *not finishing*: a
 * test written without one would hang exactly the way the app does and report nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AudioRecorderErrorTest {

    private lateinit var recorder: AudioRecorder

    /**
     * `AudioRecorder.record` ends in `flowOn(Dispatchers.IO)`, so the loop runs in real time while
     * `runTest`'s clock is virtual — a bare `withTimeout` fires instantly at virtual 10 s and
     * every test here failed that way before this wrapper existed. The timeout is the point of
     * these tests (the defect's signature is *not finishing*), so it has to be a real one.
     */
    private suspend fun <T> realTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { block() } }

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        recorder = AudioRecorder(app)
    }

    /** A microphone that answers with a script and counts how often it was asked. */
    private class ScriptedSource(private val script: List<Int>, private val fill: Short = 100) : PcmSource {
        val reads = AtomicInteger(0)
        var released = false
            private set

        override val isInitialized = true
        override fun start() = Unit
        override fun read(into: ShortArray, offset: Int, count: Int): Int {
            val n = reads.getAndIncrement()
            val answer = script.getOrElse(n) { script.last() }
            if (answer > 0) for (i in 0 until minOf(answer, count)) into[i] = fill
            return answer
        }
        override fun stop() = Unit
        override fun release() { released = true }
    }

    @Test fun `a negative read ends the flow with a failure instead of spinning`() = runTest {
        val source = ScriptedSource(listOf(AudioRecord.ERROR_DEAD_OBJECT))
        var failure: Throwable? = null

        realTime {
            recorder.record({ _, _ -> }, { source })
                .catch { failure = it }
                .toList()
        }

        val thrown = assertNotNull("the flow completed without reporting anything", failure).let { failure!! }
        assertTrue("the failure is not an SttException: $thrown", thrown is SttException)
        assertTrue(
            "the error code is not in the message, so logcat cannot say which failure happened: ${thrown.cause?.message}",
            thrown.cause?.message?.contains("read=-6") == true,
        )
        assertEquals("the loop read again after a fatal code — that is the spin", 1, source.reads.get())
        assertTrue("the microphone was not released", source.released)
    }

    /**
     * `ERROR_INVALID_OPERATION` means "not started yet", which is genuinely transient for a frame
     * or two after `startRecording`. Treating it as fatal would turn a warm-up into a failed
     * dictation; treating it as nothing is the spin.
     */
    @Test fun `a transient ERROR_INVALID_OPERATION is retried and the recording continues`() = runTest {
        val source = ScriptedSource(
            listOf(AudioRecord.ERROR_INVALID_OPERATION, AudioRecord.ERROR_INVALID_OPERATION, 160),
        )
        val samples = AtomicInteger(0)

        realTime {
            recorder.record({ _, count -> samples.addAndGet(count) }, { source })
                .catch { }
                .take(3)
                .toList()
        }

        assertTrue("nothing arrived after two transient errors: the retry gave up too early", samples.get() > 0)
    }

    /** The retry is bounded. A microphone that is never going to start must not be waited on for ever. */
    @Test fun `a permanent ERROR_INVALID_OPERATION gives up rather than retrying for ever`() = runTest {
        val source = ScriptedSource(listOf(AudioRecord.ERROR_INVALID_OPERATION))
        var failure: Throwable? = null

        realTime {
            recorder.record({ _, _ -> }, { source })
                .catch { failure = it }
                .toList()
        }

        assertTrue("the flow never gave up", failure is SttException)
        assertEquals(
            "the retry is not bounded at MAX_TRANSIENT_READS",
            AudioRecorder.MAX_TRANSIENT_READS + 1,
            source.reads.get(),
        )
    }

    /**
     * Zero is neither an error nor audio. It must not end the flow and it must not reach the
     * buffer as an empty chunk — the accumulator would take it, `rms` would divide by zero, and
     * the meter would read `NaN`.
     */
    @Test fun `a zero read is not an error and is not a chunk`() = runTest {
        val source = ScriptedSource(listOf(0, 0, 160))
        val counts = java.util.concurrent.CopyOnWriteArrayList<Int>()

        realTime {
            recorder.record({ _, count -> counts += count }, { source })
                .catch { }
                .take(1)
                .toList()
        }

        assertTrue("the flow ended on a zero read, which is not an error", counts.isNotEmpty())
        assertTrue("a zero-length read was handed on as a chunk: $counts", counts.none { it == 0 })
    }

    /**
     * **A microphone that refuses to start is still released** (`B-248`). `recorder.start()` sat
     * above the `try` whose `finally` releases the `AudioRecord`, so a throw from `startRecording()`
     * — the shell's voice command holding the microphone is the ordinary way to get one — leaked it,
     * and the next dictation, or the system, found the microphone taken.
     */
    @Test fun `a source that throws on start is released`() = runTest {
        var released = false
        val refusing = object : PcmSource {
            override val isInitialized = true
            override fun start() = throw IllegalStateException("startRecording() called on an uninitialized AudioRecord")
            override fun read(into: ShortArray, offset: Int, count: Int): Int = 0
            override fun stop() = Unit
            override fun release() { released = true }
        }
        var failure: Throwable? = null

        realTime {
            recorder.record({ _, _ -> }, { refusing })
                .catch { failure = it }
                .toList()
        }

        assertNotNull("a start that threw was swallowed", failure)
        assertTrue("the microphone was leaked by a start that threw", released)
    }
}

package ai.passioncode.fabricvr.stt

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A running transcription can be stopped, and a stopped one is not a broken one.**
 *
 * `whisper_full` blocks the thread it runs on, so coroutine cancellation could not reach it: an
 * in-flight transcription was a state a person could only wait out — minutes on a long dictation
 * against a thermally limited headset (`B-147`) — and a model switch queued behind one waited the
 * same (`B-118`). The bridge now carries `abort_callback` and the engine turns coroutine
 * cancellation into it.
 *
 * None of this was reachable by a test before `WhisperNativeCalls`: every rule the engine enforces
 * sits around `external fun`s that throw `UnsatisfiedLinkError` off a device. The fake below is a
 * **blocking** call, like the real one, so the ordering under test is the ordering that matters —
 * cancel arrives while a thread is parked inside `transcribe`.
 */
class WhisperEngineCancellationTest {

    /**
     * **Every case here is bounded, and the bound is the point.**
     *
     * The defect under test is a thread that never comes back, so the natural failure mode of a
     * regression is a hang — and the first version of this class hung a `check-all` run for ten
     * minutes before anyone could read a line of it. A case whose failure mode is a timeout is
     * how a suite's red stops being read (`SI-05`). Ten seconds is far above what a passing run
     * needs (they finish in milliseconds) and far below what a person will sit through.
     */
    private companion object {
        val TEST_BOUND = 10.seconds

        /**
         * How long the fake pretends `whisper_full` runs when nobody stops it.
         *
         * Comfortably longer than [TEST_BOUND], so a passing run never reaches it and a broken
         * one fails on the test's own bound with the test's own message — but finite, so the
         * executor thread is always freed and the JVM always exits.
         */
        val NEVER_RETURNS = 30.seconds
    }

    private fun store(): ModelStore = object : ModelStore {
        override val modelName = "ggml-small-q5_1.bin"
        override val expectedBytes = 1L
        override val expectedSha256 = ""
        override val downloadUrl = ""
        override fun modelFile() = File("ggml-small-q5_1.bin")
        override fun isPresent() = true
    }

    /**
     * A native surface that blocks until cancelled, or returns a transcript on demand.
     *
     * It answers about a single in-flight run, which is all these cases need — the rules about
     * *which* run a cancel names live in [WindowNative], where they can be told apart.
     */
    private class FakeNative(
        private val blockUntilCancelled: Boolean = true,
        private val result: ByteArray? = "готово".toByteArray(),
    ) : WhisperNativeCalls {
        val entered = CountDownLatch(1)
        val cancelCalls = AtomicInteger(0)
        val transcribeCalls = AtomicInteger(0)
        private val cancelled = AtomicBoolean(false)
        private val runs = AtomicInteger(0)
        @Volatile var freed = false
        @Volatile var reportedProgress = 0

        override fun ensureLoaded() = true
        override fun initContext(modelPath: String) = 42L
        override fun freeContext(ptr: Long) { freed = true }
        override fun detectedLanguage(ptr: Long) = "ru"
        override fun beginRun(ptr: Long) = runs.incrementAndGet().toLong()
        override fun cancel(ptr: Long, run: Long) { cancelCalls.incrementAndGet(); cancelled.set(true) }
        override fun progress(ptr: Long) = reportedProgress
        override fun wasCancelled(ptr: Long, run: Long) = cancelled.get()

        override fun transcribe(
            ptr: Long,
            run: Long,
            audio: FloatArray,
            threads: Int,
            language: String,
            beamSize: Int,
        ): ByteArray? {
            transcribeCalls.incrementAndGet()
            entered.countDown()
            if (!blockUntilCancelled) return result
            // Parked in C, as far as the coroutine machinery is concerned: nothing here checks
            // for interruption, because `whisper_full` does not either.
            //
            // **But it has a deadline, and the first version did not** — which made a regression
            // spin an executor thread for ever and hang the whole test JVM, not merely the case.
            // A fake that blocks for ever is not faithful either: the call it stands in for
            // always returns, at worst after the audio is decoded. The bound turns a regression
            // into a bounded red instead of a hang somebody has to go and kill.
            val deadline = System.nanoTime() + NEVER_RETURNS.inWholeNanoseconds
            while (!cancelled.get() && System.nanoTime() < deadline) Thread.sleep(1)
            return null
        }
    }

    /**
     * **The bridge as `fabricvr_whisper.cpp` actually behaves, run by run** (`B-181`).
     *
     * [FakeNative] above is a per-context flag, which is what the bridge was: enough to prove a
     * cancel reaches a *running* call, and blind to which run a cancel meant. This one carries the
     * run identity the JNI surface now does, so the two rules can be told apart — a cancel for the
     * run that is starting must be honoured, and a cancel for the run before it must not be.
     *
     * It parks at the entry point on demand, which is the only way to put a cancel *into* the
     * window rather than wait for one to land there by luck: 0 of 40 attempts provoked it by
     * timing, which is why `B-181` says in its own text that it is a code read.
     *
     * **Every wait is bounded and the bound is short**, for this class's stated reason: the
     * failure mode under test is a thread that never comes back, so a regression's natural shape
     * is a hang. [STALL] is well under [TEST_BOUND], so a lost cancel reports as *the run burned
     * its deadline* in three seconds rather than as a suite that stopped.
     */
    private class WindowNative(private val blockAtEntry: Boolean = true) : WhisperNativeCalls {
        enum class Ending { NOT_RUN, CANCELLED, FINISHED, DEADLINE }

        /** Counted down when the native entry is reached and before it decides anything. */
        val atEntry = CountDownLatch(1)

        /** Released by the test once the cancel is in the window. */
        val proceed = CountDownLatch(1)

        /**
         * Counted down when the native call **returns**, which is the only moment [ending] means
         * anything.
         *
         * Joining the coroutine is not enough and the first version of these cases did exactly
         * that: a cancelled job completes as soon as it is cancelled, while the executor task it
         * submitted is still inside the fake. The assertion then read [ending] mid-run and got
         * `NOT_RUN` — a red for the right row by luck rather than by evidence, and a green that
         * would have depended on the scheduler.
         */
        val finished = CountDownLatch(1)

        @Volatile var ending: Ending = Ending.NOT_RUN

        private val nextRun = java.util.concurrent.atomic.AtomicLong(0)
        private val cancelled = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
        private var lastRun = 0L

        override fun ensureLoaded() = true
        override fun initContext(modelPath: String) = 42L
        override fun freeContext(ptr: Long) = Unit
        override fun detectedLanguage(ptr: Long) = "ru"
        override fun progress(ptr: Long) = 0

        override fun beginRun(ptr: Long): Long = nextRun.incrementAndGet().also { lastRun = it }

        override fun cancel(ptr: Long, run: Long) { cancelled += run }

        override fun wasCancelled(ptr: Long, run: Long) = run in cancelled

        /** The stray cancel: named for a run that has already returned. */
        fun cancelLastRun() = cancel(0L, lastRun)

        override fun transcribe(
            ptr: Long,
            run: Long,
            audio: FloatArray,
            threads: Int,
            language: String,
            beamSize: Int,
        ): ByteArray? = try {
            runTranscribe(run)
        } finally {
            finished.countDown()
        }

        private fun runTranscribe(run: Long): ByteArray? {
            if (!blockAtEntry) {
                // An ordinary run: it is only here to be *not* aborted, so it returns at once.
                ending = if (run in cancelled) Ending.CANCELLED else Ending.FINISHED
                return if (ending == Ending.CANCELLED) null else "готово".toByteArray()
            }
            atEntry.countDown()
            proceed.await(STALL.inWholeSeconds, TimeUnit.SECONDS)
            // **The entry decision, and it is the whole of `B-181`.** The bridge used to clear the
            // abort flag here unconditionally, which wiped a cancel that had just been set for
            // THIS run. Asking whether this run was cancelled answers the same question the clear
            // was there to answer — *is a cancel meant for me?* — without losing one.
            if (run in cancelled) {
                ending = Ending.CANCELLED
                return null
            }
            // Parked in C, as far as the coroutine machinery is concerned. A lost cancel shows up
            // here as the deadline elapsing — the burned engine thread `B-181` is about — and the
            // test then fails by name in three seconds rather than as a suite that stopped.
            val deadline = System.nanoTime() + STALL.inWholeNanoseconds
            while (run !in cancelled && System.nanoTime() < deadline) Thread.sleep(1)
            if (run in cancelled) {
                ending = Ending.CANCELLED
                return null
            }
            ending = Ending.DEADLINE
            return "готово".toByteArray()
        }

        private companion object { val STALL = 3.seconds }
    }

    /**
     * The defect itself: cancelling the job must reach the blocking call. Before this the job
     * cancelled, the coroutine stayed parked, and the thread ran to completion minutes later.
     */
    @Test fun `cancelling the job stops the native run`() = runTest(timeout = TEST_BOUND) {
        val native = FakeNative()
        val engine = WhisperEngine(store(), native = native)
        try {
            val run = async(Dispatchers.IO) {
                engine.transcribe(ShortArray(16_000), 16_000, "ru")
            }
            assertTrue(
                "the fake never entered the native call",
                withContext(Dispatchers.IO) { native.entered.await(5, TimeUnit.SECONDS) },
            )

            run.cancel()

            withContext(Dispatchers.IO) { run.join() }
            assertEquals("the native run was never asked to stop", 1, native.cancelCalls.get())
        } finally {
            engine.close()
        }
    }

    /**
     * **A cancelled run is not a failed one.** `whisper_full` reports both as a non-zero return,
     * and the difference is the whole of what the person is told: *you stopped it* against *your
     * dictation is gone*. Reported as an ordinary `CancellationException` so every caller that
     * already handles cancellation keeps working — no new error shape, string or banner.
     */
    @Test fun `a cancelled run is reported as cancellation, not as a failure`() = runTest(timeout = TEST_BOUND) {
        val native = FakeNative()
        val engine = WhisperEngine(store(), native = native)
        try {
            val run = async(Dispatchers.IO) {
                engine.transcribe(ShortArray(16_000), 16_000, "ru")
            }
            withContext(Dispatchers.IO) { native.entered.await(5, TimeUnit.SECONDS) }
            run.cancel()

            val thrown = runCatching { run.await() }.exceptionOrNull()
            assertTrue(
                "a cancelled transcription surfaced as something other than cancellation: $thrown",
                thrown is CancellationException,
            )
        } finally {
            engine.close()
        }
    }

    /**
     * The other direction, and the one a careless fix breaks: a run nobody cancelled must not be
     * reported as cancelled, and the completion handler must be **disposed** — a handler left
     * registered on a reused scope fires on the next unrelated cancellation and aborts a run
     * nobody touched.
     */
    @Test fun `an ordinary run is not cancelled and leaves no handler behind`() = runTest(timeout = TEST_BOUND) {
        val native = FakeNative(blockUntilCancelled = false)
        val engine = WhisperEngine(store(), native = native)
        try {
            val first = withContext(Dispatchers.IO) {
                engine.transcribe(ShortArray(16_000), 16_000, "ru")
            }
            assertEquals("готово", first.getOrNull()?.text)
            assertEquals("a run nobody cancelled was cancelled", 0, native.cancelCalls.get())

            val second = withContext(Dispatchers.IO) {
                engine.transcribe(ShortArray(16_000), 16_000, "ru")
            }
            assertEquals("готово", second.getOrNull()?.text)
            assertEquals(
                "a disposed handler fired anyway, so a later job's end aborts an unrelated run",
                0,
                native.cancelCalls.get(),
            )
        } finally {
            engine.close()
        }
    }

    /**
     * **`B-181`: a cancel that arrives between the registration and the native entry.**
     *
     * `invokeOnCancellation` is registered before the task is submitted, and the bridge used to
     * clear the abort flag as its first act — deliberately, so that a cancel landing *between* two
     * runs could not abort the next one. A cancel landing in the window therefore set a flag the
     * run immediately wiped, and `whisper_full` went to completion with nobody waiting for it: the
     * engine thread is burned for up to the length of the dictation, and the next `close()` — a
     * model change — queues behind it, which is `B-118` through a door `B-118` did not close.
     *
     * `DEC-0062` added `if (!cont.isActive) return@execute` as the first line of the submitted
     * task. That closes the common case and not this one: the window that remains is between that
     * check and the flag-clear inside the native call.
     *
     * [WindowNative] is the bridge as it behaved, **including the clear**, and it parks at that
     * exact point so the test can put a cancel into the window instead of hoping one lands there.
     * The complete fix is the one the row names and the one [WhisperNativeCalls] now carries: the
     * run is **named**, so a flag set for run N is not something run N+1 has to clear.
     */
    @Test fun `a cancel arriving between registration and the native entry is not lost`() =
        runTest(timeout = TEST_BOUND) {
            val native = WindowNative()
            val engine = WhisperEngine(store(), native = native)
            try {
                val run = async(Dispatchers.IO) { engine.transcribe(ShortArray(16_000), 16_000, "ru") }
                assertTrue(
                    "the fake never reached the window",
                    withContext(Dispatchers.IO) { native.atEntry.await(5, TimeUnit.SECONDS) },
                )

                // The window: the task has passed `cont.isActive` and the native entry has not yet
                // decided anything about the abort flag.
                run.cancel()
                native.proceed.countDown()

                withContext(Dispatchers.IO) { run.join() }
                // The native call, not the coroutine: a cancelled job completes while the task it
                // submitted is still inside the fake, and `ending` only means anything once that
                // call has returned.
                assertTrue(
                    "the native call never returned at all",
                    withContext(Dispatchers.IO) { native.finished.await(5, TimeUnit.SECONDS) },
                )
                assertEquals(
                    "the cancel was wiped by the run it was meant for, and the engine thread ran " +
                        "to completion with nobody waiting for it (B-181)",
                    WindowNative.Ending.CANCELLED,
                    native.ending,
                )
            } finally {
                engine.close()
            }
        }

    /**
     * **The property the old shape was protecting, and which the fix must not trade away.**
     *
     * Clearing the flag at entry existed for a reason: a cancel that lands between two runs must
     * not abort the next one. A "fix" that simply stopped clearing would resurrect `I-26` in its
     * worst form — the person changes the model, and the *next* dictation reports itself cancelled
     * before it starts. Here the first run is cancelled after it has already finished, and the
     * second must be untouched.
     */
    @Test fun `a cancel that lands between two runs does not abort the next one`() =
        runTest(timeout = TEST_BOUND) {
            val native = WindowNative(blockAtEntry = false)
            val engine = WhisperEngine(store(), native = native)
            try {
                val first = withContext(Dispatchers.IO) {
                    engine.transcribe(ShortArray(16_000), 16_000, "ru")
                }
                assertEquals("готово", first.getOrNull()?.text)

                // A stray cancel for the run that just ended — the shape `invokeOnCancellation`
                // fires on a job whose body has already returned.
                native.cancelLastRun()

                val second = withContext(Dispatchers.IO) {
                    engine.transcribe(ShortArray(16_000), 16_000, "ru")
                }

                assertEquals(
                    "a cancel meant for the previous run aborted the next one (I-26)",
                    "готово",
                    second.getOrNull()?.text,
                )
                assertEquals(WindowNative.Ending.FINISHED, native.ending)
            } finally {
                engine.close()
            }
        }

    /**
     * A native failure that is **not** a cancellation still reads as a failure. Without this the
     * two collapse and a genuinely broken run reports as "you stopped it", which is the mirror of
     * the defect being fixed and just as wrong.
     */
    @Test fun `a native failure that was not cancelled still fails`() = runTest(timeout = TEST_BOUND) {
        val native = object : WhisperNativeCalls by FakeNative(blockUntilCancelled = false) {
            override fun transcribe(
                ptr: Long,
                run: Long,
                audio: FloatArray,
                threads: Int,
                language: String,
                beamSize: Int,
            ): ByteArray? = null
            override fun wasCancelled(ptr: Long, run: Long) = false
        }
        val engine = WhisperEngine(store(), native = native)
        try {
            val result = withContext(Dispatchers.IO) {
                engine.transcribe(ShortArray(16_000), 16_000, "ru")
            }
            assertTrue("a broken run reported success", result.isFailure)
            assertNull(result.getOrNull())
            assertFalse(
                "a broken run was reported as a cancellation",
                result.exceptionOrNull() is CancellationException,
            )
        } finally {
            engine.close()
        }
    }

    /**
     * Progress is **read, never pushed** (`B-146`), and reads `0` before any context exists —
     * a number rather than a null, because "not started" and "just started" are the same thing
     * to anybody drawing a bar.
     */
    @Test fun `progress reads zero before a run and the native value during one`() = runTest(timeout = TEST_BOUND) {
        val native = FakeNative(blockUntilCancelled = false)
        val engine = WhisperEngine(store(), native = native)
        try {
            assertEquals("a fresh engine reported progress it cannot have", 0, engine.progressPercent())

            withContext(Dispatchers.IO) { engine.transcribe(ShortArray(16_000), 16_000, "ru") }
            native.reportedProgress = 64

            assertEquals(
                "the engine did not read the native value",
                64,
                engine.progressPercent(),
            )
        } finally {
            engine.close()
        }
    }

    /**
     * **A transcription that arrives after `close()` never reaches the native call.**
     *
     * This is the reachable half of a defect whose other half is not reachable from here, and the
     * split is stated rather than blurred.
     *
     * *What is proven:* once the engine is closed, `transcribe` fails and `native.transcribe` is
     * not called — so no freed `whisper_context` is handed across JNI on this path.
     *
     * *What is argued and NOT measured:* before the cancellation work the guard, the context
     * creation and the blocking call were one `withContext(dispatcher)` body, so `close()` could
     * not interleave with any of it. Splitting them opened a narrow window — `close()` frees the
     * context on the engine thread and calls `executor.shutdown()` only after resuming, so a
     * submit landing between those two is accepted and would run against a freed pointer. The
     * guard inside the submitted task re-reads `closed` and `ctx` **on the engine thread**, which
     * is the thread `close()` does its work on, so the executor orders them.
     *
     * **That interleaving could not be reproduced here**, across 40 rounds with a four-million
     * sample conversion: the conversion always outlasts `close()`, so the submit is rejected by an
     * already-shut-down executor rather than accepted. A test that passes because of the rejection
     * is not evidence about the guard, and saying so is cheaper than a green that means nothing.
     * `B-179` carries a probe that could reach it. In production `LocalWhisperOwner`'s mutex also
     * prevents the overlap — that is the owner's property, and its own header says a second
     * consumer must not be able to get it wrong, which is why the guard is in the class anyway.
     */
    @Test fun `a transcription arriving after close never reaches the native call`() =
        runTest(timeout = TEST_BOUND) {
            val native = FakeNative(blockUntilCancelled = false)
            val engine = WhisperEngine(store(), native = native)

            withContext(Dispatchers.IO) { engine.transcribe(ShortArray(16_000), 16_000, "ru") }
            val before = native.transcribeCalls.get()
            withContext(Dispatchers.IO) { engine.close() }

            val after = withContext(Dispatchers.IO) {
                engine.transcribe(ShortArray(16_000), 16_000, "ru")
            }

            assertTrue("a closed engine reported success", after.isFailure)
            assertEquals(
                "the native call was reached after the context was freed",
                before,
                native.transcribeCalls.get(),
            )
            assertTrue("close() did not free the context", native.freed)
        }
}

package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner exists because nothing owned the whisper context's **lifetime**.
 *
 * `Graph.localEngine()` closed the previous engine from whichever thread asked for a new one, with
 * `runBlocking`, while holding `Graph`'s monitor — and every caller was on Main. Changing the model
 * during a transcription therefore froze the whole app for the rest of it, which on this device has
 * been measured at up to forty seconds against Android's five-second ANR limit (`I-04`).
 *
 * These tests need no native library and no device: [LocalWhisperOwner] takes its engine factory as
 * a parameter precisely so the constraint it enforces can be tested rather than argued about.
 */
class LocalWhisperOwnerTest {

    /**
     * Counts what is open and records what happened in order. `open` is the assertion the whole
     * class is for: two whisper contexts at once is up to 1.11 GB of native memory on a device with
     * no swap.
     */
    private class Fakes {
        val open = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val log = mutableListOf<String>()
        val built = AtomicInteger(0)

        @Synchronized fun record(what: String) { log += what }

        fun engine(store: ModelStore, block: (suspend () -> Unit)? = null): FakeEngine {
            built.incrementAndGet()
            val now = open.incrementAndGet()
            peak.getAndUpdate { maxOf(it, now) }
            record("open ${store.modelName}")
            return FakeEngine(store.modelName, this, block)
        }
    }

    private class FakeEngine(
        val model: String,
        private val fakes: Fakes,
        private val onTranscribe: (suspend () -> Unit)?,
    ) : SttEngine, ClosableSttEngine {
        var closes = 0
            private set

        override val name: String = "fake-$model"

        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, langHint: String?): Result<Transcript> {
            onTranscribe?.invoke()
            return Result.success(
                Transcript(text = model, language = "en", source = SttSource.LOCAL, engine = name, durationMs = 0),
            )
        }

        override suspend fun close() {
            closes++
            fakes.open.decrementAndGet()
            fakes.record("close $model")
        }
    }

    private fun store(model: WhisperModel): ModelStore = object : ModelStore {
        override val modelName = model.fileName
        override val expectedBytes = model.bytes
        override val expectedSha256 = model.sha256
        override val downloadUrl = ""
        override fun modelFile() = java.io.File(model.fileName)
        override fun isPresent() = true
    }

    private fun owner(fakes: Fakes, block: (suspend () -> Unit)? = null) =
        LocalWhisperOwner(storeFor = ::store, newEngine = { fakes.engine(it, block) })

    @Test fun `two uses of the same model share one engine`() = runTest {
        val fakes = Fakes()
        val o = owner(fakes)

        o.use(WhisperModel.SMALL) { }
        o.use(WhisperModel.SMALL) { }

        assertEquals("the model was loaded twice; the two-second load is the whole reason to cache", 1, fakes.built.get())
    }

    @Test fun `a different model closes the first engine before opening the second`() = runTest {
        val fakes = Fakes()
        val o = owner(fakes)

        o.use(WhisperModel.SMALL) { }
        o.use(WhisperModel.MEDIUM) { }

        assertEquals(
            listOf("open ggml-small-q5_1.bin", "close ggml-small-q5_1.bin", "open ggml-medium-q5_0.bin"),
            fakes.log,
        )
    }

    /**
     * The ceiling, asserted rather than documented. `Graph.withEngine`'s LOCAL branch built a
     * **throwaway** second `WhisperEngine` whenever a re-run asked for a non-selected model, so
     * `large-turbo` (574 MB) and `medium` (539 MB) could be resident at once.
     */
    @Test fun `at most one engine is open at any time`() = runTest {
        val fakes = Fakes()
        val o = owner(fakes)
        val models = WhisperModel.entries.toList()

        withContext(Dispatchers.Default) {
            (0 until 50).map { i ->
                async { o.use(models[i % models.size]) { delay(1) } }
            }.awaitAll()
        }

        assertEquals("two whisper contexts were resident at once", 1, fakes.peak.get())
        // One stays loaded on purpose — that is the cache, and `DEC-0007` is why. What must be
        // true is that it is the ONLY one, and that the owner gives it back when asked.
        assertEquals(1, fakes.open.get())
        o.release()
        assertEquals("the owner could not give the context back", 0, fakes.open.get())
    }

    @Test fun `use is serialised`() = runTest {
        val fakes = Fakes()
        val o = owner(fakes)
        val firstInside = CompletableDeferred<Unit>()
        val letFirstFinish = CompletableDeferred<Unit>()
        var secondStarted = false

        val first = launch { o.use(WhisperModel.SMALL) { firstInside.complete(Unit); letFirstFinish.await() } }
        firstInside.await()
        val second = launch { o.use(WhisperModel.SMALL) { secondStarted = true } }
        advanceUntilIdle()

        assertFalse("the second caller entered the context while the first was inside it", secondStarted)
        letFirstFinish.complete(Unit)
        first.join(); second.join()
        assertTrue(secondStarted)
    }

    @Test fun `release closes the engine and a second release is a no-op`() = runTest {
        val fakes = Fakes()
        val o = owner(fakes)
        var engine: FakeEngine? = null
        o.use(WhisperModel.SMALL) { engine = it as FakeEngine }

        o.release()
        o.release()

        assertEquals("the free ran twice, which on a real context is a use-after-free", 1, engine!!.closes)
        assertEquals(0, fakes.open.get())
    }

    /**
     * A caller who walks away must not leave 574 MB allocated. Cancellation inside `block` unwinds
     * through the mutex; the engine stays owned and the next `release` frees it exactly once.
     */
    @Test fun `a cancelled use leaves the owner usable and frees exactly once`() = runTest {
        val fakes = Fakes()
        val o = owner(fakes)
        val inside = CompletableDeferred<Unit>()

        val job = launch { o.use(WhisperModel.SMALL) { inside.complete(Unit); delay(10_000) } }
        inside.await()
        job.cancelAndJoin()

        // The lock was released by the cancellation, or this call would never return.
        o.use(WhisperModel.SMALL) { }
        assertEquals("the cancelled caller left a second context behind", 1, fakes.built.get())
        o.release()
        assertEquals(0, fakes.open.get())
    }

    /**
     * The screen has to be able to say what it is waiting for. The wait is real — a model switch
     * behind a running transcription can still be half a minute — and silence during it is a new
     * defect replacing the old one.
     */
    @Test fun `a caller that must wait announces it, and one that need not stays silent`() = runTest {
        val fakes = Fakes()
        val o = owner(fakes)
        val waits = mutableListOf<WhisperModel>()

        o.use(WhisperModel.SMALL, onWait = { waits += it }) { }
        assertEquals("the first load is a wait and the person is owed the message", listOf(WhisperModel.SMALL), waits)

        waits.clear()
        o.use(WhisperModel.SMALL, onWait = { waits += it }) { }
        assertEquals("the ordinary dictation flickered a please-wait it did not need", emptyList<WhisperModel>(), waits)

        o.use(WhisperModel.MEDIUM, onWait = { waits += it }) { }
        assertEquals("a model switch is the wait this message exists for", listOf(WhisperModel.MEDIUM), waits)
    }
}

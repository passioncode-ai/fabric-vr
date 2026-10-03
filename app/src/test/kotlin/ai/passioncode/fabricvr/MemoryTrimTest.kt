package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.ClosableSttEngine
import ai.passioncode.fabricvr.stt.LocalWhisperOwner
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.WhisperModel
import android.content.ComponentCallbacks2
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lifecycle contract LC-08, audit F1 (2026-10-03): **the whisper context is released under memory
 * pressure.** The app had no `onTrimMemory` at all, so a headset running short of memory — shared
 * with the streamed desktop — had nothing to take back from this process but the process itself,
 * and the next launch then paid the model load anyway.
 *
 * The owner here is the real [LocalWhisperOwner] with a counting engine, because the property
 * worth proving is the end-to-end one: a trim reaches the native context, a decode in flight is
 * not freed under, and the next dictation reloads. `DEC-0102`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryTrimTest {

    private class Counting {
        var open = 0
        var built = 0
        var gate: CompletableDeferred<Unit>? = null

        fun engine(store: ModelStore): ClosableSttEngine {
            built++
            open++
            return object : ClosableSttEngine {
                override val name = "fake-${store.modelName}"
                override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, langHint: String?): Result<Transcript> {
                    gate?.await()
                    return Result.success(Transcript("x", "en", SttSource.LOCAL, name, 0))
                }
                override suspend fun close() { open-- }
            }
        }
    }

    private fun store(model: WhisperModel): ModelStore = object : ModelStore {
        override val modelName = model.fileName
        override val expectedBytes = model.bytes
        override val expectedSha256 = model.sha256
        override val downloadUrl = ""
        override fun modelFile() = File(model.fileName)
        override fun isPresent() = true
    }

    @Test fun `hiding the UI frees the context and the next dictation reloads it`() = runTest {
        val engines = Counting()
        val owner = LocalWhisperOwner(storeFor = ::store, newEngine = engines::engine)
        val trim = MemoryTrim(backgroundScope) { owner.release() }

        owner.use(WhisperModel.SMALL) { }
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        runCurrent()
        assertEquals("the context survived the UI being hidden", 0, engines.open)

        owner.use(WhisperModel.SMALL) { }
        assertEquals("the next dictation did not reload", 2, engines.built)
        assertEquals(1, engines.open)
    }

    /**
     * Android 14 delivers only `UI_HIDDEN` and `BACKGROUND`; older releases also deliver the
     * running-low family. Every level from `RUNNING_LOW` up is pressure worth a model load; the
     * one below it, `RUNNING_MODERATE`, is the system's earliest hint and costs nobody anything
     * yet — answering it would reload a model the person is in the middle of using.
     */
    @Test fun `every level from running-low up releases, and running-moderate does not`() {
        val releasing = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        releasing.forEach { level ->
            assertEquals("level $level must release the speech model", true, MemoryTrim.releases(level))
        }
        assertEquals(false, MemoryTrim.releases(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test fun `a trim below the threshold leaves the context loaded`() = runTest {
        val engines = Counting()
        val owner = LocalWhisperOwner(storeFor = ::store, newEngine = engines::engine)
        val trim = MemoryTrim(backgroundScope) { owner.release() }

        owner.use(WhisperModel.SMALL) { }
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        runCurrent()

        assertEquals(1, engines.open)
    }

    /**
     * The panel can be hidden while a ten-minute dictation is still being decoded on the app
     * scope. The trim must wait for that decode, not free the context under it — a free under a
     * running `whisper_full` is a native use-after-free (`I-25`).
     */
    @Test fun `a trim during a decode waits for it, then frees`() = runTest {
        val engines = Counting().apply { gate = CompletableDeferred() }
        val owner = LocalWhisperOwner(storeFor = ::store, newEngine = engines::engine)
        val trim = MemoryTrim(backgroundScope) { owner.release() }

        val decode = launch { owner.use(WhisperModel.SMALL) { it.transcribe(ShortArray(0), 16_000, null) } }
        runCurrent()
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        runCurrent()
        assertEquals("the context was freed under a running decode", 1, engines.open)

        engines.gate!!.complete(Unit)
        decode.join()
        runCurrent()
        assertEquals("the trim was lost instead of applied once the decode ended", 0, engines.open)
    }
}

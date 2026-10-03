package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.Log2
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An [SttEngine] that holds something the process has to give back.
 *
 * Only the on-device engine does: a whisper context is native memory, up to 574 MB of it, and
 * nothing reclaims it when the Kotlin object becomes garbage. The cloud and server engines hold a
 * socket at most and are ordinary [SttEngine]s.
 *
 * `close` is **suspend**, which is the point of the interface existing at all rather than
 * `java.io.Closeable`: freeing must happen on the engine's own thread, and waiting for that thread
 * from the caller's is what froze the app.
 */
interface ClosableSttEngine : SttEngine {
    suspend fun close()
}

/**
 * The single owner of this process's whisper context.
 *
 * whisper.cpp forbids concurrent access to one context, so every call already ran on one thread.
 * What was missing was an owner for the context's **lifetime**. `Graph.localEngine()` closed the
 * previously loaded engine from whichever thread asked for a new one — with `runBlocking`, holding
 * `Graph`'s monitor, and every caller was on `Dispatchers.Main.immediate`. Changing the model while
 * a transcription ran queued the free behind a `whisper_full` that could be thirty seconds from
 * finishing, and Android kills an app whose input has been unresponsive for five (`I-04`, `C-06`).
 *
 * **One context at a time is a deliberate ceiling, not an accident of caching.** The old
 * `Graph.withEngine` built a *throwaway* second engine whenever a stored recording was re-run with
 * a model that was not the selected one, so `large-turbo` (574 MB) and `medium` (539 MB) could both
 * be resident on a device with no swap. Evicting instead costs the next ordinary dictation about
 * two seconds of model load. That is the trade, and it is taken deliberately: *one context* is a
 * rule that can be stated and tested, *"at most two, briefly, under these conditions"* is not.
 *
 * It lives in `feature-stt` rather than in `app` because the constraint is whisper's, not the
 * app's — a second consumer must not be able to get it wrong.
 *
 * **"For the process" is not an owner** (lifecycle contract LC-08, audit F1 of 2026-10-03,
 * `DEC-0102`). `DEC-0007` kept the context for the process and pointed at an `onTrimMemory` that
 * never existed, so the first dictation pinned up to 574 MB of native memory until the process
 * died, on a headset that shares its memory with the streamed desktop. The context now has three
 * release triggers, each of which costs the next dictation one model load (~2 s for `small`):
 *
 * - a model change — the eviction in [use], unchanged;
 * - **an idle timeout** — [idleReleaseMs] after the last use ended, when [idleScope] is given;
 * - **memory pressure** — the app calls [release] from `onTrimMemory` (see `MemoryTrim` in `:app`).
 *
 * Every trigger goes through the same mutex, so none of them can free a context a decode is
 * inside: a release waits for the running `whisper_full` to finish, and the idle clock starts when
 * a use **ends**, never while one runs.
 *
 * @param idleScope where the idle clock runs. Null means no idle release — the pre-`DEC-0102`
 *   behaviour, kept for callers that own the lifetime some other way.
 * @param idleReleaseMs how long the context may sit unused before it is freed.
 */
class LocalWhisperOwner(
    private val storeFor: (WhisperModel) -> ModelStore,
    private val newEngine: (ModelStore) -> ClosableSttEngine = { WhisperEngine(it) },
    private val idleScope: CoroutineScope? = null,
    private val idleReleaseMs: Long = IDLE_RELEASE_MS,
) {
    private val lock = Mutex()
    private var loaded: Pair<WhisperModel, ClosableSttEngine>? = null

    /**
     * Bumped by every [use] **before** it waits for the lock, so an idle clock that runs out while
     * a caller is queued sees a different number and leaves the context alone: somebody is about
     * to need it. Read and compared under the lock by the clock itself.
     */
    private val uses = AtomicLong(0)

    /** The pending idle release, if any. Only touched while holding [lock]. */
    private var idleClock: Job? = null

    /**
     * Runs [block] against the engine for [model], loading or switching first if it has to.
     *
     * No engine reference escapes: the whole use happens inside the mutex, so there is no moment
     * when a caller holds an engine the owner has already freed. That is why this is `use` and not
     * `get` — `Graph.sttEngine()` used to hand out a router holding a `local` engine, and the
     * reference outlived any lock it was built under.
     *
     * @param onWait called, off the critical path, when this call cannot proceed at once — the
     *   context has to be loaded or swapped, or another caller is inside it. The wait is real and
     *   can be half a minute, so the screen has to be able to name it. The check reads [loaded]
     *   and the lock's state without holding the lock, which is safe **because it decides only
     *   what to say, never what to do**; the decision that matters is re-made inside.
     */
    suspend fun <T> use(
        model: WhisperModel,
        onWait: (WhisperModel) -> Unit = {},
        block: suspend (SttEngine) -> T,
    ): T {
        uses.incrementAndGet()
        if (lock.isLocked || loaded?.first != model) onWait(model)
        return lock.withLock {
            try {
                val current = loaded
                if (current == null || current.first != model) {
                    // Before the new one is constructed, never after: the whole reason this class
                    // exists is that two contexts must not be resident at the same moment.
                    current?.second?.close()
                    // Cleared BEFORE the construction. If `newEngine` throws, `loaded` would
                    // otherwise keep the pair whose engine was just closed, and every later use of
                    // that model would answer `SttFailed("engine closed")` until a different model
                    // was selected — the owner wedged rather than merely empty.
                    loaded = null
                    loaded = model to newEngine(storeFor(model))
                }
                block(loaded!!.second)
            } finally {
                // On every exit — success, failure, cancellation: the clock starts when the use
                // ENDS, so a thirteen-minute decode is never counted as idle time.
                armIdleClock()
            }
        }
    }

    /**
     * (Re)starts the idle clock. Called with [lock] held, which is what makes the cancel-and-
     * replace below one step: no other use or release can interleave with it.
     *
     * The clock's own release re-takes the lock and frees only if no use has started since it was
     * armed — the counter check, not the cancellation, is the guarantee, because a clock that has
     * already woken and is queued on the lock cannot be relied upon to observe a cancel in time.
     */
    private fun armIdleClock() {
        val scope = idleScope ?: return
        idleClock?.cancel()
        val armedAt = uses.get()
        idleClock = scope.launch {
            delay(idleReleaseMs)
            lock.withLock {
                if (uses.get() != armedAt || loaded == null) return@withLock
                Log2.i("stt.whisper.released", "reason" to "idle", "after_ms" to idleReleaseMs)
                freeLocked()
            }
        }
    }

    /** Frees the loaded context, if any. Only with [lock] held. */
    private suspend fun freeLocked() {
        loaded?.second?.close()
        loaded = null
    }

    /**
     * Frees the context. Idempotent, because a second free of the same address is a use-after-free
     * that would surface as a crash inside whisper.cpp — ours, looking like theirs (`I-25`).
     */
    suspend fun release() = lock.withLock {
        idleClock?.cancel()
        idleClock = null
        freeLocked()
    }

    companion object {
        /**
         * Five minutes. Long enough that a person pausing between two thoughts does not pay the
         * reload (~2 s for `small`, more for the larger models) on every sentence; short enough
         * that a context left behind by one dictation is not still holding 190–574 MB while the
         * person spends the next hour in the streamed desktop. `DEC-0102`.
         */
        const val IDLE_RELEASE_MS: Long = 5 * 60 * 1000L
    }
}

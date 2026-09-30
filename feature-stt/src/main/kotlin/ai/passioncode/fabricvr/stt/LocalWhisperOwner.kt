package ai.passioncode.fabricvr.stt

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
 */
class LocalWhisperOwner(
    private val storeFor: (WhisperModel) -> ModelStore,
    private val newEngine: (ModelStore) -> ClosableSttEngine = { WhisperEngine(it) },
) {
    private val lock = Mutex()
    private var loaded: Pair<WhisperModel, ClosableSttEngine>? = null

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
        if (lock.isLocked || loaded?.first != model) onWait(model)
        return lock.withLock {
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
        }
    }

    /**
     * Frees the context. Idempotent, because a second free of the same address is a use-after-free
     * that would surface as a crash inside whisper.cpp — ours, looking like theirs (`I-25`).
     */
    suspend fun release() = lock.withLock {
        loaded?.second?.close()
        loaded = null
    }
}

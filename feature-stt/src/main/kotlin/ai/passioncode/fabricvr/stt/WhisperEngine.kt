package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.runCatchingCancellable
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device transcription. whisper.cpp forbids concurrent access to one context, so every call is
 * serialised onto a single thread — the same constraint the upstream sample documents.
 */
class WhisperEngine(
    private val modelStore: ModelStore,
    private val threads: Int = defaultThreads(),
    /**
     * Beam width. Anything above 1 selects beam search, which is how whisper was published and
     * measurably more accurate on a short utterance; 1 selects greedy, which is faster. Five is
     * whisper.cpp's own default for beam search.
     */
    private val beamSize: Int = DEFAULT_BEAM_SIZE,
    /**
     * The native calls, as a seam.
     *
     * **Only a test passes this**, and it exists for the same reason `KeystoreSecureSettings`
     * takes a failure injector: the contract added here — that cancelling the coroutine actually
     * stops a running `whisper_full`, and that a stopped run is not reported as a broken one —
     * cannot be provoked from a JVM test any other way, because `whisper_full` is a blocking call
     * into a library this tier cannot load. A cancellation path nobody has watched cancel is a
     * claim (`B-147`).
     */
    private val native: WhisperNativeCalls = WhisperNativeCalls.Default,
    /**
     * Who answers *is the file itself wrong?* when the loader refuses it (`B-191`).
     *
     * Process-wide by default, because its cache is the whole reason a good model is hashed at
     * most once however many times the loader is asked. A test passes its own to count the
     * hashes; nothing else does.
     */
    private val integrity: ModelIntegrity = ModelIntegrity.Shared,
) : ClosableSttEngine {

    /**
     * The model that actually ran, not the one this class was first written for.
     *
     * It was the constant `"whisper-small-q5_1"` whatever was loaded, and it reaches
     * `Transcript.engine`, the row badge and the vault's front matter — so the feature whose whole
     * purpose is comparing models recorded the same name for all five of them (`A-14`). The
     * derivation keeps the old value byte-for-byte for the small model, so notes already written
     * still read correctly.
     */
    override val name: String =
        "whisper-" + modelStore.modelName.removePrefix("ggml-").removeSuffix(".bin")

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "whisper") }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile private var ctx: Long = 0L
    @Volatile private var closed: Boolean = false
    private val closing = AtomicBoolean(false)

    /**
     * A loaded context and the run id issued against it, both produced on the engine's own thread.
     *
     * One value rather than two locals because they are taken together and must be: the run id is
     * issued *for* that handle, and issuing it anywhere else is what the audit found (see the
     * guard in [transcribe]).
     */
    private class Opened(val handle: Long, val run: Long)

    /**
     * Everything the native side has to say about one run, read on the engine's thread before the
     * caller is resumed.
     *
     * @param bytes the transcript's UTF-8, or null for both a cancelled run and a failed one —
     *   `whisper_full` reports them the same way.
     * @param language what whisper detected, empty when it did not or when there is no transcript.
     *   Read here rather than by the caller because `detectedLanguage` dereferences the
     *   `whisper_context`, and the caller's thread has nothing ordering it against `close()`.
     * @param cancelled what tells *you stopped it* from *your dictation is gone*, asked only when
     *   there is no transcript to explain.
     */
    private class Outcome(val bytes: ByteArray?, val language: String, val cancelled: Boolean)

    /**
     * **The blocking call runs on the engine's thread; the caller suspends somewhere else.**
     *
     * The obvious shape — wrap everything in `withContext(dispatcher)` and register
     * `invokeOnCompletion` to cancel — **deadlocks, and was written that way first.**
     * `invokeOnCompletion` fires when a job *completes*, and a job whose body is parked inside
     * `whisper_full` has not completed: the handler that would free the thread waits for the
     * thread it would free. The test written before the fix hung for ten minutes, which is the
     * cheapest possible way to learn it.
     *
     * `invokeOnCancellation` is the primitive that fires on **cancellation** rather than
     * completion, and it needs the caller not to be sitting on the thread doing the work. So the
     * guards and the context creation happen on the engine's thread (both are fast), and the
     * blocking call is submitted to that same single-thread executor — serialisation is
     * unchanged, whisper.cpp still never sees two calls at once — while the caller waits in a
     * cancellable suspension that is free to be resumed from anywhere.
     */
    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, langHint: String?): Result<Transcript> {
        // **`closed` is read BEFORE dispatching, and that guard was unreachable until `DEC-0062`.**
        // It lived inside `withContext(dispatcher)` — and `close()` shuts that executor down, so
        // reaching the check required the very dispatcher whose death the check exists to report.
        // A call arriving after `close()` therefore **threw** `RejectedExecutionException`,
        // surfacing to the caller as a cancellation: the person was told they had stopped
        // something they had not. `closed` is `@Volatile` and written on the engine thread, so
        // reading it here is exactly as true and costs a load.
        //
        // **This one answers `SttFailed` and the two later ones answer cancellation, deliberately.**
        // Arriving here means the caller is holding an engine that was closed before the call
        // began — `LocalWhisperOwner` hands out the current engine, so a stale reference is a
        // caller defect and *failed* is the true word for it. The later checks fire when the
        // engine closes **during** a run, which is `I-26`: the model changed under a dictation,
        // and telling the person it failed is the thing `close()` was restructured to stop.
        if (closed) {
            return Result.failure(
                SttException(AppError.SttFailed(name, IllegalStateException("engine closed"))),
            )
        }
        val guard = withContext(dispatcher) {
            when {
                closed -> Result.failure(
                    SttException(AppError.SttFailed(name, IllegalStateException("engine closed"))),
                )
                !modelStore.isPresent() ->
                    Result.failure(SttException(AppError.ModelMissing(modelStore.modelName)))
                !native.ensureLoaded() -> Result.failure(
                    SttException(AppError.SttFailed(name, UnsatisfiedLinkError("libfabricvr_whisper"))),
                )
                else -> runCatchingCancellable {
                    if (ctx == 0L) {
                        ctx = native.initContext(modelStore.modelFile().absolutePath)
                        if (ctx == 0L) throw loaderRefused()
                    }
                    // **The run is named here, on the ENGINE thread, and that is a fix rather
                    // than a tidy-up** (audit `2026-09-22`). `beginRun` is the one query entry
                    // point that uses the bridge's *inserting* `run_state_for`, and the bridge's
                    // own header says why that matters: an inserting call made with a handle
                    // `freeContext` has already released **resurrects** a map node keyed by a
                    // dead address that nothing erases again. On the caller's thread there was
                    // nothing ordering it against `close()`, which frees the context on this
                    // thread; here the executor orders the two, exactly as `DEC-0062` ordered
                    // the `closed` re-read.
                    //
                    // `B-181`'s own invariant is untouched and is easier to see now: the run is
                    // named before `invokeOnCancellation` is registered, which is the only thing
                    // that can ask for a cancel. Nothing is dispatched that was not dispatched
                    // before — this is the guard's existing hop, not a new one.
                    Opened(ctx, native.beginRun(ctx))
                }
            }
        }
        val opened = guard.getOrElse { failure ->
            return if (failure is SttException) {
                Result.failure(failure)
            } else {
                Log2.e("stt.local.failed", failure)
                Result.failure(SttException(AppError.SttFailed(name, failure)))
            }
        }

        val audio = FloatArray(pcm.size) { i -> pcm[i] / 32768f }
        val started = System.currentTimeMillis()
        return runCatchingCancellable {
            val outcome = awaitTranscription(opened.handle, opened.run, audio, langHint)
            if (outcome.bytes == null) {
                // **A cancelled run is not a failed one.** `whisper_full` returns the same
                // non-zero for both, so the native flag is what tells them apart — and the
                // difference is the whole of what the person is told: *you stopped it* against
                // *your dictation is gone*. Reported as an ordinary `CancellationException`, so
                // every caller that already handles cancellation keeps working and no new error
                // shape, string or banner is needed.
                if (outcome.cancelled) throw CancellationException("transcription cancelled")
                error("whisper_full failed")
            }
            val text = String(outcome.bytes, Charsets.UTF_8)
            Transcript(
                text = text.trim(),
                language = outcome.language.ifBlank { langHint.orEmpty() },
                source = SttSource.LOCAL,
                engine = name,
                durationMs = System.currentTimeMillis() - started,
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                Log2.e("stt.local.failed", it)
                Result.failure(SttException(AppError.SttFailed(name, it)))
            },
        )
    }

    /**
     * `initContext` returned 0. **Whose fault that is decides what the person can do about it**
     * (`B-191`).
     *
     * whisper.cpp tells us only that the load failed, and the two causes behind it need opposite
     * answers: a file whose bytes are wrong must be **deleted** so the next attempt offers a
     * clean download, and a file that is exactly the published model must be **kept** — blaming
     * it would throw away a correct 190–574 MB download and send the person to fetch it again
     * for nothing. The digest is the only thing that tells them apart, so it is taken here and
     * nowhere else: on the failing path, once per process per file. See [ModelIntegrity] for
     * what that costs and for the one case it cannot reach.
     *
     * `ModelMissing` rather than a new error shape, because after the deletion it is simply
     * true, and it is the one that already offers *Download* and *Write instead* — the way out
     * the person did not have. `UNVERIFIABLE` (no pinned digest, or unreadable bytes) falls to
     * the engine failure, which is the honest answer when nothing here can convict the file.
     */
    private fun loaderRefused(): Throwable = when (integrity.verify(modelStore)) {
        ModelIntegrity.Verdict.CORRUPT ->
            SttException(AppError.ModelMissing(modelStore.modelName))
        else ->
            IllegalStateException("whisper context could not be created")
    }

    /**
     * How far the run in progress has got, 0..100, or the last finished run's final value.
     *
     * **Read, never pushed.** whisper's `progress_callback` fires on its own worker threads, which
     * are not attached to the JVM; delivering from there would mean attaching a thread per
     * callback, and a callback that throws across the JNI boundary calls `std::terminate`. The
     * native side writes an atomic and this reads it, so the cost of asking is a volatile load
     * and the cost of not asking is nothing (`B-146`).
     *
     * `0` before the first run and whenever no context exists — a number, not a null, because
     * "not started" and "just started" are the same thing to anybody drawing a bar.
     */
    fun progressPercent(): Int = if (ctx == 0L) 0 else native.progress(ctx)

    /**
     * Runs the blocking native call on the engine's single thread and waits for it cancellably.
     *
     * `invokeOnCancellation` runs on **the canceller's** thread — never this engine's, which is
     * the whole point, since that one is parked in C. It names [run] to the native side, which
     * records it and polls an atomic through `abort_callback`; nothing has to be delivered to a
     * blocked thread, because nothing can be.
     *
     * **[run] is allocated by the caller, before this function is entered** (`B-181`). It has to
     * be: the handler registered on the next line can fire immediately, and a cancel that could
     * not say which run it meant was a cancel the run itself then cleared.
     */
    private suspend fun awaitTranscription(
        handle: Long,
        run: Long,
        audio: FloatArray,
        langHint: String?,
    ): Outcome = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { native.cancel(handle, run) }
        val submitted = runCatching {
            executor.execute {
                // A caller that is already gone gets no work done for it — cheap, and it saves
                // converting nothing into nothing. It used to be load-bearing as well, as the
                // narrowing of a cancel that could otherwise be lost between here and the native
                // entry; `B-181` closed that window properly by naming the run, so this is back
                // to being an optimisation rather than half a fix.
                if (!cont.isActive) return@execute
                // **`closed` is re-read HERE, on the engine thread, and that is the point.**
                // The guard that let this run through executed in an earlier task; between the
                // two the caller converts the audio — millions of elements for a ten-minute
                // dictation — and `close()` can take the engine thread in between and free the
                // context. The native call would then be handed a freed `whisper_context`.
                // `close()` does its work on this same single thread, so a check made here is
                // ordered against it by the executor rather than by hope (`DEC-0062`).
                //
                // In production `LocalWhisperOwner`'s mutex also prevents the overlap. That is
                // the owner's property; a class that is only safe because of who calls it is not
                // safe, and that owner says so in its own header.
                val outcome = runCatching {
                    // **A closed engine is a cancellation, never a failure** — the same choice
                    // the abort path makes two screens up, for the same reason. `I-26` is the
                    // incident: an in-flight transcription woke, saw `closed`, and answered
                    // *your dictation failed* when what happened is that the person changed the
                    // model. `close()` moved `closed = true` to the end to fix that; reporting
                    // `SttFailed` from here would put it straight back (`DEC-0062`).
                    if (closed || ctx == 0L) {
                        throw CancellationException("the engine closed while this run was queued")
                    }
                    val bytes = native.transcribe(handle, run, audio, threads, langHint ?: "auto", beamSize)
                    // **Both answers are taken HERE, on the engine thread, and `detectedLanguage`
                    // is the one that makes it necessary** (audit `2026-09-22`). It is the only
                    // query that dereferences the `whisper_context` itself —
                    // `whisper_full_lang_id(ctx)` — and it was asked on the caller's thread, from
                    // a handle captured before the guard, with nothing ordering it against the
                    // `close()` that frees that context on THIS thread. A model change landing in
                    // that window is a use-after-free inside the speech engine.
                    //
                    // `wasCancelled` reads the run map and never the context, so it was already
                    // safe; it comes along because splitting the pair would leave a reader to
                    // work out which of two adjacent native calls is the dangerous one, and this
                    // class's own header is about a class that is safe only because of its
                    // caller.
                    Outcome(
                        bytes = bytes,
                        language = if (bytes != null) native.detectedLanguage(handle) else "",
                        cancelled = bytes == null && native.wasCancelled(handle, run),
                    )
                }
                // The continuation may already be gone: a cancelled caller resumes before the
                // native call notices the abort flag, which is expected rather than exceptional.
                if (cont.isActive) cont.resumeWith(outcome)
            }
        }
        // `close()` shuts the executor down, so a call racing it is rejected rather than lost —
        // and a rejection means **the engine went away**, which is the same event as the check
        // inside the task and gets the same answer. Reported as `RejectedExecutionException` it
        // reached the person as *your dictation failed*, which is `I-26` again through a
        // different door (`DEC-0062`).
        submitted.exceptionOrNull()?.let { rejected ->
            if (cont.isActive) {
                cont.resumeWith(
                    Result.failure(
                        CancellationException("the engine closed before this run could start")
                            .initCause(rejected),
                    ),
                )
            }
        }
    }

    /**
     * Frees the native context **on the engine's own thread**, without holding the caller's.
     *
     * It was `runBlocking(dispatcher)`, which parks the *calling* thread until the whisper thread's
     * queue drains — and the queue may hold a `whisper_full` thirty seconds from finishing. Every
     * caller was on Main, so changing the model mid-transcription froze the app past Android's
     * five-second ANR limit (`I-04`). `withContext` suspends instead: the free still happens on the
     * whisper thread, the caller still waits for it, and no OS thread is held while it does.
     *
     * `NonCancellable`, because a cancelled close leaks up to 574 MB of native memory that nothing
     * else will ever free — whisper.cpp holds the weights outside the Java heap and the GC cannot
     * see them.
     *
     * The context is otherwise process-lifetime by design (`DEC-0007`); who decides when that
     * lifetime ends is [LocalWhisperOwner]'s job, and this method is never called from anywhere
     * else.
     */
    override suspend fun close() {
        // Idempotent. A second free of the same address is a use-after-free, and after
        // `executor.shutdown()` the dispatcher rejects work and kotlinx falls back to its default
        // executor — so the second free would run OFF the whisper thread, which is exactly what
        // this method's own contract exists to prevent (`I-25`).
        if (!closing.compareAndSet(false, true)) return
        runCatching {
            withContext(dispatcher + NonCancellable) {
                if (ctx != 0L) {
                    native.freeContext(ctx)
                    ctx = 0L
                }
                // Set HERE, on the whisper thread, after everything queued ahead of it has run.
                // It used to be set first, so a transcription already in flight woke, saw `closed`
                // and answered `SttFailed("engine closed")` — the person was told their dictation
                // had failed when what happened was that the model changed under it (`I-26`).
                closed = true
            }
        }
        executor.shutdown()
    }

    companion object {
        /** Leave a core for the compositor: a headset that stutters is worse than a slower transcript. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

        /** whisper.cpp's own default for beam search. */
        const val DEFAULT_BEAM_SIZE = 5
    }
}

class SttException(val error: AppError) : Exception(error.cause)

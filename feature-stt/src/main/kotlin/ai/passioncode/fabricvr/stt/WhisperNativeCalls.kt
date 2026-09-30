package ai.passioncode.fabricvr.stt

/**
 * The JNI surface, behind an interface, so the contract above it can be tested.
 *
 * `WhisperNative` is an `object` holding `external fun`s: calling one from a JVM test throws
 * `UnsatisfiedLinkError`, so **every rule `WhisperEngine` enforces around those calls was
 * unreachable by any test** — including the one this interface was extracted for, that cancelling
 * the coroutine stops a running `whisper_full` and that a stopped run is not reported as a broken
 * one (`B-147`, `B-118`).
 *
 * The same shape and the same reason as `KeystoreSecureSettings`'s failure injector: a rule about
 * a call nobody can make in a test is a claim. [Default] delegates to the real bridge and adds
 * nothing, so the production path is unchanged.
 *
 * **Every call about a run now NAMES the run** (`B-181`). A per-context flag could not tell *stop
 * the run that is starting* from *stop the run that just ended*, and the bridge resolved that by
 * clearing the flag as the first act of each run — which silently lost a cancel that arrived in
 * between the two. See [beginRun].
 */
interface WhisperNativeCalls {
    fun ensureLoaded(): Boolean
    fun initContext(modelPath: String): Long
    fun freeContext(ptr: Long)

    /**
     * Reserves an identity for one transcription, before anything can be cancelled.
     *
     * **This is `B-181`'s fix and the whole of it.** `invokeOnCancellation` is registered before
     * the blocking call is submitted, so a cancel can land in between — and the bridge cleared the
     * abort flag as the first thing every run did, so that cancel set a flag the run immediately
     * wiped. `whisper_full` then went to completion with nobody waiting for it: the engine thread
     * is burned for the length of the dictation, and the next `close()` — a model change — queues
     * behind it.
     *
     * The clear existed to protect a real property: a cancel that lands *between* two runs must
     * not abort the next one, which would be `I-26` in its worst form. Naming the run keeps that
     * property **by construction** — a cancel for run N is not something run N+1 has to clear —
     * and stops losing the other case.
     *
     * Ids are per context and monotonic; `0` is never issued and means *no run*.
     */
    fun beginRun(ptr: Long): Long

    /**
     * @param run the identity [beginRun] issued for this call. The bridge aborts it when a
     *   [cancel] naming that same id has arrived — whether before the call started or during it.
     */
    fun transcribe(
        ptr: Long,
        run: Long,
        audio: FloatArray,
        threads: Int,
        language: String,
        beamSize: Int,
    ): ByteArray?

    fun detectedLanguage(ptr: Long): String

    /**
     * Ask [run] to stop. Safe from any thread, and safe before it has started or after it has
     * finished: a cancel names the run it means, so one that arrives for a run which never runs is
     * simply never observed, and one that arrives for a finished run is not the next run's problem.
     */
    fun cancel(ptr: Long, run: Long)

    /** 0..100 for the run in progress, or for the last one that finished (`B-146`). */
    fun progress(ptr: Long): Int

    /** Whether [run] ended because somebody asked it to, rather than because it broke. */
    fun wasCancelled(ptr: Long, run: Long): Boolean

    object Default : WhisperNativeCalls {
        override fun ensureLoaded(): Boolean = WhisperNative.ensureLoaded()
        override fun initContext(modelPath: String): Long = WhisperNative.initContext(modelPath)
        override fun freeContext(ptr: Long) = WhisperNative.freeContext(ptr)
        override fun beginRun(ptr: Long): Long = WhisperNative.beginRun(ptr)
        override fun transcribe(
            ptr: Long,
            run: Long,
            audio: FloatArray,
            threads: Int,
            language: String,
            beamSize: Int,
        ): ByteArray? = WhisperNative.transcribe(ptr, run, audio, threads, language, beamSize)
        override fun detectedLanguage(ptr: Long): String = WhisperNative.detectedLanguage(ptr)
        override fun cancel(ptr: Long, run: Long) = WhisperNative.cancel(ptr, run)
        override fun progress(ptr: Long): Int = WhisperNative.progress(ptr)
        override fun wasCancelled(ptr: Long, run: Long): Boolean = WhisperNative.wasCancelled(ptr, run)
    }
}

package ai.passioncode.fabricvr.stt

/**
 * The JNI surface of `libfabricvr_whisper.so`. Unlike upstream's Android sample, [transcribe] takes
 * a language, which is what makes Russian dictation possible at all.
 *
 * **Every call about a run carries a run id** (`B-181`). See [beginRun].
 */
object WhisperNative {
    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return runCatching {
            System.loadLibrary("fabricvr_whisper")
            loaded = true
            true
        }.getOrElse { false }
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)

    /**
     * Reserves an identity for one transcription. Monotonic per context; `0` means *no run* and is
     * never issued.
     *
     * It must be called **before** anything can ask for a cancel — in practice before
     * `invokeOnCancellation` is registered — because naming the run is what stops a cancel that
     * lands in that window from being wiped by the run it was meant for (`B-181`).
     */
    external fun beginRun(ptr: Long): Long

    /**
     * UTF-8 bytes rather than a String: `NewStringUTF` expects *modified* UTF-8, and a transcript
     * can legitimately contain a four-byte codepoint that would make a malformed jstring.
     *
     * @param run the id [beginRun] issued. A [cancel] naming it aborts this call, whether it
     *   arrived before the call started or during it.
     */
    external fun transcribe(
        ptr: Long,
        run: Long,
        audio: FloatArray,
        threads: Int,
        language: String,
        beamSize: Int,
    ): ByteArray?

    /**
     * Ask [run] to stop. Safe from any thread, and safe before it starts or after it ends: a
     * cancel names the run it means, so it can neither be lost by the run it was meant for nor
     * abort the one after it.
     */
    external fun cancel(ptr: Long, run: Long)

    /** 0..100 for the run in progress, or for the last one that finished. */
    external fun progress(ptr: Long): Int

    /**
     * Whether [run] ended because somebody asked it to.
     *
     * `transcribe` returns `null` for both a cancellation and a real failure — `whisper_full`
     * reports them the same way — and the difference is the whole of what the person is told:
     * *you stopped it* against *your dictation is gone*.
     */
    external fun wasCancelled(ptr: Long, run: Long): Boolean

    external fun detectedLanguage(ptr: Long): String
}

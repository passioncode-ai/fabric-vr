package ai.passioncode.fabricvr.stt

/**
 * The recording, as 16-bit samples and nothing else.
 *
 * **It was an `ArrayList<Short>`.** On ART a `java.lang.Short` outside the −128…127 cache is a
 * sixteen-byte object plus a four-byte array slot — about twenty bytes to hold two — so a minute
 * of dictation retained roughly 19 MB and churned the same again in the per-chunk lists the
 * recorder built, and ten minutes was 190 MB beside whisper's own 190–574 MB native context on a
 * device with no swap (`E-02`). Worse, `addAll` reallocated and copied an array of up to 960 000
 * references **while holding the lock the main thread was waiting on** to end the recording
 * (`I-10`): the longer the dictation, the longer the stop took. The two findings are one defect.
 *
 * Here the critical section is a `System.arraycopy` of one chunk, and growth is doubling from an
 * initial one second, so a ten-minute recording reallocates about ten times in total rather than
 * continuously.
 *
 * **The lock stays.** It was added for a real `ConcurrentModificationException` — the producer
 * runs on `Dispatchers.IO` and the drain happens on Main — and removing synchronisation to fix a
 * *performance* finding would be re-opening a correctness one. What changed is what happens
 * inside it, not whether there is one.
 *
 * **It is bounded**, because nothing else in the app stops a recording and an unbounded one on
 * this device ends as an OOM that takes the recording with it. See `DEC-0032` for the number and
 * why transcription time rather than memory is what chose it.
 *
 * It lives beside [AudioRecorder] rather than in a view model because the constraint — 16 kHz
 * mono shorts, whisper-shaped — is the recorder's, not the screen's.
 */
class PcmBuffer(private val maxSamples: Int) {

    private val lock = Any()
    private var data = ShortArray(INITIAL)
    private var size = 0

    /** The backing array's length. For the test that would catch boxing coming back. */
    val capacity: Int get() = synchronized(lock) { data.size }

    val sampleCount: Int get() = synchronized(lock) { size }

    /**
     * Copies the first [count] samples of [src].
     *
     * [src] is the recorder's own buffer and is overwritten on the next read, which is the whole
     * point of taking it by reference — the producer allocates nothing per chunk.
     *
     * A chunk that crosses [maxSamples] is **truncated, not dropped**: what is kept is the
     * beginning of what the person said, and discarding a whole buffer at the boundary would lose
     * up to a chunk of speech at the exact moment the app says it is stopping.
     *
     * @return false when the buffer is now full and the recording must end. It keeps returning
     *   false, so the caller's stop has to be idempotent rather than relying on this firing once.
     */
    fun append(src: ShortArray, count: Int): Boolean = synchronized(lock) {
        val room = maxSamples - size
        if (room <= 0) return false
        val take = minOf(count, room)
        if (size + take > data.size) grow(size + take)
        System.arraycopy(src, 0, data, size, take)
        size += take
        take == count && size < maxSamples
    }

    /**
     * A copy of what has been recorded. **Does not clear** — `stopAndTranscribe` drains before the
     * producer job is joined and both callers depend on the buffer still being there afterwards.
     */
    fun drain(): ShortArray = synchronized(lock) { data.copyOf(size) }

    fun clear() = synchronized(lock) {
        size = 0
        // The array itself is kept: a person who records twice in a row should not pay for the
        // growth twice, and it is bounded by `maxSamples` either way.
    }

    private fun grow(needed: Int) {
        var next = data.size
        while (next < needed) next *= 2
        data = data.copyOf(minOf(next, maxSamples))
    }

    private companion object {
        /** One second. Long enough that a short dictation never reallocates at all. */
        const val INITIAL = AudioRecorder.SAMPLE_RATE
    }
}

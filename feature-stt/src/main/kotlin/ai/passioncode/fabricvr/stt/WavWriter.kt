package ai.passioncode.fabricvr.stt

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-bit mono PCM as a RIFF/WAVE file — what whisper-server accepts and what the vault stores.
 *
 * **Two producers, one format.** [toWav] exists because both HTTP clients post the recording as a
 * multipart body and OkHttp's `toRequestBody` wants the bytes; [writeTo] exists because the disk
 * path must not hold a second, third and fourth copy of a dictation to save it. They are checked
 * against each other byte for byte in `WavWriterStreamTest`, so there is no room for them to
 * become two formats.
 */
object WavWriter {

    /** The RIFF/WAVE header this writer emits: fixed size, so it is also the offset of sample 0. */
    const val HEADER_BYTES = 44

    /** The bound on a single buffer on the disk path (`B-202`). Even, so a sample never straddles. */
    const val CHUNK_BYTES = 1 shl 15

    /**
     * The 44-byte RIFF/WAVE header for `dataSize` bytes of 16-bit mono PCM.
     *
     * Its size is fixed and known before a single sample is encoded, which is the whole reason
     * [writeTo] can stream: nothing downstream has to be rewound to patch a length in.
     */
    private fun header(dataSize: Int, sampleRate: Int): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)               // PCM chunk size
            putShort(1)              // PCM
            putShort(1)              // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)   // byte rate
            putShort(2)              // block align
            putShort(16)             // bits per sample
            put("data".toByteArray())
            putInt(dataSize)
        }.array()

    /**
     * The whole file as one array. **Only for callers that genuinely need every byte at once** —
     * today that is the two HTTP clients, whose request body is a `ByteArray`.
     *
     * One allocation of `44 + 2n`, down from the three the audit counted (`M17`): the
     * `ByteArrayOutputStream`, the separate body buffer and the `toByteArray` copy were three
     * full-length arrays alive at the same instant. Anything writing to a stream should call
     * [writeTo] instead and allocate [CHUNK_BYTES].
     */
    fun toWav(pcm: ShortArray, sampleRate: Int = 16_000): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteBuffer.allocate(HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put(header(dataSize, sampleRate))
        pcm.forEach(out::putShort)
        return out.array()
    }

    /**
     * Encode `pcm` into `out`, header first, in buffers of at most [CHUNK_BYTES].
     *
     * **This is what `B-202` is about.** A ten-minute dictation is 19.2 MB of PCM and the previous
     * disk path had three further copies of it live at the moment of the write — near 77 MB on a
     * device with no `largeHeap`. Here the allocation is a function of the buffer and not of how
     * long somebody spoke; `WavWriterStreamTest` asserts that by writing four times the audio and
     * requiring the same largest buffer.
     *
     * The stream is not closed or flushed here — whoever opened it owns it.
     */
    fun writeTo(out: OutputStream, pcm: ShortArray, sampleRate: Int = 16_000) {
        val dataSize = pcm.size * 2
        out.write(header(dataSize, sampleRate))
        if (dataSize == 0) return

        val chunk = ByteBuffer.allocate(minOf(CHUNK_BYTES, dataSize)).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < pcm.size) {
            chunk.clear()
            while (i < pcm.size && chunk.remaining() >= 2) {
                chunk.putShort(pcm[i])
                i++
            }
            out.write(chunk.array(), 0, chunk.position())
        }
    }

    /**
     * Write the recording to `file`, allocation-bounded.
     *
     * `openSink` is a seam and only a test passes it — the same shape [ModelDownloader] uses, and
     * for the same reason: the size of the largest buffer this path hands the filesystem is the
     * property the row is about, and a bound nobody has watched hold is a sentence rather than a
     * measurement.
     *
     * **A failed write leaves nothing behind.** Streaming widens the window in which the disk can
     * fill up mid-file, and a truncated recording whose header claims a longer body is worse than
     * no recording: the caller (`VoiceViewModel`) reports *the recording was not kept* and sets
     * `audioPath = null`, and that message must not sit next to a playable-looking file.
     */
    fun write(
        file: File,
        pcm: ShortArray,
        sampleRate: Int = 16_000,
        openSink: (File) -> OutputStream = { FileOutputStream(it) },
    ): File {
        file.parentFile?.mkdirs()
        try {
            openSink(file).use { writeTo(it, pcm, sampleRate) }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return file
    }

    fun readPcm(bytes: ByteArray): ShortArray {
        require(bytes.size > HEADER_BYTES) { "not a wav file" }
        val body = ByteBuffer.wrap(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray((bytes.size - HEADER_BYTES) / 2)
        for (i in samples.indices) samples[i] = body.short
        return samples
    }
}

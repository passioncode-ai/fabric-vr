package ai.passioncode.fabricvr.stt

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **`B-202`, audit item `M17`: writing a recording to disk must not hold the recording in memory
 * three more times.**
 *
 * The measured shape was three full copies of the encoded audio alive at once — a
 * `ByteArrayOutputStream`'s buffer, the `ByteBuffer` holding the samples and the `toByteArray`
 * result — on top of the caller's own `ShortArray`. A ten-minute dictation is 19.2 MB of PCM, so
 * that is ~77 MB live at the instant of the write, on a device with no `largeHeap`.
 *
 * **The largest single allocation is asserted, not the heap.** A heap probe in a unit test
 * measures the collector's mood as much as the code, and the number it prints changes when an
 * unrelated test runs before it. What actually decides whether this path scales with the length
 * of a dictation is the size of the biggest buffer it hands the sink, and that is observable
 * exactly — through the `openSink` seam, which is the shape [ModelDownloader] already uses for
 * the same reason: a claim nobody has watched is a claim.
 *
 * The bound is a **constant**, so a sample four times longer must not move it. That is the whole
 * property: allocation bounded by the buffer, not by the recording.
 */
class WavWriterStreamTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * A sink that records the largest single buffer handed to it and keeps what passed through.
     *
     * `write(ByteArray)` is overridden as well: `OutputStream`'s own default forwards to the
     * three-argument form, but a subclass that only overrode the latter would silently stop
     * measuring if that ever changed.
     */
    private class Measured(private val keep: Boolean = true) : OutputStream() {
        val body = ByteArrayOutputStream()
        var largestWrite: Int = 0
            private set
        var total: Long = 0
            private set

        override fun write(b: Int) {
            largestWrite = maxOf(largestWrite, 1)
            total += 1
            if (keep) body.write(b)
        }

        override fun write(b: ByteArray) = write(b, 0, b.size)

        override fun write(b: ByteArray, off: Int, len: Int) {
            largestWrite = maxOf(largestWrite, len)
            total += len
            if (keep) body.write(b, off, len)
        }
    }

    /**
     * The canary (`SI-06`). A bound that is asserted by a stream which never sees a large write
     * would pass over a broken writer exactly as it passes over a correct one, so the measurement
     * is first shown catching the thing it exists to catch: the whole file in one call.
     */
    @Test fun `the measurement can see a single large write`() {
        val pcm = ShortArray(SAMPLES) { (it % 997).toShort() }
        val whole = WavWriter.toWav(pcm, 16_000)

        val sink = Measured()
        sink.write(whole)

        assertEquals(
            "the seam did not record the size of a single whole-file write — it proves nothing",
            WavWriter.HEADER_BYTES + pcm.size * 2,
            sink.largestWrite,
        )
    }

    @Test fun `a five-megabyte recording reaches the sink in bounded chunks`() {
        val pcm = ShortArray(SAMPLES) { (it % 997).toShort() }

        val sink = Measured()
        WavWriter.write(temp.newFile("bounded.wav"), pcm, 16_000) { sink }

        assertEquals(
            "the sink did not receive the whole file",
            (WavWriter.HEADER_BYTES + pcm.size.toLong() * 2),
            sink.total,
        )
        assertTrue(
            "WavWriter.write handed the sink a ${sink.largestWrite}-byte buffer for a " +
                "${pcm.size * 2}-byte recording — the write is still allocating one copy of the " +
                "whole dictation (B-202/M17). The bound is ${WavWriter.CHUNK_BYTES} bytes.",
            sink.largestWrite <= WavWriter.CHUNK_BYTES,
        )
    }

    /**
     * And the bound does not move with the recording — the point of the row. Four times the audio,
     * the same largest buffer, or the allocation is a function of how long somebody spoke.
     */
    @Test fun `the largest buffer does not grow with the recording`() {
        val short = Measured(keep = false)
        val long = Measured(keep = false)

        WavWriter.write(temp.newFile("short.wav"), ShortArray(SAMPLES) { 1 }, 16_000) { short }
        WavWriter.write(temp.newFile("long.wav"), ShortArray(SAMPLES * 4) { 1 }, 16_000) { long }

        assertEquals(
            "four times the audio produced a larger single buffer: ${short.largestWrite} -> " +
                "${long.largestWrite}. The write still scales with the length of the dictation.",
            short.largestWrite,
            long.largestWrite,
        )
    }

    /**
     * The streamed bytes are the in-memory encoding, byte for byte. `toWav` stays because both
     * HTTP clients post a request body ([RemoteWhisperClient], [CloudTranscriptionClient]); the
     * two producers must not be allowed to drift into two formats.
     */
    @Test fun `the streamed file is byte-identical to the in-memory encoding`() {
        val pcm = ShortArray(SAMPLES) { (it * 31 % 65_536 - 32_768).toShort() }

        val sink = Measured()
        WavWriter.write(temp.newFile("identical.wav"), pcm, 22_050) { sink }

        assertArrayEquals(
            "the streamed encoding differs from toWav — two producers, two formats",
            WavWriter.toWav(pcm, 22_050),
            sink.body.toByteArray(),
        )
    }

    /**
     * The round trip `retranscribe` depends on, over a multi-megabyte sample and through a real
     * file rather than the seam: `NotesViewModel` reads these files back with
     * [WavWriter.readPcm] and hands the samples to whisper. A format that survives six samples
     * and loses one in five million is a format that loses a dictation.
     */
    @Test fun `five million samples survive the trip through a real file`() {
        val noise = java.util.Random(20_260_922L)
        val pcm = ShortArray(SAMPLES) { noise.nextInt(65_536).minus(32_768).toShort() }
        val file = WavWriter.write(File(temp.newFolder("audio"), "round-trip.wav"), pcm, 16_000)

        assertEquals(
            "the file on disk is not header + samples",
            (WavWriter.HEADER_BYTES + pcm.size.toLong() * 2),
            file.length(),
        )
        assertTrue(
            "the samples read back differ from the samples written",
            pcm.contentEquals(WavWriter.readPcm(file.readBytes())),
        )
    }

    /** An empty recording is still a valid WAV, and the chunk loop must not trip over it. */
    @Test fun `an empty recording is a bare header`() {
        val sink = Measured()
        WavWriter.write(temp.newFile("empty.wav"), ShortArray(0), 16_000) { sink }

        assertArrayEquals(WavWriter.toWav(ShortArray(0)), sink.body.toByteArray())
        assertEquals(WavWriter.HEADER_BYTES.toLong(), sink.total)
    }

    /**
     * **A half-written recording is not left behind.** Streaming widens the window in which the
     * disk can fill up mid-write, and a truncated file whose header claims a longer body is worse
     * than no file: the caller's failure path says *the recording was not kept*
     * (`state_recording_not_kept`) and sets `audioPath = null`, which would then be a lie told
     * next to a playable-looking file nothing points at.
     */
    @Test fun `a failed write leaves no partial file`() {
        val file = File(temp.newFolder("failing"), "partial.wav")
        val boom = object : OutputStream() {
            var written = 0L
            override fun write(b: Int) = throw IOException("ENOSPC")
            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
                if (written > WavWriter.HEADER_BYTES) throw IOException("ENOSPC (No space left on device)")
                File(file.path).writeBytes(ByteArray(len))
            }
        }

        var thrown: Throwable? = null
        try {
            WavWriter.write(file, ShortArray(SAMPLES) { 1 }, 16_000) { boom }
        } catch (e: IOException) {
            thrown = e
        }

        assertTrue("a failed write did not report the failure", thrown is IOException)
        assertFalse("a truncated recording was left on disk", file.exists())
    }

    private companion object {
        /** 2.5 M samples = 5 MB of PCM: multi-megabyte, and ~150× the chunk bound. */
        const val SAMPLES = 2_500_000
    }
}

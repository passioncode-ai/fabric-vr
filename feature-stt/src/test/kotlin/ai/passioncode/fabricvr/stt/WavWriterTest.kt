package ai.passioncode.fabricvr.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class WavWriterTest {

    @Test fun `a wav carries a RIFF header and the right sizes`() {
        val pcm = ShortArray(800) { (it % 1000).toShort() }
        val wav = WavWriter.toWav(pcm, 16_000)

        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
    }

    @Test fun `samples survive the round trip`() {
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768, 1234)
        assertEquals(pcm.toList(), WavWriter.readPcm(WavWriter.toWav(pcm)).toList())
    }
}

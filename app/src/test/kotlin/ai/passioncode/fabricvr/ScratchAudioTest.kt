package ai.passioncode.fabricvr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `C-06`'s other half. A recording that never became a note was left in `filesDir/audio` for ever:
 * one writer, one delete-on-discard, and no listing anywhere in the app. At 32 kB a second that is
 * an unbounded voice archive on a device whose storage a person cannot see.
 */
class ScratchAudioTest {

    @get:Rule val temp = TemporaryFolder()

    private fun wav(name: String, ageMs: Long): File =
        File(temp.root, name).apply {
            writeBytes(ByteArray(64))
            check(setLastModified(NOW - ageMs)) { "the fixture could not age $name" }
        }

    @Test fun `an orphan older than a day is deleted`() {
        val old = wav("old.wav", 2 * DAY)

        assertEquals(1, sweepScratchAudio(temp.root, now = NOW))

        assertFalse(old.exists())
    }

    /**
     * The assertion that keeps this from becoming the bug it prevents. A file written seconds ago
     * may belong to a transcription **still running**, whose note is about to be written — the
     * whisper thread finishes a `whisper_full` against a dead activity by design, because
     * cancellation cannot interrupt a blocking JNI call.
     */
    @Test fun `a recording younger than the threshold is left alone`() {
        val fresh = wav("fresh.wav", 60_000)
        val borderline = wav("borderline.wav", DAY - 1_000)

        assertEquals(0, sweepScratchAudio(temp.root, now = NOW))

        assertTrue(fresh.exists())
        assertTrue("a file one second inside the window was taken", borderline.exists())
    }

    @Test fun `a directory that does not exist is not an error`() {
        assertEquals(0, sweepScratchAudio(File(temp.root, "never-created"), now = NOW))
    }

    @Test fun `a sub-directory is not swept`() {
        val dir = File(temp.root, "nested").apply { mkdirs(); setLastModified(NOW - 5 * DAY) }

        assertEquals(0, sweepScratchAudio(temp.root, now = NOW))

        assertTrue(dir.isDirectory)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}

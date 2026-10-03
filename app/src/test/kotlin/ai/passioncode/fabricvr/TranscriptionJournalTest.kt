package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lifecycle contract LC-03, audit F2 (2026-10-03): **a recording is journalled as awaiting
 * transcription before its decode starts, and the next launch resumes it.**
 *
 * The decode of a ten-minute dictation takes about thirteen minutes on the headset and runs on the
 * application scope — it survives the panel closing, not the process. With no foreground service,
 * no WorkManager and no wake lock, Android's cached-app freezer and then the low-memory killer can
 * end the process mid-decode. `DictationOutbox` stores only FINISHED transcripts, so nothing
 * recorded that a recording was still being decoded: the transcript never appeared, and the
 * launch sweep deleted the WAV a day later. Silent loss of the one thing this product exists for.
 *
 * "A new process" is modelled the only way a JVM test can model it: a second journal over the
 * same directory, with a different owner — exactly what the disk looks like after a kill, since a
 * killed process runs no `finally`.
 */
class TranscriptionJournalTest {

    @get:Rule val temp = TemporaryFolder()

    private val dir get() = File(temp.root, "awaiting")

    private fun wav(name: String = "1700000000000.wav", ageMs: Long = 0): File =
        File(temp.root, name).apply {
            writeBytes(ByteArray(64))
            if (ageMs > 0) check(setLastModified(NOW - ageMs))
        }

    private fun transcript(text: String = "spoken") = Transcript(text, "en", SttSource.LOCAL, "fake", 0)

    // ------------------------------------------------------------------------------- the journal

    @Test fun `an entry begun by one process is an orphan to the next and not to itself`() {
        val audio = wav()
        val dead = TranscriptionJournal(dir, owner = "first")
        dead.begin(audio.absolutePath, "de")

        assertEquals("a live decode was offered for resumption by its own process", emptyList<AwaitingTranscription>(), dead.orphans())

        val next = TranscriptionJournal(dir, owner = "second")
        val orphan = next.orphans().single()
        assertEquals(audio.absolutePath, orphan.audioPath)
        assertEquals("the language the person chose did not survive", "de", orphan.language)
        assertEquals(0, orphan.attempts)
    }

    @Test fun `an ended entry is gone for every later process`() {
        val audio = wav()
        val journal = TranscriptionJournal(dir, owner = "first")
        journal.begin(audio.absolutePath, "auto")
        journal.end(audio.absolutePath)

        assertEquals(emptyList<AwaitingTranscription>(), TranscriptionJournal(dir, owner = "second").orphans())
        assertTrue("an ended entry still claims its recording", journal.audioPaths().isEmpty())
    }

    @Test fun `the scratch sweep spares a journalled recording older than its threshold`() {
        val audioDir = File(temp.root, "audio").apply { mkdirs() }
        val audio = File(audioDir, "1700000000000.wav").apply { writeBytes(ByteArray(64)); check(setLastModified(NOW - 2 * DAY)) }
        TranscriptionJournal(dir, owner = "first").begin(audio.absolutePath, "auto")

        val next = TranscriptionJournal(dir, owner = "second")
        sweepScratchAudio(audioDir, now = NOW, spare = next.audioPaths())

        assertTrue("the sweep deleted a recording whose decode had not finished", audio.isFile)
    }

    @Test fun `a resume attempt is counted on disk before it runs, and the count is bounded`() {
        val audio = wav()
        TranscriptionJournal(dir, owner = "first").begin(audio.absolutePath, "auto")

        val second = TranscriptionJournal(dir, owner = "second")
        assertEquals(1, second.claimForResume(second.orphans().single(), maxAttempts = 2)?.attempts)

        val third = TranscriptionJournal(dir, owner = "third")
        assertEquals("the attempt was not persisted before the decode", 1, third.orphans().single().attempts)
        assertEquals(2, third.claimForResume(third.orphans().single(), maxAttempts = 2)?.attempts)

        val fourth = TranscriptionJournal(dir, owner = "fourth")
        assertNull(
            "a recording that killed two processes was offered a third decode — a crash loop",
            fourth.claimForResume(fourth.orphans().single(), maxAttempts = 2),
        )
    }

    @Test fun `an unreadable entry is discarded rather than retried on every launch`() {
        dir.mkdirs()
        File(dir, "garbage.pending").writeText("no tab here\n")

        assertEquals(emptyList<AwaitingTranscription>(), TranscriptionJournal(dir, owner = "second").orphans())
        assertTrue("the unreadable entry was left for the next launch to choke on", dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun `with no directory the journal still answers for its own process`() {
        val journal = TranscriptionJournal(null, owner = "only")
        journal.begin("/a.wav", "auto")
        assertTrue(journal.owns("/a.wav"))
        journal.end("/a.wav")
        assertTrue(journal.audioPaths().isEmpty())
    }

    // -------------------------------------------------------------------------------- the resume

    private class Calls {
        val delivered = mutableListOf<Pair<String, String>>()
        val keptOnly = mutableListOf<String>()
        var transcribed = 0
    }

    private suspend fun resume(
        journal: TranscriptionJournal,
        calls: Calls,
        result: Result<Transcript> = Result.success(transcript()),
    ) = resumeAwaitingTranscriptions(
        journal = journal,
        readPcm = { ShortArray(16_000) },
        transcribe = { _, _ -> calls.transcribed++; result },
        deliver = { t, path -> calls.delivered += t.text to path },
        keepRecordingOnly = { path -> calls.keptOnly += path; Result.success(Unit) },
        maxAttempts = 2,
    )

    @Test fun `a resumed decode delivers the words with the original recording and ends the entry`() = runTest {
        val audio = wav()
        TranscriptionJournal(dir, owner = "dead").begin(audio.absolutePath, "auto")
        val calls = Calls()

        val next = TranscriptionJournal(dir, owner = "live")
        resume(next, calls)

        assertEquals(listOf("spoken" to audio.absolutePath), calls.delivered)
        assertEquals(emptyList<AwaitingTranscription>(), TranscriptionJournal(dir, owner = "later").orphans())
    }

    /** `SCN-004`'s promise, kept after a kill too: the engine failed, so the audio becomes a note. */
    @Test fun `a resumed decode that fails keeps the recording as a note without a transcript`() = runTest {
        val audio = wav()
        TranscriptionJournal(dir, owner = "dead").begin(audio.absolutePath, "auto")
        val calls = Calls()

        resume(TranscriptionJournal(dir, owner = "live"), calls, Result.failure(IllegalStateException("engine")))

        assertEquals(emptyList<Pair<String, String>>(), calls.delivered)
        assertEquals(listOf(audio.absolutePath), calls.keptOnly)
        assertEquals(emptyList<AwaitingTranscription>(), TranscriptionJournal(dir, owner = "later").orphans())
    }

    @Test fun `a recording past its attempts is kept as a note without decoding it again`() = runTest {
        val audio = wav()
        TranscriptionJournal(dir, owner = "dead").begin(audio.absolutePath, "auto")
        repeat(2) { i ->
            val j = TranscriptionJournal(dir, owner = "crashed-$i")
            j.claimForResume(j.orphans().single(), maxAttempts = 2)
        }
        val calls = Calls()

        resume(TranscriptionJournal(dir, owner = "live"), calls)

        assertEquals("a recording that crashed two decodes was decoded a third time", 0, calls.transcribed)
        assertEquals(listOf(audio.absolutePath), calls.keptOnly)
    }

    @Test fun `a recording that is gone is dropped without a decode`() = runTest {
        TranscriptionJournal(dir, owner = "dead").begin(File(temp.root, "gone.wav").absolutePath, "auto")
        val calls = Calls()

        resume(TranscriptionJournal(dir, owner = "live"), calls)

        assertEquals(0, calls.transcribed)
        assertEquals(emptyList<String>(), calls.keptOnly)
        assertEquals(emptyList<AwaitingTranscription>(), TranscriptionJournal(dir, owner = "later").orphans())
    }

    @Test fun `a resumed decode that hears nothing writes no note`() = runTest {
        val audio = wav()
        TranscriptionJournal(dir, owner = "dead").begin(audio.absolutePath, "auto")
        val calls = Calls()

        resume(TranscriptionJournal(dir, owner = "live"), calls, Result.success(transcript(text = "  ")))

        assertEquals(emptyList<Pair<String, String>>(), calls.delivered)
        assertEquals(emptyList<String>(), calls.keptOnly)
        assertEquals(emptyList<AwaitingTranscription>(), TranscriptionJournal(dir, owner = "later").orphans())
    }

    @Test fun `the live process's own decode is never resumed under it`() = runTest {
        val audio = wav()
        val live = TranscriptionJournal(dir, owner = "live")
        live.begin(audio.absolutePath, "auto")
        val calls = Calls()

        resume(live, calls)

        assertEquals(0, calls.transcribed)
        assertTrue(live.owns(audio.absolutePath))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}

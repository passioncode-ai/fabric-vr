package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.vault.AudioUsage
import ai.passioncode.fabricvr.vault.Vault
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * `B-090`. *Transcribe again* held a `Note` across thirteen to forty seconds of decoding and then
 * wrote that snapshot back.
 *
 * Every field of the row is in the snapshot, so anything the person changed while the decode ran —
 * in the editor, which is reachable from the same list, on the same note — was reverted by an
 * action they took to **improve** the note. `T-007` closed the identical shape for the editor
 * (`I-01`) and `T-006` declined this one by name.
 *
 * The fix is a re-read before the write, merged the way `retranscribe` already merges an edited
 * body: the transcript is always the new one, and the body is replaced only when it is still
 * exactly what the last transcription produced — judged against what is on disk **now**, not
 * against the snapshot's copy of itself, which is a comparison that can only ever answer about the
 * past.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesRetranscribeFreshnessTest {

    @get:Rule val main = MainDispatcherRule()

    /**
     * A repository whose stored row can be changed underneath a running decode — which is the
     * whole subject. [NotesRetranscribeTest]'s fake answers `get` with the last thing *written*,
     * so it cannot express "somebody else edited this while you were busy".
     */
    private class EditableRepo(private var stored: Note?) : NotesRepository {
        val writes = mutableListOf<Note>()
        fun put(note: Note?) { stored = note }
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = stored?.takeIf { it.id == id }
        override suspend fun upsert(note: Note): Result<Note> {
            writes += note; stored = note; return Result.success(note)
        }
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> = Result.success(NotesRepository.newNote())
        override suspend fun count(): Int = 0
        override suspend fun recent(limit: Int): List<Note> = emptyList()
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    private class NoopVault(override val root: File) : Vault {
        override suspend fun audioUsage(): Result<AudioUsage> = Result.success(AudioUsage(0, 0L))
        override suspend fun removeAudio(note: Note): Result<Unit> = Result.success(Unit)
        override suspend fun sweepAudio(cutoff: Long): Result<AudioUsage> = Result.success(AudioUsage(0, 0L))
        override fun pathFor(note: Note): File = File(root, "notes/${note.id}.md")
        override fun audioPathFor(note: Note): File = File(root, "notes/${note.id}.wav")
        override suspend fun write(note: Note): Result<File> = Result.success(pathFor(note))
        override suspend fun remove(id: String, createdAt: Long): Result<Unit> = Result.success(Unit)
        override suspend fun restore(id: String, createdAt: Long): Result<Unit> = Result.success(Unit)
        override fun trashedFiles(id: String): List<File> = emptyList()
        override suspend fun purgeTrash(olderThanMillis: Long): Result<Int> = Result.success(0)
        override suspend fun adoptAudio(note: Note, source: File): Result<File> = Result.success(source)
    }

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "retranscribe-fresh-${System.nanoTime()}").apply { mkdirs() }

    private fun recording(root: File): File =
        File(root, "rec.wav").apply {
            parentFile?.mkdirs()
            writeBytes(ai.passioncode.fabricvr.stt.WavWriter.toWav(ShortArray(1_600) { 0 }, 16_000))
        }

    private fun transcript(text: String) = Transcript(text, "ru", SttSource.LOCAL, "whisper-small-q5_1", 900)

    private fun viewModel(repo: EditableRepo, root: File, result: Result<Transcript>) = NotesViewModel(
        repository = repo,
        today = { LocalDate.of(2026, 9, 20) },
        vault = NoopVault(root),
        dayTicks = emptyFlow(),
        transcribeWith = { _, _, _, _ -> result },
        sttLanguage = { "auto" },
        mirrorFailures = MutableStateFlow(emptyMap()),
        outbox = ai.passioncode.fabricvr.DictationOutbox(),
    )

    /**
     * The row, exactly: type into the note while the decode runs, and the re-run must not undo it.
     *
     * The edit is applied to the repository between the snapshot and the write, which is what
     * thirteen seconds of decoding looks like from the outside.
     */
    @Test fun `text typed during a decode survives the re-run`() = runTest(main.dispatcher) {
        val root = tempRoot()
        val audio = recording(root).absolutePath
        val original = NotesRepository.newNote("старая", "старая расшифровка")
            .copy(transcript = transcript("старая расшифровка"), audioPath = audio)
        val repo = EditableRepo(original)
        val vm = viewModel(repo, root, Result.success(transcript("новая расшифровка")))

        // The person opens the note and types while the engine works. `startRetranscribe` captured
        // `original` before this happened.
        repo.put(original.copy(body = "старая расшифровка и мысль, которую я только что дописал"))

        val result = vm.retranscribe(original, SttProvider.LOCAL, WhisperModel.DEFAULT)

        val written = repo.writes.lastOrNull()
        assertNotNull("nothing was written", written)
        assertEquals(
            "the decode reverted text typed while it ran — the row's whole complaint",
            "старая расшифровка и мысль, которую я только что дописал",
            written!!.body,
        )
        assertEquals("the new transcript did not reach the note", "новая расшифровка", written.transcript?.text)
        assertEquals(
            "an edited body must be reported as kept, not as replaced",
            RetranscribeResult.TranscriptOnly,
            result,
        )
    }

    /**
     * The merge still has to work in the ordinary case, and it is now judged against the row on
     * disk: a note nobody touched is replaced, title and all.
     */
    @Test fun `an untouched note is still replaced`() = runTest(main.dispatcher) {
        val root = tempRoot()
        val audio = recording(root).absolutePath
        val original = NotesRepository.newNote("старая расшифровка", "старая расшифровка")
            .copy(transcript = transcript("старая расшифровка"), audioPath = audio)
        val repo = EditableRepo(original)
        val vm = viewModel(repo, root, Result.success(transcript("новая расшифровка")))

        val result = vm.retranscribe(original, SttProvider.LOCAL, WhisperModel.DEFAULT)

        assertEquals("an untouched body was not replaced", "новая расшифровка", repo.writes.last().body)
        assertEquals(RetranscribeResult.Replaced, result)
    }

    /**
     * The other thing a re-read can now see: the note was **deleted** while the decode ran.
     *
     * Writing the snapshot back would resurrect a row the person deliberately removed — and
     * `undoDelete` is the only control that is supposed to be able to do that.
     */
    @Test fun `a note deleted during a decode is not resurrected`() = runTest(main.dispatcher) {
        val root = tempRoot()
        val audio = recording(root).absolutePath
        val original = NotesRepository.newNote("старая", "старая расшифровка")
            .copy(transcript = transcript("старая расшифровка"), audioPath = audio)
        val repo = EditableRepo(original)
        val vm = viewModel(repo, root, Result.success(transcript("новая расшифровка")))

        repo.put(null)

        val result = vm.retranscribe(original, SttProvider.LOCAL, WhisperModel.DEFAULT)

        assertTrue("a deleted note was written back by a re-run", repo.writes.isEmpty())
        assertEquals(RetranscribeResult.Failed, result)
        assertNotNull("the person was not told why nothing happened", vm.state.value.message)
    }
}

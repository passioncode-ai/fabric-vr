package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.SttException
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * *Transcribe again* is the one method in this project that can overwrite text a person typed, and
 * until `T-017` not one of its five branches had a test: it reached `Graph.withEngine` from its own
 * body, and `Graph` is an `object` with a `lateinit` context that no test can construct or reset.
 * The seam is the whole of what made these possible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesRetranscribeTest {

    @get:Rule val main = MainDispatcherRule()

    private class FakeRepo : NotesRepository {
        val saved = mutableListOf<Note>()
        var failUpsert = false

        /**
         * What the database holds, keyed properly — it used to answer `get` with the last thing
         * WRITTEN, which is null before the first write and the wrong note after it.
         *
         * `B-090` made that matter: `retranscribe` re-reads the row after the decode rather than
         * writing back a snapshot captured before it, so a fake that cannot answer "what is stored
         * under this id" cannot exercise the method at all. [holding] seeds the row a test is
         * about and returns it, so the call sites stay one expression.
         */
        private val stored = mutableMapOf<String, Note>()

        fun holding(note: Note): Note { stored[note.id] = note; return note }

        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = stored[id]
        override suspend fun upsert(note: Note): Result<Note> {
            if (failUpsert) return Result.failure(IllegalStateException("disk full"))
            saved.add(note); stored[note.id] = note; return Result.success(note)
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
        File(System.getProperty("java.io.tmpdir"), "retranscribe-${System.nanoTime()}").apply { mkdirs() }

    /** A real WAV on disk, because `retranscribe` reads and decodes one before it reaches the engine. */
    private fun recording(root: File): File =
        File(root, "rec.wav").apply {
            parentFile?.mkdirs()
            writeBytes(ai.passioncode.fabricvr.stt.WavWriter.toWav(ShortArray(1_600) { 0 }, 16_000))
        }

    private fun transcript(text: String) = Transcript(text, "ru", SttSource.LOCAL, "whisper-small-q5_1", 900)

    private fun viewModel(
        repo: FakeRepo,
        root: File,
        result: Result<Transcript> = Result.success(transcript("новая расшифровка")),
        language: String = "auto",
        seen: MutableList<String?> = mutableListOf(),
    ) = NotesViewModel(
        repository = repo,
        today = { LocalDate.of(2026, 9, 20) },
        vault = NoopVault(root),
        dayTicks = emptyFlow(),
        transcribeWith = { _, _, _, langHint -> seen += langHint; result },
        sttLanguage = { language },
        // `D-24` put a mirror collector in `init`, and its production default reaches `Graph` —
        // an `object` with a `lateinit` context. A clean one, because nothing here is about it.
        mirrorFailures = MutableStateFlow(emptyMap()),
        outbox = ai.passioncode.fabricvr.DictationOutbox(),
    )

    @Test fun `a re-run replaces an unedited body`() = runTest(main.dispatcher) {
        val root = tempRoot(); val repo = FakeRepo()
        val vm = viewModel(repo, root)
        val note = repo.holding(
            NotesRepository.newNote("старая", "старая расшифровка")
                .copy(transcript = transcript("старая расшифровка"), audioPath = recording(root).absolutePath),
        )

        val result = vm.retranscribe(note, SttProvider.LOCAL, WhisperModel.DEFAULT)
        advanceUntilIdle()

        assertEquals(RetranscribeResult.Replaced, result)
        assertEquals("новая расшифровка", repo.saved.last().body)
        assertEquals("новая расшифровка", repo.saved.last().transcript?.text)
    }

    @Test fun `a re-run keeps an edited body`() = runTest(main.dispatcher) {
        val root = tempRoot(); val repo = FakeRepo()
        val vm = viewModel(repo, root)
        val note = repo.holding(
            NotesRepository.newNote("заметка", "я это исправил руками")
                .copy(transcript = transcript("старая расшифровка"), audioPath = recording(root).absolutePath),
        )

        val result = vm.retranscribe(note, SttProvider.LOCAL, WhisperModel.DEFAULT)
        advanceUntilIdle()

        assertEquals(RetranscribeResult.TranscriptOnly, result)
        assertEquals("a re-run destroyed text the person had typed", "я это исправил руками", repo.saved.last().body)
        assertEquals("новая расшифровка", repo.saved.last().transcript?.text)
    }

    @Test fun `a re-run with no recording says so`() = runTest(main.dispatcher) {
        val repo = FakeRepo()
        val vm = viewModel(repo, tempRoot())
        val note = NotesRepository.newNote("без звука", "текст")

        val result = vm.retranscribe(note, SttProvider.LOCAL, WhisperModel.DEFAULT)
        advanceUntilIdle()

        assertEquals(RetranscribeResult.NoRecording, result)
        assertTrue("the note was touched anyway", repo.saved.isEmpty())
        assertNotNull("nothing was said about it", vm.state.value.message)
    }

    @Test fun `a re-run whose engine fails reports it and leaves the note alone`() = runTest(main.dispatcher) {
        val root = tempRoot(); val repo = FakeRepo()
        val vm = viewModel(repo, root, result = Result.failure(SttException(AppError.SttFailed("whisper"))))
        val note = repo.holding(
            NotesRepository.newNote("заметка", "текст")
                .copy(transcript = transcript("текст"), audioPath = recording(root).absolutePath),
        )

        val result = vm.retranscribe(note, SttProvider.LOCAL, WhisperModel.DEFAULT)
        advanceUntilIdle()

        assertEquals(RetranscribeResult.Failed, result)
        assertTrue("a failed re-run wrote to the note", repo.saved.isEmpty())
        assertNotNull(vm.state.value.message)
        assertNull("the row was left mid-flight", vm.state.value.retranscribing)
    }

    @Test fun `the title follows the body only when it was the transcript's first 60 chars`() = runTest(main.dispatcher) {
        val root = tempRoot()
        val old = transcript("старая расшифровка")

        val followed = FakeRepo()
        viewModel(followed, root).retranscribe(
            followed.holding(
                NotesRepository.newNote(old.text.take(60), old.text)
                    .copy(transcript = old, audioPath = recording(root).absolutePath),
            ),
            SttProvider.LOCAL, WhisperModel.DEFAULT,
        )
        advanceUntilIdle()
        assertEquals("новая расшифровка", followed.saved.last().title)

        val kept = FakeRepo()
        viewModel(kept, root).retranscribe(
            kept.holding(
                NotesRepository.newNote("название, которое я выбрал", old.text)
                    .copy(transcript = old, audioPath = recording(root).absolutePath),
            ),
            SttProvider.LOCAL, WhisperModel.DEFAULT,
        )
        advanceUntilIdle()
        assertEquals(
            "a re-run renamed a note the person had titled",
            "название, которое я выбрал", kept.saved.last().title,
        )
    }

    @Test fun `the chosen language reaches the engine`() = runTest(main.dispatcher) {
        val root = tempRoot(); val seen = mutableListOf<String?>(); val repo = FakeRepo()
        val vm = viewModel(repo, root, language = "ru", seen = seen)
        val note = repo.holding(
            NotesRepository.newNote("з", "т")
                .copy(transcript = transcript("т"), audioPath = recording(root).absolutePath),
        )

        vm.retranscribe(note, SttProvider.LOCAL, WhisperModel.DEFAULT)
        advanceUntilIdle()

        assertEquals(listOf<String?>("ru"), seen)
    }
}

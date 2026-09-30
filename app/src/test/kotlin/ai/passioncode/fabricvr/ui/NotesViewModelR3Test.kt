package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.DictationOutbox
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * R3 — **a composable never calls a suspend function that writes** (`DEC-0031`) — and the three
 * places `docs/modules/app.md` said did not exist.
 *
 * The module document claimed the dictation commit was the last violation. The audit of
 * 2026-09-21 (`B-190`) found three more, all on `TodayScreen`'s `rememberCoroutineScope`:
 * `createNote` at two call sites and `retranscribe` at one. A scope a composition owns dies when
 * the composition leaves, so **opening any note cancelled a re-transcription that takes thirteen
 * to forty seconds** — silently, because the `finally` clears the busy flag and shows nothing —
 * and a double tap on *New note* wrote two empty notes and navigated twice.
 *
 * The suspend functions stay as the unit of work, because twelve tests in `NotesRetranscribeTest`
 * drive the destructive one directly and that is worth keeping. What is new is a launcher beside
 * each: on `appScope` for the decode, so it outlives the screen the way a dictation does
 * (`DEC-0068`), and on the view model's own scope for the note, where the result is a navigation
 * nobody can use once the screen is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelR3Test {

    @get:Rule val main = MainDispatcherRule()

    private class FakeRepo : NotesRepository {
        val saved = mutableListOf<Note>()
        var held: Note? = null
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = held
        override suspend fun upsert(note: Note): Result<Note> {
            saved.add(note); return Result.success(note)
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
        File(System.getProperty("java.io.tmpdir"), "r3-${System.nanoTime()}").apply { mkdirs() }

    private fun recording(root: File): File =
        File(root, "rec.wav").apply {
            parentFile?.mkdirs()
            writeBytes(ai.passioncode.fabricvr.stt.WavWriter.toWav(ShortArray(1_600) { 0 }, 16_000))
        }

    private fun viewModel(
        repo: FakeRepo,
        root: File,
        appScope: CoroutineScope,
        result: Result<Transcript> = Result.success(
            Transcript("после", "ru", SttSource.LOCAL, "whisper-small-q5_1", 900),
        ),
    ) = NotesViewModel(
        repository = repo,
        today = { LocalDate.of(2026, 9, 21) },
        vault = NoopVault(root),
        dayTicks = emptyFlow(),
        transcribeWith = { _, _, _, _ -> result },
        // Without it the real `Graph.sttLanguage` runs and touches a `lateinit` context no test has.
        sttLanguage = { "auto" },
        mirrorFailures = MutableStateFlow(emptyMap()),
        outbox = DictationOutbox(),
        appScope = appScope,
    )


    /**
     * **Real time, on purpose.** `retranscribe` reads the WAV through `withContext(Dispatchers.IO)`
     * and `MainDispatcherRule` replaces only `Main`, so the scheduler's `advanceUntilIdle` returns
     * while the IO hop is still in flight — the first version of these two tests asserted an empty
     * list for exactly that reason. `runTest` would otherwise let a fire-and-forget launcher look
     * like it did nothing. `SI-05`'s neighbour: assert what a coroutine did at the tier that can
     * wait for it, and where the wait has to be real, make it real and bounded.
     */
    private suspend fun awaitTrue(what: String, predicate: () -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!predicate()) delay(10) }
        }

    /**
     * A ray can press a 128 dp control twice inside the frame it takes to navigate. Each press ran
     * its own `upsert`, so the person arrived in an editor with a second empty note behind it.
     */
    @Test fun `a second press on New note while the first is in flight writes one note`() = runTest {
        val repo = FakeRepo()
        val vm = viewModel(repo, tempRoot(), this)
        val opened = mutableListOf<String>()

        vm.newNote { opened += it }
        vm.newNote { opened += it }
        advanceUntilIdle()

        assertEquals("two presses wrote ${repo.saved.size} notes", 1, repo.saved.size)
        assertEquals("two presses navigated ${opened.size} times", 1, opened.size)
    }

    /** The guard lifts, or a second note could never be written at all. */
    @Test fun `a press after the first has finished writes another`() = runTest {
        val repo = FakeRepo()
        val vm = viewModel(repo, tempRoot(), this)
        val opened = mutableListOf<String>()

        vm.newNote { opened += it }
        advanceUntilIdle()
        vm.newNote { opened += it }
        advanceUntilIdle()

        assertEquals(2, repo.saved.size)
        assertEquals(2, opened.size)
    }

    /**
     * The one that cost a person their wait. The decode is launched on `appScope`, so a screen
     * leaving cannot take it — and the note is written even if nobody is there to be told.
     */
    @Test fun `a re-transcription runs on the scope that outlives the screen`() = runTest {
        val root = tempRoot()
        val repo = FakeRepo()
        // **In a store, because clearing the store is what leaving the screen does.** Without
        // this the test proves nothing: `viewModelScope` and `appScope` behave identically in a
        // view model nobody ever clears, and the planted `viewModelScope` passed.
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    viewModel(repo, root, this@runTest) as T
            },
        )[NotesViewModel::class.java]
        val note = NotesRepository.newNote("заметка", "до")
            .copy(
                transcript = Transcript("до", "ru", SttSource.LOCAL, "whisper-small-q5_1", 900),
                audioPath = recording(root).absolutePath,
            )
        repo.held = note

        vm.startRetranscribe(note, SttProvider.LOCAL, WhisperModel.DEFAULT)
        store.clear()
        awaitTrue("nothing was ever written") { repo.saved.isNotEmpty() }

        assertEquals("the re-transcription was lost with the screen", "после", repo.saved.last().body)
    }

    /** And it is consumed once, so the confirmation cannot reappear on every recomposition. */
    @Test fun `the result is cleared when the screen has shown it`() = runTest {
        val repo = FakeRepo()
        val root = tempRoot()
        val vm = viewModel(repo, root, this)
        val note = NotesRepository.newNote("заметка", "до")
            .copy(audioPath = recording(root).absolutePath)
        repo.held = note

        vm.startRetranscribe(note, SttProvider.LOCAL, WhisperModel.DEFAULT)
        awaitTrue("the result never reached the state") { vm.state.value.retranscribed != null }
        assertNotNull(vm.state.value.retranscribed)

        vm.retranscribedShown()

        assertNull(vm.state.value.retranscribed)
    }
}

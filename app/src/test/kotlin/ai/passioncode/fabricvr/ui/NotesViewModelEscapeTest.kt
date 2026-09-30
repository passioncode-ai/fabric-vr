package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.Cue
import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.FeedbackCues
import ai.passioncode.fabricvr.common.AppError
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * **Nothing thrown inside a coroutine reaches the process** (`B-214`, `B-215`; `DEC-0084`).
 *
 * `DEC-0084` put every `viewModelScope` launch behind `launchGuarded`, and wrote down that
 * `launchIn(viewModelScope)` is deliberately outside the rule because a flow's failures belong to
 * its own `.catch`. Two holes were left, and the 2026-09-22 audit found both:
 *
 *  - **`B-214`** — `observe()` had that `.catch` **upstream** of the `onEach` that calls
 *    `repository.count()`, a suspend query moved into the flow after the guard was written. An
 *    operator only sees failures from above it, so a SQLite error in `count()` walked straight out
 *    of `launchIn` with nothing between it and the uncaught-exception route.
 *  - **`B-215`** — three `appScope.launch`es around throwing calls (`sttLanguage()` is a Keystore
 *    decrypt, `Vault.adoptOrKeep` is file IO). `DEC-0084`'s letter does not reach them, because
 *    they are not `viewModelScope`; its whole argument does.
 *
 * `Graph.scope` is a `SupervisorJob`, so a throw does not take the other work with it — it goes to
 * the process's uncaught handler, which on a headset is a crash with no sentence attached. The
 * handler installed below is how that route is measured rather than argued about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelEscapeTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    /** Every exception that left a coroutine on the application scope, which is the crash route. */
    private val escaped = mutableListOf<Throwable>()

    /**
     * `Graph.scope`'s shape — `SupervisorJob`, so one failing child does not cancel the rest — plus
     * a handler, because that is the only way to *see* an escape rather than reason about it.
     * Production has no handler; the process's default one is what a crash is.
     */
    private val appScope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure -> escaped += failure },
    )

    private val root: File =
        File(System.getProperty("java.io.tmpdir"), "escape-${System.nanoTime()}").apply { mkdirs() }

    @After fun tearDown() {
        appScope.cancel()
        root.deleteRecursively()
    }

    private class Silent : FeedbackCues {
        override fun play(cue: Cue) = Unit
        override fun attachHaptics(channel: ai.passioncode.fabricvr.CueChannel?) = Unit
        override fun detachHaptics(channel: ai.passioncode.fabricvr.CueChannel) = Unit
    }

    private class FakeRepo : NotesRepository {
        val saved = mutableListOf<Note>()
        var note: Note? = Note(id = "n1", title = "", body = "", createdAt = 1, updatedAt = 1)
        var countThrows = false
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = note
        override suspend fun upsert(note: Note): Result<Note> {
            saved.add(note); return Result.success(note)
        }
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> =
            Result.success(NotesRepository.newNote())
        override suspend fun count(): Int {
            // What Room does when the database file is gone, in one line: a plain throw out of a
            // suspend query, from inside the flow rather than from above it.
            if (countThrows) throw IllegalStateException("no such table: notes")
            return 0
        }
        override suspend fun recent(limit: Int): List<Note> = emptyList()
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    /** Throws rather than answering with a `Result` — which is what `adoptOrKeep`'s IO can do. */
    private class ThrowingVault(override val root: File) : Vault {
        override suspend fun audioUsage(): Result<AudioUsage> = Result.success(AudioUsage(0, 0L))
        override suspend fun removeAudio(note: Note): Result<Unit> = Result.success(Unit)
        override suspend fun sweepAudio(cutoff: Long): Result<AudioUsage> = Result.success(AudioUsage(0, 0L))
        override fun pathFor(note: Note): File = File(root, "notes/${note.id}.md")
        override fun audioPathFor(note: Note): File = throw IllegalStateException("storage is gone")
        override suspend fun write(note: Note): Result<File> = Result.success(pathFor(note))
        override suspend fun remove(id: String, createdAt: Long): Result<Unit> = Result.success(Unit)
        override suspend fun restore(id: String, createdAt: Long): Result<Unit> = Result.success(Unit)
        override fun trashedFiles(id: String): List<File> = emptyList()
        override suspend fun purgeTrash(olderThanMillis: Long): Result<Int> = Result.success(0)
        override suspend fun adoptAudio(note: Note, source: File): Result<File> =
            throw IllegalStateException("storage is gone")
    }

    private fun notes(
        repo: NotesRepository,
        vault: Vault = ThrowingVault(root),
        sttLanguage: suspend () -> String = { "auto" },
    ) = NotesViewModel(
        repository = repo,
        today = { LocalDate.of(2026, 9, 22) },
        vault = vault,
        dayTicks = emptyFlow(),
        sttLanguage = sttLanguage,
        appScope = appScope,
        mirrorFailures = MutableStateFlow(emptyMap<String, AppError>()),
        retryMirror = {},
        outbox = DictationOutbox(),
        awaitReconcile = {},
        cues = Silent(),
        copyTranscriptSetting = { false },
        copyToClipboard = {},
        io = dispatcher,
    )

    private fun transcript(text: String) =
        Transcript(text, "ru", SttSource.LOCAL, "whisper-base", 900)

    // ---- B-214 -----------------------------------------------------------------------------

    /**
     * **The guard has to sit below the thing it guards.**
     *
     * `.catch` at `:314` with `.onEach` at `:315` reads like a guard on the pipeline and is a guard
     * on the two lines above it. The observable half is here: a failing `count()` must become a
     * sentence on the screen rather than nothing at all, and "nothing at all" is what a person got
     * — a list frozen mid-load behind a crash.
     */
    @Test fun `a query that throws inside the observation becomes a message`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { countThrows = true }

        val vm = notes(repo)
        advanceUntilIdle()

        assertNotNull(
            "a SQLite failure inside `onEach` left the screen with nothing to say: " +
                "the `.catch` is upstream of the operator that threw",
            vm.state.value.message,
        )
        assertTrue("the screen was left claiming to be loading", !vm.state.value.loading)
    }

    // ---- B-215 -----------------------------------------------------------------------------

    /**
     * *Transcribe again* reads the language from the Keystore, and a Keystore decrypt throws.
     *
     * `startRetranscribe` was a bare `appScope.launch`, so the throw left the coroutine and the
     * person got a crash instead of a sentence — from a control whose whole purpose is to recover
     * from a bad transcription.
     */
    @Test fun `a keystore decrypt that throws does not leave the retranscribe coroutine`() =
        runTest(dispatcher) {
            val repo = FakeRepo()
            val note = Note(
                id = "n1", title = "", body = "", createdAt = 1, updatedAt = 1,
                audioPath = File(root, "rec.wav").apply { writeBytes(ByteArray(64)) }.absolutePath,
            )
            val vm = notes(repo, sttLanguage = { throw IllegalStateException("keystore locked") })

            vm.startRetranscribe(note, SttProvider.LOCAL, WhisperModel.DEFAULT)
            advanceUntilIdle()

            assertEquals("the throw reached the process: $escaped", emptyList<Throwable>(), escaped)
            assertNotNull("the person was told nothing", vm.state.value.message)
            assertNull("the spinner was left on a note nothing is transcribing", vm.state.value.retranscribing)
        }

    /** The same shape in the other bare launch: keeping a recording whose vault refuses to answer. */
    @Test fun `a vault that throws does not leave the keep-recording coroutine`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = notes(repo)

        vm.keepRecordingOnly(File(root, "only.wav").apply { writeBytes(ByteArray(8)) }.absolutePath)
        advanceUntilIdle()

        assertEquals("the throw reached the process: $escaped", emptyList<Throwable>(), escaped)
        assertNotNull("the person was told nothing", vm.state.value.message)
    }

    /**
     * **And `committing` is reset in a `finally`.**
     *
     * It was set before the launch and cleared in both arms of the fold — so a throw between them
     * left it holding that transcript for the life of the process, and the guard at the top of
     * `commitDictation` (`if (committing == transcript) return`) then refused every retry of the
     * one dictation that needed one. The recording is in the vault, the row is not, and the control
     * that would finish the job silently does nothing.
     */
    @Test fun `a throw during a commit does not bar that dictation for ever`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = notes(repo)
        val words = transcript("не потеряй меня")

        vm.commitDictation(words, File(root, "a.wav").apply { writeBytes(ByteArray(8)) }.absolutePath)
        advanceUntilIdle()
        assertEquals("the fixture's vault did not throw", 0, repo.saved.size)
        assertEquals("the throw reached the process: $escaped", emptyList<Throwable>(), escaped)

        // The same words again, the way `retryDictation` sends them. With `committing` latched this
        // writes nothing, for ever.
        vm.commitDictation(words, null)
        advanceUntilIdle()

        assertEquals(
            "the dictation was barred from ever being committed again by a latched guard",
            1,
            repo.saved.size,
        )
    }
}

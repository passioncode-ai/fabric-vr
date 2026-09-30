package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.vault.FileVault
import ai.passioncode.fabricvr.vault.AudioUsage
import ai.passioncode.fabricvr.vault.Vault
import java.io.File
import java.time.LocalDate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Today screen's own dictation makes a note of its own. What is checked here is the seam the
 * device cannot show cheaply: that the note is stored **once**, already pointing at the vault, and
 * that a vault refusal leaves the recording where it is instead of losing it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    /**
     * Stands in for `Graph.scope`, where a dictation's commit runs since `T-047`. Not `runTest`'s
     * `backgroundScope`: that registers its coroutines as background work and `advanceUntilIdle`
     * deliberately does not wait for them, so every commit would silently never run.
     */
    private val appScope = CoroutineScope(dispatcher)

    private class FakeRepo : NotesRepository {
        val saved = mutableListOf<Note>()
        /** By id, so a test can ask what survived a `REPLACE` rather than only what was written. */
        val stored = linkedMapOf<String, Note>()
        var failUpsert = false
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = saved.lastOrNull()
        override suspend fun upsert(note: Note): Result<Note> {
            if (failUpsert) return Result.failure(IllegalStateException("disk full"))
            // **The fake models the CONTRACT, not the table.** It used to model the raw
            // `REPLACE` — a write claiming a taken day evicted the holder — because that is what
            // the database did and the demotion lived one layer up in the view model. Since
            // `DEC-0058` the demotion is `NotesRepository.upsert`'s own invariant, so a fake that
            // still evicts is a fake that lets a caller's test pass over a defect the real
            // repository would refuse. A stand-in carries the promise, never the mechanism.
            val demoted = note.dayKey
                ?.takeIf { key -> stored.values.any { it.dayKey == key && it.id != note.id } }
                ?.let { note.copy(dayKey = null) }
                ?: note
            saved.add(demoted)
            stored[demoted.id] = demoted
            return Result.success(demoted)
        }
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? =
            stored.values.firstOrNull { it.dayKey == dayKey }
        override suspend fun dailyNote(day: LocalDate): Result<Note> =
            Result.success(NotesRepository.newNote())
        override suspend fun count(): Int = 0
        override suspend fun recent(limit: Int): List<Note> = emptyList()
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    private class FakeVault(override val root: File) : Vault {
        /** Which notes had their recording removed, so the test can say it happened. */
        val audioRemoved = mutableListOf<String>()
        var sweepResult = AudioUsage(0, 0L)
        override suspend fun audioUsage(): Result<AudioUsage> = Result.success(sweepResult)
        override suspend fun removeAudio(note: Note): Result<Unit> {
            audioRemoved += note.id; return Result.success(Unit)
        }
        override suspend fun sweepAudio(cutoff: Long): Result<AudioUsage> = Result.success(sweepResult)
        var failNext = false
        override fun pathFor(note: Note): File = File(root, "notes/${note.id}.md")
        override fun audioPathFor(note: Note): File = File(root, "notes/${note.id}.wav")
        override suspend fun write(note: Note): Result<File> = Result.success(pathFor(note))
        override suspend fun remove(id: String, createdAt: Long): Result<Unit> = Result.success(Unit)
        override suspend fun restore(id: String, createdAt: Long): Result<Unit> = Result.success(Unit)
        override fun trashedFiles(id: String): List<File> = emptyList()
        override suspend fun purgeTrash(olderThanMillis: Long): Result<Int> = Result.success(0)
        override suspend fun adoptAudio(note: Note, source: File): Result<File> {
            if (failNext) return Result.failure(IllegalStateException("read-only"))
            val target = audioPathFor(note)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            source.delete()
            return Result.success(target)
        }
    }

    @After fun tearDown() = appScope.cancel()

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "fabricvr-notes-${System.nanoTime()}").apply { mkdirs() }

    /**
     * A vault mirror with nothing wrong. `D-24` put a collector in the view model's `init`, and
     * its production default reaches `Graph`, which is an `object` with a `lateinit` context —
     * so every construction in this file needs one, which is `check-seams.sh`'s rule working.
     */
    private val mirror = MutableStateFlow<Map<String, AppError>>(emptyMap())

    /** No day boundary during a test: the production ticker never ends, and a test must. */
    private val ticks = emptyFlow<LocalDate>()

    /**
     * The dictation outbox, one per test method.
     *
     * In memory — no file — because what these cases are about is the hand-off, not the disk;
     * `DictationOutboxTest` owns the persistence. It is a field rather than the production
     * default for `check-seams.sh`'s reason: `Graph.dictationOutbox` resolves `appContext`,
     * which is `lateinit` in an `object`, and a shared process-wide outbox would also let one
     * test's undrained entry become the next one's.
     */
    private val outbox = DictationOutbox()

    private fun transcript(text: String) = Transcript(text, "ru", SttSource.LOCAL, "whisper-base", 900)

    /**
     * Commits a dictation and waits for it, the way the outbox drain does, and answers with the
     * note the repository actually stored — or null when the commit failed. `commitDictation` is
     * not `suspend` precisely because its caller must not be able to cancel it.
     *
     * **The `onCommitted` callback is gone** (`REQ-046`): it existed so a composition could tell
     * `VoiceViewModel` the words had landed, and a callback from a composition is what made a
     * dictation die with its host. The observable outcome is the row, so that is what this reads.
     */
    private fun TestScope.commit(
        vm: NotesViewModel,
        repo: FakeRepo,
        t: Transcript,
        path: String?,
    ): Note? {
        val before = repo.saved.size
        vm.commitDictation(t, path)
        advanceUntilIdle()
        return if (repo.saved.size > before) repo.saved.lastOrNull() else null
    }

    @Test fun `a dictation from Today becomes a note whose audio already lives in the vault`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val root = tempRoot()
        val vault = FakeVault(root)
        val scratch = File(root, "rec.wav").apply { writeBytes(byteArrayOf(1, 2)) }
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 19) }, vault, ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)

        val note = commit(vm, repo, transcript("позвонить в понедельник"), scratch.absolutePath)

        assertNotNull("no note was created", note)
        assertEquals("позвонить в понедельник", note!!.body)
        assertEquals(1, repo.saved.size)
        assertEquals(
            "the note was stored pointing at the scratch file",
            vault.audioPathFor(note).absolutePath,
            repo.saved.single().audioPath,
        )
        assertFalse("the scratch copy survived", scratch.exists())
    }

    @Test fun `a vault that refuses the recording still leaves the note and the file`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val root = tempRoot()
        val vault = FakeVault(root).apply { failNext = true }
        val scratch = File(root, "rec.wav").apply { writeBytes(byteArrayOf(1, 2)) }
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 19) }, vault, ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)

        val note = commit(vm, repo, transcript("черновик"), scratch.absolutePath)

        assertNotNull(note)
        assertEquals(scratch.absolutePath, repo.saved.single().audioPath)
        assertTrue("the recording was lost when the vault refused it", scratch.exists())
    }

    @Test fun `crossing midnight relabels the day and reloads the daily note`() = runTest(dispatcher) {
        val repo = FakeRepo()
        var day = LocalDate.of(2026, 9, 19)
        val tick = MutableSharedFlow<LocalDate>()
        val vm = NotesViewModel(repo, { day }, FakeVault(tempRoot()), tick, appScope = appScope, mirrorFailures = mirror, outbox = outbox)
        advanceUntilIdle()
        assertEquals("2026-09-19", vm.state.value.dayLabel)

        day = LocalDate.of(2026, 9, 20)
        tick.emit(day)
        advanceUntilIdle()

        assertEquals("a panel left open overnight kept yesterday's heading", "2026-09-20", vm.state.value.dayLabel)
    }

    @Test fun `a failed save reports it instead of returning a note nobody stored`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { failUpsert = true }
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 19) }, FakeVault(tempRoot()), ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)

        val note = commit(vm, repo, transcript("потерянное"), null)

        assertEquals(null, note)
        assertNotNull("the failure was swallowed", vm.state.value.message)
    }

    // ---- T-006: undo must restore the recording, not only the row ----

    @Test fun `undo after delete restores the recording, not only the row`() = runTest(dispatcher) {
        val root = tempRoot()
        val vault = FileVault(root, io = Dispatchers.Unconfined)
        val repo = FakeRepo()
        val scratch = File(root, "rec.wav").apply { writeBytes(byteArrayOf(9, 9)) }
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 20) }, vault, ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)

        val note = commit(vm, repo, transcript("запись"), scratch.absolutePath)!!
        vault.write(note).getOrThrow()
        assertTrue("the fixture did not put a recording in the vault", vault.audioPathFor(note).exists())

        // The mirror is what deletes vault files when a note goes; do that part directly.
        vm.delete(note)
        advanceUntilIdle()
        vault.remove(note.id, note.createdAt).getOrThrow()
        assertFalse(vault.audioPathFor(note).exists())

        vm.undoDelete()
        advanceUntilIdle()

        assertTrue("undo restored the row and left the recording deleted", vault.audioPathFor(note).exists())
        assertEquals(
            "the restored note lost its pointer to the recording",
            vault.audioPathFor(note).absolutePath,
            repo.saved.last().audioPath,
        )
    }

    @Test fun `undo tells the person when the recording could not be restored`() = runTest(dispatcher) {
        val root = tempRoot()
        val vault = FileVault(root, io = Dispatchers.Unconfined)
        val repo = FakeRepo()
        val scratch = File(root, "rec.wav").apply { writeBytes(byteArrayOf(9, 9)) }
        // `io = dispatcher` since `B-241`: the undo now asks the disk whether the recording is still
        // in place, and on the real IO pool `advanceUntilIdle()` would return before the answer.
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 20) }, vault, ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox, io = dispatcher)

        val note = commit(vm, repo, transcript("запись"), scratch.absolutePath)!!
        vault.write(note).getOrThrow()
        vm.delete(note)
        advanceUntilIdle()
        vault.remove(note.id, note.createdAt).getOrThrow()
        vault.purgeTrash(olderThanMillis = 0).getOrThrow()

        vm.undoDelete()
        advanceUntilIdle()

        assertNotNull("the person was not told the recording is gone", vm.state.value.message)
        assertEquals(
            "the note came back claiming a recording it does not have",
            null,
            repo.saved.last().audioPath,
        )
    }
    /**
     * D-11. The Undo line was cleared the instant it was pressed, before the write it starts has
     * any result. When that write fails the note is gone from the database, the recording is in the
     * trash where nothing in the interface reaches it, and the one control that could have tried
     * again has just been taken off the screen. Losing a note because a retry was withdrawn is
     * worse than the delete the person did on purpose.
     */
    @Test fun `an undo that fails keeps the undo on screen`() = runTest(dispatcher) {
        val root = tempRoot()
        val vault = FileVault(root, io = Dispatchers.Unconfined)
        val repo = FakeRepo()
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 20) }, vault, ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)

        val note = commit(vm, repo, transcript("не потеряй"), null)!!
        vm.delete(note)
        advanceUntilIdle()
        assertNotNull("the fixture never offered an undo", vm.state.value.justDeleted)

        repo.failUpsert = true
        vm.undoDelete()
        advanceUntilIdle()

        assertNotNull("the failure was swallowed", vm.state.value.message)
        assertEquals(
            "the note was lost and the one control that could try again went with it",
            note.id,
            vm.state.value.justDeleted?.id,
        )
    }

    @Test fun `an undo that succeeds takes the undo off the screen`() = runTest(dispatcher) {
        val root = tempRoot()
        val vault = FileVault(root, io = Dispatchers.Unconfined)
        val repo = FakeRepo()
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 20) }, vault, ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)

        val note = commit(vm, repo, transcript("вернулась"), null)!!
        vm.delete(note)
        advanceUntilIdle()

        vm.undoDelete()
        advanceUntilIdle()

        assertEquals("the line stayed after the note was back", null, vm.state.value.justDeleted)
    }

    // --- T-047: a commit must outlive the screen that started it (I-02, A-04) ----------------

    /**
     * I-02. The commit ran on the composition's scope: the audio moved into the vault, the person
     * tapped Settings, the effect was cancelled before the row was written, and the recording was
     * left orphaned under an id nothing carried. Returning to the screen re-fired the effect
     * against a scratch file that had already gone, and the note recorded a path to nothing.
     */
    @Test fun `a dictation survives the screen it was started from`() = runTest(dispatcher) {
        val root = tempRoot()
        val vault = FakeVault(root)
        val repo = FakeRepo()
        val scratch = File(root, "rec.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4, 2)) }
        // Through a store, because clearing it is what navigation does and what cancels
        // `viewModelScope`. Calling `onCleared()` directly proves nothing: it is the callback, and
        // `ViewModel.clear()` is what cancels — a first version of this test did that and stayed
        // green with the defect planted back.
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NotesViewModel(repo, { LocalDate.of(2026, 9, 20) }, vault, ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox) as T
            },
        )[NotesViewModel::class.java]

        vm.commitDictation(transcript("не потеряй меня"), scratch.absolutePath)
        // The screen goes away the instant the commit starts — this is the whole defect.
        store.clear()
        advanceUntilIdle()

        assertEquals("the commit died with the screen", 1, repo.saved.size)
        assertEquals("не потеряй меня", repo.saved.last().body)
        assertTrue("the recording was orphaned", File(repo.saved.last().audioPath!!).exists())
    }

    /**
     * A-04. `consumed()` ran unconditionally, so a failed write released the recording anyway
     * while the banner read "your text is still here". It was not.
     */
    @Test fun `a dictation whose row will not write is not released`() = runTest(dispatcher) {
        val root = tempRoot()
        val repo = FakeRepo().apply { failUpsert = true }
        val scratch = File(root, "rec.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4, 2)) }
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 20) }, FakeVault(root), ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)

        vm.commitDictation(transcript("не сохранилось"), scratch.absolutePath)
        advanceUntilIdle()

        assertTrue("a failed write produced a row anyway", repo.saved.isEmpty())
        assertNotNull("nothing was said about it", vm.state.value.message)
        assertNotNull("the dictation was dropped instead of held for a retry", vm.state.value.pendingDictation)
        assertEquals(
            "Retry under an unsaved dictation would have reloaded the list (REQ-047, H2)",
            UiAction.RETRY_DICTATION,
            vm.state.value.message?.action,
        )
    }

    /** The retry finishes the job with one write and no second move: the audio is already in place. */
    @Test fun `retrying a failed dictation writes the same note without moving the audio again`() = runTest(dispatcher) {
        val root = tempRoot()
        val vault = FakeVault(root)
        val repo = FakeRepo().apply { failUpsert = true }
        val scratch = File(root, "rec.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4, 2)) }
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 20) }, vault, ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)

        vm.commitDictation(transcript("со второй попытки"), scratch.absolutePath)
        advanceUntilIdle()
        val held = vm.state.value.pendingDictation!!

        repo.failUpsert = false
        vm.retryDictation()
        advanceUntilIdle()

        assertTrue("the retry never wrote the note", repo.saved.isNotEmpty())
        assertEquals("the retry wrote a different note", held.id, repo.saved.last().id)
        assertEquals("the retry moved the audio a second time", held.audioPath, repo.saved.last().audioPath)
        assertEquals("the pending dictation was not cleared", null, vm.state.value.pendingDictation)
    }

    /**
     * The screen re-enters `LaunchedEffect(voice)` every time it comes back, and the state stays
     * `Ready` until the commit succeeds — so without a guard, leaving and returning mid-commit
     * writes the note twice and moves an already-moved file.
     */
    @Test fun `the same dictation is not committed twice`() = runTest(dispatcher) {
        val root = tempRoot()
        val repo = FakeRepo()
        val scratch = File(root, "rec.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4, 2)) }
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 20) }, FakeVault(root), ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)
        val one = transcript("один раз")

        vm.commitDictation(one, scratch.absolutePath)
        vm.commitDictation(one, scratch.absolutePath)
        advanceUntilIdle()

        assertEquals("the dictation was written twice", 1, repo.saved.size)
    }

    /**
     * `A-24`'s product half, decided as `DEC-0035` option A.
     *
     * `notes` has a UNIQUE index on `dayKey` and every write is a `REPLACE`, so restoring
     * yesterday's deleted daily note while today's exists would **silently destroy the note the
     * person has been typing into all day**. The restored note keeps every word and gives up the
     * day: a heading changes and nothing is lost.
     */
    @Test fun `restoring a deleted daily note does not silently replace todays note`() =
        runTest(dispatcher) {
            val repo = FakeRepo()
            val today = NotesRepository.newNote("сегодня", "то, что я печатал весь день")
                .copy(dayKey = "2026-09-21")
            repo.upsert(today)
            repo.saved.clear()
            val yesterdaysDaily = NotesRepository.newNote("вчера", "вчерашняя запись")
                .copy(dayKey = "2026-09-21")
            val vm = NotesViewModel(
                repo, { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox,
            )
            advanceUntilIdle()

            vm.delete(yesterdaysDaily)
            advanceUntilIdle()
            vm.undoDelete()
            advanceUntilIdle()

            val restored = repo.saved.last()
            assertNull("the restored note took a day another note was holding", restored.dayKey)
            assertEquals("the restored text was not kept", "вчерашняя запись", restored.body)
            assertNotNull(
                "today's note was destroyed by the undo",
                repo.stored.values.firstOrNull { it.dayKey == "2026-09-21" },
            )
        }

    /**
     * `T-024`. Until this existed, the only way to delete a recording was to delete the note it
     * belonged to — `Vault.remove` takes both in one call — so somebody wanting the audio gone
     * had to give up the text with it.
     */
    @Test fun `deleting a recording clears the audioPath and keeps the note`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vault = FakeVault(tempRoot())
        val note = NotesRepository.newNote("со звуком", "тело").copy(audioPath = "/vault/a.wav")
        repo.upsert(note)
        repo.saved.clear()
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 21) }, vault, ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)

        vm.deleteRecording(note)
        advanceUntilIdle()

        assertEquals("the recording was not removed from the vault", listOf(note.id), vault.audioRemoved)
        val stored = repo.stored.getValue(note.id)
        assertNull("the note still points at a file that is gone", stored.audioPath)
        assertEquals("the note went with its recording", "тело", stored.body)
    }

    /**
     * `T-026`. The list is bounded, so the state has to carry how many notes exist and a way to
     * ask for the rest — `T-030` renders the row; this is the state it renders from.
     */
    @Test fun `raising the window shows the rest`() = runTest(dispatcher) {
        val repo = WindowedRepo(total = 250)
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)
        advanceUntilIdle()
        // **One fewer than the window, and one fewer than the count**, because this repository's
        // daily note is the first of the 250 and today's note is drawn as the day card rather
        // than as a row. It used to be drawn as BOTH, and because the list was therefore never
        // empty, `T-031`'s two empty states could not be reached at all.
        assertEquals(
            "the list was not clipped to the window",
            NotesRepository.DEFAULT_WINDOW - 1,
            vm.state.value.notes.size,
        )
        assertEquals("the screen cannot say how many it is not showing", 249, vm.state.value.total)

        vm.showAll()
        advanceUntilIdle()

        assertEquals(249, vm.state.value.notes.size)
        assertEquals(249, vm.state.value.total)
    }

    /** A repository that honours the window, so the view model's half can be asserted. */
    private class WindowedRepo(private val total: Int) : NotesRepository {
        private val all = (1..total).map { NotesRepository.newNote("note $it", "body $it") }
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(all.take(limit))
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = all.firstOrNull { it.id == id }
        override suspend fun upsert(note: Note): Result<Note> = Result.success(note)
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun count(): Int = total
        override suspend fun dailyNote(day: LocalDate): Result<Note> = Result.success(all.first())
        override suspend fun recent(limit: Int): List<Note> = all.take(limit)
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    /**
     * **Passes today, and that is the point.** `createNote()` works; no screen called it, so on a
     * fresh install the only thing a person could do was dictate. The button was collateral of
     * `DEC-0010`'s simplification — which was right — and nothing recorded removing it (`A-01`).
     *
     * This test is what stops the next refactor taking the method away with the button, which is
     * exactly how the defect happened the first time.
     */
    @Test fun `createNote returns a persisted note`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)
        advanceUntilIdle()

        val note = vm.createNote()

        assertNotNull("createNote answered null on a repository that accepts writes", note)
        assertNotNull("the note was never written", repo.stored[note!!.id])
    }

    /**
     * **Passes today, and it documents the state the composable has to survive.**
     *
     * Editing the last `#idea` out of the last note re-emits `tags` without it while
     * `selectedTag` still holds `"idea"` — so a chip row rendered from `state.tags` alone would
     * show a filter with **no chip to release**, an empty list, and no way out. The view model
     * is right to keep the selection; the screen renders the selected tag whether or not the
     * list still contains it, and this is the test that says why that line exists.
     */
    /**
     * `D-24`, and `SCN-011`'s own words: the failure is shown **where it happened**. The
     * out-of-sync count lived in Settings alone, so a person whose notes had stopped reaching
     * their files — which, after `T-023`, is also when their export silently stops being
     * complete — had no reason to ever find out.
     *
     * **Once per episode.** A banner that returned on every emission is a screen people stop
     * reading, which is the same defect from the other side.
     */
    /**
     * **Today's note is drawn once.** `loadDailyNote()` CREATES the row and `observeAll` is an
     * unfiltered `SELECT *`, so after `T-028` added the day card the same note appeared twice —
     * and, worse, the list was never empty from the first launch onward, which made `T-031`'s
     * two empty states unreachable. Both tiers of step 8's verification found it independently.
     */
    @Test fun `todays note is the card, not a row`() = runTest(dispatcher) {
        val repo = WindowedRepo(total = 3)
        val vm = NotesViewModel(
            repo, { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks,
            appScope = appScope, mirrorFailures = mirror, outbox = outbox,
        )
        advanceUntilIdle()

        assertNotNull("the day card has no note", vm.state.value.today)
        assertEquals("today's note is in the list as well as on the card", 2, vm.state.value.notes.size)
        assertFalse(
            "today's note is drawn twice",
            vm.state.value.notes.any { it.id == vm.state.value.today?.id },
        )
        assertEquals("the count still includes the note the card is showing", 2, vm.state.value.total)
    }

    /**
     * **A tag filter suspends the card rather than hiding a match.** The card shows today
     * unfiltered, which is not what the person asked for, so under a filter it is not drawn —
     * and the list stops excluding today's note at the same moment, or a note that matches would
     * simply be missing.
     */
    @Test fun `a filtered list carries todays note itself`() = runTest(dispatcher) {
        val repo = TaggedRepo()
        val vm = NotesViewModel(
            repo, { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks,
            appScope = appScope, mirrorFailures = mirror, outbox = outbox,
        )
        advanceUntilIdle()
        assertTrue("the unfiltered list kept today's note", vm.state.value.notes.isEmpty())

        vm.selectTag("идея")
        advanceUntilIdle()

        assertEquals("the matching note disappeared under the filter", 1, vm.state.value.notes.size)
    }

    /**
     * The nothing-yet state a person actually meets on a fresh install: the daily note exists,
     * and nothing else does.
     */
    @Test fun `a fresh install lists nothing`() = runTest(dispatcher) {
        val repo = WindowedRepo(total = 1)
        val vm = NotesViewModel(
            repo, { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks,
            appScope = appScope, mirrorFailures = mirror, outbox = outbox,
        )
        advanceUntilIdle()

        assertTrue("the empty state cannot be reached", vm.state.value.notes.isEmpty())
        assertNotNull(vm.state.value.today)
    }

    /**
     * `SCN-011` says the mirror failure is shown *with Retry*, and the first version of this
     * banner offered *Settings* — a different control from the one the scenario names, in a
     * change whose own decision record says correcting the scenario to match the build is the
     * thing it refuses. `VaultMirror.retryFailed()` had existed the whole time.
     */
    @Test fun `the mirror banner retries the mirror`() = runTest(dispatcher) {
        var retried = 0
        val vm = NotesViewModel(
            FakeRepo(), { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks,
            appScope = appScope, mirrorFailures = mirror, outbox = outbox, retryMirror = { retried++ },
        )
        advanceUntilIdle()
        mirror.value = mapOf("n1" to AppError.Storage("write", null))
        advanceUntilIdle()
        assertEquals(UiAction.RETRY_LOAD, vm.state.value.message?.action)

        vm.retry()
        advanceUntilIdle()

        assertEquals("Retry did not reach the mirror", 1, retried)
        assertNull("the message survived its own retry", vm.state.value.message)
    }

    /**
     * **The episode is held, not dropped.** The first version returned without announcing when
     * any other message was up and argued that "the next emission tries again" — there is no
     * next emission: `VaultMirror.failures` is a `StateFlow`, so one note failing repeatedly with
     * an equal error emits once. An episode arriving while another message was on screen was
     * lost for the life of the process, which is `D-24` reinstated.
     */
    @Test fun `a mirror failure that arrives behind another message is not lost`() = runTest(dispatcher) {
        val vm = NotesViewModel(
            FakeRepo(), { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks,
            appScope = appScope, mirrorFailures = mirror, outbox = outbox,
        )
        advanceUntilIdle()
        vm.spaceFailed(IllegalStateException("the space would not start"))
        advanceUntilIdle()
        val other = vm.state.value.message
        assertNotNull("the fixture did not put a message on screen", other)

        mirror.value = mapOf("n1" to AppError.Storage("write", null))
        advanceUntilIdle()
        assertEquals("the mirror overwrote a message the person was reading", other, vm.state.value.message)

        vm.dismissMessage()
        advanceUntilIdle()

        assertNotNull("the held mirror episode was never told", vm.state.value.message)
        assertEquals(UiAction.RETRY_LOAD, vm.state.value.message?.action)
    }

    @Test fun `a mirror failure reaches the Today banner once`() = runTest(dispatcher) {
        val vm = NotesViewModel(
            FakeRepo(), { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks,
            appScope = appScope, mirrorFailures = mirror, outbox = outbox,
        )
        advanceUntilIdle()
        assertNull("a clean mirror said something", vm.state.value.message)

        mirror.value = mapOf("n1" to AppError.Storage("write", null))
        advanceUntilIdle()
        val first = vm.state.value.message
        assertNotNull("a mirror failure never reached Today", first)

        // Dismissed, then a second note fails in the same episode: the banner does not come back.
        vm.dismissMessage()
        mirror.value = mapOf("n1" to AppError.Storage("write", null), "n2" to AppError.Storage("write", null))
        advanceUntilIdle()
        assertNull("the banner returned on the next failure of the same episode", vm.state.value.message)
    }

    /** A **later** outage is news again, which is why the flag resets on recovery. */
    @Test fun `a mirror that recovers and fails again says so again`() = runTest(dispatcher) {
        val vm = NotesViewModel(
            FakeRepo(), { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks,
            appScope = appScope, mirrorFailures = mirror, outbox = outbox,
        )
        advanceUntilIdle()

        mirror.value = mapOf("n1" to AppError.Storage("write", null))
        advanceUntilIdle()
        vm.dismissMessage()

        mirror.value = emptyMap()
        advanceUntilIdle()
        mirror.value = mapOf("n2" to AppError.Storage("write", null))
        advanceUntilIdle()

        assertNotNull("a second outage was swallowed", vm.state.value.message)
    }

    // ---- REQ-047 / H3, M21: the Space's own failure, and its own progress line --------------

    /**
     * **`Retry` under "The space could not start" has to try the Space.**
     *
     * Every failure this screen raised carried the one `RETRY`, and Today answered it with
     * `retry()` — a list reload. So the person pressed the only control the message offered, the
     * notes were re-read, and the Space was never attempted again. The member is what lets
     * `TodayScreen` route it to `onEnterSpace`; asserting it here is asserting the thing the
     * compiler then enforces at the call site.
     */
    @Test fun `a failed space start asks to retry the space, not the list`() = runTest(dispatcher) {
        val vm = NotesViewModel(
            FakeRepo(), { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks,
            appScope = appScope, mirrorFailures = mirror, outbox = outbox,
        )
        advanceUntilIdle()

        vm.spaceFailed(IllegalStateException("no VR category on this headset"))
        advanceUntilIdle()

        assertEquals(UiAction.RETRY_SPACE, vm.state.value.message?.action)
        assertFalse("a failed start left the progress line up", vm.state.value.spaceStarting)
    }

    /**
     * `M21`. *"Starting the space…"* was set on the tap and cleared **only** by `spaceFailed`,
     * so every successful trip into the Space and back left it on Today for the life of the
     * process — a progress line for a journey that had finished. The panel being resumed is the
     * return.
     */
    @Test fun `the starting line does not outlive the trip`() = runTest(dispatcher) {
        val vm = NotesViewModel(
            FakeRepo(), { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks,
            appScope = appScope, mirrorFailures = mirror, outbox = outbox,
        )
        advanceUntilIdle()

        vm.spaceStarting()
        assertTrue("the fixture never raised the line", vm.state.value.spaceStarting)

        // The person is back on the panel: `ON_RESUME`.
        vm.panelShown()
        advanceUntilIdle()

        assertFalse("\"Starting the space…\" outlived the trip", vm.state.value.spaceStarting)
    }

    /**
     * Wiring `AppError.Network` through `:app`'s own mapper (`REQ-058`'s app half, `M12`).
     *
     * `Throwable.toMessage()` sent everything it did not recognise to `AppError.Unknown`, so a
     * `ConnectException` that reached a screen unwrapped read *"Something failed:
     * ConnectException"* — and any `IOException` beneath it was liable to be presented as
     * storage. Nothing in `:app` may show a network failure as *"Couldn't save. Your text is
     * still here."*, which is a sentence about a note the failure was never in.
     */
    @Test fun `a refused connection is a network failure, not a failed save`() {
        val message = java.net.ConnectException("connection refused").toMessage()

        assertEquals(
            "a refused connection reached the person as a storage failure",
            ai.passioncode.fabricvr.common.R.string.error_network,
            message.textRes,
        )
        assertEquals(UiAction.RETRY_LOAD, message.action)
    }

    @Test fun `a selected tag survives its last note losing it`() = runTest(dispatcher) {
        val repo = TaggedRepo()
        val vm = NotesViewModel(repo, { LocalDate.of(2026, 9, 21) }, FakeVault(tempRoot()), ticks, appScope = appScope, mirrorFailures = mirror, outbox = outbox)
        advanceUntilIdle()

        vm.selectTag("идея")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.notes.size)

        repo.dropTheTag()
        advanceUntilIdle()

        assertEquals("the selection was silently released", "идея", vm.state.value.selectedTag)
        assertTrue("the tag is still offered after its last note lost it", vm.state.value.tags.isEmpty())
        assertTrue("something still matches a tag nothing carries", vm.state.value.notes.isEmpty())
    }

    /** A repository whose one tagged note can lose its tag while the filter is on. */
    private class TaggedRepo : NotesRepository {
        private val tagged = MutableStateFlow(true)
        private val note = NotesRepository.newNote("мысль", "текст #идея")
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = tagged.map { has ->
            when {
                tag == null -> listOf(note)
                has && tag == "идея" -> listOf(note)
                else -> emptyList()
            }
        }
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = tagged.map { if (it) listOf("идея") else emptyList() }
        override suspend fun get(id: String): Note? = note
        override suspend fun upsert(note: Note): Result<Note> = Result.success(note)
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun count(): Int = 1
        override suspend fun dailyNote(day: LocalDate): Result<Note> = Result.success(note)
        override suspend fun recent(limit: Int): List<Note> = listOf(note)
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()

        fun dropTheTag() { tagged.value = false }
    }
}

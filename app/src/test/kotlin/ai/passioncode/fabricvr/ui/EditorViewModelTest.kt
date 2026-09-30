package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.vault.AudioUsage
import ai.passioncode.fabricvr.vault.Vault
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    /**
     * Stands in for `Graph.scope`: the same virtual clock as the test, and nothing the view model
     * can cancel. Not `runTest`'s own `backgroundScope` — that registers its coroutines as
     * background work, which `advanceUntilIdle` deliberately does not wait for, so every write this
     * task moves off `viewModelScope` would silently never run and every assertion would read an
     * empty repository.
     */
    private val appScope = CoroutineScope(dispatcher)

    private class FakeRepo : NotesRepository {
        val saved = mutableListOf<Note>()
        var note = Note(id = "n1", title = "", body = "", createdAt = 1, updatedAt = 1)
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(listOf(note))
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        /** Holds an upsert open so a test can type a keystroke while a write is genuinely in flight. */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun get(id: String): Note? = note
        /**
         * Which rows exist. `saved` counts writes; this answers the question a person asks, which
         * is whether the note is still there — and `upsert` is a `REPLACE`, so a write after a
         * delete puts it back rather than failing (`B-213`).
         */
        val live = mutableSetOf("n1")
        override suspend fun upsert(note: Note): Result<Note> {
            gate?.await()
            saved.add(note); live.add(note.id); this.note = note; return Result.success(note)
        }
        var failDelete = false
        override suspend fun delete(id: String): Result<Unit> =
            if (failDelete) {
                Result.failure(IllegalStateException("disk full"))
            } else {
                live.remove(id); Result.success(Unit)
            }
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> = Result.success(note)
        override suspend fun count(): Int = 0
        override suspend fun recent(limit: Int): List<Note> = listOf(note).take(limit)
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    /** Moves the file the way the real vault does, so a test can see whether the note followed it. */
    private class FakeVault(override val root: File) : Vault {
        override suspend fun audioUsage(): Result<AudioUsage> = Result.success(AudioUsage(0, 0L))
        override suspend fun removeAudio(note: Note): Result<Unit> = Result.success(Unit)
        override suspend fun sweepAudio(cutoff: Long): Result<AudioUsage> = Result.success(AudioUsage(0, 0L))
        var failNext = false
        override fun pathFor(note: Note): File = File(root, "notes/${note.id}.md")
        override fun audioPathFor(note: Note): File = File(root, "notes/${note.id}.wav")
        override suspend fun write(note: Note): Result<File> = Result.success(pathFor(note))
        override suspend fun remove(id: String, createdAt: Long): Result<Unit> = Result.success(Unit)
        override suspend fun restore(id: String, createdAt: Long): Result<Unit> = Result.success(Unit)
        override fun trashedFiles(id: String): List<File> = emptyList()
        override suspend fun purgeTrash(olderThanMillis: Long): Result<Int> = Result.success(0)
        override suspend fun adoptAudio(note: Note, source: File): Result<File> {
            if (failNext) return Result.failure(IllegalStateException("no room"))
            val target = audioPathFor(note)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            source.delete()
            return Result.success(target)
        }
    }

    @After fun tearDown() = appScope.cancel()

    @Test fun `an edit still in the autosave window is written when the editor closes`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = EditorViewModel(repo, FakeVault(tempRoot()), appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.edit(title = "half-typed thought")
        // The person leaves before the debounce elapses — the text must not be lost.
        vm.flush()
        advanceUntilIdle()

        assertTrue("nothing was written", repo.saved.isNotEmpty())
        assertEquals("half-typed thought", repo.saved.last().title)
    }

    /**
     * `REQ-059` / `M1`. **Deleting in the editor could resurrect the note.**
     *
     * `delete()` cancelled nothing: the 600 ms autosave armed by the last keystroke was still
     * counting, and `NoteEditorScreen`'s `onDispose { flush() }` fired as the screen went away —
     * both write through `NotesRepository.upsert`, which is a `REPLACE`. So the row the person
     * had just deleted came back, carrying the text they typed a moment before deleting it, and
     * the vault mirror copied it into their own folder.
     *
     * Watched failing before the fix: the autosave alone wrote the note back.
     */
    @Test fun `deleting cancels the autosave and disarms the flush`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = EditorViewModel(repo, FakeVault(tempRoot()), appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.edit(body = "half a thought")
        vm.delete { }
        advanceUntilIdle()
        // What `onDispose` does on the way out of the editor.
        vm.flush()
        advanceUntilIdle()

        assertEquals(
            "the deleted note was written back through a REPLACE upsert: ${repo.saved}",
            0,
            repo.saved.size,
        )
        assertTrue("the editor still claims unsaved work for a note that is gone", vm.state.value.saved)
    }

    @Test fun `an edit is written once the autosave window elapses`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = EditorViewModel(repo, FakeVault(tempRoot()), appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.edit(body = "typed and left alone")
        advanceUntilIdle()

        assertEquals("typed and left alone", repo.saved.last().body)
    }

    @Test fun `attaching a transcript appends it to the body and keeps the existing title`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.note = repo.note.copy(title = "Standup", body = "first line")
        val vm = EditorViewModel(repo, FakeVault(tempRoot()), appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.attachTranscript(Transcript("dictated sentence", "en", SttSource.LOCAL, "whisper-base", 1_200), null)
        advanceUntilIdle()

        val saved = repo.saved.last()
        assertEquals("first line\n\ndictated sentence", saved.body)
        assertEquals("a transcript must not rename a note the person already titled", "Standup", saved.title)
        assertEquals("dictated sentence", saved.transcript?.text)
    }

    @Test fun `an untitled note takes its title from the dictation`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = EditorViewModel(repo, FakeVault(tempRoot()), appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.attachTranscript(Transcript("buy the cable", "en", SttSource.LOCAL, "whisper-base", 1_200), null)
        advanceUntilIdle()

        assertEquals("buy the cable", repo.saved.last().title)
    }

    @Test fun `the recording is adopted into the vault before the note is written`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val root = tempRoot()
        val vault = FakeVault(root)
        val scratch = File(root, "scratch.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(7)) }
        val vm = EditorViewModel(repo, vault, appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.attachTranscript(Transcript("spoken", "en", SttSource.LOCAL, "whisper-base", 1_200), scratch.absolutePath)
        advanceUntilIdle()

        assertEquals(
            "the note kept pointing at the scratch file",
            vault.audioPathFor(repo.note).absolutePath,
            repo.saved.last().audioPath,
        )
        assertTrue("nothing was written into the vault", vault.audioPathFor(repo.note).exists())
        assertTrue("the scratch copy survived", !scratch.exists())
    }

    @Test fun `when the vault cannot take the recording the note keeps the path it has`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val root = tempRoot()
        val vault = FakeVault(root).apply { failNext = true }
        val scratch = File(root, "scratch.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(7)) }
        val vm = EditorViewModel(repo, vault, appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.attachTranscript(Transcript("spoken", "en", SttSource.LOCAL, "whisper-base", 1_200), scratch.absolutePath)
        advanceUntilIdle()

        // Honest degradation: the recording stays where it is and the mirror copies it later.
        assertEquals(scratch.absolutePath, repo.saved.last().audioPath)
        assertTrue("the recording was lost on a failure", scratch.exists())
    }

    // --- T-007: one pending write per editor, and the last one outlives the editor -------------

    @Test fun `a dictation is not reverted by the autosave it interrupted`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = EditorViewModel(repo, FakeVault(tempRoot()), appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.edit(body = "hello")
        // Mid-sentence, inside the 600 ms debounce, the person presses Record and speaks.
        advanceTimeBy(100)
        vm.attachTranscript(Transcript("spoken", "en", SttSource.LOCAL, "whisper-base", 1_200), null)
        // Far enough for the autosave scheduled BEFORE the dictation to fire as well.
        advanceUntilIdle()

        val last = repo.saved.last()
        assertNotNull("the pre-dictation autosave wrote its stale note over the dictation", last.transcript)
        assertTrue("the dictated text is not in the body that was written", last.body.contains("spoken"))
    }

    /**
     * The sibling of `an edit still in the autosave window is written when the editor closes`, and
     * the reason there are two: that one never clears the view model, so it passes whether or not
     * the write survives `onCleared()`. Navigation always clears it — `onBack()` pops the
     * back-stack entry and clears that entry's store, and the Space clears the store before the
     * composition is disposed. This is the one that fails when `flush()` launches into a scope
     * that is about to be cancelled.
     */
    @Test fun `the last write survives the view model being cleared`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EditorViewModel(repo, FakeVault(tempRoot()), appScope = appScope) as T
            },
        )[EditorViewModel::class.java]
        vm.load("n1")
        advanceUntilIdle()

        vm.edit(body = "half-typed thought")
        vm.flush()
        store.clear()
        advanceUntilIdle()

        assertTrue("the half-typed thought was never written at all", repo.saved.isNotEmpty())
        assertEquals("half-typed thought", repo.saved.last().body)
    }

    /**
     * The guard on the fix rather than on the defect: sharing `saveJob` with the dictation would
     * let a keystroke cancel a file move. `adoptAudio` is `renameTo` with a copy-then-delete
     * fallback, so a cancellation between the copy and the delete leaves two files and one after
     * the rename leaves the note pointing at a path that has just stopped existing.
     */
    @Test fun `a keystroke during a dictation does not cancel the adoption`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val root = tempRoot()
        val vault = FakeVault(root)
        val scratch = File(root, "scratch.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(7)) }
        val vm = EditorViewModel(repo, vault, appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.attachTranscript(Transcript("spoken", "en", SttSource.LOCAL, "whisper-base", 1_200), scratch.absolutePath)
        // Typed before the move has had a chance to run.
        vm.edit(body = "typed right after speaking")
        advanceUntilIdle()

        val landed = vault.audioPathFor(repo.note)
        assertTrue("the recording never reached the vault", landed.exists())
        assertTrue("the scratch copy survived beside the vault copy", !scratch.exists())
        assertEquals(
            "the written note does not point at the recording",
            landed.absolutePath,
            repo.saved.last().audioPath,
        )
    }

    /**
     * `save`'s success branch is the third revert path: it wrote the note it had SENT back into the
     * state, so anything typed while the upsert was in flight was undone by its completion.
     */
    @Test fun `a keystroke during an in-flight save is not reverted by its success`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.gate = CompletableDeferred()
        val vm = EditorViewModel(repo, FakeVault(tempRoot()), appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.attachTranscript(Transcript("spoken", "en", SttSource.LOCAL, "whisper-base", 1_200), null)
        runCurrent()
        vm.edit(body = "typed while the dictation was being written")
        repo.gate?.complete(Unit)
        // Deliberately not `advanceUntilIdle`: the point is the state between the upsert landing
        // and the new keystroke's own debounce elapsing.
        runCurrent()

        assertEquals(
            "the in-flight save wrote its own note back over a newer keystroke",
            "typed while the dictation was being written",
            vm.state.value.note?.body,
        )
    }

    /**
     * A-08, which this task closes by mechanism rather than by name: the old code published the
     * pre-adoption note into the state and then let `flush()` race the adoption, so tapping *Back*
     * straight after speaking could write `audioPath = null` over the path the recording had just
     * landed at — the wav in the vault, the note unable to find it. `save()` reading the state when
     * it runs is what makes the order stop mattering.
     */
    @Test fun `flushing immediately after a dictation keeps the adopted audio path`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val root = tempRoot()
        val vault = FakeVault(root)
        val scratch = File(root, "scratch.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(7)) }
        val vm = EditorViewModel(repo, vault, appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.attachTranscript(Transcript("spoken", "en", SttSource.LOCAL, "whisper-base", 1_200), scratch.absolutePath)
        vm.flush()
        advanceUntilIdle()

        assertEquals(
            "Back straight after a dictation wrote a null audio path over the adopted one",
            vault.audioPathFor(repo.note).absolutePath,
            repo.saved.last().audioPath,
        )
    }

    /**
     * **`B-213` — a dictation followed by *Delete* resurrected the note.**
     *
     * `attachTranscript` launches adopt-then-`save()` on the application scope and deliberately
     * does not assign `saveJob`: the adoption is a file move, and a job cancelled before its first
     * dispatch never runs its body at all, so the recording would be left in a scratch directory a
     * sweep is about to clear. `delete()` cancels `saveJob` and sets `saved = true`, which disarms
     * the autosave and `flush()` — and reaches **neither** of those two, because neither is that
     * job. The in-flight `save()` then read the state late (`I-01`, correctly) and wrote the note
     * through a `REPLACE` upsert after the row had been deleted; `VaultMirror` copied the
     * resurrection into the person's own folder.
     *
     * `M1` closed this for the autosave path and its KDoc says so; this is the other writer, and
     * it is the one a person reaches by speaking into a note and then deciding against it. Both
     * controls sit in one `Row` (`NoteEditorScreen.kt:159-188`), so the two taps are adjacent.
     *
     * The gate holds the upsert open, which is what makes the write genuinely in flight rather
     * than merely scheduled.
     */
    @Test fun `deleting during a dictation write does not resurrect the note`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val root = tempRoot()
        val vault = FakeVault(root)
        val scratch = File(root, "scratch.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(7)) }
        val vm = EditorViewModel(repo, vault, appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        repo.gate = CompletableDeferred()
        vm.attachTranscript(Transcript("spoken", "en", SttSource.LOCAL, "whisper-base", 1_200), scratch.absolutePath)
        advanceUntilIdle()

        var deleted = false
        vm.delete { deleted = true }
        advanceUntilIdle()

        // The write that was already in flight when the person pressed *Delete* now lands.
        repo.gate?.complete(Unit)
        repo.gate = null
        advanceUntilIdle()

        assertTrue("the fixture never deleted the note", deleted)
        assertFalse(
            "the note came back after being deleted, written by the dictation's own save: ${repo.saved}",
            repo.live.contains("n1"),
        )
    }

    /**
     * And the other direction, because a guard that refuses every write is the cheap way to pass
     * the case above: a delete that **failed** leaves the note in existence, so the edit still has
     * to reach the disk. `delete()` already puts `saved` back for that reason; the disarm must go
     * back with it, or one storage failure would silently stop the editor saving for ever.
     */
    @Test fun `a delete that fails re-arms the writes it disarmed`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { failDelete = true }
        val vm = EditorViewModel(repo, FakeVault(tempRoot()), appScope = appScope)
        vm.load("n1")
        advanceUntilIdle()

        vm.edit(body = "still here")
        vm.delete { }
        advanceUntilIdle()
        val writesAfterFailedDelete = repo.saved.size

        vm.edit(body = "and still typing")
        advanceUntilIdle()

        assertTrue(
            "a failed delete left the editor unable to save anything ever again",
            repo.saved.size > writesAfterFailedDelete,
        )
        assertEquals("and still typing", repo.saved.last().body)
    }

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "fabricvr-test-${System.nanoTime()}").apply { mkdirs() }
}

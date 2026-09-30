package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.vault.AudioUsage
import ai.passioncode.fabricvr.vault.Vault
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * **A dictation leaves the disk only after the note it became is written** (`B-237`, `DEC-0088`).
 *
 * The defect: `NotesViewModel.drainOutbox` called `DictationOutbox.claim`, and `claim` deleted
 * `dictation.tsv` there and then — before `commitDictation` had moved the audio or written the
 * row. A process death in that window, or an `upsert` that failed, left the words in RAM only,
 * which is exactly the state `DEC-0068` exists to make impossible. The editor's path had the same
 * shape one layer up: it claimed, appended to its state, and wrote on a debounce.
 *
 * Every case reads the **file**, through a fresh `DictationOutbox(file).restore()` — the thing a
 * new process would see — rather than the in-memory flow, because memory is what survives nothing.
 * View-model tier with a test dispatcher (`SI-05`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationDurabilityTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    private val appScope = CoroutineScope(dispatcher)

    private val root: File =
        File(System.getProperty("java.io.tmpdir"), "durability-${System.nanoTime()}").apply { mkdirs() }

    private val file = File(root, "outbox/dictation.tsv")

    private val outbox = DictationOutbox(file)

    @After fun tearDown() {
        appScope.cancel()
        root.deleteRecursively()
    }

    private fun transcript(text: String = "не потеряй меня") =
        Transcript(text, "ru", SttSource.LOCAL, "whisper-base", 900)

    /** What a new process would find on the disk. */
    private fun onDisk() = DictationOutbox(file).restore()

    /**
     * A repository that can refuse writes, and that answers `get` **by id** — the survival test's
     * fake returns its last row for any id, which would make an idempotency check pass vacuously.
     */
    private inner class Repo(
        var failWrites: Int = 0,
        /** Upsert numbers (1-based) that fail, for orders [failWrites] cannot express. */
        private val failOn: Set<Int> = emptySet(),
        /** Held open before the first upsert completes, so a test can act while it is in flight. */
        private val firstGate: CompletableDeferred<Unit>? = null,
    ) : NotesRepository {
        val rows = linkedMapOf<String, Note>()
        /** A write reported as failed whose row landed anyway — a commit whose answer was lost. */
        var landsAnyway = false
        var upserts = 0
        /** Whether the dictation was still on disk at the moment each write ran. */
        val onDiskDuringWrite = mutableListOf<Boolean>()
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = rows[id]
        override suspend fun upsert(note: Note): Result<Note> {
            upserts++
            val number = upserts
            onDiskDuringWrite += file.isFile
            // NonCancellable: a statement Room has issued is not recalled by cancelling the caller
            // (`EditorViewModel`'s own KDoc on `attachJob`), and the append cancels `saveJob`.
            if (number == 1) firstGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            if (number in failOn) return Result.failure(IOException("disk full"))
            if (failWrites > 0) {
                failWrites--
                if (landsAnyway) rows[note.id] = note
                return Result.failure(IOException("disk full"))
            }
            rows[note.id] = note
            return Result.success(note)
        }
        override suspend fun delete(id: String): Result<Unit> { rows.remove(id); return Result.success(Unit) }
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> = Result.success(NotesRepository.newNote())
        override suspend fun count(): Int = rows.size
        override suspend fun recent(limit: Int): List<Note> = rows.values.toList()
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    private class FakeVault(override val root: File) : Vault {
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
        override suspend fun adoptAudio(note: Note, source: File): Result<File> = runCatching {
            val target = audioPathFor(note)
            if (source.absolutePath == target.absolutePath) return@runCatching target
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            source.delete()
            target
        }
    }

    private fun notes(repo: NotesRepository, box: DictationOutbox = outbox) = NotesViewModel(
        repository = repo,
        today = { LocalDate.of(2026, 9, 23) },
        vault = FakeVault(root),
        dayTicks = emptyFlow(),
        appScope = appScope,
        mirrorFailures = MutableStateFlow(emptyMap<String, AppError>()),
        retryMirror = {},
        outbox = box,
        awaitReconcile = {},
    )

    private fun recording(name: String = "one.wav"): String =
        File(root, "audio/$name").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }.absolutePath

    // ---- the outbox itself ----------------------------------------------------------------

    @Test fun `a claim takes the entry in memory and leaves it on disk`() {
        val entry = outbox.offer(transcript(), "/audio/a.wav")
        assertNotNull(outbox.claim(entry.id))
        assertNull("the claim did not take the entry in memory", outbox.pending.value)
        assertEquals("the claim erased the only durable copy", "не потеряй меня", onDisk()?.transcript?.text)
    }

    @Test fun `settle erases the entry, and only that entry`() {
        val first = outbox.offer(transcript("первая"), "/audio/1.wav")
        outbox.claim(first.id)
        outbox.offer(transcript("вторая"), "/audio/2.wav")
        outbox.settle(first)
        assertEquals("settling the first erased the second's file", "вторая", onDisk()?.transcript?.text)

        val second = outbox.claim("/audio/2.wav")!!
        outbox.settle(second)
        assertFalse("a settled entry is still on disk", file.isFile)
    }

    @Test fun `a bound entry carries its note across a restart`() {
        val entry = outbox.offer(transcript(), "/audio/a.wav")
        outbox.claim(entry.id)
        outbox.bind(entry, noteId = "n-42", createdAt = 1_700_000_000_000L, audioPath = "/vault/n-42.wav")

        val back = onDisk()!!
        assertEquals("n-42", back.noteId)
        assertEquals(1_700_000_000_000L, back.createdAt)
        assertEquals("/vault/n-42.wav", back.audioPath)
        assertEquals("the restored entry changed identity, so no drain could claim it", entry.id, back.id)
    }

    // ---- Today's commit -------------------------------------------------------------------

    @Test fun `the dictation is still on disk while its note is being written`() = runTest(dispatcher) {
        val repo = Repo()
        notes(repo)
        advanceUntilIdle()

        outbox.offer(transcript(), recording())
        advanceUntilIdle()

        assertEquals(1, repo.rows.size)
        assertEquals("the outbox was erased before the write that makes it redundant", listOf(true), repo.onDiskDuringWrite)
        assertNull("a written dictation stayed on disk and will be written again next launch", onDisk())
    }

    @Test fun `a write that fails leaves the dictation on disk, bound to the note it was becoming`() =
        runTest(dispatcher) {
            val repo = Repo(failWrites = 1)
            val vm = notes(repo)
            advanceUntilIdle()

            outbox.offer(transcript(), recording())
            advanceUntilIdle()

            assertTrue("the fixture's write did not fail", repo.rows.isEmpty())
            val left = onDisk()
            assertNotNull("a failed write lost the dictation from disk — only RAM held it", left)
            assertEquals(vm.state.value.pendingDictation?.id, left!!.noteId)
            assertEquals(
                "the disk points at the scratch recording the commit had already moved",
                vm.state.value.pendingDictation?.audioPath,
                left.audioPath,
            )
        }

    @Test fun `Retry after a failed write settles the entry`() = runTest(dispatcher) {
        val repo = Repo(failWrites = 1)
        val vm = notes(repo)
        advanceUntilIdle()
        outbox.offer(transcript(), recording())
        advanceUntilIdle()

        vm.retryDictation()
        advanceUntilIdle()

        assertEquals(1, repo.rows.size)
        assertNull("Retry wrote the note and left the dictation to be written again", onDisk())
    }

    @Test fun `a restored entry whose note was already written is settled, not written twice`() =
        runTest(dispatcher) {
            // A previous process: bound, wrote the row, died before the settle.
            val entry = outbox.offer(transcript(), "/audio/gone.wav")
            outbox.claim(entry.id)
            outbox.bind(entry, noteId = "n-7", createdAt = 1_700_000_000_000L, audioPath = null)
            val repo = Repo()
            repo.rows["n-7"] = NotesRepository.newNote(body = "не потеряй меня").copy(id = "n-7")

            val next = DictationOutbox(file)
            next.restore()
            notes(repo, next)
            advanceUntilIdle()

            assertEquals("a dictation that had already become a note was written a second time", 0, repo.upserts)
            assertNull("the settled entry is still on disk", onDisk())
        }

    @Test fun `a restored bound entry whose row never landed is written under its bound id`() =
        runTest(dispatcher) {
            val entry = outbox.offer(transcript(), "/audio/gone.wav")
            outbox.claim(entry.id)
            outbox.bind(entry, noteId = "n-8", createdAt = 1_700_000_000_000L, audioPath = null)
            val repo = Repo()

            val next = DictationOutbox(file)
            next.restore()
            notes(repo, next)
            advanceUntilIdle()

            assertEquals(listOf("n-8"), repo.rows.keys.toList())
            assertEquals(1_700_000_000_000L, repo.rows.getValue("n-8").createdAt)
            assertNull(onDisk())
        }

    // ---- the editor's append ----------------------------------------------------------------

    @Test fun `an appended dictation is settled only by a write that carried it`() = runTest(dispatcher) {
        val repo = Repo(failWrites = 1)
        repo.rows["n1"] = NotesRepository.newNote(title = "open", body = "before").copy(id = "n1")
        val editor = EditorViewModel(repo, FakeVault(root), appScope = appScope)
        editor.load("n1")
        advanceUntilIdle()

        val entry = outbox.offer(transcript("дописано"), null)
        outbox.claim(entry.id)
        editor.attachTranscript(entry.transcript, null, onDurable = { outbox.settle(entry) })
        advanceUntilIdle()
        assertNotNull("the editor's failed write lost the appended words from disk", onDisk())

        editor.retry()
        advanceUntilIdle()
        assertTrue(repo.rows.getValue("n1").body.contains("дописано"))
        assertNull("the append landed and the dictation will be written again as a note", onDisk())
    }

    /**
     * A write already in flight with the **pre-dictation** note does not settle the dictation
     * appended while it was flying: it never sent those words. Here that stale write succeeds and
     * the append's own write fails — so the words are on disk nowhere but the outbox.
     */
    @Test fun `a write that left before the append does not settle it`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = Repo(failOn = setOf(2), firstGate = gate)
        repo.rows["n1"] = NotesRepository.newNote(title = "open", body = "before").copy(id = "n1")
        val editor = EditorViewModel(repo, FakeVault(root), appScope = appScope)
        editor.load("n1")
        advanceUntilIdle()

        editor.edit(body = "before, edited")
        editor.flush()
        advanceUntilIdle()
        assertEquals("the fixture's first write is not in flight", 1, repo.upserts)

        val entry = outbox.offer(transcript("пока летело"), null)
        outbox.claim(entry.id)
        editor.attachTranscript(entry.transcript, null, onDurable = { outbox.settle(entry) })
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("the fixture never attempted the append's write", repo.upserts >= 2)
        assertFalse(repo.rows.getValue("n1").body.contains("пока летело"))
        assertNotNull("a write that never carried the words settled them — they are now nowhere", onDisk())
    }

    // ---- B-241: a quick Undo keeps the recording ----------------------------------------------

    /**
     * **An Undo faster than the mirror keeps the recording** (`B-241`). `vault.restore` fails when
     * the mirror has not yet moved the files to the trash, and the note used to come back with
     * `audioPath = null` and *"recording not restored"* — then the mirror trashed the file anyway.
     * When the recording is still where the note says, the note keeps pointing at it; the mirror
     * brings it back if it trashes it first (`VaultMirror.write`).
     */
    @Test fun `an undo that beats the mirror keeps the note's recording`() = runTest(dispatcher) {
        val repo = Repo()
        val live = File(root, "notes/n-u.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        val note = NotesRepository.newNote(body = "с записью").copy(id = "n-u", audioPath = live.absolutePath)
        repo.rows[note.id] = note
        val tooEarly = object : Vault by FakeVault(root) {
            override suspend fun restore(id: String, createdAt: Long): Result<Unit> =
                Result.failure(java.io.FileNotFoundException("the trash no longer holds note $id"))
        }
        val vm = NotesViewModel(
            repository = repo,
            today = { LocalDate.of(2026, 9, 23) },
            vault = tooEarly,
            dayTicks = emptyFlow(),
            appScope = appScope,
            mirrorFailures = MutableStateFlow(emptyMap<String, AppError>()),
            retryMirror = {},
            outbox = outbox,
            awaitReconcile = {},
            io = dispatcher,
        )
        advanceUntilIdle()
        vm.delete(note)
        advanceUntilIdle()

        vm.undoDelete()
        advanceUntilIdle()

        assertEquals("the undo dropped a recording that was still there", live.absolutePath, repo.rows.getValue("n-u").audioPath)
        assertNull("the person was told a kept recording was lost", vm.state.value.message)
    }

    /**
     * **A Retry that finds the note already written clears its banner** (seam-tier verification of
     * `DEC-0088`). The already-written branch settled the entry and returned, leaving *"Couldn't
     * save"* and its *Retry* on screen over a note that was in the list.
     */
    @Test fun `a retry that finds its note already written clears the banner`() = runTest(dispatcher) {
        val repo = Repo(failWrites = 1).apply { landsAnyway = true }
        val vm = notes(repo)
        advanceUntilIdle()
        outbox.offer(transcript(), recording())
        advanceUntilIdle()
        assertNotNull("the fixture's write did not report a failure", vm.state.value.message)

        vm.retryDictation()
        advanceUntilIdle()

        assertEquals("the retry wrote a second copy of a note that had landed", 1, repo.upserts)
        assertNull("the banner stayed up over a note that is in the list", vm.state.value.message)
        assertNull(vm.state.value.pendingDictation)
        assertNull(onDisk())
    }

    // ---- B-255: a failed commit is not overwritten by the next dictation ----------------------

    /**
     * Every dictation a new process would find, in the order it would drain them. Read from a COPY
     * of the outbox directory: since `DEC-0095` the next entry is published by `settle`, which
     * erases, and a helper that looked would otherwise change what it was looking at.
     */
    private fun allOnDisk(): List<String> {
        val copy = File(root, "outbox-copy-${System.nanoTime()}")
        file.parentFile?.takeIf { it.isDirectory }?.copyRecursively(copy)
        val next = DictationOutbox(File(copy, file.name))
        next.restore()
        val found = mutableListOf<String>()
        while (true) {
            val entry = next.pending.value ?: break
            found += entry.transcript.text
            next.claim(entry.id)
            next.settle(entry)
        }
        copy.deleteRecursively()
        return found
    }

    @Test fun `an offer does not overwrite a claimed dictation that was never settled`() {
        val first = outbox.offer(transcript("первая, не записалась"), "/audio/1.wav")
        outbox.claim(first.id)
        outbox.offer(transcript("вторая"), "/audio/2.wav")

        assertEquals(
            "the second dictation's offer erased the first from disk",
            setOf("первая, не записалась", "вторая"),
            allOnDisk().toSet(),
        )
    }

    @Test fun `settling a dictation that was moved aside removes only it`() {
        val first = outbox.offer(transcript("первая"), "/audio/1.wav")
        outbox.claim(first.id)
        outbox.offer(transcript("вторая"), "/audio/2.wav")

        outbox.settle(first)

        assertEquals(listOf("вторая"), allOnDisk())
    }

    /**
     * **The whole of `B-255`, end to end.** A's write fails and its banner is up; B is dictated and
     * written. B's success used to clear `unsettled` and `pendingDictation` — A was gone from memory,
     * and its offer had overwritten A on disk. A must survive for the next launch at least.
     */
    @Test fun `a dictation that failed to write survives the next one being written`() = runTest(dispatcher) {
        val repo = Repo(failWrites = 1)
        notes(repo)
        advanceUntilIdle()
        outbox.offer(transcript("первая, не записалась"), recording("1.wav"))
        advanceUntilIdle()
        assertTrue("the fixture's first write did not fail", repo.rows.isEmpty())

        outbox.offer(transcript("вторая"), recording("2.wav"))
        advanceUntilIdle()

        assertEquals("the second dictation was not written", 1, repo.rows.size)
        assertEquals(
            "the dictation whose write failed is gone from disk once the next one landed",
            listOf("первая, не записалась"),
            allOnDisk(),
        )
    }

    /**
     * **A dictation brought back from aside is erased when it is written** (seam verification of
     * `DEC-0095`). `bind` persists into the MAIN file, and `erase` deleted the main file and
     * returned — so the aside copy survived, and the same dictation became a new note on every
     * launch after.
     */
    @Test fun `an aside dictation written on the next launch is gone from disk afterwards`() = runTest(dispatcher) {
        val a = outbox.offer(transcript("отложенная"), "/audio/a.wav")
        outbox.claim(a.id)
        val b = outbox.offer(transcript("свежая"), "/audio/b.wav")
        outbox.claim(b.id)
        outbox.settle(b)

        val next = DictationOutbox(file)
        next.restore()
        val repo = Repo()
        notes(repo, next)
        advanceUntilIdle()

        assertEquals(listOf("отложенная"), repo.rows.values.map { it.body })
        assertEquals("the written dictation is still on disk and will be written again", emptyList<String>(), allOnDisk())
    }

    /** Two restored entries are drained one after the other, and nothing is left behind. */
    @Test fun `two restored dictations are both written and neither is left on disk`() = runTest(dispatcher) {
        val a = outbox.offer(transcript("первая"), "/audio/a.wav")
        outbox.claim(a.id)
        outbox.offer(transcript("вторая"), "/audio/b.wav")

        val next = DictationOutbox(file)
        next.restore()
        val repo = Repo()
        notes(repo, next)
        advanceUntilIdle()

        assertEquals(setOf("первая", "вторая"), repo.rows.values.map { it.body }.toSet())
        assertEquals(emptyList<String>(), allOnDisk())
    }

    /**
     * **A recovered dictation whose recording has moved is written without a path, not with a
     * dangling one** (`B-256`). The editor path moves a recording into the open note before any
     * write lands and never binds the outbox entry, so after a crash the entry still names the
     * scratch file — gone — and the recovered note pointed at nothing: no *Play*, no *Transcribe
     * again*, and a row that claims a recording it does not have.
     */
    @Test fun `a recovered dictation whose recording is gone is written without an audio path`() = runTest(dispatcher) {
        val repo = Repo()
        notes(repo)
        advanceUntilIdle()

        outbox.offer(transcript("запись уехала"), File(root, "audio/gone.wav").absolutePath)
        advanceUntilIdle()

        val written = repo.rows.values.single()
        assertEquals("запись уехала", written.body)
        assertNull("the note claims a recording that does not exist: ${written.audioPath}", written.audioPath)
    }

    // ---- B-254: the note lists the recordings it keeps ----------------------------------------

    /** Hold directory work independently of Main so completion order is deterministic. */
    private class ListingDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        val pending = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            pending.addLast(block)
        }
    }

    @Test fun `an old listing cannot restore recordings after the same note has none`() = runTest(dispatcher) {
        val repo = Repo()
        val current = File(root, "old.wav").apply { writeBytes(byteArrayOf(1)) }
        val note = NotesRepository.newNote().copy(id = "listing", audioPath = current.absolutePath)
        repo.rows[note.id] = note
        val vault = object : Vault by FakeVault(root) {
            override fun recordingsOf(note: Note): List<File> = listOf(current)
        }
        val io = ListingDispatcher()
        val editor = EditorViewModel(repo, vault, appScope = appScope, io = io)
        editor.load(note.id)
        advanceUntilIdle()
        assertEquals(1, io.pending.size)

        repo.rows[note.id] = note.copy(audioPath = null)
        editor.load(note.id)
        advanceUntilIdle()
        io.pending.removeFirst().run()
        advanceUntilIdle()

        assertNull(editor.state.value.note?.audioPath)
        assertEquals(emptyList<RecordingItem>(), editor.state.value.recordings)
    }

    @Test fun `the newest listing wins when directory work finishes backwards`() = runTest(dispatcher) {
        val repo = Repo()
        val old = File(root, "old.wav").apply { writeBytes(byteArrayOf(1)) }
        val latest = File(root, "latest.wav").apply { writeBytes(byteArrayOf(2)) }
        val note = NotesRepository.newNote().copy(id = "listing", title = "before", audioPath = latest.absolutePath)
        repo.rows[note.id] = note
        val vault = object : Vault by FakeVault(root) {
            override fun recordingsOf(note: Note): List<File> =
                if (note.title == "before") listOf(latest, old) else listOf(latest)
        }
        val io = ListingDispatcher()
        val editor = EditorViewModel(repo, vault, appScope = appScope, io = io)
        editor.load(note.id)
        advanceUntilIdle()
        // The current path stays the same: older files can be swept while a listing is in flight.
        repo.rows[note.id] = note.copy(title = "after")
        editor.load(note.id)
        advanceUntilIdle()
        assertEquals(2, io.pending.size)
        io.pending.removeLast().run()
        advanceUntilIdle()
        io.pending.removeFirst().run()
        advanceUntilIdle()

        assertEquals(listOf(latest.absolutePath), editor.state.value.recordings.map { it.path })
    }

    @Test fun `the editor lists every recording the note keeps, current first`() = runTest(dispatcher) {
        val repo = Repo()
        val current = File(root, "notes/n-r.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(2)); setLastModified(2_000_000L) }
        repo.rows["n-r"] = NotesRepository.newNote(body = "с двумя записями").copy(id = "n-r", audioPath = current.absolutePath)
        val earlier = File(root, "notes/n-r~1000000.wav").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000_000L) }
        val vault = object : Vault by FakeVault(root) {
            override fun recordingsOf(note: Note): List<File> = listOf(current, earlier)
        }
        val editor = EditorViewModel(repo, vault, appScope = appScope, io = dispatcher)

        editor.load("n-r")
        advanceUntilIdle()

        assertEquals(
            listOf(RecordingItem(current.absolutePath, 2_000_000L), RecordingItem(earlier.absolutePath, 1_000_000L)),
            editor.state.value.recordings,
        )
    }

    /**
     * The list names the recording the note points at even outside the vault's folder, and an
     * earlier recording's time comes from its own name — an archive restore resets every file's
     * clock (seam and product verification of `B-254`).
     */
    @Test fun `the editor lists a recording kept outside the vault, and dates earlier ones by their name`() =
        runTest(dispatcher) {
            val repo = Repo()
            val scratch = File(root, "audio/kept.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(3)) }
            repo.rows["n-s"] = NotesRepository.newNote(body = "запись осталась в scratch").copy(id = "n-s", audioPath = scratch.absolutePath)
            val earlier = File(root, "notes/n-s~1700000000000.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)); setLastModified(5_000L) }
            val vault = object : Vault by FakeVault(root) {
                override fun recordingsOf(note: Note): List<File> = listOf(earlier)
            }
            val editor = EditorViewModel(repo, vault, appScope = appScope, io = dispatcher)

            editor.load("n-s")
            advanceUntilIdle()

            val listed = editor.state.value.recordings
            assertEquals("the recording the note points at is missing or not first", scratch.absolutePath, listed.first().path)
            assertEquals("an earlier recording was dated by a clock a restore resets", 1_700_000_000_000L, listed[1].takenAt)
        }
}

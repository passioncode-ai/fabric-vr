package ai.passioncode.fabricvr.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.RoomNotesRepository
import ai.passioncode.fabricvr.notes.db.NotesDatabase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `H5`, `H6`, `M3`, `M7`. **Nothing reconciled the index with the files it is an index over.**
 *
 * Three failures, one shape. A process killed between the Room commit and `vault.write` left a
 * note with no file, and the mirror's `pending` map was in memory, so after a restart the
 * out-of-sync count was zero and the export skipped the note in silence. An import killed halfway
 * left the database non-empty, and `importIfEmpty`'s gate was `recent(1).isNotEmpty()` — so the
 * remaining files were never imported again, on any later launch. And a `vault.remove` that
 * failed was retried by nothing at all, so the orphan file came back as a note the next time the
 * database was rebuilt.
 *
 * **Real Room, not a fake.** The invariants under test are the repository's — the day rule, the
 * timestamps, the FTS rows — and a fake that models them is a second implementation of the thing
 * being tested (`DEC-0058` is the entry about exactly that).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultReconcilerTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: NotesDatabase
    private lateinit var repo: NotesRepository
    private lateinit var root: File

    private val january = 1_767_225_600_000L

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RoomNotesRepository(db.noteDao())
        root = temp.newFolder("vault")
    }

    @After fun tearDown() = db.close()

    private fun vault() = FileVault(root, io = Dispatchers.Unconfined)

    private fun journal() = RemovalJournal(root, io = Dispatchers.Unconfined)

    private fun reconciler(
        v: Vault = vault(),
        r: NotesRepository = repo,
        j: RemovalJournal = journal(),
    ) = VaultReconciler(v, r, VaultImporter(v, r, Dispatchers.Unconfined), j, Dispatchers.Unconfined)

    /** A note on disk and nowhere else — the state a reinstall or a lost database leaves. */
    private suspend fun seedFile(title: String, body: String, createdAt: Long, updatedAt: Long): Note {
        val note = NotesRepository.newNote(title, body, now = createdAt).copy(updatedAt = updatedAt)
        vault().write(note).getOrThrow()
        return note
    }

    private fun noteFiles(): List<File> =
        File(root, "notes").walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.toList()

    // ---- H6: an interrupted import finishes on the next launch -------------------------------

    /**
     * **The gate was the defect.** `importIfEmpty` asked `recent(1).isNotEmpty()`, so an import
     * killed after its first note left a database that would never be completed: every later
     * launch saw a non-empty database and returned zero. The person kept the two notes that had
     * landed and silently lost the rest, with every file still on disk.
     */
    @Test fun `an import killed halfway is finished by the next reconcile, with no duplicates`() = runTest {
        val seeded = (1..5).map { seedFile("заметка $it", "тело $it", january + it * 1_000L, january + it * 1_000L) }

        val dying = object : NotesRepository by repo {
            var written = 0
            override suspend fun upsertPreservingTimestamps(note: Note): Result<Note> {
                if (written++ >= 2) throw IOException("the process was killed here")
                return repo.upsertPreservingTimestamps(note)
            }
        }
        val first = reconciler(r = dying).reconcile()
        assertTrue("the interrupted run reported success", first.isFailure)
        assertEquals("the fixture never got as far as being interrupted", 2, repo.count())

        val second = reconciler().reconcile().getOrThrow()

        assertEquals("the rest were never imported — the gate saw a non-empty database", 3, second.imported)
        assertEquals("the import ran twice over notes it had already imported", 5, repo.count())
        assertEquals(seeded.map { it.id }.toSet(), repo.identities().map { it.id }.toSet())
    }

    /** And running it again over a vault that is already in step changes nothing. */
    @Test fun `a second reconcile over a vault already in step does nothing`() = runTest {
        (1..3).map { seedFile("з$it", "т$it", january + it * 1_000L, january + it * 1_000L) }
        reconciler().reconcile().getOrThrow()

        val again = reconciler().reconcile().getOrThrow()

        assertEquals(ReconcileSummary(imported = 0, remirrored = 0, removalsRetried = 0, skipped = 0), again)
        assertEquals(3, repo.count())
    }

    // ---- M3: the timestamps the file carries are the note's ----------------------------------

    /**
     * `M3`. The import went through `upsert`, which stamps `updatedAt = now()` — so every
     * recovered note came back *"updated today"*, in one block, and the mirror then wrote that
     * lie back over the file's own `updated:`. The original date was destroyed by the operation
     * that existed to save it.
     */
    @Test fun `a file with no row becomes a row with the file's own timestamps`() = runTest {
        val note = seedFile("старая", "написана в январе", createdAt = january, updatedAt = january + 60_000)

        val summary = reconciler().reconcile().getOrThrow()

        assertEquals(1, summary.imported)
        val stored = repo.get(note.id)
        assertNotNull("the file was not imported at all", stored)
        assertEquals("the creation date was rewritten", january, stored!!.createdAt)
        assertEquals("every recovered note came back \"updated today\"", january + 60_000, stored.updatedAt)
    }

    // ---- H5: a row whose file never landed ---------------------------------------------------

    /**
     * `H5`. The mirror held the failure in memory. A process killed between the Room commit and
     * `vault.write` left a note with no file, the out-of-sync count read zero after the restart,
     * and the export — the product's only route off the headset — skipped it without a word.
     */
    @Test fun `a row whose file is missing is written back to the vault`() = runTest {
        val note = NotesRepository.newNote("в базе", "без файла", now = january)
        repo.upsert(note).getOrThrow()
        assertEquals("the fixture wrote a file this test needs to be missing", emptyList<File>(), noteFiles())

        val summary = reconciler().reconcile().getOrThrow()

        assertEquals(1, summary.remirrored)
        assertTrue("the note still has no file after a reconcile", vault().pathFor(note).isFile)
        assertTrue(vault().pathFor(note).readText().contains("без файла"))
    }

    // ---- M7: a remove that failed is retried, and Undo is not overruled -----------------------

    /**
     * `M7`. `VaultMirror.retryFailed` re-ran writes and nothing else, so a `remove` that failed
     * was never retried by anything — the orphan `.md` stayed, and the next rebuild imported the
     * note the person had deleted.
     *
     * The journal is what makes this survive a restart: the intent is written down **before** the
     * attempt, because a record written after a failure is lost to the same process death it
     * exists to outlive.
     */
    @Test fun `a remove that failed is retried, and the note is not imported back`() = runTest {
        val j = journal()
        val cannotRemove = object : Vault by vault() {
            override suspend fun remove(id: String, createdAt: Long): Result<Unit> =
                Result.failure(VaultException(ai.passioncode.fabricvr.common.AppError.Storage("vault.remove")))
        }
        val mirror = VaultMirror(repo, cannotRemove, j)
        mirror.start(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

        val note = NotesRepository.newNote("удалённая", "тело", now = january)
        repo.upsert(note).getOrThrow()
        advanceUntilIdle()
        assertTrue("the fixture never wrote the file", vault().pathFor(note).isFile)
        repo.delete(note.id).getOrThrow()
        advanceUntilIdle()
        assertTrue("the failed removal left no trace a restart could read", j.pending().isNotEmpty())
        assertTrue("the fixture removed the file this test needs left behind", vault().pathFor(note).isFile)

        val summary = reconciler().reconcile().getOrThrow()

        assertEquals(1, summary.removalsRetried)
        assertFalse("the orphan file survived the retry", vault().pathFor(note).isFile)
        assertNull("the note the person deleted came back as a fresh import", repo.get(note.id))
        assertEquals("the journal still holds a removal that succeeded", emptyList<PendingRemoval>(), j.pending())
        assertEquals("the deleted note was imported back", 0, summary.imported)
    }

    /**
     * **An edit whose mirror write died with the process reaches the vault on the next launch**
     * (`B-240`). The reconcile re-mirrored a note only when its file was missing, so a file that
     * existed but predated the row's last edit stayed stale for ever — and every export shipped the
     * old words. A stat, not a read: the file's modification time against the row's `updatedAt`.
     */
    @Test fun `a file older than its row's last edit is written again`() = runTest {
        val note = NotesRepository.newNote("было", "старый текст", now = january)
        repo.upsert(note).getOrThrow()
        vault().write(repo.get(note.id)!!).getOrThrow()
        // The edit lands in the database; its mirror write dies with the process.
        repo.upsert(repo.get(note.id)!!.copy(body = "новый текст")).getOrThrow()
        val file = vault().pathFor(note)
        file.setLastModified(repo.get(note.id)!!.updatedAt - 60_000)

        val summary = reconciler().reconcile().getOrThrow()

        assertEquals(1, summary.remirrored)
        assertTrue("the vault kept the words the person had replaced", file.readText().contains("новый текст"))
    }

    /**
     * **A live note's files come out of the trash on the next launch too** (`B-241`'s reconcile
     * half, from the seam-tier verification). A quick *Undo*, then a death after the mirror ran the
     * delete and before it ran the re-write: the row is back, its `.md` and `.wav` are in the trash.
     * The reconcile rewrote the `.md` from the row and left the recording there for the purge.
     */
    @Test fun `a live note whose files are in the trash gets them back`() = runTest {
        val v = vault()
        val scratch = File(temp.root, "scratch.wav").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val base = NotesRepository.newNote("вернули", "тело", now = january)
        val note = base.copy(audioPath = v.adoptAudio(base, scratch).getOrThrow().absolutePath)
        repo.upsert(note).getOrThrow()
        v.write(repo.get(note.id)!!).getOrThrow()
        v.remove(note.id, note.createdAt).getOrThrow()
        assertTrue("the fixture did not trash the recording", v.trashedFiles(note.id).isNotEmpty())

        reconciler(v).reconcile().getOrThrow()

        assertTrue("the live note's recording was left for the purge", File(note.audioPath!!).isFile)
        assertTrue("the trash still holds a live note's files", v.trashedFiles(note.id).isEmpty())
    }

    /**
     * **A clock ahead of this headset's does not make a note rewrite itself every launch** (seam
     * verification of `DEC-0093`). A file whose front-matter `updated` is in the future kept that
     * stamp through the import, so every later reconcile saw the file older than its row, rewrote
     * it, and saw it again the next time. The import takes `min(updated, now)`, so the loop ends after one rewrite.
     */
    @Test fun `a note imported with a future timestamp stops being rewritten`() = runTest {
        val future = System.currentTimeMillis() + 86_400_000L
        seedFile("из будущего", "тело", createdAt = january, updatedAt = future)

        // The import, then at most one rewrite (the imported row is stamped now, the file a moment
        // earlier), then nothing — where it used to be a rewrite on every launch until the clock
        // caught up with the other device's.
        reconciler().reconcile().getOrThrow()
        reconciler().reconcile().getOrThrow()
        val third = reconciler().reconcile().getOrThrow()
        val fourth = reconciler().reconcile().getOrThrow()

        assertEquals("a future-stamped note is still rewritten on every launch", 0, third.remirrored + fourth.remirrored)
    }

    /**
     * **A note deleted just before the process died stays deleted** (`B-239`).
     *
     * The mirror journals a removal when its collector reaches the `Deleted` change — after the
     * row is gone. A death in between left a `.md` with no row and no journal entry, and this
     * reconcile imported it back. The repository now records the removal through its
     * `beforeDelete` hook, which `Graph` points at this journal, before the row goes. No mirror is
     * started here on purpose: that is the process that died.
     */
    @Test fun `a note deleted before the mirror ran is not imported back after a restart`() = runTest {
        val j = journal()
        val journalled = RoomNotesRepository(db.noteDao(), beforeDelete = j::record)
        val note = NotesRepository.newNote("удалена перед смертью", "тело", now = january)
        journalled.upsert(note).getOrThrow()
        vault().write(note).getOrThrow()

        journalled.delete(note.id).getOrThrow()
        // — the process dies here, before `VaultMirror` sees the change —

        val summary = reconciler().reconcile().getOrThrow()

        assertNull("the note the person deleted came back from its own file", repo.get(note.id))
        assertEquals("the deleted note was imported back", 0, summary.imported)
        assertFalse("the orphan file survived the restart", vault().pathFor(note).isFile)
    }

    /**
     * **Undo overrules the journal, not the other way round.** A pending removal whose row is
     * back is a deletion the person took back — replaying it would delete the note a second time,
     * from a queue they cannot see.
     */
    @Test fun `a pending removal whose note has come back is dropped, not replayed`() = runTest {
        val note = seedFile("восстановленная", "тело", january, january)
        val j = journal()
        j.record(note.id, note.createdAt)
        repo.upsertPreservingTimestamps(note).getOrThrow()

        val summary = reconciler().reconcile().getOrThrow()

        assertEquals("a deletion the person undid was replayed", 0, summary.removalsRetried)
        assertTrue("Undo's note was deleted by a queue the person cannot see", vault().pathFor(note).isFile)
        assertEquals(emptyList<PendingRemoval>(), j.pending())
    }

    // ---- B-182: the day goes to the earlier note, not to whichever file the walk found first ---

    /**
     * `B-182`. `MIGRATION_1_2` demotes a duplicate row below the repository, so the Markdown file
     * keeps its `day:` while the row has none — and a later re-import then re-decided the holder
     * **by walk order**, which is `readdir` order, which is nothing.
     *
     * The fixture is built so that walk order and creation order disagree: the note created
     * *later* is written first and has an id that sorts first, so both "whatever the filesystem
     * hands back" and "sorted by path" give the wrong holder, and only `createdAt` gives the
     * right one.
     */
    @Test fun `two files claiming one day give it to the note created first`() = runTest {
        val later = NotesRepository.newNote("вторая", "другая запись того же дня", now = january + 3_600_000)
            .copy(id = "aaaaaaaa-0000-4000-8000-000000000001", dayKey = "2026-01-01")
        val earlier = NotesRepository.newNote("первая", "то, что я печатал весь день", now = january)
            .copy(id = "zzzzzzzz-0000-4000-8000-000000000002", dayKey = "2026-01-01")
        vault().write(later).getOrThrow()
        vault().write(earlier).getOrThrow()

        reconciler().reconcile().getOrThrow()

        assertEquals("a note was destroyed by the import that was recovering it", 2, repo.count())
        assertEquals(
            "the day went to whichever file the walk happened to reach first",
            earlier.id,
            repo.noteForDay("2026-01-01")?.id,
        )
        assertNotNull("the demoted note lost its words, not only its day", repo.get(later.id))
        assertEquals("другая запись того же дня", repo.get(later.id)!!.body)
    }

    // ---- the await the UI needs --------------------------------------------------------------

    /**
     * `H6`'s second half: `dailyNote(today)` could win the race against the import and create an
     * empty note for a day the vault already had one for. The first UI read has to be able to
     * wait, so the reconcile is a [kotlinx.coroutines.Deferred] rather than a fired-and-forgotten
     * launch.
     */
    @Test fun `the reconcile can be awaited before anything reads the database`() = runTest {
        seedFile("до запуска", "тело", january, january)

        val awaited = reconciler().start(backgroundScope).await().getOrThrow()

        assertEquals(1, awaited.imported)
        assertEquals(1, repo.count())
    }

    /** An unparseable file is counted, never fatal: the person is recovering from a failure. */
    @Test fun `a file that does not parse is counted as skipped and the rest are imported`() = runTest {
        seedFile("хорошая", "тело", january, january)
        File(root, "notes/2026/01/broken.md").writeText("this is not a note")

        val summary = reconciler().reconcile().getOrThrow()

        assertEquals(1, summary.imported)
        assertEquals(1, summary.skipped)
    }
}

package ai.passioncode.fabricvr.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ai.passioncode.fabricvr.notes.NotesRepository
import java.io.File
import ai.passioncode.fabricvr.notes.RoomNotesRepository
import ai.passioncode.fabricvr.notes.db.NotesDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.notes.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mirror is the one seam between the index and the files the person owns, and until this test
 * existed nothing drove it: `MarkdownVaultTest` calls the vault directly with the right arguments,
 * so it could not see the mirror passing the wrong ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultMirrorTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: NotesDatabase
    private lateinit var repo: NotesRepository
    private lateinit var vault: FileVault
    private lateinit var root: java.io.File

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RoomNotesRepository(db.noteDao())
        root = temp.newFolder("vault")
    }

    /** The vault's IO dispatcher is injected so the test scheduler can actually wait for a write. */
    private fun TestScope.vaultOnTestDispatcher(): FileVault =
        FileVault(root, io = UnconfinedTestDispatcher(testScheduler)).also { vault = it }

    /**
     * And the journal's, for the same reason and one layer down: since `H5` the mirror writes a
     * deletion down **before** attempting it, so a journal left on `Dispatchers.IO` puts a real
     * thread between `delete()` and the vault — `advanceUntilIdle()` then returns before the
     * removal has run and the assertion reads a file that is about to go.
     */
    private fun TestScope.mirrorOnTestDispatcher(v: Vault): VaultMirror =
        VaultMirror(repo, v, RemovalJournal(root, io = UnconfinedTestDispatcher(testScheduler)))

    @After fun tearDown() = db.close()

    /**
     * Present **as a note**, which since `T-006` is not the same as present under the vault root:
     * `remove` moves a note's files into `.trash/` rather than unlinking them, so Undo can put
     * them back. A helper that walked the whole root would call a trashed note a live one.
     */
    private fun fileExists(id: String): Boolean =
        File(vault.root, "notes").walkTopDown().any { it.isFile && it.nameWithoutExtension == id }

    private fun trashed(id: String): Boolean =
        File(vault.root, ".trash").walkTopDown().any { it.isFile && it.nameWithoutExtension == id }

    @Test
    fun `a note deleted a month after it was created leaves no file in the vault`() = runTest {
        mirrorOnTestDispatcher(vaultOnTestDispatcher()).start(
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )

        val fortyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(40)
        val note = NotesRepository.newNote("old thought", "written last month", now = fortyDaysAgo)
        repo.upsert(note).getOrThrow()
        advanceUntilIdle()
        assertTrue("the mirror never wrote the file", fileExists(note.id))

        repo.delete(note.id).getOrThrow()
        advanceUntilIdle()

        assertFalse(
            "the vault still holds a deleted note — the folder was computed from today, not from createdAt",
            fileExists(note.id),
        )
        assertTrue(
            "the note was unlinked rather than moved aside, so Undo has nothing to restore",
            trashed(note.id),
        )
    }

    @Test
    fun `a note saved before the mirror subscribes is still mirrored`() = runTest {
        val note = NotesRepository.newNote("early", "written before the mirror started")
        repo.upsert(note).getOrThrow()
        advanceUntilIdle()

        mirrorOnTestDispatcher(vaultOnTestDispatcher()).start(
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertTrue(
            "the change was dropped because nobody was listening yet",
            fileExists(note.id),
        )
    }

    /**
     * `I-06`. `_failures` was four unsynchronised read-modify-writes — `value = value + x` — and
     * the two writers are real: the mirror's collector runs on the scope it was started with, and
     * Settings' *Retry* calls `retryFailed()` from another. A lost update erases a failure the
     * mirror recorded microseconds earlier, so `vaultOutOfSync` under-reports and **a note that
     * never reached the vault looks mirrored**.
     *
     * Real threads, not a test dispatcher: the defect is a lost update between two threads and a
     * single-threaded scheduler cannot produce one. A hundred concurrent failures either all
     * arrive or the count says how many were dropped.
     */
    @Test fun `concurrent write failures all reach the failure map`() = runTest {
        val failing = object : Vault by FileVault(root, io = Dispatchers.IO) {
            override suspend fun write(note: Note): Result<java.io.File> =
                Result.failure(VaultException(AppError.Storage("vault.write", java.io.IOException("full"))))
        }
        val mirror = VaultMirror(repo, failing, RemovalJournal(root, io = Dispatchers.IO))
        val notes = (1..100).map { NotesRepository.newNote("n$it", "тело $it") }

        withContext(Dispatchers.Default) {
            notes.map { note -> async { mirror.mirrorForTest(note) } }.awaitAll()
        }

        assertEquals(
            "failures were lost to a race between two threads writing the same map",
            100,
            mirror.failures.value.size,
        )
    }

    /**
     * `B-209`. **An `IOException` in the removal journal took the mirror and the process with it.**
     *
     * `remove` calls `journal.record` before the attempt and `journal.clear` after it, and neither
     * was guarded. `RemovalJournal.persist` goes through `writeFileAtomically`, which throws on a
     * full disk — and the throw left `remove`, left the `collect`, and left the bare
     * `scope.launch` the collector runs in. `Graph.scope` carries a `SupervisorJob` and **no**
     * `CoroutineExceptionHandler`, so on the headset that is the process's uncaught route: a crash
     * with no sentence attached, and a mirror that is dead for whatever is left of the process.
     * Every note written after it looks mirrored and is not.
     *
     * **A directory where the journal's file belongs is the one `IOException` a JVM test can
     * produce**, and it is the same one: `writeFileAtomically` refuses it deliberately (see its
     * KDoc), so `record` throws exactly as it does when the disk is full.
     *
     * The assertion is not that the removal succeeded — it did not, and a journal that cannot be
     * written is a real loss. It is that the loss is bounded to the deletion it belongs to.
     */
    @Test fun `a journal that cannot be written leaves the mirror writing later notes`() = runTest {
        val v = vaultOnTestDispatcher()
        File(root, ".pending-removals").mkdirs()
        mirrorOnTestDispatcher(v).start(
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )

        val doomed = NotesRepository.newNote("к удалению", "тело")
        repo.upsert(doomed).getOrThrow()
        advanceUntilIdle()
        assertTrue("the mirror never wrote the note this test is about to delete", fileExists(doomed.id))

        repo.delete(doomed.id).getOrThrow()
        advanceUntilIdle()

        // The two guards are separable and this is what separates them. A guard only around the
        // collector would catch the same throw one frame later — after it had already abandoned
        // `remove` — so the file the person deleted would still be in their vault, with nothing
        // but a log line about a journal. The journal records work to redo; it does not gate it.
        assertFalse(
            "a journal that could not be written stopped the deletion it was only meant to record",
            fileExists(doomed.id),
        )

        val later = NotesRepository.newNote("после сбоя", "написана после того, как журнал отказал")
        repo.upsert(later).getOrThrow()
        advanceUntilIdle()

        assertTrue(
            "the journal's IOException killed the mirror's collector — every later note looks " +
                "mirrored and is not",
            fileExists(later.id),
        )
    }

    /**
     * **A removal that failed is void once the note is written again** (`B-242`).
     *
     * `write` cleared `pending` and never `owed`, so a failed removal followed by *Undo* — which
     * re-writes the same note — stayed owed, and Settings' *Retry* then trashed the restored note's
     * files: the person's note was back in the list and gone from their vault, purged a week later.
     */
    @Test fun `Retry after a failed removal and an undo leaves the restored note in the vault`() = runTest {
        val real = vaultOnTestDispatcher()
        var failRemovals = 1
        val flaky = object : Vault by real {
            override suspend fun remove(id: String, createdAt: Long): Result<Unit> =
                if (failRemovals-- > 0) Result.failure(VaultException(AppError.Storage("vault.remove", java.io.IOException("busy"))))
                else real.remove(id, createdAt)
        }
        val journal = RemovalJournal(root, io = UnconfinedTestDispatcher(testScheduler))
        val mirror = VaultMirror(repo, flaky, journal)
        mirror.start(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

        val note = NotesRepository.newNote("вернули", "тело")
        repo.upsert(note).getOrThrow()
        advanceUntilIdle()
        repo.delete(note.id).getOrThrow()
        advanceUntilIdle()
        assertTrue("the fixture's removal did not fail", fileExists(note.id))

        // Undo: the same note, written back.
        repo.upsert(note).getOrThrow()
        advanceUntilIdle()

        mirror.retryFailed()
        advanceUntilIdle()

        assertTrue("Retry trashed a note the person had restored", fileExists(note.id))
        assertFalse(trashed(note.id))
        assertTrue("the journal still owes a removal for a live note", journal.pending().none { it.id == note.id })
    }

    /**
     * **A note written while its files sit in the trash gets them back** (`B-241`).
     *
     * A quick *Undo* restores from the trash before the mirror has put anything there, fails, and
     * writes the note back; the mirror then processes the delete it was still holding and trashes
     * the recording — purged a week later, from a note the person had taken back. The mirror sees
     * the delete and the re-write in order, so it is the one place that can repair it.
     */
    @Test fun `a note written back while its recording is in the trash gets the recording back`() = runTest {
        val v = vaultOnTestDispatcher()
        mirrorOnTestDispatcher(v).start(
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        val scratch = File(temp.root, "scratch.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val base = NotesRepository.newNote("с записью", "тело")
        val note = base.copy(audioPath = v.adoptAudio(base, scratch).getOrThrow().absolutePath)
        repo.upsert(note).getOrThrow()
        advanceUntilIdle()

        repo.delete(note.id).getOrThrow()
        advanceUntilIdle()
        assertTrue("the fixture's delete did not trash the recording", trashed(note.id))

        // Undo, the way `NotesViewModel.undoDelete` writes it when its own restore came too early.
        repo.upsert(note).getOrThrow()
        advanceUntilIdle()

        assertTrue("the restored note's recording stayed in the trash", File(note.audioPath!!).isFile)
        assertFalse("the trash still holds a live note's files", trashed(note.id))
    }
}

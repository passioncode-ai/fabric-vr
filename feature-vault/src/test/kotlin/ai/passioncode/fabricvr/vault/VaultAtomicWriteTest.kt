package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.notes.NotesRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `M2`. **A note was rewritten in place every 600 ms.**
 *
 * `Vault.write` called `writeText` on the live file, and the editor autosaves on that cadence, so
 * every save opened the person's only copy for truncation. A headset that loses power mid-write —
 * or a process the system kills between the truncate and the last byte — leaves a half-written
 * `.md`, which is not a corrupt copy of a note but **no note at all**: `MarkdownSerializer.parse`
 * refuses it and the recovery path this vault exists for counts it as skipped.
 *
 * The fix is the oldest one there is: write a sibling, `fsync` it, rename over the target. Rename
 * within a directory is atomic on every filesystem Android ships, so a reader sees the old file or
 * the new one and never a prefix of either.
 *
 * **The instant that matters cannot be reached from a JVM test** — it is a power cut between two
 * syscalls. What can be reached is the same ORDER: the commit step is injected, so a commit that
 * throws stands in for the machine stopping there, and the assertion is about what survives it.
 */
class VaultAtomicWriteTest {

    @get:Rule val temp = TemporaryFolder()

    private val january = 1_767_225_600_000L

    private fun vault(
        root: File,
        commit: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ) = FileVault(root, io = Dispatchers.Unconfined, commit = commit)

    private fun temps(root: File): List<File> =
        File(root, "notes").walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()

    /**
     * The whole point, stated as the failure it prevents: the previous version of the note is
     * still readable after a write that did not finish.
     */
    @Test fun `a write that dies before the rename leaves the old note intact`() = runTest {
        val root = temp.newFolder("vault")
        val note = NotesRepository.newNote("заметка", "первая версия", now = january)
        vault(root).write(note).getOrThrow()
        val file = vault(root).pathFor(note)

        val dying = vault(root) { _, _ -> throw IOException("the headset stopped here") }
        val outcome = dying.write(note.copy(body = "вторая версия"))

        assertTrue("a failed write reported success", outcome.isFailure)
        assertTrue(
            "the person's only copy was truncated by a write that never finished",
            file.readText().contains("первая версия"),
        )
    }

    /** And the scratch file a crash leaves behind is gone the next time the note is written. */
    @Test fun `the next write clears the scratch file the interrupted one left`() = runTest {
        val root = temp.newFolder("vault")
        val note = NotesRepository.newNote("заметка", "первая версия", now = january)
        vault(root).write(note).getOrThrow()
        vault(root) { _, _ -> throw IOException("stopped") }.write(note.copy(body = "вторая"))
        assertEquals("the interrupted write left nothing to clear — this test proves nothing", 1, temps(root).size)

        vault(root).write(note.copy(body = "третья версия")).getOrThrow()

        assertEquals("a scratch file outlived the write that replaced it", emptyList<File>(), temps(root))
        assertTrue(vault(root).pathFor(note).readText().contains("третья версия"))
    }

    /** An ordinary write leaves no trace of how it was made. */
    @Test fun `a successful write leaves no scratch file`() = runTest {
        val root = temp.newFolder("vault")
        vault(root).write(NotesRepository.newNote("заметка", "тело", now = january)).getOrThrow()

        assertEquals(emptyList<File>(), temps(root))
    }

    /**
     * **A directory where the note belongs is a failure, not a thing to delete.**
     *
     * Both obvious ways of committing a scratch file will destroy one on some platform:
     * `rename(2)` gives `EISDIR` on Linux and **succeeds** against an empty directory on Darwin
     * — measured here on 2026-09-21, `renameTo` returned `true` and the directory was gone — and
     * `File.copyTo(overwrite = true)` deletes it outright. The old in-place `writeText` failed
     * everywhere, by accident of opening the target directly, and that accident was load-bearing
     * for what Settings shows: this is the shape a full disk and a corrupt tree both take, and
     * the person is told the vault is out of sync because the write reported a failure.
     */
    @Test fun `a directory standing where the note belongs fails the write rather than deleting it`() = runTest {
        val root = temp.newFolder("vault")
        val note = NotesRepository.newNote("заметка", "тело", now = january)
        val blocked = vault(root).pathFor(note).apply { mkdirs() }

        val outcome = vault(root).write(note)

        assertTrue("a write over a directory reported success", outcome.isFailure)
        assertTrue("the write deleted a directory it should have refused", blocked.isDirectory)
        assertEquals(emptyList<File>(), temps(root))
    }

    /**
     * **Rename can refuse**, and a refusal is not a reason to lose the note. `adoptAudio` already
     * carries the same fallback for the same reason; this one is narrower, because the source and
     * the target are siblings, so the only refusals left are a filesystem that forbids it.
     */
    @Test fun `a filesystem that will not rename still stores the note`() = runTest {
        val root = temp.newFolder("vault")
        val note = NotesRepository.newNote("заметка", "тело", now = january)

        vault(root) { _, _ -> false }.write(note).getOrThrow()

        assertTrue(vault(root).pathFor(note).readText().contains("тело"))
        assertEquals("the fallback left its scratch file behind", emptyList<File>(), temps(root))
    }

    /**
     * `B-210`. **Two writers of one note shared one scratch file.**
     *
     * The scratch is always `<id>.md.tmp` beside the target, and nothing serialised two writes of
     * the same note: `VaultMirror`'s collector writes on the application scope, `retryFailed`
     * writes **outside** its own mutex, and `VaultReconciler.reconcile` writes at startup — so two
     * `FileOutputStream`s could stand on one path and a rename commit whatever was left of them.
     *
     * **Driven through the injected commit step rather than by timing**, for the reason the rest of
     * this class is: the first writer is stopped at the rename — the one instant where its scratch
     * file is complete and still on disk — and the second is released exactly there.
     *
     * - *Without the lock:* the second writer truncates the first one's scratch, writes its own
     *   bytes over it and renames it away. The first writer's rename then finds nothing, its
     *   copy-instead fallback opens a file that is gone, and its `Result` is a **failure** — the
     *   note it was asked to save was silently not saved.
     * - *With the lock:* the second writer cannot enter at all, so the wait below always runs out.
     *   That timeout is the assertion rather than a sleep: it is how long this test is willing to
     *   be wrong about the lock holding.
     */
    @Test fun `two concurrent writes of one note do not share a scratch file`() {
        val root = temp.newFolder("vault")
        val note = NotesRepository.newNote("заметка", "первая версия", now = january)
        val firstWriter = AtomicBoolean(true)
        val atRename = CountDownLatch(1)
        val secondDone = CountDownLatch(1)

        val v = FileVault(root, io = Dispatchers.Unconfined) { from, to ->
            if (firstWriter.compareAndSet(true, false)) {
                atRename.countDown()
                secondDone.await(1, TimeUnit.SECONDS)
            }
            from.renameTo(to)
        }

        val outcomes = arrayOfNulls<Result<File>>(2)
        val first = thread { outcomes[0] = runBlocking { v.write(note.copy(body = "первая версия")) } }
        assertTrue("the first writer never reached its rename", atRename.await(5, TimeUnit.SECONDS))
        val second = thread {
            outcomes[1] = runBlocking { v.write(note.copy(body = "вторая версия")) }
            secondDone.countDown()
        }
        first.join(10_000)
        second.join(10_000)

        assertTrue(
            "one of two concurrent writes of the same note was lost to the other: ${outcomes.toList()}",
            outcomes.all { it != null && it.isSuccess },
        )
        val stored = v.pathFor(note).readText()
        assertTrue(
            "the committed file is a mixture rather than exactly one of the two versions",
            stored.contains("первая версия") xor stored.contains("вторая версия"),
        )
        assertEquals("a scratch file outlived both writes", emptyList<File>(), temps(root))
    }
}

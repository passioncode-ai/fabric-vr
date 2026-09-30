package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.notes.Note
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Deleting a note must be reversible for as long as *Undo* is on screen offering to reverse it.
 *
 * The invariant: **only the vault deletes vault files, and the vault's delete is reversible for a
 * bounded time.** Before this, `remove` unlinked and `undoDelete` restored only the database row,
 * so a note came back pointing at a recording that was gone and *Transcribe again* answered with a
 * `FileNotFoundException`. The audio is the expensive artefact — it is what makes re-running on a
 * better model possible at all.
 */
class VaultTrashTest {

    @get:Rule val temp = TemporaryFolder()

    private val note = Note(
        id = "7f3c",
        title = "Panel size",
        body = "keep it under 2064x2208",
        createdAt = 1_758_240_000_000,
        updatedAt = 1_758_240_600_000,
    )

    private fun vault(now: () -> Long = { 1_758_240_600_000 }) =
        FileVault(temp.newFolder("vault"), io = Dispatchers.Unconfined, now = now)

    private suspend fun writeWithAudio(v: Vault): ByteArray {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val scratch = File(temp.newFolder(), "rec.wav").apply { writeBytes(bytes) }
        v.write(note).getOrThrow()
        v.adoptAudio(note, scratch).getOrThrow()
        return bytes
    }

    @Test fun `remove moves the note and its recording into the trash`() = runTest {
        val v = vault()
        writeWithAudio(v)

        v.remove(note.id, note.createdAt).getOrThrow()

        assertFalse("the note is still where it was", v.pathFor(note).exists())
        assertFalse("the recording is still where it was", v.audioPathFor(note).exists())
        assertTrue("the note was unlinked rather than moved aside", v.trashedFiles(note.id).isNotEmpty())
        assertEquals(
            "both artefacts should be in the trash",
            setOf("${note.id}.md", "${note.id}.wav"),
            v.trashedFiles(note.id).map { it.name }.toSet(),
        )
    }

    @Test fun `restore puts both back where the note expects them`() = runTest {
        val v = vault()
        val bytes = writeWithAudio(v)
        v.remove(note.id, note.createdAt).getOrThrow()

        v.restore(note.id, note.createdAt).getOrThrow()

        assertTrue("the note did not come back", v.pathFor(note).exists())
        assertTrue("the recording did not come back", v.audioPathFor(note).exists())
        assertTrue("the recording came back changed", v.audioPathFor(note).readBytes().contentEquals(bytes))
        assertTrue("the trash still holds a copy", v.trashedFiles(note.id).isEmpty())
    }

    @Test fun `restore reports honestly when the trash no longer holds it`() = runTest {
        val v = vault()
        writeWithAudio(v)
        v.remove(note.id, note.createdAt).getOrThrow()
        v.purgeTrash(olderThanMillis = 0).getOrThrow()

        val result = v.restore(note.id, note.createdAt)

        assertTrue("a restore of a purged note must not report success", result.isFailure)
        val error = (result.exceptionOrNull() as VaultException).error
        assertTrue("the failure must be a Storage one the mapper knows", error is AppError.Storage)
        assertFalse(v.pathFor(note).exists())
    }

    @Test fun `the sweep removes only what is past the retention`() = runTest {
        var clock = 1_000_000L
        val v = vault(now = { clock })
        writeWithAudio(v)
        v.remove(note.id, note.createdAt).getOrThrow()

        val recent = note.copy(id = "beef")
        v.write(recent).getOrThrow()
        clock += 10_000
        v.remove(recent.id, recent.createdAt).getOrThrow()

        // Sweep everything trashed more than 5 s ago: the first goes, the second stays.
        clock += 1
        v.purgeTrash(olderThanMillis = 5_000).getOrThrow()

        assertTrue("the old one survived the sweep", v.trashedFiles(note.id).isEmpty())
        assertTrue("the fresh one was swept too early", v.trashedFiles(recent.id).isNotEmpty())
    }

    @Test fun `removing a note the vault never held is not a failure`() = runTest {
        val v = vault()

        // A note whose mirror write failed was never on disk. Reporting that as an error makes
        // every such delete surface a message about a file the person never had.
        val result = v.remove("never-written", note.createdAt)

        assertTrue("deleting nothing should not be an error: ${result.exceptionOrNull()}", result.isSuccess)
    }
}

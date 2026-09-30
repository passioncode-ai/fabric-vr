package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.notes.Note
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import ai.passioncode.fabricvr.notes.NotesRepository
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
 * A dictation must exist in exactly one place. The recorder writes it to the app's private scratch
 * because the note does not exist yet; once it does, the recording moves into that note's folder and
 * the scratch copy goes. Two copies on a headset whose storage the person cannot browse is storage
 * they can never reclaim.
 */
class VaultAudioTest {

    @get:Rule val temp = TemporaryFolder()

    private val note = Note(
        id = "7f3c",
        title = "Panel size",
        body = "",
        createdAt = 1_758_240_000_000,
        updatedAt = 1_758_240_000_000,
    )

    private fun vault() = FileVault(temp.newFolder("vault"), io = Dispatchers.Unconfined)

    private val january = 1_767_225_600_000L   // 2026-01-01
    private val march = 1_772_496_000_000L     // 2026-03-03

    @Test fun `adopting a recording moves it into the note folder and leaves no scratch copy`() = runTest {
        val vault = vault()
        val scratch = File(temp.newFolder("audio"), "rec-1.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val landed = vault.adoptAudio(note, scratch).getOrThrow()

        assertEquals(vault.audioPathFor(note).absolutePath, landed.absolutePath)
        assertTrue("the recording did not arrive", landed.exists())
        assertEquals(3, landed.length())
        assertFalse("the scratch copy survived", scratch.exists())
    }

    @Test fun `adopting a recording that already lives in the vault is a no-op`() = runTest {
        val vault = vault()
        val target = vault.audioPathFor(note).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(9)) }

        val landed = vault.adoptAudio(note, target).getOrThrow()

        assertEquals(target.absolutePath, landed.absolutePath)
        assertEquals("the file was copied onto itself", 1, landed.length())
    }

    @Test fun `adopting a recording that is not there fails instead of inventing a path`() = runTest {
        val vault = vault()
        val missing = File(temp.root, "gone.wav")

        val result = vault.adoptAudio(note, missing)

        assertTrue("a missing recording must not report success", result.isFailure)
        assertFalse(vault.audioPathFor(note).exists())
    }

    @Test fun `a note whose audio already sits in the vault is written without copying it again`() = runTest {
        val vault = vault()
        val target = vault.audioPathFor(note).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4, 5)) }

        vault.write(note.copy(audioPath = target.absolutePath)).getOrThrow()

        assertEquals("write truncated the audio it was meant to keep", 2, target.length())
    }

    // --- T-024: the voice archive is disclosed, bounded and removable ------------------------

    /**
     * `G-02`. A `.wav` of every dictation is written into the vault and kept for ever, and **no
     * string anywhere in the product mentions that audio exists** — the one vault sentence talks
     * about Markdown, which is about 0.1% of what is stored. 16-bit mono at 16 kHz is 32 kB a
     * second: twenty half-minute dictations a day is 19.2 MB a day and **7 GB a year**. The app
     * has no number anywhere to diagnose that with.
     */
    @Test fun `the vault reports how many recordings it holds and how many bytes`() = runTest {
        val v = vault()
        seedAudio(v, "январь", january, bytes = 1_000)
        seedAudio(v, "январь-2", january, bytes = 2_000)
        seedAudio(v, "март", march, bytes = 4_000)

        val usage = v.audioUsage().getOrThrow()

        assertEquals("recordings across two month folders were not all counted", 3, usage.count)
        assertEquals(7_000L, usage.bytes)
    }

    /**
     * `remove` deletes the recording **and** the note's Markdown in one call, so "delete the
     * recording" had to mean "delete the note". These are two different intentions.
     */
    @Test fun `removing a recording leaves the note's markdown alone`() = runTest {
        val v = vault()
        val note = seedAudio(v, "со звуком", january, bytes = 500)

        v.removeAudio(note).getOrThrow()

        assertFalse("the recording is still on the headset", v.audioPathFor(note).exists())
        val markdown = v.pathFor(note).readText()
        assertNotNull("the note went with its recording", MarkdownSerializer.parse(markdown))
    }

    @Test fun `removing recordings older than a cutoff keeps the newer ones`() = runTest {
        val v = vault()
        val old = seedAudio(v, "старая", january, bytes = 100)
        val recent = seedAudio(v, "свежая", march, bytes = 100)
        check(v.audioPathFor(old).setLastModified(1_000L))
        check(v.audioPathFor(recent).setLastModified(9_000L))

        val swept = v.sweepAudio(cutoff = 5_000L).getOrThrow()

        assertEquals(1, swept.count)
        assertFalse(v.audioPathFor(old).exists())
        assertTrue("the sweep took a recording newer than its cutoff", v.audioPathFor(recent).exists())
    }

    /**
     * **The sweep never touches `.trash`**, and this is the correction the plan re-audit made to
     * this task. `remove` moves a note's files with `renameTo`, which **preserves mtime** — so a
     * recording deleted yesterday, whose file was written a hundred days ago, would be destroyed
     * by a ninety-day cutoff **inside its own seven-day undo window**. The trash has its own
     * retention and its own owner.
     */
    @Test fun `the sweep never takes anything out of the trash`() = runTest {
        val v = vault()
        val note = seedAudio(v, "удалённая", january, bytes = 100)
        v.remove(note.id, note.createdAt).getOrThrow()
        val trashed = v.trashedFiles(note.id).filter { it.name.endsWith(".wav") }
        check(trashed.isNotEmpty()) { "the fixture did not put a recording in the trash" }
        trashed.forEach { check(it.setLastModified(1_000L)) }

        val swept = v.sweepAudio(cutoff = 9_999_999L).getOrThrow()

        assertEquals("the sweep reached into the trash", 0, swept.count)
        assertTrue("a recording inside its undo window was destroyed", trashed.all { it.exists() })
    }

    @Test fun `a vault with no recordings sweeps and measures to zero`() = runTest {
        val v = vault()
        v.write(NotesRepository.newNote("без звука", "тело", now = january)).getOrThrow()

        assertEquals(AudioUsage(0, 0L), v.audioUsage().getOrThrow())
        assertEquals(AudioUsage(0, 0L), v.sweepAudio(cutoff = Long.MAX_VALUE).getOrThrow())
    }

    /** Which notes were swept, so the caller can clear their `audioPath`. */
    @Test fun `the sweep names the notes it cleared`() = runTest {
        val v = vault()
        val note = seedAudio(v, "старая", january, bytes = 100)
        check(v.audioPathFor(note).setLastModified(1_000L))

        val swept = v.sweepAudio(cutoff = 5_000L).getOrThrow()

        assertEquals(
            "without the ids, every swept note keeps a Transcribe-again button that fails",
            setOf(note.id),
            swept.noteIds,
        )
    }

    private suspend fun seedAudio(v: FileVault, title: String, createdAt: Long, bytes: Int): Note {
        val note = NotesRepository.newNote(title, "тело", now = createdAt)
        v.write(note).getOrThrow()
        v.audioPathFor(note).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }
        return note
    }

    /**
     * `E-21`. This function was in `:app`'s `ui` package — vault policy in a screen package,
     * where `:feature-vault`'s own tests could not reach it. The move is ten lines and two
     * imports; this is the test that could not be written before it.
     *
     * The behaviour it pins is the one that matters: **a failed adoption keeps the scratch
     * path.** The recording is still on the disk, the mirror copies it on its next pass, and
     * returning null would tell the person a note has no audio when it has.
     */
    /**
     * A recording that is still there but could not be moved keeps its own path — the mirror copies
     * it on its next pass. Since `B-256` this is the ONLY fallback: a source that is gone answers
     * null (the case below), where it used to answer its own dangling path.
     */
    @Test fun `adoptOrKeep returns the original path when adoption fails and the recording is there`() = runTest {
        val v = vault()
        val note = NotesRepository.newNote("не переехала", "тело", now = january)
        val kept = File(temp.root, "still-here.wav").apply { writeBytes(byteArrayOf(1, 2)) }
        val refusing = object : Vault by v {
            override suspend fun adoptAudio(note: Note, source: File): Result<File> =
                Result.failure(VaultException(ai.passioncode.fabricvr.common.AppError.Storage("vault.audio")))
        }

        val landed = refusing.adoptOrKeep(note, kept.absolutePath)

        assertEquals("a failed adoption reported the note as having no recording", kept.absolutePath, landed)
    }

    @Test fun `adoptOrKeep answers null for a note with no recording`() = runTest {
        val v = vault()
        val note = NotesRepository.newNote("без звука", "тело", now = january)
        v.write(note).getOrThrow()

        assertNull(v.adoptOrKeep(note, null))
    }

    @Test fun `adoptOrKeep moves a real recording into the note's folder`() = runTest {
        val v = vault()
        val note = NotesRepository.newNote("со звуком", "тело", now = january)
        v.write(note).getOrThrow()
        val scratch = File(temp.root, "scratch.wav").apply { writeBytes(ByteArray(32)) }

        val landed = v.adoptOrKeep(note, scratch.absolutePath)

        assertEquals(v.audioPathFor(note).absolutePath, landed)
        assertFalse("the scratch copy was left behind", scratch.exists())
    }

    // ---- B-238: a second recording never destroys the first -------------------------------

    private fun scratch(name: String, vararg bytes: Byte) =
        File(temp.root, "scratch-$name").apply { writeBytes(bytes) }

    /**
     * **A second dictation into an open note destroyed the first recording** (`B-238`,
     * `DEC-0090`). `adoptAudio` targets `<id>.wav` and ran `target.delete()` before the rename, so
     * the recording `DEC-0022` keeps for fidelity went the moment the person pressed *Record*
     * again in the editor.
     */
    @Test fun `adopting a second recording keeps the first beside it`() = runTest {
        val vault = vault()
        vault.adoptAudio(note, scratch("1", 1)).getOrThrow()
        val landed = vault.adoptAudio(note, scratch("2", 2, 2)).getOrThrow()

        assertEquals("the newest recording is the note's", 2, landed.length())
        val earlier = landed.parentFile!!.listFiles()!!.filter { it.name.startsWith("${note.id}~") }
        assertEquals("the first recording was destroyed", 1, earlier.size)
        assertEquals(1, earlier.single().length())
        assertTrue(earlier.single().name.endsWith(".wav"))
    }

    @Test fun `removing a note carries its earlier recordings into the trash and back`() = runTest {
        val vault = vault()
        vault.write(note).getOrThrow()
        vault.adoptAudio(note, scratch("1", 1)).getOrThrow()
        vault.adoptAudio(note, scratch("2", 2)).getOrThrow()

        vault.remove(note.id, note.createdAt).getOrThrow()
        val left = vault.pathFor(note).parentFile!!.listFiles().orEmpty().filter { it.name.startsWith(note.id) }
        assertEquals("an earlier recording was orphaned by the delete: $left", emptyList<File>(), left)
        assertEquals(3, vault.trashedFiles(note.id).size)

        vault.restore(note.id, note.createdAt).getOrThrow()
        val back = vault.pathFor(note).parentFile!!.listFiles().orEmpty().filter { it.name.startsWith(note.id) }
        assertEquals("undo did not bring the earlier recording back", 3, back.size)
    }

    @Test fun `removing a note's recording removes its earlier ones too`() = runTest {
        val vault = vault()
        vault.adoptAudio(note, scratch("1", 1)).getOrThrow()
        vault.adoptAudio(note, scratch("2", 2)).getOrThrow()

        vault.removeAudio(note).getOrThrow()

        val left = vault.audioPathFor(note).parentFile!!.listFiles().orEmpty().filter { it.name.endsWith(".wav") }
        assertEquals("a recording outlived *Delete recording*", emptyList<File>(), left)
    }

    @Test fun `the sweep does not name a note whose earlier recording it took`() = runTest {
        val vault = vault()
        vault.adoptAudio(note, scratch("1", 1)).getOrThrow()
        vault.adoptAudio(note, scratch("2", 2)).getOrThrow()
        val current = vault.audioPathFor(note)
        val earlier = current.parentFile!!.listFiles()!!.single { it.name.startsWith("${note.id}~") }
        earlier.setLastModified(1_000L)

        val swept = vault.sweepAudio(cutoff = 2_000L).getOrThrow()

        assertEquals(1, swept.count)
        assertTrue("the note's current recording went with an earlier one", current.isFile)
        assertFalse("the sweep named the note, so its row would lose a recording it still has", note.id in swept.noteIds)
    }

    /**
     * **`write` never copies an outside recording over a different one** (`B-257`). When
     * `adoptAudio`'s aside-rename fails, `adoptOrKeep` keeps the scratch path and the mirror's
     * `write` then copied it over `<id>.wav` with `overwrite = true` — the earlier recording
     * `DEC-0090` exists to keep.
     */
    @Test fun `writing a note whose recording is outside the vault keeps a different one already there`() = runTest {
        val vault = vault()
        vault.adoptAudio(note, scratch("a", 1, 1, 1)).getOrThrow()
        val outside = scratch("b", 2, 2)

        vault.write(note.copy(audioPath = outside.absolutePath)).getOrThrow()

        val folder = vault.audioPathFor(note).parentFile!!
        assertEquals("the new recording is not the note's", 2, vault.audioPathFor(note).length())
        val earlier = folder.listFiles()!!.filter { it.name.startsWith("${note.id}~") }
        assertEquals("the recording already there was overwritten", listOf(3L), earlier.map { it.length() })
    }

    @Test fun `writing the same outside recording twice keeps one copy aside, not two`() = runTest {
        val vault = vault()
        vault.adoptAudio(note, scratch("a", 1, 1, 1)).getOrThrow()
        val outside = scratch("b", 2, 2)

        vault.write(note.copy(audioPath = outside.absolutePath)).getOrThrow()
        vault.write(note.copy(audioPath = outside.absolutePath)).getOrThrow()

        val earlier = vault.audioPathFor(note).parentFile!!.listFiles()!!.filter { it.name.startsWith("${note.id}~") }
        assertEquals("every rewrite of the note moved an identical file aside", 1, earlier.size)
    }

    /**
     * Identical recordings longer than one comparison window stay identical across a block
     * boundary and a short last block. A regression case for the block compare, not a planted
     * defect: the whole-buffer version it replaced was also correct here, because a short last
     * block's stale tail is the previous block's, which already compared equal.
     */
    @Test fun `an identical recording longer than one compare window is not moved aside`() = runTest {
        val vault = vault()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        vault.adoptAudio(note, File(temp.root, "big-a").apply { writeBytes(bytes) }).getOrThrow()
        val outside = File(temp.root, "big-b").apply { writeBytes(bytes) }

        vault.write(note.copy(audioPath = outside.absolutePath)).getOrThrow()

        val earlier = vault.audioPathFor(note).parentFile!!.listFiles()!!.filter { it.name.startsWith("${note.id}~") }
        assertEquals("an identical 200 kB recording was treated as a different one", 0, earlier.size)
    }

    /**
     * **A note lists every recording it keeps, newest first** (`B-254`). Since `DEC-0090` a second
     * dictation keeps the first as `<id>~<stamp>.wav`, and nothing listed it: the note played only
     * the newest and the earlier ones were reachable only through the vault or an export.
     */
    @Test fun `a note lists its recordings newest first`() = runTest {
        val vault = vault()
        vault.adoptAudio(note, scratch("1", 1)).getOrThrow()
        val earlier = vault.audioPathFor(note).apply { setLastModified(1_000_000L) }
        vault.adoptAudio(note, scratch("2", 2, 2)).getOrThrow()
        val older = File(earlier.parentFile, "${note.id}~500000.wav").apply {
            writeBytes(byteArrayOf(3))
            // A restore changed mtime: ordering must follow the preserved timestamp.
            setLastModified(9_000_000L)
        }

        val listed = vault.recordingsOf(note)

        assertEquals("the current recording is not first", vault.audioPathFor(note), listed.first())
        assertEquals(3, listed.size)
        assertEquals("${note.id}~1000000.wav", listed[1].name)
        assertEquals(older, listed[2])
        assertTrue(earlier.name.endsWith(".wav"))
    }

    @Test fun `a note with no recording lists none`() = runTest {
        assertEquals(emptyList<File>(), vault().recordingsOf(note))
    }

    /**
     * **`adoptOrKeep` never answers a path to nothing** (`B-256`, seam verification). Its fallback
     * kept the original path when adoption failed — right when the scratch file is still there, and
     * a dangling path when it is not. All three callers (the dictation commit, the editor's append,
     * the recording-only note) share this answer now.
     */
    @Test fun `adoptOrKeep answers null for a recording that is gone`() = runTest {
        val v = vault()
        val gone = File(temp.root, "moved-away.wav").absolutePath
        assertNull(v.adoptOrKeep(note, gone))
        assertNull("an already-adopted path must also exist", v.adoptOrKeep(note, v.audioPathFor(note).absolutePath))
    }
}

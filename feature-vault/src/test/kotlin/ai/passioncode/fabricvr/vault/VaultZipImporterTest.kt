package ai.passioncode.fabricvr.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.RoomNotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.notes.db.NotesDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `H4`. **The export had no way back.**
 *
 * `VaultImporter`'s own comment called the exported zip *"a genuine restore path: unzip it back
 * into `filesDir/vault` and launch with an empty database"* — and nothing in the tree could
 * unzip anything. `filesDir` is app-private and `run-as` does not work against a release build,
 * so the sentence described an operation **nobody could perform**: the archive was a backup with
 * no restore, which is a copy of your notes you are not able to use.
 *
 * The zip comes from somewhere the person chose, so every entry in it is untrusted input. Three
 * rules, and each of them is a way a zip can write outside the directory it is unpacked into:
 * `..` in a path, an absolute path, and a name that resolves outside the root after
 * normalisation — the last being why the canonical-path check exists even though the first two
 * already forbid it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultZipImporterTest {

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

    private fun importer(): VaultZipImporter {
        val v = vault()
        return VaultZipImporter(
            v,
            VaultReconciler(v, repo, VaultImporter(v, repo, Dispatchers.Unconfined),
                RemovalJournal(root, io = Dispatchers.Unconfined), Dispatchers.Unconfined),
            Dispatchers.Unconfined,
        )
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(sink.toByteArray())
    }

    private fun aNote(): Note = NotesRepository.newNote("встреча", "о чём договорились", now = january)
        .copy(
            transcript = Transcript(
                text = "о чём договорились на встрече",
                language = "ru",
                source = SttSource.REMOTE,
                engine = "whisper-small",
                durationMs = 42_000,
            ),
        )

    // ---- the round trip ----------------------------------------------------------------------

    /**
     * Export, destroy both sides, restore: the note comes back whole, including the two dates
     * that `M3` used to overwrite and the recording that is most of what the vault weighs.
     */
    @Test fun `a note survives export, a wiped headset, and import`() = runTest {
        val stored = repo.upsert(aNote()).getOrThrow()
        vault().write(stored).getOrThrow()
        vault().audioPathFor(stored).apply { parentFile?.mkdirs(); writeBytes(ByteArray(128) { it.toByte() }) }

        val archive = ByteArrayOutputStream()
        val exported = VaultExporter(vault(), Dispatchers.Unconfined)
            .writeTo(archive, includeAudio = true).getOrThrow()
        assertEquals("the fixture exported no note", 1, exported.noteFiles)
        assertEquals("the fixture exported no recording", 1, exported.audioFiles)

        // The reinstall: app-private storage gone, database gone.
        File(root, "notes").deleteRecursively()
        repo.delete(stored.id).getOrThrow()
        assertEquals(0, repo.count())

        val summary = importer().import(ByteArrayInputStream(archive.toByteArray())).getOrThrow()

        assertEquals(ImportSummary(imported = 1, skipped = 0, refused = 0), summary)
        val back = repo.get(stored.id)
        assertNotNull("the note did not come back", back)
        assertEquals(stored.id, back!!.id)
        assertEquals("the creation date was rewritten by the restore", stored.createdAt, back.createdAt)
        assertEquals("every restored note came back \"updated today\"", stored.updatedAt, back.updatedAt)
        assertEquals(stored.title, back.title)
        assertEquals(stored.body, back.body)
        assertEquals(stored.transcript?.text, back.transcript?.text)
        assertEquals(stored.transcript?.language, back.transcript?.language)
        assertEquals(stored.transcript?.engine, back.transcript?.engine)
        assertEquals(stored.transcript?.durationMs, back.transcript?.durationMs)
        assertEquals(
            "the recording did not come back beside the note",
            vault().audioPathFor(stored).absolutePath,
            back.audioPath,
        )
        assertEquals(128, File(back.audioPath!!).length().toInt())
    }

    // ---- what is refused ---------------------------------------------------------------------

    /** Zip-slip, in its plainest form. The target is outside the vault and outside `filesDir`. */
    @Test fun `an entry that climbs out of the vault is refused`() = runTest {
        val escape = File(root.parentFile, "etc").absolutePath

        val summary = importer().import(
            zipOf("notes/../../etc/passwd" to "root:x:0:0".toByteArray()),
        ).getOrThrow()

        assertEquals(1, summary.refused)
        assertEquals(0, summary.imported)
        assertFalse("a zip wrote outside the vault", File("$escape/passwd").exists())
    }

    /** And its other form, which the `..` rule alone does not catch. */
    @Test fun `an absolute entry path is refused`() = runTest {
        val summary = importer().import(
            zipOf("/etc/passwd" to "root:x:0:0".toByteArray()),
        ).getOrThrow()

        assertEquals(1, summary.refused)
    }

    /**
     * **A filename that is not a UUID is refused**, because the filename is the note's identity:
     * the reconciler decides what to import by id, and `remove` finds a note's files by id. An
     * archive naming a file `notes/2026/01/report.md` would create a note whose id is `report`
     * and whose second import is a collision.
     */
    @Test fun `a file whose name is not a UUID is refused`() = runTest {
        val summary = importer().import(
            zipOf("notes/2026/01/report.md" to "---\nid: report\n---\n\nтело".toByteArray()),
        ).getOrThrow()

        assertEquals(1, summary.refused)
        assertEquals(0, summary.imported)
        assertFalse(File(root, "notes/2026/01/report.md").exists())
    }

    /** An earlier recording (`DEC-0090`) is part of the note's archive and comes back with it. */
    @Test fun `an earlier recording of a note is accepted`() = runTest {
        val id = java.util.UUID.randomUUID()
        val summary = importer().import(
            zipOf("notes/2026/01/$id~1767225600000.wav" to byteArrayOf(1, 2)),
        ).getOrThrow()

        assertEquals(0, summary.refused)
        assertTrue(File(root, "notes/2026/01/$id~1767225600000.wav").isFile)
    }

    @Test fun `an earlier-recording name on a markdown file is refused`() = runTest {
        val id = java.util.UUID.randomUUID()
        val summary = importer().import(zipOf("notes/2026/01/$id~1.md" to "x".toByteArray())).getOrThrow()
        assertEquals(1, summary.refused)
    }

    /** Anything that is not a note is not this importer's to unpack — the crash log included. */
    @Test fun `an entry outside the notes directory is refused`() = runTest {
        val summary = importer().import(zipOf("crashes.log" to "boom".toByteArray())).getOrThrow()

        assertEquals(1, summary.refused)
        assertFalse(File(root, "crashes.log").exists())
        assertFalse(File(root, "notes/crashes.log").exists())
    }

    // ---- what is counted ---------------------------------------------------------------------

    /**
     * A truncated `.md` is what `M2`'s in-place write left behind, and what half a download
     * leaves. It is counted and the rest of the archive still lands: the person is restoring
     * from a disaster and a restore that gives up on the first bad file is not one.
     */
    @Test fun `a truncated note is counted as skipped and the rest are imported`() = runTest {
        val good = aNote()
        vault().write(good).getOrThrow()
        val archive = ByteArrayOutputStream()
        VaultExporter(vault(), Dispatchers.Unconfined).writeTo(archive, includeAudio = false).getOrThrow()
        File(root, "notes").deleteRecursively()

        val truncated = "---\nid: 11111111-2222-4333-8444-555555555555\ntitle: обрыв"
        val summary = importer().import(
            zipOf(
                *java.util.zip.ZipInputStream(ByteArrayInputStream(archive.toByteArray())).use { zip ->
                    generateSequence { zip.nextEntry }
                        .map { it.name to zip.readBytes() }
                        .toList()
                        .toTypedArray()
                },
                "notes/2026/01/11111111-2222-4333-8444-555555555555.md" to truncated.toByteArray(),
            ),
        ).getOrThrow()

        assertEquals("the broken file stopped the restore", 1, summary.imported)
        assertEquals("the file that could not be parsed was not counted", 1, summary.skipped)
        assertEquals(0, summary.refused)
    }

    /**
     * **A note already in the vault is left alone.** The on-device file is the live one; the
     * archive's copy is by definition older, and overwriting it would roll the note back to
     * whenever the export was taken — silently, during an operation the person asked for to
     * *avoid* losing work.
     */
    @Test fun `an archive does not overwrite a note the vault already holds`() = runTest {
        val note = aNote()
        val stored = repo.upsert(note).getOrThrow()
        vault().write(stored).getOrThrow()
        val archive = ByteArrayOutputStream()
        VaultExporter(vault(), Dispatchers.Unconfined).writeTo(archive, includeAudio = false).getOrThrow()
        val edited = repo.upsert(stored.copy(body = "то, что я дописал сегодня")).getOrThrow()
        vault().write(edited).getOrThrow()

        val summary = importer().import(ByteArrayInputStream(archive.toByteArray())).getOrThrow()

        assertEquals(0, summary.imported)
        assertEquals("the archive was unpacked over the live note", 1, summary.skipped)
        assertEquals("today's words were rolled back by a restore", "то, что я дописал сегодня", repo.get(stored.id)!!.body)
        assertTrue(vault().pathFor(stored).readText().contains("то, что я дописал сегодня"))
    }

    // ---- B-185: the archive may not be larger than the room there is ---------------------------

    /** Counts every byte the importer hands to the filesystem, across all entries. */
    private class CountingSink {
        var bytes = 0L
        fun open(target: File): java.io.OutputStream = object : java.io.FilterOutputStream(
            java.io.FileOutputStream(target),
        ) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                bytes += len
                out.write(b, off, len)
            }
        }
    }

    /** The importer with a fixed answer for how much room the device has. */
    private fun importerWith(free: Long, sink: CountingSink = CountingSink()): VaultZipImporter {
        val v = vault()
        return VaultZipImporter(
            v,
            VaultReconciler(v, repo, VaultImporter(v, repo, Dispatchers.Unconfined),
                RemovalJournal(root, io = Dispatchers.Unconfined), Dispatchers.Unconfined),
            Dispatchers.Unconfined,
            freeBytes = { free },
            openSink = sink::open,
        )
    }

    private fun wavName() = "notes/2026/01/${java.util.UUID.randomUUID()}.wav"

    /**
     * **`B-185`. The path rules refuse zip-slip and said nothing about volume.**
     *
     * An archive that cannot be written outside the vault can still be written *into* it until
     * `filesDir` is full — and a full `filesDir` is how the vault stops being writable, which is
     * the one failure the vault exists to prevent. The person restoring from a disaster then has
     * neither their backup nor a working app.
     *
     * **The threshold is not invented.** It is the probe `ModelDownloader` already refuses a
     * download with, at the same ten-per-cent margin (`SPACE_MARGIN_NUM`/`DEN`), so there is one
     * answer on this device to "is there room for this" rather than a new constant nobody decided.
     */
    @Test fun `an archive bigger than the free space is refused`() = runTest {
        // 256 KB of audio against 64 KB of room. Deflated, so the archive declares no size at all
        // and only the running total can stop it.
        val sink = CountingSink()
        val result = importerWith(free = 64 * 1024, sink = sink)
            .import(zipOf(wavName() to ByteArray(256 * 1024)))

        assertTrue("an archive larger than the disk was accepted", result.isFailure)
        assertTrue(
            "the importer wrote ${sink.bytes} bytes into a vault with only 64 KB of room",
            sink.bytes <= 64 * 1024,
        )
        val left = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals("the refusal left a half-written recording behind", 0L, left)
    }

    /**
     * **A declared size is refused before a single byte is written**, when the archive declares
     * one at all.
     *
     * Measured on the JDK this project builds with, 2026-09-22: an entry written by
     * `ZipOutputStream` the ordinary (DEFLATED) way reports `getSize() == -1` from
     * `ZipInputStream`, because the real size lives in a data descriptor *after* the data. Only a
     * `STORED` entry carries it in the local header. So the declared size is a cheap early
     * refusal and **cannot be the only bound** — which is why the test above, where nothing is
     * declared, is the one that matters.
     */
    @Test fun `a declared size beyond the free space is refused before the bytes arrive`() = runTest {
        val sink = ByteArrayOutputStream()
        val body = ByteArray(256 * 1024)
        ZipOutputStream(sink).use { zip ->
            val entry = ZipEntry(wavName()).apply {
                method = ZipEntry.STORED
                size = body.size.toLong()
                compressedSize = body.size.toLong()
                crc = java.util.zip.CRC32().apply { update(body) }.value
            }
            zip.putNextEntry(entry)
            zip.write(body)
            zip.closeEntry()
        }

        val counted = CountingSink()
        val result = importerWith(free = 64 * 1024, sink = counted)
            .import(ByteArrayInputStream(sink.toByteArray()))

        assertTrue("a declared size larger than the disk was accepted", result.isFailure)
        // **This is the assertion the running total cannot satisfy.** Without the early refusal
        // the importer happily writes a full budget's worth — 58 KB here — and only then notices;
        // with it, the header alone is enough and nothing reaches the disk at all.
        assertEquals(
            "bytes were written for an entry whose own header said it would not fit",
            0L,
            counted.bytes,
        )
    }

    /**
     * **The canary.** A bound that refuses everything passes both tests above and is useless. A
     * real export, restored onto a device with room for it, must still come back.
     */
    @Test fun `an archive that fits is still imported`() = runTest {
        val note = aNote()
        val stored = repo.upsert(note).getOrThrow()
        vault().write(stored).getOrThrow()
        val archive = ByteArrayOutputStream()
        VaultExporter(vault(), Dispatchers.Unconfined).writeTo(archive, includeAudio = false).getOrThrow()
        repo.delete(stored.id).getOrThrow()
        vault().remove(stored.id, stored.createdAt).getOrThrow()

        val summary = importerWith(free = 64L * 1024 * 1024)
            .import(ByteArrayInputStream(archive.toByteArray())).getOrThrow()

        assertEquals("a note that fits was refused by the size bound", 1, summary.imported)
        assertNotNull(repo.get(stored.id))
    }
}

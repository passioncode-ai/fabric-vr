package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.common.AppError

import ai.passioncode.fabricvr.notes.NotesRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The vault is app-private internal storage and `android:allowBackup="false"`, so until this class
 * existed there was no way at all to get a person's notes off a headset — and `adb uninstall`,
 * which a signature change forces, takes the whole of `filesDir` with it.
 */
class VaultExportTest {

    @get:Rule val temp = TemporaryFolder()

    private fun vault(): FileVault = FileVault(temp.newFolder("vault"), io = Dispatchers.Unconfined)

    private suspend fun seed(v: FileVault, title: String, createdAt: Long): String {
        val note = NotesRepository.newNote(title, "тело $title", now = createdAt)
        v.write(note).getOrThrow()
        return note.id
    }

    private fun entries(bytes: ByteArray): List<String> = buildList {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) { add(e.name); zip.closeEntry(); e = zip.nextEntry }
        }
    }

    private fun entryBytes(bytes: ByteArray, name: String): ByteArray? {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == name) return zip.readBytes()
                zip.closeEntry(); e = zip.nextEntry
            }
        }
        return null
    }

    private val january = 1_767_225_600_000L   // 2026-01-01
    private val march = 1_772_496_000_000L     // 2026-03-03

    @Test fun `every markdown file in the vault is in the zip`() = runTest {
        val v = vault()
        val a = seed(v, "первая", january)
        val b = seed(v, "вторая", january)
        val c = seed(v, "третья", march)
        val out = ByteArrayOutputStream()

        val summary = VaultExporter(v, Dispatchers.Unconfined).writeTo(out, includeAudio = true).getOrThrow()

        val names = entries(out.toByteArray())
        assertEquals("not every note reached the archive", 3, summary.noteFiles)
        listOf(a, b, c).forEach { id ->
            assertTrue("$id is missing from the zip: $names", names.any { it.endsWith("$id.md") })
        }
        assertTrue("entry names are not vault-relative: $names", names.all { it.startsWith("notes/") })
    }

    @Test fun `notes-only export omits the wav files`() = runTest {
        val v = vault()
        val id = seed(v, "со звуком", january)
        v.audioPathFor(v.noteFor(id)).apply { parentFile?.mkdirs(); writeBytes(ByteArray(5_000)) }
        val out = ByteArrayOutputStream()

        val summary = VaultExporter(v, Dispatchers.Unconfined).writeTo(out, includeAudio = false).getOrThrow()

        assertEquals(0, summary.audioFiles)
        assertFalse(
            "a notes-only export carried the recordings, which are three orders of magnitude larger",
            entries(out.toByteArray()).any { it.endsWith(".wav") },
        )
    }

    /**
     * `T-006` made deletion a move into `.trash/`, a sibling of `notes/` under the same root. An
     * exporter that walked the root would hand the person an archive of notes they deleted — and
     * every other test here would stay green, because a fresh test vault has no trash at all.
     */
    @Test fun `a deleted note is not in the export`() = runTest {
        val v = vault()
        val kept = seed(v, "оставил", january)
        val gone = seed(v, "удалил", january)
        v.remove(gone, january).getOrThrow()
        assertTrue("the fixture did not put anything in the trash", v.trashedFiles(gone).isNotEmpty())
        val out = ByteArrayOutputStream()

        val summary = VaultExporter(v, Dispatchers.Unconfined).writeTo(out, includeAudio = true).getOrThrow()

        val names = entries(out.toByteArray())
        assertTrue("the kept note is missing", names.any { it.endsWith("$kept.md") })
        assertFalse("the export carried a note the person deleted: $names", names.any { it.contains(gone) })
        assertEquals(1, summary.noteFiles)
    }

    /**
     * A vault being mirrored into **while** it is exported is the normal case: the walk lists a
     * path and the file is unreadable a millisecond later. The file must be **listed and then
     * fail to read**, which is the only state that reaches the guard.
     *
     * A dangling symlink was the first attempt and it proved nothing: `walkTopDown`'s `isFile`
     * follows the link, so a broken one is never listed and removing the guard left this green.
     * Found by planting the defect, which is the only way that kind of hole is ever found.
     */
    @Test fun `a file that disappears mid-export does not abort the zip`() = runTest {
        val v = vault()
        val a = seed(v, "первая", january)
        val b = seed(v, "вторая", january)
        val unreadable = v.pathFor(v.noteFor("исчезающая", january))
        unreadable.parentFile?.mkdirs()
        unreadable.writeText("содержимое, которое уже не прочитать")
        check(unreadable.setReadable(false, false) && !unreadable.canRead()) {
            "the fixture could not make a file unreadable — running as root?"
        }
        val out = ByteArrayOutputStream()

        val summary = VaultExporter(v, Dispatchers.Unconfined)
            .writeTo(out, includeAudio = true)
            .getOrThrow()

        val names = entries(out.toByteArray())
        assertEquals("one unreadable file truncated the archive", 2, summary.noteFiles)
        listOf(a, b).forEach { id -> assertTrue("$id is missing", names.any { it.endsWith("$id.md") }) }
    }

    // ---- `B-189`: what the archive costs to build, and what it admits to leaving out ---------

    /** Counts what the exporter actually pulls, so a test can see the window rather than the heap. */
    private class CountingStream(
        private val delegate: java.io.InputStream,
        val reads: MutableList<Int>,
    ) : java.io.InputStream() {
        override fun read(): Int = delegate.read().also { if (it >= 0) reads += 1 }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it >= 0) reads += it }
        override fun close() = delegate.close()
    }

    /**
     * **The defect.** `readBytes()` per entry put the whole file in the heap at once. Recording
     * length is unbounded and there is no `largeHeap`: at 32 kB/s a one-hour `.wav` is about
     * 115 MB, and the `OutOfMemoryError` was caught, logged and dropped — so the archive looked
     * complete and was not.
     *
     * Asserted on the seam, not on the heap: a heap assertion is a flake on a different machine.
     * The buffer is made small on purpose so the count is exact — `readBytes()` would pull 8 kB
     * at a time whatever it is told, and reading the file whole would not call this stream at all.
     */
    @Test fun `an entry is streamed through a bounded buffer, never read whole`() = runTest {
        val v = vault()
        val id = seed(v, "долгая запись", january)
        val size = 100_000
        v.audioPathFor(v.noteFor(id)).apply { parentFile?.mkdirs(); writeBytes(ByteArray(size)) }
        val reads = mutableListOf<Int>()
        val window = 4_096
        val out = ByteArrayOutputStream()

        val summary = VaultExporter(
            v, Dispatchers.Unconfined,
            source = { file -> CountingStream(file.inputStream(), reads) },
            copyBufferBytes = window,
        ).writeTo(out, includeAudio = true).getOrThrow()

        assertEquals(1, summary.audioFiles)
        assertTrue("the exporter never opened the stream — the file was read whole", reads.isNotEmpty())
        assertTrue(
            "a single read pulled ${reads.maxOrNull()} bytes through a $window-byte window",
            reads.all { it <= window },
        )
        assertTrue(
            "${reads.size} read(s) for $size bytes — the recording was not chunked",
            reads.size >= size / window,
        )
        // Every byte in the archive came through that window, and nothing else did.
        assertEquals("the recording did not arrive whole", summary.bytes, reads.sum().toLong())
    }

    /**
     * The other half. `ExportSummary` had no skipped count, so the one sentence the exporter's own
     * comment forbids — *"a backup that looks complete and is not"* — was exactly what a caller
     * could say. Now the number is in the summary and the reason class beside it.
     */
    @Test fun `a file that cannot be read is counted, with its reason`() = runTest {
        val v = vault()
        val kept = seed(v, "читается", january)
        val unreadable = v.pathFor(v.noteFor("нечитаемая", january))
        unreadable.parentFile?.mkdirs()
        unreadable.writeText("содержимое, которое уже не прочитать")
        check(unreadable.setReadable(false, false) && !unreadable.canRead()) {
            "the fixture could not make a file unreadable — running as root?"
        }
        val out = ByteArrayOutputStream()

        val summary = VaultExporter(v, Dispatchers.Unconfined)
            .writeTo(out, includeAudio = true).getOrThrow()

        assertEquals(1, summary.noteFiles)
        assertEquals("the archive is short one file and does not say so", 1, summary.skipped)
        assertEquals(setOf("FileNotFoundException"), summary.skippedReasons)
        assertTrue(entries(out.toByteArray()).any { it.endsWith("$kept.md") })
    }

    /**
     * **A file that dies mid-copy fails the export** (`B-247`, `DEC-0093`). A zip cannot retract a
     * name it has already written, so the truncated entry stayed in the archive, counted as
     * skipped — and a later restore imported it as a whole note, or a whole recording, because
     * nothing in the archive said otherwise. A file that is gone before it is opened is still a
     * skip (the case above); one that fails once its bytes are flowing is a real read error, and
     * an archive that looks complete and is not is the one a person relies on before an uninstall.
     */
    @Test fun `an entry that fails mid-copy fails the export rather than shipping a truncated file`() = runTest {
        val v = vault()
        val id = seed(v, "обрывается", january)
        val out = ByteArrayOutputStream()

        val summary = VaultExporter(
            v, Dispatchers.Unconfined,
            source = { file ->
                object : java.io.InputStream() {
                    private var served = 0
                    override fun read(): Int = throw java.io.IOException("gone mid-copy")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (served > 0) throw java.io.IOException("gone mid-copy")
                        served = minOf(8, len)
                        file.inputStream().use { it.read(b, off, served) }
                        return served
                    }
                }
            },
            copyBufferBytes = 4_096,
        ).writeTo(out, includeAudio = false)

        assertTrue("an archive holding a truncated $id.md was reported as a backup: $summary", summary.isFailure)
        val error = (summary.exceptionOrNull() as? VaultException)?.error
        // `B-258`: the failure names what could not be read, and says it was a note.
        assertTrue("the failure does not say which file: $error", error is AppError.ExportUnreadable)
        assertEquals("$id.md", (error as AppError.ExportUnreadable).file)
        assertEquals("a note was reported as a recording", false, error.recording)
    }

    /**
     * **A recording that cannot be read names itself, so the person can export the notes without
     * it** (`B-258`). `DEC-0093` is right to refuse an archive that looks complete and is not — and
     * one `.wav` that fails the same way every time then makes *Notes and recordings* impossible.
     */
    @Test fun `a recording that fails mid-copy is named as a recording`() = runTest {
        val v = vault()
        val id = seed(v, "с записью", january)
        val note = v.noteFor(id)
        v.adoptAudio(note, File(temp.root, "rec.wav").apply { writeBytes(ByteArray(64)) }).getOrThrow()

        val result = VaultExporter(
            v, Dispatchers.Unconfined,
            source = { file ->
                if (!file.name.endsWith(".wav")) file.inputStream()
                else object : java.io.InputStream() {
                    private var served = false
                    override fun read(): Int = throw java.io.IOException("bad sector")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (served) throw java.io.IOException("bad sector")
                        served = true; b[off] = 1; return 1
                    }
                }
            },
        ).writeTo(ByteArrayOutputStream(), includeAudio = true)

        val error = (result.exceptionOrNull() as? VaultException)?.error as? AppError.ExportUnreadable
        assertEquals("$id.wav", error?.file)
        assertEquals(true, error?.recording)
    }

    @Test fun `the zip round-trips`() = runTest {
        val v = vault()
        val id = seed(v, "туда и обратно", january)
        val onDisk = v.pathFor(v.noteFor(id)).readBytes()
        val out = ByteArrayOutputStream()

        VaultExporter(v, Dispatchers.Unconfined).writeTo(out, includeAudio = true).getOrThrow()

        val name = entries(out.toByteArray()).first { it.endsWith("$id.md") }
        assertTrue(
            "the bytes that came back are not the bytes that went in",
            onDisk.contentEquals(entryBytes(out.toByteArray(), name)),
        )
    }

    /**
     * The vault addresses a note by id **and creation time** — the folder is `yyyy/MM` of
     * `createdAt`. Passing the wrong time silently addresses a different folder, which is how the
     * first version of `a file that disappears mid-export` deleted nothing and then failed with a
     * count of three.
     */
    private fun FileVault.noteFor(id: String, createdAt: Long = january) =
        NotesRepository.newNote("", "", now = createdAt).copy(id = id)
    /**
     * `DEC-0026` deferred the crash log to whichever export existed first rather than inventing a
     * second route off the headset. `T-011` is that log, and this is the join: the one archive a
     * person takes before reinstalling carries the diagnosis too.
     */
    @Test fun `an extra file rides along in the archive`() = runTest {
        val v = vault()
        seed(v, "заметка", january)
        val crash = File(temp.root, "crashes.log").apply { writeText("--- 8< ---\nerror   boom\n") }
        val out = ByteArrayOutputStream()

        VaultExporter(v, Dispatchers.Unconfined, extras = { mapOf("crashes.log" to crash) })
            .writeTo(out, includeAudio = false).getOrThrow()

        val names = entries(out.toByteArray())
        assertTrue("the crash report did not travel with the notes: $names", names.contains("crashes.log"))
        assertTrue(
            "the report arrived empty",
            String(entryBytes(out.toByteArray(), "crashes.log")!!).contains("boom"),
        )
    }

    @Test fun `an extra file that is not there does not break the archive`() = runTest {
        val v = vault()
        val id = seed(v, "заметка", january)
        val out = ByteArrayOutputStream()

        val summary = VaultExporter(
            v, Dispatchers.Unconfined,
            extras = { mapOf("crashes.log" to File(temp.root, "never-existed.log")) },
        ).writeTo(out, includeAudio = false).getOrThrow()

        assertEquals(1, summary.noteFiles)
        assertTrue(entries(out.toByteArray()).any { it.endsWith("$id.md") })
    }

    /**
     * **A crash log that fails mid-copy does not cost the person their backup** (seam verification
     * of `DEC-0093`). The extras are diagnostics, not notes: a truncated `crashes.log` is harmless —
     * the importer refuses anything that is not a note — while failing the whole export over it would
     * leave a person with no archive before an uninstall.
     */
    @Test fun `an extra that fails mid-copy is skipped, not fatal`() = runTest {
        val v = vault()
        seed(v, "уцелеет", january)
        val log = File(temp.root, "crashes.log").apply { writeText("строка лога\n".repeat(1_000)) }
        val out = ByteArrayOutputStream()

        val summary = VaultExporter(
            v, Dispatchers.Unconfined,
            extras = { mapOf("crashes.log" to log) },
            source = { file ->
                if (file.name != "crashes.log") file.inputStream()
                else object : java.io.InputStream() {
                    private var served = false
                    override fun read(): Int = throw java.io.IOException("gone mid-copy")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (served) throw java.io.IOException("gone mid-copy")
                        served = true
                        b[off] = 1
                        return 1
                    }
                }
            },
        ).writeTo(out, includeAudio = false).getOrThrow()

        assertEquals(1, summary.noteFiles)
        assertEquals("the failed crash log was not counted", 1, summary.skipped)
    }

    /**
     * **A sink that fails is not a file that cannot be read** (seam verification of `DEC-0097`).
     * The mid-copy catch took every `IOException` from `copyTo`, including the WRITE to MediaStore —
     * so a full disk read as *"a recording couldn't be read"*, and the offered notes-only export then
     * failed the same way, blaming a note.
     */
    @Test fun `a failing sink is a storage failure, not an unreadable file`() = runTest {
        val v = vault()
        val id = seed(v, "цела", january)
        // Incompressible and larger than every buffer between the copy and the sink, so the sink
        // fails DURING the recording's copy — which is the case that was misread.
        val noise = ByteArray(300_000).also { java.util.Random(7).nextBytes(it) }
        v.adoptAudio(v.noteFor(id), File(temp.root, "noise.wav").apply { writeBytes(noise) }).getOrThrow()
        val full = object : java.io.OutputStream() {
            private var written = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
                if (written > 50_000) throw java.io.IOException("ENOSPC")
            }
        }

        val result = VaultExporter(v, Dispatchers.Unconfined).writeTo(full, includeAudio = true)

        val error = (result.exceptionOrNull() as? VaultException)?.error
        assertTrue("a full disk was reported as a damaged file: $error", error is AppError.Storage)
    }
}

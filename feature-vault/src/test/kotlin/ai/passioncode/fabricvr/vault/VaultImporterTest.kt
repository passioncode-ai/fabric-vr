package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `CONTEXT.md` says the database is "an index over the vault, never the source of truth". Until
 * `VaultImporter` that was an aspiration: `MarkdownSerializer.parse` was called by nothing but its
 * own test, so a database that would not open meant lost notes with the Markdown still on disk.
 */
class VaultImporterTest {

    @get:Rule val temp = TemporaryFolder()

    private class FakeRepo(private val existing: MutableList<Note> = mutableListOf()) : NotesRepository {
        val imported = mutableListOf<Note>()
        var failOn: String? = null
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = null
        override suspend fun upsert(note: Note): Result<Note> {
            if (note.id == failOn) return Result.failure(IllegalStateException("disk full"))
            // **`NotesRepository.upsert`'s invariant, modelled** (`DEC-0058`): a note claiming a
            // day another note already holds gives up the day rather than destroying the holder.
            // This fake used to accept anything, which is how the importer's own test could stay
            // green over a path that destroyed a note.
            val demoted = note.dayKey
                ?.takeIf { key -> imported.any { it.dayKey == key && it.id != note.id } }
                ?.let { note.copy(dayKey = null) }
                ?: note
            imported.add(demoted); return Result.success(demoted)
        }
        // This fake records the note verbatim and stamps nothing, so both writes are the same
        // write here — spelled out rather than inherited, because the interface's default makes
        // "the same" the WRONG answer for any double that does stamp. `M3`'s real assertion is
        // in `VaultReconcilerTest`, against Room.
        override suspend fun upsertPreservingTimestamps(note: Note): Result<Note> = upsert(note)
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> = Result.success(NotesRepository.newNote())
        override suspend fun count(): Int = 0
        override suspend fun recent(limit: Int): List<Note> = existing.take(limit)
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    private fun vault() = FileVault(temp.newFolder("vault"), io = Dispatchers.Unconfined)

    private suspend fun seed(v: FileVault, title: String, body: String, createdAt: Long): Note {
        val note = NotesRepository.newNote(title, body, now = createdAt)
        v.write(note).getOrThrow()
        return note
    }

    private val january = 1_767_225_600_000L

    @Test fun `an empty database is rebuilt from the vault`() = runTest {
        val v = vault()
        val a = seed(v, "первая", "тело первой", january)
        val b = seed(v, "вторая", "тело второй", january)
        val repo = FakeRepo()

        val count = VaultImporter(v, repo, Dispatchers.Unconfined)
            .importMissing(known = emptySet()).getOrThrow().imported

        assertEquals(2, count)
        assertEquals(setOf(a.id, b.id), repo.imported.map { it.id }.toSet())
        assertEquals("тело первой", repo.imported.first { it.id == a.id }.body)
    }

    /**
     * **Never a merge, and the rule is now per note rather than per launch.**
     *
     * `importIfEmpty` asked `recent(1).isNotEmpty()` — *"is anything in the table"*, which is not
     * *"has this vault been imported"* — so an import killed after its first note was never
     * finished, on any later launch (`H6`). The reasoning it was protecting is intact and is
     * enforced one id at a time: a note the database already holds is not written over by a
     * file, ever.
     */
    @Test fun `a note the database already holds is not imported over`() = runTest {
        val v = vault()
        val existing = seed(v, "в хранилище", "тело", january)
        val repo = FakeRepo()

        val outcome = VaultImporter(v, repo, Dispatchers.Unconfined)
            .importMissing(known = setOf(existing.id)).getOrThrow()

        assertEquals(0, outcome.imported)
        assertTrue("the importer wrote over a note the database already had", repo.imported.isEmpty())
    }

    /** A deletion the vault still owes is not a note to import back (`M7`). */
    @Test fun `a note the vault is still to delete is not imported back`() = runTest {
        val v = vault()
        val doomed = seed(v, "удалённая", "тело", january)
        val repo = FakeRepo()

        val outcome = VaultImporter(v, repo, Dispatchers.Unconfined)
            .importMissing(known = emptySet(), skip = setOf(doomed.id)).getOrThrow()

        assertEquals("the note the person deleted came back as a fresh import", 0, outcome.imported)
    }

    @Test fun `an empty vault and an empty database is not an error`() = runTest {
        val outcome = VaultImporter(vault(), FakeRepo(), Dispatchers.Unconfined)
            .importMissing(known = emptySet()).getOrThrow()
        assertEquals(0, outcome.imported)
    }

    /**
     * One unreadable file must not cost the other forty notes. The person is recovering from a
     * failure; a recovery that gives up on the first bad file is not one.
     */
    @Test fun `a file that does not parse is skipped and the rest are imported`() = runTest {
        val v = vault()
        val good = seed(v, "хорошая", "тело", january)
        File(v.root, "notes/2026/01/broken.md").writeText("this is not a note")

        val outcome = VaultImporter(v, FakeRepo(), Dispatchers.Unconfined)
            .importMissing(known = emptySet()).getOrThrow()

        assertEquals("the unparseable file stopped the import", 1, outcome.imported)
        assertEquals("the file that could not be read was not counted", 1, outcome.skipped)
    }

    /**
     * The stored `audioPath` is an absolute path from whichever install wrote the note, and after
     * a reinstall — the situation this class exists for — it points into a `filesDir` that is
     * gone. The sibling `.wav` is where the recording actually is.
     */
    @Test fun `the recording is re-adopted from beside the note, not from the stored path`() = runTest {
        val v = vault()
        val note = NotesRepository.newNote("со звуком", "тело", now = january)
            .copy(audioPath = "/data/user/0/some.old.install/files/audio/gone.wav")
        v.write(note).getOrThrow()
        v.audioPathFor(note).apply { parentFile?.mkdirs(); writeBytes(ByteArray(64)) }
        val repo = FakeRepo()

        VaultImporter(v, repo, Dispatchers.Unconfined).importMissing(known = emptySet()).getOrThrow()

        val path = repo.imported.single().audioPath
        assertEquals("the note came back pointing at a previous install", v.audioPathFor(note).absolutePath, path)
    }

    @Test fun `a note whose recording is gone comes back without one`() = runTest {
        val v = vault()
        val note = NotesRepository.newNote("без звука", "тело", now = january)
            .copy(audioPath = "/data/user/0/some.old.install/files/audio/gone.wav")
        v.write(note).getOrThrow()
        val repo = FakeRepo()

        VaultImporter(v, repo, Dispatchers.Unconfined).importMissing(known = emptySet()).getOrThrow()

        assertNull("a dead absolute path was carried into the rebuilt database", repo.imported.single().audioPath)
    }

    /**
     * **Two vault files claiming one day both come back.**
     *
     * The importer is the recovery path — it exists for a reinstall, when the database is gone
     * and the files are all there is. It wrote whatever `day:` a file carried straight into
     * `upsert`, and `@Insert(REPLACE)` against the UNIQUE index on `dayKey` then destroyed the
     * note already holding that date: a person recovering from a disaster lost a note **to the
     * recovery**, silently, with the file still sitting on disk that the import had just read.
     *
     * `DEC-0035` had decided the rule and `NotesViewModel` implemented it, for its own call only.
     * `DEC-0058` moved it into `NotesRepository.upsert`, which is the door every caller uses.
     * Nothing in this class changed — which is the point of putting an invariant where the
     * invariant belongs.
     */
    @Test fun `two files claiming one day both survive the import`() = runTest {
        val v = vault()
        val first = NotesRepository.newNote("первая", "то, что я печатал весь день", now = january)
            .copy(dayKey = "2026-01-05")
        val second = NotesRepository.newNote("вторая", "другая запись того же дня", now = january)
            .copy(dayKey = "2026-01-05")
        v.write(first).getOrThrow()
        v.write(second).getOrThrow()
        val repo = FakeRepo()

        VaultImporter(v, repo, Dispatchers.Unconfined).importMissing(known = emptySet()).getOrThrow()

        assertEquals("a note was destroyed by the import that was recovering it", 2, repo.imported.size)
        assertEquals(
            "exactly one may hold the day",
            1,
            repo.imported.count { it.dayKey == "2026-01-05" },
        )
        assertEquals(
            "the demoted note lost its words, not only its day",
            setOf("то, что я печатал весь день", "другая запись того же дня"),
            repo.imported.map { it.body }.toSet(),
        )
    }

    /**
     * `B-218`. **Every launch read and parsed every note in the vault to find out it already had
     * them.**
     *
     * The loop did `readText()` and `MarkdownSerializer.parse` on every `.md` and only then asked
     * whether `parsed.id` was known — while the id is the file's **own name**, which
     * `Vault.pathFor` writes and `VaultZipImporter` refuses an archive for not spelling. On the
     * normal launch, where the database holds every note in the vault, that is the whole archive
     * read and parsed before the first paint, for an answer the directory listing already had.
     *
     * **Asserted through what a parse would have cost, not through a counter.** Every known file
     * here holds text no parser could accept: an importer that reads them reports them as skipped,
     * and one that never opens them reports nothing at all. A stubbed `MarkdownSerializer` would
     * have measured the stub.
     */
    @Test fun `a vault of known notes plus one unknown parses only the unknown`() = runTest {
        val v = vault()
        val known = (1..5).map { java.util.UUID.randomUUID().toString() }
        known.forEach { id ->
            File(v.root, "notes/2026/01/$id.md")
                .apply { parentFile?.mkdirs() }
                .writeText("это не заметка и распарсить это нельзя")
        }
        seed(v, "новая", "тело новой", january)
        val repo = FakeRepo()

        val outcome = VaultImporter(v, repo, Dispatchers.Unconfined)
            .importMissing(known = known.toSet()).getOrThrow()

        assertEquals("the one note that was not in the database did not arrive", 1, outcome.imported)
        assertEquals(
            "a note the database already holds was read and parsed before being discarded",
            0,
            outcome.skipped,
        )
    }

    /**
     * The guard on what taking the id from the file name trades away: a file the app did not name
     * is still a note. `VaultZipImporter` refuses a non-UUID name into the vault, and nothing
     * refuses one a person drops in with Obsidian — so the name is a **shortcut** past the parse
     * and never the rule about what a note is.
     */
    @Test fun `a file whose name is not an id is still imported by what it says`() = runTest {
        val v = vault()
        val note = NotesRepository.newNote("вручную", "положена в хранилище руками", now = january)
        File(v.root, "notes/2026/01/моя-мысль.md")
            .apply { parentFile?.mkdirs() }
            .writeText(MarkdownSerializer.toMarkdown(note))
        val repo = FakeRepo()

        val outcome = VaultImporter(v, repo, Dispatchers.Unconfined)
            .importMissing(known = emptySet()).getOrThrow()

        assertEquals("a note whose file the app did not name was dropped", 1, outcome.imported)
        assertEquals(note.id, repo.imported.single().id)
    }
}

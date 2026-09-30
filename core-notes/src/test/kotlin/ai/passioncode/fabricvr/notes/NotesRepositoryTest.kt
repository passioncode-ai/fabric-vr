package ai.passioncode.fabricvr.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ai.passioncode.fabricvr.notes.db.NotesDatabase
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotesRepositoryTest {

    private lateinit var db: NotesDatabase
    private lateinit var repo: NotesRepository

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RoomNotesRepository(db.noteDao())
    }

    @After fun tearDown() = db.close()

    @Test fun `a note survives a round trip`() = runTest {
        val note = NotesRepository.newNote("Panel size", "keep it under 2064x2208")
        repo.upsert(note).getOrThrow()

        val stored = repo.get(note.id)
        assertNotNull(stored)
        assertEquals("Panel size", stored!!.title)
        assertEquals(1, repo.observeNotes().first().size)
    }

    /**
     * `M4`. **The primary key was a search term.** `noteId` is a column of `note_fts` and FTS4
     * indexes every column unless told otherwise, so the UUID the app generated was indexed
     * alongside the person's words: `bead*`, `4d*` and `2026*` each returned a note whose entire
     * text was *"молоко и хлеб"* — measured with sqlite3 during the 2026-09-21 audit. Somebody
     * searching for a year, or for a two-letter prefix, got notes that do not contain it and no
     * way to see why. Schema v4 adds `notindexed=noteId`.
     *
     * The id is still **stored** — `NoteDao.search` joins on it, and dropping the column would
     * break every query in the DAO. It is stored and not indexed, which is exactly what the FTS4
     * option is for.
     */
    @Test fun `a fragment of a note's own id matches nothing while its words still match`() = runTest {
        val note = NotesRepository.newNote("", "молоко и хлеб").copy(id = "bead4d21-2026-4f00-9c31-000000000001")
        repo.upsert(note).getOrThrow()

        // Every prefix the audit found: the id's own leading token, a two-character fragment, and
        // the four digits that look like a year to anybody searching for one.
        listOf("bead", "4d", "2026", "9c31").forEach { fragment ->
            assertTrue(
                "the id is still indexed: \"$fragment\" matched a note whose text is \"молоко и хлеб\"",
                repo.search(fragment).first().isEmpty(),
            )
        }
        assertEquals(
            "the words stopped matching — the index was not rebuilt, it was emptied",
            1,
            repo.search("хлеб").first().size,
        )
    }

    @Test fun `deleting removes the note`() = runTest {
        val note = NotesRepository.newNote("gone", "soon")
        repo.upsert(note).getOrThrow()
        repo.delete(note.id).getOrThrow()

        assertNull(repo.get(note.id))
        assertTrue(repo.observeNotes().first().isEmpty())
    }

    @Test fun `tags are parsed from the body on save`() = runTest {
        val note = NotesRepository.newNote("t", "an #idea about #панели")
        val saved = repo.upsert(note).getOrThrow()

        assertEquals(setOf("idea", "панели"), saved.tags)
        assertEquals(listOf("idea", "панели"), repo.observeTags().first())
        assertEquals(1, repo.observeNotes(tag = "idea").first().size)
        assertTrue(repo.observeNotes(tag = "absent").first().isEmpty())
    }

    @Test fun `search finds a word that only appears in a transcript`() = runTest {
        val spoken = NotesRepository.newNote("voice", "").copy(
            transcript = Transcript("remember the kelvin measurement", "en", SttSource.LOCAL, "whisper", 1200),
        )
        repo.upsert(spoken).getOrThrow()
        repo.upsert(NotesRepository.newNote("other", "nothing relevant here")).getOrThrow()

        val hits = repo.search("kelvin").first()
        assertEquals(1, hits.size)
        assertEquals(spoken.id, hits.first().id)
    }

    @Test fun `a blank query matches nothing`() = runTest {
        repo.upsert(NotesRepository.newNote("a", "b")).getOrThrow()
        assertTrue(repo.search("   ").first().isEmpty())
    }

    @Test fun `the daily note is created once and then reused`() = runTest {
        val day = LocalDate.of(2026, 9, 19)
        val first = repo.dailyNote(day).getOrThrow()
        val second = repo.dailyNote(day).getOrThrow()

        assertEquals(first.id, second.id)
        assertEquals("2026-09-19", first.dayKey)
        assertEquals(1, repo.observeNotes().first().size)
    }

    @Test fun `removing a tag from the body removes it from the note`() = runTest {
        val note = repo.upsert(NotesRepository.newNote("t", "an #idea and a #plan")).getOrThrow()
        assertEquals(setOf("idea", "plan"), note.tags)

        val edited = repo.upsert(note.copy(body = "an #idea only")).getOrThrow()

        assertEquals("the removed tag must not survive the edit", setOf("idea"), edited.tags)
        assertEquals(listOf("idea"), repo.observeTags().first())
        assertTrue(repo.observeNotes(tag = "plan").first().isEmpty())
    }

    @Test fun `search matches a prefix and a cyrillic word`() = runTest {
        repo.upsert(NotesRepository.newNote("Panel geometry", "кривизна панели и разрешение")).getOrThrow()
        repo.upsert(NotesRepository.newNote("Other", "nothing")).getOrThrow()

        assertEquals(1, repo.search("разреш").first().size)
        assertEquals(1, repo.search("geom").first().size)
        assertEquals(1, repo.search("panel кривизна").first().size)
        assertTrue(repo.search("zzz").first().isEmpty())
    }

    /**
     * **The blocker.** Whisper capitalises the first word of every sentence and every proper
     * noun, so a dictated note reads `Разрешение экрана`, `Панель`, `Москва`. Room's default
     * FTS4 tokenizer is `simple`, which case-folds **ASCII only** — so the query has to carry the
     * same case as the indexed token, in both directions, and a person cannot know which of their
     * words Whisper capitalised. The failure is indistinguishable from "that note does not exist".
     *
     * Measured with sqlite3 3.51.0 before the fix: against a table holding `Разрешение`,
     * `MATCH 'разреш*'` returned 0 rows and `MATCH 'Разреш*'` returned 1. `G-04`, `E-20`.
     */
    @Test fun `search finds a capitalised russian word`() = runTest {
        // Exactly what whisper writes: the sentence opens with a capital.
        repo.upsert(NotesRepository.newNote("Панель", "Разрешение экрана и панель")).getOrThrow()

        assertEquals("lowercase query, capitalised token", 1, repo.search("разреш").first().size)
        assertEquals("capitalised query, lowercase token", 1, repo.search("Панель").first().size)
        assertEquals("both capitalised", 1, repo.search("Разреш").first().size)
        assertEquals("both lowercase", 1, repo.search("панель").first().size)
    }

    /**
     * **What a reload owes a fallback, and what it does not** (`B-089`).
     *
     * `SCN-007` step 3: *"the badge reads `local (fallback)`, with a one-line notice saying the
     * server did not answer."* Two different lifetimes are hiding in that sentence, and the board
     * row treated them as one.
     *
     * - **The badge is durable.** `transcriptSource` is a column, the editor renders it
     *   (`NoteEditorScreen.kt:314` via `transcriptSourceRes`), and a person reopening a week-old
     *   note is still told the words came from the headset rather than the service they chose.
     *   That is asserted below, and it is the half that must never regress.
     * - **The notice is not.** `Transcript.fallbackReason` is an `AppError`, a tree of throwables
     *   with no serialised form, and `DEC-0068` records deliberately that it does not survive
     *   process death because `Transcript.source` carries the fact that matters. `SCN-007` binds
     *   the notice to the act of dictating — every step reads *dictate →* — and asks for nothing
     *   after a reload.
     *
     * So this pins the decision rather than a database column: `B-089` asked for a schema change
     * that an accepted decision already refused, on a requirement that never wanted it. The
     * surface half — that the reason is rendered nowhere *live* — is real and is `B-151`.
     */
    @Test fun `a fallback still says it fell back after a reload`() = runTest {
        val note = NotesRepository.newNote("", "то, что я надиктовал").copy(
            transcript = Transcript(
                text = "то, что я надиктовал",
                language = "ru",
                source = SttSource.LOCAL_FALLBACK,
                engine = "whisper-small",
                durationMs = 4_200,
                fallbackReason = ai.passioncode.fabricvr.common.AppError.RemoteStt(status = 503),
            ),
        )
        repo.upsert(note).getOrThrow()

        val reloaded = repo.get(note.id)!!.transcript!!

        assertEquals(
            "the badge stopped surviving a reload — SCN-007's `local (fallback)` is now a lie",
            SttSource.LOCAL_FALLBACK,
            reloaded.source,
        )
        assertEquals("whisper-small", reloaded.engine)
        assertNull(
            "fallbackReason now survives a reload: that is DEC-0068 reversed, and it needs a " +
                "decision superseding it rather than a column appearing quietly",
            reloaded.fallbackReason,
        )
    }

    /**
     * **`ё` and `е` are one letter to a search box** (`B-132`).
     *
     * Whisper emits `ё` — it transcribes from sound, and the sound is there — while a person
     * typing into a search field emits `е`, because that is what Russian keyboards and Russian
     * writing habitually use. So the writer and the reader of the same note disagree about a
     * character neither of them chose, and the note simply does not come back.
     *
     * **No tokenizer argument fixes this.** Measured with sqlite3 3.51.0 on 2026-09-22 against an
     * FTS4 table holding `ёлка ещё`: `MATCH 'елка'` returns **0** under plain `unicode61`, 0 under
     * `remove_diacritics=1` and 0 under `remove_diacritics=2` — the most aggressive setting there
     * is. SQLite's diacritic table covers Latin; `ё` is U+0451, a precomposed letter with its own
     * codepoint, not `е` plus a combining mark. The fold has to happen in our code, on both sides.
     *
     * Case folding is *not* the same question and already works — `unicode61` folds `Ё`/`ё` — so
     * the only assertion here is about the two distinct letters.
     */
    @Test fun `a note dictated with ё is found by typing е`() = runTest {
        // Exactly what whisper writes.
        repo.upsert(NotesRepository.newNote("Ёлка", "ещё зелёная ёлка")).getOrThrow()

        assertEquals("typed е, dictated ё — the note did not come back", 1, repo.search("елка").first().size)
        assertEquals("typed ё, dictated ё", 1, repo.search("ёлка").first().size)
        assertEquals("a prefix typed with е", 1, repo.search("зелен").first().size)
        assertEquals("the word this row was named for", 1, repo.search("еще").first().size)
    }

    /** And the other direction: a note typed with `е`, searched with the `ё` whisper would emit. */
    @Test fun `a note typed with е is found by searching ё`() = runTest {
        repo.upsert(NotesRepository.newNote("", "елка и еще одна мысль")).getOrThrow()

        assertEquals("typed ё, stored е", 1, repo.search("ёлка").first().size)
        assertEquals(1, repo.search("ещё").first().size)
    }

    /**
     * **The fold stops at `ё`.** `й` and `и` are different letters — `мой` and `мои` are different
     * words — and a normaliser that reached one letter further would merge them. The row that
     * asked for this said so in its own text; this is what keeps it true.
     */
    @Test fun `й and и stay different letters`() = runTest {
        repo.upsert(NotesRepository.newNote("", "мой план")).getOrThrow()

        assertEquals("и matched й — the fold went one letter too far", 0, repo.search("мои").first().size)
        assertEquals(1, repo.search("мой").first().size)
    }

    /**
     * **Passes today, and is committed because it can be named.** The `LIKE` path is
     * case-insensitive for ASCII only — the same defect class as the tokenizer — and it is safe
     * here only because both sides are already lowercase: `TagParser` lowercases at parse time
     * and the tag comes from the stored blob. That is a property of two files agreeing, not of
     * the query, so it is pinned rather than trusted.
     */
    @Test fun `a capitalised cyrillic tag filters the list`() = runTest {
        repo.upsert(NotesRepository.newNote("", "мысль про #Идея")).getOrThrow()

        assertEquals(listOf("идея"), repo.observeTags().first())
        assertEquals(1, repo.observeNotes(tag = "идея").first().size)
    }

    /**
     * **A note claiming a day another note holds gives up the day. Nobody is destroyed.**
     *
     * `insertNote` is `@Insert(REPLACE)` against a UNIQUE index on `dayKey`, so writing a note
     * for a taken day silently deleted the holder (`A-24`), and `upsert` then removed only the
     * new note's index row, leaving an orphan the `JOIN` drops.
     *
     * `DEC-0035` decided the product half — the incoming note comes back **without** its
     * `dayKey`, keeps every word, and stops being *the* note for that date. That rule was
     * implemented in `NotesViewModel.undoDelete()`, which is **one caller**, so every other door
     * into `upsert` still destroyed the holder: `VaultImporter` writes whatever `day:` a file
     * carries, and importing two files for one date destroyed one of them — during the recovery
     * path, which is the worst possible moment (`B-088`).
     *
     * This test asserted the eviction until `DEC-0058`: *"the fixture did not actually evict
     * anything"*. It was right about the code and wrong about the rule, which is the expensive
     * kind of green — the same shape as `MIGRATION_1_2`'s own test, in the same change.
     */
    @Test fun `a note claiming a taken day gives up the day rather than destroying the holder`() = runTest {
        val day = LocalDate.of(2026, 9, 21)
        val first = repo.dailyNote(day).getOrThrow()
        repo.upsert(first.copy(body = "первая запись дня")).getOrThrow()

        val intruder = NotesRepository.newNote("подмена", "вторая").copy(dayKey = first.dayKey)
        val written = repo.upsert(intruder).getOrThrow()

        assertEquals("a note was destroyed by a day collision", 2, repo.observeNotes().first().size)
        assertNull("the incoming note must give up the day", written.dayKey)
        assertEquals(
            "the holder lost the day it already had",
            first.id,
            repo.noteForDay(first.dayKey!!)?.id,
        )
        assertEquals(
            "the holder lost its words",
            "первая запись дня",
            repo.get(first.id)?.body,
        )
        assertEquals("an index row outlived the note it points at", 0, orphanFtsRows())
        assertEquals(
            "a surviving note lost its index row, so a search will not find it",
            2,
            db.query("SELECT count(*) FROM note_fts", null).use { c -> c.moveToFirst(); c.getInt(0) },
        )
    }

    /**
     * The demotion is announced. A migration runs below the repository and cannot emit, which is
     * half of why `B-088` was invisible; this path **can**, so the note the vault mirror writes
     * carries `day:` removed rather than the stale value it was handed.
     */
    @Test fun `the demoted note is what reaches the change stream`() = runTest {
        val day = LocalDate.of(2026, 9, 21)
        val first = repo.dailyNote(day).getOrThrow()

        val seen = mutableListOf<NoteChange>()
        val collector = launch { repo.observeChanges().toList(seen) }
        runCurrent()

        repo.upsert(NotesRepository.newNote("подмена", "вторая").copy(dayKey = first.dayKey)).getOrThrow()
        runCurrent()
        collector.cancel()

        val upserted = seen.filterIsInstance<NoteChange.Upserted>().last()
        assertNull(
            "the mirror was told the note still holds a day it does not hold",
            upserted.note.dayKey,
        )
        assertTrue(
            "the holder was announced as deleted when nothing was deleted",
            seen.none { it is NoteChange.Deleted },
        )
    }

    private fun orphanFtsRows(): Int =
        db.query("SELECT count(*) FROM note_fts WHERE noteId NOT IN (SELECT id FROM notes)", null)
            .use { c -> c.moveToFirst(); c.getInt(0) }

    @Test fun `concurrent dailyNote calls leave exactly one note for the day`() = runTest {
        val day = LocalDate.of(2026, 9, 19)
        val results = (1..8).map { async(Dispatchers.Default) { repo.dailyNote(day).getOrThrow() } }.awaitAll()

        assertEquals("one row per day", 1, repo.observeNotes().first().size)
        assertEquals("all callers must see the same note", 1, results.map { it.id }.toSet().size)
    }

    @Test fun `a deletion carries the creation time the vault needs`() = runTest {
        val seen = mutableListOf<NoteChange>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeChanges().collect { seen.add(it) }
        }
        val note = repo.upsert(NotesRepository.newNote("t", "b", now = 1_700_000_000_000)).getOrThrow()
        repo.delete(note.id).getOrThrow()
        job.cancel()

        val deleted = seen.filterIsInstance<NoteChange.Deleted>().single()
        assertEquals(note.id, deleted.id)
        assertEquals(note.createdAt, deleted.createdAt)
    }

    @Test fun `changes are emitted for upsert and delete`() = runTest {
        val seen = mutableListOf<NoteChange>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeChanges().collect { seen.add(it) }
        }
        val note = NotesRepository.newNote("watch", "me")
        repo.upsert(note).getOrThrow()
        repo.delete(note.id).getOrThrow()
        job.cancel()

        assertTrue(seen.any { it is NoteChange.Upserted })
        assertTrue(seen.any { it is NoteChange.Deleted })
    }

    // --- T-026: the list and the tag index do not read the whole table -----------------------

    /**
     * `G-10`. `observeAll()` was `SELECT * FROM notes ORDER BY updatedAt DESC` with no `LIMIT`,
     * and Room invalidates on the whole table — so every write re-materialised every row. The
     * editor autosaves every 600 ms while somebody types.
     *
     * At the real note count this was not a wall, and the honest answer in the spec is "not yet".
     * A window is what makes the answer stay "not yet" as the base grows, and `count()` is what
     * lets the screen say how many are not shown rather than silently truncating.
     */
    @Test fun `the list is bounded and the total is knowable`() = runTest {
        repeat(250) { repo.upsert(NotesRepository.newNote("note $it", "body $it")).getOrThrow() }

        assertEquals(NotesRepository.DEFAULT_WINDOW, repo.observeNotes().first().size)
        assertEquals("the screen cannot say how many it is not showing", 250, repo.count())
    }

    /**
     * **The reason this task exists, and it is a consequence of `T-022`.** `search` builds a
     * prefix match — every term becomes `term*`. While the tokenizer was `simple` a one-letter
     * Russian query matched almost nothing, so the missing `LIMIT` was dormant. `unicode61`
     * makes `п*` match essentially every Russian note in the base, and `SearchViewModel` runs it
     * on a 120 ms debounce **while the person types**. `T-022` armed a live unbounded query.
     */
    @Test fun `a one-letter prefix search is bounded`() = runTest {
        repeat(250) { repo.upsert(NotesRepository.newNote("", "панель $it")).getOrThrow() }

        val hits = repo.search("п").first()

        assertEquals(
            "a single keystroke materialised the whole base",
            NotesRepository.DEFAULT_WINDOW,
            hits.size,
        )
    }

    /**
     * `E-12`. `observeTags()` runs a second full scan and is `combine`d with the list, so every
     * autosave emitted a second, identical state. The scan still runs — a normalised tag table is
     * schema v4 and belongs after the migration harness — but an unchanged list stops re-emitting.
     */
    @Test fun `the tag list does not re-emit when nothing about tags changed`() = runTest {
        val seen = mutableListOf<List<String>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { repo.observeTags().toList(seen) }

        repeat(3) { repo.upsert(NotesRepository.newNote("", "мысль про #идея $it")).getOrThrow() }

        job.cancel()
        // Not an exact count — when the collector attaches relative to the first write is a
        // scheduling detail, and pinning it would make this a test about the dispatcher. What
        // must be true is that three writes carrying the same tag do not produce three
        // identical emissions.
        assertEquals("an identical tag list was emitted twice in a row: $seen", seen.distinct(), seen)
        assertTrue("every write re-emitted the tag list: $seen", seen.size < 3)
        assertEquals(listOf("идея"), seen.last())
    }

    /** `observeByTag` stays unbounded on purpose; a single tag's notes are a subset by construction. */
    @Test fun `filtering by tag is not clipped by the window`() = runTest {
        repeat(3) { repo.upsert(NotesRepository.newNote("", "мысль #идея $it")).getOrThrow() }

        assertEquals(3, repo.observeNotes(tag = "идея").first().size)
    }

    /**
     * **Two writers claiming one fresh day, and neither may be destroyed.**
     *
     * The demotion started life as a read (`idHoldingDay`) followed by a write, with the two in
     * separate DAO calls — a check-then-act. Both writers read *nobody holds it*, both wrote, and
     * the second `REPLACE` evicted the first: `A-24`, reopened by the fix for `A-24`.
     *
     * Moving the rule out of `NotesViewModel` and into the repository is what made this reachable:
     * one caller on the main dispatcher cannot race itself, and the repository is entered from
     * `VaultImporter` on IO and from a dictation commit on `Graph.scope` at the same time. The
     * sibling `getOrCreateByDay` had the shape this needed all along — read and write inside one
     * `@Transaction`.
     *
     * Repeated, because a race that reproduces once in ten runs is a race that passes review.
     */
    @Test fun `two writers claiming one fresh day cannot destroy each other`() = runTest {
        repeat(24) { round ->
            val day = "2026-10-%02d".format(round + 1)
            val a = NotesRepository.newNote("a$round", "первая").copy(dayKey = day)
            val b = NotesRepository.newNote("b$round", "вторая").copy(dayKey = day)

            listOf(
                async(Dispatchers.Default) { repo.upsert(a) },
                async(Dispatchers.Default) { repo.upsert(b) },
            ).awaitAll()

            assertNotNull("round $round: the first writer was destroyed", repo.get(a.id))
            assertNotNull("round $round: the second writer was destroyed", repo.get(b.id))
            assertEquals(
                "round $round: two notes hold one day, which the UNIQUE index forbids",
                1,
                listOfNotNull(repo.get(a.id), repo.get(b.id)).count { it.dayKey == day },
            )
        }
    }
}

package ai.passioncode.fabricvr.notes.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every migration edge, executed.
 *
 * **Neither of this database's migrations had ever run in a test**, and the reason was one missing
 * line: `MigrationTestHelper` reads the exported schema from assets, and only the `androidTest`
 * source set had `schemas` on its asset path. `MIGRATION_1_2` deletes rows — it has to, because
 * the unique index it adds cannot be created over duplicates — and nothing had ever watched it
 * delete the right ones (`H-24`, `A-12`).
 *
 * **And `1.json` did not exist.** `exportSchema` was `false` when v1 shipped, so there was nothing
 * to migrate *from*. It is reconstructed here from `16a5fb5^` — the same entities with export
 * switched on, which reproduces the identity hash a v1 device actually carries, rather than a
 * hand-written file that would test a database no device ever had. Diffed against `2.json`: the
 * only differences are the version, the hash, and the absence of `index_notes_dayKey`.
 *
 * Without this, a device still on schema 1 meeting version 3 raises
 * `IllegalStateException: A migration from 1 to 3 was required but not found` on **every** launch,
 * with no way in and no way out but uninstalling — which destroys the notes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NotesDatabase::class.java,
    )

    private fun insertNote(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        body: String,
        dayKey: String?,
        updatedAt: Long,
    ) {
        db.execSQL(
            "INSERT INTO notes (id, title, body, tags, createdAt, updatedAt, dayKey, audioPath, " +
                "transcriptText, transcriptLanguage, transcriptSource, transcriptEngine, " +
                "transcriptDurationMs) VALUES (?, '', ?, '', ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL)",
            arrayOf(id, body, updatedAt, updatedAt, dayKey),
        )
        db.execSQL(
            "INSERT INTO note_fts (noteId, title, body, transcript) VALUES (?, '', ?, '')",
            arrayOf(id, body),
        )
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test fun `v1 migrates to v2 without losing a note`() {
        helper.createDatabase(DB, 1).use { db ->
            insertNote(db, "a", "заметка с днём", "2026-09-19", 1_000)
            insertNote(db, "b", "заметка без дня", null, 2_000)
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, NotesDatabase.MIGRATION_1_2)

        assertEquals("a note was lost in the migration", 2, count(db, "SELECT count(*) FROM notes"))
        db.query("SELECT body FROM notes ORDER BY id").use { c ->
            c.moveToFirst(); assertEquals("заметка с днём", c.getString(0))
            c.moveToNext(); assertEquals("заметка без дня", c.getString(0))
        }
        assertTrue(
            "the unique index the migration exists to add is not there",
            count(db, "SELECT count(*) FROM sqlite_master WHERE type='index' AND name='index_notes_dayKey'") == 1,
        )
        db.close()
    }

    /**
     * **Two notes for one day both survive; the later one gives up the day.**
     *
     * A UNIQUE index on `dayKey` means only one row may hold a date — it does not mean the other
     * note may be destroyed. `MIGRATION_1_2` deleted it, silently, on the first launch after an
     * upgrade, and the person who dictated it had no way to know: the migration runs below the
     * repository, so no `NoteChange.Deleted` is emitted and the vault mirror never learns either
     * (`B-088`).
     *
     * `DEC-0035` had already decided this exact question for the runtime path — *"a restored
     * daily note gives up its day rather than destroying the one that holds it … it keeps every
     * word it had and stops being the note for that date"* — and the migration was written
     * against the opposite rule, in the same repository, for the same constraint. The holder
     * keeps the day; every later arrival is demoted to an ordinary note.
     *
     * This test asserted the destructive behaviour until `DEC-0058`. It was not wrong about what
     * the code did; it was wrong about what the code should do, which is the more expensive kind
     * of green.
     */
    @Test fun `two notes for the same day both survive and the later one gives up the day`() {
        helper.createDatabase(DB, 1).use { db ->
            insertNote(db, "older", "первая за день", "2026-09-19", 1_000)
            insertNote(db, "newer", "вторая за день", "2026-09-19", 2_000)
            // **One autosave, and it is the whole point of this line.** `@Insert(REPLACE)`
            // reassigns the rowid, and the editor autosaves every 600 ms — so in any real v1
            // database the note the person has been typing into all day carries the HIGHEST
            // rowid, not the lowest. A fixture that inserts each row exactly once cannot reach
            // that state, and the first version of this test did exactly that: it passed over a
            // migration that demoted the active note and left the day to the stale duplicate.
            db.execSQL(
                "INSERT OR REPLACE INTO notes (id, title, body, tags, createdAt, updatedAt, dayKey," +
                    " audioPath, transcriptText, transcriptLanguage, transcriptSource," +
                    " transcriptEngine, transcriptDurationMs)" +
                    " VALUES ('older', '', 'первая за день, дополненная', '', 1000, 9000," +
                    " '2026-09-19', NULL, NULL, NULL, NULL, NULL, NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, NotesDatabase.MIGRATION_1_2)

        assertEquals("a note was destroyed by the upgrade", 2, count(db, "SELECT count(*) FROM notes"))
        db.query("SELECT id FROM notes WHERE dayKey = '2026-09-19'").use { c ->
            assertEquals("exactly one note may hold the day", 1, c.count)
            c.moveToFirst()
            assertEquals(
                "the holder must be the note created first — `rowid` stops meaning creation order " +
                    "the first time REPLACE touches a row",
                "older",
                c.getString(0),
            )
        }
        db.query("SELECT body FROM notes WHERE id = 'newer'").use { c ->
            c.moveToFirst()
            assertEquals("the demoted note lost its words, not only its day", "вторая за день", c.getString(0))
        }
        db.query("SELECT dayKey FROM notes WHERE id = 'newer'").use { c ->
            c.moveToFirst()
            assertTrue("the demoted note must keep no day", c.isNull(0))
        }
        assertEquals(
            "a surviving note lost its index row, so a search will not find it",
            2,
            count(db, "SELECT count(*) FROM note_fts"),
        )
        assertEquals(
            "an index row outlived its note",
            0,
            count(db, "SELECT count(*) FROM note_fts WHERE noteId NOT IN (SELECT id FROM notes)"),
        )
        db.close()
    }

    /**
     * Three notes for one day, because "keep one, demote the rest" and "keep one, delete one" are
     * indistinguishable at two rows — and a `NOT IN (SELECT MIN(...))` that is right for two can
     * still be wrong for three.
     */
    @Test fun `three notes for one day leave one holder and two ordinary notes`() {
        helper.createDatabase(DB, 1).use { db ->
            insertNote(db, "a", "первая", "2026-09-19", 1_000)
            insertNote(db, "b", "вторая", "2026-09-19", 2_000)
            insertNote(db, "c", "третья", "2026-09-19", 3_000)
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, NotesDatabase.MIGRATION_1_2)

        assertEquals(3, count(db, "SELECT count(*) FROM notes"))
        assertEquals(1, count(db, "SELECT count(*) FROM notes WHERE dayKey = '2026-09-19'"))
        assertEquals(2, count(db, "SELECT count(*) FROM notes WHERE dayKey IS NULL"))
        assertEquals(3, count(db, "SELECT count(*) FROM note_fts"))
        db.close()
    }

    /**
     * **The one that would have caught the brick.** A device still on schema 1 has to reach 3
     * through both migrations composed, and `addMigrations` has to carry both.
     */
    @Test fun `every migration edge composes from v1 to v5`() {
        helper.createDatabase(DB, 1).use { db ->
            insertNote(db, "a", "Разрешение экрана", "2026-09-19", 1_000)
        }

        val db = helper.runMigrationsAndValidate(
            DB, 5, true,
            NotesDatabase.MIGRATION_1_2, NotesDatabase.MIGRATION_2_3, NotesDatabase.MIGRATION_3_4,
            NotesDatabase.MIGRATION_4_5,
        )

        assertEquals("the note did not survive three migrations", 1, count(db, "SELECT count(*) FROM notes"))
        assertEquals(
            "the rebuilt index does not hold the note",
            1,
            count(db, "SELECT count(*) FROM note_fts WHERE noteId = 'a'"),
        )
        // The point of v3: the rebuilt index folds case. Under `simple` this returns 0.
        assertEquals(
            "the rebuilt index still matches case-sensitively — the tokenizer did not change",
            1,
            count(db, "SELECT count(*) FROM note_fts WHERE note_fts MATCH 'разреш*'"),
        )
        db.close()
    }

    /**
     * **v3 → v4 stops indexing the primary key** (`M4`).
     *
     * `noteId` is a column of `note_fts`, and FTS4 indexes every column it is not told to skip —
     * so the UUID the app generates was a search term. Measured with sqlite3 3.51.0 during the
     * 2026-09-21 audit: `bead*`, `4d*` and `2026*` each returned a note whose entire text was
     * *"молоко и хлеб"*. A person searching for a year got notes that do not contain it.
     *
     * The rebuild is the same shape as `MIGRATION_2_3` and for the same reason: the index is
     * derived data, every row of it is recoverable from `notes`, and no note text is at risk.
     * **The CREATE statement is copied out of the generated `4.json`, not typed** — Room compares
     * it character for character at open time, on the person's headset, after the migration has
     * already run.
     */
    @Test fun `v3 migrates to v4 and the id stops being a search term`() {
        helper.createDatabase(DB, 3).use { db ->
            insertNote(db, "bead4d21-2026-4f00-9c31-000000000001", "молоко и хлеб", null, 1_000)
        }

        val db = helper.runMigrationsAndValidate(DB, 4, true, NotesDatabase.MIGRATION_3_4)

        assertEquals("the note did not survive the rebuild", 1, count(db, "SELECT count(*) FROM notes"))
        assertEquals(
            "the rebuilt index does not hold the note",
            1,
            count(db, "SELECT count(*) FROM note_fts WHERE noteId = 'bead4d21-2026-4f00-9c31-000000000001'"),
        )
        listOf("bead*", "4d*", "2026*", "9c31*").forEach { fragment ->
            assertEquals(
                "a fragment of the id still matches: $fragment",
                0,
                count(db, "SELECT count(*) FROM note_fts WHERE note_fts MATCH '$fragment'"),
            )
        }
        assertEquals(
            "the words stopped matching — the index was emptied rather than rebuilt",
            1,
            count(db, "SELECT count(*) FROM note_fts WHERE note_fts MATCH 'хлеб'"),
        )
        db.close()
    }

    /**
     * **v4 → v5 re-indexes what is already there with `ё` folded to `е`** (`B-132`).
     *
     * The fold happens in Kotlin at write time, so without this migration a person who upgrades
     * finds the notes they dictate afterwards and not the ones they dictated before — a worse
     * failure than the one being fixed, and one that looks like the fix not working at all.
     *
     * **The query is folded by [SearchFold.fold], the index by [SearchFold.sql].** That is the
     * whole point of asserting it here rather than in the repository: the two halves of the fold
     * live in different languages and cannot share an implementation, so this is the only place
     * that can catch them drifting apart.
     */
    @Test fun `v4 migrates to v5 and an old note dictated with ё is found by typing е`() {
        helper.createDatabase(DB, 4).use { db ->
            // Written by a build before the fold existed: the index holds the raw spelling.
            insertNote(db, "a", "ёлка ещё зелёная", null, 1_000)
        }

        val db = helper.runMigrationsAndValidate(DB, 5, true, NotesDatabase.MIGRATION_4_5)

        assertEquals("the note did not survive the rebuild", 1, count(db, "SELECT count(*) FROM notes"))
        assertEquals(
            "the rebuilt index does not hold the note",
            1,
            count(db, "SELECT count(*) FROM note_fts WHERE noteId = 'a'"),
        )
        assertEquals(
            "the note kept the spelling it was dictated in — the migration must not rewrite `notes`",
            "ёлка ещё зелёная",
            db.query("SELECT body FROM notes WHERE id = 'a'").use { c -> c.moveToFirst(); c.getString(0) },
        )
        listOf("елка", "еще", "зелен").forEach { typed ->
            assertEquals(
                "an old note is still unreachable by typing $typed — the SQL fold and the Kotlin fold disagree",
                1,
                count(db, "SELECT count(*) FROM note_fts WHERE note_fts MATCH '${SearchFold.fold(typed)}*'"),
            )
        }
        assertEquals(
            "the word the person actually dictated stopped matching",
            1,
            count(db, "SELECT count(*) FROM note_fts WHERE note_fts MATCH '${SearchFold.fold("ёлка")}'"),
        )
        // `й` is not `и`, in the index as in the repository.
        assertEquals(0, count(db, "SELECT count(*) FROM note_fts WHERE note_fts MATCH 'зелени'"))
        db.close()
    }

    /** A note with no transcript must index an empty string, not NULL — `NoteDao.upsert` writes `orEmpty()`. */
    @Test fun `a migrated note with no transcript indexes an empty string`() {
        helper.createDatabase(DB, 2).use { db ->
            insertNote(db, "a", "тело", null, 1_000)
        }

        val db = helper.runMigrationsAndValidate(DB, 3, true, NotesDatabase.MIGRATION_2_3)

        assertEquals(
            "a NULL transcript makes a migrated row differ from one the app wrote",
            1,
            count(db, "SELECT count(*) FROM note_fts WHERE noteId = 'a' AND transcript = ''"),
        )
        db.close()
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}

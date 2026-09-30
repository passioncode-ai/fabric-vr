package ai.passioncode.fabricvr.notes.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NoteEntity::class, NoteFts::class], version = 5, exportSchema = true)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        /**
         * v1 → v2 adds the unique index on `dayKey`, and **demotes** the duplicates rather than
         * deleting them.
         *
         * Existing duplicates would make the index creation fail, so exactly one row per date has
         * to give up its claim. This migration used to `DELETE` the others — every note for that
         * day but the oldest, on the first launch after an upgrade, with no announcement. It runs
         * below the repository, so no `NoteChange.Deleted` reaches the vault mirror either: the
         * row goes, the file stays, and nothing anywhere says a note the person dictated is gone
         * (`B-088`).
         *
         * **`DEC-0035` had already decided this exact question**, for the runtime path, against
         * the same constraint, in the same repository: *"a restored daily note gives up its day
         * rather than destroying the one that holds it … it keeps every word it had and stops
         * being the note for that date."* A migration is not a different question — it is that
         * question asked about rows that already exist. The note created first keeps the day
         * because it is the holder; every later arrival becomes an ordinary note and keeps every
         * word.
         *
         * **The holder is chosen by `createdAt`, and `rowid` was wrong** (`DEC-0062`). The first
         * version of this used `MIN(rowid)` on the reasoning that it is insertion order. It is —
         * until `@Insert(REPLACE)` touches the row, which reassigns it, and the editor autosaves
         * every 600 ms. So in any real v1 database the note the person has been typing into all
         * day carries the **highest** rowid, and `MIN(rowid)` demoted exactly that one: the
         * inverse of the rule stated three paragraphs above it. `createdAt` has been in this
         * table the whole time. The test passed because its fixture inserted each row once and
         * so could never reach the state a real database is in.
         *
         * **The FTS rows stay**, all of them, because no note is destroyed — which is why the
         * `DELETE FROM note_fts` this migration opened with is gone rather than adjusted. The
         * index is keyed by `noteId` and knows nothing about days.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // `rowid` breaks the tie when two rows share a `createdAt` to the millisecond,
                // which two notes for one day realistically never do — but a rule with no tiebreak
                // leaves the choice to the query planner, and the UNIQUE index would then refuse
                // the migration rather than the row.
                db.execSQL(
                    """
                    UPDATE notes SET dayKey = NULL
                    WHERE dayKey IS NOT NULL AND rowid NOT IN (
                        SELECT rowid FROM notes n WHERE n.dayKey IS NOT NULL
                          AND n.rowid = (
                            SELECT m.rowid FROM notes m WHERE m.dayKey = n.dayKey
                            ORDER BY m.createdAt ASC, m.rowid ASC LIMIT 1
                          )
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notes_dayKey ON notes (dayKey)")
            }
        }

        /**
         * v2 → v3 rebuilds the search index with the `unicode61` tokenizer (`DEC-0035`).
         *
         * The index is **derived data** — every row in it is recoverable from `notes` — so it is
         * dropped and rebuilt rather than migrated. No note text is at risk: `notes` is not
         * touched, and the worst case is a rebuildable index.
         *
         * **The CREATE statement below was copied out of the generated `3.json`, not typed.**
         * Room compares the create statement it generates against the one in the database at open
         * time, and a single differing character — a space, a quote — fails that identity check
         * on the person's headset, after the migration has already run. The generated string is
         * the only safe source.
         *
         * `IFNULL` matters: `NoteDao.upsert` writes `transcriptText.orEmpty()`, so an empty
         * string is what the index holds for a note with no transcript, and a `NULL` here would
         * make a migrated row differ from one the app wrote.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `note_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `note_fts` USING FTS4(`noteId` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `transcript` TEXT NOT NULL, tokenize=unicode61)",
                )
                db.execSQL(
                    """
                    INSERT INTO `note_fts` (`noteId`, `title`, `body`, `transcript`)
                    SELECT `id`, `title`, `body`, IFNULL(`transcriptText`, '') FROM `notes`
                    """.trimIndent(),
                )
            }
        }

        /**
         * v3 → v4 stops indexing the primary key (`M4`).
         *
         * `noteId` is a column of `note_fts`, and FTS4 indexes every column it is not told to
         * skip — so the UUID this app generates sat in the search index beside the person's
         * words. Measured with sqlite3 3.51.0 during the 2026-09-21 audit: `bead*`, `4d*` and
         * `2026*` each returned a note whose entire text was *"молоко и хлеб"*. Somebody
         * searching for a year got notes that do not contain it, and nothing on screen could
         * explain why.
         *
         * The column is kept — `NoteDao.search` joins `note_fts.noteId` to `notes.id` and
         * dropping it would break every search in the DAO. `notindexed` is exactly the option
         * for "stored, not searchable".
         *
         * Same shape as [MIGRATION_2_3], for the same reason: the index is derived data, every
         * row is recoverable from `notes`, and no note text is at risk. **The CREATE statement
         * below was copied out of the generated `4.json`, not typed** — Room compares it
         * character for character at open time, on the person's headset, after the migration has
         * already run, and `${'$'}{TABLE_NAME}` in the generated string is the only substitution
         * made to it.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `note_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `note_fts` USING FTS4(`noteId` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `transcript` TEXT NOT NULL, tokenize=unicode61, notindexed=`noteId`)",
                )
                db.execSQL(
                    """
                    INSERT INTO `note_fts` (`noteId`, `title`, `body`, `transcript`)
                    SELECT `id`, `title`, `body`, IFNULL(`transcriptText`, '') FROM `notes`
                    """.trimIndent(),
                )
            }
        }

        /**
         * v4 → v5 re-indexes every note with `ё` folded to `е` (`B-132`).
         *
         * **The table's shape does not change; its contents do.** `unicode61` folds case and does
         * not fold `ё`, and no tokenizer argument makes it — measured with sqlite3 3.51.0 on
         * 2026-09-22 against a table holding `ёлка ещё`, `MATCH 'елка'` returns 0 under plain
         * `unicode61`, 0 under `remove_diacritics=1` and 0 under `remove_diacritics=2`, the most
         * aggressive setting there is. SQLite's diacritic table is built for Latin, and `ё` is
         * U+0451, a precomposed letter with no combining mark to remove. So [SearchFold] does it
         * in Kotlin at write time, and every row written before this migration holds the
         * unfolded spelling — a person who upgrades would find their new notes and not their old
         * ones, which is a worse failure than the one being fixed.
         *
         * Same shape as [MIGRATION_2_3] and [MIGRATION_3_4], for the same reason: the index is
         * **derived data**, every row of it is recoverable from `notes`, and no note text is at
         * risk. `notes` is not read from here except as the source, and nothing the person reads
         * comes from this table — [NoteDao.search] joins it and selects `notes.*`, so a note
         * dictated as `ёлка` is still stored, displayed and exported as `ёлка`.
         *
         * **The CREATE statement is copied out of the generated `5.json`, not typed** — Room
         * compares it character for character at open time, on the person's headset, after the
         * migration has already run. It is identical to v4's: this migration changes no schema,
         * which is why `5.json` carries the same identity hash as `4.json`.
         *
         * **The fold is written twice and the duplication is deliberate**: [SearchFold.fold] runs
         * in Kotlin where the app writes, and [SearchFold.sql] emits the same mapping for here,
         * because a migration cannot call Kotlin. `MigrationTest` searches a migrated database
         * with a query folded by the Kotlin half, so the two cannot drift apart in silence.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `note_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `note_fts` USING FTS4(`noteId` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `transcript` TEXT NOT NULL, tokenize=unicode61, notindexed=`noteId`)",
                )
                db.execSQL(
                    """
                    INSERT INTO `note_fts` (`noteId`, `title`, `body`, `transcript`)
                    SELECT `id`,
                           ${SearchFold.sql("`title`")},
                           ${SearchFold.sql("`body`")},
                           ${SearchFold.sql("IFNULL(`transcriptText`, '')")}
                    FROM `notes`
                    """.trimIndent(),
                )
            }
        }

        fun open(context: Context): NotesDatabase =
            Room.databaseBuilder(context, NotesDatabase::class.java, "fabricvr-notes.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}

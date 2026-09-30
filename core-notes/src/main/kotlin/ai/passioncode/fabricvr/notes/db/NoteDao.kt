package ai.passioncode.fabricvr.notes.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ai.passioncode.fabricvr.notes.NoteIdentity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    /**
     * Bounded, and the bound is enforced by the compiler rather than by care: Room refuses an
     * unused query parameter, so `LIMIT :limit` cannot be deleted while `limit` is in the
     * signature. Measured 2026-09-21 by trying — the build fails with *"Unused parameter:
     * limit"*, which is a stronger guarantee than the test that motivated it.
     */
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<NoteEntity>>

    /** How many notes exist, so a clipped list can say how many it is not showing. */
    @Query("SELECT count(*) FROM notes")
    suspend fun count(): Int

    /**
     * Deliberately **unbounded**, unlike [observeAll]. A single tag's notes are a small subset by
     * construction — the filter is the bound — and clipping it would mean a chip that silently
     * shows some of what it matched.
     */
    @Query(
        "SELECT * FROM notes WHERE ',' || tags || ',' LIKE '%,' || :tag || ',%' ESCAPE '\\' " +
            "ORDER BY updatedAt DESC",
    )
    fun observeByTag(tag: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE dayKey = :dayKey LIMIT 1")
    suspend fun getByDay(dayKey: String): NoteEntity?

    @Query("SELECT tags FROM notes")
    fun observeTagBlobs(): Flow<List<String>>

    @Query(
        """
        SELECT notes.* FROM notes
        JOIN note_fts ON note_fts.noteId = notes.id
        WHERE note_fts MATCH :query
        ORDER BY notes.updatedAt DESC
        LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NoteEntity>

    /**
     * Two columns and no bodies. **Deliberately unbounded**, unlike [recent] and [observeAll]:
     * the reconciler asks which notes exist, and a windowed answer would declare everything past
     * the window missing from the vault and rewrite files that are already there.
     */
    @Query("SELECT id, createdAt, updatedAt FROM notes")
    suspend fun identities(): List<NoteIdentity>

    @Query(
        """
        SELECT notes.* FROM notes
        JOIN note_fts ON note_fts.noteId = notes.id
        WHERE note_fts MATCH :query
        ORDER BY notes.updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchOnce(query: String, limit: Int): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(row: NoteFts)

    @Query("DELETE FROM note_fts WHERE noteId = :id")
    suspend fun deleteFts(id: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    /** Whoever currently holds [dayKey], if it is not [id]. `A-24`: `REPLACE` is about to evict it. */
    @Query("SELECT id FROM notes WHERE dayKey = :dayKey AND id != :id")
    suspend fun idHoldingDay(dayKey: String, id: String): String?

    /**
     * Write a note, giving up its day if another note already holds it, **in one transaction**.
     *
     * The rule lived in the repository as a read followed by a write in two separate DAO calls —
     * a check-then-act. Two writers claiming one fresh day both read *nobody holds it*, both
     * wrote, and the second `REPLACE` destroyed the first (`DEC-0062`). That was unreachable
     * while the rule lived in one main-dispatcher caller and became reachable the moment it
     * became an invariant every door passes through: `VaultImporter` runs on IO and a dictation
     * commit on `Graph.scope`.
     *
     * Returns the entity as it was actually written, so the caller and the change stream carry
     * the same note the table does.
     */
    @Transaction
    suspend fun upsertKeepingOneNotePerDay(note: NoteEntity): NoteEntity {
        val written = note.dayKey
            ?.takeIf { key -> idHoldingDay(key, note.id) != null }
            ?.let { note.copy(dayKey = null) }
            ?: note
        upsert(written)
        return written
    }

    @Transaction
    suspend fun upsert(note: NoteEntity) {
        // `insertNote` is `REPLACE` against a table with a UNIQUE index on `dayKey`, so a note
        // claiming a day another note already holds **silently deletes that other note** — and
        // the two lines below only remove this note's index row. The evicted one's row would
        // outlive it: the `JOIN` drops it, so a search finds fewer things than it counted, and
        // the index grows for ever (`A-24`).
        note.dayKey?.let { key -> idHoldingDay(key, note.id)?.let { displaced -> deleteFts(displaced) } }
        insertNote(note)
        deleteFts(note.id)
        // **The index holds the folded spelling; `notes` holds what the person said** (`B-132`).
        // `SearchFold` maps `ё` to `е` because whisper writes the letter it hears and a person
        // types the letter on the keyboard, and no tokenizer argument reconciles the two. Nothing
        // here reaches a screen: every query above selects `notes.*` and joins `note_fts` only to
        // MATCH, so the note is still displayed and exported exactly as it was dictated.
        // `RoomNotesRepository.search` folds the query with the same function, and one side
        // without the other is worse than neither.
        insertFts(
            NoteFts(
                noteId = note.id,
                title = SearchFold.fold(note.title),
                body = SearchFold.fold(note.body),
                transcript = SearchFold.fold(note.transcriptText.orEmpty()),
            ),
        )
    }

    /** One transaction, so a race cannot produce two notes for one day. */
    @Transaction
    suspend fun getOrCreateByDay(dayKey: String, candidate: NoteEntity): NoteEntity {
        getByDay(dayKey)?.let { return it }
        upsert(candidate)
        return getByDay(dayKey) ?: candidate
    }

    @Transaction
    suspend fun delete(id: String) {
        deleteFts(id)
        deleteNote(id)
    }
}

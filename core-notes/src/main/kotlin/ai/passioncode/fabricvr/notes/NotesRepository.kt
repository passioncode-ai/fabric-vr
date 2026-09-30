package ai.passioncode.fabricvr.notes

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.runCatchingCancellable
import ai.passioncode.fabricvr.notes.db.NoteDao
import ai.passioncode.fabricvr.notes.db.SearchFold
import ai.passioncode.fabricvr.notes.db.toEntity
import ai.passioncode.fabricvr.notes.db.toNote
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface NotesRepository {
    fun observeNotes(tag: String? = null, limit: Int = DEFAULT_WINDOW): Flow<List<Note>>
    fun observeChanges(): Flow<NoteChange>
    fun observeTags(): Flow<List<String>>
    suspend fun get(id: String): Note?
    suspend fun upsert(note: Note): Result<Note>

    /**
     * [upsert] with the note's **own** `updatedAt` instead of this instant.
     *
     * `M3`: the vault importer went through `upsert`, which stamps `updatedAt = now()` — so every
     * note recovered from a reinstall came back *"updated today"*, in one undifferentiated block,
     * and the mirror then wrote that over the file's own `updated:`. The original date was
     * destroyed by the operation that existed to save it, and the file it was read from was
     * overwritten with the loss.
     *
     * Every other rule of [upsert] still applies — the tags are re-derived from the text and a
     * note claiming a taken day still gives it up — because this is the same write with one
     * decision changed, not a second door into the table (`DEC-0058`).
     *
     * **The default stamps**, i.e. it is [upsert]. A test double that models no difference between
     * the two is not made wrong by this method existing, and the alternative — an abstract member
     * — would break every fake in the tree for a distinction most of them have no opinion about.
     * `RoomNotesRepository` overrides it and `NotesRepositoryTest` proves the override; anything
     * that *depends* on the timestamps surviving must be given a repository that implements it.
     */
    suspend fun upsertPreservingTimestamps(note: Note): Result<Note> = upsert(note)

    /**
     * Every note's id and creation time, unbounded, with no text read.
     *
     * Unbounded is deliberate and is the one place in this interface where it is: reconciling the
     * index against the vault is a question about **which notes exist**, and an answer clipped to
     * a window would silently declare the notes past it missing (`DEC-0040` bounds the queries a
     * screen makes, and a screen is not what asks this).
     *
     * The default pays for the bodies it then throws away; `RoomNotesRepository` selects two
     * columns.
     */
    suspend fun identities(): List<NoteIdentity> =
        recent(Int.MAX_VALUE).map { NoteIdentity(it.id, it.createdAt, it.updatedAt) }
    suspend fun delete(id: String): Result<Unit>
    fun search(query: String, limit: Int = DEFAULT_WINDOW): Flow<List<Note>>
    suspend fun dailyNote(day: LocalDate): Result<Note>

    /**
     * Who holds [dayKey] right now, or null.
     *
     * `notes` has a UNIQUE index on `dayKey` and every write is a `REPLACE`, so a caller about to
     * write a note for a day has to be able to ask whether it is taken — otherwise the write is
     * a silent deletion (`A-24`). Undo asks.
     */
    suspend fun noteForDay(dayKey: String): Note?

    /** How many notes exist. A clipped list has to be able to say how many it is not showing. */
    suspend fun count(): Int

    /** The newest [limit] notes. Bounded, because the assistant needs a page, not the table. */
    suspend fun recent(limit: Int): List<Note>

    /** Notes matching **any** term. A question is not a phrase every note must contain. */
    suspend fun searchAny(terms: List<String>, limit: Int): List<Note>

    companion object {
        /**
         * How many notes a screen asks for at once.
         *
         * Two hundred is not a measurement, it is a ceiling chosen to be far above any real base
         * and far below the point where materialising the rows costs anything — roughly 1 KB of
         * text per note, so ~0.2 MB. It stops being enough somewhere past a couple of thousand
         * notes, and `OQ-0003` records what replaces it and when.
         */
        const val DEFAULT_WINDOW = 200

        fun newNote(title: String = "", body: String = "", now: Long = System.currentTimeMillis()): Note =
            Note(
                id = UUID.randomUUID().toString(),
                title = title,
                body = body,
                tags = TagParser.parse("$title\n$body"),
                createdAt = now,
                updatedAt = now,
            )
    }
}

class RoomNotesRepository(
    private val dao: NoteDao,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Runs **before** a row is deleted, with the id and creation time that locate its files
     * (`B-239`, `DEC-0089`). `Graph` points it at the vault's `RemovalJournal.record`.
     *
     * The mirror journals a removal too, but only when its collector reaches the `Deleted` change —
     * after the row is gone — so a process death in between left a `.md` with no row and no journal
     * entry, and the next reconcile imported the deleted note back. This module does not know the
     * vault exists (`:core-notes` has no dependency on `:feature-vault`), which is why it is a
     * function and not a journal. A hook that throws does not stop the delete: the person asked
     * for it, and the mirror still tries the removal at once — what is lost is only the guarantee
     * across a death in that window, logged as `notes.delete.intent_failed`.
     */
    private val beforeDelete: suspend (id: String, createdAt: Long) -> Unit = { _, _ -> },
) : NotesRepository {

    /**
     * `replay` is what makes a late subscriber correct: the vault mirror starts on an application
     * scope, so the daily note written during cold start happened before anybody was listening, and
     * with `tryEmit` into a replay-less flow it was simply dropped. `SUSPEND` is the other half —
     * a slow mirror must slow the writer down, never lose a note.
     */
    private val changes = MutableSharedFlow<NoteChange>(
        replay = 64,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> =
        (if (tag == null) dao.observeAll(limit) else dao.observeByTag(tag.escapeForLike()))
            .map { rows -> rows.map { it.toNote() } }

    override suspend fun count(): Int = dao.count()

    override fun observeChanges(): Flow<NoteChange> = changes.asSharedFlow()

    /**
     * `E-12`: this is a second full scan, `combine`d with the note list, and Room invalidates on
     * the whole `notes` table — so every autosave re-derived the tags and emitted a **second,
     * identical** state. The scan still runs; `distinctUntilChanged` stops the emission.
     *
     * A normalised tag table with a junction is the right eventual shape and it is schema v4 —
     * immediately after `T-022`'s v3, which is exactly the two-migrations-in-one-release risk
     * `H-24` names. It belongs after the migration harness, not here.
     */
    override fun observeTags(): Flow<List<String>> =
        dao.observeTagBlobs().map { blobs ->
            blobs.asSequence()
                .flatMap { it.split(",").asSequence() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList()
        }.distinctUntilChanged()

    /** `_` and `%` are LIKE wildcards, and `deep_work` is a perfectly ordinary tag. */
    private fun String.escapeForLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    override suspend fun get(id: String): Note? = dao.get(id)?.toNote()

    override suspend fun upsert(note: Note): Result<Note> = write(note, stamp = now())

    /**
     * `M3`. One line apart from [upsert] and the line is `updatedAt`, so they share a body:
     * two copies would be two places for the day rule, the tag rule and the change emission to
     * drift, which is the `DEC-0058` failure with different words.
     */
    override suspend fun upsertPreservingTimestamps(note: Note): Result<Note> =
        write(note, stamp = note.updatedAt)

    override suspend fun identities(): List<NoteIdentity> = dao.identities()

    private suspend fun write(note: Note, stamp: Long): Result<Note> = runCatchingCancellable {
        // Parsed only, never unioned with the previous set: tags come from the text the person
        // wrote, so deleting `#plan` from the body has to delete the tag (SCN-003).
        val withTags = note.copy(
            tags = TagParser.parse("${note.title}\n${note.body}"),
            updatedAt = stamp,
        )
        // **A note claiming a day another note holds gives up the day** (`DEC-0035`, and
        // `DEC-0058` for why it lives here). `insertNote` is `@Insert(REPLACE)` against a UNIQUE
        // index on `dayKey`, so without this the write silently **deletes** the holder — the note
        // the person has been typing into all day, with no message and no `NoteChange.Deleted`.
        //
        // The rule was already decided and was already implemented **in `NotesViewModel`**, which
        // is one caller. `VaultImporter` is another, and it writes whatever `day:` a file carries:
        // importing two files for one date destroyed one of them, during the recovery path, which
        // is the worst moment this defect could pick. **An invariant enforced by a caller is not
        // an invariant** — it is a habit that holds until the next door is cut.
        // **The read and the write share one transaction**, which they did not at first: the
        // decision was made here and the write happened in a separate DAO call, so two writers
        // claiming one fresh day both saw it free and the second `REPLACE` destroyed the first
        // (`DEC-0062`). That race was unreachable while the rule lived in one main-dispatcher
        // caller; making it an invariant every door passes through is exactly what made it
        // reachable, from `VaultImporter` on IO and a dictation commit on `Graph.scope`.
        val demoted = dao.upsertKeepingOneNotePerDay(withTags.toEntity()).toNote()
        changes.emit(NoteChange.Upserted(demoted))
        demoted
    }.recoverFailure("upsert")

    override suspend fun delete(id: String): Result<Unit> = runCatchingCancellable {
        // Read before deleting: the vault needs the creation time to find the file, and after the
        // row is gone there is nowhere left to learn it from.
        val createdAt = dao.get(id)?.createdAt ?: 0L
        // A row that is not there has no files to owe a removal for.
        if (createdAt != 0L) {
            runCatchingCancellable { beforeDelete(id, createdAt) }
                .onFailure { Log2.w("notes.delete.intent_failed", "note" to id) }
        }
        dao.delete(id)
        changes.emit(NoteChange.Deleted(id, createdAt))
    }.recoverFailure("delete")

    /**
     * Blank queries return nothing rather than everything: an empty search field is not a request
     * to list the base, and FTS would happily match nothing at all.
     */
    override fun search(query: String, limit: Int): Flow<List<Note>> {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return flowOf(emptyList())
        // Tokenise the way the index did. Stripping punctuation out of a word joined `c-sharp`
        // into `csharp*`, which matches nothing, and a trailing `?` swallowed the prefix star.
        // **Folded exactly as the index was** (`B-132`): `NoteDao.upsert` puts `SearchFold.fold`
        // over every indexed column, so a query that skipped the fold would ask for a spelling the
        // index no longer contains — turning the defect around rather than fixing it.
        val match = TOKEN.findAll(SearchFold.fold(cleaned))
            .map { it.value }
            .filter { it.isNotBlank() }
            .joinToString(" ") { term -> term + "*" }
        if (match.isBlank()) return flowOf(emptyList())
        // **The `limit` keeps the MOST RECENT matches, not the best ones** — the query orders by
        // `updatedAt`. That is a real weakness and this is not the task that fixes it (`G-11`
        // wants `bm25` ranking); saying so here is the point, because "200 of your matches"
        // silently meaning "the 200 newest" is what becomes a bug report six months later.
        return dao.search(match, limit).map { rows -> rows.map { it.toNote() } }
    }

    /**
     * Idempotent by construction, not by hope: the read and the insert share one transaction and the
     * `dayKey` column is uniquely indexed, so two callers racing on first launch cannot leave two
     * notes for one day.
     */
    override suspend fun recent(limit: Int): List<Note> =
        dao.recent(limit).map { it.toNote() }

    override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> {
        // The same fold as [search] and as the index (`B-132`).
        val match = terms.asSequence()
            .flatMap { TOKEN.findAll(SearchFold.fold(it)).map(MatchResult::value) }
            .filter { it.length >= 3 }
            .distinct()
            .joinToString(" OR ") { "$it*" }
        if (match.isBlank()) return emptyList()
        return dao.searchOnce(match, limit).map { it.toNote() }
    }

    override suspend fun noteForDay(dayKey: String): Note? = dao.getByDay(dayKey)?.toNote()

    override suspend fun dailyNote(day: LocalDate): Result<Note> = runCatchingCancellable {
        val key = day.toString()
        val stamp = now()
        val existing = dao.getByDay(key)
        val entity = dao.getOrCreateByDay(
            dayKey = key,
            candidate = Note(
                id = UUID.randomUUID().toString(),
                title = key,
                body = "",
                createdAt = stamp,
                updatedAt = stamp,
                dayKey = key,
            ).toEntity(),
        )
        val note = entity.toNote()
        if (existing == null) changes.emit(NoteChange.Upserted(note))
        note
    }.recoverFailure("dailyNote")

    private fun <T> Result<T>.recoverFailure(op: String): Result<T> = fold(
        onSuccess = { Result.success(it) },
        onFailure = {
            Log2.e("notes.$op.failed", it)
            Result.failure(NotesException(AppError.Storage(op, it)))
        },
    )
}

/** The same alphabet the FTS tokenizer splits on, so a query cannot ask for something never indexed. */
private val TOKEN = Regex("[\\p{L}\\p{N}_]+")

/** Carries the [AppError] a failed storage operation should surface. */
class NotesException(val error: AppError) : Exception(error.cause)

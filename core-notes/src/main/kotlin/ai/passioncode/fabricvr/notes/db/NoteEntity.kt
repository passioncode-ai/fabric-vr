package ai.passioncode.fabricvr.notes.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript

@Entity(
    tableName = "notes",
    indices = [Index(value = ["dayKey"], unique = true)],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val tags: String,
    val createdAt: Long,
    val updatedAt: Long,
    val dayKey: String?,
    val audioPath: String?,
    val transcriptText: String?,
    val transcriptLanguage: String?,
    val transcriptSource: String?,
    val transcriptEngine: String?,
    val transcriptDurationMs: Long?,
)

/**
 * The search index. Kept in step with [NoteEntity] inside the DAO's transaction.
 *
 * **`unicode61`, not Room's default `simple`, and the argument is the whole point.** `simple`
 * case-folds ASCII only, so the query had to carry the same case as the indexed token — in both
 * directions. Whisper capitalises the first word of every sentence and every proper noun, so a
 * dictated `Разрешение экрана` was findable only by typing the capital, and the lowercase
 * `панель` in the same note was findable only in lowercase. A person cannot know which of their
 * own words were capitalised, so the failure looked exactly like the note not existing (`G-04`,
 * `E-20`). Measured with sqlite3 3.51.0 on 2026-09-20: `MATCH 'разреш*'` against a table holding
 * `Разрешение` returned **0** rows under `simple` and 1 under `unicode61`.
 *
 * **What it does not do:** `unicode61` does not fold `ё`/`е`, and no tokenizer argument makes it —
 * `remove_diacritics=2`, the most aggressive setting, leaves `елка -> 0` against `ёлка`. Whisper
 * emits `ё` and people type `е`, so that is a real residual for a Russian writer; folding it means
 * normalising at write time in Kotlin, which is its own change with its own migration. `B-132`.
 * `й`/`и` staying distinct is correct — they are different letters.
 *
 * **`noteId` is stored and NOT indexed** (`M4`, schema v4). FTS4 indexes every column it is not
 * told to skip, so the UUID this app generates was a search term beside the person's words:
 * measured with sqlite3 3.51.0 on 2026-09-21, `bead*`, `4d*` and `2026*` each returned a note
 * whose entire text was *"молоко и хлеб"*. Somebody searching for a year got notes that do not
 * contain it, and nothing on the screen could explain why. The column stays — [NoteDao.search]
 * joins on it — it simply stops being searchable, which is precisely what `notindexed` is for.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["noteId"])
@Entity(tableName = "note_fts")
data class NoteFts(
    @ColumnInfo(name = "noteId") val noteId: String,
    val title: String,
    val body: String,
    val transcript: String,
)

fun NoteEntity.toNote(): Note = Note(
    id = id,
    title = title,
    body = body,
    tags = if (tags.isBlank()) emptySet() else tags.split(",").filter { it.isNotBlank() }.toSet(),
    createdAt = createdAt,
    updatedAt = updatedAt,
    dayKey = dayKey,
    audioPath = audioPath,
    transcript = transcriptText?.let {
        Transcript(
            text = it,
            language = transcriptLanguage.orEmpty(),
            source = transcriptSource?.let { s -> runCatching { SttSource.valueOf(s) }.getOrNull() }
                ?: SttSource.LOCAL,
            engine = transcriptEngine.orEmpty(),
            durationMs = transcriptDurationMs ?: 0L,
        )
    },
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id,
    title = title,
    body = body,
    tags = tags.joinToString(","),
    createdAt = createdAt,
    updatedAt = updatedAt,
    dayKey = dayKey,
    audioPath = audioPath,
    transcriptText = transcript?.text,
    transcriptLanguage = transcript?.language,
    transcriptSource = transcript?.source?.name,
    transcriptEngine = transcript?.engine,
    transcriptDurationMs = transcript?.durationMs,
)

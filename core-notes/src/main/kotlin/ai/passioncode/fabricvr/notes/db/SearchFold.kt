package ai.passioncode.fabricvr.notes.db

/**
 * The one place that decides which distinct letters a search treats as the same one.
 *
 * **Today that is exactly one pair: `ё` → `е`** (`B-132`). Whisper transcribes from sound, so it
 * writes the letter that is actually spoken — `ёлка`, `ещё`, `зелёная`. A person typing into a
 * search field writes `е`, because that is what Russian keyboards and ordinary Russian writing
 * use. Writer and reader disagree about a character neither of them chose, and the note does not
 * come back. Nothing on screen can explain that, which makes it indistinguishable from the note
 * not existing — the same shape as the case-folding defect `DEC-0035` fixed.
 *
 * **No tokenizer argument does this, which is why it is Kotlin.** Measured with sqlite3 3.51.0 on
 * 2026-09-22 against an FTS4 table holding `ёлка ещё`:
 *
 * ```
 * tokenize=unicode61                          MATCH 'елка' -> 0
 * tokenize=unicode61 "remove_diacritics=1"    MATCH 'елка' -> 0
 * tokenize=unicode61 "remove_diacritics=2"    MATCH 'елка' -> 0   (the most aggressive setting)
 * ```
 *
 * SQLite's diacritic table is built for Latin. `ё` is U+0451 — a precomposed letter with its own
 * codepoint, not `е` followed by a combining diaeresis — so there is no mark to remove. The fold
 * has to be ours, and it has to be applied on **both** sides: to the text going into the index
 * ([NoteDao.upsert]) and to the query coming out of the search box
 * ([ai.passioncode.fabricvr.notes.RoomNotesRepository.search]). One side alone is worse than
 * neither, because it silently reverses which of the two spellings can be found.
 *
 * **It stops here, deliberately.** `й` and `и` are different letters — `мой` and `мои` are
 * different words — and `ъ`/`ь` change meaning. `ё`/`е` is the one pair Russian orthography itself
 * treats as optional: the dictionary headword is `ёлка`, and writing `елка` is not a misspelling.
 * A normaliser that reached one letter further would merge words a person meant to keep apart,
 * and the tests pin that boundary rather than trusting this comment.
 *
 * **Nothing the person reads passes through here.** `note_fts` is a derived index; every screen
 * renders `notes.*`, and [NoteDao.search] joins the two and selects `notes.*`. So a note dictated
 * as `ёлка` is still stored, exported to the vault and displayed as `ёлка` — only the search
 * index holds the folded spelling.
 *
 * **[SQL] is this function's twin and must stay its twin.** Room migrations rebuild `note_fts` in
 * SQL, where this function cannot run, so the same mapping exists once more as a `replace()`
 * expression. `MigrationTest` searches a migrated database with a query folded by [fold], which
 * fails if the two ever disagree.
 */
internal object SearchFold {

    /** Fold [text] into the spelling the search index stores and the query must ask in. */
    fun fold(text: String): String = text.replace('ё', 'е').replace('Ё', 'Е')

    /**
     * [fold] as a SQL expression over a column, for the migrations that rebuild the index.
     *
     * Kept as a function of the column name rather than a copied string so that a new migration
     * cannot quietly fold fewer columns than the one before it.
     */
    fun sql(column: String): String = "replace(replace($column, 'ё', 'е'), 'Ё', 'Е')"
}

# `:core-notes`

The knowledge base: what a note is, how tags are found, how notes are stored and searched.

## Owns

- **`Note`**, **`Transcript`**, **`SttSource`**, **`NoteChange`** — the shared vocabulary. Other
  modules take these; none of them redefine a note.
- **`TagParser`** — `#tag` from the text the person already wrote, Unicode letters included, so
  `#идея` is a tag. Lowercased, de-duplicated, `#` alone and `C#` ignored.
- **Room storage** — `NoteEntity` plus an FTS4 `note_fts` table kept in step inside the DAO's
  transaction. The transcript is indexed, which is what makes a voice note findable by its words.
  **The tokenizer is `unicode61`, not Room's default `simple`** (`DEC-0035`'s sibling change in
  `T-022`): `simple` case-folds ASCII only, so a dictated `Разрешение` was findable only by typing
  the capital and the lowercase `панель` in the same note only in lowercase. Measured with
  sqlite3 3.51.0: `MATCH 'разреш*'` against a table holding `Разрешение` returned **0** rows under
  `simple` and 1 under `unicode61`. It does not fold `ё`/`е` and no argument makes it — `B-132`.
  **`noteId` is stored and not indexed** (`M4`, schema v4). FTS4 indexes every column it is not
  told to skip, so the UUID this app generates was a search term beside the person's words:
  measured with sqlite3 3.51.0 on 2026-09-21, `bead*`, `4d*` and `2026*` each returned a note whose
  entire text was *"молоко и хлеб"*. Somebody searching for a year got notes that do not contain
  it and nothing on the screen could explain why. The column stays, because `NoteDao.search` joins
  on it; `notindexed=noteId` is exactly the option for *stored, not searchable*.
- **`NotesRepository`** — `observeNotes(tag, limit)`, `observeChanges()`, `observeTags()`, `get`,
  `upsert`, `upsertPreservingTimestamps`, `identities()`, `delete`, `search(query, limit)`,
  `dailyNote`, `count()`. Failures arrive as `NotesException(AppError.Storage)`.
  **`RoomNotesRepository.delete` runs a `beforeDelete(id, createdAt)` hook first** (`DEC-0089`):
  `Graph` points it at the vault's removal journal, so a deletion is written down before the row
  goes and a death before the mirror runs cannot bring the note back. A hook that throws is logged
  and does not stop the delete; a row that is not there runs no hook.
  **`upsertPreservingTimestamps` is `upsert` with one decision changed** (`M3`): the note keeps
  its own `updatedAt` instead of being stamped with this instant. The vault importer went through
  `upsert`, so every note recovered from a reinstall came back *"updated today"* in one
  undifferentiated block and the mirror then wrote that over the file's own `updated:` — the
  original date destroyed by the operation that existed to save it. Both go through one private
  body, because two copies would be two places for the day rule, the tag rule and the change
  emission to drift. **The interface's default is `upsert`**, so a test double is not forced to
  model a distinction it does not have; `RoomNotesRepository` overrides it and anything depending
  on the timestamps surviving must be handed a repository that implements it.
  **`identities()` is the one query with no window** (`DEC-0040` bounds what a *screen* asks).
  Reconciling the index against the vault is a question about which notes exist, and a clipped
  answer would declare everything past the window missing from disk and rewrite files already
  there. Two columns, no bodies: at ~1 kB a note, asking it with `recent()` would read several
  megabytes of text to answer a question about names, on a device whose heap is already a finding.
  **Every list and search query carries a window** (`DEC-0040`), `DEFAULT_WINDOW = 200`, and the
  bound is enforced by the compiler rather than by care: Room refuses an unused query parameter,
  so the `LIMIT` cannot be deleted while `limit` is in the signature. `observeByTag` is the one
  exemption and its KDoc says why. The window was dormant until `DEC-0035` — a one-letter Russian
  prefix matched almost nothing under the `simple` tokenizer, and `unicode61` made it match the
  whole base on a 120 ms debounce while somebody types. `OQ-0003` records what replaces the
  window and when. The 200 kept are the most **recent** matches, not the best ones; ranking is a
  different question (`G-11`).

## Decisions worth keeping

- **A blank query matches nothing — here.** `repository.search("   ")` returns an empty list, and
  `NotesRepositoryTest`'s *a blank query matches nothing* asserts exactly that — cited by name
  rather than by line, because a line number rots with nothing changing it while `§13` already
  resolves a symbol (`B-177`). This module's contract is unchanged and was
  never the thing that moved.
  **What moved is one level up, in `:app`**: `SearchViewModel.kt:45-46` stops calling `search()`
  for a blank field and calls `observeNotes()` instead, with its reason in its own comment — *"A
  blank field is not 'no results': it lists what the person wrote recently, which is almost always
  what they came to find."* An empty field means the person has arrived, not that they have asked
  for nothing; `searched` stays false while it is blank, so an empty **base** still reads as an
  empty base rather than as no matches. The screen contract is `docs/ux/screens.md` SCR-04.
  **This bullet said the opposite of its own module's test for one commit** (`DEC-0057`), because
  `T-045` inverted it to match the screen — and it was a blind product tier, not a gate, that saw it. The lesson is the boundary, not the sentence: a module document
  states **its module's** contract, and a behaviour assembled above it belongs where it is
  assembled (`DOCMAP.md`'s one-home rule). A repository that returned recent notes for a blank
  query would be a different and worse design — the caller could no longer tell "no query" from
  "no matches".
- **Tags are re-derived on every save**, so editing the text is the only way to manage them.
- **`dailyNote` is idempotent** — called twice on one day it returns the same note.
- **A write that claims a taken day gives up the day; nobody is evicted** (`DEC-0035`,
  `DEC-0058`). `NotesRepository.upsert` demotes an incoming note whose `dayKey` another note
  already holds — it keeps every word and stops being *the* note for that date. **This is the
  repository's invariant, not a caller's**: it lived in `NotesViewModel.undoDelete()` alone, so
  `VaultImporter` — the recovery path — destroyed a note whenever two vault files carried one
  `day:`. An invariant enforced by a caller is a habit that holds until the next door is cut.
  `upsert` still removes the displaced index row, because `notes` has a UNIQUE index on `dayKey`
  and `insertNote` is `@Insert(REPLACE)`: without that line the FTS row of a note the `REPLACE`
  removed would outlive it and the index would grow for ever (`A-24`). With the demotion in place
  no note is removed at all, so it now guards only the case where a note's own day changes.
  **The read and the write are one transaction** (`DEC-0062`, `NoteDao.upsertKeepingOneNotePerDay`):
  as two separate calls, two writers claiming one fresh day both saw it free and the second
  `REPLACE` destroyed the first — `A-24` reopened by the fix for `A-24`, and reachable only once
  the rule stopped living in a single main-dispatcher caller.
- **A migration demotes duplicates; it does not delete them** (`DEC-0058`). `MIGRATION_1_2` adds
  the UNIQUE index on `dayKey` and has to make one row per date give up its claim, so it sets
  `dayKey = NULL` on every one but the holder — the same rule as the runtime path, because a
  migration is that question asked about rows that already exist. **The holder is the note with
  the earliest `createdAt`, and `rowid` was the wrong key** (`DEC-0062`): `@Insert(REPLACE)`
  reassigns it and the editor autosaves every 600 ms, so in a real database the note being typed
  into carries the highest rowid and `MIN(rowid)` demoted exactly that one.
  *Until `B-088` it **deleted** those rows* — every note for that day but one, on the first launch
  after an upgrade, with no announcement: a migration runs below the repository, so no
  `NoteChange.Deleted` reached the vault mirror either, the row went, the file stayed, and nothing
  anywhere told the person a note they had dictated was gone.
  **What is still true of the demotion:** it runs below the repository, so the Markdown file keeps
  its `day:` front matter while the row has none. Nothing is lost — `SCN-011` makes the files the
  source of truth — and **the re-import no longer disagrees about the holder**: `VaultImporter`
  sorts its candidates `compareBy({ createdAt }, { id })` before writing, which is the same rule
  this migration applies, so the file that keeps the day after a reinstall is the one the
  migration would have kept. That was `B-182`, and it was closed by `REQ-055`'s reconciler
  (`9951807`); `VaultReconcilerTest` proves it with a fixture whose walk order, path order and id
  order all point at the wrong note.
- **A rebuilt search index is dropped and recreated, never migrated in place.**
  `MIGRATION_2_3` (the tokenizer), `MIGRATION_3_4` (`notindexed=noteId`) and `MIGRATION_4_5` (the
  `ё` fold) do the same three things for the same reason: the index is derived data, every row of
  it is recoverable from `notes`, and the worst case is a rebuildable index rather than a lost
  note. **The CREATE statement is copied out of the generated `N.json`, not typed** — Room
  compares it character for character at open time, on the person's headset, after the migration
  has already run.
- **`ё` and `е` are one letter to the search index, and to nothing else** (`B-132`, schema v5).
  Whisper transcribes from sound and writes `ёлка`; a person types `елка`, because that is what
  Russian keyboards and ordinary Russian writing use. Writer and reader disagree about a character
  neither of them chose and the note does not come back — indistinguishable, on screen, from the
  note not existing.
  **No tokenizer argument fixes it.** Measured with sqlite3 3.51.0 on 2026-09-22 against an FTS4
  table holding `ёлка ещё`: `MATCH 'елка'` returns **0** under plain `unicode61`, 0 under
  `remove_diacritics=1` and 0 under `remove_diacritics=2`, the most aggressive setting there is.
  SQLite's diacritic table is built for Latin, and `ё` is U+0451 — a precomposed letter with no
  combining mark to remove.
  So `SearchFold` folds it in Kotlin, on **both** sides: `NoteDao.upsert` folds what goes into
  `note_fts`, and `RoomNotesRepository.search`/`searchAny` fold the query. One side without the
  other is worse than neither, because it reverses which spelling can be found. `MIGRATION_4_5`
  re-indexes what is already stored, because otherwise an upgrade finds new notes and not old
  ones. **`notes` is never rewritten** — a note dictated as `ёлка` is stored, displayed and
  exported as `ёлка`; only the index holds the folded spelling, and every query selects `notes.*`.
  **The fold stops at `ё`**: `й`/`и` are different letters and `мой`/`мои` are different words,
  which a test pins rather than a comment. `SearchFold.sql` is the same mapping for the migration,
  which cannot call Kotlin; `MigrationTest` searches a migrated database with a Kotlin-folded
  query, so the two halves cannot drift apart in silence.
- **v5 changes no schema, only contents.** `5.json` carries the same `identityHash` as `4.json`
  (`be5f06be03e663cd28a32e264a452c87`) — the version exists so that the rebuild runs, not because
  a column moved.
- **Every schema version is exported and committed in the change that creates it** (`DEC-0036`),
  enforced by `scripts/check-schemas.sh`. `1.json` did not exist for most of this project's life,
  so `MIGRATION_1_2` — which rewrites rows — had never been executed by anything. It was
  reconstructed from `16a5fb5^` and `MigrationTest` now runs every edge from v1 to v5.

## Refuses

To write files (that is `:feature-vault`), to know about audio, or to reach the network.

## Checks

**Every count below is recomputed by `check-docs.sh` §17 and fails on a stale one** (`DEC-0053`):
four module documents carried a wrong count under three green gates before that section existed.

`TagParserTest` (6), `NotesRepositoryTest` (25: round-trip, delete, tag add and removal, transcript search, Cyrillic and prefix search, **a capitalised Russian word**, a capitalised Cyrillic tag, blank query, the daily note under a concurrent race, deletion carrying `createdAt`, change events, **no orphan index row after a `dayKey` collision**, the bounded list and its total, a bounded one-letter prefix search, the tag list not re-emitting, a tag filter that is deliberately not clipped, **the demoted note being what reaches the change stream**, **two writers claiming one fresh day unable to destroy each other**, **a fragment of a note's own id matching nothing while its words still match**, **a note dictated with `ё` found by typing `е`** and the reverse, **`й` and `и` staying different letters**, and **a fallback still saying it fell back after a reload**) and `MigrationTest` (7: v1→v2 keeps both notes, two notes for one day both survive and the later one gives up the day, every edge composes from v1 to v5 with the rebuilt index folding case, a migrated note with no transcript indexes an empty string rather than NULL, three notes for one day leave one holder and two ordinary notes, **v3→v4 stops indexing the primary key**, and **v4→v5 re-indexes an old note so `ё` is reachable by typing `е`**) — **38 JVM tests.** A planted defect in the FTS sync was watched
failing exactly the transcript-search test; `MIGRATION_3_4` rebuilt without `notindexed` was
watched failing the v3→v4 case and the v1→v4 composition.

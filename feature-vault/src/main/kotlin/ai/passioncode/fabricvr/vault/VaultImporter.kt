package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.runCatchingCancellable
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NotesRepository
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a pass over the vault's Markdown put into the database, and what it could not. */
data class ImportOutcome(val imported: Int, val skipped: Int)

/**
 * Turns the vault's Markdown back into rows.
 *
 * `CONTEXT.md` calls the database *"an index over the vault, never the source of truth"*. Until
 * this class existed that was an aspiration rather than a fact: `MarkdownSerializer.parse` was
 * called by nothing but its own test, so a database that failed to open meant lost notes **with
 * the Markdown still sitting on disk**.
 *
 * **What the exported archive is, precisely.** `DEC-0026`'s zip is a restore path because
 * [VaultZipImporter] unpacks it back under `filesDir/vault/notes/` and this class reads what
 * lands there. `filesDir` is app-private and `run-as` is unavailable on a release build, so
 * before that importer existed the sentence *"unzip it back into filesDir"* described something
 * **nobody could do** — the archive was a backup with no way back in (`H4`). It is a route now,
 * and it is the only one.
 *
 * **What it is not.** It does not recover a database that will not open. A migration that throws
 * leaves `Room.databaseBuilder` raising on every launch, and nothing in this module ever gets a
 * turn; that claim stood in this comment for three releases and was never true. Recovering from
 * a refused migration would mean deleting the database file and starting from the vault, which is
 * a decision about destroying the person's index and nobody has taken it. `MigrationTest` is the
 * answer this project actually has: every edge is executed before it ships.
 *
 * **It is not a gate any more, either.** `importIfEmpty` ran only when the database was empty,
 * on the reasoning that a merge of two sources of truth is worse than the failure it recovers
 * from. That reasoning is intact; the implementation of it was `recent(1).isNotEmpty()`, which is
 * not "the vault has been imported" but "something is in the table" — so an import killed halfway
 * was never finished, on any later launch (`H6`). [VaultReconciler] decides what to import by
 * **id**, one note at a time, which is the same rule made true: a note already in the table is
 * not touched, and a note that is not is not a merge.
 */
class VaultImporter(
    private val vault: Vault,
    private val notes: NotesRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Imports every note in the vault whose id is not already known.
     *
     * @param known ids the database already holds. Computed once by the caller, because asking
     *   the database per file is one query per note on the launch that can least afford it.
     * @param skip ids the caller knows are on their way out — a deletion the vault still owes
     *   (see [RemovalJournal]). Importing one would resurrect a note the person deleted.
     *
     * **The file's name answers the question before the file is opened** (`B-218`). This read and
     * parsed every `.md` in the vault and only then asked whether `parsed.id` was known — on the
     * normal launch, where the database holds every note there is, that is the whole archive read
     * and parsed before the first paint, to learn what the directory listing already said.
     * [FileVault.pathFor] writes `<id>.md` and [VaultZipImporter] refuses an archive entry that is
     * not named that way, so for every file this product has ever written the name **is** the id.
     *
     * **A name that is not an id is still opened, and that is the rule rather than a leniency.** A
     * person can drop a Markdown file into their own vault with Obsidian, and the vault is theirs;
     * [VAULT_ID_SHAPE] is only the test for *"may I trust this name without reading the file"*.
     *
     * **What the shortcut trades away**, stated because it will not be visible later: a file
     * NAMED for a known id whose front matter claims a DIFFERENT id is now skipped where it used
     * to be imported. That file is a vault that disagrees with itself — nothing in this module
     * writes one — and skipping it leaves both the database row and the file exactly as they were,
     * which is the safer of the two readings of an inconsistency nobody can resolve.
     */
    suspend fun importMissing(known: Set<String>, skip: Set<String> = emptySet()): Result<ImportOutcome> =
        withContext(io) {
            runCatchingCancellable {
                val root = File(vault.root, NOTES)

                var skipped = 0
                val candidates = mutableListOf<Pair<File, Note>>()
                // The walk stays a sequence: the launch this runs on is the one that can least
                // afford a list of every file in the vault held while each one is decided. What
                // IS collected is the notes that will be imported, which on a normal launch is
                // none and on a restore is the work itself.
                root.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(MARKDOWN) }
                    .forEach { file ->
                        val named = file.name.removeSuffix(MARKDOWN)
                        if (VAULT_ID_SHAPE.matches(named) && (named in known || named in skip)) {
                            return@forEach
                        }
                        val text = runCatching { file.readText() }.getOrElse {
                            // Named by path and never by content: a note's text is the person's.
                            Log2.w("vault.import.unreadable", "path" to file.relativeTo(root).path)
                            skipped++
                            return@forEach
                        }
                        val parsed = MarkdownSerializer.parse(text)
                        if (parsed == null) {
                            Log2.w("vault.import.unparsed", "path" to file.relativeTo(root).path)
                            skipped++
                            return@forEach
                        }
                        if (parsed.id in known || parsed.id in skip) return@forEach
                        candidates += file to parsed
                    }
                if (candidates.isEmpty()) {
                    return@runCatchingCancellable ImportOutcome(0, skipped)
                }

                Log2.i("vault.import.start", "files" to candidates.size)
                var imported = 0
                // **Oldest first, and the order is a rule rather than tidiness** (`B-182`).
                // `NotesRepository.upsert` gives the day to whoever holds it and demotes the
                // arrival, so whichever of two files claiming one `day:` is written FIRST keeps
                // it. That used to be decided by walk order — `readdir` order, which is nothing —
                // and `MIGRATION_1_2` manufactures the situation: it demotes a duplicate row
                // below the repository, so the file keeps a `day:` the row no longer has.
                // `createdAt` is the rule everywhere else this question is asked (`DEC-0058`,
                // `DEC-0062`) and it is the rule here.
                candidates.sortedWith(compareBy({ it.second.createdAt }, { it.second.id }))
                    .forEach { (file, parsed) ->
                        // **The stored `audioPath` is re-derived, not trusted.** It is an absolute
                        // path written by whichever install created the note, and after a
                        // reinstall — which is the situation this class exists for — it points
                        // into a `filesDir` that no longer exists. The sibling `.wav` is where
                        // the recording actually is.
                        val sibling = vault.audioPathFor(parsed).takeIf { it.isFile }
                        // `M3`: through `upsert` this stamped `updatedAt = now()`, so every
                        // recovered note came back "updated today" and the mirror wrote that over
                        // the file's own `updated:` — the original destroyed by the operation
                        // that existed to save it.
                        // **Never later than now** (seam verification of `DEC-0093`): a stamp from a
                        // clock ahead of this headset's kept every later reconcile rewriting the file,
                        // because the rewrite's own mtime stayed older than the row.
                        val stamped = parsed.copy(
                            audioPath = sibling?.absolutePath,
                            updatedAt = minOf(parsed.updatedAt, System.currentTimeMillis()),
                        )
                        notes.upsertPreservingTimestamps(stamped)
                            .onSuccess { imported++ }
                            .onFailure {
                                Log2.w("vault.import.rejected", "path" to file.relativeTo(root).path)
                                skipped++
                            }
                    }
                Log2.i("vault.import.done", "imported" to imported, "skipped" to skipped)
                ImportOutcome(imported, skipped)
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(VaultException(AppError.Storage("vault.import", it))) },
            )
        }

    // **`importIfEmpty` was deleted here** (`REQ-055`, `H6`). It was a compatibility shim kept
    // only so `:app` compiled across the module boundary while `feature-vault` and `:app` were
    // built on separate branches, and its own `@Deprecated` message said to delete it and its
    // one call site in the integration commit. That call site is gone: `Graph.init` now starts
    // `VaultReconciler` and holds the `Deferred` the first UI read awaits. Leaving the shim
    // would leave a second, worse door into the same work — `recent(1)` as a gate is the finding.

    private companion object {
        const val NOTES = "notes"
        const val MARKDOWN = ".md"
    }
}

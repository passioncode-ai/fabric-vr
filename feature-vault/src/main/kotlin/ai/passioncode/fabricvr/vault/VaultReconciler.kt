package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.runCatchingCancellable
import ai.passioncode.fabricvr.notes.NotesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** What one reconcile changed. All zeroes is the normal answer and is not a failure. */
data class ReconcileSummary(
    val imported: Int,
    val remirrored: Int,
    val removalsRetried: Int,
    val skipped: Int,
)

/**
 * Brings the index and the files it indexes back into agreement, in both directions.
 *
 * `CONTEXT.md` says the vault is the source of truth and the database is an index over it. Until
 * this class, **nothing ever compared them** (`H5`). Three states could be reached and none of
 * them healed:
 *
 * - **A row with no file.** The process died between the Room commit and `vault.write`, or the
 *   write failed. `VaultMirror` remembered that in a `MutableMap` — in memory — so after a
 *   restart the out-of-sync count read zero, the person was told nothing, and the export, the
 *   product's only route off the headset, skipped the note in silence.
 * - **A file with no row.** A reinstall, a lost database, or an import that was killed halfway.
 *   `VaultImporter.importIfEmpty` gated on `recent(1).isNotEmpty()`, which is not *"the vault has
 *   been imported"* but *"something is in the table"* — so a partial import stayed partial for
 *   ever (`H6`).
 * - **A file whose row is gone.** `vault.remove` failed and was retried by nothing (`M7`); the
 *   next rebuild then imported the note the person had deleted.
 *
 * **Two of the three re-derive themselves and the third does not**, which is the whole design
 * decision here. A row with no file and a file with no row are both visible by comparing the two
 * sides. A file whose row is gone is *indistinguishable* from a file that was never imported —
 * the vault holds no evidence of the difference — so deriving it would resurrect every failed
 * deletion. That one case, and only that one, is written down: [RemovalJournal].
 *
 * **Idempotent by id, never by position.** Every decision is per note, keyed by its id, so
 * running this twice changes nothing the second time and running it after an interruption
 * finishes exactly what is left.
 *
 * **Order matters.** Deletions the vault owes are settled first, so a file about to be removed is
 * not imported on the way past. Then files with no row. Then rows with no file — last, because
 * the import has just created rows and they must not be walked as if their files were missing.
 */
class VaultReconciler(
    private val vault: Vault,
    private val notes: NotesRepository,
    private val importer: VaultImporter = VaultImporter(vault, notes),
    private val journal: RemovalJournal = RemovalJournal(vault.root),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Runs the reconcile on [scope] and hands back something the first UI read can wait for.
     *
     * `H6`'s second half: the import was launched and never awaited, so `dailyNote(today)` could
     * win the race, create a note for a day the vault already had one for, and the import's copy
     * would then arrive and be demoted — a duplicate for today on the launch after a restore.
     * A [Deferred] is the smallest thing that lets the composition wait without the reconcile
     * holding up `Application.onCreate`.
     */
    fun start(scope: CoroutineScope): Deferred<Result<ReconcileSummary>> = scope.async { reconcile() }

    suspend fun reconcile(): Result<ReconcileSummary> = withContext(io) {
        runCatchingCancellable {
            var removalsRetried = 0
            val stillOwed = mutableSetOf<String>()

            journal.pending().forEach { removal ->
                // **Undo overrules the journal.** A pending removal whose row is back is a
                // deletion the person took back — `NotesViewModel.undoDelete` restores the row
                // and `Vault.restore` puts the files back, and replaying the removal here would
                // delete the note a second time, from a queue nobody can see.
                if (notes.get(removal.id) != null) {
                    journal.clear(removal.id)
                    return@forEach
                }
                vault.remove(removal.id, removal.createdAt).fold(
                    onSuccess = {
                        journal.clear(removal.id)
                        removalsRetried++
                    },
                    onFailure = {
                        // Still owed. It stays in the journal for the next launch and is kept
                        // out of the import below, or the note the person deleted comes back.
                        stillOwed += removal.id
                        Log2.w("vault.reconcile.remove_failed", "note" to removal.id)
                    },
                )
            }

            val known = notes.identities()
            val outcome = importer.importMissing(
                known = known.mapTo(mutableSetOf()) { it.id },
                skip = stillOwed,
            ).getOrThrow()

            var remirrored = 0
            known.forEach { identity ->
                val file = vault.pathFor(identity.id, identity.createdAt)
                // **Missing, or older than the row's last edit** (`B-240`). Only the first was
                // checked, so an edit whose mirror write died with the process left the old `.md`
                // for ever and every export shipped it. A stat, not a read: the mirror writes after
                // the row is stamped, so a healthy file is never older than its row, and a clock
                // step costs at most one redundant rewrite.
                if (file.isFile && file.lastModified() >= identity.updatedAt) return@forEach
                // Read one at a time, and only for the notes that need writing. On the normal
                // launch this loop reads nothing at all.
                val note = notes.get(identity.id) ?: return@forEach
                // **A live note's files do not belong in the trash** — `VaultMirror.write`'s rule
                // (`B-241`), applied here too: an Undo followed by a death after the mirror ran the
                // delete and before it ran the re-write leaves the row back and its files trashed,
                // and rewriting only the `.md` left the recording for the purge.
                if (vault.trashedFiles(identity.id).isNotEmpty()) {
                    vault.restore(identity.id, identity.createdAt)
                        .onFailure { Log2.w("vault.reconcile.untrash_failed", "note" to identity.id) }
                }
                vault.write(note).fold(
                    onSuccess = { remirrored++ },
                    onFailure = { Log2.w("vault.reconcile.write_failed", "note" to identity.id) },
                )
            }

            if (outcome.imported + remirrored + removalsRetried > 0) {
                Log2.i(
                    "vault.reconcile.done",
                    "imported" to outcome.imported,
                    "remirrored" to remirrored,
                    "removed" to removalsRetried,
                    "skipped" to outcome.skipped,
                )
            }
            ReconcileSummary(
                imported = outcome.imported,
                remirrored = remirrored,
                removalsRetried = removalsRetried,
                skipped = outcome.skipped,
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(VaultException(AppError.Storage("vault.reconcile", it))) },
        )
    }
}

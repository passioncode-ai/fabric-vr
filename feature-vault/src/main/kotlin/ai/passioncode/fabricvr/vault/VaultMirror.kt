package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.runCatchingCancellable
import ai.passioncode.fabricvr.common.toAppError
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Follows every note change into the vault. A mirror failure never fails the note write — the note
 * is already saved — so the failure surfaces here instead, and Settings shows how many notes are not
 * mirrored yet.
 *
 * Failures are held **per note** rather than as one last error: a later success for a different note
 * used to clear the record of the one that failed, which is how "the vault is out of sync" became
 * invisible the moment anything else was written.
 */
class VaultMirror(
    private val repository: NotesRepository,
    private val vault: Vault,
    /**
     * Where a deletion is written down before it is attempted (`H5`, `M7`).
     *
     * Both maps below live in memory, and the process dying is the case this mirror's failures
     * are most often about — so after a restart the failure count read zero and a note that never
     * reached the vault looked mirrored. A write that failed re-derives itself, because a row
     * with no file is visible by comparison; a **deletion** that failed does not, and only that
     * is journalled. See [RemovalJournal] for the whole argument.
     */
    private val journal: RemovalJournal = RemovalJournal(vault.root),
) {
    private val _failures = MutableStateFlow<Map<String, AppError>>(emptyMap())
    val failures: StateFlow<Map<String, AppError>> = _failures.asStateFlow()

    private val pending = mutableMapOf<String, Note>()

    /** `M7`: [retryFailed] re-ran writes and nothing else, so a failed removal was retried by nobody. */
    private val owed = mutableMapOf<String, Long>()
    private val lock = Mutex()

    /**
     * **Nothing that happens to one change may end the collector** (`B-209`).
     *
     * This was a bare `launch` around a bare `collect`. `vault.write` and `vault.remove` both
     * return a `Result` and throw nothing, so the shape looked safe — and the journal, added later
     * for `H5`, does throw: `RemovalJournal.persist` goes through `writeFileAtomically`, which
     * raises an `IOException` on a full disk. That throw left `remove`, left the `collect` and
     * left this `launch`, whose scope (`Graph.scope`) had no `CoroutineExceptionHandler` — so it
     * reached the process's uncaught route. One failed deletion killed the app, and in the process
     * that survived long enough to notice, it killed **the mirror**: every note written afterwards
     * looked mirrored and was not, because nothing was collecting any more.
     *
     * The guard is per change rather than around the collect, which is the whole point: a failure
     * belongs to the change that caused it and the next one is still mirrored. It logs rather than
     * reporting, for the reason `app/ui/Guarded.kt` records — there is no screen behind this
     * coroutine — and [failures] already carries what Settings shows about writes and removals
     * that the vault itself refused.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            repository.observeChanges().collect { change ->
                runCatchingCancellable {
                    when (change) {
                        is NoteChange.Upserted -> write(change.note)
                        is NoteChange.Deleted -> remove(change.id, change.createdAt)
                    }
                }.onFailure { Log2.e("vault.mirror.escaped", it, "kind" to it::class.java.simpleName) }
            }
        }
    }

    /**
     * **`update`, never `value = value ± x`.** All four of these were read-modify-writes across
     * `Dispatchers.Default` and Main: the mirror's collector writes them, and Settings' *Retry*
     * writes them from another thread at the same time. A lost update erases a failure the mirror
     * recorded microseconds earlier, so `vaultOutOfSync` under-reports and **a note that never
     * reached the vault looks mirrored** (`I-06`). `pending` has always had the lock; this map
     * did not, and the two are updated in the same breath.
     */
    private suspend fun write(note: Note) {
        // **A live note's files do not belong in the trash** (`B-241`). A quick *Undo* restores
        // from the trash before this collector has put anything there, fails, and writes the note
        // back; this collector then runs the delete it was still holding and trashes the recording.
        // It sees the delete and the re-write in order, so it is where the two meet: a note being
        // written is alive, and whatever the trash holds under its id is its own.
        if (vault.trashedFiles(note.id).isNotEmpty()) {
            vault.restore(note.id, note.createdAt)
                .onSuccess { Log2.i("vault.mirror.untrashed", "note" to note.id) }
                .onFailure { Log2.w("vault.mirror.untrash_failed", "note" to note.id) }
        }
        vault.write(note).fold(
            onSuccess = {
                // **A note written again voids any removal still owed for it** (`B-242`). A removal
                // that failed, then *Undo* — which re-writes this same note — left `owed` holding
                // it, and [retryFailed] then trashed the restored note's files. The journal entry
                // goes with it, or the next launch's reconcile would owe the same removal.
                val wasOwed = lock.withLock { pending.remove(note.id); owed.remove(note.id) != null }
                if (wasOwed) {
                    runCatchingCancellable { journal.clear(note.id) }
                        .onFailure { Log2.w("vault.journal.clear_failed", "note" to note.id) }
                }
                _failures.update { it - note.id }
            },
            onFailure = { failure ->
                lock.withLock { pending[note.id] = note }
                _failures.update { it + (note.id to failure.asAppError()) }
            },
        )
    }

    /**
     * **Recorded before the attempt, cleared after the success.**
     *
     * A record written only when the removal fails is lost to exactly the failure it exists to
     * survive — the process dying between the Room commit and the vault write. **This call alone
     * never covered that window**, although this comment said so until `B-239`: it runs when the
     * collector reaches the `Deleted` change, which is after the Room commit. What covers it is
     * `RoomNotesRepository`'s `beforeDelete`, which `Graph` points at this same journal and which
     * runs before the row goes (`DEC-0089`); this record is the idempotent second copy, kept for a
     * repository built without the hook. The cost of
     * recording first is a spurious entry when the removal succeeded and the process died before
     * the clear, and that costs nothing: [VaultReconciler] checks the row is really gone first,
     * and removing a note the vault no longer holds is already a success.
     *
     * **Both journal calls degrade rather than throw** (`B-209`), and what that trades away is
     * worth stating rather than leaving to be discovered. `RemovalJournal.persist` raises an
     * `IOException` on a full disk, and a journal that could not be written means this deletion is
     * not durable: if the process dies before `vault.remove` lands, the next launch has no record
     * that a removal was owed and [VaultReconciler] cannot retry it — the file stays and the note
     * comes back on the following rebuild. That is a bounded, visible loss of ONE deletion. The
     * alternative, which is what the code did, was to let the throw end the collector and the
     * process: every note written after it silently unmirrored, and the disk still full.
     */
    private suspend fun remove(id: String, createdAt: Long) {
        runCatchingCancellable { journal.record(id, createdAt) }
            .onFailure { Log2.w("vault.journal.record_failed", "note" to id) }
        vault.remove(id, createdAt).fold(
            onSuccess = {
                runCatchingCancellable { journal.clear(id) }
                    .onFailure { Log2.w("vault.journal.clear_failed", "note" to id) }
                lock.withLock { pending.remove(id); owed.remove(id) }
                _failures.update { it - id }
            },
            onFailure = { failure ->
                lock.withLock { owed[id] = createdAt }
                _failures.update { it + (id to failure.asAppError()) }
            },
        )
    }

    /**
     * One mirror write, for the test that proves `I-06`'s race is closed.
     *
     * `write` is private because nothing outside this class decides when a note is mirrored — the
     * collector does. The race it is about needs a hundred of them at once from real threads,
     * which no amount of driving `observeChanges` can arrange deterministically.
     */
    internal suspend fun mirrorForTest(note: Note) = write(note)

    /**
     * Do again whatever did not reach the vault. Called by the Settings banner's Retry.
     *
     * `M7`: this re-ran **writes only**, so a `remove` that failed was retried by nothing — the
     * orphan `.md` stayed and the next rebuild imported a note the person had deleted. Both
     * directions of the mirror can fail and both are retried; the banner's count already
     * included the removals, so *Retry* was answering for work it did not do.
     */
    suspend fun retryFailed() {
        val notes = lock.withLock { pending.values.toList() }
        notes.forEach { write(it) }
        val deletions = lock.withLock { owed.toMap() }
        deletions.forEach { (id, createdAt) -> remove(id, createdAt) }
    }

    private fun Throwable.asAppError(): AppError =
        (this as? VaultException)?.error ?: toAppError("vault")
}

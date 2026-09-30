package ai.passioncode.fabricvr.vault

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One lock per file in the vault, addressed by path (`B-210`).
 *
 * **The defect.** [writeFileAtomically] always names its scratch `<name>.tmp` beside the target,
 * and nothing serialised two writers of one note: `VaultMirror`'s collector writes on the
 * application scope, `VaultMirror.retryFailed` snapshots under its mutex and then writes
 * **outside** it, and `VaultReconciler.reconcile` re-mirrors at startup while the collector is
 * already running. Two `FileOutputStream`s on one scratch path, then a rename of whatever was left
 * of them over the person's note.
 *
 * **Why not a unique scratch name per write**, which is the other obvious answer. The single
 * `<name>.tmp` is load-bearing: `FileVault.write` documents that an interrupted write leaves
 * exactly one identifiable artefact, *cleared by the next write of the same note*, and
 * [VaultExporter] and every `.md` walk in this module skip it by that suffix. Unique names would
 * trade a corrupt file for scratch files that accumulate for ever after every hard stop — on a
 * device whose storage growth is already a finding (`G-02`, `G-16`) — and the recovery that clears
 * them does not exist. Serialising the writers keeps the artefact contract and costs nothing on
 * the path that matters, because two writers of ONE note is already the rare case.
 *
 * **Why keyed by path and not by note id.** The id is the mirror's vocabulary and not the
 * reconciler's or the journal's, and the thing being protected is a **file**. A lock keyed by id
 * in `VaultMirror` would have left `VaultReconciler.reconcile`'s own `vault.write` — one of the
 * two writers in the finding — outside it entirely.
 *
 * **It is the registry [RemovalJournal] already had, moved out and shared.** That class carried
 * its own `ConcurrentHashMap<String, Mutex>` with a comment explaining that one lock per instance
 * would be the `I-06` lost update again, because `Graph` builds the mirror's journal and the
 * reconciler's separately. Two registries keyed the same way is that argument with one more level
 * of indirection, so there is one.
 *
 * **The lock is taken by whoever owns the read-modify-write, and [writeFileAtomically] never takes
 * it itself.** That is not an oversight and must not be "fixed": [RemovalJournal] reads the
 * journal, edits the list and writes it back as one operation, so it holds this file's lock across
 * all three — and a `Mutex` is not reentrant, so a locking `writeFileAtomically` would deadlock
 * against the journal on its own path, every time.
 *
 * Entries are never removed: one per file this process has written, which is bounded by the notes
 * the person touched in one session. A `Mutex` with no waiters is a few words; reference-counting
 * them would be more machinery than the thing it manages.
 */
internal object VaultFileLocks {

    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forPath(file: File): Mutex = locks.getOrPut(file.absolutePath) { Mutex() }

    suspend fun <T> withFile(file: File, block: suspend () -> T): T =
        forPath(file).withLock { block() }
}

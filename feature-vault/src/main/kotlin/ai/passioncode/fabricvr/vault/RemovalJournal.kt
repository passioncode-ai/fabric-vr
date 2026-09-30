package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.common.Log2
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A deletion the database has already made and the vault has not. */
data class PendingRemoval(val id: String, val createdAt: Long)

/**
 * The deletions the vault still owes, written down where a restart can read them.
 *
 * **Why a journal and not a diff.** Two of the three halves of `H5` re-derive themselves: a row
 * with no file is visible by comparing the two, and so is a file with no row. The third does not.
 * A file whose row is gone is *indistinguishable* from a file that was never imported — one must
 * be deleted and the other must be imported, and the vault holds no evidence telling them apart.
 * Re-deriving would therefore resurrect every note whose `vault.remove` failed, on the launch
 * after the failure, which is worse than the silence it replaced. So the one thing that cannot be
 * derived is the one thing written down.
 *
 * **Recorded before the attempt, cleared after the success.** A record written only when the
 * remove fails is lost to exactly the failure it exists to survive — the process dying between
 * the Room commit and the vault write. The cost of recording first is a spurious entry when the
 * removal did succeed and the process died before the clear; the reconciler answers that by
 * checking the row is really gone, and `vault.remove` of a note the vault never held is already
 * a success.
 *
 * Plain lines, `<id>\t<createdAt>`, because the format has to be readable by a person looking at
 * their own vault with a text editor and by a version of this app that does not exist yet. A
 * malformed line is dropped rather than fatal: this file is a hint about work to redo, and
 * refusing to start over a corrupt hint would turn a recoverable state into a brick.
 *
 * It lives at the vault **root**, beside `notes/` and `.trash/`, dot-prefixed so Obsidian hides
 * it — and therefore outside everything [VaultExporter] walks, which is correct: a deletion this
 * headset owes is not part of the archive somebody restores onto another one.
 */
class RemovalJournal(
    root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val file = File(root, NAME)

    /**
     * **One lock per file, not per instance.** The mirror records into this journal and the
     * reconciler clears from it, and `Graph` builds them separately, so two objects address one
     * file — a read-modify-write on each side with a private mutex is precisely the lost update
     * of `I-06`, one layer down and with a deletion as the thing lost.
     *
     * The registry it comes from is [VaultFileLocks], which is this map moved out and shared with
     * the note writes (`B-210`) — two registries keyed by the same absolute paths would be the
     * same argument with one more level of indirection. **The lock is held across [read] and
     * [persist] together**, which is why [writeFileAtomically] must not take it as well: a `Mutex`
     * is not reentrant and this journal would deadlock on its own path.
     */
    private val lock: Mutex get() = VaultFileLocks.forPath(file)

    /** Called **before** the vault is asked to delete anything. Idempotent per id. */
    suspend fun record(id: String, createdAt: Long) = withContext(io) {
        lock.withLock {
            val kept = read().filterNot { it.id == id }
            persist(kept + PendingRemoval(id, createdAt))
        }
    }

    /** Called once the vault has actually let the files go — or once the deletion is moot. */
    suspend fun clear(id: String) = withContext(io) {
        lock.withLock {
            val current = read()
            val kept = current.filterNot { it.id == id }
            if (kept.size != current.size) persist(kept)
        }
    }

    /** What the vault still owes. Empty on the overwhelming majority of launches. */
    suspend fun pending(): List<PendingRemoval> = withContext(io) {
        lock.withLock { read() }
    }

    private fun read(): List<PendingRemoval> {
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines() }.getOrElse {
            Log2.w("vault.journal.unreadable")
            return emptyList()
        }.mapNotNull { line ->
            val parts = line.split('\t')
            val at = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size != 2 || parts[0].isBlank() || at == null) null
            else PendingRemoval(parts[0], at)
        }
    }

    private fun persist(entries: List<PendingRemoval>) {
        if (entries.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        // The same temp-and-rename as a note (`M2`). A journal half-written by a power cut is a
        // list of deletions with one truncated line in it, and this one is read on the launch
        // after exactly that kind of stop.
        writeFileAtomically(file, entries.joinToString("\n") { "${it.id}\t${it.createdAt}" } + "\n")
    }

    private companion object {
        const val NAME = ".pending-removals"
    }
}

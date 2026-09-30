package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.runCatchingCancellable
import ai.passioncode.fabricvr.notes.Note
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The user-owned folder of Markdown files every note is mirrored into. */
/** What the recordings cost, and — after a sweep — whose they were. */
data class AudioUsage(val count: Int, val bytes: Long, val noteIds: Set<String> = emptySet())

interface Vault {
    val root: File
    fun pathFor(note: Note): File

    /**
     * Where the note with this id and creation time lives, without needing the note.
     *
     * The same pair [remove] has always taken, and for the same reason: the folder is derived
     * from `createdAt` and the filename from `id`, so those two are the whole address. The
     * reconciler asks this of every row in the database and reading each note's text to ask it
     * would be several megabytes of bodies to answer a question about names.
     *
     * The default routes back through [pathFor] with a note that carries nothing but the pair,
     * which is exactly as correct as this interface's contract — *the address is the id and the
     * creation time* — and no more. An implementation whose path depended on a note's text would
     * be breaking that contract, not this default. [FileVault] answers directly.
     */
    fun pathFor(id: String, createdAt: Long): File =
        pathFor(Note(id = id, title = "", body = "", createdAt = createdAt, updatedAt = createdAt))

    /** Where this note's recording belongs once the note exists. */
    fun audioPathFor(note: Note): File

    /**
     * Every recording [note] keeps, the current one first and the earlier ones newest first
     * (`B-254`, `DEC-0090`). The default knows only the current file, which is all a vault without
     * earlier recordings has; [FileVault] adds the `<id>~<stamp>.wav` siblings.
     */
    fun recordingsOf(note: Note): List<File> = listOfNotNull(audioPathFor(note).takeIf { it.isFile })

    suspend fun write(note: Note): Result<File>

    /**
     * Moves a recording into the note's own folder and reports where it landed.
     *
     * A dictation must exist in exactly one place. The recorder has to write somewhere before the
     * note exists, so it writes to the app's private scratch; adopting it afterwards is what keeps
     * the headset from holding two copies of every spoken note in storage the person cannot browse.
     * Called **before** the note is saved, so the note records the final path once and no later
     * write can point at a file that has since moved.
     */
    suspend fun adoptAudio(note: Note, source: File): Result<File>
    /**
     * Moves a note's files aside. **It does not unlink**: the only Undo this product offers used to
     * restore the database row and leave the recording deleted, so a note came back pointing at a
     * file that was gone and *Transcribe again* answered with a `FileNotFoundException`.
     *
     * Removing a note the vault never held is a **success**, not a failure: a note whose mirror
     * write failed was never on disk, and reporting that would surface a message about a file the
     * person never had.
     */
    suspend fun remove(id: String, createdAt: Long): Result<Unit>

    /** Puts back what [remove] took, for as long as the trash still holds it. */
    suspend fun restore(id: String, createdAt: Long): Result<Unit>

    /** What the trash currently holds for one note. Empty when there is nothing to restore. */
    fun trashedFiles(id: String): List<File>

    /**
     * Empties the trash of everything moved there longer ago than [olderThanMillis].
     *
     * Retention is not optional: a trash nothing empties is the unbounded voice archive of `G-02`
     * with an extra directory. `TRASH_RETENTION_MS` is the shipped value and the reasoning is on
     * it.
     */
    suspend fun purgeTrash(olderThanMillis: Long = TRASH_RETENTION_MS): Result<Int>

    /**
     * How much of the vault is recordings. Walks the tree, so it suspends onto the IO dispatcher.
     *
     * The product had no such number anywhere — not in Settings, not in the documents, not in a
     * decision — while writing about 1 MB per thirty seconds of speech and keeping it for ever
     * (`G-02`). A person cannot be told what they are storing if nothing can count it.
     */
    suspend fun audioUsage(): Result<AudioUsage>

    /**
     * Deletes the recording and **keeps the note**.
     *
     * Distinct from [remove], which deletes both: they were one operation, so *"delete the
     * recording"* had to mean *"delete the note"*. Two different intentions with one verb.
     */
    suspend fun removeAudio(note: Note): Result<Unit>

    /**
     * Deletes every recording under `notes/` last modified before [cutoff], and names the notes
     * it cleared so the caller can clear their `audioPath`.
     *
     * **It never touches `.trash`.** [remove] moves a note's files with `renameTo`, which
     * preserves mtime — so a recording deleted yesterday, written a hundred days ago, would be
     * destroyed by a ninety-day cutoff *inside its own seven-day undo window*. The trash has its
     * own retention and [purgeTrash] owns it.
     */
    suspend fun sweepAudio(cutoff: Long): Result<AudioUsage>
}

/**
 * @param zone the zone the year/month folder is derived in. It defaults to **UTC** rather than the
 * device's zone because the folder is a shelf, not a fact: a traveller crossing a month boundary
 * would otherwise write a note into one folder and look for it in another.
 */
/**
 * How long a deleted note stays restorable.
 *
 * Seven days: one working week, which is how long "I deleted that by mistake" plausibly takes to
 * notice for someone dictating daily, and it bounds the trash at roughly a fiftieth of the archive
 * it shadows. Thirty days is what people expect of a desktop trash and would cost proportionally
 * more on a device whose storage growth is already a finding (`G-02`, `G-16`). There is no
 * measurement that settles this — it is a judgement, and this is the one that was made.
 */
const val TRASH_RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * The suffix of the scratch file [FileVault.write] commits a note from.
 *
 * Shared rather than private because the artefact it names is visible to two other walks in this
 * module: [VaultExporter] must not put one in a person's archive, and the reconciler must not read
 * one as a note. A crash is the only way one outlives a write, and the next write of that note
 * clears it.
 */
internal const val VAULT_TMP_SUFFIX = ".tmp"

/**
 * The shape of a note's identity, which is also the shape of its file's name.
 *
 * [FileVault.pathFor] writes `<id>.md`, and [VaultZipImporter] refuses an archive entry whose name
 * is not this, on the reasoning that *"the filename is the note's identity"*. Shared rather than
 * private to either because [VaultImporter] now reads a file's name as an id before deciding
 * whether to open it (`B-218`) — one rule about what a note's file is called, stated once, so the
 * importer's shortcut and the archive's refusal cannot drift apart.
 *
 * It is deliberately NOT a claim that a file must be named this way to be a note: an importer that
 * believed that would drop a Markdown file a person wrote in Obsidian and dropped into the vault.
 * It is only the test for *"is this name an id I can trust without reading the file"*.
 */
internal val VAULT_ID_SHAPE =
    Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

/**
 * What separates a note's id from the stamp of one of its **earlier** recordings:
 * `<id>~<epoch-millis>.wav` (`B-238`, `DEC-0090`).
 *
 * A note row carries one `audioPath`, and a second dictation into an open note used to delete the
 * first recording to make room for it. Until a note can list several recordings, the earlier one is
 * kept beside the current under this name: the vault owns it, the export carries it, the archive
 * importer accepts it, a delete trashes it with the note and *Undo* brings it back. `~` because it
 * cannot appear in a UUID and is legal in every filesystem the vault is copied to.
 */
internal const val EARLIER_RECORDING_SEP = '~'

/** The window `FileVault.sameBytes` compares in. */
private const val COMPARE_BLOCK = 64 * 1024

/** `<uuid>~<digits>` — the base name of an earlier recording. Only ever a `.wav`. */
internal val EARLIER_RECORDING_SHAPE = Regex(VAULT_ID_SHAPE.pattern + EARLIER_RECORDING_SEP + """\d+""")

/** Whether [file] is one of note [id]'s earlier recordings. */
internal fun isEarlierRecordingOf(file: File, id: String): Boolean =
    file.name.startsWith("$id$EARLIER_RECORDING_SEP") && file.name.endsWith(".wav")

/**
 * Write [text] to [file] so that a reader sees the previous contents or the new ones, never a
 * prefix of either (`M2`).
 *
 * Three steps, and the order is the mechanism: write the sibling scratch file, `fsync` it, rename
 * it over the target. Without the `fsync` the rename can reach the disk before the bytes it
 * commits, which is the same loss with a longer fuse. Rename within a directory is atomic on
 * every filesystem Android ships.
 *
 * [commit] is the rename, injectable because the instant this function is about — the machine
 * stopping between the last byte and the rename — cannot be produced from a JVM test, while the
 * order can. A commit that returns **false** is a filesystem refusing the rename; copying the
 * bytes over is the same outcome and is **not atomic**, which is stated rather than hidden:
 * losing the note to a filesystem quirk would be worse, and on the device's own `filesDir` the
 * rename is the path that runs.
 *
 * **It does not lock, and its callers do** (`B-210`). Two writers of one file would otherwise
 * share this scratch path; [VaultFileLocks] is where that is closed, and its KDoc records why the
 * lock cannot live in here — [RemovalJournal]'s read-edit-write holds the same file's lock across
 * all three steps, and a `Mutex` is not reentrant.
 *
 * **A directory standing where the file belongs is refused, and the check is not decoration.**
 * `rename(2)` returns `EISDIR` on Linux, and on Darwin it **succeeds** against an empty
 * directory — measured on this machine 2026-09-21, `File.renameTo` returned `true` and the
 * directory was gone. `File.copyTo(overwrite = true)` deletes one outright. So both of the
 * obvious spellings of "commit the scratch file" will, on some platform, destroy a directory
 * rather than report that something is badly wrong with the vault; refusing up front is the only
 * behaviour that is the same everywhere. It is also the behaviour this module owes: an
 * `IOException` reaches `VaultMirror`, which counts it and tells the person the vault is out of
 * sync — nothing about the note is lost, which is more than can be said for the directory.
 */
internal fun writeFileAtomically(
    file: File,
    text: String,
    commit: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    if (file.isDirectory) {
        throw java.io.IOException("a directory stands where ${file.name} belongs")
    }
    val scratch = File(file.parentFile, file.name + VAULT_TMP_SUFFIX)
    java.io.FileOutputStream(scratch).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
        out.fd.sync()
    }
    if (!commit(scratch, file)) {
        try {
            scratch.inputStream().use { source ->
                java.io.FileOutputStream(file).use { out -> source.copyTo(out) }
            }
        } finally {
            // A refused rename is not a crash, so it must not leave the artefact a crash does.
            scratch.delete()
        }
    }
}

class FileVault(
    override val root: File,
    private val zone: ZoneId = ZoneOffset.UTC,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable so the retention sweep can be tested without waiting seven days. */
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * The commit step of [write]'s temp-and-rename, injectable for the same kind of reason [now]
     * is: the instant it is about — the machine stopping between the last byte and the rename —
     * cannot be produced from a JVM test, but the ORDER can, by making the commit throw.
     */
    private val commit: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) : Vault {

    /** Dot-prefixed so a vault opened in Obsidian does not show deleted notes as notes. */
    private val trash: File get() = File(root, ".trash")

    private fun trashFolder(id: String) = File(trash, id)

    private val yearMonth = DateTimeFormatter.ofPattern("yyyy/MM")

    private fun folderFor(createdAt: Long): String =
        yearMonth.format(Instant.ofEpochMilli(createdAt).atZone(zone))

    override fun pathFor(note: Note): File = pathFor(note.id, note.createdAt)

    override fun pathFor(id: String, createdAt: Long): File =
        File(root, "notes/${folderFor(createdAt)}/$id.md")

    override fun audioPathFor(note: Note): File = File(pathFor(note).parentFile, "${note.id}.wav")

    /** A free `<id>~<stamp>.wav` beside [current], stamped with its own last-modified time. */
    private fun earlierSlotFor(current: File, id: String): File {
        var stamp = current.lastModified().coerceAtLeast(0L)
        while (true) {
            val slot = File(current.parentFile, "$id$EARLIER_RECORDING_SEP$stamp.wav")
            if (!slot.exists()) return slot
            stamp++
        }
    }

    /**
     * Whether [a] and [b] hold the same bytes. Length first — the common answer, and free — and the
     * bytes only when the lengths match: recordings capped at ten minutes can share a length.
     */
    private fun sameBytes(a: File, b: File): Boolean {
        if (!b.isFile || a.length() != b.length()) return false
        // Block-wise: this runs under the note's lock on every write of a note whose recording is
        // outside the vault, and a byte-per-call loop over 19 MB is millions of calls.
        val bufA = ByteArray(COMPARE_BLOCK)
        val bufB = ByteArray(COMPARE_BLOCK)
        a.inputStream().use { x ->
            b.inputStream().use { y ->
                while (true) {
                    val n = x.readNBytes(bufA, 0, COMPARE_BLOCK)
                    val m = y.readNBytes(bufB, 0, COMPARE_BLOCK)
                    if (n != m || !java.util.Arrays.equals(bufA, 0, n, bufB, 0, n)) return false
                    if (n < COMPARE_BLOCK) return true
                }
            }
        }
    }

    override fun recordingsOf(note: Note): List<File> {
        val current = audioPathFor(note)
        val earlier = earlierRecordings(current.parentFile, note.id).sortedByDescending {
            it.nameWithoutExtension.substringAfter(EARLIER_RECORDING_SEP).toLongOrNull() ?: it.lastModified()
        }
        return listOfNotNull(current.takeIf { it.isFile }) + earlier
    }

    /** Note [id]'s earlier recordings in [folder] — see [EARLIER_RECORDING_SEP]. */
    private fun earlierRecordings(folder: File?, id: String): List<File> =
        folder?.listFiles()?.filter { it.isFile && isEarlierRecordingOf(it, id) }.orEmpty()

    override suspend fun adoptAudio(note: Note, source: File): Result<File> = withContext(io) {
        runCatchingCancellable {
            val target = audioPathFor(note)
            if (!source.isFile) throw FileNotFoundException("no recording at ${source.absolutePath}")
            if (source.absolutePath == target.absolutePath) return@runCatchingCancellable target
            target.parentFile?.mkdirs()
            // **Never delete a recording to make room for another** (`B-238`, `DEC-0090`). A second
            // dictation into an open note lands here with the first already at `target`; it used
            // to be `target.delete()`. The first moves aside under its own stamp and stays the
            // note's — trashed, restored, exported and removed with it. A rename that fails stops
            // the adoption: `adoptOrKeep` then keeps the new recording where it is, and nothing is
            // lost on either side.
            if (target.exists()) {
                val aside = earlierSlotFor(target, note.id)
                if (!target.renameTo(aside)) throw java.io.IOException("could not keep ${target.name} beside ${aside.name}")
                Log2.i("vault.audio.kept_earlier", "note" to note.id)
            }
            // A rename across filesystems fails rather than throwing; copy-then-delete is the same
            // outcome, slower, and the delete is what makes it a move rather than a duplication.
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            target
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                Log2.e("vault.audio.failed", it, "note" to note.id)
                Result.failure(VaultException(AppError.Storage("vault.audio", it)))
            },
        )
    }

    /**
     * **One writer of a note at a time** (`B-210`).
     *
     * The lock is the note's own `.md` path, taken around the whole body rather than around
     * [writeAtomically] alone: the recording copied beside the note is addressed by the same pair
     * — id and creation time — so the two writers who could interleave over the Markdown are
     * exactly the two who could interleave over the `.wav`. [VaultFileLocks] carries the argument
     * for where the lock lives and why the scratch file is still `<id>.md.tmp`.
     */
    override suspend fun write(note: Note): Result<File> = withContext(io) {
        val file = pathFor(note)
        VaultFileLocks.withFile(file) {
            runCatchingCancellable {
                file.parentFile?.mkdirs()
                writeAtomically(file, MarkdownSerializer.toMarkdown(note))
                note.audioPath?.let { source ->
                    val from = File(source)
                    if (from.isFile) {
                        val to = audioPathFor(note)
                        if (from.absolutePath != to.absolutePath && !sameBytes(from, to)) {
                            // `DEC-0090`'s rule on this door too (`B-257`): a different recording
                            // already at `<id>.wav` moves aside rather than being overwritten. An
                            // identical one is left alone, or every rewrite of the note would add
                            // another copy beside it.
                            if (to.exists() && !to.renameTo(earlierSlotFor(to, note.id))) {
                                throw java.io.IOException("could not keep ${to.name} before copying over it")
                            }
                            from.copyTo(to, overwrite = true)
                        }
                    }
                }
                file
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    Log2.e("vault.write.failed", it, "note" to note.id)
                    Result.failure(VaultException(AppError.Storage("vault.write", it)))
                },
            )
        }
    }

    /**
     * A note reaches disk whole or not at all (`M2`).
     *
     * The previous version called `writeText` on the live file, and the editor autosaves every
     * 600 ms — so every save opened the person's only copy for truncation. A headset that loses
     * power mid-write leaves a half-written `.md`, and a half-written `.md` is not a damaged note
     * but **no note**: `MarkdownSerializer.parse` refuses it, so the vault this product calls the
     * source of truth would hand the recovery path a file it counts as skipped.
     *
     * The mechanism is [writeFileAtomically], shared with [RemovalJournal] because a journal
     * half-written by a power cut is read on the launch after exactly that kind of stop. The
     * scratch file is `<name>.tmp` beside the target, so an interrupted write leaves exactly one
     * identifiable artefact — cleared by the next write of the same note, skipped by
     * [VaultExporter] and invisible to every `.md` walk in this module.
     */
    private fun writeAtomically(file: File, text: String) = writeFileAtomically(file, text, commit)

    /**
     * Deletes by **identity**. The computed folder is tried first, and when nothing is there the
     * vault is searched for the id — files written by an older build used the device's zone, and a
     * note the person deleted must leave whatever folder it actually landed in.
     */
    override suspend fun remove(id: String, createdAt: Long): Result<Unit> = withContext(io) {
        runCatchingCancellable {
            val moved = collectFor(id, createdAt).count { source ->
                val target = File(trashFolder(id), source.name)
                target.parentFile?.mkdirs()
                target.delete()
                source.renameTo(target) || (source.copyTo(target, overwrite = true).let { source.delete() })
            }
            // A note whose mirror write failed was never on disk. That is not an error, and
            // reporting it as one surfaced a message about a file the person never had.
            if (moved > 0) {
                trashFolder(id).setLastModified(now())
            }
            Unit
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                Log2.w("vault.remove.failed", "note" to id)
                Result.failure(VaultException(AppError.Storage("vault.remove", it)))
            },
        )
    }

    override suspend fun restore(id: String, createdAt: Long): Result<Unit> = withContext(io) {
        runCatchingCancellable {
            val held = trashedFiles(id)
            if (held.isEmpty()) {
                throw FileNotFoundException("the trash no longer holds note $id")
            }
            val home = File(root, "notes/${folderFor(createdAt)}")
            home.mkdirs()
            held.forEach { source ->
                val target = File(home, source.name)
                target.delete()
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                }
            }
            trashFolder(id).delete()
            Unit
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                Log2.w("vault.restore.failed", "note" to id)
                Result.failure(VaultException(AppError.Storage("vault.restore", it)))
            },
        )
    }

    override fun trashedFiles(id: String): List<File> =
        trashFolder(id).listFiles()?.filter { it.isFile }.orEmpty()

    override suspend fun purgeTrash(olderThanMillis: Long): Result<Int> = withContext(io) {
        runCatchingCancellable {
            val cutoff = now() - olderThanMillis
            // `<=`, not `<`: `olderThanMillis = 0` means "empty it", and with a fixed clock a
            // folder trashed this instant has `lastModified() == cutoff`.
            trash.listFiles()?.filter { it.isDirectory && it.lastModified() <= cutoff }
                ?.count { it.deleteRecursively() } ?: 0
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                Log2.w("vault.purge.failed", "cutoff" to olderThanMillis)
                Result.failure(VaultException(AppError.Storage("vault.purge", it)))
            },
        )
    }

    override suspend fun audioUsage(): Result<AudioUsage> = withContext(io) {
        runCatchingCancellable {
            recordings().fold(AudioUsage(0, 0L)) { acc, file ->
                AudioUsage(acc.count + 1, acc.bytes + file.length())
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(VaultException(AppError.Storage("vault.audio.usage", it))) },
        )
    }

    override suspend fun removeAudio(note: Note): Result<Unit> = withContext(io) {
        runCatchingCancellable {
            val file = audioPathFor(note)
            // Absent is success: the intention is "there is no recording for this note", and it
            // is already true. Failing here would make a second tap an error report. The earlier
            // recordings go too (`DEC-0090`): the person asked for this note to have none.
            (listOf(file) + earlierRecordings(file.parentFile, note.id)).forEach { each ->
                if (each.exists() && !each.delete()) error("could not delete ${each.name}")
            }
            Unit
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = {
                Log2.w("vault.audio.remove_failed", "note" to note.id)
                Result.failure(VaultException(AppError.Storage("vault.audio.remove", it)))
            },
        )
    }

    override suspend fun sweepAudio(cutoff: Long): Result<AudioUsage> = withContext(io) {
        runCatchingCancellable {
            var count = 0
            var bytes = 0L
            val ids = mutableSetOf<String>()
            recordings().filter { it.lastModified() < cutoff }.forEach { file ->
                val size = file.length()
                if (file.delete()) {
                    count++
                    bytes += size
                    // An earlier recording is not the one the row points at (`DEC-0090`): naming its
                    // note would clear an `audioPath` whose file is still there.
                    if (EARLIER_RECORDING_SEP !in file.name) ids += file.nameWithoutExtension
                }
            }
            if (count > 0) Log2.i("vault.audio.swept", "files" to count, "bytes" to bytes)
            AudioUsage(count, bytes, ids)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(VaultException(AppError.Storage("vault.audio.sweep", it))) },
        )
    }

    /**
     * Every recording that belongs to a live note.
     *
     * **`notes/`, never the root**, so `.trash` is out of reach — see [sweepAudio] for why that
     * is load-bearing rather than tidy. It is the same rule `VaultExporter` follows and for a
     * related reason: what is in the trash is not the person's current archive.
     */
    private fun recordings(): Sequence<File> =
        File(root, "notes").walkTopDown().filter { it.isFile && it.name.endsWith(".wav") }

    /** Every file this note owns: the computed folder first, then a search for an older layout. */
    private fun collectFor(id: String, createdAt: Long): List<File> {
        val expected = File(root, "notes/${folderFor(createdAt)}")
        val direct = listOf(File(expected, "$id.md"), File(expected, "$id.wav")).filter { it.exists() } +
            earlierRecordings(expected, id)
        if (direct.isNotEmpty()) return direct
        // Files written by an older build used the device's zone, so a note may sit in a
        // neighbouring month. Searching for the id finds whatever folder it actually landed in.
        return File(root, "notes").walkTopDown()
            .filter { it.isFile && (it.nameWithoutExtension == id || isEarlierRecordingOf(it, id)) }
            .toList()
    }
}

class VaultException(val error: AppError) : Exception(error.cause)

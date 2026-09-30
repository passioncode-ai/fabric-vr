package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.runCatchingCancellable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a restore did.
 *
 * @param imported notes that reached the database.
 * @param skipped entries this import did not take and did not object to: Markdown that would not
 *   parse, and a note the vault already holds — the live copy is newer than any archive's by
 *   construction, so it is kept.
 * @param refused entries the path rules rejected. Anything above zero is worth showing: a
 *   well-formed export produces none, and a nonzero count is either an archive from somewhere
 *   else or an attempt to write outside the vault.
 */
data class ImportSummary(val imported: Int, val skipped: Int, val refused: Int)

/**
 * The archive wanted more room than the device has (`B-185`).
 *
 * It carries both numbers because the message a person can act on is *"needs 1.4 GB, you have
 * 200 MB"* and not *"the import failed"* — and because a bound whose two sides are not logged is a
 * bound nobody can check after the fact.
 */
class ArchiveTooLargeException(val needBytes: Long, val budgetBytes: Long) : java.io.IOException(
    "the archive needs at least $needBytes bytes and only $budgetBytes are available",
)

/**
 * Unpacks an exported vault zip back into the vault, then reconciles.
 *
 * **This is the half of `DEC-0026` that did not exist** (`H4`). `VaultExporter` has written the
 * archive since `T-023` and `VaultImporter`'s own comment called it *"a genuine restore path:
 * unzip it back into `filesDir/vault`"* — while nothing in the tree could unzip anything, and
 * `filesDir` is app-private with `run-as` unavailable against a release build. The sentence
 * described an operation **nobody could perform**: a backup with no restore is a copy of the
 * person's notes they cannot use.
 *
 * **The stream is a parameter for the same reason [VaultExporter]'s sink is.** Where the archive
 * comes from is an Android question — `ACTION_OPEN_DOCUMENT` and `ContentResolver.openInputStream`
 * live in `:app` — and this module stays platform-light so the unpacking can be tested at all.
 *
 * **Every entry is untrusted.** The file came from a picker; the person may have been handed it.
 * Four rules, and none of them is redundant:
 *
 * - **Under `notes/`, or nothing.** An archive may carry a crash log at its root ([VaultExporter]
 *   `extras`), and that is not a note. Unpacking whatever a zip names into app-private storage is
 *   how an archive rewrites a config file.
 * - **No `..`, no absolute path.** The two plain forms of zip-slip.
 * - **The layout is `notes/YYYY/MM/<uuid>.md|.wav`.** The filename is the note's **identity** —
 *   the reconciler imports by id and `Vault.remove` finds a note's files by id — so a name that
 *   is not a UUID would mint an id the rest of this module cannot reason about.
 * - **The resolved path is inside the notes directory**, checked canonically. The three rules
 *   above already forbid everything this one catches; it is here because it is the check that
 *   still holds after somebody edits them.
 *
 * A refused entry does not fail the import. A person restoring from a disaster is served worse by
 * an all-or-nothing refusal than by a count they can read.
 *
 * **A fifth rule, about volume rather than location** (`B-185`). The four above stop an archive
 * writing *outside* the vault and said nothing about how much it may write *into* it, so a hostile
 * or merely corrupt archive could fill `filesDir` — and a full `filesDir` is how the vault stops
 * being writable, which is the one failure the vault exists to prevent. The person restoring from
 * a disaster would then have neither their backup nor a working app. [freeBytes] is the bound, and
 * running past it fails the whole import rather than being counted as a refused entry: a
 * half-unpacked archive on a disk with no room left is not a state anyone can act on.
 */
class VaultZipImporter(
    private val vault: Vault,
    private val reconciler: VaultReconciler,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * How much room the device will give the vault, in bytes.
     *
     * **The threshold is not invented, which is the whole of `B-185`'s difficulty.** A cap needs a
     * number, and a number nobody decided is worse than no cap — so this is the same question
     * `ModelDownloader` already answers before it spends 190 MB of headset Wi-Fi, asked the same
     * way and with the same ten-per-cent margin ([SPACE_MARGIN_NUM]/[SPACE_MARGIN_DEN]). There is
     * one answer on this device to *is there room for this*, not two.
     *
     * A parameter because it is the only way the refusal can be tested — a unit test cannot fill a
     * disk — and because the probe is an Android question this module deliberately stays out of.
     *
     * **The default is the pessimistic probe**, and the asymmetry with `ModelDownloader` is
     * deliberate. There, `Graph` supplies `StorageManager.getAllocatableBytes` because a *false
     * refusal* was the worse failure: the person cannot disprove it and loses a download that
     * would have worked. Here the bound is not refusing a download, it is refusing to keep writing
     * into a filesystem that is filling up, and erring toward "less room than you think" fails in
     * the safe direction. Wiring the allocatable probe through `Graph` would be strictly better
     * and is a `:app` change this task did not make.
     */
    private val freeBytes: () -> Long = { vault.root.usableSpace },
    /**
     * Where an entry's bytes go.
     *
     * **Only a test passes this**, for the same reason `ModelDownloader` takes its own sink: the
     * difference between the two halves of the size bound is *how many bytes reached the disk
     * before the refusal*, and a unit test cannot see that through the filesystem — the partial
     * file is deleted on the way out, so both halves leave an empty directory behind. A check
     * nobody has watched fire is a claim, and this is the seam that lets it be watched.
     */
    private val openSink: (File) -> java.io.OutputStream = { FileOutputStream(it) },
) {

    /**
     * @param stream the archive. **Closed by this function**, because a `ContentResolver` stream
     *   held open outlives the screen that opened it.
     */
    suspend fun import(stream: InputStream): Result<ImportSummary> = withContext(io) {
        runCatchingCancellable {
            val notesRoot = File(vault.root, NOTES)
            notesRoot.mkdirs()
            val inside = notesRoot.canonicalFile.path + File.separator

            // **The budget, measured once, before the first entry** (`B-185`). Ten per cent of the
            // free space is left alone for the same reason `ModelDownloader` leaves it: the
            // filesystem needs metadata, and a device within ten per cent of full fails the next
            // thing it does anyway. A probe that cannot answer must not become a refusal, so a
            // throwing probe means "plenty" — the same rule `ModelDownloader` applies.
            val budget = runCatching { freeBytes() }.getOrDefault(Long.MAX_VALUE)
                .let { free -> if (free >= Long.MAX_VALUE / SPACE_MARGIN_DEN) free else free * SPACE_MARGIN_DEN / SPACE_MARGIN_NUM }
            var written = 0L

            var refused = 0
            var present = 0
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val target = resolve(entry.name, notesRoot, inside)
                        when {
                            target == null -> {
                                // Named by entry, never by content: the archive is the person's.
                                Log2.w("vault.zip.refused", "entry" to entry.name)
                                refused++
                            }
                            // The live file wins. See [ImportSummary.skipped].
                            target.isFile -> present++
                            else -> {
                                // **The cheap half: believe a header that admits it will not fit.**
                                // Only a STORED entry carries its size here — measured 2026-09-22
                                // on this project's JDK, a DEFLATED entry written by
                                // `ZipOutputStream` reports -1 from `ZipInputStream`, because the
                                // real size follows the data in a descriptor. So this refuses
                                // early when it can and is never the only bound.
                                val declared = entry.size
                                if (declared > 0 && written + declared > budget) {
                                    throw ArchiveTooLargeException(written + declared, budget)
                                }
                                target.parentFile?.mkdirs()
                                // **The half that always holds: count what is actually written.**
                                // A streamed archive declares nothing, and an archive built to do
                                // harm declares whatever suits it, so the only number that can be
                                // trusted is the one this loop measured itself.
                                written += copyBounded(zip, target, budget - written)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            // **The import is the reconcile.** Writing the files is half the job; the database
            // is an index over them and only `VaultReconciler` knows how to bring it into step
            // without re-importing what is already there or resurrecting what was deleted.
            val reconciled = reconciler.reconcile().getOrThrow()
            Log2.i(
                "vault.zip.done",
                "imported" to reconciled.imported,
                "skipped" to reconciled.skipped + present,
                "refused" to refused,
            )
            ImportSummary(
                imported = reconciled.imported,
                skipped = reconciled.skipped + present,
                refused = refused,
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(VaultException(AppError.Storage("vault.zip.import", it))) },
        )
    }

    /**
     * Copy one entry, giving up the moment it would cost more than [allowance] bytes.
     *
     * **The partial file is deleted before the exception leaves.** A truncated `.md` would merely
     * fail to parse and be counted as skipped, but a truncated `.wav` is a playable-looking
     * recording that is not the person's recording — and the reconciler would adopt it. Half a
     * note is worse than no note, so what this wrote is removed on the way out.
     */
    private fun copyBounded(source: InputStream, target: File, allowance: Long): Long {
        var written = 0L
        try {
            openSink(target).use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    if (written + read > allowance) {
                        throw ArchiveTooLargeException(written + read, allowance)
                    }
                    out.write(buffer, 0, read)
                    written += read
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        return written
    }

    /** Where this entry may be written, or null if it may not be written at all. */
    private fun resolve(rawName: String, notesRoot: File, inside: String): File? {
        // A zip written on Windows can carry backslashes; normalising first means the segment
        // rules below cannot be walked around by the separator.
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/")) return null
        // `C:/…`, which `File` on a JVM would not treat as absolute but a reader would.
        if (name.length > 1 && name[1] == ':') return null

        val parts = name.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." }) return null
        if (parts.size != 4 || parts[0] != NOTES) return null

        val (_, year, month, fileName) = parts
        if (!YEAR.matches(year) || !MONTH.matches(month)) return null

        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return null
        val extension = fileName.substring(dot)
        if (extension !in EXTENSIONS) return null
        val base = fileName.substring(0, dot)
        // A note's own file, or — for a `.wav` only — one of its earlier recordings (`DEC-0090`).
        val isNote = VAULT_ID_SHAPE.matches(base)
        val isEarlier = extension == ".wav" && EARLIER_RECORDING_SHAPE.matches(base)
        if (!isNote && !isEarlier) return null

        val target = File(notesRoot, "$year/$month/$fileName")
        // Belt and braces, and the brace is the one that survives an edit to the rules above.
        if (!target.canonicalFile.path.startsWith(inside)) return null
        return target
    }

    private companion object {
        const val NOTES = "notes"
        const val BUFFER = 8 * 1024

        /**
         * Ten per cent of the free space is not spent, exactly as [ModelDownloader] does not spend
         * it. Integer arithmetic for the same reason: a number in a log line should be one a
         * person can reproduce by hand.
         */
        const val SPACE_MARGIN_NUM = 11L
        const val SPACE_MARGIN_DEN = 10L
        val EXTENSIONS = setOf(".md", ".wav")
        val YEAR = Regex("""\d{4}""")
        val MONTH = Regex("""\d{2}""")
    }
}

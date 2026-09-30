package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.runCatchingCancellable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What actually went into an export, so the screen can say it rather than imply it.
 *
 * @param skipped how many files the walk listed and the archive did not get whole. `B-189`: this
 *   number did not exist, so a caller had nothing to show and the exporter's own comment —
 *   *"the person would get a backup that looks complete and is not"* — described the code below
 *   it. Zero is the ordinary case and the only one a caller may stay silent about.
 * @param skippedReasons the distinct exception classes behind [skipped], by simple name, as they
 *   already reach the log. Classes, not messages and not paths: a message is a locale away from
 *   being other text, and a path is the person's own note title.
 */
data class ExportSummary(
    val noteFiles: Int,
    val audioFiles: Int,
    val bytes: Long,
    val skipped: Int = 0,
    val skippedReasons: Set<String> = emptySet(),
)

/**
 * Writes the vault as one zip into a stream somebody else owns.
 *
 * The stream is a parameter because where the archive lands is an Android question — a
 * `MediaStore` row, in `:app` — and this module is deliberately platform-light. Keeping the zip
 * building on this side of that line is also the only reason it can be tested at all.
 *
 * **It walks `notes/`, not the vault root.** Since `T-006` a deleted note is *moved* into
 * `.trash/` and stays there for seven days, and `.trash` is a sibling of `notes/` under the same
 * root. An export that walked the root would hand the person an archive of notes they deleted —
 * and every test in this file would have stayed green while it did, because a fresh test vault has
 * no trash. `a deleted note is not in the export` is the one that would have caught it.
 */
class VaultExporter(
    private val vault: Vault,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Anything else that belongs in the archive a person takes off the headset, by the name it
     * gets inside the zip. `T-011`'s crash log rides here: `DEC-0026` said the crash directory
     * could join the export once one existed rather than inventing a second route off the device,
     * and now one does.
     */
    private val extras: () -> Map<String, File> = ::emptyMap,
    /**
     * How a file's bytes are opened on the way into the zip.
     *
     * A seam, because `B-189` is about **how much of a file is resident at once** and there is no
     * honest assertion for that on a heap — the number is a different one on every machine and
     * every GC. A test substitutes this to count what the exporter actually pulls and how wide
     * each pull is, which is the property, measured directly.
     */
    private val source: (File) -> InputStream = { it.inputStream() },
    /**
     * The window each file travels through, not a limit on how big a file may be. `B-189` says
     * explicitly that a size threshold would be the wrong fix — the number nobody has measured —
     * and streaming is the right one, so this bounds the buffer and nothing else. A test makes it
     * small so the read count is exact.
     */
    private val copyBufferBytes: Int = COPY_BUFFER_BYTES,
) {

    suspend fun writeTo(sink: OutputStream, includeAudio: Boolean): Result<ExportSummary> =
        withContext(io) {
            runCatchingCancellable {
                var notes = 0
                var audio = 0
                var bytes = 0L
                var skipped = 0
                val reasons = linkedSetOf<String>()

                /**
                 * One entry, streamed. Returns the bytes written, or null when the file could not
                 * be delivered whole — the caller counts that rather than dropping it.
                 *
                 * The stream is opened **before** the entry is named, so the ordinary failure —
                 * a file the walk listed and the mirror deleted a millisecond later — leaves no
                 * entry in the archive at all, and is counted as skipped.
                 *
                 * **A failure part-way through the copy fails the whole export** (`B-247`,
                 * `DEC-0093`). A zip cannot retract a name it has already written, so the entry
                 * used to stay truncated with only the skipped count to say so — and a restore
                 * then imported the short `.md` or `.wav` as whole, because nothing in the archive
                 * said otherwise. The mirror replaces files by rename, so an input already open
                 * keeps reading the old inode: once bytes are flowing, a failure is a real read
                 * error, and `VaultExport` discards the whole archive rather than publishing it.
                 */
                fun ZipOutputStream.stream(file: File, name: String): Long? {
                    val input = runCatching { source(file) }.getOrElse { return note(it, reasons) }
                    putNextEntry(ZipEntry(name))
                    // **Only a READ failure is the file's fault** (seam verification of `DEC-0097`).
                    // `copyTo` read and wrote in one call, so a full disk on the sink side was
                    // reported as a damaged recording — and the notes-only export then failed the
                    // same way, blaming a note. A write failure stays a storage failure.
                    val buffer = ByteArray(copyBufferBytes)
                    var written = 0L
                    input.use { stream ->
                        while (true) {
                            val n = try {
                                stream.read(buffer)
                            } catch (e: java.io.IOException) {
                                throw ExportUnreadableException(file.name, file.name.endsWith(WAV), e)
                            }
                            if (n < 0) break
                            write(buffer, 0, n)
                            written += n
                        }
                    }
                    closeEntry()
                    return written
                }

                val root = File(vault.root, NOTES)
                ZipOutputStream(sink.buffered()).use { zip ->
                    root.walkTopDown()
                        .filter { it.isFile }
                        // `M2`'s scratch file, which a crash between write and rename can leave
                        // beside a note. It is a half-written `.md` by construction, so an
                        // archive carrying it would hand the person a file their own importer
                        // refuses — and one that looks, by name, like a note.
                        .filter { !it.name.endsWith(VAULT_TMP_SUFFIX) }
                        .filter { includeAudio || !it.name.endsWith(WAV) }
                        .sortedBy { it.path }
                        .forEach { file ->
                            // A vault being mirrored into WHILE it is exported is the normal case:
                            // the walk lists a path and the mirror can delete it a millisecond
                            // later. One missing file must not truncate the archive — the person
                            // would get a backup that looks complete and is not.
                            val written = zip.stream(
                                file,
                                "$NOTES/${file.relativeTo(root).invariantPath()}",
                            )
                            if (written == null) {
                                skipped++
                                return@forEach
                            }
                            bytes += written
                            if (file.name.endsWith(WAV)) audio++ else notes++
                        }

                    // Inside the `use`, because the stream closes with it. A first version put
                    // this after the block and did not compile, which is the cheap way to find out.
                    extras().forEach { (name, file) ->
                        // An extra that is simply not there is not a skip: no crash log is the
                        // ordinary state of a headset that has not crashed.
                        if (!file.isFile) return@forEach
                        // **Best effort** (seam verification of `DEC-0093`): an extra is a diagnostic,
                        // not a note. A truncated crash log is harmless — the importer refuses what
                        // is not a note — and failing the whole backup over one would leave the
                        // person with no archive at all. So a mid-copy failure here is a skip.
                        val written = runCatching { zip.stream(file, name) }.getOrElse { note(it, reasons) }
                        if (written == null) skipped++ else bytes += written
                    }
                }
                ExportSummary(
                    noteFiles = notes,
                    audioFiles = audio,
                    bytes = bytes,
                    skipped = skipped,
                    skippedReasons = reasons,
                )
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    val error = if (it is ExportUnreadableException) {
                        AppError.ExportUnreadable(it.fileName, it.recording, it.cause)
                    } else {
                        AppError.Storage("vault.export", it)
                    }
                    Result.failure(VaultException(error))
                },
            )
        }

    /** Log the class, remember it for the summary, and tell the caller nothing arrived. */
    private fun note(failure: Throwable, reasons: MutableSet<String>): Long? {
        val reason = failure::class.java.simpleName
        Log2.w("vault.export.skipped", "reason" to reason)
        reasons += reason
        return null
    }

    /** Zip entry names are `/`-separated wherever the archive is opened. */
    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    private companion object {
        const val NOTES = "notes"
        const val WAV = ".wav"

        /** 64 kB: big enough that the syscall count is noise, small enough to be nothing. */
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/** One file failed once its bytes were flowing; carries what `AppError.ExportUnreadable` names. */
internal class ExportUnreadableException(val fileName: String, val recording: Boolean, cause: Throwable) :
    java.io.IOException("could not read $fileName", cause)

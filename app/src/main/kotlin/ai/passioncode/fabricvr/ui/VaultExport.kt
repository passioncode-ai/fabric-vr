package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.vault.ExportSummary
import ai.passioncode.fabricvr.vault.VaultExporter
import ai.passioncode.fabricvr.vault.VaultException
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The four `MediaStore` calls an export needs, and nothing else.
 *
 * It exists so the **policy** — publish on success, discard on failure — can be tested at all.
 * `ContentResolver.insert`, `delete` and `openOutputStream` are final methods on a final class, so
 * a fake resolver is not possible and a plain JVM test throws "not mocked" on the first line; the
 * seam has to be above them.
 */
internal interface ExportStore {
    /** Creates an entry that stays invisible until [publish]. Returns its handle. */
    fun beginPending(name: String): String?
    fun openStream(handle: String): OutputStream?
    /**
     * Makes a completed entry visible, and answers where a person will find it — or null when the
     * entry could not be made visible, which is not a success however complete its bytes are
     * (`B-246`).
     */
    fun publish(handle: String): String?
    /** Removes a half-written entry, so nothing claims to be a backup that is not one. */
    fun discard(handle: String)
}

/** Where the archive landed, and the handle a share can send. */
data class ExportOutcome(val displayPath: String, val handle: String, val summary: ExportSummary)

/**
 * Writes the vault into shared storage.
 *
 * `/sdcard/Download` rather than anywhere prettier, for one property: **it is the only candidate
 * `adb uninstall` does not delete** — and a signature change forces an uninstall, which is the
 * event this whole task exists to survive. `IS_PENDING` keeps a half-written archive invisible
 * while it is being written, so a person who copies the file mid-write cannot come away with a
 * truncated zip believing they have a backup.
 */
class VaultExport internal constructor(
    private val exporter: VaultExporter,
    private val store: ExportStore,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    /**
     * Where the store is driven (`B-246`). Every [ExportStore] call is a `ContentResolver` round
     * trip, and `run` is called from `viewModelScope` — the thread that draws.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun run(includeAudio: Boolean): Result<ExportOutcome> = withContext(io) { runOnIo(includeAudio) }

    private suspend fun runOnIo(includeAudio: Boolean): Result<ExportOutcome> {
        val name = "fabric-vr-vault-${STAMP.format(now())}.zip"
        val handle = store.beginPending(name)
            ?: return Result.failure(VaultException(AppError.Storage("export.create", null)))

        val stream = store.openStream(handle)
        if (stream == null) {
            store.discard(handle)
            return Result.failure(VaultException(AppError.Storage("export.open", null)))
        }

        // NOT `stream.use`: the exporter closes it through its own `ZipOutputStream.use`, and a
        // second `use` closed the sink twice on the happy path — and, worse, let a `close()` that
        // throws escape as an exception rather than a `Result.failure`, with `discard` never
        // reaching the store. A pending, invisible, half-written row is exactly what "a failed
        // export leaves nothing" promised could not happen. Found by an independent verification
        // pass, not by this file's own tests, which is why there is one for it now.
        //
        // `runCatching` around the whole of it for the same reason: nothing half-written may stay,
        // whatever threw. An archive that looks complete and is not is the one a person relies on
        // before an uninstall.
        val summary = runCatching { exporter.writeTo(stream, includeAudio) }
            .getOrElse { Result.failure(it) }
            .getOrElse { failure ->
                store.discard(handle)
                Log2.w("vault.export.failed", "reason" to failure::class.java.simpleName)
                return Result.failure(failure)
            }

        val path = store.publish(handle)
        if (path == null) {
            // Hidden and pending, MediaStore deletes it on its own schedule: an archive nobody can
            // see is not a backup, and saying *Exported* over it is how a person uninstalls without
            // one (`B-246`). Removed rather than left to that cleanup, for the reason every other
            // failure here removes its row.
            store.discard(handle)
            Log2.w("vault.export.unpublished")
            return Result.failure(VaultException(AppError.Storage("export.publish", null)))
        }
        Log2.i("vault.export.done", "notes" to summary.noteFiles, "audio" to summary.audioFiles)
        return Result.success(ExportOutcome(path, handle, summary))
    }

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

        /** The production wiring. `minSdk 34` needs no permission to insert its own Downloads row. */
        fun create(context: Context, exporter: VaultExporter): VaultExport =
            VaultExport(exporter, MediaStoreExportStore(context))
    }
}

private class MediaStoreExportStore(private val context: Context) : ExportStore {
    private val resolver get() = context.contentResolver

    override fun beginPending(name: String): String? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.toString()
    }.getOrNull()

    override fun openStream(handle: String): OutputStream? =
        runCatching { resolver.openOutputStream(android.net.Uri.parse(handle)) }.getOrNull()

    override fun publish(handle: String): String? {
        val updated = runCatching {
            resolver.update(
                android.net.Uri.parse(handle),
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        }.getOrDefault(0)
        if (updated != 1) return null
        val name = runCatching {
            resolver.query(android.net.Uri.parse(handle), arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val dir = Environment.DIRECTORY_DOWNLOADS
        return if (name != null) "/sdcard/$dir/$name" else "/sdcard/$dir"
    }

    override fun discard(handle: String) {
        runCatching { resolver.delete(android.net.Uri.parse(handle), null, null) }
    }
}

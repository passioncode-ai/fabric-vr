package ai.passioncode.fabricvr.stt

import java.io.File

/**
 * One catalogued model as it exists on this device.
 *
 * @param bytes what it occupies **now**, including a leftover `.part` from an interrupted
 *   transfer. It is the number a person deciding what to delete needs, so it is measured from the
 *   filesystem rather than taken from [WhisperModel.bytes], which is what the file *will* weigh.
 * @param complete whether the model file is exactly the size its publisher states — the same
 *   comparison [FileModelStore.isPresent] makes, for the same reason: a truncated file passes a
 *   "roughly right" check and then fails inside whisper, where the error reads as *couldn't
 *   transcribe* rather than *the model is broken*. An incomplete row is a transfer, not a model.
 */
data class InstalledModel(
    val model: WhisperModel,
    val bytes: Long,
    val complete: Boolean,
)

/**
 * **What is on disk, and a way to get any of it back** (`B-093`).
 *
 * [FileModelStore] puts all five models in one directory so that switching between them keeps
 * whatever is already downloaded. That is the right call and it is also the whole of this row: the
 * only removal the app had resolved *the currently selected* store and deleted that one file, so a
 * person who tried `large-turbo`, disliked the speed and went back to `small` was left with 574 MB
 * that nothing listed and nothing could reclaim short of uninstalling. All five together are
 * 1.395 GB.
 *
 * **`.part` files are counted, and they are the half that is easy to miss.** A cancelled transfer
 * deletes its partial; an *interrupted* one keeps it deliberately, so that pressing Download again
 * costs only what is still missing (`H8`). Those bytes are as real as a finished model's and live
 * under a name no [WhisperModel] mentions.
 *
 * **It enumerates the catalogue, never the directory.** Every operation here starts from
 * [WhisperModel.entries] and asks whether each one's two file names exist — so a file this app did
 * not download is neither counted nor deleted, whatever it is called. A sweep that deletes by
 * pattern in a directory it does not enumerate is exactly the shape `check-destructive.sh` exists
 * to catch.
 *
 * **Nothing here is `suspend` and nothing here dispatches.** These are `File.length()` and
 * `File.delete()` on a handful of paths, and `DEC-0031` puts the dispatcher in the layer that
 * blocks: `:app` already wraps its model-store reads in `withContext(io)` and must wrap these the
 * same way. Taking a dispatcher here would be a second answer to a question that already has one.
 *
 * @param root the directory [FileModelStore] is rooted at — `Graph.modelRoot`. It need not exist;
 *   that is simply the state before the first download.
 */
class InstalledModels(private val root: File) {

    /**
     * Every catalogued model that occupies room, in the catalogue's own order.
     *
     * The order matters more than it looks: a listing sorted by size or by discovery reshuffles
     * under the person's hand the moment a download finishes, and the catalogue's order is the one
     * the model picker already shows.
     */
    fun list(): List<InstalledModel> = WhisperModel.entries.mapNotNull { model ->
        val bytes = bytesOf(model)
        if (bytes == 0L) null else InstalledModel(model, bytes, complete = isComplete(model))
    }

    /** Everything the speech models occupy, in bytes. The number a storage line is drawn from. */
    fun totalBytes(): Long = WhisperModel.entries.sumOf(::bytesOf)

    /**
     * Deletes [model] and any leftover transfer of it.
     *
     * @return the bytes actually reclaimed — measured before the delete, so a caller can say *574
     *   MB freed* rather than guess. Zero when the model was not there, which is not a failure and
     *   must not become one: a person pressing *Remove* on something already gone wants the shelf
     *   empty, not an error.
     *
     * **It does not stop a running download.** `ModelDownloads` owns that, keyed by model, and a
     * class that deleted the file under a live writer would reproduce `M15` from the other side.
     * A caller offering removal while a transfer is running must cancel it first.
     */
    fun remove(model: WhisperModel): Long {
        val freed = bytesOf(model)
        filesOf(model).forEach { it.delete() }
        return freed
    }

    /**
     * Frees everything except [keep] — *Free up space* as one press.
     *
     * [keep] is a parameter rather than a read of the setting because this module does not know
     * which model is selected: that is a Keystore-backed value `:app` owns (`DEC-0031`), and a
     * class that guessed would delete the one in use on the day the guess was wrong. `null` keeps
     * nothing, which is the honest answer to *reclaim all of it*.
     */
    fun removeOthers(keep: WhisperModel?): Long =
        WhisperModel.entries.filter { it != keep }.sumOf(::remove)

    /** The model file and its partial: the two names one catalogued model can occupy. */
    private fun filesOf(model: WhisperModel): List<File> =
        listOf(File(root, model.fileName), File(root, model.fileName + PARTIAL_SUFFIX))

    private fun bytesOf(model: WhisperModel): Long =
        filesOf(model).sumOf { if (it.isFile) it.length() else 0L }

    private fun isComplete(model: WhisperModel): Boolean =
        File(root, model.fileName).let { it.isFile && it.length() == model.bytes }

    private companion object {
        /** `ModelDownloader` writes `<name>.part` and renames on success. */
        const val PARTIAL_SUFFIX = ".part"
    }
}

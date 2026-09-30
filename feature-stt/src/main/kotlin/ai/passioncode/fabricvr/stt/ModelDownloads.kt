package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The process's model downloads: one per model, started once, outliving the screen that began it.
 *
 * **Two view models could each start a download of the same model into the same `.part` file.**
 * `VoiceViewModel.downloadModel()` and `SettingsViewModel.downloadModel()` each cancelled *their
 * own* job and knew nothing of the other's, and both resolved to `File(root, modelName + ".part")`.
 * Both wrote 64 KB chunks into it, each digesting only its own bytes, so both failed the pinned
 * SHA-256, both deleted the file, and the person was told *"The downloaded file was corrupt and
 * was removed."* — after 380 MB of headset Wi-Fi. **The model could never finish** for as long as
 * both screens were on the back stack, and a person reaches that state by doing exactly what the
 * app tells them: the Today banner says *Download*, and Settings says *Download* (`I-05`).
 *
 * A second `start` for a model already downloading **joins** it. Cancelling takes the transfer
 * away from everyone, which is the honest meaning of a Cancel button.
 *
 * **Keyed by model**, which is what closes `I-22`/`A-21`: changing the selected model used to
 * cancel the running download, and cancellation deletes the partial — so a 539 MB transfer
 * vanished silently because the person chose a different model to look at.
 *
 * **And it holds the download itself, not a `Job` per screen**, which is what makes
 * `ModelDownloader`'s resume path reachable at all (`A-22`): the `Range` header, the 206-vs-200
 * check and the digest replay are the best-engineered part of that class and had never run,
 * because leaving the screen cancelled the collection and the cancellation deleted the partial.
 */
/**
 * What a screen needs from the process's downloads.
 *
 * An interface for the same reason [Recorder] is one: [ModelDownloads] holds a real
 * `CoroutineScope` and builds real `ModelDownloader`s, so a view-model test that wanted to assert
 * *"pressing Download twice starts one transfer"* would have to stand up a `MockWebServer` to say
 * anything at all. The owner's own behaviour is tested against the real thing in
 * `ModelDownloadsTest`; this is what lets the screens be tested against a fake.
 */
interface Downloads {
    suspend fun start(model: WhisperModel): StateFlow<DownloadProgress>
    suspend fun progress(model: WhisperModel): StateFlow<DownloadProgress>?
    suspend fun active(): Map<WhisperModel, DownloadProgress>
    suspend fun cancel(model: WhisperModel)
}

class ModelDownloads(
    /**
     * Process-lifetime and **not** `Graph.scope`. A 190–574 MB transfer is exactly the
     * long-running cancellable work that `I-29` says must not silently join the scope that
     * carries the app's small startup coroutines.
     */
    private val scope: CoroutineScope,
    /**
     * Fired the moment [start] decides to **wait** for a dying transfer rather than open a second
     * one — a test-only seam, in the shape `FileVault`'s `commit` and `VaultZipImporter`'s
     * `openSink` already use.
     *
     * **Why a seam and not a longer sleep** (`B-233`). The property here is that something did
     * NOT happen: a second transfer was not opened while the first writer was still alive. A test
     * can only observe that by waiting and looking — which is a sleep with a number on it, and
     * that number went red twice on a loaded machine while the code was correct. This turns the
     * negative into a positive: `start` announces that it reached the wait, and the test asserts
     * the announcement instead of the absence.
     *
     * Production passes nothing, so this costs one no-op call on a path that runs at most once per
     * download.
     *
     * **It sits before `downloaderFor` on purpose.** That one is passed as a trailing lambda at
     * every call site, so a new parameter after it would silently capture the lambda and every
     * caller would stop compiling with a message about the wrong thing.
     */
    private val onWaitingForDyingTransfer: () -> Unit = {},
    private val downloaderFor: suspend (WhisperModel) -> ModelDownloader,
) : Downloads {
    private val lock = Mutex()
    private val running = mutableMapOf<WhisperModel, Running>()

    private class Running(val job: Job, val progress: MutableStateFlow<DownloadProgress>) {
        /**
         * Set by [cancel] **under the lock**, before the join it performs outside it.
         *
         * `M15`: without it the entry was simply removed at that point, so for as long as the
         * dying transfer was parked in a blocking read or write — which is precisely as long as a
         * cancelled transfer takes to notice — a `start` for the same model saw an empty map and
         * opened a second writer on the same `.part`. The flag turns that window into a wait.
         */
        @Volatile var cancelling: Boolean = false
    }

    /**
     * Starts [model] downloading, or returns the progress of the one already running.
     *
     * Idempotent, and that is the whole point: the second caller gets the first transfer's
     * progress rather than a second transfer. The flow is hot — the download proceeds whether or
     * not anyone is collecting — so a caller that starts one and walks away has still started
     * 190 MB. Both call sites collect; a third must collect or [cancel].
     *
     * **A start that arrives during a cancel waits for it** rather than racing it (`M15`). The
     * wait happens with the lock released, because the job being joined takes the lock itself on
     * its way out; the loop then re-reads the map, since by the time it gets the lock back the
     * world may have moved again.
     */
    override suspend fun start(model: WhisperModel): StateFlow<DownloadProgress> {
        while (true) {
            var dying: Job? = null
            val answer = lock.withLock {
                val existing = running[model]
                when {
                    existing == null -> launchLocked(model)
                    !existing.cancelling -> existing.progress.asStateFlow()
                    // The writer is already gone and only the bookkeeping is late — there is
                    // nothing to wait for, and waiting would spin until the remover got the lock.
                    existing.job.isCompleted -> {
                        running.remove(model)
                        launchLocked(model)
                    }
                    else -> {
                        dying = existing.job
                        onWaitingForDyingTransfer()
                        null
                    }
                }
            }
            if (answer != null) return answer
            dying?.join()
        }
    }

    /** Called with [lock] held: creates the one transfer for [model] and registers it. */
    private suspend fun launchLocked(model: WhisperModel): StateFlow<DownloadProgress> {
        val progress = MutableStateFlow<DownloadProgress>(DownloadProgress.Running(0, model.bytes))
        val downloader = downloaderFor(model)
        val job = scope.launch {
            downloader.download().collect { progress.value = it }
        }
        job.invokeOnCompletion {
            // Not inside the flow and not holding `lock` from the download's own coroutine: a
            // cancellation arriving while `start` holds the lock would deadlock. The removal is
            // launched instead, which is why `start` re-reads the map rather than trusting a
            // completed entry to be gone already.
            scope.launch { lock.withLock { if (running[model]?.job === job) running.remove(model) } }
        }
        running[model] = Running(job, progress)
        return progress.asStateFlow()
    }

    /** The progress of a running download, or null when [model] is not downloading. */
    override suspend fun progress(model: WhisperModel): StateFlow<DownloadProgress>? =
        lock.withLock { running[model]?.progress?.asStateFlow() }

    /** Every model currently transferring, with its progress. Settings shows these. */
    override suspend fun active(): Map<WhisperModel, DownloadProgress> =
        lock.withLock { running.mapValues { (_, r) -> r.progress.value } }

    /**
     * Stops [model]'s download **for everyone**, and that is a behaviour change worth knowing:
     * Cancel used to end one screen's view of a transfer that kept running for the other.
     */
    override suspend fun cancel(model: WhisperModel) {
        // **Marked, not removed** (`M15`). Removing it here published "no download for this
        // model" while a writer was still holding the `.part`, and `start` believed it.
        val stopped = lock.withLock {
            val existing = running[model] ?: return
            existing.cancelling = true
            existing
        }
        // **Publish the ending before cancelling.** `ModelDownloader`'s cancellation path deletes
        // the partial and rethrows without emitting, so a flow that is only cancelled freezes at
        // its last `Running` value — and every other screen collecting it parks on a progress bar
        // for a transfer that no longer exists, for ever. `Done` and `Failed` are values and were
        // always fine; cancel was the one transition with no observer.
        //
        // It also makes `Reason.CANCELLED` and `error_model_cancelled` reachable. They had existed
        // since the enum was written and nothing in production had ever produced one.
        stopped.progress.value =
            DownloadProgress.Failed(AppError.ModelDownload(AppError.ModelDownload.Reason.CANCELLED))
        // **`cancelAndJoin`, not `cancel`.** A cancelled transfer is still parked in a blocking
        // `input.read()` and only runs `partial.delete()` when it observes the cancellation — so
        // returning early let a `start()` for the same model open a second writer on the same
        // `.part`, and the dying one then deleted the file underneath it. The new download's
        // bytes went to an unlinked inode and its `renameTo` failed, reported as
        // `Reason.DISK`: *"There isn't room on this headset"*, on a headset with plenty. That is
        // the one-writer-per-model invariant this class exists for, so the join is the class.
        stopped.job.cancelAndJoin()
        // The entry is dropped here as well as by `invokeOnCompletion`, and both are guarded by
        // identity so they are idempotent. Doing it here too is what makes the removal ordered
        // with respect to this function returning: the handler's removal is `scope.launch`ed and
        // would not have happened yet, and if the scope is already cancelled it never will.
        lock.withLock { if (running[model] === stopped) running.remove(model) }
    }
}

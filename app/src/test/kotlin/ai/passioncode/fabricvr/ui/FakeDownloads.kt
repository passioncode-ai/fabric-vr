package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.stt.DownloadProgress
import ai.passioncode.fabricvr.stt.Downloads
import ai.passioncode.fabricvr.stt.WhisperModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process's downloads, faked.
 *
 * The real [ai.passioncode.fabricvr.stt.ModelDownloads] holds a `CoroutineScope` and builds real
 * `ModelDownloader`s, so a view-model test using it would need a `MockWebServer` to assert
 * anything — which is why `Downloads` is an interface. The owner's own semantics (one transfer per
 * model, surviving its collector) are tested against the real class in `ModelDownloadsTest`.
 */
internal class FakeDownloads(
    private val script: List<DownloadProgress> = listOf(DownloadProgress.Done(java.io.File("m.bin"), "")),
) : Downloads {
    /** How many transfers were STARTED — the number `I-05` is about. */
    var starts = 0
        private set
    var cancels = 0
        private set

    /**
     * Runs **inside** [cancel], before the entry is dropped.
     *
     * `B-199` needs an ordering asserted rather than a count: `InstalledModels.remove` deletes the
     * file, and deleting under a live writer is `M15` from the other side. A test can only say
     * *the cancel came first* by observing what was still true at the moment it happened.
     */
    var onCancel: (WhisperModel) -> Unit = {}

    private val flows = mutableMapOf<WhisperModel, MutableStateFlow<DownloadProgress>>()

    override suspend fun start(model: WhisperModel): StateFlow<DownloadProgress> {
        flows[model]?.let { return it.asStateFlow() }
        starts++
        val flow = MutableStateFlow<DownloadProgress>(DownloadProgress.Running(0, model.bytes))
        flows[model] = flow
        script.forEach { flow.value = it }
        return flow.asStateFlow()
    }

    override suspend fun progress(model: WhisperModel): StateFlow<DownloadProgress>? =
        flows[model]?.asStateFlow()

    override suspend fun active(): Map<WhisperModel, DownloadProgress> =
        flows.mapValues { (_, f) -> f.value }

    override suspend fun cancel(model: WhisperModel) {
        onCancel(model)
        if (flows.remove(model) != null) cancels++
    }

    /** Moves a running transfer along, for the tests that assert what a screen shows mid-download. */
    fun emit(model: WhisperModel, progress: DownloadProgress) {
        flows.getOrPut(model) { MutableStateFlow(progress) }.value = progress
    }
}

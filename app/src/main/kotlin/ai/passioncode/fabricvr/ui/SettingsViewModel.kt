package ai.passioncode.fabricvr.ui

import androidx.lifecycle.ViewModel
import ai.passioncode.fabricvr.BuildConfig
import ai.passioncode.fabricvr.Graph
import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.CrashLog
import ai.passioncode.fabricvr.crashDir
import ai.passioncode.fabricvr.vault.ImportSummary
import java.io.File
import java.io.InputStream
import ai.passioncode.fabricvr.common.SecureSettings
import ai.passioncode.fabricvr.stt.InstalledModel
import ai.passioncode.fabricvr.stt.InstalledModels
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.Downloads
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.vault.AudioUsage
import ai.passioncode.fabricvr.vault.Vault
import ai.passioncode.fabricvr.vault.VaultMirror
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.UiMessage
import ai.passioncode.fabricvr.common.UiStateMapper
import ai.passioncode.fabricvr.stt.DownloadProgress
import ai.passioncode.fabricvr.stt.RemoteWhisperClient
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Which input a successful save belongs to.
 *
 * Coarse on purpose: the screen holds exactly two secret fields and the rule is "clear the one
 * that was saved". [OTHER] covers everything whose input is seeded from state and therefore
 * cannot be lost by a save — the language, the provider, the model, the URLs.
 */
enum class SavedField { CLOUD_KEY, OTHER }

data class SettingsUiState(
    val language: String = "auto",
    val provider: SttProvider = SttProvider.DEFAULT,
    val whisperModel: WhisperModel = WhisperModel.DEFAULT,
    val cloudUrl: String = "",
    val cloudKeyTail: String? = null,
    /** True when the stored speech-service key cannot be decrypted — the Keystore lost it. */
    /** The Keystore lost it: the bytes are gone and the person has to enter it again. */
    val cloudKeyUnreadable: Boolean = false,
    /**
     * It could not be read **this time** and the bytes are still there — a busy Keystore, a
     * device still finishing boot. A different sentence, because until `T-048` this case
     * *destroyed the key* and then told the person it was lost (`C-04`).
     */
    val cloudKeyUnreadableNow: Boolean = false,
    val cloudModel: String = "",
    val serverUrl: String = "",
    val modelName: String = "",
    val modelPresent: Boolean = false,
    val downloading: Pair<Long, Long>? = null,
    /**
     * Other models currently transferring, by model. Downloads are keyed by model since `T-021`,
     * so choosing a different one no longer cancels the running transfer — which means a 539 MB
     * download would be **invisible** unless the screen says so.
     */
    val otherDownloads: Map<WhisperModel, Pair<Long, Long>> = emptyMap(),
    val vaultPath: String = "",
    val vaultOutOfSync: Int = 0,
    /**
     * **Every speech model that occupies room on this headset** (`B-199`), in the catalogue's own
     * order — the same order the model chips above it are drawn in, because a listing sorted by
     * size reshuffles under the person's hand the moment a download finishes.
     *
     * Empty is the honest first state and also the true one on a fresh install. A model's
     * leftover `.part` is counted here as a row of its own weight: those bytes are as real as a
     * finished model's, and they live under a name no chip on this screen mentions.
     */
    val installed: List<InstalledModel> = emptyList(),
    /** What the rows add up to — the one number a *Free up space* decision is made from. */
    val installedBytes: Long = 0L,
    /**
     * What the recordings cost. Null until the walk finishes — the tree can be gigabytes and
     * `T-019`'s rule forbids measuring it on the drawing thread, so the row shows a placeholder
     * rather than a zero that would read as "nothing is stored".
     */
    val audioUsage: AudioUsage? = null,
    /** Days of recordings to keep, or 0 for everything. `DEC-0038`. */
    val audioRetentionDays: Int = 0,
    /**
     * Whether a finished dictation goes to the system clipboard (`DEC-0012`, `G-02`).
     *
     * Default **on**: in a headset the point of speaking is usually to paste somewhere else, and
     * taking that away costs the product its best moment. What `G-02` is about is that the app
     * overwrites a cross-app resource nobody offered it and the only notice lasts 2.5 s — so the
     * answer is a switch beside the truth, not a changed default.
     */
    val copyTranscript: Boolean = true,
    /**
     * Whether the four dictation moments make a sound, and a controller pulse in the Space
     * (`REQ-062`, audit `M22`).
     *
     * Default **on**: the defect is that start, stop, the ten-minute cap and "saved" were
     * invisible to somebody looking at a streamed desktop rather than at this panel, and a cue
     * nobody switches on fixes none of it. The switch exists because Meta's *Haptics: Best
     * practices* requires one — "Make haptic feedback optional and adjustable".
     */
    val feedbackCues: Boolean = true,
    /** True while the archive is being written; the row shows it and refuses a second press. */
    val exporting: Boolean = false,
    /** Where the last export landed, and the handle a share can send — null until one is taken. */
    val exportedTo: String? = null,
    val exportHandle: String? = null,
    val exportIncludesAudio: Boolean = false,
    /** True while an archive is being unpacked; the row shows it and refuses a second press. */
    val restoring: Boolean = false,
    /**
     * What the last restore did, so the person can read the outcome rather than guess it
     * (`REQ-054`). Null until one has been run in this session.
     */
    val restored: ImportSummary? = null,
    /**
     * The most recent crash, if there was one. `DEC-0023`'s obligation in one field: the person
     * holding the second headset must be able to hand back a diagnosis without a laptop.
     */
    val lastCrash: String? = null,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    /**
     * Which branch this build came off, which is not trivial here: `main` carries the project but
     * the work lands on `feat/v1-notes-core`, and "is this off main?" is a real question when two
     * headsets are running two builds.
     */
    val gitBranch: String = BuildConfig.GIT_BRANCH,
    /** The **commit's** timestamp, not the compile's — how old the code is, not when it was built. */
    val builtAt: String = BuildConfig.BUILD_TIME,
    /**
     * True when this launch moved an upgrading person onto the whisper-server they had configured
     * before the provider became an explicit choice. Shown once, beside the chips.
     */
    val carriedOverServer: Boolean = false,
    /** Increments on every successful save, so the screen knows when to clear an input. */
    val savedTick: Long = 0,
    /**
     * **Which** field that save was, so only its own input is cleared (`B-12`).
     *
     * The tick alone said "something was saved", and every input listening to it cleared itself:
     * paste a 70-character OpenRouter key, tap a language chip before pressing *Save*, and the
     * key was gone with no message. Re-pasting it in a headset is not a small cost. A per-field
     * boolean was the other option and was refused — two flags today, four the next time a
     * secret is added.
     */
    val lastSaved: SavedField? = null,
    val message: UiMessage? = null,
)

/**
 * Dependencies are parameters with production defaults, the way every other view model here takes
 * them. Reaching into [Graph] from the body made this the one screen no test could construct, and
 * the audit's finding was precisely that the untestable seams are where the defects were.
 */
class SettingsViewModel(
    private val settings: SecureSettings = Graph.settings,
    /**
     * Providers, not instances: the person can change which speech model they want while this
     * screen is open, and a store captured at construction would keep answering for the old one.
     */
    private val modelStore: suspend () -> ModelStore = { Graph.currentModelStore() },
    /** Whether this launch carried an old server URL over to an explicit provider (`T-008`). */
    private val carriedOver: () -> Boolean = { Graph.carriedOverServer },
    /** One owner for every transfer in the process. See [ModelDownloads] and `I-05`. */
    private val downloads: Downloads = Graph.downloads,
    /**
     * What is on disk across **all five** models, and the way to reclaim it (`B-199`, `B-093`).
     *
     * Not the [modelStore] provider above: that resolves the *selected* model, which is why the
     * only removal this app had could free one file and left up to 1.395 GB unlisted. An
     * instance rather than a provider because it is rooted at a directory that does not change
     * while this screen is open, and because its own reads are plain `File.length()` calls this
     * view model wraps in `withContext(io)` — `DEC-0031` puts the dispatcher in the layer that
     * blocks, and `InstalledModels` deliberately takes none.
     */
    private val installedModels: InstalledModels = Graph.installedModels,
    private val vaultMirror: VaultMirror = Graph.vaultMirror,
    private val vaultRoot: String = Graph.vault.root.absolutePath,
    /**
     * The export as one call, not the object that performs it.
     *
     * [VaultExport] is a concrete class over `MediaStore`, so a test could not construct one and
     * could not hold one open — which is why `REQ-067`'s defect (a `reload()` **during** an export
     * re-enabling the button) had no test until the seam moved here. Every other dependency in
     * this constructor is a seam for the same reason.
     */
    private val runExport: suspend (includeAudio: Boolean) -> Result<ExportOutcome> =
        { Graph.vaultExport.run(it) },
    /**
     * The other direction: an archive back into the vault (`REQ-054`'s app half).
     *
     * A call rather than the importer, for [runExport]'s reason and one more — the importer
     * **closes** the stream it is given, so the contract a caller has to honour is "hand it over
     * once", and a lambda is where that is stated.
     */
    private val importArchive: suspend (InputStream) -> Result<ImportSummary> =
        { Graph.vaultZipImporter.import(it) },
    private val vault: Vault = Graph.vault,
    /** The sweep clears each swept note's `audioPath`; the vault knows nothing about rows. */
    private val notes: NotesRepository = Graph.notes,
    private val crashDir: () -> File = { Graph.crashes },
    private val language: suspend () -> String = Graph::sttLanguage,
    /**
     * Where the blocking reads and writes go. A parameter because a hard-coded `Dispatchers.IO`
     * cannot be waited for from a test: the assertions ran before the Keystore call had started.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Tells the process's cue seam that the switch moved (`REQ-062`).
     *
     * A lambda rather than a reach into [Graph], for this constructor's stated reason: a test
     * that cannot construct this view model is a screen nothing covers, and the audit's finding
     * was that the untestable seams are where the defects were.
     */
    private val onCuesChanged: (Boolean) -> Unit = { Graph.setFeedbackCues(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var lastFailedAction: (() -> Unit)? = null

    /**
     * Every coroutine this screen starts, with an owner for whatever escapes it (`B-159`).
     *
     * [launchGuarded] carries the catch and the log; what this adds is **where the failure lands
     * for the person**, and Settings is the one screen where that answer is unambiguous: it has a
     * banner, every other failure path on it already ends there, and a settings page that renders
     * its defaults after a failed read is a page telling the person their store holds values it
     * does not. `reload()` reads four seams in one coroutine and is the site the row names.
     *
     * A named function rather than eighteen `launchGuarded(onFailure = …)`: the sink is a property
     * of the screen, not of each call, and repeating it eighteen times is how the nineteenth gets
     * a different one.
     */
    private fun guarded(block: suspend CoroutineScope.() -> Unit) =
        launchGuarded(
            onFailure = { failure -> _state.update { it.copy(message = failure.toMessage()) } },
            block = block,
        )

    init {
        reload()
        guarded {
            vaultMirror.failures.collect { failures ->
                _state.update { it.copy(vaultOutOfSync = failures.size) }
            }
        }
        guarded {
            settings.corruptedKeys.collect { corrupted ->
                _state.update { it.copy(
                    cloudKeyUnreadable = SecureSettings.KEY_CLOUD_STT_KEY in corrupted,
                ) }
                // **A repair clears the WHOLE store** (`B-188`), because nothing written under
                // the replaced alias can be decrypted again — so the provider, language, model,
                // retention and the two switches are back at their defaults while this screen is
                // still drawing the old ones. Re-reading is the only way the screen tells the
                // truth about a reset it did not cause; without it a person taps *Save* on a
                // value the store no longer holds.
                if (corrupted.isNotEmpty()) reload()
            }
        }
        // The other kind, and the distinction is `C-04`: this one keeps the bytes and clears
        // itself the moment a read succeeds, so the screen must not tell the person to re-enter
        // a key that is still there.
        guarded {
            settings.unreadableKeys.collect { unreadable ->
                _state.update { it.copy(
                    cloudKeyUnreadableNow = SecureSettings.KEY_CLOUD_STT_KEY in unreadable,
                ) }
            }
        }
    }

    /** Reads run off the main thread: the first Keystore use costs tens of milliseconds. */
    fun reload() {
        guarded {
            val snapshot = withContext(io) {
                SettingsUiState(
                    language = language(),
                    serverUrl = settings.get(SecureSettings.KEY_WHISPER_SERVER_URL).orEmpty(),
                    provider = SttProvider.byKey(settings.get(SecureSettings.KEY_STT_PROVIDER)),
                    whisperModel = WhisperModel.byKey(settings.get(SecureSettings.KEY_STT_MODEL)),
                    cloudUrl = settings.get(SecureSettings.KEY_CLOUD_STT_URL).orEmpty(),
                    cloudKeyTail = settings.get(SecureSettings.KEY_CLOUD_STT_KEY)?.takeLast(4),
                    cloudModel = settings.get(SecureSettings.KEY_CLOUD_STT_MODEL).orEmpty(),
                    modelName = modelStore().modelName,
                    modelPresent = modelStore().isPresent(),
                    // `B-199`. Two `File.length()` walks over ten paths, inside the hop that is
                    // already here — not a tree walk like the recordings', so it does not need
                    // `refreshAudioUsage`'s separate coroutine and a placeholder row.
                    installed = installedModels.list(),
                    installedBytes = installedModels.totalBytes(),
                    vaultPath = vaultRoot,
                    carriedOverServer = carriedOver(),
                    lastCrash = CrashLog.latest(crashDir()),
                    audioRetentionDays = settings.get(SecureSettings.KEY_AUDIO_RETENTION_DAYS)
                        ?.toIntOrNull() ?: 0,
                    // Absent means on (`DEC-0012`); only an explicit "false" turns it off.
                    copyTranscript = settings.get(SecureSettings.KEY_COPY_TRANSCRIPT) != "false",
                    // Same rule, same reason (`REQ-062`).
                    feedbackCues = settings.get(SecureSettings.KEY_FEEDBACK_CUES) != "false",
                )
            }
            val chosen = snapshot.whisperModel
            val others = otherDownloadsFor(chosen)
            val mine = (downloads.progress(chosen)?.value as? DownloadProgress.Running)
                ?.let { it.bytes to it.total }
            val carried = _state.value
            _state.value = snapshot.copy(
                // **From the owner and nowhere else.** A transfer started on the Today banner
                // has to appear here, and one started here has to survive the person leaving and
                // coming back — but an elvis onto this screen's last frame also *resurrects* a
                // transfer the owner has finished or cancelled, and then the row is a phantom
                // with a Cancel button that does nothing. `mine` being null is the answer.
                downloading = mine,
                otherDownloads = others,
                savedTick = carried.savedTick,
                lastSaved = carried.lastSaved,
                vaultOutOfSync = carried.vaultOutOfSync,
                cloudKeyUnreadable = carried.cloudKeyUnreadable,
                cloudKeyUnreadableNow = carried.cloudKeyUnreadableNow,
                audioUsage = carried.audioUsage,
                // **`REQ-067` / `M9`: what this screen is in the MIDDLE of is not a setting.**
                //
                // Every chip on this page calls `put`, and `put` calls `reload()` on success. The
                // whitelist above rebuilds the state from the Keystore and carried five fields
                // forward; the export's four were not among them. So a language tap during a
                // multi-gigabyte export set `exporting = false`, *Export* came back, and a second
                // press started a **second concurrent archive** into a second pending MediaStore
                // row. A tap afterwards deleted the "saved at" line, which is the only thing on
                // the screen that says where the archive went — and with it the share handle.
                //
                // `message` is carried for the same reason and it is the sharpest case: a failure
                // the person is reading is erased by an unrelated tap succeeding elsewhere.
                // Whoever wants it gone calls `dismissMessage`.
                exporting = carried.exporting,
                exportedTo = carried.exportedTo,
                exportHandle = carried.exportHandle,
                exportIncludesAudio = carried.exportIncludesAudio,
                restoring = carried.restoring,
                restored = carried.restored,
                message = carried.message,
            )
            // **Followed, not sampled** (`REQ-067`, `M9`). `mine` above is one read of a
            // `StateFlow` that keeps moving: a transfer started on the Today banner froze on this
            // screen at whatever byte count happened to be current when the screen was built, and
            // its *Cancel* stood under a number that was no longer true.
            watchDownload(chosen)
        }
    }

    /**
     * Keeps [SettingsUiState.downloading] in step with the one transfer of [model], for as long as
     * that is the chosen model.
     *
     * One collector at a time: [reload] runs after every save, and a collector per reload would
     * stack up writers into the same field. Cancelling the previous one is what makes choosing a
     * different model move this row to the new one rather than leave two of them fighting.
     */
    private fun watchDownload(model: WhisperModel) {
        downloadWatch?.cancel()
        // **`watching` is deliberately not touched.** It names the transfer *this screen
        // started*, which is what Cancel must stop — re-pointing it at whatever the selection
        // says would put back exactly the defect `T-021` removed: choosing a different model
        // while a 539 MB download runs, pressing Cancel, and stopping the wrong one.
        downloadWatch = guarded {
            val flow = downloads.progress(model) ?: return@guarded
            flow.collect { progress ->
                // **`refreshInstalled()` first, outside the update.** `MutableStateFlow.update`
                // retries its block on conflict, so the block must be pure — a suspending re-read
                // of the model shelf inside it would run twice and could not be a lambda at all
                // (`B-211`).
                if (progress is DownloadProgress.Done) {
                    // **The shelf below moved** (`B-199`). 190 MB arrived and the storage rows are
                    // built from the filesystem, so without this they keep showing what was there
                    // when the screen was opened — and the one number a *Free up space* decision
                    // is made from is the stale one.
                    refreshInstalled()
                }
                _state.update { state ->
                    when (progress) {
                        is DownloadProgress.Running ->
                            state.copy(downloading = progress.bytes to progress.total)
                        is DownloadProgress.Done ->
                            state.copy(downloading = null, modelPresent = true)
                        // Reported by whoever started it; this collector only follows the bytes. A
                        // banner raised here would announce a failure the person may have already
                        // dismissed on the screen that started the transfer.
                        is DownloadProgress.Failed -> state.copy(downloading = null)
                    }
                }
            }
        }
    }

    private var downloadWatch: kotlinx.coroutines.Job? = null

    /**
     * Writes the vault into `/sdcard/Download`, which is the only place `adb uninstall` does not
     * delete — and a release signature change forces an uninstall. Until this existed the claim
     * that these are "notes you own" was false on the device: the vault is app-private, backup is
     * off, and the headset ships no file manager.
     */
    fun exportVault(includeAudio: Boolean) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, message = null, exportedTo = null, exportHandle = null) }
        guarded {
            runExport(includeAudio).fold(
                onSuccess = { outcome ->
                    _state.update { it.copy(
                        exporting = false,
                        exportedTo = outcome.displayPath,
                        exportHandle = outcome.handle,
                        exportIncludesAudio = includeAudio,
                    ) }
                },
                onFailure = { failure ->
                    _state.update { it.copy(exporting = false, message = failure.toMessage()) }
                },
            )
        }
    }

    /**
     * Unpacks an archive the person chose back into the vault (`REQ-054`, audit `H4`).
     *
     * **The export was a backup with no restore** until this existed: `VaultImporter`'s own
     * comment called the zip "a genuine restore path: unzip it back into `filesDir/vault`", and
     * `filesDir` is app-private with `run-as` unavailable against a release build — the sentence
     * described an operation nobody could perform.
     *
     * @param open hands over the chosen archive. It is a lambda because opening a
     *   `content://` URI is a `ContentResolver` call the screen owns, it must not happen on the
     *   drawing thread (`R1`), and it can answer null — a picker result whose provider has gone
     *   away. The stream is closed by the importer, which is why it is opened here and nowhere
     *   else.
     */
    fun restoreVault(open: () -> InputStream?) {
        if (_state.value.restoring) return
        _state.update { it.copy(restoring = true, message = null, restored = null) }
        guarded {
            val stream = withContext(io) { runCatching(open).getOrNull() }
            if (stream == null) {
                _state.update { it.copy(
                    restoring = false,
                    message = UiStateMapper.map(AppError.Storage("restore.open", null)),
                ) }
                return@guarded
            }
            importArchive(stream).fold(
                onSuccess = { summary ->
                    _state.update { it.copy(restoring = false, restored = summary) }
                    // The archive's notes are in the database now; the rows this screen shows —
                    // the recordings' size above all — are stale the moment it finishes.
                    refreshAudioUsage()
                    reload()
                },
                onFailure = { failure ->
                    _state.update { it.copy(restoring = false, message = failure.toMessage()) }
                },
            )
        }
    }

    /** Throws the crash record away once it has been handed on, so the row stops nagging. */
    fun clearCrash() {
        guarded {
            withContext(io) { CrashLog.clear(crashDir()) }
            _state.update { it.copy(lastCrash = null) }
        }
    }

    fun saveCopyTranscript(value: Boolean) {
        // Optimistic, because a switch that lags a Keystore write reads as a switch that did not
        // take. The write still reports its own failure through `put`'s banner.
        _state.update { it.copy(copyTranscript = value) }
        put(SecureSettings.KEY_COPY_TRANSCRIPT, value.toString())
    }

    /**
     * `REQ-062`. Optimistic for [saveCopyTranscript]'s reason, and it also tells the process
     * seam at once: the cached flag in `Graph` is what every cue reads, so a switch that only
     * wrote the Keystore would keep chirping until the next launch.
     */
    fun saveFeedbackCues(value: Boolean) {
        _state.update { it.copy(feedbackCues = value) }
        onCuesChanged(value)
        put(SecureSettings.KEY_FEEDBACK_CUES, value.toString())
    }

    fun saveLanguage(value: String) = put(SecureSettings.KEY_STT_LANGUAGE, value)
    fun saveProvider(value: SttProvider) = put(SecureSettings.KEY_STT_PROVIDER, value.key)
    fun saveCloudKey(value: String) =
        put(SecureSettings.KEY_CLOUD_STT_KEY, value.trim(), SavedField.CLOUD_KEY)
    fun saveCloudModel(value: String) = put(SecureSettings.KEY_CLOUD_STT_MODEL, value.trim())

    /**
     * The cloud block's **one** Save, because the screen has one button.
     *
     * It used to fire three independent `put`s — url, key, model — each its own coroutine with
     * its own IO hop, so the terminal [SettingsUiState.lastSaved] was whichever landed last:
     * `OTHER`. The screen clears the key field on `CLOUD_KEY`, so **the one path `B-12` was about
     * stopped clearing it**, and a seventy-character secret stayed in a `rememberSaveable`
     * `TextField` — the field that also reaches the saved-instance bundle. `T-030` fixed the
     * chip-tap half and broke the button half; found by the seam tier of step 8's verification.
     *
     * Three writes, one coroutine, one terminal state. The key wins the report when one was
     * written, because it is the only field whose input must be cleared.
     */
    fun saveCloud(url: String, key: String, model: String) {
        val trimmedUrl = url.trim()
        val validated = if (trimmedUrl.isEmpty()) {
            Result.success(Unit)
        } else {
            RemoteWhisperClient.validateBaseUrl(trimmedUrl).map { }
        }
        validated.onFailure { failure ->
            lastFailedAction = { saveCloud(url, key, model) }
            _state.update { it.copy(message = failure.toMessage()) }
            return
        }
        guarded {
            var wroteKey = false
            write(SecureSettings.KEY_CLOUD_STT_URL, trimmedUrl, SavedField.OTHER)
            if (key.isNotBlank()) {
                wroteKey = write(SecureSettings.KEY_CLOUD_STT_KEY, key.trim(), SavedField.CLOUD_KEY)
            }
            if (model.isNotBlank()) {
                // **`OTHER` would undo the line above**, which is the whole defect this method
                // exists to fix, so the field reported is the one that still has to be cleared.
                val field = if (wroteKey) SavedField.CLOUD_KEY else SavedField.OTHER
                write(SecureSettings.KEY_CLOUD_STT_MODEL, model.trim(), field)
            }
            reload()
        }
    }

    /**
     * Choosing a model does not download it. The screen then shows it as absent with the download
     * offered, because starting a 574 MB transfer because somebody tapped a name in a list is not
     * a choice they made.
     */
    fun saveWhisperModel(value: WhisperModel) = put(SecureSettings.KEY_STT_MODEL, value.key)

    /** The cloud endpoint travels the recording, so it obeys the same rule as the speech server. */
    fun saveCloudUrl(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            put(SecureSettings.KEY_CLOUD_STT_URL, "")
            return
        }
        RemoteWhisperClient.validateBaseUrl(trimmed).fold(
            onSuccess = { put(SecureSettings.KEY_CLOUD_STT_URL, trimmed) },
            onFailure = { failure ->
                lastFailedAction = { saveCloudUrl(value) }
                _state.update { it.copy(message = failure.toMessage()) }
            },
        )
    }

    /**
     * A server URL is validated before it is stored: cleartext is allowed only to a device on the
     * person's own network (`DEC-0005`), and a refused URL must say so rather than fail later as a
     * silent fallback.
     */
    fun saveServerUrl(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            put(SecureSettings.KEY_WHISPER_SERVER_URL, "")
            return
        }
        RemoteWhisperClient.validateBaseUrl(trimmed).fold(
            onSuccess = { put(SecureSettings.KEY_WHISPER_SERVER_URL, trimmed) },
            onFailure = { failure ->
                lastFailedAction = { saveServerUrl(value) }
                _state.update { it.copy(message = failure.toMessage()) }
            },
        )
    }

    /**
     * Removes the stored speech-service key. It is the only credential the app holds after
     * `DEC-0020`, and a stored secret a person cannot remove is not a setting, it is a trap.
     */
    fun clearCloudKey() {
        guarded {
            withContext(io) { settings.remove(SecureSettings.KEY_CLOUD_STT_KEY) }
            reload()
        }
    }

    /**
     * The selected model's *Remove*, which is now [removeInstalled] with the selection filled in.
     *
     * **It used to refuse while that model was transferring** (`AppError.ModelBusy`), and the
     * refusal was safe and was the wrong answer: *Remove*, pressed under the progress bar three
     * rows above it, means "I do not want this", and being told to wait for 539 MB to finish
     * downloading before it may be deleted is the app arguing with the person. `B-199` settles
     * it for the whole screen — **cancel, then delete** — and one policy is the point: this
     * button and the storage rows below perform the same act, and two policies for one act is
     * how they would come to disagree.
     */
    fun removeModel() {
        guarded { removeNow(chosenModel()) }
    }

    /**
     * One row's *Remove* in the storage list (`B-199`): this model and whatever is left of a
     * transfer of it, whether or not it is the selected one.
     */
    fun removeInstalled(model: WhisperModel) {
        guarded { removeNow(model) }
    }

    /**
     * ***Free up space*, as one press** — everything except the model the next dictation needs.
     *
     * The selection is read here rather than inside [InstalledModels], deliberately: which model
     * is chosen is a Keystore-backed value `:app` owns (`DEC-0031`), and a class in
     * `:feature-stt` that guessed would delete the one in use on the day the guess was wrong.
     */
    fun freeUpSpace() {
        guarded {
            val keep = chosenModel()
            val freed = WhisperModel.entries.filter { it != keep }.sumOf { stopThenDelete(it) }
            _state.update { it.copy(
                message = UiMessage(
                    R.string.state_space_freed,
                    // `DEC-0071`: the model's name crosses as a resource id, never as a raw
                    // `String` — `WhisperModel.key` is `large-turbo`, which is not a name.
                    listOf(freed / BYTES_PER_MB, UiMessage.Res(keep.shortLabelRes)),
                ),
            ) }
            reload()
        }
    }

    /**
     * Re-reads the model shelf alone (`B-199`).
     *
     * Not [reload]: that rebuilds the state from the Keystore and re-points [watchDownload], and
     * calling it from inside a download collector would have the collector cancel itself. What
     * moved when a transfer finished is two `File.length()` sums, so those are what is re-read.
     */
    private suspend fun refreshInstalled() {
        val rows = withContext(io) { installedModels.list() to installedModels.totalBytes() }
        _state.update { it.copy(installed = rows.first, installedBytes = rows.second) }
    }

    /** [stopThenDelete] plus what it reclaimed and the re-read, for the two single-model callers. */
    private suspend fun removeNow(model: WhisperModel) {
        val freed = stopThenDelete(model)
        _state.update { it.copy(
            message = UiMessage(
                R.string.state_model_removed,
                listOf(UiMessage.Res(model.shortLabelRes), freed / BYTES_PER_MB),
            ),
        ) }
        reload()
    }

    /**
     * **`Downloads.cancel` BEFORE the delete, always.**
     *
     * `InstalledModels.remove` says so in its own KDoc and cannot enforce it: it is a `File`
     * class with no view of the transfers. Deleting under a live writer is `M15` from the other
     * side — the downloader is parked in a blocking read, its `renameTo` lands on a path that
     * has stopped existing, and the failure reaches the person as *"There isn't room on this
     * headset"* on a headset with plenty. `ModelDownloads.cancel` is a `cancelAndJoin`, so when
     * it returns the writer is gone and its partial with it; anything still on disk afterwards
     * is ours to remove.
     *
     * Cancelling a model that is not downloading is a no-op, which is why this is unconditional
     * rather than guarded by a `progress(model) != null` read — a check between the read and the
     * delete is a window, and the no-op costs nothing.
     */
    private suspend fun stopThenDelete(model: WhisperModel): Long {
        downloads.cancel(model)
        // The row this screen is showing progress for has just been stopped for everyone; if it
        // was the one Cancel would have named, that name is now stale.
        if (watching == model) watching = null
        return withContext(io) { installedModels.remove(model) }
    }

    fun downloadModel() {
        // No `downloadJob`. The transfer belongs to `ModelDownloads`, not to this screen — two
        // screens each cancelling their own job into one `.part` file is `I-05`, and the result
        // was a model that could never finish.
        guarded {
            val model = chosenModel()
            // See `VoiceViewModel.watching`: Cancel has to name the transfer this screen is
            // showing, not whatever the selection says by the time it is pressed.
            watching = model
            downloads.start(model).collect { progress ->
                when (progress) {
                    is DownloadProgress.Running ->
                        _state.update { it.copy(downloading = progress.bytes to progress.total) }
                    is DownloadProgress.Done -> {
                        _state.update { it.copy(downloading = null, modelPresent = true) }
                        // See [watchDownload]: the bytes are on disk now and the shelf is built
                        // from the disk.
                        refreshInstalled()
                    }
                    is DownloadProgress.Failed -> {
                        lastFailedAction = { downloadModel() }
                        _state.update { it.copy(
                            downloading = null,
                            // **The button names the cost** (`REQ-047`, `H8`): *Resume* when
                            // usable bytes survived on disk, *Download again* when they did
                            // not. Only this collector knows — an `AppError` carries no partial
                            // file, so the mapper cannot tell.
                            message = UiStateMapper.map(progress.error).copy(
                                action = if (progress.resumable) {
                                    UiAction.RESUME_DOWNLOAD
                                } else {
                                    UiAction.DOWNLOAD_AGAIN
                                },
                            ),
                        ) }
                    }
                }
            }
        }
    }

    /**
     * Cancels **the download, for everyone**, and that is a behaviour change worth stating: it
     * used to end this screen's view of a transfer that carried on for the other one.
     */
    fun cancelDownload() {
        val model = watching ?: return
        guarded {
            downloads.cancel(model)
            watching = null
            _state.update { it.copy(downloading = null) }
        }
    }

    /** The model this screen's collector is attached to. See `VoiceViewModel.watching`. */
    private var watching: WhisperModel? = null

    /** What else is transferring, so a 539 MB download does not become invisible when the person looks elsewhere. */
    private suspend fun chosenModel(): WhisperModel =
        withContext(io) { WhisperModel.byKey(settings.get(SecureSettings.KEY_STT_MODEL)) }

    private suspend fun otherDownloadsFor(selected: WhisperModel): Map<WhisperModel, Pair<Long, Long>> =
        downloads.active()
            .filterKeys { it != selected }
            .mapNotNull { (model, progress) ->
                (progress as? DownloadProgress.Running)?.let { model to (it.bytes to it.total) }
            }
            .toMap()

    /**
     * Measures the recordings, off the drawing thread.
     *
     * Walking a tree that can hold a year of dictation — about 7 GB at twenty a day — is exactly
     * what `T-019`'s R1 is about. It lands in state when it lands.
     */
    fun refreshAudioUsage() {
        guarded {
            vault.audioUsage().onSuccess { usage ->
                _state.update { it.copy(audioUsage = usage) }
            }
        }
    }

    /** The choice itself is stored; nothing is deleted until the person presses the button. */
    fun saveAudioRetention(days: Int) {
        guarded {
            withContext(io) { settings.put(SecureSettings.KEY_AUDIO_RETENTION_DAYS, days.toString()) }
            _state.update { it.copy(audioRetentionDays = days) }
        }
    }

    /**
     * Deletes every recording older than the chosen age **now**, on an explicit press.
     *
     * `onSwept` carries what went so the caller can clear the notes' `audioPath` — the vault
     * deletes files and knows nothing about rows, and a sweep that leaves the rows behind turns
     * every swept note into a *Transcribe again* button that fails.
     */
    fun deleteOldRecordings() {
        val days = _state.value.audioRetentionDays
        if (days <= 0) return
        guarded {
            val cutoff = System.currentTimeMillis() - days * MILLIS_PER_DAY
            vault.sweepAudio(cutoff).fold(
                onSuccess = { swept ->
                    // **The rows, not just the files.** A sweep that deletes recordings and
                    // leaves `audioPath` populated turns every swept note into a *Transcribe
                    // again* button that fails — a worse state than the disk usage it was
                    // fixing. The vault knows nothing about rows, so this is where it belongs.
                    swept.noteIds.forEach { id ->
                        notes.get(id)?.let { note ->
                            if (note.audioPath != null) notes.upsert(note.copy(audioPath = null))
                        }
                    }
                    _state.update { it.copy(
                        message = UiMessage(R.string.state_recordings_deleted, listOf(swept.count)),
                    ) }
                    refreshAudioUsage()
                },
                onFailure = { failure -> _state.update { it.copy(message = failure.toMessage()) } },
            )
        }
    }

    fun retryVaultSync() {
        guarded { vaultMirror.retryFailed() }
    }

    fun retry() {
        val action = lastFailedAction
        lastFailedAction = null
        _state.update { it.copy(message = null) }
        action?.invoke() ?: reload()
    }

    fun dismissMessage() { _state.update { it.copy(message = null) } }

    private fun put(key: String, value: String, field: SavedField = SavedField.OTHER) {
        guarded { if (write(key, value, field)) reload() }
    }

    /**
     * One write, and the state it leaves behind. `suspend` so several can share a coroutine and
     * therefore leave **one** terminal state — see [saveCloud], which is why this was split out
     * of [put].
     */
    private suspend fun write(key: String, value: String, field: SavedField): Boolean {
        val result = withContext(io) { settings.put(key, value) }
        return result.fold(
            onSuccess = {
                // `it.savedTick`, not `_state.value.savedTick`: a counter incremented from a
                // re-read of the flow is the very read-modify-write this `update` exists to
                // remove, and it is the one field here where a lost increment is visible — the
                // confirmation beside a setting would simply not appear (`B-211`).
                _state.update { it.copy(savedTick = it.savedTick + 1, lastSaved = field) }
                true
            },
            onFailure = { failure ->
                lastFailedAction = { put(key, value, field) }
                _state.update { it.copy(message = failure.toMessage()) }
                false
            },
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /**
         * Decimal, matching `settings_download_progress` and the catalogue's own figures — the
         * model chips say *190 MB* because Hugging Face reports 190 085 487 bytes, and a
         * removal reporting *181 MB freed* for the same file would read as a different model.
         */
        const val BYTES_PER_MB = 1_000_000L
    }
}

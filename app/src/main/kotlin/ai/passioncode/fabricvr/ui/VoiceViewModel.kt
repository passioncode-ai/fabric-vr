package ai.passioncode.fabricvr.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.passioncode.fabricvr.Cue
import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.PendingDictation
import ai.passioncode.fabricvr.FeedbackCues
import ai.passioncode.fabricvr.Graph
import ai.passioncode.fabricvr.remoteReady
import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.UiMessage
import ai.passioncode.fabricvr.common.UiStateMapper
import ai.passioncode.fabricvr.common.runCatchingCancellable
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.AudioRecorder
import ai.passioncode.fabricvr.stt.Recorder
import ai.passioncode.fabricvr.stt.DownloadProgress
import ai.passioncode.fabricvr.stt.Downloads
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.PcmBuffer
import ai.passioncode.fabricvr.stt.SttEngine
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.stt.WavWriter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What the capture sheet is doing right now. Each state has its own words on screen. */
/** What the banner's *Retry* should do. See [VoiceViewModel.retry]. */
internal enum class RetryRoute { TRANSCRIPTION, DOWNLOAD, RECORD, NOTHING }

/**
 * Which of the three failures the person is looking at, decided from state alone.
 *
 * A recording in hand wins: it is the only one of the three that can be lost, and re-running a
 * decode costs nothing. A download already in flight is not retried — pressing Retry under a
 * progress bar should not start a second transfer.
 */
internal fun retryRoute(state: VoiceState, hasPcm: Boolean, modelMissing: Boolean): RetryRoute = when {
    state is VoiceState.Downloading -> RetryRoute.NOTHING
    hasPcm -> RetryRoute.TRANSCRIPTION
    modelMissing -> RetryRoute.DOWNLOAD
    else -> RetryRoute.RECORD
}

sealed interface VoiceState {
    data object Idle : VoiceState

    /**
     * The microphone was **refused**, and this is where the explanation belongs.
     *
     * It is not the state a first press produces any more (`T-031`). A first press asks the
     * system for the permission, so the person meets the OS dialog rather than a button that has
     * relabelled itself to *Allow the microphone* in the place they just pressed — which was
     * `D-05`'s first extra tap. An explanation before the dialog is noise; after a refusal it is
     * the only useful thing on the screen.
     */
    data object NeedsPermission : VoiceState
    data class Recording(val level: Float, val samples: Int) : VoiceState
    data object Transcribing : VoiceState

    /**
     * Waiting for the speech engine rather than for speech: the context is loading, or another
     * transcription is still inside it.
     *
     * A separate state and not a flag on [Transcribing], because what the person is owed is
     * different — *"Switching to the medium model…"* answers "why is nothing happening", and
     * *"Transcribing…"* does not. Before `T-018` this wait was not slow, it was a **freeze**: the
     * close blocked Main for the rest of the running transcription.
     */
    data class PreparingEngine(val model: WhisperModel) : VoiceState

    /**
     * The recorder is running and the OS is handing it **empty audio** (`REQ-052`, audit `H11`).
     *
     * *Meta Horizon OS Audio*: when the system or another app takes the microphone, "the
     * microphone stream isn't closed and is provided empty audio data". So the app would record
     * up to ten minutes of digital silence and hand it to whisper without a single error — and
     * then say *Nothing heard*, which blames the person for the headset's decision. A state of
     * its own, with its own sentence, because the two situations need opposite actions: one asks
     * you to speak up, the other to unmute.
     *
     * It carries [level] and [samples] so the meter and the clock keep their values across the
     * transition rather than resetting — the recording did not stop.
     */
    data class Silenced(val level: Float, val samples: Int) : VoiceState

    data class Ready(val transcript: Transcript, val audioPath: String?) : VoiceState
    data object NothingHeard : VoiceState
    data class Downloading(val bytes: Long, val total: Long) : VoiceState
    data class Failed(val message: UiMessage) : VoiceState
}

/**
 * Whether the microphone is open, whatever the OS is putting through it.
 *
 * [VoiceState.Silenced] is a recording in progress, so every "is a recording running" question —
 * the button's label, the disabled header, `BackHandler`, the stop on navigation — has to answer
 * yes for it. Written once here rather than as `is Recording || is Silenced` at ten call sites,
 * where the eleventh is the one that gets forgotten.
 */
val VoiceState.isRecording: Boolean
    get() = this is VoiceState.Recording || this is VoiceState.Silenced

class VoiceViewModel(
    private val recorder: Recorder = Graph.recorder,
    private val modelStore: suspend () -> ModelStore = { Graph.currentModelStore() },
    /**
     * Transcription as one whole call, not an engine handed out and kept.
     *
     * It was `() -> SttEngine`, which returned an `SttRouter` holding the process's whisper
     * context — a reference that outlived any lock, so nothing could promise the context was
     * still alive when it was used. `Graph.withStt` runs the work *inside* the owner's mutex
     * instead (`T-018`). `onWait` is called only when the call cannot start at once — a model
     * load, or a transcription already running — and it is what lets this screen say so.
     */
    private val transcribeWith: suspend (ShortArray, String?, (WhisperModel) -> Unit) -> Result<Transcript> =
        { pcm, langHint, onWait -> Graph.withStt(onWait) { it.transcribe(pcm, langHint = langHint) } },
    /**
     * How far the running on-device transcription has got, 0..100 (`B-178`).
     *
     * A seam for the same reason every other one here is: the default reaches [Graph], which is an
     * `object` with a `lateinit` context, and a test that could not replace it could not observe
     * the poll at all. See [Graph.transcriptionProgress] for why it is a poll rather than a flow.
     */
    private val progressOf: () -> Int = Graph::transcriptionProgress,
    private val language: suspend () -> String = Graph::sttLanguage,
    /**
     * Whether the chosen speech provider can reach anything. Not "is a server URL saved": that
     * read `KEY_WHISPER_SERVER_URL` and ignored `KEY_STT_PROVIDER` entirely, so somebody who chose
     * *Cloud service* and never had the on-device model **could not record at all** — the button
     * refused before a word was spoken (`A-05`). It is the same predicate `Graph` routes with.
     */
    private val remoteReady: suspend () -> Boolean = { remoteReady(Graph.sttProvider(), Graph.settings) },
    /**
     * The process's downloads, not this screen's.
     *
     * It was `() -> Flow<DownloadProgress>` resolving to a fresh `ModelDownloader` per call, and
     * `SettingsViewModel` had its own. Both wrote into the same `.part` file, each digesting only
     * its own bytes, so both failed the pinned checksum and the model could never finish —
     * `I-05`, and it is reached by doing exactly what the app says: the banner here offers
     * *Download*, and so does Settings. [ModelDownloads] makes the second press join the first
     * transfer.
     */
    private val downloads: Downloads = Graph.downloads,
    private val chosenModel: suspend () -> WhisperModel = Graph::whisperModel,
    /** Where speech goes, for the disclosure on the screen that sends it (`G-02`). */
    private val currentProvider: suspend () -> SttProvider = Graph::sttProvider,
    private val audioDir: () -> File = { File(Graph.appContext.filesDir, "audio") },
    /**
     * Where a finished dictation waits for a note (`REQ-046`, audit `H1`).
     *
     * The transcript stops belonging to this screen the moment it exists. See [DictationOutbox]
     * for the whole argument; what it changes here is that `Ready` is a **view** of the outbox
     * rather than the only place the words live.
     */
    private val outbox: DictationOutbox = Graph.dictationOutbox,
    /**
     * The scope a transcription runs on. **Not `viewModelScope`** (`REQ-046`, `H1`).
     *
     * `stopAndTranscribe` used to launch on `viewModelScope`, so leaving the Space mid-dictation
     * — which ends `ImmersiveActivity` — cancelled the transcription, and `onCleared` then
     * deleted the WAV because the state was `Transcribing` rather than `Ready`. The panel has a
     * different `VoiceViewModel`, so nothing was waiting on return: the words and the recording
     * were both gone, for the one action this product exists to perform. The same loss happened
     * on system Back out of the editor.
     *
     * `DEC-0034` says leaving is safe "because it transcribes rather than discards", and that
     * was true of the *call* and false of its *outcome* — the decision needs the refinement this
     * change earns rather than the other way round.
     */
    private val appScope: CoroutineScope = Graph.scope,
    private val last: LastRecording = LastRecording(),
    /**
     * Where this view model's blocking work goes.
     *
     * A parameter and not a hard-coded `Dispatchers.IO` for the reason `SettingsViewModel` already
     * records: a test cannot wait for work on the real IO pool, so a hard-coded dispatcher makes
     * every assertion about ordering a race. `StandardTestDispatcher` here is what makes
     * `VoiceViewModelThreadTest` deterministic.
     *
     * `IO` rather than `Default` because all of it is genuine blocking IO — Keystore decrypts,
     * `File.exists`, a two-megabyte WAV write. A future CPU-bound path takes `Default` explicitly
     * rather than borrowing this one.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * A sound, and in the Space a pulse, for the three moments of a recording (`REQ-062`,
     * audit `M22`).
     *
     * The person is usually looking at a streamed desktop rather than at this panel, so start,
     * stop and the ten-minute cap were happening with no evidence they had. Each cue accompanies
     * a line this screen already draws, which is Meta's own rule for them.
     */
    private val cues: FeedbackCues = Graph.feedbackCues,
    /**
     * The cap, as a parameter so a test does not have to record ten minutes of virtual time to
     * reach it. Production never passes it; [MAX_SAMPLES] is the number and `DEC-0032` is why.
     */
    maxSamples: Int = MAX_SAMPLES,
) : ViewModel() {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /**
     * "The recording could not be written, and your words are safe" (`B-092`, `G-05`).
     *
     * **Not a [VoiceState.Failed]**, and the distinction is the whole row. The transcription is
     * still going to succeed and the note is still going to be written; what failed is the WAV,
     * which costs the person *Transcribe again* on that row and nothing else. Parking the machine
     * in `Failed` would throw away a dictation to report a degradation, which is the shape `A-03`
     * is about. So it is a notice beside the flow rather than a state inside it — the same
     * relationship `NotesUiState.message` has to the list.
     *
     * It was `runCatching { … }.getOrNull()`: a full disk produced a note with `audioPath = null`
     * and not one word on screen, and the person found out months later that a row would not
     * re-transcribe.
     */
    private val _recordingLost = MutableStateFlow<UiMessage?>(null)
    val recordingLost: StateFlow<UiMessage?> = _recordingLost.asStateFlow()

    /** The screen has shown [recordingLost]; it must not reappear on the next recomposition. */
    fun recordingLostShown() {
        if (_recordingLost.value != null) _recordingLost.value = null
    }

    /**
     * How far the running transcription has got, 0..100, or 0 when none is running (`B-178`).
     *
     * **Polled, not pushed**, because whisper reports progress on native threads that are not
     * attached to the JVM (`DEC-0060`); [Graph.transcriptionProgress] is a volatile load. The
     * cadence is [PROGRESS_POLL_MS].
     *
     * **The ticker is keyed on the STATE and shared `WhileSubscribed`, not launched beside the
     * decode**, and the first version was the latter. A `launch { while (isActive) { …; delay() } }`
     * inside the transcription job is correct in production and a **trap under a test scheduler**:
     * `advanceUntilIdle()` advances virtual time until nothing is pending, and an unbounded delay
     * loop is pending for ever. `DictationSurvivalTest` holds a decode open on a
     * `CompletableDeferred` precisely so it can assert what happens mid-transcription, and that
     * test stopped terminating — not flaky, not slow: a spin in virtual time that `runTest`'s own
     * timeout cannot interrupt. Watched for twelve minutes before it was diagnosed.
     *
     * Keying on the state is also the better shape for the reason the row gives: nothing ticks
     * unless a screen is subscribed AND a decode is running, so an idle panel costs nothing and
     * the bar cannot outlive the state it describes. Both surfaces get the retry's wait for free,
     * because neither this nor they know which call produced the state.
     *
     * Zero is also the honest answer for a cloud or whisper-server transcription, which reports no
     * progress at all; the screen draws the bar only for a non-zero value, so a remote decode
     * keeps the wordless *Transcribing…* it has always had rather than gaining a bar stuck at 0 %.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val transcriptionProgress: StateFlow<Int> = _state
        .flatMapLatest { current ->
            if (current is VoiceState.Transcribing || current is VoiceState.PreparingEngine) {
                flow {
                    while (true) {
                        emit(progressOf())
                        delay(PROGRESS_POLL_MS)
                    }
                }
            } else {
                flowOf(0)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    /**
     * "Ask the system for the microphone, now."
     *
     * **An event from the view model rather than a branch in the button's `when`**, and the
     * choice is `D-05`'s correction. `start()` used to set `NeedsPermission` and wait; the
     * composable's button then relabelled itself to *Allow the microphone*, and only **that**
     * press reached `PermissionRequester`. Two presses where the contract (`SCN-013`) names one.
     *
     * It cannot simply be called from here: `ImmersiveActivity` is not a `ComponentActivity`, so
     * `rememberLauncherForActivityResult` cannot resolve a registry through the panel's view tree
     * and the request belongs to whichever activity is hosting. The screen holds the requester;
     * this says when.
     *
     * `Channel` rather than a `SharedFlow`: asking twice because a state was replayed to a
     * returning screen would put a system dialog in front of somebody who did not press
     * anything. A `CONFLATED` channel delivers each ask exactly once, and coalesces two presses
     * arriving before the collector runs into one dialog.
     */
    private val _permissionAsks = Channel<Unit>(Channel.CONFLATED)
    val permissionAsks: Flow<Unit> = _permissionAsks.receiveAsFlow()

    /**
     * "The model arrived."
     *
     * `DownloadProgress.Done` used to become `VoiceState.Idle` and the progress bar simply
     * vanished — so a person's only evidence that four minutes of waiting had worked was an
     * absence (`D-05`). One event, rendered in the same transient place as *Saved, and copied*.
     */
    private val _modelArrivals = Channel<Unit>(Channel.CONFLATED)
    val modelArrivals: Flow<Unit> = _modelArrivals.receiveAsFlow()

    /**
     * Whether the speech model is on the headset, for the two empty states to choose between.
     *
     * Not a "has run before" flag in `SecureSettings`: the long empty state is **about** the
     * download, so the thing it should key on is whether the download is still owed. A flag
     * would go stale the first time somebody removed a model in Settings.
     */
    private val _modelPresent = MutableStateFlow(false)
    val modelPresent: StateFlow<Boolean> = _modelPresent.asStateFlow()

    /**
     * The model this screen would download, so the first-run sentence can name its size.
     *
     * `today_empty_first_run` hard-coded "190 MB", which is the default and wrong for four of the
     * five models a person can choose in Settings — the identical defect `T-031` fixed one string
     * away in `action_download_model`, and exactly what `T-032` exists to prevent. Found by the
     * unit tier of step 8's verification.
     */
    private val _chosenModel = MutableStateFlow(WhisperModel.DEFAULT)
    val chosen: StateFlow<WhisperModel> = _chosenModel.asStateFlow()

    /**
     * Where this recording will be transcribed, so the screen that MAKES it can say so.
     *
     * `G-02`: speech has three disclosure strings and they are all in Settings, beside the
     * choice — a person who chose *Cloud service* in July presses Record in September with
     * nothing on the screen that sends their voice. The choice is disclosed; the act is not.
     */
    private val _provider = MutableStateFlow(SttProvider.DEFAULT)
    val provider: StateFlow<SttProvider> = _provider.asStateFlow()

    /**
     * The outbox entry this screen is showing as [VoiceState.Ready], if any.
     *
     * `@Volatile` because it is written from [appScope], which is `Dispatchers.Default`, and read
     * on the caller's thread.
     */
    @Volatile
    private var pendingId: String? = null

    /**
     * True while a surface intends to commit the dictation itself — the editor, which appends to
     * the note already open. See [reserveDictations].
     */
    @Volatile
    private var reserved: Boolean = false

    /** This view model's identity in the outbox, so a release can only free its own hold. */
    private val holderId: String = "voice-" + System.identityHashCode(this).toString(16)

    init {
        refresh()
        // **The outbox, not this screen, decides when the dictation is finished with.** Whoever
        // commits it — this host's `NotesViewModel`, the other host's, or the editor — takes the
        // entry out, and that is the signal to stop showing `Ready` and let the button work
        // again. Before the outbox this was a callback from the commit, which only existed while
        // the composition that started it did.
        launchGuarded {
            outbox.pending.collect { entry ->
                val mine = pendingId ?: return@collect
                if (entry?.id == mine) return@collect
                stopShowing(mine)
            }
        }
    }

    /**
     * The dictation this screen was showing has been written by somebody; stop showing it.
     *
     * **It is a method rather than three lines inside the collector because the collector cannot
     * be relied upon to run** (`B-212`). `DictationOutbox.pending` is a `StateFlow`, and a
     * `StateFlow` that goes `null → entry → null` while a collector is suspended emits **nothing
     * at all**: the value it resumes on equals the one it last delivered. That is the ordinary
     * case on Today, not a rare one — `NotesViewModel` is constructed before `VoiceViewModel` in
     * `TodayScreen`'s parameter list, so its drain is resumed first, claims the entry and puts the
     * flow back to `null` before this collector is scheduled. The measured consequence was
     * `VoiceState.Ready` that never cleared: `pendingId` kept an id nothing held, and `last` kept
     * an audio path `Vault.adoptOrKeep` had already moved, so *Discard* was offered for a file
     * that was no longer there.
     *
     * @param id the entry this screen believes it is showing. A no-op for any other, so a late
     *   call cannot cancel a dictation that started after it.
     */
    private fun stopShowing(id: String) {
        if (pendingId != id) return
        pendingId = null
        last.clear()
        if (_state.value is VoiceState.Ready) _state.value = VoiceState.Idle
    }

    /**
     * Says that this surface will commit the next dictation itself (`REQ-046`).
     *
     * The editor appends a dictation to the note already open rather than making one of its own,
     * and Today's `NotesViewModel` is still alive in the back stack while the editor is up — so
     * without a hold both would reach the same entry and the faster one would win. The hold is
     * taken while the editor is composed and dropped the moment it is not, including when its
     * host dies: an entry nobody is holding is drained as a note of its own. Losing the *append*
     * is a smaller harm than losing the words, and the person can see the note.
     */
    fun reserveDictations(reserve: Boolean) {
        reserved = reserve
        if (!reserve) outbox.release(holderId)
    }

    /**
     * Takes the dictation this screen is showing, for this screen.
     *
     * @return the entry, which the caller must [settle] once the note carrying it is durable
     *   (`B-237`); or null when another surface already has it — in which case the caller must do
     *   nothing at all, because a note is being written from it somewhere else.
     */
    fun claimPending(): PendingDictation? {
        val id = pendingId ?: return null
        return outbox.claim(id)
    }

    /** Ends a claimed dictation's life on disk — the note carrying it has landed (`DEC-0088`). */
    fun settle(entry: PendingDictation) = outbox.settle(entry)

    /**
     * Re-reads what is on disk and which model is chosen.
     *
     * **Public, and called when the screen comes back**, because both facts are owned elsewhere:
     * Settings can remove a model, download one, or choose a different one, and this view model
     * is scoped to the back-stack entry of a start destination that survives that trip. The
     * first version cached both in `init` and the doc comment beside it claimed a flag "would go
     * stale the first time somebody removed a model in Settings" — which is what it then did.
     */
    fun refresh() {
        launchGuarded {
            // **Each read keeps its own answer.** These are four Keystore and disk reads and
            // none of them is worth the screen: a decrypt that throws used to take the whole
            // coroutine with it, so one unreadable setting left the model presence, the
            // provider and the clipboard preference all at their defaults — and the throw
            // escaped `viewModelScope` and failed whichever test happened to be running.
            // `C-04`'s lesson in a new place: an unreadable setting is not a reason to lose the
            // others.
            runCatchingCancellable { chosenModel() }.onSuccess { _chosenModel.value = it }
            runCatchingCancellable { withContext(io) { modelStore().isPresent() } }
                .onSuccess { _modelPresent.value = it }
            runCatchingCancellable { currentProvider() }.onSuccess { _provider.value = it }
        }
    }

    private var recordJob: Job? = null

    /**
     * The recording. See [PcmBuffer]: it was a `mutableListOf<Short>` fed an `ArrayList<Short>`
     * per chunk, which is ten times the memory of the audio it holds and a lock held for an
     * `addAll` over up to 960 000 boxed references (`E-02`, `I-10`).
     *
     * Still guarded, and the lock is still the recorder's KDoc's lock: the producer appends from
     * an IO thread while `stopAndTranscribe` reads, and without it (and the join below) a stop
     * mid-chunk threw `ConcurrentModificationException` and lost the recording at the exact
     * moment the person expected it kept. Only the size of the critical section changed.
     */
    private val buffer = PcmBuffer(maxSamples)

    /** Kept after transcription so a wrong language detection can be re-run without speaking again. */
    /**
     * What the last recording left behind. A field rather than two, and injectable, because the
     * state a failed decode leaves behind is exactly what `T-005` is about and a test had no way
     * to construct it — the alternative was a setter that existed only for tests, which is a
     * production API shaped by its test.
     */
    class LastRecording(var pcm: ShortArray? = null, var audioPath: String? = null) {
        fun clear() { pcm = null; audioPath = null }
    }

    /**
     * Called from the recorder's IO thread with the recorder's **own** array — see [Recorder].
     *
     * When the buffer says it is full the recording ends exactly as a deliberate stop does.
     * Discarding at the limit would be `A-03` in a new place: the app throwing away a recording
     * it had just told the person it was making.
     */
    private fun appendSamples(src: ShortArray, count: Int) {
        if (buffer.append(src, count)) return
        // `append` keeps returning false once full, so this fires repeatedly. Idempotence comes
        // from `stopAndTranscribe`'s own first line, which returns unless the state is still
        // `Recording` — asserted by `reaching the maximum transcribes exactly once`, because
        // relying on a guard in another method is the kind of thing that quietly stops being true.
        //
        // **`AUTO_STOP`, not `RECORD_STOP`** (`REQ-062`). This is the app interrupting somebody
        // who is still speaking (`DEC-0032`), and a cue that sounds like the one their own press
        // makes would say the opposite of what happened.
        launchGuarded { stopAndTranscribe(completed = true, cue = Cue.AUTO_STOP) }
    }

    private fun drainBuffer(): ShortArray = buffer.drain()
    private fun clearBuffer() = buffer.clear()

    /**
     * The screen that owns this recording is going away — stop it and keep what was said.
     *
     * A named intention rather than `stopAndTranscribe(true)` with a comment at three call sites.
     * **Transcribe, never discard:** the view model outlives the composition (it is scoped to the
     * back-stack entry), so the transcript lands in `VoiceState.Ready` and is waiting when the
     * person returns. Throwing it away would discard speech they produced deliberately.
     */
    fun stopForNavigation() {
        // Or a start still resolving — `stopAndTranscribe` handles both, and a screen going away
        // during the window must not leave the microphone opening behind it.
        if (_state.value.isRecording || recordJob?.isActive == true) {
            stopAndTranscribe(completed = true)
        }
    }

    fun needsPermission(): Boolean = !recorder.hasPermission()

    /** Two `stat` calls. `suspend` since `T-019`: it is disk, and it was on the drawing thread. */
    suspend fun modelMissing(): Boolean = withContext(io) { !modelStore().isPresent() }

    /**
     * True between asking the system for the microphone and being answered (`REQ-048`, `H10`).
     *
     * **Without it two presses destroy the answer to the first.** A second `requestPermissions`
     * while one dialog is already up is answered by AOSP *immediately*, with an **empty**
     * `grantResults`; the host reads that as "not granted", and
     * `shouldShowRequestPermissionRationale` is false while the dialog is showing, so it is
     * reported as **permanently denied**. In the same breath the host clears its `pending`
     * callback — so when the person actually taps *Allow*, the result reaches nobody and the
     * screen stays on the one message this app cannot recover from by itself.
     *
     * The guard belongs here rather than in the two hosts because both of them would need it and
     * one of them is a plain `android.app.Activity` with no result registry — and because this is
     * the tier a test can drive (`SI-05`). The hosts' own half is that an empty `grantResults` is
     * a **dismissal**, never a permanent refusal; see `PanelActivity` and `ImmersiveActivity`.
     *
     * Not `@Volatile`: every caller is on the main thread — the button, and `onPermissionResult`
     * delivered by the activity.
     */
    private var permissionInFlight = false

    fun start() {
        if (needsPermission()) {
            // **Ask, do not explain.** The reason is obvious from the button that was just
            // pressed, and in a headset a rationale screen before the system dialog is one more
            // thing to aim at. The state is left alone: the OS dialog appears over whatever is
            // there, and `onPermissionResult` decides what comes next — a recording, or the
            // explanation that `NeedsPermission` now exists for.
            if (permissionInFlight) return
            permissionInFlight = true
            _permissionAsks.trySend(Unit)
            return
        }
        beginRecording()
    }

    /**
     * The dialog went away without an answer — Back, or the shell taking the foreground.
     *
     * **A dismissal is not a refusal**, so nothing on the screen changes: the person has said
     * nothing and the app must not decide on their behalf that the microphone is denied, let
     * alone permanently. All it does is let the next press ask again.
     */
    fun onPermissionDismissed() {
        permissionInFlight = false
    }

    /**
     * Everything [start] does once the microphone is known to be available.
     *
     * Split out so `onPermissionResult` can reach it **without** re-testing the permission it was
     * just told about. If the recorder still refuses, `record()` ends immediately and the state
     * stays `Idle` — a dead press, not a loop.
     */
    private fun beginRecording() {
        // **A second tap while the first is still resolving must not start a second recorder.**
        // `T-019` moved the two reads below off the drawing thread, which opened a window in
        // which the state is still `Idle` while a recording is on its way: the button is a toggle
        // that reads `Recording` to decide between start and stop, so in that window it took the
        // start branch again — two recorder flows feeding one buffer, and only the second one's
        // job in `recordJob`, so the first held the microphone for the life of the view model.
        // Found by the group-verification pass over steps 4–5; it is a regression of `T-019`.
        if (recordJob?.isActive == true || _state.value.isRecording) return
        // **The OUTER job**, not the collection. Everything from here to the first sample is part
        // of the recording as far as stopping is concerned — a stop arriving in the window has to
        // be able to cancel it, and it could not when `recordJob` was only assigned afterwards.
        recordJob = launchGuarded {
            // One hop for both reads. `modelMissing()` is two `stat` calls and `remoteReady()`
            // builds a client and reads the Keystore (`B-111`) — both ran on Main, on every tap of
            // the control the whole product is.
            // **The size travels with the refusal** (`T-031`). It was already in hand here and
            // thrown away by a mapper branch that took no arguments, so the banner said the
            // model "isn't on this headset yet" and the person learned it was 190 MB on the
            // progress bar, after committing to it (`D-05`).
            //
            // `WhisperModel.key` rather than `ModelStore.modelName`: the file name is
            // `ggml-small-q5_1.bin`, which is not a thing to show a person, and the key is the
            // stable identity the rest of the app already maps to words.
            val refusal = withContext(io) {
                if (modelStoreIsMissing() && !remoteReady()) chosenModel() else null
            }
            if (refusal != null) {
                // **`nameRes`, because the key is not a name** (`REQ-061`, `B-157`). The banner
                // read "small, 190 MB": `small` is this enum's stable key and `Tiny`/`Small` are
                // what the person chose between in Settings. The key still travels for the log.
                _state.value = VoiceState.Failed(
                    UiStateMapper.map(
                        AppError.ModelMissing(refusal.key, refusal.bytes, refusal.shortLabelRes),
                    ),
                )
                return@launchGuarded
            }
            clearBuffer()
            _state.value = VoiceState.Recording(0f, 0)
            // **Here, not on the press** (`REQ-062`). The press can end in a refused permission
            // or a missing model, and a cue that fires before the microphone is open tells the
            // person something started when nothing did — the same lie the visual states were
            // rewritten to stop telling.
            cues.play(Cue.RECORD_START)
            // **`REQ-052` / `H11`: the OS can mute the stream without closing it.** Two
            // collectors rather than one because the two events are independent — the mute
            // arrives on its own flow and can arrive between two reads, and a read arrives
            // whether or not the mute has changed. Each writes the state from the *other's*
            // current value, so neither can win a frame and leave the screen lying.
            launch {
                recorder.silenced.collect { muted ->
                    val current = _state.value
                    if (!current.isRecording) return@collect
                    val level = (current as? VoiceState.Recording)?.level
                        ?: (current as? VoiceState.Silenced)?.level ?: 0f
                    val samples = (current as? VoiceState.Recording)?.samples
                        ?: (current as? VoiceState.Silenced)?.samples ?: 0
                    _state.value = if (muted) {
                        VoiceState.Silenced(level, samples)
                    } else {
                        VoiceState.Recording(level, samples)
                    }
                }
            }
            recorder.record(::appendSamples)
                .catch { failure -> _state.value = VoiceState.Failed(failure.toMessage()) }
                .collect { level ->
                    _state.value = if (recorder.silenced.value) {
                        VoiceState.Silenced(level.rms, level.samples)
                    } else {
                        VoiceState.Recording(level.rms, level.samples)
                    }
                }
        }
    }

    /** Inside an existing `io` hop already — [modelMissing] would add a second for nothing. */
    private suspend fun modelStoreIsMissing(): Boolean = !modelStore().isPresent()

    /**
     * @param completed false when the gesture was cancelled rather than released — the recording is
     * discarded, because a cancelled press is not a request to transcribe.
     */
    fun stopAndTranscribe(completed: Boolean = true) = stopAndTranscribe(completed, Cue.RECORD_STOP)

    /**
     * The same stop, told which cue it is (`REQ-062`).
     *
     * A private overload rather than a public boolean: the cap and a press are two events with
     * two meanings, and `stopAndTranscribe(true, true)` at a call site is how the three-intents-
     * two-handlers defect above happened in the first place.
     */
    private fun stopAndTranscribe(completed: Boolean, cue: Cue) {
        if (!_state.value.isRecording) {
            // A stop arriving inside `start()`'s resolve window. There is nothing recorded yet,
            // so there is nothing to transcribe — but the pending start must not go on to open
            // the microphone with nobody able to close it.
            if (recordJob?.isActive == true) {
                recordJob?.cancel()
                recordJob = null
                clearBuffer()
                _state.value = VoiceState.Idle
            }
            // Otherwise: releasing the button after a refused permission or a missing model must
            // not wipe the message that explains why nothing happened.
            return
        }
        if (!completed) {
            cancel()
            return
        }
        val job = recordJob
        recordJob = null
        _state.value = VoiceState.Transcribing
        // The microphone is closed and the seconds have stopped; the cue says the same thing the
        // screen just did (`REQ-062`).
        cues.play(cue)
        // **[appScope], not `viewModelScope`** (`REQ-046`, `H1`). Everything from here on has to
        // outlive the host: the WAV write, the decode that can take thirteen minutes, and the
        // hand-off to the outbox. A person who leaves the Space while it runs comes back to a
        // note; before this they came back to nothing, and the recording had been deleted by
        // `onCleared` on the way out.
        transcriptionJob = appScope.launch {
            job?.cancelAndJoin()
            val pcm = drainBuffer()
            if (pcm.size < MIN_SAMPLES) {
                _state.value = VoiceState.NothingHeard
                return@launch
            }
            last.pcm = pcm
            // A sixty-second dictation is 1.9 MB encoded and written. It was done on the thread
            // that draws, at the moment the person lets go of the button.
            //
            // **`G-05`/`B-092` lived in this `runCatching`**, and it was `.getOrNull()`: a failed
            // write produced a note with `audioPath = null` and said nothing, so the person
            // discovered months later that one row had no *Transcribe again* and no *Play*. The
            // decode carries on regardless — the words are the thing that cannot be re-made, and
            // abandoning them to report a lost recording would be the larger loss. What changes is
            // that the loss is now said out loud, once, in the same place every other notice
            // appears.
            val lang: String
            last.audioPath = withContext(io) {
                val path = runCatching {
                    WavWriter.write(File(audioDir(), "${System.currentTimeMillis()}.wav"), pcm).absolutePath
                }.onFailure { failure ->
                    Log2.w("voice.wav.failed", "reason" to (failure::class.simpleName ?: "unknown"))
                    _recordingLost.value = UiMessage(R.string.state_recording_not_kept)
                }.getOrNull()
                lang = language()
                path
            }
            transcribe(pcm, lang)
        }
    }

    /**
     * Try the **same recording** again, after a decode failed.
     *
     * Not [retry], which decides between this, a download and a new recording — the button on a
     * failed transcription used to
     * reach `cancel()`, which deletes the recording, directly under a message reading "The
     * recording is kept". Three intents were sharing two handlers; they are three methods now,
     * because a boolean at a call site is how that defect happened.
     */
    fun retryTranscription() {
        val pcm = last.pcm ?: return
        _state.value = VoiceState.Transcribing
        // [appScope] for the same reason as the first attempt: a retry that dies with the screen
        // is the defect it is retrying.
        // The retry is the same wait as the first attempt and is owed the same bar and the same
        // exit (`B-178`/`B-179`); it gets both for free, because [transcriptionProgress] keys on
        // the STATE rather than on this call.
        transcriptionJob = appScope.launch { transcribe(pcm, withContext(io) { language() }) }
    }

    /**
     * Stop the decode the person is waiting for, and **keep what it was decoding** (`B-179`).
     *
     * `DEC-0060` made cancellation reach the blocking native call — `invokeOnCancellation` sets an
     * atomic that whisper's `abort_callback` polls, because nothing can be delivered to a thread
     * parked in C — and chose `CancellationException` deliberately, so a model switch abandoning a
     * run in the background needed no new error shape. **That is not enough for a person who
     * pressed something.** Silence after a deliberate act reads as a crash, and on the `small`
     * model the act ends a thirteen-minute wait, so the one thing they need to know is whether
     * they have just thrown away ten minutes of speech.
     *
     * So: *told*, not *silent*. [last] is untouched, which is what makes the two controls under
     * the message real — *Try again* routes to [retryTranscription] because `last.pcm` is still
     * there, and *Discard* appears because [hasRecording] is still true.
     *
     * It reuses [VoiceState.Failed] rather than adding a member. That is a deliberate trade and
     * not a shortcut: a new state would have to be answered by every `when` over `VoiceState` on
     * two screens, and each of those answers would be the same one this already gives — a
     * sentence, *Try again*, *Discard*. What it costs is that a stopped decode travels in a shape
     * named after failure; the sentence it carries is the thing the person reads, and it does not
     * call anything a failure.
     */
    fun stopTranscription() {
        val job = transcriptionJob ?: return
        if (!job.isActive) return
        transcriptionJob = null
        job.cancel()
        _state.value = VoiceState.Failed(
            UiMessage(R.string.state_transcription_stopped, action = UiAction.RETRY_LOAD),
        )
    }

    /**
     * Hands this screen's recording to a note that will have no transcript, and stops owning it
     * (`B-091`, `SCN-004`).
     *
     * The scenario has always promised that when the engine fails "the audio is kept and offered
     * as a note without a transcript". `T-005` delivered the first half — the file is no longer
     * deleted at the moment of failure — and the second half was built by nobody, which made the
     * first half worse than useless: [onCleared] deletes any path nothing owns, so leaving the
     * screen after a failed decode destroyed the recording the message had just promised was
     * kept. A promise that holds until you look away is not a promise.
     *
     * It returns the path rather than writing the note, because this view model has no repository
     * and should not gain one: the note is `NotesViewModel`'s to write, on `Graph.scope`, exactly
     * as a dictation's is. Clearing [last] here is the hand-off — from this line the sweep no
     * longer claims the file, and the caller is responsible for it.
     *
     * @return null when there is no recording, so a dead press writes no empty note.
     */
    fun releaseRecordingForNote(): String? {
        val path = last.audioPath ?: return null
        last.clear()
        pendingId = null
        _state.value = VoiceState.Idle
        return path
    }

    /**
     * The decode in flight, so [onCleared] can tell "nothing is pending" from "the words are
     * seconds away". It is the one case where deleting the recording would destroy the source of
     * a transcript that is about to exist.
     */
    @Volatile
    private var transcriptionJob: Job? = null

    /**
     * Put the message away and **keep** the recording. Distinct from [cancel], which throws it
     * away: `DEC-0011` left no confirmation step, so Discard is the only way to refuse a recording
     * and must stay the only thing that deletes one.
     */
    fun dismissFailure() {
        if (_state.value is VoiceState.Failed || _state.value is VoiceState.NothingHeard) {
            _state.value = VoiceState.Idle
        }
    }

    /** True when there is a recording a person could discard or retry. */
    fun hasRecording(): Boolean = last.audioPath != null

    // **`retranscribe(language)` was deleted here** (`T-032`). It re-ran the last recording with
    // a pinned language, for when detection got it wrong; no screen called it and its string
    // (`action_rerun_language`, "re-run %1$s") was one of the twenty-two `B-26` counted. The
    // feature it belonged to is not gone — `NotesViewModel.retranscribe(note, provider, model)`
    // re-runs a *stored* note's recording and is reachable from every row — so this was the
    // capture-sheet-era path to the same idea, kept alive only by its own dead string.
    //
    // Deleting the string and keeping the method would have left a caller-less method that the
    // next sweep reads as reachable; the spec's rule was "delete both, or neither".

    private suspend fun transcribe(pcm: ShortArray, langHint: String) {
        val result = try {
            transcribeWith(pcm, langHint) { model ->
                // Only fired when the call actually has to wait. Without it the app is responsive
                // during a wait that can still be half a minute and says nothing at all, which is a
                // new defect replacing the old one rather than a fix (`T-018`).
                _state.value = VoiceState.PreparingEngine(model)
            }
        } catch (abandoned: CancellationException) {
            // **Whose cancellation is it?** (`B-251`, `DEC-0092`). If THIS coroutine was cancelled
            // — the person pressed *Stop transcribing*, which already set its own state — the
            // exception is ours and must propagate. Otherwise the engine abandoned the run: a model
            // change closed the engine it was queued on or running in (`DEC-0060`, `I-26`), and a
            // `CancellationException` thrown into a coroutine nobody cancelled ended it silently,
            // leaving *Transcribing…* on screen for ever. `last` is untouched, so *Try again* and
            // *Discard* both still reach the recording.
            currentCoroutineContext().ensureActive()
            Log2.i("voice.transcription.abandoned")
            _state.value = VoiceState.Failed(
                UiMessage(R.string.state_transcription_model_changed, action = UiAction.RETRY_LOAD),
            )
            return
        }
        result.fold(
            onSuccess = { transcript ->
                if (transcript.text.isBlank()) {
                    _state.value = VoiceState.NothingHeard
                } else {
                    // **Into the outbox first, on disk, and only then onto the screen**
                    // (`REQ-046`). The order is the guarantee: from this line the words survive
                    // this view model, this activity and this process, and `Ready` is a view of
                    // an entry that already exists rather than the only copy.
                    val entry = outbox.offer(
                        transcript,
                        last.audioPath,
                        holder = holderId.takeIf { reserved },
                    )
                    pendingId = entry.id
                    _state.value = VoiceState.Ready(transcript, last.audioPath)
                    // **And then ask whether it is still there** (`B-212`). A drain scheduled by
                    // the offer above runs before this view model's own collector does, and the
                    // flow is back at `null` by then — which a `StateFlow` does not report,
                    // because it equals the value that collector last delivered. Without this
                    // line `Ready` is a state the screen never leaves. It is posted rather than
                    // read here: the drain has been scheduled and has not run yet, so the answer
                    // on this line is always "still there".
                    launchGuarded {
                        if (outbox.pending.value?.id != entry.id) stopShowing(entry.id)
                    }
                }
            },
            onFailure = { failure -> _state.value = VoiceState.Failed(failure.toMessage()) },
        )
    }

    /**
     * **Granting records.** `SCN-013` step 3 says the recording starts without re-pressing
     * *Record*, and it was the third of `D-05`'s six taps: granting parked the machine in a state
     * that rendered nothing, the warning disappeared, the button silently said *Record* again,
     * and the person pressed it a second time to find out what had happened.
     */
    fun onPermissionResult(granted: Boolean, permanent: Boolean) {
        // The question has been answered, whatever the answer: the next press may ask again.
        permissionInFlight = false
        if (granted) {
            // **`beginRecording()`, not `start()`** — do not re-ask the question the system has
            // just answered. `start()` re-checks `hasPermission()` and asks again when it is
            // false, and a device whose check lags the grant would therefore ask, be granted,
            // ask, be granted… A shell test produced exactly that as an `OutOfMemoryError`,
            // which is a loop with no exit rather than a slow one.
            beginRecording()
            return
        }
        _state.value = if (permanent) {
            VoiceState.Failed(UiStateMapper.map(AppError.Permission("microphone", permanent = true)))
        } else {
            VoiceState.NeedsPermission
        }
    }

    /**
     * The model this screen's collector is attached to.
     *
     * **Not `chosenModel()` at cancel time.** Downloads are keyed by model since `T-021`, and the
     * selection can change while a transfer runs — so re-reading it meant Cancel stopping a
     * different model's download, or nothing at all, while the one on screen carried on. The old
     * per-screen `downloadJob?.cancel()` always cancelled what that screen had started; this
     * keeps that property without going back to a Job per screen.
     */
    private var watching: WhisperModel? = null

    fun downloadModel() {
        // No `downloadJob`: the transfer outlives this screen, which is also what makes
        // `ModelDownloader`'s resume path reachable at all (`A-22` — it had never run, because
        // leaving the screen cancelled the collection and cancellation deletes the partial).
        launchGuarded {
            val model = chosenModel()
            watching = model
            downloads.start(model).collect { progress ->
                _state.value = when (progress) {
                    is DownloadProgress.Running -> VoiceState.Downloading(progress.bytes, progress.total)
                    is DownloadProgress.Done -> {
                        // Four minutes of waiting must not end in an absence.
                        _modelPresent.value = true
                        _modelArrivals.trySend(Unit)
                        VoiceState.Idle
                    }
                    // A cancelled transfer is not a failure to report: the person did it. It is a
                    // terminal VALUE now rather than a silent freeze, which is what stops another
                    // screen parking on a progress bar for a download that no longer exists.
                    is DownloadProgress.Failed ->
                        if ((progress.error as? AppError.ModelDownload)?.reason ==
                            AppError.ModelDownload.Reason.CANCELLED
                        ) {
                            VoiceState.Idle
                        } else {
                            // **The button says what the press will cost** (`REQ-047`, `H8`).
                            // `ModelDownloader` keeps usable bytes on disk when a transfer dies
                            // mid-body and says so on the value; the mapper cannot know, because
                            // an `AppError` carries no partial file. *Resume* asks for the
                            // missing megabytes, *Download again* for all 574 of them, and
                            // offering one word for both is how a person learns not to trust it.
                            VoiceState.Failed(
                                UiStateMapper.map(progress.error).copy(
                                    action = if (progress.resumable) {
                                        UiAction.RESUME_DOWNLOAD
                                    } else {
                                        UiAction.DOWNLOAD_AGAIN
                                    },
                                ),
                            )
                        }
                }
            }
        }
    }

    /**
     * Cancels **the download, for everyone**. It used to cancel one screen's job while the other
     * screen's transfer of the same bytes carried on, which is half of `I-05`.
     */
    fun cancelDownload() {
        val model = watching ?: return
        launchGuarded {
            downloads.cancel(model)
            watching = null
            _state.value = VoiceState.Idle
        }
    }

    /**
     * The banner's *Retry*, for whichever of three things failed.
     *
     * One button stands under three failures — a decode that did not work, a model download that
     * did not finish, and a recorder that would not start — and they need three different actions.
     * Wiring it to any single one leaves the other two with a button that visibly does nothing:
     * before `T-008` it reached `cancel()`, which deleted the recording; after `T-005` it reached
     * [retryTranscription], which returns at its first line when there is no recording to retry,
     * so a failed **download** had a Retry button that was inert. The route is a pure function so
     * it can be tested without a download; `T-021` then gave [downloadModel] its seam too, so
     * every arm of this `when` is now reachable from a test.
     */
    fun retry() {
        // A launch, because `modelMissing()` is two `stat` calls and the route depends on it.
        // `retryRoute` stays a pure function of the three inputs, which is what makes it testable.
        launchGuarded {
            when (retryRoute(_state.value, hasPcm = last.pcm != null, modelMissing = modelMissing())) {
                RetryRoute.TRANSCRIPTION -> retryTranscription()
                RetryRoute.DOWNLOAD -> downloadModel()
                RetryRoute.RECORD -> start()
                RetryRoute.NOTHING -> Unit
            }
        }
    }

    /** The transcript has been handed to a note; keep the audio, forget the rest. */
    fun consumed() {
        recordJob?.cancel()
        recordJob = null
        clearBuffer()
        pendingId = null
        last.clear()
        _state.value = VoiceState.Idle
    }

    /** Discard: the recording the person rejected must not survive on the headset. */
    fun cancel() {
        recordJob?.cancel()
        recordJob = null
        clearBuffer()
        // A discard is a refusal of the dictation, so the entry goes with the file — otherwise
        // the next surface to look at the outbox would write the note the person just rejected.
        // Claimed and settled at once: nothing will ever carry these words, by the person's choice.
        pendingId?.let { id -> outbox.claim(id)?.let(outbox::settle) }
        pendingId = null
        last.audioPath?.let { path -> runCatching { File(path).delete() } }
        last.clear()
        _state.value = VoiceState.Idle
    }

    /**
     * Deletes a recording nobody adopted, when the screen goes away.
     *
     * [consumed] clears [last] once the note has taken the audio, so a path still sitting here is a
     * scratch file with no row pointing at it — and until `T-018` nothing in the app ever listed
     * that directory again.
     *
     * **Three reasons not to delete, and only the first one used to be here** (`REQ-046`, `H1`):
     *
     * - [VoiceState.Ready]: the commit is moving the file into the vault on the application
     *   scope, deliberately outliving this screen (`I-02`).
     * - **A pending dictation owns it.** The entry is in [DictationOutbox] and some
     *   `NotesViewModel` — this host's on return, or the next process's on a cold start — is
     *   going to write a note pointing at this file.
     * - **A decode is still running.** This is the case the audit found: leaving the Space
     *   during `Transcribing` cleared this view model, the state was not `Ready`, and the WAV
     *   was deleted out from under the transcription that was still reading it. The words and
     *   the only recording of them went in the same breath.
     *
     * The hold on a reserved entry is released here too: a surface that is gone is not going to
     * commit anything, and an entry nobody holds is one the next drain writes as its own note.
     */
    override fun onCleared() {
        if (reserved) {
            reserved = false
            outbox.release(holderId)
        }
        val path = last.audioPath
        val owed = _state.value is VoiceState.Ready ||
            outbox.owns(path) ||
            transcriptionJob?.isActive == true
        if (!owed && path != null) {
            runCatching { File(path).delete() }
        }
        super.onCleared()
    }

    companion object {
        /** Under a third of a second is a mis-tap, not a thought. */
        private const val MIN_SAMPLES = AudioRecorder.SAMPLE_RATE / 3

        /**
         * The longest dictation this device can honestly promise, and **the bound is transcription
         * time, not memory** (`DEC-0032`).
         *
         * Ten minutes is 19.2 MB of `ShortArray`, which is nothing. It is also about **thirteen
         * minutes of transcription** at the 1.31× real time measured on this headset with the
         * default model and four threads — during which the person has a screen that says
         * *Transcribing* and a native context that cannot be interrupted (`T-018`). Anything
         * longer is a promise the device cannot keep. The WAV is also kept for ever (`G-02`), so
         * 19 MB per dictation compounds.
         *
         * Before this constant there was no limit at all: a person who started dictating and was
         * distracted recorded until the process died of memory, and everything they *had* said
         * died with it, because a recording only becomes a file when it stops.
         *
         * Longer honestly means chunked transcription — whisper already works in 30-second
         * windows — which is a feature, not a bigger number here.
         */
        const val MAX_SECONDS = 600
        const val MAX_SAMPLES = AudioRecorder.SAMPLE_RATE * MAX_SECONDS

        /** When the countdown appears. One minute is enough warning to finish a sentence. */
        const val COUNTDOWN_SECONDS = 60

        /**
         * How often the transcription's progress is read (`B-178`).
         *
         * Half a second, and the number is a trade between two cheap things. The read itself is a
         * volatile load — `B-146`'s whole point is that asking costs nothing — so the cost is the
         * recomposition it triggers, and a bar that moves twice a second on a thirteen-minute wait
         * reads as alive without spending a frame budget the meter already competes for. Faster
         * buys nothing a person can see; slower starts to look like the freeze the bar exists to
         * disprove.
         */
        const val PROGRESS_POLL_MS = 500L
    }
}

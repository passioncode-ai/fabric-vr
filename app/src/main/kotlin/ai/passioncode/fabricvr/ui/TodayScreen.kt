package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.PermissionRequester
import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.stt.AudioRecorder
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.UiMessage
import ai.passioncode.fabricvr.common.theme.Tokens
import ai.passioncode.fabricvr.common.ui.ErrorBanner
import ai.passioncode.fabricvr.common.ui.FabricChip
import ai.passioncode.fabricvr.common.ui.FabricDangerButton
import ai.passioncode.fabricvr.common.ui.FabricWrapRow
import ai.passioncode.fabricvr.common.ui.FabricIconButton
import ai.passioncode.fabricvr.common.ui.FabricOutlinedButton
import ai.passioncode.fabricvr.common.ui.FabricTextButton
import ai.passioncode.fabricvr.notes.Note
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The whole product on one screen: press once to record, press again to stop, and the words are a
 * note and are on the clipboard.
 *
 * It has no sheet, no dialog and no hold gesture, and that is the design rather than an omission.
 * A hold asks a person to keep a controller ray steady on a target for the length of a thought; a
 * sheet is a second window that has to be dismissed, and in a headset a window that does not close
 * is a wall. Every state this flow can be in — needing the microphone, needing the model,
 * recording, transcribing, failing — is drawn in place, above the same button.
 *
 * ## The frame, and why it is this one (`T-030`, `DEC-0043`)
 *
 * ```
 * Column(fillMaxSize)          <- does NOT scroll; it is the frame
 *  ├ header Row                 fixed
 *  ├ status Box                 FIXED height (Tokens.Space.statusSlot), scrolls inside
 *  ├ record Button              fixed, ALWAYS at the same y
 *  └ LazyColumn(weight(1f))     everything that can grow
 *       ├ error banner · confirmations · undo
 *       ├ the day card · the tag chips
 *       ├ the notes
 *       └ "and N more" + Show all
 * ```
 *
 * Two rules, and every layout decision here follows from them:
 *
 * 1. **Nothing may change the record button's height allocation.** It is the one thing a person
 *    aims a controller ray at for the length of a thought, and `B-13` is that it moved twice per
 *    dictation because the message above it was taller in one state than in another.
 * 2. **Everything that can grow lives in the `LazyColumn`.** Before `T-030` the banner, the
 *    confirmation line and the Undo row were siblings of an *unweighted* `LazyColumn` in a
 *    `Column` with no scroll, so the list was measured with whatever was left — which at the
 *    panel's declared minimum was nothing, and the notes simply vanished with no way to reach
 *    them (`B-11`).
 *
 * **This frame differs from `T-030`'s spec diagram, which kept the day card and the tag chips as
 * chrome above the button.** Its arithmetic omitted the 128 dp button itself: header 64 + slot
 * 130 + button 128 + three 16 dp gaps + 48 dp of padding is 418 dp of fixed chrome, against the
 * 360 dp the manifest used to declare — so the spec's own target, "at the minimum this screen
 * shows one note", was unreachable by 58 dp before a single note, day card or chip was drawn.
 * Two things follow and both are in this change: the day card and the chips became items in the
 * list, and the manifest's `minHeight` was raised to the size this frame can actually serve
 * ([PanelMinimum]). A declared minimum the product cannot honour is a worse defect than the
 * layout it hides.
 */
@Composable
fun TodayScreen(
    permissionRequester: PermissionRequester,
    onOpenNote: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onEnterSpace: ((onFailure: (Throwable) -> Unit) -> Unit)? = null,
    onLeaveSpace: (() -> Unit)? = null,
    notesViewModel: NotesViewModel = viewModel(),
    voiceViewModel: VoiceViewModel = viewModel(),
) {
    val state by notesViewModel.state.collectAsStateWithLifecycle()
    /**
     * **The `State` object, not its value** (`B-096`).
     *
     * `val voice by …` is a read, and a read in this function's body is a subscription for the
     * whole of it — so every `VoiceState.Recording(level, samples)` the recorder emitted, fifty a
     * second, invalidated `TodayScreen`, which rebuilt `TodayFrame` and the entire list of notes
     * beneath it against a 13.9 ms budget. Holding the `State` and handing a lambda down defers
     * every read to the composable that needs it; what this function needs is two coarse facts,
     * and both are derived below so that a level change is one comparison rather than a frame.
     */
    val voiceState = voiceViewModel.state.collectAsStateWithLifecycle()
    val voice: () -> VoiceState = remember(voiceState) { { voiceState.value } }
    val transcriptionPercent by voiceViewModel.transcriptionProgress.collectAsStateWithLifecycle()
    val recordingLost by voiceViewModel.recordingLost.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val playback = rememberAudioPlayback()

    /** Shown briefly after a dictation, because a clipboard gives no sign that it changed. */
    var justCopied by remember { mutableStateOf(false) }

    /**
     * Whether that dictation's words actually reached the clipboard.
     *
     * **The preference is not the answer** (`B-212`). This line used to be filled from
     * `VoiceViewModel.copyTranscript` — the switch — so it claimed a copy whenever the switch was
     * on, including in the case the audit found, where the copy never ran. The commit reports
     * what it did; this is that report.
     */
    var justCopiedReally by remember { mutableStateOf(false) }

    /**
     * Whether that dictation actually came from the headset after the chosen provider refused.
     * `SttRouter` has always recorded this on the transcript and nothing rendered it, which is why
     * a cloud-without-a-key dictation was indistinguishable from one somebody ran locally on
     * purpose.
     */
    var fellBackToHeadset by remember { mutableStateOf(false) }

    /**
     * What the last *Transcribe again* did, which the screen used to throw away (`B-10`).
     *
     * `RetranscribeResult` exists so this surface can tell *Replaced* from *TranscriptOnly*, and
     * `state_retranscribed` / `state_retranscribed_kept` were written for it and had no caller —
     * so a person who had edited a note's text saw the re-transcription apparently do nothing.
     *
     * **It lives in the view model since `B-190`**, not in a `remember`: the decode that produces
     * it runs on `Graph.scope` now, and an answer held by a composition is an answer the
     * composition can take with it — which is exactly what happened when opening a note killed
     * the work AND the result at the same time.
     */
    val retranscribed = state.retranscribed

    /**
     * True when this device has no page on which a refusal can be undone. Saying so is the only
     * honest thing left: the alternative is a button that does nothing, which is the shape `D-02`
     * is about.
     */
    var appSettingsUnavailable by remember { mutableStateOf(false) }

    /**
     * Shown for [CONFIRMATION_MS] when a download finishes (`T-031`).
     *
     * `DownloadProgress.Done` became `VoiceState.Idle` and the progress bar simply vanished, so a
     * person's only evidence that four minutes of waiting had worked was an absence (`D-05`).
     */
    var modelReady by remember { mutableStateOf(false) }
    val modelPresent by voiceViewModel.modelPresent.collectAsStateWithLifecycle()
    val chosenModel by voiceViewModel.chosen.collectAsStateWithLifecycle()
    val provider by voiceViewModel.provider.collectAsStateWithLifecycle()

    // **Both facts are owned elsewhere.** Settings can remove the model, download one, or choose
    // a different one, and this view model is scoped to a start destination that survives that
    // trip — so a cached flag would tell a person to download a model they already have, or
    // offer the short empty state for one that has just been deleted.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { voiceViewModel.refresh() }

    // The system dialog belongs to the host activity: `ImmersiveActivity` is not a
    // `ComponentActivity`, so the requester is hoisted and the view model only says *when*.
    // This is what makes the first press produce the OS dialog instead of a button that has
    // relabelled itself in the place the person just pressed (`D-05`, tap 1→2).
    LaunchedEffect(voiceViewModel) {
        voiceViewModel.permissionAsks.collect {
            permissionRequester.requestRecordAudio(
                onResult = { granted, permanent ->
                    voiceViewModel.onPermissionResult(granted, permanent)
                },
                // `REQ-048`: a dialog dismissed unanswered only lifts the guard. Reading it as
                // a refusal — which is what an empty `grantResults` used to become — is how two
                // presses produced "permanently denied" with nobody having said no.
                onDismissed = voiceViewModel::onPermissionDismissed,
            )
        }
    }
    LaunchedEffect(voiceViewModel) {
        voiceViewModel.modelArrivals.collect { modelReady = true }
    }
    LaunchedEffect(modelReady) {
        if (modelReady) {
            delay(CONFIRMATION_MS)
            modelReady = false
        }
    }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(CONFIRMATION_MS)
            justCopied = false
        }
    }
    LaunchedEffect(retranscribed) {
        if (retranscribed != null) {
            delay(CONFIRMATION_MS)
            notesViewModel.retranscribedShown()
        }
    }

    // A finished transcript commits itself. There is no Save step and no preview to dismiss: the
    // note appears in the list below and its text is already on the clipboard.
    //
    // **Nothing about that dictation is done from this composition any more** (`REQ-046` took the
    // commit; `B-212` took the clipboard). What was left here was
    // `LaunchedEffect(dictation) { if (copyTranscript) clipboard.setText(…) }`, keyed on a
    // `derivedStateOf` over `VoiceState` — so `DEC-0012`'s promise was kept only if a
    // recomposition frame happened to observe `VoiceState.Ready` between the outbox offer and the
    // drain that empties it. Neither end of that window is a frame, and which way it resolved
    // depended on the order two view models subscribed to a `StateFlow`. The copy happens in
    // `NotesViewModel.commitDictation`, where the row is known to have been written; this screen
    // is told what happened.
    //
    // `replay = 0`, so a commit that finished while nobody was looking is not announced late: a
    // confirmation is true of an instant. The words are still on the clipboard either way.
    LaunchedEffect(notesViewModel) {
        notesViewModel.dictationCommitted.collect { committed ->
            fellBackToHeadset = committed.source == SttSource.LOCAL_FALLBACK
            justCopiedReally = committed.copied
            justCopied = true
        }
    }

    // The only two things this function needs from a fifty-hertz source, both coarse.
    val recording by remember(voiceState) { derivedStateOf { voiceState.value.isRecording } }

    /**
     * `REQ-047` / `M21`. *"Starting the space…"* was set on the tap and cleared only by a
     * failure, so every successful trip out and back left it on screen for the life of the
     * process. The return **is** the resume.
     */
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notesViewModel.panelShown() }

    /**
     * `REQ-046`. **Back must not pop a screen that is recording.**
     *
     * Leaving by any other route already transcribes — the header is disabled, the
     * `DisposableEffect` and `ON_STOP` below both call `stopForNavigation` — but the system Back
     * is not a route this screen controls, and in the Space it is delivered by
     * `ImmersiveActivity.dispatchKeyEvent` straight into `returnToPanel()`, which ends the
     * activity. Consuming it while the microphone is live turns an accidental press into
     * nothing, which is what a person expects of a gesture they did not mean to make.
     *
     * It does **not** stop the recording: a Back that silently ended a dictation would be the
     * same surprise wearing a different hat. The person presses *Stop recording*, which is the
     * control they are already looking at.
     */
    BackHandler(enabled = recording) { /* a recording screen does not pop */ }

    // `REQ-060` / `M20`. The undo line must not outlive the screen it belongs to: it names a
    // note that can still be put back, and offering that on a screen the person has come back to
    // an hour later is offering a control whose recording the trash may already have swept.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { notesViewModel.clearJustDeleted() }
    DisposableEffect(Unit) {
        onDispose { notesViewModel.clearJustDeleted() }
    }

    // Two ways a recording outlives the screen that owns it, and both leave the microphone live
    // with nothing on screen saying so (`E-03`).
    //
    // `stopForNavigation` TRANSCRIBES — the view model is scoped to the back-stack entry and
    // outlives this composition, so the transcript is waiting when the person comes back.
    DisposableEffect(recording) {
        onDispose { if (recording) voiceViewModel.stopForNavigation() }
    }
    // The headset coming off, or another app taking the foreground. **In the Space this only
    // works because of `T-012`/`DEC-0028`**: before it, the panel's composition shadowed the
    // SDK's lifecycle owner with a hand-rolled one that never received `ON_STOP` at all (`I-12`),
    // so whoever reverts that decision finds out here.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (recording) voiceViewModel.stopForNavigation()
    }

    TodayFrame(
        state = state,
        voice = voice,
        transcriptionPercent = transcriptionPercent,
        recordingLost = recordingLost,
        justCopied = justCopied,
        fellBackToHeadset = fellBackToHeadset,
        retranscribed = retranscribed,
        modelReady = modelReady,
        modelPresent = modelPresent,
        modelMegabytes = (chosenModel.bytes / 1_000_000).toInt(),
        provider = provider,
        copiedToClipboard = justCopiedReally,
        appSettingsUnavailable = appSettingsUnavailable,
        playingId = playback.playingId,
        hasRecording = voiceViewModel.hasRecording(),
        canEnterSpace = onEnterSpace != null,
        canLeaveSpace = onLeaveSpace != null,
        actions = TodayActions(
            onOpenNote = onOpenNote,
            onNewNote = { notesViewModel.newNote(onOpenNote) },
            onSearch = onSearch,
            onSettings = onSettings,
            onEnterSpace = {
                onEnterSpace?.let { enter ->
                    notesViewModel.spaceStarting()
                    enter { failure -> notesViewModel.spaceFailed(failure) }
                }
            },
            onLeaveSpace = { onLeaveSpace?.invoke() },
            onSelectTag = notesViewModel::selectTag,
            onShowAll = notesViewModel::showAll,
            // **Exhaustive, and it was not.** The `else -> retry()` here swallowed
            // `DOWNLOAD_MODEL`, which this banner really can carry: a *Transcribe again* that
            // fails with no model on disk puts `ModelMissing` into `NotesUiState.message`, so the
            // person got a *Download* button that dismissed itself and reloaded the list. The
            // voice banner six hundred lines below routed the same enum correctly — two
            // implementations of one rule, one of them wrong. Found by the seam tier of step 8's
            // verification; the compiler owns it now.
            onBannerAction = { action ->
                when (action) {
                    // Two different places, and conflating them is `D-02`: our Settings screen
                    // cannot grant a permission the system has locked, so a person who had
                    // refused the microphone twice was sent somewhere that could not help them.
                    UiAction.OPEN_APP_SETTINGS -> if (!permissionRequester.openAppSettings()) {
                        appSettingsUnavailable = true
                    }
                    UiAction.OPEN_SETTINGS -> onSettings()
                    UiAction.WRITE_NOTE -> notesViewModel.newNote(onOpenNote)
                    // Settings' export is the only producer (`B-258`); this banner never carries it.
                    UiAction.EXPORT_NOTES_ONLY -> notesViewModel.dismissMessage()
                    UiAction.DOWNLOAD_MODEL, UiAction.RESUME_DOWNLOAD, UiAction.DOWNLOAD_AGAIN ->
                        voiceViewModel.downloadModel()
                    UiAction.GRANT_PERMISSION -> voiceViewModel.start()
                    // **Three retries where there was one** (`REQ-047`, `H2`/`H3`). Every
                    // failure this banner can carry used to reach `notesViewModel.retry()`, so
                    // *Retry* under an unsaved dictation reloaded the list and *Retry* under
                    // "The space could not start" reloaded the list. The compiler owns the
                    // distinction now: a new member cannot be added without answering here.
                    UiAction.RETRY_LOAD -> notesViewModel.retry()
                    UiAction.RETRY_DICTATION -> notesViewModel.retryDictation()
                    // Not `enter(…)` directly: `onEnterSpace` also raises "Starting the
                    // space…", and a retry that starts the Space silently is the same
                    // control-that-did-nothing this member exists to fix.
                    UiAction.RETRY_SPACE -> onEnterSpace?.let { enter ->
                        notesViewModel.spaceStarting()
                        enter { failure -> notesViewModel.spaceFailed(failure) }
                    }
                }
            },
            onDismissBanner = notesViewModel::dismissMessage,
            onUndoDelete = notesViewModel::undoDelete,
            onStartRecording = { voiceViewModel.start() },
            onStopRecording = { voiceViewModel.stopAndTranscribe(true) },
            onGrantPermission = {
                permissionRequester.requestRecordAudio(
                    onResult = { granted, permanent ->
                        voiceViewModel.onPermissionResult(granted, permanent)
                    },
                    onDismissed = voiceViewModel::onPermissionDismissed,
                )
            },
            onDownloadModel = { voiceViewModel.downloadModel() },
            onCancelDownload = { voiceViewModel.cancelDownload() },
            onOpenAppSettings = {
                if (!permissionRequester.openAppSettings()) appSettingsUnavailable = true
            },
            onRetryVoice = { voiceViewModel.retry() },
            onDismissVoiceMessage = { voiceViewModel.dismissFailure() },
            onDiscardRecording = { voiceViewModel.cancel() },
            onStopTranscription = { voiceViewModel.stopTranscription() },
            // `B-091`/`SCN-004`. Two view models and one file: the voice side gives up ownership
            // and the notes side takes it, in that order, so there is no window in which both
            // believe they may delete it.
            onKeepRecording = {
                voiceViewModel.releaseRecordingForNote()?.let { notesViewModel.keepRecordingOnly(it) }
            },
            onDismissRecordingLost = voiceViewModel::recordingLostShown,
            onCopyNote = { note -> clipboard.setText(AnnotatedString(noteText(note))) },
            onDeleteNote = notesViewModel::delete,
            onPlayRecording = { note -> note.audioPath?.let { playback.toggle(note.id, it) } },
            onDeleteRecording = { note ->
                playback.stop()
                notesViewModel.deleteRecording(note)
            },
            onRetranscribe = { note, choice ->
                // `B-190`. This was `scope.launch { … }` on `rememberCoroutineScope`, so opening
                // any note cancelled a decode already minutes in — and the `finally` cleared the
                // busy flag with nothing on screen to say why.
                notesViewModel.startRetranscribe(note, choice.provider, choice.model)
            },
        ),
    )
}

/**
 * The identity of the dictation waiting to be committed, or null when there is none.
 *
 * It exists so `LaunchedEffect` can key on the *dictation* rather than on the whole `VoiceState`,
 * and it is a top-level function so that property can be asserted without a device: every
 * `Recording` frame must produce the same key, and two emissions of one finished dictation must
 * produce the same key, or the effect re-enters and the person's words are handled twice.
 *
 * The audio path is the identity where there is one — it is unique per recording — and the
 * transcript's text is the fallback for a dictation whose recording was not kept.
 */
internal fun dictationKey(voice: VoiceState): String? = (voice as? VoiceState.Ready)?.let { ready ->
    ready.audioPath ?: ("text:" + ready.transcript.text)
}

/**
 * The smallest panel this screen can serve, and the numbers the manifest declares.
 *
 * `PanelSizeTest` asserts the two agree. They drifted the moment the frame acquired a 130 dp
 * status slot: the manifest still promised 360 dp, at which the fixed chrome alone overflows and
 * the notes cannot be reached at all. A declaration is a promise to the shell, which will let a
 * person resize to it.
 *
 * **Raised from 560 by `REQ-060`, and this is what it bought.** The notice slot below the record
 * button is the chrome that makes *Undo* reachable after deleting the thirtieth note; it is
 * bounded at [NOTICE_SLOT_MAX] and it has to be paid for in the budget, or `B-11` returns the
 * first time a banner and an undo line are up together at the minimum.
 *
 * Derived: 48 padding + 64 header + 130 status slot + 128 record button + 160 notice slot +
 * four 16 dp gaps + 128 for the shortest note row = 722, rounded up to 736. `PanelSizeTest`
 * computes the same sum from the tokens rather than trusting this sentence.
 */
object PanelMinimum {
    const val WIDTH_DP = 480
    const val HEIGHT_DP = 736
}

/**
 * How tall the pinned notice slot may grow before it starts scrolling inside itself.
 *
 * A **bound**, not a reservation — unlike `Tokens.Space.statusSlot`, which is fixed because the
 * record button must not move and the slot is above it. This one is below the button, so its
 * height costs the list rather than the target: it is zero when there is nothing to say, and at
 * its worst it takes 160 dp that [PanelMinimum] has already accounted for. Unbounded, three
 * notices at the declared minimum would leave the list nothing to measure with, which is `B-11`.
 *
 * 160 dp holds the tallest realistic pair — an `ErrorBanner` (~80) and the Undo row (a 72 dp
 * button) with a gap — and anything beyond that scrolls rather than pushing the notes away.
 * Raising it means raising [PanelMinimum.HEIGHT_DP] and the manifest with it; `PanelSizeTest`
 * fails if you do not.
 */
internal val NOTICE_SLOT_MAX: Dp = 160.dp

/** Below this the header drops its labels and renders glyphs only (`B-25`, `B-11`). */
private val HEADER_LABELS_MIN_WIDTH: Dp = 720.dp

/** Every intent this screen can express, so the frame can be composed without a view model. */
internal data class TodayActions(
    val onOpenNote: (String) -> Unit = {},
    val onNewNote: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onEnterSpace: () -> Unit = {},
    val onLeaveSpace: () -> Unit = {},
    val onSelectTag: (String?) -> Unit = {},
    val onShowAll: () -> Unit = {},
    val onBannerAction: (UiAction) -> Unit = {},
    val onDismissBanner: () -> Unit = {},
    val onUndoDelete: () -> Unit = {},
    val onStartRecording: () -> Unit = {},
    val onStopRecording: () -> Unit = {},
    val onGrantPermission: () -> Unit = {},
    val onDownloadModel: () -> Unit = {},
    val onCancelDownload: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onRetryVoice: () -> Unit = {},
    val onDismissVoiceMessage: () -> Unit = {},
    val onDiscardRecording: () -> Unit = {},
    /** `B-179`. Ends the decode and keeps the recording; see `VoiceViewModel.stopTranscription`. */
    val onStopTranscription: () -> Unit = {},
    /** `B-091`/`SCN-004`. The audio becomes a note of its own when the engine could not read it. */
    val onKeepRecording: () -> Unit = {},
    /** `B-092`. Puts away the notice that a recording could not be written. */
    val onDismissRecordingLost: () -> Unit = {},
    val onCopyNote: (Note) -> Unit = {},
    val onDeleteNote: (Note) -> Unit = {},
    val onPlayRecording: (Note) -> Unit = {},
    val onDeleteRecording: (Note) -> Unit = {},
    val onRetranscribe: (Note, SpeechChoice) -> Unit = { _, _ -> },
)

/**
 * The frame, with no view model in it.
 *
 * Stateless on purpose: the whole of `T-030` is a claim about geometry — that the record button
 * does not move and that a note is reachable at the declared minimum — and a claim about geometry
 * is only worth what a test of it costs. With the view models hoisted into [TodayScreen] this
 * composes on the JVM under Robolectric at any forced size, so `TodayFrameTest` measures those
 * two claims instead of a person checking them in a headset (`DEC-0043`).
 */
@Composable
internal fun TodayFrame(
    state: NotesUiState,
    /**
     * The capture state, **as a function rather than a value** (`B-096`).
     *
     * `VoiceState.Recording` carries `level` and `samples`, and the recorder emits about fifty
     * times a second. A parameter is read by whoever receives it, so a `VoiceState` here made
     * every audio frame invalidate this whole composable — the header, the notice slot and the
     * entire `LazyColumn` of notes — against a 13.9 ms budget on a headset. Deferring the read to
     * the composables that genuinely need it is the Compose remedy for exactly this, and it is
     * why the type is `() -> VoiceState`: nothing in this function's own body may call it outside
     * a `derivedStateOf`, or the deferral is undone by one line.
     */
    voice: () -> VoiceState,
    /**
     * How far the running transcription has got, 0..100 (`B-178`). Zero draws no bar — it is also
     * the honest answer for a cloud or whisper-server decode, which reports no progress at all.
     */
    transcriptionPercent: Int = 0,
    /** `B-092`. The dictation's WAV could not be written; the words survived and this says so. */
    recordingLost: UiMessage? = null,
    justCopied: Boolean = false,
    fellBackToHeadset: Boolean = false,
    retranscribed: RetranscribeResult? = null,
    /** The speech model has just finished downloading, and the person is owed that sentence. */
    modelReady: Boolean = false,
    /**
     * Whether the speech model is on the headset. It chooses between the two empty states — the
     * long one is *about* the download, so the thing it keys on is whether one is still owed.
     */
    modelPresent: Boolean = false,
    /** What the first-run sentence will cost, in MB. Five models, five different numbers. */
    modelMegabytes: Int = WhisperModel.DEFAULT.bytes.let { (it / 1_000_000).toInt() },
    /** Where a recording made now would be transcribed. Named on screen unless it is LOCAL. */
    provider: SttProvider = SttProvider.DEFAULT,
    /** Whether the confirmation may claim a clipboard copy, because one happened. */
    copiedToClipboard: Boolean = true,
    appSettingsUnavailable: Boolean = false,
    playingId: String? = null,
    hasRecording: Boolean = false,
    canEnterSpace: Boolean = false,
    canLeaveSpace: Boolean = false,
    actions: TodayActions = TodayActions(),
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Palette.ink),
    ) {
        val labelled = maxWidth >= HEADER_LABELS_MIN_WIDTH
        // **The one coarse fact this function needs from a fifty-hertz source** (`B-096`).
        // `derivedStateOf` recomputes when the state changes and notifies only when the BOOLEAN
        // does, so a level emission costs one comparison here instead of a whole frame.
        val recording by remember(voice) { derivedStateOf { voice().isRecording } }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Tokens.Space.l),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            TodayHeader(
                labelled = labelled,
                recording = recording,
                canEnterSpace = canEnterSpace,
                canLeaveSpace = canLeaveSpace,
                actions = actions,
            )

            // **A reservation, not a measurement.** Fixed at the tallest state's height so the
            // button below never moves (`B-13`), and scrollable inside so a state that outgrows
            // the reservation is still readable rather than clipped.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tokens.Space.statusSlot)
                    .verticalScroll(rememberScrollState()),
            ) {
                RecordStatus(
                    voice = voice,
                    transcriptionPercent = transcriptionPercent,
                    hasRecording = hasRecording,
                    actions = actions,
                )
            }

            RecordButton(voice = voice, actions = actions)

            // **`REQ-060` / `M20`: pinned, not the list's first items.**
            //
            // Undo, the failure banner and the 2.5-second confirmations were items 1..n of the
            // `LazyColumn`. Delete the thirtieth note and "Deleted … Undo" was thirty rows of
            // scroll above where the person was looking; the confirmations expired unseen for
            // the same reason. A message nobody can see is the same defect as no message, and an
            // *Undo* that has scrolled away is worse, because the note is gone and the control
            // that would bring it back exists somewhere off-screen.
            //
            // **Below the button, so the button still cannot move** (`B-13`): everything above
            // it is fixed and this is not. Bounded at [NOTICE_SLOT_MAX] and scrollable inside,
            // for `B-11`'s reason — an unbounded pinned block at the panel's declared minimum
            // would take the whole list's allocation and the notes would be unreachable again.
            // The bound is part of the frame's height budget and `PanelSizeTest` computes it.
            //
            // Emitted only when there is something in it, so an empty slot costs neither a gap
            // nor a pixel of the list.
            if (hasNotices(state, justCopied, modelReady, retranscribed, recordingLost)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = NOTICE_SLOT_MAX)
                        .verticalScroll(rememberScrollState())
                        .testTag(NOTICE_SLOT_TAG),
                ) {
                    NoticeSlot(
                        state = state,
                        recordingLost = recordingLost,
                        justCopied = justCopied,
                        fellBackToHeadset = fellBackToHeadset,
                        copiedToClipboard = copiedToClipboard,
                        modelReady = modelReady,
                        retranscribed = retranscribed,
                        actions = actions,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag(NOTES_LIST_TAG),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            ) {
                // **`G-02`, and it is directly under the button on purpose.** The three provider
                // notes live in Settings, beside the CHOICE; the screen that actually sends the
                // audio said nothing, so somebody who picked a cloud service months ago pressed
                // Record with no reminder that their voice was leaving. `T-025`'s spec asked for
                // it *below* the button because `B-13` was that everything above it moved it;
                // `DEC-0043` fixed that, and `DEC-0046` then made "three fixed things" a rule —
                // so this is the list's first item rather than a fourth fixed child. It is on
                // screen without scrolling and costs the list nothing.
                //
                // **Nothing at all when it is LOCAL.** An app that announces "nothing is
                // leaving" every time teaches people to stop reading the line.
                if (provider != SttProvider.LOCAL) {
                    item(key = "provider") {
                        Text(
                            stringResource(R.string.state_recording_leaves, stringResource(provider.labelRes)),
                            color = Tokens.Palette.warn,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                // **It was a fourth fixed child**, which made the frame's own rule — three fixed
                // things and one list — false by one paragraph, and a long one: this sentence
                // wraps to three lines on a narrow panel and took that much off the list's
                // allocation. Found by the product tier of step 8's verification.
                if (appSettingsUnavailable) {
                    item(key = "app-settings-unavailable") {
                        Text(
                            stringResource(R.string.state_app_settings_unavailable),
                            color = Tokens.Palette.warn,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                // **The banner, the confirmations and Undo were six items here** (`REQ-060`,
                // `M20`). They are in the pinned slot above this list now — see [NoticeSlot].
                // What stayed is everything that belongs *with* the notes: the provider line,
                // the day card, the chips, the rows themselves.

                // **Not under a filter.** The card shows today unfiltered, which is not what the
                // person asked for — and the view model stops excluding today's note from the
                // list at the same moment, so a note that matches is never simply missing.
                state.today?.takeIf { state.selectedTag == null }?.let { today ->
                    item(key = "day-card") {
                        DayCard(
                            dayLabel = state.dayLabel,
                            note = today,
                            onClick = { actions.onOpenNote(today.id) },
                        )
                    }
                }

                // **The tag feature was complete except that nobody could reach it.** `TagParser`
                // parses `#idea`, the repository stores and filters by it, `NotesViewModel` puts
                // the list and the selection in state and exposes `selectTag` — and no composable
                // read any of it (`D-01`). `NotesRepositoryTest` covered the whole path and passed.
                //
                // **The selected tag is rendered even when it is no longer in the list.** Editing
                // the last `#idea` out of the last note re-emits `tags` without it while
                // `selectedTag` still holds it — so without this the row would show a filter with
                // no chip to release, an empty list, and no way out.
                val chips = (state.tags + listOfNotNull(state.selectedTag)).distinct()
                if (chips.isNotEmpty()) {
                    item(key = "tags") {
                        // `FlowRow`, not `LazyRow`: tags wrap onto a second line rather than
                        // scrolling off one. A horizontal scroller inside a panel aimed at with a
                        // controller ray is a control that moves while you aim at it. Unbounded
                        // is safe here because the row is an item in the list rather than chrome
                        // above the button — growth costs scrolling, not reachability.
                        FabricWrapRow {
                            FabricChip(
                                selected = state.selectedTag == null,
                                onClick = { actions.onSelectTag(null) },
                                label = stringResource(R.string.label_all_tags),
                            )
                            chips.forEach { tag ->
                                FabricChip(
                                    selected = state.selectedTag == tag,
                                    onClick = {
                                        actions.onSelectTag(if (state.selectedTag == tag) null else tag)
                                    },
                                    label = stringResource(R.string.tag_hash, tag),
                                )
                            }
                        }
                    }
                }

                when {
                    state.loading -> item(key = "loading") { CircularProgressIndicator() }
                    // A filter that matches nothing says WHICH filter. `today_empty` here would be
                    // additionally wrong — it talks about having no notes at all. That string is
                    // stale in its own right and `T-032` owns it; this only stops it appearing
                    // where it lies twice.
                    state.notes.isEmpty() && state.selectedTag != null -> item(key = "empty-tag") {
                        Text(
                            stringResource(R.string.today_no_tagged_notes, state.selectedTag.orEmpty()),
                            color = Tokens.Palette.textMuted,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    // **Two empty states, chosen by whether the model is still owed** (`T-031`).
                    // The long one is the first run's only piece of onboarding and it is three
                    // facts in the order a person needs them: what to press, what it costs, and
                    // that there is a way round it. The third clause is honest only because
                    // `T-028` put *New note* back — before it, it was the same lie the old
                    // `today_empty` told about a hold gesture `DEC-0010` had deleted.
                    //
                    // Not a "has run before" flag: the paragraph is **about** the download, and a
                    // flag would go stale the first time somebody removed a model in Settings.
                    //
                    // **And it is chosen by the PROVIDER first** (`REQ-061`, audit `M27`). The
                    // long sentence ends "after that everything happens on this headset", which
                    // is false under `CLOUD` and `SERVER` — where the recording is uploaded
                    // rather than decoded here, and where the provider line a few items above
                    // says exactly that. The screen contradicted itself, in the person's
                    // favour in one line and against them in the other. The remote variant is
                    // deliberately silent about the destination: `state_recording_leaves`
                    // already names it, and a screen that says it twice trains people to read
                    // neither.
                    state.notes.isEmpty() -> item(key = "empty") {
                        Text(
                            when {
                                provider != SttProvider.LOCAL ->
                                    stringResource(R.string.today_empty_first_run_remote)
                                modelPresent -> stringResource(R.string.today_empty)
                                else -> stringResource(R.string.today_empty_first_run, modelMegabytes)
                            },
                            color = Tokens.Palette.textMuted,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    else -> items(state.notes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            busy = state.retranscribing == note.id,
                            playing = playingId == note.id,
                            onClick = { actions.onOpenNote(note.id) },
                            onCopy = { actions.onCopyNote(note) },
                            onDelete = { actions.onDeleteNote(note) },
                            onPlay = { actions.onPlayRecording(note) },
                            onDeleteRecording = { actions.onDeleteRecording(note) },
                            onRetranscribe = { choice -> actions.onRetranscribe(note, choice) },
                        )
                    }
                }

                // `T-026`. The window is 200 and the list says so rather than pretending to be
                // everything: showing 200 of 400 with nothing on screen to indicate it is the
                // same failure as a search that finds fewer things than it counted.
                if (state.total > state.notes.size) {
                    item(key = "more") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.today_more_notes, state.total - state.notes.size),
                                color = Tokens.Palette.textMuted,
                                modifier = Modifier.weight(1f),
                            )
                            FabricOutlinedButton(onClick = actions.onShowAll) {
                                Text(stringResource(R.string.action_show_all))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whether the pinned slot has anything to say, so an empty one costs the list nothing.
 *
 * Top-level rather than a private `val` inside the frame because [PanelSizeTest] and
 * [TodayFrameTest] both reason about the slot's contribution to the height budget, and a
 * predicate a test can read is one that cannot quietly drift from the thing it gates.
 */
internal fun hasNotices(
    state: NotesUiState,
    justCopied: Boolean,
    modelReady: Boolean,
    retranscribed: RetranscribeResult?,
    /** `B-092`. A recording that could not be written is the sixth thing the slot can carry. */
    recordingLost: UiMessage? = null,
): Boolean = state.message != null ||
    state.justDeleted != null ||
    state.spaceStarting ||
    justCopied ||
    modelReady ||
    recordingLost != null ||
    (retranscribed == RetranscribeResult.Replaced || retranscribed == RetranscribeResult.TranscriptOnly)

/**
 * Everything the screen has to say about what just happened, pinned above the list (`REQ-060`).
 *
 * The order is by how much it costs to miss: the failure first, then the news, then *Undo* —
 * which is last because it is the only one with a control the person may be reaching for, and a
 * control that moves as the lines above it appear is a control aimed at and missed.
 */
@Composable
private fun NoticeSlot(
    state: NotesUiState,
    recordingLost: UiMessage?,
    justCopied: Boolean,
    fellBackToHeadset: Boolean,
    copiedToClipboard: Boolean,
    modelReady: Boolean,
    retranscribed: RetranscribeResult?,
    actions: TodayActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        state.message?.let { message ->
            ErrorBanner(
                message = message,
                onAction = actions.onBannerAction,
                onDismiss = actions.onDismissBanner,
            )
        }

        // **`B-092`, and it is above the confirmations on purpose.** The dictation succeeded —
        // "Saved, and copied" is about to appear a line below — and what the person has to carry
        // away is that this one row will never re-transcribe. A degradation printed under a
        // success is a degradation nobody reads. It carries no action because there is none: the
        // recording does not exist, and the only repair is to say it again.
        recordingLost?.let { message ->
            ErrorBanner(
                message = message,
                onAction = { /* the message carries no action — see above */ },
                onDismiss = actions.onDismissRecordingLost,
            )
        }

        if (justCopied) {
            val saved = if (copiedToClipboard) {
                stringResource(R.string.state_saved_and_copied)
            } else {
                stringResource(R.string.state_saved)
            }
            Text(
                if (fellBackToHeadset) {
                    saved + " · " + stringResource(R.string.stt_source_local_fallback)
                } else {
                    saved
                },
                color = if (fellBackToHeadset) Tokens.Palette.warn else Tokens.Palette.accent,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // `B-19`. `spaceStarting` has been in the state and `state_space_starting` in
        // `strings.xml` since the Space existed, and nothing rendered either — so pressing
        // *Space* looked like a control that did nothing for the two to four seconds the
        // immersive activity takes to come up, which is exactly long enough to press it again.
        // It clears on failure (`spaceFailed`) and on the panel's next resume (`panelShown`,
        // `REQ-047`/`M21`), which is what the return from a successful trip is.
        if (state.spaceStarting) {
            Text(
                stringResource(R.string.state_space_starting),
                color = Tokens.Palette.textMuted,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // `T-031`. Four minutes of waiting must not end in an absence: `Done` became `Idle`, the
        // bar vanished, and nothing said the bytes had arrived or been verified.
        if (modelReady) {
            Text(
                stringResource(R.string.state_model_ready),
                color = Tokens.Palette.accent,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // `B-10`. The two failing outcomes already reach the banner through the view model; only
        // the two successes had nowhere to go, and *TranscriptOnly* is the one that matters — it
        // is the case where the note's text deliberately did not change, which without a word
        // looks exactly like nothing having happened.
        retranscribed?.let { result ->
            val said = when (result) {
                RetranscribeResult.Replaced -> R.string.state_retranscribed
                RetranscribeResult.TranscriptOnly -> R.string.state_retranscribed_kept
                RetranscribeResult.NoRecording, RetranscribeResult.Failed -> null
            }
            said?.let { textRes ->
                Text(
                    stringResource(textRes),
                    color = Tokens.Palette.accent,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        state.justDeleted?.let { deleted ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.state_deleted,
                        deleted.title.ifBlank { stringResource(R.string.label_untitled) },
                    ),
                    color = Tokens.Palette.textMuted,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = actions.onUndoDelete,
                    modifier = Modifier.height(Tokens.Space.controlHeight),
                ) { Text(stringResource(R.string.action_undo)) }
            }
        }
    }
}

/**
 * The header, labelled or not.
 *
 * `B-25`: five unlabelled glyph actions sat here, two of them the same `ViewInAr` pointing in
 * opposite directions. A glyph a person has to learn is a glyph they mis-tap from a metre away,
 * so every action carries its word wherever there is width for it — and where there is not, the
 * glyph keeps the `contentDescription` that the labelled form would have shown.
 */
@Composable
private fun TodayHeader(
    labelled: Boolean,
    recording: Boolean,
    canEnterSpace: Boolean,
    canLeaveSpace: Boolean,
    actions: TodayActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Every header action is unavailable while the microphone is live. Until `T-020` they
        // were all live: tapping one changed the screen, left this view model on the back stack,
        // and the recorder kept reading with nothing anywhere saying so.
        //
        // **The missing capability.** `createNote()`, `action_new_note` and `Routes.editor` all
        // existed; no screen called any of them, so on a fresh install the only thing a person
        // could do was dictate. The control was collateral of `DEC-0010`'s deliberate
        // simplification — which was right — and nothing recorded removing it (`A-01`).
        HeaderAction(
            label = stringResource(R.string.action_new_note),
            icon = Icons.Filled.Add,
            labelled = labelled,
            enabled = !recording,
            onClick = actions.onNewNote,
        )
        HeaderAction(
            label = stringResource(R.string.action_search),
            icon = Icons.Filled.Search,
            labelled = labelled,
            enabled = !recording,
            onClick = actions.onSearch,
        )
        if (canEnterSpace) {
            HeaderAction(
                label = stringResource(R.string.action_space),
                icon = Icons.Filled.ViewInAr,
                labelled = labelled,
                enabled = !recording,
                onClick = actions.onEnterSpace,
            )
        }
        if (canLeaveSpace) {
            // Not `ViewInAr`, and not "Back". Both were wrong in the same way: this control is
            // the *opposite* of the one above it and wore the same glyph, and the word "Back"
            // already means "pop one screen" everywhere else in this app (`B-25`).
            //
            // **Live during a recording, unlike every other header action**, and the exception is
            // deliberate. `T-020` disabled the header so a dictation could not outlive the screen
            // showing it — but this control is the *only* way out of an immersive surface, and
            // `T-013` could not establish that a physical controller's Back even arrives
            // (`B-115`). Greying it out leaves a person holding a recording they must stop before
            // they are allowed to leave the room. Leaving is already safe: `ON_STOP` and the
            // `DisposableEffect` both transcribe rather than discard.
            HeaderAction(
                label = stringResource(R.string.action_leave_space),
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                labelled = labelled,
                enabled = true,
                onClick = actions.onLeaveSpace,
            )
        }
        HeaderAction(
            label = stringResource(R.string.label_settings),
            icon = Icons.Filled.Settings,
            labelled = labelled,
            enabled = !recording,
            onClick = actions.onSettings,
        )
    }
}

/**
 * One header action, as a word plus a glyph or as a glyph alone.
 *
 * **Disabled, not hidden** (`E-03`). A control that disappears mid-gesture is worse than one that
 * is visibly unavailable: the person's ray is already moving toward where it was, and a layout
 * that reflows under it sends the tap somewhere they did not choose.
 */
@Composable
private fun HeaderAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (labelled) {
        FabricTextButton(
            onClick = onClick,
            enabled = enabled,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Tokens.Space.m))
            Spacer(Modifier.width(Tokens.Space.xs))
            Text(label, maxLines = 1)
        }
    } else {
        FabricIconButton(
            onClick = onClick,
            icon = icon,
            contentDescription = label,
            enabled = enabled,
        )
    }
}

/** Title and body are the same sentence for a dictation; copying must not paste it twice. */
private fun noteText(note: Note): String {
    val body = note.body.trim()
    val title = note.title.trim()
    return when {
        body.isEmpty() -> title
        title.isEmpty() || body.startsWith(title) -> body
        else -> "$title\n\n$body"
    }
}

/**
 * Whatever the voice flow has to say, in the reserved slot above the button.
 *
 * **The `when` is exhaustive over `VoiceState` and has no `else`.** `B-05` — `Allowed` rendering
 * nothing at all — existed because a catch-all hid a missing branch; every state now either draws
 * something or says in one line why it draws nothing, and the compiler refuses a new state that
 * does neither.
 */
@Composable
private fun RecordStatus(
    voice: () -> VoiceState,
    transcriptionPercent: Int,
    hasRecording: Boolean,
    actions: TodayActions,
) {
    // **The one composable that genuinely needs every frame** (`B-096`). The read is here, at the
    // meter, and nowhere above it.
    val current = voice()
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        // One clock for the whole wait. `Transcribing` and `PreparingEngine` are the same wait
        // from the person's side — the words are not back yet — and `WhisperEngine` moves
        // between them whenever a model has to be loaded or swapped.
        if (current is VoiceState.Transcribing || current is VoiceState.PreparingEngine) {
            // **`B-178`: a number, when there is one.** Zero is both "just started" and "this
            // decode reports nothing at all" — a cloud endpoint and a whisper-server return one
            // answer at the end — so a bar is drawn only once whisper has said something, and the
            // wordless clock below carries the rest. A bar pinned at 0 % for thirteen minutes is
            // worse than no bar: it claims a measurement and then contradicts it.
            if (transcriptionPercent > 0) {
                Text(
                    stringResource(R.string.voice_transcribing_progress, transcriptionPercent),
                    color = Tokens.Palette.textMuted,
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { transcriptionPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                ElapsedSeconds()
            }
            // **`B-179`, and it is NOT the record button.** That control is disabled while a
            // decode runs, so a person cannot be asked to press a dead target to escape a wait
            // that reaches thirteen minutes on a ten-minute dictation. The recording is kept —
            // `VoiceViewModel.stopTranscription` says so in the line it leaves behind.
            FabricOutlinedButton(onClick = actions.onStopTranscription) {
                Text(stringResource(R.string.action_stop_transcribing))
            }
        }
        when (current) {
            // Nothing to say: the button reads *Record* and that is the whole instruction.
            VoiceState.Idle -> Unit

            // **The last silence in the flow** (`D-05`). The button reads *Transcribing…* and is
            // disabled; at the measured 1.31× real time a forty-second thought costs about
            // fifty-two seconds with nothing moving, which is indistinguishable from a hang. A
            // counter is the cheapest honest answer: the same `voice_seconds` the recording
            // already uses, pointed at a different clock.
            //
            // **A real progress fraction and a cancel are deliberately not here.** whisper.cpp
            // reports no progress through this bridge and there is no cancellation path through
            // `transcribe` — both are findings of their own (`B-146`, `B-147`) rather than
            // half-built inside a first-run task.
            //
            // The clock is hoisted above this `when` so it spans `PreparingEngine` too: the
            // counter is per-composable, and a `Transcribing → PreparingEngine` walk restarted
            // it from zero — covering the shortest part of the silence it was written for.
            VoiceState.Transcribing -> Unit

            // Consumed within a frame by the commit effect; the confirmation appears in the list.
            is VoiceState.Ready -> Unit

            is VoiceState.Recording -> {
                LinearProgressIndicator(
                    progress = { current.level.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val left = (VoiceViewModel.MAX_SAMPLES - current.samples) / AudioRecorder.SAMPLE_RATE
                Text(
                    // The elapsed count, until the last minute — then what is left, because a
                    // recording that simply stops at ten minutes with no warning is a trap. The
                    // number is derived from `samples`, which `Recording` already carries, so
                    // there is no new state and nothing to keep in sync.
                    if (left <= VoiceViewModel.COUNTDOWN_SECONDS) {
                        stringResource(R.string.voice_time_left, left.coerceAtLeast(0))
                    } else {
                        stringResource(R.string.voice_seconds, current.samples / AudioRecorder.SAMPLE_RATE)
                    },
                    color = Tokens.Palette.recording,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            // **`REQ-052` / `H11`, and the point is the sentence it does NOT say.** The
            // recording is still running and the OS is feeding it silence, so *Nothing heard*
            // would blame the person for the headset's decision — and it is not even said at
            // the same moment: that one comes after a stop, this one during. The meter and the
            // clock stay, because the recording did not.
            is VoiceState.Silenced -> {
                Text(
                    stringResource(R.string.state_microphone_silenced),
                    color = Tokens.Palette.warn,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.voice_seconds, current.samples / AudioRecorder.SAMPLE_RATE),
                    color = Tokens.Palette.recording,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            is VoiceState.Downloading -> {
                Text(
                    stringResource(
                        R.string.settings_download_progress,
                        current.bytes / 1_000_000,
                        current.total / 1_000_000,
                    ),
                    color = Tokens.Palette.textMuted,
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { if (current.total > 0) current.bytes.toFloat() / current.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                // The estimate sits **beside** Cancel rather than above it, and that is a budget
                // decision, not a style one: another full-width line would push the slot past
                // `Tokens.Space.statusSlot`, and raising that token raises the panel's declared
                // minimum with it (`PanelSizeTest`).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.voice_download_estimate),
                        color = Tokens.Palette.textMuted,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    FabricOutlinedButton(onClick = actions.onCancelDownload) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }

            is VoiceState.Failed -> ErrorBanner(
                message = current.message,
                onAction = { action ->
                    when (action) {
                        UiAction.GRANT_PERMISSION -> actions.onGrantPermission()
                        // *Resume* and *Download again* are two words for one call: the
                        // downloader decides for itself whether the bytes on disk are usable.
                        // What the two members buy is that the button said which it would be
                        // before the person agreed to it (`REQ-047`, `H8`).
                        UiAction.DOWNLOAD_MODEL, UiAction.RESUME_DOWNLOAD, UiAction.DOWNLOAD_AGAIN ->
                            actions.onDownloadModel()
                        UiAction.OPEN_APP_SETTINGS -> actions.onOpenAppSettings()
                        UiAction.OPEN_SETTINGS -> actions.onSettings()
                        UiAction.WRITE_NOTE -> actions.onNewNote()
                        // A voice failure is the voice flow's to retry, whichever of the three
                        // the mapper chose: this banner never carries a dictation commit or a
                        // Space start, both of which belong to `NotesUiState.message`.
                        UiAction.RETRY_LOAD, UiAction.RETRY_DICTATION, UiAction.RETRY_SPACE ->
                            actions.onRetryVoice()
                        // Settings' export is the only producer (`B-258`); a voice banner never is.
                        UiAction.EXPORT_NOTES_ONLY -> actions.onDismissVoiceMessage()
                    }
                },
                onDismiss = actions.onDismissVoiceMessage,
            )

            // Reached only after a **refusal** since `T-031`, which is the one moment an
            // explanation is worth the room: before the dialog the reason is obvious from the
            // button that was just pressed. *Write a note instead* is here because a person who
            // has declined the microphone should not be left on a screen whose only control is
            // the thing they declined.
            VoiceState.NeedsPermission -> {
                Text(
                    stringResource(R.string.state_needs_microphone),
                    color = Tokens.Palette.warn,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    FabricOutlinedButton(onClick = actions.onStartRecording) {
                        Text(stringResource(R.string.action_ask_again))
                    }
                    FabricOutlinedButton(onClick = actions.onNewNote) {
                        Text(stringResource(R.string.action_write_instead))
                    }
                }
            }

            VoiceState.NothingHeard -> Text(
                stringResource(R.string.state_nothing_heard),
                color = Tokens.Palette.warn,
                style = MaterialTheme.typography.titleMedium,
            )

            // Named rather than folded into "Transcribing…": the person is waiting for a model to
            // load or swap, which is a different answer to "why is nothing happening" and can take
            // far longer than the transcription itself.
            is VoiceState.PreparingEngine -> Text(
                stringResource(
                    R.string.state_preparing_engine,
                    stringResource(current.model.shortLabelRes),
                ),
                color = Tokens.Palette.textMuted,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // Discard appears only when there is a recording to discard, and only beside a message
        // about it. A control that discards nothing teaches the person that the app has lost
        // track of its own state.
        if (hasRecording && (current is VoiceState.Failed || current is VoiceState.NothingHeard)) {
            FabricWrapRow {
                // **`B-091`/`SCN-004`: "the audio is kept and offered as a note without a
                // transcript".** `T-005` stopped the file being deleted when the decode failed,
                // and then nothing owned it — so `VoiceViewModel.onCleared` deleted it the moment
                // the person looked away, which is a promise that holds only while you watch it.
                // This is the offer, and taking it is what gives the file an owner.
                //
                // It stands **before** Discard because the two are opposites and this is the
                // recoverable one: a note can be deleted afterwards, and a recording cannot be
                // un-discarded (`DEC-0011` left no confirmation step).
                FabricOutlinedButton(onClick = actions.onKeepRecording) {
                    Text(stringResource(R.string.action_keep_recording))
                }
                FabricOutlinedButton(onClick = actions.onDiscardRecording) {
                    Text(stringResource(R.string.action_discard_recording))
                }
            }
        }
    }
}

/**
 * How long the person has been waiting, counted from the moment this composable appeared.
 *
 * A local clock rather than a field on `VoiceState.Transcribing`: the state is a `data object`
 * and giving it a ticking value would make the view model emit once a second for the whole of a
 * transcription, which is churn for something only the screen needs. The counter dies with the
 * state, because the composable does.
 */
@Composable
private fun ElapsedSeconds() {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds += 1
        }
    }
    Text(
        stringResource(R.string.voice_seconds, seconds),
        color = Tokens.Palette.textMuted,
        style = MaterialTheme.typography.titleMedium,
    )
}

/**
 * The one control the product exists for.
 *
 * Its height is fixed and everything above it is fixed, so its bounds are identical in every
 * state — `TodayFrameTest` measures that rather than trusting this sentence, which is what
 * `B-13` was: the same claim, written in a comment, false since `732c92b`.
 */
@Composable
private fun RecordButton(voice: () -> VoiceState, actions: TodayActions) {
    // The read is here rather than in the frame (`B-096`). This composable is four states wide and
    // a level emission recomposes it; that is one `Button`, not a screen of notes.
    val current = voice()
    // `isRecording`, so a silenced microphone still reads *Stop recording*: the recording is
    // running and the button that ends it must not relabel itself to *Record* underneath a
    // person's ray (`REQ-052`).
    val recording = current.isRecording
    // `PreparingEngine` belongs here and forgetting it would have been the whole defect back: the
    // button would read *Record* and be live while the engine is mid-swap, so a second recording
    // could be started into a context that is being freed.
    val working = current is VoiceState.Transcribing ||
        current is VoiceState.Downloading ||
        current is VoiceState.PreparingEngine
    Button(
        // **One button, one meaning.** It used to relabel itself to *Allow the microphone* when
        // the permission was missing, so a person who pressed *Record* was answered with a
        // different button in the same place and had to press again — `D-05`'s first extra tap.
        // `start()` asks for the permission itself now, and `onPermissionResult` starts the
        // recording, so the press that was wasted is gone.
        onClick = { if (recording) actions.onStopRecording() else actions.onStartRecording() },
        enabled = !working,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (recording) Tokens.Palette.recording else Tokens.Palette.accent,
        ),
        shape = RoundedCornerShape(Tokens.Radius.l),
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.Space.captureButtonHeight)
            .testTag(RECORD_BUTTON_TAG),
    ) {
        Text(
            stringResource(
                when {
                    current is VoiceState.Transcribing -> R.string.state_transcribing
                    current is VoiceState.Downloading -> R.string.state_getting_model
                    current is VoiceState.PreparingEngine -> R.string.state_transcribing
                    recording -> R.string.action_stop_record
                    else -> R.string.action_record
                },
            ),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/**
 * Today's note, named and reachable.
 *
 * `NotesViewModel` has always loaded it and kept `dayLabel` correct across midnight, and nothing
 * rendered either — so the daily note created silently at launch appeared, on the next emission,
 * as an unexplained row bearing today's date. With a card it is explained, and it is the second
 * entry to writing beside *New note*.
 *
 * **An item in the list, not chrome.** `T-028` handed its height to `T-030` to place; the
 * arithmetic there says the fixed chrome is already 418 dp and only the header, the status slot
 * and the button can afford to be in it.
 */
@Composable
private fun DayCard(dayLabel: String, note: Note, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.Space.controlHeight)
            .clickable(onClick = onClick)
            .padding(vertical = Tokens.Space.s),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
    ) {
        Text(dayLabel, style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.label_today_note),
            color = Tokens.Palette.textMuted,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            note.body.lineSequence().firstOrNull { it.isNotBlank() }
                ?: stringResource(R.string.state_nothing_written),
            color = Tokens.Palette.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoteRow(
    note: Note,
    busy: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onDeleteRecording: () -> Unit,
    onRetranscribe: (SpeechChoice) -> Unit,
) {
    var choosingEngine by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    // `B-14`: `copied` was set true on click and set false by nothing, and because the list is
    // keyed by note id the state survived recomposition — so the button read *Copied* for the
    // rest of the screen's life and stopped saying anything at all. The same shape the screen's
    // own `justCopied` already used, applied where it was missing.
    LaunchedEffect(copied) {
        if (copied) {
            delay(CONFIRMATION_MS)
            copied = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.m))
            .background(Tokens.Palette.surface)
            .padding(Tokens.Space.m)
            .testTag(NOTE_ROW_TAG),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        ) {
            // `B-20`: the `clickable` used to wrap this whole column, buttons included, so the
            // gutter between *Delete* and *Transcribe again* opened the editor. It is on the text
            // now — the part of a row that looks like a thing to open.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .testTag(NOTE_OPEN_TAG),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
            ) {
                Text(
                    noteText(note).ifBlank { stringResource(R.string.label_untitled) },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 6,
                )

                // `B-02`: a list of notes with no times in it cannot be scanned. One line, muted,
                // under the text — the clock for today and the date for anything older, in the
                // person's own format rather than one this app invents.
                Text(
                    noteTime(note.updatedAt),
                    color = Tokens.Palette.textMuted,
                    style = MaterialTheme.typography.labelLarge,
                )

                // Which engine produced these words, because the answer decides whether a re-run
                // with a bigger one is worth waiting for.
                //
                // **`B-153`: the glyph, which this line was standing in for and cannot.** `B-02`'s
                // sixth element was a microphone on a dictated row; `T-032` deleted
                // `label_voice_note_icon` as dead, `T-030` rebuilt this row without it, and
                // `SCR-01` listed it until step 8's verification read the line. The engine and the
                // language are strictly more informative and they are not scannable: somebody
                // looking down a list for "the one I spoke" reads twelve lines of
                // `whisper-small-q5_1 · ru` instead of seeing two microphones. Both, then — the
                // glyph answers *whether*, the text answers *how*.
                note.transcript?.let { transcript ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            // Required rather than null: an unlabelled glyph is `B-25`, and this
                            // one carries the whole distinction for anybody using a screen reader.
                            contentDescription = stringResource(R.string.label_voice_note_icon),
                            tint = Tokens.Palette.accent,
                            modifier = Modifier.size(Tokens.Space.m),
                        )
                        Text(
                            listOf(transcript.engine, transcript.language)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            color = Tokens.Palette.accent,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            // **Two groups, `Tokens.Space.l` apart: what is taken often, and what cannot be
            // taken back** (`B-154`).
            //
            // They were one wrapping row at `Tokens.Space.s`, so *Delete* sat **8 dp** from
            // *Transcribe again* and *Delete recording* 8 dp from *Play* — the two controls a
            // person uses most on a dictated note, beside the two that destroy something. `Copy`
            // has had `Tokens.Space.l` of clear air since it was written, with the reason stated
            // at `Tokens.Space.copyButtonWidth`: *"the action taken most often and the one whose
            // neighbours must never be hit instead."* The same argument runs from the other end,
            // and 24 dp is the same answer rather than a new number.
            //
            // **Stacked, not side by side.** A `Row` with the frequent group weighted was the
            // first arrangement and it is the fragile one: what separates the groups is then
            // whatever horizontal room is left after a 148 dp *Copy* and four controls, so the
            // gap is a consequence of the panel's width rather than a decision. Stacked, the
            // separation is the `Column`'s own `spacedBy(Tokens.Space.l)` and is the same at
            // every size — which matters here because `PanelMinimum.WIDTH_DP` is 480 and `B-11`
            // is the last time this product assumed a size. The axis is the better one too: a
            // ray pivots at the wrist, so its drift is mostly horizontal and a horizontal
            // boundary is the one it crosses by accident.
            //
            // `DestructiveAffordanceTest` measures both widths. Read its note on density before
            // trusting a number from that harness — the first version of that assertion took the
            // rule's density instead of the composition's and reported a correct 24 dp layout as
            // 5 px, which is `DEC-0043`'s recorded trap one field over.
            //
            // Colour carries the rest — `FabricDangerButton` is `Tokens.Palette.danger`, which
            // until now no control in the product wore.
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
                // Emitted only when it holds something: an empty row above the gap would spend
                // 24 dp of a note row on nothing, which at the declared minimum is the list's.
                if (note.audioPath != null || busy) {
                    FabricWrapRow {
                    if (note.audioPath != null) {
                        // `T-050`. `DEC-0022` keeps the recording because it is what makes
                        // *Transcribe again* possible — and until now the person could not hear
                        // the thing being kept. One control, two intentions, like the record
                        // button.
                        FabricOutlinedButton(onClick = onPlay) {
                            Text(
                                stringResource(
                                    if (playing) R.string.action_stop_recording else R.string.action_play_recording,
                                ),
                            )
                        }
                        // **An inline expansion, not a `DropdownMenu`** (`B-09`, `DEC-0043`).
                        //
                        // A `DropdownMenu` is a `Popup`: a second window added through
                        // `WindowManager`. In the Space a panel is a `Presentation` on a
                        // `VirtualDisplay` created without `SUPPORTS_TOUCH` or `TRUSTED`, so
                        // whether that window is composited into the panel texture AND receives
                        // injected input is unverified — and the second failure shape, a menu
                        // that appears and cannot be dismissed, swallows the next tap. This is
                        // the whole of `SCN-005`'s recovery path; it must not rest on an unproven
                        // widget. The expansion is no second window, needs no focus, and behaves
                        // identically in both hosts.
                        FabricOutlinedButton(
                            onClick = { choosingEngine = !choosingEngine },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.action_redo_stt)) }
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.height(Tokens.Space.controlHeight))
                    }
                    }
                }
                // The two that destroy something, together and away from the rest. Together
                // deliberately: a miss between *Delete recording* and *Delete* stays inside one
                // family and the row's *Undo* answers it (`SCN-015`), where a miss between
                // *Delete* and *Play* does not look like a mistake at all until the note is gone.
                FabricWrapRow {
                    if (note.audioPath != null) {
                        // `T-024`. The recording goes and the note stays: `Vault.remove` took both
                        // in one call, so wanting the audio gone meant giving up the text with it.
                        FabricDangerButton(onClick = onDeleteRecording, enabled = !busy) {
                            Text(stringResource(R.string.action_delete_recording))
                        }
                    }
                    FabricDangerButton(onClick = onDelete) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
            }

            if (choosingEngine && note.audioPath != null) {
                FabricWrapRow {
                    SpeechChoice.all().forEach { choice ->
                        FabricChip(
                            selected = false,
                            onClick = { choosingEngine = false; onRetranscribe(choice) },
                            label = if (choice.provider == SttProvider.LOCAL) {
                                stringResource(choice.model.labelRes)
                            } else {
                                stringResource(choice.provider.labelRes)
                            },
                        )
                    }
                }
            }
        }

        // Copy stands alone on the right, a gap away from everything else. It is the action taken
        // most often and the one whose neighbour must never be hit instead.
        Button(
            onClick = { onCopy(); copied = true },
            modifier = Modifier
                .width(Tokens.Space.copyButtonWidth)
                .height(Tokens.Space.copyButtonHeight),
        ) {
            Text(
                stringResource(if (copied) R.string.action_copied else R.string.action_copy),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * When a note was last touched, in the person's own format.
 *
 * The clock for anything from the last day, the date for anything older: a list of thoughts is
 * scanned by "this morning" or "Tuesday", and a full timestamp on every row is noise that pushes
 * the text out. `DateFormat.getTimeFormat` reads the device's 12/24-hour setting, so nothing here
 * decides that on the person's behalf.
 */
@Composable
private fun noteTime(updatedAt: Long): String {
    val context = LocalContext.current
    val fresh = System.currentTimeMillis() - updatedAt < DAY_MS
    val format = if (fresh) DateFormat.getTimeFormat(context) else DateFormat.getDateFormat(context)
    return format.format(Date(updatedAt))
}

/** Long enough to read from a metre away, short enough not to become furniture. */
private const val CONFIRMATION_MS = 2_500L

private const val DAY_MS = 24L * 60 * 60 * 1_000

/** The record button, so a test can measure where it is rather than where it looks. */
internal const val RECORD_BUTTON_TAG = "record-button"

/** One note in the list. Agreed with `T-029`'s chip tests, which look for rows by this tag. */
internal const val NOTE_ROW_TAG = "note-row"

/**
 * The part of a row that opens the editor: the text, and not the buttons under it (`B-20`).
 *
 * Named so a test can measure it. The assertion is geometric rather than a click at a chosen
 * coordinate — the defect was that the target *contained* the buttons and the gutter between
 * them, and a bounds comparison says that directly instead of guessing where the gutter is.
 */
internal const val NOTE_OPEN_TAG = "note-open"

/**
 * The scrolling half of the frame.
 *
 * Named because the status slot scrolls too, and a test that asks "the scrollable one" would
 * otherwise have two answers — one of which is 130 dp tall and holds no notes.
 */
internal const val NOTES_LIST_TAG = "notes-list"

/** The pinned notices above the list (`REQ-060`), so a test can measure what they cost. */
internal const val NOTICE_SLOT_TAG = "notice-slot"

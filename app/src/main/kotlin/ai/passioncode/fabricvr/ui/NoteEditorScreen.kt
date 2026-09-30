package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.theme.Tokens
import ai.passioncode.fabricvr.common.ui.ErrorBanner
import ai.passioncode.fabricvr.common.ui.FabricOutlinedButton
import ai.passioncode.fabricvr.common.ui.FabricTextButton
import ai.passioncode.fabricvr.common.ui.FabricWrapRow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun NoteEditorScreen(
    noteId: String,
    permissionRequester: ai.passioncode.fabricvr.PermissionRequester,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    viewModel: EditorViewModel = viewModel(),
    voiceViewModel: VoiceViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val voiceState by voiceViewModel.state.collectAsStateWithLifecycle()
    val transcriptionPercent by voiceViewModel.transcriptionProgress.collectAsStateWithLifecycle()

    // The editor keeps the dictation in the note the person is looking at, and commits it the
    // moment it is ready — there is no preview window to dismiss.
    // **Keyed on the dictation, not on `voiceState`** — the same correction `T-030` made on
    // Today and, until step 8's verification read this file, did not make here.
    // `VoiceState.Recording` carries `level` and `samples`, which change on every audio frame, so
    // this effect was cancelled and relaunched fifty times a second for the length of every
    // recording made from the editor.
    // **This surface holds its own dictations** (`REQ-046`). A dictation made here is appended
    // to the note already open, not turned into a note of its own — and Today's
    // `NotesViewModel` is still alive in the back stack, draining the same outbox. The hold is
    // what keeps the two from racing; dropping it when this screen goes away is what keeps the
    // words when the append can no longer happen.
    DisposableEffect(voiceViewModel) {
        voiceViewModel.reserveDictations(true)
        onDispose { voiceViewModel.reserveDictations(false) }
    }

    LaunchedEffect(dictationKey(voiceState)) {
        val ready = voiceState as? VoiceState.Ready ?: return@LaunchedEffect
        // Claim first: a null claim means another surface has already written a note from these
        // words, and appending them here as well would give the person the same sentence twice.
        val entry = voiceViewModel.claimPending() ?: return@LaunchedEffect
        // The outbox keeps the words on disk until a write carrying them lands (`B-237`).
        viewModel.attachTranscript(ready.transcript, ready.audioPath, onDurable = { voiceViewModel.settle(entry) })
        voiceViewModel.consumed()
    }

    // The same pair as Today's, for the same reason (`E-03`): a recording must not outlive the
    // screen that is showing it, and what it must not do is be thrown away. `stopForNavigation`
    // transcribes. In the Space the `ON_STOP` half depends on `T-012`/`DEC-0028`.
    // The system dialog belongs to the host activity, not to this composition — and the editor
    // has its own *Record into this note*, so without this collector a first dictation started
    // from the editor on a fresh install would ask for nothing and do nothing (`T-031`).
    LaunchedEffect(voiceViewModel) {
        voiceViewModel.permissionAsks.collect {
            permissionRequester.requestRecordAudio(
                onResult = { granted, permanent ->
                    voiceViewModel.onPermissionResult(granted, permanent)
                },
                onDismissed = voiceViewModel::onPermissionDismissed,
            )
        }
    }

    /** As on Today: a device with no page on which a refusal can be undone has to say so. */
    var appSettingsUnavailable by remember { mutableStateOf(false) }

    val recordingNow = voiceState.isRecording
    DisposableEffect(recordingNow) {
        onDispose { if (recordingNow) voiceViewModel.stopForNavigation() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (recordingNow) voiceViewModel.stopForNavigation()
    }

    /**
     * `REQ-046`. Back must not pop the editor while the microphone is live — see the same
     * handler on Today. *Back* is the one exit this screen's own controls do not cover: the
     * header's *Back* button is already disabled during a recording, and the system gesture was
     * not.
     */
    BackHandler(enabled = recordingNow) { /* a recording screen does not pop */ }

    LaunchedEffect(noteId) { viewModel.load(noteId) }
    val playback = rememberAudioPlayback()
    // Nothing plays while the microphone is open (seam verification of `B-254`): the headset's
    // speaker is inches from its microphone, and a recording being dictated would carry the one
    // being played.
    LaunchedEffect(voiceState.isRecording) { if (voiceState.isRecording) playback.stop() }

    // An edit still inside the autosave window must not die with the screen.
    DisposableEffect(noteId) { onDispose { viewModel.flush() } }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Tokens.Space.l)
            // `B-222`, `DEC-0087`: the body field is the bottom of this screen.
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Disabled while the microphone is live, like Today's header: leaving mid-dictation
            // is the navigation `E-03` is about, and `DisposableEffect` above is the safety net
            // rather than the plan.
            FabricTextButton(
                onClick = { viewModel.flush(); onBack() },
                enabled = !voiceState.isRecording,
            ) { Text(stringResource(R.string.action_back)) }
            Text(
                stringResource(if (state.saved) R.string.editor_saved else R.string.editor_saving),
                color = Tokens.Palette.textMuted,
                style = MaterialTheme.typography.labelLarge,
            )
            FabricWrapRow {
                // One press starts, the next stops. Same control as the main screen and no window
                // of its own: the dictation lands in the note already open behind it.
                // A silenced microphone is still a recording: the button must read *Stop
                // recording* and end it, not offer to start a second one (`REQ-052`).
                val recording = voiceState.isRecording
                Button(
                    // `T-031`: `start()` asks for the permission itself and `onPermissionResult`
                    // starts the recording, so there is one branch here where there were two.
                    // The label below still changes after a refusal, because then the press
                    // genuinely is "ask me again" — but it is the same press either way.
                    onClick = {
                        if (recording) voiceViewModel.stopAndTranscribe(true) else {
                            playback.stop()
                            voiceViewModel.start()
                        }
                    },
                    enabled = voiceState !is VoiceState.Transcribing &&
                        voiceState !is VoiceState.PreparingEngine,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording) Tokens.Palette.recording else Tokens.Palette.accent,
                    ),
                    modifier = Modifier.height(Tokens.Space.controlHeight),
                ) {
                    Text(
                        stringResource(
                            when {
                                voiceState is VoiceState.Transcribing -> R.string.state_transcribing
                                voiceState is VoiceState.PreparingEngine -> R.string.state_transcribing
                                voiceState is VoiceState.NeedsPermission -> R.string.action_allow_microphone
                                recording -> R.string.action_stop_record
                                else -> R.string.action_record
                            },
                        ),
                    )
                }
                FabricOutlinedButton(
                    onClick = { viewModel.delete(onBack) },
                ) { Text(stringResource(R.string.action_delete)) }
            }
        }

        // `B-254`: every recording the note keeps — `DEC-0090` keeps the first when a second
        // dictation lands here. One recording needs no list: Today's row already plays it.
        if (state.recordings.size > 1) RecordingsList(state.recordings, playback, enabled = !recordingNow)

        state.message?.let { message ->
            ErrorBanner(
                message = message,
                // Exhaustive with no `else`. An editor message is a storage failure, and every
                // action it can carry is answered by re-reading the note.
                onAction = { action ->
                    when (action) {
                        UiAction.RETRY_LOAD,
                        UiAction.RETRY_DICTATION,
                        UiAction.RETRY_SPACE,
                        UiAction.OPEN_SETTINGS,
                        UiAction.OPEN_APP_SETTINGS,
                        UiAction.GRANT_PERMISSION,
                        UiAction.DOWNLOAD_MODEL,
                        UiAction.RESUME_DOWNLOAD,
                        UiAction.DOWNLOAD_AGAIN,
                        UiAction.WRITE_NOTE,
                        // Settings' export is the only producer (`B-258`); not reachable here.
                        UiAction.EXPORT_NOTES_ONLY,
                        -> viewModel.retry()
                    }
                },
                onDismiss = viewModel::dismissMessage,
            )
            Spacer(Modifier.height(Tokens.Space.m))
        }

        // **The editor had no surface for a voice failure at all** (`B-083`), and step 8 made
        // that worse rather than better: since `DEC-0044` a press asks the system directly, so a
        // **permanently** refused microphone became an instant denial with nothing on screen —
        // press, nothing, press, nothing, for the life of the screen. That is `D-02`'s shape, on
        // the one screen this group touched and did not give a banner.
        //
        // The router is exhaustive and answers each action with something this screen can really
        // do; `OPEN_APP_SETTINGS` is the branch the whole finding is about.
        (voiceState as? VoiceState.Failed)?.let { failed ->
            ErrorBanner(
                message = failed.message,
                onAction = { action ->
                    when (action) {
                        UiAction.GRANT_PERMISSION -> voiceViewModel.start()
                        UiAction.DOWNLOAD_MODEL, UiAction.RESUME_DOWNLOAD, UiAction.DOWNLOAD_AGAIN ->
                            voiceViewModel.downloadModel()
                        UiAction.OPEN_APP_SETTINGS ->
                            if (!permissionRequester.openAppSettings()) appSettingsUnavailable = true
                        UiAction.OPEN_SETTINGS -> onSettings()
                        // The editor is where a note is written, so this action is already taken.
                        UiAction.WRITE_NOTE -> voiceViewModel.dismissFailure()
                        // A voice banner in the editor never carries a dictation commit or a
                        // Space start; all three mean "try the voice flow again" here.
                        UiAction.RETRY_LOAD, UiAction.RETRY_DICTATION, UiAction.RETRY_SPACE ->
                            voiceViewModel.retry()
                        // Settings' export is the only producer (`B-258`); a voice banner never is.
                        UiAction.EXPORT_NOTES_ONLY -> voiceViewModel.dismissFailure()
                    }
                },
                onDismiss = voiceViewModel::dismissFailure,
            )
            Spacer(Modifier.height(Tokens.Space.m))
        }

        if (appSettingsUnavailable) {
            Text(
                stringResource(R.string.state_app_settings_unavailable),
                color = Tokens.Palette.warn,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(Tokens.Space.m))
        }

        // **`REQ-052` / `H11`.** The OS can mute an open microphone and keep feeding the
        // recorder empty audio; without this line the editor shows a live recording that is
        // capturing nothing, for up to ten minutes, and then says *Nothing heard* — which is
        // the app blaming the person for the headset's decision.
        if (voiceState is VoiceState.Silenced) {
            Text(
                stringResource(R.string.state_microphone_silenced),
                color = Tokens.Palette.warn,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(Tokens.Space.m))
        }

        // **`B-178`/`B-179`, on the second surface that can start a dictation.** The record
        // control here is disabled during a decode exactly as Today's is, so without this the
        // editor is the same thirteen-minute wait with no shape and no exit — and it is the
        // likelier place to meet one, because the note is already open and the person is reading
        // it. The bar appears only once whisper has reported something: zero is also what a cloud
        // or whisper-server decode reports for its whole duration.
        if (voiceState is VoiceState.Transcribing || voiceState is VoiceState.PreparingEngine) {
            if (transcriptionPercent > 0) {
                Text(
                    stringResource(R.string.voice_transcribing_progress, transcriptionPercent),
                    color = Tokens.Palette.textMuted,
                    style = MaterialTheme.typography.labelLarge,
                )
                LinearProgressIndicator(
                    progress = { transcriptionPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FabricTextButton(onClick = voiceViewModel::stopTranscription) {
                Text(stringResource(R.string.action_stop_transcribing))
            }
            Spacer(Modifier.height(Tokens.Space.m))
        }

        // A dictation that heard nothing is the other silent dead end this screen had.
        if (voiceState is VoiceState.NothingHeard) {
            Text(
                stringResource(R.string.state_nothing_heard),
                color = Tokens.Palette.warn,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(Tokens.Space.m))
        }

        val note = state.note
        when {
            state.loading -> CircularProgressIndicator()
            note == null -> Text(stringResource(R.string.state_note_gone), color = Tokens.Palette.warn)
            else -> {
                OutlinedTextField(
                    value = note.title,
                    onValueChange = { viewModel.edit(title = it) },
                    label = { Text(stringResource(R.string.label_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Tokens.Space.m))
                OutlinedTextField(
                    value = note.body,
                    onValueChange = { viewModel.edit(body = it) },
                    label = { Text(stringResource(R.string.field_body_hint)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                if (note.tags.isNotEmpty()) {
                    Spacer(Modifier.height(Tokens.Space.s))
                    Text(note.tags.joinToString(" ") { "#$it" }, color = Tokens.Palette.accent)
                }
                note.transcript?.let { transcript ->
                    Spacer(Modifier.height(Tokens.Space.s))
                    // **Two fields, both sentences** (`REQ-061`, `B-151`). It used to push
                    // `transcript.engine` — `cloud:whisper-large-v3`, `whisper-server` — and
                    // `source.name.lowercase()` into the badge, so the person read
                    // `local_fallback`. The engine identifier is a diagnostic; it stays on the
                    // transcript, where the vault records it and `Log2` can carry it.
                    Text(
                        stringResource(
                            R.string.editor_transcript_meta,
                            transcript.language,
                            stringResource(transcriptSourceRes(transcript.source)),
                        ),
                        color = Tokens.Palette.textMuted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    // **`B-151`'s open half.** The line above names the FACT of a fallback —
                    // *"transcribed on this headset instead"* — and `Note.kt` calls the reason
                    // "the receipt's most important field" while nothing drew it. Instead of
                    // WHAT: the speech service the person configured did not answer, and the
                    // address and the key are two things they can go and change. `warn` rather
                    // than `textMuted` because it is the one part of the receipt that is news.
                    fallbackNotice(transcript)?.let { reason ->
                        Text(
                            stringResource(R.string.editor_transcript_fallback, reason.text()),
                            color = Tokens.Palette.warn,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }

    }
}

/**
 * The recordings a note keeps, current first (`B-254`). A time and *Play* per row; the time is
 * computed by the view model (`RecordingItem.takenAt`), because reading a file's clock is a disk
 * call and this is the drawing thread.
 */
@Composable
internal fun RecordingsList(recordings: List<RecordingItem>, playback: AudioPlayback, enabled: Boolean) {
    val format = remember {
        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
            .withZone(java.time.ZoneId.systemDefault())
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = Tokens.Space.m)) {
        Text(
            stringResource(R.string.editor_recordings_title, recordings.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        recordings.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Tokens.Space.s),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(format.format(java.time.Instant.ofEpochMilli(item.takenAt)), color = Tokens.Palette.textMuted)
                FabricOutlinedButton(onClick = { playback.toggle(item.path, item.path) }, enabled = enabled) {
                    Text(
                        stringResource(
                            if (playback.playingId == item.path) R.string.action_stop_recording else R.string.action_play_recording,
                        ),
                    )
                }
            }
        }
    }
}

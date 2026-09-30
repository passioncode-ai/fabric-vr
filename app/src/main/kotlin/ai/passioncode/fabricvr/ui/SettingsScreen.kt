package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.theme.Tokens
import ai.passioncode.fabricvr.common.ui.ErrorBanner
import ai.passioncode.fabricvr.common.ui.FabricButton
import ai.passioncode.fabricvr.common.ui.FabricChip
import ai.passioncode.fabricvr.common.ui.FabricDangerButton
import ai.passioncode.fabricvr.common.ui.FabricOutlinedButton
import ai.passioncode.fabricvr.common.ui.FabricTextButton
import ai.passioncode.fabricvr.common.ui.FabricWrapRow
import ai.passioncode.fabricvr.startAppSettings
import ai.passioncode.fabricvr.stt.CloudTranscriptionClient
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel



@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /** See `FabricApp`. Null hides the *Restore* row rather than offering a dead button. */
    archivePicker: ai.passioncode.fabricvr.ArchivePicker? = null,
    /**
     * Opens the licence notice (`REQ-063`, audit `H13`). Null for the same reason
     * [archivePicker] is: a host with no route there shows no row rather than a control that
     * goes nowhere, which is `D-02`'s shape. Both shipped hosts pass one.
     */
    onLicences: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var server by rememberSaveable { mutableStateOf("") }
    var customModel by rememberSaveable { mutableStateOf("") }
    var cloudUrl by rememberSaveable { mutableStateOf("") }
    var cloudKey by rememberSaveable { mutableStateOf("") }
    var cloudModel by rememberSaveable { mutableStateOf("") }

    // The fields follow the state rather than guessing it, and the key box is cleared only when a
    // save actually succeeded — clearing it on a failed write threw away what the person pasted.
    LaunchedEffect(state.serverUrl) { server = state.serverUrl }
    var exportAudio by rememberSaveable { mutableStateOf(false) }
    /** As on Today: a device with no page on which a refusal can be undone has to say so. */
    var appSettingsUnavailable by remember { mutableStateOf(false) }
    val appContext = LocalContext.current
    LaunchedEffect(state.cloudUrl) { cloudUrl = state.cloudUrl }
    LaunchedEffect(state.cloudModel) { cloudModel = state.cloudModel }
    // The key field is emptied after **its own** save: the value is in the Keystore and the field
    // showing it back would be the one place it lived in plain sight.
    //
    // `B-12`: this used to fire on any successful save at all — language, provider, model, either
    // URL — so pasting a 70-character key and then tapping a language chip threw the key away
    // with no message. `lastSaved` is what makes the difference, and never clearing the field was
    // the other option and the wrong one: leaving a secret in a `TextField` after it has been
    // committed is the one thing the old code got right.
    LaunchedEffect(state.savedTick) {
        if (state.savedTick > 0 && state.lastSaved == SavedField.CLOUD_KEY) cloudKey = ""
    }

    Column(
        // `B-222`, `DEC-0087`: four fields here, and the server URL is near the bottom.
        modifier = Modifier.fillMaxSize().padding(Tokens.Space.l).imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        FabricTextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }

        state.message?.let { message ->
            ErrorBanner(
                message = message,
                // **Exhaustive, with no `else`.** Three of the four `ErrorBanner` call sites used
                // to throw the action away and call `retry()` — so *Download* under a cancelled
                // transfer reloaded the screen instead of starting one, and a new `UiAction`
                // would land there silently. The compiler now refuses a screen that has not
                // answered for every action it can be handed.
                onAction = { action ->
                    when (action) {
                        // One call for all three: the downloader decides for itself whether the
                        // bytes on disk can be resumed. The members exist so the BUTTON could
                        // say which it would be before the person agreed to it (`REQ-047`).
                        UiAction.DOWNLOAD_MODEL, UiAction.RESUME_DOWNLOAD, UiAction.DOWNLOAD_AGAIN ->
                            viewModel.downloadModel()
                        // Settings raises neither a dictation commit nor a Space start, so the
                        // three retries are one answer here: re-run the action that failed.
                        UiAction.RETRY_LOAD, UiAction.RETRY_DICTATION, UiAction.RETRY_SPACE ->
                            viewModel.retry()
                        // This *is* the settings page; a message asking to open it has nowhere
                        // to go, so a retry is the only honest answer.
                        UiAction.OPEN_SETTINGS -> viewModel.retry()
                        // **The system page, not this one.** `D-02` is exactly the substitution
                        // of one for the other, and `scripts/check-seams.sh` refuses an arm that
                        // does not reach it — it caught this branch routing to `retry()` the
                        // moment it was written.
                        UiAction.OPEN_APP_SETTINGS -> {
                            if (!appContext.startAppSettings()) appSettingsUnavailable = true
                        }
                        // A runtime request needs a requester and this screen holds none, so no
                        // message it produces carries this action.
                        UiAction.GRANT_PERMISSION -> viewModel.retry()
                        // `T-028` put the editor back, but Settings has no route to it.
                        UiAction.WRITE_NOTE -> viewModel.retry()
                        // `B-258`: one recording could not be read, so the export is offered
                        // again without recordings — the notes are the part a backup cannot lose.
                        UiAction.EXPORT_NOTES_ONLY -> viewModel.exportVault(includeAudio = false)
                    }
                },
                onDismiss = viewModel::dismissMessage,
            )
        }

        if (appSettingsUnavailable) {
            Text(
                stringResource(R.string.state_app_settings_unavailable),
                color = Tokens.Palette.warn,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.height(Tokens.Space.s))
        Text(
            stringResource(R.string.label_speech),
            style = MaterialTheme.typography.titleMedium,
            // `B-225`: a section title is a heading, and a screen reader can only
            // offer "jump to the next section" if something says so.
            modifier = Modifier.semantics { heading() },
        )

        // Where speech goes is a decision about the person's voice, so it is the first thing here
        // and it says out loud what each choice means.
        Text(stringResource(R.string.label_speech_provider), color = Tokens.Palette.textMuted)
        // **`M23`'s smallest target, raised — and since `B-154`, spaced as well.** A raw
        // `FilterChip` measures 32 dp against this project's own 72 dp floor
        // (`Tokens.Space.controlHeight`), which in a headset is about 1.4° of arc: a target a
        // controller ray cannot hold. `FabricChip` carries that floor. `FabricWrapRow` carries
        // the other half nobody had noticed: a bare `FlowRow` spaces its cross axis by
        // `Arrangement.Top`, which is **zero**, so the second line of wrapped 72 dp chips shared
        // an edge with the first.
        FabricWrapRow {
            SttProvider.entries.forEach { provider ->
                FabricChip(
                    selected = state.provider == provider,
                    onClick = { viewModel.saveProvider(provider) },
                    label = stringResource(provider.labelRes),
                )
            }
        }
        if (state.carriedOverServer) {
            // Their speech started going somewhere else on this launch. Saying so is the whole
            // point of the carry-over; performing it silently would be the defect it fixes.
            Text(
                stringResource(R.string.settings_carried_over_server),
                color = Tokens.Palette.accent,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Text(
            stringResource(state.provider.noteRes),
            color = Tokens.Palette.textMuted,
            style = MaterialTheme.typography.labelLarge,
        )

        Spacer(Modifier.height(Tokens.Space.s))
        // `G-02`. `DEC-0012` copies every transcript to the system clipboard and stands — in a
        // headset the point of speaking is usually to paste somewhere else. What was missing is
        // the ability to say no to a cross-app write nobody offered, whose only notice lasts
        // 2.5 s. Beside the speech block, because that is where the dictation is configured.
        // **The whole row is the target and the whole row is the announcement** (`B-221`,
        // `B-225`). A bare `Switch` is Material's 52x32 dp thumb, under Meta's 48 dp minimum, and
        // the 72 dp `heightIn` around it bought nothing because the Row was not clickable — a
        // controller ray had to land on the thumb. `toggleable` with `Role.Switch` makes the row
        // itself the control, and `mergeDescendants` makes a screen reader say the label with the
        // state instead of announcing an unnamed toggle beside an unrelated line of text.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.Space.controlHeight)
                .toggleable(
                    value = state.copyTranscript,
                    onValueChange = { viewModel.saveCopyTranscript(it) },
                    role = Role.Switch,
                )
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.label_copy_transcript), modifier = Modifier.weight(1f))
            Switch(checked = state.copyTranscript, onCheckedChange = null)
        }
        Text(
            stringResource(R.string.settings_copy_transcript_note),
            color = Tokens.Palette.textMuted,
            style = MaterialTheme.typography.labelLarge,
        )

        // `REQ-062` / `M22`. Beside the clipboard switch because both are about what a finished
        // dictation does beyond the note itself, and Meta requires the cue to be optional.
        // **The whole row is the target and the whole row is the announcement** (`B-221`,
        // `B-225`). A bare `Switch` is Material's 52x32 dp thumb, under Meta's 48 dp minimum, and
        // the 72 dp `heightIn` around it bought nothing because the Row was not clickable — a
        // controller ray had to land on the thumb. `toggleable` with `Role.Switch` makes the row
        // itself the control, and `mergeDescendants` makes a screen reader say the label with the
        // state instead of announcing an unnamed toggle beside an unrelated line of text.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.Space.controlHeight)
                .toggleable(
                    value = state.feedbackCues,
                    onValueChange = { viewModel.saveFeedbackCues(it) },
                    role = Role.Switch,
                )
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.label_feedback_cues), modifier = Modifier.weight(1f))
            Switch(checked = state.feedbackCues, onCheckedChange = null)
        }
        Text(
            stringResource(R.string.settings_feedback_cues_note),
            color = Tokens.Palette.textMuted,
            style = MaterialTheme.typography.labelLarge,
        )

        Text(stringResource(R.string.label_speech_language), color = Tokens.Palette.textMuted)
        FabricWrapRow {
            LANGUAGES.forEach { code ->
                FabricChip(
                    selected = state.language == code,
                    onClick = { viewModel.saveLanguage(code) },
                    label = if (code == "auto") stringResource(R.string.label_language_auto) else code,
                )
            }
        }

        Spacer(Modifier.height(Tokens.Space.s))
        Text(stringResource(R.string.label_speech_model), color = Tokens.Palette.textMuted)
        FabricWrapRow {
            WhisperModel.entries.forEach { model ->
                FabricChip(
                    selected = state.whisperModel == model,
                    onClick = { viewModel.saveWhisperModel(model) },
                    label = stringResource(model.labelRes),
                )
            }
        }
        Text(
            if (state.modelPresent) {
                stringResource(R.string.settings_model_present, state.modelName)
            } else {
                stringResource(R.string.settings_model_absent, state.modelName)
            },
            color = if (state.modelPresent) Tokens.Palette.accent else Tokens.Palette.warn,
        )
        state.downloading?.let { (bytes, total) ->
            LinearProgressIndicator(
                progress = { if (total > 0) bytes.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.settings_download_progress, bytes / 1_000_000, total / 1_000_000), color = Tokens.Palette.textMuted)
            FabricTextButton(onClick = { viewModel.cancelDownload() }) { Text(stringResource(R.string.action_cancel)) }
        }
        // Downloads are keyed by model since `T-021`, so choosing a different one no longer
        // cancels the transfer that is running — which means a 539 MB download of some other
        // model would be **invisible** on this screen unless it says so.
        state.otherDownloads.forEach { (model, progress) ->
            val (bytes, total) = progress
            Text(
                stringResource(
                    R.string.settings_model_downloading_other,
                    stringResource(model.shortLabelRes),
                    if (total > 0) (bytes * 100 / total).toInt() else 0,
                ),
                color = Tokens.Palette.textMuted,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (state.downloading == null) {
            FabricWrapRow {
                if (!state.modelPresent) {
                    FabricButton(onClick = { viewModel.downloadModel() }) {
                        Text(
                            stringResource(
                                R.string.action_download_model,
                                state.whisperModel.bytes / 1_000_000,
                            ),
                        )
                    }
                } else {
                    FabricTextButton(onClick = { viewModel.removeModel() }) { Text(stringResource(R.string.action_remove_model)) }
                }
            }
        }

        // **What the models cost, and the way to get it back** (`B-199`). Directly under the
        // model chips, because that is where the bytes were spent: choosing a different model
        // keeps the old one on disk — deliberately, so switching back is free — and until this
        // block existed nothing said so and nothing could undo it.
        ModelStorageSection(
            state = state,
            formatSize = { bytes -> android.text.format.Formatter.formatShortFileSize(appContext, bytes) },
            onRemove = { model -> viewModel.removeInstalled(model) },
            onFreeUpSpace = { viewModel.freeUpSpace() },
        )

        if (state.provider == SttProvider.CLOUD) {
            Spacer(Modifier.height(Tokens.Space.s))
            OutlinedTextField(
                value = cloudUrl,
                onValueChange = { cloudUrl = it },
                label = { Text(stringResource(R.string.field_cloud_url)) },
                placeholder = { Text(CloudTranscriptionClient.SUGGESTED_BASE_URL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_cloud_hint),
                color = Tokens.Palette.textMuted,
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedTextField(
                value = cloudKey,
                onValueChange = { cloudKey = it },
                label = { Text(stringResource(R.string.field_cloud_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.cloudKeyUnreadable) {
                Text(stringResource(R.string.settings_key_unreadable), color = Tokens.Palette.warn)
            } else if (state.cloudKeyUnreadableNow) {
                // Not the same sentence. The bytes are still stored; asking for the key again
                // would be asking for something that is not lost (`C-04`).
                Text(stringResource(R.string.settings_key_unreadable_now), color = Tokens.Palette.warn)
            }
            state.cloudKeyTail?.let { tail ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_cloud_key_saved, tail),
                        color = Tokens.Palette.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    // A stored secret a person cannot remove is not a setting, it is a trap.
                    FabricTextButton(
                        onClick = { viewModel.clearCloudKey() },
                    ) { Text(stringResource(R.string.action_clear)) }
                }
            }
            OutlinedTextField(
                value = cloudModel,
                onValueChange = { cloudModel = it },
                label = { Text(stringResource(R.string.field_cloud_model)) },
                placeholder = { Text(CloudTranscriptionClient.DEFAULT_MODEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // **One call, not three.** Three independent saves are three coroutines with three
            // IO hops, and the terminal `lastSaved` was whichever landed last — so the key field
            // stopped being cleared on the one path `B-12` was about. See `saveCloud`.
            FabricButton(onClick = { viewModel.saveCloud(cloudUrl, cloudKey, cloudModel) }) {
                Text(stringResource(R.string.action_save))
            }
        }

        // Shown whenever a URL is saved, not only when SERVER is selected: a value the person
        // cannot see is one they cannot diagnose, and this field holding an address that now routes
        // nowhere is exactly the state an upgrade left them in.
        if (state.provider == SttProvider.SERVER || state.serverUrl.isNotBlank()) {
            Spacer(Modifier.height(Tokens.Space.s))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(stringResource(R.string.field_server_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_server_hint),
                color = Tokens.Palette.textMuted,
                style = MaterialTheme.typography.labelLarge,
            )
            FabricButton(onClick = { viewModel.saveServerUrl(server) }) { Text(stringResource(R.string.action_save_server)) }
        }

        Spacer(Modifier.height(Tokens.Space.s))
        state.lastCrash?.let { report ->
            // Above the vault section deliberately: it is the only thing on this screen that is
            // about something already broken, and a person looking for it should not have to scroll.
            Spacer(Modifier.height(Tokens.Space.s))
            Text(
            stringResource(R.string.label_last_crash),
            style = MaterialTheme.typography.titleMedium,
            // `B-225`: a section title is a heading, and a screen reader can only
            // offer "jump to the next section" if something says so.
            modifier = Modifier.semantics { heading() },
        )
            Text(
                stringResource(R.string.settings_crash_hint),
                color = Tokens.Palette.textMuted,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                report.lines().take(CRASH_PREVIEW_LINES).joinToString("\n"),
                color = Tokens.Palette.warn,
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(report)) },
                    modifier = Modifier.height(Tokens.Space.controlHeight),
                ) { Text(stringResource(R.string.action_copy_crash)) }
                FabricOutlinedButton(
                    onClick = { viewModel.clearCrash() },
                ) { Text(stringResource(R.string.action_dismiss_crash)) }
            }
        }

        Text(
            stringResource(R.string.label_vault),
            style = MaterialTheme.typography.titleMedium,
            // `B-225`: a section title is a heading, and a screen reader can only
            // offer "jump to the next section" if something says so.
            modifier = Modifier.semantics { heading() },
        )
        FabricTextButton(onClick = { clipboard.setText(AnnotatedString(state.vaultPath)) }) {
            Text(state.vaultPath)
        }

        // The way out of app-private storage. `adb uninstall` — which a release signature change
        // forces — deletes the vault, the database, the model and every Keystore value, and until
        // this row existed there was no way to take a single note off the headset.
        Spacer(Modifier.height(Tokens.Space.s))
        Text(stringResource(R.string.label_export_scope), color = Tokens.Palette.textMuted)
        FabricWrapRow {
            // Notes only by default: a year of dictation is three orders of magnitude larger than
            // the Markdown, and the archive a person takes before reinstalling must not be the one
            // that runs out of room.
            FabricChip(
                selected = !exportAudio,
                onClick = { exportAudio = false },
                label = stringResource(R.string.label_export_notes_only),
            )
            FabricChip(
                selected = exportAudio,
                onClick = { exportAudio = true },
                label = stringResource(R.string.label_export_with_audio),
            )
        }
        Button(
            onClick = { viewModel.exportVault(exportAudio) },
            enabled = !state.exporting,
            modifier = Modifier.height(Tokens.Space.controlHeight),
        ) {
            Text(
                stringResource(
                    if (state.exporting) R.string.settings_export_running else R.string.action_export_vault,
                ),
            )
        }
        state.exportedTo?.let { path ->
            Text(
                stringResource(R.string.settings_export_done, path),
                color = Tokens.Palette.accent,
                style = MaterialTheme.typography.labelLarge,
            )
            // Measured on both fielded headsets 2026-09-20: Horizon OS carries a share sheet and
            // Telegram and Discord register for `application/zip`, so the archive can leave without
            // a cable. Guarded, because a headset with no target must not be shown a button that
            // opens an empty list — which is the shape T-023's spec rejected the route on.
            val share = remember(state.exportHandle) {
                state.exportHandle?.let { handle ->
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(handle))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
            if (share != null && share.resolveActivity(appContext.packageManager) != null) {
                FabricOutlinedButton(
                    onClick = {
                        appContext.startActivity(
                            Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                ) { Text(stringResource(R.string.action_export_send)) }
            }
        }
        // **The way back in** (`REQ-054`, audit `H4`). The export has existed since `T-023` and
        // `VaultImporter`'s own comment called the archive "a genuine restore path: unzip it
        // back into `filesDir/vault`" — while `filesDir` is app-private, `run-as` is unavailable
        // against a release build, and nothing in the tree could unzip anything. A backup with
        // no restore is a copy of the person's notes that they cannot use, and the uninstall a
        // signature change forces is exactly when they would need it.
        archivePicker?.let { picker ->
            Spacer(Modifier.height(Tokens.Space.s))
            FabricOutlinedButton(
                onClick = {
                    picker.pickArchive { uri ->
                        if (uri != null) {
                            // Opened inside the view model's own IO hop, not here: a
                            // `ContentResolver` call on the drawing thread is `R1`, and the
                            // importer closes what it is handed.
                            viewModel.restoreVault { appContext.contentResolver.openInputStream(uri) }
                        }
                    }
                },
                enabled = !state.restoring,
                modifier = Modifier.height(Tokens.Space.controlHeight),
            ) {
                Text(
                    stringResource(
                        if (state.restoring) R.string.settings_restore_running else R.string.action_restore_vault,
                    ),
                )
            }
            state.restored?.let { summary ->
                Text(
                    stringResource(R.string.settings_restore_done, summary.imported, summary.skipped),
                    color = Tokens.Palette.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
                // **Its own sentence, and only when there is one to say.** A well-formed export
                // produces no refusals at all, so a non-zero count means the archive came from
                // somewhere else — or tried to write outside the vault. Folding it into the
                // line above would bury the one number worth reading.
                if (summary.refused > 0) {
                    Text(
                        stringResource(R.string.settings_restore_refused, summary.refused),
                        color = Tokens.Palette.warn,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        if (state.vaultOutOfSync > 0) {
            Text(
                pluralStringResource(
                    R.plurals.settings_vault_out_of_sync,
                    state.vaultOutOfSync,
                    state.vaultOutOfSync,
                ),
                color = Tokens.Palette.warn,
            )
            FabricTextButton(onClick = { viewModel.retryVaultSync() }) { Text(stringResource(R.string.action_retry_vault)) }
        }
        Text(
            stringResource(R.string.settings_vault_hint),
            color = Tokens.Palette.textMuted,
            style = MaterialTheme.typography.labelLarge,
        )

        LaunchedEffect(Unit) { viewModel.refreshAudioUsage() }
        RecordingsSection(
            state = state,
            formatSize = { bytes -> android.text.format.Formatter.formatShortFileSize(appContext, bytes) },
            onRetention = { days -> viewModel.saveAudioRetention(days) },
            onDelete = { viewModel.deleteOldRecordings() },
        )

        // **`REQ-063` / `H13`.** whisper.cpp is MIT and its notice has to travel with the binary;
        // a submodule path is not something a person holding the APK can reach. The page renders
        // the repository's own `NOTICE`, copied into the APK by `copyLicenceNotice` — one file,
        // so a dependency change cannot leave a second copy behind saying something else.
        onLicences?.let { open ->
            Spacer(Modifier.height(Tokens.Space.s))
            FabricTextButton(onClick = open) {
                Text(stringResource(R.string.label_licences))
            }
        }

        Spacer(Modifier.height(Tokens.Space.s))
        Text(
            stringResource(
                R.string.settings_version,
                state.versionName,
                state.versionCode,
                state.gitBranch,
                // The date alone: the time of day answers nothing a person asks out loud, and the
                // full ISO stamp is what `BUILD_TIME` keeps for anyone who needs it.
                state.builtAt.take(10),
            ),
            color = Tokens.Palette.textMuted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * **The model shelf**, as a stateless block (`B-199`).
 *
 * `InstalledModels` has listed, totalled and reclaimed every model on disk since `B-093` —
 * including a leftover `.part` — and **no screen called any of it**. `SettingsViewModel.removeModel()`
 * freed the selected model's file and nothing else, so trying `large-turbo`, disliking the speed
 * and going back to `small` left 574 MB that nothing listed and nothing could reclaim short of
 * the uninstall that takes the vault with it. All five together are 1.395 GB on a headset whose
 * whole point is that it works offline.
 *
 * Stateless and `internal` for [RecordingsSection]'s reason (`DEC-0043`): the claims this block
 * makes — that a row names what it occupies **now**, that an unfinished transfer is not a model,
 * and that *Free up space* keeps the selected one — are claims about rendered text, and the
 * screen as a whole cannot be composed on the JVM without a `Graph`.
 *
 * @param formatSize see [RecordingsSection] — a `Context` call a test wants a stable answer from.
 */
@Composable
internal fun ModelStorageSection(
    state: SettingsUiState,
    formatSize: (Long) -> String,
    onRemove: (WhisperModel) -> Unit,
    onFreeUpSpace: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Spacer(Modifier.height(Tokens.Space.s))
        Text(
            stringResource(R.string.label_model_storage),
            style = MaterialTheme.typography.titleMedium,
            // `B-225`: a section title is a heading, and a screen reader can only
            // offer "jump to the next section" if something says so.
            modifier = Modifier.semantics { heading() },
        )

        if (state.installed.isEmpty()) {
            Text(
                stringResource(R.string.settings_models_none),
                color = Tokens.Palette.textMuted,
                style = MaterialTheme.typography.labelLarge,
            )
            return@Column
        }

        Text(
            stringResource(
                R.string.settings_models_total,
                formatSize(state.installedBytes),
                state.installed.size,
            ),
            color = Tokens.Palette.textMuted,
        )
        state.installed.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                // `B-154`'s gap, and here it is load-bearing rather than decorative: *Remove* is
                // irreversible and it sits at the end of a row a ray sweeps across.
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.settings_model_row,
                        stringResource(entry.model.shortLabelRes),
                        formatSize(entry.bytes),
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(
                        if (entry.complete) R.string.settings_model_row_ready
                        else R.string.settings_model_row_partial,
                    ),
                    // An unfinished transfer is bytes without a model, which is the one row a
                    // person reading this list is most likely to want gone.
                    color = if (entry.complete) Tokens.Palette.textMuted else Tokens.Palette.warn,
                    style = MaterialTheme.typography.labelLarge,
                )
                FabricDangerButton(onClick = { onRemove(entry.model) }) {
                    Text(stringResource(R.string.action_remove))
                }
            }
        }
        // Offered only when it would do something: with one model on disk it is the selected one,
        // and a button that reclaims nothing is a button that teaches people to distrust the next.
        if (state.installed.size > 1) {
            Spacer(Modifier.height(Tokens.Space.s))
            FabricDangerButton(onClick = onFreeUpSpace) {
                Text(stringResource(R.string.action_free_up_space))
            }
        }
        Text(
            stringResource(R.string.settings_models_hint),
            color = Tokens.Palette.textMuted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * **Recordings**, as a stateless block (`REQ-061`, audit `M27`).
 *
 * Until `T-024` no string in the whole product mentioned that audio was stored at all — the vault
 * sentence above talks about Markdown, which is about 0.1% of what is on the disk. Somebody
 * dictating private material for months believed they were keeping text; what they were keeping
 * was an archive of everything they had said, which they could not hear, list, measure or remove
 * (`G-02`).
 *
 * It is a separate composable for `TodayFrame`'s reason (`DEC-0043`): the two claims this block
 * makes to a person — that the retention chips *arm* rather than schedule, and that the button
 * names the set the sweep will actually remove — are claims about rendered text, and the screen
 * as a whole cannot be composed on the JVM without a `Graph`. `CopyTruthTest` measures both.
 *
 * @param formatSize a parameter because `Formatter.formatShortFileSize` needs a `Context` and a
 *   test wants a stable string rather than a locale's idea of a megabyte.
 */
@Composable
internal fun RecordingsSection(
    state: SettingsUiState,
    formatSize: (Long) -> String,
    onRetention: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        Spacer(Modifier.height(Tokens.Space.s))
        Text(
            stringResource(R.string.label_recordings),
            style = MaterialTheme.typography.titleMedium,
            // `B-225`: a section title is a heading, and a screen reader can only
            // offer "jump to the next section" if something says so.
            modifier = Modifier.semantics { heading() },
        )
        Text(
            state.audioUsage?.let { usage ->
                stringResource(R.string.settings_audio_usage, usage.count, formatSize(usage.bytes))
            } ?: stringResource(R.string.state_measuring_recordings),
            color = Tokens.Palette.textMuted,
        )
        Text(
            stringResource(R.string.settings_audio_explain),
            color = Tokens.Palette.textMuted,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(stringResource(R.string.label_audio_retention), color = Tokens.Palette.textMuted)
        FabricWrapRow {
            // **`Nothing` is the default and it is selected first**, deliberately: a retention
            // that defaulted to deleting would have destroyed months of recordings on the first
            // launch after this update, before the person had read this screen (`DEC-0038`).
            // The mechanism ships; the choice is theirs.
            listOf(0, 90, 30, 7).forEach { days ->
                FabricChip(
                    selected = state.audioRetentionDays == days,
                    onClick = { onRetention(days) },
                    // **`M23`, and the floor is no longer spelled here.** A raw `FilterChip`
                    // measures 32 dp against this project's own 72 dp floor, which in a headset
                    // is a target a controller ray cannot hold. `REQ-061` fixed that by writing
                    // `heightIn` at each of this screen's chips, which is the drift `Controls.kt`
                    // exists to prevent — the twenty-fifth chip is the one that forgets. The
                    // wrapper carries it now, and `ControlFloorTest` fails the build if a raw one
                    // comes back. `B-154`'s remainder is closed too: `FabricWrapRow` spaces both
                    // axes, `NoteRow`'s irreversible controls are a `Tokens.Space.l` gap from its
                    // frequent ones, and `Tokens.Palette.danger` is on a control at last.
                    label = if (days == 0) {
                        stringResource(R.string.label_retention_keep_all)
                    } else {
                        stringResource(R.string.label_retention_days, days)
                    },
                )
            }
        }
        // The chips choose; nothing runs until the button is pressed, and `DEC-0038` says so.
        // This line is what makes the row stop reading as a policy (`REQ-061`).
        Text(
            stringResource(R.string.settings_retention_note),
            color = Tokens.Palette.textMuted,
            style = MaterialTheme.typography.labelLarge,
        )
        if (state.audioRetentionDays > 0) {
            // **The button names the set it is about to remove.** It named `audioUsage` — every
            // recording on the headset — while `sweepAudio(cutoff)` removes only what is older
            // than the cutoff. A correct count is a tree walk (`T-019`'s R1 forbids one here),
            // so the label names the set rather than inventing a number.
            FabricOutlinedButton(onClick = onDelete) {
                Text(
                    stringResource(R.string.action_delete_old_recordings, state.audioRetentionDays),
                )
            }
        }
    }
}

/**
 * The languages offered as a pin. Whisper knows many more; these are the ones worth one tap, and
 * "detect automatically" stays first because it is right until it is visibly wrong.
 */
private val LANGUAGES = listOf("auto", "ru", "en", "uk", "de", "fr", "es", "it", "pl", "tr")

/** Enough of the report to recognise the failure without turning the screen into a log. */
private const val CRASH_PREVIEW_LINES = 6

# Axis D — Scenarios and access

Read-only audit of `feat/v1-notes-core` @ `421377e`. Contract: `docs/ux/scenarios.md`
(SCN-001…SCN-015) with `docs/ux/flows.md`, `docs/ux/screens.md`, `docs/ux/foundation.md`.
Code read: `app/src/main/kotlin/ai/passioncode/fabricvr/**`, `core-notes`, `core-common`,
`feature-stt`, `feature-vault`, `feature-assistant`, `app/src/main/AndroidManifest.xml`,
`app/src/main/res/values/strings.xml`.

Nothing was built or run. Every `file:line` below was read at this commit.

**Roll-up:** 3 scenarios hold · 9 drifted · 3 broken.

---

## Scenario walk

### SCN-001: Write and keep a text note

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Tap *New note* → editor opens, title focused, note already created | **MISSING** | `NotesViewModel.createNote()` exists (`NotesViewModel.kt:150`) and is called from nowhere. `TodayScreen.kt:117-139` draws Search / Assistant / Space / Settings and no *New note*. The string `action_new_note` (`strings.xml:29`) is orphaned. `PanelSmokeTest.kt:21` still asserts it is displayed. |
| 1b. Title field focused | **MISSING** | `NoteEditorScreen.kt:140-146` has no `FocusRequester`; compare `SearchScreen.kt:43,46`, which does. |
| 2. Type → auto-save indicator says "saved" within a second | `EditorViewModel.kt:46-49` (600 ms debounce), indicator `NoteEditorScreen.kt:82-86` | Holds. |
| 3. Leave the editor → SCR-01 lists the note with title, first line and time | `NoteEditorScreen.kt:67,81` (flush), list `TodayScreen.kt:204-217` | **Drift:** the row renders one blob — `noteText(note)` at `TodayScreen.kt:388` — not title + first line. No timestamp is rendered anywhere in the row. `Note.preview` (`Note.kt:35`) is used only by Search. |
| 4. Force-stop and reopen → the note is still there | Room, `NotesDatabase.open` via `Graph.kt:43-45`; `EditorViewModel.flush()` `EditorViewModel.kt:88-93` | Holds. |
| Errors: storage write fails → inline message, text kept, *Retry* | `EditorViewModel.kt:105-110`, banner `NoteEditorScreen.kt:126-133`, real retry `EditorViewModel.kt:96-101` | Holds. |

**Verdict: drifted.** The scenario's only entry point no longer exists; the editor is reachable
only by tapping an existing row (including the auto-created daily note).

### SCN-002: Land in today's note

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1a. Today's date is the **first card** | **MISSING** | `NotesUiState.today` and `.dayLabel` (`NotesViewModel.kt:59-60`) are populated by `loadDailyNote()` (`NotesViewModel.kt:119-128`) and rendered by nothing. `TodayScreen.kt:122-126` draws the app name, not the date. `label_today_note` (`strings.xml:42`) is orphaned. |
| 1b. Today's daily note exists whether or not anything was written | `NotesViewModel.kt:89`, `NotesRepository.kt:141-159`, `NoteDao.kt:82-87` (one transaction, unique `dayKey`) | Holds — and it does appear in the list as an ordinary row titled `2026-09-20`, because `observeAll` returns everything (`NoteDao.kt:13`). |
| 1c. It is *first* | **Drift** | Ordering is `updatedAt DESC` (`NoteDao.kt:13`). A daily note created yesterday and untouched sinks below every dictation. Only on the day it is created is it at the top. |
| 2. Tap the card → editor opens on today's note, **cursor at the end of its body** | `TodayScreen.kt:209` → `NoteEditorScreen.kt:148-153` | Cursor placement **MISSING**: the body `OutlinedTextField` takes a `String`, not a `TextFieldValue`, so the selection is at index 0 on every open. |
| Errors: daily note cannot be created → the card shows the failure and *Retry*, the rest of the list still loads | `NotesViewModel.kt:125` sets `message`; banner `TodayScreen.kt:141-152`; `retry()` re-reads (`NotesViewModel.kt:135-139`) | Holds in substance; there is no card, so the failure lands in the page banner. |

**Verdict: drifted.**

### SCN-003: Tag a note inline and filter by tag

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Type `#idea` in the body → on save `idea` appears in the tag row | `NotesRepository.kt:86-96` (re-parsed on every upsert, never unioned), `TagParser.kt`; rendered `NoteEditorScreen.kt:154-157` | Holds. |
| 2. Return to SCR-01 → a chip `idea` is offered above the list | **MISSING** | `NotesUiState.tags` (`NotesViewModel.kt:57`) is filled from `observeTags()` (`NotesViewModel.kt:105`) and rendered nowhere. `TodayScreen.kt` contains no chip. |
| 3. Tap the chip → the list narrows, the chip shows selected | **MISSING** | `NotesViewModel.selectTag()` (`NotesViewModel.kt:130`) has no caller. The repository side works (`NoteDao.kt:16-20`, with LIKE escaping at `NotesRepository.kt:81-82`). |
| 4. Tap again → the full list returns | **MISSING** | Same. |
| Errors: a tag matching nothing disappears with its last note | `NotesRepository.kt:70-78` derives tags from live rows | Would hold if anything drew them. |

**Verdict: broken.** Half the scenario has a working view model and no interface, in either host.

### SCN-004: Speak a note and keep the transcript

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Press *Record* once → the button turns red, reads *Stop recording*, a level meter and elapsed time appear above it; nothing opens on top | `TodayScreen.kt:313-342` (one button, `holdButtonHeight` = 128dp at `Tokens.kt:42`), red at `:322-324`, meter + seconds at `:253-263` | Holds, and matches the post-2026-09-19 design. |
| 2. Speak; the meter moves; no finger is held | `AudioRecorder.kt:60-73` emits RMS; `VoiceViewModel.kt:86-90` | Holds. |
| 3. Press *Stop recording* → the button reads "Transcribing…" and is not pressable | `TodayScreen.kt:310,321,333`; `VoiceViewModel.kt:97-121` | Holds. |
| 4. The note writes itself, text already on the clipboard, "Saved, and copied. Paste it anywhere." for a few seconds | `TodayScreen.kt:102-108` (self-commit), `:169-175` + `CONFIRMATION_MS = 2_500` at `:460`; string `strings.xml:143` | Holds. |
| Alt: *Cancel* during recording returns to SCR-01 and deletes the recording | **MISSING** | `VoiceViewModel.stopAndTranscribe(completed = false)` (`VoiceViewModel.kt:97,101-104`) is the cancel path and its only caller passes `true` (`TodayScreen.kt:157`, `NoteEditorScreen.kt:98`). Once recording starts there is no way out but to transcribe. `action_discard` (`strings.xml:22`) is orphaned. |
| Alt: a wrong word is corrected afterwards — the note carries *Transcribe again* and *Copy*, tapping it opens the editor | `TodayScreen.kt:405-455` | Holds. Layout matches SCN-015's claim: *Transcribe again* + *Delete* on the left (`:405-440`), *Copy* alone on the right (`:445-455`). |
| Errors: silence → "Nothing was heard" with *Try again* | `VoiceViewModel.kt:111-113`, shown `TodayScreen.kt:300-304` | **Drift:** the message shows, but there is no *Try again* control — the button below simply reverts to *Record*. `action_try_again` (`strings.xml:26`) is orphaned. Behaviourally equivalent, verbally not. |
| Errors: the engine fails → the message names the failure, **the audio is kept and offered as a note without a transcript** | `VoiceViewModel.kt:139` → `VoiceState.Failed`; banner `TodayScreen.kt:281-292` | **MISSING and inverted.** The banner's only exit (`UiAction.RETRY` → `onDismissFailure` at `TodayScreen.kt:288,291`) calls `VoiceViewModel.cancel()` (`:166`), which **deletes the recording** (`VoiceViewModel.kt:201`). No note is offered. The thing the scenario promises to keep is the thing the only button destroys. |

**Verdict: drifted** on the happy path (it matches the new design closely), **broken** on the
failure path.

### SCN-005: Dictate Russian, then English

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| Precondition: language setting *Detect automatically*, or one of nine pinned | `SettingsScreen.kt:147-158,290` — exactly the nine listed plus `auto` | Holds. |
| 1-2. Russian then English → the transcript is in that language and the badge reads `ru` / `en` | detection `WhisperEngine.kt:64`; badge `TodayScreen.kt:395-403` (`engine · language`) | Holds for the local engine. |
| Alt: pinned → the engine is told that language, the badge shows it **as pinned rather than detected** | passed at `WhisperEngine.kt:62`, `CloudTranscriptionClient.kt:66-67` | **Drift (local/cloud):** nothing in the UI distinguishes pinned from detected — `Transcript` has no such field (`Note.kt:13-20`). **MISSING (server):** `RemoteWhisperClient` never sends a `language` part (form built at `RemoteWhisperClient.kt:51-57`) and reports `language = langHint.orEmpty()` (`:82`), so with `auto` pinned the badge reads the literal string `auto`. |
| Errors: *Transcribe again* offers every on-device model by name and size plus the cloud service and the whisper server | `SpeechChoice.kt:46-49` (5 + cloud + server), menu `TodayScreen.kt:413-430`, names `strings.xml:121-129` | Holds. |
| Errors: the recording is kept in the vault so re-running costs no re-speaking | `AudioAdoption.kt:16-25`, `Vault.kt:56-81` | Holds. |
| Errors: **the body is replaced only if it is still exactly what the last transcription produced** | `NotesViewModel.kt:193-207` | Holds — and it is the cleanest part of the file. |
| …and the person is told which happened | **MISSING** | `retranscribe` returns `RetranscribeResult` (`NotesViewModel.kt:52,207`) and the caller throws it away (`TodayScreen.kt:212-214`). `state_retranscribed` and `state_retranscribed_kept` (`strings.xml:110-111`) are orphaned. On the "edited text kept" branch the screen shows literally nothing, so a re-run looks like a no-op. |

**Verdict: drifted.**

### SCN-006: Get the speech model before the first dictation

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. First *Record* → instead of recording, the model prompt appears **naming the model and its size**, with one line saying **text notes work meanwhile** | gate `VoiceViewModel.kt:80-83`; banner `TodayScreen.kt:281-292`; text `core-common/…/strings.xml:10` — "The speech model isn't on this headset yet." | **Drift, and the worst copy on the first-run path.** No model name, no 190 MB, no "text notes still work". `AppError.ModelMissing` carries the name (`AppError.kt:13`) and `UiStateMapper.kt:21-22` drops it. |
| 2. Tap *Download* → progress with percentage and megabytes, *Cancel* available | `TodayScreen.kt:265-279`; `settings_download_progress` = `%1$d / %2$d MB` (`strings.xml:84`) | **Drift:** megabytes yes, percentage no (the bar carries it graphically). *Cancel* holds (`:275-278` → `VoiceViewModel.kt:171-175`). |
| 3. The file's SHA-256 is checked | `ModelDownloader.kt:148-158`, digests pinned at `WhisperModel.kt:21-25` | Holds, and the size is checked too. |
| 3b. **The screen says the model is ready** | **MISSING** | `DownloadProgress.Done` → `VoiceState.Idle` (`VoiceViewModel.kt:160`). After a 190 MB wait the screen simply returns to *Record* with no word that it arrived. |
| 3c. …and the recording the person wanted starts | **MISSING** | It does not resume; `start()` must be pressed again. |
| Alt: side-loaded with `adb push` → found on the next launch, never offered | `FileModelStore.isPresent()` `ModelStore.kt:57-63` | Holds, with a sharp edge: the length must equal `expectedBytes` **exactly**, so a differently-quantised `ggml-small.bin` pushed by hand is treated as absent. |
| Errors: network or checksum → partial deleted, the message says which, *Retry* restarts | `ModelDownloader.kt:141-151`; mapping `UiStateMapper.kt:24-33`; both strings distinct (`core-common/…/strings.xml:11-12`) | Holds in Settings. **In the Today banner it does not:** `UiAction.RETRY` routes to `onDismissFailure` (`TodayScreen.kt:288`) = `cancel()`, which clears the message and restarts nothing. `VoiceViewModel.retryLast()` (`VoiceViewModel.kt:178-183`) exists and has no caller. |

**Verdict: drifted.**

### SCN-007: Dictate through a whisper-server, fall back on failure

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Save a whisper-server URL → the setting shows the server as the preferred engine | `SettingsScreen.kt:238-253`, validated `SettingsViewModel.kt:161-174` | **Drift:** saving a URL is no longer enough — the *provider* chip must also be set to *My whisper server* (`Graph.kt:97`). The URL field is only rendered when that chip is already selected (`SettingsScreen.kt:238`), so the two agree, but SCN-007's precondition ("a URL is saved") is now false on its own. |
| 2. Dictate → the audio goes to the server, the engine badge reads `remote` | `SttRouter.kt:20-22`; badge shows `transcript.engine` = `"whisper-server"` (`RemoteWhisperClient.kt:33`, `TodayScreen.kt:397`) | Holds in substance; the badge word differs from the contract's `remote`. |
| 3. Stop the server and dictate again → the transcript is produced on the device, the badge reads `local (fallback)`, **with a one-line notice saying the server did not answer** | fallback `SttRouter.kt:29-30`; `SttSource.LOCAL_FALLBACK` and `fallbackReason` recorded (`Note.kt:3,19`) | **Badge MISSING on the list:** `TodayScreen.kt:395-403` prints `engine · language` and never the source, so a fallback is indistinguishable from a local run. **In the editor** it prints `source.name.lowercase()` (`NoteEditorScreen.kt:165`) = the raw enum `local_fallback`; the three human strings `stt_source_local` / `_remote` / `_local_fallback` (`strings.xml:105-107`) are orphaned. **Notice MISSING:** `fallbackReason` — the field whose KDoc calls it "the receipt's most important field" — is read by nothing in the app. |
| Errors: if the device model is also missing, the message says both and offers *Download* | `SttRouter.kt:26-27` returns the remote error only | **MISSING**: the person is told the server's status and not that there is no local model either. |

**Verdict: drifted.**

### SCN-008: Find a note by a word in its transcript

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Tap *Search* → the field is focused and recent notes are listed | `TodayScreen.kt:127`; focus `SearchScreen.kt:43,46`; recents `SearchViewModel.kt:45-46` | Holds. |
| 2. Type a word only in a transcript → the list narrows and shows the matching line | FTS over `transcript` column `NoteDao.kt:31-39,70-78`; tokenising `NotesRepository.kt:110-121` | Holds. **Low drift:** the "matching line" is the whole transcript truncated to two lines (`SearchScreen.kt:93-96`), not the line that matched. |
| 3. Tap the result → the note opens in the editor | `SearchScreen.kt:89` → `FabricApp.kt:66` | Holds. |
| Alt: typing a tag name also matches | tags are in title/body text, indexed by FTS | Holds. |
| Errors: no match → "Nothing matches «query»" with the query kept | `SearchViewModel.kt:24,55`; `SearchScreen.kt:100-103` | Holds — the `searched` flag exists precisely so "no matches" is not "not asked". |
| Errors: index unavailable → message + *Retry* | `SearchViewModel.kt:48-50,68-72` (a real retry) | Holds. |

**Verdict: holds.**

### SCN-009: Ask the assistant about my notes

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Tap *Assistant* → the chat opens with three example questions and the model name visible | `TodayScreen.kt:128`; examples `ChatScreen.kt:84-91`; model `ChatScreen.kt:50` | Holds. **Low:** the Assistant toolbar button wears `Icons.Filled.Mic` (`TodayScreen.kt:128`) — the microphone glyph, next to a screen whose subject is dictation. |
| 1b. FLW-04's gate: **no key saved → sent to settings with one sentence** before asking | **MISSING** | `ChatScreen`/`ChatViewModel` never check for a key on entry. The person types a question, sends it, and only then gets `AppError.NoApiKey` → `OPEN_SETTINGS` (`UiStateMapper.kt:53-54`, routed `ChatScreen.kt:58`). A state with a way forward, one question later than the contract. |
| 2. Ask → the answer streams word by word, a line names which notes were used | `ChatViewModel.kt:83-90`; context line `ChatScreen.kt:67-74` | Holds. |
| 3. Tap *Stop* mid-answer → streaming ends, the partial answer stays | `ChatViewModel.kt:95-99,115-123` | Holds. |
| Alt: with no notes, the assistant says so rather than inventing context | `NotesContextBuilder` | Not read in depth on this axis — see Not covered. |
| Errors: 401 / 402 / 429 / network, each named, *Retry* preserves the question | `UiStateMapper.kt:42-51`; `ChatViewModel.kt:42,102-111` re-asks rather than clearing | Holds. |

**Verdict: drifted** (entry gate only).

### SCN-010: Set the API key and the model

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Paste a key → masked immediately | `SettingsScreen.kt:96` | Holds. |
| 2. *Save* → stored encrypted, the field shows the last four only | `SettingsViewModel.kt:126,236-250` → `KeystoreSecureSettings`; field cleared on success only (`SettingsScreen.kt:65`, comment at `:58-59`); tail `SettingsViewModel.kt:103`, shown `SettingsScreen.kt:86` | Holds. |
| 3. Choose a model → used for the next question, the chat header shows its name | chips `SettingsScreen.kt:103-111`; `ChatViewModel.kt:66` re-reads on each ask; header `ChatScreen.kt:50` | Holds. |
| Alt: *Clear* removes the key and the assistant returns to its no-key state | `SettingsScreen.kt:101` → `SettingsViewModel.kt:176-181` | Holds. |
| Errors: secure storage unavailable → the message says the key was not saved and the field keeps its content | `SettingsViewModel.kt:244-247`; the field is cleared only on `savedTick` (`SettingsScreen.kt:65`) | Holds. A corrupted-key state is handled too (`SettingsViewModel.kt:88-93`, `SettingsScreen.kt:85`), which the scenario does not name. |

**Verdict: holds.**

### SCN-011: Take the notes as files

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Save a note → Markdown appears under `notes/YYYY/MM/` with front-matter (id, tags, timestamps) | `VaultMirror.kt:35-44,46-57`; `Vault.kt:52,83-92`; `MarkdownSerializer` | Holds. |
| 2. Save a voice note → the audio sits beside it, named from the same id | `Vault.kt:54,56-81` (adopt = move, not copy), `Vault.kt:87-93` | Holds. |
| 3. Read the vault path in settings and copy the files off | `SettingsScreen.kt:256-259` (tap to copy), hint `strings.xml:86` | Holds. **Note:** the root is `filesDir/vault` (`Graph.kt:47`) — app-private, so "copy the files off the device" means `adb`/MTP access to app storage, which the hint does not say. |
| Alt: deleting a note removes its Markdown **and audio** in the same action | `Vault.kt:110-114` (both `.md` and `.wav` candidates, plus an id walk for older layouts) | Holds. |
| Errors: mirror write fails → the note is still saved, the failure shows once with *Retry*, settings marks the vault out of sync until it succeeds | per-note failures `VaultMirror.kt:29-33,52-56`; retry `:72-75`; shown `SettingsScreen.kt:260-270` | Holds. **Drift:** "shown once" — it is shown only in Settings, never where the note was written. |

**Verdict: holds.**

### SCN-012: Move to the space and back

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Tap *Space* → an immersive view starts showing the same notes over passthrough | `TodayScreen.kt:129-134` → `PanelActivity.kt:32-37`; `ImmersiveActivity.kt:93-125`, passthrough `:129`, entity created `:135-142`, placed from the viewer's own pose `:189-202`; manifest `AndroidManifest.xml:59-71` with the hand-tracking line at `:15-16` | Holds. One Compose tree in both hosts (`FabricApp.kt:33-37`), which is what makes "the same notes" true rather than a second implementation. |
| 2. Open a note there → it behaves as it does in the panel | same tree; owners supplied at `ImmersiveActivity.kt:107-112` / `SpatialPanelOwners.kt:31-57` | Holds structurally. **Unverified:** text entry inside the Space — there is no keyboard handling anywhere in the app (grep for `keyboard` finds only two `configChanges` attributes), and every write path except dictation needs a text field. See D-19. |
| 3. Tap *Back to panel* → the shell reopens the 2D panel **with the same note still open** | `TodayScreen.kt:135-137` → `ImmersiveActivity.returnToPanel()` `:146-163` (PendingIntent into Home, the documented hybrid path) | **State MISSING.** Each host builds its own `NavHost` (`FabricApp.kt:40-41`) over its own `ViewModelStore` (`SpatialPanelOwners.kt:40`), always starting at `Routes.TODAY`. Entering the Space with a note open lands on Today; returning lands on whatever the panel happened to be showing. Nothing is lost from the database, but "the same note still open" is false in both directions. |
| Errors: the immersive activity cannot start → the person stays in the panel, the message names the failure with *Retry* | guarded `PanelActivity.kt:35-36`; `NotesViewModel.spaceFailed` `:143-148`; banner `TodayScreen.kt:141-152` | **Drift:** the banner's *Retry* routes to `notesViewModel.retry()` (`TodayScreen.kt:147`), which re-reads the note list. It does not try the Space again. `spaceStarting` (`NotesViewModel.kt:61,141`) is set and never rendered; `state_space_starting` (`strings.xml:73`) is orphaned. |

**Verdict: drifted.**

### SCN-013: Refuse the microphone, then change my mind

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Press *Record* → **the system permission prompt appears** | `VoiceViewModel.start()` `:75-79` sets `NeedsPermission` and returns | **Drift:** no prompt on the first press. The screen shows "Dictation needs the microphone." (`TodayScreen.kt:294-298`) and the button relabels to *Allow the microphone* (`:336`). The OS prompt appears on the **second** press (`:316`). One extra tap before anything can happen — on the very first interaction of a fresh install. |
| 2. Deny it → the screen explains and offers *Grant* and *Write instead*; no recording attempted | `VoiceViewModel.onPermissionResult` `:143-151` → `NeedsPermission` again | *Grant* holds (the same button). ***Write instead* MISSING** — there is no such control anywhere and `action_write_instead` (`strings.xml:28`) is orphaned; with SCN-001's *New note* also gone, the "write instead" exit does not exist at all. |
| 3. Tap *Grant* and allow → **the recording starts immediately, without re-pressing *Record*** | `VoiceViewModel.kt:145` → `VoiceState.Allowed` | **Broken.** `VoiceState.Allowed` (`VoiceViewModel.kt:30`) is handled by no branch in `RecordControl` — it falls to `else -> Unit` (`TodayScreen.kt:306`) and to `else -> onStart()` (`:318`) with the label *Record* (`:337`). So grant produces a silent state change and a **third** press is required. `state_allowed` (`strings.xml:69`) is orphaned. |
| Alt: permanently denied → the sheet offers *Open app settings* instead of *Grant* | `VoiceViewModel.kt:146-148` → `UiStateMapper.kt:16` → `UiAction.OPEN_APP_SETTINGS`, button labelled "App settings" (`core-common/…/strings.xml:32`) | **Broken — a dead end.** `TodayScreen.kt:287` maps `OPEN_APP_SETTINGS` to `onOpenSettings()`, the app's **own** Settings screen. `SettingsScreen.kt` has no microphone row and no route to `ACTION_APPLICATION_DETAILS_SETTINGS`. Grep over `app/src/main` finds no such intent. A person who hard-refuses the microphone can never dictate again from inside this app, in either host. The chat screen makes the same mapping (`ChatScreen.kt:58`). |

**Verdict: broken.**

### SCN-014: Choose where speech is transcribed, and with which model

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Pick *On this headset* / *Cloud service* / *My whisper server*; the line below states what that choice does with the recording | chips `SettingsScreen.kt:131-139`; the line `:140-144` from `SpeechChoice.kt:34-40` → `strings.xml:130-132` | Holds, and the wording is exactly the scenario's intent. |
| 2. *Cloud service* reveals address, key and model; any OpenAI-compatible endpoint; key in the Keystore, shown as last four | `SettingsScreen.kt:197-236`; tail `:220-222`; `CloudTranscriptionClient.kt:24-34` | Holds. |
| 3. *Model on this headset* — five by name and size; choosing does **not** download; the line says present/absent and offers the download | chips `SettingsScreen.kt:162-170`, names+sizes `strings.xml:121-125`; the no-download rule is stated and implemented at `SettingsViewModel.kt:133-138`; status `SettingsScreen.kt:171-195` | Holds. |
| 4. *Language* — detect automatically or pin one | `SettingsScreen.kt:147-158` | Holds. |
| Alt: the chosen model is absent when a dictation starts → the download is offered rather than failing | `VoiceViewModel.kt:80-83` → `ModelMissing` → `DOWNLOAD_MODEL` (`UiStateMapper.kt:21-22`, `TodayScreen.kt:286`) | Holds. |
| Errors: a cleartext address on the open internet is refused **before any audio leaves the headset**, with the reason | `SettingsViewModel.kt:141-154,161-174` → `NetworkPolicy.requireReachable` (`NetworkPolicy.kt:26-45`); belt-and-braces in the client constructor (`CloudTranscriptionClient.kt:44-46`) | Holds — one of the strongest parts of the code. |
| Errors: **a cloud provider with no key reports that rather than silently falling back** | `Graph.sttEngine()` `:85-101` | **Broken, and this is the scenario's whole point.** With `provider == CLOUD` and a blank key (or blank address), the `when` at `:89-98` yields `remote = null`, and `SttRouter(local, remote = null)` (`:100`) runs the **local** engine with `SttSource.LOCAL` (`SttRouter.kt:31-35`, `WhisperEngine.kt:65`). Nothing is reported. The same hole exists for `provider == SERVER` with a blank URL. |

**Verdict: broken.**

### SCN-015: Delete a note by accident and put it back

| Step | Code (file:line) or MISSING | Note |
|---|---|---|
| 1. Tap *Delete* → it leaves the list at once and a line names it, with *Undo* | `TodayScreen.kt:211` → `NotesViewModel.delete()` `:220-227`; line + button `TodayScreen.kt:177-195`; strings `strings.xml:144-145` | Holds. No confirmation dialog, as designed. |
| 2. Tap *Undo* → the note returns **with its id, its timestamps and its vault file** | `NotesViewModel.undoDelete()` `:230-238` | **Id** holds. **Timestamps: MISSING** — `repository.upsert` unconditionally rewrites `updatedAt = now()` (`NotesRepository.kt:89-92`), so an undone note jumps to the top of an `updatedAt DESC` list and its history is rewritten. **Vault file: half.** The `.md` is rewritten by the mirror (`VaultMirror.kt:39`), but the `.wav` was deleted by `Vault.remove` (`Vault.kt:110-114`) and `Vault.write` re-copies audio only `if (from.isFile)` (`Vault.kt:87-92`) — the source no longer exists. The note comes back with a dangling `audioPath`, which still satisfies `note.audioPath != null` at `TodayScreen.kt:406`, so *Transcribe again* is offered and fails on `File(path).readBytes()` (`NotesViewModel.kt:180`). |
| Layout: *Delete* with *Transcribe again* on the left, *Copy* alone on the right, a gap away; 72dp / 72dp | `TodayScreen.kt:405-455`; `Tokens.kt:43,50-51` (`controlHeight` 72dp, Copy 148×96dp) | Holds. |
| Alt: doing nothing → the line goes when the next note is deleted **or the screen is left** | `clearJustDeleted()` (`NotesViewModel.kt:240`) has no caller | **Drift:** it is replaced on the next delete (`:223`) but never cleared on leaving. `NotesViewModel` is scoped to the Today back-stack entry, so a trip to Search and back still shows "Deleted …" with a live *Undo*. |
| Errors: delete fails → the note stays and the banner says why | `NotesViewModel.kt:224` | Holds. |
| Errors: undo fails → the banner says why and the note is still in hand | `NotesViewModel.kt:232,234-236` | **Broken:** `justDeleted` is set to `null` **before** the upsert is attempted (`:232`), so a failed undo loses the only copy of the note and there is nothing left to try again with. |

**Verdict: drifted** (with one broken clause).

---

## First run on a fresh headset

Fresh install, no microphone permission, no model, no key, `SttProvider.DEFAULT = LOCAL`
(`SttProvider.kt:23`), `WhisperModel.DEFAULT = SMALL` / 190 MB (`WhisperModel.kt:34`).

**The trace, tap by tap:**

1. **Launch.** `PanelActivity` → `FabricApp` → `TodayScreen` (`FabricApp.kt:41-52`).
   The screen shows: the words "Fabric VR"; four icon buttons with no labels (search, a
   **microphone** glyph that means Assistant, a cube, a cog — `TodayScreen.kt:127-138`); a
   128dp button reading **Record**; and one line, "Nothing here yet. Hold Record and say
   something, or start a note." (`today_empty`, `strings.xml:102`).
   Two things are already wrong. The empty state says **"Hold"** and the button is press-to-toggle.
   It says "or start a note" and there is no control that starts one. Meanwhile
   `loadDailyNote()` has silently created a note titled `2026-09-20`, which is not in the list
   yet because the list renders `today_empty` whenever `state.notes.isEmpty()` — it will appear
   on the next emission as an unexplained row bearing today's date.
2. **Tap 1 — Record.** `start()` finds no permission (`VoiceViewModel.kt:75-78`). No system
   prompt. The line above the button becomes "Dictation needs the microphone."
   (`TodayScreen.kt:294-298`) and the button becomes **Allow the microphone** (`:336`).
3. **Tap 2 — Allow the microphone.** Now the OS dialog appears (`TodayScreen.kt:316` →
   `PermissionRequester` → `PanelActivity.kt:22-25`). The person allows.
4. **State: `Allowed`.** Nothing visible happens. The warning line disappears (`Allowed` falls
   through `else -> Unit` at `TodayScreen.kt:306`) and the button silently reverts to **Record**.
   The contract says the recording starts here (SCN-013 step 3). It does not.
5. **Tap 3 — Record.** Permission passes; `modelMissing()` is true and the server URL is blank,
   so `start()` returns `Failed(ModelMissing)` without recording (`VoiceViewModel.kt:80-83`).
   The banner reads **"The speech model isn't on this headset yet."** with two controls:
   **Download** and a close ✕ (`ErrorBanner`, `Components.kt:54-59`).
   No model name. No "190 MB". No "text notes work meanwhile". No indication that the next tap
   commits the headset to a 190 MB transfer over Wi-Fi.
6. **Tap 4 — Download.** `downloadModel()` (`TodayScreen.kt:286` → `VoiceViewModel.kt:153-169`).
   The button reads "Getting the speech model…" and is disabled (`:310,334`); above it a bar and
   "0 / 190 MB" with a *Cancel* (`TodayScreen.kt:265-279`). This is honest and legible. It is also
   the first moment in the session where the person learns the size.
7. **Download completes.** `DownloadProgress.Done` → `VoiceState.Idle` (`VoiceViewModel.kt:160`).
   The progress disappears; the button reads **Record** again; **nothing says the model arrived or
   was verified.** The person's only evidence that four minutes of waiting worked is the absence
   of the bar.
8. **Tap 5 — Record.** Recording starts at last. Meter and seconds appear (`TodayScreen.kt:253-263`).
9. **Tap 6 — Stop recording.** "Transcribing…", disabled. On the small model at the measured
   1.31× real time, a 40-second thought costs ~52 s here with no progress, no elapsed count and
   no way to cancel.
10. **Done.** The note commits itself, the clipboard is set, "Saved, and copied. Paste it
    anywhere." shows for 2.5 s (`TodayScreen.kt:102-108,169-175`). The note appears in the list
    with `engine · language` beneath it, a *Transcribe again* menu, *Delete*, and *Copy*.

**Six taps and one 190 MB download to the first dictation.** Three of the six taps
(2, 4, 5) are the contract's; taps 1→2 and 4→5 are extra steps the contract does not have, and
tap 3 is pure waste caused by the unhandled `Allowed` state.

**Findings from the trace:**

- The only two sentences the person reads before the download — `today_empty` and
  `error_model_missing` — are both wrong or empty of the facts that matter. One tells them to
  *hold* a button that toggles; the other omits the model, the size and the fact that the app is
  still useful without it.
- Nothing on the first-run path mentions that text notes work. With *New note* deleted, they
  effectively do not: the only writable note is the daily row, which looks like a date and
  explains nothing.
- A person who denies the microphone once lands back where they started with a warning line; a
  person who denies it twice (Android's "don't ask again") is routed by "App settings" into
  this app's own settings screen, which cannot grant anything. That is the end of dictation for
  that install.
- Nothing needs to be read from a document to reach a first dictation — that much is true. But
  the route is discovered by pressing the same button five times and watching what it says,
  which is discovery by repetition rather than by design.

---

## Findings

| ID | Sev | Scenario(s) | Where (file:line) | Claim | Failure scenario | Proposed fix | Verify by |
|---|---|---|---|---|---|---|---|
| D-01 | Blocker | SCN-003 | `TodayScreen.kt:197-218`; state at `NotesViewModel.kt:57,113,130` | Tag chips and tag filtering have no interface in either host; the view model side is complete and unreachable | Person types `#idea` in three notes, returns to Today, and has no way to filter by it. Steps 2-4 of SCN-003 cannot be performed at all | Render `state.tags` as a `FilterChip` row above the list, wired to `selectTag`, selected from `state.selectedTag` | A UI test that types `#idea`, returns to Today, taps the chip and asserts the list narrows, then taps again and asserts it widens |
| D-02 | Blocker | SCN-013 (alt) | `TodayScreen.kt:287`, `ChatScreen.kt:58`; mapping `UiStateMapper.kt:16` | `UiAction.OPEN_APP_SETTINGS` opens the app's own Settings screen, which cannot grant a permission | Person hard-denies the microphone, taps the button labelled "App settings", lands on a screen of API keys and vault paths, and can never dictate again on that install | Route `OPEN_APP_SETTINGS` to `Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))`; it must be host-supplied, since `ImmersiveActivity` is not a `ComponentActivity` | Deny twice on a device, tap the button, assert the OS app-info page opens; assert the same from inside the Space |
| D-03 | Blocker | SCN-014 | `Graph.kt:85-101` | Choosing *Cloud service* (or *My whisper server*) with no key/URL silently transcribes on the device and reports nothing — the exact failure SCN-014's error clause forbids | Person selects Cloud, forgets the key, dictates, gets a transcript, and believes it came from the cloud model they chose. Also: a person who *wanted* local privacy cannot tell the two apart afterwards | Return `FailingEngine(AppError.NoApiKey)` / `RemoteStt(0, "no server configured")` as the remote rather than `null`, so `SttRouter` records a real `fallbackReason` and the UI can say it | `SttRouterTest` case: CLOUD + blank key → the transcript carries `LOCAL_FALLBACK` and a non-null `fallbackReason`; a UI assertion that the note row says so |
| D-04 | High | SCN-013 | `VoiceViewModel.kt:30,145`; `TodayScreen.kt:306,318,337` | `VoiceState.Allowed` is handled by no branch; granting the microphone produces a silent no-op and a third press is required | First run: grant the microphone, watch nothing happen, press *Record* again. Contract says "the recording starts immediately, without re-pressing *Record*" | Either drop `Allowed` and call `start()` from `onPermissionResult(granted = true)`, or handle it in `RecordControl` and auto-start | `VoiceViewModelStateTest`: `onPermissionResult(true, false)` leaves the state in `Recording`, not `Allowed` |
| D-05 | High | SCN-004, SCN-006, SCN-007 | `TodayScreen.kt:288,291` vs `VoiceViewModel.kt:178-183` | The *Retry* button on the voice banner calls `cancel()`, which clears the message and **deletes the recording** (`VoiceViewModel.kt:201`); `retryLast()` exists and is never called | Transcription fails on a 3-minute thought; the banner says "Couldn't transcribe that. The recording is kept."; the person taps *Retry*; the recording is deleted and nothing is retried. The message is a lie written by the button beside it | Wire `UiAction.RETRY` to `voiceViewModel.retryLast()`; keep `cancel()` behind the ✕ only | Unit test: `Failed` → `retryLast()` re-enters `Transcribing` with `lastPcm` intact; `cancel()` alone deletes the wav |
| D-06 | High | SCN-004 | `VoiceViewModel.kt:139,196-204`; `TodayScreen.kt:281-292` | On an engine failure the audio is **not** offered as a note without a transcript, as the scenario promises — the only exit destroys it | The whisper context fails to load after a long dictation; the words are gone and so is the recording | On `Failed` after a successful capture, offer *Keep as a note* that calls `createVoiceNote` with an empty transcript and the kept `audioPath` | A test that fails the engine and asserts a note exists afterwards with `audioPath != null` and `transcript == null` |
| D-07 | High | SCN-006 | `UiStateMapper.kt:21-22`; `core-common/…/strings.xml:10` | The model prompt names neither the model nor its size nor that text notes work meanwhile; one tap then starts a 190 MB download | On metered or slow headset Wi-Fi, a person taps *Download* without being told what it costs. `AppError.ModelMissing` carries the name and the mapper drops it | Pass `error.model` and the megabytes into a format string; add the "text notes work meanwhile" line beside the button | `UiStateMapperTest` asserting the args reach the message; a screenshot of the first-run banner |
| D-08 | High | SCN-001 | `TodayScreen.kt:117-139`; dead code `NotesViewModel.kt:150`; stale test `PanelSmokeTest.kt:21` | *New note* does not exist; the scenario's only entry point is gone and its "write instead" role in SCN-013 with it | A person who refuses the microphone is told nothing about writing and has no button that starts a note. `PanelSmokeTest` asserts both "Hold to record" and "New note" and would fail on this commit | Restore a *New note* action calling `createNote()` then navigating to the editor — or rewrite SCN-001, SCN-013 and the `today_empty` copy to match a dictation-only product. Fix the smoke test either way | `PanelSmokeTest` green against the strings actually rendered |
| D-09 | High | SCN-015 | `NotesViewModel.kt:230-238`; `Vault.kt:110-114,87-92`; `NotesRepository.kt:89-92` | Undo restores the row and the Markdown but **not the audio**, and rewrites `updatedAt`; the restored note keeps a dangling `audioPath` that still shows *Transcribe again* | Delete a voice note by accident, undo, then tap *Transcribe again* → `FileNotFoundException` surfaces as a storage error. The recording is gone for good | Hold the audio bytes (or defer the vault delete behind the undo window) and restore them in `undoDelete`; preserve `updatedAt` on an undo upsert | `VaultMirrorTest` + a view-model test: delete → undo → the `.wav` exists and `updatedAt` is unchanged |
| D-10 | High | SCN-014, SCN-005 | `Graph.kt:105-117` (`?: CloudTranscriptionClient.SUGGESTED_BASE_URL`, `CloudTranscriptionClient.kt:131` = `https://api.groq.com/openai`) | *Transcribe again → Cloud service* with a key saved but **no address** posts the recording to Groq — an endpoint the person never named | The person set a key once while exploring, later re-runs an old recording "with the better model", and the audio leaves the headset to a third party they did not choose. This is the privacy gap the whole provider chip exists to close | Require an explicit saved address: return `FailingEngine(AppError.NoApiKey)` (or a new `NoEndpoint`) when `KEY_CLOUD_STT_URL` is blank, rather than defaulting | A test asserting `withEngine(CLOUD, …)` with a blank URL never constructs a client; `CloudTranscriptionClientTest` for the refusal path |
| D-11 | High | SCN-015 | `NotesViewModel.kt:232` | `justDeleted` is cleared **before** the restoring upsert is attempted, so a failed undo loses the note entirely — the opposite of the scenario's "the note is still in hand" | Storage is momentarily unavailable; undo fails; the banner says why and there is nothing left to press | Clear `justDeleted` only inside `onSuccess` | Unit test: an upsert that fails leaves `justDeleted` non-null |
| D-12 | Medium | SCN-002 | `NotesViewModel.kt:59-60,119-128`; `TodayScreen.kt:122-126` | Neither the date header nor today's card is rendered; `state.today`, `state.dayLabel` and the midnight ticker (`:42-49,92-97`) all feed nothing | The daily note appears as a row titled `2026-09-20` among the others, indistinguishable and unexplained, and sinks below newer notes on any later day | Render the date as the header and today's note as the first card, pinned above the `LazyColumn` | A UI test asserting the first card carries today's date on a fresh day and after a dictation |
| D-13 | Medium | SCN-005, SCN-015 | `TodayScreen.kt:212-214` discards `RetranscribeResult` (`NotesViewModel.kt:52,207`) | After *Transcribe again*, the screen says nothing — and when the body was left alone because it had been edited, the person sees an apparent no-op | Person fixes a misheard word by hand, re-runs with a bigger model, and concludes the feature is broken. `state_retranscribed` / `state_retranscribed_kept` (`strings.xml:110-111`) are written and orphaned | Surface the result as a short confirmation line, reusing the two existing strings | A UI test for the `TranscriptOnly` branch asserting the "your edited text was kept" line appears |
| D-14 | Medium | SCN-007 | `TodayScreen.kt:395-403`; `NoteEditorScreen.kt:160-169`; `Note.kt:19` | The fallback is invisible on the list, printed as a raw enum (`local_fallback`) in the editor, and `fallbackReason` is read nowhere | The whisper server is down for a week; every note quietly comes from the small local model and nothing ever says so. The three human strings exist (`strings.xml:105-107`) and are orphaned | Render `SttSource` through those strings on both surfaces and show `fallbackReason` as the one-line notice the scenario names | A UI test with a failing remote asserting the note row reads "this headset (fallback)" and carries the server's status |
| D-15 | Medium | SCN-004 (alt) | `VoiceViewModel.kt:97,101-104`; callers `TodayScreen.kt:157`, `NoteEditorScreen.kt:98` | There is no *Cancel* while recording; `stopAndTranscribe(false)` is dead code | A mis-aimed ray starts a recording; the only way out is *Stop recording*, which transcribes it and commits a junk note that must then be deleted | Add a *Cancel* beside (not under) the record button while `Recording`, calling `stopAndTranscribe(false)` | A test that `Recording` → cancel leaves no note and deletes the wav |
| D-16 | Medium | SCN-004, unnamed state | `VoiceViewModel.kt:40` (`Graph.recorder`, a process singleton at `Graph.kt:62`); two independent `viewModel()` instances at `TodayScreen.kt:84` and `NoteEditorScreen.kt:51` | Two `VoiceViewModel`s share one `AudioRecorder`; nothing prevents a second dictation starting while the first is in flight | Start a dictation on Today, navigate into a note mid-transcription, press *Record* there: a second `AudioRecord` opens against the same device, and two `Ready` states race to commit | Make the recorder single-flight (a mutex or a shared capture state in `Graph`), or hoist one `VoiceViewModel` to the activity scope so both screens see one state | A test that `start()` on a second view model while the first is `Recording` returns a busy state rather than opening a device |
| D-17 | Medium | unnamed state | `TodayScreen.kt:309-342`; `VoiceViewModel.kt:107` | `Transcribing` has no progress, no elapsed time and no cancel, for 1.3–3.4× the recording length | A three-minute thought on the medium model is four to ten minutes of a disabled button reading "Transcribing…". A person in a headset will assume the app hung and force-quit it | Show the elapsed transcription time and a *Cancel*; the `viewModelScope` job is already cancellable | A UI test asserting a running counter and a cancel that returns to `Idle` |
| D-18 | Medium | SCN-012, unnamed state | `FabricApp.kt:40-41`; `SpatialPanelOwners.kt:40` | Each host owns its own `NavHost` and `ViewModelStore` and always starts at Today; no state crosses the boundary | Enter the Space with a note open → land on Today. Enter the Space **while transcribing** → the panel's `VoiceViewModel` keeps running in the backgrounded activity and the Space shows an idle *Record*; the note appears in the panel later, in the host the person has left | Hoist the route (and ideally the capture state) into something both hosts read — a saved handle in `Graph`, or the same `ViewModelStore` | A test that opens a note, enters the Space, and asserts the same note is open |
| D-19 | Medium | SCN-001, SCN-003, SCN-010, access principle | no keyboard handling anywhere (`grep -rn keyboard app/src/main` → only two `configChanges` attributes, `AndroidManifest.xml:45,65`) | Text entry inside the Space is unverified. Six of the fifteen scenarios need a text field (title, body, `#tags`, API key, cloud address, search query) | If the Spatial SDK compose panel does not raise the system keyboard, SCN-001, SCN-003, SCN-008, SCN-010, SCN-011 and SCN-014 are all panel-only, and the Space is a viewer rather than a surface — a direct violation of the access principle | Measure first, then fix. If no IME appears, either raise the Horizon system keyboard explicitly or state in `screens.md` that SCR-08 is read-plus-dictate | On a Quest 3: enter the Space, tap the search field, assert an IME appears and text lands. Add it to `ImmersiveLaunchTest` as a second assertion |
| D-20 | Medium | SCN-012 | `TodayScreen.kt:147`; `NotesViewModel.kt:143-148` | The "space could not start" banner's *Retry* re-reads the note list instead of retrying the Space; `spaceStarting` is never rendered | On a headset that refuses the VR activity, the person taps *Retry* repeatedly and the Space never opens, with no sign anything was attempted | Give the banner a dedicated action that re-invokes `onEnterSpace`; render `spaceStarting` with `state_space_starting` (`strings.xml:73`) | A test that forces `spaceFailed` and asserts *Retry* calls the enter lambda again |
| D-21 | Medium | SCN-015 | `NotesViewModel.kt:240` (`clearJustDeleted` has no caller) | The "Deleted …" line with its live *Undo* never clears on leaving the screen, contrary to the alt path | Delete a note, go to Search, come back an hour later, tap the still-present *Undo* by mistake and resurrect something intentionally deleted | Call `clearJustDeleted()` from a `DisposableEffect` on Today, or give the line a timeout like `justCopied`'s | A UI test: delete, navigate away and back, assert the line is gone |
| D-22 | Medium | SCN-005, SCN-007 | `RemoteWhisperClient.kt:51-57,82` | The whisper server is never told the pinned language, and the reported language is the hint echoed back — so the badge reads the literal `auto` | Person pins `ru`, uses their own whisper server, and gets worse recognition than on the headset plus a badge that says `auto` | Send a `language` part when the hint is not `auto` (as `CloudTranscriptionClient.kt:64-67` already does) and prefer the server's reported language | `RemoteWhisperClientUrlTest` extended: the multipart body carries `language=ru`; a recorded response's language wins over the hint |
| D-23 | Medium | SCN-014 (alt), SCN-006 | `VoiceViewModel.kt:80-83` | The "model missing" gate ignores the chosen provider: it checks only the local model and the **server** URL | (a) Provider *Cloud* with a valid key and no local model → recording is refused with "download the speech model", although the cloud would have worked. (b) Provider *Local* with a stale server URL saved → recording proceeds and fails later inside the engine | Gate on the engine the current provider will actually use: ask `Graph.sttProvider()` and check the matching precondition | `VoiceViewModelStateTest` for each of the three providers × model-present/absent |
| D-24 | Low | SCN-011 | `SettingsScreen.kt:260-270` only | The vault out-of-sync count lives in Settings alone; the scenario says the failure "is shown once with *Retry*" where it happened | Notes stop mirroring; the person never opens Settings and never learns their files are not being written | Surface the first mirror failure as a one-shot banner on Today as well | A test asserting a mirror failure reaches the Today banner once |
| D-25 | Low | SCN-004, SCN-005, SCN-006, SCN-013 | `scenarios.md:103,135,161,206`; `flows.md:46-77` | The contract still says "(hold)" as the entry point and FLW-02 still documents hold-to-talk with tap-to-start listed as the **rejected** shape — the opposite of what shipped on 2026-09-19/20 | A reader implementing from the contract rebuilds the gesture that was deliberately removed. The prose in SCN-004 ("Why no hold") already contradicts its own entry line | Rewrite the contract: the code is right. Update the four entry points, FLW-02's task analysis, its mermaid graph and its rejected-shape note | `grep -n "hold" docs/ux/*.md` returns only the rationale, never an instruction |
| D-26 | Low | SCN-004, SCN-006 | `screens.md:11,15,73-74,113` point SCR-03 and SCR-07 at `ui/VoiceCaptureSheet.kt`, which does not exist at this commit | Two of eight screens in the design map have no code and no replacement; SCR-03's whole premise (a sheet over SCR-01) was deleted | Anyone auditing coverage from `screens.md` follows a dead path and concludes the capture flow is unimplemented | Delete SCR-03, fold SCR-07 into SCR-01/SCR-06 as in-place states, and re-point coverage at `TodayScreen.kt` | Every `Coverage:` path in `screens.md` resolves — a one-line CI check |
| D-27 | Low | all | `strings.xml:22,26,28,29,30,42,46,47,51,63,64,65,69,73,92,93,94,105,106,107,110,111,115` | 23 user-facing strings are written and rendered nowhere, including every word the contract needs: *Write instead*, *Try again*, *New note*, "Today's note", "Allowed. …", the three `stt_source_*`, both `state_retranscribed*` | The strings file reads as the contract fulfilled; the screens are the contract half-built. A copy or translation pass would spend its budget on dead text | Wire them (most are named in D-01…D-14) or delete them in the same change as the scenario rewrite | `./gradlew lintDebug` with `UnusedResources` on, or a script diffing `strings.xml` names against `R.string.` references |
| D-28 | Low | SCN-014 | `UiStateMapper.kt:53-54`; `core-common/…/strings.xml:21` | `AppError.NoApiKey` always reads "Add an OpenRouter key to use the assistant" — including on the speech path, where the missing key is the **cloud transcription** key | Person re-runs a recording with *Cloud service*, no speech key saved, and is told to add an OpenRouter key for the assistant. They add the wrong key to the wrong field | Give `NoApiKey` a `what` field the way `Permission` has, and two strings | `UiStateMapperTest` asserting distinct resources for the two callers |
| D-29 | Low | SCN-009 | `TodayScreen.kt:128` | The Assistant toolbar button uses `Icons.Filled.Mic`, on a screen whose primary action is the microphone | Person looking for dictation taps the microphone glyph and lands in a chat | Use a chat or sparkle glyph | A screenshot review; the icon names are the assertion |
| D-30 | Low | SCN-008 | `SearchScreen.kt:93-96` | The result shows the whole transcript truncated to two lines, not "the matching line" | A word matched 400 characters into a five-minute dictation; the row shows the opening sentence and the person cannot see why it matched | Extract and show the line containing the query | A test asserting the rendered snippet contains the query |

**Counts: 3 Blocker · 8 High · 12 Medium · 7 Low (30 findings).**

---

## Not covered

- **Anything requiring the device.** Nothing was built or run; there is no evidence here about
  what the Space actually renders, whether an IME appears in it (D-19), or how long a
  transcription really takes. Every timing in this report is quoted from the repository's own
  measurement (`WhisperModel.kt:31-33`), not measured by me.
- **`NotesContextBuilder`, `OpenRouterClient`, `SseParser`, `StopWords`** — SCN-009's alt path
  ("with no notes yet, the assistant says so plainly") was not traced into the context builder.
  Axis coverage for the assistant internals belongs to whoever has the seam axis.
- **`MarkdownSerializer` front-matter contents.** SCN-011 step 1 names "id, tags and timestamps";
  I verified that the file is written and where, not the exact front-matter keys.
- **`TagParser` grammar.** SCN-003's alt path (several tags in one body, a duplicate counted once)
  was taken on the strength of `TagParserTest` existing, not read.
- **The Horizon shell's own behaviour** — whether `returnToPanel()`'s
  `extra_launch_in_home_pending_intent` actually re-opens the 2D panel, and whether the shell
  defers the VR launch (the risk `ImmersiveLaunchTest.kt:57-64` was written for).
- **Accessibility.** `SmallAction` supplies a `contentDescription` (`TodayScreen.kt:355`); the
  128dp record button's label is its text; I did not audit the rest. No scenario names it and no
  skill in this family owns it.
- **The clipboard's behaviour across hosts.** `LocalClipboardManager` inside a Spatial SDK panel
  was not verified; SCN-004's promise ("paste it anywhere") is the product's reason to exist in
  the Space and is unproven there.

## Notes for the merge

- **The three Blockers are three different shapes of the same defect: a complete back end with no
  front.** `selectTag`, `createNote`, `clearJustDeleted`, `retryLast`, `RetranscribeResult`,
  `fallbackReason`, `state.today`, `state.dayLabel`, `state.tags`, `spaceStarting` — ten pieces of
  working, tested, commented logic that no composable calls, plus 23 orphaned strings. The
  2026-09-19/20 rewrite of `TodayScreen` to "one button, no windows" was right about the capture
  flow and took the rest of the screen with it. Whoever merges should decide deliberately which of
  those ten come back and which are deleted, rather than letting them sit as evidence of features
  that do not exist.
- **The contract is stale in one direction and the code is wrong in the other, and they should not
  be reconciled by picking a side per scenario.** The capture *gesture* — one button, no sheet, no
  hold, self-committing, auto-copied — is the code's to keep and the contract's to catch up with
  (D-25, D-26). The *promises around it* — the recording is kept when the engine fails, a
  fallback is visible, a cloud provider with no key says so, a permission refusal has a way out —
  are the contract's to keep and the code's to catch up with (D-03, D-05, D-06, D-02).
- **Three findings are safety-shaped, not usability-shaped**, and should not be traded against
  polish: D-03 (a chosen cloud provider silently becomes local), D-10 (an unnamed cloud endpoint
  receives a recording by default), D-05/D-06 (the button that promises to keep a recording
  deletes it). The `NetworkPolicy` / Keystore / checksum work in this repo is genuinely careful;
  these three undo part of it at the seams.
- **The access principle holds structurally and is unproven behaviourally.** One Compose tree,
  both hosts, no second implementation (`FabricApp.kt:33-37`), and `SpatialPanelOwners` is an
  honest fix for a real crash. But every scenario that needs a keyboard is untested in the Space
  (D-19), and no scenario carries state across the boundary (D-18). If the IME does not appear,
  SCN-001, SCN-003, SCN-008, SCN-010, SCN-011 and SCN-014 are panel-only — which would make
  D-19 the largest finding in this report. **Measure it before merging anything else.**
- **`PanelSmokeTest` fails at this commit** (`PanelSmokeTest.kt:20-21` asserts "Hold to record"
  and "New note", neither of which is rendered). Whatever else is decided, that test is either a
  bug report or a broken test, and the merge should not pass it silently.

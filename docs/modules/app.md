# `:app`

The two surfaces and everything that wires the modules together. Shape decided in `DEC-0002`.

## Owns

- **`PanelActivity`** — the 2D panel. `com.oculus.intent.category.2D` plus a `<layout>` block is
  what makes Horizon OS show it as a window beside other panels, including Meta Virtual Display's
  screens. This is the surface the product exists to be.
- **`ImmersiveActivity`** — an `AppSystemActivity` with `VRFeature` + `ComposeFeature`, one
  `PanelRegistration` at 1024 × 640 dp / 288 dpi (1843 × 1152 px, inside the documented
  2064 × 2208 ceiling), passthrough on. Returning to the shell hands Home a `PendingIntent` —
  starting the panel activity directly would leave the immersive one on top.
- **Leaving the Space is an ordered sequence, not a `finish()`** (`SpaceExit`, `REQ-053`).
  `panelEntity.destroy()` → the Home `PendingIntent` → `finish()`, once however many times the
  control is pressed. Meta's *Known issues* page: "calling `finish()` on an activity with panels
  … can cause a crash (SIGSEGV) inside `libMetaSpatialSDK.so` … Use `panelEntity.destroy()` to
  properly clean up panel resources before ending the activity"; *Spawn and remove 2D panels*
  says never to `finish()` such an activity at all, while Meta's own `HybridSample` does exactly
  that. The trailing `finish()` is kept because `ImmersiveActivity` is `singleTask`: a second
  entry would reuse a live instance without re-running `onSceneReady`, and the panel entity
  destroyed on the way out would never come back. Which shape Horizon OS prefers is a device
  question (`B-115`'s neighbour, deferred by `DEC-0066`).
- **`FabricApp`** — one composable tree used by **both** activities, which is what makes "the same
  notes, in the room" true rather than a second implementation.
- **`FeedbackCues`** — the seam that makes a dictation perceptible without looking at the panel
  (`REQ-062`, audit `M22`). See *Cues* below.
- **`LicencesScreen`** — the notice this build ships, read from an asset generated from the
  repository's `NOTICE` (`REQ-063`, `DEC-0073`, audit `H13`). See *One notice* below.
- **Screens** — Today, Note editor, Search, Assistant chat, Settings, plus the voice capture sheet.
- **`TodayFrame`** — the Today screen's layout, with no view model in it. `TodayScreen` collects
  the state and owns the effects; the frame takes a `NotesUiState`, a `TodayActions` of lambdas,
  and the capture state as a **`() -> VoiceState`** rather than a value (`B-096` — see *The frame*
  below). It is split that way because the screen's two hardest claims are claims about geometry,
  and a stateless composable can be measured on the JVM (`DEC-0043`).
- **View models** — `NotesViewModel`, `EditorViewModel` (auto-save after 600 ms; one pending
  write per editor, and the last one runs on `Graph.scope`),
  `VoiceViewModel` (idle → recording → transcribing → ready, plus the permission, model and
  failure states), `SearchViewModel` (120 ms debounce),
  `SettingsViewModel`.
- **`DictationOutbox`** — where a finished transcript waits for a note (`REQ-046`, `DEC-0068`). Process-wide,
  in `Graph`, and backed by one small file under `filesDir/outbox`, so a dictation survives the
  activity that produced it and — within one recording — the process. See *A dictation survives
  its host* below.
  **The file is written whole, flushed, then renamed.** `persist` cited `Vault.write` and did two
  of its three steps: it wrote the scratch file and renamed it, and never `fsync`ed — so the
  rename could reach the disk before the bytes it commits, in the one class whose whole purpose is
  surviving a hard stop. `flush` is an injected seam, because an `fsync` cannot be observed from a
  JVM test while the order it sits in can.
  **`claim` erases the entry it was given, and not whatever is on disk.** It compare-and-set
  `_pending` to null and then deleted the file outright, so an `offer` landing in that window —
  the next dictation, from the surface just freed — had its file deleted while memory kept the
  entry: in memory and not on disk, which is the one state this class exists to make impossible.
  `erase(entry)` now decodes the file under the same lock `persist` holds and leaves a newer
  dictation's file alone.
  **And since `DEC-0088` the claim erases nothing at all.** `claim` is ownership in memory;
  `bind` writes down which note the dictation is becoming (id, `createdAt`, the audio's new path)
  before that note is written; `settle` erases the file once the note is durable, the dictation
  was discarded, or the note it was appended to was deleted. A restart finds nothing, a bound
  entry whose note exists (settled, not written twice), or a bound entry whose note does not
  (written under the bound id). The editor settles only through a write whose note was read after
  the append — a generation counter, read before the note in `save`, bumped after the state in
  `attachTranscript`.
  **And `offer` writes before it publishes** (`B-245`): published first, a drain could claim,
  commit and settle the entry before `persist` ran, and the write then left an already-written
  dictation for the next launch.
  **And never over another dictation** (`DEC-0095`, `B-255`): a file holding a different id is an
  unsettled debt — most often a failed commit whose *Retry* is still up — so `persist` moves it to
  `dictation.tsv.aside-<nanos>` instead of overwriting it; `restore` queues the asides after the
  main file and each `settle` publishes the next (`DEC-0096`: publishing from `claim` raced two
  commits, and `erase` now removes an entry's aside copies too — it left them, and a restored entry
  became a new note every launch). A commit's success clears only its own debt. The launch sweep
  spares every waiting dictation's recording (`waitingAudio()`). **A commit never stores a path to
  a file that is not there** (`B-256`): a dictation recovered after an editor crash can name a
  scratch file the editor had already moved, and the note used to claim a recording it did not
  have; it is written with its words and no `audioPath`.
- **The editor lists the recordings a note keeps** (`B-254`): `EditorViewModel` reads
  `Vault.recordingsOf` on `io` after the note loads and after a dictation lands in it — not at all
  for a note with no recording — and the screen draws *Recordings (N)* with *Play* per row only when
  there is more than one. `DEC-0098` adds scratch-path recordings, dates earlier files by their
  name stamp, and rejects stale listings even after a refresh with no audio. Playback stops before
  dictation and stays unavailable until recording ends (`RecordingsListTest`, one rendering test).
- **`ArchivePicker`** — choosing an exported zip, hoisted out of the composition for
  `PermissionRequester`'s reason: `ACTION_OPEN_DOCUMENT` returns a result and the Spatial SDK's
  compose panel provides no `ActivityResultRegistryOwner`. `PanelActivity` uses the result
  registry; `ImmersiveActivity` is a plain `android.app.Activity` and uses
  `startActivityForResult`. Both build the intent with `openArchiveIntent()`, so they cannot
  filter differently.
- **`Graph`** — manual DI, and since `DEC-0030` it hands out **uses, not engines**: `withStt { }`
  and `withEngine(provider, model) { }` run the caller's block inside `LocalWhisperOwner`'s mutex.
  The *remote* half is still resolved per call, because the endpoint is a setting the person may
  change at any time; the local whisper context is not — there is one per process and evicting it
  is a decision, not an accident.

## A dictation survives its host (`REQ-046`, audit `H1`)

`VoiceViewModel.stopAndTranscribe` launched the decode on `viewModelScope`.
`ImmersiveActivity.returnToPanel()` ends the activity, which clears the store, which cancels
that scope. `onCleared` then deleted the WAV because the state was `Transcribing` rather than
`Ready`. And the panel the person lands on has a **different** `VoiceViewModel`, so nothing was
waiting for the words on return. Leaving the Space mid-dictation therefore lost both the
transcript and the only recording of it — for the one action this product exists to perform. The
same loss happened on system Back out of the editor, and whenever the headset came off.

What replaces it, in order:

1. The decode runs on `Graph.scope`, not on any screen's.
2. The transcript goes into `DictationOutbox` — **on disk first, then on the screen**. From that
   line the words outlive this view model, this activity and this process.
3. Every `NotesViewModel` drains the outbox. `claim` is a compare-and-set, so the panel's and
   the Space's can both be alive and exactly one writes the note — and the entry stays on disk
   until that note is written (`DEC-0088`).
4. `onCleared` deletes the recording only when nothing is owed: not while `Ready`, not while the
   outbox owns the path, and not while a decode is still reading it. The third was the case the
   audit found.
5. `BackHandler(enabled = recording)` on Today and in the editor, so Back cannot pop a screen
   with the microphone open. It does **not** stop the recording — a Back that silently ended a
   dictation is the same surprise wearing a different hat.

**The editor holds its own.** A dictation made there is appended to the note already open, and
Today's `NotesViewModel` is still alive in the back stack draining the same outbox — so the
editor takes a *hold* on the entry while it is composed and drops it when it is not, including
when its host dies. An entry nobody holds is written as a note of its own. Losing the append is
a smaller harm than losing the words, and the person can see the note.

**Process death: the transcript survives, `Transcript.fallbackReason` does not.** The file
carries the text, language, source, engine and duration; an `AppError` is a tree of throwables
with no serialised form, and the fact that matters — that the words came from the headset after
the chosen provider refused — is already in `Transcript.source`. `Graph.init` restores the entry
before the scratch sweep runs and passes its path to `sweepScratchAudio(spare = …)`.

**`DEC-0034` is technically wrong as written and this change is what corrects it.** It says
leaving the Space is safe "because it transcribes rather than discards" — true of the call,
false of its outcome until now.

### And the clipboard moved with it (`B-212`, `DEC-0012`)

`DEC-0012`'s promise is one sentence: *the text goes to the clipboard as the transcript commits*.
After the outbox, the commit was here and the copy was still in `TodayScreen`, inside
`LaunchedEffect(dictation)` keyed on a `derivedStateOf` over `VoiceState` — so it ran only if a
**recomposition frame** observed `VoiceState.Ready`. Both ends of that window are writes this
module owns and neither of them is a frame: `VoiceViewModel.transcribe` offers the entry to the
outbox and *then* sets `Ready`; `NotesViewModel.drainOutbox` claims it on the next Main dispatch.

**What the measurement found is not what the audit predicted, and the difference is the useful
part.** `DictationOutbox.pending` is a `StateFlow`, and a `StateFlow` that goes
`null → entry → null` while a collector is suspended emits **nothing at all** — the value it
resumes on equals the one it last delivered. `TodayScreen` declares `notesViewModel` before
`voiceViewModel`, so the drain subscribes first, is resumed first, and has nulled the flow before
`VoiceViewModel`'s own collector runs. That collector therefore never fires, and the state stays
`Ready` **for ever**: `pendingId` holding an id nothing holds, and `last` holding an audio path
`Vault.adoptOrKeep` has already moved — so *Discard* was offered for a file that was no longer
there. The copy did happen, by that accident; reverse the two declarations and it stops.

Both halves are fixed by one move. The copy runs in `NotesViewModel.commitDictation`, on the
success arm, where the row is known to exist — behind a `suspend (String) -> Unit` the module
injects (`app/src/main/kotlin/ai/passioncode/fabricvr/ui/Clipboard.kt`), because a view model must not hold `LocalClipboardManager`. And
`VoiceViewModel` stops depending on a transition it may never see: after publishing `Ready` it
posts one check of `outbox.pending.value` and calls `stopShowing(id)` if the entry has gone.

**What it trades away, stated:** a dictation restored from the outbox on the next launch —
process killed mid-dictation — now overwrites the clipboard when it becomes a note, with nobody
having just spoken. That is `DEC-0012`'s own consequence widened, and it is the price of the
promise being kept by the code that writes the row rather than by whichever frame happens to run.
The screen is told what happened through `NotesViewModel.dictationCommitted`, a `replay = 0`
`SharedFlow` carrying `copied` and the transcript's source, so *"Saved, and copied"* reports the
copy that occurred instead of the switch that permits one.

`ClipboardPromiseTest` holds it at the view-model tier (`SI-05`): five cases, one of them the
finding itself — after the drain the state is `Idle` and `hasRecording()` is false.

## Decisions worth keeping

- **Errors are banners where they happened, never toasts.** In a headset a message the person
  looked away from is a message never seen.
- **There is no single `UiAction.RETRY`** (`REQ-047`, audit `H2`/`H3`). One member meant each
  screen answered it with whatever *its* retry happened to be, and on Today that was
  `NotesViewModel.retry()` — reload the list. So *Retry* under **"Couldn't save your dictation"**
  reloaded the list and left the transcript unwritten (the next dictation then overwrote
  `pendingDictation` and its recording was orphaned under an id no row carried), and *Retry*
  under **"The space could not start"** reloaded the list and never tried the Space.
  `NotesViewModel.retryDictation()` had **zero callers** (`DEC-0069`). It is `RETRY_LOAD`, `RETRY_DICTATION`
  and `RETRY_SPACE` now; the view model that raises the message picks, because an `AppError`
  carries no memory of the act that produced it, and every `when` over the enum is exhaustive so
  the compiler names each site that has to decide.
- **A failed download says what the next press will cost** (`REQ-047`, `H8`). *Resume* asks for
  the missing megabytes, *Download again* for all 574. Only the collector of
  `DownloadProgress.Failed` knows which, so the view model swaps the action onto the mapped
  message rather than the mapper guessing from an error that carries no partial file.
- **A silenced microphone is its own state, and it does not say "Nothing heard"** (`REQ-052`,
  `H11`). Meta's *Horizon OS Audio*: when the system takes the microphone "the microphone stream
  isn't closed and is provided empty audio data". `VoiceState.Silenced` keeps the meter and the
  clock — the recording has not stopped — and `VoiceState.isRecording` is what every "is a
  recording running" question asks, so the button, the disabled header and the `BackHandler`
  all treat it as one.
- **The export has a way back in** (`REQ-054`, `H4`). Settings → `ACTION_OPEN_DOCUMENT` →
  `Graph.vaultZipImporter`, which unpacks into the vault and reconciles. `refused > 0` gets its
  own sentence, because a well-formed export produces none: a count above zero means the archive
  came from somewhere else, or an entry tried to write outside the vault.
- **`reload()` may not undo what Settings is in the middle of** (`REQ-067`, `M8`/`M9`). Every
  chip calls `put`, and `put` calls `reload()` on success — which rebuilt the state from a
  whitelist of Keystore reads and dropped `exporting`, so a language tap during a multi-gigabyte
  export re-enabled *Export* and a second press started a **second concurrent archive**. The
  export's four fields, the restore's two and `message` are carried forward now; whoever wants
  the message gone calls `dismissMessage`. A transfer started elsewhere is **collected**, not
  sampled once out of `.value`.
- **Under a third of a second of audio is a mis-tap**, and says "nothing was heard".
- **One button, no windows.** Recording is press-to-start, press-to-stop. There is no hold gesture
  and no sheet: a hold asks a person to keep a controller ray steady on a target for the length of a
  thought, and a sheet is a second window that has to be dismissed — in a headset one that does not
  close is a wall. Every blocking state (microphone, model, failure) is drawn above the same button,
  which therefore never moves. A finished transcript commits itself: the note appears in the list
  and its text is on the clipboard.
- **The Space needs ONE owner the panel does not provide, not four** (`DEC-0028`).
  `AppSystemActivity` is a plain `android.app.Activity`, and navigation asked for an
  `OnBackPressedDispatcherOwner` and crashed the activity on every entry:
  `No OnBackPressedDispatcherOwner was provided via LocalOnBackPressedDispatcherOwner`, from
  `PredictiveBackHandler.kt:137`. The symptom was real; the diagnosis was not. Disassembly of the
  0.14.0 AAR shows `composePanel` calls `attachLifecycleToRootView`, which sets the lifecycle,
  view-model-store and saved-state owners on the view tree — so supplying our own **shadowed
  three working owners with strictly worse ones**: no `onStart`/`onStop`, and a store cleared
  before the composition was disposed. `SpatialBackOwner` is the one genuine gap.
- **The launch is watched, and the watching is the hard part.** `ImmersiveLaunchTest` launches the
  real activity, waits for it to reach RESUMED and then for
  `compositionCount` to **increase**; a compose test cannot stand in for it, because the test
  rule's host is a `ComponentActivity` and supplies from its view tree the very owners whose
  absence crashed the product. The count replaced a boolean because the boolean needed a reset
  and the reset is what fails silently. A shell that never brings the Space forward — Guardian,
  no controllers — is an **assumption**, not a failure: it says nothing about this app.
  Exit reasons moved to a `@Before` tripwire about the *previous* run, because instrumentation
  shares the app's process and a crash there kills the runner rather than being reported.
- **The Space needs a manifest line, not code.** Horizon OS refuses to launch an immersive activity
  when no controllers are paired unless the app declares hand tracking; the shell shows
  `common_system_dialog_app_launch_blocked_controller_required` and nothing of ours runs. Measured
  on a Quest 3 on 2026-09-19.
- **And the hand-tracking line is used, not idle** (`DEC-0067`, refining `DEC-0009`): without
  `oculus.software.handtracking` and `com.oculus.permission.HAND_TRACKING` the Spatial SDK runtime
  enumerates no hand devices, so inside the Space hands work as an input device *because* of the
  declaration (Meta, *Enable Hand Tracking*, fetched 2026-09-21). Nobody has driven the app with
  bare hands yet — `B-175`, deferred to the headset session.
- **The Horizon OS floor is 81** (`DEC-0065`, closing `B-098`): `uses-horizonos-sdk` min = target =
  81, the version Meta Virtual Display needs. `minSdk = 34` alone would have made the floor v76;
  the manifest used to claim 69 and a headset on v69–v75 failed with the installer error *INSTALL_FAILED_OLDER_SDK*.
- **One header action stays live while recording, and it is the exit** (`DEC-0034`). `DEC-0032`
  disables *Search*, *Space* and *Settings* so a dictation cannot outlive the screen showing it;
  *Leave the Space* is exempt because it is the only way out of an immersive surface and no
  controller Back has been observed arriving (`B-115`). Leaving is safe: the `DisposableEffect`
  and `ON_STOP` handler both transcribe rather than discard, so the recording ends with the
  screen instead of being hidden behind it.
- **Back in the Space is an override, not a platform.** `VrActivity.dispatchKeyEvent` (0.14.0, 65
  bytes, disassembled) routes the event to a pinned game controller and returns `true`, or returns
  `false`; it never calls `super`, so `onKeyUp`, `onBackPressed` and the platform's
  `OnBackInvokedDispatcher` are all unreachable and the dispatcher `DEC-0018 (retired, superseded by DEC-0028)` provided was fed by
  nobody. `ImmersiveActivity.dispatchKeyEvent` **is** the back path: `KEYCODE_BACK` + `ACTION_UP` +
  `repeatCount == 0` goes to `SpatialBackOwner`'s dispatcher, whose fallback leaves the Space;
  everything else goes to `super`, preserving the pinned-controller branch — returning `true`
  unconditionally would swallow the volume keys. **The plan said to delete all of this.** An
  `adb shell input keyevent` against a live Space produced no key at all, which reads as "keys do
  not reach an immersive activity"; an injected key goes to the *focused window* and the shell's
  was not landing here. `SpaceBackTest` injects through the instrumentation and watched the key
  arrive (`DEC-0029`). It fails in both directions on purpose, and the pair of plants was watched
  failing on the device. Whether a **physical** controller's B button becomes that key is
  unmeasured and needs a person wearing the headset — `docs/evidence/device-gate.md`, `B-115`.
  The on-screen exit is *Leave the Space* with `ExitToApp`; it shared `ViewInAr` and the word
  "Back" with the control that enters until this change — half of `B-25`; the other half, that the
  five header actions carry no *visible* label at all, is T-030's.
- **`Graph` hands out uses, not engines** (`DEC-0030`). `sttEngine()` returned an `SttRouter`
  holding the process's whisper context, and `localEngine()` closed the previous one — with
  `runBlocking`, `@Synchronized` on the `Graph` object, from whichever thread asked, and every
  caller was on Main. `withStt { }` and `withEngine(provider, model) { }` run the work inside
  `LocalWhisperOwner`'s mutex instead, so no engine reference outlives the lock that built it and
  nothing here constructs a `WhisperEngine` at all. The `@Synchronized`, the `loaded` field and the
  throwaway second engine are gone with it.
- **A recording that never became a note is swept.** `sweepScratchAudio` runs once per launch over
  `filesDir/audio` and deletes files older than a day; `VoiceViewModel.onCleared()` deletes an
  unadopted recording immediately — **except while `VoiceState.Ready`**, which is exactly the
  window in which `commitDictation` is moving the file into the vault on the application scope
  (`I-02`). Deleting there would turn a leak into the data loss `T-007` fixed, and both directions
  are tested. Before this, one writer and one delete-on-discard were the only code that ever
  touched that directory (`C-06`).
- **Which layer owns a dispatcher — R1, R2, R3** (`DEC-0031`). The audit found fourteen places
  where a click handler did a Keystore decrypt, a `stat`, a JSON encode or an HTTP construction on
  `Dispatchers.Main.immediate`. Fourteen patches is not a fix, because the fifteenth is written
  next week by somebody reading the file rather than the audit. The rules are the fix:
  - **R1 — a function that touches the Keystore, the filesystem, JNI or the network is `suspend`
    and switches its own thread.** Its caller needs no knowledge of dispatchers. `FileVault`,
    `AudioRecorder`, `ModelDownloader`, `RemoteWhisperClient` and `CloudTranscriptionClient`
    already obeyed it; `Graph`'s accessors and `OpenRouterClient.stream` now do. The boundary sits
    at `Graph` rather than at `SecureSettings.get` **by decision**: that interface has two
    implementations, six key constants and a caller in another module whose `apiKey: () -> String?`
    parameter is not ours to change.
  - **R2 — a `viewModelScope.launch { … }` body may read a `StateFlow`, assign one, and call
    suspend functions. Anything else it does itself goes in `withContext(io)`,** where `io` is an
    injected `CoroutineDispatcher = Dispatchers.IO`. The parameter is for testability, not taste:
    with the real `Dispatchers.IO`, `advanceUntilIdle()` cannot wait for the work, and five tests
    in `VoiceViewModelTest` went red on exactly that race the moment the reads moved.
  - **R3 — a composable never calls a suspend function that writes.** `LaunchedEffect` and
    `rememberCoroutineScope` observe and invoke non-suspend view-model entry points; the view model
    owns the scope. `TodayScreen.kt`'s dictation commit used to break this and it was `I-02`, a
    data-loss defect with its own mechanism; `T-047` closed it — the effect calls
    `NotesViewModel.commitDictation`, which is not `suspend` and runs on `Graph.scope`, so the
    commit outlives the composition that started it.
    **Three more violations shipped after that sentence was written** (`B-190`, audit 2026-09-21): `createNote` at two call sites and `retranscribe` at one, all on `TodayScreen`'s `rememberCoroutineScope`. They are `NotesViewModel.newNote` and `NotesViewModel.startRetranscribe` now, and `NotesViewModelR3Test` holds both — the double press and the decode that must outlive its screen.

  R1 is the load-bearing one. With it, forgetting a `withContext` in a view model costs nothing,
  because there is nothing left in `Graph` that blocks. `tools/check_main_thread.py` enforces R2 by
  brace-counting launch bodies, and `MainThreadPolicyTest` runs it — **its honest baseline was 1,
  not the eleven the spec predicted**, because `T-017` had already moved every `Graph.` reach into
  a constructor default and the scanner stopped seeing it. The check got weaker at the moment the
  code got better, silently, which is why R1 rather than a longer seam list is the answer.
- **`start()` is asynchronous now**, and that is a behaviour change at the control the person taps
  most. The two reads behind it — `modelStore()`, two `stat` calls, and `remoteReady()`, which
  builds a client and reads the Keystore (`B-111`) — cannot be done on the drawing thread, so the
  state moves a frame later. `RecordStatus` reads `state` as a collected flow and never from this
  call's return, so nothing flickers; that was checked, not assumed.
- **A recording lives in exactly one place.** The recorder writes to the app's private scratch
  because the note does not exist yet; `Vault.adoptAudio` moves it into that note's folder **before**
  the note is saved, so the note records the final path once (`EditorViewModel.attachTranscript`,
  `NotesViewModel.createVoiceNote`). A discarded recording is deleted by `VoiceViewModel.cancel`. When
  the move fails the scratch path is kept and the mirror copies it later — nothing is lost, and the
  note never points at a file that has moved.
- **A view model reaches `Graph` only through its constructor.** `Graph` is an `object` with a
  `lateinit` context: it cannot be built twice and cannot be reset between tests, so a dependency
  taken from inside a method body is one no test can replace. That is why
  `NotesViewModel.retranscribe` — the one method that can overwrite text a person typed — had no
  test for any of its five branches until `T-017`. `scripts/check-seams.sh` enforces the rule
  mechanically: `Graph.` may appear above the line that closes the constructor and nowhere else.
  The seams are `transcribeWith` and `sttLanguage` on `NotesViewModel`, and on `VoiceViewModel`
  `downloads` — the `Downloads` interface, because `ModelDownloads` holds a real scope and builds
  real `ModelDownloader`s, so a screen test asserting *"two presses start one transfer"* would
  otherwise need a `MockWebServer`. `T-021` replaced the old `downloadProgress` stream with it.
- **A dictation's commit outlives the screen that started it.** It ran on the composition's scope:
  the recording moves into the vault before the row is written, so leaving Today in between left
  the audio orphaned under an id nothing carried, and returning re-fired the effect against a
  scratch file that had already gone — a note pointing at nothing (`I-02`). `commitDictation` runs
  on `Graph.scope`, is guarded against committing the same transcript twice, and **releases the
  recording only when the row is actually written** — releasing it after a failed write is how the
  banner came to read "your text is still here" about text that was gone (`A-04`). A failed commit
  is held as `pendingDictation`, so Retry finishes the job with one write and no second move.
- **A permanently denied microphone has a way back.** Once the refusal is hard,
  `requestPermissions` returns immediately with no prompt — the app cannot ask again however it is
  worded, and the only route is the system's own page. Both hosts implement
  `PermissionRequester.openAppSettings()`, which is guarded by `resolveActivity` and **answers
  whether it worked**, so a device with no such page gets a sentence rather than a button that
  does nothing. Measured on a Quest 3: the intent resolves to
  `com.oculus.vrshell.intents.AndroidIntentsRelayActivity`, Meta's relay, so it will not throw.
  `scripts/check-seams.sh` refuses a `when` arm that shares the system page with this app's
  Settings screen — that conflation is `D-02`, and it lost voice notes for anyone who tapped
  *Deny* twice.
- **A crash leaves something the person can hand back.** `FabricVrApp` installs the handler
  **before** `Graph.init`, because a failure inside `Graph.init` — an unreadable Keystore, a
  database that will not open — is exactly the kind that leaves a window closing and nothing to
  show for it. `ApplicationExitInfo` is read once per launch behind a watermark for what the
  handler cannot catch. Settings shows the newest and copies it, and `DEC-0026`'s export carries
  the file off the headset. `DEC-0027`.
- **`MainDispatcherRule`** (`app/src/test/.../MainDispatcherRule.kt`) installs a test dispatcher as
  `Dispatchers.Main` and hands the same one back for `runTest`. A bare `runTest {}` in a class that
  installed a different dispatcher builds a second scheduler nothing pumps, and every assertion
  then reads the state as if the act had never run.
- **Where speech goes is decided once, in `SttRouting.kt`.** `remoteEngineFor(provider, settings)`
  serves both entry points — a live dictation and a re-run of a stored recording — and never
  answers `null` for a provider the person chose: an unconfigured one refuses with
  `AppError.SttNotConfigured`, which the router turns into a visible `LOCAL_FALLBACK`. It is a free
  function over a `SecureSettings` rather than a method on `Graph`, because `Graph` is an `object`
  with a `lateinit` context that no test can construct twice — and that seam is why nobody noticed
  the two rules had drifted apart (`DEC-0024`).
- **`carryOverSettings` runs once per install, off the main thread.** `Graph.init` is called from
  `FabricVrApp.onCreate`; the Keystore's first use generates a key inside a synchronized block, so
  the migration is launched on `Graph.scope` and never awaited.
  **And it reads the `Result` of the write, which it discarded** (audit `2026-09-22`).
  `SecureSettings.put` returns one because a Keystore write genuinely fails — an alias that no
  longer decrypts, StrongBox busy, a device not yet unlocked — so `moved = true` meant *the line
  above was executed*, not *the provider moved*: Settings announced a carry-over while the stored
  provider was still absent and the person's speech was still on the on-device model, which is
  `DEC-0014`'s own silence re-created by the code that exists to end it. On a failed write the
  schema marker is **not** written either, so the next launch tries again — writing it would
  retire the migration for ever on the strength of a write that did not land, and one busy
  Keystore at one launch would cost the person their configured server permanently, with nothing
  to see and nothing to press. A marker that will not write is logged and not fatal: the provider
  is already correct and the only cost is two reads next launch.
  **The same shape is still open one file away:** `SettingsViewModel.saveAudioRetention`
  (`app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsViewModel.kt:777-778`) discards the
  `Result` of its Keystore write, so a retention choice the Keystore refused is reported to the
  person as saved. It is a screen's view model and belongs to whoever owns that file.
- **One pending write per editor, and it reads the note when it runs.** `EditorViewModel.save()`
  takes no argument: a write scheduled 600 ms ago must express the note as it is at the moment of
  writing, not as it was at the moment of scheduling. Taking it by value is how a dictation pressed
  mid-sentence was silently reverted by the keystroke's own autosave — and the mirror copied the
  loss into the person's folder. `edit` debounces on `viewModelScope`; `flush`, `retry` and the
  dictation's adopt-then-write run on `Graph.scope`, which navigation does not cancel. Both callers
  of `flush()` fire it as the screen is going away, and in the Space the activity clears the
  ViewModelStore *before* the composition is disposed, so on `viewModelScope` that write was
  cancelled every time.

- **The way out of app-private storage.** `VaultExport` writes the archive into
  `MediaStore.Downloads` — the only place `adb uninstall` does not delete, and a release signature
  change forces an uninstall. The entry is held pending until the archive is complete, published on
  success and **discarded on failure**, because an archive that looks complete and is not is the
  one a person relies on before reinstalling. The four `MediaStore` calls sit behind `ExportStore`
  so that rule can be tested at all: the platform's content resolver is a final class with final
  methods, so a fake cannot exist and a plain JVM test throws "not mocked" on its first line. A *Send
  it somewhere* button sits beside the path, guarded by `resolveActivity`. `DEC-0026`.
  **A publish that did not take is a failure** (`B-246`): `IS_PENDING = 0` is an `update` whose
  result must be 1, or the zip stays hidden — and eligible for MediaStore's own cleanup — while the
  screen said *Exported*. The row is discarded and the failure is `Storage("export.publish")`.
  **And the store is driven on `io`**, not on the `viewModelScope` thread `run` is called from.

- **The panel's texture and its size in the room are one decision.** `applyPanelConfig` is a
  function rather than a literal block so a test can hand it a `PanelConfigOptions` and read the
  result — the constants were `private const val`, which Kotlin inlines, so nothing could have read
  them. Measured on a Quest 3: the SDK defaults a panel to 1.0 m × 0.75 m, and this app asked for a
  1024×640 dp texture and set no world size at all, so 1.6 was drawn on 1.333 and stretched
  vertically by a fifth (`B-08`). The height is computed from the width and the texture, never
  typed twice.

## The first run, in four presses

The audit's *First run on a fresh headset* walk counted **six presses and a 190 MB download** to
a first saved note. Two of the six were the app arguing with itself:

- Pressing *Record* with no permission set a state, and **the button relabelled itself** to
  *Allow the microphone* in the place just pressed — so the press that reached the system was the
  second one.
- Granting then parked the machine in `VoiceState.Allowed`, **which no screen rendered**. The
  warning vanished, the button silently said *Record* again, and the person pressed it a third
  time to find out what had happened.

`DEC-0044` removes both. `start()` raises `permissionAsks`, a `CONFLATED` `Channel` the hosting
screen collects and turns into a `PermissionRequester` call, so the OS dialog appears on the
**first** press; `onPermissionResult(granted = true)` calls `start()`; `VoiceState.Allowed` is
deleted, which is also what keeps `RecordStatus`'s `when` exhaustive with no `else`.

**The event exists because `ImmersiveActivity` is not a `ComponentActivity`.**
`rememberLauncherForActivityResult` resolves a registry through the view tree and a Spatial SDK
panel's host does not provide one, so the request belongs to whichever activity is hosting and the
view model can only say *when*. A `Channel` rather than a `SharedFlow`: a replayed state would put
a system dialog in front of somebody who came back to the screen without pressing anything.
**Both screens with a *Record* collect it** — Today and the note editor — and leaving the editor
out would have made a first dictation started there ask for nothing and do nothing.

`NeedsPermission` survives as the state after a **refusal**, which is the one moment an
explanation earns its room, and it carries *Ask again* and *Write a note instead*. The second is
honest for the first time since `DEC-0010`, because `T-028` put the text editor back.

Three more silences closed in the same change: the model banner **names the model and its size**
(`AppError.ModelMissing` carried both and the mapper threw them away), a finished download **says
so** instead of the progress bar merely vanishing, and transcription **counts seconds** instead of
showing a disabled button for the ~52 s a forty-second thought costs at the measured 1.31× real
time. A real progress fraction (`B-146`) and a cancel (`B-147`) are findings of their own.

**Four, not three, and the boundary matters.** *Record* — the dialog, allow, and the missing
model refuses before any recording — then *Download*, *Record*, *Stop*: **four presses to a saved
note**, plus the OS dialog's own button. `DEC-0044` said three and it was wrong, in six
documents; `DEC-0046` corrects it. Three is the count to the *start* of the first dictation once
the model is there, which is a different sentence about a different moment.

**What is not proven: the count.** Four is what the arithmetic says and what the tests assert
step by step. Nobody has walked it on a device — the headset has been offline since `eaf0c51` —
and the second half of the check, a person who has not read the spec walking the same route, has
no substitute. `B-148`, which now names the boundary it counts to.

## Which build this is

`DEC-0048`. Four fields are stamped from git at configuration time — `versionCode` from **the
commit's own clock**, `versionName` as `0.1.0+<sha>`, the branch, and **the commit's** timestamp —
and Settings renders all four. Before it, every APK this project had produced read
`Fabric VR 0.1.0 (1)`: the version came from a `-PversionCode` flag whose only caller was CI, so
whether a tester's headset ran a week-old build had no answer short of comparing APK bytes (`H-31`).

**`versionCode` was the commit COUNT until `B-160`, and a count is a property of the branch
rather than of the code** (`DEC-0083`: it is the commit's own committer time in seconds, a clock
that cannot run backwards between two branches of one history). Measured on 2026-09-22: `git rev-list --count` answers **57** on `main`
and **176** on `feat/v1-notes-core`. `main` is the repository's default branch and carries no
project commits (`H-36`), so a fresh clone lands there, builds, and cannot install over what is
on the headset — `INSTALL_FAILED_VERSION_DOWNGRADE`, whose only way through is the uninstall that
takes `filesDir` with it: the vault, the database, every Keystore value and a 574 MB model. Two
feature branches off one base are worse again, because the longer one outranks the newer one in
either direction and always will.

It is now `%ct` — the commit's committer time — in seconds since a fixed project epoch
(`versionEpochSeconds`, 2026-01-01T00:00:00Z). That is a **total order over every commit in every
branch**, fixed by the commit rather than by the build, and needing no second ref and no tracked
file; a rebase, an amend and a cherry-pick each reset it to the moment the new commit was made.
Measured at `f0031db` by building the debug APK and reading the `output-metadata.json`
Gradle writes beside it: **22 814 667**.

Two candidates were refused and the reasons are in `app/build.gradle.kts` beside the code: a
*committed counter bumped by a check* gives two branches off one base the same number, so two
different APKs become indistinguishable and `install -r` succeeds silently — `H-31` again, with a
merge conflict in one line on every rebase; and *`rev-list --count main` plus a branch offset*
needs a local `main` a single-branch checkout does not have, and composes two sequences into one
integer, which is exactly what `DEC-0048`'s Decision 2 refused.

**What it trades:** the number stops being small — `176` becomes about 22.8 million, so nobody
reads it aloud and the short sha in `versionName` is what a person quotes, which it already was.
The space is finite: 2 100 000 000 seconds from that epoch runs out in 2092, and
`VersionCodeTest` asserts the headroom rather than assuming it. A deliberately **backdated**
committer date still walks the number backwards, which is a smaller hazard than switching branch.

**The shallow-clone refusal went with the count that needed it.** `rev-list --count` returns `1`
in a `--depth 1` clone — a plausible wrong answer indistinguishable from a first commit, which
shipped release APKs stamped `versionCode = 1` for eleven commits — so the build used to refuse
one outright. `show -s --format=%ct HEAD` reads the commit object the clone is *for*. Verified
2026-09-22 against a `--depth 1` clone of this worktree: `rev-list --count` said `1` and `%ct`
said `1790040267`, the same value the full checkout gives. CI keeps `fetch-depth: 0` because
`check-docs.sh` resolves SHAs against history; the build stopped depending on it.

`providers.exec`, so the configuration cache survives; every fallback is the word `unknown` rather
than a blank, because a version line that silently omits its provenance reads as an answer without
being one. **One sequence:** CI stopped passing `github.run_number`, since two monotonic sequences
over one field cannot be ordered against each other and Android refuses a downgrade install.

`scripts/install-on-quest.sh` refuses to choose between two connected headsets, prints the serial
and model of the one it wrote to, and **exits non-zero when the installed `versionCode` is not the
APK's** — the retro's *verify against the build that is installed* rule could not be followed while
there was nothing to compare. `scripts/check-installer.sh` runs it against a fake `adb`.

## What leaves the device, said where it happens

`DEC-0047`. Two things leave the headset or the app, and each used to happen silently at the
moment it happened.

**Speech** had three careful disclosure strings and all three lived in Settings, **beside the
choice** — so somebody who picked a cloud service in July pressed Record in September with nothing
on the screen that sends their voice. Today names the destination now, first in the list and
directly under the button, whenever the provider is not `LOCAL`; **nothing at all when it is**,
because an app that announces "nothing is leaving" every time trains people to stop reading.

**The transcript** went to the system clipboard on every dictation, unconditionally. `DEC-0012`
chose that deliberately and it stands — in a headset the point of speaking is usually to paste
somewhere else — but the app was overwriting a cross-app resource nobody offered it, with a notice
that lasted 2.5 s and no way to say no. There is a switch now, default on, and **the confirmation
stops claiming a copy that did not happen**.

Both are read once into state rather than at the moment they are used: they are Keystore decrypts,
and the path between "the words are ready" and "they are on the clipboard" is the one place a
stall is unforgivable. Each read keeps its own answer, so one unreadable setting does not lose the
other three.

**Nothing in that change asks the person to accept anything** — no consent flow, no dialog, no
first-run privacy screen. A first-run screen would also fight `T-031` directly.

**And the first-run sentence itself used to contradict the disclosure above it** (`REQ-061`,
audit `M27`). *"…the first dictation downloads a 190 MB speech model, and after that everything
happens on this headset"* was chosen by whether the model was present, so under `CLOUD` or
`SERVER` the empty state promised privacy one row above the line saying the recording is sent
away — and both of its clauses were false there, because the first dictation uploads rather than
downloads. The provider chooses it now, and the remote variant deliberately says nothing about
the destination: `state_recording_leaves` already names it, and a screen that says it twice
trains people to read neither.

## Cues — the only thing a person can notice without looking (`REQ-062`, audit `M22`)

Grepping this tree for haptics, tone generation and sound pools found **nothing**: record start,
record stop, the ten-minute cap and "saved and copied" were visual only, on a panel beside the
streamed desktop the person is actually looking at.

`FeedbackCues` is one seam with four members (`DEC-0072`) — `RECORD_START`, `RECORD_STOP`, `AUTO_STOP`,
`SAVED` — and two channels. **Audio exists everywhere** (`ToneCueChannel`, a `ToneGenerator` on
`STREAM_NOTIFICATION`): no asset to ship and therefore no new `NOTICE` entry, no decode step
between the state change and the sound, and a volume the headset's own control already governs.
**Haptics exist in the Space and nowhere else**: `spatial.applyHapticFeedback(hand, amplitude,
durationNs, frequency)` on an `AppSystemActivity` is the only Kotlin haptics API Meta documents
(*Inputs and controllers*, fetched 2026-09-21), so `ImmersiveActivity` attaches a channel in
`onSceneReady` and detaches it by identity in `onDestroy`; the panel attaches nothing. Whether
`android.os.Vibrator` reaches a Touch controller from a 2D panel is **UNVERIFIED** and is not
guessed at.

Meta's *Haptics: Best practices* shapes the rest: "Do not just play haptic feedback if there is
no corresponding visual or audio cue to relate it to" — so every cue accompanies a line the
screen already draws, and the haptic is always an addition to the sound rather than a substitute
— and "Make haptic feedback optional and adjustable", which is the one Settings switch over both
channels, read per cue so turning it off is immediate.

`AUTO_STOP` is its own member on purpose: the cap is the app interrupting somebody who is still
speaking (`DEC-0032`), and a cue indistinguishable from the acknowledgement of their own press
would say the opposite of what happened. `SAVED` fires on the commit's success and not on the
composition that draws the confirmation — since `REQ-046` a dictation can be written with no
composition alive at all, and a write that failed is not a save.

**The seam also owns the device's audio around a dictation** (`B-226`), which nothing did.
`grep requestAudioFocus` over this tree returned **nothing**, and Meta documents this exact
failure: *"Entering an immersive experience can cause background audio from other apps to stop…
avoid requesting `AUDIOFOCUS_GAIN` … use `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` … handle
`AudioManager.OnAudioFocusChangeListener`"* — on a product whose whole position is being the
notebook beside a streamed desktop. Three things changed and they are one area rather than three
rows:

- **`AudioFocus`**, a seam with `AndroidAudioFocus` behind it. `TRANSIENT_MAY_DUCK`, taken when
  the microphone opens and given back when it closes, because *transient* means transient;
  `setWillPauseWhenDucked(false)`, because this app's own output is a 90 ms blip; one
  `AudioFocusRequest` instance, because `abandonAudioFocusRequest` must be handed the same object
  that was granted or the focus is never released. The listener has three answers rather than
  one: a permanent `LOSS` stops this app's playback and keeps recording — the microphone is not
  focus-gated and the person's words are the thing being kept — and the transient pair is the
  mixer doing what was asked for, in the other direction.
- **The player is stopped when a recording starts.** A headset's speakers are centimetres from
  its microphone array, so a person who tapped *play* on one note and then held the trigger for
  another was dictating over their own recorded voice, which whisper transcribes as cheerfully as
  the live one because it is the same voice. `Playback` is the seam; `AudioPlayback` lives in
  the screen layer and is owned by the composition that started it, so it is attached rather than called.
- **`ToneGenerator` is released.** It was a `by lazy` with no release path at all — an
  `AudioTrack` outside the Java heap, allocated on the first cue and held until the process died,
  by an app whose own argument for `STREAM_NOTIFICATION` is that it should stay out of the way of
  whatever else is playing. `ToneCueChannel` is `AutoCloseable` now and **re-openable on purpose**:
  the next cue allocates again, which is what lets either host call `releaseAudio()` from
  `onDestroy` without the two having to agree about which of them is last.

**The session work happens BEFORE the person's cue switch is read, and that is deliberate.**
`enabled` is *"do I want to hear a blip"*; it is not *"may other apps keep playing over my open
microphone"*. Reading it first would have made turning cues off silently turn the audio session
off too — a setting nobody offered and nobody could find.

`captureEnded()` is the door for a path that plays no cue: `VoiceViewModel.cancel()` discards the
recording without one, because there is nothing to acknowledge, and the focus still has to go
back or the person's music stays ducked until the next dictation. It is idempotent, so a stop
after a cancel does not abandon twice.

**What is wired and what is not.** `ImmersiveActivity` attaches `AndroidAudioFocus` in
`onSceneReady` (from the **application** context, and never detached — the focus belongs to the
process, because a dictation started in the Space can finish after it has closed) and calls
`releaseAudio()` in `onDestroy`. The remaining three wires belong to files this change does not
own and are listed in the handoff: the panel host doing the same two things, `AudioPlayback`
attaching itself as a `Playback`, and `VoiceViewModel.cancel()` calling `captureEnded()`. Until
that last one lands, a **cancelled** gesture leaves the focus held until the next stop — visible
as other apps staying ducked, and strictly better than the nothing that was there before.

**Nothing here has been heard or felt on a headset** (`B-183`): the tests prove which cue fires,
in what order, and what the seam asked the platform for — which is what a JVM can prove.

## One notice, not two (`REQ-063`, audit `H13`)

whisper.cpp is MIT and its notice has to travel with the binary — a submodule path is not
something a person holding the APK can reach. Settings → *Licences* renders the repository's own
`NOTICE`, and the way it does it is the point: `copyLicenceNotice` in `app/build.gradle.kts`
copies that file into the APK's assets, so there is **one** producer and the text is never
re-typed. `LicencesTest` reads the asset and `../NOTICE` and compares the bytes, so a dependency
change that updates one and not the other is a red test rather than a licence breach nobody
notices.

Two details worth keeping. `assets.srcDir(provider)` does **not** by itself run the copy — a
`Copy` task's `destinationDir` is a plain `File` and carries no task dependency, and the first
run of this failed with `FileNotFoundException`; the dependency is declared on the `preBuild`
anchor, which every variant task including `mergeDebugAssets` is ordered after. And a notice
that cannot be read renders a sentence saying so rather than an empty page: an empty licences
screen reads as "this app uses nothing", which is the one claim it must never make.

## A wait with a shape and an exit (`B-178`, `B-179`)

`WhisperEngine.progressPercent()` has returned 0..100 from whisper's own `progress_callback`
since `DEC-0060`, and nothing drew it. On the `small` model a ten-minute dictation is about
thirteen minutes of *Transcribing…*, and until now that state had no shape and no way out.

**It is a poll, and that is whisper's decision rather than this project's.** `progress_callback`
fires on native worker threads that are not attached to the JVM; delivering from there would
mean attaching a thread per callback, and a callback that throws across the JNI boundary calls
`std::terminate`. So the native side writes an atomic, `Graph.transcriptionProgress()` reads it,
and `VoiceViewModel` decides the cadence — `PROGRESS_POLL_MS`, 500 ms, as a child job of the
decode so it cannot outlive it however the decode ends.

`Graph` reaches the value by holding the `WhisperEngine` that `LocalWhisperOwner.use` hands it,
in a `@Volatile` field cleared in a `finally`. **Not a method on `SttEngine`**: progress belongs
to whisper and to nothing else — a cloud endpoint and a whisper server each return one answer at
the end — so widening the interface would put a method on two implementations that can only ever
answer zero. Zero is also what the screens key on: the bar is drawn only above zero, so a remote
decode keeps the wordless seconds counter rather than gaining a bar pinned at 0 % for its whole
duration.

**The stop reuses `VoiceState.Failed`, and that is a trade rather than a shortcut.** `DEC-0060`
made cancellation reach the blocking native call and chose `CancellationException` so a model
switch abandoning a background run needed no new error shape. A person who pressed something is
owed more: `VoiceViewModel.stopTranscription` leaves `last.pcm` and `last.audioPath` untouched —
which is what makes *Try again* and *Discard* real under the message — and puts
`state_transcription_stopped` on screen. A new `VoiceState` member would have to be answered by
every `when` on two screens, and every answer would be the one this already gives. What it costs
is that a deliberate stop travels in a shape named after failure; the sentence the person reads
calls nothing a failure.

**A run the engine abandons gets the same shape** (`DEC-0092`, `B-251`). A model change closes the
engine a decode is queued on or running in, and the `CancellationException` that reports it
arrived in a coroutine nobody had cancelled — which ended it silently and left *Transcribing…* on
screen for ever. `transcribe` now asks `currentCoroutineContext().ensureActive()`: its own
cancellation propagates, and an abandoned run becomes `Failed(state_transcription_model_changed,
RETRY_LOAD)` with `last` untouched.

## What a dictation owes when part of it fails (`B-091`, `B-092`)

Two degradations that used to be silent, and they are deliberately not the same shape.

**A WAV that could not be written is a notice, not a state.** It was
`runCatching { … }.getOrNull()`: the note was created with `audioPath = null` and nothing was
said, so the person found out months later that one row would not play or re-transcribe. The
decode carries on — the words are the part that cannot be re-made, and abandoning them to report
a lost recording would be the larger loss — and `VoiceViewModel.recordingLost` carries a
`UiMessage` into the pinned notice slot, above the *"Saved, and copied"* line it would otherwise
be read under.

**A decode that failed can hand its recording to a note.** `SCN-004` has always promised that
"the audio is kept and offered as a note without a transcript"; `T-005` built the first half and
the second half existed nowhere, which made the first half worse than useless — `onCleared`
deletes any path nothing owns, so the recording died the moment the person looked away. The
hand-off is two calls in order: `VoiceViewModel.releaseRecordingForNote()` gives up ownership and
returns the path, `NotesViewModel.keepRecordingOnly(path)` adopts it into the vault and writes
the row. It is deliberately **not** `commitDictation` with an empty transcript — a `Transcript`
is a receipt, and inventing a blank one would put a lie in the vault and on the row's
engine·language line.

## The frame, and the arithmetic under it

Four things are fixed chrome and nothing else is: the header, a **status `Box` of
`Tokens.Space.statusSlot` (130 dp)**, the **128 dp record button**, and — since `REQ-060` — a
**pinned notice slot below the button, bounded at `NOTICE_SLOT_MAX` (160 dp)**. The day card,
the tag chips, the notes and the "and N more" row are items in a `LazyColumn` with `weight(1f)`.

**The banner, the confirmations and the Undo row moved out of that list** (`REQ-060`, audit
`M20`). They were its first items, so deleting the thirtieth note put *"Deleted … Undo"* thirty
rows of scroll above where the person was looking, and the 2.5-second confirmations expired
unseen for the same reason. A message nobody can see is the same defect as no message; an
*Undo* that has scrolled away is worse, because the note is already gone.

**`TodayFrame` takes the capture state as `() -> VoiceState`, not as a value** (`B-096`).
`VoiceState.Recording` carries `level` and `samples` and the recorder emits about fifty times a
second, so a value parameter made every audio frame invalidate the frame's own body — and
`TodayScreen` above it, which builds `TodayActions` (twenty-six lambdas capturing two view
models, a clipboard and a playback handle) **inline in the call**. That instance is unequal on
every recomposition, which is what defeats Compose's skipping for the whole subtree beneath it,
`NoteRow` included. `TodayScreen` now holds the `State` and derives the two coarse facts it
needs — `isRecording` and `dictationKey` — through `derivedStateOf`, so a level change is a
comparison rather than a frame; the frame does the same for the header, and the read lands in
`RecordStatus` and `RecordButton`, which are the two composables that genuinely need it.

**The row's stated magnitude did not reproduce, and that is worth recording.** `B-096` says the
read "recomposes the whole screen". Measured on 2026-09-22 with a counting `List<Note>` across
twenty level emissions, with the read eager in the frame's body and again with it deferred: the
note list was touched **0** times either way and the voice state read 60 times either way,
because Compose already skips a child whose parameters are stable and unchanged. The cost is real
one level up, at the `TodayActions` rebuild, and that is what the fix removes.
`TodayVoiceSurfaceTest` keeps the deferral honest as a guard — it cannot show a red against the
old shape, and it says so.

**Below the button, never above it.** Everything above the button is fixed, so the target cannot
move (`B-13`); the notice slot's height costs the list instead. It is emitted only when there is
something in it — `hasNotices` is the predicate, and it is `internal` so the tests that reason
about the budget read the same one the frame does — and it is bounded and scrolls inside itself,
because an unbounded pinned block at the declared minimum would take the list's whole
allocation, which is `B-11`.

**It was four fixed things before this too, briefly and by accident**: the "this headset has no
page where a permission can be turned back on" line was a fixed child and wraps to three lines
on a narrow panel. It is an item in the list.

**Two rules, and every decision in the file follows from them.** Nothing may change the record
button's height allocation — it is the one thing a person aims a controller ray at for the length
of a thought. And everything that can grow lives in the list.

They exist because both were broken at once (`B-11`, `B-13`, `DEC-0043`). The list was the last,
**unweighted** child of a `Column` with no scroll and the three transient rows were its siblings,
so a `Column` measuring children in order handed it **zero** — the notes were not drawn and no
scroll reached them — and the button moved by whatever the current state's message happened to be
tall.

The budget, which is why the day card and the chips are items rather than chrome:

```
 48  padding (Tokens.Space.l, both ends)
 64  header, icons only (Tokens.Space.iconButton)
130  status slot
128  record button
160  notice slot, at its bound (NOTICE_SLOT_MAX)
 64  four 16 dp gaps
----
594  fixed chrome, before one note is drawn
128  the shortest a note row can be (copyButtonHeight + its padding)
----
722  rounded up to 736 = PanelMinimum.HEIGHT_DP
```

A 360 dp panel has 312 dp of content height. `T-030`'s spec kept the day card (≥88) and the chip
row (≥72) above the button as well and still concluded that "at the manifest's minimum this
screen shows one note" — its arithmetic had omitted the button, which is how the declared
minimum came to be 360 while the chrome alone was 418. It became 560, and `REQ-060` raised it to
**736** (`DEC-0070`): the notice slot is what makes *Undo* reachable, and it has to be in the budget or `B-11`
returns the first time a banner and an undo line are up together at the minimum. The manifest's
`defaultHeight` rose with it (640 → 800), because a default below the declared minimum is not a
size the shell can honour — `PanelSizeTest` asserts that too now, and it fired for real on this
change.

**The notice slot is counted at its MAXIMUM, deliberately.** It is zero when there is nothing to
say, so most of the time the list is 160 dp taller than the budget assumes — but the case the
budget exists for is the one where a banner and an undo row are both up, and an uncounted slot
leaves the `LazyColumn` nothing to measure with exactly then.

**What the minimum buys, exactly:** that chrome plus the **shortest a note row can be** — its
Copy button and the row's own padding. A row with a title, a time and an action button is taller,
and at the minimum a person scrolls to reach the rest of it; `TodayFrameTest` measures that
reachability, which is what `REQ-019` claims (`DEC-0046`). `PanelMinimum` in `TodayScreen.kt`
carries the two numbers, and `PanelSizeTest` **derives** the sum from `Tokens.Space` and
`NOTICE_SLOT_MAX` rather than typing it — the first version hard-coded every number, so raising
`statusSlot` to 260 dp left it green while the frame it guards was broken.

**The slot scrolls inside itself.** A failure banner plus *Discard the recording* can outgrow
130 dp, and a fixed box that clips would lose the message. Scrolling inside keeps both properties
at once: nothing is lost, and the button does not move.

**There are no `Popup`s in this product.** A `DropdownMenu` is one — a second window through
`WindowManager` on a `VirtualDisplay` created without `SUPPORTS_TOUCH` or `TRUSTED` — so whether
it draws in the Space, and whether it can be dismissed, is unverified. *Transcribe again* expands
its row into chips instead (`B-09`). The rule is cheap to hold because there is nothing else to
convert.

## The model shelf, and the one rule for emptying it (`B-199`)

`FileModelStore` puts all five whisper models in one directory so switching between them keeps
whatever is already downloaded. That is the right call and it is the whole of this row: the only
removal the app had resolved *the selected* store and deleted that one file, so a person who
tried `large-turbo`, disliked the speed and went back to `small` was left with 574 MB that nothing
listed and nothing could reclaim short of uninstalling. All five together are **1.395 GB**.

`InstalledModels` in `:feature-stt` had done the work since `B-093` — list, total, remove,
remove-all-but-one, counting a model's leftover `.part` — and **no screen called any of it**.
Settings has a *Speech models on this headset* block now, directly under the model chips because
that is where the bytes were spent: a row per model with what it occupies **now** (measured from
the filesystem, not taken from the catalogue) and whether it finished, a total, and *Free up
space*, which keeps the selected model because reclaiming the one the next dictation needs would
cost the person the download again. `Graph.modelRoot` is public for this; it was private, and
`Graph.modelStore(model)` — one model at a time — was the only way in, which is why nothing could
enumerate the directory.

**One rule for every removal: `Downloads.cancel(model)` before the delete.** `InstalledModels`
says so in its own KDoc and cannot enforce it — it is a `File` class with no view of the
transfers — and deleting under a live writer is `M15` from the other side: the downloader is
parked in a blocking read, its `renameTo` lands on a path that has stopped existing, and the
person is told *"There isn't room on this headset"* on a headset with plenty.
`ModelDownloads.cancel` is a `cancelAndJoin`, so when it returns the writer is gone and its
partial with it.

**That replaced a refusal, and the change is deliberate.** `removeModel()` used to answer
`AppError.ModelBusy` while the selected model was transferring. Safe, and the wrong answer:
*Remove*, pressed under the progress bar three rows above it, means "I do not want this", and
being told to wait for 539 MB to finish before it may be deleted is the app arguing with the
person. The deeper reason is that this button and the storage rows perform the same act — two
policies for one act is how they come to disagree. `AppError.ModelBusy` stays defined in
`:core-common` with its string and its mapper case; nothing in `:app` raises it any more.

## A view-model coroutine has an owner for what escapes it (`B-159`)

`viewModelScope` is `SupervisorJob() + Dispatchers.Main.immediate` and carries **no**
`CoroutineExceptionHandler`, so every coroutine launched into it is its own root. An exception
that leaves the body fails no parent, reaches no `fold`, and reaches no person: on the headset it
is a crash with nothing attached, and in the suite it is a red **in the wrong place** — `runTest`
collects an uncaught coroutine exception against whichever test happens to be running.
`SettingsViewModel.reload()` reads four seams in one coroutine from `init`, and a construction
site missing one of them produced four different red cases across five runs, none of them the
test that built the view model.

Thirty-six launches across the four view models now go through `launchGuarded`
(`app/.../ui/Guarded.kt`, `DEC-0084` — `viewModelScope.launch` may appear in that file and
nowhere else under `app/.../ui/`), which catches with `runCatchingCancellable` — cancellation is
rethrown, or leaving a screen mid-write would report *"Couldn't save"* — logs
`vm.launch.escaped`, and hands the failure to the caller's own sink. Settings binds that sink to
its banner: a settings page that renders defaults after a failed read is a page telling the
person their store holds values it does not.

**The same question, one scope up (`B-209`).** `Graph.scope` is `SupervisorJob() +
Dispatchers.Default` and carried no handler either, for the same reason and with a worse blast
radius: the supervisor decides that one failed child does not cancel its siblings and decides
nothing about where that child's exception goes. `VaultMirror`'s collector runs there, and
`RemovalJournal.persist` throws on a full disk — so a person whose storage filled up lost the app
to a failed deletion, with no screen anywhere in the path to say so. `Graph.scopeGuard` is a
`CoroutineExceptionHandler` that logs `app.scope.escaped` and lets the process live. It is
deliberately the twin of `launchGuarded` and it is weaker in one stated way: there is no screen
behind this scope, so the log line is the whole of the honesty available, and the handler does not
make the failed child resume. `GraphScopeGuardTest` asserts both halves — that the handler is in
the context, and that an exception reaching it is named rather than handed to the process.

**The durable half is the gate.** `scripts/check-seams.sh` refuses a bare `viewModelScope.launch`
anywhere under `app/.../ui/` except `Guarded.kt`, with a canary that plants one bare launch, one
wrapper call and two comments and demands exactly one hit; `scripts/selftest.sh` plants the
defect into `VoiceViewModel.kt` and watches the gate refuse it. `launchIn(viewModelScope)` is
deliberately outside the scan — a flow's owner is its `.catch` operator, which is a different
question with a different shape; there is one such call, in `NotesViewModel.kt`, and it carries
one.

### Two holes that rule left, and both were open (`B-214`, `B-215`)

**`.catch` only sees what is above it** (`B-214`). In `NotesViewModel.observe()` it sat one line
*above* the `onEach` that calls `repository.count()` — a suspend query that moved into the flow
after the guard was written. So a SQLite failure in the count walked out of
`launchIn(viewModelScope)` with nothing between it and the uncaught-exception route, while the
screen went on saying it was loading. It is the last operator before `launchIn` now, which is the
only position that makes `DEC-0084`'s exemption true.

**`appScope.launch` is the same root with a different name** (`B-215`). `Graph.scope` is
`SupervisorJob() + Dispatchers.Default` and carries no handler either, so a throw out of one of
its bodies crashes the process exactly as a `viewModelScope` one would. Three of them stood
around calls that throw: `startRetranscribe` (`Graph.sttLanguage` is a Keystore decrypt,
`WavWriter.readPcm` reads a file), `keepRecordingOnly` and `commitDictation` (`Vault.adoptOrKeep`
is a file move); `deleteRecording` and the editor's three make seven. `launchGuarded` has an
**overload** taking the scope — deliberately the same name, because `tools/check_main_thread.py`
matches three spellings and a wrapper called anything else would be a blind spot the day it was
written. That is `DEC-0084`'s own side-finding arriving a second time, and
`tools/check_main_thread.py` now reads **49** view-model coroutines where it read 41 at the
branch point — eight bodies that were invisible to R2's scan are inside it.

**And `committing` is released in a `finally`.** It was set before the launch and cleared in both
arms of the fold, so a throw between them latched it — and the guard at the top of
`commitDictation` then refused every retry of the one dictation that needed one: recording in the
vault, row never written, *Retry* doing nothing at all and saying nothing.

`NotesViewModelEscapeTest` measures all four, with a `CoroutineExceptionHandler` on the test's
application scope so an escape is **seen** rather than argued about. It also took a seam:
`NotesViewModel` now accepts its IO dispatcher, for the reason `VoiceViewModel` already does — a
real `Dispatchers.IO` hop is invisible to a test scheduler, so `advanceUntilIdle` returns while
the work is still on another thread.

## A state holder is updated, never read and written back (`B-211`)

`_state.value = _state.value.copy(…)` is a read, a construction and a write with nothing joining
them, and this module had **84** of them and no `update {}` at all — while `VaultMirror`'s own
KDoc had already written the rule down (`I-06`). `NotesViewModel` alone writes from both
dispatchers: `viewModelScope` is `Dispatchers.Main.immediate`, `Graph.scope` is
`Dispatchers.Default`. The field that loses is the one that carries a recovery control —
`pendingDictation` and the `RETRY_DICTATION` message beside it, which is the offer to write a
dictation whose row would not save. All 84 are `update { it.copy(…) }` now, which is the same
read and write under a compare-and-set that retries.

Measured rather than asserted: two writers meeting at a `CyclicBarrier` inside a refused upsert
lost a field in **17 520 of 20 000 rounds** before the change and 0 after, and one single call
site reverted still lost 14 057. `StateAtomicityTest` carries that race and the gate beside it —
a source scan for `x.value = x.value.copy(`, with a canary over the defect and over the three
shapes that must not red, because the eighty-fifth write is the one nobody re-reads.

**The one-shot events named in the row stay as state, and the reason is a defect this project has
already paid for.** `retranscribedShown()` and `recordingLostShown()` look like events modelled as
state, and a replay-less `Channel`/`SharedFlow` would drop them — which is exactly `B-190`: the
decode runs on `Graph.scope` and its answer must survive a composition that went away while it
ran. `panelShown()` is not an event at all; it is an input, a resume telling the view model that
*"Starting the Space…"* is over. The one genuine event here is the commit, and that is why
`dictationCommitted` is the only `SharedFlow` on this class.

## Nothing outlives a delete in the editor (`B-213`, extending `M1`)

`M1` closed the resurrection for the autosave path: `delete()` cancels `saveJob` and sets
`saved = true`, which disarms the debounce and `flush()`. There is a **third** writer and neither
line reaches it. `attachTranscript` launches adopt-then-`save()` on `Graph.scope` and deliberately
does not assign `saveJob` — the adoption is a file move, and a job cancelled before its first
dispatch never runs its body at all, so cancelling it would strand the recording in a scratch
directory a sweep is about to clear. It also sets `saved = false` a moment before the delete sets
it true. So speaking into a note and then pressing *Delete* — the two controls sit in one `Row`,
`NoteEditorScreen.kt:159-188` — wrote the row back through a `REPLACE` upsert, and `VaultMirror`
copied the resurrection into the person's own folder.

Cancellation is the wrong tool, so the fix is two gates of different kinds:

- `deletedId` — an id, not a flag, so a delete cannot silence a write to a note loaded after it;
  `@Volatile`, because it is set on the caller's thread and read from `Dispatchers.Default`. It is
  checked before the adoption and again at the top of `save()`, which is the one place every
  writer in this class passes through.
- `delete()` **joins** whatever is already in flight before issuing the delete. Nothing recalls a
  statement the repository has already sent, so the only ordering that ends with the note gone is
  write-then-delete.

**And both are put back when the delete fails**, with `saved`: the note still exists, so an editor
that had silently stopped saving would be a worse outcome than the failure that caused it.
`EditorViewModelTest` holds both directions — the in-flight write with the repository's gate held
open, and a refused delete that must leave the editor able to save again.

## Two groups on a note row, and a colour for the one that cannot be undone (`B-154`)

`B-18`'s 72 dp floor reached every control and `ControlFloorTest` holds it. What was left was
about what sits *next to* what:

- **Every wrapping row had zero vertical spacing.** Eleven `FlowRow`s, each declaring
  `horizontalArrangement` and nothing else; the cross-axis default is `Arrangement.Top`. All
  eleven are `FabricWrapRow` now, which spaces both axes.
- **`NoteRow`'s irreversible controls sat 8 dp from its frequent ones.** *Delete* beside
  *Transcribe again*, *Delete recording* beside *Play*. They are two stacked groups with
  `Tokens.Space.l` between them — the same clear air *Copy* has carried since it was written, for
  the same reason read from the other end. Stacked rather than side by side because a horizontal
  gap is whatever room is left after a 148 dp *Copy*, and `PanelMinimum.WIDTH_DP` is 480; and
  because a ray pivots at the wrist, so its drift is mostly horizontal and a horizontal boundary
  is the one it crosses by accident.
- **`Tokens.Palette.danger` is on a control for the first time**, through `FabricDangerButton`.

`DestructiveAffordanceTest` measures the gap at 1024 dp and again at the declared minimum.
**Read its density note before trusting a number from that harness**: `ForcedSize` scales the
density rather than the root, so `compose.density` answers `1.0` while the composition is laid
out at `0.19375`, and the first version of that assertion reported a correct 24 dp layout as
"5 px" — one field over from the text-metric trap `DEC-0043` already records.

## Checks

**342 JVM tests in `:app`**, counted from the tree rather than remembered — an earlier version of
this paragraph said sixteen where `VoiceViewModelTest` had twenty, which is the shape
`evidence-docs` exists to refuse. `scripts/check-docs.sh` recomputes this number and fails on a
stale one (`DEC-0053` §17, watched failing twice — against a drifted number, and against the claim
deleted outright, which is the escape a naive check reads as *nothing to compare*).

**Twenty-three of them arrived with `G-F`** (`REQ-061`, `REQ-062`, `REQ-063`): `CopyTruthTest`
(13 — two of them `B-199`'s, which is why it is no longer eleven: a model row names what it
occupies **now** rather than what the catalogue says it will weigh, an unfinished transfer says
it is one, and *Free up space* is not offered over a shelf holding only the model it would keep)
asserts that the sentences on the screen are true of this build — the delete button names
the set `Vault.sweepAudio` actually removes (`DEC-0074`, correcting `DEC-0038`), the retention row says nothing runs on its own, the
first-run line is chosen by the **provider** rather than by the model, the transcript badge
renders sentences instead of `local_fallback`, the whisper server has one name and the Space is
capitalised; `FeedbackCuesTest` (14 — four for the cue seam and ten for `B-226`'s audio session, below) and `DictationCuesTest` (4) cover the cue seam and its four
callers; `LicencesTest` (4) compares the shipped notice with the repository's `NOTICE` byte for
byte. One defect was planted per requirement and the catching test recorded — the delete label
refilled from `audioUsage`, the cap firing `RECORD_STOP`, and the notice generated from a
different file.

**Twenty-six of them compose the Today frame under Robolectric** — `TodayFrameTest`'s twenty-four
plus `B-154`'s two — which is new with `T-030`: the Compose test harness is in the JVM tier as
well as the instrumented one, so the button's bounds across every `VoiceState`, the list's height
at the declared minimum and the gap around an irreversible control are **measured** rather than
argued. What that harness cannot answer is written down in `DEC-0043` — its text metrics do not
scale with the forced density, so the budget tests are stricter than the device and the row-level
tests run on a deliberately tall canvas.

**And what it answers in the wrong units if you ask carelessly**, which `B-154` paid for:
`DeviceConfigurationOverride.ForcedSize` scales the **density** rather than the root, so a 198 px
Robolectric root reports itself as 1024 dp at `density = 0.19375` while `compose.density` still
answers `1.0`. A distance compared against a floor taken from the rule's density is a comparison
between two different units — it called a correct 24 dp layout "5 px", and an arrangement was
rewritten around the artefact before anyone measured the density itself.
`GeometryDensityPolicyTest` (2 — `B-208`) refuses any test that measures a laid-out node with `<rule>.density`, so the rule below is a check rather than a sentence. `DestructiveAffordanceTest` now takes its floor from `node.layoutInfo.density` and prints which
density it measured in.

`NotesViewModelTest` (27 — four on the dictation commit, five on `D-24`'s banner, three on
`DEC-0046`'s day card, and three new with `REQ-047`/`REQ-058`: a failed Space start asks to
retry the **Space**, *"Starting the space…"* does not outlive the trip, and a refused connection
reaches the person as a network failure rather than *"Couldn't save"*),
`VoiceViewModelTest` (27, two of them `REQ-048`'s in-flight guard and two `REQ-047`'s
*Resume* / *Download again*),
`SettingsViewModelTest` (31, five of them `REQ-067`'s and `REQ-054`'s: a chip tap during an
export does not re-enable *Export*, one afterwards does not wipe the path, a transfer started
elsewhere keeps moving, an archive is unpacked and its outcome shown, and a second press during
a restore is ignored; five more with this change — `B-159`'s *a seam that throws reaches the
banner instead of escaping the scope*, and `B-199`'s listing, one-row removal, *Free up space*
— each of which asserts the cancel happened **while the file was still there** — and a finished
download reaching the shelf without leaving the screen),
**`DestructiveAffordanceTest` (6 — `B-154`'s redesign half: the bare-`FlowRow` scan and its
planted case, the gap between an irreversible control and a frequent one at 1024 dp and again at
`PanelMinimum.WIDTH_DP`, and the two that hold `Tokens.Palette.danger` to a control)**,
**`VersionCodeTest` (3 — `B-160`: the stamped code is the commit's own clock, every commit in
this history orders the same way by the rule and by its clock, and the bound with its headroom)**,
`TodayFrameTest` (24, two of them `REQ-060`'s: after scrolling to the thirtieth note both
*Undo* and the failure banner are still displayed),
`EditorViewModelTest` (14, one of them `REQ-059`'s: edit → delete → dispose leaves no row),
`VoiceViewModelRecordingTest` (11 — the ten-minute cap, the stop when the screen goes away,
three that pin the window `T-019` opened in `start()`, and two on `REQ-052`: a muted microphone
becomes `Silenced` and comes back, and stopping while muted still transcribes),
**`DictationOutboxTest` (15 — one is `DEC-0096`'s, every restored dictation names its recording for the sweep; one is `B-245`'s, an offer on disk before it is claimable; `REQ-046`'s hand-off and its disk half, including sixteen threads
claiming one entry at once, which a test dispatcher cannot show because it runs one coroutine at
a time, and the two the outbox pair closed: the flush before the rename, and an offer that lands
inside a claim keeping its file)**,
**`TranscriptionAbandonedTest` (1 — `DEC-0092`: a run the engine abandons leaves a state with an exit and keeps the recording)**,
**`DictationDurabilityTest` (22 — two reject obsolete directory results, including a new empty list; one is `DEC-0098`'s: a recording kept outside the vault is listed first and an earlier one is dated by its name; one is `B-254`'s: the editor lists every recording the note keeps, current first; one is `B-256`'s: a recovered dictation whose recording is gone is written without an audio path; two are `DEC-0096`'s: an aside dictation written on the next launch is gone from disk, and two restored dictations are both written and neither is left; three are `DEC-0095`'s: an offer does not overwrite an unsettled dictation, settling one moved aside removes only it, and a dictation that failed to write survives the next one being written; the twelfth is `DEC-0094`'s: a retry that finds its note already written clears the banner; the eleventh is `B-241`'s: an undo that beats the mirror keeps the note's recording; the rest are `DEC-0088`'s: the entry is on disk while its note is written,
a failed write leaves it bound to the note it was becoming, *Retry* settles it, a restored bound
entry is written once under its bound id or not at all, and the editor settles only through a
write that carried the words)**,
**`DictationSurvivalTest` (5 — the three `REQ-046` names, plus the editor's hold and a cold
start: a host cleared mid-transcription still produces a note, the WAV survives while a
dictation is pending, and two surfaces observing the outbox write one note)**,
`ManifestPlatformTest` (8 — the eighth is `B-104`'s: no backup through extraction rules that exclude everything), `VaultExportPolicyTest` (8 — two are `B-246`'s: a publish that did not take is a failure and leaves nothing pending, and the store runs on the io dispatcher it is given), `GraphSttEngineTest` (6),
`NotesRetranscribeTest` (6: body replaced, body kept, no recording, engine failed, the title
rule's two branches, the language reaching the engine),
`DictationKeyTest` (5), `SearchViewModelTest` (4),
**`TodayScreenShellTest` (4 — the half `TodayFrameTest` cannot reach: the `permissionAsks`
collector, the clipboard condition and the provider line, composed against the STATEFUL screen
with real view models over fakes. `B-156`: three of the ten defects step 8's verification found
lived in that shell and nothing that runs had touched it)**,
`VoiceViewModelThreadTest` (4 — the **dispatcher**, not a thread name; under
`StandardTestDispatcher` every coroutine runs on one thread, so the obvious assertion would pass
against the defect), `ScratchAudioTest` (4), `SettingsCarryOverTest` (6 — the four carry-over cases plus the two the `2026-09-22` audit added: a Keystore write that failed is not reported as a migration, and the schema marker is then left absent so the next launch tries again),
`SpaceExitTest` (6), `PanelSizeTest` (3 — the third is `REQ-060`'s: a `defaultHeight` below the
declared `minHeight` is not a size the shell can honour, and it fired for real when the notice
slot raised the minimum), `SettingsVersionTest` (2),
`MainThreadPolicyTest` (3, which runs `tools/check_main_thread.py` — the third is `B-159`'s: the
script keyed on the literal `viewModelScope.launch`, and moving all thirty-six behind
`launchGuarded` left it scanning **nothing** while still printing `ok`) and `PermissionRecoveryTest`
(2). Each was watched failing against a defect planted for it alone.

Compilation plus the device run: the panel resumed beside Meta Virtual Display, a note written, a
voice note in both languages, a search hit, a streamed answer. Recorded in
`docs/evidence/verification.md`.

## Decisions this module carries

`DEC-0002` the panel and Space shape · `DEC-0009` hand tracking is declared so the shell stops
blocking the Space · `DEC-0067` and the Space uses it · `DEC-0065` the Horizon OS floor is 81 · `DEC-0010` the hold gesture and the capture sheet are deleted, reversing the
v1 reasoning still quoted above · `DEC-0018 (retired, superseded by DEC-0028)` a Spatial SDK panel is given all four Compose host
owners · `DEC-0024` a chosen speech provider refuses out loud and no address is invented ·
`DEC-0043` the Today frame is three fixed things and one list, its declared minimum is measured,
and the frame is stateless so the geometry can be. ·
`DEC-0044` the first run asks, records and confirms — the permission is requested on the first
press, granting records, `VoiceState.Allowed` is gone, and every wait says what it is ·
`DEC-0047` what leaves the device is said on the screen that sends it, and the clipboard can be
refused · `DEC-0048` the build stamps its own identity from git, and the installer refuses to
guess which headset it is writing to.

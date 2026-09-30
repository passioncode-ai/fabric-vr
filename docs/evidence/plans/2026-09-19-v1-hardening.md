# Plan — v1 hardening (2026-09-19)

Turns every finding of [`../audits/2026-09-19-v1-audit.md`](../audits/2026-09-19-v1-audit.md) into a
task a **zero-context agent** can execute. Each task names its parent findings, the exact files, the
exact change, the test written **first**, the acceptance command, what it must not touch, and what it
depends on. Read the audit row before the task; the row is the failure you are fixing, the task is how.

## 0. Rules for the executing agent — read once, obey throughout

1. **Repository and branch.** The repository root, branch `feat/v1-notes-core` (or a task branch off
   it, merged back with `--no-ff`). Never commit to `main`. Push after every task.
2. **Environment.** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
   before any Gradle command. `local.properties` holds `sdk.dir` and is untracked. Submodule:
   `git submodule update --init --recursive` once.
3. **Commands, exactly:**
   - JVM suite: `./gradlew testDebugUnitTest` — must stay at 0 failures.
   - Device suite (after H-01): `./gradlew :app:connectedDebugAndroidTest` with the Quest awake.
   - Lint: `./gradlew :app:lintDebug` — 0 errors; the 34 "newer version available" warnings are
     expected (`DEC-0004`); a new warning class is a finding.
   - Gates: `bash scripts/check-docs.sh`, `bash scripts/check-secrets.sh`, `python3 docs/ux/lint.py`
     — all must exit 0 before a commit.
   - Build + install: `./gradlew :app:assembleDebug && bash scripts/install-on-quest.sh`.
4. **TDD is not optional.** Write the named test, run it, **watch it fail for the stated reason**,
   then implement, then watch it pass. Paste the red and the green output into the commit message.
   A test that passes before the fix is testing the wrong thing.
5. **Scenarios move in the same commit as behaviour.** When a task says "update SCN-nnn", edit
   `docs/ux/scenarios.md` (and `screens.md`/`flows.md` where named) in that commit, then run
   `python3 docs/ux/lint.py`.
6. **Do not widen the task.** A neighbouring smell you notice becomes a row on
   `docs/evidence/backlog.md`, not an edit. The audit already lists 58 findings; the plan orders them.
7. **Strings are EN drafts.** Keep the register of the existing strings; the brand/copy pass is
   deferred by decision (carry-over row 1). Do not invent new terminology — reuse `CONTEXT.md`.
8. **Never print a secret.** No key, token or note body in a log, a test name, a commit or a chat.
   `Log2.redact()` exists for a reason.
9. **Commit message shape:** `fix(<module>): <what> — closes AUD-nn` with the red/green evidence and
   `Co-Authored-By` as configured. One task, one commit, unless the task says otherwise.
10. **When a task says (device-verify):** the fix is complete only when the observation is recorded
    in `docs/evidence/verification.md` — the row's `Human` column is a date and the `Note` says what
    was seen. A fix nobody watched on the headset stays `partial`.

## 1. Work packages and order

| WP | Tasks | Closes | Why this order |
|---|---|---|---|
| **WP-0 Prove the seams** | H-01, H-02 | test gaps behind AUD-01…05 | every later fix gets a test that can actually fail |
| **WP-1 Blockers** | H-03, H-04, H-05, H-06 | AUD-01…05, 10, 39 | the product does not work until these land |
| **WP-2 First-session Highs** | H-07 … H-12 | AUD-06…09, 11, 12, 35, 36, 43 | what the operator hits in the first ten minutes |
| **WP-3 Core correctness** | H-13 … H-18 | AUD-13…19, 27…34, 37 | data integrity and the assistant's honesty |
| **WP-4 Native, network, security** | H-19 … H-22 | AUD-20, 22…26, 44, 45, 50 | speed, resilience, the key |
| **WP-5 Completeness and hygiene** | H-23 … H-27 | AUD-41, 42, 46…49, 51…58, DOC-01…04 | what the screens promise, what the build should be |
| **WP-6 Verify on the headset** | H-28 | REQ-001/003/007, all (device-verify) rows | closes the run |

Dependency graph: `H-01 → H-03,H-04,H-19,H-28` · `H-02 → H-06,H-13` · `H-03 → H-04,H-07,H-16,H-22` ·
`H-05 → H-16` · `H-13 → H-24` · `H-17 → H-21` · everything → `H-28`. Tasks with no edge between them
may run in parallel **in separate worktrees**, provided they touch disjoint files (each task lists its
files; check before dispatching two at once).

---

## WP-0 — Prove the seams

### H-01 — Instrumented device suite skeleton
`Closes:` the test gap behind AUD-01, AUD-02, AUD-20, REQ-003 (carry-over row 13) · `Size:` M · `Depends on:` — · `Blocks:` H-03, H-04, H-19, H-28

**Why.** Every Blocker lives at a seam the JVM cannot reach. Until a suite runs on the Quest, "green"
means "green where it cannot fail".

**Files to create**
- `app/src/androidTest/kotlin/ai/passioncode/fabricvr/PanelSmokeTest.kt`
- `feature-stt/src/androidTest/kotlin/ai/passioncode/fabricvr/stt/WhisperEngineTest.kt`
- `feature-stt/src/androidTest/assets/fixtures/jfk.wav` — copy from
  `third_party/whisper.cpp/samples/jfk.wav` (16 kHz mono; verify with `file`).
- `feature-stt/src/androidTest/assets/fixtures/ru_short.wav` — record 3–5 s of Russian ("Это тест
  распознавания речи") at 16 kHz mono 16-bit: `sox -d -r 16000 -c 1 -b 16 ru_short.wav trim 0 5`
  or `ffmpeg -f avfoundation -i ":0" -ar 16000 -ac 1 -sample_fmt s16 -t 5 ru_short.wav`; keep it
  under 200 KB. Commit it (`.gitignore` anchors `*.bin`, not `*.wav`).
- `scripts/push-model-for-tests.sh` — pushes `ggml-small-q5_1.bin` from `~/Downloads` (or a path
  argument) into `/data/local/tmp/fabricvr-test-models/` on the device; the test reads it from there.

**Files to edit**
- `feature-stt/build.gradle.kts`: add `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`
  under `defaultConfig`, `androidTestImplementation(libs.androidx.test.runner)`,
  `androidTestImplementation(libs.androidx.test.junit)`, and `sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")`.
- `README.md` → *Checks*: add the device suite command and the model-push step.

**Tests (write first)**
- `PanelSmokeTest.panel activity launches and renders the today screen`: `ActivityScenario.launch(PanelActivity::class.java)`,
  then `onView(withText(startsWith("Hold to record"))).check(matches(isDisplayed()))` (Espresso) or a
  Compose `onNodeWithText("Hold to record")` — use `androidx.compose.ui.test.junit4` if you add it.
- `WhisperEngineTest.transcribes the jfk fixture in english`: build `FileModelStore` pointed at
  `/data/local/tmp/fabricvr-test-models`; skip with `Assume.assumeTrue(store.isPresent())` when the
  model is absent (say so in the test name output); read `jfk.wav` via `WavWriter.readPcm`, call
  `transcribe(pcm, langHint = "auto")`, assert the text contains `"ask not what your country"`
  (case-insensitive) and `language == "en"`. Record `durationMs` in the test log.
- `WhisperEngineTest.transcribes the russian fixture and detects ru`: same with `ru_short.wav`,
  assert `language == "ru"` and the text contains `"тест"`.
- `WhisperEngineTest.reports the model as missing when it is absent`: an empty store →
  `Result.failure` whose `SttException.error is AppError.ModelMissing`.

**Acceptance.** `./gradlew :app:connectedDebugAndroidTest :feature-stt:connectedDebugAndroidTest`
exits 0 with the model pushed; the JFK test prints its latency, which is copied into
`docs/research/local-evidence-2026-09-19.md` as the first measured on-device number.

**Must not touch.** Production code. If a test cannot pass without a production change, that change
belongs to the task that owns the finding — stop and record it.

### H-02 — `VaultMirror` tested through a real repository
`Closes:` test gap behind AUD-05, AUD-13 · `Size:` S · `Depends on:` — · `Blocks:` H-06, H-13

**Files to create.** `feature-vault/src/test/kotlin/ai/passioncode/fabricvr/vault/VaultMirrorTest.kt`
(Robolectric, in-memory `NotesDatabase`, `RoomNotesRepository`, `FileVault(temp.newFolder())`,
`VaultMirror(repo, vault).start(TestScope)`).

**Tests (write first — both must be RED against the current code)**
- `a note deleted a month after it was created leaves no file in the vault`: upsert a note whose
  `createdAt` is 40 days ago (through the repository, not the DAO), assert its `.md` exists, delete it,
  `advanceUntilIdle()`, assert `vault.root.walkTopDown().none { it.nameWithoutExtension == id }`.
  **Red reason today:** the mirror derives the folder from `System.currentTimeMillis()` (AUD-05).
- `a note saved before the mirror subscribes is still mirrored`: upsert **before** calling
  `mirror.start(...)`, then start, `advanceUntilIdle()`, assert the file exists.
  **Red reason today:** `MutableSharedFlow(replay = 0)` drops it (AUD-13).

**Acceptance.** Both tests exist and fail with those two reasons (paste the output). They turn green
in H-06 and H-13 respectively.

**Must not touch.** Production code.

---

## WP-1 — Blockers

### H-03 — Voice capture as an in-window overlay; the hold survives it
`Closes:` AUD-01, AUD-03, AUD-10, AUD-39 · `Size:` M · `Depends on:` H-01 · `Blocks:` H-04, H-07, H-16, H-22

**Why.** Opening a `Dialog` during the press cancels the press. The sheet must live in the same window
as the button. A `Dialog` also does not render inside the Spatial scene at all.

**Files to edit**
- `app/src/main/kotlin/ai/passioncode/fabricvr/ui/VoiceCaptureSheet.kt`
  - Remove `Dialog(...)`. Make the composable a plain overlay: root `Box(Modifier.fillMaxSize())`
    with a scrim `Box(Modifier.fillMaxSize().background(Tokens.Palette.ink.copy(alpha = 0.6f)))` that
    does **not** intercept pointer events destined for the button (place the overlay **below** the
    button in z-order, see TodayScreen), and a centred `Surface(color = Tokens.Palette.surfaceRaised,
    shape = RoundedCornerShape(Tokens.Radius.l), tonalElevation = 0.dp)` holding the existing `Column`.
  - Remove `rememberLauncherForActivityResult` and the `permission` launcher entirely (H-04 replaces
    it with a lambda parameter). Add parameter `onRequestPermission: () -> Unit` and call it where
    `permission.launch(...)` was.
- `app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt` and `NoteEditorScreen.kt`
  - Wrap the screen body in `Box(Modifier.fillMaxSize()) { Column(...) { … } ; if (sheetOpen)
    VoiceCaptureSheet(...) }` so the overlay composes in the same window and the button stays
    reachable. The `HoldToTalkButton` must remain in the tree while the sheet is open (it is).
- `app/src/main/kotlin/ai/passioncode/fabricvr/ui/HoldToTalkButton.kt`
  - Use the return value: `val completed = tryAwaitRelease(); held = false; release(completed)` and
    change the parameter to `onRelease: (completed: Boolean) -> Unit`. Callers pass
    `{ completed -> if (completed) voiceViewModel.stopAndTranscribe() else voiceViewModel.cancel() }`.
  - Add `DisposableEffect(Unit) { onDispose { if (held) release(false) } }` so leaving composition
    mid-hold ends the recording (AUD-10).
  - Add `.semantics { role = Role.Button }` and `.sizeIn(minHeight = 48.dp, minWidth = 48.dp)`; fix
    the dead colour branch (`accentInk` both sides → keep one).
- `docs/ux/screens.md` SCR-03: replace "Appears over SCR-01 or SCR-02" with "an in-window overlay over
  SCR-01 or SCR-02 (never a separate window)". `docs/ux/flows.md` FLW-02: add the rejected shape
  "a Dialog — lost because a new window cancels the press that opened it".

**Tests (write first)**
- Device (`app/src/androidTest/.../VoiceHoldTest.kt`): launch `PanelActivity`, grant `RECORD_AUDIO`
  via `GrantPermissionRule`, push the model (or `assumeTrue`), perform a **3-second** long press on
  the node with text `Hold to record` using `performTouchInput { down(center); advanceEventTime(3000); up() }`,
  then assert a node with text starting `Transcribing` or the transcript preview appears **and**
  no node with text `Nothing was heard` appears within 10 s. **Red today** (AUD-01).
- JVM (`app/src/test/.../HoldToTalkContractTest.kt`): a pure function test on the release contract
  is not possible for `pointerInput`; instead assert `VoiceViewModel.cancel()` from `Recording` yields
  `Idle` and `stopAndTranscribe()` from `Recording` yields `Transcribing` (extend
  `VoiceViewModelStateTest`).

**Acceptance.** `VoiceHoldTest` green on the device; a manual 3-second hold on the headset shows the
level meter, then the transcript (device-verify, record in `verification.md`).

**Must not touch.** `VoiceViewModel` state machine (beyond the `cancel` path already there), STT
modules, `ImmersiveActivity` (H-12).

### H-04 — Permission requested from the activities, never from the panel tree
`Closes:` AUD-02, AUD-08 · `Size:` M · `Depends on:` H-03 · `Blocks:` H-28

**Why.** `rememberLauncherForActivityResult` needs an `ActivityResultRegistryOwner`; the immersive
host is a plain `android.app.Activity` inside the Spatial runtime. And the first hold should show the
system prompt, not a banner.

**Files to create**
- `app/src/main/kotlin/ai/passioncode/fabricvr/PermissionRequester.kt`:
  ```kotlin
  interface PermissionRequester {
      /** Asks for RECORD_AUDIO; [onResult] gets (granted, permanentlyDenied). */
      fun requestRecordAudio(onResult: (granted: Boolean, permanentlyDenied: Boolean) -> Unit)
  }
  ```

**Files to edit**
- `PanelActivity.kt`: implement it with `registerForActivityResult(ActivityResultContracts.RequestPermission())`
  registered in `onCreate` **before** `setContent`; `permanentlyDenied = !granted &&
  !shouldShowRequestPermissionRationale(RECORD_AUDIO)`. Pass the requester into `FabricApp`.
- `ImmersiveActivity.kt`: implement it with `requestPermissions(arrayOf(RECORD_AUDIO), REQ)` and
  `override fun onRequestPermissionsResult(...)` (the plain-Activity API — it exists on
  `android.app.Activity`), same `permanentlyDenied` rule. Pass it into `FabricApp`.
- `FabricApp.kt`: new parameter `permissionRequester: PermissionRequester`, threaded to
  `TodayScreen` and `NoteEditorScreen`, then into `VoiceCaptureSheet(onRequestPermission = …)`.
- `VoiceViewModel.kt`:
  - `start()` when permission is missing → new state `VoiceState.NeedsPermission` (add to the sealed
    interface) instead of `Failed`. The sheet renders it as one line "Dictation needs the microphone."
    with buttons *Allow* (→ `onRequestPermission`) and *Write instead* (→ dismiss).
  - Add `fun onPermissionResult(granted: Boolean, permanent: Boolean)`: granted →
    `VoiceState.Idle` with a hint (the sheet shows "Allowed. Hold the button to record.") — **never
    auto-start**; permanent → `Failed(UiStateMapper.map(AppError.Permission("microphone", permanent = true)))`;
    otherwise → `Idle`.
- `AudioRecorder.kt:37`: keep the `Permission("microphone")` failure for the race where permission
  is revoked mid-session; unchanged otherwise.
- `docs/ux/scenarios.md` SCN-013: step 1 becomes "Hold *Record* → the system permission prompt
  appears (the app asks for the microphone the first time it is needed)"; step 3 becomes "Tap *Allow*
  → the sheet says 'Allowed. Hold the button to record.'"; the alt path stays (permanent denial →
  *Open app settings*). `screens.md` SCR-03: add the `needs-permission` state.

**Tests (write first)**
- JVM `VoiceViewModelPermissionTest`: (a) `start()` with a recorder whose `hasPermission()` is false →
  `NeedsPermission`; (b) `onPermissionResult(true, false)` → `Idle`, and `recordJob` is null (no
  auto-start — assert `state !is Recording`); (c) `onPermissionResult(false, true)` → `Failed` whose
  message action is `OPEN_APP_SETTINGS`. Make `VoiceViewModel` take the recorder and a
  `() -> Boolean` model-presence check via constructor for testability.
- Device: `VoiceHoldTest` gains `hold in the Space does not crash` — launch `ImmersiveActivity`,
  assert the process is alive after a 1-second press on `Hold to record` (Espresso idling; the panel
  is a Compose view inside the scene — if it is not reachable by Espresso, record that limitation and
  make the manual (device-verify) step the acceptance).

**Acceptance.** JVM tests green; on the headset, first hold shows the OS prompt; in the Space, a hold
does not crash (device-verify).

**Must not touch.** The STT router, Settings.

### H-05 — Cleartext to a LAN whisper-server, refused everywhere else
`Closes:` AUD-04 · `Size:` S · `Depends on:` — · `Blocks:` H-16

**Decision to implement.** Network Security Config cannot express IP ranges, and app-wide
`usesCleartextTraffic="true"` would also let a mistyped OpenRouter URL go plain. So: enable cleartext
at the platform level and enforce the policy **in code at the two call sites**.

**Files to edit**
- `app/src/main/AndroidManifest.xml`: add `android:usesCleartextTraffic="true"` to `<application>`
  with an XML comment pointing at `DEC-0005`.
- The policy is already recorded as `DEC-0005`; implement it, do not re-decide it.
- `feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/RemoteWhisperClient.kt`: add
  `companion object { fun validateBaseUrl(url: String): Result<HttpUrl> }` — accept `https://*`;
  accept `http://` **only** when the host is an IPv4 literal in `10.0.0.0/8`, `172.16.0.0/12`,
  `192.168.0.0/16`, `127.0.0.0/8`, or ends with `.local`; otherwise `AppError.RemoteStt(0,
  "cleartext to a public host is refused")` → give it its own `AppError.InsecureUrl(url)` and a
  mapper string "Only https, or http to a device on your own network."
- `feature-assistant/.../OpenRouterClient.kt`: refuse a non-https `baseUrl` at construction
  (`require(baseUrl.startsWith("https://"))`).
- `SettingsViewModel.saveServerUrl`: run `validateBaseUrl` before saving; on failure show the mapper
  message and keep the field.
- `README.md` and `docs/modules/feature-stt.md`: state the policy in one sentence each.

**Tests (write first)**
- `RemoteWhisperClientUrlTest` (JVM): `http://192.168.1.20:8080` accepted; `http://10.1.2.3` accepted;
  `http://whisper.local:8080` accepted; `http://example.com` refused with `InsecureUrl`;
  `https://example.com` accepted; garbage refused.
- Device (`feature-stt/src/androidTest/.../CleartextTest.kt`): start a `MockWebServer` **on the
  device** (add `androidTestImplementation(libs.okhttp.mockwebserver)`), point `RemoteWhisperClient`
  at `http://127.0.0.1:<port>`, enqueue `{"text":"ok"}`, assert the transcript text is `ok`. **Red
  today** with `UnknownServiceException`.

**Acceptance.** Both tests green; `bash scripts/check-docs.sh` green with the new decision cited.

### H-06 — Vault removal by identity, not by today's date
`Closes:` AUD-05, AUD-31 · `Size:` S · `Depends on:` H-02 · `Blocks:` —

**Files to edit**
- `core-notes/.../Note.kt`: `data class Deleted(val id: String, val createdAt: Long) : NoteChange`.
- `core-notes/.../NotesRepository.kt` `delete(id)`: read the entity first (`dao.get(id)`), then
  `dao.delete(id)`, then `changes.tryEmit(NoteChange.Deleted(id, entity?.createdAt ?: 0L))`.
- `feature-vault/.../Vault.kt`:
  - Derive the folder in **UTC** (`ZoneOffset.UTC`), both in `pathFor` and `remove` — one place:
    `private fun folderFor(createdAt: Long)`.
  - `remove(id, createdAt)`: try the computed path first; if the `.md` is absent, **search**
    `root/notes` recursively for `"$id.md"` (files written before this fix used the system zone) and
    delete what is found together with `"$id.wav"`; return `Result.failure(VaultException(
    AppError.Storage("vault.remove", FileNotFoundException(id))))` when nothing was deleted.
- `feature-vault/.../VaultMirror.kt:28`: `vault.remove(change.id, change.createdAt)`.
- `docs/modules/feature-vault.md`: one sentence on UTC folders and the identity fallback.

**Tests.** H-02's first test turns green; add `MarkdownVaultTest.remove finds a file written under a
different zone` (write with a `FileVault(root, zone = ZoneId.of("Asia/Tokyo"))`, remove with the UTC
one, assert gone) and `remove reports failure when nothing matched`.

**Acceptance.** `./gradlew :feature-vault:testDebugUnitTest :core-notes:testDebugUnitTest` green;
`VaultMirrorTest` first case green.

---

## WP-2 — First-session Highs

### H-07 — "Record into this note" attaches to the open note
`Closes:` AUD-06 · `Size:` S · `Depends on:` H-03

**Files to edit**
- `VoiceCaptureSheet.kt`: replace `onSaved: (String) -> Unit` with
  `onTranscript: (Transcript, audioPath: String?) -> Unit`; the *Save* button calls it and then
  `viewModel.cancel()`. The sheet no longer touches `Graph.notes`.
- `TodayScreen.kt`: `onTranscript = { t, audio -> scope.launch {
  val note = NotesRepository.newNote(title = t.text.take(60), body = t.text).copy(transcript = t, audioPath = audio)
  Graph.notes.upsert(note).onSuccess { onOpenNote(it.id) } } }` — move this into
  `NotesViewModel.createVoiceNote(t, audio): Note?` so the screen has no repository call.
- `NoteEditorScreen.kt`: `onTranscript = { t, audio -> viewModel.attachTranscript(t, audio) }`.
- `docs/ux/scenarios.md` SCN-004: add alt path "From the editor, *Record into this note* appends the
  transcript to the open note and attaches the audio".

**Tests (write first).** `EditorViewModelTest.attaching a transcript appends to the body and keeps the
audio` (body `"a"` + transcript `"b"` → body `"a\n\nb"`, `transcript` set, `audioPath` set, one
`upsert`). `NotesViewModelTest.createVoiceNote saves a note carrying the transcript`.

### H-08 — Error banners that act and can be dismissed
`Closes:` AUD-07, AUD-43 · `Size:` M · `Depends on:` —

**Files to edit**
- `core-common/.../ui/Components.kt`: `ErrorBanner(message, modifier, onAction: (UiAction) -> Unit,
  onDismiss: () -> Unit)` — always render a trailing close icon (`Icons.Filled.Close`, content
  description "Dismiss") calling `onDismiss`; render the action button only when `message.action != null`.
- Each ViewModel gains `fun retry()` that re-runs its last failed operation:
  `NotesViewModel.retry()` → re-collect (recreate the `combine` job) and re-run `dailyNote`;
  `EditorViewModel.retry()` → `flush()`; `SearchViewModel.retry()` → re-emit the current query;
  `ChatViewModel.retry()` → `ask(lastQuestion)` where `lastQuestion` is kept in state (also fixes the
  "question preserved" half of AUD-38); `SettingsViewModel.retry()` → re-run the last `put` or
  `downloadModel()`.
- Call sites in `TodayScreen`, `NoteEditorScreen`, `SearchScreen`, `SettingsScreen`, `ChatScreen`:
  `ErrorBanner(msg, onAction = { a -> when (a) { RETRY -> vm.retry(); OPEN_SETTINGS,
  OPEN_APP_SETTINGS -> onSettings(); … } }, onDismiss = vm::dismissMessage)`.
  `OPEN_APP_SETTINGS` must open the OS app-info page:
  `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))`.
- `TodayScreen.kt:107-112`: order the `when` as `message != null && notes.isEmpty()` → show **only**
  the banner (no "Nothing here yet"); then `loading`; then `empty`; then the list.

**Tests (write first).** `UiStateMapperTest` unchanged; add `ChatViewModelTest.retry re-sends the last
question` (fake `Assistant`), `NotesViewModelTest.an error with an empty list does not show the empty
hint` (assert a `showEmptyHint` boolean derived in the state), and a Compose UI test if
`ui-test-junit4` was added in H-01: the banner exposes a node with content description `Dismiss`.

### H-09 — Layouts that survive the panel's minimum size
`Closes:` AUD-09 · `Size:` M · `Depends on:` —

**Files to edit**
- `TodayScreen.kt`: header actions → `FlowRow` (`androidx.compose.foundation.layout.FlowRow`,
  needs `@OptIn(ExperimentalLayoutApi::class)`); below 640 dp available width, render icon buttons
  (`Icons.Filled.Search`, `Icons.Filled.Chat`, `Icons.Filled.Settings`, a `ViewInAr`-style icon for
  Space) with content descriptions equal to today's labels. Use `BoxWithConstraints` to read the width.
  Tag chips → `LazyRow`, no `take(12)`.
- `NoteEditorScreen.kt`: root `Column` gets `.verticalScroll(rememberScrollState())`; the body field
  `heightIn(min = 160.dp)` instead of `height(320.dp)`; header actions in a `FlowRow`.
- `ChatScreen.kt`, `SearchScreen.kt`, `SettingsScreen.kt`: check each `Row` of buttons; convert any
  that can exceed 480 dp to `FlowRow`.
- `docs/ux/screens.md` → *Panel geometry*: add "every screen is verified at 480 × 360 dp".

**Tests (write first).** Compose UI test `MinimumPanelSizeTest` (device or Robolectric with
`ui-test-junit4`): set a 480 × 360 dp content size via `Modifier.requiredSize`, compose `TodayScreen`
with 14 tags and 3 notes, assert nodes `Settings` (or its icon description) and `Hold to record` are
displayed and not clipped (`assertIsDisplayed`), and that `NoteEditorScreen`'s `Delete` is
reachable after `performScrollTo()`.

### H-10 — Settings: save results, main thread, state survival
`Closes:` AUD-11, AUD-38 (settings/chat fields), AUD-40 · `Size:` S · `Depends on:` —

**Files to edit**
- `SettingsViewModel.kt`: `reload()` and `put()` run in `viewModelScope.launch(Dispatchers.IO)`;
  `put` sets `message` on failure and a new `savedKey: Long` tick on success. `saveKey` returns
  nothing; the screen clears its field in a `LaunchedEffect(state.savedTick)`.
- `SettingsScreen.kt`: `rememberSaveable` for `key` and `server`; clear `key` only when
  `savedTick` changes.
- `ChatScreen.kt`: `rememberSaveable` for `question`; `ChatViewModel` keeps `lastQuestion`.
- `ChatViewModel.kt:21`: `model` default becomes `Models.DEFAULT` and is refreshed in `ask()`
  (already) and once in `init` on IO — remove the `Graph.model()` call from the data class default.
- `AndroidManifest.xml` `PanelActivity`: add
  `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|density"` so a
  panel resize does not recreate the activity (the Spatial sample declares the same set).

**Tests (write first).** `SettingsViewModelTest.a failed save keeps the message and does not tick
savedTick` and `…a successful save ticks savedTick` with an `InMemorySecureSettings` whose `put`
can be made to fail (add a `failNext` flag to the in-memory implementation in `core-common`).

### H-11 — Keystore hardening: no silent key loss, no alias race
`Closes:` AUD-22, AUD-23, AUD-50 · `Size:` S · `Depends on:` —

**Files to edit**
- `core-common/.../SecureSettings.kt`:
  - `secretKey()` → `@Synchronized`, cache in `@Volatile private var cached: SecretKey?`.
  - `KeyGenParameterSpec`: `.setKeySize(256)`; try `.setIsStrongBoxBacked(true)` and on
    `StrongBoxUnavailableException` build again without it.
  - `get()`: distinguish absent (`null` in prefs → `null`) from **undecryptable** (`AEADBadTagException`,
    `KeyPermanentlyInvalidatedException`, malformed) → `remove(key)`, `Log2.w("secure_settings.corrupt")`,
    and record the key name in a new `corruptedKeys: MutableStateFlow<Set<String>>` the Settings
    screen renders as "The saved OpenRouter key could not be read on this device — enter it again."
  - Interface: add `val corruptedKeys: StateFlow<Set<String>>` with an empty default in the in-memory
    implementation.
- The spec is already recorded as `DEC-0006` and the `run-as` caveat is already in `README.md`
  (*Security posture*); implement them, do not re-decide them.

**Tests (write first).** `SecureSettingsTest` (Robolectric): round trip; `remove`; **planted
corruption** — write garbage base64 under a key, `get` returns `null` **and** the key is in
`corruptedKeys` **and** the pref is gone; a concurrent first use from two threads yields one alias
(run `secretKey()` from 8 threads via a latch, assert all returned keys are `equals`).
Drop `isReturnDefaultValues` from `core-common/build.gradle.kts` so `Base64` runs for real under
Robolectric (AUD-49 for this module).

### H-12 — The Space: guarded launch, transparent panel, placed where the eyes are
`Closes:` AUD-12, AUD-35, AUD-36 · `Size:` M · `Depends on:` — (device-verify)

**Files to edit**
- `PanelActivity.kt` / `TodayScreen.kt`: `onEnterSpace` becomes
  `{ runCatching { startActivity(ImmersiveActivity.intent(this)) }.onFailure { showMessage(UiMessage(
  "The space could not start on this headset.", UiAction.RETRY)) } }` — add `message` handling to
  `NotesViewModel` (`showMessage(UiMessage)`) so the banner appears in the panel. Show a one-second
  "Starting the space…" state (`spaceStarting` boolean) so a slow start is not silence.
- `ImmersiveActivity.kt`:
  - `config { themeResourceId = R.style.Theme_FabricVR_Transparent; layoutWidthInDp = 1024f;
    layoutHeightInDp = 640f; layoutDpi = 288; enableTransparent = true; includeGlass = false;
    layerConfig = LayerConfig() }` (import `com.meta.spatial.toolkit.LayerConfig`; check the field
    names against `PanelRegistration`'s `config` DSL with `javap` — the sample uses exactly these).
  - `onSceneReady`: `scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)`; compute the pose from the
    viewer: `val head = scene.getViewerPose(); val forward = head.forward(); val flat =
    Vector3(forward.x, 0f, forward.z).normalize(); val position = head.t + flat * 1.3f;
    val position2 = Vector3(position.x, head.t.y - 0.15f, position.z)`; rotation: a quaternion that
    faces the viewer — `Quaternion.lookRotation`-style helper is not exposed; use
    `Quaternion(0f, yawDegreesOf(flat) + 180f, 0f)` (the three-float constructor is Euler degrees per
    `javap`; **verify the sign on device**, this is the (device-verify) item) and create
    `Entity.create(listOf(Panel(PANEL_ID), Transform(Pose(position2, rotation)), Grabbable()))` so the
    person can move it by hand if the first placement is off.
  - Add `Theme.FabricVR.Transparent` usage (it exists, unused).
- `docs/ux/scenarios.md` SCN-012: add the error path text; `screens.md` SCR-08: `loading` and `error`
  states now exist; `docs/modules/app.md`: describe the placement rule.

**Tests (write first).** `HybridIntentTest` (JVM): the panel→immersive intent has `ACTION_MAIN` and
`FLAG_ACTIVITY_NEW_TASK`; the return intent has `CATEGORY_HOME` and the `extra_launch_in_home_pending_intent`
extra — extract the intent builders into a testable `HybridIntents` object. `SpacePlacementTest`
(JVM, pure math): given a viewer pose facing +X at (2, 1.6, 2), the panel position is
(3.3, 1.45, 2) ± 0.01 — extract the placement into `fun placePanel(viewer: Pose): Pose` in a
plain Kotlin file with no SDK imports beyond `Pose/Vector3/Quaternion` (they are plain classes).

**Acceptance (device-verify).** Enter the Space: the panel appears in front of the wearer at roughly
chest-to-eye height, readable, over passthrough, with no opaque frame; *Back to panel* returns to the
shell. Record in `verification.md` REQ-007.

---

## WP-3 — Core correctness

### H-13 — Change stream that cannot drop, daily note that cannot double
`Closes:` AUD-13, AUD-28 · `Size:` S · `Depends on:` H-02 · `Blocks:` H-24

**Files to edit**
- `NotesRepository.kt`: `private val changes = MutableSharedFlow<NoteChange>(replay = 64,
  extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)`; replace every `tryEmit`
  with `emit` (the callers are `suspend`). Add a comment: replay lets a subscriber that starts late
  (the mirror on cold start) see what happened before it.
- `dailyNote`: move the read-or-create into `NoteDao` as
  `@Transaction suspend fun getOrCreateByDay(dayKey: String, factory: () -> NoteEntity): NoteEntity`.
- `NoteEntity`: `@Entity(tableName = "notes", indices = [Index(value = ["dayKey"], unique = true)])`;
  bump the database version to 2 with a `Migration(1, 2)` that creates the unique index after
  de-duplicating existing rows (`DELETE FROM notes WHERE dayKey IS NOT NULL AND rowid NOT IN
  (SELECT MIN(rowid) FROM notes WHERE dayKey IS NOT NULL GROUP BY dayKey)`). Add
  `exportSchema = true` and commit `core-notes/schemas/` so future migrations are testable.

**Tests (write first).** H-02's second case turns green; `NotesRepositoryTest.two concurrent
dailyNote calls create one note` (two `async` on `Dispatchers.Default`, assert one row);
`MigrationTest` (Room `MigrationTestHelper`) from v1 with two rows sharing a `dayKey` → one row and
the index present.

### H-14 — Search and tag queries that mean what the person typed
`Closes:` AUD-32, AUD-33 · `Size:` S · `Depends on:` —

**Files to edit**
- `NotesRepository.search`: tokenise with `Regex("[\\p{L}\\p{N}_]+")` (letters, digits, underscore —
  keep `-`-separated words as separate tokens, which is how FTS tokenised them at index time); each
  token becomes `"<token>"*`? — **no**: FTS4 prefix must be unquoted. Emit `token*` per token and
  join with a space (AND). Document the rule in a comment: "we search what FTS indexed; the
  tokenizer already split on punctuation".
- `NoteDao.observeByTag`: `WHERE ',' || tags || ',' LIKE '%,' || :tagEscaped || ',%' ESCAPE '\'`
  and escape `%`, `_`, `\` in the repository before the call.

**Tests (write first).** `NotesRepositoryTest.hyphenated words are searchable` (`work-log` found by
`work-log` and by `log`); `…a tag with an underscore does not match a lookalike` (`deep_work` does not
return `deepXwork`); `…punctuation in the query does not kill the prefix` (`decide?` finds `decided`).

### H-15 — The assistant reads what is relevant, and never denies notes exist
`Closes:` AUD-14, AUD-15, AUD-34 · `Size:` M · `Depends on:` —

**Files to edit**
- `NotesRepository`: add `suspend fun recent(limit: Int): List<Note>` (DAO `LIMIT :limit`) and
  `suspend fun searchAny(terms: List<String>, limit: Int): List<Note>` (FTS `MATCH` with `OR`,
  ordered by `updatedAt DESC`).
- `NotesContextBuilder.build`:
  - Terms = tokens of the question with length ≥ 3, minus a small stop list (EN + RU: the, and,
    what, did, про, что, как, это…) — keep the list in `StopWords.kt`, 40 words, tested.
  - `matching = repo.searchAny(terms, 20)`, `recent = repo.recent(40)`.
  - Budget loop: `if (block.length > remaining) { if (used.isEmpty()) append(block.take(remaining))
    else continue }` — never `break`, never report "no notes" when the base is non-empty; the
    "no notes yet" text only when `repo.recent(1).isEmpty()`.
- `docs/modules/feature-assistant.md`: update the context rule.

**Tests (write first).** `NotesContextBuilderTest` switches to a Robolectric `RoomNotesRepository`:
`a six-word question still finds the note that shares two words`; `an oversized first note is
truncated, not dropped, and the base is never called empty`; `budget never exceeded`;
`StopWordsTest`.

### H-16 — The fallback says why, and the sheet shows it
`Closes:` AUD-16, AUD-27 · `Size:` M · `Depends on:` H-03, H-05

**Files to edit**
- `core-notes/.../Note.kt` `Transcript`: add `val fallbackReason: AppError? = null` (serialise to
  the vault as `fallback: <status or class>` in `MarkdownSerializer`).
- `SttRouter`: on fallback → `local.transcribe(...).map { it.copy(source = LOCAL_FALLBACK,
  fallbackReason = remoteError) }`.
- `RemoteWhisperClient`: map non-`SttException` throwables with `toAppError()`; cap the body read
  to 64 KiB (`response.body?.source()?.let { src -> src.request(65536); src.buffer.snapshot().utf8() }`);
  cancel the call on coroutine cancellation (`suspendCancellableCoroutine`).
- `UiStateMapper`: `RemoteStt` text → "The speech server answered ${status}." (no claim about the
  headset); add `InsecureUrl` (from H-05) and `Network` already exists.
- `VoiceCaptureSheet` *Ready* state: badge `"${language} · ${engine} · ${source.label()}"` where
  `LOCAL_FALLBACK.label() == "local (fallback)"`; when `fallbackReason != null` show one muted line
  with `UiStateMapper.map(reason).text`; make the transcript an `OutlinedTextField` bound to a
  `editedText` state so it is editable before *Save*; add a row of `FilterChip`s `auto / ru / en`
  with *Re-run* that calls `viewModel.retranscribe(lang)` (new: re-runs the STT on the kept `pcm`
  with a pinned hint).
- `docs/ux/scenarios.md` SCN-005 alt path and SCN-007 step 3 now describe exactly this UI.

**Tests (write first).** `SttRouterTest.a fallback carries the server's error` (503 + local →
`fallbackReason is RemoteStt(503)`); `RemoteWhisperClientTest.an unreachable host is a Network
error, not RemoteStt(0)`; `VoiceViewModelTest.retranscribe keeps the audio and re-runs with the hint`
(fake engine records the `langHint`).

### H-17 — Cancellation means cancelled
`Closes:` AUD-17 · `Size:` S · `Depends on:` — · `Blocks:` H-21

**Files to create.** `core-common/.../Results.kt`:
```kotlin
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: kotlinx.coroutines.CancellationException) { throw c }
  catch (t: Throwable) { Result.failure(t) }
```
**Files to edit.** Replace `runCatching` with `runCatchingCancellable` at `NotesRepository.kt`
(`upsert`, `delete`, `dailyNote`), `Vault.kt` (`write`, `remove`), `WhisperEngine.kt`,
`RemoteWhisperClient.kt`, `SecureSettings.kt`, `ModelDownloader.kt` (the outer `runCatching` around
`execute()`). In `ModelDownloader` add `catch (c: CancellationException) { partial.delete();
emit(DownloadProgress.Failed(AppError.ModelDownload(Reason.CANCELLED))); throw c }` **before** the
`Throwable` catch — note `emit` inside a `catch` in a `flow {}` is allowed only if the flow is not
already cancelled; use `try/finally` with a flag and emit from `finally` guarded by
`currentCoroutineContext().isActive.not()`… simplest correct form: catch, delete the partial, rethrow,
and let the collector map the `CancellationException` to `CANCELLED` in the ViewModel
(`downloadModel` wraps `collect` in `try { } catch (c: CancellationException) { state = Idle }`).
Add a *Cancel* button to both download UIs (AUD-42 part): `VoiceViewModel.cancelDownload()` and
`SettingsViewModel.cancelDownload()` cancel the stored `Job`.

**Tests (write first).** `ModelDownloaderTest.cancelling mid-download removes the partial file`
(a slow `MockResponse().throttleBody(1024, 50, MILLISECONDS)`, cancel the collecting job after the
first `Running`, assert `.part` absent and no `Failed(NETWORK)` was emitted);
`NotesRepositoryTest.a cancelled upsert does not report a storage error` (cancel a job during
`upsert` on a dispatcher you control; assert `CancellationException` propagates).

### H-18 — Front-matter a YAML reader accepts, and a transcript marker that cannot collide
`Closes:` AUD-18, AUD-30 · `Size:` S · `Depends on:` —

**Files to edit.** `MarkdownSerializer.kt`:
- `private fun yaml(value: String): String` — if the value is empty, or contains any of
  `: # " ' [ ] { } , & * ! | > % @ \`` or a leading `-`/`?`, or leading/trailing spaces, or a newline
  → emit `"…"` with `\` and `"` escaped and newlines as `\n`; otherwise emit bare. Use it for
  `title`, `audio`, `engine`, and each tag.
- `parse`: unquote `"…"` values (reverse the escapes); accept both bare and quoted.
- Transcript section: write `\n\n<!-- fabricvr:transcript -->\n## Transcript\n\n…`; `parse` looks for
  the comment marker at a line start and **only** when the front-matter carries `lang`.

**Tests (write first).** `MarkdownVaultTest.a title with a colon, a hash and quotes survives`
(title `Panel: size #1 "final"` round-trips exactly, and the `title:` line starts with `"`);
`a body containing '## Transcript' is not split`; `tags with hyphens round-trip`.

---

## WP-4 — Native, network, security

### H-19 — Whisper engine: fast, closable, robust
`Closes:` AUD-19, AUD-20, AUD-44, AUD-45 · `Size:` M · `Depends on:` H-01

**Files to edit**
- `feature-stt/build.gradle.kts` `externalNativeBuild.cmake.arguments += "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16"`
  (ggml's CMake reads `GGML_CPU_ARM_ARCH`; verify after configuring that
  `feature-stt/.cxx/**/CMakeCache.txt` shows `HAVE_DOTPROD:INTERNAL=1` — **that grep is the
  acceptance**, not the flag being present).
- `fabricvr_whisper.cpp`: null-check `GetFloatArrayElements` (return `nullptr`); wrap each
  function body in `try { … } catch (const std::exception& e) { LOGE("%s", e.what()); return
  nullptr; } catch (...) { return nullptr; }`; build the result string with `env->NewByteArray` +
  `String(bytes, UTF_8)` on the Kotlin side (change `transcribe` to return `jbyteArray`, decode in
  `WhisperEngine`).
- `WhisperEngine.kt`: `close()` → `runBlocking(dispatcher) { if (ctx != 0L) { freeContext(ctx); ctx = 0L } }`,
  then `executor.shutdown()`; a `@Volatile closed` flag makes `transcribe` return
  `Result.failure(SttException(AppError.SttFailed(name, IllegalStateException("closed"))))`.
  `Graph`: expose `Graph.release()` called from `FabricVrApp.onTerminate()` (best effort). The
  process-lifetime choice is already `DEC-0007` — implement it, do not re-decide it.
- Stripping: in `feature-stt/build.gradle.kts` add `packaging { jniLibs.keepDebugSymbols.clear() }`
  and in `app/build.gradle.kts` `packaging { jniLibs.useLegacyPackaging = false }`; run
  `./gradlew :app:assembleDebug` and check `file` on the extracted `.so` says `stripped`. If AGP
  still ships the unstripped copy, set `android.ndk.debugSymbolLevel = "none"` for debug and record
  the reason.

**Tests.** `WhisperEngineTest.transcribe after close fails cleanly` (device); the JFK latency from
H-01 is re-measured and both numbers go into local evidence — the acceptance is **a measured
speed-up**, not a flag.

### H-20 — OpenRouter stream: error frames, truncation, timeouts, retry
`Closes:` AUD-26 · `Size:` M · `Depends on:` —

**Files to edit.** `OpenRouterClient.kt`:
- Per `data:` frame, decode `ErrorEnvelope` first; if `error != null` → `close(AssistantException(
  AppError.OpenRouter(error.code, error.code.toString(), error.message)))` and return.
- Track `sawFinish` (`finish_reason != null` or `[DONE]`); on stream end without it →
  `close(AssistantException(AppError.Network(EOFException("stream ended early"))))`.
- `catch (t: Throwable) { close(AssistantException(t.toAppError("assistant"))) }` around the loop.
- Client: `.callTimeout(10, MINUTES)`, `.writeTimeout(30, SECONDS)`; add an interceptor that retries
  **once** on 429/502/503 honouring `Retry-After` (cap 20 s) — only when no token has been emitted yet.
- Remove `okhttp-sse` from `feature-assistant/build.gradle.kts` (unused).
**Tests (write first).** `OpenRouterClientTest`: `an error frame inside a 200 stream surfaces as
OpenRouter`; `a stream that ends without DONE fails with Network`; `a 429 with Retry-After: 1 is
retried once` (MockWebServer enqueues 429 then the SSE body; assert two requests and the tokens).

### H-21 — Model download: own client, resume, a digest that is actually checked
`Closes:` AUD-24, AUD-25, DOC-02 · `Size:` M · `Depends on:` H-17

**Steps**
1. On the Mac: `curl -L -o /tmp/ggml-small-q5_1.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin`,
   `shasum -a 256 /tmp/ggml-small-q5_1.bin`, `stat -f %z /tmp/…` (must be 190085487). Record the
   digest, the date and the command in `docs/evidence/model-digest.md`.
2. `ModelStore.kt`: `expectedSha256 = "<that digest>"`.
3. `ModelDownloader.kt`: own `OkHttpClient.Builder().connectTimeout(15 s).readTimeout(90 s)
   .callTimeout(0)`; log the final URL host after redirects (`response.request.url.host`) and refuse
   a host not ending in `huggingface.co`; resume: if `.part` exists send `Range: bytes=<len>-`,
   seed the digest by re-reading the existing bytes, and treat a 200 (no range support) as restart.
4. `docs/ux/scenarios.md` SCN-006 step 3 now true; `README.md` mentions resume.

**Tests (write first).** `ModelDownloaderTest`: `resumes a partial file with a Range header`
(MockWebServer asserts the header and serves 206); `a redirect to a foreign host is refused`;
`a wrong digest is rejected` (already exists, now against the real pin).

### H-22 — Discarded audio is deleted; audio lives once
`Closes:` AUD-37 · `Size:` S · `Depends on:` H-03

**Files to edit.** `VoiceViewModel.cancel()`: if the state is `Ready`/`NothingHeard` with an
`audioPath`, delete the file. `VoiceCaptureSheet` *Save* → after the note is written the vault holds
the copy; `Vault.write` already copies — then delete the original under `filesDir/audio/` and set
`note.audioPath` to the vault path (do this inside `VaultMirror` after a successful write: emit a
follow-up `repository.upsert(note.copy(audioPath = vaultWav))` guarded against loops by comparing
paths). Add `docs/modules/app.md` one line: audio lives in the vault only.
**Tests (write first).** `VoiceViewModelTest.discard deletes the recording`; `VaultMirrorTest.after
mirroring, the note's audioPath points into the vault and the temp file is gone`.

---

## WP-5 — Completeness and hygiene

### H-23 — Screens promise it, code does it
`Closes:` AUD-41, AUD-42, AUD-53 · `Size:` M · `Depends on:` H-08

- `SearchScreen`: `FocusRequester` + `LaunchedEffect(Unit) { focus.requestFocus() }`; blank query →
  show `repository.observeNotes()` ("Recent") via `SearchViewModel`; trailing clear icon; a `loading`
  flag while the debounce/flow is in flight.
- `SettingsScreen`: version and build (`BuildConfig.VERSION_NAME`, `BuildConfig.VERSION_CODE` —
  `buildConfig = true` is already on); a "Custom model…" text field that saves any
  `provider/model` string; *Remove speech model* (`ModelStore.modelFile().delete()`); the vault path
  row copies to the clipboard on tap (`LocalClipboardManager`); the download row shows
  `store.modelName`.
- `VoiceCaptureSheet` *Downloading*: name the model; *Cancel* (H-17 wiring).
- `TodayScreen`: date from a `dayKey` in `NotesUiState` refreshed by a `while(true) { delay(until
  midnight) }` loop in `NotesViewModel`; `Icons.Filled.Mic` glyph for voice notes.
- Update `screens.md` SCR-04/06/07 coverage cells to the new line ranges after the change.
**Tests.** ViewModel-level: `SearchViewModelTest.a blank query lists recent notes`;
`SettingsViewModelTest.remove model deletes the file and flips modelPresent`.

### H-24 — "Vault out of sync" is real
`Closes:` AUD-29, DOC-03 · `Size:` S · `Depends on:` H-13

`VaultMirror.lastError` → `MutableStateFlow<Map<String, AppError>>` keyed by note id: set on
failure, removed on that note's next success. `SettingsViewModel` exposes `vaultOutOfSync: Int`
and the screen shows "N notes are not mirrored yet" with *Retry* (→ `VaultMirror.retryFailed()`
re-writes them). Map unknown throwables via `toAppError`.
**Tests.** `VaultMirrorTest.a failed write is remembered until that note is written again`.

### H-25 — Build and repository hygiene
`Closes:` AUD-46, AUD-47, AUD-48, AUD-49, AUD-57, DOC-01 · `Size:` M · `Depends on:` —

- `git rm -r --cached .kotlin && printf '.kotlin/\n' >> .gitignore`.
- `.gitignore`: replace `*.bin` with `**/models/*.bin` and `/app/src/main/assets/*.bin`.
- `scripts/check-secrets.sh`: exclude only `scripts/check-secrets.sh` and
  `docs/evidence/audits/**` by path (they quote shapes); extend `PATTERNS` with
  `hf_[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{36}|gho_[A-Za-z0-9]{36}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|glpat-[A-Za-z0-9_-]{20}`;
  plant each shape in a scratch file, watch the exit code go to 1, revert (paste into the commit).
- Remove `unitTests.isReturnDefaultValues = true` from every module that uses Robolectric (all six
  do); run the JVM suite; where a test now fails on an unmocked call, that is a real finding — fix
  the test's setup, never re-enable the flag silently.
- Release build: `signingConfigs { release { … from keystore.properties, all four values read via
  Properties(); the file is untracked } }`, `buildTypes.release { isMinifyEnabled = true;
  isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
  "proguard-rules.pro"); signingConfig = signingConfigs.getByName("release") }`;
  `app/proguard-rules.pro` with `-keep class ai.passioncode.fabricvr.assistant.** { *; }` (kotlinx
  serialization) and the Room rule `-keep class * extends androidx.room.RoomDatabase`; `versionCode`
  from `-PversionCode=` with a default. `./gradlew :app:assembleRelease` must produce a signed APK
  when a keystore is present and a clear message when not.
- `gradle/wrapper/gradle-wrapper.properties`: add `distributionSha256Sum=` from
  `https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256`; commit `gradlew.bat`
  from the same tag; record the wrapper's origin in `README.md`.
- `scripts/install-on-quest.sh`: default `DEVICE` to the first `adb devices` row when unset.
- Spec §1–2 (dated document — add a dated note, do not rewrite): "the Spatial SDK Gradle plugin is
  not applied; the four artifacts are used directly; scenes are created in code (H-12)". DOC-01.

### H-26 — Strings out of Kotlin, an icon, motion tokens used or removed
`Closes:` AUD-52 (part), AUD-54, AUD-58 · `Size:` M · `Depends on:` H-09

Move every user-facing literal in `app/src/main/kotlin/**/ui/*.kt` and `UiStateMapper.kt` into
`res/values/strings.xml` (`core-common` gets its own `strings.xml` for the mapper) with keys
`action.*`, `state.*`, `error.*`, `label.*`; Compose reads them with `stringResource`; the mapper
takes a `Resources` or returns string **ids** (`UiMessage(textRes: Int, args…)`) — choose the ids
form so `core-common` stays Android-resource-light and tests compare ids. Add a real launcher icon
(`mipmap-anydpi-v26` adaptive icon from `assets/brand/` in the `fabric` repo — copy the PassionCode
mark; record its source). Use `Tokens.Motion.calmMs` for the sheet's state transitions
(`AnimatedContent`) or delete the token. `ChatMessage` gains `id: String = UUID…` and `items(key =
{ it.id })`. Update `docs/ux/scenarios.md` `Strings:` lines with the new keys (SCN-004/006/009/010/013).

### H-27 — Documentation reconciliation sweep
`Closes:` DOC-01…04, carry-over row 14 · `Size:` S · `Depends on:` every task above

Walk `docs/modules/*.md`, `docs/evidence/specs/*-design.md` (dated: add "as built" notes, do not
rewrite), `docs/ux/*.md` coverage cells, `README.md`; write the wiki entry
in the maintainer's private wiki (overview +
links) with the `wiki-update` skill; run all four gates.

## WP-6 — Verify on the headset

### H-28 — The device gate, walked and recorded
`Closes:` REQ-001 (beside Virtual Display), REQ-003, REQ-007; every (device-verify) row · `Size:` S · `Depends on:` all

With the headset on: `bash scripts/install-on-quest.sh`; open Meta Virtual Display beside the panel;
walk SCN-001, 002, 003, 004, 005, 006, 008, 009, 010, 012, 013 in order; for each, write the
`Human` date and a one-line `Note` into `docs/evidence/verification.md`; screenshot
(`adb shell screencap -p /sdcard/x.png && adb pull`) into `docs/evidence/screenshots/`; measure and
record the JFK and RU transcription latencies; then run stage 10 of the pipeline (ladder walk,
coverage table, retro) and close every carry-over row with a `B-NNN`.

---

## Task ↔ finding matrix

| Task | AUD ids | DOC | REQ |
|---|---|---|---|
| H-01 | (tests for 01, 02, 20) | — | 003 |
| H-02 | (tests for 05, 13) | — | 008 |
| H-03 | 01, 03, 10, 39, 52(part) | — | 003 |
| H-04 | 02, 08 | — | 003 |
| H-05 | 04 | — | 004 |
| H-06 | 05, 31 | — | 008 |
| H-07 | 06 | — | 003 |
| H-08 | 07, 43 | — | 009 |
| H-09 | 09 | — | 001 |
| H-10 | 11, 38, 40 | — | 006 |
| H-11 | 22, 23, 50, 51 | — | 006 |
| H-12 | 12, 35, 36 | — | 007 |
| H-13 | 13, 28 | — | 002, 008 |
| H-14 | 32, 33 | — | 005 |
| H-15 | 14, 15, 34 | — | 006 |
| H-16 | 16, 27 | — | 004 |
| H-17 | 17, 42(cancel) | — | 010 |
| H-18 | 18, 30 | — | 008 |
| H-19 | 19, 20, 44, 45 | — | 003 |
| H-20 | 26 | — | 006 |
| H-21 | 24, 25 | 02 | 010 |
| H-22 | 37 | — | 003 |
| H-23 | 41, 42, 53 | — | 005, 010 |
| H-24 | 29 | 03 | 008 |
| H-25 | 46, 47, 48, 49, 57 | 01 | 011 |
| H-26 | 52, 54, 58 | — | 011 |
| H-27 | — | 01–04 | 011 |
| H-28 | all device-verify | — | 001, 003, 007 |

Every AUD-01…58 and DOC-01…04 appears in exactly one task above, except AUD-55/56 (Low: RMS naming,
boxing, RIFF chunk walk, call cancel) which are folded into H-16 (call cancel) and H-19 (recorder
buffer as `ShortArray`) — recorded here so the coverage claim is checkable.

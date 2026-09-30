# Plan — v1-notes-core (stage 4)

Spec: `docs/evidence/specs/2026-09-19-v1-notes-core-design.md`. Global Constraints §1 of the spec are
inherited verbatim by every task below. Execution is **inline** (subagents unavailable — the
provider's session spend limit resets later that day), so each task's review is a
self-review against `references/review.md`'s rubric, which is weaker evidence than a fresh
reviewer and is recorded as such.

REQ set check: brief REQs `{001…011}` == union of `Implements:` below.

## Task groups (dependency order; groups share no files)

| Group | Tasks | Depends on |
|---|---|---|
| A — shell skeleton | T1, T2, T3 | — |
| B — notes | T4, T5, T6 | A |
| C — vault | T7 | B |
| D — speech | T8, T9, T10, T11 | A, B |
| E — assistant | T12, T13 | A, B |
| F — surface | T14, T15 | B, D, E |
| G — release | T16, T17, T18 | all |

---

### T1 — Gradle skeleton and the panel that installs
`Implements: REQ-001`

Create `settings.gradle.kts` (six modules + `third_party` excluded), root `build.gradle.kts`,
`gradle/libs.versions.toml` with the pinned versions, `gradle.properties`
(`android.useAndroidX=true`, `org.gradle.jvmargs=-Xmx4g`), the Gradle wrapper 8.13, and
`app/` with the manifest of spec §3.1, `Theme.FabricVR`, `PanelActivity` rendering a placeholder
Compose screen, and `FabricVrApp`.

- **Check:** `./gradlew :app:assembleDebug` exits 0; `adb install -r` exits 0; `adb shell dumpsys
  activity activities | grep fabricvr` shows `PanelActivity` resumed.
- **Test first:** none possible before a module exists — this task's proof is the two commands, run
  and recorded. Every later task is TDD.

### T2 — Design tokens, theme, error taxonomy, mapper
`Implements: REQ-009`

`core-common`: `Tokens.kt` (workbench palette, type scale, spacing, radii), `Theme.kt`
(`FabricTheme`, dark-first), `AppError.kt`, `UiMessage.kt`, `UiStateMapper.kt`, `Logging.kt`
(tag `FabricVR`, a `redact()` that never prints bodies or keys).

- **Test first:** `UiStateMapperTest` — every `AppError` branch maps to a distinct, non-empty text,
  and the three permission/model/key branches carry the right `UiAction`. Watch it fail (no mapper),
  then implement.

### T3 — Secure settings
`Implements: REQ-006`

`SecureSettings` over `AndroidKeyStore` AES/GCM per spec §3.7, plus `Graph.kt` wiring.

- **Test first:** `SecureSettingsTest` (Robolectric) — put/get round-trip, `remove` clears, a
  keystore failure surfaces `AppError.Storage` rather than throwing.

### T4 — Note model, tag parser
`Implements: REQ-005`

`core-notes`: `Note`, `NoteChange`, `Transcript` reference, `TagParser`.

- **Test first:** `TagParserTest` — `#idea` parsed, duplicates collapsed, Cyrillic `#идея` parsed,
  `#` alone ignored, punctuation-terminated tags, lowercasing.

### T5 — Room storage, repository, search, daily note
`Implements: REQ-002, REQ-005`

`NoteEntity`, FTS4 `note_fts`, `NoteDao`, `NotesDatabase`, `RoomNotesRepository`.

- **Test first:** `NotesRepositoryTest` (upsert → observe → get → delete), `SearchTest` (a word only
  in the transcript matches; blank query → empty; tag query matches), `DailyNoteTest` (creates once,
  second call returns the same id).

### T6 — Notes UI: today, editor, search
`Implements: REQ-002, REQ-005`
`Traces: SCN-001, SCN-002, SCN-003, SCN-008`

`TodayScreen`, `NoteEditorScreen`, `SearchScreen` + their ViewModels; navigation in `FabricApp.kt`.
Strings from spec §3.2 and `docs/ux/screens.md`.

- **Test first:** `NotesViewModelTest` — a repository error becomes the mapper's message, not a crash;
  the list state moves loading → success; tag selection filters.

### T7 — Vault mirror
`Implements: REQ-008`

`Vault`, `MarkdownSerializer`, `VaultMirror` subscribing to `observeChanges()` in app scope.

- **Test first:** `MarkdownVaultTest` — `toMarkdown` → `parse` round-trips id, tags, timestamps, body;
  `write` creates `notes/YYYY/MM/<id>.md`; `remove` deletes it; a write failure emits an error and
  does not throw.

### T8 — whisper.cpp JNI with a language parameter
`Implements: REQ-003`

`feature-stt/src/main/cpp/{CMakeLists.txt,fabricvr_whisper.cpp}` per spec §3.5, `WhisperNative.kt`
externals, `WhisperEngine.kt` (single-thread dispatcher, ShortArray → FloatArray `/32768f`).

- **Test first:** `WhisperEngineTest` (instrumented, device) on `jfk.wav` asserting the text contains
  "ask not what your country"; watched failing with no model present (expect `ModelMissing`) before
  the engine exists.

### T9 — Audio capture
`Implements: REQ-003`
`Traces: SCN-004, SCN-013`

`AudioRecorder` (`VOICE_RECOGNITION`, 16 kHz, mono, PCM16), WAV writer for the attachment,
`RECORD_AUDIO` request flow in Compose.

- **Test first:** `WavWriterTest` — header bytes, sample count, 16 kHz mono; `AudioRecorderTest`
  (Robolectric) — a denied permission yields `AppError.Permission`, never a crash.

### T10 — Model store and downloader
`Implements: REQ-010`
`Traces: SCN-006`

`ModelStore`, `ModelDownloader` (progress, SHA-256 verify, partial file deleted on failure),
`ModelDownloadDialog`.

- **Test first:** `ModelDownloaderTest` (MockWebServer) — good body with the right digest → Done and
  the file exists; wrong digest → `ModelDownload(CHECKSUM)` and the file is gone; a dropped stream →
  `ModelDownload(NETWORK)`.

### T11 — Remote whisper client and the router
`Implements: REQ-004`
`Traces: SCN-007`

`RemoteWhisperClient` (multipart `/inference`), `SttRouter` (remote → local fallback, `Source`
marking).

- **Test first:** `SttRouterTest` (MockWebServer) — 200 → `REMOTE`; 500 → `LOCAL_FALLBACK` with the
  local engine called; timeout → same; no URL → `LOCAL`; neither available → `ModelMissing`.

### T12 — OpenRouter client
`Implements: REQ-006`

`SseParser`, `ChatModels`, `OpenRouterClient` (OkHttp SSE, `Authorization`, `HTTP-Referer`,
`X-Title`).

- **Test first:** `SseParserTest` — `: OPENROUTER PROCESSING` is a comment, `data: [DONE]` is the
  terminator, a data line parses to a token, the usage chunk parses to `Usage`.
  `OpenRouterClientTest` (MockWebServer) — a token stream arrives in order; 401/402/429 map to
  `AppError.OpenRouter` with the right status; a body-less 500 maps too.

### T13 — Assistant context and chat UI
`Implements: REQ-006`
`Traces: SCN-009, SCN-010`

`NotesContextBuilder` (budgeted), `Assistant`, `ChatScreen` + ViewModel (streaming, *Stop*, context
line, model name), `SettingsScreen` (key, model, language, server URL, vault path).

- **Test first:** `NotesContextBuilderTest` — matching notes come first, the budget is respected, an
  empty base yields an explicit "no notes" context.

### T14 — Voice capture sheet wired into the editor
`Implements: REQ-003, REQ-004`
`Traces: SCN-004, SCN-005, SCN-006, SCN-007, SCN-013`

`VoiceCaptureSheet` (hold-to-talk, level, transcribing, preview, save/discard) used from
`TodayScreen` and `NoteEditorScreen`; the transcript becomes a note with its audio.

- **Test first:** `VoiceCaptureViewModelTest` — permission denied → the permission state; model
  missing → the download state; empty audio → the "nothing was heard" state; success → a note with
  `audioPath` and `transcript`.

### T15 — Immersive activity (hybrid)
`Implements: REQ-007`
`Traces: SCN-012`

`ImmersiveActivity` (`AppSystemActivity`, `VRFeature` + `ComposeFeature`, one `PanelRegistration`
hosting `FabricApp()`), *Space* on `TodayScreen`, *Back to panel* using the Home `PendingIntent`
pattern from the docs study.

- **Test first:** `HybridIntentTest` (JVM) — the panel→immersive intent carries `ACTION_MAIN` +
  `FLAG_ACTIVITY_NEW_TASK`; the immersive→panel intent is `CATEGORY_HOME` with
  `extra_launch_in_home_pending_intent`. The rendering itself is proven on the device.

### T16 — Suite, lint, secret scan
`Implements: REQ-011`

`scripts/check-secrets.sh` (no `sk-or-`, no key-shaped strings, no `local.properties` in git),
`./gradlew testDebugUnitTest` and `lintDebug` green, the device suite run.

### T17 — Install on the Quest and verify
`Implements: REQ-001, REQ-003, REQ-007`

`adb install -r`, launch, record the panel beside Meta Virtual Display, run the manual checks for
REQ-001/002/003/007, capture a screenshot with `adb shell screencap`.

### T18 — Documentation, wiki, graph
`Implements: REQ-011`

README (build, install, run, side-loading the model), `docs/modules/*.md` per Gradle module, device
facts into `docs/research/local-evidence-2026-09-19.md`, `docs/DOCMAP.md` rows, wiki
entry in the maintainer's wiki, `graphify . --update`, docs gate green.

## No placeholders

Every task above names its files, its check and its test. Nothing is "TBD"; the two unresolved
platform facts (Horizon OS `RECORD_AUDIO` policy, on-device Whisper latency) are **measured in T17**
rather than assumed here.

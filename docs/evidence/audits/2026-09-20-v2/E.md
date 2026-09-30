# Axis E — Architecture, performance, refactoring

Subject: `fabric-vr` @ `b32af38`, branch `feat/v1-notes-core`. Read-only pass over
`app/`, `core-common/`, `core-notes/`, `feature-vault/`, `feature-stt/` (Kotlin + cpp),
`feature-assistant/` — main and test source sets — plus all seven Gradle files,
`gradle/libs.versions.toml`, `docs/modules/*.md` and the two dated specs of 2026-09-19.

Every claim below carries `file:line`. Where a claim is reasoned rather than measured it says so
in the same sentence, because this repository's own rule is that a grade is not a measurement.

---

## Dependency graph (as built)

Read from `settings.gradle.kts:23` and the six `build.gradle.kts` files. Arrows point at the
dependency; `api` means the dependency is re-exported to consumers.

```
                          :core-common
                    (AppError, UiStateMapper, UiMessage,
                     SecureSettings, Log2, NetworkPolicy,
                     Tokens/Theme, ErrorBanner, 24 strings)
                                  ▲
                                  │ api          core-notes/build.gradle.kts:31
                                  │
                           :core-notes
                 (Note, Transcript, SttSource, NoteChange,
                  NotesRepository, Room + FTS4, TagParser)
                     ▲            ▲            ▲
          api        │            │ api        │ api
  feature-vault:25 ──┘            │            └── feature-assistant:26
          │                 feature-stt:50                    │
          │                       │                           │
   :feature-vault            :feature-stt              :feature-assistant
   (FileVault,          (AudioRecorder, WhisperEngine   (OpenRouterClient,
    VaultMirror,         + JNI, ModelStore/Downloader,   NotesContextBuilder,
    MarkdownSerializer)  SttRouter, RemoteWhisperClient, Assistant, SseParser)
          │              CloudTranscriptionClient)
          │                       │                           │
          └───────────────┬───────┴───────────┬───────────────┘
                          │                   │
                        :app  ← core-common, core-notes (app/build.gradle.kts:82-86)
                 (Graph, two activities, SpatialPanelOwners,
                  6 view models, 6 screens, 114 strings)
```

**It is acyclic and no module reaches sideways into a sibling.** `:feature-vault` does not know
`:feature-stt` exists, `:feature-stt` does not know about the vault, and neither knows about
`:feature-assistant`; every cross-feature composition happens in `:app`. That is the shape the
stage-2 module map promised (`docs/evidence/specs/2026-09-19-v1-notes-core-modules.md:35-41`) and it
is the single best structural property this codebase has. The findings below are about what sits
*inside* the boxes, not about the arrows between them.

Four observations about the graph itself:

1. **`Transcript` and `SttSource` live in `:core-notes`, not in `:feature-stt`.**
   `core-notes/.../notes/Note.kt:4` and `:13`. The spec assigns them to stt
   (`…-modules.md:79-80`, "`Transcript` (owner stt; consumer notes-core as a value)"). The
   inversion is why `:feature-stt` depends on `:core-notes` at all — its eight imports from that
   module are *only* `Transcript` and `SttSource` (verified: no other `ai.passioncode.fabricvr.notes.*`
   import exists in `feature-stt/src/main`). A speech module that depends on the notes database to
   obtain the type it produces is backwards, and it drags `Note`, `NotesRepository`, Room and
   `TagParser` onto stt's compile classpath. It is also *harmless today* and costs a module move to
   fix. See E-22.

2. **`:core-common` holds UI, not just "no business logic".** `ui/Components.kt:36` (`ErrorBanner`),
   `theme/Tokens.kt`, `theme/Theme.kt` and 24 strings. That is a deliberate and good choice — the
   banner is the one component every screen must not re-invent — but it means `:core-common` is a
   design-system module, and `core-common/build.gradle.kts:33-35` correctly uses `api` for Compose
   so consumers inherit it. Nothing to change.

3. **`:app` is the only module with a `Graph`.** All five libraries take their collaborators as
   constructor parameters (`RoomNotesRepository(dao, now)`, `FileVault(root, zone, io)`,
   `VaultMirror(repository, vault)`, `WhisperEngine(modelStore, threads, beamSize)`,
   `Assistant(client, contextBuilder, model)`). The service-locator problem is confined to `:app`,
   which is why it is fixable cheaply. See E-04.

4. **App-layer logic living in a feature module, and vice versa** — the three the brief named:
   - `ui/SpeechChoice.kt:15-40` maps `WhisperModel`/`SttProvider` to `@StringRes` ids. **Correct
     placement**, and the file says why (`SpeechChoice.kt:8-14`): the enums carry a stable `key`
     (`WhisperModel.kt:21-25`, `SttProvider.kt:12-19`) and no copy, so the language stays in `:app`.
     The `when` rather than a map makes a new model a compile error until it has a name. Leave it.
     The one thing that is *not* a naming concern is `SpeechChoice.all()` (`:46-49`) — the policy of
     which re-transcription options exist — but it is three lines and moving it buys nothing.
   - `ui/AudioAdoption.kt:16-25` is `Vault.adoptOrKeep`, the "move it, and keep the scratch path if
     the move failed" policy. That is vault policy, expressed as an `internal` extension in `:app`'s
     `ui` package so `:feature-vault` cannot reach it, and both `NotesViewModel.createVoiceNote`
     (`NotesViewModel.kt:249`) and `EditorViewModel.attachTranscript` (`EditorViewModel.kt:68`)
     depend on it. Belongs in `:feature-vault` beside `adoptAudio`. See E-21.
   - **The re-transcription rule lives in a view model** — `NotesViewModel.kt:193-207`, the
     "replace the body only when it is still exactly what the last transcription produced" rule,
     with `Graph.withEngine` and `Graph.sttLanguage` called from the method body at `:186-187`.
     This is the worst placement in the tree and it is the subject of E-04: it is a domain rule, it
     is the only rule in this class that reaches into `Graph` from a body rather than a constructor
     default, and it is the only public method of `NotesViewModel` with no test.

---

## Findings

| ID | Sev | Where | Claim | Cost of leaving it | Proposed change | Blast radius | Verify by |
|---|---|---|---|---|---|---|---|
| E-01 | Blocker | `EditorViewModel.kt:88-93`; `NoteEditorScreen.kt:67`, `:81` | `flush()` writes the pending note on `viewModelScope`, which is cancelled in `onCleared()`. Both callers fire it as the screen is going away: `DisposableEffect(noteId) { onDispose { viewModel.flush() } }` and `onClick = { viewModel.flush(); onBack() }` — the latter pops synchronously while `flush` has only *launched*. When navigation destroys the back-stack entry, the scope dies with the coroutine suspended inside `repository.upsert` → `dao.upsert`, and the last ≤600 ms of typing is gone. | Silent loss of the half-typed thought — the exact failure the class's own KDoc (`:83-87`) says it exists to prevent. Invisible: the note simply has the previous text. | Do the final write on a scope that outlives the view model: pass an application-scoped `CoroutineScope` (`Graph.scope`, `Graph.kt:39`) into `EditorViewModel` as a constructor parameter and use it in `flush()` only. `retry()` (`:96-101`) may stay on `viewModelScope`. | `EditorViewModel.kt`, `Graph.kt` (one new default arg), `EditorViewModelTest.kt`. No screen change. | A test that calls `vm.flush()` and then cancels the view model's scope **before** `advanceUntilIdle()`, asserting the write landed. Today's test (`EditorViewModelTest.kt:69-82`) never clears the view model, so it passes either way. |
| E-02 | Blocker | `AudioRecorder.kt:35`, `:64-69`; `VoiceViewModel.kt:60`, `:66` | The whole recording is accumulated as **boxed** `Short`s. `record(onSamples: (List<Short>) -> Unit)` builds `ArrayList<Short>(read)` per chunk (`:64`) and `VoiceViewModel` appends into `mutableListOf<Short>()` (`:60`). On ART a `java.lang.Short` outside the −128..127 cache is a 16-byte object plus a 4-byte array slot: **~20 bytes per 2-byte sample, ~320 KB per second of audio** against 32 KB/s raw. Arithmetic, not measured. | A 60-second dictation holds ~19 MB of boxed samples and churns the same again per chunk; ten minutes is ~190 MB — alongside whisper's 190–574 MB native context, Compose and an OpenXR session. Nothing bounds the recording length (E-03 makes an unbounded one reachable), so this is an OOM path and the recording dies with the process. | Change the callback to `(ShortArray, Int)` (buffer + count) and accumulate into a growable `ShortArray` or a `ByteArrayOutputStream`; `drainBuffer()` (`:67`) then becomes a single `copyOf`. | `AudioRecorder.kt`, `VoiceViewModel.kt`. **No test touches either** (`VoiceViewModelStateTest` never constructs the view model), so nothing breaks and nothing catches a regression either — add the test with the change. | A JVM test that pushes 16 000 × 60 samples through the buffer and asserts retained size; or `Debug.getNativeHeapAllocatedSize()`/allocation tracking around a 60 s record on the headset. |
| E-03 | High | `VoiceViewModel.kt:86-90`; `TodayScreen.kt:127-138`; `FabricApp.kt:42-52` | Recording runs on `viewModelScope`, which is **not** lifecycle-bound. The Today header's Search / Assistant / Space / Settings buttons are live while recording (`TodayScreen.kt:127-138` — nothing disables them on `VoiceState.Recording`). Navigating away leaves Today's entry on the back stack, so its `VoiceViewModel` survives, `recordJob` keeps reading the microphone, and the buffer keeps growing. `collectAsStateWithLifecycle` stops *observing*; it does not stop *recording*. | The microphone stays hot with nothing on screen saying so — which is precisely the risk `docs/modules/app.md` cites as the reason the product chose hold-to-talk, in a build that no longer holds. Combined with E-02 it is the unbounded growth that makes the OOM reachable. | Stop the recording when the owning screen leaves: a `DisposableEffect` in `TodayScreen`/`NoteEditorScreen` calling `voiceViewModel.stopAndTranscribe(false)` on dispose, or bind `recordJob` to the lifecycle. Second, disable the header actions while `voice is VoiceState.Recording`. | `TodayScreen.kt`, `NoteEditorScreen.kt`, possibly `VoiceViewModel.kt`. No test exists to break. | Record, navigate to Settings, wait 30 s, return: the app must not be in `Recording` with 30 s of samples. Today there is no automated way to see this — that is the finding. |
| E-04 | High | `NotesViewModel.kt:186-187`; `VoiceViewModel.kt:157`; `ChatViewModel.kt:35`, `:52`, `:66`, `:72` | `Graph` is reached from **method bodies** in exactly three places — and those three are exactly the three units with no test. Everywhere else the pattern is right: `Graph` appears only as a constructor default (`NotesViewModel.kt:70,72`; `EditorViewModel.kt:26,27`; `SearchViewModel.kt:30`; `SettingsViewModel.kt:57,62-66`; `VoiceViewModel.kt:40,41,44,45`), which a test can override. `ChatViewModel` has **no constructor parameters at all**. | The correlation is the finding: `retranscribe` (a 47-line domain rule that decides whether to overwrite a person's edited text), `downloadModel` (a 574 MB transfer) and the entire assistant are unreachable from a test because of one reference each. `SettingsViewModel`'s own KDoc (`:51-55`) already records that this is how the previous audit's defects got in. | Finish the job that class started. `NotesViewModel`: add `withEngine: suspend (SttProvider, WhisperModel, suspend (SttEngine) -> T) -> T = Graph::withEngine` and `language: () -> String = Graph::sttLanguage`. `VoiceViewModel`: add `downloader: () -> ModelDownloader = { Graph.modelDownloader() }`. `ChatViewModel`: add `assistant: Assistant = Graph.assistant`, `model: () -> String = Graph::model`. Stop there — a DI framework is not the answer for six view models. | Three files, four signatures, three new test classes. No screen changes: `viewModel()` still uses the defaults. | The three new test classes compile and run without a `Graph`. |
| E-05 | High | `Graph.kt:85-101`, `:151`; `VoiceViewModel.kt:119`, `:131` | `sttEngine()` is rebuilt per call and reads **five** Keystore-backed settings (`:86,87,88,93/94`), `sttProvider()` reads a sixth (`:103`) and `sttLanguage()` a seventh (`:151`) — all on the caller's dispatcher, which is `Dispatchers.Main`: `stopAndTranscribe` launches on `viewModelScope` (`VoiceViewModel.kt:108`) and calls `transcribe(pcm, language())` (`:119`) with `engine()` evaluated at `:131`, neither inside a `withContext`. The project's own measurement is "the first Keystore use costs tens of milliseconds" (`SettingsViewModel.kt:97`). | Seven decrypts on the main thread at the exact instant the person releases Record — a visible hitch on the app's one hot path, in a headset where a dropped frame is felt rather than seen. | Wrap the engine resolution in `withContext(Dispatchers.IO)` inside `VoiceViewModel.transcribe`, or better, give `KeystoreSecureSettings` a value cache invalidated on `put`/`remove` (it already caches the key at `SecureSettings.kt:61`, but not the values). | `VoiceViewModel.kt` (one line) for the cheap fix; `SecureSettings.kt` + `SettingsViewModel` reload for the better one. | `StrictMode.ThreadPolicy` with `detectCustomSlowCalls`, or a Perfetto trace of the main thread across a stop-and-transcribe. |
| E-06 | High | `Graph.kt:71-79`; `WhisperEngine.kt:86-97` | `localEngine()` is `@Synchronized` and, when the chosen model changed, calls `engine.close()` — which does `runBlocking(dispatcher)` on the whisper executor (`WhisperEngine.kt:89`). So a **blocking wait for a possibly-running transcription plus a native free of up to 574 MB happens on whatever thread called `sttEngine()`, which is the main thread** (E-05), while holding the `Graph` monitor. The free itself is correct and well-reasoned (the KDoc at `:78-85` is right about use-after-free); the *thread it runs on* is not. | Best case a main-thread stall for the native free. Worst case: re-transcribe a long recording with model B, switch back to model A in Settings, press Record — `close()` now waits for the in-flight decode. At the measured 1.31× real time for `small`, a 60-second recording is a 78-second main-thread block, i.e. an ANR kill. The race is contrived but the single-threaded `runBlocking` under a lock is not. | Make engine switching suspend rather than block: hold the current engine in a `Mutex`-guarded suspend accessor and `withContext(Dispatchers.IO) { old.close() }`. Or make `sttEngine()` a `suspend fun`, which also fixes E-05. | `Graph.kt`, `VoiceViewModel.kt`, `NotesViewModel.kt` call sites. No test exists. | A test that holds the whisper dispatcher busy and asserts the switch does not block the caller; on device, an ANR trace. |
| E-07 | High | `PanelSmokeTest.kt:20-21` vs `TodayScreen.kt:337`, and `strings.xml:29-30`, `:146` | **The one instrumented test of the panel is RED against this commit.** It asserts `onNodeWithText("Hold to record")` and `onNodeWithText("New note")`. `TodayScreen` renders `R.string.action_record` = "Record" (`strings.xml:146`) and has no New-note control at all; `action_hold_to_record` (`:30`) and `action_new_note` (`:29`) are referenced from nowhere in the tree. The test was last touched at `16a5fb5`; `TodayScreen` was rewritten at `732c92b`, three commits later. | A suite that reads as coverage and is a guaranteed failure. Worse, the next person will "fix" it by relaxing the assertion rather than by asking what the screen should say. | Assert against the resource id, not the literal: `compose.onNodeWithText(context.getString(R.string.action_record))`. Replace the second assertion with something the screen actually has — `R.string.today_empty` or the settings action's `contentDescription`. | `PanelSmokeTest.kt` only. | `./gradlew :app:connectedDebugAndroidTest --tests '*PanelSmokeTest*'` on a Quest. |
| E-08 | High | `TodayScreen.kt:87`, `:110-219`; `AudioRecorder.kt:71` | `val voice by voiceViewModel.state.collectAsStateWithLifecycle()` is read in `TodayScreen`'s **top-level** scope, so every emission recomposes the whole `Column` — header row, error banner, the record control, the confirmation, the undo row and the `LazyColumn` block. `AudioRecorder` emits one `Level` per `AudioRecord.read()` (`:71`), i.e. at the device's minimum-buffer cadence — tens of times a second. `state` (`:86`) is read in the same scope, so any `NotesUiState` field change (including `retranscribing`, which the `items` lambda captures at `:208`) re-runs the list block too. | Full-screen recomposition tens of times a second during the app's core interaction, on hardware where the frame budget is 13.9 ms and the compositor is already contended. Not measured here — but the emission rate is structural and visible in the code. | Stop reading `voice` at the top: pass `voiceViewModel.state` (the flow, or a `() -> VoiceState`) into `RecordControl` and collect inside it, so the recomposition scope is the control. Same for `state.retranscribing` — pass a `busy: (String) -> Boolean` lambda into the item. | `TodayScreen.kt` only, ~15 lines. | Compose's `Recomposition` counts in Layout Inspector, or a `SideEffect { count++ }` in the header while recording. |
| E-09 | High | `Graph.kt:86-99` vs `:105-121` | **Two different rules for "is the cloud configured" live in one object.** `sttEngine()` requires both a non-blank URL and a non-blank key (`:87-90`) and returns `null` otherwise. `cloudClient()`, used only by `withEngine` (`:136`), falls back to `CloudTranscriptionClient.SUGGESTED_BASE_URL` — Groq — when no URL is set (`:106-107`), and requires only the key. `serverClient()` (`:119-121`) likewise duplicates the server branch of `:97`. | Re-transcribing a note "via cloud" sends the person's voice to `api.groq.com` even though the primary path refuses to, because they never configured an endpoint. Two rules about where a recording is sent, in a file whose whole subject is that this is the person's decision. It is also four functions where there should be one. | One private `fun remoteEngine(provider: SttProvider): SttEngine?` used by both `sttEngine()` and `withEngine`. Decide the default-URL question once, in the open. | `Graph.kt` only (~35 lines deleted). Nothing tests it today. | A test over the extracted function asserting that no endpoint configured yields `FailingEngine(NoApiKey)` for both entry points. |
| E-10 | Medium | `WhisperEngine.kt:29`, used at `:66` | `override val name: String = "whisper-small-q5_1"` is a constant, but the engine is constructed with any of five models (`Graph.kt:78`, `:142`; `WhisperModel.kt:21-25`). Every transcript therefore records `engine = "whisper-small-q5_1"` regardless, and `TodayScreen.kt:395-402` prints that string under the note as the receipt of which engine produced the words. | The re-transcription feature exists so a person can tell whether a bigger model is worth waiting for (`TodayScreen.kt:393-394` says exactly that), and the receipt lies for four of the five models. Commit `bb82929` is titled "the app says what it is called". | `override val name: String get() = "whisper-${modelStore.modelName.removePrefix("ggml-").removeSuffix(".bin")}"`, or take the `WhisperModel` and use its `key`. | `WhisperEngine.kt` one line; `WhisperEngineTest`/`WhisperBenchmarkTest` if they assert the name (they do not). | A JVM test constructing `WhisperEngine` over a `TINY` store and asserting `name` contains `tiny`. |
| E-11 | Medium | `NotesViewModel.kt:57-61`, `:119-128`; grep of `state.*` across `app/src/main` | Five of eleven `NotesUiState` fields are written and **never read by any screen**: `tags`, `selectedTag`, `today`, `dayLabel`, `spaceStarting`. Verified by grepping `state.<field>` over `app/src/main/kotlin` — the only reads are `message`, `justDeleted`, `loading`, `notes`, `retranscribing`. `loadDailyNote()` runs at construction (`:89`) and at every midnight tick (`:95`), and `dailyNote()` **creates a row** when absent (`NotesRepository.kt:145-157`) which the mirror then writes to disk (`VaultMirror.kt:39`). | An empty note and an empty Markdown file are created every day, for a feature with no UI. Per year: 365 rows and 365 files the person never asked for, each one a row in `observeNotes()` (E-12) and in the assistant's `recent(40)` window (`NotesContextBuilder.kt:30`), which the assistant then reads as context. Plus `spaceFailed(cause)` discards its parameter (`:143-148`). | Decide: either render the daily note (it is the "Today" in `TodayScreen`) or delete `today`/`dayLabel`/`loadDailyNote`/`midnightTicks` and the `dailyNote` path. Delete `tags`/`selectedTag`/`selectTag` with E-12. Delete `spaceStarting` or render it. | `NotesViewModel.kt` (~40 lines), `NotesViewModelTest.kt:120-134` (the midnight test goes with it), possibly `NotesRepository.dailyNote` + `NoteDao.getOrCreateByDay` + `MIGRATION_1_2`. | `grep -rn 'state\.today\|state\.dayLabel\|state\.tags\|state\.selectedTag\|state\.spaceStarting' app/src/main` returns nothing — that *is* the evidence. |
| E-12 | Medium | `NoteDao.kt:28-29`; `NotesRepository.kt:70-78`; `NotesViewModel.kt:103-114` | `observeTags()` runs `SELECT tags FROM notes` — a full table scan — and then does a `flatMap`/`filter`/`distinct`/`sorted` over every row in Kotlin. It is `combine`d with `observeNotes()` (`:103-106`), so **every** note write re-runs both, and the result lands in `state.tags`, which nothing renders (E-11). | A full table scan plus an allocation-heavy transform per keystroke-debounce of the editor's autosave, for a value that is discarded. It is the cheapest real saving in the tree. | Delete `observeTags` from the `combine`; keep the repository method (it is tested at `NotesRepositoryTest.kt:66`, `:105`) until the tag UI arrives. | `NotesViewModel.kt` (~8 lines), `NotesViewModelTest.kt:45` (the fake's stub can stay). | The `combine` is gone; `NotesRepositoryTest` still green. |
| E-13 | Medium | `app/src/main/res/values/strings.xml` (25 keys, listed below); `Tokens.kt:28,32,55,73,74` | **25 of 114 app string keys and 5 of 24 tokens are referenced from nowhere** (method: grep for `R.string.<key>` and `@string/<key>` over every `.kt` and `.xml` outside `build/`). `core-common`'s 24 strings are all used. Two of the dead tokens are asserted as live in a dated spec: `…-design.md` says "`Tokens.Motion` is in use: `quickMs` eases the hold button's colour, `calmMs` cross-fades the voice sheet's states" — the sheet is gone and `TodayScreen` animates nothing. | The strings are the fossil record of the hold-gesture UI and the voice sheet; leaving them means the next copy pass translates 25 sentences nobody reads, and the resource shrinker cannot drop a `<string>` reached by id. The Motion tokens make a documentation claim false (that is Axis F's to grade; the tokens are mine). | Delete the 25 keys and the 5 tokens. Keep `Motion` only if E-08's fix introduces an animation. | `strings.xml`, `Tokens.kt`. `PanelSmokeTest` references two of them **as literals** — fix E-07 first or the delete is invisible to it. | Re-run the grep; it must return zero. |
| E-14 | Medium | `TodayScreen.kt:223-231`; `NotesViewModel.kt:245-247`, `:197-201`; `EditorViewModel.kt:63` | The convention "a dictation's title is the first 60 characters of its body" is encoded in **four** places with three different expressions of it: `createVoiceNote` writes `title = transcript.text.take(60), body = transcript.text` (`:245-247`); `noteText()` undoes it at read time with `body.startsWith(title)` (`TodayScreen.kt:228`); `retranscribe` re-derives it with `note.title == note.transcript?.text?.take(60)` (`NotesViewModel.kt:197`); `attachTranscript` applies it again with `current.title.ifBlank { transcript.text.take(60) }` (`EditorViewModel.kt:63`). `Note.preview` (`Note.kt:35`) is a fifth, different, answer to "what to show". | Change `60` in one place and the copy button pastes the title twice, or the re-transcription silently declines to update the title. Three of the four have no test between them. | One place, in `:core-notes` beside `Note`: `Note.displayText`, `Note.titleFrom(text)`, and a `Note.isTitleDerived` predicate. `noteText()` in `TodayScreen` becomes a call. | `Note.kt` (+3 members), `TodayScreen.kt`, `NotesViewModel.kt` ×2, `EditorViewModel.kt`. `NotesViewModelTest.kt:86-105` and `EditorViewModelTest.kt:96-123` already cover two of the four and would need to keep passing. | A `NoteTextTest` in `:core-notes` over the four cases `noteText` enumerates, plus the existing view-model tests staying green. |
| E-15 | Medium | `TodayScreen.kt:240-344` vs `NoteEditorScreen.kt:88-118` | Two record controls, and **they have already diverged**. Today's handles `NeedsPermission`, `Recording`, `Downloading` (with progress and cancel), `Failed` (with a banner and five recovery actions) and `NothingHeard`. The editor's handles `NeedsPermission`, `Recording` and `Transcribing` and **nothing else**: a failed dictation in the editor, a missing model, or a download in progress shows a button labelled "Record" and no message whatsoever, because `NoteEditorScreen` never renders `voiceState is VoiceState.Failed`. | A dictation that fails inside the editor is silent — the person presses Record, nothing happens, and there is no banner. That is a first-session bug in the second most likely place to dictate. | Extract `RecordControl` (`TodayScreen.kt:240-344`) into its own file taking `voice` plus the seven callbacks, and use it from both screens. It is already parameterised correctly — only `private` and its file location stand in the way. | New `ui/RecordControl.kt`; `TodayScreen.kt` −105 lines; `NoteEditorScreen.kt` −30 lines. No test touches either. | A Robolectric composition test over `RecordControl` in each of the eight `VoiceState`s asserting a non-blank message. None exists today. |
| E-16 | Medium | `CloudTranscriptionClient.kt:51-109` vs `RemoteWhisperClient.kt:47-97` | The two HTTP transcription clients share, near-verbatim: `withContext(Dispatchers.IO)` + `runCatchingCancellable`, the `WavWriter.toWav` multipart form, the `invokeOnCompletion { call.cancel() }` cancellation hook (`:81` / `:67`), the bounded body read via `source.request(MAX)` + `buffer.snapshot().utf8().take(...)` (`:84-87` / `:69-73`), the `!isSuccessful → RemoteStt(code, body)` throw, the `Json.parseToJsonElement(...)["text"]` extraction, the `Transcript(...)` construction, and the `fold` that classifies the throwable (`:102-108` / `:88-96`). What genuinely differs is four things: the route, the `Authorization` header, three extra form fields, and the language normalisation table. Each also builds **its own `OkHttpClient`** by default (`:40-42` / `:29-31`) — and `Graph` constructs a new one per call (`:91`, `:110`), so a connection pool and a dispatcher thread pool are allocated and discarded per transcription. | ~70 duplicated lines across two files that must be fixed twice. The per-call `OkHttpClient` is the concrete cost: no connection reuse for the cloud path, and two idle thread pools per dictation until GC. | Extract a `MultipartTranscriptionCall` helper in `:feature-stt` holding the shared body, taking route + headers + extra parts + a response mapper. Separately, hoist **one** `OkHttpClient` into `Graph` as a `by lazy` and pass it to both (`Graph.kt:91`, `:110`, `:121`). The client hoist is worth doing even if the extraction is not. | `feature-stt` (+1 file, 2 edited), `Graph.kt`. `CloudTranscriptionClientTest.kt` (6 tests) and `SttRouterTest.kt` already inject a client and would keep working. | Both existing test classes green after the extraction; `assertSame` on the client across two `Graph.sttEngine()` calls. |
| E-17 | Medium | absence of `.github/`, `.gitlab-ci.yml`, or any CI config (checked at the repository root) | **There is no CI.** Nothing runs the 96 JVM tests on a push. `scripts/check-docs.sh` (24 KB) and `scripts/check-secrets.sh` exist and are run by hand. E-07 is the direct consequence: an instrumented test went red three commits ago and nothing said so. | Every finding in this table that ends "verify by a test" is worth less without something that runs it. The suite's value decays from the day it stops being run. | The minimum that is worth having, and nothing more: one GitHub Actions workflow on push and PR, JDK 17, `./gradlew --no-daemon test lint` plus `scripts/check-secrets.sh`. Device tests cannot run on a hosted runner and should be left out rather than faked — say so in the workflow's comment. Add `assembleDebug` only if the NDK build is cached, or it will take 20 minutes. | One new file. No source changes. | The badge is green on a PR that breaks a test, and red. |
| E-18 | Medium | `VoiceViewModelStateTest.kt:23`, `:34` | The only "VoiceViewModel" test **does not touch `VoiceViewModel`**. Both tests rewrite the guard inline — `val stateAfterRelease = if (state !is VoiceState.Recording) state else VoiceState.Transcribing` — and assert on the local variable. It is a test of a copy of the rule. If `stopAndTranscribe` (`VoiceViewModel.kt:100`) loses its guard tomorrow, this test still passes. | It occupies the name under which a real test would be found, so the absence reads as presence. | Delete it and write the real one, which E-04 makes possible: construct `VoiceViewModel` with a fake recorder, a fake engine and a temp `audioDir`, and drive the whole start → record → stop → transcribe → ready → consumed path. | `VoiceViewModelStateTest.kt` (replaced), `VoiceViewModel.kt` (one new default arg from E-04). | The new test fails when the `if (_state.value !is VoiceState.Recording) return` guard at `:100` is deleted. Today's does not. |
| E-19 | Medium | all six `build.gradle.kts` | Each module repeats the same block: `compileSdk = 35`, `minSdk = 34`, the identical `compileOptions`/`kotlin { jvmTarget }` pair, three `sourceSets[...].kotlin.srcDir`, `testOptions { unitTests.isIncludeAndroidResources = true }`, the same five `testImplementation` lines, and a verbatim four-line `resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:...")` with the same three-line comment (`app:117-121`, `core-common:45-49`, `core-notes:44-48`, `feature-vault:33-37`, `feature-stt:64-68`, `feature-assistant:37-41`). ~110 duplicated lines. | An SDK bump is six edits, and the sixth will be forgotten. The stdlib pin in particular is load-bearing (it exists because AGP 8.11.1 pulls a newer stdlib than the compiler) and a module that loses it fails with a metadata-version error nobody will connect to this. | A `build-logic` included build with one `fabricvr.android.library` convention plugin and one `fabricvr.android.app`. | Six build files, one new `build-logic` module. No source changes, no test changes. | `./gradlew build` produces the same artifacts; `./gradlew :app:dependencies` shows the same forced stdlib. |
| E-20 | Medium | `NoteEntity.kt:33-40`; `NoteDao.kt:31-39`, `:67-79`; `NotesRepositoryTest.kt:109-116` | `@Fts4` is declared with no `tokenizer` and no `contentEntity`. Two consequences. (a) Room's default is `simple`, which case-folds **ASCII only** — so a query typed as `Разреш` would not match a note containing `разрешение`, while the lowercase `разреш` does. The test at `:113-115` only ever uses lowercase on both sides, so the suite cannot see it. Stated as unproven: one capitalised assertion settles it either way. (b) Without `contentEntity`, title + body + transcript are stored **twice** — once in `notes`, once in `note_fts` — and the DAO hand-maintains the copy (`:70-78`). | (a) is a first-session bug for the app's stated bilingual purpose: a Russian sentence starts with a capital, and so does the search a person types. (b) doubles the on-disk text and makes every upsert a delete-plus-insert into a second table inside the transaction. | (a) `@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)` + schema v3 + a migration that rebuilds the FTS table. (b) `contentEntity = NoteEntity::class` would remove the duplication and the manual sync, but the column names must match and it is a second migration — **not worth it now**; (a) is. | `NoteEntity.kt`, `NotesDatabase.kt` (+1 migration), `core-notes/schemas/`, `NotesRepositoryTest.kt`. | Add `assertEquals(1, repo.search("Разреш").first().size)` to `NotesRepositoryTest.kt:109` — it either passes today or it does not, and that answers the claim. |
| E-21 | Low | `ui/AudioAdoption.kt:16-25` | `Vault.adoptOrKeep` — vault policy — sits in `:app`'s `ui` package as an `internal` extension, because `:feature-vault` cannot see it there. Its two callers are view models (`NotesViewModel.kt:249`, `EditorViewModel.kt:68`). | Small. It is 10 lines and it is correct. But it is the only piece of vault behaviour outside `:feature-vault`, and it is the one that decides what happens when a recording cannot be moved. | Move it to `feature-vault/.../vault/AudioAdoption.kt` as a public extension. | Two import lines; `NotesViewModelTest.kt:59-84` and `EditorViewModelTest.kt:50-64` fakes are unaffected (they implement `Vault`). | Compiles; `VaultAudioTest` can then cover it directly. |
| E-22 | Low | `Note.kt:4`, `:13`; `feature-stt/build.gradle.kts:50` | `Transcript`/`SttSource` live in `:core-notes`, so `:feature-stt` depends on the whole notes module — Room, DAO, repository, `TagParser` — to obtain the two types it produces. Inverts the spec's stated ownership (`…-modules.md:79-80`). `Transcript` also carries `fallbackReason: AppError` (`Note.kt:19`), so the notes model knows that a speech server can fail. | Nothing today: the graph is still acyclic and `:core-notes` has no stt dependency. It costs a longer compile classpath for stt and it will matter the first time something else wants `Transcript` without wanting Room. | Move both types to `:feature-stt` and make `:core-notes` depend on it — or, cleaner, to `:core-common`, which both already depend on. | `Note.kt` (−18 lines), `feature-stt/build.gradle.kts`, `core-notes/build.gradle.kts`, ~12 import lines. | `./gradlew build`; `:feature-stt:dependencies` no longer lists `:core-notes`. |
| E-23 | Low | `NotesRepository.kt:58-62` | `MutableSharedFlow(replay = 64, extraBufferCapacity = 256)` retains the last 64 `NoteChange.Upserted` — each holding a full `Note` with its body and transcript — for the life of the process. The `replay` is deliberate and correct (`:52-57` explains the late-subscriber bug it fixes), but 64 is far more than the one the fix needs. | Up to 64 full note bodies held indefinitely, and — given that retained dictated text is a live privacy concern — 64 bodies of dictated text in a heap dump. Small, but free to fix. | `replay = 1` is what the documented bug requires (one mirror, one late subscriber). Keep `extraBufferCapacity` and `SUSPEND`. | `NotesRepository.kt` one line; `VaultMirrorTest.kt:83` ("a note saved before the mirror subscribes is still mirrored") is the test that pins the behaviour and must stay green. | `VaultMirrorTest` green with `replay = 1`. |
| E-24 | Low | `VoiceViewModel.kt:116-118`, `:196-204` | The scratch WAV is written to `filesDir/audio` before transcription (`:117`) and deleted only by `cancel()` (`:201`). If the process dies while `Transcribing` — or if the person navigates away and the transcript is never consumed — the file stays. Nothing ever sweeps that directory. | Slow growth of orphaned WAVs in private storage: ~32 KB per second of audio, invisible to the person, on a device where storage is not expandable. | A sweep in `Graph.init`: delete files in `filesDir/audio` older than 24 h. Ten lines, on `Graph.scope`, not on the main thread. | `Graph.kt`, `FabricVrApp.kt` unchanged. | A test over the sweep function with a fake clock. |
| E-25 | Low | `NotesViewModel.kt:143-148` | `spaceFailed(cause: Throwable)` never uses `cause`; it maps every failure to the same `R.string.state_space_failed` with `UiAction.RETRY`. Everywhere else in the codebase a throwable is classified through `toMessage()` (`:258-268`). | The one failure path that discards its reason, in a codebase whose stated rule is that nothing is swallowed. If the Space fails for a reason other than a missing VR category, the person is told to retry forever. | `_state.value = _state.value.copy(spaceStarting = false, message = cause.toMessage())`, or at minimum `Log2.w("space.failed", "cause" to cause::class.java.simpleName)`. | `NotesViewModel.kt` one line. | A test asserting a non-generic message for a non-`ActivityNotFoundException`. |

**Counts: Blocker 2, High 7, Medium 11, Low 5 — 25 findings.**

---

## Dead code inventory

Method: grep for `R.string.<key>` and `@string/<key>` across every `.kt` and `.xml` outside
`build/` and `.cxx/`; grep for each symbol across `src/main` and `src/test`/`src/androidTest`
separately. Counts are exact as of `b32af38`.

### Unused string resources — `app/src/main/res/values/strings.xml`: **25 of 114**

| Line | Key | Why it is dead |
|---|---|---|
| 20 | `action_close` | the banner uses `core-common`'s `action_dismiss` (`Components.kt:58`) |
| 22 | `action_discard` | the discard step went with the voice sheet |
| 26 | `action_try_again` | superseded by `core-common`'s `action_retry` |
| 28 | `action_write_instead` | the "write instead" branch no longer exists |
| 29 | `action_new_note` | **asserted by `PanelSmokeTest.kt:21`** — see E-07 |
| 30 | `action_hold_to_record` | **asserted by `PanelSmokeTest.kt:20`** — the hold gesture is gone |
| 39 | `action_rerun_language` | `VoiceViewModel.retranscribe(language)` has no UI |
| 42 | `label_today_note` | the daily note is never rendered (E-11) |
| 46 | `label_voice_note` | |
| 47 | `label_transcript` | |
| 51 | `label_voice_note_icon` | |
| 63 | `state_keep_holding` | hold gesture |
| 64 | `state_listening` | hold gesture |
| 65 | `state_release_to_finish` | hold gesture |
| 69 | `state_allowed` | the only renderable copy for `VoiceState.Allowed` — see below |
| 73 | `state_space_starting` | `spaceStarting` is never rendered (E-11) |
| 92 | `voice_getting_model` | superseded by `state_getting_model` (`TodayScreen.kt:334`) |
| 93 | `voice_release_to_stop` | hold gesture |
| 94 | `tag_hash` | the editor inlines `"#$it"` (`NoteEditorScreen.kt:156`) |
| 105 | `stt_source_local` | `SttSource` is never shown to the person |
| 106 | `stt_source_remote` | idem |
| 107 | `stt_source_local_fallback` | idem — **so `SttRouter`'s carefully visible degradation (`SttRouter.kt:30`) is invisible** |
| 110 | `state_retranscribed` | `RetranscribeResult` is discarded — see below |
| 111 | `state_retranscribed_kept` | idem |
| 115 | `action_start_record` | superseded by `action_record` (`:146`) |

`core-common/src/main/res/values/strings.xml`: **0 of 24 unused.**

### Dead states and dead returns

- **`VoiceState.Allowed`** (`VoiceViewModel.kt:29`) — written once, at `:145`, on a granted
  permission. No screen renders it: `TodayScreen`'s `when` falls through to `else -> Unit`
  (`:306`) and the button's `when` falls to `else -> onStart()` (`:318`); `NoteEditorScreen` never
  mentions it. It is behaviourally identical to `Idle`. Its string (`state_allowed`) is dead too.
  **Delete the state and go straight to `Idle`** — or, better, make granting the permission start
  the recording, which is what the state was presumably reaching for.
- **`RetranscribeResult`** (`NotesViewModel.kt:52`) — a four-case enum, returned from
  `retranscribe`, documented at `:160-163` as the thing that lets the screen say "your edited text
  was kept". The only caller discards it: `scope.launch { notesViewModel.retranscribe(...) }`
  (`TodayScreen.kt:213`). Both of its strings are dead. **Either render it or delete the enum.**
  Rendering it is the right call — the whole point of the rule at `:193` is that the person is told.

### Unused public members

| Symbol | Declared | Callers |
|---|---|---|
| `VoiceViewModel.retranscribe(language)` | `VoiceViewModel.kt:124` | none |
| `VoiceViewModel.retryLast()` | `VoiceViewModel.kt:178` | none |
| `NotesViewModel.createNote()` | `NotesViewModel.kt:150` | none (no "new note" control exists) |
| `NotesViewModel.selectTag(tag)` | `NotesViewModel.kt:130` | none |
| `NotesViewModel.clearJustDeleted()` | `NotesViewModel.kt:240` | none |
| `NotesUiState.tags` | `:57` | written `:111`, read nowhere |
| `NotesUiState.selectedTag` | `:58` | written `:113`, read nowhere |
| `NotesUiState.today` | `:59` | written `:123`, read nowhere |
| `NotesUiState.dayLabel` | `:60` | written `:94`,`:123`, read nowhere in `src/main` (read by `NotesViewModelTest.kt:126`) |
| `NotesUiState.spaceStarting` | `:61` | written `:141`,`:144`, read nowhere |

### Unused design tokens — 5 of 24

`Tokens.Space.xs` (`:28`), `Tokens.Space.xl` (`:32`), `Tokens.Radius.s` (`:55`),
`Tokens.Motion.quickMs` (`:73`), `Tokens.Motion.calmMs` (`:74`). The last two are asserted as live
in `docs/evidence/specs/2026-09-19-v1-notes-core-design.md` ("`quickMs` eases the hold button's
colour, `calmMs` cross-fades the voice sheet's states"); there is no animation anywhere in
`app/src/main` — `grep -rn 'animate\|Animatable\|Crossfade' app/src/main` returns nothing.

### Unused test helpers

- `VoiceViewModelStateTest.kt` in its entirety (E-18) — 38 lines that exercise no production code.
- `ImmersiveActivity.composedAtLeastOnce` (`:172`) is production code existing only for
  `ImmersiveLaunchTest.kt:34,40,63`. That is **justified** and the KDoc says why; noted so it is not
  mistaken for dead.
- `scripts/push-model-for-tests.sh` supports `WhisperEngineTest`/`WhisperBenchmarkTest`, which
  cannot run without a headset and a 190 MB model. Alive, but only by hand.

**Total removable: 25 strings, 5 tokens, 1 enum + 2 strings, 1 state + 1 string, 5 methods,
5 state fields, 1 test class — roughly 200 lines, none of it load-bearing.**

---

## Test architecture

### What exists

96 JVM test methods across 20 classes, plus 6 instrumented classes. The distribution is the
finding: the libraries are well covered, the shell is not.

| Module | JVM tests | Assessment |
|---|---|---|
| `:core-notes` | 17 (`NotesRepositoryTest` 11, `TagParserTest` 6) | Good. Round-trip, FTS, tag removal, the day-note race under `Dispatchers.Default`, the change stream. Robolectric + in-memory Room. |
| `:core-common` | 19 | Good. `UiStateMapperTest` asserts *which* id, and `UiStringsTest` (instrumented) resolves them all against a real table — the split is right and the KDoc explains it (`UiStateMapper.kt:7-9`). |
| `:feature-vault` | 14 | Good. Round-trip, YAML hostility, cross-month deletion, adoption, the late-subscriber case. |
| `:feature-stt` | 27 | Good. MockWebServer for both clients, eight downloader cases including redirect refusal and resume, six router cases. |
| `:feature-assistant` | 24 | Good. SSE edge cases, backpressure (the 335-of-400-tokens regression), retry honouring `Retry-After`. |
| `:app` | **21, of which 2 test nothing** | The gap. |

### What has no test at all, and why it cannot be tested as written

| Unit | Why |
|---|---|
| **`ChatViewModel`** (124 lines) | No constructor parameters (`:35`). `Graph.model()` at `:52`,`:66` and `Graph.assistant` at `:72` are unconditional statics. Constructing it in a JVM test initialises `Graph`, which requires `appContext`, which requires an `Application`. Everything interesting — the `ChatTurn` id monotonicity (`:44-46`), `finish()` dropping a blank partial (`:120`), `retry()`'s `dropLastWhile` (`:106-109`), the `CancellationException` rethrow at `:74` — is unreachable. **Cause: E-04. Cost to fix: two constructor parameters.** |
| **`VoiceViewModel`'s real recording flow** | Constructible (all six deps are parameters, `:40-45`), but `downloadModel()` calls `Graph.modelDownloader()` from the body (`:157`), so the download path is unreachable. The record→stop→transcribe path *is* reachable today with a fake `AudioRecorder` — nobody wrote it. The `MIN_SAMPLES` guard (`:111`), the `bufferLock` (`:59`, the fix for a lost `ConcurrentModificationException`), the `cancelAndJoin` ordering (`:109`), `cancel()` deleting the WAV (`:201`): all untested. |
| **`NotesViewModel.retranscribe`** (47 lines, the longest method in `:app`) | `Graph.withEngine` (`:186`) and `Graph.sttLanguage` (`:187`) from the body. The rule it enforces — "do not overwrite a person's edited text" — is the most consequential decision in the class and is verified by nothing. **Cause: E-04.** |
| **`ImmersiveActivity.placePanel`** (`:189-202`) | A **pure function** over `Pose` — trivially testable in principle. It is unreachable because it sits in a `companion object` of a class extending `AppSystemActivity`, so touching it loads `ImmersiveActivity`, then `VrActivity`, then the Spatial SDK's native init. Move it to a top-level `PanelPlacement.kt` and it becomes four assertions: forward, behind, straight up (the `flatLength < 1e-4f` degenerate branch at `:192`), and the yaw sign. That degenerate branch and the yaw convention are exactly the two things the KDoc says were previously guessed. |
| **`SettingsScreen` composition** (290 lines, the largest screen) | No composition test anywhere in `:app` except `PanelSmokeTest`, which is red. `SettingsViewModel` is well tested (8 methods) but the screen that consumes 20 state fields is not. |
| **`NavHost` routes** (`FabricApp.kt:41-79`) | Five routes, one with a `{id}` argument, and one conditional control (`onEnterSpace?.let`, `TodayScreen.kt:129`). A wrong route string is a runtime crash. Testable with `createComposeRule` + a `TestNavHostController`; nothing does. |
| **`SpatialPanelOwners`** (`:31-57`) | The four-owner lifecycle bridge, the fix for the crash that took the Space down on every entry. `onCreate`/`onResume`/`onPause`/`onDestroy` transitions and `viewModelStore.clear()` are pure JVM-testable state machine work over `LifecycleRegistry` — untested. `ImmersiveLaunchTest` covers "it does not crash on a device", not "the states are correct". |
| **`Graph`** | Every branch of `sttEngine()` (`:85-101`), the engine cache (`:71-79`), `withEngine`'s three provider paths (`:135-149`). An `object` with a `lateinit` context is not constructible twice, so even a Robolectric test cannot reset it between cases. This is the structural argument for E-04: the container is untestable *by construction*, so anything reached through it is too. |

### Which instrumented tests are RED against this commit

- **`PanelSmokeTest` — RED, both assertions.** `onNodeWithText("Hold to record")`
  (`:20`) and `onNodeWithText("New note")` (`:21`). `TodayScreen` renders
  `R.string.action_record` = "Record" (`strings.xml:146`, chosen at `TodayScreen.kt:337`) and has
  no new-note control; both literals correspond to keys referenced from nowhere
  (`strings.xml:29-30`). `git log` puts the test at `16a5fb5` and the `TodayScreen` rewrite at
  `732c92b`, three commits later. This is a certainty, not an estimate: the strings the test looks
  for are not in the tree.
- `ImmersiveLaunchTest` — cannot be judged from here; it depends on a paired headset and on
  whether the shell defers the launch. Its second assertion (`:60-64`) was added at `421377e`
  precisely because the first one passed over a Space that never opened. Sound design.
- `ModelDownloadReachabilityTest` — depends on Hugging Face's live CDN. It is the right test for
  the defect it guards (`4bddfcc`) and it will go red the day the vendor moves, which is the point.
- `WhisperEngineTest` / `WhisperBenchmarkTest` — need a 190 MB model pushed by
  `scripts/push-model-for-tests.sh`; not runnable in CI, correctly out of the JVM suite.
- `SecureSettingsTest`, `UiStringsTest` — device-only by necessity (Keystore, resource table), both
  well-founded.

### Would the JVM suite catch a regression in each view model?

| View model | Caught? |
|---|---|
| `EditorViewModel` | **Partly.** 6 tests cover autosave, flush, transcript attach, vault adoption and its failure. They would **not** catch E-01, because no test cancels the scope. |
| `NotesViewModel` | **Partly.** 4 tests cover voice-note creation, vault refusal, midnight and a failed save. `retranscribe`, `delete`, `undoDelete`, `retry` and `observe`'s `catch` branch are uncovered. |
| `SearchViewModel` | **Yes.** 4 tests cover blank→recent, debounce, clear and a failing index. |
| `SettingsViewModel` | **Yes.** 8 tests cover the URL policy, key handling, model removal, a failing Keystore and the vault counter. This is the model for the others — and its KDoc (`:51-55`) says so explicitly. |
| `VoiceViewModel` | **No.** See above; the one test file tests a copy of one `if`. |
| `ChatViewModel` | **No.** Not constructible. |

### CI

There is none — no `.github/`, no `.gitlab-ci.yml`, nothing at the root. See E-17 for the minimum.
The one thing to add beyond `test` is `scripts/check-secrets.sh`, which already exists and already
runs in a second.

---

## Performance — worth it / not worth it

Ordered by expected return, with the honest answer where the answer is "leave it".

### Worth fixing now

1. **The boxed-`Short` recording buffer (E-02).** ~10× memory for the one thing the app does, on a
   device that also holds 190–574 MB of whisper weights. One afternoon, two files, no test to
   break. The highest return in the tree.
2. **The whole-screen recomposition while recording (E-08).** Tens of full recompositions a second
   of a `LazyColumn`-bearing tree, during the interaction the product exists for. ~15 lines in one
   file.
3. **Seven Keystore decrypts on the main thread per dictation (E-05).** One `withContext` fixes the
   symptom; a value cache in `KeystoreSecureSettings` fixes the cause and also speeds up Settings.
4. **`observeTags()`'s table scan per note change (E-12).** A full scan plus an allocating transform
   for a value nothing renders. Deleting it is strictly free.
5. **The per-call `OkHttpClient` (E-16, second half).** `Graph.sttEngine()` builds a new one on
   every dictation (`Graph.kt:91`), so no connection is ever reused for the cloud path and two
   thread pools are allocated per transcription. One `by lazy` in `Graph`.
6. **Startup work in `Graph.init` (`:163-166`) — check, then almost certainly leave.** It runs on
   the main thread from `FabricVrApp.onCreate` (`:8`) and touches `appContext`, then
   `vaultMirror.start(scope)`, which forces the `vaultMirror` → `notes` → `database` lazies.
   `Room.databaseBuilder(...).build()` does **not** open the file (Room opens on first query), and
   `FileVault` and the `SupervisorJob` scope are trivial — so cold start is probably clean. The
   thing actually worth checking on device is the **first** `Graph.settings` touch, which is the
   Keystore, and that happens from `SettingsViewModel.reload()` and `ChatViewModel.init` (both
   correctly on IO) *and* from `VoiceViewModel`'s hot path (not — that is E-05). Measure with
   `adb shell am start -W` before changing anything here.

### Not worth fixing now — and why

- **`noteText()` recomputed per recomposition and per row** (`TodayScreen.kt:210`, `:388`). It is a
  `trim()`, a `trim()` and a `startsWith` over a note body. `LazyColumn` composes only visible rows
  — six or seven on a 640 dp panel — and note bodies are dictation-length. **Not worth it.** A
  `remember(note)` would buy microseconds and add a cache-invalidation bug surface. The *real*
  problem with `noteText` is that the rule is written four times (E-14), which is correctness, not
  speed.
- **`observeNotes()` is unbounded** — `SELECT * FROM notes ORDER BY updatedAt DESC`
  (`NoteDao.kt:13`), no `LIMIT`, every row's full body mapped to a `Note` on every change. This is
  a real ceiling, but the corpus is one person's dictations: after a year of heavy use, low
  thousands of rows of a few hundred bytes each. **Not worth it now**; revisit when the list is
  paged, which it must be before the note count reaches the thousands. Note that E-11's daily-note
  creation adds 365 empty rows a year to this query for no benefit — fixing E-11 is the cheap half.
- **The FTS query shape** — `SELECT notes.* FROM notes JOIN note_fts ON note_fts.noteId = notes.id
  WHERE note_fts MATCH :query ORDER BY notes.updatedAt DESC` (`NoteDao.kt:31-39`), no `LIMIT`. The
  join is on the primary key and FTS4 does the selective work first; the 120 ms debounce
  (`SearchViewModel.kt:42`) bounds the query rate. **Not worth it.** The `searchOnce` variant used
  by the assistant already has its `LIMIT` (`:44-53`). The tokenizer, by contrast, *is* worth
  looking at (E-20) — that is correctness wearing a performance costume.
- **`VaultMirror` writing a file on every autosave.** `EditorViewModel` debounces at 600 ms
  (`:113`), each save emits a `NoteChange` (`NotesRepository.kt:94`) and the mirror rewrites the
  whole Markdown file (`VaultMirror.kt:39` → `Vault.kt:87`). Continuous typing is therefore roughly
  1.7 file writes per second of a few-KB file, on flash. **Not worth it.** The debounce is the
  throttle, and adding a second one would delay the durability the vault exists to provide. The one
  thing to watch is the `SUSPEND` backpressure at `NotesRepository.kt:61`: a slow vault suspends the
  *writer*, by design ("a slow mirror must slow the writer down, never lose a note"), and with
  64 + 256 slots it would take 320 pending changes to bite. Correct as built.
- **whisper's native memory resident for the process lifetime.** `WhisperEngine` keeps `ctx` alive
  deliberately (`:78-85`, `DEC-0007`) because a reload costs ~2 s, and `Graph.loaded` (`:64`) keeps
  one engine. That is the right trade and the code closes the old context when the model changes
  (`Graph.kt:76`). **Not worth changing** — but two adjacent facts deserve naming rather than
  fixing: (a) `withEngine`'s throwaway branch (`:142-147`) loads a **second** set of weights
  alongside the resident one, so re-transcribing with `medium` while `small` is loaded peaks at
  539 + 190 = 729 MB of native memory plus Compose plus an OpenXR session — bounded and transient,
  but it is the app's memory high-water mark and nothing measures it; (b) the free of that memory
  happens on the main thread (E-06), which *is* worth fixing.
- **`Log2` string building on hot paths.** **There are none.** All twelve `Log2` call sites in
  `src/main` are on failure paths: `AudioAdoption.kt:21`, `SecureSettings.kt:78`,
  `NotesRepository.kt:164`, `Vault.kt:77,99,127`, `ModelDownloader.kt:87,143`, `SttRouter.kt:24`,
  `WhisperEngine.kt:72`, `RetryInterceptor.kt:25`, `OpenRouterClient.kt:80`. `Log2.line()`
  (`Logging.kt:48-50`) does build a string eagerly with no level guard, but it is never called in a
  loop, per frame, or per audio chunk. **Nothing to do.** Stated because the absence is the answer.
- **The download's progress throttle** (`ModelDownloader.kt:129`) already batches emissions to one
  per `PROGRESS_STEP_BYTES` with the reasoning written down. Correct; leave it.
- **Gradle build performance.** `gradle.properties` has `parallel` and `caching` on but **not**
  `org.gradle.configuration-cache=true` and not `org.gradle.unsafe.configuration-cache`. Worth one
  line and a trial run — but the NDK build of whisper.cpp dominates a clean build regardless, and
  that is already cached by CMake. Low priority, and out of scope for the product's performance.

---

## Refactoring, ranked

The five that most reduce the chance of the next bug, by expected defects prevented per hour.

### 1. Move the last three `Graph` reaches into constructors (E-04)

**What:** four signatures — `NotesViewModel(… withEngine, language)`,
`VoiceViewModel(… downloader)`, `ChatViewModel(assistant, model)`.
**Blast radius:** three files, ~10 lines of production code. No screen changes, because
`viewModel()` keeps using the defaults. Three *new* test classes; nothing existing breaks.
**Why now:** this one change converts three untestable units — the re-transcription rule that
decides whether to overwrite a person's text, the 574 MB download, and the entire assistant —
into testable ones, and every other item on this list gets cheaper afterwards. `SettingsViewModel`
already did exactly this and wrote down why (`:51-55`); this is finishing a job the codebase
started and stopped three-quarters of the way through. There is no argument for later: the cost
is an hour and it is the precondition for items 2, 3 and 5.

### 2. Fix the flush-on-a-dying-scope (E-01) and the boxed audio buffer (E-02)

**What:** `flush()` onto an application scope; `AudioRecorder`'s callback to `ShortArray`.
**Blast radius:** `EditorViewModel.kt` + `Graph.kt` (one default arg) and
`AudioRecorder.kt` + `VoiceViewModel.kt`. One existing test (`EditorViewModelTest.kt:69`) needs a
scope-cancellation step added; nothing else touches either path.
**Why now:** these are the only two findings that lose the person's data, and they sit on the two
paths the product is *for* — typing a note and speaking one. Both are ~20 lines. Later is worse
than now for a specific reason: the flush bug is invisible when it fires (the note just has older
text), so it will be reported as "sometimes it doesn't save", which is the hardest class of bug to
reproduce after the fact.

### 3. Extract `RecordControl` and give it a composition test (E-15)

**What:** lift `TodayScreen.kt:240-344` into `ui/RecordControl.kt`; call it from both screens.
**Blast radius:** one new file, two edited; −135 lines net. No view-model change. First composition
test in `:app` that is not red.
**Why now:** the two copies have *already* diverged, and the divergence is a silent failure — a
dictation that fails inside the editor shows no message at all, because `NoteEditorScreen` renders
no `Failed` branch. Every future voice state has to be added twice or the editor falls further
behind. The component is already correctly parameterised (seven callbacks, no `Graph`), so the
extraction is mechanical. Doing it later means doing it after the next state is added twice.

### 4. Make the "title is a prefix of body" rule one function in `:core-notes` (E-14)

**What:** `Note.displayText`, `Note.titleFrom(text)`, `Note.isTitleDerived` beside `Note`, and four
call sites become calls.
**Blast radius:** `Note.kt` (+3 members), `TodayScreen.kt`, `NotesViewModel.kt` (×2),
`EditorViewModel.kt`. `NotesViewModelTest.kt:86-105` and `EditorViewModelTest.kt:96-123` already
pin two of the four behaviours and must stay green; one new `NoteTextTest` in `:core-notes` covers
the rule directly, for the first time, in the module that owns the type.
**Why now:** four encodings of one invariant, with the `60` literal written three times and the
"is this title derived?" predicate expressed two different ways. Three of the four have no test
between them, and the one in `retranscribe` (`NotesViewModel.kt:197`) guards whether a person's
edit survives. Not urgent — nothing is broken today — but the cost only grows, and item 1 makes
the fourth call site testable, so doing it right after item 1 is efficient.

### 5. Add CI, then delete the dead weight (E-17, E-07, E-13, E-11, E-18)

**What:** one GitHub Actions workflow (`./gradlew test lint` + `check-secrets.sh`); fix
`PanelSmokeTest` to assert resource ids; delete 25 strings, 5 tokens, 5 methods, 5 state fields,
`VoiceState.Allowed`, and either render or delete `RetranscribeResult`.
**Blast radius:** one new file; `strings.xml`, `Tokens.kt`, `NotesViewModel.kt`,
`VoiceViewModel.kt`, `PanelSmokeTest.kt`, `VoiceViewModelStateTest.kt`. ~200 lines removed.
**Why now, in this order:** CI first, because everything above is worth less without something that
runs it, and because E-07 proves the point — an instrumented test has been red for three commits
and nothing said so. The deletions second, because a dead string that a red test asserts on
(`action_hold_to_record`) cannot be deleted safely until the test is fixed. This is the lowest-risk
item on the list and the one most likely to be skipped; it is ranked fifth rather than dropped
because a codebase whose strings file is 22% fossil teaches the next reader that the file is not to
be trusted.

**Deliberately not on this list, with reasons:**

- **A DI framework (Hilt/Koin).** Six view models and eleven singletons. `Graph` plus constructor
  defaults is the right size, and the pattern already works in four of six view models. Adding a
  framework would be a large diff that fixes nothing item 1 does not.
- **The convention plugin (E-19).** ~110 duplicated build lines is real debt with a real cost —
  but it changes no product behaviour and prevents no user-visible bug. Do it at the next SDK bump,
  when the six-edit cost is being paid anyway.
- **Moving `Transcript` out of `:core-notes` (E-22).** Correct, and worth doing the day something
  other than the notes DB wants the type. Today it is a 12-import churn for a cleaner diagram.
- **FTS `contentEntity` (E-20b).** Two schema migrations for storage the device has. The
  *tokenizer* half of E-20 is a different matter and belongs with the product work, not here.

---

## Not covered

Named so the merge does not read silence as coverage.

- **The C++ and JNI layer beyond its Kotlin seam.** `feature-stt/src/main/cpp/fabricvr_whisper.cpp`
  (173 lines) and `CMakeLists.txt` were read for build configuration and for the memory-lifetime
  claims in E-06 and the performance section, not audited for correctness — no JNI reference
  leaks, no `GetPrimitiveArrayCritical` misuse, no `whisper_full_params` review. That is a
  specialist pass and a different axis.
- **Whether `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` (`feature-stt/build.gradle.kts:22`)
  actually selects the intended kernels.** The reasoning in the comment is sound; verifying it
  needs `objdump` on the built `.so` and a benchmark run on the headset.
- **Anything requiring a build or a device.** No Gradle task was run and nothing was installed, per
  the audit's read-only constraint. Every "RED" claim above is derived from source comparison, not
  from a run; E-07 is a certainty because the asserted strings are absent from the tree, and the
  others are labelled as inference where they are.
- **Actual memory and frame numbers.** E-02's ~320 KB/s is arithmetic over ART's object layout, not
  a heap dump. E-08's "tens of times a second" is derived from `AudioRecord`'s buffer cadence, not
  a Perfetto trace. Both need a device to become measurements, and both are structural enough that
  the measurement will confirm rather than surprise.
- **Third-party code.** `third_party/whisper.cpp` (a pinned submodule) was excluded from every
  count and every grep.
- **The Meta Spatial SDK's own behaviour** — panel registration, `AppSystemActivity`'s lifecycle,
  whether `ComposeFeature` re-creates the panel on resume. `SpatialPanelOwners` is audited as
  Kotlin; whether it matches what the SDK actually does can only be established on hardware.
- **Everything the other five axes own.** Security (the Keystore, the URL policy, cleartext),
  correctness of the UX flows against `docs/ux/scenarios.md`, copy, documentation truth, release
  and signing. Where this report touches them — E-09's Groq default, E-13's stale spec claim,
  E-20's Cyrillic search — it is because the architecture is the cause; the grading belongs to the
  axis that owns them.

---

## Notes for the merge

1. **Two Blockers, and they are not the ones an architecture axis usually produces.** E-01
   (autosave flush on a scope that is being cancelled) and E-02 (the recording held as boxed
   `Short`s) both lose the person's data on the two paths the product exists for. Neither is a
   design disagreement; both are ~20-line fixes. If the merge produces one task, make it these two.

2. **E-04 is the load-bearing finding and should be sequenced first even though it is High, not
   Blocker.** Three `Graph` references in method bodies — `NotesViewModel.kt:186-187`,
   `VoiceViewModel.kt:157`, `ChatViewModel.kt:52/66/72` — correlate *exactly* with the three units
   that have no test. That is not a coincidence to note; it is a causal chain, and four constructor
   parameters break it. Several other findings (E-06, E-09, E-18, and the verification step of
   E-14) become cheap only after it lands.

3. **`PanelSmokeTest` is red and has been for three commits (E-07).** Any axis that cites the
   instrumented suite as evidence of anything should be read with that in mind. It is a two-line
   fix and it should not be bundled into a larger task, because it is also the gate that makes the
   dead-string deletion (E-13) safe.

4. **Expect overlap with other axes on three items, and let this axis own the cause, not the
   grade.** E-09 (a re-transcription "via cloud" defaults to `api.groq.com` when nothing is
   configured) is a privacy question owned by the security axis — the architectural fact is that
   two contradictory configuration rules live in one file, `Graph.kt:86-99` and `:105-121`. E-20
   (FTS4's `simple` tokenizer versus Cyrillic capitals) is a product-correctness question — the
   architectural fact is that one line of the schema decides it and one added assertion settles it.
   E-13's dead `stt_source_local_fallback` means `SttRouter`'s deliberately visible degradation
   (`SttRouter.kt:30`) reaches no screen — a UX finding with an architectural cause.

5. **The module graph is genuinely good and should not be touched.** Acyclic, no sibling reaches
   sideways, every library takes its collaborators as constructor parameters, and the one service
   locator is confined to `:app`. Whatever the other axes find, the seams are not the problem, and
   a proposal to reshape them should be met with this paragraph.

6. **Three tests in this repository are worth copying rather than changing**, and a merge that
   proposes a testing standard should point at them: `SettingsViewModelTest` (the injection pattern
   and the `io` dispatcher parameter), `ImmersiveLaunchTest` (which asserts the Space *composed*,
   not merely that nothing crashed — `421377e` is the commit that learned the difference), and
   `core-common`'s split between `UiStateMapperTest` (which id) and `UiStringsTest` (does the id
   resolve to words). The counter-example to name in the same breath is
   `VoiceViewModelStateTest`, which tests a retyped copy of an `if`.

7. **No CI exists.** Every "verify by" cell above assumes something will run the check. One
   workflow file is the whole remedy, and it is the difference between the other five axes'
   findings being fixed once and being fixed repeatedly.

# Module map — Fabric VR v1 (notes core)

Stage-2 artifact (task-pipeline). Build order is top to bottom. Status: `planned` →
`in progress` → `done` | `deferred`. Deploy cadence (brief): **once at the end** — one
debug APK installed on the connected Quest 3 after module 6.

## Design summary (approved design = these decisions)

- **Shape:** one Android Gradle project, **multi-module**, one Gradle module per brick so the
  seams are compile-time and each brick's tests run alone: `:app` (shell), `:core-notes`,
  `:feature-vault`, `:feature-stt` (Kotlin + C/C++ JNI for whisper.cpp), `:feature-assistant`.
  Shared tiny module `:core-common` for `AppError`, `Result` helpers, logging tag and
  design tokens (theme) — no business logic.
- **Runtime:** Kotlin, Jetpack Compose UI, Meta Spatial SDK 0.14.x for the hybrid immersive
  activity (versions locked at stage 3 from fetched docs), Room + FTS for the index, files for
  the Vault, OkHttp for HTTP/SSE, Android Keystore (AES/GCM) for secrets, Kotlin coroutines +
  `Flow` for all async.
- **Data flow:** UI → ViewModel → repository (`:core-notes`) → Room; every note change is
  emitted as a `Flow`; `:feature-vault` subscribes and mirrors to Markdown; `:feature-stt`
  produces `Transcript`s that the notes editor turns into notes; `:feature-assistant` reads
  notes through the repository and streams answers.
- **Error handling / degradation:** every failure is a typed `AppError` (permission, model
  missing, network, remote STT failure, OpenRouter 401/402/429/5xx, storage) mapped by the
  shell to a message + a recovery action; nothing is swallowed; logs are structured, tag
  `FabricVR`, never contain secrets or note bodies.
- **Testing:** JVM unit tests per module (Room via Robolectric in-memory, HTTP via
  MockWebServer, SSE parser on fixtures); instrumented tests on the Quest for the whisper JNI
  path with bundled WAV fixtures; manual checks in the headset recorded per REQ.
- **Rejected:** single Gradle module (faster to start, but the seams would be conventions
  only); STT on a Mac server only (needs the Mac — brief decision 2); Spatial SDK immersive
  app as the primary surface (would not coexist with Meta Virtual Display — product
  definition §3.4).

| # | Module | Delivers | Owns (entities) | Depends on | Contracts exposed | UI? | REQs | Status |
|---|---|---|---|---|---|---|---|---|
| 1 | shell (`:app`, `:core-common`) | walking skeleton: installable APK, 2D panel `MainActivity` (Compose), hybrid `ImmersiveActivity` hosting the same panel, theme tokens, navigation, `SecureSettings`, `AppError` + `UiStateMapper`, logging | Settings (apiKey, serverUrl, model), AppError | — | `SecureSettings`, `AppError`, `UiStateMapper`, `FabricTheme`, `Navigator` | yes | REQ-001, REQ-007, REQ-009 | planned |
| 2 | notes-core (`:core-notes`) | Note CRUD, tags, FTS search, daily note; list/editor/search screens | Note, Tag, NoteFts | shell | `NotesRepository` (`observeNotes`, `get`, `upsert`, `delete`, `search`, `dailyNote`), `Note` model, `TagParser` | yes | REQ-002, REQ-005 | planned |
| 3 | vault (`:feature-vault`) | Markdown mirror of every note (`notes/YYYY/MM/<slug>.md`, front-matter), audio attachments beside it | VaultFile | notes-core | `Vault.write(note, attachments)`, `Vault.pathFor(note)`; subscribes `NotesRepository.observeChanges` | no | REQ-008 | planned |
| 4 | stt (`:feature-stt`) | hold-to-record capture (16 kHz mono), whisper.cpp JNI engine (`ggml-small-q5_1`), model provisioning (download + SHA-256, side-load), remote whisper-server client, `SttRouter` with fallback; `VoiceCapture` composable for the notes editor | Transcript, SttModel | shell, notes-core (creates notes via repository) | `SttEngine.transcribe(pcm16k, langHint): Result<Transcript>`, `SttRouter`, `ModelStore`, `VoiceCapture(onTranscript)` | yes | REQ-003, REQ-004, REQ-010 | planned |
| 5 | assistant (`:feature-assistant`) | OpenRouter chat client (SSE streaming, typed errors), notes-context builder, chat screen, model setting | ChatMessage | shell, notes-core | `OpenRouterClient.stream(request): Flow<Token>`, `Assistant.ask(question, notesContext)` | yes | REQ-006 | planned |
| 6 | release | full suite green on JVM + device, lint, `adb install` on the Quest, post-deploy checks, README + module docs + local evidence + wiki + code graph | — | 1–5 | — | no | REQ-011 | planned |

## Cut rationale

By capability, not by layer: notes, vault mirror, speech, assistant are each a brick with
its own data and tests. `shell` is the walking skeleton — the thinnest slice that proves the
riskiest assumption (a Spatial-SDK hybrid panel app installs and shows beside Meta Virtual
Display). `vault` is split from `notes-core` because it owns files, not rows, and can be
replaced by a sync engine later without touching notes. `stt` bundles capture + engine +
provisioning because they share the audio format and fail together. `release` exists so
REQ-011 has exactly one owner and the single deploy has a home.

## Cross-module contracts

- **`AppError`** (owner shell; consumers all). Sealed class: `Permission(kind)`,
  `ModelMissing(name)`, `Network(cause)`, `RemoteStt(status)`, `OpenRouter(status, code,
  message)`, `Storage(cause)`, `Unknown(cause)`. Every module returns `Result<T>` failing with
  an `AppError`; the shell's `UiStateMapper` turns it into `UiMessage(text, action)`. Failure
  behaviour: an unmapped error still renders (`Unknown`) — never a crash, never silence.
- **`SecureSettings`** (owner shell; consumers stt, assistant). `get(key): String?`,
  `set(key, value)`, `remove(key)`; values encrypted with an Android Keystore AES/GCM key;
  keys: `openrouter_api_key`, `openrouter_model`, `whisper_server_url`. Unavailable Keystore →
  `AppError.Storage`, settings screen says so.
- **`NotesRepository`** (owner notes-core; consumers vault, stt, assistant).
  `observeNotes(): Flow<List<Note>>`, `observeChanges(): Flow<NoteChange>`,
  `get(id): Note?`, `upsert(note): Result<Note>`, `delete(id): Result<Unit>`,
  `search(query): Flow<List<Note>>`, `dailyNote(date): Note`. `Note(id: String, title,
  body, tags: Set<String>, createdAt, updatedAt, audioPath: String?, transcript:
  Transcript?)`. Storage failure → `AppError.Storage`.
- **`Transcript`** (owner stt; consumer notes-core as a value). `Transcript(text, language,
  source: SttSource {LOCAL, REMOTE, LOCAL_FALLBACK}, durationMs, engine: String)`.
- **`SttEngine` / `SttRouter`** (owner stt). `suspend fun transcribe(pcm: ShortArray,
  sampleRate: Int = 16000, langHint: String? = null): Result<Transcript>`. Router order:
  remote if URL set → on failure local with `LOCAL_FALLBACK`; local missing model →
  `AppError.ModelMissing`.
- **`VoiceCapture`** (owner stt; consumer notes-core editor). Composable
  `VoiceCapture(onTranscript: (Transcript, audioFile: File) -> Unit, onError: (AppError) ->
  Unit)`; requests `RECORD_AUDIO` itself.
- **`OpenRouterClient`** (owner assistant). `stream(model, messages): Flow<Token>`; errors
  typed `AppError.OpenRouter(401 → key invalid, 402 → credits, 429 → rate limit, 5xx →
  provider)`; no key → `AppError.OpenRouter(0, "no_key", …)` before any request.

## Deferred to later modules (outside v1)

Mac companion + dictation into desktop fields (v2 `tools`), clipboard/file sync (`transfer`),
sketches (`ink`), computer agent (`agent`), calls tooling, Store release — all carried in the
carry-over ledger rows 3–7.

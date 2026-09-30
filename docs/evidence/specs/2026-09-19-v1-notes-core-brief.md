# Task brief — v1-notes-core

> Stage-0 intake artifact (task-pipeline). Locked 2026-09-19 after the grill; the
> operator confirmed the four material branches with the recommended answers.

- **Date:** 2026-09-19
- **Task (one line):** Build and install on the connected Quest 3 the first version of
  Fabric VR — a notes-first 2D panel app (Meta Spatial SDK / Kotlin / Compose, hybrid
  immersive) with text notes, voice notes transcribed on-device by an open Whisper
  model, a basic knowledge base (tags, search, daily note) and an AI chat over the
  notes through OpenRouter.
- **UI verdict:** **yes** — a user-facing Quest panel. The stage-3 super-ux track is armed
  (scenarios first). Visual track: **text-only** with sheleg-design tokens in code (no
  Figma). Copy track: **declined, draft** — strings written directly in EN; `/brand-init` +
  `/copy` before any public build (carry-over row 1).

## Knowledge sources (phase-1 harvest — written before the first question)

| Source | What it says about this task | Fresh? | Authority | Stale after this run? |
|---|---|---|---|---|
| repo code | none — the repository holds documentation only | 2026-09-19 | code | **yes — first code lands** |
| `docs/product/product-definition.md` | notes-first identity, layers, 2D panel + hybrid immersive, build order (phase 0 spikes → Notes Core → Voice tools …) | 2026-09-19 | product decision | partly — v1 realises phases 0–1 and the voice-notes slice |
| `docs/research/2026-09-19-fabric-vr-work-layer-report.md` + `sources/02,03,09` | Spatial SDK v0.14.0 (2026-09-11), Panels/Compose, `PixelDisplayOptions`, no Google Play Services, `SpeechRecognizer` unavailable, on-device dictation EN-US only → STT must be bundled; Whisper options; MX Ink stylus; Markdown/Excalidraw storage | 2026-09-19 | research | no |
| `docs/research/local-evidence-2026-09-19.md` | Quest 3 (`eureka`) reachable over wireless adb; Android Studio 2026.1, SDK 35/36.1, NDK 27.2 | 2026-09-19 | measured | **yes — add device `getprop` + build facts** |
| the maintainer's host-level agent instructions | quality bar, ops autonomy, handoff rule (commit + push), secrets never in chat/repo, the maintainer's local secret store | current | convention | no |
| `CONTEXT.md`, `docs/adr/`, `docs/DECISIONS.md`, `docs/evidence/*` | **none found** — seeded this run | — | — | created |
| `docs/ux/` | **none found** — created at stage 3 by `/ux` | — | — | created |
| the maintainer's wiki, `projects/fabric-vr` | **none found** — stage 9 creates the project folder | — | context | **yes — write at stage 9** |
| `graphify-out/graph.json` | not built (graphify installed, no code yet) | — | index | **yes — build at stage 9** |
| `fabric-agent-contract` (organization repository) | Provider profiles (MCP / A2A / local-runner), Memory Kernel — out of v1 scope, shapes later phases | 0.1.0 | contract | no |

## Documentation (phase-1b inventory)

| Question | Answer |
|---|---|
| **Regime** | governed — seeded this run (`DEC-0001`) |
| **Decision home** | `docs/DECISIONS.md` (`DEC-####`) — no `docs/adr/` |
| **Open questions** | `docs/OPEN_QUESTIONS.md` (`OQ-####`) |
| **Doc map** | `docs/DOCMAP.md` |
| **Gate** | `bash scripts/check-docs.sh` — seeded; floors start at 0 |
| **Shared state** | **`ungated`** — no `.claude/agent-sync.json`; single agent, said out loud |
| **Intent vs as-built** | nothing built yet — no divergence to reconcile |

- **Doc repos / hosted systems:** none named. **Knowledge wiki:** installed (stage 9 writes
  `projects/fabric-vr/`). **Retro in force:** none (seeded). **Retro archive:** empty.
  **Code graph:** installed, not built.

## Scope

- **In scope (v1, Quest-only):** installable APK for Quest 3/3S; 2D panel app in the
  Horizon OS shell with a hybrid switch to an immersive activity hosting the same panel;
  text notes (create/edit/delete/list) persisted locally; voice notes recorded from the
  headset microphone and transcribed on-device by whisper.cpp (ggml `small` quantised,
  RU+EN auto-detect) with an optional remote whisper-server URL; tags, full-text search,
  daily note; AI chat about the notes via OpenRouter (streaming) with the API key entered
  in Settings and stored encrypted on device; Markdown vault on device retrievable with
  `adb pull`; honest error states; structured logs without secrets; repo docs, README,
  wiki entry, code graph.
- **Out of scope / deferred (carry-over ledger):** Mac/PC companion and dictation into
  desktop fields (v2); cross-device clipboard, file transfer, CRDT sync, Notion push
  (v2+); sketches/MX Ink, sketch→diagram (v2); computer-use agent (v3); calls tooling;
  Horizon Store listing, VRC pass, privacy policy, subscriptions; brand pack and final
  copy; Figma mockups; Windows/phone companions.

## Requirements (the REQ spine)

| ID | Requirement | How it's verified | Status |
|---|---|---|---|
| REQ-001 | Debug APK builds reproducibly (`./gradlew :app:assembleDebug`) and installs on the connected Quest 3 with `adb install`; the app launches as a **2D panel** in the Horizon OS shell | `./gradlew :app:assembleDebug` exit 0; `adb install -r` exit 0; `adb shell dumpsys activity activities` shows `ai.passioncode.fabricvr/.MainActivity` resumed; operator sees the panel beside Meta Virtual Display | open |
| REQ-002 | Text notes: create, edit, delete, list; persisted across app restarts | JVM tests `NotesRepositoryTest` (Room in-memory) + `adb shell am force-stop` → relaunch → note still listed (manual, recorded) | open |
| REQ-003 | Voice note: hold-to-record from the headset mic (RECORD_AUDIO runtime permission), 16 kHz mono PCM, transcribed **on-device** by whisper.cpp with `ggml-small-q5_1`, language auto (RU/EN); transcript saved as a note with the audio attached | instrumented test `WhisperEngineTest` transcribes bundled `fixtures/jfk.wav` → contains "ask not what your country"; RU fixture → expected phrase; manual: speak RU and EN in headset | open |
| REQ-004 | Remote STT option: when a whisper-server URL is set in Settings, audio is posted to it (`/inference`); on failure falls back to on-device with a visible notice | JVM test `SttRouterTest` with MockWebServer (200 → remote text; 500/timeout → local fallback + `SttSource.LOCAL_FALLBACK`) | open |
| REQ-005 | Knowledge base basics: tags (`#tag` parsed from text + explicit), full-text search over title/body/transcript, a daily note per day | JVM tests `SearchTest` (FTS4 query), `TagParserTest`, `DailyNoteTest` | open |
| REQ-006 | AI chat about notes via OpenRouter (`anthropic/claude-sonnet-5` default, model configurable), streaming tokens into the UI, notes context injected; API key entered in Settings, stored encrypted with Android Keystore, never logged or committed | JVM tests `OpenRouterClientTest` (SSE parsing incl. `: OPENROUTER PROCESSING`, 401/402/429 → typed errors); `scripts/check-secrets.sh` (grep for `sk-or-` and key patterns) exit 0; log inspection `adb logcat` shows no key | open |
| REQ-007 | Hybrid mode: a button switches to an immersive Spatial SDK activity hosting the same notes panel in passthrough, and back to the 2D panel | manual in headset (recorded with screenshot via `adb shell screencap`); `adb shell dumpsys activity` shows `ImmersiveActivity` resumed | open |
| REQ-008 | Vault: every note is mirrored as a Markdown file (`notes/YYYY/MM/<slug>.md`, front-matter with id/tags/created) in app-private storage; audio as `.wav` beside it; retrievable via `adb pull` | JVM test `MarkdownVaultTest` (round-trip); `adb shell run-as ai.passioncode.fabricvr ls files/vault` lists files | open |
| REQ-009 | Honest degradation: missing mic permission, missing Whisper model, no API key, network failure and OpenRouter errors each show a specific message and a recovery action; nothing is swallowed | JVM tests `UiStateMapperTest` (each error → distinct state); manual: revoke permission, kill Wi-Fi | open |
| REQ-010 | Whisper model provisioning: first run downloads `ggml-small-q5_1.bin` from Hugging Face with progress and SHA-256 check into app storage; `adb push` side-load is honoured | JVM test `ModelDownloaderTest` (MockWebServer, checksum mismatch → rejected); manual first-run on headset | open |
| REQ-011 | Documentation shipped in the same change: README (build/install/run), module docs (`docs/modules/*.md`), device facts in local evidence, wiki `projects/fabric-vr`, code graph built; docs gate green | `bash scripts/check-docs.sh` exit 0; `graphify-out/graph.json` exists; wiki folder exists | open |

Status lifecycle: `open` → `planned` → `built` → `verified` | `partial` | `deferred` | `dropped`.
**The list is frozen once confirmed.** Adding is free; removing needs the operator.

## Users & context

- **Who / for what:** the operator (knowledge worker, RU/EN) working in a Quest 3 beside
  Meta Virtual Display; captures thoughts by voice and text without leaving the headset,
  finds them later, asks the AI about them.
- **Where it runs / constraints:** Quest 3/3S, Horizon OS 2.7 (v2.x), AOSP without Google
  Play Services; Snapdragon XR2 Gen 2; ~2 h battery; Spatial SDK v0.14.x; on-device
  inference must stay under a few seconds for a 10–20 s utterance; OpenRouter over Wi-Fi.

## Decisions locked (the grill's output)

| # | Decision | Chosen | Rationale |
|---|---|---|---|
| 1 | v1 scope | Quest-only: notes + voice + AI chat; no Mac companion | installable and useful standalone; fastest path to the headset |
| 2 | STT placement | on-device whisper.cpp `small-q5_1` + optional remote whisper-server URL | works without the Mac; RU quality acceptable for notes; server path for quality later |
| 3 | Visual layer | text-only, sheleg-design tokens in Compose theme | dev build to look at in the headset; Figma later |
| 4 | Copy | draft strings in EN (copy pass declined), brand pack before public build | speed; recorded in carry-over |
| 5 | LLM | OpenRouter, default `anthropic/claude-sonnet-5`, configurable; Haiku 4.5 for cheap ops | operator's OpenRouter account; Claude models lead on notes structuring |
| 6 | Secrets | API key + server URL entered in Settings, stored via Android Keystore-backed encryption; never in repo, logs or chat | house rule; `security-crypto` deprecated → Keystore + AES/GCM |
| 7 | Storage | Room (SQLite + FTS4) as index, Markdown vault files as the user-owned canonical copy | product definition: vault is the user's; Room gives search |
| 8 | App identity | package `ai.passioncode.fabricvr`, name "Fabric VR" | PassionCode.ai family; trademark check before Store (carry-over) |
| 9 | Deploy | `adb install -r` to the connected Quest 3 after the suite is green; no Store | operator's explicit instruction |
| 10 | Branching | `feat/v1-notes-core` → merge to `main` → push `origin` | standing handoff authorization |

## Autonomy (the sweep)

| Stage | Question | Answer |
|---|---|---|
| run-wide | Model | Fable 5.1 (most capable available); Sonnet for sub-agent research/docs study |
| run-wide Escalation | Decide alone vs escalate | decide alone inside the repo and reversible; escalate: Store publishing, pricing, anything outward beyond this repo + this headset |
| run-wide Pacing | Run mode | item-by-item, no check-in between items (`.task-pipeline/pipeline.json` → `run.loop` recorded this run); manual gates still stop |
| 0 Harvest | Sources beyond repo; may stage 9 write? | the maintainer's wiki — yes, write `projects/fabric-vr/`; graph — build at stage 9 |
| 0 Duplicates | which copy the build reads | single Gradle project; `app/build.gradle.kts` is the consumer; no duplicates yet |
| 0 Fixtures | persistent local state + recreate command | Gradle caches (`~/.gradle`), Whisper model file on device (`adb shell run-as … rm files/models/*` recreates the first-run path); Room DB (`adb shell pm clear ai.passioncode.fabricvr`) |
| 0 Source | `git rev-list --count HEAD..@{u}` | printed at seed time (see run ledger); 0 expected |
| 0 Work-list | task-state register + read command | `docs/evidence/backlog.md`; `grep -c '| open |' docs/evidence/backlog.md` |
| 0 Setup audit | run the entry audit? | no — doc map absent, seeded now; nothing to audit |
| 0 Docs regime | home, writers, lease, gate | `docs/DECISIONS.md`; this agent; **ungated**; `bash scripts/check-docs.sh`, floors 0; may raise floors |
| 1 Docs | libs/SDKs | Meta Spatial SDK 0.14.x, whisper.cpp (JNI), OpenRouter API, Compose/Room/Keystore/OkHttp — docs-study agent fetches; context7 for AndroidX |
| 2 Decompose | platform or module | **platform** (notes core, STT, AI chat, vault, hybrid shell) — module map at stage 2; deploy once at the end (single APK) |
| 2–3 Spec | UI verdict; waiver | yes; no waiver — scenarios written by `/ux` |
| 3 Design surface | Figma or text-only | **text-only** (recorded above); sheleg-design tokens |
| 3 Design file | — | n/a (text-only) |
| 4–5 Dev | branch policy | base `main`; branch `feat/v1-notes-core`; conventional commits; tracker = board |
| 5 Integration | how it lands | direct merge to `main` by this run after gates; no fan-out (single agent) |
| 6 Tests | command; green | `./gradlew :app:testDebugUnitTest` (JVM) + `./gradlew :app:connectedDebugAndroidTest` on the Quest (instrumented, incl. Whisper fixture); green = all pass, no known-red |
| 7 Lint | command | `./gradlew :app:lintDebug` + `python3 docs/ux/lint.py` (after `/ux` seeds it) + `bash scripts/check-docs.sh` + `bash scripts/check-secrets.sh` |
| 7 Deploy | target | `adb -s <headset-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk`; launch via `adb shell am start` |
| 7 Deploy | authorization | **specific, standing for this run:** install the debug APK on the connected Quest 3 (`<headset-ip>:5555`) once tests and lint are green — the operator asked for the build to be installed on the headset. Store publishing: always ask |
| 8 Post-deploy | logs/health | `adb logcat -s FabricVR:*`; `adb shell dumpsys activity`; screenshot `adb shell screencap` |
| 9 Docs + wiki | what updates | README, `docs/modules/*.md`, local evidence, DOCMAP, wiki `projects/fabric-vr`, `/graphify .` |
| 10 Acceptance | sign-off; deferred REQs | operator in headset; deferred → `docs/evidence/backlog.md`; retro seeded |

## Done-criteria

APK installed on the connected Quest 3; the operator opens the panel beside Meta Virtual
Display, writes a text note, records a voice note in Russian and in English and sees the
transcript, finds a note by search, asks the AI a question about a note and gets a streamed
answer; every REQ row carries evidence; docs gate green; branch merged and pushed.

## Open assumptions / risks

- On-device `small-q5_1` Russian accuracy may disappoint → remote server path exists (REQ-004).
- Spatial SDK 0.14 hybrid sample may pin Gradle/AGP versions that conflict with the latest
  Compose BOM → pin to the sample's versions first, upgrade later.
- Panel coexistence with Meta Virtual Display is asserted from docs, not yet seen on this
  device → REQ-001 manual check is the proof.
- Fable spend limit hit sub-agents earlier today → research/doc agents run on Sonnet.

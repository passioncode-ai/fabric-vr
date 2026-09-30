# Audit plan — Fabric VR v2 (2026-09-20)

What is being audited: branch `feat/v1-notes-core` at `421377e` — six Gradle modules, 5 598 lines of
Kotlin in `main`, 173 lines of C++ (the whisper.cpp JNI bridge), 123 JVM tests and 14 instrumented
tests, 15 UX scenarios, the Spatial SDK manifest, the documentation and the process. Installed on two
Quest 3 headsets. Since the first audit (2026-09-19) the product changed shape: the capture flow is
one tap-to-toggle button with no sheet; a transcript commits itself to a note and to the clipboard;
notes carry Copy / Transcribe again / Delete with Undo; speech has a provider (device, cloud, LAN
server), five on-device models and a pinned language; the immersive Space launches after two
platform fixes (hand-tracking declaration, panel owners). **Nobody has yet used the current build for
more than a minute.**

The audit's question is not "is it good" but three things: *what will go wrong when a person uses
it*, *what did the build quietly skip or leave half-thought*, and *where is the shape wrong enough
that fixing bugs one by one is the slow path*. Every finding carries `file:line`, a concrete failure
scenario, a severity and a proposed fix; every axis names what it did **not** cover.

## Method

Six independent readers, one per axis, in parallel and blind to each other, each writing its own
report into `docs/evidence/audits/2026-09-20-v2/<axis>.md`. Findings are then merged, de-duplicated
and **each verified against the code before it enters the report** — a reader's claim about a line
is checked by opening the line. Where a claim can only be settled on the headset, the finding says
so and names the observation that settles it.

Then a second wave: one agent per work package turns verified findings into developer-ready tasks —
context gathered, related findings linked, best practice researched, alternatives weighed with their
trade-offs, and a decomposition fine enough that a zero-context agent cannot misstep.

Severity vocabulary:

| Severity | Meaning |
|---|---|
| **Blocker** | data loss, a crash on a main path, or a promised feature that cannot work at all |
| **High** | a scenario the person will hit in the first session behaves wrongly |
| **Medium** | wrong under a plausible condition, or a contract the code silently violates |
| **Low** | quality, hygiene, or a divergence with no user-visible effect yet |

## Axes

| # | Axis | Reads | Looks for |
|---|---|---|---|
| A | **Business logic and data** | `core-notes`, `feature-vault`, `feature-stt` (Kotlin), `feature-assistant`, `app/**/ui/*ViewModel.kt`, `app/Graph.kt`, `app/ui/AudioAdoption.kt`, their tests | logic errors; the re-transcription replacement rule; delete + undo against the vault (the `.wav` is removed on delete — does undo restore it?); audio adoption ordering and failure paths; provider routing and fallback semantics; switching the model while an engine is mid-transcription (`Graph.localEngine` closes it); the cloud client's `auto`, language normalisation and error mapping; downloader resume/redirect/digest; Room migrations and the change stream; SecureSettings corrupt-key handling; coroutine and Flow misuse; anything untested that is promised |
| B | **UI, interaction, and reachability in BOTH hosts** | `app/**/ui/*Screen.kt`, `FabricApp.kt`, `PanelActivity.kt`, `ImmersiveActivity.kt`, `core-common/theme`, `core-common/ui`, `res/values/*.xml`, `docs/ux/screens.md` | every element reachable and operable in the 2D panel **and** inside the Spatial SDK panel — text fields and the system keyboard in the Space, `DropdownMenu` popups inside a Spatial panel (a separate view root), the clipboard in the Space, Back behaviour now that a dispatcher exists; Compose state and effects (`copied` never resets; `justCopied` under scroll; `LaunchedEffect(voice)` re-firing); the 480×360 dp minimum; contrast over passthrough with the transparent theme; empty / loading / error states per screen; anything hard-coded outside tokens; strings that are stale or duplicated in `strings.xml` |
| C | **Platform, lifecycle, native, security, build** | `ImmersiveActivity`, `SpatialPanelOwners`, `PanelActivity`, `FabricVrApp`, `PermissionRequester`, `AndroidManifest.xml`, `feature-stt/src/main/cpp/*`, `feature-stt/build.gradle.kts`, `WhisperEngine`, `AudioRecorder`, `SecureSettings`, `NetworkPolicy`, `CloudTranscriptionClient`, all `build.gradle.kts`, `proguard-rules.pro`, `scripts/*` | lifecycle of the owners vs the activity; permission flow inside the Space; audio focus and the microphone while OpenXR renders; JNI lifetimes and error paths with beam search; thread count and thermal behaviour while the Space renders (whisper at 4 threads competes with the compositor); native memory of a 574 MB model; Keystore edge cases; the cloud key over `http://` to a LAN host (the policy permits it); **R8 rules against kotlinx.serialization, Room and the Meta SDK — the release build has never been run**; signing; versionCode; wrapper; secret scan; the absence of CI |
| D | **Scenarios and the access principle** | `docs/ux/scenarios.md` (SCN-001…015), `docs/ux/flows.md`, `docs/ux/screens.md`, the screens and view models | each scenario walked against the code with `file:line` for every step; steps the code no longer performs; states no scenario names; **the first-run path on a fresh headset** — no model, no key, no permission: can a person reach a working dictation without reading a document?; what happens after Stop when the model is absent, when the cloud key is missing, when the network drops mid-upload, when transcription fails; scenarios contradicted by the current UI; the principle that the interface must give access to the product in both hosts |
| E | **Architecture, performance, refactoring** | the whole tree, `docs/modules/*.md`, `docs/evidence/specs/*-design.md` | module boundaries and dependency direction; `Graph` as a service locator with lazies and a mutable engine cache (thread safety, testability); duplication (the record control exists twice); dead code and dead states (`VoiceState` members no longer rendered, `VoiceViewModel.retranscribe(language)`, unused string resources); what has **no** test at all and why (`ChatViewModel`, `VoiceViewModel`'s real flow, `ImmersiveActivity.placePanel`); performance: `noteText()` per recomposition, unbounded `observeNotes`, whisper memory + Compose in one process, startup, thermal strategy; where an optimisation is worth its cost and where it is not — say which |
| F | **Documentation and process** | `README.md`, `docs/modules/*.md`, `docs/DECISIONS.md`, `docs/evidence/**`, `docs/handoff/**`, `docs/ux/**` | every claim contradicted by the code; decisions taken today with no `DEC-` row (beam search, hand tracking, self-committing transcript, cloud provider, no-hold-no-sheet); verification rows that are stale about the current tree; board rows that should exist; carry-over rows never closed; what a new agent would get wrong from the handoff |

## Not covered by this audit

- On-head usability: whether the panel is legible and the button hit-rate is acceptable at the
  distance people actually sit. Each such item is listed as "verify on device" with the observation.
- Transcription quality across voices and rooms — measured, not reviewed.
- Store compliance (VRC, privacy labels) — out of scope by the brief.
- Accessibility beyond touch-target size — the family has no owner for it.

## Outputs

1. `docs/evidence/audits/2026-09-20-v2/{A..F}.md` — one report per axis, raw.
2. `docs/evidence/audits/2026-09-20-v2-audit.md` — the merged, verified findings.
3. `docs/evidence/plans/2026-09-20-v2-plan.md` — every finding turned into a task a zero-context
   agent can execute, grouped into work packages, with the research and the alternatives that
   were weighed.
4. `docs/evidence/backlog.md` — a board row per task.

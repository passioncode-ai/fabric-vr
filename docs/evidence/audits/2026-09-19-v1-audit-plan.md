# Audit plan — Fabric VR v1 (2026-09-19)

What is being audited: the v1 build on branch `feat/v1-notes-core` at `7c0b438` — 4 174 lines of
Kotlin across six Gradle modules, one C++ JNI bridge, 60 JVM tests, the Spatial SDK manifest, the
documentation and the process that produced them. The app is installed on the operator's Quest 3 and
was seen launching (`Displayed PanelActivity +1s11ms`); **nobody has used it yet**.

The audit's question is not "is it good" but "what will go wrong when a person picks it up, and what
did the build quietly skip". Every finding carries `file:line`, a concrete failure scenario and a
proposed fix; every axis names what it did **not** cover.

## Method

Three independent reviewers read the code cold against the contracts (spec, scenarios, module docs),
in parallel and without seeing each other's output, plus the author's own re-read. Findings are
merged, de-duplicated, then **each is verified against the code before it enters the report** — a
reviewer's claim about a line is checked by opening the line. Where a claim can only be settled on the
headset, the finding says so and names the observation that settles it.

Severity vocabulary:

| Severity | Meaning |
|---|---|
| **Blocker** | data loss, a crash on a main path, or a promised feature that cannot work at all |
| **High** | a scenario the person will hit in the first session behaves wrongly |
| **Medium** | wrong under a plausible condition, or a contract the code silently violates |
| **Low** | quality, hygiene, or a divergence with no user-visible effect yet |

## Axes and what each checks

| # | Axis | Reads | Looks for | Reviewer |
|---|---|---|---|---|
| A | **Business logic and data** | `core-notes`, `feature-vault`, `feature-stt` (Kotlin), `feature-assistant`, their tests, spec §3–§5, SCN-001…013 | logic errors, spec↔code contract drift, coroutine/Flow misuse, Room↔FTS↔vault divergence, front-matter escaping, swallowed errors, leaks, the STT fallback matrix, SSE parsing, context budget, WAV header, downloader partial/cancel/rename paths, untested promises | independent (Opus) |
| B | **UI, visual, rendering, platform** | `app/**`, manifest, resources, `core-common/theme`, `screens.md`, `flows.md`, docs-study §A | every SCR state rendered or missing, Compose state/effects/keys, the hold gesture under controller ray and hand pinch, the 480×360 dp minimum, hard-coded values outside tokens, immersive panel geometry and placement, passthrough, the Home return path, ViewModel/Lifecycle owners inside the Spatial SDK panel, navigation and lifecycle, string↔scenario drift | independent (Opus) |
| C | **Security, network, native, build** | `SecureSettings`, `OpenRouterClient`, `RemoteWhisperClient`, `ModelDownloader`, JNI C++ + CMake, whisper.h at the pinned tag, all Gradle files, scripts, manifest | Keystore spec and lost-key behaviour, key reaching logs/bodies, cleartext to a LAN server, timeouts, SSE cancellation and deadlock, redirects and missing digest, JNI reference and lifetime rules, `language="auto"` semantics in v1.9.4, CMake flags for arm64, version pins vs docs, gitignore, script false negatives, exported surfaces | independent (Opus) |
| D | **Author's re-read** | the app layer and the seams between modules | what the author knows he wrote fast: the Dialog-during-hold interaction, unused `attachTranscript`, vault path on delete, tag-chip and header overflow, error banners without dismiss, permission grant mid-hold | author |
| E | **Process and documentation** | run ledger, carry-over, verification ledger, DOCMAP, README, module docs | what the run recorded as skipped (inline build, batched TDD, no device suite, no graph, no wiki), stale claims (spec §1 versions), gates that passed without a planted defect | author |

## Not covered by this audit

- **Anything that needs the headset on a head**: on-device transcription latency and quality,
  the panel beside Meta Virtual Display, the immersive panel's actual position, hand-pinch on the
  hold button. Each such item is listed as "verify on device" with the exact observation.
- Performance under load (battery, thermal, whisper thread count) — measured, not reviewed.
- Store compliance (VRC, privacy labels) — out of v1 scope by the brief.
- Accessibility beyond touch-target size — the family has no owner for it (`sheleg-design` says so).

## Outputs

1. `2026-09-19-v1-audit.md` — the merged, verified findings with `file:line`, ordered by severity
   and then by the seam they sit on.
2. `docs/evidence/plans/2026-09-19-v1-hardening.md` — every finding turned into a task a
   zero-context agent can execute: exact files, exact edit, the test written first, the acceptance
   check, what it must not touch.
3. `docs/evidence/backlog.md` — a board row per task, so the work survives this session.

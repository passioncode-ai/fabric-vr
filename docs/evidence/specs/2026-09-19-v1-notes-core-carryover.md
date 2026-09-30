# Carry-over ledger — v1-notes-core

> **Append-only.** Any stage may add a row; nobody edits or deletes one. Read in full by stage 10.
> *Deferred out loud is forgotten* — if it is not here, it was lost.
>
> **Rows 1–8 were written at stage 0 and lost** when the shell command that created this file failed
> part-way; they were restored from the brief at stage 9. That is itself the failure this ledger
> exists to prevent, so it is recorded here rather than quietly repaired.

| # | Stage | What | Why it isn't done | REQ | Where it lives now |
|---|---|---|---|---|---|
| 1 | 0 Intake | UI strings written as drafts in EN; brand pack (`/brand-init`) and the `/copy` pass not run | operator declined the copy pass (draft strings), speed of the dev build | — | B-035 |
| 2 | 0 Intake | Figma mockups not produced; the visual layer is text-only with sheleg-design tokens | operator decision (text-only) | — | B-035 |
| 3 | 0 Intake | Mac/PC companion: dictation into desktop fields, a remote whisper-server installer | v1 is Quest-only | — | B-030 |
| 4 | 0 Intake | Cross-device clipboard, file transfer, CRDT sync, Notion push | v2+ per the product definition | — | B-031 |
| 5 | 0 Intake | Sketches (MX Ink / hands), sketch → diagram | v2 per the product definition | — | B-032 |
| 6 | 0 Intake | Computer-use agent with voice supervision | v3 per the product definition | — | B-033 |
| 7 | 0 Intake | Horizon Store listing, VRC pass, privacy policy, subscriptions, trademark check of "Fabric VR" | not in v1; Store publishing always asks | — | B-034 |
| 8 | 0 Intake | Meta Connect 2026-09-23/24 may change the avatar and AI facts in the research reports | future event | — | B-036 |
| 9 | 5 Build | The build ran **inline**, not with per-task implementer and reviewer subagents | the provider's session spend limit was reached; subagents were unavailable from ~12:30 | — | **resolved 2026-09-21 by `T-046`** (`DEC-0054`) — the revisit condition came true. Nine blind axis readings ran in the `2026-09-20-v2` audit and three independent tiers verified step 8, finding ten defects the inline run had shipped green. The waiver is closed, not carried: `SI-08` is what the fan-out taught, and `B-176` records that a reading which dies reports nothing at all |
| 10 | 5 Build | TDD was **batched** per module — tests and implementation written together, then run — rather than one red-green cycle per test | each Gradle run costs 1–2 minutes; a planted defect was used instead to prove the highest-value test | — | **live again, 2026-09-21 (`T-046`, `DEC-0054`) — the waiver's own premise failed.** It rested on "a planted defect was used instead to prove the highest-value test", and the plant at `deb604d` is one a test **passed over**: `LocalOnBackPressedDispatcherOwner` fell back to the rule's `ComponentActivity` host, so the check read nothing and printed the word that means it read everything. A technique-based waiver is worth exactly what the technique is worth. `SI-06` now requires the plant to be watched **failing**, at the level the defect lives — which is the replacement this row was promised, arriving two days late and with its own incident |
| 11 | 9 Docs | The code graph was NOT built — `graphify` needs an OpenAI key this machine lacks (401 User not found) | external credential missing | REQ-011 | B-029 |
| 12 | 8 Post-deploy | REQ-001 launch, REQ-003 on-device transcription and REQ-007 hybrid switch are **unobserved** — the headset slept and went offline | device unavailable | REQ-001, REQ-003, REQ-007 | B-028 |
| 13 | 5 Build | No instrumented (device) test suite was written; `WhisperEngineTest` from the plan does not exist | the device was unavailable to run it against | REQ-003 | B-001 |
| 15 | 5 Review | `WhisperEngine` holds a single-thread executor and a native context that are never closed — `Graph` has no shutdown path | the app has no lifecycle where it would matter yet (one process, one engine) | — | B-019 |
| 16 | 5 Review | The model's SHA-256 is not pinned, so only the published SIZE is enforced | no digest for `ggml-small-q5_1.bin` has been confirmed from a primary source; pinning an unverified one would be a claim, not a check | REQ-010 | B-021 |
| 14 | 9 Docs | The wiki entry `projects/fabric-vr/` was not written | the run stopped at the device gate; stage 9's wiki half is owed | REQ-011 | B-027 |

## Columns

- **Stage** — where it surfaced, so acceptance knows how far it travelled.
- **What** — the concrete thing not done.
- **Why it isn't done** — scope call, blocked, deliberate deferral, out of budget.
- **REQ** — the requirement it belongs to, or `—` outside the REQ spine.
- **Where it lives now** — `open` here means unresolved and blocking the stage-10 gate until each
  row leaves with a board id on `docs/evidence/backlog.md`.

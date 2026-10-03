# Doc map — fabric-vr

**One per project, not per run.** The four questions of
`references/documentation.md`, answered for this repository: where settled things
live, what each fact's single home is, what a change obliges, and what proves it.

Seeded by task-pipeline at stage 0 **only when absent**. Extend it whenever a new
document class appears; never let it grow a second copy of something stated
elsewhere — where another file already says it, this one holds a **pointer line**,
not a copy. A doc map that duplicates `AGENTS.md` is the first violation of the
rule it publishes.

## Contents

- Regime
- Registers
- Single source of truth
- Propagation matrix
- Gates
- Ratchets
- Terms
- Navigation

## Regime

`governed` — established 2026-09-19 by run `v1-notes-core`, recorded as `DEC-0001`.

Governed scales by **volume**, never by dropping rules: a register with three
entries is a register. Nothing here is authored twice — every entry is transcribed
from an artefact the run already produced (the brief's *Decisions locked*, the
spec's contracts, an ADR).

## Registers

| Register | File | ID scheme | Append-only? | Guarded? |
|---|---|---|---|---|
| Decisions | `docs/DECISIONS.md` | `DEC-####` | yes | lease before write, where a mechanism exists |
| Open questions | `docs/OPEN_QUESTIONS.md` | `OQ-####` | yes (never delete a resolved row) | same |
| Board | `docs/evidence/backlog.md` | `B-###` | no (mutable; a closed row **stays in place** and carries a resolving reference — `T-045` retired the *Closed* list) | `check-docs.sh` §18 |
| Verification | `docs/evidence/verification.md` | `REQ-###` rows | yes | `check-docs.sh` §16 (the `Environment` vocabulary) · §9 (every SHA resolves, or names a listed pre-publication commit and prints `NOT_CHECKED`, `DEC-0101`) · §12 (commands it names exist); `scripts/exposure.sh` parses it and **prints**, never gates. It read `ungated` until `DEC-0057` — written before §16 existed and never revisited |
| Device gate | `docs/evidence/device-gate.md` | one row per instrumented run, plus the **walk** a person follows (`T-043`) | yes — rows are appended, and the walk is versioned with the commit it was written against | `scripts/check-device-gate.sh` (a change to `*/src/androidTest/**` must carry a row) · `check-docs.sh` §21 (its cited ranges exist). It named "§20" until `DEC-0057`; §20 is handoff currency, and the number had drifted under the row when sections were added |
| Pre-publication commits | `docs/pre-publication-commits.txt` | one commit id per line, spelled as the documents cite it | **closed** — the set of entries is pinned by digest in `check-docs.sh`; a change is a new decision (`DEC-0101`) | `check-docs.sh` §9: references to these commits print `NOT_CHECKED`, an entry reachable from HEAD and a changed set are refused |
| Requirements (run `v1-notes-core`) | `docs/evidence/specs/2026-09-19-v1-notes-core-brief.md` | `REQ-###` | frozen per run | ungated |
| Requirements (run `2026-09-21-v3`) | `docs/evidence/plans/2026-09-21-v3-plan.md` | `REQ-042`…`REQ-067` | frozen per run | ungated |
| Requirements born in the ledger (`2026-09-21-v3` close-out, `2026-09-22-sweep`, the 2026-09-22 audit) | `docs/evidence/verification.md` — the row **is** the requirement | `REQ-068`…, next free id after the ledger's last | yes | same as Verification. These were never planned: each is an absence a walk found or a correction appended because a row cannot be edited, so no plan names them and this row is their only register |

> One decision home per project. If this repository already had `docs/adr/`, that
> is the register and the first row names it instead — never both.

## Single source of truth

Every fact has exactly one home; everything else links to it by id. A fact stated
in two places is a bug: collapse it to one home and link from the other.

**The incident that proves it** (`DEC-0046`): the first run's tap count was written as three, and
by the time a blind reading walked it from the code and got four, the wrong number sat in **six
documents — one of them a verification row marked `pass`**. No gate can compare prose to prose;
only one home can.

| Fact | Home | Everything else |
|---|---|---|
| A settled decision | `docs/DECISIONS.md` | cites the `DEC-####` |
| The domain glossary | `CONTEXT.md` | links to the term |
| User-facing behaviour | `docs/ux/scenarios.md` | links to the scenario id |
| Product identity and layers | `docs/product/product-definition.md` | link |
| A module's contracts and refusals | `docs/modules/<module>.md` | link |
| Research facts | `docs/research/*` (dated records) | cite the file |
| Where a credential lives, who holds it, how it is backed up | `docs/deployment/signing.md` | link — **never the value** (`DEC-0049`) |
| The release certificate's SHA-256 | `docs/deployment/signing.md` | `scripts/verify-release-apk.sh` enforces it, and `scripts/check-release-apk.sh` fails if the two differ (`DEC-0104`) |
| What each release contains | `CHANGELOG.md` | the release notes are its `## X.Y.Z` section, taken by the organization's publish workflow (`DEC-0104`) |
| The licence of this project's own code | `LICENSE` + `COMMERCIAL-LICENSE.md` (Fabric ADR-0092) | `README.md` → License links them |
| Each third-party component and its licence | `NOTICE` (`DEC-0073`; copied into the APK) | link — never a second list |
| How to report a vulnerability, and what is in scope | `SECURITY.md` | link |

## Propagation matrix

**The harvest ledger names what you read. This names what you owe.** A row's third
column names the check that notices when the row is not honoured — or the word
`review` **with a one-line reason why no check can decide it**. An empty third
column is a finding, not a blank.

| Change type | Update these | Checked by |
|---|---|---|
| **A new document or rule** — start with this row | every surface that must *learn* it exists: the index a reader opens, the map, any manifest, the agent-facing rules file | `bash scripts/check-docs.sh` (link + index sections) |
| A module's code changes | that module's file in `docs/modules/` | review — the module file states intent, which no check can read |
| New/changed **decision** | `docs/DECISIONS.md` + every doc in its `Consequences / affects:` line | gate §5 propagation |
| Question **resolved** | `docs/OPEN_QUESTIONS.md` → `Resolved→DEC-####`, the owning topic doc | gate §2 ids · gate §8 status vocabulary |
| **Scope** change | `docs/product/product-definition.md`, the register | review — scope is a judgement, not a shape |
| New/changed entity or field | `docs/modules/core-notes.md` (data model), `CONTEXT.md` | review — schema changes are read by a person |
| User-facing behaviour | `docs/ux/scenarios.md` + `flows.md` + `screens.md`, same change | `python3 docs/ux/lint.py` |
| **A run ends, or the entry point moves** | the current entry point — `docs/handoff/2026-09-22-v3-entry.md` today — is replaced whole; the previous one gets a `> **SUPERSEDED` banner above its title and **no other edit**; `README.md` names the new one and only it (`DEC-0055`) | gate §20 handoff currency. **This row named `2026-09-20-v2-entry.md` for two runs after it stopped being current** — the matrix itself is a surface that must learn, and §20 checks the README rather than this cell |
| **A gesture, screen or device address changes** | `docs/evidence/device-gate.md`'s *walk* — the steps name controls, and a gate is the part of a handoff that goes stale fastest | review — whether a step is still performable needs a person; §20 only guarantees the reader reaches the right file |
| A signing credential's home changes | `docs/deployment/signing.md`, and nothing else names the path | `bash scripts/check-secrets.sh` §2b |
| The release key is rotated (`DEC-0104`) | `docs/deployment/signing.md`'s fingerprint **and** `RELEASE_CERT_SHA256` in `scripts/verify-release-apk.sh`, in one change | `bash scripts/check-release-apk.sh` |
| A user-visible change lands | `CHANGELOG.md` → `## Unreleased`; a release renames it `## X.Y.Z` | review — whether a change is user-visible is a judgement |


**Start with the row above.** The most frequent change in any documented project is *adding a document*, and it is the row nobody writes — so the matrix ends up unable to catch the class it will meet most. On the project this practice comes from, nine findings across five audits were that one missing row.

## Gates

**One command runs all of them: `bash scripts/check-all.sh`, and its last line is
`ALL GATES GREEN`.** That line is reachable only under `set -eu`, so a green cannot be
manufactured by a pipeline whose `grep` exited 0 — which is how a run with three failed tests
was once pushed (`SI-03`).

The table below is **the list, in execution order** — seventeen of them. It listed four until
`T-045` (`DEC-0053`), and the doc map's gate table is the one place an agent looks for *what must pass*:

| # | Gate | Command | When | Blocking? |
|---|---|---|---|---|
| 1 | Device-gate ledger | `bash scripts/check-device-gate.sh $BASE_REF` | before the commit | yes — passes with a notice when `origin/main` is absent in a shallow clone |
| 2 | Secrets, including signing material | `bash scripts/check-secrets.sh` | before the commit | yes |
| 3 | Destructive commands | `bash scripts/check-destructive.sh` | before the commit | yes |
| 4 | Seams | `bash scripts/check-seams.sh` | before the commit | yes |
| 5 | Main-thread policy (R2, `DEC-0031`) | `python3 tools/check_main_thread.py` | before the commit | yes — also runs as `MainThreadPolicyTest`; exits 2 when the scan proved nothing |
| 6 | Exported Room schemas (`DEC-0036`) | `bash scripts/check-schemas.sh` | before the commit, **before the build** | yes |
| 7 | Documentation | `bash scripts/check-docs.sh` | before the commit | yes |
| 8 | Strings — duplicates, exemptions, library modules (`DEC-0045`) | `bash scripts/check-strings.sh` | before the commit | yes |
| 9 | Installer (`DEC-0048`; closes `H-30`, `H-31`) | `bash scripts/check-installer.sh` | before the commit | yes — it runs the real script against a fake `adb`, and its third case is the canary: without one proving a *correct* install still succeeds, a script that exited non-zero for any reason would pass the other two |
| 10 | Release APK verifier (`DEC-0104`) | `bash scripts/check-release-apk.sh` | before the commit | yes — it runs the real `scripts/verify-release-apk.sh` against a fake `apksigner` and `aapt2` in both spellings the real tools print, with a canary; it also fails if the fingerprint the verifier enforces drifts from `docs/deployment/signing.md`, or if `release.yml` puts a keystore secret anywhere but an environment mapping. The real APK is verified by the same script in `release.yml` |
| 11 | Native bridge compiles (`DEC-0061`), and is linked for 16 KB pages (`B-104`) | `bash scripts/check-native.sh` | before the commit | yes — one file, `-fsyntax-only`, ~1.4 s; reports and passes where there is no NDK. The page-size flag is a configuration check and runs even without one |
| 12 | Every shell script parses (`DEC-0062`) | `bash scripts/check-shell.sh` | before the commit | yes — `bash -n`, milliseconds; it exists because an apostrophe inside a single-quoted `awk` program closed the string **three separate times in one session** |
| 13 | **The gates' own negative tests** (`DEC-0056`) | `bash scripts/selftest.sh` | before the commit | yes. **The script prints its own split** — planted defects and controls — because three documents restated it and all three were wrong, three different ways (`DEC-0062`). A case that stops failing is a check that has stopped working. **~3:45**, most of the suite's runtime, and the price of the claim |
| 14 | UX documents | `python3 docs/ux/lint.py` | before the commit | yes |
| 15 | JVM test suite, and the instrumented sources compile (`REQ-068`) | `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin` | before the commit | yes — it compiles `androidTest` and runs none of it; only a headset runs that |
| 16 | Strings — unreferenced, `:app` only | `lint { error += "UnusedResources" }` | ci.yml's nightly `release build` job — it needs the NDK | yes, in CI |
| 17 | Dependency verification | automatic, from `gradle/verification-metadata.xml` | every resolution | yes — and a **cold** re-resolve nightly in CI (`DEC-0050`) |

**Gate 13 is the one that keeps the other sixteen honest.** Five sections of the documentation
gate were written with "watched failing against a planted defect" in their own headers, and a
blind verification found five of the six evadable — the claim was true when written and nothing
kept it true. `scripts/selftest.sh` plants each defect and asserts the refusal, in the repository
rather than in a commit message (`DEC-0056`).

**A gate that loses a check prints the same word** (`DEC-0063`). `check-all.sh` records what each
gate prints on a clean tree and refuses a run where one prints fewer — a ratchet that may only
rise. It names the script, never the check; naming the check is gate 13's job, for the sections it
covers. The incident it exists for: a `git checkout` deleted a whole section of `check-strings.sh`
and the suite stayed green with one fewer `ok:` line, while a decision record described the check
for days afterwards.

Two lines are **printed and never gated**, deliberately: the staleness disclosure
(`scripts/exposure.sh`, `DEC-0052`) and each gate's own ratchet counts. A disclosure that can
fail a build becomes a number to avoid printing.

Each gate states in its own header what it does **not** cover. Read that before
quoting a green as evidence — `check-docs.sh:8-9` in particular disclaims prose *meaning*, which
is why a stated decision and its opposite can sit seven lines apart under a green.

## Ratchets

A named number that may move in one direction only, printed beside the verdict on every run.
Moving a floor the other way is a decision and belongs in the register. **They are not one
kind**, and `check-docs.sh`'s own header spells out why (its *ratchets* block):

| Ratchet | Where | Kind | Current | Set on |
|---|---|---|---|---|
| Propagation backlog | `PROP_FLOOR`, `check-docs.sh` | an **id threshold**, not a count: every decision at or above it must have propagated; the older ones are a printed backlog that may only shrink | 0 | 2026-09-19 |
| Retired residue | `RESIDUE_FLOOR`, `check-docs.sh` | a **count** of unmarked citations of retired decisions tolerated; may only fall | 0 | 2026-09-19 |
| Verdict lines per gate | the table at the top of `check-all.sh` (`DEC-0063`) | a **count** of `ok:` lines each gate prints on a clean tree; may only **rise** — fewer means a check was lost | per gate | 2026-09-21 |

## Terms

Only terms **declared here** are checked. A heuristic over every capitalised word
cries wolf, and a gate that cries wolf is removed by the third person who hits it —
so this table is the project's own list, and it may start with three rows.

| Term | Definition lives in | Anchor |
|---|---|---|
| Note | `CONTEXT.md` | `#note` |
| Vault | `CONTEXT.md` | `#vault` |
| Transcript | `CONTEXT.md` | `#transcript` |

Rules: **one definition per term**, the anchor resolves, and a document that uses the
term links to that anchor rather than restating it. A term with two definitions is
the same defect as a fact with two homes.

## Navigation

- One definition per entity, with an explicit anchor.
- A mention links to the **anchor**, not to the file.
- Indexes and summaries link; they never restate a rule. An index that falls behind
  lies with authority — a reader concludes the entry does not exist.

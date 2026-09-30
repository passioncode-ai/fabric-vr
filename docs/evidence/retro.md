# Pipeline retrospective — fabric-vr

One file per project, not per run. Written as the **last act of stage 10**:
**stamp first, then prune**, then write an entry **only if the run diverged**.
The order is load-bearing: the cold-retirement trigger reads the stamp this stage
writes, so a prune ahead of it can never run on real data (`learned.md` rule 21).
Doctrine: `references/retrospective.md`.

**What stage 0 reads in full:** *Standing instructions* and *Run stamps* — both
are bounded by construction (ten rows, one line per run), which is why the cap is
not negotiable. The **Recent log** and the **archive**
(`docs/evidence/retro/YYYY-QN.md`) are *queried* by the task's nouns and never
read end to end: nothing caps the log's length, and an uncapped section read in
full is the volume that stops the ten binding rows being read.

## Standing instructions (max 10 — in force right now, and the cap is checked)

Every row is a rule an agent must follow, that **no check can decide**. A rule a
check *can* decide is not written here: it is written as the check (grade 1).
No row is accepted without its **retire-when** trigger, written at birth.

Two SHA columns, and they are not bookkeeping: a `file:line` rots at the next edit,
while `git show <sha>` reconstructs the whole incident months later. Every SHA here
must resolve — the documentation gate runs `git rev-parse --verify` over all of
them (§9).

**All three of this section's rules are now gated** (`DEC-0054`), which they were not when the
first five rows were written: §9 resolves the SHAs, and §19 (`T-046`) enforces the cap and refuses a row with no
retire-when — watched failing against both, a planted row without a trigger and a planted eleventh
instruction. The cap is the load-bearing one: this list is read **in full** at stage 0 of every
run, and its worth comes from being short enough that reading it is cheap. An unbounded list of
rules nobody can hold arrives one useful row at a time, which is exactly how it would arrive.

| id | Born | Commit | Instruction | Because | Retire when | Last fired | Fired at |
|---|---|---|---|---|---|---|---|
| SI-01 | 2026-09-21 | `ac4c29e` | **A number in a document is computed, or it is not written.** If a document states a sum, a count or a budget, the run computes it from the tree in the same pass — `git show`, a test, a script — and says what it computed it from. A number carried from a spec, a sweep list or an earlier document is not computed. | Step 8's verification found four false claims and **three were arithmetic**: a height budget that omitted the 128 dp control it was about, so the spec's own target was unreachable by 58 dp; a press count of three sitting beside its own "six taps, two were waste"; and "eighteen dead strings" taken from the sweep list when the file says seventeen. Each read as evidence and none was. | A check derives every number in `docs/` from the tree and fails on a mismatch. | 2026-09-21 | `b0a1a55` |
| SI-05 | 2026-09-21 | `8772eaa` | **A Robolectric Compose rule is not a place to assert what a coroutine did.** Compose-under-Robolectric tests may assert what is *rendered* from state they are handed. Anything that has to wait for a view model — a recording starting, a commit finishing, a settings read resolving — is asserted at the view-model tier with a test dispatcher, or not at all. | Three cases cost three separate debugging rounds in one session. `waitForIdle()` returns when nothing is recomposing, which says nothing about a coroutine; waiting for an on-screen confirmation waits for a 2 500 ms transient the Compose clock can advance past between polls; and `waitUntil` polls the main looper the continuation needs in order to progress. The first form **flaked**, and twice a flake was nearly filed as a transient; the second and third failed on every run, which is how the shape finally became visible. | A harness exists that can await a view-model coroutine from a composition — or the tier is dropped. | 2026-09-21 | `8772eaa` |
| SI-03 | 2026-09-21 | `7a15abb` | **A push is chained to an exit code, never to a grep.** `bash scripts/check-all.sh && git push`, or check `$?` — not `check-all.sh \| grep OK && git push`. The same for any command whose success is being used as a precondition. | On 2026-09-21 a push went out on a run that had printed three `FAILED` tests: the chain was `./scripts/check-all.sh 2>&1 \| grep -E "ERR\|FAIL\|^OK: doc" && git commit && git push`, and `grep` exited 0 because it matched the documentation gate's OK line. The pipeline's exit code was `grep`'s, not Gradle's. | A pre-push hook enforces it **in a fresh clone without a manual step**. `.githooks/pre-push` exists since `0a5b5fa` and four `selftest.sh` cases watch it. It **stopped running the gates on 2026-09-22** and now checks a receipt `check-all.sh` writes on its own last line: running them inside the hook held git's connection to the remote open for minutes and got four pushes killed by `SIGPIPE` after printing `ALL GATES GREEN`. The rule is better served — a receipt cannot be manufactured by a grep, nor by being patient — but git still does not track hooks, so a clone arms it with `git config core.hooksPath .githooks` and an agent that skips that line is back where this rule started. Half the trigger, and the rule stays for the other half. | 2026-09-21 | `0a5b5fa` |
| SI-02 | 2026-09-21 | `ac4c29e` | **A comment that predicts a failure mode is checked against the code it sits beside, before the commit.** Writing "X would go stale" or "the next emission tries again" is a claim about behaviour and gets the same treatment as a claim in a document. | Two of step 8's defects were written **in the doc comment that described them**: `VoiceViewModel.modelPresent` carried "a flag would go stale the first time somebody removed a model in Settings" and was that flag, cached in `init`; `watchMirror` argued "the next emission tries again" about a `StateFlow`, which does not re-emit an equal value. Both passed review, both passed every gate. | It has not fired in five run stamps. | — | — |
| SI-06 | 2026-09-21 | `f7d656d` | **A test or a gate is not evidence until a defect has been planted and watched failing it — at the level the defect lives.** A plant that *passes* means the check sits at the wrong level, not that the code is fine. Say in the commit what was planted and what was watched. | Two incidents, one shape. `check-secrets.sh` piped `git ls-files -z` into `grep -zZv`, and `grep` here is ugrep where `-z` means *decompress*: **ten planted credential shapes all passed** and the gate printed `ok` having read nothing. Separately a Compose test that omitted the back-press owner **passed**, because `LocalOnBackPressedDispatcherOwner` falls back to the test rule's `ComponentActivity` host — the defect was planted, watched passing, and the real test had to launch the activity (`deb604d`). | Every gate in `DOCMAP.md` carries a self-test in CI (**T-002**), and a harness exists that runs Compose tests under a non-`ComponentActivity` host. | 2026-09-21 | `101a6d1` |
| SI-07 | 2026-09-21 | `421377e` | **The absence of a failure is not the presence of a feature.** A test asserting only "it did not crash" is satisfied by a surface that never appeared. Assert something the feature *produces*, from inside it. | The immersive launch test could pass over a Space the shell had deferred behind a Guardian dialog — nothing ran, nothing crashed, green. It now waits for a marker set **inside** the composition, and at `662e4ad` the composition was made to throw and the test failed on the device, so it is non-vacuous in both directions. | A platform-level assertion for "the scene composed" exists. | — | — |
| SI-08 | 2026-09-21 | `8c5ff9e` | **A parallel fan-out asserts its own arity before its results are merged.** Count the readings that came back against the readings that were launched, in the output, every time. | This audit ran six blind axis readings. **The first three (B, C, E) died on a model spend limit and had to be relaunched**, and a run of three merged as if it were a run of six is indistinguishable from a complete one — a missing reader reports nothing at all. The merge records the relaunch; nothing in the mechanism would have. | The harness reports a failed subagent as a failure of the run rather than as silence. | — | — |
| SI-09 | 2026-09-21 | `4bddfcc` | **A mocked test proves the rule the code implements, never that the rule still matches the world.** Any rule derived from a third party gets one test that touches the third party. | Every redirect test pointed at a server the test itself started, so all six were green while **no model could be downloaded at all** — the guard was refusing Hugging Face's own CDN. `ModelDownloadReachabilityTest` is the answer and cost about a megabyte. Four of the five model digests are still in exactly this position (`B-173`). | Every external contract in the tree has a reachability test. | — | — |
| SI-10 | 2026-09-21 | `6fa6ec7` | **A parser that addresses a table cell by position asserts the row's shape first, and refuses a row it cannot address.** Never `next`, never a default. This applies to any positional read of a register — markdown, CSV, a log line. | Six parsers written across two days each split a markdown row on an unescaped `|` and indexed from the left. Two board rows carry a pipe in a code span and were **skipped without being counted**, so a closed row with no reference could have hidden there for ever; one standing instruction with an escaped pipe let a row with an empty retirement trigger pass; and `exposure.sh` reported a row **`current` that was 20 commits behind** — the worst, because the summary still reads as a measurement. `exposure.sh`'s own header contains the reasoning that would have prevented all three, and is right about the **last** cell only. | Every register read in this project goes through `tools/mdtable.awk` **and** a schema, so the shape is checked by construction rather than by each parser remembering to. | 2026-09-21 | `6fa6ec7` |
| SI-11 | 2026-09-21 | `8327b76` | **A subagent's report is not evidence. Re-plant the defect in the integrated tree before the merge is believed.** Read the changed files, plant the defect the report says a test catches, watch it fail, revert. Say in the commit which plant was re-run. | This run merged four module branches, each arriving with a careful report naming its failing output and its plant. All four reports were accurate — and re-planting still earned its cost three times over, because what a subagent cannot see is the tree it is merged INTO: `feature-stt`'s module doc cited a **build output** that resolves only on a machine that has just built, `app/src/androidTest` had stopped compiling at `REQ-048` and no gate looked, and the entry document went stale on a count one commit after the gate that checks it was written. None of the three is a defect in the work reported; all three were invisible from inside it. | A merge gate runs each branch's own planted defects in the integrated tree automatically. | 2026-09-21 | `8327b76` |

Retire on **any** of: it became a check · every path/command it names is gone · it
has not fired in the last five run stamps, or in the last sixty days. At eleven
rows, the oldest never-fired
row goes — the cap is not negotiable, ranking is.

## Recent log — narrative entries, uncapped and queried rather than read (newest first)

Older entries and every retirement **move** to `docs/evidence/retro/YYYY-QN.md`
at the prune. Moving is not deleting: the archive is append-only and holds the
incident forever, so pruning the in-force list costs no knowledge. This section
stays short precisely so that reading it in full at stage 0 stays cheap.

### 2026-09-25 — the first generation plant tested the path guard instead

- **Incident:** `407d412`. Removing the listing generation check initially stayed green because
  the test changed audioPath too; the independent path check rejected the old result.
- **Correction:** DictationDurabilityTest now holds the current path fixed while the earlier-file
  set changes and directory work completes backwards. Removing the generation guard then fails.
- **Scope:** an application of SI-06, not an eleventh standing instruction. The fixture must isolate
  the invariant it claims to prove. [Round-3 report](2026-09-25-round3.md) carries the run limits.

### 2026-09-22 — the gate that passed inside a socket nobody was holding open

- **What happened.** Four pushes in a row printed every gate green, then `ALL GATES GREEN`, then
  `pre-push: gates green`, and then exited **141** having transmitted nothing. The remote sat three
  commits behind for two hours while every local signal said the work had shipped. Each attempt
  cost a full multi-minute suite for a failure that had nothing to do with the tree.
- **The cause, and it is a property of `git push` rather than of this repository.** git opens the
  connection to the remote **before** calling `pre-push` — it needs the remote refs to build the
  hook's input — and holds it across the hook. GitHub closes an idle SSH session well inside the
  time this suite takes, so git finished the gates and died writing the pack. `141 = 128 + 13`,
  which is `SIGPIPE`, which reads exactly like a network fault.
- **Two wrong diagnoses before the right one, both worth recording.** First: the hook never drained
  the ref list git writes to its stdin, which genuinely can raise `SIGPIPE` — fixed, proven
  non-vacuous against a copy of the hook with the drain removed, and **the push still failed**.
  Second: the machine was loaded, so a leftover process was killed by `PPID == 1` — and that
  process was the running push's own gate run, reparented when its shell exited. **`PPID == 1` is
  not evidence of an orphan** when the work was started in the background.
- **What settled it was a measurement, not an argument.** The same push with `check-all.sh` stubbed
  to `exit 0` succeeds; with the real script it exits 141. Duration, not verdict.
- **The fix, and why it serves `SI-03` better than what it replaces.** `check-all.sh` writes a
  receipt on its own last line — reachable only on success under `set -eu` — naming the exact tree
  it read; the hook compares it in milliseconds and refuses anything else. A caller could never
  manufacture a green by matching an `OK:` line, and now cannot manufacture one by being patient
  either. The identity is a hash of the tracked files' CONTENT, not `git write-tree`: content is
  invariant across `git add` and `git commit`, so a receipt written before the commit is still true
  at the push, and the computation writes nothing — which is what makes it safe to exercise inside
  `selftest.sh`'s copied tree, whose `.git` is a symlink to the real repository.
- **Grade 3 (an entry), not a rule.** Nothing here generalises into a standing instruction that
  would have been obeyed: the trap is invisible until a hook gets slow, and the repository now
  carries four self-test cases and a paragraph in `README.md` instead of a sentence nobody could
  have acted on in advance.

### 2026-09-21 — the run that quoted the rule against chaining to a grep, and then chained to a grep

- **Symptom.** `docs(verification)` was committed and pushed as `17fdfd7` on a `check-all.sh` run
  that had printed `FAILURE: Build failed with an exception`. The chain was
  `bash scripts/check-all.sh 2>&1 | grep -E "^(ERR|FAIL)|ALL GATES|selftest: [0-9]+ passed" | head -4 && git add -A && git commit && git push`.
  `grep` matched `selftest: 33 passed`, exited 0, and everything after it ran. Three full runs
  immediately afterwards were green, so the tree was in fact fine — which is luck, and the reason
  this entry exists rather than a shrug.
- **The stage it surfaced at:** 10, while reading the output that had already been pushed. **The
  stage that owned it:** every commit in the run; the chain was built that way nine times and
  happened to sit on a green tree eight of them.
- **Root cause, and it is not ignorance.** `SI-03` says *a push is chained to an exit code, never
  to a grep*, and this run had **read it at stage 0** and **quoted it in a commit message** two
  hours earlier. The rule was known, agreed with, and broken — because a long run does not
  re-read its own instructions between commands, and a grep that formats output for a human reads
  as formatting rather than as control flow. A rule whose observance depends on remembering it at
  the exact moment fails hardest late in a session, which is when a push matters most.
- **The fix, by grade.** Grade 1: `.githooks/pre-push` runs the gates and passes their exit code
  up, so the chain cannot be built wrong; `git config core.hooksPath .githooks` arms it and
  `--no-verify` skips it out loud. **Its first draft passed a red tree** — `if cmd; then … fi;
  rc=$?` reads 0, because an `if` whose condition fails is itself a success — and `selftest.sh`
  case 28 caught that on the first run against a planted red: a hook written to stop a push on a
  failing exit code, losing the exit code. Grade 2: `SI-03` stays, with its trigger downgraded to
  half-met, because git does not track hooks and a fresh clone arms nothing.
- **The check that catches it next time:** the hook, plus its self-test case. Neither depends on
  anybody remembering anything.
- **What is worth carrying to other projects.** *The rules a run breaks are the ones it has
  already internalised.* An unfamiliar rule gets looked up; a familiar one gets assumed, and the
  assumption is not re-tested against the command actually being typed. The remedy is never more
  care — this run was careful and said so in writing — it is moving the rule out of the agent and
  into the mechanism.

### 2026-09-21 — six blind readings, and the three things only the merge could see

- **Symptom.** Four module branches arrived with careful reports: failing output quoted, planted
  defects named, tests green. All four reports were accurate. The integrated tree was not:
  `docs/modules/feature-stt.md` cited `feature-stt/build/test-results/…`, a **build output** that
  resolves only on a machine that has just built, so the documentation gate refused a clean tree
  and the failure surfaced as `selftest: control — the gate REFUSED a clean tree`;
  `app/src/androidTest/.../SpaceBackTest.kt` had stopped compiling at `REQ-048` and stayed broken
  through a whole group of work; and the entry document went stale on an instrumented-test count
  **one commit after** the gate that checks counts was written.
- **The stage it surfaced at:** 5, at each merge. **The stage that owned it:** 5, in a worktree
  that could not see the tree it was merging into.
- **Root cause.** A branch is verified against itself. None of the three defects is a defect in
  the work that was reported — each is a relationship between that work and something outside it,
  and a report written from inside cannot name what it cannot see. `check-all.sh` compiled the JVM
  suite and the native bridge and never `androidTest`, so the compile break had no reader at all:
  CI cannot run instrumented tests, and the device gate reads a ledger rather than a compiler.
- **The fix, by grade.** Grade 1: `check-all.sh` compiles `compileDebugAndroidTestKotlin`;
  `check-docs.sh` §23 reads the current handoff's numbers; the doc no longer names a generated
  path. Grade 2: `SI-11` — re-plant each branch's defect in the integrated tree before believing
  the merge. Grade 3: nothing; all three were mechanical once seen.
- **The check that catches it next time:** the three new gate sections, and `selftest.sh` case 28
  aside, the honest gap is stated in the file: the instrumented-compile check has **no plant**,
  because a plant would need a real Android build in a copied tree that has no `local.properties`
  and would refuse for an environment reason — a pass for the wrong reason. `B-193` carries it.

### 2026-09-21 — the step that audited claims wrote four false ones, and five of its six gates could be evaded

- **Symptom.** Step 11 closed with `ALL GATES GREEN`, five new gate sections, a new script, and
  three decision records each stating that every check had been watched failing against a planted
  defect. Three blind tiers then read the same range and found **twelve** things: five of the six
  new checks were evadable, the CI release job was stamping every APK `versionCode = 1`, and four
  false claims had been written by the three tasks whose stated purpose was making claims
  checkable.
- **The stage it surfaced at:** 10, the group verification. **The stage that owned it:** 5 and 9 —
  the build wrote the parsers, the doc pass wrote the certificates for them.
- **Root cause, and it is one thing.** Every new parser read a markdown table with `awk -F'|'` and
  addressed cells by index **from the left**. A pipe inside a code span or an escaped `\|` shifts
  every cell right, and none of the three live failures was loud: two board rows were skipped
  *without being counted*, one standing instruction with an escaped pipe let an empty retirement
  trigger pass, and `exposure.sh` reported a row **`current` that was 20 commits behind**. What
  makes this worth an entry rather than a fix: **`exposure.sh`'s own header contains the argument
  that would have prevented all three.** It reasons carefully about why counting from the *right*
  drops rows whose trailing note holds a pipe — which is true — and that correctness about one end
  of the row is what made the other end feel settled. Being right about the hard case is how you
  stop looking at the easy one.
- **The second root cause, which is about evidence rather than parsing.** Three decision records
  said the plants had been watched. They had — on the day, by hand, and then the evidence
  evaporated. A claim that a check can fail is exactly as perishable as any other claim, and the
  place for it is the repository. `scripts/selftest.sh` now plants twenty defects and asserts
  twenty refusals, plus two controls on a clean tree.
- **The fix, by grade.** Grade 1: `tools/mdtable.awk` plus a shape assertion in every parser, so a
  row that cannot be addressed is reported rather than skipped; `check-docs.sh` §21 for cited line
  ranges; `docs/ux/lint.py` `[U080]` for a scenario that names code while reading `draft`; the
  build refuses a shallow clone. Grade 2: `SI-10`. Grade 3: nothing — every finding here was
  mechanical once seen.
- **The check that catches it next time:** `scripts/selftest.sh`, gate 10, and it is the one that
  retired `SI-04` the same day by being stronger than the mechanism `SI-04` asked for.
- **What is worth carrying to other projects.** *A run that is auditing claims is writing claims
  at the highest rate it ever will, under the least scrutiny, with the reviewer's attention
  pointed backwards.* Four of the five false claims were written by the three tasks whose purpose
  was making claims checkable. The remedy is not care — the run was careful, and said so in
  writing each time. The remedy is that a blind tier read the range afterwards.

### 2026-09-21 — the prune found nothing, and two proposed rules were born already retired

- **The prune ran and is recorded as finding nothing to prune**, which is the correct outcome and
  not a skipped step. Measured before writing anything:

  ```bash
  $ sed -n '/^| id | Born |/,/^$/p' docs/evidence/retro.md | grep -c '^| SI-'
  5
  ```

  All five were born on 2026-09-21 against run stamps `ac4c29e` and later; none is cold, none has
  had its retire-when come true, and the retirements section was still empty when the prune ran. **Order
  matters and was followed** — stamp first, then prune: the cold-retirement trigger reads the
  stamps this stage writes, so a prune ahead of them can only ever run on no data.

- **`T-046`'s spec proposed six instructions against a file it believed was empty. It is not**
  — five arrived during step 8 and step 11 — so six more would have opened the list at 11, one
  over its own cap. What the cap forced was a reading rather than a trim:

  - **Two of the six were one rule at two scales.** "A test must be watched failing with the
    defect present, at the level the defect lives" and "a gate is not a gate until a violation has
    been planted and watched failing it" differ only in whether the subject is a test or a gate,
    and both incidents (`deb604d`, `f7d656d`) are the same failure: *the check ran and read
    nothing, and printed the word that means it read everything.* They are `SI-06`, with both
    incidents in one `Because`. A rule split in two is a rule that gets obeyed in one half.
  - **One was born retired.** *"Verify against the build that is installed, not the build that was
    compiled"* names its own retirement: the installer prints and asserts the installed
    `versionCode`. `T-038` shipped that at `7a15abb` — `install-on-quest.sh:66-68` refuses when
    `$AFTER != $EXPECTED` — so the rule was already a check before it could become an instruction.
    **It goes here instead of into the list**, because the file's own bar is that a rule a check
    can decide is written as the check. The incident is worth keeping: "the run that looked like it
    still failed was testing the previous build, which had never installed", and a real fix was
    nearly rejected as ineffective (`732c92b`).

- **The propagation rule's enforcement, revisited.** The spec's first log entry said
  `DOCMAP.md`'s module-doc propagation row is graded `review` and therefore has no enforcement at
  all — the 2026-09-19 reconciliation found 20 stale claims and the audit found 39 one day later.
  That is **no longer entirely true**, and the difference is worth stating: `check-docs.sh` §11
  resolves backticked paths in descriptive documents, §13 resolves named symbols, §17 (`T-045`)
  computes stated test counts, and §18 (`T-045`) checks that a closed board row names what closed
  it. What is still unenforced is the thing the row is actually about — **that deleting a `.kt`
  file forces the documents naming it to change** — and `T-002`'s per-gate self-test. `SI-01`'s
  retire-when is likewise only partly met: §17 derives *test counts*, not every number.

- **Carry-over rows 9 and 10, both resolved against what happened.** Row 9 waived per-task
  implementer and reviewer subagents because the provider's session spend limit was reached; its
  revisit condition was *reviewer subagents available*, and **it came true** — this audit ran nine
  of them, and the step-8 group verification ran three blind tiers that found ten defects. Row 10
  waived per-test TDD on the grounds that "a planted defect was used instead to prove the
  highest-value test", and **that premise failed**: the plant at `deb604d` was one a test passed
  over. A waiver justified by a technique is only as good as the technique, which is why `SI-06`
  now requires the plant to be watched *failing* rather than merely used.

### 2026-09-21 — every gate was green through all ten defects

- **Symptom.** Step 8 (`T-028`…`T-032`) closed with 342 JVM tests, a clean lint and six green
  gates. Three independent tiers then read the same range and found ten defects, four of them in
  claims the run had written down as true.
- **The stage it surfaced at:** 10, the group verification. **The stage that owned it:** 5 and 9 —
  the build wrote the defects, the doc pass wrote the certificates for them.
- **Root cause, and it is one thing.** The run graded its own arithmetic and its own prose. A
  height budget omitted the control it was about; a press count contradicted the subtraction two
  lines above it; a string count came from the sweep list rather than the file; two doc comments
  described the exact failure they were shipping. **None of it was checkable by any gate**, and
  the gates' green was quoted as though it were.
- **Fixes by grade.** Grade 1 (a check): `PanelSizeTest` derives its sum from `Tokens.Space` and
  reds on the experiment that defeated it; `check-strings.sh` grew a library-module scan with its
  own canary; `check-seams.sh` learned the second spelling of the system page, and its canary now
  proves a correct arm passes as well as that the defect fails. Grade 2 (a rule): `SI-01` and
  `SI-02`. Grade 3 (an entry): this one.
- **The check that catches it next time** is the three-tier read itself, which is why it ran. What
  it cost — three agents, about forty minutes — is the price of not believing a run's own report.

### Retirements

One line each, newest first. A retirement is a record, not a section — so it is a
list item. A heading promises a body, and a heading with nothing under it is the
shape the hygiene gate's check 6 exists to find.

- **`SI-04` retired 2026-09-21** — *never `git checkout <path>` to undo a planted defect* —
  because its own retirement trigger came true, by a stronger mechanism than the one it named. It
  asked for "a pre-commit hook that diffs the gate scripts' check counts, or the scripts asserting
  their own section count"; what exists is `scripts/selftest.sh`, which plants a defect per gate
  section and asserts the refusal. **Watched, not assumed**: §18 was deleted outright from
  `check-docs.sh`, the gate went on printing `OK: documentation gate`, and the self-test reported
  `FAIL 18 a closed row with no reference — the gate PASSED with the defect planted`, naming the
  section that had vanished. A count would have said a number changed; this says which check
  stopped working. Full text in `docs/evidence/retro/2026-Q3.md`. The incident it carried —
  `git checkout` destroying uncommitted work twice in one session, once silently deleting a whole
  gate section while the gate still printed `OK` — is the incident `selftest.sh` exists for, so
  the knowledge is not lost, only the instruction.

## Run stamps

One line per run, appended at stage 10, **capped at ten** — at the eleventh the
oldest rotates whole into the archive. One line per run is a slope, not a bound:
this section is read in full at stage 0.

| Date | Topic | Commit | Verdict | Retro |
|---|---|---|---|---|
| 2026-09-19 | `2026-09-19-v1-notes-core` — v1 built, then a 28-task hardening pass | `f770049` | shipped; installed on a Quest 3 and photographed. **H-28, the device gate, was not run**, and the handoff claimed a green suite over a red instrumented test | — · stamped retroactively by `T-046`, which is why its `Retro` column is empty: stage 10 never ran |
| 2026-09-20 | `2026-09-20-v2-audit` — nine blind axis readings | `8c5ff9e` | audit only, nothing fixed. **291 findings, 27 Blocker.** Three of the first six readings died on a spend limit and were relaunched; a run of three merges indistinguishably from a run of six | — · stamped retroactively by `T-046`. The relaunch is `SI-08`'s incident |
| 2026-09-21 | Step 8 — the Today surface, rebuilt once (`T-028`…`T-032`), then its own group verification | `ac4c29e` | shipped green, then **ten defects** found by three blind tiers; all fixed, each with a test watched failing | `DEC-0046`, `SI-01`, `SI-02` |
| 2026-09-21 | Step 11 — the documentation audit's own tasks (`T-042`…`T-046`) | `41b4a95` | shipped. Four documents cited machinery that **does not exist**; five claims became gates. `T-044` refused to write five retroactive observation rows and wrote the disclosure instead | `DEC-0052`…`DEC-0055`, `SI-06`…`SI-09` |
| 2026-09-21 | Step 11's **own** group verification, three blind tiers | `d6ee7ff` | **twelve findings, and the step had shipped green.** Five gate sections written to make claims checkable could be **evaded**, three of them by a defect already in the tree; the release job was stamping every CI APK `versionCode = 1`; and four false claims were written by the three tasks whose purpose was making claims checkable | `DEC-0056`, `DEC-0057`, `SI-10` |
| 2026-09-21 | The group verification of `DEC-0058`…`DEC-0061`, three blind tiers | `9be8128` | shipped green four iterations running, then **twenty-six findings, seven of them breaks** — and the unit and seam tiers reproduced the same three from different directions. Two would have reached a person's data, both written inside the fix for the defect they recreated: a migration that demoted the note the person had been typing into all day, and a demotion whose read sat outside the transaction that writes (28 of 40 rounds). `selftest.sh` reported *the gate PASSED with the defect planted* wherever there is no NDK. **Stamped retroactively on 2026-09-22** — stage 10 never ran for this one, and a gap is worth more than a pretended date | `DEC-0062`, `DEC-0063` |
| 2026-09-21 | `2026-09-21-v3` — a cold-start project audit, then the operator's four decisions and the map worked | `8327b76` | shipped. Six blind readings over `10d5c7f` found 5 Blockers outside the code and 14 High in it; 27 REQs landed across seven module groups. **CI has not run since `7e22df4`** — the organisation's Actions budget is $0 — so every verdict in this run is a local one. Three timing flakes, two audit findings the ladder walk had to invent REQs for, and `SI-03` broken by the run that was quoting it | `DEC-0064`…`DEC-0074`, `SI-11` |

| 2026-09-25 | Third validation of iterations 8–10, resumed | `407d412` | local JVM and lint pass; device unobserved; hosted jobs have zero steps | `DEC-0098`; [round-3 evidence](2026-09-25-round3.md) |

The `Commit` column is what turns a stamp from a date into a navigable point in
history: `git show <sha>` is the run, and `git log <sha>..HEAD` is everything that
happened since a rule last fired.

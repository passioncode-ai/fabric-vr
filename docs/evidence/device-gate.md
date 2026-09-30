# Device gate — instrumented runs

> **Append-only.** One row per `connectedDebugAndroidTest` run. A row is never edited except to add
> the `Environment` value, which only a person can supply.
>
> This file exists because two instrumented tests were red at `421377e` for two completely
> different reasons — one stale assertion, one unworn headset — and the handoff recorded neither,
> claiming "13 tests, 0 failures". A red test with no attributable cause sends the next agent
> bisecting for a regression that is not there (`F-03`).

## Environment — the vocabulary, declared here so it is not invented per row

A value is used only if it appears below. `verification.md` declares its own for the same reason
(`F-24` exists because a value was used and never declared).

| Value | What it means |
|---|---|
| `device-worn` | a person had the headset on for the whole run |
| `device-unworn` | the headset was awake but on a table; the shell may defer an immersive launch behind a Guardian dialog, and `ImmersiveLaunchTest` is then **expected red** |
| `device-unknown` | nobody recorded it — the row proves the run happened and nothing about what the red means |

**`device-worn` is a person's observation.** Do not infer it from a sensor, a log line or a test
outcome: an approximation of the thing the ledger exists to record would make every row unfalsifiable.

## Runs

| Date | Commit | Device | Environment | Result | Red, and why |
|---|---|---|---|---|---|
| 2026-09-20 | `421377e` | Quest 3 `<serial>` | `device-unworn` | 14 run, 1 red | `ImmersiveLaunchTest` — **expected**: the shell deferred the launch behind a Guardian dialog, so the panel never composed. `PanelSmokeTest` was **not** run in that invocation. **Reconstructed from `421377e`'s commit message, not observed** — the first row of a ledger that did not exist yet. |
| 2026-09-20 | `5fd2f9b` | — | `device-unknown` | **not run** | T-008 added three cases to `core-common/src/androidTest/.../UiStringsTest.kt` (`SttNotConfigured` ×2, `InsecureUrl`) and a fourth test, and nobody ran them: `adb devices` reports both headsets **offline**, and a Quest drops its link within seconds of going to sleep. Recorded rather than skipped because the gate that should have asked for this row could not — see below. The three cases are pure resource lookups with no device behaviour in them, which is why this is a row and not a blocker; `B-085` carries the observation that is actually owed.
| 2026-09-21 | `c4833ea` | Quest 3 `<serial>` | `device-unworn` | 1 run, 0 red | `VaultExportOnDeviceTest` only, by class filter — the export's `MediaStore` half is the one part no JVM test can reach. The headset was awake on the desk and nobody was wearing it, which this test does not need: it touches no immersive surface. The rest of the instrumented suite was **not** run in this invocation. |
| 2026-09-21 | `4872529` | Quest 3 `<serial>` | `device-unworn` | 2 run, 0 red | `SettingsCarryOverOnDeviceTest` only, by class filter. Neither the immersive surface nor a worn headset is involved — it exercises `AndroidKeyStore` AES/GCM through `KeystoreSecureSettings` in a probe-only preference file, which is the part `InMemorySecureSettings` proves nothing about. |
| 2026-09-21 | `e564ee6` | Quest 3 `<serial>` | `device-unworn` | 2 run, 0 red | `SettingsCarryOverOnDeviceTest` re-run against the **committed** tree. The earlier row for it cited `4872529`, the then-HEAD, because the test was still untracked when it ran — the content was identical but the row could not prove it, and the gate said so the moment `9bae2bd` landed. The lesson is the workflow, not the file: commit the instrumented change, then run, then append. |
| 2026-09-21 | `8f7cb65` | — | `device-unknown` | **correction, not a run** | The row above citing `c4833ea` for `VaultExportOnDeviceTest` is **false**: that file was added by `4872529` and `git ls-tree -r c4873ea` does not contain it. The row cites the parent commit, which is verbatim the failure the gate installed one commit earlier exists to refuse — and because the gate takes the row with the newest commit, the stale one is never examined again. The run itself happened and its content is `4872529`'s; **the citation was wrong, not the observation.** Found by an independent verification pass. Nothing above is edited — this file is append-only, and a wrong row corrected in place is a file nobody can audit. |
| 2026-09-21 | `662e4ad` | Quest 3 `<serial>` | `device-unworn` | 1 run, 0 red | `ImmersiveLaunchTest` after `T-012`/`T-016`. **It passed unworn, which corrects a standing belief in this file**: the row for `421377e` recorded the Space as expected-red on an unworn headset, and that was true of a Guardian dialog at that moment rather than a property of the device. The Space reached RESUMED and its panel composed with `DEC-0028`'s owner arrangement. Then, the point of the task: the composition was made to throw and the test **failed on the device**, carrying the exception — so it is non-vacuous in both directions, proven on hardware rather than argued. The planted crash did not latch the `@Before` tripwire on the next run, which is what the high-water mark is for. |
| 2026-09-21 | `eb8e237` | Quest 3 `<serial>` | `device-unworn` | **10 run, 0 red** | The whole instrumented suite, for the first time with every one of them green on hardware. `PanelGeometryTest` is new and measured the defect rather than assuming it: a fresh `PanelConfigOptions` on this device defaults to **1.0 m × 0.75 m**, and the app asked for a 1024×640 dp texture — 1.6 against 1.333, a twenty per cent vertical stretch (`B-08`). Its two assertions were watched failing against the shipped configuration, on the device. |

## The gate's own failure, recorded here because the ledger is what it protects

**From 2026-09-20 until the same day, this gate could not fail.** `check-device-gate.sh` took the
newest commit in the table and passed if it was an ancestor of `HEAD`. The seed row above cites
`421377e`, which is an ancestor and always will be — so from the moment the ledger was created the
gate printed `ok` for every future change to the instrumented suite, for ever, with nobody running
anything. That is verbatim the `F-03` failure this file exists to prevent, and it ran in CI on
every push. `T-002`'s Definition of Done asked for a planted red; the seed row satisfied it by
accident.

It now requires the row's commit to **contain** the change it is offered as proof of, and it
self-tests `git merge-base --is-ancestor` against an impossible pair before trusting its answer —
a check whose empty output is indistinguishable from a check that read nothing is not a check.
| 2026-09-21 | `eaf0c51` | Quest 3 `<serial>` | `device-unworn` | **13 run, 0 red** | `SpaceBackTest` and `SpaceExitControlTest` are new, and the first of them **overturned the belief this task was planned on**. `adb shell input keyevent` had produced no key at all in the Space — but an injected key goes to the focused window, and the shell's was not landing here; injected through the instrumentation, `KEYCODE_BACK` reaches `ImmersiveActivity.dispatchKeyEvent`. Both defects were planted and watched failing on the device: removing `onBackPressed()` reds `backLeavesTheSpace`, and giving the leave control the enter control's glyph and word reds both exit tests. **Unmeasured and needing a person: whether a physical controller's B button becomes a `KEYCODE_BACK` here.** No injection from outside the process reaches this surface, so no run on this machine can answer it — `B-115`. |
| 2026-09-21 | `eaf0c51` | — | `device-unknown` | **correction, not a run** | **`fae0785` shipped with this gate red, and nothing caught it.** Its row cites `eb8e237`, its own parent, while the test it describes — `PanelGeometryTest` — landed in `fae0785` itself; the gate's rule is that a row's commit must CONTAIN the change, so it has been failing on every invocation since. That is the third instance of one shape (`8f7cb65` corrected the second), and the cause is not carelessness but arithmetic: **a row appended in the same commit as the test can only ever cite the parent.** The row above is the first written the other way round — commit the instrumented change, run it against the committed tree, then append. `B-116` carries making that impossible to get wrong rather than remembered. Nothing is edited above; the run `fae0785` describes did happen. |
| 2026-09-21 | `15e3ccb` | — | `device-unknown` | **not run** | The group verification of steps 4–5 added two cases to `core-common/src/androidTest/.../UiStringsTest.kt` — `AppError.ModelBusy` and the two-number DISK message, the two shapes `T-021` shipped without adding to the one list that proves an error renders — and widened its placeholder assertion from `%1$` to any numbered one. **Nobody ran them**: both headsets were offline or asleep for the whole group, and the class is instrumented precisely because it needs a real resource table. The JVM half (`UiStateMapperTest`) covers the mapping and says nothing about the rendering. Recorded rather than skipped, and `B-130` carries the run that is owed. |
| 2026-09-21 | `c57c566` | — | `device-unknown` | **not run** | `TodayScreenTest` is new — three Compose cases for `T-028`'s *New note* control, the day card, and the assertion that the editor does **not** open with the title field focused (`B-01`: a keyboard in the Space stops the panel receiving taps at all, so auto-focus is a trap). It compiles; nobody ran it. Both headsets have been offline or asleep since `eaf0c51`, which is eight tasks ago. The JVM half — `createNote returns a persisted note` — passes and covers the method, not the control. `B-141` carries the run. |
| 2026-09-21 | `6fe7110` | — | `device-unknown` | **not run** | Two more Compose cases in `TodayScreenTest` for `T-029`'s chip row — a tag narrows the list and *All* widens it, and a filter whose last note loses the tag still says which filter and still releases. They seed through `Graph.notes` rather than the editor, so they are deterministic; they have still never executed. Nine tasks since a headset was reachable. `B-141`. |
| 2026-09-21 | `edb3089` | — | `device-unknown` | **not run** | `T-032` added one case to `core-common/src/androidTest/.../UiStringsTest.kt` — `everyAppErrorShapeIsCovered`, which asserts `AppError::class.sealedSubclasses - covered` is empty rather than trusting the count a document carries (`F-14`). **It has not run**; the same assertion runs on the JVM in `UiStateMapperTest` on every build, and there it was watched reporting `[InsecureUrl, SttNotConfigured]` before those two were added. What only the device can answer is the other two cases in that class — whether the ids resolve through a **real resource table** to non-blank sentences — and that is unobserved for the four strings this task changed and the two it added. Ten tasks since a headset was reachable. `B-130` carries the run. |
| 2026-09-21 | `ac4c29e` | — | `device-unknown` | **not run** | Step 8's own verification widened `core-common/src/androidTest/.../UiStringsTest.kt`: the `ModelMissing` case now includes the **size** branch — `error_model_missing_size` is the project's second two-placeholder string, and the comment in that file counts how many times a placeholder string has reached the product without reaching this list; this was the third — and `everyAppErrorShapeIsCovered` recurses `sealedSubclasses` rather than reading one level. Both are JVM-mirrored in `UiStateMapperTest`, which runs on every build and was watched reporting `[InsecureUrl, SttNotConfigured]` before the gap was closed. **What only a device answers is whether the two new ids render through a real resource table with no unfilled `%n$`** — and that is exactly what this class exists for. Twelve tasks since a headset was reachable. `B-130`. |
| 2026-09-21 | `e490863` | — | `device-unknown` | **not run** | `REQ-058` widened `core-common/src/androidTest/.../UiStringsTest.kt` again: `AppError.Network` is a new shape of the sealed class, so `everyAppErrorShapeIsCovered` now has one more subclass to resolve a string for, and the export/save/delete-recording wording of `Storage(op)` is asserted per operation. **Not run, and the reason is a decision rather than an omission:** `DEC-0066` puts the headset session at the end of the plan, after a packaged out-of-Store build, so no agent task waits on a device nobody is holding. The gate now says so out loud — an acknowledgement is announced as one, with the instrumented-test count and the last executed row beside it, both computed at run time, so this row cannot be read as evidence of a run. |
| 2026-09-21 | `8327b76` | — | `device-unknown` | **not run** | `REQ-061`…`REQ-063` widened `core-common/src/androidTest/.../UiStringsTest.kt` once more — the strings a rewritten copy pass produced have to resolve, and `AppError`'s sealed shapes are asserted against them — and `app/src/androidTest/.../SpaceBackTest.kt` was **repaired**: it had stopped compiling at `REQ-048`, when `PermissionRequester.requestRecordAudio` gained `onDismissed`, and nothing noticed for a whole group of work because no gate compiled this source set. `check-all.sh` compiles it now. **Not run:** `DEC-0066` puts the headset session last. What this row certifies is that the suite COMPILES; that it passes is what the session is for. |
| 2026-09-21 | `9d9d716` | — | `device-unknown` | **not run** | The repair of `app/src/androidTest/.../SpaceBackTest.kt` landed in this commit rather than the one the row above cites, which is `B-116`'s ordering exactly: a row appended in the same commit as the test can only cite its parent. The **pre-push hook caught it** — `check-all.sh` exited 1 on the push and the gate named the commit — which is the first time this ordering rule has been enforced by a machine instead of remembered. `NoopPermissionRequester` now implements `requestRecordAudio(onResult, onDismissed)`; `compileDebugAndroidTestKotlin` is green for all three modules. **Not run:** `DEC-0066`. |
| 2026-09-22 | `8ef8677` | — | `device-unknown` | **not run** | `B-216` added three cases to `core-common/src/androidTest/.../UiStringsTest.kt`: `AppError.InsecureUrl` now has a redirect shape, so `everyAppErrorShapeIsCovered` has one more subclass to resolve a string for. **Nothing was run on any device**, and this row exists to say so rather than to let a green gate imply otherwise. `DEC-0066` puts the headset session after the packaged build, and the operator has asked for the application not to be launched until they are ready to test. The suite is **34 `@Test` methods** and it COMPILES — `check-all.sh` builds `compileDebugAndroidTestKotlin` — which is the whole of what this run can honestly claim. The last EXECUTED row remains 2026-09-21 `eaf0c51`. |

## What flips the expected red

`ImmersiveLaunchTest` passes when a person wears the headset, clears the Guardian boundary prompt,
and the run happens while the shell is not showing a system dialog. That is the observation; there
is no code-side substitute for it. If a later Spatial SDK exposes an assertable "the scene
composed" signal, the retro instruction this row is governed by names that as the retirement
condition.

## What consumes this file

`T-002` (CI): a push touching `*/src/androidTest/**` with no row here whose commit is an ancestor of
`HEAD` fails the build. The check is T-002's to write; this file is only its shape and its history.

## The walk — what a person does with a headset, in order

**Written against `1c1b781` by `T-043` (`DEC-0055`), and it names the build it was written for on
purpose.** The gate it replaces lived inside a dated handoff, told its reader to **hold** *Record*
— a gesture `DEC-0010` deleted in `732c92b` — and the likeliest repair was to reinstate the hold
and undo the decision (`F-02`). A gate is the part of a handoff that goes stale fastest, because
it names gestures, screens and addresses; it lives here, versioned with the ledger it feeds, and
**the entry point links to it rather than copying it**. Every copy is the one that will be stale.

**Before you start.** The two headsets, their addresses and the install command are in
`docs/handoff/2026-09-20-v2-entry.md`, *What needs a headset* — **not repeated here**, because
`T-036` is still deciding what the device register records and whether a serial belongs on a
public surface, and a third copy would have to move with it. Install, then read the version back:
`scripts/install-on-quest.sh` asserts the installed `versionCode` matches the APK it just built
and refuses when it does not (`:66-68`). **Do not skip that line** — a run that looks like it
still fails is usually testing the build from before the fix (`732c92b`).

Each step says what to observe, which `REQ` row it answers, and — where it cannot be walked yet —
which task unblocks it. **Write what you saw into the *Runs* table above, and into
`verification.md` if it answers a `REQ`: the `Human` column is the one column a machine may not
fill.**

| # | Step | What counts as pass | Answers | Blocked by |
|---|---|---|---|---|
| 1 | **Does the Space open, and does it come back?** Launch the panel, enter the Space, then leave it by the system back gesture. | The Space reaches RESUMED and its panel composes; leaving returns to the panel with the notes intact. `ImmersiveLaunchTest` passed unworn at `662e4ad`, so a red here is a **new** fact rather than the Guardian dialog. | `REQ-007`, whose ledger row has read `never` since `eaf0c51` | — |
| 2 | **Hand tracking, with no controllers paired.** Put the controllers down. Scroll the notes list, open a note, press *Record* with a bare hand. | Each gesture lands on the control you aimed at. The 72/64 dp floor (`Tokens.kt:34-41`) was sized for a pinch and has never met one. | `B-175` | — |
| 3 | **Dictate one Russian and one English sentence.** **Press** *Record*, speak, **press** again. There is no hold. | The transcript appears, the language is right, and the note carries the engine name. On `small`, expect roughly real time; `feature-stt.md:148-152` says why the published figure is a best case. | `REQ-003`, and `REQ-036`, which records that the published accuracy and latency describe a **greedy** decoder the build no longer ships | — |
| 4 | **Leave the screen mid-recording.** Start recording, then take the headset off or navigate away. | It stops and transcribes what was said rather than discarding it (`DEC-0032`, `T-020`). | `REQ-005` | — |
| 5 | **The secret survives a restart.** Set a cloud STT key in Settings, force-stop the app, reopen. This walks `SCN-016`, and `docs/ux/scenarios.md` called that scenario `draft` while this table instructed a person to perform it — the incident behind the linter's `[U080]` (`DEC-0057`). | The key is still configured, and the field shows it saved rather than echoing the value. `SecureSettingsTest` covers the round-trip; Robolectric has no `AndroidKeyStore`, so the JVM half proves nothing about the real one. | `REQ-006`, `REQ-033` | — |
| 6 | **Export the notes off the device.** Use the export route and open the result on the machine. | Files arrive readable. The vault is app-private with backup disabled, so this route is the only way out. | `REQ-008`, `SCN-011` | — |
| 7 | **Install a release-signed build.** | It installs and runs. | `REQ-029` | **`B-165`** — the keystore does not exist yet and creating it needs a person typing passwords; `T-037` is parked on it |
| 8 | **Update a headset that is not on this LAN.** | The written steps work from a release page. | — | **`T-041`**, parked: needs both a headset and step 7's release |
| 9 | **Run the instrumented suite:** `./gradlew connectedDebugAndroidTest` | Every `@Test` in `*/src/androidTest/**` runs. **Count them rather than reading a number here** — `grep -rho '@Test' */src/androidTest --include='*.kt' \| wc -l`; this cell said `33` while the tree held 34, and `check-docs.sh` §17 did not see it because the sentence carried no suite name (`SI-01`). **Append a row to *Runs* above whatever the result**, with the `Environment` value — a red with an attributable cause is information, and a red with none sends the next agent bisecting for a regression that is not there, which is the whole reason this file exists. | `REQ-016` | — |

**Steps 1–6 and 9 are walkable today.** Steps 7 and 8 are not, and each names the task that owns
it rather than reading as a failure — a gate whose unwalkable steps look red is a gate people stop
walking.

# Axis F — Documentation and process

Subject: `feat/v1-notes-core` @ `421377e`. Read-only. Every row below was checked by opening the
file named in *What the code says*; counts are counted from source, not restated.

The shape of the problem, in one sentence: **the documentation set was reconciled to the tree at
`830ad0a` on 2026-09-19 afternoon (commit `f770049`, "20 stale claims corrected"), and then nine
commits reshaped the product without the reconciliation being repeated.** The registers that were
built to notice exactly that — `DECISIONS.md`, `verification.md`, `backlog.md`, the retro, the
handoff — recorded none of it, and the gate stayed green through all nine.

## Findings

| ID | Sev | Where | Claim in the document | What the code says | Correction |
|---|---|---|---|---|---|
| F-01 | Blocker | `docs/handoff/2026-09-19-v1-build-handoff.md:77-80` | "**The next agent's task is H-28, the device gate below, and nothing before it.**" | Nine commits shipped after the handoff was last touched (`9daec47` → `421377e`), reshaping capture, speech routing, the model catalogue and the Space. `git log --stat 9daec47..HEAD` | The handoff is the named entry point (`:120-123`) and it describes a build that no longer exists. Write a 2026-09-20 handoff; until then mark this one superseded at the top, not inside. |
| F-02 | Blocker | `docs/handoff/2026-09-19-v1-build-handoff.md:96-103` (the device gate) | "3. **REQ-003** — … then hold *Record* and dictate one Russian and one English sentence." | There is no hold gesture. `HoldToTalkButton.kt` was deleted in `732c92b`; the control is press-to-start/press-to-stop — `strings.xml:115-116` (`action_start_record`, `action_stop_record`), `TodayScreen.kt:328`, `NoteEditorScreen.kt:98,113-114` | A person given this gate cannot perform step 3 and will report REQ-003 unverifiable. Rewrite the five checks against the current UI, and add the two things that are now the risk: entering the Space at all, and a dictation that commits itself. |
| F-03 | Blocker | `docs/handoff/2026-09-19-v1-build-handoff.md:115-118` | "**The instrumented suite has now run.** 13 tests on a Quest 3, 0 failures, 0 skipped … Re-run with `ANDROID_SERIAL=<device> ./gradlew connectedDebugAndroidTest`." | Two of the suite's tests fail on the current tree. `PanelSmokeTest.kt:20` asserts `onNodeWithText("Hold to record")`, a string no screen renders any more (`action_hold_to_record` exists at `strings.xml:30` and has **no** Kotlin caller). `ImmersiveLaunchTest` is declared failing by the commit that wrote it — `421377e`: "It fails right now, and correctly". | A next agent runs the suite, sees red, and cannot tell a stale assertion from a regression. Say in the handoff which tests are expected red and why; fix `PanelSmokeTest`'s assertion; give `ImmersiveLaunchTest` a board row. |
| F-04 | Blocker | `docs/handoff/2026-09-19-v1-build-handoff.md:8-11` | "**State: hardened, still not yet seen running** … Nobody has watched the app run, because the Quest went to sleep and `adb` has reported it `offline` since 13:12." | The same file at `:27` says the headset came back at 17:40 and the app is running; `docs/evidence/verification.md:162` records the panel observed rendering beside a virtual desktop at 17:55. | The document contradicts itself in two adjacent sections. A reader who stops at the status block plans a device session that has already happened. Rewrite the status block; keep `:27`. |
| F-05 | High | `docs/modules/app.md:26-27` | "**Hold to talk, not tap to start.** A stray raycast in a headset would otherwise leave the microphone open with nothing on screen saying so." | Tap to start is exactly what shipped (`TodayScreen.kt:157`, `NoteEditorScreen.kt:98`), and `app.md:33-36` in the same *Decisions worth keeping* list states the opposite decision. | Two contradictory standing decisions in one list, seven lines apart. Delete the first and record the reversal as a `DEC-` row (see F-M03 below), because a reversed decision with no register entry will be re-litigated. |
| F-06 | High | `docs/modules/app.md:16` | "**Screens** — Today, Note editor, Search, Assistant chat, Settings, plus the voice capture sheet." | `VoiceCaptureSheet.kt` was deleted in `732c92b` (245 lines removed); no file of that name exists under `app/src`. | Drop the sheet. Add `SpatialPanelOwners` and `SpeechChoice` to *Owns* — both are new files the module doc never learned about. |
| F-07 | High | `docs/ux/screens.md:11,15` | SCR-03 and SCR-07 `Coverage: app/src/main/kotlin/ai/passioncode/fabricvr/ui/VoiceCaptureSheet.kt` | The file does not exist. `python3 docs/ux/lint.py` still exits 0 — it does not check that a `Coverage:` path resolves. | Repoint SCR-07 at `TodayScreen.kt`; retire SCR-03 or redefine it as the in-panel capture control. And add a path-existence check to the UX lint, which is the only reason this survived a green gate. |
| F-08 | High | `docs/ux/screens.md:43` and `:64-74` | SCR-01 elements: "*Record* (primary, **hold-to-talk**)"; SCR-03: "Appears over SCR-01 or SCR-02 while the record control is **held** … *Release to stop* hint … the transcript preview with *Save* and *Discard*." | No hold, no overlay, no Save/Discard. `TodayScreen.kt:65-68` documents the opposite in its own header comment: "It has no sheet, no dialog and no hold gesture, and that is the design rather than an omission." | Rewrite SCR-01's element list and delete or rewrite SCR-03 wholesale. |
| F-09 | High | `docs/ux/flows.md:53-57` | FLW-02 task analysis: "1. Hold the record control … 2. Speak; release"; "**Rejected shape:** tap-to-start / tap-to-stop — lost because in a headset an accidental raycast leaves the microphone open with nothing on screen saying so; hold-to-talk cannot be left running." | The rejected shape is the shipped shape. The mermaid at `:60-77` still routes `R -->|release| T` and `V -->|tap Save| S`. | `flows.md` now tells a reader the current product was considered and rejected. Rewrite FLW-02 and move the old rationale into the `DEC-` row for the reversal, where a rejected-then-adopted shape belongs. |
| F-10 | High | `docs/modules/feature-stt.md:6-29` (*Owns* and *Checks*) | The module owns `WhisperEngine`, `ModelStore`/`ModelDownloader`, `RemoteWhisperClient`, `SttRouter`, `AudioRecorder`, `WavWriter` — "**19 JVM tests**". | `CloudTranscriptionClient.kt` (144 lines, sends the recording to a third-party endpoint), `SttProvider.kt` and `WhisperModel.kt` are in the module and in none of its doc. Source count: `SttRouterTest` 6, `ModelDownloaderTest` **8** (not 6), `RemoteWhisperClientUrlTest` 5, `WavWriterTest` 2, `CloudTranscriptionClientTest` **6** = **27**. | The single most privacy-relevant class in the repository has no entry in its module's contract file, against the `DOCMAP.md:72` propagation rule. Add all three; recount. |
| F-11 | High | `docs/DECISIONS.md:66-77` (DEC-0003) | "a configured whisper-server **is preferred** and its failure degrades to the device" | `Graph.kt:89-99`: a whisper-server URL alone routes nowhere; the remote engine is built only when `sttProvider() == SttProvider.SERVER`. A saved URL with the provider left at its default `LOCAL` (`SttProvider.kt:23`) is ignored. | DEC-0003's decision clause is false for the current build and carries no `Refined by`/`Contradicts` annotation. Record the provider decision (F-M07) and annotate DEC-0003. |
| F-12 | High | `docs/DECISIONS.md:101-104` (DEC-0005) | "the policy is enforced in code at **the two call sites**: `RemoteWhisperClient` … and `OpenRouterClient` …" | There are three. `CloudTranscriptionClient.kt:45` calls `NetworkPolicy.requireReachable(baseUrl)` and throws `AppError.InsecureUrl`. | Update the clause (and note the consequence a reader needs: the policy **permits** a cloud STT key and a microphone recording over plain `http://` to any RFC1918 host — `NetworkPolicy.kt:18-19`). |
| F-13 | High | `docs/DECISIONS.md:133-136` (DEC-0007) | "`close()` frees **inside** the engine's dispatcher … it is called on a **best-effort `onTerminate` only**." | `grep -rn onTerminate app/src core-common/src feature-stt/src` returns nothing — there is no `onTerminate` anywhere. `close()` now has two live callers: `Graph.kt:76` (a model change closes the loaded engine) and `Graph.kt:145` (a throwaway engine for *Transcribe again*). | The decision's stated lifetime rule no longer describes the code in either direction. Annotate and record the new rule (F-M12). |
| F-14 | High | `docs/evidence/verification.md:159` (REQ-009, re-observed) | "`UiStringsTest`, 2 tests: **every one of the 17 `AppError` shapes** resolves through the resource table to a non-blank sentence" | `UiStringsTest.kt:20-36` lists 17 *cases*, and `AppError.InsecureUrl` — a real shape, `AppError.kt:25`, the one `DEC-0005` created — is **not among them**. `AppError` has 11 constructors; 17 is the case count, not the shape count. | The row claims exhaustive coverage its evidence does not show. Correct the wording to "17 cases" and add `InsecureUrl`. |
| F-15 | High | `docs/evidence/verification.md:17` and `:55` | "`python3 scripts/graph.py producer` — from the project root"; "`scripts/exposure.sh` reports four states" | Neither file exists. `scripts/` holds `check-docs.sh`, `check-secrets.sh`, `install-on-quest.sh`, `push-model-for-tests.sh`. | The ledger's own producer block and its entire staleness mechanism are uncomputable here — which is why no row has ever been marked `behind` despite ten commits. Either vendor the two scripts or replace those sections with what this project can actually run. |
| F-16 | High | `README.md:13-15` | "v1 is **built and installed on the operator's Quest 3**; nobody has watched it run yet — the headset slept before the launch could be observed." | `9daec47` and `verification.md:162` record the panel launched, resumed in multi-window and photographed beside a virtual desktop on 2026-09-19 at 17:55. | Same stale status as F-04, in the file a new reader opens first. |
| F-17 | High | `README.md:22-23` | "**Voice** — **hold** to record from the headset microphone, transcribed **on the device** by whisper.cpp (`ggml-small-q5_1`…). **An optional whisper-server URL is preferred when set**, and the app falls back to the device and says so." | Three claims, three misses: no hold (F-02); five models (`WhisperModel.kt:21-25`); the URL is not preferred unless the provider is set to `SERVER` (`Graph.kt:97`). The cloud provider — a third-party endpoint that receives the recording — is not mentioned in the README at all. | Rewrite the bullet. This is the paragraph that tells a reader where their voice goes. |
| F-18 | High | `README.md:137-143` (*Security posture*) | Names exactly one secret: "The OpenRouter key is AES-256/GCM in AndroidKeyStore…" | `SecureSettings.kt:43-45` now stores `cloud_stt_url`, `cloud_stt_key`, `cloud_stt_model`. The same `run-as` caveat at `:141-142` applies to the STT key, and the cleartext note at `:142-143` says cleartext is permitted "only to a whisper-server on your own network" while `CloudTranscriptionClient` accepts any RFC1918 host too. | A security section that names one of two secrets is worse than none. Add the STT key and widen the cleartext sentence. |
| F-19 | High | `docs/evidence/backlog.md` (the whole table) | B-001…B-027 closed, B-028…B-036 open — every row sourced from `2026-09-19-v1-notes-core` / audit `2026-09-19-v1-audit`. | Nine commits of new work (`4bddfcc`…`421377e`) produced **zero** board rows: the capture reshape, cloud STT, the model catalogue, the hand-tracking declaration, the panel owners, beam search, and a known-failing `ImmersiveLaunchTest`. | The board is the file "a loop iteration reads at the top" (`:8-10`) and it has no knowledge of a day's work. See *Board and carry-over*. |
| F-20 | Medium | `docs/modules/core-notes.md:18` | "**A blank query matches nothing.** An empty search field is not a request to list the base." | `SearchViewModel.kt:43-46`: "A blank field is not 'no results': it lists what the person wrote recently" — `if (query.isBlank()) repository.observeNotes() else repository.search(query)`. Changed in `830ad0a`; the module doc was updated in the same commit for counts but not for this. | Delete or invert the bullet. It is a *Decisions worth keeping* row asserting the opposite of the code. |
| F-21 | Medium | `docs/modules/core-common.md:30-33` | "`UiStateMapperTest` (8) … `NetworkPolicyTest` (5) … `InMemorySecureSettingsTest` (3) and `LoggingTest` (1) — **18 JVM tests**" | 8 + 5 + 3 + 1 = 17, and 17 is what the source holds. The total is a typo, not a stale count. | 17. |
| F-22 | Medium | `README.md:114` | "`./gradlew testDebugUnitTest` # **115 JVM tests**" | 122 `@Test` in the six modules' `src/test` (third_party excluded). The delta is `CloudTranscriptionClientTest` (+6), `ModelDownloaderTest` (+2), `SettingsViewModelTest` (+2 net), etc. | 122. Same number is stale at `docs/handoff/…-build-handoff.md:25`. |
| F-23 | Medium | `docs/handoff/2026-09-19-v1-build-handoff.md:25,26,115` | "**115 JVM tests** … and **13 instrumented tests** on a Quest 3" | 122 JVM and 15 instrumented in source (`ImmersiveLaunchTest` and `ModelDownloadReachabilityTest` were added after). | Recount, and see F-03 — two of the 15 are red. |
| F-24 | Medium | `docs/evidence/verification.md:88-96` | The Environment vocabulary is declared as exactly five values: `production`, `preview`, `ci`, `local`, `—`, with "not invented per row". | Six rows at `:156-162` use `device`, which is in no declared list and in no runbook. | Declare `device` in the table (it is the right value; the rule it breaks is that it was never declared). |
| F-25 | Medium | `docs/DOCMAP.md:74` | Propagation row: "New/changed entity or field → `docs/modules/notes-core.md` (data model), `CONTEXT.md`" | The file is `docs/modules/core-notes.md`. `notes-core.md` does not exist. The gate's link check passed because the name sits in a backtick span, not a markdown link. | Fix the name. |
| F-26 | Medium | `docs/DOCMAP.md:82-84` (*Gates*) | One gate: `bash scripts/check-docs.sh`. | Three gates are mandatory per `docs/evidence/plans/2026-09-19-v1-hardening.md:26-28`: `check-docs.sh`, `check-secrets.sh`, `python3 docs/ux/lint.py`. `README.md:113-119` lists five checks. | The doc map's gate table is the one place an agent looks for "what must pass"; it lists a third of them. |
| F-27 | Medium | `docs/ux/screens.md:51,61,73,82,92,103,112,122` | Each screen carries "**Wireframe:** `wireframes/SCR-0N.md`". | `docs/ux/wireframes/` does not exist; no file named `SCR-0*` exists in the repository. | Eight dead pointers. Either produce them or say `none — text-only for v1`, which is what carry-over row 2 actually decided. |
| F-28 | Medium | `docs/ux/scenarios.md:100,135,161,206` | SCN-004/005/013 "Entry point: SCR-01 *Record* (**hold**)"; SCN-006 "first **hold** of *Record*", ":164" "**Hold** *Record* for the first time", ":169" "starts on the next **hold**". | Press-to-toggle. SCN-004's own step 1 at `:103` says "Press *Record* once" — the entry-point line contradicts the step list inside the same scenario. | `732c92b` rewrote the bodies of SCN-004/005 and left the entry-point lines and SCN-006/013 untouched. |
| F-29 | Medium | `docs/ux/scenarios.md:125` and `:209,215` | SCN-004 errors: "silence → 'Nothing was heard' with *Try again*, **the sheet stays open**"; SCN-013: "the **sheet** explains that dictation needs the microphone", "UI elements: … explanation **sheet**". | No sheet exists. `TodayScreen.kt:295,301` draws `state_needs_microphone` and `state_nothing_heard` inline above the button. | Three residual references to a deleted surface inside scenarios the same commit claimed to have updated. |
| F-30 | Medium | `docs/modules/feature-stt.md:14` | "`ModelStore` / `ModelDownloader` — `ggml-small-q5_1.bin` (190 MB) from Hugging Face" | `FileModelStore(root, model: WhisperModel)` — five models, 32 MB to 574 MB, each with a pinned digest (`WhisperModel.kt:21-25`, `ModelStore.kt:40-53`). | Name the catalogue and the default. The 574 MB entry is a material fact about a device whose thermal ceiling this very file measures at `:68-72`. |
| F-31 | Medium | `CONTEXT.md:14-16` (glossary, *Transcript*) | "records which engine produced it (**on-device Whisper or a remote whisper-server**) and the detected language" | Three sources now: `SttProvider.kt:13-19` — `LOCAL`, `CLOUD` (an OpenAI-compatible third party), `SERVER`. | The glossary is the declared single home for the term (`DOCMAP.md:106-108`); a term whose definition omits a whole class of producer is the "one definition per term" rule failing quietly. |
| F-32 | Medium | `docs/evidence/backlog.md:6` and `:77-84` | Header: "a closed row is marked closed, **with the commit that closed it**". *Closed* section: "Rows leave the table above **only** into this list" … "*None yet.*" | 27 rows are marked `closed` in the live table and the *Closed* list is empty; `B-027`'s `Home` cell holds prose ("20 divergences found by an independent read…") instead of a commit. | The file states a rule and then violates it 27 times. Either move the closed rows or amend the rule — but not both states at once, because the next agent will pick one at random. |
| F-33 | Medium | `docs/evidence/verification.md:124-162` | Eleven rows `Observed at d9967a3/325e100/e235a81`, six at `830ad0a`, one at `f770049`. | `git rev-list --count 830ad0a..HEAD` = **10**; `f770049..HEAD` = **9**. Every row in the ledger is behind the tree; none is marked so, because the tool that marks them does not exist (F-15). | See *Verification ledger state* for the per-row reason. |
| F-34 | Low | `docs/evidence/audits/2026-09-20-v2-audit-plan.md:4-5` | "123 JVM tests and 14 instrumented tests" | 122 JVM and 15 instrumented in the six project modules; 123 is the count with `third_party/whisper.cpp`'s own `ExampleUnitTest`, which is not in this build. | Off by one in both directions; the audit's own framing number. |
| F-35 | Low | `docs/ux/scenarios.md:375-380` | SCN-015 expected result contains "Deleting is one tap and costs nothing when it was a miss." **twice**, four lines apart. | — | Editing residue from `ff7787b`. |
| F-36 | Low | `docs/ux/scenarios.md:22-23` | SCN-014 and SCN-015 carry `Status: draft`. | Both describe behaviour that shipped in `2f91df7`/`ff7787b` and is on the device. | `draft` beside shipped behaviour makes the status column unreadable; the other thirteen say `validated` for behaviour nobody has walked either. Pick one meaning. |
| F-37 | Low | `core-common/.../theme/Tokens.kt:39-42` (and `TodayScreen.kt:328`) | `holdButtonHeight` — "for the one thing the product exists to do" | There is no hold. The token names a gesture that was deleted. | Rename to `captureButtonHeight`; a name is documentation that the compiler checks. |
| F-38 | Low | `app/src/main/res/values/strings.xml:30,63,69` | `action_hold_to_record` ("Hold to record"), `state_keep_holding`, `state_allowed` ("Allowed. Hold the button to record.") | No Kotlin caller for any of the three. One of them is what `PanelSmokeTest.kt:20` still asserts (F-03). | Delete them, after fixing the test that depends on the dead string. |
| F-39 | Low | `docs/evidence/specs/2026-09-19-v1-notes-core-design.md:41` (the drift table) | "§2 file layout `ui/ModelDownloadDialog.kt` → never existed; the download UI is a state inside `VoiceCaptureSheet.kt`" | `VoiceCaptureSheet.kt` is gone too. The dated spec's *as built* note is itself now out of date. | The convention here is right — a dated spec keeps its body and carries drift as a table. That table just needs a 2026-09-20 tranche (see *Notes for the merge*). |

Severity totals: **Blocker 4 · High 15 · Medium 15 · Low 5** = 39.

## Decisions missing a DEC row

Register state: 7 entries, `Next free ID: DEC-0008`, last written 2026-09-19 during the hardening
pass. Nothing after `f770049` produced one. Proposed ids are sequential from the current allocator
and are **not reserved** — read the allocator again before writing.

| Proposed | Decision | Trade-off it bought and paid | Commit |
|---|---|---|---|
| DEC-0008 | The decoder runs **beam search at width 5** with non-speech-token suppression and an explicit temperature ladder, not greedy with whisper.cpp's bare defaults. Beam width is a constructor parameter (`WhisperEngine.kt:26,104`), so a future *speed* setting is an argument. | Accuracy on a short utterance, bought with time — and the same benchmark showed the purchase is void once the device is thermally limited (beam and greedy within 5% after six minutes of inference). So the decision is: pay for accuracy on a cool device, and accept that the setting stops mattering exactly when the user is waiting longest. | `bb82929` |
| DEC-0009 | The manifest **declares `oculus.software.handtracking`** (`required=false`) and requests `com.oculus.permission.HAND_TRACKING`, solely to stop Horizon OS intercepting the immersive launch with `common_system_dialog_app_launch_blocked_controller_required`. | The Space becomes launchable with no controllers paired. Paid: the app now advertises a capability it does not implement — no hand input exists anywhere in the tree — and requests a permission it never uses. Store review and the app's own permission list read that declaration. **Still unverified on a device** (`732c92b`'s own message). | `732c92b` |
| DEC-0010 | **The hold gesture and the capture sheet are deleted**; recording is press-to-start, press-to-stop, with every blocking state drawn above a control that never moves. | Reverses the v1 reasoning kept in `app.md:26-27` and `flows.md:56-57`: a hold cannot be left running by a stray raycast, a tap can. Paid: the microphone can now be left open by a mis-tap, and the mitigation is only that the button says *Stop recording*. Bought: a thought can be longer than a person can hold a ray steady, and no window has to be dismissed. | `732c92b` |
| DEC-0011 | **A finished transcript commits itself**: the note is written with no Save step and no preview to accept or discard. | Removes the whole cost of the feature for the common case. Paid: every misfire, every "Nothing was heard", every wrong decode becomes a row in the base that the person must delete; the only undo is the per-note *Delete*. The scenario's old *Save*/*Discard* pair was the confirmation being spent here. | `2f91df7` |
| DEC-0012 | **The transcript is auto-copied to the system clipboard** on commit, with a transient "Saved, and copied" line. | Dictating in a headset is usually done to paste elsewhere, so the clipboard is the result rather than a second action. Paid: the app silently overwrites the system clipboard on every dictation — a cross-app resource the person did not offer — and the only notice is a line that disappears. | `2f91df7` |
| DEC-0013 | **Cloud STT over an OpenAI-compatible endpoint** (`CloudTranscriptionClient`): one client for Groq, OpenAI and self-hosted faster-whisper; base URL, key and model are settings; the constructor refuses an endpoint `NetworkPolicy` will not allow. | One client instead of one per vendor, and a quality ceiling the headset cannot reach. Paid: the recording leaves the device; a second secret enters the Keystore (`cloud_stt_key`); `DEC-0003`'s "speech runs on the device" becomes one of three answers; and the policy still permits the key and the audio over plain `http://` to any RFC1918 host. | `2f91df7` |
| DEC-0014 | **Where speech goes is an explicit provider choice** (`SttProvider` LOCAL/CLOUD/SERVER), replacing "a whisper-server URL is preferred when it is set". | The person decides where their voice goes before anything is sent, with a line per option saying what it does. Paid: it silently contradicts `DEC-0003` — a URL saved under the old rule now routes nowhere, and an upgrading user's configured server goes quiet with no message. | `2f91df7` |
| DEC-0015 | **The on-device model is a catalogue of five**, each with the size and SHA-256 its publisher states, all in one directory so switching keeps what is downloaded; `small` is the default and the only one whose latency was measured. | Choice, and a documented default. Paid: 539 MB and 574 MB options are offered on a device measured at 2.5× slowdown under sustained inference, and `WhisperEngine.name` is still the literal `"whisper-small-q5_1"` (`WhisperEngine.kt:29`) — so the per-note engine badge lies for four of the five. | `2f91df7` |
| DEC-0016 | **`expectedSha256` is enforced, pinned to the vendor-published Git-LFS object id.** Until `f770049` the downloader enforced the size and computed the digest for the record only. | Bytes from anywhere must hash to a value read from a primary source — this is what closed carry-over row 16, which refused to pin a digest nobody had checked. Paid: a vendor re-upload breaks every download until the pin is edited, and there is no mechanism that notices. | `f770049` |
| DEC-0017 | **The redirect allow-list is declared by the `ModelStore`, matched on the registrable suffix** (`{huggingface.co, hf.co}`), not derived from the download URL. | Fixes a total outage: the guard was refusing the vendor's own CDN (`us.aws.cdn.hf.co`) and no model could be downloaded at all. Paid: the list is now hand-maintained per store, and `hf.co` admits every subdomain the vendor may ever serve, including ones it does not control today. Accepted because the digest pin (DEC-0016) is the real line and this is defence in depth. | `4bddfcc` |
| DEC-0018 (retired, superseded by DEC-0028) | **`SpatialPanelOwners` supplies all four Compose host owners** — lifecycle, view-model store, saved-state registry, back dispatcher — for a Spatial SDK panel hosted by a plain `android.app.Activity`, and **all four together** rather than the one that crashed. | Stops a crash on every entry into the Space. Paid: a hand-driven lifecycle that must be stepped by the activity (`SpatialPanelOwners.kt:45-57`) and can drift from it; and the class of defect stays open — the owners are provided, but nothing proves the set is complete. | `deb604d` |
| DEC-0019 | **The whisper context is closed and rebuilt when the chosen model changes**, and a *Transcribe again* against a non-selected model runs on a throwaway engine that is closed in a `finally`. | Two sets of weights would sit in native memory for the life of the process; the small model alone is 190 MB. Paid: this contradicts `DEC-0007`'s "kept for the process … called on a best-effort `onTerminate` only" — and `onTerminate` does not exist at all, so the decision text is now wrong at both ends. | `2f91df7` |

Twelve. Each needs a `Consequences / affects:` line, and three of them (`DEC-0003`, `DEC-0005`,
`DEC-0007`) require a `Contradicts:` marker and an annotation on the target, which `check-docs.sh`
section 6 will then enforce.

## Verification ledger state

**Every row is behind the tree, and nothing in the file can say so.** The staleness machinery at
`:48-73` delegates to `scripts/exposure.sh`, which does not exist (F-15), so the four states it
defines have never been computed once. Measured by hand:

| Rows | Observed at | Commits behind HEAD | Overtaken by | Kind |
|---|---|---|---|---|
| REQ-001 (`:124`) | `d9967a3` | 13 | the launch is now gated by the hand-tracking declaration and `SpatialPanelOwners` | code |
| REQ-002, 003, 004, 005, 008, 009, 010 (`:125-133`) | `325e100` | 12 | see per-row below | code |
| REQ-006, 007, 011 (`:129-134`) | `e235a81` | 11 | REQ-007's surface crashed on every entry until `deb604d` | code |
| REQ-003 ×2, REQ-006, REQ-009, REQ-010, REQ-001 (`:156-161`) | `830ad0a` | **10** | see per-row below | code |
| REQ-001 beside a virtual desktop (`:162`) | `f770049` | **9** | the Today screen it photographs no longer exists in that form | code |

Per-row, naming the invalidation kind the file's own rule at `:69-73` demands:

- **REQ-003, "Voice note transcribed on device"** (`:156`) — **code**. `bb82929` replaced greedy
  sampling with beam search at width 5, changed the temperature ladder and turned on non-speech
  suppression inside `whisper_full_params`. The fixture assertions ("ask not what your country",
  "тест") are unchanged, but they were observed against a different decoder. Cheap to re-observe.
- **REQ-003, latency** (`:157`) — **code**, and the row already knows it. 1.31× real time was
  measured greedy; the shipped decoder is beam. `feature-stt.md:68-72` says the figure is a best
  case; the ledger row does not carry that caveat.
- **REQ-004, "Remote STT with visible fallback"** (`:127`) — **code**. `SttRouter`'s inputs are now
  chosen by `SttProvider` (`Graph.kt:89-99`), and a third engine can occupy the remote slot. The
  cited `SttRouterTest` still passes; what it proves no longer covers the routing decision above it.
- **REQ-006, "Assistant key encrypted"** (`:158`) — **code**. A second secret (`cloud_stt_key`) now
  travels the same Keystore path and is covered by no row.
- **REQ-009, "Honest degradation"** (`:159`) — **overclaims** (F-14): "every one of the 17 `AppError`
  shapes" excludes `InsecureUrl`, which is the shape `DEC-0005` created.
- **REQ-010, "Model download"** (`:160`) — **code**. The digest was pinned in the observed commit,
  but `4bddfcc` then rewrote the redirect rule and `2f91df7` made the store's digest come from
  `WhisperModel` — four digests that have never been exercised by any download at all.
- **REQ-001, "the panel's Compose tree stands up"** (`:161`) — **code, and the cited evidence is now
  red**. `PanelSmokeTest.kt:20` asserts a string no screen renders (F-03).
- **REQ-007, "panel ↔ immersive Space"** (`:130`) — the worst row in the file. It records
  `Auto: none`, note "both activities compile and are in the manifest; the switch has NOT been seen
  on the device", and was never re-observed. In the nine commits since, the Space was found to crash
  on **every** entry (`deb604d`) and to be refused by the shell before that (`732c92b`), and both
  fixes are unverified on a device. Nothing in the ledger records either the defect or the fix. A
  reader of the ledger alone would conclude REQ-007 is merely unobserved, not that it was broken.

**Requirements never observed by a person: all eleven.** `Human` is the literal `never` on all 18
rows, which is correct and is the file working as designed — but it means every REQ-001…011 claim in
this repository rests on machine evidence alone. The nearest approach is `:162`, a screencap taken
while the headset was worn, and the row itself refuses to call that human observation.

**Missing entirely**: no producer block and no rows for the run that produced `4bddfcc`…`421377e`.
The file's own rule at `:205-207` is that stage 8 writes one row per REQ the run shipped. That run
touched REQ-001, 003, 004, 007 and 010 and wrote nothing. `Environment: device` is used on six rows
and declared nowhere (F-24).

## Board and carry-over

**Rows that should exist and do not.** Nine commits produced no board row at all. At minimum:

| Should be | Why it is a row and not a note |
|---|---|
| `ImmersiveLaunchTest` fails on the current tree | `421377e` states it plainly: "It fails right now, and correctly … the headset is awake but showing a Guardian dialog". A red test with no row is a red test the next agent discovers by running it. |
| `PanelSmokeTest` asserts a deleted string | F-03. Distinct from the above: this one is a stale assertion, not a device condition, and it will stay red on any device. |
| The hand-tracking declaration is unverified on a device | `732c92b`: "**This is not yet verified on a device** — both headsets went off the network before the build with the fix could be installed". This is the sole evidence for the Space being launchable at all. |
| `WhisperEngine.name` is hard-coded `"whisper-small-q5_1"` for all five models | The per-note engine badge — the thing `ff7787b` added so a person can judge whether a bigger model is worth the wait — reports the wrong model for four of five. |
| The four new model digests have never been exercised | DEC-0016's guarantee is untested for `tiny`, `base`, `medium`, `large-turbo`. |
| Documentation reconciliation for 2026-09-20 | B-027 closed the equivalent for the 2026-09-19 tree. Thirty-nine findings in this report are the same class recurring, which is the argument for making it a standing gate rather than a row. |

**Rows marked closed whose commit does not close them.** B-001…B-026 hold real commits and hold up
on inspection. Two exceptions:

- **B-027** (`:40`) — state `closed`, `Home` = prose, **no commit**. The header at `:6` requires the
  commit. It was closed by `f770049`; say so.
- **B-003** (`:16`) — "H-03 Voice capture as an in-window overlay; **the hold survives** (AUD-01/03/10/39)",
  closed by `1fb8510`. The hold did not survive: `732c92b` deleted it eight commits later. The row is
  closed against work that has since been undone, and no row records the undoing. This is exactly
  the trail a future agent follows to understand why the hold exists, and it dead-ends at a fix that
  is gone.

**The carry-over ledger** (`…-notes-core-carryover.md`) is in better shape: all sixteen rows have a
home (`B-029`…`B-036`, `B-001`, `B-019`, `B-021`, `B-027`, `B-028`, or `waived` with a revisit
condition). Still open through their board rows: **row 1/2** → B-035 (no brand pack; `2f91df7`'s own
message re-states the violation: "Written without the brand pack, which this project does not have
yet"), **row 11** → B-029 (graphify has no key; REQ-011 still `partial`), **row 12** → B-028 (the
human walk), **rows 3–8** → v2+ scope. Rows 9 and 10 are `waived` on conditions that have quietly
come true — row 10's waiver rests on "the planted-defect checks in the audit plan H-01…H-28 replace
[TDD]", and `deb604d` records a planted defect that a test **passed over**. That waiver should be
revisited, not silently carried.

No carry-over row was written for the 2026-09-19-evening/2026-09-20 work either. Its own rule:
*deferred out loud, or lost.*

## Handoff risks

What a new agent gets wrong by trusting `docs/handoff/2026-09-19-v1-build-handoff.md` today, in the
order they would hit it:

1. **They believe nothing has been seen running** (`:8-11`) and plan a first device session — which
   already happened at 17:55 on 2026-09-19 (F-04, F-16). Wasted, not dangerous.
2. **They run the device gate at `:86-103`.** Step 3 tells them to *hold Record*; there is nothing to
   hold. The most likely outcome is a report that REQ-003 cannot be verified, or — worse — a "fix"
   that reinstates the hold, undoing `DEC-0010` because no register entry exists to stop them. This
   is the destructive path.
3. **They re-run the instrumented suite** as `:118` instructs and get two failures (F-03). With the
   handoff asserting "0 failures, 0 skipped", the honest reading is a regression, and they will
   bisect for one that is not there.
4. **They take "the next agent's task is H-28 and nothing before it"** (`:77-79`) literally and skip
   the entire 2026-09-20 surface: a Space whose launch fix is unverified on hardware, a cloud client
   that sends audio off the device, and four model digests that no download has ever checked.
5. **They connect to `<headset-ip>:5555`** (`:90`), a single hard-coded address, while the v2 audit
   plan records two headsets. Minor, but it is the first command in the block.

**The exact next task the handoff names** is H-28, the human walk of SCN-001…013. **What is actually
next** is narrower and must come first: get the current build onto a headset and establish whether
the Space opens at all — that is the one claim in the tree with no device evidence behind it
(`732c92b`: not verified; `deb604d`: the fix; `421377e`: the test that would prove it is red for a
person-in-the-headset reason). Only then is a scenario walk meaningful, and it has to be a walk of
SCN-001…015 against the current UI, not the 2026-09-19 script.

Per the repository's handoff rule, the 2026-09-20 work has no handoff entry of any
kind: no objective, no completed/open split, no decisions, no checks actually run, no next task.
That is the single highest-value document missing from this repository.

## Retro — instructions to add or prune

`docs/evidence/retro.md` is **empty**: zero standing instructions, zero run stamps, zero log
entries, zero retirements — after two full runs, a 58-finding audit and nine commits. The gate
reports nothing because section 9 only checks that SHAs in the retro resolve, and there are none.
Stage 10 has never run.

Five incidents from 2026-09-19/20 each produced a rule that **no check can decide**, which is the
exact bar this file sets at `:18-20`. Each is written in a commit message and nowhere else:

| Proposed instruction | Because (the incident) | Retire when |
|---|---|---|
| **A test written to catch a defect must be watched failing with that defect present, at the level the defect lives.** A passing plant means the test is at the wrong level, not that the code is fine. | `deb604d`: a Compose test that omitted the owner **passed**, because `LocalOnBackPressedDispatcherOwner` falls back to the test rule's `ComponentActivity` host. The defect was planted and watched passing. The real test had to launch the activity. | a harness exists that runs Compose tests under a non-`ComponentActivity` host |
| **The absence of a failure is not the presence of a feature.** A test that asserts only "it did not crash" is satisfied by a surface that never appeared. | `421377e`: the launch test could pass over a Space the shell had deferred. It now waits for a marker set *inside* the composition. | a platform-level assertion for "the scene composed" exists |
| **A gate is not a gate until a violation has been planted and watched failing it.** | `f7d656d` / handoff `:37-42`: `check-secrets.sh` piped `git ls-files -z` into `grep -zZv`, and `grep` on this machine is ugrep, where `-z` means *decompress*. **Ten planted credential shapes all passed.** | every gate in `DOCMAP.md` carries a self-test in CI |
| **Verify against the build that is installed, not the build that was compiled.** Name the version you are looking at before reading its behaviour. | `732c92b`: "the run that looked like it still failed was testing the previous build, which had never installed". A real fix was nearly rejected as ineffective. | `install-on-quest.sh` prints and asserts the installed `versionCode` after every install |
| **A mocked test proves the rule the code implements, never that the rule still matches the world.** Any rule derived from a third party needs one test that touches the third party. | `4bddfcc`: every redirect test pointed at a server the test itself started, so all six were green while **no model could be downloaded at all** — the guard was refusing Hugging Face's own CDN. `ModelDownloadReachabilityTest` is the answer and cost about a megabyte. | every external contract in the tree has a reachability test |

Two more that belong in the log rather than the standing list, because they are already checks or
already decided:

- The 2026-09-19 reconciliation found **20 stale claims** and this audit finds **39** one day later.
  The lesson is not "reconcile harder"; it is that the module-doc propagation rule (`DOCMAP.md:70`)
  is marked `review` and therefore has no enforcement at all. Candidate grade-1 check: refuse a
  commit that deletes a `.kt` file still named in `docs/`.
- Carry-over row 10's TDD waiver rests on planted-defect checks, and a planted defect passed
  (`deb604d`). Record that the waiver's condition failed once.

Nothing to prune: the list is empty. **Run stamps for both runs are also missing**, which is what
makes `git log <sha>..HEAD` unusable as the "everything since a rule last fired" query the file
describes at `:59-61`.

## Gate output

`bash scripts/check-docs.sh`, verbatim, at `421377e`:

```
ok:      decision home: register (7 entries, ids DEC-####)
ok:      relative links resolve (48 files)
ok:      every referenced id is defined (7 decisions, 1 open questions)
ok:      next free DEC id is correct (DEC-0008)
ok:      next free OQ id is correct (OQ-0002)
dormant: register-size cross-check — no 'Register size:' line stated
ok:      consequences propagate (backlog below the floor: 0)
ok:      supersede and contradict targets are annotated
ok:      no retired decisions yet
ok:      decision statuses are inside the closed vocabulary
ok:      open-question statuses are inside the closed vocabulary
ok:      every commit reference in docs/evidence resolves AND is reachable from HEAD
ok:      doc map and registers agree in both directions
OK: documentation gate — shape register · 7 decisions · 1 open questions · propagation backlog 0 (floor 0) · retired residue 0 (floor 0)
```

Exit 0. For completeness, the other two gates also pass: `python3 docs/ux/lint.py` →
`OK — docs/ux is consistent`, exit 0.

**Thirty-nine findings, including four Blockers, sit behind three green gates.** What the gate does
not check, ordered by what it cost here:

1. **That a path named in prose exists.** Section 1 resolves *markdown links*; a path inside a
   backtick span is invisible to it. That is how `VoiceCaptureSheet.kt` survives as the declared
   coverage of two screens (F-07), `docs/modules/notes-core.md` survives in the propagation matrix
   (F-25), and eight `wireframes/SCR-0N.md` survive (F-27). **This is the single highest-value
   addition**: extract every backticked token that looks like a path and `test -e` it.
2. **That a command named in prose exists and runs.** `scripts/graph.py` and `scripts/exposure.sh`
   are cited as the ledger's own instrumentation and neither is in the repository (F-15). Same
   mechanism as (1), applied to `scripts/*` and `./gradlew *`.
3. **That a symbol named in prose exists in the code.** `onTerminate` (F-13), `HoldToTalkButton`,
   `VoiceCaptureSheet`. A `git grep -q` per backticked identifier under `docs/modules/` would have
   caught F-06, F-10 and F-13.
4. **That a stated count matches a counted one.** `115 JVM tests`, `18 JVM tests`, `19 JVM tests`,
   `13 instrumented` (F-21, F-22, F-23). The gate already computes register sizes; the same idea
   applied to `@Test` counts is a dozen lines and closes a recurring class.
5. **That a deleted source file is not still named in `docs/`.** A pre-commit check over
   `git diff --diff-filter=D --name-only` against `git grep -l` in `docs/` would have made F-06,
   F-07 and F-08 impossible.
6. **That the retro is not empty after a run.** Sections 9 checks the SHAs *in* the retro resolve;
   it cannot notice there are none. A stamp count of zero against a non-empty `git log` is a
   measurable state.
7. **That every `Observed at` in `verification.md` is compared to `HEAD`.** The file describes four
   staleness states and computes none (F-33). Three lines of `git rev-list --count` would print the
   disclosure the file asks for.
8. **That a row marked `closed` carries a commit.** F-32; section 9 already validates SHAs in
   `docs/evidence`, so the machinery exists.
9. **Prose meaning** — correctly disclaimed in the script's own header at `:8-9`. F-05 (two opposite
   decisions in one list) and F-09 (a rejected shape that shipped) are review findings and always
   will be. Worth stating so that a green is never read as covering them.

## Not covered

- **The research corpus** (`docs/research/**`, 13 files, two long RU reports). Read only where the
  handoff cites it. Its claims about the market and the platform are not checkable against this
  code and were out of scope for this axis.
- **Whether `docs/product/product-definition.md` still describes the product.** Skimmed: §3.1–3.4
  describe CRDT sync, Excalidraw sketches, a Mac companion and an agent that acts on a computer —
  all correctly marked v2+ there, so nothing in it is false about v1. I did not audit its roadmap
  against the board.
- **The maintainer's wiki entry** for this project, claimed written and pushed by
  `f770049` and the handoff `:113-114`. It is outside this repository and I did not open it. It is
  certainly stale by the same nine commits.
- **Whether the JVM and instrumented suites actually pass.** Counts are `@Test` occurrences in
  source; no Gradle run was made (read-only, and a connected device is needed for half of it). F-03's
  claim that two instrumented tests fail rests on reading `PanelSmokeTest.kt:20` against `strings.xml`
  and on `421377e`'s own commit message, not on an execution.
- **Axes A–E.** Where a documentation claim rests on a code judgement (whether the re-transcription
  replacement rule is right, whether the owners' lifecycle is correct), I recorded the divergence
  between doc and code and left the code verdict to the owning axis.
- **`docs/evidence/audits/inputs/A,B,C.md`** — the 2026-09-19 raw reviewer inputs. Superseded by the
  merged audit, which I did read.

## Notes for the merge

- **F-01…F-04 are one incident with four faces.** They should merge into a single work package:
  *the handoff and the device gate are rewritten for the current build before anyone puts a headset
  on.* Splitting them produces four tasks that each half-fix the same document.
- **F-05, F-08, F-09, F-28, F-29, F-37, F-38 are also one incident**: `732c92b` deleted a gesture
  and a surface, updated the two scenario bodies it touched, and left every other reference
  standing. One sweep — `git grep -in 'hold\|sheet' docs/ app/src/main/res` — closes all seven.
  Axis D is walking the same scenarios; expect overlap on F-28/F-29 and defer to its `file:line`
  evidence for the step-level detail.
- **The twelve missing DEC rows should land as one commit**, before any code change from this audit.
  Three of them annotate existing entries (`DEC-0003`, `DEC-0005`, `DEC-0007`), which `check-docs.sh`
  section 6 then enforces — so writing them turns three of this report's High findings into gate
  failures that cannot recur.
- **F-15 (the two missing scripts) blocks the fix for F-33.** Sequence them: the ledger cannot be
  brought current until something can compute what is behind.
- **The gate additions in items 1–5 of *Gate output* are worth more than any individual finding
  here.** Thirty-nine divergences one day after a reconciliation that fixed twenty is not an
  attention problem; it is the absence of a mechanical check over a class of claim that this
  project makes constantly. If the merged plan carries one thing from this axis, carry that.
- **Axis E also reads `docs/modules/*.md` and the design spec.** Where it reports dead code
  (`VoiceState` members, `VoiceViewModel.retranscribe(language)`, unused strings), F-38's three dead
  strings are a subset — merge rather than double-count.
- I wrote nothing outside this file. No document, register or ledger was edited.

> **SUPERSEDED on 2026-09-20 by `docs/handoff/2026-09-20-v2-entry.md`. Do not act on this file.**
>
> Everything below describes a tree nine commits old. Three specific instructions here are now
> wrong and one of them would waste a day: the device gate tells you to *hold* the Record button,
> a gesture deleted on 2026-09-20 (`DEC-0010`); the state section says "13 tests, 0 failures" while
> two instrumented tests are red; and the next task it names was overtaken. It is kept because it
> is the record of what was true on 2026-09-19, not because it is a map.

# Handoff — Fabric VR v1 build (2026-09-19)

## Objective

Define v1, plan it in detail, build it, and install it on the connected Quest 3. Run through
task-pipeline stages 0–10; branch `feat/v1-notes-core`.

## State: hardened, still **not yet seen running**

The APK is on the headset from an earlier build. Nobody has watched the app run, because the Quest
went to sleep and `adb` has reported it `offline` since 13:12. **Three requirements are therefore
unobserved and this document does not claim them.**

Since that install, the audit below was carried out and **27 of its 28 hardening tasks are done and
pushed**. The one that is not is the one that needs a person wearing the headset.

| Stage | Verdict | Evidence |
|---|---|---|
| 0 Intake | pass | brief, REQ-001…011, registers, doc map, board, retro seeded |
| 1 Docs study | pass | `…-docs-study.md` — Spatial SDK 0.14.0, whisper.cpp v1.9.4, OpenRouter, AndroidX, all with URLs |
| 2 Brainstorm + decompose | pass | module map, 6 modules, operator approved |
| 3 Spec | pass | `…-design.md` plus its dated "as built" notes; UX track ran (13 scenarios, linter green) |
| 4 Plan | pass | 18 tasks; REQ set equality holds |
| 5 Build | pass (**inline**, no subagents) | `./gradlew :app:assembleDebug` exit 0 |
| 6 Tests | pass | **115 JVM tests, 0 failures** (60 before the hardening pass) and **13 instrumented tests on a Quest 3, 0 failures, 0 skipped** |
| 7 Lint + install | pass | `lintDebug` 0 errors; `check-secrets.sh` OK **and now proven by ten planted shapes**; `assembleRelease` produces a 74 MB minified APK |
| 8 Post-deploy | **partial** | the headset came back online at 17:40. The hardened APK is installed and running (`topResumedActivity=…PanelActivity`, `mode=multi-window`, no `AndroidRuntime` line), **13 instrumented tests pass on the Quest 3**, and whisper transcribes both fixtures with the right language. What is still unobserved is everything only a person can judge: the thirteen scenarios walked by hand |
| 9 Docs + wiki | pass | README, 6 module docs, DEC-0002…0007, docs gate OK, UX lint OK, **wiki entry written** (the maintainer's wiki, pushed). Code graph still NOT_RUN (graphify needs a key — board row B-029) |
| 10 Acceptance | not reached | blocked on stage 8 |

### What the hardening pass changed, in one paragraph each

- **The main path very likely did not work before it.** The hold-to-talk gesture opened a `Dialog`,
  and the new window cancelled the press that opened it; the same sheet asked for the microphone
  from a Compose tree that, inside the Spatial SDK's panel, has no `ActivityResultRegistryOwner`.
  Both are fixed and the permission is requested from the activities.
- **Two gates were lying rather than failing.** `scripts/check-secrets.sh` piped `git ls-files -z`
  into `grep -zZv`, and `grep` on this machine is ugrep, where `-z` means *decompress*: ten planted
  credential shapes all passed. It uses `git grep` now and matches a canary first. Removing
  `unitTests.isReturnDefaultValues = true` turned 13 tests red and the cause was real — `Log2`
  called `android.util.Log`, which throws off the device, so the logger could take down the code it
  watched.
- **The native build was for the wrong chip.** ggml compiled at the armv8-a baseline;
  `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` is in place and the acceptance is
  `HAVE_DOTPROD:INTERNAL=1` in `feature-stt/.cxx/**/CMakeCache.txt`.
- **Strings left Kotlin.** 76 strings and one plural in the app, 24 in `core-common`; `UiMessage`
  carries a resource id and its arguments. The launcher icon is the PassionCode mark.

## What exists

Six Gradle modules, ~3 500 lines of Kotlin plus a C++ JNI bridge:

- `:core-common` — sheleg-design tokens and theme, the `AppError` taxonomy, `UiStateMapper`,
  Keystore-backed `SecureSettings`, redacting logger.
- `:core-notes` — `Note`, `TagParser` (Cyrillic tags included), Room + FTS4, `NotesRepository`
  (search across transcripts, daily note, change events).
- `:feature-vault` — Markdown mirror with front-matter, audio beside it, failure reported not thrown.
- `:feature-stt` — **our own whisper.cpp JNI bridge that takes a language** (upstream's sample
  hardcodes `"en"`), `WhisperEngine`, verified model downloader, whisper-server client, fallback
  router.
- `:feature-assistant` — OpenRouter SSE client (keep-alive comments skipped, 401/402/429 typed),
  notes-context builder.
- `:app` — `PanelActivity` (2D panel, `com.oculus.intent.category.2D`), `ImmersiveActivity`
  (Spatial SDK, same composable tree), Today / editor / search / chat / settings, voice capture sheet.

## Audit of 2026-09-19 (after the validation pass)

Three independent reviewers plus the author's re-read: **58 verified findings — 5 Blocker, 15 High,
26 Medium, 12 Low — and 4 documentation divergences.** The headline: the hold-to-talk gesture opens
a `Dialog`, and the new window cancels the press that opened it, so voice capture very likely never
works on the device as shipped; the same sheet would crash the immersive Space; a LAN whisper-server
over `http://` is refused by the cleartext policy; deleting a note removes its vault file from the
wrong month. Report: `docs/evidence/audits/2026-09-19-v1-audit.md`. Plan, decomposed into 28 tasks a
zero-context agent can execute: `docs/evidence/plans/2026-09-19-v1-hardening.md`. Every task is a
board row (`B-001…B-028`) on `docs/evidence/backlog.md`.

**H-01 through H-27 are done and pushed** (see the board for the commit that closed each row).
**The next agent's task is H-28, the device gate below, and nothing before it.** Read §0 of the plan
(the rules) first. After the gate, stage 10 closes the run: the ladder walk, the coverage table, the
retrospective, and a board id for every carry-over row still open.

## The device gate (now H-28 in the plan)

**Put the headset on, then run the device gate.** Everything else waits on it.

```bash
cd <repository root>
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB connect 192.168.0.253:5555
$ADB shell am start -n ai.passioncode.fabricvr/.PanelActivity
$ADB shell dumpsys activity activities | grep fabricvr     # expect PanelActivity resumed
$ADB logcat -s FabricVR:* AndroidRuntime:E                 # expect no crash
```

Then, in the headset, walk the five checks that close the open REQs:

1. **REQ-001** — the panel appears in the shell *beside* a Meta Virtual Display screen.
2. **REQ-002** — write a note, force-stop the app, reopen: the note is still listed.
3. **REQ-003** — Settings → download the speech model (190 MB), then hold *Record* and dictate one
   Russian and one English sentence. Record the latency and whether the language badge is right.
4. **REQ-007** — *Space*, then *Back to panel*.
5. **REQ-006** — paste an OpenRouter key in Settings, ask the assistant about a note.

Record each in `docs/evidence/verification.md` (the `Human` column is the one a machine may not
fill) and move the matching carry-over rows to the board.

## Then

- Stage 10 acceptance: the ladder walk, the coverage table, the retro. Every carry-over row that is
  still `open` leaves with a `B-NNN` id.
- Owed from stage 9: **the code graph** (graphify needs an OpenAI key on this machine — board row
  B-029). The wiki entry is written and pushed to the maintainer's
  private wiki, with one concept page beside it.
- **The instrumented suite has now run.** 13 tests on a Quest 3, 0 failures, 0 skipped:
  `PanelSmokeTest`, `WhisperEngineTest` and `WhisperBenchmarkTest` (RU and EN WAV fixtures, the
  model pushed with `scripts/push-model-for-tests.sh`), `SecureSettingsTest`, `UiStringsTest`.
  Re-run with `ANDROID_SERIAL=<device> ./gradlew connectedDebugAndroidTest`.

## Entry point

`README.md` → `docs/product/product-definition.md` → this file. Branch `feat/v1-notes-core`,
pushed; `main` still holds the research only.

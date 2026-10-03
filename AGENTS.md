# Working in fabric-vr

## Read first

1. The PassionCode.ai knowledge base — `fabric-workspace/knowledge/` in your clone (org-index
   `scripts/clone_all.sh` makes it) or https://wiki.passioncode.ai/knowledge — at least its
   [README](https://github.com/passioncode-ai/fabric-workspace/blob/main/knowledge/README.md),
   vision, principles and how-to-work.
2. This file, then the organization's
   [CONTRIBUTING.md](https://github.com/passioncode-ai/.github/blob/main/CONTRIBUTING.md).

That guide holds the names, how a change lands, the code region markers and the security contact;
this file adds the rules of this repository and wins where the two differ.

## What this repository is

Fabric VR builds **Fabric's remote surfaces**: a native Android app for Meta Quest 3 and 3S first,
then an Android phone from the same code (`DEC-0100`). The Mac holds the Estate, and these
surfaces never hold a copy of its journal. They reach Fabric only through its northbound MCP,
carried by one relay the Mac connects out to. The rules for that connection are Fabric's ADR-0088.

Today the app is the Capture module and nothing more: text and voice notes, voice transcribed on
the device by whisper.cpp, and a Markdown vault. It has no connection to Fabric yet, because the
relay does not exist. It sits beside a virtual desktop and never renders one. It ships no
assistant of its own (`DEC-0020`). Until the relay exists it also works on its own, as that
capture app. Source: `README.md` and `docs/handoff/`.

## Commands

| What | Command |
|---|---|
| Install | `git submodule update --init --recursive`, then `./gradlew :app:assembleDebug` and sideload with `adb` (`README.md` → Build and install) |
| Test (the gate) | `bash scripts/check-all.sh` |
| Build | `./gradlew :app:assembleDebug` |
| MCP (register + proving call) | none yet: the app will reach Fabric only through Fabric's northbound MCP over one relay (Fabric ADR-0088), and the relay is not built |

`README.md` ("Build and install", "Checks") has the requirements and the full steps: Android
Studio's JBR, SDK 35, NDK 27.2.12479018, CMake 3.22.1 and the whisper.cpp submodule.

```bash
git submodule update --init --recursive
./gradlew :app:assembleDebug
bash scripts/check-all.sh              # every gate plus the JVM suite, the same command CI runs
bash scripts/check-all.sh --with-lint  # the same, plus :app:lintDebug
git config core.hooksPath .githooks    # once per clone: pre-push refuses a tree check-all has not passed
```

The register of every gate, in execution order, is the Gates section of
[docs/DOCMAP.md](docs/DOCMAP.md). It is not restated here. `.github/workflows/ci.yml` runs the
gates on every push, and the cold dependency job and the release build in the 23:00
Europe/Warsaw nightly batch or on a manual dispatch. It cannot run the instrumented suite, which needs a headset. The walk
that needs a person is [docs/evidence/device-gate.md](docs/evidence/device-gate.md).

## Local rules

### Where things live

- Start at the handoff that `README.md` names under **Start here**. The other files in
  `docs/handoff/` are dated records of earlier runs.
- [docs/DOCMAP.md](docs/DOCMAP.md) lists the registers, the single home of each fact, what each
  change obliges you to update, and the gates.
- Decisions are in [docs/DECISIONS.md](docs/DECISIONS.md) (`DEC-####`, append-only). Open
  questions are in [docs/OPEN_QUESTIONS.md](docs/OPEN_QUESTIONS.md). The board is
  [docs/evidence/backlog.md](docs/evidence/backlog.md), and the verification ledger is
  [docs/evidence/verification.md](docs/evidence/verification.md).
- Scenarios: [docs/ux/scenarios.md](docs/ux/scenarios.md). Product definition:
  [docs/product/product-definition.md](docs/product/product-definition.md). Module notes are in
  `docs/modules/` and runbooks are in `docs/runbooks/`.

### Rules in this repository

- There is one branch. `main` is fast-forwarded on local gate evidence (`DEC-0099`).
- Counts are computed, not restated in prose. `scripts/check-docs.sh` recomputes the counts it
  guards (`DEC-0056`, `DEC-0057`).
- Read [docs/deployment/signing.md](docs/deployment/signing.md) before you create a signing key.
  Key passwords never go into `keystore.properties` or Git (`DEC-0049`).
- If a build refuses a dependency, never delete `gradle/verification-metadata.xml` and never pass
  `--dependency-verification off` (`DEC-0050`). See `docs/runbooks/dependencies.md`.
- `adb uninstall` deletes every note, the vault and every key. Export the vault first (`README.md`).
- Licence: this project's own code is `AGPL-3.0-only OR LicenseRef-PassionCode-Commercial`
  (`LICENSE`, `COMMERCIAL-LICENSE.md`). Third-party code keeps its own licence: never relicense
  or strip a notice in `third_party/`, and a new or upgraded component gets its `NOTICE` entry in
  the same change (`DEC-0073`; `LicencesTest` compares the shipped copy byte for byte).
- **Shared registers are edited under a lease.** [docs/AGENT_SYNC.md](docs/AGENT_SYNC.md)
  (generated from `.claude/agent-sync.json` by `agent_sync.py setup`; never edited by hand) lists
  the guarded files and the gate. Run `agent_sync.py acquire <file>` before editing one and
  `agent_sync.py release <file>` after, on every path including failure. The lease is a ref under
  `refs/agent-sync/leases/` on `origin`, so another contributor's agent sees it
  (`git ls-remote origin 'refs/agent-sync/leases/*'`); the record plane is local (`fs`), and
  `.agent-sync/` is git-ignored.
- **Ids are reserved, not read.** `DEC` (`docs/DECISIONS.md`) and `OQ` (`docs/OPEN_QUESTIONS.md`)
  are `idRegisters`: run `agent_sync.py reserve DEC` (or `OQ`) and write the number it returns. The
  allocator is a compare-and-swap on `refs/agent-sync/ids/<REG>` on `origin`, so two machines cannot
  take one number; the register's **Next free ID** line is its floor, so move it forward in the
  same edit. A number reserved and then not used is recorded with `agent_sync.py release-id <REG> <n>`; the
  counter never moves back, so that number stays a hole rather than being handed out twice. `check`
  accepts these registers on the `fs` record plane from agent-sync 1.21.1.

## Lifecycle

The organization's [lifecycle contract](https://github.com/passioncode-ai/fabric-workspace/blob/main/knowledge/lifecycle.md)
(LC-01…LC-15, adopted 2026-10-03) holds here in its Android translation. This section is LC-09's
declaration and LC-15's retention line; the receipts are the files and tests named in each row.

### What runs, who starts it, who stops it (LC-09)

- **One process, `ai.passioncode.fabricvr`, and nothing else on the headset.** No
  `android:process`, no foreground service, no WorkManager, AlarmManager, JobScheduler or wake
  lock, no boot receiver, no listening socket, no launchd label and no port. The debug variant's
  merged manifest adds four library components, none of which runs on a schedule: androidx's
  `InitializationProvider` (start-up initialisers), Room's `MultiInstanceInvalidationService`
  (bound on demand), the Spatial SDK's `ChannelBrokerService` (bound by the SDK), and
  `ProfileInstallReceiver` (answers only `DUMP`-holding callers). Checked with
  `./gradlew :app:processDebugMainManifest`, which writes it under
  `app/build/intermediates/merged_manifests/debug/`.
- **Started by** the person opening the panel (`PanelActivity`) or the Space
  (`ImmersiveActivity`); `FabricVrApp.onCreate` → `Graph.init` launches bounded start-up work —
  reconcile, trash purge, scratch sweep, outbox restore, the resume of journalled transcriptions
  (`DEC-0103`) — and one long-lived collector, the vault mirror, which is suspended until a row
  changes.
- **What keeps running with no window:** a transcription already started (bounded by the
  ten-minute recording cap, about thirteen minutes of decode, `DEC-0032`), a model download the
  person started, and the whisper context's idle clock. Nothing polls, nothing is scheduled, and
  nothing starts while the app is in the background.
- **Who stops it:** Android, by process teardown — there is no quit and nothing that needs one.
  A decode killed with the process is journalled and resumed at the next launch (`DEC-0103`); a
  download keeps its `.part` and resumes from it when the person next starts it. Making either
  outlive the process, or resume the download unasked, is board row `B-264`.
- **Host side: nothing resident.** `scripts/` holds one-shot build, install and check commands.

### Heavy resources and their release triggers (LC-08, LC-02)

| Resource | Owner | Released on |
|---|---|---|
| whisper context, 190–574 MB native | `LocalWhisperOwner` | a model change; 5 min idle after the last use; `onTrimMemory` ≥ `RUNNING_LOW` via `MemoryTrim`. Never under a running decode (`DEC-0102`; `LocalWhisperOwnerTest`, `MemoryTrimTest`) |
| microphone (`AudioRecord`) | `AudioRecorder` | the `finally` of every recording, failed start included (`B-248`) |
| audio focus (transient duck) | `PlatformFeedbackCues`, attached once by `Graph.init` | the `finally` of every recording: stop, cap, failure, discard, host cleared (`RecordingAudioFocusTest`, `ProcessAudioFocusTest`) |
| note playback (`MediaPlayer`) | `AudioPlayback` via `PlaybackBinding` | a recording starting; its screen's `ON_STOP`; dispose (`PlaybackBindingTest`) |
| tone generator (`AudioTrack`) | `ToneCueChannel` | `ImmersiveActivity.onDestroy`; re-opened by the next cue |
| Space panel entity | `SpaceExit` | the *Leave* path; the system-ended path is `B-267` |

**Idle budget, stated as a target and not yet measured on a headset:** no CPU work at rest (the
only timers are each notes screen's midnight delay and the 500 ms progress read that runs only
while a decode is on screen), and resident memory at the app's baseline once the idle clock has
freed the whisper context. Measuring both is device-gate work.

### Build output and its cap (LC-15)

- **Release artefacts** are written under fixed names per variant —
  `app/build/outputs/apk/<variant>/app-<variant>.apk` and `app/build/outputs/bundle/release/app-release.aab` —
  so a build overwrites the previous one and at most one per variant is ever on disk.
  `scripts/check-build-cache.sh` fails the gate the day `app/build.gradle.kts` starts versioning
  those names. Releases themselves live in CI's uploaded artefacts, not on a build machine.
- **Build caches:** every module's `build/`, `app/.cxx/` (the NDK's whisper.cpp compile) and
  `.kotlin/`. **Cap: 3 GB** in total (`FABRICVR_BUILD_CAP_MB`). `bash scripts/build-cache.sh`
  reports them; **`bash scripts/build-cache.sh enforce`** removes them when over the cap and is
  what an agent that built runs before ending its run; `clean` removes them regardless. It deletes
  only directories git ignores.
- **Gradle's user home (`~/.gradle`)** is shared with every Gradle project on the machine, so the
  script above does not touch it. A cold build and the full gate here fill it to 1.9 GB, 1.7 GB of it `caches/`
  (measured 2026-10-03). Cap: 4 GB; over it, `./gradlew --stop && rm -rf ~/.gradle/caches`, and
  the next build downloads again, verified against `gradle/verification-metadata.xml`.

## Organisation

This repository is one of the `passioncode-ai` repositories. **The org map and onboarding live in
[passioncode-ai/org-index](https://github.com/passioncode-ai/org-index)** (private; readable by
every org member); the shared rules live in the knowledge base:

- [README](https://github.com/passioncode-ai/org-index#repositories): which repository owns what, and how they connect
- [rules](https://github.com/passioncode-ai/fabric-workspace/blob/main/knowledge/rules.md): branches, commits, CI, leases, secrets, handoffs
- [ONBOARDING.md](https://github.com/passioncode-ai/org-index/blob/main/ONBOARDING.md): setting up a new contributor's machine

Where this file is stricter than the rules, this file wins. A change to this repository's
role, dependencies or test command updates its row in `org-index/repositories.json` in the same change.

## Shared backlog

[docs/backlog-sources.json](docs/backlog-sources.json) declares this repository's canonical
local task sources and their vision goals. The [common backlog contract](https://github.com/passioncode-ai/fabric-workspace/blob/main/knowledge/backlog.md)
owns aggregation; [the workspace backlog](https://wiki.passioncode.ai/backlog) is a derived view.
Edit a task only in its canonical source under an agent-sync lease, retain stable IDs and
closure receipts, and declare any new source in the manifest. Do not edit generated task
status in the workspace or copy another repository's task into a second editable row.
Land the source change, then run `node scripts/workspace.mjs sync` from a Fabric checkout
(or use the scheduled sync); check the published source commit before calling it current.

## After work

In the same run: update this repository's docs with the change; if a cross-repository fact changed
(a product, a version, a plan row, a principle), update the page in `fabric-workspace/knowledge/`
that owns it; land both; publish (`node scripts/workspace.mjs sync` from a Fabric checkout) or
leave it to the scheduled sync. Leave a handoff with the exact next task.

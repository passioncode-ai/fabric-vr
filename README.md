# Fabric VR

Fabric VR is **Fabric's remote surfaces** (`DEC-0100`): a native Android app for Meta Quest 3 / 3S
first, then an Android phone from the same code. It is Fabric beside any virtual desktop, never a
desktop itself. It will reach Fabric on the Mac only through the northbound MCP, over one relay
(Fabric ADR-0088). The relay is not built yet.

**Parked since 2026-09-29, on purpose.** Today the app is the Capture module alone: text and voice
notes, speech-to-text on the headset, and a Markdown vault. It has no connection to Fabric. The
vision now moves forward in the `fabric` repository. Where this was left, and the next task, are
at the top of the handoff named under **Start here**. Until the relay exists it works on its own,
as that capture app.

Built by [PassionCode.ai](https://passioncode.ai/), whose toolkit is for AI-native teams.

## Status

v1 is **built, installed and seen running on the operator's Quest 3.** This paragraph said
*"nobody has watched it run yet"* for eleven commits after that stopped being true: at 17:55 on
2026-09-19 the panel launched, resumed in `mode=multi-window`, logged nothing from
`AndroidRuntime`, and a `screencap` taken while the headset was worn shows it beside a Meta
Virtual Display screen (`verification.md` REQ-001 at `f770049`).

**What that is not is a walkthrough.** A machine-read launch and one photograph are the
`Auto` half; the `Human` column of every row in the verification ledger still reads `never`,
and almost every row was observed against a tree that has since moved. **How many is printed by
`scripts/exposure.sh` on every gate run and is deliberately not restated here** — this sentence
carried "39 of its rows" for one commit, copied from a sample output block rather than computed,
which is `SI-01` broken on the front page by the run that restated `SI-01` (`DEC-0057`). A count
in a README is wrong the next time a row is appended, and nobody appending one thinks to look.
Nobody has dictated a note on the headset and read it back.

**Start here:** [`docs/handoff/2026-09-22-v3-entry.md`](docs/handoff/2026-09-22-v3-entry.md) — the
current handoff, and the only one this README names. What needs a person with a headset is the
walk in [`docs/evidence/device-gate.md`](docs/evidence/device-gate.md). The other files in
`docs/handoff/` are **dated records of earlier runs**; each one begins with a banner saying so,
and `check-docs.sh` §20 refuses a commit where that stops being true (`DEC-0055`). This line
pointed at one of those records for eleven commits, which is how an agent plans a first device
session for something that already happened.

## Quick start for a new teammate

1. **Install:** nothing is published yet — no release, no store listing. Build the debug APK from
   source and sideload it with `adb` onto a Quest 3 or 3S in Developer Mode
   ([Build and install](#build-and-install)). Without a headset the build is as far as it goes.
2. **Configure:** no account and no key. The build needs Android Studio's JBR as `JAVA_HOME` and
   `local.properties` naming the SDK; the app downloads its speech model on first dictation.
   No signing key either: releases are signed only in CI, with the organization's release key
   (`DEC-0104`, [`docs/deployment/signing.md`](docs/deployment/signing.md)); a headset gets the debug build.
3. **MCP:** none yet. The app will reach Fabric only through Fabric's northbound MCP over one
   relay (Fabric ADR-0088), and the relay is not built.
4. **Develop:** `bash scripts/check-all.sh` is the gate ([Checks](#checks)), and
   `git config core.hooksPath .githooks` makes a push run it. [Where things are](#where-things-are)
   maps the modules; `docs/DOCMAP.md` says what each change must also update.

## What v1 does

- **Notes** — text and voice notes, `#tags` parsed from what you write (Cyrillic included),
  full-text search across titles, bodies **and transcripts**, a daily note.
- **Voice** — press once to record from the headset microphone, press again to stop
  (`DEC-0010`: a hold asks a person to keep a controller ray steady for the length of a thought),
  **up to ten minutes, after which it stops and transcribes itself** rather than recording until
  the process runs out of memory (`DEC-0032` — the bound is the transcription wait, not the
  memory). Leaving the screen or taking the headset off also stops it, and keeps what was said.
  Transcribed **on the device** by whisper.cpp — five models by name and size, `ggml-small-q5_1`
  by default, Russian and English, auto-detected. Where speech goes is an explicit choice —
  this headset, a cloud endpoint, or your own whisper-server (`DEC-0014`) — and a provider you
  chose but did not finish configuring **says so** rather than falling silently to the device
  (`DEC-0024`).
- **No assistant in v1** — the chat over OpenRouter is cut (`DEC-0020`). The module stays in the
  tree with its tests; nothing in the shipped APK links it, and no OpenRouter key is asked for
  or stored.
- **Vault** — every note is mirrored as Markdown with YAML front-matter under
  `files/vault/notes/YYYY/MM/<id>.md`, audio beside it. The database is an index over those files.
  **A second note claiming a taken day gives up the day, never a word** (`DEC-0058`): the rule is
  `NotesRepository.upsert`'s own invariant, not a check a caller remembers to make, so importing a
  vault and migrating an old database both obey it.
- **Taking them with you** — Settings → *Export the vault* writes one zip to
  `/sdcard/Download`, which survives an uninstall, and offers to share it if anything on the
  headset can receive a zip (`DEC-0026`). Do this **before** any reinstall.
  *Restore from an archive* reads such a zip back through the system document picker
  (`REQ-054`): notes the headset does not hold are unpacked into the vault and indexed, notes it
  already holds are left alone — the live copy wins — and an entry that is not a note from this
  app's export is refused and counted.
- **Two surfaces** — a 2D panel in the Horizon OS shell (so it sits beside Meta Virtual Display)
  and an immersive *Space* hosting the same screens over passthrough.

## What it is called, and where to find it

| | |
|---|---|
| Name in the headset | **Fabric VR** (the Space activity appears as *Fabric VR Space* in a task switcher) |
| Package id | `ai.passioncode.fabricvr` |
| Launcher entry | `ai.passioncode.fabricvr/.PanelActivity` |
| Repository | `passioncode-ai/fabric-vr` |

**On the headset it is not in the main app grid.** A sideloaded app lives in the App Library under
**Unknown sources**; open the library, switch the filter to *Unknown sources*, and it is there under
*Fabric VR*. Typing "fabric" into the library search finds it too. The version it is showing is at
the bottom of its own Settings screen, as `Fabric VR <name> (<code>)`.

From a terminal, the same three facts:

```bash
adb shell pm list packages | grep fabricvr                        # is it installed
adb shell dumpsys package ai.passioncode.fabricvr | grep version  # which build
adb shell am start -n ai.passioncode.fabricvr/.PanelActivity      # open it
# DESTRUCTIVE. `uninstall` deletes filesDir: the notes database, the whole vault with
# every recording, the 190 MB speech model and every Keystore value. There is no backup
# (`allowBackup="false"`). Take Settings -> *Export the vault* FIRST and copy the zip off
# `/sdcard/Download` — that step is the one thing standing between a reinstall and losing
# everything the person has written.
adb uninstall ai.passioncode.fabricvr                             # remove it
```

## Build and install

Requirements: Android Studio's JBR (JDK 21 runs the build, Java target 17), Android SDK 35,
NDK 27.2.12479018, CMake 3.22.1, a Quest 3/3S in Developer Mode.

```bash
git submodule update --init --recursive     # whisper.cpp v1.9.4
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew :app:assembleDebug                # ~2 min cold, builds whisper.cpp for arm64-v8a
adb connect <headset-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ai.passioncode.fabricvr/.PanelActivity
```

It opens as a 2D panel; find it in the App Library under **Unknown sources** (see above — a
sideloaded app is not in the main grid) and open it beside a Meta Virtual Display screen.

`scripts/install-on-quest.sh` does the same and waits for a sleeping headset. With `DEVICE` unset it
takes whichever device `adb devices` already lists, so a USB cable and a different network both work
without editing it.

### The wrapper, and a release build

`gradlew`, `gradlew.bat` and `gradle/wrapper/` were generated by `./gradlew wrapper --gradle-version
8.13 --distribution-type bin` with `--gradle-distribution-sha256-sum` set to the checksum published
at `https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256`. That sum is in
`gradle-wrapper.properties`, so a tampered distribution fails the build instead of running.

```bash
./gradlew :app:assembleRelease
```

No `-PversionCode`: the build stamps its own version from git (`DEC-0048`), and two monotonic
sequences over one field cannot be ordered against each other.

The release build minifies with `app/proguard-rules.pro` and shrinks resources — 74 MB against the
debug APK's 145 MB. Without a `keystore.properties` the build still succeeds and says plainly that
the APK is unsigned; that is what a local `assembleRelease` and CI's nightly `release build` job
produce.

**A signed release is built only by CI** (`DEC-0104`): push an annotated `vX.Y.Z` tag, someone from
`release-approvers` approves the `release` environment (any member, the person who pushed the tag
included: `prevent_self_review: false`, the organization's rule since 2026-10-03, `DEC-0105`), and
`.github/workflows/release.yml` decodes the release keystore into the runner's temp directory,
builds `:app:assembleRelease` with the passwords in the environment (never in a file, `DEC-0049`), and runs
`scripts/verify-release-apk.sh` — APK Signature Scheme v2/v3, the published certificate
SHA-256, a release manifest, the tag's version. The organization's publish workflow then attests
the APK (Sigstore), writes `SHA256SUMS` with a GPG signature, and publishes the GitHub release from
this repository's `CHANGELOG.md`. A rehearsal publishes nothing:

```bash
git tag -a v0.1.0-rc.1 -m "rehearsal" && git push origin v0.1.0-rc.1
gh workflow run release.yml --ref v0.1.0-rc.1 -f publish=false
```

**The release key never comes to a development machine.** It lives in the Observatory vault, its
encrypted off-disk backup and the repository's `release` environment;
[`docs/deployment/signing.md`](docs/deployment/signing.md) has the alias, the fingerprint and what
losing it costs — every installed copy could never be updated again. A build signed anywhere else
is a debug build and is never published. **Do not install a release APK over the debug build on a
headset** until `T-037` has run: Android refuses a different key, and the way past it deletes the
notes.

`.gitignore` and the secret scan refuse tracked key material (`*.keystore`, `*.jks`, `*.p12`,
`*.pepk`, `release-lineage.bin`), and that refusal has been watched firing on a planted file.

### The speech model

On first dictation the app offers to download `ggml-small-q5_1.bin` (190 MB) from Hugging Face,
with progress, a pinned SHA-256, and a **free-space check before the first byte is requested** —
a headset with no room is told so with both numbers rather than told to check its network
(`DEC-0033`). One transfer per model, however many screens ask for it. To side-load it instead:

```bash
adb push ggml-small-q5_1.bin /sdcard/Download/
adb shell run-as ai.passioncode.fabricvr mkdir -p files/models
adb shell "run-as ai.passioncode.fabricvr cp /sdcard/Download/ggml-small-q5_1.bin files/models/"
```

### Reading the vault

```bash
adb shell run-as ai.passioncode.fabricvr ls files/vault/notes
```

## Checks

One command runs every gate, and it is the same command CI runs — so "green locally" and "green in
CI" cannot mean two different sets of checks:

```bash
bash scripts/check-all.sh              # every gate + the JVM suite — minutes; selftest.sh is most of it
bash scripts/check-all.sh --with-lint  # the same, plus :app:lintDebug
```

**And a push is refused unless it has passed**, once per clone:

```bash
git config core.hooksPath .githooks   # installs .githooks/pre-push
```

`SI-03` exists because a push was chained to a `grep` that matched the documentation gate's `OK`
line while three tests were failing — and it happened a second time, in the session that wrote the
rule down. The hook removes the chain: `check-all.sh` leaves a **receipt** naming the exact tree
it passed on, written on its last line, and `git push` refuses any tree the receipt does not name.
`git push --no-verify` is the way past it, deliberately visible.

**The hook does not run the gates, and that is deliberate.** It used to, and every push then died
after they passed: `git push` opens its connection to the remote **before** calling the hook, and
GitHub closes an idle SSH session long before a multi-minute suite finishes — so git printed
`ALL GATES GREEN`, then `SIGPIPE`, exit 141, and transmitted nothing. Four times in a row on
2026-09-22 while the remote sat three commits behind. Run the gates first, in your own shell, and
let them finish; the hook then costs milliseconds.

Individually — **this list is a convenience, not the register.** `docs/DOCMAP.md` declares all
every gate in execution order and is the single home for *what must pass* — the number lives
there and is deliberately not restated here, because it has now moved twice; the eight below are
the ones worth running by hand. The source counter reports **775 JVM tests**, and that number is checked rather
than remembered (`check-docs.sh` §17, `DEC-0056`): a count stated in prose is a claim the gate
recomputes, while a count inside the code block below would be an example by the same rule that
lets a sample command carry a sample number. The scanner currently misses a fully qualified
`@org.junit.Test`; [the round-3 report](docs/evidence/2026-09-25-round3.md) records the JUnit
case count and skipped tests separately.

```bash
bash scripts/check-secrets.sh    # no credential may be tracked; matches a canary first
bash scripts/check-docs.sh       # documentation gate, including paths, commands and symbols
python3 docs/ux/lint.py          # scenario base
./gradlew testDebugUnitTest      # the JVM suite
./gradlew :app:lintDebug         # 0 errors — pulls the NDK, so it is not in check-all by default
bash scripts/check-device-gate.sh  # a change to the instrumented suite must carry a ledger row
bash scripts/check-strings.sh    # one key, one module; every lint exemption names its finding
bash scripts/check-installer.sh  # the installer names the headset and asserts what it installed
bash scripts/check-shell.sh      # every tracked script parses; milliseconds
```

`check-shell.sh` is here because an apostrophe inside a single-quoted `awk` program closes the
string and the rest is parsed as shell — written three separate times in one session, each time
found by running a three-minute gate and reading a syntax error (`DEC-0062`). That same reading
found eleven false statements in this documentation under a green suite, which is why counts in
this file are recomputed rather than restated.

**The unreferenced-string half is Android lint**, not a script: `UnusedResources` is an **error**
since `DEC-0045`, because lint already resolves `@string/` from the manifest and `R.plurals.`
from Kotlin — where a naive `grep R.string.NAME` got three of twenty-seven answers wrong. There is
no suppression list; a per-string `tools:ignore` must name the board row that owns it, and
`check-strings.sh` fails when one does not.

**Dependencies are verified by their bytes**, not by their version numbers: `gradle/verification-metadata.xml`
pins 1 212 SHA-256 checksums over 685 components, and Gradle enforces it on every resolution
without a flag (`DEC-0050`). A build that refuses an artefact is **never** fixed by deleting that
file or passing `--dependency-verification off` — `docs/runbooks/dependencies.md` is the three
things you actually need, including how to regenerate it after a version bump. The file is also
this project's SBOM.

`.github/workflows/ci.yml` runs the gates on every push and the unsigned release build on `main`,
the repository's one branch (`DEC-0099`); `.github/workflows/release.yml` builds the signed release
on a version tag (`DEC-0104`). **It cannot run the instrumented suite**: those tests need arm64, Horizon OS and
the Spatial SDK runtime, and a hosted x86 emulator would run none of them.

## Where things are

| Module | Owns |
|---|---|
| `:app` | the two activities, navigation, screens, view models, the object graph |
| `:core-common` | design tokens and theme, the `AppError` taxonomy, the message mapper, secure settings, the cleartext rule and the one HTTP client factory (`NetworkPolicy`, `FabricHttp`) |
| `:core-notes` | `Note`, tags, Room storage with an FTS4 `unicode61` index, the repository |
| `:feature-vault` | the Markdown mirror, its reconciler and removal journal, zip export and import back (`REQ-054`) |
| `:feature-stt` | audio capture, the whisper.cpp JNI bridge, model store and downloader, the router |
| `:feature-assistant` | the OpenRouter SSE client and the notes-context builder — **not linked into `:app`** (`DEC-0020`) |

Module notes: [`docs/modules/`](docs/modules/). Product definition:
[`docs/product/product-definition.md`](docs/product/product-definition.md). Scenarios:
[`docs/ux/scenarios.md`](docs/ux/scenarios.md). The research behind all of it:
[`docs/research/`](docs/research/).

## Security posture

**Two secrets, not one** (`DEC-0053`). This section named only the first until `T-045`, which is worse than
naming none: a reader who checks a security section and finds it complete stops looking.

- **The OpenRouter key** (`openrouter_api_key`) — AES-256/GCM in AndroidKeyStore, StrongBox when
  the device has it, without user authentication (`DEC-0006`): a headset has no reliable lock
  screen and the key protects spend, not identity.
- **The cloud speech key** (`cloud_stt_key`, `DEC-0013`) — the **same** storage and the same
  guarantees, stored beside `cloud_stt_url` and `cloud_stt_model`
  (`SecureSettings.kt:56-58`). It is the key that pays for transcription, and unlike the first it
  is reachable in v1: the assistant is cut (`DEC-0020`), speech is not.

**On a debuggable build `adb shell run-as` runs as the app and can therefore decrypt either
one**: put a real key only on a release-signed build.

**Cleartext HTTP, stated exactly.** The rule is `DEC-0005`, in `NetworkPolicy.permits`: `https` to anywhere, plain
`http` only to a private address — RFC1918, loopback, link-local, a single-label `.local` and their
IPv6 equivalents (`core-common/.../NetworkPolicy.kt`). It is asked at **two doors**. The first is
`requireReachable`, when a URL is configured — `RemoteWhisperClient`, `CloudTranscriptionClient` and
`OpenRouterClient`. The second is `NetworkPolicyInterceptor`, on **every hop** of every request,
redirects included (`DEC-0077`): all four HTTP clients — those three plus `ModelDownloader` — are
built by `FabricHttp.builder()` in `:core-common` (`DEC-0079`), and `FabricHttpSourceTest` refuses a
client built anywhere else. A request that carries a body or a key may also **not change host** on a
redirect, unless both hosts are private (`NetworkPolicy.permitsRedirect`, `B-216`); a credential-free
model download may follow its vendor's CDN hop, judged by a pinned SHA-256. The
consequence a reader needs and the older wording hid: **the cloud speech key and the recording
itself may travel over plain `http` to any RFC1918 host**, not only to a whisper-server. That is
the policy working as designed — "your own network" is the trust boundary — but a host on your
LAN is not automatically yours.

## Version pins, and why they are not the newest

Three pins are deliberate (`DEC-0004`) and each was forced by a measured failure — the reasoning is in
[`docs/evidence/specs/2026-09-19-v1-notes-core-design.md`](docs/evidence/specs/2026-09-19-v1-notes-core-design.md) §1:

- **OkHttp 4.12.0**, not 5.5.0 — `okhttp-android:5.5.0` requires `compileSdk 37`, which AGP 8.11.1
  refuses.
- **Kotlin 2.2.21**, not the Spatial SDK sample's 2.1.0 — the build resolves `kotlin-stdlib 2.3.20`
  strictly and a 2.1.0 compiler crashes on its metadata. 2.2.21 is the newest Kotlin with a
  released KSP.
- **targetSdk 34** — Horizon OS is Android 14. Lint's "newer version available" warnings are
  expected here.

## Decisions behind what you see

The register is `docs/DECISIONS.md`. The two that most affect a first run: `DEC-0009` — the app
declares hand tracking, because Horizon OS otherwise refuses to open the Space with no controllers
paired; `DEC-0010` — recording is press-to-start, press-to-stop, with no hold and no sheet.

## License

Open source under the [GNU AGPL-3.0](LICENSE). A [commercial license](COMMERCIAL-LICENSE.md) is
available for use that does not meet the AGPL's terms — [passioncode.ai/business](https://passioncode.ai/business/).
No version was released under another licence: until 2026-09-30 the repository carried no licence
file, and nothing has been published. Third-party code keeps its own licence — whisper.cpp in
`third_party/` (MIT), the Meta Spatial SDK and the Apache-2.0 libraries — as listed in
[NOTICE](NOTICE), which the APK ships (`DEC-0073`). Contributions are accepted under the
[CLA](CLA.md).

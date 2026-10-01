# Decisions — fabric-vr

**Append-only.** Every settled thing that shapes the product, the architecture, the
scope, security, data, pricing or process. Doctrine:
`references/documentation.md`.

**Next free ID:** `DEC-0102`

Reading *"Next free ID"* is **not** reserving it — a second agent reading it in the
same minute gets the same answer. Reserve it, then write.

## Format

```markdown
### DEC-0007 — <one line, in the present tense>

- **Date:** 2026-08-03
- **Status:** Accepted
- **Context:** what forced the choice
- **Decision:** what was chosen, stated so it can be obeyed
- **Consequences / affects:** `docs/SECURITY.md`, `docs/DATA_MODEL.md`
- **Source:** run `2026-08-03-v1-notes-core` · commit `a1b2c3d`
- **Supersedes:** DEC-0004
```

| Field | Rule |
|---|---|
| `Status` | `Accepted` · `Superseded by DEC-####` · `Reversed` · `Accepted · **Partially superseded by DEC-####** — <one line>` · `Accepted · **Refined by DEC-####**` |
| `Consequences / affects` | every document that must change. **Each one must cite this id** — the gate checks it |
| `Source` | the run that produced it **and the commit**; the commit is what survives a rename |
| edge markers | `Refines:` additive, target needs no annotation · `Contradicts:` a named clause falls, target **must** be annotated · `Supersedes:` the whole target retires, target **must** be annotated |

**To change your mind:** add a new entry, edit **only the status line** of the old
one, leave its body intact. Never renumber. Never delete.

---

### DEC-0001 — Documentation is governed: registers, a doc map and a gate

- **Date:** 2026-09-19
- **Status:** Accepted
- **Context:** this repository had no addressable home for settled things, so
  decisions lived in chat and in per-run specs and were re-litigated every time
  somebody new arrived.
- **Decision:** decisions live here with stable `DEC-####` ids, append-only; open
  questions live in `docs/OPEN_QUESTIONS.md`; `docs/DOCMAP.md` holds the single
  homes, the propagation matrix and the gate; `scripts/check-docs.sh` enforces the
  mechanical half and runs before every commit.
- **Consequences / affects:** `docs/DOCMAP.md`, `docs/OPEN_QUESTIONS.md`
- **Source:** run `v1-notes-core` · commit `0dcadae`

---

### DEC-0002 — v1 is a Horizon OS 2D panel with an optional immersive Space, built on Meta Spatial SDK

- **Date:** 2026-09-19
- **Status:** Accepted
- **Context:** the product must sit *beside* a streamed desktop (Meta Virtual Display), which an
  immersive-only app cannot do; and it must still offer a spatial mode.
- **Decision:** `PanelActivity` carries `com.oculus.intent.category.2D` and a `<layout>` block;
  `ImmersiveActivity` is an `AppSystemActivity` with `VRFeature` + `ComposeFeature` hosting the
  same composable tree; returning to the shell uses Home's `extra_launch_in_home_pending_intent`.
- **Consequences / affects:** `docs/modules/app.md`, `docs/ux/screens.md`
- **Source:** run `2026-09-19-v1-notes-core`

### DEC-0003 — Speech runs on the device, with a remote server as a preference and a visible fallback

- **Date:** 2026-09-19
- **Status:** Accepted · **Partially superseded by DEC-0014** — the clause "a remote server is
  *preferred* when its URL is set" falls; where speech goes is an explicit choice, and `DEC-0024`
  makes an unconfigured choice refuse out loud instead of falling silently to the device. The
  on-device engine as the always-present fallback survives unchanged.
- **Context:** Horizon OS ships no Google services, so `SpeechRecognizer` is unavailable, and the
  headset's own dictation is English-only. The product is bilingual by design.
- **Decision:** whisper.cpp v1.9.4 is vendored as a submodule and bridged through our own JNI that
  takes a language (upstream's sample hardcodes `"en"`); `ggml-small-q5_1` is fetched on first use
  and verified; a configured whisper-server is preferred and its failure degrades to the device with
  the transcript marked `LOCAL_FALLBACK`.
- **Consequences / affects:** `docs/modules/feature-stt.md`, `docs/ux/scenarios.md`
- **Source:** run `2026-09-19-v1-notes-core`

### DEC-0004 — Three dependency versions are pinned below the newest, each for a measured reason

- **Date:** 2026-09-19
- **Status:** Accepted
- **Context:** the newest of each broke the build in a way that was measured, not guessed.
- **Decision:** OkHttp **4.12.0** (5.5.0 requires `compileSdk 37`, AGP 8.11.1 refuses);
  Kotlin **2.2.21** with `kotlin-stdlib` force-pinned to it (the build resolves stdlib 2.3.20
  strictly and a 2.1.0 compiler crashes on its metadata; 2.2.21 is the newest Kotlin with a
  released KSP); **targetSdk 34** because Horizon OS is Android 14. Lint's "newer version
  available" warnings are therefore expected output, not debt.
- **Consequences / affects:** `README.md`, `docs/evidence/specs/2026-09-19-v1-notes-core-design.md`
- **Source:** run `2026-09-19-v1-notes-core`

### DEC-0005 — Cleartext HTTP is allowed only to a whisper-server on a private network

- **Date:** 2026-09-19
- **Status:** Accepted · **Refined by DEC-0013** — the clause "**the two call sites**" undercounts:
  `DEC-0013` added a third, `CloudTranscriptionClient.kt:43`, and the decision text was never
  annotated. The three are `RemoteWhisperClient.kt:44`, `CloudTranscriptionClient.kt:43` and
  `OpenRouterClient.kt:44` (measured by `T-045`). The **policy** is unchanged and its body stands;
  what changed is what travels under it, and that is the part a reader needs: since `DEC-0013` the
  permitted cleartext path carries **a cloud speech key and the recording itself** to any private
  address, not only a whisper-server. `README.md`'s security posture states this in full.
- **Context:** `targetSdk 34` blocks cleartext by default, so a whisper-server at
  `http://192.168.x.x:8080` — the only realistic shape for a server on the operator's own LAN — was
  refused by the platform and the failure surfaced as a silent fallback (audit `AUD-04`). Android's
  Network Security Config cannot express IP *ranges*, and an app-wide `usesCleartextTraffic` would
  also permit a mistyped assistant endpoint to travel in the clear.
- **Decision:** the manifest enables cleartext at the platform level, and the policy is enforced in
  code at the two call sites: `RemoteWhisperClient` accepts `https://` anywhere and `http://` only
  for RFC1918 literals, loopback or a `.local` host; `OpenRouterClient` refuses any non-`https`
  base URL at construction. A refused URL is reported to the person, never silently downgraded.
- **Consequences / affects:** `docs/modules/feature-stt.md`, `docs/modules/feature-assistant.md`,
  `README.md`
- **Source:** audit `2026-09-19-v1-audit` · plan task `H-05`

### DEC-0006 — The settings key is AES-256/GCM in AndroidKeyStore, StrongBox when available, with no user authentication

- **Date:** 2026-09-19
- **Status:** Accepted
- **Context:** `androidx.security:security-crypto` is deprecated, so the platform Keystore is the
  path (`DEC-0004` era). The audit found the generator left the key size at the 128-bit default, made
  no StrongBox attempt, and — worse — could not tell "no key was ever saved" from "the key can no
  longer be decrypted" (`AUD-22`, `AUD-23`, `AUD-50`).
- **Decision:** 256-bit AES/GCM; StrongBox attempted and silently degraded when the device has none;
  key generation is synchronised and the key cached, so two threads cannot replace the alias under
  each other; an undecryptable value is **removed** and reported as "the saved key could not be read
  on this device", never as absence. **No user authentication is required** — a headset has no
  reliable lock screen, and the key protects spend rather than identity.
- **Consequences / affects:** `docs/modules/core-common.md`, `README.md`
- **Source:** audit `2026-09-19-v1-audit` · plan task `H-11`

### DEC-0007 — The whisper context lives for the process, and is freed only on the engine's own thread

- **Date:** 2026-09-19
- **Status:** Accepted · **Partially superseded by DEC-0019** — the clause "a best-effort
  `onTerminate`" falls: nothing overrides `onTerminate` anywhere in the tree, and Android does not
  call it on a real device. `T-018` owns the remaining half, that `close()` still blocks the
  **caller's** thread rather than only the engine's.
- **Context:** whisper.cpp forbids concurrent access to one context, and Android gives no reliable
  process-teardown callback. The audit found `close()` freeing the native context off the engine's
  single-thread dispatcher — a use-after-free waiting for the first caller — while nothing ever
  called it, leaving ~190 MB resident (`AUD-19`).
- **Decision:** the context is created lazily on first transcription and kept for the process; that
  cost is accepted so a second dictation does not pay the model load again. `close()` frees **inside**
  the engine's dispatcher and marks the engine closed, so a later call fails as a `Result` rather
  than crashing natively; it is called on a best-effort `onTerminate` only.
- **Consequences / affects:** `docs/modules/feature-stt.md`
- **Source:** audit `2026-09-19-v1-audit` · plan task `H-19`

### DEC-0008 — Beam search at width five, not greedy

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** transcription quality was the operator's complaint. The decoder ran greedy sampling
  on whisper.cpp's bare defaults; a dictation is a short utterance with no surrounding context for
  a wrong word to be corrected by, which is exactly where greedy and beam differ.
- **Decision:** beam search at width 5 — the configuration whisper was published with — plus
  non-speech-token suppression and an explicit temperature ladder. The width is a constructor
  parameter (`WhisperEngine`), so a future speed setting is an argument rather than a rewrite.
- **Consequences / affects:** `docs/modules/feature-stt.md`. Accuracy is bought with time, and the
  purchase is void once the device is thermally limited: the same benchmark put beam and greedy
  within 5% of each other after six minutes of continuous inference. The setting stops mattering
  exactly when the person is waiting longest.
- **Source:** operator report · commit `bb82929`

### DEC-0009 — Declare hand tracking so the shell stops blocking the Space

- **Date:** 2026-09-20
- **Status:** Accepted · **Refined by DEC-0067** — the declaration is used by the Space, not idle
- **Context:** Horizon OS intercepted every launch of the immersive activity with
  `common_system_dialog_app_launch_blocked_controller_required` when no controllers were paired.
  Measured on a Quest 3; the Space had never opened for that reason.
- **Decision:** the manifest declares `oculus.software.handtracking` (`required="false"`) and
  requests `com.oculus.permission.HAND_TRACKING`.
- **Consequences / affects:** `docs/modules/app.md`, `README.md`. The app now advertises a
  capability it does not implement — there is no hand input anywhere in the tree — and requests a
  permission it never uses. Store review and the app's own permission list both read that
  declaration. **Open question:** whether `required="true"` is the correct value, and whether a
  narrower mechanism exists.
- **Source:** operator report · commit `732c92b`

### DEC-0010 — The hold gesture and the capture sheet are deleted

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** the operator could use neither. A hold asks a person to keep a controller ray steady
  on a target for the length of a thought, and a released ray ends the sentence; a sheet is a
  second window that has to be dismissed, and in a headset one that does not close is a wall.
- **Decision:** recording is press-to-start, press-to-stop. Every blocking state is drawn above a
  control that keeps its place and its size.
- **Consequences / affects:** `docs/modules/app.md`, `docs/ux/scenarios.md`, `docs/ux/flows.md`.
  **This reverses the v1 reasoning**, which chose a hold because it cannot be left running by a
  stray raycast. A tap can: the microphone can now be left open by a mis-tap, and the only
  mitigation is that the button reads *Stop recording*. `app.md:26-27` and `flows.md:56-57` still
  carry the old rationale and must be corrected.
- **Source:** operator report · commit `732c92b`

### DEC-0011 — A finished transcript commits itself

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** in a headset the step between speaking and having the text is the whole cost of the
  feature, and a preview to accept or discard is one more thing to aim at.
- **Decision:** the note is written with no Save step and no preview. A wrong word is corrected
  afterwards, from the list, with *Transcribe again* or by opening the note.
- **Consequences / affects:** `docs/ux/scenarios.md` (SCN-004). Every misfire, every "nothing was
  heard" and every wrong decode becomes a row the person must delete; the confirmation that the
  old *Save* / *Discard* pair provided is spent here.
- **Source:** operator report · commit `2f91df7`

### DEC-0012 — The transcript is copied to the system clipboard on commit

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** dictating in a headset is usually done to paste the words into something that is not
  this app, so a note that is saved but not copied costs a second trip.
- **Decision:** the text goes to the clipboard as the transcript commits, with a transient
  "Saved, and copied" line.
- **Consequences / affects:** `docs/ux/scenarios.md` (SCN-004). The app overwrites a cross-app
  resource the person did not offer, on every dictation, and the only notice disappears after a
  few seconds.
- **Source:** operator report · commit `2f91df7`

### DEC-0013 — Cloud transcription speaks the OpenAI-compatible route

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** the on-device small model transcribes Russian poorly, and a better model is either
  much slower on this chip or much larger.
- **Decision:** one `CloudTranscriptionClient` against `POST {base}/v1/audio/transcriptions`, so
  Groq, OpenAI and a self-hosted faster-whisper are one client and three settings. The constructor
  refuses an endpoint `NetworkPolicy` will not allow.
- **Consequences / affects:** `docs/modules/feature-stt.md`, `DEC-0003`, `DEC-0005`, `README.md`,
  `CONTEXT.md`. The recording leaves the device; a second secret enters the Keystore; `DEC-0003`'s
  "speech runs on the device" becomes one of three answers rather than the rule; and `DEC-0005`'s
  cleartext policy gains a third call site and a materially larger payload.
- **Refines:** DEC-0005 — annotated by `T-045`, eleven commits late. The gate that now makes this
  un-forgettable is `check-docs.sh` §6: a `Refines:` marker whose target carries no annotation is
  a gate failure, so the edge can no longer exist in one direction only.
- **Source:** operator report · commit `2f91df7`

### DEC-0014 — Where speech goes is an explicit choice, not an inference from a URL

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** the previous rule was "a whisper-server is preferred when its URL is set", which
  decided something about a person's voice from a field they may have filled months earlier.
- **Decision:** `SttProvider` is LOCAL, CLOUD or SERVER, chosen in Settings, each with a line
  saying what it does with the recording.
- **Consequences / affects:** `DEC-0003`, `docs/modules/feature-stt.md`. A URL saved under the old
  rule now routes nowhere: an upgrading person's configured server goes quiet with no message.
- **Source:** operator report · commit `2f91df7`

### DEC-0015 — Five on-device models, chosen by name and size

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** one hard-coded model made quality a property of the build rather than a choice.
- **Decision:** a catalogue of five, each carrying the size and SHA-256 its publisher states, all
  in one directory so switching keeps whatever is already downloaded. `small` remains the default
  and is the only one whose latency has been measured.
- **Consequences / affects:** `docs/modules/feature-stt.md`. The 539 MB and 574 MB options are
  offered on a device measured at 2.5× slowdown under sustained inference, and `WhisperEngine.name`
  is still the literal `"whisper-small-q5_1"`, so the per-note engine badge is wrong for four of
  the five.
- **Source:** operator report · commit `2f91df7`

### DEC-0016 — The model digest is enforced, pinned to the vendor-published value

- **Date:** 2026-09-19
- **Status:** Accepted
- **Context:** `expectedSha256` was deliberately empty because no digest had been verified from a
  primary source, and pinning one nobody checked is a claim rather than a check.
- **Decision:** pin the Git-LFS object id Hugging Face publishes for the file, confirmed against an
  independent download at the published length, and refuse a mismatch.
- **Consequences / affects:** `docs/modules/feature-stt.md`, carry-over row 16. A vendor re-upload
  breaks every download until the pin is edited, and nothing notices that it has happened.
- **Source:** audit `2026-09-19-v1-audit` · commit `f770049`

### DEC-0017 — The redirect allow-list belongs to the store, matched on the registrable suffix

- **Date:** 2026-09-19
- **Status:** Accepted
- **Context:** deriving the permitted host from the download URL refused the vendor's own CDN —
  Hugging Face answers `resolve/main` with a 302 to `us.aws.cdn.hf.co` — so no model could be
  downloaded at all.
- **Decision:** `ModelStore` declares `allowedRedirectHosts` (`{huggingface.co, hf.co}` for the
  shipped store), matched on the registrable suffix so one entry covers a CDN's subdomains.
- **Consequences / affects:** `docs/modules/feature-stt.md`. The list is hand-maintained per store,
  and `hf.co` admits every subdomain the vendor may ever serve. Accepted because `DEC-0016` is the
  real line and this is defence in depth.
- **Source:** operator report · commit `4bddfcc`

### DEC-0018 — A Spatial SDK panel is given all four Compose host owners

- **Date:** 2026-09-20
- **Status:** Accepted · **Superseded by DEC-0028** — the premise was wrong. Disassembly of
  `meta-spatial-sdk-compose-0.14.0.aar` shows the SDK sets three of the four on the view tree;
  supplying our own shadowed them with strictly worse ones.
- **Context:** `AppSystemActivity` is a plain `android.app.Activity`, so a panel's composition had
  no back dispatcher; navigation's predictive back handler threw and took the activity down on
  every entry into the Space.
- **Decision:** `SpatialPanelOwners` supplies lifecycle, view-model store, saved-state registry and
  back dispatcher together, rather than only the one that crashed.
- **Consequences / affects:** `docs/modules/app.md`. The lifecycle is hand-driven by the activity
  and can drift from it, and nothing proves the set of four is complete. **Contested:** audit axis
  B reports that the SDK's `composePanel` already provides three of the four; settle that before
  editing the file.
- **Source:** operator report · commit `deb604d`

### DEC-0019 — Changing the model closes the loaded context

- **Date:** 2026-09-20
- **Status:** Accepted · **Refined by DEC-0030** — the rule is right and the mechanism was the
  ANR. The throwaway engine this decision authorised is gone; `LocalWhisperOwner` evicts instead.
- **Context:** the model became a setting, and two sets of weights in native memory would sit there
  for the life of the process — the small model alone is 190 MB.
- **Decision:** `Graph` closes the loaded engine when the chosen model changes, and a
  *Transcribe again* against a non-selected model runs on a throwaway engine closed in a `finally`.
- **Consequences / affects:** `DEC-0007`, `docs/modules/feature-stt.md`. This narrows `DEC-0007`'s
  "kept for the process", whose own text is wrong at the other end too: it names an `onTerminate`
  that does not exist. Closing an engine from the caller's thread while a transcription runs on the
  engine's dispatcher is an open risk (audit finding C-03).
- **Source:** operator report · commit `2f91df7`

### DEC-0020 — The assistant is cut from v1

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** the audit found every precondition the assistant needs to be missing at once
  (`G-33`, `G-11`, `G-14`, `G-03`): retrieval is broken for the language the notes are written in
  (`G-04`), the context builder posts up to 12 000 characters of raw dictations to a third party,
  the conversation does not survive `popBackStack`, and its entry point is a microphone icon sitting
  beside the record button. Fixing it means fixing all four; leaving it means shipping a feature
  that answers badly from notes it retrieved wrongly.
- **Decision:** close the assistant route for v1 — the chat screen, its view model, the entry point,
  and the OpenRouter key and model settings all go, and `:app` stops depending on
  `:feature-assistant`, so nothing of it reaches the APK and nothing can invoke it.
  **`:feature-assistant` itself stays in the tree.** Its 24 tests keep running: deleting the module
  would delete real coverage of code this project intends to bring back, and an unreferenced
  library whose tests run is not the same thing as dead surface reachable from nowhere. Re-entry is
  one dependency line plus the work `T-033` Option B describes, on a notes base worth querying.
  **Revised from the original text of this record**, which said to delete the module; the spec
  written against the code argued the narrower cut and was right.
- **Consequences / affects:** `docs/modules/feature-assistant.md`, `docs/ux/scenarios.md`
  (SCN-009, SCN-010), `README.md`, `DEC-0006`. It closes the product's largest disclosed-nowhere
  data flow (`G-03`), removes one screen from the Space keyboard problem (`B-01`), removes a secret
  from the Keystore surface, and deletes about 400 lines of source with their tests. What is paid:
  the product is now a capture tool with search, and the differentiator the brief named is gone
  until it returns.
- **Source:** operator decision 2026-09-20 · spec `docs/evidence/plans/2026-09-20-v2/T-033.md`

### DEC-0021 — The interface stays English

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** every string a person reads is English while every word they dictate is Russian
  (`G-09`). A `values-ru` was one option; following the note language was another.
- **Decision:** the interface stays English. No `values-ru`.
- **Consequences / affects:** `docs/ux/scenarios.md`. The one current reader of the interface reads English, so the
  cost is borne by nobody today; a second person who does not would find the app harder than it
  needs to be. Deferring also avoids translating strings that `T-032` is about to rewrite and
  `T-033` is about to delete — translating first would have meant translating twice.
- **Source:** operator decision 2026-09-20 · spec `docs/evidence/plans/2026-09-20-v2/T-034.md`

### DEC-0022 — The recorded audio is kept, and therefore must be disclosed and managed

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** a `.wav` of every dictation is stored forever with no player, no size shown, no
  per-recording delete and no interface string that mentions audio at all — about 1 MB per
  30 seconds, roughly 7 GB a year at twenty dictations a day (`G-02`, `G-16`, `G-12`).
- **Decision:** the audio is kept. It is what makes *Transcribe again* possible, and re-running a
  bad decode on a better model is the product's answer to a weak on-device model.
- **Consequences / affects:** `docs/modules/feature-vault.md`, `docs/ux/scenarios.md`. **Keeping it
  makes `T-024` obligatory rather than optional**: the interface must say that audio is stored, show
  how much, and allow deleting it — per note and in bulk. The default stays *keep everything*; a
  retention that defaulted to deleting would destroy months of recordings on the first launch after
  the update. A player is in scope as part of `T-024`, because storage a person cannot listen to is
  storage they cannot judge.
- **Source:** operator decision 2026-09-20 · spec `docs/evidence/plans/2026-09-20-v2/T-035.md`

### DEC-0023 — The second headset is a test copy, not a second user

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** a build runs on another person's Quest 3 with no account, no sync, no sharing and no
  way for anyone to learn that it is failing there (`G-30`/`H-35`, `H-32`). The operator left this
  one to the author.
- **Decision:** it is a copy for testing. The product gains no identity, no sync and no sharing in
  v1. Two obligations follow and are the whole of it: that person must be able to hand back a
  diagnosis without a laptop, and anyone must be able to say which build they are holding.
- **Consequences / affects:** `docs/product/product-definition.md`, `T-011`, `T-038`, `T-041`.
  `T-011`'s clipboard route is now load-bearing rather than a convenience, and `T-038`'s build
  identity is required rather than tidy. `T-041` shrinks to a GitHub Release carrying the APK and a
  written install step — not a distribution channel. Two people dictating into two vaults that
  never meet is accepted, and stops being acceptable the moment either of them expects to see the
  other's notes.
- **Source:** author decision 2026-09-20, delegated by the operator · spec
  `docs/evidence/plans/2026-09-20-v2/T-036.md`

### DEC-0024 — A chosen speech provider refuses out loud; the app never invents an address

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** `Graph` answered "is the remote configured?" in two places and differently. One
  required a URL *and* a key and returned `null` otherwise, so the router ran the on-device model
  and stamped the transcript plain `LOCAL` — indistinguishable from somebody who chose the headset
  on purpose (`D-03`). The other required only a key and filled the address in with
  `https://api.groq.com/openai`, so re-running an old recording "with the better model" posted it
  to a third party nobody had named (`D-10`). A third silence sat beside them: `DEC-0014` made the
  provider explicit without carrying over the people configured under the old rule (`H-26`).
- **Decision:** one function, `remoteEngineFor`, decides where speech goes for every entry point.
  A provider the person chose but did not finish configuring yields a `FailingEngine` carrying
  `AppError.SttNotConfigured(provider, ADDRESS|KEY)` — never `null`, and never a default endpoint.
  `SttProvider.LOCAL` is the only null, and it means nothing remote was asked for.
  `CloudTranscriptionClient.SUGGESTED_BASE_URL` remains a *placeholder* in the Settings field — a
  suggestion a person may accept — and is never a fallback. A one-shot settings migration, guarded
  by a schema marker rather than by the provider's absence, moves a pre-`DEC-0014` server URL onto
  `SttProvider.SERVER` and says so once in Settings.
- **Consequences / affects:** `Graph.kt`, `app/.../SttRouting.kt`, `core-common/.../AppError.kt`
  and its mapper, `SettingsScreen`, `TodayScreen`. An unconfigured provider now produces a visible
  `LOCAL_FALLBACK` instead of a silent local run, which is the input `SttRouter` was designed for
  and had never been given. A saved server URL is visible in Settings whatever provider is
  selected. What `NetworkPolicy` *considers* reachable stays `T-010`'s question; this decides only
  what happens when it refuses. The two error strings are EN drafts — the brand pack does not exist
  (`B-035`) and `copywriting` owns the final wording.
- **Source:** author decision 2026-09-20, delegated by the operator · spec
  `docs/evidence/plans/2026-09-20-v2/T-008.md`

### DEC-0025 — A decision's body is never edited; DEC-0020's was, and this entry is the repair

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:** this register's own rule (`docs/DECISIONS.md`, *To change your mind*) is "add a new
  entry, edit **only the status line** of the old one, leave its body intact." On 2026-09-20 the
  body of `DEC-0020` was rewritten in place: it originally said to remove the `:feature-assistant`
  module, and was revised to say the module stays and only the dependency goes. The revision was
  the *right answer* — the narrower cut is what `T-033` shipped — and the commit said openly that
  it had revised the text, which is why this was found rather than hidden. It is still the one
  edit in the register's history that its own rule forbids (checked with `git log -p` over every
  commit touching this file; no other entry has a rewritten body).
- **Decision:** the rule stands as written, and the violation is repaired by record rather than by
  a second rewrite. `DEC-0020`'s body keeps the corrected text, because reverting it would put a
  wrong instruction back into a register agents read as true; this entry is what makes the history
  legible. **The original clause, so nothing is lost:** *"remove … the `:feature-assistant`
  module."* The reason it changed: deleting the module would have deleted its tests with it, and a
  route that is closed is cheaper to reopen than one that was demolished.
- **Consequences / affects:** `docs/DECISIONS.md`. No code. A future agent that needs to change a
  decision's meaning writes a new entry; if the old body is actively misleading, it says so **in
  the new entry**, as this one does, rather than editing the old one. `T-046` carries this into the
  retro as a standing instruction.
- **Source:** the plan re-audit of 2026-09-20, decisions lane · `git log -p docs/DECISIONS.md`

### DEC-0026 — The vault leaves the headset as one zip in shared storage, and may be shared from there

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** the vault is `filesDir/vault` — app-private internal storage — with
  `android:allowBackup="false"`, no picker, no share, no external directory and no export of any
  kind. A release APK is signed with a different key than the debug builds on both fielded
  headsets, so `install -r` is refused and `adb uninstall` is the way through; it deletes
  `filesDir` entirely: the notes database, every `.wav`, the 190 MB model and every Keystore
  value. `README.md` hands the reader that command as routine. The product definition and
  `ST-008` both promise notes a person can take with them, and on this build that promise was
  false — not weakly supported, false (`G-01`, `H-23`).
- **Decision:** a single zip written to `MediaStore.Downloads` (`/sdcard/Download/`) from a row in
  Settings, with `IS_PENDING` held until the archive is complete, published on success and
  **discarded on failure** — an archive that looks complete and is not is the one a person relies
  on before an uninstall. Shared storage rather than anywhere prettier for one property: it is the
  only candidate `adb uninstall` does not delete. The export walks `vault.root/notes` and **not**
  the vault root, so a deleted note inside `DEC-0022`'s seven-day trash does not leave the device
  in an archive. Scope is a choice — *Notes only* by default, because a year of dictation is three
  orders of magnitude larger than the Markdown.
- **Consequences / affects:** `docs/modules/feature-vault.md`, `docs/modules/app.md`, `README.md`,
  `T-037` (which must not be attempted until an export has been taken off a headset), `T-027`
  (which owns the import side — this is not half of a pair and must not be read as one).
  **A share row sits beside the path**, guarded by `resolveActivity`: measured on both fielded
  headsets on 2026-09-20, Horizon OS carries `com.android.documentsui` and both Telegram and
  Discord register for `application/zip`, so the archive can leave without a cable — which is what
  `DEC-0023` asks for when it says the second person must be able to hand something back without a
  laptop. `T-023`'s spec had rejected the share route on the belief that nothing could receive a
  zip; that belief is recorded as refuted in `docs/evidence/verification.md` REQ-012.
  This is **not** the answer to `ST-008` — a vault a desktop can open live is a different, larger
  decision that changes `Vault.root` and migrates every stored `audioPath`. It is the answer to
  "an uninstall must not be fatal", which is smaller and more urgent.
- **Source:** run `2026-09-20-v2` task `T-023` · probe recorded at `docs/evidence/verification.md`
  REQ-012

### DEC-0027 — A crash is written down on the headset, redacted by shape, and never sent anywhere

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `DEC-0023` calls the second headset a test copy and makes one obligation follow from
  that — the person holding it must be able to hand back a diagnosis without a laptop. On this
  build a crash was a one-line chat report that the app had closed, against a logcat nobody captured,
  on a device that is not on this network (`H-01`, `H-02`, `H-03`).
- **Decision:** a default uncaught-exception handler appends one record to a bounded file in
  `filesDir/crash/`, **chaining to the previous handler** so the platform still does what it was
  going to do — a handler that swallows the exception leaves a process alive in a state nobody
  designed. The file is capped at 64 KB and trimmed **from the front**, because a crash loop
  writes the same failure repeatedly and the interesting one is the newest. `ApplicationExitInfo`
  is read once per launch behind a watermark for the half our handler cannot see: a native abort
  inside whisper, a low-memory kill, an ANR. **Nothing is sent anywhere** — two headsets do not
  need a backend, and one would put stack traces, which can carry note text in a message, on
  somebody else's server. A Settings row shows the most recent and copies it; the archive from
  `DEC-0026` carries the file, which is the deferral that decision recorded.
- **Consequences / affects:** `docs/modules/core-common.md`, `docs/modules/app.md`,
  `docs/modules/feature-vault.md`. **`Redaction.scrub` is a second copy of `check-secrets.sh`'s
  shape list**, and two copies of a security rule are one copy and one lie —
  `RedactionParityTest` reads the script and fails when they drift, checking its own marker list
  against the script first so it cannot pass by comparing with nothing. Blanket redaction was
  rejected: `Log2.redact` replaces a whole value with its shape, which is right when the caller
  knows it holds a secret and wrong for an exception message, where it would leave a report that
  diagnoses nothing. Crashlytics and Sentry were rejected for this size; revisit if the product
  ever has users rather than two headsets.
- **Source:** run `2026-09-20-v2` task `T-011`

### DEC-0028 — The panel keeps the SDK's Compose owners and adds only the one it lacks

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `DEC-0018` supplied all four Compose host owners to the Spatial SDK's panel, on the
  belief that an `AppSystemActivity` — a plain `android.app.Activity` — provides none. Entering the
  Space did crash without an `OnBackPressedDispatcherOwner`, so the decision's *symptom* was real
  and its *premise* was not.
- **Decision:** keep `OnBackPressedDispatcherOwner`, delegate its `lifecycle` to the SDK's owner,
  and delete the other three with the hand-driven lifecycle callbacks that fed them. Measured by
  disassembling `meta-spatial-sdk-compose-0.14.0.aar` on 2026-09-21: `ComposeFeature` holds one
  `PanelViewLifecycleOwner`, that class implements `LifecycleOwner`, `ViewModelStoreOwner` **and**
  `SavedStateRegistryOwner`, and `attachLifecycleToRootView` — called by `composePanel` — sets all
  three on the view tree, which is where Compose resolves them from.
- **Consequences / affects:** `docs/modules/app.md`. Three findings close by removal rather than by
  fixing: `I-12` (the hand-rolled registry had no `onStart`/`onStop`, so every
  `collectAsStateWithLifecycle` kept collecting behind a stopped Space), `I-03` (the store was
  cleared *before* the SDK disposed the composition, so the last write on every exit fired into a
  dead scope — `T-007` already made that survivable and this removes the cause) and `B-16` (a
  `Popup` or `AndroidView` subtree resolved a different store from its own parent).
  **The delegated lifecycle is a requirement, not a convenience:**
  `OnBackPressedDispatcher.addCallback(owner, callback)` uses it to *unregister*, so a registry
  nobody drives to `DESTROYED` would leak every `BackHandler` the app ever composed.
  **What this does not fix:** Back still delivers nothing in the Space — the dispatcher is fed by
  nobody, because `VrActivity.dispatchKeyEvent` never calls `super`. That is `T-013`.
- **Source:** run `2026-09-20-v2` task `T-012` · disassembly of the 0.14.0 AAR
- **Supersedes:** DEC-0018

### DEC-0029 — The Space gets a real Back, because the measurement that said it could not reversed

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `DEC-0028` left Back delivering nothing in the Space and named the reason:
  `VrActivity.dispatchKeyEvent` (0.14.0, 65 bytes, disassembled) routes the event to a pinned game
  controller or returns `false`, and **never calls `super`** — so `onKeyUp`, `onBackPressed` and
  the platform's `OnBackInvokedDispatcher` are all unreachable, and the dispatcher `DEC-0018`
  handed the composition was fed by nobody. `T-013` was written with two endings and one
  observation to pick between them: if a key can reach the activity, wire it; if not, delete the
  lambda that pretends and make the absence honest.
- **The observation, and the wrong answer it nearly gave.** `adb shell input keyevent 4` and `66`
  against a live Space, with an unconditional log at the top of an override, produced **no line at
  all** — and the Back brought `PanelActivity` forward, i.e. the shell consumed it. Read straight,
  that says "no key ever reaches an immersive activity" and the task deletes the dispatcher. It is
  wrong: an injected key goes to the **focused window**, and the shell's injection was not landing
  on this one. Injecting through the instrumentation instead, `SpaceBackTest` watched
  `KEYCODE_BACK` arrive at `ImmersiveActivity.dispatchKeyEvent`, down and up, on a Quest 3
  (`<serial>`, Horizon OS 207) on 2026-09-21.
- **Decision:** override `dispatchKeyEvent` in `ImmersiveActivity`. On `KEYCODE_BACK` + `ACTION_UP`
  + `repeatCount == 0` it asks the composition through `SpatialBackOwner`'s dispatcher and returns
  `true`; everything else goes to `super`, which preserves the SDK's pinned-game-controller branch
  — returning `true` unconditionally would swallow the volume keys. The dispatcher's fallback
  `returnToPanel()` is **kept**, not deleted: it is now reachable and it is what leaves the Space.
  The owner moves from the composition to the activity, since the activity is the only thing that
  can feed it, and keeps `PanelViewLifecycleOwner` as its lifecycle for `DEC-0028`'s reason.
- **And the exit control is no longer disguised as its own opposite.** Today rendered *enter the
  Space* and *leave the Space* with the same `ViewInAr` glyph, the second labelled "Back" — a word
  that means "pop one screen" everywhere else in this app (`B-25`). It is now *Leave the Space*
  with `ExitToApp`. This is one string and one icon and it belongs here rather than in `T-030`,
  because it is the control the whole "can the person get out" question rests on.
- **Consequences / affects:** `docs/modules/app.md`, `docs/ux/screens.md`, `docs/ux/scenarios.md`.
  Closes `I-13` and `B-15` (cited as B-14 in the plan — see `T-013.md`'s correction). **`B-25` is
  only half-closed:** its glyph collision is gone, its other half — five icon-only header actions
  carrying no *visible* text label, only a content description — is untouched and stays T-030's.
  `SpaceBackTest` fails in **two directions on purpose**: red if no key arrives (the platform
  changed and the Space lost its exit key) and red if a key arrives and the Space stays open
  (this app broke). Both plants were watched failing on the device before this shipped.
  **What is still unmeasured:** whether a *physical* controller's B button becomes a
  `KEYCODE_BACK` in an immersive session. No injection reaches this surface from outside the
  process, so it needs a person wearing the headset — `docs/evidence/device-gate.md` carries that
  row and `B-115` carries the ask.
- **Source:** run `2026-09-20-v2` task `T-013` · `SpaceBackTest` on a Quest 3, 2026-09-21

### DEC-0030 — One whisper context, one owner, and closing it never holds a thread

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `DEC-0019` was right that changing the model must close the loaded context, and the
  mechanism it authorised was the defect. `WhisperEngine.close()` used `runBlocking(dispatcher)`:
  the free happened on the whisper thread, which is correct, but the **caller's** thread was parked
  until that thread's queue drained — and the queue could hold a `whisper_full` thirty seconds from
  finishing. Every caller was on `Dispatchers.Main.immediate`, reached through `Graph.localEngine()`,
  which was `@Synchronized` on the `Graph` object itself. Changing the model or pressing *Transcribe
  again* during a transcription therefore froze the entire interface — the recording meter, the
  Space's panel, everything — past Android's five-second ANR limit (`I-04`, `C-03`).
  The same function's *Transcribe again* branch built a **second** `WhisperEngine` for a
  non-selected model and closed it in a `finally`: two native contexts at once, up to 1.11 GB on a
  device with no swap, and a second blocking free on Main (`I-08`).
- **Decision:** one context per process, owned by `LocalWhisperOwner` in `feature-stt`, which is the
  only place a `WhisperEngine` is constructed or closed. Every use goes through
  `suspend fun use(model, onWait, block)`, which takes a `Mutex`, decides *inside* it whether the
  loaded context matches, and lets no engine reference escape. `close()` is `suspend`,
  `NonCancellable`, idempotent, and sets `closed` **after** the free rather than before.
  `Graph.sttEngine()` is replaced by `withStt { }` for the same reason: it used to hand out an
  `SttRouter` holding the context, a reference that outlived any lock that built it.
- **What it trades.** A *Transcribe again* with a different model now **evicts** the cached one, so
  the next ordinary dictation pays about two seconds of model load. That is taken deliberately:
  *one context* is a rule that can be stated and tested, *"at most two, briefly, under these
  conditions"* is not — and the alternative was an app that could allocate a gigabyte of native
  memory inside a click handler.
- **What it does not fix.** The *wait* is still up to thirty seconds when a model switch queues
  behind a running transcription. It is a suspended wait now, so the interface is alive and says
  `state_preparing_engine`; making it **short** needs `whisper_full_params.abort_callback` through
  JNI, which changes the native ABI and is `B-118`.
- **Consequences / affects:** `docs/modules/feature-stt.md`, `docs/modules/app.md`. Closes `I-04`,
  `C-03`, `I-08`, `I-25`, `I-26`, and `A-14` by derivation — `WhisperEngine.name` was the constant
  `"whisper-small-q5_1"` whatever was loaded, so the feature whose purpose is comparing models
  recorded one name for all five. `C-06`'s other half closes with the scratch-audio sweep.
  **`grep -rnE runBlocking` over every main source set is now a gate** (`scripts/check-seams.sh`),
  because "every caller is off Main" is not checkable and "this string does not appear" is. That
  gate's first version used `\brunBlocking\b` and matched **nothing** — POSIX ERE has no `\b` —
  while its canary grepped a different pattern in a different file and therefore proved that
  `grep` works rather than that the check does. Both are fixed; the canary now exercises the
  check's own function.
- **Source:** run `2026-09-20-v2` task `T-018`
- **Refines:** DEC-0019, DEC-0007

### DEC-0031 — The dispatcher belongs to the layer that blocks, not to the caller

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** the 2026-09-20 audit enumerated fourteen pieces of blocking work reached from
  `Dispatchers.Main.immediate` — Keystore AES-GCM decrypts behind the Record button, `File.exists`
  on the model, a 1.9 MB WAV encoded and written at the moment the person lets go, a twelve-
  kilobyte context rendered before the first token of an answer, an `OkHttpClient` built inside a
  click handler, and a StrongBox key generated inside a `@Synchronized` block on the first tap of a
  fresh install. In the 2D panel that is jank; in the Space the panel's texture is redrawn from the
  thread that composes it, so it is a frozen rectangle hanging in the room while the passthrough
  world behind it keeps moving.
- **Decision:** three rules, written in `docs/modules/app.md` where a developer meets them rather
  than only in a spec. **R1** — a function that touches the Keystore, the filesystem, JNI or the
  network is `suspend` and switches its own thread. **R2** — a `viewModelScope.launch` body does
  nothing blocking itself; what it must do goes in `withContext(io)` with `io` injected. **R3** — a
  composable never calls a suspend function that writes.
- **Where the boundary is, and why it is not further in.** R1 is applied at `Graph`'s accessors,
  not at `SecureSettings.get`. That interface has two implementations, six key constants and
  callers in three modules, one of which takes `apiKey: () -> String?` and is not ours to change.
  Moving the boundary inward is correct and is its own task; putting it at `Graph`, which every
  app-side caller already goes through, buys the same property for this app at a tenth of the diff.
- **What it cost that was not obvious.** `start()` became asynchronous, so the record state moves a
  frame later — checked against `RecordControl`, which reads a collected flow and not this call's
  return. `Graph.modelStore(model = whisperModel())` split in two, because **a default argument
  that quietly decrypts is how five Keystore reads reached the drawing thread**; the pure overload
  stays synchronous and the chosen-model one suspends. And `AudioRecorder` gained a `Recorder`
  interface — not to fix it, but because a final class whose `record` loops `while (isActive)`
  against Robolectric's always-full shadow buffer made every assertion about what happens *after* a
  recording unwritable: the suite died of `OutOfMemoryError`, the second time only when every class
  ran together.
- **Consequences / affects:** `docs/modules/app.md`, `docs/modules/feature-stt.md`. Closes `I-09`,
  `I-11`, `I-14`, `I-21`, `I-28` and `B-111`. `C-03` closes jointly with `DEC-0030`, whose
  `suspend fun close()` is the correct shape — C-03's own suggestion, wrapping `close()` in
  `withContext(Dispatchers.IO)`, would have moved the `runBlocking` to a pooled thread and parked
  that one for thirty seconds instead. `I-02` is explicitly **not** closed: it is named under R3
  and it is a data-loss defect, and counting it as main-thread work is how it would look handled.
  `tools/check_main_thread.py` + `MainThreadPolicyTest` enforce R2; the checker's real baseline was
  **1, not 11**, because `T-017` had already moved the `Graph.` reaches behind constructor
  defaults — the scan got weaker as the code got better, which is the reason R1 is the rule and the
  scanner is only its backstop.
- **Source:** run `2026-09-20-v2` task `T-019`

### DEC-0032 — A dictation is at most ten minutes, and reaching the limit transcribes it

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** nothing bounded a recording. A person who started dictating and was distracted —
  took the headset off, walked away, was spoken to — recorded until the process died of memory,
  and **everything they had already said died with it**, because a recording only becomes a file
  when it stops (`E-03`). The memory got there fast: the buffer was an `ArrayList<Short>` fed a
  fresh `ArrayList<Short>` per chunk, and on ART a boxed `Short` outside the −128…127 cache is
  about twenty bytes to hold two — 19 MB of Java objects per minute of audio, beside whisper's
  190–574 MB native context on a device with no swap (`E-02`).
- **Decision:** ten minutes, `VoiceViewModel.MAX_SECONDS`. At nine the meter becomes a countdown
  and says so; at ten the recording **stops and transcribes**, exactly as if the person had
  pressed stop.
- **The bound is transcription time, not memory, and that is the whole reasoning.** Ten minutes is
  19.2 MB of `ShortArray`, which is nothing. It is also about **thirteen minutes of transcription**
  at the 1.31× real time measured on this headset with the default model at four threads, during
  which the person has a screen that says *Transcribing* and a native context that cannot be
  interrupted (`DEC-0030`). Anything longer is a promise the device cannot keep. The WAV is also
  kept for ever (`G-02`), so 19 MB per dictation compounds. Longer honestly means chunked
  transcription — whisper already works in 30-second windows — which is a feature, not a bigger
  number. **This is the spec's recommendation taken as written; the operator was offered the
  choice and the run is autonomous, so it is one constant to change.**
- **Discarding at the limit was rejected outright.** It would be `A-03` in a new place: the app
  deleting a recording it had just told the person it was making.
- **Three mechanisms end a recording, because any one alone leaves a hole:** the header actions are
  **disabled** while recording (disabled, not hidden — a control that vanishes mid-gesture sends
  the ray somewhere the person did not choose); a `DisposableEffect` calls `stopForNavigation()`
  when the screen goes away; and `LifecycleEventEffect(ON_STOP)` covers the headset coming off.
  **The `ON_STOP` half only works in the Space because of `DEC-0028`** — before it, the panel's
  composition shadowed the SDK's lifecycle owner with one that never received `ON_STOP` (`I-12`).
- **Consequences / affects:** `docs/modules/feature-stt.md`, `docs/ux/scenarios.md`. Closes `E-02`,
  `E-03`, `I-10` and `C-05`. `C-05` was **misfiled on `T-016`** in the plan, where it had nothing
  to do with the subject; it is `AudioRecorder`'s negative-`read` busy loop and it is in the twelve
  lines this task rewrote — left where the plan put it, it would never have been fixed, because
  T-016 does not open that file.
- **Two seams this needed, named because they are production code that exists for a test.**
  `PcmSource` — `android.media.AudioRecord` is final and its Robolectric shadow answers every read
  with a full buffer, so a negative read could not be made to happen and the `C-05` fix would have
  been a claim. And `maxSamples` as a constructor parameter of `VoiceViewModel`, so a test reaches
  the cap without recording ten minutes of virtual time.
- **Source:** run `2026-09-20-v2` task `T-020`

### DEC-0033 — One download per model, owned by the process, with a free-space precondition

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `VoiceViewModel.downloadModel()` and `SettingsViewModel.downloadModel()` each held
  their own `Job` and each cancelled only their own, and both resolved to the same
  `File(root, modelName + ".part")`. Both view models are alive at once — Today stays on the back
  stack while Settings is open — and the app tells the person to press *Download* on **both**
  screens. Two writers into one file, each digesting only its own bytes, so both fail the pinned
  SHA-256, both delete the file, and the person is told *"The downloaded file was corrupt and was
  removed."* **380 MB of headset Wi-Fi, and the model can never finish** for as long as both
  screens are on the stack (`I-05`). The pinned digest is what keeps this a wrong-result race
  rather than a corruption one, which is why it presents as a checksum that keeps failing.
- **Decision:** `ModelDownloads` in `feature-stt` owns every transfer, **keyed by model**. A second
  `start` for a model already downloading joins it. `cancel` stops the transfer for everyone —
  a behaviour change: Cancel used to end one screen's view of a download that carried on for the
  other. Both view models take the narrow `Downloads` interface; neither holds a `Job`.
- **Its own scope, not `Graph.scope`.** A 190–574 MB transfer is exactly the long-running
  cancellable work `I-29` says must not silently join the scope carrying the app's small startup
  coroutines, and it belongs on `IO` where a stalled socket parks a pooled thread rather than one
  of `Default`'s core-count workers.
- **Keying by model is what closes `I-22`/`A-21`.** Choosing a different model used to cancel the
  running download, and cancellation deletes the partial — a 539 MB transfer gone, silently,
  because the person looked at a different name in a list. Settings now shows *"medium is still
  downloading (43%)"*, without which that transfer becomes invisible, and refuses to delete a
  model while it is being written.
- **And it closes `A-22`, which is the one a person will actually notice.** `ModelDownloader`'s
  resume path — a `Range` header, a 206-vs-200 check, a digest replay over the existing bytes — is
  the best-engineered part of that class and **had never run**: the collection lived in
  `viewModelScope`, leaving the screen cancelled it, and the cancellation deletes the `.part`.
  Holding the download in the owner makes it reachable.
- **The free-space precondition, before the first byte.** Five models total 1.395 GB and every
  dictation keeps its WAV for ever, so a full headset is the expected end state, not a corner
  case — and it was reported as *"The download stopped. Check the network."* Every throwable from
  the write loop was `Reason.NETWORK`, `ENOSPC` included, while the correct string sat three lines
  away reachable only from a failed `renameTo`, which is the least likely way to run out of space
  because source and target share a directory (`G-06`, `G-07`). The check needs only the bytes
  still **missing**, with a ten-per-cent margin in integer arithmetic, and `getAllocatableBytes`
  is preferred over `StatFs` because a **false** refusal is the worse failure: the person has no
  way to disprove it. A probe that cannot answer falls back to "plenty".
- **The classifier walks the cause chain and drops `FileNotFoundException`.** `G-06`'s own
  proposal did neither: `FileOutputStream.write` surfaces `ENOSPC` as an `IOException` whose
  *cause* is the `ErrnoException`, so reading `t.message` alone misses it; and
  `FileNotFoundException` on an existing directory is usually permissions, so folding it into
  `DISK` tells that person to free up room. Both mistakes are pinned by a test.
- **Not done:** `WorkManager`, which would survive process death and give retry-with-backoff for
  free. It is the upgrade path and it is named here so it is not rediscovered: it adds a
  dependency, a worker, a notification channel and a foreground-service type on a platform where
  none of that has been tried in this project.
- **Consequences / affects:** `docs/modules/feature-stt.md`. Closes `I-05`, `G-06`, `G-07`,
  `I-22`, `A-21`, `A-22` and `I-28`'s remaining half. **The `ENOSPC` reproduction was not made** —
  `B-124` — so which of the two branches of `isNoSpace` fires on this hardware is unmeasured;
  both stay, and both would stay anyway.
- **Source:** run `2026-09-20-v2` task `T-021`

### DEC-0034 — *Leave the Space* stays live while a dictation is running

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `DEC-0032` disabled the Today header's actions during a recording, so a dictation
  could not outlive the screen showing it — the microphone staying hot with nothing on screen
  saying so is `E-03`. The header holds four actions and one of them is the **only way out of an
  immersive surface**. Disabling it left a person in the Space unable to leave until they stopped
  talking, and `DEC-0029` could not establish that a physical controller's Back even arrives
  (`B-115`), so there was no second door. Found by the group verification of steps 4–5; no spec
  anticipated the collision, because `T-013` gave the control its own word and `T-020` greyed it
  out four hours later.
- **Decision:** *Search*, *Space* and *Settings* are disabled while recording; ***Leave the
  Space* is not.** The exemption is safe rather than a compromise: the `DisposableEffect` and
  `LifecycleEventEffect(ON_STOP)` that `DEC-0032` added both call `stopForNavigation`, which
  **transcribes** — so leaving mid-dictation keeps what was said and the note is waiting on
  return. The risk `E-03` names is a recording nobody can see; leaving the Space does not create
  one, because the recording ends with the screen.
- **What this is not:** it is not a hole in `DEC-0032`. Navigation *within* the app — Search,
  Settings — still leaves the view model on the back stack with the recorder running, which is
  exactly the case the disabling exists for. Leaving the Space destroys the host.
- **Consequences / affects:** `docs/ux/scenarios.md` (SCN-004, SCN-012), `docs/ux/screens.md`
  (SCR-01, SCR-08), `docs/modules/app.md`. Refines `DEC-0032`.
- **Source:** group verification of steps 4–5, run `2026-09-20-v2`
- **Refines:** DEC-0032


### DEC-0035 — A restored daily note gives up its day rather than destroying the one that holds it

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `notes` carries a UNIQUE index on `dayKey` and every write is `@Insert(REPLACE)`, so
  a note claiming a day another note already holds **silently deletes that other note**. `upsert`
  then removed only the new note's index row, so the evicted note's row outlived it: the `JOIN`
  drops it, a search finds fewer things than it counted, and the index grows for ever (`A-24`).
  The reachable path is *Undo* — restore yesterday's deleted daily note while today's exists, and
  the note the person has been typing into all day is gone with no message.
- **Decision:** two halves. The **mechanical** one: `upsert` deletes the displaced note's index
  row before the `REPLACE`, so no orphan can survive. The **product** one, of three options: the
  restored note comes back **without** its `dayKey`. It keeps every word it had and stops being
  *the* note for that date — a heading changes and nothing is lost.
- **What the alternatives cost.** Refusing the undo needs a new string and a new failure path the
  person did not ask for, and it leaves them holding a note they cannot get back. Merging the two
  texts is the most "correct" and the most surprising: two things silently become one, and
  nothing tells them which parts were whose.
- **Consequences / affects:** `docs/modules/core-notes.md`. Closes `A-24`.
- **Source:** run `2026-09-20-v2` task `T-022`

### DEC-0036 — Every schema version is exported and committed in the change that creates it

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `exportSchema` was `false` when v1 of the database shipped, so `1.json` never
  existed — and Room's `MigrationTestHelper` starts from an exported schema. No test could begin
  at v1, so `MIGRATION_1_2` had **never once been executed** by anything, and it deletes rows
  (`H-24`, `H-25`, `A-12`). At version 3 a device still on schema 1 would have raised
  `A migration from 1 to 3 was required but not found` on every launch, with no way in and no way
  out but uninstalling — which destroys the notes, because there was no export either (`T-023`).
- **Decision:** `scripts/check-schemas.sh` fails the build when a version between 1 and the
  declared one has no committed JSON, or when the build regenerated a schema that differs from
  the committed one. It runs in `check-all.sh`. The rule is written here as well as in the script
  because **a CI step nobody can explain gets deleted the first time it is inconvenient.**
- **The missing `1.json` was reconstructed, not invented.** Generated from `16a5fb5^` — the same
  entities with `exportSchema` switched on and a `room.schemaLocation` argument added — which
  reproduces the identity hash a v1 device actually carries. Verified by diffing against the
  committed `2.json`: the **only** differences are the version, the hash, and the absence of
  `index_notes_dayKey`. A hand-written file would have tested a database no device ever had.
- **And the harness could validate against a stale schema.** The `schemas` directory is both a KSP
  output and a test asset source, and nothing ordered the two: a tokenizer change reported
  `Migration didn't properly handle: note_fts` on the first invocation and passed on the second
  with no file edited in between. `core-notes/build.gradle.kts` now makes every asset merge depend
  on KSP. A test whose green depends on invocation order is worse than no test, because the green
  is the one that gets kept.
- **`fallbackToDestructiveMigration()` is still absent**, and deliberately so even now that
  `VaultImporter` exists. It converts *"the app will not launch"* into *"the app launches and the
  notes are gone"*, silently. Adding it in the same change as the thing that makes it survivable
  is how it ends up shipped without anyone deciding. `docs/OPEN_QUESTIONS.md` carries the choice.
- **Consequences / affects:** `docs/modules/core-notes.md`, `docs/modules/feature-vault.md`.
  Closes `H-24`, `H-25`, and `A-12` by finally executing `MIGRATION_1_2`.
- **Source:** run `2026-09-20-v2` task `T-027`

### DEC-0037 — An unreadable setting is not a reason to delete it

- **Date:** 2026-09-21
- **Status:** Accepted · **Refined by DEC-0075** — a third class for an entry that exists and cannot be opened
- **Context:** `KeystoreSecureSettings.get()` wrapped the whole decrypt in `runCatching` and, on
  **any** throwable, deleted the stored ciphertext. It could not tell *"the Keystore entry was
  replaced"* — permanent, where deleting is right because keeping the bytes means re-failing for
  ever — from a transient `KeyStoreException`, a busy keystore, or a device still finishing boot.
  **One transient error destroyed the value permanently** (`C-04`). And the same store holds every
  non-secret setting — the server URL, the provider, the model, the language — so a run of
  transient errors silently reset the app's whole configuration one key per read, with only the
  OpenRouter key's loss surfaced anywhere.
- **Decision:** classify. *Permanent* is `AEADBadTagException`, `BadPaddingException`,
  `KeyPermanentlyInvalidatedException` and a malformed stored string — matched on **type**, never
  on a message, and through the cause chain, because a provider wraps. Those delete the bytes and
  publish `corruptedKeys` as before. *Everything else is transient*: the bytes stay, and the key
  joins `unreadableKeys`, a second set that empties the moment a read succeeds.
- **The default is transient, deliberately.** Misclassifying a permanent failure costs one
  pointless read; misclassifying a transient one costs the person their data. The two are not
  symmetric and the fallback should not pretend they are.
- **Two facts need two sentences.** Settings said *"could not be read — enter it again"* for both.
  Telling somebody to re-enter a key that is still stored and merely unreadable this second is
  the wrong instruction, so the transient case has its own line saying it is still there.
- **`I-06` lands with it**, because it is the same shape one module over: `VaultMirror._failures`
  was four unsynchronised read-modify-writes across `Dispatchers.Default` and Main — the mirror's
  collector and Settings' *Retry* — so a lost update erased a failure recorded microseconds
  earlier and **a note that never reached the vault looked mirrored**. `update {}` now, with a
  hundred concurrent failures on real threads as the test; a single-threaded scheduler cannot
  produce a lost update and would have been a green that proved nothing.
- **Consequences / affects:** `docs/modules/core-common.md`, `docs/modules/feature-vault.md`.
  Closes `C-04` and `I-06`. The non-secret settings stay in the encrypted store — moving them out
  is a separate change with its own migration, and it is no longer urgent now that a transient
  failure cannot destroy them.
- **Source:** run `2026-09-20-v2` task `T-048`

### DEC-0038 — The voice archive is disclosed and bounded, and the retention default keeps everything

- **Date:** 2026-09-21
- **Status:** Accepted · **Corrected by DEC-0074** — "names what it is about to remove" was false in the shipped build
- **Context:** every dictation writes a `.wav` into the vault and keeps it for ever, and **no
  string anywhere in the product mentioned that audio existed**. The one vault sentence talks
  about Markdown, which is roughly 0.1% of what is stored. 16-bit mono at 16 kHz is 32 kB a
  second: twenty half-minute dictations a day is 19.2 MB a day and **7 GB a year**. Somebody
  dictating private material for months believed they were keeping text; what they were keeping
  was an archive of everything they had said that they could not hear, list, measure or reach,
  and could only remove by deleting the note it belonged to, one row at a time (`G-02`, `G-16`).
  That is a privacy property the product acquired without stating it.
- **Decision:** say it, size it, let them remove it. Settings gains a **Recordings** block with
  the count and the size, the sentence that did not exist, a per-age retention control and a
  *Delete now* button that **names what it is about to remove before it removes it**. Each note's
  row gains *Delete recording*, which takes the audio and keeps the note — `Vault.remove` took
  both in one call, so wanting the audio gone meant giving up the text.
- **The default is *Keep everything*, and that is the decision rather than the mechanism.** A
  retention defaulting to thirty days is the right engineering answer and the wrong thing to
  ship: the first launch after this update would have deleted months of a person's
  recordings, before they had read the screen that announces the feature. That is the same class
  of failure as a *Retry* button that deletes. The mechanism ships, the four options are put in
  front of them, and nothing fires on its own — asserted by a test, not by intention.
- **The sweep never touches `.trash`, and this is not tidiness.** `Vault.remove` moves a note's
  files with `renameTo`, which **preserves mtime** — so a recording deleted yesterday, whose file
  was written a hundred days ago, would be destroyed by a ninety-day cutoff *inside its own
  seven-day undo window*. `purgeTrash` owns the trash on its own schedule.
- **And a sweep clears the rows it swept.** Deleting the files and leaving `audioPath` populated
  turns every swept note into a *Transcribe again* button that fails, which is a worse state than
  the disk usage it was fixing. The vault knows nothing about rows, so the join lives in
  `SettingsViewModel` — where the button is.
- **Consequences / affects:** `docs/modules/feature-vault.md`, `docs/ux/screens.md`,
  `docs/ux/scenarios.md`. Closes `G-02` and `G-16`'s disclosure half. **`G-16`'s compression half
  is not closed** — Opus instead of raw PCM is a 10.7× reduction and it changes what
  `WhisperEngine` is handed at re-transcription time, so it is its own task (`B-137` — read `B-138`; the id was off by one, corrected 2026-09-24 by `B-203`).
  `settings_vault_hint` is now incomplete rather than wrong — it says Markdown and stops — which
  is `T-032`'s sweep to finish.
- **Source:** run `2026-09-20-v2` task `T-024`

### DEC-0039 — The recording can be played, from the row that owns it

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `DEC-0022` decided the audio is kept because it is what makes *Transcribe again*
  possible. It did not give anyone a way to hear it, and **the player had a circular deferral
  where its owner should have been**: `T-024` handed it to `T-035` and `T-035` handed it back, so
  `G-12`'s central complaint — *"twenty lines and it turns 99.9% of the stored bytes from a
  liability into the feature that justifies them"* — was owned by no task in any of the 46 specs.
- **Decision:** `AudioPlayback`, and `MediaPlayer` rather than media3. One 16 kHz mono WAV, no
  streaming, no playlist, no seek: a library dependency for a `start()` and a `release()` would
  be a cost with no matching benefit. **One player at a time, owned by the composition that
  started it** — a second tap on another row stops the first, and leaving the screen releases it,
  because a `MediaPlayer` that outlives its row holds a file descriptor nothing will ever close.
- **A missing file is not an error worth a banner.** The recording may have been swept or deleted
  from another surface; the button does nothing and the fact is logged by note id. Accusing the
  person of a state the app created is worse than silence.
- **Consequences / affects:** `docs/ux/screens.md`, `docs/ux/scenarios.md`. Closes `T-050` and the
  half of `G-12` that `DEC-0038` does not. Whether the audio should be kept **at all** remains
  `T-035`'s.
- **Source:** run `2026-09-20-v2` task `T-050`

### DEC-0040 — Every list and search query carries a window, and paging has a trigger rather than a date

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `observeAll()` and `search()` had no `LIMIT`, and `observeTags()` is a second full
  scan `combine`d with the list. Room invalidates on the whole `notes` table and the editor
  autosaves every 600 ms, so **every keystroke re-ran both scans and emitted two identical
  states** (`G-10`, `E-12`). At the real note count none of that is a wall, and the honest answer
  in the spec is *"not yet"* — about 1 KB of text per note, so ~0.2 MB at two hundred.
- **The exception, and it is why this shipped now: `DEC-0035`'s tokenizer change armed it.**
  `search` builds a prefix match, and while the tokenizer was `simple` a one-letter Russian query
  matched almost nothing — the missing `LIMIT` was dormant. `unicode61` makes `п*` match
  essentially every Russian note in the base, and Search runs that on a 120 ms debounce **while
  the person types**. A dormant unbounded query became a live one on the day `T-022` landed.
- **Decision:** `LIMIT :limit` on `observeAll` and `search`, `DEFAULT_WINDOW = 200`, a `count()`
  so a clipped list can say how many it is not showing, `distinctUntilChanged()` on the derived
  tag list, and `showAll()` to raise the window. `observeByTag` stays unbounded and the reason is
  in its KDoc: a single tag's notes are a subset by construction, and clipping a chip would mean
  showing some of what it matched.
- **The bound is enforced by the compiler, not by care.** Room refuses an unused query parameter,
  so `LIMIT :limit` cannot be deleted while `limit` is in the signature — measured by trying, and
  a stronger guarantee than the test that motivated it.
- **`androidx.paging` is rejected with an expiry, not on principle.** A new dependency plus two
  rewrites, for a problem that starts past a thousand notes on a two-person deployment. `OQ-0003`
  records the two triggers — a note count and a frame-time condition — so the decision is
  scheduled rather than forgotten. A normalised tag table is the right eventual shape for `E-12`
  and it is schema v4, immediately after `DEC-0035`'s v3: two migrations in one release is what
  `H-24` warns about, so it waits.
- **What the window does not fix, said because it would otherwise become a bug report:** the 200
  kept are the most **recent** matches, not the best ones — `search` orders by `updatedAt`.
  Ranking is `G-11`'s and a different question.
- **`E-21` folds in:** `adoptOrKeep` was vault policy living in `:app`'s `ui` package, where
  `:feature-vault`'s own tests could not reach it. Moved, and the test that could not be written
  before — a failed adoption keeps the scratch path rather than reporting no recording — exists.
- **Consequences / affects:** `docs/modules/core-notes.md`. Closes `G-10`, `E-12` and `E-21`.
  **The plan's row for this task named E-21 where it meant E-12**; the row is corrected. `T-030`
  owns the two rows that render `total` and `showAll`, and the strings are written so it finds
  them. `T-025` needs the same `count()` and now has it.
- **Source:** run `2026-09-20-v2` task `T-026`

### DEC-0041 — A text note can be created, and the day has a card

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** **there was no way to make a text note.** `NotesViewModel.createNote()`, the string
  `action_new_note` and `Routes.editor(id)` all existed and **no screen called any of them**
  (`A-01`), so the editor was reachable only through an existing row — and on a fresh install
  there are none. The first thing a person saw was an empty state naming two things it offered
  neither of. `git log -S` names the commit: `732c92b`, `DEC-0010`'s simplification, which
  deleted the hold gesture and the capture sheet for good reasons. **Taking note creation with
  them was not part of that decision and is recorded in no DEC.**
- **Decision:** *New note* is a **labelled** action in the header, first, beside Search, and it
  opens the editor on the note it creates. Labelled rather than a sixth silent glyph: `B-25` is
  that five unlabelled icon actions already sit in that row, two of them the same glyph pointing
  in opposite directions.
- **It does not auto-focus the title field, and that is `B-01` rather than a preference.** In the
  Space a panel is a `Presentation` on a `VirtualDisplay` and `PanelDisplayBase.dispatchEvent`
  returns **without dispatching** while `imm.isAcceptingText()` — so the moment a keyboard opens,
  the panel stops receiving taps at all, including *Back*. Auto-focusing on entry is a trap the
  person cannot leave. `SearchScreen` already sets it; the editor must not, and `T-014` owns the
  text-entry question.
- **Rejected:** a second button beside Record (the record control is 128 dp and the screen's
  whole point; a second of comparable weight makes the person choose before they have done
  anything); *New note* only in the empty state (unreachable the moment a first note exists — the
  same defect, smaller); a floating action button (it overlaps the list, and in a headset an
  element floating over content is a target with nothing behind it to steady the aim against);
  and inline creation on Today (a second editing surface to keep in step with the real one).
- **The day card** renders `state.dayLabel` and today's note above the record control. Both were
  already in state and nothing rendered either, so the daily note created silently at launch
  appeared as an unexplained row bearing today's date. It is **chrome, not a list item**: `T-030`
  is solving a height budget at the manifest's 360 dp floor and this card's height is one of its
  inputs.
- **What is not done here:** `today_empty` is now *incomplete* rather than wrong — there is a
  second thing a person can do and the string does not say so. Half-correcting it would be worse
  than leaving it, because it would look checked; `T-032` owns every stale string. Tag chips are
  `T-029`'s and the per-row time and glyph are `T-030`'s.
- **Consequences / affects:** `docs/ux/screens.md`, `docs/ux/scenarios.md`. Closes `A-01`, and
  `B-02` in part — three of its six elements, with the split written into the task so nothing
  falls between it and `T-029`/`T-030`.
- **Source:** run `2026-09-20-v2` task `T-028`

### DEC-0042 — Tags are a single-select chip row, and a selected tag is always releasable

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** the tag feature was complete except that **nobody could reach it**. `TagParser`
  parses `#идея`, the repository stores it, derives the list and filters by it, `NotesViewModel`
  holds the list and the selection in state and exposes `selectTag` — and **no composable read
  any of it** (`D-01`). `NotesRepositoryTest` covered the whole path and passed. `SCN-003`
  steps 2–4 could not be performed at all.
- **Decision:** a single-select `FilterChip` row between the day card and the record control,
  with an *All* chip, **hidden entirely when no tag exists**.
- **Single-select, because that is what the data layer offers.** `observeNotes(tag: String?)`
  takes one tag; multi-select means a dynamic `WHERE`, which means `@RawQuery` — a data-layer
  change inside a screen task, in a module two migrations just touched.
- **Chips rather than a dropdown, and this is `B-09`.** A `DropdownMenu` is a `Popup`, a second
  window on the panel's `VirtualDisplay`, and whether it composites into the panel texture *and*
  receives injected input is unverified. Putting the only route to a feature behind an unproven
  widget is how *Transcribe again* became unreachable in the Space.
- **`FlowRow`, not `LazyRow`:** tags wrap onto a second line rather than scrolling off one. A
  horizontal scroller inside a panel aimed at with a controller ray is a control that moves while
  you aim at it. And the chips carry the 72 dp floor `Tokens` declares and most of the product
  ignores (`B-18`) — a bare material chip is 32.
- **Three empty cases, and conflating them was the risk.** No tag anywhere: **nothing at all** —
  somebody who has never used tags should not be told about the feature by an empty container,
  and the row appearing the first time a `#` is parsed is its own discovery. Tags but none
  selected: the row with *All* selected. A selected tag whose notes are all gone: the chip
  **stays** and the list says *Nothing tagged #x* — falling back to *All* silently would make a
  delete look like a filter that broke.
- **And the fourth, which a naive version gets wrong:** a selected tag that stops *existing* —
  the person edits the last `#идея` out of the last note while the filter is on. `observeTags()`
  re-emits without it and `selectedTag` still holds it, so a row rendered from `state.tags` alone
  shows a filter with **no chip to release**, an empty list and no way out. The composable
  renders `(tags + selectedTag).distinct()`; the view model, which is tested and correct, is
  untouched.
- **Consequences / affects:** `docs/ux/screens.md`, `docs/ux/scenarios.md`. Closes `D-01` and
  `B-02`'s tag-chip element. **`SCR-04`'s chips remain unbuilt** — Search filtering a query's
  results is different behaviour with a different empty case and `SearchViewModel` has no tag
  state at all; `T-042` records it as a doc-versus-build gap rather than assuming this covered it.
- **Source:** run `2026-09-20-v2` task `T-029`

### DEC-0043 — The Today frame is three fixed things and one list, and its minimum is measured

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `B-11` and `B-13`, which are one defect seen from two sides. The `LazyColumn` was
  the last, **unweighted** child of a `Column` with no `verticalScroll`, and the error banner, the
  "saved and copied" line and the Undo row were its siblings. A `Column` measures an unweighted
  child with what is left, so with those three up the list was measured at **zero** — the notes
  were not drawn and there was no scroll to reach them. The same arrangement moved the record
  button, because each voice state's message above it was a different height, and the doc comment
  claiming it "keeps its place and its size through all of them" had been false since `732c92b`.
- **Decision:** exactly three things are fixed chrome — the header, a **status `Box` of
  `Tokens.Space.statusSlot` (130 dp)**, and the 128 dp record button. **Everything else is an item
  in a `LazyColumn` with `weight(1f)`**: the banner, the confirmations, the Undo row, the day
  card, the tag chips, the notes and the "and N more" row.
- **This is not `T-030`'s spec diagram, and the spec was wrong by arithmetic.** It kept the day
  card and the chips as chrome above the button, and its own budget omitted the button: 48 padding
  + 64 header + 130 slot + 128 button + three 16 dp gaps is **418 dp of fixed chrome**, against
  the 312 dp of content height a 360 dp panel has. Its target — "at the manifest's minimum this
  screen shows one note" — was unreachable by 58 dp before a single note was drawn.
- **So the declared minimum moved with the frame.** `android:minHeight` is now **560 dp**: the
  fixed chrome plus one whole note row. A declared minimum is a promise to the shell, which will
  let a person resize to it; promising a size at which the product's own list cannot be reached is
  a worse defect than the layout that hides it. `PanelMinimum` carries the two numbers and
  `PanelSizeTest` fails when the manifest and the frame disagree.
- **The status slot scrolls inside itself.** A fixed reservation clips a state that outgrows it,
  and the tallest state — a failure banner plus *Discard the recording* — can. Scrolling inside a
  fixed box keeps both properties: nothing is lost, and the button does not move.
- **Rejected: `verticalScroll` on the outer `Column`.** It gives children infinite height, so a
  `LazyColumn` inside either crashes or stops being lazy — and the record button would scroll
  away, which is the one control the product exists for.
- **Rejected: a snackbar for the transient rows.** It floats, auto-dismisses, and the Undo row
  must be reachable by a ray under `B-18`'s 72 dp floor.
- **The frame is stateless, and that is the load-bearing half of this decision.** `TodayFrame`
  takes `NotesUiState`, a `VoiceState` and a `TodayActions` of lambdas; `TodayScreen` keeps the
  view models and the effects. Both claims above are claims about **geometry**, and geometry was
  the thing this project could not check: the headset has been away since `eaf0c51`, so every
  layout finding since has been an argument rather than a measurement. A stateless frame composes
  under Robolectric at a forced size in about two seconds, and `TodayFrameTest` measures the
  button's bounds across every `VoiceState` and the list's height at the declared minimum.
- **What that harness cannot answer, stated so nobody over-reads it.** Robolectric's text metrics
  do not scale with the forced density — a `titleLarge` line measures the same pixels whatever
  size the panel is told it is — so a row that is ~200 dp on the device measures ~480 dp there.
  That makes the **budget** tests stricter than reality, which is safe; it makes a row-level test
  fail for reasons unrelated to its subject, which is why those run on a deliberately tall canvas.
  Anything about passthrough, aspect, injected input or a `Popup` on a `VirtualDisplay` is still a
  headset question.
- **`B-09` is decided rather than measured, and deliberately.** *Transcribe again* expands the row
  into a `FlowRow` of chips instead of opening a `DropdownMenu`. A `DropdownMenu` is a `Popup` — a
  second window through `WindowManager` on a `VirtualDisplay` created without `SUPPORTS_TOUCH` or
  `TRUSTED` — and whether it draws, and whether it can be dismissed, is unverified. This control is
  the whole of `SCN-005`'s recovery path; it does not rest on an unproven widget while the device
  is away. There are now **no `Popup`s anywhere in the product**, so the rule is cheap to hold.
- **Consequences / affects:** `docs/modules/app.md`, `docs/ux/screens.md`, `app/src/main/AndroidManifest.xml`,
  `core-common/.../theme/Tokens.kt`, a new `core-common/.../ui/Controls.kt`. Closes `B-05`,
  `B-09`, `B-10`, `B-11`, `B-12`, `B-13`, `B-14`, `B-18` (at the shared controls and the banner),
  `B-20` and `B-25`. **`B-08` stays open** — the aspect measurement is `T-015`'s and needs a
  headset — and no spacing here was tuned against a build without it.
- **Source:** run `2026-09-20-v2` task `T-030`

### DEC-0044 — The first run asks, records and confirms; nothing is a state the person must decode

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** the audit's *First run on a fresh headset* walk (`docs/evidence/audits/2026-09-20-v2/D.md`,
  the unnumbered section, **not** `D-05`, which is the *Retry* button that deletes the recording —
  a different High finding, closed by `T-005`; the mis-citation came from the plan's own row and
  is corrected by `DEC-0046`) traced a clean headset tap by tap: **six taps and a 190 MB download** to a
  first dictation, where the contract (`SCN-013`) names three. Two were pure waste. Pressing
  *Record* with no permission set `VoiceState.NeedsPermission` and the button **relabelled itself
  to *Allow the microphone* in the place just pressed** — so the press that reached the system was
  the second. Granting then parked the machine in `VoiceState.Allowed`, which no screen rendered:
  the warning vanished, the button silently said *Record* again, and the person pressed it a third
  time to find out what had happened. D's own summary is the finding: *"the route is discovered by
  pressing the same button five times and watching what it says."*
- **The count in this decision is wrong and `DEC-0046` corrects it: the first run is FOUR
  presses to a saved note, not three.** Every mechanism below is right; the arithmetic on top of
  them was not, in this record and in five other documents.
- **Decision, in four parts:**
  1. **`start()` asks for the permission itself**, through a `Channel` the hosting screen collects
     — `permissionAsks`. The OS dialog therefore appears on the **first** press.
  2. **Granting records.** `onPermissionResult(granted = true)` calls `start()`.
  3. **`VoiceState.Allowed` is deleted.** `B-05` offered "handle it or delete it" and there is
     nothing left for it to mean. Deleting it is also what keeps `RecordStatus`'s `when`
     exhaustive without an `else` (`DEC-0043`), so the next state cannot be dropped the same way.
  4. **`NeedsPermission` survives as the state after a *refusal***, which is the one moment an
     explanation earns its room, and it carries two controls: *Ask again* and *Write a note
     instead*.
- **An event rather than a call, and the reason is the Space.** `ImmersiveActivity` is **not** a
  `ComponentActivity`, so `rememberLauncherForActivityResult` cannot resolve a registry through
  the panel's view tree and the request belongs to whichever activity is hosting. The view model
  can only say *when*. `Channel(CONFLATED)` rather than a `SharedFlow`: a replayed state would put
  a system dialog in front of somebody who returned to the screen without pressing anything, and
  two presses arriving before the collector runs are one dialog.
  **Both hosts collect it** — Today and the note editor — because *Record into this note* is the
  same first press from a different screen, and leaving the editor out would have made a first
  dictation started there do nothing at all.
- **The cost is said where it can first be known.** `AppError.ModelMissing` carries the model and
  its size; the mapper renders them. The banner was the first place in the session where the
  price was knowable and, until now, the last — a person pressed *Download* and learned it was
  190 MB from the progress bar. Zero bytes falls back to the sentence without a size rather than
  announcing "0 MB". `action_download_model` stops hard-coding "(190 MB)", which was wrong for
  four of the five models.
- **A refusal that cannot be repaired here offers the way round it.** `UiAction.WRITE_NOTE` and
  `UiMessage.secondary`, rendered beside the repair and never instead of it. Two actions at most:
  in a headset a row of four targets is a row a controller ray cannot separate. The offer is
  honest for the first time since `DEC-0010`, because `T-028` put the text editor back.
- **The download says it arrived.** `DownloadProgress.Done` became `VoiceState.Idle` and the bar
  simply vanished, so four minutes of waiting ended in an absence. One event, rendered in the same
  transient place as *Saved, and copied*. The estimate beside *Cancel* is deliberately vague —
  the app measures no bandwidth, and an invented minute count is a number it cannot keep.
- **Two empty states, chosen by whether the model is present — not by a first-run flag.** The long
  one is *about* the download, so the thing it keys on is whether one is still owed; a flag in
  `SecureSettings` would go stale the first time somebody removed a model in Settings.
- **Transcription counts seconds, and the two things it still does not do are named.** At the
  measured 1.31× real time a forty-second thought costs about fifty-two seconds of nothing moving.
  A real progress fraction needs whisper.cpp to report one through the JNI bridge (`B-146`) and a
  cancel needs a cancellation path through `transcribe` (`B-147`); both are findings rather than
  half-built inside a first-run task.
- **Rejected: an onboarding screen, a carousel, a rationale dialog before the system prompt, a
  "skip and set up later".** The product's shape is one screen, and the fastest first dictation is
  the one where nothing stands between the person and the button. Every sentence above renders in
  a place that already existed.
- **What this decision cannot claim.** The tap count is the point of that walk and **nobody has
  counted it on a device** — the headset has been offline since `eaf0c51`. Every step is asserted
  and the arithmetic says three, but three-by-construction is not three-by-observation, and the
  second half of the spec's check — a person who has not read the spec walking the same route —
  has no substitute at all. `B-148` carries both.
- **`G-17`'s dead-string list is corrected: four, not five.** `action_new_note` gained a caller in
  `T-028` and deleting it would silently re-open `A-01`. `state_allowed` is now unreachable too
  and joins the list; the removals are `T-032`'s.
- **Consequences / affects:** `docs/modules/app.md`, `docs/ux/scenarios.md`, `docs/ux/screens.md`,
  `core-common/.../AppError.kt`, `UiMessage.kt`, `UiStateMapper.kt`, `ui/Components.kt`. Closes
  the first-run walk's reachable half and `G-17`'s reachability half.
- **Source:** run `2026-09-20-v2` task `T-031`

### DEC-0045 — A string is true of this build, and a behaviour claim names the code that makes it true

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** the first sentence the product said was wrong twice. `today_empty` read *"Nothing
  here yet. **Hold Record** and say something, **or start a note**."* — the hold gesture was
  deleted by `DEC-0010` and the button toggles, and there was no control that started a note
  (`A-01`) until `T-028`. In English an operator shrugs; in the `values-ru` that `G-09` wants, a
  translator would have to invent a gesture that does not exist, in the app's primary language.
  Behind it, **26 declared strings were referenced by nothing** (`B-26`) — and the reason splits
  two ways that must not be confused: some described features that were **deleted**, some
  described features that **exist and were never wired up**.
- **Decision, in three parts:**
  1. **The unreferenced half is Android lint, promoted to an error** — `lint { error +=
     "UnusedResources" }`. Not a script: lint already resolves `@string/` from the manifest and
     `R.plurals.` from Kotlin, which is where a naive `grep R.string.NAME` got **three of
     twenty-seven** answers wrong when this was measured. It had been reporting all 22 of the
     app's dead strings the whole time, as warnings nobody read.
  2. **`scripts/check-strings.sh` covers the two things lint will not.** A key declared in two
     modules — `action_allow` was in both, `UiStateMapper` resolves the core one, and a duplicate
     where *both* are referenced is invisible to lint and drifts into two spellings. And a
     `tools:ignore="UnusedResources"` that names no board row.
  3. **A string that makes a claim about behaviour carries a comment naming the code that makes
     it true** — `true because: TodayScreen.kt RecordStatus — one press starts, the next stops`.
     Only behaviour-claiming strings; a label like `action_copy` carries nothing. Changing the
     named file means re-reading the strings that point at it.
- **`today_empty` was never *unreferenced*.** It was rendered, on the first screen, every launch,
  saying two false things. **No mechanical check would have caught it**, and pretending otherwise
  is the failure this decision's third part exists to avoid. `strings.xml`'s header already
  carried a rule about *location* — "Kotlin holds none of them" — and it held; what was missing
  was a rule about *truth*.
- **There is no suppression list, and the exemptions have receipts.** A list is how twenty-six
  accumulated. Three strings are kept alive on purpose — `action_try_again` (`B-33`:
  `NothingHeard` offers no action) and `stt_source_local` / `stt_source_remote` (`B-21`:
  `NoteEditorScreen` renders the literal `local_fallback` while these sit unused). **Deleting
  them would delete the evidence of two unfixed defects**, which is worse than the sweep not
  happening. Each carries a per-string `tools:ignore` and a comment naming its board row, and
  `check-strings.sh` fails when one does not — so the exemption disappears when the defect is
  fixed, rather than outliving it.
- **Two findings corrected.** `B-26` lists `action_clear` and `action_clear_query` as duplicates;
  they are **two different actions** — clearing a stored API key and clearing a search field —
  and merging them would put one word on a destructive control and a trivial one. And
  `action_new_note` appears in both `B-26`'s list and `G-17`'s five-to-delete: it **gained a
  caller in `T-028`** and deleting it would silently re-open `A-01`, a Blocker. G-17's list of
  five is a list of four.
- **`retranscribe(language)` went with its string.** The rule is delete both or neither: a string
  deleted while its caller-less method survives leaves something the next sweep reads as
  reachable. The feature is not gone — `NotesViewModel.retranscribe(note, provider, model)` is
  reachable from every row.
- **`D-24`: the mirror failure reaches Today, once per episode.** `SCN-011` says the failure is
  shown *where it happened*; the out-of-sync count lived in Settings alone, so a person whose
  notes had stopped reaching their files — which, after `T-023`, is also when their export stops
  being complete — had no reason to find out. **Correcting the scenario to match the build was
  the other option and is the one this rule exists to refuse.**
- **`F-14`: the coverage claim is structural now.** `verification.md`'s `REQ-009` said "every one
  of the 17 `AppError` shapes"; there are **eleven** shapes and seventeen *cases*, and two shapes
  had no case at all. `AppError` is `sealed`, so `sealedSubclasses - covered` is exhaustive by
  construction and a twelfth shape fails the test instead of quietly widening the gap. It runs on
  the JVM, where it executes on every build, **and** in the instrumented list the ledger row is
  actually about.
- **Consequences / affects:** `app/src/main/res/values/strings.xml`, `core-common/.../strings.xml`,
  `app/build.gradle.kts`, `scripts/check-strings.sh`, `scripts/check-all.sh`, `README.md`,
  `docs/DOCMAP.md`, `docs/evidence/verification.md`. Closes `B-26`, `D-24`, `F-14`, `B-19` and
  the string half of `G-17`.
- **Source:** run `2026-09-20-v2` task `T-032`

### DEC-0046 — What step 8's own verification found, and the four claims it had to correct

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** step 8 (`T-028`…`T-032`) shipped with 342 JVM tests, a clean lint and six green
  gates, and was then read by three independent tiers — unit, seam and product. Every check the
  run had been asked to make was green; **ten defects sat in what the green does not reach**, four
  of them in claims the run had written down as true. This decision is their home, because a
  correction carried only in a commit message is a correction nobody re-reads.
- **Decision 1 — today's note is drawn once, as the day card.** `loadDailyNote()` **creates** the
  row (`getOrCreateByDay` inserts) and `observeAll` is an unfiltered `SELECT *`, so after `T-028`
  added the card the same note appeared twice — and `state.notes` was never empty from the first
  launch onward, which made `T-031`'s two empty states, the product's only onboarding,
  **unreachable**. The list now excludes it. **Under a tag filter the card is not drawn and the
  list stops excluding**, because the card shows today unfiltered, which is not what the person
  asked for, and a matching note must not simply be missing.
- **Decision 2 — the first run is FOUR presses, and the earlier number was wrong.**
  `DEC-0044` said three. Walked from the code: *Record* (the dialog, allow, and then the missing
  model refuses before any recording), *Download*, *Record*, *Stop* — four, plus the OS dialog's
  own button. `D.md`'s baseline of six counted *Stop* as tap 6, so the honest delta is **six to
  four**, which is also what `DEC-0044`'s own arithmetic says: "six taps … two were pure waste".
  Three was in six documents, one of them a verification row marked `pass`. **The boundary is
  stated with the number wherever it appears** — *to a saved note* — because `B-148`'s device walk
  would otherwise confirm a count nobody had defined.
- **Decision 3 — a library module's unused strings are the script's, not lint's.** `DEC-0045`
  promoted `UnusedResources` to an error on `:app`, which left the module that owns the **error
  vocabulary** covered by nothing. Adding the same block to a library is **inert** — measured by
  planting `zz_probe_core` in `core-common` and watching both `:core-common:lintDebug
  --rerun-tasks` and `:app:lintDebug` print BUILD SUCCESSFUL. That is deliberate upstream: a
  library's resources are part of its API. In *this* repository there is no consumer outside the
  tree, so `check-strings.sh` does a repository-wide reference scan for library modules — accurate
  here in a way it would not be for a published library, and accurate about the three forms a
  naive grep misses (`@string/` in a manifest, `R.plurals.`, a cross-module duplicate).
- **Decision 4 — the mirror banner retries the mirror.** `SCN-011` says the failure is shown once
  **with Retry**; the first version offered *Settings*, a different control from the one the
  scenario names, inside a change whose own record says correcting the scenario to match the build
  is what it refuses. `VaultMirror.retryFailed()` had existed the whole time. And the episode is
  **held** rather than dropped when another message is on screen: the guard said "the next
  emission tries again", and a `StateFlow` re-emitting an equal value emits **nothing**, so an
  episode arriving behind any other message was lost for the life of the process — `D-24`
  reinstated by the change that closed it.
- **Also corrected, each with a test that was watched failing:** the first-run sentence hard-coded
  190 MB (wrong for four of five models, one string away from the defect `T-031` had just fixed);
  `VoiceViewModel` cached the model's presence in `init` beside a comment claiming a cached flag
  "would go stale the first time somebody removed a model in Settings"; the notes banner's
  `else -> retry()` swallowed `DOWNLOAD_MODEL`, so *Download* dismissed itself and reloaded the
  list; the cloud **Save** button fired three independent writes, so the terminal `lastSaved` was
  `OTHER` and a seventy-character secret stayed in a `rememberSaveable` field — `B-12` fixed for
  chip-taps and broken for the button it was about; `PanelSizeTest` hard-coded every number, so
  raising `statusSlot` to 260 dp left it green; and the elapsed counter was per-branch, so a
  `Transcribing → PreparingEngine` walk restarted it from zero.
- **The note editor got a voice surface** (`B-083`). It rendered no `Failed`, `Downloading`,
  `NothingHeard` or `PreparingEngine` at all, and step 8 made that worse rather than better: since
  `DEC-0044` a press asks the system directly, so a **permanently** refused microphone became an
  instant denial with nothing on screen — press, nothing, press, nothing. Every `ErrorBanner` call
  site now routes `UiAction` **exhaustively with no `else`**, so a new action cannot land silently
  on any screen.
- **What `560 dp` buys, stated exactly.** The fixed chrome plus the **shortest** a note row can
  be — its Copy button and the row's own padding. A row with a title, a time and an action button
  is taller, and at the minimum a person scrolls to reach the rest of it; `TodayFrameTest`
  measures reachability, which is what `REQ-019` claims. `PanelSizeTest` derives the sum from
  `Tokens.Space` now instead of typing it, and the `appSettingsUnavailable` line moved into the
  list, so "three fixed things" is true rather than four.
- **The lesson, and it is the one worth keeping.** Every gate was green and every gate stayed
  green through all ten defects. Three of the four corrected claims were **written in the same
  change that made them false** — a comment predicting the exact staleness it then shipped, an
  arithmetic argument that omitted its own subject, a guard whose KDoc described a retry that
  could not happen. Prose about behaviour is not evidence, and a run that writes its own
  certificate should expect to be read by somebody who did not.
- **Consequences / affects:** `docs/ux/scenarios.md`, `docs/ux/screens.md`, `docs/modules/app.md`,
  `docs/modules/core-common.md`, `docs/evidence/backlog.md`, `docs/evidence/verification.md`,
  `docs/evidence/plans/2026-09-20-v2-plan.md`, `docs/DOCMAP.md`. Corrects `DEC-0043`, `DEC-0044`
  and `DEC-0045` without superseding them. Closes `B-083`.
- **Source:** run `2026-09-20-v2`, the group verification of step 8

### DEC-0047 — What leaves the device is said on the screen that sends it, and the clipboard can be refused

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `G-02`. Two things leave the headset or the app, and each happened silently at the
  moment it happened. **Speech** is disclosed by three careful strings — `provider_local_note`,
  `provider_cloud_note`, `provider_server_note` — and all three live in Settings, **beside the
  choice**: somebody who picked *Cloud service* in July pressed Record in September with nothing
  on the screen that sends their voice. The choice was disclosed; the act was not. **The
  transcript** went to the system clipboard on every dictation, unconditionally — `DEC-0012`
  accepted that deliberately and its own consequences line says it plainly: *"the app overwrites a
  cross-app resource the person did not offer, on every dictation, and the only notice disappears
  after a few seconds."* The app was careful about the voice and silent about the text, which is
  the wrong way round: the text is the distilled version.
- **Decision 1 — Today names the destination whenever speech is not local.** One line,
  `state_recording_leaves` with the provider's own label, in `Tokens.Palette.warn`. **Nothing at
  all when it is `LOCAL`**: an app that announces "nothing is leaving" every time trains people to
  stop reading the line, and then it is not there when it matters.
- **Where it sits, and why not where the spec said.** `T-025` asked for it *below* the button,
  because `B-13` was that everything above the button moved the button. `DEC-0043` fixed that, and
  `DEC-0046` then made "three fixed things and one list" a rule — so it is the **first item of the
  list**, directly under the button. It is on screen without scrolling and costs the fixed budget
  nothing.
- **Decision 2 — the clipboard copy is a setting, default on.** `DEC-0012` **stands**: in a
  headset the point of speaking is usually to paste somewhere else, and taking the default away
  costs the product its best moment. What was missing is the ability to say no. One key,
  `KEY_COPY_TRANSCRIPT`, absent meaning on, so an upgrading person keeps what they have.
  **The confirmation stops claiming a copy that did not happen** — `state_saved` rather than
  *"Saved, and copied. Paste it anywhere."* A false confirmation is worse than none.
- **Read once into state, not at the moment of the copy.** It is a Keystore decrypt, and the path
  between "the words are ready" and "they are on the clipboard" is the one place a stall is
  unforgivable.
- **Nothing here asks the person to accept anything.** No consent flow, no dialog, no first-run
  privacy screen. This is a two-person app on two sideloaded headsets; an accept button would be
  theatre, and worse, it is the kind of thing that makes a person click past the one sentence that
  mattered. A first-run screen would also fight `T-031` directly, whose whole job is to reach a
  first dictation without reading a document. What is owed is that **the truth is visible at the
  place and moment it applies**.
- **Two thirds of this task were deleted rather than written**, and the spec says so: `DEC-0020`
  cut the assistant, so `G-03`'s OpenRouter disclosure — the largest part — was closed by removal.
  Two of the three channels that once left the device are gone.
- **A defect this task's own test found, in this task's own code.** Routing
  `onPermissionResult(granted)` back through `start()` re-tests the permission the system has just
  answered. On a device whose check lags the grant that is ask → grant → ask → grant with no exit;
  the first shell test met it as an `OutOfMemoryError`. `onPermissionResult` reaches
  `beginRecording()` now, which never asks, and a bounded test — a fake that grants once and
  refuses afterwards — counts the asks instead of hanging.
- **And a flaky suite, which is worse than a red one.** `refresh()` reads four settings in one
  coroutine; two of the four were added here with `Graph` defaults, and the tests that had not
  been given them threw inside `init`. The exception escaped `viewModelScope` and failed
  **whichever test happened to be running** — five runs, four different cases. Each read keeps its
  own answer now (`C-04`'s lesson in a new place: one unreadable setting is not a reason to lose
  the others), and every construction site passes the seams.
- **`B-156` closed with it.** `TodayScreenShellTest` composes the **stateful** `TodayScreen` under
  Robolectric with real view models over fakes, and measures the wiring `TodayFrameTest` cannot
  reach: the `permissionAsks` collector, the clipboard condition, the provider line. Three of the
  ten defects step 8's verification found lived in that shell, and nothing that runs had ever
  touched it.
- **Consequences / affects:** `docs/ux/scenarios.md`, `docs/ux/screens.md`, `docs/modules/app.md`,
  `core-common/.../SecureSettings.kt`. Closes `G-02`'s disclosure half — the storage half is
  `DEC-0038` — and `B-156`. `G-03` was closed by removal at `T-033`, not by disclosure.
- **Source:** run `2026-09-20-v2` task `T-025`

### DEC-0048 — The build stamps its own identity from git, and the installer refuses to guess

- **Date:** 2026-09-21
- **Status:** Accepted · **Refined by DEC-0083** — the count of commits is a property of the branch, not of the commit
- **Context:** `H-31` and `H-30` are one question from two ends — *what is on the second headset?* — and
  neither end could answer it. The version came from `-PversionCode=42`, with a comment explaining
  that a release could then be rebuilt from the same commit without editing a tracked file; **CI
  was the only caller and local builds never passed it**, so every APK this project has produced
  reads `Fabric VR 0.1.0 (1)`, on today's build and on the one from two days ago. `dumpsys` gives
  the same `1`, so the platform could not answer either. And `install-on-quest.sh` took the first
  line of `adb devices`: with both headsets reachable it installed on an arbitrary one, printed
  `connected.`, started the activity and exited 0, naming nothing.
- **Decision 1 — four fields, stamped from git at configuration time.** `versionCode` from
  `git rev-list --count HEAD` (monotonic, needs no flag, reproducible from a commit); `versionName`
  as `0.1.0+<short sha>`; `GIT_BRANCH`, because `main` and the working branch are a real
  distinction here; and `BUILD_TIME` from **the commit's** timestamp, because what a reader wants
  is how old the *code* is — a wall clock would make every build non-reproducible and invalidate
  the configuration cache on every invocation for no answer anybody asked.
- **`providers.exec`, not `"git …".execute()`.** The latter breaks the configuration cache, which
  this project should not give up for four strings. Verified: `--configuration-cache` stores an
  entry with no problem reported.
- **Every fallback is a word, not a blank.** A source tarball, a shallow clone or a machine with no
  `git` still builds, and the line reads `unknown` out loud. Verified by building with `GIT_DIR`
  pointed at nothing: `0.1.0+unknown`, `GIT_BRANCH = "unknown"`. A version line that silently omits
  its provenance reads as an answer without being one, which is exactly the defect it replaces.
- **Decision 2 — one sequence, and CI stopped passing the other.** `-PversionCode` still wins
  where it is given, as an escape hatch, but the workflow no longer passes `github.run_number`. Two
  monotonic sequences over one field cannot be ordered against each other: a release built at run
  50 and a local build at commit 120 are not comparable, and Android refuses a downgrade install.
- **Decision 3 — the installer names the headset and asserts what it wrote.** It refuses to choose
  between two connected devices and prints both; it prints `ro.serialno`, `ro.product.model` and
  the installed `versionCode` before and after; and it **exits non-zero** when the after-value is
  not the one in the APK. The expected value comes from `output-metadata.json`, which Gradle
  writes beside the APK — not from `aapt2`, which is not on every PATH, and not from `git`, which
  describes the tree rather than the artefact. `push-model-for-tests.sh` gets the same refusal.
  **The fallback address stays and now says so**, so "no headset is connected" is distinguishable
  from "the right one is".
- **`scripts/check-installer.sh` runs the real script against a fake `adb`.** The script already
  took `ADB` from the environment, so nothing exists in production for testing only. Three cases,
  and the third is the canary: without a case proving a *correct* install still succeeds, a script
  that exited non-zero for any reason would pass the other two. Both defects were planted and
  watched failing — the first-device pick, and the assertion softened into a `note:`.
- **The hazard this creates, named rather than discovered.** `git rev-list --count` **decreases**
  when history is rewritten or when a build comes from a branch behind another, and Android
  refuses a downgrade install. That is correct behaviour and it is why the branch is in the
  version line — but it will bite the first time somebody builds from `main`, which carries no
  project commits (`H-36`), and tries to install over a `feat/v1-notes-core` build. `B-160`.
- **Consequences / affects:** `app/build.gradle.kts`, `SettingsViewModel.kt`, `SettingsScreen.kt`,
  one string, `scripts/install-on-quest.sh`, `scripts/push-model-for-tests.sh`,
  `scripts/check-installer.sh`, `scripts/check-all.sh`, `.github/workflows/ci.yml`, `README.md`,
  `docs/DOCMAP.md`. Closes `H-30` and `H-31`.
- **Source:** run `2026-09-20-v2` task `T-038`

### DEC-0049 — The signing key lives outside the repository, with a custodian, two backups and named passwords

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `H-34`. The Android signing key is the only artefact in this project with **no
  recovery path**: once a signed build is on a headset, losing the key means every future upgrade
  is an `adb uninstall` that takes `filesDir` with it — the notes, the whole vault, every
  recording, the 190 MB model and every Keystore value — permanently, for everyone who has the
  app. The entire written guidance on it was six lines naming a file. Nothing said how to create
  the keystore, where it lives, who holds it, or how it is backed up, and nothing verified any of
  it. **No release keystore existed yet**, which is exactly why this was the moment to decide:
  nothing is lost, and a `.keystore` on one laptop with no backup is the default outcome of doing
  nothing.
- **Decision 1 — the keystore lives outside the worktree, at an absolute path.** The sample said
  *"paths are relative to the repository root"* and suggested `release.keystore`, and
  `app/build.gradle.kts` resolved it with `rootProject.file(...)` — so the shortest route from the
  sample to a working build put the project's one unrecoverable secret **inside the repository**,
  in a directory `.gitignore` did not cover. The build now takes an absolute path where one is
  given. *Rejected: `~/.android/`, beside the debug keystore* — convenient, and exactly where a
  person looks when cleaning up Android tooling. A key with no recovery path does not live in a
  cache directory.
- **Decision 2 — one named custodian, two copies, at least one off this laptop.** A second
  custodian with no second person who can reach the backup is a name in a document. The backup
  covers **three artefacts together**, because any one alone is useless: the `.keystore`, the two
  passwords and the alias, and `release-lineage.bin` once `T-037` creates it.
- **Decision 3 — the passwords are names, not file contents.** `keystore.properties` holds
  `storeFile` and `keyAlias` only; the build reads the file first and
  `FABRICVR_KEYSTORE_PASSWORD` / `FABRICVR_KEY_PASSWORD` from the environment second, supplied by
  `use_secret.py run`, which removes the values from everything the child prints. *Rejected:
  passwords in `keystore.properties`* — it works, and it puts two irreplaceable values in the file
  most likely to be opened, screenshotted or pasted while debugging a signing error. *Rejected:
  `~/.gradle/gradle.properties`* — the same plaintext problem with a blast radius of every project
  on the machine. *Rejected: prompting* — it breaks CI and any unattended release.
- **Decision 4 — the gate closed first, before there was a key to leak.** `.gitignore` and
  `scripts/check-secrets.sh` §2b refuse tracked `*.keystore`, `*.jks`, `*.p12`, `*.pepk` and
  `release-lineage.bin` — **by pattern**, because the file name is the one thing a person picks
  freely — and the new branch was watched refusing a planted `planted-release.keystore`. Its
  canary enumerates a planted file in a temp repository rather than testing the pattern alone: a
  pathspec that matches nothing prints exactly what a clean tree prints, which is the shape that
  made the destructive-command gate inert for a week.
- **Two numbers that the specs conflated, measured and separated.** `T-039`'s *what NOT to touch*
  calls the debug keystore "byte-identical … verified at SHA-256 `44f3efe6…83fd`". That value is
  the **certificate** digest — what `apksigner verify --print-certs` prints, confirmed with
  `keytool` on 2026-09-21 — and not a hash of the file, which is
  `b592817d…307e`. Both belong in the record and they check different things: the certificate
  digest proves the *key*, the file hash proves the *file* was not swapped or regenerated.
- **What this decision cannot do, and it is said in the runbook rather than hidden.** Creating the
  keystore needs a **person**: a password chosen, typed or generated inside an agent session has
  already left the vault, because a session transcript outlives the key — this estate has measured
  its own credential values sitting in a local agent-memory database for exactly that reason. Five
  human steps are isolated in one block at the end of `docs/deployment/signing.md`, and every
  field the record cannot yet fill says *not yet created* instead of being left blank or guessed.
  **`T-037` must not start until that block is done.**
- **Consequences / affects:** `.gitignore`, `scripts/check-secrets.sh`, `keystore.properties.sample`,
  `app/build.gradle.kts`, `README.md`, `docs/deployment/signing.md` (new), `T-037`. Closes `H-34`.
- **Source:** run `2026-09-20-v2` task `T-039`

### DEC-0050 — Dependencies are verified by SHA-256 against committed metadata; locking is deliberately not used

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `H-13`, `C-19`. Every clean build downloaded **685 components** from
  `mavenCentral()` and `google()` and ran them with **no checksum, no signature check and nothing
  pinned beyond a declared version number**. One of them is `meta-spatial-sdk-0.14.0.aar`, a
  47.8 MB proprietary native blob loaded into the same process as the microphone and the Keystore.
  A version is not an identity: `spatialsdk = "0.14.0"` names a coordinate, not a set of bytes. If
  any one artefact were republished or substituted it would enter a build that ships to a second
  person's headset and nothing in the toolchain would notice.
- **Decision:** `gradle/verification-metadata.xml`, generated from a green tree, committed, and
  regenerated deliberately at every version bump. 1 210 SHA-256 checksums over 685 components.
- **Generated with the task set that resolves the real graph.** `--write-verification-metadata
  sha256 help`, which both findings quote, **under-covers badly**: `help` resolves almost nothing,
  so the file misses most of what the build pulls and the first real build fails on hundreds of
  missing entries. The captured command is in `docs/runbooks/dependencies.md` and includes
  `:app:assembleDebugAndroidTest`, because a verification failure that appears only when somebody
  runs the instrumented suite is the worst possible moment to find one.
- **Rejected: dependency locking, as well or instead.** Locking pins *versions*, and the version
  catalog already does that for every first-order dependency; what it adds is transitive version
  pinning, which checksums make moot — a substituted transitive artefact fails verification
  whatever its version. It also writes one lockfile per configuration per module, which over six
  modules with debug and release variants is a great deal of churn for the smaller half of the
  problem. **Verification closes the supply-chain gap; locking closes a reproducibility gap this
  project does not currently have.** If reproducible resolution is later wanted it is its own row.
- **Rejected: PGP signatures instead of checksums, for v1.** Coverage across Maven Central and
  `google()` is uneven, every key must be trusted individually, and the failure mode — *"this
  artefact is unsigned"* — produces a `<trusted-keys>` block nobody reads. SHA-256 answers the
  actual threat: *these bytes and not other bytes.* Adding `pgp` later is additive and invalidates
  nothing.
- **Rejected: vendoring.** 47.8 MB of proprietary AAR plus 685 components in git, for a guarantee
  one file already gives.
- **Rejected: waiting for a real release.** It inverts the order. The metadata must be captured at
  a moment the build is **known-good**, and every day that passes adds a version bump whose
  provenance nobody checked.
- **The IDE's artefacts are trusted by pattern, not by switching verification off.** Android
  Studio downloads `-sources.jar` and `-javadoc.jar` that the command-line build never resolves,
  so they carry no checksum and a sync would fail on them. `<trusted-artifacts>` covers them:
  trusting them is safe in a way that trusting a jar is not, because they are never on a classpath
  and nothing executes from them. **`--dependency-verification lenient` is the right tool while
  generating and nothing else** — a lenient build prints warnings that look exactly like a build
  with no verification at all, which is how a project ends up believing in a mechanism it has
  turned off.
- **Proven by planting.** One byte of `kotlin-stdlib`'s checksum was changed and
  `./gradlew --refresh-dependencies testDebugUnitTest` was watched refusing with *"Dependency
  verification failed for configuration 'classpath'"*, then the file was restored from a copy.
  The project's own retro carries the reason this is not optional: `check-secrets.sh` once printed
  `ok` while reading nothing, and ten planted credentials passed.
- **A nightly job resolves cold.** The warm cache in the ordinary CI job verifies bytes it
  downloaded weeks ago; a republished artefact should be found by a schedule rather than by
  whoever next clears their cache. `cache-disabled: true` and `--refresh-dependencies`, daily.
- **The file is also this project's SBOM** (`H-19`): the only tracked artefact enumerating every
  third-party component the build pulls. Nobody should build a second inventory.
- **What it does not cover, stated so it is not read as having covered it.** `third_party/whisper.cpp`
  is a submodule compiled by CMake, not a Gradle dependency, and no checksum here touches it —
  its integrity is the submodule pin's job, and `.gitmodules` declares `branch = master`, which
  invites `git submodule update --remote`. `B-167`. The Gradle wrapper is already pinned and
  verified by `distributionSha256Sum` (`H-14`) and this task did not touch it.
- **Consequences / affects:** `gradle/verification-metadata.xml` (new),
  `docs/runbooks/dependencies.md` (new), `.github/workflows/ci.yml`. Closes `H-13`, `C-19`, and
  `H-19`'s SBOM half. `C-22`'s repository asymmetry in `dependencyResolutionManagement` becomes
  harmless once bytes are pinned and is not changed here.
- **Source:** run `2026-09-20-v2` task `T-040`

### DEC-0051 — A scenario reads `validated` only when a person walked it, and the word already existed

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `F-36`. `docs/ux/scenarios.md` is declared the source of truth for user-facing
  behaviour, and its `Status` column carried no information: **eleven scenarios read `validated`
  for behaviour nobody had ever walked**, while the two reading `draft` described behaviour that
  had shipped. A column where every row says the same thing is empty; this one was worse, because
  what it said was that a person had confirmed something.
- **Decision:** `validated` means **a person walked it on a headset and it did what the file says**.
  Everything whose code exists and whose automated checks cover it reads `implemented`. The
  separate `Product:` field keeps carrying whether shipping it changed anything for a person, and
  every one of those still reads `unobserved`.
- **`T-042`'s spec proposed inventing a fourth value, `built`, and it already existed.**
  `docs/ux/lint.py`'s `STATUS_ENUMS` has carried `draft | validated | implemented | retired` for
  the `SCN` layer all along; adding `built` would have put the file and its own linter into
  disagreement for no gain. The spec's *intent* — that `validated` stop being free — is what was
  implemented, with the vocabulary the contract already had.
- **Eleven scenarios moved to `implemented` and none reads `validated` today.** That is not a
  regression. It is the first time the column has been true, and it will look worse until somebody
  puts a headset on, which is the point.
- **`SCN-011`'s grade was wrong in the audit and is corrected here rather than in it.** Axis D
  graded *Take the notes as files* as **holding**, on the strength of its first two steps. The
  third — take them off the device — was false: the vault is app-private internal storage with
  `android:allowBackup="false"`, and there was no picker, no `FileProvider` and no share intent in
  the tree, so the scenario's own title named the one thing the build could not do. `T-023`
  (`DEC-0026`) made it true, and the scenario is rewritten against the export rather than against
  the file paths. The axis report is left whole, per the audit's own rule.
- **`FLW-02` was the most misleading document in the set, and that is a category worth naming.**
  It documented hold-to-talk as the design and *tap-to-start / tap-to-stop* — the shipped shape —
  as the **rejected** alternative. A contract document that describes the wrong product is stale;
  one that argues against the right one actively pushes the next reader away from it. Rewritten
  against the build, with `DEC-0010`'s reasoning cited rather than restated, and with the risk
  that reasoning was about answered where it was actually answered: `DEC-0032` bounds a recording
  and transcribes rather than discarding, `T-020` stops one when the screen goes away.
- **`docs/ux/lint.py` gained `[U079]`: a `Wireframe:` pointer resolves or says `none`.** Five
  screens pointed at `wireframes/SCR-0N.md` in a directory that has **never existed**, and the
  gate was green over all five. Watched failing on the live tree before the fix, and again on one
  planted pointer afterwards. A carry-over decided text-only for v1, and `none — text-only for v1`
  is the honest way to write that.
- **What this task deliberately did not touch:** `docs/ux/foundation.md`. `T-042`'s spec forbids
  it, and the reason is good — rewriting the *why* layer to match a build is how a product forgets
  what it was for. But `ST-002` still says *"I want to hold a button and speak"*, which describes
  a gesture `DEC-0010` deleted, and that is the *how* wearing the *why*'s clothes. `B-170` carries
  it rather than this task guessing at the boundary.
- **Consequences / affects:** `docs/ux/scenarios.md`, `docs/ux/flows.md`, `docs/ux/screens.md`,
  `docs/ux/lint.py`. Closes `F-07`, `F-08`, `F-09`, `F-27`, `F-28`, `F-29`, `F-35`, `F-36` and
  axis D's scenario walk.
- **Source:** run `2026-09-20-v2` task `T-042`

### DEC-0052 — The staleness states are computed by a script that exists, and the producer script that never did is dropped

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `F-14`, `F-15`, `F-24`, `B-080`. `docs/evidence/verification.md` answers *"what do
  we actually know is true?"*, and it contained three citations to machinery that does not exist
  in this repository. It defined **four staleness states** — `current`, `behind N`,
  `unresolvable`, `unanchored` — and delegated computing them to a tool it named but never
  shipped, so **the four states had never been computed once** and no row had ever been marked
  `behind` while every row was behind. It also described a **producer block** filled by a script
  (`scripts/graph.py`) that this project has never had. Both citations survived a green
  documentation gate for the life of the file, which is precisely the failure section 12 of that
  gate exists to catch — and section 12 did not catch it because it only resolves paths inside
  `DOCMAP.md`, `docs/modules/` and `README.md`.
- **Decision, in three parts:**
  1. **The staleness tool is written, not deleted.** `scripts/exposure.sh` computes all four
     states from the ledger's own rows and prints one line. It is **printed, never gated**, and
     exits 0 whatever it finds — for the same reason the `Human` column has no target: the moment
     `behind` becomes a number to avoid printing, rows stop being re-observed and start being
     re-worded. It exits non-zero for exactly one reason, that it could not read the ledger,
     because a staleness tool reporting `behind 0` having parsed nothing is the defect it exists
     to close, wearing the answer it exists to give.
  2. **The producer script is dropped and the rule it served is kept.** Four of the block's seven
     fields are harness environment variables this harness does not export; three resolve from the
     tree with one `git` call. A script that printed four `unavailable` lines was not worth
     importing from another project to satisfy a citation. **The valuable half — that a field
     which cannot be resolved says so by name rather than vanishing — is doctrine in the file, not
     a property of a script**, and it survives the removal intact.
  3. **`device` and `jvm` are declared in the Environment vocabulary.** The file said the
     vocabulary is "not invented per row", and six rows already said `device` and fourteen said
     `jvm`. The values are right; the rule they broke is that a vocabulary is extended in the
     vocabulary. Declared, with what each one claims and what it does not.
- **Why it is parsed by field index from the left.** Notes in this ledger contain `|` inside code
  spans. Splitting a row and counting cells from the right drops exactly those rows — silently,
  and in the direction that makes the answer look better. Fields 1–8 are fixed in meaning, so the
  script reads `$2` and `$6` and a note may contain whatever it needs to.
- **What it found on its first real run, and why the number is the point:** `current 0 · behind 39
  · unresolvable 0 · unanchored 0  (of 39 rows, against c650a45)`. Not one row in the ledger was
  observed against the tree that exists. After this task's own appended rows:
  `current 3 · behind 39 · unresolvable 0 · unanchored 1 (of 43 rows)` — all four states now
  occur on real data, which is the only way to know all four branches work.
- **What this task refused to do.** `T-044`'s spec asked for retroactive rows covering the run
  that shipped `4bddfcc`…`421377e` and wrote none. This run declined and wrote `REQ-034` instead,
  which says the gap exists and is permanent. A row is an observation; five rows marked
  *retroactive* would put five claims into a file whose whole argument is that a claim names the
  tree somebody looked at. **The disclosure is the honest artifact and the script is what stops it
  recurring.**
- **The check caught this run, one cell to the left of where it was pointed.** `Auto` is a closed
  vocabulary as well — `pass · partial · none` — and while writing the rows above, this run put
  three values outside it into three new rows, then found a fourth (`pass, with ten corrections`)
  in a row **it had written earlier the same day**, hours before deciding that a vocabulary
  extended by a row is indistinguishable from a typo. Its own new rows were normalised before
  commit; the committed one is corrected by appending `REQ-038`, because the file is append-only
  and that rule does not bend for the author's embarrassment. `B-174` carries extending §16 to
  `Auto` and `Human`. **The lesson worth keeping is not that the slip happened — it is that the
  column with a check has no slips and the column without one had four.**
- **Consequences / affects:** `scripts/exposure.sh` (new), `scripts/check-docs.sh`,
  `.github/workflows/ci.yml`, `docs/evidence/verification.md`. Closes `F-14`, `F-15`, `F-24`.
- **Source:** run `2026-09-20-v2` task `T-044`

### DEC-0053 — A closed board row stays where it is, and three more claims became checkable

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `F-05`, `F-10`, `F-12`, `F-16`, `F-18`, `F-19`, `F-20`, `F-21`, `F-22`, `F-25`,
  `F-26`, `F-30`, `F-31`, `F-32`, `F-37`. The documentation set was reconciled to the tree on
  2026-09-19. Nine commits then reshaped the product and the reconciliation was never repeated;
  three green gates stayed green through all nine. The residue by the time `T-045` swept it: a
  security section naming one of two secrets, a glossary omitting the only speech producer that
  sends audio off the device, a module contract with no entry for the class that sends it, four
  wrong test counts, a rule the board stated and broke 82 times, and a Compose token named after
  a gesture deleted eleven commits earlier.
- **Decision, and the part that was a real choice:** `F-32` offered two coherent states — move
  every closed row into the *Closed* list the header promised, or amend the header. **The rows
  stay.** A closed row's `Home` cell here is frequently a paragraph naming what was done and what
  it cost; a one-line entry in a second list destroys exactly that, and the board is read
  top-to-bottom every loop iteration so the next agent sees what was tried beside what remains.
  Splitting it also splits every id lookup. What survives from the old rule is the half with
  value — **which commit closed it** — and that is now true of all 83 closed rows and checked.
  The *Closed* heading is kept, holding its own retirement notice, so a reader arriving from an
  older document lands on the explanation rather than on nothing.
- **Three claims stopped being decoration**, each watched failing against a planted defect before
  it was believed:
  - **`check-docs.sh` §17 — a stated test count equals the counted one.** `T-003` built §11–13
    and deferred this one *explicitly*, on the sound reasoning that a count check written before
    the counts are corrected seeds a red gate; it named `T-044` as the owner. `T-044`'s spec never
    mentioned it, so `T-044` closed without it and the gap surfaced here — which is the right
    moment, because this is the task that corrects the counts, so check and correction land
    together. Four documents were wrong: `README.md` (365 vs 361), `app.md` (164 vs 161),
    `core-common.md` twice. **Two plants watched**: a drifted number, and the claim deleted
    outright — the escape a naive check reads as "nothing to compare".
  - **§18 — a closed board row names what closed it.** Nine of 83 named nothing. It refused its
    author's own row within a minute of being written, because the reference had gone into the
    `State` column instead of `Home`.
  - **§16** already closed the `Environment` vocabulary (`DEC-0052`); `Auto` and `Human` remain
    open as `B-174`.
- **`holdButtonHeight` is `captureButtonHeight`.** A name is documentation the compiler checks,
  and it was the last piece of this product's documentation still asserting a gesture `DEC-0010`
  deleted. Three call sites, `PanelSizeTest` green after.
- **What the sweep found already fixed, which is the more interesting half.** `F-19` named six
  unrecorded findings; **four were stale** — `PanelSmokeTest` resolves ids now, `ImmersiveLaunchTest`
  passes and was proven non-vacuous on hardware at `662e4ad`, `WhisperEngine.name` derives per
  model, and the digests became `B-173`. They had been fixed with no board row, which is the
  finding `F-19` actually names, still true. `B-176` is filed **closed** so the fact that four of
  six were stale is visible instead of absent; `B-175` carries the one that is genuinely open,
  hand tracking declared and never driven by a hand.
- **What was annotated rather than rewritten:** `DEC-0005` gains a status-line refinement — its
  "two call sites" undercounts, there are three, and since `DEC-0013` the permitted cleartext path
  carries a cloud speech key and the recording to any private address. `DEC-0013` names the edge
  at its own end. **Neither decision clause was edited**, because a register whose entries are
  edited to stay true cannot answer what we believed then.
- **Consequences / affects:** `README.md`, `CONTEXT.md`, `docs/DOCMAP.md`,
  `docs/modules/core-notes.md`, `docs/modules/core-common.md`, `docs/modules/feature-stt.md`,
  `docs/modules/app.md`, `docs/evidence/backlog.md`, `scripts/check-docs.sh`,
  `core-common/.../theme/Tokens.kt`. Closes `F-05`, `F-10`, `F-12`, `F-16`, `F-18`, `F-19`,
  `F-20`, `F-21`, `F-22`, `F-25`, `F-26`, `F-30`, `F-31`, `F-32`, `F-37`.
- **Source:** run `2026-09-20-v2` task `T-045`

### DEC-0054 — The retro's cap is a reading instrument, not a quota

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** axis F's *Retro — instructions to add or prune* section, and carry-over rows 9 and
  10. `T-046`'s spec was written against a `docs/evidence/retro.md` that was **empty** — zero
  standing instructions, zero log entries, zero run stamps, after two full runs, two audits and
  thirty-one commits. It proposed six instructions, two log entries and two stamps. By the time
  the task ran, five instructions had arrived during steps 8 and 11, so the proposal would have
  opened the list at **eleven against its own cap of ten**.
- **Decision — the cap is obeyed, and obeying it is what produced the useful result.** The
  cheap response is to trim the weakest row. What the cap actually forced was a re-reading of all
  six, and two of them did not survive it:
  - **Two were one rule at two scales.** *"A test must be watched failing with the defect
    present, at the level the defect lives"* (`deb604d`) and *"a gate is not a gate until a
    violation has been planted and watched failing it"* (`f7d656d`) differ only in whether the
    subject is a test or a gate. Both incidents are the same failure: **the check ran, read
    nothing, and printed the word that means it read everything.** They are `SI-06`, carrying
    both incidents. A rule split in two is a rule obeyed in one half.
  - **One was born retired.** *"Verify against the build that is installed, not the build that was
    compiled"* names its own retirement condition, and `T-038` had already shipped it:
    `install-on-quest.sh:66-68` refuses when the installed `versionCode` is not the one just
    built. The file's own bar is that a rule a check can decide is written as the check, so it
    became a log entry with its incident intact rather than a tenth row.
  The list stands at **9 of 10**, with one slot of headroom, which the file's own header argues
  for: a list that opens at its cap has no room for what the next run learns.
- **Stamp before prune, and the prune is recorded finding nothing.** The order is load-bearing:
  the cold-retirement trigger reads the stamps stage 10 writes, so a prune run ahead of them can
  only ever run on no data. Four stamps now exist where there was one — `f770049`, `8c5ff9e`,
  `ac4c29e`, `101a6d1` — the two oldest written retroactively and saying so, because stage 10 had
  never run and a stamp that pretends otherwise is worse than a gap.
- **`check-docs.sh` §19 enforces the two rules the file stated about itself and nobody checked:**
  the cap, and that no row exists without a retire-when. Watched failing against a planted row
  with no trigger and a planted eleventh instruction. §9 already resolved the SHAs, so the
  section's three stated rules are now three gates — the same pattern as `DEC-0052`'s §16 and
  `DEC-0053`'s §17 and §18, and for the same reason: **a rule a file states about itself is the
  rule most likely to be broken by the file's own authors**, this run included.
- **Carry-over rows 9 and 10 are resolved against what actually happened**, rather than carried.
  Row 9 waived reviewer subagents for a spend limit; the condition came true — nine blind readings
  and three verification tiers. Row 10 waived per-test TDD because "a planted defect was used
  instead", and **that premise failed**: the plant at `deb604d` is one a test passed over. The row
  is live again, and `SI-06` is the replacement it was promised.
- **Consequences / affects:** `docs/evidence/retro.md`, `scripts/check-docs.sh`,
  `docs/evidence/specs/2026-09-19-v1-notes-core-carryover.md`, `docs/evidence/backlog.md`.
- **Source:** run `2026-09-20-v2` task `T-046`

### DEC-0055 — One handoff is current, every other keeps its body and carries its drift at the top

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `F-01`, `F-02`, `F-03`, `F-04`. `README.md` pointed at a handoff describing a tree
  nine commits old, and the document contradicted itself two sections apart — "nobody has watched
  the app run" at `:8-11` against "the headset returned at 17:40 and the app is running" at `:27`.
  An agent who trusted it would have planned a device session for something that already happened;
  been told to **hold** *Record*, a gesture `DEC-0010` deleted in `732c92b`; re-run an instrumented
  suite against a claim of "13 tests, 0 failures" while two were red for two unrelated reasons; and
  taken "the next agent's task is H-28 and nothing before it" literally, skipping the whole
  2026-09-20 surface. **The destructive branch is the one worth naming**: the likeliest repair for
  a gate that says *hold* is to reinstate the hold, undoing a decision.
- **Decision, in three parts:**
  1. **One current handoff, named from exactly one place.** `README.md` names it and nothing else
     does. Every other file in `docs/handoff/` begins with a `> **SUPERSEDED` banner naming the
     current one and saying in a line what it is kept for.
  2. **A dated handoff is never rewritten — body included.** Not to fix a count, not to fix the
     self-contradiction, not to correct the hold. The banner is the whole remedy. Overwriting it
     destroys the record of what was believed on the day the decisions of that day were made, and
     `B-003` is already a closed row against work later undone: the trail explaining *why the hold
     existed* runs through that document, and a rewrite dead-ends it permanently.
  3. **The walk lives with the ledger it feeds, not inside a handoff.** `docs/evidence/device-gate.md`
     gains nine ordered steps, each naming what counts as a pass, the `REQ` row it answers and —
     for the two that cannot be walked — the task that owns the blockage. A gate names gestures,
     screens and addresses, so it goes stale faster than anything around it; burying it in an
     otherwise stable document means republishing that document every time a button moves, which
     is exactly how `F-02` happened. **The entry point links to it and does not copy it**, and the
     serials stay in one place because `T-036` may yet move them.
- **`check-docs.sh` §20 makes it an exit code**, watched failing against two plants: a stripped
  banner, and a README naming a second handoff. `F-01` happened because nothing noticed a document
  had outlived its build; "someone should have updated the handoff" does not survive a deadline
  and an exit code does.
- **One check in the spec was not written as specified, and the reason is the point.** `T-043`'s
  test table asks for `git grep -in 'hold' docs/evidence/device-gate.md` to be **empty**. Taken
  literally that forbids the gate from *warning* about the deleted gesture, which is the single
  most useful sentence it carries — step 3 says "**Press** *Record* … There is no hold", and the
  header explains what the old gate told people to do. A grep for a word cannot tell a use from a
  warning; the check was replaced by reading, and this paragraph is the record of that judgement.
- **What was already done before this task ran:** the build handoff's banner, added on 2026-09-20.
  `F-01`'s structural half was closed then, and re-doing it would have overwritten a correct fix.
- **Consequences / affects:** `README.md`, `docs/handoff/2026-09-19-research-handoff.md`,
  `docs/handoff/2026-09-20-v2-entry.md`, `docs/evidence/device-gate.md`, `docs/DOCMAP.md`,
  `scripts/check-docs.sh`. Closes `F-01`, `F-02`, `F-03`, `F-04`.
- **Source:** run `2026-09-20-v2` task `T-043`

### DEC-0056 — One table splitter, and every gate is watched failing in the repository

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** step 11's own group verification, three blind tiers. Step 11 wrote five gate
  sections and one script whose entire argument was that a claim must be checkable, and stated in
  three decision records that each had been watched failing against a planted defect. The
  verification found **five of the six could be evaded**, three of them by a defect already
  present in the tree.
- **The single root cause.** Every new parser read a markdown table with `awk -F'|'` and addressed
  cells by index from the left. A cell may contain a pipe two ways — escaped as `\|`, or inside an
  inline code span — and either shifts every cell to its right. What the shift produced was never
  loud:
  - `§18` read `State` as `$10`; two board rows carry a pipe in their `What` cell, so `$10` landed
    on the Prio number, the state match failed, and both rows were **skipped without being
    counted**. A closed row with no reference could have lived there permanently.
  - `§19` read `Retire when` as `$7`; the one standing instruction with an escaped pipe put its
    `Because` prose there, and a row with a genuinely empty retirement trigger passed.
  - `scripts/exposure.sh` read `Observed at` as `$6` and **reported a row `current` that was 20
    commits behind** — the worst of the three, because the summary line still reads as a
    measurement.
  **`exposure.sh`'s own header contains the reasoning that would have prevented all three.** It is
  right, and it protects the *last* cell: a note may contain anything because nothing is read
  after it. It says nothing about the cells to the **left** of the one being read, which is where
  every one of these lived. Being right about one end of the row is what made it feel settled.
- **Decision:** `tools/mdtable.awk` is the one splitter. It understands an escaped pipe and a code
  span, drops the empty fields the leading and trailing pipes produce so index 1 is the first real
  column, and every register parser takes its cells from it **by name, with the row's shape
  asserted** — a row a parser cannot address is reported, never skipped, because a skipped row and
  a clean row print the same line. `check-docs.sh` gained `mdawk`, which concatenates the splitter
  with a program; two `-f` flags is not the alternative, because `awk -f lib.awk '<program>' file`
  treats the program **text** as a **filename**, which is how the first attempt at that helper
  silently read zero rows and printed a clean answer — the same failure one level up.
- **Four more evasions closed in the same pass:** a row written `|SI-10 |` with no space after the
  leading pipe was invisible to both halves of `§19`; `§17` read the raw file and took the first
  match, so a stale number in a fenced block above the real claim satisfied it; `§19` restated its
  own cap as a literal beside a heading that states it, which `SI-01` forbids — it is read from
  the heading now; and `§18`'s commit test accepted any seven-character run of hex letters, which
  includes `defaced`.
- **`scripts/selftest.sh` is the durable half, and it exists because of the sharpest finding of
  the three tiers.** `exposure.sh`'s usage block cited *"its own self-test"* and there was none —
  the `F-15` shape, a citation to an artefact that is not there surviving a green gate,
  **reproduced inside the script written to close `F-15`**. Eighteen cases now plant one defect
  each into a copy of the working tree and assert the gate refuses it, plus two controls asserting
  it accepts a clean one. It is wired into `check-all.sh`. Two of its own early failures are
  recorded in its comments because both are incidents: its control failed while the copy was an
  enumerated subset missing `gradle/`, and eleven cases failed at once while the copy used
  `git ls-files` without `--others`, testing the previous commit rather than the work in hand.
- **The CI break the same verification found:** the `release` and `dependencies` jobs checked out
  shallow, and `app/build.gradle.kts` derives `versionCode` from `git rev-list --count HEAD`, so
  **every CI release APK was stamped `versionCode = 1`** — silently reintroducing the exact defect
  `H-31` removed `-PversionCode` to stop producing. Both jobs get `fetch-depth: 0`, and the build
  now **refuses a shallow clone** unless `-PversionCode` is passed deliberately: a depth-1 clone
  has a git directory and answers every other question correctly, so `1` there is not a fallback
  but a plausible wrong answer. A source tarball has no `.git` at all and still builds, which is
  the case the fallback was written for.
- **Consequences / affects:** `tools/mdtable.awk` (new), `scripts/selftest.sh` (new),
  `scripts/check-docs.sh`, `scripts/exposure.sh`, `scripts/check-all.sh`,
  `.github/workflows/ci.yml`, `app/build.gradle.kts`, `README.md`, `docs/DOCMAP.md`.
- **Source:** run `2026-09-20-v2` step 11 group verification

### DEC-0057 — What the product tier found: five claims under a green gate, and the three rules that now decide them

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** step 11's group verification, product tier. Every finding below sat under
  `ALL GATES GREEN`, and `check-docs.sh:8` disclaims the class by design: it resolves that a path
  exists and a symbol is declared, never whether a **claim** is true or a **range** is the right
  one. They cluster in one place — **the documents that assert their own correctness were the
  ones nobody checked.**
- **`docs/ux/scenarios.md` asserted, four lines under its own Status table, that every scenario
  whose code exists reads `implemented`. Three read `draft`.** `SCN-014`, `SCN-015` and `SCN-016`
  ship, are tested, and one of them — choosing where speech is transcribed — became **step 5 of
  the device walk** `T-043` wrote two commits later: a person would have been instructed to
  perform a scenario this file called *not yet true of any build*. Corrected with each row's
  evidence, and `docs/ux/lint.py` gained **`[U080]`**: a scenario with a non-empty `Coverage:` may
  not read `draft`. The check runs **before** the unfinished-status exemption, because that
  exemption is what made the contradiction invisible rather than merely wrong.
- **A module document asserted the opposite of its own module's test**, and `T-045` wrote it.
  `core-notes.md` said *"a blank query lists what was written recently"* while
  `NotesRepositoryTest.kt:84` asserts `repo.search("   ")` is empty, green. Both statements are
  true at their own level and the bullet had put the `:app` one in `:core-notes`'s contract list.
  Restored, with the boundary stated: a module document states **its module's** contract, and a
  behaviour assembled above it belongs where it is assembled.
- **Three `file:line` ranges did not resolve, in the run whose purpose was making claims
  checkable** — `WhisperModel.kt:104-114` against a file 38 lines long, `feature-stt.md:68-72` for
  a passage at `:148-152`, and `Graph.kt:89-99` for a selection at `:186`/`:192-193`/`:203-237`.
  Two had reached the append-only ledger and are corrected the way `REQ-038` corrected `REQ-026`:
  by appending `REQ-039`, which names both and their true ranges. **`check-docs.sh` §21 now
  decides the half that is arithmetic** — a range whose end exceeds the file's length, or that
  runs backwards — and says nothing about whether the range holds the right content, which needs
  a person. `B-177` carries the rest, and its real answer is probably doctrine: prefer a symbol
  over a line number in prose, and let §13 resolve it.
- **`SI-01` was broken on the front page by the run that restated `SI-01`.** `README.md` said
  *"`exposure.sh` reports 39 of its rows"*; the script said 45 of 47, and the 39 had been copied
  from a sample output block instead of computed — false at the moment it was written. The
  sentence no longer carries a count: the tool prints it on every gate run, and a number in a
  README is wrong the next time a row is appended by someone who will not think to look.
- **`FLW-02` contradicted its own index row inside `T-042`'s change**, and the contradiction was
  load-bearing: the body said *there is no capture sheet* and listed SCR-01 and SCR-02, while the
  index still named SCR-03 and SCR-07 — and `SCR-03`'s retirement entry justified itself by
  *"`FLW-02` still traces here"*. The index kept a dead trace alive so the justification could
  lean on it. Both corrected; `SCR-07` went too, because `DEC-0043` made it a **state of SCR-01**.
  `[U011]` then warned that SCR-03 was an orphan — **so the linter was rewarding the false
  trace**, and it now exempts a `retired` screen, which is what the word means. Watched still
  firing on a live screen.
- **The pattern worth keeping.** Four of these five were written by the three tasks whose stated
  purpose was making claims checkable, inside the same range. A run that is auditing claims is
  writing claims at the highest rate it ever will, under the least scrutiny, with the reviewer's
  attention pointed backwards. **The remedy is not care; it is that a blind tier read the range
  afterwards.**
- **Consequences / affects:** `docs/ux/scenarios.md`, `docs/ux/flows.md`, `docs/ux/screens.md`,
  `docs/ux/lint.py`, `docs/modules/core-notes.md`, `docs/modules/feature-stt.md`,
  `docs/evidence/device-gate.md`, `docs/evidence/verification.md`, `README.md`,
  `scripts/check-docs.sh`, `scripts/selftest.sh`.
- **Source:** run `2026-09-20-v2` step 11 group verification, product tier

### DEC-0058 — An invariant enforced by a caller is not an invariant

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `B-088`, `A-12`. `notes` carries a UNIQUE index on `dayKey` and every write is
  `@Insert(REPLACE)`, so a note claiming a taken day **silently deletes** the note holding it.
  `DEC-0035` decided what should happen — *"a restored daily note gives up its day rather than
  destroying the one that holds it … it keeps every word it had and stops being the note for that
  date"* — and the rule was implemented in **`NotesViewModel.undoDelete()`**. That is one caller.
- **Two other doors were open, and both destroy a person's note:**
  - **`VaultImporter`** writes whatever `day:` a vault file carries, straight into `upsert`. Two
    files claiming one date meant one was destroyed **during the import** — the recovery path,
    the thing that exists for a reinstall, with the file it had just read still sitting on disk.
    The worst moment this defect could have picked.
  - **`MIGRATION_1_2`** deleted every note for a day but the oldest, on the first launch after an
    upgrade. A migration runs **below** the repository, so no `NoteChange.Deleted` is emitted and
    the vault mirror never learns: the row goes, the file stays, and nothing anywhere tells the
    person. It was written against the opposite rule to `DEC-0035`, for the same constraint, in
    the same repository.
- **Decision:** the rule is **`NotesRepository.upsert`'s invariant** — an incoming note whose
  `dayKey` another note already holds is written with `dayKey = null`, keeps every word, and is
  what the change stream announces. `MIGRATION_1_2` sets `dayKey = NULL` instead of deleting, and
  keeps every FTS row because no note is destroyed. `NotesViewModel` no longer carries its own
  copy of the check.
- **Why the migration is the same question and not a different one.** It is the runtime rule asked
  about rows that already exist. The holder keeps the day — the oldest, because it is the holder —
  and every later arrival becomes an ordinary note. Deleting was never about the *day*; it was
  about the row, and the row is the only thing the person cannot get back.
- **Two tests asserted the destruction and were green.** `MigrationTest`'s *two notes for one day
  collapse to the oldest and take their index row* and `NotesRepositoryTest`'s *the fixture did not
  actually evict anything*. Neither was wrong about the code. Both were wrong about the rule, which
  is the more expensive kind of green: a test that encodes a defect makes fixing it look like a
  regression. Rewritten, and each watched failing before the fix.
- **The fakes now model the contract, not the table.** `NotesViewModelTest`'s and
  `VaultImporterTest`'s repositories both modelled the raw `REPLACE`, because that is what the
  database did while the demotion lived a layer above. A fake that models the mechanism instead of
  the promise lets a caller's test pass over a defect the real collaborator would refuse — which
  is exactly how the importer's own suite stayed green across this whole class.
- **What `check-docs.sh` §17 did in the same hour:** caught three test counts this change drifted
  — `README.md`, `core-notes.md`, `feature-vault.md` — the first time the check has fired on
  ordinary work rather than on its own plant.
- **Consequences / affects:** `docs/modules/core-notes.md`, `docs/modules/feature-vault.md`,
  `README.md`. Closes `A-12`, `B-088`.
- **Source:** run `2026-09-20-v2` board row `B-088`

### DEC-0059 — A board row that outlives its fix is a wrong instruction, not clutter

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `B-087` was taken up as the next task and turned out to be **already fixed**, eleven
  commits back, by `78d9cdd`. A sweep of the other open rows then found two more in the same
  state: `B-094` (`I-06`, `VaultMirror._failures` — closed by the *same commit*) and `B-083`
  (`B-11`, the editor's voice failure — closed by `ac4c29e`). Three rows told a reader there was
  work where there was none.
- **Why this is not bookkeeping.** `docs/evidence/backlog.md` says of itself that it is *"the file
  a loop iteration reads at the top and re-prioritises at the bottom"*. A stale open row is
  therefore not clutter — it is **an instruction to do work that is done**, handed to whoever
  reads next, with a priority attached. This run acted on one before checking the tree, which is
  the cost measured rather than argued.
- **Decision:** `check-docs.sh` §22 **prints** every open row citing a finding a shipped decision
  claims to close. **It does not gate**, and that is the design rather than caution: several such
  rows are correctly open because they record the *residue* of a partly-closed finding — "`G-16`'s
  compression half is not closed", "`B-18`'s 72 dp floor reached two surfaces of five". A gate
  there would teach somebody to delete the sentence instead of the defect, which is the failure
  mode every disclosure in this project is shaped to avoid (`DEC-0052`).
- **What it cannot do, said plainly.** It narrows ninety rows to a handful and a person reads
  them. The other end of this class — `F-19`, a fix that ships without touching its row — is not
  decidable by anything: nothing in a diff says which board row it answers. The honest split is
  that the machine finds the candidates and the reader makes the call.
- **A crude version was written first and thrown away.** It pulled every backticked identifier out
  of an open row and asked whether the tree still had it; the output was mostly string resource
  names, C++ symbols, manifest attributes and commit hashes. A sweep that reports mostly noise
  trains its reader to skim, which is the defect this whole session has been documenting — so it
  was replaced by the exact signal rather than shipped with a caveat.
- **Consequences / affects:** `docs/evidence/backlog.md`, `scripts/check-docs.sh`.
  Closes `C-04`, `I-06`, `B-11` by recording that they were already closed.
- **Source:** run `2026-09-20-v2` board row `B-087`

### DEC-0060 — A blocking native call is stopped by a flag it polls, not by a message nobody can deliver

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `B-147`, `B-118`, `B-146` — three findings, one native change, each excluded from an
  earlier task for the same reason. `whisper_full` blocks the thread it runs on, so coroutine
  cancellation could not reach it: *Transcribing…* was a state a person could only **wait out**,
  minutes on a long dictation against a thermally limited headset, and a model switch queued
  behind a running transcription waited exactly as long. Nothing reported progress either, so the
  wait had no shape.
- **Decision — two atomics in the bridge, and no call out of it.** `fabricvr_whisper.cpp` carries
  a per-context `RunState` of two `std::atomic`s; `params.abort_callback` reads one and
  `params.progress_callback` writes the other. **Neither callback touches JNI.** They run on
  whisper's own worker threads, which are not attached to the JVM: attaching one per invocation
  would cost more than the work being cancelled, and a C++ exception crossing the JNI boundary
  calls `std::terminate` and takes the app down instead of failing a note. Kotlin sets the flag
  through `cancel()` and reads progress by asking, which needs no attachment in either direction.
- **The first Kotlin design deadlocked, and the test caught it before the claim was written.**
  The obvious shape is to wrap the call in `withContext(dispatcher)` and register
  `invokeOnCompletion` to cancel. `invokeOnCompletion` fires when a job **completes**, and a job
  whose body is parked inside `whisper_full` has not completed — **the handler that would free the
  thread waits for the thread it would free.** The suite hung for ten minutes. `invokeOnCancellation`
  is the primitive that fires on cancellation, and it needs the caller not to be sitting on the
  thread doing the work: the guards and the context creation run on the engine's thread, the
  blocking call is submitted to that same single-thread executor — so whisper.cpp still never sees
  two calls at once — and the caller waits in a suspension that can be resumed from anywhere.
- **A cancelled run is not a failed one.** `whisper_full` returns the same non-zero for both, so
  `wasCancelled` is what tells them apart, and the difference is the whole of what the person is
  told: *you stopped it* against *your dictation is gone*. It surfaces as an ordinary
  `CancellationException`, so every caller that already handles cancellation keeps working and
  **no new `AppError` shape, string or banner exists** — which also means no new row in the two
  lists `REQ-023` checks.
- **`WhisperNativeCalls` exists so the rules around the native calls can be tested at all.** Every
  one of them sits around `external fun`s that throw `UnsatisfiedLinkError` off a device, so the
  contract added here was unreachable by any JVM test — the same shape and the same reason as
  `KeystoreSecureSettings`'s failure injector. A cancellation path nobody has watched cancel is a
  claim.
- **The fake got a deadline, and that is a finding about fakes.** Written first as an unbounded
  `while (!cancelled)`, it was faithful to `whisper_full` in the one way that mattered and
  unfaithful in another: the real call always returns. A regression therefore spun an executor
  thread for ever and hung **the whole test JVM**, not the case — and `SI-05` already records that
  a case whose failure mode is a timeout is how a suite's red stops being read. With a bound on
  the fake and `runTest(timeout = …)` on every case, the planted defect now produces two named
  failures in a bounded time and the other three cases still pass, which is the discrimination
  that makes the red worth reading.
- **What is done and what is not.** `B-147` and `B-118` are closed. `B-146`'s native and engine
  halves are done — `WhisperEngine.progressPercent()` reads 0..100 — and **the surface that draws
  it is not**: `TodayScreen` is `T-030`'s to own and a second task editing it is how that file
  came to be edited nine times. `B-178` carries the UI half.
- **Consequences / affects:** `docs/modules/feature-stt.md`. Closes `B-147`, `B-118`.
- **Source:** run `2026-09-20-v2` board rows `B-147`, `B-118`, `B-146`

### DEC-0061 — The bridge is compiled before the push, and the gate that proves it was made affordable first

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `DEC-0060` changed `fabricvr_whisper.cpp` and the change was **pushed without ever
  being compiled**. `check-all.sh` excludes the native build deliberately — `externalNativeBuild`
  pulls the NDK and a full arm64 compile of whisper.cpp — and CI's cheap job has no NDK by design,
  so the only compiler of that file is the heavy `release` job, minutes after the push. Noticed by
  asking the question afterwards rather than by anything failing, which is the shape of a gap
  nothing reports.
- **Decision — one file, `-fsyntax-only`, 1.4 seconds.** `scripts/check-native.sh` compiles the
  bridge alone: no linking, no whisper.cpp, no ggml. It cannot say the library **works** — that
  stays the release job's answer and the device's — only that the file somebody just edited is
  still a C++ translation unit, which is exactly what was reaching the remote. Where there is no
  NDK it **reports and passes**, in the same shape as the device-gate check against a missing
  `origin/main`: what it never does is print the word that means it checked. Watched refusing a
  planted syntax error and a planted misspelling of `whisper_full_params.abort_callback`, each
  with the compiler's own message.
- **It needs no JDK, and looking for one was the first version's mistake.** `jni.h` ships in the
  NDK sysroot, which is the correct copy because this file targets Android — and Android Studio's
  bundled JBR has no `include/` directory at all, so the JDK path failed on the machine it was
  written on and reported "not compiled" while looking deliberate.
- **Adding it meant making the gate affordable, which was the larger finding.** `check-docs.sh`
  took **42 seconds**, and `selftest.sh` runs it once per case. Profiled: **section 9 alone was
  15 of those seconds** — `rev-parse --verify` plus `merge-base --is-ancestor` per candidate, 410
  SHAs, 820 process spawns. One `cat-file --batch-check` and one `rev-list HEAD` answer the same
  two questions for the whole corpus. **42 s → 12 s**, and the self-test fell from 5:26 to 3:40
  even with three more cases.
  - Two of this project's own traps appeared inside that change and are recorded where they bit:
    indexing the reachable set by awk's `NR` — which is **global across input files** — left the
    array mostly empty and the gate went red on five true commits; and an apostrophe inside a
    single-quoted awk program closed the string, which is the second time this session.
  - `selftest.sh` got the same treatment: the pristine tree is built **once** and copied with one
    `cp -R` per case instead of 311 `mkdir`+`cp` spawns, and each case runs its gate **once**
    rather than twice for output and exit code separately. `third_party` is symlinked rather than
    skipped, because skipping it made `check-native.sh` report "no whisper.h" and **pass** — a
    self-test case that proves nothing while looking like it passed.
  - The optimisation introduced a bug the first run caught: under `set -e`, an assignment from a
    **failing** command substitution exits the script, and every case here expects a failure, so
    the run died after one line.
- **The cost, stated rather than discovered:** `check-all.sh` now takes about **5 minutes**, and
  roughly 3:40 of that is the self-test running the documentation gate 20 times. That is a real
  price for "every gate is watched failing, in the repository". It is paid here rather than in CI
  alone because this project already fixed the other arrangement once: when a check lived only in
  the workflow, *green locally* and *green in CI* meant different sets, and a commit shipped red.
- **Consequences / affects:** `scripts/check-native.sh` (new), `scripts/check-all.sh`,
  `scripts/check-docs.sh`, `scripts/selftest.sh`, `docs/DOCMAP.md`.
- **Source:** run `2026-09-20-v2`, after `DEC-0060`

### DEC-0062 — What three blind tiers found under `ALL GATES GREEN`, and the three that were the same defect

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** the group verification of `DEC-0058`…`DEC-0061` — four iterations, five commits,
  every gate green. Three tiers read the range independently and returned **twenty-six findings**,
  of which seven were breaks. The unit and seam tiers **reproduced the same three defects from
  different directions**, which is what a blind reading is for.
- **The two that would have reached a person's data, both mine, both in the fix for the defect
  they recreated:**
  - **`MIGRATION_1_2` demoted the wrong note.** It chose the holder by `MIN(rowid)`, on the
    reasoning that rowid is insertion order. It is — until `@Insert(REPLACE)` touches the row,
    which reassigns it, and the editor autosaves every 600 ms. So in any real database **the note
    the person has been typing into all day carries the highest rowid**, and the migration demoted
    exactly that one while a stale duplicate kept the day: the inverse of the rule stated three
    paragraphs above it in the same file. Nothing is destroyed and the wrong note becomes "today".
    Now ordered by `createdAt`, which has been in the table the whole time.
    **The test passed because its fixture inserted each row once** — a state no real database is
    in. One `INSERT OR REPLACE` in the fixture turns it red.
  - **The demotion's read sat outside the transaction that writes.** Two writers claiming one
    fresh day both read *nobody holds it* and the second `REPLACE` destroyed the first: `A-24`,
    reopened by the fix for `A-24`. Measured at **28 of 40 rounds**. It was unreachable while the
    rule lived in one main-dispatcher caller and became reachable the moment it became an
    invariant every door passes through — which was the point of `DEC-0058` and is what made this
    possible. `NoteDao.upsertKeepingOneNotePerDay` puts the read and the write in one
    `@Transaction`; the sibling `getOrCreateByDay` had the shape all along.
- **Two in the engine, and the second is the more interesting:**
  - **`close()` could free the context between the guard and the submit.** Splitting them is what
    made cancellation deliverable, and it opened a window the single `withContext` could not have.
    The state is re-read **inside the submitted task**, on the thread `close()` does its work on,
    so the executor orders them rather than hope.
  - **A closed engine reported `SttFailed` — *your dictation failed* — when the model had
    changed.** That is `I-26` exactly, the incident `close()` was restructured to fix, arriving
    through a door the restructure opened. Both late paths answer `CancellationException` now,
    the same choice the abort path already made. The early guard keeps `SttFailed` **deliberately
    and the distinction is written down**: arriving there means the caller holds an engine closed
    before the call began, which is a caller defect and *failed* is the true word for it.
- **The one that was a false green:** `selftest.sh`'s two native cases demand a non-zero exit, and
  `check-native.sh` exits 0 by design where it cannot run. On a fresh clone without `--recursive`,
  or on any runner without an NDK, the self-test would report *the gate PASSED with the defect
  planted* and fail `check-all.sh` with a message pointing at the wrong thing entirely.
- **The documentation was false in eleven places under the same green**, and two of them are the
  shape this project keeps finding: `B-147` was **closed whole** on a claim about the product —
  *Transcribing… is no longer a state a person can only wait out* — which is true of the engine
  and false of the product, because **nothing offers a stop**: no `VoiceState` member, no string,
  no surface, and `SCN-004` still correctly says the control is unpressable until transcription
  ends. `B-146` was split honestly into `B-178` in the same change and this was not.
  `B-179` and `B-180` carry the surfaces. And **`REQ-040` is anchored to a docs-only commit**
  where its evidence did not exist — `B-116`'s shape, two commits after `REQ-039` was appended to
  this same file to correct citations. Corrected by appending `REQ-041`.
- **Smaller, each real:** `progressPercent()` could never return 100, because whisper computes
  progress at the top of its loop and returns after the last chunk — 100 is stored on success now;
  the three native query entry points used an **inserting** lookup, so `cancel`/`wasCancelled` on
  a freed handle resurrected a map node keyed by a dead address, and `forget_run_state`'s own
  comment about preventing that was false of the function doing it; §9's batching would have
  reported an annotated tag as unreachable, because it prefix-matched the **token** against a set
  of commit ids — it matches the **resolved** id now; `check-native.sh` wrote its error log inside
  the tracked tree next to the source, where `*.err` is not ignored; and its success line named
  `llvm` rather than the NDK version, so the message whose purpose was naming the compiler printed
  a constant.
- **Two risks recorded rather than closed.** `selftest.sh` symlinks `.git` and `third_party`, and
  `cp -R` preserves symlinks — so a plant that ever **writes** through either path mutates the real
  repository. No case does; the two native cases made perl-editing a source the idiom, one
  directory away. The prohibition is in the script's header. And a cancel landing between
  registration and the native entry is still lost (`B-181`): `if (!cont.isActive) return@execute`
  closes the common case, and the complete fix is a run generation in the native state.
- **What the verification cost and what it bought.** Roughly forty minutes of three agents reading
  in parallel, against four iterations that had each run every gate green. The gates were not
  wrong — they check what they check, and `check-docs.sh:8` disclaims prose meaning in its own
  header. **Seven breaks, three of them data-placement defects, none of which any gate in this
  project could have decided.**
- **Two things the fixing itself taught, both now gates.**
  - **`scripts/check-shell.sh`.** An apostrophe inside a single-quoted `awk` program closes the
    string and the rest is parsed as shell. Written **three separate times in one session** —
    always inside a comment explaining something careful — and each time found by running a
    three-minute gate and reading a syntax error from a line that looks like awk. `bash -n` over
    every tracked script answers it in milliseconds. Watched refusing the exact apostrophe.
  - **A plant must prove it planted.** `perl -0pi -e 's/a/b/'` exits 0 when nothing matches, so a
    plant whose target text has been edited since applies silently and the case reports *the gate
    PASSED with the defect planted* — blaming the gate for the plant going stale. It happened the
    first time one of these lines was rewritten. `case_fails` now hashes the tree before and
    after and refuses a plant that changed nothing; a case whose defect is in the **command**
    rather than the tree declares `NOPLANT`, because a silent `true` is indistinguishable from a
    plant that stopped matching.
- **Consequences / affects:** `docs/modules/core-notes.md`, `docs/modules/feature-stt.md`,
  `docs/DOCMAP.md`, `docs/ux/scenarios.md`, `README.md`, `docs/evidence/retro.md`,
  `scripts/check-shell.sh` (new), `scripts/check-native.sh`, `scripts/check-docs.sh`,
  `scripts/selftest.sh`, `scripts/check-all.sh`.
- **Source:** run `2026-09-20-v2`, group verification of `DEC-0058`…`DEC-0061`

### DEC-0063 — A gate that loses a check is caught by counting its verdicts, not its sections

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `B-164`. `git checkout scripts/check-strings.sh`, used to undo a planted defect,
  deleted the whole library-module section. `check-all.sh` stayed green **with one fewer `ok:`
  line**, and a decision record plus two commit messages went on describing a check that was not
  in the tree. Nothing noticed, because a gate with one check fewer prints the same word as a gate
  with all of them.
- **What the group before this closed, and what it did not.** `scripts/selftest.sh` plants a
  defect per section of `check-docs.sh`, so one of *those* vanishing is named rather than missed.
  It says nothing about the other nine scripts — and `check-strings.sh`, the script the incident
  happened to, is one of them.
- **Decision:** a **verdict-line ratchet in `check-all.sh`**, one entry per gate, which may only
  rise. Each number is what that script prints on a clean tree; adding a check raises it in the
  same change, and a number that falls means a check went away.
- **Why not the counter the row asked for.** `B-164` proposed that each script assert its own
  section count. Only `check-docs.sh` has section markers at all — the other nine are structured
  differently — so that shape would have needed a different mechanism per script, threaded through
  eleven files. **The observable the incident actually named is the verdict line**, every script
  has those, and the count lives in the file that already knows the full list of gates rather than
  in eleven places that each know one.
- **What it cannot do, stated:** it says which **script** lost a check, never which check. That is
  `selftest.sh`'s answer, for the sections it covers. Two mechanisms, and the cheap one covers
  everything while the expensive one covers precisely.
- **Watched against the incident itself**, not against a proxy: section 3 of `check-strings.sh`
  — the library-module scan, the one `git checkout` deleted — removed cleanly so the file still
  parses, the script still printed `OK: string scan`, and `check-all.sh` refused the run naming
  `check-strings`. It is a self-test case now.
- **Consequences / affects:** `scripts/check-all.sh`, `scripts/selftest.sh`,
  `docs/evidence/backlog.md`, `docs/DOCMAP.md`.
- **Source:** run `2026-09-20-v2` board row `B-164`

### DEC-0064 — The product is Notion-first, and the virtual-desktop reading is rejected a third time

- **Date:** 2026-09-21
- **Status:** Accepted · **Partially superseded by DEC-0100** — the product is no longer notes-first but a remote surface of Fabric; the notes, the clipboard layer and "never renders a desktop" stand
- **Context:** the project audit of 2026-09-21 (`docs/audit/2026-09-21-audit.md` §1) found the
  product stated two ways: the repository says *notes as a knowledge base beside any desktop,
  never a desktop*, while a session record of the same day described *a native virtual desktop
  to a Mac/Windows host with an AI voice assistant*. Half the board depends on which is true.
- **Decision:** the operator confirmed the repository. Fabric VR is **Notion-first**: notes as a
  knowledge base, plus AI and agents over them, plus a **shared clipboard across Mac, phone and
  Quest** — copy a password on the phone, paste it in the headset — voice input, easy copying and
  sharing — a Notion adapted for VR. It never renders a desktop. The cross-device clipboard is
  the next tool layer after the notes core, not a v1 fix.
- **Consequences / affects:** `docs/product/product-definition.md` (identity confirmed; the
  clipboard named as the next layer), `docs/evidence/backlog.md` (`B-031` re-pointed here),
  `docs/evidence/plans/2026-09-21-v3-plan.md`.
- **Source:** run `2026-09-21-v3` · operator, 2026-09-21

### DEC-0065 — The Horizon OS floor is 81, the version Meta Virtual Display needs

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `B-098`. `AndroidManifest.xml` declared `horizonos:minSdkVersion="69"` while
  `minSdk = 34` made the real floor v76 (every Horizon OS release from v76 is Android 14, earlier
  ones Android 12 — Meta, *Application Manifests for Release Builds*, fetched 2026-09-21). A
  headset on v69–v75 failed with `INSTALL_FAILED_OLDER_SDK`, naming neither the OS nor the fix.
- **Decision:** `uses-horizonos-sdk` **min = target = 81**. The product sits beside Meta Virtual
  Display, and Virtual Display itself needs v81; a headset that cannot run the desktop the panel
  is designed to sit beside gains nothing from installing the panel. `minSdk` stays 34.
- **What it costs:** headsets on v76–v80 are refused at install. They could run the notes; they
  cannot run the product's positioning. Widening later is one attribute.
- **Consequences / affects:** `app/src/main/AndroidManifest.xml`, `docs/modules/app.md`,
  `docs/evidence/backlog.md` (`B-098` closed).
- **Source:** run `2026-09-21-v3` · operator, 2026-09-21

### DEC-0066 — The headset session and the Store both come last, in that order

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** the audit counted 39 of 91 open board rows waiting on a person with a headset, and
  the founding premise (`OQ-0001`) open since day one; both headsets were offline at the audit.
  Store preparation (`B-034`, `B-165`) is roughly a week of non-code work plus review.
- **Decision:** (1) **the headset session is the last step**, not a gate on anything before it —
  every device-only row stays open, marked `deferred: device` in its `Home` cell, and no task
  waits on it; (2) **Store preparation comes after** a **packaged final build that can be handed
  out outside the Store** — signed, versioned, installable over the current debug builds without
  data loss (`B-073`); only then privacy policy, assets, review.
- **Consequences / affects:** `docs/evidence/backlog.md` (device rows tagged), `docs/handoff/`
  (the current handoff's *Human steps* re-ordered), `docs/evidence/plans/2026-09-21-v3-plan.md`.
- **Source:** run `2026-09-21-v3` · operator, 2026-09-21

### DEC-0067 — Hand tracking is declared because the Space uses it, not merely to unblock the launch

- **Date:** 2026-09-21
- **Status:** Accepted · **Refines DEC-0009**
- **Context:** `DEC-0009` records the hand-tracking declaration as a workaround for the shell
  refusing to open the Space with no controllers paired, and says the app does not use hands. Meta,
  *Enable Hand Tracking* (Spatial SDK, fetched 2026-09-21): without `oculus.software.handtracking`
  and `com.oculus.permission.HAND_TRACKING` the runtime does not enumerate hand devices at all, so
  inside the Space hands work as an input device **because** of the declaration.
- **Decision:** the declaration stays and its reason is corrected: the Space is operated by
  controllers **or hands**, and the permission would pass VRC.Quest.Security.2 because the SDK
  uses it. `DEC-0009`'s mechanism (the shell's refusal) remains true; its "without using it" does
  not. `B-175` (nobody has driven the app with bare hands) stays open and is `deferred: device`.
- **Consequences / affects:** `docs/modules/app.md`, `docs/DECISIONS.md` (`DEC-0009` status).
- **Source:** run `2026-09-21-v3` · audit `docs/audit/2026-09-21-audit.md` §4

### DEC-0068 — A dictation belongs to the process, not to the screen that started it

- **Date:** 2026-09-21
- **Status:** Accepted · **Refines DEC-0034**, which is technically wrong as written
- **Context:** `DEC-0034` argues that leaving the Space mid-dictation is safe "because the
  `DisposableEffect` and `ON_STOP` handler both transcribe rather than discard". That is true of
  the CALL and false of its OUTCOME, which is the distinction the audit of 2026-09-21 (H1) turned
  on: the decode ran on `viewModelScope`, `returnToPanel()` ended the host, `onCleared` deleted
  the `.wav` because the state was `Transcribing` rather than `Ready`, and the panel's own
  `VoiceViewModel` was a different object with nothing owed to it. Nothing was waiting on return.
  System Back out of the editor lost a dictation the same way, and nothing intercepted it.
- **Decision:** the transcription and the commit run on `Graph.scope`. The finished transcript is
  written to a **`DictationOutbox`** — `filesDir/outbox/dictation.tsv`, temp file then rename —
  and drained by whichever `NotesViewModel` is alive, under an atomic claim so two surfaces
  observing it write one note. `Graph.init` restores it before the scratch sweep, so a dictation
  survives process death as well as host death. `onCleared` deletes a recording only when nothing
  is owed on it. `BackHandler(enabled = recording)` closes the last unguarded exit.
- **What it trades away, stated:** a dictation started in the editor whose host dies becomes a
  note of its own rather than an append — the outbox knows a transcript, not which note was open.
  `Transcript.fallbackReason` does not survive process death, because an `AppError` is a throwable
  tree with no serialised form; `Transcript.source` keeps the fact that matters (`LOCAL_FALLBACK`).
- **Watched failing:** `transcriptionJob = appScope.launch` reverted to `viewModelScope.launch` —
  `DictationSurvivalTest` red on both cases (`:205`, `:237`), re-planted at integration.
- **Consequences / affects:** `docs/modules/app.md`, `docs/ux/scenarios.md` (`SCN-004`, `SCN-012`),
  `docs/evidence/backlog.md` (`B-121`, `B-086`).
- **Source:** run `2026-09-21-v3` · commit `28c513d`

### DEC-0069 — One retry per thing that can fail

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `UiAction.RETRY` was a single member, so every surface answered it with its own
  idea of a retry — and on Today that was a list reload. `NotesViewModel.retryDictation()` and
  `clearJustDeleted()` had **zero callers** (audit H2, H3): *Retry* under "Couldn't save. Your
  text is still here." reloaded the list and left the transcript unwritten, and *Retry* under
  "The space could not start" never tried the Space again. `spaceStarting` was cleared only by
  failure, so "Starting the space…" outlived a successful trip (M21).
- **Decision:** `RETRY_LOAD`, `RETRY_DICTATION`, `RETRY_SPACE`, plus `RESUME_DOWNLOAD` and
  `DOWNLOAD_AGAIN` for the two shapes a failed model transfer now has. Every `when` over the enum
  is exhaustive, so the compiler names each site. `UiStateMapper` emits `RETRY_LOAD` because an
  `AppError` carries no memory of the act that produced it; the view model that raises the message
  attaches the specific one. The panel clears `spaceStarting` when it is shown again.
- **Consequences / affects:** `core-common/UiMessage.kt`, `docs/ux/scenarios.md` (`SCN-006`,
  `SCN-012`), `docs/modules/app.md`.
- **Source:** run `2026-09-21-v3` · commit `28c513d`

### DEC-0070 — The panel's declared minimum is 736 dp

- **Date:** 2026-09-21
- **Status:** Accepted · **Refines DEC-0043**
- **Context:** `REQ-060` moved undo, banners and confirmations out of Today's `LazyColumn` into a
  pinned slot, because as list items they went off-screen exactly when they mattered — delete the
  thirtieth note and "Deleted … Undo" is somewhere above the fold, with 2.5 s to find it (M20).
  A pinned slot is fixed chrome, and fixed chrome belongs in the height budget or `B-11` returns
  the first time a banner and an undo line are up together at the minimum.
- **Decision:** `minHeight` 560 → **736 dp**, `defaultHeight` 640 → **800 dp** — a default below
  the declared minimum is not a size the shell can honour, and `PanelSizeTest` now asserts that
  too. The sum is recomputed from the tokens rather than typed.
- **What it trades away:** a person can no longer shrink the panel below 736 dp. Whether that is
  comfortable beside Meta Virtual Display screens is a headset question (`DEC-0066` defers it).
- **Consequences / affects:** `app/src/main/AndroidManifest.xml`, `docs/modules/app.md`.
- **Source:** run `2026-09-21-v3` · commit `28c513d`

### DEC-0071 — No internal identifier reaches a person, and the rule is decidable

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** the audit of 2026-09-21 (C5) found five sentences carrying something only a
  programmer can read: "No key saved for **cloud**", "Something failed: **NullPointerException**",
  "voice · ru · … · **local_fallback**", "small, 190 MB" as a model's whole name, and a server
  status with no next step. Each arrived the same way — a `UiMessage` taking a raw `String`
  argument that happened to be an enum name or a class name.
- **Decision:** **no argument of a `UiMessage` is a raw `String`.** A name that lives in another
  module crosses as `UiMessage.Res`, a resource id the rendering context resolves — which is how
  `:app` gives a `WhisperModel`'s human name to a mapper in `:core-common`. A caller with no name
  gives the sentence **without** one rather than with a key in it. `NoRawIdentifiersTest` is the
  gate, and it is decidable: the test reads every message the mapper can produce.
- **Consequences / affects:** `core-common/UiMessage.kt`, `core-common/UiStateMapper.kt`,
  `docs/modules/core-common.md`.
- **Source:** run `2026-09-21-v3` · commit `ad8bf66`

### DEC-0072 — Audio everywhere, haptics only in the Space, one switch

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** the audit (M22) found no cue of any kind: grepping for haptics, `ToneGenerator` and
  `SoundPool` returned nothing, so record start, stop, the ten-minute cap and "saved and copied"
  were visual only — in a product whose whole position is that the person is looking at a streamed
  desktop rather than at the panel.
- **Decision:** a short `ToneGenerator` cue everywhere and a haptic pulse **only in the Space**,
  behind one Settings switch, default on. Meta documents exactly one Kotlin haptics API and it
  belongs to an `AppSystemActivity` (*Inputs and controllers*); whether `android.os.Vibrator`
  reaches a Touch controller from a 2D panel is UNVERIFIED and is not guessed at. *Haptics: Best
  practices*: never a haptic without a matching visual or audio cue, and always optional —
  the switch is that requirement, not a preference. `ToneGenerator` rather than a bundled sound:
  no asset, no `NOTICE` entry, and no decode before a start cue.
- **The four cues:** record start, record stop, the ten-minute cap — **its own cue**, because that
  one is the app interrupting somebody still speaking (`DEC-0032`) — and the commit.
- **Not proven:** nobody has heard or felt any of them. `B-194` carries it, `deferred: device`.
- **Consequences / affects:** `docs/modules/app.md`, `docs/ux/scenarios.md` (`SCN-004`).
- **Source:** run `2026-09-21-v3` · commit `ad8bf66`

### DEC-0073 — One licence notice, generated rather than copied

- **Date:** 2026-09-21
- **Status:** Accepted
- **Context:** `REQ-063`. The APK ships whisper.cpp/ggml, whose MIT licence requires its notice to
  travel with the binary; a submodule path is not a notice a person holding the APK can reach.
- **Decision:** the repository root `NOTICE` is the one copy. A Gradle `Copy` puts it into the
  APK's assets at build time and `LicencesTest` compares the two **byte for byte**. Pasting the
  text into a string resource is refused: two copies of a licence notice is the drift this project
  keeps finding, and the one place it would never be noticed is a legal file nobody reads.
- **Consequences / affects:** `app/build.gradle.kts`, `NOTICE`, `docs/modules/app.md`.
- **Source:** run `2026-09-21-v3` · commit `ad8bf66`

### DEC-0074 — A destructive button names the SET it will remove, not a count

- **Date:** 2026-09-21
- **Status:** Accepted · **Corrects DEC-0038**
- **Context:** `DEC-0038` says a destructive control "names what it is about to remove". The
  shipped build did not: *Delete N recordings (M MB)* was filled from the TOTAL usage while
  `sweepAudio(cutoff)` deletes only files older than the retention setting, so the button
  overstated its own effect every time the setting was not *keep everything* (audit C1). The
  retention row read as a standing policy as well, while `DEC-0038`'s own text says nothing fires
  on its own.
- **Decision:** the label names the **set** — the recordings older than the chosen age — and not a
  number. A count would need a tree walk per retention tap, which `T-019`'s R1 refuses, and a
  wrong number is worse than an honest description. The retention row says it only arms the
  button.
- **Consequences / affects:** `app/res/values/strings.xml`, `docs/modules/app.md`,
  `docs/ux/scenarios.md` (`SCN-014`).
- **Source:** run `2026-09-21-v3` · commit `ad8bf66`

### DEC-0075 — An unusable Keystore alias is repaired, not retried for ever

- **Date:** 2026-09-21
- **Status:** Accepted · **Refines DEC-0037**
- **Context:** `B-188`. `DEC-0037` split decrypt failures into *permanent* (the value is gone: a
  bad tag, bad padding, `KeyPermanentlyInvalidatedException`, malformed stored text) and
  *transient* (everything else, and the bytes stay). It had no class for an entry that **exists
  and cannot be opened** — `UnrecoverableKeyException` from `getEntry`, a vendor's "invalid key
  blob" inside it, a plain `InvalidKeyException` — so those landed in the transient default,
  `deleteEntry` was never called, and every read re-failed for ever. Because the same store holds
  every NON-secret setting, `put` then returned `Storage("keystore")` for all of them and the
  Settings screen stopped saving until the person cleared app data.
- **Decision:** a third class, *unusable*, checked **after** permanent so `DEC-0037` keeps every
  shape it already claimed. It deletes the alias, generates a new key and clears the store in one
  act — nothing written under the old key can be read again, so failing one key at a time would
  only delay the same loss — and announces `openrouter_api_key` and `cloud_stt_key` through
  `corruptedKeys`, but only when they were actually stored. If the alias cannot be **replaced**,
  nothing is cleared and nothing is announced: the `put` fails honestly instead.
- **What is deliberately NOT in the class:** `java.security.KeyStoreException`. A busy or
  still-booting keystore throws it, and `C-04` is this project's record of what treating that as
  loss costs. Widening the predicate destroys data; narrowing it costs a retry.
- **The seam:** the key moved behind `SecretKeyProvider` (`AndroidKeystoreKeyProvider` in
  production), because deleting and regenerating an alias happens nowhere near a decrypt — the
  existing `decryptFailure` seam could neither provoke the repair nor watch it succeed, and
  Robolectric has no `AndroidKeyStore` at all.
- **What it trades away, and it is visible:** a repair returns the language, provider, model,
  retention and both switches to their defaults without announcing it. `SettingsViewModel`
  re-reads on a repair so the screen shows the reset rather than drawing values the store no
  longer holds. **Not proven on a device:** the unusable arm is tested against a fake provider
  with a real AES key, never against a genuine invalid key blob — `B-196`, `deferred: device`.
- **Consequences / affects:** `core-common/SecureSettings.kt`, `docs/modules/core-common.md`,
  `app/ui/SettingsViewModel.kt`.
- **Source:** run `2026-09-21-v3` · commit `3be95c8`

### DEC-0076 — The export streams, and says what it left out

- **Date:** 2026-09-21
- **Status:** Accepted · **Refined by DEC-0093**
- **Context:** `B-189`. `VaultExporter` read each entry whole with `readBytes()` inside a
  `runCatching` that catches `OutOfMemoryError`, and `ExportSummary` had no skipped count — so a
  long recording produced an archive that **looks complete and is not**, which is the outcome the
  exporter's own comment forbids. Recording length is unbounded and there is no `largeHeap`: at
  32 kB/s a one-hour `.wav` is about 115 MB.
- **Decision:** each entry streams through a 64 kB window, and `ExportSummary` carries `skipped`
  plus the distinct reason classes (exception simple names — no messages, no paths, because an
  export summary is a thing people forward). **No size limit was added**, on purpose: the
  threshold is unmeasured and inventing one is the wrong fix.
- **What it cannot do, stated:** a file that dies part-way through its copy leaves a truncated
  entry, because a zip cannot retract a name it has already written. The skipped count is then
  the only honest signal, which is exactly why it exists. `bytes` counts entries that completed.
- **Consequences / affects:** `feature-vault/VaultExporter.kt`, `docs/modules/feature-vault.md`.
- **Source:** run `2026-09-21-v3` · commit `3be95c8`

### DEC-0077 — The cleartext rule is enforced at every hop, not only at the address a person typed

- **Date:** 2026-09-21
- **Status:** Accepted · **Narrows DEC-0005**
- **Context:** `B-187`, the worst finding of the 2026-09-21 audit that was not a headset question.
  `NetworkPolicy.requireReachable` was asked **once**, about the URL in Settings. OkHttp follows
  redirects by default and the manifest permits cleartext, so a `307` or `308` from a configured
  `https` endpoint re-POSTed the recording — and `CloudTranscriptionClient`'s `Authorization`
  header — to whatever host the server named, in the clear, with nothing checking where it landed.
- **Decision:** the policy gains a second door, `NetworkPolicy.permits(scheme, host)`, which
  `requireReachable` delegates to so there is one rule and not two; and a **network** interceptor
  applies it to every hop of every call in `:feature-stt`, with all three clients built through
  `FabricHttp` and a test asserting each one carries it.
- **Network rather than application level, and this is the whole fix:** an application interceptor
  runs once per CALL, above `RetryAndFollowUpInterceptor`, so it sees the original request and the
  final response and never the redirect it would have to refuse. A network interceptor runs once
  per HOP and above `CallServerInterceptor` — the thing that writes the request line, the headers
  and the body — so a refusal means no byte of the recording and no byte of the key reaches that
  socket.
- **What it does not close, stated:** network interceptors run AFTER `ConnectInterceptor`, so DNS
  and TCP to the redirect host still happen — a malicious server learns the device followed it
  that far. Closing that means turning `followRedirects` off and re-implementing OkHttp's redirect
  semantics by hand inside a security control, which is a worse trade than the handshake it saves.
- **Not a new error shape:** a refused hop is `AppError.InsecureUrl`, whose existing sentence is
  the policy's own. `AppError.Network(host)` would say the host did not answer, which is false —
  it answered, with a redirect this app refused. The error carries scheme and host only, because a
  query string is where a token ends up.
- **`Authorization` across hosts is verified, not assumed:** OkHttp drops it in
  `buildRedirectRequest`, and `RedirectPolicyTest` asserts it — a rule nobody checks is precisely
  what this row is about. It says nothing about the recording, which a 307 re-sends in full.
- **Watched failing:** `addNetworkInterceptor` → `addInterceptor` reddens three tests, re-planted
  at integration.
- **Consequences / affects:** `core-common/NetworkPolicy.kt`, `docs/modules/feature-stt.md`,
  `docs/modules/core-common.md`, `docs/evidence/backlog.md` (`B-197`).
- **Source:** run `2026-09-21-v3` · commit `5e57192`

### DEC-0078 — The model's digest is re-checked when the loader refuses it, and never before

- **Date:** 2026-09-21
- **Status:** Accepted · **Narrows DEC-0016**
- **Context:** `B-191`. `ModelStore.isPresent` compares the byte count only, so a file corrupted
  after download stayed "present" for ever: `initContext` returned 0 on every dictation, nothing
  evicted it, and the only way out was *Remove speech model* — which a person has no reason to
  suspect.
- **Decision:** `ModelIntegrity` hashes on the **first loader failure**, caches the verdict per
  process by path + length + mtime (so a re-download is judged again), deletes a file that fails
  and reports `ModelMissing` — which already offers *Download* — and keeps one that passes,
  reporting `SttFailed`. A file with no pinned digest convicts nothing.
- **Why not at first use:** seconds of SHA-256 over 190–574 MB in front of the first dictation of
  every process, on the path where the person is holding the trigger, for something true of one
  run in thousands.
- **The gap that choice accepts, named:** a model wrong enough to trip `GGML_ASSERT` aborts the
  process, and nothing after the loader returns can intervene. `whisper_log_set` (WARN and above,
  because the loader announces itself at INFO with the model's full path and the store's root is a
  directory a person's name can be in) at least makes such a crash readable. **An abort observed on
  a headset reverses this decision.**
- **Consequences / affects:** `feature-stt/ModelIntegrity.kt`, `feature-stt/WhisperEngine.kt`,
  `feature-stt/src/main/cpp/fabricvr_whisper.cpp`, `docs/modules/feature-stt.md`.
- **Source:** run `2026-09-21-v3` · commit `5e57192`

### DEC-0079 — The HTTP client factory lives in `:core-common`, beside the rule it enforces

- **Date:** 2026-09-22
- **Status:** Accepted · **Widens DEC-0077**
- **Context:** `B-197`. `DEC-0077` put `FabricHttp` and the hop interceptor in `:feature-stt`,
  where the clients that carried recordings lived. `OpenRouterClient` in `:feature-assistant`
  built its own `OkHttpClient` and followed redirects unchecked — the rule covered three callers
  of four, and the fourth was invisible from inside the module that held the factory.
- **Decision:** the factory, the interceptor and `InsecureHopException` move to `:core-common`,
  beside `NetworkPolicy` itself. Moved rather than duplicated, for `NetworkPolicy`'s own reason:
  **two copies of a cleartext rule is how one of them quietly stops being the rule.**
- **What it costs, accepted:** `:core-common` declares `api(libs.okhttp)`, so OkHttp is on every
  module's compile classpath and used by two of them.
- **How a fifth client is prevented, and why a runtime check could not do it:** a per-module
  assertion structurally cannot see another module — that is exactly how `B-197` happened.
  `FabricHttpSourceTest` scans every module's sources for an `OkHttpClient` built outside the
  factory, with canaries proving it can fail. Finding that test sitting `UP-TO-DATE` while
  another module changed is `B-204`.
- **Consequences / affects:** `core-common/FabricHttp.kt`, `docs/modules/core-common.md`,
  `docs/modules/feature-stt.md`, `docs/modules/feature-assistant.md`.
- **Source:** run `2026-09-22-sweep` · commit `795ac9b`

### DEC-0080 — A whisper run has an identity

- **Date:** 2026-09-22
- **Status:** Accepted · **Refines DEC-0060**
- **Context:** `B-181`. The bridge cleared the abort flag as its first act — deliberately, so a
  cancel landing between two runs could not abort the next one — which meant a cancel arriving
  between `invokeOnCancellation` and the native entry was wiped, and the run went to completion
  with nobody waiting for it.
- **Decision:** `RunState` carries `next_run`, `active_run`, a lock-free `abort_now` (the only
  thing `abort_callback` reads) and a capped set of cancelled runs. `beginRun(ptr)` issues a
  monotonic id, and `begin_active_run` replaces *clear the flag* with **"is a cancel meant for
  me?"**. Both properties then hold **by construction** rather than by ordering luck, so
  `I-26` — telling a person their dictation failed when they changed the model — cannot return.
  Kotlin allocates the id before registering the cancellation handler.
- **The bound, stated:** `cancelled_runs` is capped at 64 entries, because an unbounded set
  behind a mutex in a process that never releases the whisper context is a slow leak.
- **What is not proven:** the C++ is `-fsyntax-only` verified by `check-native.sh` and has never
  executed. The JVM tests prove the Kotlin ordering and the run-identity contract, not the
  bridge. The device session owns the rest (`DEC-0066`).
- **Consequences / affects:** `feature-stt/src/main/cpp/fabricvr_whisper.cpp`,
  `feature-stt/WhisperEngine.kt`, `docs/modules/feature-stt.md`.
- **Source:** run `2026-09-22-sweep` · commit `795ac9b`

### DEC-0081 — A test that touches the network is opt-in, and reports skipped

- **Date:** 2026-09-22
- **Status:** Accepted
- **Context:** `B-173`, and `SI-09`: a mocked test proves the rule the code implements, never
  that the rule still matches the world. Four of the five pinned model digests had never been
  compared with anything the vendor publishes, and downloading 1.395 GB in a unit suite is not a
  trade anybody would keep.
- **Decision:** a network-touching test is gated behind `FABRICVR_NETWORK_TESTS=1` and uses
  `Assume`, so an ordinary run reports it **skipped** — not green, not failed. Silence and a pass
  must not look the same, which is the same rule `blind` serves in an audit. Its cost must be
  bounded by design rather than by luck: this one spends one API call plus five single-byte
  ranged requests.
- **Where it belongs:** the nightly cold-dependency job, not the per-commit gate.
- **What it proves and does not:** the pin equals the digest the vendor **publishes**, not the
  hash of bytes downloaded — `ModelDownloader` does the latter free on every real transfer. It
  is also the only thing in the tree that proves the vendor honours `Range` at all, which is the
  premise of the entire resume path.
- **Consequences / affects:** `docs/modules/feature-stt.md`, `.github/workflows/ci.yml`
  (the cold job, once `main` moves — `B-168`).
- **Source:** run `2026-09-22-sweep` · commit `795ac9b`

### DEC-0082 — The recording stays lossless; retention and export are the levers

- **Date:** 2026-09-22
- **Status:** Accepted
- **Context:** `B-138` asked for Opus in the live store — about a 10.7× reduction on 32 kB/s raw
  PCM. The blast radius was measured before the decision: 12 `.wav`-literal sites where a miss
  fails **silently** (`audioUsage()` reports 0 and `sweepAudio()` deletes nothing, killing
  `DEC-0038`'s disclosure; the notes-only export starts shipping recordings; archives from a new
  build are refused entry by entry), 24 test files, 13 hard breaks, and new machinery with no
  precedent here — a `MediaCodec` encoder, an OGG muxer with correct Opus CSD, an extractor and
  a 48→16 kHz resampler. Robolectric can execute none of it, and CI has not run since `7e22df4`.
- **Decision:** **declined as scoped.** The recording stays lossless. `DEC-0022` keeps the
  archive *for fidelity*, and since `DEC-0039` a human ear is one of its consumers — telling
  apart two first names that differ in one vowel is single-phoneme discrimination, the first thing 24 kbps
  spends. The change is one-way, and the 7 GB/year that motivates it is arithmetic: `B-139`, the
  `du` on a real headset, has never been run.
- **Replaced by three rows, in this order:** `B-200` (make retention actually fire — bounds
  storage at no fidelity cost, reversible by the person), `B-202` (stream `WavWriter.write`,
  which closes the measured ~96 MB peak of `M17` and adds no format), `B-201` (compress on
  **export** only, where a wrong byte costs a re-export rather than a recording).
- **What would reverse it:** `B-139` measuring a headset actually filling up.
- **Consequences / affects:** `docs/evidence/backlog.md`, `docs/modules/feature-vault.md`.
- **Source:** run `2026-09-22-sweep` · audit of `B-138`

### DEC-0083 — The version code is the commit's own clock

- **Date:** 2026-09-22
- **Status:** Accepted · **Refines DEC-0048**
- **Context:** `B-160`. `DEC-0048` chose a git-derived `versionCode` and took the count of commits
  reachable from HEAD — which is a property of the **branch**, not of the commit. Measured
  2026-09-22: `main` gives 57 and `feat/v1-notes-core` gives 176. Android refuses a downgrade
  install, so a person holding a branch build could not install a `main` build over it, and the
  only way through is the uninstall that takes `filesDir` — the notes, the vault, the Keystore
  and a 574 MB model.
- **Decision:** `versionCode` is the commit's committer time in seconds since
  2026-01-01T00:00:00Z. It is a **total order over every commit in every branch**, fixed by the
  commit itself, needing no tracked file and no second ref. `-PversionCode` remains the escape
  hatch.
- **Refused, with the reason for each:** a **committed counter** gives two branches off one base
  the same number — two APKs, one version, which is `H-31` again — and a rebase conflict on every
  commit; **count-of-main plus a branch offset** needs a local `main` that a single-branch
  checkout does not have, and composes two sequences into one field, which `DEC-0048`'s own
  Decision 2 already refused.
- **What it trades away, stated:** the number stops being readable by a person (22 814 667 rather
  than 176 — the short sha was already the readable half); the field's space runs out in 2092,
  which a test asserts; and a deliberately backdated committer date still walks it backwards.
- **A side effect worth naming:** the shallow-clone `require` is gone. `%ct` is correct at any
  depth — verified against a real `--depth 1` clone, where the commit count said `1` and the
  timestamp matched the full checkout exactly.
- **Consequences / affects:** `app/build.gradle.kts`, `.github/workflows/ci.yml`,
  `docs/modules/app.md`.
- **Source:** run `2026-09-22-sweep` · commit `6a8bec4`

### DEC-0084 — Every view-model coroutine goes through `launchGuarded`

- **Date:** 2026-09-22
- **Status:** Accepted
- **Context:** `B-159`. `viewModelScope` carries no `CoroutineExceptionHandler`, so a throw inside
  one of its launches crashes the app with no sentence for the person — and under `runTest` it
  fails whichever test happens to be running, which is how this arrived: the flakiness was the
  only symptom and it pointed at the wrong class.
- **Decision:** `viewModelScope.launch` may appear only in `app/ui/Guarded.kt`. Everything else
  calls `launchGuarded`, which wraps the body, logs `vm.launch.escaped` and forwards to the
  caller's sink; 36 launches across four view models were converted. `check-seams.sh` refuses a
  bare one with a canary, and `selftest.sh` plants against it.
- **`launchIn(viewModelScope)` is deliberately outside the rule:** a flow's failures belong to its
  own `.catch`, and forcing them through a second mechanism would hide where they came from.
- **What it trades away:** three of the four view models **log** an escape rather than showing it.
  Forcing a failure state onto a screen from a background refresh would be a worse lie than a log
  line — but it is a lie of omission either way, and it is written here rather than left implicit.
- **The side-finding, which is the more valuable half:** the rename neutered
  `tools/check_main_thread.py`, which keyed on the literal `viewModelScope.launch` and would have
  gone on printing `ok` over a tree it could no longer read. That is this project's oldest defect
  shape — a check that reads nothing and prints the word that means it read everything — arriving
  through a refactor rather than through a bug. The checker knows all three spellings now, and
  prints how many blocks it scanned.
- **Consequences / affects:** `app/ui/Guarded.kt`, `scripts/check-seams.sh`,
  `tools/check_main_thread.py`, `docs/modules/app.md`.
- **Source:** run `2026-09-22-sweep` · commit `6a8bec4`


### DEC-0085 — The Space lets the room through, and the palette stops describing tones the display throws away

- **Date:** 2026-09-22
- **Status:** Accepted
- **Context:** `B-219` and `B-220`, both from the 2026-09-22 audit's VR UX reading, and they are
  one surface seen twice. **Every piece of the transparency was already in place** —
  `Theme_FabricVR_Transparent`, `enableTransparent = true`, `includeGlass = false` on the panel
  configuration, and `com.oculus.ossplash.background=passthrough-contextual` in the manifest — and
  one line in the shared composition, `Surface(color = Tokens.Palette.ink)`, painted over all of
  it. The Space was a black rectangle floating in somebody's room, which is the one thing Meta's
  mixed-reality guidance asks an app not to be. Separately, Meta's *Color* page: "LCD limitations
  prevent them from meaningfully differentiating brightness levels **below 13 out of 255**", and
  `ink` was `#0B0E12` — red **11**.
- **Decision 1 — the background is the host's to choose.** `FabricApp` takes a `background`
  colour, defaulting to the opaque `ink`. `ImmersiveActivity` passes `inkSpace`; `PanelActivity`
  passes nothing. **The default is the panel's on purpose**: the 2D host sits in the Horizon OS
  shell beside other windows, and a see-through window there makes its neighbour unreadable.
- **Decision 2 — `inkSpace` is `ink` at alpha 0.92, and the number has an argument.** The floor is
  legibility over a room nobody controls. Worst case is a white wall: the composite background
  becomes about `0.92 x 13 + 0.08 x 255 = 32` in red, and `text` at 232 still reads against it at
  better than 12:1, far above the 4.5:1 a person needs. At 0.80 the same wall lifts it past 60 and
  the margin starts to matter. **The exact value is a device question and stays on the board for
  the headset session** (`DEC-0066` defers it); what it is not allowed to be is 1.0, silently.
- **Decision 3 — no palette token sits below 13 in any channel.** `ink` becomes `#0D1116` and
  `accentInk` `#0D201D` — red 5 there, and the floor applies to a foreground as much as to a
  background, because below it the display renders 5 and 13 as the same tone. `PaletteFloorTest`
  holds the whole palette to it rather than the two tokens that happened to be caught, and also
  asserts that `ink` and `surface` stay at least five levels apart: a floor is worth nothing if
  the two tones the dark theme rests on land on the same one anyway.
- **What this trades away, stated rather than discovered.** A translucent Space background means
  text contrast now depends on the room. The arithmetic above says the margin is large, and the
  arithmetic is not a measurement — nobody has read this in a headset over a bright wall. That is
  exactly what the device session is for, and the alpha is one token when it needs to move.
- **The wiring is a test, not a screenshot** (`SpaceBackgroundTest`). The defect was never in the
  tokens; it was that nobody passed one. A source scan names the omission in the module where the
  next host will be written, in `ControlFloorTest`'s shape, instead of waiting for a screenshot
  nobody takes.
- **Consequences / affects:** `core-common/.../theme/Tokens.kt`, `app/.../ui/FabricApp.kt`,
  `app/.../ImmersiveActivity.kt`, `docs/modules/core-common.md`, `docs/ux/screens.md`,
  `docs/evidence/backlog.md`. Closes `B-219`, `B-220`.
- **Source:** run `2026-09-22-loop`, audit `docs/audit/2026-09-22-audit.md` UX-H1 and UX-H2


### DEC-0086 — A control is visible, and its floor is checked by the scan rather than by a comment

- **Date:** 2026-09-22
- **Status:** Accepted
- **Context:** `B-221` and `B-224`, from the 2026-09-22 audit's VR UX reading. Meta asks for a
  **48 dp minimum hit target, 60 dp for a primary action**, and **3:1 contrast on a UI component's
  boundary**. This product's own floor is 72 dp and it had wrappers for it; six controls were
  drawn without them, and the one token that draws every outline measured **1.38:1**.
- **Decision 1 — the exclusion that expired becomes a rule.** `ControlFloorTest` excluded `Button`
  on a stated fact: *"every `Button` in this tree already carries an explicit height."* True when
  written; five call sites later Settings drew *Download*, *Save* (cloud) and *Save* (server) at
  Material's 40 dp, and the scan whose whole job is this could not see them. **An exclusion
  justified by a fact outlives the fact.** `Button` and `Switch` are in the set now, under the rule
  the old comment was actually asserting: a bare control is an offender **unless its own call
  states a height**. `FabricButton` is the wrapper that makes obeying it the short path.
- **Decision 2 — a `Switch` row is one target and one announcement.** Material's switch is
  52 × 32 dp; the 72 dp `heightIn` around it belonged to a `Row` that was not clickable, so a
  controller ray had to land on the thumb. Both rows are `toggleable` with `Role.Switch` and
  `mergeDescendants` now — the whole row is the target, and a screen reader says the label with the
  state instead of announcing an unnamed toggle beside an unrelated line of text.
- **Decision 3 — `Palette.line` is `#5C6A7E`, measured at 3.15:1.** It was `#2A3340`: **1.38:1**
  against `surface`, and `Theme.kt` maps Material's `outline` to that one token, so it is the only
  edge on every `FabricOutlinedButton` and all six `OutlinedTextField`s. A control whose whole
  affordance is its outline, with an outline nobody can see, reads as text. `FabricDangerButton`
  overrode it at 5.73:1 and was the proof the rest could. `PaletteFloorTest` computes the ratio
  from the sRGB formula rather than trusting this paragraph.
- **The scan matches brackets, not a character count, and that took three attempts.** A `Button`'s
  `modifier` sits several lines below it, often behind a paragraph of comment, and a `Switch` is
  legitimate when the row ABOVE it carries the target. A 600-character window reported four correct
  call sites; 500 back and 900 forward reported two; the real distances are 1 089 and 813. **A
  number tuned until the tree passes is a number the next screen breaks**, so the forward extent is
  the call's matching close-paren exactly and the backward extent is the nearest enclosing `Row(` /
  `Column(` / `Box(`.
- **What this trades away.** The palette's borders are now visibly present rather than a whisper,
  which changes how every settings card reads; that is the point, and it has not been seen in a
  headset. And the scan is stricter, so the next screen that wants a 40 dp button has to say so at
  the call site rather than inherit it.
- **Consequences / affects:** `core-common/.../theme/Tokens.kt`, `core-common/.../ui/Controls.kt`,
  `app/.../ui/SettingsScreen.kt`, `docs/modules/core-common.md`, `docs/ux/screens.md`,
  `docs/evidence/backlog.md`. Closes `B-221`, `B-224`.
- **Source:** run `2026-09-22-loop`, audit `docs/audit/2026-09-22-audit.md` UX-H5 and UX-H7


### DEC-0087 — A screen with a field leaves room for the keyboard, and says what it cannot verify

- **Date:** 2026-09-22
- **Status:** Accepted
- **Context:** `B-222`. Seven text fields across three screens, and **zero** `imePadding`,
  `ImeAction`, `WindowInsets.ime` or `BringIntoViewRequester` anywhere in the tree. `SearchScreen`
  raises the keyboard on entry with the results list directly under it.
- **Decision — `imePadding()` on every screen that draws a field, and a scan that keeps it there.**
  `ImePaddingTest` finds the screens with fields and requires the padding, so the fourth screen
  gets it without being told.
- **What this is honest about, and the honesty is the decision.** Horizon OS can present its
  keyboard as a **spatial overlay in front of the person**, and on that surface the panel has no
  IME inset at all, so `imePadding` does nothing. The same build also runs as a 2D window in the
  Horizon OS shell, where the keyboard **is** an inset and the results list genuinely sat under it.
  Whether either keyboard declaration in the manifest is what makes the system keyboard attach to a
  Compose panel is **unmeasured** and already on the board as a device question (`B-050`). A screen
  without the padding is wrong on one surface and unchanged on the other; a screen with it is
  correct on one and unchanged on the other. That is the whole argument, and it does not pretend to
  be a measurement.
- **The search field declares its action key.** `ImeAction.Search`, and pressing it **dismisses**
  the keyboard rather than re-running the search — the query runs on every keystroke, so the
  results are already there and what the person wants is the keyboard out of the way.
- **The auto-focus stays.** `SearchScreen` requests focus on entry and it is the only screen that
  does; a person who navigated to Search came to type. Meta's objection is to a keyboard appearing
  unbidden, which is not this.
- **Section headings carry `heading()`** (`B-225`, the half that is decidable without a device).
  Five titles in Settings. A screen reader can only offer *jump to the next section* if something
  says where the sections are. **The focus-ring half of `B-225` is not closed here**: whether a
  controller ray produces hover events on a Compose panel at all is the same unmeasured question as
  the keyboard, and a focus indication written against a guess is decoration.
- **Consequences / affects:** `app/.../ui/SearchScreen.kt`, `app/.../ui/NoteEditorScreen.kt`,
  `app/.../ui/SettingsScreen.kt`, `docs/ux/screens.md`, `docs/evidence/backlog.md`.
  Closes `B-222`.
- **Source:** run `2026-09-22-loop`, audit `docs/audit/2026-09-22-audit.md` UX-H4 and UX-M18..M20

### DEC-0088 — A dictation leaves the outbox when its note is durable, not when it is claimed

- **Date:** 2026-09-23
- **Status:** Accepted · **Refined by DEC-0095**
- **Context:** `B-237`, found by `docs/audit/2026-09-23-audit.md`. `DictationOutbox.claim` erased
  `dictation.tsv` at the claim, and every caller did the durable work after it: `commitDictation`
  moved the audio and wrote the row; the editor appended to its state and wrote on a debounce. A
  process death in between, or a write that failed, left the words in RAM only — the state
  `DEC-0068` exists to make impossible, moved from before the claim to after it. Watched: six
  cases of `DictationDurabilityTest` red against the old claim, the first of them *"the outbox was
  erased before the write that makes it redundant"*.
- **Decision — three verbs where there was one.** `claim` is ownership in memory only; the file
  stays. `bind` writes down, before the write, which note the dictation is becoming — its id, its
  `createdAt` and where its audio now is. `settle` erases the file once the note is durable, or
  once the person discarded the dictation (`VoiceViewModel.cancel`) or deleted the note it was
  appended to. A restart therefore finds one of three things: nothing (settled), a bound entry
  whose note exists (settled without a second write — `commitDictation` checks
  `repository.get(noteId)` first), or a bound entry whose note does not (written under the bound
  id and `createdAt`, so its recording's vault folder is the one it was already moved into).
- **The editor settles only through a write that carried the words.** Any successful write ends
  the promise — the append's own write may fail and a later autosave, `flush` or *Retry* carry
  the words instead — but not one whose note was read before the append: `EditorViewModel.save`
  reads a generation counter before the note, and `attachTranscript` bumps it after the state.
  Watched: removing that guard turns *"a write that left before the append does not settle it"*
  red.
- **What it trades away, stated:** a death after the editor's write lands and before the settle
  writes the appended words a second time, as a note of their own. A duplicate sentence is
  recoverable; a lost one is not. And an `offer` landing between a claim and its settle overwrites
  the file — it needs a whole second dictation to finish inside one Room write, and `offer` logs
  `dictation.outbox.replaced` if it ever happens.
- **Rejected:** a two-slot outbox file (for the overwrite residue) — a format change and a second
  identity rule for a window measured in milliseconds against seconds.
- **Consequences / affects:** `app/src/main/kotlin/ai/passioncode/fabricvr/DictationOutbox.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/ui/NotesViewModel.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/ui/EditorViewModel.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/ui/VoiceViewModel.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/ui/NoteEditorScreen.kt`, `docs/modules/app.md`,
  `docs/evidence/backlog.md`. Closes `B-237`.
- **Source:** run `2026-09-23-loop` · audit `docs/audit/2026-09-23-audit.md`

### DEC-0089 — A deletion is journalled by the repository, before the row goes

- **Date:** 2026-09-23
- **Status:** Accepted
- **Context:** `B-239`. `VaultMirror.remove` journals a removal when its collector reaches the
  `Deleted` change — after `dao.delete` has committed. A process death between the two left a `.md`
  with no row and no journal entry, and `VaultReconciler`'s import brought the deleted note back on
  the next launch. The mirror's own KDoc claimed *recorded before the attempt* covered that window;
  it covered the window after it.
- **Decision:** `RoomNotesRepository` takes a `beforeDelete(id, createdAt)` hook and runs it before
  the row is deleted; `Graph` points it at `RemovalJournal.record`. `:core-notes` does not depend on
  `:feature-vault`, so the seam is a function rather than a journal type. `record` is idempotent
  per id, so the mirror's later record is a harmless second copy. A delete whose row fails to go
  leaves a journal entry for a live row, which the reconcile already drops (*Undo overrules the
  journal*).
- **What it trades away:** a hook that throws — the journal on a full disk — does not stop the
  delete. The person asked for it and the mirror still removes the files at once; only the
  guarantee across a death in that window is lost, logged as `notes.delete.intent_failed`.
- **Rejected:** a tombstone table deleted-and-inserted in one Room transaction. It closes the same
  window without a second file, but it is a schema migration (v6) and a second source of truth
  about deletions beside the journal the reconciler already reads.
- **Watched:** `VaultReconcilerTest` *"a note deleted before the mirror ran is not imported back
  after a restart"* red with the hook call removed, green with it.
- **Consequences / affects:** `core-notes/src/main/kotlin/ai/passioncode/fabricvr/notes/NotesRepository.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/Graph.kt`,
  `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/VaultMirror.kt`,
  `docs/modules/core-notes.md`, `docs/modules/feature-vault.md`, `docs/evidence/backlog.md`.
  Closes `B-239`.
- **Source:** run `2026-09-23-loop` · audit `docs/audit/2026-09-23-audit.md`

### DEC-0090 — A second recording never replaces the first; the earlier one is kept beside it

- **Date:** 2026-09-23
- **Status:** Accepted · **Refined by** `B-254` — the earlier recordings are listed in the editor since `1252416`
- **Context:** `B-238`. A note row has one `audioPath`, and `FileVault.adoptAudio` targeted
  `<id>.wav` with `target.delete()` before the rename. The editor's *Record* appends a second
  dictation's words to the open note and hands its recording to the same target, so the first
  recording — kept for fidelity (`DEC-0022`) and playable since `DEC-0039` — was deleted the moment
  the person dictated into a note twice. Watched: `VaultAudioTest` *"adopting a second recording
  keeps the first beside it"* red with *"the first recording was destroyed"*.
- **Decision — nothing is deleted to make room.** The recording already at `<id>.wav` is renamed
  to `<id>~<its last-modified millis>.wav` in the same folder, and the new one takes `<id>.wav`, so
  the row's `audioPath` and its `transcript` still describe the same (newest) recording and
  *Transcribe again* re-reads the recording the transcript came from. The earlier recording is the
  note's in every other respect: a delete trashes it with the note, *Undo* restores it, *Delete
  recording* removes it, the export carries it, and the archive importer accepts
  `<uuid>~<digits>.wav` (and only as a `.wav`). A sweep that takes one does not name the note,
  because the row's own recording is still there. A rename that fails stops the adoption, and
  `adoptOrKeep` keeps the new recording at its scratch path — nothing is lost on either side.
- **What it does not do yet, and why that is a board row rather than this decision:** the app
  plays only the newest recording; the earlier ones are on disk, disclosed by the recordings count
  and in every export, and not listed on the note. Listing them is interface work (`B-254`), and
  a note that can list several recordings is a data-model change — neither was needed to stop the
  loss.
- **Rejected:** refusing a second recording in the editor (turns a working gesture into an error
  for a defect that was ours), and making the second dictation a note of its own (splits one
  thought across two notes, which is exactly what *Record* in the editor exists to avoid).
- **Consequences / affects:** `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/Vault.kt`,
  `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/VaultZipImporter.kt`,
  `docs/modules/feature-vault.md`, `docs/ux/scenarios.md`, `docs/evidence/backlog.md`.
  Closes `B-238`.
- **Source:** run `2026-09-23-loop` · audit `docs/audit/2026-09-23-audit.md`

### DEC-0091 — A remote answer is words only if it says so, and its deadline grows with the recording

- **Date:** 2026-09-23
- **Status:** Accepted · **Refined by DEC-0094**
- **Context:** two findings of `docs/audit/2026-09-23-audit.md` in the two remote speech clients.
  `B-244`: both read `text` and fell back to the raw body with `?: body`, so a captive portal's
  HTML, a proxy's page or a JSON object with no `text` was saved as the note — and, found while
  fixing it, the successful body was read under the 64 KiB ceiling meant for error bodies, so a long
  transcript arrived cut, failed to parse and the same fallback wrote half a JSON document into the
  note. `B-243`: `RemoteWhisperClient` gave every call 60 s and `CloudTranscriptionClient` 120 s,
  while a dictation may run 600 s, so a long dictation always timed out and fell back to the headset.
- **Decision — the body.** `RemoteTranscriptBody` is the one rule both clients follow: a 2xx body is
  read whole up to 4 MiB, and it is a transcript only when it is a JSON object whose `text` is a
  string (an empty string included — silence is an answer). Anything else is a new
  `AppError.RemoteSttUnreadable(status)`, which the router treats like every remote failure: it
  falls back and the *Why:* line says so. Its own sentence — *"The speech service answered, but not
  with a transcript. Check its address in Settings."* — because *"refused the recording (200)"*
  would be false. Written without a brand pack (`B-035`), stated rather than skipped silently.
- **Decision — the deadline.** `RemoteDeadline.secondsFor` = max(60 s, 30 s + 2 × the audio's
  length), set on each `Call` before it is enqueued. *Stop transcribing* still cancels the call at
  once (`CallAwait.kt`), so a long deadline bounds only an unattended call.
- **Watched:** `RemoteTranscriptShapeTest` four cases red against the old clients (portal page,
  JSON without text, non-string text, a 100 kB transcript truncated); `RemoteCallTimeoutTest`'s
  ten-minute case red with the per-call deadline removed from one client.
- **Not measured, and said so:** decode speed on a real home whisper-server. Twice real time is a
  CPU-only estimate; a slower machine still falls back, now after a deadline that scales.
- **Consequences / affects:** `feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/RemoteTranscriptBody.kt`,
  `feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/RemoteDeadline.kt`,
  `feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/RemoteWhisperClient.kt`,
  `feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/CloudTranscriptionClient.kt`,
  `core-common/src/main/kotlin/ai/passioncode/fabricvr/common/AppError.kt`,
  `core-common/src/main/kotlin/ai/passioncode/fabricvr/common/UiStateMapper.kt`,
  `docs/modules/feature-stt.md`, `docs/ux/scenarios.md`, `docs/evidence/backlog.md`.
  Closes `B-243`, `B-244`.
- **Source:** run `2026-09-23-loop` · audit `docs/audit/2026-09-23-audit.md`

### DEC-0092 — A run the engine abandons ends in a state with an exit, not in silence

- **Date:** 2026-09-23
- **Status:** Accepted
- **Context:** `B-251`, and `B-180` from the other end. `DEC-0060` reports a cancelled native run as
  a `CancellationException`, and `WhisperEngine` answers a run queued behind `close()` the same way
  (`I-26`) — which is what a model change does. The transcription coroutine in `VoiceViewModel` was
  **not** itself cancelled, so the exception ended it the way a cancellation ends a coroutine:
  silently. Nothing set a state, and the screen stayed on *Transcribing…* for ever, the recording
  kept and unreachable. `B-180` had called this "a model switch abandons a transcription and
  nothing describes what the person sees" and deferred it to a headset; it was worse than
  undescribed. Watched: `TranscriptionAbandonedTest` red with *"left the screen waiting for ever:
  Transcribing"*.
- **Decision:** `VoiceViewModel.transcribe` catches that `CancellationException` and asks whose it
  is — `currentCoroutineContext().ensureActive()`. Its own cancellation (*Stop transcribing*, a host
  going away) propagates unchanged. An abandoned run becomes `VoiceState.Failed` with
  *"The speech model changed, so this transcription stopped. Your recording is kept — try again, or
  discard it."* and `RETRY_LOAD`, reusing the shape and the controls of `B-179`'s deliberate stop,
  for `B-179`'s reason: the recording is intact and both exits are real.
- **Watched the other half:** removing `ensureActive()` turns `VoiceDegradationTest` *a
  transcription can be stopped and the recording is kept* red — a person's own stop would have been
  relabelled as a model change.
- **Consequences / affects:** `app/src/main/kotlin/ai/passioncode/fabricvr/ui/VoiceViewModel.kt`,
  `app/src/main/res/values/strings.xml`, `docs/modules/app.md`, `docs/ux/scenarios.md`,
  `docs/evidence/backlog.md`. Closes `B-251`, `B-180`.
- **Source:** run `2026-09-23-loop` · audit `docs/audit/2026-09-23-audit.md`

### DEC-0093 — An archive that cannot be written whole is not written, and a stale file is found by its clock

- **Date:** 2026-09-23
- **Status:** Accepted
- **Context:** two vault findings of `docs/audit/2026-09-23-audit.md`, both reported and reproduced
  before this entry. `B-247`: a read failure after `putNextEntry` left a truncated entry in the zip —
  a name cannot be retracted — counted as skipped, and a later restore imported the short `.md` or
  `.wav` as whole. `B-240`: `VaultReconciler` re-mirrored a note only when its file was missing, so
  an edit whose mirror write died with the process left the old file for ever and every export
  shipped it.
- **Decision — the archive.** A file that cannot be *opened* is still a skip (the mirror deleting it
  between the walk and the read is ordinary). A failure once its bytes are flowing propagates and
  fails `writeTo`, and `VaultExport` discards the pending row: the mirror replaces files by rename,
  so an open input keeps reading the old inode, and a failure there is a real read error. An archive
  that looks complete and is not is the one a person relies on before an uninstall.
- **Decision — the clock.** `NoteIdentity` carries `updatedAt` (a third column in the same bodiless
  query), and the reconcile rewrites a file whose modification time is older than its row's last
  edit. The mirror writes after the row is stamped, so a healthy file is never older than its row;
  a clock step costs one redundant rewrite, not a loss.
- **Rejected:** a manifest of truncated entries read by the importer — a zip read as a stream sees a
  trailing manifest only after the files it would have refused; and re-reading every `.md` on
  launch to compare front-matter — the loop's own comment promises a normal launch reads nothing.
- **Watched:** `VaultExportTest` *an entry that fails mid-copy fails the export* and
  `VaultReconcilerTest` *a file older than its row's last edit is written again*, both red first.
- **Consequences / affects:** `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/VaultExporter.kt`,
  `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/VaultReconciler.kt`,
  `core-notes/src/main/kotlin/ai/passioncode/fabricvr/notes/Note.kt`,
  `core-notes/src/main/kotlin/ai/passioncode/fabricvr/notes/db/NoteDao.kt`,
  `docs/modules/feature-vault.md`, `docs/evidence/backlog.md`. Closes `B-247`, `B-240`.
- **Source:** run `2026-09-23-loop` · audit `docs/audit/2026-09-23-audit.md`

### DEC-0094 — What the loop's own verification found under three green commits

- **Date:** 2026-09-23
- **Status:** Accepted · **Refines DEC-0091**
- **Context:** after `dc385ff`, `dcb956e` and `8808458` the loop ran two blind readings over the range
  (seam tier, product tier). Every gate was green. The seam tier found that `DEC-0091`'s deadline
  **did nothing**: both remote clients set only `callTimeout`, `FabricHttp.builder()` sets no read
  timeout, so OkHttp's default 10 s applied — and whisper-server sends nothing until it has decoded
  the whole file. `RemoteCallTimeoutTest` read `Call.timeout()` and passed; `SI-09` names exactly
  this: a test of the rule, not of the world.
- **Decision — the read timeout.** Both `SHARED` clients set `readTimeout(0)`; the per-call deadline
  is what bounds the wait. Proven twice: an interceptor reads `chain.readTimeoutMillis()` on both
  clients, and one real-time case makes a MockWebServer hold its headers for 11 s — red with
  *"SocketTimeoutException: Read timed out"* before, green after.
- **Decision — the reconcile untrashes too.** `B-241`'s rule lived only in `VaultMirror.write`; an
  Undo followed by a death between the mirror's delete and its re-write left the row back and the
  recording trashed, and the reconcile rewrote only the `.md`. It now restores a live note's
  trashed files before rewriting. Red first.
- **Decision — a Retry that finds its note written clears its banner.** `commitDictation`'s
  already-written branch settled the entry and left `pendingDictation` and *Retry* on screen. Red
  first.
- **Filed, not fixed, with the reasoning on the board:** `B-255` (a failed commit's entry stays
  claimed while its banner is up, so a later dictation's offer overwrites it on disk — `DEC-0088`'s
  "milliseconds" residue is wrong for a failed commit), `B-256` (the editor path moves the recording
  but never binds, so a recovered entry points at a recording that has moved), `B-257` (a failed
  aside-rename in `adoptAudio` lets the mirror copy the scratch file over the earlier recording).
- **Consequences / affects:** `feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/RemoteWhisperClient.kt`,
  `feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/CloudTranscriptionClient.kt`,
  `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/VaultReconciler.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/ui/NotesViewModel.kt`, `docs/modules/feature-stt.md`,
  `docs/modules/feature-vault.md`, `docs/evidence/backlog.md`.
- **Source:** run `2026-09-23-loop`, validation after three iterations

### DEC-0095 — The outbox never writes one dictation over another

- **Date:** 2026-09-24
- **Status:** Accepted · **Refines DEC-0088**
- **Context:** `B-255`, from the loop's own seam verification (`DEC-0094`). `DEC-0088` kept
  `dictation.tsv` single-slot and called the overwrite of an unsettled entry a milliseconds-wide
  residue. For a **failed** commit it was as wide as the *Retry* banner: A's write fails, B is
  dictated and offered over A's file, B commits — and B's success cleared `unsettled` and
  `pendingDictation`, so A was gone from memory as well as from disk. Watched: two
  `DictationDurabilityTest` cases red, the end-to-end one with *"the dictation whose write failed is
  gone from disk once the next one landed"*.
- **Decision:** `persist` never overwrites a file holding a different id — a file on disk is by
  construction unsettled, because settling erases it. The old entry moves to
  `dictation.tsv.aside-<zero-padded nanos>`; `settle` erases an entry wherever it lives; `restore`
  publishes the main file and queues the asides oldest first, and each `claim` publishes the next,
  so the drains that already exist take them one at a time. `commitDictation`'s success clears
  `unsettled` and `pendingDictation` only when they are its own.
- **What it trades away:** in the session, A's banner survives B's success only while nothing
  replaces it — a failure of B's own write shows B's banner instead — but A stays on disk and is
  written on the next launch. The format of the file is unchanged, so a file from an older build
  restores as before.
- **Watched the other half:** replacing the aside rename with a log line turns both cases red.
- **Consequences / affects:** `app/src/main/kotlin/ai/passioncode/fabricvr/DictationOutbox.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/ui/NotesViewModel.kt`, `docs/modules/app.md`,
  `docs/evidence/backlog.md`. Closes `B-255`.
- **Source:** run `2026-09-23-loop` · `DEC-0094`

### DEC-0096 — What the second verification found under iterations 4–6

- **Date:** 2026-09-24
- **Status:** Accepted · **Refines DEC-0093, DEC-0095**
- **Context:** the loop's second pair of blind readings over `8808458..8a43a4e`. Every gate was green.
  The seam tier found that `DEC-0095` **leaked**: an entry restored from aside is `bind`-ed into the
  main file, and `erase` deleted the main file and returned — so the aside copy survived and the same
  dictation became a new note on every launch after. Watched: two `DictationDurabilityTest` cases
  red, *"the written dictation is still on disk and will be written again"*.
- **Decision — the outbox.** `erase` deletes an entry's main file **and every aside copy of it**.
  The next restored entry is published by `settle`, not by `claim`, so two commits never race on one
  main file and one view model's retry state. `waitingAudio()` names every waiting dictation's
  recording and the launch sweep spares all of them, not only the first's. The already-written
  branch clears only its own debt, as the success arm does (not planted: no in-session path reaches
  it without an artificial restore queue; it mirrors the tested arm).
- **Decision — the rest.** A cancel landing before a model download's headers is the cancel: the
  partial goes and nothing retries (`B-250`'s other door; red first). `FileVault.sameBytes` compares
  in 64 KiB blocks. An imported note's `updatedAt` is clamped to now, so a clock ahead of this
  headset's no longer makes the reconcile rewrite the file every launch (red first; one rewrite, then
  none). An **extra** in the export — the crash log — that fails mid-copy is a skip, not a failed
  backup: a truncated diagnostic is harmless and the importer refuses what is not a note (red first).
- **Consequences / affects:** `app/src/main/kotlin/ai/passioncode/fabricvr/DictationOutbox.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/Graph.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/ui/NotesViewModel.kt`,
  `feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/ModelDownloader.kt`,
  `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/Vault.kt`,
  `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/VaultImporter.kt`,
  `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/VaultExporter.kt`,
  `docs/modules/app.md`, `docs/modules/feature-vault.md`, `docs/ux/scenarios.md`,
  `docs/evidence/backlog.md`.
- **Source:** run `2026-09-23-loop`, second validation

### DEC-0097 — An export that cannot read a file names it, and a recording can be left out

- **Date:** 2026-09-24
- **Status:** Accepted · **Refines DEC-0093**
- **Context:** `B-258`, from the second validation's product tier. `DEC-0093` fails the whole export
  when a file fails once its bytes are flowing, which is right — and meant a `.wav` that fails the
  same way every time made *Notes and recordings* impossible, with a message that named nothing and
  no way round it on screen.
- **Decision:** the exporter's mid-copy failure carries the file's name and whether it is a
  recording (`AppError.ExportUnreadable`). A recording: *"Couldn't read the recording %1$s, so
  nothing was exported. You can export the notes without recordings."* with a new
  `UiAction.EXPORT_NOTES_ONLY`, which Settings answers with `exportVault(includeAudio = false)`. A
  note: named, and no partial export offered — a backup cannot leave a note out. The rule that no
  truncated archive is published is unchanged. Strings written without a brand pack (`B-035`).
- **Rejected:** skip-and-name a recording while still failing on a note. It keeps one export
  button working, but an archive that silently lacks a recording is the "looks complete and is
  not" backup `DEC-0093` exists to refuse.
- **Watched:** `VaultExportTest` both mid-copy cases red with the failure left untyped;
  `UiStateMapperTest` two cases name the file and offer — or withhold — the action.
- **Consequences / affects:** `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/VaultExporter.kt`,
  `core-common/src/main/kotlin/ai/passioncode/fabricvr/common/AppError.kt`,
  `core-common/src/main/kotlin/ai/passioncode/fabricvr/common/UiStateMapper.kt`,
  `core-common/src/main/kotlin/ai/passioncode/fabricvr/common/UiMessage.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsScreen.kt`, `docs/modules/feature-vault.md`,
  `docs/ux/scenarios.md`, `docs/evidence/backlog.md`. Closes `B-258`.
- **Source:** run `2026-09-24-loop` · commit `a6d7469`

### DEC-0098 — What the third verification found under iterations 8–10

- **Date:** 2026-09-24
- **Status:** Accepted · **Refines DEC-0097**
- **Context:** the loop's third pair of blind readings over `b0e0c7d..1252416`, every gate green.
  The product tier found that `DEC-0097` put a **file name** on screen — a note's UUID, which
  nothing on the headset can locate — against `DEC-0071`, and that `NoRawIdentifiersTest` did not
  know the new shape existed because its list is written by hand. The seam tier found that the
  mid-copy catch took the WRITE side too: a full disk read as *"a recording couldn't be read"*, and
  the offered notes-only export then failed the same way, blaming a note.
- **Decision — the export.** The sentence says what kind of file failed, never its name (the name
  goes to the log): *"One recording couldn't be read, so nothing was exported and nothing was
  removed. You can export the notes without recordings."* / *"One note file couldn't be read…"*.
  `ExportUnreadable` joins `identifierCarrying` with a UUID the rendered text must not contain. The
  copy is an explicit loop: only a failure of `read` is the file's fault; a failure of the sink stays
  `Storage("vault.export")`. Watched: four `core-common` cases red on the UUID, and a sink failing
  mid-copy (300 kB of incompressible bytes, so it fails during the copy and not at close) red.
- **Decision — the recordings.** `adoptOrKeep` answers null for a source that is gone, so the
  dictation commit, the editor's append and the recording-only note share `B-256`'s rule (red
  first; the test that encoded the dangling answer now proves the kept-and-present one). The
  editor's list names the recording the note points at even outside the vault's folder, dates an
  earlier recording by the stamp in its name (an archive restore resets file clocks), lets a newer
  listing win over an older one, and stops playback when a dictation starts (red first for the
  first two).
- **Resume corrections, 2026-09-25:** directory work completing after an empty refresh cannot
  restore the old list; the generation check is inside the state update. `DictationDurabilityTest`
  reproduces the empty-refresh defect and reverses two nonempty completions. `VaultAudioTest`
  reproduces restored clocks reversing the list and an absent source at its final vault path
  being accepted as already adopted; sorting uses the name stamp, and existence precedes that
  fast path. The editor stops playback before requesting dictation and disables every Play
  control while recording; `RecordingsListTest` checks disabled and re-enabled controls.
  These extend the existing contracts, with no per-file deletion or outbox redesign.
- **Consequences / affects:** `core-common/src/main/kotlin/ai/passioncode/fabricvr/common/UiStateMapper.kt`,
  `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/VaultExporter.kt`,
  `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/AudioAdoption.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/ui/EditorViewModel.kt`,
  `app/src/main/kotlin/ai/passioncode/fabricvr/ui/NoteEditorScreen.kt`, `docs/ux/scenarios.md`,
  `docs/modules/feature-vault.md`, `docs/evidence/backlog.md`, `docs/ux/screens.md`,
  `feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/Vault.kt`.
- **Source:** run `2026-09-24-loop`, third validation

### DEC-0099 — One branch: `main` is fast-forwarded without a hosted run, on local evidence

- **Date:** 2026-09-27
- **Status:** Accepted · **Closes B-195**
- **Context:** `main` (`0718a4c`) was a strict ancestor of `feat/v1-notes-core` (`c4d290e`), 158
  commits behind, and `B-195` held the fast-forward until one green hosted run was read. No hosted
  run can start: every job since 2026-09-23 failed in 1–5 s with the annotation *"The job was not
  started"* for an account-level reason, not a code one
  (run `35938821364`, read with `gh run view`). The operator asked for the repository
  to be consolidated into one branch and chose, when asked, local gates in place of the hosted run.
- **Decision.** The gates the hosted workflow would run were run locally on `c4d290e`'s tree, then
  `main` was fast-forwarded to it — no merge commit — and `feat/v1-notes-core` was deleted locally
  and on `origin`. Evidence, both exit 0: `bash scripts/check-all.sh --with-lint` → `ALL GATES
  GREEN` (the `gates` job plus `:app:lintDebug`), and `./gradlew --no-daemon :app:assembleRelease`
  → `BUILD SUCCESSFUL`, `app-release-unsigned.apk` 78 272 089 bytes (the `release` job). The
  release job's `if:` now names `main` alone, since the second branch it named no longer exists.
- **Traded away:** a hosted, clean-runner reading. Local runs share this machine's Gradle and NDK
  caches, so a defect that shows only on a cold cache (what the scheduled cold-dependency job
  exists for) is not ruled out. The first hosted run after the Actions budget is restored is the
  reading still owed; the device gate (`docs/evidence/device-gate.md`) is unchanged by this.
- **Also, same session:** build outputs (`*/build`, `feature-stt/.cxx`, `.gradle`, `.kotlin`) were
  deleted — the working copy went from 1.4 GB to 132 MB. `graphify-out/` was kept: graphify could not
  rebuild it at the time.
- **Consequences / affects:** `.github/workflows/ci.yml`, `README.md`, `docs/evidence/backlog.md`,
  `docs/handoff/2026-09-22-v3-entry.md`.
- **Source:** operator request, 2026-09-27 cleanup session

### DEC-0100 — The product is Fabric's remote surface on the Quest and the phone

- **Date:** 2026-09-29
- **Status:** Accepted · **Closes B-259**
- **Contradicts:** DEC-0064 — the clause that makes the product *Notion-first*, notes as its core.
  Its other clauses stand: the notes, the cross-device clipboard as a later layer, and the rule
  that the product never renders a desktop.
- **Context:** on 2026-09-28 the operator asked for fabric-vr to become Fabric's remote control
  in VR and on the phone: notes and voice notes, following Projects, updating the knowledge
  base, watching the Agents running in Fabric and commanding them, and dashboards. The proposal
  was written as `docs/product/2026-09-28-fabric-remote-concept.html` (`231fba1`). On 2026-09-29
  the operator asked for the decision to be recorded in the workspace so that agents know how
  VR, mobile and desktop connect. The cross-repository half is Fabric's ADR-0088 (`fabric` at
  `8d5efc3`, merged into its `main` as `9fb89fa`).
- **Decision.**
  - fabric-vr builds **the remote surfaces of Fabric**: the Quest first, and an Android phone
    from the same code. The Mac holds the Estate. These surfaces hold no copy of its journal.
  - They reach Fabric **only** through its northbound MCP, carried by one relay the Mac connects
    out to. They read projections and send typed commands with idempotency keys. A change shows
    only once Fabric's receipt arrives.
  - The modules are Attention, Projects, Agents and runs, Capture, Knowledge and Role workspaces.
  - What the app does today — dictation, whisper.cpp, the vault, export and import — becomes
    **Capture**. It keeps working with no connection.
  - The rules that bind this repository are ADR-0088's Decisions 2, 5, 6 and 7:
    - no path to Fabric except the northbound MCP;
    - remote bindings start below the Mac's effect ceiling;
    - capture is sent after review, from a durable outbox;
    - Fabric Dashboards stays a Mac host, seen here only through projections.
- **Open, and not decided here:** the store name, the relay host, the iOS order, and where the
  relay sits against Fabric's R0 acceptance. The one home for these is ADR-0088's *Open* table
  (O1–O4), not this register.
- **What it costs:**
  - Every remote feature waits on a relay and a northbound MCP server. Neither exists yet: in
    `fabric` both are architecture only. Until they do, the app is exactly what it was.
  - The product definition, the foundation's JTBD and the scenarios now describe a product this
    decision replaced. They are rewritten through `/ux` as `B-260`, not in this commit.
  - Brand alignment (`B-035`) and the 500 dp reflow (`B-223`) need nothing from Fabric and come
    first.
- **Consequences / affects:** `docs/product/product-definition.md` (banner: direction replaced,
  rewrite pending), `docs/product/2026-09-28-fabric-remote-concept.html` (status: accepted; the
  Fabric Dashboards statement corrected), `docs/evidence/backlog.md` (`B-259` closed, `B-260`
  opened), `docs/handoff/2026-09-22-v3-entry.md`.
- **Source:** operator, 2026-09-28 request and 2026-09-29 instruction to record it · Fabric ADR-0088

### DEC-0101 — A reference to the pre-publication history is NOT_CHECKED, and the list of them is closed

- **Date:** 2026-10-01
- **Status:** Accepted
- **Context:** on 2026-09-30 this repository was re-created as a public repository with one clean
  history. Its root is `2536a3d`, and its tree is identical to the last commit of the private
  history before it. That earlier history no longer exists anywhere: there is no bundle and no
  mirror. The dated records under `docs/evidence/` cite **472** commit references to **99** of its
  commits, and `check-docs.sh` §9 refused every one as "does not resolve". So `check-all.sh` was
  red on `main`, the pre-push hook refused every push, and `selftest.sh` failed six cases on a clean
  tree (47 passed, 6 failed, measured on a fresh clone of `2536a3d`):
  - three cases failed through the same red. These were the documentation gate's clean-tree control,
    the KDoc-line control of §23, and case 164. Case 164 runs `check-all.sh` and expects the
    verdict-floor message, and `check-docs.sh` stopped the run before any floor was read;
  - the three device-gate cases took the real root commit as their base. With a one-commit history
    that base was HEAD, so `check-device-gate.sh` exited at "no instrumented sources changed"
    before any check.
- **Decision.**
  - **A reference is repinned only when its claim is about a file's current content, and only after
    that claim is re-verified at the new commit.** Exactly one place qualifies:
    `docs/evidence/2026-09-25-round3.md` linked three source anchors at the old implementation
    commit. Each anchor was re-read at `2536a3d` and still opens on the function the link names, so
    the links now point there; the old URLs answer 404 and the new ones 200 (measured 2026-10-01).
    That document's own commit reference stays as written. It names the commit the work was done
    at, and the new root is not that commit.
  - **Every other reference is history.** It stays in place, because a dated record is not rewritten
    to name a commit it never described, and deleting it would make the gate pass by hiding the
    finding. Its commit is listed once in `docs/pre-publication-commits.txt`, spelled the way the
    documents spell it.
  - `check-docs.sh` §9 reports a reference to a listed commit as **NOT_CHECKED** on a line of its
    own, every run, with the count. It does not count that reference in its `ok:` line, which says
    how many references it actually followed. The section still refuses:
    - a reference to any unlisted commit that does not resolve or does not reach HEAD (unchanged);
    - a listed commit that is reachable from HEAD, which belongs to this history and can be checked;
    - any change to the set of listed commits. The digest of the entries is pinned in the gate, so
      the list cannot grow into the place every future dead reference would pass through. Changing
      it is a new decision, and the digest moves in the same change.
  - The unused `docgate:known-dead` marker is removed. It let a marker anywhere in the corpus exempt
    a commit, and the exempted reference passed without a word.
  - The device-gate self-test cases run against a **fixture history** built in the case's copy.
    That history has a base without `app/src/androidTest`, a commit that adds it, and a ledger row
    with the Result under test. Nothing then depends on which commits this repository happens to
    have. A fourth case covers the ledger as it is now: every row names a commit this clone lacks.
    `check-device-gate.sh` used to call that ledger one that "records nothing", which was false, and
    now names it as pre-publication history. The remedy is the same: a new row.
- **What it costs:** the 472 references can no longer be checked by anyone, and the gate says so
  rather than implying it did. `scripts/exposure.sh` still counts the ledger rows that name these
  commits as `unresolvable`. Its own definition says exactly this ("the commit does not resolve
  here"), so it is unchanged.
- **Consequences / affects:** `scripts/check-docs.sh` (§9), `scripts/check-device-gate.sh`,
  `scripts/selftest.sh`, `docs/pre-publication-commits.txt` (new),
  `docs/evidence/2026-09-25-round3.md` (three links repinned), `docs/DOCMAP.md` (register row,
  Verification row), `docs/handoff/2026-09-22-v3-entry.md`.
- **Source:** operator, 2026-10-01 instruction to make the gate honestly green on a fresh clone
  after the public re-creation of 2026-09-30 · branch `agent/pre-publication-gate`

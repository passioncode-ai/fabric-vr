# `:feature-stt`

Speech into text, on the device by default. Placement and fallback decided in `DEC-0003`;
the cleartext policy for a LAN server is `DEC-0005`; the native context's lifetime is `DEC-0007`.

## Owns

- **The JNI bridge** (`feature-stt/src/main/cpp/fabricvr_whisper.cpp`) over whisper.cpp v1.9.4, vendored as a
  submodule and built for `arm64-v8a`. **It exists because upstream's own Android sample hardcodes
  `params.language = "en"`**, which makes Russian dictation impossible; here the language arrives
  from Kotlin and `"auto"` is passed through as the value `whisper.h` documents for detection.
  **Its diagnostics now reach logcat** (`B-191`): `whisper_log_set` is installed once per process
  before the first load, because ggml's default sink is stderr and on Android that goes nowhere
  anybody can read — when the loader refused a model, the reason it printed was lost and Kotlin
  saw only a zero. **WARN and above only, and that bound is the privacy rule rather than a volume
  one:** the loader announces itself at INFO with the model's full path, and the store's root is
  a constructor argument — a directory a person's name can be in. The bridge's own refusal line
  logs the file name for the same reason. The callback stays a comparison and one
  `__android_log_write`, with no JNI in it, on whichever ggml thread it lands.

  **Every JNI pin is owned by a destructor since the `2026-09-22` audit, and nothing this file
  does is released by hand any more.** `GetFloatArrayElements` was released at exactly two call
  sites, and between the pin and both of them sit a `std::string::assign`, a `std::lock_guard` and
  `whisper_full` — three things that can throw in a library compiled with `-fexceptions`, while
  the `catch (...)` at the bottom of `transcribe` sits *outside* every one of those releases. So
  each such throw returned `nullptr` to Kotlin with the recording still pinned in the JVM heap,
  unmovable and uncollectable for the life of the process; on a ten-minute dictation that is
  megabytes. `PinnedFloats` and `ActiveRun` are the two guards, scoped to end exactly where the
  hand-written releases used to run, so the timing is unchanged and only the failure paths differ.

  **And `GetStringUTFChars(language)` is null-checked, which `initContext` already did and this
  path did not.** JNI returns null when the JVM cannot allocate the copy, and
  `std::string::assign(nullptr)` is `strlen(nullptr)` one call down — undefined behaviour inside
  the speech engine, with the microphone's output pinned beside it. An `OutOfMemoryError` is
  pending in the JVM either way; the bridge now returns and lets it surface as the failure it is.

  **Both were code reads until `NativeBridgeMemoryTest`, because nothing in this project could
  RUN this file.** `scripts/check-native.sh` compiles it with `-fsyntax-only` against the NDK's
  headers — a pin that leaks on a throw compiles perfectly — and `WhisperNativeCalls` exists
  precisely because the native call cannot be made from a JVM test, so the fake on the other side
  of that seam is not the bridge. The test compiles the real file on the host against
  `src/test/cpp/shim` (a JNI surface narrow enough that a new JNI call fails to compile rather
  than passing against a fiction) and `bridge_harness.cpp` (fakes for whisper, plus the pin
  counters), provokes each path and compares pins against releases. Watched failing 2026-09-22:
  the throw prints `pinned 1, released 0`, and the null language does not fail a check at all —
  **the process dies with a signal**, which is why the harness runs unbuffered and the driver
  reads the exit code. The bridge's sources are declared as inputs of the module's `Test` tasks
  in `feature-stt/build.gradle.kts`; without that, editing the C++ leaves `testDebugUnitTest`
  `UP-TO-DATE` and the whole thing does not run, which is `B-204` exactly.
- **`PcmBuffer`** — the recording, as a growable `ShortArray` with a cap. It was an
  `ArrayList<Short>`: about twenty bytes of Java object per two bytes of audio, so a minute
  retained ~19 MB and churned the same again, and `addAll` reallocated and copied up to 960 000
  references **inside the lock the main thread waited on** to end the recording (`E-02`, `I-10` —
  one defect from two sides). The lock stays; only the size of its critical section changed.
  Bounded at `VoiceViewModel.MAX_SECONDS`, and `DEC-0032` explains why the number comes from
  transcription time rather than memory.
- **`ModelDownloads`** — every model transfer in the process, one per model, outliving the screen
  that began it (`DEC-0033`). Two view models each held their own `Job` into the same `.part`
  file, each digesting only its own bytes, so both failed the pinned digest and **the model could
  never finish** (`I-05`). A second *Download* now joins the first. Keyed by model, so choosing a
  different one no longer cancels — and no longer deletes — a running transfer, and the download
  surviving its collector is what makes `ModelDownloader`'s resume path reachable at all (`A-22`:
  the `Range` header and the digest replay had never run).
  **A `start` arriving during a cancel now waits for it** (`M15`). `cancel` removed the map entry
  under the lock and joined *outside* it, and a cancelled transfer stops only when its blocking
  read or write returns — so in that window a second `start` found an empty map and opened a
  second writer on the same `.part`, which the dying one then deleted underneath it. The new
  download's bytes went to an unlinked inode, its `renameTo` failed, and the person was told
  *"There isn't room on this headset"* on a headset with plenty. The entry is now **marked**
  rather than removed, and `start` joins the dying job with the lock released.
- **`PcmSource`** — the microphone behind an interface, so the read loop can be driven by a test.
  It exists for `C-05`: a negative `read` (`ERROR_DEAD_OBJECT`, `ERROR_INVALID_OPERATION`) used to
  fall through `if (read > 0)` straight back into `read()` — one core at 100%, the meter frozen,
  the interface still saying *Recording*, and "nothing was heard" when the person stopped. The
  loop now ends the flow with the code in the message, retrying only the transient one and only
  `MAX_TRANSIENT_READS` times. `AudioRecord` is final and Robolectric's shadow never fails, so
  without this seam the fix would have been a claim.
- **`LocalWhisperOwner`** — the only thing in the process that constructs or frees a
  `WhisperEngine`, and therefore the only thing that decides when a native context exists
  (`DEC-0030`). Every use is `suspend fun use(model, onWait, block)`: it takes a `Mutex`, decides
  *inside* it whether the loaded context matches the model asked for, and lets no engine reference
  escape. **At most one context lives at a time** — 574 MB for `large-turbo`, on a device with no
  swap — and `LocalWhisperOwnerTest` asserts that ceiling across fifty interleaved uses rather than
  documenting it. `Graph` reaches it through `withStt { }` and `withEngine(provider, model) { }`;
  nothing else may.
- **`WhisperEngine`** — serialises every call onto one thread, because whisper.cpp forbids
  concurrent access to a context. Converts `ShortArray / 32768f` to the float PCM it expects.
  **Cancelling the coroutine stops the running transcription** (`DEC-0060`): the bridge carries
  `abort_callback`, the engine turns cancellation into it through `invokeOnCancellation`, and the
  blocking call is submitted to the engine's own executor so the caller is never the thread being
  stopped. A run stopped because the **engine** went away — a model switch closing it mid-run — is
  reported the same way (`DEC-0062`), because telling the person their dictation failed when they
  changed the model is `I-26`. A cancelled run is reported as an ordinary `CancellationException`
  and never as a failure — `whisper_full` returns the same non-zero for both, and the difference is the whole of
  what the person is told. `progressPercent()` reads 0..100 from whisper's own
  `progress_callback`; nothing draws it yet (`B-178`).
  **Every native call about a run is now made on the engine's own thread** (audit `2026-09-22`).
  Three sat on the caller's, and one of them was a use-after-free waiting for a model switch:
  `detectedLanguage` is the only query that dereferences the `whisper_context` itself
  (`whisper_full_lang_id(ctx)`), and it was asked from a handle captured before the guard with
  nothing ordering it against the `close()` that frees that context on the engine thread.
  `beginRun` is the bridge's one *inserting* query — `run_state_for` — so a stale handle
  **resurrects** a map node keyed by a dead address, which is the leak `run_state_of` was
  extracted to prevent, reached through the one door that still used the inserting form.
  `wasCancelled` reads the run map and never the context, so it was already safe; it moved with
  the other two rather than leaving a reader to work out which of two adjacent native calls is
  the dangerous one. **No new dispatch was added:** `beginRun` moved into the guard's existing
  `withContext(dispatcher)`, and the other two into the executor task that already runs the
  blocking call, so the deadlock the class's own header records — `withContext` plus
  `invokeOnCompletion` — is not reintroduced. `B-181`'s invariant is easier to see, not weaker:
  the run is still named before `invokeOnCancellation` is registered, which is the only thing
  that can ask for a cancel.
- **`WhisperNativeCalls`** — the JNI surface behind an interface, so the rules above it can be
  tested. (This passage was cited at `:68-72` while it lived at `:148-152`; `REQ-039` corrected
  both ranges by appending rather than editing, and `check-docs.sh` §21 now decides the arithmetic
  half — `DEC-0057`.) `WhisperNative` is an `object` of `external fun`s that throw `UnsatisfiedLinkError` off
  a device, so every contract the engine enforces around them was unreachable by any JVM test.
  **Every call about a run now names the run** (`DEC-0080`, `B-181`): `beginRun(ptr)` issues a monotonic id
  per context, and `transcribe`, `cancel` and `wasCancelled` all carry it. It replaced a single
  per-context flag that `transcribe` cleared as its first act — a rule that existed to stop a
  cancel landing *between* two runs from aborting the next one, and that paid for it by wiping a
  cancel landing in the window between `invokeOnCancellation` being registered and the native
  entry. That cancel set a flag the run immediately erased, `whisper_full` went to completion with
  nobody waiting for it, and the next `close()` — a model change — queued behind a burned engine
  thread: `B-118` through a door `B-118` did not close. Naming the run keeps the old property **by
  construction** (a flag set for run N is not run N+1's to clear) and stops losing the other case,
  so the clearing rule is gone. Kotlin allocates the id *before* registering the cancellation
  handler, which is the ordering the whole fix rests on.
- **`FabricHttp` / `NetworkPolicyInterceptor`** (`DEC-0077`, `B-197`) — **they live in `:core-common` now**,
  beside `NetworkPolicy` itself. `DEC-0005` applied to **every hop of a call**,
  not only to the address the person typed (`B-187`). `NetworkPolicy.requireReachable` guarded
  Settings; OkHttp then followed redirects by default and the manifest permits cleartext, so a
  `307` from a configured `https` endpoint moved the WAV — and the cloud transcription key — to
  whatever host the server named, in the clear, with nothing checking where it landed. It is a
  **network** interceptor, not an application one, and that is the whole fix: an application
  interceptor runs once per *call*, above `RetryAndFollowUpInterceptor`, so it never sees the
  redirect it would have to refuse. A network interceptor runs once per *hop*, above
  `CallServerInterceptor` — the thing that writes the request line, the headers and the body — so
  a refusal means no byte of the recording and no byte of the key reaches that socket.
  **What it does not prevent, because network interceptors run after `ConnectInterceptor`:** the
  redirect host is resolved and connected to before the refusal, so a malicious server still
  learns the device followed it that far. Closing that means turning `followRedirects` off and
  re-implementing OkHttp's redirect semantics by hand in a security control, which is a worse
  trade than the TCP handshake it saves. **`Authorization` is already dropped across hosts** by
  OkHttp itself — verified in `RedirectPolicyTest` rather than assumed, since a rule nobody checks
  is exactly what this row is about — and it says nothing about the recording, which a 307 re-sends
  in full. `FabricHttp` exists because three classes here build a client each for reasons about
  timeouts (`I-28`), a fourth would be written the same way and would forget this, and
  `FabricHttpTest` turns forgetting into a red test rather than a recording in the clear.
  **A fourth WAS written that way, and the factory being `internal` to this module is how**
  (`B-197`): `OpenRouterClient` in `:feature-assistant` could not import it, built its own client
  and followed redirects unchecked. The factory moved to `:core-common` (`DEC-0079`) — where the policy it
  applies already lives — and `FabricHttpSourceTest` there scans every module's sources for an
  `OkHttpClient` construction outside it, which is the question a per-module wiring test could
  not ask. `FabricHttpTest` stays here and still holds this module's three real clients: the
  source scan says *are there only these*, the wiring test says *are these guarded*.
- **`ModelIntegrity`** (`DEC-0078`) — whether the model on disk is still the file whose digest was pinned
  (`B-191`). `ModelStore.isPresent` compares the byte **count** and nothing re-verified the SHA
  after the download, so a file corrupted afterwards was *present* for ever: `initContext`
  returned 0, every dictation failed identically, nothing evicted it, and the only way out was
  Settings → *Remove speech model* — a step the person had no reason to suspect, under a message
  that told them their dictation had failed. The digest is taken **on the first loader failure**
  and cached per process, keyed by path **plus length plus modification time** so a re-download
  is judged again. A file that fails is deleted, so the next attempt offers a clean download; a
  file that passes is kept and the failure is reported as the engine's, because blaming a correct
  190–574 MB download sends the person to fetch it again for nothing. **Hashing at first use
  instead would put seconds of SHA-256 in front of the first dictation of every process**, on the
  path where the person is holding the trigger, to find something true of one run in thousands —
  and hashing per dictation was never on the table. The price of that choice is stated rather than
  hidden: a model wrong in a way that makes ggml `abort()` kills the process, and nothing that
  runs after the loader returns can intervene.
- **`CloudTranscriptionClient`** — **the recording leaves the device here, and nowhere else.**
  `POST {base}/v1/audio/transcriptions`, the OpenAI-compatible route, so Groq, OpenAI and a
  self-hosted faster-whisper are one client and three settings (`DEC-0013`). Its constructor
  refuses an endpoint `NetworkPolicy` will not allow — which permits plain `http` to any
  private address, so the audio and the key may travel in the clear on a LAN the person trusts.
  Since `B-187` **every redirect that endpoint answers with is checked by the same rule**, through
  the network interceptor above; the constructor's check alone had been a one-time gate on a
  journey that could continue anywhere.
  **This class had no entry in this file for eleven commits**, against `DOCMAP.md`'s propagation
  rule, and it is the single most privacy-relevant one in the repository. It is listed first for
  that reason.
  **Dismissing the sheet now stops the upload** (`H9`, and the same fix in `RemoteWhisperClient`).
  Both clients registered `call.cancel()` through `job.invokeOnCompletion`, which fires when the
  job *completes* — and a job parked in OkHttp's synchronous `execute()` completes when the call
  returns, so the handler cancelled the call at the moment it was ending anyway. The recording
  kept being uploaded for up to the call timeout, 120 s here and 60 s there, holding
  `LocalWhisperOwner`'s mutex the whole time so the person's next dictation queued behind a
  transcription nobody wanted. `Call.await()` (`CallAwait.kt`) is `enqueue` plus
  `invokeOnCancellation`, one implementation for both clients, because two copies of a
  cancellation contract is how one of them silently stops being cancellable.
- **`SttProvider`** — the three answers to *where does speech go*: `LOCAL` (nothing leaves the
  headset), `CLOUD` (a third party receives the recording), `SERVER` (a whisper.cpp server,
  typically the person's own machine). It is an explicit choice, never inferred from a URL being
  set (`DEC-0014`), and a chosen-but-unconfigured provider refuses out loud (`DEC-0024`).
- **`WhisperModel`** — the catalogue: five models, each with a name, a byte size and a **pinned
  SHA-256** taken from Hugging Face's own API (`DEC-0015`). Its table is `WhisperModel.kt:21-25`
  — the file is 38 lines long, so the `:104-114` this document carried could never have resolved; only
  `small` has ever been downloaded by this project, so four of the five digests have never been
  exercised against the network (`B-173`, `REQ-032`).
- **`ModelStore` / `ModelDownloader`** — the transfer, with progress, a digest and a size floor.
  **Cancel reaches a blocking read** (`B-250`): each attempt runs inside `cancellingCallOnCancel`,
  whose child coroutine cancels the HTTP call the moment the transfer's job is cancelled, so a
  stalled connection no longer holds *Cancel* for the 90 s read timeout; a read broken by that
  cancel is handled as the cancel (the partial goes), not as a network retry — before the headers
  as well as during the body (`DEC-0096`).
  A **corrupt** file is deleted, never left pretending; an **interrupted** one is kept, and that
  is the difference `H8` is about. `partial.delete()` used to run for every non-cancellation
  throwable alike, so a Wi-Fi hiccup at 560 of 574 MB cost all 574 again and the resume machinery
  this class already carried had never run (`A-22`). Now one `download()` is up to three attempts
  on the same `.part` — waits of one second then two — and the file survives between them, so the
  `Range` header, the 206-versus-200 check and the digest replayed over the bytes on disk are
  exercised by every dropped connection rather than only by a process death. A `416` is the
  server saying those bytes are not a prefix of what it is serving, so they go and the transfer
  restarts from zero inside the same `download()`. **Cancellation still deletes** — the person
  asked for the transfer to stop existing — and so do a failed digest, a wrong size and a refused
  redirect, because bytes that are wrong or came from the wrong host must never become the head
  start of the next attempt. `DownloadProgress.Failed` carries `resumable`, computed from the
  file rather than asserted; it lives there and not in `AppError.ModelDownload` because the same
  `Reason.NETWORK` is resumable after a dropped connection and not after a refused redirect.
  **A full disk is not retried**: three more attempts are seven seconds of waiting for the same
  answer.
  **The retry lives here rather than in `ModelDownloads`**, which was the other candidate because
  it owns the job. Everything a retry must not lose is in this class — the `.part`, the offset the
  next `Range` is computed from, the digest replay — and a retry above would have to publish a
  `Failed` to the shared `StateFlow` and then take it back, because `Failed` is a terminal value
  every screen renders. Here a transfer that recovers is one the person never sees fail.
  **The default is
  `ggml-small-q5_1.bin`, 190 MB**, but the person picks from the catalogue above and the range is
  32 MB (`tiny`) to **574 MB** (`large-turbo`) — a material fact about a headset whose thermal
  ceiling this same file measures at `:148-152`, and about a download that fails its digest at the
  end rather than the start.
- **`RemoteWhisperClient`** — whisper.cpp's own server, `POST /inference`, multipart. There is no
  OpenAI-compatible transcription route in that server; this speaks the documented form.
  **It sends `language`** — the hint, or `auto` when there is none (`H7`). whisper-server
  initialises `params.language` to `"en"` (`third_party/whisper.cpp/examples/server/server.cpp:113`)
  and overwrites it only from that field (`:558-560`), so omitting it was a decision to decode
  English: a Russian dictation came back as English phonetics *labelled `ru`*, because the
  transcript took its language from the hint the server never saw. The opposite of
  `CloudTranscriptionClient`, which must **omit** the field, since an OpenAI-compatible provider
  reads `auto` as a language named auto.
- **`RemoteTranscriptBody` and `RemoteDeadline`** — the two rules both remote clients share
  (`DEC-0091`). A 2xx body is read whole up to 4 MiB and is a transcript only when `text` is a JSON
  string; anything else is `AppError.RemoteSttUnreadable`, never the raw body (`B-244` — a portal's
  HTML used to become the note, and a long transcript was cut at the 64 KiB error-body ceiling).
  Each call's deadline is max(60 s, 30 s + 2 × audio length), set on the `Call` (`B-243` — a fixed
  60 s / 120 s made every long dictation fall back). **And both clients set `readTimeout(0)`**
  (`DEC-0094`): OkHttp's default 10 s read timeout fired inside every longer decode, because
  whisper-server answers only after decoding — the deadline alone changed nothing.
- **`SttRouter`** — tries the chosen remote, falls back to the device, and marks the transcript
  `LOCAL_FALLBACK` so the degradation is visible rather than silent. Since `DEC-0024` it is always
  *given* a remote for a provider the person chose: an unconfigured one is a `FailingEngine`, which
  is the input this class was designed for. A `null` remote now means only `SttProvider.LOCAL`.
  **And when the fallback fails too, the REMOTE's reason is what the person is told** (audit
  `M13`, open since 2026-09-21). `Result.map` does not touch a failure, so the local engine's
  answer used to replace the remote's silently — and the local engine's commonest answer is
  `ModelMissing`, which offers a **Download**. Somebody whose cloud key had expired was therefore
  told the speech model was not on the headset and invited to commit to 190–574 MB that would not
  have fixed anything, while the 401 they could have acted on was dropped on the floor. `REQ-058`
  closed this for the `local == null` case and left the branch a person actually reaches. The
  fallback's own failure is logged (`stt.fallback.failed`) rather than shown — it answers a
  question nobody asked — and it is still reported when the remote had no classified reason to
  give, because saying nothing would be worse than either. A fallback that was **cancelled** is
  rethrown untouched: the person's own stop is not the remote's error.
- **`FailingEngine`** — an engine that refuses with a reason, so "chosen but not configured" is a
  failure the router can render rather than a `null` that disappears into a silent local run.
- **`AudioRecorder`** — `VOICE_RECOGNITION`, 16 kHz, mono, PCM16 — the rate whisper.cpp expects.
  **It says when the OS has muted the microphone** (`H11`). *Meta Horizon OS Audio* documents the
  contract: when the system or another app takes the microphone, "the microphone stream isn't
  closed and is provided empty audio data", and the two ways to notice are
  `AudioRecordingConfiguration.isClientSilenced()` and `AudioManager.isMicrophoneMute()` — both
  API 29, against this module's `minSdk` of 34. Nothing looked, so a person could hold the button
  for ten minutes while Meta Virtual Display held the microphone, watch a meter at zero, and be
  told **"Nothing heard"** — the app's word for *you did not speak* — after sending ten minutes of
  zeroes to whisper. `Recorder.silenced` is a `StateFlow<Boolean>` beside the levels rather than
  instead of them, because silencing is a **state and not an error**: the recording keeps running,
  the microphone can come back, and the signal falls again when it does. Cleared at the start and
  the end of every recording, so it never describes one that is over. Both questions are asked
  per delivered chunk (about four times a second) rather than through
  `registerAudioRecordingCallback`, which would buy a quarter of a second at the cost of a binder
  thread, an executor and an unregistration on every exit from a loop that is already ticking.
  The interface member has a default answering `false` for ever — the honest answer for a
  `Recorder` that cannot ask, which is what a view-model fake is.
- **`WavWriter`** — RIFF/WAVE for the attachment and for the server, and **the disk path does not
  hold the recording four times over to save it** (`B-202`, audit `M17`). It did: a
  `ByteArrayOutputStream`, a separate body `ByteBuffer` and the `toByteArray` copy were three
  full-length arrays alive at the same instant, on top of the caller's own `ShortArray` — for a
  ten-minute dictation (19.2 MB of PCM) that is ~77 MB live at the moment of the write, on a
  headset with no `largeHeap`. `writeTo(OutputStream, ShortArray, Int)` streams the 44-byte header
  and then the samples in buffers of `CHUNK_BYTES` (32 KiB), so the allocation is a function of
  the buffer and not of how long somebody spoke; measured through the `openSink` seam, the largest
  single buffer handed to the filesystem fell from **5 000 044 bytes to 32 768** for a 5 MB
  recording, and stayed 32 768 when the recording was quadrupled.
  **`toWav(ShortArray, Int): ByteArray` stays**, because both HTTP clients post the recording as a
  multipart body and OkHttp wants the bytes — it is one allocation now instead of three, and
  `WavWriterStreamTest` compares the streamed file against it byte for byte so the two producers
  cannot become two formats. **What it trades:** the encode now runs while the file handle is
  open rather than before it, so a failure arrives mid-file; `write` deletes the partial and
  rethrows, because `VoiceViewModel` answers a failure with *the recording was not kept* and that
  sentence must not sit beside a playable-looking file. `readPcm` is unchanged and still reads a
  whole `ByteArray` — its caller is `NotesViewModel.retranscribe`, which needs every sample at
  once to hand to whisper; the round trip is proven over five million samples through a real file.
  `openSink` is a test seam and nothing in production passes it, the shape `ModelDownloader` uses.
- **`InstalledModels`** (`B-093`) — **what is on disk, and a way to get any of it back.**
  `FileModelStore` puts all five models in one directory so that switching between them keeps
  whatever is already downloaded — right, and the whole of this row: the only removal the app had
  resolved *the currently selected* store and deleted that one file, so a person who tried
  `large-turbo`, disliked the speed and went back to `small` was left with 574 MB that nothing
  listed and nothing could reclaim short of uninstalling. All five together are 1.395 GB.
  `list()` returns one row per catalogued model with the bytes it occupies **now** and whether it
  is complete; `totalBytes()` is the storage line; `remove(model)` frees any model and returns what
  it reclaimed; `removeOthers(keep)` is *Free up space* in one press. **`.part` files are counted
  and removed with their model** — a cancelled transfer deletes its partial, an interrupted one
  keeps it deliberately (`H8`), and those bytes are as real as a finished model's. It enumerates
  `WhisperModel.entries`, never the directory, so a file this app did not download is neither
  counted nor deleted. Nothing here is `suspend`: these are `length()` and `delete()` on a handful
  of paths, and `DEC-0031` puts the dispatcher in the layer that blocks, which is `:app`.
  **It does not stop a running download** — `ModelDownloads` owns that, and deleting under a live
  writer is `M15` from the other side, so a caller offering removal during a transfer cancels first.

## Checks

**162 JVM tests in `:feature-stt`**, over 28 classes, counted from the JUnit XML a run of
`./gradlew :feature-stt:testDebugUnitTest` leaves behind — a build output, so it is named here as
a procedure rather than as a path: `check-docs.sh` resolves every path a document writes, and a
generated one resolves only on a machine that has just built. The number is recomputed by `check-docs.sh` §17 (`DEC-0053`). It said nineteen over four classes
until the group verification of steps 4–5 counted them; six classes arrived in that group alone, and the
`G-C` run added three more.

`ModelDownloaderSpaceTest` (9 — the free-space refusal before the first byte, every arm of the
`ENOSPC` classifier including the two `G-06` got wrong, and a resume that only needs the bytes it
lacks, **pinned to a real digest**: that one asserted `!is Failed` against a blank
`expectedSha256` until `REQ-050`, so the one thing a resume must get right was the one thing it
did not check), `ModelDownloaderCancelTest` (2 — `B-250`: cancelling a stalled transfer returns in seconds, and `DEC-0096`: a cancel before the headers deletes the partial and does not retry; real server, real time), `ModelDownloaderTest` (8: good download, wrong digest, truncated body, server
error, two redirects refused and one followed), `SttRouterTest` (9: local only, healthy server,
failing server, hanging server, server with no local engine, nothing configured, a refusing remote
degrading visibly, and `M13`'s pair — a remote 401 surviving a fallback that also fails, with the
control that an unclassified remote leaves the fallback's own reason in place), `PcmBufferTest` (7), `LocalWhisperOwnerTest` (7 — the one-context ceiling
across fifty interleaved uses), `ModelDownloadsTest` (7 — two starts one transfer, and `M15`: a
`start` arriving during a cancel waits for the writer instead of opening a second one on the same
`.part`. **That case has no wall clock in it since `B-186`**: it asserted the sink count after a
`realTime { delay(500) }`, lost the race once in a full multi-module run and never reproduced in
nine more. What replaced the sleep is a handshake against a latch the test holds — the dying writer
is parked inside a blocking `write`, so a correct `start` *cannot* reach the server at all, and the
remaining bound is on how long the test waits for a request a correct implementation can never
send. A loaded machine makes that wait longer and the verdict identical, which is the property the
sleep did not have. `maxAttempts = 1` there is load-bearing: the downloader's own retry opens a
second sink by design, so without it a stalled first attempt could produce exactly the red that was
seen. Two control assertions close it — the second transfer must really happen — because "nothing
happened" is what a flaky sleep looks like), `ModelDownloaderResumeTest` (6 — the whole of `H8`: a dropped connection keeps the
partial and says it is resumable, the next attempt sends `Range: bytes=N-` and the finished file
still hashes to the pinned digest, a `416` drops the stale bytes and restarts, the budget is three
attempts with waits of one and two seconds, a recovered transfer never shows a failure, and a
**wrong digest is still deleted and still not resumable**), `AudioRecorderSilenceTest` (6 — `H11`:
a silenced microphone raises its own signal, an ordinary one never does, the signal falls again
when the microphone returns, silencing does not fail the flow, and it is cleared before and after
each recording), `CloudTranscriptionClientTest` (6), `WhisperEngineCancellationTest` (8 — the
whole evidence for `DEC-0060`: cancelling the job reaches the blocking call, a cancelled run is
reported as cancellation and not as a failure, an ordinary run leaves no handler behind, a native
failure that was not cancelled still fails, a transcription arriving after `close()` never reaches
the native call, and progress reading zero before a run and the native value during one — **plus
the two `B-181` cases**: a cancel arriving between the registration and the native entry is not
lost, and a cancel that lands *between* two runs does not abort the next one. Those two are a pair
on purpose; either alone can be passed by a change that breaks the other, and breaking the second
is `I-26`. The fake for them models the bridge run by run and parks at the entry point on demand,
because the window cannot be provoked by timing — 0 of 40 attempts did),
`ModelIntegrityTest` (7 — `B-191`: a file of the pinned **size** with the wrong bytes detected and
removed, a good file hashed **once** however often it is asked, a file replaced at the same path
hashed again, and the three things this class refuses to convict — no pinned digest, no file,
bytes it could not read — plus the streaming digest matching a one-shot hash),
`RemoteWhisperClientUrlTest` (5: the `DEC-0005` cleartext rule at the client's own door),
`RedirectPolicyTest` (10 — `B-187` against the real OkHttp redirect machinery, with every host
resolved to loopback so "somewhere else" is a name rather than a network: the recording never
reaching a `307` target off the private network, a `307` **into** it still followed, the
`Authorization` header verified to be dropped across hosts, and a model download refused on the
first attempt rather than retried three times — **plus the `B-198` pair over a real TLS handshake**:
a `307` from a verified `https` origin to a cleartext public host never receiving the recording,
and the control that proves the same client and certificate complete an ordinary call.
**And `B-216`'s four, which are the case none of the above could see**: the certificate now
carries two names, so a `307` from a verified `https` origin to a **second verified `https` host**
is a hop nothing about the transport can refuse — the recording does not reach it, nor does the
cloud client's, while a redirect to another **path** on the same host is followed and a
credential-free model download still follows its vendor's cross-host CDN redirect), `FabricHttpTest` (10 — the interceptor alone, hop
by hop: the `https → http` downgrade refused with `proceed` never reached, the refusal naming the
host and carrying no path or query, seven private and https hops made, **every client this
module ships** asserted to carry the rule, and `B-216`'s five — an `https` cross-host hop refused
for a body and again for a bare `Authorization` header, allowed for a request carrying neither,
allowed between two hosts on the person's own network, and refused on the way out of it),
`NativeBridgeMemoryTest` (1, and it compiles and runs the real `fabricvr_whisper.cpp` on the host — the JNI bridge entry under *Owns* says what it counts and what it cannot say), `WhisperEngineModelIntegrityTest` (4 — `B-191` as the
person meets it: a corrupt file removed and reported as a **missing** model so *Download* is
offered, a loader failure on a good file reported as the engine's, a successful load never
hashing anything, and three failures in one process hashing once),
`RemoteTranscriptShapeTest` (5 — `DEC-0091`: a portal page, a JSON with no `text` and a non-string `text` are refused on both clients, a 100 kB transcript arrives whole, and an empty one is still a transcript), `RemoteCallTimeoutTest` (4 — the deadline grows past a ten-minute dictation on both clients and keeps its 60 s floor; `DEC-0094`: neither client's read timeout undercuts it, and one real-time case in which the server holds its headers for 11 s), `AudioRecorderErrorTest` (5 — the fifth is `B-248`'s: a source that throws on start is released; the `C-05` spin, under a **real** timeout, because the defect's
signature is not finishing), `RemoteCallCancellationTest` (3 — `H9`, for both remote clients,
asserting the **clock**: `isCanceled()` alone cannot discriminate, because the handler this
replaced did cancel the call, two minutes late), `RemoteWhisperClientFormTest` (3 — `H7`: the
`language` field, pinned and `auto`), `WhisperEngineNameTest` (3), `WavWriterTest` (2),
`WavWriterStreamTest` (7 — `B-202`: the measurement shown catching a single whole-file write
before it is trusted, a 5 MB recording reaching the sink only in bounded buffers, the largest
buffer unchanged when the recording is quadrupled, the streamed file equal to `toWav` byte for
byte, five million samples surviving the trip through a real file, an empty recording as a bare
header, and a failed write leaving nothing on disk), `CrossModuleTestInputsTest` (4 — `B-204`:
the marker scan shown seeing each of its seven escape idioms and staying quiet on a test that
reads nothing outside itself, the registry parser reading a well-formed entry and refusing a
malformed one, and the rule itself over every module — a test that reads outside its module with
no entry in its build file, an entry whose `declared` verdict declares nothing, and an entry
naming a test that no longer escapes) and
`InstalledModelsTest` (12 — `B-093`: an empty store and a store that was never created, every
downloaded model listed in the catalogue's order with its size, an interrupted transfer and a
truncated file listed as **incomplete**, a model with both a file and a leftover `.part` counted
once, removing a model that is not the selected one, removal taking the partial with it, removing
what is not there, `removeOthers` keeping what it is told to and clearing the store when told
nothing, and a file this app did not download left alone).

**`ModelDigestReachabilityTest` (2) is opt-in and reports *skipped* rather than green** (`DEC-0081`) — it is
`B-173`'s answer and the reason it is not in the gate is in its own header. Four of the five pinned
SHA-256 digests had never been exercised against anything: a typo in `tiny`, `base`, `medium` or
`large-turbo` is indistinguishable from a correct pin until a person picks that model, and then it
fails at the **end** of a transfer of up to 574 MB as *"The downloaded file was corrupt and was
removed."* Hashing all five means downloading 1.395 GB, so it asks two cheaper questions instead:
one request to Hugging Face's tree API — the endpoint `WhisperModel`'s own header names as the
source of these values — returns `lfs.oid`, which *is* the SHA-256, for all five at once; and one
`Range: bytes=0-0` request per model proves the file is reachable and that the `Content-Range`
total is the pinned length. **Five bytes of payload for the reachability of 1.395 GB**, plus about
ten kilobytes of JSON. It also exercises something nothing else does: that the vendor's CDN honours
`Range` at all, which is the premise of `ModelDownloader`'s entire resume path (`H8`, `A-22`) and
has until now only ever been asked of a `MockWebServer` this project configured to say yes. What it
does **not** prove is that the served bytes hash to the published digest — that needs the 1.395 GB,
and `ModelDownloader` checks it for free on every real download. Run it with:

```bash
FABRICVR_NETWORK_TESTS=1 ./gradlew :feature-stt:testDebugUnitTest \
  --tests '*ModelDigestReachabilityTest*'
```

Without that variable both cases report **skipped**, which is visible in the report — not green,
which is what a silently disabled check looks like. Measured 2026-09-22: all five published digests
and all five published lengths equal the pinned values, and all five answered `206`.

**Decoding is whisper's published configuration, not its fastest one.** Beam search with a width of
five rather than greedy sampling, because a dictation is a short utterance with no surrounding
context for a wrong word to be corrected by, and that is exactly where the two differ. Non-speech
token suppression is on, so room noise stops arriving as "(музыка)" or "[BLANK_AUDIO]" for a person
to delete. The temperature ladder is explicit — decode at zero, retry warmer when the result looks
degenerate by entropy or average log probability — so one bad decode of a short phrase is not final.
`no_context` stays on: each dictation is its own utterance, and carrying the previous one is how
whisper starts completing a sentence nobody said. The beam width is a parameter of `WhisperEngine`,
so a future *speed* setting is one argument rather than a rewrite.

The engine itself is proven on the device, not in JVM tests: there is no first-party whisper.cpp
Android benchmark to cite, so the latency is measured. `WhisperEngineTest` (4 instrumented) reads
WAV fixtures from `/data/local/tmp/fabricvr-test-models`; three of the four **skip loudly** with
`assumeTrue` when the 190 MB model is not on the device — an assumption failure reads differently
from a pass, which is the point. Push it with `scripts/push-model-for-tests.sh`. The two clips in `feature-stt/src/androidTest/assets/fixtures/` are `jfk.wav`, copied from `third_party/whisper.cpp/samples/jfk.wav`, and `ru_short.wav`, a Russian line synthesised with a macOS text-to-speech voice: neither is anyone's own recording.

**The redirect guard is named by the store, not derived from the URL.** `resolve/main` is answered
with a 302 to `us.aws.cdn.hf.co`, which shares no registrable domain with `huggingface.co`, so a
rule derived from the download URL refused the vendor's own CDN and no model could be downloaded at
all — measured on a Quest 3 on 2026-09-19, the failure the operator reported. `ModelStore`
declares `allowedRedirectHosts` (`{huggingface.co, hf.co}` for the shipped store) and matching is on
the registrable suffix, so one entry covers a CDN's subdomains. The guard is defence in depth and no
longer the only line: `expectedSha256` is pinned to the vendor-published digest, so bytes arriving
from anywhere must still hash to it.

The mocked tests prove the rule the code implements; they cannot prove the rule still matches the
vendor, because each one redirects to a server the test itself started. `ModelDownloadReachabilityTest`
in `:app` covers that gap — it starts the real download from the device and stops at the first
bytes, costing about a megabyte.

**Since `B-187` the store's allow-list is the second of two checks, not the only one.** The hop
rule refuses a redirect that leaves the private network in cleartext before the request is
written; the allow-list then asks whether the host it landed on is still the vendor's. They
compose and neither replaces the other: the allow-list deliberately permits `hf.co`, a different
registrable domain, and says nothing about the scheme.

**The `https → http` downgrade now crosses a real TLS hop** (`B-198`). It used to be asserted only
against a fabricated `Interceptor.Chain`, on the argument that the interceptor is a pure function
of one request and that adding a dependency for a single case cost more than it proved. That
argument was wrong in the way `SI-09` names: a mocked test proves the rule the code implements,
never that the rule still matches the world — and what it could not reach is whether OkHttp's own
`followSslRedirects` path hands a cleartext `Location` to a **network** interceptor at all.
`okhttp-tls` is in `gradle/libs.versions.toml` as a **test-only** dependency (it never reaches the
APK), and `RedirectPolicyTest` now starts a `MockWebServer` over TLS with a certificate generated
per run and trusted by nothing else on the machine, answers `307` with an `http` `Location` on a
public name, and asserts the recording never reaches it. Its **control** is the case beside it: the
same client, the same certificate, completing an ordinary call — without which a refusal caused by
a broken handshake would look exactly like a refusal caused by the rule.

The catalogue entry carries the checksums for `okhttp-tls` in `gradle/verification-metadata.xml`,
added by hand rather than by a wholesale regeneration, each one corroborated against the `.sha1`
Maven Central publishes beside the artefact — see `docs/runbooks/dependencies.md` for why that file
is never deleted or bypassed.

### What a model costs on disk

Read from `WhisperModel`, which took every figure from Hugging Face's own API. They are not
estimates, and `ModelDownloader` refuses a download before the first byte when the device cannot
hold what is still missing, plus ten per cent (`G-07`).

| Model | Bytes | With the margin |
|---|---|---|
| `tiny` | 32,152,673 | 35.4 MB |
| `base` | 59,707,625 | 65.7 MB |
| `small` (default) | 190,085,487 | 209.1 MB |
| `medium` | 539,212,467 | 593.1 MB |
| `large-turbo` | 574,041,195 | 631.4 MB |
| **all five** | **1,395,199,447** | **1.395 GB** |

Every dictation also keeps its WAV for ever (`G-02`) at about 1.9 MB a minute, so a full headset
is the expected end state rather than a corner case — which is why running out of space is a
first-class error with two numbers in it rather than "check the network".

Measured on a Quest 3 on 2026-09-19, 11.0 s of audio, `ggml-small-q5_1`:

| Threads | Cold | Warm | Model load | Against real time |
|---|---|---|---|---|
| 4 (`defaultThreads()`) | 16.5 s | 14.5 s | ≈ 2.1 s | **1.31×** |
| 6 (every core the OS reports) | 26.9 s | 37.6 s | — | 3.42× |

**Those figures are from a cool device and do not hold under sustained use.** The same benchmark
re-run after six minutes of continuous inference took two and a half times longer for the same work,
with greedy and beam search within 5% of each other — at that point the headset is thermally limited
and the decoding strategy stops being what decides the wait. A latency quoted from one run is
therefore a best case, not a rate.

Two things follow. Transcription is **slower than real time**, so a ten-second thought costs about
thirteen seconds of waiting and the interface must say so rather than look stuck. And oversubscribing
the cores is not a free win but a 2.6× loss — `defaultThreads()` leaving two cores alone is now a
measurement, not a preference.

## Decisions this module carries

`DEC-0003` speech placement · `DEC-0008` beam search at width five · `DEC-0013` cloud transcription
over the OpenAI-compatible route · `DEC-0014` the provider is an explicit choice · `DEC-0015` five
on-device models · `DEC-0016` the digest is pinned and enforced · `DEC-0017` the redirect
allow-list belongs to the store · `DEC-0019` changing the model closes the loaded context, which
narrows `DEC-0007` · `DEC-0030` one context, one owner, and a `suspend` close that never holds the
caller's thread — which refines both of those and is why `runBlocking` is now a build gate. ·
`DEC-0033` one download per model, owned by the
process, refused up front when there is no room ·
`DEC-0032` a dictation is at most ten
minutes and the limit transcribes rather than discards ·
`DEC-0031` the dispatcher belongs to the layer that blocks — which is why `ModelDownloader`,
`RemoteWhisperClient` and `CloudTranscriptionClient` each keep **one** `OkHttpClient` instead of
building one per construction (`I-28`: three of them were default arguments, so every
`Graph.sttEngine()` call allocated a dispatcher, a thread pool and a connection pool and threw
them away), and why `AudioRecorder` now implements a `Recorder` interface — a final class whose
`record` loops `while (isActive)` cannot be substituted, and under Robolectric letting it start
exhausts the heap, so everything downstream of a recording was untestable.

## What the close used to cost, and what it costs now

`WhisperEngine.close()` was `runBlocking(dispatcher)`. The free ran on the whisper thread, which is
required — freeing a context from another thread while a transcription is running is a
use-after-free — but the **caller** was parked until that thread's queue drained, and every caller
was on Main. Changing the model or pressing *Transcribe again* during a transcription froze the
whole interface for the rest of it: measured at up to about forty seconds on this device, against
Android's five-second ANR limit.

`withContext(dispatcher + NonCancellable)` suspends instead, and that removed the **freeze**
(`DEC-0030`). It did not remove the **wait**: a blocking JNI call cannot be interrupted by
coroutine cancellation, so a model switch queued behind a running transcription still took up to
half a minute with the interface merely alive rather than finished.

**The wait is gone too, and `B-118` is closed** (`DEC-0060`). `whisper_full_params.abort_callback`
now reaches the bridge (`fabricvr_whisper.cpp:83`, `:221-222`), `WhisperEngine` turns coroutine
cancellation into it through `invokeOnCancellation`, and a model switch cancels the job it was
queued behind rather than waiting it out. `VoiceState.PreparingEngine` still says which model is
being waited for, because the load itself is unchanged.

What remains is narrower and is written down rather than implied: a cancel that lands **between**
the handler's registration and the native entry sets an abort flag the run clears as its first act,
so that one run goes to completion with nobody waiting for it (`B-181`, open). It is a code read,
not a measurement — 0 of 40 attempts provoked it from the JVM seam — and the complete fix is a run
generation in the native `RunState`, not more Kotlin.

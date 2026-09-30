# Audit input C — security, network, native, build (independent reviewer, Opus, 2026-09-19)

Submodule `third_party/whisper.cpp` is pinned at tag **v1.9.4** (`third_party/whisper.cpp@927cfce3`,
verified with `git -C third_party/whisper.cpp describe --tags`). That sha belongs to the submodule's
history, not to this repository's.

| id | sev | file:line | defect | failure scenario | fix |
|---|---|---|---|---|---|
| F-C-01 | **Blocker** | `AndroidManifest.xml:11-16`; `RemoteWhisperClient.kt:44` | No `usesCleartextTraffic`, no `networkSecurityConfig`; targetSdk 34 blocks cleartext by default. | `http://192.168.0.x:8080` → `UnknownServiceException: CLEARTEXT not permitted` → mapped to `RemoteStt(0)` → silent fallback. Remote STT on a LAN server can never work. | `res/xml/network_security_config.xml` permitting cleartext for private ranges (or the entered host); a test that an `http://` URL reaches the server. |
| F-C-02 | High | `SecureSettings.kt:38-49` | `get()` returns `null` on any decrypt failure — key lost/rotated is indistinguishable from never set. | The user is told "add a key" with no hint theirs was lost; the dead ciphertext stays forever. | Separate *absent* from *undecryptable*: remove the entry and surface "re-enter your key". |
| F-C-03 | High | `SecureSettings.kt:69-83` | `secretKey()` check-then-generate is unsynchronised. | Settings init (main) and the OpenRouter lambda (OkHttp thread) race on first run; the second `generateKey()` replaces the alias and every value under the first key becomes garbage. | `@Synchronized` + cached `@Volatile` key. |
| F-C-04 | High | `CMakeLists.txt:11-18`; measured `CMakeCache.txt` | ggml built at the armv8-a baseline: `GGML_NATIVE=OFF`, `GGML_CPU_ARM_ARCH` unset, `HAVE_DOTPROD`/`HAVE_FP16_VECTOR_ARITHMETIC` empty, no `-march`. | Q5_1 matmul falls to scalar/NEON; transcription several times slower than XR2 Gen 2 allows, and the latency measurement will blame whisper. | `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` in `feature-stt/build.gradle.kts`; re-measure. |
| F-C-05 | High | `ModelStore.kt:27,30-31`; `ModelDownloader.kt:28` | Digest disabled; `resolve/main` redirects cross-host to a CDN and the default client follows with no allow-list. | Any body of exactly 190 085 487 bytes is accepted. | Pin a verified digest; log the resolved URL. |
| F-C-06 | High | `ModelDownloader.kt:28` | Default `OkHttpClient()`: read timeout 10 s, no call timeout, no resume. | A 10 s Wi-Fi stall kills the 190 MB fetch and deletes the `.part`; restart from zero. | Own client (read 60 s, callTimeout 0); keep `.part` and `Range`-resume, or document no-resume. |
| F-C-07 | Medium | `OpenRouterClient.kt:23-26` | No `callTimeout`; read timeout is per-read. | A provider emitting keep-alives holds a stream open indefinitely. | `callTimeout(10 min)` + token-stall detector. |
| F-C-08 | Medium | `OpenRouterClient.kt:104` | Only `IOException` caught; `trySendBlocking` can throw `InterruptedException`; non-IO escapes without `close()`. | The flow is never closed → the collector hangs forever. | `catch (t: Throwable) { close(AssistantException(t.toAppError())) }`. |
| F-C-09 | Medium | `OpenRouterClient.kt:89-95` | `trySendBlocking` parks an OkHttp dispatcher thread while the collector is slow. | Several stalled streams starve the shared dispatcher. | Explicit buffer/overflow, or read on `Dispatchers.IO` with `execute()`. |
| F-C-10 | Medium | `OpenRouterClient.kt:62`; `RemoteWhisperClient.kt:49` | Bodies materialised whole via `.string()` before any size check. | A hostile/misconfigured server OOMs the headset. | `peek().readUtf8(64 KiB)` cap. |
| F-C-11 | Medium | `SecureSettings.kt:73-81` | No `setKeySize(256)` (default AES-128), no StrongBox attempt, no user-auth. | Free hardening left on the table. | `setKeySize(256)`; StrongBox with fallback; record why no user-auth. |
| F-C-12 | Medium | `README.md:58-65`; `app/build.gradle.kts` | Debug is debuggable and README documents `run-as`, which runs with the app UID and can decrypt the key. | A headset in developer mode hands the API key to anyone with adb. Not acknowledged. | Decision record: real keys only on release-signed builds. |
| F-C-13 | Medium | `app/build.gradle.kts:15,21-25` | No release signing, minify off, no `proguard-rules.pro`, `versionCode` hardcoded. | No shippable artifact; enabling minify later breaks Room `*_Impl` and serialization. | Release `signingConfig` from `keystore.properties`; ProGuard keeps; versionCode from a property. |
| F-C-14 | Medium | `git ls-files` | Four Kotlin compiler crash logs are **tracked**: `.kotlin/errors/*.log`, with the home path and a stale 2.1.0 trace. | Noise and a local-path leak. | `git rm --cached -r .kotlin`; ignore `.kotlin/`. |
| F-C-15 | Medium | `scripts/check-secrets.sh:13,15` | Excludes all of `docs/` (40 of 133 files); patterns cover only `sk-or-v1-`, `sk-ant-`, `AIza`, PEM. | A key pasted into a handoff is invisible; `hf_`, `ghp_`, `AKIA`, `xox`, `glpat-` are not scanned. | Exclude only the two files that quote shapes; extend the patterns. |
| F-C-16 | Medium | `fabricvr_whisper.cpp:54,84-92` | `GetFloatArrayElements` not null-checked; `-fexceptions` on with no try/catch (a C++ exception across JNI → `std::terminate`); `NewStringUTF` given real UTF-8 (JNI wants modified UTF-8). | OOM → native crash; a long transcript under pressure aborts; a 4-byte codepoint yields a malformed jstring. | Null-check; wrap in try/catch; build the string via a byte array. |
| F-C-17 | Medium | `WhisperEngine.kt:19,63-69`; `Graph.kt:52` | `Closeable` never called; ~190 MB of weights live for the process; `close()` would free off the dispatcher. | Permanent memory cost; a native crash the moment a lifecycle owner calls it. | Free on the dispatcher; call from process teardown. |
| F-C-18 | Medium | measured on `app-debug.apk` | `libfabricvr_whisper.so` ships **unstripped** (14 755 240 B, "with debug_info"); the library module produced a 1 897 664 B stripped copy of the same BuildID. | ~13 MB dead weight and full symbols on the device; release stripping unverified. | Investigate the `stripDebugSymbols` no-op; assert `, stripped` in CI. |
| F-C-19 | Medium | all six `build.gradle.kts` | `isReturnDefaultValues = true` — unmocked android.jar calls return null/0. | `Base64.encodeToString` returns null in JVM tests; a `SecureSettings` regression can pass green. | Keep only where Robolectric is absent. |
| F-C-20 | Medium | `OpenRouterClient.kt` | No retry/backoff for 429/502/503 (docs-study §C names `Retry-After`). | One 429 ends the answer. | Interceptor honouring `Retry-After`, capped backoff. |
| F-C-21 | Low | `gradle-wrapper.properties:3-5` | No `distributionSha256Sum`; wrapper jar provenance unrecorded; `gradlew.bat` absent. | Distribution unpinned. | Add the checksum; record the origin. |
| F-C-22 | Low | `.gitignore:9` | `*.bin` repo-wide. | Future `.bin` fixtures silently untracked. | Anchor to `**/models/*.bin`. |
| F-C-23 | Low | `scripts/install-on-quest.sh:9` | A DHCP lease baked into a tracked script. | Anyone else waits 300 s against a stranger's IP. | Discover via `adb devices`. |
| F-C-24 | Low | `SettingsViewModel.kt:32` | Keystore decrypt + `File.length()` on the main thread in `init`. | Jank frame in VR. | `viewModelScope.launch(Dispatchers.IO)`. |
| F-C-25 | Low | `feature-assistant/build.gradle.kts:29` | `okhttp-sse` declared, never imported. | Dead dependency. | Drop it or adopt `EventSources`. |

## Verified fine
`language="auto"` with `detect_language=false` is exactly right for v1.9.4 (`whisper.h:532-534`;
`src/whisper.cpp:6938`; `detect_language=true` would return without transcribing at `:6957-6959`);
`lang` lifetime safe; `whisper_full_lang_id` semantics correct; `JNI_ABORT` right; GCM IV fresh per
encrypt, Base64 NO_WRAP makes the `:` split sound; the key never reaches a log/toString/body;
`allowBackup=false` applies to debug; submodule pin = v1.9.4; CMake shape (static, examples off,
`libc++_shared.so` packaged); permissions minimal, exported activities read no extras;
`awaitClose { call.cancel() }` present; downloader cooperative cancellation and atomic rename;
`check-secrets.sh`'s `grep -zZv` filter correct on both `/usr/bin/grep` and ugrep; version pins match
the spec's as-built block.

Documentation gap: spec §1–2 name the Spatial SDK Gradle plugin; `app/build.gradle.kts` applies none.

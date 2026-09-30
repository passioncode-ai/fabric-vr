# Spec — v1-notes-core (stage 3)

Locks every shared contract for the build. Inputs: the stage-0 brief, the stage-1 docs study, the
stage-2 module map, and the UX layer (`docs/ux/`). Tracks: **UX** ran (scenarios SCN-001…013,
linter green); **VISUAL** ran text-only with sheleg-design *workbench* tokens in code (Figma
declined by the operator); **COPY** declined — EN drafts, recorded in the carry-over
ledger row 1. **Tracks converge: clean** — every screen in `screens.md` has both its states and its
strings drafted in this spec, no string exceeds its element, no state lacks a string.

## 1. Global Constraints (inherited verbatim by every task)

The three below-latest pins are `DEC-0004`; the panel/Space shape is `DEC-0002`; speech placement is
`DEC-0003`.

> **As built, four of the versions below moved during stage 5 and the block is left as written**
> because a dated artifact records the moment it was written. The build's actual values, each forced
> by a measured failure: **Kotlin 2.2.21** (not 2.1.0) with `kotlin-stdlib` force-pinned to it,
> **KSP 2.2.21-2.0.5**, **OkHttp 4.12.0** (not 5.5.0), **Room 2.7.2** (not 2.8.5) and
> **compileSdk 35** (not 34; `minSdk`/`targetSdk` stayed at 34). `gradle/libs.versions.toml` is the
> live record.

> **DOC-01, recorded 2026-09-19 in the hardening pass: the Spatial SDK Gradle plugin is not
> applied.** The stack table below names `com.meta.spatial.plugin 0.14.0`; `app/build.gradle.kts`
> applies three plugins and none of them is Meta's. The four `com.meta.spatial:*` artifacts are used
> directly and the scene is built in code (`ImmersiveActivity.onSceneReady`), because the plugin's
> value is glXF and asset tooling this app has no use for. The same pass turned the release build
> type on: `isMinifyEnabled = true` with `app/proguard-rules.pro` and resource shrinking, signing
> read from an untracked `keystore.properties` (a sample is committed), and `versionCode` taken from
> `-PversionCode=` so a release can be rebuilt from one commit. Measured: release APK 74 MB against
> the debug APK's 145 MB.
>
> **Also recorded 2026-09-19 (H-26): the strings left Kotlin.** The table below says "EN drafts,
> hardcoded in Kotlin for v1 (no res/values/strings split yet)" and §3.2 gives
> `UiMessage(text: String, action: UiAction?)`. As built, `UiMessage` is
> `(textRes: Int, args: List<Any>, action: UiAction?)` and every word the person reads lives in
> `app/src/main/res/values/strings.xml` (76 strings and one plural) or
> `core-common/src/main/res/values/strings.xml` (24: the failure messages and the banner's buttons). `UiStateMapperTest` therefore asserts which
> message was chosen; that the ids resolve to non-blank, fully formatted sentences is checked on the
> device by `core-common`'s `UiStringsTest`, because a JVM test cannot open a resource table and
> would pass for the wrong reason. The launcher icon is the PassionCode passion-fruit mark as an
> adaptive icon, and `Tokens.Motion` is in use: `quickMs` eases the hold button's colour, `calmMs`
> cross-fades the voice sheet's states.
>
> **The contracts below moved too, and this is the list (2026-09-19).** The sections are left as
> they were written; each row says what the code now holds, with the file that holds it. A dated
> spec that is quietly edited stops being evidence of what was decided.
>
> | Section | As specified | As built |
> |---|---|---|
> | §2 file layout | `ui/ModelDownloadDialog.kt` | never existed; the download UI is a state inside `VoiceCaptureSheet.kt` |
> | §3.2 `AppError` | no `InsecureUrl` | `AppError.InsecureUrl(url)`, mapped to "Only https, or http to a device on your own network" with *Settings* |
> | §3.3 `NotesRepository` | no bulk reads | plus `suspend fun recent(limit)` and `suspend fun searchAny(terms, limit)`, both used by `NotesContextBuilder` |
> | §3.3 `NoteChange.Deleted` | `Deleted(id)` | `Deleted(id, createdAt)` — the vault needs the month to find the file |
> | §3.4 `Vault` | write and remove only | plus `audioPathFor(note)` and `adoptAudio(note, source)` |
> | §3.4 `VaultMirror` | "reports the error once through a `Flow<AppError>`" | `failures: StateFlow<Map<String, AppError>>` keyed per note, plus `retryFailed()`; one last error meant a success elsewhere erased the record of the failure |
> | §3.4 front-matter | `id, created, updated, tags, day, audio, lang, engine` | also `title`, `source`, `duration_ms`, and `fallback` when there was one |
> | §3.5 JNI | `JNIEXPORT jstring … transcribe` | `jbyteArray`, and `ByteArray?` in Kotlin: `NewStringUTF` expects *modified* UTF-8 and a transcript can carry a four-byte codepoint |
> | §3.7 `SecureSettings` | get / put / remove | plus `corruptedKeys: StateFlow<Set<String>>`, so a value that cannot be decrypted is announced rather than silently absent |
> | §5 testing | `NotesRepositoryTest` + `SearchTest` + `DailyNoteTest` | one class, `NotesRepositoryTest`; search and the daily note are methods inside it |

`covers: REQ-001, REQ-011`

```
package            ai.passioncode.fabricvr
Gradle             wrapper 8.13, JDK 17 (Android Studio JBR 21 runs it; Java target 17)
AGP                8.11.1          Kotlin 2.1.0          KSP 2.1.0-1.0.29
Compose BOM        2024.09.03      activity-compose 1.9.2
compileSdk 34      minSdk 34       targetSdk 34          (Horizon OS is Android 14)
horizonos          minSdkVersion 69, targetSdkVersion 69
Spatial SDK        com.meta.spatial:* 0.14.0 from mavenCentral(); plugin com.meta.spatial.plugin 0.14.0
Room               androidx.room:*  2.8.5 (KSP)          OkHttp 5.5.0 + okhttp-sse 5.5.0
kotlinx-serialization-json 1.11.0   coroutines 1.9.0
NDK                27.2.12479018   CMake 3.22.1          abiFilters: arm64-v8a ONLY (Quest 3 is arm64)
whisper.cpp        submodule third_party/whisper.cpp @ v1.9.4, add_subdirectory, WHISPER_BUILD_* OFF
model              ggml-small-q5_1.bin, 190085487 bytes,
                   https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin
LLM                OpenRouter https://openrouter.ai/api/v1/chat/completions
                   default model anthropic/claude-sonnet-5, alternative anthropic/claude-haiku-4.5
log tag            FabricVR       — never log a key, a note body or a transcript
secrets            AndroidKeyStore AES/GCM (security-crypto is deprecated); never in the repo
strings            EN drafts, hardcoded in Kotlin for v1 (no res/values/strings split yet)
tests              JVM: ./gradlew testDebugUnitTest   device: ./gradlew connectedDebugAndroidTest
```

## 2. Architecture and file layout

`covers: REQ-001, REQ-007`

Six Gradle modules; `:app` is the only one that applies the Spatial SDK plugin.

```
settings.gradle.kts            :app :core-common :core-notes :feature-vault :feature-stt :feature-assistant
app/
  src/main/AndroidManifest.xml            panel + immersive activities (contracts in §3)
  src/main/kotlin/ai/passioncode/fabricvr/
    FabricVrApp.kt                        Application; builds the object graph (manual DI, no Hilt)
    Graph.kt                              lazily constructed singletons, one place
    PanelActivity.kt                      2D panel — ComponentActivity + setContent { FabricApp() }
    ImmersiveActivity.kt                  AppSystemActivity — VRFeature + ComposeFeature, same FabricApp()
    ui/FabricApp.kt                       NavHost: today | editor | search | chat | settings
    ui/TodayScreen.kt  NoteEditorScreen.kt  SearchScreen.kt  ChatScreen.kt  SettingsScreen.kt
    ui/VoiceCaptureSheet.kt  ModelDownloadDialog.kt
core-common/   theme/Tokens.kt Theme.kt · AppError.kt · UiMessage.kt · UiStateMapper.kt · Logging.kt · SecureSettings.kt
core-notes/    Note.kt NoteChange.kt TagParser.kt · db/(NoteEntity, NoteFts, NoteDao, NotesDatabase) · NotesRepository.kt
feature-vault/ Vault.kt · MarkdownSerializer.kt · VaultMirror.kt
feature-stt/   AudioRecorder.kt · SttEngine.kt · WhisperEngine.kt (JNI) · RemoteWhisperClient.kt · SttRouter.kt
               ModelStore.kt ModelDownloader.kt · Transcript.kt · src/main/cpp/{CMakeLists.txt,fabricvr_whisper.cpp}
feature-assistant/ OpenRouterClient.kt · SseParser.kt · ChatModels.kt · NotesContextBuilder.kt · Assistant.kt
```

Data flow: UI → ViewModel (`androidx.lifecycle.ViewModel`, state as `StateFlow<UiState>`) →
repository/service → storage or network. `VaultMirror` subscribes to `NotesRepository.observeChanges()`
in application scope, so the vault follows every write without the UI knowing it exists.

## 3. Contracts — locked

`covers: REQ-001, REQ-002, REQ-003, REQ-004, REQ-005, REQ-006, REQ-007, REQ-008, REQ-009, REQ-010`

### 3.1 Manifest (the panel/immersive discriminator)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:horizonos="http://schemas.horizonos/sdk">
  <uses-feature android:name="android.hardware.vr.headtracking" android:required="false" />
  <horizonos:uses-horizonos-sdk horizonos:minSdkVersion="69" horizonos:targetSdkVersion="69" />
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.RECORD_AUDIO" />
  <application android:name=".FabricVrApp" android:allowBackup="false" android:label="Fabric VR">
    <meta-data android:name="com.oculus.supportedDevices" android:value="quest3|quest3s" />
    <meta-data android:name="com.oculus.vr.focusaware" android:value="true" />
    <uses-native-library android:name="libossdk.oculus.so" android:required="false" />
    <activity android:name=".PanelActivity" android:exported="true" android:resizeableActivity="true"
              android:theme="@style/Theme.FabricVR">
      <layout android:defaultWidth="1024dp" android:defaultHeight="640dp"
              android:minWidth="480dp" android:minHeight="360dp" />
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="com.oculus.intent.category.2D" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
    <activity android:name=".ImmersiveActivity" android:exported="true"
              android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen"
              android:launchMode="singleTask"
              android:configChanges="screenSize|screenLayout|orientation|keyboardHidden|keyboard|navigation|uiMode">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="com.oculus.intent.category.VR" />
        <category android:name="android.intent.category.DEFAULT" />
      </intent-filter>
    </activity>
  </application>
</manifest>
```

`headtracking` is `required="false"` deliberately: the panel must install and run even where a
head-tracked feature is unavailable, and the immersive activity is optional (SCN-012 degrades).

### 3.2 `AppError` — one taxonomy (core-common)

```kotlin
sealed class AppError(val cause: Throwable? = null) {
    data class Permission(val what: String, val permanent: Boolean = false) : AppError()
    data class ModelMissing(val model: String) : AppError()
    data class ModelDownload(val reason: Reason, val t: Throwable? = null) : AppError(t) {
        enum class Reason { NETWORK, CHECKSUM, DISK, CANCELLED }
    }
    data class Network(val t: Throwable) : AppError(t)
    data class RemoteStt(val status: Int, val body: String?) : AppError()
    data class SttFailed(val engine: String, val t: Throwable?) : AppError(t)
    data class OpenRouter(val status: Int, val code: String?, val message: String?) : AppError()
    object NoApiKey : AppError()
    data class Storage(val op: String, val t: Throwable?) : AppError(t)
    data class Unknown(val t: Throwable?) : AppError(t)
}
```

`UiStateMapper.map(error): UiMessage(text: String, action: UiAction?)` — every branch returns a
distinct text; `Unknown` still renders (never a crash, never silence). `UiAction` is
`Retry | OpenSettings | GrantPermission | OpenAppSettings | DownloadModel`.

Strings (EN drafts — SCN-004, SCN-006, SCN-009, SCN-010, SCN-013):

| Error | Text | Action |
|---|---|---|
| `Permission(mic, false)` | "Dictation needs the microphone." | Grant |
| `Permission(mic, true)` | "The microphone is blocked for this app." | Open app settings |
| `ModelMissing` | "The speech model isn't on this headset yet." | Download |
| `ModelDownload(NETWORK)` | "The download stopped. Check the network." | Retry |
| `ModelDownload(CHECKSUM)` | "The downloaded file was corrupt and was removed." | Retry |
| `RemoteStt(status)` | "The speech server answered $status. Used this headset instead." | — |
| `SttFailed` | "Couldn't transcribe that. The recording is kept." | Retry |
| `NoApiKey` | "Add an OpenRouter key to use the assistant." | Open settings |
| `OpenRouter(401)` | "OpenRouter rejected the key." | Open settings |
| `OpenRouter(402)` | "OpenRouter credits are exhausted." | — |
| `OpenRouter(429)` | "Rate limited by OpenRouter." | Retry |
| `Network` | "No answer from the network." | Retry |
| `Storage` | "Couldn't save. Your text is still here." | Retry |
| `Unknown` | "Something failed: <class name>." | Retry |

### 3.3 Notes (core-notes)

```kotlin
data class Note(
    val id: String,                    // UUID string, also the vault file stem
    val title: String,
    val body: String,
    val tags: Set<String>,             // lowercase, no '#'
    val createdAt: Long, val updatedAt: Long,
    val dayKey: String?,               // "YYYY-MM-DD" for a daily note, else null
    val audioPath: String? = null,
    val transcript: Transcript? = null,
)
sealed interface NoteChange { data class Upserted(val note: Note) : NoteChange
                              data class Deleted(val id: String) : NoteChange }

interface NotesRepository {
    fun observeNotes(tag: String? = null): Flow<List<Note>>   // newest first
    fun observeChanges(): Flow<NoteChange>
    suspend fun get(id: String): Note?
    suspend fun upsert(note: Note): Result<Note>
    suspend fun delete(id: String): Result<Unit>
    fun search(query: String): Flow<List<Note>>               // FTS over title+body+transcript
    suspend fun dailyNote(day: LocalDate): Result<Note>       // creates on first call, idempotent
    fun observeTags(): Flow<List<String>>
}
object TagParser { fun parse(text: String): Set<String> }      // #tag, unicode letters/digits/_/-, lowercased
```

Room: `NoteEntity` (tags stored as a comma string), FTS4 `note_fts(noteId, title, body, transcript)`
kept in sync inside the DAO's transaction. `search` returns `emptyList()` for a blank query.

### 3.4 Vault (feature-vault)

```kotlin
interface Vault {
    fun pathFor(note: Note): File              // <files>/vault/notes/YYYY/MM/<id>.md
    suspend fun write(note: Note): Result<File>
    suspend fun remove(id: String, createdAt: Long): Result<Unit>
    val root: File
}
object MarkdownSerializer {
    fun toMarkdown(note: Note): String         // YAML front-matter + body
    fun parse(text: String): Note?             // round-trip for the test
}
```

Front-matter keys: `id`, `created`, `updated`, `tags` (YAML list), `day`, `audio`, `lang`, `engine`.
Audio is copied beside the Markdown as `<id>.wav`. A failed mirror never fails the note write: the
repository succeeded, `VaultMirror` reports the error once through a `Flow<AppError>` the settings
screen shows as "vault out of sync".

### 3.5 Speech (feature-stt)

```kotlin
data class Transcript(val text: String, val language: String, val source: Source,
                      val engine: String, val durationMs: Long) {
    enum class Source { LOCAL, REMOTE, LOCAL_FALLBACK }
}
interface SttEngine { suspend fun transcribe(pcm: ShortArray, sampleRate: Int = 16_000,
                                             langHint: String? = null): Result<Transcript> }
class SttRouter(local: SttEngine?, remote: SttEngine?, ...) : SttEngine   // remote → local fallback
interface ModelStore { fun modelFile(): File; fun isPresent(): Boolean; fun expectedBytes(): Long }
class ModelDownloader(...) { fun download(): Flow<Progress> }             // Progress: Running(bytes,total) | Done | Failed(AppError)
class AudioRecorder(...) { fun record(): Flow<Chunk>; fun stop() }        // 16 kHz mono PCM16
```

JNI (`fabricvr_whisper.cpp`), the deliberate difference from the upstream sample whose `jni.c`
hardcodes `params.language = "en"`:

```cpp
JNIEXPORT jlong  Java_..._WhisperNative_initContext(JNIEnv*, jobject, jstring modelPath);
JNIEXPORT void   Java_..._WhisperNative_freeContext(JNIEnv*, jobject, jlong ctx);
JNIEXPORT jstring Java_..._WhisperNative_transcribe(JNIEnv*, jobject, jlong ctx,
                     jfloatArray audio, jint threads, jstring language /* "auto" | "ru" | "en" */);
JNIEXPORT jstring Java_..._WhisperNative_detectedLanguage(JNIEnv*, jobject, jlong ctx);
```

`transcribe` sets `params.language = language` (`"auto"` → the pointer whisper.h documents for
auto-detection), `translate = false`, `no_context = true`, `n_threads = threads`, then concatenates
`whisper_full_get_segment_text` over `whisper_full_n_segments`. Access is serialised on one
single-thread dispatcher per context (the upstream constraint).

Remote: `POST <serverUrl>/inference`, multipart `file` (16-bit PCM WAV), `response_format=json`,
`temperature=0.0`; non-2xx or timeout → `AppError.RemoteStt` → the router falls back to local and
marks the transcript `LOCAL_FALLBACK`.

### 3.6 Assistant (feature-assistant)

```kotlin
class OpenRouterClient(private val apiKey: () -> String?, ...) {
    fun stream(model: String, messages: List<ChatMessage>): Flow<StreamEvent>
}
sealed interface StreamEvent { data class Token(val text: String) : StreamEvent
                               data class Usage(val prompt: Int, val completion: Int) : StreamEvent
                               object Done : StreamEvent }
object SseParser { fun event(line: String): Parsed }   // Comment | Data(json) | DoneMarker | Ignore
class NotesContextBuilder(repo: NotesRepository) { suspend fun build(question: String, budget: Int = 12_000): Context }
```

SSE rules from the docs study: lines beginning `:` are comments (`: OPENROUTER PROCESSING`) and are
skipped; `data: [DONE]` terminates; the final content-free chunk carries `usage`. Errors:
`{"error":{"code":N,"message":…}}` with HTTP status == `code` → `AppError.OpenRouter(status, code,
message)`; 401/402/429 get their own strings (§3.2). Headers: `Authorization: Bearer …`,
`HTTP-Referer: https://passioncode.ai`, `X-Title: Fabric VR`.

Context: the newest notes whose text matches the question's terms first, then recent notes, until the
character budget is spent; each note enters as `### <title> (<date>)\n<body>`; the chat shows which
titles were used.

### 3.7 Secure settings (core-common)

```kotlin
interface SecureSettings {
    fun get(key: String): String?
    fun put(key: String, value: String): Result<Unit>
    fun remove(key: String)
}
// keys: openrouter_api_key, openrouter_model, whisper_server_url, stt_language
```

AES/GCM with an `AndroidKeyStore` key (`fabricvr_settings_v1`), ciphertext + IV base64 in
`SharedPreferences`. Keystore unavailable → `AppError.Storage("keystore")`, the field keeps its text.

## 4. Error handling and degradation

`covers: REQ-009`

Every public suspend function returns `Result<T>` whose failure is an `AppError`. The UI layer never
catches raw exceptions; `UiStateMapper` is the only place that turns an error into words. Four
degradations are explicit and tested: **no model** → text notes still work and the dictation path
offers the download; **remote STT down** → local with a visible badge; **no key** → the assistant is
reachable but says why and links settings; **vault write failed** → the note is still saved and the
settings screen says the vault is out of sync.

## 5. Testing approach

`covers: REQ-002, REQ-003, REQ-004, REQ-005, REQ-006, REQ-008, REQ-009, REQ-010`

- **JVM (`testDebugUnitTest`)**: `TagParserTest`, `NotesRepositoryTest` + `SearchTest` +
  `DailyNoteTest` (Robolectric, in-memory Room), `MarkdownVaultTest` (round-trip),
  `SttRouterTest` (MockWebServer: 200 → REMOTE, 500/timeout → LOCAL_FALLBACK),
  `ModelDownloaderTest` (checksum mismatch → file removed, `ModelDownload(CHECKSUM)`),
  `SseParserTest` + `OpenRouterClientTest` (comment lines, `[DONE]`, usage chunk, 401/402/429),
  `UiStateMapperTest` (every `AppError` branch maps to a distinct text).
- **Device (`connectedDebugAndroidTest`)**: `WhisperEngineTest` transcribes bundled 16 kHz WAV
  fixtures — `jfk.wav` (EN, expects "ask not what your country") and `ru_fixture.wav` — asserting the
  detected language; `PanelSmokeTest` launches `PanelActivity` and asserts the list renders.
- Every test is written before its production code and watched failing first (TDD).

## 6. Open risks carried into the build

Same list as the docs study's *Unverified*: `targetSdkVersion 69` vs the doc's `76`; `RECORD_AUDIO`
behaviour on Horizon OS unconfirmed by documentation (REQ-003's device run is the proof); no
first-party whisper.cpp Android benchmark, so on-device latency is measured, not claimed; the
whisper-server multipart `language` field is unverified, so the client sends none and relies on the
server's own default plus our `auto` local path.

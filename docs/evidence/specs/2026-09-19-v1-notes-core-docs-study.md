# Docs study — v1-notes-core (stage 1)

Fetched 2026-09-19 by two research sub-agents (raw GitHub files via `curl`/`raw.githubusercontent.com`,
`developers.meta.com` via WebFetch, Maven metadata, the live OpenRouter models endpoint). Every
contract stage 3 locks is grounded here, not on recall. Items neither agent could verify are in
*Unverified* at the end.

## A. Meta Spatial SDK 0.14.0 — the hybrid shape

**Resolution:** `com.meta.spatial:*` **0.14.0** and the Gradle plugin `com.meta.spatial.plugin`
**0.14.0** resolve from **Maven Central** — the sample's `settings.gradle.kts` declares only
`mavenCentral()`, `google()`, `gradlePluginPortal()`; there is **no Meta-hosted repository**
(`maven-metadata.xml` on repo1.maven.org: `<latest>0.14.0</latest><release>0.14.0</release>`).

**Versions the shipped sample pins** (`HybridSample/gradle/libs.versions.toml`):
`spatialsdk = "0.14.0"`, `agp = "8.11.1"`, `kotlin = "2.1.0"`, `composeBom = "2024.09.03"`,
`composeActivity = "1.9.2"`. `app/build.gradle.kts`: `compileSdk = 34`, `minSdk = 34`,
`targetSdk = 34` with the comment *"HorizonOS is Android 14 (API level 34)"*, Java 17.

**Artifacts:** `meta-spatial-sdk` (base), `-toolkit`, `-compose`, `-vr`, `-isdk`, `-uiset`,
`-mruk`, `-physics`, `-animation`, `-spatialaudio`, plus debug-only `-ovrmetrics`,
`-castinputforward`, `-hotreload`, `-datamodelinspector`.

**The panel/immersive discriminator is the intent category** (`HybridSample/app/src/main/AndroidManifest.xml`):

```xml
<manifest xmlns:horizonos="http://schemas.horizonos/sdk">
  <uses-feature android:name="android.hardware.vr.headtracking" android:required="true" />
  <horizonos:uses-horizonos-sdk horizonos:minSdkVersion="69" horizonos:targetSdkVersion="69" />
  <application>
    <meta-data android:name="com.oculus.supportedDevices" android:value="quest2|questpro|quest3" />
    <meta-data android:name="com.oculus.vr.focusaware" android:value="true" />
    <uses-native-library android:name="libossdk.oculus.so" android:required="true" />

    <activity android:name=".PancakeActivity" android:exported="true" android:resizeableActivity="true">
      <layout android:defaultHeight="550dp" android:defaultWidth="800dp" />
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="com.oculus.intent.category.2D" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>

    <activity android:name=".HybridSampleActivity"
        android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen"
        android:launchMode="singleTask"
        android:configChanges="screenSize|screenLayout|orientation|keyboardHidden|keyboard|navigation|uiMode"
        android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="com.oculus.intent.category.VR" />
        <category android:name="android.intent.category.DEFAULT" />
      </intent-filter>
    </activity>
  </application>
</manifest>
```

- **2D panel** = `com.oculus.intent.category.2D` + `<layout>` + `LAUNCHER`, a plain
  `ComponentActivity`, `resizeableActivity="true"`.
- **Immersive** = `com.oculus.intent.category.VR`, `singleTask`, fullscreen theme, an
  `AppSystemActivity` (Spatial SDK).

**Switching** (`PancakeActivity.kt`, `HybridSampleActivity.kt`):

```kotlin
// panel → immersive
startActivity(Intent(this, ImmersiveActivity::class.java).apply {
  action = Intent.ACTION_MAIN; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })

// immersive → panel in Home
val pending = PendingIntent.getActivity(applicationContext, 0,
    Intent(applicationContext, PanelActivity::class.java).apply {
      action = Intent.ACTION_MAIN; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    .putExtra("extra_launch_in_home_pending_intent", pending))
finish()
```

**Compose panel registration** (immersive side):

```kotlin
override fun registerFeatures(): List<SpatialFeature> = mutableListOf(VRFeature(this), ComposeFeature())
override fun registerPanels(): List<PanelRegistration> = listOf(
  PanelRegistration(R.id.panel_id) {
    config { themeResourceId = R.style.PanelAppThemeTransparent
             layoutWidthInDp = 1024f; layoutHeightInDp = 627f
             layerConfig = LayerConfig(); enableTransparent = true; includeGlass = false }
    composePanel { setContent { /* the same Composable as the 2D panel */ } }
  })
```

**Panel resolution** (`spatial-sdk-2dpanel-resolution`): `layoutDpi` default `288`;
`layoutWidthInPx = layoutWidthInDp × layoutDpi / 160`; *"Avoid exceeding 2064x2208px due to memory
limitations"*; Quest 3 eye buffer is 2064×2208 per eye. `PixelDisplayOptions` for exact pixels.

**Horizon OS 2D apps** (`android-apps/port-an-existing-app`): *"Horizon OS does not include Google
Mobile Services, so calls into GMS APIs fail"*; default panel window `1024dp × 640dp`, minimum
example `360dp × 225dp`; `com.oculus.supportedDevices` may include `quest3s`. No Horizon-specific
prose about `RECORD_AUDIO` or keyboards was found on any reachable page.

## B. whisper.cpp — on-device STT

**Release v1.9.4**, published 2026-09-11 (GitHub releases API). Android example lives at
`examples/whisper.android` (Kotlin) with the native build in its `:lib` module:
`compileSdk 34`, `minSdk 26`, `ndkVersion "25.2.9519653"`,
`abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'`.

**The JNI bridge hardcodes English** (`lib/src/main/jni/whisper/jni.c`):

```c
struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
params.translate = false;
params.language = "en";          // ← no runtime language argument in the sample's JNI surface
params.n_threads = num_threads;
params.no_context = true;
whisper_full(context, params, audio_data_arr, audio_data_length);
```

**Consequence for us:** Russian and auto-detect require our own JNI that passes the language
through. `include/whisper.h` documents the contract:

```c
#define WHISPER_SAMPLE_RATE 16000
// whisper_full_params.language — "for auto-detection, set to nullptr, "" or "auto""
struct whisper_full_params whisper_full_default_params(enum whisper_sampling_strategy strategy);
int whisper_full(struct whisper_context * ctx, struct whisper_full_params params,
                 const float * samples, int n_samples);
struct whisper_context * whisper_init_from_file_with_params(const char * path_model,
                                                            struct whisper_context_params params);
int whisper_full_n_segments(struct whisper_context * ctx);
const char * whisper_full_get_segment_text(struct whisper_context * ctx, int i_segment);
int whisper_lang_auto_detect(struct whisper_context * ctx, int offset_ms, int n_threads, float * lang_probs);
```

Audio must arrive as **float32 mono at 16 kHz**; the Kotlin side must convert `ShortArray / 32768f`.
The sample also serialises access: *"Meet Whisper C++ constraint: Don't access from more than one
thread at a time"* — one single-thread dispatcher per context.

**Models** (`https://huggingface.co/ggerganov/whisper.cpp/resolve/main/<file>`, sizes from HEAD):

| File | Bytes |
|---|---|
| `ggml-small-q5_1.bin` | 190 085 487 |
| `ggml-small.bin` | 487 601 967 |
| `ggml-base-q5_1.bin` | 59 707 625 |
| `ggml-large-v3-turbo-q5_0.bin` | 574 041 195 |

**No first-party Android/Quest benchmark exists** in the README — the on-device speed claim must be
measured on the device, not cited.

**whisper-server** (`examples/server`): routes are `/inference`, `/load`, `/health` only —
**there is no `/v1/audio/transcriptions`**. Multipart fields: `file`, `temperature`,
`temperature_inc`, `prompt`, `response_format`. Language is a CLI flag (`-l LANG`, `'auto'`), the
form field is unverified. Homebrew's formula is **`whisper.cpp`** (not `whisper-cpp`) and builds
with `-DWHISPER_BUILD_SERVER=OFF`, so `brew install` does **not** give `whisper-server`.

## C. OpenRouter

- Base `https://openrouter.ai/api/v1`, `POST /chat/completions`,
  `Authorization: Bearer <key>`; optional `HTTP-Referer`, `X-Title` (docs now also name
  `X-OpenRouter-Title`, with `X-Title` kept for back-compat).
- **SSE:** `data: {…}` lines, terminator `data: [DONE]`, keep-alive comment lines literally
  `: OPENROUTER PROCESSING` — *"Comment payload can be safely ignored per the SSE specs"*; a final
  content-free chunk carries `usage` just before `[DONE]`.
- **Errors:** `{ "error": { "code": number, "message": string, "metadata"?: {…} } }`, HTTP status ==
  `error.code`. 401 invalid credentials · 402 insufficient credits · 403 moderation · 408 timeout ·
  429 rate limited (`Retry-After`) · 502 model down · 503 no provider.
- **No audio-transcription endpoint** — chat completions only. Whisper stays on-device or on our own
  `whisper-server`.
- **Anthropic ids, live from `GET /api/v1/models`** (447 models, prices USD per token):

| id | context | prompt | completion |
|---|---|---|---|
| `anthropic/claude-sonnet-5` | 1 000 000 | 0.000002 | 0.00001 |
| `anthropic/claude-opus-5` | 1 000 000 | 0.000005 | 0.000025 |
| `anthropic/claude-haiku-4.5` | 200 000 | 0.000001 | 0.000005 |
| `anthropic/claude-fable-5.1` | 1 000 000 | 0.00001 | 0.00005 |

`:batch` twins exist at half price. **v1 default: `anthropic/claude-sonnet-5`**, cheap tier
`anthropic/claude-haiku-4.5`, both configurable in Settings.

## D. Android platform

- **Compose BOM** latest stable `androidx.compose:compose-bom:2026.09.00`; the Compose compiler ships
  with Kotlin as `org.jetbrains.kotlin.plugin.compose` at the same version as Kotlin. The Spatial SDK
  sample pins Kotlin `2.1.0` + BOM `2024.09.03`; **we follow the sample's pairing** (proven against
  Spatial SDK 0.14.0) rather than the newest.
- **Room** legacy group stable `androidx.room:room-*:2.8.5` (KSP). A new `androidx.room3` group exists
  (3.0.x) — not used.
- **`androidx.security:security-crypto` is deprecated** (1.1.0 notes: *"Deprecated all APIs in favour
  of existing platform APIs and direct use of Android Keystore"*) → we use **AndroidKeyStore AES/GCM
  directly** for the OpenRouter key.
- **AudioRecord:** `AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
  AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, getMinBufferSize(...))`, requires
  `RECORD_AUDIO`.
- **OkHttp `5.5.0`** + **`okhttp-sse:5.5.0`** (`EventSources.createFactory(client).newEventSource`,
  comment lines handled per spec), **kotlinx-serialization-json `1.11.0`**.
- Permission in Compose: `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`.

## Unverified / conflicting (carried to the spec's risk list)

1. `horizonos:targetSdkVersion` — shipped sample `69`, the hybrid doc's example `76`. We ship `69`
   (the version that is known to run) and record the divergence.
2. The hybrid doc adds `finishAndRemoveTask()` after panel→immersive; the shipped sample omits it.
3. Horizon OS prohibited / review-required permission lists were not fetched — `RECORD_AUDIO` is
   assumed to behave as standard Android; **REQ-003's device run is the proof**.
4. No Horizon-specific microphone or keyboard documentation found.
5. No first-party whisper.cpp Android benchmark — on-device latency must be measured.
6. `whisper-server` multipart `language` field unconfirmed (CLI flag only).
7. KSP ↔ Kotlin 2.1.0 pairing to be resolved from Maven metadata at build time.
8. `developers.meta.com` quotes are WebFetch-rendered (plain `curl` returns HTTP 400), not
   byte-verified like the raw GitHub files.

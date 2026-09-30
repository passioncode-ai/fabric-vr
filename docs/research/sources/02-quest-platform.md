# Source brief 02 — Meta Quest platform for a native Android app (web research, 2026-09-19)

Produced by a research sub-agent (Sonnet) with WebSearch/WebFetch; 34 tool calls. Facts carry
[source, date]; anything not independently confirmed is marked "unverified".

## 1. Meta Spatial SDK

- Kotlin-based native Android framework built in Android Studio [developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-explainer/, 2026].
- Current version **v0.14.0** (2026-09-11): hands-only locomotion, mesh-node parenting, audio controls, 60% smaller shader storage. v0.13.2 (2026-07-16): in-place panel resize, shadow casting. v0.13.0 (2026-05-14): Canvas Panel API stereo rendering, Lighting API, pak-based assets [developers.meta.com/horizon/release-notes/spatial-sdk/].
- **Panels**: Android Activities/Views/Compose in 3D via `PanelSceneObject`; `resize()` in place [release notes, 2026-07-16].
- Passthrough, scene, anchors, MRUK, hand tracking, controllers, spatial audio supported [developers.meta.com/horizon/blog/guide-meta-spatial-sdk-android-developers-mixed-reality-mobile/, 2026].
- **Hot reload**: Gradle plugin or push from Spatial Editor [spatial-sdk-hot-reload docs, 2026]. Spatial Editor v16.0 (Apr 2026).
- **Compose**: `meta-spatial-sdk-compose` + `ComposeFeature` [spatial-sdk-2dpanel-compose docs, 2026].
- **Video panels**: `VideoSurfacePanelRegistration` (max performance + DRM, bypasses the View system; recommended for high-res/performance-critical video) vs `ReadableVideoSurfacePanelRegistration` (post-processing at a cost) [spatial-sdk-media-playback docs, 2026]. Samples: `PremiumMediaSample`, `MediaPlayerSample`, `SpatialVideoSample` [github.com/meta-quest/Meta-Spatial-SDK-Samples].
- **Limits**: panel docs warn "avoid exceeding 2064×2208 px due to memory limitations" [spatial-sdk-2dpanel-resolution docs, 2026]. Display options: `DpPerMeterDisplayOptions` (500 dp/m), `DpDisplayOptions` (288 DPI), `PixelDisplayOptions` (exact pixel match — best for media), `ScreenFractionDisplayOptions` (50%). No documented cap on panel/layer count — unverified.
- Samples require Quest build v69.0+ (old numbering); AGP 8.11.1 / JDK 17 / Gradle 9.4.1; 13 samples + 4 showcase apps; `Meta-Spatial-SDK-Templates` backs the Horizon plugin for Android Studio.

## 2. OpenXR / Meta XR Mobile SDK (C/C++)

- **VrApi deprecated since 2022-08-31**; OpenXR is the standard [developers.meta.com/horizon/documentation/unity/os-openxr-vrapi/, current 2026-01-02].
- Passthrough via `XR_FB_passthrough`: compositor replaces a reserved layer; apps never get raw camera frames; `XrCompositionLayerPassthroughFB` at frame end; `environmentBlendMode` stays `OPAQUE` [mobile-passthrough docs, 2026].
- Quad/cylinder/equirect layers, `XR_FB_display_refresh_rate`, layer sharpening — not confirmed against raw NDK docs in this pass (unverified). Unity OpenXR:Meta 2.5.1 (2026-06-22) re-implemented passthrough on composition layers — mechanism exists.
- Community allegation (mbucchia, 2025-02-15) that Meta degrades non-Meta OpenXR backends — contested, unverified.

## 3. Horizon OS

- Version scheme switched from linear builds (v85, Jan 2026) to **"Meta Horizon OS 2" (2.x)** the week of 2026-02-23 [truenorthvr.com; heise.de, 2026-02-13]. Current **v2.7** (Aug 17 or 26, 2026 — sources differ) [en.wikipedia.org/wiki/Meta_Horizon_OS; meta.com/help/quest/172903867975450].
- **Android 2D apps can be published** on Horizon OS [developers.meta.com/horizon/documentation/android-apps/horizon-os-apps/, 2026-09-04].
- Third-party Horizon OS headsets (ASUS ROG, Lenovo) — program **paused 2025-12-17** [uploadvr.com; techcrunch.com, 2025-12-17].
- App Lab merged into the Horizon Store Aug 2024; basic technical/content/privacy requirements still apply [uploadvr.com, 2024-08-23].
- **VRC performance**: ≥60 fps; interactive apps at 72/80/90/96/100/120 Hz [developers.meta.com/horizon/resources/vrc-quest-performance-1/, 2026].
- Microphone shows as a user-visible permission; "Quest Privacy Essentials" compliance course [developers.meta.com/horizon/blog/meta-quest-privacy-essentials-for-developers-compliance/].
- Submit **≥2 weeks** before launch [developers.meta.com/horizon/resources/publish-submit/, 2026-05-08]. (20-day figures in press concern Facebook/Graph API review — likely conflated.)
- Horizon Start developer program + $1.5M competition (2025–2026 cycle) [developers.meta.com/horizon/resources/meta-start-developer-program/, 2026-05-11].
- IAP/subscription API details not pulled — unverified in depth.

## 4. Meta Voice SDK / Wit.ai

- **No deprecation found**; docs updated 2026-04-15 (Unreal), downloads 2026-02-10 [developers.meta.com/horizon/downloads/package/meta-voice-sdk/].
- Meta "Muse Voice Transcribe" real-time ASR announced ~2026-09-01 (Meta Superintelligence Labs) [thenewstack.io, 2026-09-01] — Quest positioning unverified.
- Android `SpeechRecognizer` on Horizon OS: **not confirmed working** — no Google Play Services, no registered recognition service; developers seek alternatives [communityforums.atmeta.com/discussions/dev-quest/on-device-speech-recognition-on-the-quest-with-unity/777619; reddit r/oculus, 2024-03-23]. Community-observed.
- No third-party system dictation API found (unverified).

## 5. Quest 3 / 3S hardware

- Both: **Snapdragon XR2 Gen 2** [qualcomm.com device finder, 2026].
- Quest 3: 2064×2208 px/eye, pancake lenses, 72/90/120 Hz [uploadvr.com/quest-3s-specs/, 2024-10-15]. Quest 3S: 1832×1920 px/eye, Fresnel, 120 Hz, 8 GB RAM.
- "240 Hz" claim (Threads, 2026-08-31) — unverified.
- Wi-Fi 6E (Quest 3) vs Wi-Fi 6 (3S): widely cited, not confirmed against a primary Meta spec in this pass.
- USB-C on both; data spec per model unverified.
- **Hardware video decode**: Meta docs say AV1 is not hardware-decoded on Quest 2/Pro — implying Quest 3/3S do [developers.meta.com/horizon/documentation/web/browser-video/, 2026]; community confirms HEVC + AV1 hardware decode on XR2 Gen 2. Working ceilings reported by streaming apps: HEVC 10-bit ~150 Mbps, AV1 120–200 Mbps [reddit r/virtualreality, 2024-09-11; forum.il2sturmovik.com, 2025-04-29].
- Bluetooth keyboard/mouse: standard Android HID. **Tracked keyboard** requires build v72+ on Quest 3/3S/Pro [meta.com/help/quest/382605190279228].
- Mic array count, hand-tracking version — unverified.

## 6. Meta's own remote desktop

- **Meta Virtual Display**: auto-installed on all Quest; Windows or Mac; up to 3 screens; presets 720p/1080p/1440p; Portrait/Compact/Wide/Ultrawide; requires Horizon OS v81+ and 5/6 GHz Wi-Fi [meta.com/help/quest/1370025034331518]. Free.
- 2026-09-08 UploadVR: renamed, gained **input forwarding**, **Mac parity**; "one first-party app for Windows PCs, Macs and external HDMI devices" [uploadvr.com/quests-meta-virtual-display-got-a-new-name-input-forwarding-mac-parity/].
- **Windows 11 Mixed Reality Link**: Windows 11 22H2+; Quest 3/3S; CPU/GPU tiers; gigabit + 802.11ac, 5 GHz preferred / 6 GHz recommended; ports 8264 TCP / 8265 TCP / 8266 UDP [support.microsoft.com/…/about-windows-mixed-reality-link, 2026]; Windows-on-Arm since Sept 2025 [windowscentral.com, 2025-09-01]. Use of private system APIs — not stated (unverified).

## 7. Developer tooling

- **MQDH** for device management, debugging, capture, submission; ADB 1.0.41; Developer Mode + ADB over Wi-Fi [ts-mqdh docs, 2026-04-17].
- **Meta XR Simulator**: experimental OpenXR runtime for PC/Mac, Unity/Unreal; native Kotlin support unverified.
- Kotlin/Compose first-class in Spatial SDK.
- **No Google Play Services** — no Play Billing, FCM, Google on-device speech; use Meta equivalents or cloud.
- Exact min/target API level for Quest submission not pinned (unverified).

## Key implications for a virtual-desktop app

- Build native on Meta Spatial SDK (Kotlin/Compose), not raw OpenXR/NDK — actively maintained (v0.14.0, Sept 2026), Panels/passthrough/MRUK/hands/hot reload.
- Use `VideoSurfacePanelRegistration` for the streamed-desktop surface.
- Cap streamed panel at ~2064×2208 px; use `PixelDisplayOptions` for 1:1 mapping.
- Never rely on VrApi.
- Do not assume Android `SpeechRecognizer` — bundle on-device or cloud ASR (or run STT on the host).
- HEVC 10-bit and AV1 both hardware-decoded on Quest 3/3S — pick by encoder support.
- ≥60 fps and 72/80/90/96/100/120 Hz are hard Store gates.
- Design for Quest 3/3S only (third-party Horizon OS paused).
- Meta's own Virtual Display / Mixed Reality Link may use private paths — budget for our own capture/encode/transport.
- Test loop: Spatial Editor + hot reload + MQDH/ADB over Wi-Fi.
- ≥2 weeks Store review lead time; microphone-permission disclosure mandatory.

## Unverified / conflicting

- OpenXR layer types, `XR_FB_display_refresh_rate`, sharpening for the raw NDK path.
- Muse Voice Transcribe positioning for Quest.
- Mic array count, hand-tracking version.
- Wi-Fi 6E vs 6 split, USB-C data generation.
- "20-day review" applies to a different Meta pipeline.
- Version numbering conflict (v81 vs 2.x) in Meta help pages.
- Whether Meta Virtual Display / Mixed Reality Link use system APIs closed to third parties.
- Meta XR Simulator support for native Kotlin.
- Exact min/target API level.

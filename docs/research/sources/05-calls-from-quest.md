# Source brief 05 — Video calls from Quest (web research, 2026-09-19)

Produced by a research sub-agent (Sonnet); 37 tool calls. Facts carry [source, date]; unverified in the last section.

## 1. How calls work on Quest today

- **Zoom**: free official **2D Android app** on the Horizon Store (flat window, standard client) [meta.com/blog, 2025-07-08; uploadvr.com, 2025-07-08]. **Horizon Workrooms** discontinued 2026-02-16 [meta.com/help; uploadvr.com, 2026-01-16]. Quest for Business winding down: commercial SKUs stop 2026-02-20, platform sunset 2030-01-04 [Forbes, 2026-01-16; UploadVR].
- **Microsoft Teams**: only "Teams Immersive" (Mesh successor, events/spaces) on the Store, 1.6/5 from 13 reviews [meta.com; Microsoft Learn, 2026-04-01]. No flat 2D Teams client found — likely absent (unverified).
- **Google Meet**: no native app; meet.google.com in the Horizon Browser; no authoritative source confirms reliable `getUserMedia` camera capture — open question.
- **Quest 3/3S cameras are outward-facing only**; Quest Pro (face/eye cameras) discontinued [Reddit, 2025-07-14]. **No Quest headset has a camera on the wearer's face** → any outgoing video must be synthetic.
- What the other side sees today: WhatsApp/Messenger on Quest support **Meta Avatar calling** (cartoon, audio-driven lip-sync) + "View Sharing" (~Sept 2024) [UploadVR, 2024-09-09]. A reverse-engineered "**Hologram Calling**" framework (Codec Avatars) found in Aug 2026 Horizon OS builds — no SDK/docs; Meta Connect 2026-09-23/24 may reveal more [vr.org, 2026-08-27] — speculative.

## 2. Passthrough Camera API

- Announced Sept 2024, experimental March 2025, **GA with Store publishing 2025-04-30** [roadtovr.com, 2025-03-18; developers.meta.com/horizon/blog, 2025-04-30].
- Docs (updated 2026-04-21): Quest 3/3S only; Horizon OS **v74+**; 1280×1280 added in **v83**; built on **Android Camera2**; 1280×960 or 1280×1280, **60 Hz**, YUV420, ~20–40 ms capture latency, ~1–2 % GPU per stream, ~45 MB RAM; permission `android.permission.CAMERA` or `horizonos.permission.HEADSET_CAMERA`; explicit opt-in + on-screen recording indicator mandatory; platforms Unity, Unreal, native, **Meta Spatial SDK**, Android panel apps; WebXR access "coming in v77" — current status unverified.
- No documented rule against piping frames into WebRTC/off-device; general "strict privacy requirements" store standard. Feasible for "show my desk/whiteboard"; irrelevant to the wearer's face.

## 3. Personas / face representation

- **Apple Vision Pro**: Persona from a face/hand scan; visionOS 26 (2025-06-09) improved spatial Personas [Apple Newsroom]; third-party apps (Zoom, Teams, Webex) receive the Persona only as a **flat 2D video frame** [Reddit r/VisionPro, 2025-09-30] — the proven pattern: synthesize the face, hand it to apps as an ordinary camera.
- **Meta Avatars SDK**: **End-of-Feature, v40.0.1 final** [developers.meta.com, updated 2026-04-08]. **Codec Avatars** still research (real-time full-body demo on Quest 3, Aug 2025) — no third-party path.
- Quest 3 gives zero facial input → avatar is **audio-driven only** (head pose + hands + voice).
- Building blocks: HeyGen Digital Twin/Avatar IV (generated video, not confirmed live two-way) [heygen.com; bigvu.tv, 2026-04-12]; **Tavus** real-time conversational avatars with APIs [tavus.io, 2025-07-16]; **Zoom's developer forum confirms Meeting SDK support for Tavus/HeyGen/LiveAvatar video avatars** [devforum.zoom.us, 2026-03-24]; **NVIDIA Audio2Face-3D** open-sourced 2025-09-24 (audio → facial blendshapes) [developer.nvidia.com]. No independent latency/acceptability benchmark for live business calls — unverified.

## 4. Host-side virtual camera / microphone

- **macOS**: **CoreMediaIO Camera Extension** (system extension, macOS 12.3+), signed + entitlement + user approval; code-signing is OBS's common failure [obs-studio#11026, 2024-07-24]; App Store viability proven by Camo; OBS uses this model on macOS 13+ [obs-versions.com, 2026-04-28].
- **Windows**: native **Media Foundation Virtual Camera API** (Win11 22000+), but many apps (incl. OBS virtual cam) still use **DirectShow** filters; the two are not interchangeable [obs-studio#13439, 2026-05-17; fakecam.net, 2026-07-13] → ship **both**.
- **Virtual mic**: macOS Core Audio HAL driver — **BlackHole** (zero added driver latency) [existential.audio]; Windows **VB-CABLE** (WASAPI) [vb-audio.com]. Pipeline: headset mic → AI denoise → host → virtual driver → meeting client.
- Client acceptance: Zoom/Teams/Meet enumerate normal OS devices; friction = ordering bugs (Teams misses a cam started after launch) [Ecamm, 2025-12-08] and signing breakage after OS updates; no confirmed blocking of virtual devices found (not exhaustive across enterprise policies).

## 5. Meeting AI prior art

- Capture models: **bot joins** (Recall.ai Meeting Bot API from **$0.50/hour**; Otter/Fireflies bots) vs **bot-free local capture** (Granola; newer Otter/Fireflies modes) [recall.ai/pricing; zackproser.com, 2026-08-02; granola.ai blog, 2026-03-20].
- Pricing 2026: Fireflies ~$10/user/mo [get-alfred.ai, 2026-09-02]; Krisp Core ~$8/mo annual, Advanced ~$15 [laxis.com, 2026-07-27]; Krisp 3.1/5 Trustpilot (411), overage-fee complaints [krisp.ai blog, 2026-03-27].
- **Cluely / Final Round AI** ("whisper me the answer"): detectable by interviewers, 2025 breach (~83,000 users), 5–10 s latency [interviewsidekick.com, 2026-08-25]; Final Round AI 2.9/5 Trustpilot (277). Reputational trap to avoid.
- Translation/captions native: Zoom AI Companion translated captions (30–46 languages, sources differ) [zoom.com; telsysinc.com, 2026-06-02]; Teams live translated captions; Meet/Gemini voice interpretation [deeptrue.org, 2026-01-01] → surface, don't rebuild.

## 6. Quest hardware for calls

- Mic: v64 (2024-04-08) improved mic quality and added external-mic support [meta.com/blog].
- **Open-ear speakers** audible nearby at mid/loud volume → no privacy without wired headphones [Reddit r/MetaQuestVR, 2025-01-14].
- Bluetooth audio latency poor unless aptX LL; headphone jack on Quest 3 (3S unverified) [androidcentral.com, 2025-09-23].
- Battery: "up to 2.2 h" (Meta); ~2.1 h gaming / 2.8 h productivity [reality-atlas.com, 2026-07-18]; Forbes 2026-09-15 "up to two hours".
- Notifications/multitasking during a call with Virtual Display open — **no source; hands-on test needed**.

## Implications

1. Use **Zoom Meeting SDK's avatar integration point** (Tavus/HeyGen/LiveAvatar) rather than fighting the client.
2. Don't build on Meta Avatars SDK (EoF) or Codec Avatars (no API).
3. Outgoing camera = **audio-driven synthetic avatar**; first-party latency/quality test before commitment.
4. Passthrough Camera API for "show my desk/whiteboard" — separate feature.
5. Ship CoreMediaIO extension (Mac) **and** DirectShow + Media Foundation (Windows).
6. Pair with BlackHole-class / VB-CABLE-class virtual mic + AI noise suppression.
7. Don't rebuild transcription/translation — surface platform captions in-headset.
8. Learn from Krisp overage backlash and Cluely detectability/breach.
9. Recommend/bundle wired audio.
10. ~2 h battery → design for mid-call charging.
11. Verify Horizon Browser `getUserMedia` and WebXR camera status before any browser-based approach.
12. Watch Meta Connect 2026-09-23/24 for Hologram Calling / Codec Avatar SDK.

## Unverified / conflicting

- Horizon Browser `getUserMedia` for Meet; WebXR passthrough camera status; Store policy on network-streaming passthrough frames; standalone Teams for Quest (Dec 2023) status; Zoom caption language count; live-call latency of HeyGen/Tavus avatars; Quest 3S headphone jack; notification behaviour during calls; Hologram Calling (single source).

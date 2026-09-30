# Local evidence collected 2026-09-19 (this machine)

Recorded before the web research; every line below was measured here, not recalled.

## Connected headset

- `adb devices -l` listed `192.168.0.253:5555 device product:eureka model:Quest_3 device:eureka`
  (adb at `~/Library/Android/sdk/platform-tools/adb`). A second query minutes later returned
  `device offline` and `adb connect` timed out — the headset had gone to sleep. Build
  properties (Horizon OS version, SDK level) therefore come from public sources, not from
  the device, and must be re-read with `adb shell getprop` when it is awake.

## Local Android toolchain

- Android Studio 2026.1; SDK platforms `android-35`, `android-36.1`; build-tools 35.0.0,
  36.1.0, 37.0.0; NDK 27.2.12479018. No system Java runtime on PATH (Studio bundles its own).

## Meta Quest Virtual Display.app (the official Meta macOS streamer, installed here)

Measured with `PlistBuddy`, `otool -L`, `codesign -d --entitlements`, `strings`:

| Fact | Evidence |
|---|---|
| Version 106.0.0.0.109, bundle id `com.meta.virtualdesktop`, macOS ≥ 13.3 | `Info.plist` |
| Internal codename **Highwind**; Bonjour service `_highwind_sp_v1._tcp.` | strings |
| Transport is **WebRTC** (`UDP/TLS/RTP/SAVPF`, SRTP, DTLS, `webrtc-datachannel`, transport-wide CC, GCC-style BWE) **plus a QUIC client** (`ServerTransportParametersExtension`, `-QUICClient-ResumptionCache`) | strings |
| Capture via **ScreenCaptureKit**, encode via **VideoToolbox**; linked `CoreDisplay.framework` (virtual displays), `Metal`, `Network.framework` | `otool -L` |
| Decoders/encoders referenced: H.264 (`kRTCVideoCodecH264Name`, `_h264BitstreamParser`), **AV1** (`_av1Decode`, `disable_av1sw_scaler`) | strings |
| Audio: bundled `libopus_mlow.dylib` (Meta's "MLow" Opus variant), WebRTC AGC2/clipping predictor | Frameworks dir, strings |
| App shell is **React Native / Hermes** (`main.hbcbundle`, `hermes::vm`) on top of a native RTC core | Resources, strings |
| Sparkle auto-update feed at `oculus.com/sparkle-updates/…` → distributed **outside the Mac App Store** (needed for virtual displays / input injection) | strings |
| `FileProvider.appex` + `MQRDContentService` → clipboard/file sharing between headset and Mac ("MQRD" = Meta Quest Remote Display) | PlugIns, strings |
| LoginItem `VirtualDesktopLauncher.app` (launch at login) | Library/LoginItems |
| Windows counterpart license link `aka.ms/WindowsAppForQuestLicense` → the Windows 11 path is Microsoft's "Windows App for Quest" | strings |
| Entitlements: `audio-input`, keychain groups `com.meta.highwindservice.SharedItems`; no App Sandbox | `codesign` |
| Endpoints: `graph.facebook.com`, `meta.graph.meta.com`, `gateway-edge.meta.com` (certificate signing "HighwindCertificateSigning" via GraphQL) — pairing is account-bound, not LAN-only | strings |

Implication: the incumbent's stack is *WebRTC + QUIC, ScreenCaptureKit + VideoToolbox, H.264/AV1,
Opus, Bonjour discovery, non-App-Store distribution*. A new product can match it with open
components (libwebrtc or a QUIC/RTP stack, ScreenCaptureKit, VideoToolbox, MediaCodec on Quest).

## Fabric ecosystem context (local checkouts of the organization's repositories)

- `fabric` — kernel of PassionCode.ai (Electron 44 + React 19 + TS, pnpm, local Supabase);
  vision: "operate Projects, not agent conversations"; agents replaceable via contracts.
- `fabric-agent-contract` 0.1.0 — profiles: **MCP capability** (rev `2026-07-28`,
  `streamable-http`/`stdio`), **A2A peer** (`1.0`, agent card), **local runner**
  (`fabric-local-runner/0.1`, kinds `claude-code`, `codex`, `cursor`, …). Commands are
  executable + argument array, never shell strings.
- `fabric-agent-adapter` — npm `@passioncode-ai/fabric-agent-adapter`, skills
  `adapting-projects-to-fabric`, `creating-fabric-agents`.
- `fabric-workspace` — private hosted workspace (Heroku).
- GitHub org `passioncode-ai` holds `fabric`, `fabric-workspace`, `fabric-agent-adapter`,
  `fabric-agent-contract`, `passioncode-ai.github.io`, `.github`. No `fabric-vr` repo yet.

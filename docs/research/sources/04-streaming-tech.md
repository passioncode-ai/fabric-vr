# Source brief 04 — Low-latency desktop streaming to Quest 3 (web research, 2026-09-19)

Produced by a research sub-agent (Sonnet) with WebSearch/WebFetch; 51 tool calls. Facts carry
[URL, date]; "unverified" where not directly confirmed.

## 1. How established products work

**Virtual Desktop** — codecs H.264, H.264+, HEVC (10-bit), AV1 (10-bit) [reddit r/virtualreality, 2024-07-17]; HEVC/AV1 cap 200 Mbps, H.264+ 400–500 Mbps on Quest 3 [mixed-news.com, 2023-10-05]. **VDXR** = own OpenXR runtime bypassing SteamVR (~10% gain) [uploadvr.com, 2023-11-07]. **SSW** = Synchronous SpaceWarp with Qualcomm. macOS streamer **rewritten in-house**, shipped ~April 2025, up to 3 virtual monitors on macOS; Apple's Mac Virtual Display still sharper [uploadvr.com/virtual-desktop-macos-streamer-update, 2025-04-01]. Pairing by Meta-account username + UPnP for WAN, no relay — shown exploitable [reddit r/MetaQuestVR, 2025-06-12].

**Immersed** — up to 5 monitors (4 virtual + 1 real) on Vision Pro; driver-less virtual displays on Mac and Windows; Linux limited [immersed.com/faq; uploadvr.com, 2024-05-09]. Encoders: NVIDIA ≥378.66, Intel QuickSync, VAAPI. Multi-monitor gated by tier (backlash 2024-05).

**Meta Quest Virtual Display (official)** — Windows 11 22H2+ via Microsoft "Mixed Reality Link"; older Windows and macOS via the Meta app; up to 3 displays; 720p/1080p/1440p; requires Horizon OS v81+ and 5/6 GHz Wi-Fi [meta.com/help/quest/1370025034331518]. Codec/protocol undisclosed publicly (see local-evidence note: WebRTC + QUIC, ScreenCaptureKit, VideoToolbox, H.264/AV1, Opus).

**Apple Vision Pro Mac Virtual Display** — Ultrawide = 10K horizontal via **foveation** (eye tracking) [uploadvr.com, 2024-12-11]; macOS 15.2+, Apple silicon; direct wireless link bypassing the Wi-Fi network. **visionOS 26.4** (Feb–Mar 2026) exposed a public **Foveated Streaming framework** built on **NVIDIA CloudXR** [9to5mac.com, 2026-02-16; developer.apple.com/documentation/foveatedstreaming; WWDC 2026 session 286]; ALVR opened an issue to adopt it [github.com/alvr-org/ALVR/issues/3206].

**Moonlight/Sunshine/Apollo** — Sunshine: self-hosted GameStream re-implementation; encoders NVENC/QSV/AMF (Windows), VAAPI/NvFBC/Vulkan Video (Linux), **VideoToolbox (macOS)**, software fallback; capture DXGI DDA + WGC (Windows), **ScreenCaptureKit (macOS)** [github.com/LizardByte/Sunshine]. Built-in FEC (default 20); HDR needs HEVC; AV1 ~30% more efficient than HEVC; 60–80 Mbps baseline, diminishing returns past ~120 Mbps at 4K60, cap 500 Mbps [niquette.ca, 2025-04-09]. **Apollo** fork: per-client virtual display at native resolution [github.com/ClassicOldSong/Apollo; tech-insider.org, 2026-09-09]. Valve **PyroWave** intra-only GPU codec (reported 2026-07-02); **Steam Link 2.0** beta ~Oct 2025 [reddit, 2025-10-29].

**ALVR** — TCP control + UDP stream; UDP broadcast discovery on 9943 with semver protocol ID; server authorizes [github.com/alvr-org/ALVR/wiki/How-ALVR-works]. Codecs H.264/HEVC via NvEnc/AMF/VAAPI; MediaCodec decode; loss recovery via IDR regeneration; AADT foveated encoding; planned Phase Sync, Sliced Encoding. Latency inconsistent: 40–100 ms encode on RTX 4090 vs 10–25 ms on RX 6900 XT [issue 1320]; 150 ms+ over wired ADB forwarding [issue 2594, 2024-12-26].

## 2. Host capture

**macOS**: ScreenCaptureKit `SCStream` per display/window/app, configurable frame rate, system + mic audio [WWDC22 10155/10156; recall.ai, 2026-07-02]. VideoToolbox low-latency H.264/HEVC [WWDC21 10158]. Apple Silicon encode ~200–350 fps at 1080p (M1/M1 Pro) [macrumors forums; yre.jp, 2026-03-20].
**Windows**: DXGI Desktop Duplication (Win32, best same-GPU with NVENC/AMF) [learn.microsoft.com]; Windows.Graphics.Capture (cross-GPU, UWP-friendly, Media Foundation) at some throughput cost.

## 3. Virtual displays

**macOS**: **no public API**; BetterDisplay, DeskPad, SimpleDisplay, OpenDisplay use private `CGVirtualDisplay` [simpledisplay.app; newsshooter.com, 2026-08-26] — blocks Mac App Store, fine for notarized direct distribution.
**Windows**: **IddCx** indirect display drivers [learn.microsoft.com]; Parsec VDD and `VirtualDrivers/Virtual-Display-Driver` (up to 8K/240 Hz), signed via SignPath Foundation rather than WHQL; 24H2 ARM64 may need test signing.

## 4. Input injection

**macOS**: `CGEvent` inject; requires Accessibility (+ Input Monitoring / Screen Recording) TCC grants. **Secure Input** (password fields, some terminals) blocks synthetic keystrokes and can get stuck [apple.stackexchange.com/331557].
**Windows**: `SendInput` fails under UIPI into elevated windows [learn.microsoft.com/nf-winuser-sendinput]; `KEYEVENTF_UNICODE` sends arbitrary Unicode with `wVk = 0`.

## 5. Transport

WebRTC congestion control = GCC (Kalman on inter-arrival delay), originally tuned around ~2.5 Mbps telephony [arxiv 2409.10042, 2024-09-16] → must be overridden/fixed-bitrate for 100–500 Mbps LAN. On LAN most glass-to-glass latency is capture/encode, not transport [transitiverobotics.com, 2026-05-06]. **MoQ** complementary, early in 2026 [cloudflare, 2025-08-22; red5.net, 2025-10-22]. USB-C tether: USB 3.0 ~5 Gbps, slightly better latency/bitrate than Wi-Fi 6E in community tests [reddit, 2025-04-03]. Dedicated Wi-Fi 6E router / 6 GHz SSID — strong community pattern [reddit, 2024-12-08].

## 6. Headset decode/display

XR2 Gen 2 decodes HEVC and AV1 in hardware; AV1 quality inconsistent for some; HEVC 10-bit ~150 Mbps or AV1 ~120 Mbps as workarounds [reddit, 2024-09-11; communityforums.atmeta.com, 2024-08-14]. Android low-latency decode is not standardized across devices [jahed.dev, 2025-10-25]; ~30 ms decode measured on Dimensity 9000 [moonlight-android issue 1241]. Audio: virtual audio sink on host; Opus 20 ms frames ≈ 26.5 ms encode latency [wowza.com].

## 7. Discovery / pairing

ALVR: UDP 9943 broadcast, server authorizes. VD: account username + UPnP, no relay (exploitable). **Tailscale** (WireGuard mesh) common for Parsec/Immersed/RDP remote reach [tailscale.com/blog, 2025-07-10].

## 8. Latency / bitrate

Air Link / VD e2e ~70–85 ms [store.pimax.com, 2025-07-16 — snippet only]. VD breakdown: decode largest component ~12 ms; 65–85 ms total on AV1; HEVC 200 Mbps decode ~8 ms over H.264 [communityforums.atmeta.com; facebook Quest2Community, 2025-01-08]. "20–50 ms wireless / 6–8 ms cable" — low-authority, unverified.

## Recommended stack options (LAN-first)

| Option | Approach | Build effort | Latency (LAN) | Mac | Windows | Licensing |
|---|---|---|---|---|---|---|
| **A. Native APIs + custom UDP** | SCK+VideoToolbox / DDA+NVENC, hand-rolled UDP+FEC (Moonlight/ALVR-style), CGEvent/SendInput, private `CGVirtualDisplay` / IddCx | High | Best (~20–40 ms class) | Full; virtual display needs notarized non-MAS | Full | Fully ours |
| **B. WebRTC-based** | libwebrtc/Pion/str0m, custom capturer over native encoders, GCC overridden | Medium | Good (~30–60 ms class) | Full | Full | BSD-style |
| **C. Fork OSS engine** | Sunshine host + custom Quest client, or ALVR | Low–Medium | ~20–50 ms LAN | Partial (macOS path less mature) | Full | **GPLv3-family copyleft risk** — verify before closed-source |

Given LAN-first, A or B are the safer commercial choices; C fastest to prototype but needs licence review.

## Unverified / conflicting

- Pimax 70–85 ms figure from snippet only.
- "20–50 ms / 6–8 ms" aggregator claim.
- Sunshine/ALVR exact licences not read from LICENSE files.
- Quest 3 panel resolution from a secondary source.
- Third-party access to visionOS Foveated Streaming unclear.
- ALVR encode-latency figures from older issues.

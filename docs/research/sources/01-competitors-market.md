# Source brief 01 — Competitors and market (web research, 2026-09-19)

Produced by a research sub-agent (Sonnet) with WebSearch/WebFetch; 34 tool calls. Facts carry
[Source, date]; anything not independently corroborated is in §7.

## 1. Products

**Virtual Desktop** — the incumbent PCVR/productivity streamer. $24.99 one-time on the Meta Horizon Store, rated 4.3★ (10,750 reviews); connects up to 4 Windows 10/11 computers, native low-latency streaming [meta.com/experiences/virtual-desktop, retrieved 2026-09]. Late-2025/2026 updates focused on Hands 2.3 hand-tracking stability for the cursor/keyboard flow [meta.com/blog, 2024-12-09; Reddit r/virtualreality, 2025-12-17]. Mac performance remains a known weak point (§2).

**Meta Virtual Display** (built into Horizon OS, formerly "Remote Desktop") — free, auto-installed on every Quest; Windows or Mac connect, up to 3 large virtual screens [meta.com/help/quest, retrieved 2026-09]. Renamed with Horizon OS 2.7 (week of 2026-08-17); the same release added **Input Forwarding** (control the connected computer with Quest hands/controllers) and brought Mac to feature parity with Windows — quick-connect in passthrough, display formats (Portrait/Compact/Wide/Ultrawide), text-size controls, multi-monitor [UploadVR, 2026-09-08]. Limitation: older Intel Macs can't trigger quick-connect; Windows and Mac still run different backend client software [UploadVR, 2026-09-08].

**Windows 11 Mixed Reality Link (Microsoft)** — free, preview Sept 2025, GA 2025-10-30 [blogs.windows.com, 2025-10-30]. Up to 3 virtual monitors, Windows 11 only; Microsoft Store rating 3.4★/57 as of 2026-09-15.

**Meta Horizon Workrooms** — discontinued 2026-02-16; "the Meta Quest Remote Desktop app will remain available" [meta.com/help/quest; UploadVR/Road to VR/Thurrott, 2026-01-16].

**Bigscreen** — free Beta app, 3.8★/6,198 reviews; social theater + screen sharing, not solo productivity. Sells Beyond 2 PCVR headset (2025).

**Fluid** — free social VR browser with screen sharing; beta PC-streaming mode with a reported 15-minute cap (unverified) [Reddit r/OculusQuest, 2025-06-23]. SideQuest 4.5★/1,155.

**Apple Vision Pro — Mac Virtual Display** — native visionOS feature; since visionOS 2.2 ultrawide/4K up to 5120×2880 [support.apple.com/118521; MacRumors, 2024-11-05], but a **single** virtual display [Gear Patrol, 2024-02-14].

**Xreal Nebula** — free; Mac gives up to 3 virtual desktops rendered locally (3DoF), no PC streaming pipeline [Reddit r/Xreal, 2025-08-02].

**Viture SpaceWalker** — free, Win/Mac/Android/iOS, multiple virtual screens; 6DoF for Luma Ultra since Sept 2025 firmware [viture.com/release-updates, 2025-12-27].

### Comparison table

| Product | Price | Platforms | Virtual monitors | Remote-access tech | AI features |
|---|---|---|---|---|---|
| Virtual Desktop | $24.99 one-time | Win 10/11 native; Mac via streamer | Multiple, up to 4 PCs | Proprietary low-latency Wi-Fi streamer | None |
| Meta Virtual Display (built-in) | Free | Windows + Mac (parity Aug 2026) | Up to 3 | Meta protocol + Input Forwarding | None (Meta AI is OS-level only) |
| Mixed Reality Link (Microsoft) | Free | Windows 11 only | Up to 3 | Microsoft remote-link protocol | None |
| Immersed | Free / $5.99+/mo Pro / $14.99/mo Collaborators [immersed.com/modes] | Win/Mac + Vision Pro/Quest | 3 (free) / 5 (Pro) | Cloud rooms + streaming | "Curator" AI, bundled only with unshipped Visor sub |
| Bigscreen | Free | Windows | Social screen | Streaming to shared room | None |
| Fluid | Free | Standalone-first; beta PC streaming | N/A | Browser + screen share | None |
| Apple Vision Pro Mac Virtual Display | Included (~$3,499 device) | Mac only | 1, up to 5120×2880 | Apple proprietary | None |
| Xreal Nebula | Free app | Mac/Win/Android/Steam Deck | Up to 3 | Local 3DoF render | None |
| Viture SpaceWalker | Free app | Win/Mac/Android/iOS | Multiple | 3DoF/6DoF local render | None |

## 2. User pain points (2025–2026)

- **Latency**: 30–150 ms baseline and spikes to 400 ms on Virtual Desktop, usually tied to router/Wi-Fi [Reddit r/virtualreality, 2026-02-20; 2026-02-03; 2026-06-04; 2025-10-15].
- **Mac performance gap**: "unusable" complaints still referenced in 2026; exactly what Meta's Aug 2026 parity update targeted [Reddit r/OculusQuest, 2024-02-05; UploadVR 2026-09-08].
- **Setup friction**: users trace stutters to routers/powerline; dedicated Wi-Fi 6/6E recommended — network diagnosis falls on the user [Reddit r/VRGaming, 2025-11-25].
- **Single-display ceiling on Vision Pro** [Gear Patrol, 2024-02-14].
- **Immersed Visor**: "doesn't support PC VR… just launches the Immersed app" [Reddit r/virtualreality, 2025-01-17].

## 3. AI-agent / voice-assistant prior art

Meta AI became system-level on Quest with Horizon OS v2.6 ("Hey Meta": opens apps, store search, screenshots, Q&A) — operates on the **headset OS only**, no desktop-control capability [vr.org, 2026-07-23]; framed as rehearsal for smart-glasses input. Immersed's Visor subscription ($40/mo×24 or $60/mo×12) bundles a "Curator AI assistant", but the headset is unshipped after four missed timelines [vr.org, 2026-07-27]. Outside VR: Razer Project AVA (CES 2026), Manus desktop agent (2026-03-18, CNBC), OpenAI voice/computer-use agent (2025-11) — none integrate with a VR display.

## 4. Market size signals

Conflicting: IDC-sourced coverage reports the MR/VR headset category contracting ~42–43% YoY in 2025 [shattered.io, 2026-07-06; quantumrun.com, 2026-09-04], while one blog claims Quest shipped 2.3M units in 2025 with 53% standalone share and 8.5M MAU [treeview.studio, 2026-04-28]. IDC forecast (2026-07-01): MR units 3.2M (2026) → 10.4M (2030). Omdia: VR shipments −4% in 2026 to ~10.5M. Reality Labs Q1 2026: $402M revenue / $4.028B operating loss; FY2025 loss $19.2B [vr.org, 2026-04-30]. Quest 3/3S prices raised $50–100 in April 2026 (DDR5 shortage). Quest Store cumulative content revenue ~$2.9–3B as of March 2025 [roadtovr.com, 2025-04-04]; 2025 IAP revenue +13% YoY, 100+ titles over $1M [developers.meta.com, 2026-03]. **Meta Horizon+** passed 1M subscribers in 2025, paid developers ~$20M [developers.meta.com/horizon/blog, GDC 2026].

## 5. Horizon Store business terms / Meta for Work

Horizon+ has Supplemental Terms over the Store Terms [meta.com/legal/quest/meta-horizon-plus-terms, 2026-07-21]. **Meta for Work / Quest for Business winding down**: sales of Horizon Managed Services and commercial SKUs stopped 2026-02-20 [meta.com/blog "An Update on Meta for Work", 2026-01-15; UploadVR, 2026-01-16]. "Support to 2030" — secondary press, unverified. No 2025/2026 primary source confirmed the paid-app revenue-share percentage.

## 6. Naming check — "Fabric VR" / "Fabric"

No active virtual-desktop or XR product trades as "Fabric VR". Collisions: **Microsoft Fabric** (data/AI analytics platform) dominates SEO/trademark for "Fabric" [news.microsoft.com, 2024-11-19]; **Fabric Engine** (VFX/games/VR tooling, discontinued Oct 2017) [cgchannel.com, 2017-10-28]; minor: academic "FabricVR" museum app, Minecraft "Fabric" mod loader, Meta's internal "Fabric Aggregator". Recommend a formal trademark search.

## Differentiation opportunities

- No competitor's assistant acts on the streamed PC — Meta AI controls only the Quest OS.
- Immersed's Curator AI is locked to an unshipped headset with a mandatory subscription.
- Mac is the most-cited pain point; Meta reached Mac parity only in Aug 2026; Vision Pro caps at one display.
- Network/latency setup friction is chronic — an agent that profiles Wi-Fi and auto-tunes bitrate turns support cost into a feature.
- Meta treats Quest voice AI as smart-glasses rehearsal, not desktop productivity.
- Hand-tracking text entry is rough — dictated/typed intent reduces in-air typing.
- Meta's enterprise retrenchment leaves the prosumer/solo-knowledge-worker niche.
- Clear the "Fabric" trademark before shipping the name.

## 7. Unverified / conflicting

- Quest 2025 shipments: −42% YoY (IDC via secondary) vs "2.3M units / 53% share".
- Current Horizon Store revenue-share percentage: no 2025/2026 primary confirmation.
- "Quest for Business support to 2030": secondary press only.
- Immersed Visor ship date unconfirmed.
- "HDMI support added to Virtual Display": Facebook group post only (2026-08-04).
- Fluid 15-minute cap: Reddit paraphrase.
- Vision Pro "good, not great" quality verdict dates to April 2024.

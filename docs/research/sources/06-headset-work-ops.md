# Source brief 06 — Working in a headset: what Horizon OS offers, pains, missing ops (web research, 2026-09-19)

Produced by a research sub-agent (Sonnet); 49 tool calls. Meta Connect 2026 (Sept 23–24) is after this brief. Facts carry [source, date].

## 1. What Horizon OS already offers for work

- **Virtual Display**: up to 3 virtual monitors; 2026-09-08 added **Input Forwarding** (controllers / paired keyboard+mouse drive the remote Mac/PC) and Mac quick-connect parity; fails on some older Intel Macs; Windows (Mixed Reality Link) and Mac (Meta client) are two stacks [uploadvr.com, 2026-09-08; meta.com Help].
- **Windows**: Navigator up to 3 attached panels; "hinged" layout 3 docked + 3 floating = 6 total [zdnet.com, 2024-07-05]; power users want 8–10 and use PCVR streaming [reddit, 2025-04]. Panels auto-snap to walls; **Automatic App Offload restores sessions** [meta.com blog, 2026-08-25].
- **Input**: hand tracking for panels; **Voice Control** (voice + head gaze); **on-device Voice Dictation** offline; experimental **surface keyboard/touchpad** on any flat surface [meta.com blog, 2026-08-25]. Physical keyboards since 2021; since v71 PTC (Oct 2024) passthrough cutout shows **any** keyboard the cameras see [uploadvr.com, 2024-10-11].
- **Meta AI "Hey Meta"** system-level from v2.6 (rollout 2026-07-23): opens apps, store search, screenshots, Q&A — entertainment-framed, no work-task delegation [vr.org, 2026-07-23].
- **Travel Mode** — 15-hour flight test praised over Vision Pro [tomsguide.com, 2024-06; uploadvr.com, 2026-02-27].
- **Gaps**: no native clipboard sync located by web search (recurring requests 2020 → Jan 2026) — **note: the local teardown of Meta Quest Virtual Display.app found clipboard plumbing (`MRDS_Clipboard`), so the truth is "exists in the Mac app, gaps remain for users"**; file transfer via USB or Horizon mobile app, users self-send WhatsApp messages [reddit, 2023-12]; **phone notifications** toggle exists but reported broken/removed 2023–2025 [reddit; Facebook, 2025-10]; 2D catalogue: Slack, Instagram, Dropbox, Spotify; Microsoft 365 as PWAs ("literally web apps") [reddit, 2024-04]; no native Notion found.

## 2. Pain points of hours in a Quest

- **Battery**: 1.5–2.5 h; drains faster than it charges under load [reddit, 2024-08; Meta forum, 2025-09; androidcentral.com, 2024-10].
- **Eye strain** top complaint for text work ("not worth it") [reddit, 2024-03; 2025-03]; some acclimate after ~2 weeks — mixed.
- **Comfort/weight**: forehead/face soreness [reddit, 2025-08].
- **Prescription glasses**: inserts industry (VR-Rock, Zenni, VR Optician) as de-facto fix.
- **Text input / gorilla arm**: quantified mid-air fatigue [ACM 2023; Springer 2025-08]; tracked physical keyboard the only workaround; copy/paste between remote desktop and headset UI a long-standing request.
- **"Suiting up" friction** (charge, updates, donning) kills daily habit [reddit, 2025-10; 2026-02]; abandonment reasons skew comfort/content [reddit, 2026-04].
- Immersed: battery drains even on USB under heavy compute; Visor single-purpose criticism.

## 3. What early adopters do

Virtual Desktop ($24.99) and Meta Virtual Display dominate; multi-monitor standard since 2024. **Coding** validated [reddit, 2023-10]; travel/hotel work validated. No rigorous session-length dataset (Immersed marketing vague). No evidence for multi-monitor trading on Quest.

## 4. Vision Pro benchmark

visionOS 26 (2025-06-09): spatial widgets placed around the room, rebuilt Personas, hands-free scrolling, spatial FaceTime; Personas in Zoom/Webex/Teams [apple.com; sixcolors.com, 2025-10]. WWDC 2026: expanded Mac Virtual Display + **Spatial Preview** framework (developer/content tooling) — office features beyond visionOS 26 unconfirmed. Sentiment cooled: AppleInsider "apathy" (2026-05), CNET M5 "subtle… still heavy" (2025-10), Stratechery content scarcity (2026-01). Traction: Slack, Zoom, Webex, Teams, Notion (iPad app), Fantastical ($4.75–8.99/mo) [9to5mac, 2024-02].

## 5. Adjacent wearables

Xreal One/1S, Viture Luma, RayNeo — monitor-replacement glasses; ZDNet 2026-03 picks Xreal 1S. **Meta Ray-Ban Display + Neural Band** ($799, 2025-09-17): EMG wristband; popular quick ops = **live captions/translation** [engadget.com, 2025-10], **dictating full replies** [uploadvr.com, 2025-11], **reading a message while occupied** [reddit, 2025-10]; complaints: bulky case, prescription lens integration.

## 6. AI-agent-in-the-loop patterns

**Claude Cowork** (~Jan 2026): Manual / Auto / Skip supervision modes; "Dispatch" from phone to desktop [support.claude.com; substack, 2026-04] → voice-only supervision needs status narration, confirm-before-risk, always-on interrupt. ChatGPT agent success rates contradictory (27.4 % / 32.6–38 % / 87 %) — unverified. OpenAI trial: AI cut weekly email time 31 % [openai.com, 2026-01]. **Copilot Actions/Workflows**: plain-language recurring automations over Outlook/Teams/SharePoint [microsoft.com, 2026-06].

## Top 15 operational actions a headset work-layer should provide

| # | Ops action | Pain removed | Evidence | Horizon OS today |
|---|---|---|---|---|
| 1 | Clipboard sync headset ↔ PC (↔ phone) | no copy/paste across VR fields and desktop | atmeta forums 2020; reddit 2026-01 | No / partial (Mac app plumbing exists) |
| 2 | Voice-native agent supervision (narrate, confirm, interrupt) | can't oversee a background agent while visually busy | Cowork modes | No |
| 3 | Power solution (hot-swap, low-power productivity mode) | 1.5–2.5 h, drains under load | reddit 2024-08 | No |
| 4 | Reliable real-world notification triage (calls, texts, calendar) | fear of missing events → remove headset | reddit/Facebook 2023–2025 | Partial (unreliable) |
| 5 | Universal passthrough keyboard view | gorilla arm; can't see keys | uploadvr 2024-10; ACM/Springer | Partial (v71 cutout) |
| 6 | More than 3 anchored panels natively | want 8–10 | reddit 2025-04 | Partial (6 cap) |
| 7 | Wireless file transfer | WhatsApp-to-self workaround | reddit 2023-12 | Partial |
| 8 | One connection stack Windows + Mac | two clients | uploadvr 2026-09 | Partial |
| 9 | Prescription solution | discomfort/scratches | reddit 2023-12 | Partial (3rd party) |
| 10 | Proactive calendar / "next meeting" in-headset | no glanceable schedule | absent | No |
| 11 | Eye-strain-aware text rendering | strain reading | reddit 2024-03/2025-03 | No |
| 12 | Live captions/translation for in-person interruptions | colleague walks in | engadget 2025-10 (glasses) | Partial (glasses only) |
| 13 | One-gesture "step into reality" with state preserved | suiting-up friction | reddit 2025-10 | Partial |
| 14 | Session/context persistence across relaunch | losing WIP | meta blog 2026-08 | Yes |
| 15 | Quick dictate-and-send for messages/notes | needs dedicated flow | uploadvr 2025-11 (glasses) | Partial |

## Unverified / conflicting

ChatGPT agent success rates; phone-notification reliability; Immersed/VD session data; Tom's Guide 2026-09-01 article content; Quest trading use; WWDC 2026 office features; Notion on Quest absence; Meta Connect 2026 (future).

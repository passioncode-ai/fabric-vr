> **SUPERSEDED on 2026-09-20 by `docs/handoff/2026-09-20-v2-entry.md`. Do not act on this file.**
>
> It describes the product **before the scope pivot it records**: a virtual-desktop app with an AI
> voice agent controlling the host computer. What was built is a notes-first Quest app, and the
> assistant is cut from v1 entirely (`DEC-0020`). Treat every architecture recommendation below as
> research into a product that was not built.
>
> It is kept for two things a later document cannot supply: **the scope pivot in the words used on
> the day it happened**, and the research corpus in `docs/research/` that the recommendations were
> drawn from. Banner added by `T-043` (`DEC-0055`); nothing below it was edited.

# Handoff — Fabric VR research (2026-09-19, updated after the scope pivot)

## Objective
Detailed research and an architecture report for Fabric VR: native Android (Meta Spatial SDK)
virtual-desktop app for Quest 3/3S with an AI voice agent controlling the host computer and a
Speech-to-Text dictation button.

## Scope pivot (same day)
The operator dropped own desktop streaming (Meta Virtual Display is the display) and redirected
to a work layer: voice + dictation, host agent, calls (Meet/Zoom/Teams), "Notion for VR" notes
and sketches, cross-device clipboard, everyday ops, packaged around a user-owned accumulating
context. Recorded in `docs/research/2026-09-19-scope-pivot.md`.

## Second pivot (later the same day) — notes-first
Identity fixed as "your VR Notes": notes KB core, voice/STT/storage/files/clipboard as tools that
work everywhere, agent as conversational partner, beside any desktop app. Calls demoted to a
later tool. `docs/product/product-definition.md` is now the entry point and supersedes the
work-layer report's module order and roadmap. Spatial SDK `HybridSample` confirms the 2D-panel +
optional-immersive shape.

## Completed
- Local evidence: connected Quest 3 (wireless adb, went offline), Android toolchain, teardown of
  the official `Meta Quest Virtual Display.app` (WebRTC + QUIC, ScreenCaptureKit, VideoToolbox,
  H.264/AV1, Opus MLow, Bonjour `_highwind_sp_v1._tcp`), Fabric contract context —
  `docs/research/local-evidence-2026-09-19.md`.
- Four web-research briefs (Sonnet sub-agents, 34/34/43/51 tool calls) —
  `docs/research/sources/01…04`.
- Main report (RU) — `docs/research/2026-09-19-fabric-vr-research-report.md`: TL;DR, market,
  platform, streaming, architecture (components, channels, voice pipeline, agent + policy,
  pairing, observability), decisions D1–D10, roadmap phases 0–7, risks, unverified list.

- Second research wave (Sonnet sub-agents, 37/49/51/41/45 tool calls) — `docs/research/sources/05…09`
  (calls, headset work ops, analogous markets and moat, clipboard sync, spatial notes).
- Second report (RU) — `docs/research/2026-09-19-fabric-vr-work-layer-report.md`: market
  summary, modules M1–M6, architecture, the accumulating asset on the Fabric Memory Kernel,
  packaging (recommendation: "the agent you talk to" + local-first trust), roadmap phases 0–7,
  risks, unverified list.

## Decisions taken (first report §6; still valid where not superseded)
Meta Spatial SDK (Kotlin) for the headset; WebRTC with custom bitrate control; HEVC 10-bit
primary; STT and agent on the host; Claude Agent SDK + computer use with AX/UIA-first hybrid
perception; cascaded voice pipeline; Mac first, notarized outside the App Store; PTT not wake
word; no Sunshine/ALVR forking (GPL); LAN PIN/QR pairing without accounts.

## Open work (next task, in order)
1. **Phase 0 spikes of `docs/product/product-definition.md` §5**: (a) Compose 2D panel running
   beside Meta Virtual Display on the device + Hybrid switch to immersive; (b) Quest mic → host →
   STT → text insertion in 5 apps; (c) MX Ink strokes in Spatial SDK → Excalidraw JSON; (d) CRDT
   vault sync Quest ↔ Mac; (e) LAN clipboard Mac ↔ Quest. Call-related spikes (virtual camera,
   avatar) wait for phase 6. Each spike through
   `/task-pipeline`; product behaviour first through `/ux` (`docs/ux/scenarios.md` does not
   exist yet — run `/ux` before any UI work).
1b. Watch Meta Connect 2026-09-23/24 (Hologram Calling / Codec Avatar SDK, Meta AI on Quest)
   and update report §2.2 / §8 if the avatar strategy changes.
2. Re-read the headset when awake: `adb -s 192.168.0.253:5555 shell getprop` (Horizon OS
   build, SDK level) and record in local evidence.
3. Resolve the unverified items in the work-layer report §10 that block a spike (Passthrough
   Camera streaming policy, ML Kit on Horizon OS, Horizon Store anti-steering).
4. Trademark search for "Fabric VR" before any Store listing.

## Prerequisites
Quest 3 in Developer Mode on the same Wi-Fi; Android Studio 2026.1 (installed); Meta Spatial
SDK v0.14+ and Spatial Editor; an Anthropic API key on the host (Keychain, never in the repo).

## Checks actually run
- `adb devices -l` (device seen, then offline); `PlistBuddy`, `otool -L`, `codesign -d
  --entitlements`, `strings` over the Meta app bundle; a local skill-inventory listing.
- No code, no tests — this is a research deliverable. `git log` lists every commit of the day.

## Entry point
`README.md` → report → this file.

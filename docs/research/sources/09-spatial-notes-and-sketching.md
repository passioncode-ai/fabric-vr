# Source brief 09 — Spatial notes, sketching, "Notion for VR" (web research, 2026-09-19)

Produced by a research sub-agent (Sonnet); 45 tool calls. Facts carry [source, date]; unverified flagged.

## 1. Prior art

- **Meta first-party**: Workrooms whiteboard (sticky notes since v1.4, 2022-06) was removed in the March 2024 update; Workrooms shut down 2026-02-16 with data deleted [meta.com/help; uploadvr; mashable, 2026-01]. "Layouts" on developers.meta.com is a UI design-system concept, not a notes app. **Horizon Dock** — free Quest overlay floating notes/tasks/calendar [meta.com, 2026-03] — canvas-only, no search/tags/links.
- **VR-native canvas tools** (all canvas-first, none a searchable/linked KB): ShapesXR (free, 4.1★/393), Gravity Sketch (free community rooms, 2026-01), Arkio (tiered), Figmin XR (4.3★/195; persistent placement + colocation [uploadvr, 2024-12]), **Softspace/Spaceframe** ("spatial notebook for research, thinking, writing" — nearest analog to Notion-for-VR; 3.1★/113; maintenance note about a 3rd-party service shutdown; 2025–26 cadence unverified).
- **visionOS**: Freeform (Logitech Muse haptics in 26.2 beta); Apple Notes "just a floating notes app" [toolfinder.com, 2026-03]; Notion only as iPad-compat window; no Obsidian VR client. **Kosmik** (desktop/tablet; local files + tags + infinite canvas + browser/PDF) reportedly shutting down 2026-07 [keystone-studios.com] — canvas-PKM sustainability caution.
- **Desktop-in-headset**: Immersed (4.3★/4,350) whiteboards without structured notes; Virtual Desktop "zero features" beyond display.
- **Gap**: no product combines spatial capture with linked/searchable knowledge-base organization.

## 2. Input devices for sketching

- **Logitech MX Ink** ($129.99, shipped 2024-09-25): 6DoF IR-tracked stylus, pressure tip + haptics, ~7 h battery; flat surface (MX Mat $49.99) or in air [logitech.com; mixed-news, 2025-01]. Dedicated **OpenXR stylus interaction profile** + Touch-controller emulation; Horizon OS v69+, Core SDK v68.0.2+; Unity, Unreal, WebXR docs [logitech.github.io/mxink]. Supported: ShapesXR, Gravity Sketch, PaintingVR, Vermillion. "Far more precise than controllers" [Reddit, 2025-06]; IR tracking tolerant of hand occlusion [vr-wave.store, 2024-10]; conflict with Touch controller under Body-tracking API reported 2024-08 (2026 status unverified). Integration-challenges paper [ResearchGate, 2025-04].
- **Hand tracking**: mean positional error ~1.73 cm on Quest (1.22 cm Quest Pro) [PubMed]; Hand Tracking 2.4 (2025-12) still "floaty" for precise strokes → stylus/controller default for legible handwriting.
- **Text**: on-device Voice Dictation (EN-US; expansion unverified); **Surface Keyboard** (Horizon OS v85, 2026-01-30) turns any flat surface into a tracked keyboard [uploadvr; forbes]. No Meta stylus/"digital paper" announcement found.

## 3. Handwriting and sketch recognition

- **MyScript iink SDK**: activation-certificate licensing; enterprise packs from 100 licences at €12 each/yr [myscript.com/enterprise]; iink 4.5 (2026-06) adds text-to-handwriting and unified free-writing infinite canvas — fits a VR notebook; self-serve pricing unverified (JS page).
- **Google ML Kit Digital Ink Recognition**: on-device, offline, ~20 MB/language model, API 23+ [developers.google.com]; Play Services dependency on Horizon OS **unverified — test directly**.
- **Sketch→diagram**: tldraw "Make Real" (sketch → HTML via LLM); Napkin AI / Whimsical AI (text → diagrams); Flowchart2Mermaid [arXiv 2512.02170, 2025-12]; whiteboard-photo → Mermaid via vision LLMs common in 2026 dev tooling [mermaid.ai blog, 2026-04]. Feasible today with multi-pass self-correction, not one-shot.

## 4. Voice notes → structured notes

Granola (bot-free capture; Basic capped; Business $14/user/mo; Enterprise $35+) [granola.ai, 2026-02; itsconvo, 2026-09]; Otter Pro $8.33–16.99, Business $20–30; AudioPen Prime ~$75/yr (rewrites rambling speech) [speakwiseapp, 2026-06]; Voicenotes.com Pro ~$120/yr or lifetime [Medium, 2025-05]. Auto-tag/auto-link marketed as differentiators (Kosmik, Saner AI) [taskade; saner.ai, 2026-03/04]; spaced resurfacing / daily digests rare → differentiation opportunity.

## 5. Storage and sync

- CRDT engines: **Yjs** (broadest ecosystem), **Automerge** (JSON-native, 3.x columnar compression), **Loro** (1.0 2024, Git-like versioning, fastest in 2026 benchmarks, least mature) [loro.dev; pkgpulse, 2026-04].
- **Excalidraw / tldraw** persist scenes as plaintext versionable JSON (Excalidraw schema documented; tldraw snapshot + Excalidraw import); **InkML/SVG** remain the cross-tool stroke interchange formats.
- **Meta Spatial Anchors/MRUK**: persistence across sessions + sync/async sharing across users/devices [developers.meta.com, 2025-12]; **drift** acknowledged (Meta help: "clear physical space history"); Scene API export limits — no primary doc found (unverified).

## 6. Spatial UX evidence

PMC 2022: VR memory-palace improves recall with minimal guidance. bioRxiv (2024-11-26, rev. 2025-08-30): fMRI — reliability of a room's spatial representation before learning predicts recall of objects placed there — mechanistic support, but controlled 23-room VR set. visionOS 26 persistent widgets anchored to walls across restarts "a game changer" [MacStories, 2025-09] but "uncanny" while moving, shallow gallery [Six Colors, 2025-10; AppleInsider, 2026-05]. Quest failure modes: anchor drift, "finding position in room" interruptions [Reddit, 2025-05], room-scan data underused for persistent content.

## Recommended note/sketch stack

- **Capture**: MX Ink primary (pressure + surface, OpenXR profile), hand tracking/controllers fallback; BT keyboard + Surface Keyboard for text; on-device dictation for voice notes.
- **Recognition**: MyScript iink for handwriting→text (infinite-canvas mode); do not depend on ML Kit until Play Services dependency verified on Horizon OS.
- **Sketch→diagram**: multimodal LLM pipeline with structured prompt + self-correction; output Mermaid (text-portable) and SVG/Excalidraw JSON (editable).
- **Storage**: plaintext versionable JSON per note/canvas (Excalidraw-style) for strokes/diagrams; Markdown for text; raw ink as InkML/point arrays for re-recognition.
- **Sync engine**: Automerge or Loro (JSON-native, versioned) for offline-first multi-device merge; Yjs if web ecosystem prioritized.
- **Sync targets**: two-way to a folder of Markdown (Obsidian-vault-compatible) as canonical KB; optional one-way push to Notion API.
- **Spatial placement**: Spatial Anchors/MRUK for anchoring to real surfaces with a **non-spatial list view always available** — anchor persistence must not be a single point of failure.
- **Recall design**: few stable, distinctive anchor locations (desk-left, wall-above-monitor), not dense clutter — context reliability, not quantity, predicts recall.
- **Agent role (host)**: transcribe + extract entities/actions; auto-link (Zettelkasten backlinks); rough sketch → clean Mermaid/diagram on request; periodic digest/resurfacing of stale notes — the differentiation.
- **KB UX**: tags/search/backlinks from day one — every VR-native competitor is canvas-only.
- **Degradation**: notes reachable through flat KB view if anchors drift or the room changes.

## Unverified / conflicting

MyScript self-serve pricing; ML Kit Play-Services dependency on Horizon OS; "Notion VR" claims; Softspace/Spaceframe status; Scene API export limits; Meta stylus plans; MX Ink + controller limitation in 2026; Kosmik shutdown specifics.

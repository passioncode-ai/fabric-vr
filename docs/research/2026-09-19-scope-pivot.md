# Scope pivot — 2026-09-19 (operator decision, same day as the first report)

After reading the first research report the operator redirected the product:

1. **Do not reinvent the display.** Meta Virtual Display (built into Horizon OS 2.7, free, up to
   3 screens, input forwarding, Mac parity) is the desktop. Fabric VR builds the **work
   infrastructure on top of it**.
2. **Keep**: voice input, speech-to-text button, the AI agent on the host computer.
3. **Add — calls**: video/audio streaming so the user can join and run Google Meet, Zoom and
   Microsoft Teams calls from the headset (camera/persona, microphone, meeting AI).
4. **Add — "Notion for VR"**: voice notes, text notes, hand-drawn sketches and diagrams,
   organised as a knowledge base.
5. **Add — clipboard sync** between headset, desktop (Mac/Windows) and mobile (iPhone/Android).
6. **Research questions**: which other operational actions ("ops") knowledge workers lack in a
   headset; where the market deficit is; how analogous markets developed; how to package the
   product so it is unique, delivers long-term value and **compounds per user through
   accumulated data**.

Research sub-agents launched for items 3–6 (Sonnet): calls, headset work ops, analogous markets
and data moat, spatial notes and sketching, clipboard sync. Results land in
`docs/research/sources/05…09` and the second report
`docs/research/2026-09-19-fabric-vr-work-layer-report.md`.

Local findings relevant to the pivot (measured on this machine, see local-evidence note):
`Meta Quest Virtual Display.app` already syncs the clipboard headset↔Mac (`MRDS_Clipboard`,
`DefaultExtractorsMac.cpp`) and tracks the focused element/selection via Accessibility
(`InputFocusCaptureMac`); it exposes **no virtual camera or microphone** to meeting clients.
The Fabric contract's Memory Kernel (`fabric-agent-contract/docs/specification/
memory-and-learning.md`: episodic / semantic / experiential records with provenance,
project-scoped, append-only ledger) is the natural home for the accumulating per-user asset.

## Second pivot, later the same day — notes-first

The operator narrowed the identity: **the product is personal VR notes** — notes (text, voice,
hand-drawn sketches, diagrams, files) as a knowledge base are the core; voice input /
speech-to-text, storage, file transfer and clipboard sync are **tools that work everywhere**, not
only inside notes; the AI agent is the conversational partner over the notes and the computer.
It sits on top of **any** desktop app (Meta Virtual Display, Virtual Desktop, Immersed, future
ones) — infrastructure beside them, never a competitor. Calls move from core to a later tool.

Recorded in `docs/product/product-definition.md`, which supersedes the module order and roadmap
of the work-layer report. Verified for the shape: Meta Spatial SDK ships `HybridSample` — a
standard 2D panel app that can switch to an immersive experience hosting the same panel
(github.com/meta-quest/Meta-Spatial-SDK-Samples README, read 2026-09-19). The session's web-search
budget was exhausted at this point (200/200); the remaining checks are listed in the
definition's §7.

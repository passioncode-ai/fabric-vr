# Source brief 03 — Computer-use agent and voice pipeline (web research, 2026-09-19)

Produced by a research sub-agent (Sonnet) with WebSearch/WebFetch; 43 tool calls. Facts carry
[source, date]; conflicts in the final section.

## 1. Computer-use agents

**Anthropic.** Web docs fetched 2026-09-19 name the production tool `computer_toolset_20260801` (no beta header; Opus 5, Sonnet 5, Opus 4.8, Fable 5/Mythos 5) [platform.claude.com/docs/…/computer-use-tool; daily.dev, 2026-08-22]. Older models: beta `computer_20251124` (adds `zoom`), `computer_20250124`. Note: the local `claude-api` skill copy on this machine (checked 2026-09-19) still names `computer_20251124` only — re-verify the tool string at build time. Actions: `screenshot`, `zoom`, click variants, drag, `mouse_move`, `scroll`, `type`, `key`, `hold_key`, `wait`, `cursor_position`. Coordinates are in screenshot-pixel space; scale screenshots down (1568 px long edge / ~1.15 MP older tool; up to 2576 px / ~4784 visual tokens new toolset) and scale coordinates back, with a macOS Retina 2× caveat. Recommended screenshot 1024×768 (XGA) [simonwillison.net, 2024-10-22; anthropic-quickstarts]. New toolset reportedly cuts round-trips 20–40% [daily.dev, 2026-08-22]. **Claude Cowork / Claude Code** can drive the local computer (background windows on macOS 15+, doesn't steal keyboard/pointer), per-app permission prompts, default blocklist for banking/crypto apps, prompt-injection scanning, Pro/Max only, beta [support.claude.com/…/let-claude-use-your-computer-in-cowork, 2026-09-19]. **Claude Agent SDK** packages the Claude Code harness (Read/Write/Edit/Bash/Grep/WebSearch, MCP, hooks, subagents, permissions) as a self-hosted library [code.claude.com/docs/en/agent-sdk/overview].

**OpenAI.** `computer-use-preview` in the Responses API (click/double_click/drag/move/scroll/keypress/type/wait/screenshot; Playwright or PyAutoGUI); preview models can be retired on two weeks' notice [developers.openai.com/api/docs/guides/tools-computer-use; …/deprecations]. Safety guidance: VM/sandbox, allowlist, confirmation for irreversible actions, step/time/cost limits. Agents SDK harness rewritten 2026-04-15.

**Google.** Gemini computer-use models (`gemini-3.8-flash` recommended; legacy `gemini-2.5-computer-use-preview-10-2025`); coordinates normalized 0–1000; actions carry `intent`; built-in prompt-injection detection [ai.google.dev/gemini-api/docs/computer-use, 2026-09-19].

**Open source.** Agent S3 (Apache-2.0): 72.60% OSWorld, screenshots + UI-TARS grounding model [github.com/simular-ai/agent-s]. UI-TARS-2 ~53% OSWorld-Verified. **Simular "Sai"** ($50–500+/mo) prototypes voice-driven computer control from Meta Ray-Ban glasses with mid-run interruption [simular.ai/research, verified 2026-09-11] — closest analogue to this product.

**Architecture consensus**: hybrid — accessibility tree first (macOS AXUIElement, Windows UI Automation), screenshot + `zoom` fallback [openowl.dev, 2026-08-10; abhinandan.one, 2026-04-24].

**Safety patterns**: sandbox/minimal privileges, allowlists, human confirmation for irreversible actions, step/time/cost bounds with cancellation, on-screen content treated as untrusted (prompt injection), visible kill switch [platform.claude.com; straiker.ai, 2026-07-31; bbc.com, ~2026-09-15].

**OSWorld-Verified** (llm-stats.com, 2026-09-19): Claude Fable 5 0.850, Opus 4.8 0.834, Sonnet 5 0.812, Opus 4.7 0.780, GPT-5.5 0.787, Qwen3.8 Max 0.861. Other leaderboards disagree (see §7).

## 2. Speech-to-text

**On-device Apple Silicon**: WhisperKit (CoreML/ANE) sub-200 ms, 2–8% WER streaming [forasoft.com, 2026-08-05]; large-v3 15–30× realtime [macparakeet.com, 2026-03-14]; whisper.cpp / faster-whisper (CTranslate2, ~4× faster than reference) [github.com/SYSTRAN/faster-whisper]. Apple `SpeechAnalyzer`/`SpeechTranscriber` (on-device) reportedly faster than Whisper in some tests [reddit r/apple, 2025-06-18].
**Open models**: NVIDIA Parakeet-TDT 0.6B v2 — 6.05% WER, ~50× realtime [developer.nvidia.com, 2025-06-04]; Parakeet/Canary top the HF Open ASR Leaderboard [2026-02-27]. Kyutai STT streaming [kyutai.org/stt].
**Cloud streaming**: Deepgram Nova-3 ~150–300 ms server, 200–500 ms e2e, ~5.26% batch WER EN; AssemblyAI Universal-Streaming $0.15–0.45/hr, async $0.21/hr [assemblyai.com/blog, 2026-07-08/15]; OpenAI gpt-4o-transcribe ~$0.006/min, mini ~$0.003/min [vexascribe.com, 2026-07]; GPT-Realtime-Whisper $0.017/min [coval.ai, 2026-06-04]; ElevenLabs Scribe v2 Realtime sub-150 ms, ~2.3% error [the-decoder.com, 2026-07-29]. Prices vary ~30× across vendors.
**Quest**: built-in dictation is **US/English-only** [meta.com/help/quest/463323051789865]. Recommendation: capture mic on Quest, ship PCM over the existing link, run VAD/STT on the **host** (local model; cloud fallback).

## 3. Voice pipeline

- VAD: Silero VAD (open); Picovoice Cobra claims to beat it (vendor claim). Wake word: Porcupine (>97%, offline, commercial), openWakeWord (free, mixed reliability). Push-to-talk avoids false triggers.
- Speech-to-speech (Kyutai Moshi/Unmute sub-300 ms; OpenAI Realtime WebRTC rebuilt 2026-05-04; Gemini Flash Live ~200 ms) vs **cascaded STT→LLM→TTS** — cascaded remains dominant for tool-using agents; Pipecat/LiveKit implement barge-in [cekura.ai, 2026-07-28].
- TTS TTFA: Cartesia Sonic 3.5 ~40 ms vendor / ~188 ms independent P50 [inworld.ai, 2026-01-22; gradium.ai, 2026-05-05]; ElevenLabs Flash v2.5 ~75 ms vendor, variable independently; Kokoro-82M open, CPU, sub-300 ms [vexyl.ai, 2026-01-25]; Piper, Apple `AVSpeechSynthesizer` zero-cost fallbacks.
- Audio to headset: reuse the PC-VR link (Air Link/VD/Steam Link e2e 47–85 ms) [store.pimax.com, 2025-07-16].

## 4. Agent runtime frameworks

Claude Agent SDK (Python/TS): Claude Code harness, MCP client, hooks (tighten only), permissions, subagents [code.claude.com/docs/en/agent-sdk/overview]. OpenAI Agents SDK (computer-use tool, 2026-04 harness). LangGraph (stateful graphs). Pipecat (frame pipeline VAD→STT→LLM→TTS→transport). LiveKit Agents (WebRTC server, server-side VAD, LiveKit Inference, barge-in) [forasoft.com, 2026-07-13]. All can call Anthropic models; MCP/A2A exposure native to the Agent SDK.

## 5. Model choice

Claude Opus 5 (`claude-opus-5`, 1M ctx, $5/$25 per MTok), Sonnet 5 (`claude-sonnet-5`, 1M ctx, $3/$15; intro $2/$10 through 2026-08-31), Haiku 4.5 (`claude-haiku-4-5`, 200K, ~$1/$5), Fable 5 (~$10/$50) [anthropic.com/news/claude-sonnet-5, 2026-06-30; claude.com/pricing; local `claude-api` skill confirms Opus 5 $5/$25 and Sonnet 5 $3/$15]. Computer-use tool: Opus 5, Sonnet 5, Opus 4.8, Fable 5/Mythos 5. Suggested: Haiku 4.5 for intent/cleanup, Opus 5 (Sonnet 5 cost-sensitive) for the control loop.

## 6. Existing voice + computer-control products

Dictation apps insert text via OS accessibility API or synthetic paste. Wispr Flow $12–15/mo; Superwhisper $249.99 lifetime; Aqua Voice $8/mo (97.3% claimed); Willow ~$12/mo, ~200 ms claimed [getvoibe.com, 2026-03-18; willowvoice.com, 2025-10-30]. Microsoft Copilot Studio computer use GA May 2026 (hosted Windows VM, UI Automation) [microsoft.com, 2026-05-26]. Apple App Intents: no third-party API to invoke other apps' intents [WWDC 2026 session 8011]. Simular Sai — see §1.

## Recommended AI architecture (from the brief)

- Claude Agent SDK on the host with the current computer-use toolset, Opus 5 primary, Sonnet 5 fallback.
- Hybrid perception: AX/UIA first, screenshot+zoom fallback at XGA.
- Haiku 4.5 for dictation cleanup/intent.
- Dictation STT local on host (WhisperKit / faster-whisper / Parakeet), cloud fallback (Deepgram / AssemblyAI).
- Text insertion via accessibility API, synthetic paste fallback.
- Cascaded STT→LLM→TTS via Pipecat or LiveKit Agents for barge-in.
- Silero VAD + push-to-talk; Porcupine later if hands-free needed.
- TTS: Cartesia/ElevenLabs Flash, Kokoro-82M offline fallback.
- Reuse the desktop-streaming link for audio.
- Safety: sandbox boundary, app/action allowlist, confirmation for irreversible actions, in-VR kill switch.
- MCP as the integration seam; stable tool contract across model fallback.

## 7. Unverified / conflicting

- OSWorld scores disagree by source/variant (Fable 5 0.850 vs "Opus 5 70.6%" vs "39.6% strict").
- OpenAI 2026 computer-use model naming (gpt-6-astra / gpt-5.6-sol) partially confirmed.
- Picovoice Cobra vs Silero claim vendor-sourced.
- No spec for Opus-over-WebRTC to Quest specifically.
- TTS TTFA vendor vs independent figures differ 2–5×.
- `computer_toolset_20260801` (web) vs `computer_20251124` (local skill copy) — verify at build time.

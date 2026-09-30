# Fabric VR

Notes-first work layer for Meta Quest: a knowledge base of notes captured by voice, text and
(later) sketches, with tools that work everywhere in the headset and on the computer, and an
AI you talk to. Lives beside any virtual desktop, never is one.

## Language

**Note** <a id="note"></a>:
One captured item in the knowledge base — text, a voice recording with its transcript, or (later)
a sketch — with a stable id, tags, timestamps and a Markdown mirror in the Vault.
_Avoid_: page, document, memo

**Transcript** <a id="transcript"></a>:
The text produced from a voice recording by an STT engine; it belongs to its Note and records
which engine produced it and the detected language. **There are three producers, not two**
(`SttProvider.kt:11-20`): `LOCAL` — on-device whisper.cpp, nothing leaves the headset; `CLOUD` —
an OpenAI-compatible endpoint (`DEC-0013`: one route, `POST {base}/v1/audio/transcriptions`,
whoever is behind it), a third party that receives the recording; `SERVER` — a
whisper.cpp server, typically the person's own machine. This entry named the first and the third
for eleven commits and omitted the only one that sends audio off the person's own hardware, which
is the one a reader of a glossary most needs to find here (`DEC-0053`).
_Avoid_: caption

**Dictation** <a id="dictation"></a>:
One act of speaking a Note — from the press that opens the microphone to the saved Note and its
Transcript. It is the **act**, not its text: the text is the [Transcript](#transcript), and the
audio kept beside it is the recording.
_Avoid_: voice memo, capture, recording (the recording is the `.wav` file, not the act)

> **Why this entry exists** (`REQ-061`, audit `M27`). *Transcript* listed "dictation" under
> _Avoid_ while eight user-facing strings and most of the codebase used the word — and they were
> not misusing it: they name the **act**, which the glossary had no word for at all, while the
> rule was written to stop it being used for the **text**. A prohibition nobody can follow is a
> defect in the glossary, not in eight strings; the rule the product actually keeps is written
> above. `M27` asked which of the two was wrong; this is the answer, and the strings are left
> alone.

**Vault** <a id="vault"></a>:
The user-owned folder of Markdown files (plus audio attachments) that mirrors every Note; the
canonical, exportable copy. The Room database is an index over it, never the source of truth.
_Avoid_: database, backup

**Panel**:
The app's 2D window in the Horizon OS shell, living beside other panels such as Meta Virtual
Display screens.

**Space**:
The optional immersive mode (Spatial SDK) that hosts the same Panel in passthrough.

**Daily note**:
The Note automatically associated with a calendar day; quick captures land there by default.

**Tag**:
A label attached to a Note, written inline as `#tag` or added explicitly; the KB's first
organising axis (search and backlinks are the others).

**Assistant**:
The AI chat over the Notes (OpenRouter-backed in v1). Not yet an Agent: it reads and answers, it
does not act on a computer.

## Relationships

- A **Note** has zero or more **Tags** and at most one **Transcript**.
- Every **Note** has exactly one Markdown file in the **Vault**.
- The **Panel** and the **Space** show the same Notes; switching between them loses nothing.
- The **Assistant** reads Notes; it never edits them without the user confirming.

## Flagged ambiguities

- "Agent" was used for the v1 chat — resolved: v1 ships an **Assistant** (reads, answers);
  **Agent** is reserved for the later computer-controlling layer.
- "Desktop" — Fabric VR never renders one; the word refers to Meta Virtual Display or another
  streamer's panels.

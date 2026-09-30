<!-- Managed with super-ux (ux-contract v4). The WHY layer: update when the understanding of users changes. -->

# UX Foundation — Fabric VR

## Index

| ID | Name | Type | Status |
|----|------|------|--------|
| P-01 | The operator in the headset | persona | proposed |
| JTBD-01 | Capture a thought without leaving the headset | job | proposed |
| JTBD-02 | Find what I captured before | job | proposed |
| JTBD-03 | Think with an assistant that already knows my notes | job | retired |
| ST-001 | Write a text note | story | proposed |
| ST-002 | Speak a note and get it transcribed | story | proposed |
| ST-003 | Dictate in Russian or English without choosing | story | proposed |
| ST-004 | Find a note by words in it | story | proposed |
| ST-005 | Tag a note and browse by tag | story | proposed |
| ST-006 | Land in today's page | story | proposed |
| ST-007 | Ask the assistant about my notes | story | dropped |
| ST-008 | Keep my notes as files I own | story | proposed |
| ST-009 | Put the notes into the room | story | proposed |
| ST-010 | Understand what failed and what to do about it | story | proposed |

## Design tooling

- **Figma:** off for v1 — the visual layer is text-only, tokens in code
  (`core-common/src/main/kotlin/ai/passioncode/fabricvr/common/theme/`). Recorded in the
  stage-0 brief; revisit before any public build.
- **Style pack:** sheleg-design *workbench* tokens applied by hand (dark-first, calm motion).

## 1. Personas

### P-01: The operator in the headset

A solo knowledge worker who already wears a Quest 3 for hours beside a streamed Mac desktop. Fluent
in Russian and English, often mid-sentence in both. Comfortable with technical tools, impatient with
setup. Wants to capture a thought in the two seconds before it is gone, without taking the headset
off and without typing in the air.

## 2. Jobs to Be Done

### JTBD-01: Capture a thought without leaving the headset
- **Statement:** When a thought arrives while I am working in the headset, I want to record it in
  seconds by voice or a few typed words, so I can keep working without losing it.
- **Personas:** P-01
- **Type:** functional
- **Forces:** push: typing in the air is slow and tiring, and taking the headset off breaks the
  session; pull: a microphone is already on my head; anxiety: a transcript I cannot trust is worse
  than no note; habit: grabbing the phone.
- **Success metric:** the thought is in the knowledge base within ten seconds of arriving, and the
  person did not leave what they were doing.

### JTBD-02: Find what I captured before
- **Statement:** When I remember that I wrote something down, I want to find it by the words I
  remember, so I can act on it instead of rewriting it.
- **Personas:** P-01
- **Type:** functional
- **Forces:** push: notes scattered across apps and devices; pull: one searchable base; anxiety:
  a tool that owns my notes and can lose them; habit: searching in chat history.
- **Success metric:** the note is found on the first query in under five seconds.

### JTBD-03: Think with an assistant that already knows my notes

> **Out of v1 since 2026-09-20 (`DEC-0020`)**, with `ST-007`. `retired` is the job layer's only
> word for "not pursued now" — the linter's vocabulary is `proposed | confirmed | retired` — and it
> means *not in this build*, not *never*: the job is what the product is for, and it is kept here
> as written against its return.

- **Statement:** When I am stuck or need a summary, I want to ask an assistant that has read my own
  notes, so I get an answer about my work rather than the general internet.
- **Personas:** P-01
- **Type:** functional
- **Forces:** push: generic chatbots know nothing about my context; pull: the notes are right here;
  anxiety: my notes leaving the device without me deciding; habit: pasting notes into a chat by hand.
- **Success metric:** the person asks a question about their own material and acts on the answer
  without pasting anything by hand.
- **Status:** retired

## 3. Customer journeys

### JRN-01: P-01 — capture, find, ask (JTBD-01, JTBD-02, JTBD-03)

| # | Stage | User action | Touchpoint | Emotion (1-5) | Pain | Opportunity |
|---|-------|------------|------------|---------------|------|-------------|
| 1 | Install | side-loads the APK, opens the panel beside the desktop | Horizon OS shell | 3 | another app to set up | open straight into today's page, nothing to configure |
| 2 | First capture | presses *Record*, speaks a sentence, presses it again | panel, microphone | 4 | does the model even understand Russian? | show the live transcript and let it be edited |
| 3 | Model fetch | waits for the speech model on first use | download progress | 2 | a long silent wait | progress, size, and a working text path meanwhile |
| 4 | Daily use | types and speaks notes between tasks | panel | 4 | switching windows costs attention | stay a panel beside the desktop, never take over |
| 5 | Retrieval | searches for a phrase | search field | 4 | forgetting where it was filed | one field over everything, including transcripts |
| 6 | Asking | asks the assistant about a note | chat | 4 | key setup, cost anxiety | one key field, streaming answer, model shown |
| 7 | Trust | checks the files on disk | `adb pull` / vault | 5 | lock-in | Markdown mirror the person can take away |
| 8 | Spatial | puts the notes into the room | immersive Space | 3 | losing the panel's convenience | one button there and back, same notes |

## 4. User stories

### ST-001: Write a text note
- **Story:** As P-01, I want to type a note in the panel, so that a thought is saved without speaking.
- **Traces:** JTBD-01, JRN-01/#4
- **Acceptance criteria:**
  - Given the notes list is open, when I create a note, type a title and body and leave the editor,
    then the note appears in the list and survives an app restart.
- **Priority:** must
- **Status:** proposed

### ST-002: Speak a note and get it transcribed
- **Story:** As P-01, I want to press once, speak, and press again, so my words become a note without typing.
- **Traces:** JTBD-01, JRN-01/#2
- **Acceptance criteria:**
  - Given the microphone permission is granted and the speech model is present, when I press
    *Record*, speak and press it again, then the transcript is saved as a note with its audio
    attached, with no Save step (`DEC-0010`, `DEC-0011`; the hold gesture this story used to ask
    for was deleted, `B-170`).
- **Priority:** must
- **Status:** proposed

### ST-003: Dictate in Russian or English without choosing
- **Story:** As P-01, I want to speak either language, so that I never have to switch a setting mid-thought.
- **Traces:** JTBD-01, JRN-01/#2
- **Acceptance criteria:**
  - Given the language setting is `auto`, when I dictate in Russian and then in English, then each
    transcript is in the language spoken and the detected language is shown on the note.
- **Priority:** must
- **Status:** proposed

### ST-004: Find a note by words in it
- **Story:** As P-01, I want to search across titles, bodies and transcripts, so that I find a note by
  what I remember of it.
- **Traces:** JTBD-02, JRN-01/#5
- **Acceptance criteria:**
  - Given notes exist, when I type a word that appears in a note's transcript, then that note is in
    the results and the list updates as I type.
- **Priority:** must
- **Status:** proposed

### ST-005: Tag a note and browse by tag
- **Story:** As P-01, I want `#tags` in my text to become real tags, so that related notes group
  themselves without extra work.
- **Traces:** JTBD-02, JRN-01/#5
- **Acceptance criteria:**
  - Given a note body contains `#idea`, when I save it, then `idea` is listed as a tag on the note and
    selecting that tag filters the list to notes carrying it.
- **Priority:** should
- **Status:** proposed

### ST-006: Land in today's page
- **Story:** As P-01, I want the app to open on today's note, so that capture needs no navigation.
- **Traces:** JTBD-01, JRN-01/#1
- **Acceptance criteria:**
  - Given it is a new day, when I open the app, then today's daily note exists and is the first thing
    offered for writing.
- **Priority:** should
- **Status:** proposed

### ST-007: Ask the assistant about my notes

> **Deferred out of v1 on 2026-09-20 by `DEC-0020`.** Every precondition this story needs is
> missing at once — retrieval does not work for the language the notes are written in, the context
> builder posts up to 12 000 characters of raw dictations to a third party, and the conversation
> does not survive leaving the screen. The story is kept, not deleted: it is what the product is
> for, and `:feature-assistant` stays in the tree against its return. **Its status reads `dropped`**
> because that is the story layer's only word for "not pursued in this build" — the linter's
> vocabulary is `proposed | validated | delivered | dropped` — and `proposed` claimed it was still
> on v1's list.

- **Story:** As P-01, I want to ask a question and get an answer grounded in my notes, so that I do not
  paste my own material into a chat by hand.
- **Traces:** JTBD-03, JRN-01/#6
- **Acceptance criteria:**
  - Given an API key is saved, when I ask a question in the chat, then the answer streams in word by
    word and refers to my notes rather than to general knowledge.
- **Priority:** must
- **Status:** dropped

### ST-008: Keep my notes as files I own
- **Story:** As P-01, I want every note mirrored as a Markdown file, so that I can take my notes with me.
- **Traces:** JTBD-02, JRN-01/#7
- **Acceptance criteria:**
  - Given a note is saved, when I look in the vault folder, then there is a Markdown file with the
    note's text and front-matter, and its audio beside it.
- **Priority:** must
- **Status:** proposed

### ST-009: Put the notes into the room
- **Story:** As P-01, I want to move the same notes into an immersive space and back, so that I can
  work spatially without a second app.
- **Traces:** JTBD-01, JRN-01/#8
- **Acceptance criteria:**
  - Given the panel is open, when I press *Enter space*, then the same notes appear in an immersive
    view over passthrough, and pressing *Back to panel* returns me to the shell with nothing lost.
- **Priority:** should
- **Status:** proposed

### ST-010: Understand what failed and what to do about it
- **Story:** As P-01, I want every failure to say what happened and what to do, so that I am never
  left guessing whether my words were saved.
- **Traces:** JTBD-01, JTBD-03, JRN-01/#3
- **Acceptance criteria:**
  - Given the microphone permission is denied, the speech model is missing, the network is down or the
    API key is wrong, when the matching action fails, then the app shows a specific message naming the
    cause and an action that fixes it, and nothing is silently discarded.
- **Priority:** must
- **Status:** proposed

## Monetization

Out of scope for v1 — the build is side-loaded on the operator's own headset, not listed. Pricing
shapes are recorded in `docs/research/2026-09-19-fabric-vr-work-layer-report.md` §6 and revisited
before any Store listing.

<!-- Managed with super-ux (ux-contract v4). Update in the same change as any user-facing behavior change. -->

# UX Scenarios — Fabric VR v1

> **SCN-009 and SCN-010 were retired on 2026-09-20.** `DEC-0020` closed the assistant route: the
> chat screen and its view model are deleted, `:app` no longer depends on `:feature-assistant`, and
> nothing in the product can reach either, so the two scenarios describe a feature with no entry
> point. They are **retired, not deleted**: the linter is right that a removed id leaves a hole
> nobody can read, `ST-007` still traces to them, and `:feature-assistant` stays in the tree
> against their return. `T-042` owns this file; this much is done here because leaving a red
> linter behind would mean the task was not finished. Decided the same day: `DEC-0021` keeps the interface English;
> `DEC-0022` keeps the recorded audio, which adds the disclosure and deletion steps `T-024`
> specifies. `T-042` is the sweep that makes this file true again.
>
> Decisions that changed these scenarios on 2026-09-20: `DEC-0010` (the hold gesture and the
> capture sheet are deleted; recording is press-to-start, press-to-stop), `DEC-0011` (a finished
> transcript commits itself, with no Save step) and `DEC-0012` (the transcript is copied to the
> system clipboard on commit).

## What `Status` means here, since it used to mean nothing

`T-042`, `DEC-0051`. Eleven scenarios read `Status: validated` for behaviour **nobody had ever
walked**, while the two that read `draft` described behaviour that had shipped (`F-36`). A column
where every row says the same thing carries no information, and this one was worse than empty: it
said a person had confirmed something.

The contract already had the right word and the file was not using it:

| Value | Means | Who may write it |
|---|---|---|
| `draft` | written, not yet true of any build | anyone |
| `implemented` | the code exists and the automated checks cover it | anyone, with the commit |
| `validated` | **a person walked it, on a headset, and it did what this file says** | only a person |
| `retired` | the feature it describes is gone | the decision that removed it |

**`T-042`'s spec proposed inventing a fourth value, `built`, for the middle row.** It already
exists as `implemented`, in `docs/ux/lint.py`'s own `STATUS_ENUMS`, and inventing a synonym would
have put the file and its linter into disagreement for no gain. The spec's *intent* — that
`validated` stop being free — is what was implemented.

**Every scenario whose code exists therefore reads `implemented` today, and none reads
`validated`.** That is not a regression; it is the first time the column has been true.

**Three of them did not, for four commits, and the sentence above was false while it said so.**
`T-042` swept the eleven rows reading `validated` and did not revisit the three reading `draft` —
`SCN-014`, `SCN-015` and `SCN-016` — whose code ships, whose tests are green, and one of which
(`SCN-014`, choosing where speech is transcribed) is step 5 of the device walk `T-043` wrote two
commits later. A person would have been instructed to perform a scenario this file called *not
yet true of any build*. Found by the step-11 group verification, product tier; corrected here
with each row's evidence, and the rule that would have caught it is now `docs/ux/lint.py`'s:
**a scenario with a non-empty `Coverage:` may not read `draft`** (`DEC-0057`). The
separate `Product:` field carries whether shipping it changed anything for a person, and every one
of those still reads `unobserved` — which is also true, and is what the twenty-four-row device
queue in the plan is about.

## Index

| ID | Title | Feature | Persona | Traces | Status | Last audit |
|----|-------|---------|---------|--------|--------|------------|
| SCN-001 | Write and keep a text note | notes | P-01 | ST-001, FLW-01 | implemented | — |
| SCN-002 | Land in today's note | notes | P-01 | ST-006, FLW-01 | implemented | — |
| SCN-003 | Tag a note inline and filter by tag | notes | P-01 | ST-005, FLW-01 | implemented | — |
| SCN-004 | Speak a note and keep the transcript | voice | P-01 | ST-002, FLW-02 | implemented | — |
| SCN-005 | Dictate Russian, then English | voice | P-01 | ST-003, FLW-02 | implemented | — |
| SCN-006 | Get the speech model before the first dictation | voice | P-01 | ST-002, ST-010, FLW-02 | implemented | — |
| SCN-007 | Dictate through a whisper-server, fall back on failure | voice | P-01 | ST-003, ST-010, FLW-05 | implemented | — |
| SCN-008 | Find a note by a word in its transcript | search | P-01 | ST-004, FLW-03 | implemented | — |
| SCN-009 | Ask the assistant about my notes | assistant | P-01 | ST-007, FLW-04 | retired | — |
| SCN-010 | Set the API key and the model | assistant | P-01 | ST-007, ST-010, FLW-05 | retired | — |
| SCN-011 | Take the notes as files | vault | P-01 | ST-008, FLW-01 | implemented | — |
| SCN-012 | Move to the space and back | shell | P-01 | ST-009, FLW-06 | implemented | — |
| SCN-013 | Refuse the microphone, then change my mind | voice | P-01 | ST-010, FLW-02 | implemented | — |
| SCN-014 | Choose where speech is transcribed, and with which model | voice | P-01 | ST-003, ST-010, FLW-05 | implemented | — |
| SCN-015 | Delete a note by accident and put it back | notes | P-01 | ST-001, FLW-01 | implemented | — |
| SCN-016 | Reach a first dictation on a headset that has never run this app | voice | P-01 | ST-010, FLW-02 | implemented | — |
| SCN-017 | See that the list is a window, and show the rest | notes | P-01 | ST-004, FLW-01 | implemented | — |
| SCN-018 | Play a note's recording | voice | P-01 | ST-002, FLW-02 | implemented | — |
| SCN-019 | Wait while the speech engine gets ready | voice | P-01 | ST-010, FLW-02 | implemented | — |
| SCN-020 | See and reclaim what recordings and models occupy | vault | P-01 | ST-008, FLW-05 | implemented | — |
| SCN-021 | Hand back a report after the app closed unexpectedly | settings | P-01 | ST-010, FLW-05 | implemented | — |

## A destructive control names the SET, never a count

`DEC-0074`. A button that offers to remove *"3 recordings"* is read at the moment the number is
already stale, and a person deciding whether to press it cannot check it. Every destructive label
in this product therefore names **what** it will remove — *Delete recordings older than 30 days* —
so that the sentence is true before the press, during it and afterwards. Counts belong beside the
thing being counted, not on the control that destroys it.

## Personas

Speech runs on the device with a visible remote fallback (`DEC-0003`).

See [foundation.md](foundation.md) → *Personas*. One persona in v1: **P-01, the operator in the
headset**.

## notes

### SCN-001: Write and keep a text note
- **Persona:** P-01
- **Feature:** notes
- **Traces:** ST-001, FLW-01 (JTBD-01, JRN-01/#4)
- **Entry point:** SCR-01 Today → *New note*
- **Preconditions:** the app is open
- **Steps:**
  1. Tap *New note* -> the editor opens on a note that is already created. **The title field is
     not focused** (`DEC-0041`): in the Space a keyboard stops the panel receiving taps at all,
     including *Back* (`B-01`), so nothing opens a field on the person's behalf. They tap it when
     they want it.
  2. Type a title and a body -> an auto-save indicator shows "saved" within a second of the last keystroke.
  3. Leave the editor -> SCR-01 lists the note at the top with its title, first line and time.
  4. Force-stop the app and reopen it -> the note is still listed with the same text.
- **Expected result:** the note exists in the list after a restart, with the text as typed.
- **UI elements:** *New note* button, title field, body field, auto-save indicator, note list row, *Back*.
- **States covered:** loading, empty, success, error
- **Errors & recovery:** storage write fails -> an inline message names the failure and keeps the typed
  text on screen with *Retry*, which **writes the pending note again** rather than only clearing
  the words (`EditorViewModel.retry`, `DEC-0069`); nothing is discarded.
- **Status:** implemented
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/NoteEditorScreen.kt, core-notes/src/main/kotlin/ai/passioncode/fabricvr/notes/NotesRepository.kt
- **Product:** unobserved

### SCN-002: Land in today's note
- **Persona:** P-01
- **Feature:** notes
- **Traces:** ST-006, FLW-01 (JTBD-01, JRN-01/#1)
- **Entry point:** launching the app
- **Preconditions:** none
- **Steps:**
  1. Open the app on a new day -> SCR-01 shows today's date as a card, and today's daily note
     exists whether or not anything was written in it. **The card is the only place it is
     drawn** — it used to be a row in the list as well (`DEC-0046`). Under a tag filter the card
     is not shown and the note takes its place in the list if it matches, because the card shows
     today unfiltered.
  2. Tap the card -> the editor opens on today's note. **The body is not focused** (`DEC-0041`,
     `B-01`): in the Space a keyboard stops the panel receiving taps at all, so nothing opens a
     field on the person's behalf. They tap it when they want it.
- **Alt paths — the day turns over while the app is open** (`B-106`): the card and its label
  follow the new day without a relaunch. It is driven by a ticker rather than by a read at
  launch, because a headset is left running for hours and a person who dictates after midnight
  should not land in yesterday.
- **Expected result:** writing is one tap from launch, with no navigation and no naming decision.
- **UI elements:** today's card, note list.
- **States covered:** loading, empty, success
- **Errors & recovery:** the daily note cannot be created (storage) -> **the card is not drawn**,
  because there is no note for it to show, and the failure goes to the shared notice slot between
  the record button and the list (`NotesViewModel.loadDailyNote`, `REQ-060`); the rest of the list
  still loads.
- **Status:** implemented
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt, core-notes/src/main/kotlin/ai/passioncode/fabricvr/notes/NotesRepository.kt
- **Product:** unobserved

### SCN-003: Tag a note inline and filter by tag
- **Persona:** P-01
- **Feature:** notes
- **Traces:** ST-005, FLW-01 (JTBD-02, JRN-01/#5)
- **Entry point:** SCR-02 Note editor
- **Preconditions:** a note is open
- **Steps:**
  1. Type `#idea` inside the body -> on save, `idea` appears in the note's tag row.
  2. Return to SCR-01 -> a chip `#idea` is offered above the list (`DEC-0042`). The row is
     absent entirely until a first tag exists.
  3. Tap the chip -> the list narrows to notes carrying that tag, and the chip shows as selected.
  4. Tap the chip again, or tap *All* -> the full list returns. If the last note carrying the tag
     loses it while the filter is on, the chip **stays** and the list says *Nothing tagged #idea*
     — releasing silently would make a delete look like a filter that broke.
- **Expected result:** tags come from the text the person already wrote; no separate tagging step exists.
- **Alt paths:** several tags in one body all appear; a duplicate `#idea` is counted once. **Also true here and uncovered until `B-106`:** every row carries its own *Copy*, which puts
  that note's text on the clipboard without opening it — the fastest path from a note to a field
  in whatever the person was actually working in, which is the whole product position.
- **UI elements:** body field, tag row, tag chips, note list.
- **States covered:** success, empty
- **Errors & recovery:** a tag matching nothing after a deletion or an edit -> as in step 4, the
  selected chip **stays** and the list says *"Nothing tagged #idea."* (`today_no_tagged_notes`), so
  there is always a chip to release and *All* beside it; the filter is never dropped silently.
- **Status:** implemented
- **Coverage:** core-notes/src/main/kotlin/ai/passioncode/fabricvr/notes/TagParser.kt, app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt
- **Product:** unobserved

### SCN-017: See that the list is a window, and show the rest
- **Persona:** P-01
- **Feature:** notes
- **Traces:** ST-004, FLW-01 (JTBD-02, JRN-01/#5)
- **Entry point:** SCR-01, with more than 200 notes
- **Preconditions:** more notes exist than the list's window of 200 (`NotesRepository.DEFAULT_WINDOW`, `T-026`)
- **Steps:**
  1. Scroll to the foot of Today -> after the 200th row a line reads *"and 312 more"* — the count
     not drawn — with *Show all* beside it.
  2. Press *Show all* -> every note is listed; the row goes because nothing is left out.
- **Expected result:** a clipped list says it is clipped. Showing 200 of 512 with nothing on screen
  to say so would be the same failure as a search that finds fewer things than it counted.
- **Alt paths:** *Show all* lists everything rather than the next 200, deliberately — the person
  pressing it is looking for something. `OQ-0003` records the count at which paging replaces it.
- **UI elements:** the *and N more* line, *Show all*.
- **States covered:** success
- **Errors & recovery:** none of its own — the list's read failure is `SCN-002`'s notice.
- **Status:** implemented — `TodayScreen` draws the row whenever `total` exceeds the rows drawn; **not walked on a headset**
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt:829-848, app/src/main/kotlin/ai/passioncode/fabricvr/ui/NotesViewModel.kt:756-768
- **Product:** unobserved

## voice

### SCN-004: Speak a note and keep the transcript
- **Persona:** P-01
- **Feature:** voice
- **Traces:** ST-002, FLW-02 (JTBD-01, JRN-01/#2)
- **Entry point:** SCR-01 *Record*
- **Preconditions:** microphone permission granted; the speech model is present
- **Steps:**
  1. Press *Record* once -> the button turns red, reads *Stop recording*, and a level meter and
     elapsed time appear above it. Nothing opens on top of the screen.
  2. Speak -> the meter moves with the voice. No finger is holding anything. The thought can be as
     long as it needs to be **up to ten minutes**: in the last minute the elapsed time becomes a
     countdown, and at ten the recording stops and transcribes itself exactly as if *Stop* had
     been pressed. Nothing is discarded at the limit. `DEC-0032` sets the number, and its reason
     is the transcription wait — about thirteen minutes for ten minutes of audio on this
     headset — not memory.
  3. Press *Stop recording* -> the button reads "Transcribing…" and is not pressable until it is
     done. **This line is why `B-147` was reopened** (`DEC-0062`): the row had been closed whole on
     *"Transcribing… is no longer a state a person can only wait out"*, which is true of the engine
     — cancellation is deliverable — and was false of the product, because nothing offered a stop.
     No `VoiceState` member, no string, no surface, and this scenario said so correctly the whole
     time. A claim about the engine is not a claim about the product. **The wait has a shape and an exit** (`B-178`, `B-179`): once the on-device engine has
     reported anything, a percentage and a bar replace the bare seconds counter, and a *Stop
     transcribing* control stands under it on both Today and the editor. The bar is drawn only
     for a local decode — a cloud service and a whisper server return one answer at the end and
     report nothing on the way, so those keep the seconds counter rather than gaining a bar
     pinned at 0 % for the whole wait. The control is separate from the record button precisely
     because that button is disabled here: a person cannot be asked to press a dead target to
     escape a wait that reaches thirteen minutes on a ten-minute dictation.
  4. The note writes itself: it appears at the top of the list, and its text is **already on the
     clipboard** — the screen says "Saved, and copied. Paste it anywhere." for a few seconds.
     **The copy happens when the row reaches the database, not when a screen happens to be
     watching** (`B-212`): it lived in a Compose effect that only ran if a recomposition frame
     observed the finished dictation, and since `DEC-0068` a dictation is committed by whichever
     surface is alive — which, after the headset came off mid-dictation, can be a process that has
     just started with nobody looking at it. Two consequences worth stating: a dictation recovered
     on the next launch lands on the clipboard too — unless its note had already been written before
     the process died, in which case it is simply in the list (`DEC-0088`) — and the confirmation now reports **what
     happened** rather than what the switch says, so it cannot claim a copy that did not occur.
  5. **Each of those four moments also makes a short sound**, and in the Space a pulse in both
     controllers (`REQ-062`): the microphone opening, the stop, the ten-minute cap, and the
     saved note. They are switchable in Settings and on by default.
- **Expected result:** spoken words become a saved note with its audio, without typing and **without
  a Save step**. Dictating in a headset is usually done to paste the words somewhere else, so the
  clipboard is part of the result rather than a second action.
- **What happens to the recording afterwards** (`DEC-0038`, `DEC-0039`): it is kept, so the note
  can be transcribed again, and the row can now **play** it. It can be deleted on its own without
  losing the note, and Settings says how many recordings there are and what they occupy — about
  1 MB per thirty seconds, which nothing in the product disclosed before.
- **Alt paths:** **There is no *Cancel* during recording, and that is the design** (`DEC-0010`,
  `B-105`): the button is press-to-start, press-to-stop, and every way out of the screen
  *transcribes* rather than discards — a recording a person walked away from is words they said,
  not rubbish. What they can discard is a **finished** one they do not want: *Discard the
  recording* stands beside *Keep the recording as a note* after a failed decode. This clause
  promised a control for eight days and no task was ever going to build it. **Leaving
  the screen while recording, or taking the headset off, stops the recording and transcribes it**
  — the note is waiting on return rather than lost; and while a recording is running the header's
  *Search*, *Space* and *Settings* are disabled, so the only way to leave mid-dictation is
  deliberate. **A dictation can also start from inside an open note** (`B-106`): the editor's
  own *Record* button (`NoteEditorScreen.kt`, `action_record`) appends the transcript to the body being edited rather than
  creating a second note, which is the difference between correcting a thought and starting one.
  If the headset loses power in the instant after the append is saved, the words can come back on
  the next launch as a separate note too — a duplicate rather than a loss (`DEC-0088`) — without its
  recording, which stays with the note it was dictated into (`B-256`).
  A second dictation into a note that already has a recording **keeps the first** (`DEC-0090`):
  the note plays the newest, and the earlier one stays in the note's folder, counted under
  *Recordings* and carried by every export — and the editor lists them, current first, each with a time and *Play*, once a note keeps more than one (`B-254`). **System Back does nothing while the microphone is open** (`REQ-046`): it is the
  one exit the screen's own controls do not cover, so it is consumed rather than allowed to pop
  a recording screen. It does not stop the recording either — the person presses *Stop
  recording*, which is the control they are already looking at. ***Leave the Space* stays live**
  (`DEC-0034`) — it is the only way out of an immersive surface, and greying it out would trap a
  person in the room until they stopped talking; leaving is safe because it transcribes rather
  than discards. A wrong word is corrected afterwards: the note is in the list with *Transcribe
  again* and *Copy* beside it, and tapping it opens the editor. In the editor the note carries a
  line saying it was dictated, in which language, and **where it was transcribed** — *"Dictated ·
  ru · transcribed on this headset"*. It used to render the enum member and the engine
  identifier, so the person read `local_fallback` and `cloud:whisper-large-v3` (`REQ-061`,
  `B-151`). **And when it says "instead", it now says why** — *"Why: The speech server answered
  503."* under the badge. The fallback line named the fact and withheld the only part the person
  can act on: they configured a service, it did not answer, and the address and the key are two
  fields in Settings. A dictation that crossed a process boundary arrives without the reason —
  the outbox cannot serialise one (`DEC-0068`) — and then says nothing rather than printing a
  sentence with a hole in it.
- **A dictated row is marked as one in the list** (`B-153`, closing `B-02`'s sixth element). It
  carries a microphone glyph beside the engine and language. The text alone is strictly more
  informative and it is not scannable: somebody looking down a list for "the one I spoke" was
  reading twelve lines of `whisper-small-q5_1 · ru` instead of seeing two microphones.
- **Leaving during *Transcribing…* keeps the words too, and until `REQ-046` it did not.** The
  decode ran on the screen's own scope and the recording was deleted on the way out, so leaving
  the Space between *Stop* and the note appearing lost both. The transcript is now handed to a
  process-wide outbox the moment it exists (`DEC-0068`), written to disk before it reaches the screen, and
  the note is written by whichever surface is alive next — the panel on return, the Space on the
  way in, or the **next launch** if the process died. The recording is not swept while a
  dictation still owes a note — every waiting dictation's, not only the first's (`DEC-0096`). If a
  note cannot be written the banner offers *Retry*; should a second dictation also fail before
  that, the newer one's *Retry* replaces it, and the earlier one stays on the headset and is written
  on the next launch (`DEC-0095`). When several dictations are recovered at one launch, each
  becomes its own note and the clipboard ends up holding the last of them.
- **Every one of those moments used to be visual only** (`REQ-062`, audit `M22`), and a person
  dictating in this product is usually looking at a streamed desktop rather than at this panel —
  so the recording started, the cap fired and the note saved with no evidence any of it had
  happened (`DEC-0072`). The audio cue exists on both surfaces; the **haptic exists only in the Space**,
  because `spatial.applyHapticFeedback` is the only haptics API Meta documents and it belongs to
  an immersive activity — whether `android.os.Vibrator` reaches a Touch controller from a 2D
  panel is unverified and is not guessed at. The cap is deliberately the one cue that does not
  sound like an acknowledgement: it is the app interrupting somebody who is still speaking.
  Meta's *Haptics: Best practices* is why every cue accompanies a line the screen already draws,
  and why the switch exists.
- **The headset can mute the microphone mid-recording** (`REQ-052`). The recording keeps running
  and the stream goes empty, so the screen says *the headset has muted the microphone* — **not**
  *Nothing was heard*, which is what a stop after real silence says and would blame the person
  for the system's decision. The meter and the clock stay; unmuting returns to the ordinary
  recording line.
- **UI elements:** one button across the full width of the panel, 128dp tall, that reads *Record*,
  then *Stop recording*, then "Transcribing…". Whatever has to be said — the microphone is not
  granted, the model is missing, the last attempt failed — is drawn immediately above it, so the
  button never moves and there is never a second window to dismiss. Then the "Saved, and copied"
  line, and per note *Transcribe again*, *Delete*, and *Copy* standing alone on the right.
- **Why no hold:** a hold asks a person to keep a controller ray steady on a target for the length
  of a thought, and a released ray ends the recording mid-sentence. Press-to-start and
  press-to-stop costs one more press and nothing else.
- **States covered:** loading, success, empty, error
- **Errors & recovery:** the speech model is changed in Settings while this dictation is being
  transcribed -> the transcription stops and says so, *"The speech model changed, so this
  transcription stopped. Your recording is kept — try again, or discard it."*, with the same two
  exits a deliberate stop has (`DEC-0092`); it used to leave *Transcribing…* on screen for ever.
  Silence -> the status area says *"Nothing was heard."* as a line of text
  with no control of its own; there is no sheet (`DEC-0010`). On Today, **when a recording
  exists**, *Keep the recording as a note* and *Discard the recording* stand under it — the same
  pair a failed decode gets — and when none exists the line is all there is; the editor draws the
  line alone (`TodayScreen.kt`, `NoteEditorScreen.kt`). **There is no retry control yet**: `B-150`
  was closed by `REQ-061` deleting the *Try again* string nothing rendered, and the surface half —
  that `VoiceState.NothingHeard` offers no action — stays open as `B-33`. The next press of
  *Record* is the way on. The engine
  fails -> the message names the failure, the audio is kept and offered as a note without a transcript.
  **That second clause is built now** (`B-091`): *Keep the recording as a note* stands beside
  *Discard the recording* under the failure, and it writes a row whose only content is the recording — untitled,
  with *Transcribe again* on it, which is the control that fixes it. Until this the promise held
  only while the person kept looking: `T-005` stopped the file being deleted at the moment of
  failure, nothing then owned it, and the screen's own clean-up deleted it on the way out. *Keep*
  stands before *Discard* because a note can be deleted afterwards and a discarded recording
  cannot be recovered — there is no confirmation step (`DEC-0011`).
- **Stopping a transcription keeps the recording, and says so** (`B-179`). The decision is *told*
  rather than *silent*: the person pressed something, so the screen answers "Stopped. Your
  recording is kept — try again, or discard it", with those two controls under it. Silence after
  a deliberate act reads as a crash, and what they need to know is whether ten minutes of speech
  has just gone. `DEC-0060` chose `CancellationException` deliberately so a **background** model
  switch could abandon a run with no new error shape; that is right for a run nobody asked about
  and is not enough for one somebody ended.
- **A recording that could not be written is said out loud, once** (`B-092`). A full disk, or an
  audio directory that is not one, used to produce a note with no audio and no message at all, and
  the person discovered months later that one row would not play or re-transcribe. The dictation
  still completes — the words are the part that cannot be re-made — and a line above the
  confirmation says the recording was not kept and that this note cannot be transcribed again.
- **Status:** implemented
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt, app/src/main/kotlin/ai/passioncode/fabricvr/ui/VoiceViewModel.kt, app/src/main/kotlin/ai/passioncode/fabricvr/FeedbackCues.kt
- **Product:** unobserved — and the cues are the sharpest case of it: `FeedbackCuesTest` and
  `DictationCuesTest` prove which cue fires and in what order, and **no headset has heard or
  felt one** (`B-194`).

### SCN-005: Dictate Russian, then English
- **Persona:** P-01
- **Feature:** voice
- **Traces:** ST-003, FLW-02 (JTBD-01, JRN-01/#2)
- **Entry point:** SCR-01 *Record*
- **Preconditions:** the speech language setting is *Detect automatically*. Settings also offers a
  pinned language (`ru`, `en`, `uk`, `de`, `fr`, `es`, `it`, `pl`, `tr`), which is passed to the
  engine and makes recognition easier for it.
- **Steps:**
  1. Dictate a sentence in Russian -> the transcript is in Russian and the badge reads `ru`.
  2. Dictate a sentence in English -> the transcript is in English and the badge reads `en`.
- **Expected result:** neither dictation required changing a setting, and each note records which
  language was detected.
- **Alt paths — two of them were behaviours with no scenario until `B-106`.** A run that fell
  back says so **in the confirmation itself**, as a suffix naming why, rather than in a separate
  banner the person has to connect to what they just did; and a server address entered under one
  provider is **kept** when the provider changes and offered back on return, because retyping an
  address on a headset keyboard is the cost this avoids. Beyond those: the language setting is pinned to `ru` or `en` -> the engine is told that language and
  the badge shows it as pinned rather than detected.
- **UI elements:** *Record*, transcript preview, language badge, the language setting on SCR-06.
- **States covered:** success, error
- **Errors & recovery:** the detected language is wrong, or the model heard the wrong words ->
  *Transcribe again* on the note offers every on-device model by name and size, plus the cloud
  service and the whisper server. The recording is kept in the vault, so re-running costs no
  re-speaking. **The body is replaced only if it is still exactly what the last transcription
  produced**; once edited, the new text lands in the transcript and the edit is left alone.
- **Status:** implemented
- **Coverage:** feature-stt/src/main/cpp/fabricvr_whisper.cpp, feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/WhisperEngine.kt
- **Product:** unobserved

### SCN-006: Get the speech model before the first dictation
- **Persona:** P-01
- **Feature:** voice
- **Traces:** ST-002, ST-010, FLW-02 (JTBD-01, JRN-01/#3)
- **Entry point:** the first press of *Record*, or SCR-06 → *Download speech model (190 MB)* — the
  number is the chosen model's
- **Preconditions:** no speech model on the device
- **Steps:**
  1. Press *Record* for the first time -> instead of recording, the status area above the button
     carries the model banner — *"Speech needs a model — Small, 190 MB. It downloads once and then
     everything happens on this headset."* (`error_model_missing_size`) — with *Download* and
     *Write a note instead*. There is no separate model screen and no hold (`DEC-0010`,
     `DEC-0043`).
  2. Press *Download* -> a progress bar with the megabytes, an estimate line saying text notes work
     meanwhile, and *Cancel*.
  3. Wait for completion -> the file's SHA-256 is checked and *"The speech model is ready. Press
     Record."* appears where *Saved, and copied* appears (`state_model_ready`). The recording is not
     started for the person: the next press of *Record* starts it.
- **Expected result:** the model arrives once, verified, and the wait is visible rather than silent.
- **Alt paths:** the model is side-loaded with `adb push` into the models folder -> the app finds it on
  the next launch and never offers the download.
- **UI elements:** the model banner (name and size), progress bar, estimate line, *Download*,
  *Write a note instead*, *Cancel*, *Resume* / *Download again*.
- **States covered:** loading, success, error
- **Errors & recovery:** **there is not room** -> the download is refused *before the first byte
  is requested*, with both numbers: "There isn't room: the speech model needs 209 MB and this
  headset has 12 MB free." Five models total 1.395 GB and every dictation keeps its WAV, so this
  is the expected end state rather than a corner case (`DEC-0033`). The network fails -> the
  message says so, and **the button names what the next press will cost**: *Resume* when usable
  bytes survived on disk, *Download again* when they did not (`REQ-047`). One word for both
  would be a promise of 574 MB dressed as a promise of thirty. The checksum does not match ->
  the file is removed and the digest is not relaxed (`DEC-0016`), so that failure is never
  resumable.
- **What is true of a download that this scenario used to get wrong:** it belongs to the process,
  not to the screen. Pressing *Download* on Today and again in Settings joins one transfer rather
  than starting two into the same file — which is what used to make the model never finish
  (`I-05`). *Cancel* stops it **for every screen**, not just the one pressed. Leaving the screen
  does not stop it, which is what finally makes resuming reachable. And Settings refuses to
  delete a model while it is being written.
- **Status:** implemented
- **Coverage:** feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/ModelDownloader.kt
- **Product:** unobserved

### SCN-007: Dictate through a whisper-server, fall back on failure
- **Persona:** P-01
- **Feature:** voice
- **Traces:** ST-003, ST-010, FLW-05 (JTBD-01, JRN-01/#3)
- **Entry point:** SCR-06 → whisper-server URL
- **Preconditions:** a URL is saved; the on-device model is present
- **Steps:**
  1. Save a whisper-server URL -> the setting shows the server as the preferred engine.
  2. Dictate -> the audio goes to the server and the editor's line reads *"Dictated · ru ·
     transcribed by a speech service"* — one phrase for a cloud endpoint and the whisper server
     alike, because `SttSource.REMOTE` covers both (`stt_source_remote`, `SpeechChoice.kt`
     `transcriptSourceRes`).
  3. Stop the server and dictate again -> the transcript is produced on the device and the line
     reads *"transcribed on this headset instead"*, with *"Why: …"* under it naming what the
     server answered (`stt_source_local_fallback`, `editor_transcript_fallback`, `B-151`). Today's
     confirmation carries the same phrase as a suffix.
- **Expected result:** a better engine is used when it is reachable, and its absence degrades to the
  device instead of failing.
- **UI elements:** whisper-server address field, the editor's *Dictated* line, the *Why:* line.
- **States covered:** success, error
- **Errors & recovery:** the server answers with an error or times out -> the fallback runs and the
  *Why:* line names the status — or, for an answer that is not a transcript, says exactly that. A server that answers with something that is not a transcript — a
  hotel Wi-Fi sign-in page, a proxy's error page — is a failure too, never the note's text
  (`DEC-0091`); a long dictation is given a deadline that grows with it rather than a fixed minute. **If the fallback fails too — most often because the device model
  is missing — only the server's reason is shown**, with its *Settings* action (*"The speech
  service refused the recording (401). Check its address and key in Settings."*); the device's own
  failure is logged (`stt.fallback.failed`), not shown, and no *Download* is offered (`SttRouter`,
  audit `M13`). The server is what the person chose, so its answer is the one they can act on, and
  a *Download* would invite 190–574 MB that fixes nothing when the key has expired. With no device
  engine configured at all, the server's reason is returned the same way (`REQ-058`).
- **Status:** implemented
- **Coverage:** feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/SttRouter.kt, feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/RemoteWhisperClient.kt
- **Product:** unobserved

### SCN-013: Refuse the microphone, then change my mind
- **Persona:** P-01
- **Feature:** voice
- **Traces:** ST-010, FLW-02 (JTBD-01, JRN-01/#3)
- **Entry point:** SCR-01 *Record*
- **Preconditions:** microphone permission not granted
- **Steps:**
  1. Press *Record* -> **the system permission prompt appears on this press** (`DEC-0044`). The
     button does not relabel itself and nothing is explained first: the reason is obvious from the
     control just pressed, and in a headset a rationale screen is one more thing to aim at.
  2. Deny it -> the status area above the button explains that the microphone is needed and offers
     *Ask again* and *Write a note instead*, and no recording is attempted.
  3. Press *Ask again* and allow -> **the recording starts immediately, without re-pressing
     *Record***. Granting calls `start()`; the state that used to swallow this
     (`VoiceState.Allowed`) is deleted.
- **Expected result:** a refusal is a state with a way forward, not a dead button — and granting is
  not a state the person has to decode.
- **Alt paths:** the permission is denied permanently -> the banner offers *App settings*
  instead of *Ask again*; a headset with no such page says so (`D-02`).
- **Pressing *Record* twice before the prompt is answered asks once** (`REQ-048`). A second
  request while one dialog is showing is answered by Android **immediately and empty**, and an
  empty answer reads as *permanently denied* while also throwing away the person's real reply —
  so two taps on the product's one control used to cost voice notes for the rest of the screen's
  life, with nobody having refused anything. The second press is ignored until the system has
  answered; a prompt dismissed without an answer only lets the next press ask again, and is
  never read as a refusal.
- **UI elements:** *Record*, system prompt, the status area, *Ask again*, *Write a note instead*,
  *App settings*.
- **States covered:** error, success
- **Errors & recovery:** covered above — the refusal is the error state and both exits are offered.
- **Status:** implemented
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt, app/src/main/kotlin/ai/passioncode/fabricvr/ui/VoiceViewModel.kt
- **Product:** unobserved

### SCN-016: Reach a first dictation on a headset that has never run this app
- **Persona:** P-01
- **Feature:** voice
- **Traces:** ST-010, FLW-02 (JTBD-01, JRN-01/#1)
- **Entry point:** SCR-01, first launch
- **Preconditions:** no permission, no speech model, no key, `SttProvider.LOCAL`
- **Steps:**
  1. Launch -> the empty state says what to press, what the first dictation will download —
     **the chosen model's own size**, not a constant — and that a note can be written without it.
     It is the **long** variant, chosen by the model being absent rather than by a first-run flag
     (`DEC-0044`), **and only when speech is local**: under *Cloud service* or *Whisper server*
     both of its clauses are false — the first dictation uploads a recording rather than
     downloading a model, and nothing "happens on this headset" — so the provider chooses the
     sentence, and the shorter one says nothing about the destination because the line a few
     rows up already names it (`REQ-061`, audit `M27`). It sits above the day card, and today's note is drawn **only** as that card:
     it used to be a row as well, which is also why this paragraph could not be reached at all
     (`DEC-0046`).
  2. Press *Record* -> the system prompt appears; allow -> `start()` runs without a second press.
     The model is missing, so it answers with the model banner rather than a recording:
     **it names the model and its size** and offers *Download* and *Write a note instead*.
  3. Press *Download* -> a bar, the megabytes, an estimate and *Cancel*. When it finishes,
     *"The speech model is ready. Press Record."* appears where *Saved, and copied* appears.
  4. Press *Record*, speak, press it again -> *Transcribing…* with the seconds counting, then the
     note is written and its text is on the clipboard — **unless the switch in Settings says no**,
     and then the confirmation says *Saved.* rather than claiming a copy (`DEC-0047`). If speech
     is not local, a line under the record control has been naming the destination all along.
- **Expected result:** **four presses to a saved note** — *Record*, *Download*, *Record*, *Stop*
  — plus the OS dialog's own button, and every screen states its own next step. It was **six**
  (`D.md`, *First run on a fresh headset*, which counted the same way). Three is the count to the
  start of the first dictation and was written here by mistake; `DEC-0046` corrects it, and
  `B-148`'s device walk counts *to a saved note* so that it confirms a number somebody defined.
- **Alt paths:** the microphone is refused -> SCN-013. There is no room for the model -> the
  refusal names both numbers before the first byte (`G-07`).
- **UI elements:** the empty state, *Record*, the system prompt, the model banner, *Download*,
  *Write a note instead*, the progress bar, *Cancel*, the ready confirmation, the seconds counter.
- **States covered:** empty, loading, error, success
- **Errors & recovery:** the microphone refusal and the missing model are both states with two
  exits each; neither is a dead end.
- **Status:** implemented — four presses to a saved note, counted structurally in `TodayFrameTest`. **The count has never been counted on a device** (`B-148`), which is what `Product: unobserved` says
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt, app/src/main/kotlin/ai/passioncode/fabricvr/ui/VoiceViewModel.kt
- **Product:** unobserved — **the tap count has never been counted on a device** (`B-148`), and
  the second half of the check, a person who has not read the spec walking the same route, has no
  substitute at all.

### SCN-018: Play a note's recording
- **Persona:** P-01
- **Feature:** voice
- **Traces:** ST-002, FLW-02 (JTBD-02, JRN-01/#7)
- **Entry point:** SCR-01, *Play* on a dictated note's row
- **Preconditions:** a note carries a recording (`DEC-0022` keeps it)
- **Steps:**
  1. Press *Play* -> the recording plays and the control reads *Stop*.
  2. Press *Stop*, or let it finish -> playback ends and the control reads *Play* again.
  3. Press *Play* on another row while one is playing -> the first stops and the second starts.
- **Expected result:** the person can hear the thing the app is keeping on their behalf — one player
  at a time, one control with two intentions, like the record button (`DEC-0039`, `T-050`).
- **Alt paths:** leaving the screen releases its player. The editor has the same control on each
  row of *Recordings (N)* when a note keeps more than one recording (`B-254`), and it stops playing
  before starting dictation; *Play* stays disabled while the microphone is open (`DEC-0098`).
- **UI elements:** *Play* / *Stop* on the row.
- **States covered:** success, error
- **Errors & recovery:** the file is gone (swept or deleted elsewhere) or will not decode -> the
  press does nothing visible and the failure is logged (`audio.play.missing`, `audio.play.failed`);
  there is no banner, which is a deliberate choice in `AudioPlayback` and not yet a walked one.
- **Status:** implemented — **not walked on a headset**; whether the audio is audible beside a streamed desktop is unmeasured
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt:1617-1623, app/src/main/kotlin/ai/passioncode/fabricvr/ui/AudioPlayback.kt:54-90
- **Product:** unobserved

### SCN-019: Wait while the speech engine gets ready
- **Persona:** P-01
- **Feature:** voice
- **Traces:** ST-010, FLW-02 (JTBD-01, JRN-01/#2)
- **Entry point:** SCR-01, *Stop recording* on a dictation whose model is not yet loaded
- **Preconditions:** speech is local, and the engine has to load or swap its model for this call
- **Steps:**
  1. Press *Stop recording* -> the status area reads *"Getting the Small model ready…"* — the
     chosen model's short name — and the button reads *Transcribing…* and is not pressable.
  2. The engine is ready -> the ordinary transcribing state follows (`SCN-004`, step 3).
- **Expected result:** a wait for a model to load, which can be half a minute, is named as that wait
  rather than looking like a frozen transcription (`DEC-0030`, `T-018`).
- **Alt paths:** the state is raised only when the call actually has to wait; a warm engine goes
  straight to *Transcribing…*.
- **UI elements:** the status line, the disabled record button.
- **States covered:** loading
- **Errors & recovery:** a load that fails ends in `SCN-004`'s failure state, with the recording kept.
- **Status:** implemented — **not walked on a headset**
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt:1328-1335, app/src/main/kotlin/ai/passioncode/fabricvr/ui/VoiceViewModel.kt:854-859
- **Product:** unobserved

## search

### SCN-008: Find a note by a word in its transcript
- **Persona:** P-01
- **Feature:** search
- **Traces:** ST-004, FLW-03 (JTBD-02, JRN-01/#5)
- **Entry point:** SCR-01 → *Search*
- **Preconditions:** several notes exist, at least one of them a voice note
- **Steps:**
  1. Tap *Search* -> **the field is focused on entry** (`SearchScreen.kt`, a `FocusRequester` in a
     `LaunchedEffect`) and recent notes are listed. That is a **decided exception to `DEC-0041`**:
     `DEC-0087` keeps the auto-focus because a person who navigated to Search came to type, and
     Meta's objection is to a keyboard appearing unbidden. Whether the Space's keyboard then
     covers Back is `B-050`'s device question.
  2. Type a word that appears only inside a voice note's transcript -> the list narrows to that note and
     shows the matching line.
  3. Tap the result -> the note opens in the editor.
- **Expected result:** transcripts are searched like any other text, and the result shows why it matched.
- **Alt paths:** typing a tag name also matches notes carrying it.
- **UI elements:** *Search*, query field, result rows with the matching line, *Clear*.
- **States covered:** empty, loading, success, error
- **Errors & recovery:** no match -> "Nothing matches «query»" with the query kept so it can be edited;
  the index is unavailable -> the message says so and offers *Retry* rather than an empty list.
- **Status:** implemented
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/SearchScreen.kt, core-notes/src/main/kotlin/ai/passioncode/fabricvr/notes/NotesRepository.kt
- **Product:** unobserved

## assistant

### SCN-011: Take the notes as files
- **Persona:** P-01
- **Feature:** vault
- **Traces:** ST-008, FLW-01 (JTBD-02, JRN-01/#7)
- **Entry point:** SCR-06 Settings → *Export the vault*
- **Preconditions:** at least one note exists, one of them with audio
- **Steps:**
  1. Save a note -> a Markdown file appears in the vault under `notes/YYYY/MM/`, carrying front-matter
     with the id, tags and timestamps.
  2. Save a voice note -> its audio file sits beside the Markdown file and is named from the same id.
  3. Choose *Notes only* or *Notes and recordings*, then *Export the vault* -> an archive is written
     where the headset's own file manager and every sharing app can reach it, and the screen names
     the path it went to.
  4. *Send it somewhere* -> the system share sheet, so the archive leaves the headset without a
     cable; or take it off with `adb pull`.
  5. **After a reinstall, *Restore from an archive*** -> the system file picker opens, the
     chosen zip is unpacked back into the vault, and the row says how many notes were restored
     and how many were already on this headset (`REQ-054`).
- **Expected result:** every note exists as a file the person can take away, **and there is a way to
  take it, and a way to put it back** — the database is an index over those files, not the only
  copy.
- **The restore is what made the export a backup** (`REQ-054`, audit `H4`). `VaultImporter`'s own
  comment called the zip "a genuine restore path: unzip it back into `filesDir/vault`" — and
  `filesDir` is app-private with `run-as` unavailable against a release build, so the sentence
  described an operation **nobody could perform**. Until this step the archive was a copy of the
  person's notes that they could not use, and the uninstall a release signature change forces is
  exactly when they would need it.
- **Corrected here, and it is the reason this scenario needed rewriting** (`T-042`): the axis-D walk
  graded this *holds* on the strength of step 1 and 2, which were true. Step 3 was not. The vault
  is app-private internal storage with `android:allowBackup="false"` and there was no picker, no
  `FileProvider` and no share intent anywhere in the tree — so the scenario's own title named the
  one thing the build could not do. `T-023` (`DEC-0026`) is what made it true, and `REQ-013`
  records the archive being walked off a Quest 3 and opened on a Mac.
- **Alt paths — the trash has a lifetime, which no scenario said until `B-106`:** a deleted
  note's files are moved into `vault/.trash/<id>/` and purged after **seven days**, on the next
  launch after that. Undo restores from there, so the window an accident can be repaired in is
  the same seven days — and after them the file is gone from the vault the person owns, which is
  the point of saying so here rather than only in a module document. Beyond that: deleting a note removes its Markdown file and audio in the same action — into
  `vault/.trash/` for seven days, so *Undo* restores both (`DEC-0027`). The export walks `notes/`
  only, so a deleted note is not in the archive.
- **UI elements:** vault path row (read-only, copyable), note editor, *Restore from an archive*.
- **States covered:** success, error
- **Errors & recovery:** the mirror write fails -> the note is still saved in the index, the failure is
  shown once with *Retry* **on Today, where the note was made** (`DEC-0045`), and the settings
  screen marks the vault as out of sync until it succeeds. *Retry* re-writes every note the
  mirror still owes — `VaultMirror.retryFailed()` — rather than re-reading the list, which is a
  control that would look like it worked (`DEC-0046`). One banner per episode: it returns only
  after the mirror has recovered and failed again. **A restore refuses entries rather than the
  whole archive**: a person recovering from a disaster is served worse by all-or-nothing than by
  a count they can read, so entries outside `notes/`, entries carrying `..`, and filenames that
  are not named from a note's UUID are refused one by one and **counted in a sentence of their own** — a
  well-formed export of this app produces none, so any number above zero means the archive came
  from somewhere else. A picker that hands back something unreadable says so instead of
  appearing to do nothing, and a second press while one is unpacking is ignored. **An export that
  meets a file it cannot read stops and identifies its kind** (`DEC-0093`, `DEC-0097`,
  `DEC-0098`): nothing is exported and nothing is removed. For a recording, the message offers
  *Export notes only*; for a note, it offers no partial backup. Internal file names never appear
  in the message. Failure to write the destination is a storage failure, not an accusation that
  the source note or recording is damaged.
- **Status:** implemented
- **Coverage:** feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/Vault.kt, feature-vault/src/main/kotlin/ai/passioncode/fabricvr/vault/MarkdownSerializer.kt, app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsViewModel.kt
- **Product:** unobserved

## shell

### SCN-012: Move to the space and back
- **Persona:** P-01
- **Feature:** shell
- **Traces:** ST-009, FLW-06 (JTBD-01, JRN-01/#8)
- **Entry point:** SCR-01 → *Space*
- **Preconditions:** the app is running as a 2D panel
- **Steps:**
  1. Tap *Space* -> an immersive view starts and shows the same notes on a panel over passthrough.
  2. Open a note there -> it behaves as it does in the panel.
  3. Tap *Leave the Space*, or press Back -> the shell reopens the 2D panel **showing whatever
     the panel was showing when you left it** — not what you were looking at in the Space.
     `B-105` carried this clause as "the same note still open", which was never true and no task
     was going to make it so: the two hosts run one Compose tree but **two `NavController`s**,
     and the Space always opens on Today. The panel keeps its own place because it was never
     destroyed. Carrying a position across the seam is `B-207`, and it is a feature rather than
     a repair. The tap works **even while a dictation is running** — it is the one header
     action `DEC-0034` keeps live, and leaving transcribes rather than discards. Both reach the same place: Back is delivered by `ImmersiveActivity`'s own
     `dispatchKeyEvent` override, because the SDK's activity discards key events before anything
     below the window sees them (`DEC-0029`). Whether a controller's B button *is* that key is
     unmeasured — the on-screen control is the one this scenario is validated against.
  4. Back on the panel -> **"Starting the space…" is gone.** It was raised on the tap in step 1
     and cleared only by a failure, so every successful round trip left it on Today for the life
     of the process (`REQ-047`).
- **Alt paths — two behaviours the Space has and no scenario covered until `B-106`:** the panel
  is **grabbable**, because it is placed once from where the person happened to be looking when
  the Space opened (`ImmersiveActivity.placePanel`) and a placement computed from one pose is
  wrong for anybody who then moves or sits differently — dragging it is the repair, and it is the
  only one there is. And **the microphone can be asked for from inside the Space**: the system
  page that a refusal sends a person to opens over the shell rather than over the Space, so they
  come back to the panel and not to where they were. That is the platform's behaviour and saying
  nothing about it would be worse (`B-155` is the measurement nobody has taken).
- **Expected result:** one app, two surfaces, no separate data and no lost state — **including a
  dictation started in one surface and finished after leaving it** (`REQ-046`): the transcript
  is written by whichever surface is alive when it is ready, and the note is in the list on the
  panel.
- **UI elements:** *Space*, immersive panel, *Leave the Space*.
- **States covered:** loading, success, error
- **Errors & recovery:** the immersive activity cannot start -> the person stays in the panel and
  the message names the failure with *Retry*; nothing is lost. **That *Retry* tries the Space**
  (`REQ-047`): until now every failure on Today carried the same generic retry and the screen
  answered all of them by reloading the notes, so the one control the message offered re-read a
  list nobody had asked about and the Space was never attempted again.
- **Status:** implemented
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ImmersiveActivity.kt, app/src/main/kotlin/ai/passioncode/fabricvr/PanelActivity.kt
- **Product:** unobserved

### SCN-014: Choose where speech is transcribed, and with which model
- **Persona:** P-01
- **Feature:** voice
- **Traces:** ST-003, ST-010, FLW-05 (JTBD-01, JRN-01/#2)
- **Entry point:** SCR-06 *Settings* -> *Speech*
- **Preconditions:** none
- **Steps:**
  1. Under *Where speech is transcribed*, pick *On this headset*, *Cloud service* or *Whisper
     server* -> the line below states what that choice does with the recording. **One machine,
     one name**: it was *My whisper server* on the chip, *whisper-server URL (optional)* on the
     field, *Your saved whisper server* in the carry-over line and *speech server* in two error
     sentences, and "(optional)" was false the moment that provider was the chosen one
     (`REQ-061`, audit `M27`).
  2. Picking *Cloud service* reveals the address, the key and the model. The address is any
     OpenAI-compatible endpoint; the key is stored in the Keystore and shown only as its last four
     characters.
  3. Under *Model on this headset*, pick one of five by name and size -> choosing does **not**
     download it; the line below says whether it is present and offers the download — or, while a transfer is running, shows its
     progress with *Cancel*, and names any **other** model still downloading ("Medium is still
     downloading (43%)") so a 539 MB transfer does not become invisible the moment the person
     looks at a different name.
  4. Under *Language*, pick *Detect automatically* or pin one.
  5. Beside the clipboard switch, **Sound and vibration for dictation** — on by default,
     covering the four moments of `SCN-004` (`REQ-062`).
  6. At the foot of the screen, **Licences** opens the notice this build actually ships
     (`REQ-063`): it is the repository's own `NOTICE`, copied into the APK rather than pasted
     into a string, so the two cannot drift.
- **Expected result:** the person decides where their voice goes and what it costs, and the words
  next to each choice say so before it is made rather than in a policy.
- **Alt paths — four Settings behaviours had no scenario until `B-106`.** **The stored key can
  become unreadable**, and the screen says which key was lost rather than failing silently — a
  headset whose Keystore entry is replaced loses the two secrets and nothing else, and the
  person is told to enter them again (`DEC-0075`). **The cloud key can be cleared**, which
  returns that provider to its unconfigured state and is the only way to remove a secret without
  clearing app data. **A model can be removed**, freeing its bytes — and removing the one in use
  leaves speech unconfigured rather than silently falling back. **The build says which build it
  is**, at the bottom of the screen, because a second person holding a headset cannot otherwise
  answer the only question that makes their report usable. Beyond those: the chosen model is absent when a dictation starts -> the model banner above the
  record button offers the download rather than failing (`SCN-006`).
- **UI elements:** provider chips and their explanation, language chips, model chips, the download
  and remove buttons, the cloud address, key and model fields.
- **States covered:** empty, loading, success, error
- **Errors & recovery:** a cleartext address on the open internet is refused before any audio leaves
  the headset, with the reason; a cloud provider with no key reports that rather than silently
  falling back.
- **Status:** implemented — `9fc0291`. `SettingsScreen` carries the three-way choice, `GraphSttEngineTest` (6) proves which engine each provider is handed and `SettingsViewModelTest` that saving the cloud key reports `CLOUD_KEY`. `DEC-0024`: a provider chosen but not configured refuses out loud
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsScreen.kt, app/src/main/kotlin/ai/passioncode/fabricvr/ui/LicencesScreen.kt, feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/CloudTranscriptionClient.kt, feature-stt/src/main/kotlin/ai/passioncode/fabricvr/stt/WhisperModel.kt
- **Product:** unobserved

### SCN-015: Delete a note by accident and put it back
- **Persona:** P-01
- **Feature:** notes
- **Traces:** ST-001, FLW-01 (JTBD-01, JRN-01/#1)
- **Entry point:** SCR-01, the *Delete* button on a note
- **Preconditions:** at least one note exists
- **Steps:**
  1. Tap *Delete* on a note -> it leaves the list at once and a line appears naming it, with *Undo*.
  2. Tap *Undo* -> the note returns with its id, its timestamps and its vault file.
- **Expected result:** deleting is one tap and costs nothing when it was a miss. There is no
  confirmation dialog: in a headset a dialog is one more thing to aim at, and an undo offered after
  the act is cheaper than a question asked before every one.
- **Where the delete affordance is, and why it moved** (`B-154`): the row's controls are **two
  groups, `Tokens.Space.l` apart**. *Play* and *Transcribe again* are the frequent ones and sit
  together; *Delete recording* and *Delete* are below them, in `Tokens.Palette.danger` through
  `FabricDangerButton`, and *Copy* is alone on the right with the same clear air it has always
  had. Until this change all four shared one wrapping row at **8 dp**, so the two controls a
  person uses most on a dictated note were neighbours of the two that destroy something — and
  `Tokens.Palette.danger` was defined and worn by no control at all, so they looked identical.
  Stacked rather than side by side because a horizontal gap is whatever room is left after the
  148 dp *Copy* and the panel may be shrunk to 480 dp, and because a ray pivots at the wrist: its
  drift is mostly horizontal, so a horizontal boundary is the one it crosses by accident.
  Measured by `DestructiveAffordanceTest` at 1024 dp and at the declared minimum.
- **Where the line is, and why it moved** (`REQ-060`): *Deleted … Undo* is **pinned between the
  record button and the list**, not the list's first row. As a row it was above everything the
  person had scrolled past — delete the thirtieth note and the only control that could bring it
  back was thirty rows away, off-screen, while the note was already gone. The failure banner and
  the 2.5-second confirmations moved with it for the same reason. The slot is bounded and
  scrolls inside itself, so three notices at once cannot take the notes' room (`B-11`), and the
  record button is above it and does not move (`B-13`).
- **Alt paths — the editor's own delete has no Undo, and that asymmetry had no scenario until
  `B-106`.** Deleting from the list offers *Undo* in a pinned slot; deleting from inside the
  editor closes the note and offers nothing, because the surface that would carry the offer is
  the one being left. The files are still in `vault/.trash/` for seven days, so the act is
  recoverable by a person who knows that — which is exactly the knowledge a scenario is for.
  **Deleting straight after dictating into the note deletes it** (`B-213`): the editor's *Record*
  starts a write on a scope no navigation cancels, and until now that write landed after
  the delete and put the row back — text, recording and all — because the upsert replaces. The
  delete waits for the write it cannot recall and refuses the ones that have not started, so the
  two adjacent controls mean what they say in either order.
  Beyond that: the person does nothing -> the line goes when the next note is deleted or when
  the screen is left or stopped — both are wired now, and `clearJustDeleted()` had **zero
  callers** before `REQ-060`, so the offer outlived the visit and could name a note whose
  recording the trash had since swept; the note stays deleted. **Restoring a daily note whose date another note has
  since taken -> it comes back without the date**: every word is kept and a heading changes, so
  nothing is lost and it is no longer *the* note for that day (`DEC-0035`). Refusing the undo
  leaves the person holding something they cannot get back, and merging the two texts is the most
  surprising outcome available — both were weighed and rejected. **This had never been written
  into the scenario**, only into the decision and the code, for the whole time it has been true.
- **UI elements:** *Delete* (72 dp, `Tokens.Palette.danger`, a `Tokens.Space.l` gap from the frequent controls), the "Deleted …" line, *Undo* (72dp).
- **States covered:** success, error
- **Errors & recovery:** the delete fails -> the note stays and the banner says why; the undo fails
  -> the banner says why and the note is still in hand until the screen is left.
- **Status:** implemented — `NotesViewModel.undoDelete()`, covered by `NotesViewModelTest`'s *undo after delete restores the recording, not only the row*, which is `T-006`'s point: the row coming back is not the same as the audio coming back. **The day rule lives one layer down since `DEC-0058`**, in `NotesRepository.upsert`, so it holds for every caller and not only for undo — `NotesRepositoryTest`'s *a note claiming a taken day gives up the day rather than destroying the holder*
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt, app/src/main/kotlin/ai/passioncode/fabricvr/ui/NotesViewModel.kt
- **Product:** unobserved

### SCN-009: Ask the assistant about my notes

> **RETIRED 2026-09-20 — `DEC-0020` closed the assistant route.** The chat screen and its view
> model are deleted and `:app` no longer depends on `:feature-assistant`, so this scenario has no
> entry point. It is retired rather than deleted: the module stays in the tree against its return,
> `ST-007` still traces here, and a deleted id is a hole nobody can read.

- **Persona:** P-01
- **Feature:** assistant
- **Traces:** ST-007, FLW-04 (JTBD-03, JRN-01/#6)
- **Entry point:** SCR-01 → *Assistant*
- **Preconditions:** an API key is saved; notes exist
- **Steps:**
  1. Tap *Assistant* -> the chat opens with three example questions and the model name visible.
  2. Ask "what did I decide about the panel size?" -> the answer streams in word by word, and a line
     names which notes were used as context.
  3. Tap *Stop* mid-answer -> streaming ends and the partial answer stays on screen.
- **Expected result:** the answer is about the person's own notes, arrives progressively, and can be
  interrupted.
- **Alt paths:** with no notes yet, the assistant says so plainly rather than inventing context.
- **UI elements:** *Assistant*, message list, input field, *Send*, *Stop*, model name, context line.
- **States covered:** empty, loading, success, error
- **Errors & recovery:** no key -> the chat sends the person to settings with one sentence explaining
  why; 401 -> "the key was rejected"; 402 -> "credits exhausted"; 429 -> "rate limited" with *Retry*;
  network failure -> *Retry* with the question preserved.
- **Status:** retired
- **Coverage:** none — the screen and its view model were deleted by `DEC-0020`
- **Product:** unobserved

### SCN-010: Set the API key and the model

> **RETIRED 2026-09-20 — `DEC-0020` closed the assistant route.** The chat screen and its view
> model are deleted and `:app` no longer depends on `:feature-assistant`, so this scenario has no
> entry point. It is retired rather than deleted: the module stays in the tree against its return,
> `ST-007` still traces here, and a deleted id is a hole nobody can read.

- **Persona:** P-01
- **Feature:** assistant
- **Traces:** ST-007, ST-010, FLW-05 (JTBD-03, JRN-01/#6)
- **Entry point:** SCR-06 Settings
- **Preconditions:** none
- **Steps:**
  1. Paste an OpenRouter key -> the field masks it immediately.
  2. Tap *Save* -> the key is stored encrypted and the field shows the last four characters only.
  3. Choose a model -> the assistant uses it for the next question, and the chat header shows its name.
- **Expected result:** the key is usable and never displayed again in full, and the model in use is
  always visible where answers appear.
- **Alt paths:** *Clear* removes the key and the assistant returns to its no-key state.
- **UI elements:** key field (masked), *Save*, *Clear*, model selector, chat header.
- **States covered:** success, error
- **Errors & recovery:** secure storage is unavailable -> the message says the key was not saved and the
  field keeps its content so it can be retried; no key is ever written to the log.
- **Status:** retired
- **Coverage:** none — the screen and its view model were deleted by `DEC-0020`
- **Product:** unobserved

## vault

### SCN-020: See and reclaim what recordings and models occupy
- **Persona:** P-01
- **Feature:** vault
- **Traces:** ST-008, FLW-05 (JTBD-02, JRN-01/#7)
- **Entry point:** SCR-06 *Settings* -> *Recordings* and *Speech models on this headset*
- **Preconditions:** dictations have kept recordings; one or more models are on the headset
- **Steps:**
  1. Open *Recordings* -> the count and the size (*Measuring…* until the walk lands), and a line
     saying every dictation keeps its recording.
  2. Under *What the button below will delete*, pick *Nothing*, *Recordings older than 90 days*,
     30 or 7 -> **nothing is deleted**; *Nothing* is the default (`DEC-0038`), and the line under
     the chips says the choice only arms the button.
  3. With an age chosen, press *Delete recordings older than 30 days* -> those recording files go
     now — a note may keep a newer one (`DEC-0090`) — and the notes stay, and the screen says *"12 recordings removed. The
     notes stay."* The button names the set, never a count (`DEC-0074`).
  4. Open *Speech models on this headset* -> one row per model on disk with its size, *ready* or
     *unfinished download*, and *Remove*.
  5. With more than one model on disk, press *Free up space* -> every model except the selected
     one is removed, and the screen says how many megabytes were freed and which model was kept.
- **Expected result:** the person can see what the app stores on their behalf and remove it
  themselves, and nothing is removed without a press.
- **Alt paths:** *Free up space* is not offered when only one model is on disk — it would reclaim
  nothing. *Remove* on a model that is still downloading cancels the transfer and then deletes it
  (`B-199`). Deleting one note's recording from its row is `SCN-015`'s *Delete recording*.
- **UI elements:** the recordings count and size, the retention chips, *Delete recordings older
  than N days*, the model rows, *Remove*, *Free up space*.
- **States covered:** loading, success, error
- **Errors & recovery:** the sweep fails -> the banner at the top of Settings names the failure and
  nothing is reported as removed.
- **Status:** implemented — **not walked on a headset**
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsScreen.kt:654-732, app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsScreen.kt:752-823, app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsViewModel.kt:636-650, app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsViewModel.kt:797-822
- **Product:** unobserved

## settings

### SCN-021: Hand back a report after the app closed unexpectedly
- **Persona:** P-01
- **Feature:** settings
- **Traces:** ST-010, FLW-05 (JTBD-01, JRN-01/#7)
- **Entry point:** SCR-06 *Settings*, after a crash
- **Preconditions:** the app closed unexpectedly and wrote a crash record (`DEC-0023`, `T-011`)
- **Steps:**
  1. Open Settings -> above the vault section, *The app closed unexpectedly*, a line saying the
     report carries what failed and no note text, and the first six lines of it.
  2. Press *Copy the report* -> the whole report is on the clipboard, to paste wherever it is sent.
  3. Press *Dismiss* -> the record is deleted and the card goes.
- **Expected result:** a second person holding a test copy can hand back a diagnosis without a
  laptop, and the card stops asking once it has been handed on.
- **Alt paths:** no crash record -> the card is not drawn at all.
- **UI elements:** the crash card, *Copy the report*, *Dismiss*.
- **States covered:** success
- **Errors & recovery:** deleting the record fails -> the settings banner names it and the card stays.
- **Status:** implemented — **not walked on a headset**
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsScreen.kt:432-462, app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsViewModel.kt:479-485
- **Product:** unobserved

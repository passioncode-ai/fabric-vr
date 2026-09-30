<!-- Managed with super-ux (ux-contract v4). The design map: every screen and state with its Figma frame, wireframe, code coverage, and resources. Update in the same change as any interface change; when Figma is enabled, update the frame too. -->

# Screens — Fabric VR v1

## Index

| ID | Screen | Used by | Figma | Status | Coverage |
|----|--------|---------|-------|--------|----------|
| SCR-01 | Today (home) | FLW-01, FLW-02, FLW-03 | none — Figma off | built | app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt |
| SCR-02 | Note editor | FLW-01, FLW-02 | none — Figma off | built | app/src/main/kotlin/ai/passioncode/fabricvr/ui/NoteEditorScreen.kt |
| SCR-03 | Voice capture sheet | none — no flow traverses it since `DEC-0010` | none — Figma off | retired | none — deleted by `DEC-0010` |
| SCR-04 | Search | FLW-03 | none — Figma off | built | app/src/main/kotlin/ai/passioncode/fabricvr/ui/SearchScreen.kt |
| SCR-05 | Assistant chat | FLW-04 | none — Figma off | retired | none — deleted by `DEC-0020` |
| SCR-06 | Settings | FLW-04, FLW-05 | none — Figma off | built | app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsScreen.kt |
| SCR-07 | Model download (a state of SCR-01) | FLW-02, FLW-05 | none — Figma off | built | app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt |
| SCR-08 | Space (immersive) | FLW-06 | none — Figma off | built | app/src/main/kotlin/ai/passioncode/fabricvr/ImmersiveActivity.kt |

## Wireframes

**There are none, and the pointers used to say otherwise.** Five screens pointed at
`wireframes/SCR-0N.md` in a directory that has never existed, and `docs/ux/lint.py` was green over
all five — it resolved `Coverage:` and did not resolve `Wireframe:`. Carry-over row 2 decided
text-only for v1, so every entry says `none — text-only for v1` and the linter refuses a pointer
that does not resolve (`[U079]`, `DEC-0051`).

## Design system

- **Style pack:** sheleg-design *workbench* — dark-first, calm motion, one accent.
- **Figma library:** none — Figma is off for v1 (`foundation.md` → *Design tooling*).
- **Tokens in code:** `core-common/src/main/kotlin/ai/passioncode/fabricvr/common/theme/Tokens.kt`
- **Component source:** `core-common/src/main/kotlin/ai/passioncode/fabricvr/common/ui/`
- **Assets:** none beyond Material icons in v1.

## Web surfaces

**Web surfaces:** no — Fabric VR v1 is a native Horizon OS app with no public web page a search or
answer engine can read. No screen carries a `Web surface:` block.

## Panel geometry

The panel/Space split is `DEC-0002`.

**A screen with a field leaves room for the keyboard** (`DEC-0087`). `imePadding()` on Search,
the editor and Settings; the search field's action key says *Search* and dismisses the keyboard,
because the query already runs on every keystroke. On Horizon OS the keyboard may be a spatial
overlay, where this is a no-op — the same build is a 2D window in the shell, where it is not, and
which of the two a Compose panel gets is unmeasured (`B-050`). Five Settings titles carry
`heading()`; the focus ring does not exist yet, for the same unmeasured reason.

**A settings switch is a row, not a thumb** (`DEC-0086`). Both switch rows are `toggleable`
with `Role.Switch`, so the whole 72 dp row is the target and a screen reader announces the label
with the state. Material's switch is 52 × 32 dp and the `heightIn` around it belonged to a row that
was not clickable, so a controller ray had to land on the thumb.

**The Space draws over passthrough and the 2D panel does not** (`DEC-0085`). Both hosts render one
Compose tree; only the background differs — `Palette.ink` opaque in the shell, `Palette.inkSpace`
at alpha 0.92 in the Space. The immersive panel had been configured transparent since it was
written, and the shared composition painted an opaque rectangle over it, so the Space was a black
slab in the room. The alpha is legible over a white wall by arithmetic and **has not been read in
a headset**; that is step 3 of the human walk.

The 2D panel declares `1024dp × 800dp` and is resizable down to `480dp × 736dp` — the two heights
moved from Horizon OS's 640 and from 560 in `DEC-0070`, which named the manifest and
`docs/modules/app.md` and **not this file**, so both numbers here were wrong for two runs until
`B-230`. `check-docs.sh` §26 reads them out of the manifest now. The immersive panel is a separate
registration and did **not** move: `layoutWidthInDp = 1024f`, `layoutHeightInDp = 640f`,
`layoutDpi = 288` → 1843 × 1152 px, inside the 2064 × 2208 px ceiling the Spatial SDK documents.

## SCR-01: Today (home)

The landing screen: a header, the record control, and everything else in one scrolling list.

- **Elements**, as the code draws them since `DEC-0043`/`DEC-0046` — the previous version of this
  line survived the rebuild and described four things that no longer existed:
  - **Fixed, in this order:** the header — *New note*, *Search*, *Space*, *Leave the Space* (only
    in the immersive host), *Settings*, each carrying its word wherever there is width and its
    glyph where there is not (`B-25`); a **130 dp status area** for whatever the voice flow has
    to say; and the **128 dp record control**, which is a toggle — one press starts, the next
    stops. There is **no hold gesture** (`DEC-0010`) and no *Assistant* (`DEC-0020`).
  - **Scrolling, everything else:** the error banner, the transient confirmations and the Undo
    row; **today's day card**; the tag filter chips; the note rows — title, the time it was last
    touched, and the engine·language of a dictation — and the *and N more* row.
  - **A line naming where the recording goes**, first in the list and directly under the button,
    whenever speech is not on this headset (`DEC-0047`, `G-02`). **Nothing when it is local** — an
    app that announces "nothing is leaving" every time trains people to stop reading the line.
  - **A voice note carries a microphone glyph**, beside the engine·language line, tinted with the
    accent and labelled for a screen reader rather than left unlabelled (`DEC-0071`). This bullet
    said the opposite — that the element was owned by no task — for the whole of the time
    `B-153` had already been closed at `cf551cb`, which is what a residue claim looks like: true
    when written, never re-read, and sitting in the file a designer opens first.
- **While a dictation is running** the header's *New note*, *Search*, *Space* and *Settings* are **disabled,
  not hidden** — a control that vanishes mid-gesture sends a controller ray somewhere the person
  did not choose. *Leave the Space* is the exception and stays live (`DEC-0034`): it is the only way
  out of an immersive surface, and leaving transcribes rather than discards. In the last minute of the
  ten-minute maximum the elapsed time becomes a countdown (`DEC-0032`).
- **A state before *Transcribing*:** *"Getting the Small model ready…"*, while the speech engine
  is loading or being swapped. It can be half a minute and it used to be a freeze rather than a
  wait (`DEC-0030`).
- **A note with a recording carries three controls**: *Play* (`DEC-0039` — one player at a time;
  a second tap on another row stops the first, and leaving the screen releases it), *Delete
  recording* (`DEC-0038` — the audio goes, the note stays, and no undo is offered because the
  file is gone) and *Transcribe again*.
- ***New note*** is a **labelled** action first in the header (`DEC-0041`), and it opens the
  editor on the note it creates — **without focusing the title field**, which is `B-01` and not a
  style choice: a keyboard in the Space stops the panel receiving taps at all.
- **A day card**: the date, *Today's note*, and its first line — or *Nothing written yet*.
  Today's note is created at launch and nothing rendered it, so it used to appear as an
  unexplained row bearing today's date.
- **A tag chip row** (`DEC-0042`): *All* plus one chip per tag, single-select, wrapping rather
  than scrolling. **Absent entirely when no tag exists** — an empty container is not how a feature
  should be announced. A selected tag stays on screen even after its last note loses it, so there
  is always something to release.
- **The frame is three fixed things and one list** (`DEC-0043`). Fixed: the header, a 130 dp
  status area, and the 128 dp record button — **in that order, and the button never moves**,
  because a person is holding a controller ray on it for the length of a thought. Everything else
  scrolls, the day card and the chip row included: the fixed chrome is already 418 dp, so at the
  panel's declared minimum there is room for the button or for the chrome around it, not both.
  The declared minimum moved with it — `android:minHeight` is **736 dp** (`DEC-0070`), which is
  that chrome plus one whole note row, because a minimum the list cannot be reached at is a
  promise the shell will let a person take up. It read 560 here until `B-230`: `DEC-0070` raised
  it and this file was not in the decision's affects list.
- **Every row shows when its note was last touched** (`B-02`), the clock for today and the date
  for anything older, in the device's own format.
- ***Transcribe again* expands the row** into a chip per engine rather than opening a menu
  (`B-09`, `DEC-0043`). A `DropdownMenu` is a second window on the panel's `VirtualDisplay` and
  nothing has established that it draws there, let alone that it can be dismissed; this control
  is the whole of `SCN-005`'s recovery path.
- **The part of a row that opens the editor is its text, not its buttons** (`B-20`). The click
  target used to wrap the action row, so the gutter beside *Delete* opened the note instead.
- **The first press asks** (`DEC-0044`). Pressing *Record* with no permission produces the system
  prompt on **that** press; the button never relabels itself to *Allow the microphone*, and
  granting starts the recording without a second press. The explanation lives after a **refusal**,
  where the status area offers *Ask again* and *Write a note instead*.
- **The model banner names what it costs** — the model and its megabytes — and offers *Write a
  note instead* beside *Download*. A finished download says *"The speech model is ready. Press
  Record."* in the same transient place as *Saved, and copied*; before that it ended in the bar simply vanishing.
- **Transcription counts the seconds, and a local decode shows its progress.** At the measured
  1.31× real time a forty-second thought is about fifty-two seconds of waiting. Once the on-device
  engine reports, a percentage and a bar replace the bare counter, and *Stop transcribing* stands
  under it (`B-178`, `B-179`); a cloud service or whisper server reports nothing on the way and
  keeps the counter (`SCN-004`).
- **The confirmation after a dictation tells the truth about the clipboard.** *Saved, and copied.
  Paste it anywhere.* when the copy happened; *Saved.* when the switch in Settings is off
  (`DEC-0047`). `DEC-0012` keeps the copy on by default.
- **States:**
  - `loading` — the list is being read; a calm skeleton, the actions already usable.
  - `empty (first run)` — no notes **and no model**, speech local: what to press, that the first
    dictation downloads the chosen model's size, and that a note can be written without it
    (`DEC-0044`, `SCN-016`).
  - `empty` — no notes, model present: one line. Chosen by the model, never by a first-run flag —
    a flag would go stale the first time somebody removed a model in Settings.
  - `success` — notes listed, newest first.
  - `error` — storage unavailable: message names the cause, offers *Retry*.
  - Every one of these is a row in the scrolling half, so none of them moves the record button
    and none of them can starve the list (`DEC-0043`).
- **Wireframe:** none — text-only for v1 (carry-over row 2; Figma is off, `foundation.md` → *Design tooling*)
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt

## SCR-02: Note editor

- **Elements:** title field; body field (multi-line, `#tags` inline); tag row (parsed, read-only);
  *Record* (which appends to the open note — the button reads *Record*, not *Record into this
  note*); transcript block with its *Dictated · language · where* line and, after a fallback, its
  *Why:* line; *Delete*; auto-save indicator. A **Recordings (N)** list — a time and *Play* per
  row, current first — appears only when the note keeps more than one recording (`B-254`,
  `DEC-0090`); a note with one recording has no player here, *Play* for it is on Today's row
  (`SCN-018`). Playback stops before dictation starts and these *Play* controls remain disabled
  while the microphone is open (`DEC-0098`).
- **States:** `loading` (opening an existing note), `success` (editing, auto-saved),
  `error` (save failed — message plus *Retry*, text never discarded).
- **Wireframe:** none — text-only for v1 (carry-over row 2; Figma is off, `foundation.md` → *Design tooling*)
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/NoteEditorScreen.kt

## SCR-03: Voice capture sheet

**Retired by `DEC-0010`.** The hold gesture and the sheet over the screen were both deleted on
2026-09-20: a hold asks a person to keep a controller ray steady for the length of a thought, and
a sheet is one more thing to dismiss in a headset. What the sheet showed is now drawn inline above
the record control on SCR-01, which is why this entry is retired rather than deleted: **a deleted
id is a hole nobody can read**, and that reason stands on its own.

**It used to say `FLW-02` still traces here, and that was the wrong reason** — `FLW-02`'s own
body lists SCR-01 and SCR-02 and states in as many words that *there is no capture sheet*, while
its index row two dozen lines above still named SCR-03 and SCR-07. So this entry was justified by
a trace that the rewrite had already removed, and the index kept the trace alive for the justifica-
tion. Found by the step-11 group verification (`DEC-0057`); the index row now matches the body,
and `SCR-07` went with it — `DEC-0043` made it a **state of SCR-01**, drawn in place, not a screen
a flow traverses.

Its description, kept for the record:

- **Elements:** level meter; elapsed time; *Release to stop* hint; *Cancel*; after release: a
  progress line "Transcribing…" then the transcript preview with *Save* and *Discard*.
- **States:** `loading` (recording, then transcribing), `success` (transcript shown),
  `empty` (silence: "Nothing was heard" with *Try again*), `error` (permission denied, model
  missing, remote STT failed — each with its own message and action).
- **Wireframe:** none — the screen no longer exists
- **Status:** retired
- **Coverage:** none — deleted by `DEC-0010`; its states live inline on SCR-01

## SCR-04: Search

- **Elements:** query field (focused on entry — a decided exception to `DEC-0041`, kept by
  `DEC-0087`, `SCN-008`); result list with the matching line; tag chips;
  *Clear*.
- **States:** `empty` (no query: recent notes; or no matches: "Nothing matches «query»"),
  `loading`, `success`, `error`.
- **Wireframe:** none — text-only for v1 (carry-over row 2; Figma is off, `foundation.md` → *Design tooling*)
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/SearchScreen.kt

## SCR-05: Assistant chat

**Retired by `DEC-0020`.** The assistant is cut from v1; the screen, its view model and the
OpenRouter settings were deleted on 2026-09-20. Retired rather than deleted for the same reason as
SCR-03: `FLW-04` still traces here.

Its description, kept for the record:

- **Elements:** message list (user and assistant); input field; *Send*; *Stop* while streaming;
  the model name; a line naming which notes were used as context.
- **States:** `empty` (no messages: three example questions), `loading` (streaming, tokens
  appearing), `success`, `error` (no key → *Open settings*; 402 → "credits exhausted"; 429 →
  "rate limited, retry"; network → *Retry*).
- **Wireframe:** none — the screen no longer exists
- **Status:** retired
- **Coverage:** none — deleted by `DEC-0020`

## SCR-06: Settings

- **Elements:** speech section — where speech goes (this headset / a cloud endpoint / your own
  whisper server, `DEC-0014`), the cloud address, key and model, the whisper server's address,
  model status with *Download* / *Resume* / *Remove* and its language (`auto` / `ru` / `en`);
  recordings — usage, how long they are kept, and a delete naming the set it removes
  (`DEC-0074`); the clipboard switch; the cue switch (`DEC-0072`); the vault path (read-only,
  copyable), *Export the vault* and *Restore from an archive* (`REQ-054`); *Licences*
  (`DEC-0073`); the last crash; version and build.
- **Retired elements:** the OpenRouter key field and the Anthropic model selector were deleted by
  `DEC-0020`, which cut the assistant from v1. This entry described them for a day after the code
  stopped having them — the audit of 2026-09-21 (`B-192`) is where that was noticed, and
  `docs/ux/lint.py` checks that every screen HAS an entry, never that the entry is true.
- **States:** `success`, `error` (keystore unavailable, download failed, server unreachable —
  each named).
- **Wireframe:** none — text-only for v1 (carry-over row 2; Figma is off, `foundation.md` → *Design tooling*)
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/SettingsScreen.kt

## SCR-07: Model download

**Not a screen, and no longer drifted.** `DEC-0010` deleted the sheet it lived in; the download
is a **state in SCR-01's status area**, and this entry is kept as the contract that state must
meet. `T-031` closed the gap this paragraph used to describe: the banner names the model and its
size, the estimate line says text notes work meanwhile, and a finished transfer says so instead
of the bar merely vanishing (`DEC-0044`).

- **Elements:** the model and its size, named before the download is committed to — `small`,
  190 MB, and the number is **the chosen model's**, not a constant, because four of the five are
  not 190 (`DEC-0046`); *Download* beside *Write a note instead*; a progress bar with the
  megabytes; an estimate beside *Cancel*; and, when it lands, *The speech model is ready.*
- **States:** `loading`, `success` (verified by checksum), `error` — the network failed, and the
  usable bytes are **kept** and the button says *Resume*, or *Download again* when none survived
  (`REQ-047`); or the checksum did not match, and the file is removed and the message says so, with
  *Download again* (`DEC-0016`).
- **Wireframe:** none — a state in SCR-01's status area
- **Status:** built (as a state, not a screen)
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt
- **A download belongs to the process, not to this screen** (`DEC-0033`). Pressing *Download*
  here and on the Today banner joins one transfer. *Cancel* stops it for both. Leaving does not
  stop it. A model that is transferring cannot be removed, and any **other** model still
  downloading is named with its percentage. A download with no room for it is refused before the
  first byte, with both numbers.
- **A switch for the clipboard** (`DEC-0047`, `G-02`), in the speech block where the dictation is
  configured: *Copy the transcript to the clipboard*, default **on**. `DEC-0012` copies on every
  dictation and stands; what was missing is the ability to refuse a cross-app write nobody offered,
  whose only notice lasted 2.5 s.
- **A Recordings block** (`DEC-0038`): how many there are and what they occupy, the sentence that
  says recordings are kept at all — no string in the product mentioned audio before — a
  retention control whose default is *Nothing*, and a *Delete recordings older than N days*
  button that **names the set it removes, never a count** (`DEC-0074`, `SCN-020`). The size is
  measured off the drawing thread and the row says *Measuring…* until it lands.

## SCR-08: Space (immersive)

The same notes hosted on a Spatial SDK panel over passthrough.

- **Elements:** the SCR-01 panel content; *Leave the Space* — its own word and its own glyph
  (`ExitToApp`), because until `DEC-0029` it was labelled *Back* and wore the same `ViewInAr` icon
  as the control that *enters* the Space, two positions to its left (`B-25`).
- ***Leave the Space* is live even while a dictation is running**, unlike the rest of the
  header. `T-020` disables the others so a recording cannot outlive the screen showing it; this
  one is exempt (`DEC-0034`) because it is the only exit from an immersive surface and no
  controller Back has been observed arriving (`B-115`). Leaving is safe: `ON_STOP` transcribes
  rather than discards.
- **The Back key works here, and it very nearly did not.** `VrActivity.dispatchKeyEvent` (Spatial
  SDK 0.14.0, 65 bytes, disassembled) never calls `super`, so nothing below it — `onKeyUp`,
  `onBackPressed`, the platform's `OnBackInvokedDispatcher` — is reachable in the Space.
  `ImmersiveActivity` overrides `dispatchKeyEvent` itself, which **is** the back path; measured on
  a Quest 3 on 2026-09-21. Whether a physical controller's B button produces that key is still
  unanswered and needs a person wearing the headset.
- **States:** `loading` (scene starting), `success`, `error` (immersive unavailable → returns to
  the panel with a message).
- **Wireframe:** none — text-only for v1 (carry-over row 2; Figma is off, `foundation.md` → *Design tooling*)
- **Coverage:** app/src/main/kotlin/ai/passioncode/fabricvr/ImmersiveActivity.kt

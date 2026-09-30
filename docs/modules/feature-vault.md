# `:feature-vault`

The copy of the notes the person owns.

## Owns

- **`MarkdownSerializer`** — a note as Markdown with YAML front-matter (`id`, `title`, `created`,
  `updated`, `tags`, `day`, `audio`, `lang`, `engine`, `source`, `duration_ms`) and a
  `## Transcript` section when the spoken text differs from the body. `parse` round-trips it.
- **`FileVault`** — writes `notes/YYYY/MM/<id>.md` under the app's files directory, copies the
  recording beside it as `<id>.wav` (earlier ones as `<id>~<stamp>.wav`, `DEC-0090`), and moves
  all of them to the trash on removal.
  **A note reaches disk whole or not at all** (`M2`). It called `writeText` on the live file and
  the editor autosaves every 600 ms, so every save opened the person's only copy for truncation;
  a headset that loses power mid-write left a half-written `.md`, which is not a damaged note but
  **no note** — `MarkdownSerializer.parse` refuses it and the recovery path counts it as skipped.
  The write is now scratch file → `fsync` → rename, and the order is the mechanism: without the
  `fsync` the rename can reach the disk before the bytes it commits. `<name>.tmp` is the one
  artefact a crash leaves; the next write of that note clears it, `VaultExporter` skips it and
  every `.md` walk in this module is blind to it.
  **A directory standing where the file belongs is refused rather than deleted.** Both obvious
  commits destroy one on some platform: `rename(2)` gives `EISDIR` on Linux and **succeeds**
  against an empty directory on Darwin (measured 2026-09-21 — `renameTo` returned `true` and the
  directory was gone), and `File.copyTo(overwrite = true)` deletes it outright. The old in-place
  `writeText` failed everywhere by accident of opening the target directly, and that accident was
  load-bearing for what Settings shows.
  **One writer of a note at a time** (`B-210`). The scratch file is always `<id>.md.tmp`, and three
  callers could stand on it at once: the mirror's collector, `retryFailed` — which snapshots under
  its mutex and then writes **outside** it — and `VaultReconciler.reconcile` at startup. `write`
  now holds the note's own path in `VaultFileLocks` across its whole body, the recording copy
  included, because the `.wav` is addressed by the same pair. Unique scratch names were the other
  answer and were rejected: the single `<id>.md.tmp` is what makes an interrupted write leave one
  identifiable artefact that the next write clears, and unique ones would trade a corrupt file for
  scratch that accumulates for ever on a device whose storage growth is already a finding
  (`G-02`, `G-16`). `writeFileAtomically` itself deliberately does **not** lock: `RemovalJournal`
  holds the same file's lock across its read-edit-write, and a `Mutex` is not reentrant.
- **`VaultMirror`** — subscribes to `NotesRepository.observeChanges()` in application scope. Its
  failure map is updated with `update {}` and never `value = value ± x` (`DEC-0037`): the
  collector and Settings' *Retry* write it from different threads, and a lost update erased a
  failure recorded microseconds earlier — so `vaultOutOfSync` under-reported and **a note that
  never reached the vault looked mirrored** (`I-06`).
  **`retryFailed` retries removals too** (`M7`). It re-ran writes only, so a `vault.remove` that
  failed was retried by nothing: the orphan `.md` stayed and the next rebuild imported a note the
  person had deleted. The banner's count already included those failures, so *Retry* was
  answering for work it did not do.
  **A deletion is written to the journal before it is attempted** (`H5`). A record written only
  when the removal fails is lost to exactly the failure it exists to survive — the process dying
  mid-operation. **The mirror's record is the second copy since `DEC-0089`**: it runs when the
  collector reaches `Deleted`, after the row is gone, so the window between the Room commit and
  the mirror was covered by nothing (`B-239`). `RoomNotesRepository`'s `beforeDelete`, wired in
  `Graph` to this journal, now records it before the row goes.
  **A note written again voids a removal still owed for it** (`B-242`). `write` cleared `pending`
  and never `owed`, so a failed removal followed by *Undo* stayed owed and *Retry* trashed the
  restored note's files; a successful write now drops the owed removal and its journal entry.
  **A note written while its files sit in the trash gets them back first** (`B-241`). A quick
  *Undo* restores before this collector has trashed anything, fails, and writes the note back;
  the collector then runs the delete it still held and trashes the recording. It sees both in
  order, so `write` calls `vault.restore` when `trashedFiles(id)` is not empty — a note being
  written is alive, and what the trash holds under its id is its own.
  **Nothing that happens to one change ends the collector** (`B-209`). `vault.write` and
  `vault.remove` both return a `Result` and throw nothing, so the bare `launch` around a bare
  `collect` looked safe — and the journal, added later for `H5`, throws: `RemovalJournal.persist`
  goes through `writeFileAtomically`, which raises an `IOException` on a full disk. The throw left
  `remove`, left the `collect` and left the `launch`, whose scope carried no
  `CoroutineExceptionHandler`, so it reached the process's uncaught route — one failed deletion
  killed the app, and in the process that noticed it killed the mirror, after which every note
  written looked mirrored and was not. Both journal calls are now under `runCatchingCancellable`
  and the collect body is guarded per change. **What that trades away:** a journal that could not
  be written means this one deletion is not durable — a process that dies before `vault.remove`
  lands leaves no record for `VaultReconciler` to retry, so the file stays and the note returns on
  the next rebuild. One bounded, logged loss instead of the collector and the process.

## The rule that shapes this module

**A mirror failure never fails the note write.** The note is already saved in the index; the
failure surfaces through `VaultMirror.lastError` and Settings reports the vault as out of sync.
Losing the file is recoverable, losing the thought is not.

- **`VaultExporter`** (`DEC-0076`) — the vault as one zip into a stream somebody else owns, so the module stays
  platform-light and the zip building stays testable. It walks the **notes directory,
  not the vault root**: since `T-006` a deleted note is *moved* into the trash directory beside
  it, under the same root, and an exporter that walked the root would hand a person an archive of notes they deleted
  — with every test still green, because a fresh test vault has no trash. A file that is listed and
  then cannot be *opened* is skipped and counted — a vault being mirrored into while it is exported
  is the normal case — and one that fails once its bytes are flowing fails the whole export
  (`DEC-0093`). `DEC-0026`.
  **Each entry is streamed, and a skip is counted** (`B-189`). It was `readBytes()` per entry
  inside a `runCatching` that caught `OutOfMemoryError` — recording length is unbounded and there
  is no `largeHeap`, so at 32 kB/s a one-hour `.wav` is about 115 MB — and `ExportSummary` had no
  skipped count, so the sentence the comment above that line forbids, *"a backup that looks
  complete and is not"*, was exactly what a caller could say. The bytes now travel through a
  64 kB window (`copyBufferBytes`), which bounds the **buffer and nothing else**: no size limit
  was added, because the threshold is unmeasured and inventing one would be the wrong fix.
  **A file that fails once its bytes are flowing fails the whole export** (`DEC-0093`, `B-247`):
  a zip cannot retract a name, so the truncated entry used to stay, counted as skipped, and a
  restore imported it as whole. A file that cannot be *opened* is still a skip.
  **The reconcile also rewrites a file older than its row's last edit** (`DEC-0093`, `B-240`):
  `NoteIdentity.updatedAt` against the file's modification time — a stat, not a read — so an edit
  whose mirror write died with the process reaches the vault on the next launch; an imported note's
  `updatedAt` is clamped to now, or a clock ahead of this headset's rewrote the file every launch
  (`DEC-0096`). A live note whose
  files sit in the trash gets them back before it is rewritten (`DEC-0094`), the mirror's `B-241`
  rule applied to a death between the mirror's delete and its re-write.
  `ExportSummary` carries `skipped` and `skippedReasons` — the distinct exception classes, never
  a message or a path. The stream is opened before the entry is named, so the ordinary case
  (listed, then deleted by the mirror) puts nothing in the archive at all. A note or recording that
  fails part-way through its copy fails the export (`DEC-0093`); an **extra** — the crash log —
  that fails part-way is a skip, because a truncated diagnostic is harmless and the importer
  refuses anything that is not a note (`DEC-0096`). The failure names the file and whether it was a
  recording — `AppError.ExportUnreadable` — so Settings can offer *Export notes only* past a damaged
  recording (`DEC-0097`, `B-258`); only a failed **read** is the file's fault, a failed write to the
  sink stays a storage failure, and the name reaches the log, never the screen (`DEC-0098`).
  `adoptOrKeep` keeps an unmoved recording's own path only while that file exists (`DEC-0098`);
  `adoptAudio` checks existence even when source and destination are already the same path. An extra that is
  simply absent is not a skip: no crash log is the ordinary state of a headset that has not
  crashed. `source` is a seam so a test can count what the exporter pulls and how wide each pull
  is; there is no honest heap assertion for residency, the number differing per machine and GC.

- **`VaultExporter`'s `extras`** — anything else that belongs in the one archive a person takes
  off the headset, by the name it gets inside the zip. `T-011`'s crash log rides there:
  `DEC-0026` deferred it to whichever export existed first rather than inventing a second route
  off the device, and `DEC-0027` is the log.

- **The recordings are countable, removable and bounded** (`DEC-0038`). `audioUsage()` walks the
  live notes tree and answers a count and a byte total — the product had no such number anywhere while
  writing about 1 MB per thirty seconds and keeping it for ever. `removeAudio(note)` takes the
  recording and **keeps the note**: `remove` takes both in one call, so "delete the recording"
  had to mean "delete the note". `sweepAudio(cutoff)` deletes by age and names the notes it
  cleared, **and it never touches the trash** — `remove` moves files with `renameTo`, which
  preserves mtime, so a ninety-day cutoff would otherwise destroy a recording deleted yesterday
  inside its own seven-day undo window.
- **`VaultImporter`** — the vault's Markdown back into the database, and the reason `CONTEXT.md`'s
  *"the database is an index over the vault, never the source of truth"* is a fact rather than a
  wish. `MarkdownSerializer.parse` was called by nothing but its own test, so a database that
  would not open meant lost notes with the Markdown still on disk. It re-derives each note's
  recording from the sibling `.wav` rather than trusting the stored absolute path, which after a
  reinstall points into a `filesDir` that is gone. See `DEC-0036`.
  **It is no longer a gate** (`H6`). `importIfEmpty` asked `recent(1).isNotEmpty()`, which is not
  *"this vault has been imported"* but *"something is in the table"* — so an import killed after
  its first note was never finished, on any later launch, and the person silently kept two notes
  out of forty with every file still on disk. The rule it was protecting (never a merge, because
  two sources of truth reconciling silently is worse than the failure it recovers from) is intact
  and is enforced **per note, by id**: a note the database already holds is not written over.
  **Oldest first, and the order is a rule** (`B-182`). `upsert` gives the day to whoever already
  holds it, so whichever of two files claiming one `day:` is written first keeps it — and that
  was decided by walk order, which is `readdir` order, which is nothing. `MIGRATION_1_2`
  manufactures the situation: it demotes a duplicate row below the repository, so the file keeps
  a `day:` the row no longer has. `createdAt` is the rule everywhere else this question is asked
  (`DEC-0058`, `DEC-0062`) and it is the rule here.
  **It imports through `upsertPreservingTimestamps`** (`M3`): through `upsert` every recovered
  note came back *"updated today"* and the mirror wrote that over the file's own `updated:`.
  **The file's name answers the question before the file is opened** (`B-218`). It did `readText()`
  and `parse` on **every** `.md` and only then asked whether `parsed.id` was known — so on the
  normal launch, where the database holds every note there is, the whole archive was read and
  parsed before the first paint to learn what the directory listing already said. `FileVault`
  writes `<id>.md` and `VaultZipImporter` refuses an archive entry named anything else, so for
  every file this product wrote the name **is** the id; `VAULT_ID_SHAPE` is now one rule in one
  place, used by the importer's shortcut and the archive's refusal. The walk also stays a
  `Sequence` rather than being collected into a list of every file in the vault. A file whose name
  is not an id is still opened and imported by what it says — a person may drop Markdown into
  their own vault with Obsidian. **What it trades away:** a file named for a known id whose front
  matter claims a *different* id is now skipped where it used to be imported. Nothing in this
  module writes such a file; it is a vault disagreeing with itself, and leaving both sides
  untouched is the safer reading.

- **`VaultReconciler`** — the two sides compared, in both directions, idempotently (`H5`, `H6`,
  `M7`). Nothing ever compared them, and three states could be reached that nothing healed: a row
  whose file never landed (the mirror's failure map is in memory, so after a restart the
  out-of-sync count read zero and the export skipped the note in silence), a file whose row is
  missing, and a file whose row is gone because `vault.remove` failed. Order matters: deletions
  the vault owes are settled first so a file about to be removed is not imported on the way past,
  then files with no row, then rows with no file — last, because the import has just created rows
  and they must not be walked as if their files were missing. `start(scope)` returns a `Deferred`
  the first UI read awaits: the import used to be launched and never awaited, so `dailyNote(today)`
  could win the race and leave a duplicate for today on the launch after a restore.

- **`RemovalJournal`** — the one thing that cannot be re-derived, written down. A row with no file
  and a file with no row are both visible by comparing the two sides; a file whose row is gone is
  *indistinguishable* from a file that was never imported, so deriving it would resurrect every
  failed deletion on the launch after the failure. Plain `<id>\t<createdAt>` lines at the vault
  root, dot-prefixed, outside everything the exporter walks — a deletion this headset owes is not
  part of an archive somebody restores onto another one. **Undo overrules it**: a pending removal
  whose row is back is a deletion the person took back, and the reconciler drops it rather than
  deleting the note a second time from a queue nobody can see. One lock **per file** rather than
  per instance, because the mirror and the reconciler are separate objects over one file and
  that is `I-06` again with a deletion as the thing lost. That registry is `VaultFileLocks` now
  (`B-210`) — this map moved out and shared with the note writes, because two registries keyed by
  the same absolute paths would be the same argument one indirection later.

- **`VaultZipImporter`** — the half of `DEC-0026` that did not exist (`H4`). The exporter has
  written the archive since `T-023` and `VaultImporter`'s own comment called it *"a genuine
  restore path: unzip it back into `filesDir/vault`"* — while nothing in the tree could unzip
  anything and `filesDir` is app-private with `run-as` unavailable against a release build. The
  sentence described an operation **nobody could perform**: a backup with no restore is a copy of
  the person's notes they cannot use. The stream is a parameter for the same reason the
  exporter's sink is. Every entry is untrusted and four rules apply — under the notes directory or nothing;
  no `..` and no absolute path; the layout is `notes/YYYY/MM/<uuid>.md`, `<uuid>.wav` or an earlier
  recording `<uuid>~<digits>.wav` (`DEC-0090`), because the
  filename **is** the note's identity and the reconciler imports by id; and the resolved path is
  inside the notes directory, checked canonically, which the other three already imply and which
  is the check that survives an edit to them. **A fifth rule is about volume rather than
  location** (`B-185`): the four above stop an archive writing *outside* the vault and said
  nothing about how much it may write *into* it, so a hostile or merely corrupt archive could fill
  `filesDir` — and a full `filesDir` is how the vault stops being writable, which is the one
  failure the vault exists to prevent. The bound is `freeBytes` with the same ten-per-cent margin
  `ModelDownloader` refuses a download by, so there is **one** answer on this device to *is there
  room for this* rather than a threshold nobody decided.
  It is enforced twice, and the measurement says why. A `STORED` entry declares its size in the
  local header and is refused before a byte is written; a `DEFLATED` entry — which is what
  `VaultExporter` produces — reports `getSize() == -1` from `ZipInputStream`, because the real
  size follows the data in a descriptor (measured 2026-09-22 on this project's JDK). So the
  declared size is a cheap early refusal and **the running total of bytes actually written is the
  only bound that always holds**. Exceeding it fails the whole import rather than counting a
  refused entry, and the partial file is deleted on the way out: a truncated `.md` merely fails to
  parse, but a truncated `.wav` is a playable-looking recording that is not the person's, and the
  reconciler would adopt it.
  The default probe is `usableSpace` rather than the `getAllocatableBytes` that `Graph` hands the
  downloader — deliberately, because here erring toward *less room than you think* fails in the
  safe direction, where a false refusal of a download was the worse failure. Wiring the
  allocatable probe through `Graph` would be strictly better and is a `:app` change.
  A refused entry does not fail the import, and a
  note the vault already holds is **not** overwritten: the live file is newer than any archive's
  by construction, and rolling it back during an operation asked for to avoid losing work is the
  worst moment to do it.

## Checks

`MarkdownVaultTest` (8): round-trip, transcript round-trip with language and engine, a title
carrying a colon and quotes that a YAML reader still accepts, a body containing the transcript
heading that is not split, refusal of a file that is not a note, write/remove paths, and a write
failure reported rather than thrown. `VaultAudioTest` (23 — one is `DEC-0098`'s: `adoptOrKeep` answers null for a recording that is gone; two are `B-254`'s: a note lists its recordings newest first, and one with none lists none; one is a regression case: an identical recording longer than one compare window is not moved aside; two are `B-257`'s: a different recording already there survives an outside copy, and the same copy twice leaves one aside; four are `DEC-0090`'s: a second recording keeps the first beside it, a delete carries the earlier ones into the trash and *Undo* back, *Delete recording* removes them, and a sweep does not name the note): a recording moves into the note's folder
and leaves no scratch copy, adopting one already in place is a no-op, a missing recording fails
rather than inventing a path, and `write` never copies a file onto itself. `VaultMirrorTest` (6 — the sixth is `B-241`'s: a note written back while its recording is in the trash gets it back): a
failed write is remembered per note until that note is written again, *Retry* after a failed
removal and an *Undo* leaves the restored note in the vault (`B-242`), and a hundred concurrent
failures on **real** threads all reach the map — a single-threaded scheduler cannot produce the
lost update that test is about, so it would have been a green proving nothing.

`VaultAtomicWriteTest` (6): a write that dies before the rename leaves the old note intact, the
next write clears the scratch file the interrupted one left, a successful write leaves none, a
filesystem that will not rename still stores the note, and a directory standing where the note
belongs fails the write rather than being deleted. **The instant these are about cannot be
reached from a JVM test** — it is a power cut between two syscalls — so the commit step is
injected and a commit that throws stands in for the machine stopping there; what is asserted is
what survives it.

`VaultReconcilerTest` (13 — one is `DEC-0096`'s: a note imported with a future timestamp stops being rewritten; one is `DEC-0094`'s: a live note whose files are in the trash gets them back; one is `B-240`'s: a file older than its row's last edit is written again; one is the tenth is `B-239`'s: a note deleted before the mirror ran is not imported back — on **real Room** under Robolectric, because the invariants under test
are the repository's and a fake that models them is a second implementation of the thing being
tested): an import killed halfway is finished by the next reconcile with no duplicates, a second
reconcile over a vault already in step changes nothing, a file's own timestamps survive the
import, a row whose file is missing is written back, a failed remove is retried and the note is
not imported back, a pending removal whose note has come back is dropped rather than replayed,
two files claiming one day give it to the note created **first** — with the fixture built so that
walk order and creation order disagree, or the assertion would pass over the defect — the
reconcile can be awaited, and an unparseable file is counted rather than fatal.

`VaultZipImporterTest` (12 — two are `DEC-0090`'s: an earlier recording is accepted, and the same name on a `.md` is refused): a note survives export, a wiped headset and import with its id, both
dates, its transcript and its recording; an entry that climbs out of the vault, an absolute path,
a filename that is not a UUID and an entry outside the notes directory are each refused; a truncated note is
counted as skipped while the rest land; an archive does not overwrite a note the vault
already holds; and **the size bound** — an archive bigger than the free space is refused with
nothing left behind, a declared size beyond it is refused before a single byte reaches the disk,
and an archive that fits is still imported, which is the canary that stops the bound from
refusing everything. The two refusal cases count bytes through an injected sink, because the
difference between the two halves of the bound is *how much reached the disk* and the deleted
partial file makes that invisible through the filesystem.

**The importer relies on `NotesRepository`'s day invariant rather than restating it**
(`DEC-0058`): two vault files carrying one `day:` used to mean one of them was destroyed **by the
import**, which is the path that exists for a reinstall. Nothing in `VaultImporter` changed when
that was fixed, which is what putting an invariant in the right place looks like.

**96 JVM tests**, counted from the tree — it said fourteen before `T-027`, then thirty-two, then forty-two, then forty-three, then sixty-eight, and drifted every time because it was written by hand. `check-docs.sh` §17 computes it now (`DEC-0056`), and caught this very line the same hour the forty-third test was added. The
transcript round-trip caught a real parser defect — the marker lost its newline when the body was
empty.

## Audio

`audioPathFor(note)` is the one place a recording belongs: `<note folder>/<id>.wav`. `adoptAudio`
**moves** a fresh recording there — rename where the filesystem allows it, copy-then-delete where it
does not — and is called by the app before the note is written, so the note is stored once with its
final path. `write` still copies an audio file that sits outside the vault, for a note that arrived
by another route, and skips the copy when the source is already the target: copying a file onto
itself truncates it.
**A second recording never replaces the first** (`DEC-0090`, `B-238`). When `<id>.wav` is already
there — a second dictation into an open note — it is renamed to `<id>~<last-modified>.wav` first,
and the new recording takes `<id>.wav`. `collectFor` (delete, trash), `restore`, `removeAudio` and
the export all carry the earlier files with the note; `VaultZipImporter` accepts
`<uuid>~<digits>.wav`; `sweepAudio` never names a note for an earlier recording it took. The shape
is `EARLIER_RECORDING_SEP` / `EARLIER_RECORDING_SHAPE` in `Vault.kt`, one definition for both
the vault and the importer. `recordingsOf(note)` lists them — current first, earlier newest first
(`B-254`); earlier-file ordering uses the preserved name stamp after a restore (`DEC-0098`).
The interface's default knows only the current file, so every fake stays valid. `write` applies the same rule when it copies a recording from outside
the vault (`B-257`): a *different* file already at `<id>.wav` moves aside, an identical one is left
alone (length first, bytes only when lengths match), so a rewrite never overwrites and never adds
duplicates.

## Deleting is reversible for seven days

`remove` does not unlink: it moves a note's `.md` and `.wav` into `vault/.trash/<id>/`, and
`restore` moves them back. Before this, *Undo* restored the database row and left the recording
deleted, so a note came back pointing at a file that was gone and *Transcribe again* answered with
a `FileNotFoundException`.

The invariant: **only the vault deletes vault files, and the vault's delete is reversible for a
bounded time.** `TRASH_RETENTION_MS` is seven days — one working week, which is how long a mistaken
delete plausibly takes to notice, and it bounds the trash at roughly a fiftieth of the archive it
shadows. `purgeTrash()` runs once at launch from `Graph.init`; the retention has no deadline to
meet, so a periodic job would be machinery for a deletion that can wait. The trash is dot-prefixed
so a vault opened in Obsidian does not show deleted notes as notes.

Restoring a note whose trash has been swept fails honestly: the note comes back with
`audioPath = null` and the person is told, because the alternative is a note claiming a recording
it does not have. Removing a note the vault never held is a **success** — a note whose mirror write
failed was never on disk.

## Audio retention

**This vault is an audio archive with some Markdown in it, and that is the sentence the module
owes a reader.** `G-12`: a person dictates for months believing they are keeping text. The only
string about the vault said *"Every note is mirrored there as Markdown"* — true, and describing
about a thousandth of what is stored.

Derived from the format the code actually writes, not estimated: `AudioRecorder.SAMPLE_RATE` is
16 000, `AudioFormat.CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`, and `WavWriter.toWav` defaults to the
same rate — **32 kB per second of speech**.

| | Audio | Markdown, at ~1 kB a note |
|---|---|---|
| one 30-second dictation | ~0.96 MB | ~1 kB |
| twenty a day | ~19 MB | ~20 kB |
| a year of that | **~7 GB** | ~7 MB |

`DEC-0022` keeps every `.wav`, because it is what makes *Transcribe again* possible and re-running
a bad decode on a better model is the product's whole answer to a weak on-device model. **That
choice is what makes disclosure and management obligatory rather than optional** — `DEC-0038` and
`T-024` deliver them: the interface says that audio is stored, counts it with a real measurement
rather than an estimate, plays it back (`DEC-0039`), and deletes it per note and in bulk, with the
default left at *keep everything*.

**The option this project took is A — audio is a feature.** Option B was a retention sweep that
deletes by age, and it is the reason `T-023`'s export had to ship before any sweep could delete a
byte. The sweep exists and its default deletes nothing.

**Unverified, and it is the one thing here a device answers:** nobody has compared what Settings
claims about the recordings with `du -sh files/vault` on a headset. `B-139` carries it, and the
comparison is not trivial — a byte sum and `du`'s block rounding are different numbers, and the
row says which is which.

## The recording is kept lossless

`DEC-0082`. The vault stores raw 16-bit PCM at 16 kHz — about 32 kB a second, and roughly a
10.7× reduction was on the table with Opus. It was declined as scoped, with the blast radius
measured rather than estimated: twelve `.wav`-literal sites where a miss fails **silently**
(`audioUsage()` reporting 0 kills `DEC-0038`'s disclosure; the notes-only export starts
shipping recordings; archives from a new build are refused entry by entry), and machinery
with no precedent here that Robolectric cannot execute while CI is down. `DEC-0022` keeps the
archive for fidelity and `DEC-0039` made a human ear one of its consumers. The levers instead
are `B-200` (retention that actually fires), `B-202` (stream the WAV write, closing the
measured ~96 MB peak) and `B-201` (compress on export only) — and `B-139`, a `du` on a real
headset, is the measurement that would reverse the decision.

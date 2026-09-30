# Open questions — fabric-vr

Everything undecided, with an owner and what it blocks. This register was
established by DEC-0001. Doctrine: `references/documentation.md`. A question is
**never deleted** — the question is the history of the answer, and deleting it is
how the same thing gets re-argued a quarter later.

**Next free ID:** `OQ-0004`

Reserve the id before you write it; reading this line is not reserving it.

Status is a closed vocabulary: `Open` · `Resolved→DEC-####` · `Dropped (<why>)`.
Anything else reads as answered when it is not, and every check on that row skips
in silence.

| ID | Question | Owner | Blocks | Status |
|---|---|---|---|---|
| OQ-0001 | Does our 2D panel coexist with Meta Virtual Display screens on the device, and how many panels does the shell allow beside them? | operator + run | REQ-001 manual check | Open |
| OQ-0002 | Now that `VaultImporter` exists, should `fallbackToDestructiveMigration()` be added — so a migration that throws wipes the index and the vault rebuilds it, instead of the app refusing to launch for ever? | operator | nothing; it is a choice between two failure modes | Open |
| OQ-0003 | At what note count does the 200-note window stop being enough, and does `androidx.paging` replace it then? | operator + a measurement | nothing today | Open |

**When one resolves:** flip the status to `Resolved→DEC-####` in the **same
change** as the decision that answers it, and leave the row where it is.

## OQ-0002 — the destructive-migration floor

`T-027` deliberately did **not** add it, in the same change that made it survivable, and the
reason is the shape of the mistake rather than the merits:

- **Without it**, a migration that throws means `IllegalStateException` on every launch, no way
  in, and no way out but uninstalling — which destroys the notes, because `adb uninstall` takes
  `filesDir` with it. The data is still on disk and unreachable.
- **With it**, the database is silently rebuilt empty and `VaultImporter` refills it from the
  Markdown on the next launch. What is lost is anything the vault does not hold — and the vault
  holds every note, so in principle nothing.
- **What makes it a question rather than an obvious yes:** the mirror is best-effort. A note
  written while the vault was unwritable exists only in the database, and `vaultOutOfSync` counts
  exactly those. A destructive floor turns that counter into a number of notes destroyed. It also
  converts a **loud** failure into a **silent** one, and this project has found more defects in
  silent recoveries than in loud crashes.

Adding it belongs in its own change with its own DEC, after somebody has looked at what
`vaultOutOfSync` is typically holding on a real headset.

## OQ-0003 — when the window becomes paging

`T-026` bounded every list and search query at `NotesRepository.DEFAULT_WINDOW = 200` and
**rejected `androidx.paging` on cost**: a new dependency, a `LazyPagingItems` rewrite of the list
and a second one for Search, to solve a problem that begins somewhere past a thousand notes on a
two-person deployment. That rejection has an expiry and this is it.

**The trigger, so the decision is scheduled rather than forgotten — either of:**

- the note base passes **~2,000**; or
- `adb shell dumpsys gfxinfo ai.passioncode.fabricvr` shows janky frames while typing in the
  editor at the real note count. The editor autosaves every 600 ms and each autosave re-runs the
  list query and the tag scan, so that is where it would show first.

Neither has been measured. `B-140` carries the `gfxinfo` run, which needs a headset; until it
exists, "the window is enough" is an argument from arithmetic — about 1 KB of text per note, so
~0.2 MB at the window — and not a measurement of anything a person would feel.

**What paging would not fix:** the search window keeps the most *recent* matches rather than the
best ones, because the query orders by `updatedAt`. That is ranking (`G-11` wants `bm25`) and it
is a different question from how many rows are read.

# Axis G — Product, data lifecycle, privacy, language

Read at `d533e0b` on branch `feat/v1-notes-core` (`git rev-parse --short HEAD`), after the merge
`docs/evidence/audits/2026-09-20-v2-audit.md` and the six axis reports. Axes A–F read the code as
code: what it does, what it breaks, what it says about itself. This axis reads it as a **product a
Russian-speaking person uses every day beside a streamed Mac**, and asks four questions nobody else
was given: what is the thing now, what does it accumulate and what ever removes it, what leaves the
headset and who was told, and what does it look like to someone who dictates in Russian.

Where a finding overlaps an earlier axis it says so and does not re-claim it. One of them — E-20,
which stated the Cyrillic tokenizer problem and marked it **unproven** — is settled here by
measurement, and re-graded.

## Findings

| ID | Sev | Where | Claim | Failure scenario | Proposed fix | Verify by |
|---|---|---|---|---|---|---|
| G-01 | **Blocker** | `app/.../Graph.kt:47`; `app/src/main/AndroidManifest.xml:29`; `docs/ux/foundation.md` ST-008; `docs/product/product-definition.md` §3.1 | The vault is `File(appContext.filesDir, "vault")` — **app-private internal storage**. There is no SAF picker, no `FileProvider`, no `ACTION_SEND`, no external directory and no export: `grep -rn "ACTION_OPEN_DOCUMENT_TREE\|DocumentFile\|MANAGE_EXTERNAL\|getExternalFilesDir\|FileProvider\|ACTION_SEND" app core-* feature-*` returns **nothing**. `allowBackup="false"` on top. | ST-008 is priority **must**: "every note mirrored as a Markdown file, so that I can take my notes with me". The person cannot open one file, cannot point Obsidian at the folder, cannot sync it, cannot back it up, and an uninstall or a factory reset destroys every note and every recording with no warning anywhere in the app. The whole "notes you own, vault compatible with Obsidian" claim of the product definition is, on this build, false. D noted this in passing in a scenario row (`D.md:154`) and did not raise it. | Decide the vault's home as a product decision, then implement one of: (a) `ACTION_OPEN_DOCUMENT_TREE` once at first run, vault written through `DocumentFile` to a folder the person picks — the only option that survives an uninstall; (b) `getExternalFilesDir(null)/vault`, reachable over MTP from the Mac, still lost on uninstall; (c) keep it private and **say so** — a string that names the trade and an export button. Anything but the present silence. | `adb shell pm uninstall` then reinstall on a headset with notes in it, and count what is left. Then: from the Mac, with no `adb`, open one note. |
| G-02 | **Blocker** | `feature-vault/.../Vault.kt:58`, `:88-94`; `app/.../SettingsScreen.kt:256-275`; `app/src/main/res/values/strings.xml:86` | Every dictation leaves a `.wav` of the person's voice in the vault, forever, **and nothing in the interface ever mentions audio**. The one vault string is `settings_vault_hint` — "Every note is mirrored there as Markdown. Tap the path to copy it." Markdown is ~0.1% of the bytes; the audio is 99.9% (arithmetic below). There is no player, no listing, no size, no "delete the recording and keep the note", and — by G-01 — no way to reach the folder. | The person dictates private material into a headset for months believing they are keeping text. What they are actually keeping is an undeleteable, unlistenable, unnamed audio archive of everything they have ever said, growing ~1 MB per 30 seconds. The only way to remove one recording is to delete the note it belongs to. This is a privacy property the product acquired without stating it, which is the brief's definition of a Blocker. | Three things, smallest first: (1) a string that names the audio and a size in Settings — "N recordings · X MB"; (2) *Delete recording* on the note row, leaving the note and the transcript; (3) a retention setting with a real default — "keep recordings for 30 days" — and a sweep on `Graph.scope`. (1) is a day; (3) is what makes the growth bounded. | `find vault -name '*.wav' \| wc -l` and `du -sh` on a headset after a week of use, beside what the Settings screen claims. A test over the sweep with a fake clock. |
| G-03 | **Blocker** | `feature-assistant/.../OpenRouterClient.kt:35`, `:56-65`; `NotesContextBuilder.kt:27`, `:70-77`; `app/.../ChatViewModel.kt:72`; absence in both `strings.xml` | Asking the assistant one question posts up to **12,000 characters of the person's notes** — titles, bodies, transcripts, tags, dates — to `openrouter.ai`, and onward to whoever routes `anthropic/claude-sonnet-5`. **No string anywhere says so.** The nearest is `chat_reading` "reading: %1$s" (`strings.xml:96`), which is set *after* the request is in flight (`ChatViewModel.kt:78`), names at most four titles (`ChatScreen.kt:69`), and never says anything left the device. Speech has three explicit disclosure strings (`provider_local_note` / `provider_cloud_note` / `provider_server_note`, `strings.xml:130-132`); the assistant has none. | The person taps a **microphone icon** in the header (G-15), lands on a screen with three inviting example questions, taps one, and twenty-two of their notes are on a US provider's servers. The foundation names this exact anxiety in JTBD-03 — "my notes leaving the device without me deciding" — and the build decides for them. The app is careful about the *voice* and silent about the *text*, which is the wrong way round: the text is the distilled version. | A disclosure line on the Chat empty state and beside the OpenRouter key in Settings, in the same register as `provider_cloud_note`: "Your notes are sent to OpenRouter to answer." Make `chat_reading` permanent and honest — "read N of your M notes" — and add a per-note exclusion or, cheaper and better for v1, cut the screen (G-33). | `grep -rn "OpenRouter" app/src/main/res core-common/src/main/res` — today the only hits are a key field and four error messages, none of which says where the notes go. |
| G-04 | **Blocker** | `core-notes/.../db/NoteEntity.kt:33`; `core-notes/schemas/…/2.json:136` (`"tokenizer": "simple"`); `NotesRepository.kt:110-121`, `:131-139` | **Russian search does not work, and it is now measured rather than suspected.** `@Fts4` takes Room's default `simple` tokenizer, which case-folds **ASCII only**. Measured with `sqlite3` 3.51.0 against a table holding `панель размером и Разрешение экрана`: `MATCH 'панель*'` → 1, `MATCH 'Панель*'` → **0**, `MATCH 'разреш*'` → **0** (the indexed word is `Разрешение`), while `MATCH 'Panel*'` → 1 and `MATCH 'resolution*'` → 1. With `tokenize=unicode61` both Russian cases → 1. This **settles E-20**, which stated the claim and marked it unproven; E graded it Medium. | Whisper capitalises the first word of every sentence and every proper noun, so the words a person most often remembers and searches for are exactly the ones stored capitalised. For an operator whose base is entirely Russian, ST-004 (**must**) and JTBD-02 cannot be delivered: the second of the product's three jobs is dead. It hits three surfaces — `search()` for the Search screen, `searchAny()` for the assistant's retrieval (so the assistant degenerates to "the most recent notes", G-11), and nothing else: tags go through `LIKE` on a lowercased blob (`TagParser.kt:13`, `NoteDao.kt:17`) and are correct for Cyrillic. The regex `[\p{L}\p{N}_]+` (`NotesRepository.kt:171`) is also correct — it is not the tokenisation in Kotlin that is wrong, it is the one in SQLite. | `@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)`, schema v3, a migration that drops and rebuilds `note_fts` from `notes`. Belt and braces: lowercase the terms in `search()`/`searchAny()` too, which costs nothing and makes the behaviour independent of the tokenizer. | The test E-20 already specifies — `assertEquals(1, repo.search("Разреш").first().size)` in `NotesRepositoryTest.kt:109` — plus the mirror case `repo.search("Панель")` against a note containing `панель`. Both fail today. |
| G-05 | High | `app/.../VoiceViewModel.kt:116-118` | `lastAudioPath = runCatching { WavWriter.write(...) }.getOrNull()` — a failed WAV write is swallowed. The note is then created with `audioPath = null` and no message is shown. | The headset fills (it will: G-16's arithmetic, plus up to 1.395 GB of models). The person dictates, the transcript appears, everything looks normal — and the recording was never written. They find out weeks later when *Transcribe again* is absent from a row they wanted to re-run. Silent partial data loss on the product's only path. | Let the failure reach the state: `VoiceState.Ready` carries the transcript, and a `UiMessage` says the recording could not be kept. `AppError.Storage` already maps to "Couldn't save." — a variant for the recording is one string. | `VoiceViewModelStateTest`: a writer that throws `IOException` produces `Ready` **and** a message, not a silent null. |
| G-06 | High | `feature-stt/.../ModelDownloader.kt:141-145`; `core-common/.../UiStateMapper.kt:24-33`; `core-common/.../strings.xml:11,13` | Every throwable during the download body — including `ENOSPC` — is classified `Reason.NETWORK` and shown as "The download stopped. Check the network." `Reason.DISK` and its correct string ("There isn't room on this headset for the speech model.") exist and are reachable **only** from a failed `renameTo` at `:159`. | A person with a full headset is told to check the Wi-Fi, retries, fails again, retries again — 190 MB of traffic per attempt over headset Wi-Fi — and never learns the actual cause. ST-010 ("every failure says what happened and what to do about it") is exactly what this violates. | Classify: `t is IOException && (t.message?.contains("ENOSPC") == true \|\| t is java.io.FileNotFoundException)` → `Reason.DISK`; better, check free space up front (G-07) so the branch is rarely reached. | `ModelDownloaderTest`: a sink that throws `IOException("ENOSPC")` emits `Failed(DISK)`, not `Failed(NETWORK)`. |
| G-07 | High | `feature-stt/.../ModelDownloader.kt:71-80`; `WhisperModel.kt:21-25` | Nothing checks free space before a download. `grep -rn "StatFs\|usableSpace\|getAllocatableBytes" app core-* feature-*` returns **nothing** in main source. The five models are 32 / 60 / 190 / 539 / 574 MB. | Starting a 574 MB download on a headset with 200 MB free wastes the bandwidth, the time and the battery, and ends in the wrong error (G-06). | `StatFs(modelRoot).availableBytes` (or `StorageManager.getAllocatableBytes`) against `store.expectedBytes * 1.1` before the first byte; refuse with `Reason.DISK`. Ten lines. | `ModelDownloaderTest` with an injected space probe; on the headset, fill storage and press Download. |
| G-08 | High | `app/.../SettingsViewModel.kt:183-188`; `app/.../SettingsScreen.kt:171-195`; `Graph.kt:57` | `removeModel()` deletes only `modelStore().modelFile()` — the **currently selected** model. Settings shows presence for the selected model only. All five live in one directory (`Graph.kt:51`) and nothing lists it. | A person exploring quality downloads small, then medium, then large-turbo: **1.303 GB** sits on the headset and the only way to free it is to select each model in turn and press Remove — a sequence nothing in the interface suggests. Total for all five is **1,395,199,447 B = 1.395 GB**, on a device with no expandable storage. | List the directory: one row per downloaded model with its size and a Remove button, and a total. The data is already in `WhisperModel.bytes`. | `ls -la files/models` on a headset against what Settings claims; a `SettingsViewModelTest` over a store with three files present. |
| G-09 | High | `app/src/main/res/values/strings.xml` (152 strings), `core-common/src/main/res/values/strings.xml` (26); `find . -type d -name 'values*'` → exactly those two directories | **There is no `values-ru`.** Every word the operator reads is English, while every word they write is Russian. The persona is explicit (`docs/ux/foundation.md:36`: "Fluent in Russian and English, often mid-sentence in both") and the build addresses only half of that. | Not a blocker — this operator reads English — but it is the difference between a tool that belongs to the person and one they are visiting. It also hides real defects: an English "Retry" under an English "The recording is kept" (A-03) reads as boilerplate; in Russian it would have read as a lie the first day. | Two `values-ru/strings.xml` files, ~178 strings. Three things must happen first or the translation is incomplete: move the three hard-coded chat examples into resources (B-22, `ChatScreen.kt:84-88`); add `few`/`many` to the plural (G-20); re-check every format string keeps its placeholder order. Cost: a day including the copy pass, and it is the cheapest morale change available. | `adb shell settings put system system_locales ru-RU` + relaunch; every screen, no English left. Then `lint --check MissingTranslation`. |
| G-10 | High | `core-notes/.../db/NoteDao.kt:13`, `:28`, `:31-39`; `app/.../NotesViewModel.kt:103-106`; `app/.../SearchViewModel.kt:46`; `app/.../EditorViewModel.kt:113` | The list reads the **whole table** on every change, twice: `observeAll()` (no `LIMIT`) combined with `observeTagBlobs()` (`SELECT tags FROM notes`) — and the tag half feeds a UI that does not exist (B-02, E-11). A blank Search does the same (`SearchViewModel.kt:46`). `dao.search()` has no `LIMIT` either, so a one-letter Russian prefix matches everything. Overlaps **E-12**; the arithmetic is new. | At ~900 bytes of body per dictated note: 1,000 notes ≈ 0.9 MB of text materialised as `NoteEntity` **and again** as `Note` per emission ≈ 2 MB; 10,000 notes ≈ 20 MB. The editor autosaves every 600 ms while typing (`EditorViewModel.kt:113`); each autosave is an upsert, each upsert re-emits **both** flows. At 10,000 notes that is ~20 MB of allocation and two full table scans per 600 ms of typing, on a mobile chip already running whisper and an OpenXR session. The list is the wall long before storage is. | `LIMIT` + paging (`androidx.paging`) on `observeAll`, or at minimum `LIMIT 200` with a "show more". Delete `observeTagBlobs`/`observeByTag` with the dead tag UI (G-24). `LIMIT` on `search`. | Seed 10,000 rows in `NotesRepositoryTest`, assert the query is bounded; on the headset, type in the editor with 5,000 notes present and watch the frame time. |
| G-11 | High | `feature-assistant/.../NotesContextBuilder.kt:27-47`, `:64-68`; `NoteDao.kt:44-52`; `app/.../ChatScreen.kt:67-74` | The context is `searchAny(terms, 20)` + `recent(40)`, concatenated until a **12,000-character** budget. At ~540 characters per rendered dictation that is **~22 notes**, whatever the base holds. Ranking is `ORDER BY notes.updatedAt DESC` — recency, not relevance. And by G-04 the Russian search half returns **nothing**, so in practice the context *is* `recent(22)`. The person is never told: `chat_reading` shows at most four titles and no count. | The assistant answers "что я решил про панель три недели назад" fluently, from the 22 most recent notes, having read none of the three-week-old ones — and nothing on screen distinguishes that from a real answer. A confident answer from the wrong window is worse than "I don't know", and this is the failure mode JTBD-03 exists to prevent. | Three steps, in order: fix G-04 so retrieval works at all; rank by FTS relevance (`bm25`/`matchinfo`) rather than `updatedAt`; make the disclosure permanent and quantitative — "read 22 of your 1,340 notes". Or cut the screen (G-33). | `NotesContextBuilderTest` with 500 fake notes: assert the built context names how many were considered, and that a term present only in note #400 pulls it in. |
| G-12 | High | `feature-vault/.../Vault.kt:58`; `app/.../TodayScreen.kt:405-440`; `docs/ux/foundation.md` JTBD-01 forces | The product stores a WAV per dictation and offers **no way to hear it**. The only use of the audio is *Transcribe again*. | JTBD-01 names the anxiety: "a transcript I cannot trust is worse than no note." The only repair offered is to run a different model and hope. A person who cannot tell whether "сорок" was "сорока" has to remember what they said, which is the thing the app exists to stop them having to do. Meanwhile the audio is the single largest thing the app stores (G-02, G-16) and earns nothing. | A play button on the row, `MediaPlayer` over the WAV. Twenty lines and it turns 99.9% of the stored bytes from a liability into the feature that justifies them. If playback is not worth building, **stop keeping the audio** — the two decisions are the same decision. | Tap play on a note row; the words come out of the headset. |
| G-13 | High | `NotesViewModel.kt:220-238`; `Vault.kt:110-131`; greps for `deleteAll\|wipe\|export` — nothing | **Every removal path in the product, complete:** (1) *Delete* on a row → the note, its `.md` and its `.wav`; (2) `VoiceViewModel.cancel()` → the scratch WAV; (3) a failed `.part` download; (4) `removeModel()` → one model file. That is all. There is no retention, no archive, no compaction, no export, no bulk delete, no "delete everything", and no way to separate a note from its recording. | Growth is unconditional and removal is one row at a time. A person who wants to clear a month of dictations before handing the headset to someone else has to tap Delete several hundred times, with an Undo buffer of exactly one. | Retention for the audio (G-02), a bulk *Delete all* behind a confirmation in Settings, and an export before either exists (G-01). Retention first: it is the one that stops the problem growing. | A Settings screen that can answer "how much is stored and how do I reduce it" without `adb`. |
| G-14 | High | `app/.../ChatViewModel.kt` (no persistence); `app/.../FabricApp.kt:70-75` | The chat lives entirely in a `ViewModel` scoped to the `NavBackStackEntry`. `nav.popBackStack()` destroys it: the conversation, the streamed answer and `usedTitles` are gone. Nothing is written to the notes, the vault or anywhere else. | The person asks a question, gets a good answer, presses Back to check a note, comes back — empty. The one output of the assistant that might be worth keeping cannot be kept, and cannot even be copied (there is no Copy on a chat message). | Minimum: a Copy button on each answer. Better: *Save as note* — the answer becomes a note, in the base, in the vault, where the product says everything belongs. | Ask, Back, return: the conversation is still there, or its answer is a note. |
| G-15 | High | `app/.../TodayScreen.kt:128` | `SmallAction(stringResource(R.string.label_assistant), Icons.Filled.Mic, onChat)` — **the assistant's icon is a microphone**, sitting in a header directly above the record button, in an app whose whole purpose is dictation. | The one control that ships the person's notes to a third party (G-03) is disguised as the one control they already trust. Every wrong tap is a privacy event. Compounding B's finding that Search and the assistant are reachable by icon alone with no label visible at distance. | Any icon but a microphone — `Icons.AutoMirrored.Filled.Chat`, `Icons.Filled.QuestionAnswer`. One line. | Look at the header from a metre away and name what each icon does. |
| G-16 | High | arithmetic below; `feature-stt/.../WavWriter.kt:11-34`; `Vault.kt:58` | Storage grows at **~1 MB per 30 seconds of speech** and nothing bounds it. At the operator's plausible rate — 20 dictations a day, 30 s each — that is **19.2 MB/day, 134 MB/week, ~7 GB/year**, none of it visible, reducible or reachable. | Not an immediate wall on a 128 GB headset, which is why it will be ignored until it is one; the point is that the product has **no number anywhere** — not in Settings, not in the docs, not in a decision — for the thing it accumulates fastest. Combined with G-08's 1.4 GB of models and G-13's absent retention, the first person to run out of space will do so with no diagnosis available. | The numbers in Settings (G-02), retention (G-13), and — the one engineering change that moves the decimal point — store Opus or FLAC rather than raw PCM: a 30 s dictation at 24 kbps Opus is **90 KB**, a **10.7×** reduction, and whisper only ever needs the 16 kHz PCM at transcription time. | `du -sh files/vault` weekly on the headset against the projection. |
| G-17 | High | `strings.xml:30,63,65,69,102`, `:29`; `TodayScreen.kt:199-203` | The empty state the person meets on first launch — `today_empty`, "Nothing here yet. **Hold Record** and say something, **or start a note**." — promises two things the build does not have: the hold gesture was deleted (DEC-0010) and text-note creation is unreachable (A-01). Five more strings say the same (`state_keep_holding`, `state_release_to_finish`, `state_allowed`, `action_hold_to_record`, `action_new_note`). Overlaps B and F-02; stated here because it is the **first sentence the product says to a person**. | The very first instruction is wrong twice. In English an operator shrugs; in the `values-ru` of G-09 a translator would have to invent a gesture that does not exist. | Rewrite `today_empty` to the build ("Ничего ещё нет. Нажмите «Запись» и говорите."), and delete the five dead strings with the dead code. | `grep -rn "R.string.<name>" app/src/main/kotlin` for each; five return nothing. |
| G-18 | Medium | `app/.../NotesViewModel.kt:245`, `:197-201`; `EditorViewModel.kt:63`; `MarkdownSerializer.kt:19` | The title is `transcript.text.take(60)` — a hard cut at 60 characters, mid-word for Russian as for English. It becomes the list row (via `noteText`), the vault's `title:` front-matter key, and the titles the assistant reports in `chat_reading`. | Every note in the vault is titled with a truncated fragment — `"Надо не забыть сказать про панель и что мы решили по поводу р"` — which is what Obsidian will show in its file list and its graph when G-01 is fixed. In Russian the cut lands mid-word more often than in English: the mean word length is longer. | Cut on a word boundary: `text.take(60).substringBeforeLast(' ', text.take(60)) + "…"`, or take the first sentence. | `MarkdownVaultTest`: a 200-character Russian body produces a title that ends on a word. |
| G-19 | Medium | `feature-assistant/.../StopWords.kt:16-24` | The Russian stop list is 28 words against 37 English, and it misses the forms a Russian question actually opens with: `какой/какая/какие/каких`, `почему`, `зачем`, `нужно`, `надо`, `сделать`, `можно`, `сколько`, `кто`, `весь/всё`. `isSignal` drops anything under 3 characters, which correctly removes `я`, `не`, `мы`. | `searchAny` ORs the survivors as prefixes (`NotesRepository.kt:131-139`), so `какие` and `нужно` pull in unrelated notes and crowd out the 22-note budget (G-11) with noise. Less harmful than an AND query would be, but it is the difference between the assistant reading the right notes and reading whatever mentions "нужно". | Add the ~15 missing forms; consider a stem-insensitive check (`почему`/`почем` differ by a character). Ten minutes. | `NotesContextBuilderTest`: "какие задачи нужно сделать по панели" yields terms `{задач…, панел…}` and nothing else. |
| G-20 | Medium | `strings.xml:88-91` | `settings_vault_out_of_sync` declares `one` and `other`. Russian needs **four** categories: `one` (1, 21, 31 — заметка), `few` (2–4 — заметки), `many` (5–20, 0 — заметок), `other`. | The moment `values-ru` exists (G-09), "3 заметка не синхронизирована" appears in Settings. A plural is the one thing a translation cannot fix without the source declaring the slots. | Declare all four in `values-ru`; the `values` file stays as it is. This is the only string in either file that needs structural work before translation. | `lint --check ImpliedQuantity`; render with 1, 3, 5, 21. |
| G-21 | Medium | `Graph.kt:47`; `AndroidManifest.xml:29`; nothing in `docs/`; the merge's own `2026-09-20-v2-audit.md:21` and `F.md:190` | **The second headset is an undocumented, unrecoverable second data store.** The vault root is per-app-install, per-device; there is no account, no identity, no device id, no sync and no share (`grep -rn "sync\|account\|deviceId\|ANDROID_ID\|CRDT\|automerge"` over main source → nothing but `vaultOutOfSync` and `@Synchronized`). Two people dictating produce two disjoint bases that can never meet. | Per-device is **correct for v1** — the product plan puts sync in a later phase (`product-definition.md` §5) — so this is not a scope failure. It is a documentation and hand-off failure: the build is on two headsets, nobody has written down what the second person gets, and by G-01 their notes are in private storage with no way off and no backup. The first uninstall loses them, and nothing warned anyone. | One paragraph in the handoff and one decision: "v1 is single-device; the second headset's notes are its own and are not backed up." Then G-01 makes it survivable. | A `DECISIONS.md` row and a handoff line naming both headsets and what each holds. |
| G-22 | Medium | `NoteEntity.kt:12-15` (`Index(value = ["dayKey"], unique = true)`); `NotesRepository.kt:141-159`; E-11 | The daily note is the one row in the schema whose key is **not** a UUID. Note ids are `UUID.randomUUID()` (`:37`, `:148`), so any future merge of two devices' vaults is collision-free — except `dayKey`, where both devices have a row for `2026-09-20` and the unique index refuses the second. | Adds a concrete cost to E-11's "delete the daily note": keeping it is not merely 365 empty files a year, it is the single thing that will break the first sync the product ever attempts. | Cut the whole daily-note path as E-11 proposes, and the migration that created the index with it. | The schema v4 that removes it; a merge test that imports two vaults. |
| G-23 | Medium | `core-notes/.../TagParser.kt:9,13`; `NoteDao.kt:17`; SCN-003; D-01 | Tag *handling* is correct for Cyrillic — `#([\p{L}\p{N}_-]+)` plus `.lowercase()` parses `#идея`, and `observeByTag` LIKE-matches the lowercased blob without the case problem that breaks FTS (G-04). **But a dictation cannot produce a `#`**: whisper writes "решётка идея" or "хэштег идея" or nothing. Tags are therefore a text-note feature, in a build where text notes cannot be created (A-01) and the filter chips render nowhere (D-01). | ST-005 is unreachable by every path the build has. The code is correct, tested and serving nobody, while costing a full-table read per change (G-10). | Either restore text notes and the chips, or cut `selectTag`/`tags`/`selectedTag`/`observeTags`/`observeByTag`/`TagParser` together. Do not leave it half-built through another cycle. If tags survive: teach the vocabulary — "тег идея" → `#идея` — or the feature stays dictation-proof. | `grep -rn "selectTag\|state.tags\|observeByTag" app/src/main` — today only the declarations. |
| G-24 | Medium | `feature-assistant/.../NotesContextBuilder.kt:49-60` | `render()` emits `### <note.title> (date)` as the heading, and `note.title` for a dictation **is the first 60 characters of `note.body`** (`NotesViewModel.kt:245`). Every block therefore states its own opening twice. | ~60 of ~540 characters per block, ~11% of a budget that only fits 22 notes (G-11). Four or five notes' worth of context spent on duplication, on the axis where context is the scarce resource. | Use the date alone as the heading for a voice note, or omit the title when `body.startsWith(title)` — the same test `noteText` already makes at `TodayScreen.kt:228`. | `NotesContextBuilderTest`: a voice note's block contains its text once. |
| G-25 | Medium | `feature-vault/.../Vault.kt:110-131` | When the computed month folder holds no `<id>.md`, `remove()` falls back to `File(root, "notes").walkTopDown()` over the **entire** vault. | The fallback is correct and necessary (older builds used the device zone), but it is O(N) over every file in the vault on the mirror's coroutine. At 7,000 notes that is 14,000 files walked to delete one — for every delete that misses, and every delete of an old-zone note misses by construction. | Bound it: try the adjacent months (±1) before the full walk; or record the folder in the note. | `VaultAudioTest` with 2,000 files: assert the delete does not walk them. |
| G-26 | Medium | `strings.xml:86`; `SettingsScreen.kt:257-259` | "Every note is mirrored there as Markdown. **Tap the path to copy it.**" — "it" reads as the notes; what is copied is the string of the path, to a clipboard, on a headset, for a folder the person cannot open (G-01). B flagged the clipboard write as device-unverified; the **wording** is the finding here. | The one affordance the product offers for "your notes are yours" does nothing a person can use, and its wording suggests it did something. | Rewrite once G-01 is decided. If the vault stays private, the honest string names that. | Read the sentence aloud to someone who has not seen the code. |
| G-27 | Medium | `TodayScreen.kt:102-108`; DEC-0012 (`docs/DECISIONS.md:201-212`) | The clipboard write is **unconditional and cannot be turned off**: no setting, no per-note exception, no condition at `:104`. DEC-0012 records the consequence honestly ("the app overwrites a cross-app resource the person did not offer, on every dictation") and then accepts it with no control. The notice, `state_saved_and_copied`, lasts 2,500 ms (`:460`). | It is also the product's single best feature (the whole point is pasting into the Mac), so this is not a request to remove it — it is a request for the one case it breaks: dictating something private while a shared screen is mirrored, or while a password manager owns the clipboard. On `targetSdk = 34` the system may show its own clipboard confirmation on Horizon OS; unverified, and not something to rely on. | One switch in Settings, default on. Five lines. | A `SettingsViewModelTest` for the flag; on the headset, confirm whether Horizon OS shows a system clipboard toast (this is a device question — see Not covered). |
| G-28 | Medium | `ChatViewModel.kt:72-78`; `ChatScreen.kt:67-74` | `usedTitles` is assigned **after** `Graph.assistant.ask()` returns, i.e. after the context was built and the HTTP call enqueued. The "reading:" line is a report of a completed action, presented in the place a confirmation would be. | Even the partial disclosure the product has arrives too late to be a decision. A person who sees a title they did not want sent has already sent it. | Build the context, show what it will send, then send on a tap — or accept the post-hoc line and make it complete (G-11). The first is right; the second is cheap. | Ask a question with the network off: the titles still appear, which is the proof they are not a confirmation. |
| G-29 | Medium | `TodayScreen.kt:177-195`; `NotesViewModel.kt:220-240` | The undo buffer is exactly one note, cleared by the next delete, and the row carries no confirmation (deliberate, DEC-0011's reasoning applies). Combined with A-02 (undo restores the row, not the `.wav`), the delete path is the least reversible thing in the product. | Two deletes in a row and the first is unrecoverable — from a base that has no export (G-01), no backup (`allowBackup="false"`) and no bin. | A `deletedAt` column and a 30-day bin, which costs one migration and makes every delete reversible; or at minimum keep the last *N* deletes. Do this **with** A-02's fix, not after it. | `NotesViewModelTest`: two deletes, both recoverable. |
| G-30 | Low | `NotesViewModel.kt:245`; `EditorViewModel.kt:63` | `take(60)` operates on UTF-16 units and can split a surrogate pair, putting a lone surrogate into the title, the DB and the YAML front-matter. Cyrillic is BMP so the operator will not hit it; an emoji in a pasted note will. | A malformed character in a Markdown file other tools have to read. | `text.take(60).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }`, or fix it as part of G-18's word-boundary cut. | A unit test with an emoji at offset 59. |
| G-31 | Low | `OpenRouterClient.kt:61-62` | `HTTP-Referer: https://passioncode.ai` and `X-Title: Fabric VR` identify the app to OpenRouter on every question. | Harmless and conventional — OpenRouter documents both — but it belongs in the leaving-the-device table so the table is complete rather than reassuring. | Nothing. Record it. | — |
| G-32 | Low | `ModelStore.kt:49-53` | The model download contacts `huggingface.co` / `hf.co` on first use. Not disclosed; harmless; the digest is pinned (DEC-0016). | — | Nothing. Record it. | — |
| G-33 | High | product | **The assistant, as built, should be cut from v1.** Not because chat-over-notes is wrong, but because every precondition it needs is missing: retrieval that works in Russian (G-04), a base larger than the window (G-11), a disclosure (G-03), a way to keep an answer (G-14), and a reason to ask a panel instead of the general-purpose assistant two inches away on the streamed Mac. | Keeping it means shipping the product's only undisclosed data egress to serve a feature that reads 22 of the person's notes and cannot say so. Cutting it removes one route, one screen, one key field, one dependency and the whole of G-03. | Delete `Routes.CHAT`, the header action and the OpenRouter block in Settings; keep `feature-assistant` in the repository, untouched, for the phase that earns it. Re-enter when G-04 is fixed and the base is worth querying — and then as "summarise what I dictated", which is what the window actually supports. | The panel with one fewer icon, and `grep -rn "openrouter" app/src/main` empty. |

**Counts — 4 Blocker, 14 High, 12 Medium, 3 Low (33).**

## Storage growth — the numbers

**The unit.** `AudioRecorder` records 16 kHz mono PCM-16 (`AudioRecorder.kt:41`, `:82`).
`WavWriter.toWav` writes a 44-byte RIFF header plus two bytes per sample (`WavWriter.kt:11-34`).

```
16,000 samples/s × 2 B = 32,000 B/s
30 s → 44 + 30 × 32,000 = 960,044 B = 0.96 MB      ← the brief's figure, confirmed from the writer
60 s → 1,920,044 B = 1.92 MB
```

**Everything else, per 30-second Russian dictation.** Russian at ~2.2 words/s ≈ 66 words ≈ 450
characters; Cyrillic in UTF-8 is 2 bytes per character ≈ 900 B of text.

| Artefact | Where | Bytes | Working |
|---|---|---|---|
| WAV | `vault/notes/YYYY/MM/<id>.wav` | **960,044** | above |
| Markdown | `vault/notes/YYYY/MM/<id>.md` | ~1,300 | front-matter ≈ 400 B (`MarkdownSerializer.kt:16-33`) + body 900 B. The transcript block is **not** duplicated: `:36` skips it when `text == body`, which it is for a voice note |
| Room row | `notes` | ~2,100 | body 900 + `transcriptText` 900 (the same text again, `NoteEntity.kt:72`) + title 120 + 8 scalars |
| FTS4 row | `note_fts` | ~3,700 | `@Fts4` with no `contentEntity` (`NoteEntity.kt:33`) stores title+body+transcript **again** as content, plus the inverted index ≈ 1.3× |
| **Total** | | **~967,000 B** | audio is **99.25%** |

**At scale.** One 30-second dictation ≈ 0.967 MB, so:

| Notes | Vault + DB | Of which audio | Files in `notes/` |
|---|---|---|---|
| 100 | **96.7 MB** | 96.0 MB | 200 |
| 1,000 | **967 MB** | 960 MB | 2,000 |
| 10,000 | **9.67 GB** | 9.60 GB | 20,000 |

At the operator's plausible rate — 20 dictations a day — that is **19.2 MB/day, 134 MB/week,
7.0 GB/year**, plus one empty daily note and one empty `.md` per day forever (E-11), plus up to
**1,395,199,447 B = 1.395 GB** of whisper models of which only the selected one can be removed
(G-08). A 128 GB Quest 3 has ~100 GB usable, so storage alone is roughly a **fourteen-year**
problem — which is exactly why it will never be noticed until something else breaks first.

**What breaks first, in order.**

1. **RAM, during a long dictation — minutes, not years.** The recording accumulates as boxed
   `Short`s (E-02: `AudioRecorder.kt:64`, `VoiceViewModel.kt:60`), ~24 B per 2-byte sample →
   **~11.5 MB per 30 s**, ~115 MB at five minutes, ~230 MB at ten, alongside whisper's 190–574 MB
   native context. Nothing bounds the recording length and E-03 makes an unattended one reachable.
   The "out of space mid-recording" the brief asks about is therefore an **OOM**, not a full disk,
   and it arrives at single-digit minutes.
2. **The list — ~1,000 notes, i.e. about seven weeks.** G-10: two full table scans and a full
   re-materialisation per change, and a change is every 600 ms autosave.
3. **The assistant — immediately, and it never recovers.** G-11: a 22-note window over any base.
4. **The vault directory — never.** 20 notes/day is 1,200 files per month folder; ext4 and Obsidian
   both handle that. This is the one thing that scales.
5. **Disk — years, and silently.** G-05 (the WAV write fails quietly), G-06 (ENOSPC reported as a
   network fault), G-07 (no pre-flight check). The failure will be diagnosed as "the Wi-Fi".

**Mid-download.** `ENOSPC` is caught at `ModelDownloader.kt:141`, the `.part` is deleted, and the
person is told to check the network (G-06). The 190 MB already fetched is discarded, and the resume
path (`Range`, `:65-70`) — which A-27 shows has never been tested — cannot help because the file is
gone.

**What deletes anything — the complete list.** (1) *Delete* on a note row → `RoomNotesRepository.delete`
→ `NoteChange.Deleted` → `FileVault.remove` → `<id>.md` + `<id>.wav`. (2) `VoiceViewModel.cancel()`
→ the scratch WAV (`:201`) — including on the *Retry* that promises to keep it (A-03). (3) A failed
download's `.part`. (4) `removeModel()` → one model file. **Nothing else.** No retention, no archive,
no compaction, no export, no bulk delete, and no way to drop a recording while keeping its note.

## What leaves the device

| What | To whom | When | Disclosed? | Can it be turned off? |
|---|---|---|---|---|
| The full recording (WAV) | the endpoint in `KEY_CLOUD_STT_URL`, placeholder `https://api.groq.com/openai` (`CloudTranscriptionClient.kt:131`) | every dictation while provider = **Cloud** (`Graph.kt:89-96`) | **Partly.** `provider_cloud_note` — "Your recording is sent to the service you name below." (`strings.xml:131`) — shown once, in Settings, at configuration time. Nothing at the moment of sending; the row afterwards shows only `engine · language` (`TodayScreen.kt:395-403`) | Yes — switch the provider chip. **Not per note.** |
| The full recording (WAV) | the machine in `KEY_WHISPER_SERVER_URL` | every dictation while provider = **Server** | **Partly**, same shape: `provider_server_note` (`strings.xml:132`) | Yes — the chip. Not per note. |
| The full recording (WAV) | **Groq**, unasked | *Transcribe again → Cloud service* with a key saved and **no address**: `?: CloudTranscriptionClient.SUGGESTED_BASE_URL` (`Graph.kt:106`) | **No.** This is D-10 | Only by clearing the key |
| Note text — titles, bodies, transcripts, tags, dates, **up to 12,000 characters, ~22 notes** | `openrouter.ai` (`OpenRouterClient.kt:35`), onward to the provider behind `anthropic/claude-sonnet-5` (`Models.kt:47`) | every question on the Chat screen | **No.** No string in either `strings.xml` says notes leave the device. `chat_reading` "reading: %1$s" (`strings.xml:96`) names ≤4 titles (`ChatScreen.kt:69`) **after** the call is enqueued (`ChatViewModel.kt:78`) and never says where they went. **G-03** | **No.** Only by not opening the screen. No per-note exclusion exists |
| Every transcript, automatically | the **system clipboard** — readable by whatever app has focus on the headset | every dictation, unconditionally (`TodayScreen.kt:102-108`, DEC-0012) | **Yes, briefly:** `state_saved_and_copied` — "Saved, and copied. Paste it anywhere." (`strings.xml:143`) for 2,500 ms (`:460`) | **No.** No setting, no condition, no exception. **G-27** |
| A note's text on demand | the clipboard | the *Copy* button (`TodayScreen.kt:210`) | it is the button's own name | it is a deliberate act |
| The vault path string | the clipboard | tapping the path (`SettingsScreen.kt:257`) | yes | deliberate |
| `HTTP-Referer: https://passioncode.ai`, `X-Title: Fabric VR` | OpenRouter | every question | no | no. **G-31**, harmless |
| A model request | `huggingface.co` → `hf.co` (`ModelStore.kt:50,53`) | first use of a model | no | no. **G-32**, harmless |
| **Nothing** | the person's own Mac, folder, or backup | — | — | — |

The last row is the finding, not the comfort: the app talks to Groq, OpenRouter and Hugging Face,
and to the person's own computer **not at all** (G-01).

**What the vault holds that the person will not expect.** A `.wav` of every dictation ever made,
named `<uuid>.wav`, beside the note, never removed except with the note, never playable, never
counted, never mentioned. The only string about the vault names Markdown (`strings.xml:86`).
Markdown is 0.13% of the bytes. — **G-02.**

## Language assessment

**What breaks today.** Nothing in Russian *renders* badly — the fonts, the layout and the storage
are all Unicode-clean, and `TagParser` was written for Cyrillic on purpose (`TagParser.kt:5-6`). One
thing is genuinely broken and one is merely absent.

*Broken:* **search**. FTS4's `simple` tokenizer folds ASCII only, measured above (G-04): a query
whose capitalisation differs from the indexed word returns nothing, in Russian only. Whisper
capitalises sentence openings, so the failure is not an edge case — it is the common case. This
kills ST-004 for this operator and degrades the assistant to "the most recent notes" (G-11). The
Kotlin-side tokenisation `[\p{L}\p{N}_]+` (`NotesRepository.kt:171`) is correct and is not the
problem; neither is tag matching, which goes through `LIKE` on a lowercased blob.

*Absent:* **the interface**. 178 English strings, no `values-ru`. The operator reads English, so this
costs comfort rather than function — but it also hides defects, because an English error message
reads as boilerplate where a Russian one would read as a lie.

**What a `values-ru` costs.** Two files, ~178 strings, one day including a copy pass — with three
prerequisites, none of which is optional:

1. `settings_vault_out_of_sync` must declare `few` and `many` in `values-ru` (G-20), or 2–4 notes
   read wrong.
2. The three chat examples hard-coded in Kotlin (`ChatScreen.kt:84-88`, B-22) must become resources
   first, or the first screen of the assistant stays English.
3. The dead and lying strings (G-17) must be deleted or corrected first — translating
   `action_hold_to_record` into a gesture that does not exist is worse than leaving it English.

`LANGUAGES` already puts `auto` first and `ru` second (`SettingsScreen.kt:290`), which is right.
Every format string uses positional placeholders (`%1$s`, `%1$d / %2$d`), so word order is the
translator's to choose — the file header claims this and it holds.

**Should the assistant's system prompt follow the note language?** Yes, and not by translating it.
The prompt is English (`NotesContextBuilder.kt:70-77`) while the notes inside it will be Russian and
the question will be Russian. A frontier model mirrors the question's language reliably, so the
answer will usually be Russian anyway — but "Be brief" and "say so plainly" are register
instructions the model will apply in the language the instruction is written in, and the base is
bilingual by design (P-01 is "often mid-sentence in both"). **One line** — *"Answer in the language
of the question."* — is more robust than a translated prompt, because it handles the mixed case the
persona actually describes. Cost: one line, one test asserting a Russian question yields a Russian
answer.

**Search and tags with Cyrillic, item by item.**

| Surface | Cyrillic-correct? | Evidence |
|---|---|---|
| `TagParser.parse` | **yes** — `#([\p{L}\p{N}_-]+)` + `.lowercase()` | `TagParser.kt:9,13` |
| `observeByTag` LIKE match | **yes** — both sides lowercased, `_`/`%` escaped | `NoteDao.kt:17`, `NotesRepository.kt:81-82` |
| Kotlin query tokenisation | **yes** — `[\p{L}\p{N}_]+` | `NotesRepository.kt:171` |
| FTS4 index and `MATCH` | **NO** — `simple` folds ASCII only | `NoteEntity.kt:33`, `schemas/…/2.json:136`, measured |
| `searchAny` term length ≥ 3 | fine for Russian; correctly drops `я`, `не`, `мы` | `NotesRepository.kt:135`, `StopWords.kt:24` |
| Russian stop words | **incomplete** — misses `какой`, `почему`, `нужно`, `надо`, `сколько` | `StopWords.kt:16-20` (G-19) |
| Title truncation | **weak** — `take(60)` cuts mid-word, more often in Russian | `NotesViewModel.kt:245` (G-18) |
| Whisper language pin | **yes** — `ru` is offered and passed through | `SettingsScreen.kt:290`, `WhisperEngine.kt:59` |

**One thing no translation can fix.** `#tags` cannot be dictated. Whisper renders a spoken hash as
"решётка" or nothing, so in a build whose only input is speech, tags are unreachable by every path
(G-23). If tags survive the cut, they need a spoken vocabulary; if they do not, they should go with
the dead view-model surface.

## Product verdict

**What the product is, today, measured rather than intended.** One screen. One large button. Press
to start, press to stop, the words become a note in a flat list and land on the clipboard. Around
that: *Copy* per row, *Delete* with a one-deep undo, *Transcribe again* with a chosen model, a tap
to open an editor, and four header icons — Search, the assistant (wearing a microphone), Space,
Settings.

It is **a dictaphone with a clipboard and a receipt log.** Everything the product definition calls
the core — a knowledge base with tags, backlinks, a daily page, entities, a vault the person owns
and syncs — is absent, dead, or unreachable. That is not a criticism of the rewrite: the one loop
the build does have is the right loop, it is fast, and it is the only thing in the repository that a
person actually used yesterday. The problem is that the product still describes itself as the other
thing, in its strings (G-17), its documentation, and the features it keeps half-built.

**What a person actually gets:** speak → text on the clipboard → paste into the Mac. That is worth
having, on its own, today.

### Cut

| Cut | Why | Cost of keeping |
|---|---|---|
| **The daily note** (E-11) | no UI, never read, creates a row and a file every day forever, pollutes the assistant's window, and owns the one non-UUID key that will break the first sync (G-22) | 365 files/year and a future migration |
| **The assistant** (G-33) | every precondition missing: Russian retrieval (G-04), a base bigger than its 22-note window (G-11), a disclosure (G-03), a way to keep an answer (G-14) — and a general-purpose assistant is two inches away on the streamed Mac | the product's only undisclosed data egress |
| **Tag filtering** (G-23, D-01) | undictatable, unrendered, and costing a full-table read per change | dead code plus G-10's cost |
| **The Space** | one icon into an immersive activity that traps the person the moment a keyboard appears (B-01), showing the same flat list | the Spatial SDK, a second activity, an untestable failure class |
| **Medium and Large-turbo models** | the app's own strings call them "slow here" and "slowest here" (`strings.xml:124-125`); 1.1 GB that only an undiscoverable sequence can reclaim (G-08) | 1.1 GB and a wrong-error path |

### Add — in this order

1. **A vault the person can reach** (G-01). Without it the app is a silo, and every other claim about
   ownership is false. This is the single most valuable change in the list.
2. **The Cyrillic tokenizer** (G-04). One annotation and one migration restore the product's second
   job.
3. **Text notes** (A-01). A notes app in which you cannot write a note.
4. **Playback, or stop keeping the audio** (G-12, G-02). These are one decision. Playback turns 99%
   of the stored bytes into the feature that justifies them; retention removes them. Doing neither
   is the only wrong answer.
5. **Say what leaves** (G-03, G-27): one line for the assistant, one switch for the clipboard.

### Leave

The record → transcribe → clipboard loop and its single button (DEC-0010/0011 were right for a
headset). *Copy* standing alone on the right of the row. *Transcribe again* with a chosen model —
the repair path that DEC-0013's own reasoning demands. The provider choice, once D-03 and D-10 are
fixed. The Keystore. The vault's Markdown format, which is good and will matter the day G-01 lands.

## Not covered

- **Whether Horizon OS shows a system clipboard confirmation** on `targetSdk = 34`. It changes how
  bad G-27 is and can only be answered on the headset.
- **Whether the Quest microphone's own recording indicator appears** while the app records. E-03
  makes an unattended recording reachable; whether the shell says so is a device question.
- **Transcription quality for Russian across the five models** — the whole premise of *Transcribe
  again* and of the cloud provider, and nobody has measured it. The merge lists it as open.
- **Actual storage on a used headset.** Every number above is computed from the writers, not read
  from a device.
- **What OpenRouter and Groq retain.** Their policies are outside the repository and were not
  fetched; the finding here is the absence of disclosure, not the provider's behaviour.
- **Whether the second tester has dictated anything yet**, and what is on the second headset right now.
- **The Markdown round-trip against Obsidian** — `MarkdownSerializer.parse` exists and is tested
  against itself; no real vault has read these files, because no real vault can (G-01).
- Business logic (A), UI (B), platform (C), scenarios (D), architecture (E) and documentation (F),
  which were read by their own axes and are cited here only where the product consequence is new.

## Notes for the merge

- **E-20 is settled and should be re-graded.** It stated the Cyrillic tokenizer claim and marked it
  unproven at Medium. It is proven (sqlite3 3.51.0, the five `MATCH` results in G-04) and, for an
  operator whose base is entirely Russian, it is a **Blocker**: ST-004 is priority *must* and cannot
  be delivered. The fix E-20 proposes is correct; only the grade moves.
- **Four new Blockers**, none of which any axis raised: G-01 (the vault is unreachable, so "notes you
  own" is false), G-02 (an undisclosed audio archive of everything ever said), G-03 (notes to
  OpenRouter with no disclosure anywhere), G-04 (above).
- **G-01 and D's scenario note are the same fact at two grades.** `D.md:154` records "the root is
  `filesDir/vault` … the hint does not say" as an aside inside a scenario that it grades *Holds*.
  SCN-013 does not hold: the person cannot copy the files off the device. Worth correcting in D's
  table as well as carrying G-01.
- **Three axes now agree the daily note should go** — E-11 (dead surface, 365 files/year), G-22 (the
  one key a future sync will collide on). It is also the first row a person sees every morning —
  an empty note titled with the date, because `observeAll` returns everything. Treat it as a
  cross-axis agreement, not three findings.
- **Two findings are the same decision and must be scheduled together**, or the second will never
  happen: G-12 (no playback) and G-02 (an unmanaged audio archive). Keep the audio and build the
  player, or add retention and stop keeping it.
- **A-02 and G-29 are one fix.** A-02 restores a row whose `.wav` is gone; G-29 is the one-deep,
  unconfirmed, unexportable delete path around it. A `deletedAt` column and a bin closes both.
- **G-33 (cut the assistant) contradicts nothing in A–F and removes work from several of them** —
  G-03, G-11, G-14, G-28, B-22 and part of C's network surface all disappear with the route. It is
  the cheapest severity reduction available in this audit, and it is a product decision, not an
  engineering one, so it belongs to the operator.
- **The counts:** 4 Blocker, 14 High, 12 Medium, 3 Low = 33. Adding them to the register takes the
  audit to **222 findings, 19 Blockers**.

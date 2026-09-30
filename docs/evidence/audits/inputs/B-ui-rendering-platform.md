# Audit input B — UI, rendering, platform (independent reviewer, Opus, 2026-09-19)

Read: `app/**`, manifest, resources, `core-common/theme`, `Components.kt`; contracts `screens.md`, `flows.md`,
`scenarios.md`, spec §3.1–3.2, docs-study §A, `modules/app.md`. Items marked (device-verify) are reasoned
from the SDK bytecode and Android window semantics, not from a headset run.

| id | sev | file:line | defect | failure scenario | fix |
|---|---|---|---|---|---|
| F-B-01 | **Blocker** | `HoldToTalkButton.kt:43-53` + `TodayScreen.kt:79-83` | `onPress` opens a Compose `Dialog` in the same frame it starts the hold. The new window takes touch focus, the panel window receives `ACTION_CANCEL`, `tryAwaitRelease()` returns immediately and `release()` → `stopAndTranscribe()` runs one frame after `start()`. | Every hold records <0.33 s → "Nothing was heard". Voice capture never works. (device-verify) | Render the sheet inside the same window (a `Box` overlay in the panel tree), not a `Dialog`. |
| F-B-02 | **Blocker** | `VoiceCaptureSheet.kt:44` | `rememberLauncherForActivityResult` needs an `ActivityResultRegistryOwner`; the Spatial compose panel provides only Lifecycle, ViewModelStore and SavedStateRegistry owners. | In the Space, holding Record throws `IllegalStateException: No ActivityResultRegistryOwner provided`. (device-verify) | Hoist the permission launcher to the activities and pass a `requestPermission` lambda into `FabricApp`. |
| F-B-03 | **Blocker** | `VoiceCaptureSheet.kt:48`, `ImmersiveActivity.kt:35-39` | A `Dialog` adds a window to the *activity's* WindowManager; `AppSystemActivity` renders the scene, not its 2D window. | SCR-03 is invisible inside SCR-08; recording starts with no UI. (device-verify) | Same fix as F-B-01. |
| F-B-04 | High | `NoteEditorScreen.kt:59-63,108-114`; `EditorViewModel.kt:50` | "Record into this note" saves a **new** note; `attachTranscript` has zero callers. | The dictation lands in a note the person is not looking at. | Give the sheet an `onTranscript(Transcript, audioPath)`; Today creates a note, the editor calls `attachTranscript`. |
| F-B-05 | High | `TodayScreen.kt:74`, `NoteEditorScreen.kt:69`, `SearchScreen.kt:44`, `SettingsScreen.kt:53`, `ChatScreen.kt:54` | `ErrorBanner`'s lambda is `onAction`; four screens pass `dismissMessage()`; Search passes nothing. | Every "…with *Retry*" in the scenarios is a button that clears the message and retries nothing. | Separate `onAction`/`onDismiss`; wire RETRY to a real `retry()` per VM. |
| F-B-06 | High | `VoiceViewModel.kt:58-61`, `VoiceCaptureSheet.kt:44-46,132` | First hold shows a Failed state, not the system prompt; `permanent = true` is never built; after a grant `start()` runs with no button held. | SCN-013 step 1 wrong; permanent-denial path dead; the grant path leaves an open microphone. | Launch the request from the press; track `shouldShowRequestPermissionRationale`; after a grant return to Idle. |
| F-B-07 | High | `TodayScreen.kt:56-68,94-102`; `NoteEditorScreen.kt:46,90` | Nothing survives `minWidth=480dp/minHeight=360dp`: header ≈518dp, tag row `take(12)` ≈1072dp (> the 1024dp default), editor column has no scroll and a fixed 320dp body. | A resized panel clips *Settings*, most chips, the editor body. | `FlowRow`/`LazyRow`, `verticalScroll`, `heightIn(min=)`. |
| F-B-08 | High | `HoldToTalkButton.kt:43-53` | If the composable leaves composition mid-hold the `pointerInput` coroutine is cancelled and `release()` never runs. | Microphone stays open with nothing on screen. | `DisposableEffect { onDispose { if (held) release() } }` or a VM-side guarantee. |
| F-B-09 | High | `SettingsScreen.kt:69` | `saveKey(key); key = ""` clears the field unconditionally; `saveKey` is fire-and-forget. | On a keystore failure the pasted key is gone while the banner says "Your text is still here." | Clear only on success; surface the result as state. |
| F-B-10 | High | `PanelActivity.kt:13` | `startActivity(ImmersiveActivity.intent(this))` is unguarded; no SCR-08 loading/error. | SCN-012 error path does not exist; `ActivityNotFoundException` uncaught. | `runCatching` + a message with RETRY; a "starting the space…" state. |
| F-B-11 | Medium | `ImmersiveActivity.kt:30-34`; `themes.xml:6-9` | `config {}` sets only the layout values; no `themeResourceId`, `enableTransparent`, `includeGlass = false`, `layerConfig`; `Theme.FabricVR.Transparent` unused. | The panel over passthrough keeps the default opaque glass/frame. | Mirror the sample's `config` block. |
| F-B-12 | Medium | `ImmersiveActivity.kt:50-55` | `Transform(Pose(Vector3(0f,1.1f,1.4f)))` — identity rotation, axis convention not read. | If forward is −Z the panel spawns behind the wearer. (device-verify) | Place relative to `scene.getViewerPose()`; explicit look-at rotation. |
| F-B-13 | Medium | `VoiceCaptureSheet.kt:103,110-111`; `VoiceViewModel.kt:126-131` | Discard/Try again/Close call `cancel()`, which never deletes the WAV in `filesDir/audio/`. | Discarded audio accumulates forever. | Delete `audioPath` in `cancel()` from Ready/NothingHeard. |
| F-B-14 | Medium | `ChatScreen.kt:38,121`; `SettingsScreen.kt:40-41`; manifest | `remember` not `rememberSaveable`; `PanelActivity` has no `configChanges` and is resizable → recreated on every resize. | Resizing wipes the typed question, the pasted key, the server URL. | `rememberSaveable`; keep the last question in `ChatViewModel`. |
| F-B-15 | Medium | `VoiceCaptureSheet.kt:48-54` | The `Dialog` column has no background/shape/elevation. | Floating text over the scrim; unreadable over passthrough. | Wrap in `Surface(surfaceRaised, Radius.l)`. |
| F-B-16 | Medium | `ChatViewModel.kt:21`; `SettingsViewModel.kt:32-44,76-81` | Keystore AES + `prefs.edit().commit()` on the main thread. | Dropped frames / ANR risk in a headset. | `viewModelScope` + `Dispatchers.IO`. |
| F-B-17 | Medium | `SearchScreen.kt:29-69` | Field not focused on entry, no recent notes before typing, no *Clear*, no loading state. | Three SCR-04 elements and one state absent. | `FocusRequester`; show `observeNotes(null)` on blank query; clear icon; loading flag. |
| F-B-18 | Medium | `SettingsScreen.kt:96`; `ChatModels.kt:46-50` | SCR-06 missing version/build, custom-model entry, *Remove* for the model, copyable vault path; SCR-07 never shows the model name, no *Cancel* in either surface. | Six named elements absent; SCN-006 step 2 promises *Cancel*. | Add the rows; expose `modelName`; make the download cancellable. |
| F-B-19 | Medium | `VoiceCaptureSheet.kt:83-89` | The sheet prints `language · engine` but not `source`; the transcript is not editable; no "pin a language and re-run". | SCN-007 badge and SCN-005 "editable before saving" fail. | Render `source` + notice; `OutlinedTextField` preview. |
| F-B-20 | Medium | `TodayScreen.kt:107-112` | On a storage error the banner and "Nothing here yet. Hold Record…" render together. | `error` reads as `empty`. | Branch `error` before `empty`. |
| F-B-21 | Low | `HoldToTalkButton.kt:39` | `if (held) accentInk else accentInk` — identical branches. | Dead conditional. | Pick a held colour or drop it. |
| F-B-22 | Low | `HoldToTalkButton.kt:36-56` | No `Role.Button` semantics, no 48dp minimum. | Not announced as a control; small ray target. | `semantics { role = Role.Button }` + `sizeIn(minHeight = 48.dp)`. |
| F-B-23 | Low | `TodayScreen.kt:61,148-150` | `LocalDate.now()` per recomposition, never rolls at midnight; "microphone glyph" is text; `material-icons-extended` unused. | Stale date after midnight. | Day-tick state; `Icons.Filled.Mic`. |
| F-B-24 | Low | `NoteEditorScreen.kt:90`; `strings.xml`; manifest icon | One hard-coded dp; all strings are Kotlin literals; system launcher icon; `Tokens.Motion` unused. | The later copy pass becomes a code edit in 8 files. | `strings.xml` now; a launcher icon; `Tokens.Size`. |
| F-B-25 | Low | `ChatScreen.kt:87` | `items(state.messages)` without a key. | Breaks if messages ever reorder. | `key = { it.id }` once messages carry one. |

## screens.md ↔ code coverage (✓ present · ✓⚠ action inert · ✗ missing · – n/a)

| Screen | loading | empty | success | error |
|---|---|---|---|---|
| SCR-01 Today | ✓ (spinner, not skeleton) | ✓ | ✓ | ✓⚠ F-B-05, F-B-20 |
| SCR-02 Editor | ✓ | – | ✓ (no audio player; record-into broken F-B-04) | ✓⚠ |
| SCR-03 Voice sheet | ✓ | ✓ | ✓ | ✓ |
| SCR-04 Search | ✗ | ✓ (no recent-notes variant) | ✓ | ✓⚠ no handler |
| SCR-05 Chat | ✓ | ✓ | ✓ | ✓⚠ Retry dismisses |
| SCR-06 Settings | – | – | ✓ (F-B-18 gaps) | ✓⚠ |
| SCR-07 Model download | ✓ | – | ✓ | ✓ |
| SCR-08 Space | ✗ | – | ✓ | ✗ F-B-10 |

## Verified fine
Manifest matches spec §3.1 line for line; panel geometry 1843×1152 px inside the ceiling; the Home
PendingIntent path exact; passthrough on; contrast textMuted/surface 6.7:1; elapsed seconds correct;
buffer lock + cancelAndJoin closes the race; autosave flush on Back and dispose; LazyColumn keys where
identity matters; effects keyed correctly; `viewModel()` per back-stack entry; controller ray and hand
pinch arrive as touch events so `detectTapGestures` is the right primitive; `SecureSettingsException`
maps through the single mapper and every mapper string matches spec §3.2.

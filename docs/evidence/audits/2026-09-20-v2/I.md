# Axis I — Panel owners settled, and the threading model

Written 2026-09-20 against `feat/v1-notes-core` at `d533e0b`, after the six-axis merge
(`docs/evidence/audits/2026-09-20-v2-audit.md`). Two jobs the merge names as unresolved: the
contested `SpatialPanelOwners` finding, settled by disassembly rather than by argument; and the
threading model as one subject, which no axis owned.

Disassembly used the JDK shipped with Android Studio
(`/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javap`) over `classes.jar`
extracted from the 0.14.0 AARs in
`~/.gradle/caches/modules-2/files-2.1/com.meta.spatial/`. Every bytecode offset below is
reproducible with the commands in **Verify by**.

---

## Job 1 — what the SDK actually provides

### The bytecode

**`composePanel` builds the ComposeView and hands it straight to `attachLifecycleToRootView`.**

`com.meta.spatial.compose.PanelRegistrationExtensionKt.composePanel` is three instructions of
substance: it captures the caller's lambda into an `invokedynamic` and passes it to
`PanelRegistration.view(Function1)`.

```
composePanel(PanelRegistration, Function1<ComposeView,Unit>):
  14: invokedynamic #38  // Function1 capturing composeViewFun
  19: invokevirtual #44  // PanelRegistration.view:(Lkotlin/jvm/functions/Function1;)LPanelRegistration;
  24: areturn
```

The captured lambda is `composePanel$lambda$1(Function1, Context)`, and that is where the owners
are set:

```
composePanel$lambda$1(Function1, Context) -> View:
   6: new  #51  // androidx/compose/ui/platform/ComposeView
  16: invokespecial #55  // ComposeView.<init>(Context, AttributeSet, int, ...)
  25: aload_0
  27: invokeinterface #59  // composeViewFun.invoke(composeView)      <- OUR setContent runs here
  33: aload_3
  34: getstatic #65  // ViewCompositionStrategy$DisposeOnViewTreeLifecycleDestroyed.INSTANCE
  40: invokevirtual #71  // ComposeView.setViewCompositionStrategy(...)
  43: getstatic #76  // SpatialActivityManager.INSTANCE
  46: invokevirtual #80  // SpatialActivityManager.getAppSystemActivity():AppSystemActivity
  49: aload_3        // the ComposeView
  53: aconst_null    // owner = null  -> default
  56: invokestatic #88  // AppSystemActivityExtensionKt.attachLifecycleToRootView$default(...)
  64: areturn
```

**`attachLifecycleToRootView` sets exactly three view-tree owners, all to one object.**

```
attachLifecycleToRootView(AppSystemActivity, View, PanelViewLifecycleOwner):
   6: aload_1
   7: ifnull 45                 // null view -> no-op
  10..17: owner ?: getPanelViewLifecycleOwner(activity)
  21: aload_1 / 22: aload_3
  26: invokestatic #47  // androidx/lifecycle/ViewTreeLifecycleOwner.set(View, LifecycleOwner)
  34: invokestatic #54  // androidx/lifecycle/ViewTreeViewModelStoreOwner.set(View, ViewModelStoreOwner)
  42: invokestatic #61  // androidx/savedstate/ViewTreeSavedStateRegistryOwner.set(View, SavedStateRegistryOwner)
  45: return
```

`getPanelViewLifecycleOwner(activity)` is `activity.findFeature(ComposeFeature::class).panelViewLifecycleOwner`
(offsets 6–21 of that method). `ImmersiveActivity.kt:91` registers `ComposeFeature()`, so the
lookup resolves; had it not, `findFeature` would have returned null and offset 18's
`invokevirtual getPanelViewLifecycleOwner` would have thrown NPE before the panel ever drew.

**What that owner is, and how well it is driven.**

`PanelViewLifecycleOwner` implements `LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner`
— its constructor allocates a `ViewModelStore`, a `LifecycleRegistry(this)` and a
`SavedStateRegistryController.create(this)`. `onCreate()` is `performRestore(null)` then
`handleLifecycleEvent(ON_CREATE)`; `onDestroy()` is `handleLifecycleEvent(ON_DESTROY)` then
`store.clear()`.

`ComposeFeature` forwards the activity's whole lifecycle into it — `onCreate`, `onStart`,
`onResume`, `onPauseActivity`→`onPause`, `onStopActivity`→`onStop`, `onDestroy`/`onSpatialShutdown`
→ `destroyLifecycleOwner()` (idempotent, guarded by `lifecycleOwnerDestroyed`).

And `VrActivity` really does call those. In `meta-spatial-sdk-0.14.0.aar`:

| `VrActivity` method | dispatches |
|---|---|
| `onPause()` offset 190 | `FeatureManager.onPauseActivity()` |
| `onStop()` offset 125 | `FeatureManager.onStopActivity()` |
| `onDestroy()` offset 139 | `FeatureManager.onStopActivity()` |
| `destroy()` offset 179 | `FeatureManager.onStopActivity()` |

### The verdict

**Axis B is right on the facts, axis C is wrong.** The SDK sets `LocalLifecycleOwner`,
`LocalViewModelStoreOwner` and `LocalSavedStateRegistryOwner` for every panel built through
`composePanel`, on the panel's own `ComposeView`, with the activity-scoped
`ComposeFeature.panelViewLifecycleOwner` as the owner. Only `OnBackPressedDispatcherOwner` was
genuinely missing — the SDK sets no such view-tree owner anywhere in the compose module (five
classes total; none mentions `OnBackPressedDispatcher`).

**Our `CompositionLocalProvider` therefore shadows three SDK-provided owners.** `setContent`'s
content lambda composes *inside* `ProvideAndroidCompositionLocals`, which is what publishes the
view-tree owners as composition locals; `ImmersiveActivity.kt:107-112` provides its own values in
a nested `CompositionLocalProvider`, so for the whole `FabricApp` subtree our values win.

Consequences, stated precisely, because two of the three usual guesses are wrong here:

- **The ViewModelStore.** Not the disaster it looks like. `NavHost` takes
  `LocalViewModelStoreOwner.current` only to hold the `NavControllerViewModel`; each
  `composable {}` then re-provides the `NavBackStackEntry` as the owner, so every screen's
  `viewModel()` (`TodayScreen.kt:83-84`, `NoteEditorScreen.kt:50-51`, …) already resolves against
  the back-stack entry, not against ours. Both stores — ours and the SDK's — are cleared at
  activity destroy. Practically equivalent; the difference is *when* (see I-03/I-17).
- **The lifecycle.** This one does regress, and not in the direction axis B guessed. Our registry
  is driven (`ImmersiveActivity.kt:50,55,59,64`), so the "never leaves CREATED" scenario does not
  happen. What happens is the opposite: `ImmersiveActivity` overrides `onCreate`, `onResume`,
  `onPause` and `onDestroy` and **not `onStop`**, so `registry.currentState` sits at `STARTED`
  while the activity is stopped. The SDK's owner receives `ON_STOP` via the table above. Every
  `collectAsStateWithLifecycle` in the app defaults to `minActiveState = STARTED`, so shadowing
  keeps Room queries and a live chat stream collecting after the Space is gone (I-12).
- **The saved-state registry.** Shadowing it is nearly inert, and the file's own doc comment
  (`SpatialPanelOwners.kt:27-28`, "`rememberSaveable` across a panel rebuild needs the saved-state
  registry") names the wrong mechanism. `rememberSaveable` reads `LocalSaveableStateRegistry`,
  which `ProvideAndroidCompositionLocals` builds from the **view-tree** owner before our provider
  runs. Ours is read only by code that asks for `LocalSavedStateRegistryOwner` directly. Neither
  owner ever calls `performSave`, so nothing survives process death either way.

### Axis B's second claim: the back dispatcher is not merely unfed, it is unreachable

`com.meta.spatial.runtime.VrActivity.dispatchKeyEvent` — the class `AppSystemActivity` extends
(`public class AppSystemActivity extends com.meta.spatial.runtime.VrActivity`) — is 65 bytes and
contains no `invokespecial` to `android/app/Activity.dispatchKeyEvent`:

```
dispatchKeyEvent(KeyEvent) -> boolean:
   7..23: gameControllerDeviceIds.contains(event.deviceId)  ; ifeq 63
  26..57: pinnedControllers[deviceId]?.invoke(null, event)
  61: iconst_1 / 62: ireturn        // game controller: consumed, super never called
  63: iconst_0 / 64: ireturn        // everything else: "not handled", super never called
```

`dispatchGenericMotionEvent` has the identical shape. Since `Activity.dispatchKeyEvent` is what
routes `KEYCODE_BACK` into `onKeyUp`/`onBackPressed`, **no key event can reach the activity's back
path at all.** Nothing in this repository calls `onBackPressedDispatcher.onBackPressed()`
(`grep -rn "onBackPressed" app/src/main` returns only `SpatialPanelOwners.kt:21,42`), and nothing
calls `OnBackPressedDispatcher.setOnBackInvokedDispatcher(...)`, which is what `ComponentActivity`
does to receive Android 13+ predictive back — so the platform route is dead too.

So the dispatcher we install exists **solely to stop navigation-compose crashing at composition**.
It delivers zero back events. `onUnhandledBack = { returnToPanel() }` (`ImmersiveActivity.kt:46`)
is dead code, every `BackHandler` inside the tree is inert, and in the Space the only exit is the
`onLeaveSpace` icon on Today (`TodayScreen.kt:135-137`) — which is not rendered on Search, Chat,
Settings or the editor. Combined with B-01 (the IME swallows every tap), Search is a trap with two
independent reasons rather than one. **Axis B's second claim: confirmed.**

### The correct final shape of `SpatialPanelOwners`

Keep **one** owner, drop **three**, and fix the one thing shadowing was accidentally covering.

```kotlin
/**
 * The one owner a Compose tree needs that the Spatial SDK does not supply.
 *
 * `composePanel` already calls `attachLifecycleToRootView`, which sets ViewTreeLifecycleOwner,
 * ViewTreeViewModelStoreOwner and ViewTreeSavedStateRegistryOwner on the panel's ComposeView to
 * `ComposeFeature.panelViewLifecycleOwner` — verified against the 0.14.0 bytecode, see axis I.
 * Providing our own shadows an owner the SDK drives better than we do.
 *
 * It does NOT set an OnBackPressedDispatcherOwner, and navigation-compose's PredictiveBackHandler
 * requires one; its absence is what crashed every entry into the Space on 2026-09-19.
 */
class SpatialPanelBackOwner(onUnhandledBack: () -> Unit) : OnBackPressedDispatcherOwner {
    override val lifecycle: Lifecycle get() = <the SDK's>   // see note below
    override val onBackPressedDispatcher = OnBackPressedDispatcher { onUnhandledBack() }
}
```

Three qualifications that make this a real change rather than a deletion:

1. **`OnBackPressedDispatcherOwner` extends `LifecycleOwner`.** The remaining owner must therefore
   still answer `lifecycle`, and it must answer with the *SDK's* — `(this as AppSystemActivity)
   .panelViewLifecycleOwner.lifecycle` — not a second registry. `OnBackPressedDispatcher
   .addCallback(owner, callback)` uses that lifecycle to unregister callbacks, so a registry
   nobody stops leaks every `BackHandler` the app ever composed.
2. **Back must actually be delivered, or say plainly that it is not.** Dropping the three owners
   does not fix I-13. Either override `dispatchKeyEvent` in `ImmersiveActivity` to call
   `owner.onBackPressedDispatcher.onBackPressed()` on `KEYCODE_BACK` `ACTION_UP` and return true
   (super is unreachable, so the override is the whole mechanism), or delete the fallback and put
   a Back control on every screen. Keeping an unreachable fallback is the same class of defect as
   A-03: a promise the code does not keep.
3. **Both owners are activity-scoped, not panel-scoped.** `ComposeFeature` holds one
   `PanelViewLifecycleOwner` for the whole activity, and so does `SpatialPanelOwners`. With one
   panel that is invisible; a second panel would give two `NavHost`s one `SavedStateRegistry` and
   collide on its key. If a second panel is ever registered, the fix is a per-panel owner passed
   as `attachLifecycleToRootView`'s third argument — which the SDK accepts and this app never
   uses (`aconst_null` at offset 53 above).

---

## Job 2 — the threading model

### Dispatchers and threads

| Thread / dispatcher | Created at | What runs on it | Owner |
|---|---|---|---|
| **Main** (`Dispatchers.Main.immediate`) | platform | every `viewModelScope.launch` in the app, every `LaunchedEffect`, every `rememberCoroutineScope()` | Compose |
| `Graph.scope` — `Dispatchers.Default` + `SupervisorJob` | `Graph.kt:39`, started `Graph.kt:165` | one coroutine: `VaultMirror.start`'s `observeChanges().collect` | nobody; process-lifetime, never cancelled |
| `Dispatchers.IO` | — | `FileVault` (`Vault.kt:48`, injected), `SettingsViewModel.io` (`SettingsViewModel.kt:72`, injected), `AudioRecorder.flowOn` (`AudioRecorder.kt:79`), `ModelDownloader.flowOn` (`ModelDownloader.kt:166`), `RemoteWhisperClient`/`CloudTranscriptionClient` `withContext` (`:48`/`:52`), `ChatViewModel.kt:51`, `NotesViewModel.kt:179` | per-call |
| **`"whisper"`** — one thread, `Executors.newSingleThreadExecutor` | `WhisperEngine.kt:31-32` | every `WhisperNative.*` JNI call; the whole point is serialising one `whisper_context` | the `WhisperEngine` instance, closed by `Graph.localEngine()` |
| Room's `ArchTaskExecutor` IO pool | `NotesDatabase.open` (`NotesDatabase.kt:42`) | every suspend DAO call and every `Flow` query | Room |
| OkHttp dispatcher + connection pool | three separate `OkHttpClient`s: `ModelDownloader.kt:40`, `OpenRouterClient.kt:26`, `RemoteWhisperClient.kt:29` / `CloudTranscriptionClient.kt:40` | `call.enqueue` callback in `OpenRouterClient.kt:68` runs on an OkHttp thread; `execute()` elsewhere runs on the caller's | each client |
| Spatial SDK render thread + `Choreographer` | `VrActivity` (`implements Choreographer$FrameCallback`) | `onSceneReady`, `onSceneTick`, panel dispatch | SDK |
| AudioRecord's own capture buffer | `AudioRecorder.kt:44` | filled by the platform; drained by the `Dispatchers.IO` coroutine | `callbackFlow` |

Four clients, four pools, no shared `OkHttpClient` — three of them are constructed as default
arguments, so each `Graph.sttEngine()` call (see below: on Main, per dictation) allocates a fresh
`CloudTranscriptionClient`/`RemoteWhisperClient` and with it a fresh dispatcher and pool.

### What runs on Main that must not

`VoiceViewModel` contains no `withContext` and no `Dispatchers` import at all
(`grep -c withContext app/src/main/.../VoiceViewModel.kt` → 0). Everything it does happens on
`viewModelScope`, which is `Dispatchers.Main.immediate`.

| Main-thread work | Where | Cost |
|---|---|---|
| `WavWriter.write(...)` — allocates `44 + 2n` bytes and writes the file | `VoiceViewModel.kt:116-118` | a 60 s dictation is 1.9 MB encoded and written on Main |
| `Graph.sttEngine()` — **four** Keystore AES-GCM decrypts (`KEY_WHISPER_SERVER_URL`, `KEY_CLOUD_STT_URL`, `KEY_CLOUD_STT_KEY`, `KEY_CLOUD_STT_MODEL`) plus `sttProvider()` and `localEngine()` | `VoiceViewModel.kt:131` → `Graph.kt:86-100` | first Keystore use is tens of ms; `localEngine()` may `close()` — see I-04 |
| `Graph.sttLanguage()` — a fifth decrypt | `VoiceViewModel.kt:119` | — |
| `Graph.whisperModel()` + `ModelStore.isPresent()` (`File.exists` + `File.length`) + `serverUrl()` decrypt, three times per Record tap | `VoiceViewModel.kt:72,80,81` | disk stat + Keystore in a click handler |
| `Graph.model()` — Keystore decrypt inside `ask()`'s state update | `ChatViewModel.kt:66` | in a click handler |
| `Graph.assistant` (`by lazy`) — builds `OpenRouterClient`, which builds an `OkHttpClient` and runs `NetworkPolicy.requireReachable` in its `init` | `ChatViewModel.kt:72` → `Graph.kt:155-161` | first ask only |
| `OpenRouterClient.stream`'s `callbackFlow` body: `apiKey()` (Keystore decrypt), `json.encodeToString` of the entire prompt + history, `client.newCall(...).enqueue` | `OpenRouterClient.kt:50-68` — **no `flowOn`**, so it runs in the collector's context, which is `viewModelScope` | proportional to the 12 KB context |
| `NotesContextBuilder.build` — `render()` over up to 60 notes into a 12 KB string | `Assistant.kt:14` ← `ChatViewModel.kt:72`; the DAO calls suspend off-Main, the rendering does not | — |
| `Graph.withEngine(...)` and `Graph.sttLanguage()` for a re-run | `NotesViewModel.kt:186-187`, invoked from `rememberCoroutineScope()` at `TodayScreen.kt:213` | `withEngine` may construct a `WhisperEngine` and, via `localEngine()`, `runBlocking` — see I-04 |
| `notesViewModel.createVoiceNote(...)` | `TodayScreen.kt:105`, a `LaunchedEffect` body | `TagParser.parse` and the `Note` copies on Main; the vault and Room hops are correct |
| `Graph.init` → `vaultMirror` → `notes` → `NotesDatabase.open` | `FabricVrApp.kt:8` | `Room…build()` does no disk IO, so this is cheap — but `Graph.settings` (`SharedPreferences`) and `SettingsViewModel`'s `Graph.vault.root.absolutePath` default argument do touch disk on Main |

There is exactly one `runBlocking` in the whole of `*/src/main`: `WhisperEngine.kt:89`. It is
reachable from Main (I-04).

### Shared mutable state and its guards

| State | Guard | Verdict |
|---|---|---|
| `Graph.loaded: Pair<WhisperModel, WhisperEngine>?` (`Graph.kt:64`) | `@Synchronized localEngine()` (`Graph.kt:71`) — the monitor is the `Graph` object | Guarded for `loaded` itself. But the monitor is held across `engine.close()`, which is `runBlocking` (I-04), and `withEngine` reads `whisperModel()` *outside* it (I-08) |
| `VoiceViewModel.buffer` + `bufferLock` (`VoiceViewModel.kt:59-68`) | `synchronized` on all three accessors; `stopAndTranscribe` additionally `cancelAndJoin`s the producer before draining (`:109-110`) | **Correct.** The only sound piece of concurrency in the app. Its cost is that Main blocks on a lock an IO thread holds while `addAll`-ing a boxed `ArrayList<Short>` (I-20) |
| `VaultMirror.pending` + `Mutex` (`VaultMirror.kt:32-33`) | `lock.withLock` on every access | Guarded — but the **`_failures` StateFlow next to it is not** (I-06), and `remove()`'s failure branch never touches `pending` (I-07) |
| `KeystoreSecureSettings.cached: SecretKey?` (`SecureSettings.kt:63`) | `@Volatile` + `@Synchronized secretKey()` (`:109`) | Correct against double generation, which is what the comment claims. It also means a Main-thread `get()` can block on an IO thread inside `KeyGenerator.generateKey()` with StrongBox (I-21). `_corruptedKeys` is an unguarded read-modify-write (I-20) |
| `ImmersiveActivity.composedAtLeastOnce` (`ImmersiveActivity.kt:171-173`) | `@Volatile`, `internal set`, written from the composition | Adequate as a test handshake. Never reset in production; a process that composed once answers `true` forever (I-27) |
| `SpatialPanelOwners.registry` / `viewModelStore` | none; touched from Main only | Fine as far as threads go; the ordering is the problem (I-17) |
| `RoomNotesRepository.changes` (`NotesRepository.kt:58-62`) | `MutableSharedFlow`, replay 64, buffer 256, `SUSPEND` | Thread-safe. `SUSPEND` makes the vault's latency back-pressure the UI's save (I-18) |
| `EditorViewModel.saveJob` / `VoiceViewModel.recordJob`, `downloadJob` / `ChatViewModel.job` | plain `var`, Main-confined | Main-confined is true; the bug is that `attachTranscript` does not use `saveJob` at all (I-01) |

### Races, as interleavings

**I-01 — the editor's autosave overwrites the dictation it was racing.** *Blocker.*

```
Main t+0    edit(body="hello")        -> state.note = A(body="hello"),  saveJob := launch{delay 600; save(A)}
Main t+100  VoiceState.Ready          -> LaunchedEffect -> attachTranscript(tx, path)
                                          state.note = B(body="hello\n\ntx", transcript=tx)
                                          launch{...}  <-- a NEW job; saveJob is NOT cancelled
IO   t+140  adoptOrKeep moves the .wav into the vault
Main t+150  save(B.copy(audioPath=…)) -> Room row = B. state.saved = true
Main t+600  the ORIGINAL saveJob fires -> save(A)
                                       -> Room row = A: transcript gone, audioPath gone, body reverted
```

`EditorViewModel.kt:46` assigns `saveJob`; `EditorViewModel.kt:67` does not, and
`attachTranscript` never calls `saveJob?.cancel()`. The window is the 600 ms autosave delay, and it
is open exactly when someone dictates into a note they are typing — the product's own headline
gesture. `VaultMirror` then mirrors A over B, so the loss reaches the user's Markdown folder too.

**I-02 — a dictation committed on the composition's scope, cancelled by leaving the screen.** *Blocker.*

```
Main   VoiceState.Ready -> TodayScreen.kt:102 LaunchedEffect(voice) starts on the COMPOSITION scope
Main   clipboard.setText(...)                                   (done)
Main   notesViewModel.createVoiceNote(...)  -> vault.adoptOrKeep -> withContext(IO)
IO     renameTo(target): the .wav is now in the vault, not in scratch
Main   person taps Settings (or the Space is paused) -> TodayScreen leaves the composition
                                                     -> LaunchedEffect cancelled BEFORE repository.upsert
                                                     -> voiceViewModel.consumed() (line 106) never runs
Main   back on Today: voice is still Ready -> LaunchedEffect(voice) fires AGAIN
Main   createVoiceNote -> adoptAudio -> source.isFile == false -> FileNotFoundException
                       -> adoptOrKeep keeps the SCRATCH path -> the note records a file that is gone
```

Two distinct outcomes from one defect: cancel after the move and before the upsert leaves the
recording orphaned in the vault under an id no row carries; cancel and return leaves a note whose
`audioPath` points at nothing, which is the same end state as A-02. `createVoiceNote` is a
`suspend fun` whose only caller is a `LaunchedEffect` (`TodayScreen.kt:105`); a commit must not be
scoped to a composition.

**I-03 — `flush()` launched into a scope the destroy path has already cancelled.** *Blocker.*

`ImmersiveActivity.onDestroy` (`:63-66`) runs `owners.onDestroy()` **before** `super.onDestroy()`:

```
Main  owners.onDestroy()        -> registry = DESTROYED; viewModelStore.clear()
                                -> NavControllerViewModel.onCleared -> every NavBackStackEntry store cleared
                                -> EditorViewModel.onCleared -> viewModelScope CANCELLED
Main  super.onDestroy()         -> AppSystemActivity -> FeatureManager.onStopActivity/onDestroy
                                -> ComposeFeature.destroyLifecycleOwner -> SDK owner DESTROYED
                                -> DisposeOnViewTreeLifecycleDestroyed disposes the composition
Main  NoteEditorScreen.kt:67 onDispose { viewModel.flush() }
                                -> saveJob = viewModelScope.launch { ... }  on a cancelled scope
                                -> the body never runs. Silently.
```

The composition strategy the SDK installed (`composePanel$lambda$1` offset 34) keys disposal to the
**SDK's** owner, which our owner is destroyed ahead of. E-01 called this "a real risk, loss window
≤600 ms"; in the immersive host it is not a risk but the deterministic order, and `flush()` is a
no-op on every exit from the Space.

The same function races on the ordinary path too, for a different reason:
`NoteEditorScreen.kt:81` is `{ viewModel.flush(); onBack() }`. `Dispatchers.Main.immediate` starts
the coroutine inline, so `save()` reaches its first suspension inside Room and returns; `onBack()`
then pops the entry, clears its store, and cancels the scope mid-write.

**I-04 — closing a whisper context from Main while a transcription runs.** *Blocker.*

```
Main     VoiceViewModel.transcribe -> Graph.sttEngine() -> Graph.localEngine() returns E(model=small)
whisper  E.transcribe(...)  -- WhisperNative.transcribe, 5-30 s for a 60 s utterance
Main     person opens Settings, picks `medium`; SettingsViewModel.put -> Keystore write
Main     person taps Record again (or Re-run) -> Graph.sttEngine() -> @Synchronized localEngine()
Main         wanted=medium != small -> E.close()
Main             closed = true
Main             runBlocking(E.dispatcher) { freeContext }   <-- WhisperEngine.kt:89, on MAIN
                 the single "whisper" thread is busy; the free is queued behind it
Main         ...blocked for the remainder of the transcription -> ANR
```

`close()`'s doc (`WhisperEngine.kt:78-84`) is right that freeing must happen on the engine's own
thread; what it does not say is that `runBlocking` makes the *caller* wait for it, and every caller
of `Graph.localEngine()` in this app is on Main (`VoiceViewModel.kt:131`, `NotesViewModel.kt:186`
via `rememberCoroutineScope`). Worse, the wait happens while holding `Graph`'s monitor
(`@Synchronized`, `Graph.kt:71`), so any other coroutine touching `localEngine()` blocks behind it.
Android kills the process at 5 s of unresponsive input; a `medium`-model transcription is longer
than that on a Quest 3.

**I-05 — two downloads appending to one `.part`.** *High.*

`VoiceViewModel.downloadModel()` (`:153-169`) and `SettingsViewModel.downloadModel()` (`:190-215`)
are separate jobs on separate view models, both alive at once because Today stays in the back stack
while Settings is open, and both resolve to `Graph.modelDownloader()` → the same
`FileModelStore.modelFile()` → the same `<name>.part`.

```
Main(Today)    downloadModel() -> flowOn(IO) -> FileOutputStream(partial, append=false)
Main(Settings) downloadModel() -> flowOn(IO) -> FileOutputStream(partial, append=false)
IO#1 / IO#2    both write 64 KB chunks into one file, each hashing only its OWN bytes
IO#1           finishes: digest != store.expectedSha256 -> partial.delete() -> Reason.CHECKSUM
IO#2           finishes: writes into a deleted inode, renameTo fails or renames garbage
```

The pinned SHA-256 (`ModelStore.kt:37-38`, `FileModelStore.kt:46-47`) is what stops a corrupt model
reaching whisper — so this is a wrong-result race, not a corruption one. Its user-visible shape is
that the download "keeps failing the checksum" and 380 MB of headset Wi-Fi is spent twice.

**I-06 — `VaultMirror._failures` is a read-modify-write across two dispatchers.** *High.*

```
Default  (Graph.scope) write(n1) fails -> _failures.value = _failures.value + (n1 to e)
Main     (SettingsViewModel.kt:224) retryFailed() -> write(n2) succeeds
                                                 -> reads _failures.value   [does NOT contain n1 yet]
                                                 -> _failures.value = that - n2
                                          the n1 entry is erased
```

`VaultMirror.kt:50,54,61,66` are four unsynchronised `x.value = x.value ± y` on a `StateFlow`
touched from `Dispatchers.Default` and from Main. The Mutex next to them guards `pending` and
nothing else, and the two are updated non-atomically — so `pending` and `failures` can disagree
about the same note. `SettingsUiState.vaultOutOfSync` is read straight off this
(`SettingsViewModel.kt:85`): the count the person is shown is the thing the race corrupts. The
class docstring says failures are held per note precisely so one cannot hide another; the
implementation loses them anyway, by a different route.

**I-07 — a deleted note resurrected into the vault.** *High.*

```
Default  Upserted(n) -> vault.write fails (disk full) -> pending[n] = note
Main     person deletes n -> Deleted(n) -> vault.remove fails (the .md was never written)
                                        -> VaultMirror.kt:65-67 records the failure and
                                           NEVER touches `pending`
Main     Settings -> Retry -> retryFailed() -> write(n) now succeeds
                           -> a Markdown file appears in the person's vault for a note they deleted
```

`remove()`'s success branch clears `pending` (`:61`); its failure branch does not. Nothing else ever
removes an id from `pending` on deletion.

**I-08 — `Graph.withEngine`'s model check is outside the lock.** *High.*

```
Main   retranscribe(note, LOCAL, small) -> Graph.withEngine
Main     reads whisperModel() == small        (Graph.kt:139, NOT synchronized)
IO     SettingsViewModel.put(KEY_STT_MODEL, "medium") commits
Main     takes the branch `model == whisperModel()` -> block(localEngine())
Main       localEngine(): wanted = medium -> closes the small engine, opens medium
                        -> the re-run the person asked to do with `small` runs on `medium`
```

The `RetranscribeResult` then reports success and the transcript records `engine = "whisper-small-q5_1"`
regardless — `WhisperEngine.name` (`:29`) is a hard-coded constant, so the note claims a model it
did not use. Wrong result, recorded as fact, in the one feature whose purpose is to let the person
compare models.

**I-19 — `SearchViewModel.retry()` stacks collectors.** *Medium.*

`NotesViewModel.observe()` cancels `observer` first (`NotesViewModel.kt:101`). `SearchViewModel`
keeps no `Job` and `retry()` calls `observe()` again (`SearchViewModel.kt:70`), so every retry adds
another `launchIn(viewModelScope)` over the same debounced query. N collectors then race to write
`_state`; results flicker between query generations and the debounce is defeated.

**I-10 — Main blocks on the record buffer's lock.** *High.*

`AudioRecorder` calls `onSamples(chunk)` from `Dispatchers.IO` (`AudioRecorder.kt:69,79`) into
`appendSamples`, which takes `bufferLock` (`VoiceViewModel.kt:66`). `drainBuffer` and `clearBuffer`
take the same lock from Main. The critical section is `buffer.addAll(samples)` on a
`mutableListOf<Short>()` — boxed, so a 60 s dictation is 960 000 `java.lang.Short` objects, and
`ArrayList.addAll` periodically copies an array of that length while holding the lock. This is
E-02's memory finding seen from the lock side: the longer the dictation, the longer Main waits at
`stopAndTranscribe`.

**I-22 — changing the model mid-download discards the partial in silence.** *Medium.*

`SettingsViewModel.downloadModel()` resolves `modelDownloader()` once, at launch, pinning the
downloader to model A. `saveWhisperModel(B)` → `put` → `reload()` recomputes `modelPresent` from
`modelStore()`, which now answers for **B**, so the screen shows "not downloaded" with a Download
button while A is still transferring. Tapping it runs `downloadJob?.cancel()` (`:191`) → A's flow
hits `catch (CancellationException)` → `partial.delete()` (`ModelDownloader.kt:139`). A's resumable
partial is gone and nothing said so. The progress bar in between is A's bytes under B's name.

**I-12 — the shadowed lifecycle never stops.** *High.*

Settled in Job 1: `ImmersiveActivity` overrides no `onStop`, so `SpatialPanelOwners.registry` rests
at `STARTED` when the activity is stopped, while the SDK's owner receives `ON_STOP`. Everything
under `collectAsStateWithLifecycle` (all five screens) keeps collecting: Room's `observeAll`
requery on every write, `SearchViewModel`'s debounce, and `ChatViewModel`'s SSE stream — the last
of which holds an OkHttp connection with a 10-minute call timeout on a battery-powered headset.

**I-13 — back is unreachable, and the fallback advertises otherwise.** *High.*

Settled in Job 1 by the `VrActivity.dispatchKeyEvent` bytecode. Every `BackHandler` in the tree is
inert and `returnToPanel()` is dead. On Search, Chat, Settings and the editor inside the Space,
`onLeaveSpace` is not rendered (`FabricApp.kt:43-51` passes it to `TodayScreen` alone), so the only
way out is the system menu.

**I-09 — the chat's whole prologue on Main.** *High.*

`ChatViewModel.ask` (`:57-92`): `Graph.model()` at line 66 is a Keystore decrypt in the click
handler; `Graph.assistant` at line 72 builds an `OkHttpClient` on first use; `Assistant.ask` renders
up to 60 notes into 12 KB on Main; and `OpenRouterClient.stream`'s `callbackFlow` body carries a
sixth Keystore decrypt plus `json.encodeToString` of the whole prompt — with **no `flowOn`**, so it
runs in `viewModelScope`. `ChatViewModel.kt:49-54` already establishes that the author knew the
model name had to come off Main; line 66 puts it back.

**Two races the merge asked about that are NOT reachable as written, and why that matters**

- **`VoiceViewModel.cancel()` deleting a file `adoptAudio` is moving.** Not reachable. `cancel()`
  is offered only from the `Failed` banner (`TodayScreen.kt:166,288,291`) and `adoptAudio` runs
  only in `Ready`; the two are branches of one `MutableStateFlow`, so they cannot coexist.
  A-03 is a real Blocker — *Retry* deletes the recording — but it is a wiring defect, not a race.
  The reachable sibling is I-02, where the mover is cancelled rather than raced.
- **`VaultMirror` writing while a note is deleted.** Not reachable as a write/delete interleaving:
  `changes` is a single `MutableSharedFlow` with one collector on `Graph.scope`
  (`VaultMirror.kt:36-43`), and `RoomNotesRepository` emits `Upserted` and `Deleted` in program
  order with `SUSPEND` back-pressure, so the mirror processes them in order. The real defects in
  that class are I-06 and I-07, which are about the state *beside* the queue, not the queue.

### The rules a correct model would have

Six rules, no rewrite. Each one closes findings named above.

1. **A view model owns a dispatcher, and Main is not it.** Every view model takes an
   `io: CoroutineDispatcher = Dispatchers.IO` constructor parameter the way `SettingsViewModel`
   already does (`:72`), and every `Graph.*` read, file write and JNI entry goes inside
   `withContext(io)`. `VoiceViewModel` is the whole of the work: `start`, `stopAndTranscribe` and
   `transcribe` (I-11) and `retranscribe` is the same defect one layer up (I-14, I-09, I-10).
2. **Main may do exactly two things: read a `StateFlow` and assign one.** Anything that touches the
   Keystore, the filesystem, JNI or JSON is off it by construction. The mechanical check is
   `grep -n "Graph\.\|File(\|WavWriter\|settings.get"` inside any `viewModelScope.launch {` that
   has no `withContext` — today that matches eleven sites.
3. **A commit is never scoped to a composition.** `LaunchedEffect` may observe and may call a
   `fun`, never a `suspend fun` that writes. `createVoiceNote` becomes
   `fun commit(...)` launching on `viewModelScope`, and the state machine advances only after the
   write returns — which also removes the double-fire in I-02.
4. **One pending write per editor, one job that owns it.** `attachTranscript`, `edit`, `flush` and
   `retry` all assign the same `saveJob` and cancel the previous one; `save` reads
   `_state.value.note` at call time instead of closing over a snapshot. That is I-01, and it is
   four lines.
5. **The engine's lifetime belongs to `Graph`, and `Graph` never blocks.** `localEngine()` becomes
   `suspend`, the `@Synchronized` becomes a `Mutex`, and `close()` becomes
   `suspend fun close()` using `withContext(dispatcher)` instead of `runBlocking` — the free still
   happens on the whisper thread, the caller still waits, and no OS thread is held (I-04).
   `withEngine` reads `whisperModel()` inside that Mutex (I-08). One `WhisperEngine` per process,
   closed only from a suspend context.
6. **Shared mutable state is one object with one guard.** `VaultMirror`'s `pending` and `_failures`
   become a single `MutableStateFlow<MirrorState>` updated with `update { }`, which is atomic and
   makes the Mutex unnecessary (I-06); `remove()`'s failure branch clears `pending` like its
   success branch (I-07); `KeystoreSecureSettings._corruptedKeys` uses `update { }` (I-20). A
   long-running consumer that is not a view model — the mirror, the downloader — is cancellable
   and owned; `Graph.scope` never being cancelled is acceptable only because it holds one
   coroutine, and that should be stated where the scope is declared.

Rules 1–4 are the ones that stop data loss. Rules 5–6 stop wrong answers and the ANR.

---

## Findings

| ID | Sev | Where | Claim | Failure scenario | Proposed fix | Verify by |
|---|---|---|---|---|---|---|
| I-01 | Blocker | `EditorViewModel.kt:46,58-71` | `attachTranscript` launches a second save without cancelling the pending autosave | Dictate into a note typed within the last 600 ms: the older `save(A)` fires after the newer `save(B)` and reverts body, transcript and `audioPath`; the mirror copies the loss into the vault | `attachTranscript` assigns `saveJob` and cancels the previous; `save` re-reads `_state.value.note` | Unit test on `StandardTestDispatcher`: `edit(body="a")`, advance 100 ms, `attachTranscript(tx,null)`, `advanceUntilIdle()`, assert `repository.get(id).transcript != null` |
| I-02 | Blocker | `TodayScreen.kt:102-108`, `NotesViewModel.kt:243-254`, `Vault.kt:60-81` | The dictation is committed on the composition's scope, after the audio has already moved | Leave Today between the move and the upsert: the `.wav` is orphaned in the vault with no row; return and the effect re-fires, writing a note whose `audioPath` does not exist | `commit()` on `viewModelScope`; clear `VoiceState.Ready` only after the upsert returns | Instrumented: enter Ready, `navigate(SETTINGS)` inside the IO hop, return, assert exactly one note and `File(note.audioPath).isFile` |
| I-03 | Blocker | `ImmersiveActivity.kt:63-66`, `NoteEditorScreen.kt:67,81`, `EditorViewModel.kt:88-93` | `owners.onDestroy()` clears the ViewModelStore before the composition is disposed, so `onDispose { flush() }` launches into a cancelled scope | Leaving the Space with an unsaved edit loses it every time, not probabilistically | Drop our lifecycle/store owners (Job 1) so disposal and clearing both follow the SDK owner; make `flush` synchronous-until-written via `GlobalScope`-free application scope | `javap` order above; then instrumented: type, `finish()` the activity, reopen, assert the text is present |
| I-04 | Blocker | `WhisperEngine.kt:86-97`, `Graph.kt:71-79,131-149` | `close()` is `runBlocking` on the caller's thread, and every caller of `localEngine()` is on Main, holding `Graph`'s monitor | Switch model or re-run while a transcription is in flight: Main blocks for the rest of it (5-30 s) → ANR kill | `suspend fun close()` with `withContext(dispatcher)`; `localEngine()` suspend behind a `Mutex` | `grep -n runBlocking */src/main` → must be empty; then `adb shell am start` a dictation and switch model, watch for `ANR in ai.passioncode.fabricvr` |
| I-05 | High | `VoiceViewModel.kt:153-169`, `SettingsViewModel.kt:190-215`, `ModelDownloader.kt:118` | Two view models can download the same model into one `.part` concurrently | Start the download from the Today banner, open Settings, start it again: both append to one file, both fail the SHA and delete it; the model can never finish | One download owner (a repository or a `Graph`-level job keyed by model), the second call joins the first | Instrumented with a `MockWebServer`: launch both, assert one HTTP call and one `Done` |
| I-06 | High | `VaultMirror.kt:50,54,61,66` | `_failures` is four unsynchronised read-modify-writes across `Dispatchers.Default` and Main | A retry from Settings erases a failure the mirror recorded microseconds earlier; `vaultOutOfSync` under-reports and a broken note looks mirrored | `_failures.update { }`, or fold `pending` and `failures` into one state object | Unit test: 100 concurrent `write()` failures on `Dispatchers.Default` + `retryFailed()` on another; assert `failures.size == 100` |
| I-07 | High | `VaultMirror.kt:59-69` | `remove()`'s failure branch never clears `pending` | Delete a note whose vault write had failed: the failed remove leaves it pending, and Settings' Retry writes the deleted note back into the person's folder | Clear `pending` in both branches of `remove` | Unit test with a `Vault` whose `remove` fails: delete, `retryFailed()`, assert `vault.write` was not called |
| I-08 | High | `Graph.kt:131-149` | `withEngine` reads `whisperModel()` outside the `@Synchronized` that guards `loaded` | A model change between the check and `localEngine()` runs the re-run on the other model; the transcript still records `whisper-small-q5_1` because `WhisperEngine.name:29` is constant | Read the model inside the lock; derive `Transcript.engine` from `modelStore.modelName` | Unit test with a settings stub that flips the key on its second `get`; assert the engine used matches the requested model |
| I-09 | High | `ChatViewModel.kt:66,72`, `Assistant.kt:14`, `OpenRouterClient.kt:50-68` | A Keystore decrypt, an `OkHttpClient` construction, a 12 KB context render and a JSON encode all run on Main | Every Ask janks; the first Ask of a session janks hardest | `ask` launches on `io`; `stream` gets `.flowOn(Dispatchers.IO)` | `StrictMode.ThreadPolicy` with `detectDiskReads` + `penaltyDeath` in a debug build, then Ask |
| I-10 | High | `VoiceViewModel.kt:66-68`, `AudioRecorder.kt:64,69` | Main takes `bufferLock` while an IO thread holds it copying a boxed `ArrayList<Short>` | A 60 s dictation makes `stopAndTranscribe` wait on a lock held across an `addAll` of ~960 000 boxed shorts | Accumulate into a `ShortArray`-backed growable buffer (E-02's fix closes both) | Benchmark: 60 s of synthetic samples, measure the Main-thread stall at `drainBuffer` |
| I-11 | High | `VoiceViewModel.kt:74-91,97-121,124-128,130-141` | Every Keystore read, the WAV encode and the WAV write happen on `viewModelScope` (Main); the class has no `withContext` at all | Tapping Record does three Keystore decrypts and a disk stat inline; stopping writes ~2 MB on Main | Add `io: CoroutineDispatcher` and wrap | `grep -c withContext VoiceViewModel.kt` must be > 0; `StrictMode` as above |
| I-12 | High | `ImmersiveActivity.kt:53-66`, `SpatialPanelOwners.kt:50-54` | Our lifecycle receives no `ON_STOP`; the SDK's does (`VrActivity.onStop` offset 125) | The Space is stopped but every `collectAsStateWithLifecycle` keeps collecting — Room requeries, and a chat SSE stream stays open on a 10-minute call timeout | Drop the owner (Job 1). If kept, add `onStart`/`onStop` | `adb shell am start -n <other app>`, then `dumpsys` the OkHttp connection / log the Room query count |
| I-13 | High | `VrActivity.dispatchKeyEvent` (0.14.0), `ImmersiveActivity.kt:46`, `FabricApp.kt:43-51` | No key event reaches `Activity.dispatchKeyEvent`; nothing calls `onBackPressed()`; the platform dispatcher is never registered | In the Space, Back does nothing anywhere, `returnToPanel()` is dead code, and Search/Chat/Settings/editor have no exit control | Override `dispatchKeyEvent` in `ImmersiveActivity` to feed the dispatcher on `KEYCODE_BACK`, or render an exit control on every screen | `javap -p -c com/meta/spatial/runtime/VrActivity.class`; then on-headset, press B on each screen |
| I-14 | High | `NotesViewModel.kt:186-187`, `TodayScreen.kt:213` | `retranscribe` runs `Graph.withEngine` and `Graph.sttLanguage()` from `rememberCoroutineScope()`, which is Main | A re-run's Keystore reads and a possible `WhisperEngine` construction land on Main; with I-04 it can also `runBlocking` there | `retranscribe` launches on `viewModelScope` with `withContext(io)`; the screen calls a non-suspend entry point | `StrictMode`; and assert `retranscribe` is not `suspend` in the view model's public API |
| I-15 | Medium | `SpatialPanelOwners.kt:37-45`, `ImmersiveActivity.kt:107-112` | Three of the four provided owners shadow owners `attachLifecycleToRootView` already set | Duplicated, divergent owners: two lifecycles, two stores, two saved-state registries per panel, with ours the less complete | Keep only `OnBackPressedDispatcherOwner`, delegating `lifecycle` to `ComposeFeature.panelViewLifecycleOwner` | The `javap` above; then assert in an instrumented test that `LocalLifecycleOwner.current` inside the panel `=== activity.panelViewLifecycleOwner` |
| I-16 | Medium | `SpatialPanelOwners.kt:27-28` | The doc comment says the saved-state registry is needed for `rememberSaveable` across a panel rebuild | Wrong mechanism recorded as a reason: `rememberSaveable` reads `LocalSaveableStateRegistry`, built upstream from the **view-tree** owner, so our provider does not affect it | Correct the comment when the file is reduced | Read `ProvideAndroidCompositionLocals`; or an instrumented test that sets `rememberSaveable` state and asserts which registry holds it |
| I-17 | Medium | `ImmersiveActivity.kt:63-66` | The owner is destroyed before `super.onDestroy()`, i.e. before the composition is disposed | View models are cleared while their composables are still alive and still reading their state | Destroy after `super.onDestroy()`, or (better) stop owning a lifecycle at all | Log the order of `onCleared` and `onDispose` on exit from the Space |
| I-18 | Medium | `NotesRepository.kt:58-62`, `VaultMirror.kt:46-57` | `changes` is `BufferOverflow.SUSPEND`, so the vault's latency back-pressures `upsert` | 256 queued changes and a slow vault write make the editor's save suspend; the screen shows "Saving…" with no failure to report | Keep SUSPEND (losing a note is worse) but surface the stall: a mirror-lag indicator, or `DROP_OLDEST` with an explicit resync | Unit test with a `Vault.write` that delays 1 s; emit 300 upserts; assert the 300th caller's latency |
| I-19 | Medium | `SearchViewModel.kt:37,40-59,68-72` | `retry()` re-runs `observe()` without cancelling the previous collector | Each retry adds a collector; N of them race to write `_state` and the debounce stops working | Hold a `Job` and cancel it, as `NotesViewModel.kt:101` does | Unit test: `retry()` three times, assert `repository.observeNotes` was collected once |
| I-20 | Medium | `SecureSettings.kt:60,80,92,101` | `_corruptedKeys` is an unguarded read-modify-write, reachable from Main and IO at once | A corrupt key recorded on one thread is erased by a successful `put` on another; Settings then says the key is fine | `_corruptedKeys.update { }` | Unit test: concurrent `get` of a corrupt key and `put` of another; assert the corrupt one is still listed |
| I-21 | Medium | `SecureSettings.kt:109-117,119-139` | `secretKey()` is `@Synchronized` and may generate a StrongBox-backed AES key inside the monitor | First run: an IO thread generates while Main calls `get()` from a click handler and blocks on the monitor for the length of key generation | Generate eagerly off Main at `Graph.init`, or make `settings` a suspend API | `StrictMode` + a cold first launch; or instrument `secretKey()` with timing logs |
| I-22 | Medium | `SettingsViewModel.kt:190-215`, `Graph.kt:57-60` | The downloader is pinned at launch but `modelPresent` is recomputed for the newly chosen model | Changing model mid-download shows "not downloaded" over a running transfer; tapping Download cancels and deletes the old partial with no message | Key the download state by model; refuse or confirm a switch while a transfer is live | Instrumented: start a download, change the model, assert the `.part` for the first model survives |
| I-23 | Medium | `ImmersiveActivity.kt:91`, `AppSystemActivityExtension` offset 53 | Both the SDK's owner and ours are activity-scoped, not panel-scoped, although the SDK accepts a per-panel owner | A second panel would give two `NavHost`s one `SavedStateRegistry` and collide on its key | Pass a per-panel `PanelViewLifecycleOwner` as `attachLifecycleToRootView`'s third argument when a second panel is added | Register a second `composePanel` in a scratch build and observe the duplicate-key `IllegalArgumentException` |
| I-24 | Medium | `Graph.kt:35-49,163-166` | `Graph` is a process-wide `object` reached from method bodies (`NotesViewModel.kt:186`, `VoiceViewModel.kt:157`, `ChatViewModel.kt:52,66,72`) | None of the threading above is testable, which is why none of it was caught; it is the same finding as A/C-03/E-04 seen from the concurrency side | Inject what the body reaches for, as `SettingsViewModel` already does | `grep -n "Graph\." app/src/main/**/ui/*.kt` outside constructor defaults must be empty |
| I-25 | Low | `WhisperEngine.kt:86-97` | A second `close()` dispatches onto a shut-down executor; `ExecutorCoroutineDispatcher` then falls back to the default executor | `freeContext` would run off the whisper thread — precisely the use-after-free the comment says it prevents | Guard with the existing `closed` flag before the `runBlocking` | Unit test: `close(); close()`; assert `freeContext` is called once and on the `whisper` thread |
| I-26 | Low | `WhisperEngine.kt:34-35,39-43` | `closed` is set before the free, so a queued transcription returns `SttFailed("engine closed")` | A dictation in flight during a model switch reports an engine failure rather than "retrying on the new model" | Map that state to a retryable `AppError` and re-dispatch | Unit test asserting the `AppError` variant |
| I-27 | Low | `ImmersiveActivity.kt:171-173` | `composedAtLeastOnce` is a process-wide mutable static, reset only by the test | A second entry into the Space in the same process reads a stale `true` | Reset it in `onCreate`, or replace it with a `CompletableDeferred` the test awaits | Run `ImmersiveLaunchTest` twice in one instrumentation run |
| I-28 | Low | `ModelDownloader.kt:40`, `OpenRouterClient.kt:26`, `RemoteWhisperClient.kt:29`, `CloudTranscriptionClient.kt:40` | Four `OkHttpClient`s, three of them constructed per call site as default arguments | Each `Graph.sttEngine()` — on Main, per dictation — allocates a dispatcher and a connection pool that are discarded | One shared client in `Graph`, with per-use timeouts via `newBuilder()` | `grep -c "OkHttpClient.Builder()" */src/main` should be 1 |
| I-29 | Low | `Graph.kt:39,165` | `Graph.scope` is never cancelled and carries exactly one coroutine, which is not stated | Any future `Graph.scope.launch` inherits an unbounded, uncancellable lifetime | Document the invariant at the declaration, or name the scope for what it holds | Read; `grep -n "Graph.scope" */src/main` |

**Counts: 4 Blocker, 10 High, 10 Medium, 5 Low — 29 findings.**

---

## Not covered

- **The Spatial SDK's render thread.** `onSceneReady`/`onSceneTick` and `Entity.create` are called
  from it (`ImmersiveActivity.kt:127-143`); this axis did not establish which SDK calls are
  thread-confined to it, and whether `scene.*` may be touched from Main. It needs the same
  disassembly treatment as `dispatchKeyEvent` before anyone moves that code.
- **Room's internal threading under FTS.** `NoteFts` triggers run inside the DAO's transaction; no
  attempt was made to establish whether `observeTagBlobs`'s per-row string splitting
  (`NotesRepository.kt:70-78`) runs on Room's executor or on the collector's.
- **`Dispatchers.IO`'s 64-thread cap against the headset's core count.** `WhisperEngine.defaultThreads()`
  reserves two cores (`:101`), but nothing bounds the IO pool, and a download plus a transcription
  plus a mirror write compete for the same cores as the compositor. Unmeasured.
- **Whether any of this is observable on the headset.** Every ANR claim here is derived from code,
  not from a trace. I-04 in particular deserves a `systrace` before it is believed at Blocker.
- **`PanelDisplayBase.dispatchEvent`'s threading** — B-01 settled *what* it does with the IME; this
  axis did not establish which thread calls it, which decides whether an IME-dismiss workaround can
  be written at all.

## Notes for the merge

1. **The `SpatialPanelOwners` disagreement is settled in axis B's favour, and the fix is not the
   obvious one.** Three owners go, but the fourth cannot simply stay: `OnBackPressedDispatcherOwner`
   extends `LifecycleOwner`, so the reduced class must delegate `lifecycle` to the SDK's owner
   rather than keep a registry of its own. Merge rows B and C into one task with that shape, and
   attach I-13 to it — reducing the file without feeding the dispatcher leaves a fallback that
   still cannot fire.
2. **E-01 should be upgraded from "a real risk, ≤600 ms" to a Blocker with a deterministic
   mechanism.** In the immersive host the activity's destroy order cancels the view-model scope
   before `onDispose` runs, so `flush()` is a no-op on every exit from the Space, not a race
   (I-03). The fix lands with the owners change, not beside it.
3. **A-03's shape is confirmed but its mechanism is not a race.** *Retry* deleting the recording is
   a wiring defect (`onDismissFailure` → `cancel()`); the states cannot overlap, so no lock will
   help. The reachable data-loss race in the same area is I-02, and it needs its own row.
4. **One new Blocker no axis found: I-01.** The editor's autosave reverts a dictation attached
   inside its 600 ms window. It is four lines to fix and it destroys exactly the thing the product
   exists to keep.
5. **The threading defects cluster where `Graph` is reached from a method body** — the same seam A,
   C-03 and E-04 flagged for testability. I-11, I-14, I-09 and I-08 are all instances. Whoever
   takes the injection task should take these with it; fixing them separately means touching the
   same eleven call sites twice.

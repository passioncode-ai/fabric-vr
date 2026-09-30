package ai.passioncode.fabricvr

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * The **one** Compose host owner the Meta Spatial SDK does not supply.
 *
 * Disassembled from `meta-spatial-sdk-compose-0.14.0.aar` on 2026-09-21, because this is a claim
 * about somebody else's code and recalling it is not evidence:
 *
 * - `ComposeFeature` holds one `PanelViewLifecycleOwner`, and that class
 *   `implements LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner`;
 * - `PanelRegistrationExtensionKt` — where `composePanel` lives — calls `attachLifecycleToRootView`
 *   after installing `ViewCompositionStrategy$DisposeOnViewTreeLifecycleDestroyed`;
 * - that function calls `ViewTreeLifecycleOwner.set`, `ViewTreeViewModelStoreOwner.set` **and**
 *   `ViewTreeSavedStateRegistryOwner.set`.
 *
 * Compose resolves those three from the view tree, so this app supplied three owners that
 * **shadowed working ones** — and the shadows were strictly worse. `PanelViewLifecycleOwner` has
 * `onStart`/`onStop`; the hand-rolled registry had neither, so every
 * `collectAsStateWithLifecycle` in the Space kept collecting behind a stopped activity (`I-12`).
 * Its store was cleared by `onDestroy()` **before** the SDK disposed the composition, so the last
 * write on the way out fired into a dead scope every single time (`I-03`), and a `Popup` or
 * `AndroidView` subtree resolved a different store from its own parent (`B-16`).
 *
 * `OnBackPressedDispatcherOwner` is the genuine gap: five classes in the compose module, not one
 * mentions `OnBackPressedDispatcher`, and navigation-compose's `PredictiveBackHandler` throws
 * `No OnBackPressedDispatcherOwner was provided via LocalOnBackPressedDispatcherOwner` without
 * one. That crash took the activity down on every entry into the Space on 2026-09-19.
 *
 * **The delegated `lifecycle` is a requirement, not a convenience.**
 * `OnBackPressedDispatcher.addCallback(owner, callback)` uses that lifecycle to *unregister* the
 * callback. A registry nobody drives to `DESTROYED` never unregisters anything, so every
 * `BackHandler` the app ever composed would stay in the dispatcher's list for the life of the
 * process.
 */
class SpatialBackOwner(
    private val host: LifecycleOwner,
    onUnhandledBack: () -> Unit,
) : OnBackPressedDispatcherOwner {

    override val lifecycle: Lifecycle get() = host.lifecycle

    /**
     * **Fed by exactly one thing: `ImmersiveActivity.dispatchKeyEvent`.**
     *
     * From `DEC-0018` until `T-013` measured it, this fallback was believed unreachable and the
     * spec was written to delete it: `VrActivity.dispatchKeyEvent` (0.14.0, 65 bytes) never calls
     * `super`, so nothing downstream of the window — `onKeyUp`, `onBackPressed`, the platform's own
     * `OnBackInvokedDispatcher` — can ever see a key. All of that is still true. What was **not**
     * true is the conclusion drawn from it: `dispatchKeyEvent` is itself an override point, and
     * `SpaceBackTest` watched a `KEYCODE_BACK` arrive there on a Quest 3 on 2026-09-21. `DEC-0029`
     * records the measurement that overturned the assumption.
     *
     * So the override is the whole back path, and this lambda is its end: when nothing in the
     * composition consumed the press, the Space is left.
     */
    override val onBackPressedDispatcher: OnBackPressedDispatcher =
        OnBackPressedDispatcher { onUnhandledBack() }
}

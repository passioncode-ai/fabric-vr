package ai.passioncode.fabricvr

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import ai.passioncode.fabricvr.ui.FabricApp
import ai.passioncode.fabricvr.common.theme.Tokens
import ai.passioncode.fabricvr.common.Log2
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.composePanel
import com.meta.spatial.compose.panelViewLifecycleOwner
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Hand
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.vr.VRFeature
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The same notes, in the room. This is the hybrid half of the app: the panel activity is what lives
 * beside a streamed desktop, and this one takes over when the person asks for space.
 *
 * It is a plain `android.app.Activity` underneath (`AppSystemActivity` → `VrActivity` → `Activity`),
 * which is why the microphone request goes through `requestPermissions`/`onRequestPermissionsResult`
 * rather than the androidx result API.
 */
class ImmersiveActivity : AppSystemActivity() {

    private var pending: ((Boolean, Boolean) -> Unit)? = null

    /**
     * Back for the Space, held by the activity rather than built in the composition, because
     * [dispatchKeyEvent] is the only thing that feeds it and an activity cannot reach into a
     * composition to find one.
     *
     * Its lifecycle is still the SDK's `PanelViewLifecycleOwner` — the same object the composition
     * used to resolve through `LocalLifecycleOwner` — for the reason `SpatialBackOwner` documents:
     * the dispatcher uses that lifecycle to *unregister* callbacks, and a registry nobody drives to
     * `DESTROYED` leaks every `BackHandler` the app ever composed. `by lazy`, because the property
     * is an extension that `ComposeFeature` backs and it is first touched from inside the panel.
     */
    private val backOwner: SpatialBackOwner by lazy {
        SpatialBackOwner(panelViewLifecycleOwner) { returnToPanel() }
    }

    private val requester = object : PermissionRequester {
        override fun requestRecordAudio(
            onResult: (Boolean, Boolean) -> Unit,
            onDismissed: () -> Unit,
        ) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onResult(true, false)
            } else {
                pending = onResult
                pendingDismissal = onDismissed
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
            }
        }

        // The Space is an exclusive surface: the system page opens over the shell, so the person
        // comes back to the panel rather than to the Space. That is the platform's behaviour and
        // not something this app can improve on — saying nothing about it would be worse.
        override fun openAppSettings(): Boolean = startAppSettings()
    }

    /**
     * **An empty `grantResults` is a dismissal, not a refusal** (`REQ-048`, audit `H10`).
     *
     * AOSP delivers an empty array when the dialog goes away without an answer — Back, the shell
     * taking the foreground, or a second `requestPermissions` made while one was already
     * showing. This method used to read that as "not granted", and
     * `shouldShowRequestPermissionRationale` is false while a dialog is up, so it reported
     * **permanently denied** — the one state this app cannot recover from by itself — and
     * cleared `pending` in the same breath, so the person's real answer reached nobody.
     *
     * `pending` is deliberately **kept** here: the request that is still on screen will answer
     * this callback again. What the view model is told is only that the question is no longer in
     * flight, so the next press may ask.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MICROPHONE) return
        if (grantResults.isEmpty()) {
            pendingDismissal?.invoke()
            return
        }
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        val permanent = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        pending?.invoke(granted, permanent)
        pending = null
        pendingDismissal = null
    }

    /** Told when a dialog went away unanswered, so the guard in `VoiceViewModel` lifts. */
    private var pendingDismissal: (() -> Unit)? = null

    /** `REQ-054`, this host's half: a plain Activity, so the result comes back the old way. */
    private var pendingArchive: ((android.net.Uri?) -> Unit)? = null

    private val picker = object : ArchivePicker {
        override fun pickArchive(onChosen: (android.net.Uri?) -> Unit) {
            pendingArchive = onChosen
            runCatching { startActivityForResult(openArchiveIntent(), REQUEST_ARCHIVE) }
                .onFailure {
                    pendingArchive = null
                    onChosen(null)
                }
        }
    }

    @Deprecated("The only result API a non-ComponentActivity has; see ArchivePicker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ARCHIVE) return
        pendingArchive?.invoke(data?.data?.takeIf { resultCode == RESULT_OK })
        pendingArchive = null
    }

    /**
     * **The back path for the Space — there is no other, and until this override there was none.**
     *
     * `VrActivity.dispatchKeyEvent` (0.14.0, 65 bytes, disassembled) routes the event to a pinned
     * game controller and returns `true`, or returns `false`. It never calls `super`, so nothing
     * below it ever runs: not `onKeyUp`, not `onBackPressed`, not the platform's own
     * `OnBackInvokedDispatcher`. The dispatcher `DEC-0018` gave the composition was fed by nobody
     * for two days, which is `I-13`/`B-15`.
     *
     * `T-013` was written to delete that dispatcher on the assumption that no key reaches an
     * immersive activity at all — an assumption `adb shell input keyevent` appeared to confirm,
     * because an injected key goes to the focused window and the shell's injection was not landing
     * here. **`SpaceBackTest` measured it properly on a Quest 3 on 2026-09-21: a `KEYCODE_BACK`
     * sent to this activity reaches this method, down and up.** `DEC-0029` is that reversal.
     *
     * Delegating everything else to `super` preserves the SDK's pinned-game-controller branch,
     * which is the only thing it does; returning `true` unconditionally would swallow the volume
     * keys. Whether a **physical** controller's B button becomes a `KEYCODE_BACK` in an immersive
     * session is a separate question that needs a person wearing the headset — it is a row in
     * `docs/evidence/device-gate.md`, not a claim here.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keyEventsSeen.incrementAndGet()
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_UP &&
            event.repeatCount == 0 &&
            // A long press delivers repeated DOWNs and then an UP carrying FLAG_CANCELED with
            // repeatCount 0 — so without this a long press the system had already handled left
            // the Space anyway. `!isCanceled` is Android's own convention for this override.
            !event.isCanceled
        ) {
            Log2.i("space.back", "consumed" to backOwner.onBackPressedDispatcher.hasEnabledCallbacks())
            // `onBackPressed()` walks the composition's own callbacks first and falls through to
            // `returnToPanel()`. Nothing calls `super.onBackPressed()` here on purpose:
            // `returnToPanel()` already finishes, and a second finish after the PendingIntent has
            // been sent is the redundant-finish shape `C-02` names.
            backOwner.onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun registerFeatures(): List<SpatialFeature> =
        mutableListOf(VRFeature(this), ComposeFeature())

    override fun registerPanels(): List<PanelRegistration> = listOf(
        PanelRegistration(PANEL_ID) {
            config { applyPanelConfig(this) }
            composePanel {
                setContent {
                    // Only the owner the SDK does not supply. The other three arrive through the
                    // view tree from `PanelViewLifecycleOwner`, which `attachLifecycleToRootView`
                    // installs — and which, unlike anything this app could hand-drive, is stopped
                    // and destroyed by the activity that owns it. See [SpatialBackOwner].
                    CompositionLocalProvider(
                        LocalOnBackPressedDispatcherOwner provides backOwner,
                    ) {
                        // From inside the composition, not from onCreate: the crash this records
                        // the absence of happened while composing, and a marker set before it
                        // would have said "fine" about a panel that never drew. `SideEffect` so it
                        // counts successful compositions — a bare statement also runs on every
                        // composition that is later abandoned, which matters now the number is
                        // read rather than a flag.
                        SideEffect { compositionCount.incrementAndGet() }
                        FabricApp(
                            permissionRequester = requester,
                            archivePicker = picker,
                            onLeaveSpace = { returnToPanel() },
                            // The room shows through here and only here (`DEC-0085`). Everything
                            // else about this panel already asked for it; this is the line that
                            // stopped painting over the answer.
                            background = Tokens.Palette.inkSpace,
                        )
                    }
                }
            }
        },
    )

    /**
     * **The only haptics this app can have** (`REQ-062`, audit `M22`).
     *
     * Meta documents one Kotlin API for it — `spatial.applyHapticFeedback(hand, amplitude,
     * durationNs, frequency)` on an `AppSystemActivity`, *Inputs and controllers*, fetched
     * 2026-09-21 — so it exists here and nowhere else in this app. Whether
     * `android.os.Vibrator` reaches a Touch controller from the 2D panel is UNVERIFIED and is
     * not guessed at: the panel attaches nothing and gets the audio cue alone.
     *
     * **Both hands**, because nothing here knows which one is holding the ray at the moment a
     * dictation ends, and a pulse in the hand the person is not using is a pulse they miss.
     * The cap is the longer, lower one: it is the app interrupting somebody who is still
     * speaking (`DEC-0032`), and it must not feel like the acknowledgement of their own press.
     *
     * Amplitude, duration and frequency are Meta's parameters and these values are a first
     * choice, not a measurement — no headset has felt them (`B-183`).
     */
    private val spaceHaptics = CueChannel { cue ->
        val (amplitude, millis, frequency) = when (cue) {
            Cue.RECORD_START -> Triple(0.6f, 40L, 180f)
            Cue.RECORD_STOP -> Triple(0.6f, 40L, 140f)
            Cue.AUTO_STOP -> Triple(0.9f, 120L, 90f)
            Cue.SAVED -> Triple(0.4f, 30L, 200f)
        }
        Hand.entries.forEach { hand ->
            spatial.applyHapticFeedback(hand, amplitude, millis * 1_000_000L, frequency)
        }
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.enablePassthrough(true)
        // Attached once the scene exists, because `spatial` is what answers the call and there
        // is no scene before this. Detached in `onDestroy`, so a cue raised after the Space has
        // gone reaches the audio channel alone rather than a dead activity.
        Graph.feedbackCues.attachHaptics(spaceHaptics)

        // Audio focus (`B-226`) is NOT attached here any more: `Graph.init` attaches it once for
        // the whole process (F4 of the 2026-10-03 lifecycle audit). Attached from this scene
        // alone, a dictation on the panel ducked nothing until the Space had been opened once.

        // registerPanels() only says HOW to build the panel. Something has to create an entity that
        // carries it, or the Space opens onto an empty room — the SDK sample gets this from an
        // exported scene file, which this app deliberately does not use.
        //
        // **The reference is kept** since `REQ-053`: Meta's own troubleshooting says a panel's
        // resources — mesh, layer, texture, Android surface — must be released with
        // `panelEntity.destroy()` before the activity ends, and an entity nobody holds cannot be
        // destroyed. It was discarded here until the audit of 2026-09-21 (H12).
        panelEntity = Entity.create(
            listOf(
                Panel(PANEL_ID),
                Transform(placePanel(scene.getViewerPose())),
                // If the placement is still wrong for this person's height or seat, they can move it.
                Grabbable(),
            ),
        )
    }

    /**
     * The haptic channel goes with the scene that owns it (`REQ-062`).
     *
     * Guarded by identity: both surfaces can be alive at once, so a Space that is going away
     * must not detach a channel a newer one has just attached.
     */
    override fun onDestroy() {
        Graph.feedbackCues.detachHaptics(spaceHaptics)
        // **`B-226`: the `ToneGenerator`'s `AudioTrack` goes back here**, and it is safe to do so
        // while the panel host is still alive — the channel allocates again on the next cue. That
        // property is what makes this callable from either surface without the two having to
        // agree about which of them is last, and it is why this is not guarded by identity the
        // way the haptic channel above is.
        Graph.feedbackCues.releaseAudio()
        super.onDestroy()
    }

    /**
     * Back to the shell: the panel's resources are released, then Home is handed a PendingIntent,
     * which is the documented hybrid path.
     *
     * The order and the idempotence live in [SpaceExit], where a test can read them without a
     * headset; this method is the three platform calls it drives.
     */
    private fun returnToPanel() = spaceExit.leave()

    private val spaceExit by lazy {
        SpaceExit(
            destroyPanel = {
                panelEntity?.destroy()
                panelEntity = null
            },
            launchHome = {
                val pending = PendingIntent.getActivity(
                    applicationContext,
                    0,
                    Intent(applicationContext, PanelActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("extra_launch_in_home_pending_intent", pending),
                )
            },
            finishActivity = { finish() },
        )
    }

    /** The entity carrying the panel, held so it can be destroyed before this activity ends. */
    private var panelEntity: Entity? = null

    companion object {
        /**
         * How many times the panel's Compose tree has run. A **test handshake, and production
         * reads it nowhere** — which is a cost, not a free lunch: a field that exists only for a
         * test is production code that ships, and E said so about the boolean this replaces.
         *
         * It is a count rather than a flag because the flag needed a reset protocol and the reset
         * is what fails silently: a process that composed once answers `true` for ever, so whoever
         * forgets to clear it gets a green test over a Space that never opened. A test that
         * snapshots this and waits for an **increase** is asking about its own launch. Only ever
         * incremented, so there is no protocol left to get wrong.
         */
        val compositionCount = java.util.concurrent.atomic.AtomicLong(0)

        /**
         * How many key events have reached this activity. It exists because the claim that none
         * ever did was the premise `T-013` was written on, and it was wrong — a number a test can
         * read is what corrected it. `SpaceBackTest` snapshots this and waits for an increase.
         * Never reset, for [compositionCount]'s reason.
         */
        val keyEventsSeen = java.util.concurrent.atomic.AtomicLong(0)

        /**
         * The panel's texture and its size in the room, in one place a test can read back.
         *
         * **Measured on a Quest 3 (Horizon OS 207) on 2026-09-21**, because the defect here is a
         * number nobody had looked at: `PanelConfigOptions()` defaults to `width = 1.0` m and
         * `height = 0.75` m — an aspect of 1.333 — while this app asked for a 1024×640 dp texture,
         * an aspect of 1.6. Nothing set `width` or `height`, so the 1.6 texture was drawn onto a
         * 1.333 quad and **stretched vertically by 20%** (`B-08`). It is not subtle and nobody had
         * noticed, because no test could read the configuration and no screenshot was taken.
         *
         * A function rather than a literal block so a test can construct a `PanelConfigOptions`,
         * hand it here, and read the result. The constants stayed `private const val` before, and
         * Kotlin **inlines** those at the call site — so a test could not have read them even if
         * it had tried.
         */
        internal fun applyPanelConfig(options: PanelConfigOptions) = with(options) {
            // Transparent, frameless: the panel floats over passthrough, and the default glass
            // would put an opaque plate between the person and their own room.
            themeResourceId = R.style.Theme_FabricVR_Transparent
            layoutWidthInDp = PANEL_WIDTH_DP
            layoutHeightInDp = PANEL_HEIGHT_DP
            layoutDpi = PANEL_DPI
            width = PANEL_WIDTH_M
            // Computed, never a second literal: two numbers that must agree and are typed
            // separately are two numbers that will stop agreeing.
            height = PANEL_WIDTH_M * PANEL_HEIGHT_DP / PANEL_WIDTH_DP
            enableTransparent = true
            includeGlass = false
        }

        private const val PANEL_WIDTH_DP = 1024f
        private const val PANEL_HEIGHT_DP = 640f

        /** The SDK's own `DEFAULT_DPI` on this device, measured rather than chosen. */
        private const val PANEL_DPI = 288

        /**
         * One metre across at [DISTANCE_M]. That subtends about 41 degrees, and the 1843 px the
         * texture is wide across it is ~44 pixels per degree — comfortably inside the 2064×2208
         * eyebuffer this device reports.
         */
        private const val PANEL_WIDTH_M = 1.0f

        private const val PANEL_ID = 1
        private const val REQUEST_MICROPHONE = 4711

        /** `REQ-054`. Distinct from [REQUEST_MICROPHONE] and from nothing else in this activity. */
        private const val REQUEST_ARCHIVE = 4712

        /** How far in front of the wearer the panel sits, and how far below eye level. */
        private const val DISTANCE_M = 1.3f
        private const val DROP_M = 0.15f

        /**
         * Places the panel in front of wherever the person is actually looking, turned to face them.
         *
         * A fixed world position was the previous shape and it is a guess twice over: it assumes the
         * wearer stands at the origin, and it assumes a sign convention for "forward" that this
         * project never verified. Deriving both from the viewer's own pose removes both assumptions.
         */
        fun placePanel(viewer: Pose): Pose {
            val forward = viewer.forward()
            val flatLength = sqrt(forward.x * forward.x + forward.z * forward.z)
            val flat = if (flatLength < 1e-4f) Vector3(0f, 0f, 1f) else
                Vector3(forward.x / flatLength, 0f, forward.z / flatLength)
            val position = Vector3(
                viewer.t.x + flat.x * DISTANCE_M,
                viewer.t.y - DROP_M,
                viewer.t.z + flat.z * DISTANCE_M,
            )
            // Face the viewer: yaw the panel back along the direction it was placed.
            val yawDegrees = Math.toDegrees(atan2(flat.x.toDouble(), flat.z.toDouble())).toFloat()
            return Pose(position, Quaternion(0f, yawDegrees, 0f))
        }

        fun intent(from: android.content.Context): Intent =
            Intent(from, ImmersiveActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

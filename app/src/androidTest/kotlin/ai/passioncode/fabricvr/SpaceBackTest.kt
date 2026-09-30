package ai.passioncode.fabricvr

import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Back does in the Space, asserted rather than assumed.
 *
 * `T-013` was written with two possible endings and one observation to choose between them, and the
 * observation nearly went the wrong way. `adb shell input keyevent 4` against a live Space produced
 * no log line at all, which reads as "no key ever reaches an immersive activity" — and on that
 * reading the task would have deleted the dispatcher and shipped a documented absence. The shell's
 * injection simply was not landing on this window; it brought `PanelActivity` forward instead.
 *
 * This test injects through the instrumentation, which does land, and it watched a `KEYCODE_BACK`
 * arrive at `ImmersiveActivity.dispatchKeyEvent` on a Quest 3 on 2026-09-21. That is `DEC-0029`,
 * and it is the reason the Space has a Back at all.
 */
@RunWith(AndroidJUnit4::class)
class SpaceBackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    /**
     * **The measurement this task turned on, kept as a test so it cannot quietly stop being true.**
     *
     * Two claims, and they fail differently on purpose. If the count does not move, the platform
     * stopped delivering keys here and the Space has silently lost its only exit key — the app is
     * not broken, its assumption is. If the count moves and the activity is still resumed, the
     * override ran and did not leave: that is ours.
     *
     * Do not "fix" the first failure by loosening it. It is the thing that was got wrong once
     * already, in the other direction.
     */
    @Test
    fun backLeavesTheSpace() {
        val keysBefore = ImmersiveActivity.keyEventsSeen.get()
        context.startActivity(ImmersiveActivity.intent(context))
        assumeTrue(
            "the shell never brought the Space forward — Guardian, no controllers, or nobody " +
                "wearing the headset. Nothing is claimed about the app by this run.",
            awaitResumedImmersiveActivity(),
        )

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()

        assertNotEquals(
            "no key event reached ImmersiveActivity.dispatchKeyEvent. On 2026-09-21 a KEYCODE_BACK " +
                "did, which is the whole premise of DEC-0029 — if that has changed, the Space's " +
                "only exit key is gone and the decision must be re-made, not the test relaxed.",
            keysBefore,
            ImmersiveActivity.keyEventsSeen.get(),
        )
        assertTrue(
            "Back reached the activity and the Space did not close. returnToPanel() is the " +
                "dispatcher's fallback; either something in the composition consumed the press, or " +
                "dispatchKeyEvent stopped calling onBackPressed().",
            awaitImmersiveActivityGone(),
        )
    }

    /** The Space is left by `finish()`, so RESUMED going away is the observable half of it. */
    private fun awaitImmersiveActivityGone(): Boolean {
        val deadline = System.currentTimeMillis() + LAUNCH_MS
        while (System.currentTimeMillis() < deadline) {
            var present = true
            instrumentation.runOnMainSync {
                present = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { it is ImmersiveActivity }
            }
            if (!present) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun awaitResumedImmersiveActivity(): Boolean {
        val deadline = System.currentTimeMillis() + LAUNCH_MS
        while (System.currentTimeMillis() < deadline) {
            var found = false
            // `getActivitiesInStage` calls `checkMainThread()`, and a test body is not it.
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { it is ImmersiveActivity }
            }
            if (found) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private companion object {
        const val LAUNCH_MS = 12_000L
        const val POLL_MS = 250L
    }
}

/**
 * The exit, which is what the person has when a key is not enough.
 *
 * Hosted by the test manifest's bare `ComponentActivity`, not by either real one: this asks about
 * `FabricApp`'s own controls, and both surfaces set their content before a rule can. `PanelActivity`
 * refuses `setContent` outright (`has already set content`), and the immersive panel lives in a 3D
 * scene as a texture no compose rule can drive.
 */
@RunWith(AndroidJUnit4::class)
class SpaceExitControlTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Every screen offers a way out, and the two that lead in opposite directions do not look the
     * same. Today carries *Leave the Space*; the other three carry their own *Back*, which reaches
     * Today, where the exit is — two taps, not zero. That was already true before `T-013` and the
     * task says so in writing: a test green before the change is evidence the finding overstated
     * the defect, and that belongs on the record rather than in a silent pass.
     */
    @Test
    fun everyScreenInTheSpaceOffersAWayOut() {
        val activity = compose.activity
        val leave = activity.getString(R.string.action_leave_space)
        val back = activity.getString(R.string.action_back)

        compose.setContent {
            ai.passioncode.fabricvr.ui.FabricApp(
                permissionRequester = NoopPermissionRequester,
                onEnterSpace = {},
                onLeaveSpace = {},
            )
        }

        compose.onAllNodesWithContentDescription(leave).onFirst().assertHasClickAction()

        // Search, then Settings: reached from Today's own header, each with its own Back.
        compose.onAllNodesWithContentDescription(activity.getString(R.string.action_search))
            .onFirst().performClick()
        compose.onAllNodesWithText(back).onFirst().assertHasClickAction()
        compose.onAllNodesWithText(back).onFirst().performClick()

        compose.onAllNodesWithContentDescription(activity.getString(R.string.label_settings))
            .onFirst().performClick()
        compose.onAllNodesWithText(back).onFirst().assertHasClickAction()
    }

    /** `B-25`: the control that leaves and the control that enters shared a glyph and a word. */
    @Test
    fun theLeaveControlAndTheEnterControlAreDistinguishable() {
        val activity = compose.activity
        compose.setContent {
            ai.passioncode.fabricvr.ui.FabricApp(
                permissionRequester = NoopPermissionRequester,
                onEnterSpace = {},
                onLeaveSpace = {},
            )
        }

        assertNotEquals(
            "the enter and leave controls carry the same label, so neither can be told from its " +
                "opposite by anyone using the app or by any accessibility service reading it",
            activity.getString(R.string.action_space),
            activity.getString(R.string.action_leave_space),
        )
        compose.onAllNodesWithContentDescription(activity.getString(R.string.action_space))
            .onFirst().assertHasClickAction()
        compose.onAllNodesWithContentDescription(activity.getString(R.string.action_leave_space))
            .onFirst().assertHasClickAction()
    }

    /**
     * **This object stopped compiling when `REQ-048` gave `requestRecordAudio` its `onDismissed`
     * parameter, and nothing said so for a whole group of work** — `check-all.sh` compiles the
     * JVM suite and the native bridge, never `androidTest`, and CI cannot run instrumented tests
     * at all. A suite that does not compile is a suite that cannot be run on the headset session
     * `DEC-0066` schedules, so the break would have been found by the person wearing the headset.
     * `check-all.sh` compiles this source set now.
     */
    private object NoopPermissionRequester : PermissionRequester {
        override fun requestRecordAudio(
            onResult: (Boolean, Boolean) -> Unit,
            onDismissed: () -> Unit,
        ) = onResult(false, false)

        override fun openAppSettings(): Boolean = false
    }
}

package ai.passioncode.fabricvr

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Entering the Space must not take the app down, and the Space must actually open.
 *
 * This is the only shape of test that can say so. The panel's composition is hosted by
 * `AppSystemActivity`, a plain `android.app.Activity`, so a compose test cannot reproduce its
 * conditions — the test rule's host is a `ComponentActivity` and supplies from its view tree the
 * very owners whose absence crashed the product. A test that merely omitted an owner passed while
 * the product crashed; that was watched happening on 2026-09-19.
 *
 * **What this file used to claim and could not.** It filtered `getHistoricalProcessExitReasons`
 * for a crash "since the test started". Instrumentation runs in the app's own process: if the app
 * dies, the test run dies with it and reports nothing, and a live process has no exit record. The
 * filter could only ever be empty — a green that meant "this code ran", not "nothing crashed". It
 * also asked for `RunningAppProcessInfo.REASON_UNKNOWN` where it meant `ApplicationExitInfo`'s;
 * the two happen to share the value 0, so it worked by accident and read as a different check.
 *
 * So the exit reasons moved to where they can say something: a **tripwire before the run**, about
 * the run before this one. The crash of *this* run is reported by the runner not finishing.
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveLaunchTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    /**
     * Reports a crash from a **previous** run, once.
     *
     * Android keeps up to sixteen exit records and they survive a reboot, so without a high-water
     * mark one crash fails this class for ever — including immediately after somebody plants one
     * on purpose to prove the tripwire works. The mark is written **before** the assertion, so a
     * failing run does not re-report the same death on the next attempt.
     */
    @Before
    fun theLastRunDidNotCrash() {
        val prefs = context.getSharedPreferences("fabricvr_launch_test", Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN_UNTIL, 0L)
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val fatal = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
            .filter { it.timestamp > seen && it.reason in FATAL }
        val newest = fatal.maxOfOrNull { it.timestamp } ?: 0L
        if (newest > seen) prefs.edit().putLong(SEEN_UNTIL, newest).commit()

        assertTrue(
            "a previous run of this app died: " +
                fatal.joinToString { "${it.reason}/${it.status} ${it.description.orEmpty()}" },
            fatal.isEmpty(),
        )
    }

    @Test
    fun theSpaceOpensAndItsPanelComposes() {
        val before = ImmersiveActivity.compositionCount.get()

        context.startActivity(ImmersiveActivity.intent(context))

        val resumed = awaitResumedImmersiveActivity()
        // An assumption, not a failure, and the distinction is the whole point of the test. The
        // shell defers an immersive launch behind a Guardian prompt, with no controllers paired,
        // or with the headset off a head — none of which says anything about this app. An
        // activity that reached RESUMED and then did not compose is ours.
        assumeTrue(
            "the shell never brought the Space forward — Guardian, no controllers, or nobody " +
                "wearing the headset. Nothing is claimed about the app by this run.",
            resumed,
        )

        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (ImmersiveActivity.compositionCount.get() == before &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(POLL_MS)
        }

        assertTrue(
            "the Space was resumed and its panel never composed — the composition threw. " +
                "Until DEC-0028 that was `No OnBackPressedDispatcherOwner was provided via " +
                "LocalOnBackPressedDispatcherOwner`, on every entry. Check logcat.",
            ImmersiveActivity.compositionCount.get() > before,
        )
    }

    /**
     * `ActivityLifecycleMonitorImpl.getActivitiesInStage` calls `checkMainThread()`, and an
     * `@Test` body is not the main thread — asking it directly throws `IllegalStateException`,
     * which reads as neither a pass nor a failure.
     */
    private fun awaitResumedImmersiveActivity(): Boolean {
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            var found = false
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
        const val SETTLE_MS = 12_000L
        const val MAX_RECORDS = 16
        const val POLL_MS = 250L
        const val SEEN_UNTIL = "exit_seen_until"

        /** Deaths worth reporting. A normal `finish()` is `REASON_USER_REQUESTED` and is not one. */
        val FATAL = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
        )
    }
}

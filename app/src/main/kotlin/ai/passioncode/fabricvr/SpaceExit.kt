package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.Log2
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The order in which an immersive activity is allowed to go away, as a thing a test can read.
 *
 * Meta documents this twice and the app obeyed neither. *Known issues*
 * (`spatial-sdk-known-issues`, read 2026-04-02 revision on 2026-09-21): "calling `finish()` on an
 * activity with panels … can cause a crash (SIGSEGV) inside `libMetaSpatialSDK.so` … Use
 * `panelEntity.destroy()` to properly clean up panel resources **before ending the activity**."
 * *Spawn and remove 2D panels*: "Never use the `finish()` method to terminate an Activity
 * associated with a panel. This causes crashes because memory references remain active for the
 * panel's resources: the 3D mesh, layer, texture, and Android surface."
 *
 * **Why `finish()` is still called at the end, deliberately.** Meta's own `HybridSample` calls it
 * (`HybridSampleActivity.kt`, `main` at 0.14.0) right after the Home intent, and the first bullet
 * above permits *ending the activity* once the panels are gone — what it forbids is ending it
 * with them alive. Dropping the call entirely is the other readable interpretation, and it was
 * rejected here for a reason nothing in the docs covers: `ImmersiveActivity` is `singleTask`, so a
 * second entry into the Space would reuse a live instance and **not** run `onSceneReady` again —
 * the panel entity destroyed on the way out would never be recreated, and the Space would open
 * empty. Which of the two shapes Horizon OS actually prefers is a device question, and it is on
 * the board rather than settled here (`REQ-053`, `DEC-0066` defers it to the headset session).
 *
 * **Why this is a class and not three lines in the activity.** Two things had to be true at once:
 * the order must be checkable without a headset, and a second press must do nothing. A ray can
 * press a 72 dp control twice while the shell switches environments, and the old code would have
 * destroyed a destroyed entity and sent a second Home intent — on a device that is the crash
 * above, not a double transition.
 *
 * **Every step is best-effort except the order, and until `B-217` only the first step was.** The
 * sentence above this one was in the file while `launchHome()` and `finishActivity()` sat outside
 * any guard: `startActivity` with `CATEGORY_HOME` raises `ActivityNotFoundException` where nothing
 * resolves it — a real shape on a stripped or mid-update image — and a throw there skipped
 * `finish()` entirely, having already set the flag that made every later press a no-op. The person
 * is then inside a Space with no controller Back observed arriving at all (`B-115`) and no way out
 * but the power button. A promise in a comment is not a guard.
 *
 * **Two flags, because there are two facts.** A panel may be destroyed once — a second
 * `panelEntity.destroy()` is the SIGSEGV this class exists to prevent — while an exit that did not
 * happen may be retried, and must be, since reaching `finish()` is the whole of the way out. So
 * `panelReleased` latches the first step and `left` latches only a leave that reached the end
 * without throwing. A press after a successful leave does nothing; a press after a failed one
 * tries the two remaining steps again and skips the one that must not repeat.
 */
internal class SpaceExit(
    private val destroyPanel: () -> Unit,
    private val launchHome: () -> Unit,
    private val finishActivity: () -> Unit,
) {
    /** The panel's resources are released at most once, ever. A second release is a crash. */
    private val panelReleased = AtomicBoolean(false)

    /** Set only by a leave that reached the end. A leave that threw did not leave. */
    private val left = AtomicBoolean(false)

    fun leave() {
        if (left.get()) return

        if (panelReleased.compareAndSet(false, true)) {
            runCatching { destroyPanel() }
                .onFailure { Log2.e("space.exit.destroy_failed", it) }
        }

        val home = runCatching { launchHome() }
            .onFailure { Log2.e("space.exit.home_failed", it) }
        val finished = runCatching { finishActivity() }
            .onFailure { Log2.e("space.exit.finish_failed", it) }

        if (home.isSuccess && finished.isSuccess) left.set(true)
    }
}

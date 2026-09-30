package ai.passioncode.fabricvr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Leaving the Space has a documented order, and this is the only place it can be checked without
 * a headset.
 *
 * Meta, *Known issues* (`spatial-sdk-known-issues`, 2026-04-02): "When transitioning from an
 * immersive activity back to a 2D panel, calling `finish()` on the immersive activity may cause
 * the 2D panel to not reappear. In some cases, calling `finish()` on an activity with panels …
 * can cause a crash (SIGSEGV) inside `libMetaSpatialSDK.so`. … Use `panelEntity.destroy()` to
 * properly clean up panel resources **before ending the activity**." *Spawn and remove 2D panels*
 * repeats it: "Never use the `finish()` method to terminate an Activity associated with a panel.
 * This causes crashes because memory references remain active for the panel's resources: the 3D
 * mesh, layer, texture, and Android surface."
 *
 * The app did the three steps in the wrong order and without the first one at all
 * (`ImmersiveActivity.kt:181-198` before `REQ-053`) — audit `docs/audit/2026-09-21-audit.md` H12.
 */
class SpaceExitTest {

    private val steps = mutableListOf<String>()

    private fun exit() = SpaceExit(
        destroyPanel = { steps += "destroy" },
        launchHome = { steps += "home" },
        finishActivity = { steps += "finish" },
    )

    @Test fun `the panel's resources are released before anything else happens`() {
        exit().leave()

        assertEquals(listOf("destroy", "home", "finish"), steps)
    }

    /**
     * A ray can press a 72 dp control twice in the time the shell takes to switch environments,
     * and the second press would destroy an entity already destroyed and send a second Home
     * intent. Nothing in the old code stopped it, and on a headset the symptom would have been
     * the crash above rather than a double transition.
     */
    @Test fun `a second press does nothing`() {
        val once = exit()
        once.leave()
        once.leave()
        once.leave()

        assertEquals(listOf("destroy", "home", "finish"), steps)
    }

    /**
     * The destroy may throw — the entity can already be gone if the scene was torn down under
     * us — and a leave that stops there strands the person inside the Space with no other way
     * out (`B-115`: no controller Back has ever been observed arriving). Home is the thing that
     * must happen; the cleanup is best-effort.
     */
    @Test fun `a failed cleanup still leaves the space`() {
        SpaceExit(
            destroyPanel = { steps += "destroy"; error("entity already gone") },
            launchHome = { steps += "home" },
            finishActivity = { steps += "finish" },
        ).leave()

        assertEquals(listOf("destroy", "home", "finish"), steps)
    }

    /**
     * **The home intent can throw, and it is the step most likely to** (`B-217`): `startActivity`
     * with `CATEGORY_HOME` raises `ActivityNotFoundException` where nothing resolves it, which is
     * a real shape on a stripped or mid-update Horizon OS image. The class's own KDoc promised
     * that "a cleanup that throws must not strand the person inside the Space" and only the first
     * step was ever wrapped, so a throw here skipped `finish()` entirely — and `left` had already
     * been set, which made every later press a no-op. The person is then inside a Space with no
     * controller Back observed arriving at all (`B-115`) and no way out but the power button.
     */
    @Test fun `a home intent that throws still finishes the activity`() {
        SpaceExit(
            destroyPanel = { steps += "destroy" },
            launchHome = { steps += "home"; error("no activity resolves CATEGORY_HOME") },
            finishActivity = { steps += "finish" },
        ).leave()

        assertEquals(listOf("destroy", "home", "finish"), steps)
    }

    /**
     * **An exit that did not happen may be retried; a panel that was destroyed may not be.** These
     * are two different facts and the old code had one flag for both. A second press after a
     * failed leave has to reach `finish()` — that is the whole of the person's way out — while
     * `panelEntity.destroy()` a second time is the SIGSEGV this class exists to prevent.
     */
    @Test fun `a second press after a failed leave retries without destroying the panel twice`() {
        var homeWorks = false
        val exit = SpaceExit(
            destroyPanel = { steps += "destroy" },
            launchHome = { steps += "home"; if (!homeWorks) error("no activity resolves CATEGORY_HOME") },
            finishActivity = { steps += "finish" },
        )

        exit.leave()
        homeWorks = true
        exit.leave()

        assertEquals(listOf("destroy", "home", "finish", "home", "finish"), steps)
    }

    /**
     * And once it has actually worked, a press really is a no-op: nothing is re-sent, which is
     * what the second-press test above asserts for the ordinary path and this asserts for the
     * path that had to recover first.
     */
    @Test fun `a press after a successful retry does nothing`() {
        var homeWorks = false
        val exit = SpaceExit(
            destroyPanel = { steps += "destroy" },
            launchHome = { steps += "home"; if (!homeWorks) error("no activity resolves CATEGORY_HOME") },
            finishActivity = { steps += "finish" },
        )

        exit.leave()
        homeWorks = true
        exit.leave()
        exit.leave()

        assertEquals(listOf("destroy", "home", "finish", "home", "finish"), steps)
    }
}

package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The line in Settings that says **which build this is**.
 *
 * `H-31`: two headsets are running this app and nobody can tell what is on either of them,
 * including by looking at the app. Settings read `Fabric VR 0.1.0 (1)` on today's build, on the
 * build from two days ago, and on the next one — `versionCode` came from a `-PversionCode` flag
 * nobody had ever passed, and `versionName` was a tracked constant. The question whether the second
 * headset was a week old had no answer that did not involve comparing APK bytes.
 *
 * Asserted against the **rendered** string rather than the fields, because the defect was that the
 * fields were right and said nothing: the format is what a person reads out over a call.
 */
@RunWith(AndroidJUnit4::class)
class SettingsVersionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun render(
        name: String,
        code: Int,
        branch: String,
        builtAt: String,
    ): String = context.getString(R.string.settings_version, name, code, branch, builtAt)

    /**
     * A sha, not a constant. `0.1.0` is the same on every build ever produced; the commit is the
     * one thing that makes a bug report actionable.
     */
    @Test fun `the version line names a commit`() {
        val line = render("0.1.0+a1b2c3d", 137, "feat/v1-notes-core", "2026-09-21T06:00:00+02:00")

        assertTrue("the line does not name the commit: \"$line\"", line.contains("a1b2c3d"))
        assertTrue("the line does not name the branch: \"$line\"", line.contains("feat/v1-notes-core"))
        assertTrue("the line does not say when: \"$line\"", line.contains("2026-09-21"))
        assertTrue("the build number is missing: \"$line\"", line.contains("137"))
    }

    /**
     * **A tarball, a shallow clone, or a machine with no `git` must still build** — and the line
     * must then say `unknown` out loud. A version line that silently omits the provenance is the
     * same failure class as the constant it replaced: it reads as an answer and is not one.
     */
    @Test fun `an unknown provenance says so`() {
        val line = render("0.1.0+unknown", 1, "unknown", "unknown")

        // **Three** of them — the commit, the branch and the date — because a format that drops
        // the fields it was given would still contain the word once, from the name. The first
        // version of this assertion did exactly that and passed against the two-placeholder
        // string it was written to replace.
        assertEquals(
            "the line hides fields it does not know: \"$line\"",
            3,
            line.split("unknown").size - 1,
        )
        assertFalse("the line has an empty field: \"$line\"", line.contains("()"))
        assertFalse("the line has a dangling separator: \"$line\"", line.trim().endsWith("·"))
    }
}

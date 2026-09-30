package ai.passioncode.fabricvr.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `REQ-063`, the app half of `H13`.
 *
 * **The point of this suite is that there is ONE notice, not two.** The obligation the MIT licence
 * puts on this project is that its text travels with the binary, and the cheapest way to satisfy
 * that — pasting the text into a string resource — is the way that rots: the root `NOTICE` is
 * rewritten whenever a dependency moves, the copy is not, and nobody finds out because both
 * render fine. So the asset is **generated** from the root file by `app/build.gradle.kts`, and
 * the first test below reads both and compares the bytes.
 */
@RunWith(AndroidJUnit4::class)
class LicencesTest {

    @get:Rule val compose = createComposeRule()

    /** The unit test's working directory is the module's, so the repository root is one up. */
    private val repositoryNotice = File("../NOTICE")

    @Test fun `the shipped notice is the repository NOTICE, byte for byte`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(
            "the repository's NOTICE is not where this test looks: ${repositoryNotice.absolutePath}",
            repositoryNotice.isFile,
        )

        val shipped = context.assets.open(LICENCE_NOTICE_ASSET).use { it.readBytes() }

        assertArrayEquals(
            "the shipped licence notice has drifted from the repository's NOTICE",
            repositoryNotice.readBytes(),
            shipped,
        )
    }

    @Test fun `the notice reader returns the whole file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val text = readLicenceNotice(context)

        assertTrue("the licence notice is empty", text.isNotBlank())
        assertTrue("the licence notice does not name whisper.cpp", text.contains("whisper.cpp"))
    }

    @Test fun `the licences screen renders the notice`() {
        compose.setContent { LicencesFrame(notice = "whisper.cpp, including ggml — MIT", onBack = {}) }

        compose.onAllNodesWithText("whisper.cpp, including ggml — MIT", substring = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * A screen that cannot read its own asset says so. An empty licences page reads as "this app
     * uses nothing", which is the one thing it must never say.
     */
    @Test fun `a notice that could not be read says so rather than showing nothing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = context.getString(ai.passioncode.fabricvr.R.string.state_licences_unavailable)

        compose.setContent { LicencesFrame(notice = "", onBack = {}) }

        compose.onAllNodesWithText(expected, substring = true).onFirst().assertIsDisplayed()
    }
}

package ai.passioncode.fabricvr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import org.junit.Rule
import org.junit.Test

/**
 * The launch nobody had watched: the 2D panel activity starts and renders the Today screen.
 * REQ-001's machine half — the human half (does it sit beside a Meta Virtual Display screen) is
 * recorded in `docs/evidence/verification.md`, not here.
 */
class PanelSmokeTest {

    @get:Rule val compose = createAndroidComposeRule<PanelActivity>()

    @Test
    fun the_panel_renders_the_today_screen() {
        // **Resolve ids, never assert a sentence.** This test spent three commits red because it
        // asserted the literals "Hold to record" and "New note" after both controls were deleted,
        // while the handoff went on claiming a green suite. A literal assertion breaks the first
        // time anyone edits a string, and it breaks in a way that reads as a broken app.
        val context = compose.activity

        // The header, present in every VoiceState — the record button's label is not: it reads
        // "Transcribing…", "Stop recording" or "Allow the microphone" depending on the state.
        compose.onAllNodesWithText(context.getString(R.string.app_name)).onFirst().assertIsDisplayed()

        // The one control the whole product is: at rest it reads "Record".
        compose.onAllNodesWithText(context.getString(R.string.action_record)).onFirst().assertIsDisplayed()
    }
}

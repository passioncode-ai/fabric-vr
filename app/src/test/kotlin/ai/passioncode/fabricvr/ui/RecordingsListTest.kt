package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.theme.FabricTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Render handed-in state only: coroutine completion belongs in the view-model tests (SI-05). */
@RunWith(AndroidJUnit4::class)
class RecordingsListTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `every play control stays disabled until recording ends`() {
        val recording = mutableStateOf(false)
        val play = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_play_recording)
        compose.setContent {
            FabricTheme {
                RecordingsList(
                    listOf(RecordingItem("current.wav", 2_000L), RecordingItem("earlier.wav", 1_000L)),
                    rememberAudioPlayback(),
                    enabled = !recording.value,
                )
            }
        }
        val controls = compose.onAllNodesWithText(play)
        assertEquals(2, controls.fetchSemanticsNodes().size)
        for (i in 0..1) controls[i].assertIsEnabled()
        compose.runOnIdle { recording.value = true }
        for (i in 0..1) controls[i].assertIsNotEnabled()
        compose.runOnIdle { recording.value = false }
        for (i in 0..1) controls[i].assertIsEnabled()
    }
}

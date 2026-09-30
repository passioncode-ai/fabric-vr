package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.theme.FabricTheme
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Today draws while the microphone and the engine are working, and what that costs to draw.
 *
 * Four board rows, one surface:
 *
 * - `B-096` — `voiceViewModel.state` was read at the top of the screen, so every audio-level
 *   emission (fifty a second) invalidated the whole tree against a 13.9 ms frame budget.
 * - `B-178` — `WhisperEngine.progressPercent()` has been readable since `DEC-0060` and nothing
 *   drew it; a ten-minute dictation is thirteen minutes of a state with no shape.
 * - `B-179` — the same thirteen minutes with no exit. Cancellation reaches the blocking native
 *   call already; nothing offers it.
 * - `B-153` — a dictated row carries no glyph, so a list of notes cannot be scanned for the ones
 *   that were spoken.
 */
@RunWith(AndroidJUnit4::class)
class TodayVoiceSurfaceTest {

    @get:Rule val compose = createComposeRule()

    private fun s(@StringRes id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun note(id: String, title: String, dictated: Boolean) = Note(
        id = id,
        title = title,
        body = title,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        audioPath = if (dictated) "/tmp/$id.wav" else null,
        transcript = if (dictated) Transcript(title, "ru", SttSource.LOCAL, "whisper-base", 900) else null,
    )

    private fun at(width: Int, height: Int, content: @Composable () -> Unit) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp)),
            ) {
                FabricTheme { content() }
            }
        }
    }

    // ---------------------------------------------------------------- B-096

    /**
     * The meter moves fifty times a second and the list of notes is not touched.
     *
     * **This is a guard, not a proof, and the difference is worth writing down** — `B-096` claims
     * that reading the voice state at the top of the screen "recomposes the whole screen", and at
     * this level that claim does not reproduce. Measured on 2026-09-22 across twenty level
     * emissions, with the read eager in `TodayFrame`'s own body and again with it deferred: the
     * note list was touched **0** times either way, and the voice state was read 60 times (three
     * per emission) either way. Compose skips a child whose parameters are stable and unchanged,
     * and with a constant `TodayActions` every child here is skipped.
     *
     * Where the cost is real is one level up, and it is real there for a reason the row does not
     * name: `TodayScreen` builds `TodayActions` — twenty-six lambdas capturing two view models, a
     * clipboard and a playback handle — **inline in the call**, so a recomposition of that
     * function produces a new, unequal instance and every composable below it, `NoteRow` included,
     * loses its skip. Stopping that function recomposing per audio frame is what the fix does; the
     * deferred parameter here is what lets it.
     *
     * So this asserts the property the deferral guarantees, which is the one a future edit can
     * break by writing `val v = voice()` at the top of the frame and passing `v` downwards.
     */
    @Test fun `an audio level emission does not recompose the note list`() {
        val voice = mutableStateOf<VoiceState>(VoiceState.Recording(0f, 0))
        val notes = CountingNotes((1..12).map { note("n$it", "заметка $it", dictated = it % 2 == 0) })

        at(720, 900) {
            TodayFrame(
                state = NotesUiState(loading = false, notes = notes, total = 12),
                voice = { voice.value },
            )
        }
        compose.waitForIdle()
        val settled = notes.reads

        val emissions = 20
        repeat(emissions) { i ->
            voice.value = VoiceState.Recording(level = (i % 10) / 10f, samples = 16_000 * (i + 1))
            compose.waitForIdle()
        }

        val cost = notes.reads - settled
        assertEquals(
            "twenty audio levels cost $cost reads of the note list, against $settled for the first " +
                "composition — the list is being rebuilt on the audio thread's cadence (`B-096`)",
            0,
            cost,
        )
    }

    /**
     * A `List` that reports how often the composition looked at it.
     *
     * Delegation rather than a subclass, so it behaves exactly as the real list does everywhere
     * else in the frame; `size` and `get` are the two members `LazyListScope.items` uses to build
     * an item provider, which is what a recomposition of the list costs.
     */
    private class CountingNotes(private val backing: List<Note>) : List<Note> by backing {
        @Volatile var reads: Int = 0
        override val size: Int get() { reads++; return backing.size }
        override fun get(index: Int): Note { reads++; return backing[index] }
    }

    // ---------------------------------------------------------------- B-178 / B-179

    @Test fun `a running transcription shows how far it has got`() {
        at(720, 900) {
            TodayFrame(
                state = NotesUiState(loading = false),
                voice = { VoiceState.Transcribing },
                transcriptionPercent = 37,
            )
        }

        compose.onAllNodesWithText(s(R.string.voice_transcribing_progress, 37)).onFirst().assertIsDisplayed()
    }

    /**
     * `B-179`'s decision, drawn: the control exists and it is not the record button, which is
     * disabled while a decode runs — a person cannot be asked to press a dead target to escape.
     */
    @Test fun `a running transcription can be stopped`() {
        var stopped = 0
        at(720, 900) {
            TodayFrame(
                state = NotesUiState(loading = false),
                voice = { VoiceState.Transcribing },
                transcriptionPercent = 12,
                actions = TodayActions(onStopTranscription = { stopped++ }),
            )
        }

        compose.onAllNodesWithText(s(R.string.action_stop_transcribing)).onFirst().performClick()

        assertEquals("the Stop control reached nothing", 1, stopped)
    }

    /** Nothing to stop, nothing to draw: an idle screen must not carry a Stop. */
    @Test fun `an idle screen offers no stop`() {
        at(720, 900) {
            TodayFrame(state = NotesUiState(loading = false), voice = { VoiceState.Idle })
        }

        assertEquals(
            "a Stop control was drawn with no transcription behind it",
            0,
            compose.onAllNodesWithText(s(R.string.action_stop_transcribing)).fetchSemanticsNodes().size,
        )
    }

    // ---------------------------------------------------------------- B-153

    @Test fun `a dictated row carries a glyph and a typed one does not`() {
        val notes = listOf(note("a", "продиктовано", dictated = true), note("b", "набрано", dictated = false))
        at(720, 900) {
            TodayFrame(
                state = NotesUiState(loading = false, notes = notes, total = notes.size),
                voice = { VoiceState.Idle },
            )
        }

        assertEquals(
            "the microphone glyph is on every row or on none — `SCR-01`'s sixth element is owned " +
                "by nobody (`B-153`)",
            1,
            compose.onAllNodesWithContentDescription(s(R.string.label_voice_note_icon))
                .fetchSemanticsNodes().size,
        )
    }
}

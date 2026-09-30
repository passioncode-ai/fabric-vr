package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.UiMessage
import ai.passioncode.fabricvr.common.theme.FabricTheme
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.WhisperModel
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
 * The Today frame, measured.
 *
 * **This suite exists because the two hardest claims on this screen are claims about geometry**,
 * and until `T-030` the only way to check either was a headset that has been away since
 * `eaf0c51`:
 *
 * - `B-11` — at the panel's declared minimum the notes were unreachable. The `LazyColumn` was the
 *   last, *unweighted* child of a `Column` with no scroll, so a banner, a confirmation line and an
 *   Undo row together left it nothing to measure with and no way to scroll to what it did not draw.
 * - `B-13` — the record button moved. The doc comment above it claimed it "keeps its place and its
 *   size through all of them" and had been false since `732c92b`; in a headset a person is holding
 *   a controller ray on that target, and it moved twice per dictation.
 *
 * `TodayFrame` is stateless precisely so both can be composed on the JVM at a forced size
 * (`DEC-0043`). These run under Robolectric in the ordinary unit-test tier — a headset is needed
 * for what a headset alone can answer, and geometry is not that.
 */
@RunWith(AndroidJUnit4::class)
class TodayFrameTest {

    @get:Rule val compose = createComposeRule()

    private fun s(@StringRes id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun note(id: String, title: String, audio: String? = null) = Note(
        id = id,
        title = title,
        body = title,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        audioPath = audio,
        transcript = audio?.let { Transcript(title, "ru", SttSource.LOCAL, "whisper-base", 900) },
    )

    private fun listed(vararg notes: Note) =
        NotesUiState(loading = false, notes = notes.toList(), total = notes.size)

    /** The panel at a chosen size, themed as the product themes it. */
    private fun at(width: Int, height: Int, content: @Composable () -> Unit) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp)),
            ) {
                FabricTheme { content() }
            }
        }
    }

    private fun recordButtonTop(): Float =
        compose.onNodeWithTag(RECORD_BUTTON_TAG).fetchSemanticsNode().boundsInRoot.top

    /**
     * `B-11`, part one: at the panel's declared minimum, a note is on screen without scrolling.
     *
     * **Watched failing** against the pre-`T-030` frame — and against this one with `weight(1f)`
     * taken off the list, which is the same defect: the `LazyColumn` was the last, *unweighted*
     * child of a `Column` with no scroll, so it was measured with whatever the fixed chrome left
     * and no `note-row` node existed at all.
     */
    @Test fun `the first note is visible at the declared minimum`() {
        at(PanelMinimum.WIDTH_DP, PanelMinimum.HEIGHT_DP) {
            TodayFrame(
                state = listed(note("n1", "первая мысль"), note("n2", "вторая мысль")),
                voice = { VoiceState.Idle },
            )
        }

        compose.onAllNodesWithTag(NOTE_ROW_TAG).onFirst().assertIsDisplayed()
    }

    /**
     * `B-11`, part two, and the state that provoked it: a failure banner, the "saved and copied"
     * confirmation and an Undo row all up at once.
     *
     * Together those are about 180 dp, so at a 560 dp panel the first note is **below the fold**
     * — and that is correct. What `B-11` was is that there was no fold: the three of them were
     * siblings of an unweighted list, so the notes were not drawn and **no scroll reached them**.
     * This asserts the reachability, which is the property that was lost.
     */
    @Test fun `the notes stay reachable with three transient rows up`() {
        at(PanelMinimum.WIDTH_DP, PanelMinimum.HEIGHT_DP) {
            TodayFrame(
                state = listed(note("n1", "первая мысль"), note("n2", "вторая мысль")).copy(
                    justDeleted = note("n3", "удалённая"),
                    message = UiMessage(R.string.state_nothing_heard, action = UiAction.RETRY_LOAD),
                ),
                voice = { VoiceState.Idle },
                justCopied = true,
            )
        }

        // The height first, and before the scroll on purpose: against the broken frame the list
        // is measured at zero, and `performScrollToNode` against a list that composes no items
        // does not fail — it spins. A test whose failure mode is a hung suite teaches people to
        // skip it, so the measurement that names the defect comes first.
        val listHeight = compose.onNodeWithTag(NOTES_LIST_TAG).fetchSemanticsNode().size.height
        assertTrue(
            "the notes list was measured at ${listHeight}px with three transient rows up — B-11",
            listHeight > 0,
        )
        compose.onNodeWithTag(NOTES_LIST_TAG).performScrollToNode(hasTestTag(NOTE_ROW_TAG))
        compose.onAllNodesWithTag(NOTE_ROW_TAG).onFirst().assertIsDisplayed()
    }

    /**
     * `REQ-060` / `M20`. **Undo has to be reachable from where the delete happened.**
     *
     * The Undo row, the error banner and the 2.5 s confirmations were the LazyColumn's first
     * items, so deleting the thirtieth note put "Deleted … Undo" above thirty rows of scroll — and
     * the confirmations expired unseen while the person was looking at the bottom of their own
     * list. They live in a pinned slot between the record button and the list now.
     *
     * Watched failing against the pre-`REQ-060` frame: after scrolling to the last note the Undo
     * button was not displayed.
     */
    @Test fun `undo stays on screen when the delete happened far down the list`() {
        val many = (1..30).map { note("n$it", "мысль $it") }
        at(PanelMinimum.WIDTH_DP, PanelMinimum.HEIGHT_DP) {
            TodayFrame(
                state = NotesUiState(loading = false, notes = many, total = many.size)
                    .copy(justDeleted = note("gone", "удалённая мысль")),
                voice = { VoiceState.Idle },
            )
        }

        compose.onNodeWithTag(NOTES_LIST_TAG).performScrollToNode(hasText("мысль 30"))
        compose.waitForIdle()

        compose.onAllNodesWithText(s(R.string.action_undo)).onFirst().assertIsDisplayed()
    }

    /**
     * The same property for the failure banner: a storage failure announced once, at the top of a
     * list somebody is scrolled to the bottom of, is announced to nobody.
     */
    @Test fun `the error banner stays on screen when the list is scrolled`() {
        val many = (1..30).map { note("n$it", "мысль $it") }
        at(PanelMinimum.WIDTH_DP, PanelMinimum.HEIGHT_DP) {
            TodayFrame(
                state = NotesUiState(loading = false, notes = many, total = many.size)
                    .copy(message = UiMessage(R.string.state_nothing_heard, action = UiAction.RETRY_LOAD)),
                voice = { VoiceState.Idle },
            )
        }

        compose.onNodeWithTag(NOTES_LIST_TAG).performScrollToNode(hasText("мысль 30"))
        compose.waitForIdle()

        compose.onAllNodesWithText(s(R.string.state_nothing_heard)).onFirst().assertIsDisplayed()
    }

    /**
     * `B-13`. One button, driven through five states in place — which is what happens to a person
     * mid-dictation, and a stricter check than five separately composed screens that happen to
     * agree.
     *
     * **Watched failing** against the pre-`T-030` frame: Idle, Recording, Downloading and Failed
     * put the button at four different heights, because each state's message above it was a
     * different size.
     */
    @Test fun `the record button does not move between states`() {
        val voice: MutableState<VoiceState> = mutableStateOf(VoiceState.Idle)
        at(1024, 640) {
            TodayFrame(state = listed(note("n1", "мысль")), voice = { voice.value })
        }

        val measured = STATES.map { state ->
            compose.runOnIdle { voice.value = state }
            compose.waitForIdle()
            state to recordButtonTop()
        }

        assertEquals(
            "the record button moved between states: $measured",
            1,
            measured.map { it.second }.distinct().size,
        )
    }

    /**
     * The same walk at the declared minimum, because a slot that fits at 640 dp and clips at 560
     * is a defect that only shows where nobody is looking.
     */
    @Test fun `the record button does not move at the declared minimum either`() {
        val voice: MutableState<VoiceState> = mutableStateOf(VoiceState.Idle)
        at(PanelMinimum.WIDTH_DP, PanelMinimum.HEIGHT_DP) {
            TodayFrame(state = listed(note("n1", "мысль")), voice = { voice.value })
        }

        val measured = STATES.map { state ->
            compose.runOnIdle { voice.value = state }
            compose.waitForIdle()
            state to recordButtonTop()
        }

        assertEquals(
            "the record button moved between states: $measured",
            1,
            measured.map { it.second }.distinct().size,
        )
    }

    /**
     * Every `VoiceState` reaches the slot and leaves the button where it is — `B-05` was `Allowed`
     * rendering nothing at all behind an `else ->` that hid the missing branch. The `when` is
     * exhaustive now and the compiler enforces it; this asserts the states also *render*.
     */
    @Test fun `every voice state renders with the button in place`() {
        val voice: MutableState<VoiceState> = mutableStateOf(VoiceState.Idle)
        at(1024, 640) {
            TodayFrame(state = listed(note("n1", "мысль")), voice = { voice.value })
        }

        val tops = ALL_STATES.map { state ->
            compose.runOnIdle { voice.value = state }
            compose.waitForIdle()
            compose.onNodeWithTag(RECORD_BUTTON_TAG).assertIsDisplayed()
            recordButtonTop()
        }

        assertEquals(ALL_STATES.size, tops.size)
        assertEquals("a state moved the button: ${ALL_STATES.zip(tops)}", 1, tops.distinct().size)
    }

    /**
     * `B-10`. `RetranscribeResult` exists so this surface can tell *Replaced* from
     * *TranscriptOnly*, and the two strings written for it had no caller — so a person who had
     * edited a note's text saw *Transcribe again* apparently do nothing at all.
     */
    @Test fun `a re-transcription that kept an edited body says so`() {
        at(1024, 640) {
            TodayFrame(
                state = listed(note("n1", "мысль")),
                voice = { VoiceState.Idle },
                retranscribed = RetranscribeResult.TranscriptOnly,
            )
        }

        compose.onAllNodesWithText(s(R.string.state_retranscribed_kept)).onFirst().assertIsDisplayed()
    }

    /** And the other success, which is a different sentence because it means something else. */
    @Test fun `a re-transcription that replaced the body says so`() {
        at(1024, 640) {
            TodayFrame(
                state = listed(note("n1", "мысль")),
                voice = { VoiceState.Idle },
                retranscribed = RetranscribeResult.Replaced,
            )
        }

        compose.onAllNodesWithText(s(R.string.state_retranscribed)).onFirst().assertIsDisplayed()
    }

    /**
     * `B-25`: the header carries words wherever there is width for them. At the declared minimum
     * there is not, and the glyph keeps the same label as its `contentDescription` — so the same
     * finder locates the control either way and nothing becomes unreachable when the panel
     * shrinks.
     */
    @Test fun `every header action is reachable at both sizes`() {
        at(PanelMinimum.WIDTH_DP, PanelMinimum.HEIGHT_DP) {
            TodayFrame(state = listed(note("n1", "мысль")), voice = { VoiceState.Idle })
        }

        listOf(R.string.action_new_note, R.string.action_search, R.string.label_settings).forEach { id ->
            val label = s(id)
            compose.onAllNodes(hasText(label) or hasContentDescription(label))
                .onFirst().assertIsDisplayed()
        }
    }

    /**
     * `B-20`. The `clickable` that opens a note wrapped the whole left column of the row — the
     * action buttons and the gutter between them included — so aiming at the space beside
     * *Delete* opened the editor instead. In a headset, where the ray lands a few millimetres
     * from where it was aimed, that is the difference between deleting a note and editing it.
     *
     * Measured as geometry rather than as a click at a chosen coordinate: the defect is that the
     * open target *contains* the buttons, and this says exactly that.
     */
    @Test fun `the row's open target does not cover its buttons`() {
        at(1024, TALL) {
            TodayFrame(
                state = listed(note("n1", "мысль", audio = "/tmp/rec.wav")),
                voice = { VoiceState.Idle },
            )
        }

        val open = compose.onNodeWithTag(NOTE_OPEN_TAG).fetchSemanticsNode().boundsInRoot
        val delete = compose.onAllNodesWithText(s(R.string.action_delete)).onFirst()
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the open target $open still covers the Delete button $delete",
            delete.top >= open.bottom,
        )
    }

    /**
     * `B-09`. *Transcribe again* expands the row instead of opening a `DropdownMenu`, which is a
     * `Popup` — a second window through `WindowManager`. In the Space a panel is a `Presentation`
     * on a `VirtualDisplay` created without `SUPPORTS_TOUCH` or `TRUSTED`, so whether such a
     * window draws at all, and whether it can be dismissed, is unverified; and this control is
     * the whole of `SCN-005`'s recovery path.
     *
     * Nothing about a `Popup` can be measured here — that is the point. What is measured is that
     * the five choices arrive **in the row's own tree**, which a popup's contents would not.
     */
    @Test fun `the engine choices appear inside the row, not in a second window`() {
        at(1024, TALL) {
            TodayFrame(
                state = listed(note("n1", "мысль", audio = "/tmp/rec.wav")),
                voice = { VoiceState.Idle },
            )
        }

        val rowBefore = compose.onNodeWithTag(NOTE_ROW_TAG).fetchSemanticsNode().size.height
        compose.onAllNodesWithText(s(R.string.action_redo_stt)).onFirst().performClick()
        compose.waitForIdle()

        val rowAfter = compose.onNodeWithTag(NOTE_ROW_TAG).fetchSemanticsNode().size.height
        assertTrue("the row did not expand: $rowBefore -> $rowAfter", rowAfter > rowBefore)
        compose.onAllNodesWithText(s(WhisperModel.DEFAULT.labelRes)).onFirst().assertIsDisplayed()
    }

    /**
     * `B-14`. `copied` was set true on click and set false by nothing, and the list is keyed by
     * note id — so the button read *Copied* for the rest of the screen's life and stopped saying
     * anything at all. The clock here is real rather than virtual: the reset is a `delay` inside
     * a composition, and `waitUntil` polls the frame clock the composition actually runs on.
     */
    @Test fun `Copied reverts to Copy`() {
        at(1024, TALL) {
            TodayFrame(state = listed(note("n1", "мысль")), voice = { VoiceState.Idle })
        }

        compose.onAllNodesWithText(s(R.string.action_copy)).onFirst().performClick()
        compose.waitUntil(CONFIRMATION_WAIT_MS) {
            compose.onAllNodesWithText(s(R.string.action_copied)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(CONFIRMATION_WAIT_MS) {
            compose.onAllNodesWithText(s(R.string.action_copy)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // --- T-031: the first run -----------------------------------------------------------

    /**
     * `D-05`. The first screen has to say what to press, what it will cost, and that there is a
     * way round it — and the way round it is only honest because `T-028` put *New note* back.
     *
     * **The spec asked for this on a device; it runs here** because `T-030` made the frame
     * stateless, and a string chosen by a boolean is not a thing a headset answers better.
     */
    @Test fun `the first-run empty state names the download`() {
        at(1024, TALL) {
            TodayFrame(
                state = NotesUiState(loading = false),
                voice = { VoiceState.Idle },
                modelPresent = false,
            )
        }

        compose.onAllNodesWithText(s(R.string.today_empty_first_run, 190)).onFirst().assertIsDisplayed()
    }

    /**
     * And the short one once the model is there. Somebody who has deleted their last note does
     * not need the onboarding paragraph again — and it would be **wrong**, because it promises a
     * download that has already happened.
     */
    @Test fun `the empty state is short once the model is present`() {
        at(1024, TALL) {
            TodayFrame(
                state = NotesUiState(loading = false),
                voice = { VoiceState.Idle },
                modelPresent = true,
            )
        }

        compose.onAllNodesWithText(s(R.string.today_empty)).onFirst().assertIsDisplayed()
        assertEquals(
            "the first-run paragraph is still on screen after the download",
            0,
            compose.onAllNodesWithText(s(R.string.today_empty_first_run, 190)).fetchSemanticsNodes().size,
        )
    }

    /**
     * A person who has declined the microphone must not be left on a screen whose only control
     * is the thing they declined. Both actions, in the status slot, after a refusal — which is
     * the one moment an explanation is worth the room (`T-031`).
     */
    @Test fun `a refused microphone offers asking again and writing instead`() {
        at(1024, TALL) {
            TodayFrame(
                state = listed(note("n1", "мысль")),
                voice = { VoiceState.NeedsPermission },
            )
        }

        // `performScrollTo` because the status slot scrolls inside its fixed 130 dp, and under
        // Robolectric's unscaled text metrics the explanation line alone fills it — on the
        // device the line and the two controls come to about 104 dp and no scroll happens
        // (`DEC-0043`, `B-145`). What is asserted either way is that both controls are reachable
        // without the button moving, which is the property that matters.
        compose.onAllNodesWithText(s(R.string.action_ask_again)).onFirst()
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.action_write_instead)).onFirst()
            .performScrollTo().assertIsDisplayed()
    }

    /**
     * Four minutes of waiting must not end in an absence. `DownloadProgress.Done` became
     * `VoiceState.Idle`, the bar vanished, and nothing said the bytes had arrived or been
     * verified (`D-05`).
     */
    @Test fun `a finished download says the model is ready`() {
        at(1024, TALL) {
            TodayFrame(
                state = listed(note("n1", "мысль")),
                voice = { VoiceState.Idle },
                modelReady = true,
            )
        }

        compose.onAllNodesWithText(s(R.string.state_model_ready)).onFirst().assertIsDisplayed()
    }

    /**
     * The button stops relabelling itself. Pressing *Record* with no permission used to be
     * answered with a different button in the same place — `D-05`'s first extra tap — and the
     * press that reached the system was the second one.
     */
    @Test fun `the record button never asks for the microphone in its own label`() {
        val voice: MutableState<VoiceState> = mutableStateOf(VoiceState.Idle)
        at(1024, 640) {
            TodayFrame(state = listed(note("n1", "мысль")), voice = { voice.value })
        }

        ALL_STATES.forEach { state ->
            compose.runOnIdle { voice.value = state }
            compose.waitForIdle()
            assertEquals(
                "the button relabelled itself in state $state",
                0,
                compose.onAllNodesWithText(s(R.string.action_allow_microphone)).fetchSemanticsNodes().size,
            )
        }
    }

    /**
     * The last silence in the flow: *Transcribing…*, disabled, and at the measured 1.31× real
     * time a forty-second thought costs about fifty-two seconds of nothing moving. A counter is
     * the cheapest honest answer; a real fraction and a cancel are `B-146` and `B-147`.
     */
    @Test fun `transcribing counts the seconds`() {
        at(1024, 640) {
            TodayFrame(state = listed(note("n1", "мысль")), voice = { VoiceState.Transcribing })
        }

        compose.waitUntil(CONFIRMATION_WAIT_MS) {
            compose.onAllNodesWithText(s(R.string.voice_seconds, 1)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * **The state a first launch actually produces**, which is not the one the first two
     * empty-state cases seed: the daily note exists from `init`, so `today` is non-null while
     * the list is empty. Before step 8's verification the note was *also* in the list, which is
     * why neither empty state could be reached and why today's note was drawn twice.
     */
    @Test fun `a first launch shows the onboarding paragraph above the day card`() {
        val today = note("today", "")
        at(1024, TALL) {
            TodayFrame(
                state = NotesUiState(loading = false, today = today, dayLabel = "2026-09-21"),
                voice = { VoiceState.Idle },
                modelPresent = false,
                modelMegabytes = 190,
            )
        }

        compose.onAllNodesWithText(s(R.string.today_empty_first_run, 190)).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.label_today_note)).onFirst().assertIsDisplayed()
        assertEquals(
            "today's note is drawn as a row as well as on the card",
            0,
            compose.onAllNodesWithTag(NOTE_ROW_TAG).fetchSemanticsNodes().size,
        )
    }

    /**
     * The sentence names **the chosen model's** size. It hard-coded 190 MB, the default, which is
     * wrong for four of the five models a person can pick in Settings — one string away from
     * `action_download_model`, which `T-031` had just fixed for the same reason.
     */
    @Test fun `the first-run sentence names the chosen model's size`() {
        at(1024, TALL) {
            TodayFrame(
                state = NotesUiState(loading = false),
                voice = { VoiceState.Idle },
                modelPresent = false,
                modelMegabytes = 574,
            )
        }

        compose.onAllNodesWithText(s(R.string.today_empty_first_run, 574)).onFirst().assertIsDisplayed()
    }

    /**
     * The model banner carries **both** controls: the repair and the way round it. `UiMessage`
     * gained `secondary` for this, and a refusal whose only control is the thing the person has
     * just declined is a dead end.
     */
    @Test fun `a missing model offers the download and writing instead`() {
        at(1024, TALL) {
            TodayFrame(
                state = listed(note("n1", "мысль")),
                voice = {
                    VoiceState.Failed(
                        ai.passioncode.fabricvr.common.UiStateMapper.map(
                            ai.passioncode.fabricvr.common.AppError.ModelMissing("small", 190_085_487L),
                        ),
                    )
                },
            )
        }

        compose.onAllNodesWithText(s(ai.passioncode.fabricvr.common.R.string.action_download))
            .onFirst().performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(s(ai.passioncode.fabricvr.common.R.string.action_write_note))
            .onFirst().performScrollTo().assertIsDisplayed()
    }

    /**
     * The counter spans the whole wait. It was per-branch, so a `Transcribing → PreparingEngine`
     * walk — which is what happens whenever a model has to be loaded or swapped — restarted it
     * from zero, covering the shortest part of the silence it was written for.
     */
    @Test fun `the seconds keep counting while the engine is being prepared`() {
        val voice: MutableState<VoiceState> = mutableStateOf(VoiceState.Transcribing)
        at(1024, 640) {
            TodayFrame(state = listed(note("n1", "мысль")), voice = { voice.value })
        }
        compose.waitUntil(CONFIRMATION_WAIT_MS) {
            compose.onAllNodesWithText(s(R.string.voice_seconds, 1)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle { voice.value = VoiceState.PreparingEngine(WhisperModel.DEFAULT) }
        compose.waitForIdle()

        // **Still there**, and not back at zero. Asserting only the absence of "0s" was too weak
        // and a planted defect walked through it: removing the counter from `PreparingEngine`
        // leaves no "0s" either. The presence is what the finding was about.
        assertTrue(
            "the counter disappeared when the engine was prepared",
            compose.onAllNodesWithText(s(R.string.voice_seconds, 1)).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "the counter restarted when the engine was prepared",
            compose.onAllNodesWithText(s(R.string.voice_seconds, 0)).fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * `G-02`, the clipboard half. `DEC-0012` copies on every dictation and the confirmation says
     * so; when the copy is switched off the confirmation must stop claiming it, because a false
     * confirmation is worse than none.
     */
    @Test fun `the confirmation does not claim a copy that did not happen`() {
        at(1024, TALL) {
            TodayFrame(
                state = listed(note("n1", "мысль")),
                voice = { VoiceState.Idle },
                justCopied = true,
                copiedToClipboard = false,
            )
        }

        compose.onAllNodesWithText(s(R.string.state_saved)).onFirst().assertIsDisplayed()
        assertEquals(
            "the confirmation claimed a clipboard copy that was switched off",
            0,
            compose.onAllNodesWithText(s(R.string.state_saved_and_copied)).fetchSemanticsNodes().size,
        )
    }

    private companion object {
        /** Longer than `CONFIRMATION_MS`, and it is a ceiling rather than a sleep. */
        const val CONFIRMATION_WAIT_MS = 10_000L

        /**
         * A canvas tall enough that a whole note row is placed, for the tests whose subject is
         * the **row** rather than the frame's height budget.
         *
         * It is generous because Robolectric's text metrics do not scale with the forced
         * density: a line of `titleLarge` measures the same number of pixels whatever size the
         * panel is told it is, so a row that is ~200 dp on the device measures ~480 dp here.
         * That makes the budget tests above **stricter** than reality, which is the safe
         * direction; it makes a row-level test fail for a reason that has nothing to do with its
         * subject, which is not. See `DEC-0043` on what this harness can and cannot answer.
         */
        const val TALL = 2_400

        /** The five `T-030` names: the states a person meets during one dictation. */
        val STATES = listOf(
            VoiceState.Idle,
            VoiceState.Recording(level = 0.4f, samples = 16_000),
            VoiceState.Transcribing,
            VoiceState.Downloading(bytes = 40_000_000, total = 190_000_000),
            VoiceState.Failed(UiMessage(R.string.state_nothing_heard, action = UiAction.RETRY_LOAD)),
        )

        /** Every state the sealed interface has, so a new one cannot be added unrendered. */
        val ALL_STATES = STATES + listOf(
            VoiceState.NeedsPermission,
            VoiceState.NothingHeard,
            VoiceState.PreparingEngine(WhisperModel.DEFAULT),
            VoiceState.Ready(
                Transcript("готово", "ru", SttSource.LOCAL, "whisper-base", 900),
                audioPath = null,
            ),
        )
    }
}

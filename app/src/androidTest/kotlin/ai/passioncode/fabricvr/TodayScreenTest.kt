package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.notes.NotesRepository
import kotlinx.coroutines.runBlocking
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * `A-01`: **there was no way to make a text note.**
 *
 * `NotesViewModel.createNote()`, the string `action_new_note` and `Routes.editor` all existed and
 * no screen called any of them, so the first thing a person saw on a fresh install was an empty
 * state offering neither of the two things it names. The control was collateral of `DEC-0010`'s
 * deliberate simplification — the hold gesture and the capture sheet went for good reasons — and
 * nothing recorded removing note creation.
 *
 * **Asserted by resource id, never by literal.** `B-03` is that a hard-coded `"New note"` in
 * `PanelSmokeTest` is what made the instrumented suite red for three commits after the string
 * changed: a literal assertion breaks the first time anyone edits copy, and it breaks in a way
 * that reads as a broken app.
 */
@RunWith(AndroidJUnit4::class)
class TodayScreenTest {

    @get:Rule val compose = createAndroidComposeRule<PanelActivity>()

    private fun s(@StringRes id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @Test fun aNewNoteCanBeCreatedFromToday() {
        compose.onAllNodesWithText(s(R.string.action_new_note)).onFirst().assertIsDisplayed().performClick()

        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(s(R.string.label_title)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(s(R.string.label_title)).onFirst().assertIsDisplayed()
    }

    @Test fun todaysNoteIsReachableFromACard() {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(s(R.string.label_today_note)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(s(R.string.label_today_note)).onFirst().performClick()

        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(s(R.string.label_title)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * **This one will look pointless in six months, so: `B-01`.**
     *
     * In the Space a panel is a `Presentation` on a `VirtualDisplay`, and
     * `PanelDisplayBase.dispatchEvent` returns **without dispatching** while
     * `imm.isAcceptingText()` is true — so the moment the keyboard opens, the panel stops
     * receiving taps at all, including *Back*. Auto-focusing a field on entry is a trap the
     * person cannot get out of. `SearchScreen` already sets it; the editor must not.
     */
    @Test fun theEditorDoesNotOpenWithTheKeyboardUp() {
        compose.onAllNodesWithText(s(R.string.action_new_note)).onFirst().performClick()
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(s(R.string.label_title)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onAllNodesWithText(s(R.string.label_title)).onFirst().assertIsNotFocused()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }

    // --- T-029: tags are reachable ----------------------------------------------------------

    /**
     * `D-01`. The tag feature was complete except that nobody could reach it: parsed, stored,
     * filtered, held in state with `selectTag` exposed — and read by no composable. `SCN-003`
     * steps 2–4 could not be performed at all.
     *
     * **Seeded through `Graph`, not through the editor.** This test runs in the app's own
     * process, so the repository is right there; going through the editor would mean its 600 ms
     * autosave and a navigation round trip, which makes a flaky test out of deterministic
     * behaviour. `D-01`'s proposed verification says to type `#idea` — the typing half is
     * already covered on the JVM by `NotesRepositoryTest`.
     */
    @Test fun aTagChipNarrowsTheListAndAllWidensItAgain() = runBlocking {
        val tagged = NotesRepository.newNote("с тегом", "мысль про #идея")
        val plain = NotesRepository.newNote("без тега", "просто текст")
        Graph.notes.upsert(tagged).getOrThrow()
        Graph.notes.upsert(plain).getOrThrow()
        try {
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText("#идея").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onAllNodesWithText("#идея").onFirst().assertIsDisplayed().performClick()
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText("без тега").fetchSemanticsNodes().isEmpty()
            }
            compose.onAllNodesWithText("с тегом").onFirst().assertIsDisplayed()

            compose.onAllNodesWithText(s(R.string.label_all_tags)).onFirst().performClick()
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText("без тега").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            Graph.notes.delete(tagged.id)
            Graph.notes.delete(plain.id)
        }
    }

    /**
     * **A filter that matches nothing says which filter**, and keeps the chip that releases it.
     *
     * Falling back to *All* silently would make deleting the last tagged note look like a filter
     * that broke, and rendering the row from `state.tags` alone would leave a selection with no
     * chip to press — the list empty and no way out. The state half is pinned on the JVM by
     * `a selected tag survives its last note losing it`.
     */
    @Test fun aFilterWithNoMatchesSaysSoAndCanStillBeReleased() = runBlocking {
        val tagged = NotesRepository.newNote("исчезнет", "мысль про #пропажа")
        Graph.notes.upsert(tagged).getOrThrow()
        try {
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText("#пропажа").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("#пропажа").onFirst().performClick()
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText("исчезнет").fetchSemanticsNodes().isNotEmpty()
            }

            // The last note carrying the tag loses it while the filter is on.
            Graph.notes.upsert(tagged.copy(body = "мысль без тега", tags = emptySet())).getOrThrow()

            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText(s(R.string.today_no_tagged_notes, "пропажа"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText(s(R.string.label_all_tags)).onFirst()
                .assertIsDisplayed().performClick()
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText("исчезнет").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            Graph.notes.delete(tagged.id)
        }
    }
}

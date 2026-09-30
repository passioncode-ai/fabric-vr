package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.InstalledModel
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.vault.AudioUsage
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `REQ-061`, audit `M27` — the interface is true of THIS build.
 *
 * Every case below is a sentence the audit proved false by reading the code that renders it. They
 * are asserted here rather than reviewed, because a copy defect is the one class of defect that
 * survives every gate this project has: `check-strings.sh` sees location, lint sees reachability,
 * and neither can see that a true-looking sentence describes something the code does not do.
 */
@RunWith(AndroidJUnit4::class)
class CopyTruthTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    // ---- C1: the delete button names the set the sweep actually deletes --------------------

    /**
     * **The button said "Delete 9 recordings (412 MB)" and deleted only the old ones.**
     *
     * It was filled from `audioUsage` — the TOTAL count and bytes — while
     * `Vault.sweepAudio(cutoff)` removes what is older than the cutoff. Against `DEC-0038`'s own
     * *"names what it is about to remove"*, and the direction of the error is the bad one: it
     * over-states, so the person braces for more loss than happens and cannot trust the number
     * next time.
     *
     * The honest fix is **words, not a different number**: nothing in the app knows how many
     * files are older than the cutoff without walking the tree, and a second walk on every
     * retention tap to fill a button caption is a cost the audit's `M23`/`T-019` reasoning
     * already refuses. So the label names the *set*.
     */
    @Test fun `the delete button names the same set the sweep deletes`() {
        val state = SettingsUiState(
            audioUsage = AudioUsage(count = 9, bytes = 412_000_000L),
            audioRetentionDays = 30,
        )

        compose.setContent {
            RecordingsSection(state = state, formatSize = { "$it B" }, onRetention = {}, onDelete = {})
        }

        compose.onAllNodesWithText(s(R.string.action_delete_old_recordings, 30)).onFirst()
            .assertIsDisplayed()
        assertEquals(
            "the label still carries the total count, which the sweep does not delete",
            0,
            compose.onAllNodesWithText("Delete 9", substring = true).fetchSemanticsNodes().size,
        )
    }

    /** And it says *older than*, with the days the sweep will use — not a count it cannot know. */
    @Test fun `the delete label states the age, and takes exactly one argument`() {
        val raw = s(R.string.action_delete_old_recordings, 7)

        assertTrue("the label does not name the age it deletes by: $raw", raw.contains("7"))
        assertTrue("the label does not say what makes a recording old: $raw", raw.contains("older"))
    }

    // ---- B-199: the model shelf says what is there and what it costs ------------------------

    /**
     * **A row says what the model occupies *now*, and whether those bytes are a model at all.**
     *
     * `InstalledModel.bytes` is measured from the filesystem rather than taken from
     * `WhisperModel.bytes`, which is what the file *will* weigh — the number a person deciding
     * what to delete needs is the one on the disk. And an interrupted transfer keeps its `.part`
     * on purpose (`H8`), so the shelf holds rows that are **not** models: a row that did not say
     * so would read as a model ready to use, and the person would delete the wrong one.
     */
    @Test fun `a model row names what it occupies now, and an unfinished one says so`() {
        val state = SettingsUiState(
            installed = listOf(
                InstalledModel(WhisperModel.SMALL, bytes = 190_085_487L, complete = true),
                InstalledModel(WhisperModel.MEDIUM, bytes = 93_000_000L, complete = false),
            ),
            installedBytes = 283_085_487L,
        )

        compose.setContent {
            ModelStorageSection(
                state = state,
                formatSize = { "$it B" },
                onRemove = {},
                onFreeUpSpace = {},
            )
        }

        compose.onAllNodesWithText(s(R.string.settings_model_row, s(R.string.model_short_small), "190085487 B"))
            .onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.settings_model_row, s(R.string.model_short_medium), "93000000 B"))
            .onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.settings_model_row_partial)).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.settings_models_total, "283085487 B", 2))
            .onFirst().assertIsDisplayed()
        assertEquals(
            "the shelf shows a model's published size instead of what is on the disk",
            0,
            compose.onAllNodesWithText("574", substring = true).fetchSemanticsNodes().size,
        )
    }

    /**
     * ***Free up space* is offered only when it would free some.** With one model on disk that
     * model is the selected one, which the sweep keeps — so the button would reclaim nothing,
     * and a control that does nothing is how a person learns to distrust the next one.
     */
    @Test fun `free up space is not offered when there is nothing it could take`() {
        compose.setContent {
            ModelStorageSection(
                state = SettingsUiState(
                    installed = listOf(InstalledModel(WhisperModel.SMALL, 190_085_487L, complete = true)),
                    installedBytes = 190_085_487L,
                ),
                formatSize = { "$it B" },
                onRemove = {},
                onFreeUpSpace = {},
            )
        }

        assertEquals(
            "the button is offered over a shelf holding only the model it would keep",
            0,
            compose.onAllNodesWithText(s(R.string.action_free_up_space)).fetchSemanticsNodes().size,
        )
    }

    // ---- C2: retention is an arming choice, not a standing policy ---------------------------

    /**
     * *"Delete recordings older than [30 days]"* above a set of chips reads as a policy that
     * runs. `DEC-0038` says the opposite — "nothing fires on its own" — and nothing in the tree
     * schedules a sweep. The heading and the note beneath it now say which it is.
     */
    @Test fun `the retention row says it only arms the button`() {
        val note = s(R.string.settings_retention_note)

        assertTrue(
            "the retention note does not say that nothing deletes on its own: $note",
            note.contains("on its own"),
        )
        compose.setContent {
            RecordingsSection(
                state = SettingsUiState(audioUsage = AudioUsage(0, 0L), audioRetentionDays = 0),
                formatSize = { "$it B" },
                onRetention = {},
                onDelete = {},
            )
        }
        compose.onAllNodesWithText(note, substring = true).onFirst().assertIsDisplayed()
    }

    // ---- C3: the first-run line is chosen by the provider ----------------------------------

    /**
     * **"…and after that everything happens on this headset" is false under `CLOUD`.**
     *
     * It was chosen by whether the model was present, so a person whose speech goes to a third
     * party read a promise of privacy one row above the line that says their recording is sent
     * away. Both clauses of the local sentence are wrong there: the first dictation does not
     * download a model either — it uploads a recording.
     */
    @Test fun `the first run line is chosen by the provider, not by the model`() {
        compose.setContent {
            TodayFrame(
                    state = NotesUiState(loading = false),
                    voice = { VoiceState.Idle },
                    modelPresent = false,
                    provider = SttProvider.LOCAL,
                )
        }
        compose.onAllNodesWithText(s(R.string.today_empty_first_run, 190), substring = true)
            .onFirst().assertIsDisplayed()
    }

    @Test fun `a remote provider is never promised that everything happens on this headset`() {
        // One composition, two providers: `setContent` may be called once per test, and what is
        // under test is that the SENTENCE follows the choice — so the choice has to move.
        val provider = mutableStateOf(SttProvider.CLOUD)
        compose.setContent {
            TodayFrame(
                state = NotesUiState(loading = false),
                voice = { VoiceState.Idle },
                modelPresent = false,
                provider = provider.value,
            )
        }

        listOf(SttProvider.CLOUD, SttProvider.SERVER).forEach { chosen ->
            provider.value = chosen
            compose.waitForIdle()

            assertEquals(
                "$chosen was promised the on-headset sentence",
                0,
                compose.onAllNodesWithText(s(R.string.today_empty_first_run, 190), substring = true)
                    .fetchSemanticsNodes().size,
            )
            compose.onAllNodesWithText(s(R.string.today_empty_first_run_remote), substring = true)
                .onFirst().assertIsDisplayed()
        }
    }

    /** The claim the audit caught, stated as an assertion about the string itself. */
    @Test fun `only the local first-run line claims the headset keeps everything`() {
        assertTrue(s(R.string.today_empty_first_run, 190).contains("on this headset"))
        assertFalse(s(R.string.today_empty_first_run_remote).contains("on this headset"))
    }

    // ---- C4: no raw key, enum member or class name reaches the person -----------------------

    /**
     * `B-151`. The editor rendered `transcript.source.name.lowercase()` — so a person read the
     * literal `local_fallback` — and `transcript.engine`, which is `cloud:whisper-large-v3` or
     * `whisper-server`. Two human strings sat unused beside it, kept alive only by a
     * `tools:ignore`. They are what it renders now, and the engine identifier is a diagnostic
     * that belongs in `Log2`.
     */
    @Test fun `the transcript badge renders sentences, not enum members`() {
        SttSource.entries.forEach { source ->
            val badge = s(R.string.editor_transcript_meta, "ru", s(transcriptSourceRes(source)))

            assertFalse("the badge leaks $source: $badge", badge.contains(source.name.lowercase()))
            assertFalse("the badge leaks the engine identifier: $badge", badge.contains(":"))
        }
    }

    @Test fun `every transcript source has a sentence of its own`() {
        val sentences = SttSource.entries.map { s(transcriptSourceRes(it)) }

        assertEquals("two sources read the same", sentences.size, sentences.toSet().size)
        sentences.forEach { assertTrue("an empty source sentence", it.isNotBlank()) }
    }

    /** And the badge is built from them, rather than from the transcript's own fields. */
    @Test fun `the editor badge is built from the source sentence`() {
        val transcript = Transcript("x", "ru", SttSource.LOCAL_FALLBACK, "cloud:whisper-large-v3", 0)

        val badge = s(R.string.editor_transcript_meta, transcript.language, s(transcriptSourceRes(transcript.source)))

        assertEquals(s(R.string.editor_transcript_meta, "ru", s(R.string.stt_source_local_fallback)), badge)
    }

    // ---- C5: one server, one name -----------------------------------------------------------

    /**
     * Four names for one thing: *whisper-server URL (optional)*, *My whisper server*, *Your
     * saved whisper server*, *speech server*. And `(optional)` is false the moment that provider
     * is the chosen one — it is then the only address there is.
     */
    @Test fun `the whisper server has one name everywhere`() {
        val naming = listOf(
            R.string.field_server_url,
            R.string.provider_server,
            R.string.settings_carried_over_server,
        ).map { s(it) }

        naming.forEach { line ->
            assertTrue("this does not use the one name: $line", line.lowercase().contains("whisper server"))
        }
        assertFalse(
            "the address is not optional once that provider is chosen",
            s(R.string.field_server_url).lowercase().contains("optional"),
        )
    }

    /** The Space is a proper noun in this product, and was lower-case in two strings of three. */
    @Test fun `the Space is capitalised everywhere it is named`() {
        listOf(
            R.string.action_space,
            R.string.action_leave_space,
            R.string.state_space_starting,
            R.string.state_space_failed,
        ).map { s(it) }.forEach { line ->
            assertFalse("the Space is lower-case in: $line", line.contains(" space"))
            assertTrue("this string stopped naming the Space: $line", line.contains("Space"))
        }
    }
}

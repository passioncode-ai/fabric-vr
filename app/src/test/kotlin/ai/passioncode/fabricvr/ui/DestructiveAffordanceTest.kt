package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.theme.FabricTheme
import ai.passioncode.fabricvr.common.theme.Tokens
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **`B-154`'s redesign half: a destructive action a ray can hit by accident is the defect.**
 *
 * `B-18` gave every control a 72 dp floor and `ControlFloorTest` holds it. Three things were
 * left, and all three are about what sits *next to* what rather than how big it is:
 *
 * 1. **Chips had no vertical spacing at all.** Every `FlowRow` on this product declared
 *    `horizontalArrangement` and nothing else, so `Arrangement.Top` gave **0 dp** between wrapped
 *    lines — and the chips are 72 dp tall because a ray cannot hold a smaller one. Ten language
 *    chips in a panel wrap; two 72 dp targets then share an edge, and the miss is silent because
 *    both are legal taps.
 * 2. ***Delete* and *Delete recording* sat 8 dp from *Play* and *Transcribe again*** on
 *    `TodayScreen`'s `NoteRow` — the two most frequent controls on the row beside the two that
 *    cannot be taken back. `Copy` has had `Tokens.Space.l` of clear air since it was written, for
 *    exactly this reason, and the buttons underneath had none of it.
 * 3. **`Tokens.Palette.danger` was defined and used on no control at all** — only as Material's
 *    `error` role in the theme. A palette entry nothing renders is a decision nobody took.
 *
 * Asserted as geometry and as a tree scan rather than reviewed, because "these are far enough
 * apart" is the kind of claim that is true on the day it is written.
 */
@RunWith(AndroidJUnit4::class)
class DestructiveAffordanceTest {

    @get:Rule val compose = createComposeRule()

    private fun s(@StringRes id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun at(width: Int, height: Int, content: @Composable () -> Unit) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp)),
            ) {
                FabricTheme { content() }
            }
        }
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("no repository root above ${System.getProperty("user.dir")}")
        }
        return dir
    }

    private fun screens(): List<File> =
        File(repoRoot(), "app/src/main/kotlin/ai/passioncode/fabricvr/ui")
            .listFiles { f -> f.isFile && f.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }

    private fun voiceNote() = Note(
        id = "n1",
        title = "мысль",
        body = "мысль",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        audioPath = "/tmp/rec.wav",
        transcript = Transcript("мысль", "ru", SttSource.LOCAL, "whisper-base", 900),
    )

    // ---- 1. a wrapped row of targets has a gap in both directions ---------------------------

    /**
     * The rule as a scan, in `ControlFloorTest`'s shape and for its reason: fixing eleven call
     * sites is not the deliverable, because the twelfth is written next week by somebody reading
     * a screen rather than `Controls.kt`. A bare `FlowRow` takes `Arrangement.Top` for its cross
     * axis, which is zero, and zero between two 72 dp targets is the whole finding.
     */
    @Test fun `no screen wraps controls in a row with no gap between the lines`() {
        val screenFiles = screens()
        assertTrue("the scan found no screens — a green from an empty list proves nothing", screenFiles.size >= 5)

        val offenders = screenFiles.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                BARE_FLOW_ROW.containsMatchIn(line) &&
                    !line.trimStart().startsWith("//") &&
                    !line.trimStart().startsWith("*")
            }.map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }

        assertEquals(
            "these wrap with `Arrangement.Top` between the lines, which is 0 dp between two " +
                "72 dp targets — use `FabricWrapRow` from `core-common/.../ui/Controls.kt`:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /** A scan that matches nothing prints the same silence as a clean tree. */
    @Test fun `the wrap scan catches a planted row and leaves the wrapper alone`() {
        assertTrue(
            "the pattern stopped matching the row it exists to find",
            BARE_FLOW_ROW.containsMatchIn("FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {"),
        )
        assertTrue(
            "the pattern matched the wrapper it must leave alone",
            !BARE_FLOW_ROW.containsMatchIn("FabricWrapRow {") &&
                !BARE_FLOW_ROW.containsMatchIn("FabricWrapRow(modifier = Modifier.weight(1f)) {"),
        )
    }

    // ---- 2. the irreversible actions are a gap away from the frequent ones -------------------

    /**
     * **Measured on the rendered row, not argued.**
     *
     * The gap asserted is `Tokens.Space.l`, which is not a new number: it is the clear air
     * `Copy` has carried since it was written — *"the action taken most often and the one whose
     * neighbours must never be hit instead"* — and the two controls that cannot be taken back
     * are owed the same treatment from the other side.
     */
    @Test fun `an irreversible action is a gap away from the frequent ones on a note row`() {
        at(1024, 2400) {
            TodayFrame(
                state = NotesUiState(loading = false, notes = listOf(voiceNote()), total = 1),
                voice = { VoiceState.Idle },
            )
        }
        assertGapHolds()
    }

    /**
     * **And at the width the panel may actually be shrunk to** (`PanelMinimum.WIDTH_DP`).
     *
     * A gap that only exists because the panel is wide is not a gap. The two groups are stacked,
     * so the `Column`'s `spacedBy(Tokens.Space.l)` is the separation at any size — but that is
     * the claim, and a claim about geometry is what this file exists to measure rather than
     * restate. The panel may be shrunk to 480 dp, and `B-11` is the last time this product
     * assumed a size.
     */
    @Test fun `the gap holds at the panel's declared minimum width`() {
        at(PanelMinimum.WIDTH_DP, 2400) {
            TodayFrame(
                state = NotesUiState(loading = false, notes = listOf(voiceNote()), total = 1),
                voice = { VoiceState.Idle },
            )
        }
        assertGapHolds()
    }

    /**
     * **The floor is computed at the COMPOSITION's density, not the rule's** — and that is not a
     * detail, it is the difference between a measurement and a number.
     *
     * `DeviceConfigurationOverride.ForcedSize` does not resize Robolectric's root: it scales the
     * density so a 198 px root believes it is 1024 dp wide, which here is `0.19375`, while
     * `compose.density` still answers `1.0`. The first version of this assertion took the rule's,
     * so it compared a gap in root pixels against a floor in device-independent ones and called a
     * correct 24 dp layout "5 px". It then diagnosed a layout collapse that was not there and a
     * whole arrangement was rewritten around it. `DEC-0043` records the neighbouring trap — this
     * harness's **text** metrics do not scale with the forced density either, which is why the
     * distance asserted is between controls rather than a claim about their size.
     */
    private fun assertGapHolds() {
        val frequentNodes = listOf(R.string.action_play_recording, R.string.action_redo_stt)
            .map { id -> compose.onAllNodesWithText(s(id)).onFirst().fetchSemanticsNode() }
        val destructiveNodes = listOf(R.string.action_delete, R.string.action_delete_recording)
            .map { id -> compose.onAllNodesWithText(s(id)).onFirst().fetchSemanticsNode() }

        val laidOutAt = destructiveNodes.first().layoutInfo.density
        val floor = with(laidOutAt) { Tokens.Space.l.toPx() }
        assertTrue(
            "the composition was laid out at the rule's density, so this measurement is in the " +
                "wrong units and proves nothing",
            floor > 0f,
        )

        destructiveNodes.map { it.boundsInRoot }.forEach { danger ->
            frequentNodes.map { it.boundsInRoot }.forEach { safe ->
                val horizontal = maxOf(danger.left - safe.right, safe.left - danger.right)
                val vertical = maxOf(danger.top - safe.bottom, safe.top - danger.bottom)
                val apart = maxOf(horizontal, vertical)
                assertTrue(
                    "an irreversible control at $danger is only $apart px from a frequent one at " +
                        "$safe; the floor is $floor px (Tokens.Space.l at density " +
                        "${laidOutAt.density})",
                    apart >= floor - HALF_PIXEL,
                )
            }
        }
    }

    // ---- 3. the danger colour reaches a control ----------------------------------------------

    /**
     * `Tokens.Palette.danger` existed and was rendered by nothing but Material's `error` role,
     * which is a colour the product hands to the framework rather than a decision it takes. A
     * destructive control that looks exactly like a safe one is the visual half of the same
     * finding as the 8 dp gap.
     *
     * Asserted against the shared wrapper rather than against a pixel, because the wrapper is
     * what the next destructive control will reach for — and a pixel assertion would be a test of
     * Material's tint pipeline instead of of this product's choice.
     */
    @Test fun `the danger colour is carried by a control the screens can call`() {
        val controls = File(
            repoRoot(),
            "core-common/src/main/kotlin/ai/passioncode/fabricvr/common/ui/Controls.kt",
        )
        assertTrue("Controls.kt is not where this test looks: $controls", controls.isFile)
        val text = controls.readText()

        assertTrue(
            "no shared control renders `Tokens.Palette.danger`, so the token is a decision " +
                "nobody took (B-154)",
            text.contains("Palette.danger"),
        )
        assertTrue(
            "there is no `FabricDangerButton` for a screen to reach for",
            Regex("""fun FabricDangerButton\(""").containsMatchIn(text),
        )
    }

    /** And the two irreversible controls on a note row actually use it. */
    @Test fun `the note row's irreversible controls use the danger wrapper`() {
        val row = File(
            repoRoot(),
            "app/src/main/kotlin/ai/passioncode/fabricvr/ui/TodayScreen.kt",
        ).readText()

        listOf("R.string.action_delete)", "R.string.action_delete_recording)").forEach { label ->
            val at = row.indexOf(label)
            assertTrue("the note row no longer renders $label", at > 0)
            val opening = row.lastIndexOf("Fabric", at)
            assertEquals(
                "the control rendering $label is `${row.substring(opening, at).takeWhile { it != '(' }}`, " +
                    "not the danger wrapper",
                "FabricDangerButton",
                row.substring(opening, at).takeWhile { it != '(' },
            )
        }
    }

    private companion object {
        /**
         * A `FlowRow` written at the call site. `FabricWrapRow` does not match, because the
         * lookbehind refuses a name that merely ends in these letters — the same guard
         * `ControlFloorTest` uses to leave `FabricChip` alone.
         */
        val BARE_FLOW_ROW = Regex("""(?<![A-Za-z])FlowRow\s*\(""")

        /** Rounding slack: a layout distance is computed in floats and lands a hair short. */
        const val HALF_PIXEL = 0.5f
    }
}

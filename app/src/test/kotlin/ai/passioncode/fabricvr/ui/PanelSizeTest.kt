package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.common.theme.Tokens
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest promises the shell a minimum panel size, and the shell lets a person resize to it.
 *
 * `B-11` is what happens when that promise is larger than the layout can serve: the fixed chrome
 * overflows, the `LazyColumn` is measured with nothing, and the notes are unreachable with no
 * scroll to reach them. The fix is in two files — the frame, and the number the manifest declares
 * — and two numbers in two files drift. This test is the thing that says so.
 */
class PanelSizeTest {

    private val manifest: String by lazy {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("the manifest is not where this test looks: ${file.absolutePath}", file.isFile)
        file.readText()
    }

    private fun declared(attribute: String): Int {
        val match = Regex("""android:$attribute="(\d+)dp"""").find(manifest)
        requireNotNull(match) { "the manifest declares no android:$attribute" }
        return match.groupValues[1].toInt()
    }

    @Test fun `the declared minimum is the one the frame is built for`() {
        assertEquals(PanelMinimum.WIDTH_DP, declared("minWidth"))
        assertEquals(PanelMinimum.HEIGHT_DP, declared("minHeight"))
    }

    /**
     * The derivation, as arithmetic rather than as a sentence in a doc comment.
     *
     * If somebody raises the status slot for a taller state, or the record button for a bigger
     * target, this fails and the manifest has to be raised with it — which is exactly the step
     * that was missed when the slot was introduced.
     */
    /**
     * **Read from the tokens, not typed.** The first version of this test hard-coded every
     * number, so the drift its own KDoc promised to catch could not fire: step 8's verification
     * set `Tokens.Space.statusSlot` to 260 dp — fixed chrome 548 dp against a declared 560 dp
     * minimum, which is `B-11` again — and this test stayed green while a different one went
     * red. A guard that cannot fail is worse than no guard, because its green is quoted.
     *
     * What the sum buys is stated exactly: the fixed chrome plus **the shortest a note row can
     * be**, which is its Copy button and the row's own padding. A row with a title, a time and
     * an action button is taller, and at the minimum a person scrolls to reach the rest of it —
     * `TodayFrameTest` measures that, and `DEC-0043` says so in those words.
     */
    @Test fun `the minimum holds the fixed chrome and the shortest note row`() {
        val padding = Tokens.Space.l.value.toInt() * 2
        val header = Tokens.Space.iconButton.value.toInt()
        val slot = Tokens.Space.statusSlot.value.toInt()
        val button = Tokens.Space.captureButtonHeight.value.toInt()
        // **`REQ-060` added a fifth child and a fourth gap.** The pinned notice slot sits
        // between the record button and the list, bounded at `NOTICE_SLOT_MAX` and scrolling
        // inside; it is what makes *Undo* reachable after deleting the thirtieth note. It is
        // counted at its **maximum**, because the case the budget is about is the one where a
        // banner and an undo line are both up — at which point an uncounted slot leaves the
        // `LazyColumn` nothing to measure with, which is `B-11` exactly.
        val notices = NOTICE_SLOT_MAX.value.toInt()
        val gaps = Tokens.Space.m.value.toInt() * 4
        val shortestRow = Tokens.Space.copyButtonHeight.value.toInt() + Tokens.Space.m.value.toInt() * 2
        val needed = padding + header + slot + button + notices + gaps + shortestRow

        assertTrue(
            "the declared minimum height ${PanelMinimum.HEIGHT_DP} cannot hold $needed dp of frame",
            PanelMinimum.HEIGHT_DP >= needed,
        )
    }

    /**
     * A default the shell cannot honour is the same class of defect as a minimum the frame
     * cannot serve: `defaultHeight` is what the panel opens at, and opening below the declared
     * floor is a contradiction the shell resolves however it likes.
     *
     * It fired for real: `REQ-060` raised the minimum to 736 dp while the default still said
     * 640.
     */
    @Test fun `the default panel is at least the declared minimum`() {
        assertTrue(
            "defaultWidth ${declared("defaultWidth")} is below minWidth ${declared("minWidth")}",
            declared("defaultWidth") >= declared("minWidth"),
        )
        assertTrue(
            "defaultHeight ${declared("defaultHeight")} is below minHeight ${declared("minHeight")}",
            declared("defaultHeight") >= declared("minHeight"),
        )
    }
}

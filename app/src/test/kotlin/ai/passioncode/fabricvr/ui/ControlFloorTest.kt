package ai.passioncode.fabricvr.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `B-18`'s 72 dp floor, as a rule the suite enforces rather than a sentence in `Controls.kt`.
 *
 * `B-154` is what happens without this: `Controls.kt` says *"the screens call these"*, and when
 * step 8's verification counted, only `ErrorBanner` and `TodayScreen` did. Settings drew seven raw
 * `FilterChip`s (32 dp by default) and eight raw `TextButton`s, Search its Back, the editor two,
 * Licences its Back. Every one of them is a target a controller ray has to hold steady on, and
 * every one was below the floor the project decided on.
 *
 * Fixing nineteen call sites is not the deliverable — the twentieth is written next week by
 * somebody reading `SettingsScreen.kt` rather than `Controls.kt`. This is the deliverable, in the
 * same shape [ai.passioncode.fabricvr.MainThreadPolicyTest] uses for R2: scan the tree, name every
 * offender, and fail with the list.
 *
 * **`Button` used to be excluded, on a fact that expired** (`B-221`). The exclusion read: "every
 * `Button` in this tree already carries an explicit height — the 128 dp record control, the
 * `copyButtonHeight` on a row, Settings' save actions." True when written; five call sites later
 * Settings drew *Download*, *Save* (cloud) and *Save* (server) at Material's 40 dp, under Meta's
 * 48 dp minimum and well under the 60 dp it recommends for a primary action — and the scan whose
 * whole job is this could not see them. **An exclusion justified by a fact outlives the fact.**
 *
 * So `Button` and `Switch` are in the set, and the rule that replaces the exclusion is the one the
 * old KDoc was actually asserting: a bare control is an offender **unless its own call carries an
 * explicit height**. That keeps the 128 dp record control and the copy button, which were the real
 * reason for the carve-out, and refuses the next Settings action that forgets.
 *
 * `Switch` is here for a different reason and the fix was different: Material's is 52x32 dp, the
 * 72 dp `heightIn` around it belonged to a `Row` that was not clickable, so a controller ray had
 * to land on the thumb. Both are `toggleable` rows now — one target, one announcement.
 */
class ControlFloorTest {

    /** Material controls whose bare use accepts a default below the floor. */
    private val bare = Regex("""(?<![A-Za-z])(TextButton|OutlinedButton|FilterChip|IconButton)\s*\(""")

    /**
     * Controls whose bare use is an offender **only when the call states no height of its own**.
     * `Button` is the 128 dp record control as often as it is a 40 dp default, and `Switch` is
     * legitimate inside a `toggleable` row that carries the target.
     */
    private val heightBearing = Regex("""(?<![A-Za-z])(Button|Switch)\s*\(""")

    /** A height on the call itself, or on the row that made itself the control. */
    private val statesHeight = Regex("""\.height(In)?\(|toggleable\(""")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("no repository root above ${System.getProperty("user.dir")}")
        }
        return dir
    }

    /** The index just past the `)` that closes the `(` at [open]; the end of the text if unbalanced. */
    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return text.length
    }

    /**
     * Whether the control at [at] sits inside a layout whose own modifier made it the target.
     *
     * Bounded by the nearest enclosing `Row(` / `Column(` / `Box(` above it, so a `toggleable` on
     * some unrelated earlier row cannot rescue this one.
     */
    private fun ownedByToggleableRow(text: String, at: Int): Boolean {
        val layout = Regex("""(?<![A-Za-z])(Row|Column|Box)\s*\(""")
        val opener = layout.findAll(text.take(at)).lastOrNull() ?: return false
        return text.substring(opener.range.first, at).contains("toggleable(")
    }

    private fun isComment(line: String): Boolean =
        line.trimStart().startsWith("//") || line.trimStart().startsWith("*")

    private fun screens(): List<File> =
        File(repoRoot(), "app/src/main/kotlin/ai/passioncode/fabricvr/ui")
            .listFiles { f -> f.isFile && f.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }

    @Test fun `no screen draws a Material control at its own default height`() {
        val screenFiles = screens()
        assertTrue("the scan found no screens — a green from an empty list proves nothing", screenFiles.size >= 5)

        val offenders = screenFiles.flatMap { file ->
            val text = file.readText()
            val lines = file.readLines()

            val plain = lines.withIndex().filter { (_, line) ->
                bare.containsMatchIn(line) && !isComment(line)
            }.map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }

            // **The call's own argument list, matched by bracket rather than by a character
            // count.** A `Button(`'s `modifier` sits several lines below it, often behind a
            // paragraph of comment, and the first two versions of this check used a fixed window —
            // 600 characters, then 500 back and 900 forward — and reported correct call sites both
            // times, because the real distances here are 1 089 and 813. A number tuned until the
            // tree passes is a number the next screen breaks.
            //
            // So: the forward extent is the call's matching close-paren, exactly. The backward
            // extent is the nearest enclosing `Row(` / `Column(` / `Box(`, because a `Switch` is
            // legitimate when the ROW carries the target — `toggleable` with `Role.Switch` — and
            // that text is above the match, not below it.
            val windowed = heightBearing.findAll(text)
                .filter { m -> !isComment(lines[text.take(m.range.first).count { it == '\n' }]) }
                .filter { m ->
                    val open = text.indexOf('(', m.range.first)
                    !statesHeight.containsMatchIn(text.substring(open, matchingParen(text, open)))
                }
                .filter { m -> !ownedByToggleableRow(text, m.range.first) }
                .map { m ->
                    val line = text.take(m.range.first).count { it == '\n' } + 1
                    "${file.name}:$line: ${lines[line - 1].trim()}"
                }
                .toList()

            plain + windowed
        }

        assertEquals(
            "these controls are below `Tokens.Space.controlHeight` — use the wrappers in " +
                "`core-common/.../ui/Controls.kt`:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The other half, and the one this project keeps relearning: a scan that matches nothing
     * prints the same silence as a clean tree. This proves the pattern still catches what it was
     * written for.
     */
    @Test fun `the scan catches a planted control`() {
        val planted = """
            FilterChip(selected = false, onClick = {}, label = { Text("x") })
            TextButton(onClick = onBack) { Text("back") }
        """.trimIndent()

        assertEquals(
            "the pattern stopped matching the controls it exists to find",
            2,
            planted.lines().count { bare.containsMatchIn(it) },
        )
        assertEquals(
            "the height-bearing pattern stopped matching the controls `B-221` found",
            2,
            "Button(onClick = {}) { Text(\"x\") }\nSwitch(checked = c, onCheckedChange = null)"
                .lines().count { heightBearing.containsMatchIn(it) },
        )
        assertTrue(
            "the pattern matched a wrapper it must leave alone",
            !bare.containsMatchIn("FabricTextButton(onClick = onBack) { Text(\"back\") }") &&
                !bare.containsMatchIn("FabricChip(selected = false, onClick = {}, label = \"x\")") &&
                !bare.containsMatchIn("FabricIconButton(onClick = {}, icon = i, contentDescription = \"c\")") &&
                !heightBearing.containsMatchIn("FabricButton(onClick = {}) { Text(\"x\") }"),
        )
    }
}

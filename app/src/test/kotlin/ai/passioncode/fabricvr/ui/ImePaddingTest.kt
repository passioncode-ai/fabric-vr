package ai.passioncode.fabricvr.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen that has a text field leaves room for the keyboard (`B-222`, `DEC-0087`).
 *
 * The audit found **zero** `imePadding`, `ImeAction`, `WindowInsets.ime` or `BringIntoViewRequester`
 * anywhere, across seven fields on three screens — and `SearchScreen` raises the keyboard on entry
 * with the results list directly under it.
 *
 * **Why this is worth a gate even where it may be a no-op.** Horizon OS can present its keyboard as
 * a spatial overlay in front of the person, and on that surface the panel has no IME inset at all.
 * The same build also runs as a 2D window in the shell, where the keyboard *is* an inset — and
 * whether either declaration in the manifest is what makes the system keyboard attach to a Compose
 * panel is unmeasured and on the board as a device question (`B-050`). A screen that omits the
 * padding is wrong on one surface and unchanged on the other, so the cheap correct thing is to
 * have it everywhere and say plainly that half of it is unverified here.
 */
class ImePaddingTest {

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

    private val field = Regex("""(?<![A-Za-z])(OutlinedTextField|BasicTextField|TextField)\s*\(""")

    @Test fun `every screen with a text field leaves room for the keyboard`() {
        val files = screens()
        assertTrue("the scan found no screens — a green from an empty list proves nothing", files.size >= 5)

        val withFields = files.filter { f ->
            f.readLines().any { field.containsMatchIn(it) && !it.trimStart().startsWith("//") }
        }
        assertTrue(
            "no screen in this tree draws a text field, which contradicts the finding this test " +
                "exists for — the pattern has stopped matching",
            withFields.size >= 3,
        )

        val missing = withFields.filter { !it.readText().contains("imePadding()") }

        assertEquals(
            "these screens draw a text field and reserve no room for the keyboard:\n" +
                missing.joinToString("\n") { it.name },
            emptyList<String>(),
            missing.map { it.name },
        )
    }

    /**
     * And the one field a person arrives at with the keyboard already up says what its action key
     * does. Search runs on every keystroke, so pressing it dismisses the keyboard rather than
     * re-running a search whose results are already on screen.
     */
    @Test fun `the search field declares its action key`() {
        val search = File(
            repoRoot(),
            "app/src/main/kotlin/ai/passioncode/fabricvr/ui/SearchScreen.kt",
        ).readText()

        assertTrue(
            "SearchScreen auto-focuses its field and declares no ImeAction, so the keyboard's " +
                "action key does whatever the platform defaults to",
            search.contains("ImeAction.Search"),
        )
        assertTrue(
            "the action key is declared and does nothing",
            search.contains("onSearch ="),
        )
    }
}

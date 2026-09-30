package ai.passioncode.fabricvr.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A geometry assertion takes its density from the node, never from the test rule** (`B-208`).
 *
 * Under `ForcedSize` the composition is laid out at the forced density — 0.19375 on the case that
 * found this — while the rule's `density` still answers 1.0. A correct 24 dp control measured
 * through the rule's density read as *5 px*, and an arrangement was rewritten around the artefact
 * before anybody noticed (`B-154`, 2026-09-22). The rule was a comment in one test and a line in
 * `docs/modules/app.md`, which is `SI-02`'s shape: a sentence predicting a failure, beside code that
 * nothing checks. `assertHeightIsAtLeast` and friends already read `layoutInfo.density`; the one
 * spelling that does not is `<rule>.density`, and it is refused here.
 *
 * Code lines only — the KDoc that explains the trap names the forbidden spelling, and must.
 */
class GeometryDensityPolicyTest {

    private val forbidden = Regex("""\b(compose|composeRule|composeTestRule|rule)\s*\.\s*density\b""")

    private fun codeLines(file: File): List<Pair<Int, String>> =
        file.readLines().mapIndexedNotNull { i, line ->
            val t = line.trim()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else (i + 1) to line
        }

    @Test fun `no test measures a laid-out node with the rule's density`() {
        // Inside this module only — `CrossModuleTestInputsTest` refuses a read Gradle does not track.
        val tests = File("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "GeometryDensityPolicyTest.kt" }
            .toList()
        // The canary: the scan read the tree, and it can see a geometry test at all.
        assertTrue("the scan found no geometry test — it read nothing", tests.any { it.name == "DestructiveAffordanceTest.kt" })

        val hits = tests.flatMap { f ->
            codeLines(f).filter { (_, line) -> forbidden.containsMatchIn(line) }.map { (n, line) -> "${f.name}:$n  ${line.trim()}" }
        }
        assertEquals(
            "take the density from `node.layoutInfo.density` (B-208) — the rule's is 1.0 under ForcedSize:\n" +
                hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )
    }

    @Test fun `the pattern catches the spelling it exists for`() {
        assertTrue(forbidden.containsMatchIn("val px = with(compose.density) { 24.dp.toPx() }"))
        assertTrue(forbidden.containsMatchIn("composeTestRule . density"))
        assertTrue(!forbidden.containsMatchIn("node.layoutInfo.density"))
    }
}

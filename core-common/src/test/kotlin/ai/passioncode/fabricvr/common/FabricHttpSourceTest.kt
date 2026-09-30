package ai.passioncode.fabricvr.common

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Every `OkHttpClient` in this repository is built by [FabricHttp], and this is what makes a
 * fourth one impossible to write quietly** (`B-197`).
 *
 * `FabricHttpTest` in `:feature-stt` asserts the same rule the strong way — it holds the three real
 * clients and looks for [NetworkPolicyInterceptor] in `networkInterceptors`. It could only ever see
 * the clients its own module links, and that is exactly how `B-197` happened: `DEC-0077` put the
 * hop rule on all three clients a `:feature-stt` test can reach, and [OpenRouterClient] in
 * `:feature-assistant` — a fourth client, in a module that test cannot import — followed redirects
 * unchecked for another two days. A per-module wiring assertion cannot answer a tree-wide question.
 *
 * So this one reads the **source of every module**, which is the only vantage point from which the
 * question *are there any others?* has an answer. It is a text scan and it says so: it proves that
 * no module constructs an OkHttp client outside the factory, and it proves nothing about what the
 * factory then puts on it. The two tests are the evidence together and neither is it alone.
 *
 * **A scan that matches nothing prints the same silence as a clean tree**, which is the failure
 * this repository has been bitten by in three separate gates (`check-secrets.sh`,
 * `check-device-gate.sh`, `check-seams.sh`). Every case below therefore carries a canary: a planted
 * construction that must be seen, and a correct line that must not be.
 */
class FabricHttpSourceTest {

    /**
     * Constructing an OkHttp client, in the two spellings Kotlin allows.
     *
     * Deliberately NOT a word-boundary regex — POSIX and Java disagree about `\b` around `.`, and a
     * pattern that silently matches nothing is the whole failure mode this class exists to avoid.
     */
    private val construction = Regex("""OkHttpClient\s*\(|OkHttpClient\.Builder\s*\(""")

    /** A comment is allowed to NAME the thing it explains; the KDoc on the factory is full of it. */
    private fun isComment(line: String): Boolean =
        line.trimStart().startsWith("*") || line.trimStart().startsWith("//") ||
            line.trimStart().startsWith("/*")

    private fun hits(text: String): List<String> =
        text.lineSequence()
            .withIndex()
            .filterNot { (_, line) -> isComment(line) }
            .filter { (_, line) -> construction.containsMatchIn(line) }
            .map { (i, line) -> "${i + 1}: ${line.trim()}" }
            .toList()

    /**
     * The repository root, found by walking up for the file that defines it.
     *
     * A test that cannot find the tree it is supposed to scan must FAIL rather than pass over an
     * empty list — the exact shape of a green that means nothing.
     */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("no settings.gradle.kts above ${File("").absolutePath}: this scan could not run")
    }

    private fun mainSources(root: File): List<File> =
        root.listFiles { f: File -> f.isDirectory && File(f, "src/main/kotlin").isDirectory }
            .orEmpty()
            .flatMap { module -> File(module, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" } }

    /** The canary: the scan must see a planted construction and must not see a correct line. */
    @Test fun `the scan can fail`() {
        assertEquals(
            "a planted OkHttpClient construction was not seen — this scan proves nothing",
            1,
            hits("val c = OkHttpClient.Builder().build()").size,
        )
        assertEquals(
            "a planted bare construction was not seen — this scan proves nothing",
            1,
            hits("val c = OkHttpClient()").size,
        )
        assertEquals(
            "the scan reds on a correct line, so it would be switched off rather than obeyed",
            0,
            hits(
                """
                | * Built through [FabricHttp], so OkHttpClient.Builder is never called here.
                |// OkHttpClient() would bypass the rule
                |val c = FabricHttp.builder().build()
                """.trimMargin(),
            ).size,
        )
    }

    /**
     * The rule itself. One file may construct a client — the factory — and it is named rather than
     * pattern-matched, so moving the factory is a decision somebody makes here rather than a hole
     * that opens by accident.
     */
    @Test fun `no module builds an OkHttpClient outside the factory`() {
        val root = repoRoot()
        val sources = mainSources(root)
        assertTrue(
            "the scan found no Kotlin sources under ${root.absolutePath} — it proves nothing",
            sources.size > 20,
        )

        val offenders = sources
            .filter { it.name != FACTORY }
            .mapNotNull { file ->
                val found = hits(file.readText())
                if (found.isEmpty()) null else "${file.relativeTo(root)}\n    " + found.joinToString("\n    ")
            }

        assertTrue(
            "a module builds its own OkHttpClient and the DEC-0005 hop rule is not on it " +
                "(B-197 was exactly this, in :feature-assistant). Build it through " +
                "FabricHttp.builder():\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** And the factory is where it is claimed to be, so the exemption above is not a dead name. */
    @Test fun `the factory exists and is the one place the client is constructed`() {
        val root = repoRoot()
        val factory = mainSources(root).filter { it.name == FACTORY }
        assertEquals("there is not exactly one $FACTORY in the tree: $factory", 1, factory.size)
        assertTrue(
            "$FACTORY does not construct a client, so the exemption above hides nothing",
            hits(factory.single().readText()).isNotEmpty(),
        )
    }

    private companion object {
        const val FACTORY = "FabricHttp.kt"
    }
}

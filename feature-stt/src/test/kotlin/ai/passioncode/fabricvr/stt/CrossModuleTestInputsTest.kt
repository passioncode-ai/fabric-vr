package ai.passioncode.fabricvr.stt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`B-204`. A test that reads a file its Gradle task does not know about can be `UP-TO-DATE`
 * while the thing it scans changes — and a check that did not run prints exactly what a check
 * that passed prints.**
 *
 * `B-197` is the instance. `FabricHttpSourceTest` in `:core-common` reads every module's sources
 * to prove no module builds its own `OkHttpClient`; a unit test's declared inputs are its own
 * module's classpath, so planting the defect back into `:feature-assistant` left
 * `:core-common:testDebugUnitTest` untouched and the scan simply did not run. That one task now
 * declares the tree (`core-common/build.gradle.kts`).
 *
 * **This is the general case, and it was not a one-off.** Measured on 2026-09-22 by planting,
 * running and reverting:
 *
 * | test | reads | measured |
 * |---|---|---|
 * | `MainThreadPolicyTest` | `tools/check_main_thread.py`, and through it `app`, `core-notes` and **`feature-assistant`** main sources | an R2 violation planted in `feature-assistant` left `:app:testDebugUnitTest` `UP-TO-DATE` and green while the checker run by hand exited 1 |
 * | `RedactionParityTest` | `scripts/check-secrets.sh` | a new credential shape added to the gate left `:core-common:testDebugUnitTest` `UP-TO-DATE`, so the drift detector did not detect the drift |
 *
 * `:feature-assistant` is the same module both times, and not by chance: `DEC-0020` keeps it out
 * of `:app`'s dependencies, so nothing it contains reaches any other module's test classpath.
 * Four more escapes were measured **safe** and are recorded as such in their module's build file
 * rather than papered over with an input nobody needs.
 *
 * ## What this check is, and what it is not
 *
 * It is a **ratchet, not a proof.** Deciding whether an arbitrary test reads outside its module
 * would mean deciding what a path expression evaluates to, and that is not decidable from source.
 * What is decidable is the two halves below, and both can fail:
 *
 * 1. a unit-test source containing one of [MARKERS] — the escape idioms this tree has actually
 *    used — must be named in its module's `build.gradle.kts` by a `reads-outside-module:` line,
 *    with a verdict of `declared` or `safe` and a reason;
 * 2. a `reads-outside-module:` line naming a class that no longer carries any marker must be
 *    deleted, so the registry cannot rot into a list of names nobody re-reads.
 *
 * **What it cannot see, stated rather than implied:** a test that escapes its module by a route
 * the marker list does not name — an environment variable, a path assembled at run time, a
 * resource pulled off the classpath — is invisible to it, and so is a marker that appears only in
 * prose. The first is a false negative and the second a false positive; the false positive costs
 * one registry line and the false negative costs what `B-197` cost. The list is widened when the
 * tree invents a new idiom, in the same change as the idiom.
 *
 * **Why it lives in `:feature-stt` and not beside the other tree-wide scanners in `:app`.** It
 * scans every module's `src/test` and every `build.gradle.kts`, and by its own rule the task that
 * runs it must declare that tree — so whichever module hosts it re-runs its whole suite whenever
 * any test file anywhere changes. `:app` is the slowest suite in this build (Robolectric, twenty-
 * nine composed frames) and already carries the tree-wide main sources for `MainThreadPolicyTest`;
 * `:feature-stt` answers in seconds. The subject here is the build's input declarations, not any
 * one module's behaviour, so the cheap host is the right one.
 */
class CrossModuleTestInputsTest {

    /**
     * The escape idioms in this tree, each one taken from a test that actually uses it.
     *
     * `settings.gradle.kts` is the repository-root walk-up every tree-wide scanner here opens
     * with; `File("../` and `File("src/` are the two relative escapes from a module directory,
     * which is a `Test` task's working directory; `ProcessBuilder(` shells out to a checker whose
     * own inputs Gradle cannot see; `rootDir`/`rootProject` are the Gradle-side spellings; and
     * `ls-files` is the git-side one, unused today and listed because it is the next one somebody
     * reaches for.
     */
    private val markers = MARKERS

    private fun escapes(source: String): List<String> = markers.filter { it in source }

    /** One public test class per file, named after the file — this tree's convention throughout. */
    private fun escapingClasses(module: File): Map<String, List<String>> =
        File(module, "src/test").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val found = escapes(file.readText())
                if (found.isEmpty()) null else file.nameWithoutExtension to found
            }
            .toMap()

    /** `// reads-outside-module: <Class> — <declared|safe>: <reason>` */
    private fun registry(buildFile: String): Map<String, Pair<String, String>> =
        ENTRY.findAll(buildFile).associate { m ->
            m.groupValues[1] to (m.groupValues[2] to m.groupValues[3].trim())
        }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "no settings.gradle.kts above ${File("").absolutePath}" }
    }

    private fun modules(root: File): List<File> =
        root.listFiles { f: File -> f.isDirectory && File(f, "src/test").isDirectory }
            .orEmpty()
            .sortedBy { it.name }

    // ---------------------------------------------------------------------------------------
    // The canaries. `SI-06`: a scan that matches nothing prints the same silence as a clean tree,
    // and this project has shipped five checks that could not fail. These run on strings, so they
    // answer for the matcher itself rather than for whatever happens to be in the tree today.
    // ---------------------------------------------------------------------------------------

    @Test fun `the marker scan sees every idiom it claims to see`() {
        val planted = mapOf(
            "settings.gradle.kts" to """while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile""",
            """File("../""" to """private val notice = File("../NOTICE")""",
            """File("src/""" to """val manifest = File("src/main/AndroidManifest.xml")""",
            "ProcessBuilder(" to """ProcessBuilder("python3", script.path).start()""",
            "rootDir" to """val root = rootDir.resolve("scripts")""",
            "rootProject" to """val gate = rootProject.file("scripts/check-secrets.sh")""",
            "ls-files" to """ProcessBuilder("git", "ls-files").start()""",
        )
        assertEquals(
            "a marker has no planted example, so nothing proves the scan can see it",
            markers.toSet(),
            planted.keys,
        )
        planted.forEach { (marker, line) ->
            assertTrue("the scan did not see `$marker` in: $line", marker in escapes(line))
        }
    }

    @Test fun `the marker scan stays quiet on a test that stays in its module`() {
        assertEquals(
            "the scan reds on a test that reads nothing outside itself — it would be switched " +
                "off rather than obeyed",
            emptyList<String>(),
            escapes(
                """
                |class VaultAudioTest {
                |    @get:Rule val temp = TemporaryFolder()
                |    fun `a scratch recording is moved into the vault`() {
                |        val scratch = File(temp.newFolder("audio"), "rec-1.wav")
                |    }
                |}
                """.trimMargin(),
            ),
        )
    }

    @Test fun `the registry parser reads an entry and refuses a malformed one`() {
        val parsed = registry(
            """
            |// reads-outside-module: MainThreadPolicyTest — declared: tools/check_main_thread.py
            |// reads-outside-module: LicencesTest — safe: NOTICE reaches the merged assets
            |// reads-outside-module: Broken — maybe: not one of the two verdicts
            |// reads-outside-module: AlsoBroken — safe:
            """.trimMargin(),
        )
        assertEquals(
            "the parser did not read exactly the two well-formed entries: ${parsed.keys}",
            setOf("MainThreadPolicyTest", "LicencesTest"),
            parsed.keys,
        )
        assertEquals("declared", parsed.getValue("MainThreadPolicyTest").first)
        assertEquals("NOTICE reaches the merged assets", parsed.getValue("LicencesTest").second)
    }

    // ---------------------------------------------------------------------------------------
    // The rule.
    // ---------------------------------------------------------------------------------------

    @Test fun `every test that reads outside its module is accounted for in its build file`() {
        val root = repoRoot()
        val modules = modules(root)
        assertTrue("no modules with unit tests were found under $root — this proves nothing", modules.size >= 5)

        val unaccounted = mutableListOf<String>()
        val dead = mutableListOf<String>()
        val undeclared = mutableListOf<String>()
        var checked = 0

        modules.forEach { module ->
            val buildFile = File(module, "build.gradle.kts")
            assertTrue("${module.name} has unit tests and no build file", buildFile.isFile)
            val text = buildFile.readText()
            val entries = registry(text)
            val escaping = escapingClasses(module)
            checked += escaping.size

            escaping.forEach { (cls, found) ->
                val entry = entries[cls]
                if (entry == null) {
                    unaccounted += "${module.name}/src/test/**/$cls.kt reads outside its module " +
                        "($found) and ${module.name}/build.gradle.kts does not say so"
                } else if (entry.first == "declared" && !text.contains("inputs.file")) {
                    undeclared += "${module.name}/build.gradle.kts records $cls as `declared` " +
                        "and registers no input on its Test tasks"
                }
            }
            entries.keys.filterNot { it in escaping }.forEach { cls ->
                dead += "${module.name}/build.gradle.kts still names $cls, which no longer reads " +
                    "outside its module — delete the line"
            }
        }

        assertTrue(
            "the scan found no cross-module test reads at all, which this tree is known to have " +
                "— it read nothing and proves nothing",
            checked >= 5,
        )
        assertEquals(
            "a unit test reads a file Gradle does not know it reads. Declare the inputs on the " +
                "module's Test tasks and record it, or record why the task already re-runs — " +
                "`B-204`, and `B-197` is what the silence costs:\n" +
                unaccounted.joinToString("\n") { "  $it" },
            emptyList<String>(),
            unaccounted,
        )
        assertEquals(
            "a `declared` verdict with nothing declared:\n" + undeclared.joinToString("\n") { "  $it" },
            emptyList<String>(),
            undeclared,
        )
        assertEquals(
            "the registry has rotted:\n" + dead.joinToString("\n") { "  $it" },
            emptyList<String>(),
            dead,
        )
    }

    private companion object {
        val MARKERS = listOf(
            "settings.gradle.kts",
            """File("../""",
            """File("src/""",
            "ProcessBuilder(",
            "rootDir",
            "rootProject",
            "ls-files",
        )

        /**
         * The em dash is required and the verdict is one of two words, so a line that merely
         * mentions the marker in prose is not mistaken for an entry.
         */
        val ENTRY = Regex("""//\s*reads-outside-module:\s*(\w+)\s+—\s+(declared|safe):\s+(\S[^\n]*)""")
    }
}

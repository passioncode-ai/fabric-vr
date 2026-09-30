package ai.passioncode.fabricvr.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **One way to install the Main dispatcher in this suite, and it is [MainDispatcherRule]** —
 * `B-112`, and the durable half of `B-183`.
 *
 * `setMain` installs process-global mutable state, and this suite runs every class in one
 * JVM. Nine classes each re-typed the `setMain`/`resetMain` pair by hand, each placing the reset
 * at a slightly different point in its own teardown, so *when* Main was released depended on
 * which class you were looking at. `B-183` is what that cost: `VoiceViewModelTest` failing with
 * `Dispatchers.Main is used concurrently with setting it`, twice, seen by two different agents,
 * green on `--rerun-tasks` both times and never reproducible on demand.
 *
 * A `TestWatcher` fixes the *ordering* rather than the symptom: `finished()` runs after every
 * `@After`, always, including when the test method threw — so the dispatcher is released once all
 * other cleanup has run, in every class, by construction rather than by nine people remembering.
 *
 * **Fixing nine classes is not the deliverable; the tenth is.** It gets written next month by
 * somebody copying the shape of a class that still had the hand-rolled pair, and no review catches
 * a `@Before` that looks exactly like the one above it. This is what makes that impossible.
 *
 * It scans the source tree rather than reflecting over loaded classes deliberately: the defect is a
 * line of code somebody wrote, and a class that fails to load is exactly the case reflection cannot
 * see. `MainThreadPolicyTest` shells out to a python checker for the same reason; this rule is two
 * lines of scan and does not earn a second file.
 */
class MainDispatcherPolicyTest {

    @Test fun `no test class installs the main dispatcher by hand`() {
        val offenders = scan(testSourceRoot())

        assertEquals(
            "these classes install the Main dispatcher by hand instead of using MainDispatcherRule — " +
                "which is the order-dependence behind B-183:\n" +
                offenders.joinToString("\n") { "  $it" },
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The other half, and the one this project keeps having to relearn (`SI-06`): a scan that
     * matches nothing prints the same silence as a clean tree. This proves the scan can still see
     * the thing it is looking for — that the path resolved, the files were read, and the green
     * above came from reading rather than from an empty directory.
     */
    @Test fun `the scan reports a planted violation`() {
        val sandbox = File.createTempFile("maindispatcher", "").apply { delete(); mkdirs() }
        try {
            File(sandbox, "PlantedTest.kt").writeText(
                """
                class PlantedTest {
                    private val dispatcher = StandardTestDispatcher()
                    @Before fun setUp() = $SET_MAIN(dispatcher)
                    @After fun tearDown() = $RESET_MAIN()
                }
                """.trimIndent(),
            )

            val offenders = scan(sandbox)

            assertEquals("the scan did not see a file that plainly sets Main by hand", 1, offenders.size)
            assertTrue(offenders.single().contains("PlantedTest.kt"))
        } finally {
            sandbox.deleteRecursively()
        }
    }

    /** And the canary's canary: a file using the rule properly must NOT be reported. */
    @Test fun `the scan passes a class that uses the rule`() {
        val sandbox = File.createTempFile("maindispatcher-ok", "").apply { delete(); mkdirs() }
        try {
            File(sandbox, "GoodTest.kt").writeText(
                """
                class GoodTest {
                    private val dispatcher = StandardTestDispatcher()
                    @get:Rule val main = MainDispatcherRule(dispatcher)
                }
                """.trimIndent(),
            )

            assertEquals(emptyList<String>(), scan(sandbox))
        } finally {
            sandbox.deleteRecursively()
        }
    }

    /**
     * Every `.kt` under [root] that names either call, except [MainDispatcherRule] — the one file
     * that is *supposed* to make them.
     *
     * **It matches text, so it cannot tell a call from a mention in a comment.** A future author
     * who writes the qualified name in prose gets a failure naming their own file. That is the
     * direction worth failing in: it is loud, it says exactly which file, and the fix is to write
     * the bare method name — whereas the alternative, an exemption list, is how a scan stops
     * seeing the one file somebody put a real violation in.
     */
    private fun scan(root: File): List<String> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.name != "MainDispatcherRule.kt" }
        .filter { file ->
            val text = file.readText()
            SET_MAIN in text || RESET_MAIN in text
        }
        .map { it.relativeTo(root).path }
        .sorted()
        .toList()

    private companion object {
        /**
         * **Assembled from two halves so that this file is not its own first offender.**
         *
         * The scan reads source text, and a scanner that spells out what it hunts for matches
         * itself — which is not a hypothetical: the first run of this test failed naming
         * `MainDispatcherPolicyTest.kt`. The alternative was an exemption list, and an exemption
         * list is how a scan stops seeing the one file somebody put a real violation in. The
         * compiler folds these back into the constants at build time, so what the scan compares
         * is exactly the call it means.
         */
        val SET_MAIN = "Dispatchers." + "setMain"
        val RESET_MAIN = "Dispatchers." + "resetMain"
    }

    /** The test's working directory is the module, not the repository. */
    private fun testSourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val repo = checkNotNull(dir) { "no settings.gradle.kts above ${File("").absolutePath}" }
        val src = File(repo, "app/src/test/kotlin")
        check(src.isDirectory) { "the test source root moved: ${src.path} is not a directory" }
        return src
    }
}

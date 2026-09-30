package ai.passioncode.fabricvr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R2 as part of the suite, not as a rule in a document nobody re-reads.
 *
 * The 2026-09-20 audit found fourteen places where a click handler did a Keystore decrypt, a
 * filesystem call, a JSON encode or an HTTP construction on `Dispatchers.Main.immediate`. Fixing
 * fourteen is not the deliverable; the fifteenth is written next week by somebody reading the file
 * rather than the audit. `tools/check_main_thread.py` is the deliverable and this is what makes it
 * run.
 *
 * It shells out rather than reimplementing the scan, so there is exactly one definition of the
 * rule. The script self-tests before it answers — exit 2 means the scan proved nothing, which is a
 * different failure from exit 1 and is reported as one.
 */
class MainThreadPolicyTest {

    @Test fun `no viewModelScope launch touches a blocking seam without switching`() {
        val repo = repoRoot()
        val script = File(repo, "tools/check_main_thread.py")
        check(script.isFile) { "the checker is missing at ${script.path} — the rule has no enforcement" }

        val process = ProcessBuilder("python3", script.path, repo.path)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()

        assertEquals(
            "tools/check_main_thread.py did not pass:\n$output",
            0,
            code,
        )
    }

    /**
     * The other half, and the one this project keeps having to relearn: a scan that matches
     * nothing prints the same silence as a clean tree. The script's own canary covers its
     * internals; this covers the wiring — that the path resolved, python ran, and the green above
     * came from a scan rather than from a `FileNotFoundException` nobody read.
     */
    @Test fun `the checker reports a planted violation`() {
        val repo = repoRoot()
        val sandbox = File.createTempFile("mainthread", "").apply { delete(); mkdirs() }
        try {
            val src = File(sandbox, "app/src/main/kotlin").apply { mkdirs() }
            File(src, "Planted.kt").writeText(
                """
                class Fake : ViewModel() {
                    fun bad() {
                        viewModelScope.launch {
                            val bytes = File(dir, "x.wav").readBytes()
                            _state.value = bytes.size
                        }
                    }
                }
                """.trimIndent(),
            )
            val process = ProcessBuilder("python3", File(repo, "tools/check_main_thread.py").path, sandbox.path)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()

            assertEquals("the checker passed a file planted with a blocking launch:\n$output", 1, process.waitFor())
        } finally {
            sandbox.deleteRecursively()
        }
    }

    /**
     * **And the same violation written the way this tree writes one** (`B-159`).
     *
     * The script keyed on the literal `viewModelScope.launch`, and `B-159` moved all thirty-six
     * of those behind `launchGuarded`. The rule got no weaker and the scanner stopped seeing
     * anything: it would have kept printing `ok` over a tree it could no longer read, which is
     * the exact shape the script's own header records `T-017` causing once before. The bare
     * spelling no longer occurs in `app/src/main` outside `Guarded.kt`, so a canary written in it
     * proves the scan works on code the product does not contain.
     */
    @Test fun `the checker reports a violation written behind launchGuarded`() {
        val repo = repoRoot()
        val sandbox = File.createTempFile("mainthread-guarded", "").apply { delete(); mkdirs() }
        try {
            val src = File(sandbox, "app/src/main/kotlin").apply { mkdirs() }
            File(src, "Planted.kt").writeText(
                """
                class Fake : ViewModel() {
                    fun bad() {
                        launchGuarded {
                            val bytes = File(dir, "x.wav").readBytes()
                            _state.value = bytes.size
                        }
                    }
                }
                """.trimIndent(),
            )
            val process = ProcessBuilder("python3", File(repo, "tools/check_main_thread.py").path, sandbox.path)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()

            assertEquals(
                "the checker passed a blocking launchGuarded body — it is scanning nothing:\n$output",
                1,
                process.waitFor(),
            )
        } finally {
            sandbox.deleteRecursively()
        }
    }

    /** The test's working directory is the module, not the repository. */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "no settings.gradle.kts above ${File("").absolutePath}" }
    }
}

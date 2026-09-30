package ai.passioncode.fabricvr.stt

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **`fabricvr_whisper.cpp`, executed.** Compiles the real bridge against a fake JVM and a fake
 * whisper on this machine, provokes the paths the `2026-09-22` audit named, and counts what it
 * pinned against what it released.
 *
 * **Nothing in this project could run that file before**, and both halves of the gap are worth
 * stating because each looks like coverage from the other side:
 *
 * - `scripts/check-native.sh` compiles the bridge with `-fsyntax-only` against the NDK's own
 *   headers. That says it is a translation unit for arm64 and nothing about behaviour — a pin
 *   that leaks on a throw compiles perfectly.
 * - `WhisperEngineCancellationTest` drives the rules `WhisperEngine` enforces *around* the native
 *   calls through `WhisperNativeCalls`. That seam exists **because** the native call cannot be
 *   made from a JVM test; the fake on the other side of it is not the bridge.
 *
 * So two defects sat in a speech engine with nothing able to convict them: a `jfloat*` pinned at
 * `GetFloatArrayElements` and released at two call sites that a throw skips, and a
 * `GetStringUTFChars` handed straight to `std::string::assign` with no null check, which is
 * `strlen(nullptr)` on an out-of-memory JVM. Watched failing before the fix, 2026-09-22: the
 * first prints `FAIL: a run that throws releases the audio array — pinned 1, released 0`, and the
 * second does not fail a check at all — **the process dies with a signal**, which is why the
 * harness runs unbuffered and why this reads the exit code rather than only the output.
 *
 * **What it cannot say, stated:** the fakes are not whisper.cpp and this shim is not the NDK's
 * `jni.h`, so a green here is not a claim that the library works — that is the release job's
 * answer and the headset's. It is a claim about the bridge's own control flow, which is where
 * both defects lived.
 *
 * **On a machine with no host C++ compiler this test is SKIPPED, not passed.** A check that
 * cannot run and prints the word that means it ran is `SI-06`'s own incident; JUnit's skipped
 * state says so in the report instead.
 */
class NativeBridgeMemoryTest {

    @Test fun `the jni bridge gives back every array and string it pins`() {
        val root = repositoryRoot()
        val bridge = File(root, "feature-stt/src/main/cpp/fabricvr_whisper.cpp")
        val harness = File(root, "feature-stt/src/test/cpp/bridge_harness.cpp")
        val shim = File(root, "feature-stt/src/test/cpp/shim")
        val whisper = File(root, "third_party/whisper.cpp")

        assertTrue("the bridge is not where this test looks: $bridge", bridge.isFile)
        assertTrue("the harness is not where this test looks: $harness", harness.isFile)

        // The submodule is not checked out in every clone, and `check-native.sh` says the same
        // thing in the same shape rather than failing on it.
        assumeTrue(
            "whisper.h is absent — run: git submodule update --init --recursive",
            File(whisper, "include/whisper.h").isFile,
        )
        val compiler = hostCompiler()
        assumeTrue("no host C++ compiler on this machine, so the bridge cannot be run", compiler != null)

        val binary = File.createTempFile("fabricvr-bridge", "").apply { delete(); deleteOnExit() }
        val compile = run(
            listOf(
                compiler!!, "-std=c++17", "-fexceptions", "-g",
                "-I", shim.path,
                "-I", File(whisper, "include").path,
                "-I", File(whisper, "ggml/include").path,
                bridge.path, harness.path,
                "-o", binary.path,
            ),
            root,
        )
        assertEquals("the bridge did not compile for this machine:\n${compile.output}", 0, compile.code)

        val ran = run(listOf(binary.path), root)

        // **The exit code first, and the reason is the whole of case (b).** Before the fix the
        // null-language scenario does not report a failure — it segfaults — so a test that only
        // read the output would have found an incomplete list of `ok:` lines and no explanation.
        assertTrue(
            "the bridge died rather than failing a check (exit ${ran.code}):\n${ran.output}",
            ran.code in 0..1,
        )
        assertEquals("a bridge scenario failed:\n${ran.output}", 0, ran.code)
        assertTrue(
            "the harness stopped early without saying so:\n${ran.output}",
            "ALL BRIDGE SCENARIOS GREEN" in ran.output,
        )
        // The two the audit is about, named here so that deleting either scenario from the
        // harness fails this test rather than quietly shrinking it.
        listOf(
            "a run that throws releases the audio array",
            "a language string that could not be read still releases the audio array",
        ).forEach { scenario ->
            assertTrue("the harness no longer runs: $scenario\n${ran.output}", scenario in ran.output)
        }
    }

    private class Ran(val code: Int, val output: String)

    private fun run(command: List<String>, dir: File): Ran {
        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        // A bound, because the failure mode this test exists for is a native process that
        // misbehaves — and a suite that hangs is a suite somebody kills rather than reads.
        assertTrue("the bridge harness did not finish:\n$output", process.waitFor(120, TimeUnit.SECONDS))
        return Ran(process.exitValue(), output)
    }

    /** `c++` on macOS and Linux; nothing else is tried, because nothing else is where CI runs. */
    private fun hostCompiler(): String? = listOf("c++", "clang++", "g++").firstOrNull { candidate ->
        runCatching {
            ProcessBuilder(listOf(candidate, "--version"))
                .redirectErrorStream(true)
                .start()
                .let { it.waitFor(30, TimeUnit.SECONDS) && it.exitValue() == 0 }
        }.getOrDefault(false)
    }

    /**
     * The repository root, found by walking up from the module Gradle happens to run this in.
     *
     * Not `user.dir` plus a fixed number of `..`: that is true of exactly one invocation shape and
     * silently wrong for every other.
     */
    private fun repositoryRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "no settings.gradle.kts above ${System.getProperty("user.dir")}, so the sources cannot be found",
        )
    }
}

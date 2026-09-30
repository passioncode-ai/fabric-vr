package ai.passioncode.fabricvr.common

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes [Redaction] scrubs out of a crash report and the shapes `scripts/check-secrets.sh`
 * refuses to let into the repository are the same security rule written twice. Two copies of a
 * rule are one copy and one lie; this is what keeps them one rule in two places.
 *
 * It asserts **coverage, not equality**: the gate may be stricter about what may come near a
 * repository, but every shape it calls a credential must be one the scrubber removes before it is
 * written onto a person's device.
 */
class RedactionParityTest {

    /**
     * A `Test` task's working directory is the **module** directory, so a relative path here
     * resolves inside `core-common/`. Walk up to the build root instead — this is the trap
     * `T-019`'s checker spec carries, where a relative path matched zero files and the check
     * passed having read nothing.
     */
    private fun repoRoot(): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** One concrete example per shape the gate names, long enough to satisfy its own length rule. */
    private val examples = mapOf(
        "sk-or-v1-" to "sk-" + "or-v1-" + "a".repeat(32),
        "sk-ant-" to "sk-" + "ant-api03-" + "b".repeat(40),
        "sk-proj-" to "sk" + "-proj-" + "c".repeat(40),
        // The generic OpenAI shape, which the count check found missing from this list the moment
        // the check existed — three `sk-` families were named and the bare one was not.
        "sk-[" to "sk-" + "r".repeat(40),
        "sk_live_" to "sk_live_" + "d".repeat(24),
        "rk_live_" to "rk_live_" + "e".repeat(24),
        "AIza" to "AIza" + "f".repeat(35),
        "hf_" to "hf_" + "g".repeat(24),
        "ghp_" to "ghp_" + "h".repeat(36),
        "gho_" to "gho_" + "i".repeat(36),
        "github_pat_" to "github_pat_" + "j".repeat(62),
        "AKIA" to "AKIA" + "K".repeat(16),
        "xox" to "xoxb-" + "l".repeat(20),
        "xapp-" to "xapp-1-" + "m".repeat(20),
        "glpat-" to "glpat-" + "n".repeat(24),
        "lin_api_" to "lin_api_" + "o".repeat(24),
        "SG\\." to "SG." + "p".repeat(24) + "." + "q".repeat(30),
        // Assembled rather than written out: a literal header in a file is a shape the estate's
        // own guards refuse to let through a command line, and they are right to.
        "PRIVATE" + " KEY" to "-".repeat(5) + "BEGIN RSA " + "PRIVATE" + " KEY" + "-".repeat(5),
    )

    @Test
    fun everyShapeTheGateRefusesIsAlsoScrubbedFromWhatWeWriteDown() {
        // `assertTrue`, not `assumeTrue`. A skipped test is green, so the first version passed by
        // comparing with nothing when the script was absent — the exact outcome its own commit
        // message claimed it could not have. Found by an independent verification pass.
        val root = repoRoot()
        assertTrue("not a checkout — this test cannot verify anything and must not pass", root != null)
        val gate = File(root, "scripts/check-secrets.sh")
        assertTrue("the secret gate is missing; parity with it cannot be claimed", gate.isFile)
        val script = gate.readText()

        // The parity claim is only as wide as the list below, so the list is checked too: every
        // marker must actually appear in the gate, or this test compares with nothing.
        examples.keys.forEach { marker ->
            assertTrue(
                "the parity list names `$marker`, which check-secrets.sh does not — the list has drifted",
                script.contains(marker),
            )
        }

        // And the other direction, which a hand-written list cannot see on its own: a shape added
        // to the gate that this list does not name is drift too, and invisible. Counting the
        // gate's own alternations is the cheapest check that notices — add a shape there and this
        // fails, saying to add an example here.
        val alternations = script.lines()
            .filter { it.trimStart().startsWith("PATTERNS") }
            .joinToString("\n")
            .substringAfter("=")
            .split("|")
            .count { it.isNotBlank() }
        assertTrue(
            "check-secrets.sh carries $alternations credential shapes and this list names " +
                "${examples.size}; a shape the gate refuses and this test does not name would be " +
                "written into a crash report unnoticed",
            alternations == examples.size,
        )

        examples.forEach { (marker, example) ->
            val scrubbed = Redaction.scrub("crashed while using $example, sorry")
            assertTrue(
                "check-secrets.sh refuses `$marker` but Redaction would write it into a crash report",
                !scrubbed.contains(example) && scrubbed.contains("<redacted:"),
            )
            assertTrue("the surrounding text was destroyed too", scrubbed.contains("crashed while using"))
        }
    }
}

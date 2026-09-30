package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`B-160`: a `versionCode` from `git rev-list --count` is not a version, it is a branch length.**
 *
 * `DEC-0048` chose a git-derived number deliberately and was right to: `-PversionCode` had exactly
 * one caller and every APK this project ever produced read `(1)`. What it took was the *count of
 * commits reachable from HEAD*, and that is a property of the branch rather than of the code.
 * Measured on this repository on 2026-09-22: `main` 57, `feat/v1-notes-core` 176. Two feature
 * branches off one base are worse — the one with more commits outranks the one with newer work,
 * whichever was written first — and Android answers a lower number with
 * `INSTALL_FAILED_VERSION_DOWNGRADE`, whose only way through is the uninstall that takes
 * `filesDir`, the vault, the database and the model with it.
 *
 * **The property the number has to have is monotonicity in the code's own age**, across every
 * branch somebody might install from, without a tracked file to bump and without a second ref to
 * resolve. `DEC-00xx` weighs the three candidates; this is the chosen one asserted.
 *
 * The rule is read out of `app/build.gradle.kts` rather than restated here, in
 * `PanelSizeTest`'s shape and for its reason: a test that hard-codes the number it is guarding
 * cannot catch the drift its own KDoc promises to catch.
 */
class VersionCodeTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("no repository root above ${System.getProperty("user.dir")}")
        }
        return dir
    }

    private val buildFile: String by lazy {
        val file = File(repoRoot(), "app/build.gradle.kts")
        assertTrue("the build file is not where this test looks: ${file.absolutePath}", file.isFile)
        file.readText()
    }

    /**
     * The project epoch the build subtracts, read from the build file.
     *
     * Its absence is the red this test was written for: a build that derives `versionCode` from
     * anything but a clock has no epoch to find.
     */
    private fun declaredEpoch(): Long? =
        Regex("""val versionEpochSeconds = ([0-9_]+)L""").find(buildFile)
            ?.groupValues?.get(1)?.replace("_", "")?.toLongOrNull()

    /** The rule itself, in one line, so every case below applies the same one. */
    private fun codeFor(commitSeconds: Long, epoch: Long): Long = commitSeconds - epoch

    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repoRoot())
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) null else out
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun commitSeconds(ref: String): Long? = git("show", "-s", "--format=%ct", ref)?.toLongOrNull()

    // ---- the rule in force ------------------------------------------------------------------

    /**
     * **The stamped number is the commit's own age, not the branch's length.**
     *
     * Tied to `BuildConfig.VERSION_CODE` — the value Gradle actually wrote into this build —
     * rather than to a second reading of the expression, so the build file and this test cannot
     * agree with each other while disagreeing with the APK.
     */
    @Test fun `the version code is the commit's own clock, not the count of commits behind it`() {
        val epoch = declaredEpoch()
        assertNotNull(
            "app/build.gradle.kts declares no `versionEpochSeconds`, so the version code is not " +
                "derived from a clock — under a count it falls the moment a branch is shorter (B-160)",
            epoch,
        )
        val head = commitSeconds("HEAD")
        assertNotNull("this test needs the repository's own history and git could not answer", head)

        assertEquals(
            "the build stamped ${BuildConfig.VERSION_CODE}, and the commit's clock says " +
                "${codeFor(head!!, epoch!!)}",
            codeFor(head, epoch),
            BuildConfig.VERSION_CODE.toLong(),
        )
    }

    /**
     * **The property, over the history this repository actually has.**
     *
     * Ordering every reachable commit by the rule must give the same order as ordering it by when
     * the commit was made — which is exactly what a count does not do once there is a second
     * branch. Asserted over real commits rather than over invented pairs, because the rule is
     * trivially monotone in its own input and that is not the question; the question is whether
     * the input is the one that orders builds.
     */
    @Test fun `every commit in this history orders the same way by the rule and by its clock`() {
        val epoch = declaredEpoch() ?: return  // the case above owns that red; this one adds nothing
        val refs = buildList {
            git("rev-list", "-n", "40", "HEAD")?.lines()?.forEach { add(it) }
            listOf("origin/main", "main", "origin/feat/v1-notes-core").forEach { name ->
                git("rev-parse", "--verify", "--quiet", name)?.let { add(it) }
            }
        }.distinct().mapNotNull { sha -> commitSeconds(sha)?.let { sha to it } }

        assertTrue(
            "fewer than two commits could be read, so an ordering assertion proves nothing",
            refs.map { it.second }.distinct().size >= 2,
        )

        assertEquals(
            "the version code does not order commits the way their own clocks do",
            refs.sortedBy { it.second }.map { it.first },
            refs.sortedBy { codeFor(it.second, epoch) }.map { it.first },
        )
    }

    /**
     * **The bound, and the headroom, said out loud.**
     *
     * Android refuses a `versionCode` above 2 100 000 000, and an epoch base spends that space at
     * one per second. The number here is what says how long the choice lasts: at 2026-01-01 it
     * runs out in the second half of the next century. A `0` would mean the fallback fired — a
     * tarball or a machine with no `git` — and that must still be a legal code rather than a
     * negative one.
     */
    @Test fun `the version code is inside the platform's bound, with the headroom it claims`() {
        assertTrue(
            "versionCode ${BuildConfig.VERSION_CODE} is outside Android's 1..2_100_000_000",
            BuildConfig.VERSION_CODE in 1..ANDROID_MAX,
        )
        val epoch = declaredEpoch() ?: return
        val yearsLeft = (ANDROID_MAX - (System.currentTimeMillis() / 1000 - epoch)) / SECONDS_PER_YEAR
        assertTrue(
            "the epoch $epoch leaves only $yearsLeft years before the code hits Android's bound",
            yearsLeft > 50,
        )
    }

    private companion object {
        /** Google Play's documented ceiling, and the one Android itself enforces. */
        const val ANDROID_MAX = 2_100_000_000L
        const val SECONDS_PER_YEAR = 31_557_600L
    }
}

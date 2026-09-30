package ai.passioncode.fabricvr.common

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A crash on the second headset used to be a one-line chat report that the app had closed, against a
 * logcat nobody captured, on a device that is not on this network. `DEC-0023` makes handing back a
 * diagnosis without a laptop the obligation that follows from calling it a test copy.
 */
class CrashLogTest {

    @get:Rule val temp = TemporaryFolder()

    private fun dir(): File = temp.newFolder("crash")

    private fun boom(message: String = "something went wrong"): Throwable =
        runCatching { throw IllegalStateException(message) }.exceptionOrNull()!!

    @Test fun `a crash is written as one record with its stack`() {
        val d = dir()

        CrashLog.record(d, boom("the vault would not open"), mapOf("thread" to "main", "build" to "0.1.0"))

        val text = CrashLog.all(d)
        assertTrue("the exception class is missing: $text", text.contains("IllegalStateException"))
        assertTrue("the message is missing", text.contains("the vault would not open"))
        assertTrue("no stack frames were written", text.contains("    at "))
        assertTrue("the metadata was dropped", text.contains("thread") && text.contains("main"))
        assertTrue("the record is not timestamped", text.contains("at      20"))
    }

    @Test fun `the cause chain is written, not only the top`() {
        val d = dir()
        val cause = IllegalArgumentException("the root of it")
        CrashLog.record(d, IllegalStateException("the surface of it", cause))

        val text = CrashLog.all(d)
        assertTrue("the cause was dropped, which is usually the only useful half", text.contains("caused by"))
        assertTrue(text.contains("the root of it"))
    }

    /**
     * Front, not back. A crash loop writes the same failure repeatedly and the interesting one is
     * the newest; trimming the other way keeps the first and discards everything after it.
     */
    @Test fun `the file never grows past its bound, and it is the newest that survives`() {
        val d = dir()
        repeat(200) { i -> CrashLog.record(d, boom("failure number $i")) }

        val text = CrashLog.all(d)
        assertTrue(
            "a crash loop filled the device it was already failing on: ${text.toByteArray().size} bytes",
            text.toByteArray().size <= CrashLog.MAX_BYTES,
        )
        assertTrue("the newest crash was trimmed away", text.contains("failure number 199"))
        assertFalse("the oldest crash was kept instead", text.contains("failure number 0\n"))
        assertTrue("the file begins mid-record", text.trimStart().startsWith("---"))
    }

    /**
     * The project's standing rule, and the reason this is not `printStackTrace` to a file: a value
     * never enters anything that is written down. An HTTP client throwing `401 for sk-…` is a real
     * shape, not a hypothetical one.
     */
    @Test fun `a credential in an exception message never reaches the file`() {
        val d = dir()
        val key = "sk-" + "or-v1-" + "b".repeat(32)

        CrashLog.record(d, boom("401 Unauthorized for $key"), mapOf("url" to "https://x/v1"))

        val text = CrashLog.all(d)
        assertFalse("a key was written to disk in plain text", text.contains(key))
        assertTrue("nothing says a value was removed: $text", text.contains("<redacted:"))
        assertTrue("the diagnosis was thrown away with the secret", text.contains("401 Unauthorized"))
    }

    @Test fun `recording a crash cannot itself crash`() {
        val unwritable = File(temp.root, "not-a-dir").apply { writeText("I am a file") }

        CrashLog.record(unwritable, boom())   // must not throw

        assertEquals("", CrashLog.all(unwritable))
        assertNull(CrashLog.latest(unwritable))
    }

    @Test fun `latest returns the newest record alone`() {
        val d = dir()
        CrashLog.record(d, boom("first"))
        CrashLog.record(d, boom("second"))

        val latest = CrashLog.latest(d)!!
        assertTrue(latest.contains("second"))
        assertFalse("latest returned the whole file", latest.contains("first"))
    }

    @Test fun `clearing leaves nothing behind`() {
        val d = dir()
        CrashLog.record(d, boom())
        CrashLog.clear(d)
        assertNull(CrashLog.latest(d))
    }
    /**
     * The branch no test reached: a single record larger than the whole bound. `takeLast(MAX_BYTES)`
     * counted **characters**, and this app's text is Cyrillic at two bytes each — so the fallback
     * returned about twice the documented bound. Found by an independent verification pass.
     */
    @Test fun `one record larger than the bound is still cut to the bound, in bytes`() {
        val d = dir()
        CrashLog.record(d, boom("я" .repeat(CrashLog.MAX_BYTES)))

        val bytes = CrashLog.all(d).toByteArray()
        assertTrue(
            "the file is ${bytes.size} bytes against a bound of ${CrashLog.MAX_BYTES}",
            bytes.size <= CrashLog.MAX_BYTES,
        )
        assertTrue("the surviving text is not valid UTF-8", CrashLog.all(d).isNotEmpty())
    }

}

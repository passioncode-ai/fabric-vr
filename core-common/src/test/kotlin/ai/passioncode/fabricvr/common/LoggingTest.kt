package ai.passioncode.fabricvr.common

import org.junit.Assert.assertEquals
import org.junit.Test

class LoggingTest {

    @Test fun `redact never returns the value itself`() {
        assertEquals("<7 chars>", Log2.redact("sk-or-1"))
        assertEquals("empty", Log2.redact(""))
        assertEquals("null", Log2.redact(null))
    }
}

/**
 * The logger is called from every error path. Off the device `android.util.Log` throws, so a
 * logger that did not survive its absence turned a handled failure into a test failure — and would
 * turn any JVM-side tooling into a crash.
 */
class LoggingOffDeviceTest {
    @org.junit.Test fun `logging without an Android runtime does not throw`() {
        Log2.i("test.info", "k" to 1)
        Log2.w("test.warn")
        Log2.e("test.error", IllegalStateException("expected"), "k" to Log2.redact("secret"))
    }
}

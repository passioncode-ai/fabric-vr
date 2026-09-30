package ai.passioncode.fabricvr.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-memory implementation is what tests and keystore-less hosts use, so its failure path is
 * worth pinning: a caller must be able to exercise "the write did not happen".
 */
class InMemorySecureSettingsTest {

    @Test fun `values round-trip and clear`() {
        val s = InMemorySecureSettings()
        s.put("k", "v").getOrThrow()
        assertEquals("v", s.get("k"))
        s.remove("k")
        assertNull(s.get("k"))
    }

    @Test fun `a forced failure surfaces a storage error and does not repeat`() {
        val s = InMemorySecureSettings()
        s.failNextPut = true

        val failed = s.put("k", "v")
        val second = s.put("k", "v")

        assertTrue(failed.isFailure)
        assertTrue((failed.exceptionOrNull() as SecureSettingsException).error is AppError.Storage)
        assertTrue(second.isSuccess)
        assertEquals("v", s.get("k"))
    }

    @Test fun `nothing is corrupt in memory`() {
        assertTrue(InMemorySecureSettings().corruptedKeys.value.isEmpty())
    }
}

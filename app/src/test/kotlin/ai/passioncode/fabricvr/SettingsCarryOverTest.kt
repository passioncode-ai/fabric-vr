package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.InMemorySecureSettings
import ai.passioncode.fabricvr.common.SecureSettings
import ai.passioncode.fabricvr.stt.SttProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DEC-0014` replaced "a saved whisper-server URL is preferred" with an explicit provider. Nobody
 * wrote the carry-over, so an operator who configured a server under the old rule had their
 * speech quietly move onto the on-device model — and the Settings field that would have shown the
 * URL still sitting there renders only when the provider is already `SERVER`.
 */
class SettingsCarryOverTest {

    @Test fun `a server url saved under the old rule is carried over once`() {
        val settings = InMemorySecureSettings()
        settings.put(SecureSettings.KEY_WHISPER_SERVER_URL, "http://192.168.1.9:8080")

        val carried = carryOverSettings(settings)

        assertTrue("the operator's server was left routing nowhere", carried)
        assertEquals(SttProvider.SERVER.key, settings.get(SecureSettings.KEY_STT_PROVIDER))
        assertEquals("2", settings.get(SecureSettings.KEY_SETTINGS_SCHEMA))
    }

    @Test fun `the carry-over does not run twice`() {
        val settings = InMemorySecureSettings()
        settings.put(SecureSettings.KEY_WHISPER_SERVER_URL, "http://192.168.1.9:8080")
        settings.put(SecureSettings.KEY_SETTINGS_SCHEMA, "2")
        // Chosen deliberately, with the old URL still saved — a common shape, and the one a guard
        // keyed on "the provider is absent" instead of on the marker would trample every launch.
        settings.put(SecureSettings.KEY_STT_PROVIDER, SttProvider.LOCAL.key)

        val carried = carryOverSettings(settings)

        assertFalse(carried)
        assertEquals(SttProvider.LOCAL.key, settings.get(SecureSettings.KEY_STT_PROVIDER))
    }

    @Test fun `a person with no server url is not moved`() {
        val settings = InMemorySecureSettings()

        val carried = carryOverSettings(settings)

        assertFalse(carried)
        assertNull(settings.get(SecureSettings.KEY_STT_PROVIDER))
        assertEquals("the marker must still be written, or this runs forever", "2", settings.get(SecureSettings.KEY_SETTINGS_SCHEMA))
    }

    /**
     * **The `Result` of a Keystore write was discarded, and the return value announced a migration
     * that had not happened** (audit `2026-09-22`, the same shape as `SettingsViewModel:777`).
     *
     * `SecureSettings.put` returns `Result<Unit>` because a Keystore write genuinely fails — an
     * undecryptable alias, StrongBox busy, a device that has just been unlocked. `carryOverSettings`
     * ignored it, so `moved = true` meant *the line above was executed*, not *the provider moved*.
     * Settings then tells the person their whisper-server was carried over while the stored
     * provider is still absent and their speech is still on the on-device model — which is the
     * exact silence `DEC-0014`'s carry-over exists to end, re-created by the thing that ends it.
     */
    @Test fun `a carry-over whose write failed does not report that it moved anybody`() {
        val settings = InMemorySecureSettings()
        settings.put(SecureSettings.KEY_WHISPER_SERVER_URL, "http://192.168.1.9:8080")
        settings.failNextPut = true

        val carried = carryOverSettings(settings)

        assertFalse("Settings announced a migration the Keystore refused", carried)
        assertNull("the provider was reported as moved and was not", settings.get(SecureSettings.KEY_STT_PROVIDER))
    }

    /**
     * And the marker is **not** written when that happens, which is the half that decides whether
     * the failure is transient or permanent.
     *
     * Writing it would retire the migration for ever on the strength of a write that did not
     * land — a StrongBox that was busy for one launch would cost the person their configured
     * server permanently, with nothing to see and nothing to press. Leaving it absent means the
     * next launch simply tries again, which for a once-per-install migration costs two reads.
     */
    @Test fun `a failed carry-over leaves the marker absent so the next launch tries again`() {
        val settings = InMemorySecureSettings()
        settings.put(SecureSettings.KEY_WHISPER_SERVER_URL, "http://192.168.1.9:8080")
        settings.failNextPut = true

        assertFalse(carryOverSettings(settings))
        assertNull(
            "the migration was retired on the strength of a write that failed",
            settings.get(SecureSettings.KEY_SETTINGS_SCHEMA),
        )

        // The control: the same call, with the Keystore working, does the whole job.
        assertTrue("the retry did not carry the operator's server over", carryOverSettings(settings))
        assertEquals(SttProvider.SERVER.key, settings.get(SecureSettings.KEY_STT_PROVIDER))
        assertEquals("2", settings.get(SecureSettings.KEY_SETTINGS_SCHEMA))
    }

    @Test fun `a person who already chose a provider is not moved`() {
        val settings = InMemorySecureSettings()
        settings.put(SecureSettings.KEY_WHISPER_SERVER_URL, "http://192.168.1.9:8080")
        settings.put(SecureSettings.KEY_STT_PROVIDER, SttProvider.CLOUD.key)

        val carried = carryOverSettings(settings)

        assertFalse(carried)
        assertEquals(SttProvider.CLOUD.key, settings.get(SecureSettings.KEY_STT_PROVIDER))
    }
}

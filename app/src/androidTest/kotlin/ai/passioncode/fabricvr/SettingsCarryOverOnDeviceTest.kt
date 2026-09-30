package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.KeystoreSecureSettings
import ai.passioncode.fabricvr.common.SecureSettings
import ai.passioncode.fabricvr.stt.SttProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `H-26`'s carry-over, against the **real** Keystore.
 *
 * `DEC-0014` made the speech provider an explicit choice; somebody who had configured a
 * whisper-server under the old rule has a URL, no provider, and their speech silently moved onto
 * the on-device model. `DEC-0024` added the one-shot migration.
 *
 * The board row for this said the observation "cannot be simulated — the finding is about a value
 * already on a device". Measured 2026-09-21: **neither fielded headset has a `shared_prefs` file at
 * all**, so there is no such value to find and waiting for one would have been waiting for
 * nothing. What is real and was untested is the Keystore: `InMemorySecureSettings` proves the
 * branch logic and proves nothing about AES/GCM round-tripping through `AndroidKeyStore` on
 * Horizon OS, which is where a migration that reads and writes settings actually lives.
 */
@RunWith(AndroidJUnit4::class)
class SettingsCarryOverOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = "fabricvr_carryover_probe"

    /** A store of its own, so the probe never touches the settings the person is using. */
    private fun settings(): SecureSettings = KeystoreSecureSettings(
        context,
        context.getSharedPreferences(prefs, android.content.Context.MODE_PRIVATE),
    )

    @Before fun clean() = wipe()
    @After fun tearDown() = wipe()

    private fun wipe() {
        context.getSharedPreferences(prefs, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun aServerConfiguredUnderTheOldRuleIsCarriedOverOnceOnRealHardware() {
        val s = settings()
        s.put(SecureSettings.KEY_WHISPER_SERVER_URL, "http://192.168.0.9:8080").getOrThrow()
        assertNull("the fixture already had a provider", s.get(SecureSettings.KEY_STT_PROVIDER))

        val moved = carryOverSettings(s)

        assertEquals("the operator's server was left routing nowhere", true, moved)
        assertEquals(SttProvider.SERVER.key, s.get(SecureSettings.KEY_STT_PROVIDER))
        assertEquals("2", s.get(SecureSettings.KEY_SETTINGS_SCHEMA))
        // The value must survive the Keystore round trip, or the migration moved a provider onto a
        // server address the app can no longer read.
        assertEquals("http://192.168.0.9:8080", s.get(SecureSettings.KEY_WHISPER_SERVER_URL))

        assertEquals("the migration ran a second time", false, carryOverSettings(s))
        assertEquals(SttProvider.SERVER.key, s.get(SecureSettings.KEY_STT_PROVIDER))
    }

    @Test
    fun aDeliberateLocalChoiceSurvivesTheMigration() {
        val s = settings()
        s.put(SecureSettings.KEY_WHISPER_SERVER_URL, "http://192.168.0.9:8080").getOrThrow()
        s.put(SecureSettings.KEY_STT_PROVIDER, SttProvider.LOCAL.key).getOrThrow()

        assertEquals(false, carryOverSettings(s))
        assertEquals(
            "somebody who chose the headset on purpose was dragged onto their old server",
            SttProvider.LOCAL.key, s.get(SecureSettings.KEY_STT_PROVIDER),
        )
    }
}

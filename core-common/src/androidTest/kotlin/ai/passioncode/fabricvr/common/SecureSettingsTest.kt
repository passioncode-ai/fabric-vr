package ai.passioncode.fabricvr.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On the device, not the JVM: Robolectric provides no `AndroidKeyStore` provider, so every
 * encryption here would fail for the wrong reason and a corrupt-value test would pass because
 * *nothing* could be decrypted. The Keystore is a device fact and is checked on one.
 */
class SecureSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun settings() = KeystoreSecureSettings(context)

    @Test fun a_value_round_trips() {
        val s = settings()
        s.put(SecureSettings.KEY_OPENROUTER_MODEL, "anthropic/claude-sonnet-5").getOrThrow()

        assertEquals("anthropic/claude-sonnet-5", s.get(SecureSettings.KEY_OPENROUTER_MODEL))
    }

    @Test fun remove_clears_the_value() {
        val s = settings()
        s.put(SecureSettings.KEY_STT_LANGUAGE, "ru").getOrThrow()
        s.remove(SecureSettings.KEY_STT_LANGUAGE)

        assertNull(s.get(SecureSettings.KEY_STT_LANGUAGE))
    }

    @Test fun an_unreadable_value_is_reported_and_cleared_not_read_as_absent() {
        val prefs = context.getSharedPreferences("fabricvr_secure", Context.MODE_PRIVATE)
        prefs.edit().putString(SecureSettings.KEY_OPENROUTER_API_KEY, "not:ciphertext").commit()
        val s = settings()

        val value = s.get(SecureSettings.KEY_OPENROUTER_API_KEY)

        assertNull(value)
        assertTrue(
            "the key must be reported unreadable, not simply missing",
            SecureSettings.KEY_OPENROUTER_API_KEY in s.corruptedKeys.value,
        )
        assertFalse(
            "the bytes that cannot be read must not stay forever",
            prefs.contains(SecureSettings.KEY_OPENROUTER_API_KEY),
        )
    }

    @Test fun eight_threads_racing_on_first_use_share_one_key() {
        val s = settings()
        val method = KeystoreSecureSettings::class.java.getDeclaredMethod("secretKey")
            .apply { isAccessible = true }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val keys = java.util.Collections.synchronizedList(mutableListOf<SecretKey>())

        repeat(8) {
            pool.submit {
                start.await()
                keys.add(method.invoke(s) as SecretKey)
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        assertEquals("one alias, one key", 1, keys.map { it.encoded?.toList() ?: it }.toSet().size)
    }

}

package ai.passioncode.fabricvr.common

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableEntryException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `C-04`. `get()` wrapped the whole decrypt in `runCatching` and, on **any** throwable, deleted
 * the stored ciphertext.
 *
 * It could not tell *"the Keystore entry was replaced"* — permanent, where deleting the bytes is
 * right because keeping them means re-failing for ever — from a transient `KeyStoreException`, a
 * busy keystore, or a device still finishing boot. **One transient error destroyed the value.**
 * The same store holds every non-secret setting, so a run of them silently reset the app's whole
 * configuration one key per read, and only the OpenRouter key's loss was surfaced anywhere.
 *
 * **What this file can and cannot reach.** Robolectric has no `AndroidKeyStore`, so nothing here
 * encrypts or decrypts for real — the instrumented `SecureSettingsTest` does that on the device,
 * and a JVM version of it would pass for the wrong reason. What is tested here is the
 * **decision**: which failures are permanent, and what each kind does to the stored bytes and to
 * the two published sets. The ciphertext is written straight into preferences, and the failure is
 * injected before the cipher is touched, so no keystore is involved either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeystoreSecureSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences("test_${System.nanoTime()}", Context.MODE_PRIVATE)

    private fun stored(p: SharedPreferences, key: String = "k") {
        // Shaped like a real entry — `iv:body`, both Base64 — so the malformed-text branch is not
        // taken by accident and the test is about the injected failure alone.
        check(p.edit().putString(key, "aXY=:Ym9keQ==").commit())
    }

    private fun settings(p: SharedPreferences, vararg failures: Throwable): KeystoreSecureSettings {
        val queue = ArrayDeque(failures.toList())
        return KeystoreSecureSettings(context, prefs = p, decryptFailure = { queue.removeFirstOrNull() })
    }

    /**
     * **The defect.** A single transient failure must not cost the value. Before the fix the
     * second `get()` returned null and the preferences no longer held the key at all.
     */
    @Test fun `a transient keystore error keeps the bytes`() {
        val p = prefs()
        stored(p)
        // Two failures: the first is the transient error, the second stands in for the decrypt
        // that would follow — there is no keystore here to do it for real.
        val s = settings(p, KeyStoreException("keystore busy"), KeyStoreException("still busy"))

        assertNull("the read should fail, or this test proves nothing", s.get("k"))

        assertNotNull("one transient error destroyed the stored value", p.getString("k", null))
        assertTrue("a transient failure was reported as permanent loss", s.corruptedKeys.value.isEmpty())
        assertEquals("the person is not told the value is unreadable right now", setOf("k"), s.unreadableKeys.value)
    }

    /**
     * The permanent case, unchanged: a tag that does not authenticate means the key that wrote
     * these bytes is gone. Keeping them means re-failing for ever, so they go — and the loss is
     * published, because telling somebody "add a key" when theirs was destroyed is the wrong
     * sentence.
     */
    @Test fun `a bad tag deletes the bytes and publishes the loss`() {
        val p = prefs()
        stored(p)
        val s = settings(p, AEADBadTagException("tag mismatch"))

        assertNull(s.get("k"))

        assertNull("unreadable bytes were kept and will re-fail for ever", p.getString("k", null))
        assertEquals(setOf("k"), s.corruptedKeys.value)
        assertFalse("a permanent loss was also reported as transient", s.unreadableKeys.value.contains("k"))
    }

    /** Malformed stored text is structural: no later read can succeed, so it is permanent too. */
    @Test fun `malformed stored text is permanent`() {
        val p = prefs()
        check(p.edit().putString("k", "not-a-ciphertext").commit())
        val s = KeystoreSecureSettings(context, prefs = p)

        assertNull(s.get("k"))

        assertEquals(setOf("k"), s.corruptedKeys.value)
        assertNull(p.getString("k", null))
    }

    @Test fun `a key that was never set is neither corrupt nor unreadable`() {
        val s = settings(prefs())
        assertNull(s.get("absent"))
        assertTrue(s.corruptedKeys.value.isEmpty())
        assertTrue(s.unreadableKeys.value.isEmpty())
    }

    /**
     * The classifier itself, over every shape it has to separate — matched on **type**, never on
     * a message, which is a locale and a vendor away from being different text. The wrapped cases
     * are the ones `G-06` got wrong in the other classifier this project has: a provider wraps,
     * and reading only the top-level throwable misses it.
     */
    @Test fun `permanence is decided by type, through a wrapper`() {
        val permanent = listOf(
            AEADBadTagException("tag"),
            BadPaddingException("padding"),
            IllegalArgumentException("bad base64"),
            IOException("wrapped", AEADBadTagException("tag")),
        )
        val transient = listOf(
            KeyStoreException("busy"),
            IllegalStateException("not ready"),
            IOException("network-ish"),
        )

        permanent.forEach { failure ->
            val p = prefs(); stored(p)
            val s = settings(p, failure)
            s.get("k")
            assertNull("kept the bytes for a permanent failure: $failure", p.getString("k", null))
        }
        transient.forEach { failure ->
            val p = prefs(); stored(p)
            val s = settings(p, failure, failure)
            s.get("k")
            assertNotNull("destroyed the value for a transient failure: $failure", p.getString("k", null))
        }
    }

    // ---- `B-188`: the alias that exists and cannot be used ----------------------------------

    /**
     * A key that behaves like the AndroidKeyStore alias without being it.
     *
     * The real provider cannot run here at all — `KeyStore.getInstance("AndroidKeyStore")` throws
     * under Robolectric — so a test using it could watch the repair be *attempted* and never
     * watch it succeed, which is the half of `B-188` that matters. This one holds a genuine
     * AES-256 key from the JVM's own provider, so everything downstream of the repair (encrypt,
     * decrypt, round-trip) is real.
     */
    private class FakeKeyProvider : SecretKeyProvider {
        /** Set to make the next [key] fail, as an unopenable alias does. Cleared by a delete. */
        var failure: Throwable? = null
        var deletions = 0
        val generated = mutableListOf(fresh())

        override fun key(): SecretKey {
            failure?.let { throw it }
            return generated.last()
        }

        override fun deleteAlias() {
            deletions++
            failure = null
            generated += fresh()
        }

        private companion object {
            fun fresh(): SecretKey =
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private fun settings(p: SharedPreferences, provider: FakeKeyProvider) =
        KeystoreSecureSettings(context, prefs = p, keyProvider = provider)

    /** The state `B-188` describes: values written, then the alias becomes unopenable. */
    private fun storeWithBrokenAlias(
        p: SharedPreferences,
        provider: FakeKeyProvider,
        failure: Throwable = UnrecoverableKeyException("Failed to obtain information about key"),
    ): KeystoreSecureSettings {
        settings(p, provider).run {
            put(SecureSettings.KEY_OPENROUTER_API_KEY, "первый секрет").getOrThrow()
            put(SecureSettings.KEY_CLOUD_STT_KEY, "второй секрет").getOrThrow()
            put(SecureSettings.KEY_STT_LANGUAGE, "ru").getOrThrow()
        }
        provider.failure = failure
        // A new instance: the alias is discovered on the next launch, not inside the session that
        // has the key cached. Reusing the first one would never call the provider again and the
        // test would prove nothing.
        return settings(p, provider)
    }

    /**
     * **The defect.** The entry **exists and cannot be used** — `UnrecoverableKeyException` out of
     * `getEntry`, the vendor's "invalid key blob". No retry fixes it, so the transient default
     * kept the bytes and re-failed for ever; and because the same store holds every non-secret
     * setting, `put` failed with `Storage("keystore")` on every call and the whole Settings
     * screen stopped saving until the person cleared app data.
     */
    @Test fun `an unusable alias is deleted, regenerated, and the save goes through`() {
        val p = prefs()
        val provider = FakeKeyProvider()
        val s = storeWithBrokenAlias(p, provider)

        val saved = s.put(SecureSettings.KEY_STT_LANGUAGE, "en")

        assertTrue("every setting is unsaveable until app data is cleared", saved.isSuccess)
        assertEquals("the alias nothing can open was left in place", 1, provider.deletions)
        assertEquals("no key was generated to replace it", 2, provider.generated.size)
        assertEquals("the store did not come back", "en", s.get(SecureSettings.KEY_STT_LANGUAGE))
    }

    /**
     * The person has to be told, because these two cannot be reopened from a screen — they are
     * fetched from another service and typed in again. Named, never valued: this set reaches a
     * log and a UI.
     */
    @Test fun `the secrets the alias took with it are named`() {
        val p = prefs()
        val provider = FakeKeyProvider()
        val s = storeWithBrokenAlias(p, provider)

        s.put(SecureSettings.KEY_STT_LANGUAGE, "en").getOrThrow()

        assertEquals(
            "the person is not told which secrets they have to enter again",
            setOf(SecureSettings.KEY_OPENROUTER_API_KEY, SecureSettings.KEY_CLOUD_STT_KEY),
            s.corruptedKeys.value,
        )
        assertTrue("a loss no read can undo was published as a passing one", s.unreadableKeys.value.isEmpty())
        assertNull(
            "ciphertext no key can read again was kept",
            p.getString(SecureSettings.KEY_OPENROUTER_API_KEY, null),
        )

        s.put(SecureSettings.KEY_CLOUD_STT_KEY, "введён заново").getOrThrow()
        assertEquals("введён заново", s.get(SecureSettings.KEY_CLOUD_STT_KEY))
        assertFalse(
            "the screen still asks for a key that is back",
            SecureSettings.KEY_CLOUD_STT_KEY in s.corruptedKeys.value,
        )
    }

    /** Announcing the loss of a secret nobody ever set is its own wrong sentence. */
    @Test fun `a secret that was never stored is not announced as lost`() {
        val p = prefs()
        val provider = FakeKeyProvider()
        settings(p, provider).put(SecureSettings.KEY_STT_LANGUAGE, "ru").getOrThrow()
        provider.failure = UnrecoverableKeyException("Invalid key blob")
        val s = settings(p, provider)

        s.put(SecureSettings.KEY_STT_LANGUAGE, "en").getOrThrow()

        assertEquals(1, provider.deletions)
        assertTrue("a key the person never entered was reported lost", s.corruptedKeys.value.isEmpty())
    }

    /** A read discovers it just as often as a write does, and must repair it the same way. */
    @Test fun `an unusable alias found on a read is repaired too`() {
        val p = prefs()
        val provider = FakeKeyProvider()
        val s = storeWithBrokenAlias(p, provider)

        assertNull("this read cannot succeed, or the test proves nothing", s.get(SecureSettings.KEY_CLOUD_STT_KEY))

        assertEquals(1, provider.deletions)
        assertTrue(SecureSettings.KEY_CLOUD_STT_KEY in s.corruptedKeys.value)
        s.put(SecureSettings.KEY_CLOUD_STT_KEY, "снова").getOrThrow()
        assertEquals("снова", s.get(SecureSettings.KEY_CLOUD_STT_KEY))
    }

    /**
     * The third arm's own table, and the guard on `DEC-0037`: the two classes it already had keep
     * every shape they claimed. A permanent failure must **not** reach the alias — deleting it
     * over one bad ciphertext would destroy every other setting with it.
     */
    @Test fun `unusable is decided by type, through a wrapper, and never claims a permanent shape`() {
        val unusable = listOf(
            UnrecoverableKeyException("Failed to obtain information about key"),
            UnrecoverableEntryException("no such entry"),
            InvalidKeyException("invalid key"),
            IOException("wrapped", UnrecoverableKeyException("Invalid key blob")),
        )
        unusable.forEach { failure ->
            val p = prefs()
            val provider = FakeKeyProvider()
            val s = storeWithBrokenAlias(p, provider, failure)
            s.put(SecureSettings.KEY_STT_LANGUAGE, "en")
            assertEquals("left the alias unusable for $failure", 1, provider.deletions)
        }

        // `DEC-0037`'s own shapes, unchanged: one value goes, the alias stays.
        val p = prefs()
        val provider = FakeKeyProvider()
        stored(p)
        val s = KeystoreSecureSettings(
            context, prefs = p, keyProvider = provider,
            decryptFailure = { AEADBadTagException("tag mismatch") },
        )
        s.get("k")
        assertEquals("a bad tag on one value destroyed the key for all of them", 0, provider.deletions)
        assertEquals(setOf("k"), s.corruptedKeys.value)

        // …and the transient default, which `C-04` is the record of, is still the default.
        val q = prefs()
        val busy = FakeKeyProvider()
        stored(q)
        val t = KeystoreSecureSettings(
            context, prefs = q, keyProvider = busy,
            decryptFailure = { KeyStoreException("keystore busy") },
        )
        t.get("k")
        assertEquals("a busy keystore was read as an unusable alias", 0, busy.deletions)
        assertNotNull("a busy keystore destroyed the value", q.getString("k", null))
    }

    /**
     * An alias that cannot be replaced is not a repaired one. Clearing the store or announcing
     * the loss here would report a destruction that has not happened and need not.
     */
    @Test fun `an alias that cannot be replaced fails honestly rather than clearing the store`() {
        val p = prefs()
        val provider = object : SecretKeyProvider {
            override fun key(): SecretKey = throw UnrecoverableKeyException("Invalid key blob")
            override fun deleteAlias() = throw KeyStoreException("keystore is gone")
        }
        check(p.edit().putString(SecureSettings.KEY_CLOUD_STT_KEY, "aXY=:Ym9keQ==").commit())
        val s = KeystoreSecureSettings(context, prefs = p, keyProvider = provider)

        val saved = s.put(SecureSettings.KEY_STT_LANGUAGE, "en")

        assertTrue("a failure that did happen was reported as success", saved.isFailure)
        assertNotNull(
            "a store that might still be readable was wiped",
            p.getString(SecureSettings.KEY_CLOUD_STT_KEY, null),
        )
        assertTrue("a loss that has not happened was announced", s.corruptedKeys.value.isEmpty())
    }

    /** `put` and `remove` retire both flags; neither needs a keystore to be asserted. */
    @Test fun `removing a key clears both flags`() {
        val p = prefs()
        stored(p)
        val s = settings(p, AEADBadTagException("tag"))
        s.get("k")
        assertEquals(setOf("k"), s.corruptedKeys.value)

        s.remove("k")

        assertTrue(s.corruptedKeys.value.isEmpty())
        assertTrue(s.unreadableKeys.value.isEmpty())
    }
}

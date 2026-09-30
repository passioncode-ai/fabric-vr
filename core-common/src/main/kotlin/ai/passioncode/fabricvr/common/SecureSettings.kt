package ai.passioncode.fabricvr.common

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small secrets, encrypted at rest. Keys are values the app holds, never values it prints. */
interface SecureSettings {
    fun get(key: String): String?
    fun put(key: String, value: String): Result<Unit>
    fun remove(key: String)

    /**
     * Keys whose stored value could not be decrypted on this device — the Keystore entry was
     * replaced, invalidated or restored from another device. Absence and unreadability are
     * different facts, and telling the person "add a key" when theirs was lost is the wrong one.
     */
    val corruptedKeys: StateFlow<Set<String>>

    /**
     * Keys that could not be read **this time**, with their bytes kept.
     *
     * A different fact from [corruptedKeys] and the reason `C-04` was a defect: a busy Keystore,
     * a device still finishing boot or a transient `KeyStoreException` used to be treated as
     * permanent loss and the ciphertext was deleted, so **one transient error destroyed the
     * value**. This set empties as soon as a read succeeds.
     *
     * Abstract rather than defaulted: a default returning a fresh empty flow would hand every
     * implementor a silent "nothing is ever transient", which is the belief `C-04` was.
     */
    val unreadableKeys: StateFlow<Set<String>>

    companion object {
        const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        const val KEY_OPENROUTER_MODEL = "openrouter_model"
        const val KEY_WHISPER_SERVER_URL = "whisper_server_url"
        const val KEY_STT_LANGUAGE = "stt_language"

        /** Which on-device speech model to use; the key of a `WhisperModel`. */
        const val KEY_STT_MODEL = "stt_model"

        /** Where transcription is attempted first: `local`, `cloud` or `server`. */
        const val KEY_STT_PROVIDER = "stt_provider"

        /** An OpenAI-compatible transcription endpoint, e.g. `https://api.groq.com/openai`. */
        const val KEY_CLOUD_STT_URL = "cloud_stt_url"
        const val KEY_CLOUD_STT_KEY = "cloud_stt_key"
        const val KEY_CLOUD_STT_MODEL = "cloud_stt_model"

        /**
         * Which shape the stored settings are in. Absent means schema 1 — written before
         * `DEC-0014` made the provider explicit, when a saved whisper-server URL *was* the choice.
         * `"2"` means the carry-over has run; it is the guard, so a person who deliberately moved
         * back to the on-device model is not dragged onto their old server again next launch.
         */
        const val KEY_SETTINGS_SCHEMA = "settings_schema"

        /**
         * How many days of recordings to keep, or absent/`0` for **everything** — which is the
         * default and `DEC-0038` says why: a retention that defaulted to deleting would destroy
         * months of the operator's dictations on the first launch after the update, before they
         * had read the screen that announces the feature.
         */
        const val KEY_AUDIO_RETENTION_DAYS = "audio_retention_days"

        /**
         * Whether a finished dictation is written to the system clipboard. Absent means **on**.
         *
         * `DEC-0012` chose to copy on every dictation and stands: in a headset the point of
         * speaking is usually to paste somewhere else, and taking that away costs the product its
         * best moment. What was missing (`G-02`) is the ability to say no — the app overwrites a
         * cross-app resource nobody offered it, and the only notice disappears after 2.5 s.
         */
        const val KEY_COPY_TRANSCRIPT = "copy_transcript"

        /**
         * Whether record start, record stop, the ten-minute cap and a saved dictation make a
         * sound and — in the Space — a controller pulse. Absent means **on**.
         *
         * `REQ-062`, audit `M22`. On by default because the defect is that the four moments were
         * invisible to somebody looking at a streamed desktop rather than at the panel, and a
         * cue nobody switches on fixes nothing. The switch exists because Meta's *Haptics: Best
         * practices* says it must: "Make haptic feedback optional and adjustable."
         */
        const val KEY_FEEDBACK_CUES = "feedback_cues"
    }
}

/**
 * The one AES key every stored setting is encrypted under, behind a seam.
 *
 * `B-188` needs two acts the rest of this file cannot reach: **delete the alias** and **generate a
 * new one**. Neither happens during a decrypt, so neither can be provoked — or observed — through
 * `decryptFailure`. A seam is also the only way a JVM test can watch a read succeed *after* the
 * repair: Robolectric has no `AndroidKeyStore`, so the real provider cannot be asked for a key at
 * all, and a test that could not get past the regeneration would prove only that it happened.
 */
interface SecretKeyProvider {
    /** The key under the alias, generating one if the alias holds nothing. Throws if it cannot. */
    fun key(): SecretKey

    /**
     * Drop the alias. Every value written under it becomes undecryptable, by design and for ever
     * — the caller owns telling the person what that cost them.
     */
    fun deleteAlias()
}

/**
 * AndroidKeyStore, StrongBox where the device has it (`DEC-0006`).
 * `androidx.security:security-crypto` is deprecated — its 1.1.0 notes say to use the platform APIs
 * directly — so this is the platform path.
 */
class AndroidKeystoreKeyProvider : SecretKeyProvider {

    override fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        // Deliberately unguarded: `getEntry` on a blob the provider cannot open throws
        // `UnrecoverableKeyException`, and that throwable is the whole signal `B-188` classifies.
        // Swallowing it here would put the decision somewhere nothing can see it.
        val existing = (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        return existing ?: generate()
    }

    override fun deleteAlias() {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    private fun generate(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .also { if (strongBox) it.setIsStrongBoxBacked(true) }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        return runCatching {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        }.getOrElse {
            // Most headsets have no StrongBox; asking for it and degrading is free, demanding it is not.
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "fabricvr_settings_v1"
        const val KEY_BITS = 256
    }
}

/**
 * AES-256/GCM over [SharedPreferences], with the key held by [keyProvider].
 */
class KeystoreSecureSettings(
    context: Context,
    private val prefs: SharedPreferences =
        context.getSharedPreferences("fabricvr_secure", Context.MODE_PRIVATE),
    /**
     * A throwable to fail the next decrypt with, or null to decrypt for real.
     *
     * Only a test passes this. `C-04` is a distinction between two *kinds* of decrypt failure,
     * and neither can be provoked from a test any other way: a real `AEADBadTagException` needs
     * the Keystore entry replaced under a live app, and a transient `KeyStoreException` needs a
     * busy Keystore. A classifier nobody has watched classify is a claim.
     */
    private val decryptFailure: () -> Throwable? = { null },
    /** Where the key comes from; a test substitutes it. See [SecretKeyProvider] for why. */
    private val keyProvider: SecretKeyProvider = AndroidKeystoreKeyProvider(),
) : SecureSettings {

    private val _corruptedKeys = MutableStateFlow<Set<String>>(emptySet())
    override val corruptedKeys: StateFlow<Set<String>> = _corruptedKeys.asStateFlow()

    private val _unreadableKeys = MutableStateFlow<Set<String>>(emptySet())
    override val unreadableKeys: StateFlow<Set<String>> = _unreadableKeys.asStateFlow()

    @Volatile private var cached: SecretKey? = null

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            decryptFailure()?.let { throw it }
            val parts = stored.split(SEPARATOR)
            require(parts.size == 2) { "malformed ciphertext" }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val body = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.fold(
            onSuccess = { value ->
                // A read that worked retires both flags: whatever went wrong before is over.
                if (key in _unreadableKeys.value) _unreadableKeys.value = _unreadableKeys.value - key
                value
            },
            onFailure = { failure -> onDecryptFailed(key, failure) },
        )
    }

    /**
     * **Three kinds of failure. Treating the first two alike was `C-04`; having no third was
     * `B-188`.**
     *
     * *Permanent* means no later read of **this value** can succeed: the tag does not
     * authenticate, the Keystore entry was replaced or invalidated, or the stored text is not a
     * ciphertext at all. Keeping those bytes means re-failing for ever, so they are deleted and
     * the loss is published — telling somebody "add a key" when theirs was destroyed is the wrong
     * sentence.
     *
     * *Unusable* means no later read of **anything** can succeed, because the alias itself cannot
     * be opened. It is checked second, so `DEC-0037`'s permanent set keeps every shape it already
     * claimed, and it is repaired rather than published: see [repairUnusableAlias].
     *
     * *Transient* is everything else: a busy Keystore, a device still finishing boot, a
     * `KeyStoreException` from a provider under load. The bytes **stay**. Before this, one such
     * error destroyed the value permanently — and the same store holds every non-secret setting,
     * so a run of them silently reset the whole configuration one key per read, with only the
     * OpenRouter key's loss surfaced anywhere.
     *
     * The default is still *transient*, deliberately. Misclassifying a permanent failure costs one
     * pointless read; misclassifying a transient one costs the person their data.
     */
    private fun onDecryptFailed(key: String, failure: Throwable): String? {
        when {
            isPermanent(failure) -> {
                Log2.w("secure_settings.unreadable", "key" to key, "kind" to "permanent")
                prefs.edit().remove(key).apply()
                _corruptedKeys.value = _corruptedKeys.value + key
                _unreadableKeys.value = _unreadableKeys.value - key
            }
            isUnusable(failure) -> {
                Log2.w("secure_settings.unreadable", "key" to key, "kind" to "unusable")
                repairUnusableAlias()
            }
            else -> {
                Log2.w("secure_settings.unreadable", "key" to key, "kind" to "transient")
                _unreadableKeys.value = _unreadableKeys.value + key
            }
        }
        return null
    }

    /**
     * Whether a decrypt failure can ever succeed on a later read.
     *
     * Matched on **type**, not on a message: a message is a locale and a vendor away from being
     * different text. The chain is walked because a provider may wrap.
     *
     * - `AEADBadTagException` — the tag does not authenticate. The key that wrote these bytes is
     *   not the key reading them; no retry changes that.
     * - `BadPaddingException` — its supertype, for a provider that throws the general form.
     * - `KeyPermanentlyInvalidatedException` — the platform saying so in as many words.
     * - `IllegalArgumentException` — a malformed stored string, from `require` above or from
     *   Base64. Structural: the bytes are not a ciphertext and never will be.
     *
     * Everything else is transient by default, because the costs are not symmetric.
     */
    private fun isPermanent(failure: Throwable): Boolean =
        generateSequence(failure, Throwable::cause).take(MAX_CAUSE_DEPTH).any { cause ->
            cause is javax.crypto.AEADBadTagException ||
                cause is javax.crypto.BadPaddingException ||
                cause is android.security.keystore.KeyPermanentlyInvalidatedException ||
                cause is IllegalArgumentException
        }

    /**
     * Whether the **alias** — not one value — can never be used again as it stands. `B-188`.
     *
     * The gap this closes: an entry that *exists* and cannot be opened fell into the transient
     * default, so nothing ever called `deleteEntry`, every `get` re-failed and — because the same
     * store holds every non-secret setting — every `put` failed with `Storage("keystore")`. The
     * Settings screen stopped saving until the person cleared app data, permanently, from one
     * error.
     *
     * Matched on **type**, through the cause chain, for the reason [isPermanent] is:
     *
     * - `UnrecoverableEntryException` — the supertype of `UnrecoverableKeyException`, which is
     *   what `KeyStore.getEntry` throws when the provider cannot open the blob. The vendor's
     *   *"invalid key blob"* arrives exactly here, as the cause inside it; the chain walk means
     *   no message has to be read to see it, and a message is a locale away from being other text.
     * - `InvalidKeyException` — a key the cipher refuses. Its subclass
     *   `KeyPermanentlyInvalidatedException` never reaches this branch: [isPermanent] claims it
     *   first, deliberately, so `DEC-0037` keeps the set it already had.
     *
     * `java.security.KeyStoreException` is **not** here and must not be: a busy or still-booting
     * Keystore throws it, and `C-04` is the record of what treating that as loss costs. Widening
     * this predicate destroys data; narrowing it costs a retry.
     */
    private fun isUnusable(failure: Throwable): Boolean =
        generateSequence(failure, Throwable::cause).take(MAX_CAUSE_DEPTH).any { cause ->
            cause is java.security.UnrecoverableEntryException ||
                cause is java.security.InvalidKeyException
        }

    /**
     * Delete the alias, generate a new one, and say which secrets that cost. `B-188`.
     *
     * Nothing written under the old key can be read again, so the store is cleared in one act
     * rather than left to fail one key at a time — and the two values a person cannot re-derive,
     * [SecureSettings.KEY_OPENROUTER_API_KEY] and [SecureSettings.KEY_CLOUD_STT_KEY], are
     * published through [corruptedKeys] **only if they were actually stored**. Announcing the
     * loss of a key somebody never set is its own wrong sentence. The rest of the store is
     * settings with defaults; they come back as defaults and the log is where that is recorded.
     *
     * The regeneration happens here, not lazily, because its failure has to be told apart from
     * its success: if the alias cannot be replaced the values are not lost yet, so nothing is
     * cleared and nothing is announced, and the caller's `put` fails honestly instead.
     *
     * @return whether the alias is usable again.
     */
    @Synchronized
    private fun repairUnusableAlias(): Boolean {
        cached = null
        val regenerated = runCatching {
            keyProvider.deleteAlias()
            cached = keyProvider.key()
        }
        if (regenerated.isFailure) {
            cached = null
            Log2.e("secure_settings.alias_unrepairable", regenerated.exceptionOrNull())
            return false
        }
        val lost = SECRET_KEYS.filter { prefs.contains(it) }.toSet()
        val discarded = prefs.all.keys.size
        prefs.edit().clear().apply()
        _unreadableKeys.value = emptySet()
        _corruptedKeys.value = _corruptedKeys.value + lost
        Log2.w(
            "secure_settings.alias_repaired",
            "discarded" to discarded,
            // Key NAMES, never values: this line reaches a log the person may well send us.
            "lost_secrets" to lost.joinToString(","),
        )
        return true
    }

    /**
     * One retry, and only after a repair. A `put` that fails because the alias was unusable is
     * the call that discovers it, and returning `Storage("keystore")` to a person whose store has
     * just been made usable again would report a failure that is already over.
     */
    override fun put(key: String, value: String): Result<Unit> =
        write(key, value).recoverCatching { failure ->
            if (!isUnusable(failure) || !repairUnusableAlias()) throw failure
            write(key, value).getOrThrow()
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(SecureSettingsException(AppError.Storage("keystore", it))) },
        )

    private fun write(key: String, value: String): Result<Unit> = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + SEPARATOR +
            Base64.encodeToString(body, Base64.NO_WRAP)
        check(prefs.edit().putString(key, encoded).commit()) { "preferences write failed" }
        _corruptedKeys.value = _corruptedKeys.value - key
        _unreadableKeys.value = _unreadableKeys.value - key
        Unit
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
        _corruptedKeys.value = _corruptedKeys.value - key
        _unreadableKeys.value = _unreadableKeys.value - key
    }

    /**
     * Synchronised and cached. Check-then-generate from two threads — the settings screen and the
     * assistant's key lambda can easily coincide on first run — would generate twice, and the second
     * generation **replaces** the alias, turning every value written under the first into garbage.
     */
    @Synchronized
    private fun secretKey(): SecretKey = cached ?: keyProvider.key().also { cached = it }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128

        /** Bounded, for the reason `ModelDownloader.isNoSpace` is: a cause chain can cycle. */
        const val MAX_CAUSE_DEPTH = 16
        const val SEPARATOR = ":"

        /**
         * The values in this store a person cannot get back by reopening a screen — they have to
         * be fetched from another service and typed in again. Everything else here is a setting
         * with a default, which is why losing the alias announces these two and only these two.
         */
        val SECRET_KEYS = setOf(
            SecureSettings.KEY_OPENROUTER_API_KEY,
            SecureSettings.KEY_CLOUD_STT_KEY,
        )
    }
}

/** Carries the [AppError] a failed secure write should surface. */
class SecureSettingsException(val error: AppError) : Exception(error.cause)

/** An in-memory implementation for tests and for hosts with no keystore. */
class InMemorySecureSettings(
    private val map: MutableMap<String, String> = mutableMapOf(),
) : SecureSettings {
    /** Set by a test to make the next write fail, so the failure path can be exercised. */
    var failNextPut: Boolean = false

    private val _corruptedKeys = MutableStateFlow<Set<String>>(emptySet())
    override val corruptedKeys: StateFlow<Set<String>> = _corruptedKeys.asStateFlow()

    /** Nothing here can fail transiently: there is no cipher and no Keystore to be busy. */
    override val unreadableKeys: StateFlow<Set<String>> = MutableStateFlow<Set<String>>(emptySet()).asStateFlow()

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String): Result<Unit> {
        if (failNextPut) {
            failNextPut = false
            return Result.failure(SecureSettingsException(AppError.Storage("keystore", null)))
        }
        map[key] = value
        return Result.success(Unit)
    }

    override fun remove(key: String) { map.remove(key) }
}

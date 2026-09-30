package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.SecureSettings
import ai.passioncode.fabricvr.stt.CloudTranscriptionClient
import ai.passioncode.fabricvr.stt.FailingEngine
import ai.passioncode.fabricvr.stt.RemoteWhisperClient
import ai.passioncode.fabricvr.stt.SttEngine
import ai.passioncode.fabricvr.stt.SttProvider

/**
 * Where speech goes, decided in one place.
 *
 * **Never null for a provider the person chose, and never an address they did not type.** `Graph`
 * used to answer this question twice and differently: `sttEngine()` wanted a URL *and* a key and
 * returned `null` otherwise — so the router ran the on-device model and stamped the transcript
 * plain `LOCAL`, indistinguishable from somebody who chose the headset on purpose — while
 * `cloudClient()` wanted only a key and filled the address in with Groq, so re-running an old
 * recording "with the better model" posted it to a third party nobody had named.
 *
 * A chosen-but-unconfigured provider is therefore a [FailingEngine], not a `null`: `SttRouter`
 * already knows what to do with a remote that fails — run the local engine and stamp the transcript
 * `LOCAL_FALLBACK` with the reason — and that is exactly the visible degradation this case needs.
 * [SttProvider.LOCAL] is the one honest null in the function: nothing remote was asked for.
 *
 * A free function over a [SecureSettings] rather than a method on `Graph`, because `Graph` is an
 * `object` with a `lateinit` context and cannot be constructed twice — the seam that made this
 * decision untestable is the reason nobody noticed there were two rules for it. That is T-017's
 * pattern arriving early for one function; it is not extended to the rest of `Graph` here.
 */
internal fun remoteEngineFor(
    provider: SttProvider,
    settings: SecureSettings,
    cloudFactory: (String, () -> String?, String) -> SttEngine =
        { url, key, model -> CloudTranscriptionClient(url, key, model) },
    serverFactory: (String) -> SttEngine = { RemoteWhisperClient(it) },
): SttEngine? = when (provider) {
    SttProvider.LOCAL -> null

    SttProvider.CLOUD -> {
        val url = settings.get(SecureSettings.KEY_CLOUD_STT_URL)?.takeIf { it.isNotBlank() }
        val key = settings.get(SecureSettings.KEY_CLOUD_STT_KEY)?.takeIf { it.isNotBlank() }
        when {
            url == null -> refusal(provider, AppError.SttNotConfigured.Missing.ADDRESS)
            key == null -> refusal(provider, AppError.SttNotConfigured.Missing.KEY)
            else -> runCatching {
                cloudFactory(
                    url,
                    { settings.get(SecureSettings.KEY_CLOUD_STT_KEY) },
                    settings.get(SecureSettings.KEY_CLOUD_STT_MODEL)?.takeIf { it.isNotBlank() }
                        ?: CloudTranscriptionClient.DEFAULT_MODEL,
                )
            }.getOrElse {
                // The constructor refuses an endpoint `NetworkPolicy` will not allow. Swallowing
                // that into a null is how a recording went to the on-device model with nothing
                // said; the URL is the problem and the person is told so. What the policy
                // considers reachable is T-010's question, not this one.
                Log2.w("stt.remote.refused", "provider" to provider.key, "reason" to "insecure_url")
                FailingEngine(AppError.InsecureUrl(url))
            }
        }
    }

    SttProvider.SERVER -> {
        val url = settings.get(SecureSettings.KEY_WHISPER_SERVER_URL)?.takeIf { it.isNotBlank() }
        when {
            url == null -> refusal(provider, AppError.SttNotConfigured.Missing.ADDRESS)
            // Validated here as well as at save time: a URL stored under an older policy is still
            // in the Keystore, and the policy is the thing that changed.
            RemoteWhisperClient.validateBaseUrl(url).isFailure -> {
                Log2.w("stt.remote.refused", "provider" to provider.key, "reason" to "insecure_url")
                FailingEngine(AppError.InsecureUrl(url))
            }
            else -> runCatching { serverFactory(url) }
                .getOrElse { FailingEngine(AppError.InsecureUrl(url)) }
        }
    }
}

private fun refusal(provider: SttProvider, missing: AppError.SttNotConfigured.Missing): SttEngine {
    Log2.w("stt.remote.unconfigured", "provider" to provider.key, "missing" to missing.name.lowercase())
    return FailingEngine(AppError.SttNotConfigured(provider.key, missing))
}

/**
 * Moves a person configured under the pre-`DEC-0014` rule onto the provider they actually had.
 *
 * Before that decision a saved whisper-server URL *was* the choice; afterwards the choice is
 * [SecureSettings.KEY_STT_PROVIDER], which is written only when somebody opens Settings and picks
 * one. An upgrading operator therefore has a URL, no provider, `byKey(null) == LOCAL`, and their
 * speech silently running on the on-device model — with the Settings field that would have shown
 * the URL rendering only when the provider is already `SERVER`, so they could not see it either.
 * `DEC-0014`'s own Consequences section names this outcome and nothing implemented the carry-over.
 *
 * Keyed on the schema marker rather than on "the provider is absent": the latter would re-apply
 * every launch and drag somebody who deliberately moved back to the headset onto their old server
 * again. Returns whether it moved anybody, so Settings can say so once — a migration that changes
 * where a person's voice goes and says nothing is the same silence this task is about.
 *
 * **Must not run on the main thread.** `settings` is the Keystore, and generating its key on first
 * run happens inside a `@Synchronized` block.
 */
internal fun carryOverSettings(settings: SecureSettings): Boolean {
    if (settings.get(SecureSettings.KEY_SETTINGS_SCHEMA) != null) return false
    val server = settings.get(SecureSettings.KEY_WHISPER_SERVER_URL)?.takeIf { it.isNotBlank() }
    val chosen = settings.get(SecureSettings.KEY_STT_PROVIDER)
    var moved = false
    if (chosen == null && server != null) {
        // **The `Result` decides the answer; it used to be discarded** (audit `2026-09-22`).
        // `put` returns one because a Keystore write really fails — an alias that no longer
        // decrypts, StrongBox busy, a device not yet unlocked — and ignoring it made `moved`
        // mean *the line above was executed* rather than *the provider moved*. Settings then
        // announced a carry-over while the stored provider was still absent and the person's
        // speech was still on the on-device model: `DEC-0014`'s silence, re-created by the code
        // that exists to end it.
        val written = settings.put(SecureSettings.KEY_STT_PROVIDER, SttProvider.SERVER.key)
        if (written.isFailure) {
            Log2.w("settings.carry_over.failed", "provider" to SttProvider.SERVER.key)
            // **And the marker is not written either**, which is what makes the failure
            // recoverable. Writing it would retire the migration for ever on the strength of a
            // write that did not land, so one busy Keystore at one launch would cost the person
            // their configured server permanently, with nothing to see and nothing to press.
            // Absent, the next launch simply tries again — two reads for a once-per-install
            // migration.
            return false
        }
        moved = true
        Log2.i("settings.carry_over", "provider" to SttProvider.SERVER.key)
    }
    // A marker that will not write is not worth refusing the migration over: the provider is
    // already correct, and the only cost is that the next launch reads two keys and finds
    // nothing to do. It is logged, because a marker that never lands would otherwise be
    // invisible for ever.
    settings.put(SecureSettings.KEY_SETTINGS_SCHEMA, SETTINGS_SCHEMA)
        .onFailure { Log2.w("settings.schema_marker.failed", "schema" to SETTINGS_SCHEMA) }
    return moved
}

/**
 * Whether the chosen provider can actually reach anything.
 *
 * Asked **of** [remoteEngineFor] rather than re-derived from the settings, because "is the remote
 * configured?" having two answers in this codebase is the whole of what `DEC-0024` abolished. A
 * refusing engine is the shape an unconfigured choice takes, so the question is simply whether the
 * answer is one.
 */
internal fun remoteReady(provider: SttProvider, settings: SecureSettings): Boolean =
    remoteEngineFor(provider, settings).let { it != null && it !is FailingEngine }

/** Schema 2: the provider is explicit. See [SecureSettings.KEY_SETTINGS_SCHEMA]. */
internal const val SETTINGS_SCHEMA = "2"

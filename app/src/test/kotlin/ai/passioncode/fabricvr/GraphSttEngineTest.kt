package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.InMemorySecureSettings
import ai.passioncode.fabricvr.common.SecureSettings
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.SttEngine
import ai.passioncode.fabricvr.stt.SttException
import ai.passioncode.fabricvr.stt.SttProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Graph` is an `object` with a `lateinit` context, so it cannot be built twice and no test can
 * reset it. The routing decision is therefore a pure function of a [SecureSettings], and this is
 * the only way these cases can exist at all.
 */
class GraphSttEngineTest {

    /** Records rather than connects: the point of the first case is that it is never called. */
    private class RecordingFactory : SttEngine {
        var cloudUrls = mutableListOf<String>()
        var serverUrls = mutableListOf<String>()
        override val name: String = "recording"
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, langHint: String?) =
            Result.success(Transcript("", "en", SttSource.REMOTE, name, 0))
    }

    private fun engine(
        provider: SttProvider,
        settings: SecureSettings,
        factory: RecordingFactory = RecordingFactory(),
    ): SttEngine? = remoteEngineFor(
        provider,
        settings,
        cloudFactory = { url, _, _ -> factory.also { it.cloudUrls += url } },
        serverFactory = { url -> factory.also { it.serverUrls += url } },
    )

    private suspend fun refusal(engine: SttEngine?): AppError? =
        (engine?.transcribe(ShortArray(0), 16_000, null)?.exceptionOrNull() as? SttException)?.error

    @Test fun `cloud with a key and no address refuses, and names the address`() = runTest {
        val settings = InMemorySecureSettings()
        settings.put(SecureSettings.KEY_CLOUD_STT_KEY, "a-key")
        val factory = RecordingFactory()

        val engine = engine(SttProvider.CLOUD, settings, factory)

        assertEquals(
            AppError.SttNotConfigured("cloud", AppError.SttNotConfigured.Missing.ADDRESS),
            refusal(engine),
        )
        // The assertion that matters: no address was invented. An error alone would still pass
        // with a default endpoint restored one refactor from now.
        assertEquals("the recording was aimed at an address nobody typed", emptyList<String>(), factory.cloudUrls)
    }

    @Test fun `cloud with an address and no key refuses, and names the key`() = runTest {
        val settings = InMemorySecureSettings()
        settings.put(SecureSettings.KEY_CLOUD_STT_URL, "https://example.invalid/v1")

        assertEquals(
            AppError.SttNotConfigured("cloud", AppError.SttNotConfigured.Missing.KEY),
            refusal(engine(SttProvider.CLOUD, settings)),
        )
    }

    @Test fun `server with no address refuses`() = runTest {
        assertEquals(
            AppError.SttNotConfigured("server", AppError.SttNotConfigured.Missing.ADDRESS),
            refusal(engine(SttProvider.SERVER, InMemorySecureSettings())),
        )
    }

    @Test fun `local asks for nothing remote`() {
        assertNull(
            "LOCAL is the one honest null: nothing remote was asked for",
            engine(SttProvider.LOCAL, InMemorySecureSettings()),
        )
    }

    @Test fun `a chosen provider never yields null`() {
        val states: List<Pair<String, SecureSettings>> = listOf(
            "nothing saved" to InMemorySecureSettings(),
            "cloud key only" to InMemorySecureSettings().apply { put(SecureSettings.KEY_CLOUD_STT_KEY, "k") },
            "cloud url only" to InMemorySecureSettings().apply { put(SecureSettings.KEY_CLOUD_STT_URL, "https://e.invalid/v1") },
            "server url only" to InMemorySecureSettings().apply { put(SecureSettings.KEY_WHISPER_SERVER_URL, "http://192.168.1.9:8080") },
        )
        for ((label, settings) in states) {
            for (provider in SttProvider.entries) {
                val result = engine(provider, settings)
                if (provider == SttProvider.LOCAL) {
                    assertNull("$label / $provider", result)
                } else {
                    assertNotNull(
                        "$label / $provider yielded null — a silent local run the person cannot see",
                        result,
                    )
                }
            }
        }
    }

    @Test fun `a cloud url the network policy refuses becomes a refusal, not a silence`() = runTest {
        val settings = InMemorySecureSettings()
        settings.put(SecureSettings.KEY_CLOUD_STT_URL, "http://api.example.com/v1")
        settings.put(SecureSettings.KEY_CLOUD_STT_KEY, "a-key")

        // The real client is used here on purpose: its constructor is what refuses cleartext.
        val engine = remoteEngineFor(SttProvider.CLOUD, settings)

        assertTrue(
            "a URL the policy refuses left the person with a silent local run",
            refusal(engine) is AppError.InsecureUrl,
        )
    }
}

package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.FabricHttp
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `B-187`. **`DEC-0005` used to be checked once, on the address the person typed, and never again.**
 *
 * `NetworkPolicy.requireReachable` guards the URL in Settings; OkHttp then follows redirects by
 * default and the manifest permits cleartext, so a `307` from a configured `https` endpoint moved
 * the WAV — and, for the cloud client, the `Authorization` header carrying the transcription key —
 * to any host the server named, in the clear, with nothing checking where it landed.
 *
 * **"Somewhere else" is a NAME here, not a network.** Every host in this class resolves to
 * loopback through [loopback], and the second server stands in for whatever the redirect points at.
 * That is what makes the defect demonstrable rather than argued: before the fix, `elsewhere`
 * received the recording; after it, `elsewhere.requestCount` is zero.
 */
class RedirectPolicyTest {

    /** The address the person typed into Settings. */
    private lateinit var origin: MockWebServer

    /** Wherever the redirect points — the same machine, under a different name. */
    private lateinit var elsewhere: MockWebServer

    @get:Rule val temp = TemporaryFolder()

    @Before fun setUp() {
        origin = MockWebServer().also { it.start() }
        elsewhere = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        origin.shutdown()
        elsewhere.shutdown()
    }

    private val loopback = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName("127.0.0.1"))
    }

    /**
     * One generated certificate, issued for the name the TLS cases use, trusted by nothing but the
     * client those cases build (`B-198`).
     *
     * Generated per class rather than checked in: a certificate in the tree expires, and a test
     * that goes red on a date is a test that gets deleted. `addSubjectAlternativeName` is what
     * makes the verification real — OkHttp reads the SAN, not the CN, so a certificate without one
     * would fail the hostname check and every TLS case here would pass for the wrong reason.
     */
    private val handshake: HandshakeCertificates by lazy {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName(TLS_HOST)
            // `B-216`'s case needs a SECOND https name on the same generated certificate, so the
            // hop it makes is `https` to `https` and the only thing left to refuse it is the
            // cross-host rule. One certificate for both, because the point is a redirect the
            // TLS layer is perfectly happy with.
            .addSubjectAlternativeName(TLS_ELSEWHERE)
            .build()
        HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .addTrustedCertificate(certificate.certificate)
            .build()
    }

    /**
     * The production client shape, with a resolver that keeps the whole test on this machine.
     *
     * Built through [FabricHttp] because that is what the shipped clients do; `FabricHttpTest`
     * is what ties this shape to theirs, so a client that stopped going through the factory
     * fails there rather than passing quietly here.
     */
    private fun client(): OkHttpClient = FabricHttp.builder()
        .dns(loopback)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** A `307`, which is the shape that matters: method and body are preserved, so the WAV is re-sent. */
    private fun redirectTo(host: String, path: String = "/inference") = MockResponse()
        .setResponseCode(307)
        .setHeader("Location", "http://$host:${elsewhere.port}$path")

    private fun transcript(text: String) = MockResponse().setBody("""{"text":"$text"}""")

    /** An https server on this machine, holding [handshake]'s certificate for both TLS names. */
    private fun tlsServer() = MockWebServer().also {
        it.useHttps(handshake.sslSocketFactory(), false)
        it.start()
    }

    /** [client], trusting [handshake] and nothing else — never the machine's CA store. */
    private fun secureClient(): OkHttpClient = client().newBuilder()
        .sslSocketFactory(handshake.sslSocketFactory(), handshake.trustManager)
        .build()

    private companion object {
        /** The name the generated certificate is issued for, and the one [loopback] resolves. */
        const val TLS_HOST = "whisper.example.com"

        /** The second name on the same certificate: where `B-216`'s redirect points. */
        const val TLS_ELSEWHERE = "cdn.example.com"
    }

    /**
     * The defect itself: a hop that leaves the private network must be refused **before** the
     * recording is written to the socket.
     */
    @Test fun `a redirect off the private network never receives the recording`() = runTest {
        origin.enqueue(redirectTo("whisper.example.com"))
        elsewhere.enqueue(transcript("the recording arrived at the wrong host"))

        val result = RemoteWhisperClient(origin.url("/").toString(), client())
            .transcribe(ShortArray(1_600), 16_000, "ru")

        assertEquals(
            "the recording was re-POSTed in the clear to a host nothing had checked",
            0,
            elsewhere.requestCount,
        )
        assertTrue("a refused redirect reported success", result.isFailure)
        val error = (result.exceptionOrNull() as SttException).error
        assertTrue("a refused hop reached the person as $error", error is AppError.InsecureUrl)
    }

    /**
     * The other half of `DEC-0005`, and the reason this is a policy rather than a ban: a
     * whisper-server on the person's own network cannot hold a certificate, so a redirect that
     * stays inside that network is followed exactly as before.
     */
    @Test fun `a redirect that stays on the private network is followed`() = runTest {
        origin.enqueue(redirectTo("whisper.local"))
        elsewhere.enqueue(transcript("готово"))

        val result = RemoteWhisperClient(origin.url("/").toString(), client())
            .transcribe(ShortArray(1_600), 16_000, "ru")

        assertEquals("the LAN redirect was refused", 1, elsewhere.requestCount)
        assertEquals("готово", result.getOrNull()?.text)
    }

    /**
     * The cloud key must not follow the recording to a host it was not issued for.
     *
     * OkHttp drops `Authorization` when a redirect cannot reuse the connection — a different host,
     * port or scheme — and this asserts that rather than trusting it, because the whole point of
     * `B-187` is that a rule nobody checks is not a rule.
     */
    @Test fun `the cloud key does not follow a redirect to another host`() = runTest {
        origin.enqueue(redirectTo("whisper.local"))
        elsewhere.enqueue(transcript("готово"))

        CloudTranscriptionClient(origin.url("/").toString(), { "sk-test-key" }, client = client())
            .transcribe(ShortArray(1_600), 16_000, "ru")

        assertEquals(1, elsewhere.requestCount)
        assertNull(
            "the transcription key travelled to the redirect target",
            elsewhere.takeRequest().getHeader("Authorization"),
        )
    }

    /**
     * **`B-198`: the one case that crosses a real TLS hop.**
     *
     * Every other redirect in this file travels over cleartext, and the `https → http` downgrade —
     * the shape `DEC-0005` exists to refuse — was asserted only against a fabricated
     * `Interceptor.Chain` in `FabricHttpTest`. That fabrication is exact about the interceptor and
     * says nothing about the machinery around it: whether OkHttp's `followSslRedirects` really
     * hands a cleartext `Location` to a **network** interceptor at all, and whether the refusal
     * survives the path a TLS connection takes through `RetryAndFollowUpInterceptor`. `SI-09` is
     * the rule this closes — a mocked test proves the rule the code implements, never that the
     * rule still matches the world.
     *
     * The handshake is real and the trust is not the machine's: [handshake] holds one generated
     * certificate for `whisper.example.com` and the client trusts that and nothing else, so a
     * green here cannot come from a CA store or from verification being off. The hop that follows
     * is `http` to a public name, which is what must be refused **before the recording is written
     * to the socket** — `elsewhere.requestCount` is the whole assertion.
     */
    @Test fun `a downgrade from a real TLS origin never receives the recording`() = runTest {
        val tls = MockWebServer().also {
            it.useHttps(handshake.sslSocketFactory(), false)
            it.start()
        }
        try {
            tls.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", "http://whisper.example.com:${elsewhere.port}/inference"),
            )
            elsewhere.enqueue(transcript("the recording arrived over cleartext"))

            val secure = client().newBuilder()
                .sslSocketFactory(handshake.sslSocketFactory(), handshake.trustManager)
                .build()
            // The name, not the port's own `localhost`: the certificate is issued for it and
            // `loopback` resolves it here, so the handshake is genuinely verified rather than
            // waved through.
            val url = "https://whisper.example.com:${tls.port}/"

            val result = RemoteWhisperClient(url, secure).transcribe(ShortArray(1_600), 16_000, "ru")

            assertEquals(
                "the https origin was reached, so the handshake itself did not happen",
                1,
                tls.requestCount,
            )
            assertEquals(
                "a 307 from a verified https server moved the recording to cleartext",
                0,
                elsewhere.requestCount,
            )
            assertTrue("a refused downgrade reported success", result.isFailure)
            val error = (result.exceptionOrNull() as SttException).error
            assertTrue("a refused hop reached the person as $error", error is AppError.InsecureUrl)
        } finally {
            tls.shutdown()
        }
    }

    /**
     * The control for the case above, and it is not optional: a green that came from the handshake
     * failing would look exactly like a green that came from the hop rule. This proves the same
     * client, against the same certificate, **does** complete a TLS call and get an answer — so
     * the refusal above is the rule and not a broken socket.
     */
    @Test fun `the same TLS setup completes an ordinary call`() = runTest {
        val tls = MockWebServer().also {
            it.useHttps(handshake.sslSocketFactory(), false)
            it.start()
        }
        try {
            tls.enqueue(transcript("готово"))
            val secure = client().newBuilder()
                .sslSocketFactory(handshake.sslSocketFactory(), handshake.trustManager)
                .build()

            val result = RemoteWhisperClient("https://whisper.example.com:${tls.port}/", secure)
                .transcribe(ShortArray(1_600), 16_000, "ru")

            assertEquals(
                "the TLS handshake itself fails, so the refusal above proves nothing",
                "готово",
                result.getOrNull()?.text,
            )
        } finally {
            tls.shutdown()
        }
    }

    /**
     * **`B-216`, and it is a different question from every case above.**
     *
     * `DEC-0005` asks *where may bytes travel in the clear*, and `DEC-0077` asks it again at every
     * hop. Neither asks *may a redirect move the recording to another host at all* — so
     * `NetworkPolicy.permits` returned true for **every** `https` host, and a `307` from the
     * address in Settings re-POSTed the whole WAV to `https://attacker`: encrypted, and to
     * somebody else. A person whose whisper-server address is a name they do not control gets
     * exactly this, and the encryption is what makes it invisible.
     *
     * **Both ends are real TLS, and that is what makes the case honest.** The one certificate this
     * class generates now carries two names, so the redirect is `https` to `https` with a verified
     * handshake at both ends: nothing about the transport can be what refuses it. `target`
     * receiving zero requests is the whole assertion, because a network interceptor sits above
     * `CallServerInterceptor` — a refusal means no byte of the recording is written to that socket.
     */
    @Test fun `a redirect to another https host never receives the recording`() = runTest {
        val tls = tlsServer()
        val target = tlsServer()
        try {
            tls.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", "https://$TLS_ELSEWHERE:${target.port}/inference"),
            )
            target.enqueue(transcript("the recording arrived at a host the person never named"))

            val result = RemoteWhisperClient("https://$TLS_HOST:${tls.port}/", secureClient())
                .transcribe(ShortArray(1_600), 16_000, "ru")

            assertEquals("the https origin was not reached, so this proves nothing", 1, tls.requestCount)
            assertEquals(
                "a 307 moved the whole recording to another https host, encrypted, to somebody else",
                0,
                target.requestCount,
            )
            assertTrue("a refused redirect reported success", result.isFailure)
            val error = (result.exceptionOrNull() as SttException).error
            assertTrue("a refused hop reached the person as $error", error is AppError.RedirectRefused)
            assertEquals(TLS_ELSEWHERE, (error as AppError.RedirectRefused).host)
        } finally {
            tls.shutdown()
            target.shutdown()
        }
    }

    /**
     * The same hop, on the client that also carries the transcription key.
     *
     * OkHttp drops `Authorization` across hosts and `the cloud key does not follow a redirect to
     * another host` asserts that; it says nothing about the **recording**, which a `307` re-sends
     * in full. This is the half that was left open — the key stayed behind and the person's voice
     * went anyway.
     */
    @Test fun `a redirect to another https host never receives the cloud recording`() = runTest {
        val tls = tlsServer()
        val target = tlsServer()
        try {
            tls.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader(
                        "Location",
                        "https://$TLS_ELSEWHERE:${target.port}/v1/audio/transcriptions",
                    ),
            )
            target.enqueue(transcript("the recording arrived without the key, which is not the point"))

            val result = CloudTranscriptionClient(
                "https://$TLS_HOST:${tls.port}/",
                { "sk-test-key" },
                client = secureClient(),
            ).transcribe(ShortArray(1_600), 16_000, "ru")

            assertEquals("the key stayed behind and the recording went anyway", 0, target.requestCount)
            assertTrue("a refused redirect reported success", result.isFailure)
            val error = (result.exceptionOrNull() as SttException).error
            assertTrue("a refused hop reached the person as $error", error is AppError.RedirectRefused)
        } finally {
            tls.shutdown()
            target.shutdown()
        }
    }

    /**
     * **The control, and without it the rule above would be indistinguishable from *refuse every
     * redirect*.** A server that moves its own route — same host, another path — is an ordinary
     * thing to do, and the recording follows it exactly as before.
     */
    @Test fun `a redirect to another path on the same host is followed`() = runTest {
        val tls = tlsServer()
        try {
            tls.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", "https://$TLS_HOST:${tls.port}/v2/inference"),
            )
            tls.enqueue(transcript("готово"))

            val result = RemoteWhisperClient("https://$TLS_HOST:${tls.port}/", secureClient())
                .transcribe(ShortArray(1_600), 16_000, "ru")

            assertEquals("a same-host redirect was refused", 2, tls.requestCount)
            assertEquals("готово", result.getOrNull()?.text)
        } finally {
            tls.shutdown()
        }
    }

    /**
     * **What the rule deliberately does NOT refuse, and the model download is why.**
     *
     * A model host's `resolve/main` ALWAYS answers with a cross-host redirect to a CDN, and
     * refusing that would make the 190–574 MB download impossible. The request that follows it is
     * a `GET` with no body and no `Authorization`: there is nothing of the person's on it to
     * misdirect, and what it brings back is judged by a pinned SHA-256 and by the store's own
     * vendor allow-list. The rule is about a request that CARRIES something, not about redirects.
     */
    @Test fun `a credential-free download still follows its vendor's cross-host redirect`() = runTest {
        val tls = tlsServer()
        val cdn = tlsServer()
        try {
            tls.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", "https://$TLS_ELSEWHERE:${cdn.port}/model"),
            )
            cdn.enqueue(MockResponse().setBody("0123456789abcdef"))
            val root = temp.newFolder("cdn-models")
            val store = object : ModelStore {
                override val modelName = "test-model.bin"
                override val expectedBytes = 16L
                override val expectedSha256 = ""
                override val downloadUrl = "https://$TLS_HOST:${tls.port}/model"
                override val allowedRedirectHosts = setOf(TLS_ELSEWHERE)
                override fun modelFile() = File(root, modelName)
                override fun isPresent() = modelFile().isFile
            }

            val events = ModelDownloader(
                store,
                client = secureClient(),
                url = store.downloadUrl,
                retryDelay = {},
            ).download().toList()

            assertEquals("the vendor's own CDN hop was refused", 1, cdn.requestCount)
            assertTrue(
                "a credential-free GET could no longer reach the model: ${events.last()}",
                events.last() is DownloadProgress.Done,
            )
        } finally {
            tls.shutdown()
            cdn.shutdown()
        }
    }

    /**
     * The third client. `ModelDownloader` allow-lists redirect hosts of its own, so this store
     * **names the redirect target as the vendor's** — the only thing left to refuse the hop is
     * `DEC-0005`, and it does.
     *
     * And it refuses **once**. A refused hop is not a dropped connection: three more attempts
     * would follow the same `Location` to the same host for the same answer, so the transfer is
     * fatal on the first (`B-187`, `H8` from the other side).
     */
    @Test fun `a model download redirected off the private network stops on the first attempt`() = runTest {
        // Enough answers for every attempt the retry budget allows, so "it stopped at one" is a
        // measurement rather than an artefact of the queue running dry.
        repeat(ModelDownloader.MAX_ATTEMPTS) { origin.enqueue(redirectTo("cdn.example.com", "/model")) }
        elsewhere.enqueue(MockResponse().setBody("not the model"))
        val root = temp.newFolder("models")
        val store = object : ModelStore {
            override val modelName = "test-model.bin"
            override val expectedBytes = 64L
            override val expectedSha256 = ""
            override val downloadUrl = origin.url("/model").toString()
            override val allowedRedirectHosts = setOf("cdn.example.com")
            override fun modelFile() = File(root, modelName)
            override fun isPresent() = modelFile().isFile
        }

        val events = ModelDownloader(
            store,
            client = client(),
            url = store.downloadUrl,
            retryDelay = {},
        ).download().toList()

        assertEquals("the model request followed the redirect off the private network", 0, elsewhere.requestCount)
        assertEquals("a refused hop was retried as though the Wi-Fi had dropped", 1, origin.requestCount)
        assertTrue("a refused hop did not end the transfer", events.last() is DownloadProgress.Failed)
    }
}

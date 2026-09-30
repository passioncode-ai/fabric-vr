package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.FabricHttp
import ai.passioncode.fabricvr.common.InsecureHopException
import ai.passioncode.fabricvr.common.NetworkPolicyInterceptor
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NetworkPolicyInterceptor] on its own, hop by hop, and the wiring that puts it on every client
 * this module ships (`B-187`).
 *
 * **`RedirectPolicyTest` drives the real OkHttp redirect machinery and this does not, on purpose.**
 * That one proves the interceptor is reached for each hop of a real `307`, **including one that
 * begins with a real TLS handshake** since `B-198` added `okhttp-tls` to the catalogue. This class
 * states the downgrade as a pure function of one request: the interceptor decides from the request
 * in front of it, so a fabricated chain says whether `proceed` was reached at all, which is the
 * strongest available form of *the recording was never written*.
 *
 * The header this file used to carry argued the opposite — that a real handshake cost more than it
 * proved. It was wrong in the way `SI-09` names: a mocked test proves the rule the code implements,
 * never that the rule still matches the world. Both live now, and neither alone is the evidence.
 *
 * **What this class can no longer see, since `B-197` moved the factory to `:core-common`:** the
 * clients of every OTHER module. `FabricHttpSourceTest` there scans the tree's sources for exactly
 * that, because a per-module wiring assertion answered *are these three guarded?* while the
 * question was *are there only three?*
 */
class FabricHttpTest {

    /**
     * A chain that records whether it was proceeded with, and the call the hop belongs to.
     *
     * **`call()` used to throw, and the sentence it threw was the argument for `B-216` written
     * from the wrong side.** It said the interceptor *must* decide from the request in front of
     * it — which is true of `DEC-0005` and is exactly why that rule could not see a redirect
     * changing host: *somewhere else* has no meaning without the place the call started. The
     * cross-host rule reads `Call.request()`, OkHttp's own record of what `newCall` was handed,
     * so the fabrication now carries one. [origin] defaults to the hop itself, which is what the
     * first hop of every real call looks like.
     */
    private class FakeChain(
        private val request: Request,
        private val origin: Request = request,
    ) : Interceptor.Chain {
        var proceeded: Request? = null

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null

        /**
         * Only [Call.request] is answered. Everything else on the interface would be a fiction
         * this class cannot state honestly, so it says so rather than returning a plausible value.
         */
        override fun call(): Call = object : Call {
            override fun request(): Request = origin
            override fun execute(): Response = unsupported()
            override fun enqueue(responseCallback: okhttp3.Callback) = unsupported()
            override fun cancel() = unsupported()
            override fun isExecuted(): Boolean = unsupported()
            override fun isCanceled(): Boolean = unsupported()
            override fun timeout(): okio.Timeout = unsupported()
            override fun clone(): Call = unsupported()
            private fun unsupported(): Nothing =
                throw UnsupportedOperationException("the hop rule reads only the call's request")
        }
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    /** A hop carrying a recording, so "was it proceeded with" means "did the WAV go out". */
    private fun hop(url: String) = FakeChain(
        Request.Builder().url(url).post(ByteArray(64).toRequestBody()).build(),
    )

    private fun refusal(url: String, from: String = url): InsecureHopException {
        val chain = FakeChain(hop(url).request(), hop(from).request())
        val thrown = runCatching { NetworkPolicyInterceptor.intercept(chain) }.exceptionOrNull()
        assertTrue("the hop to $url was allowed: $thrown", thrown is InsecureHopException)
        assertNull("the recording was written to a hop that was refused", chain.proceeded)
        return thrown as InsecureHopException
    }

    /**
     * The shape `B-187` is named for: a configured `https` endpoint answers `307` with an `http`
     * `Location` on a public host. The interceptor sees only the second hop, which is the point —
     * the rule is absolute per hop rather than relative to where the call started, so it holds
     * whatever the origin was and cannot be defeated by an extra hop in between.
     */
    @Test fun `a downgrade to cleartext on a public host is refused`() {
        listOf(
            "http://whisper.example.com/inference",
            "http://8.8.8.8/v1/audio/transcriptions",
            "http://[2001:db8::1]/inference",
            "http://172.32.0.1/inference",
            // `C-23` and `C-01`, at the hop rather than at the address bar: a name that merely
            // ends in `.local` is not mDNS, and userinfo is not a host.
            "http://example.com.local/inference",
        ).forEach { refusal(it) }
    }

    /** The refusal names what it refused, because that is the only part anybody can act on. */
    @Test fun `a refusal names the host and nothing else from the url`() {
        val refused = refusal("http://whisper.example.com/inference?token=secret")

        assertEquals("whisper.example.com", refused.host)
        val url = (refused.error as AppError.InsecureUrl).url
        assertEquals("http://whisper.example.com", url)
        assertTrue(
            "the refused path or query reached the error, and a query is where a token is",
            "secret" !in url && "inference" !in url,
        )
    }

    /**
     * **`B-216`: a hop that is perfectly encrypted and still goes somewhere else.**
     *
     * Every case above is about cleartext, and `NetworkPolicy.permits` answers yes to every
     * `https` host — so a `307` from the address in Settings re-POSTed the whole WAV to
     * `https://attacker` and nothing here said a word. The refusal carries
     * [AppError.RedirectRefused] and not [AppError.InsecureUrl], because the person would
     * otherwise read *"Only https, or http to a device on your own network"* about a hop that was
     * `https` at both ends.
     */
    @Test fun `an https hop to another host is refused when the request carries something`() {
        val refused = refusal(
            "https://attacker.example.com/inference",
            from = "https://whisper.example.com/inference",
        )

        assertEquals("attacker.example.com", refused.host)
        assertEquals(AppError.RedirectRefused("attacker.example.com"), refused.error)
    }

    /**
     * The trade this rule makes, stated as a test rather than as a sentence.
     *
     * A `GET` with no body and no key has nothing of the person's on it to misdirect, and the
     * model download's vendor CDN hop is a cross-host redirect **by design** — `resolve/main`
     * always answers with one. Refusing it would make the 190–574 MB download impossible for no
     * gain, since the pinned SHA-256 and the store's own allow-list are what judge what comes
     * back.
     */
    @Test fun `an https hop to another host is made when the request carries nothing`() {
        val chain = FakeChain(
            Request.Builder().url("https://cdn.example.com/model").build(),
            Request.Builder().url("https://models.example.com/model").build(),
        )

        NetworkPolicyInterceptor.intercept(chain)

        assertEquals(
            "a credential-free download could no longer follow its vendor's CDN",
            "https://cdn.example.com/model",
            chain.proceeded?.url?.toString(),
        )
    }

    /**
     * `DEC-0005`'s own trust boundary, applied to the new rule rather than restated beside it.
     *
     * A local gateway in front of a whisper-server redirects to it, and both ends are addresses
     * that decision already lets a recording reach in the clear — so following the hop exposes
     * nothing that was not already permitted. This is what keeps `DEC-0077`'s accepted case true.
     */
    @Test fun `a hop between two hosts on the person's own network is made`() {
        listOf(
            "http://192.168.1.50:8080/inference" to "http://whisper.local:8080/inference",
            "http://whisper.local:8080/inference" to "http://127.0.0.1:41234/inference",
        ).forEach { (from, to) ->
            val chain = FakeChain(hop(to).request(), hop(from).request())
            NetworkPolicyInterceptor.intercept(chain)
            assertEquals("$from -> $to was refused", to, chain.proceeded?.url?.toString())
        }
    }

    /**
     * The other half of the exemption above: leaving that network is the whole of `B-216`.
     *
     * A whisper-server on a network the person does not own — a café, a hotel, an office — can
     * answer `307` with an `https` address, and the encryption is what makes it invisible.
     */
    @Test fun `a hop from the person's own network to a public https host is refused`() {
        val refused = refusal(
            "https://attacker.example.com/inference",
            from = "http://192.168.1.50:8080/inference",
        )

        assertEquals(AppError.RedirectRefused("attacker.example.com"), refused.error)
    }

    /**
     * The control. Without it, the four cases above would be satisfied by *refuse every redirect*,
     * which would break every server that moves its own route.
     */
    @Test fun `a hop to another path on the same host is made`() {
        val chain = FakeChain(
            hop("https://whisper.example.com/v2/inference").request(),
            hop("https://whisper.example.com/inference").request(),
        )

        NetworkPolicyInterceptor.intercept(chain)

        assertEquals(
            "a same-host redirect was refused",
            "https://whisper.example.com/v2/inference",
            chain.proceeded?.url?.toString(),
        )
    }

    /**
     * The `Authorization` half of *carries something*, which OkHttp's own stripping usually gets
     * to first. Asserted rather than trusted, for `B-187`'s reason: a rule nobody checks is not a
     * rule, and "usually" is not one either.
     */
    @Test fun `an https hop to another host is refused for a bodyless request carrying a key`() {
        val chain = FakeChain(
            Request.Builder().url("https://attacker.example.com/v1/models")
                .header("Authorization", "Bearer sk-test").build(),
            Request.Builder().url("https://api.groq.com/v1/models").build(),
        )

        val thrown = runCatching { NetworkPolicyInterceptor.intercept(chain) }.exceptionOrNull()

        assertTrue("the key was carried to another host: $thrown", thrown is InsecureHopException)
        assertNull("the request was written to a hop that was refused", chain.proceeded)
    }

    /**
     * `DEC-0005`'s own case, at the hop: a whisper-server on the person's network cannot hold a
     * certificate, so a redirect that lands inside that network is followed with the body intact.
     */
    @Test fun `a hop into the private network is made`() {
        listOf(
            "http://192.168.1.50:8080/inference",
            "http://10.1.2.3:8080/inference",
            "http://172.16.4.5/inference",
            "http://127.0.0.1:41234/inference",
            "http://[fd00::1]/inference",
            "http://whisper.local:8080/inference",
            // https is reachable anywhere, which is what makes the cloud client possible at all.
            "https://api.groq.com/openai/v1/audio/transcriptions",
        ).forEach { url ->
            val chain = hop(url)
            NetworkPolicyInterceptor.intercept(chain)
            assertEquals("the hop to $url was refused", url, chain.proceeded?.url?.toString())
        }
    }

    /**
     * **The wiring, asserted rather than trusted.** Three classes build a client each, for reasons
     * about timeouts; a fourth was written the same way and did forget this, which is `B-187` a
     * second time and is `B-197` — it simply happened in `:feature-assistant`, where this test
     * cannot look. The factory is one line and this is what makes skipping it visible **here**;
     * `FabricHttpSourceTest` in `:core-common` is what makes it visible anywhere.
     */
    @Test fun `every client this module ships enforces the hop rule`() {
        val clients: Map<String, OkHttpClient> = mapOf(
            "RemoteWhisperClient" to RemoteWhisperClient.SHARED,
            "CloudTranscriptionClient" to CloudTranscriptionClient.SHARED,
            "ModelDownloader" to ModelDownloader.SHARED,
        )

        clients.forEach { (name, client) ->
            assertTrue(
                "$name builds its own OkHttpClient without the DEC-0005 hop rule",
                NetworkPolicyInterceptor in client.networkInterceptors,
            )
        }
    }
}

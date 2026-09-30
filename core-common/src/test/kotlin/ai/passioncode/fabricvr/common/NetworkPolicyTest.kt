package ai.passioncode.fabricvr.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    private fun allowed(url: String) = NetworkPolicy.requireReachable(url).isSuccess

    @Test fun `cleartext reaches a device on this network`() {
        assertTrue(allowed("http://192.168.1.20:8080"))
        assertTrue(allowed("http://10.1.2.3:8080"))
        assertTrue(allowed("http://172.16.4.5"))
        assertTrue(allowed("http://127.0.0.1:41234"))
        assertTrue(allowed("http://localhost:8080"))
        assertTrue(allowed("http://whisper.local:8080"))
    }

    @Test fun `cleartext to the open internet is refused`() {
        assertFalse(allowed("http://example.com:8080"))
        assertFalse(allowed("http://8.8.8.8"))
        assertFalse(allowed("http://172.32.0.1"))
    }

    @Test fun `https is allowed anywhere`() {
        assertTrue(allowed("https://openrouter.ai/api/v1"))
        assertTrue(allowed("https://192.168.1.20:8443"))
    }

    @Test fun `anything that is not http is refused`() {
        assertFalse(allowed("ftp://192.168.1.20"))
        assertFalse(allowed("not a url"))
        assertFalse(allowed(""))
        assertFalse(allowed("http://"))
    }

    @Test fun `a refusal carries the url it refused`() {
        val failure = NetworkPolicy.requireReachable("http://example.com")
        val error = (failure.exceptionOrNull() as InsecureUrlException).error as AppError.InsecureUrl
        assertTrue(error.url.contains("example.com"))
    }
    // --- T-010: the host is what the HTTP client will resolve, not what the string looks like ---

    /**
     * C-01. The host was taken as everything before the first `/` and then before the first `:`,
     * which reads a URL's **userinfo** as its host. `http://192.168.0.5:8080@evil.invalid/`
     * therefore passed the private-address test while OkHttp, which follows RFC 3986, resolves
     * `evil.invalid` — so the cloud Bearer key and a WAV of the person's voice travelled in
     * the clear to whatever host the attacker put after the `@`.
     */
    @Test fun `userinfo cannot pose as the host`() {
        listOf(
            "http://192.168.0.5:8080@evil.invalid/",
            "http://127.0.0.1@evil.invalid/v1",
            "http://localhost@evil.invalid/",
            "http://user:192.168.1.1@evil.invalid/",
        ).forEach { url ->
            assertTrue(
                "a recording would have gone in the clear to the host after the @: $url",
                NetworkPolicy.requireReachable(url).isFailure,
            )
        }
    }

    /**
     * C-23, graded Low in the audit and raised here: it is the same class of bug as C-01, in the
     * same function. `.local` is mDNS, and RFC 6762 restricts those names to a **single** label —
     * so `printer.local` is one and `example.com.local` is not, however much it ends in `.local`.
     */
    @Test fun `a multi-label local name is not an mDNS name`() {
        assertTrue(NetworkPolicy.requireReachable("http://printer.local/").isSuccess)
        assertTrue(NetworkPolicy.requireReachable("http://quest-3.local:8080/").isSuccess)
        listOf("http://example.com.local/", "http://evil.co.uk.local/inference").forEach { url ->
            assertTrue("a public-looking name passed the mDNS test: $url", NetworkPolicy.requireReachable(url).isFailure)
        }
    }

    /** The other half of C-23: a loopback the person genuinely has was refused. */
    @Test fun `IPv6 loopback and unique-local addresses are reachable in the clear`() {
        listOf(
            "http://[::1]:8080/inference",
            "http://[fd00::1]/inference",          // unique local, RFC 4193
            "http://[fe80::1%25eth0]/inference",   // link local
        ).forEach { url ->
            assertTrue("a private IPv6 host was refused: $url", NetworkPolicy.requireReachable(url).isSuccess)
        }
    }

    @Test fun `a public IPv6 host in the clear is refused`() {
        assertTrue(NetworkPolicy.requireReachable("http://[2001:db8::1]/v1").isFailure)
    }

    /**
     * A host the parser cannot identify is refused rather than guessed at. The app must know where
     * it is about to send a person's voice; "probably fine" is not a property of a URL.
     */
    @Test fun `a host this app cannot parse is refused rather than assumed`() {
        listOf(
            "http:///v1",
            "http://",
            "http:// 192.168.0.5/",
            "http://192.168.0.5 evil.invalid/",
        ).forEach { url ->
            assertTrue("an unparseable host was let through: $url", NetworkPolicy.requireReachable(url).isFailure)
        }
    }

    // --- B-187: the same rule, asked again for every hop an HTTP client is about to make ---

    /**
     * **The two doors onto the rule must answer the same thing.** `requireReachable` is asked once
     * about the address in Settings and `permits` is asked again for each redirect the server
     * names; a divergence between them is a cleartext hole with a green test over it, because
     * each door has its own cases. Asserted as a table across both rather than as two lists.
     */
    @Test fun `the hop rule and the url rule agree`() {
        mapOf(
            "https://api.groq.com" to true,
            "https://192.168.1.20:8443" to true,
            "http://192.168.1.20:8080" to true,
            "http://10.1.2.3:8080" to true,
            "http://172.16.4.5" to true,
            "http://127.0.0.1:41234" to true,
            "http://localhost:8080" to true,
            "http://printer.local" to true,
            "http://[::1]:8080" to true,
            "http://[fd00::1]" to true,
            "http://example.com:8080" to false,
            "http://8.8.8.8" to false,
            "http://172.32.0.1" to false,
            "http://example.com.local" to false,
            "http://[2001:db8::1]" to false,
        ).forEach { (url, expected) ->
            val parsed = java.net.URI(url)
            assertEquals(
                "the hop rule disagrees with the url rule about $url",
                expected,
                NetworkPolicy.permits(parsed.scheme, parsed.host?.removeSurrounding("[", "]")),
            )
            assertEquals(
                "the url rule moved under the hop rule: $url",
                expected,
                NetworkPolicy.requireReachable(url).isSuccess,
            )
        }
    }

    /**
     * A hop whose scheme or host the client could not give us is refused, on the same principle
     * as an unparseable URL: "probably fine" is not a property of a hop either.
     */
    @Test fun `a hop this app cannot identify is refused`() {
        assertFalse(NetworkPolicy.permits(null, "192.168.1.20"))
        assertFalse(NetworkPolicy.permits("http", null))
        assertFalse(NetworkPolicy.permits("http", ""))
        assertFalse(NetworkPolicy.permits("http", "  "))
        assertFalse(NetworkPolicy.permits("ftp", "192.168.1.20"))
        assertFalse(NetworkPolicy.permits("http", "192.168.0.5 evil.invalid"))
    }

    /** The scheme arrives from whatever named it, so it is compared without regard to case. */
    @Test fun `the scheme is matched without regard to case`() {
        assertTrue(NetworkPolicy.permits("HTTPS", "api.groq.com"))
        assertTrue(NetworkPolicy.permits("Http", "192.168.1.20"))
        assertFalse(NetworkPolicy.permits("HTTP", "example.com"))
    }

    /**
     * **`B-216`. [NetworkPolicy.permits] answers yes to every `https` host, correctly**, and that
     * is why it could not see this: TLS is TLS wherever it points, so a `307` moved the whole
     * recording to `https://attacker` with every check in this file saying yes.
     */
    @Test fun `a redirect off the configured host is refused`() {
        assertFalse(NetworkPolicy.permitsRedirect("whisper.example.com", "attacker.example.com"))
        assertFalse(NetworkPolicy.permitsRedirect("api.groq.com", "api.groq.com.evil.example"))
        // Leaving the person's own network is the shape the row is named for: a whisper-server on
        // a network they do not own, answering with a public address.
        assertFalse(NetworkPolicy.permitsRedirect("192.168.1.50", "attacker.example.com"))
        // And the other direction, which is the same defect pointed inward.
        assertFalse(NetworkPolicy.permitsRedirect("api.groq.com", "192.168.1.50"))
    }

    /**
     * The control, without which the rule above would be *refuse every redirect* — a server that
     * moves its own route is ordinary, and this rule is about the host and nothing else.
     */
    @Test fun `a redirect that stays on the configured host is followed`() {
        assertTrue(NetworkPolicy.permitsRedirect("whisper.example.com", "whisper.example.com"))
        // The host arrives from whatever named it, and DNS does not care about case.
        assertTrue(NetworkPolicy.permitsRedirect("Whisper.Example.com", "whisper.example.COM"))
        assertTrue(NetworkPolicy.permitsRedirect(" whisper.local ", "whisper.local"))
    }

    /**
     * `DEC-0005`'s own trust boundary, not a softening of the rule: both ends are addresses that
     * decision already lets a recording reach **in the clear**, so following the hop exposes
     * nothing that was not already permitted. This is what keeps `DEC-0077`'s accepted case — a
     * local gateway in front of a whisper-server — true.
     */
    @Test fun `a redirect between two hosts on this network is followed`() {
        assertTrue(NetworkPolicy.permitsRedirect("192.168.1.50", "whisper.local"))
        assertTrue(NetworkPolicy.permitsRedirect("localhost", "10.1.2.3"))
        assertTrue(NetworkPolicy.permitsRedirect("[fd00::1]", "127.0.0.1"))
        // `C-23` reaches here too: a name that merely ends in `.local` is not mDNS, so it is not
        // one of the addresses the exemption is about.
        assertFalse(NetworkPolicy.permitsRedirect("192.168.1.50", "example.com.local"))
    }

    /** "The client could not tell me" lands on the same answer as "somewhere else" — refusal. */
    @Test fun `a redirect this app cannot identify is refused`() {
        assertFalse(NetworkPolicy.permitsRedirect(null, "whisper.local"))
        assertFalse(NetworkPolicy.permitsRedirect("whisper.local", null))
        assertFalse(NetworkPolicy.permitsRedirect("", ""))
        assertFalse(NetworkPolicy.permitsRedirect("  ", "  "))
    }
}

package ai.passioncode.fabricvr.common

import java.net.URI

/**
 * Where this app is willing to send bytes in the clear (`DEC-0005`), in one place so the HTTP
 * clients cannot drift apart.
 *
 * The rule is about reach, not about protocol purity: a whisper-server on the person's own network
 * cannot hold a certificate, and refusing it would make the feature impossible; a mistyped public
 * host, on the other hand, would put a recording or a question on the open internet unencrypted.
 *
 * **The host is parsed, never carved out of the string.** It used to be "everything before the
 * first `/`, then everything before the first `:`", which reads a URL's *userinfo* as its host:
 * `http://192.168.0.5:8080@evil.invalid/` passed the private-address test while OkHttp — which
 * follows RFC 3986 — resolves `evil.invalid`, so the cloud Bearer key and a WAV of the
 * person's voice went in the clear to whatever the attacker put after the `@` (`C-01`). The same
 * surgery refused `http://[::1]:8080/`, an address the person genuinely has (`C-23`).
 *
 * **[requireReachable] judges a string; [permits] judges a hop.** They are two doors onto one rule
 * and they must never answer differently: the first is asked once, about the address in Settings,
 * and the second is asked again for every redirect the server then names (`B-187`). A URL that
 * passed the first door used to be followed anywhere at all, so a `307` could move the recording
 * and the transcription key to a public host in the clear. Two copies of a cleartext rule is how
 * one of them quietly stops being the rule, which is why the second door delegates to the first's
 * predicate rather than restating it.
 */
object NetworkPolicy {

    private val PRIVATE_IPV4 = Regex(
        """^(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|127\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})$""",
    )

    /** True for a host the app may reach over plain http. Takes the host, not a URL. */
    fun isPrivateHost(host: String): Boolean {
        val bare = host.trim().lowercase().removeSurrounding("[", "]")
        if (bare.isBlank()) return false
        if (bare == "localhost") return true
        if (PRIVATE_IPV4.matches(bare)) return true
        if (bare.contains(':')) return isPrivateIpv6(bare)
        // mDNS, and RFC 6762 restricts those names to a SINGLE label — `printer.local` is one,
        // `example.com.local` is not, however much it ends in `.local`. Accepting the second let a
        // public-looking name into the cleartext allow-list (`C-23`).
        return bare.endsWith(LOCAL) && bare.dropLast(LOCAL.length).let {
            it.isNotEmpty() && !it.contains('.')
        }
    }

    /**
     * Loopback, unique-local (`fc00::/7`, RFC 4193) and link-local (`fe80::/10`). A zone index —
     * `fe80::1%eth0` — is part of the address, not part of the host name.
     */
    private fun isPrivateIpv6(bare: String): Boolean {
        val address = bare.substringBefore('%')
        if (address == "::1") return true
        val head = address.substringBefore(':')
        if (head.length < 2) return false
        val prefix = head.take(2).toIntOrNull(16) ?: return false
        return prefix in 0xfc..0xfd || (prefix == 0xfe && head.length >= 3 &&
            (head[2].lowercaseChar() in '8'..'9' || head[2].lowercaseChar() in 'a'..'b'))
    }

    /**
     * Whether ONE hop — one scheme to one host — may carry bytes. The whole of `DEC-0005`, with
     * nothing about paths, ports or query strings in it.
     *
     * Takes the parts an HTTP client already holds rather than a string, because the caller that
     * needs this is an OkHttp interceptor looking at a `HttpUrl` the client itself resolved:
     * re-serialising that to a string so this could parse it back is exactly the round trip
     * `T-010` proved unsafe. Both arguments are nullable so that "the client could not tell me"
     * lands on the same answer as "the host is public" — refusal. The app must know where it is
     * about to send a person's voice.
     */
    fun permits(scheme: String?, host: String?): Boolean {
        val protocol = scheme?.trim()?.lowercase() ?: return false
        if (protocol != "http" && protocol != "https") return false
        val target = host?.trim() ?: return false
        if (target.isBlank() || target.contains(' ')) return false
        return protocol == "https" || isPrivateHost(target)
    }

    /**
     * Whether a request that **carries something** may move from [originHost] to [targetHost]
     * (`B-216`).
     *
     * **A different question from [permits], and that is the whole of this row.** [permits] asks
     * *may these bytes travel in the clear*, and it answers yes to every `https` host — correctly,
     * because TLS is TLS wherever it points. So a whisper-server answering `307` re-POSTed the
     * entire WAV to `https://attacker`, encrypted, to somebody the person never named, and every
     * check in this file said yes. `ModelDownloader` had carried a vendor allow-list against
     * exactly this shape since it was written; the two clients that send a person's voice had
     * none.
     *
     * **The rule: a hop may not change host.** Same host, any path, any port — followed, because a
     * server moving its own route is ordinary. Another host — refused, because a redirect to
     * somewhere else is not something a transcription endpoint needs, and whisper.cpp's own server
     * does not issue one at all.
     *
     * **The one exemption, and it is `DEC-0005`'s own trust boundary rather than a softening of
     * this rule.** When both ends are hosts that decision already lets a recording reach in the
     * clear — RFC1918, loopback, a single-label `.local` — the redirect moves the recording
     * somewhere it was already permitted to go, so nothing new is exposed by following it. That
     * exemption is what keeps `DEC-0077`'s accepted case true: a local gateway in front of a
     * whisper-server still redirects to it.
     *
     * **What it deliberately does not cover: a request with nothing on it.** A model download is a
     * `GET` with no body and no `Authorization`, and a vendor's `resolve/main` ALWAYS answers it
     * with a cross-host redirect to a CDN — refusing that would make the 190–574 MB download
     * impossible for no gain, since what comes back is judged by a pinned SHA-256 and by the
     * store's own allow-list. Whether a request carries anything is an HTTP question and is asked
     * by [NetworkPolicyInterceptor]; this function answers only *may these two hosts differ*.
     *
     * Both arguments nullable for [permits]'s reason: "the client could not tell me" is the same
     * answer as "somewhere else" — refusal.
     */
    fun permitsRedirect(originHost: String?, targetHost: String?): Boolean {
        val from = originHost?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        val to = targetHost?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        if (from == to) return true
        return isPrivateHost(from) && isPrivateHost(to)
    }

    /**
     * @return the normalised URL, or [AppError.InsecureUrl] when it would travel in the clear to
     * somewhere the app cannot vouch for — **including a URL whose host this app cannot identify
     * at all**. "Probably fine" is not a property of a URL, and the app must know where it is
     * about to send a person's voice.
     */
    fun requireReachable(url: String): Result<String> {
        val trimmed = url.trim()
        val refusal = Result.failure<String>(InsecureUrlException(AppError.InsecureUrl(url)))

        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return refusal
        val scheme = parsed.scheme?.lowercase() ?: return refusal
        if (scheme != "http" && scheme != "https") return refusal

        // `URI.host` is null for an authority the RFC does not recognise — an underscore, a space,
        // a stray `@`. That is exactly the case to refuse: whatever the client would resolve, this
        // policy could not check it.
        val host = parsed.host ?: return refusal

        return if (permits(scheme, host)) Result.success(trimmed) else refusal
    }

    private const val LOCAL = ".local"
}

/** Carries the refusal so a caller can map it through the one message mapper. */
class InsecureUrlException(val error: AppError) : Exception()

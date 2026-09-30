package ai.passioncode.fabricvr.common

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A hop this app refuses to make, as the one exception shape that survives OkHttp.
 *
 * **It is an [IOException] and that is not cosmetic.** Both remote clients reach the network
 * through `Call.await()`, which is `enqueue`; OkHttp's `AsyncCall` hands an `IOException` to
 * `onFailure` unchanged, and wraps anything else in `IOException("canceled due to …")` with the
 * original merely suppressed. A refusal thrown as a plain `Exception` would therefore arrive at
 * the client with its [AppError] already lost, and the person would be told their dictation was
 * cancelled.
 *
 * **One exception, two reasons, and the reason travels in [error].** `B-216` added a second rule —
 * a hop may not change host while carrying a body or a key — and it could have arrived as a second
 * exception class. It did not: every client already names this type before `toAppError`, and a
 * second one would have had to be added to four `when`s in three modules, one of which
 * (`:feature-assistant`) is the module that proved it would be missed (`B-197`). Widening [error]
 * from `AppError.InsecureUrl` to [AppError] is what lets the new refusal reach the person through
 * the path the old one already had.
 *
 * @param error why the hop was refused: [AppError.InsecureUrl] for `DEC-0005`, or
 *   [AppError.RedirectRefused] for the cross-host rule. The distinction is the sentence the person
 *   reads, and they are not the same sentence.
 * @param host what the redirect named. It reaches the log and the error; it is the only part of
 *   the event anybody can act on.
 */
class InsecureHopException(val error: AppError, val host: String) :
    IOException("refused a hop to $host")

/**
 * `DEC-0005` applied to **every** hop of a call, not only to the address the person typed
 * (`B-187`).
 *
 * `NetworkPolicy.requireReachable` guards what goes into Settings. OkHttp then follows redirects
 * by default (`followRedirects`, `followSslRedirects`) and the manifest permits cleartext, so a
 * `307` or `308` from a configured `https` endpoint moved the WAV — and, from
 * `CloudTranscriptionClient`, the `Authorization` header carrying the transcription key — to
 * whatever host the server named, in the clear, with nothing checking where it landed.
 *
 * **A NETWORK interceptor, not an application one, and the difference is the whole fix.** An
 * application interceptor runs once per *call*: above `RetryAndFollowUpInterceptor`, so it sees
 * the original request and the final response and never the redirect in between — it cannot
 * refuse a hop it is not shown. A network interceptor runs once per *hop*, inside that loop, so
 * the rule is re-applied to each `Location` the server names. It also sits above
 * `CallServerInterceptor`, which is what writes the request line, the headers and the body, so
 * throwing here means **no byte of the recording and no byte of the key is written to that
 * socket**.
 *
 * **What it does NOT prevent, said plainly.** Network interceptors run *after*
 * `ConnectInterceptor`, so by the time this refuses, the redirect host has already been resolved
 * and a TCP connection (and, for an `https` target, a TLS handshake carrying SNI) opened to it.
 * A malicious server can therefore still learn that this device followed its redirect far enough
 * to connect. Closing that too means turning `followRedirects` off and re-implementing OkHttp's
 * redirect semantics — `followUpRequest`, the 20-hop cap, relative `Location` resolution, the
 * method and body rules for 301/302/303 against 307/308, and the header stripping below — by
 * hand, in a security control, where a subtle mistake is worse than the connect it saves.
 * The thing `B-187` is about is the payload, and the payload does not move.
 *
 * **The `Authorization` header is already dropped across hosts, and that is measured rather than
 * assumed.** OkHttp removes it in `RetryAndFollowUpInterceptor.buildRedirectRequest` whenever the
 * redirect target cannot reuse the connection — a different scheme, host or port — and
 * `RedirectPolicyTest` asserts it, because a rule nobody checks is exactly what this row is about.
 * It is defence beside this one, not instead of it: it says nothing about the recording, which is
 * re-sent in full on a 307.
 *
 * **`ModelDownloader` keeps its own, post-hoc, vendor allow-list as well.** That check answers a
 * different question — *is this still the vendor's CDN?* — on a credential-free `GET` whose real
 * guard is the pinned SHA-256, and `hf.co` is deliberately allowed although it shares no
 * registrable domain with `huggingface.co`. This interceptor is now also on that client, so the
 * two compose: the hop must be one `DEC-0005` permits *and* land on a host the store names.
 */
object NetworkPolicyInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!NetworkPolicy.permits(url.scheme, url.host)) {
            Log2.w("http.hop_refused", "scheme" to url.scheme, "host" to url.host)
            // Scheme and host only. A refused `Location` can carry a path or a query, and a
            // query is where a token ends up; the error is rendered, logged and kept, so it
            // carries the two parts the decision was actually made on and nothing else.
            throw InsecureHopException(AppError.InsecureUrl("${url.scheme}://${url.host}"), url.host)
        }
        // **`B-216`. The second rule, and it is the only one here that is not a property of one
        // hop.** `DEC-0005` is absolute per hop — it can be decided from the request in front of
        // it — which is exactly why it could not see this: *the host changed* has no meaning
        // without the host the call started on. `Call.request()` is OkHttp's own record of the
        // request `newCall` was handed, unchanged by the redirect loop above us, so it is where
        // the origin comes from rather than from anything this object would have to remember.
        //
        // **Asked only of a request that carries something**, because that is what a misdirected
        // redirect steals: a WAV of the person talking, or the key on it. A credential-free `GET`
        // — the model download, whose vendor CDN hop is a cross-host redirect by design — is left
        // alone, and its own allow-list plus a pinned SHA-256 are what judge it.
        if (request.carriesPayload() &&
            !NetworkPolicy.permitsRedirect(chain.call().request().url.host, url.host)
        ) {
            Log2.w("http.redirect_refused", "host" to url.host)
            throw InsecureHopException(AppError.RedirectRefused(url.host), url.host)
        }
        return chain.proceed(request)
    }

    /**
     * Whether this request has anything of the person's on it.
     *
     * A body is the recording or the prompt; `Authorization` is the key. OkHttp already strips the
     * header when a redirect cannot reuse the connection, so on a cross-host hop it is usually
     * gone by the time this reads it — it is named anyway, because "usually" is not a rule and the
     * whole of `B-187` is that a rule nobody checks stops being one. Header lookup is
     * case-insensitive in OkHttp, so a server naming it differently does not slip past.
     */
    private fun Request.carriesPayload(): Boolean =
        body != null || header("Authorization") != null
}

/**
 * **Every `OkHttpClient` in this repository starts here**, and it lives beside [NetworkPolicy]
 * rather than inside one feature because the rule it carries is the whole app's (`B-197`).
 *
 * Four classes build one each — `RemoteWhisperClient`, `CloudTranscriptionClient`,
 * `ModelDownloader` and `OpenRouterClient` — for reasons that are about timeouts and have nothing
 * to do with policy (`I-28`, `DEC-0031`). A fifth would be written the same way, and the hop check
 * would be forgotten on it.
 *
 * **It was `internal` to `:feature-stt` and that is precisely how the fourth one went unguarded.**
 * `DEC-0077` put the interceptor on every client a `:feature-stt` test can hold, and
 * `OpenRouterClient` — in `:feature-assistant`, which cannot import an `internal` symbol of
 * another module — built its own client and followed redirects unchecked for two days (`B-197`).
 * The alternative on the table was to duplicate the four lines in the assistant. It was refused
 * for the reason [NetworkPolicy]'s own header gives about its two doors: two copies of a cleartext
 * rule is how one of them quietly stops being the rule. A shared home costs `:core-common` an
 * `api` dependency on OkHttp — visible to every module, used by two — and buys one definition of
 * *where this app will send bytes in the clear*.
 *
 * `FabricHttpTest` in `:feature-stt` asserts that the clients that module ships carry the
 * interceptor; `FabricHttpSourceTest` beside this file asserts that **no module anywhere in the
 * tree** constructs a client outside this factory, which is the half a per-module test cannot see.
 */
object FabricHttp {
    fun builder(): OkHttpClient.Builder =
        OkHttpClient.Builder().addNetworkInterceptor(NetworkPolicyInterceptor)
}

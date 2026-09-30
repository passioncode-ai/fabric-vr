package ai.passioncode.fabricvr.assistant

import ai.passioncode.fabricvr.common.Log2
import okhttp3.Interceptor
import okhttp3.Response

/**
 * One retry, for the three statuses OpenRouter's own documentation describes as transient: rate
 * limiting and a provider being down. `Retry-After` is honoured when the provider sends it, capped
 * so a headset never sits blocked for a minute on a header it did not choose.
 *
 * Only one retry, and only before any token has been delivered — re-sending a request whose answer
 * has already started would duplicate text in the person's chat.
 */
class RetryInterceptor(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code !in TRANSIENT) return response

        val wait = response.header("Retry-After")?.toLongOrNull()?.times(1_000)
            ?: DEFAULT_WAIT_MS
        Log2.w("assistant.retry", "status" to response.code, "waitMs" to wait)
        response.close()
        sleep(wait.coerceAtMost(MAX_WAIT_MS))
        return chain.proceed(chain.request())
    }

    private companion object {
        val TRANSIENT = setOf(429, 502, 503)
        const val DEFAULT_WAIT_MS = 1_000L
        const val MAX_WAIT_MS = 20_000L
    }
}

package ai.passioncode.fabricvr.assistant

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.FabricHttp
import ai.passioncode.fabricvr.common.InsecureHopException
import ai.passioncode.fabricvr.common.NetworkPolicy
import ai.passioncode.fabricvr.common.toAppError
import ai.passioncode.fabricvr.common.Log2
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.EOFException
import java.io.IOException

/** The assistant's only outward call. Streams tokens; every failure arrives as an [AppError]. */
class OpenRouterClient(
    private val apiKey: () -> String?,
    private val client: OkHttpClient = SHARED,
    private val baseUrl: String = "https://openrouter.ai/api/v1",
) {
    init {
        // DEC-0005: https to a public provider, cleartext only to a host on this network — which is
        // what makes a loopback test server legitimate and a mistyped public host refused.
        NetworkPolicy.requireReachable(baseUrl).getOrThrow()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Tokens are sent with [trySendBlocking], not `trySend`: the reader is a UI collector and the
     * provider can outrun it. With a bounded buffer and a non-blocking send, 335 of 400 tokens were
     * measured lost — and a token lost mid-answer is invisible, the text simply reads wrong.
     */
    fun stream(model: String, messages: List<ChatMessage>): Flow<StreamEvent> = callbackFlow {
        val key = apiKey()
        if (key.isNullOrBlank()) {
            close(AssistantException(AppError.NoApiKey))
            return@callbackFlow
        }
        val body = json.encodeToString(ChatRequest.serializer(), ChatRequest(model, messages))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", "https://passioncode.ai")
            .addHeader("X-Title", "Fabric VR")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // `InsecureHopException` is named BEFORE the generic network shape, exactly as the
                // two speech clients name it (`B-197`). It IS an `IOException` so that it survives
                // OkHttp's `AsyncCall` unwrapped — and that is also why it would otherwise be
                // swallowed here as *the network failed*, when what happened is that the provider
                // tried to move the person's notes and their key to a host in the clear.
                close(
                    AssistantException(
                        if (e is InsecureHopException) e.error else AppError.Network(e),
                    ),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        val text = r.body?.string().orEmpty()
                        val parsed = runCatching {
                            json.decodeFromString(ErrorEnvelope.serializer(), text).error
                        }.getOrNull()
                        Log2.w("assistant.http_error", "status" to r.code)
                        close(
                            AssistantException(
                                AppError.OpenRouter(r.code, parsed?.code?.toString(), parsed?.message),
                            ),
                        )
                        return
                    }
                    val source = r.body?.source()
                    if (source == null) {
                        close(AssistantException(AppError.OpenRouter(r.code, null, "empty body")))
                        return
                    }
                    var sawFinish = false
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8LineStrict()
                            when (val parsed = SseParser.event(line)) {
                                is SseParser.Parsed.Data -> {
                                    // A provider can fail *after* the 200. Such a frame decodes into
                                    // an empty chunk and used to pass unnoticed, so the answer simply
                                    // stopped with no explanation.
                                    val failure = runCatching {
                                        json.decodeFromString(ErrorEnvelope.serializer(), parsed.json).error
                                    }.getOrNull()
                                    if (failure != null) {
                                        close(
                                            AssistantException(
                                                AppError.OpenRouter(
                                                    failure.code,
                                                    failure.code.toString(),
                                                    failure.message,
                                                ),
                                            ),
                                        )
                                        return
                                    }
                                    val chunk = runCatching {
                                        json.decodeFromString(StreamChunk.serializer(), parsed.json)
                                    }.getOrNull() ?: continue
                                    chunk.choices.firstOrNull()?.delta?.content
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { trySendBlocking(StreamEvent.Token(it)) }
                                    if (chunk.choices.firstOrNull()?.finishReason != null) sawFinish = true
                                    chunk.usage?.let {
                                        trySendBlocking(StreamEvent.Usage(it.promptTokens, it.completionTokens))
                                    }
                                }
                                SseParser.Parsed.DoneMarker -> {
                                    trySendBlocking(StreamEvent.Done)
                                    close()
                                    return
                                }
                                SseParser.Parsed.Comment, SseParser.Parsed.Ignore -> Unit
                            }
                        }
                        if (sawFinish) {
                            trySendBlocking(StreamEvent.Done)
                            close()
                        } else {
                            // The connection ended mid-answer. Emitting Done here presented a
                            // truncated answer as a complete one, with no way to ask again.
                            close(AssistantException(AppError.Network(EOFException("stream ended early"))))
                        }
                    } catch (t: Throwable) {
                        // Anything that escapes here without closing leaves the collector waiting
                        // forever: no tokens, no error, no timeout.
                        close(
                            AssistantException(
                                if (t is IOException) AppError.Network(t) else t.toAppError("assistant"),
                            ),
                        )
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }
        // `flowOn`, not a `withContext` inside the block: the prologue above — `apiKey()`, which is
        // a Keystore decrypt, `encodeToString` of the whole 12 KB context, and `newCall(...)` —
        // runs in the COLLECTOR's context, and the collector is a UI coroutine. Only `flowOn`
        // moves it; `withContext` inside a `callbackFlow` does not, and is a compile error at the
        // `ProducerScope` boundary anyway.
        //
        // The buffer is explicit and it is load-bearing. `flowOn` inserts a channel between
        // producer and collector, and the KDoc above records why `trySendBlocking` exists: with a
        // bounded buffer and a non-blocking send, 335 of 400 tokens were measured lost.
        // `UNLIMITED` keeps `trySendBlocking` from ever having to block a network callback thread,
        // and `StreamBackpressureTest` is the guard on this line.
        .flowOn(Dispatchers.IO)
        .buffer(Channel.UNLIMITED)

    companion object {
        /**
         * One client for the process, built through [FabricHttp] like every other client in this
         * repository (`B-197`).
         *
         * **What changed and what did not.** The timeouts and the retry are the ones this class
         * always had; what it gains is [ai.passioncode.fabricvr.common.NetworkPolicyInterceptor],
         * so a `307` from the provider can no longer move up to 12 KB of the person's own notes,
         * and the `Authorization` header carrying their OpenRouter key, to a public host over
         * cleartext. `DEC-0077` gave that to the three clients in `:feature-stt` and could not
         * give it to this one, because the factory was `internal` to that module.
         *
         * It was a **default argument** before, so every construction allocated an OkHttp
         * dispatcher, its thread pool and a connection pool and discarded them with the object —
         * the same `I-28` the downloader's own `SHARED` exists for. A `val` is also what makes the
         * wiring assertable: a rule nobody can hold an instance of is a rule nobody checks.
         *
         * `RetryInterceptor` is stateless — its only field is the sleep function — so one shared
         * instance across concurrent calls is safe.
         */
        val SHARED: OkHttpClient by lazy {
            FabricHttp.builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                // A read timeout bounds one read; a provider that keeps sending keep-alive
                // comments can hold a stream open forever without ever tripping it.
                .callTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(RetryInterceptor())
                .build()
        }
    }
}

class AssistantException(val error: AppError) : Exception(error.cause)

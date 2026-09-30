package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.stt.RemoteDeadline.withDeadlineFor

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.FabricHttp
import ai.passioncode.fabricvr.common.InsecureHopException
import ai.passioncode.fabricvr.common.NetworkPolicy
import ai.passioncode.fabricvr.common.runCatchingCancellable
import ai.passioncode.fabricvr.common.toAppError
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Transcription through an **OpenAI-compatible** endpoint: `POST {base}/v1/audio/transcriptions`
 * with the audio as a multipart file.
 *
 * One client rather than one per vendor, because Groq, OpenAI and a self-hosted faster-whisper all
 * implement the same route and the same form. What differs is the base URL, the key and the model
 * name, and those are settings. Distinct from [RemoteWhisperClient], which speaks whisper.cpp's own
 * `/inference` route — that server has no OpenAI-compatible path.
 *
 * The recording leaves the device here, so the constructor refuses an endpoint the network policy
 * will not allow: cleartext is permitted only to the person's own network, and never to the open
 * internet. A microphone recording sent in the clear is the failure this class must not have.
 */
class CloudTranscriptionClient(
    baseUrl: String,
    private val apiKey: () -> String?,
    private val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = SHARED,
) : SttEngine {

    private val base: String = NetworkPolicy.requireReachable(baseUrl)
        .getOrElse { throw SttException(AppError.InsecureUrl(baseUrl)) }
        .trimEnd('/')

    override val name: String = "cloud:$model"

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, langHint: String?): Result<Transcript> =
        withContext(Dispatchers.IO) {
            val key = apiKey()?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(SttException(AppError.NoApiKey))

            runCatchingCancellable {
                val wav = WavWriter.toWav(pcm, sampleRate)
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav", wav.toRequestBody(WAV.toMediaType()))
                    .addFormDataPart("model", model)
                    .addFormDataPart("response_format", "json")
                    .addFormDataPart("temperature", "0")
                    .also { builder ->
                        // "auto" is this app's word for "you decide", not a language. Sending it
                        // would be read as a language named auto and the provider would refuse it.
                        val pinned = langHint?.takeIf { it.isNotBlank() && it != AUTO }
                        if (pinned != null) builder.addFormDataPart("language", pinned)
                    }
                    .build()

                val request = Request.Builder()
                    .url("$base/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $key")
                    .post(form)
                    .build()

                val started = System.currentTimeMillis()
                // Uploading a whole recording is worth cancelling: a sheet the person dismissed
                // should not keep a two-minute call alive, nor keep sending their voice. `await`
                // is the seam that makes that true — see `CallAwait.kt` for why the
                // `invokeOnCompletion` this replaced could not (`H9`).
                // `B-243`: the deadline grows with the recording — see [RemoteDeadline].
                client.newCall(request).withDeadlineFor(pcm.size, sampleRate).await().use { response ->
                    val source = response.body?.source()
                    if (!response.isSuccessful) {
                        val body = source?.let {
                            it.request(MAX_BODY)
                            it.buffer.snapshot().utf8().take(MAX_BODY.toInt())
                        }.orEmpty()
                        throw SttException(AppError.RemoteStt(response.code, message(body)))
                    }
                    // `B-244`: read whole, and a transcript only if `text` is a string — see
                    // [RemoteTranscriptBody].
                    val body = RemoteTranscriptBody.read(source, response.code)
                    val json = RemoteTranscriptBody.json(body)
                    val text = RemoteTranscriptBody.text(json, response.code)
                    val reported = json?.get("language")?.jsonPrimitive?.content
                    Transcript(
                        text = text.trim(),
                        language = normalise(reported) ?: langHint?.takeIf { it != AUTO }.orEmpty(),
                        source = SttSource.REMOTE,
                        engine = name,
                        durationMs = System.currentTimeMillis() - started,
                    )
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    // `InsecureHopException` is named before `toAppError`: it is an
                    // `IOException`, so the general classifier would file a redirect refused for
                    // this person's privacy under storage and tell them their note could not be
                    // saved.
                    val error = when (it) {
                        is SttException -> it.error
                        is InsecureHopException -> it.error
                        else -> it.toAppError("cloud-stt")
                    }
                    Result.failure(SttException(error))
                },
            )
        }

    /** The provider's error text, when it sent one in the shape everyone uses. */
    private fun message(body: String): String? = runCatchingCancellable {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: body.take(200).takeIf { it.isNotBlank() }

    /**
     * OpenAI answers with an English language *name* ("russian"); whisper.cpp and this app use the
     * two-letter code. A name nobody maps would be shown to the person as their language.
     */
    private fun normalise(reported: String?): String? {
        val value = reported?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length == 2) return value
        return NAMES[value] ?: value
    }

    companion object {
        /**
         * One client, not one per engine construction — `I-28`, see `ModelDownloader.SHARED`.
         *
         * Built through [FabricHttp]: this is the class where the recording **and** the
         * transcription key leave the device, so a redirect it followed unchecked was the worst
         * shape `B-187` could take.
         */
        internal val SHARED: OkHttpClient by lazy {
            // `readTimeout(0)`, for `RemoteWhisperClient.SHARED`'s reason: the answer comes after the
            // whole decode, and the per-call deadline is what bounds it (`B-243`).
            FabricHttp.builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
        }
        /** Groq's whisper-large-v3: the one most people will point this at, and the fastest. */
        const val DEFAULT_MODEL = "whisper-large-v3"

        /** Groq, because it is the cheapest OpenAI-compatible transcription endpoint today. */
        const val SUGGESTED_BASE_URL = "https://api.groq.com/openai"

        private const val AUTO = "auto"
        private const val WAV = "audio/wav"
        private const val MAX_BODY = 64 * 1024L

        private val NAMES = mapOf(
            "english" to "en", "russian" to "ru", "ukrainian" to "uk", "german" to "de",
            "french" to "fr", "spanish" to "es", "italian" to "it", "portuguese" to "pt",
            "polish" to "pl", "dutch" to "nl", "turkish" to "tr", "hebrew" to "he",
            "arabic" to "ar", "chinese" to "zh", "japanese" to "ja", "korean" to "ko",
        )
    }
}

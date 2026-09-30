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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * whisper.cpp's own server. Its only documented route is `/inference` — there is no
 * OpenAI-compatible transcription path, so this speaks the documented multipart form.
 */
class RemoteWhisperClient(
    private val baseUrl: String,
    private val client: OkHttpClient = SHARED,
) : SttEngine {

    override val name: String = "whisper-server"

    companion object {
        /**
         * One client, not one per engine construction — `I-28`, see `ModelDownloader.SHARED`.
         *
         * Built through [FabricHttp], so every hop of every call is re-checked against
         * `DEC-0005` rather than only the address in Settings (`B-187`).
         */
        internal val SHARED: OkHttpClient by lazy {
            // `readTimeout(0)`: whisper-server sends nothing until it has decoded the whole file,
            // so OkHttp's default 10 s read timeout cut every longer decode short — the per-call
            // deadline (`RemoteDeadline`) is what bounds the wait (`B-243`, seam verification).
            FabricHttp.builder().callTimeout(60, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
        }
        /** Longest body worth reading from a server that is already misbehaving. */
        private const val MAX_ERROR_BODY = 64 * 1024L

        /** whisper.cpp's own word for "detect it" — not a language, and not this app's invention. */
        private const val AUTO = "auto"

        /** `DEC-0005`, delegated to the one policy both HTTP clients obey. */
        fun validateBaseUrl(url: String): Result<String> =
            NetworkPolicy.requireReachable(url).recoverCatching {
                throw SttException(AppError.InsecureUrl(url))
            }
    }

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, langHint: String?): Result<Transcript> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val wav = WavWriter.toWav(pcm, sampleRate)
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", "audio.wav",
                        wav.toRequestBody("audio/wav".toMediaType()),
                    )
                    .addFormDataPart("temperature", "0.0")
                    .addFormDataPart("response_format", "json")
                    // `H7`. whisper-server initialises `params.language` to `"en"`
                    // (`server.cpp:113`) and only overwrites it from this field (`:558-560`), so
                    // sending no field is not "let it decide" — it is a decision to decode
                    // English. A Russian dictation came back as English phonetics while the
                    // transcript was labelled with the hint, which is the worst of both.
                    // `"auto"` is the value `whisper.h` documents for detection and the one the
                    // JNI bridge already passes locally; the opposite of [CloudTranscriptionClient],
                    // which must OMIT the field because an OpenAI-compatible provider would read
                    // `auto` as a language named auto.
                    .addFormDataPart("language", langHint?.trim()?.takeIf { it.isNotEmpty() } ?: AUTO)
                    .build()
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/inference")
                    .post(form)
                    .build()
                val started = System.currentTimeMillis()
                // The upload of a whole recording is worth cancelling: a released button that the
                // person then dismissed should not keep a 60-second call alive. `await` is the
                // seam that makes that true — see `CallAwait.kt` for what the `invokeOnCompletion`
                // this replaced actually did (`H9`).
                // `B-243`: the deadline grows with the recording — see [RemoteDeadline].
                client.newCall(request).withDeadlineFor(pcm.size, sampleRate).await().use { response ->
                    val source = response.body?.source()
                    if (!response.isSuccessful) {
                        val body = source?.let {
                            it.request(MAX_ERROR_BODY)
                            it.buffer.snapshot().utf8().take(MAX_ERROR_BODY.toInt())
                        }.orEmpty()
                        throw SttException(AppError.RemoteStt(response.code, body.take(200)))
                    }
                    // `B-244`: read whole, and a transcript only if `text` is a string — see
                    // [RemoteTranscriptBody] for the two ways the raw body used to become a note.
                    val body = RemoteTranscriptBody.read(source, response.code)
                    val text = RemoteTranscriptBody.text(RemoteTranscriptBody.json(body), response.code)
                    Transcript(
                        text = text.trim(),
                        language = langHint.orEmpty(),
                        source = SttSource.REMOTE,
                        engine = name,
                        durationMs = System.currentTimeMillis() - started,
                    )
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    // A timeout or a DNS failure is not "the server answered 0" — it is a network
                    // error, and the difference is the whole content of the message.
                    //
                    // `InsecureHopException` is named BEFORE `toAppError` because it is an
                    // `IOException` and would otherwise land in that function's storage branch —
                    // a redirect refused for the person's privacy would read as *"Couldn't save.
                    // Your text is still here."* (`M12`, from the other side).
                    val error = when (it) {
                        is SttException -> it.error
                        is InsecureHopException -> it.error
                        else -> it.toAppError("remote-stt")
                    }
                    Result.failure(SttException(error))
                },
            )
        }
}

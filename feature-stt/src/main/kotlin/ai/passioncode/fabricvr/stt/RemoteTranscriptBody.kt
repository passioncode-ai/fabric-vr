package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.runCatchingCancellable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.BufferedSource

/**
 * How a successful answer from a remote speech service becomes words — the one rule both
 * [RemoteWhisperClient] and [CloudTranscriptionClient] obey (`B-244`, `DEC-0091`).
 *
 * **Two defects, one shape.** Both clients read `text` and fell back to the raw body with
 * `?: body`, so a captive portal's HTML, a proxy's page or a JSON object without `text` was saved
 * as what the person said. And the success body was read under the 64 KiB ceiling meant for error
 * bodies, so a long transcript arrived cut, failed to parse, and the same fallback wrote half a
 * JSON document into the note. A person cannot tell a note full of HTML from a bug in their own
 * words, and the recording that could have been re-transcribed looked like it already had been.
 *
 * So: the body is read whole up to [MAX_TRANSCRIPT_BODY], and it is a transcript only if it is a
 * JSON object whose `text` is a string. Anything else is [AppError.RemoteSttUnreadable], which the
 * router treats like every other remote failure — it falls back to the headset and says why.
 */
internal object RemoteTranscriptBody {

    /**
     * Ten minutes of speech (`VoiceViewModel.MAX_SECONDS`) is a few thousand words — tens of
     * kilobytes of UTF-8 even as JSON with timestamps. A body past this is not a transcript this app
     * asked for, and reading it whole would be the unbounded read `MAX_ERROR_BODY` exists to avoid.
     */
    const val MAX_TRANSCRIPT_BODY = 4L * 1024 * 1024

    /** The body of a 2xx response, whole, or [AppError.RemoteSttUnreadable] if it will not fit. */
    fun read(source: BufferedSource?, status: Int): String {
        if (source == null) return ""
        // `request` answers whether that many bytes are buffered; one past the bound tells a body
        // that ends exactly at it from one that goes on.
        if (source.request(MAX_TRANSCRIPT_BODY + 1)) throw SttException(AppError.RemoteSttUnreadable(status))
        return source.readUtf8()
    }

    /** The parsed JSON object, or null when [body] is not one. */
    fun json(body: String): JsonObject? =
        runCatchingCancellable { Json.parseToJsonElement(body).jsonObject }.getOrNull()

    /** `text` as a string, or [AppError.RemoteSttUnreadable] — never the raw body. */
    fun text(json: JsonObject?, status: Int): String {
        val field = json?.get("text") as? JsonPrimitive
        if (field == null || !field.isString) throw SttException(AppError.RemoteSttUnreadable(status))
        return field.content
    }
}

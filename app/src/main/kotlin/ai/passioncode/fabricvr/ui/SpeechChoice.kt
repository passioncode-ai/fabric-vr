package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.UiMessage
import ai.passioncode.fabricvr.common.UiStateMapper
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import androidx.annotation.StringRes

/**
 * The names the person sees for a speech provider and a speech model.
 *
 * They live in the app rather than in `:feature-stt` because that module must not carry copy or a
 * language — the enums there hold a stable key, this maps the key to a resource. A `when` over the
 * enum rather than a map, so adding a model is a compile error here until it has a name.
 */
@get:StringRes
val WhisperModel.labelRes: Int
    get() = when (this) {
        WhisperModel.TINY -> R.string.model_tiny
        WhisperModel.BASE -> R.string.model_base
        WhisperModel.SMALL -> R.string.model_small
        WhisperModel.MEDIUM -> R.string.model_medium
        WhisperModel.LARGE_TURBO -> R.string.model_large_turbo
    }

/** The one-word name, for a status line rather than a chooser. See `model_short_*`. */
val WhisperModel.shortLabelRes: Int
    get() = when (this) {
        WhisperModel.TINY -> R.string.model_short_tiny
        WhisperModel.BASE -> R.string.model_short_base
        WhisperModel.SMALL -> R.string.model_short_small
        WhisperModel.MEDIUM -> R.string.model_short_medium
        WhisperModel.LARGE_TURBO -> R.string.model_short_large_turbo
    }

@get:StringRes
val SttProvider.labelRes: Int
    get() = when (this) {
        SttProvider.LOCAL -> R.string.provider_local
        SttProvider.CLOUD -> R.string.provider_cloud
        SttProvider.SERVER -> R.string.provider_server
    }

/** What sending speech there actually means, said where the choice is made rather than in a policy. */
@get:StringRes
val SttProvider.noteRes: Int
    get() = when (this) {
        SttProvider.LOCAL -> R.string.provider_local_note
        SttProvider.CLOUD -> R.string.provider_cloud_note
        SttProvider.SERVER -> R.string.provider_server_note
    }

/**
 * Where a transcript came from, in words (`REQ-061`, audit `M27`/`B-151`).
 *
 * The editor pushed `transcript.source.name.lowercase()` into its badge, so a person read the
 * literal `local_fallback`, while three human strings sat in `strings.xml` — two of them kept
 * alive only by a `tools:ignore`. Here for `labelRes`'s reason: the enum is a domain type and
 * the words belong to the app.
 *
 * `REMOTE` deliberately does not name the whisper server: it covers a cloud endpoint too, and
 * naming one of the two was half of `M27`'s four-names finding.
 */
@StringRes
internal fun transcriptSourceRes(source: SttSource): Int = when (source) {
    SttSource.LOCAL -> R.string.stt_source_local
    SttSource.REMOTE -> R.string.stt_source_remote
    SttSource.LOCAL_FALLBACK -> R.string.stt_source_local_fallback
}

/**
 * Why a transcript says *"transcribed on this headset instead"*, as a sentence — or null when
 * there is nothing to explain (`B-151`).
 *
 * `Note.kt` calls [Transcript.fallbackReason] "the receipt's most important field" and `REQ-061`
 * left it rendered nowhere: the badge named the FACT of the fallback and withheld the only part a
 * person can act on. They configured a speech service; it did not answer; the address and the key
 * are in Settings and are the two things they can change.
 *
 * **Null in two cases, and the second one is not an oversight.** A transcript that did not fall
 * back has nothing to explain, and a line saying so on every note is how a person learns to stop
 * reading the receipt. A fallback whose reason did not survive is the other: [DictationOutbox]
 * cannot serialise an `AppError` (`DEC-0068`), so a dictation that crossed a process boundary
 * arrives with `source = LOCAL_FALLBACK` and `fallbackReason = null`. Saying nothing beats a
 * sentence with a hole where the reason should be.
 *
 * **It returns the mapper's own message rather than wrapping it here.** [UiStateMapper] is the one
 * place an `AppError` becomes words, and half of those sentences carry arguments — the status a
 * server answered with, the host that did not reply. Flattening one to its `textRes` to nest it
 * inside another string would print `%1$s` at a person. The caller wraps the resolved sentence in
 * `editor_transcript_fallback`; the action the message carries is deliberately dropped, because
 * this is a receipt on a finished note and not a failure to act on now.
 */
internal fun fallbackNotice(transcript: Transcript): UiMessage? {
    if (transcript.source != SttSource.LOCAL_FALLBACK) return null
    val reason = transcript.fallbackReason ?: return null
    return UiStateMapper.map(reason)
}

/** One line in the "transcribe again" menu: a provider, and for the local one, which model. */
data class SpeechChoice(val provider: SttProvider, val model: WhisperModel) {
    companion object {
        /** Every way the person may re-run a recording, in the order the menu shows them. */
        fun all(): List<SpeechChoice> =
            WhisperModel.entries.map { SpeechChoice(SttProvider.LOCAL, it) } +
                SpeechChoice(SttProvider.CLOUD, WhisperModel.DEFAULT) +
                SpeechChoice(SttProvider.SERVER, WhisperModel.DEFAULT)
    }
}

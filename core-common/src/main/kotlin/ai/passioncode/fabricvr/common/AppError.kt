package ai.passioncode.fabricvr.common

/**
 * One taxonomy for everything that can fail. Every public suspend function in this app returns
 * [Result] whose failure carries an [AppError]; the UI never catches a raw exception.
 */
sealed class AppError(open val cause: Throwable? = null) {

    /** A runtime permission was refused. [permanent] means the system will no longer prompt. */
    data class Permission(val what: String, val permanent: Boolean = false) : AppError()

    /**
     * The speech model is not on the device yet.
     *
     * [bytes] is what it will cost to fix that, and it is not decoration: the first run's whole
     * complaint (`D-05`) is that a person meets this message, presses *Download*, and only then —
     * on the progress bar — learns they have committed to 190 MB. The banner is the first place
     * in the session where the cost is knowable, and until `T-031` it was the last.
     *
     * Zero means unknown, and the message falls back to the sentence without a size rather than
     * printing "0 MB".
     *
     * @param model the stable key — `small`, or a file name, or `none configured`. It is for the
     *   log and for a test; **it never reaches a person** (`REQ-061`, audit `M27`/`B-157`): the
     *   banner read *"small, 190 MB"*, which is a key and a number, not a name.
     * @param nameRes the name the person reads, as a resource id belonging to whichever module
     *   owns the enum — `:app` maps `WhisperModel` to `model_short_*` and `:core-common` cannot
     *   see that mapping. Zero means the caller had no name to give, and the message then falls
     *   back to the sentence without one rather than printing the key.
     */
    data class ModelMissing(
        val model: String,
        val bytes: Long = 0,
        @androidx.annotation.StringRes val nameRes: Int = 0,
    ) : AppError()

    /**
     * @param needBytes and [freeBytes] are set only for [Reason.DISK], and only when the refusal
     *   happened before the transfer — the free-space precondition (`G-07`). They exist because
     *   *"there isn't room"* without two numbers is a sentence the person cannot act on or
     *   disprove: it does not say how much to free.
     */
    data class ModelDownload(
        val reason: Reason,
        override val cause: Throwable? = null,
        val needBytes: Long? = null,
        val freeBytes: Long? = null,
    ) : AppError(cause) {
        enum class Reason { NETWORK, CHECKSUM, DISK, CANCELLED }
    }

    /** The model is being transferred right now, so it cannot be deleted under the writer. */
    data object ModelBusy : AppError()

    /**
     * The network did not answer.
     *
     * [host] is what did not answer, and it is not decoration: the person typed that address into
     * Settings, so it is the only part of the sentence they can check or correct. It is null when
     * the caller genuinely does not know one — a stream that ended early has no host to name — and
     * the message then falls back to the sentence without it rather than printing an empty gap.
     */
    data class Network(override val cause: Throwable?, val host: String? = null) : AppError(cause)

    /** A remote whisper-server answered with a non-2xx status. */
    data class RemoteStt(val status: Int, val body: String? = null) : AppError()

    /**
     * A remote speech service answered successfully with something that is not a transcript — a
     * captive portal's page, a proxy's, a JSON object with no `text` (`B-244`, `DEC-0091`).
     * Distinct from [RemoteStt] because *"refused the recording (200)"* would be false.
     */
    data class RemoteSttUnreadable(val status: Int) : AppError()

    /**
     * An export stopped because one file could not be read once its bytes were flowing
     * (`DEC-0093`), named so the person can act (`B-258`). [recording] decides what they can do:
     * a recording can be left out by exporting notes only; a note cannot be left out of a backup.
     */
    data class ExportUnreadable(val file: String, val recording: Boolean, override val cause: Throwable?) : AppError(cause)

    /** A URL the app refuses to send anything to in the clear (`DEC-0005`). */
    data class InsecureUrl(val url: String) : AppError()

    /**
     * A redirect that would have moved a request **carrying something** to another host (`B-216`).
     *
     * A separate shape from [InsecureUrl] because the two refusals answer different questions and
     * a person can only act on the one they are shown. [InsecureUrl]'s sentence is about
     * cleartext — *"Only https, or http to a device on your own network"* — and printing it for an
     * `https` → `https` hop would be a lie about why nothing was sent.
     *
     * [host] is where the server tried to send it. It is the only part of the event anybody can
     * check, and it is scheme and host only: a refused `Location` can carry a query string, and a
     * query string is where a token ends up.
     */
    data class RedirectRefused(val host: String) : AppError()

    data class SttFailed(val engine: String, override val cause: Throwable? = null) : AppError(cause)

    data class OpenRouter(val status: Int, val code: String? = null, val message: String? = null) : AppError()

    data object NoApiKey : AppError()

    /**
     * A speech provider the person chose, with nothing to reach it by.
     *
     * Distinct from [NoApiKey], which is the assistant's: telling somebody whose *transcription*
     * key is missing to add an OpenRouter key sends them to the wrong screen. The discriminant is
     * here rather than in two separate shapes because it is one concept — "chosen but not
     * configured" — and two shapes would mean two mapper branches and two tests for it.
     */
    data class SttNotConfigured(val provider: String, val missing: Missing) : AppError() {
        enum class Missing { ADDRESS, KEY }
    }

    data class Storage(val op: String, override val cause: Throwable? = null) : AppError(cause)

    data class Unknown(override val cause: Throwable?) : AppError(cause)
}

/**
 * Turn any throwable into an [AppError] without losing it.
 *
 * **Everything below the network branch is storage, so the network branch has to be complete**
 * (`M12`). It named three shapes, and `ConnectException`, `SSLException`,
 * `NoRouteToHostException` and `SocketException` are all `IOException`s — so a whisper-server that
 * refused the connection, or one whose certificate did not verify, became `Storage` and reached
 * the person as *"Couldn't save. Your text is still here."*: a sentence about their note, for a
 * failure their note was never in.
 *
 * `ConnectException` and `NoRouteToHostException` extend `SocketException` and would be matched by
 * it alone. They are named anyway, because the audit row names them and the next reader looking
 * for one of those two words should find it here rather than have to know the hierarchy.
 *
 * @param host what did not answer, when the caller knows. A caller that does not pass one still
 *   gets the right shape; it gets the message without the address.
 */
fun Throwable.toAppError(op: String = "unknown", host: String? = null): AppError = when (this) {
    is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.io.InterruptedIOException,
    is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.SocketException,
    is javax.net.ssl.SSLException ->
        AppError.Network(this, host)
    is java.io.IOException -> AppError.Storage(op, this)
    else -> AppError.Unknown(this)
}

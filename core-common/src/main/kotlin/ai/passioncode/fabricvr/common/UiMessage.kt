package ai.passioncode.fabricvr.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * What the user may do about a failure. The UI renders one button per action.
 *
 * [WRITE_NOTE] is not a repair, and that is the point: some failures cannot be repaired here and
 * now — the microphone was refused, the model is 190 MB away — and a message whose only control
 * is one the person has just declined is a dead end. It leads to the text editor, which `T-028`
 * put back, so offering it is honest for the first time since `DEC-0010`.
 *
 * **There is no single `RETRY` any more** (`REQ-047`, audit `H2`/`H3`). One member meant every
 * screen answered it with whatever *its* retry happened to be, and on Today that was
 * `NotesViewModel.retry()` — reload the list. So *Retry* under **"Couldn't save your dictation"**
 * reloaded the list and left the transcript unwritten (the next dictation then overwrote it and
 * its recording was orphaned), and *Retry* under **"The space could not start"** reloaded the
 * list and never tried the Space. `NotesViewModel.retryDictation()` existed and had **zero
 * callers**. Three members, and every `when` over this enum is exhaustive, so the compiler names
 * each site that has to decide which one it means.
 *
 * [RETRY_LOAD] is the generic one and the mapper's default: *try what this surface was doing
 * again*. The other two are attached deliberately by the view model that knows better, because
 * only it can tell a failed commit from a failed read.
 */
enum class UiAction {
    /** Re-read what this screen shows — the list, the note, the search, the settings. */
    RETRY_LOAD,

    /** Write the dictation whose row would not save. The audio has already moved; this is one write. */
    RETRY_DICTATION,

    /** Start the immersive Space again. Reloading the notes answers a different question. */
    RETRY_SPACE,

    OPEN_SETTINGS,
    GRANT_PERMISSION,
    OPEN_APP_SETTINGS,
    DOWNLOAD_MODEL,

    /**
     * Carry on with the bytes already on disk (`REQ-052`'s neighbour, audit `H8`).
     *
     * Separate from [DOWNLOAD_MODEL] because the two cost different amounts and the person is
     * entitled to know which they are agreeing to: a resumable failure asks for the missing
     * megabytes, a non-resumable one for all 574 of them. `DownloadProgress.Failed.resumable` is
     * what decides, and only the collector of that flow knows it — so the view model swaps the
     * action in, rather than the mapper guessing from the error.
     */
    RESUME_DOWNLOAD,

    /** The partial bytes are gone or unusable, so this starts over. See [RESUME_DOWNLOAD]. */
    DOWNLOAD_AGAIN,

    WRITE_NOTE,

    /** Export the vault without recordings — the way past one that cannot be read (`B-258`). */
    EXPORT_NOTES_ONLY,
}

/**
 * A failure as the person will read it: a string **id** plus the values that fill it.
 *
 * An id rather than a string so the words live in `strings.xml`, where a translation or a copy pass
 * changes them, and so a test can assert which message was chosen without freezing its wording.
 */
data class UiMessage(
    @StringRes val textRes: Int,
    val args: List<Any> = emptyList(),
    val action: UiAction? = null,
    /**
     * A second thing the person may do, rendered beside [action] and never instead of it.
     *
     * Two rather than a list because two is what the product needs and a list invites a banner
     * with four buttons in it, which in a headset is a row of targets a ray cannot separate.
     */
    val secondary: UiAction? = null,
) {
    /**
     * An argument that is **itself** a sentence, resolved where the message is rendered.
     *
     * `REQ-061`, audit `M27`. The arguments of a message are the values a failure carried, and
     * three of them were internal identifiers: a provider key reached the person as *"No key
     * saved for cloud"*, a model key as *"small, 190 MB"*, and a throwable's class as
     * *"Something failed: NullPointerException"*. The mapper cannot fix that by substituting a
     * nicer string, because the nice name lives in whichever module owns the enum — `:app` names
     * the providers and the models, and `:core-common` cannot see either.
     *
     * A resource **id** crosses that boundary where a string cannot: it is an `Int`, so `:app`
     * hands over `R.string.model_short_small` and this file resolves it against the one context
     * that can see every module's resources. `NoRawIdentifiersTest` then has a decidable rule to
     * assert — no argument is a raw `String` — which is what makes the next identifier added to
     * `AppError` fail a test rather than appear in a banner.
     */
    @JvmInline
    value class Res(@StringRes val id: Int)

    /** For a composable. */
    @Composable
    fun text(): String = stringResource(textRes, *resolved(LocalContext.current))

    /** For anything that is not — a notification, a log line, a test. */
    fun text(context: Context): String = context.getString(textRes, *resolved(context))

    private fun resolved(context: Context): Array<Any> =
        args.map { arg -> if (arg is Res) context.getString(arg.id) else arg }.toTypedArray()
}

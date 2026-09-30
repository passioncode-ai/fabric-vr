package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `B-151`, the half `REQ-061` left open.
 *
 * The badge no longer renders `local_fallback` — that was closed at `ad8bf66`, and
 * [transcriptSourceRes] is what closed it. What stayed open is a **surface**, not a string:
 * `Note.kt` calls [Transcript.fallbackReason] "the receipt's most important field" and it is drawn
 * nowhere at all, so *"transcribed on this headset instead"* names the fact and withholds the only
 * part a person can act on — that the speech service they configured did not answer, and why.
 *
 * A pure function rather than an assertion about the editor's tree: the reason is an [AppError],
 * the one mapper owns how an [AppError] becomes words, and what this row needs decided is *which
 * transcripts get a second line*. A composable test would answer that question through three
 * layers of layout.
 */
class TranscriptReceiptTest {

    private fun transcript(source: SttSource, reason: AppError? = null) =
        Transcript("слова", "ru", source, "whisper-small-q5_1", 900, fallbackReason = reason)

    @Test fun `a fallback carries the reason it fell back`() {
        val message = fallbackNotice(transcript(SttSource.LOCAL_FALLBACK, AppError.RemoteStt(503)))

        assertNotNull("the receipt's most important field is still rendered nowhere", message)
        assertEquals(
            "the reason did not come from the one mapper that turns an AppError into words",
            ai.passioncode.fabricvr.common.R.string.error_remote_stt,
            message!!.textRes,
        )
        assertEquals(
            "the status the server answered with was flattened away — the sentence would print %1\$s",
            listOf<Any>(503),
            message.args,
        )
    }

    /**
     * A transcript that did not fall back has nothing to explain, and a line saying so on every
     * note is how a person learns to stop reading the receipt.
     */
    @Test fun `an ordinary local transcript explains nothing`() {
        assertNull(fallbackNotice(transcript(SttSource.LOCAL)))
        assertNull(fallbackNotice(transcript(SttSource.REMOTE)))
    }

    /**
     * `DictationOutbox` cannot serialise an `AppError` (`DEC-0068`), so a dictation that crossed a
     * process boundary arrives as a fallback with no reason. That is a real state and it must not
     * produce a receipt line with an empty hole in it.
     */
    @Test fun `a fallback whose reason did not survive says nothing rather than nothing-shaped`() {
        assertNull(
            "a fallback with no reason produced a sentence with a gap in it",
            fallbackNotice(transcript(SttSource.LOCAL_FALLBACK, reason = null)),
        )
    }
}

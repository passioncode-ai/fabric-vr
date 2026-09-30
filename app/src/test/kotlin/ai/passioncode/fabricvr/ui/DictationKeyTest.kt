package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.common.UiMessage
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.WhisperModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the Today screen's commit effect is keyed on.
 *
 * `LaunchedEffect(voice)` keyed on the whole state object, and `VoiceState.Recording` carries
 * `level` and `samples` — both of which change on every audio frame — so the effect that commits
 * a dictation was cancelled and relaunched about fifty times a second for the length of every
 * recording. Nothing visible broke, because its first line returns on the `as?`; what it did was
 * make a cancellation between the clipboard write and the commit reachable at all.
 *
 * These are the two properties the key must have, and neither needs a device.
 */
class DictationKeyTest {

    private fun transcript(text: String) =
        Transcript(text, "ru", SttSource.LOCAL, "whisper-base", 900)

    @Test fun `every recording frame produces the same key`() {
        val keys = (0..50).map { frame ->
            dictationKey(VoiceState.Recording(level = frame / 50f, samples = frame * 320))
        }.distinct()
        assertEquals(listOf(null), keys)
    }

    @Test fun `no state but a finished dictation has a key`() {
        assertNull(dictationKey(VoiceState.Idle))
        assertNull(dictationKey(VoiceState.NeedsPermission))
        assertNull(dictationKey(VoiceState.Transcribing))
        assertNull(dictationKey(VoiceState.NothingHeard))
        assertNull(dictationKey(VoiceState.PreparingEngine(WhisperModel.DEFAULT)))
        assertNull(dictationKey(VoiceState.Downloading(1, 2)))
        assertNull(dictationKey(VoiceState.Failed(UiMessage(0))))
    }

    /**
     * Two emissions of one finished dictation are one dictation. A key that differed between them
     * would re-enter the effect, and the person's words would be handled twice — the note is
     * saved once regardless since `T-047`'s idempotence guard, but the clipboard would be written
     * again and the confirmation would flash a second time.
     */
    @Test fun `one dictation has one key however often it is emitted`() {
        val ready = VoiceState.Ready(transcript("привет"), "/data/rec-1.wav")
        assertEquals(dictationKey(ready), dictationKey(ready.copy()))
    }

    @Test fun `two dictations do not share a key`() {
        val first = VoiceState.Ready(transcript("первая"), "/data/rec-1.wav")
        val second = VoiceState.Ready(transcript("вторая"), "/data/rec-2.wav")
        assertNotEquals(dictationKey(first), dictationKey(second))
    }

    /** A dictation whose recording was not kept still has an identity — its words. */
    @Test fun `a dictation with no recording is keyed by its text`() {
        val first = VoiceState.Ready(transcript("первая"), null)
        val second = VoiceState.Ready(transcript("вторая"), null)
        assertNotEquals(dictationKey(first), dictationKey(second))
        assertEquals(dictationKey(first), dictationKey(VoiceState.Ready(transcript("первая"), null)))
    }
}

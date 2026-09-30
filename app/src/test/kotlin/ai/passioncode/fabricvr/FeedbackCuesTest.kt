package ai.passioncode.fabricvr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `REQ-062`, audit `M22`.
 *
 * **Nothing in this app made a sound or moved a controller.** Record start, record stop, the
 * ten-minute cap and "saved and copied" were visual only, on a panel the person is usually not
 * looking at — they are looking at the streamed desktop the panel sits beside. So the one product
 * this app exists for, dictation, gave no evidence it had started, and the cap gave none that it
 * had fired.
 *
 * **What the platform actually offers, fetched from Meta on 2026-09-21.** *Inputs and controllers*
 * documents exactly one Kotlin haptics call — `spatial.applyHapticFeedback(hand, amplitude,
 * durationNs, frequency)` on an `AppSystemActivity` — so haptics exist **in the Space and
 * nowhere else**; whether `android.os.Vibrator` reaches a Touch controller from a 2D panel is
 * UNVERIFIED and this code does not pretend otherwise. *Haptics: Best practices* gives the two
 * rules this seam is shaped by: "Do not just play haptic feedback if there is no corresponding
 * visual or audio cue to relate it to", and "Make haptic feedback optional and adjustable".
 *
 * Hence: an audio cue **everywhere**, a haptic pulse only where one is attached, one switch over
 * both, and every cue accompanied by the line the screen already draws.
 *
 * These tests are about the seam — which cue fired, in what order, down which channel. Whether a
 * `ToneGenerator` is audible in a headset is a device question and is `B-183`'s.
 */
class FeedbackCuesTest {

    /** Records what it was asked to play, so "in what order" is an assertion and not a claim. */
    private class RecordingChannel : CueChannel {
        val heard = mutableListOf<Cue>()
        override fun emit(cue: Cue) { heard += cue }
    }

    @Test fun `every cue reaches the audio channel`() {
        val audio = RecordingChannel()
        val cues = PlatformFeedbackCues(enabled = { true }, audio = audio)

        cues.play(Cue.RECORD_START)
        cues.play(Cue.RECORD_STOP)
        cues.play(Cue.AUTO_STOP)
        cues.play(Cue.SAVED)

        assertEquals(
            listOf(Cue.RECORD_START, Cue.RECORD_STOP, Cue.AUTO_STOP, Cue.SAVED),
            audio.heard,
        )
    }

    /**
     * The panel has no haptics API at all, so the seam must be a no-op there rather than a
     * `Vibrator` call nobody has watched reach a controller.
     */
    @Test fun `a haptic pulse happens only where the platform provides one`() {
        val audio = RecordingChannel()
        val haptics = RecordingChannel()
        val cues = PlatformFeedbackCues(enabled = { true }, audio = audio)

        cues.play(Cue.RECORD_START)
        cues.attachHaptics(haptics)
        cues.play(Cue.RECORD_STOP)
        cues.attachHaptics(null)
        cues.play(Cue.SAVED)

        assertEquals("the panel must not claim a haptic", listOf(Cue.RECORD_STOP), haptics.heard)
        assertEquals(
            "audio is the channel that exists everywhere",
            listOf(Cue.RECORD_START, Cue.RECORD_STOP, Cue.SAVED),
            audio.heard,
        )
    }

    /** Meta: "Make haptic feedback optional and adjustable." One switch, over both channels. */
    @Test fun `the switch silences both channels`() {
        val audio = RecordingChannel()
        val haptics = RecordingChannel()
        var on = true
        val cues = PlatformFeedbackCues(enabled = { on }, audio = audio)
        cues.attachHaptics(haptics)

        cues.play(Cue.RECORD_START)
        on = false
        cues.play(Cue.RECORD_STOP)
        on = true
        cues.play(Cue.SAVED)

        assertEquals(listOf(Cue.RECORD_START, Cue.SAVED), audio.heard)
        assertEquals(listOf(Cue.RECORD_START, Cue.SAVED), haptics.heard)
    }

    /** The default the rest of the app falls back to when nothing has been wired. */
    @Test fun `the silent seam plays nothing and does not throw`() {
        FeedbackCues.None.attachHaptics(RecordingChannel())
        FeedbackCues.None.attachAudioFocus(RecordingFocus())
        FeedbackCues.None.attachPlayback(RecordingPlayback())
        Cue.entries.forEach { FeedbackCues.None.play(it) }
        FeedbackCues.None.captureEnded()
        FeedbackCues.None.releaseAudio()
    }

    // -----------------------------------------------------------------------------------------
    // `B-226`. Three failures in one area, and the tests below are one per failure.
    // -----------------------------------------------------------------------------------------

    /** Counts the requests and the abandons, so "idempotent" is measured rather than asserted. */
    private class RecordingFocus(private val grant: Boolean = true) : AudioFocus {
        var requests = 0
        var abandons = 0
        var onLoss: () -> Unit = {}

        override fun requestTransientDuck(onLoss: () -> Unit): Boolean {
            requests++
            this.onLoss = onLoss
            return grant
        }

        override fun abandon() { abandons++ }
    }

    private class RecordingPlayback : Playback {
        var stops = 0
        override fun stop() { stops++ }
    }

    /** A channel whose native allocation can be asked whether it was given back. */
    private class ClosableChannel : CueChannel, AutoCloseable {
        val heard = mutableListOf<Cue>()
        var closes = 0
        override fun emit(cue: Cue) { heard += cue }
        override fun close() { closes++ }
    }

    /**
     * **Meta, verbatim:** *"Entering an immersive experience can cause background audio from other
     * apps to stop… avoid requesting `AUDIOFOCUS_GAIN` … use `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`
     * … handle `AudioManager.OnAudioFocusChangeListener`"* — and `grep requestAudioFocus` over
     * this tree returned nothing at all, on a product whose whole position is being the notebook
     * beside a streamed desktop.
     *
     * The focus is taken when the microphone opens and given back when it closes, not held for
     * the app's lifetime: `TRANSIENT` means transient.
     */
    @Test fun `the microphone takes audio focus and gives it back`() {
        val focus = RecordingFocus()
        val cues = PlatformFeedbackCues(enabled = { true }, audio = RecordingChannel())
        cues.attachAudioFocus(focus)

        cues.play(Cue.RECORD_START)
        assertEquals("nothing asked the mixer to make room for the microphone", 1, focus.requests)
        assertEquals("the focus was given back before the recording ended", 0, focus.abandons)

        cues.play(Cue.RECORD_STOP)
        assertEquals("the focus was held after the microphone closed", 1, focus.abandons)
    }

    /** The cap is the app stopping the person, and it closes the microphone exactly as a press does. */
    @Test fun `the ten-minute cap gives the focus back too`() {
        val focus = RecordingFocus()
        val cues = PlatformFeedbackCues(enabled = { true }, audio = RecordingChannel())
        cues.attachAudioFocus(focus)

        cues.play(Cue.RECORD_START)
        cues.play(Cue.AUTO_STOP)

        assertEquals(1, focus.abandons)
    }

    /**
     * The door for a path that plays no cue at all.
     *
     * `VoiceViewModel.cancel()` discards the recording without a cue — there is nothing to
     * acknowledge — and the focus still has to go back, or the person's music stays ducked until
     * the next dictation.
     */
    @Test fun `a cancelled gesture gives the focus back although it plays no cue`() {
        val focus = RecordingFocus()
        val audio = RecordingChannel()
        val cues = PlatformFeedbackCues(enabled = { true }, audio = audio)
        cues.attachAudioFocus(focus)

        cues.play(Cue.RECORD_START)
        cues.captureEnded()

        assertEquals("a cancelled dictation left the mixer ducked", 1, focus.abandons)
        assertEquals("a cancel is not a cue and must make no sound", listOf(Cue.RECORD_START), audio.heard)
    }

    /** Both doors lead to the same place, so a stop after a cancel does not abandon twice. */
    @Test fun `the focus is abandoned once however many doors ask`() {
        val focus = RecordingFocus()
        val cues = PlatformFeedbackCues(enabled = { true }, audio = RecordingChannel())
        cues.attachAudioFocus(focus)

        cues.play(Cue.RECORD_START)
        cues.captureEnded()
        cues.play(Cue.RECORD_STOP)
        cues.captureEnded()

        assertEquals(1, focus.abandons)
    }

    /**
     * **The person's switch is about hearing a blip, not about the device's audio** (`REQ-062`
     * beside `B-226`).
     *
     * Reading [PlatformFeedbackCues]'s `enabled` before the session work would have made *turn
     * cues off* silently mean *stop ducking other apps and stop silencing my own playback* —
     * a setting nobody offered and nobody could find.
     */
    @Test fun `turning cues off does not turn the audio session off`() {
        val focus = RecordingFocus()
        val playback = RecordingPlayback()
        val audio = RecordingChannel()
        val cues = PlatformFeedbackCues(enabled = { false }, audio = audio)
        cues.attachAudioFocus(focus)
        cues.attachPlayback(playback)

        cues.play(Cue.RECORD_START)
        cues.play(Cue.RECORD_STOP)

        assertEquals("the switch silenced the cue, which is its job", emptyList<Cue>(), audio.heard)
        assertEquals("the switch also stopped ducking other apps", 1, focus.requests)
        assertEquals("the switch also stopped silencing our own speaker", 1, playback.stops)
        assertEquals(1, focus.abandons)
    }

    /**
     * **The speaker feeds the microphone**, and a headset's are centimetres apart.
     *
     * A person who taps *play* on one note and then holds the trigger for another was dictating
     * over their own recorded voice, which whisper transcribes as cheerfully as the live one
     * because it is the same voice.
     */
    @Test fun `a recording silences whatever this app is playing`() {
        val playback = RecordingPlayback()
        val cues = PlatformFeedbackCues(enabled = { true }, audio = RecordingChannel())
        cues.attachPlayback(playback)

        cues.play(Cue.RECORD_START)

        assertEquals("the recording was made over this app's own output", 1, playback.stops)
    }

    /**
     * And it stops it when something else takes the output for good — Meta's *"handle
     * `OnAudioFocusChangeListener`"*, which is a requirement about behaviour rather than about
     * registering a callback.
     */
    @Test fun `losing the output permanently stops this app's playback`() {
        val focus = RecordingFocus()
        val playback = RecordingPlayback()
        val cues = PlatformFeedbackCues(enabled = { true }, audio = RecordingChannel())
        cues.attachAudioFocus(focus)
        cues.attachPlayback(playback)

        cues.play(Cue.RECORD_START)
        assertEquals(1, playback.stops)

        focus.onLoss()

        assertEquals("this app kept playing over whatever took the output", 2, playback.stops)
    }

    /** Identity, for [PlatformFeedbackCues.detachHaptics]'s reason: two surfaces, either order. */
    @Test fun `detaching a player detaches only that player`() {
        val gone = RecordingPlayback()
        val current = RecordingPlayback()
        val cues = PlatformFeedbackCues(enabled = { true }, audio = RecordingChannel())

        cues.attachPlayback(current)
        cues.detachPlayback(gone)
        cues.play(Cue.RECORD_START)

        assertEquals("a surface on its way out silenced the one that had just arrived", 1, current.stops)
        assertEquals(0, gone.stops)
    }

    /**
     * **`ToneGenerator` was allocated on the cue path and never released** — an `AudioTrack`
     * outside the Java heap, held from the first cue until the process died, by an app whose own
     * argument for `STREAM_NOTIFICATION` is that it should stay out of the way of whatever else
     * is playing.
     *
     * Re-openable on purpose: the channel works after a release, which is what lets either host
     * call this from `onDestroy` without the two having to agree about which is last.
     */
    @Test fun `the audio channel's native allocation is released and the channel still works`() {
        val audio = ClosableChannel()
        val cues = PlatformFeedbackCues(enabled = { true }, audio = audio)

        cues.play(Cue.RECORD_START)
        cues.releaseAudio()
        cues.play(Cue.SAVED)

        assertEquals("nothing ever gave the native allocation back", 1, audio.closes)
        assertEquals(
            "releasing it on one surface's teardown silenced the other's cues",
            listOf(Cue.RECORD_START, Cue.SAVED),
            audio.heard,
        )
    }

    /** A channel with nothing to release is asked for nothing, and must not throw. */
    @Test fun `releasing a channel that holds nothing is harmless`() {
        val cues = PlatformFeedbackCues(enabled = { true }, audio = RecordingChannel())
        cues.releaseAudio()
        cues.play(Cue.SAVED)
    }
}

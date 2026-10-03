package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.Cue
import ai.passioncode.fabricvr.CueChannel
import ai.passioncode.fabricvr.Playback
import ai.passioncode.fabricvr.PlatformFeedbackCues
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lifecycle contract LC-02, audit F5 (2026-10-03): **a note's playback stops when a recording
 * starts and when the screen that owns it stops.**
 *
 * `FeedbackCues.attachPlayback` existed — `B-226` built the seam so a recording could silence this
 * app's own speaker, which sits centimetres from the headset's microphones — and nothing called
 * it. So `silencePlayback()` never ran: a clip played on one row bled into the dictation started
 * on the next, and a clip kept playing after the panel was hidden or the headset taken off, until
 * it ended on its own.
 *
 * The binding is tested on its own because the composable around it is three lines that hand it a
 * lifecycle; the real [PlatformFeedbackCues] is the seam on the other side.
 */
class PlaybackBindingTest {

    private class Counting : Playback {
        var stops = 0
        override fun stop() { stops++ }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun cues() = PlatformFeedbackCues(enabled = { false }, audio = CueChannel { })

    @Test fun `starting a recording stops the playback`() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val playback = Counting()
        val cues = cues()
        PlaybackBinding(playback, cues).bind(owner.lifecycle)

        cues.play(Cue.RECORD_START)

        assertEquals("a recording started over this app's own playback", 1, playback.stops)
    }

    @Test fun `the screen stopping stops the playback`() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val playback = Counting()
        PlaybackBinding(playback, cues()).bind(owner.lifecycle)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertEquals("a clip kept playing after its screen was hidden", 1, playback.stops)
    }

    /**
     * Two surfaces can each hold a player, and the older one's teardown can arrive after the newer
     * one's setup. Unbinding must release only its own — and an unbound player must not be stopped
     * by a recording on a surface it no longer belongs to.
     */
    @Test fun `an unbound player is left alone, and a newer one is not detached by it`() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val old = Counting()
        val current = Counting()
        val cues = cues()
        val oldBinding = PlaybackBinding(old, cues).apply { bind(owner.lifecycle) }
        PlaybackBinding(current, cues).bind(owner.lifecycle)
        oldBinding.unbind(owner.lifecycle)

        cues.play(Cue.RECORD_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertEquals("an unbound player was still reached", 0, old.stops)
        assertEquals("the old surface's teardown detached the newer player", 2, current.stops)
    }
}

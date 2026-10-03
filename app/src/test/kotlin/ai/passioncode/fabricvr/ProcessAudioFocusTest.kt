package ai.passioncode.fabricvr

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Lifecycle contract LC-02, audit F4 (2026-10-03): **audio focus is attached once, for the process,
 * not by whichever surface opened first.**
 *
 * It was attached from `ImmersiveActivity.onSceneReady` alone. A dictation on the panel therefore
 * ducked nothing until the Space had been opened once in that process, and everything after —
 * the same press, two behaviours, depending on history the person cannot see.
 *
 * No activity exists in this test, which is the point: the panel-only path, end to end through the
 * platform `AudioManager` (Robolectric's shadow records the requests the real one would receive).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProcessAudioFocusTest {

    private val manager: AudioManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)

    @Test fun `with no Space ever opened, a dictation ducks the rest and then gives it back`() {
        val cues = PlatformFeedbackCues(enabled = { false }, audio = CueChannel { })
        attachProcessAudioFocus(cues, manager)

        cues.play(Cue.RECORD_START)
        val requested = shadowOf(manager).lastAudioFocusRequest
        assertNotNull("a panel-only dictation asked the mixer for nothing", requested)

        cues.play(Cue.RECORD_STOP)
        assertSame(
            "the focus given back is not the one that was granted, so the platform never releases it",
            requested.audioFocusRequest,
            shadowOf(manager).lastAbandonedAudioFocusRequest,
        )
    }

    @Test fun `a device with no AudioManager leaves recording alone`() {
        val cues = PlatformFeedbackCues(enabled = { false }, audio = CueChannel { })
        attachProcessAudioFocus(cues, null)

        cues.play(Cue.RECORD_START)
        cues.play(Cue.RECORD_STOP)

        assertNull(shadowOf(manager).lastAudioFocusRequest)
    }
}

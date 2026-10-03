package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.FeedbackCues
import ai.passioncode.fabricvr.Graph
import ai.passioncode.fabricvr.Playback
import ai.passioncode.fabricvr.common.Log2
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/**
 * Playing back one recording, and nothing more.
 *
 * `T-050`, and it existed in no spec: `T-024` handed the player to `T-035` and `T-035` handed it
 * back, so `DEC-0022`'s obligation — *"the audio is kept, and it is what makes Transcribe again
 * possible"* — had a **circular deferral** where its owner should have been. Keeping a person's
 * voice for ever while giving them no way to hear it is the half of `G-12` that no amount of
 * disclosure fixes: they can now see that the recordings exist and how much they cost, and until
 * this they still could not listen to one.
 *
 * `MediaPlayer` rather than `ExoPlayer`: one 16 kHz mono WAV, no streaming, no playlist, no
 * seeking. A media3 dependency to play a local file would be a library for a feature that is a
 * `start()` and a `release()`.
 *
 * **One player at a time, owned by the composition that started it.** A second tap on another row
 * stops the first — two recordings of the same person talking over each other is not a state
 * anyone asked for — and leaving the screen releases it, because a `MediaPlayer` that outlives
 * its row holds an audio focus and a file descriptor nothing will ever close.
 */
@Composable
internal fun rememberAudioPlayback(cues: FeedbackCues = Graph.feedbackCues): AudioPlayback {
    val playback = remember { AudioPlayback() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(playback, lifecycle, cues) {
        val binding = PlaybackBinding(playback, cues).apply { bind(lifecycle) }
        onDispose {
            binding.unbind(lifecycle)
            playback.release()
        }
    }
    return playback
}

/**
 * Ties a player to the two moments it must stop at (LC-02, audit F5 of 2026-10-03).
 *
 * - **A recording starting.** `B-226` built `FeedbackCues.attachPlayback` so the `RECORD_START` cue
 *   could silence this app's own speaker — centimetres from the headset's microphones — before the
 *   microphone opens, and nothing ever attached a player. A clip played on one row bled into the
 *   dictation started on the next.
 * - **The screen stopping** — the panel hidden, the headset taken off, another app in front. The
 *   player stopped only on dispose or at the end of the clip.
 *
 * [unbind] detaches only its own player (`detachPlayback` is identity-guarded): two surfaces can
 * each hold one, and the older one's teardown can arrive after the newer one's setup.
 */
internal class PlaybackBinding(
    private val playback: Playback,
    private val cues: FeedbackCues,
) : DefaultLifecycleObserver {

    fun bind(lifecycle: Lifecycle) {
        cues.attachPlayback(playback)
        lifecycle.addObserver(this)
    }

    fun unbind(lifecycle: Lifecycle) {
        lifecycle.removeObserver(this)
        cues.detachPlayback(playback)
    }

    override fun onStop(owner: LifecycleOwner) = playback.stop()
}

/** What a row needs to play its recording and to know whether it is the one playing. */
internal class AudioPlayback : Playback {
    private var player: MediaPlayer? = null

    var playingId: String? by mutableStateOf(null)
        private set

    /**
     * Starts [path], or stops it when it is already the one playing — one control, two
     * intentions, the same shape as the record button.
     *
     * A missing file is not an error worth a banner: the recording may have been swept or deleted
     * from another surface, and the row's button simply does nothing rather than accusing the
     * person of something. It is logged by path.
     */
    fun toggle(id: String, path: String) {
        if (playingId == id) {
            stop()
            return
        }
        stop()
        val file = File(path)
        if (!file.isFile) {
            Log2.w("audio.play.missing", "note" to id)
            return
        }
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                // `setOnErrorListener` returning true means "handled": without it a decode error
                // also fires the completion listener, and `stop()` would run twice.
                setOnErrorListener { _, what, extra ->
                    Log2.w("audio.play.failed", "what" to what, "extra" to extra)
                    stop()
                    true
                }
                prepare()
                start()
            }
        }.onFailure {
            Log2.w("audio.play.failed", "note" to id)
            player = null
        }.getOrNull()
        playingId = if (player != null) id else null
    }

    override fun stop() {
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
        playingId = null
    }

    fun release() = stop()
}


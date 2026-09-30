package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.common.Log2
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
internal fun rememberAudioPlayback(): AudioPlayback {
    val playback = remember { AudioPlayback() }
    DisposableEffect(Unit) { onDispose { playback.release() } }
    return playback
}

/** What a row needs to play its recording and to know whether it is the one playing. */
internal class AudioPlayback {
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

    fun stop() {
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
        playingId = null
    }

    fun release() = stop()
}

package ai.passioncode.fabricvr.stt

import java.util.concurrent.TimeUnit
import okhttp3.Call

/**
 * How long one remote transcription may take, from how long the recording is (`B-243`, `DEC-0091`).
 *
 * A fixed deadline was 60 s for a whisper-server and 120 s for a cloud endpoint, while a dictation
 * may run ten minutes. The whole recording is uploaded and then decoded before the first byte of
 * the answer — whisper.cpp's server has no streaming reply — so the time is roughly *upload + decode*,
 * and decode on a home machine is of the order of the audio's own length. A ten-minute dictation
 * therefore always timed out and fell back to the headset: the server the person chose was never
 * used for the dictations it matters most for.
 *
 * **A long deadline is not a long wait for a person who has given up**: *Stop transcribing* cancels
 * the call (`CallAwait.kt`), so the bound only decides when an unattended call is abandoned.
 */
internal object RemoteDeadline {

    /** Never less than this — the old fixed deadline, which short dictations always met. */
    const val FLOOR_SECONDS = 60L

    /** Seconds allowed for the upload itself: ~19 MB of WAV at a modest home-Wi-Fi rate. */
    private const val UPLOAD_SECONDS = 30L

    /** Seconds of decode allowed per second of audio: twice real time, for a CPU-only server. */
    private const val DECODE_FACTOR = 2L

    fun secondsFor(samples: Int, sampleRate: Int): Long {
        val audio = if (sampleRate > 0) samples.toLong() / sampleRate else 0L
        return maxOf(FLOOR_SECONDS, UPLOAD_SECONDS + audio * DECODE_FACTOR)
    }

    /** Sets this call's deadline before it is enqueued; OkHttp reads it only then. */
    fun Call.withDeadlineFor(samples: Int, sampleRate: Int): Call =
        apply { timeout().timeout(secondsFor(samples, sampleRate), TimeUnit.SECONDS) }
}

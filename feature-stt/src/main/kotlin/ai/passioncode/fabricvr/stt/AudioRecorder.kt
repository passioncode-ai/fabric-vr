package ai.passioncode.fabricvr.stt

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import ai.passioncode.fabricvr.common.AppError
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * What a view model needs from a microphone, so that a test can supply one.
 *
 * [AudioRecorder] is a final class wrapping `android.media.AudioRecord`, and its `record` is a
 * `while (isActive)` loop on `Dispatchers.IO`. Under Robolectric the shadow returns a full buffer
 * every read, so a test that let it start filled the heap in seconds — every assertion about what
 * happens *after* a recording was therefore unwritable, which is the same shape as the reach-into-
 * a-singleton that `T-017` legislated against. The interface is the seam; nothing about the real
 * recorder's behaviour changes. Its own defects — `E-02`'s boxed `ArrayList<Short>` and `C-05`'s
 * negative-`read` busy loop — were `T-020`'s and are fixed in the body below; see [PcmSource] for
 * the seam that made the second one testable.
 */
interface Recorder {
    fun hasPermission(): Boolean

    /**
     * Whether the OS is currently handing this recorder **empty audio** instead of the
     * microphone (`H11`).
     *
     * *Meta Horizon OS Audio*: when the system or another app takes the microphone, "the
     * microphone stream isn't closed and is provided empty audio data". Nothing in a read loop
     * can tell that apart from a silent room, which is why it is published rather than inferred —
     * and why the app must say *the headset muted your microphone* rather than *nothing heard*,
     * which blames the person for the system's decision.
     *
     * It is a state and not an error: the recording keeps running, because the microphone can
     * come back, and this falls again when it does. Cleared when a recording starts and when one
     * ends, so it never describes a recording that is over.
     *
     * The default answers `false` for ever. That is the honest answer for a [Recorder] that has
     * no way to ask — a fake in a view-model test — and the real one overrides it.
     */
    val silenced: StateFlow<Boolean> get() = NEVER_SILENCED

    /**
     * @param onSamples handed the recorder's **own** buffer and a count. The array is reused on
     *   the very next read and is valid only for the duration of the call — the caller copies
     *   what it wants. It took a `List<Short>` and allocated a new one per chunk, which is
     *   `E-02`: twenty bytes of Java object per two bytes of audio.
     */
    fun record(onSamples: (ShortArray, Int) -> Unit): Flow<AudioRecorder.Level>
}

/**
 * The microphone itself, behind an interface so the read loop can be driven by a test.
 *
 * `C-05` is a defect in how this loop reacts to a **negative** return, and the only honest way to
 * test that is to make one happen. `android.media.AudioRecord` is final and its Robolectric shadow
 * answers every read with a full buffer, so without this seam the failure path is unreachable from
 * any test and the fix would be a claim.
 */
interface PcmSource {
    val isInitialized: Boolean

    /**
     * Whether this source is currently being handed empty audio by the OS (`H11`).
     *
     * The same seam argument as the rest of this interface: the production answer is
     * `AudioRecord.getActiveRecordingConfiguration()?.isClientSilenced` and
     * `AudioManager.isMicrophoneMute`, neither of which a JVM test can make true, so without it
     * the fix would be a claim. The default is `false` because a fake written for `C-05` is
     * making no statement about silencing.
     */
    val isSilenced: Boolean get() = false

    fun start()
    fun read(into: ShortArray, offset: Int, count: Int): Int
    fun stop()
    fun release()
}

/** One flow for every [Recorder] that cannot detect silencing; it never changes, so sharing is free. */
private val NEVER_SILENCED: StateFlow<Boolean> = MutableStateFlow(false)

/** One recording, captured at the rate whisper.cpp expects (`WHISPER_SAMPLE_RATE` is 16000). */
class AudioRecorder(private val context: Context) : Recorder {

    data class Level(val rms: Float, val samples: Int)

    private val _silenced = MutableStateFlow(false)

    /** See [Recorder.silenced]. Written only by the read loop, which is the only thing that asks. */
    override val silenced: StateFlow<Boolean> = _silenced.asStateFlow()

    private val audioManager: android.media.AudioManager? by lazy {
        runCatching { context.getSystemService(android.media.AudioManager::class.java) }.getOrNull()
    }

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Records until the flow is cancelled, emitting a level for the meter and handing every chunk
     * to [onSamples]. The caller owns the accumulation — and owns its locking, because this runs
     * on an IO thread while the caller may be stopping the recording. [PcmBuffer] is what it
     * should own it with.
     *
     * **The array handed to [onSamples] is this recorder's own and is overwritten by the next
     * read.** It is valid for the duration of the call and no longer. That is the whole point:
     * the producer allocates nothing per chunk, where it used to build an `ArrayList<Short>` and
     * box every sample (`E-02`).
     */
    @SuppressLint("MissingPermission")
    override fun record(onSamples: (ShortArray, Int) -> Unit): Flow<Level> = record(onSamples, ::openMicrophone)
    /** The same loop, over a source a test can supply. See [PcmSource]. */
    internal fun record(
        onSamples: (ShortArray, Int) -> Unit,
        openSource: () -> PcmSource,
    ): Flow<Level> = callbackFlow {
        if (!hasPermission()) {
            close(SttException(AppError.Permission("microphone")))
            return@callbackFlow
        }
        val recorder = openSource()
        if (!recorder.isInitialized) {
            recorder.release()
            close(SttException(AppError.SttFailed("audio-record", IllegalStateException("not initialized"))))
            return@callbackFlow
        }
        val buffer = ShortArray(bufferSamples())
        var total = 0
        var transientReads = 0
        // A recording never begins already muted, whatever the last one ended as.
        _silenced.value = false
        try {
            // **Inside the `try`, so the `finally` releases a microphone that refused to start**
            // (`B-248`). It sat above it: `startRecording()` throws when another client — the
            // shell's voice command — holds the microphone, and the `AudioRecord` then leaked for
            // the life of the process.
            recorder.start()
            while (isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) {
                    // `C-05`. A negative code means the session died (`ERROR_DEAD_OBJECT`, −6),
                    // the microphone went to the shell's voice command or another app, or the
                    // recorder is not started yet (`ERROR_INVALID_OPERATION`, −3). The old loop
                    // tested `if (read > 0)` and fell straight back into `read()`: one core at
                    // 100%, the meter frozen at its last value, the interface still saying
                    // *Recording* — and when the person stopped, a buffer too short to transcribe,
                    // so they were told "nothing was heard". That is the one failure a dictation
                    // app must not have.
                    if (read == AudioRecord.ERROR_INVALID_OPERATION && transientReads++ < MAX_TRANSIENT_READS) {
                        delay(RETRY_MS)
                        continue
                    }
                    close(
                        SttException(
                            AppError.SttFailed("audio-record", IllegalStateException("read=$read")),
                        ),
                    )
                    return@callbackFlow
                }
                if (read == 0) {
                    // Zero is neither an error nor audio, and a source that keeps answering it
                    // is the `C-05` spin in a different costume: the 3-arg `read` blocks, so a
                    // persistent 0 should not happen — and "should not happen" is what the
                    // negative branch above used to say too. Counted, delayed and bounded.
                    if (transientReads++ < MAX_TRANSIENT_READS) {
                        delay(RETRY_MS)
                        continue
                    }
                    close(
                        SttException(
                            AppError.SttFailed("audio-record", IllegalStateException("read=0 repeatedly")),
                        ),
                    )
                    return@callbackFlow
                }
                transientReads = 0
                // `H11`, asked once per delivered chunk — about four times a second, which is
                // the read's own cadence and the only place the answer can change anything.
                //
                // **Polled rather than driven by `registerAudioRecordingCallback`.** The callback
                // arrives on a binder thread and would need its own executor, its own
                // unregistration on every exit from this loop, and a bridge back into the flow —
                // three pieces of lifecycle to learn, a quarter of a second earlier, about a
                // condition that lasts seconds. The read loop is already ticking and already owns
                // the recording's lifetime. The two questions Meta names are asked together
                // because they answer different halves of it: `isClientSilenced` is the OS
                // handing *this* client empty audio, `isMicrophoneMute` is the whole device
                // muted, and neither implies the other.
                val muted = runCatching { recorder.isSilenced }.getOrDefault(false)
                if (muted != _silenced.value) _silenced.value = muted
                var sum = 0.0
                for (i in 0 until read) sum += abs(buffer[i].toInt()).toDouble()
                onSamples(buffer, read)
                total += read
                trySend(Level(rms = (sum / read / Short.MAX_VALUE).toFloat(), samples = total))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            // A signal about a recording that no longer exists is a message the person cannot act
            // on, and the next screen would open showing it.
            _silenced.value = false
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private fun bufferSamples(): Int {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return (if (minBuffer > 0) minBuffer * 2 else SAMPLE_RATE) / 2
    }

    @SuppressLint("MissingPermission")
    private fun openMicrophone(): PcmSource {
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSamples() * 2,
        )
        return object : PcmSource {
            override val isInitialized get() = record.state == AudioRecord.STATE_INITIALIZED

            /**
             * `H11`, the two questions *Meta Horizon OS Audio* names, in the order they answer.
             *
             * `isClientSilenced` is the specific one — the OS is feeding **this** client empty
             * audio while another app or the system holds the microphone — and it is only
             * readable while a configuration is active, so a null means "not capturing", not
             * "fine". `isMicrophoneMute` is the device-wide switch, which the first would not
             * report. Both are API 29 and this module's `minSdk` is 34.
             *
             * Wrapped, because a binder call four times a second must not be able to end a
             * recording: failing to *ask* whether the microphone is muted is not a reason to stop
             * recording with it.
             */
            override val isSilenced: Boolean
                get() = runCatching {
                    record.activeRecordingConfiguration?.isClientSilenced == true ||
                        audioManager?.isMicrophoneMute == true
                }.getOrDefault(false)

            override fun start() = record.startRecording()
            override fun read(into: ShortArray, offset: Int, count: Int) = record.read(into, offset, count)
            override fun stop() = record.stop()
            override fun release() = record.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * How many `ERROR_INVALID_OPERATION`s to ride out before giving up. That code means "not
         * started yet", which is genuinely transient for a frame or two after `startRecording`;
         * every other negative code is final and is not retried.
         */
        internal const val MAX_TRANSIENT_READS = 5
        internal const val RETRY_MS = 20L
    }
}

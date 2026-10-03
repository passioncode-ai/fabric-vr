package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.Log2
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator

/**
 * The four moments a dictation has that the person must be able to notice without looking at the
 * panel (`REQ-062`, audit `M22`).
 *
 * Four members and not one, because the four mean different things: [AUTO_STOP] is the app
 * deciding to stop while the person is still talking, and it must not sound like [RECORD_STOP],
 * which is the person's own press being obeyed.
 */
enum class Cue {
    /** The microphone is open. `TodayScreen` shows the seconds counting at the same moment. */
    RECORD_START,

    /** The person pressed stop; the recording is on its way to an engine. */
    RECORD_STOP,

    /** The ten-minute cap fired (`DEC-0032`) — the app stopped, not the person. */
    AUTO_STOP,

    /** The note reached the database. `state_saved_and_copied` is on screen. */
    SAVED,
}

/** One way of making a cue perceptible. Audio is one; the Space's haptics are the other. */
fun interface CueChannel {
    fun emit(cue: Cue)
}

/**
 * Whatever this app is playing through the speaker, so a recording can silence it (`B-226`).
 *
 * **The speaker feeds the microphone.** A headset's speakers are centimetres from its microphone
 * array, so a person who taps *play* on one note and then holds the trigger for another was
 * dictating over their own voice — and whisper cannot tell the two apart, because they are the
 * same voice. Nothing stopped playback when a recording started.
 *
 * A seam rather than a direct call because the player lives in `ui/`, is owned by the composition
 * that started it, and there can be none at all — the Space has no note list open.
 */
fun interface Playback {
    fun stop()
}

/**
 * The device's audio focus, as a seam (`B-226`).
 *
 * **Meta documents this exact failure.** *"Entering an immersive experience can cause background
 * audio from other apps to stop… avoid requesting `AUDIOFOCUS_GAIN` … use
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` … handle `AudioManager.OnAudioFocusChangeListener`"* — on
 * a product whose whole position is being the notebook **beside** a streamed desktop. This app
 * requested no focus at all, which is not the safe default it looks like: the platform is then
 * free to treat an app that opens a microphone and plays notification tones however it likes, and
 * the app has no way to be told when something else takes the output.
 *
 * An interface because `AudioManager` cannot be constructed in a JVM test, and because the panel
 * host and the Space each have a context while `Graph.feedbackCues` — which is process-wide — has
 * none.
 */
interface AudioFocus {

    /**
     * Ask to duck whatever else is playing, for as long as the microphone is open.
     *
     * `TRANSIENT_MAY_DUCK` and not `GAIN`: the person's music or call should get quieter, not
     * stop. Idempotent — a second request while one is held is not a second grant.
     *
     * @param onLoss called when something else takes the output permanently. The recording is not
     *   stopped by it (the microphone is not focus-gated), but this app's own playback must be.
     * @return whether focus was granted. A refusal is worth logging and is not worth refusing to
     *   record over: the microphone works either way and a person holding the trigger is owed a
     *   recording.
     */
    fun requestTransientDuck(onLoss: () -> Unit): Boolean

    /** Give it back. Idempotent, and safe when nothing was held. */
    fun abandon()

    companion object {
        /** No platform here — a test, or a host that has wired none. Not "focus off". */
        val None: AudioFocus = object : AudioFocus {
            override fun requestTransientDuck(onLoss: () -> Unit): Boolean = true
            override fun abandon() = Unit
        }
    }
}

/**
 * Where the rest of the app announces a cue, without knowing which channels exist.
 *
 * **Why a seam at all.** Meta documents exactly one Kotlin haptics API —
 * `spatial.applyHapticFeedback(hand, amplitude, durationNs, frequency)` on an
 * `AppSystemActivity` (*Inputs and controllers*, fetched 2026-09-21) — so haptics exist in the
 * **Space** and nowhere else, and whether `android.os.Vibrator` reaches a Touch controller from
 * a 2D panel is UNVERIFIED. A call site cannot know which surface it is on, so it does not try:
 * it plays a [Cue] and the seam decides what that costs here.
 */
interface FeedbackCues {
    fun play(cue: Cue)

    /**
     * Attaches the surface's haptic channel, or detaches it with null.
     *
     * `ImmersiveActivity` attaches one when its scene is ready and detaches it when it stops, so
     * a controller pulse happens in the Space and nowhere else — rather than being attempted on
     * a panel and silently doing nothing, which is the state this app was already in.
     */
    fun attachHaptics(channel: CueChannel?)

    /**
     * Detaches [channel], and **only** [channel].
     *
     * Both surfaces can be alive at once, and the old one's `onDestroy` can arrive after the new
     * one's `onSceneReady` — so an unconditional `attachHaptics(null)` on the way out would
     * silence the Space that has just opened. Identity is what makes the order not matter.
     */
    fun detachHaptics(channel: CueChannel)

    /**
     * Attaches the platform's audio focus, or detaches it with null (`B-226`).
     *
     * `AudioManager` needs a `Context` and this seam is constructed without one, so it is handed
     * over. Unlike the haptic channel there is nothing surface-specific about it — the application
     * context answers for the whole process — so it is attached **once, by `Graph.init`** through
     * [attachProcessAudioFocus], and never detached: a dictation started in the Space and finished
     * after it closed must still give the focus back. (It was attached by the Space alone until
     * the 2026-10-03 lifecycle audit, F4, so the panel ducked nothing until the Space had opened.)
     *
     * **Default: nothing.** Until a host wires one, this is exactly the behaviour before `B-226`,
     * which is the honest default for a seam that cannot construct its own platform.
     */
    fun attachAudioFocus(focus: AudioFocus?) = Unit

    /**
     * Attaches the player a recording must silence, or detaches it with null (`B-226`).
     *
     * [detachPlayback] is the identity-guarded half, for [detachHaptics]'s reason: two surfaces
     * can be alive at once and the older one's teardown can arrive after the newer one's setup.
     */
    fun attachPlayback(playback: Playback?) = Unit

    /** Detaches [playback], and **only** [playback]. */
    fun detachPlayback(playback: Playback) = Unit

    /**
     * The microphone closed on a path that plays no cue (`B-226`).
     *
     * [Cue.RECORD_STOP] and [Cue.AUTO_STOP] already say this, and a **cancelled** gesture says
     * nothing at all — `VoiceViewModel.cancel()` discards the recording without a cue, because
     * there is nothing to acknowledge. The audio focus still has to go back, so there is a door
     * that is not a cue. Idempotent: a stop that already released it does nothing.
     */
    fun captureEnded() = Unit

    /**
     * Give the audio channel's native allocation back (`B-226`).
     *
     * `ToneGenerator` holds an `AudioTrack` outside the Java heap and had no release path at all —
     * allocated on the first cue and held until the process died. Called from a host's `onDestroy`;
     * **safe to call while another surface is still alive**, because the channel simply allocates
     * again on the next cue. That property is what makes it callable from either host without the
     * two having to agree about which of them is last.
     */
    fun releaseAudio() = Unit

    companion object {
        /**
         * The seam that does nothing, for a test and for any host that has not wired one. It is
         * not "cues off" — that is the person's switch — it is "no platform here".
         */
        val None: FeedbackCues = object : FeedbackCues {
            override fun play(cue: Cue) = Unit
            override fun attachHaptics(channel: CueChannel?) = Unit
            override fun detachHaptics(channel: CueChannel) = Unit
        }
    }
}

/**
 * The shipped seam: audio everywhere, haptics where a surface has attached them, and one switch
 * over both.
 *
 * **Meta's two rules** (*Haptics: Best practices*, fetched 2026-09-21) are the shape of this
 * class. "Do not just play haptic feedback if there is no corresponding visual or audio cue to
 * relate it to" — hence [audio] fires on every surface and the haptic is an addition to it,
 * never a substitute, and every one of the four moments already draws a line on the panel.
 * "Make haptic feedback optional and adjustable" — hence [enabled], read at each play so a
 * change in Settings takes effect without rebuilding anything.
 *
 * @param enabled the person's switch. Read per cue, not captured: `SettingsViewModel` writes the
 *   value and this must not go on chirping at somebody who has just turned it off.
 */
class PlatformFeedbackCues(
    private val enabled: () -> Boolean,
    private val audio: CueChannel,
) : FeedbackCues {

    @Volatile private var haptics: CueChannel? = null
    @Volatile private var focus: AudioFocus = AudioFocus.None
    @Volatile private var playback: Playback? = null

    /** True exactly while this seam is holding focus, so requesting and abandoning are idempotent. */
    @Volatile private var holdingFocus: Boolean = false

    override fun attachHaptics(channel: CueChannel?) { haptics = channel }

    @Synchronized
    override fun detachHaptics(channel: CueChannel) {
        if (haptics === channel) haptics = null
    }

    override fun attachAudioFocus(focus: AudioFocus?) {
        this.focus = focus ?: AudioFocus.None
    }

    override fun attachPlayback(playback: Playback?) { this.playback = playback }

    @Synchronized
    override fun detachPlayback(playback: Playback) {
        if (this.playback === playback) this.playback = null
    }

    override fun play(cue: Cue) {
        // **The device's audio is handled BEFORE the person's switch, and that is deliberate**
        // (`B-226`). [enabled] is *"do I want to hear a blip"*; it is not *"may other apps keep
        // playing over my open microphone"* and it is not *"may my own playback feed the mic".*
        // Reading it first would have made turning cues off silently turn the audio session off
        // too, which is a setting nobody offered and nobody could find.
        when (cue) {
            Cue.RECORD_START -> beginCapture()
            Cue.RECORD_STOP, Cue.AUTO_STOP -> captureEnded()
            Cue.SAVED -> Unit
        }
        if (!enabled()) return
        // Each channel is on its own `runCatching`: a `ToneGenerator` that will not allocate must
        // not take the haptic with it, and neither may take down the dictation they are about.
        runCatching { audio.emit(cue) }.onFailure { Log2.w("cue.audio.failed", "cue" to cue) }
        haptics?.let { channel ->
            runCatching { channel.emit(cue) }.onFailure { Log2.w("cue.haptic.failed", "cue" to cue) }
        }
    }

    /**
     * The microphone is about to open: silence our own speaker, then duck everything else's.
     *
     * **In that order.** Stopping playback is the one that must not be skipped — a recording made
     * over this app's own output is unusable and the person cannot tell why — so it happens
     * first and independently of whether focus is granted.
     */
    @Synchronized
    private fun beginCapture() {
        silencePlayback()
        val granted = runCatching { focus.requestTransientDuck(::silencePlayback) }
            .onFailure { Log2.w("cue.focus.failed") }
            .getOrDefault(false)
        holdingFocus = granted
        if (!granted) {
            // Not a refusal to record: the microphone is not focus-gated, and a person holding
            // the trigger is owed a recording whatever the mixer decided.
            Log2.w("cue.focus.denied")
        }
    }

    @Synchronized
    override fun captureEnded() {
        if (!holdingFocus) return
        holdingFocus = false
        runCatching { focus.abandon() }.onFailure { Log2.w("cue.focus.abandon_failed") }
    }

    override fun releaseAudio() {
        (audio as? AutoCloseable)?.let { closeable ->
            runCatching { closeable.close() }.onFailure { Log2.w("cue.audio.release_failed") }
        }
    }

    private fun silencePlayback() {
        playback?.let { player ->
            runCatching { player.stop() }.onFailure { Log2.w("cue.playback.stop_failed") }
        }
    }
}

/**
 * The audio channel, on `ToneGenerator`.
 *
 * **Why a tone and not a bundled sound file.** Three reasons, in order of weight: it ships no
 * asset, so the APK grows by nothing and `NOTICE` gains no entry for a sample somebody would
 * have to licence; it has no decode step, so the cue lands at the moment the state changes
 * rather than a frame or two later, which is the entire point of a start cue; and it plays on
 * `STREAM_NOTIFICATION`, which the headset's own volume control already governs — so "quiet" is
 * something the person can set rather than something this code asserts. The cost is that the
 * exact timbre is the platform's, which for a 40 ms confirmation blip is not a thing worth
 * owning.
 *
 * [VOLUME] is 50 of 100 and the durations are tens of milliseconds: Meta's guidance is that a
 * confirmation is short and stays out of the way. Whether it is audible over a streamed desktop
 * is a device question (`B-183`).
 *
 * The generator is allocated lazily and kept: constructing one per cue is a native allocation on
 * the path of the app's most time-sensitive control, and releasing one while its tone is still
 * playing truncates it. The process holds one.
 *
 * **And gives it back, which it did not** (`B-226`). `ToneGenerator` holds an `AudioTrack` outside
 * the Java heap, and a `by lazy` has no release path at all: the first cue allocated one and the
 * process death freed it. On a headset that is an output stream held open for the lifetime of an
 * app whose own argument for `STREAM_NOTIFICATION` is that it should stay out of the way of
 * whatever else is playing.
 *
 * [close] is therefore the missing half, and it is **re-openable on purpose**: the next cue
 * allocates again. That is what lets either host call it from `onDestroy` without the two having
 * to agree about which of them is last, and it costs one native allocation on a cue the person
 * is not waiting on.
 */
class ToneCueChannel(private val volume: Int = VOLUME) : CueChannel, AutoCloseable {

    private val lock = Any()
    private var generator: ToneGenerator? = null

    /**
     * Sticky: a device that will not give us a `ToneGenerator` will not give us one on the next
     * cue either, and retrying is a native allocation attempt on the app's most time-sensitive
     * path. The `by lazy` this replaced cached the same null for the same reason; it is a field
     * now only because the generator can legitimately go away and come back.
     */
    private var unavailable = false

    private fun generator(): ToneGenerator? = synchronized(lock) {
        generator?.let { return@synchronized it }
        if (unavailable) return@synchronized null
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume) }
            .onFailure {
                Log2.w("cue.tone.unavailable")
                unavailable = true
            }
            .getOrNull()
            .also { generator = it }
    }

    /**
     * Releases the native allocation. Idempotent, and the channel still works afterwards.
     *
     * A tone still sounding is truncated by this, which is why it is called from a host's
     * teardown and not from anywhere on the cue path.
     */
    override fun close() {
        synchronized(lock) {
            generator?.let { runCatching { it.release() }.onFailure { Log2.w("cue.tone.release_failed") } }
            generator = null
        }
    }

    override fun emit(cue: Cue) {
        val tone = when (cue) {
            // Rising, because something opened.
            Cue.RECORD_START -> ToneGenerator.TONE_PROP_BEEP
            // A plain acknowledgement: the person asked for this.
            Cue.RECORD_STOP -> ToneGenerator.TONE_PROP_ACK
            // **Deliberately the one that does not sound like an acknowledgement.** The cap is
            // the app interrupting somebody who is still speaking (`DEC-0032`).
            Cue.AUTO_STOP -> ToneGenerator.TONE_PROP_NACK
            Cue.SAVED -> ToneGenerator.TONE_PROP_BEEP2
        }
        generator()?.startTone(tone, DURATION_MS)
    }

    private companion object {
        /** Half of the stream's own level, which the headset's volume control then scales. */
        const val VOLUME = 50

        /** Long enough to be heard beside a streamed desktop, short enough not to be a noise. */
        const val DURATION_MS = 90
    }
}

/**
 * Gives [cues] the platform's audio focus, once, for the whole process (LC-02/F4 of the 2026-10-03
 * lifecycle audit). Called from `Graph.init`.
 *
 * It used to be attached from `ImmersiveActivity.onSceneReady` alone, so a dictation on the panel
 * ducked nothing until the Space had been opened once in that process — and ducked everything
 * after. The focus answers for the process, not for a surface, so one host-independent point owns
 * it. A device that hands back no `AudioManager` leaves the seam at [AudioFocus.None]: recording
 * is not focus-gated, and the person holding the trigger is still owed a recording.
 */
fun attachProcessAudioFocus(cues: FeedbackCues, manager: AudioManager?) {
    if (manager == null) {
        Log2.w("cue.focus.unavailable")
        return
    }
    cues.attachAudioFocus(AndroidAudioFocus(manager))
}

/**
 * [AudioFocus] on the platform, which is the only implementation that is not a test double.
 *
 * **`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, and Meta names the alternative as the mistake.** *"Avoid
 * requesting `AUDIOFOCUS_GAIN`, which will stop background audio"* — on a device where the thing
 * playing in the background is the person's music or their call, beside the streamed desktop this
 * app is meant to sit next to. `MAY_DUCK` asks for the rest to get quieter for the length of a
 * dictation, which is what a notebook should ask for.
 *
 * `setWillPauseWhenDucked(false)` is the matching half and is easy to get backwards: it says *do
 * not pause me when somebody else ducks me* — this app's own tones are 90 ms and pausing them is
 * meaningless, and asking to be paused would mean asking to be told about every transient duck.
 *
 * @param manager the process's `AudioManager`. From the **application** context: the focus this
 *   holds outlives any one surface, because a dictation started in the Space can finish after it
 *   has closed.
 */
class AndroidAudioFocus(private val manager: AudioManager) : AudioFocus {

    /** What the current holder asked to be told when the output goes away for good. */
    @Volatile private var onLoss: () -> Unit = {}

    /**
     * Meta: *"handle `AudioManager.OnAudioFocusChangeListener`"*, and handling it means three
     * different answers rather than one.
     *
     * `LOSS` is permanent — something else owns the output now — so this app's own playback stops
     * and the request is not re-made; a person who starts a call mid-dictation keeps recording,
     * because the microphone is not focus-gated and their words are the thing being kept.
     * `LOSS_TRANSIENT` and its ducking sibling are the mixer doing exactly what was asked for in
     * the other direction, and there is nothing to do but say so.
     */
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log2.w("cue.focus.lost")
                runCatching { onLoss() }.onFailure { Log2.w("cue.focus.loss_handler_failed") }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> Log2.i("cue.focus.ducked")
            AudioManager.AUDIOFOCUS_GAIN -> Log2.i("cue.focus.gained")
            else -> Unit
        }
    }

    /**
     * One request object, because `abandonAudioFocusRequest` must be handed **the same instance**
     * that was granted — a fresh builder produces an equal-looking request the platform does not
     * recognise, and the focus is then never released.
     */
    private val request: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // What this app actually emits while holding focus: 90 ms confirmation
                    // tones. `USAGE_MEDIA` would ask the mixer to treat a blip as content.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            // A dictation is now or not at all: the person is holding the trigger. Delayed focus
            // would arrive after they had finished speaking.
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener)
            .build()
    }

    override fun requestTransientDuck(onLoss: () -> Unit): Boolean {
        this.onLoss = onLoss
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        onLoss = {}
        manager.abandonAudioFocusRequest(request)
    }
}

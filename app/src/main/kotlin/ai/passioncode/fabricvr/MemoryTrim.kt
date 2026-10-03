package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.Log2
import android.content.ComponentCallbacks2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the process gives back when the system asks for memory (lifecycle contract LC-08, audit F1
 * of 2026-10-03, `DEC-0102`).
 *
 * **There was no `onTrimMemory` anywhere in the app.** `DEC-0007` kept the whisper context "for
 * the process" and named a trim callback as the safety valve; `DEC-0019` found that callback did
 * not exist. So on an 8 GB headset whose memory is shared with the streamed desktop, the only
 * thing the system could reclaim from this app was the whole process — and the next launch then
 * paid the model load anyway, plus a cold start.
 *
 * Called from `FabricVrApp.onTrimMemory`. The release itself is [release] — in production
 * `LocalWhisperOwner.release`, which takes the owner's mutex and so waits for a decode in flight
 * rather than freeing under it. It is launched, never awaited: the callback arrives on the main
 * thread, and a wait for a thirteen-minute decode there is an ANR.
 *
 * @param scope where the release runs — `Graph.scope`, whose exception guard names a failure.
 */
internal class MemoryTrim(
    private val scope: CoroutineScope,
    private val release: suspend () -> Unit,
) {
    fun onTrimMemory(level: Int) {
        if (!releases(level)) return
        scope.launch {
            release()
            Log2.i("stt.whisper.released", "reason" to "trim", "level" to level)
        }
    }

    companion object {
        /**
         * Every level from `TRIM_MEMORY_RUNNING_LOW` up: the running-low family (delivered before
         * Android 14), `UI_HIDDEN` and the background levels (all that Android 14 still
         * delivers). `RUNNING_MODERATE` is the system's earliest hint and is not answered — a
         * reload costs the person ~2 s on their next dictation, and that hint costs nobody
         * anything yet.
         */
        @Suppress("DEPRECATION") // the running-low family is deprecated from API 34, still delivered below it
        fun releases(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    }
}

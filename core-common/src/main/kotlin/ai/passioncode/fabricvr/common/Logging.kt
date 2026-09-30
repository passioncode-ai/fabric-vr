package ai.passioncode.fabricvr.common

import android.util.Log

/**
 * Structured logging with one tag. Note bodies, transcripts and keys never reach the log —
 * [redact] is what makes that a rule rather than an intention.
 *
 * Every call degrades to stderr when there is no Android runtime under it. `android.util.Log` is a
 * stub outside the device and every method throws, so a logger that called it blindly was the one
 * thing that could take down the code it was watching — and it did, in the JVM suite, on exactly the
 * error paths that log.
 */
object Log2 {
    const val TAG = "FabricVR"

    /** Resolved once: whether this process has a real `android.util.Log` cannot change under it. */
    private val onDevice: Boolean = runCatching {
        Log.isLoggable(TAG, Log.INFO)
        true
    }.getOrDefault(false)

    fun i(event: String, vararg pairs: Pair<String, Any?>) = emit('I', line(event, pairs), null)
    fun w(event: String, vararg pairs: Pair<String, Any?>) = emit('W', line(event, pairs), null)
    fun e(event: String, t: Throwable? = null, vararg pairs: Pair<String, Any?>) =
        emit('E', line(event, pairs), t)

    /** Never print a secret or user text: report its shape instead. */
    fun redact(value: String?): String = when {
        value == null -> "null"
        value.isEmpty() -> "empty"
        else -> "<${value.length} chars>"
    }

    private fun emit(level: Char, message: String, t: Throwable?) {
        if (onDevice) {
            when (level) {
                'I' -> Log.i(TAG, message)
                'W' -> Log.w(TAG, message)
                else -> Log.e(TAG, message, t)
            }
        } else {
            System.err.println("$level/$TAG: $message")
            t?.printStackTrace()
        }
    }

    private fun line(event: String, pairs: Array<out Pair<String, Any?>>): String =
        if (pairs.isEmpty()) event
        else event + " " + pairs.joinToString(" ") { (k, v) -> "$k=$v" }
}

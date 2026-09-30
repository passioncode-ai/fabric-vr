package ai.passioncode.fabricvr.common

import java.io.File
import java.time.Instant

/**
 * A crash, written down where the person who saw it can reach it without a laptop.
 *
 * `DEC-0023` makes this load-bearing rather than a convenience: the second headset is a test copy,
 * and the one obligation that follows is that the person holding it can hand back a diagnosis. A
 * crash on that device is otherwise a sentence in a chat message — "it closed" — against a logcat
 * nobody captured, on a headset that is not on this network.
 *
 * Nothing is sent anywhere. Two headsets do not need a backend, and adding one would put stack
 * traces — which can carry note text in an exception message — on somebody else's server.
 */
object CrashLog {

    /** Bounded, so a crash loop cannot fill the device that is already failing. */
    const val MAX_BYTES: Int = 64 * 1024

    private const val FILE = "crashes.log"
    private const val SEPARATOR = "\n--- 8< ---\n"

    /**
     * Appends one record and trims the file from the **front**.
     *
     * Front, not back: a crash loop writes the same failure repeatedly and the interesting one is
     * the newest. Trimming the other way keeps the first crash and discards every one after it,
     * which is the record nobody needs.
     *
     * Never throws. A handler that throws replaces a diagnosable crash with an undiagnosable one,
     * and it runs on a thread that is already dying.
     */
    fun record(dir: File, error: Throwable, meta: Map<String, String> = emptyMap()) {
        runCatching {
            val record = buildString {
                append(SEPARATOR)
                append("at      ").append(Instant.now()).append('\n')
                meta.forEach { (k, v) -> append(k.padEnd(8)).append(v).append('\n') }
                append("error   ").append(describe(error)).append('\n')
            }.let(Redaction::scrub)

            dir.mkdirs()
            val file = File(dir, FILE)
            val existing = if (file.isFile) file.readText() else ""
            file.writeText(trimToBound(existing + record))
        }
    }

    /** The newest record, or null when nothing has ever crashed. */
    fun latest(dir: File): String? = all(dir)
        .split(SEPARATOR)
        .lastOrNull { it.isNotBlank() }
        ?.let { SEPARATOR.trim() + "\n" + it.trim() }

    fun all(dir: File): String = File(dir, FILE).let { if (it.isFile) it.readText() else "" }

    fun clear(dir: File) { runCatching { File(dir, FILE).delete() } }

    /** The exception and everything it was caused by, each with its frames. */
    private fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (depth > 0) append("caused by ")
            append(current::class.java.name).append(": ").append(current.message ?: "no message").append('\n')
            current.stackTrace.take(FRAMES).forEach { append("    at ").append(it).append('\n') }
            current = current.cause.takeIf { it !== current }
            depth++
        }
    }

    /**
     * Cuts whole records off the front, never a half one: a reader must not meet a stack frame with
     * no exception above it and take it for the failure.
     */
    private fun trimToBound(text: String): String {
        var bytes = text.toByteArray()
        if (bytes.size <= MAX_BYTES) return text
        var kept = text
        while (bytes.size > MAX_BYTES) {
            val next = kept.indexOf(SEPARATOR, startIndex = SEPARATOR.length)
            // One record larger than the whole bound: keep its TAIL, cut on a byte boundary that
            // is also a character boundary. `takeLast(MAX_BYTES)` counted characters, and the text
            // this app carries is Cyrillic at two bytes each — so the fallback returned a file of
            // about twice the documented bound, cut mid-record, three lines below a comment
            // promising never a half one. Found by an independent verification pass; no test
            // reached this branch because every case wrote many small records.
            if (next < 0) return String(bytes, bytes.size - MAX_BYTES, MAX_BYTES, Charsets.UTF_8)
            kept = kept.substring(next)
            bytes = kept.toByteArray()
        }
        return kept
    }

    private const val FRAMES = 12
    private const val MAX_CAUSES = 5
}

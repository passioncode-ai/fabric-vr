package ai.passioncode.fabricvr.stt

/**
 * Where a dictation is transcribed **first**. The on-device engine is always the fallback, so this
 * chooses what is tried before it, never whether there is a safety net.
 *
 * [LOCAL] is the default and the only one that works with no account and no network: speech never
 * leaves the headset. The other two send the recording to a server, which is a decision about the
 * person's voice and is therefore theirs to make explicitly.
 */
enum class SttProvider(val key: String) {
    /** On-device whisper.cpp only. Nothing leaves the headset. */
    LOCAL("local"),

    /** An OpenAI-compatible endpoint — Groq, OpenAI, or a self-hosted one. */
    CLOUD("cloud"),

    /** A whisper.cpp server, typically the person's own machine on their own network. */
    SERVER("server"),
    ;

    companion object {
        val DEFAULT = LOCAL
        fun byKey(key: String?): SttProvider = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

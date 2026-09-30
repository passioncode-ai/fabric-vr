package ai.passioncode.fabricvr.stt

/**
 * The speech models this app will download, with the size and digest their publisher states.
 *
 * Every figure here was read from Hugging Face's own API on 2026-09-19
 * (`/api/models/ggerganov/whisper.cpp/tree/main?recursive=1`), where `lfs.oid` is the file's
 * SHA-256; the small model's digest was additionally confirmed against an independent download.
 * They are not estimates and must never be adjusted to make a download pass — a mismatch means the
 * bytes are wrong, which is the whole point of pinning them.
 *
 * No user-facing wording lives here. [key] is stable and the app maps it to a string resource, so
 * this module stays free of copy and of a language.
 */
enum class WhisperModel(
    val key: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
) {
    TINY("tiny", "ggml-tiny-q5_1.bin", 32_152_673L, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"),
    BASE("base", "ggml-base-q5_1.bin", 59_707_625L, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
    SMALL("small", "ggml-small-q5_1.bin", 190_085_487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
    MEDIUM("medium", "ggml-medium-q5_0.bin", 539_212_467L, "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f"),
    LARGE_TURBO("large-turbo", "ggml-large-v3-turbo-q5_0.bin", 574_041_195L, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
    ;

    companion object {
        /**
         * Small is the default: it is the smallest that transcribes Russian usefully, and it is the
         * one whose latency on this headset has actually been measured (1.31x real time at four
         * threads). Anything larger is an invitation, not a recommendation.
         */
        val DEFAULT = SMALL

        fun byKey(key: String?): WhisperModel = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

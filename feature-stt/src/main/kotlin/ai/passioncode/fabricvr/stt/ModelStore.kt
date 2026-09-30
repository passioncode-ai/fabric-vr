package ai.passioncode.fabricvr.stt

import java.io.File

/** Where the speech model lives on the device, and whether it is actually there. */
interface ModelStore {
    val modelName: String
    val expectedBytes: Long
    val expectedSha256: String
    val downloadUrl: String

    /**
     * Hosts a redirect from [downloadUrl] may land on, besides the download host itself.
     *
     * It is declared here rather than derived from the URL because a vendor's own CDN is often a
     * different registrable domain: Hugging Face answers `resolve/main` with a 302 to
     * `us.aws.cdn.hf.co`, which shares no registrable domain with `huggingface.co`. Deriving the
     * rule from the URL therefore refused the vendor's own CDN and the model could not be
     * downloaded at all — measured on a Quest 3 on 2026-09-19. A match is on the host's registrable
     * suffix, so `us.aws.cdn.hf.co` is covered by `hf.co`.
     *
     * This is defence in depth and no longer the only line: [expectedSha256] is pinned to the
     * value the vendor publishes, so bytes that arrive from anywhere at all must still hash to it.
     */
    val allowedRedirectHosts: Set<String> get() = emptySet()

    fun modelFile(): File
    fun isPresent(): Boolean
}

/**
 * A whisper.cpp model on this device, one file per [WhisperModel], all in one directory so that
 * switching between them keeps whatever was already downloaded.
 *
 * The digests were empty until 2026-09-19 because none had been verified from a primary source, and
 * pinning one nobody checked is a claim rather than a check. They now come from [WhisperModel],
 * which holds the values Hugging Face publishes. The downloader refuses a mismatch: a large payload
 * swapped in flight is the cheapest attack on this app.
 */
class FileModelStore(
    private val root: File,
    val model: WhisperModel = WhisperModel.DEFAULT,
) : ModelStore {

    override val modelName: String = model.fileName
    override val expectedBytes: Long = model.bytes
    override val expectedSha256: String = model.sha256

    override val downloadUrl: String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$modelName"

    /** Hugging Face serves the blob itself from `hf.co`, not from `huggingface.co`. */
    override val allowedRedirectHosts: Set<String> = setOf("huggingface.co", "hf.co")

    override fun modelFile(): File = File(root, modelName)

    override fun isPresent(): Boolean {
        val file = modelFile()
        if (!file.exists()) return false
        // Exact, not "roughly": a truncated file passes a half-size check and then fails inside
        // whisper, where the error reads as "couldn't transcribe" rather than "the model is broken".
        return if (expectedBytes > 0) file.length() == expectedBytes else file.length() > 0
    }
}

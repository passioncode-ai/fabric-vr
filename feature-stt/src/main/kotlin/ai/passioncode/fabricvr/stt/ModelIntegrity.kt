package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.Log2
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the model on disk is still the file whose digest was pinned — asked once per process,
 * and only when something has already gone wrong (`B-191`).
 *
 * `ModelStore.isPresent` compares the byte **count**, and nothing re-verified the SHA after the
 * download: `ModelDownloader` checks it on the way in and then never again. A file corrupted
 * afterwards — a bad block, a half-written restore, a truncation that happened to round back to
 * the right length — is therefore *present* for ever. `whisper_init_from_file_with_params`
 * returns null, [WhisperEngine] sees `initContext` return 0, and every dictation fails the same
 * way with the same sentence. Nothing evicts the file, so the only way out is Settings →
 * *Remove speech model*: a step the person has no reason to suspect, under a message that told
 * them their dictation failed.
 *
 * **When the digest is taken, and why not sooner.** On the first loader failure, never on a good
 * path. The alternative the row offers — hash once per process at first use — would put seconds
 * of SHA-256 over 190–574 MB in front of the first dictation of *every* process, on the path
 * where the person is holding the trigger, to discover something that is true of one run in
 * thousands. Hashing on every dictation is not on the table at all. So the cost is paid only by
 * the run that has already failed, where a few seconds buys a diagnosis and a way out.
 *
 * **What this deliberately does not cover.** A model wrong in a way that makes ggml abort —
 * `GGML_ASSERT` calls `abort()` — kills the process outright, and neither the JNI `try`/`catch`
 * nor a check that runs after the loader returns can intervene. That is the case the eager hash
 * would have caught, and it is the price of the choice above. The loader's own diagnosis now
 * reaches logcat through `whisper_log_set` (`fabricvr_whisper.cpp`) so that such a crash is at
 * least readable; if one is ever observed on a headset, that observation is what reverses this
 * decision, not an argument.
 *
 * @param digestOf how a file becomes a hex digest. A seam only a test uses — it is the only way
 *   to assert that a good model is hashed **once** rather than once per dictation, and a cache
 *   nobody has watched hit is a claim.
 */
class ModelIntegrity(
    private val digestOf: (File) -> String = { sha256(it) },
) {

    /**
     * What the file turned out to be.
     *
     * [UNVERIFIABLE] is not a soft [CORRUPT]: it means this class has nothing to judge against —
     * no pinned digest, or the bytes could not be read — and deleting 190 MB on that basis would
     * be the same defect pointing the other way.
     */
    enum class Verdict { GOOD, CORRUPT, UNVERIFIABLE }

    /**
     * Files this process has already hashed and found good.
     *
     * Keyed by path **plus length plus modification time**, not by path: a corrupt file that is
     * deleted and downloaded again lands at the same path, and a cache keyed on the path alone
     * would answer for the old bytes. Only [Verdict.GOOD] is remembered — a corrupt file is gone
     * by the time the verdict is returned, so its key cannot recur.
     */
    private val known = ConcurrentHashMap.newKeySet<String>()

    /**
     * @return what [store]'s file is, deleting it when it is provably not the pinned model so the
     *   next attempt can offer a clean download instead of failing identically for ever.
     */
    fun verify(store: ModelStore): Verdict {
        val pinned = store.expectedSha256
        if (pinned.isBlank()) return Verdict.UNVERIFIABLE
        val file = store.modelFile()
        if (!file.isFile) return Verdict.UNVERIFIABLE

        val identity = identityOf(file)
        if (identity in known) return Verdict.GOOD

        val actual = runCatching { digestOf(file) }.getOrElse { unreadable ->
            // A file that cannot be read is not a file that is wrong. Saying so costs a failed
            // dictation; guessing costs the download.
            Log2.w(
                "stt.model.digest_unreadable",
                "model" to store.modelName,
                "cause" to unreadable.javaClass.simpleName,
            )
            return Verdict.UNVERIFIABLE
        }

        if (actual.equals(pinned, ignoreCase = true)) {
            known.add(identity)
            return Verdict.GOOD
        }

        // The model NAME, never the path: the store's root is a constructor argument, and a
        // person's own directory is a place their name can be.
        Log2.e("stt.model.corrupt", null, "model" to store.modelName, "bytes" to file.length())
        if (!file.delete()) {
            // Still corrupt — the verdict is about the bytes, not about whether we could tidy up.
            // The download that follows renames over this path anyway.
            Log2.w("stt.model.corrupt_not_removed", "model" to store.modelName)
        }
        return Verdict.CORRUPT
    }

    companion object {
        /**
         * One per process, because the cache is the point: two instances would each hash the
         * same good file once, which is the cost this class exists to pay only when it must.
         */
        val Shared = ModelIntegrity()

        /** Streamed in 64 KB blocks — the file is up to 574 MB and the heap is a headset's. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun identityOf(file: File): String =
            "${file.absolutePath}|${file.length()}|${file.lastModified()}"
    }
}

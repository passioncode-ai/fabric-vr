package ai.passioncode.fabricvr.stt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **`B-173`: four of the five pinned digests had never been exercised against anything.**
 *
 * [WhisperModel] pins a SHA-256 per model, read from Hugging Face's own API on 2026-09-19, and
 * `ModelDownloader` refuses a transfer whose bytes do not hash to it. Only `small` has ever
 * actually been downloaded by this project. A typo in `tiny`, `base`, `medium` or `large-turbo` is
 * therefore **indistinguishable from a correct pin** until a person picks that model — and then it
 * fails at the *end* of a transfer of up to 574 MB, over headset Wi-Fi, reported as
 * *"The downloaded file was corrupt and was removed."* The bytes were fine; the constant was not.
 *
 * ## What this costs, and why it is this shape
 *
 * Downloading all five to hash them is 1.395 GB. This asks two much smaller questions instead:
 *
 * 1. **Does the vendor still publish the digest we pinned?** One request to the tree API — the
 *    same endpoint [WhisperModel]'s own header names as the source of these values — returns
 *    `lfs.oid`, which *is* the file's SHA-256, for every file in the repository at once. About ten
 *    kilobytes for all five. This is the half that catches a typo, because it re-derives the pin
 *    from the primary source rather than from a note in a document.
 * 2. **Is the file actually reachable, at the size we pinned?** One `Range: bytes=0-0` request per
 *    model — **one byte each** — whose `206` and `Content-Range` carry the total length. Five
 *    bytes of payload for the reachability of 1.395 GB.
 *
 * It also exercises something no other test does: that the vendor's CDN honours `Range` at all.
 * `ModelDownloader`'s whole resume path — the `Range` header, the 206-vs-200 check, the digest
 * replayed over what is on disk (`H8`, `A-22`) — is built on that assumption, and every test of it
 * so far has asked a `MockWebServer` the project itself configured to say yes.
 *
 * ## What it does NOT prove, said plainly
 *
 * It compares our constant against the vendor's *published* digest. It does not hash 574 MB, so it
 * cannot see a vendor whose published digest and served bytes disagree. That case is caught where
 * it matters and for free: `ModelDownloader` hashes every byte it writes, on every real download.
 *
 * ## Why it is opt-in
 *
 * `check-all.sh` runs on every push and these are somebody else's servers. Six requests to Hugging
 * Face per push is rude and makes the gate fail when their CDN has a bad afternoon — a red that
 * says nothing about this repository is a red people learn to ignore (`SI-05`). So it **skips**
 * rather than fails unless [OPT_IN] is set, which is the shape `B-173` asked for: the nightly job
 * that already resolves dependencies cold is where this belongs.
 *
 * ```bash
 * FABRICVR_NETWORK_TESTS=1 ./gradlew :feature-stt:testDebugUnitTest \
 *   --tests '*ModelDigestReachabilityTest*'
 * ```
 *
 * A run without it reports **skipped**, which is visible in the test report — not green, which is
 * what a silently-disabled check looks like.
 */
class ModelDigestReachabilityTest {

    /** Only the fields this test reads; the API sends a dozen more per entry. */
    @Serializable
    private data class TreeEntry(
        val path: String = "",
        val size: Long = 0,
        val lfs: Lfs? = null,
    ) {
        @Serializable
        data class Lfs(
            /** Git LFS's object id for a file stored this way **is** its SHA-256. */
            val oid: String = "",
            @SerialName("size") val size: Long = 0,
        )
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The production client, so the hop rule and the vendor allow-list are on this path too. */
    private val client = ModelDownloader.SHARED

    private fun online() {
        assumeTrue(
            "set $OPT_IN=1 to let this test reach Hugging Face (B-173)",
            System.getenv(OPT_IN) == "1",
        )
    }

    /**
     * **The half that catches a typo.** Every pinned digest is compared against the value the
     * vendor publishes today, for all five models, in one request.
     */
    @Test fun `every pinned digest is the one the vendor publishes`() {
        online()

        val body = client.newCall(Request.Builder().url(TREE_URL).build()).execute().use { r ->
            assertEquals("the vendor's tree API answered ${r.code}", 200, r.code)
            r.body?.string()
        }
        assertNotNull("the vendor's tree API returned no body", body)

        val published = json.decodeFromString<List<TreeEntry>>(body!!)
            .filter { it.lfs != null }
            .associateBy { it.path }
        assertTrue(
            "the tree API returned no LFS entries at all — its shape has changed and this test " +
                "would now pass by comparing nothing",
            published.size > 5,
        )

        WhisperModel.entries.forEach { model ->
            val entry = published[model.fileName]
            assertNotNull(
                "${model.fileName} is no longer published at $TREE_URL — the catalogue points at " +
                    "a file that is gone, and a person choosing ${model.key} gets a 404 at the " +
                    "end of a transfer",
                entry,
            )
            assertEquals(
                "the SHA-256 pinned for ${model.key} is not the one the vendor publishes. Either " +
                    "the constant in WhisperModel is a typo — which is B-173 — or the vendor " +
                    "re-published the file. Do not adjust the pin to make a download pass without " +
                    "deciding which.",
                entry!!.lfs!!.oid.lowercase(),
                model.sha256.lowercase(),
            )
            assertEquals(
                "the byte count pinned for ${model.key} disagrees with the vendor's",
                entry.size,
                model.bytes,
            )
        }
    }

    /**
     * **The half that proves the file is there.** One byte per model, and the `Content-Range` the
     * answer carries is the whole file's length — which is also the pin `FileModelStore.isPresent`
     * compares against.
     */
    @Test fun `every model answers a ranged request with its pinned length`() {
        online()

        WhisperModel.entries.forEach { model ->
            val url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${model.fileName}"
            val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()

            client.newCall(request).execute().use { r ->
                assertEquals(
                    "the vendor did not honour a range request for ${model.key} (got ${r.code}). " +
                        "ModelDownloader's whole resume path assumes it does, and every other " +
                        "test of that assumption asks a server this project configured.",
                    206,
                    r.code,
                )
                val range = r.header("Content-Range")
                assertNotNull("a 206 for ${model.key} carried no Content-Range", range)
                assertEquals(
                    "the length the vendor serves for ${model.key} is not the one pinned in " +
                        "WhisperModel, so the exact-size check in FileModelStore.isPresent would " +
                        "call a complete download incomplete",
                    model.bytes.toString(),
                    range!!.substringAfter('/'),
                )
                assertEquals("a one-byte range returned ${r.body?.contentLength()} bytes", 1L, r.body?.contentLength())
            }
        }
    }

    private companion object {
        const val OPT_IN = "FABRICVR_NETWORK_TESTS"

        /** The endpoint [WhisperModel]'s own header names as the source of these values. */
        const val TREE_URL =
            "https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/main?recursive=1"
    }
}

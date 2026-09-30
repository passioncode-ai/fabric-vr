package ai.passioncode.fabricvr.stt

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `B-191`, at the class that answers the question. The engine's side — which error a person
 * actually gets — is `WhisperEngineModelIntegrityTest`.
 */
class ModelIntegrityTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun model(bytes: ByteArray): File {
        if (!::root.isInitialized) root = temp.newFolder("models")
        return File(root, "ggml-small-q5_1.bin").also { it.writeBytes(bytes) }
    }

    private fun store(file: File, sha: String) = object : ModelStore {
        override val modelName = "ggml-small-q5_1.bin"
        override val expectedBytes = file.length()
        override val expectedSha256 = sha
        override val downloadUrl = ""
        override fun modelFile() = file
        override fun isPresent() = file.isFile && file.length() == expectedBytes
    }

    /**
     * The row itself: the size is right — so `isPresent` has always said yes — and the bytes are
     * not. The file goes, because the next attempt must be able to offer a clean download rather
     * than fail the same way for ever.
     */
    @Test fun `a file of the right size with wrong bytes is detected and removed`() {
        val file = model(ByteArray(4_096))
        val pinned = sha256(ByteArray(4_096) { 0xFF.toByte() })
        val store = store(file, pinned)
        assertTrue("the premise of this row: the byte count still passes", store.isPresent())

        assertEquals(ModelIntegrity.Verdict.CORRUPT, ModelIntegrity().verify(store))
        assertFalse("the corrupt file survived its own verdict", file.exists())
    }

    /**
     * **A good file is hashed once, whatever asks.** Without the cache the hash would be paid on
     * every loader failure — seconds over up to 574 MB, in front of a person who is holding the
     * trigger — which is the cost this whole design exists to avoid.
     */
    @Test fun `a good file is not re-hashed twice in one process`() {
        val bytes = ByteArray(4_096) { it.toByte() }
        val file = model(bytes)
        val hashes = AtomicInteger(0)
        val integrity = ModelIntegrity { f -> hashes.incrementAndGet(); sha256(f.readBytes()) }
        val store = store(file, sha256(bytes))

        assertEquals(ModelIntegrity.Verdict.GOOD, integrity.verify(store))
        assertEquals(ModelIntegrity.Verdict.GOOD, integrity.verify(store))
        assertEquals(ModelIntegrity.Verdict.GOOD, integrity.verify(store))

        assertEquals("a good model was hashed again", 1, hashes.get())
    }

    /**
     * The other side of that cache, and the reason its key is not the path. A corrupt file is
     * deleted and downloaded again to the same name; a cache keyed on the path alone would go on
     * answering for bytes that are no longer there.
     */
    @Test fun `a file replaced at the same path is hashed again`() {
        val first = ByteArray(4_096) { it.toByte() }
        val file = model(first)
        val hashes = AtomicInteger(0)
        val integrity = ModelIntegrity { f -> hashes.incrementAndGet(); sha256(f.readBytes()) }

        assertEquals(ModelIntegrity.Verdict.GOOD, integrity.verify(store(file, sha256(first))))

        val second = ByteArray(8_192) { (it + 1).toByte() }
        file.writeBytes(second)
        assertEquals(ModelIntegrity.Verdict.GOOD, integrity.verify(store(file, sha256(second))))

        assertEquals("a re-downloaded model answered from the previous file's verdict", 2, hashes.get())
    }

    /**
     * **`UNVERIFIABLE` is not a soft `CORRUPT`.** A store with nothing pinned — the shape every
     * test double in this module uses, and the shape `WhisperModel` had before `DEC-0016` —
     * gives this class nothing to convict the file with, and deleting a 574 MB download on that
     * basis is the same defect pointing the other way.
     */
    @Test fun `a store with no pinned digest cannot convict its file`() {
        val file = model(ByteArray(4_096))

        assertEquals(ModelIntegrity.Verdict.UNVERIFIABLE, ModelIntegrity().verify(store(file, "")))
        assertTrue("a file nothing could judge was deleted anyway", file.exists())
    }

    /** Nor can a file that is not there: absent is `ModelStore.isPresent`'s word, not this one's. */
    @Test fun `an absent file is unverifiable rather than corrupt`() {
        val file = File(temp.newFolder("empty"), "ggml-small-q5_1.bin")

        assertEquals(
            ModelIntegrity.Verdict.UNVERIFIABLE,
            ModelIntegrity().verify(store(file, "0".repeat(64))),
        )
    }

    /**
     * Bytes that cannot be read are not bytes that are wrong. Saying so costs one failed
     * dictation; guessing costs the download.
     */
    @Test fun `a file that cannot be read is unverifiable rather than corrupt`() {
        val file = model(ByteArray(4_096))
        val integrity = ModelIntegrity { throw java.io.IOException("EACCES") }

        assertEquals(ModelIntegrity.Verdict.UNVERIFIABLE, integrity.verify(store(file, "0".repeat(64))))
        assertTrue("an unreadable file was deleted on a guess", file.exists())
    }

    /**
     * The default hasher streams rather than loading the file, because the real one is up to
     * 574 MB against a headset's heap. Asserted against a digest computed the other way.
     */
    @Test fun `the default digest streams the file and matches a one-shot hash`() {
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        val file = model(bytes)

        assertEquals(sha256(bytes), ModelIntegrity.sha256(file))
    }
}

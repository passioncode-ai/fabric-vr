package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `B-191`. **A model file of the right size and the wrong bytes was never detected.**
 *
 * `ModelStore.isPresent` compares the byte count and nothing re-verifies the SHA after the
 * download, so a file corrupted on disk makes `whisper_init_from_file_with_params` return null,
 * `initContext` return 0, and every dictation fail identically for ever. Nothing evicted the file,
 * so the only way out was Settings → *Remove speech model* — a step the person has no reason to
 * suspect, under a message that said their dictation failed.
 *
 * The digest is checked **on the first loader failure**, never on a good path: hashing 190–574 MB
 * costs seconds, and the person is holding the trigger.
 */
class WhisperEngineModelIntegrityTest {

    @get:Rule val temp = TemporaryFolder()

    private companion object {
        val TEST_BOUND = 10.seconds
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A store over a real file, with whatever digest the case wants pinned to it. */
    private fun store(file: File, sha: String) = object : ModelStore {
        override val modelName = "ggml-small-q5_1.bin"
        override val expectedBytes = file.length()
        override val expectedSha256 = sha
        override val downloadUrl = ""
        override fun modelFile() = file
        override fun isPresent() = file.isFile && file.length() == expectedBytes
    }

    /** A loader that refuses the file, which is all whisper.cpp tells us when a model is wrong. */
    private class RefusingLoader : WhisperNativeCalls {
        val initCalls = AtomicInteger(0)
        override fun ensureLoaded() = true
        override fun initContext(modelPath: String): Long { initCalls.incrementAndGet(); return 0L }
        override fun freeContext(ptr: Long) = Unit
        override fun beginRun(ptr: Long) = 1L
        override fun transcribe(
            ptr: Long,
            run: Long,
            audio: FloatArray,
            threads: Int,
            language: String,
            beamSize: Int,
        ): ByteArray? = null
        override fun detectedLanguage(ptr: Long) = ""
        override fun cancel(ptr: Long, run: Long) = Unit
        override fun progress(ptr: Long) = 0
        override fun wasCancelled(ptr: Long, run: Long) = false
    }

    private fun modelFile(bytes: ByteArray): File =
        File(temp.newFolder("models"), "ggml-small-q5_1.bin").also { it.writeBytes(bytes) }

    /**
     * The row itself. The file is the pinned size and the wrong bytes, so it is removed and the
     * person is told the model is missing — which is now true, and which offers *Download*.
     */
    @Test fun `a file of the right size with wrong bytes is removed and reported as missing`() =
        runTest(timeout = TEST_BOUND) {
            val file = modelFile(ByteArray(4_096))
            val pinned = sha256(ByteArray(4_096) { 0xFF.toByte() })
            val engine = WhisperEngine(store(file, pinned), native = RefusingLoader())
            try {
                val result = withContext(Dispatchers.IO) {
                    engine.transcribe(ShortArray(1_600), 16_000, "ru")
                }

                val error = (result.exceptionOrNull() as SttException).error
                assertTrue("a corrupt model reached the person as $error", error is AppError.ModelMissing)
                assertFalse("the corrupt file was left for the next dictation to fail on", file.exists())
            } finally {
                engine.close()
            }
        }

    /**
     * The other direction, and the one a careless fix breaks: a loader failure on a file whose
     * digest is **good** is the engine's failure, not the file's. Calling it corruption would
     * delete a correct 190 MB download and send the person to fetch it again for nothing.
     */
    @Test fun `a loader failure on a good file is an engine failure, not corruption`() =
        runTest(timeout = TEST_BOUND) {
            val bytes = ByteArray(4_096) { it.toByte() }
            val file = modelFile(bytes)
            val engine = WhisperEngine(store(file, sha256(bytes)), native = RefusingLoader())
            try {
                val result = withContext(Dispatchers.IO) {
                    engine.transcribe(ShortArray(1_600), 16_000, "ru")
                }

                val error = (result.exceptionOrNull() as SttException).error
                assertTrue("a working file was blamed: $error", error is AppError.SttFailed)
                assertTrue("a file that hashes correctly was deleted", file.exists())
            } finally {
                engine.close()
            }
        }

    /**
     * **A loader that succeeds must never cost the person a hash of half a gigabyte.** This is
     * the whole justification for verifying on failure rather than at first use, so it is
     * asserted rather than described.
     */
    @Test fun `a model that loads is never hashed`() = runTest(timeout = TEST_BOUND) {
        val bytes = ByteArray(4_096) { it.toByte() }
        val file = modelFile(bytes)
        val hashes = AtomicInteger(0)
        val native = object : WhisperNativeCalls by RefusingLoader() {
            override fun initContext(modelPath: String) = 7L
            override fun transcribe(
                ptr: Long,
                run: Long,
                audio: FloatArray,
                threads: Int,
                language: String,
                beamSize: Int,
            ): ByteArray = "готово".toByteArray()
        }
        val integrity = ModelIntegrity { f -> hashes.incrementAndGet(); sha256(f.readBytes()) }
        val engine = WhisperEngine(store(file, sha256(bytes)), native = native, integrity = integrity)
        try {
            val result = withContext(Dispatchers.IO) {
                engine.transcribe(ShortArray(1_600), 16_000, "ru")
            }

            assertEquals("готово", result.getOrNull()?.text)
            assertEquals("a successful load paid for a digest nobody needed", 0, hashes.get())
        } finally {
            engine.close()
        }
    }

    /**
     * The failing path is hashed **once per process**, not once per dictation: the engine holds
     * no context after a refusal, so every later attempt calls `initContext` again and would
     * hash again without the cache in [ModelIntegrity].
     */
    @Test fun `a repeated loader failure on a good file hashes it once`() = runTest(timeout = TEST_BOUND) {
        val bytes = ByteArray(4_096) { it.toByte() }
        val file = modelFile(bytes)
        val hashes = AtomicInteger(0)
        val loader = RefusingLoader()
        val integrity = ModelIntegrity { f -> hashes.incrementAndGet(); sha256(f.readBytes()) }
        val engine = WhisperEngine(store(file, sha256(bytes)), native = loader, integrity = integrity)
        try {
            repeat(3) {
                withContext(Dispatchers.IO) { engine.transcribe(ShortArray(1_600), 16_000, "ru") }
            }

            assertEquals("the loader was not re-asked, so this proves nothing", 3, loader.initCalls.get())
            assertEquals("every failed dictation paid for the digest again", 1, hashes.get())
        } finally {
            engine.close()
        }
    }
}

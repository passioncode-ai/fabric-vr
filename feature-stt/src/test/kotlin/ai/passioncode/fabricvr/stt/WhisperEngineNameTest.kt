package ai.passioncode.fabricvr.stt

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `A-14`: the engine name reaches `Transcript.engine`, the row badge and the vault's front matter,
 * and it was the constant `"whisper-small-q5_1"` whatever model was loaded. The one feature whose
 * purpose is comparing models recorded the same name for all five of them.
 *
 * No native library is touched: the name is derived in the constructor, and `close()` on an engine
 * that never initialised a context never calls into whisper.cpp.
 */
class WhisperEngineNameTest {

    private fun store(model: WhisperModel): ModelStore = object : ModelStore {
        override val modelName = model.fileName
        override val expectedBytes = model.bytes
        override val expectedSha256 = model.sha256
        override val downloadUrl = ""
        override fun modelFile() = File(model.fileName)
        override fun isPresent() = false
    }

    @Test fun `the engine names the model it loaded`() = runTest {
        val names = WhisperModel.entries.map { model ->
            val engine = WhisperEngine(store(model))
            try { engine.name } finally { engine.close() }
        }

        assertEquals(
            listOf(
                "whisper-tiny-q5_1",
                "whisper-base-q5_1",
                "whisper-small-q5_1",
                "whisper-medium-q5_0",
                "whisper-large-v3-turbo-q5_0",
            ),
            names,
        )
        assertEquals("five models must not answer to fewer than five names", 5, names.toSet().size)
    }

    /**
     * The old constant, preserved exactly for the model it was right about. Notes written before
     * this change carry `whisper-small-q5_1` in the database and in their vault front matter; a
     * derivation that produced anything else would make every one of them look like a different
     * engine's work.
     */
    @Test fun `the small model keeps the name already written into people's notes`() = runTest {
        val engine = WhisperEngine(store(WhisperModel.SMALL))
        try {
            assertEquals("whisper-small-q5_1", engine.name)
        } finally {
            engine.close()
        }
    }

    /** `I-25`: a second free of the same address is a use-after-free. Here it must simply return. */
    @Test fun `closing twice is a no-op`() = runTest {
        val engine = WhisperEngine(store(WhisperModel.TINY))
        engine.close()
        engine.close()
    }
}

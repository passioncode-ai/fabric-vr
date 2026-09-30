package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The on-device half of the speech engine. It exists because nothing in the JVM suite can reach the
 * native library: the JNI bridge, the model file and the CPU are all only real on the headset.
 *
 * The model is pushed by `scripts/push-model-for-tests.sh` into a world-readable directory rather
 * than downloaded, so the test does not depend on the network. Without it the transcription cases
 * are skipped **loudly** — an `assumeTrue` prints as "assumption failed", which is a different
 * thing from a pass.
 */
class WhisperEngineTest {

    private val modelDir = File("/data/local/tmp/fabricvr-test-models")

    private fun store(dir: File = modelDir) = FileModelStore(dir)

    private fun fixture(name: String): ShortArray {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("fixtures/$name").use { it.readBytes() }
        return WavWriter.readPcm(bytes)
    }

    @Test
    fun transcribes_the_jfk_fixture_in_english() = runBlocking {
        val store = store()
        assumeTrue("push the model first: scripts/push-model-for-tests.sh", store.isPresent())
        val engine = WhisperEngine(store)

        val started = System.currentTimeMillis()
        val transcript = engine.transcribe(fixture("jfk.wav"), langHint = "auto").getOrThrow()
        val wall = System.currentTimeMillis() - started

        println("FabricVR-bench jfk.wav: ${wall}ms wall, ${transcript.durationMs}ms engine, lang=${transcript.language}")
        assertTrue(
            "unexpected transcript: ${transcript.text}",
            transcript.text.lowercase().contains("ask not what your country"),
        )
        assertEquals("en", transcript.language)
        engine.close()
    }

    @Test
    fun transcribes_the_russian_fixture_and_detects_ru() = runBlocking {
        val store = store()
        assumeTrue("push the model first: scripts/push-model-for-tests.sh", store.isPresent())
        val engine = WhisperEngine(store)

        val started = System.currentTimeMillis()
        val transcript = engine.transcribe(fixture("ru_short.wav"), langHint = "auto").getOrThrow()
        val wall = System.currentTimeMillis() - started

        println("FabricVR-bench ru_short.wav: ${wall}ms wall, ${transcript.durationMs}ms engine, lang=${transcript.language}")
        assertEquals("ru", transcript.language)
        assertTrue(
            "unexpected transcript: ${transcript.text}",
            transcript.text.lowercase().contains("тест"),
        )
        engine.close()
    }

    @Test
    fun a_pinned_language_is_honoured() = runBlocking {
        val store = store()
        assumeTrue("push the model first: scripts/push-model-for-tests.sh", store.isPresent())
        val engine = WhisperEngine(store)

        val transcript = engine.transcribe(fixture("ru_short.wav"), langHint = "ru").getOrThrow()

        assertEquals("ru", transcript.language)
        engine.close()
    }

    @Test
    fun reports_the_model_as_missing_when_it_is_absent() = runBlocking {
        val empty = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "no-models-here",
        )
        val engine = WhisperEngine(store(empty))

        val result = engine.transcribe(ShortArray(16_000))

        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull() as SttException).error is AppError.ModelMissing)
        engine.close()
    }
}

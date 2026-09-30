package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.Log2
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a dictation actually costs on this headset, separated into the two numbers that behave
 * differently: loading the 190 MB model, which happens once per process, and transcribing, which
 * happens every time. A single figure that folds them together makes the first dictation look like
 * every dictation, and it is the second one the interface has to be designed around.
 *
 * It asserts nothing about speed — a threshold measured on one device becomes a false failure on the
 * next. It prints, and the printed numbers go into the verification ledger.
 */
@RunWith(AndroidJUnit4::class)
class WhisperBenchmarkTest {

    private val modelDir = File("/data/local/tmp/fabricvr-test-models")

    private fun fixture(name: String): ShortArray {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("fixtures/$name").use { it.readBytes() }
        return WavWriter.readPcm(bytes)
    }

    private fun bench(label: String, threads: Int, beamSize: Int = WhisperEngine.DEFAULT_BEAM_SIZE) = runBlocking {
        val store = FileModelStore(modelDir)
        assumeTrue("push the model first: scripts/push-model-for-tests.sh", store.isPresent())
        val engine = WhisperEngine(store, threads = threads, beamSize = beamSize)
        val audio = fixture("jfk.wav")
        val seconds = audio.size / AudioRecorder.SAMPLE_RATE.toDouble()

        val coldStart = System.currentTimeMillis()
        engine.transcribe(audio, langHint = "en").getOrThrow()
        val cold = System.currentTimeMillis() - coldStart

        val warmStart = System.currentTimeMillis()
        engine.transcribe(audio, langHint = "en").getOrThrow()
        val warm = System.currentTimeMillis() - warmStart

        // Log, not println: an instrumentation's stdout reaches neither logcat nor the result XML
        // on this setup, so a printed measurement is a measurement nobody can read.
        Log2.i(
            "bench",
            "case" to label,
            "threads" to threads,
            "beam" to beamSize,
            "audioSec" to "%.1f".format(seconds),
            "coldMs" to cold,
            "warmMs" to warm,
            "loadMs" to (cold - warm),
            "realtime" to "%.2fx".format(warm / 1000.0 / seconds),
        )
        engine.close()
    }

    @Test fun beamSearch() = bench("beam-5", WhisperEngine.defaultThreads(), beamSize = 5)

    @Test fun greedy() = bench("greedy", WhisperEngine.defaultThreads(), beamSize = 1)
}

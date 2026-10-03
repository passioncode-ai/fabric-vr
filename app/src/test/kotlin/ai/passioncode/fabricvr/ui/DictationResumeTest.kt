package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.TranscriptionJournal
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.resumeAwaitingTranscriptions
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.sweepScratchAudio
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lifecycle contract LC-03, audit F2 (2026-10-03), at the tier where it happens: **the process
 * dies while a dictation is being decoded, and the next launch still produces the note.**
 *
 * `TranscriptionJournalTest` proves the journal and the resume in isolation; this proves that
 * `VoiceViewModel` actually writes the journal before the decode, ends it on every in-process
 * outcome, and that a kill — modelled as new outbox and journal instances over the same files,
 * which is all a killed process leaves — is recovered end to end: spared by the sweep, decoded on
 * launch, handed to the outbox with its original recording.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DictationResumeTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    private lateinit var root: File
    private val audioDir get() = File(root, "audio")
    private val journalDir get() = File(root, "outbox/awaiting")
    private val outboxFile get() = File(root, "outbox/dictation.tsv")

    @Before fun setUp() {
        root = File.createTempFile("resume", "").apply { delete(); mkdirs() }
        audioDir.mkdirs()
    }

    @After fun tearDown() = root.deleteRecursively().let { }

    private fun store() = object : ModelStore {
        override val modelName = "ggml-test.bin"
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = ""
        override fun modelFile() = File(root, modelName)
        override fun isPresent() = true
    }

    private fun viewModel(
        journal: TranscriptionJournal,
        decode: suspend () -> Result<Transcript>,
    ) = VoiceViewModel(
        recorder = TestRecorder(samples = 16_000, chunks = 1),
        modelStore = { store() },
        transcribeWith = { _, _, _ -> decode() },
        language = { "fr" },
        remoteReady = { false },
        downloads = FakeDownloads(emptyList()),
        chosenModel = { WhisperModel.DEFAULT },
        currentProvider = { SttProvider.LOCAL },
        audioDir = { audioDir },
        outbox = DictationOutbox(outboxFile),
        journal = journal,
        appScope = TestScope(dispatcher),
        io = dispatcher,
    )

    private fun transcript(text: String = "the words") = Transcript(text, "fr", SttSource.LOCAL, "fake", 0)

    private suspend fun kotlinx.coroutines.test.TestScope.dictate(vm: VoiceViewModel) {
        vm.start()
        advanceUntilIdle()
        vm.stopAndTranscribe()
        advanceUntilIdle()
    }

    @Test fun `the recording is journalled before its decode starts`() = runTest(dispatcher) {
        val decodeStarted = CompletableDeferred<Unit>()
        var journalledAtDecode = emptyList<String>()
        val journal = TranscriptionJournal(journalDir, owner = "live")
        val vm = viewModel(journal) {
            // What a kill at this instant would leave on disk.
            journalledAtDecode = TranscriptionJournal(journalDir, owner = "next").orphans().map { it.audioPath }
            decodeStarted.complete(Unit)
            Result.success(transcript())
        }

        dictate(vm)

        assertTrue(decodeStarted.isCompleted)
        val wav = audioDir.listFiles().orEmpty().single()
        assertEquals("the decode began with nothing on disk saying a recording awaited it", listOf(wav.absolutePath), journalledAtDecode)
    }

    @Test fun `every in-process outcome ends the journal entry`() = runTest(dispatcher) {
        val outcomes = listOf<suspend () -> Result<Transcript>>(
            { Result.success(transcript()) },
            { Result.success(transcript(text = " ")) },
            { Result.failure(IllegalStateException("engine")) },
        )
        outcomes.forEach { decode ->
            val journal = TranscriptionJournal(journalDir, owner = "live")
            dictate(viewModel(journal, decode))
            assertEquals(
                "an outcome the person saw left an entry for the next launch to decode again",
                emptyList<String>(),
                TranscriptionJournal(journalDir, owner = "next").orphans().map { it.audioPath },
            )
        }
    }

    /** *Stop transcribing* is the person's decision; the next launch must not overrule it. */
    @Test fun `stopping the decode ends the journal entry`() = runTest(dispatcher) {
        val journal = TranscriptionJournal(journalDir, owner = "live")
        val vm = viewModel(journal) { CompletableDeferred<Result<Transcript>>().await() }

        dictate(vm)
        vm.stopTranscription()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), TranscriptionJournal(journalDir, owner = "next").orphans().map { it.audioPath })
    }

    @Test fun `a process killed mid-decode leaves a recording the next launch turns into words`() = runTest(dispatcher) {
        // The first process: dictates, and its decode never finishes — the freezer, then the
        // low-memory killer. Nothing after this point runs in it: no `finally`, no `onCleared`.
        val vm = viewModel(TranscriptionJournal(journalDir, owner = "killed")) {
            CompletableDeferred<Result<Transcript>>().await()
        }
        dictate(vm)
        assertTrue(vm.state.value is VoiceState.Transcribing)
        val wav = audioDir.listFiles().orEmpty().single()
        // A day and a half later, past the sweep's threshold.
        check(wav.setLastModified(System.currentTimeMillis() - 36L * 60 * 60 * 1000))

        // The next launch, in `Graph.init`'s order: outbox restored, sweep, resume.
        val outbox = DictationOutbox(outboxFile).apply { restore() }
        val journal = TranscriptionJournal(journalDir, owner = "next")
        sweepScratchAudio(audioDir, spare = outbox.waitingAudio() + journal.audioPaths())
        assertTrue("the sweep deleted the only recording of a dictation still awaiting its decode", wav.isFile)

        var language: String? = null
        resumeAwaitingTranscriptions(
            journal = journal,
            readPcm = { ShortArray(16_000) },
            transcribe = { _, lang -> language = lang; Result.success(transcript()) },
            deliver = { t, path -> outbox.offer(t, path) },
            keepRecordingOnly = { error("the decode succeeded; nothing should fall back to audio only") },
        )

        val pending = outbox.pending.value
        assertEquals("the resumed words never reached the outbox", "the words", pending?.transcript?.text)
        assertEquals("the words lost their recording", wav.absolutePath, pending?.audioPath)
        assertEquals("the resume ignored the language chosen at the time", "fr", language)
        assertEquals(emptyList<String>(), TranscriptionJournal(journalDir, owner = "later").orphans().map { it.audioPath })
        assertEquals(
            "the transcript is not durable: a second kill now would lose it",
            "the words",
            DictationOutbox(outboxFile).restore()?.transcript?.text,
        )
    }
}

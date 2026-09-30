package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.Cue
import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.FeedbackCues
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.vault.AudioUsage
import ai.passioncode.fabricvr.vault.Vault
import ai.passioncode.fabricvr.vault.FileVault
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `REQ-062`, audit `M22` — the four moments, and which cue each of them fires.
 *
 * `FeedbackCuesTest` covers the seam; this covers the **callers**, because the defect `M22`
 * names is not that the seam was wrong but that nothing called one. A cap that fires silently is
 * the worst of the four: the person is still speaking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DictationCuesTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    private lateinit var temp: File

    /** What was played, in order. The whole assertion of this suite. */
    private class Heard : FeedbackCues {
        val cues = mutableListOf<Cue>()
        override fun play(cue: Cue) { cues += cue }
        override fun attachHaptics(channel: ai.passioncode.fabricvr.CueChannel?) = Unit
        override fun detachHaptics(channel: ai.passioncode.fabricvr.CueChannel) = Unit
    }

    @Before fun setUp() {
        temp = File.createTempFile("cues", "").apply { delete(); mkdirs() }
    }

    @After fun tearDown() = temp.deleteRecursively().let { }

    private fun store() = object : ModelStore {
        override val modelName = "ggml-test.bin"
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = ""
        override fun modelFile() = File(temp, modelName)
        override fun isPresent() = true
    }

    private fun voice(heard: Heard, chunks: Int, maxSamples: Int) = VoiceViewModel(
        recorder = TestRecorder(samples = 16_000, chunks = chunks),
        modelStore = { store() },
        transcribeWith = { _, _, _ ->
            Result.success(Transcript("spoken", "en", SttSource.LOCAL, "fake", 0))
        },
        language = { "auto" },
        remoteReady = { false },
        downloads = FakeDownloads(emptyList()),
        chosenModel = { WhisperModel.DEFAULT },
        currentProvider = { ai.passioncode.fabricvr.stt.SttProvider.LOCAL },
        audioDir = { temp },
        outbox = DictationOutbox(),
        appScope = TestScope(dispatcher),
        io = dispatcher,
        cues = heard,
        maxSamples = maxSamples,
    )

    @Test fun `a recording announces its start and its stop, in that order`() = runTest(dispatcher) {
        val heard = Heard()
        val vm = voice(heard, chunks = 1, maxSamples = 16_000 * 60)

        vm.start()
        advanceUntilIdle()
        vm.stopAndTranscribe()
        advanceUntilIdle()

        assertEquals(listOf(Cue.RECORD_START, Cue.RECORD_STOP), heard.cues)
    }

    /**
     * **The cap is its own cue.** `DEC-0032` stops a dictation at ten minutes and transcribes what
     * it has — the one moment where the person is still speaking and the app has stopped
     * listening. A cue that cannot be told apart from a stop they asked for would say the wrong
     * thing.
     */
    @Test fun `reaching the cap announces itself as a cap, not as a stop`() = runTest(dispatcher) {
        val heard = Heard()
        val vm = voice(heard, chunks = 2, maxSamples = 16_000)

        vm.start()
        advanceUntilIdle()

        assertEquals(listOf(Cue.RECORD_START, Cue.AUTO_STOP), heard.cues)
    }

    @Test fun `a committed dictation announces that it was saved`() = runTest(dispatcher) {
        val heard = Heard()
        val repo = CollectingRepo()
        val vm = NotesViewModel(
            repository = repo,
            today = { LocalDate.of(2026, 9, 21) },
            vault = FakeVaultForCues(temp),
            dayTicks = emptyFlow(),
            appScope = TestScope(dispatcher),
            mirrorFailures = MutableStateFlow(emptyMap()),
            retryMirror = {},
            outbox = DictationOutbox(),
            awaitReconcile = {},
            cues = heard,
        )
        advanceUntilIdle()

        vm.commitDictation(Transcript("spoken", "en", SttSource.LOCAL, "fake", 0), audioPath = null)
        advanceUntilIdle()

        assertEquals(listOf(Cue.SAVED), heard.cues)
    }

    /** A write that failed is not a save, and must not sound like one. */
    @Test fun `a dictation whose row would not write announces nothing`() = runTest(dispatcher) {
        val heard = Heard()
        val repo = CollectingRepo(failWrites = true)
        val vm = NotesViewModel(
            repository = repo,
            today = { LocalDate.of(2026, 9, 21) },
            vault = FakeVaultForCues(temp),
            dayTicks = emptyFlow(),
            appScope = TestScope(dispatcher),
            mirrorFailures = MutableStateFlow(emptyMap()),
            retryMirror = {},
            outbox = DictationOutbox(),
            awaitReconcile = {},
            cues = heard,
        )
        advanceUntilIdle()

        vm.commitDictation(Transcript("spoken", "en", SttSource.LOCAL, "fake", 0), audioPath = null)
        advanceUntilIdle()

        assertEquals(emptyList<Cue>(), heard.cues)
    }

    private class CollectingRepo(private val failWrites: Boolean = false) : NotesRepository {
        val saved = mutableListOf<Note>()
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = null
        override suspend fun upsert(note: Note): Result<Note> =
            if (failWrites) Result.failure(IllegalStateException("no"))
            else { saved += note; Result.success(note) }
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> =
            Result.success(NotesRepository.newNote())
        override suspend fun count(): Int = 0
        override suspend fun recent(limit: Int): List<Note> = emptyList()
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    private class FakeVaultForCues(root: File) : Vault by FileVault(root, io = Dispatchers.Unconfined) {
        override suspend fun audioUsage(): Result<AudioUsage> = Result.success(AudioUsage(0, 0L))
        override suspend fun sweepAudio(cutoff: Long): Result<AudioUsage> =
            Result.success(AudioUsage(0, 0L))
    }
}

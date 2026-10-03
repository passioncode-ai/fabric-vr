package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.AudioRecorder
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.Recorder
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.vault.AudioUsage
import ai.passioncode.fabricvr.vault.Vault
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * **`DEC-0012`'s promise, asserted where the commit happens** (`B-212`).
 *
 * The promise is one sentence — *"the text goes to the clipboard as the transcript commits"* — and
 * the 2026-09-22 audit found that since `DEC-0068` nothing kept it. The copy lived in
 * `TodayScreen`'s `LaunchedEffect` on a `derivedStateOf` key, so it ran only if a **recomposition
 * frame** happened to observe `VoiceState.Ready`; and `VoiceViewModel.transcribe` offers the
 * transcript to the outbox *before* setting `Ready`, `NotesViewModel.drainOutbox` claims it on the
 * next Main dispatch, and the collector in `VoiceViewModel.init` then puts the state back to
 * `Idle`. Both writes can land inside one frame, and a frame that never sees `Ready` never copies.
 *
 * `there and back again` below is that finding, stated as an assertion rather than as prose: it is
 * green before the fix and after it, because it is a fact about the state machine, not about the
 * clipboard.
 *
 * **View-model tier, with a test dispatcher** (`SI-05`, and the block of prose in
 * `TodayScreenShellTest` that removed the two Compose cases this file replaces): a Robolectric
 * Compose rule cannot wait for a coroutine, and the two deleted tests flaked for exactly that
 * reason. What is asserted here is what the coroutine did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardPromiseTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    /** Stands in for `Graph.scope` — the same virtual clock, and nothing a host can cancel. */
    private val appScope = CoroutineScope(dispatcher)

    private val outbox = DictationOutbox()

    private val root: File =
        File(System.getProperty("java.io.tmpdir"), "clipboard-${System.nanoTime()}").apply { mkdirs() }

    /** What reached the clipboard, in order. A list rather than a flag: a double copy is a defect too. */
    private val clipboard = mutableListOf<String>()

    @After fun tearDown() {
        appScope.cancel()
        root.deleteRecursively()
    }

    // ---- fakes -------------------------------------------------------------------------

    private class FakeRepo : NotesRepository {
        val saved = mutableListOf<Note>()
        var failUpsert = false
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = saved.lastOrNull()
        override suspend fun upsert(note: Note): Result<Note> {
            if (failUpsert) return Result.failure(IllegalStateException("disk full"))
            saved.add(note); return Result.success(note)
        }
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> =
            Result.success(NotesRepository.newNote())
        override suspend fun count(): Int = 0
        override suspend fun recent(limit: Int): List<Note> = emptyList()
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    private class FakeVault(override val root: File) : Vault {
        override suspend fun audioUsage(): Result<AudioUsage> = Result.success(AudioUsage(0, 0L))
        override suspend fun removeAudio(note: Note): Result<Unit> = Result.success(Unit)
        override suspend fun sweepAudio(cutoff: Long): Result<AudioUsage> = Result.success(AudioUsage(0, 0L))
        override fun pathFor(note: Note): File = File(root, "notes/${note.id}.md")
        override fun audioPathFor(note: Note): File = File(root, "notes/${note.id}.wav")
        override suspend fun write(note: Note): Result<File> = Result.success(pathFor(note))
        override suspend fun remove(id: String, createdAt: Long): Result<Unit> = Result.success(Unit)
        override suspend fun restore(id: String, createdAt: Long): Result<Unit> = Result.success(Unit)
        override fun trashedFiles(id: String): List<File> = emptyList()
        override suspend fun purgeTrash(olderThanMillis: Long): Result<Int> = Result.success(0)
        override suspend fun adoptAudio(note: Note, source: File): Result<File> = runCatching {
            val target = audioPathFor(note)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            source.delete()
            target
        }
    }

    private class PresentStore(private val file: File) : ModelStore {
        override val modelName = "ggml-test.bin"
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = "https://example.invalid/$modelName"
        override fun modelFile() = file
        override fun isPresent() = true
    }

    /** One chunk and stop — the real loop fills the heap, see `TestRecorder`. */
    private class OneChunkRecorder : Recorder {
        override fun hasPermission(): Boolean = true
        override fun record(onSamples: (ShortArray, Int) -> Unit): Flow<AudioRecorder.Level> = flow {
            val chunk = ShortArray(16_000) { 1 }
            onSamples(chunk, chunk.size)
            emit(AudioRecorder.Level(rms = 0.5f, samples = chunk.size))
        }
    }

    // ---- construction ------------------------------------------------------------------

    private fun voice(
        text: String = "позвонить в понедельник",
        source: SttSource = SttSource.LOCAL,
    ) = VoiceViewModel(
        recorder = OneChunkRecorder(),
        modelStore = { PresentStore(File(root, "model.bin").apply { writeBytes(ByteArray(8)) }) },
        transcribeWith = { _, _, _ ->
            Result.success(Transcript(text, "ru", source, "whisper-base", 900))
        },
        language = { "auto" },
        remoteReady = { false },
        downloads = FakeDownloads(emptyList()),
        chosenModel = { WhisperModel.DEFAULT },
        currentProvider = { SttProvider.LOCAL },
        audioDir = { File(root, "audio").apply { mkdirs() } },
        outbox = outbox,
        journal = ai.passioncode.fabricvr.TranscriptionJournal(null),
        appScope = appScope,
        io = dispatcher,
    )

    private fun notes(repo: NotesRepository, wantsCopy: Boolean = true) = NotesViewModel(
        repository = repo,
        today = { LocalDate.of(2026, 9, 22) },
        vault = FakeVault(root),
        dayTicks = emptyFlow(),
        appScope = appScope,
        mirrorFailures = MutableStateFlow(emptyMap<String, AppError>()),
        retryMirror = {},
        outbox = outbox,
        awaitReconcile = {},
        copyTranscriptSetting = { wantsCopy },
        copyToClipboard = { text -> clipboard += text },
    )

    // ---- the finding ---------------------------------------------------------------------

    /**
     * **`Ready` is a state the screen is not guaranteed to see.**
     *
     * This is the whole of `B-212` in one assertion, and it passes both before and after the fix —
     * it is the reason the copy had to move, not the copy itself. With a `NotesViewModel` draining,
     * the words are offered, claimed and the state reset with no frame in between, so a
     * `LaunchedEffect` keyed on the dictation is never entered.
     */
    @Test fun `there and back again - Ready does not survive the drain`() = runTest(dispatcher) {
        val repo = FakeRepo()
        notes(repo)
        val voice = voice()

        voice.start()
        advanceUntilIdle()
        voice.stopAndTranscribe(completed = true)
        advanceUntilIdle()

        assertEquals("the dictation never became a note", 1, repo.saved.size)
        assertEquals(
            "the screen would have had a frame to see `Ready` in — the finding is stale",
            VoiceState.Idle,
            voice.state.value,
        )
        assertNull("the outbox kept the dictation", outbox.pending.value)
    }

    // ---- the promise ---------------------------------------------------------------------

    /**
     * `DEC-0012`, end to end: a dictation that becomes a note is on the clipboard, and no
     * composition was involved in putting it there.
     */
    @Test fun `a dictation that becomes a note is on the clipboard`() = runTest(dispatcher) {
        val repo = FakeRepo()
        notes(repo)
        val voice = voice(text = "позвонить в понедельник")

        voice.start()
        advanceUntilIdle()
        voice.stopAndTranscribe(completed = true)
        advanceUntilIdle()

        assertEquals("the dictation never became a note", 1, repo.saved.size)
        assertEquals(
            "`DEC-0012` promises the words are on the clipboard once the note exists",
            listOf("позвонить в понедельник"),
            clipboard,
        )
    }

    /** `G-02`: the switch is obeyed, and nothing is copied when it is off. */
    @Test fun `the preference is obeyed and nothing is copied when it is off`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = notes(repo, wantsCopy = false)

        vm.commitDictation(Transcript("тихо", "ru", SttSource.LOCAL, "whisper-base", 900), null)
        advanceUntilIdle()

        assertEquals("the note was not written", 1, repo.saved.size)
        assertEquals("the switch was off and the clipboard was overwritten anyway", emptyList<String>(), clipboard)
    }

    /**
     * **A write that failed is not a copy.** The recording and the words are still here and the
     * banner offers *Retry*; putting them on the clipboard as if they had been saved would be the
     * same lie `A-04` is about, from the other end.
     */
    @Test fun `a commit that does not reach the database copies nothing`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { failUpsert = true }
        val vm = notes(repo)

        vm.commitDictation(Transcript("не записалось", "ru", SttSource.LOCAL, "whisper-base", 900), null)
        advanceUntilIdle()

        assertEquals("the fixture wrote a note it was told to refuse", 0, repo.saved.size)
        assertEquals("a failed commit copied anyway", emptyList<String>(), clipboard)
        assertNotEquals(
            "the retry the person is offered was not armed",
            null,
            vm.state.value.pendingDictation,
        )
    }

    /**
     * The confirmation the screen draws is an **event**, and it carries whether the copy actually
     * happened rather than whether the preference is on (`B-211`'s one-shot half, `B-212`).
     */
    @Test fun `the commit announces itself, with the truth about the copy`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = notes(repo)
        val seen = mutableListOf<DictationCommitted>()
        // A foreground collector, cancelled below: `runTest`'s `backgroundScope` registers its
        // coroutines as background work and `advanceUntilIdle` does not run them, so a collector
        // launched there subscribes to nothing and the assertion reads an empty list (the same
        // trap `NotesViewModelTest` records for `appScope`, measured again here).
        val collector = vm.dictationCommitted.onEach { seen += it }.launchIn(this)
        advanceUntilIdle()

        vm.commitDictation(
            Transcript("из гарнитуры", "ru", SttSource.LOCAL_FALLBACK, "whisper-base", 900),
            null,
        )
        advanceUntilIdle()

        assertEquals("the commit was not announced", 1, seen.size)
        assertTrue("the confirmation would deny a copy that happened", seen.single().copied)
        assertEquals(
            "the fall-back to the headset was not carried to the screen",
            SttSource.LOCAL_FALLBACK,
            seen.single().source,
        )
        collector.cancel()
    }
}

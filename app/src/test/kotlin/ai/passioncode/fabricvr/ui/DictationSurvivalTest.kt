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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * **A dictation survives its host** (`REQ-046`, audit `H1`; `DEC-0034` is what it refines).
 *
 * The defect, end to end: `VoiceViewModel.stopAndTranscribe` launched the decode on
 * `viewModelScope`; `ImmersiveActivity.returnToPanel()` ends the activity, which clears the
 * store, which cancels that scope; `onCleared` then deleted the WAV because the state was
 * `Transcribing` rather than `Ready`; and the panel the person lands on has a **different**
 * `VoiceViewModel`, so nothing was waiting for the words on return. The same loss happened on
 * system Back out of the editor, and on Today when the headset came off.
 *
 * `DEC-0034` says leaving the Space is safe "because it transcribes rather than discards" —
 * true of the call, false of its outcome. These three cases are the outcome.
 *
 * **View-model tier, with a test dispatcher, not Robolectric Compose** (`SI-05`): every claim
 * here is about coroutine ownership and a file on disk, and neither is something a composition
 * answers better.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationSurvivalTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    /** Stands in for `Graph.scope` — the same virtual clock, and nothing a host can cancel. */
    private val appScope = CoroutineScope(dispatcher)

    private val outbox = DictationOutbox()

    @After fun tearDown() {
        appScope.cancel()
        root.deleteRecursively()
    }

    private val root: File =
        File(System.getProperty("java.io.tmpdir"), "survival-${System.nanoTime()}").apply { mkdirs() }

    // ---- fakes -------------------------------------------------------------------------

    private class FakeRepo : NotesRepository {
        val saved = mutableListOf<Note>()
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = saved.lastOrNull()
        override suspend fun upsert(note: Note): Result<Note> {
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
        /**
         * A `Result`, never a throw — the real `FileVault` wraps its IO and `adoptOrKeep`
         * relies on that. A fake that throws instead cancels the application scope the commit
         * runs on, which is a failure mode the production path does not have.
         */
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

    /** One chunk and stop — see `TestRecorder`; the real loop fills the heap under Robolectric. */
    private class OneChunkRecorder : Recorder {
        override fun hasPermission(): Boolean = true
        override fun record(onSamples: (ShortArray, Int) -> Unit): Flow<AudioRecorder.Level> = flow {
            val chunk = ShortArray(16_000) { 1 }
            onSamples(chunk, chunk.size)
            emit(AudioRecorder.Level(rms = 0.5f, samples = chunk.size))
        }
    }

    // ---- construction ------------------------------------------------------------------

    /** A `VoiceViewModel` inside a store, because clearing the store is what ending a host does. */
    private fun hostedVoice(
        gate: CompletableDeferred<Unit>? = null,
        text: String = "не потеряй меня",
    ): Pair<ViewModelStore, VoiceViewModel> {
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = VoiceViewModel(
                    recorder = OneChunkRecorder(),
                    modelStore = { PresentStore(File(root, "model.bin").apply { writeBytes(ByteArray(8)) }) },
                    transcribeWith = { _, _, _ ->
                        // A decode that can be held open, so a test can end the host **during**
                        // it — which is the moment the whole finding is about.
                        gate?.await()
                        Result.success(Transcript(text, "ru", SttSource.LOCAL, "whisper-base", 900))
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
                ) as T
            },
        )[VoiceViewModel::class.java]
        return store to vm
    }

    private fun notes(repo: NotesRepository) = NotesViewModel(
        repository = repo,
        today = { LocalDate.of(2026, 9, 21) },
        vault = FakeVault(root),
        dayTicks = emptyFlow(),
        appScope = appScope,
        mirrorFailures = MutableStateFlow(emptyMap<String, AppError>()),
        retryMirror = {},
        outbox = outbox,
        awaitReconcile = {},
    )

    // ---- the three claims ----------------------------------------------------------------

    /**
     * **Clearing the host mid-transcription still produces a note.**
     *
     * Before `REQ-046` this test's repository stayed empty: the decode was cancelled with
     * `viewModelScope`, the WAV was deleted by `onCleared`, and the panel's own view models had
     * no way to learn that a dictation had been owed.
     */
    @Test fun `a host cleared mid-transcription still produces a note`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val (store, voice) = hostedVoice(gate)
        voice.start()
        advanceUntilIdle()
        voice.stopAndTranscribe(completed = true)
        advanceUntilIdle()
        assertEquals("the fixture is not mid-transcription", VoiceState.Transcribing, voice.state.value)

        // The Space ends: `returnToPanel()` → the activity finishes → the store is cleared.
        store.clear()
        advanceUntilIdle()

        // The panel the person lands on, with its own view models.
        val repo = FakeRepo()
        notes(repo)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("the words were lost with the host: ${repo.saved}", 1, repo.saved.size)
        assertEquals("не потеряй меня", repo.saved.single().body)
        assertNull("the outbox kept the dictation after it became a note", outbox.pending.value)
    }

    /**
     * **The WAV is not deleted while a dictation is pending.**
     *
     * `onCleared` deleted the recording unless the state was `Ready`, so ending the host during
     * `Transcribing` destroyed the only copy of what was said *while the decode was reading it*.
     * Two windows are covered: the decode still running, and the transcript waiting in the
     * outbox for a note.
     */
    @Test fun `the recording outlives the host while a dictation is pending`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val (store, voice) = hostedVoice(gate)
        voice.start()
        advanceUntilIdle()
        voice.stopAndTranscribe(completed = true)
        advanceUntilIdle()
        val wav = File(root, "audio").listFiles()?.singleOrNull()
        assertTrue("the fixture wrote no recording", wav != null && wav.isFile)

        store.clear()
        advanceUntilIdle()
        assertTrue("the recording was deleted under a transcription still reading it", wav!!.isFile)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("the recording was deleted while its note was still owed", wav.isFile)
        assertTrue("the transcript never reached the outbox", outbox.pending.value != null)
    }

    /**
     * **Drained exactly once when two surfaces observe it.**
     *
     * Both hosts can be alive at the same moment — the panel stays up while the Space runs — and
     * each has its own `NotesViewModel` collecting this flow. A read-then-write would give the
     * person their words twice, as two notes, with the second one pointing at a recording the
     * first had already moved.
     */
    @Test fun `two surfaces observing the outbox write one note`() = runTest(dispatcher) {
        val panel = FakeRepo()
        val space = FakeRepo()
        notes(panel)
        notes(space)
        advanceUntilIdle()

        outbox.offer(
            Transcript("одна заметка", "ru", SttSource.LOCAL, "whisper-base", 900),
            File(root, "audio/one.wav").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }.absolutePath,
        )
        advanceUntilIdle()

        assertEquals(
            "the dictation was written by both surfaces: panel=${panel.saved} space=${space.saved}",
            1,
            panel.saved.size + space.saved.size,
        )
        assertNull(outbox.pending.value)
    }

    /**
     * A dictation the editor is holding is **not** taken by Today's drain — it is going to be
     * appended to the note already open — and it becomes an ordinary note the moment the hold
     * goes, which is what the editor's view model does when its host ends.
     */
    @Test fun `a held dictation waits for its surface and is drained when it goes`() =
        runTest(dispatcher) {
            val repo = FakeRepo()
            notes(repo)
            advanceUntilIdle()

            val editor = "editor-1"
            outbox.offer(
                Transcript("в открытую заметку", "ru", SttSource.LOCAL, "whisper-base", 900),
                "/audio/held.wav",
                holder = editor,
            )
            advanceUntilIdle()
            assertEquals("Today's drain took a dictation the editor was holding", 0, repo.saved.size)

            outbox.release(editor)
            advanceUntilIdle()

            assertEquals("the words were stranded when the editor went away", 1, repo.saved.size)
        }

    /**
     * And a cold start: the entry is restored from disk before any view model exists, and the
     * first `NotesViewModel` of the new process writes it.
     */
    @Test fun `a dictation restored from disk becomes a note on the next launch`() =
        runTest(dispatcher) {
            val file = File(root, "outbox/dictation.tsv")
            DictationOutbox(file).offer(
                Transcript("пережила процесс", "ru", SttSource.LOCAL, "whisper-base", 900),
                "/audio/cold.wav",
            )

            val next = DictationOutbox(file)
            next.restore()
            val repo = FakeRepo()
            NotesViewModel(
                repository = repo,
                today = { LocalDate.of(2026, 9, 21) },
                vault = FakeVault(root),
                dayTicks = emptyFlow(),
                appScope = appScope,
                mirrorFailures = MutableStateFlow(emptyMap<String, AppError>()),
                retryMirror = {},
                outbox = next,
                awaitReconcile = {},
            )
            advanceUntilIdle()

            assertEquals("the restored dictation was never written", 1, repo.saved.size)
            assertEquals("пережила процесс", repo.saved.single().body)
        }
}

package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.common.InMemorySecureSettings
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.stt.DownloadProgress
import ai.passioncode.fabricvr.common.SecureSettings
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.stt.InstalledModels
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.ModelDownloader
import ai.passioncode.fabricvr.vault.AudioUsage
import ai.passioncode.fabricvr.vault.ImportSummary
import ai.passioncode.fabricvr.vault.Vault
import ai.passioncode.fabricvr.vault.FileVault
import ai.passioncode.fabricvr.vault.VaultMirror
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Settings is where a person hands the app a credential and a 190 MB download, so its failures are
 * the expensive kind. Until this pass it was the one view model that could not be constructed
 * outside the app, which is exactly why none of this was covered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    /** A repository that remembers, so the sweep's row-clearing half can be asserted. */
    private class RecordingRepo : NotesRepository {
        val stored = linkedMapOf<String, Note>()
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(stored.values.toList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = stored[id]
        override suspend fun upsert(note: Note): Result<Note> {
            stored[note.id] = note; return Result.success(note)
        }
        override suspend fun delete(id: String): Result<Unit> {
            stored.remove(id); return Result.success(Unit)
        }
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> = Result.success(NotesRepository.newNote())
        override suspend fun count(): Int = 0
        override suspend fun recent(limit: Int): List<Note> = stored.values.take(limit)
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    /** A vault that answers the audio questions and counts how often it was asked to sweep. */
    private class CountingVault(root: File) : Vault by FileVault(root, io = Dispatchers.Unconfined) {
        var sweepResult = AudioUsage(0, 0L)
        var sweepCalls = 0
            private set
        override suspend fun audioUsage(): Result<AudioUsage> = Result.success(sweepResult)
        override suspend fun sweepAudio(cutoff: Long): Result<AudioUsage> {
            sweepCalls++; return Result.success(sweepResult)
        }
    }

    private class EmptyRepo : NotesRepository {
        override suspend fun count(): Int = 0
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = null
        override suspend fun upsert(note: Note): Result<Note> = Result.success(note)
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> =
            Result.success(NotesRepository.newNote())
        override suspend fun recent(limit: Int): List<Note> = emptyList()
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    private lateinit var settings: InMemorySecureSettings
    private class TestModelStore(root: File) : ModelStore {
        // **The default model's own file name**, so the fake store and the Keystore setting name
        // the same model. They did not: the store said `tiny` while `KEY_STT_MODEL` was absent
        // and therefore `small`, which nothing noticed while removal resolved the store — and
        // `B-199` made removal resolve the SELECTION, which is what production has always done.
        override val modelName = WhisperModel.DEFAULT.fileName
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = "https://huggingface.co/x/$modelName"
        private val file = File(root, modelName)
        override fun modelFile() = file
        override fun isPresent() = file.exists() && file.length() > 0
    }

    private lateinit var store: TestModelStore
    private lateinit var mirror: VaultMirror

    /**
     * The one directory all five models share (`FileModelStore`), so `InstalledModels` can be
     * pointed at the same place the fixture's store writes into.
     */
    private lateinit var modelRoot: File

    private fun modelFile(model: WhisperModel, partial: Boolean = false): File =
        File(modelRoot, model.fileName + if (partial) ".part" else "")

    @Before fun setUp() {
        settings = InMemorySecureSettings()
        // A stand-in rather than FileModelStore: the real one calls a model present only at its
        // exact published length, and writing 32 MB to assert a delete is a test that costs more
        // than it proves.
        modelRoot = temp.newFolder("models")
        store = TestModelStore(modelRoot)
        mirror = VaultMirror(EmptyRepo(), FileVault(temp.newFolder("vault"), io = Dispatchers.Unconfined))
        vault = CountingVault(temp.newFolder("audio-vault"))
    }

    private val crashes: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "crash-${System.nanoTime()}").apply { mkdirs() }
    }

    private val downloads = FakeDownloads()
    private val notes = RecordingRepo()
    private lateinit var vault: CountingVault

    private fun viewModel() = SettingsViewModel(
        settings = settings,
        modelStore = { store },
        downloads = downloads,
        vaultMirror = mirror,
        vaultRoot = "/vault",
        language = { "auto" },
        io = dispatcher,
        // A directory the test owns. The default reaches `Graph`, which is an `object` with a
        // `lateinit` context — the exact seam `T-017` exists for, found again by adding one
        // parameter and watching seven unrelated tests go red.
        crashDir = { crashes },
        vault = vault,
        notes = notes,
        installedModels = InstalledModels(modelRoot),
    )

    /**
     * `B-12`. The tick said only that *something* had been saved, and the screen's key field
     * cleared itself on it — so pasting a 70-character cloud key and then tapping a language
     * chip before pressing *Save* threw the key away with no message. In a headset re-pasting
     * that is not a small cost.
     *
     * The decision is here; the screen's rule is one line reading [SettingsUiState.lastSaved].
     */
    @Test fun `saving a language does not report the cloud key as saved`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveLanguage("ru")
        advanceUntilIdle()

        assertEquals(SavedField.OTHER, vm.state.value.lastSaved)
        assertTrue("the save did not land at all", vm.state.value.savedTick > 0)
    }

    @Test fun `saving the cloud key reports the cloud key`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveCloudKey("sk-not-a-real-key")
        advanceUntilIdle()

        assertEquals(SavedField.CLOUD_KEY, vm.state.value.lastSaved)
    }

    /**
     * And it survives a `reload()`, which is what happens immediately after every save: the
     * field is copied forward beside the tick rather than being reset to null by the next
     * snapshot, which would leave the screen unable to tell which save it had just seen.
     */
    /**
     * **The Save button, which is the path `B-12` was actually about.**
     *
     * It fired three independent `put`s — url, key, model — each its own coroutine with its own
     * IO hop, so the terminal `lastSaved` was whichever landed last: `OTHER`. The screen clears
     * the key field on `CLOUD_KEY`, so a seventy-character secret stayed in a `rememberSaveable`
     * `TextField` — the field that also reaches the saved-instance bundle. `T-030` fixed the
     * chip-tap half and broke this one; found by the seam tier of step 8's verification, which
     * reproduced it with this fixture before it was a test.
     */
    /**
     * `G-02`. `DEC-0012` copies every transcript to the system clipboard and stands — in a
     * headset the point of speaking is usually to paste somewhere else. What was missing is the
     * ability to say no to a cross-app write nobody offered, whose only notice lasts 2.5 s.
     *
     * **Absent means on**, so an upgrading person keeps the behaviour they have; only an
     * explicit `false` turns it off.
     */
    @Test fun `the clipboard preference defaults to on and round-trips`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue("an absent key must mean on", vm.state.value.copyTranscript)

        vm.saveCopyTranscript(false)
        advanceUntilIdle()
        assertFalse("the switch did not take", vm.state.value.copyTranscript)

        // A fresh view model over the same store: the answer survives the screen.
        val reopened = viewModel()
        advanceUntilIdle()
        assertFalse("the preference did not survive a reload", reopened.state.value.copyTranscript)
    }

    @Test fun `saving the cloud block reports the key, whatever else it wrote`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveCloud(url = "https://api.example.com/v1", key = "sk-not-a-real-key", model = "whisper-1")
        advanceUntilIdle()

        assertEquals(SavedField.CLOUD_KEY, vm.state.value.lastSaved)
    }

    /** And a save with no key in the box reports `OTHER`, so nothing is cleared for nothing. */
    @Test fun `saving the cloud block without a key reports other`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveCloud(url = "https://api.example.com/v1", key = "", model = "whisper-1")
        advanceUntilIdle()

        assertEquals(SavedField.OTHER, vm.state.value.lastSaved)
    }

    @Test fun `which field was saved survives the reload that follows the save`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveCloudKey("sk-not-a-real-key")
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()

        assertEquals(SavedField.CLOUD_KEY, vm.state.value.lastSaved)
    }

    @Test fun `removing the speech model deletes the file and says it is gone`() = runTest(dispatcher) {
        store.modelFile().apply { parentFile?.mkdirs(); writeBytes(ByteArray(64)) }
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue("the fixture did not register as present", vm.state.value.modelPresent)

        vm.removeModel()
        advanceUntilIdle()

        assertFalse("the screen still claims the model is here", vm.state.value.modelPresent)
        assertFalse("the 190 MB file is still on the headset", store.modelFile().exists())
    }

    @Test fun `saving the speech-service key keeps only its last four characters on screen`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveCloudKey("  a-secret-value-9f3c  ")
        advanceUntilIdle()

        assertEquals("9f3c", vm.state.value.cloudKeyTail)
        assertEquals(
            "the value must be stored trimmed, not as typed",
            "a-secret-value-9f3c",
            settings.get(SecureSettings.KEY_CLOUD_STT_KEY),
        )
    }

    @Test fun `clearing the speech-service key removes it from the store`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.saveCloudKey("a-secret-value-9f3c")
        advanceUntilIdle()

        vm.clearCloudKey()
        advanceUntilIdle()

        assertNull(vm.state.value.cloudKeyTail)
        assertNull(settings.get(SecureSettings.KEY_CLOUD_STT_KEY))
    }

    @Test fun `a cleartext server on the open internet is refused and never stored`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveServerUrl("http://whisper.example.com:8080")
        advanceUntilIdle()

        assertNotNull("a refused URL must say so", vm.state.value.message)
        assertNull(settings.get(SecureSettings.KEY_WHISPER_SERVER_URL))
    }

    @Test fun `a server on the person's own network is accepted`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveServerUrl("http://192.168.0.14:8080")
        advanceUntilIdle()

        assertNull(vm.state.value.message)
        assertEquals("http://192.168.0.14:8080", settings.get(SecureSettings.KEY_WHISPER_SERVER_URL))
    }

    @Test fun `a keystore that cannot write reports it instead of pretending to save`() = runTest(dispatcher) {
        settings.failNextPut = true
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveCloudKey("a-secret-value-9f3c")
        advanceUntilIdle()

        assertNotNull("a failed save was reported as success", vm.state.value.message)
        assertNull(vm.state.value.cloudKeyTail)
    }

    @Test fun `a note the vault refused is counted on the screen`() = runTest(dispatcher) {
        // A directory standing where the note's file belongs makes the write fail the way a full
        // disk would, without needing one.
        val root = temp.newFolder("blocked-vault")
        val vault = FileVault(root, io = Dispatchers.Unconfined)
        val note = Note(id = "n9", title = "x", body = "", createdAt = 1, updatedAt = 1)
        vault.pathFor(note).mkdirs()

        val changes = MutableSharedFlow<NoteChange>()
        val repo = object : NotesRepository by EmptyRepo() {
            override fun observeChanges(): Flow<NoteChange> = changes
        }
        mirror = VaultMirror(repo, vault)
        val vm = viewModel()
        mirror.start(backgroundScope)
        advanceUntilIdle()
        assertEquals(0, vm.state.value.vaultOutOfSync)

        changes.emit(NoteChange.Upserted(note))
        advanceUntilIdle()

        assertEquals("the screen says the vault is in sync when it is not", 1, vm.state.value.vaultOutOfSync)
    }

    /**
     * `I-22`/`A-21` from the screen's side.
     *
     * `SettingsViewModel.downloadModel()` used to resolve its downloader once and pin it to model
     * A; saving model B then recomputed `modelPresent` for **B** and showed *not downloaded* with
     * a *Download* button, while A was still transferring. Pressing it ran
     * `downloadJob?.cancel()`, A's flow hit `catch (CancellationException)`, and
     * `ModelDownloader` deleted A's `.part`. A 539 MB resumable partial gone, silently, because
     * the person looked at a different name in a list.
     */
    @Test fun `changing the model mid-download keeps the first download running`() = runTest(dispatcher) {
        settings.put(SecureSettings.KEY_STT_MODEL, WhisperModel.MEDIUM.key)
        val vm = viewModel()
        advanceUntilIdle()
        vm.downloadModel()
        advanceUntilIdle()
        downloads.emit(WhisperModel.MEDIUM, DownloadProgress.Running(43, 100))

        vm.saveWhisperModel(WhisperModel.SMALL)
        advanceUntilIdle()

        assertEquals("choosing another model cancelled the running transfer", 0, downloads.cancels)
        assertNotNull(
            "the running download was dropped from the owner",
            downloads.progress(WhisperModel.MEDIUM),
        )
        assertEquals(
            "the screen shows the new model's state",
            WhisperModel.SMALL,
            vm.state.value.whisperModel,
        )
        assertEquals(
            "a 539 MB transfer became invisible the moment the person looked elsewhere",
            43L to 100L,
            vm.state.value.otherDownloads[WhisperModel.MEDIUM],
        )
    }

    /**
     * **The delete must not race the writer — and since `B-199` it stops the writer rather than
     * refusing.**
     *
     * This case used to assert a refusal: `removeModel()` answered `AppError.ModelBusy` while a
     * transfer of that model was running. The refusal was safe and it was also the wrong answer
     * to the question the person asked — *Remove* under a progress bar means "I do not want
     * this", and being told to wait for a 539 MB download to finish before it may be deleted is
     * the app arguing with them. `B-199` names the rule for the whole screen: **cancel, then
     * delete**, one policy for every removal, because two policies for one act is how the
     * storage list and this button would drift apart.
     *
     * The order is what matters, so it is the order that is asserted, from inside the cancel.
     */
    @Test fun `removing a model stops its transfer before it deletes anything`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        modelFile(WhisperModel.DEFAULT).writeBytes(ByteArray(64))
        vm.downloadModel()
        advanceUntilIdle()
        downloads.emit(WhisperModel.DEFAULT, DownloadProgress.Running(1, 100))
        var fileWasStillThereWhenTheWriterStopped: Boolean? = null
        downloads.onCancel = { fileWasStillThereWhenTheWriterStopped = modelFile(WhisperModel.DEFAULT).exists() }

        vm.removeModel()
        advanceUntilIdle()

        assertEquals(
            "the writer was never stopped, so the delete raced it (M15 from the other side)",
            true,
            fileWasStillThereWhenTheWriterStopped,
        )
        assertFalse("the file survived the removal", modelFile(WhisperModel.DEFAULT).exists())
    }

    // ---- B-199: the model store can be listed and reclaimed, and now a screen offers it ------

    /**
     * **Up to 1.395 GB across five models, and nothing listed it.**
     *
     * `FileModelStore` puts every model in one directory so switching between them keeps what is
     * already downloaded — the right call, and the whole of this row: the only removal the app
     * had resolved *the selected* store and deleted that one file. Try `large-turbo`, dislike the
     * speed, go back to `small`, and 574 MB sits there that nothing lists and nothing can
     * reclaim short of uninstalling, which takes the vault with it.
     *
     * **A `.part` is counted and is the half that is easy to miss**: an interrupted transfer
     * keeps its partial deliberately so *Download* costs only what is missing (`H8`), and those
     * bytes live under a name no `WhisperModel` mentions.
     */
    @Test fun `the screen lists every model on disk with its bytes and whether it finished`() = runTest(dispatcher) {
        modelFile(WhisperModel.SMALL).writeBytes(ByteArray(1_024))
        modelFile(WhisperModel.MEDIUM, partial = true).writeBytes(ByteArray(2_048))

        val vm = viewModel()
        advanceUntilIdle()

        val listed = vm.state.value.installed.associateBy { it.model }
        assertEquals(
            "the listing is not the catalogue's own answer about what is on disk",
            setOf(WhisperModel.SMALL, WhisperModel.MEDIUM),
            listed.keys,
        )
        assertEquals(1_024L, listed.getValue(WhisperModel.SMALL).bytes)
        assertFalse(
            "a file short of its published length is a transfer, not a model",
            listed.getValue(WhisperModel.SMALL).complete,
        )
        assertEquals(
            "the leftover .part is bytes on the headset too",
            2_048L,
            listed.getValue(WhisperModel.MEDIUM).bytes,
        )
        assertEquals("the total is not what the rows add up to", 3_072L, vm.state.value.installedBytes)
    }

    /** One row's *Remove*: the same cancel-then-delete rule, for a model nobody selected. */
    @Test fun `removing one listed model reclaims it and leaves the others alone`() = runTest(dispatcher) {
        modelFile(WhisperModel.TINY).writeBytes(ByteArray(16))
        modelFile(WhisperModel.MEDIUM).writeBytes(ByteArray(64))
        val vm = viewModel()
        advanceUntilIdle()
        var stoppedFirst: Boolean? = null
        downloads.onCancel = { stoppedFirst = modelFile(WhisperModel.MEDIUM).exists() }

        vm.removeInstalled(WhisperModel.MEDIUM)
        advanceUntilIdle()

        assertEquals("the writer was not stopped before the delete", true, stoppedFirst)
        assertFalse(modelFile(WhisperModel.MEDIUM).exists())
        assertTrue("the removal took a model it was not asked about", modelFile(WhisperModel.TINY).exists())
        assertEquals(listOf(WhisperModel.TINY), vm.state.value.installed.map { it.model })
    }

    /**
     * **A finished download changes the shelf, and the shelf is read from the disk.**
     *
     * The rows and the total are `File.length()` sums taken when the screen was built, so without
     * a refresh a 190 MB arrival is invisible in the one place that measures it — and *Free up
     * space* is then a decision made from a number that was true a minute ago.
     */
    @Test fun `a finished download appears on the shelf without leaving the screen`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue("the fixture already had something on the shelf", vm.state.value.installed.isEmpty())

        vm.downloadModel()
        advanceUntilIdle()
        // The transfer lands: the bytes are on disk before `Done` is published, as the real
        // downloader renames its `.part` into place before it emits.
        modelFile(WhisperModel.DEFAULT).writeBytes(ByteArray(4_096))
        downloads.emit(WhisperModel.DEFAULT, DownloadProgress.Done(modelFile(WhisperModel.DEFAULT), ""))
        advanceUntilIdle()

        assertEquals(
            "the shelf still shows what was there when the screen opened",
            listOf(WhisperModel.DEFAULT),
            vm.state.value.installed.map { it.model },
        )
        assertEquals(4_096L, vm.state.value.installedBytes)
    }

    /**
     * ***Free up space*, as one press** — and it keeps the selected model, because reclaiming the
     * one the person is about to dictate with would cost them the 190 MB again on the next
     * Record. Which model that is, is a Keystore value `:app` owns; `InstalledModels` takes it as
     * a parameter rather than guessing (`DEC-0031`).
     */
    @Test fun `free up space reclaims every model but the selected one`() = runTest(dispatcher) {
        settings.put(SecureSettings.KEY_STT_MODEL, WhisperModel.SMALL.key)
        listOf(WhisperModel.TINY, WhisperModel.SMALL, WhisperModel.MEDIUM).forEach {
            modelFile(it).writeBytes(ByteArray(100))
        }
        modelFile(WhisperModel.LARGE_TURBO, partial = true).writeBytes(ByteArray(50))
        val vm = viewModel()
        advanceUntilIdle()
        val stoppedWhileStillOnDisk = mutableSetOf<WhisperModel>()
        downloads.onCancel = { model -> if (modelFile(model).exists()) stoppedWhileStillOnDisk += model }

        vm.freeUpSpace()
        advanceUntilIdle()

        assertEquals(
            "free up space took the model the next dictation needs",
            listOf(WhisperModel.SMALL),
            vm.state.value.installed.map { it.model },
        )
        assertTrue("the kept model's file is gone", modelFile(WhisperModel.SMALL).exists())
        assertFalse("an unfinished transfer's bytes were left behind", modelFile(WhisperModel.LARGE_TURBO, partial = true).exists())
        assertEquals(
            "a transfer was left running under a delete",
            setOf(WhisperModel.TINY, WhisperModel.MEDIUM),
            stoppedWhileStillOnDisk,
        )
        assertFalse(
            "the selected model's transfer was cancelled although it is the one being kept",
            WhisperModel.SMALL in stoppedWhileStillOnDisk,
        )
    }

    /**
     * Found by the group-verification pass over steps 4–5, and it is two defects in one row.
     *
     * `ModelDownloads.cancel` used to remove the entry and cancel the job, and `ModelDownloader`'s
     * cancellation path rethrows without emitting — so the flow froze at its last `Running` value
     * and every other collector parked on a progress bar for a transfer that no longer existed.
     * And `reload()` read `downloading = mine ?: _state.value.downloading`, which re-instated the
     * stale pair on every refresh, so the row came back even after the owner had dropped it —
     * with a Cancel button under it that did nothing.
     */
    @Test fun `a cancelled download clears the row rather than leaving a phantom`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.downloadModel()
        advanceUntilIdle()
        downloads.emit(WhisperModel.DEFAULT, DownloadProgress.Running(50, 100))
        vm.reload()
        advanceUntilIdle()
        assertNotNull("the fixture never showed a transfer, so this asserts nothing", vm.state.value.downloading)

        // Cancelled by somebody else — the Today banner — which is the case the elvis hid.
        downloads.cancel(WhisperModel.DEFAULT)
        vm.reload()
        advanceUntilIdle()

        assertNull(
            "the row survived the transfer: a progress bar for a download that no longer exists",
            vm.state.value.downloading,
        )
    }

    /**
     * Cancel has to name the transfer the screen is SHOWING. Downloads are keyed by model, the
     * selection can change under a running one, and re-reading the selection at cancel time meant
     * stopping a different model — or nothing — while the one on screen carried on.
     */
    @Test fun `cancel stops the transfer this screen started, not whatever is selected now`() =
        runTest(dispatcher) {
            settings.put(SecureSettings.KEY_STT_MODEL, WhisperModel.MEDIUM.key)
            val vm = viewModel()
            advanceUntilIdle()
            vm.downloadModel()
            advanceUntilIdle()
            vm.saveWhisperModel(WhisperModel.SMALL)
            advanceUntilIdle()

            vm.cancelDownload()
            advanceUntilIdle()

            assertNull(
                "the running transfer was left alone and something else was cancelled",
                downloads.progress(WhisperModel.MEDIUM),
            )
            assertEquals(1, downloads.cancels)
        }

    /**
     * `T-024`'s load-bearing half. Deleting the recordings and leaving the rows alone turns every
     * swept note into a *Transcribe again* button that fails — a worse state than the disk usage
     * it was fixing. The vault deletes files and knows nothing about rows, so this is where the
     * two are joined.
     */
    @Test fun `a sweep clears audioPath on every note it swept`() = runTest(dispatcher) {
        val kept = NotesRepository.newNote("свежая", "тело").copy(audioPath = "/vault/new.wav")
        val swept = NotesRepository.newNote("старая", "тело").copy(audioPath = "/vault/old.wav")
        notes.upsert(kept)
        notes.upsert(swept)
        vault.sweepResult = AudioUsage(1, 100L, setOf(swept.id))
        val vm = viewModel()
        advanceUntilIdle()
        vm.saveAudioRetention(30)
        advanceUntilIdle()

        vm.deleteOldRecordings()
        advanceUntilIdle()

        assertNull("a swept note still points at a deleted file", notes.stored.getValue(swept.id).audioPath)
        assertEquals(
            "an untouched note lost its recording pointer",
            "/vault/new.wav",
            notes.stored.getValue(kept.id).audioPath,
        )
    }

    /** `DEC-0038`: the mechanism ships, the default keeps everything, and nothing fires on its own. */
    @Test fun `keep everything is the default and deletes nothing`() = runTest(dispatcher) {
        val note = NotesRepository.newNote("со звуком", "тело").copy(audioPath = "/vault/a.wav")
        notes.upsert(note)
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("the default is not Keep everything", 0, vm.state.value.audioRetentionDays)

        vm.deleteOldRecordings()
        advanceUntilIdle()

        assertEquals("a retention of zero swept something", 0, vault.sweepCalls)
        assertEquals("/vault/a.wav", notes.stored.getValue(note.id).audioPath)
    }

    /** Choosing an age stores the choice and still deletes nothing until the button is pressed. */
    @Test fun `choosing an age does not delete anything by itself`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveAudioRetention(7)
        advanceUntilIdle()

        assertEquals(7, vm.state.value.audioRetentionDays)
        assertEquals("choosing a retention swept immediately", 0, vault.sweepCalls)
    }

    // ---- REQ-067 / M8, M9: a reload must not undo what the screen is in the middle of ----

    /**
     * **A chip tap during an export used to re-enable *Export*.**
     *
     * `reload()` rebuilds the whole state from a whitelist of settings reads and copies forward
     * only five fields; `exporting` was not one of them. Every chip on this screen calls `put`,
     * which calls `reload()` on success — so tapping a language while a 7 GB archive was being
     * written set `exporting = false`, the button came back, and a second press started a
     * **second concurrent export** into a second pending `MediaStore` row.
     *
     * Watched failing before the fix: `exporting` read false while the export was still running.
     */
    @Test fun `a reload during an export does not re-enable the button`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Result<ExportOutcome>>()
        val vm = exportingViewModel { started.complete(Unit); finish.await() }
        advanceUntilIdle()

        vm.exportVault(includeAudio = false)
        advanceUntilIdle()
        assertTrue("the export never started", started.isCompleted)
        assertTrue("the row did not say it was exporting", vm.state.value.exporting)

        vm.saveLanguage("ru")
        advanceUntilIdle()

        assertTrue(
            "a chip tap during an export re-enabled Export — a second press would start a " +
                "second concurrent archive",
            vm.state.value.exporting,
        )
        finish.complete(Result.failure(IllegalStateException("cancelled by the test")))
        advanceUntilIdle()
    }

    /**
     * And the other half: a tap **after** the export removes the "saved at" line, which is the
     * only thing on the screen that says where the archive went.
     */
    @Test fun `a reload after an export keeps the path it landed at`() = runTest(dispatcher) {
        val outcome = ExportOutcome(
            displayPath = "/sdcard/Download/fabric-vr-vault.zip",
            handle = "content://downloads/1",
            summary = ai.passioncode.fabricvr.vault.ExportSummary(noteFiles = 2, audioFiles = 0, bytes = 4_096),
        )
        val vm = exportingViewModel { Result.success(outcome) }
        advanceUntilIdle()

        vm.exportVault(includeAudio = true)
        advanceUntilIdle()
        assertEquals(outcome.displayPath, vm.state.value.exportedTo)

        vm.saveLanguage("ru")
        advanceUntilIdle()

        assertEquals(
            "the only line naming where the archive went was wiped by an unrelated tap",
            outcome.displayPath,
            vm.state.value.exportedTo,
        )
        assertEquals(outcome.handle, vm.state.value.exportHandle)
        assertTrue("the scope the archive was written with was forgotten", vm.state.value.exportIncludesAudio)
    }

    /**
     * `M9`, the second half. A transfer started on the Today banner was read **once** out of
     * `.value` when this screen was built and never again, so the progress row froze at whatever
     * byte count happened to be current and its *Cancel* stood under a number that was no longer
     * true.
     *
     * Watched failing before the fix: the row still read the first sample after the transfer had
     * moved on.
     */
    @Test fun `a transfer started elsewhere keeps moving on this screen`() = runTest(dispatcher) {
        downloads.emit(WhisperModel.DEFAULT, DownloadProgress.Running(10_000_000, 190_000_000))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(10_000_000L to 190_000_000L, vm.state.value.downloading)

        downloads.emit(WhisperModel.DEFAULT, DownloadProgress.Running(120_000_000, 190_000_000))
        advanceUntilIdle()

        assertEquals(
            "the screen snapshotted the transfer instead of following it",
            120_000_000L to 190_000_000L,
            vm.state.value.downloading,
        )
    }

    // ---- REQ-054 / H4: the archive has a way back in ----------------------------------------

    /**
     * **The export had no restore**, which is what made `VaultImporter`'s "genuine restore path:
     * unzip it back into `filesDir/vault`" a sentence describing an operation nobody could
     * perform: `filesDir` is app-private and `run-as` is unavailable against a release build.
     * This is the seam between the picker and `VaultZipImporter`.
     */
    @Test fun `a chosen archive is unpacked and its outcome is on the screen`() = runTest(dispatcher) {
        val opened = java.util.concurrent.atomic.AtomicInteger(0)
        val closed = java.util.concurrent.atomic.AtomicInteger(0)
        var handed: java.io.InputStream? = null
        val vm = restoringViewModel(
            { stream -> handed = stream; Result.success(ImportSummary(imported = 3, skipped = 1, refused = 0)) },
        )
        advanceUntilIdle()

        vm.restoreVault {
            opened.incrementAndGet()
            object : java.io.ByteArrayInputStream(ByteArray(0)) {
                override fun close() { closed.incrementAndGet(); super.close() }
            }
        }
        advanceUntilIdle()

        assertEquals("the archive was never opened", 1, opened.get())
        assertNotNull("the importer was handed nothing", handed)
        assertEquals(ImportSummary(3, 1, 0), vm.state.value.restored)
        assertFalse("the row is still showing a restore that finished", vm.state.value.restoring)
    }

    /** A picker that answered with nothing that can be read says so rather than doing nothing. */
    @Test fun `an archive that cannot be opened is reported`() = runTest(dispatcher) {
        val vm = restoringViewModel({ Result.success(ImportSummary(0, 0, 0)) })
        advanceUntilIdle()

        vm.restoreVault { null }
        advanceUntilIdle()

        assertNotNull("a restore that could not start said nothing", vm.state.value.message)
        assertFalse(vm.state.value.restoring)
        assertNull("a restore that never ran claimed an outcome", vm.state.value.restored)
    }

    /** A second press while one is unpacking must not start a second unpack over the same files. */
    @Test fun `a second press during a restore is ignored`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<ImportSummary>>()
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val vm = restoringViewModel({ calls.incrementAndGet(); gate.await() })
        advanceUntilIdle()

        vm.restoreVault { java.io.ByteArrayInputStream(ByteArray(0)) }
        advanceUntilIdle()
        vm.restoreVault { java.io.ByteArrayInputStream(ByteArray(0)) }
        advanceUntilIdle()

        assertEquals("two unpacks were started over one vault", 1, calls.get())
        gate.complete(Result.success(ImportSummary(1, 0, 0)))
        advanceUntilIdle()
    }

    /**
     * **`B-159`: an exception inside `viewModelScope` has no owner, and the suite paid for it.**
     *
     * `reload()` runs from `init` and reads four seams in one coroutine. A construction site that
     * had not been given one of them threw *inside the launch*, and `viewModelScope` is a plain
     * `SupervisorJob` with no handler — so the throw went to the process's uncaught-exception
     * route, which under `runTest` is **whichever test happens to be running**. Five runs, four
     * different red cases, none of them the one that built the view model.
     *
     * Asserted from the person's side rather than by catching: a read that failed must reach the
     * banner. A test that only proved "nothing escaped" would also pass a `catch {}` that threw
     * the failure away, which is the other way to make a screen lie.
     */
    @Test fun `a seam that throws reaches the banner instead of escaping the scope`() = runTest(dispatcher) {
        val vm = SettingsViewModel(
            settings = settings,
            modelStore = { error("this construction site was never given a model store") },
            downloads = downloads,
            vaultMirror = mirror,
            vaultRoot = "/vault",
            language = { "auto" },
            io = dispatcher,
            crashDir = { crashes },
            vault = vault,
            notes = notes,
            installedModels = InstalledModels(modelRoot),
        )
        advanceUntilIdle()

        assertNotNull(
            "the failed read vanished: the screen shows defaults and says nothing",
            vm.state.value.message,
        )
    }

    private fun restoringViewModel(
        import: suspend (java.io.InputStream) -> Result<ImportSummary>,
    ) = SettingsViewModel(
        settings = settings,
        modelStore = { store },
        downloads = downloads,
        vaultMirror = mirror,
        vaultRoot = "/vault",
        language = { "auto" },
        io = dispatcher,
        crashDir = { crashes },
        vault = vault,
        notes = notes,
        installedModels = InstalledModels(modelRoot),
        importArchive = import,
    )

    private fun exportingViewModel(export: suspend (Boolean) -> Result<ExportOutcome>) =
        SettingsViewModel(
            settings = settings,
            modelStore = { store },
            downloads = downloads,
            vaultMirror = mirror,
            vaultRoot = "/vault",
            language = { "auto" },
            io = dispatcher,
            crashDir = { crashes },
            vault = vault,
            notes = notes,
            installedModels = InstalledModels(modelRoot),
            runExport = export,
        )
}

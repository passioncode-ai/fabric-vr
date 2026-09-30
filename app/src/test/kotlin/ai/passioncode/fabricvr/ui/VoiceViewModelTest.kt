package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.UiStateMapper
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.AudioRecorder
import ai.passioncode.fabricvr.stt.DownloadProgress
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.stt.SttEngine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The real [VoiceViewModel], constructed and driven through its public API.
 *
 * It replaces `VoiceViewModelStateTest`, which was green and proved nothing: that file re-typed the
 * guard it claimed to check and asserted against its own copy, so deleting the guard from the
 * production class left it passing. It also occupied the name a real test would be found under, so
 * an absence read as a presence.
 *
 * **Scope limit, stated because it is not obvious.** The record → stop → transcribe path is not
 * reachable here: `recorder` is the concrete, final `AudioRecorder`, which talks to `AudioRecord`
 * and has no seam to fake. Making one is a signature change that **T-020** performs for its own
 * reason, so doing it here would mean doing it twice. What is reachable today — and what the
 * deleted file pretended to cover — is the guard that a stop from a non-recording state changes
 * nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VoiceViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    /** Counts calls so a test can assert that stopping from a dead state reached no engine. */
    private class CountingEngine : SttEngine {
        var calls = 0
        override val name: String = "counting"
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, langHint: String?): Result<Transcript> {
            calls++
            return Result.success(Transcript("x", "en", SttSource.LOCAL, name, 1))
        }
    }

    /** Emits exactly what a test asks for, so the three download states are reachable at all. */
    private class PresentModelStore(private val file: File, private val present: Boolean = true) : ModelStore {
        override val modelName = "ggml-test.bin"
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = "https://example.invalid/$modelName"
        override fun modelFile() = file
        override fun isPresent() = present
    }

    private lateinit var engine: CountingEngine
    private lateinit var temp: File

    @Before fun setUp() {
        engine = CountingEngine()
        temp = File(System.getProperty("java.io.tmpdir"), "voice-vm-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        // **The scope standing in for `Graph.scope` is cancelled here and was not before.** It is
        // built on this class's own dispatcher and outlived the test method, so a transcription
        // still queued on it stayed alive into whatever ran next — one of the two ways `B-183`
        // could see `Dispatchers.Main` in use while another class was setting it.
        appScope.cancel()
        temp.deleteRecursively()
    }

    /** See [TestRecorder]: the real one fills the heap under Robolectric and is not the subject. */
    private val recorder = TestRecorder()

    /** One per test method, in memory: `DictationOutboxTest` owns the disk half (`REQ-046`). */
    private val outbox = ai.passioncode.fabricvr.DictationOutbox()

    /** Stands in for `Graph.scope`, where a transcription now outlives its host (`REQ-046`). */
    private val appScope = kotlinx.coroutines.CoroutineScope(dispatcher)

    private fun viewModel(
        last: VoiceViewModel.LastRecording = VoiceViewModel.LastRecording(),
        remoteReady: Boolean = false,
        modelPresent: Boolean = true,
        downloads: FakeDownloads? = null,
        /** A microphone that has not been granted, which is the whole of `T-031`'s first tap. */
        recorder: TestRecorder = this.recorder,
    ) = VoiceViewModel(
        recorder = recorder,
        modelStore = { PresentModelStore(File(temp, "model.bin").apply { writeBytes(ByteArray(8)) }, modelPresent) },
        downloads = downloads ?: FakeDownloads(emptyList()),
        // Without this the call reaches `Graph.whisperModel()`, whose `lateinit appContext`
        // throws — on the real IO dispatcher, where `advanceUntilIdle()` cannot see it, so the
        // download simply never happened and four tests read as production defects.
        chosenModel = { WhisperModel.DEFAULT },
        // Both reach `Graph` by default, which is an `object` with a `lateinit` context — the
        // seam rule working. Without them `refresh()` threw inside `init` and the exception
        // escaped `viewModelScope`, failing whichever test happened to be running: five runs of
        // the suite reded on four different cases. A flaky suite is worse than a red one.
        currentProvider = { SttProvider.LOCAL },
        transcribeWith = { pcm, langHint, _ -> engine.transcribe(pcm, langHint = langHint) },
        language = { "auto" },
        remoteReady = { remoteReady },
        audioDir = { temp },
        // `REQ-046`. Both reach `Graph` by default: the outbox resolves `appContext` to find its
        // file, and `appScope` is a real `Dispatchers.Default` scope no test scheduler can
        // advance — so a transcription launched on it would simply never be waited for.
        outbox = outbox,
        appScope = appScope,
        last = last,
        // Not a detail. `T-019` moved the Keystore and disk reads behind `withContext(io)`, and
        // with the real `Dispatchers.IO` the work runs on a pool `advanceUntilIdle()` cannot wait
        // for — five tests here went green-to-red on nothing but a race. That is the reason the
        // dispatcher is a parameter, written down in `SettingsViewModel` before this and true
        // again here.
        io = dispatcher,
    )

    @Test
    fun `stopping after a refused microphone keeps the failure on screen`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onPermissionResult(granted = false, permanent = true)
        advanceUntilIdle()

        vm.stopAndTranscribe(completed = true)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("the failure was replaced by a stop that had nothing to stop: $state", state is VoiceState.Failed)
        assertEquals(
            "the person lost the one action that can recover a permanent refusal",
            UiAction.OPEN_APP_SETTINGS,
            (state as VoiceState.Failed).message.action,
        )
        assertEquals("a stop from a dead state reached the engine", 0, engine.calls)
    }

    @Test
    fun `stopping when nothing is recording changes nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        val before = vm.state.value

        vm.stopAndTranscribe(completed = true)
        advanceUntilIdle()

        assertEquals("a stop from Idle moved the state", before, vm.state.value)
        assertEquals("a stop from Idle reached the engine", 0, engine.calls)
    }

    /**
     * `SCN-013` step 3: *"the recording starts immediately, without re-pressing Record"*.
     *
     * It was the third of `D-05`'s six taps and the purest waste of them: granting parked the
     * machine in `VoiceState.Allowed`, which no screen rendered — the warning disappeared, the
     * button silently said *Record* again, and the person pressed it a second time to find out
     * what had happened. The state it replaced this with is gone (`T-031`); the pinning test that
     * used to assert `Allowed` here is what made removing it deliberate.
     */
    @Test
    fun `granting the microphone starts recording`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onPermissionResult(granted = true, permanent = false)
        advanceUntilIdle()

        assertTrue("granting did not start a recording: ${vm.state.value}", vm.state.value is VoiceState.Recording)
        assertTrue("the recorder was never started", recorder.started)
    }

    /**
     * The first press with no permission produces the **system dialog**, not a button that has
     * relabelled itself to *Allow the microphone* in the place the person just pressed.
     *
     * The ask is an event rather than a call because `ImmersiveActivity` is not a
     * `ComponentActivity`: the request belongs to whichever activity is hosting, so the view
     * model can only say *when*. That it is raised **exactly once** per press is the half that
     * matters — a replayed state would put a system dialog in front of somebody who returned to
     * the screen without pressing anything.
     */
    @Test
    fun `a first press with no permission asks for it, once`() = runTest(dispatcher) {
        val refused = TestRecorder(permitted = false)
        val vm = viewModel(recorder = refused)
        val asks = mutableListOf<Unit>()
        val job = launch { vm.permissionAsks.collect { asks += it } }
        advanceUntilIdle()

        vm.start()
        advanceUntilIdle()

        assertEquals("the press did not reach the system", 1, asks.size)
        assertFalse("a refused microphone was opened anyway", refused.started)
        // And the state is left alone: the dialog appears over whatever is there, and the
        // explanation belongs after a refusal rather than before the prompt.
        assertEquals(VoiceState.Idle, vm.state.value)
        job.cancel()
    }

    /** A refusal is where the explanation goes, and it is still the state that carries it. */
    @Test
    fun `a refusal leaves the state that explains itself`() = runTest(dispatcher) {
        val vm = viewModel(recorder = TestRecorder(permitted = false))

        vm.onPermissionResult(granted = false, permanent = false)
        advanceUntilIdle()

        assertEquals(VoiceState.NeedsPermission, vm.state.value)
    }

    /**
     * Four minutes of waiting must not end in an absence. `DownloadProgress.Done` became
     * `VoiceState.Idle` and the progress bar simply vanished, so the person's only evidence that
     * the transfer had worked was that something had stopped being on the screen (`D-05`).
     */
    /**
     * **Both facts are owned elsewhere.** Settings can remove the model, download one, or choose
     * a different one, and this view model is scoped to a start destination that survives that
     * trip — so a value cached in `init` tells a person to download a model they already have,
     * or offers the short empty state for one that has just been deleted. The first version
     * cached both and carried a doc comment claiming a flag "would go stale the first time
     * somebody removed a model in Settings"; it is that flag. Found by two tiers independently.
     */
    @Test
    fun `refresh re-reads what Settings may have changed`() = runTest(dispatcher) {
        var present = true
        var model = WhisperModel.DEFAULT
        val vm = VoiceViewModel(
            recorder = recorder,
            modelStore = { PresentModelStore(File(temp, "model.bin"), present) },
            downloads = FakeDownloads(emptyList()),
            chosenModel = { model },
            transcribeWith = { pcm, langHint, _ -> engine.transcribe(pcm, langHint = langHint) },
            language = { "auto" },
            remoteReady = { false },
            audioDir = { temp },
            io = dispatcher,
        )
        advanceUntilIdle()
        assertTrue(vm.modelPresent.value)
        assertEquals(WhisperModel.DEFAULT, vm.chosen.value)

        present = false
        model = WhisperModel.LARGE_TURBO
        vm.refresh()
        advanceUntilIdle()

        assertFalse("the empty state would still offer the short sentence", vm.modelPresent.value)
        assertEquals("the first-run sentence would name the wrong size", WhisperModel.LARGE_TURBO, vm.chosen.value)
    }

    @Test
    fun `a finished download announces itself and updates the model presence`() = runTest(dispatcher) {
        val downloads = FakeDownloads(
            listOf(
                DownloadProgress.Running(1, 2),
                DownloadProgress.Done(File(temp, "model.bin"), "sha"),
            ),
        )
        val vm = viewModel(modelPresent = false, downloads = downloads)
        val arrivals = mutableListOf<Unit>()
        val job = launch { vm.modelArrivals.collect { arrivals += it } }
        advanceUntilIdle()
        assertFalse("the fixture started with the model present", vm.modelPresent.value)

        vm.downloadModel()
        advanceUntilIdle()

        assertEquals("nothing said the model arrived", 1, arrivals.size)
        assertTrue("the empty state would still offer the download", vm.modelPresent.value)
        job.cancel()
    }

    /**
     * `REQ-048` / `H10`. **A second press while the first request is still in flight.**
     *
     * `start()` had no guard, so the second press called `requestPermissions` again — and AOSP
     * answers a request made while one is already showing *immediately*, with an empty
     * `grantResults`. The host reads that as "not granted", `shouldShowRequestPermissionRationale`
     * is false while the dialog is up, so it is reported as **permanently denied** — and the
     * host's `pending` is cleared in the same breath, so the person's real answer, when they give
     * it, reaches nobody. Two taps on the product's one control, and voice notes are gone for the
     * life of the screen.
     *
     * Asserted on the asks, not on the state: the ask is the only thing this view model can
     * emit, and one press means one dialog.
     */
    @Test
    fun `a second press while the request is in flight does not ask twice`() = runTest(dispatcher) {
        val refused = TestRecorder(permitted = false)
        val vm = viewModel(recorder = refused)
        val asks = mutableListOf<Unit>()
        val job = launch { vm.permissionAsks.collect { asks += it } }
        advanceUntilIdle()

        vm.start()
        // Drained, so the CONFLATED channel cannot hide the second ask by coalescing it — which
        // is what makes this about the guard rather than about the channel.
        advanceUntilIdle()
        vm.start()
        advanceUntilIdle()

        assertEquals("the second press asked the system a second time: $asks", 1, asks.size)
        job.cancel()
    }

    /**
     * And the guard lifts when the answer arrives, or a refusal would be the last dialog this
     * screen ever shows — which is the same dead end from the other side.
     */
    @Test
    fun `the guard lifts once the system has answered`() = runTest(dispatcher) {
        val refused = TestRecorder(permitted = false)
        val vm = viewModel(recorder = refused)
        val asks = mutableListOf<Unit>()
        val job = launch { vm.permissionAsks.collect { asks += it } }
        advanceUntilIdle()

        vm.start()
        advanceUntilIdle()
        vm.onPermissionResult(granted = false, permanent = false)
        advanceUntilIdle()
        vm.start()
        advanceUntilIdle()

        assertEquals("asking again after a refusal was refused by the guard: $asks", 2, asks.size)
        job.cancel()
    }

    @Test
    fun `a permanent refusal is the one that offers the app settings`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onPermissionResult(granted = false, permanent = false)
        advanceUntilIdle()
        assertEquals(VoiceState.NeedsPermission, vm.state.value)

        vm.onPermissionResult(granted = false, permanent = true)
        advanceUntilIdle()
        val failed = vm.state.value as VoiceState.Failed
        assertEquals(UiAction.OPEN_APP_SETTINGS, failed.message.action)
        assertTrue(
            "the message must be the permission one, not a generic failure",
            failed.message.args.contains("microphone"),
        )
    }

    // ---- T-005: the failure path must not destroy the recording it promises to keep ----

    /** The state a failed decode leaves behind: a message on screen and a recording on disk. */
    private fun failedWithRecording(): Pair<VoiceViewModel, File> {
        val wav = File(temp, "recording.wav").apply { writeBytes(ByteArray(64)) }
        val vm = viewModel(
            VoiceViewModel.LastRecording(pcm = ShortArray(16_000) { 1 }, audioPath = wav.absolutePath),
        )
        // A permanent refusal is the cheapest way into Failed that needs no recorder.
        vm.onPermissionResult(granted = false, permanent = true)
        return vm to wav
    }

    @Test
    fun `retrying a failed transcription re-runs the same audio and keeps the file`() = runTest(dispatcher) {
        val (vm, wav) = failedWithRecording()

        vm.retryTranscription()
        advanceUntilIdle()

        assertTrue("the recording was deleted by the button that promises to keep it", wav.exists())
        assertEquals("the stored audio was not re-transcribed", 1, engine.calls)
    }

    @Test
    fun `dismissing a failure keeps the recording`() = runTest(dispatcher) {
        val (vm, wav) = failedWithRecording()

        vm.dismissFailure()
        advanceUntilIdle()

        assertTrue("dismissing a message destroyed the recording", wav.exists())
        assertEquals(VoiceState.Idle, vm.state.value)
        assertEquals("dismissing must not transcribe anything", 0, engine.calls)
    }

    @Test
    fun `discarding is the one action that deletes the recording`() = runTest(dispatcher) {
        val (vm, wav) = failedWithRecording()

        vm.cancel()
        advanceUntilIdle()

        // The behaviour that must survive: Discard exists precisely to remove a rejected recording
        // from the headset, and DEC-0011 left no confirmation step, so this is the only way out.
        assertTrue("a discarded recording survived on the device", !wav.exists())
        assertEquals(VoiceState.Idle, vm.state.value)
    }
    // --- the banner's one Retry button, and the three failures standing under it ---------------

    @Test
    fun `retry re-runs the decode when a recording is in hand`() {
        assertEquals(
            RetryRoute.TRANSCRIPTION,
            retryRoute(VoiceState.Failed(UiStateMapper.map(AppError.SttFailed("whisper"))), hasPcm = true, modelMissing = false),
        )
    }

    /**
     * The case that had an inert button: a model download fails before any recording exists, so
     * `retryTranscription()` returns at its first line and the person presses a button that does
     * nothing at all.
     */
    @Test
    fun `retry restarts the download when the model is what failed`() {
        assertEquals(
            RetryRoute.DOWNLOAD,
            retryRoute(
                VoiceState.Failed(UiStateMapper.map(AppError.ModelDownload(AppError.ModelDownload.Reason.NETWORK))),
                hasPcm = false,
                modelMissing = true,
            ),
        )
    }

    @Test
    fun `retry starts a new recording when there is nothing else to retry`() {
        assertEquals(
            RetryRoute.RECORD,
            retryRoute(VoiceState.Failed(UiStateMapper.map(AppError.Unknown(null))), hasPcm = false, modelMissing = false),
        )
    }

    @Test
    fun `retry under a running download does not start a second one`() {
        assertEquals(
            RetryRoute.NOTHING,
            retryRoute(VoiceState.Downloading(1, 2), hasPcm = false, modelMissing = true),
        )
    }

    /**
     * A-05. The guard read `KEY_WHISPER_SERVER_URL` and never looked at which provider the person
     * chose, so somebody on *Cloud service* who had never downloaded the 190 MB on-device model was
     * refused before they could speak — by a check about a whisper-server they do not use.
     */
    @Test
    fun `a cloud-only person can record without the on-device model`() = runTest(dispatcher) {
        grantMicrophone()
        val vm = viewModel(remoteReady = true, modelPresent = false)

        vm.start()
        advanceUntilIdle()

        // Asserted narrowly on purpose: whether the recorder itself starts under Robolectric is
        // not this test's subject, and a blanket "not Failed" would pass on `NeedsPermission`.
        assertFalse(
            "a configured remote was refused by a check about a server URL: ${vm.state.value}",
            refusedForMissingModel(vm.state.value),
        )
    }

    @Test
    fun `with no model and no remote the refusal still comes before the recording`() = runTest(dispatcher) {
        grantMicrophone()
        val vm = viewModel(remoteReady = false, modelPresent = false)

        vm.start()
        advanceUntilIdle()

        assertTrue(
            "nothing could have transcribed this and it recorded anyway: ${vm.state.value}",
            refusedForMissingModel(vm.state.value),
        )
    }

    private fun refusedForMissingModel(state: VoiceState): Boolean =
        state is VoiceState.Failed && state.message.action == UiAction.DOWNLOAD_MODEL

    private fun grantMicrophone() {
        org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    }

    // --- T-017: the download's three states, unreachable until the downloader became a parameter -

    /**
     * A failed download says so — and the button names **what the press will cost**
     * (`REQ-047`, `H8`). Nothing usable survived on disk here, so it is *Download again*, not
     * *Resume*: `ModelDownloader` deletes a partial it cannot resume, and offering to carry on
     * from bytes that are gone is a promise of 574 MB dressed as a promise of thirty.
     */
    @Test fun `a download failure with nothing left on disk offers a fresh download`() =
        runTest(dispatcher) {
            val vm = viewModel(downloads = FakeDownloads(listOf(
                DownloadProgress.Failed(
                    AppError.ModelDownload(AppError.ModelDownload.Reason.NETWORK),
                    resumable = false,
                ),
            )))

            vm.downloadModel()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue("a failed download said nothing: $state", state is VoiceState.Failed)
            assertEquals(UiAction.DOWNLOAD_AGAIN, (state as VoiceState.Failed).message.action)
        }

    /**
     * And the other half of `DownloadProgress.Failed.resumable`: usable bytes survived, so the
     * next press costs only the missing ones and the button says *Resume*.
     */
    @Test fun `a resumable download failure offers to resume`() = runTest(dispatcher) {
        val vm = viewModel(downloads = FakeDownloads(listOf(
            DownloadProgress.Failed(
                AppError.ModelDownload(AppError.ModelDownload.Reason.NETWORK),
                resumable = true,
            ),
        )))

        vm.downloadModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("a failed download said nothing: $state", state is VoiceState.Failed)
        assertEquals(
            "a resumable failure offered a fresh 574 MB download",
            UiAction.RESUME_DOWNLOAD,
            (state as VoiceState.Failed).message.action,
        )
    }

    @Test fun `cancelling a download returns to Idle without a message`() = runTest(dispatcher) {
        val vm = viewModel(downloads = FakeDownloads(listOf(DownloadProgress.Running(1, 100))))

        vm.downloadModel()
        advanceTimeBy(10)
        vm.cancelDownload()
        advanceUntilIdle()

        assertEquals(VoiceState.Idle, vm.state.value)
    }

    /**
     * **`T-021` reversed this deliberately**, and the old test is kept in the history rather than
     * defended: it asserted that a second *Download* cancelled the first. That behaviour is
     * `I-05` — two screens each cancelling their own job into one `.part` file, both digesting
     * only their own bytes, both failing the pinned checksum, and the model never finishing.
     */
    @Test fun `starting a download twice joins the first rather than starting a second`() = runTest(dispatcher) {
        val downloads = FakeDownloads(
            listOf(DownloadProgress.Running(1, 100), DownloadProgress.Done(File(temp, "m.bin"), "sha")),
        )
        val vm = viewModel(downloads = downloads)

        vm.downloadModel()
        advanceTimeBy(10)
        vm.downloadModel()
        advanceUntilIdle()

        assertEquals("the second Download started a second transfer of the same bytes", 1, downloads.starts)
        assertEquals("the first transfer was cancelled by the second", 0, downloads.cancels)
    }

    /**
     * The **wiring**, not the decision. Four tests drove the pure `retryRoute` and none drove
     * `retry()` — so pointing every arm of its `when` back at `retryTranscription()`, which is the
     * original defect verbatim, left the whole suite green. Found by an independent verification
     * pass; the gap is exactly the one this project keeps finding, a test at the wrong level.
     */
    @Test fun `retry actually starts the download when the model is what failed`() = runTest(dispatcher) {
        grantMicrophone()
        val downloads = FakeDownloads(listOf(DownloadProgress.Running(1, 100)))
        val vm = viewModel(downloads = downloads, modelPresent = false, remoteReady = false)
        vm.start()                 // refuses: no model, no remote
        advanceUntilIdle()
        assertTrue("the fixture did not reach the failure this is about", refusedForMissingModel(vm.state.value))

        vm.retry()
        advanceUntilIdle()

        assertEquals("Retry did not reach the downloader at all", 1, downloads.starts)
    }

    @Test fun `retry re-runs the decode when a recording is the thing that failed`() = runTest(dispatcher) {
        grantMicrophone()
        val downloads = FakeDownloads(listOf(DownloadProgress.Running(1, 100)))
        val last = VoiceViewModel.LastRecording(pcm = ShortArray(1_600), audioPath = null)
        val vm = viewModel(last = last, downloads = downloads, modelPresent = true)

        vm.retry()
        advanceUntilIdle()

        assertEquals("Retry started a download while a recording was waiting", 0, downloads.starts)
        assertEquals("the recording was not re-transcribed", 1, engine.calls)
    }

    /**
     * `C-06`'s near half. A recording the person walked away from left a `.wav` in the scratch
     * directory that nothing in the app would ever list again.
     *
     * Through `ViewModelStore.clear()` rather than by calling `onCleared()`: `clear()` is what the
     * platform does and it also cancels `viewModelScope`, and a test that calls the callback
     * directly passes with the production hook missing entirely — which is how `T-047`'s first
     * version stayed green over a planted defect.
     */
    @Test fun `an unadopted recording is deleted when the screen goes away`() = runTest(dispatcher) {
        val orphan = File(temp, "orphan.wav").apply { writeBytes(ByteArray(64)) }
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    viewModel(last = VoiceViewModel.LastRecording(audioPath = orphan.absolutePath)) as T
            },
        )[VoiceViewModel::class.java]

        store.clear()
        advanceUntilIdle()

        assertFalse("the abandoned recording is still on the headset", orphan.exists())
    }

    /**
     * The other side of it, and the one that matters more: a dictation whose commit is in flight
     * must not have its source deleted underneath it. `commitDictation` moves the file into the
     * vault on the **application** scope precisely so it outlives this screen (`I-02`); a tidy-up
     * here would turn a leak into the data loss `T-007` fixed.
     */
    @Test fun `a recording whose note is being written is not deleted`() = runTest(dispatcher) {
        grantMicrophone()
        val committing = File(temp, "committing.wav").apply { writeBytes(ByteArray(64)) }
        val last = VoiceViewModel.LastRecording(pcm = ShortArray(1_600), audioPath = committing.absolutePath)
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel(last = last) as T
            },
        )[VoiceViewModel::class.java]
        vm.retry()
        advanceUntilIdle()
        assertTrue(
            "the fixture never reached Ready, so this asserts nothing",
            vm.state.value is VoiceState.Ready,
        )

        store.clear()
        advanceUntilIdle()

        assertTrue("the commit's source file was deleted under it", committing.exists())
    }
}

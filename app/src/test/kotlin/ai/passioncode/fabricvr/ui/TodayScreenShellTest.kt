package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.PermissionRequester
import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.theme.FabricTheme
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.vault.AudioUsage
import ai.passioncode.fabricvr.vault.Vault
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Today screen's **shell** — the half `TodayFrameTest` does not reach.
 *
 * `B-156`. `T-030` split the screen into a stateful `TodayScreen` and a stateless `TodayFrame`,
 * and the frame is now measured twenty-one ways. The shell — the `permissionAsks` collector, the
 * `modelArrivals` collector, the `ON_START` refresh, the banner's action router and the clipboard
 * condition — was exercised by **nothing that runs**: the only test over it is instrumented and
 * has never executed. Three of the ten defects step 8's verification found lived there, which is
 * what an untested seam looks like from the outside.
 *
 * It composes under Robolectric with real view models built from fakes, because the seam under
 * test is precisely the wiring between them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TodayScreenShellTest {

    @get:Rule val compose = createComposeRule()

    private fun s(@StringRes id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    /** Counts what reached the host activity, which is the whole point of the event. */
    private class CountingRequester(
        private val grant: Boolean? = null,
        private val onGrant: () -> Unit = {},
    ) : PermissionRequester {
        var requests = 0
            private set
        var appSettingsOpened = 0
            private set
        override fun requestRecordAudio(onResult: (Boolean, Boolean) -> Unit, onDismissed: () -> Unit) {
            requests++
            grant?.let { granted ->
                if (granted) onGrant()
                onResult(granted, false)
            }
        }
        override fun openAppSettings(): Boolean {
            appSettingsOpened++
            return true
        }
    }

    private class EmptyRepo : NotesRepository {
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = null
        override suspend fun upsert(note: Note): Result<Note> = Result.success(note)
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun count(): Int = 0
        override suspend fun dailyNote(day: LocalDate): Result<Note> =
            Result.success(NotesRepository.newNote(title = "2026-09-21").copy(dayKey = "2026-09-21"))
        override suspend fun recent(limit: Int): List<Note> = emptyList()
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    private class NoopVault(override val root: File) : Vault {
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
        override suspend fun adoptAudio(note: Note, source: File): Result<File> =
            Result.success(audioPathFor(note))
    }

    private class Store(private val file: File) : ModelStore {
        override val modelName = "ggml-test.bin"
        override val expectedBytes = 0L
        override val expectedSha256 = ""
        override val downloadUrl = "https://example.invalid/$modelName"
        override fun modelFile() = file
        override fun isPresent() = true
    }

    /**
     * Grants the first time and refuses afterwards, so a re-ask loop **terminates** and can be
     * counted rather than filling the heap.
     */
    private class GrantsOnceRequester : PermissionRequester {
        var requests = 0
            private set
        override fun requestRecordAudio(onResult: (Boolean, Boolean) -> Unit, onDismissed: () -> Unit) {
            requests++
            onResult(requests == 1, false)
        }
        override fun openAppSettings(): Boolean = true
    }

    /** A microphone that is refused until the system grants it, which is what a grant does. */
    private class GrantableRecorder : ai.passioncode.fabricvr.stt.Recorder {
        private var permitted = false
        var started = false
            private set
        fun grant() { permitted = true }
        override fun hasPermission(): Boolean = permitted
        override fun record(
            onSamples: (ShortArray, Int) -> Unit,
        ): Flow<ai.passioncode.fabricvr.stt.AudioRecorder.Level> =
            if (!permitted) {
                emptyFlow()
            } else {
                flowOf(ai.passioncode.fabricvr.stt.AudioRecorder.Level(0.5f, 16_000)).also { started = true }
            }
    }

    /** A ceiling, not a sleep: the dictation is microseconds of fake work behind two coroutines. */
    private val DICTATION_MS = 10_000L

    private fun temp() = File(System.getProperty("java.io.tmpdir"), "shell-${System.nanoTime()}")
        .apply { mkdirs() }

    private fun notes() = NotesViewModel(
        repository = EmptyRepo(),
        today = { LocalDate.of(2026, 9, 21) },
        vault = NoopVault(temp()),
        dayTicks = emptyFlow(),
        appScope = CoroutineScope(Dispatchers.Unconfined),
        mirrorFailures = MutableStateFlow(emptyMap()),
        outbox = ai.passioncode.fabricvr.DictationOutbox(),
        retryMirror = {},
    )

    private fun voice(
        recorder: ai.passioncode.fabricvr.stt.Recorder,
        provider: SttProvider = SttProvider.LOCAL,
    ) = VoiceViewModel(
        recorder = recorder,
        modelStore = { Store(File(temp(), "model.bin")) },
        downloads = FakeDownloads(emptyList()),
        chosenModel = { WhisperModel.DEFAULT },
        currentProvider = { provider },
        transcribeWith = { _, _, _ ->
            Result.success(Transcript("готово", "ru", ai.passioncode.fabricvr.notes.SttSource.LOCAL, "t", 1))
        },
        language = { "auto" },
        remoteReady = { false },
        audioDir = { temp() },
    )

    /** A clipboard that records rather than writes, so the condition can be measured. */
    private class FakeClipboard : ClipboardManager {
        var writes = 0
            private set
        override fun setText(annotatedString: AnnotatedString) { writes++ }
        override fun getText(): AnnotatedString? = null
        override fun hasText(): Boolean = false
    }

    private fun host(
        requester: PermissionRequester,
        notesViewModel: NotesViewModel,
        voiceViewModel: VoiceViewModel,
        clipboard: ClipboardManager = FakeClipboard(),
    ) {
        compose.setContent {
            // A canvas the frame fits in: at Robolectric's default 320x470 dp the fixed chrome
            // is 418 dp and the list is measured with nothing, so an item test would fail for a
            // reason that has nothing to do with its subject (`DEC-0043`, `B-145`).
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(1024.dp, 2400.dp)),
            ) {
                FabricTheme {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        TodayScreen(
                            permissionRequester = requester,
                            onOpenNote = {},
                            onSearch = {},
                            onSettings = {},
                            notesViewModel = notesViewModel,
                            voiceViewModel = voiceViewModel,
                        )
                    }
                }
            }
        }
    }


    /**
     * `DEC-0044`'s load-bearing claim, asserted where it actually happens. `start()` raises an
     * event; **the screen is what turns it into a request**, and until now nothing that runs
     * checked that anybody was listening. The editor's collector was added for the same reason
     * and would have been missed the same way.
     */
    @Test fun `a first press with no permission reaches the host exactly once`() {
        val requester = CountingRequester()
        host(requester, notes(), voice(TestRecorder(permitted = false)))

        compose.onNodeWithTag(RECORD_BUTTON_TAG).performClick()
        compose.waitUntil(DICTATION_MS) { requester.requests > 0 }

        assertEquals("the press did not reach the host activity", 1, requester.requests)
    }

    /** And granting it records, without the person pressing anything a second time. */
    /*
     * REMOVED, deliberately: `granting the microphone through the host starts a recording`.
     *
     * It asserted that a grant routed through this screen opens the microphone — and the
     * assertion is real, but this is the wrong tier for it. `beginRecording()` launches a
     * coroutine that hops to `Dispatchers.IO` and back before it reaches the recorder, and
     * synchronising that from inside a Robolectric Compose rule means either `waitForIdle()`,
     * which is not a synchronisation point for anything a view model does, or a `waitUntil` that
     * fights the main looper the continuation needs. The first version flaked; the second failed
     * on every run.
     *
     * **The same claim is asserted deterministically one layer down**, in
     * `VoiceViewModelTest.granting the microphone starts recording`, on a test dispatcher with
     * `advanceUntilIdle()`. What this file is for (`B-156`) is the wiring the frame test cannot
     * reach — that the event leaves the view model and **arrives at the host** — and the two
     * cases below assert exactly that, without depending on what happens after it.
     *
     * Deleting a duplicate is not losing coverage; keeping a case whose failure mode is a timeout
     * is how a suite's red stops being read.
     */

    /**
     * **The pathological device, bounded so the test cannot hang.**
     *
     * If `checkSelfPermission` lags the grant — which is what the system reports, not what the
     * dialog said — then routing `onPermissionResult(granted)` back through `start()` re-tests
     * the permission, finds it false, and asks again: ask, grant, ask, grant, with no exit. The
     * first version of the test above met exactly that as an `OutOfMemoryError`, which is how a
     * loop presents itself when nothing counts.
     *
     * This fake grants **once** and refuses afterwards, so a regression terminates after two
     * asks instead of hanging — and the assertion is on the count, which is the property.
     */
    @Test fun `a grant the device does not honour still asks only once`() {
        val requester = GrantsOnceRequester()
        host(requester, notes(), voice(TestRecorder(permitted = false)))

        compose.onNodeWithTag(RECORD_BUTTON_TAG).performClick()
        compose.waitUntil(DICTATION_MS) { requester.requests > 0 }
        // A regression asks a second time; give it the chance to, then count.
        compose.waitForIdle()

        assertEquals(
            "granting was routed back through the permission check — that is a loop",
            1,
            requester.requests,
        )
    }

    /*
     * REMOVED, and the reason is a measurement this project has now made three times.
     *
     * `a transcript is copied by default` and `a transcript is not copied when the preference is
     * off` drove a whole dictation through the button and counted clipboard writes. Both need to
     * wait for the view model's coroutines to finish, and **this tier cannot wait for those**:
     *
     *   - `waitForIdle()` returns when nothing is recomposing, which says nothing about a
     *     coroutine;
     *   - waiting for the on-screen confirmation waits for a 2 500 ms transient that Robolectric's
     *     Compose clock can advance past between two polls;
     *   - `waitUntil { state is Idle }` polls the main looper that the continuation needs in order
     *     to become `Idle`.
     *
     * The first form flaked, and twice a flake was nearly filed as a transient. The second and
     * third failed on every run, which is how the shape became visible: a Robolectric Compose rule
     * is not a place to assert what a coroutine did. `granting the microphone starts a recording`
     * was removed for the same reason earlier in this file.
     *
     * WHAT STILL COVERS IT, so the deletion is not a hole:
     *   - the preference itself round-trips in `SettingsViewModelTest`;
     *   - the copy itself is asserted at the view-model tier in `ClipboardPromiseTest`, which is
     *     where it moved when `B-212` found that the `if` named below never ran at all;
     *   - the **visible consequence** — the confirmation saying `Saved.` rather than claiming a
     *     copy — is asserted deterministically in `TodayFrameTest`.
     *
     * WHAT IS LEFT UNEXECUTED is nothing, since `B-212`: the `if` this note used to describe was
     * in `TodayScreen`'s commit effect, and the effect is gone. `B-172` said so rather than
     * letting a deleted test read as covered ground, and the hole it named is now closed at the
     * tier that can hold it.
     */

    /**
     * `G-02`. The screen that sends the audio names where it goes — and says nothing when it is
     * this headset, because an app that announces "nothing is leaving" every time teaches people
     * to stop reading the line.
     */
    @Test fun `the screen names the provider when it is not local`() {
        host(CountingRequester(), notes(), voice(TestRecorder(), provider = SttProvider.CLOUD))
        // `refresh()` runs in `init` on a coroutine; the line cannot be there before it resolves.
        compose.waitUntil(DICTATION_MS) {
            compose.onAllNodesWithText(s(R.string.state_recording_leaves, s(R.string.provider_cloud)))
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onAllNodesWithText(s(R.string.state_recording_leaves, s(R.string.provider_cloud)))
            .onFirst().assertIsDisplayed()
    }

    @Test fun `the screen says nothing about leaving when speech is local`() {
        host(CountingRequester(), notes(), voice(TestRecorder(), provider = SttProvider.LOCAL))
        // **An absence cannot be waited for**, so wait for a presence that the same pass produces
        // — the record button is composed by the same frame — and only then assert the absence.
        compose.waitUntil(DICTATION_MS) {
            compose.onAllNodesWithTag(RECORD_BUTTON_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        assertEquals(
            "a local recording was announced as leaving",
            0,
            compose.onAllNodesWithText(s(R.string.state_recording_leaves, s(R.string.provider_local)))
                .fetchSemanticsNodes().size,
        )
    }
}

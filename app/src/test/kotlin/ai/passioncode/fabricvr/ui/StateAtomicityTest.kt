package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.vault.AudioUsage
import ai.passioncode.fabricvr.vault.Vault
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CyclicBarrier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * **A state holder is updated, never read and written back** (`B-211`).
 *
 * `MutableStateFlow.value = value.copy(…)` is a read, a construction and a write with nothing
 * joining them. Two writers that overlap inside that gap both read the same object and the second
 * write discards the first one's field — and this app has writers on **two dispatchers at once**:
 * `viewModelScope` is `Dispatchers.Main.immediate` and `Graph.scope` is `Dispatchers.Default`, and
 * `NotesViewModel` writes from both (`:329,340,374,583` against `:482,569,708-723,756` at the
 * commit this was found in). The fields at risk are the ones that carry a recovery control:
 * `pendingDictation` and the `RETRY_DICTATION` message beside it, which is the offer to write a
 * dictation whose row would not save. `MutableStateFlow.update` is the same read and write under a
 * compare-and-set that retries, so no interleaving can lose either field.
 *
 * The project had already diagnosed this class and written the rule in `VaultMirror`'s own KDoc
 * (`I-06`); what it had not done is apply it here, and 84 call sites is not a thing anybody
 * re-checks by reading. Hence two tests of two different kinds:
 *
 *  - the **gate** below is decidable and deterministic, and it is the one that catches the
 *    eighty-fifth write, written next week by somebody who has not read this file;
 *  - the **race** is the behaviour itself, driven through a barrier rather than a sleep. It is
 *    green by construction after the fix — a compare-and-set cannot lose a field — and it is what
 *    says the gate is about something real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateAtomicityTest {

    /**
     * **The READ half, on its own line.** `x.value.copy(` is a state holder read in order to be
     * written back, and the write is the assignment somewhere above it — which may be several
     * lines up.
     *
     * The first version of this scan matched the whole statement,
     * `(\w+)\.value\s*=\s*\1\.value\.copy\(`, and that is the spelling this tree happened to use
     * 84 times. It missed three more in `SettingsViewModel.downloadWatch`, where the assignment is
     * `_state.value = when (progress) {` and each arm reads `_state.value.copy(` two lines below —
     * the same defect, wearing a `when`. A gate that catches one spelling of a defect catches no
     * defect (`check-seams.sh` records the same lesson from the other side), so the rule is the
     * read: inside `update { it.copy(…) }` the lambda's own parameter is what a correct rewrite
     * reads, never `.value` again.
     *
     * A blind write (`_state.value = VoiceState.Idle`) is deliberately **not** matched: it takes
     * nothing from the old value, so there is nothing for a concurrent writer to lose.
     */
    private val readModifyWrite = Regex("""\w+\.value\.copy\(""")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "no settings.gradle.kts above ${File("").absolutePath}" }
    }

    private fun sources(): List<File> =
        File(repoRoot(), "app/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    // ---- the gate --------------------------------------------------------------------------

    @Test fun `no state holder is updated by reading it and writing it back`() {
        val offenders = sources().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> readModifyWrite.containsMatchIn(line) }
                .map { (i, line) -> "${file.relativeTo(repoRoot())}:${i + 1}: ${line.trim()}" }
        }

        assertEquals(
            "a state holder is read and written back, which loses a field to a concurrent " +
                "writer — use `update { it.copy(…) }`:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The other half, and the one this project keeps having to relearn: a scanner that matches
     * nothing prints the same silence as a clean tree (`SI-06`). The regex is exercised against a
     * planted write **and** against the two shapes that must not red — a blind write and an
     * `update` — because a gate that reds on correct code gets switched off rather than obeyed.
     */
    @Test fun `the scanner sees a planted read-modify-write and passes the correct shapes`() {
        assertTrue(
            "the scanner cannot see the defect it exists for",
            readModifyWrite.containsMatchIn("        _state.value = _state.value.copy(message = null)"),
        )
        assertTrue(
            "a receiver other than `_state` evades the scan",
            readModifyWrite.containsMatchIn("        _ui.value = _ui.value.copy(loading = false)"),
        )
        assertTrue(
            "the `when` spelling evades the scan — the arm is on its own line",
            readModifyWrite.containsMatchIn("                        _state.value.copy(downloading = null)"),
        )
        assertTrue(
            "a blind write was reported — the gate reds on correct code",
            !readModifyWrite.containsMatchIn("        _state.value = VoiceState.Idle"),
        )
        assertTrue(
            "an atomic update was reported — the gate reds on the fix it asks for",
            !readModifyWrite.containsMatchIn("        _state.update { it.copy(message = null) }"),
        )
        assertTrue(
            "a named `update` parameter was reported — the same fix, spelled out",
            !readModifyWrite.containsMatchIn("        _state.update { state -> state.copy(total = 0) }"),
        )
    }

    // ---- the race --------------------------------------------------------------------------

    /** Main has to exist because the view model's `init` launches on it; nothing here runs on it. */
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    /** Real threads. The point of this case is two dispatchers, which one dispatcher cannot show. */
    private val appScope = CoroutineScope(Dispatchers.Default)

    private val root: File =
        File(System.getProperty("java.io.tmpdir"), "atomicity-${System.nanoTime()}").apply { mkdirs() }

    @After fun tearDown() {
        appScope.cancel()
        root.deleteRecursively()
    }

    /**
     * Refuses every write, and **parks inside the refusal** until the test thread arrives.
     *
     * The barrier is what makes this a race rather than a hope: both sides leave it within
     * nanoseconds of each other, so the background write of `pendingDictation` and the foreground
     * write of `spaceStarting` land in the same window. A `Thread.sleep` would be the other way to
     * write this and it would be both slower and weaker.
     */
    private class BarrierRepo(val barrier: CyclicBarrier) : NotesRepository {
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = null
        override suspend fun upsert(note: Note): Result<Note> {
            barrier.await()
            return Result.failure(IllegalStateException("disk full"))
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

    private class NoVault(override val root: File) : Vault {
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
            Result.failure(IllegalStateException("no audio in this case"))
    }

    /**
     * **`pendingDictation` and `spaceStarting`, written from two dispatchers at the same instant.**
     *
     * `commitDictation` runs on the application scope — real threads here, because that is the
     * production shape — and writes `pendingDictation` when the row will not save. `spaceStarting`
     * is written by a tap, on the caller's thread. They are disjoint fields of one object, so
     * neither may erase the other; with a read-modify-write one of them does, and the one that
     * usually loses is the recovery control.
     */
    @Test fun `a background write and a foreground write do not lose each other`() {
        val barrier = CyclicBarrier(2)
        val repo = BarrierRepo(barrier)
        val vm = NotesViewModel(
            repository = repo,
            today = { LocalDate.of(2026, 9, 22) },
            vault = NoVault(root),
            dayTicks = emptyFlow(),
            appScope = appScope,
            mirrorFailures = MutableStateFlow(emptyMap<String, AppError>()),
            retryMirror = {},
            outbox = ai.passioncode.fabricvr.DictationOutbox(),
            awaitReconcile = {},
            copyTranscriptSetting = { false },
            copyToClipboard = {},
        )
        val losses = mutableListOf<String>()

        repeat(ROUNDS) { round ->
            vm.panelShown()
            val transcript = Transcript("round-$round", "ru", SttSource.LOCAL, "whisper-base", 900)

            vm.commitDictation(transcript, null)
            val commit = appScope.coroutineContext.job.children.toList()
            barrier.await()
            vm.spaceStarting()
            runBlocking { commit.forEach { it.join() } }

            val state = vm.state.value
            if (state.pendingDictation?.body != "round-$round") {
                losses += "round $round lost the dictation the retry needs"
            } else if (!state.spaceStarting) {
                losses += "round $round lost the tap that said the Space was starting"
            }
        }

        assertEquals(
            "a write was read and written back, so a concurrent one was discarded " +
                "(${losses.size} of $ROUNDS rounds):\n" + losses.take(5).joinToString("\n"),
            emptyList<String>(),
            losses,
        )
    }

    private companion object {
        /**
         * Enough rounds for the window to be hit, not so many that the case costs a minute. The
         * planted read-modify-write lost a field within the first few hundred on this machine;
         * the number is a margin, not a measurement.
         */
        const val ROUNDS = 20_000
    }
}

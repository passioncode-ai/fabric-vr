package ai.passioncode.fabricvr.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.passioncode.fabricvr.Cue
import ai.passioncode.fabricvr.DictationOutbox
import ai.passioncode.fabricvr.FeedbackCues
import ai.passioncode.fabricvr.Graph
import ai.passioncode.fabricvr.PendingDictation
import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.UiMessage
import ai.passioncode.fabricvr.common.UiStateMapper
import ai.passioncode.fabricvr.common.toAppError
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NotesException
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.common.runCatchingCancellable
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WavWriter
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.vault.Vault
import ai.passioncode.fabricvr.vault.adoptOrKeep
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Waits for the next local midnight, emits the new day, and does it again. */
private fun midnightTicks(): Flow<LocalDate> = flow {
    while (true) {
        val now = LocalDateTime.now()
        val untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay())
        delay(untilMidnight.toMillis().coerceAtLeast(1_000))
        emit(LocalDate.now())
    }
}

/** What a re-run did, because "done" and "done but your edit is untouched" are different news. */
enum class RetranscribeResult { Replaced, TranscriptOnly, NoRecording, Failed }

/**
 * A dictation that is now a row in the database (`REQ-046`, `DEC-0012`).
 *
 * @param copied whether the words actually reached the clipboard. **Not the preference** — the
 *   confirmation used to be filled from the setting, so it claimed a copy whenever the switch was
 *   on, including when the copy never ran at all (`B-212`). A false confirmation is worse than
 *   none.
 * @param source so the screen can say the words came from the headset after the chosen provider
 *   refused, which `SttRouter` has always recorded and no surface could see.
 */
data class DictationCommitted(val copied: Boolean, val source: SttSource)

data class NotesUiState(
    val loading: Boolean = true,
    val notes: List<Note> = emptyList(),
    /**
     * How many notes exist, against [notes].size, which is at most the window.
     *
     * The list is bounded since `T-026` and a clipped list has to **say so**: showing 200 of 400
     * with nothing on screen to indicate it is the same failure as a search that finds fewer
     * things than it counted. The row that says it belongs to `T-030`, which rebuilds this
     * surface; the state it needs is here.
     */
    val total: Int = 0,
    val tags: List<String> = emptyList(),
    val selectedTag: String? = null,
    val today: Note? = null,
    val dayLabel: String = LocalDate.now().toString(),
    val spaceStarting: Boolean = false,
    /** The id of the note whose recording is being transcribed again, if any. */
    val retranscribing: String? = null,
    /**
     * What the last *Transcribe again* did, held here rather than in a `remember` (`B-190`).
     * It lived in the composition, which is also where the decode was launched from — so leaving
     * the screen cancelled the work AND lost the answer. Cleared by [NotesViewModel.retranscribedShown].
     */
    val retranscribed: RetranscribeResult? = null,
    /** True while a *New note* press is being written, so a second press writes nothing. */
    val creatingNote: Boolean = false,
    /** The last note deleted, held so it can be put back. */
    val justDeleted: Note? = null,
    /**
     * A dictation whose row would not write. Its recording is already in the vault under this
     * note's id, so a retry is one write and no second move.
     */
    val pendingDictation: Note? = null,
    val message: UiMessage? = null,
)

class NotesViewModel(
    private val repository: NotesRepository = Graph.notes,
    private val today: () -> LocalDate = LocalDate::now,
    private val vault: Vault = Graph.vault,
    /**
     * Emits at every day boundary. It is a parameter rather than a loop inside `init` because an
     * endless loop started at construction is a view model no test can build: the test scheduler
     * advances virtual time to the next delay forever, so the suite hangs instead of failing.
     */
    private val dayTicks: Flow<LocalDate> = midnightTicks(),
    /**
     * Transcription for one explicit choice of provider and model, as one call.
     *
     * A parameter rather than a reach into [Graph] from inside [retranscribe], because `Graph` is
     * an `object` with a `lateinit` context: a test cannot construct a second one and cannot reset
     * the one there is, so every branch of the most destructive method in this class — the one that
     * can overwrite a person's edited text — was unreachable by any test. It takes the whole call
     * rather than the engine, so `withEngine`'s contract that a non-selected engine is closed when
     * the block returns stays inside `Graph`, where it is written down.
     */
    private val transcribeWith: suspend (SttProvider, WhisperModel, ShortArray, String?) -> Result<Transcript> =
        { provider, model, pcm, langHint ->
            Graph.withEngine(provider, model) { engine -> engine.transcribe(pcm, langHint = langHint) }
        },
    private val sttLanguage: suspend () -> String = Graph::sttLanguage,
    /**
     * Where a dictation's commit runs. **Not `viewModelScope`, and never the composition's.**
     * The audio is moved into the vault before the row is written; a commit cancelled between the
     * two leaves the recording orphaned under an id no row carries, and returning to the screen
     * re-fires the effect against a scratch file that has already gone — a note pointing at
     * nothing. A commit must outlive the screen that started it (`I-02`).
     */
    private val appScope: CoroutineScope = Graph.scope,
    /**
     * Which notes are not in the vault, from the one mirror in the process.
     *
     * `D-24`: this count lived in Settings **alone**. `SCN-011` says the failure is shown *where
     * it happened* — notes stop being written to files and the person, who has no reason to open
     * Settings, never finds out; after `T-023` that is also the moment their export silently
     * stops being complete. Correcting the scenario to match the build was the other option and
     * is the one this task exists to refuse.
     */
    private val mirrorFailures: StateFlow<Map<String, AppError>> = Graph.vaultMirror.failures,
    /**
     * Re-writes every note the mirror still owes, which is what the banner's *Retry* means.
     *
     * `SCN-011` says the failure is shown once **with Retry**, and the first version of this
     * banner offered *Settings* instead — a different control from the one the scenario names,
     * in a change whose own decision record says correcting the scenario to match the build is
     * the thing it refuses. `VaultMirror.retryFailed()` has existed the whole time.
     */
    private val retryMirror: suspend () -> Unit = { Graph.vaultMirror.retryFailed() },
    /**
     * Where a finished dictation waits for a note (`REQ-046`, audit `H1`).
     *
     * **Every host drains this, and the drain is what makes a dictation survive its host.** The
     * commit used to be started by a `LaunchedEffect` in the composition that produced the
     * transcript — so leaving the Space mid-dictation left nobody to write the note, and the
     * panel's own `NotesViewModel` had no way to know there was one owed. Both hosts can be
     * alive at once, so the hand-off is `DictationOutbox.claim`, which answers exactly one
     * caller.
     */
    private val outbox: DictationOutbox = Graph.dictationOutbox,
    /**
     * Waits for the startup reconcile before the first read (`REQ-055`, audit `H6`).
     *
     * `Graph.init` launched the vault import and never awaited it, so `dailyNote(today)` could
     * win the race, create a note for a day the vault already had one for, and the import's copy
     * would then be demoted — a duplicate for today on the launch after a restore. A suspend
     * lambda rather than the `Deferred` itself so a test constructs this view model without
     * `Graph`; before [Graph.init] it is a no-op, which is exactly what a test sees.
     */
    private val awaitReconcile: suspend () -> Unit = { Graph.reconciled?.await() },
    /**
     * The fourth of `REQ-062`'s four moments: the note reached the database.
     *
     * Here rather than in the composition that draws *"Saved, and copied"*, because the commit
     * is the thing that happened — and since `REQ-046` it can happen with no composition alive
     * at all. It fires on success only: a write that failed is not a save and must not sound
     * like one.
     */
    private val cues: FeedbackCues = Graph.feedbackCues,
    /**
     * Whether a committed dictation goes to the system clipboard (`DEC-0012`, `G-02`).
     *
     * Read inside the commit rather than cached at construction: the switch lives in Settings and
     * a person who turns it off expects the next dictation to obey, and this read is already off
     * the drawing thread, behind a database write, on a path where a few milliseconds of Keystore
     * are invisible. That is the opposite trade from the one the *screen* made when the copy lived
     * there, and it is the right one on this side of the seam.
     */
    private val copyTranscriptSetting: suspend () -> Boolean = Graph::copyTranscript,
    /**
     * Puts the words on the system clipboard (`DEC-0012`, `B-212`).
     *
     * A lambda rather than a `Context`, because a view model must not hold a Compose local and a
     * JVM test must be able to count copies without an Android clipboard. `Dispatchers.Main`
     * because `ClipboardManager` is a main-thread API on the framework side and this commit runs
     * on `Graph.scope`, which is `Dispatchers.Default`.
     */
    private val copyToClipboard: suspend (String) -> Unit = { text ->
        withContext(Dispatchers.Main) { Graph.appContext.copyPlainText(text) }
    },
    /**
     * Where the blocking read of a recording happens (`DEC-0031`, R2).
     *
     * A parameter for the reason `VoiceViewModel` already takes one: a real `Dispatchers.IO` hop
     * is invisible to a test scheduler, so `advanceUntilIdle` returns while the work is still on
     * another thread and the assertion reads a state that has not been written yet. A test that
     * passes its own dispatcher is deterministic; one that polls and sleeps is `SI-05` again.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /**
     * **The dictation reached the database, and here is what happened to the clipboard.**
     *
     * An event rather than a field of [NotesUiState], and the only one on this view model
     * (`B-211`, `B-212`). The confirmation it drives — *"Saved, and copied"* — is true of an
     * instant and of nothing afterwards, so a screen that arrives later must not find it waiting:
     * `replay = 0` means a commit nobody was watching is simply not announced, which is the honest
     * answer for a transient. [NotesUiState.retranscribed] is deliberately **not** this shape; see
     * [retranscribedShown].
     */
    private val _dictationCommitted = MutableSharedFlow<DictationCommitted>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val dictationCommitted: SharedFlow<DictationCommitted> = _dictationCommitted.asSharedFlow()

    private val _state = MutableStateFlow(NotesUiState())
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

    private val tagFilter = MutableStateFlow<String?>(null)

    /**
     * How many notes the list asks for. Raised by [showAll], never lowered — somebody who asked
     * to see everything has not asked to stop seeing it.
     */
    private val window = MutableStateFlow(NotesRepository.DEFAULT_WINDOW)
    private var observer: Job? = null

    /**
     * The dictation currently being committed, so the same one is never committed twice.
     *
     * `LaunchedEffect(voice)` re-enters whenever the screen comes back, and the state is still
     * `Ready` until the commit succeeds — without this, leaving and returning mid-commit writes
     * the note twice and moves an already-moved file.
     *
     * `@Volatile` because it is written from `Graph.scope`, which is `Dispatchers.Default`, and
     * read on the caller's thread. Without it nothing publishes the `null` back and the guard can
     * latch permanently or be skipped. Every test runs on one test dispatcher, so the suite cannot
     * reach the race — it was found by an independent verification pass reading the code.
     */
    @Volatile
    private var committing: Transcript? = null

    /**
     * The outbox entry a failed commit still owes a [DictationOutbox.settle] to (`B-237`).
     *
     * [NotesUiState.pendingDictation] is the NOTE the banner's *Retry* writes; this is the entry on
     * disk that the same write ends. Without it a successful retry wrote the row and left the file,
     * and the next launch wrote the dictation again.
     */
    @Volatile
    private var unsettled: PendingDictation? = null

    /**
     * The id of today's note, so the list can leave it to the day card.
     *
     * A flow rather than a read of `_state`, because the card and the list arrive on different
     * coroutines: `loadDailyNote()` resolves after the first list emission on a cold start, and
     * without re-filtering when it lands the row would appear and then quietly vanish.
     */
    private val dailyNoteId = MutableStateFlow<String?>(null)

    // **Declared above `init` on purpose.** Kotlin runs property initialisers and `init` blocks
    // in source order, and `observe()` reads this flow — below the block it is still `null` when
    // the block runs, and `combine` fails with an NPE that arrives as "Something failed:
    // NullPointerException" on the person's screen. Watched happening, once.

    init {
        observe()
        loadDailyNote()
        watchMirror()
        drainOutbox()
        // The date is state, not a call in the composition: a panel left open overnight used to
        // keep yesterday's heading and yesterday's daily note.
        launchGuarded {
            dayTicks.collect {
                _state.update { it.copy(dayLabel = today().toString()) }
                loadDailyNote()
            }
        }
    }

    /**
     * The first mirror failure of an episode reaches this screen, and only the first.
     *
     * **Once per episode, not once per note.** The map re-emits on every failure and every
     * recovery, and a banner that returned on each of them would be a screen a person stops
     * reading — which is the same defect as not showing it, arrived at from the other side. The
     * flag resets when the map empties, so a *later* outage is announced again.
     *
     * It never overwrites a message already on screen: a storage failure the person is looking at
     * is more urgent than a mirror one, and not marking it announced means the next emission
     * tries again rather than the news being lost.
     */
    private fun watchMirror() {
        launchGuarded {
            mirrorFailures.collect { failures ->
                if (failures.isEmpty()) {
                    mirrorAnnounced = false
                    mirrorPending = false
                    return@collect
                }
                if (mirrorAnnounced) return@collect
                announceMirrorFailure()
            }
        }
    }

    /** True once this episode's banner has been shown, until the mirror recovers. */
    private var mirrorAnnounced = false

    /**
     * True when an episode was waiting for the screen to be free.
     *
     * **The reason this field exists is a defect the first version shipped with.** The guard read
     * *"if a message is already up, do nothing and let the next emission try again"* — and there
     * is no next emission: `VaultMirror.failures` is a `StateFlow`, so one note failing
     * repeatedly with an equal error emits **once**. An episode that arrived while any other
     * message was on screen was therefore lost for the life of the process, which is `D-24`
     * reinstated. Found by the unit tier of step 8's verification.
     */
    private var mirrorPending = false

    private fun announceMirrorFailure() {
        // Never over-writes a message the person is already reading — a storage failure they are
        // looking at is more urgent — but the news is held rather than dropped.
        if (_state.value.message != null) {
            mirrorPending = true
            return
        }
        mirrorAnnounced = true
        mirrorPending = false
        mirrorMessageUp = true
        _state.update { it.copy(
            // `RETRY_LOAD` is the member this screen answers with `retry()`, and `retry()` knows
            // from `mirrorMessageUp` that a mirror episode is what it is retrying. The mirror is
            // not a fourth member because the three `REQ-047` names are the three a person can
            // tell apart from the sentence; see `retry()`.
            message = UiMessage(R.string.error_vault_not_mirrored, action = UiAction.RETRY_LOAD),
        ) }
    }

    /** Whether the message on screen is the mirror's, so *Retry* retries the right thing. */
    private var mirrorMessageUp = false

    /** Whatever cleared the slot, a held mirror episode takes it. */
    private fun releaseMirrorSlot() {
        if (mirrorPending) announceMirrorFailure()
    }

    private fun observe() {
        observer?.cancel()
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        observer = combine(
            combine(tagFilter, window) { tag, limit -> tag to limit }
                .flatMapLatest { (tag, limit) -> repository.observeNotes(tag, limit) },
            repository.observeTags(),
            dailyNoteId,
        ) { notes, tags, todayId -> Triple(notes, tags, todayId) }
            .onEach { (notes, tags, todayId) ->
                // **Today's note is drawn once, as the day card** — it used to be drawn twice.
                // `loadDailyNote()` CREATES the row (`getOrCreateByDay` inserts), `observeAll` is
                // an unfiltered `SELECT *`, and `T-028` then put a card above the list showing the
                // same note. Worse, `notes` was therefore never empty from the first launch
                // onward, so `T-031`'s two empty states — the product's only onboarding — could
                // not be selected at all. Both halves were found by step 8's verification, by two
                // tiers independently.
                // **Only when the card is the thing showing it.** Under a tag filter the card is
                // not rendered — it shows today unfiltered, which is not what the person asked
                // for — so the list must carry today's note itself or a matching note would
                // simply be missing.
                val carded = todayId != null && tagFilter.value == null
                val listed = if (carded) notes.filterNot { it.id == todayId } else notes
                _state.update { it.copy(
                    loading = false,
                    notes = listed,
                    tags = tags,
                    selectedTag = tagFilter.value,
                ) }
                // After the list, not with it: the count is a separate query and making the
                // list wait for it would trade a bounded read for a slower first paint. The day
                // card's note is subtracted here too, or "and N more" would offer one that is
                // already on screen.
                val counted = repository.count() - (if (carded) 1 else 0)
                _state.update { it.copy(total = counted.coerceAtLeast(listed.size)) }
            }
            // **Below the `onEach`, not above it** (`B-214`). `catch` sees only what is upstream of
            // it, and it sat one line above the operator that calls `repository.count()` — a
            // suspend query that moved in here after the guard was written. So a SQLite failure in
            // the count walked out of `launchIn(viewModelScope)` with nothing between it and the
            // process's uncaught handler, while the screen went on saying it was loading.
            // `DEC-0084` leaves `launchIn(viewModelScope)` outside `launchGuarded` precisely
            // because a flow's failures belong to its own `catch`; that only holds while the
            // `catch` is the last thing in the chain.
            .catch { failure -> _state.update { it.copy(loading = false, message = failure.toMessage()) } }
            .launchIn(viewModelScope)
    }

    /**
     * Writes a note from whatever dictation is waiting, whichever host produced it (`REQ-046`).
     *
     * **Held entries are skipped.** The editor appends a dictation to the note already open and
     * holds the entry while it is composed; releasing the hold — which its view model does when
     * it is cleared, host death included — is what hands the words to this drain instead. So the
     * append is attempted while the surface for it exists, and the words become a note of their
     * own the moment it does not.
     *
     * `claim` is a compare-and-set, so the panel's view model and the Space's can both collect
     * this flow and exactly one of them writes the note.
     */
    private fun drainOutbox() {
        launchGuarded {
            outbox.pending.collect { entry ->
                if (entry == null || entry.holder != null) return@collect
                val claimed = outbox.claim(entry.id) ?: return@collect
                commitDictation(claimed.transcript, claimed.audioPath, claimed)
            }
        }
    }

    private fun loadDailyNote() {
        launchGuarded {
            // The reconcile first (`REQ-055`, `H6`): creating today's note before the vault has
            // been read back is how a restore produced two notes for one day.
            runCatchingCancellable { awaitReconcile() }
            repository.dailyNote(today()).fold(
                onSuccess = { note ->
                    _state.update { it.copy(today = note, dayLabel = today().toString()) }
                    dailyNoteId.value = note.id
                },
                onFailure = { failure -> _state.update { it.copy(message = failure.toMessage()) } },
            )
        }
    }

    fun selectTag(tag: String?) { tagFilter.value = tag }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
        mirrorMessageUp = false
        releaseMirrorSlot()
    }

    /** A real retry: the observation is restarted and the daily note re-read, not just the text cleared. */
    fun retry() {
        // **The mirror's Retry retries the mirror.** Re-reading the list would clear a message
        // about files that are still not being written, which is the shape of a control that
        // looks like it worked.
        if (mirrorMessageUp) {
            mirrorMessageUp = false
            _state.update { it.copy(message = null) }
            launchGuarded {
                runCatchingCancellable { retryMirror() }
                releaseMirrorSlot()
            }
            return
        }
        _state.update { it.copy(message = null, loading = true) }
        observe()
        loadDailyNote()
    }

    fun spaceStarting() { _state.update { it.copy(spaceStarting = true, message = null) } }

    fun spaceFailed(cause: Throwable) {
        _state.update { it.copy(
            spaceStarting = false,
            // **`RETRY_SPACE`, and it used to be the generic one** (`REQ-047`, `H3`). Today's
            // banner answered `RETRY` with `retry()` — a list reload — so the one control under
            // *"The space could not start"* re-read the notes and never tried the Space again.
            message = UiMessage(R.string.state_space_failed, action = UiAction.RETRY_SPACE),
        ) }
    }

    /**
     * The panel is in front of the person again, so nothing may still claim to be starting
     * (`REQ-047`, `M21`).
     *
     * `spaceStarting` was set on the tap and cleared **only** by `spaceFailed`, so every
     * successful trip into the Space and back left *"Starting the space…"* on Today for the life
     * of the process — a progress line for a journey that finished. There is no third case to
     * time out: the Space either failed to start, in which case `spaceFailed` clears it, or it
     * started and this is the return.
     *
     * Called from `ON_RESUME`, and idempotent: the common case is a resume with nothing pending.
     */
    fun panelShown() {
        if (_state.value.spaceStarting) {
            _state.update { it.copy(spaceStarting = false) }
        }
    }

    suspend fun createNote(): Note? =
        repository.upsert(NotesRepository.newNote()).fold(
            onSuccess = { it },
            onFailure = { failure -> _state.update { it.copy(message = failure.toMessage()) }; null },
        )

    /**
     * *New note*, as the screen may call it: **no suspend function crosses a composable** (R3,
     * `DEC-0031`) and a second press while the first is in flight does nothing.
     *
     * Both were real (`B-190`). `TodayScreen` ran `createNote()` on `rememberCoroutineScope` at
     * two call sites with no guard, so a ray pressing a 128 dp control twice inside one
     * navigation frame wrote two empty notes and navigated twice — and the module document said
     * the dictation commit was the last R3 violation in the tree.
     *
     * `viewModelScope` rather than [appScope], deliberately: the product of this call is a
     * NAVIGATION, and a navigation nobody is there to receive is not worth outliving its screen.
     * The decode in [startRetranscribe] is the opposite case and is scoped the opposite way.
     */
    fun newNote(onOpened: (String) -> Unit) {
        if (_state.value.creatingNote) return
        _state.update { it.copy(creatingNote = true) }
        launchGuarded {
            try {
                createNote()?.let { onOpened(it.id) }
            } finally {
                _state.update { it.copy(creatingNote = false) }
            }
        }
    }

    /**
     * *Transcribe again*, as the screen may call it — on [appScope], because a decode is thirteen
     * to forty seconds of work and the screen that asked for it is not entitled to cancel it by
     * being left (`B-190`, and `DEC-0068`'s reasoning applied to the other long job in this app).
     *
     * The note is written whether or not anybody is still watching; the ANSWER lands in
     * [NotesUiState.retranscribed] for whichever surface is alive, and [retranscribedShown]
     * consumes it so a confirmation cannot reappear on every recomposition.
     */
    fun startRetranscribe(note: Note, provider: SttProvider, model: WhisperModel) {
        // `launchGuarded` on [appScope] (`B-215`): `sttLanguage()` inside [retranscribe] is a
        // Keystore decrypt and `WavWriter.readPcm` reads a file, and a bare launch put both of
        // those one throw away from the process's uncaught handler — from the control a person
        // presses *because* the first transcription was wrong.
        launchGuarded(
            appScope,
            onFailure = { failure ->
                _state.update {
                    it.copy(
                        retranscribing = null,
                        retranscribed = RetranscribeResult.Failed,
                        message = failure.toMessage(),
                    )
                }
            },
        ) {
            val result = retranscribe(note, provider, model)
            _state.update { it.copy(retranscribed = result) }
        }
    }

    /** The screen has shown [NotesUiState.retranscribed]; it must not be shown twice. */
    fun retranscribedShown() {
        if (_state.value.retranscribed != null) {
            _state.update { it.copy(retranscribed = null) }
        }
    }

    /**
     * Transcribes a note's stored recording again, with a provider and model the person picked
     * because the first attempt got it wrong.
     *
     * **The body is replaced only when it is still exactly what the last transcription produced.**
     * Once a person has edited the text, a re-run that overwrote it would destroy work to fix a
     * word — so the new transcript is recorded and the body is left alone, and [RetranscribeResult]
     * says which happened so the screen can tell them.
     */
    suspend fun retranscribe(
        note: Note,
        provider: SttProvider,
        model: WhisperModel,
    ): RetranscribeResult {
        val path = note.audioPath
        if (path == null) {
            _state.update { it.copy(
                message = UiMessage(R.string.state_no_recording, action = null),
            ) }
            return RetranscribeResult.NoRecording
        }
        _state.update { it.copy(retranscribing = note.id) }
        try {
            val pcm = withContext(io) {
                runCatchingCancellable { WavWriter.readPcm(File(path).readBytes()) }
            }.getOrElse { failure ->
                _state.update { it.copy(message = failure.toMessage()) }
                return RetranscribeResult.NoRecording
            }

            val transcript = transcribeWith(provider, model, pcm, sttLanguage()).getOrElse { failure ->
                _state.update { it.copy(message = failure.toMessage()) }
                return RetranscribeResult.Failed
            }

            // **The row as it is NOW, not as it was thirteen to forty seconds ago** (`B-090`).
            //
            // Everything above this line is a decode, and the `note` parameter was captured before
            // it started. Writing that snapshot back reverts every field of the row — so somebody
            // who opened the note and typed while the engine worked lost what they typed, to an
            // action they took in order to IMPROVE the note. It is the same shape `T-007` closed
            // for the editor (`I-01`); `T-006` declined this one by name.
            //
            // A re-read rather than a lock: the decode is minutes long and holding a row for it
            // would block the editor instead. The merge below is what makes a re-read sufficient —
            // it already knows how to combine a new transcript with a body somebody has edited,
            // and the only thing that was wrong was WHICH body it compared against.
            //
            // **A note that is gone is not written back.** `get` answering null means the person
            // deleted it while the decode ran, and `undoDelete` is the one control that may
            // resurrect a row. Resurrecting it here would put back a note they removed on purpose,
            // and its recording with it.
            val fresh = repository.get(note.id)
            if (fresh == null) {
                _state.update { it.copy(
                    message = UiMessage(R.string.state_note_gone, action = null),
                ) }
                return RetranscribeResult.Failed
            }

            val untouched = fresh.transcript?.text?.trim() == fresh.body.trim()
            val updated = fresh.copy(
                transcript = transcript,
                body = if (untouched) transcript.text else fresh.body,
                title = if (untouched && fresh.title == fresh.transcript?.text?.take(60)) {
                    transcript.text.take(60)
                } else {
                    fresh.title
                },
            )
            repository.upsert(updated).onFailure { failure ->
                _state.update { it.copy(message = failure.toMessage()) }
                return RetranscribeResult.Failed
            }
            return if (untouched) RetranscribeResult.Replaced else RetranscribeResult.TranscriptOnly
        } finally {
            _state.update { it.copy(retranscribing = null) }
        }
    }

    /**
     * Deletes a note and keeps it in hand, so [undoDelete] can put it back.
     *
     * There is no confirmation step: in a headset a dialog is another thing to aim at, and an undo
     * that appears after the act costs nothing when the tap was deliberate. The note is held only
     * until the next delete or until the screen clears it.
     */
    fun delete(note: Note) {
        launchGuarded {
            repository.delete(note.id).fold(
                onSuccess = { _state.update { it.copy(justDeleted = note) } },
                onFailure = { failure -> _state.update { it.copy(message = failure.toMessage()) } },
            )
        }
    }

    /** Writes the deleted note back, id and timestamps included, so the vault file returns too. */
    /**
     * Puts the note back — **and its recording**.
     *
     * Restoring the row alone brought the note back pointing at a `.wav` the vault had already
     * unlinked, so *Transcribe again* answered with a `FileNotFoundException` and the audio, which
     * is the expensive artefact, was gone for good. The vault soft-deletes now; this asks it to
     * undo that first, and only then writes the row.
     *
     * When the trash no longer holds the recording — swept after its retention — the note comes
     * back **without** it and the person is told. That is the honest degradation: the alternative
     * is a note that claims a recording it does not have.
     */
    fun undoDelete() {
        val note = _state.value.justDeleted ?: return
        // Cleared now so a second press cannot start a second restore, and **put back** if the
        // write fails: clearing it unconditionally meant a failed undo took the note out of the
        // database and the one control that could try again off the screen in the same breath,
        // leaving the recording in a trash nothing in the interface reaches. Losing a note because
        // the retry was withdrawn is worse than the delete the person meant to do.
        _state.update { it.copy(justDeleted = null) }
        launchGuarded {
            val restored = vault.restore(note.id, note.createdAt)
            // **Too early is not too late** (`B-241`). The restore fails when the mirror has not
            // yet moved the files to the trash — the person pressed *Undo* faster than it ran — and
            // then the recording is still exactly where the note says. The note keeps pointing at
            // it; if the mirror trashes it on its way through the delete, `VaultMirror.write` brings
            // it back when this note is written. Only a recording that is gone is reported gone.
            // Asked only after a failed restore, so the ordinary undo touches the disk no more
            // than it did.
            val kept = restored.isSuccess ||
                (note.audioPath != null && withContext(io) { File(note.audioPath).isFile })
            val toWrite = if (kept) {
                note
            } else {
                _state.update { it.copy(
                    message = UiMessage(R.string.state_recording_not_restored, action = null),
                ) }
                note.copy(audioPath = null)
            }
            // **A restored daily note gives up its day if another note already holds it**
            // (`DEC-0035`, option A): it keeps every word and stops being *the* note for that
            // date, because a `REPLACE` against the UNIQUE `dayKey` index would otherwise destroy
            // the note the person has been typing into all day.
            //
            // **The check used to live here, and that was the defect** (`DEC-0058`). This is one
            // caller; `VaultImporter` is another and had no such check, so importing two files for
            // one date destroyed one of them. The rule is the repository's invariant now —
            // `NotesRepository.upsert` demotes an incoming note whose day is held — so this call
            // is an ordinary restore and the outcome is the same for every door.
            repository.upsert(toWrite)
                .onFailure { failure ->
                    _state.update { it.copy(
                        message = failure.toMessage(),
                        justDeleted = note,
                    ) }
                }
        }
    }

    /**
     * The recording goes; the note, its text and its transcript stay.
     *
     * **File first, then the row.** Clearing `audioPath` before the delete would leave a note
     * pointing at nothing while the bytes stayed — the worst of both, and unrecoverable from the
     * interface because the button that would retry has already gone.
     *
     * No undo is offered, deliberately, and that is the difference from [delete]: the file is
     * gone and there is nothing to restore. `T-006` made *Undo* put a deleted note's recording
     * back; this control is the one place where the recording is the thing being removed.
     */
    fun deleteRecording(note: Note) {
        launchGuarded(
            appScope,
            onFailure = { failure -> _state.update { it.copy(message = failure.toMessage()) } },
        ) {
            vault.removeAudio(note).fold(
                onSuccess = {
                    repository.upsert(note.copy(audioPath = null)).onFailure { failure ->
                        _state.update { it.copy(message = failure.toMessage()) }
                    }
                },
                onFailure = { failure ->
                    _state.update { it.copy(message = failure.toMessage()) }
                },
            )
        }
    }

    /**
     * Shows every note rather than the window.
     *
     * Deliberately not "the next page": the person who presses this is looking for something, and
     * two hundred more at a time is a worse answer than all of them at a count where all of them
     * is still cheap. `OQ-0003` records the note count at which that stops being true and paging
     * replaces this.
     */
    fun showAll() {
        window.value = Int.MAX_VALUE
    }

    fun clearJustDeleted() { _state.update { it.copy(justDeleted = null) } }

    /** A dictation from the Today screen becomes its own note; from the editor it is appended instead. */
    /**
     * Writes a finished dictation as a note, on a scope nothing navigational can cancel.
     *
     * [onCommitted] runs only when the row is actually written — it is what releases the
     * recording, and releasing it after a failed write is how the banner came to read "your text
     * is still here" about text that was gone (`A-04`).
     *
     * On failure nothing is released and nothing is undone: the recording stays where the move put
     * it, under this note's id, so [retryDictation] finishes the job with one write and no second
     * move. A recording orphaned until a retry is recoverable; one deleted is not.
     */
    fun commitDictation(transcript: Transcript, audioPath: String?, entry: PendingDictation? = null) {
        if (committing == transcript) return
        committing = transcript
        // `launchGuarded` on [appScope] (`B-215`): `Vault.adoptOrKeep` is a file move and it
        // throws. The `finally` below is the other half — see [committing].
        launchGuarded(
            appScope,
            onFailure = { failure ->
                _state.update {
                    it.copy(message = failure.toMessage().copy(action = UiAction.RETRY_DICTATION))
                }
            },
        ) {
            try {
                // **A previous process already wrote this one** (`B-237`, `DEC-0088`): the entry was
                // bound to its note, the row landed, and the process died before the settle. The
                // words are in the base; writing them again would be a second note, and the
                // clipboard and the cue belong to a moment that has passed.
                val boundId = entry?.noteId
                if (boundId != null && repository.get(boundId) != null) {
                    outbox.settle(entry)
                    // A *Retry* can land here too — a write that reported failure while its row
                    // landed — so the owed note and its banner go with the entry (seam verification).
                    // Its OWN debt only, as on the success arm (`DEC-0095`): a different dictation's
                    // failed write keeps its handle.
                    if (unsettled == null || unsettled?.id == entry.id) unsettled = null
                    _state.update {
                        if (it.pendingDictation?.id == boundId) it.copy(pendingDictation = null, message = null) else it
                    }
                    Log2.i("dictation.outbox.already_written", "note" to boundId)
                    return@launchGuarded
                }
                val existing = _state.value.pendingDictation
                val note = when {
                    existing?.transcript == transcript -> existing
                    // Bound in a previous life and never written: the SAME note, so the vault
                    // folder its recording was already moved into is the one it is written to.
                    boundId != null && entry.createdAt != null ->
                        NotesRepository.newNote(
                            title = transcript.text.take(60),
                            body = transcript.text,
                            now = entry.createdAt,
                        ).copy(id = boundId, transcript = transcript)
                    else ->
                        NotesRepository.newNote(title = transcript.text.take(60), body = transcript.text)
                            .copy(transcript = transcript)
                }
                // The recording moves into the vault first, so the note is written once with its final
                // path — and a retry reuses the same id, so the move has already happened.
                val adopted = note.audioPath ?: vault.adoptOrKeep(note, audioPath)
                // **Never a path to nothing** (`B-256`). The editor path moves a recording before any
                // write lands and does not bind the outbox, so a dictation recovered after a crash can
                // name a scratch file that has already gone; `adoptOrKeep` then keeps that path and
                // the note claimed a recording it does not have. The words are written; the claim is not.
                // A stat on `appScope`, which is off the drawing thread (`Graph.scope` is
                // `Dispatchers.Default`) — the same scope that has just moved the file.
                val present = adopted?.takeIf { path -> File(path).isFile }
                if (adopted != null && present == null) Log2.w("dictation.audio.missing", "note" to note.id)
                val stored = note.copy(audioPath = present)
                // **Written down before the write** (`B-237`): which note, when, and where its audio
                // now is. A death between here and the settle re-writes this note next launch
                // instead of losing it, and a death after the row lands writes nothing.
                val owed = entry?.let {
                    outbox.bind(it, noteId = stored.id, createdAt = stored.createdAt, audioPath = stored.audioPath)
                }
                repository.upsert(stored).fold(
                    onSuccess = {
                        // Durable now, so the entry ends — and not one line earlier.
                        owed?.let(outbox::settle)
                        // **Only this dictation's debt is paid** (`B-255`). A different one whose
                        // write failed earlier keeps its entry and its banner: clearing both here
                        // is how the first dictation vanished once the second was written.
                        if (unsettled == null || unsettled?.id == owed?.id) unsettled = null
                        _state.update {
                            if (it.pendingDictation == null || it.pendingDictation.id == stored.id) {
                                it.copy(pendingDictation = null, message = null)
                            } else {
                                it
                            }
                        }
                        cues.play(Cue.SAVED)
                        // **`DEC-0012`, where the commit is known to have happened** (`B-212`). It
                        // used to live in `TodayScreen`'s `LaunchedEffect` on a `derivedStateOf` key,
                        // so it ran only if a recomposition frame observed `VoiceState.Ready` between
                        // the outbox offer and the drain that empties it — a window this code owns
                        // both ends of and neither end of which is a frame. Here it runs once, on the
                        // path that produced the row, whether or not any composition is alive: since
                        // `DEC-0068` a dictation can be written by a process that has just started.
                        //
                        // **A failure to copy does not fail the commit.** The note is on disk; losing
                        // the clipboard is an inconvenience, and reporting it as a save failure would
                        // offer a *Retry* that writes the row a second time.
                        //
                        // **The tail of the commit, and nothing before it waits on it.** The switch
                        // is a Keystore decrypt; read ahead of the write it would put a stall — and,
                        // for anything that cannot resolve it, a hang — in front of the row this
                        // method exists to produce. An unreadable switch means "on", which is
                        // `G-02`'s default and the answer that keeps `DEC-0012` rather than dropping
                        // it silently.
                        val wantsCopy =
                            runCatchingCancellable { copyTranscriptSetting() }.getOrDefault(true)
                        val copied = wantsCopy &&
                            runCatchingCancellable { copyToClipboard(transcript.text) }.isSuccess
                        _dictationCommitted.tryEmit(
                            DictationCommitted(copied = copied, source = transcript.source),
                        )
                    },
                    onFailure = { failure ->
                        unsettled = owed ?: unsettled
                        _state.update { it.copy(
                            pendingDictation = stored,
                            // **`RETRY_DICTATION`, and the whole of `H2` is that it was not.** The
                            // mapper turns a failed write into a generic retry; Today answered that
                            // with `retry()`, which reloads the list. So *Retry* under *"Couldn't
                            // save. Your text is still here."* re-read the notes, the transcript
                            // stayed unwritten, the next dictation overwrote `pendingDictation`, and
                            // its recording was orphaned in the vault under an id no row carried.
                            // `retryDictation()` had existed the whole time with zero callers.
                            message = failure.toMessage().copy(action = UiAction.RETRY_DICTATION),
                        ) }
                    },
                )
            } finally {
                // **In a `finally`, because it used to be in both arms of the fold** (`B-215`).
                // The one path neither arm covers is a throw — `adoptOrKeep` is file IO — and a
                // throw between them left this field holding that transcript for the life of the
                // process. The guard at the top of this method then refused every retry of the one
                // dictation that needed one: recording in the vault, row never written, and
                // *Retry* doing nothing at all, silently.
                committing = null
            }
        }
    }

    /**
     * Writes a note that is **only** a recording (`B-091`, `SCN-004`).
     *
     * The scenario promises that when the engine fails "the audio is kept and offered as a note
     * without a transcript", and until now the second half existed nowhere: the file survived the
     * failure and `VoiceViewModel.onCleared` deleted it on the way out, because nothing owned it.
     *
     * It is deliberately **not** [commitDictation] with an empty transcript. A `Transcript` is a
     * receipt — a language, a source, an engine, a duration — and inventing a blank one to reuse a
     * code path would put a lie in the vault and on the row's engine·language line. This note has
     * no transcript because nothing transcribed it, and `Transcribe again` is exactly the control
     * that fixes that; the recording is what makes it reachable.
     *
     * The title is the only thing it can honestly carry: a recording has no words to name itself
     * with. `label_untitled` is what the list already draws for a note with no text, so the row is
     * a date, a microphone glyph and a *Transcribe again* — which is the whole offer.
     */
    fun keepRecordingOnly(audioPath: String) {
        // `Vault.adoptOrKeep` is a file move (`B-215`). A bare launch here crashed the process for
        // the one case this method exists to rescue: the engine already failed, and the recording
        // is all that is left.
        launchGuarded(
            appScope,
            onFailure = { failure -> _state.update { it.copy(message = failure.toMessage()) } },
        ) {
            val note = NotesRepository.newNote()
            // The recording moves into the vault first, so the row is written once with its final
            // path — the same order `commitDictation` uses and for the same reason.
            val stored = note.copy(audioPath = vault.adoptOrKeep(note, audioPath))
            repository.upsert(stored).fold(
                onSuccess = { cues.play(Cue.SAVED) },
                onFailure = { failure ->
                    _state.update { it.copy(message = failure.toMessage()) }
                },
            )
        }
    }

    /**
     * The banner's *Retry* for a dictation whose row would not write. The audio has not moved,
     * so this is one write and no second file operation (`REQ-047`, `H2`).
     */
    fun retryDictation() {
        val pending = _state.value.pendingDictation ?: return
        val transcript = pending.transcript ?: return
        _state.update { it.copy(message = null) }
        commitDictation(transcript, pending.audioPath, unsettled)
    }
}

/**
 * Every failure becomes words through the one mapper — no screen invents its own wording.
 *
 * **The `else` classifies rather than shrugging** (`REQ-058`'s app half, `M12`). It was
 * `AppError.Unknown(this)`, so a `ConnectException` or an `SSLException` that reached a screen
 * without having been wrapped by its module arrived as *"Something failed: ConnectException"* —
 * a class name, to a person, about a host they typed in themselves. `toAppError` is the same
 * classification `:feature-stt` and `:feature-vault` use, so a network failure says the network
 * failed on every path, and none of them says *"Couldn't save. Your text is still here."*
 */
fun Throwable.toMessage(): UiMessage = UiStateMapper.map(
    when (this) {
        is NotesException -> error
        is ai.passioncode.fabricvr.vault.VaultException -> error
        is ai.passioncode.fabricvr.stt.SttException -> error
        is ai.passioncode.fabricvr.common.SecureSettingsException -> error
        is ai.passioncode.fabricvr.common.InsecureUrlException -> error
        else -> toAppError()
    },
)

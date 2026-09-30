package ai.passioncode.fabricvr.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.passioncode.fabricvr.Graph
import ai.passioncode.fabricvr.common.UiMessage
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.Transcript
import ai.passioncode.fabricvr.vault.Vault
import ai.passioncode.fabricvr.vault.adoptOrKeep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher

/** One recording a note keeps: where it is, and when it was made (`B-254`). */
data class RecordingItem(val path: String, val takenAt: Long)

data class EditorUiState(
    val loading: Boolean = true,
    val note: Note? = null,
    val saved: Boolean = true,
    val message: UiMessage? = null,
    /**
     * Every recording the note keeps, current first (`B-254`). Since `DEC-0090` a second dictation
     * keeps the first beside it, and until this list nothing on the note could play it.
     */
    val recordings: List<RecordingItem> = emptyList(),
)

class EditorViewModel(
    private val repository: NotesRepository = Graph.notes,
    private val vault: Vault = Graph.vault,
    /**
     * Where a write that must not be cancelled runs. `viewModelScope` dies with the editor, and
     * both callers of [flush] fire it as the screen is going away — on the panel `onBack()` pops
     * the back-stack entry and clears *that entry's* store mid-write, and in the Space the activity
     * clears the store before the composition is disposed, so `onDispose { flush() }` launched into
     * an already-cancelled scope every time. A parameter rather than `GlobalScope`, so a test can
     * supply a scope it owns.
     */
    private val appScope: CoroutineScope = Graph.scope,
    /** Where the note's folder is listed (`B-254`) — a directory read, never on the drawing thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private var saveJob: Job? = null

    /**
     * The note this editor has deleted, until something proves the delete failed (`B-213`).
     *
     * **Cancellation cannot be the mechanism here, and that is the whole of the finding.**
     * `delete()` disarms the two writers it can reach by cancelling [saveJob] — but the write
     * started by [attachTranscript] is deliberately not that job, and deliberately not
     * cancellable: it is a file move followed by a row, and a job cancelled before its first
     * dispatch never runs its body at all, so cancelling it would strand the recording in a
     * scratch directory that is about to be swept. So the in-flight write is not stopped; it is
     * told, when it arrives, that the row it is about to write no longer exists.
     *
     * An id rather than a flag, so a delete cannot silence a write to a different note — the
     * editor loads another one straight after deleting this one on some routes — and `@Volatile`
     * because it is set on the caller's thread and read from [appScope], which is
     * `Dispatchers.Default`.
     */
    @Volatile
    private var deletedId: String? = null

    /**
     * The uncancellable write [attachTranscript] starts, kept so [delete] can **wait** for it.
     *
     * [deletedId] stops a write that has not reached the repository yet; this is the other half,
     * for one that has. `NotesRepository.upsert` is a `REPLACE` and nothing recalls a statement
     * already issued — so a delete that ran while the upsert was in flight removed the row and the
     * upsert then wrote it back. Joining first means the two happen in the order the person asked
     * for them in: the dictation is saved, and then the note they deleted is gone.
     */
    private var attachJob: Job? = null

    /**
     * How many dictations have been appended to the state, and the promise each one carries (`B-237`).
     *
     * An appended dictation is still in the outbox's file until the note carrying it is durable —
     * the caller passed `onDurable` to end it there. **Any** successful write ends it, not only the
     * one [attachTranscript] started: that one may fail and a later autosave, `flush` or *Retry*
     * carry the words instead. But only a write whose note was read AFTER the append: an autosave
     * already in flight with the pre-dictation note would otherwise settle words it never sent.
     * [save] reads this counter before it reads the note, and [attachTranscript] bumps it after it
     * updates the state, so a count a write has seen is an append the write's note contains.
     */
    private val appended = java.util.concurrent.atomic.AtomicLong(0)
    private val owedDurable = mutableListOf<Pair<Long, () -> Unit>>()

    private fun settleThrough(generation: Long) {
        val due = synchronized(owedDurable) {
            owedDurable.filter { it.first <= generation }.also { owedDurable.removeAll(it) }
        }
        due.forEach { (_, settle) -> runCatching(settle) }
    }

    fun load(id: String) {
        launchGuarded {
            val note = repository.get(id)
            // A fresh subject: whatever was deleted, this is not it.
            deletedId = null
            _state.value = EditorUiState(loading = false, note = note)
            // After the note, not with it: the list is a directory read on `io`, and the editor is
            // usable before it arrives.
            refreshRecordings()
        }
    }

    private val listings = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * When a recording was made. An earlier one carries its moment in its own name
     * (`<id>~<stamp>.wav`, `DEC-0090`), which survives an archive restore that resets every
     * file's modification time; the current one has only its file clock.
     */
    private fun takenAt(file: File): Long =
        file.nameWithoutExtension.substringAfter('~', "").toLongOrNull() ?: file.lastModified()

    /**
     * Re-lists the note's recordings (`B-254`): on open, and after a dictation lands in it — the
     * second from [appScope], because the write it follows runs there and outlives the screen.
     */
    private fun refreshRecordings(scope: CoroutineScope? = null) {
        // Even an empty result supersedes work already dispatched for this note.
        val generation = listings.incrementAndGet()
        val note = _state.value.note ?: return
        // A note with no current recording keeps no earlier ones either — *Delete recording* removes
        // them together — so there is no directory worth reading.
        if (note.audioPath == null) {
            if (_state.value.recordings.isNotEmpty()) _state.update { it.copy(recordings = emptyList()) }
            return
        }
        // A newer listing always wins (seam verification): the refresh on open and the one after a
        // dictation can finish in either order.
        val body: suspend CoroutineScope.() -> Unit = {
            val items = withContext(io) {
                val files = vault.recordingsOf(note)
                // The recording the note POINTS at comes first even when it is not in the vault's
                // folder — `adoptOrKeep` keeps a scratch path when a move fails (seam verification).
                val pointed = note.audioPath?.let(::File)?.takeIf { f -> f.isFile && files.none { it.absolutePath == f.absolutePath } }
                (listOfNotNull(pointed) + files).map { RecordingItem(it.absolutePath, takenAt(it)) }
            }
            _state.update {
                if (generation == listings.get() && it.note?.id == note.id && it.note.audioPath == note.audioPath) {
                    it.copy(recordings = items)
                } else it
            }
        }
        launchGuarded(scope ?: viewModelScope, block = body)
    }

    fun edit(title: String? = null, body: String? = null) {
        val current = _state.value.note ?: return
        val updated = current.copy(title = title ?: current.title, body = body ?: current.body)
        _state.update { it.copy(note = updated, saved = false) }
        saveJob?.cancel()
        // The debounce belongs to the editor and dies with it: an autosave still counting down for
        // a screen nobody is looking at has nothing to write that [flush] has not already written.
        saveJob = launchGuarded {
            delay(AUTOSAVE_DELAY_MS)
            save()
        }
    }

    /**
     * Appends a dictation to the open note. The text lands in the state at once — the person is
     * looking at it — and the recording is moved into the vault before the note is written, so the
     * note records where the audio actually ended up rather than a scratch path that is about to
     * stop existing.
     *
     * @param onDurable called once a write carrying these words has landed, or the note was
     *   deleted — the screen passes the outbox's `settle` for the entry it claimed (`B-237`,
     *   `DEC-0088`). Never called for a write that failed: the entry then stays on disk, and if
     *   this editor goes away before any write lands, the next launch writes the words as a note
     *   of their own. A duplicate sentence is recoverable; a lost one is not.
     */
    fun attachTranscript(transcript: Transcript, audioPath: String?, onDurable: () -> Unit = {}) {
        val current = _state.value.note ?: return
        val body = listOf(current.body, transcript.text).filter { it.isNotBlank() }.joinToString("\n\n")
        _state.update { it.copy(
            note = current.copy(
                body = body,
                title = current.title.ifBlank { transcript.text.take(60) },
                transcript = transcript,
            ),
            saved = false,
        ) }
        // After the state, never before — see [appended].
        val generation = appended.incrementAndGet()
        synchronized(owedDurable) { owedDurable += generation to onDurable }
        // The autosave scheduled before the person spoke holds the pre-dictation note. Left alone
        // it fires 600 ms from now and writes that note back over the dictation — body, transcript
        // and recording — and the vault mirror copies the loss into the person's own folder.
        saveJob?.cancel()
        // Deliberately NOT assigned to `saveJob`, and deliberately on a scope no keystroke and no
        // navigation cancels. The adoption is a file move — `renameTo`, falling back to copy then
        // delete — so a cancellation between the copy and the delete leaves two files, and one
        // after the rename leaves the note pointing at a path that has just stopped existing.
        // Wrapping the body in `NonCancellable` would not be enough: a job cancelled before its
        // first dispatch never runs its body at all, and on a queueing dispatcher that is the
        // common case, not the rare one.
        // **`B-213`: this launch is not `saveJob`, so `delete()` cannot cancel it — and must not
        // try.** What stops it instead is [deletedId], checked here (so a delete that has already
        // happened does not move a recording into the vault for a row nobody will write) and
        // again inside [save] (so one that happens *during* the adoption cannot be written back).
        // `launchGuarded` on [appScope] rather than a bare launch (`B-215`): `adoptOrKeep` is a
        // file move and it throws, and this scope has no handler either.
        attachJob = launchGuarded(
            appScope,
            onFailure = { failure -> _state.update { it.copy(saved = false, message = failure.toMessage()) } },
        ) {
            if (_state.value.note?.id == deletedId) return@launchGuarded
            val landed = vault.adoptOrKeep(_state.value.note ?: return@launchGuarded, audioPath)
            if (landed != null) {
                _state.value.note?.let { latest ->
                    _state.update { it.copy(note = latest.copy(audioPath = landed)) }
                }
            }
            save()
            refreshRecordings(appScope)
        }
    }

    /**
     * Deletes the open note — and **disarms everything that would write it back** (`REQ-059`,
     * `M1`).
     *
     * The two lines above the launch are the whole finding. `delete()` used to cancel nothing:
     * the 600 ms autosave armed by the last keystroke went on counting, and the screen's
     * `onDispose { flush() }` fired as it went away. Both write through `NotesRepository.upsert`,
     * which is a `REPLACE` — so the row came back carrying the text typed a moment before the
     * delete, and `VaultMirror` copied the resurrection into the person's own folder. A note that
     * returns after being deleted is worse than one that will not delete: the second is a failure
     * they can see.
     *
     * `saved = true` is what disarms [flush], which returns at its first line for a note with
     * nothing pending. It is honest rather than a trick: there is nothing left to write.
     *
     * **And that was only two of the three writers** (`B-213`). [attachTranscript] starts its own
     * adopt-then-save on [appScope] and deliberately does not assign it to [saveJob] — so the
     * cancel above never touched it, `saved = true` was overwritten by the dictation's own
     * `saved = false` a moment earlier, and a person who spoke into a note and then pressed
     * *Delete* — the two controls are adjacent in one `Row` — got the note back, dictation and
     * all. `M1` was closed for the autosave path and its record says so; the third writer is
     * stopped by [deletedId], because it is a file move and cancelling it is the wrong tool.
     *
     * **On failure both are put back.** The note still exists, so the edit still has to reach the
     * disk — withdrawing the write because the delete failed would lose the keystrokes to a
     * failure that had nothing to do with them.
     */
    fun delete(onDone: () -> Unit) {
        val id = _state.value.note?.id ?: return onDone()
        val hadPendingEdit = !_state.value.saved
        // Everything that could still write this note, captured before the cancel below nulls the
        // field: a cancel does not unwind a statement the repository has already issued, so the
        // delete waits for each of them rather than racing it.
        val inFlight = listOfNotNull(saveJob, attachJob)
        saveJob?.cancel()
        saveJob = null
        // **Before the launch, not inside it** (`B-213`). The write this has to beat is already
        // running on [appScope]; a flag set after a dispatch would be set after it had finished.
        deletedId = id
        _state.update { it.copy(saved = true) }
        launchGuarded {
            inFlight.forEach { it.join() }
            repository.delete(id).fold(
                // The person deleted the note the words were appended to: that is a decision about
                // them too, and the outbox must not write them back as a note of their own.
                onSuccess = { settleThrough(Long.MAX_VALUE); onDone() },
                onFailure = { failure ->
                    // The note still exists, so every writer this disarmed is armed again —
                    // [deletedId] with the rest, or a storage failure would silently stop the
                    // editor saving for the life of the screen.
                    deletedId = null
                    _state.update {
                        it.copy(saved = !hadPendingEdit, message = failure.toMessage())
                    }
                },
            )
        }
    }

    /**
     * Write whatever is pending right now. The editor calls this when it goes away: an edit still
     * inside the autosave window would otherwise die with the view model, and losing the half-typed
     * thought is exactly what the app exists to prevent.
     */
    fun flush() {
        if (_state.value.note == null || _state.value.saved) return
        saveJob?.cancel()
        // `appScope`: both callers fire this as the screen goes away, and the store that owns
        // `viewModelScope` is cleared in the same breath — on the panel by the back-stack entry
        // being popped, in the Space by the activity clearing owners before disposal. Launched on
        // the view model's own scope this write was cancelled mid-flight, every time, in the Space.
        saveJob = launchGuarded(appScope) { save() }
    }

    /** The banner's Retry writes the pending note again rather than only clearing the words. */
    fun retry() {
        _state.update { it.copy(message = null) }
        if (_state.value.note == null) return
        saveJob?.cancel()
        saveJob = launchGuarded(appScope) { save() }
    }

    fun dismissMessage() { _state.update { it.copy(message = null) } }

    /**
     * Writes whatever the note is **now**, not what it was when this write was scheduled.
     *
     * Taking the note by value is how a 600 ms autosave scheduled before a dictation came back and
     * wrote the pre-dictation note over it, and how the success of an upsert undid the keystrokes
     * typed while it was in flight. Both ends need the same discipline: read late, and only claim
     * `saved` about the note that was actually sent.
     */
    private suspend fun save() {
        // Before the note, never after — see [appended].
        val carries = appended.get()
        val sending = _state.value.note ?: return
        // **The one place every writer passes through** (`B-213`). `upsert` is a `REPLACE`, so a
        // write that arrives after the delete does not fail — it re-creates the row, and
        // `VaultMirror` copies it back into the person's folder. The autosave and `flush()` are
        // disarmed by `saved = true`; this is what disarms the write `attachTranscript` started on
        // a scope nothing cancels, and it is deliberately the last gate rather than the only one,
        // because the delete can land at any point of that write.
        if (sending.id == deletedId) return
        repository.upsert(sending).fold(
            onSuccess = { stored ->
                settleThrough(carries)
                // `stored` is never equal to `sending` — the repository re-derives `updatedAt` and
                // the tags — so the comparison is against the note this call composed, not against
                // what came back. If the state has moved on, the newer note is the truth and its
                // own write is already scheduled; all this call may claim is that the error is over.
                if (_state.value.note == sending) {
                    _state.update { it.copy(note = stored, saved = true, message = null) }
                } else {
                    _state.update { it.copy(message = null) }
                }
            },
            onFailure = { failure -> _state.update { it.copy(saved = false, message = failure.toMessage()) } },
        )
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 600L
    }
}

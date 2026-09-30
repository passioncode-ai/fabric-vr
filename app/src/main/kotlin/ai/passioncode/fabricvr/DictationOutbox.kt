package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A dictation that has been transcribed and has not yet become a note.
 *
 * @param id the dictation's identity, and the token a drain claims it by. It is the audio path
 *   where there is one — unique per recording — with the transcript's text as the fallback for a
 *   dictation whose recording could not be written. The same rule `dictationKey` uses on the
 *   screens, so the two cannot disagree about what "the same dictation" means.
 * @param holder non-null while a live surface intends to commit this one itself — the editor,
 *   which appends to the note already open instead of making a new one. A held entry is skipped
 *   by [DictationOutbox]'s ordinary drain and released the moment that surface goes away, so the
 *   words are never stranded by the hold. It is deliberately **not** persisted: a process that
 *   died was holding nothing.
 * @param noteId the note this dictation is becoming, once a commit has chosen it ([DictationOutbox.bind]).
 *   Persisted, so a process that dies between choosing and settling writes the SAME note on the
 *   next launch — or, finding it already written, writes nothing (`B-237`, `DEC-0088`).
 * @param createdAt that note's creation time, persisted with [noteId] because it locates the
 *   note's folder in the vault: the same id with a new clock would move its recording.
 */
data class PendingDictation(
    val id: String,
    val transcript: Transcript,
    val audioPath: String?,
    val holder: String? = null,
    val noteId: String? = null,
    val createdAt: Long? = null,
)

/**
 * Where a finished dictation waits for a note to be written from it (`REQ-046`, audit `H1`).
 *
 * **The defect this exists for.** Transcription ran on `viewModelScope`, the commit ran from a
 * `LaunchedEffect` in the composition that started it, and both die with their host. Leaving the
 * Space mid-dictation ends `ImmersiveActivity`; `onCleared` then deleted the WAV because the
 * state was `Transcribing` rather than `Ready`; the panel has a *different* `VoiceViewModel`, so
 * nothing was waiting on return. The words, and the recording they could have been recovered
 * from, were both gone — for the one action this product exists to perform. The same loss
 * happened on system Back out of the editor.
 *
 * So the transcript stops belonging to a screen. It is offered here, by a job on the application
 * scope, and whichever `NotesViewModel` is alive next drains it — the panel's on return from the
 * Space, the Space's on the way in, or a brand-new one on the next cold start.
 *
 * **Exactly once, with two surfaces watching.** Both hosts can be alive at the same moment and
 * both observe this flow, so the hand-off is a compare-and-set rather than a read followed by a
 * write: [claim] answers the entry to exactly one caller and null to every other.
 *
 * **It survives the process**, which is the point of the file. A `MutableStateFlow` alone would
 * lose the transcript to a kill between the words arriving and the row landing — the window this
 * class narrows but cannot close, because Room's write is not instantaneous. One small text file
 * costs a few hundred bytes and turns "the headset was taken off and the shell reaped us" into a
 * note that is simply there next time. What it does **not** carry is
 * [Transcript.fallbackReason] — an `AppError` is a tree of throwables with no serialised form,
 * and the fact that matters, that the words came from the headset after the chosen provider
 * refused, is already in [Transcript.source].
 *
 * @param file where to persist. Null means memory only, which is what a test uses when the
 *   subject is the hand-off rather than the disk.
 * @param flush how the bytes are forced to the disk before the rename commits them. Injectable
 *   for the reason `writeFileAtomically`'s commit step is: an `fsync` cannot be observed from a
 *   JVM test at all, while the ORDER it sits in can — the scratch file complete, the target not
 *   yet there. Only a test passes this.
 * @param duringClaim run inside [claim], right after the entry is let go in memory. **Only a test
 *   passes this**, and it exists because the window it opens is a real interleaving between two
 *   live surfaces — an [offer] landing mid-claim — that a timing test would pass over on a machine
 *   that happened not to schedule it.
 */
class DictationOutbox(
    private val file: File? = null,
    private val flush: (java.io.FileOutputStream) -> Unit = { it.fd.sync() },
    private val duringClaim: () -> Unit = {},
) {

    private val _pending = MutableStateFlow<PendingDictation?>(null)

    /**
     * Entries a previous process left **beside** the main file (`B-255`), waiting their turn. Only
     * [restore] fills it; each successful [claim] publishes the next, so the drains that already
     * exist take them one at a time.
     */
    private val queued = ArrayDeque<PendingDictation>()

    /**
     * The file is one resource and four callers touch it — [offer], [release], [bind] and [settle] — from
     * whichever thread their surface lives on. `_pending`'s own atomicity says nothing about the
     * disk, which is how a claim could delete the file a newer offer had just written.
     */
    private val fileLock = Any()

    /** The dictation waiting for a note, or null. Held entries are still published — see [drainable]. */
    val pending: StateFlow<PendingDictation?> = _pending.asStateFlow()

    /**
     * Puts a finished dictation in, and writes it down.
     *
     * One at a time: a second dictation cannot start until the first has left `Ready`, so a
     * second offer means the first was committed and this is its successor. Overwriting is
     * therefore correct and losing an entry here is not reachable — but it is logged, because a
     * silent overwrite would be a loss nobody could see.
     */
    fun offer(transcript: Transcript, audioPath: String?, holder: String? = null): PendingDictation {
        val entry = PendingDictation(
            id = audioPath ?: ("text:" + transcript.text),
            transcript = transcript,
            audioPath = audioPath,
            holder = holder,
        )
        // **On disk first, then visible** (`B-245`). Published first, a drain could claim, commit
        // and settle this entry before `persist` ran — the settle found no file, and the write then
        // left an already-written dictation for the next launch to write again.
        persist(entry)
        val replaced = _pending.getAndUpdate { entry }
        if (replaced != null && replaced.id != entry.id) {
            Log2.w("dictation.outbox.replaced", "id" to replaced.id)
        }
        return entry
    }

    /**
     * Takes the entry named by [id], for exactly one caller.
     *
     * @return the entry, or null when there is none, when it is a different one, or when another
     *   surface got there first. A null is not an error: it is the answer that keeps two hosts
     *   from writing one dictation twice.
     *
     * **A claim is ownership, not an ending — the file stays** (`B-237`, `DEC-0088`). It used to
     * erase the file here, and every caller then did the durable work AFTER: move the audio, write
     * the row. A process death in between, or a write that failed, left the words in RAM only — the
     * one state this class exists to make impossible, moved from before the claim to after it.
     * Now the caller that owns the entry ends it with [settle] once the note is durable (or the
     * person discarded it), and [bind] writes down which note it is becoming in the meantime, so a
     * restart re-writes that same note or, finding it written, writes nothing.
     *
     * **The residue, stated:** an [offer] landing between this claim and the [settle] overwrites the
     * file with the newer dictation. Reaching it needs a whole second dictation — record, stop,
     * transcribe, seconds at least — to finish inside one Room write of the first, milliseconds; it
     * is logged by [offer] as `dictation.outbox.replaced` if it ever happens.
     */
    fun claim(id: String): PendingDictation? {
        while (true) {
            val current = _pending.value ?: return null
            if (current.id != id) return null
            if (_pending.compareAndSet(current, null)) {
                duringClaim()
                return current
            }
        }
    }

    /**
     * Writes down which note [entry] is becoming, before that note is written.
     *
     * Only over the file of this same dictation, or over no file at all: a file holding a
     * different id is a newer offer's and is left alone, which is [erase]'s rule applied to a write.
     */
    fun bind(entry: PendingDictation, noteId: String, createdAt: Long, audioPath: String?): PendingDictation {
        val bound = entry.copy(noteId = noteId, createdAt = createdAt, audioPath = audioPath, holder = null)
        val target = file ?: return bound
        synchronized(fileLock) {
            if (target.isFile) {
                val onDisk = runCatching { decode(target.readText()) }.getOrNull()
                if (onDisk != null && onDisk.id != entry.id) return bound
            }
            persist(bound)
        }
        return bound
    }

    /**
     * Ends [entry]'s life on disk: the note it became is durable, or the person discarded it — and
     * only then hands the next restored entry to the drains (`DEC-0095`). Publishing it from [claim]
     * ran two commits at once, racing on one main file and on the view model's retry state.
     */
    fun settle(entry: PendingDictation) {
        erase(entry)
        synchronized(queued) { queued.removeFirstOrNull() }?.let { next -> _pending.compareAndSet(null, next) }
    }

    /** The entry a drain of last resort may take: present, and nobody's in particular. */
    fun drainable(): PendingDictation? = _pending.value?.takeIf { it.holder == null }

    /**
     * Gives up a hold without giving up the dictation.
     *
     * Called when the surface that meant to commit it goes away — the editor being disposed, or
     * its view model cleared with the host. The entry stays; it simply becomes anybody's, so the
     * next `NotesViewModel` writes it as a note of its own. Losing the append is a smaller harm
     * than losing the words, and it is the honest one: the note is in the list.
     */
    fun release(holder: String) {
        while (true) {
            val current = _pending.value ?: return
            if (current.holder != holder) return
            val freed = current.copy(holder = null)
            if (_pending.compareAndSet(current, freed)) {
                persist(freed)
                return
            }
        }
    }

    /**
     * Whether [path] is the recording of the dictation waiting here.
     *
     * `VoiceViewModel.onCleared` asks before deleting: the file is the only copy of what was
     * said, and a pending dictation owns it until the note that will point at it exists.
     */
    fun owns(path: String?): Boolean = path != null && _pending.value?.audioPath == path

    /**
     * Reads back whatever the last process left (`REQ-046`, the cold-start half).
     *
     * A restored entry is never held: nothing is alive that could have been holding it.
     * Unreadable or half-written content is discarded rather than surfaced — a transcript nobody
     * can parse is not evidence of anything, and leaving the file would make every launch try
     * again.
     */
    fun restore(): PendingDictation? {
        val main = file ?: return null
        // The main file first, then whatever an offer moved aside (`B-255`), oldest first.
        val sources = listOfNotNull(main.takeIf { it.isFile }) + asides(main)
        val entries = sources.mapNotNull { source ->
            runCatching { decode(source.readText()) }.getOrNull().also { entry ->
                if (entry == null) {
                    Log2.w("dictation.outbox.unreadable")
                    runCatching { source.delete() }
                }
            }
        }
        val first = entries.firstOrNull() ?: return null
        synchronized(queued) { queued.clear(); queued.addAll(entries.drop(1)) }
        _pending.value = first
        Log2.i("dictation.outbox.restored", "audio" to (first.audioPath != null), "waiting" to (entries.size - 1))
        return first
    }

    /**
     * The recordings of every dictation waiting here — the one published and the ones queued behind
     * it. The launch sweep spares all of them: a queued entry's scratch WAV can be older than the
     * sweep's day by the time the drain reaches it (seam verification of `DEC-0095`).
     */
    fun waitingAudio(): Set<String> =
        (listOfNotNull(_pending.value) + synchronized(queued) { queued.toList() })
            .mapNotNull { it.audioPath }.toSet()

    /** Files an offer moved aside rather than overwrite, oldest first. */
    private fun asides(main: File): List<File> =
        main.parentFile?.listFiles { f -> f.isFile && f.name.startsWith(main.name + ASIDE) }
            ?.sortedBy { it.name }.orEmpty()

    /**
     * **Three steps, and the middle one was missing.**
     *
     * This comment already cited `Vault.write`, which writes a sibling, **`fsync`s it** and then
     * renames it over the target — and whose KDoc says in as many words that without the flush the
     * rename can reach the disk before the bytes it commits, *"the same loss with a longer fuse"*.
     * This class did the first step and the third. It is the one file in the product whose whole
     * purpose is surviving a hard stop — a headset taken off mid-dictation is the situation this
     * class was written for, not an edge of it — so the fuse was lit exactly where it mattered.
     *
     * The fallback for a filesystem that refuses the rename is flushed too. It is not atomic and
     * says so, the same way `writeFileAtomically`'s is; losing the transcript to a filesystem
     * quirk would be worse.
     */
    private fun persist(entry: PendingDictation) {
        val target = file ?: return
        runCatching {
            synchronized(fileLock) {
                target.parentFile?.mkdirs()
                // **Never over another dictation** (`B-255`). A file holding a different id is one
                // nobody has settled — settling erases — and the commonest way to be here is a
                // commit that failed, whose *Retry* banner is still up. Overwriting it lost those
                // words as soon as the next dictation was written. It moves aside instead, and
                // [restore] brings it back after the main file.
                if (target.isFile) {
                    val onDisk = runCatching { decode(target.readText()) }.getOrNull()
                    if (onDisk != null && onDisk.id != entry.id) {
                        val aside = File(target.parentFile, target.name + ASIDE + "%020d".format(System.nanoTime()))
                        if (target.renameTo(aside)) Log2.w("dictation.outbox.moved_aside", "id" to onDisk.id)
                    }
                }
                val bytes = encode(entry).toByteArray(Charsets.UTF_8)
                val temp = File(target.parentFile, target.name + ".tmp")
                java.io.FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    flush(out)
                }
                if (!temp.renameTo(target)) {
                    java.io.FileOutputStream(target).use { out ->
                        out.write(bytes)
                        out.flush()
                        flush(out)
                    }
                    temp.delete()
                }
            }
        }.onFailure { Log2.w("dictation.outbox.persist.failed", "reason" to it::class.java.simpleName) }
    }

    /**
     * Removes the file **of [entry] and of nothing else**.
     *
     * It took no argument and deleted whatever was there, which is only correct while nothing can
     * arrive between the claim and the delete — and two surfaces are alive at once by
     * construction, which is why [claim] is a compare-and-set in the first place. The identity
     * check under the same lock [persist] holds is what makes the pair one operation: a file
     * holding a different dictation is a newer offer's, and a file that will not decode is nobody's
     * and goes.
     */
    private fun erase(entry: PendingDictation) {
        val target = file ?: return
        synchronized(fileLock) {
            if (target.isFile) {
                val onDisk = runCatching { decode(target.readText()) }.getOrNull()
                if (onDisk == null || onDisk.id == entry.id) runCatching { target.delete() }
            }
            // **And every aside copy of it** — not only when the main file was someone else's. An
            // entry restored from aside is `bind`-ed into the MAIN file, so returning after the
            // main delete left the aside copy for the next launch to write as a note again, every
            // launch (seam verification of `DEC-0095`).
            asides(target).filter { aside ->
                runCatching { decode(aside.readText()) }.getOrNull()?.id == entry.id
            }.forEach { runCatching { it.delete() } }
        }
    }

    private companion object {
        /**
         * One `key\tvalue` per line, values escaped.
         *
         * Not JSON: `org.json` is a stub in the JVM test tier that throws on the first call, so a
         * class written against it could not be tested at all without a dependency this project
         * does not otherwise carry. Six scalar fields do not need one.
         */
        const val SEP = '\t'

        /** Between the main file's name and a moved-aside entry's stamp (`B-255`). */
        const val ASIDE = ".aside-"

        fun encode(entry: PendingDictation): String = buildString {
            fun line(key: String, value: String?) {
                if (value != null) append(key).append(SEP).append(escape(value)).append('\n')
            }
            line("audio", entry.audioPath)
            line("text", entry.transcript.text)
            line("language", entry.transcript.language)
            line("source", entry.transcript.source.name)
            line("engine", entry.transcript.engine)
            line("duration", entry.transcript.durationMs.toString())
            // The identity is the ORIGINAL audio path, and a bound entry's audio has moved into the
            // vault — so the identity is written on its own line, or a restored bound entry would
            // come back as a different dictation from the one the drain is looking for.
            line("id", entry.id)
            line("note", entry.noteId)
            line("created", entry.createdAt?.toString())
        }

        fun decode(raw: String): PendingDictation? {
            val fields = raw.lineSequence()
                .mapNotNull { line ->
                    val at = line.indexOf(SEP)
                    if (at <= 0) null else line.substring(0, at) to unescape(line.substring(at + 1))
                }
                .toMap()
            val text = fields["text"] ?: return null
            val audio = fields["audio"]
            val transcript = Transcript(
                text = text,
                language = fields["language"] ?: "auto",
                source = runCatching { SttSource.valueOf(fields["source"].orEmpty()) }
                    .getOrDefault(SttSource.LOCAL),
                engine = fields["engine"].orEmpty(),
                durationMs = fields["duration"]?.toLongOrNull() ?: 0L,
            )
            return PendingDictation(
                id = fields["id"] ?: audio ?: ("text:$text"),
                transcript = transcript,
                audioPath = audio,
                noteId = fields["note"],
                createdAt = fields["created"]?.toLongOrNull(),
            )
        }

        fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

        fun unescape(value: String): String {
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        'n' -> { out.append('\n'); i += 2 }
                        't' -> { out.append('\t'); i += 2 }
                        '\\' -> { out.append('\\'); i += 2 }
                        else -> { out.append(c); i++ }
                    }
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }
    }
}

/**
 * `MutableStateFlow.getAndUpdate` is in `kotlinx.coroutines` 1.10; this project is on 1.9, where
 * the function exists only as `update`. Written out rather than bumping a dependency for one
 * call — the loop is the same compare-and-set the rest of this class uses.
 */
private inline fun <T> MutableStateFlow<T>.getAndUpdate(transform: (T) -> T): T {
    while (true) {
        val current = value
        if (compareAndSet(current, transform(current))) return current
    }
}

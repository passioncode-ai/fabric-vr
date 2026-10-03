package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.runCatchingCancellable
import ai.passioncode.fabricvr.notes.Transcript
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A recording whose decode started and has not ended, as the journal holds it.
 *
 * @param owner the process that began it. An entry whose owner is not the reading journal's was
 *   left by a process that died mid-decode — the only way an entry outlives its decode.
 * @param attempts how many resumes have started on it. Persisted **before** each resume, so a
 *   recording that crashes the decoder cannot crash every launch after it.
 */
data class AwaitingTranscription(
    val audioPath: String,
    val language: String,
    val owner: String,
    val attempts: Int,
)

/**
 * Recordings awaiting transcription, written down before their decode starts (lifecycle contract
 * LC-03, audit F2 of 2026-10-03, `DEC-0103`).
 *
 * **The loss this closes.** A ten-minute dictation takes about thirteen minutes to decode on the
 * headset (`DEC-0032`), on the application scope so it survives the panel closing (`REQ-046`). It
 * does not survive the process: there is no foreground service, no WorkManager and no wake lock, so
 * with nothing visible Android's cached-app freezer suspends the process and the low-memory killer
 * may then end it. [DictationOutbox] holds only **finished** transcripts — its `offer` follows the
 * decode — so nothing on disk said a recording was still being turned into words. The transcript
 * never appeared, and the launch sweep deleted the WAV a day later.
 *
 * **One small file per recording**, `<wav name>.pending` in [dir], written atomically (scratch file,
 * `fsync`, rename — the outbox's three steps, for the same reason). An entry lives exactly as long
 * as a decode does in its own process: `VoiceViewModel` begins it after the WAV is on disk and ends
 * it on every outcome the person can see — words, nothing heard, a failure, *Stop transcribing*.
 * So the only entries a launch ever finds from another owner are the ones a dead process left, and
 * [resumeAwaitingTranscriptions] runs them.
 *
 * WorkManager would let the decode outlive the process itself rather than resume after it; it is
 * not a dependency here and a long-running worker needs a foreground-service notification whose
 * behaviour on Horizon OS needs a device check. That remainder is board row `B-264`.
 *
 * @param dir where entries live. Null means memory only — the process still knows its own entries,
 *   and nothing survives it. Only a test passes null.
 * @param owner this process's identity. A fresh random id per process; a test names it.
 * @param flush forces the bytes to disk before the rename commits them. Only a test replaces it.
 */
class TranscriptionJournal(
    private val dir: File?,
    val owner: String = UUID.randomUUID().toString(),
    private val flush: (FileOutputStream) -> Unit = { it.fd.sync() },
) {
    /** This process's own entries, by audio path. The disk is the record; this is what [owns] reads. */
    private val mine = ConcurrentHashMap<String, AwaitingTranscription>()

    private val fileLock = Any()

    /**
     * Writes [audioPath] down as awaiting transcription. Call it after the WAV is on disk and before
     * the decode starts. A write that fails is logged and the decode goes on: refusing to transcribe
     * because the safety net could not be written would lose the words for certain to avoid
     * possibly losing them.
     */
    fun begin(audioPath: String, language: String) {
        val entry = AwaitingTranscription(audioPath, language, owner, attempts = 0)
        mine[audioPath] = entry
        write(entry)
    }

    /** The decode of [audioPath] reached an outcome; nothing is owed to it any more. */
    fun end(audioPath: String) {
        mine.remove(audioPath)
        val target = fileFor(audioPath) ?: return
        synchronized(fileLock) { runCatching { target.delete() } }
    }

    /** Whether this process has [path]'s decode in flight. */
    fun owns(path: String?): Boolean = path != null && mine.containsKey(path)

    /**
     * Every recording an entry names, this process's and dead ones'. The launch sweep spares all of
     * them: an orphan's WAV can be older than the sweep's day by the time the headset is next used.
     */
    fun audioPaths(): Set<String> = mine.keys + readAll().map { it.audioPath }

    /** Entries left by a process that died mid-decode, oldest recording first. */
    fun orphans(): List<AwaitingTranscription> =
        readAll().filter { it.owner != owner && !mine.containsKey(it.audioPath) }.sortedBy { it.audioPath }

    /**
     * Takes [entry] for a resume in this process and counts the attempt **on disk first**.
     *
     * @return the entry as now owned, or null when it has already had [maxAttempts] — a recording
     *   that ended that many processes is not decoded again, because the decoder itself may be
     *   what is killing them, and a crash on every launch is worse than a note without a transcript.
     */
    fun claimForResume(entry: AwaitingTranscription, maxAttempts: Int): AwaitingTranscription? {
        if (entry.attempts >= maxAttempts) return null
        val claimed = entry.copy(owner = owner, attempts = entry.attempts + 1)
        mine[claimed.audioPath] = claimed
        write(claimed)
        return claimed
    }

    private fun fileFor(audioPath: String): File? = dir?.let { File(it, File(audioPath).name + SUFFIX) }

    private fun write(entry: AwaitingTranscription) {
        val target = fileFor(entry.audioPath) ?: return
        if (listOf(entry.audioPath, entry.language, entry.owner).any { it.contains('\n') || it.contains('\t') }) {
            // Never reached by a path this app makes; refused rather than written ambiguously.
            Log2.w("dictation.journal.unencodable")
            return
        }
        runCatching {
            synchronized(fileLock) {
                target.parentFile?.mkdirs()
                val bytes = encode(entry).toByteArray(Charsets.UTF_8)
                val temp = File(target.parentFile, target.name + ".tmp")
                FileOutputStream(temp).use { out -> out.write(bytes); out.flush(); flush(out) }
                if (!temp.renameTo(target)) {
                    FileOutputStream(target).use { out -> out.write(bytes); out.flush(); flush(out) }
                    temp.delete()
                }
            }
        }.onFailure { Log2.w("dictation.journal.write_failed", "reason" to it::class.java.simpleName) }
    }

    private fun readAll(): List<AwaitingTranscription> {
        val root = dir ?: return emptyList()
        val files = synchronized(fileLock) { root.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } }
            ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching { decode(file.readText()) }.getOrNull().also { entry ->
                if (entry == null) {
                    // A half-written or foreign file is evidence of nothing, and left in place it
                    // would be read — and fail — on every launch.
                    Log2.w("dictation.journal.unreadable")
                    synchronized(fileLock) { runCatching { file.delete() } }
                }
            }
        }
    }

    private companion object {
        const val SUFFIX = ".pending"

        fun encode(e: AwaitingTranscription): String =
            "audio\t${e.audioPath}\nlanguage\t${e.language}\nowner\t${e.owner}\nattempts\t${e.attempts}\n"

        fun decode(raw: String): AwaitingTranscription? {
            val fields = raw.lineSequence().mapNotNull { line ->
                val at = line.indexOf('\t')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }.toMap()
            return AwaitingTranscription(
                audioPath = fields["audio"] ?: return null,
                language = fields["language"] ?: "auto",
                owner = fields["owner"] ?: return null,
                attempts = fields["attempts"]?.toIntOrNull() ?: return null,
            )
        }
    }
}

/** What one launch's resume did, for the log line and for a test. */
data class ResumeSummary(
    val transcribed: Int = 0,
    val keptAsAudio: Int = 0,
    val nothingHeard: Int = 0,
    val missing: Int = 0,
)

/**
 * Runs every decode a dead process left unfinished (LC-03, audit F2, `DEC-0103`). Called once per
 * launch from `Graph.init`, after the outbox is restored and the sweep has spared the journalled
 * recordings.
 *
 * Each orphan ends one of four ways, and every one of them ends the entry:
 * - **words** → [deliver] hands them to the outbox with the original recording, which the next
 *   drain writes as a note exactly as if the first process had lived;
 * - **a failure, or too many attempts** → [keepRecordingOnly] makes the recording a note without a
 *   transcript (`SCN-004`'s promise), so the person can *Transcribe again* from its row — the
 *   recording is never left for the sweep;
 * - **nothing heard** → no note, as in the first process;
 * - **the WAV is gone** → nothing to resume.
 *
 * A cancellation (this process ending too) propagates and leaves the entry, attempt counted, for
 * the next launch.
 */
internal suspend fun resumeAwaitingTranscriptions(
    journal: TranscriptionJournal,
    readPcm: suspend (String) -> ShortArray,
    transcribe: suspend (ShortArray, String) -> Result<Transcript>,
    deliver: (Transcript, String) -> Unit,
    keepRecordingOnly: suspend (String) -> Result<Unit>,
    maxAttempts: Int = MAX_RESUME_ATTEMPTS,
): ResumeSummary {
    var summary = ResumeSummary()
    for (orphan in journal.orphans()) {
        val path = orphan.audioPath
        if (!File(path).isFile) {
            journal.end(path)
            summary = summary.copy(missing = summary.missing + 1)
            Log2.w("dictation.resume.missing")
            continue
        }
        suspend fun keepAsAudio(why: String) {
            keepRecordingOnly(path)
                .onSuccess {
                    journal.end(path)
                    summary = summary.copy(keptAsAudio = summary.keptAsAudio + 1)
                    Log2.w("dictation.resume.kept_audio", "why" to why)
                }
                // The entry stays: the next launch tries the hand-off again, and the sweep keeps
                // sparing the recording until one succeeds.
                .onFailure { Log2.e("dictation.resume.keep_failed", it, "why" to why) }
        }
        val claimed = journal.claimForResume(orphan, maxAttempts)
        if (claimed == null) {
            keepAsAudio("attempts")
            continue
        }
        val result = runCatchingCancellable { readPcm(path) }
            .mapCatching { pcm -> transcribe(pcm, claimed.language).getOrThrow() }
        result.fold(
            onSuccess = { transcript ->
                if (transcript.text.isBlank()) {
                    journal.end(path)
                    summary = summary.copy(nothingHeard = summary.nothingHeard + 1)
                } else {
                    // Into the outbox first, then the entry ends: there is no instant at which
                    // neither holds the recording.
                    deliver(transcript, path)
                    journal.end(path)
                    summary = summary.copy(transcribed = summary.transcribed + 1)
                }
            },
            onFailure = { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                keepAsAudio(failure::class.java.simpleName)
            },
        )
    }
    if (summary != ResumeSummary()) {
        Log2.i(
            "dictation.resumed",
            "transcribed" to summary.transcribed,
            "kept_audio" to summary.keptAsAudio,
            "nothing_heard" to summary.nothingHeard,
            "missing" to summary.missing,
        )
    }
    return summary
}

/**
 * Two: one resume after the kill, and one more if that resume was itself interrupted. A third
 * would only be reached by a recording that ended two processes in a row — then the decoder is the
 * suspect, and the recording becomes a note the person can re-run by hand.
 */
internal const val MAX_RESUME_ATTEMPTS = 2

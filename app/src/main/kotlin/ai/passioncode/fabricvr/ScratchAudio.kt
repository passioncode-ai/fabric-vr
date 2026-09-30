package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.Log2
import java.io.File

/**
 * Deletes recordings in the scratch directory that no note ever adopted.
 *
 * The recorder writes to `filesDir/audio` because at that moment the note does not exist yet;
 * `Vault.adoptAudio` then **moves** the file into the note's own folder. Every path that skips that
 * move leaves a `.wav` nothing references: a transcription the person abandoned, a process killed
 * mid-dictation, a `whisper_full` that outlived its activity (`C-06`). Nothing in the app listed
 * this directory before this function existed, so the leak was unbounded — sixteen-kilohertz mono
 * is about 32 kB a second, and a minute of thinking is 2 MB.
 *
 * @param olderThan the age below which a file is left alone. It is not a tidiness knob: a file
 *   younger than this may belong to a transcription **still running**, whose note is seconds from
 *   being written. Deleting that is the data loss this sweep exists to avoid causing.
 * @param spare absolute paths this sweep must not touch whatever their age — the recording of a
 *   dictation still in [DictationOutbox], restored from the last process and about to become a
 *   note (`REQ-046`). The age threshold would almost always protect it anyway; "almost always"
 *   is not a promise to make about the only copy of what somebody said.
 */
internal fun sweepScratchAudio(
    dir: File,
    now: Long = System.currentTimeMillis(),
    olderThan: Long = SCRATCH_MAX_AGE_MS,
    spare: Set<String> = emptySet(),
): Int {
    val files = dir.listFiles() ?: return 0
    var swept = 0
    files.forEach { file ->
        if (!file.isFile || now - file.lastModified() < olderThan) return@forEach
        if (file.absolutePath in spare) return@forEach
        if (runCatching { file.delete() }.getOrDefault(false)) swept++
    }
    if (swept > 0) Log2.i("audio.scratch.swept", "files" to swept)
    return swept
}

/** One day. Long enough that no live transcription is anywhere near it, short enough to matter. */
internal const val SCRATCH_MAX_AGE_MS = 24L * 60 * 60 * 1000

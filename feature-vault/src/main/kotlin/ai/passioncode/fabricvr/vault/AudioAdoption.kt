package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.notes.Note
import java.io.File

/**
 * Hands a fresh recording to the vault and answers with the path the note should record.
 *
 * The recorder writes to the app's private scratch because the note does not exist yet; the note is
 * saved once, with the path the recording ends at, so nothing later has to rewrite it. When the move
 * fails the scratch path is kept: the recording is still there, the mirror copies it on its next
 * pass, and the person is not told a file moved that did not.
 *
 * It lived in `:app`'s `ui` package (`E-21`) because that is where its callers were — vault
 * policy in a screen package, where `:feature-vault`'s own tests could not reach it. Ten lines
 * and two imports.
 */
suspend fun Vault.adoptOrKeep(note: Note, audioPath: String?): String? {
    if (audioPath == null) return null
    return adoptAudio(note, File(audioPath)).fold(
        onSuccess = { it.absolutePath },
        onFailure = { failure ->
            // **Kept only if it is there** (`B-256`). A recording that could not be moved but still
            // exists keeps its path and the mirror copies it later; one that is gone — the editor
            // moved it before a crash — answers null, or the note claims a recording it has not got.
            if (File(audioPath).isFile) {
                Log2.w("audio.adopt.kept", "note" to note.id, "reason" to failure::class.java.simpleName)
                audioPath
            } else {
                Log2.w("audio.adopt.gone", "note" to note.id, "reason" to failure::class.java.simpleName)
                null
            }
        },
    )
}

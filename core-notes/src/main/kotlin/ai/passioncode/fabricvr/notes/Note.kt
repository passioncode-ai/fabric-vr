package ai.passioncode.fabricvr.notes

/** How a transcript was produced. [LOCAL_FALLBACK] means a remote engine was preferred and failed. */
enum class SttSource { LOCAL, REMOTE, LOCAL_FALLBACK }

/**
 * The text an STT engine produced from one recording, with the receipt of how it was produced.
 *
 * [fallbackReason] is the receipt's most important field: when a configured speech server did not
 * answer, the person is entitled to know *that* and *why*, rather than silently getting a different
 * engine's output. It is null on every normal path.
 */
data class Transcript(
    val text: String,
    val language: String,
    val source: SttSource,
    val engine: String,
    val durationMs: Long,
    val fallbackReason: ai.passioncode.fabricvr.common.AppError? = null,
)

/** One captured item in the knowledge base. The vault's Markdown file is keyed by [id]. */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val tags: Set<String> = emptySet(),
    val createdAt: Long,
    val updatedAt: Long,
    val dayKey: String? = null,
    val audioPath: String? = null,
    val transcript: Transcript? = null,
) {
    /** What the list shows under the title. */
    val preview: String get() = body.lineSequence().firstOrNull { it.isNotBlank() }?.take(140).orEmpty()
}

/**
 * A note's identity and nothing else: the pair that locates its file in the vault.
 *
 * It exists so that reconciling the index against the files does not have to materialise every
 * note's text to do it. `DEFAULT_WINDOW`'s own reasoning puts a note at roughly 1 kB, so a base
 * of five thousand is about 5 MB of bodies read to answer a question about **names** — on a
 * headset whose heap is already a finding (`M17`, `M18`).
 */
data class NoteIdentity(
    val id: String,
    val createdAt: Long,
    /**
     * When the row last changed — what the reconcile compares with its file's modification time,
     * so a file that predates the row's last edit is written again (`B-240`). A column, not a body:
     * the argument above is unchanged.
     */
    val updatedAt: Long = 0L,
)

sealed interface NoteChange {
    data class Upserted(val note: Note) : NoteChange

    /**
     * [createdAt] travels with the deletion because the vault files the note away by the month it
     * was *created* in. Without it the mirror computed the folder from today and deleted nothing.
     */
    data class Deleted(val id: String, val createdAt: Long) : NoteChange
}

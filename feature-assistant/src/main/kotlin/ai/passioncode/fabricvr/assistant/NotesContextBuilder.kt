package ai.passioncode.fabricvr.assistant

import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NotesRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/** What the assistant is allowed to know: the person's own notes, most relevant first. */
class NotesContextBuilder(
    private val repository: NotesRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * Where the rendering runs. The DAO calls already suspend off the caller's thread; the render
     * did not, and it is up to sixty notes into a twelve-kilobyte string, built before the first
     * token of an answer appears (`I-09`).
     *
     * `Default`, not `IO`, and it is the one place in this app where that is right: this is pure
     * CPU over an in-memory list with no blocking call in it. `IO`'s pool is sized for threads
     * that are waiting.
     */
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    data class Context(val text: String, val titles: List<String>)

    /**
     * Two things here are deliberate and both were defects before.
     *
     * **The question is not a phrase.** Feeding it whole to the search ANDed every word, so a note
     * had to contain "what", "did" and "pick" as well as "panel" — matches were almost always
     * empty and "most relevant" quietly degenerated into "most recent".
     *
     * **An oversized note is truncated, never dropped.** Breaking out of the budget loop on the
     * first note too large for it left the context empty, and the assistant then told the person
     * they had no notes while their base was full.
     */
    suspend fun build(question: String, budget: Int = 12_000): Context {
        val terms = TOKEN.findAll(question).map { it.value }.filter(StopWords::isSignal).toList()
        val matching = if (terms.isEmpty()) emptyList() else repository.searchAny(terms, MATCH_LIMIT)
        val recent = repository.recent(RECENT_LIMIT)
        if (matching.isEmpty() && recent.isEmpty()) {
            return Context("The person has no notes yet.", emptyList())
        }

        val ordered = matching + recent.filterNot { note -> matching.any { it.id == note.id } }
        val used = mutableListOf<Note>()
        val text = withContext(cpu) {
            buildString {
                for (note in ordered) {
                    val remaining = budget - length
                    if (remaining <= MIN_BLOCK) break
                    val block = render(note)
                    append(if (block.length <= remaining) block else block.take(remaining))
                    used.add(note)
                }
            }
        }
        return Context(text, used.map { it.title.ifBlank { it.preview.take(40) } })
    }

    private fun render(note: Note): String {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .format(Instant.ofEpochMilli(note.updatedAt).atZone(zone))
        val transcript = note.transcript?.text?.takeIf { it.isNotBlank() && it != note.body }
        return buildString {
            appendLine("### ${note.title.ifBlank { "(untitled)" }} ($date)")
            if (note.tags.isNotEmpty()) appendLine("tags: ${note.tags.sorted().joinToString(", ")}")
            if (note.body.isNotBlank()) appendLine(note.body)
            if (transcript != null) appendLine(transcript)
            appendLine()
        }
    }

    companion object {
        private val TOKEN = Regex("[\\p{L}\\p{N}_]+")
        private const val MATCH_LIMIT = 20
        private const val RECENT_LIMIT = 40

        /** Below this there is no room for even a heading, so stop rather than append a stub. */
        private const val MIN_BLOCK = 80

        fun systemPrompt(context: String): String = """
            You are the assistant inside Fabric VR, a notes app the person uses in a VR headset.
            Answer from their notes below. When the notes do not contain the answer, say so plainly
            instead of guessing. Be brief: the person is reading this on a panel in a headset.

            NOTES
            $context
        """.trimIndent()
    }
}

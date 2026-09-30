package ai.passioncode.fabricvr.assistant

import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesContextBuilderTest {

    private fun note(id: String, title: String, body: String) = Note(
        id = id, title = title, body = body, createdAt = 1_758_240_000_000, updatedAt = 1_758_240_000_000,
    )

    /**
     * The fake matches on **any** term, exactly as the real repository now does. The previous fake
     * returned a canned list, which is why the builder's real weakness — feeding the whole question
     * to an AND query — was invisible to this suite.
     */
    private class FakeRepo(private val all: List<Note>) : NotesRepository {
        var lastTerms: List<String> = emptyList()

        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(all)
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = all.firstOrNull { it.id == id }
        override suspend fun upsert(note: Note): Result<Note> = Result.success(note)
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> = Result.success(all.first())
        override suspend fun count(): Int = 0
        override suspend fun recent(limit: Int): List<Note> = all.take(limit)
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> {
            lastTerms = terms
            return all.filter { note ->
                terms.any { term ->
                    note.title.contains(term, true) || note.body.contains(term, true) ||
                        note.transcript?.text?.contains(term, true) == true
                }
            }.take(limit)
        }
    }

    @Test fun `a six-word question still finds the note that shares one word`() = runTest {
        val hit = note("2", "Panel size", "2064x2208 per eye")
        val other = note("1", "Groceries", "milk")
        val repo = FakeRepo(listOf(other, hit))

        val context = NotesContextBuilder(repo).build("what panel size did I pick?")

        assertEquals("Panel size", context.titles.first())
        assertTrue(context.text.indexOf("Panel size") < context.text.indexOf("Groceries"))
        assertTrue("question words must not be searched", repo.lastTerms.none { it == "what" || it == "did" })
    }

    @Test fun `an oversized first note is truncated, never dropped`() = runTest {
        val huge = note("1", "Long", "x".repeat(50_000))
        val context = NotesContextBuilder(FakeRepo(listOf(huge))).build("anything", budget = 1_200)

        assertTrue(context.text.length <= 1_200)
        assertTrue("the base is not empty and must not be described as empty", context.titles.isNotEmpty())
        assertTrue(context.text.contains("Long"))
    }

    @Test fun `the budget is respected across many notes`() = runTest {
        val many = (1..50).map { note("$it", "Note $it", "x".repeat(500)) }
        val context = NotesContextBuilder(FakeRepo(many)).build("note", budget = 1_200)

        assertTrue(context.text.length <= 1_200)
        assertTrue(context.titles.size < many.size)
    }

    @Test fun `an empty base says so rather than inventing context`() = runTest {
        val context = NotesContextBuilder(FakeRepo(emptyList())).build("hello")

        assertEquals(emptyList<String>(), context.titles)
        assertTrue(context.text.contains("no notes"))
    }

    @Test fun `a transcript reaches the context`() = runTest {
        val spoken = note("1", "voice", "").copy(
            transcript = Transcript("the kelvin measurement", "en", SttSource.LOCAL, "whisper", 1),
        )
        val context = NotesContextBuilder(FakeRepo(listOf(spoken))).build("kelvin")

        assertTrue(context.text.contains("the kelvin measurement"))
    }
}

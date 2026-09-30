package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NoteChange
import ai.passioncode.fabricvr.notes.NotesRepository
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Search opens on an empty field, and an empty field is not an empty result. Whether the screen
 * says "nothing matches" or lists what the person wrote recently is decided here, not in the layout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule val main = MainDispatcherRule(dispatcher)

    private val recent = listOf(
        Note(id = "n1", title = "Panel size", body = "", createdAt = 2, updatedAt = 2),
        Note(id = "n2", title = "Standup", body = "", createdAt = 1, updatedAt = 1),
    )
    private val hit = listOf(recent.first())

    private inner class FakeRepo : NotesRepository {
        var searchFails = false
        var lastQuery: String? = null
        override fun observeNotes(tag: String?, limit: Int): Flow<List<Note>> = flowOf(recent)
        override fun observeChanges(): Flow<NoteChange> = emptyFlow()
        override fun observeTags(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun get(id: String): Note? = null
        override suspend fun upsert(note: Note): Result<Note> = Result.success(note)
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
        override fun search(query: String, limit: Int): Flow<List<Note>> {
            lastQuery = query
            return if (searchFails) flow { throw java.io.IOException("index gone") } else flowOf(hit)
        }
        override suspend fun noteForDay(dayKey: String): Note? = null
        override suspend fun dailyNote(day: LocalDate): Result<Note> = Result.success(recent.first())
        override suspend fun count(): Int = 0
        override suspend fun recent(limit: Int): List<Note> = recent.take(limit)
        override suspend fun searchAny(terms: List<String>, limit: Int): List<Note> = emptyList()
    }

    @Test fun `a blank query lists what was written recently, not nothing`() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeRepo())
        advanceUntilIdle()

        assertEquals(recent, vm.state.value.results)
        assertFalse("an unasked question must not read as an empty answer", vm.state.value.searched)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `typing searches and marks the question as asked`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = SearchViewModel(repo)
        advanceUntilIdle()

        vm.query("panel")
        assertTrue("the wait must be visible while the debounce runs", vm.state.value.loading)
        advanceUntilIdle()

        assertEquals("panel", repo.lastQuery)
        assertEquals(hit, vm.state.value.results)
        assertTrue(vm.state.value.searched)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `clearing goes back to the recent list`() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeRepo())
        vm.query("panel")
        advanceUntilIdle()

        vm.clear()
        advanceUntilIdle()

        assertEquals("", vm.state.value.query)
        assertEquals(recent, vm.state.value.results)
        assertFalse(vm.state.value.searched)
    }

    @Test fun `a failing index reports itself instead of looking like no matches`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { searchFails = true }
        val vm = SearchViewModel(repo)
        advanceUntilIdle()

        vm.query("panel")
        advanceUntilIdle()

        assertNotNull("the failure was swallowed into an empty result", vm.state.value.message)
        assertFalse(vm.state.value.loading)
    }
}

package ai.passioncode.fabricvr.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.passioncode.fabricvr.Graph
import ai.passioncode.fabricvr.common.UiMessage
import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.NotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

data class SearchUiState(
    val query: String = "",
    val results: List<Note> = emptyList(),
    val loading: Boolean = false,
    /** True once a non-blank query has been answered — "no matches" is not the same as "not asked". */
    val searched: Boolean = false,
    val message: UiMessage? = null,
)

class SearchViewModel(
    private val repository: NotesRepository = Graph.notes,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private val queries = MutableStateFlow("")

    init { observe() }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observe() {
        queries
            .debounce(120)
            // A blank field is not "no results": it lists what the person wrote recently, which is
            // almost always what they came to find.
            .flatMapLatest { query ->
                if (query.isBlank()) repository.observeNotes() else repository.search(query)
            }
            .catch { failure ->
                _state.update { it.copy(loading = false, message = failure.toMessage()) }
            }
            .onEach { results ->
                _state.update { it.copy(
                    results = results,
                    loading = false,
                    searched = queries.value.isNotBlank(),
                ) }
            }
            .launchIn(viewModelScope)
    }

    fun query(text: String) {
        _state.update { it.copy(query = text, loading = true) }
        queries.value = text
    }

    fun clear() = query("")

    fun retry() {
        _state.update { it.copy(message = null, loading = true) }
        observe()
        queries.value = queries.value
    }

    fun dismissMessage() { _state.update { it.copy(message = null) } }
}

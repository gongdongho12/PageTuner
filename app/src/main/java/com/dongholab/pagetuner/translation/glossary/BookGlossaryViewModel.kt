package com.dongholab.pagetuner.translation.glossary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BookGlossaryUiState(
    val glossary: BookGlossary? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class BookGlossaryViewModel(private val store: BookGlossaryStore) : ViewModel() {
    private val _uiState = MutableStateFlow(BookGlossaryUiState())
    val uiState: StateFlow<BookGlossaryUiState> = _uiState.asStateFlow()

    private var selection = 0L
    private var selectedBookId: String? = null
    private val writes = kotlinx.coroutines.sync.Mutex()

    fun selectBook(bookId: String?) {
        if (selectedBookId == bookId && (_uiState.value.glossary != null || _uiState.value.busy)) return
        selectedBookId = bookId
        val ticket = ++selection
        if (bookId == null) {
            _uiState.value = BookGlossaryUiState()
            return
        }
        viewModelScope.launch {
            _uiState.value = BookGlossaryUiState(busy = true)
            val result = runCatching { withContext(Dispatchers.IO) { store.load(bookId) } }
            if (ticket == selection) _uiState.value = result.fold(
                onSuccess = { BookGlossaryUiState(glossary = it) },
                onFailure = { BookGlossaryUiState(error = it.message ?: "Unable to read dictionary.") })
        }
    }

    fun upsert(entry: BookGlossaryEntry) = mutate { glossary ->
        val normalized = entry.copy(
            id = entry.id.ifBlank { UUID.randomUUID().toString() },
            sourceTerm = entry.sourceTerm.trim(),
            translatedTerm = entry.translatedTerm.trim(),
            displayTerm = entry.displayTerm.trim(),
        )
        require(normalized.sourceTerm.isNotBlank() && normalized.translatedTerm.isNotBlank())
        glossary.copy(entries = (glossary.entries.filterNot { it.id == normalized.id } + normalized)
            .sortedWith(compareBy<BookGlossaryEntry> { it.kind }.thenBy { it.sourceTerm.lowercase() }))
    }

    fun delete(entryId: String) = mutate { glossary ->
        glossary.copy(entries = glossary.entries.filterNot { it.id == entryId })
    }

    fun mergeLlmCharacterAliases(bookId: String, suggestions: List<CharacterAliasSuggestion>) {
        mutate(expectedBookId = bookId) { glossary -> BookGlossaryMerger.mergeCharacterAliases(glossary, suggestions) }
    }

    fun importSharedDictionary(raw: String): Boolean {
        return runCatching { BookGlossaryShareCodec.decode(raw) }
            .fold(
                onSuccess = { shared ->
                    mutate { glossary -> BookGlossaryMerger.mergeEntries(glossary, shared.entries) }
                    true
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message ?: "Unable to import dictionary.") }
                    false
                },
            )
    }

    private fun mutate(expectedBookId: String? = null, transform: (BookGlossary) -> BookGlossary) {
        val ticket = selection
        viewModelScope.launch {
            if (selection != ticket || _uiState.value.busy || _uiState.value.error != null) return@launch
            val current = _uiState.value.glossary ?: return@launch
            if (expectedBookId != null && (current.bookId != expectedBookId || selectedBookId != expectedBookId)) return@launch
            val updated = transform(current)
            if (updated == current) return@launch
            _uiState.update { it.copy(glossary = updated, busy = true, error = null) }
            val result = runCatching { writes.withLock { withContext(Dispatchers.IO) { store.save(updated) } } }
            if (selection == ticket) result
                .onSuccess { _uiState.update { state -> state.copy(busy = false) } }
                .onFailure { error -> _uiState.update { state -> state.copy(glossary = current, busy = false, error = error.message) } }
        }
    }

    class Factory(private val store: BookGlossaryStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BookGlossaryViewModel(store) as T
    }
}

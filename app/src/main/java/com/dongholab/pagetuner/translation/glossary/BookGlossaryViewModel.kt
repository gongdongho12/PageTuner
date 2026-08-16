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
import kotlinx.coroutines.withContext

data class BookGlossaryUiState(
    val glossary: BookGlossary? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class BookGlossaryViewModel(private val store: BookGlossaryStore) : ViewModel() {
    private val _uiState = MutableStateFlow(BookGlossaryUiState())
    val uiState: StateFlow<BookGlossaryUiState> = _uiState.asStateFlow()

    fun selectBook(bookId: String?) {
        if (bookId == null) {
            _uiState.value = BookGlossaryUiState()
            return
        }
        if (_uiState.value.glossary?.bookId == bookId) return
        viewModelScope.launch {
            _uiState.value = BookGlossaryUiState(busy = true)
            val glossary = withContext(Dispatchers.IO) { store.load(bookId) }
            _uiState.value = BookGlossaryUiState(glossary = glossary)
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

    fun mergeLlmCharacterAliases(suggestions: List<CharacterAliasSuggestion>) = mutate { glossary ->
        BookGlossaryMerger.mergeCharacterAliases(glossary, suggestions)
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

    private fun mutate(transform: (BookGlossary) -> BookGlossary) {
        val current = _uiState.value.glossary ?: return
        val updated = transform(current)
        if (updated == current) return
        _uiState.update { it.copy(glossary = updated, busy = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.save(updated) } }
                .onSuccess { _uiState.update { state -> state.copy(busy = false) } }
                .onFailure { error -> _uiState.update { state -> state.copy(busy = false, error = error.message) } }
        }
    }

    class Factory(private val store: BookGlossaryStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BookGlossaryViewModel(store) as T
    }
}

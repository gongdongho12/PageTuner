package com.dongholab.pagetuner.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val books: List<LocalBook> = emptyList(),
    val busy: Boolean = false,
)

sealed interface LibraryEvent {
    data class OpenedLocalBook(
        val result: LocalLibraryOpenResult,
    ) : LibraryEvent

    data class ImportedBook(
        val result: LocalLibraryOpenResult,
    ) : LibraryEvent

    data class DeletedBook(
        val book: LocalBook,
        val wasCurrentBook: Boolean,
    ) : LibraryEvent

    data class Error(
        val detail: String?,
    ) : LibraryEvent
}

class LibraryViewModel(
    private val localLibraryStore: LocalLibraryStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    fun loadInitialLibrary() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { state -> state.copy(busy = true) }
            runCatching {
                val books = localLibraryStore.listBooks()
                val result = books.firstOrNull()?.let { lastOpened ->
                    localLibraryStore.openBook(lastOpened.id)
                }
                val refreshedBooks = localLibraryStore.listBooks()
                result to refreshedBooks
            }.onSuccess { (result, books) ->
                _uiState.update { state -> state.copy(books = books) }
                if (result != null) {
                    _events.emit(LibraryEvent.OpenedLocalBook(result))
                }
            }.onFailure { error ->
                _events.emit(LibraryEvent.Error(error.message))
            }
            _uiState.update { state -> state.copy(busy = false) }
        }
    }

    fun openBook(book: LocalBook) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { state -> state.copy(busy = true) }
            runCatching {
                val result = localLibraryStore.openBook(book.id)
                val books = localLibraryStore.listBooks()
                result to books
            }.onSuccess { (result, books) ->
                _uiState.update { state -> state.copy(books = books) }
                _events.emit(LibraryEvent.OpenedLocalBook(result))
            }.onFailure { error ->
                _events.emit(LibraryEvent.Error(error.message))
            }
            _uiState.update { state -> state.copy(busy = false) }
        }
    }

    fun importBook(uri: Uri) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { state -> state.copy(busy = true) }
            runCatching {
                val result = localLibraryStore.importBook(uri)
                val books = localLibraryStore.listBooks()
                result to books
            }.onSuccess { (result, books) ->
                _uiState.update { state -> state.copy(books = books) }
                _events.emit(LibraryEvent.ImportedBook(result))
            }.onFailure { error ->
                _events.emit(LibraryEvent.Error(error.message))
            }
            _uiState.update { state -> state.copy(busy = false) }
        }
    }

    fun deleteBook(book: LocalBook, wasCurrentBook: Boolean) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { state -> state.copy(busy = true) }
            runCatching {
                val deleted = localLibraryStore.deleteBook(book.id)
                val books = localLibraryStore.listBooks()
                deleted to books
            }.onSuccess { (deleted, books) ->
                _uiState.update { state -> state.copy(books = books) }
                if (deleted) {
                    _events.emit(
                        LibraryEvent.DeletedBook(
                            book = book,
                            wasCurrentBook = wasCurrentBook,
                        ),
                    )
                } else {
                    _events.emit(LibraryEvent.Error("Local book metadata was not found."))
                }
            }.onFailure { error ->
                _events.emit(LibraryEvent.Error(error.message))
            }
            _uiState.update { state -> state.copy(busy = false) }
        }
    }

    fun updateProgress(bookId: String, pageIndex: Int) {
        viewModelScope.launch {
            runCatching {
                localLibraryStore.updateProgress(bookId, pageIndex)
                localLibraryStore.listBooks()
            }.onSuccess { books ->
                _uiState.update { state -> state.copy(books = books) }
            }.onFailure { error ->
                _events.emit(LibraryEvent.Error(error.message))
            }
        }
    }

    class Factory(
        private val localLibraryStore: LocalLibraryStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                return LibraryViewModel(localLibraryStore) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

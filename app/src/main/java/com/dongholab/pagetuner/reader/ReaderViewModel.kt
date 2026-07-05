package com.dongholab.pagetuner.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dongholab.pagetuner.document.LoadedReaderDocument
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ReaderUiState(
    val document: ReaderDocument,
    val pageIndex: Int = 0,
    val pdfSourceUri: String? = null,
    val currentBookId: String? = null,
    val controlsVisible: Boolean = true,
    val showDocumentDetails: Boolean = false,
) {
    val safePageIndex: Int = pageIndex.coerceIn(0, document.pageCount - 1)
    val currentPage: ReaderPage = document.pages[safePageIndex]
}

enum class ReaderPageMoveResult {
    Moved,
    FirstPage,
    LastPage,
}

class ReaderViewModel(
    initialDocument: ReaderDocument,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReaderUiState(document = initialDocument))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    fun applyLoadedDocument(
        loaded: LoadedReaderDocument,
        localBookId: String?,
        requestedPageIndex: Int,
    ) {
        _uiState.update { current ->
            ReaderUiState(
                document = loaded.document,
                pageIndex = requestedPageIndex.coerceIn(0, loaded.document.pageCount - 1),
                pdfSourceUri = loaded.pdfSourceUri,
                currentBookId = localBookId,
                controlsVisible = current.controlsVisible,
            )
        }
    }

    fun resetDocument(document: ReaderDocument) {
        _uiState.update { current ->
            ReaderUiState(
                document = document,
                controlsVisible = current.controlsVisible,
            )
        }
    }

    fun changePage(targetIndex: Int): ReaderPageMoveResult {
        val current = _uiState.value
        val boundedIndex = targetIndex.coerceIn(0, current.document.pageCount - 1)
        if (boundedIndex == current.pageIndex) {
            return if (targetIndex < current.pageIndex) {
                ReaderPageMoveResult.FirstPage
            } else {
                ReaderPageMoveResult.LastPage
            }
        }

        _uiState.update { state -> state.copy(pageIndex = boundedIndex) }
        return ReaderPageMoveResult.Moved
    }

    fun toggleControls() {
        _uiState.update { state -> state.copy(controlsVisible = !state.controlsVisible) }
    }

    fun showDocumentDetails() {
        _uiState.update { state -> state.copy(showDocumentDetails = true) }
    }

    fun hideDocumentDetails() {
        _uiState.update { state -> state.copy(showDocumentDetails = false) }
    }

    class Factory(
        private val initialDocument: ReaderDocument,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
                return ReaderViewModel(initialDocument) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

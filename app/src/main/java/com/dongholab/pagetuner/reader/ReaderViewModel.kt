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
    val manualRefreshToken: Int = 0,
    val searchQuery: String = "",
    val searchResults: List<ReaderSearchMatch> = emptyList(),
    val selectedSearchResultIndex: Int = -1,
    val bookmarkDraftLabel: String = "",
    val bookmarks: List<ReaderBookmark> = emptyList(),
    val noteDraftText: String = "",
    val annotations: List<ReaderAnnotation> = emptyList(),
) {
    val safePageIndex: Int = pageIndex.coerceIn(0, document.pageCount - 1)
    val currentPage: ReaderPage = document.pages[safePageIndex]
    val selectedSearchMatch: ReaderSearchMatch? =
        searchResults.getOrNull(selectedSearchResultIndex)
    val selectedSearchResultNumber: Int =
        if (selectedSearchResultIndex in searchResults.indices) selectedSearchResultIndex + 1 else 0
}

data class ReaderBookmark(
    val id: String,
    val pageIndex: Int,
    val label: String?,
    val createdAtMillis: Long,
)

data class ReaderAnnotation(
    val id: String,
    val type: ReaderAnnotationType,
    val pageIndex: Int,
    val text: String,
    val createdAtMillis: Long,
)

enum class ReaderAnnotationType {
    Highlight,
    Note,
}

data class ReaderSearchMatch(
    val pageIndex: Int,
    val segmentIndex: Int,
    val preview: String,
)

enum class ReaderPageMoveResult {
    Moved,
    FirstPage,
    LastPage,
}

sealed interface ReaderSearchMoveResult {
    data class Moved(
        val match: ReaderSearchMatch,
        val resultNumber: Int,
        val totalResults: Int,
    ) : ReaderSearchMoveResult

    data object NoQuery : ReaderSearchMoveResult
    data object NoResults : ReaderSearchMoveResult
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
        bookmarks: List<ReaderBookmark> = emptyList(),
        annotations: List<ReaderAnnotation> = emptyList(),
    ) {
        _uiState.update { current ->
            ReaderUiState(
                document = loaded.document,
                pageIndex = requestedPageIndex.coerceIn(0, loaded.document.pageCount - 1),
                pdfSourceUri = loaded.pdfSourceUri,
                currentBookId = localBookId,
                controlsVisible = current.controlsVisible,
                manualRefreshToken = current.manualRefreshToken,
                bookmarks = bookmarks
                    .filter { bookmark -> bookmark.pageIndex in 0 until loaded.document.pageCount }
                    .sortedBy { bookmark -> bookmark.pageIndex },
                annotations = annotations
                    .filter { annotation ->
                        annotation.pageIndex in 0 until loaded.document.pageCount &&
                            annotation.text.isNotBlank()
                    }
                    .sortedWith(
                        compareBy<ReaderAnnotation> { annotation -> annotation.pageIndex }
                            .thenBy { annotation -> annotation.createdAtMillis },
                    ),
            )
        }
    }

    fun resetDocument(document: ReaderDocument) {
        _uiState.update { current ->
            ReaderUiState(
                document = document,
                controlsVisible = current.controlsVisible,
                manualRefreshToken = current.manualRefreshToken,
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

        _uiState.update { state ->
            state.copy(
                pageIndex = boundedIndex,
                selectedSearchResultIndex = -1,
            )
        }
        return ReaderPageMoveResult.Moved
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { state ->
            val results = state.document.search(query)
            state.copy(
                searchQuery = query,
                searchResults = results,
                selectedSearchResultIndex = -1,
            )
        }
    }

    fun clearSearch() {
        _uiState.update { state ->
            state.copy(
                searchQuery = "",
                searchResults = emptyList(),
                selectedSearchResultIndex = -1,
            )
        }
    }

    fun updateBookmarkDraftLabel(label: String) {
        _uiState.update { state -> state.copy(bookmarkDraftLabel = label) }
    }

    fun addBookmark(): ReaderBookmark {
        val current = _uiState.value
        val pageIndex = current.safePageIndex
        val now = System.currentTimeMillis()
        val bookmark = ReaderBookmark(
            id = "bookmark-$pageIndex-$now",
            pageIndex = pageIndex,
            label = current.bookmarkDraftLabel.trim().takeIf { it.isNotBlank() },
            createdAtMillis = now,
        )
        _uiState.update { state ->
            state.copy(
                bookmarkDraftLabel = "",
                bookmarks = (state.bookmarks.filterNot { it.pageIndex == pageIndex } + bookmark)
                    .sortedBy { it.pageIndex },
            )
        }
        return bookmark
    }

    fun removeBookmark(bookmarkId: String) {
        _uiState.update { state ->
            state.copy(bookmarks = state.bookmarks.filterNot { it.id == bookmarkId })
        }
    }

    fun openBookmark(bookmarkId: String): ReaderBookmark? {
        val bookmark = _uiState.value.bookmarks.firstOrNull { it.id == bookmarkId }
            ?: return null
        _uiState.update { state ->
            state.copy(
                pageIndex = bookmark.pageIndex.coerceIn(0, state.document.pageCount - 1),
                selectedSearchResultIndex = -1,
            )
        }
        return bookmark
    }

    fun updateNoteDraftText(text: String) {
        _uiState.update { state -> state.copy(noteDraftText = text) }
    }

    fun addHighlight(): ReaderAnnotation {
        val current = _uiState.value
        return addAnnotation(
            type = ReaderAnnotationType.Highlight,
            pageIndex = current.safePageIndex,
            text = current.currentPage.plainText.toSingleLinePreview()
                .ifBlank { current.currentPage.chapterTitle.orEmpty() }
                .ifBlank { "Page ${current.safePageIndex + 1}" },
            clearDraft = false,
        )
    }

    fun addNote(): ReaderAnnotation? {
        val current = _uiState.value
        val text = current.noteDraftText.trim()
        if (text.isBlank()) return null
        return addAnnotation(
            type = ReaderAnnotationType.Note,
            pageIndex = current.safePageIndex,
            text = text,
            clearDraft = true,
        )
    }

    fun removeAnnotation(annotationId: String) {
        _uiState.update { state ->
            state.copy(annotations = state.annotations.filterNot { it.id == annotationId })
        }
    }

    fun openAnnotation(annotationId: String): ReaderAnnotation? {
        val annotation = _uiState.value.annotations.firstOrNull { it.id == annotationId }
            ?: return null
        _uiState.update { state ->
            state.copy(
                pageIndex = annotation.pageIndex.coerceIn(0, state.document.pageCount - 1),
                selectedSearchResultIndex = -1,
            )
        }
        return annotation
    }

    fun nextSearchResult(): ReaderSearchMoveResult {
        return moveToSearchResult(direction = 1)
    }

    fun previousSearchResult(): ReaderSearchMoveResult {
        return moveToSearchResult(direction = -1)
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

    fun requestManualRefresh() {
        _uiState.update { state ->
            state.copy(manualRefreshToken = state.manualRefreshToken + 1)
        }
    }

    private fun moveToSearchResult(direction: Int): ReaderSearchMoveResult {
        val current = _uiState.value
        if (current.searchQuery.isBlank()) return ReaderSearchMoveResult.NoQuery
        if (current.searchResults.isEmpty()) return ReaderSearchMoveResult.NoResults

        val selectedIndex = current.selectedSearchResultIndex
        val targetIndex = if (selectedIndex in current.searchResults.indices) {
            (selectedIndex + direction).floorMod(current.searchResults.size)
        } else if (direction > 0) {
            current.searchResults.indexOfFirst { it.pageIndex >= current.safePageIndex }
                .takeIf { it >= 0 } ?: 0
        } else {
            current.searchResults.indexOfLast { it.pageIndex <= current.safePageIndex }
                .takeIf { it >= 0 } ?: current.searchResults.lastIndex
        }
        val match = current.searchResults[targetIndex]

        _uiState.update { state ->
            state.copy(
                pageIndex = match.pageIndex,
                selectedSearchResultIndex = targetIndex,
            )
        }
        return ReaderSearchMoveResult.Moved(
            match = match,
            resultNumber = targetIndex + 1,
            totalResults = current.searchResults.size,
        )
    }

    private fun ReaderDocument.search(query: String): List<ReaderSearchMatch> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()

        return pages.flatMap { page ->
            page.segments.mapNotNull { segment ->
                val text = segment.text.trim()
                if (!text.lowercase().contains(normalized)) {
                    null
                } else {
                    ReaderSearchMatch(
                        pageIndex = page.index,
                        segmentIndex = segment.indexInPage,
                        preview = text.toSingleLinePreview(),
                    )
                }
            }
        }
    }

    private fun addAnnotation(
        type: ReaderAnnotationType,
        pageIndex: Int,
        text: String,
        clearDraft: Boolean,
    ): ReaderAnnotation {
        val now = System.currentTimeMillis()
        val annotation = ReaderAnnotation(
            id = "annotation-${type.name.lowercase()}-$pageIndex-$now",
            type = type,
            pageIndex = pageIndex,
            text = text,
            createdAtMillis = now,
        )
        _uiState.update { state ->
            state.copy(
                noteDraftText = if (clearDraft) "" else state.noteDraftText,
                annotations = (state.annotations + annotation).sortedWith(
                    compareBy<ReaderAnnotation> { it.pageIndex }.thenBy { it.createdAtMillis },
                ),
            )
        }
        return annotation
    }

    private fun String.toSingleLinePreview(): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .take(160)
    }

    private fun Int.floorMod(divisor: Int): Int {
        return ((this % divisor) + divisor) % divisor
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

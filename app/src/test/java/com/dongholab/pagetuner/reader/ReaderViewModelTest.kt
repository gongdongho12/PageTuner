package com.dongholab.pagetuner.reader

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.LoadedReaderDocument
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderViewModelTest {
    @Test
    fun appliesLoadedDocumentAndBoundsPageIndex() {
        val initial = documentWithPages("Initial", pageCount = 1)
        val loaded = documentWithPages("Loaded", pageCount = 3)
        val viewModel = ReaderViewModel(initial)

        viewModel.applyLoadedDocument(
            loaded = LoadedReaderDocument(document = loaded, pdfSourceUri = "content://book.pdf"),
            localBookId = "book-1",
            requestedPageIndex = 20,
        )

        val state = viewModel.uiState.value
        assertEquals("Loaded", state.document.title)
        assertEquals(2, state.pageIndex)
        assertEquals("content://book.pdf", state.pdfSourceUri)
        assertEquals("book-1", state.currentBookId)
    }

    @Test
    fun reportsPageBoundariesWithoutChangingState() {
        val viewModel = ReaderViewModel(documentWithPages("Book", pageCount = 2))

        assertEquals(ReaderPageMoveResult.FirstPage, viewModel.changePage(-1))
        assertEquals(0, viewModel.uiState.value.pageIndex)
        assertEquals(ReaderPageMoveResult.Moved, viewModel.changePage(1))
        assertEquals(1, viewModel.uiState.value.pageIndex)
        assertEquals(ReaderPageMoveResult.LastPage, viewModel.changePage(2))
        assertEquals(1, viewModel.uiState.value.pageIndex)
    }

    @Test
    fun togglesControlsAndDocumentDetails() {
        val viewModel = ReaderViewModel(documentWithPages("Book", pageCount = 1))

        assertTrue(viewModel.uiState.value.controlsVisible)
        viewModel.toggleControls()
        assertFalse(viewModel.uiState.value.controlsVisible)

        viewModel.showDocumentDetails()
        assertTrue(viewModel.uiState.value.showDocumentDetails)
        viewModel.hideDocumentDetails()
        assertFalse(viewModel.uiState.value.showDocumentDetails)
    }

    @Test
    fun incrementsManualRefreshToken() {
        val viewModel = ReaderViewModel(documentWithPages("Book", pageCount = 1))

        assertEquals(0, viewModel.uiState.value.manualRefreshToken)
        viewModel.requestManualRefresh()
        assertEquals(1, viewModel.uiState.value.manualRefreshToken)
    }

    @Test
    fun searchesCurrentDocumentAndMovesBetweenMatches() {
        val viewModel = ReaderViewModel(
            documentWithTextPages(
                "Book",
                listOf(
                    "Opening page",
                    "Needle is here",
                    "Another needle lives here",
                ),
            ),
        )

        viewModel.updateSearchQuery("needle")

        assertEquals(2, viewModel.uiState.value.searchResults.size)
        assertTrue(viewModel.nextSearchResult() is ReaderSearchMoveResult.Moved)
        assertEquals(1, viewModel.uiState.value.pageIndex)
        assertEquals(1, viewModel.uiState.value.selectedSearchResultNumber)

        viewModel.nextSearchResult()
        assertEquals(2, viewModel.uiState.value.pageIndex)
        assertEquals(2, viewModel.uiState.value.selectedSearchResultNumber)

        viewModel.nextSearchResult()
        assertEquals(1, viewModel.uiState.value.pageIndex)
        assertEquals(1, viewModel.uiState.value.selectedSearchResultNumber)
    }

    @Test
    fun reportsSearchEmptyAndNoResults() {
        val viewModel = ReaderViewModel(documentWithPages("Book", pageCount = 1))

        assertEquals(ReaderSearchMoveResult.NoQuery, viewModel.nextSearchResult())

        viewModel.updateSearchQuery("missing")

        assertEquals(ReaderSearchMoveResult.NoResults, viewModel.nextSearchResult())
        assertEquals(0, viewModel.uiState.value.searchResults.size)
    }

    @Test
    fun addsOpensAndRemovesBookmarks() {
        val viewModel = ReaderViewModel(documentWithPages("Book", pageCount = 3))
        viewModel.changePage(1)
        viewModel.updateBookmarkDraftLabel("Important")

        val bookmark = viewModel.addBookmark()

        assertEquals("Important", bookmark.label)
        assertEquals(1, bookmark.pageIndex)
        assertEquals("", viewModel.uiState.value.bookmarkDraftLabel)
        assertEquals(listOf(bookmark), viewModel.uiState.value.bookmarks)

        viewModel.changePage(2)
        val opened = viewModel.openBookmark(bookmark.id)

        assertEquals(bookmark, opened)
        assertEquals(1, viewModel.uiState.value.pageIndex)

        viewModel.removeBookmark(bookmark.id)
        assertTrue(viewModel.uiState.value.bookmarks.isEmpty())
    }

    @Test
    fun addsOpensAndRemovesAnnotations() {
        val viewModel = ReaderViewModel(documentWithPages("Book", pageCount = 3))
        viewModel.changePage(1)

        val highlight = viewModel.addHighlight()
        viewModel.updateNoteDraftText("Remember this.")
        val note = viewModel.addNote()

        assertEquals(ReaderAnnotationType.Highlight, highlight.type)
        assertEquals(1, highlight.pageIndex)
        assertEquals(ReaderAnnotationType.Note, note?.type)
        assertEquals("", viewModel.uiState.value.noteDraftText)
        assertEquals(2, viewModel.uiState.value.annotations.size)

        viewModel.changePage(2)
        val opened = viewModel.openAnnotation(highlight.id)

        assertEquals(highlight, opened)
        assertEquals(1, viewModel.uiState.value.pageIndex)

        viewModel.removeAnnotation(highlight.id)
        assertEquals(listOf(note), viewModel.uiState.value.annotations)
    }

    private fun documentWithPages(title: String, pageCount: Int) = ReaderDocument(
        id = title.lowercase(),
        title = title,
        format = DocumentFormat.TEXT,
        pages = (0 until pageCount).map { pageIndex ->
            ReaderPage(
                index = pageIndex,
                segments = listOf(
                    TextSegment(
                        id = "$title-$pageIndex",
                        pageIndex = pageIndex,
                        indexInPage = 0,
                        text = "Page ${pageIndex + 1}",
                    ),
                ),
            )
        },
    )

    private fun documentWithTextPages(title: String, pageTexts: List<String>) = ReaderDocument(
        id = title.lowercase(),
        title = title,
        format = DocumentFormat.TEXT,
        pages = pageTexts.mapIndexed { pageIndex, text ->
            ReaderPage(
                index = pageIndex,
                segments = listOf(
                    TextSegment(
                        id = "$title-$pageIndex",
                        pageIndex = pageIndex,
                        indexInPage = 0,
                        text = text,
                    ),
                ),
            )
        },
    )
}

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
}

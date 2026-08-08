package com.dongholab.pagetuner.ui.screen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.document.DocumentOutlineItem
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.sampleDocument
import com.dongholab.pagetuner.library.LibraryViewModel
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.library.toLocalBookAnnotation
import com.dongholab.pagetuner.library.toLocalBookBookmark
import com.dongholab.pagetuner.library.toReaderAnnotation
import com.dongholab.pagetuner.library.toReaderBookmark
import com.dongholab.pagetuner.reader.ReaderAnnotation
import com.dongholab.pagetuner.reader.ReaderAnnotationExport
import com.dongholab.pagetuner.reader.ReaderBookmark
import com.dongholab.pagetuner.reader.ReaderPageMoveResult
import com.dongholab.pagetuner.reader.ReaderSearchMoveResult
import com.dongholab.pagetuner.reader.ReaderViewModel
import com.dongholab.pagetuner.translation.TranslationRepository
import com.dongholab.pagetuner.translation.TranslationSettings
import com.dongholab.pagetuner.translation.TranslationViewModel
import com.dongholab.pagetuner.ui.text.readableMessage

/**
 * PageTurnerApp 에서 발생하는 모든 사용자 액션을 하나의 data class로 묶습니다.
 * - Composable 로컬 함수 인라인 제거 → 가독성/깊이 개선
 * - 각 Screen Composable 에 필요한 액션만 선택적으로 전달 가능
 */
data class ReaderActions(
    // Reader navigation
    val previousPage: () -> Unit,
    val nextPage: () -> Unit,
    val previousChapter: () -> Unit,
    val nextChapter: () -> Unit,
    // Search
    val updateSearchQuery: (String) -> Unit,
    val previousSearchResult: () -> Unit,
    val nextSearchResult: () -> Unit,
    val clearSearch: () -> Unit,
    // Bookmark
    val addBookmark: () -> Unit,
    val openBookmark: (ReaderBookmark) -> Unit,
    val removeBookmark: (ReaderBookmark) -> Unit,
    // Annotation
    val addHighlight: () -> Unit,
    val addNote: () -> Unit,
    val openAnnotation: (ReaderAnnotation) -> Unit,
    val removeAnnotation: (ReaderAnnotation) -> Unit,
    val exportAnnotations: () -> Unit,
    // Library
    val openLocalBook: (LocalBook) -> Unit,
    val deleteLocalBook: (LocalBook) -> Unit,
    val openFilePicker: () -> Unit,
    // Reader controls
    val requestManualRefresh: () -> Unit,
    val loadCachedCurrentPage: () -> Unit,
    val translateCurrentPage: () -> Unit,
    val prefetchDocument: () -> Unit,
    val clearTranslationCache: () -> Unit,
)

/**
 * ReaderActions 인스턴스를 생성합니다.
 * PageTurnerApp() 최상단에서 한 번만 호출합니다.
 */
fun buildReaderActions(
    context: Context,
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    translationViewModel: TranslationViewModel,
    document: ReaderDocument,
    currentPage: ReaderPage,
    pageIndex: Int,
    settings: TranslationSettings,
    repository: TranslationRepository,
    currentBookId: String?,
    tableOfContents: List<DocumentOutlineItem>,
    currentChapterIndex: Int,
    busy: Boolean,
    getAppStatusText: () -> String,
    setAppStatusText: (String) -> Unit,
    setAppErrorText: (String?) -> Unit,
    resetPdfCache: () -> Unit,
    openFilePicker: () -> Unit,
): ReaderActions {

    fun persistBookmarks(bookmarks: List<ReaderBookmark>) {
        val bookId = currentBookId ?: return
        libraryViewModel.updateBookmarks(bookId, bookmarks.map { it.toLocalBookBookmark() })
    }

    fun persistAnnotations(annotations: List<ReaderAnnotation>) {
        val bookId = currentBookId ?: return
        libraryViewModel.updateAnnotations(bookId, annotations.map { it.toLocalBookAnnotation() })
    }

    fun changePage(targetIndex: Int) {
        when (readerViewModel.changePage(targetIndex)) {
            ReaderPageMoveResult.Moved -> translationViewModel.clearPageTranslation()
            ReaderPageMoveResult.FirstPage -> {
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_first_page))
            }
            ReaderPageMoveResult.LastPage -> {
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_last_page))
            }
        }
    }

    fun handleSearchMove(result: ReaderSearchMoveResult) {
        when (result) {
            is ReaderSearchMoveResult.Moved -> {
                translationViewModel.clearPageTranslation()
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_search_result, result.resultNumber, result.totalResults, result.match.pageIndex + 1))
            }
            ReaderSearchMoveResult.NoQuery -> {
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_search_empty_query))
            }
            ReaderSearchMoveResult.NoResults -> {
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_search_no_results))
            }
        }
    }

    return ReaderActions(
        previousPage = { changePage(pageIndex - 1) },
        nextPage = { changePage(pageIndex + 1) },
        previousChapter = {
            if (currentChapterIndex <= 0) {
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_first_chapter))
            } else {
                changePage(tableOfContents[currentChapterIndex - 1].pageIndex)
            }
        },
        nextChapter = {
            val next = when {
                tableOfContents.isEmpty() -> null
                currentChapterIndex == -1 -> 0
                currentChapterIndex < tableOfContents.lastIndex -> currentChapterIndex + 1
                else -> null
            }
            if (next == null) {
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_last_chapter))
            } else {
                changePage(tableOfContents[next].pageIndex)
            }
        },
        updateSearchQuery = { readerViewModel.updateSearchQuery(it) },
        previousSearchResult = {
            if (!busy) handleSearchMove(readerViewModel.previousSearchResult())
        },
        nextSearchResult = {
            if (!busy) handleSearchMove(readerViewModel.nextSearchResult())
        },
        clearSearch = {
            readerViewModel.clearSearch()
            translationViewModel.clearStatus()
            setAppStatusText(context.getString(R.string.status_ready))
        },
        addBookmark = {
            if (!busy) {
                val bookmark = readerViewModel.addBookmark()
                persistBookmarks(readerViewModel.uiState.value.bookmarks)
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_added_bookmark, bookmark.pageIndex + 1))
            }
        },
        openBookmark = { bookmark ->
            if (!busy) {
                val opened = readerViewModel.openBookmark(bookmark.id) ?: return@ReaderActions
                translationViewModel.clearPageTranslation()
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_opened_bookmark, opened.pageIndex + 1))
            }
        },
        removeBookmark = { bookmark ->
            if (!busy) {
                readerViewModel.removeBookmark(bookmark.id)
                persistBookmarks(readerViewModel.uiState.value.bookmarks)
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_deleted_bookmark))
            }
        },
        addHighlight = {
            if (!busy) {
                val annotation = readerViewModel.addHighlight()
                persistAnnotations(readerViewModel.uiState.value.annotations)
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_added_highlight, annotation.pageIndex + 1))
            }
        },
        addNote = {
            if (!busy) {
                val annotation = readerViewModel.addNote()
                translationViewModel.clearStatus()
                if (annotation == null) {
                    setAppStatusText(context.getString(R.string.status_note_empty))
                    return@ReaderActions
                }
                persistAnnotations(readerViewModel.uiState.value.annotations)
                setAppStatusText(context.getString(R.string.status_added_note, annotation.pageIndex + 1))
            }
        },
        openAnnotation = { annotation ->
            if (!busy) {
                val opened = readerViewModel.openAnnotation(annotation.id) ?: return@ReaderActions
                translationViewModel.clearPageTranslation()
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_opened_annotation, opened.pageIndex + 1))
            }
        },
        removeAnnotation = { annotation ->
            if (!busy) {
                readerViewModel.removeAnnotation(annotation.id)
                persistAnnotations(readerViewModel.uiState.value.annotations)
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_deleted_annotation))
            }
        },
        exportAnnotations = {
            if (!busy) {
                val exportText = ReaderAnnotationExport.buildText(document, readerViewModel.uiState.value.annotations)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, document.title)
                    putExtra(Intent.EXTRA_TEXT, exportText)
                }
                context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.action_export_annotations)))
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_exported_annotations))
            }
        },
        openLocalBook = { book ->
            if (!busy) {
                translationViewModel.clearStatus()
                setAppStatusText(context.getString(R.string.status_opening_document))
                libraryViewModel.openBook(book)
            }
        },
        deleteLocalBook = { book ->
            if (!busy) {
                translationViewModel.clearStatus()
                libraryViewModel.deleteBook(book, wasCurrentBook = currentBookId == book.id)
            }
        },
        openFilePicker = openFilePicker,
        requestManualRefresh = {
            readerViewModel.requestManualRefresh()
            resetPdfCache()
            translationViewModel.clearStatus()
            setAppStatusText(context.getString(R.string.status_manual_refresh_requested))
        },
        loadCachedCurrentPage = {
            translationViewModel.loadCachedPage(document, currentPage, settings, repository, showMissingStatus = true)
        },
        translateCurrentPage = {
            if (!busy) translationViewModel.translatePage(document, currentPage, settings, repository)
        },
        prefetchDocument = {
            if (!busy) translationViewModel.prefetchDocument(document, currentPage, pageIndex, settings, repository)
        },
        clearTranslationCache = {
            if (!busy) translationViewModel.clearTranslationCache(document, settings, repository)
        },
    )
}

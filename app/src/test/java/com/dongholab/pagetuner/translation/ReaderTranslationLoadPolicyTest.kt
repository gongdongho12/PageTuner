package com.dongholab.pagetuner.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTranslationLoadPolicyTest {
    @Test
    fun doesNotRacePendingAutomaticTranslation() {
        assertFalse(
            shouldLoadCachedReaderTranslation(
                currentDocumentId = "chapter-9",
                currentPageIndex = 0,
                pendingTranslationDocumentId = "chapter-9",
                status = TranslationStatus.Starting(TranslationPaceMode.READING),
                readerLoad = ReaderTranslationLoadState(),
            ),
        )
    }

    @Test
    fun preservesFailureForInlineRetryInsteadOfReplacingItWithReady() {
        assertFalse(
            shouldLoadCachedReaderTranslation(
                currentDocumentId = "chapter-9",
                currentPageIndex = 0,
                pendingTranslationDocumentId = null,
                status = TranslationStatus.Error("network unavailable"),
                readerLoad = ReaderTranslationLoadState(
                    "chapter-9",
                    0,
                    ReaderTranslationLoadStage.Failed,
                ),
            ),
        )
    }

    @Test
    fun loadsCacheDuringNormalPageNavigation() {
        assertTrue(
            shouldLoadCachedReaderTranslation(
                currentDocumentId = "chapter-10",
                currentPageIndex = 0,
                pendingTranslationDocumentId = null,
                status = TranslationStatus.Ready,
                readerLoad = ReaderTranslationLoadState(),
            ),
        )
    }

    @Test
    fun failureFromPreviousPageDoesNotBlockNewPageCacheRefresh() {
        assertTrue(
            shouldLoadCachedReaderTranslation(
                currentDocumentId = "book",
                currentPageIndex = 4,
                pendingTranslationDocumentId = null,
                status = TranslationStatus.Error("page 3 failed"),
                readerLoad = ReaderTranslationLoadState(
                    "book",
                    3,
                    ReaderTranslationLoadStage.Failed,
                ),
            ),
        )
    }

    @Test
    fun fastCacheLookupDoesNotCauseAnExtraEinkRefresh() {
        val checking = ReaderTranslationLoadState(
            "book",
            4,
            ReaderTranslationLoadStage.CheckingCache,
        )

        assertFalse(
            shouldShowReaderTranslationIndicator(
                currentDocumentId = "book",
                currentPageIndex = 4,
                pendingTranslationDocumentId = null,
                pageHasText = true,
                hasVisibleTranslation = false,
                readerLoad = checking,
                rollingPageFlag = null,
                revealCacheLookup = false,
            ),
        )
        assertTrue(
            shouldShowReaderTranslationIndicator(
                currentDocumentId = "book",
                currentPageIndex = 4,
                pendingTranslationDocumentId = null,
                pageHasText = true,
                hasVisibleTranslation = false,
                readerLoad = checking,
                rollingPageFlag = null,
                revealCacheLookup = true,
            ),
        )
    }

    @Test
    fun backgroundTranslationDoesNotShowOnAnAlreadyReadyPage() {
        assertFalse(
            shouldShowReaderTranslationIndicator(
                currentDocumentId = "book",
                currentPageIndex = 4,
                pendingTranslationDocumentId = null,
                pageHasText = true,
                hasVisibleTranslation = true,
                readerLoad = ReaderTranslationLoadState(
                    "book",
                    4,
                    ReaderTranslationLoadStage.Ready,
                ),
                rollingPageFlag = TranslationPageFlag.Translating,
                revealCacheLookup = true,
            ),
        )
    }

    @Test
    fun pendingInitialLoadRemainsOwnedWhileTranslationIsStarting() {
        assertFalse(
            shouldClearPendingReaderTranslation(
                currentDocumentId = "chapter-9",
                pendingTranslationDocumentId = "chapter-9",
                hasTranslation = false,
                status = TranslationStatus.Starting(TranslationPaceMode.OFFLINE_PREFETCH),
            ),
        )
    }

    @Test
    fun pendingInitialLoadClearsOnlyForVisibleTranslationOrError() {
        assertTrue(
            shouldClearPendingReaderTranslation(
                currentDocumentId = "chapter-9",
                pendingTranslationDocumentId = "chapter-9",
                hasTranslation = true,
                status = TranslationStatus.TranslatedSavedPage(1),
            ),
        )
        assertTrue(
            shouldClearPendingReaderTranslation(
                currentDocumentId = "chapter-9",
                pendingTranslationDocumentId = "chapter-9",
                hasTranslation = false,
                status = TranslationStatus.Error("network"),
            ),
        )
    }

    @Test
    fun unmatchedFirstPageStateShowsLoadingUntilCacheResultArrives() {
        assertTrue(
            shouldShowInitialReaderTranslationLoading(
                currentDocumentId = "new-book",
                currentPageIndex = 0,
                pendingTranslationDocumentId = null,
                pageHasText = true,
                hasTranslation = false,
                readerLoad = ReaderTranslationLoadState(),
            ),
        )
        assertFalse(
            shouldShowInitialReaderTranslationLoading(
                currentDocumentId = "new-book",
                currentPageIndex = 0,
                pendingTranslationDocumentId = null,
                pageHasText = true,
                hasTranslation = false,
                readerLoad = ReaderTranslationLoadState(
                    "new-book",
                    0,
                    ReaderTranslationLoadStage.Missing,
                ),
            ),
        )
    }
}

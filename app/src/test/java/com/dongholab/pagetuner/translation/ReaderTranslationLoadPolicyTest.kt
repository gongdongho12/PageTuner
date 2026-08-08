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
                pendingTranslationDocumentId = "chapter-9",
                status = TranslationStatus.Starting(TranslationPaceMode.READING),
            ),
        )
    }

    @Test
    fun preservesFailureForInlineRetryInsteadOfReplacingItWithReady() {
        assertFalse(
            shouldLoadCachedReaderTranslation(
                currentDocumentId = "chapter-9",
                pendingTranslationDocumentId = null,
                status = TranslationStatus.Error("network unavailable"),
            ),
        )
    }

    @Test
    fun loadsCacheDuringNormalPageNavigation() {
        assertTrue(
            shouldLoadCachedReaderTranslation(
                currentDocumentId = "chapter-10",
                pendingTranslationDocumentId = null,
                status = TranslationStatus.Ready,
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

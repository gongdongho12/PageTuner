package com.dongholab.pagetuner.translation

/**
 * Decides whether passive cache loading may update the current reader page.
 * Active automatic translation and visible failures own the state until they
 * complete or the user explicitly retries.
 */
fun shouldLoadCachedReaderTranslation(
    currentDocumentId: String,
    currentPageIndex: Int,
    pendingTranslationDocumentId: String?,
    status: TranslationStatus,
    readerLoad: ReaderTranslationLoadState,
): Boolean {
    if (pendingTranslationDocumentId == currentDocumentId) return false
    if (
        status is TranslationStatus.Error &&
        readerLoad.matches(currentDocumentId, currentPageIndex)
    ) return false
    return true
}

/**
 * Page-scoped E-Ink feedback policy. Fast cache hits stay visually quiet so a page turn does not
 * spend an extra panel refresh on a message that disappears immediately.
 */
fun shouldShowReaderTranslationIndicator(
    currentDocumentId: String,
    currentPageIndex: Int,
    pendingTranslationDocumentId: String?,
    pageHasText: Boolean,
    hasVisibleTranslation: Boolean,
    readerLoad: ReaderTranslationLoadState,
    rollingPageFlag: TranslationPageFlag?,
    revealCacheLookup: Boolean,
): Boolean {
    if (!pageHasText) return false
    if (pendingTranslationDocumentId == currentDocumentId) return true

    val currentStage = readerLoad.takeIf {
        it.matches(currentDocumentId, currentPageIndex)
    }?.stage
    if (
        hasVisibleTranslation &&
        currentStage !in setOf(ReaderTranslationLoadStage.Queued, ReaderTranslationLoadStage.Translating)
    ) return false
    if (currentStage in setOf(ReaderTranslationLoadStage.Queued, ReaderTranslationLoadStage.Translating)) {
        return true
    }
    if (rollingPageFlag in setOf(TranslationPageFlag.Queued, TranslationPageFlag.Translating)) {
        return true
    }
    if (hasVisibleTranslation) return false
    return revealCacheLookup && (
        currentStage == null || currentStage == ReaderTranslationLoadStage.CheckingCache
    )
}

fun hasCurrentReaderTranslationError(
    currentDocumentId: String,
    currentPageIndex: Int,
    status: TranslationStatus,
    readerLoad: ReaderTranslationLoadState,
): Boolean = status is TranslationStatus.Error &&
    readerLoad.matches(currentDocumentId, currentPageIndex) &&
    readerLoad.stage == ReaderTranslationLoadStage.Failed

/** Keeps the initial loading owner until translated content is visible or a recoverable error is shown. */
fun shouldClearPendingReaderTranslation(
    currentDocumentId: String,
    pendingTranslationDocumentId: String?,
    hasTranslation: Boolean,
    status: TranslationStatus,
): Boolean {
    if (pendingTranslationDocumentId != currentDocumentId) return false
    return hasTranslation || status is TranslationStatus.Error
}

fun shouldShowInitialReaderTranslationLoading(
    currentDocumentId: String,
    currentPageIndex: Int,
    pendingTranslationDocumentId: String?,
    pageHasText: Boolean,
    hasTranslation: Boolean,
    readerLoad: ReaderTranslationLoadState,
): Boolean {
    if (!pageHasText || hasTranslation) return false
    if (pendingTranslationDocumentId == currentDocumentId) return true
    if (!readerLoad.matches(currentDocumentId, currentPageIndex)) return true
    return readerLoad.isLoading
}

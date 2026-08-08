package com.dongholab.pagetuner.translation

/**
 * Decides whether passive cache loading may update the current reader page.
 * Active automatic translation and visible failures own the state until they
 * complete or the user explicitly retries.
 */
fun shouldLoadCachedReaderTranslation(
    currentDocumentId: String,
    pendingTranslationDocumentId: String?,
    status: TranslationStatus,
): Boolean {
    if (pendingTranslationDocumentId == currentDocumentId) return false
    if (status is TranslationStatus.Error) return false
    return true
}

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

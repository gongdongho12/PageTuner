package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.ReaderPage

data class PageTranslation(
    val page: ReaderPage,
    val sourceLanguage: String,
    val targetLanguage: String,
    val segments: List<TranslatedSegment>,
    val completedFromCache: Boolean,
) {
    val text: String = segments.joinToString(separator = "\n\n") { it.translatedText }
}

data class PrefetchProgress(
    val completedPages: Int,
    val totalPages: Int,
    val activePageNumber: Int,
    val stage: PrefetchStage,
) {
    val fraction: Float
        get() = if (totalPages == 0) 1f else completedPages.toFloat() / totalPages.toFloat()
}

enum class PrefetchStage {
    PREPARING,
    SAVED,
}

enum class TranslationQueueItemStatus {
    Pending,
    Active,
    Saved,
    Failed,
    Cancelled,
}

data class TranslationQueueItem(
    val pageIndex: Int,
    val pageNumber: Int,
    val status: TranslationQueueItemStatus = TranslationQueueItemStatus.Pending,
    val attempts: Int = 0,
    val error: String? = null,
)

data class TranslationQueueState(
    val items: List<TranslationQueueItem> = emptyList(),
    val running: Boolean = false,
    val paused: Boolean = false,
    val cancelled: Boolean = false,
    val retrying: Boolean = false,
) {
    val totalPages: Int
        get() = items.size

    val completedPages: Int
        get() = items.count { it.status == TranslationQueueItemStatus.Saved }

    val failedPages: Int
        get() = items.count { it.status == TranslationQueueItemStatus.Failed }

    val activePageNumber: Int?
        get() = items.firstOrNull { it.status == TranslationQueueItemStatus.Active }?.pageNumber

    val fraction: Float
        get() = if (totalPages == 0) 0f else completedPages.toFloat() / totalPages.toFloat()

    val canPause: Boolean
        get() = running && !paused

    val canResume: Boolean
        get() = running && paused

    val canCancel: Boolean
        get() = running

    val canRetry: Boolean
        get() = !running && failedPages > 0
}

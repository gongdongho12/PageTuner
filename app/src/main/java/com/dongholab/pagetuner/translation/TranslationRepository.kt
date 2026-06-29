package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import kotlinx.coroutines.delay

class TranslationRepository(
    private val provider: TranslationProvider,
    private val cache: TranslationCache,
) {
    suspend fun translatePage(
        document: ReaderDocument,
        page: ReaderPage,
        settings: TranslationSettings,
        onProgress: suspend (TranslationProgress) -> Unit = {},
    ): PageTranslation {
        val keys = page.segments.associateWith { segment ->
            cacheKey(document.id, segment.id, settings)
        }
        val cached = cache.getMany(keys.values.toList())
        val completed = mutableMapOf<String, TranslatedSegment>()

        page.segments.forEach { segment ->
            val cachedRecord = cached[keys.getValue(segment).id]
            if (cachedRecord != null) {
                completed[segment.id] = TranslatedSegment(segment.id, cachedRecord.text)
            }
        }

        publishProgress(page, completed, "Loaded ${completed.size} cached segments.", onProgress)

        val missing = page.segments.filterNot { completed.containsKey(it.id) }
        if (missing.isEmpty()) {
            return PageTranslation(
                page = page,
                sourceLanguage = settings.normalizedSourceLanguage,
                targetLanguage = settings.normalizedTargetLanguage,
                segments = orderedSegments(page, completed),
                completedFromCache = true,
            )
        }

        val pacing = TranslationPacing(
            readingWordsPerMinute = settings.readingWordsPerMinute,
            mode = settings.paceMode,
        )

        missing
            .chunked(settings.batchSize.coerceIn(1, 24))
            .forEachIndexed { batchIndex, batch ->
                if (batchIndex > 0) {
                    val previousWords = missing
                        .chunked(settings.batchSize.coerceIn(1, 24))[batchIndex - 1]
                        .sumOf { it.wordCount }
                    delay(pacing.delayAfterBatchMillis(previousWords))
                }

                val translated = provider.translate(
                    TranslationRequest(
                        sourceLanguage = settings.normalizedSourceLanguage,
                        targetLanguage = settings.normalizedTargetLanguage,
                        segments = batch,
                    ),
                )

                val records = translated.mapNotNull { translatedSegment ->
                    val original = batch.firstOrNull { it.id == translatedSegment.segmentId }
                    original?.let {
                        CachedTranslation(
                            key = keys.getValue(original),
                            text = translatedSegment.translatedText,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                }
                cache.putAll(records)
                translated.forEach { completed[it.segmentId] = it }
                publishProgress(
                    page = page,
                    completed = completed,
                    status = "Translated ${completed.size}/${page.segments.size} segments.",
                    onProgress = onProgress,
                )
            }

        return PageTranslation(
            page = page,
            sourceLanguage = settings.normalizedSourceLanguage,
            targetLanguage = settings.normalizedTargetLanguage,
            segments = orderedSegments(page, completed),
            completedFromCache = false,
        )
    }

    suspend fun prefetchDocument(
        document: ReaderDocument,
        startPageIndex: Int,
        settings: TranslationSettings,
        onProgress: suspend (PrefetchProgress) -> Unit = {},
    ) {
        val orderedPages = document.pages.drop(startPageIndex) + document.pages.take(startPageIndex)
        orderedPages.forEachIndexed { index, page ->
            onProgress(
                PrefetchProgress(
                    completedPages = index,
                    totalPages = orderedPages.size,
                    activePageNumber = page.index + 1,
                    stage = PrefetchStage.PREPARING,
                ),
            )
            translatePage(document, page, settings.copy(paceMode = TranslationPaceMode.OFFLINE_PREFETCH))
            onProgress(
                PrefetchProgress(
                    completedPages = index + 1,
                    totalPages = orderedPages.size,
                    activePageNumber = page.index + 1,
                    stage = PrefetchStage.SAVED,
                ),
            )
        }
    }

    suspend fun loadCachedPage(
        document: ReaderDocument,
        page: ReaderPage,
        settings: TranslationSettings,
    ): PageTranslation? {
        if (!page.hasText) return null

        val keys = page.segments.associateWith { segment ->
            cacheKey(document.id, segment.id, settings)
        }
        val cached = cache.getMany(keys.values.toList())
        if (cached.size != page.segments.size) return null

        val segments = page.segments.map { segment ->
            val record = cached.getValue(keys.getValue(segment).id)
            TranslatedSegment(segmentId = segment.id, translatedText = record.text)
        }

        return PageTranslation(
            page = page,
            sourceLanguage = settings.normalizedSourceLanguage,
            targetLanguage = settings.normalizedTargetLanguage,
            segments = segments,
            completedFromCache = true,
        )
    }

    private fun cacheKey(
        documentId: String,
        segmentId: String,
        settings: TranslationSettings,
    ): TranslationCacheKey {
        return TranslationCacheKey(
            documentId = documentId,
            segmentId = segmentId,
            sourceLanguage = settings.normalizedSourceLanguage,
            targetLanguage = settings.normalizedTargetLanguage,
            providerId = provider.id,
        )
    }

    private suspend fun publishProgress(
        page: ReaderPage,
        completed: Map<String, TranslatedSegment>,
        status: String,
        onProgress: suspend (TranslationProgress) -> Unit,
    ) {
        onProgress(
            TranslationProgress(
                completedSegments = completed.size,
                totalSegments = page.segments.size,
                status = status,
                currentText = orderedSegments(page, completed).joinToString(separator = "\n\n") {
                    it.translatedText
                },
            ),
        )
    }

    private fun orderedSegments(
        page: ReaderPage,
        completed: Map<String, TranslatedSegment>,
    ): List<TranslatedSegment> {
        return page.segments.mapNotNull { completed[it.id] }
    }
}

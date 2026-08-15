package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import kotlinx.coroutines.delay

data class TranslationCacheStatus(
    val cachedSegments: Int,
    val totalSegments: Int,
) {
    val fraction: Float
        get() = if (totalSegments == 0) 1f else cachedSegments.toFloat() / totalSegments.toFloat()

    val isComplete: Boolean
        get() = totalSegments > 0 && cachedSegments == totalSegments
}

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

    /**
     * Translates multiple reader pages through shared provider requests, then restores
     * page boundaries when writing and reading the segment cache.
     */
    suspend fun translatePages(
        document: ReaderDocument,
        pages: List<ReaderPage>,
        settings: TranslationSettings,
    ): List<PageTranslation> {
        val translatablePages = pages.filter(ReaderPage::hasText)
        if (translatablePages.isEmpty()) return emptyList()

        val segments = translatablePages.flatMap(ReaderPage::segments)
        val keysBySegmentId = segments.associate { segment ->
            segment.id to cacheKey(document.id, segment.id, settings)
        }
        val cached = cache.getMany(keysBySegmentId.values.toList())
        val completed = mutableMapOf<String, TranslatedSegment>()
        segments.forEach { segment ->
            cached[keysBySegmentId.getValue(segment.id).id]?.let { record ->
                completed[segment.id] = TranslatedSegment(segment.id, record.text)
            }
        }
        val initiallyCachedIds = completed.keys.toSet()
        val missing = segments.filterNot { segment -> segment.id in completed }
        val requestBatches = TranslationRequestBatcher.batch(
            segments = missing,
            maxSegments = TranslationRequestBatcher.DefaultMaxSegments,
            maxCharacters = TranslationRequestBatcher.DefaultMaxCharacters,
        )
        val pacing = TranslationPacing(
            readingWordsPerMinute = settings.readingWordsPerMinute,
            mode = settings.paceMode,
        )

        requestBatches.forEachIndexed { batchIndex, batch ->
            if (batchIndex > 0) {
                delay(pacing.delayAfterBatchMillis(requestBatches[batchIndex - 1].sumOf(TextSegment::wordCount)))
            }
            val translated = provider.translate(
                TranslationRequest(
                    sourceLanguage = settings.normalizedSourceLanguage,
                    targetLanguage = settings.normalizedTargetLanguage,
                    segments = batch,
                ),
            )
            validateBatchResponse(batch, translated)
            cache.putAll(
                translated.map { translatedSegment ->
                    CachedTranslation(
                        key = keysBySegmentId.getValue(translatedSegment.segmentId),
                        text = translatedSegment.translatedText,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                },
            )
            translated.forEach { segment -> completed[segment.segmentId] = segment }
        }

        return translatablePages.map { page ->
            PageTranslation(
                page = page,
                sourceLanguage = settings.normalizedSourceLanguage,
                targetLanguage = settings.normalizedTargetLanguage,
                segments = orderedSegments(page, completed),
                completedFromCache = page.segments.all { segment -> segment.id in initiallyCachedIds },
            )
        }
    }

    suspend fun prefetchDocument(
        document: ReaderDocument,
        startPageIndex: Int,
        settings: TranslationSettings,
        onProgress: suspend (PrefetchProgress) -> Unit = {},
    ) {
        val orderedPages = document.pages.drop(startPageIndex) + document.pages.take(startPageIndex)
        var completedPages = 0
        orderedPages.chunked(PrefetchPageGroupSize).forEach { pages ->
            val firstPage = pages.first()
            onProgress(
                PrefetchProgress(
                    completedPages = completedPages,
                    totalPages = orderedPages.size,
                    activePageNumber = firstPage.index + 1,
                    stage = PrefetchStage.PREPARING,
                ),
            )
            translatePages(document, pages, settings.copy(paceMode = TranslationPaceMode.OFFLINE_PREFETCH))
            completedPages += pages.size
            onProgress(
                PrefetchProgress(
                    completedPages = completedPages,
                    totalPages = orderedPages.size,
                    activePageNumber = pages.last().index + 1,
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

    suspend fun cacheStatus(
        document: ReaderDocument,
        settings: TranslationSettings,
    ): TranslationCacheStatus {
        val keys = document.textSegments().map { segment ->
            cacheKey(document.id, segment.id, settings)
        }
        val cached = cache.getMany(keys)
        return TranslationCacheStatus(
            cachedSegments = cached.size,
            totalSegments = keys.size,
        )
    }

    suspend fun clearDocumentCache(
        document: ReaderDocument,
        settings: TranslationSettings,
    ): Int {
        val keys = document.textSegments().map { segment ->
            cacheKey(document.id, segment.id, settings)
        }
        return cache.deleteMany(keys)
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

    private fun validateBatchResponse(
        requested: List<TextSegment>,
        translated: List<TranslatedSegment>,
    ) {
        val requestedIds = requested.map(TextSegment::id)
        val translatedIds = translated.map(TranslatedSegment::segmentId)
        require(translatedIds.size == translatedIds.distinct().size) {
            "Translation provider returned duplicate segment IDs."
        }
        require(translatedIds.toSet() == requestedIds.toSet()) {
            "Translation provider response did not match the requested segments."
        }
    }

    private fun ReaderDocument.textSegments(): List<TextSegment> {
        return pages.flatMap { page -> page.segments }.filter { segment -> segment.text.isNotBlank() }
    }

    private companion object {
        const val PrefetchPageGroupSize = 10
    }
}

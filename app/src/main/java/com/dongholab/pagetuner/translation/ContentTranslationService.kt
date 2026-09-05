package com.dongholab.pagetuner.translation

import android.content.Context
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.core.translation.TranslationFieldSegmenter
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.translation.glossary.BookGlossary
import com.dongholab.pagetuner.translation.glossary.BookGlossaryStore
import com.dongholab.pagetuner.translation.glossary.GlossaryTranslationProvider

typealias TranslatableField = com.dongholab.pagetuner.core.translation.TranslatableField
typealias ContentTranslationRequest = com.dongholab.pagetuner.core.translation.ContentTranslationRequest
typealias ContentTranslationResult = com.dongholab.pagetuner.core.translation.ContentTranslationResult

interface ContentTranslationService {
    val providerId: String

    suspend fun translate(
        request: ContentTranslationRequest,
        settings: TranslationSettings,
        onProgress: suspend (TranslationProgress) -> Unit = {},
    ): ContentTranslationResult
}

/**
 * Converts arbitrary named text fields into the same stable segment model used by the reader.
 * Web catalogs, chapter downloads, and future remote sources should use this instead of building
 * synthetic [ReaderDocument] instances themselves.
 */
class DefaultContentTranslationService(
    provider: TranslationProvider,
    cache: TranslationCache,
) : ContentTranslationService {
    override val providerId: String = provider.id
    private val repository = TranslationRepository(provider, cache)

    override suspend fun translate(
        request: ContentTranslationRequest,
        settings: TranslationSettings,
        onProgress: suspend (TranslationProgress) -> Unit,
    ): ContentTranslationResult {
        val plan = TranslationDocumentFactory.create(request)
        if (plan.document.pages.first().segments.isEmpty()) {
            return ContentTranslationResult(
                values = emptyMap(),
                sourceLanguage = settings.normalizedSourceLanguage,
                targetLanguage = settings.normalizedTargetLanguage,
                providerId = providerId,
                completedFromCache = true,
            )
        }
        val pageTranslation = repository.translatePage(
            document = plan.document,
            page = plan.document.pages.first(),
            settings = settings,
            onProgress = onProgress,
        )
        val translatedBySegment = pageTranslation.segments.associate {
            it.segmentId to it.translatedText
        }
        return ContentTranslationResult(
            values = plan.fieldSegments.mapValues { (_, segmentIds) ->
                segmentIds.mapNotNull(translatedBySegment::get).joinToString("\n\n")
            },
            sourceLanguage = pageTranslation.sourceLanguage,
            targetLanguage = pageTranslation.targetLanguage,
            providerId = providerId,
            completedFromCache = pageTranslation.completedFromCache,
        )
    }
}

object ContentTranslationServiceFactory {
    fun create(
        context: Context,
        settings: TranslationSettings,
        glossary: BookGlossary? = null,
    ): ContentTranslationService {
        val applicationContext = context.applicationContext
        val glossaryStore = glossary?.let { BookGlossaryStore(applicationContext) }
        val provider = TranslationProviderFactory.create(
            settings = settings,
            initialCharacterAliases = glossary?.characterAliases.orEmpty(),
            onCharacterAliases = glossary?.let { selectedGlossary ->
                { suggestions -> glossaryStore?.mergeCharacterAliases(selectedGlossary.bookId, suggestions) }
            },
        )
        return DefaultContentTranslationService(
            provider = glossary
                ?.takeIf { it.activeEntries.isNotEmpty() }
                ?.let { GlossaryTranslationProvider(provider, it) }
                ?: provider,
            cache = JsonFileTranslationCache(applicationContext),
        )
    }
}

internal data class TranslationDocumentPlan(
    val document: ReaderDocument,
    val fieldSegments: Map<String, List<String>>,
)

internal object TranslationDocumentFactory {
    fun create(request: ContentTranslationRequest): TranslationDocumentPlan {
        val plan = TranslationFieldSegmenter.create(request)
        val segments = plan.segments.map { segment ->
            TextSegment(segment.id, pageIndex = 0, indexInPage = segment.ordinal, text = segment.text)
        }
        val page = ReaderPage(index = 0, segments = segments)
        return TranslationDocumentPlan(
            document = ReaderDocument(
                id = plan.documentId,
                title = request.title,
                format = DocumentFormat.TEXT,
                pages = listOf(page),
            ),
            fieldSegments = plan.fieldSegments,
        )
    }
}

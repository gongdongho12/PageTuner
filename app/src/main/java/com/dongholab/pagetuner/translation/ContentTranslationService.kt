package com.dongholab.pagetuner.translation

import android.content.Context
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.translation.glossary.BookGlossary
import com.dongholab.pagetuner.translation.glossary.GlossaryTranslationProvider

data class TranslatableField(
    val id: String,
    val text: String,
)

data class ContentTranslationRequest(
    /** Stable namespace shared by the same logical content type, for cache reuse across subsets. */
    val namespace: String,
    val title: String,
    val fields: List<TranslatableField>,
)

data class ContentTranslationResult(
    val values: Map<String, String>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val providerId: String,
    val completedFromCache: Boolean,
)

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
        val provider = TranslationProviderFactory.create(settings)
        return DefaultContentTranslationService(
            provider = glossary
                ?.takeIf { it.activeEntries.isNotEmpty() }
                ?.let { GlossaryTranslationProvider(provider, it) }
                ?: provider,
            cache = JsonFileTranslationCache(context.applicationContext),
        )
    }
}

internal data class TranslationDocumentPlan(
    val document: ReaderDocument,
    val fieldSegments: Map<String, List<String>>,
)

internal object TranslationDocumentFactory {
    private const val MaxSegmentCharacters = 400

    fun create(request: ContentTranslationRequest): TranslationDocumentPlan {
        require(request.namespace.isNotBlank()) { "Translation namespace cannot be blank." }
        val activeFields = request.fields.filter { it.text.isNotBlank() }
        require(activeFields.map { it.id }.distinct().size == activeFields.size) {
            "Translation field IDs must be unique within a request."
        }
        val documentId = DocumentIds.sha256("content-translation:${request.namespace}")
        val fieldSegments = linkedMapOf<String, MutableList<String>>()
        val segments = buildList {
            activeFields.forEach { field ->
                require(field.id.isNotBlank()) { "Translation field ID cannot be blank." }
                field.text.chunked(MaxSegmentCharacters).forEachIndexed { chunkIndex, chunk ->
                    val segmentId = DocumentIds.sha256(
                        "$documentId:${field.id}:$chunkIndex:$chunk",
                    ).take(24)
                    add(
                        TextSegment(
                            id = segmentId,
                            pageIndex = 0,
                            indexInPage = size,
                            text = chunk,
                        ),
                    )
                    fieldSegments.getOrPut(field.id) { mutableListOf() } += segmentId
                }
            }
        }
        val page = ReaderPage(index = 0, segments = segments)
        return TranslationDocumentPlan(
            document = ReaderDocument(
                id = documentId,
                title = request.title,
                format = DocumentFormat.TEXT,
                pages = listOf(page),
            ),
            fieldSegments = fieldSegments,
        )
    }
}

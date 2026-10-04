package com.dongholab.pagetuner.server.catalogTranslation

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.core.translation.CatalogTranslationService
import com.dongholab.pagetuner.core.translation.ContentTranslationResult
import com.dongholab.pagetuner.core.translation.TranslationFieldSegmenter
import com.dongholab.pagetuner.core.translation.TranslationLanguages
import com.dongholab.pagetuner.server.workflow.CreateTranslationJobRequest
import com.dongholab.pagetuner.server.workflow.SourceParagraph
import com.dongholab.pagetuner.server.workflow.StoredChapter
import com.dongholab.pagetuner.server.workflow.WorkflowProviders
import com.dongholab.pagetuner.server.workflow.settings
import com.dongholab.pagetuner.translation.ChapterTranslationEngine
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

fun interface CatalogTextTranslator {
    suspend fun translate(request: CatalogTranslationRequest, progress: suspend (Int, Int) -> Unit): List<CatalogTranslationItem>
}

/** The same catalog field mapping and 400-character identities used by the Android catalog adapter. */
@Component
class RuntimeCatalogTextTranslator(private val providers: WorkflowProviders) : CatalogTextTranslator {
    override suspend fun translate(request: CatalogTranslationRequest, progress: suspend (Int, Int) -> Unit): List<CatalogTranslationItem> =
        execute(request, progress, ChapterTranslationEngine())

    internal suspend fun execute(request: CatalogTranslationRequest, progress: suspend (Int, Int) -> Unit,
        engine: ChapterTranslationEngine): List<CatalogTranslationItem> {
        val catalog = CatalogTranslationService { content, languages, _ ->
            val plan = TranslationFieldSegmenter.create(content)
            val paragraphs = plan.segments.map { ContentParagraph(it.id, it.ordinal, it.text) }
            val chapter = ChapterContent(ChapterIdentity(BookIdentity("catalog-display", plan.documentId), "fields"),
                content.title, languages.source, paragraphs)
            // Reuse provider allowlists, canonical model/prompt identity and environment-key selection.
            val transientChapter = StoredChapter(UUID(0, 0), "catalog-display", plan.documentId, content.title, "", "fields", content.title, "",
                languages.source, chapter.sourceRevision, paragraphs.map { SourceParagraph(it.paragraphId, it.ordinal, it.text) }, Instant.EPOCH)
            val (config, key) = providers.resolve(CreateTranslationJobRequest(transientChapter.recordId, request.providerKind,
                languages.target, request.requestId, languages.source, request.endpoint, request.model, request.apiKey), transientChapter)
            val translated = engine.translate(chapter, config.settings(key), onProgress = progress)
            check(translated.map { it.paragraphId } == plan.segments.map { it.id } && translated.all { it.text.isNotBlank() }) {
                "Provider did not return complete catalog fields."
            }
            val values = translated.associate { it.paragraphId to it.text }
            ContentTranslationResult(plan.fieldSegments.mapValues { (_, ids) -> ids.joinToString("") { values.getValue(it) } },
                languages.source, languages.target, config.translationProviderId, false)
        }
        val translated = catalog.translate(request.items, TranslationLanguages(request.sourceLanguage, request.targetLanguage))
        return request.items.map { item -> translated.getValue(item.key).let { CatalogTranslationItem(item.key, it.title, it.description, it.targetLanguage) } }
    }
}

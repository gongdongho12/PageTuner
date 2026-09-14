package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.translation.glossary.BookGlossary
import com.dongholab.pagetuner.translation.glossary.GlossaryTranslationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Device-independent execution. Checkpoints always contain complete original paragraphs. */
class ChapterTranslationEngine(
    private val providerFactory: (TranslationSettings) -> TranslationProvider = { TranslationProviderFactory.create(it) },
    private val maxRetries: Int = 2,
    private val retryDelayMillis: Long = 500,
    private val maxParagraphCharacters: Int = 1_000_000,
) {
    init {
        require(maxRetries in 0..5) { "maxRetries must be between zero and five." }
        require(retryDelayMillis in 0..60_000) { "retryDelayMillis must be bounded." }
        require(maxParagraphCharacters in 1..1_000_000) { "maxParagraphCharacters must be bounded." }
    }

    suspend fun translate(
        chapter: ChapterContent,
        settings: TranslationSettings,
        glossary: BookGlossary? = null,
        completed: Map<String, String> = emptyMap(),
        onParagraph: suspend (paragraphId: String, text: String) -> Unit = { _, _ -> },
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<TranslatedParagraph> {
        // All caller-owned collections are snapshotted before the first suspension.
        val paragraphs = chapter.paragraphs.toList()
        val results = completed.toMutableMap()
        val glossarySnapshot = glossary?.copy(entries = glossary.entries.toList())
        require(chapter.copy(paragraphs = paragraphs).sourceRevision == chapter.sourceRevision) {
            "Chapter paragraphs changed after their source revision was calculated."
        }
        require(paragraphs.isNotEmpty()) { "Chapter must contain paragraphs." }
        val paragraphIds = paragraphs.map { it.paragraphId }
        require(paragraphIds.all(String::isNotBlank) && paragraphIds.distinct().size == paragraphIds.size) {
            "Chapter paragraph IDs must be nonblank and unique."
        }
        require(paragraphs.map { it.ordinal }.zipWithNext().all { (left, right) -> left < right }) {
            "Chapter paragraphs must have strictly increasing ordinals."
        }
        require(paragraphs.all { it.text.isNotBlank() && it.text.length <= maxParagraphCharacters }) {
            "Chapter paragraphs must be nonblank and within the whole-paragraph request limit."
        }
        require(results.keys.all { it in paragraphIds } && results.values.all(String::isNotBlank)) {
            "Completed checkpoints must contain only known paragraph IDs and nonblank translations."
        }
        require(settings.normalizedSourceLanguage == "auto" ||
            settings.normalizedSourceLanguage.equals(chapter.sourceLanguage.trim(), ignoreCase = true)
        ) { "Source language must match the chapter language." }
        require(settings.normalizedTargetLanguage != "auto") { "A concrete target language is required." }
        val effectiveSettings = settings.copy(sourceLanguage = chapter.sourceLanguage.trim())
        val chunkLimit = if (settings.providerKind == TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML) 1_000 else 4_000
        val chunksByParagraph = paragraphs.filterNot { it.paragraphId in results }.associate { paragraph ->
            paragraph.paragraphId to ParagraphTranslationChunker.split(
                paragraph.paragraphId, paragraph.text, chunkLimit, glossarySnapshot?.activeEntries.orEmpty(),
            )
        }
        val paragraphsByChunkId = chunksByParagraph.flatMap { (paragraphId, chunks) -> chunks.map { it.id to paragraphId } }.toMap()
        val chunks = chunksByParagraph.values.flatten()
        require(paragraphsByChunkId.size == chunks.size) { "Provider chunk IDs must be unique." }
        val segments = chunks.filter { it.text.isNotBlank() }.mapIndexed { index, chunk -> TextSegment(chunk.id, 0, index, chunk.text) }
        val translatedChunks = mutableMapOf<String, String>()
        val batches = TranslationRequestBatcher.batch(
            segments,
            maxSegments = settings.batchSize.coerceIn(1, TranslationRequestBatcher.DefaultMaxSegments),
        )
        currentCoroutineContext().ensureActive()
        onProgress(results.size, paragraphs.size)
        currentCoroutineContext().ensureActive()
        if (batches.isEmpty()) return paragraphs.map { TranslatedParagraph(it.paragraphId, results.getValue(it.paragraphId)) }

        val baseProvider = providerFactory(effectiveSettings)
        val provider = if (glossarySnapshot?.activeEntries?.isNotEmpty() == true) {
            GlossaryTranslationProvider(baseProvider, glossarySnapshot)
        } else baseProvider
        val pacing = TranslationPacing(settings.readingWordsPerMinute, settings.paceMode)
        batches.forEachIndexed { index, batch ->
            currentCoroutineContext().ensureActive()
            if (index > 0) delay(pacing.delayAfterBatchMillis(batches[index - 1].sumOf(TextSegment::wordCount)))
            val request = TranslationRequest(effectiveSettings.normalizedSourceLanguage, settings.normalizedTargetLanguage, batch)
            val translated = translateWithRetry(provider, request)
            validateTranslationResponse(batch, translated, provider.id)
            val byId = translated.associateBy(TranslatedSegment::segmentId)
            batch.forEach { segment ->
                currentCoroutineContext().ensureActive()
                translatedChunks[segment.id] = byId.getValue(segment.id).translatedText
                val paragraphId = paragraphsByChunkId.getValue(segment.id)
                val paragraphChunks = chunksByParagraph.getValue(paragraphId)
                if (paragraphChunks.any { it.text.isNotBlank() && it.id !in translatedChunks }) return@forEach
                val text = paragraphChunks.joinToString("") { chunk ->
                    if (chunk.text.isBlank()) chunk.originalText else chunk.restore(translatedChunks.getValue(chunk.id))
                }
                check(text.isNotBlank()) { "Completed paragraph translation must not be blank." }
                onParagraph(paragraphId, text)
                // Only advance after the caller's durable checkpoint succeeds.
                currentCoroutineContext().ensureActive()
                results[paragraphId] = text
                onProgress(results.size, paragraphs.size)
            }
        }
        currentCoroutineContext().ensureActive()
        return paragraphs.map { TranslatedParagraph(it.paragraphId, results.getValue(it.paragraphId)) }
    }

    private suspend fun translateWithRetry(provider: TranslationProvider, request: TranslationRequest): List<TranslatedSegment> {
        repeat(maxRetries + 1) { attempt ->
            try {
                currentCoroutineContext().ensureActive()
                return provider.translate(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: TranslationProviderException) {
                val retryable = error.failure.kind in setOf(
                    TranslationProviderErrorKind.Network,
                    TranslationProviderErrorKind.Server,
                    TranslationProviderErrorKind.RateLimited,
                )
                if (!retryable || attempt == maxRetries) throw error
                delay((retryDelayMillis * (1L shl attempt)).coerceAtMost(60_000))
            }
        }
        error("Unreachable retry state.")
    }
}

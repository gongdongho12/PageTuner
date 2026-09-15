package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.translation.StoredTranslation
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import com.dongholab.pagetuner.core.translation.TranslationSaveResult
import com.dongholab.pagetuner.core.translation.TranslationStore
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.translation.CachedTranslation
import com.dongholab.pagetuner.translation.TranslationCache
import com.dongholab.pagetuner.translation.TranslationCacheKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** cacheProviderId is the existing TranslationProvider.id, including any endpoint/model/glossary suffix. */
data class TranslationCacheVariant(
    val sourceLanguage: String,
    val targetLanguage: String,
    val cacheProviderId: String,
    val translationProviderId: String,
    val modelId: String,
    val promptRevision: String,
    val glossaryRevision: String,
) {
    init {
        require(sourceLanguage.isNotBlank() && targetLanguage.isNotBlank())
        require(cacheProviderId.isNotBlank() && translationProviderId.isNotBlank())
    }
}

/**
 * Explicit, complete one-paragraph/one-segment mapping. Split or merged paragraphs need a separate,
 * lossless source adapter; page indices are deliberately never used to invent paragraph identities.
 */
class TranslationCacheChapterMapping(
    document: ReaderDocument,
    chapter: ChapterContent,
    paragraphToSegmentId: Map<String, String>,
    val variant: TranslationCacheVariant,
) {
    val chapter: ChapterContent = chapter.copy(paragraphs = chapter.paragraphs.toList())
    private val segmentIds: Map<String, String> = paragraphToSegmentId.toMap()
    val keys: List<TranslationCacheKey>

    init {
        require(document.id.isNotBlank()) { "A stable local document ID is required." }
        require(this.chapter.paragraphs.isNotEmpty()) { "An empty chapter cannot be synchronized." }
        require(variant.sourceLanguage == chapter.sourceLanguage) { "Cache and chapter source languages must match." }
        require(segmentIds.keys == this.chapter.paragraphs.map { it.paragraphId }.toSet()) { "Map every chapter paragraph exactly once." }
        require(segmentIds.values.toSet().size == segmentIds.size) { "A reader segment may map to only one paragraph." }
        val segments = document.pages.flatMap { it.segments }
        require(segments.map { it.id }.distinct().size == segments.size) { "Reader segment IDs must be unique." }
        val byId = segments.associateBy { it.id }
        this.chapter.paragraphs.forEach { paragraph ->
            val segment = requireNotNull(byId[segmentIds.getValue(paragraph.paragraphId)]) { "Mapped segment does not belong to this document." }
            require(segment.text == paragraph.text) { "Mapped segment source text differs from the chapter paragraph." }
        }
        keys = this.chapter.paragraphs.map { paragraph ->
            TranslationCacheKey(document.id, segmentIds.getValue(paragraph.paragraphId), variant.sourceLanguage, variant.targetLanguage, variant.cacheProviderId)
        }
    }

    fun toArtifact(records: Map<String, CachedTranslation>): TranslationArtifact {
        require(records.keys == keys.map { it.id }.toSet()) { "A complete, matching chapter cache is required." }
        val paragraphs = chapter.paragraphs.mapIndexed { index, paragraph ->
            val key = keys[index]
            val record = records.getValue(key.id)
            require(record.key == key) { "Cached document, segment, language or provider does not match." }
            require(record.text.isNotBlank()) { "Every chapter paragraph must have a nonblank translation." }
            TranslatedParagraph(paragraph.paragraphId, record.text)
        }
        return buildArtifact(paragraphs)
    }

    fun toCache(artifact: TranslationArtifact, updatedAtMillis: Long): List<CachedTranslation> {
        require(artifact.paragraphs.map { it.paragraphId } == chapter.paragraphs.map { it.paragraphId }) { "Stored translation must contain the complete chapter in source order." }
        require(artifact == buildArtifact(artifact.paragraphs)) { "Stored translation source revision or translation variant does not match." }
        require(artifact.paragraphs.all { it.text.isNotBlank() }) { "Every stored paragraph must have a nonblank translation." }
        return artifact.paragraphs.mapIndexed { index, paragraph ->
            CachedTranslation(keys[index], paragraph.text, updatedAtMillis)
        }
    }

    private fun buildArtifact(paragraphs: List<TranslatedParagraph>): TranslationArtifact = TranslationArtifact(
        chapter.identity,
        chapter.sourceRevision,
        variant.sourceLanguage,
        variant.targetLanguage,
        variant.translationProviderId,
        variant.modelId,
        variant.promptRevision,
        variant.glossaryRevision,
        paragraphs,
    )
}

class TranslationCacheConflictException : IllegalStateException("Existing local translations differ from the stored translation.")

/** Manual publish/restore only. Validation finishes before a remote save or a local cache write. */
class TranslationCacheSyncService(
    private val store: TranslationStore,
    private val cache: TranslationCache,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun publish(mapping: TranslationCacheChapterMapping): TranslationSaveResult {
        currentCoroutineContext().ensureActive()
        val artifact = mapping.toArtifact(cache.getMany(mapping.keys))
        currentCoroutineContext().ensureActive()
        val result = store.save(artifact)
        currentCoroutineContext().ensureActive()
        require(result.translation.artifact == artifact) { "Translation store returned a different artifact." }
        return result
    }

    suspend fun restore(recordId: String, mapping: TranslationCacheChapterMapping, replaceExisting: Boolean = false): StoredTranslation {
        currentCoroutineContext().ensureActive()
        val stored = store.get(recordId)
        require(stored.recordId == recordId) { "Translation store returned a different record." }
        val records = mapping.toCache(stored.artifact, nowMillis())
        currentCoroutineContext().ensureActive()
        if (replaceExisting) {
            cache.putAll(records)
        } else if (!cache.putAllIfCompatible(records)) {
            throw TranslationCacheConflictException()
        }
        return stored
    }
}

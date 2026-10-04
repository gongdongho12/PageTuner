package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.core.translation.StoredTranslation
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import com.dongholab.pagetuner.core.translation.TranslationSaveResult
import com.dongholab.pagetuner.core.translation.TranslationStore
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.translation.CachedTranslation
import com.dongholab.pagetuner.translation.JsonFileTranslationCache
import com.dongholab.pagetuner.translation.TranslationCache
import com.dongholab.pagetuner.translation.TranslationCacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslationCacheSyncServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun completeCachePublishesAndRestoresThroughStoreWithoutChangingLegacyKeys() = runTest {
        val mapping = mapping()
        val original = records(mapping)
        val cache = JsonFileTranslationCache(temporaryFolder.newFile("source.json").apply { delete() })
        cache.putAll(original)
        val store = RecordingStore()
        val first = TranslationCacheSyncService(store, cache).publish(mapping)
        val second = TranslationCacheSyncService(store, cache).publish(mapping)
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.translation, second.translation)
        assertEquals(chapter.sourceRevision, first.translation.artifact.sourceRevision)
        assertEquals("translator", first.translation.artifact.providerId)
        assertEquals(listOf("p-1", "p-2"), first.translation.artifact.paragraphs.map { it.paragraphId })
        val restoredFile = temporaryFolder.newFolder("restored").resolve("cache.json")
        val restored = JsonFileTranslationCache(restoredFile)
        TranslationCacheSyncService(store, restored) { 1234L }.restore(first.translation.recordId, mapping)
        val reread = JsonFileTranslationCache(restoredFile).getMany(mapping.keys)
        assertEquals(original.map { it.key }, mapping.keys)
        assertEquals(original.map { it.text }, mapping.keys.map { reread.getValue(it.id).text })
        val diskRecords = JSONObject(restoredFile.readText(Charsets.UTF_8)).getJSONObject("records")
        assertEquals(original.map { it.text }, mapping.keys.map { diskRecords.getJSONObject(it.id).getString("text") })
        assertTrue(reread.values.all { it.updatedAtMillis == 1234L })
        mapping.keys.forEach { key ->
            assertEquals("translator:model-a:glossary-g1", key.providerId)
            assertEquals(DocumentIds.sha256(listOf(key.documentId, key.segmentId, key.sourceLanguage, key.targetLanguage, key.providerId).joinToString("|")), key.id)
        }
    }

    @Test
    fun refusesIncompleteOrWrongProviderCacheBeforeRemoteSave() = runTest {
        val mapping = mapping()
        val store = RecordingStore()
        val cache = RecordingCache(records(mapping).dropLast(1))
        expectRejected { TranslationCacheSyncService(store, cache).publish(mapping) }
        assertEquals(0, store.saveCalls)
        val good = records(mapping).associateBy { it.key.id }
        val key = mapping.keys.first()
        val spoofed = good + (key.id to good.getValue(key.id).copy(key = key.copy(providerId = "different-model")))
        assertThrows(IllegalArgumentException::class.java) { mapping.toArtifact(spoofed) }
    }

    @Test
    fun explicitMappingRejectsMissingRepeatedUnknownAndDifferentSourceSegments() {
        assertThrows(IllegalArgumentException::class.java) { mapping(mapOf("p-1" to "segment-a")) }
        assertThrows(IllegalArgumentException::class.java) { mapping(mapOf("p-1" to "segment-a", "p-2" to "segment-a")) }
        assertThrows(IllegalArgumentException::class.java) { mapping(mapOf("p-1" to "segment-a", "p-2" to "unknown")) }
        assertThrows(IllegalArgumentException::class.java) { mapping(mapOf("p-1" to "segment-b", "p-2" to "segment-a")) }
        assertThrows(IllegalArgumentException::class.java) { TranslationCacheChapterMapping(document, chapter, ids, variant.copy(sourceLanguage = "auto")) }
        assertThrows(IllegalArgumentException::class.java) { TranslationCacheChapterMapping(document, chapter.copy(paragraphs = emptyList()), emptyMap(), variant) }
    }

    @Test
    fun restoreRejectsEverySourceAndVariantMismatchBeforeWritingAnyCacheRecord() = runTest {
        val mapping = mapping()
        val artifact = mapping.toArtifact(records(mapping).associateBy { it.key.id })
        val invalid = listOf(
            artifact.copy(chapter = artifact.chapter.copy(chapterId = "another-chapter")),
            artifact.copy(sourceRevision = "another-revision"),
            artifact.copy(sourceLanguage = "ja"),
            artifact.copy(targetLanguage = "en"),
            artifact.copy(providerId = "another-translator"),
            artifact.copy(modelId = "another-model"),
            artifact.copy(promptRevision = "another-prompt"),
            artifact.copy(glossaryRevision = "another-glossary"),
            artifact.copy(paragraphs = artifact.paragraphs.take(1)),
            artifact.copy(paragraphs = artifact.paragraphs.reversed()),
            artifact.copy(paragraphs = listOf(TranslatedParagraph("other", "wrong")) + artifact.paragraphs.drop(1)),
        )
        val cache = RecordingCache()
        for (wrong in invalid) {
            val store = RecordingStore(StoredTranslation("record-1", wrong, "2026-09-14T00:00:00Z"))
            expectRejected { TranslationCacheSyncService(store, cache).restore("record-1", mapping) }
        }
        assertEquals(0, cache.writeCalls)
        assertTrue(cache.records.isEmpty())
    }

    @Test
    fun failedOrCancelledLookupDoesNotOverwriteExistingCache() = runTest {
        val mapping = mapping()
        val original = records(mapping)
        val cache = RecordingCache(original)
        for (failure in listOf(TranslationStoreException(TranslationStoreFailure.CONFLICT, 409), CancellationException("cancel"))) {
            val store = object : TranslationStore {
                override suspend fun save(artifact: TranslationArtifact): TranslationSaveResult = error("Not publishing")
                override suspend fun get(recordId: String): StoredTranslation = throw failure
            }
            try { TranslationCacheSyncService(store, cache).restore("record-1", mapping); error("Expected lookup failure") }
            catch (error: Exception) { assertTrue(error === failure) }
        }
        assertEquals(0, cache.writeCalls)
        assertEquals(original.associateBy { it.key.id }, cache.records)
    }

    @Test
    fun localConflictRejectsEntireRestoreUnlessReplacementIsExplicit() = runTest {
        val mapping = mapping()
        val original = records(mapping)
        val artifact = mapping.toArtifact(original.associateBy { it.key.id })
        val local = original.take(1).map { it.copy(text = "Locally edited translation") }
        val cache = RecordingCache(local)
        val store = RecordingStore(StoredTranslation("record-1", artifact, "2026-09-14T00:00:00Z"))
        val service = TranslationCacheSyncService(store, cache) { 55L }
        try { service.restore("record-1", mapping); error("Expected local conflict") }
        catch (_: TranslationCacheConflictException) { }
        assertEquals(0, cache.writeCalls)
        assertEquals(local.associateBy { it.key.id }, cache.records)
        service.restore("record-1", mapping, replaceExisting = true)
        assertEquals(1, cache.writeCalls)
        assertEquals(original.map { it.text }, mapping.keys.map { cache.records.getValue(it.id).text })
        service.restore("record-1", mapping)
        assertEquals(2, cache.writeCalls)
    }

    @Test
    fun mappingUsesExplicitSegmentIdsAcrossPageLayoutsAndSnapshotsInputCollections() {
        val mutableIds = ids.toMutableMap()
        val mapping = mapping(mutableIds)
        mutableIds.clear()
        assertEquals(listOf("segment-a", "segment-b"), mapping.keys.map { it.segmentId })
        val repaged = document.copy(pages = listOf(ReaderPage(99, document.pages.flatMap { it.segments })))
        val alternative = TranslationCacheChapterMapping(repaged, chapter, ids, variant)
        assertEquals(mapping.keys, alternative.keys)
    }

    private fun mapping(map: Map<String, String> = ids) = TranslationCacheChapterMapping(document, chapter, map, variant)
    private fun records(mapping: TranslationCacheChapterMapping) = mapping.keys.mapIndexed { index, key -> CachedTranslation(key, "번역 ${index + 1}", 10L) }
    private suspend fun expectRejected(block: suspend () -> Unit) {
        try { block(); error("Invalid synchronization must be rejected") } catch (_: IllegalArgumentException) { }
    }

    private class RecordingStore(var stored: StoredTranslation? = null) : TranslationStore {
        var saveCalls = 0
        override suspend fun save(artifact: TranslationArtifact): TranslationSaveResult {
            saveCalls++
            val old = stored
            if (old?.artifact == artifact) return TranslationSaveResult(old, false)
            return TranslationSaveResult(StoredTranslation("record-1", artifact, "2026-09-14T00:00:00Z").also { stored = it }, true)
        }
        override suspend fun get(recordId: String): StoredTranslation = requireNotNull(stored)
    }

    private class RecordingCache(initial: List<CachedTranslation> = emptyList()) : TranslationCache {
        val records = initial.associateBy { it.key.id }.toMutableMap()
        var writeCalls = 0
        override suspend fun getMany(keys: List<TranslationCacheKey>): Map<String, CachedTranslation> = records.filterKeys { id -> keys.any { it.id == id } }
        override suspend fun putAll(records: List<CachedTranslation>) { writeCalls++; this.records.putAll(records.associateBy { it.key.id }) }
        override suspend fun putAllIfCompatible(records: List<CachedTranslation>): Boolean {
            if (records.any { record -> this.records[record.key.id]?.let { it.key != record.key || it.text != record.text } == true }) return false
            putAll(records)
            return true
        }
        override suspend fun deleteMany(keys: List<TranslationCacheKey>): Int = keys.count { records.remove(it.id) != null }
    }

    companion object {
        private val chapter = ChapterContent(
            ChapterIdentity(BookIdentity("site", "book"), "chapter"), "Chapter", "en",
            listOf(ContentParagraph("p-1", 0, "First source."), ContentParagraph("p-2", 1, "Second source.")),
        )
        private val document = ReaderDocument("local-document", "Book", DocumentFormat.TEXT, listOf(
            ReaderPage(4, listOf(TextSegment("segment-a", 4, 0, "First source."))),
            ReaderPage(9, listOf(TextSegment("segment-b", 9, 0, "Second source."))),
        ))
        private val ids = mapOf("p-1" to "segment-a", "p-2" to "segment-b")
        private val variant = TranslationCacheVariant("en", "ko", "translator:model-a:glossary-g1", "translator", "model-a", "prompt-1", "g1")
    }
}
